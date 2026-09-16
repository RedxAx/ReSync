package restudio.resync.migration;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.identity.ServerId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ProductionAuthorityTrustAnchor {
    public static final int FORMAT_VERSION = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final String KIND = "resync-authority-trust-anchor";
    public static final String SCHEMA = "resync.authority-trust-anchor.v1";
    public static final String HASH_DOMAIN = "resync.authority-trust-anchor";
    public static final String SIGNATURE_ALGORITHM = ProductionAuthorityBundle.SIGNATURE_ALGORITHM;
    private static final Set<String> KNOWN_FIELDS = Set.of(
        "kind", "formatVersion", "schema", "schemaVersion", "serverId", "installationId",
        "installAuthorityHash", "authorityKeyId", "publicKeyFingerprint", "publicKey");

    private final ServerId serverId;
    private final String installationId;
    private final String installAuthorityHash;
    private final String authorityKeyId;
    private final String publicKeyFingerprint;
    private final String publicKey;

    public ProductionAuthorityTrustAnchor(
        ServerId serverId,
        String installationId,
        String installAuthorityHash,
        String authorityKeyId,
        String publicKeyFingerprint,
        String publicKey
    ) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.installationId = requireIdentifier(installationId, "installationId");
        this.installAuthorityHash = MigrationCanonical.requireDigest(installAuthorityHash, "installAuthorityHash");
        this.authorityKeyId = requireIdentifier(authorityKeyId, "authorityKeyId");
        this.publicKey = requireBase64(publicKey, "publicKey");
        this.publicKeyFingerprint = MigrationCanonical.requireDigest(publicKeyFingerprint, "publicKeyFingerprint");
        if (!publicKeyFingerprint.equals(fingerprint(publicKey))) {
            throw new IllegalArgumentException("Public Key Fingerprint Does Not Match Public Key");
        }
        parsePublicKey(publicKey);
    }

    public static ProductionAuthorityTrustAnchor pinned(
        ServerId serverId,
        String installationId,
        String installAuthorityHash,
        String authorityKeyId,
        String publicKey
    ) {
        String checkedPublicKey = requireBase64(publicKey, "publicKey");
        return new ProductionAuthorityTrustAnchor(serverId, installationId, installAuthorityHash, authorityKeyId,
            fingerprint(checkedPublicKey), checkedPublicKey);
    }

    public static ProductionAuthorityTrustAnchor of(
        ServerId serverId,
        String installationId,
        String installAuthorityHash,
        String authorityKeyId,
        String publicKey
    ) {
        return pinned(serverId, installationId, installAuthorityHash, authorityKeyId, publicKey);
    }

    public static ProductionAuthorityTrustAnchor fromCanonical(String text) {
        Objects.requireNonNull(text, "Trust Anchor Canonical Text Is Required");
        JsonValue.JsonObject object = CanonicalCodec.requireObject(CanonicalCodec.decode(text));
        Map<String, Object> value = object.toJava() instanceof Map<?, ?> map
            ? toStringMap(map)
            : throwInvalid("Trust Anchor Must Be A JSON Object");
        if (!value.keySet().equals(KNOWN_FIELDS)) {
            throw new IllegalArgumentException("Trust Anchor Fields Are Not Canonical");
        }
        if (!KIND.equals(text(value, "kind")) || number(value, "formatVersion") != FORMAT_VERSION
            || !SCHEMA.equals(text(value, "schema")) || number(value, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Trust Anchor Schema Is Unsupported");
        }
        ProductionAuthorityTrustAnchor anchor = new ProductionAuthorityTrustAnchor(
            ServerId.parseCanonicalText(text(value, "serverId")), text(value, "installationId"),
            text(value, "installAuthorityHash"), text(value, "authorityKeyId"),
            text(value, "publicKeyFingerprint"), text(value, "publicKey"));
        if (!anchor.canonical().equals(text)) {
            throw new IllegalArgumentException("Trust Anchor Is Not Canonical");
        }
        return anchor;
    }

    public ServerId serverId() {
        return serverId;
    }

    public String installationId() {
        return installationId;
    }

    public String installAuthorityHash() {
        return installAuthorityHash;
    }

    public String authorityKeyId() {
        return authorityKeyId;
    }

    public String publicKeyFingerprint() {
        return publicKeyFingerprint;
    }

    public String publicKey() {
        return publicKey;
    }

    public boolean matchesDurableInstall(Path dataRoot) throws IOException {
        return installAuthorityHash.equals(ProductionAuthorityBundle.installAuthorityDigest(dataRoot));
    }

    public boolean matches(ProductionAuthorityBundle bundle) {
        ProductionAuthorityBundle checked = bundle;
        return checked != null
            && serverId.equals(checked.serverId())
            && installationId.equals(checked.installationId())
            && installAuthorityHash.equals(checked.installAuthorityHash())
            && authorityKeyId.equals(checked.authorityKeyId())
            && publicKeyFingerprint.equals(checked.publicKeyFingerprint())
            && publicKey.equals(checked.signingPublicKey());
    }

    boolean verify(byte[] payload, String signature) {
        Objects.requireNonNull(payload, "payload");
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(parsePublicKey(publicKey));
            verifier.update(payload);
            return verifier.verify(Base64.getDecoder().decode(requireBase64(signature, "signature")));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    public String canonical() {
        return CanonicalCodec.encodeText(JsonValue.fromJava(canonicalValue()));
    }

    public byte[] canonicalBytes() {
        return CanonicalCodec.encode(JsonValue.fromJava(canonicalValue()));
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", KIND);
        value.put("formatVersion", FORMAT_VERSION);
        value.put("schema", SCHEMA);
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("serverId", serverId.canonicalText());
        value.put("installationId", installationId);
        value.put("installAuthorityHash", installAuthorityHash);
        value.put("authorityKeyId", authorityKeyId);
        value.put("publicKeyFingerprint", publicKeyFingerprint);
        value.put("publicKey", publicKey);
        return Map.copyOf(value);
    }

    public static String fingerprint(String publicKey) {
        String checked = requireBase64(publicKey, "publicKey");
        return CanonicalHash.rawSha256(Base64.getDecoder().decode(checked));
    }

    public static String fingerprint(PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey");
        return CanonicalHash.rawSha256(publicKey.getEncoded());
    }

    private static PublicKey parsePublicKey(String value) {
        try {
            return KeyFactory.getInstance(SIGNATURE_ALGORITHM).generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(requireBase64(value, "publicKey"))));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Public Key Is Invalid", exception);
        }
    }

    private static String requireBase64(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " Must Not Be Blank");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (!Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw new IllegalArgumentException(field + " Is Not Canonical Base64");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " Is Not Canonical Base64", exception);
        }
    }

    private static String requireIdentifier(String value, String field) {
        String checked = MigrationCanonical.requireText(value, field);
        if (checked.length() > 256 || !StandardCharsets.UTF_8.newEncoder().canEncode(checked)) {
            throw new IllegalArgumentException(field + " Is Not A Canonical Identifier");
        }
        return checked;
    }

    private static Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, entry) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("Trust Anchor Object Keys Must Be Text");
            }
            value.put(text, entry);
        });
        return value;
    }

    private static String text(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Trust Anchor Field Must Be Text: " + field);
        }
        return text;
    }

    private static int number(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("Trust Anchor Field Must Be A Number: " + field);
        }
        try {
            return new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Trust Anchor Number Must Be An Integer: " + field, exception);
        }
    }

    private static <T> T throwInvalid(String message) {
        throw new IllegalArgumentException(message);
    }
}
