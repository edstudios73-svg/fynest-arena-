package gh.edu.gctu.smartattend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A browser-like test client: keeps cookies between requests and sends the CSRF header. */
public class Client {
    private static final ObjectMapper OM = new ObjectMapper();
    private final MockMvc mvc;
    private final Map<String, Cookie> jar = new LinkedHashMap<>();
    public String csrf;
    public String ip = "10.20.30.40";
    public String origin = null;

    public Client(MockMvc mvc) { this.mvc = mvc; }

    public record Resp(int status, JsonNode json, byte[] body, MockHttpServletResponse raw) {
        public String code() { return json == null ? null : json.path("error").path("code").asText(null); }
        public String text() { return new String(body, java.nio.charset.StandardCharsets.UTF_8); }
    }

    public Resp get(String path) { return exec(MockMvcRequestBuilders.get(path)); }

    public Resp post(String path, Object body) {
        try {
            String json = body == null ? "{}" : body instanceof String s ? s : OM.writeValueAsString(body);
            return exec(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public Resp put(String path, Object body) {
        try {
            return exec(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(OM.writeValueAsString(body)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public Resp multipart(String path, Map<String, String> params, List<MockMultipartFile> files) {
        MockMultipartHttpServletRequestBuilder b = MockMvcRequestBuilders.multipart(path);
        for (var f : files) b.file(f);
        params.forEach(b::param);
        return exec(b);
    }

    private Resp exec(MockHttpServletRequestBuilder b) {
        try {
            if (!jar.isEmpty()) b.cookie(jar.values().toArray(new Cookie[0]));
            if (csrf != null) b.header("X-CSRF-Token", csrf);
            if (origin != null) b.header("Origin", origin);
            final String addr = ip;
            b.with(r -> { r.setRemoteAddr(addr); return r; });
            MockHttpServletResponse res = mvc.perform(b).andReturn().getResponse();
            for (Cookie c : res.getCookies()) {
                if (c.getMaxAge() == 0) jar.remove(c.getName());
                else jar.put(c.getName(), c);
            }
            byte[] body = res.getContentAsByteArray();
            JsonNode json = null;
            String ct = res.getContentType();
            if (ct != null && ct.contains("json") && body.length > 0) json = OM.readTree(body);
            return new Resp(res.getStatus(), json, body, res);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Load the CSRF token for the current session. */
    public Client refresh() {
        Resp r = get("/api/me");
        if (r.status() == 200) csrf = r.json().path("csrfToken").asText();
        return this;
    }

    public boolean hasCookie(String name) { return jar.containsKey(name); }
    public void dropCookie(String name) { jar.remove(name); }
    public void copyCookieFrom(Client other, String name) { if (other.jar.containsKey(name)) jar.put(name, other.jar.get(name)); }
}
