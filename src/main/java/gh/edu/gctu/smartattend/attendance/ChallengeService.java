package gh.edu.gctu.smartattend.attendance;

import gh.edu.gctu.smartattend.common.ApiException;
import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.common.Json;
import gh.edu.gctu.smartattend.common.SettingsService;
import gh.edu.gctu.smartattend.config.AppProperties;
import gh.edu.gctu.smartattend.security.Crypto;
import gh.edu.gctu.smartattend.security.KeyProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rotating attendance challenges. Each challenge has
 *  - a 6-digit OTP from SecureRandom (unpredictable; not time- or student-derived), stored only as
 *    HMAC-SHA256(server key, sessionId:otp) so codes are session-bound and a DB leak reveals nothing;
 *  - an opaque 192-bit QR token (stored as SHA-256), embedded in a link — no student data, no OTP.
 * For display on the lecturer screen from any server instance, "otp|token" is also stored encrypted
 * (AES-256-GCM, bound to the challenge id); verification never uses it, and it is wiped on expiry.
 */
@Service
public class ChallengeService {
    private final Db db;
    private final KeyProvider keys;
    private final SettingsService settings;
    private final AppProperties props;

    public ChallengeService(Db db, KeyProvider keys, SettingsService settings, AppProperties props) {
        this.db = db;
        this.keys = keys;
        this.settings = settings;
        this.props = props;
    }

    byte[] otpHash(UUID sessionId, String otp) {
        return Crypto.hmac(keys.hmacKey(), "otp:" + sessionId + ":" + otp);
    }

    /** Current challenge for display on the lecturer screen; rotates automatically when expired. */
    @Transactional
    public Map<String, Object> current(UUID sessionId) {
        Map<String, Object> s = db.one("SELECT status FROM attendance_sessions WHERE id = :id FOR UPDATE", "id", sessionId);
        if (s == null || !"OPEN".equals(s.get("status"))) throw ApiException.conflict("SESSION_CLOSED", "This attendance session is closed.");
        Map<String, Object> c = db.one("""
                SELECT id, issued_at, expires_at, display_ciphertext, display_iv FROM attendance_challenges
                WHERE session_id = :s AND revoked_at IS NULL AND expires_at > now() + interval '1 second'
                  AND display_ciphertext IS NOT NULL
                ORDER BY expires_at DESC LIMIT 1""", "s", sessionId);
        String otp, token;
        if (c != null) {
            String[] parts = new String(Crypto.aesGcmDecrypt(keys.hmacKey(), (byte[]) c.get("displayIv"),
                    (byte[]) c.get("displayCiphertext"), aad((UUID) c.get("id"))), java.nio.charset.StandardCharsets.UTF_8).split("\\|");
            otp = parts[0];
            token = parts[1];
        } else {
            db.update("UPDATE attendance_challenges SET revoked_at = now() WHERE session_id = :s AND revoked_at IS NULL AND expires_at > now()", "s", sessionId);
            otp = Crypto.sixDigitCode();
            token = Crypto.randomToken(24);
            int ttl = settings.getInt("attendance.otpRotationSeconds");
            UUID id = UUID.randomUUID();
            Crypto.Sealed sealed = Crypto.aesGcmEncrypt(keys.hmacKey(), (otp + "|" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8), aad(id));
            c = db.one("""
                    INSERT INTO attendance_challenges(id, session_id, otp_hash, qr_token_hash, expires_at, display_ciphertext, display_iv)
                    VALUES (:id, :s, :oh, :qh, now() + make_interval(secs => :ttl), :dc, :di) RETURNING id, issued_at, expires_at""",
                    "id", id, "s", sessionId, "oh", otpHash(sessionId, otp), "qh", Crypto.sha256(token), "ttl", ttl,
                    "dc", sealed.ciphertext(), "di", sealed.iv());
        }
        String url = props.baseUrl().replaceAll("/$", "") + "/student.html#/attend?t=" + token;
        return Json.obj("challengeId", c.get("id"), "otp", otp, "qrUrl", url, "qrSvg", QrSvg.svg(url),
                "issuedAt", c.get("issuedAt"), "expiresAt", c.get("expiresAt"), "serverNow", Instant.now(),
                "rotationSeconds", settings.getInt("attendance.otpRotationSeconds"));
    }

    public record Valid(UUID challengeId, UUID sessionId) {}

    /** Validate an OTP for one specific session. */
    public Valid validateOtp(UUID sessionId, String otp) {
        if (otp == null || !otp.trim().matches("\\d{6}")) throw ApiException.badRequest("INVALID_OTP", "Enter the 6-digit code shown by your lecturer.");
        Map<String, Object> c = db.one("""
                SELECT id, expires_at > now() AS live, revoked_at IS NULL OR expires_at > now() AS not_revoked
                FROM attendance_challenges WHERE session_id = :s AND otp_hash = :h ORDER BY issued_at DESC LIMIT 1""",
                "s", sessionId, "h", otpHash(sessionId, otp.trim()));
        if (c == null) throw ApiException.badRequest("INVALID_OTP", "Invalid code. Check the code on the lecturer's screen.");
        if (!Boolean.TRUE.equals(c.get("live"))) throw ApiException.badRequest("EXPIRED_OTP", "This code has expired. Enter the new code on the screen.");
        return new Valid((UUID) c.get("id"), sessionId);
    }

    /** Validate a QR token. Returns the session it belongs to. */
    public Valid validateQr(String token) {
        if (token == null || token.length() < 20 || token.length() > 64) throw ApiException.badRequest("INVALID_QR", "This QR code is not a SmartAttend attendance code.");
        Map<String, Object> c = db.one("""
                SELECT id, session_id, expires_at > now() AS live FROM attendance_challenges WHERE qr_token_hash = :h""",
                "h", Crypto.sha256(token));
        if (c == null) throw ApiException.badRequest("INVALID_QR", "This QR code is not valid.");
        if (!Boolean.TRUE.equals(c.get("live"))) throw ApiException.badRequest("EXPIRED_QR", "This QR code has expired. Scan the current code on the screen.");
        return new Valid((UUID) c.get("id"), (UUID) c.get("sessionId"));
    }

    public void revokeAll(UUID sessionId) {
        db.update("UPDATE attendance_challenges SET revoked_at = now(), expires_at = LEAST(expires_at, now()) WHERE session_id = :s AND revoked_at IS NULL", "s", sessionId);
    }

    private static byte[] aad(UUID challengeId) {
        return ("smartattend-otp-display:" + challengeId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Wipe display copies of expired challenges (only the HMAC remains). */
    public void purgeExpiredDisplays() {
        db.update("UPDATE attendance_challenges SET display_ciphertext = NULL, display_iv = NULL WHERE display_ciphertext IS NOT NULL AND expires_at < now() - interval '30 seconds'");
    }
}
