package gh.edu.gctu.smartattend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import gh.edu.gctu.smartattend.support.Client;
import gh.edu.gctu.smartattend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner-run flow: students sign in with index number + face, pick a course, confirm their index number,
 * scan their face, scan the QR / enter the code, and must be within range of the admin's device. Attendance
 * only counts after an end-of-class confirmation while still in range.
 */
class OwnerFlowTest extends IntegrationTest {
    static final double LAT = 5.6037, LON = -0.1870;      // admin's device
    static final double NEAR = LAT + 0.00018;             // ≈ 20 m away
    static final double FAR = LAT + 0.0018;               // ≈ 200 m away

    @BeforeEach
    void ownerSettings() {
        resetSetting("security.passkeyOnLogin", "false");
        resetSetting("security.passkeyOnAttendance", "false");
        resetSetting("attendance.checkoutMinutes", "10");
        resetSetting("attendance.classMinutes", "60");
        resetSetting("proximity.defaultPolicy", "GEOFENCE");
    }

    private static Map<String, Object> gps(double lat) {
        return Map.of("latitude", lat, "longitude", LON, "accuracy", 15.0);
    }

    private String start(Client admin) {
        Map<String, Object> body = new HashMap<>(Map.of("courseId", courseId("CSNS 141").toString(), "latitude", LAT, "longitude", LON, "accuracy", 10.0));
        return expectOk(admin.post("/api/lecturer/sessions", body)).json().path("sessionId").asText();
    }

    @Test
    void registerAndSignInWithIndexNumberAndFaceOnly() {
        seedStudents(1);
        verifyRegister();
        Student s = register("TEST00001", "alice");
        assertEquals(200, s.client().get("/api/me").status(), "signed in straight after face enrolment, no passkey");
        assertEquals(0, db.scalar("SELECT count(*)::int FROM webauthn_credentials", Integer.class));

        Client c = client();
        JsonNode st = expectOk(c.post("/api/auth/student/login/start", Map.of("indexNumber", "TEST00001"))).json();
        assertEquals("FACE", st.path("next").asText());
        assertEquals(401, c.get("/api/me").status());
        JsonNode ch = expectOk(c.post("/api/auth/student/login/face/challenge", null)).json();
        JsonNode bad = expectOk(faceUpload(c, "/api/auth/student/login/face", ch, "mallory", null)).json();
        assertFalse(bad.path("passed").asBoolean(), "someone else's face cannot sign in");
        ch = expectOk(c.post("/api/auth/student/login/face/challenge", null)).json();
        assertTrue(expectOk(faceUpload(c, "/api/auth/student/login/face", ch, "alice", null)).json().path("signedIn").asBoolean());
        assertEquals(200, c.get("/api/me").status());
    }

    @Test
    void courseRepRunsCoursesAndReportsButNothingElse() {
        seedStudents(2);
        verifyRegister();
        Client rep = admin("ADMIN");
        String cid = courseId("CSNS 141").toString();
        assertEquals(200, rep.get("/api/admin/courses").status());
        Map<String, Object> edit = new HashMap<>(Map.of("code", "CSNS 141",
                "title", "Digital Electronics", "lecturerName", "Dr Rep Edit", "classMinutes", 90, "geofenceMeters", 50));
        assertEquals(200, rep.put("/api/admin/courses/" + cid, edit).status(), "rep edits a course");
        String sid = start(rep);
        assertEquals(200, rep.get("/api/lecturer/sessions/" + sid + "/challenge").status(), "rep shows the board");
        assertEquals(200, rep.get("/api/lecturer/sessions/" + sid).status(), "rep sees who checked in");
        // Not for course reps
        assertEquals(403, rep.get("/api/admin/students").status());
        assertEquals(403, rep.get("/api/admin/settings").status());
        assertEquals(403, rep.post("/api/admin/courses", edit).status());
        assertEquals(403, rep.get("/api/admin/audit").status());
        assertEquals(403, rep.post("/api/lecturer/sessions/" + sid + "/records/" + db.scalar("SELECT id FROM students LIMIT 1", UUID.class),
                Map.of("status", "PRESENT", "reason", "nope")).status());
        assertEquals(200, rep.post("/api/lecturer/sessions/" + sid + "/close", Map.of()).status(), "rep turns attendance off");
        assertEquals(403, rep.post("/api/admin/sessions/" + sid + "/mark-all", Map.of("status", "PRESENT")).status());
        assertEquals(403, rep.post("/api/admin/courses/" + cid + "/manual-class", Map.of()).status());
        // Past classes, attendance tracking and reports
        assertEquals(200, rep.get("/api/admin/sessions").status());
        assertEquals(200, rep.get("/api/admin/analytics").status());
        assertEquals(200, rep.get("/api/admin/reports/course/" + cid + "?format=pdf").status());
        assertEquals(200, rep.get("/api/admin/reports/records?format=xlsx").status());
        Client.Resp pdf = rep.get("/api/lecturer/reports/session/" + sid + "?format=pdf");
        assertEquals(200, pdf.status());
        assertTrue(pdf.text().startsWith("%PDF"), "attendance sheet is a PDF");
        assertEquals(403, rep.get("/api/admin/reports/student/" + db.scalar("SELECT id FROM students LIMIT 1", UUID.class)).status());
    }

    @Test
    void superAdminMarksAClassWhereAttendanceWasNotTurnedOn() {
        seedStudents(3);
        verifyRegister();
        Client sup = admin("SUPER_ADMIN");
        String cid = courseId("CSNS 141").toString();
        JsonNode r = expectOk(sup.post("/api/admin/courses/" + cid + "/manual-class", Map.of("date", java.time.LocalDate.now().minusDays(1).toString(), "everyonePresent", true))).json();
        String sid = r.path("sessionId").asText();
        assertEquals(3, r.path("students").asInt());
        assertEquals(3, db.scalar("SELECT count(*)::int FROM attendance_records WHERE status = 'PRESENT'", Integer.class));
        // remove one student's mark, then clear all
        UUID st = db.scalar("SELECT id FROM students ORDER BY index_number LIMIT 1", UUID.class);
        expectOk(sup.post("/api/lecturer/sessions/" + sid + "/records/" + st, Map.of("status", "ABSENT", "reason", "Was not in class")));
        assertEquals(2, db.scalar("SELECT count(*)::int FROM attendance_records WHERE status = 'PRESENT'", Integer.class));
        expectOk(sup.post("/api/admin/sessions/" + sid + "/mark-all", Map.of("status", "ABSENT")));
        assertEquals(0, db.scalar("SELECT count(*)::int FROM attendance_records WHERE status = 'PRESENT'", Integer.class));
        assertEquals("CLOSED", db.scalar("SELECT status FROM attendance_sessions WHERE id = :id::uuid", String.class, "id", sid));
    }

    @Test
    void registrationFaceIsTheOnlyFaceThatWorksForever() {
        seedStudents(1);
        verifyRegister();
        Student alice = register("TEST00001", "alice");
        String saved = db.scalar("SELECT encode(digest(template_ciphertext, 'sha256'), 'hex') FROM biometric_enrollments", String.class);
        // Registering again with the same index number is refused
        assertEquals("ALREADY_REGISTERED", client().post("/api/auth/student/register/start", Map.of("indexNumber", "TEST00001")).code());
        // Another face cannot mark attendance for this index number
        Client admin = admin("SUPER_ADMIN");
        String sid = start(admin);
        JsonNode b = expectOk(alice.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "indexNumber", "TEST00001"))).json();
        String att = b.path("attemptId").asText();
        Client.Resp bad = faceUpload(alice.client(), "/api/student/attendance/" + att + "/face", b.path("faceChallenge"), "mallory", null);
        assertFalse(bad.status() == 200 && bad.json().path("passed").asBoolean(true), "another face is refused: " + bad.json());
        assertEquals("FACE_REQUIRED", alice.client().post("/api/student/attendance/" + att + "/code",
                Map.of("otp", challenge(admin, sid).path("otp").asText())).code());
        // The saved face was never replaced
        assertEquals(1, db.scalar("SELECT count(*)::int FROM biometric_enrollments", Integer.class));
        assertEquals(saved, db.scalar("SELECT encode(digest(template_ciphertext, 'sha256'), 'hex') FROM biometric_enrollments", String.class));
    }

    @Test
    void fullOwnerFlowWithGeofenceAndEndOfClassConfirmation() {
        seedStudents(3);
        verifyRegister();
        Student alice = register("TEST00001", "alice");
        Student bob = register("TEST00002", "bob");
        Client admin = admin("SUPER_ADMIN");

        assertEquals("LOCATION_REQUIRED", admin.post("/api/lecturer/sessions", Map.of("courseId", courseId("CSNS 141").toString())).code(),
                "the admin's device location is required to start");
        String sid = start(admin);
        String otp = challenge(admin, sid).path("otp").asText();

        // Index number must be the student's own
        assertEquals("INDEX_MISMATCH", alice.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "indexNumber", "TEST00002")).code());
        JsonNode b = expectOk(alice.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "indexNumber", "test00001"))).json();
        String att = b.path("attemptId").asText();
        assertTrue(b.path("requiresLocation").asBoolean());
        assertFalse(b.path("requiresPasskey").asBoolean());
        // Face comes before the code
        assertEquals("FACE_REQUIRED", alice.client().post("/api/student/attendance/" + att + "/code", Map.of("otp", otp)).code());
        expectOk(faceUpload(alice.client(), "/api/student/attendance/" + att + "/face", b.path("faceChallenge"), "alice", null));
        assertEquals("CODE_REQUIRED", alice.client().post("/api/student/attendance/" + att + "/submit", gps(NEAR)).code());
        expectOk(alice.client().post("/api/student/attendance/" + att + "/code", Map.of("otp", otp)));
        // Too far from the admin → refused; within range → checked in
        Client.Resp far = alice.client().post("/api/student/attendance/" + att + "/submit", gps(FAR));
        assertEquals(403, far.status());
        assertEquals("OUT_OF_RANGE", far.code());
        assertTrue(far.json().path("error").path("message").asText().contains("m away"));
        assertEquals("OUT_OF_RANGE", alice.client().post("/api/student/attendance/" + att + "/submit", Map.of()).code(), "no location = no check-in");
        JsonNode in = expectOk(alice.client().post("/api/student/attendance/" + att + "/submit", gps(NEAR))).json();
        assertEquals("CHECKED_IN", in.path("status").asText());

        // Bob checks in via QR (scanned from the board) too
        JsonNode bb = expectOk(bob.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "indexNumber", "TEST00002"))).json();
        String batt = bb.path("attemptId").asText();
        expectOk(faceUpload(bob.client(), "/api/student/attendance/" + batt + "/face", bb.path("faceChallenge"), "bob", null));
        expectOk(bob.client().post("/api/student/attendance/" + batt + "/code", Map.of("qrToken", qrToken(challenge(admin, sid)))));
        assertEquals("CHECKED_IN", expectOk(bob.client().post("/api/student/attendance/" + batt + "/submit", gps(NEAR))).json().path("status").asText());

        // Not counted yet; confirmation only opens in the last minutes of class
        assertEquals("TOO_EARLY", alice.client().post("/api/student/attendance/checkout/" + sid + "/start", null).code());
        db.update("UPDATE attendance_sessions SET ends_at = now() + interval '5 minutes', closes_at = now() + interval '15 minutes'");
        JsonNode co = expectOk(alice.client().post("/api/student/attendance/checkout/" + sid + "/start", null)).json();
        assertEquals("FACE_REQUIRED", alice.client().post("/api/student/attendance/checkout/" + sid + "/confirm", gps(NEAR)).code());
        JsonNode cf = expectOk(faceUpload(alice.client(), "/api/student/attendance/checkout/" + sid + "/face", co.path("faceChallenge"), "alice", null)).json();
        assertTrue(cf.path("passed").asBoolean());
        assertEquals("OUT_OF_RANGE", alice.client().post("/api/student/attendance/checkout/" + sid + "/confirm", gps(FAR)).code(),
                "left the class → cannot confirm");
        JsonNode done = expectOk(alice.client().post("/api/student/attendance/checkout/" + sid + "/confirm", gps(NEAR))).json();
        assertEquals("PRESENT", done.path("status").asText());

        // The admin's device moved: the geofence follows it
        expectOk(admin.post("/api/lecturer/sessions/" + sid + "/anchor", Map.of("latitude", FAR, "longitude", LON, "accuracy", 10.0)));

        // Bob never confirms (left early); student 3 never came. Closing marks both absent.
        JsonNode closed = expectOk(admin.post("/api/lecturer/sessions/" + sid + "/close", null)).json();
        assertEquals(1, closed.path("leftEarly").asInt());
        assertEquals("ABSENT", db.scalar("SELECT r.status FROM attendance_records r JOIN students s ON s.id = r.student_id WHERE s.index_number = 'TEST00002'", String.class));
        assertEquals("PRESENT", db.scalar("SELECT r.status FROM attendance_records r JOIN students s ON s.id = r.student_id WHERE s.index_number = 'TEST00001'", String.class));
        assertEquals("ABSENT", db.scalar("SELECT r.status FROM attendance_records r JOIN students s ON s.id = r.student_id WHERE s.index_number = 'TEST00003'", String.class));
    }
}
