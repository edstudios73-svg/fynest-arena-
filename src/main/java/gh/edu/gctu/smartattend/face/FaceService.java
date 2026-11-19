package gh.edu.gctu.smartattend.face;

import gh.edu.gctu.smartattend.common.*;
import gh.edu.gctu.smartattend.security.Crypto;
import gh.edu.gctu.smartattend.security.FraudService;
import gh.edu.gctu.smartattend.security.KeyProvider;
import gh.edu.gctu.smartattend.security.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.util.*;

/**
 * One-to-one facial verification with active liveness. Raw camera frames are analysed in memory
 * and discarded; only an AES-256-GCM encrypted embedding is persisted.
 */
@Service
public class FaceService {
    public static final String CONSENT_VERSION = "2026-11-v1";
    private static final int MAX_FRAME_BYTES = 700_000;

    private final Db db;
    private final FaceEngine engine;
    private final KeyProvider keys;
    private final SettingsService settings;
    private final FraudService fraud;
    private final RateLimiter limiter;
    private final AuditService audit;

    public FaceService(Db db, FaceEngine engine, KeyProvider keys, SettingsService settings, FraudService fraud,
                       RateLimiter limiter, AuditService audit) {
        this.db = db;
        this.engine = engine;
        this.keys = keys;
        this.settings = settings;
        this.fraud = fraud;
        this.limiter = limiter;
        this.audit = audit;
    }

    public FaceEngine engine() { return engine; }

    public void requireAvailable() {
        if (!engine.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FACE_UNAVAILABLE",
                    "Facial verification is not configured on this server. Please contact the administrator.");
        }
    }

    /** Create a single-use random pose challenge. */
    public Map<String, Object> createChallenge(UUID studentId, String purpose) {
        requireAvailable();
        limiter.check("face-challenge", studentId.toString(), 20, 3600);
        List<String> turns = new ArrayList<>(List.of("LEFT", "RIGHT"));
        Collections.shuffle(turns, new java.security.SecureRandom());
        if (Crypto.randomInt(2) == 1) turns.add(Crypto.randomInt(2) == 0 ? "LEFT" : "RIGHT");
        List<String> steps = new ArrayList<>();
        steps.add("CENTER");
        steps.addAll(turns);
        steps.add("CENTER");
        UUID id = db.insertReturningId("""
                INSERT INTO face_challenges(student_id, purpose, steps, expires_at)
                VALUES (:s, :p, string_to_array(:steps, ','), now() + interval '150 seconds') RETURNING id""",
                "s", studentId, "p", purpose, "steps", String.join(",", steps));
        return Json.obj("challengeId", id, "steps", steps, "expiresInSeconds", 150, "framesPerStep", 1);
    }

    public record Outcome(boolean passed, double score, String failure, String message, UUID challengeId) {}

    /**
     * Verify the frames for a challenge. For ENROL the reference template is created; for other
     * purposes the live face is compared against the student's enrolled template.
     */
    public Outcome verify(UUID studentId, UUID challengeId, String purpose, List<byte[]> frames) {
        requireAvailable();
        int maxFail = settings.getInt("security.maxFaceFailuresPerHour");
        Integer recentFails = db.scalar("""
                SELECT count(*)::int FROM face_challenges WHERE student_id = :s AND passed = false
                AND used_at > now() - interval '1 hour'""", Integer.class, "s", studentId);
        if (recentFails != null && recentFails >= maxFail) {
            throw ApiException.tooMany("Too many failed face checks. Please wait an hour or ask an administrator for supervised verification.");
        }

        // Single use: atomically claim the challenge.
        Map<String, Object> ch = db.one("""
                UPDATE face_challenges SET used_at = now()
                WHERE id = :id AND student_id = :s AND purpose = :p AND used_at IS NULL AND expires_at > now()
                RETURNING array_to_string(steps, ',') AS steps""", "id", challengeId, "s", studentId, "p", purpose);
        if (ch == null) throw ApiException.badRequest("FACE_CHALLENGE_INVALID", "This face check has expired. Please start again.");
        List<String> steps = List.of(((String) ch.get("steps")).split(","));
        if (frames.size() != steps.size()) return fail(challengeId, studentId, "FRAME_COUNT", 0);

        Set<String> seen = new HashSet<>();
        List<FaceEngine.FrameAnalysis> analyses = new ArrayList<>();
        for (byte[] f : frames) {
            if (f.length < 1000 || f.length > MAX_FRAME_BYTES || !isImage(f)) return fail(challengeId, studentId, "BAD_FRAME", 0);
            if (!seen.add(Crypto.b64url(Crypto.sha256(f)))) return fail(challengeId, studentId, "REPLAYED_FRAME", 0);
            FaceEngine.FrameAnalysis a = engine.analyse(f);
            if (!a.ok()) return fail(challengeId, studentId, a.problem(), 0);
            analyses.add(a);
        }

        LivenessEvaluator.Result live = LivenessEvaluator.evaluate(steps, analyses.stream().map(FaceEngine.FrameAnalysis::yaw).toList());
        if (!live.passed()) return fail(challengeId, studentId, "LIVENESS_" + live.failure(), 0);

        // Same person throughout the sequence (prevents swapping faces between frames).
        float[] firstCenter = analyses.get(0).embedding();
        for (var a : analyses) {
            if (engine.similarity(firstCenter, a.embedding()) < 0.30) return fail(challengeId, studentId, "LIVENESS_IDENTITY_CHANGED", 0);
        }
        List<float[]> centers = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).equals("CENTER")) centers.add(analyses.get(i).embedding());

        double threshold = settings.getDouble("face.matchThreshold");
        // Face lock: once a face is enrolled for an index number it is permanent. A repeated enrolment
        // (e.g. an interrupted registration started again) must match the locked face; it never replaces it.
        boolean locked = "ENROL".equals(purpose) && hasTemplate(studentId);
        if ("ENROL".equals(purpose) && !locked) {
            double agree = engine.similarity(centers.get(0), centers.get(centers.size() - 1));
            if (agree < 0.55) return fail(challengeId, studentId, "INCONSISTENT_CAPTURE", agree);
            float[] template = mean(centers);
            storeTemplate(studentId, template, analyses);
            if (settings.getBool("face.duplicateCheckOnEnrol")) duplicateCheck(studentId, template, threshold);
            pass(challengeId, agree);
            return new Outcome(true, agree, null, "Face enrolled", challengeId);
        }

        float[] reference = loadTemplate(studentId);
        if (reference == null) throw ApiException.conflict("NOT_ENROLLED", "No face enrolment found for this account.");
        double score = Double.MAX_VALUE;
        for (float[] c : centers) score = Math.min(score, engine.similarity(reference, c));
        if (score < threshold) {
            if (locked) {
                fraud.raise("ENROLMENT_FACE_MISMATCH", "HIGH", studentId, null, null,
                        Json.obj("note", "A different face tried to register an index number whose face is already locked."));
            }
            return fail(challengeId, studentId, locked ? "FACE_LOCKED_MISMATCH" : "FACE_MISMATCH", score);
        }
        pass(challengeId, score);
        return new Outcome(true, score, null, "Face verified", challengeId);
    }

    private void duplicateCheck(UUID studentId, float[] template, double threshold) {
        for (var r : db.list("""
                SELECT student_id, template_ciphertext, template_iv FROM biometric_enrollments
                WHERE status = 'ACTIVE' AND student_id <> :s""", "s", studentId)) {
            float[] other = decrypt((UUID) r.get("studentId"), (byte[]) r.get("templateIv"), (byte[]) r.get("templateCiphertext"));
            double sim = engine.similarity(template, other);
            if (sim >= threshold + 0.08) {
                fraud.raise("DUPLICATE_FACE_ENROLMENT", "HIGH", studentId, null, null,
                        Json.obj("matchesStudentId", r.get("studentId"), "similarity", Math.round(sim * 1000) / 1000.0));
            }
        }
    }

    private void storeTemplate(UUID studentId, float[] template, List<FaceEngine.FrameAnalysis> analyses) {
        Crypto.Sealed sealed = Crypto.aesGcmEncrypt(keys.biometricKey(), toBytes(template), aad(studentId));
        double quality = analyses.stream().mapToDouble(FaceEngine.FrameAnalysis::detectionScore).average().orElse(0);
        int retentionDays = settings.getInt("biometric.retentionDays");
        db.update("UPDATE biometric_enrollments SET status = 'REVOKED', revoked_at = now() WHERE student_id = :s AND status = 'ACTIVE'", "s", studentId);
        db.update("""
                INSERT INTO biometric_enrollments(student_id, provider, model_version, template_ciphertext, template_iv, key_id,
                    quality_score, consent_version, consent_given_at, retention_until)
                VALUES (:s, :p, :mv, :ct, :iv, :kid, :q, :cv, now(), :ret)""",
                "s", studentId, "p", engine.providerId(), "mv", engine.modelVersion(), "ct", sealed.ciphertext(),
                "iv", sealed.iv(), "kid", keys.biometricKeyId(), "q", quality, "cv", CONSENT_VERSION,
                "ret", LocalDate.now().plusDays(retentionDays));
        audit.log("BIOMETRIC_ENROLLED", "student", studentId, Json.obj("provider", engine.providerId()));
    }

    public boolean hasTemplate(UUID studentId) {
        Integer c = db.scalar("SELECT count(*)::int FROM biometric_enrollments WHERE student_id = :s AND status = 'ACTIVE'", Integer.class, "s", studentId);
        return c != null && c > 0;
    }

    private float[] loadTemplate(UUID studentId) {
        Map<String, Object> r = db.one("SELECT template_ciphertext, template_iv FROM biometric_enrollments WHERE student_id = :s AND status = 'ACTIVE'", "s", studentId);
        if (r == null) return null;
        return decrypt(studentId, (byte[]) r.get("templateIv"), (byte[]) r.get("templateCiphertext"));
    }

    private float[] decrypt(UUID studentId, byte[] iv, byte[] ct) {
        return fromBytes(Crypto.aesGcmDecrypt(keys.biometricKey(), iv, ct, aad(studentId)));
    }

    private Outcome fail(UUID challengeId, UUID studentId, String failure, double score) {
        db.update("UPDATE face_challenges SET passed = false, failure = :f, score = :sc WHERE id = :id",
                "f", failure, "sc", score, "id", challengeId);
        Integer fails = db.scalar("""
                SELECT count(*)::int FROM face_challenges WHERE student_id = :s AND passed = false AND used_at > now() - interval '1 hour'""",
                Integer.class, "s", studentId);
        if (fails != null && fails >= 3) {
            fraud.raise("REPEATED_FACE_FAILURES", fails >= 5 ? "HIGH" : "MEDIUM", studentId, null, null, Json.obj("failuresLastHour", fails, "last", failure));
        }
        return new Outcome(false, score, failure, message(failure), challengeId);
    }

    private void pass(UUID challengeId, double score) {
        db.update("UPDATE face_challenges SET passed = true, score = :sc WHERE id = :id", "sc", score, "id", challengeId);
    }

    public static String message(String failure) {
        if (failure == null) return null;
        if (failure.startsWith("LIVENESS_")) {
            return switch (failure) {
                case "LIVENESS_NOT_FACING_CAMERA" -> "Liveness check failed: please look straight at the camera when asked.";
                case "LIVENESS_HEAD_TURN_NOT_DETECTED" -> "Liveness check failed: we could not detect the head turns. Turn your head clearly when prompted.";
                case "LIVENESS_IDENTITY_CHANGED" -> "Liveness check failed: the face changed during the check.";
                default -> "Liveness check failed. Please try again.";
            };
        }
        return switch (failure) {
            case "NO_FACE" -> "No face detected. Make sure your face is fully visible.";
            case "MULTIPLE_FACES" -> "More than one face detected. Only you should be in the frame.";
            case "FACE_TOO_SMALL" -> "Move closer to the camera.";
            case "TOO_DARK" -> "It's too dark. Move to a brighter place.";
            case "TOO_BRIGHT" -> "Too much light. Avoid strong light behind or in front of you.";
            case "TOO_BLURRY" -> "The image is blurry. Hold your phone steady.";
            case "FACE_MISMATCH" -> "Facial verification failed: the face does not match the enrolled student.";
            case "FACE_LOCKED_MISMATCH" -> "A face is already locked to this index number and yours does not match it. Only that student can register. If this is your index number, see the administrator.";
            case "INCONSISTENT_CAPTURE" -> "Capture was inconsistent. Please try again in good lighting.";
            case "REPLAYED_FRAME" -> "Duplicate frames detected. Please retry the live check.";
            default -> "Facial verification failed. Please try again.";
        };
    }

    public void deleteBiometrics(UUID studentId, String reason) {
        db.update("""
                UPDATE biometric_enrollments SET status = 'DELETED', revoked_at = now(),
                    template_ciphertext = '\\x00'::bytea, template_iv = '\\x00'::bytea
                WHERE student_id = :s AND status IN ('ACTIVE','REVOKED')""", "s", studentId);
        audit.log("BIOMETRIC_DELETED", "student", studentId, Json.obj("reason", reason));
    }

    /** Retention policy: erase templates whose retention date has passed (runs daily and at startup). */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 60_000, fixedDelay = 86_400_000)
    public void enforceRetention() {
        for (var r : db.list("SELECT student_id FROM biometric_enrollments WHERE status IN ('ACTIVE','REVOKED') AND retention_until < CURRENT_DATE")) {
            db.update("""
                    UPDATE biometric_enrollments SET status = 'DELETED', revoked_at = now(),
                        template_ciphertext = '\\x00'::bytea, template_iv = '\\x00'::bytea
                    WHERE student_id = :s AND status IN ('ACTIVE','REVOKED') AND retention_until < CURRENT_DATE""", "s", r.get("studentId"));
            audit.logAs(null, "SYSTEM", "BIOMETRIC_RETENTION_EXPIRED", "student", r.get("studentId"), Json.obj());
        }
        // Revoked (superseded) templates are not needed for verification: erase their ciphertext.
        db.update("UPDATE biometric_enrollments SET status = 'DELETED', template_ciphertext = '\\x00'::bytea, template_iv = '\\x00'::bytea WHERE status = 'REVOKED'");
    }

    private static boolean isImage(byte[] b) {
        boolean jpeg = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
        boolean png = (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean webp = b.length > 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        return jpeg || png || webp;
    }

    private static byte[] aad(UUID studentId) {
        return ("smartattend-face:" + studentId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static float[] mean(List<float[]> vs) {
        float[] m = new float[vs.get(0).length];
        for (float[] v : vs) for (int i = 0; i < m.length; i++) m[i] += v[i];
        OpenCvFaceEngine.normalise(m);
        return m;
    }

    static byte[] toBytes(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) bb.putFloat(f);
        return bb.array();
    }

    static float[] fromBytes(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
        return v;
    }
}
