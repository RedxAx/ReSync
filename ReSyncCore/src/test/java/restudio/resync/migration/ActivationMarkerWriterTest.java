package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivationMarkerWriterTest {
    @TempDir
    Path temporary;

    @Test
    void unsignedBindingFailsClosed() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("replacement"));
        MigrationPlan plan = new MigrationPlan(
            "snapshot",
            "a".repeat(64),
            1,
            2,
            "b".repeat(64),
            List.of());
        StagedMigration staged = new StagedMigration(root, Optional.empty(), plan.planHash(), "c".repeat(64));
        ActivationMarkerWriter.Binding binding = new ActivationMarkerWriter.Binding(
            "d".repeat(64), "e".repeat(64), 1, "f".repeat(64), 1);

        assertThrows(MigrationException.class, () -> ActivationMarkerWriter.replacementRoot(binding).write(plan, staged));
    }

    @Test
    void signedAuthorityBindingProducesFormatFiveMarkerWithEveryField() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("authority-replacement"));
        String installationId = "installation-marker";
        String authorityKeyId = "authority-key-marker";
        ServerId serverId = ServerId.deterministic("marker-server");
        SnapshotId snapshotId = SnapshotId.deterministic("marker-snapshot");
        Files.writeString(root.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), installationId);
        Files.writeString(root.resolve("server-id"), serverId.canonicalText() + "\n");
        String invocationHash = "1".repeat(64);
        MigrationPlan plan = new MigrationPlan(snapshotId.canonicalText(), "a".repeat(64), 1, 2,
            "b".repeat(64), List.of(), invocationHash);
        StagedMigration staged = new StagedMigration(root, Optional.empty(), plan.planHash(), "c".repeat(64));
        ProductionAuthorityTestSigner signer = new ProductionAuthorityTestSigner(serverId, root);
        ProductionAuthorityTrustAnchor anchor = ProductionAuthorityTrustAnchor.pinned(serverId, installationId,
            ProductionAuthorityBundle.installAuthorityDigest(root), authorityKeyId, signer.signingPublicKey());
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(
            signer, anchor, snapshotId,
            Instant.parse("2026-08-17T00:00:00Z"), ContentHash.of("d".repeat(64)), 7,
            new CatalogVersion(4, 2), ContentHash.of("e".repeat(64)), 3, "f".repeat(64), 5);
        AuthorityUseGrant grant = AuthorityUseGrant.issue(bundle, anchor, signer, root, "marker-migration",
            invocationHash, AuthorityUseGrant.planPreimageHash(plan.canonicalText()),
            Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-18T00:00:00Z"), "grant-marker");

        ActivationMarkerWriter.replacementRoot(new ActivationMarkerWriter.Binding(bundle, anchor, grant)).write(plan, staged);

        MigrationActivationMarker.Values marker = MigrationActivationMarker.read(root);
        assertEquals(bundle, marker.authorityBundle());
        assertEquals(grant, marker.authorityUseGrant());
        assertEquals(MigrationCanonical.sha256(anchor.canonicalBytes()), marker.authorityTrustAnchorHash());
        assertTrue(Files.readString(MigrationActivationMarker.markerPath(root)).startsWith("format=5\n"));
    }

    @Test
    void markerParserRejectsWhitespaceTrailingAndTruncatedContent() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("strict-marker"));
        MigrationActivationMarker.Values values = new MigrationActivationMarker.Values(
            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
            "e".repeat(64), 1, "f".repeat(64), 1, true);
        MigrationActivationMarker.write(root, values);
        Path marker = MigrationActivationMarker.markerPath(root);
        String canonical = Files.readString(marker);

        Files.writeString(marker, canonical + "\n");
        assertThrows(MigrationException.class, () -> MigrationActivationMarker.read(root));

        Files.writeString(marker, canonical.replace("runtime-binding-manifest-version=1", "runtime-binding-manifest-version=1 "));
        assertThrows(MigrationException.class, () -> MigrationActivationMarker.read(root));

        Files.writeString(marker, canonical.substring(0, canonical.length() - 1));
        assertThrows(MigrationException.class, () -> MigrationActivationMarker.read(root));
    }

    @Test
    void formatOneCannotBeWrittenOrAccepted() throws Exception {
        assertThrows(NoSuchMethodException.class, () -> MigrationActivationMarker.class.getMethod(
            "write", Path.class, String.class, String.class, String.class));

        Path root = Files.createDirectory(temporary.resolve("legacy-marker"));
        Path marker = MigrationActivationMarker.markerPath(root);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "format=1\nstate=COMMITTED\n");

        MigrationException exception = assertThrows(MigrationException.class, () -> MigrationActivationMarker.read(root));
        assertEquals("Legacy Activation Marker Format 1 Is Not Accepted", exception.getMessage());
        assertFalse(Files.exists(root.resolve("assets/.migrations/replacement-activation.marker.tmp")));
    }
}
