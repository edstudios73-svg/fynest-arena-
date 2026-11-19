package gh.edu.gctu.smartattend.reports;

import gh.edu.gctu.smartattend.attendance.AcademicCalendar;
import gh.edu.gctu.smartattend.attendance.AnalyticsService;
import gh.edu.gctu.smartattend.common.ApiException;
import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.student.StudentDirectory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ReportService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(AcademicCalendar.ZONE);
    private final Db db;
    private final AnalyticsService analytics;
    private final AcademicCalendar calendar;

    public ReportService(Db db, AnalyticsService analytics, AcademicCalendar calendar) {
        this.db = db;
        this.analytics = analytics;
        this.calendar = calendar;
    }

    private static String pct(Object p) { return p == null ? "—" : p + "%"; }

    private String policyLine() {
        var p = analytics.policy();
        return "Policy: late " + (Boolean.TRUE.equals(p.get("lateCountsAsAttended")) ? "counts" : "does not count") +
                " as attended; excused sessions " + (Boolean.TRUE.equals(p.get("excusedExcludedFromDenominator")) ? "excluded from" : "included in") +
                " the denominator; only closed sessions are counted.";
    }

    public Report course(UUID courseId) {
        Map<String, Object> c = db.one("SELECT code, title FROM courses WHERE id = :c", "c", courseId);
        if (c == null) throw ApiException.notFound("COURSE_NOT_FOUND", "Course not found.");
        Map<String, Object> sem = calendar.currentSemester();
        var rows = analytics.studentCourseStats((UUID) sem.get("id"), courseId, null, null);
        List<List<Object>> out = new ArrayList<>();
        for (var r : rows) {
            out.add(List.of(r.get("indexNumber"), StudentDirectory.fullName(r), r.get("denominator"), r.get("present"), r.get("late"),
                    r.get("absent"), r.get("excused"), r.get("pending"), pct(r.get("percentage"))));
        }
        Integer sessions = db.scalar("SELECT count(*)::int FROM attendance_sessions WHERE course_id = :c AND semester_id = :s AND status = 'CLOSED'",
                Integer.class, "c", courseId, "s", sem.get("id"));
        return new Report("Course attendance — " + label(c),
                List.of(sem.get("academicYear") + " · " + sem.get("name"), "Closed sessions: " + sessions, policyLine()),
                List.of("Index number", "Name", "Eligible", "Present", "Late", "Absent", "Excused", "Pending", "Attendance %"),
                out, "course-" + slug(label(c)) + "-" + ReportRenderer.stamp());
    }

    public Report session(UUID sessionId) {
        Map<String, Object> s = db.one("""
                SELECT s.*, c.code, c.title, v.code AS venue FROM attendance_sessions s JOIN courses c ON c.id = s.course_id
                LEFT JOIN venues v ON v.id = s.venue_id WHERE s.id = :id""", "id", sessionId);
        if (s == null) throw ApiException.notFound("SESSION_NOT_FOUND", "Session not found.");
        List<Map<String, Object>> rows = db.list("""
                SELECT st.index_number, st.first_name, st.last_name, st.other_names, r.status, r.verification_status, r.submitted_at, r.reference_no, r.reason
                FROM course_enrollments e JOIN students st ON st.id = e.student_id
                LEFT JOIN attendance_records r ON r.session_id = :s AND r.student_id = st.id
                WHERE e.course_id = :c AND e.semester_id = :sem AND e.status = 'ENROLLED' AND st.class_group_id = :g
                ORDER BY st.last_name, st.first_name""", "s", sessionId, "c", s.get("courseId"), "sem", s.get("semesterId"), "g", s.get("classGroupId"));
        List<List<Object>> out = new ArrayList<>();
        for (var r : rows) {
            out.add(Arrays.asList(r.get("indexNumber"), StudentDirectory.fullName(r),
                    r.get("status") == null ? ("OPEN".equals(s.get("status")) ? "NOT YET" : "ABSENT") : r.get("status"),
                    r.get("verificationStatus"), r.get("submittedAt") == null || "ABSENT".equals(r.get("status")) ? "" : TIME.format((Instant) r.get("submittedAt")),
                    r.get("referenceNo"), r.get("reason")));
        }
        return new Report("Session attendance — " + label(s),
                List.of("Date: " + s.get("sessionDate") + " · Mode: " + s.get("mode") + (s.get("venue") != null ? " · Venue: " + s.get("venue") : ""),
                        "Status: " + s.get("status")),
                List.of("Index number", "Name", "Status", "Verification", "Time", "Reference", "Note"),
                out, "session-" + slug(label(s)) + "-" + s.get("sessionDate"));
    }

    public Report student(UUID studentId) {
        Map<String, Object> st = db.one("SELECT * FROM students WHERE id = :s", "s", studentId);
        if (st == null) throw ApiException.notFound("STUDENT_NOT_FOUND", "Student not found.");
        Map<String, Object> sem = calendar.currentSemester();
        List<List<Object>> out = new ArrayList<>();
        for (var r : analytics.studentCourseStats((UUID) sem.get("id"), null, studentId, null)) {
            out.add(List.of(label(Map.of("code", Objects.toString(r.get("courseCode"), ""), "title", r.get("courseTitle"))),
                    r.get("denominator"), r.get("present"), r.get("late"), r.get("absent"), r.get("excused"), r.get("pending"), pct(r.get("percentage"))));
        }
        return new Report("Student attendance — " + StudentDirectory.fullName(st) + " (" + st.get("indexNumber") + ")",
                List.of(sem.get("academicYear") + " · " + sem.get("name"), policyLine()),
                List.of("Course", "Eligible", "Present", "Late", "Absent", "Excused", "Pending", "Attendance %"),
                out, "student-" + slug((String) st.get("indexNumber")) + "-" + ReportRenderer.stamp());
    }

    /** Institutional record-level export with filters. */
    public Report records(UUID courseId, UUID lecturerId, UUID classGroupId, String from, String to, UUID semesterId) {
        UUID sem = semesterId != null ? semesterId : calendar.currentSemesterId();
        List<Map<String, Object>> rows = db.list("""
                SELECT s.session_date, c.code, c.title, st.index_number, st.first_name, st.last_name, st.other_names,
                       r.status, r.verification_status, r.submitted_at, r.reference_no
                FROM attendance_records r JOIN attendance_sessions s ON s.id = r.session_id JOIN courses c ON c.id = s.course_id
                JOIN students st ON st.id = r.student_id
                WHERE s.semester_id = :sem AND s.status <> 'CANCELLED'
                  AND (CAST(:c AS uuid) IS NULL OR s.course_id = :c)
                  AND (CAST(:g AS uuid) IS NULL OR s.class_group_id = :g)
                  AND (CAST(:l AS uuid) IS NULL OR EXISTS (SELECT 1 FROM course_assignments a WHERE a.course_id = s.course_id AND a.lecturer_id = :l))
                  AND (CAST(:from AS date) IS NULL OR s.session_date >= CAST(:from AS date))
                  AND (CAST(:to AS date) IS NULL OR s.session_date <= CAST(:to AS date))
                ORDER BY s.session_date, c.code, st.last_name LIMIT 50000""",
                "sem", sem, "c", courseId, "g", classGroupId, "l", lecturerId,
                "from", from == null || from.isBlank() ? null : from, "to", to == null || to.isBlank() ? null : to);
        List<List<Object>> out = new ArrayList<>();
        for (var r : rows) {
            out.add(Arrays.asList(r.get("sessionDate"), label(r), r.get("indexNumber"), StudentDirectory.fullName(r), r.get("status"),
                    r.get("verificationStatus"), "ABSENT".equals(r.get("status")) ? "" : TIME.format((Instant) r.get("submittedAt")), r.get("referenceNo")));
        }
        List<String> meta = new ArrayList<>();
        meta.add("Records: " + rows.size());
        if (from != null && !from.isBlank()) meta.add("From " + from);
        if (to != null && !to.isBlank()) meta.add("To " + to);
        return new Report("Attendance records", meta,
                List.of("Date", "Course", "Index number", "Name", "Status", "Verification", "Time", "Reference"), out,
                "attendance-records-" + ReportRenderer.stamp());
    }

    static String label(Map<String, ?> c) {
        Object code = c.get("code");
        return (code == null || code.toString().isBlank() ? "" : code + " ") + c.get("title");
    }

    static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
