package restudio.resync.server;

import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.AuthorityUseGrant;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionAuthorityBundle;
import restudio.resync.migration.ProductionAuthorityTrustAnchor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

final class ProductionAuthorityIssuer {
    static final Duration MAX_GRANT_LIFETIME = Duration.ofMinutes(5);
    static final String GRANTS_DIRECTORY = "grants";
    static final String GRANTS_RELATIVE_DIRECTORY = ProductionAuthorityKeyStore.TRUST_ANCHOR_DIRECTORY + "/" + GRANTS_DIRECTORY;

    private final ServerIdentityStore identity;
    private final Clock clock;

    ProductionAuthorityIssuer(ServerIdentityStore identity) {
        this(identity, Clock.systemUTC());
    }

    ProductionAuthorityIssuer(ServerIdentityStore identity, Clock clock) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    ProductionAuthorityKeyStore authorityStore() {
        return identity.productionAuthorityKeyStore();
    }

    ProductionAuthorityTrustAnchor exportTrustAnchor() throws IOException {
        return identity.withProductionAuthority(ProductionAuthorityKeyStore::trustAnchor);
    }

    VerifiedAuthorityMaterial verifiedAuthorityMaterial() throws IOException {
        return identity.withProductionAuthority(store -> {
            ProductionAuthorityTrustAnchor anchor = store.trustAnchor();
            ProductionAuthorityBundle.TrustedAuthority trustedAuthority =
                new ProductionAuthorityBundle.TrustedAuthority(anchor.serverId(), anchor.installAuthorityHash(),
                    anchor.publicKey());
            return new VerifiedAuthorityMaterial(anchor, trustedAuthority);
        });
    }

    Path trustAnchorPath() throws IOException {
        return identity.withProductionAuthority(ProductionAuthorityKeyStore::trustAnchorPath);
    }

    AuthorityUseGrant issueUseGrant(Path sourceRoot, String migrationId, String invocationHash,
                                    String planPreimageHash, String grantId) throws IOException {
        return issueUseGrant(sourceRoot, migrationId, invocationHash, planPreimageHash, grantId,
            MAX_GRANT_LIFETIME);
    }

    AuthorityUseGrant issueUseGrant(Path sourceRoot, String migrationId, String invocationHash,
                                    String planPreimageHash, String grantId, Duration lifetime) throws IOException {
        return identity.withProductionAuthority(signer -> issueUseGrant(sourceRoot, migrationId, invocationHash,
            planPreimageHash, grantId, lifetime, signer));
    }

    private AuthorityUseGrant issueUseGrant(Path sourceRoot, String migrationId, String invocationHash,
                                            String planPreimageHash, String grantId, Duration lifetime,
                                            ProductionAuthorityKeyStore signer) throws IOException {
        Path source = canonicalDirectory(sourceRoot);
        String checkedMigrationId = requireText(migrationId, "migrationId");
        String checkedInvocationHash = requireDigest(invocationHash, "invocationHash");
        String checkedPlanHash = requireDigest(planPreimageHash, "planPreimageHash");
        String checkedGrantId = requireText(grantId, "grantId");
        Duration checkedLifetime = requireLifetime(lifetime);
        ProductionAuthorityTrustAnchor anchor = signer.trustAnchor();
        ProductionAuthorityBundle bundle = readAuthenticatedBundle(source, anchor);
        Instant issuedAt = clock.instant();
        Instant expiresAt;
        try {
            expiresAt = issuedAt.plus(checkedLifetime);
        } catch (RuntimeException exception) {
            throw new MigrationException("Authority Use Grant Expiry Is Invalid", exception);
        }
        AuthorityUseGrant grant = AuthorityUseGrant.issue(bundle, anchor, signer, source, checkedMigrationId,
            checkedInvocationHash, checkedPlanHash, issuedAt, expiresAt, checkedGrantId);
        return persistGrant(grant, signer);
    }

    AuthorityUseGrant issueUseGrantFromPlanPreimage(Path sourceRoot, String migrationId, String invocationHash,
                                                     Path planPreimage, String grantId) throws IOException {
        return identity.withProductionAuthority(signer -> {
            Path path = MigrationPaths.requirePath(planPreimage, "planPreimage");
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Plan Preimage Must Be A Regular File");
            }
            return issueUseGrant(sourceRoot, migrationId, invocationHash,
                AuthorityUseGrant.planPreimageHash(Files.readAllBytes(path)), grantId, MAX_GRANT_LIFETIME, signer);
        });
    }

    Path grantPath(String grantId, Path dataRoot) {
        String checkedGrantId = requireText(grantId, "grantId");
        String fileName = CanonicalHash.rawSha256(checkedGrantId.getBytes(StandardCharsets.UTF_8)) + ".json";
        return MigrationPaths.resolveInside(MigrationPaths.requirePath(dataRoot, "dataRoot"),
            GRANTS_RELATIVE_DIRECTORY + "/" + fileName);
    }

    private ProductionAuthorityBundle readAuthenticatedBundle(Path source,
                                                               ProductionAuthorityTrustAnchor anchor) throws IOException {
        Path path = ProductionAuthorityBundle.path(source);
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.read(path);
        if (!bundle.verifySignature(source, anchor)) {
            throw new MigrationException("Authority Use Grant Source Bundle Is Foreign Or Not Authenticated");
        }
        if (!bundle.freshAt(clock.instant())) {
            throw new MigrationException("Authority Use Grant Source Bundle Is Stale");
        }
        return bundle;
    }

    private AuthorityUseGrant persistGrant(AuthorityUseGrant grant, ProductionAuthorityKeyStore signer) throws IOException {
        Path dataRoot = signer.trustAnchorPath().getParent().getParent();
        Path grantsDirectory = MigrationPaths.resolveInside(dataRoot, GRANTS_RELATIVE_DIRECTORY);
        ensureDirectory(dataRoot, grantsDirectory);
        Path target = grantPath(grant.grantId(), dataRoot);
        byte[] bytes = grant.canonicalBytes();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return equalExisting(target, grant, bytes);
        }
        try {
            AtomicFiles.writeNew(target, bytes);
            return grant;
        } catch (FileAlreadyExistsException exception) {
            return equalExisting(target, grant, bytes);
        }
    }

    private AuthorityUseGrant equalExisting(Path target, AuthorityUseGrant expected, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Use Grant Target Is Not A Regular File");
        }
        byte[] existing = Files.readAllBytes(target);
        if (!Arrays.equals(existing, bytes)) {
            throw new MigrationException("Authority Use Grant Target Already Contains Different Content");
        }
        AuthorityUseGrant parsed;
        try {
            parsed = AuthorityUseGrant.fromCanonical(new String(existing, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new MigrationException("Authority Use Grant Target Is Invalid", exception);
        }
        if (!parsed.equals(expected)) {
            throw new MigrationException("Authority Use Grant Target Already Contains Different Content");
        }
        return parsed;
    }

    private static void ensureDirectory(Path dataRoot, Path grantsDirectory) throws IOException {
        Path authorityDirectory = grantsDirectory.getParent();
        if (authorityDirectory == null || !authorityDirectory.startsWith(dataRoot)) {
            throw new MigrationException("Authority Use Grant Directory Is Invalid");
        }
        MigrationPaths.requireNoSymlinkTraversal(dataRoot, grantsDirectory);
        if (!Files.exists(authorityDirectory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(authorityDirectory);
        }
        if (Files.isSymbolicLink(authorityDirectory) || !Files.isDirectory(authorityDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Use Grant Directory Is Invalid");
        }
        if (!Files.exists(grantsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(grantsDirectory);
        }
        if (Files.isSymbolicLink(grantsDirectory) || !Files.isDirectory(grantsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Use Grant Directory Is Invalid");
        }
    }

    private static Path canonicalDirectory(Path sourceRoot) throws IOException {
        return MigrationPaths.requireDirectory(sourceRoot, "sourceRoot").toRealPath();
    }

    private static Duration requireLifetime(Duration lifetime) {
        Duration checked = Objects.requireNonNull(lifetime, "lifetime");
        if (checked.isZero() || checked.isNegative() || checked.compareTo(MAX_GRANT_LIFETIME) > 0) {
            throw new IllegalArgumentException("Authority Use Grant Lifetime Must Be Between One Nanosecond And Five Minutes");
        }
        return checked;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " Is Required");
        }
        if (value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Contains A Control Character");
        }
        return value;
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    record VerifiedAuthorityMaterial(ProductionAuthorityTrustAnchor trustAnchor,
                                     ProductionAuthorityBundle.TrustedAuthority trustedAuthority) {
        VerifiedAuthorityMaterial {
            trustAnchor = Objects.requireNonNull(trustAnchor, "trustAnchor");
            trustedAuthority = Objects.requireNonNull(trustedAuthority, "trustedAuthority");
            if (!trustAnchor.serverId().equals(trustedAuthority.serverId())
                || !trustAnchor.installAuthorityHash().equals(trustedAuthority.installAuthorityHash())
                || !trustAnchor.publicKey().equals(trustedAuthority.trustedPublicKey())) {
                throw new IllegalArgumentException("Verified Authority Material Is Inconsistent");
            }
        }
    }
}
