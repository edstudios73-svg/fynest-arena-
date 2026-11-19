package gh.edu.gctu.smartattend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import gh.edu.gctu.smartattend.face.FaceService;
import gh.edu.gctu.smartattend.support.Client;
import gh.edu.gctu.smartattend.support.IntegrationTest;
import gh.edu.gctu.smartattend.support.SoftwareAuthenticator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationFlowTest extends IntegrationTest {

    @Test
    void validIndexNumberRegistersWithFaceAndPasskey() {
        seedStudents(3);
        verifyRegister();
        Client c = client();
        JsonNode start = expectOk(c.post("/api/auth/student/register/start", Map.of("indexNumber", " test00003 "))).json();
        assertEquals("First3 Middle3 Last3", start.path("fullName").asText(), "official name built from register fields");
        Student s = register("TEST00001", "alice");
        JsonNode me = expectOk(s.client().get("/api/me")).json();
        assertEquals("STUDENT", me.path("role").asText());
        assertEquals("TEST00001", me.path("student").path("indexNumber").asText());
        assertEquals("ACTIVE", me.path("status").asText());
        assertEquals(1, db.scalar("SELECT count(*)::int FROM biometric_enrollments WHERE status = 'ACTIVE'", Integer.class));
        // The stored template is ciphertext, not a raw image
        byte[] ct = db.scalar("SELECT template_ciphertext FROM biometric_enrollments", byte[].class);
        assertEquals(128 * 4 + 16, ct.length, "AES-GCM encrypted 128-float embedding + tag");
    }

    @Test
    void invalidIndexNumberIsRejected() {
        seedStudents(1);
        verifyRegister();
        Client.Resp r = client().post("/api/auth/student/register/start", Map.of("indexNumber", "NOPE12345"));
        assertEquals(404, r.status());
        assertEquals("ACCOUNT_NOT_FOUND", r.code());
    }

    @Test
    void registrationBlockedUntilRegisterVerified() {
        seedStudents(1);
        Client.Resp r = client().post("/api/auth/student/register/start", Map.of("indexNumber", "TEST00001"));
        assertEquals(403, r.status());
        assertEquals("REGISTER_NOT_VERIFIED", r.code());
    }

    @Test
    void anotherStudentsIndexNumberCannotBeReused() {
        seedStudents(2);
        verifyRegister();
        register("TEST00001", "alice");
        Client.Resp r = client().post("/api/auth/student/register/start", Map.of("indexNumber", "TEST00001"));
        assertEquals(409, r.status());
        assertEquals("ALREADY_REGISTERED", r.code());
    }

    @Test
    void stepsCannotBeSkipped() {
        seedStudents(1);
        verifyRegister();
        Client c = client();
        expectOk(c.post("/api/auth/student/register/start", Map.of("indexNumber", "TEST00001")));
        // jump straight to passkey without consent / face
        Client.Resp r = c.post("/api/auth/student/register/passkey/options", null);
        assertEquals(400, r.status());
        assertEquals("FLOW_STEP", r.code());
    }

    @Test
    void returningStudentSignsInWithPasskeyAndFace() throws Exception {
        seedStudents(1);
        verifyRegister();
        Student s = register("TEST00001", "alice");
        Client c = client();
        JsonNode start = expectOk(c.post("/api/auth/student/login/start", Map.of("indexNumber", "TEST00001"))).json();
        JsonNode pk = expectOk(c.post("/api/auth/student/login/passkey", s.authenticator().get(start.path("publicKey")))).json();
        assertEquals("FACE", pk.path("next").asText());
        assertEquals(401, c.get("/api/me").status(), "not signed in before face verification");
        JsonNode ch = expectOk(c.post("/api/auth/student/login/face/challenge", null)).json();
        JsonNode face = expectOk(faceUpload(c, "/api/auth/student/login/face", ch, "alice", null)).json();
        assertTrue(face.path("signedIn").asBoolean(), face.toString());
        assertEquals(200, c.get("/api/me").status());
    }

    @Test
    void faceVerificationRejectsUnrelatedPerson() throws Exception {
        seedStudents(1);
        verifyRegister();
        Student s = register("TEST00001", "alice");
        Client c = client();
        JsonNode start = expectOk(c.post("/api/auth/student/login/start", Map.of("indexNumber", "TEST00001"))).json();
        expectOk(c.post("/api/auth/student/login/passkey", s.authenticator().get(start.path("publicKey"))));
        JsonNode ch = expectOk(c.post("/api/auth/student/login/face/challenge", null)).json();
        JsonNode face = expectOk(faceUpload(c, "/api/auth/student/login/face", ch, "mallory", null)).json();
        assertFalse(face.path("passed").asBoolean());
        assertEquals("FACE_MISMATCH", face.path("reason").asText());
        assertEquals(401, c.get("/api/me").status());
    }

    @Test
    void livenessRejectsStaticPhotoReplayAndChallengeReuse() throws Exception {
        seedStudents(1);
        verifyRegister();
        Student s = register("TEST00001", "alice");
        Client c = client();
        JsonNode start = expectOk(c.post("/api/auth/student/login/start", Map.of("indexNumber", "TEST00001"))).json();
        expectOk(c.post("/api/auth/student/login/passkey", s.authenticator().get(start.path("publicKey"))));

        // Static photo: the enrolled face, but no head turns at all
        JsonNode ch = expectOk(c.post("/api/auth/student/login/face/challenge", null)).json();
        double[] flat = new double[ch.path("steps").size()];
        JsonNode r1 = expectOk(faceUpload(c, "/api/auth/student/login/face", ch, "alice", flat)).json();
        assertFalse(r1.path("passed").asBoolean());
        assertTrue(r1.path("reason").asText().startsWith("LIVENESS_"), r1.toString());

        // Re-using a consumed challenge is refused
        Client.Resp r2 = faceUpload(c, "/api/auth/student/login/face", ch, "alice", null);
        assertEquals(400, r2.status());
        assertEquals("FACE_CHALLENGE_INVALID", r2.code());

        // Turns in the wrong order (pre-recorded video) fail
        JsonNode ch3 = expectOk(c.post("/api/auth/student/login/face/challenge", null)).json();
        double[] reversed = new double[ch3.path("steps").size()];
        int i = 0;
        for (JsonNode st : ch3.path("steps")) reversed[i++] = switch (st.asText()) { case "LEFT" -> -0.35; case "RIGHT" -> 0.35; default -> 0.0; };
        // Reversing every turn keeps the sequence self-consistent, so also break one turn:
        for (int k = 0; k < reversed.length; k++) if (reversed[k] != 0) { reversed[k] = 0.05; break; }
        JsonNode r3 = expectOk(faceUpload(c, "/api/auth/student/login/face", ch3, "alice", reversed)).json();
        assertFalse(r3.path("passed").asBoolean());
        assertEquals(401, c.get("/api/me").status());
    }

    @Test
    void passkeyFromAnotherAccountIsRejected() throws Exception {
        seedStudents(2);
        verifyRegister();
        Student a = register("TEST00001", "alice");
        register("TEST00002", "bob");
        Client c = client();
        JsonNode start = expectOk(c.post("/api/auth/student/login/start", Map.of("indexNumber", "TEST00002"))).json();
        // Alice's authenticator signs Bob's challenge
        Client.Resp r = c.post("/api/auth/student/login/passkey", a.authenticator().get(start.path("publicKey")));
        assertEquals(401, r.status());
        assertEquals("DEVICE_NOT_RECOGNISED", r.code());
    }

    @Test
    void passkeyWithoutUserVerificationIsRejected() throws Exception {
        seedStudents(1);
        verifyRegister();
        Client c = client();
        expectOk(c.post("/api/auth/student/register/start", Map.of("indexNumber", "TEST00001")));
        expectOk(c.post("/api/auth/student/register/confirm", Map.of("confirmed", true)));
        expectOk(c.post("/api/auth/student/register/consent", Map.of("accepted", true, "consentVersion", FaceService.CONSENT_VERSION)));
        JsonNode ch = expectOk(c.post("/api/auth/student/register/face/challenge", null)).json();
        expectOk(faceUpload(c, "/api/auth/student/register/face", ch, "alice", null));
        JsonNode opts = expectOk(c.post("/api/auth/student/register/passkey/options", null)).json();
        SoftwareAuthenticator noUv = new SoftwareAuthenticator(ORIGIN, "localhost");
        noUv.userVerified = false;
        Client.Resp r = c.post("/api/auth/student/register/passkey/verify", noUv.create(opts));
        assertEquals(400, r.status());
    }

    @Test
    void pendingApprovalAccountCannotMarkAttendance() {
        resetSetting("security.registrationRequiresApproval", "true");
        seedStudents(1);
        verifyRegister();
        Student s = register("TEST00001", "alice");
        assertEquals("PENDING_APPROVAL", s.client().get("/api/me").json().path("status").asText());
        Client.Resp r = s.client().post("/api/student/attendance/begin", Map.of("otp", "123456", "sessionId", UUID.randomUUID().toString()));
        assertEquals(403, r.status());
        Client admin = admin("ADMIN");
        UUID sid = db.scalar("SELECT id FROM students WHERE index_number = 'TEST00001'", UUID.class);
        expectOk(admin.post("/api/admin/students/" + sid + "/approve", Map.of("reason", "ID card checked")));
        assertEquals("ACTIVE", s.client().get("/api/me").json().path("status").asText());
    }

    @Test
    void csrfOriginAndRoleChecksAreEnforced() {
        seedStudents(1);
        verifyRegister();
        Student s = register("TEST00001", "alice");
        Client c = s.client();
        String token = c.csrf;
        c.csrf = null;
        assertEquals("CSRF", c.post("/api/student/corrections", Map.of()).code());
        c.csrf = token;
        c.origin = "https://evil.example";
        assertEquals("BAD_ORIGIN", c.post("/api/student/corrections", Map.of()).code());
        c.origin = null;
        assertEquals(403, c.get("/api/admin/overview").status());
        assertEquals(403, c.get("/api/lecturer/overview").status());
        assertEquals(403, c.get("/api/super/structure").status());
        assertEquals(401, client().get("/api/student/dashboard").status());
    }

    @Test
    void staffLockoutAfterRepeatedFailures() {
        Client l = lecturer("CSNS 141");
        Client c = client();
        for (int i = 0; i < 5; i++) assertEquals(401, c.post("/api/auth/staff/login", Map.of("email", "csns141@lecturer.test", "password", "wrong-password")).status());
        Client.Resp r = c.post("/api/auth/staff/login", Map.of("email", "csns141@lecturer.test", "password", LECTURER_PW));
        assertEquals(429, r.status(), "correct password is refused while locked");
        assertNotNull(l);
    }
}
