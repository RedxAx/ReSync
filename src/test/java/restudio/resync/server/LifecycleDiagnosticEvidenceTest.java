package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticIdentity;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleDiagnosticEvidenceTest {
    @TempDir
    Path directory;

    @Test
    void typedIdentitySurvivesPrivacyFilteringAndFileEncoding() throws Exception {
        String server = UUID.randomUUID().toString();
        UUID request = UUID.randomUUID();
        ResourceKey key = new ResourceKey(new ContractRef<>(new OwnerId("restudio.resync"),
            new ResourceTypeId("flow")), "example");
        DiagnosticEvent event = LifecycleDiagnosticEventAdapter.event("resource_terminal", 487L, server,
            Map.of("typedKey", key, "requestId", request, "outcome", "failed"), true);
        assertEquals(key, event.identity().resource().key());
        assertEquals(request, event.identity().requestId());
        try (LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(directory, DiagnosticSink.Mode.RECOVERY, 64_000L, null)) {
            assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(event));
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            String line = Files.readString(sink.activePath());
            assertTrue(line.contains(event.identity().resource().canonicalText()));
            assertTrue(line.contains(request.toString()));
            assertTrue(line.contains("\"elapsedMs\":487"));
        }
    }

    @Test
    void oversizedFailureRetainsStageTimingIdentityAndOutcome() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < 25; index++) values.put("detail" + index, "x".repeat(240));
        values.put("outcome", "failed");
        values.put("diagnosticCode", "RESOURCE.CONFLICT");
        values.put("reason", "revision_conflict");
        DiagnosticEvent event = DiagnosticEvent.of("resource_terminal", DiagnosticSink.Priority.TERMINAL,
            DiagnosticIdentity.empty(), values, 487L);
        try (LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(directory, DiagnosticSink.Mode.RECOVERY, 64_000L, null)) {
            assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(event));
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            String line = Files.readAllLines(sink.activePath()).stream()
                .filter(value -> value.contains("\"kind\":\"event\"")).findFirst().orElseThrow();
            assertTrue(line.contains("\"stage\":\"resource_terminal\""));
            assertTrue(line.contains("\"elapsedMs\":487"));
            assertTrue(line.contains("\"outcome\":\"failed\""));
            assertTrue(line.contains("RESOURCE.CONFLICT"));
            assertTrue(line.contains("\"truncated\":true"));
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length < LifecycleDiagnosticPolicy.MAX_LINE_BYTES);
        }
    }

    @Test
    void unrelatedCountersSurviveWhileCredentialFieldsRemainPrivate() {
        assertFalse(LifecycleDiagnosticPolicy.sensitiveField("descriptorCount"));
        assertFalse(LifecycleDiagnosticPolicy.sensitiveField("ownershipValidationMs"));
        assertFalse(LifecycleDiagnosticPolicy.sensitiveField("participantCount"));
        assertFalse(LifecycleDiagnosticPolicy.sensitiveField("pipelineMs"));
        assertTrue(LifecycleDiagnosticPolicy.sensitiveField("accessToken"));
        assertTrue(LifecycleDiagnosticPolicy.sensitiveField("password"));
        assertTrue(LifecycleDiagnosticPolicy.sensitiveField("absolutePath"));
        assertEquals("[redacted]", LifecycleDiagnosticPolicy.safeText("C:/Users/private/file"));
    }
    @Test
    void listLifecycleBoundariesAndSlowPagesAreNeverThrottled() {
        DiagnosticEvent boundary = DiagnosticEvent.of("resource_list_completed", DiagnosticSink.Priority.NORMAL,
            DiagnosticIdentity.empty(), Map.of("outcome", "completed"), 1L);
        DiagnosticEvent progress = DiagnosticEvent.of("resource_list_page", DiagnosticSink.Priority.NORMAL,
            DiagnosticIdentity.empty(), Map.of("count", 40L), 1L);
        DiagnosticEvent slow = DiagnosticEvent.of("resource_list_page", DiagnosticSink.Priority.NORMAL,
            DiagnosticIdentity.empty(), Map.of("count", 40L), 300L);
        assertFalse(LifecycleDiagnosticPolicy.healthyProgress(boundary));
        assertTrue(LifecycleDiagnosticPolicy.healthyProgress(progress));
        assertFalse(LifecycleDiagnosticPolicy.healthyProgress(slow));
    }

    @Test
    void participantDurationPreservesNativeElapsedPriorityAndLegacyCpuEvidence() throws Exception {
        Map<String, Object> values = Map.of("participantId", "assets", "operation", "flush",
            "participantElapsedMs", 487L, "elapsedMs", 487L, "cpuMs", 31L,
            "phaseCpuTimings", Map.of("flush", 31L), "outcome", "complete");
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(directory,
            DiagnosticSink.Mode.NORMAL, 64_000L, null);
        TemporaryLifecycleDiagnostics.bind(sink);
        try {
            TemporaryLifecycleDiagnostics.eventElapsed("persistence_participant_timing", 487L, values);
            assertEquals(DiagnosticSink.Flush.FLUSHED, TemporaryLifecycleDiagnostics.flush());
            String line = Files.readAllLines(sink.activePath()).stream()
                .filter(value -> value.contains("\"stage\":\"persistence_participant_timing\""))
                .findFirst().orElseThrow();
            assertTrue(line.contains("\"elapsedMs\":487"));
            assertFalse(line.contains("\"elapsedMs\":0"));
            assertTrue(line.contains("\"priority\":\"important\""));
            assertTrue(line.contains("\"cpuMs\":31"));
            assertTrue(line.contains("\"phaseCpuTimings\":{\"flush\":31}"));
        } finally {
            TemporaryLifecycleDiagnostics.close(sink);
        }
        String legacy = TemporaryLifecycleDiagnostics.format("persistence_participant_timing", 487L, "", values);
        assertTrue(legacy.contains("cpuMs=31"));
        assertTrue(legacy.contains("phaseCpuTimings={flush:31}"));
    }

    @Test
    void recoveredFailureWithoutDropsIsSummarizedBeforeFlushAndClose() throws Exception {
        AtomicBoolean injected = new AtomicBoolean();
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(directory,
            DiagnosticSink.Mode.RECOVERY, 64_000L, step -> {
                if (step.equals("open") && injected.compareAndSet(false, true)) throw new IOException("injected");
            });
        try {
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            assertEquals(0L, sink.status().dropped());
            assertTrue(sink.status().failures() > 0L);
            assertEquals(DiagnosticSink.State.READY, sink.status().state());
        } finally {
            sink.close();
        }
        String summary = Files.readAllLines(sink.activePath()).stream()
            .filter(line -> line.contains("\"kind\":\"drop-summary\""))
            .findFirst().orElseThrow();
        assertTrue(summary.contains("\"failed\":1"));
        assertTrue(summary.contains("recovered:IOException"));
    }

}
