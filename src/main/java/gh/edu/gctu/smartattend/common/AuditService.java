package gh.edu.gctu.smartattend.common;

import gh.edu.gctu.smartattend.security.AuthContext;
import gh.edu.gctu.smartattend.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** Append-only audit trail (UPDATE/DELETE are blocked by a database trigger). */
@Service
public class AuditService {
    private final Db db;

    public AuditService(Db db) { this.db = db; }

    public void log(String action, String entityType, Object entityId, Map<String, Object> detail) {
        CurrentUser u = AuthContext.get();
        logAs(u == null ? null : u.userId(), u == null ? null : u.role().name(), action, entityType, entityId, detail);
    }

    public void logAs(UUID actor, String role, String action, String entityType, Object entityId, Map<String, Object> detail) {
        db.update("""
                INSERT INTO audit_logs(actor_user_id, actor_role, action, entity_type, entity_id, detail, ip)
                VALUES (:a, :r, :act, :et, :eid, CAST(:d AS jsonb), :ip)""",
                "a", actor, "r", role, "act", action, "et", entityType,
                "eid", entityId == null ? null : entityId.toString(),
                "d", db.json(detail == null ? Map.of() : detail), "ip", AuthContext.ip());
    }
}
