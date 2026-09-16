package restudio.resync.jobs;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowJobReference;
import restudio.resync.flow.jobs.FlowJobRegistry;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobManagerTest {
    @Test
    void snapshotsFilterByActorClientId() {
        JobManager manager = new JobManager(new FlowJobRegistry(), null);
        JobRecord<String> first = manager.create("saveFlow", "client-a", "flow");
        JobRecord<String> second = manager.create("saveTab", "client-b", "tab");
        first.markRunning();
        second.markRunning();

        List<Map<String, Object>> snapshot = manager.snapshot("client-a");

        assertEquals(1, snapshot.size());
        assertEquals(first.getJobId(), snapshot.getFirst().get("jobId"));
    }

    @Test
    void activeOrRecentSnapshotKeepsRunningAndRecentTerminalJobs() {
        JobManager manager = new JobManager(new FlowJobRegistry(), null);
        JobRecord<String> running = manager.create("saveFlow", "client", "flow");
        JobRecord<String> succeeded = manager.create("saveTab", "client", "tab");
        running.markRunning();
        succeeded.markRunning();
        succeeded.markSucceeded("tab", "Saved");

        List<Map<String, Object>> snapshot = manager.activeOrRecentSnapshot("client", 60000);

        assertEquals(2, snapshot.size());
        assertTrue(snapshot.stream().anyMatch(job -> running.getJobId().equals(job.get("jobId"))));
        assertTrue(snapshot.stream().anyMatch(job -> succeeded.getJobId().equals(job.get("jobId"))));
    }

    @Test
    void duplicateRequestIdReturnsExistingJob() {
        JobManager manager = new JobManager(new FlowJobRegistry(), null);
        JobRecord<String> first = manager.create("saveFlow", "client", "flow", "request-1");
        first.markRunning();

        JobRecord<String> retry = manager.create("saveFlow", "client", "flow", "request-1");

        assertEquals(first.getJobId(), retry.getJobId());
        assertEquals("request-1", retry.getRequestId());
    }

    @Test
    void requestIdsAreScopedPerClient() {
        JobManager manager = new JobManager(new FlowJobRegistry(), null);
        JobRecord<String> first = manager.create("saveFlow", "client-a", "flow", "request-1");
        JobRecord<String> second = manager.create("saveFlow", "client-b", "flow", "request-1");

        assertTrue(!first.getJobId().equals(second.getJobId()));
    }

    @Test
    void packetJobsUseTheCanonicalRegistryWhenProvided() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);

        JobRecord<String> job = manager.create("saveFlow", "client", "flow", "request-1");
        job.markRunning();
        job.markSucceeded("saved", "Saved");

        FlowJobReference<?> canonical = registry.get(job.getJobId());
        assertEquals(FlowJobReference.State.SUCCEEDED, canonical.getState());
        assertEquals("request-1", canonical.getMetadata().get("requestId"));
        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
    }

    @Test
    void operationIdRemainsDistinctFromRequestIdAcrossSnapshots() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);

        JobManager.StartedJob<String> started = manager.createStarted("saveWorldGen", "client", "project",
            "request-1", "mutation-1", "operation-1", 4L, "intent-1");

        assertTrue(started.started());
        assertEquals("operation-1", started.job().getOperationId());
        assertEquals("operation-1", manager.operationId(started.job()));
        Map<String, Object> snapshot = started.job().snapshot();
        assertEquals("operation-1", snapshot.get("operationId"));
        assertEquals("request-1", snapshot.get("requestId"));
        assertEquals("operation-1", registry.get(started.job().getJobId()).getMetadata().get("operationId"));

        started.execution().complete();
    }

    @Test
    void operationIdFallbackUsesCanonicalJobIdentityInsteadOfRequestId() {
        JobManager manager = new JobManager(new FlowJobRegistry(), null);
        JobRecord<String> job = manager.create("preview", "client", "project", "request-1");

        assertEquals(job.getJobId(), job.getOperationId());
        assertEquals(job.getJobId(), job.snapshot().get("operationId"));
        assertFalse("request-1".equals(job.getOperationId()));
    }

    @Test
    void changedMutationIdentityConflictsAfterTerminalCompletion() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);
        JobManager.StartedJob<String> original = manager.createStarted("preview", "client", "preview", "request-1",
            "mutation-1", "operation-1", 3L, "intent-a");

        original.job().markSucceeded("preview", "Ready");

        JobManager.StartedJob<String> changed = manager.createStarted("preview", "client", "preview", "request-1",
            "mutation-1", "operation-1", 4L, "intent-b");

        assertTrue(changed.identityConflict());
        assertEquals(original.job().getJobId(), changed.job().getJobId());
        original.execution().complete();
        manager.shutdown();
    }

    @Test
    void mutationlessTerminalRequestReplaysOnlyItsExactIdentity() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);
        JobManager.StartedJob<String> original = manager.createStarted("saveFlow", "client", "flow", "request-1");
        original.job().markSucceeded("saved", "Saved");

        JobManager.StartedJob<String> replay = manager.createStarted("saveFlow", "client", "flow", "request-1");
        JobManager.StartedJob<String> changed = manager.createStarted("saveTab", "client", "tab", "request-1");

        assertTrue(replay.terminalReplay());
        assertEquals(original.job().getJobId(), replay.job().getJobId());
        assertTrue(changed.identityConflict());
        assertEquals(original.job().getJobId(), changed.job().getJobId());
        assertEquals(1, registry.snapshots("client").size());
        original.execution().complete();
        manager.shutdown();
    }

    @Test
    void mutationlessTerminalRequestRejectsChangedOperationIdentityWithoutStartingAnotherJob() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);
        JobManager.StartedJob<String> original = manager.createStarted("saveFlow", "client", "flow", "request-1",
            null, "operation-1", 3L, "intent-a");
        original.job().markSucceeded("saved", "Saved");

        JobManager.StartedJob<String> changedOperation = manager.createStarted("saveFlow", "client", "flow",
            "request-1", null, "operation-2", 3L, "intent-a");
        JobManager.StartedJob<String> changedRevision = manager.createStarted("saveFlow", "client", "flow",
            "request-1", null, "operation-1", 4L, "intent-a");
        JobManager.StartedJob<String> changedIntent = manager.createStarted("saveFlow", "client", "flow",
            "request-1", null, "operation-1", 3L, "intent-b");

        assertTrue(changedOperation.identityConflict());
        assertTrue(changedRevision.identityConflict());
        assertTrue(changedIntent.identityConflict());
        assertEquals(original.job().getJobId(), changedOperation.job().getJobId());
        assertEquals(original.job().getJobId(), changedRevision.job().getJobId());
        assertEquals(original.job().getJobId(), changedIntent.job().getJobId());
        assertEquals(1, registry.snapshots("client").size());
        original.execution().complete();
        manager.shutdown();
    }

    @Test
    void mutationIdentityPresenceCannotChangeTerminalRequestIdentity() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);
        JobManager.StartedJob<String> original = manager.createStarted("saveFlow", "client", "flow", "request-1",
            "mutation-1", "operation-1", 3L, "intent-a");
        original.job().markSucceeded("saved", "Saved");

        JobManager.StartedJob<String> changed = manager.createStarted("saveFlow", "client", "flow", "request-1",
            null, "operation-1", 3L, "intent-a");

        assertTrue(changed.identityConflict());
        assertEquals(original.job().getJobId(), changed.job().getJobId());
        assertEquals(1, registry.snapshots("client").size());
        original.execution().complete();
        manager.shutdown();
    }

    @Test
    void canonicalTerminalStatusPublishesOnceAndRetainsExecutionLeaseUntilCompletion() {
        FlowJobRegistry registry = new FlowJobRegistry();
        List<JobRecord<?>> published = new ArrayList<>();
        JobManager manager = new JobManager(registry, published::add);
        JobManager.StartedJob<String> started = manager.createStarted("preview", "client", "preview", "request-1",
            "mutation-1", "operation-1", 3L, "intent-a");
        published.clear();

        started.job().markSucceeded("preview", "Ready");

        assertEquals(1, published.size());
        assertEquals(JobStatus.SUCCEEDED, published.getFirst().getStatus());
        assertEquals("Ready", published.getFirst().snapshot().get("message"));
        assertEquals("preview", published.getFirst().snapshot().get("result"));
        assertEquals(1, registry.physicalTaskCount());
        started.execution().complete();
        assertTrue(registry.awaitIdle(Duration.ofSeconds(1)));
        manager.shutdown();
    }

    @Test
    void canonicalFailurePublicationRetainsCallerErrorText() {
        FlowJobRegistry registry = new FlowJobRegistry();
        List<JobRecord<?>> published = new ArrayList<>();
        JobManager manager = new JobManager(registry, published::add);
        JobRecord<String> job = manager.create("preview", "client", "preview");
        assertTrue(job.markRunning());
        published.clear();

        assertTrue(job.markFailed("Request Failed", new IllegalStateException("Exact Failure Detail")));

        assertEquals(1, published.size());
        Map<String, Object> snapshot = published.getFirst().snapshot();
        assertEquals(JobStatus.FAILED.wireName(), snapshot.get("status"));
        assertEquals("Request Failed", snapshot.get("message"));
        assertEquals("Exact Failure Detail", snapshot.get("errorText"));
        manager.shutdown();
    }

    @Test
    void terminalRetrySurvivesConcurrentSnapshotWhilePublicationIsInProgress() throws Exception {
        FlowJobRegistry registry = new FlowJobRegistry();
        CountDownLatch terminalPublicationEntered = new CountDownLatch(1);
        CountDownLatch releaseTerminalPublication = new CountDownLatch(1);
        JobManager manager = new JobManager(registry, job -> {
            if (!job.getStatus().terminal()) {
                return;
            }
            terminalPublicationEntered.countDown();
            try {
                releaseTerminalPublication.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        JobManager.StartedJob<String> original = manager.createStarted("preview", "client", "preview", "request-race");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> completion = executor.submit(() -> original.job().markSucceeded("preview", "Ready"));
            assertTrue(terminalPublicationEntered.await(5, TimeUnit.SECONDS));

            Future<List<Map<String, Object>>> snapshot = executor.submit(() -> manager.snapshot("client"));
            List<Map<String, Object>> visible = snapshot.get(5, TimeUnit.SECONDS);
            assertTrue(visible.stream().anyMatch(data -> original.job().getJobId().equals(data.get("jobId"))));

            JobManager.StartedJob<String> retry = manager.createStarted("preview", "client", "preview", "request-race");
            assertTrue(retry.terminalReplay());
            assertSame(original.job(), retry.job());

            releaseTerminalPublication.countDown();
            assertTrue(completion.get(5, TimeUnit.SECONDS));
            original.execution().complete();
        } finally {
            releaseTerminalPublication.countDown();
            executor.shutdownNow();
            manager.shutdown();
        }
    }

    @Test
    void failedTerminalRetryUsesCanonicalErrorDetailDuringPublication() throws Exception {
        FlowJobRegistry registry = new FlowJobRegistry();
        CountDownLatch terminalPublicationEntered = new CountDownLatch(1);
        CountDownLatch releaseTerminalPublication = new CountDownLatch(1);
        JobManager manager = new JobManager(registry, job -> {
            if (!job.getStatus().terminal()) {
                return;
            }
            terminalPublicationEntered.countDown();
            try {
                releaseTerminalPublication.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        JobManager.StartedJob<String> original = manager.createStarted("preview", "client", "preview", "request-failure-race");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> completion = executor.submit(() -> original.job().markFailed("Request Failed",
                new IllegalStateException("Exact Failure Detail")));
            assertTrue(terminalPublicationEntered.await(5, TimeUnit.SECONDS));

            JobManager.StartedJob<String> retry = manager.createStarted("preview", "client", "preview",
                "request-failure-race");
            assertTrue(retry.terminalReplay());
            assertSame(original.job(), retry.job());
            Map<String, Object> replaySnapshot = retry.job().snapshot();
            assertEquals(JobStatus.FAILED.wireName(), replaySnapshot.get("status"));
            assertEquals("Request Failed", replaySnapshot.get("message"));
            assertEquals("Exact Failure Detail", replaySnapshot.get("errorText"));

            releaseTerminalPublication.countDown();
            assertTrue(completion.get(5, TimeUnit.SECONDS));
            assertEquals(replaySnapshot.get("errorText"), original.job().snapshot().get("errorText"));
            original.execution().complete();
        } finally {
            releaseTerminalPublication.countDown();
            executor.shutdownNow();
            manager.shutdown();
        }
    }

    @Test
    void shutdownPublishesCancellationAfterPhysicalTerminationAndLeaseRelease() {
        FlowJobRegistry registry = new FlowJobRegistry();
        List<JobRecord<?>> published = new ArrayList<>();
        JobManager manager = new JobManager(registry, published::add);
        JobManager.StartedJob<String> started = manager.createStarted("preview", "client", "preview", "request-1",
            "mutation-1", "operation-1", 3L, "intent-a");
        published.clear();

        CompletableFuture<Void> shutdown = manager.shutdownAsync();

        assertTrue(published.isEmpty());
        assertEquals(JobStatus.RUNNING, started.job().getStatus());
        assertEquals(1, registry.physicalTaskCount());
        assertFalse(shutdown.isDone());
        started.execution().complete();
        shutdown.join();
        assertEquals(1, published.size());
        assertEquals(JobStatus.CANCELLED, published.getFirst().getStatus());
        assertNull(manager.execution(started.job()));
    }

    @Test
    void admissionCloseDuringCreateRetiresPendingCanonicalReference() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, ignored -> registry.closeAdmission());

        JobManager.StartedJob<String> started = manager.createStarted("saveWorldGen", "client", "project", "request-1");

        assertEquals(JobManager.StartDisposition.NOT_STARTED, started.disposition());
        assertEquals(JobStatus.FAILED, started.job().getStatus());
        assertNull(registry.get(started.job().getJobId()));
        assertNull(manager.get(started.job().getJobId()));
        assertEquals(0, registry.activeJobCount());
        assertTrue(registry.snapshots("").stream().noneMatch(snapshot -> snapshot.state() == FlowJobReference.State.PENDING));
    }

    @Test
    void canonicalCancellationPropagatesToPacketJobState() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);
        JobRecord<String> job = manager.create("createWorldGenPreview", "client", "preview");
        job.markRunning();

        assertTrue(registry.cancel(job.getJobId()));
        assertTrue(registry.awaitIdle(Duration.ofSeconds(1)));
        assertEquals(JobStatus.CANCELLED, job.getStatus());
        assertTrue(job.getFuture().isCancelled());
    }

    @Test
    void quiesceCancelsEphemeralJobsAndClosesAdmissionUntilResume() {
        JobManager manager = new JobManager(new FlowJobRegistry(), null);
        JobRecord<String> job = manager.create("saveFlow", "client", "flow");
        job.markRunning();

        manager.quiesce().join();

        assertEquals(JobStatus.CANCELLED, job.getStatus());
        assertEquals(FlowJobRegistry.State.QUIESCED, manager.state());
        assertEquals(0, manager.activeJobCount());
        assertThrows(IllegalStateException.class, () -> manager.create("blocked", "client", "flow"));

        manager.resume();
        assertEquals(FlowJobRegistry.State.OPEN, manager.state());
        assertTrue(manager.create("accepted", "client", "flow") != null);
    }

    @Test
    void canonicalRegistryAdmissionClosesPacketJobCreationToo() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);

        registry.closeAdmission();

        assertThrows(IllegalStateException.class, () -> manager.create("blocked", "client", "flow"));
        assertEquals(FlowJobRegistry.State.ADMISSION_CLOSED, manager.health().state());
    }

    @Test
    void canonicalRegistryIsRequired() {
        assertThrows(NullPointerException.class, () -> new JobManager(null, null));
    }

    @Test
    void startedPacketExecutionLeaseKeepsPhysicalDrainOpenUntilHandlerReturns() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);

        JobManager.StartedJob<String> started = manager.createStarted("saveFlow", "client", "flow", "request-physical");
        assertTrue(started.started());
        assertEquals(JobStatus.RUNNING, started.job().getStatus());
        assertEquals(1, registry.physicalTaskCount());

        var drain = registry.quiesce(Duration.ofSeconds(1));

        assertFalse(drain.isDone());
        assertEquals(1, registry.physicalTaskCount());

        started.execution().complete();
        drain.join();

        assertEquals(0, registry.physicalTaskCount());
        assertTrue(registry.health().available());
    }

    @Test
    void createStartedAndQuiesceRaceNeverLosesAStartedPhysicalLease() {
        for (int attempt = 0; attempt < 32; attempt++) {
            int race = attempt;
            FlowJobRegistry registry = new FlowJobRegistry();
            JobManager manager = new JobManager(registry, null);
            CompletableFuture<JobManager.StartedJob<String>> creation = CompletableFuture.supplyAsync(
                () -> manager.createStarted("saveFlow", "client", "flow", "race-" + race));
            CompletableFuture<CompletableFuture<Void>> quiesce = CompletableFuture.supplyAsync(
                () -> registry.quiesce(Duration.ofSeconds(2)));

            JobManager.StartedJob<String> started = null;
            try {
                started = creation.join();
            } catch (CompletionException ignored) {
            }
            CompletableFuture<Void> drain = quiesce.join();
            if (started != null && started.started()) {
                JobStatus status = started.job().getStatus();
                assertTrue(status == JobStatus.RUNNING || status == JobStatus.CANCELLED);
                assertEquals(1, registry.physicalTaskCount());
                started.execution().complete();
            }
            drain.join();
            assertEquals(0, registry.physicalTaskCount());
            assertEquals(FlowJobRegistry.State.QUIESCED, registry.state());
        }
    }
}
