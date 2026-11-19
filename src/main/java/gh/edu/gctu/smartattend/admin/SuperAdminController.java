package gh.edu.gctu.smartattend.admin;

import gh.edu.gctu.smartattend.common.*;
import gh.edu.gctu.smartattend.security.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** Platform-level structure (multi-institution ready) and administrator management. SUPER_ADMIN only. */
@RestController
@RequestMapping("/api/super")
public class SuperAdminController {
    private final Db db;
    private final AuditService audit;
    private final SessionService sessions;

    public SuperAdminController(Db db, AuditService audit, SessionService sessions) {
        this.db = db;
        this.audit = audit;
        this.sessions = sessions;
    }

    private CurrentUser sup() { return AuthContext.requireRole(Role.SUPER_ADMIN); }

    @GetMapping("/structure")
    public Map<String, Object> structure() {
        sup();
        return Json.obj(
                "institutions", db.list("SELECT id, name, short_name, timezone, created_at FROM institutions ORDER BY name"),
                "departments", db.list("SELECT d.id, d.name, d.institution_id, i.short_name AS institution FROM departments d JOIN institutions i ON i.id = d.institution_id ORDER BY d.name"),
                "programmes", db.list("SELECT p.id, p.name, p.award, p.department_id, d.name AS department FROM programmes p JOIN departments d ON d.id = p.department_id ORDER BY p.name"),
                "academicYears", db.list("SELECT y.id, y.label, y.institution_id FROM academic_years y ORDER BY y.label DESC"),
                "semesters", db.list("SELECT s.id, s.name, s.start_date, s.end_date, s.teaching_weeks, s.is_current, y.label AS academic_year FROM semesters s JOIN academic_years y ON y.id = s.academic_year_id ORDER BY y.label DESC, s.name"),
                "groups", db.list("SELECT g.id, g.name, g.level, g.expected_size, g.register_status, p.name AS programme, s.name AS semester FROM class_groups g JOIN programmes p ON p.id = g.programme_id JOIN semesters s ON s.id = g.semester_id ORDER BY p.name, g.level, g.name"));
    }

    public record NameBody(String name, String shortName, String timezone, UUID parentId, String award, String label) {}

    @PostMapping("/institutions")
    public Map<String, Object> institution(@RequestBody NameBody b) {
        sup();
        req(b.name(), "Name");
        req(b.shortName(), "Short name");
        String tz = b.timezone() == null || b.timezone().isBlank() ? "Africa/Accra" : b.timezone();
        java.time.ZoneId.of(tz);
        UUID id = db.insertReturningId("INSERT INTO institutions(name, short_name, timezone) VALUES (:n, :s, :t) RETURNING id", "n", b.name().trim(), "s", b.shortName().trim(), "t", tz);
        audit.log("INSTITUTION_CREATED", "institution", id, Json.obj("name", b.name()));
        return Json.obj("id", id);
    }

    @PostMapping("/departments")
    public Map<String, Object> department(@RequestBody NameBody b) {
        sup();
        req(b.name(), "Name");
        UUID id = db.insertReturningId("INSERT INTO departments(institution_id, name) VALUES (:p, :n) RETURNING id", "p", b.parentId(), "n", b.name().trim());
        audit.log("DEPARTMENT_CREATED", "department", id, Json.obj("name", b.name()));
        return Json.obj("id", id);
    }

    @PostMapping("/programmes")
    public Map<String, Object> programme(@RequestBody NameBody b) {
        sup();
        req(b.name(), "Name");
        req(b.award(), "Award");
        UUID id = db.insertReturningId("INSERT INTO programmes(department_id, name, award) VALUES (:p, :n, :a) RETURNING id", "p", b.parentId(), "n", b.name().trim(), "a", b.award().trim());
        audit.log("PROGRAMME_CREATED", "programme", id, Json.obj("name", b.name()));
        return Json.obj("id", id);
    }

    @PostMapping("/academic-years")
    public Map<String, Object> year(@RequestBody NameBody b) {
        sup();
        if (b.label() == null || !b.label().matches("\\d{4}/\\d{4}")) throw ApiException.badRequest("INVALID", "Use the format 2026/2027.");
        UUID id = db.insertReturningId("INSERT INTO academic_years(institution_id, label) VALUES (:p, :l) RETURNING id", "p", b.parentId(), "l", b.label());
        audit.log("ACADEMIC_YEAR_CREATED", "academic_year", id, Json.obj("label", b.label()));
        return Json.obj("id", id);
    }

    public record SemesterBody(UUID academicYearId, String name, String startDate, Integer teachingWeeks) {}

    @PostMapping("/semesters")
    public Map<String, Object> semester(@RequestBody SemesterBody b) {
        sup();
        req(b.name(), "Name");
        UUID id = db.insertReturningId("INSERT INTO semesters(academic_year_id, name, start_date, teaching_weeks) VALUES (:y, :n, :s, :w) RETURNING id",
                "y", b.academicYearId(), "n", b.name().trim(), "s", b.startDate() == null || b.startDate().isBlank() ? null : LocalDate.parse(b.startDate()),
                "w", b.teachingWeeks() == null ? 13 : b.teachingWeeks());
        audit.log("SEMESTER_CREATED", "semester", id, Json.obj("name", b.name()));
        return Json.obj("id", id);
    }

    @PostMapping("/semesters/{id}/make-current")
    @Transactional
    public Map<String, Object> makeCurrent(@PathVariable UUID id) {
        sup();
        Integer open = db.scalar("SELECT count(*)::int FROM attendance_sessions WHERE status = 'OPEN'", Integer.class);
        if (open != null && open > 0) throw ApiException.conflict("SESSIONS_OPEN", "Close all open attendance sessions first.");
        db.update("UPDATE semesters SET is_current = false WHERE is_current");
        if (db.update("UPDATE semesters SET is_current = true WHERE id = :id", "id", id) == 0) throw ApiException.notFound("NOT_FOUND", "Semester not found.");
        audit.log("SEMESTER_MADE_CURRENT", "semester", id, Json.obj());
        return Json.obj("current", true);
    }

    public record GroupBody(UUID programmeId, UUID semesterId, Integer level, String name, Integer expectedSize) {}

    @PostMapping("/groups")
    public Map<String, Object> group(@RequestBody GroupBody b) {
        sup();
        req(b.name(), "Name");
        if (b.level() == null) throw ApiException.badRequest("INVALID", "Level is required.");
        UUID id = db.insertReturningId("""
                INSERT INTO class_groups(programme_id, semester_id, level, name, expected_size) VALUES (:p, :s, :l, :n, :e) RETURNING id""",
                "p", b.programmeId(), "s", b.semesterId(), "l", b.level(), "n", b.name().trim(), "e", b.expectedSize());
        audit.log("CLASS_GROUP_CREATED", "class_group", id, Json.obj("name", b.name()));
        return Json.obj("id", id);
    }

    @GetMapping("/administrators")
    public List<Map<String, Object>> administrators() {
        sup();
        return db.list("""
                SELECT a.id, a.full_name, u.id AS user_id, u.email, u.role, u.status, u.last_login_at, u.must_change_password
                FROM administrators a JOIN app_users u ON u.id = a.user_id ORDER BY u.role DESC, a.full_name""");
    }

    public record AdminBody(String fullName, String email, String role) {}

    @PostMapping("/administrators")
    @Transactional
    public Map<String, Object> createAdmin(@RequestBody AdminBody b) {
        sup();
        req(b.fullName(), "Name");
        String email = AdminController.validEmail(b.email());
        if (email == null) throw ApiException.badRequest("EMAIL_REQUIRED", "Email is required.");
        String role = "SUPER_ADMIN".equals(b.role()) ? "SUPER_ADMIN" : "ADMIN";
        String temp = AdminController.temporaryPassword();
        UUID uid = db.insertReturningId("""
                INSERT INTO app_users(role, email, password_hash, status, must_change_password) VALUES (:r, :e, :h, 'ACTIVE', true) RETURNING id""",
                "r", role, "e", email, "h", AccountController.BCRYPT.encode(temp));
        UUID inst = db.scalar("SELECT id FROM institutions ORDER BY created_at LIMIT 1", UUID.class);
        UUID id = db.insertReturningId("INSERT INTO administrators(user_id, full_name, institution_id) VALUES (:u, :n, :i) RETURNING id",
                "u", uid, "n", b.fullName().trim(), "i", inst);
        audit.log("ADMIN_CREATED", "administrator", id, Json.obj("email", email, "role", role));
        return Json.obj("id", id, "email", email, "temporaryPassword", temp, "note", "Shown once. Must be changed at first sign-in.");
    }

    @PostMapping("/administrators/{id}/status")
    public Map<String, Object> adminStatus(@PathVariable UUID id, @RequestParam String status) {
        CurrentUser me = sup();
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) throw ApiException.badRequest("INVALID_STATUS", "Invalid status.");
        UUID uid = db.scalar("SELECT user_id FROM administrators WHERE id = :id", UUID.class, "id", id);
        if (uid == null) throw ApiException.notFound("NOT_FOUND", "Administrator not found.");
        if (uid.equals(me.userId())) throw ApiException.badRequest("SELF", "You cannot change your own status.");
        db.update("UPDATE app_users SET status = :s WHERE id = :u", "s", status, "u", uid);
        if ("DISABLED".equals(status)) sessions.revokeAllForUser(uid);
        audit.log("ADMIN_STATUS_CHANGED", "administrator", id, Json.obj("status", status));
        return Json.obj("status", status);
    }

    private static void req(String v, String name) {
        if (v == null || v.isBlank()) throw ApiException.badRequest("REQUIRED", name + " is required.");
    }
}
