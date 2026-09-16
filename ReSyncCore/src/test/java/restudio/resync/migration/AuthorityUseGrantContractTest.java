package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityUseGrantContractTest {
    @TempDir
    Path temporary;

    @Test
    void pinnedAnchorAndBundleFieldsAreRequiredForGrantVerification() throws Exception {
        Fixture fixture = fixture();
        assertTrue(fixture.bundle.verifySignature(fixture.anchor));
        assertTrue(fixture.grant.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-a",
            fixture.invocationHash, fixture.planPreimageHash, fixture.now));

        KeyPair forgedKey = KeyPairGenerator.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM).generateKeyPair();
        ProductionAuthorityTrustAnchor forgedAnchor = ProductionAuthorityTrustAnchor.pinned(
            fixture.serverId, fixture.anchor.installationId(), fixture.anchor.installAuthorityHash(),
            fixture.anchor.authorityKeyId(), Base64.getEncoder().encodeToString(forgedKey.getPublic().getEncoded()));
        assertFalse(fixture.bundle.verifySignature(forgedAnchor));
        assertFalse(fixture.grant.verify(forgedAnchor, fixture.bundle, fixture.root, "migration-a",
            fixture.invocationHash, fixture.planPreimageHash, fixture.now));
    }

    @Test
    void grantBindsPathMigrationInvocationAndPlanPreimage() throws Exception {
        Fixture fixture = fixture();
        Path foreign = Files.createDirectory(temporary.resolve("foreign"));
        assertFalse(fixture.grant.verify(fixture.anchor, fixture.bundle, foreign, "migration-a",
            fixture.invocationHash, fixture.planPreimageHash, fixture.now));
        assertFalse(fixture.grant.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-b",
            fixture.invocationHash, fixture.planPreimageHash, fixture.now));
        assertFalse(fixture.grant.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-a",
            "e".repeat(64), fixture.planPreimageHash, fixture.now));
        assertFalse(fixture.grant.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-a",
            fixture.invocationHash, "f".repeat(64), fixture.now));
    }

    @Test
    void futureAndExpiredGrantsAreRejectedAndReplayIsLocal() throws Exception {
        Fixture fixture = fixture();
        AuthorityUseGrant future = AuthorityUseGrant.issue(fixture.bundle, fixture.anchor, fixture.signer, fixture.root,
            "migration-a", fixture.invocationHash, fixture.planPreimageHash, fixture.now.plusSeconds(10),
            fixture.now.plusSeconds(30), "future-grant");
        AuthorityUseGrant expired = AuthorityUseGrant.issue(fixture.bundle, fixture.anchor, fixture.signer, fixture.root,
            "migration-a", fixture.invocationHash, fixture.planPreimageHash, fixture.now.minusSeconds(30),
            fixture.now.minusSeconds(10), "expired-grant");
        assertFalse(future.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-a", fixture.invocationHash,
            fixture.planPreimageHash, fixture.now));
        assertFalse(expired.verify(fixture.anchor, fixture.bundle, fixture.root, "migration-a", fixture.invocationHash,
            fixture.planPreimageHash, fixture.now));

        AuthorityUseGrant.ReplayGuard replay = new AuthorityUseGrant.ReplayGuard();
        assertTrue(fixture.grant.acceptOnce(replay, fixture.anchor, fixture.bundle, fixture.root, "migration-a",
            fixture.invocationHash, fixture.planPreimageHash, fixture.now));
        assertFalse(fixture.grant.acceptOnce(replay, fixture.anchor, fixture.bundle, fixture.root, "migration-a",
            fixture.invocationHash, fixture.planPreimageHash, fixture.now));
    }

    @Test
    void canonicalGrantParsingRejectsUnknownAndTamperedFields() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> unknown = new LinkedHashMap<>(fixture.grant.canonicalValue());
        unknown.put("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> AuthorityUseGrant.fromCanonical(
            JsonValue.fromJava(unknown).canonicalText()));

        Map<String, Object> tampered = new LinkedHashMap<>(fixture.grant.canonicalValue());
        tampered.put("migrationId", "foreign-migration");
        assertThrows(IllegalArgumentException.class, () -> AuthorityUseGrant.fromCanonical(
            JsonValue.fromJava(tampered).canonicalText()));
    }

    private Fixture fixture() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("source-" + System.nanoTime()));
        ServerId serverId = ServerId.deterministic("grant-server");
        SnapshotId snapshotId = SnapshotId.deterministic("grant-snapshot");
        Files.writeString(root.resolve("server-id"), serverId.canonicalText() + "\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "authority-grant",
            StandardCharsets.UTF_8);
        ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(serverId, root);
        ProductionAuthorityTrustAnchor anchor = ProductionAuthorityTrustAnchor.pinned(serverId, "installation-a",
            ProductionAuthorityBundle.installAuthorityDigest(root), "authority-key-a", signer.signingPublicKey());
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(signer, anchor, snapshotId, Instant.parse("2026-08-17T00:00:00Z"),
            ContentHash.of("a".repeat(64)), 1, new CatalogVersion(1, 0), ContentHash.of("b".repeat(64)), 1,
            "c".repeat(64), 1);
        Instant now = Instant.parse("2026-08-17T00:01:00Z");
        String invocationHash = "d".repeat(64);
        String planPreimageHash = AuthorityUseGrant.planPreimageHash("format=1\nplan\n");
        AuthorityUseGrant grant = AuthorityUseGrant.issue(bundle, anchor, signer, root, "migration-a", invocationHash,
            planPreimageHash, now.minusSeconds(10), now.plusSeconds(60), "grant-a");
        return new Fixture(root, serverId, signer, anchor, bundle, grant, invocationHash, planPreimageHash, now);
    }

    private record Fixture(
        Path root,
        ServerId serverId,
        ProductionAuthorityTestSigner signer,
        ProductionAuthorityTrustAnchor anchor,
        ProductionAuthorityBundle bundle,
        AuthorityUseGrant grant,
        String invocationHash,
        String planPreimageHash,
        Instant now
    ) {
    }
}
