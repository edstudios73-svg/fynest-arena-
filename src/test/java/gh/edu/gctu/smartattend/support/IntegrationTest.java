package gh.edu.gctu.smartattend.support;

import com.fasterxml.jackson.databind.JsonNode;
import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.common.SettingsService;
import gh.edu.gctu.smartattend.face.FaceEngine;
import gh.edu.gctu.smartattend.face.FaceService;
import gh.edu.gctu.smartattend.security.AccountController;
import gh.edu.gctu.smartattend.security.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.TestConfig.class)
public abstract class IntegrationTest {
    public static final String ORIGIN = "http://localhost";
    public static final String LECTURER_PW = "Lecturer-Pass-2026!";
    public static final String ADMIN_PW = "Admin-Pass-2026!x";

    @TestConfiguration
    static class TestConfig {
        @Bean
        FaceEngine syntheticFaceEngine() { return new SyntheticFaceEngine(); }

        /** Fresh schema once per test JVM. */
        @Bean
        FlywayMigrationStrategy cleanMigrate() {
            return fw -> { fw.clean(); fw.migrate(); };
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected Db db;
    @Autowired protected RateLimiter limiter;
    @Autowired protected SettingsService settings;

    @BeforeEach
    void resetData() {
        db.update("""
                TRUNCATE attendance_verification_events, correction_requests, attendance_records, attendance_attempts,
                         attendance_challenges, attendance_sessions, fraud_alerts, notifications, recovery_requests, auth_flows,
                         auth_sessions, webauthn_credentials, biometric_enrollments, face_challenges, devices, course_enrollments,
                         audit_logs CASCADE""");
        db.update("UPDATE system_settings SET updated_by = NULL");
        db.update("UPDATE class_groups SET register_verified_by = NULL");
        db.update("DELETE FROM students");
        db.update("DELETE FROM import_batches");
        db.update("DELETE FROM administrators");
        db.update("DELETE FROM app_users WHERE role IN ('STUDENT','ADMIN','SUPER_ADMIN')");
        db.update("UPDATE app_users SET password_hash = NULL, email = NULL, failed_logins = 0, locked_until = NULL, must_change_password = false, status = 'ACTIVE' WHERE role = 'LECTURER'");
        db.update("UPDATE class_groups SET register_status = 'UNVERIFIED', expected_size = 129, register_verified_at = NULL");
        db.update("UPDATE semesters SET start_date = NULL");
        db.update("UPDATE venues SET latitude = NULL, longitude = NULL, network_cidrs = '{}'");
        db.update("UPDATE system_settings SET updated_by = NULL");
        resetSetting("security.registrationRequiresApproval", "false");
        resetSetting("security.faceOnLogin", "true");
        resetSetting("security.faceOnAttendance", "true");
        resetSetting("security.passkeyOnAttendance", "true");
        resetSetting("proximity.defaultPolicy", "EVIDENCE");
        resetSetting("security.passkeyOnLogin", "true");
        resetSetting("security.faceOnCheckout", "true");
        resetSetting("attendance.checkoutMinutes", "0");
        resetSetting("attendance.classMinutes", "20");
        resetSetting("attendance.geofenceMeters", "50");
        resetSetting("attendance.lateAfterMinutes", "15");
        settings.clearCache();
        limiter.reset();
    }

    protected void resetSetting(String k, String v) {
        db.update("UPDATE system_settings SET value = :v WHERE key = :k", "v", v, "k", k);
        settings.clearCache();
    }

    protected Client client() { return new Client(mvc); }

    protected UUID groupId() { return db.scalar("SELECT id FROM class_groups LIMIT 1", UUID.class); }

    protected UUID courseId(String code) { return db.scalar("SELECT id FROM courses WHERE code = :c", UUID.class, "c", code); }

    /** Insert register entries directly (fast path for tests that are not about importing). */
    protected List<String> seedStudents(int n) {
        List<String> ids = new ArrayList<>();
        UUID g = groupId();
        for (int i = 1; i <= n; i++) {
            String idx = String.format("TEST%05d", i);
            db.update("INSERT INTO students(class_group_id, index_number, first_name, last_name, other_names) VALUES (:g, :i, :f, :l, :o)",
                    "g", g, "i", idx, "f", "First" + i, "l", "Last" + i, "o", i % 3 == 0 ? "Middle" + i : null);
            ids.add(idx);
        }
        db.update("""
                INSERT INTO course_enrollments(course_id, student_id, semester_id)
                SELECT DISTINCT t.course_id, s.id, t.semester_id FROM students s JOIN class_timetables t ON t.class_group_id = s.class_group_id
                ON CONFLICT DO NOTHING""");
        return ids;
    }

    protected void verifyRegister() {
        db.update("UPDATE class_groups SET register_status = 'VERIFIED', register_verified_at = now()");
    }

    protected Client lecturer(String courseCode) {
        UUID uid = db.scalar("""
                SELECT l.user_id FROM lecturers l JOIN course_assignments a ON a.lecturer_id = l.id JOIN courses c ON c.id = a.course_id
                WHERE c.code = :c""", UUID.class, "c", courseCode);
        String email = courseCode.toLowerCase().replace(" ", "") + "@lecturer.test";
        db.update("UPDATE app_users SET email = :e, password_hash = :h WHERE id = :u", "e", email, "h", AccountController.BCRYPT.encode(LECTURER_PW), "u", uid);
        Client c = client();
        Client.Resp r = c.post("/api/auth/staff/login", Map.of("email", email, "password", LECTURER_PW, "portal", "lecturer"));
        assertEquals(200, r.status(), r.text());
        return c.refresh();
    }

    protected Client admin(String role) {
        String email = role.toLowerCase() + UUID.randomUUID().toString().substring(0, 6) + "@admin.test";
        UUID uid = db.insertReturningId("INSERT INTO app_users(role, email, password_hash, status) VALUES (:r, :e, :h, 'ACTIVE') RETURNING id",
                "r", role, "e", email, "h", AccountController.BCRYPT.encode(ADMIN_PW));
        db.update("INSERT INTO administrators(user_id, full_name) VALUES (:u, 'Test Admin')", "u", uid);
        Client c = client();
        Client.Resp r = c.post("/api/auth/staff/login", Map.of("email", email, "password", ADMIN_PW, "portal", "admin"));
        assertEquals(200, r.status(), r.text());
        return c.refresh();
    }

    public record Student(Client client, SoftwareAuthenticator authenticator, String index, String person) {}

    /** Complete the full real registration flow through the HTTP API. */
    protected Student register(String index, String person) {
        Client c = client();
        expectOk(c.post("/api/auth/student/register/start", Map.of("indexNumber", index)));
        expectOk(c.post("/api/auth/student/register/confirm", Map.of("confirmed", true)));
        expectOk(c.post("/api/auth/student/register/consent", Map.of("accepted", true, "consentVersion", FaceService.CONSENT_VERSION)));
        JsonNode ch = expectOk(c.post("/api/auth/student/register/face/challenge", null)).json();
        JsonNode face = expectOk(faceUpload(c, "/api/auth/student/register/face", ch, person, null)).json();
        assertEquals(true, face.path("passed").asBoolean(), face.toString());
        SoftwareAuthenticator a = new SoftwareAuthenticator(ORIGIN, "localhost");
        if (face.path("registered").asBoolean()) {
            c.refresh();
            return new Student(c, a, index, person);
        }
        JsonNode opts = expectOk(c.post("/api/auth/student/register/passkey/options", null)).json();
        try {
            expectOk(c.post("/api/auth/student/register/passkey/verify", a.create(opts)));
        } catch (Exception e) { throw new RuntimeException(e); }
        c.refresh();
        return new Student(c, a, index, person);
    }

    /** Upload frames following the challenge steps. `override` can replace a step's yaw to simulate spoofs. */
    protected Client.Resp faceUpload(Client c, String path, JsonNode challenge, String person, double[] yawOverride) {
        List<MockMultipartFile> files = new ArrayList<>();
        int i = 0;
        for (JsonNode step : challenge.path("steps")) {
            double yaw = yawOverride != null ? yawOverride[i] : SyntheticFaceEngine.yawFor(step.asText());
            files.add(new MockMultipartFile("frames", "f" + i + ".jpg", "image/jpeg", SyntheticFaceEngine.frame(person, yaw)));
            i++;
        }
        return c.multipart(path, Map.of("challengeId", challenge.path("challengeId").asText()), files);
    }

    protected Client.Resp expectOk(Client.Resp r) {
        assertEquals(200, r.status(), () -> "Expected 200 but got " + r.status() + ": " + r.text());
        return r;
    }

    /** Lecturer starts a session and reads the live OTP/QR. */
    protected JsonNode startSession(Client lecturer, String courseCode, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra == null ? Map.of() : extra);
        body.put("courseId", courseId(courseCode).toString());
        JsonNode s = expectOk(lecturer.post("/api/lecturer/sessions", body)).json();
        return s;
    }

    protected JsonNode challenge(Client lecturer, String sessionId) {
        return expectOk(lecturer.get("/api/lecturer/sessions/" + sessionId + "/challenge")).json();
    }

    /** Full student attendance flow; returns final submit response. */
    protected Client.Resp attend(Student st, String sessionId, String otp, String qrToken) {
        Map<String, Object> begin = new HashMap<>();
        if (sessionId != null) begin.put("sessionId", sessionId);
        if (otp != null) begin.put("otp", otp);
        if (qrToken != null) begin.put("qrToken", qrToken);
        Client.Resp b = st.client().post("/api/student/attendance/begin", begin);
        if (b.status() != 200) return b;
        String attemptId = b.json().path("attemptId").asText();
        if (b.json().path("requiresFace").asBoolean()) {
            Client.Resp f = faceUpload(st.client(), "/api/student/attendance/" + attemptId + "/face", b.json().path("faceChallenge"), st.person(), null);
            if (f.status() != 200 || !f.json().path("passed").asBoolean()) return f;
        }
        if (b.json().path("requiresPasskey").asBoolean()) {
            try {
                Client.Resp p = st.client().post("/api/student/attendance/" + attemptId + "/passkey", st.authenticator().get(b.json().path("passkey")));
                if (p.status() != 200) return p;
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        return st.client().post("/api/student/attendance/" + attemptId + "/submit", Map.of());
    }

    protected static String qrToken(JsonNode challenge) {
        String url = challenge.path("qrUrl").asText();
        return url.substring(url.indexOf("t=") + 2);
    }
}
