package gh.edu.gctu.smartattend.config;

import gh.edu.gctu.smartattend.common.AuditService;
import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.common.Json;
import gh.edu.gctu.smartattend.security.AccountController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Creates the first Super Administrator from environment variables if none exists.
 * No default credentials are ever built in.
 */
@Component
public class Bootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);
    private final Db db;
    private final AppProperties props;
    private final AuditService audit;
    private final gh.edu.gctu.smartattend.admin.ImportService imports;

    public Bootstrap(Db db, AppProperties props, AuditService audit, gh.edu.gctu.smartattend.admin.ImportService imports) {
        this.db = db;
        this.props = props;
        this.audit = audit;
        this.imports = imports;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            createSuperAdmin();
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            log.info("Super Administrator was created by another instance starting at the same time.");
        }
        seedRegisterIfEmpty();
    }

    /**
     * When the database has no students yet, import the official class list from the SMARTATTEND_SEED_REGISTER
     * environment variable (base64 of the .xlsx or .csv, stored encrypted in the hosting platform, never in git).
     * Students can register as soon as the list is loaded.
     * Safe if several instances start at once (inserts ignore duplicates).
     */
    void seedRegisterIfEmpty() {
        String b64 = System.getenv("SMARTATTEND_SEED_REGISTER");
        if (b64 == null || b64.isBlank()) return;
        Integer existing = db.scalar("SELECT count(*)::int FROM students", Integer.class);
        if (existing != null && existing > 0) return;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(b64.trim());
            String name = bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K' ? "register.xlsx" : "register.csv";
            var parsed = imports.parse(name, bytes, null);
            if (!parsed.fileErrors().isEmpty()) { log.warn("Seed register not imported: {}", parsed.fileErrors()); return; }
            java.util.UUID group = db.scalar("SELECT id FROM class_groups ORDER BY created_at LIMIT 1", java.util.UUID.class);
            java.util.Set<String> seen = new java.util.HashSet<>();
            int n = 0;
            for (var r : parsed.rows()) {
                String idx = (String) r.get("indexNumber");
                if (idx.isEmpty() || !seen.add(idx) || ((String) r.get("firstName")).isEmpty() || ((String) r.get("lastName")).isEmpty()) continue;
                n += db.update("""
                        INSERT INTO students(class_group_id, index_number, title, first_name, last_name, other_names)
                        VALUES (:g, :i, NULLIF(:t, ''), :f, :l, NULLIF(:o, '')) ON CONFLICT (index_number) DO NOTHING""",
                        "g", group, "i", idx, "t", r.get("title"), "f", r.get("firstName"), "l", r.get("lastName"), "o", r.get("otherNames"));
            }
            db.update("""
                    INSERT INTO course_enrollments(course_id, student_id, semester_id)
                    SELECT DISTINCT t.course_id, s.id, t.semester_id FROM students s JOIN class_timetables t ON t.class_group_id = s.class_group_id
                    ON CONFLICT DO NOTHING""");
            audit.logAs(null, "SYSTEM", "PREVIEW_REGISTER_SEEDED", "class_group", group, Json.obj("students", n));
            log.info("Seeded {} students from SMARTATTEND_SEED_REGISTER (register left unverified)", n);
        } catch (Exception e) {
            log.warn("Seed register failed: {}", e.getMessage());
        }
    }

    void createSuperAdmin() {
        Integer count = db.scalar("SELECT count(*)::int FROM app_users WHERE role = 'SUPER_ADMIN'", Integer.class);
        if (count != null && count > 0) return;
        String email = props.bootstrapSuperAdminEmail();
        String pw = props.bootstrapSuperAdminPassword();
        if (email == null || email.isBlank() || pw == null || pw.isBlank()) {
            log.warn("No Super Administrator exists. Set SMARTATTEND_SUPERADMIN_EMAIL and SMARTATTEND_SUPERADMIN_PASSWORD and restart.");
            return;
        }
        AccountController.validatePassword(pw);
        UUID inst = db.scalar("SELECT id FROM institutions ORDER BY created_at LIMIT 1", UUID.class);
        UUID uid = db.insertReturningId("""
                INSERT INTO app_users(role, email, password_hash, status, must_change_password)
                VALUES ('SUPER_ADMIN', :e, :h, 'ACTIVE', true) RETURNING id""",
                "e", email.trim().toLowerCase(), "h", AccountController.BCRYPT.encode(pw));
        db.update("INSERT INTO administrators(user_id, full_name, institution_id) VALUES (:u, 'Super Administrator', :i)", "u", uid, "i", inst);
        audit.logAs(null, "SYSTEM", "SUPER_ADMIN_BOOTSTRAPPED", "user", uid, Json.obj("email", email));
        log.info("Bootstrap Super Administrator created for {} (password change required at first sign-in).", email);
    }
}
