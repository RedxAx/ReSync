package restudio.resync.world;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.server.ReSyncPersistenceTopology;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAuditPersistenceParticipantTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path temporary;

    @Test
    void freshAndExistingFilesLoadThroughOneAuthority() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("fresh"));
        WorldOperationSafetyService fresh = new WorldOperationSafetyService(dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME), 100);
        assertEquals(List.of(), fresh.snapshot(10));
        assertTrue(Files.exists(dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME)));

        Path existingFile = temporary.resolve("existing").resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        Files.createDirectories(existingFile.getParent());
        WorldOperationAuditRecord record = record("audit-1", "op-1", "deleteWorld");
        Files.writeString(existingFile, GSON.toJson(List.of(record)));
        WorldOperationSafetyService existing = new WorldOperationSafetyService(existingFile, 100);

        assertEquals("audit-1", existing.snapshot(10).getFirst().getAuditId());
        assertEquals(existingFile.toAbsolutePath().normalize(), existing.persistenceRoot());
    }

    @Test
    void oversizedLegacyAuditFailsClosedBeforeReadOrMigration() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("audit-oversized"));
        Path file = dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        createSparseFile(file, WorldOperationSafetyService.MAXIMUM_AUDIT_FILE_BYTES + 1L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new WorldOperationSafetyService(file, 100));

        assertTrue(failure.getCause() instanceof IOException);
        assertTrue(failure.getCause().getMessage().contains("maximum byte budget"));
        assertEquals(WorldOperationSafetyService.MAXIMUM_AUDIT_FILE_BYTES + 1L, Files.size(file));
        assertFalse(Files.exists(dataRoot.resolve(".migrations")));
    }

    @Test
    void deeplyNestedLegacyAuditFailsClosedWithoutMigration() throws Exception {
        Path deepRoot = Files.createDirectory(temporary.resolve("audit-deep"));
        Path deepFile = deepRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        int nesting = WorldOperationSafetyService.MAXIMUM_LEGACY_JSON_DEPTH + 2;
        String deep = "[".repeat(nesting) + "null" + "]".repeat(nesting);
        Files.writeString(deepFile, deep, StandardCharsets.UTF_8);
        byte[] deepBytes = Files.readAllBytes(deepFile);

        IllegalStateException deepFailure = assertThrows(IllegalStateException.class,
            () -> new WorldOperationSafetyService(deepFile, 100));

        assertTrue(deepFailure.getCause() instanceof IOException);
        assertTrue(deepFailure.getCause().getMessage().contains("maximum nesting depth"));
        assertArrayEquals(deepBytes, Files.readAllBytes(deepFile));
        assertFalse(Files.exists(deepRoot.resolve(".migrations")));
    }

    @Test
    void oversizedLegacyCollectionFailsClosedWithoutMigration() throws Exception {
        Path collectionRoot = Files.createDirectory(temporary.resolve("audit-collection"));
        Path collectionFile = collectionRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        int entries = Math.toIntExact(WorldOperationSafetyService.MAXIMUM_LEGACY_COLLECTION_ENTRIES + 1L);
        String collection = "[" + "null,".repeat(entries - 1) + "null]";
        Files.writeString(collectionFile, collection, StandardCharsets.UTF_8);
        byte[] collectionBytes = Files.readAllBytes(collectionFile);

        IllegalStateException collectionFailure = assertThrows(IllegalStateException.class,
            () -> new WorldOperationSafetyService(collectionFile, 100));

        assertTrue(collectionFailure.getCause() instanceof IOException);
        assertTrue(collectionFailure.getCause().getMessage().contains("maximum collection budget"));
        assertArrayEquals(collectionBytes, Files.readAllBytes(collectionFile));
        assertFalse(Files.exists(collectionRoot.resolve(".migrations")));
    }

    @Test
    void legacyFailedRecordsNormalizeDiagnosticsAndBackupFactsBeforeStrictV2Publication() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("audit-legacy-normalization"));
        Path file = dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationAuditRecord missingDiagnostic = record("audit-missing-diagnostic", "op-missing", "deleteWorld");
        missingDiagnostic.setSuccess(false);
        missingDiagnostic.setMessage(null);
        missingDiagnostic.setFailureReason(null);
        missingDiagnostic.setSafetyBackupId(null);
        missingDiagnostic.setBackupAvailable(false);
        WorldOperationAuditRecord inconsistentBackup = record("audit-inconsistent-backup", "op-backup", "purgeWorld");
        inconsistentBackup.setSuccess(false);
        inconsistentBackup.setMessage("legacy failure");
        inconsistentBackup.setFailureReason(null);
        inconsistentBackup.setSafetyBackupId("legacy-backup");
        inconsistentBackup.setBackupAvailable(false);
        Files.writeString(file, GSON.toJson(List.of(missingDiagnostic, inconsistentBackup)));

        WorldOperationSafetyService service = new WorldOperationSafetyService(file, 100);

        List<WorldOperationAuditRecord> migrated = service.snapshot(10);
        WorldOperationAuditRecord missing = migrated.stream()
            .filter(value -> value.getAuditId().equals("audit-missing-diagnostic"))
            .findFirst().orElseThrow();
        WorldOperationAuditRecord backup = migrated.stream()
            .filter(value -> value.getAuditId().equals("audit-inconsistent-backup"))
            .findFirst().orElseThrow();
        assertEquals("Legacy unsuccessful operation did not include a failure reason", missing.getFailureReason());
        assertFalse(missing.isBackupAvailable());
        assertEquals("legacy-backup", backup.getSafetyBackupId());
        assertTrue(backup.isBackupAvailable());
        byte[] canonical = Files.readAllBytes(file);
        assertTrue(new String(canonical).contains("\"version\":2"));
        assertTrue(Files.exists(dataRoot.resolve(".migrations").resolve("resync.world-audit-v2.backup")));

        WorldOperationSafetyService restarted = new WorldOperationSafetyService(file, 100);

        assertArrayEquals(canonical, Files.readAllBytes(file));
        assertEquals("Legacy unsuccessful operation did not include a failure reason",
            restarted.snapshot(10).stream().filter(value -> value.getAuditId().equals("audit-missing-diagnostic"))
                .findFirst().orElseThrow().getFailureReason());
        assertTrue(restarted.snapshot(10).stream().filter(value -> value.getAuditId().equals("audit-inconsistent-backup"))
            .findFirst().orElseThrow().isBackupAvailable());
    }

    @Test
    void canonicalVersionOneRecordsMigrateWithTheirSourceHashBeforeNormalization() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("audit-version-one"));
        Path file = dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationAuditRecord source = record("audit-v1", "op-v1", "deleteWorld");
        Map<String, Object> sourceBody = new LinkedHashMap<>();
        sourceBody.put("auditId", source.getAuditId());
        sourceBody.put("operationId", source.getOperationId());
        sourceBody.put("action", source.getAction());
        sourceBody.put("actorClientId", source.getActorClientId());
        sourceBody.put("targetWorld", source.getTargetWorld());
        sourceBody.put("parameters", source.getParameters());
        sourceBody.put("success", source.isSuccess());
        sourceBody.put("message", source.getMessage());
        sourceBody.put("failureReason", source.getFailureReason());
        sourceBody.put("safetyBackupId", source.getSafetyBackupId());
        sourceBody.put("backupAvailable", source.isBackupAvailable());
        sourceBody.put("startedAt", source.getStartedAt());
        sourceBody.put("finishedAt", source.getFinishedAt());
        sourceBody.put("durationMillis", source.getDurationMillis());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", "resync.world-audit");
        document.put("version", 1);
        document.put("records", List.of(sourceBody));
        document.put("contentHash", CanonicalHash.sha256(JsonValue.fromJava(documentWithoutHash(document))));
        Files.write(file, JsonValue.fromJava(document).canonicalBytes());

        WorldOperationSafetyService service = new WorldOperationSafetyService(file, 100);

        assertEquals("op-v1", service.snapshot(10).getFirst().getOperationId());
        assertTrue(new String(Files.readAllBytes(file)).contains("\"version\":2"));
        assertTrue(Files.exists(dataRoot.resolve(".migrations").resolve("resync.world-audit-v2.json")));
    }

    @Test
    void migrationRecoversAPreparedCutAndFencesTamperedBackupOrReport() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("audit-migration-cut"));
        Path file = dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        byte[] legacyBytes = GSON.toJson(List.of(record("audit-cut", "op-cut", "deleteWorld"))).getBytes(StandardCharsets.UTF_8);
        Files.write(file, legacyBytes);
        new WorldOperationSafetyService(file, 100);
        Path migrationRoot = dataRoot.resolve(".migrations");
        Path report = migrationRoot.resolve("resync.world-audit-v2.json");
        Map<String, Object> prepared = new LinkedHashMap<>((Map<String, Object>) CanonicalCodec.decodePermissive(
            Files.readAllBytes(report)).toJava());
        prepared.remove("contentHash");
        prepared.put("state", "PREPARED");
        prepared.put("contentHash", StorageSafety.sha256(JsonValue.fromJava(prepared).canonicalBytes()));
        Files.write(report, JsonValue.fromJava(prepared).canonicalBytes());
        Files.write(file, legacyBytes);

        WorldOperationSafetyService recovered = new WorldOperationSafetyService(file, 100);

        assertEquals("op-cut", recovered.snapshot(10).getFirst().getOperationId());
        String committed = Files.readString(report);
        assertTrue(committed.contains("\"state\":\"COMMITTED\""));
        byte[] canonical = Files.readAllBytes(file);
        new WorldOperationSafetyService(file, 100);
        assertArrayEquals(canonical, Files.readAllBytes(file));

        Files.writeString(migrationRoot.resolve("resync.world-audit-v2.backup"), "tampered");
        assertThrows(IllegalStateException.class, () -> new WorldOperationSafetyService(file, 100));

        Path reportRoot = Files.createDirectory(temporary.resolve("audit-migration-report-tamper"));
        Path reportFile = reportRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        Files.write(reportFile, legacyBytes);
        new WorldOperationSafetyService(reportFile, 100);
        Path reportPath = reportRoot.resolve(".migrations").resolve("resync.world-audit-v2.json");
        String reportText = Files.readString(reportPath);
        int hashStart = reportText.indexOf("\"contentHash\":\"") + "\"contentHash\":\"".length();
        char replacement = reportText.charAt(hashStart) == '0' ? '1' : '0';
        Files.writeString(reportPath, reportText.substring(0, hashStart) + replacement + reportText.substring(hashStart + 1));
        assertThrows(IllegalStateException.class, () -> new WorldOperationSafetyService(reportFile, 100));
    }

    @Test
    void mutationFlushAndRestartPreserveRecordsExactly() throws Exception {
        Path file = temporary.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(file, 100);
        WorldOperationAuditRecord record = service.begin("op-1", "deleteWorld", "client", "world", Map.of("worldName", "world"));
        service.finish(record, WorldOperationResult.success("deleteWorld", "world", "Deleted"), null);
        service.flushPersistence();

        WorldOperationSafetyService restarted = new WorldOperationSafetyService(file, 100);

        assertEquals(1, restarted.snapshot(10).size());
        assertEquals("op-1", restarted.snapshot(10).getFirst().getOperationId());
        assertEquals("world", restarted.snapshot(10).getFirst().getParameters().get("worldName"));
    }

    @Test
    void ownershipIndexClaimsOnlyTheWorldAuditFile() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("ownership"));
        Path file = dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(file, 100);
        WorldAuditPersistenceParticipant participant = new WorldAuditPersistenceParticipant(dataRoot, service);
        var index = participant.ownershipIndex(new PersistenceOwnershipContext(dataRoot, participant.root()));

        assertTrue(index.owns(WorldAuditPersistenceParticipant.FILE_NAME));
        assertFalse(index.owns(WorldAuditPersistenceParticipant.FILE_NAME + ".tmp"));
        assertFalse(index.owns(WorldAuditPersistenceParticipant.FILE_NAME + "/nested"));
    }

    @Test
    void quiesceRejectsNewMutationsAndShutdownClosesAuthority() throws Exception {
        WorldOperationSafetyService service = new WorldOperationSafetyService(temporary.resolve(WorldAuditPersistenceParticipant.FILE_NAME), 100);
        service.quiescePersistence();

        assertThrows(IllegalStateException.class, () -> service.begin("op", "deleteWorld", "client", "world", Map.of()));
        service.closePersistence();
        assertTrue(service.isClosed());
        assertThrows(IllegalStateException.class, () -> service.rememberStatus(WorldOperationResult.success("action", "world", "ok")));
    }

    @Test
    void quiesceAndSnapshotDrainAnAdmittedLeaseBeforePublication() throws Exception {
        Path file = temporary.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(file, 100);
        WorldOperationAuditRecord record = service.begin("op-in-flight", "deleteWorld", "client", "world", Map.of());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> quiesce = executor.submit(() -> {
            service.quiescePersistence();
            return null;
        });
        Thread.sleep(100L);
        Future<List<WorldOperationAuditRecord>> snapshot = executor.submit(() -> service.snapshot(10));
        Thread.sleep(50L);
        assertFalse(quiesce.isDone());
        service.finish(record, WorldOperationResult.success("deleteWorld", "world", "done"), null);
        quiesce.get(5, TimeUnit.SECONDS);
        assertEquals("op-in-flight", snapshot.get(5, TimeUnit.SECONDS).getFirst().getOperationId());
        executor.shutdownNow();
    }

    @Test
    void shutdownWaitsForAnAdmittedLeaseAndNeverLosesItsFinish() throws Exception {
        Path file = temporary.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(file, 100);
        WorldOperationAuditRecord record = service.begin("op-shutdown", "deleteWorld", "client", "world", Map.of());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> shutdown = executor.submit(() -> {
            service.closePersistence();
            return null;
        });
        Thread.sleep(50L);
        assertFalse(service.isClosed());
        service.finish(record, WorldOperationResult.success("deleteWorld", "world", "done"), null);
        shutdown.get(5, TimeUnit.SECONDS);
        assertTrue(service.isClosed());
        WorldOperationSafetyService restarted = new WorldOperationSafetyService(file, 100);
        assertEquals("op-shutdown", restarted.snapshot(10).getFirst().getOperationId());
        executor.shutdownNow();
    }

    @Test
    void rebindCannotBypassAnInFlightLeaseAndLaterPublishesItsFinish() throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("rebind-in-flight"));
        Path activeFile = activeRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(activeFile, 100);
        WorldAuditPersistenceParticipant participant = new WorldAuditPersistenceParticipant(activeRoot, service);
        WorldOperationAuditRecord record = service.begin("op-rebind", "deleteWorld", "client", "world", Map.of());
        Path candidateRoot = Files.createDirectory(temporary.resolve("rebind-in-flight-candidate"));
        Files.writeString(candidateRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME), GSON.toJson(List.of(record("audit-new", "op-new", "purgeWorld"))));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> rebind = executor.submit(() -> {
            assertThrows(IOException.class, () -> participant.rebind(candidateRoot));
            return null;
        });
        rebind.get(5, TimeUnit.SECONDS);
        service.finish(record, WorldOperationResult.success("deleteWorld", "world", "done"), null);
        service.quiescePersistence();
        participant.rebind(candidateRoot);
        assertEquals("op-new", service.snapshot(10).getFirst().getOperationId());
        executor.shutdownNow();
    }

    @Test
    void failedLeaseDrainRetainsReadinessFailureUntilExplicitResume() throws Exception {
        Path file = temporary.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(file, 100);
        WorldOperationAuditRecord record = service.begin("op-timeout", "deleteWorld", "client", "world", Map.of());
        assertThrows(IOException.class, () -> service.quiescePersistence());
        assertFalse(service.isQuiesced());
        assertThrows(IllegalStateException.class, () -> service.begin("blocked", "deleteWorld", "client", "world", Map.of()));
        service.finish(record, WorldOperationResult.success("deleteWorld", "world", "done"), null);
        service.resumePersistence();
        assertEquals("op-timeout", service.snapshot(10).getFirst().getOperationId());
    }

    @Test
    void rebindLoadsCandidateBeforeSwitchAndKeepsFailedCandidateRetained() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("active"));
        Path activeFile = dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(activeFile, 100);
        WorldAuditPersistenceParticipant participant = new WorldAuditPersistenceParticipant(dataRoot, service);
        WorldOperationAuditRecord original = service.begin("op-old", "deleteWorld", "client", "old", Map.of());
        service.finish(original, WorldOperationResult.success("deleteWorld", "old", "old"), null);
        byte[] originalBytes = Files.readAllBytes(activeFile);
        long initialGeneration = service.generation();
        service.quiescePersistence();

        Path malformedRoot = Files.createDirectory(temporary.resolve("malformed"));
        Path malformed = malformedRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        Files.writeString(malformed, "{not-an-array}");
        assertThrows(IOException.class, () -> participant.rebind(malformedRoot));
        assertEquals(activeFile, participant.root());
        assertArrayEquals(originalBytes, Files.readAllBytes(activeFile));
        assertEquals("op-old", service.snapshot(10).getFirst().getOperationId());

        Path missingRoot = Files.createDirectory(temporary.resolve("missing"));
        assertThrows(IOException.class, () -> participant.rebind(missingRoot));
        assertEquals(activeFile, participant.root());

        Path candidateRoot = Files.createDirectory(temporary.resolve("candidate"));
        Files.writeString(candidateRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME), GSON.toJson(List.of(record("audit-new", "op-new", "purgeWorld"))));
        participant.rebind(candidateRoot);

        assertEquals(candidateRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME).toAbsolutePath().normalize(), participant.root());
        assertEquals(initialGeneration + 1L, service.generation());
        assertEquals("op-new", service.snapshot(10).getFirst().getOperationId());
        assertNotEquals("op-old", service.snapshot(10).getFirst().getOperationId());
    }

    @Test
    void restoredAuditRecordsAreReadOnlyDataAndNeverReexecuted() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("restore"));
        Path file = dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        Files.writeString(file, GSON.toJson(List.of(record("audit-delete", "op-delete", "deleteWorld"))));
        WorldOperationSafetyService service = new WorldOperationSafetyService(file, 100);
        WorldAuditPersistenceParticipant participant = new WorldAuditPersistenceParticipant(dataRoot, service);
        service.quiescePersistence();

        participant.rebind(dataRoot);

        assertEquals(1, service.snapshot(10).size());
        assertEquals("deleteWorld", service.snapshot(10).getFirst().getAction());
        assertEquals(1, service.snapshot(10).size());
    }

    @Test
    void candidateValidationRejectsMissingAuditIdentity() throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("semantic-active"));
        WorldOperationSafetyService service = new WorldOperationSafetyService(
            activeRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME), 100);
        WorldAuditPersistenceParticipant participant = new WorldAuditPersistenceParticipant(activeRoot, service);
        service.quiescePersistence();
        Path candidateRoot = Files.createDirectory(temporary.resolve("semantic-candidate"));
        Files.writeString(candidateRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME),
            "[{\"auditId\":\"\",\"operationId\":\"op\",\"action\":\"deleteWorld\"}]");

        assertThrows(IOException.class, () -> participant.rebind(candidateRoot));
        assertEquals(activeRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME).toAbsolutePath().normalize(), participant.root());
        assertEquals(0L, participant.generation());
    }

    @Test
    void canonicalContractRejectsUnknownDuplicateAndNumericCoercion() throws Exception {
        Path active = temporary.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(active, 100);
        service.quiescePersistence();
        Path candidateRoot = Files.createDirectory(temporary.resolve("strict-candidate"));
        Path candidate = candidateRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        Files.writeString(candidate, "{\"kind\":\"resync.world-audit\",\"version\":1,\"records\":[],\"contentHash\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"unknown\":true}");
        assertThrows(IOException.class, () -> service.rebindPersistence(candidate));
        Files.writeString(candidate, "[{\"auditId\":\"a\",\"auditId\":\"b\",\"operationId\":\"op\",\"action\":\"deleteWorld\",\"success\":true,\"startedAt\":1.0,\"finishedAt\":1,\"durationMillis\":0}]");
        assertThrows(IOException.class, () -> service.rebindPersistence(candidate));
        Files.writeString(candidate, "[{\"auditId\":\"a\",\"operationId\":\"op\",\"action\":\"deleteWorld\",\"success\":true,\"message\":\"ok\",\"parameters\":{},\"backupAvailable\":false,\"startedAt\":1e0,\"finishedAt\":1,\"durationMillis\":0}]");
        assertThrows(IOException.class, () -> service.rebindPersistence(candidate));
        Files.writeString(candidate, GSON.toJson(List.of(record("same", "op-1", "deleteWorld"), record("SAME", "op-2", "purgeWorld"))));
        assertThrows(IOException.class, () -> service.rebindPersistence(candidate));
    }

    @Test
    void concurrentReadersAndMutationsRemainLinearized() throws Exception {
        Path file = temporary.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(file, 500);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> writer = executor.submit(() -> {
            start.await();
            for (int index = 0; index < 20; index++) {
                WorldOperationAuditRecord record = service.begin("op-" + index, "action", "client", "world", Map.of());
                service.finish(record, WorldOperationResult.success("action", "world", "ok"), null);
            }
            return null;
        });
        Future<?> reader = executor.submit(() -> {
            start.await();
            for (int index = 0; index < 20; index++) {
                service.snapshot(500);
            }
            return null;
        });
        start.countDown();
        writer.get(10, TimeUnit.SECONDS);
        reader.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(20, service.snapshot(500).size());
    }

    @Test
    void concurrentMutationReadAndRebindNeverExposeMixedGeneration() throws Exception {
        Path activeRoot = Files.createDirectory(temporary.resolve("concurrent-rebind"));
        Path activeFile = activeRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME);
        WorldOperationSafetyService service = new WorldOperationSafetyService(activeFile, 100);
        service.finish(service.begin("op-old", "deleteWorld", "client", "old", Map.of()),
            WorldOperationResult.success("deleteWorld", "old", "old"), null);
        WorldAuditPersistenceParticipant participant = new WorldAuditPersistenceParticipant(activeRoot, service);
        service.quiescePersistence();

        Path candidateRoot = Files.createDirectory(temporary.resolve("concurrent-candidate"));
        Files.writeString(candidateRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME),
            GSON.toJson(List.of(record("audit-new", "op-new", "purgeWorld"))));
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> reader = executor.submit(() -> {
            start.await();
            for (int index = 0; index < 200; index++) {
                long before = participant.generation();
                String operationId = service.snapshot(1).getFirst().getOperationId();
                long after = participant.generation();
                if (before == after) {
                    String expected = before == 0L ? "op-old" : "op-new";
                    if (!expected.equals(operationId)) {
                        throw new AssertionError("Mixed world audit generation observed");
                    }
                }
            }
            return null;
        });
        Future<?> writer = executor.submit(() -> {
            start.await();
            assertThrows(IllegalStateException.class,
                () -> service.begin("op-blocked", "deleteWorld", "client", "blocked", Map.of()));
            return null;
        });
        Future<?> rebinder = executor.submit(() -> {
            start.await();
            participant.rebind(candidateRoot);
            return null;
        });
        start.countDown();
        reader.get(10, TimeUnit.SECONDS);
        writer.get(10, TimeUnit.SECONDS);
        rebinder.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(1L, participant.generation());
        assertEquals("op-new", service.snapshot(1).getFirst().getOperationId());
    }

    @Test
    void topologyOwnsOnlyExactAuditFileAndRejectsOverlap() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("topology"));
        WorldOperationSafetyService service = new WorldOperationSafetyService(dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME), 100);
        WorldAuditPersistenceParticipant participant = new WorldAuditPersistenceParticipant(dataRoot, service);
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"), new MigrationFence());
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(participant.owner(), participant.root(), participant)),
            List.of());

        assertTrue(registration.sealed());
        Path activeRoot = coordinator.activeDataRoot();
        assertEquals(participant.owner(), coordinator.participants().ownerFor(activeRoot, participant.root()));
        assertFalse(participant.owns(participant.root().resolve("nested")));
        assertThrows(MigrationException.class, () -> coordinator.participants().ownerFor(dataRoot, dataRoot));
        assertFalse(coordinator.participants().participants().stream().map(PersistenceParticipant::owner)
            .anyMatch("resync.root"::equals));
        coordinator.close();
        assertTrue(service.isClosed());
    }

    private WorldOperationAuditRecord record(String auditId, String operationId, String action) {
        WorldOperationAuditRecord record = new WorldOperationAuditRecord();
        record.setAuditId(auditId);
        record.setOperationId(operationId);
        record.setAction(action);
        record.setSuccess(true);
        record.setMessage("restored");
        record.setStartedAt(1L);
        record.setFinishedAt(2L);
        record.setDurationMillis(1L);
        record.setParameters(Map.of());
        return record;
    }

    private Map<String, Object> documentWithoutHash(Map<String, Object> document) {
        Map<String, Object> withoutHash = new LinkedHashMap<>(document);
        withoutHash.remove("contentHash");
        return withoutHash;
    }

    private static void createSparseFile(Path file, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(size - 1L);
            channel.write(ByteBuffer.wrap(new byte[] {' '}));
        }
    }
}
