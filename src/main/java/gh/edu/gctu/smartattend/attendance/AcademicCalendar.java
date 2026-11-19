package gh.edu.gctu.smartattend.attendance;

import gh.edu.gctu.smartattend.common.ApiException;
import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.common.Json;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Semester, teaching-week and timetable resolution in the institution's timezone (Africa/Accra). */
@Service
public class AcademicCalendar {
    public static final ZoneId ZONE = ZoneId.of("Africa/Accra");
    private final Db db;
    private final Clock clock;

    public AcademicCalendar(Db db) {
        this.db = db;
        this.clock = Clock.system(ZONE);
    }

    public LocalDate today() { return LocalDate.now(clock); }
    public ZonedDateTime now() { return ZonedDateTime.now(clock); }

    public Map<String, Object> currentSemester() {
        Map<String, Object> s = db.one("""
                SELECT s.id, s.name, s.start_date, s.end_date, s.teaching_weeks, y.label AS academic_year
                FROM semesters s JOIN academic_years y ON y.id = s.academic_year_id WHERE s.is_current""");
        if (s == null) throw ApiException.conflict("NO_CURRENT_SEMESTER", "No current semester is configured.");
        return s;
    }

    public UUID currentSemesterId() { return (UUID) currentSemester().get("id"); }

    /** Teaching week number for a date, or null if the semester start date has not been set. */
    public Integer weekNumber(Map<String, Object> semester, LocalDate date) {
        LocalDate start = (LocalDate) semester.get("startDate");
        if (start == null || date.isBefore(start)) return null;
        return (int) (ChronoUnit.DAYS.between(start, date) / 7) + 1;
    }

    /** Effective venue/mode for one timetable slot on a given date, applying week exceptions. */
    public Map<String, Object> resolve(Map<String, Object> slot, LocalDate date, Map<String, Object> semester) {
        Integer week = weekNumber(semester, date);
        String mode = (String) slot.get("mode");
        Object venueId = slot.get("venueId");
        Object venueCode = slot.get("venueCode");
        boolean cancelled = false;
        String note = null;
        if (week != null) {
            Map<String, Object> ex = db.one("""
                    SELECT e.mode, e.cancelled, e.note, e.venue_id, v.code AS venue_code
                    FROM timetable_exceptions e LEFT JOIN venues v ON v.id = e.venue_id
                    WHERE e.timetable_id = :t AND e.week_number = :w""", "t", slot.get("id"), "w", week);
            if (ex != null) {
                if (ex.get("mode") != null) mode = (String) ex.get("mode");
                if (ex.get("venueId") != null) { venueId = ex.get("venueId"); venueCode = ex.get("venueCode"); }
                if ("ONLINE".equals(mode) && ex.get("venueId") == null) { venueId = null; venueCode = null; }
                cancelled = Boolean.TRUE.equals(ex.get("cancelled"));
                note = (String) ex.get("note");
            }
        }
        return Json.obj("timetableId", slot.get("id"), "date", date, "week", week, "mode", mode, "venueId", venueId,
                "venue", "ONLINE".equals(mode) ? "Online" : venueCode, "cancelled", cancelled, "note", note,
                "startTime", slot.get("startTime"), "endTime", slot.get("endTime"), "weekKnown", week != null);
    }

    public List<Map<String, Object>> slotsForGroup(UUID classGroupId, UUID semesterId) {
        return db.list("""
                SELECT t.id, t.course_id, t.day_of_week, t.start_time, t.end_time, t.mode, t.venue_id, t.notes,
                       v.code AS venue_code, c.code AS course_code, c.alt_code, c.title AS course_title,
                       (SELECT string_agg(trim(coalesce(l.title || ' ', '') || l.full_name), ', ')
                          FROM course_assignments a JOIN lecturers l ON l.id = a.lecturer_id
                          WHERE a.course_id = c.id AND a.semester_id = t.semester_id) AS lecturer
                FROM class_timetables t JOIN courses c ON c.id = t.course_id LEFT JOIN venues v ON v.id = t.venue_id
                WHERE t.class_group_id = :g AND t.semester_id = :s ORDER BY t.day_of_week, t.start_time""",
                "g", classGroupId, "s", semesterId);
    }

    /** Upcoming lectures for a class group over the next N days. */
    public List<Map<String, Object>> upcoming(UUID classGroupId, int days, UUID courseFilter) {
        Map<String, Object> sem = currentSemester();
        List<Map<String, Object>> slots = slotsForGroup(classGroupId, (UUID) sem.get("id"));
        List<Map<String, Object>> out = new ArrayList<>();
        ZonedDateTime now = now();
        for (int d = 0; d <= days; d++) {
            LocalDate date = today().plusDays(d);
            for (var s : slots) {
                if (((Number) s.get("dayOfWeek")).intValue() != date.getDayOfWeek().getValue()) continue;
                if (courseFilter != null && !courseFilter.equals(s.get("courseId"))) continue;
                LocalTime end = LocalTime.parse((String) s.get("endTime"));
                if (d == 0 && now.toLocalTime().isAfter(end)) continue;
                Map<String, Object> r = resolve(s, date, sem);
                if (Boolean.TRUE.equals(r.get("cancelled"))) continue;
                r.put("courseId", s.get("courseId"));
                r.put("courseCode", s.get("courseCode"));
                r.put("courseTitle", s.get("courseTitle"));
                r.put("lecturer", s.get("lecturer"));
                out.add(r);
            }
        }
        return out;
    }

    /** Timetable slot for a course on a given date (to pre-fill attendance sessions), or null. */
    public Map<String, Object> slotFor(UUID courseId, LocalDate date) {
        Map<String, Object> sem = currentSemester();
        Map<String, Object> slot = db.one("""
                SELECT t.*, v.code AS venue_code FROM class_timetables t LEFT JOIN venues v ON v.id = t.venue_id
                WHERE t.course_id = :c AND t.semester_id = :s AND t.day_of_week = :d ORDER BY t.start_time LIMIT 1""",
                "c", courseId, "s", sem.get("id"), "d", date.getDayOfWeek().getValue());
        if (slot == null) return null;
        Map<String, Object> r = resolve(slot, date, sem);
        r.put("classGroupId", slot.get("classGroupId"));
        return r;
    }
}
