package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.AuthorityUseGrant;
import restudio.resync.migration.ProductionAuthorityBundle;
import restudio.resync.migration.ProductionAuthorityTrustAnchor;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.SnapshotId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionAuthorityIssuerTest {
    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    @Test
    void issuerUsesTheIdentityOwnedAuthorityStore(@TempDir Path temporary) throws Exception {
        Path firstRoot = Files.createDirectories(temporary.resolve("first"));
        ServerIdentityStore firstIdentity = ServerIdentityStore.open(firstRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore firstStore = firstIdentity.productionAuthorityKeyStore();
        ProductionAuthorityIssuer firstIssuer = new ProductionAuthorityIssuer(firstIdentity,
            Clock.fixed(NOW, ZoneOffset.UTC));
        ProductionAuthorityIssuer repeatedIssuer = new ProductionAuthorityIssuer(firstIdentity,
            Clock.fixed(NOW, ZoneOffset.UTC));

        assertSame(firstStore, firstIssuer.authorityStore());
        assertSame(firstStore, repeatedIssuer.authorityStore());
        ProductionAuthorityTrustAnchor anchor = firstIssuer.exportTrustAnchor();
        assertEquals(anchor.publicKey(), firstIdentity.productionAuthoritySigner().signingPublicKey());

        ServerIdentityStore reopenedIdentity = ServerIdentityStore.open(firstRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityIssuer reopenedIssuer = new ProductionAuthorityIssuer(reopenedIdentity,
            Clock.fixed(NOW, ZoneOffset.UTC));
        assertNotSame(firstStore, reopenedIssuer.authorityStore());

        Path secondRoot = Files.createDirectories(temporary.resolve("second"));
        ServerIdentityStore secondIdentity = ServerIdentityStore.open(secondRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityIssuer secondIssuer = new ProductionAuthorityIssuer(secondIdentity,
            Clock.fixed(NOW, ZoneOffset.UTC));
        assertNotSame(firstStore, secondIssuer.authorityStore());
        assertNotEquals(firstIdentity.serverId(), secondIdentity.serverId());
    }

    @Test
    void bootstrapMaterialUsesOneVerifiedAnchorForTheTrustedAuthority(@TempDir Path temporary) throws Exception {
        Path root = Files.createDirectories(temporary.resolve("bootstrap-material"));
        ServerIdentityStore identity = ServerIdentityStore.open(root.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityIssuer issuer = new ProductionAuthorityIssuer(identity, Clock.fixed(NOW, ZoneOffset.UTC));

        ProductionAuthorityIssuer.VerifiedAuthorityMaterial material = issuer.verifiedAuthorityMaterial();

        assertEquals(material.trustAnchor().serverId(), material.trustedAuthority().serverId());
        assertEquals(material.trustAnchor().installAuthorityHash(), material.trustedAuthority().installAuthorityHash());
        assertEquals(material.trustAnchor().publicKey(), material.trustedAuthority().trustedPublicKey());
        assertTrue(material.trustedAuthority().matchesDurableInstall(root));
    }

    @Test
    void issuerUsesTheReboundIdentityStoreForExportsAndGrants(@TempDir Path temporary) throws Exception {
        Path activeRoot = Files.createDirectories(temporary.resolve("active"));
        ServerIdentityStore identity = ServerIdentityStore.open(activeRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityIssuer issuer = new ProductionAuthorityIssuer(identity, Clock.fixed(NOW, ZoneOffset.UTC));
        ProductionAuthorityKeyStore activeStore = issuer.authorityStore();
        ProductionAuthorityTrustAnchor activeAnchor = issuer.exportTrustAnchor();
        Path candidateRoot = Files.createDirectories(temporary.resolve("candidate"));
        Files.copy(identity.path(), candidateRoot.resolve(ServerIdentityStore.FILE_NAME));
        Files.copy(identity.installSignalPath(), candidateRoot.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE));
        Path candidateAnchorPath = candidateRoot.resolve(ProductionAuthorityKeyStore.TRUST_ANCHOR_RELATIVE_PATH);
        Files.createDirectories(candidateAnchorPath.getParent());
        Files.copy(issuer.trustAnchorPath(), candidateAnchorPath);

        identity.quiesce();
        identity.rebind(candidateRoot);
        identity.resume();

        ProductionAuthorityKeyStore reboundStore = issuer.authorityStore();
        assertNotSame(activeStore, reboundStore);
        assertSame(reboundStore, identity.productionAuthorityKeyStore());
        assertEquals(candidateAnchorPath, issuer.trustAnchorPath());
        ProductionAuthorityTrustAnchor reboundAnchor = issuer.exportTrustAnchor();
        assertEquals(activeAnchor.serverId(), reboundAnchor.serverId());
        assertEquals(activeAnchor.installationId(), reboundAnchor.installationId());
        assertEquals(activeAnchor.installAuthorityHash(), reboundAnchor.installAuthorityHash());
        assertEquals(activeAnchor.authorityKeyId(), reboundAnchor.authorityKeyId());
        assertEquals(activeAnchor.publicKeyFingerprint(), reboundAnchor.publicKeyFingerprint());
        assertEquals(activeAnchor.publicKey(), reboundAnchor.publicKey());
        assertEquals(activeAnchor.canonical(), reboundAnchor.canonical());

        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(identity.productionAuthoritySigner(),
            reboundAnchor, SnapshotId.deterministic("issuer-rebind-snapshot"), NOW, ContentHash.of("a".repeat(64)), 1,
            new CatalogVersion(1, 0), ContentHash.of("b".repeat(64)), 1, "c".repeat(64), 1);
        bundle.write(ProductionAuthorityBundle.path(candidateRoot));
        String planHash = AuthorityUseGrant.planPreimageHash("format=1\nplan\n".getBytes(StandardCharsets.UTF_8));
        AuthorityUseGrant grant = issuer.issueUseGrant(candidateRoot, "migration-rebind", "d".repeat(64),
            planHash, "grant-rebind");

        assertTrue(grant.verify(reboundAnchor, bundle, candidateRoot, "migration-rebind", "d".repeat(64),
            planHash, NOW));
        assertTrue(Files.isRegularFile(issuer.grantPath("grant-rebind", candidateRoot), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(issuer.grantPath("grant-rebind", activeRoot), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void rebindWaitsForAnIssuerOperationAndTheNextOperationUsesTheNewRoot(@TempDir Path temporary) throws Exception {
        Path activeRoot = Files.createDirectories(temporary.resolve("leased-active"));
        ServerIdentityStore identity = ServerIdentityStore.open(activeRoot.resolve(ServerIdentityStore.FILE_NAME));
        BlockingClock clock = new BlockingClock(NOW);
        ProductionAuthorityIssuer issuer = new ProductionAuthorityIssuer(identity, clock);
        ProductionAuthorityTrustAnchor activeAnchor = issuer.exportTrustAnchor();
        ProductionAuthorityBundle activeBundle = ProductionAuthorityBundle.create(identity.productionAuthoritySigner(),
            activeAnchor, SnapshotId.deterministic("issuer-lease-active"), NOW, ContentHash.of("a".repeat(64)), 1,
            new CatalogVersion(1, 0), ContentHash.of("b".repeat(64)), 1, "c".repeat(64), 1);
        activeBundle.write(ProductionAuthorityBundle.path(activeRoot));
        Path candidateRoot = Files.createDirectories(temporary.resolve("leased-candidate"));
        Files.copy(identity.path(), candidateRoot.resolve(ServerIdentityStore.FILE_NAME));
        Files.copy(identity.installSignalPath(), candidateRoot.resolve(ServerIdentityStore.INSTALL_SIGNAL_FILE));
        Path candidateAnchorPath = candidateRoot.resolve(ProductionAuthorityKeyStore.TRUST_ANCHOR_RELATIVE_PATH);
        Files.createDirectories(candidateAnchorPath.getParent());
        Files.copy(issuer.trustAnchorPath(), candidateAnchorPath);
        String planHash = AuthorityUseGrant.planPreimageHash("format=1\nplan\n".getBytes(StandardCharsets.UTF_8));
        CountDownLatch rebindStarted = new CountDownLatch(1);
        CountDownLatch rebindFinished = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var grantFuture = executor.submit(() -> issuer.issueUseGrant(activeRoot, "migration-active",
                "d".repeat(64), planHash, "grant-active"));
            assertTrue(clock.awaitBlocked());
            var rebindFuture = executor.submit(() -> {
                try {
                    identity.quiesce();
                    rebindStarted.countDown();
                    identity.rebind(candidateRoot);
                    identity.resume();
                } finally {
                    rebindFinished.countDown();
                }
                return null;
            });
            assertTrue(rebindStarted.await(10, TimeUnit.SECONDS));
            assertFalse(rebindFinished.await(200, TimeUnit.MILLISECONDS));
            clock.release();
            AuthorityUseGrant activeGrant = grantFuture.get(10, TimeUnit.SECONDS);
            rebindFuture.get(10, TimeUnit.SECONDS);

            assertTrue(activeGrant.verify(activeAnchor, activeBundle, activeRoot, "migration-active", "d".repeat(64),
                planHash, NOW));
            assertTrue(Files.isRegularFile(issuer.grantPath("grant-active", activeRoot), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(issuer.grantPath("grant-active", candidateRoot), LinkOption.NOFOLLOW_LINKS));

            ProductionAuthorityTrustAnchor reboundAnchor = issuer.exportTrustAnchor();
            ProductionAuthorityBundle reboundBundle = ProductionAuthorityBundle.create(identity.productionAuthoritySigner(),
                reboundAnchor, SnapshotId.deterministic("issuer-lease-rebound"), NOW, ContentHash.of("e".repeat(64)), 1,
                new CatalogVersion(1, 0), ContentHash.of("f".repeat(64)), 1, "0".repeat(64), 1);
            reboundBundle.write(ProductionAuthorityBundle.path(candidateRoot));
            AuthorityUseGrant reboundGrant = issuer.issueUseGrant(candidateRoot, "migration-rebound",
                "1".repeat(64), planHash, "grant-rebound");
            assertTrue(reboundGrant.verify(reboundAnchor, reboundBundle, candidateRoot, "migration-rebound",
                "1".repeat(64), planHash, NOW));
            assertTrue(Files.isRegularFile(issuer.grantPath("grant-rebound", candidateRoot), LinkOption.NOFOLLOW_LINKS));
        } finally {
            clock.release();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void issuesAnExactShortLivedGrantWithoutPrivateMaterial(@TempDir Path temporary) throws Exception {
        Fixture fixture = fixture(temporary.resolve("source"));
        AuthorityUseGrant grant = fixture.issuer.issueUseGrant(fixture.root, "migration-a", "d".repeat(64),
            fixture.planHash, "grant-a");
        assertTrue(grant.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-a", "d".repeat(64),
            fixture.planHash, NOW));
        assertEquals(fixture.bundle.bundleHash(), grant.bundleHash());
        assertEquals(fixture.bundle.snapshotId(), grant.snapshotId());
        assertEquals(fixture.issuer.grantPath("grant-a", fixture.root), fixture.issuer.grantPath("grant-a", fixture.root));
        assertTrue(Files.isRegularFile(fixture.issuer.grantPath("grant-a", fixture.root), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.readString(fixture.issuer.trustAnchorPath()).contains("private-key"));
        assertFalse(Files.readString(fixture.issuer.grantPath("grant-a", fixture.root)).contains("private-key"));
        assertEquals(NOW.plus(ProductionAuthorityIssuer.MAX_GRANT_LIFETIME), grant.expiresAt());
        assertEquals(grant, fixture.issuer.issueUseGrant(fixture.root, "migration-a", "d".repeat(64),
            fixture.planHash, "grant-a"));
    }

    @Test
    void rejectsForeignSourceBundleAndLongExpiry(@TempDir Path temporary) throws Exception {
        Fixture fixture = fixture(temporary.resolve("source"));
        Fixture foreign = fixture(temporary.resolve("foreign"));
        assertThrows(Exception.class, () -> fixture.issuer.issueUseGrant(foreign.root, "migration-a", "d".repeat(64),
            fixture.planHash, "foreign-grant"));
        assertThrows(IllegalArgumentException.class, () -> fixture.issuer.issueUseGrant(fixture.root, "migration-a",
            "d".repeat(64), fixture.planHash, "long-grant", Duration.ofMinutes(6)));
    }

    @Test
    void grantBindsPathMigrationInvocationAndPlan(@TempDir Path temporary) throws Exception {
        Fixture fixture = fixture(temporary.resolve("source"));
        AuthorityUseGrant grant = fixture.issuer.issueUseGrant(fixture.root, "migration-a", "d".repeat(64),
            fixture.planHash, "binding-grant");
        Path foreign = Files.createDirectory(temporary.resolve("other"));
        assertFalse(grant.verify(fixture.anchor, fixture.bundle, foreign, "migration-a", "d".repeat(64),
            fixture.planHash, NOW));
        assertFalse(grant.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-b", "d".repeat(64),
            fixture.planHash, NOW));
        assertFalse(grant.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-a", "e".repeat(64),
            fixture.planHash, NOW));
        assertFalse(grant.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-a", "d".repeat(64),
            "f".repeat(64), NOW));
    }

    private static Fixture fixture(Path root) throws Exception {
        Files.createDirectories(root);
        ServerIdentityStore identity = ServerIdentityStore.open(root.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityIssuer issuer = new ProductionAuthorityIssuer(identity,
            Clock.fixed(NOW, ZoneOffset.UTC));
        ProductionAuthorityTrustAnchor anchor = issuer.exportTrustAnchor();
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(identity.productionAuthoritySigner(), anchor,
            SnapshotId.deterministic("issuer-test-snapshot"), NOW, ContentHash.of("a".repeat(64)), 1,
            new CatalogVersion(1, 0), ContentHash.of("b".repeat(64)), 1, "c".repeat(64), 1);
        bundle.write(ProductionAuthorityBundle.path(root));
        String planHash = restudio.resync.migration.AuthorityUseGrant.planPreimageHash(
            "format=1\nplan\n".getBytes(StandardCharsets.UTF_8));
        return new Fixture(root, issuer, anchor, bundle, planHash);
    }

    private record Fixture(Path root, ProductionAuthorityIssuer issuer, ProductionAuthorityTrustAnchor anchor,
                           ProductionAuthorityBundle bundle, String planHash) {
    }

    private static final class BlockingClock extends Clock {
        private final Instant instant;
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean firstRead = new AtomicBoolean(true);

        private BlockingClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return ZoneOffset.UTC.equals(zone) ? this : Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            if (firstRead.compareAndSet(true, false)) {
                blocked.countDown();
                try {
                    released.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Blocking Clock Was Interrupted", exception);
                }
            }
            return instant;
        }

        private boolean awaitBlocked() throws InterruptedException {
            return blocked.await(10, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }
    }
}
