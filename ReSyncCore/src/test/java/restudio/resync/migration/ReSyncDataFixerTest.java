package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.upgrade.AssetAdoptionArtifactProducer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReSyncDataFixerTest {
    @TempDir
    Path temporary;

    @Test
    void appliesEveryRequiredFixAndAtomicallyPublishesTheResult() throws Exception {
        Path coordination = temporary.resolve("coordination");
        Path source = coordination.resolve("active-roots/source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("value.txt"), "one", StandardCharsets.UTF_8);
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(coordination.resolve("restore-control"));
        roots.activate(new StagedMigration(source, Optional.empty(), "1".repeat(64), TreeDigest.of(source)));

        ReSyncDataFixer versionOne = new ReSyncDataFixer(1, List.of());
        versionOne.prepare(source, coordination, true, roots);
        ReSyncDataFixer fixer = new ReSyncDataFixer(3, List.of(
            fix("one-to-two", 1, "two"),
            fix("two-to-three", 2, "three")));

        ReSyncDataFixer.Result result = fixer.prepare(source, coordination, false, roots);

        assertTrue(result.changed());
        assertEquals(List.of("one-to-two", "two-to-three"), result.appliedFixes());
        assertEquals("three", Files.readString(result.activeRoot().resolve("value.txt"), StandardCharsets.UTF_8));
        assertEquals(Optional.of(result.activeRoot()), roots.activeRoot());
        assertEquals("one", Files.readString(source.resolve("value.txt"), StandardCharsets.UTF_8));
        Path report;
        try (var reports = Files.list(result.activeRoot().resolve(ReSyncDataFixer.VERSION_DIRECTORY))) {
            report = reports.filter(path -> ReSyncDataFixer.isDataFixReportName(path.getFileName().toString()))
                .findFirst().orElseThrow();
        }
        ReSyncDataFixer.validateReport(report);

        ReSyncDataFixer.Result repeated = fixer.prepare(result.activeRoot(), coordination, false, roots);
        assertFalse(repeated.changed());
        assertEquals(result.activeRoot(), repeated.activeRoot());
    }

    @Test
    void rejectsAChainWithAMissingVersion() throws Exception {
        Path coordination = temporary.resolve("missing-coordination");
        Path source = coordination.resolve("active-roots/source");
        Files.createDirectories(source);
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(coordination.resolve("restore-control"));
        roots.activate(new StagedMigration(source, Optional.empty(), "2".repeat(64), TreeDigest.of(source)));
        new ReSyncDataFixer(1, List.of()).prepare(source, coordination, true, roots);

        assertThrows(IllegalArgumentException.class,
            () -> new ReSyncDataFixer(3, List.of(fix("two-to-three", 2, "three"))));
        assertEquals(Optional.of(source), roots.activeRoot());
    }

    @Test
    void rejectsAmbiguousFixIdentitiesAtRegistryComposition() {
        assertThrows(IllegalArgumentException.class,
            () -> new ReSyncDataFixer(2, List.of(fix("Invalid Fix", 1, "two"))));
        assertThrows(IllegalArgumentException.class, () -> new ReSyncDataFixer(3, List.of(
            fix("same-fix", 1, "two"),
            fix("same-fix", 2, "three"))));
    }

    @Test
    void coordinatorClearsBootstrapRecoveryAndRebindsAuthorityToTheFixedRoot() throws Exception {
        Path source = temporary.resolve("coordinated-source");
        Path coordination = temporary.resolve("coordinated-coordination");
        Files.createDirectories(source);
        Files.writeString(source.resolve("value.txt"), "one", StandardCharsets.UTF_8);
        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = ReSyncPersistenceCoordinator.bootstrapPrepared(
            source, coordination);
        ReSyncPersistenceCoordinator coordinator = prepared.coordinator();

        coordinator.prepareDataFixes(new ReSyncDataFixer(1, List.of()), true);
        ReSyncDataFixer.Result result = coordinator.prepareDataFixes(
            new ReSyncDataFixer(2, List.of(fix("one-to-two", 1, "two"))), false);

        assertTrue(result.changed());
        assertEquals(result.activeRoot(), coordinator.activeDataRoot());
        assertEquals(result.activeRoot(), new AuthorityEpochStore(coordination).boundRoot());
        assertEquals(2, ReSyncDataFixer.installedVersion(result.activeRoot()).orElseThrow());
        assertEquals("two", Files.readString(result.activeRoot().resolve("value.txt"), StandardCharsets.UTF_8));
        assertEquals("one", Files.readString(prepared.activeRoot().resolve("value.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void freshProofCanResumeAndConsumeAfterTheBaselineMarkerIsWritten() throws Exception {
        Path source = temporary.resolve("fresh-source");
        Path coordination = temporary.resolve("fresh-coordination");
        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = ReSyncPersistenceCoordinator.bootstrapPrepared(
            source, coordination);
        ReSyncPersistenceCoordinator coordinator = prepared.coordinator();
        FreshRootProvenance provenance = coordinator.freshRootProvenance().orElseThrow();
        AssetAdoptionArtifactProducer.Result artifact = AssetAdoptionArtifactProducer.produceEmptyUnconsumed(
            coordination, provenance);

        coordinator.prepareDataFixes(new ReSyncDataFixer(1, List.of()), true);
        AssetAdoptionArtifactProducer.Result resumed = AssetAdoptionArtifactProducer.produceEmptyUnconsumed(
            coordination, provenance);
        provenance.consume(resumed.artifactHash());

        assertEquals(artifact.artifactHash(), resumed.artifactHash());
        FreshRootProvenance.verifyConsumed(coordination, prepared.activeRoot(), provenance.proofHash(),
            artifact.artifactHash());
    }

    private static ReSyncDataFix fix(String id, int sourceVersion, String value) {
        return new ReSyncDataFix() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int sourceVersion() {
                return sourceVersion;
            }

            @Override
            public void apply(Context context) throws java.io.IOException {
                AtomicFiles.write(context.root().resolve("value.txt"), value.getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
