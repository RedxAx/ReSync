package restudio.resync.migration;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ProductionAuthorityBundle {
    public static final int FORMAT_VERSION = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final String KIND = "resync-authority-bundle";
    public static final String SCHEMA = "resync.authority-bundle.v1";
    public static final String HASH_DOMAIN = "resync.authority-bundle";
    public static final String AUTHORITY_DIRECTORY = "authority";
    public static final String AUTHORITY_FILE = "authority-bundle.json";
    public static final String INSTALL_AUTHORITY_FILE = ".resync-install-authority.db";
    public static final String SIGNATURE_ALGORITHM = "Ed25519";
    public static final Duration MAX_FRESHNESS_AGE = Duration.ofDays(7);
    public static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final long MAX_BYTES = 64 * 1024;
    private static final Set<String> KNOWN_FIELDS = Set.of(
        "kind", "formatVersion", "schema", "schemaVersion", "serverId", "installationId", "authorityKeyId",
        "publicKeyFingerprint", "snapshotId", "timestamp",
        "catalogContentChecksum", "catalogGeneration", "catalogContractVersion", "runtimeBindingManifestHash",
        "runtimeBindingManifestVersion", "readinessReportHash", "readinessReportVersion", "installAuthorityHash",
        "signingPublicKey", "signature", "bundleHash");

    private final ServerId serverId;
    private final String installationId;
    private final String authorityKeyId;
    private final String publicKeyFingerprint;
    private final SnapshotId snapshotId;
    private final Instant timestamp;
    private final ContentHash catalogContentChecksum;
    private final long catalogGeneration;
    private final CatalogVersion catalogContractVersion;
    private final ContentHash runtimeBindingManifestHash;
    private final int runtimeBindingManifestVersion;
    private final String readinessReportHash;
    private final int readinessReportVersion;
    private final String installAuthorityHash;
    private final String signingPublicKey;
    private final String signature;
    private final String bundleHash;

    public record TrustedAuthority(ServerId serverId, String installAuthorityHash, String trustedPublicKey) {
        public TrustedAuthority {
            serverId = Objects.requireNonNull(serverId, "serverId");
            installAuthorityHash = MigrationCanonical.requireDigest(installAuthorityHash, "installAuthorityHash");
            trustedPublicKey = requireBase64(trustedPublicKey, "trustedPublicKey");
        }

        public static TrustedAuthority from(ProductionAuthoritySigner signer) throws IOException {
            ProductionAuthoritySigner checked = Objects.requireNonNull(signer, "signer");
            return new TrustedAuthority(checked.serverId(), checked.installAuthorityHash(), checked.signingPublicKey());
        }

        public static TrustedAuthority of(ProductionAuthoritySigner signer) throws IOException {
            return from(signer);
        }

        public static TrustedAuthority from(Path dataRoot, ServerId serverId, String trustedPublicKey) throws IOException {
            return new TrustedAuthority(serverId, installAuthorityDigest(dataRoot), trustedPublicKey);
        }

        public static TrustedAuthority of(Path dataRoot, ServerId serverId, String trustedPublicKey) throws IOException {
            return from(dataRoot, serverId, trustedPublicKey);
        }

        public static TrustedAuthority from(Path dataRoot, ProductionAuthoritySigner signer) throws IOException {
            TrustedAuthority authority = from(signer);
            String durableHash = installAuthorityDigest(dataRoot);
            if (!authority.installAuthorityHash().equals(durableHash)) {
                throw new MigrationException("Trusted Production Authority Is Not Bound To Durable Install Authority");
            }
            return authority;
        }

        public static TrustedAuthority of(Path dataRoot, ProductionAuthoritySigner signer) throws IOException {
            return from(dataRoot, signer);
        }

        public boolean matchesDurableInstall(Path dataRoot) throws IOException {
            return installAuthorityHash.equals(installAuthorityDigest(dataRoot));
        }

        public String publicKey() {
            return trustedPublicKey;
        }

        @Deprecated(forRemoval = true)
        public byte[] keyMaterial() {
            throw new UnsupportedOperationException("Public Key Is Not Integrity Key Material");
        }

        ProductionAuthorityTrustAnchor trustAnchor() {
            String fingerprint = ProductionAuthorityTrustAnchor.fingerprint(trustedPublicKey);
            return new ProductionAuthorityTrustAnchor(serverId, serverId.canonicalText(), installAuthorityHash,
                fingerprint, fingerprint, trustedPublicKey);
        }
    }

    public record TrustContext(TrustedAuthority installationAuthority, Path sourceRoot,
                               SnapshotId snapshotId, String migrationId) {
        public TrustContext {
            installationAuthority = Objects.requireNonNull(installationAuthority, "installationAuthority");
            sourceRoot = MigrationPaths.requirePath(sourceRoot, "sourceRoot");
            snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
            migrationId = MigrationCanonical.requireText(migrationId, "migrationId");
        }

        public TrustContext(Path sourceRoot, ServerId serverId, SnapshotId snapshotId, String migrationId,
                            String installAuthorityHash, String trustedPublicKey) {
            this(new TrustedAuthority(serverId, installAuthorityHash, trustedPublicKey), sourceRoot, snapshotId, migrationId);
        }

        public ServerId serverId() {
            return installationAuthority.serverId();
        }

        public String installAuthorityHash() {
            return installationAuthority.installAuthorityHash();
        }

        public String trustedPublicKey() {
            return installationAuthority.trustedPublicKey();
        }

        public TrustedAuthority trustedAuthority() {
            return installationAuthority;
        }

        public static TrustContext forSource(Path sourceRoot, SnapshotId snapshotId, String migrationId,
                                             ProductionAuthoritySigner signer) throws IOException {
            return new TrustContext(TrustedAuthority.from(sourceRoot, signer), sourceRoot, snapshotId, migrationId);
        }

        public static TrustContext of(Path sourceRoot, SnapshotId snapshotId, String migrationId,
                                      ProductionAuthoritySigner signer) throws IOException {
            return forSource(sourceRoot, snapshotId, migrationId, signer);
        }

        public static TrustContext forSource(Path sourceRoot, ServerId serverId, SnapshotId snapshotId,
                                             String migrationId, String trustedPublicKey) throws IOException {
            return new TrustContext(TrustedAuthority.from(sourceRoot, serverId, trustedPublicKey), sourceRoot,
                snapshotId, migrationId);
        }

        public static TrustContext of(Path sourceRoot, ServerId serverId, SnapshotId snapshotId,
                                      String migrationId, String trustedPublicKey) throws IOException {
            return forSource(sourceRoot, serverId, snapshotId, migrationId, trustedPublicKey);
        }
    }

    private ProductionAuthorityBundle(
        ServerId serverId,
        String installationId,
        String authorityKeyId,
        String publicKeyFingerprint,
        SnapshotId snapshotId,
        Instant timestamp,
        ContentHash catalogContentChecksum,
        long catalogGeneration,
        CatalogVersion catalogContractVersion,
        ContentHash runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String readinessReportHash,
        int readinessReportVersion,
        String installAuthorityHash,
        String signingPublicKey,
        String signature,
        String bundleHash
    ) {
        this.serverId = Objects.requireNonNull(serverId, "Server ID Is Required");
        this.installationId = requireIdentifier(installationId, "installationId");
        this.authorityKeyId = requireIdentifier(authorityKeyId, "authorityKeyId");
        this.publicKeyFingerprint = MigrationCanonical.requireDigest(publicKeyFingerprint, "publicKeyFingerprint");
        this.snapshotId = Objects.requireNonNull(snapshotId, "Snapshot ID Is Required");
        this.timestamp = Objects.requireNonNull(timestamp, "Authority Bundle Timestamp Is Required");
        this.catalogContentChecksum = Objects.requireNonNull(catalogContentChecksum, "Catalog Content Checksum Is Required");
        if (catalogGeneration < 1) {
            throw new IllegalArgumentException("Catalog Generation Must Be Positive");
        }
        this.catalogGeneration = catalogGeneration;
        this.catalogContractVersion = Objects.requireNonNull(catalogContractVersion, "Catalog Contract Version Is Required");
        this.runtimeBindingManifestHash = Objects.requireNonNull(runtimeBindingManifestHash, "Runtime Binding Manifest Hash Is Required");
        if (runtimeBindingManifestVersion < 1) {
            throw new IllegalArgumentException("Runtime Binding Manifest Version Must Be Positive");
        }
        this.runtimeBindingManifestVersion = runtimeBindingManifestVersion;
        this.readinessReportHash = MigrationCanonical.requireDigest(readinessReportHash, "readinessReportHash");
        if (readinessReportVersion < 1) {
            throw new IllegalArgumentException("Readiness Report Version Must Be Positive");
        }
        this.readinessReportVersion = readinessReportVersion;
        this.installAuthorityHash = MigrationCanonical.requireDigest(installAuthorityHash, "installAuthorityHash");
        this.signingPublicKey = requireBase64(signingPublicKey, "signingPublicKey");
        if (!this.publicKeyFingerprint.equals(ProductionAuthorityTrustAnchor.fingerprint(this.signingPublicKey))) {
            throw new IllegalArgumentException("Public Key Fingerprint Does Not Match Signing Public Key");
        }
        this.signature = requireBase64(signature, "signature");
        this.bundleHash = MigrationCanonical.requireDigest(bundleHash, "bundleHash");
    }

    static ProductionAuthorityBundle create(
        ProductionAuthoritySigner signer,
        ServerId serverId,
        SnapshotId snapshotId,
        Instant timestamp,
        ContentHash catalogContentChecksum,
        long catalogGeneration,
        CatalogVersion catalogContractVersion,
        ContentHash runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String readinessReportHash,
        int readinessReportVersion
    ) throws IOException {
        ProductionAuthoritySigner checkedSigner = Objects.requireNonNull(signer, "Authority Signer Is Required");
        if (!checkedSigner.serverId().equals(serverId)) {
            throw new MigrationException("Authority Signer Server Identity Does Not Match The Bundle");
        }
        String publicKey = requireBase64(checkedSigner.signingPublicKey(), "signingPublicKey");
        String keyFingerprint = ProductionAuthorityTrustAnchor.fingerprint(publicKey);
        return create(checkedSigner, serverId.canonicalText(), keyFingerprint, serverId, snapshotId, timestamp,
            catalogContentChecksum, catalogGeneration, catalogContractVersion, runtimeBindingManifestHash,
            runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion);
    }

    public static ProductionAuthorityBundle create(
        ProductionAuthoritySigner signer,
        String installationId,
        String authorityKeyId,
        ServerId serverId,
        SnapshotId snapshotId,
        Instant timestamp,
        ContentHash catalogContentChecksum,
        long catalogGeneration,
        CatalogVersion catalogContractVersion,
        ContentHash runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String readinessReportHash,
        int readinessReportVersion
    ) throws IOException {
        return create(Objects.requireNonNull(signer, "Authority Signer Is Required"), installationId, authorityKeyId,
            serverId, snapshotId, timestamp, catalogContentChecksum, catalogGeneration, catalogContractVersion,
            runtimeBindingManifestHash, runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion,
            null);
    }

    public static ProductionAuthorityBundle create(
        ProductionAuthoritySigner signer,
        ProductionAuthorityTrustAnchor trustAnchor,
        SnapshotId snapshotId,
        Instant timestamp,
        ContentHash catalogContentChecksum,
        long catalogGeneration,
        CatalogVersion catalogContractVersion,
        ContentHash runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String readinessReportHash,
        int readinessReportVersion
    ) throws IOException {
        ProductionAuthorityTrustAnchor anchor = Objects.requireNonNull(trustAnchor, "Trust Anchor Is Required");
        return create(Objects.requireNonNull(signer, "Authority Signer Is Required"), anchor.installationId(),
            anchor.authorityKeyId(), anchor.serverId(), snapshotId, timestamp, catalogContentChecksum,
            catalogGeneration, catalogContractVersion, runtimeBindingManifestHash, runtimeBindingManifestVersion,
            readinessReportHash, readinessReportVersion, anchor);
    }

    private static ProductionAuthorityBundle create(
        ProductionAuthoritySigner signer,
        String installationId,
        String authorityKeyId,
        ServerId serverId,
        SnapshotId snapshotId,
        Instant timestamp,
        ContentHash catalogContentChecksum,
        long catalogGeneration,
        CatalogVersion catalogContractVersion,
        ContentHash runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String readinessReportHash,
        int readinessReportVersion,
        ProductionAuthorityTrustAnchor trustAnchor
    ) throws IOException {
        ProductionAuthoritySigner checkedSigner = Objects.requireNonNull(signer, "Authority Signer Is Required");
        if (!checkedSigner.serverId().equals(serverId)) {
            throw new MigrationException("Authority Signer Server Identity Does Not Match The Bundle");
        }
        String installAuthorityHash = MigrationCanonical.requireDigest(checkedSigner.installAuthorityHash(), "installAuthorityHash");
        String signingPublicKey = requireBase64(checkedSigner.signingPublicKey(), "signingPublicKey");
        String publicKeyFingerprint = ProductionAuthorityTrustAnchor.fingerprint(signingPublicKey);
        if (trustAnchor != null && (!trustAnchor.serverId().equals(serverId)
            || !trustAnchor.installationId().equals(installationId)
            || !trustAnchor.authorityKeyId().equals(authorityKeyId)
            || !trustAnchor.installAuthorityHash().equals(installAuthorityHash)
            || !trustAnchor.publicKeyFingerprint().equals(publicKeyFingerprint)
            || !trustAnchor.publicKey().equals(signingPublicKey))) {
            throw new MigrationException("Authority Signer Does Not Match The Pinned Trust Anchor");
        }
        Map<String, Object> unsigned = unsignedValue(serverId, installationId, authorityKeyId, publicKeyFingerprint,
            snapshotId, timestamp, catalogContentChecksum, catalogGeneration, catalogContractVersion,
            runtimeBindingManifestHash, runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion,
            installAuthorityHash, signingPublicKey);
        String signature = requireBase64(checkedSigner.sign(JsonValue.fromJava(unsigned).canonicalBytes()), "signature");
        return create(serverId, installationId, authorityKeyId, publicKeyFingerprint, snapshotId, timestamp,
            catalogContentChecksum, catalogGeneration, catalogContractVersion, runtimeBindingManifestHash,
            runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion, installAuthorityHash,
            signingPublicKey, signature);
    }

    static ProductionAuthorityBundle create(
        ServerId serverId,
        SnapshotId snapshotId,
        Instant timestamp,
        ContentHash catalogContentChecksum,
        long catalogGeneration,
        CatalogVersion catalogContractVersion,
        ContentHash runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String readinessReportHash,
        int readinessReportVersion,
        String installAuthorityHash,
        String signingPublicKey,
        String signature
    ) {
        String checkedPublicKey = requireBase64(signingPublicKey, "signingPublicKey");
        String publicKeyFingerprint = ProductionAuthorityTrustAnchor.fingerprint(checkedPublicKey);
        return create(serverId, serverId.canonicalText(), publicKeyFingerprint, publicKeyFingerprint, snapshotId,
            timestamp, catalogContentChecksum, catalogGeneration, catalogContractVersion, runtimeBindingManifestHash,
            runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion, installAuthorityHash,
            checkedPublicKey, signature);
    }

    public static ProductionAuthorityBundle create(
        ServerId serverId,
        String installationId,
        String authorityKeyId,
        SnapshotId snapshotId,
        Instant timestamp,
        ContentHash catalogContentChecksum,
        long catalogGeneration,
        CatalogVersion catalogContractVersion,
        ContentHash runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String readinessReportHash,
        int readinessReportVersion,
        String installAuthorityHash,
        String signingPublicKey,
        String signature
    ) {
        String checkedPublicKey = requireBase64(signingPublicKey, "signingPublicKey");
        return create(serverId, installationId, authorityKeyId, ProductionAuthorityTrustAnchor.fingerprint(checkedPublicKey),
            snapshotId, timestamp, catalogContentChecksum, catalogGeneration, catalogContractVersion,
            runtimeBindingManifestHash, runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion,
            installAuthorityHash, checkedPublicKey, signature);
    }

    public static ProductionAuthorityBundle create(
        ServerId serverId,
        String installationId,
        String authorityKeyId,
        String publicKeyFingerprint,
        SnapshotId snapshotId,
        Instant timestamp,
        ContentHash catalogContentChecksum,
        long catalogGeneration,
        CatalogVersion catalogContractVersion,
        ContentHash runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String readinessReportHash,
        int readinessReportVersion,
        String installAuthorityHash,
        String signingPublicKey,
        String signature
    ) {
        Map<String, Object> unsigned = unsignedValue(serverId, installationId, authorityKeyId, publicKeyFingerprint,
            snapshotId, timestamp, catalogContentChecksum, catalogGeneration, catalogContractVersion,
            runtimeBindingManifestHash, runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion,
            installAuthorityHash, signingPublicKey);
        Map<String, Object> hashed = new LinkedHashMap<>(unsigned);
        hashed.put("signature", requireBase64(signature, "signature"));
        String bundleHash = CanonicalHash.sha256(HASH_DOMAIN, JsonValue.fromJava(hashed).canonicalBytes());
        return new ProductionAuthorityBundle(serverId, installationId, authorityKeyId, publicKeyFingerprint, snapshotId,
            timestamp, catalogContentChecksum, catalogGeneration, catalogContractVersion, runtimeBindingManifestHash,
            runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion, installAuthorityHash,
            signingPublicKey, signature, bundleHash);
    }

    public static ProductionAuthorityBundle read(Path path) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "authorityBundlePath");
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Bundle Is Not A Regular File: " + normalized);
        }
        if (Files.size(normalized) > MAX_BYTES) {
            throw new MigrationException("Authority Bundle Is Too Large");
        }
        try {
            return fromCanonical(decodeUtf8(Files.readAllBytes(normalized)));
        } catch (RuntimeException exception) {
            throw new MigrationException("Authority Bundle Is Invalid", exception);
        }
    }

    public static ProductionAuthorityBundle fromCanonical(String text) {
        Objects.requireNonNull(text, "Authority Bundle Canonical Text Is Required");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Authority Bundle Is Too Large");
        }
        JsonValue.JsonObject object = CanonicalCodec.requireObject(CanonicalCodec.decode(text));
        Map<String, Object> value = object.toJava() instanceof Map<?, ?> map
            ? toStringMap(map)
            : throwInvalid("Authority Bundle Must Be A JSON Object");
        if (!value.keySet().equals(KNOWN_FIELDS)) {
            throw new IllegalArgumentException("Authority Bundle Fields Are Not Canonical");
        }
        if (!KIND.equals(text(value, "kind")) || number(value, "formatVersion") != FORMAT_VERSION
            || !SCHEMA.equals(text(value, "schema")) || number(value, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Authority Bundle Schema Is Unsupported");
        }
        ServerId serverId = ServerId.parseCanonicalText(text(value, "serverId"));
        String installationId = requireIdentifier(text(value, "installationId"), "installationId");
        String authorityKeyId = requireIdentifier(text(value, "authorityKeyId"), "authorityKeyId");
        String publicKeyFingerprint = MigrationCanonical.requireDigest(text(value, "publicKeyFingerprint"), "publicKeyFingerprint");
        SnapshotId snapshotId = SnapshotId.parseCanonicalText(text(value, "snapshotId"));
        String timestampText = text(value, "timestamp");
        Instant timestamp = Instant.parse(timestampText);
        if (!timestamp.toString().equals(timestampText)) {
            throw new IllegalArgumentException("Authority Bundle Timestamp Is Not Canonical");
        }
        ContentHash catalogChecksum = ContentHash.parseCanonicalText(text(value, "catalogContentChecksum"));
        long generation = longNumber(value, "catalogGeneration");
        CatalogVersion contractVersion = catalogVersion(value.get("catalogContractVersion"));
        ContentHash runtimeHash = ContentHash.parseCanonicalText(text(value, "runtimeBindingManifestHash"));
        int runtimeVersion = number(value, "runtimeBindingManifestVersion");
        String readinessHash = MigrationCanonical.requireDigest(text(value, "readinessReportHash"), "readinessReportHash");
        int readinessVersion = number(value, "readinessReportVersion");
        String installAuthorityHash = MigrationCanonical.requireDigest(text(value, "installAuthorityHash"), "installAuthorityHash");
        String signingPublicKey = requireBase64(text(value, "signingPublicKey"), "signingPublicKey");
        String signature = requireBase64(text(value, "signature"), "signature");
        ProductionAuthorityBundle expected = create(serverId, installationId, authorityKeyId, publicKeyFingerprint,
            snapshotId, timestamp, catalogChecksum, generation, contractVersion, runtimeHash, runtimeVersion,
            readinessHash, readinessVersion, installAuthorityHash, signingPublicKey, signature);
        String suppliedHash = MigrationCanonical.requireDigest(text(value, "bundleHash"), "bundleHash");
        if (!expected.bundleHash.equals(suppliedHash) || !expected.canonical().equals(text)) {
            throw new IllegalArgumentException("Authority Bundle Hash Does Not Match Content");
        }
        return expected;
    }

    public void write(Path path) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "authorityBundlePath");
        byte[] bytes = canonicalBytes();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Authority Bundle Target Is Not A Regular File: " + normalized);
            }
            byte[] existing = Files.readAllBytes(normalized);
            if (!Arrays.equals(existing, bytes)) {
                throw new MigrationException("Authority Bundle Target Already Contains Different Content: " + normalized);
            }
            return;
        }
        try {
            AtomicFiles.writeNew(normalized, bytes);
        } catch (FileAlreadyExistsException exception) {
            if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                && Arrays.equals(Files.readAllBytes(normalized), bytes)) {
                return;
            }
            throw new MigrationException("Authority Bundle Target Already Contains Different Content: " + normalized, exception);
        }
    }

    public static Path path(Path dataRoot) {
        return MigrationPaths.resolveInside(MigrationPaths.requirePath(dataRoot, "dataRoot"),
            AUTHORITY_DIRECTORY + "/" + AUTHORITY_FILE);
    }

    public static String installAuthorityDigest(Path dataRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(dataRoot, "dataRoot");
        Path authority = MigrationPaths.resolveInside(root, INSTALL_AUTHORITY_FILE);
        if (Files.isSymbolicLink(authority) || !Files.isRegularFile(authority, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Durable Install Authority Is Missing Or Invalid: " + authority);
        }
        return CanonicalHash.rawSha256(Files.readAllBytes(authority));
    }

    public ServerId serverId() {
        return serverId;
    }

    public String installationId() {
        return installationId;
    }

    public String authorityKeyId() {
        return authorityKeyId;
    }

    public String publicKeyFingerprint() {
        return publicKeyFingerprint;
    }

    public SnapshotId snapshotId() {
        return snapshotId;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public ContentHash catalogContentChecksum() {
        return catalogContentChecksum;
    }

    public long catalogGeneration() {
        return catalogGeneration;
    }

    public CatalogVersion catalogContractVersion() {
        return catalogContractVersion;
    }

    public ContentHash runtimeBindingManifestHash() {
        return runtimeBindingManifestHash;
    }

    public int runtimeBindingManifestVersion() {
        return runtimeBindingManifestVersion;
    }

    public String readinessReportHash() {
        return readinessReportHash;
    }

    public int readinessReportVersion() {
        return readinessReportVersion;
    }

    public String installAuthorityHash() {
        return installAuthorityHash;
    }

    public String signingPublicKey() {
        return signingPublicKey;
    }

    public String signature() {
        return signature;
    }

    public boolean verifySignature(ProductionAuthorityTrustAnchor trustAnchor) {
        ProductionAuthorityTrustAnchor anchor = trustAnchor;
        return anchor != null && anchor.matches(this) && anchor.verify(unsignedCanonicalBytes(), signature);
    }

    public void requireAuthenticated(ProductionAuthorityTrustAnchor trustAnchor) throws MigrationException {
        if (!verifySignature(trustAnchor)) {
            throw new MigrationException("Production Authority Bundle Is Not Authenticated By The Pinned Trust Anchor");
        }
    }

    public boolean verifySignature(TrustedAuthority trustedAuthority) {
        TrustedAuthority authority = trustedAuthority;
        return authority != null && verifySignature(authority.trustAnchor());
    }

    public boolean verifySignature(Path sourceRoot, ProductionAuthorityTrustAnchor trustAnchor) {
        Path root;
        try {
            root = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot").toRealPath();
            return verifySignature(trustAnchor)
                && trustAnchor.matchesDurableInstall(root)
                && sourceIdentityMatches(root);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    public boolean verifySignature(Path sourceRoot, TrustedAuthority trustedAuthority) {
        Path root;
        try {
            root = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot").toRealPath();
            return verifySignature(trustedAuthority)
                && trustedAuthority != null
                && trustedAuthority.matchesDurableInstall(root)
                && sourceIdentityMatches(root);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    public boolean verifySignature(TrustContext context) {
        TrustContext checked = context;
        if (checked == null || !snapshotId.equals(checked.snapshotId())) {
            return false;
        }
        return verifySignature(checked.sourceRoot(), checked.installationAuthority());
    }

    public boolean verifySignature(ServerId expectedServerId, String expectedInstallAuthorityHash,
                                   String trustedPublicKey) {
        try {
            return verifySignature(new TrustedAuthority(expectedServerId, expectedInstallAuthorityHash, trustedPublicKey));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean verifySignature(String expectedInstallAuthorityHash, String trustedPublicKey) {
        return verifySignature(serverId, expectedInstallAuthorityHash, trustedPublicKey);
    }

    public boolean verifySignatureWithTrustedKey(String trustedPublicKey, String expectedInstallAuthorityHash) {
        return verifySignature(expectedInstallAuthorityHash, trustedPublicKey);
    }

    public boolean verifySignature(String expectedInstallAuthorityHash) {
        return false;
    }

    public boolean authenticate(TrustContext context) {
        return verifySignature(context);
    }

    public boolean authenticate(ProductionAuthorityTrustAnchor trustAnchor) {
        return verifySignature(trustAnchor);
    }

    public boolean authenticate(Path sourceRoot, TrustedAuthority trustedAuthority) {
        return verifySignature(sourceRoot, trustedAuthority);
    }

    public void requireAuthenticated(TrustContext context) throws MigrationException {
        if (!verifySignature(context)) {
            throw new MigrationException("Production Authority Bundle Is Not Authenticated By The Trusted Installation Authority");
        }
    }

    public void requireAuthenticated(Path sourceRoot, ProductionAuthorityTrustAnchor trustAnchor) throws MigrationException {
        if (!verifySignature(sourceRoot, trustAnchor)) {
            throw new MigrationException("Production Authority Bundle Is Not Authenticated By The Pinned Trust Anchor");
        }
    }

    public void requireAuthenticated(Path sourceRoot, TrustedAuthority trustedAuthority) throws MigrationException {
        if (!verifySignature(sourceRoot, trustedAuthority)) {
            throw new MigrationException("Production Authority Bundle Is Not Authenticated By The Trusted Installation Authority");
        }
    }

    public String bundleHash() {
        return bundleHash;
    }

    public boolean freshAt(Instant now) {
        Instant current = Objects.requireNonNull(now, "now");
        return !timestamp.isBefore(current.minus(MAX_FRESHNESS_AGE))
            && !timestamp.isAfter(current.plus(MAX_FUTURE_SKEW));
    }

    public String canonical() {
        return CanonicalCodec.encodeText(JsonValue.fromJava(canonicalValue()));
    }

    public byte[] canonicalBytes() {
        return CanonicalCodec.encode(JsonValue.fromJava(canonicalValue()));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProductionAuthorityBundle value)) {
            return false;
        }
        return catalogGeneration == value.catalogGeneration
            && runtimeBindingManifestVersion == value.runtimeBindingManifestVersion
            && readinessReportVersion == value.readinessReportVersion
            && serverId.equals(value.serverId)
            && installationId.equals(value.installationId)
            && authorityKeyId.equals(value.authorityKeyId)
            && publicKeyFingerprint.equals(value.publicKeyFingerprint)
            && snapshotId.equals(value.snapshotId)
            && timestamp.equals(value.timestamp)
            && catalogContentChecksum.equals(value.catalogContentChecksum)
            && catalogContractVersion.equals(value.catalogContractVersion)
            && runtimeBindingManifestHash.equals(value.runtimeBindingManifestHash)
            && readinessReportHash.equals(value.readinessReportHash)
            && installAuthorityHash.equals(value.installAuthorityHash)
            && signingPublicKey.equals(value.signingPublicKey)
            && signature.equals(value.signature)
            && bundleHash.equals(value.bundleHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverId, installationId, authorityKeyId, publicKeyFingerprint, snapshotId, timestamp,
            catalogContentChecksum, catalogGeneration, catalogContractVersion, runtimeBindingManifestHash,
            runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion, installAuthorityHash,
            signingPublicKey, signature, bundleHash);
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> value = new LinkedHashMap<>(unsignedValue(serverId, installationId, authorityKeyId,
            publicKeyFingerprint, snapshotId, timestamp, catalogContentChecksum, catalogGeneration,
            catalogContractVersion, runtimeBindingManifestHash, runtimeBindingManifestVersion, readinessReportHash,
            readinessReportVersion, installAuthorityHash, signingPublicKey));
        value.put("signature", signature);
        value.put("bundleHash", bundleHash);
        return Map.copyOf(value);
    }

    private byte[] unsignedCanonicalBytes() {
        return JsonValue.fromJava(unsignedValue(serverId, installationId, authorityKeyId, publicKeyFingerprint,
            snapshotId, timestamp, catalogContentChecksum, catalogGeneration, catalogContractVersion,
            runtimeBindingManifestHash, runtimeBindingManifestVersion, readinessReportHash, readinessReportVersion,
            installAuthorityHash, signingPublicKey)).canonicalBytes();
    }

    private static Map<String, Object> unsignedValue(
        ServerId serverId,
        String installationId,
        String authorityKeyId,
        String publicKeyFingerprint,
        SnapshotId snapshotId,
        Instant timestamp,
        ContentHash catalogContentChecksum,
        long catalogGeneration,
        CatalogVersion catalogContractVersion,
        ContentHash runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String readinessReportHash,
        int readinessReportVersion,
        String installAuthorityHash,
        String signingPublicKey
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", KIND);
        value.put("formatVersion", FORMAT_VERSION);
        value.put("schema", SCHEMA);
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("serverId", Objects.requireNonNull(serverId, "serverId").canonicalText());
        value.put("installationId", requireIdentifier(installationId, "installationId"));
        value.put("authorityKeyId", requireIdentifier(authorityKeyId, "authorityKeyId"));
        value.put("publicKeyFingerprint", MigrationCanonical.requireDigest(publicKeyFingerprint, "publicKeyFingerprint"));
        value.put("snapshotId", Objects.requireNonNull(snapshotId, "snapshotId").canonicalText());
        value.put("timestamp", Objects.requireNonNull(timestamp, "timestamp").toString());
        value.put("catalogContentChecksum", Objects.requireNonNull(catalogContentChecksum, "catalogContentChecksum").canonicalText());
        value.put("catalogGeneration", catalogGeneration);
        value.put("catalogContractVersion", Map.of(
            "generation", Objects.requireNonNull(catalogContractVersion, "catalogContractVersion").generation(),
            "minor", catalogContractVersion.minor()));
        value.put("runtimeBindingManifestHash", Objects.requireNonNull(runtimeBindingManifestHash, "runtimeBindingManifestHash").canonicalText());
        value.put("runtimeBindingManifestVersion", runtimeBindingManifestVersion);
        value.put("readinessReportHash", MigrationCanonical.requireDigest(readinessReportHash, "readinessReportHash"));
        value.put("readinessReportVersion", readinessReportVersion);
        value.put("installAuthorityHash", MigrationCanonical.requireDigest(installAuthorityHash, "installAuthorityHash"));
        value.put("signingPublicKey", requireBase64(signingPublicKey, "signingPublicKey"));
        return value;
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

    private boolean sourceIdentityMatches(Path sourceRoot) throws IOException {
        Path identity = MigrationPaths.resolveInside(sourceRoot, "server-id");
        if (Files.isSymbolicLink(identity) || !Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String text = Files.readString(identity, StandardCharsets.UTF_8);
        if (!text.equals(serverId.canonicalText() + "\n")) {
            return false;
        }
        try {
            return ServerId.parseCanonicalText(text.substring(0, text.length() - 1)).equals(serverId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static CatalogVersion catalogVersion(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Catalog Contract Version Must Be An Object");
        }
        Map<String, Object> value = toStringMap(map);
        if (!value.keySet().equals(Set.of("generation", "minor"))) {
            throw new IllegalArgumentException("Catalog Contract Version Fields Are Invalid");
        }
        return new CatalogVersion(number(value, "generation"), number(value, "minor"));
    }

    private static Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, entry) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("Authority Bundle Object Keys Must Be Text");
            }
            value.put(text, entry);
        });
        return value;
    }

    private static String text(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Authority Bundle Field Must Be Text: " + field);
        }
        return text;
    }

    private static int number(Map<String, Object> value, String field) {
        long parsed = longNumber(value, field);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Authority Bundle Number Is Outside Integer Range: " + field);
        }
        return (int) parsed;
    }

    private static long longNumber(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("Authority Bundle Field Must Be A Number: " + field);
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Authority Bundle Number Must Be An Integer: " + field, exception);
        }
    }

    private static <T> T throwInvalid(String message) {
        throw new IllegalArgumentException(message);
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Authority Bundle Encoding Is Invalid", exception);
        }
    }
}
