package restudio.resync.migration;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthorityUseGrant {
    public static final int FORMAT_VERSION = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final String KIND = "resync-authority-use-grant";
    public static final String SCHEMA = "resync.authority-use-grant.v1";
    public static final String HASH_DOMAIN = "resync.authority-use-grant";
    public static final String SOURCE_IDENTITY_HASH_DOMAIN = "resync.authority-use-grant.source-identity";
    public static final String SEMANTICS = "Offline grants are exact path, migration, and invocation bindings with local replay detection; they are not cross-machine rollback-proof.";
    private static final int MAX_BYTES = 128 * 1024;
    private static final Set<String> KNOWN_FIELDS = Set.of(
        "kind", "formatVersion", "schema", "schemaVersion", "bundleHash", "serverId", "installationId",
        "installAuthorityHash", "snapshotId", "canonicalSourcePath", "sourceIdentityHash", "migrationId",
        "invocationHash", "planPreimageHash", "issuedAt", "expiresAt", "grantId", "authorityKeyId",
        "publicKeyFingerprint", "signature", "grantHash");

    private final String bundleHash;
    private final ServerId serverId;
    private final String installationId;
    private final String installAuthorityHash;
    private final SnapshotId snapshotId;
    private final String canonicalSourcePath;
    private final String sourceIdentityHash;
    private final String migrationId;
    private final String invocationHash;
    private final String planPreimageHash;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final String grantId;
    private final String authorityKeyId;
    private final String publicKeyFingerprint;
    private final String signature;
    private final String grantHash;

    private AuthorityUseGrant(
        String bundleHash,
        ServerId serverId,
        String installationId,
        String installAuthorityHash,
        SnapshotId snapshotId,
        String canonicalSourcePath,
        String sourceIdentityHash,
        String migrationId,
        String invocationHash,
        String planPreimageHash,
        Instant issuedAt,
        Instant expiresAt,
        String grantId,
        String authorityKeyId,
        String publicKeyFingerprint,
        String signature,
        String grantHash
    ) {
        this.bundleHash = MigrationCanonical.requireDigest(bundleHash, "bundleHash");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.installationId = requireIdentifier(installationId, "installationId");
        this.installAuthorityHash = MigrationCanonical.requireDigest(installAuthorityHash, "installAuthorityHash");
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        this.canonicalSourcePath = requireCanonicalPath(canonicalSourcePath);
        this.sourceIdentityHash = MigrationCanonical.requireDigest(sourceIdentityHash, "sourceIdentityHash");
        this.migrationId = MigrationCanonical.requireText(migrationId, "migrationId");
        this.invocationHash = MigrationCanonical.requireDigest(invocationHash, "invocationHash");
        this.planPreimageHash = MigrationCanonical.requireDigest(planPreimageHash, "planPreimageHash");
        this.issuedAt = requireInstant(issuedAt, "issuedAt");
        this.expiresAt = requireInstant(expiresAt, "expiresAt");
        if (expiresAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("Grant Expiry Must Not Precede Issuance");
        }
        this.grantId = requireIdentifier(grantId, "grantId");
        this.authorityKeyId = requireIdentifier(authorityKeyId, "authorityKeyId");
        this.publicKeyFingerprint = MigrationCanonical.requireDigest(publicKeyFingerprint, "publicKeyFingerprint");
        this.signature = requireBase64(signature, "signature");
        this.grantHash = MigrationCanonical.requireDigest(grantHash, "grantHash");
    }

    public static AuthorityUseGrant issue(
        ProductionAuthorityBundle bundle,
        ProductionAuthorityTrustAnchor trustAnchor,
        ProductionAuthoritySigner signer,
        Path sourceRoot,
        String migrationId,
        String invocationHash,
        String planPreimageHash,
        Instant issuedAt,
        Instant expiresAt,
        String grantId
    ) throws IOException {
        String canonicalSourcePath = canonicalSourcePath(sourceRoot);
        String sourceIdentityHash = sourceIdentityDigest(sourceRoot, bundle);
        return issue(bundle, trustAnchor, signer, canonicalSourcePath, sourceIdentityHash, migrationId,
            invocationHash, planPreimageHash, issuedAt, expiresAt, grantId);
    }

    public static AuthorityUseGrant issue(
        ProductionAuthorityBundle bundle,
        ProductionAuthorityTrustAnchor trustAnchor,
        ProductionAuthoritySigner signer,
        String canonicalSourcePath,
        String sourceIdentityHash,
        String migrationId,
        String invocationHash,
        String planPreimageHash,
        Instant issuedAt,
        Instant expiresAt,
        String grantId
    ) throws IOException {
        ProductionAuthorityBundle checkedBundle = Objects.requireNonNull(bundle, "bundle");
        ProductionAuthorityTrustAnchor anchor = Objects.requireNonNull(trustAnchor, "trustAnchor");
        ProductionAuthoritySigner checkedSigner = Objects.requireNonNull(signer, "signer");
        if (!anchor.matches(checkedBundle) || !checkedBundle.verifySignature(anchor)) {
            throw new MigrationException("Authority Bundle Is Not Bound To The Pinned Trust Anchor");
        }
        String signerKey = requireBase64(checkedSigner.signingPublicKey(), "signingPublicKey");
        if (!checkedSigner.serverId().equals(checkedBundle.serverId())
            || !MigrationCanonical.requireDigest(checkedSigner.installAuthorityHash(), "installAuthorityHash")
                .equals(checkedBundle.installAuthorityHash())
            || !signerKey.equals(checkedBundle.signingPublicKey())) {
            throw new MigrationException("Grant Signer Does Not Match Authority Bundle");
        }
        Map<String, Object> unsigned = unsignedValue(checkedBundle.bundleHash(), checkedBundle.serverId(),
            checkedBundle.installationId(), checkedBundle.installAuthorityHash(), checkedBundle.snapshotId(),
            canonicalSourcePath, sourceIdentityHash, migrationId, invocationHash, planPreimageHash, issuedAt,
            expiresAt, grantId, checkedBundle.authorityKeyId(), checkedBundle.publicKeyFingerprint());
        String signature = requireBase64(checkedSigner.sign(JsonValue.fromJava(unsigned).canonicalBytes()), "signature");
        return create(checkedBundle.bundleHash(), checkedBundle.serverId(), checkedBundle.installationId(),
            checkedBundle.installAuthorityHash(), checkedBundle.snapshotId(), canonicalSourcePath, sourceIdentityHash,
            migrationId, invocationHash, planPreimageHash, issuedAt, expiresAt, grantId,
            checkedBundle.authorityKeyId(), checkedBundle.publicKeyFingerprint(), signature);
    }

    public static AuthorityUseGrant create(
        String bundleHash,
        ServerId serverId,
        String installationId,
        String installAuthorityHash,
        SnapshotId snapshotId,
        String canonicalSourcePath,
        String sourceIdentityHash,
        String migrationId,
        String invocationHash,
        String planPreimageHash,
        Instant issuedAt,
        Instant expiresAt,
        String grantId,
        String authorityKeyId,
        String publicKeyFingerprint,
        String signature
    ) {
        Map<String, Object> unsigned = unsignedValue(bundleHash, serverId, installationId, installAuthorityHash,
            snapshotId, canonicalSourcePath, sourceIdentityHash, migrationId, invocationHash, planPreimageHash,
            issuedAt, expiresAt, grantId, authorityKeyId, publicKeyFingerprint);
        String checkedSignature = requireBase64(signature, "signature");
        Map<String, Object> hashed = new LinkedHashMap<>(unsigned);
        hashed.put("signature", checkedSignature);
        String grantHash = CanonicalHash.sha256(HASH_DOMAIN, JsonValue.fromJava(hashed).canonicalBytes());
        return new AuthorityUseGrant(bundleHash, serverId, installationId, installAuthorityHash, snapshotId,
            canonicalSourcePath, sourceIdentityHash, migrationId, invocationHash, planPreimageHash, issuedAt,
            expiresAt, grantId, authorityKeyId, publicKeyFingerprint, checkedSignature, grantHash);
    }

    public static AuthorityUseGrant fromCanonical(String text) {
        Objects.requireNonNull(text, "Grant Canonical Text Is Required");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Grant Is Too Large");
        }
        JsonValue.JsonObject object = CanonicalCodec.requireObject(CanonicalCodec.decode(text));
        Map<String, Object> value = object.toJava() instanceof Map<?, ?> map
            ? toStringMap(map)
            : throwInvalid("Grant Must Be A JSON Object");
        if (!value.keySet().equals(KNOWN_FIELDS)) {
            throw new IllegalArgumentException("Grant Fields Are Not Canonical");
        }
        if (!KIND.equals(text(value, "kind")) || number(value, "formatVersion") != FORMAT_VERSION
            || !SCHEMA.equals(text(value, "schema")) || number(value, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Grant Schema Is Unsupported");
        }
        AuthorityUseGrant expected = create(
            text(value, "bundleHash"), ServerId.parseCanonicalText(text(value, "serverId")),
            text(value, "installationId"), text(value, "installAuthorityHash"),
            SnapshotId.parseCanonicalText(text(value, "snapshotId")), text(value, "canonicalSourcePath"),
            text(value, "sourceIdentityHash"), text(value, "migrationId"), text(value, "invocationHash"),
            text(value, "planPreimageHash"), instant(value, "issuedAt"), instant(value, "expiresAt"),
            text(value, "grantId"), text(value, "authorityKeyId"), text(value, "publicKeyFingerprint"),
            text(value, "signature"));
        if (!expected.grantHash.equals(MigrationCanonical.requireDigest(text(value, "grantHash"), "grantHash"))
            || !expected.canonical().equals(text)) {
            throw new IllegalArgumentException("Grant Hash Does Not Match Content");
        }
        return expected;
    }

    public static String canonicalSourcePath(Path sourceRoot) throws IOException {
        return MigrationPaths.requireDirectory(sourceRoot, "sourceRoot").toRealPath().toString();
    }

    public static String sourceIdentityDigest(Path sourceRoot, ProductionAuthorityBundle bundle) throws IOException {
        ProductionAuthorityBundle checkedBundle = Objects.requireNonNull(bundle, "bundle");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("canonicalSourcePath", canonicalSourcePath(sourceRoot));
        value.put("serverId", checkedBundle.serverId().canonicalText());
        value.put("installationId", checkedBundle.installationId());
        value.put("snapshotId", checkedBundle.snapshotId().canonicalText());
        value.put("installAuthorityHash", checkedBundle.installAuthorityHash());
        return CanonicalHash.sha256(SOURCE_IDENTITY_HASH_DOMAIN, JsonValue.fromJava(value).canonicalBytes());
    }

    public static String planPreimageHash(String canonicalPlanPreimage) {
        return CanonicalHash.rawSha256(requireCanonicalPreimage(canonicalPlanPreimage).getBytes(StandardCharsets.UTF_8));
    }

    public static String planPreimageHash(byte[] canonicalPlanPreimage) {
        Objects.requireNonNull(canonicalPlanPreimage, "planPreimage");
        String decoded = decodeUtf8(canonicalPlanPreimage);
        requireCanonicalPreimage(decoded);
        return CanonicalHash.rawSha256(canonicalPlanPreimage);
    }

    public ServerId serverId() {
        return serverId;
    }

    public String bundleHash() {
        return bundleHash;
    }

    public String installationId() {
        return installationId;
    }

    public String installAuthorityHash() {
        return installAuthorityHash;
    }

    public SnapshotId snapshotId() {
        return snapshotId;
    }

    public String canonicalSourcePath() {
        return canonicalSourcePath;
    }

    public String sourceIdentityHash() {
        return sourceIdentityHash;
    }

    public String migrationId() {
        return migrationId;
    }

    public String invocationHash() {
        return invocationHash;
    }

    public String planPreimageHash() {
        return planPreimageHash;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public String grantId() {
        return grantId;
    }

    public String authorityKeyId() {
        return authorityKeyId;
    }

    public String publicKeyFingerprint() {
        return publicKeyFingerprint;
    }

    public String signature() {
        return signature;
    }

    public String grantHash() {
        return grantHash;
    }

    public String canonicalHash() {
        return grantHash;
    }

    public String semantics() {
        return SEMANTICS;
    }

    public boolean verify(
        ProductionAuthorityTrustAnchor trustAnchor,
        ProductionAuthorityBundle bundle,
        Path sourceRoot,
        String migrationId,
        String invocationHash,
        String planPreimageHash,
        Instant now
    ) throws IOException {
        return verify(trustAnchor, bundle, canonicalSourcePath(sourceRoot), sourceIdentityDigest(sourceRoot, bundle),
            migrationId, invocationHash, planPreimageHash, now);
    }

    public boolean verify(
        ProductionAuthorityTrustAnchor trustAnchor,
        ProductionAuthorityBundle bundle,
        String canonicalSourcePath,
        String sourceIdentityHash,
        String migrationId,
        String invocationHash,
        String planPreimageHash,
        Instant now
    ) {
        ProductionAuthorityTrustAnchor anchor = trustAnchor;
        ProductionAuthorityBundle checkedBundle = bundle;
        if (anchor == null || checkedBundle == null || now == null || !anchor.matches(checkedBundle)
            || !checkedBundle.verifySignature(anchor)
            || !bundleHash.equals(checkedBundle.bundleHash())
            || !serverId.equals(checkedBundle.serverId())
            || !installationId.equals(checkedBundle.installationId())
            || !installAuthorityHash.equals(checkedBundle.installAuthorityHash())
            || !snapshotId.equals(checkedBundle.snapshotId())
            || !authorityKeyId.equals(checkedBundle.authorityKeyId())
            || !publicKeyFingerprint.equals(checkedBundle.publicKeyFingerprint())
            || !safeEquals(this.canonicalSourcePath, canonicalSourcePath)
            || !safeEquals(this.sourceIdentityHash, sourceIdentityHash)
            || !safeEquals(this.migrationId, migrationId)
            || !safeDigestEquals(this.invocationHash, invocationHash)
            || !safeDigestEquals(this.planPreimageHash, planPreimageHash)
            || issuedAt.isAfter(now)
            || expiresAt.isBefore(now)
            || !anchor.verify(unsignedCanonicalBytes(), signature)) {
            return false;
        }
        return true;
    }

    public boolean acceptOnce(
        ReplayGuard replayGuard,
        ProductionAuthorityTrustAnchor trustAnchor,
        ProductionAuthorityBundle bundle,
        Path sourceRoot,
        String migrationId,
        String invocationHash,
        String planPreimageHash,
        Instant now
    ) throws IOException {
        ReplayGuard guard = Objects.requireNonNull(replayGuard, "replayGuard");
        return verify(trustAnchor, bundle, sourceRoot, migrationId, invocationHash, planPreimageHash, now)
            && guard.consume(grantId);
    }

    public boolean isFutureAt(Instant now) {
        return issuedAt.isAfter(Objects.requireNonNull(now, "now"));
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt.isBefore(Objects.requireNonNull(now, "now"));
    }

    public String canonical() {
        return CanonicalCodec.encodeText(JsonValue.fromJava(canonicalValue()));
    }

    public byte[] canonicalBytes() {
        return CanonicalCodec.encode(JsonValue.fromJava(canonicalValue()));
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> value = new LinkedHashMap<>(unsignedValue(bundleHash, serverId, installationId,
            installAuthorityHash, snapshotId, canonicalSourcePath, sourceIdentityHash, migrationId, invocationHash,
            planPreimageHash, issuedAt, expiresAt, grantId, authorityKeyId, publicKeyFingerprint));
        value.put("signature", signature);
        value.put("grantHash", grantHash);
        return Map.copyOf(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuthorityUseGrant value)) {
            return false;
        }
        return bundleHash.equals(value.bundleHash)
            && serverId.equals(value.serverId)
            && installationId.equals(value.installationId)
            && installAuthorityHash.equals(value.installAuthorityHash)
            && snapshotId.equals(value.snapshotId)
            && canonicalSourcePath.equals(value.canonicalSourcePath)
            && sourceIdentityHash.equals(value.sourceIdentityHash)
            && migrationId.equals(value.migrationId)
            && invocationHash.equals(value.invocationHash)
            && planPreimageHash.equals(value.planPreimageHash)
            && issuedAt.equals(value.issuedAt)
            && expiresAt.equals(value.expiresAt)
            && grantId.equals(value.grantId)
            && authorityKeyId.equals(value.authorityKeyId)
            && publicKeyFingerprint.equals(value.publicKeyFingerprint)
            && signature.equals(value.signature)
            && grantHash.equals(value.grantHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bundleHash, serverId, installationId, installAuthorityHash, snapshotId,
            canonicalSourcePath, sourceIdentityHash, migrationId, invocationHash, planPreimageHash, issuedAt,
            expiresAt, grantId, authorityKeyId, publicKeyFingerprint, signature, grantHash);
    }

    private byte[] unsignedCanonicalBytes() {
        return JsonValue.fromJava(unsignedValue(bundleHash, serverId, installationId, installAuthorityHash,
            snapshotId, canonicalSourcePath, sourceIdentityHash, migrationId, invocationHash, planPreimageHash,
            issuedAt, expiresAt, grantId, authorityKeyId, publicKeyFingerprint)).canonicalBytes();
    }

    private static Map<String, Object> unsignedValue(
        String bundleHash,
        ServerId serverId,
        String installationId,
        String installAuthorityHash,
        SnapshotId snapshotId,
        String canonicalSourcePath,
        String sourceIdentityHash,
        String migrationId,
        String invocationHash,
        String planPreimageHash,
        Instant issuedAt,
        Instant expiresAt,
        String grantId,
        String authorityKeyId,
        String publicKeyFingerprint
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", KIND);
        value.put("formatVersion", FORMAT_VERSION);
        value.put("schema", SCHEMA);
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("bundleHash", MigrationCanonical.requireDigest(bundleHash, "bundleHash"));
        value.put("serverId", Objects.requireNonNull(serverId, "serverId").canonicalText());
        value.put("installationId", requireIdentifier(installationId, "installationId"));
        value.put("installAuthorityHash", MigrationCanonical.requireDigest(installAuthorityHash, "installAuthorityHash"));
        value.put("snapshotId", Objects.requireNonNull(snapshotId, "snapshotId").canonicalText());
        value.put("canonicalSourcePath", requireCanonicalPath(canonicalSourcePath));
        value.put("sourceIdentityHash", MigrationCanonical.requireDigest(sourceIdentityHash, "sourceIdentityHash"));
        value.put("migrationId", MigrationCanonical.requireText(migrationId, "migrationId"));
        value.put("invocationHash", MigrationCanonical.requireDigest(invocationHash, "invocationHash"));
        value.put("planPreimageHash", MigrationCanonical.requireDigest(planPreimageHash, "planPreimageHash"));
        value.put("issuedAt", requireInstant(issuedAt, "issuedAt").toString());
        value.put("expiresAt", requireInstant(expiresAt, "expiresAt").toString());
        value.put("grantId", requireIdentifier(grantId, "grantId"));
        value.put("authorityKeyId", requireIdentifier(authorityKeyId, "authorityKeyId"));
        value.put("publicKeyFingerprint", MigrationCanonical.requireDigest(publicKeyFingerprint, "publicKeyFingerprint"));
        return value;
    }

    private static String requireCanonicalPath(String value) {
        String checked = MigrationCanonical.requireText(value, "canonicalSourcePath");
        Path path;
        try {
            path = MigrationPaths.requirePath(Path.of(checked), "canonicalSourcePath");
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Canonical Source Path Is Invalid", exception);
        }
        if (!path.toString().equals(checked)) {
            throw new IllegalArgumentException("Canonical Source Path Is Not Normalized");
        }
        return checked;
    }

    private static String requireCanonicalPreimage(String value) {
        String checked = Objects.requireNonNull(value, "planPreimage");
        if (checked.isBlank() || checked.indexOf('\u0000') >= 0 || checked.indexOf('\r') >= 0
            || !StandardCharsets.UTF_8.newEncoder().canEncode(checked)) {
            throw new IllegalArgumentException("Plan Preimage Is Invalid");
        }
        if (checked.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Plan Preimage Is Too Large");
        }
        return checked;
    }

    private static Instant requireInstant(Instant value, String field) {
        return Objects.requireNonNull(value, field);
    }

    private static String requireIdentifier(String value, String field) {
        String checked = MigrationCanonical.requireText(value, field);
        if (checked.length() > 256 || !StandardCharsets.UTF_8.newEncoder().canEncode(checked)) {
            throw new IllegalArgumentException(field + " Is Not A Canonical Identifier");
        }
        return checked;
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

    private static String text(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Grant Field Must Be Text: " + field);
        }
        return text;
    }

    private static int number(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("Grant Field Must Be A Number: " + field);
        }
        try {
            return new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Grant Number Must Be An Integer: " + field, exception);
        }
    }

    private static Instant instant(Map<String, Object> value, String field) {
        String text = text(value, field);
        Instant parsed = Instant.parse(text);
        if (!parsed.toString().equals(text)) {
            throw new IllegalArgumentException("Grant Instant Is Not Canonical: " + field);
        }
        return parsed;
    }

    private static Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, entry) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("Grant Object Keys Must Be Text");
            }
            value.put(text, entry);
        });
        return value;
    }

    private static boolean safeEquals(String expected, String actual) {
        return actual != null && expected.equals(actual);
    }

    private static boolean safeDigestEquals(String expected, String actual) {
        try {
            return expected.equals(MigrationCanonical.requireDigest(actual, "digest"));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Plan Preimage Encoding Is Invalid", exception);
        }
    }

    private static <T> T throwInvalid(String message) {
        throw new IllegalArgumentException(message);
    }

    public static final class ReplayGuard {
        private final Set<String> consumed = ConcurrentHashMap.newKeySet();

        public boolean consume(String grantId) {
            return consumed.add(requireIdentifier(grantId, "grantId"));
        }

        public boolean consumed(String grantId) {
            return consumed.contains(requireIdentifier(grantId, "grantId"));
        }
    }
}
