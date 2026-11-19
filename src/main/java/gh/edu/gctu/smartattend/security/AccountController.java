package gh.edu.gctu.smartattend.security;

import gh.edu.gctu.smartattend.common.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Staff (lecturer / administrator) password sign-in, session info, notifications, sign-out. */
@RestController
public class AccountController {
    public static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);
    // Pre-computed hash used to equalise timing when the account does not exist.
    private static final String DUMMY_HASH = BCRYPT.encode("not-a-real-password-" + Crypto.randomToken(8));

    private final Db db;
    private final SessionService sessions;
    private final DeviceService devices;
    private final RateLimiter limiter;
    private final AuditService audit;
    private final Cookies cookies;

    public AccountController(Db db, SessionService sessions, DeviceService devices, RateLimiter limiter, AuditService audit, Cookies cookies) {
        this.db = db;
        this.sessions = sessions;
        this.devices = devices;
        this.limiter = limiter;
        this.audit = audit;
        this.cookies = cookies;
    }

    public record StaffLogin(String email, String password, String portal) {}
    public record PasswordChange(String currentPassword, String newPassword) {}

    @PostMapping("/api/auth/staff/login")
    public Map<String, Object> staffLogin(@RequestBody StaffLogin body, HttpServletRequest req, HttpServletResponse res) {
        limiter.check("staff-login-ip", req.getRemoteAddr(), 20, 300);
        String email = body.email() == null ? "" : body.email().trim().toLowerCase();
        limiter.check("staff-login-acct", email, 10, 900);
        Map<String, Object> u = db.one("""
                SELECT id, role, password_hash, status, locked_until > now() AS locked, must_change_password
                FROM app_users WHERE lower(email) = :e AND role IN ('LECTURER','ADMIN','SUPER_ADMIN')""", "e", email);
        String hash = u == null || u.get("passwordHash") == null ? DUMMY_HASH : (String) u.get("passwordHash");
        boolean match = body.password() != null && BCRYPT.matches(body.password(), hash);
        if (u == null || u.get("passwordHash") == null || !match) {
            if (u != null) {
                db.update("""
                        UPDATE app_users SET failed_logins = failed_logins + 1,
                          locked_until = CASE WHEN failed_logins + 1 >= 5 THEN now() + interval '15 minutes' ELSE locked_until END
                        WHERE id = :u""", "u", u.get("id"));
                audit.logAs((UUID) u.get("id"), (String) u.get("role"), "LOGIN_FAILED", "user", u.get("id"), Json.obj());
            }
            throw ApiException.unauthorized("Incorrect email or password.");
        }
        if (Boolean.TRUE.equals(u.get("locked"))) throw ApiException.tooMany("Account temporarily locked after failed attempts. Try again in 15 minutes.");
        if (!"ACTIVE".equals(u.get("status"))) throw ApiException.forbidden("This account is not active.");
        String role = (String) u.get("role");
        if ("lecturer".equals(body.portal()) && !"LECTURER".equals(role)) throw ApiException.forbidden("Please use the administrator sign-in page.");
        if ("admin".equals(body.portal()) && "LECTURER".equals(role)) throw ApiException.forbidden("Please use the lecturer sign-in page.");
        UUID userId = (UUID) u.get("id");
        UUID deviceId = devices.upsert(userId, devices.cookieHash(req, res), req);
        sessions.create(userId, deviceId, req, res);
        audit.logAs(userId, role, "LOGIN", "user", userId, Json.obj());
        return Json.obj("role", role, "mustChangePassword", u.get("mustChangePassword"));
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
        String t = Cookies.read(req, Cookies.SESSION);
        if (t != null) sessions.revokeToken(t);
        cookies.clear(res, Cookies.SESSION);
        return Json.obj("signedOut", true);
    }

    @GetMapping("/api/auth/session")
    public Map<String, Object> session() {
        CurrentUser u = AuthContext.get();
        if (u == null) return Json.obj("authenticated", false);
        return me();
    }

    @GetMapping("/api/me")
    public Map<String, Object> me() {
        CurrentUser u = AuthContext.require();
        String status = sessions.userStatus(u.userId());
        Map<String, Object> m = Json.obj("authenticated", true, "userId", u.userId(), "role", u.role(), "name", u.displayName(),
                "csrfToken", u.csrfToken(), "status", status, "mustChangePassword", u.mustChangePassword());
        if (u.studentId() != null) {
            m.put("student", db.one("""
                    SELECT s.index_number, s.title, s.first_name, s.last_name, s.other_names, g.name AS group_name, g.level, p.name AS programme
                    FROM students s JOIN class_groups g ON g.id = s.class_group_id JOIN programmes p ON p.id = g.programme_id
                    WHERE s.id = :s""", "s", u.studentId()));
        }
        m.put("unreadNotifications", db.scalar("SELECT count(*)::int FROM notifications WHERE user_id = :u AND read_at IS NULL", Integer.class, "u", u.userId()));
        return m;
    }

    @PostMapping("/api/me/password")
    public Map<String, Object> changePassword(@RequestBody PasswordChange body) {
        CurrentUser u = AuthContext.require();
        if (u.role() == Role.STUDENT) throw ApiException.forbidden("Students sign in with passkeys, not passwords.");
        limiter.check("pw-change", u.userId().toString(), 10, 3600);
        String hash = db.scalar("SELECT password_hash FROM app_users WHERE id = :u", String.class, "u", u.userId());
        if (hash == null || body.currentPassword() == null || !BCRYPT.matches(body.currentPassword(), hash)) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Your current password is incorrect.");
        }
        validatePassword(body.newPassword());
        if (BCRYPT.matches(body.newPassword(), hash)) throw ApiException.badRequest("WEAK_PASSWORD", "Choose a password different from the current one.");
        db.update("UPDATE app_users SET password_hash = :h, must_change_password = false WHERE id = :u",
                "h", BCRYPT.encode(body.newPassword()), "u", u.userId());
        db.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = :u AND id <> :sid AND revoked_at IS NULL", "u", u.userId(), "sid", u.sessionId());
        audit.log("PASSWORD_CHANGED", "user", u.userId(), Json.obj());
        return Json.obj("changed", true);
    }

    public static void validatePassword(String p) {
        if (p == null || p.length() < 12 || p.length() > 128) throw ApiException.badRequest("WEAK_PASSWORD", "Password must be 12-128 characters.");
        int classes = 0;
        if (p.chars().anyMatch(Character::isLowerCase)) classes++;
        if (p.chars().anyMatch(Character::isUpperCase)) classes++;
        if (p.chars().anyMatch(Character::isDigit)) classes++;
        if (p.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes++;
        if (classes < 3) throw ApiException.badRequest("WEAK_PASSWORD", "Use at least three of: lowercase, uppercase, digits, symbols.");
    }

    // ---------------------------------------------------------------- notifications (any role)

    @GetMapping("/api/notifications")
    public List<Map<String, Object>> notifications() {
        CurrentUser u = AuthContext.require();
        return db.list("SELECT id, title, body, link, read_at, created_at FROM notifications WHERE user_id = :u ORDER BY created_at DESC LIMIT 100", "u", u.userId());
    }

    @PostMapping("/api/notifications/read")
    public Map<String, Object> readAll() {
        CurrentUser u = AuthContext.require();
        int n = db.update("UPDATE notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL", "u", u.userId());
        return Json.obj("updated", n);
    }
}
