package gh.edu.gctu.smartattend.webauthn;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.*;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import gh.edu.gctu.smartattend.common.ApiException;
import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.common.Json;
import gh.edu.gctu.smartattend.config.AppProperties;
import gh.edu.gctu.smartattend.security.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * WebAuthn / passkeys. The device's fingerprint / Face ID / Windows Hello data never leaves the
 * device: the authenticator only returns a signature proving user verification happened on a
 * credential that was registered to this account. We require user verification (UV) on every ceremony.
 */
@Service
public class WebAuthnService {
    private static final Logger log = LoggerFactory.getLogger(WebAuthnService.class);
    private final WebAuthnManager manager = WebAuthnManager.createNonStrictWebAuthnManager();
    private final AttestedCredentialDataConverter acdConverter = new AttestedCredentialDataConverter(new ObjectConverter());
    private final List<PublicKeyCredentialParameters> pubKeyParams = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.EdDSA));
    private final Db db;
    private final AppProperties props;

    public WebAuthnService(Db db, AppProperties props) {
        this.db = db;
        this.props = props;
    }

    public Map<String, Object> creationOptions(UUID userId, byte[] challenge, String userName, String displayName) {
        byte[] handle = db.scalar("SELECT webauthn_user_handle FROM app_users WHERE id = :u", byte[].class, "u", userId);
        List<Map<String, Object>> exclude = activeCredentialIds(userId).stream()
                .map(id -> Json.obj("type", "public-key", "id", Crypto.b64url(id))).collect(Collectors.toList());
        return Json.obj(
                "rp", Json.obj("id", props.rpId(), "name", props.rpName()),
                "user", Json.obj("id", Crypto.b64url(handle), "name", userName, "displayName", displayName),
                "challenge", Crypto.b64url(challenge),
                "pubKeyCredParams", List.of(Json.obj("type", "public-key", "alg", -7), Json.obj("type", "public-key", "alg", -257),
                        Json.obj("type", "public-key", "alg", -8)),
                "authenticatorSelection", Json.obj("authenticatorAttachment", "platform", "residentKey", "preferred",
                        "userVerification", "required"),
                "attestation", "none",
                "timeout", 120000,
                "excludeCredentials", exclude);
    }

    public Map<String, Object> requestOptions(UUID userId, byte[] challenge) {
        List<Map<String, Object>> allow = new ArrayList<>();
        for (var r : db.list("SELECT credential_id, transports FROM webauthn_credentials WHERE user_id = :u AND revoked_at IS NULL", "u", userId)) {
            Map<String, Object> c = Json.obj("type", "public-key", "id", Crypto.b64url((byte[]) r.get("credentialId")));
            String t = (String) r.get("transports");
            if (t != null && !t.isBlank()) c.put("transports", List.of(t.split(",")));
            allow.add(c);
        }
        if (allow.isEmpty()) throw ApiException.conflict("NO_PASSKEY", "No passkey is registered for this account on any device. Use account recovery.");
        return Json.obj("challenge", Crypto.b64url(challenge), "rpId", props.rpId(), "allowCredentials", allow,
                "userVerification", "required", "timeout", 120000);
    }

    /** Verify a registration response and store the credential. Returns the credential row id. */
    public UUID register(UUID userId, UUID deviceId, byte[] challenge, String responseJson, String label) {
        RegistrationData data;
        try {
            data = manager.verifyRegistrationResponseJSON(responseJson,
                    new RegistrationParameters(serverProperty(challenge), pubKeyParams, true, true));
        } catch (RuntimeException e) {
            log.info("Passkey registration rejected: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSKEY_REGISTRATION_FAILED",
                    "Your device could not create a passkey. Make sure screen lock / biometrics are enabled and try again.");
        }
        AuthenticatorData<?> ad = Objects.requireNonNull(data.getAttestationObject()).getAuthenticatorData();
        AttestedCredentialData acd = Objects.requireNonNull(ad.getAttestedCredentialData());
        if (!ad.isFlagUV()) throw ApiException.badRequest("UV_REQUIRED", "Device biometric / screen-lock verification is required.");
        String transports = data.getTransports() == null ? null :
                data.getTransports().stream().map(AuthenticatorTransport::getValue).collect(Collectors.joining(","));
        try {
            return db.insertReturningId("""
                    INSERT INTO webauthn_credentials(user_id, device_id, credential_id, attested_credential_data, sign_count,
                        uv_initialized, backup_eligible, backup_state, transports, label)
                    VALUES (:u, :d, :cid, :acd, :sc, :uv, :be, :bs, :tr, :label) RETURNING id""",
                    "u", userId, "d", deviceId, "cid", acd.getCredentialId(), "acd", acdConverter.convert(acd),
                    "sc", ad.getSignCount(), "uv", ad.isFlagUV(), "be", ad.isFlagBE(), "bs", ad.isFlagBS(),
                    "tr", transports, "label", label);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("PASSKEY_EXISTS", "This passkey is already registered.");
        }
    }

    /** Verify an assertion for the expected user. Returns the credential row id. */
    public UUID authenticate(UUID expectedUserId, byte[] challenge, String responseJson) {
        AuthenticationData parsed;
        try {
            parsed = manager.parseAuthenticationResponseJSON(responseJson);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("PASSKEY_INVALID", "Passkey response was not valid.");
        }
        Map<String, Object> row = db.one("""
                SELECT id, user_id, attested_credential_data, sign_count, uv_initialized, backup_eligible, backup_state, transports
                FROM webauthn_credentials WHERE credential_id = :cid AND revoked_at IS NULL""", "cid", parsed.getCredentialId());
        if (row == null || !expectedUserId.equals(row.get("userId"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "DEVICE_NOT_RECOGNISED",
                    "This device's passkey is not registered to your account. Use account recovery if you changed phones.");
        }
        AttestedCredentialData acd = acdConverter.convert((byte[]) row.get("attestedCredentialData"));
        Set<AuthenticatorTransport> tr = new HashSet<>();
        if (row.get("transports") != null) for (String t : ((String) row.get("transports")).split(",")) if (!t.isBlank()) tr.add(AuthenticatorTransport.create(t));
        CredentialRecordImpl record = new CredentialRecordImpl(null, (Boolean) row.get("uvInitialized"),
                (Boolean) row.get("backupEligible"), (Boolean) row.get("backupState"), ((Number) row.get("signCount")).longValue(),
                acd, null, null, null, tr);
        try {
            manager.verify(parsed, new AuthenticationParameters(serverProperty(challenge), record,
                    List.of(parsed.getCredentialId()), true, true));
        } catch (RuntimeException e) {
            log.info("Passkey assertion rejected: {}", e.getMessage());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PASSKEY_FAILED", "Passkey verification failed. Please try again.");
        }
        long newCount = Objects.requireNonNull(parsed.getAuthenticatorData()).getSignCount();
        db.update("UPDATE webauthn_credentials SET sign_count = :c, last_used_at = now() WHERE id = :id",
                "c", newCount, "id", row.get("id"));
        return (UUID) row.get("id");
    }

    public List<byte[]> activeCredentialIds(UUID userId) {
        return db.jdbc().queryForList("SELECT credential_id FROM webauthn_credentials WHERE user_id = :u AND revoked_at IS NULL",
                Db.params("u", userId), byte[].class);
    }

    private ServerProperty serverProperty(byte[] challenge) {
        Set<Origin> origins = props.allowedOrigins().stream().map(Origin::new).collect(Collectors.toSet());
        return new ServerProperty(origins, props.rpId(), new DefaultChallenge(challenge), null);
    }
}
