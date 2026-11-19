package gh.edu.gctu.smartattend.security;

import gh.edu.gctu.smartattend.common.ApiException;
import gh.edu.gctu.smartattend.common.Db;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Short-lived server-side state for multi-step, pre-login flows (registration, sign-in, recovery). */
@Service
public class FlowService {
    private final Db db;
    private final Cookies cookies;

    public FlowService(Db db, Cookies cookies) {
        this.db = db;
        this.cookies = cookies;
    }

    public UUID start(String kind, UUID studentId, String stage, HttpServletResponse res) {
        String token = Crypto.randomToken(32);
        UUID id = db.insertReturningId("""
                INSERT INTO auth_flows(token_hash, kind, student_id, stage, expires_at)
                VALUES (:h, :k, :s, :st, now() + interval '20 minutes') RETURNING id""",
                "h", Crypto.sha256(token), "k", kind, "s", studentId, "st", stage);
        cookies.set(res, Cookies.FLOW, token, Duration.ofMinutes(20), "Strict");
        return id;
    }

    /** Load the active flow of the given kind; optionally require one of the given stages. */
    public Map<String, Object> require(HttpServletRequest req, String kind, String... stages) {
        String token = Cookies.read(req, Cookies.FLOW);
        if (token == null) throw ApiException.badRequest("FLOW_EXPIRED", "Your session for this step has expired. Please start again.");
        Map<String, Object> f = db.one("""
                SELECT f.*, s.index_number, s.user_id FROM auth_flows f JOIN students s ON s.id = f.student_id
                WHERE f.token_hash = :h AND f.kind = :k AND f.completed_at IS NULL AND f.expires_at > now()""",
                "h", Crypto.sha256(token), "k", kind);
        if (f == null) throw ApiException.badRequest("FLOW_EXPIRED", "Your session for this step has expired. Please start again.");
        if (stages.length > 0) {
            boolean ok = false;
            for (String s : stages) if (s.equals(f.get("stage"))) ok = true;
            if (!ok) throw ApiException.badRequest("FLOW_STEP", "Please complete the previous step first.");
        }
        return f;
    }

    public void stage(UUID flowId, String stage) {
        db.update("UPDATE auth_flows SET stage = :s WHERE id = :id", "s", stage, "id", flowId);
    }

    public void complete(UUID flowId, HttpServletResponse res) {
        db.update("UPDATE auth_flows SET completed_at = now() WHERE id = :id", "id", flowId);
        cookies.clear(res, Cookies.FLOW);
    }
}
