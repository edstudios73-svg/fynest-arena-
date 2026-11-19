package gh.edu.gctu.smartattend.security;

import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.common.NotificationService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Records suspicious signals for human review. Alerts never change attendance on their own:
 * a single unreliable signal must not punish a student.
 */
@Service
public class FraudService {
    private final Db db;
    private final NotificationService notifications;

    public FraudService(Db db, NotificationService notifications) {
        this.db = db;
        this.notifications = notifications;
    }

    public void raise(String kind, String severity, UUID studentId, UUID sessionId, UUID deviceId, Map<String, Object> detail) {
        Integer recent = db.scalar("""
                SELECT count(*)::int FROM fraud_alerts WHERE kind = :k AND status = 'OPEN'
                  AND student_id IS NOT DISTINCT FROM :s AND session_id IS NOT DISTINCT FROM :sess
                  AND created_at > now() - interval '30 minutes'""",
                Integer.class, "k", kind, "s", studentId, "sess", sessionId);
        if (recent != null && recent > 0) return; // de-duplicate bursts
        db.update("""
                INSERT INTO fraud_alerts(kind, severity, student_id, session_id, device_id, detail)
                VALUES (:k, :sev, :s, :sess, :d, CAST(:det AS jsonb))""",
                "k", kind, "sev", severity, "s", studentId, "sess", sessionId, "d", deviceId, "det", db.json(detail));
        if ("HIGH".equals(severity)) {
            notifications.notifyAdmins("Security alert: " + kind.replace('_', ' ').toLowerCase(),
                    "A high-severity alert needs review.", "/admin.html#/security");
        }
    }
}
