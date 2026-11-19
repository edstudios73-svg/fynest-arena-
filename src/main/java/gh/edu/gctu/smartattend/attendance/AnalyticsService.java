package gh.edu.gctu.smartattend.attendance;

import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.common.Json;
import gh.edu.gctu.smartattend.common.SettingsService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Attendance percentage = attended eligible sessions / eligible sessions × 100, where
 *  - eligible sessions = CLOSED sessions of the course for the student's class (OPEN sessions are
 *    still in progress; CANCELLED sessions are never counted),
 *  - attended = PRESENT + LATE (LATE counts only if attendance.lateCountsAsAttended = true),
 *  - EXCUSED sessions are removed from the denominator if attendance.excusedExcludedFromDenominator = true
 *    (otherwise they count as not attended),
 *  - PENDING_VERIFICATION counts as not attended until a lecturer approves it,
 *  - ABSENT and REJECTED count as not attended.
 */
@Service
public class AnalyticsService {
    private final Db db;
    private final SettingsService settings;

    public AnalyticsService(Db db, SettingsService settings) {
        this.db = db;
        this.settings = settings;
    }

    public Map<String, Object> policy() {
        return Json.obj("lateCountsAsAttended", settings.getBool("attendance.lateCountsAsAttended"),
                "excusedExcludedFromDenominator", settings.getBool("attendance.excusedExcludedFromDenominator"),
                "lowThreshold", settings.getInt("attendance.lowAttendanceThreshold"),
                "excellentThreshold", settings.getInt("attendance.excellentAttendanceThreshold"));
    }

    /** Per (student, course) statistics. Filters may be null. */
    public List<Map<String, Object>> studentCourseStats(UUID semesterId, UUID courseId, UUID studentId, UUID classGroupId) {
        List<Map<String, Object>> rows = db.list("""
                SELECT st.id AS student_id, st.index_number, st.first_name, st.last_name, st.other_names,
                       c.id AS course_id, c.code AS course_code, c.title AS course_title,
                       count(s.id) AS eligible,
                       count(*) FILTER (WHERE r.status = 'PRESENT') AS present,
                       count(*) FILTER (WHERE r.status = 'LATE') AS late,
                       count(*) FILTER (WHERE r.status = 'EXCUSED') AS excused,
                       count(*) FILTER (WHERE r.status = 'PENDING_VERIFICATION') AS pending,
                       count(*) FILTER (WHERE r.status IN ('ABSENT','REJECTED') OR (s.id IS NOT NULL AND r.id IS NULL)) AS absent
                FROM course_enrollments e
                JOIN students st ON st.id = e.student_id
                JOIN courses c ON c.id = e.course_id
                LEFT JOIN attendance_sessions s ON s.course_id = e.course_id AND s.semester_id = e.semester_id
                     AND s.class_group_id = st.class_group_id AND s.status = 'CLOSED'
                LEFT JOIN attendance_records r ON r.session_id = s.id AND r.student_id = st.id
                WHERE e.semester_id = :sem AND e.status = 'ENROLLED'
                  AND (CAST(:c AS uuid) IS NULL OR e.course_id = :c)
                  AND (CAST(:st AS uuid) IS NULL OR e.student_id = :st)
                  AND (CAST(:g AS uuid) IS NULL OR st.class_group_id = :g)
                GROUP BY st.id, c.id ORDER BY c.code NULLS LAST, st.last_name, st.first_name""",
                "sem", semesterId, "c", courseId, "st", studentId, "g", classGroupId);
        boolean lateCounts = settings.getBool("attendance.lateCountsAsAttended");
        boolean excludeExcused = settings.getBool("attendance.excusedExcludedFromDenominator");
        for (var r : rows) {
            long eligible = n(r.get("eligible")), present = n(r.get("present")), late = n(r.get("late")), excused = n(r.get("excused"));
            long attended = present + (lateCounts ? late : 0);
            long denominator = eligible - (excludeExcused ? excused : 0);
            r.put("attended", attended);
            r.put("denominator", denominator);
            r.put("percentage", denominator <= 0 ? null : Math.round(attended * 1000.0 / denominator) / 10.0);
        }
        return rows;
    }

    public Map<String, Object> summarise(List<Map<String, Object>> rows) {
        long att = 0, den = 0, present = 0, late = 0, absent = 0, excused = 0, pending = 0;
        int low = settings.getInt("attendance.lowAttendanceThreshold"), excellent = settings.getInt("attendance.excellentAttendanceThreshold");
        int[] buckets = new int[4];
        for (var r : rows) {
            att += n(r.get("attended")); den += n(r.get("denominator"));
            present += n(r.get("present")); late += n(r.get("late")); absent += n(r.get("absent"));
            excused += n(r.get("excused")); pending += n(r.get("pending"));
            Double p = (Double) r.get("percentage");
            if (p != null) buckets[p < 50 ? 0 : p < low ? 1 : p < excellent ? 2 : 3]++;
        }
        return Json.obj("percentage", den == 0 ? null : Math.round(att * 1000.0 / den) / 10.0,
                "attended", att, "eligible", den, "present", present, "late", late, "absent", absent,
                "excused", excused, "pending", pending,
                "distribution", List.of(
                        Json.obj("label", "Below 50%", "count", buckets[0]),
                        Json.obj("label", "50% – " + (low - 1) + "%", "count", buckets[1]),
                        Json.obj("label", low + "% – " + (excellent - 1) + "%", "count", buckets[2]),
                        Json.obj("label", excellent + "% +", "count", buckets[3])));
    }

    /** Weekly attended ratio across closed sessions (for trend charts). */
    public List<Map<String, Object>> weeklyTrend(UUID semesterId, UUID courseId, List<UUID> courseIds, UUID studentId) {
        boolean lateCounts = settings.getBool("attendance.lateCountsAsAttended");
        String ids = courseIds == null ? null : courseIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
        List<Map<String, Object>> rows = db.list("""
                SELECT to_char(date_trunc('week', s.session_date), 'YYYY-MM-DD') AS week_start,
                       count(r.id) FILTER (WHERE r.status = 'PRESENT' OR (:lc AND r.status = 'LATE')) AS attended,
                       count(r.id) FILTER (WHERE r.status <> 'EXCUSED') AS total
                FROM attendance_sessions s JOIN attendance_records r ON r.session_id = s.id
                WHERE s.semester_id = :sem AND s.status = 'CLOSED'
                  AND (CAST(:c AS uuid) IS NULL OR s.course_id = :c)
                  AND (CAST(:ids AS text) IS NULL OR s.course_id::text = ANY(string_to_array(:ids, ',')))
                  AND (CAST(:st AS uuid) IS NULL OR r.student_id = :st)
                GROUP BY 1 ORDER BY 1""", "lc", lateCounts, "sem", semesterId, "c", courseId, "ids", ids, "st", studentId);
        for (var r : rows) {
            long t = n(r.get("total"));
            r.put("percentage", t == 0 ? null : Math.round(n(r.get("attended")) * 1000.0 / t) / 10.0);
        }
        return rows;
    }

    static long n(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
}
