package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

class MigrationJournalIntegrityTest {
    @TempDir
    Path temporary;

    @Test
    void formatTwoJournalBindsIdentityHistoryAndDetailToTheWholeFileHash() throws IOException {
        Path path = temporary.resolve("bound.journal");
        MigrationJournal journal = createBound(path);
        journal.transition(MigrationJournalState.PREPARED, "prepared");
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming");

        String original = Files.readString(path);
        assertTrue(original.startsWith("format=2\n"));
        assertTrue(original.contains("journal-hash="));
        assertEquals(journal.entries(), MigrationJournal.open(path).entries());

        Files.writeString(path, original.replace(
            "migration-id=" + MigrationCanonical.encode("migration-id"),
            "migration-id=" + MigrationCanonical.encode("other-migration")));
        assertThrows(MigrationException.class, () -> MigrationJournal.open(path));

        Files.writeString(path, original.replace(
            MigrationCanonical.encode("transforming"),
            MigrationCanonical.encode("tampered-detail")));
        assertThrows(MigrationException.class, () -> MigrationJournal.open(path));
    }

    @Test
    void formatTwoJournalRejectsExtraLinesAndNonCanonicalEncoding() throws IOException {
        Path extraPath = temporary.resolve("extra.journal");
        createBound(extraPath);
        String original = Files.readString(extraPath);
        Files.writeString(extraPath, original + "extra=content\n");
        assertThrows(MigrationException.class, () -> MigrationJournal.open(extraPath));

        Path encodedPath = temporary.resolve("encoded.journal");
        createBound(encodedPath);
        String encoded = Files.readString(encodedPath);
        Files.writeString(encodedPath, encoded.replace(
            "migration-id=" + MigrationCanonical.encode("migration-id"),
            "migration-id=" + MigrationCanonical.encode("migration-id") + "="));
        assertThrows(MigrationException.class, () -> MigrationJournal.open(encodedPath));

        Path lineEndingPath = temporary.resolve("line-ending.journal");
        createBound(lineEndingPath);
        String lineEnding = Files.readString(lineEndingPath).replace("\n", "\r\n");
        Files.writeString(lineEndingPath, lineEnding);
        assertThrows(MigrationException.class, () -> MigrationJournal.open(lineEndingPath));
    }

    @Test
    void authorityBundleJournalRetainsTheFullAuthorityIdentity() throws Exception {
        Path path = temporary.resolve("authority.journal");
        Files.writeString(temporary.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "authority");
        ServerId serverId = ServerId.deterministic("journal-server");
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(
            new ProductionAuthorityTestSigner(serverId, temporary), serverId, SnapshotId.deterministic("journal-snapshot"),
            Instant.parse("2026-08-17T00:00:00Z"), ContentHash.of("9".repeat(64)), 9,
            new CatalogVersion(5, 1), ContentHash.of("a".repeat(64)), 2, "b".repeat(64), 4);
        MigrationJournal.Binding binding = new MigrationJournal.Binding(
            "snapshot-id", "2".repeat(64), "3".repeat(64), "4".repeat(64), "", "5".repeat(64),
            bundle.catalogContentChecksum().canonicalText(), bundle.runtimeBindingManifestHash().canonicalText(),
            bundle.runtimeBindingManifestVersion(), bundle.readinessReportHash(), bundle.readinessReportVersion(), bundle);

        MigrationJournal journal = MigrationJournal.create(path, "migration-id", "1".repeat(64), binding);
        MigrationJournal opened = MigrationJournal.open(path);

        assertEquals(4, Integer.parseInt(Files.readString(path).lines().findFirst().orElseThrow().substring("format=".length())));
        assertEquals(bundle, opened.binding().orElseThrow().authorityBundle());
        assertEquals(journal.binding(), opened.binding());
    }

    private static MigrationJournal createBound(Path path) throws IOException {
        return MigrationJournal.create(path, "migration-id", "1".repeat(64), new MigrationJournal.Binding(
            "snapshot-id",
            "2".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "",
            "5".repeat(64),
            "6".repeat(64),
            "7".repeat(64),
            1,
            "8".repeat(64),
            1));
    }
}
