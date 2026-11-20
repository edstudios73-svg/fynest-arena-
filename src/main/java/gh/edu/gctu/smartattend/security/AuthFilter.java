package gh.edu.gctu.smartattend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import gh.edu.gctu.smartattend.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Central server-side enforcement of authentication, role-based access, CSRF and origin checks.
 * Hiding buttons in the UI is never relied upon: every /api route is checked here, and services
 * additionally scope data to the caller (e.g. lecturers only see assigned courses).
 */
@Component
@Order(10)
public class AuthFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final SessionService sessions;
    private final AppProperties props;
    private final ObjectMapper om;

    public AuthFilter(SessionService sessions, AppProperties props, ObjectMapper om) {
        this.sessions = sessions;
        this.props = props;
        this.om = om;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            String path = req.getRequestURI();
            boolean unsafe = !SAFE.contains(req.getMethod());
            res.setHeader("Cache-Control", "no-store");

            // Cross-site request defence for every state-changing request.
            if (unsafe && !originAllowed(req)) {
                deny(res, 403, "BAD_ORIGIN", "Request origin not allowed.");
                return;
            }

            CurrentUser user = sessions.resolve(Cookies.read(req, Cookies.SESSION));
            AuthContext.set(user, req.getRemoteAddr());

            if (path.startsWith("/api/auth/") || path.startsWith("/api/public/")) {
                chain.doFilter(req, res);
                return;
            }
            if (user == null) {
                deny(res, 401, "UNAUTHENTICATED", "Your session has expired. Please sign in again.");
                return;
            }
            if (unsafe && !csrfOk(req, user)) {
                deny(res, 403, "CSRF", "Security token missing or invalid. Please reload the page.");
                return;
            }
            if (user.mustChangePassword() && !path.startsWith("/api/me")) {
                deny(res, 403, "PASSWORD_CHANGE_REQUIRED", "You must change your temporary password first.");
                return;
            }
            Role r = user.role();
            boolean ok;
            if (path.startsWith("/api/student/")) ok = r == Role.STUDENT;
            else if (r == Role.ADMIN && (path.startsWith("/api/lecturer/") || path.startsWith("/api/admin/"))) ok = courseRepAllowed(req.getMethod(), path);
            else if (path.startsWith("/api/lecturer/")) ok = r == Role.LECTURER || r.isAdmin();
            else if (path.startsWith("/api/admin/")) ok = r.isAdmin();
            else if (path.startsWith("/api/super/")) ok = r == Role.SUPER_ADMIN;
            else ok = path.startsWith("/api/me") || path.startsWith("/api/notifications") || path.startsWith("/api/face/");
            if (!ok) {
                deny(res, 403, "FORBIDDEN", "You do not have permission to access this resource.");
                return;
            }
            chain.doFilter(req, res);
        } finally {
            AuthContext.clear();
        }
    }

    private static final String UUID_RE = "[0-9a-fA-F-]{36}";
    /** Course reps (role ADMIN): see and edit courses, turn attendance on/off, show the board and see who checked in. Nothing else. */
    private static final List<String[]> COURSE_REP = List.of(
            new String[] {"GET", "/api/admin/courses"},
            new String[] {"PUT", "/api/admin/courses/" + UUID_RE},
            new String[] {"POST", "/api/lecturer/sessions"},
            new String[] {"GET", "/api/lecturer/sessions/" + UUID_RE},
            new String[] {"GET", "/api/lecturer/sessions/" + UUID_RE + "/challenge"},
            new String[] {"GET", "/api/lecturer/sessions/" + UUID_RE + "/stream"},
            new String[] {"POST", "/api/lecturer/sessions/" + UUID_RE + "/anchor"},
            new String[] {"POST", "/api/lecturer/sessions/" + UUID_RE + "/close"});

    static boolean courseRepAllowed(String method, String path) {
        for (String[] a : COURSE_REP) if (a[0].equalsIgnoreCase(method) && path.matches(a[1])) return true;
        return false;
    }

    private boolean originAllowed(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null) {
            String ref = req.getHeader("Referer");
            if (ref == null) return true; // non-browser client: cannot carry a victim's cookies cross-site
            for (String o : props.allowedOrigins()) if (ref.startsWith(o + "/") || ref.equals(o)) return true;
            return false;
        }
        return props.allowedOrigins().contains(origin);
    }

    private boolean csrfOk(HttpServletRequest req, CurrentUser user) {
        String h = req.getHeader("X-CSRF-Token");
        return h != null && MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8), user.csrfToken().getBytes(StandardCharsets.UTF_8));
    }

    private void deny(HttpServletResponse res, int status, String code, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        om.writeValue(res.getOutputStream(), Map.of("error", Map.of("code", code, "message", msg)));
    }
}
