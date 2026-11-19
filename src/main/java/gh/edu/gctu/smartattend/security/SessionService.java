package gh.edu.gctu.smartattend.security;

import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Server-side sessions: an opaque random cookie whose SHA-256 is stored, so a DB leak does not expose live tokens. */
@Service
public class SessionService {
    private static final int IDLE_MINUTES = 120;
    private final Db db;
    private final Cookies cookies;
    private final AppProperties props;

    public SessionService(Db db, Cookies cookies, AppProperties props) {
        this.db = db;
        this.cookies = cookies;
        this.props = props;
    }

    public void create(UUID userId, UUID deviceId, HttpServletRequest req, HttpServletResponse res) {
        // Session fixation defence: always mint a fresh token on login.
        String old = Cookies.read(req, Cookies.SESSION);
        if (old != null) revokeToken(old);
        String token = Crypto.randomToken(32);
        db.update("""
                INSERT INTO auth_sessions(token_hash, user_id, csrf_token, device_id, ip, user_agent, expires_at)
                VALUES (:h, :u, :c, :d, :ip, :ua, now() + make_interval(hours => :hrs))""",
                "h", Crypto.sha256(token), "u", userId, "c", Crypto.randomToken(24), "d", deviceId,
                "ip", req.getRemoteAddr(), "ua", truncate(req.getHeader("User-Agent")), "hrs", props.sessionHours());
        db.update("UPDATE app_users SET last_login_at = now(), failed_logins = 0, locked_until = NULL WHERE id = :u", "u", userId);
        cookies.set(res, Cookies.SESSION, token, Duration.ofHours(props.sessionHours()), "Lax");
    }

    /** Resolve a session cookie to the current user, or null. */
    public CurrentUser resolve(String token) {
        if (token == null || token.length() > 100) return null;
        Map<String, Object> r = db.one("""
                SELECT s.id AS sid, s.csrf_token, s.device_id, s.last_seen_at, u.id AS uid, u.role, u.status, u.must_change_password,
                       st.id AS student_id, l.id AS lecturer_id,
                       COALESCE(st.first_name || ' ' || st.last_name, l.full_name, a.full_name, u.email) AS display_name,
                       (now() - s.last_seen_at) > make_interval(mins => :idle) AS idle_expired
                FROM auth_sessions s JOIN app_users u ON u.id = s.user_id
                LEFT JOIN students st ON st.user_id = u.id
                LEFT JOIN lecturers l ON l.user_id = u.id
                LEFT JOIN administrators a ON a.user_id = u.id
                WHERE s.token_hash = :h AND s.revoked_at IS NULL AND s.expires_at > now()""",
                "h", Crypto.sha256(token), "idle", IDLE_MINUTES);
        if (r == null) return null;
        if (Boolean.TRUE.equals(r.get("idleExpired"))) { revokeToken(token); return null; }
        Role role = Role.valueOf((String) r.get("role"));
        String status = (String) r.get("status");
        // Students awaiting approval may sign in to see their status, but suspended/disabled accounts may not.
        if (!"ACTIVE".equals(status) && !"PENDING_APPROVAL".equals(status)) return null;
        db.update("UPDATE auth_sessions SET last_seen_at = now() WHERE id = :id AND last_seen_at < now() - interval '1 minute'", "id", r.get("sid"));
        return new CurrentUser((UUID) r.get("uid"), role, (UUID) r.get("sid"), (String) r.get("csrfToken"),
                (UUID) r.get("deviceId"), (UUID) r.get("studentId"), (UUID) r.get("lecturerId"),
                (String) r.get("displayName"), Boolean.TRUE.equals(r.get("mustChangePassword")));
    }

    public String userStatus(UUID userId) {
        return db.scalar("SELECT status FROM app_users WHERE id = :u", String.class, "u", userId);
    }

    public void revokeToken(String token) {
        db.update("UPDATE auth_sessions SET revoked_at = now() WHERE token_hash = :h AND revoked_at IS NULL", "h", Crypto.sha256(token));
    }

    public void revokeAllForUser(UUID userId) {
        db.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL", "u", userId);
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 300 ? s.substring(0, 300) : s);
    }
}
