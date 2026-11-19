package gh.edu.gctu.smartattend.admin;

import gh.edu.gctu.smartattend.attendance.*;
import gh.edu.gctu.smartattend.common.*;
import gh.edu.gctu.smartattend.face.FaceService;
import gh.edu.gctu.smartattend.reports.ReportRenderer;
import gh.edu.gctu.smartattend.reports.ReportService;
import gh.edu.gctu.smartattend.security.*;
import gh.edu.gctu.smartattend.student.StudentDirectory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/** Administrator API. Biometric templates are never returned by any endpoint: only enrolment status. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final Db db;
    private final AuditService audit;
    private final SettingsService settings;
    private final ImportService imports;
    private final AnalyticsService analytics;
    private final AcademicCalendar calendar;
    private final ReportService reports;
    private final ReportRenderer renderer;
    private final NotificationService notifications;
    private final SessionService sessions;
    private final FaceService face;

    public AdminController(Db db, AuditService audit, SettingsService settings, ImportService imports, AnalyticsService analytics,
                           AcademicCalendar calendar, ReportService reports, ReportRenderer renderer, NotificationService notifications,
                           SessionService sessions, FaceService face) {
        this.db = db;
        this.audit = audit;
        this.settings = settings;
        this.imports = imports;
        this.analytics = analytics;
        this.calendar = calendar;
        this.reports = reports;
        this.renderer = renderer;
        this.notifications = notifications;
        this.sessions = sessions;
        this.face = face;
    }

    private CurrentUser admin() { return AuthContext.requireRole(Role.ADMIN, Role.SUPER_ADMIN); }

    // ================================================================= overview & setup checks

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        admin();
        Map<String, Object> sem = calendar.currentSemester();
        Map<String, Object> counts = db.one("""
                SELECT (SELECT count(*) FROM students WHERE register_status = 'ACTIVE') AS register_size,
                       (SELECT count(*) FROM students s JOIN app_users u ON u.id = s.user_id WHERE u.status = 'ACTIVE') AS active_accounts,
                       (SELECT count(*) FROM students s JOIN app_users u ON u.id = s.user_id WHERE u.status = 'PENDING_APPROVAL') AS pending_approvals,
                       (SELECT count(*) FROM biometric_enrollments WHERE status = 'ACTIVE') AS biometric_enrolled,
                       (SELECT count(DISTINCT user_id) FROM webauthn_credentials WHERE revoked_at IS NULL) AS passkey_users,
                       (SELECT count(*) FROM attendance_sessions WHERE status = 'OPEN') AS open_sessions,
                       (SELECT count(*) FROM fraud_alerts WHERE status = 'OPEN') AS open_alerts,
                       (SELECT count(*) FROM recovery_requests WHERE status = 'PENDING') AS pending_recovery,
                       (SELECT count(*) FROM lecturers) AS lecturers,
                       (SELECT count(*) FROM courses) AS courses""");
        var stats = analytics.studentCourseStats((UUID) sem.get("id"), null, null, null);
        return Json.obj("semester", sem, "counts", counts, "groups", groups(), "warnings", setupWarnings(sem),
                "summary", analytics.summarise(stats), "trend", analytics.weeklyTrend((UUID) sem.get("id"), null, null, null));
    }

    private List<Map<String, Object>> setupWarnings(Map<String, Object> sem) {
        List<Map<String, Object>> w = new ArrayList<>();
        if (!face.engine().available()) w.add(Json.obj("level", "HIGH", "message", "Facial verification is unavailable: " + face.engine().unavailableReason()));
        if (sem.get("startDate") == null) w.add(Json.obj("level", "MEDIUM", "link", "#/timetable",
                "message", "Semester start date is not set, so teaching-week venue changes (e.g. Linear Algebra weeks 2, 6, 10) cannot be applied."));
        for (var g : groups()) {
            if (!"VERIFIED".equals(g.get("registerStatus"))) {
                w.add(Json.obj("level", "HIGH", "link", "#/register",
                        "message", "Register for " + g.get("programme") + " L" + g.get("level") + " " + g.get("name") + " is not verified (" +
                                g.get("actualSize") + " records" + (g.get("expectedSize") != null ? " vs " + g.get("expectedSize") + " expected" : "") +
                                "). Students cannot register and attendance cannot start until it is verified."));
            }
        }
        Integer noCred = db.scalar("SELECT count(*)::int FROM lecturers l JOIN app_users u ON u.id = l.user_id WHERE u.password_hash IS NULL", Integer.class);
        if (noCred != null && noCred > 0) w.add(Json.obj("level", "MEDIUM", "link", "#/lecturers", "message", noCred + " lecturer(s) have no sign-in credentials yet."));
        Integer noCode = db.scalar("SELECT count(*)::int FROM courses WHERE code IS NULL", Integer.class);
        if (noCode != null && noCode > 0) w.add(Json.obj("level", "LOW", "link", "#/courses", "message", noCode + " course(s) have no course code confirmed."));
        Integer noGeo = db.scalar("SELECT count(*)::int FROM venues WHERE latitude IS NULL AND cardinality(network_cidrs) = 0", Integer.class);
        if (noGeo != null && noGeo > 0) w.add(Json.obj("level", "LOW", "link", "#/venues",
                "message", noGeo + " venue(s) have no GPS coordinates or classroom network configured; proximity evidence will be 'not evaluated'."));
        return w;
    }

    // ================================================================= class groups & register verification

    @GetMapping("/groups")
    public List<Map<String, Object>> groups() {
        return db.list("""
                SELECT g.id, g.name, g.level, g.expected_size, g.expected_size_source, g.register_status, g.register_verified_at,
                       g.register_verification_note, p.name AS programme,
                       (SELECT count(*)::int FROM students s WHERE s.class_group_id = g.id AND s.register_status = 'ACTIVE') AS actual_size,
                       (SELECT count(*)::int FROM students s WHERE s.class_group_id = g.id) AS total_records
                FROM class_groups g JOIN programmes p ON p.id = g.programme_id ORDER BY p.name, g.level, g.name""");
    }

    public record VerifyBody(String note, Boolean acknowledgeDiscrepancy) {}

    @PostMapping("/groups/{id}/verify")
    public Map<String, Object> verifyRegister(@PathVariable UUID id, @RequestBody VerifyBody body) {
        CurrentUser u = admin();
        Map<String, Object> g = db.one("""
                SELECT expected_size, (SELECT count(*)::int FROM students WHERE class_group_id = :g AND register_status = 'ACTIVE') AS actual
                FROM class_groups WHERE id = :g""", "g", id);
        if (g == null) throw ApiException.notFound("NOT_FOUND", "Class group not found.");
        int actual = (Integer) g.get("actual");
        if (actual == 0) throw ApiException.badRequest("EMPTY_REGISTER", "Import the student register before verifying it.");
        Integer expected = (Integer) g.get("expectedSize");
        boolean mismatch = expected != null && expected != actual;
        String note = body.note() == null ? "" : body.note().trim();
        if (mismatch && (!Boolean.TRUE.equals(body.acknowledgeDiscrepancy()) || note.length() < 10)) {
            throw ApiException.badRequest("DISCREPANCY_UNRESOLVED", "The register has " + actual + " active students but " + expected +
                    " are expected. Reconcile the records (mark withdrawn/under review) or acknowledge the discrepancy with an explanation of at least 10 characters.");
        }
        db.update("""
                UPDATE class_groups SET register_status = 'VERIFIED', register_verified_by = :u, register_verified_at = now(),
                    register_verification_note = :n WHERE id = :g""", "u", u.userId(), "n", note, "g", id);
        audit.log("REGISTER_VERIFIED", "class_group", id, Json.obj("expected", expected, "actual", actual, "note", note, "mismatch", mismatch));
        return Json.obj("verified", true);
    }

    public record GroupUpdate(Integer expectedSize, String source) {}

    @PutMapping("/groups/{id}")
    public Map<String, Object> updateGroup(@PathVariable UUID id, @RequestBody GroupUpdate b) {
        admin();
        if (b.expectedSize() != null && (b.expectedSize() < 1 || b.expectedSize() > 5000)) throw ApiException.badRequest("INVALID", "Invalid size.");
        db.update("""
                UPDATE class_groups SET expected_size = :e, expected_size_source = :s, register_status = 'UNVERIFIED',
                    register_verified_at = NULL, register_verified_by = NULL WHERE id = :g""",
                "e", b.expectedSize(), "s", b.source(), "g", id);
        audit.log("GROUP_EXPECTED_SIZE_CHANGED", "class_group", id, Json.obj("expectedSize", b.expectedSize(), "source", b.source()));
        return Json.obj("updated", true);
    }

    // ================================================================= import

    @PostMapping(value = "/imports", consumes = "multipart/form-data")
    public Map<String, Object> importPreview(@RequestParam("file") MultipartFile file, @RequestParam UUID classGroupId,
                                             @RequestParam(required = false) String sheetName,
                                             @RequestParam(defaultValue = "true") boolean enrolAllCourses) throws IOException {
        return imports.preview(admin(), file, classGroupId, sheetName, enrolAllCourses);
    }

    @PostMapping("/imports/{id}/commit")
    public Map<String, Object> importCommit(@PathVariable UUID id) {
        return imports.commit(admin(), id);
    }

    @PostMapping("/imports/{id}/cancel")
    public Map<String, Object> importCancel(@PathVariable UUID id) {
        admin();
        int n = db.update("UPDATE import_batches SET status = 'CANCELLED' WHERE id = :id AND status = 'PREVIEW'", "id", id);
        if (n == 0) throw ApiException.conflict("BATCH_DONE", "Import cannot be cancelled.");
        audit.log("REGISTER_IMPORT_CANCELLED", "import_batch", id, Json.obj());
        return Json.obj("cancelled", true);
    }

    @GetMapping("/imports")
    public List<Map<String, Object>> importsList() {
        admin();
        return db.list("""
                SELECT b.id, b.filename, b.sheet_name, b.row_count, b.valid_count, b.invalid_count, b.duplicate_count, b.status,
                       b.created_at, b.committed_at, g.name AS group_name, coalesce(a.full_name, u.email) AS uploaded_by
                FROM import_batches b JOIN class_groups g ON g.id = b.class_group_id JOIN app_users u ON u.id = b.uploaded_by
                LEFT JOIN administrators a ON a.user_id = u.id ORDER BY b.created_at DESC LIMIT 50""");
    }

    // ================================================================= students

    @GetMapping("/students")
    public Map<String, Object> students(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                        @RequestParam(required = false) UUID groupId, @RequestParam(defaultValue = "0") int page) {
        admin();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase().replace("%", "").replace("_", "") + "%";
        String filter = """
                FROM students s JOIN class_groups g ON g.id = s.class_group_id LEFT JOIN app_users u ON u.id = s.user_id
                WHERE (CAST(:q AS text) IS NULL OR lower(s.index_number) LIKE :q
                       OR lower(s.first_name || ' ' || coalesce(s.other_names,'') || ' ' || s.last_name) LIKE :q
                       OR lower(s.last_name || ' ' || s.first_name) LIKE :q)
                  AND (CAST(:g AS uuid) IS NULL OR s.class_group_id = :g)
                  AND (CAST(:st AS text) IS NULL
                       OR (:st = 'NOT_REGISTERED' AND (u.id IS NULL OR u.status = 'DISABLED'))
                       OR u.status = :st OR s.register_status = :st)""";
        Object st = status == null || status.isBlank() ? null : status;
        Integer total = db.scalar("SELECT count(*)::int " + filter, Integer.class, "q", like, "g", groupId, "st", st);
        List<Map<String, Object>> rows = db.list("""
                SELECT s.id, s.index_number, s.title, s.first_name, s.last_name, s.other_names, s.register_status, g.name AS group_name,
                       CASE WHEN u.id IS NULL OR u.status = 'DISABLED' THEN 'NOT_REGISTERED' ELSE u.status END AS account_status,
                       EXISTS (SELECT 1 FROM biometric_enrollments b WHERE b.student_id = s.id AND b.status = 'ACTIVE') AS biometric_enrolled,
                       (SELECT count(*) FROM webauthn_credentials w WHERE w.user_id = u.id AND w.revoked_at IS NULL) AS passkeys
                """ + filter + " ORDER BY s.last_name, s.first_name LIMIT 100 OFFSET :off",
                "q", like, "g", groupId, "st", st, "off", Math.max(0, page) * 100);
        for (var r : rows) r.put("name", StudentDirectory.fullName(r));
        return Json.obj("total", total, "page", page, "pageSize", 100, "rows", rows);
    }

    @GetMapping("/students/{id}")
    public Map<String, Object> student(@PathVariable UUID id) {
        admin();
        Map<String, Object> s = db.one("""
                SELECT s.*, g.name AS group_name, u.status AS account_status, u.created_at AS account_created_at, u.last_login_at
                FROM students s JOIN class_groups g ON g.id = s.class_group_id LEFT JOIN app_users u ON u.id = s.user_id WHERE s.id = :id""", "id", id);
        if (s == null) throw ApiException.notFound("STUDENT_NOT_FOUND", "Student not found.");
        s.put("name", StudentDirectory.fullName(s));
        UUID userId = (UUID) s.get("userId");
        audit.log("STUDENT_PROFILE_VIEWED", "student", id, Json.obj());
        return Json.obj("student", s,
                "biometric", db.list("""
                        SELECT provider, model_version, status, quality_score, consent_version, consent_given_at, created_at, retention_until, revoked_at
                        FROM biometric_enrollments WHERE student_id = :s ORDER BY created_at DESC""", "s", id),
                "devices", userId == null ? List.of() : db.list("""
                        SELECT d.id, d.label, d.status, d.first_seen_at, d.last_seen_at,
                               (SELECT count(*) FROM webauthn_credentials w WHERE w.device_id = d.id AND w.revoked_at IS NULL) AS passkeys
                        FROM devices d WHERE d.user_id = :u ORDER BY d.last_seen_at DESC""", "u", userId),
                "stats", analytics.studentCourseStats(calendar.currentSemesterId(), null, id, null),
                "enrolments", db.list("""
                        SELECT e.id, e.status, c.id AS course_id, c.code, c.title FROM course_enrollments e JOIN courses c ON c.id = e.course_id
                        WHERE e.student_id = :s AND e.semester_id = :sem ORDER BY c.code""", "s", id, "sem", calendar.currentSemesterId()),
                "recovery", db.list("SELECT id, kind, reason, status, face_verified, created_at, review_note FROM recovery_requests WHERE student_id = :s ORDER BY created_at DESC", "s", id),
                "alerts", db.list("SELECT id, kind, severity, status, created_at FROM fraud_alerts WHERE student_id = :s ORDER BY created_at DESC LIMIT 20", "s", id),
                "faceAttempts", db.list("""
                        SELECT purpose, passed, failure, round(score::numeric, 3) AS score, used_at FROM face_challenges
                        WHERE student_id = :s AND used_at IS NOT NULL ORDER BY used_at DESC LIMIT 20""", "s", id));
    }

    public record StudentBody(UUID classGroupId, String indexNumber, String title, String firstName, String lastName,
                              String otherNames, String registerStatus, String reason) {}

    @PostMapping("/students")
    @Transactional
    public Map<String, Object> addStudent(@RequestBody StudentBody b) {
        admin();
        String idx = StudentDirectory.normaliseIndex(b.indexNumber());
        if (!idx.matches("[A-Za-z0-9/._-]{3,40}")) throw ApiException.badRequest("INVALID_INDEX", "Invalid index number.");
        if (blank(b.firstName()) || blank(b.lastName())) throw ApiException.badRequest("NAME_REQUIRED", "First and last name are required.");
        if (b.classGroupId() == null) throw ApiException.badRequest("GROUP_REQUIRED", "Choose a class.");
        UUID id = db.insertReturningId("""
                INSERT INTO students(class_group_id, index_number, title, first_name, last_name, other_names)
                VALUES (:g, :i, :t, :f, :l, :o) RETURNING id""",
                "g", b.classGroupId(), "i", idx, "t", nz(b.title()), "f", b.firstName().trim(), "l", b.lastName().trim(), "o", nz(b.otherNames()));
        UUID sem = db.scalar("SELECT semester_id FROM class_groups WHERE id = :g", UUID.class, "g", b.classGroupId());
        db.update("""
                INSERT INTO course_enrollments(course_id, student_id, semester_id)
                SELECT DISTINCT course_id, :s, semester_id FROM class_timetables WHERE class_group_id = :g AND semester_id = :sem
                ON CONFLICT DO NOTHING""", "s", id, "g", b.classGroupId(), "sem", sem);
        db.update("UPDATE class_groups SET register_status = 'UNVERIFIED' WHERE id = :g", "g", b.classGroupId());
        audit.log("STUDENT_ADDED", "student", id, Json.obj("indexNumber", idx, "reason", b.reason()));
        return Json.obj("id", id);
    }

    /** Correction of register data (source errors). Every change is audited with before/after values. */
    @PutMapping("/students/{id}")
    @Transactional
    public Map<String, Object> updateStudent(@PathVariable UUID id, @RequestBody StudentBody b) {
        admin();
        Map<String, Object> before = db.one("SELECT index_number, title, first_name, last_name, other_names, register_status, class_group_id FROM students WHERE id = :id FOR UPDATE", "id", id);
        if (before == null) throw ApiException.notFound("STUDENT_NOT_FOUND", "Student not found.");
        if (blank(b.reason()) || b.reason().trim().length() < 5) throw ApiException.badRequest("REASON_REQUIRED", "Give a reason for the correction.");
        if (blank(b.firstName()) || blank(b.lastName())) throw ApiException.badRequest("NAME_REQUIRED", "First and last name are required.");
        String rs = b.registerStatus() == null ? (String) before.get("registerStatus") : b.registerStatus();
        if (!Set.of("ACTIVE", "UNDER_REVIEW", "WITHDRAWN").contains(rs)) throw ApiException.badRequest("INVALID_STATUS", "Invalid register status.");
        db.update("""
                UPDATE students SET title = :t, first_name = :f, last_name = :l, other_names = :o, register_status = :rs, updated_at = now()
                WHERE id = :id""", "t", nz(b.title()), "f", b.firstName().trim(), "l", b.lastName().trim(), "o", nz(b.otherNames()), "rs", rs, "id", id);
        if (!rs.equals(before.get("registerStatus"))) {
            db.update("UPDATE class_groups SET register_status = 'UNVERIFIED' WHERE id = :g", "g", before.get("classGroupId"));
        }
        audit.log("STUDENT_RECORD_CORRECTED", "student", id, Json.obj("before", before,
                "after", Json.obj("title", b.title(), "firstName", b.firstName(), "lastName", b.lastName(), "otherNames", b.otherNames(), "registerStatus", rs),
                "reason", b.reason()));
        return Json.obj("updated", true);
    }

    public record ReasonBody(String reason) {}

    @PostMapping("/students/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) ReasonBody b) {
        admin();
        UUID userId = db.scalar("SELECT user_id FROM students WHERE id = :id", UUID.class, "id", id);
        int n = userId == null ? 0 : db.update("UPDATE app_users SET status = 'ACTIVE' WHERE id = :u AND status = 'PENDING_APPROVAL'", "u", userId);
        if (n == 0) throw ApiException.conflict("NOT_PENDING", "This account is not awaiting approval.");
        audit.log("STUDENT_ACCOUNT_APPROVED", "student", id, Json.obj("note", b == null ? null : b.reason()));
        notifications.notify(userId, "Account verified", "Your SmartAttend account has been verified. You can now mark attendance.", "/student.html");
        return Json.obj("approved", true);
    }

    @PostMapping("/students/{id}/suspend")
    public Map<String, Object> suspend(@PathVariable UUID id, @RequestBody ReasonBody b) {
        admin();
        if (b == null || blank(b.reason()) || b.reason().trim().length() < 5) throw ApiException.badRequest("REASON_REQUIRED", "A justification is required to suspend an account.");
        UUID userId = db.scalar("SELECT user_id FROM students WHERE id = :id", UUID.class, "id", id);
        if (userId == null) throw ApiException.conflict("NOT_REGISTERED", "This student has not registered.");
        db.update("UPDATE app_users SET status = 'SUSPENDED' WHERE id = :u", "u", userId);
        sessions.revokeAllForUser(userId);
        audit.log("STUDENT_ACCOUNT_SUSPENDED", "student", id, Json.obj("reason", b.reason()));
        return Json.obj("suspended", true);
    }

    @PostMapping("/students/{id}/reactivate")
    public Map<String, Object> reactivate(@PathVariable UUID id, @RequestBody ReasonBody b) {
        admin();
        UUID userId = db.scalar("SELECT user_id FROM students WHERE id = :id", UUID.class, "id", id);
        int n = userId == null ? 0 : db.update("UPDATE app_users SET status = 'ACTIVE', failed_logins = 0, locked_until = NULL WHERE id = :u AND status = 'SUSPENDED'", "u", userId);
        if (n == 0) throw ApiException.conflict("NOT_SUSPENDED", "This account is not suspended.");
        audit.log("STUDENT_ACCOUNT_REACTIVATED", "student", id, Json.obj("reason", b == null ? null : b.reason()));
        return Json.obj("reactivated", true);
    }

    /** Erase the face template (data-subject request / retention). Student re-enrols at next sign-in. */
    @PostMapping("/students/{id}/biometric/delete")
    public Map<String, Object> deleteBiometric(@PathVariable UUID id, @RequestBody ReasonBody b) {
        admin();
        if (b == null || blank(b.reason())) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required.");
        face.deleteBiometrics(id, b.reason());
        return Json.obj("deleted", true);
    }

    // ================================================================= lecturers

    @GetMapping("/lecturers")
    public List<Map<String, Object>> lecturers() {
        admin();
        return db.list("""
                SELECT l.id, l.title, l.full_name, l.staff_id, u.email, u.status, u.password_hash IS NOT NULL AS has_credentials,
                       u.must_change_password, u.last_login_at,
                       (SELECT json_agg(json_build_object('id', c.id, 'code', c.code, 'title', c.title)) FROM course_assignments a
                          JOIN courses c ON c.id = a.course_id WHERE a.lecturer_id = l.id) AS courses
                FROM lecturers l JOIN app_users u ON u.id = l.user_id ORDER BY l.full_name""");
    }

    public record LecturerBody(String title, String fullName, String email, String staffId) {}

    @PostMapping("/lecturers")
    @Transactional
    public Map<String, Object> addLecturer(@RequestBody LecturerBody b) {
        admin();
        if (blank(b.fullName())) throw ApiException.badRequest("NAME_REQUIRED", "Name is required.");
        String email = validEmail(b.email());
        UUID uid = db.insertReturningId("INSERT INTO app_users(role, email, status) VALUES ('LECTURER', :e, 'ACTIVE') RETURNING id", "e", email);
        UUID id = db.insertReturningId("INSERT INTO lecturers(user_id, title, full_name, staff_id) VALUES (:u, :t, :n, :s) RETURNING id",
                "u", uid, "t", nz(b.title()), "n", b.fullName().trim(), "s", nz(b.staffId()));
        audit.log("LECTURER_CREATED", "lecturer", id, Json.obj("name", b.fullName(), "email", email));
        return Json.obj("id", id);
    }

    @PutMapping("/lecturers/{id}")
    public Map<String, Object> updateLecturer(@PathVariable UUID id, @RequestBody LecturerBody b) {
        admin();
        if (blank(b.fullName())) throw ApiException.badRequest("NAME_REQUIRED", "Name is required.");
        UUID uid = db.scalar("SELECT user_id FROM lecturers WHERE id = :id", UUID.class, "id", id);
        if (uid == null) throw ApiException.notFound("NOT_FOUND", "Lecturer not found.");
        db.update("UPDATE lecturers SET title = :t, full_name = :n, staff_id = :s WHERE id = :id", "t", nz(b.title()), "n", b.fullName().trim(), "s", nz(b.staffId()), "id", id);
        db.update("UPDATE app_users SET email = :e WHERE id = :u", "e", validEmail(b.email()), "u", uid);
        audit.log("LECTURER_UPDATED", "lecturer", id, Json.obj("name", b.fullName(), "email", b.email()));
        return Json.obj("updated", true);
    }

    /** Issue a one-time temporary password (shown once). The lecturer must change it at first sign-in. */
    @PostMapping("/lecturers/{id}/credentials")
    public Map<String, Object> issueCredentials(@PathVariable UUID id) {
        admin();
        Map<String, Object> l = db.one("SELECT l.user_id, u.email FROM lecturers l JOIN app_users u ON u.id = l.user_id WHERE l.id = :id", "id", id);
        if (l == null) throw ApiException.notFound("NOT_FOUND", "Lecturer not found.");
        if (l.get("email") == null) throw ApiException.badRequest("EMAIL_REQUIRED", "Set the lecturer's email address first.");
        String temp = temporaryPassword();
        db.update("UPDATE app_users SET password_hash = :h, must_change_password = true, failed_logins = 0, locked_until = NULL WHERE id = :u",
                "h", AccountController.BCRYPT.encode(temp), "u", l.get("userId"));
        sessions.revokeAllForUser((UUID) l.get("userId"));
        audit.log("LECTURER_CREDENTIALS_ISSUED", "lecturer", id, Json.obj());
        return Json.obj("email", l.get("email"), "temporaryPassword", temp,
                "note", "Share this temporary password securely. It is shown only once and must be changed at first sign-in.");
    }

    @PostMapping("/lecturers/{id}/status")
    public Map<String, Object> lecturerStatus(@PathVariable UUID id, @RequestParam String status) {
        admin();
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) throw ApiException.badRequest("INVALID_STATUS", "Invalid status.");
        UUID uid = db.scalar("SELECT user_id FROM lecturers WHERE id = :id", UUID.class, "id", id);
        if (uid == null) throw ApiException.notFound("NOT_FOUND", "Lecturer not found.");
        db.update("UPDATE app_users SET status = :s WHERE id = :u", "s", status, "u", uid);
        if ("DISABLED".equals(status)) sessions.revokeAllForUser(uid);
        audit.log("LECTURER_STATUS_CHANGED", "lecturer", id, Json.obj("status", status));
        return Json.obj("status", status);
    }

    // ================================================================= courses, assignments, enrolments

    @GetMapping("/courses")
    public List<Map<String, Object>> courses() {
        admin();
        UUID sem = calendar.currentSemesterId();
        return db.list("""
                SELECT c.id, c.code, c.alt_code, c.title, p.name AS programme,
                       (SELECT count(*) FROM course_enrollments e WHERE e.course_id = c.id AND e.semester_id = :sem AND e.status = 'ENROLLED') AS enrolled,
                       (SELECT json_agg(json_build_object('lecturerId', l.id, 'name', trim(coalesce(l.title || ' ', '') || l.full_name)))
                          FROM course_assignments a JOIN lecturers l ON l.id = a.lecturer_id WHERE a.course_id = c.id AND a.semester_id = :sem) AS lecturers
                FROM courses c JOIN programmes p ON p.id = c.programme_id ORDER BY c.code NULLS LAST, c.title""", "sem", sem);
    }

    public record CourseBody(UUID programmeId, String code, String altCode, String title) {}

    @PostMapping("/courses")
    public Map<String, Object> addCourse(@RequestBody CourseBody b) {
        admin();
        if (blank(b.title())) throw ApiException.badRequest("TITLE_REQUIRED", "Course title is required.");
        UUID prog = b.programmeId() != null ? b.programmeId() : db.scalar("SELECT id FROM programmes ORDER BY created_at LIMIT 1", UUID.class);
        UUID id = db.insertReturningId("INSERT INTO courses(programme_id, code, alt_code, title) VALUES (:p, :c, :a, :t) RETURNING id",
                "p", prog, "c", nz(b.code()), "a", nz(b.altCode()), "t", b.title().trim());
        audit.log("COURSE_CREATED", "course", id, Json.obj("code", b.code(), "title", b.title()));
        return Json.obj("id", id);
    }

    @PutMapping("/courses/{id}")
    public Map<String, Object> updateCourse(@PathVariable UUID id, @RequestBody CourseBody b) {
        admin();
        if (blank(b.title())) throw ApiException.badRequest("TITLE_REQUIRED", "Course title is required.");
        Map<String, Object> before = db.one("SELECT code, alt_code, title FROM courses WHERE id = :id", "id", id);
        if (before == null) throw ApiException.notFound("NOT_FOUND", "Course not found.");
        db.update("UPDATE courses SET code = :c, alt_code = :a, title = :t WHERE id = :id", "c", nz(b.code()), "a", nz(b.altCode()), "t", b.title().trim(), "id", id);
        audit.log("COURSE_UPDATED", "course", id, Json.obj("before", before, "after", b));
        return Json.obj("updated", true);
    }

    public record AssignBody(UUID lecturerId) {}

    @PostMapping("/courses/{id}/assign")
    public Map<String, Object> assign(@PathVariable UUID id, @RequestBody AssignBody b) {
        admin();
        db.update("INSERT INTO course_assignments(course_id, lecturer_id, semester_id) VALUES (:c, :l, :s) ON CONFLICT DO NOTHING",
                "c", id, "l", b.lecturerId(), "s", calendar.currentSemesterId());
        audit.log("COURSE_ASSIGNED", "course", id, Json.obj("lecturerId", b.lecturerId()));
        return Json.obj("assigned", true);
    }

    @DeleteMapping("/courses/{id}/assign/{lecturerId}")
    public Map<String, Object> unassign(@PathVariable UUID id, @PathVariable UUID lecturerId) {
        admin();
        db.update("DELETE FROM course_assignments WHERE course_id = :c AND lecturer_id = :l AND semester_id = :s",
                "c", id, "l", lecturerId, "s", calendar.currentSemesterId());
        audit.log("COURSE_UNASSIGNED", "course", id, Json.obj("lecturerId", lecturerId));
        return Json.obj("unassigned", true);
    }

    public record EnrolGroupBody(UUID classGroupId) {}

    @PostMapping("/courses/{id}/enrol-group")
    public Map<String, Object> enrolGroup(@PathVariable UUID id, @RequestBody EnrolGroupBody b) {
        admin();
        int n = db.update("""
                INSERT INTO course_enrollments(course_id, student_id, semester_id)
                SELECT :c, s.id, g.semester_id FROM students s JOIN class_groups g ON g.id = s.class_group_id
                WHERE s.class_group_id = :g AND s.register_status = 'ACTIVE' ON CONFLICT DO NOTHING""", "c", id, "g", b.classGroupId());
        audit.log("COURSE_GROUP_ENROLLED", "course", id, Json.obj("classGroupId", b.classGroupId(), "added", n));
        return Json.obj("added", n);
    }

    @PostMapping("/enrolments/{enrolmentId}")
    public Map<String, Object> enrolmentStatus(@PathVariable UUID enrolmentId, @RequestParam String status) {
        admin();
        if (!Set.of("ENROLLED", "DROPPED").contains(status)) throw ApiException.badRequest("INVALID_STATUS", "Invalid status.");
        db.update("UPDATE course_enrollments SET status = :s WHERE id = :id", "s", status, "id", enrolmentId);
        audit.log("ENROLMENT_STATUS_CHANGED", "course_enrollment", enrolmentId, Json.obj("status", status));
        return Json.obj("status", status);
    }

    // ================================================================= timetable, venues, semester

    @GetMapping("/timetable")
    public Map<String, Object> timetable() {
        admin();
        Map<String, Object> sem = calendar.currentSemester();
        return Json.obj("semester", sem, "currentWeek", calendar.weekNumber(sem, calendar.today()),
                "slots", db.list("""
                        SELECT t.id, t.course_id, t.class_group_id, t.day_of_week, t.start_time, t.end_time, t.venue_id, t.mode, t.notes,
                               c.code AS course_code, c.title AS course_title, v.code AS venue, g.name AS group_name
                        FROM class_timetables t JOIN courses c ON c.id = t.course_id LEFT JOIN venues v ON v.id = t.venue_id
                        JOIN class_groups g ON g.id = t.class_group_id WHERE t.semester_id = :s ORDER BY t.day_of_week, t.start_time""", "s", sem.get("id")),
                "exceptions", db.list("""
                        SELECT e.id, e.timetable_id, e.week_number, e.mode, e.cancelled, e.note, e.venue_id, v.code AS venue
                        FROM timetable_exceptions e JOIN class_timetables t ON t.id = e.timetable_id LEFT JOIN venues v ON v.id = e.venue_id
                        WHERE t.semester_id = :s ORDER BY e.week_number""", "s", sem.get("id")),
                "venues", venues(), "groups", groups(),
                "weeks", db.list("SELECT id, week_number, kind, note FROM academic_weeks WHERE semester_id = :s ORDER BY week_number", "s", sem.get("id")));
    }

    public record SlotBody(UUID courseId, UUID classGroupId, Integer dayOfWeek, String startTime, String endTime, UUID venueId, String mode, String notes) {}

    @PostMapping("/timetable")
    public Map<String, Object> addSlot(@RequestBody SlotBody b) {
        admin();
        validateSlot(b);
        UUID id = db.insertReturningId("""
                INSERT INTO class_timetables(course_id, class_group_id, semester_id, day_of_week, start_time, end_time, venue_id, mode, notes)
                VALUES (:c, :g, :s, :d, CAST(:st AS time), CAST(:et AS time), :v, :m, :n) RETURNING id""",
                "c", b.courseId(), "g", b.classGroupId(), "s", calendar.currentSemesterId(), "d", b.dayOfWeek(), "st", b.startTime(),
                "et", b.endTime(), "v", "ONLINE".equals(b.mode()) ? null : b.venueId(), "m", b.mode(), "n", nz(b.notes()));
        audit.log("TIMETABLE_SLOT_CREATED", "class_timetable", id, Json.obj("slot", b));
        return Json.obj("id", id);
    }

    @PutMapping("/timetable/{id}")
    public Map<String, Object> updateSlot(@PathVariable UUID id, @RequestBody SlotBody b) {
        admin();
        validateSlot(b);
        int n = db.update("""
                UPDATE class_timetables SET course_id = :c, class_group_id = :g, day_of_week = :d, start_time = CAST(:st AS time),
                    end_time = CAST(:et AS time), venue_id = :v, mode = :m, notes = :n WHERE id = :id""",
                "c", b.courseId(), "g", b.classGroupId(), "d", b.dayOfWeek(), "st", b.startTime(), "et", b.endTime(),
                "v", "ONLINE".equals(b.mode()) ? null : b.venueId(), "m", b.mode(), "n", nz(b.notes()), "id", id);
        if (n == 0) throw ApiException.notFound("NOT_FOUND", "Timetable slot not found.");
        audit.log("TIMETABLE_SLOT_UPDATED", "class_timetable", id, Json.obj("slot", b));
        return Json.obj("updated", true);
    }

    @DeleteMapping("/timetable/{id}")
    public Map<String, Object> deleteSlot(@PathVariable UUID id) {
        admin();
        Integer used = db.scalar("SELECT count(*)::int FROM attendance_sessions WHERE timetable_id = :id", Integer.class, "id", id);
        if (used != null && used > 0) throw ApiException.conflict("IN_USE", "This slot has attendance history and cannot be deleted. Edit it instead.");
        db.update("DELETE FROM class_timetables WHERE id = :id", "id", id);
        audit.log("TIMETABLE_SLOT_DELETED", "class_timetable", id, Json.obj());
        return Json.obj("deleted", true);
    }

    public record ExceptionBody(UUID timetableId, Integer weekNumber, UUID venueId, String mode, Boolean cancelled, String note) {}

    @PostMapping("/timetable/exceptions")
    public Map<String, Object> addException(@RequestBody ExceptionBody b) {
        admin();
        if (b.timetableId() == null || b.weekNumber() == null || b.weekNumber() < 1 || b.weekNumber() > 30) throw ApiException.badRequest("INVALID", "Invalid week.");
        if (b.mode() != null && !Set.of("IN_PERSON", "ONLINE").contains(b.mode())) throw ApiException.badRequest("INVALID_MODE", "Invalid mode.");
        UUID id = db.insertReturningId("""
                INSERT INTO timetable_exceptions(timetable_id, week_number, venue_id, mode, cancelled, note) VALUES (:t, :w, :v, :m, :c, :n)
                ON CONFLICT (timetable_id, week_number) DO UPDATE SET venue_id = EXCLUDED.venue_id, mode = EXCLUDED.mode,
                    cancelled = EXCLUDED.cancelled, note = EXCLUDED.note RETURNING id""",
                "t", b.timetableId(), "w", b.weekNumber(), "v", b.venueId(), "m", nz(b.mode()), "c", Boolean.TRUE.equals(b.cancelled()), "n", nz(b.note()));
        audit.log("TIMETABLE_EXCEPTION_SAVED", "timetable_exception", id, Json.obj("exception", b));
        return Json.obj("id", id);
    }

    @DeleteMapping("/timetable/exceptions/{id}")
    public Map<String, Object> deleteException(@PathVariable UUID id) {
        admin();
        db.update("DELETE FROM timetable_exceptions WHERE id = :id", "id", id);
        audit.log("TIMETABLE_EXCEPTION_DELETED", "timetable_exception", id, Json.obj());
        return Json.obj("deleted", true);
    }

    public record SemesterBody(String startDate, String endDate, Integer teachingWeeks) {}

    @PutMapping("/semesters/current")
    public Map<String, Object> updateSemester(@RequestBody SemesterBody b) {
        admin();
        UUID id = calendar.currentSemesterId();
        LocalDate start = blank(b.startDate()) ? null : LocalDate.parse(b.startDate());
        LocalDate end = blank(b.endDate()) ? null : LocalDate.parse(b.endDate());
        if (start != null && end != null && !end.isAfter(start)) throw ApiException.badRequest("INVALID_DATES", "End date must be after start date.");
        int weeks = b.teachingWeeks() == null ? 13 : b.teachingWeeks();
        if (weeks < 1 || weeks > 30) throw ApiException.badRequest("INVALID", "Teaching weeks must be 1-30.");
        db.update("UPDATE semesters SET start_date = :s, end_date = :e, teaching_weeks = :w WHERE id = :id", "s", start, "e", end, "w", weeks, "id", id);
        audit.log("SEMESTER_UPDATED", "semester", id, Json.obj("startDate", b.startDate(), "endDate", b.endDate(), "teachingWeeks", weeks));
        return Json.obj("updated", true);
    }

    public record WeekBody(Integer weekNumber, String kind, String note) {}

    @PostMapping("/semesters/current/weeks")
    public Map<String, Object> saveWeek(@RequestBody WeekBody b) {
        admin();
        if (b.weekNumber() == null || b.weekNumber() < 1 || b.weekNumber() > 30) throw ApiException.badRequest("INVALID", "Invalid week.");
        if (!Set.of("TEACHING", "BREAK", "EXAM", "HOLIDAY").contains(b.kind())) throw ApiException.badRequest("INVALID", "Invalid week type.");
        db.update("""
                INSERT INTO academic_weeks(semester_id, week_number, kind, note) VALUES (:s, :w, :k, :n)
                ON CONFLICT (semester_id, week_number) DO UPDATE SET kind = EXCLUDED.kind, note = EXCLUDED.note""",
                "s", calendar.currentSemesterId(), "w", b.weekNumber(), "k", b.kind(), "n", nz(b.note()));
        audit.log("ACADEMIC_WEEK_SAVED", "semester", calendar.currentSemesterId(), Json.obj("week", b));
        return Json.obj("saved", true);
    }

    @GetMapping("/venues")
    public List<Map<String, Object>> venues() {
        return db.list("""
                SELECT v.id, v.code, v.description, v.latitude, v.longitude, v.radius_m, array_to_string(v.network_cidrs, ', ') AS network_cidrs,
                       (SELECT count(*) FROM proximity_devices p WHERE p.venue_id = v.id AND p.active) AS beacons
                FROM venues v ORDER BY v.code""");
    }

    public record VenueBody(String code, String description, Double latitude, Double longitude, Integer radiusM, String networkCidrs) {}

    @PostMapping("/venues")
    public Map<String, Object> addVenue(@RequestBody VenueBody b) {
        admin();
        if (blank(b.code())) throw ApiException.badRequest("CODE_REQUIRED", "Venue code is required.");
        UUID inst = db.scalar("SELECT id FROM institutions ORDER BY created_at LIMIT 1", UUID.class);
        UUID id = db.insertReturningId("INSERT INTO venues(institution_id, code, description) VALUES (:i, :c, :d) RETURNING id",
                "i", inst, "c", b.code().trim().toUpperCase(), "d", nz(b.description()));
        updateVenue(id, b);
        return Json.obj("id", id);
    }

    @PutMapping("/venues/{id}")
    public Map<String, Object> updateVenue(@PathVariable UUID id, @RequestBody VenueBody b) {
        admin();
        if ((b.latitude() == null) != (b.longitude() == null)) throw ApiException.badRequest("INVALID_GPS", "Provide both latitude and longitude, or neither.");
        if (b.latitude() != null && (Math.abs(b.latitude()) > 90 || Math.abs(b.longitude()) > 180)) throw ApiException.badRequest("INVALID_GPS", "Invalid coordinates.");
        int radius = b.radiusM() == null ? 60 : b.radiusM();
        if (radius < 10 || radius > 2000) throw ApiException.badRequest("INVALID_RADIUS", "Radius must be 10–2000 m.");
        List<String> cidrs = new ArrayList<>();
        if (!blank(b.networkCidrs())) for (String c : b.networkCidrs().split("[,\\s]+")) {
            if (c.isBlank()) continue;
            if (!PresenceService.validCidr(c.trim())) throw ApiException.badRequest("INVALID_CIDR", "Invalid network range: " + c);
            cidrs.add(c.trim());
        }
        int n = db.update("""
                UPDATE venues SET description = :d, latitude = :lat, longitude = :lon, radius_m = :r,
                    network_cidrs = CASE WHEN :c = '' THEN '{}'::text[] ELSE string_to_array(:c, ',') END WHERE id = :id""",
                "d", nz(b.description()), "lat", b.latitude(), "lon", b.longitude(), "r", radius, "c", String.join(",", cidrs), "id", id);
        if (n == 0) throw ApiException.notFound("NOT_FOUND", "Venue not found.");
        audit.log("VENUE_UPDATED", "venue", id, Json.obj("venue", b));
        return Json.obj("updated", true);
    }

    public record BeaconBody(String kind, String identifier, String note) {}

    @PostMapping("/venues/{id}/proximity-devices")
    public Map<String, Object> addBeacon(@PathVariable UUID id, @RequestBody BeaconBody b) {
        admin();
        if (!Set.of("BLE_BEACON", "WIFI_AP", "LOCAL_NETWORK_NODE").contains(b.kind()) || blank(b.identifier())) throw ApiException.badRequest("INVALID", "Invalid device.");
        UUID pid = db.insertReturningId("""
                INSERT INTO proximity_devices(venue_id, kind, identifier, metadata) VALUES (:v, :k, :i, CAST(:m AS jsonb)) RETURNING id""",
                "v", id, "k", b.kind(), "i", b.identifier().trim(), "m", db.json(Json.obj("note", b.note())));
        audit.log("PROXIMITY_DEVICE_ADDED", "venue", id, Json.obj("kind", b.kind()));
        return Json.obj("id", pid);
    }

    // ================================================================= sessions, analytics, reports

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(@RequestParam(required = false) String status, @RequestParam(required = false) UUID courseId) {
        admin();
        return db.list("""
                SELECT s.id, s.status, s.session_date, s.mode, s.opened_at, s.closed_at, c.code, c.title, v.code AS venue,
                       coalesce(l.full_name, a.full_name) AS started_by,
                       count(r.id) FILTER (WHERE r.status IN ('PRESENT','LATE')) AS attended,
                       count(r.id) FILTER (WHERE r.status = 'PENDING_VERIFICATION') AS pending, count(r.id) AS records
                FROM attendance_sessions s JOIN courses c ON c.id = s.course_id LEFT JOIN venues v ON v.id = s.venue_id
                LEFT JOIN lecturers l ON l.user_id = s.started_by LEFT JOIN administrators a ON a.user_id = s.started_by
                LEFT JOIN attendance_records r ON r.session_id = s.id
                WHERE (CAST(:st AS text) IS NULL OR s.status = :st) AND (CAST(:c AS uuid) IS NULL OR s.course_id = :c)
                GROUP BY s.id, c.id, v.code, l.full_name, a.full_name ORDER BY s.opened_at DESC LIMIT 300""",
                "st", blank(status) ? null : status, "c", courseId);
    }

    @GetMapping("/analytics")
    public Map<String, Object> analyticsView(@RequestParam(required = false) UUID courseId) {
        admin();
        UUID sem = calendar.currentSemesterId();
        var rows = analytics.studentCourseStats(sem, courseId, null, null);
        // Per-student overall
        Map<UUID, long[]> per = new LinkedHashMap<>();
        Map<UUID, Map<String, Object>> who = new HashMap<>();
        for (var r : rows) {
            long[] a = per.computeIfAbsent((UUID) r.get("studentId"), k -> new long[2]);
            a[0] += ((Number) r.get("attended")).longValue();
            a[1] += ((Number) r.get("denominator")).longValue();
            who.put((UUID) r.get("studentId"), r);
        }
        int low = settings.getInt("attendance.lowAttendanceThreshold"), excellent = settings.getInt("attendance.excellentAttendanceThreshold");
        List<Map<String, Object>> students = new ArrayList<>();
        for (var e : per.entrySet()) {
            if (e.getValue()[1] == 0) continue;
            double p = Math.round(e.getValue()[0] * 1000.0 / e.getValue()[1]) / 10.0;
            var w = who.get(e.getKey());
            students.add(Json.obj("studentId", e.getKey(), "indexNumber", w.get("indexNumber"), "name", StudentDirectory.fullName(w),
                    "percentage", p, "attended", e.getValue()[0], "eligible", e.getValue()[1]));
        }
        students.sort(Comparator.comparingDouble(s -> (Double) s.get("percentage")));
        List<Map<String, Object>> byCourse = new ArrayList<>();
        Map<UUID, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (var r : rows) grouped.computeIfAbsent((UUID) r.get("courseId"), k -> new ArrayList<>()).add(r);
        for (var e : grouped.entrySet()) {
            var first = e.getValue().get(0);
            Map<String, Object> s = analytics.summarise(e.getValue());
            s.put("courseId", e.getKey());
            s.put("code", first.get("courseCode"));
            s.put("title", first.get("courseTitle"));
            byCourse.add(s);
        }
        return Json.obj("summary", analytics.summarise(rows), "byCourse", byCourse,
                "low", students.stream().filter(s -> (Double) s.get("percentage") < low).toList(),
                "excellent", students.stream().filter(s -> (Double) s.get("percentage") >= excellent).sorted((a, b) -> Double.compare((Double) b.get("percentage"), (Double) a.get("percentage"))).toList(),
                "trend", analytics.weeklyTrend(sem, courseId, null, null), "policy", analytics.policy());
    }

    @GetMapping("/reports/records")
    public ResponseEntity<byte[]> recordsReport(@RequestParam(defaultValue = "csv") String format, @RequestParam(required = false) UUID courseId,
                                                @RequestParam(required = false) UUID lecturerId, @RequestParam(required = false) UUID classGroupId,
                                                @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        admin();
        if (!blank(from)) LocalDate.parse(from);
        if (!blank(to)) LocalDate.parse(to);
        audit.log("REPORT_EXPORTED", "records", null, Json.obj("format", format, "courseId", courseId, "lecturerId", lecturerId, "from", from, "to", to));
        return renderer.render(reports.records(courseId, lecturerId, classGroupId, from, to, null), format);
    }

    @GetMapping("/reports/student/{id}")
    public ResponseEntity<byte[]> studentReport(@PathVariable UUID id, @RequestParam(defaultValue = "pdf") String format) {
        admin();
        audit.log("REPORT_EXPORTED", "student", id, Json.obj("format", format));
        return renderer.render(reports.student(id), format);
    }

    @GetMapping("/reports/course/{id}")
    public ResponseEntity<byte[]> courseReport(@PathVariable UUID id, @RequestParam(defaultValue = "csv") String format) {
        admin();
        audit.log("REPORT_EXPORTED", "course", id, Json.obj("format", format));
        return renderer.render(reports.course(id), format);
    }

    // ================================================================= devices, alerts, recovery

    @GetMapping("/devices")
    public List<Map<String, Object>> devices(@RequestParam(required = false) String q) {
        admin();
        String like = blank(q) ? null : "%" + q.trim().toLowerCase().replace("%", "") + "%";
        return db.list("""
                SELECT d.id, d.label, d.status, d.first_seen_at, d.last_seen_at, u.role, st.id AS student_id, st.index_number,
                       coalesce(st.first_name || ' ' || st.last_name, l.full_name, a.full_name, u.email) AS owner,
                       (SELECT count(*) FROM webauthn_credentials w WHERE w.device_id = d.id AND w.revoked_at IS NULL) AS passkeys,
                       (SELECT count(DISTINCT d2.user_id) FROM devices d2 WHERE d2.device_cookie_hash = d.device_cookie_hash) AS accounts_on_device
                FROM devices d JOIN app_users u ON u.id = d.user_id LEFT JOIN students st ON st.user_id = u.id
                LEFT JOIN lecturers l ON l.user_id = u.id LEFT JOIN administrators a ON a.user_id = u.id
                WHERE (CAST(:q AS text) IS NULL OR lower(coalesce(st.index_number,'') || ' ' || coalesce(st.first_name,'') || ' ' || coalesce(st.last_name,'') || ' ' || coalesce(l.full_name,'')) LIKE :q)
                ORDER BY d.last_seen_at DESC LIMIT 300""", "q", like);
    }

    @PostMapping("/devices/{id}/revoke")
    @Transactional
    public Map<String, Object> revokeDevice(@PathVariable UUID id, @RequestBody ReasonBody b) {
        admin();
        if (b == null || blank(b.reason())) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required.");
        int n = db.update("UPDATE devices SET status = 'REVOKED', revoked_at = now() WHERE id = :id AND status = 'TRUSTED'", "id", id);
        if (n == 0) throw ApiException.conflict("ALREADY_REVOKED", "Device already revoked.");
        db.update("UPDATE webauthn_credentials SET revoked_at = now() WHERE device_id = :id AND revoked_at IS NULL", "id", id);
        db.update("UPDATE auth_sessions SET revoked_at = now() WHERE device_id = :id AND revoked_at IS NULL", "id", id);
        audit.log("DEVICE_REVOKED", "device", id, Json.obj("reason", b.reason()));
        return Json.obj("revoked", true);
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(defaultValue = "OPEN") String status) {
        admin();
        return db.list("""
                SELECT f.id, f.kind, f.severity, f.status, f.detail, f.created_at, f.review_note, f.reviewed_at,
                       st.id AS student_id, st.index_number, st.first_name, st.last_name, c.code AS course_code, c.title AS course_title, s.session_date
                FROM fraud_alerts f LEFT JOIN students st ON st.id = f.student_id LEFT JOIN attendance_sessions s ON s.id = f.session_id
                LEFT JOIN courses c ON c.id = s.course_id WHERE (:st = 'ALL' OR f.status = :st) ORDER BY f.created_at DESC LIMIT 300""", "st", status);
    }

    public record ReviewBody(String status, String note) {}

    @PostMapping("/alerts/{id}/review")
    public Map<String, Object> reviewAlert(@PathVariable UUID id, @RequestBody ReviewBody b) {
        CurrentUser u = admin();
        if (!Set.of("DISMISSED", "CONFIRMED").contains(b.status())) throw ApiException.badRequest("INVALID_STATUS", "Invalid status.");
        if (blank(b.note())) throw ApiException.badRequest("NOTE_REQUIRED", "Add a review note.");
        int n = db.update("UPDATE fraud_alerts SET status = :s, review_note = :n, reviewed_by = :u, reviewed_at = now() WHERE id = :id AND status = 'OPEN'",
                "s", b.status(), "n", b.note().trim(), "u", u.userId(), "id", id);
        if (n == 0) throw ApiException.conflict("ALREADY_REVIEWED", "This alert was already reviewed.");
        audit.log("ALERT_REVIEWED", "fraud_alert", id, Json.obj("status", b.status(), "note", b.note()));
        return Json.obj("reviewed", true);
    }

    @GetMapping("/recovery")
    public List<Map<String, Object>> recovery(@RequestParam(defaultValue = "PENDING") String status) {
        admin();
        List<Map<String, Object>> l = db.list("""
                SELECT r.id, r.kind, r.reason, r.status, r.face_verified, r.created_at, r.expires_at, r.review_note, r.reviewed_at,
                       st.id AS student_id, st.index_number, st.first_name, st.last_name, st.other_names
                FROM recovery_requests r JOIN students st ON st.id = r.student_id
                WHERE (:st = 'ALL' OR r.status = :st) ORDER BY r.created_at DESC LIMIT 300""", "st", status);
        for (var r : l) r.put("name", StudentDirectory.fullName(r));
        return l;
    }

    public record DecideBody(boolean approve, String note) {}

    @PostMapping("/recovery/{id}/decide")
    public Map<String, Object> decideRecovery(@PathVariable UUID id, @RequestBody DecideBody b) {
        CurrentUser u = admin();
        if (blank(b.note()) || b.note().trim().length() < 5) {
            throw ApiException.badRequest("NOTE_REQUIRED", "Record how the student's identity was confirmed (e.g. ID card checked in person).");
        }
        Map<String, Object> r = db.one("SELECT r.*, st.user_id FROM recovery_requests r JOIN students st ON st.id = r.student_id WHERE r.id = :id", "id", id);
        if (r == null) throw ApiException.notFound("NOT_FOUND", "Request not found.");
        if (!"PENDING".equals(r.get("status"))) throw ApiException.conflict("ALREADY_DECIDED", "This request was already decided.");
        String status = b.approve() ? ("DEVICE_CHANGE".equals(r.get("kind")) ? "APPROVED" : "COMPLETED") : "REJECTED";
        db.update("""
                UPDATE recovery_requests SET status = :s, reviewed_by = :u, reviewed_at = now(), review_note = :n,
                    expires_at = CASE WHEN :s = 'APPROVED' THEN now() + interval '48 hours' ELSE expires_at END WHERE id = :id""",
                "s", status, "u", u.userId(), "n", b.note().trim(), "id", id);
        audit.log("RECOVERY_" + (b.approve() ? "APPROVED" : "REJECTED"), "recovery_request", id, Json.obj("kind", r.get("kind"), "note", b.note()));
        if (r.get("userId") != null) {
            notifications.notify((UUID) r.get("userId"), "Recovery request " + (b.approve() ? "approved" : "rejected"),
                    b.approve() && "DEVICE_CHANGE".equals(r.get("kind"))
                            ? "Open SmartAttend on your new phone and choose 'Recover account' within 48 hours to finish."
                            : b.note(), "/recover.html");
        }
        return Json.obj("status", status);
    }

    // ================================================================= audit & settings

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog(@RequestParam(required = false) String action, @RequestParam(defaultValue = "0") int page) {
        admin();
        return db.list("""
                SELECT a.id, a.action, a.entity_type, a.entity_id, a.detail, a.ip, a.created_at, a.actor_role,
                       coalesce(l.full_name, ad.full_name, st.index_number, u.email) AS actor
                FROM audit_logs a LEFT JOIN app_users u ON u.id = a.actor_user_id LEFT JOIN lecturers l ON l.user_id = u.id
                LEFT JOIN administrators ad ON ad.user_id = u.id LEFT JOIN students st ON st.user_id = u.id
                WHERE (CAST(:a AS text) IS NULL OR a.action ILIKE :a)
                ORDER BY a.id DESC LIMIT 200 OFFSET :off""", "a", blank(action) ? null : "%" + action.trim() + "%", "off", Math.max(0, page) * 200);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        admin();
        return Json.obj("settings", settings.all(), "faceEngine", Json.obj("provider", face.engine().providerId(),
                "model", face.engine().modelVersion(), "available", face.engine().available(), "reason", face.engine().unavailableReason()));
    }

    public record SettingBody(String key, String value) {}

    @PutMapping("/settings")
    public Map<String, Object> updateSetting(@RequestBody SettingBody b) {
        CurrentUser u = admin();
        if (b.key() == null) throw ApiException.badRequest("KEY_REQUIRED", "Setting key required.");
        boolean securityKey = b.key().startsWith("security.") || b.key().startsWith("face.") || b.key().startsWith("biometric.");
        if (securityKey && u.role() != Role.SUPER_ADMIN) throw ApiException.forbidden("Only a Super Administrator can change security policies.");
        String before = settings.get(b.key());
        settings.set(b.key(), b.value(), u.userId());
        audit.log("SETTING_CHANGED", "setting", b.key(), Json.obj("before", before, "after", b.value()));
        return Json.obj("updated", true);
    }

    // ================================================================= helpers

    static boolean blank(String s) { return s == null || s.isBlank(); }

    static String nz(String s) { return blank(s) ? null : s.trim(); }

    static String validEmail(String e) {
        if (blank(e)) return null;
        String x = e.trim().toLowerCase();
        if (!x.matches("[^@\\s]{1,64}@[^@\\s]{1,190}\\.[a-z]{2,}")) throw ApiException.badRequest("INVALID_EMAIL", "Invalid email address.");
        return x;
    }

    static String temporaryPassword() {
        String alpha = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 14; i++) b.append(alpha.charAt(Crypto.randomInt(alpha.length())));
        return b.substring(0, 5) + "-" + b.substring(5, 10) + "-" + b.substring(10) + "!7";
    }

    static void validateSlot(SlotBody b) {
        if (b.courseId() == null || b.classGroupId() == null) throw ApiException.badRequest("INVALID", "Course and class are required.");
        if (b.dayOfWeek() == null || b.dayOfWeek() < 1 || b.dayOfWeek() > 7) throw ApiException.badRequest("INVALID", "Invalid day.");
        if (!Set.of("IN_PERSON", "ONLINE").contains(b.mode())) throw ApiException.badRequest("INVALID_MODE", "Invalid mode.");
        LocalTime s = LocalTime.parse(b.startTime()), e = LocalTime.parse(b.endTime());
        if (!e.isAfter(s)) throw ApiException.badRequest("INVALID_TIME", "End time must be after start time.");
    }
}
