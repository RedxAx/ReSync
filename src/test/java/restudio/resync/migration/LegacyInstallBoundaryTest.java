package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.install.ReSyncInstallationStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LegacyInstallBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsPreRewriteDataWithoutExplicitArchiveRequest() throws Exception {
        Path data = temporary.resolve("ReSync");
        Files.createDirectories(data.resolve("assets"));
        Files.writeString(data.resolve("assets/legacy.json"), "{}");

        assertThrows(MigrationException.class,
            () -> LegacyInstallBoundary.prepare(data, temporary.resolve(".resync-coordination")));
        assertTrue(Files.exists(data.resolve("assets/legacy.json")));
        ReSyncInstallationStatus status = ReSyncInstallationStatus.decode(
            Files.readString(temporary.resolve(LegacyInstallBoundary.STATUS_FILE)));
        assertTrue(status.blocksStartup());
        assertTrue(status.preservesLegacyData());
    }

    @Test
    void archivesPreRewriteDataAndStartsFromAnAbsentRoot() throws Exception {
        Path data = temporary.resolve("ReSync");
        Path coordination = temporary.resolve(".resync-coordination");
        Files.createDirectories(data.resolve("assets"));
        Files.createDirectories(coordination);
        Files.writeString(data.resolve("assets/legacy.json"), "{}");
        Files.writeString(coordination.resolve("partial"), "partial");
        Files.writeString(temporary.resolve(LegacyInstallBoundary.RESET_MARKER), "");

        LegacyInstallBoundary.Result result = LegacyInstallBoundary.prepare(data, coordination);

        assertTrue(result.archived());
        assertTrue(Files.exists(result.dataBackup().resolve("assets/legacy.json")));
        assertTrue(Files.exists(result.dataBackup().resolve("ReSync Legacy Archive.txt")));
        assertTrue(Files.exists(result.coordinationBackup().resolve("partial")));
        assertFalse(Files.exists(data));
        assertFalse(Files.exists(coordination));
        assertFalse(Files.exists(temporary.resolve(LegacyInstallBoundary.RESET_MARKER)));
        ReSyncInstallationStatus status = ReSyncInstallationStatus.decode(
            Files.readString(temporary.resolve(LegacyInstallBoundary.STATUS_FILE)));
        assertFalse(status.blocksStartup());
        assertTrue(status.archivePath().startsWith("ReSync Legacy Backup "));
    }

    @Test
    void resumesAnArchiveInterruptedAfterTheDataMove() throws Exception {
        String token = UUID.randomUUID().toString();
        Path dataBackup = temporary.resolve("ReSync Legacy Backup " + token);
        Path coordination = temporary.resolve(".resync-coordination");
        Files.createDirectories(dataBackup);
        Files.writeString(dataBackup.resolve("legacy.json"), "{}");
        Files.createDirectories(coordination);
        Files.writeString(coordination.resolve("partial"), "partial");
        Files.writeString(temporary.resolve(LegacyInstallBoundary.RESET_MARKER), token);

        LegacyInstallBoundary.Result result = LegacyInstallBoundary.prepare(temporary.resolve("ReSync"), coordination);

        assertTrue(result.archived());
        assertTrue(Files.exists(result.dataBackup().resolve("legacy.json")));
        assertTrue(Files.exists(result.coordinationBackup().resolve("partial")));
        assertFalse(Files.exists(temporary.resolve(LegacyInstallBoundary.RESET_MARKER)));
    }

    @Test
    void acceptsOnlyAValidatedVersionedActiveRootAsCurrent() throws Exception {
        Path data = temporary.resolve("ReSync");
        Path coordination = temporary.resolve(".resync-coordination");
        Path active = coordination.resolve("active-roots/current");
        Files.createDirectories(data);
        Files.createDirectories(active);
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(coordination.resolve("restore-control"));
        roots.activate(new StagedMigration(active, Optional.empty(), "3".repeat(64), TreeDigest.of(active)));
        new ReSyncDataFixer(1, List.of()).prepare(active, coordination, true, roots);

        LegacyInstallBoundary.Result result = LegacyInstallBoundary.prepare(data, coordination);
        MigrationReportsPersistenceParticipant reports = new MigrationReportsPersistenceParticipant(active);
        reports.admit();
        reports.healthCheck();

        assertFalse(result.archived());
        assertTrue(reports.owns(ReSyncDataFixer.versionPath(active)));
        assertFalse(Files.exists(temporary.resolve(LegacyInstallBoundary.STATUS_FILE)));
    }

    @Test
    void archivesAnUnversionedActiveRootWhenTheSourceRootIsAbsent() throws Exception {
        Path coordination = temporary.resolve(".resync-coordination");
        Path active = coordination.resolve("active-roots/legacy");
        Files.createDirectories(active);
        Files.writeString(active.resolve("legacy.json"), "{}");
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(coordination.resolve("restore-control"));
        roots.activate(new StagedMigration(active, Optional.empty(), "4".repeat(64), TreeDigest.of(active)));
        Files.writeString(temporary.resolve(LegacyInstallBoundary.RESET_MARKER), "");

        LegacyInstallBoundary.Result result = LegacyInstallBoundary.prepare(temporary.resolve("ReSync"), coordination);

        assertTrue(result.archived());
        assertTrue(Files.exists(result.dataBackup().resolve("ReSync Legacy Archive.txt")));
        assertTrue(Files.exists(result.coordinationBackup().resolve("active-roots/legacy/legacy.json")));
    }

    @Test
    void rejectsAnOversizedArchiveMarkerWithoutMovingLegacyData() throws Exception {
        Path data = temporary.resolve("ReSync");
        Files.createDirectories(data);
        Files.writeString(data.resolve("legacy.json"), "{}");
        Files.writeString(temporary.resolve(LegacyInstallBoundary.RESET_MARKER), "x".repeat(129));

        assertThrows(MigrationException.class,
            () -> LegacyInstallBoundary.prepare(data, temporary.resolve(".resync-coordination")));
        assertTrue(Files.exists(data.resolve("legacy.json")));
    }
}
