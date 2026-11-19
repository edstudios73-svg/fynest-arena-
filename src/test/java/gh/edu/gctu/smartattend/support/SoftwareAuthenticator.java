package gh.edu.gctu.smartattend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.*;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A real, spec-compliant software passkey authenticator for tests: it generates a P-256 key pair,
 * produces "none" attestation on registration and ECDSA-signed assertions. The server verifies
 * these with the same code path used for phones (no mocking of WebAuthn verification).
 */
public class SoftwareAuthenticator {
    private static final ObjectMapper OM = new ObjectMapper();
    private final ObjectConverter oc = new ObjectConverter();
    private final String origin;
    private final String rpId;
    private KeyPair keyPair;
    private byte[] credentialId;
    private long counter = 0;
    public boolean userVerified = true;

    public SoftwareAuthenticator(String origin, String rpId) {
        this.origin = origin;
        this.rpId = rpId;
    }

    public byte[] credentialId() { return credentialId; }

    public String create(JsonNode options) throws Exception {
        String challenge = options.path("challenge").asText();
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = g.generateKeyPair();
        credentialId = new byte[32];
        new SecureRandom().nextBytes(credentialId);
        EC2COSEKey cose = EC2COSEKey.create((ECPublicKey) keyPair.getPublic(), COSEAlgorithmIdentifier.ES256);
        AttestedCredentialData acd = new AttestedCredentialData(AAGUID.ZERO, credentialId, cose);
        byte flags = (byte) (AuthenticatorData.BIT_UP | (userVerified ? AuthenticatorData.BIT_UV : 0) | AuthenticatorData.BIT_AT);
        AuthenticatorData<?> ad = new AuthenticatorData<>(rpIdHash(), flags, counter, acd);
        @SuppressWarnings({"unchecked", "rawtypes"})
        AttestationObject ao = new AttestationObject((AuthenticatorData) ad, new NoneAttestationStatement());
        byte[] aoBytes = new AttestationObjectConverter(oc).convertToBytes(ao);
        byte[] cd = clientData("webauthn.create", challenge);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("clientDataJSON", b64(cd));
        resp.put("attestationObject", b64(aoBytes));
        resp.put("transports", new String[]{"internal"});
        return credentialJson(resp);
    }

    public String get(JsonNode options) throws Exception {
        String challenge = options.path("challenge").asText();
        counter++;
        byte flags = (byte) (AuthenticatorData.BIT_UP | (userVerified ? AuthenticatorData.BIT_UV : 0));
        AuthenticatorData<?> ad = new AuthenticatorData<>(rpIdHash(), flags, counter);
        byte[] adBytes = new AuthenticatorDataConverter(oc).convert(ad);
        byte[] cd = clientData("webauthn.get", challenge);
        byte[] cdHash = MessageDigest.getInstance("SHA-256").digest(cd);
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(ByteBuffer.allocate(adBytes.length + cdHash.length).put(adBytes).put(cdHash).array());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("clientDataJSON", b64(cd));
        resp.put("authenticatorData", b64(adBytes));
        resp.put("signature", b64(sig.sign()));
        resp.put("userHandle", null);
        return credentialJson(resp);
    }

    private String credentialJson(Map<String, Object> response) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b64(credentialId));
        m.put("rawId", b64(credentialId));
        m.put("type", "public-key");
        m.put("response", response);
        m.put("clientExtensionResults", Map.of());
        m.put("authenticatorAttachment", "platform");
        return OM.writeValueAsString(m);
    }

    private byte[] clientData(String type, String challenge) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("challenge", challenge);
        m.put("origin", origin);
        m.put("crossOrigin", false);
        return OM.writeValueAsString(m).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] rpIdHash() throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8));
    }

    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
