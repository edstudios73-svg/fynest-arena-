package gh.edu.gctu.smartattend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private final AppProperties props;

    public SecurityHeadersFilter(AppProperties props) { this.props = props; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // No inline scripts are used anywhere, so a strict script policy is possible (XSS mitigation).
        res.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; " +
                "media-src 'self' blob:; connect-src 'self'; font-src 'self'; object-src 'none'; " +
                "base-uri 'self'; form-action 'self'; frame-ancestors 'none'; manifest-src 'self'; worker-src 'self'");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "same-origin");
        res.setHeader("Permissions-Policy", "camera=(self), geolocation=(self), microphone=(), publickey-credentials-get=(self), publickey-credentials-create=(self)");
        res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        if (props.cookieSecure()) res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        chain.doFilter(req, res);
    }
}
