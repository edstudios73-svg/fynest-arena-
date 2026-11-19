package gh.edu.gctu.smartattend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "smartattend")
public record AppProperties(
        String baseUrl,
        String rpId,
        String rpName,
        String extraOrigins,
        String biometricKey,
        String hmacKey,
        String devKeyFile,
        boolean allowDevKeys,
        boolean cookieSecure,
        int sessionHours,
        String modelsDir,
        String faceProvider,
        String bootstrapSuperAdminEmail,
        String bootstrapSuperAdminPassword) {

    /** Origins accepted for WebAuthn client data and for state-changing requests. */
    public Set<String> allowedOrigins() {
        Set<String> s = new LinkedHashSet<>();
        s.add(stripSlash(baseUrl));
        if (extraOrigins != null && !extraOrigins.isBlank()) {
            Arrays.stream(extraOrigins.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                    .map(AppProperties::stripSlash).forEach(s::add);
        }
        return s;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
