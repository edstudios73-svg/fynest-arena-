package gh.edu.gctu.smartattend.lecturer;

import gh.edu.gctu.smartattend.attendance.*;
import gh.edu.gctu.smartattend.common.*;
import gh.edu.gctu.smartattend.reports.ReportRenderer;
import gh.edu.gctu.smartattend.reports.ReportService;
import gh.edu.gctu.smartattend.security.AuthContext;
import gh.edu.gctu.smartattend.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * Lecturer API (administrators may also use it with institution-wide scope). Every course- or
 * session-specific call goes through CourseAccess, which enforces course assignment.
 */
@RestController
@RequestMapping("/api/lecturer")
public class LecturerController {
    private final Db db;
    private final CourseAccess access;
    private final AttendanceService attendance;
    private final ChallengeService challenges;
    private final AnalyticsService analytics;
    private final AcademicCalendar calendar;
    private final LiveBroker live;
    private final ReportService reports;
    private final ReportRenderer renderer;
    private final AuditService audit;
    private final NotificationService notifications;

    public LecturerController(Db db, CourseAccess access, AttendanceService attendance, ChallengeService challenges,
                              AnalyticsService analytics, AcademicCalendar calendar, LiveBroker live, ReportService reports,
                              ReportRenderer renderer, AuditService audit, NotificationService notifications) {
        this.db = db;
        this.access = access;
        this.attendance = attendance;
        this.challenges = challenges;
        this.analytics = analytics;
        this.calendar = calendar;
        this.live = live;
        this.reports = reports;
        this.renderer = renderer;
        this.audit = audit;
        this.notifications = notifications;
    }

    private List<UUID> myCourseIds(CurrentUser u) {
        if (u.role().isAdmin()) return db.jdbc().queryForList("SELECT id FROM courses", Db.params(), UUID.class);
        return db.jdbc().queryForList("SELECT DISTINCT course_id FROM course_assignments WHERE lecturer_id = :l",
                Db.params("l", u.lecturerId()), UUID.class);
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        CurrentUser u = AuthContext.require();
        attendance.housekeepingIfDue();
        UUID sem = calendar.currentSemesterId();
        List<UUID> ids = myCourseIds(u);
        String idList = ids.isEmpty() ? "" : String.join(",", ids.stream().map(UUID::toString).toList());
        List<Map<String, Object>> courses = db.list("""
                SELECT c.id, c.code, c.alt_code, c.title,
                       (SELECT count(*) FROM course_enrollments e WHERE e.course_id = c.id AND e.semester_id = :sem AND e.status = 'ENROLLED') AS enrolled,
                       (SELECT count(*) FROM attendance_sessions s WHERE s.course_id = c.id AND s.semester_id = :sem AND s.status = 'CLOSED') AS sessions_held,
                       (SELECT s.id FROM attendance_sessions s WHERE s.course_id = c.id AND s.status = 'OPEN' LIMIT 1) AS open_session_id,
                       (SELECT json_agg(json_build_object('day', t.day_of_week, 'start', t.start_time, 'end', t.end_time, 'mode', t.mode, 'venue', v.code))
                          FROM class_timetables t LEFT JOIN venues v ON v.id = t.venue_id WHERE t.course_id = c.id AND t.semester_id = :sem) AS slots
                FROM courses c WHERE c.id::text = ANY(string_to_array(:ids, ',')) ORDER BY c.code NULLS LAST""", "sem", sem, "ids", idList);
        List<Map<String, Object>> stats = new ArrayList<>();
        for (UUID c : ids) stats.addAll(analytics.studentCourseStats(sem, c, null, null));
        for (var c : courses) {
            var mine = stats.stream().filter(s -> s.get("courseId").equals(c.get("id"))).toList();
            c.put("summary", analytics.summarise(mine));
        }
        List<Map<String, Object>> recent = db.list("""
                SELECT s.id, s.session_date, s.status, s.opened_at, s.mode, c.code, c.title,
                       count(r.id) FILTER (WHERE r.status IN ('PRESENT','LATE')) AS attended, count(r.id) AS records
                FROM attendance_sessions s JOIN courses c ON c.id = s.course_id LEFT JOIN attendance_records r ON r.session_id = s.id
                WHERE s.course_id::text = ANY(string_to_array(:ids, ',')) GROUP BY s.id, c.id ORDER BY s.opened_at DESC LIMIT 10""", "ids", idList);
        Set<UUID> students = new HashSet<>();
        for (var s : stats) students.add((UUID) s.get("studentId"));
        return Json.obj("courses", courses, "summary", analytics.summarise(stats), "totalStudents", students.size(),
                "recentSessions", recent, "trend", analytics.weeklyTrend(sem, null, ids, null), "today", calendar.today(),
                "pendingCorrections", db.scalar("""
                        SELECT count(*)::int FROM correction_requests cr JOIN attendance_sessions s ON s.id = cr.session_id
                        WHERE cr.status = 'PENDING' AND s.course_id::text = ANY(string_to_array(:ids, ','))""", Integer.class, "ids", idList),
                "policy", analytics.policy());
    }

    @GetMapping("/courses/{courseId}")
    public Map<String, Object> course(@PathVariable UUID courseId) {
        CurrentUser u = AuthContext.require();
        access.requireCourse(u, courseId);
        UUID sem = calendar.currentSemesterId();
        var stats = analytics.studentCourseStats(sem, courseId, null, null);
        for (var s : stats) {
            s.put("name", gh.edu.gctu.smartattend.student.StudentDirectory.fullName(s));
            s.put("accountStatus", db.scalar("SELECT u.status FROM students st LEFT JOIN app_users u ON u.id = st.user_id WHERE st.id = :s",
                    String.class, "s", s.get("studentId")));
        }
        return Json.obj("course", db.one("SELECT id, code, alt_code, title FROM courses WHERE id = :c", "c", courseId),
                "students", stats, "summary", analytics.summarise(stats),
                "sessions", db.list("""
                        SELECT s.id, s.session_date, s.status, s.mode, s.opened_at, s.closed_at, v.code AS venue,
                               count(r.id) FILTER (WHERE r.status = 'PRESENT') AS present, count(r.id) FILTER (WHERE r.status = 'LATE') AS late,
                               count(r.id) FILTER (WHERE r.status = 'ABSENT') AS absent, count(r.id) FILTER (WHERE r.status = 'PENDING_VERIFICATION') AS pending,
                               count(r.id) FILTER (WHERE r.status = 'EXCUSED') AS excused
                        FROM attendance_sessions s LEFT JOIN venues v ON v.id = s.venue_id LEFT JOIN attendance_records r ON r.session_id = s.id
                        WHERE s.course_id = :c AND s.semester_id = :sem GROUP BY s.id, v.code ORDER BY s.opened_at DESC""", "c", courseId, "sem", sem),
                "today", calendar.slotFor(courseId, calendar.today()),
                "upcoming", upcomingFor(courseId),
                "trend", analytics.weeklyTrend(sem, courseId, null, null),
                "defaults", Json.obj("durationMinutes", db.scalar("SELECT value FROM system_settings WHERE key = 'attendance.defaultDurationMinutes'", String.class),
                        "lateAfterMinutes", db.scalar("SELECT value FROM system_settings WHERE key = 'attendance.lateAfterMinutes'", String.class),
                        "proximityPolicy", db.scalar("SELECT value FROM system_settings WHERE key = 'proximity.defaultPolicy'", String.class)));
    }

    private List<Map<String, Object>> upcomingFor(UUID courseId) {
        UUID group = db.scalar("SELECT class_group_id FROM class_timetables WHERE course_id = :c AND semester_id = :s LIMIT 1",
                UUID.class, "c", courseId, "s", calendar.currentSemesterId());
        return group == null ? List.of() : calendar.upcoming(group, 21, courseId);
    }

    @PostMapping("/sessions")
    public Map<String, Object> start(@RequestBody AttendanceService.StartRequest body) {
        return attendance.start(AuthContext.require(), body);
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> session(@PathVariable UUID id) {
        CurrentUser u = AuthContext.require();
        attendance.housekeepingIfDue();
        Map<String, Object> s = access.requireSession(u, id);
        Map<String, Object> info = db.one("""
                SELECT s.id, s.status, s.mode, s.session_date, s.opened_at, s.closes_at, s.closed_at, s.late_after_minutes,
                       s.ends_at, s.checkout_minutes, s.geofence_m, s.anchor_at,
                       s.require_face, s.require_passkey, s.proximity_policy, s.notes, c.id AS course_id, c.code AS course_code,
                       c.title AS course_title, v.code AS venue, g.name AS group_name
                FROM attendance_sessions s JOIN courses c ON c.id = s.course_id LEFT JOIN venues v ON v.id = s.venue_id
                JOIN class_groups g ON g.id = s.class_group_id WHERE s.id = :id""", "id", id);
        Integer enrolled = db.scalar("""
                SELECT count(*)::int FROM course_enrollments e JOIN students st ON st.id = e.student_id
                WHERE e.course_id = :c AND e.semester_id = :sem AND e.status = 'ENROLLED' AND st.class_group_id = :g""",
                Integer.class, "c", s.get("courseId"), "sem", s.get("semesterId"), "g", s.get("classGroupId"));
        List<Map<String, Object>> records = db.list("""
                SELECT r.student_id, r.status, r.verification_status, r.submitted_at, r.reference_no, r.reason, r.presence_evidence,
                       st.index_number, st.first_name, st.last_name, st.other_names
                FROM attendance_records r JOIN students st ON st.id = r.student_id WHERE r.session_id = :s ORDER BY r.submitted_at DESC""", "s", id);
        for (var r : records) r.put("name", gh.edu.gctu.smartattend.student.StudentDirectory.fullName(r));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String k : List.of("CHECKED_IN", "PRESENT", "LATE", "PENDING_VERIFICATION", "ABSENT", "EXCUSED", "REJECTED")) counts.put(k, 0L);
        for (var r : records) counts.merge((String) r.get("status"), 1L, Long::sum);
        List<Map<String, Object>> failures = db.list("""
                SELECT e.event_type, e.created_at, e.detail, st.index_number, st.first_name, st.last_name, st.other_names
                FROM attendance_verification_events e LEFT JOIN students st ON st.id = e.student_id
                WHERE e.session_id = :s AND e.success = false ORDER BY e.created_at DESC LIMIT 50""", "s", id);
        for (var f : failures) f.put("name", f.get("indexNumber") == null ? "Unknown" : gh.edu.gctu.smartattend.student.StudentDirectory.fullName(f));
        Integer inProgress = db.scalar("SELECT count(*)::int FROM attendance_attempts WHERE session_id = :s AND status = 'IN_PROGRESS' AND expires_at > now()",
                Integer.class, "s", id);
        long attended = counts.get("PRESENT") + counts.get("LATE");
        List<Map<String, Object>> roster = db.list("""
                SELECT st.id AS student_id, st.index_number, st.first_name, st.last_name, st.other_names, r.status
                FROM course_enrollments e JOIN students st ON st.id = e.student_id
                LEFT JOIN attendance_records r ON r.session_id = :s AND r.student_id = st.id
                WHERE e.course_id = :c AND e.semester_id = :sem AND e.status = 'ENROLLED' AND st.class_group_id = :g
                ORDER BY st.last_name, st.first_name""", "s", id, "c", s.get("courseId"), "sem", s.get("semesterId"), "g", s.get("classGroupId"));
        for (var r : roster) r.put("name", gh.edu.gctu.smartattend.student.StudentDirectory.fullName(r));
        return Json.obj("session", info, "enrolled", enrolled, "counts", counts, "inProgress", inProgress,
                "failedAttempts", failures.size(), "failures", failures, "records", records, "roster", roster,
                "percentage", enrolled == null || enrolled == 0 ? null : Math.round(attended * 1000.0 / enrolled) / 10.0,
                "serverNow", java.time.Instant.now());
    }

    @GetMapping("/sessions/{id}/challenge")
    public Map<String, Object> challenge(@PathVariable UUID id) {
        access.requireSession(AuthContext.require(), id);
        return challenges.current(id);
    }

    @GetMapping(value = "/sessions/{id}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable UUID id) {
        access.requireSession(AuthContext.require(), id);
        return live.subscribe(id);
    }

    public record LocationBody(Double latitude, Double longitude, Double accuracy) {}

    @PostMapping("/sessions/{id}/anchor")
    public Map<String, Object> anchor(@PathVariable UUID id, @RequestBody LocationBody b) {
        return attendance.updateAnchor(AuthContext.require(), id, new PresenceService.Gps(b.latitude(), b.longitude(), b.accuracy()));
    }

    @PostMapping("/sessions/{id}/close")
    public Map<String, Object> close(@PathVariable UUID id) {
        return attendance.close(AuthContext.require(), id, false);
    }

    public record ReasonBody(String reason) {}

    @PostMapping("/sessions/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id, @RequestBody ReasonBody body) {
        return attendance.cancel(AuthContext.require(), id, body.reason());
    }

    public record MarkBody(String status, String reason) {}

    @PostMapping("/sessions/{id}/records/{studentId}")
    public Map<String, Object> mark(@PathVariable UUID id, @PathVariable UUID studentId, @RequestBody MarkBody body) {
        return attendance.manualMark(AuthContext.require(), id, studentId, body.status(), body.reason());
    }

    // ---------------------------------------------------------------- corrections & alerts

    @GetMapping("/corrections")
    public List<Map<String, Object>> corrections(@RequestParam(defaultValue = "PENDING") String status) {
        CurrentUser u = AuthContext.require();
        String ids = String.join(",", myCourseIds(u).stream().map(UUID::toString).toList());
        List<Map<String, Object>> l = db.list("""
                SELECT cr.id, cr.session_id, cr.student_id, cr.requested_status, cr.reason, cr.status, cr.created_at, cr.review_note,
                       s.session_date, c.code AS course_code, c.title AS course_title, r.status AS current_status,
                       st.index_number, st.first_name, st.last_name, st.other_names
                FROM correction_requests cr JOIN attendance_sessions s ON s.id = cr.session_id JOIN courses c ON c.id = s.course_id
                JOIN students st ON st.id = cr.student_id LEFT JOIN attendance_records r ON r.session_id = cr.session_id AND r.student_id = cr.student_id
                WHERE s.course_id::text = ANY(string_to_array(:ids, ',')) AND (:st = 'ALL' OR cr.status = :st)
                ORDER BY cr.created_at DESC LIMIT 200""", "ids", ids, "st", status);
        for (var r : l) r.put("name", gh.edu.gctu.smartattend.student.StudentDirectory.fullName(r));
        return l;
    }

    public record ResolveBody(boolean approve, String note) {}

    @PostMapping("/corrections/{id}/resolve")
    @Transactional
    public Map<String, Object> resolve(@PathVariable UUID id, @RequestBody ResolveBody body) {
        CurrentUser u = AuthContext.require();
        Map<String, Object> cr = db.one("SELECT * FROM correction_requests WHERE id = :id FOR UPDATE", "id", id);
        if (cr == null) throw ApiException.notFound("NOT_FOUND", "Request not found.");
        access.requireSession(u, (UUID) cr.get("sessionId"));
        if (!"PENDING".equals(cr.get("status"))) throw ApiException.conflict("ALREADY_RESOLVED", "This request was already resolved.");
        String note = body.note() == null ? "" : body.note().trim();
        if (body.approve()) {
            attendance.manualMark(u, (UUID) cr.get("sessionId"), (UUID) cr.get("studentId"), (String) cr.get("requestedStatus"),
                    "Correction approved" + (note.isEmpty() ? "" : ": " + note));
        } else if (note.length() < 3) {
            throw ApiException.badRequest("REASON_REQUIRED", "Give a reason for rejecting the request.");
        }
        db.update("UPDATE correction_requests SET status = :s, reviewed_by = :u, reviewed_at = now(), review_note = :n WHERE id = :id",
                "s", body.approve() ? "APPROVED" : "REJECTED", "u", u.userId(), "n", note, "id", id);
        audit.log("CORRECTION_" + (body.approve() ? "APPROVED" : "REJECTED"), "correction_request", id, Json.obj("note", note));
        UUID studentUser = db.scalar("SELECT user_id FROM students WHERE id = :s", UUID.class, "s", cr.get("studentId"));
        notifications.notify(studentUser, "Correction " + (body.approve() ? "approved" : "rejected"),
                note.isEmpty() ? "Your attendance correction request was reviewed." : note, "/student.html#/history");
        return Json.obj("resolved", true);
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts() {
        CurrentUser u = AuthContext.require();
        String ids = String.join(",", myCourseIds(u).stream().map(UUID::toString).toList());
        return db.list("""
                SELECT f.id, f.kind, f.severity, f.status, f.detail, f.created_at, c.code AS course_code, c.title AS course_title,
                       s.session_date, st.index_number
                FROM fraud_alerts f JOIN attendance_sessions s ON s.id = f.session_id JOIN courses c ON c.id = s.course_id
                LEFT JOIN students st ON st.id = f.student_id
                WHERE s.course_id::text = ANY(string_to_array(:ids, ',')) ORDER BY f.created_at DESC LIMIT 100""", "ids", ids);
    }

    // ---------------------------------------------------------------- reports

    @GetMapping("/reports/course/{courseId}")
    public ResponseEntity<byte[]> courseReport(@PathVariable UUID courseId, @RequestParam(defaultValue = "csv") String format) {
        access.requireCourse(AuthContext.require(), courseId);
        audit.log("REPORT_EXPORTED", "course", courseId, Json.obj("format", format));
        return renderer.render(reports.course(courseId), format);
    }

    @GetMapping("/reports/session/{sessionId}")
    public ResponseEntity<byte[]> sessionReport(@PathVariable UUID sessionId, @RequestParam(defaultValue = "csv") String format) {
        access.requireSession(AuthContext.require(), sessionId);
        audit.log("REPORT_EXPORTED", "attendance_session", sessionId, Json.obj("format", format));
        if ("pdf".equalsIgnoreCase(format)) return renderer.sessionSheetPdf(reports.sessionSheet(sessionId));
        return renderer.render(reports.session(sessionId), format);
    }
}
