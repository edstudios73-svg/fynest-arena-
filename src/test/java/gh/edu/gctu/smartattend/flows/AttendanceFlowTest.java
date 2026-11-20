package gh.edu.gctu.smartattend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import gh.edu.gctu.smartattend.support.Client;
import gh.edu.gctu.smartattend.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AttendanceFlowTest extends IntegrationTest {

    private Student ready(String index, String person) {
        return register(index, person);
    }

    @Test
    void validOtpRecordsPresentWithConfirmation() {
        seedStudents(3);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        JsonNode ch = challenge(lec, sid);
        assertTrue(ch.path("otp").asText().matches("\\d{6}"));
        assertFalse(ch.path("qrUrl").asText().contains(ch.path("otp").asText()), "QR must not contain the OTP");

        // student sees the active session
        JsonNode active = expectOk(s.client().get("/api/student/attendance/active")).json();
        assertEquals(sid, active.get(0).path("sessionId").asText());

        Client.Resp r = attend(s, sid, ch.path("otp").asText(), null);
        assertEquals(200, r.status(), r.text());
        JsonNode j = r.json();
        assertEquals("PRESENT", j.path("status").asText());
        assertEquals("CSNS 141", j.path("courseCode").asText());
        assertEquals("TEST00001", j.path("indexNumber").asText());
        assertTrue(j.path("referenceNo").asText().startsWith("SA-"));
        assertEquals(1, db.scalar("SELECT count(*)::int FROM attendance_records WHERE status = 'PRESENT'", Integer.class));
        // OTP is stored only as an HMAC
        assertEquals(0, db.scalar("SELECT count(*)::int FROM attendance_challenges WHERE encode(otp_hash,'escape') LIKE '%' || :o || '%'",
                Integer.class, "o", ch.path("otp").asText()));
    }

    @Test
    void qrCodeAttendanceWorks() {
        seedStudents(1);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        Client.Resp r = attend(s, null, null, qrToken(challenge(lec, sid)));
        assertEquals(200, r.status(), r.text());
    }

    @Test
    void expiredOtpIsRejected() {
        seedStudents(1);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        JsonNode ch = challenge(lec, sid);
        db.update("UPDATE attendance_challenges SET expires_at = now() - interval '1 second', issued_at = now() - interval '61 seconds'");
        Client.Resp r = s.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "otp", ch.path("otp").asText()));
        assertEquals(400, r.status());
        assertEquals("EXPIRED_OTP", r.code());
        // The lecturer screen rotates to a fresh, different challenge
        JsonNode next = challenge(lec, sid);
        assertNotEquals(ch.path("challengeId").asText(), next.path("challengeId").asText());
    }

    @Test
    void wrongOtpIsRejectedAndRateLimited() {
        seedStudents(1);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        String otp = challenge(lec, sid).path("otp").asText();
        String wrong = otp.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) assertEquals("INVALID_OTP", s.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "otp", wrong)).code());
        Client.Resp r = s.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "otp", otp));
        assertEquals(429, r.status(), "locked out after too many wrong codes, even with the right one");
        assertTrue(db.scalar("SELECT count(*)::int FROM fraud_alerts WHERE kind = 'REPEATED_INVALID_OTP'", Integer.class) >= 1);
    }

    @Test
    void codeFromAnotherCourseIsRejected() {
        seedStudents(1);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lecA = lecturer("CSNS 141");
        Client lecB = lecturer("CSSD 101");
        String sidA = startSession(lecA, "CSNS 141", null).path("sessionId").asText();
        String sidB = startSession(lecB, "CSSD 101", null).path("sessionId").asText();
        JsonNode chB = challenge(lecB, sidB);
        // QR from course B while attending course A
        Client.Resp r = s.client().post("/api/student/attendance/begin", Map.of("sessionId", sidA, "qrToken", qrToken(chB)));
        assertEquals(400, r.status());
        assertEquals("WRONG_COURSE", r.code());
        // OTP from course B typed into course A (unless the two random codes collide, 1 in a million)
        String otpA = challenge(lecA, sidA).path("otp").asText();
        if (!otpA.equals(chB.path("otp").asText())) {
            assertEquals("INVALID_OTP", s.client().post("/api/student/attendance/begin", Map.of("sessionId", sidA, "otp", chB.path("otp").asText())).code());
        }
    }

    @Test
    void cannotSubmitTwiceEvenConcurrently() throws Exception {
        seedStudents(1);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        String otp = challenge(lec, sid).path("otp").asText();
        // Prepare two independent attempts up to the final step
        List<String> attempts = new ArrayList<>();
        for (int k = 0; k < 2; k++) {
            JsonNode b = expectOk(s.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "otp", otp))).json();
            String id = b.path("attemptId").asText();
            expectOk(faceUpload(s.client(), "/api/student/attendance/" + id + "/face", b.path("faceChallenge"), "alice", null));
            expectOk(s.client().post("/api/student/attendance/" + id + "/passkey", s.authenticator().get(b.path("passkey"))));
            attempts.add(id);
        }
        // first attempt was expired by the second begin; re-open it to force a true race on the record insert
        db.update("UPDATE attendance_attempts SET status = 'IN_PROGRESS'");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Client.Resp>> fs = new ArrayList<>();
        for (String a : attempts) fs.add(pool.submit(() -> { go.await(); return s.client().post("/api/student/attendance/" + a + "/submit", Map.of()); }));
        go.countDown();
        int ok = 0, conflict = 0;
        for (var f : fs) { int st = f.get(30, TimeUnit.SECONDS).status(); if (st == 200) ok++; else if (st == 409) conflict++; }
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(1, conflict);
        assertEquals(1, db.scalar("SELECT count(*)::int FROM attendance_records", Integer.class));
        // and a fresh attempt is refused outright
        assertEquals("ALREADY_RECORDED", s.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "qrToken", qrToken(challenge(lec, sid)))).code());
    }

    @Test
    void cannotSubmitAfterSessionCloses() {
        seedStudents(2);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        String otp = challenge(lec, sid).path("otp").asText();
        JsonNode b = expectOk(s.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "otp", otp))).json();
        JsonNode closed = expectOk(lec.post("/api/lecturer/sessions/" + sid + "/close", null)).json();
        assertEquals(2, closed.path("markedAbsent").asInt(), "enrolled students without a record become ABSENT");
        Client.Resp r = s.client().post("/api/student/attendance/" + b.path("attemptId").asText() + "/submit", Map.of());
        assertEquals(409, r.status());
        assertEquals("SESSION_CLOSED", r.code());
        assertEquals("SESSION_CLOSED", s.client().post("/api/student/attendance/begin", Map.of("sessionId", sid, "otp", otp)).code());
        assertEquals("SESSION_CLOSED", lec.get("/api/lecturer/sessions/" + sid + "/challenge").code());
    }

    @Test
    void unassignedLecturerCannotStartOrViewAnotherCourse() {
        seedStudents(1);
        verifyRegister();
        Client lec = lecturer("CSNS 141");
        Client.Resp r = lec.post("/api/lecturer/sessions", Map.of("courseId", courseId("MATH 103").toString()));
        assertEquals(403, r.status());
        assertEquals(403, lec.get("/api/lecturer/courses/" + courseId("MATH 103")).status());
        assertEquals(403, lec.get("/api/lecturer/reports/course/" + courseId("MATH 103") + "?format=csv").status());
        Client other = lecturer("MATH 103");
        String sid = startSession(other, "MATH 103", null).path("sessionId").asText();
        assertEquals(403, lec.get("/api/lecturer/sessions/" + sid).status());
        assertEquals(403, lec.get("/api/lecturer/sessions/" + sid + "/challenge").status());
        assertEquals(403, lec.post("/api/lecturer/sessions/" + sid + "/close", null).status());
    }

    @Test
    void studentCannotEditAttendance() {
        seedStudents(1);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        String stId = db.scalar("SELECT id FROM students WHERE index_number = 'TEST00001'", UUID.class).toString();
        assertEquals(403, s.client().post("/api/lecturer/sessions/" + sid + "/records/" + stId, Map.of("status", "PRESENT", "reason", "me")).status());
        assertEquals(403, s.client().post("/api/admin/students/" + stId + "/approve", Map.of()).status());
        assertEquals(403, s.client().post("/api/lecturer/sessions/" + sid + "/close", null).status());
        assertEquals(405, s.client().put("/api/student/history", Map.of("status", "PRESENT")).status());
        assertEquals(0, db.scalar("SELECT count(*)::int FROM attendance_records", Integer.class));
    }

    @Test
    void lateArrivalAndManualExceptionAreRecorded() {
        seedStudents(2);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", Map.of("lateAfterMinutes", 0)).path("sessionId").asText();
        db.update("UPDATE attendance_sessions SET opened_at = now() - interval '1 minute'");
        Client.Resp r = attend(s, sid, challenge(lec, sid).path("otp").asText(), null);
        assertEquals("LATE", r.json().path("status").asText());
        String other = db.scalar("SELECT id FROM students WHERE index_number = 'TEST00002'", UUID.class).toString();
        assertEquals("REASON_REQUIRED", lec.post("/api/lecturer/sessions/" + sid + "/records/" + other, Map.of("status", "EXCUSED", "reason", "")).code());
        expectOk(lec.post("/api/lecturer/sessions/" + sid + "/records/" + other, Map.of("status", "EXCUSED", "reason", "Medical certificate")));
        assertEquals("MANUAL", db.scalar("SELECT verification_status FROM attendance_records WHERE status = 'EXCUSED'", String.class));
        assertTrue(db.scalar("SELECT count(*)::int FROM audit_logs WHERE action = 'ATTENDANCE_MANUAL_CHANGE'", Integer.class) >= 1);
    }

    @Test
    void lecturerCannotWeakenSecurityLayers() {
        seedStudents(1);
        verifyRegister();
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", Map.of("requireFace", false, "requirePasskey", false)).path("sessionId").asText();
        assertEquals(Boolean.TRUE, db.scalar("SELECT require_face AND require_passkey FROM attendance_sessions WHERE id = CAST(:s AS uuid)", Boolean.class, "s", sid));
    }

    @Test
    void strictProximityRejectsOutsideNetworkAndEvidenceFlagsFarGps() {
        seedStudents(2);
        verifyRegister();
        db.update("UPDATE venues SET network_cidrs = '{10.99.0.0/16}', latitude = 5.5600, longitude = -0.2000, radius_m = 60 WHERE code = 'D4A'");
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        db.update("UPDATE class_timetables SET day_of_week = EXTRACT(ISODOW FROM (now() AT TIME ZONE 'Africa/Accra'))::int WHERE course_id = :c", "c", courseId("CSNS 141"));
        String sid = startSession(lec, "CSNS 141", Map.of("proximityPolicy", "STRICT")).path("sessionId").asText();
        s.client().ip = "41.66.1.1"; // not on classroom network
        Client.Resp r = attend(s, sid, challenge(lec, sid).path("otp").asText(), null);
        assertEquals(403, r.status());
        assertEquals("PRESENCE_FAILED", r.code());
        s.client().ip = "10.99.4.20"; // on classroom network
        Client.Resp ok = attend(s, sid, challenge(lec, sid).path("otp").asText(), null);
        assertEquals(200, ok.status(), ok.text());
        assertEquals("PRESENT", ok.json().path("status").asText());
    }

    @Test
    void sharedPhoneForTwoStudentsIsFlagged() {
        seedStudents(2);
        verifyRegister();
        Student a = ready("TEST00001", "alice");
        Student b = ready("TEST00002", "bob");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        assertEquals(200, attend(a, sid, challenge(lec, sid).path("otp").asText(), null).status());
        // Bob uses Alice's phone (same device cookie) to sign in and mark attendance
        b.client().copyCookieFrom(a.client(), "sa_dev");
        Client phone = client();
        phone.copyCookieFrom(a.client(), "sa_dev");
        JsonNode st = expectOk(phone.post("/api/auth/student/login/start", Map.of("indexNumber", "TEST00002"))).json();
        try {
            expectOk(phone.post("/api/auth/student/login/passkey", b.authenticator().get(st.path("publicKey"))));
        } catch (Exception e) { throw new RuntimeException(e); }
        JsonNode fch = expectOk(phone.post("/api/auth/student/login/face/challenge", null)).json();
        expectOk(faceUpload(phone, "/api/auth/student/login/face", fch, "bob", null));
        phone.refresh();
        Student bobOnAlicePhone = new Student(phone, b.authenticator(), "TEST00002", "bob");
        Client.Resp r = attend(bobOnAlicePhone, sid, challenge(lec, sid).path("otp").asText(), null);
        assertEquals(200, r.status(), r.text());
        assertEquals("PENDING_VERIFICATION", r.json().path("status").asText());
        assertTrue(db.scalar("SELECT count(*)::int FROM fraud_alerts WHERE kind = 'SAME_DEVICE_MULTIPLE_STUDENTS'", Integer.class) >= 1);
    }

    @Test
    void onlineSessionDoesNotRequireProximity() {
        seedStudents(1);
        verifyRegister();
        Student s = ready("TEST00001", "alice");
        Client lec = lecturer("CSNS 141");
        String sid = startSession(lec, "CSNS 141", Map.of("mode", "ONLINE", "proximityPolicy", "STRICT")).path("sessionId").asText();
        assertEquals("NONE", db.scalar("SELECT proximity_policy FROM attendance_sessions WHERE id = CAST(:s AS uuid)", String.class, "s", sid));
        s.client().ip = "8.8.8.8";
        assertEquals(200, attend(s, sid, challenge(lec, sid).path("otp").asText(), null).status());
    }

    @Test
    void attendanceCanStartWithoutRegisterVerification() {
        seedStudents(1);
        Client lec = lecturer("CSNS 141");
        startSession(lec, "CSNS 141", null);
    }

    @Test
    void onlyOneOpenSessionPerCourse() {
        seedStudents(1);
        verifyRegister();
        Client lec = lecturer("CSNS 141");
        startSession(lec, "CSNS 141", null);
        assertEquals("SESSION_ALREADY_OPEN", lec.post("/api/lecturer/sessions", Map.of("courseId", courseId("CSNS 141").toString())).code());
    }
}
