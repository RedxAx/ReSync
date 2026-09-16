package restudio.resync.migration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.server.ReSyncPersistenceTopology;
import restudio.resync.server.ReSyncUncoveredWriterInventory;
import restudio.resync.server.coverage.PersistenceWriterCoverageBoundary;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationReportsPersistenceParticipantTest {
    @TempDir
    Path temporaryDirectory;
    private final List<ReSyncJsonResourceStorage> storages = new ArrayList<>();
    private final List<AssetPersistenceGate> assetsGates = new ArrayList<>();
    private final List<AssetTransactionCoordinator> coordinators = new ArrayList<>();

    @Test
    void writesAndValidatesCanonicalCatalogRebindReport() throws Exception {
        MigrationReportsPersistenceParticipant participant =
            new MigrationReportsPersistenceParticipant(temporaryDirectory);
        participant.admit();
        String report = "{\"format\":\"core-catalog-binding-rebind-report-v1\",\"items\":[],"
            + "\"manifestHash\":\"" + "a".repeat(64) + "\",\"migrationId\":\"core-catalog-binding-rebind-v1\","
            + "\"planHash\":\"" + "b".repeat(64) + "\",\"sourceBinding\":\"53|" + "c".repeat(64)
            + "|" + "d".repeat(64) + "\",\"targetBinding\":\"54|" + "e".repeat(64) + "|"
            + "f".repeat(64) + "\"}";

        participant.writeCatalogRebindReport(report);
        participant.healthCheck();

        assertEquals(report, Files.readString(participant.root().resolve(
            MigrationReportsPersistenceParticipant.CATALOG_REBIND_REPORT_FILE)));
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.writeCatalogRebindReport(report));
        participant.writeCatalogRebindRecoveryReport(report);
        assertEquals(report, participant.readCatalogRebindReport());
        participant.resume();
        participant.close();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            for (AssetPersistenceGate assetsGate : assetsGates) {
                assetsGate.quiesce();
            }
            for (ReSyncJsonResourceStorage storage : storages.reversed()) {
                storage.closePersistence();
            }
            for (AssetTransactionCoordinator coordinator : coordinators.reversed()) {
                coordinator.close();
            }
        } finally {
            storages.clear();
            assetsGates.clear();
            coordinators.clear();
            MockBukkit.unmock();
        }
    }

    @Test
    void migrationReportIsNotWrittenUntilTheReportsParticipantFlushes() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path dataRoot = plugin.getDataFolder().toPath();
        ReSyncJsonResourceStorage storage = jsonStorage(plugin);
        JsonObject recipe = JsonParser.parseString("""
            {
              "id": "legacy",
              "type": "shapeless",
              "shape": ["A"],
              "keys": {"A": {"material": "STONE"}}
            }
            """).getAsJsonObject();
        storage.save(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe);
        queueMigrationReport(storage, RecipeMigrationReportContract.create(1, 1));

        Path report = dataRoot.resolve(".migrations/recipe-schema-v1.json");
        assertFalse(Files.exists(report));
        assertTrue(storage.hasPendingMigrationReports());

        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(dataRoot, storage);
        assertFalse(Files.exists(dataRoot.resolve(MigrationReportsPersistenceParticipant.DIRECTORY)));
        assertThrows(IOException.class, participant::healthCheck);
        participant.admit();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(dataRoot);
        registry.register(participant);
        registry.flushAll();

        JsonObject reportJson = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        List<String> fields = new ArrayList<>();
        reportJson.entrySet().forEach(entry -> fields.add(entry.getKey()));
        assertEquals(RecipeMigrationReportContract.FIELD_ORDER, fields);
        assertEquals(1, reportJson.get("formatVersion").getAsInt());
        assertEquals(RecipeMigrationReportContract.REPORT_ID, reportJson.get("reportId").getAsString());
        assertEquals(RecipeMigrationReportContract.MIGRATION_ID, reportJson.get("migrationId").getAsString());
        assertEquals(0, reportJson.get("sourceSchemaVersion").getAsInt());
        assertEquals(1, reportJson.get("targetSchemaVersion").getAsInt());
        assertEquals(1, reportJson.get("inspected").getAsInt());
        assertEquals(1, reportJson.get("normalized").getAsInt());
        assertEquals(0, reportJson.get("unchanged").getAsInt());
        assertEquals(Files.readString(report), RecipeMigrationReportContract.read(report).canonicalJson());
        String first = Files.readString(report);

        queueMigrationReport(storage, RecipeMigrationReportContract.create(1, 1));
        registry.flushAll();

        assertEquals(first, Files.readString(report));
        assertFalse(storage.hasPendingMigrationReports());
    }

    @Test
    void abortFlushPersistsTheReportAfterSharedAssetPersistenceIsQuiesced() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path dataRoot = plugin.getDataFolder().toPath();
        ReSyncJsonResourceStorage storage = jsonStorage(plugin);
        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(dataRoot, storage);
        participant.admit();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(dataRoot);
        registry.register(participant);

        storage.quiescePersistence();
        registry.beginShutdown();
        PersistenceShutdownStatus status = registry.quiesceForShutdown();

        Path report = participant.root().resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE);
        assertEquals(PersistenceShutdownStatus.State.QUIESCED, status.state());
        assertEquals(List.of(MigrationReportsPersistenceParticipant.OWNER), status.flushedOwners());
        assertEquals(List.of(MigrationReportsPersistenceParticipant.OWNER), status.quiescedOwners());
        assertEquals(RecipeMigrationReportContract.create(0, 0), RecipeMigrationReportContract.read(report));
        assertFalse(storage.hasPendingMigrationReports());
        assertFalse(assetsGates.getFirst().isOpen());
    }

    @Test
    void canonicalExistingReportIsAcceptedWhenTheFirstWriteCollides() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path dataRoot = plugin.getDataFolder().toPath();
        ReSyncJsonResourceStorage storage = jsonStorage(plugin);
        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(dataRoot, storage);
        participant.admit();
        Path report = participant.root().resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE);
        String canonical = RecipeMigrationReportContract.create(0, 0).canonicalJson();
        Files.writeString(report, canonical, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(report);

        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(dataRoot);
        registry.register(participant);
        registry.flushAll();

        assertArrayEquals(before, Files.readAllBytes(report));
        assertFalse(storage.hasPendingMigrationReports());
    }

    @Test
    void differentExistingReportFailsClosedWithoutReplacement() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path dataRoot = plugin.getDataFolder().toPath();
        ReSyncJsonResourceStorage storage = jsonStorage(plugin);
        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(dataRoot, storage);
        participant.admit();
        Path report = participant.root().resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE);
        String existing = RecipeMigrationReportContract.create(1, 0).canonicalJson();
        Files.writeString(report, existing, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(report);

        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(dataRoot);
        registry.register(participant);
        assertThrows(IOException.class, registry::flushAll);

        assertArrayEquals(before, Files.readAllBytes(report));
        assertTrue(storage.hasPendingMigrationReports());
    }

    @Test
    void inventoryAndCoverageRequireTheExactRegisteredMigrationReportsParticipant() throws Exception {
        Path dataRoot = Files.createDirectories(temporaryDirectory.resolve("inventory-source"));
        var inventory = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot);
        var inventoryEntry = inventory.stream()
            .filter(writer -> writer.id().equals(MigrationReportsPersistenceParticipant.OWNER))
            .findFirst()
            .orElseThrow();
        assertEquals(dataRoot.resolve(MigrationReportsPersistenceParticipant.DIRECTORY).toAbsolutePath().normalize(),
            inventoryEntry.root());

        PersistenceWriterCoverageBoundary.Family family = PersistenceWriterCoverageBoundary.current().families().stream()
            .filter(candidate -> candidate.writerId().equals(MigrationReportsPersistenceParticipant.OWNER))
            .findFirst()
            .orElseThrow();
        assertEquals(MigrationReportsPersistenceParticipant.DIRECTORY, family.inventoryRelativePath());
        assertEquals(PersistenceWriterCoverageBoundary.LifecycleCoverage.PROVEN, family.lifecycleCoverage());
        assertEquals("restudio.resync.migration.MigrationReportsPersistenceParticipant", family.participantType());

        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(dataRoot);
        participant.admit();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporaryDirectory.resolve("inventory-control"), new MigrationFence());
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                MigrationReportsPersistenceParticipant.OWNER, participant.root(), participant)),
            inventory);

        assertFalse(registration.readiness().uncoveredWriterIds().contains(MigrationReportsPersistenceParticipant.OWNER));
    }

    @Test
    void participantOwnsOnlyTheExactMigrationReportsRootAndSupportsLifecycle() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path target = Files.createDirectories(temporaryDirectory.resolve("target"));
        Files.createDirectories(target.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(source);
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(source, participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);

        assertEquals(MigrationReportsPersistenceParticipant.OWNER, participant.owner());
        assertEquals(source.resolve(".migrations"), participant.root());
        assertEquals(source, participant.rebindScope());
        assertTrue(participant.owns(participant.root().resolve("recipe-schema-v1.json")));
        assertTrue(participant.owns(participant.root()));
        assertFalse(participant.owns(source.resolve("assets/resource.json")));
        assertFalse(participant.owns(participant.root().resolve("extra.json")));
        assertFalse(participant.owns(source));
        assertFalse(Files.exists(source.resolve(MigrationReportsPersistenceParticipant.DIRECTORY)));
        assertEquals(participant.owns(participant.root()), index.owns(context.participantRootRelative()));
        Path report = participant.root().resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE);
        assertEquals(participant.owns(report), index.owns(context.relativeToSource(report)));
        Path malformed = participant.root().resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE + ".tmp");
        assertEquals(participant.owns(malformed), index.owns(context.relativeToSource(malformed)));
        Path atomicTemp = participant.root().resolve(".resync-00000000-0000-0000-0000-000000000000.tmp");
        assertTrue(participant.owns(atomicTemp));
        assertTrue(index.owns(context.relativeToSource(atomicTemp)));
        Path quarantine = participant.root().resolve(MigrationReportsPersistenceParticipant.QUARANTINE_DIRECTORY);
        Path quarantinedTemp = quarantine.resolve(".resync-00000000-0000-0000-0000-000000000000.tmp");
        assertTrue(participant.owns(quarantine));
        assertTrue(index.owns(context.relativeToSource(quarantine)));
        assertTrue(participant.owns(quarantinedTemp));
        assertTrue(index.owns(context.relativeToSource(quarantinedTemp)));
        assertFalse(participant.owns(participant.root().resolve("unrelated123.tmp")));
        assertFalse(index.owns(context.relativeToSource(participant.root().resolve("unrelated123.tmp"))));
        Path nested = participant.root().resolve("nested").resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE);
        assertEquals(participant.owns(nested), index.owns(context.relativeToSource(nested)));

        assertThrows(IOException.class, () -> participant.rebind(target));
        participant.admit();
        participant.quiesce();
        participant.rebind(target);
        assertEquals(target.resolve(".migrations"), participant.root());
        participant.healthCheck();
        participant.resume();
        assertFalse(participant.isQuiesced());

        participant.quiesce();
        participant.shutdown();
        assertTrue(participant.isClosed());
        assertThrows(IOException.class, participant::healthCheck);
    }

    @Test
    void invalidCandidateDoesNotReplaceTheActiveReportsRoot() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("invalid-source"));
        Path target = Files.createDirectories(temporaryDirectory.resolve("invalid-target"));
        Files.writeString(target.resolve(MigrationReportsPersistenceParticipant.DIRECTORY), "not a directory");
        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(source);
        participant.admit();
        participant.quiesce();

        assertThrows(IOException.class, () -> participant.rebind(target));
        assertEquals(source.resolve(MigrationReportsPersistenceParticipant.DIRECTORY), participant.root());
        participant.resume();
    }

    @Test
    void startupAdmissionRollbackRemovesOnlyItsFreshEmptyRoot() throws Exception {
        Path freshSource = Files.createDirectories(temporaryDirectory.resolve("fresh-source"));
        MigrationReportsPersistenceParticipant fresh = new MigrationReportsPersistenceParticipant(freshSource);
        fresh.admit();
        fresh.rollbackAdmission();
        assertFalse(Files.exists(freshSource.resolve(MigrationReportsPersistenceParticipant.DIRECTORY)));
        assertFalse(fresh.isAdmitted());

        Path existingSource = Files.createDirectories(temporaryDirectory.resolve("existing-source"));
        Files.createDirectories(existingSource.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        MigrationReportsPersistenceParticipant existing = new MigrationReportsPersistenceParticipant(existingSource);
        existing.admit();
        existing.rollbackAdmission();
        assertTrue(Files.isDirectory(existing.root()));
        assertTrue(existing.isAdmitted());
    }

    @Test
    void rejectsExtraEntriesNestedDirectoriesAndMixedReportVersions() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("strict-source"));
        Path extraTarget = Files.createDirectories(temporaryDirectory.resolve("extra-target"));
        Path extraRoot = Files.createDirectories(extraTarget.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        Files.writeString(extraRoot.resolve("extra.json"), "{}");
        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(source);
        participant.admit();
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(extraTarget));
        assertEquals(source.resolve(MigrationReportsPersistenceParticipant.DIRECTORY), participant.root());
        participant.resume();

        Path nestedTarget = Files.createDirectories(temporaryDirectory.resolve("nested-target"));
        Files.createDirectories(nestedTarget.resolve(MigrationReportsPersistenceParticipant.DIRECTORY).resolve("nested"));
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(nestedTarget));
        participant.resume();

        Path mixedTarget = Files.createDirectories(temporaryDirectory.resolve("mixed-target"));
        Path mixedRoot = Files.createDirectories(mixedTarget.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        Files.writeString(mixedRoot.resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE),
            "{\"version\":1,\"inspected\":0,\"normalized\":0,\"unchanged\":0}");
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(mixedTarget));
        participant.resume();

        Path unknownTarget = Files.createDirectories(temporaryDirectory.resolve("unknown-target"));
        Path unknownRoot = Files.createDirectories(unknownTarget.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        JsonObject unknownReport = JsonParser.parseString(RecipeMigrationReportContract.create(0, 0).canonicalJson()).getAsJsonObject();
        unknownReport.addProperty("unknown", true);
        Files.writeString(unknownRoot.resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE), unknownReport.toString());
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(unknownTarget));
        participant.resume();
    }

    @Test
    void quarantinesKnownAtomicTempsAtStartupAndDuringHealthAndRebind() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("atomic-source"));
        Path sourceRoot = Files.createDirectories(source.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        String sourceTempName = ".resync-00000000-0000-0000-0000-000000000000.tmp";
        Path sourceTemp = sourceRoot.resolve(sourceTempName);
        Files.writeString(sourceTemp, "partial-report", StandardCharsets.UTF_8);

        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(source);
        Path sourceEvidence = sourceRoot.resolve(MigrationReportsPersistenceParticipant.QUARANTINE_DIRECTORY)
            .resolve(sourceTempName);
        assertFalse(Files.exists(sourceTemp));
        assertEquals("partial-report", Files.readString(sourceEvidence));

        participant.admit();
        String healthTempName = ".resync-00000000-0000-0000-0000-000000000001.tmp";
        Path healthTemp = participant.root().resolve(healthTempName);
        Files.writeString(healthTemp, "partial-world-report", StandardCharsets.UTF_8);
        participant.healthCheck();
        assertFalse(Files.exists(healthTemp));
        assertEquals("partial-world-report", Files.readString(participant.root()
            .resolve(MigrationReportsPersistenceParticipant.QUARANTINE_DIRECTORY).resolve(healthTempName)));

        Path target = Files.createDirectories(temporaryDirectory.resolve("atomic-target"));
        Path targetRoot = Files.createDirectories(target.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        String targetTempName = ".resync-00000000-0000-0000-0000-000000000002.tmp";
        Files.writeString(targetRoot.resolve(targetTempName), "partial-custom-backup", StandardCharsets.UTF_8);
        participant.quiesce();
        participant.rebind(target);
        assertFalse(Files.exists(targetRoot.resolve(targetTempName)));
        assertEquals("partial-custom-backup", Files.readString(targetRoot
            .resolve(MigrationReportsPersistenceParticipant.QUARANTINE_DIRECTORY).resolve(targetTempName)));
        participant.resume();
    }

    @Test
    void rejectsMalformedAtomicTempsNonRegularEntriesAndQuarantineCollisions() throws Exception {
        Path malformedSource = Files.createDirectories(temporaryDirectory.resolve("malformed-atomic-source"));
        Path malformedRoot = Files.createDirectories(malformedSource.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        Files.writeString(malformedRoot.resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE + "abc.tmp"), "bad");
        assertThrows(IllegalArgumentException.class, () -> new MigrationReportsPersistenceParticipant(malformedSource));

        Path directorySource = Files.createDirectories(temporaryDirectory.resolve("directory-atomic-source"));
        Path directoryRoot = Files.createDirectories(directorySource.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        Files.createDirectory(directoryRoot.resolve(".resync-00000000-0000-0000-0000-000000000003.tmp"));
        assertThrows(IllegalArgumentException.class, () -> new MigrationReportsPersistenceParticipant(directorySource));

        Path unknownQuarantineSource = Files.createDirectories(temporaryDirectory.resolve("unknown-quarantine-source"));
        Path unknownQuarantineRoot = Files.createDirectories(unknownQuarantineSource.resolve(
            MigrationReportsPersistenceParticipant.DIRECTORY).resolve(".quarantine"));
        Files.createDirectories(unknownQuarantineRoot.resolve("unexpected"));
        Files.createDirectories(unknownQuarantineRoot.resolve("migration-report-temps"));
        assertThrows(IllegalArgumentException.class, () -> new MigrationReportsPersistenceParticipant(unknownQuarantineSource));

        Path collisionSource = Files.createDirectories(temporaryDirectory.resolve("collision-atomic-source"));
        Path collisionRoot = Files.createDirectories(collisionSource.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        Path collisionQuarantine = Files.createDirectories(collisionRoot.resolve(
            MigrationReportsPersistenceParticipant.QUARANTINE_DIRECTORY));
        String collisionName = ".resync-00000000-0000-0000-0000-000000000004.tmp";
        Files.writeString(collisionRoot.resolve(collisionName), "new-evidence", StandardCharsets.UTF_8);
        Files.writeString(collisionQuarantine.resolve(collisionName), "old-evidence", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> new MigrationReportsPersistenceParticipant(collisionSource));
        assertEquals("new-evidence", Files.readString(collisionRoot.resolve(collisionName)));
        assertEquals("old-evidence", Files.readString(collisionQuarantine.resolve(collisionName)));
    }

    @Test
    void rejectsTamperedHashAndCountsButAllowsADeliberateEmptyReportSet() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("tamper-source"));
        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(source);
        participant.admit();
        assertTrue(participant.isAdmitted());
        participant.healthCheck();

        Path report = participant.root().resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE);
        RecipeMigrationReportContract.Report valid = RecipeMigrationReportContract.create(2, 1);
        Files.writeString(report, valid.canonicalJson(), StandardCharsets.UTF_8);
        JsonObject hashTampered = JsonParser.parseString(valid.canonicalJson()).getAsJsonObject();
        String replacementHashPrefix = valid.contentHash().startsWith("0") ? "1" : "0";
        hashTampered.addProperty("contentHash", replacementHashPrefix + valid.contentHash().substring(1));
        Files.writeString(report, hashTampered.toString(), StandardCharsets.UTF_8);
        assertThrows(IOException.class, participant::healthCheck);

        JsonObject selfHashTampered = JsonParser.parseString(valid.canonicalJson()).getAsJsonObject();
        String replacementSelfHashPrefix = valid.selfHash().startsWith("0") ? "1" : "0";
        selfHashTampered.addProperty("selfHash", replacementSelfHashPrefix + valid.selfHash().substring(1));
        Files.writeString(report, selfHashTampered.toString(), StandardCharsets.UTF_8);
        assertThrows(IOException.class, participant::healthCheck);

        JsonObject countTampered = JsonParser.parseString(valid.canonicalJson()).getAsJsonObject();
        countTampered.addProperty("unchanged", 2);
        Files.writeString(report, countTampered.toString(), StandardCharsets.UTF_8);
        assertThrows(IOException.class, participant::healthCheck);

        Files.delete(report);
        participant.healthCheck();
        participant.quiesce();
        Path emptyTarget = Files.createDirectories(temporaryDirectory.resolve("empty-target"));
        Files.createDirectories(emptyTarget.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        participant.rebind(emptyTarget);
        participant.healthCheck();
        participant.resume();
    }

    @Test
    void coordinatorSnapshotRebindAndRestartRetainTheCanonicalReport() throws Exception {
        Path dataRoot = Files.createDirectories(temporaryDirectory.resolve("coordinator-source"));
        MigrationReportsPersistenceParticipant participant = new MigrationReportsPersistenceParticipant(dataRoot);
        participant.admit();
        RecipeMigrationReportContract.Report report = RecipeMigrationReportContract.create(1, 0);
        Files.writeString(participant.root().resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE),
            report.canonicalJson(), StandardCharsets.UTF_8);

        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporaryDirectory.resolve("coordinator-control"), new MigrationFence());
        coordinator.register(participant);
        coordinator.seal();
        Snapshot snapshot = coordinator.createSnapshot(
            temporaryDirectory.resolve("coordinator-snapshot"), SnapshotMetadata.preflight());
        assertTrue(snapshot.verified());
        assertEquals(report.canonicalJson(), Files.readString(snapshot.root().resolve(
            MigrationReportsPersistenceParticipant.DIRECTORY).resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE)));

        coordinator.participants().quiesceAll();
        coordinator.participants().rebindAll(snapshot.root());
        coordinator.participants().resumeAll();
        participant.healthCheck();
        coordinator.close();

        MigrationReportsPersistenceParticipant restartedParticipant = new MigrationReportsPersistenceParticipant(snapshot.root());
        restartedParticipant.admit();
        ReSyncPersistenceCoordinator restartedCoordinator = new ReSyncPersistenceCoordinator(
            snapshot.root(), temporaryDirectory.resolve("restarted-control"), new MigrationFence());
        restartedCoordinator.register(restartedParticipant);
        restartedCoordinator.seal();
        restartedParticipant.healthCheck();
        restartedCoordinator.close();
    }

    @Test
    void coordinatorRestoresMigrationReportsAfterALaterParticipantFails() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        Path dataRoot = plugin.getDataFolder().toPath();
        ReSyncJsonResourceStorage storage = jsonStorage(plugin);
        assertTrue(storage.hasPendingMigrationReports());

        Path target = Files.createDirectories(temporaryDirectory.resolve("rollback-target"));
        Path sourceAssets = dataRoot.resolve("assets");
        Path targetAssets = Files.createDirectories(target.resolve("assets"));
        Path sourceLater = Files.createDirectories(dataRoot.resolve("later"));
        Path targetLater = Files.createDirectories(target.resolve("later"));
        Files.writeString(sourceLater.resolve("active.txt"), "source-active", StandardCharsets.UTF_8);
        Files.writeString(targetLater.resolve("active.txt"), "target-active", StandardCharsets.UTF_8);
        Path targetReports = Files.createDirectories(target.resolve(MigrationReportsPersistenceParticipant.DIRECTORY));
        String targetReport = RecipeMigrationReportContract.create(8, 3).canonicalJson();
        Files.writeString(targetReports.resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE), targetReport,
            StandardCharsets.UTF_8);
        byte[] targetBytes = Files.readAllBytes(targetLater.resolve("active.txt"));
        byte[] targetReportBytes = Files.readAllBytes(targetReports.resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE));

        MigrationReportsPersistenceParticipant migrationReports = new MigrationReportsPersistenceParticipant(dataRoot, storage);
        migrationReports.admit();
        RebindableTestParticipant assets = new RebindableTestParticipant(
            "resync.assets", dataRoot, sourceAssets, "assets");
        FailingRebindParticipant failing = new FailingRebindParticipant(dataRoot, sourceLater, targetLater);
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporaryDirectory.resolve("rollback-control"), new MigrationFence());
        coordinator.register(assets);
        coordinator.register(migrationReports);
        coordinator.register(failing);
        coordinator.seal();
        Path previousBootstrapRoot = coordinator.activeDataRoot();
        Path previousBootstrapLater = previousBootstrapRoot.resolve("later");
        byte[] previousBootstrapLaterBytes = Files.readAllBytes(previousBootstrapLater.resolve("active.txt"));
        Path previousBootstrapReport = previousBootstrapRoot.resolve(MigrationReportsPersistenceParticipant.DIRECTORY)
            .resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE);
        byte[] previousBootstrapReportBytes = Files.readAllBytes(previousBootstrapReport);

        coordinator.participants().quiesceAll();
        assertThrows(MigrationException.class, () -> coordinator.participants().rebindAll(target));

        assertTrue(failing.targetRebindAttempted);
        assertEquals(previousBootstrapRoot.resolve(MigrationReportsPersistenceParticipant.DIRECTORY), migrationReports.root());
        assertEquals(previousBootstrapLater, failing.root());
        PersistenceRebindStatus rebindStatus = coordinator.participants().rebindStatus();
        assertEquals(PersistenceRebindStatus.State.ROLLED_BACK, rebindStatus.state());
        assertEquals(previousBootstrapRoot, rebindStatus.activeRoot().orElseThrow());
        assertEquals(List.of("resync.assets", MigrationReportsPersistenceParticipant.OWNER), rebindStatus.reboundOwners());
        assertTrue(migrationReports.isQuiesced());
        assertFalse(storage.hasPendingMigrationReports());
        assertEquals(previousBootstrapReport, migrationReports.root().resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE));
        assertArrayEquals(previousBootstrapReportBytes, Files.readAllBytes(previousBootstrapReport));
        assertArrayEquals(previousBootstrapLaterBytes, Files.readAllBytes(previousBootstrapLater.resolve("active.txt")));
        assertArrayEquals(targetBytes, Files.readAllBytes(targetLater.resolve("active.txt")));
        assertArrayEquals(targetReportBytes, Files.readAllBytes(targetReports.resolve(MigrationReportsPersistenceParticipant.RECIPE_REPORT_FILE)));

        coordinator.participants().resumeAll();
        coordinator.close();
    }

    private ReSyncJsonResourceStorage jsonStorage(JavaPlugin plugin) {
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        AssetPersistenceGate assetsGate = new AssetPersistenceGate(scope);
        try {
            AssetTransactionCoordinator.AdoptionInventory inventory = new AssetTransactionCoordinator.AdoptionInventory(
                "migration-reports-persistence-test", "{}", List.of());
            AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.adoptExisting(
                scope.resolve("assets"), new Gson(), inventory);
            CanonicalProjectMetadataFixture.seed(coordinator);
            assetsGates.add(assetsGate);
            coordinators.add(coordinator);
            ReSyncJsonResourceStorage storage = new ReSyncJsonResourceStorage(
                plugin, LegacyRuntimeActivationGate.compatibility(scope), assetsGate, coordinator);
            storages.add(storage);
            queueMigrationReport(storage, RecipeMigrationReportContract.create(0, 0));
            return storage;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open migration report test persistence", exception);
        }
    }

    private void queueMigrationReport(ReSyncJsonResourceStorage storage,
                                      RecipeMigrationReportContract.Report report) {
        try {
            Field pendingReport = ReSyncJsonResourceStorage.class.getDeclaredField("pendingRecipeMigrationReport");
            pendingReport.setAccessible(true);
            pendingReport.set(storage, report);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to prepare migration report fixture", exception);
        }
    }

    private static class RebindableTestParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final Path scopeRoot;
        private final String relativePath;
        private Path activeRoot;
        private boolean quiesced;

        private RebindableTestParticipant(String owner, Path scopeRoot, Path sourceRoot, String relativePath) {
            this.owner = owner;
            this.scopeRoot = scopeRoot.toAbsolutePath().normalize();
            this.activeRoot = sourceRoot.toAbsolutePath().normalize();
            this.relativePath = relativePath;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path root() {
            return activeRoot;
        }

        @Override
        public Path rebindScope() {
            return scopeRoot;
        }

        @Override
        public boolean owns(Path file) {
            Path candidate = file.toAbsolutePath().normalize();
            return candidate.equals(activeRoot) || candidate.startsWith(activeRoot.resolve(""));
        }

        @Override
        public void flush() throws IOException {
            MigrationPaths.requireDirectory(activeRoot, owner + " root");
        }

        @Override
        public void quiesce() throws IOException {
            MigrationPaths.requireDirectory(activeRoot, owner + " root");
            quiesced = true;
        }

        @Override
        public void resume() throws IOException {
            MigrationPaths.requireDirectory(activeRoot, owner + " root");
            quiesced = false;
        }

        @Override
        public void rebind(Path activeScope) throws IOException {
            if (!quiesced) {
                throw new IOException(owner + " is not quiesced");
            }
            Path candidate = activeScope.toAbsolutePath().normalize().resolve(relativePath).normalize();
            MigrationPaths.requireDirectory(candidate, owner + " root");
            activeRoot = candidate;
        }

        @Override
        public void healthCheck() throws IOException {
            MigrationPaths.requireDirectory(activeRoot, owner + " root");
        }
    }

    private static final class FailingRebindParticipant extends RebindableTestParticipant {
        private final Path targetRoot;
        private boolean targetRebindAttempted;

        private FailingRebindParticipant(Path scopeRoot, Path sourceRoot, Path targetRoot) {
            super("zz.migration-reports-failure", scopeRoot, sourceRoot, "later");
            this.targetRoot = targetRoot.toAbsolutePath().normalize();
        }

        @Override
        public void rebind(Path activeScope) throws IOException {
            Path candidate = activeScope.toAbsolutePath().normalize().resolve("later").normalize();
            if (candidate.equals(targetRoot)) {
                targetRebindAttempted = true;
                throw new IOException("later participant rebind failed");
            }
            super.rebind(activeScope);
        }
    }
}
