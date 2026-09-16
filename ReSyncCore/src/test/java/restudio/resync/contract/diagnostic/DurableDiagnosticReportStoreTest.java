package restudio.resync.contract.diagnostic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.diagnostic.DiagnosticSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableDiagnosticReportStoreTest {
    @TempDir
    Path directory;

    @Test
    void quarantinesMalformedRecognizedTempAndRecovers() throws Exception {
        Files.writeString(directory.resolve(".diagnostic-report-not-a-report.tmp"), "not-json");

        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(directory);

        assertTrue(Files.exists(directory.resolve(DurableDiagnosticReportStore.QUARANTINE_DIRECTORY)));
        try (Stream<Path> paths = Files.list(directory.resolve(DurableDiagnosticReportStore.QUARANTINE_DIRECTORY))) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().startsWith("malformed-")
                && path.getFileName().toString().endsWith(".evidence")));
        }
        assertTrue(store.list().isEmpty());
    }

    @Test
    void failedPublicationLeavesDistinctEvidenceAcrossRestart() throws Exception {
        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(directory);
        UUID reportId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        Files.createDirectory(directory.resolve(reportId + ".json"));

        assertThrows(IllegalStateException.class, () -> store.persist(reportId, List.of()));
        Files.delete(directory.resolve(reportId + ".json"));

        DurableDiagnosticReportStore restarted = new DurableDiagnosticReportStore(directory);

        assertTrue(restarted.load(reportId).isEmpty());
        try (Stream<Path> paths = Files.list(directory.resolve(DurableDiagnosticReportStore.QUARANTINE_DIRECTORY))) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().startsWith("failed-")
                && path.getFileName().toString().endsWith(".evidence")));
        }
    }

    @Test
    void newerGenerationWinsWhenClockMovesBackward() throws Exception {
        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(directory);
        UUID reportId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        DurableDiagnosticReportStore.StoredReport first = store.persist(reportId, List.of());
        assertEquals(1L, first.revision());

        UUID tempId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        Files.write(directory.resolve(reportId + ".json-" + tempId + ".tmp"), reportBytes(reportId, Instant.EPOCH, 2L));

        DurableDiagnosticReportStore restarted = new DurableDiagnosticReportStore(directory);

        assertEquals(2L, restarted.require(reportId).revision());
        assertEquals(Instant.EPOCH, restarted.require(reportId).createdAt());
    }

    @Test
    void listReturnsRecoveredGenerationAndRevalidatesUnchangedSizeFiles() throws Exception {
        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(directory);
        UUID reportId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID unrelatedId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        store.persist(reportId, List.of());
        store.persist(unrelatedId, List.of());
        Path temporary = directory.resolve(reportId + ".json-" + unrelatedId + ".tmp");
        Files.write(temporary, reportBytes(reportId, Instant.EPOCH, 2L));

        List<DurableDiagnosticReportStore.StoredReport> reports = store.list();

        assertEquals(List.of(unrelatedId, reportId), reports.stream().map(DurableDiagnosticReportStore.StoredReport::reportId).toList());
        assertEquals(2L, reports.get(1).revision());
        assertEquals(Instant.EPOCH, reports.get(1).createdAt());
        assertFalse(Files.exists(temporary));
        Path target = directory.resolve(unrelatedId + ".json");
        byte[] corrupted = Files.readAllBytes(target);
        corrupted[0] = '!';
        Files.write(target, corrupted);
        assertThrows(IllegalArgumentException.class, store::list);
    }

    @Test
    void appendRecoversTargetTemporaryBeforeAllocatingRevision() throws Exception {
        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(directory);
        UUID reportId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        assertEquals(1L, store.persist(reportId, List.of()).revision());
        Path temporary = directory.resolve(reportId + ".json-" + reportId + ".tmp");
        Files.write(temporary, reportBytes(reportId, Instant.EPOCH, 2L));

        DurableDiagnosticReportStore.StoredReport appended = store.persist(reportId, List.of());

        assertEquals(3L, appended.revision());
        assertEquals(3L, store.require(reportId).revision());
        assertFalse(Files.exists(temporary));
    }

    @Test
    void appendIsTargetLocalWhileListStillValidatesWholeStore() throws Exception {
        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(directory);
        Files.writeString(directory.resolve(new UUID(0L, 1L) + ".json"), "not-json");
        UUID reportId = UUID.fromString("88888888-8888-4888-8888-888888888888");

        DurableDiagnosticReportStore.StoredReport persisted = store.persist(reportId, List.of());

        assertEquals(1L, persisted.revision());
        assertTrue(Files.isRegularFile(directory.resolve(reportId + ".json")));
        assertThrows(IllegalArgumentException.class, store::list);
    }

    @Test
    void startupRecoversLegacyTemporaryReport() throws Exception {
        UUID reportId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        Files.write(directory.resolve(".diagnostic-report-legacy.tmp"), reportBytes(reportId, Instant.EPOCH, 1L));

        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(directory);

        assertEquals(1L, store.require(reportId).revision());
        assertEquals(Instant.EPOCH, store.require(reportId).createdAt());
        assertFalse(Files.exists(directory.resolve(".diagnostic-report-legacy.tmp")));
    }

    @Test
    void unknownFieldsAreDeeplyImmutable() {
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("nested", new LinkedHashMap<>(Map.of("values", new ArrayList<>(List.of("value")))));
        DurableDiagnosticReportStore.StoredReport report = new DurableDiagnosticReportStore.StoredReport(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            Instant.EPOCH,
            new DiagnosticSet(List.of()),
            unknown,
            "hash",
            new byte[0]
        );

        assertThrows(UnsupportedOperationException.class, () -> report.unknown().put("new", true));
        Map<?, ?> nested = (Map<?, ?>) report.unknown().get("nested");
        assertThrows(UnsupportedOperationException.class, nested::clear);
        List<?> values = (List<?>) nested.get("values");
        assertThrows(UnsupportedOperationException.class, values::clear);
        assertFalse(report.unknown().isEmpty());
    }

    @Test
    void instancesSerializeRevisionAllocation() throws Exception {
        DurableDiagnosticReportStore firstStore = new DurableDiagnosticReportStore(directory);
        DurableDiagnosticReportStore secondStore = new DurableDiagnosticReportStore(directory);
        UUID reportId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DurableDiagnosticReportStore.StoredReport> first = executor.submit(
                () -> firstStore.persist(reportId, List.of()));
            Future<DurableDiagnosticReportStore.StoredReport> second = executor.submit(
                () -> secondStore.persist(reportId, List.of()));

            assertEquals(Set.of(1L, 2L), Set.of(first.get().revision(), second.get().revision()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsOversizedTempBeforeReadingIt() throws Exception {
        Path temporary = directory.resolve(".diagnostic-report-oversized.tmp");
        Files.write(temporary, new byte[DurableDiagnosticReportStore.MAX_TEMP_BYTES + 1]);

        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(directory);

        assertTrue(store.list().isEmpty());
        try (Stream<Path> paths = Files.list(directory.resolve(DurableDiagnosticReportStore.QUARANTINE_DIRECTORY))) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().startsWith("malformed-")));
        }
    }

    private static byte[] reportBytes(UUID reportId, Instant createdAt, long revision) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("kind", "diagnostic-report");
        base.put("version", 1);
        base.put("reportId", reportId.toString());
        base.put("createdAt", createdAt.toString());
        base.put("diagnostics", List.of());
        base.put("revision", revision);
        String hash = CanonicalHash.sha256(JsonValue.fromJava(base));
        Map<String, Object> document = new LinkedHashMap<>(base);
        document.put("contentHash", hash);
        return JsonValue.fromJava(document).canonicalBytes();
    }
}
