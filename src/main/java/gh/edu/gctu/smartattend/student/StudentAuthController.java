package gh.edu.gctu.smartattend.student;

import com.fasterxml.jackson.databind.JsonNode;
import gh.edu.gctu.smartattend.common.*;
import gh.edu.gctu.smartattend.face.FaceService;
import gh.edu.gctu.smartattend.security.*;
import gh.edu.gctu.smartattend.webauthn.WebAuthnService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * Student registration, sign-in and device recovery. Every step is validated server-side against
 * a short-lived flow record; the browser cannot skip steps.
 */
@RestController
@RequestMapping("/api/auth/student")
public class StudentAuthController {
    private final Db db;
    private final StudentDirectory directory;
    private final FlowService flows;
    private final FaceService face;
    private final WebAuthnService webauthn;
    private final SessionService sessions;
    private final DeviceService devices;
    private final RateLimiter limiter;
    private final SettingsService settings;
    private final AuditService audit;
    private final NotificationService notifications;
    private final FraudService fraud;

    public StudentAuthController(Db db, StudentDirectory directory, FlowService flows, FaceService face, WebAuthnService webauthn,
                                 SessionService sessions, DeviceService devices, RateLimiter limiter, SettingsService settings,
                                 AuditService audit, NotificationService notifications, FraudService fraud) {
        this.db = db;
        this.directory = directory;
        this.flows = flows;
        this.face = face;
        this.webauthn = webauthn;
        this.sessions = sessions;
        this.devices = devices;
        this.limiter = limiter;
        this.settings = settings;
        this.audit = audit;
        this.notifications = notifications;
        this.fraud = fraud;
    }

    public record IndexBody(String indexNumber) {}
    public record ConfirmBody(boolean confirmed, String note) {}
    public record ConsentBody(boolean accepted, String consentVersion) {}
    public record ReasonBody(String reason) {}
    public record RecoverStartBody(String indexNumber, String reason) {}

    // ------------------------------------------------------------------ Registration

    @PostMapping("/register/start")
    public Map<String, Object> registerStart(@RequestBody IndexBody body, HttpServletRequest req, HttpServletResponse res) {
        limitIndexLookups(req);
        Map<String, Object> s = directory.byIndex(body.indexNumber());
        if (s == null) {
            throw ApiException.notFound("ACCOUNT_NOT_FOUND",
                    "We could not find this index number in the official register. Check it and try again, or contact the department.");
        }
        if (!"ACTIVE".equals(s.get("registerStatus"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RECORD_UNDER_REVIEW", "Your register entry is under review. Please contact the department.");
        }
        if (s.get("userId") != null && !"DISABLED".equals(s.get("accountStatus"))) {
            throw ApiException.conflict("ALREADY_REGISTERED",
                    "This index number is already registered. Sign in instead, or use account recovery if you changed phones.");
        }
        flows.start("REGISTRATION", (UUID) s.get("id"), "CONFIRM_NAME", res);
        devices.cookieHash(req, res);
        return Json.obj("indexNumber", s.get("indexNumber"), "fullName", StudentDirectory.fullName(s),
                "programme", s.get("programme"), "level", s.get("level"), "group", s.get("groupName"));
    }

    @PostMapping("/register/confirm")
    public Map<String, Object> registerConfirm(@RequestBody ConfirmBody body, HttpServletRequest req, HttpServletResponse res) {
        var f = flows.require(req, "REGISTRATION", "CONFIRM_NAME");
        if (!body.confirmed()) {
            // The student says the official name is wrong: route to an administrator correction request.
            db.update("""
                    INSERT INTO recovery_requests(student_id, kind, reason, expires_at)
                    VALUES (:s, 'REGISTRATION_DISPUTE', :r, now() + interval '30 days')""",
                    "s", f.get("studentId"), "r", "Name on register reported incorrect: " + clip(body.note(), 400));
            notifications.notifyAdmins("Register correction requested", "A student reported an error in their register entry.", "/admin.html#/recovery");
            flows.complete((UUID) f.get("id"), res);
            return Json.obj("next", "DISPUTE_SUBMITTED");
        }
        flows.stage((UUID) f.get("id"), "CONSENT");
        return Json.obj("next", "CONSENT", "consentVersion", FaceService.CONSENT_VERSION);
    }

    @PostMapping("/register/consent")
    public Map<String, Object> registerConsent(@RequestBody ConsentBody body, HttpServletRequest req) {
        var f = flows.require(req, "REGISTRATION", "CONSENT");
        if (!body.accepted() || !FaceService.CONSENT_VERSION.equals(body.consentVersion())) {
            throw ApiException.badRequest("CONSENT_REQUIRED", "Biometric consent is required to continue. Choose the supervised alternative if you do not consent.");
        }
        db.update("UPDATE auth_flows SET consent_at = now(), stage = 'FACE' WHERE id = :id", "id", f.get("id"));
        audit.logAs(null, "STUDENT", "BIOMETRIC_CONSENT_GIVEN", "student", f.get("studentId"), Json.obj("version", body.consentVersion()));
        return Json.obj("next", "FACE");
    }

    @PostMapping("/register/face/challenge")
    public Map<String, Object> registerFaceChallenge(HttpServletRequest req) {
        var f = flows.require(req, "REGISTRATION", "FACE");
        return face.createChallenge((UUID) f.get("studentId"), "ENROL");
    }

    @PostMapping(value = "/register/face", consumes = "multipart/form-data")
    public Map<String, Object> registerFace(@RequestParam UUID challengeId, @RequestParam("frames") List<MultipartFile> frames,
                                            HttpServletRequest req, HttpServletResponse res) throws IOException {
        var f = flows.require(req, "REGISTRATION", "FACE");
        var out = face.verify((UUID) f.get("studentId"), challengeId, "ENROL", bytes(frames));
        if (!out.passed()) return Json.obj("passed", false, "reason", out.failure(), "message", out.message());
        db.update("UPDATE auth_flows SET face_passed_at = now(), stage = 'PASSKEY' WHERE id = :id", "id", f.get("id"));
        if (!settings.getBool("security.passkeyOnLogin")) {
            return finishRegistration(f, null, null, req, res);
        }
        return Json.obj("passed", true, "next", "PASSKEY");
    }

    /** Create/activate the student account after the required checks, and sign the student in. */
    @Transactional
    Map<String, Object> finishRegistration(Map<String, Object> f, byte[] challenge, String credentialJson,
                                           HttpServletRequest req, HttpServletResponse res) {
        UUID studentId = (UUID) f.get("studentId");
        Map<String, Object> s = db.one("SELECT s.*, u.status AS account_status FROM students s LEFT JOIN app_users u ON u.id = s.user_id WHERE s.id = :id FOR UPDATE OF s", "id", studentId);
        UUID userId = (UUID) s.get("userId");
        if (userId == null) {
            userId = db.insertReturningId("INSERT INTO app_users(role, status) VALUES ('STUDENT', 'DISABLED') RETURNING id");
            db.update("UPDATE students SET user_id = :u, updated_at = now() WHERE id = :s", "u", userId, "s", studentId);
        } else if (!"DISABLED".equals(s.get("accountStatus"))) {
            throw ApiException.conflict("ALREADY_REGISTERED", "This index number is already registered.");
        }
        if (!face.hasTemplate(studentId)) throw ApiException.badRequest("FLOW_STEP", "Face enrolment missing.");
        UUID deviceId = devices.upsert(userId, devices.cookieHash(req, res), req);
        if (credentialJson != null) webauthn.register(userId, deviceId, challenge, credentialJson, "Registered device");
        boolean approval = settings.getBool("security.registrationRequiresApproval");
        String status = approval ? "PENDING_APPROVAL" : "ACTIVE";
        db.update("UPDATE app_users SET status = :st WHERE id = :u", "st", status, "u", userId);
        flows.complete((UUID) f.get("id"), res);
        audit.logAs(userId, "STUDENT", "STUDENT_REGISTERED", "student", studentId, Json.obj("status", status));
        sharedDeviceCheck(studentId, deviceId, req);
        if (approval) notifications.notifyAdmins("New student registration", "A student is awaiting approval.", "/admin.html#/students?status=PENDING_APPROVAL");
        sessions.create(userId, deviceId, req, res);
        return Json.obj("passed", true, "registered", true, "status", status);
    }

    @PostMapping("/register/passkey/options")
    @Transactional
    public Map<String, Object> registerPasskeyOptions(HttpServletRequest req) {
        var f = flows.require(req, "REGISTRATION", "PASSKEY");
        UUID studentId = (UUID) f.get("studentId");
        Map<String, Object> s = db.one("SELECT s.*, u.status AS account_status FROM students s LEFT JOIN app_users u ON u.id = s.user_id WHERE s.id = :id FOR UPDATE OF s", "id", studentId);
        UUID userId = (UUID) s.get("userId");
        if (userId == null) {
            userId = db.insertReturningId("INSERT INTO app_users(role, status) VALUES ('STUDENT', 'DISABLED') RETURNING id");
            db.update("UPDATE students SET user_id = :u, updated_at = now() WHERE id = :s", "u", userId, "s", studentId);
        } else if (!"DISABLED".equals(s.get("accountStatus"))) {
            throw ApiException.conflict("ALREADY_REGISTERED", "This index number is already registered.");
        }
        byte[] challenge = Crypto.randomBytes(32);
        db.update("UPDATE auth_flows SET webauthn_challenge = :c WHERE id = :id", "c", challenge, "id", f.get("id"));
        return webauthn.creationOptions(userId, challenge, (String) s.get("indexNumber"), StudentDirectory.fullName(s));
    }

    @PostMapping("/register/passkey/verify")
    public Map<String, Object> registerPasskeyVerify(@RequestBody JsonNode credential, HttpServletRequest req, HttpServletResponse res) {
        var f = flows.require(req, "REGISTRATION", "PASSKEY");
        if (f.get("facePassedAt") == null || f.get("webauthnChallenge") == null) throw ApiException.badRequest("FLOW_STEP", "Complete face enrolment first.");
        db.update("UPDATE auth_flows SET webauthn_challenge = NULL WHERE id = :id", "id", f.get("id"));
        return finishRegistration(f, (byte[]) f.get("webauthnChallenge"), credential.toString(), req, res);
    }

    /** For students whose device cannot create a passkey or who decline biometrics. */
    @PostMapping("/register/alternative")
    public Map<String, Object> registerAlternative(@RequestBody ReasonBody body, HttpServletRequest req, HttpServletResponse res) {
        var f = flows.require(req, "REGISTRATION");
        db.update("""
                INSERT INTO recovery_requests(student_id, kind, reason, device_cookie_hash, expires_at)
                VALUES (:s, 'ALT_VERIFICATION', :r, :h, now() + interval '30 days')""",
                "s", f.get("studentId"), "r", clip(body.reason(), 500), "h", DeviceService.existingCookieHash(req));
        notifications.notifyAdmins("Supervised verification requested", "A student needs an alternative verification arrangement.", "/admin.html#/recovery");
        flows.complete((UUID) f.get("id"), res);
        return Json.obj("submitted", true);
    }

    // ------------------------------------------------------------------ Sign in

    @PostMapping("/login/start")
    public Map<String, Object> loginStart(@RequestBody IndexBody body, HttpServletRequest req, HttpServletResponse res) {
        limitIndexLookups(req);
        Map<String, Object> s = directory.byIndex(body.indexNumber());
        if (s == null || s.get("userId") == null || "DISABLED".equals(s.get("accountStatus"))) {
            throw ApiException.notFound("ACCOUNT_NOT_FOUND", "No SmartAttend account was found for this index number. Register first.");
        }
        if ("SUSPENDED".equals(s.get("accountStatus"))) {
            throw ApiException.forbidden("This account is suspended. Please contact the department.");
        }
        checkLock((UUID) s.get("userId"));
        if (!settings.getBool("security.passkeyOnLogin")) {
            // Index number + live face check (the face check is mandatory in this mode).
            flows.start("LOGIN", (UUID) s.get("id"), "FACE", res);
            devices.cookieHash(req, res);
            return Json.obj("next", "FACE", "faceRequired", true);
        }
        UUID flowId = flows.start("LOGIN", (UUID) s.get("id"), "PASSKEY", res);
        byte[] challenge = Crypto.randomBytes(32);
        db.update("UPDATE auth_flows SET webauthn_challenge = :c WHERE id = :id", "c", challenge, "id", flowId);
        devices.cookieHash(req, res);
        return Json.obj("next", "PASSKEY", "publicKey", webauthn.requestOptions((UUID) s.get("userId"), challenge), "faceRequired", settings.getBool("security.faceOnLogin"));
    }

    @PostMapping("/login/passkey")
    public Map<String, Object> loginPasskey(@RequestBody JsonNode credential, HttpServletRequest req, HttpServletResponse res) {
        var f = flows.require(req, "LOGIN", "PASSKEY");
        UUID userId = (UUID) f.get("userId");
        byte[] challenge = (byte[]) f.get("webauthnChallenge");
        if (challenge == null) throw ApiException.badRequest("FLOW_EXPIRED", "Please start sign-in again.");
        db.update("UPDATE auth_flows SET webauthn_challenge = NULL WHERE id = :id", "id", f.get("id"));
        checkLock(userId);
        try {
            webauthn.authenticate(userId, challenge, credential.toString());
        } catch (ApiException e) {
            recordLoginFailure(userId);
            throw e;
        }
        if (settings.getBool("security.faceOnLogin")) {
            flows.stage((UUID) f.get("id"), "FACE");
            return Json.obj("next", "FACE");
        }
        return finishLogin(f, userId, req, res);
    }

    /**
     * If the student's face template was erased (retention / deletion request), they re-enrol here,
     * after giving consent again, instead of being locked out.
     */
    @PostMapping("/login/face/challenge")
    public Map<String, Object> loginFaceChallenge(@RequestBody(required = false) ConsentBody consent, HttpServletRequest req) {
        var f = flows.require(req, "LOGIN", "FACE");
        UUID studentId = (UUID) f.get("studentId");
        if (!face.hasTemplate(studentId)) {
            if (consent == null || !consent.accepted() || !FaceService.CONSENT_VERSION.equals(consent.consentVersion())) {
                return Json.obj("enrolRequired", true, "consentVersion", FaceService.CONSENT_VERSION);
            }
            db.update("UPDATE auth_flows SET consent_at = now() WHERE id = :id", "id", f.get("id"));
            audit.logAs((UUID) f.get("userId"), "STUDENT", "BIOMETRIC_CONSENT_GIVEN", "student", studentId, Json.obj("version", consent.consentVersion()));
            Map<String, Object> ch = face.createChallenge(studentId, "ENROL");
            ch.put("enrol", true);
            return ch;
        }
        return face.createChallenge(studentId, "LOGIN");
    }

    @PostMapping(value = "/login/face", consumes = "multipart/form-data")
    public Map<String, Object> loginFace(@RequestParam UUID challengeId, @RequestParam("frames") List<MultipartFile> frames,
                                         HttpServletRequest req, HttpServletResponse res) throws IOException {
        var f = flows.require(req, "LOGIN", "FACE");
        boolean enrol = !face.hasTemplate((UUID) f.get("studentId"));
        if (enrol && f.get("consentAt") == null) throw ApiException.badRequest("CONSENT_REQUIRED", "Biometric consent is required.");
        var out = face.verify((UUID) f.get("studentId"), challengeId, enrol ? "ENROL" : "LOGIN", bytes(frames));
        if (!out.passed()) {
            if (!enrol) recordLoginFailure((UUID) f.get("userId"));
            audit.logAs((UUID) f.get("userId"), "STUDENT", "LOGIN_FACE_FAILED", "student", f.get("studentId"), Json.obj("reason", out.failure()));
            return Json.obj("passed", false, "reason", out.failure(), "message", out.message());
        }
        return finishLogin(f, (UUID) f.get("userId"), req, res);
    }

    private Map<String, Object> finishLogin(Map<String, Object> f, UUID userId, HttpServletRequest req, HttpServletResponse res) {
        byte[] cookie = devices.cookieHash(req, res);
        UUID knownDevice = devices.find(userId, cookie);
        UUID deviceId = devices.upsert(userId, cookie, req);
        if (knownDevice == null) {
            fraud.raise("NEW_DEVICE_SIGN_IN", "LOW", (UUID) f.get("studentId"), null, deviceId,
                    Json.obj("note", "Passkey used from a browser not previously seen for this student (synced passkey or new browser)."));
        }
        sharedDeviceCheck((UUID) f.get("studentId"), deviceId, req);
        flows.complete((UUID) f.get("id"), res);
        sessions.create(userId, deviceId, req, res);
        audit.logAs(userId, "STUDENT", "LOGIN", "student", f.get("studentId"), Json.obj());
        return Json.obj("passed", true, "signedIn", true);
    }

    // ------------------------------------------------------------------ Device recovery

    @PostMapping("/recover/start")
    public Map<String, Object> recoverStart(@RequestBody RecoverStartBody body, HttpServletRequest req, HttpServletResponse res) {
        limitIndexLookups(req);
        Map<String, Object> s = directory.byIndex(body.indexNumber());
        if (s == null || s.get("userId") == null) throw ApiException.notFound("ACCOUNT_NOT_FOUND", "No registered account was found for this index number.");
        byte[] cookie = devices.cookieHash(req, res);
        UUID studentId = (UUID) s.get("id");
        Map<String, Object> existing = db.one("""
                SELECT id, status FROM recovery_requests WHERE student_id = :s AND kind = 'DEVICE_CHANGE'
                AND device_cookie_hash = :h AND status IN ('PENDING','APPROVED') AND expires_at > now()
                ORDER BY created_at DESC LIMIT 1""", "s", studentId, "h", cookie);
        UUID flowId = flows.start("RECOVERY", studentId, "FACE", res);
        if (existing != null) {
            db.update("UPDATE auth_flows SET recovery_request_id = :r WHERE id = :id", "r", existing.get("id"), "id", flowId);
            return Json.obj("requestStatus", existing.get("status"), "next", "FACE", "faceEnrolled", face.hasTemplate(studentId));
        }
        return Json.obj("requestStatus", "NEW", "next", "FACE", "faceEnrolled", face.hasTemplate(studentId),
                "reasonRequired", body.reason() == null || body.reason().isBlank());
    }

    @PostMapping("/recover/face/challenge")
    public Map<String, Object> recoverFaceChallenge(HttpServletRequest req) {
        var f = flows.require(req, "RECOVERY", "FACE");
        return face.createChallenge((UUID) f.get("studentId"), "RECOVERY");
    }

    @PostMapping(value = "/recover/face", consumes = "multipart/form-data")
    public Map<String, Object> recoverFace(@RequestParam UUID challengeId, @RequestParam("frames") List<MultipartFile> frames,
                                           HttpServletRequest req) throws IOException {
        var f = flows.require(req, "RECOVERY", "FACE");
        var out = face.verify((UUID) f.get("studentId"), challengeId, "RECOVERY", bytes(frames));
        if (!out.passed()) return Json.obj("passed", false, "reason", out.failure(), "message", out.message());
        db.update("UPDATE auth_flows SET face_passed_at = now(), stage = 'SUBMIT' WHERE id = :id", "id", f.get("id"));
        String status = f.get("recoveryRequestId") == null ? "NEW"
                : db.scalar("SELECT status FROM recovery_requests WHERE id = :id", String.class, "id", f.get("recoveryRequestId"));
        return Json.obj("passed", true, "requestStatus", status);
    }

    /** Submit a new request (face verified) or, if face verification is impossible, an unverified one for in-person review. */
    @PostMapping("/recover/submit")
    public Map<String, Object> recoverSubmit(@RequestBody ReasonBody body, HttpServletRequest req, HttpServletResponse res) {
        var f = flows.require(req, "RECOVERY", "FACE", "SUBMIT");
        if (f.get("recoveryRequestId") != null) return Json.obj("submitted", true, "requestId", f.get("recoveryRequestId"));
        String reason = clip(body.reason(), 500);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Please describe why you need to recover your account.");
        boolean faceOk = f.get("facePassedAt") != null;
        UUID id = db.insertReturningId("""
                INSERT INTO recovery_requests(student_id, kind, reason, device_cookie_hash, face_verified, expires_at)
                VALUES (:s, 'DEVICE_CHANGE', :r, :h, :fv, now() + interval '7 days') RETURNING id""",
                "s", f.get("studentId"), "r", reason, "h", devices.cookieHash(req, res), "fv", faceOk);
        db.update("UPDATE auth_flows SET recovery_request_id = :r WHERE id = :id", "r", id, "id", f.get("id"));
        audit.logAs(null, "STUDENT", "RECOVERY_REQUESTED", "student", f.get("studentId"), Json.obj("faceVerified", faceOk));
        notifications.notifyAdmins("Device recovery request", "A student requested to move their account to a new device.", "/admin.html#/recovery");
        return Json.obj("submitted", true, "requestId", id, "faceVerified", faceOk);
    }

    @PostMapping("/recover/passkey/options")
    public Map<String, Object> recoverPasskeyOptions(HttpServletRequest req, HttpServletResponse res) {
        var f = flows.require(req, "RECOVERY", "SUBMIT");
        Map<String, Object> rr = approvedRequestForThisDevice(f, req, res);
        UUID userId = (UUID) f.get("userId");
        byte[] challenge = Crypto.randomBytes(32);
        db.update("UPDATE auth_flows SET webauthn_challenge = :c, stage = 'PASSKEY' WHERE id = :id", "c", challenge, "id", f.get("id"));
        Map<String, Object> s = directory.byId((UUID) f.get("studentId"));
        Map<String, Object> opts = webauthn.creationOptions(userId, challenge, (String) s.get("indexNumber"), StudentDirectory.fullName(s));
        opts.put("excludeCredentials", List.of()); // old credentials will be revoked
        return Json.obj("publicKey", opts, "requestId", rr.get("id"));
    }

    @PostMapping("/recover/passkey/verify")
    @Transactional
    public Map<String, Object> recoverPasskeyVerify(@RequestBody JsonNode credential, HttpServletRequest req, HttpServletResponse res) {
        var f = flows.require(req, "RECOVERY", "PASSKEY");
        Map<String, Object> rr = approvedRequestForThisDevice(f, req, res);
        UUID userId = (UUID) f.get("userId");
        byte[] challenge = (byte[]) f.get("webauthnChallenge");
        db.update("UPDATE auth_flows SET webauthn_challenge = NULL WHERE id = :id", "id", f.get("id"));
        if (challenge == null) throw ApiException.badRequest("FLOW_EXPIRED", "Please start again.");
        // Revoke all previous passkeys and devices, then bind the new device.
        db.update("UPDATE webauthn_credentials SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL", "u", userId);
        db.update("UPDATE devices SET status = 'REVOKED', revoked_at = now() WHERE user_id = :u AND status = 'TRUSTED'", "u", userId);
        sessions.revokeAllForUser(userId);
        UUID deviceId = devices.upsert(userId, devices.cookieHash(req, res), req);
        db.update("UPDATE devices SET status = 'TRUSTED', revoked_at = NULL WHERE id = :d", "d", deviceId);
        webauthn.register(userId, deviceId, challenge, credential.toString(), "Recovered device");
        db.update("UPDATE recovery_requests SET status = 'COMPLETED' WHERE id = :id", "id", rr.get("id"));
        flows.complete((UUID) f.get("id"), res);
        audit.logAs(userId, "STUDENT", "RECOVERY_COMPLETED", "student", f.get("studentId"), Json.obj("requestId", rr.get("id")));
        notifications.notify(userId, "New device registered", "Your account was moved to a new device. Old devices were signed out.", "/student.html#/profile");
        sessions.create(userId, deviceId, req, res);
        return Json.obj("recovered", true);
    }

    private Map<String, Object> approvedRequestForThisDevice(Map<String, Object> f, HttpServletRequest req, HttpServletResponse res) {
        if (f.get("facePassedAt") == null) throw ApiException.forbidden("Face verification is required to complete recovery.");
        Map<String, Object> rr = db.one("""
                SELECT id FROM recovery_requests WHERE student_id = :s AND kind = 'DEVICE_CHANGE' AND status = 'APPROVED'
                AND device_cookie_hash = :h AND expires_at > now() ORDER BY reviewed_at DESC LIMIT 1""",
                "s", f.get("studentId"), "h", devices.cookieHash(req, res));
        if (rr == null) throw ApiException.forbidden("Your recovery request has not been approved for this device yet.");
        return rr;
    }

    // ------------------------------------------------------------------ helpers

    private void limitIndexLookups(HttpServletRequest req) {
        limiter.check("index-lookup-min", req.getRemoteAddr(), 12, 60);
        limiter.check("index-lookup-hour", req.getRemoteAddr(), 120, 3600);
    }

    private void checkLock(UUID userId) {
        Boolean locked = db.scalar("SELECT locked_until > now() FROM app_users WHERE id = :u", Boolean.class, "u", userId);
        if (Boolean.TRUE.equals(locked)) throw ApiException.tooMany("Too many failed sign-in attempts. Try again in 15 minutes.");
    }

    private void recordLoginFailure(UUID userId) {
        db.update("""
                UPDATE app_users SET failed_logins = failed_logins + 1,
                  locked_until = CASE WHEN failed_logins + 1 >= 5 THEN now() + interval '15 minutes' ELSE locked_until END
                WHERE id = :u""", "u", userId);
    }

    /** Flag when one browser/device is used by more than one student account. */
    private void sharedDeviceCheck(UUID studentId, UUID deviceId, HttpServletRequest req) {
        byte[] h = DeviceService.existingCookieHash(req);
        if (h == null) return;
        List<Map<String, Object>> others = db.list("""
                SELECT DISTINCT st.id, st.index_number FROM devices d JOIN students st ON st.user_id = d.user_id
                WHERE d.device_cookie_hash = :h AND st.id <> :s""", "h", h, "s", studentId);
        if (!others.isEmpty()) {
            fraud.raise("SHARED_DEVICE", "MEDIUM", studentId, null, deviceId,
                    Json.obj("otherStudents", others.stream().map(o -> o.get("indexNumber")).toList()));
        }
    }

    static List<byte[]> bytes(List<MultipartFile> frames) throws IOException {
        if (frames == null || frames.isEmpty() || frames.size() > 12) throw ApiException.badRequest("BAD_FRAMES", "Invalid camera capture.");
        List<byte[]> out = new ArrayList<>();
        for (MultipartFile m : frames) out.add(m.getBytes());
        return out;
    }

    static String clip(String s, int n) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > n ? s.substring(0, n) : s;
    }
}
