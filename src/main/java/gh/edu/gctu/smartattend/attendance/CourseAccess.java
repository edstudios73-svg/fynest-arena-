package gh.edu.gctu.smartattend.attendance;

import gh.edu.gctu.smartattend.common.ApiException;
import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.security.CurrentUser;
import gh.edu.gctu.smartattend.security.Role;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Object-level authorisation: lecturers may only act on courses assigned to them. */
@Component
public class CourseAccess {
    private final Db db;

    public CourseAccess(Db db) { this.db = db; }

    public void requireCourse(CurrentUser u, UUID courseId) {
        if (u.role().isAdmin()) {
            if (db.scalar("SELECT 1 FROM courses WHERE id = :c", Integer.class, "c", courseId) == null)
                throw ApiException.notFound("COURSE_NOT_FOUND", "Course not found.");
            return;
        }
        if (u.role() != Role.LECTURER || u.lecturerId() == null) throw ApiException.forbidden("Not allowed.");
        Integer ok = db.scalar("SELECT 1 FROM course_assignments WHERE course_id = :c AND lecturer_id = :l LIMIT 1",
                Integer.class, "c", courseId, "l", u.lecturerId());
        if (ok == null) throw ApiException.forbidden("You are not assigned to this course.");
    }

    /** Returns the session row after checking the caller may manage it. */
    public Map<String, Object> requireSession(CurrentUser u, UUID sessionId) {
        Map<String, Object> s = db.one("SELECT * FROM attendance_sessions WHERE id = :id", "id", sessionId);
        if (s == null) throw ApiException.notFound("SESSION_NOT_FOUND", "Attendance session not found.");
        requireCourse(u, (UUID) s.get("courseId"));
        return s;
    }
}
