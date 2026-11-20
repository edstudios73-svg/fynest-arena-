package gh.edu.gctu.smartattend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import gh.edu.gctu.smartattend.support.Client;
import gh.edu.gctu.smartattend.support.IntegrationTest;
import gh.edu.gctu.smartattend.support.SoftwareAuthenticator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdministrationFlowTest extends IntegrationTest {

    /** Workbook shaped like "DIPLOMA IN COMPUTER SCIENCE GROUPS.xlsx" (sheet GROUP A) with synthetic, non-real data. */
    static byte[] workbook(int n, boolean withProblems) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("cover sheet");
            Sheet s = wb.createSheet("GROUP A");
            Row h = s.createRow(0);
            String[] cols = {"Student ID", "Title", "First Name", "Last Name", "Other Names"};
            for (int i = 0; i < cols.length; i++) h.createCell(i).setCellValue(cols[i]);
            for (int i = 1; i <= n; i++) {
                Row r = s.createRow(i);
                r.createCell(0).setCellValue(String.format("SYN%07d", i));
                r.createCell(1).setCellValue(i % 2 == 0 ? "Ms." : "Mr.");
                r.createCell(2).setCellValue("Given" + i);
                r.createCell(3).setCellValue("Family" + i);
                if (i % 4 == 0) r.createCell(4).setCellValue("Other" + i);
            }
            if (withProblems) {
                Row dup = s.createRow(n + 1);
                dup.createCell(0).setCellValue("SYN0000001");
                dup.createCell(2).setCellValue("Dupe");
                dup.createCell(3).setCellValue("Person");
                Row missing = s.createRow(n + 2);
                missing.createCell(0).setCellValue("SYN9999999");
                missing.createCell(3).setCellValue("NoFirstName");
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void adminImportsRegisterAndDiscrepancyMustBeVerified() throws Exception {
        Client admin = admin("ADMIN");
        UUID group = groupId();
        Client.Resp prev = admin.multipart("/api/admin/imports", Map.of("classGroupId", group.toString()),
                List.of(new MockMultipartFile("file", "DIPLOMA IN COMPUTER SCIENCE GROUPS.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(134, false))));
        assertEquals(200, prev.status(), prev.text());
        JsonNode p = prev.json();
        assertEquals("GROUP A", p.path("sheet").asText(), "finds the sheet with the register columns");
        assertEquals(134, p.path("rowCount").asInt());
        assertEquals(134, p.path("validCount").asInt());
        assertEquals(129, p.path("discrepancy").path("expected").asInt());
        assertEquals(134, p.path("discrepancy").path("afterImport").asInt());
        assertEquals(0, db.scalar("SELECT count(*)::int FROM students", Integer.class), "nothing is written before confirmation");

        JsonNode commit = expectOk(admin.post("/api/admin/imports/" + p.path("batchId").asText() + "/commit", null)).json();
        assertEquals(134, commit.path("inserted").asInt());
        assertEquals(134 * 6, commit.path("enrolments").asInt(), "enrolled in all six timetabled courses");
        assertEquals("Given4 Other4 Family4", db.scalar("SELECT first_name || ' ' || other_names || ' ' || last_name FROM students WHERE index_number = 'SYN0000004'", String.class));
        assertEquals("BATCH_DONE", admin.post("/api/admin/imports/" + p.path("batchId").asText() + "/commit", null).code(), "cannot commit twice");

        // The list is active straight away (students can register); the admin can still record a check.
        assertEquals("VERIFIED", db.scalar("SELECT register_status FROM class_groups", String.class));

        // Verification refused without acknowledging the discrepancy
        assertEquals("DISCREPANCY_UNRESOLVED", admin.post("/api/admin/groups/" + group + "/verify", Map.of("note", "ok")).code());
        expectOk(admin.post("/api/admin/groups/" + group + "/verify",
                Map.of("acknowledgeDiscrepancy", true, "note", "Checked with department: 5 late registrations confirmed")));
        assertEquals("VERIFIED", db.scalar("SELECT register_status FROM class_groups", String.class));
        assertTrue(db.scalar("SELECT count(*)::int FROM audit_logs WHERE action IN ('REGISTER_IMPORT_PREVIEWED','REGISTER_IMPORT_COMMITTED','REGISTER_VERIFIED')", Integer.class) >= 3);
    }

    @Test
    void importReportsDuplicatesAndMissingFieldsWithoutSilentlyDroppingThem() throws Exception {
        Client admin = admin("ADMIN");
        JsonNode p = expectOk(admin.multipart("/api/admin/imports", Map.of("classGroupId", groupId().toString()),
                List.of(new MockMultipartFile("file", "register.xlsx", "application/octet-stream", workbook(10, true))))).json();
        assertEquals(12, p.path("rowCount").asInt());
        assertEquals(2, p.path("duplicateCount").asInt());
        assertEquals(1, p.path("invalidCount").asInt());
        assertEquals(9, p.path("validCount").asInt());
        JsonNode c = expectOk(admin.post("/api/admin/imports/" + p.path("batchId").asText() + "/commit", null)).json();
        assertEquals(9, c.path("inserted").asInt());
        assertEquals(3, c.path("skipped").asInt());
    }

    @Test
    void importRejectsWrongStructure() {
        Client admin = admin("ADMIN");
        JsonNode p = expectOk(admin.multipart("/api/admin/imports", Map.of("classGroupId", groupId().toString()),
                List.of(new MockMultipartFile("file", "x.csv", "text/csv", "Name,Email\na,b\n".getBytes())))).json();
        assertFalse(p.path("valid").asBoolean());
        JsonNode p2 = expectOk(admin.multipart("/api/admin/imports", Map.of("classGroupId", groupId().toString()),
                List.of(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", "not excel".getBytes())))).json();
        assertFalse(p2.path("valid").asBoolean());
        JsonNode csv = expectOk(admin.multipart("/api/admin/imports", Map.of("classGroupId", groupId().toString()),
                List.of(new MockMultipartFile("file", "r.csv", "text/csv", "Student ID,First Name,Last Name\nCSV0001,\"Ama, Jr\",Mensah\n".getBytes())))).json();
        assertEquals(1, csv.path("validCount").asInt());
        assertEquals("Ama, Jr", csv.path("rows").get(0).path("firstName").asText());
    }

    @Test
    void reportsContainCorrectData() throws Exception {
        seedStudents(4);
        verifyRegister();
        Student a = register("TEST00001", "alice");
        Student b = register("TEST00002", "bob");
        Client lec = lecturer("CSNS 141");
        // Session 1: alice present, bob present, others absent
        String s1 = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        assertEquals(200, attend(a, s1, challenge(lec, s1).path("otp").asText(), null).status());
        assertEquals(200, attend(b, s1, challenge(lec, s1).path("otp").asText(), null).status());
        expectOk(lec.post("/api/lecturer/sessions/" + s1 + "/close", null));
        // Session 2: only alice
        String s2 = startSession(lec, "CSNS 141", null).path("sessionId").asText();
        assertEquals(200, attend(a, s2, challenge(lec, s2).path("otp").asText(), null).status());
        expectOk(lec.post("/api/lecturer/sessions/" + s2 + "/close", null));

        String csv = lec.get("/api/lecturer/reports/course/" + courseId("CSNS 141") + "?format=csv").text();
        assertTrue(csv.contains("TEST00001,First1 Last1,2,2,0,0,0,0,100.0%"), csv);
        assertTrue(csv.contains("TEST00002,First2 Last2,2,1,0,1,0,0,50.0%"), csv);
        assertTrue(csv.contains("TEST00004,First4 Last4,2,0,0,2,0,0,0.0%"), csv);

        Client.Resp xlsx = lec.get("/api/lecturer/reports/session/" + s1 + "?format=xlsx");
        assertEquals(200, xlsx.status());
        assertEquals('P', (char) xlsx.body()[0]);
        Client.Resp pdf = lec.get("/api/lecturer/reports/course/" + courseId("CSNS 141") + "?format=pdf");
        assertEquals("%PDF", new String(pdf.body(), 0, 4));

        // Student dashboard shows the same percentage
        JsonNode dash = expectOk(b.client().get("/api/student/dashboard")).json();
        for (JsonNode c : dash.path("courses")) {
            if ("CSNS 141".equals(c.path("code").asText())) assertEquals(50.0, c.path("percentage").asDouble());
        }
        Client admin = admin("ADMIN");
        String recs = admin.get("/api/admin/reports/records?format=csv&courseId=" + courseId("CSNS 141")).text();
        assertEquals(8, recs.lines().filter(l -> l.contains("CSNS 141")).count(), "2 sessions × 4 students");
    }

    @Test
    void deviceRecoveryRequiresApprovalAndRevokesOldPasskey() throws Exception {
        seedStudents(1);
        verifyRegister();
        Student s = register("TEST00001", "alice");
        byte[] oldCred = s.authenticator().credentialId();

        Client newPhone = client();
        expectOk(newPhone.post("/api/auth/student/recover/start", Map.of("indexNumber", "TEST00001", "reason", "Phone stolen")));
        JsonNode ch = expectOk(newPhone.post("/api/auth/student/recover/face/challenge", null)).json();
        assertTrue(expectOk(faceUpload(newPhone, "/api/auth/student/recover/face", ch, "alice", null)).json().path("passed").asBoolean());
        JsonNode sub = expectOk(newPhone.post("/api/auth/student/recover/submit", Map.of("reason", "Phone stolen on campus"))).json();
        assertTrue(sub.path("faceVerified").asBoolean());

        // Not approved yet → cannot register a new passkey
        assertEquals(403, newPhone.post("/api/auth/student/recover/passkey/options", null).status());

        Client admin = admin("ADMIN");
        String reqId = sub.path("requestId").asText();
        assertEquals("NOTE_REQUIRED", admin.post("/api/admin/recovery/" + reqId + "/decide", Map.of("approve", true, "note", "")).code());
        expectOk(admin.post("/api/admin/recovery/" + reqId + "/decide", Map.of("approve", true, "note", "Student ID card checked in person")));

        // Another device cannot use the approval
        Client thief = client();
        expectOk(thief.post("/api/auth/student/recover/start", Map.of("indexNumber", "TEST00001", "reason", "x")));
        JsonNode tch = expectOk(thief.post("/api/auth/student/recover/face/challenge", null)).json();
        expectOk(faceUpload(thief, "/api/auth/student/recover/face", tch, "alice", null));
        assertEquals(403, thief.post("/api/auth/student/recover/passkey/options", null).status());

        // The approved phone completes recovery (fresh face check required)
        expectOk(newPhone.post("/api/auth/student/recover/start", Map.of("indexNumber", "TEST00001", "reason", "continue")));
        JsonNode ch2 = expectOk(newPhone.post("/api/auth/student/recover/face/challenge", null)).json();
        JsonNode f2 = expectOk(faceUpload(newPhone, "/api/auth/student/recover/face", ch2, "alice", null)).json();
        assertEquals("APPROVED", f2.path("requestStatus").asText());
        JsonNode opts = expectOk(newPhone.post("/api/auth/student/recover/passkey/options", null)).json();
        SoftwareAuthenticator newAuth = new SoftwareAuthenticator(ORIGIN, "localhost");
        expectOk(newPhone.post("/api/auth/student/recover/passkey/verify", newAuth.create(opts.path("publicKey"))));

        assertNotNull(db.scalar("SELECT revoked_at FROM webauthn_credentials WHERE credential_id = :c", java.sql.Timestamp.class, "c", oldCred));
        assertEquals(401, s.client().get("/api/me").status(), "old device sessions are revoked");
        assertEquals(200, newPhone.refresh().get("/api/me").status());
    }

    @Test
    void ownerEditsCourseAndClassUsesItsSettings() {
        seedStudents(2);
        Client admin = admin("SUPER_ADMIN");
        String id = courseId("CSNS 141").toString();
        expectOk(admin.put("/api/admin/courses/" + id, Map.of("title", "Digital Electronics", "code", "CSNS 141",
                "lecturerName", "Gabriel Gidisu", "creditHours", 3, "classMinutes", 180, "geofenceMeters", 30)));
        JsonNode c = null;
        for (JsonNode x : expectOk(admin.get("/api/admin/courses")).json()) if (x.path("id").asText().equals(id)) c = x;
        assertEquals(3, c.path("creditHours").asInt());
        assertEquals(180, c.path("classMinutes").asInt());
        assertEquals(30, c.path("geofenceM").asInt());
        assertEquals("INVALID", admin.put("/api/admin/courses/" + id, Map.of("title", "X", "classMinutes", 5)).code());

        // Activating attendance uses the course's duration and distance
        String sid = expectOk(admin.post("/api/lecturer/sessions", Map.of("courseId", id, "proximityPolicy", "GEOFENCE",
                "latitude", 5.6, "longitude", -0.18, "accuracy", 10.0))).json().path("sessionId").asText();
        assertEquals(30, db.scalar("SELECT geofence_m FROM attendance_sessions WHERE id = CAST(:s AS uuid)", Integer.class, "s", sid));
        assertTrue(db.scalar("SELECT ends_at - opened_at BETWEEN interval '179 minutes' AND interval '181 minutes' FROM attendance_sessions WHERE id = CAST(:s AS uuid)", Boolean.class, "s", sid));
        JsonNode live = null;
        for (JsonNode x : expectOk(admin.get("/api/admin/courses")).json()) if (x.path("id").asText().equals(id)) live = x;
        assertEquals(sid, live.path("openSessionId").asText(), "course card shows attendance on");

        // New course: everyone in the class list can take it
        String nid = expectOk(admin.post("/api/admin/courses", Map.of("title", "Web Design", "code", "CSWD 120", "classMinutes", 90))).json().path("id").asText();
        assertEquals(2, db.scalar("SELECT count(*)::int FROM course_enrollments WHERE course_id = CAST(:c AS uuid)", Integer.class, "c", nid));
    }

    @Test
    void securityPoliciesRequireSuperAdmin() {
        Client admin = admin("ADMIN");
        assertEquals(403, admin.put("/api/admin/settings", Map.of("key", "security.faceOnAttendance", "value", "false")).status());
        expectOk(admin.put("/api/admin/settings", Map.of("key", "attendance.lateAfterMinutes", "value", "10")));
        assertEquals("INVALID_SETTING", admin.put("/api/admin/settings", Map.of("key", "attendance.lateAfterMinutes", "value", "-5")).code());
        Client sup = admin("SUPER_ADMIN");
        expectOk(sup.put("/api/admin/settings", Map.of("key", "face.matchThreshold", "value", "0.45")));
        assertEquals(403, admin.get("/api/super/structure").status());
        expectOk(sup.get("/api/super/structure"));
        resetSetting("face.matchThreshold", "0.42");
    }

    @Test
    void auditLogIsAppendOnly() {
        admin("ADMIN").post("/api/admin/settings", Map.of());
        db.update("INSERT INTO audit_logs(action) VALUES ('TEST')");
        assertThrows(Exception.class, () -> db.update("UPDATE audit_logs SET action = 'X'"));
        assertThrows(Exception.class, () -> db.update("DELETE FROM audit_logs"));
    }

    @Test
    void adminNeverReceivesBiometricTemplates() {
        seedStudents(1);
        verifyRegister();
        register("TEST00001", "alice");
        Client admin = admin("ADMIN");
        UUID sid = db.scalar("SELECT id FROM students", UUID.class);
        String body = admin.get("/api/admin/students/" + sid).text();
        assertFalse(body.contains("template"), body);
        assertTrue(body.contains("\"provider\":\"test-synthetic\""));
    }

    @Test
    void lecturerTemporaryPasswordMustBeChanged() {
        Client admin = admin("ADMIN");
        String lid = admin.get("/api/admin/lecturers").json().get(0).path("id").asText();
        assertEquals("EMAIL_REQUIRED", admin.post("/api/admin/lecturers/" + lid + "/credentials", null).code());
        expectOk(admin.put("/api/admin/lecturers/" + lid, Map.of("fullName", "Adusei-Poku Afful", "title", "Dr.", "email", "afful@lecturer.test")));
        String temp = expectOk(admin.post("/api/admin/lecturers/" + lid + "/credentials", null)).json().path("temporaryPassword").asText();
        Client l = client();
        expectOk(l.post("/api/auth/staff/login", Map.of("email", "afful@lecturer.test", "password", temp, "portal", "lecturer")));
        l.refresh();
        assertEquals("PASSWORD_CHANGE_REQUIRED", l.get("/api/lecturer/overview").code());
        assertEquals("WEAK_PASSWORD", l.post("/api/me/password", Map.of("currentPassword", temp, "newPassword", "short")).code());
        expectOk(l.post("/api/me/password", Map.of("currentPassword", temp, "newPassword", "A-much-Better-Pass9")));
        expectOk(l.get("/api/lecturer/overview"));
    }
}
