package gh.edu.gctu.smartattend.common;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class NotificationService {
    private final Db db;

    public NotificationService(Db db) { this.db = db; }

    public void notify(UUID userId, String title, String body, String link) {
        if (userId == null) return;
        db.update("INSERT INTO notifications(user_id, title, body, link) VALUES (:u, :t, :b, :l)",
                "u", userId, "t", title, "b", body, "l", link);
    }

    /** Notify every registered student enrolled in a course. */
    public int notifyCourseStudents(UUID courseId, UUID semesterId, String title, String body, String link) {
        return db.update("""
                INSERT INTO notifications(user_id, title, body, link)
                SELECT s.user_id, :t, :b, :l FROM course_enrollments e JOIN students s ON s.id = e.student_id
                WHERE e.course_id = :c AND e.semester_id = :sem AND e.status = 'ENROLLED' AND s.user_id IS NOT NULL""",
                "t", title, "b", body, "l", link, "c", courseId, "sem", semesterId);
    }

    public void notifyAdmins(String title, String body, String link) {
        db.update("""
                INSERT INTO notifications(user_id, title, body, link)
                SELECT id, :t, :b, :l FROM app_users WHERE role IN ('ADMIN','SUPER_ADMIN') AND status = 'ACTIVE'""",
                "t", title, "b", body, "l", link);
    }
}
