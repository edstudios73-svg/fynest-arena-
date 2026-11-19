package gh.edu.gctu.smartattend.security;

import gh.edu.gctu.smartattend.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class Cookies {
    public static final String SESSION = "sa_session";
    public static final String FLOW = "sa_flow";
    public static final String DEVICE = "sa_dev";

    private final AppProperties props;

    public Cookies(AppProperties props) { this.props = props; }

    public static String read(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }

    public void set(HttpServletResponse res, String name, String value, Duration maxAge, String sameSite) {
        ResponseCookie c = ResponseCookie.from(name, value)
                .httpOnly(true).secure(props.cookieSecure()).sameSite(sameSite).path("/").maxAge(maxAge).build();
        res.addHeader("Set-Cookie", c.toString());
    }

    public void clear(HttpServletResponse res, String name) {
        set(res, name, "", Duration.ZERO, "Lax");
    }
}
