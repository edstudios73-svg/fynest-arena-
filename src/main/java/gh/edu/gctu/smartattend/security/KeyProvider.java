package gh.edu.gctu.smartattend.security;

import gh.edu.gctu.smartattend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Properties;

/**
 * Supplies the server-side secret keys. In production both keys MUST come from environment
 * variables / a secret manager. For local development only, a key file is generated outside the
 * source tree (./data is git-ignored) so that data survives restarts.
 */
@Component
public class KeyProvider {
    private static final Logger log = LoggerFactory.getLogger(KeyProvider.class);
    private final byte[] biometricKey;
    private final byte[] hmacKey;
    private final String biometricKeyId;

    public KeyProvider(AppProperties props) {
        byte[] bio = decode(props.biometricKey());
        byte[] mac = decode(props.hmacKey());
        if (bio == null || mac == null) {
            if (!props.allowDevKeys()) {
                throw new IllegalStateException("SMARTATTEND_BIOMETRIC_KEY and SMARTATTEND_HMAC_KEY must be set (32 random bytes, base64).");
            }
            Properties p = loadOrCreateDevKeys(Path.of(props.devKeyFile()));
            if (bio == null) bio = Base64.getDecoder().decode(p.getProperty("biometric"));
            if (mac == null) mac = Base64.getDecoder().decode(p.getProperty("hmac"));
            log.warn("Using DEVELOPMENT keys from {}. Configure SMARTATTEND_BIOMETRIC_KEY and SMARTATTEND_HMAC_KEY for production.",
                    props.devKeyFile());
        }
        if (bio.length != 32 || mac.length != 32) throw new IllegalStateException("Keys must be exactly 32 bytes");
        this.biometricKey = bio;
        this.hmacKey = mac;
        this.biometricKeyId = Crypto.b64url(Crypto.sha256(bio)).substring(0, 12);
    }

    public byte[] biometricKey() { return biometricKey; }
    public String biometricKeyId() { return biometricKeyId; }
    public byte[] hmacKey() { return hmacKey; }

    private static byte[] decode(String s) {
        if (s == null || s.isBlank()) return null;
        return Base64.getDecoder().decode(s.trim());
    }

    private static Properties loadOrCreateDevKeys(Path file) {
        try {
            Properties p = new Properties();
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) { p.load(r); }
                return p;
            }
            Files.createDirectories(file.toAbsolutePath().getParent());
            p.setProperty("biometric", Base64.getEncoder().encodeToString(Crypto.randomBytes(32)));
            p.setProperty("hmac", Base64.getEncoder().encodeToString(Crypto.randomBytes(32)));
            try (Writer w = Files.newBufferedWriter(file)) { p.store(w, "SmartAttend DEVELOPMENT keys - never use in production"); }
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) {}
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create development key file", e);
        }
    }
}
