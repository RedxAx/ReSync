package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticIdentity;
import restudio.resync.diagnostics.DiagnosticSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryLifecycleDiagnosticsTest {
    @TempDir
    Path tempDirectory;

    @Test
    void executionTimingWindowKeepsBoundedExactSamplesAndResets() {
        var window = new TemporaryLifecycleDiagnostics.TimingWindow();
        for (int index = 1; index <= 5000; index++) {
            window.record(index);
        }
        var timing = window.snapshot();
        assertEquals(5000L, timing.observed());
        assertEquals(4096, timing.retained());
        assertEquals(4796L, timing.p95Nanos());
        assertEquals(4960L, timing.p99Nanos());
        assertEquals(5000L, timing.maximumNanos());
        window.reset();
        assertEquals(new TemporaryLifecycleDiagnostics.Timing(0L, 0, 0L, 0L, 0L), window.snapshot());
        window.record(7L);
        assertEquals(new TemporaryLifecycleDiagnostics.Timing(1L, 1, 7L, 7L, 7L), window.snapshot());
    }

    @Test
    void diagnosticsDefaultToImportantWithExplicitOffOverride() {
        assertEquals(DiagnosticSink.Mode.NORMAL, LifecycleDiagnosticPolicy.resolveMode(null, null, null, null));
        assertEquals(DiagnosticSink.Mode.NORMAL, LifecycleDiagnosticPolicy.resolveMode(null, null, "", ""));
        assertFalse(DiagnosticSink.Mode.NORMAL.accepts(DiagnosticSink.Priority.NORMAL));
        assertTrue(DiagnosticSink.Mode.NORMAL.accepts(DiagnosticSink.Priority.IMPORTANT));
        assertTrue(DiagnosticSink.Mode.RECOVERY.accepts(DiagnosticSink.Priority.NORMAL));
        assertTrue(TemporaryLifecycleDiagnostics.enabled(null, null));
        assertTrue(TemporaryLifecycleDiagnostics.enabled("", ""));
        assertEquals(DiagnosticSink.Mode.NORMAL, LifecycleDiagnosticPolicy.resolveMode(null, null, "true", null));
        assertEquals(DiagnosticSink.Mode.NORMAL, LifecycleDiagnosticPolicy.parseMode("true"));
        assertEquals(DiagnosticSink.Mode.RECOVERY, LifecycleDiagnosticPolicy.parseMode("recovery"));
        assertEquals(DiagnosticSink.Priority.NORMAL, LifecycleDiagnosticPolicy.priority(
            "trigger_execution_terminal", Map.of("outcome", "success"), true));
        assertEquals(DiagnosticSink.Priority.TERMINAL, LifecycleDiagnosticPolicy.priority(
            "trigger_execution_terminal", Map.of("outcome", "failed"), true));
        assertTrue(TemporaryLifecycleDiagnostics.enabled("true", "false"));
        assertTrue(TemporaryLifecycleDiagnostics.enabled(null, "1"));
        assertTrue(TemporaryLifecycleDiagnostics.enabled("recovery", null));
        assertFalse(TemporaryLifecycleDiagnostics.enabled("false", "true"));
        assertFalse(TemporaryLifecycleDiagnostics.enabled("0", null));
        assertFalse(TemporaryLifecycleDiagnostics.enabled(null, "off"));
        assertFalse(TemporaryLifecycleDiagnostics.enabled(null, "no"));
        assertEquals(DiagnosticSink.Mode.OFF, LifecycleDiagnosticPolicy.resolveMode("off", null, "true", null));
        assertEquals(DiagnosticSink.Mode.OFF, LifecycleDiagnosticPolicy.resolveMode(null, "off", "true", null));
    }

    @Test
    void formatsNumericAuthorityEpochAndStableTerminalFields() {
        Map<String, Object> values = TemporaryLifecycleDiagnostics.with(
            TemporaryLifecycleDiagnostics.identity("server", "flow:example", "SAVE", "mutation", "request",
                "correlation", "trace", 9L, AuthorityEpoch.fixed(7L), 3L),
            "outcome", "failed",
            "diagnosticCode", "PERSISTENCE.FLUSH_FAILED",
            "reason", "Participant failed");

        String line = TemporaryLifecycleDiagnostics.format("mutation_terminal", 12L, "fallback", values);

        assertTrue(line.contains("authorityEpoch=7"));
        assertFalse(line.contains("AuthorityEpoch@"));
        assertTrue(line.contains("operation=SAVE"));
        assertTrue(line.contains("outcome=failed"));
        assertTrue(line.contains("diagnosticCode=PERSISTENCE.FLUSH_FAILED"));
        assertTrue(line.contains("reason=\"Participant failed\""));
    }

    @Test
    void omitsSensitiveAndUnknownFieldsAndRedactsSensitiveText() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("payload", "private-document");
        values.put("password", "private-password");
        values.put("path", Path.of("C:\\private\\state.json"));
        values.put("unknown", "not-allowlisted");
        values.put("reason", "token=private-token");
        values.put("rejection", "{\"private\":\"document\"}");
        values.put("source", Path.of("C:\\private\\source.json"));
        values.put("protocol", "failed at file=C:\\private\\protocol.json");

        String line = TemporaryLifecycleDiagnostics.format("failure", 0L, "server", values);

        assertFalse(line.contains("private"));
        assertFalse(line.contains("payload="));
        assertFalse(line.contains("password="));
        assertFalse(line.contains("path="));
        assertFalse(line.contains("unknown="));
        assertTrue(line.contains("reason=[redacted]"));
        assertTrue(line.contains("rejection=[redacted]"));
        assertTrue(line.contains("source=[path]"));
        assertTrue(line.contains("protocol=[redacted]"));
    }

    @Test
    void boundsValuesCollectionsFieldsAndWholeLine() {
        Map<String, Object> values = new LinkedHashMap<>();
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        values.put("reason", "x".repeat(1_000));
        values.put("source", new ArrayList<>(IntStream.range(0, 100).boxed().toList()));
        values.put("recovery", cyclic);
        for (String field : List.of(
            "resourceType", "resourceId", "outcome", "diagnosticCode", "receiptStatus", "phase", "stageName", "protocol",
            "rejection", "errorCode", "transactionId", "responseMutationId", "responseRevision", "resultRevision",
            "authoritativeRevision", "primaryRevision", "metadataRevision", "sequence", "keyCount", "count", "resourceCount",
            "assetCount", "metadataDeltaCount", "removedResourceCount", "pendingCount", "recoveredCount", "skippedCount",
            "blockedCount", "unpublishedCount", "mutationCount", "journalCount", "preparedCount", "historyEntryCount")) {
            values.put(field, "value");
        }

        String line = TemporaryLifecycleDiagnostics.format("bounded", 1L, "server", values);

        assertTrue(line.length() <= TemporaryLifecycleDiagnostics.MAX_LINE_CHARS);
        assertTrue(fieldCount(line) <= TemporaryLifecycleDiagnostics.MAX_FIELDS);
        assertTrue(line.contains("...(+84)"));
        assertTrue(line.contains("[nested]"));
        assertTrue(line.contains("truncated=true"));
        String reason = fieldValue(line, "reason");
        assertTrue(reason.length() <= TemporaryLifecycleDiagnostics.MAX_VALUE_CHARS + 2);
    }

    @Test
    void retainsPersistenceStateCountsAndPhaseTimings() {
        Map<String, Object> values = Map.ofEntries(
            Map.entry("state", "QUIESCED"),
            Map.entry("bindingCount", 25),
            Map.entry("participantCount", 24),
            Map.entry("flushMs", 11),
            Map.entry("quiesceMs", 12),
            Map.entry("rebindMs", 13),
            Map.entry("authoritativeChecksMs", 14),
            Map.entry("resumeMs", 15),
            Map.entry("derivedChecksMs", 16),
            Map.entry("ownershipValidationMs", 17),
            Map.entry("activationMs", 18),
            Map.entry("convergenceMs", 19));

        String line = TemporaryLifecycleDiagnostics.format("persistence_terminal", 20L, "server", values);

        assertTrue(line.contains("state=QUIESCED"));
        assertTrue(line.contains("bindingCount=25"));
        assertTrue(line.contains("participantCount=24"));
        assertTrue(line.contains("flushMs=11"));
        assertTrue(line.contains("quiesceMs=12"));
        assertTrue(line.contains("rebindMs=13"));
        assertTrue(line.contains("authoritativeChecksMs=14"));
        assertTrue(line.contains("resumeMs=15"));
        assertTrue(line.contains("derivedChecksMs=16"));
        assertTrue(line.contains("ownershipValidationMs=17"));
        assertTrue(line.contains("activationMs=18"));
        assertTrue(line.contains("convergenceMs=19"));
    }

    @Test
    void recoversEachBoundedIoFailureAndRetainsUniqueOrderedEvents() throws Exception {
        List<String> failedSteps = List.of(
            "write", "post-write", "flush", "post-flush", "close", "post-move", "open", "header");
        for (String failedStep : failedSteps) {
            Path root = Files.createDirectory(tempDirectory.resolve(failedStep));
            AtomicBoolean armed = new AtomicBoolean();
            AtomicBoolean failed = new AtomicBoolean();
            LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(root, DiagnosticSink.Mode.VERBOSE,
                9_000L, step -> {
                    if (armed.get() && failedStep.equals(step) && failed.compareAndSet(false, true)) {
                        throw new IOException("injected");
                    }
                });
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            armed.set(true);
            for (int index = 0; index < 80; index++) {
                assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(event("rollover_event_" + index,
                    DiagnosticSink.Priority.NORMAL)));
            }
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            DiagnosticSink.Status status = sink.status();
            sink.close();

            assertTrue(failed.get(), failedStep);
            assertEquals(DiagnosticSink.State.READY, status.state(), failedStep);
            assertTrue(status.reason().startsWith("recovered:"), failedStep);
            List<String> lines = diagnosticLines(root);
            assertEquals(80L, lines.stream().filter(line -> line.contains("\"stage\":\"rollover_event_")).count(),
                failedStep);
            assertEquals(1L, lines.stream().filter(line -> line.contains("\"stage\":\"rollover_event_79\"")).count(),
                failedStep);
            assertPhysicalSequenceOrder(lines);
        }
    }

    @Test
    void repairsAnOversizedPartialTailBeforeRotatingIt() throws Exception {
        Path directory = Files.createDirectories(tempDirectory.resolve(LifecycleDiagnosticFileSink.DIRECTORY));
        Path active = directory.resolve(LifecycleDiagnosticFileSink.FILE_NAME);
        String complete = "{\"kind\":\"seed\"}\n".repeat(580);
        String partial = "partial-tail".repeat(80);
        assertTrue(complete.length() < 10_000);
        assertTrue(complete.length() + partial.length() > 10_000);
        Files.writeString(active, complete + partial);

        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory,
            DiagnosticSink.Mode.VERBOSE, 10_000L, step -> { });
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        assertEquals(DiagnosticSink.Offer.ACCEPTED,
            sink.offer(event("after_partial_recovery", DiagnosticSink.Priority.TERMINAL)));
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        sink.close();

        List<String> lines = diagnosticLines(tempDirectory);
        assertEquals(1L, lines.stream().filter(line -> line.contains("\"stage\":\"after_partial_recovery\"")).count());
        assertTrue(lines.stream().noneMatch(line -> line.contains("partial-tail")));
        try (var files = Files.list(directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("resync-lifecycle.0")));
        }
        assertPhysicalSequenceOrder(lines);
    }

    @Test
    void uncertainFlushOrCloseNeverDuplicatesTerminalEvidence() throws Exception {
        for (String failedStep : List.of("post-write", "flush", "post-flush")) {
            Path root = Files.createDirectory(tempDirectory.resolve(failedStep));
            AtomicBoolean armed = new AtomicBoolean();
            AtomicBoolean failed = new AtomicBoolean();
            LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(root, DiagnosticSink.Mode.VERBOSE,
                64_000L, step -> {
                    if (armed.get() && failedStep.equals(step) && failed.compareAndSet(false, true)) {
                        throw new IOException("injected");
                    }
                });
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            armed.set(true);
            assertEquals(DiagnosticSink.Offer.ACCEPTED,
                sink.offer(event("terminal_once", DiagnosticSink.Priority.TERMINAL)));
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            sink.close();

            List<String> lines = diagnosticLines(root);
            assertTrue(failed.get(), failedStep);
            assertEquals(1L, lines.stream().filter(line -> line.contains("\"stage\":\"terminal_once\"")).count(),
                failedStep);
            assertPhysicalSequenceOrder(lines);
        }
    }

    @Test
    void combinedUncertainFlushAndCloseNeverRewritesTerminalEvidence() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean flushFailed = new AtomicBoolean();
        AtomicBoolean closeFailed = new AtomicBoolean();
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory, DiagnosticSink.Mode.VERBOSE,
            64_000L, step -> {
                if (armed.get() && "flush".equals(step) && flushFailed.compareAndSet(false, true)) {
                    throw new IOException("flush injected");
                }
                if (armed.get() && "close".equals(step) && closeFailed.compareAndSet(false, true)) {
                    throw new IOException("close injected");
                }
            });
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        armed.set(true);
        assertEquals(DiagnosticSink.Offer.ACCEPTED,
            sink.offer(event("terminal_once", DiagnosticSink.Priority.TERMINAL)));
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        sink.close();

        assertTrue(flushFailed.get());
        assertTrue(closeFailed.get());
        List<String> lines = diagnosticLines(tempDirectory);
        assertEquals(1L, lines.stream().filter(line -> line.contains("\"stage\":\"terminal_once\"")).count());
        assertPhysicalSequenceOrder(lines);
    }

    @Test
    void concurrentFlushAndCloseCannotAcknowledgeAFailedFinalWrite() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory, DiagnosticSink.Mode.VERBOSE,
            64_000L, step -> {
                if (armed.get() && "write".equals(step)) {
                    writeEntered.countDown();
                    await(release);
                    throw new IOException("final write injected");
                }
            }, warnings::add);
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        armed.set(true);
        assertEquals(DiagnosticSink.Offer.ACCEPTED,
            sink.offer(event("failed_final_write", DiagnosticSink.Priority.TERMINAL)));
        assertTrue(writeEntered.await(5L, TimeUnit.SECONDS));

        CompletableFuture<DiagnosticSink.Flush> flush = CompletableFuture.supplyAsync(sink::flush);
        awaitCondition(sink::flushPendingForTest);
        CompletableFuture<Void> close = CompletableFuture.runAsync(sink::close);
        awaitCondition(() -> sink.status().closed());
        release.countDown();

        assertEquals(DiagnosticSink.Flush.FAILED, flush.get(5L, TimeUnit.SECONDS));
        close.get(5L, TimeUnit.SECONDS);
        DiagnosticSink.Status status = sink.status();
        assertEquals(DiagnosticSink.State.FAILED, status.state());
        assertTrue(status.reason().contains("unconfirmed=1-1"));
        assertEquals(0L, diagnosticLines(tempDirectory).stream()
            .filter(line -> line.contains("\"stage\":\"failed_final_write\"")).count());
        assertTrue(warnings.stream().anyMatch(line -> line.contains("unconfirmedSequence=1-1")));
    }

    @Test
    void closeTimeoutReportsTheAcceptedUnconfirmedRange() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean blocked = new AtomicBoolean();
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch polling = new CountDownLatch(1);
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory, DiagnosticSink.Mode.VERBOSE,
            64_000L, step -> {
                if (armed.get() && "normal-poll".equals(step) && blocked.compareAndSet(false, true)) {
                    polling.countDown();
                    while (!release.get()) {
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }, warnings::add);
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        armed.set(true);
        assertTrue(polling.await(5L, TimeUnit.SECONDS));
        assertEquals(DiagnosticSink.Offer.ACCEPTED,
            sink.offer(event("close_timeout_pending", DiagnosticSink.Priority.IMPORTANT)));

        sink.close();
        try {
            DiagnosticSink.Status status = sink.status();
            assertEquals(DiagnosticSink.State.FAILED, status.state());
            assertTrue(status.reason().contains("close-timeout"));
            assertTrue(status.reason().contains("unconfirmed=1-1"));
            assertTrue(warnings.stream().anyMatch(line -> line.contains("unconfirmedSequence=1-1")));
        } finally {
            release.set(true);
            awaitCondition(() -> !sink.writerAliveForTest());
        }
    }

    @Test
    void firstNormalPollStillPublishesAnOlderCriticalSequenceFirst() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean blocked = new AtomicBoolean();
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory, DiagnosticSink.Mode.VERBOSE,
            64_000L, step -> {
                if (armed.get() && "normal-poll".equals(step) && blocked.compareAndSet(false, true)) {
                    polling.countDown();
                    await(release);
                }
            });
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        armed.set(true);
        assertTrue(polling.await(5L, TimeUnit.SECONDS));
        assertEquals(DiagnosticSink.Offer.ACCEPTED,
            sink.offer(event("older_critical", DiagnosticSink.Priority.IMPORTANT)));
        assertEquals(DiagnosticSink.Offer.ACCEPTED,
            sink.offer(event("newer_normal", DiagnosticSink.Priority.NORMAL)));
        release.countDown();
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        sink.close();

        List<String> lines = diagnosticLines(tempDirectory);
        assertTrue(sequenceOf(lines, "older_critical") < sequenceOf(lines, "newer_normal"));
        assertPhysicalSequenceOrder(lines);
    }

    @Test
    void exhaustedRecoveryReportsAnUnconfirmedRangeWithoutCallingItLost() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory, DiagnosticSink.Mode.VERBOSE,
            9_000L, step -> {
                if (armed.get() && "open".equals(step)) {
                    throw new IOException("injected");
                }
            }, warnings::add);
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        armed.set(true);
        for (int index = 0; index < 80; index++) {
            sink.offer(event("exhausted_event_" + index, DiagnosticSink.Priority.NORMAL));
        }

        assertEquals(DiagnosticSink.Flush.FAILED, sink.flush());

        DiagnosticSink.Status status = sink.status();
        assertEquals(DiagnosticSink.State.FAILED, status.state());
        assertTrue(status.reason().contains("unconfirmed="));
        assertFalse(status.reason().contains("lost="));
        sink.close();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("unconfirmedSequence="));
        assertFalse(warnings.get(0).contains("lostSequence="));
    }

    @Test
    void terminalEvidencePreemptsImportantEvidenceWhenTheCriticalQueueIsFull() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory, DiagnosticSink.Mode.VERBOSE,
            64_000L, step -> {
                if ("open".equals(step) && opened.getCount() > 0L) {
                    opened.countDown();
                    try {
                        if (!release.await(5L, TimeUnit.SECONDS)) {
                            throw new IOException("release timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", exception);
                    }
                }
            });
        assertTrue(opened.await(5L, TimeUnit.SECONDS));
        for (int index = 0; index < LifecycleDiagnosticPolicy.CRITICAL_CAPACITY; index++) {
            assertEquals(DiagnosticSink.Offer.ACCEPTED,
                sink.offer(event("important_" + index, DiagnosticSink.Priority.IMPORTANT)));
        }
        assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(event("terminal_evidence", DiagnosticSink.Priority.TERMINAL)));
        release.countDown();
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        sink.close();

        List<String> lines = diagnosticLines(tempDirectory);
        assertEquals(1L, lines.stream().filter(line -> line.contains("\"stage\":\"terminal_evidence\"")).count());
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"droppedCriticalPreempted\":1")));
        assertPhysicalSequenceOrder(lines);
    }

    @Test
    void preemptedAcceptedSequenceRemainsVisibleWhenTheSinkFailsBeforeItsSummary() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean failWrites = new AtomicBoolean();
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory, DiagnosticSink.Mode.VERBOSE,
            64_000L, step -> {
                if ("open".equals(step) && opened.getCount() > 0L) {
                    opened.countDown();
                    await(release);
                }
                if (failWrites.get() && "write".equals(step)) {
                    throw new IOException("write injected");
                }
            }, warnings::add);
        assertTrue(opened.await(5L, TimeUnit.SECONDS));
        for (int index = 0; index < LifecycleDiagnosticPolicy.CRITICAL_CAPACITY; index++) {
            assertEquals(DiagnosticSink.Offer.ACCEPTED,
                sink.offer(event("important_" + index, DiagnosticSink.Priority.IMPORTANT)));
        }
        assertEquals(DiagnosticSink.Offer.ACCEPTED,
            sink.offer(event("terminal_evidence", DiagnosticSink.Priority.TERMINAL)));
        failWrites.set(true);
        release.countDown();

        assertEquals(DiagnosticSink.Flush.FAILED, sink.flush());

        DiagnosticSink.Status status = sink.status();
        assertEquals(DiagnosticSink.State.FAILED, status.state());
        assertTrue(status.reason().contains("unconfirmed=1-" + (LifecycleDiagnosticPolicy.CRITICAL_CAPACITY + 1L)));
        sink.close();
        assertTrue(warnings.stream().anyMatch(line -> line.contains("droppedSequence=1-1")));
        assertTrue(warnings.stream().anyMatch(line -> line.contains("unconfirmedSequence=1-"
            + (LifecycleDiagnosticPolicy.CRITICAL_CAPACITY + 1L))));
    }

    @Test
    void terminalCapacityReportsAreCoalescedWithExactDroppedRange() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory, DiagnosticSink.Mode.VERBOSE,
            256_000L, step -> {
                if ("open".equals(step) && opened.getCount() > 0L) {
                    opened.countDown();
                    await(release);
                }
            }, warnings::add);
        assertTrue(opened.await(5L, TimeUnit.SECONDS));
        for (int index = 0; index < LifecycleDiagnosticPolicy.CRITICAL_CAPACITY; index++) {
            assertEquals(DiagnosticSink.Offer.ACCEPTED,
                sink.offer(event("terminal_" + index, DiagnosticSink.Priority.TERMINAL)));
        }
        int overflow = 16;
        for (int index = 0; index < overflow; index++) {
            assertEquals(DiagnosticSink.Offer.DROPPED,
                sink.offer(event("overflow_" + index, DiagnosticSink.Priority.TERMINAL)));
        }
        assertTrue(warnings.isEmpty());
        release.countDown();
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        DiagnosticSink.Status status = sink.status();
        sink.close();

        assertEquals(overflow, status.dropped());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("count=" + overflow));
        long firstDropped = LifecycleDiagnosticPolicy.CRITICAL_CAPACITY + 1L;
        long lastDropped = LifecycleDiagnosticPolicy.CRITICAL_CAPACITY + overflow;
        assertTrue(warnings.get(0).contains("droppedSequence=" + firstDropped + "-" + lastDropped));
        List<String> lines = diagnosticLines(tempDirectory);
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"droppedTerminalCapacity\":" + overflow)));
        assertPhysicalSequenceOrder(lines);
    }

    @Test
    void rotationNamesRemainLexicallyOrderedBeyondSingleDigits() {
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000001");

        String second = LifecycleDiagnosticFileSink.rotatedFileName(1_000L, 2L, session);
        String tenth = LifecycleDiagnosticFileSink.rotatedFileName(1_000L, 10L, session);

        assertTrue(second.compareTo(tenth) < 0);
    }

    @Test
    void fileSinkBoundsVisitedTopLevelAndNestedMapEntries() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        for (int index = 0; index < LifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS + 4; index++) {
            nested.put("password." + index, "hidden");
        }
        nested.put("afterNestedLimit", "must-not-be-visited");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nested", nested);
        for (int index = 0; index < LifecycleDiagnosticPolicy.MAX_FIELDS + 8; index++) {
            values.put("password." + index, "hidden");
        }
        values.put("afterTopLevelLimit", "must-not-be-visited");
        LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(tempDirectory,
            DiagnosticSink.Mode.VERBOSE, 64_000L, step -> { });

        assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(DiagnosticEvent.of("bounded_traversal",
            DiagnosticSink.Priority.TERMINAL, DiagnosticIdentity.empty(), values, 0L)));
        assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
        sink.close();

        String line = diagnosticLines(tempDirectory).stream()
            .filter(candidate -> candidate.contains("\"stage\":\"bounded_traversal\""))
            .findFirst().orElseThrow();
        assertFalse(line.contains("afterNestedLimit"));
        assertFalse(line.contains("afterTopLevelLimit"));
        assertFalse(line.contains("must-not-be-visited"));
        assertTrue(line.contains("\"truncated\":true"));
    }

    private static DiagnosticEvent event(String stage, DiagnosticSink.Priority priority) {
        return DiagnosticEvent.of(stage, priority, DiagnosticIdentity.empty(), Map.of("detail", "x".repeat(240)), 0L);
    }

    private static List<String> diagnosticLines(Path root) throws IOException {
        Path directory = root.resolve(LifecycleDiagnosticFileSink.DIRECTORY);
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.sorted().toList();
        }
        List<String> lines = new ArrayList<>();
        for (Path file : files) {
            lines.addAll(Files.readAllLines(file));
        }
        return lines;
    }

    private static void assertPhysicalSequenceOrder(List<String> lines) {
        long previous = 0L;
        for (String line : lines) {
            if (line.contains("\"kind\":\"header\"")) {
                continue;
            }
            int marker = line.indexOf("\"sequence\":");
            if (marker < 0) {
                continue;
            }
            int start = marker + 11;
            int end = line.indexOf(',', start);
            long sequence = Long.parseLong(line.substring(start, end));
            assertTrue(sequence > previous, sequence + " followed " + previous);
            previous = sequence;
        }
    }

    private static long sequenceOf(List<String> lines, String stage) {
        String marker = "\"stage\":\"" + stage + "\"";
        for (String line : lines) {
            if (!line.contains(marker)) {
                continue;
            }
            int start = line.indexOf("\"sequence\":") + 11;
            int end = line.indexOf(',', start);
            return Long.parseLong(line.substring(start, end));
        }
        return Long.MAX_VALUE;
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IOException("release timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", exception);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(condition.getAsBoolean());
    }

    private static int fieldCount(String line) {
        int fields = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) == ' ') {
                fields++;
            }
        }
        return Math.max(0, fields - 1);
    }

    private static String fieldValue(String line, String field) {
        int start = line.indexOf(" " + field + "=");
        if (start < 0) {
            return "";
        }
        start += field.length() + 2;
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }
}
