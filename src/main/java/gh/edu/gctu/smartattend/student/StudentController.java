package gh.edu.gctu.smartattend.student;

import com.fasterxml.jackson.databind.JsonNode;
import gh.edu.gctu.smartattend.attendance.*;
import gh.edu.gctu.smartattend.common.*;
import gh.edu.gctu.smartattend.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/** Student API. Every query is scoped to the authenticated student's own id; there is no write path to records. */
@RestController
@RequestMapping("/api/student")
public class StudentController {
    private final Db db;
    private final AcademicCalendar calendar;
    private final AnalyticsService analytics;
    private final AttendanceService attendance;
    private final AuditService audit;
    private final NotificationService notifications;
    private final SessionService sessions;

    public StudentController(Db db, AcademicCalendar calendar, AnalyticsService analytics, AttendanceService attendance,
                             AuditService audit, NotificationService notifications, SessionService sessions) {
        this.db = db;
        this.calendar = calendar;
        this.analytics = analytics;
        this.attendance = attendance;
        this.audit = audit;
        this.notifications = notifications;
        this.sessions = sessions;
    }

    private CurrentUser me() {
        CurrentUser u = AuthContext.requireRole(Role.STUDENT);
        if (u.studentId() == null) throw ApiException.forbidden("No student profile.");
        return u;
    }

    private UUID groupOf(UUID studentId) {
        return db.scalar("SELECT class_group_id FROM students WHERE id = :s", UUID.class, "s", studentId);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        CurrentUser u = me();
        UUID sem = calendar.currentSemesterId();
        List<Map<String, Object>> stats = analytics.studentCourseStats(sem, null, u.studentId(), null);
        List<Map<String, Object>> upcoming = calendar.upcoming(groupOf(u.studentId()), 14, null);
        Map<String, Map<String, Object>> nextByCourse = new HashMap<>();
        for (var up : upcoming) nextByCourse.putIfAbsent(up.get("courseId").toString(), up);
        List<Map<String, Object>> courses = new ArrayList<>();
        for (var s : stats) {
            Map<String, Object> slot = db.one("""
                    SELECT t.day_of_week, t.start_time, t.end_time FROM class_timetables t
                    WHERE t.course_id = :c AND t.semester_id = :sem AND t.class_group_id = :g ORDER BY t.day_of_week LIMIT 1""",
                    "c", s.get("courseId"), "sem", sem, "g", groupOf(u.studentId()));
            String lecturer = db.scalar("""
                    SELECT string_agg(trim(coalesce(l.title || ' ', '') || l.full_name), ', ') FROM course_assignments a
                    JOIN lecturers l ON l.id = a.lecturer_id WHERE a.course_id = :c AND a.semester_id = :sem""",
                    String.class, "c", s.get("courseId"), "sem", sem);
            courses.add(Json.obj("courseId", s.get("courseId"), "code", s.get("courseCode"), "title", s.get("courseTitle"),
                    "lecturer", lecturer, "slot", slot, "percentage", s.get("percentage"), "attended", s.get("attended"),
                    "eligible", s.get("denominator"), "late", s.get("late"), "absent", s.get("absent"),
                    "next", nextByCourse.get(s.get("courseId").toString())));
        }
        return Json.obj("semester", calendar.currentSemester(), "summary", analytics.summarise(stats),
                "courses", courses, "nextLecture", upcoming.isEmpty() ? null : upcoming.get(0),
                "active", attendance.activeForStudent(u.studentId()), "policy", analytics.policy(),
                "accountStatus", sessions.userStatus(u.userId()));
    }

    @GetMapping("/timetable")
    public Map<String, Object> timetable() {
        CurrentUser u = me();
        UUID group = groupOf(u.studentId());
        Map<String, Object> sem = calendar.currentSemester();
        return Json.obj("semester", sem, "week", calendar.weekNumber(sem, calendar.today()),
                "slots", calendar.slotsForGroup(group, (UUID) sem.get("id")),
                "exceptions", db.list("""
                        SELECT e.timetable_id, e.week_number, e.mode, e.cancelled, e.note, v.code AS venue FROM timetable_exceptions e
                        JOIN class_timetables t ON t.id = e.timetable_id LEFT JOIN venues v ON v.id = e.venue_id
                        WHERE t.class_group_id = :g AND t.semester_id = :s ORDER BY e.week_number""", "g", group, "s", sem.get("id")),
                "upcoming", calendar.upcoming(group, 7, null));
    }

    @GetMapping("/courses/{courseId}")
    public Map<String, Object> course(@PathVariable UUID courseId) {
        CurrentUser u = me();
        UUID sem = calendar.currentSemesterId();
        Integer enrolled = db.scalar("SELECT 1 FROM course_enrollments WHERE course_id = :c AND student_id = :s AND semester_id = :sem",
                Integer.class, "c", courseId, "s", u.studentId(), "sem", sem);
        if (enrolled == null) throw ApiException.forbidden("You are not enrolled in this course.");
        List<Map<String, Object>> stats = analytics.studentCourseStats(sem, courseId, u.studentId(), null);
        return Json.obj("course", db.one("SELECT id, code, alt_code, title FROM courses WHERE id = :c", "c", courseId),
                "stats", stats.isEmpty() ? null : stats.get(0),
                "upcoming", calendar.upcoming(groupOf(u.studentId()), 21, courseId),
                "history", history(courseId, null, null, null, null),
                "trend", analytics.weeklyTrend(sem, courseId, null, u.studentId()));
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(required = false) UUID courseId, @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                             @RequestParam(required = false) Integer limit) {
        CurrentUser u = me();
        return db.list("""
                SELECT r.reference_no, r.status, r.verification_status, r.submitted_at, r.updated_at, r.reason,
                       s.id AS session_id, s.session_date, s.opened_at, s.mode, c.id AS course_id, c.code AS course_code, c.title AS course_title,
                       (SELECT cr.status FROM correction_requests cr WHERE cr.session_id = s.id AND cr.student_id = :st
                        ORDER BY cr.created_at DESC LIMIT 1) AS correction_status
                FROM attendance_records r JOIN attendance_sessions s ON s.id = r.session_id JOIN courses c ON c.id = s.course_id
                WHERE r.student_id = :st
                  AND (CAST(:c AS uuid) IS NULL OR c.id = :c)
                  AND (CAST(:status AS text) IS NULL OR r.status = :status)
                  AND (CAST(:from AS date) IS NULL OR s.session_date >= CAST(:from AS date))
                  AND (CAST(:to AS date) IS NULL OR s.session_date <= CAST(:to AS date))
                ORDER BY s.opened_at DESC LIMIT :lim""",
                "st", u.studentId(), "c", courseId, "status", blank(status), "from", blank(from), "to", blank(to),
                "lim", limit == null ? 500 : Math.min(limit, 1000));
    }

    @GetMapping("/attendance/active")
    public List<Map<String, Object>> active() {
        return attendance.activeForStudent(me().studentId());
    }

    @PostMapping("/attendance/begin")
    public Map<String, Object> begin(@RequestBody AttendanceService.BeginRequest body) {
        return attendance.begin(me(), body);
    }

    @PostMapping(value = "/attendance/{attemptId}/face", consumes = "multipart/form-data")
    public Map<String, Object> face(@PathVariable UUID attemptId, @RequestParam UUID challengeId,
                                    @RequestParam("frames") List<MultipartFile> frames) throws IOException {
        return attendance.verifyFace(me(), attemptId, challengeId, StudentAuthHelpersBridge.bytes(frames));
    }

    public record CodeBody(String otp, String qrToken) {}

    @PostMapping("/attendance/{attemptId}/code")
    public Map<String, Object> code(@PathVariable UUID attemptId, @RequestBody CodeBody b) {
        return attendance.verifyCode(me(), attemptId, b.otp(), b.qrToken());
    }

    @PostMapping("/attendance/checkout/{sessionId}/start")
    public Map<String, Object> checkoutStart(@PathVariable UUID sessionId) {
        return attendance.checkoutStart(me(), sessionId);
    }

    @PostMapping(value = "/attendance/checkout/{sessionId}/face", consumes = "multipart/form-data")
    public Map<String, Object> checkoutFace(@PathVariable UUID sessionId, @RequestParam UUID challengeId,
                                            @RequestParam("frames") List<MultipartFile> frames) throws IOException {
        return attendance.checkoutFace(me(), sessionId, challengeId, StudentAuthHelpersBridge.bytes(frames));
    }

    @PostMapping("/attendance/checkout/{sessionId}/confirm")
    public Map<String, Object> checkoutConfirm(@PathVariable UUID sessionId, @RequestBody(required = false) SubmitBody body, HttpServletRequest req) {
        PresenceService.Gps gps = body == null ? null : new PresenceService.Gps(body.latitude(), body.longitude(), body.accuracy());
        return attendance.checkoutConfirm(me(), sessionId, gps, req.getRemoteAddr());
    }

    @PostMapping("/attendance/{attemptId}/passkey")
    public Map<String, Object> passkey(@PathVariable UUID attemptId, @RequestBody JsonNode credential) {
        return attendance.verifyPasskey(me(), attemptId, credential.toString());
    }

    public record SubmitBody(Double latitude, Double longitude, Double accuracy) {}

    @PostMapping("/attendance/{attemptId}/submit")
    public Map<String, Object> submit(@PathVariable UUID attemptId, @RequestBody(required = false) SubmitBody body, HttpServletRequest req) {
        PresenceService.Gps gps = body == null ? null : new PresenceService.Gps(body.latitude(), body.longitude(), body.accuracy());
        return attendance.submit(me(), attemptId, gps, req.getRemoteAddr());
    }

    public record CorrectionBody(UUID sessionId, String requestedStatus, String reason) {}

    @PostMapping("/corrections")
    public Map<String, Object> requestCorrection(@RequestBody CorrectionBody b) {
        CurrentUser u = me();
        if (b.sessionId() == null || b.reason() == null || b.reason().trim().length() < 10) {
            throw ApiException.badRequest("REASON_REQUIRED", "Please explain the correction (at least 10 characters).");
        }
        if (!Set.of("PRESENT", "LATE", "EXCUSED").contains(b.requestedStatus())) throw ApiException.badRequest("INVALID_STATUS", "Invalid status.");
        Integer own = db.scalar("SELECT 1 FROM attendance_records WHERE session_id = :s AND student_id = :st", Integer.class, "s", b.sessionId(), "st", u.studentId());
        if (own == null) throw ApiException.notFound("RECORD_NOT_FOUND", "No attendance record found for that session.");
        UUID id = db.insertReturningId("""
                INSERT INTO correction_requests(session_id, student_id, requested_status, reason) VALUES (:s, :st, :rs, :r) RETURNING id""",
                "s", b.sessionId(), "st", u.studentId(), "rs", b.requestedStatus(), "r", b.reason().trim().substring(0, Math.min(800, b.reason().trim().length())));
        audit.log("CORRECTION_REQUESTED", "correction_request", id, Json.obj("sessionId", b.sessionId(), "requested", b.requestedStatus()));
        // Notify the course lecturer(s)
        db.update("""
                INSERT INTO notifications(user_id, title, body, link)
                SELECT l.user_id, 'Attendance correction request', 'A student requested a correction for a session.', '/lecturer.html#/corrections'
                FROM attendance_sessions s JOIN course_assignments a ON a.course_id = s.course_id AND a.semester_id = s.semester_id
                JOIN lecturers l ON l.id = a.lecturer_id WHERE s.id = :s""", "s", b.sessionId());
        return Json.obj("submitted", true, "id", id);
    }

    @GetMapping("/corrections")
    public List<Map<String, Object>> corrections() {
        CurrentUser u = me();
        return db.list("""
                SELECT cr.id, cr.requested_status, cr.reason, cr.status, cr.review_note, cr.created_at, cr.reviewed_at,
                       s.session_date, c.code AS course_code, c.title AS course_title
                FROM correction_requests cr JOIN attendance_sessions s ON s.id = cr.session_id JOIN courses c ON c.id = s.course_id
                WHERE cr.student_id = :st ORDER BY cr.created_at DESC""", "st", u.studentId());
    }

    @GetMapping("/devices")
    public Map<String, Object> devices() {
        CurrentUser u = me();
        return Json.obj("currentDeviceId", u.deviceId(),
                "devices", db.list("""
                        SELECT d.id, d.label, d.status, d.first_seen_at, d.last_seen_at,
                               (SELECT count(*) FROM webauthn_credentials w WHERE w.device_id = d.id AND w.revoked_at IS NULL) AS passkeys
                        FROM devices d WHERE d.user_id = :u ORDER BY d.last_seen_at DESC""", "u", u.userId()),
                "passkeys", db.list("""
                        SELECT id, label, created_at, last_used_at, backup_eligible FROM webauthn_credentials
                        WHERE user_id = :u AND revoked_at IS NULL ORDER BY created_at""", "u", u.userId()),
                "biometric", db.one("""
                        SELECT provider, model_version, created_at, retention_until, consent_version FROM biometric_enrollments
                        WHERE student_id = :s AND status = 'ACTIVE'""", "s", u.studentId()));
    }

    /** A student may sign out / untrust another browser. Removing the last passkey is not allowed (use recovery). */
    @PostMapping("/devices/{deviceId}/revoke")
    public Map<String, Object> revokeDevice(@PathVariable UUID deviceId) {
        CurrentUser u = me();
        if (deviceId.equals(u.deviceId())) throw ApiException.badRequest("CURRENT_DEVICE", "You cannot remove the device you are using.");
        Integer remaining = db.scalar("""
                SELECT count(*)::int FROM webauthn_credentials WHERE user_id = :u AND revoked_at IS NULL AND (device_id IS NULL OR device_id <> :d)""",
                Integer.class, "u", u.userId(), "d", deviceId);
        if (remaining == null || remaining == 0) throw ApiException.badRequest("LAST_PASSKEY", "You cannot remove your only passkey.");
        int n = db.update("UPDATE devices SET status = 'REVOKED', revoked_at = now() WHERE id = :d AND user_id = :u", "d", deviceId, "u", u.userId());
        if (n == 0) throw ApiException.notFound("DEVICE_NOT_FOUND", "Device not found.");
        db.update("UPDATE webauthn_credentials SET revoked_at = now() WHERE device_id = :d AND user_id = :u AND revoked_at IS NULL", "d", deviceId, "u", u.userId());
        db.update("UPDATE auth_sessions SET revoked_at = now() WHERE device_id = :d AND user_id = :u AND revoked_at IS NULL", "d", deviceId, "u", u.userId());
        audit.log("DEVICE_REVOKED_BY_STUDENT", "device", deviceId, Json.obj());
        return Json.obj("revoked", true);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    static final class StudentAuthHelpersBridge {
        static List<byte[]> bytes(List<MultipartFile> frames) throws IOException {
            if (frames == null || frames.isEmpty() || frames.size() > 12) throw ApiException.badRequest("BAD_FRAMES", "Invalid camera capture.");
            List<byte[]> out = new ArrayList<>();
            for (MultipartFile m : frames) out.add(m.getBytes());
            return out;
        }
    }
}
