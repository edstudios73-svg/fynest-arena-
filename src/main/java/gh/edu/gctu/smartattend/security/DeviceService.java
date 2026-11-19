package gh.edu.gctu.smartattend.security;

import gh.edu.gctu.smartattend.common.Db;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Tracks the browser/device through a long-lived random HttpOnly cookie. This is NOT a security
 * credential on its own (cookies can be cleared or copied); it is a correlation signal used for
 * fraud detection (e.g. one phone submitting attendance for several students) and to bind device
 * recovery approvals to the device that requested them. Possession is proven by WebAuthn passkeys.
 */
@Service
public class DeviceService {
    private final Db db;
    private final Cookies cookies;

    public DeviceService(Db db, Cookies cookies) {
        this.db = db;
        this.cookies = cookies;
    }

    /** Returns SHA-256 of the device cookie, issuing a new cookie if absent. */
    public byte[] cookieHash(HttpServletRequest req, HttpServletResponse res) {
        String v = Cookies.read(req, Cookies.DEVICE);
        if (v == null || v.length() < 20 || v.length() > 100) {
            v = Crypto.randomToken(32);
            cookies.set(res, Cookies.DEVICE, v, Duration.ofDays(730), "Lax");
        }
        return Crypto.sha256(v);
    }

    public static byte[] existingCookieHash(HttpServletRequest req) {
        String v = Cookies.read(req, Cookies.DEVICE);
        return v == null ? null : Crypto.sha256(v);
    }

    /** Find or create the device row for (user, cookie). */
    public UUID upsert(UUID userId, byte[] cookieHash, HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        if (ua != null && ua.length() > 300) ua = ua.substring(0, 300);
        return db.insertReturningId("""
                INSERT INTO devices(user_id, device_cookie_hash, user_agent, label)
                VALUES (:u, :h, :ua, :label)
                ON CONFLICT (user_id, device_cookie_hash) DO UPDATE SET last_seen_at = now(), user_agent = EXCLUDED.user_agent
                RETURNING id""", "u", userId, "h", cookieHash, "ua", ua, "label", describe(ua));
    }

    public UUID find(UUID userId, byte[] cookieHash) {
        if (cookieHash == null) return null;
        return db.scalar("SELECT id FROM devices WHERE user_id = :u AND device_cookie_hash = :h AND status = 'TRUSTED'",
                UUID.class, "u", userId, "h", cookieHash);
    }

    static String describe(String ua) {
        if (ua == null) return "Unknown device";
        String os = ua.contains("Android") ? "Android" : ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad"
                : ua.contains("Windows") ? "Windows" : ua.contains("Mac OS") ? "Mac" : ua.contains("Linux") ? "Linux" : "Device";
        String br = ua.contains("Edg/") ? "Edge" : ua.contains("Chrome/") ? "Chrome" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Safari/") ? "Safari" : "Browser";
        return os + " · " + br;
    }
}
