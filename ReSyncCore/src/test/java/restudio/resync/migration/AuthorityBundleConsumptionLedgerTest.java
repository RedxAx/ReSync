package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityBundleConsumptionLedgerTest {
    @TempDir
    Path temporary;

    @Test
    void reservesCommitsAndRetriesTheExactMigrationIdempotently() throws Exception {
        Fixture fixture = fixture("exact");
        AuthorityBundleConsumptionLedger.Binding binding = fixture.binding();

        AuthorityBundleConsumptionLedger.Reservation reserved = AuthorityBundleConsumptionLedger.reserve(binding);
        assertEquals(AuthorityBundleConsumptionLedger.State.RESERVED, reserved.state());
        assertFalse(reserved.existing());

        AuthorityBundleConsumptionLedger.commit(binding);
        AuthorityBundleConsumptionLedger.Reservation retried = AuthorityBundleConsumptionLedger.reserve(binding);
        assertEquals(AuthorityBundleConsumptionLedger.State.COMMITTED, retried.state());
        assertTrue(retried.existing());

        AuthorityBundleConsumptionLedger.commit(binding);
        assertEquals(AuthorityBundleConsumptionLedger.State.COMMITTED,
            AuthorityBundleConsumptionLedger.reserve(binding).state());
    }

    @Test
    void rejectsTamperingEvenWhenAnAttackerRewritesTheUnkeyedDigest() throws Exception {
        Fixture fixture = fixture("tamper");
        AuthorityBundleConsumptionLedger.Binding binding = fixture.binding();
        AuthorityBundleConsumptionLedger.reserve(binding);

        Path ledger = AuthorityBundleConsumptionLedger.path(fixture.source(), fixture.grant().grantHash());
        String content = Files.readString(ledger);
        String tampered = content.replace("grant-hash=" + fixture.grant().grantHash(), "grant-hash=" + "a".repeat(64));
        Files.writeString(ledger, tampered);

        assertThrows(MigrationException.class, () -> AuthorityBundleConsumptionLedger.reserve(binding));
    }

    @Test
    void rejectsForeignMigrationAndSourceIdentity() throws Exception {
        Fixture fixture = fixture("foreign");
        AuthorityBundleConsumptionLedger.Binding binding = fixture.binding();
        AuthorityBundleConsumptionLedger.reserve(binding);

        AuthorityUseGrant changed = fixture.grant("migration-2", fixture.grant().canonicalSourcePath());
        assertFalse(AuthorityBundleConsumptionLedger.path(fixture.source(), changed.grantHash())
            .equals(AuthorityBundleConsumptionLedger.path(fixture.source(), fixture.grant().grantHash())));
    }

    @Test
    void rejectsAClonedControlRootAndAClonedSourceRoot() throws Exception {
        Fixture fixture = fixture("clone");
        AuthorityBundleConsumptionLedger.Binding binding = fixture.binding();
        AuthorityBundleConsumptionLedger.reserve(binding);

        Path clonedSource = fixture.parent().resolve("source-clone");
        copyTree(fixture.source(), clonedSource);
        assertThrows(MigrationException.class, () -> AuthorityBundleConsumptionLedger.reserve(
            new AuthorityBundleConsumptionLedger.Binding(clonedSource, fixture.bundle(), fixture.grant())));

        assertEquals(AuthorityBundleConsumptionLedger.path(fixture.source(), fixture.grant().grantHash()),
            AuthorityBundleConsumptionLedger.path(fixture.source(), fixture.grant().grantHash()));
    }

    private Fixture fixture(String name) throws Exception {
        Path parent = Files.createDirectory(temporary.resolve(name));
        Path source = Files.createDirectory(parent.resolve("source"));
        Path control = Files.createDirectory(parent.resolve("control"));
        ServerId serverId = ServerId.deterministic("ledger-" + name);
        Files.writeString(source.resolve("server-id"), serverId.canonicalText() + "\n");
        Files.writeString(source.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "install-authority-" + name);
        ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(serverId, source);
        ProductionAuthorityTrustAnchor anchor = ProductionAuthorityTrustAnchor.pinned(serverId,
            "installation-" + name, ProductionAuthorityBundle.installAuthorityDigest(source), "authority-key-" + name,
            signer.signingPublicKey());
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(signer, anchor, SnapshotId.deterministic("snapshot-" + name),
            Instant.parse("2026-08-17T00:00:00Z"), ContentHash.of("1".repeat(64)), 1,
            new CatalogVersion(1, 0), ContentHash.of("2".repeat(64)), 1, "3".repeat(64), 1);
        AuthorityUseGrant grant = AuthorityUseGrant.issue(bundle, anchor, signer, source, "migration-1",
            "4".repeat(64), AuthorityUseGrant.planPreimageHash("format=1\nplan\n"),
            Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-18T00:00:00Z"), "grant-" + name);
        return new Fixture(parent, source, control, signer, bundle, anchor, grant);
    }

    private static void copyTree(Path source, Path target) throws Exception {
        Files.walk(source).forEach(path -> {
            try {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private record Fixture(Path parent, Path source, Path control, ProductionAuthorityTestSigner signer,
                           ProductionAuthorityBundle bundle,
                           ProductionAuthorityTrustAnchor anchor, AuthorityUseGrant grant) {
        private AuthorityBundleConsumptionLedger.Binding binding() {
            return new AuthorityBundleConsumptionLedger.Binding(source, bundle, grant);
        }

        private AuthorityUseGrant grant(String migrationId, String canonicalSourcePath) throws Exception {
            return AuthorityUseGrant.issue(bundle, anchor, signer,
                canonicalSourcePath, AuthorityUseGrant.sourceIdentityDigest(source, bundle), migrationId,
                grant.invocationHash(), grant.planPreimageHash(), grant.issuedAt(), grant.expiresAt(), grant.grantId() + "-" + migrationId);
        }
    }
}
