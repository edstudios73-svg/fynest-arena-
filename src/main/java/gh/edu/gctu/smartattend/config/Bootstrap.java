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

    public Bootstrap(Db db, AppProperties props, AuditService audit) {
        this.db = db;
        this.props = props;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
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
