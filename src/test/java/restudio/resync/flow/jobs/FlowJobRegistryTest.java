package restudio.resync.flow.jobs;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowJobReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowJobRegistryTest {
    @Test
    void jobLifecyclePublishesCanonicalSnapshots() {
        FlowJobRegistry registry = new FlowJobRegistry();
        List<FlowJobReference.Snapshot<?>> snapshots = new ArrayList<>();
        registry.addListener(snapshots::add);

        FlowJobReference<String> job = registry.create("compile", "flow:test");
        registry.start(job);
        registry.update(job, 0.5, Map.of("phase", "compile"));
        registry.succeed(job, "ready");

        assertEquals(FlowJobReference.State.SUCCEEDED, job.getState());
        assertEquals(1.0, job.getProgress());
        assertEquals("ready", job.snapshot().outcome().value());
        assertEquals(4, snapshots.size());
        assertEquals(FlowJobReference.State.SUCCEEDED, snapshots.getLast().state());
    }

    @Test
    void cancellationInvokesTheOwnedCancellationAction() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<String> job = registry.create("preview", "flow:test");
        AtomicBoolean cancelled = new AtomicBoolean();
        job.setCancellation(() -> cancelled.set(true));
        registry.start(job);

        assertTrue(registry.cancel(job));
        job.getCompletion().join();
        assertTrue(registry.awaitIdle(Duration.ofSeconds(1)));
        assertTrue(cancelled.get());
        assertEquals(FlowJobReference.State.CANCELLED, job.getState());
        assertFalse(registry.cancel(job));
    }

    @Test
    void physicalCancellationStaysNonterminalUntilTermination() throws Exception {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<Void> job = registry.create("preview", "flow:test");
        registry.start(job);
        CompletableFuture<Void> termination = new CompletableFuture<>();
        CountDownLatch cancellationRequested = new CountDownLatch(1);
        List<FlowJobReference.Snapshot<?>> snapshots = new ArrayList<>();
        registry.addListener(snapshots::add);
        registry.bind(job, cancellationRequested::countDown, termination);

        assertTrue(registry.cancel(job));
        assertTrue(cancellationRequested.await(1, TimeUnit.SECONDS));
        assertEquals(FlowJobReference.State.CANCELLING, job.getState());
        assertFalse(job.getCompletion().isDone());
        assertEquals(1, registry.physicalTaskCount());
        assertTrue(snapshots.stream().noneMatch(snapshot -> snapshot.state() == FlowJobReference.State.CANCELLED));

        termination.complete(null);
        job.getCompletion().join();

        assertEquals(FlowJobReference.State.CANCELLED, job.getState());
        assertEquals(0, registry.physicalTaskCount());
        assertEquals(1L, snapshots.stream().filter(snapshot -> snapshot.state() == FlowJobReference.State.CANCELLED).count());
    }

    @Test
    void latePhysicalBindingCannotRaceLogicalCancellation() throws Exception {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<Void> job = registry.create("preview", "flow:test");
        registry.start(job);
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        job.setCancellation(() -> {
            cancellationStarted.countDown();
            try {
                releaseCancellation.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });

        assertTrue(registry.cancel(job));
        assertTrue(cancellationStarted.await(1, TimeUnit.SECONDS));
        CompletableFuture<Void> termination = new CompletableFuture<>();
        CountDownLatch physicalCancellation = new CountDownLatch(1);
        registry.bind(job, physicalCancellation::countDown, termination);
        releaseCancellation.countDown();

        assertTrue(physicalCancellation.await(1, TimeUnit.SECONDS));
        assertFalse(job.getCompletion().isDone());
        termination.complete(null);
        job.getCompletion().join();

        assertEquals(FlowJobReference.State.CANCELLED, job.getState());
        assertEquals(0, registry.activeJobCount());
        assertEquals(0, registry.physicalTaskCount());
    }

    @Test
    void exceptionalPhysicalTerminationFailsLogicalJobAndReleasesLifecycle() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<Void> job = registry.create("physical", "flow:test");
        registry.start(job);
        CompletableFuture<Void> termination = new CompletableFuture<>();
        registry.bind(job, () -> { }, termination);

        termination.completeExceptionally(new IllegalStateException("termination failed"));
        var outcome = job.getCompletion().join();

        assertFalse(outcome.success());
        assertEquals("JOB_FAILED", outcome.errorCode());
        assertEquals(FlowJobReference.State.FAILED, job.getState());
        assertEquals(0, registry.activeJobCount());
        assertEquals(0, registry.physicalTaskCount());
        assertEquals("termination failed", outcome.details().get("error"));
        assertEquals("physical termination: termination failed", registry.health().failures().get(job.getId()));
    }

    @Test
    void exceptionalPhysicalTerminationAfterCancellationRetainsFailureOnCancellation() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<Void> job = registry.create("physical", "flow:test");
        registry.start(job);
        CompletableFuture<Void> termination = new CompletableFuture<>();
        registry.bind(job, () -> { }, termination);

        assertTrue(registry.cancel(job));
        termination.completeExceptionally(new IllegalStateException("cancel termination failed"));
        var outcome = job.getCompletion().join();

        assertFalse(outcome.success());
        assertEquals("JOB_CANCELLED", outcome.errorCode());
        assertEquals(FlowJobReference.State.CANCELLED, job.getState());
        assertEquals(0, registry.activeJobCount());
        assertEquals(0, registry.physicalTaskCount());
        assertEquals("cancel termination failed", outcome.details().get("error"));
        assertEquals("physical termination: cancel termination failed", registry.health().failures().get(job.getId()));
    }

    @Test
    void terminalRetentionRemovesExpiredJobs() {
        FlowJobRegistry registry = new FlowJobRegistry(16, Duration.ZERO);
        FlowJobReference<String> completed = registry.create("compile", "flow:test");
        registry.succeed(completed, "ready");

        registry.create("compile", "flow:test");

        assertFalse(registry.snapshots("flow:test").stream().anyMatch(snapshot -> snapshot.id().equals(completed.getId())));
    }

    @Test
    void quiesceClosesAdmissionCancelsInStableOrderAndResumesOnlyAfterDrain() {
        FlowJobRegistry registry = new FlowJobRegistry();
        List<FlowJobReference<?>> jobs = List.of(
            registry.create("first", "flow:test"),
            registry.create("second", "flow:test"),
            registry.create("third", "flow:test"));
        List<String> cancelled = new ArrayList<>();
        jobs.forEach(job -> job.setCancellation(() -> cancelled.add(job.getId())));

        registry.quiesce().join();

        List<String> expected = jobs.stream()
            .sorted(Comparator.comparing(FlowJobReference<?>::getCreatedAt).thenComparing(FlowJobReference::getId))
            .map(FlowJobReference::getId)
            .toList();
        assertEquals(expected, cancelled);
        assertEquals(FlowJobRegistry.State.QUIESCED, registry.state());
        assertEquals(0, registry.activeJobCount());
        assertThrows(IllegalStateException.class, () -> registry.create("blocked", "flow:test"));

        registry.resume();
        assertEquals(FlowJobRegistry.State.OPEN, registry.state());
        assertTrue(registry.create("accepted", "flow:test") != null);
    }

    @Test
    void cancellationClosesAsyncAdmissionBeforeConcurrentCreatorsCanEnter() throws Exception {
        FlowJobRegistry registry = new FlowJobRegistry();
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch creatorReady = new CountDownLatch(1);
        CountDownLatch releaseCreator = new CountDownLatch(1);
        FlowJobReference<Void> active = registry.create("async", "flow:test");
        active.setCancellation(cancelled::countDown);
        registry.start(active);

        CompletableFuture<FlowJobReference<Void>> creator = CompletableFuture.supplyAsync(() -> {
            creatorReady.countDown();
            try {
                releaseCreator.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return registry.create("blocked", "flow:test");
        });
        assertTrue(creatorReady.await(1, TimeUnit.SECONDS));
        registry.closeAdmission();
        assertEquals(FlowJobRegistry.State.ADMISSION_CLOSED, registry.state());
        releaseCreator.countDown();
        assertThrows(CompletionException.class, creator::join);
        registry.quiesce().get(1, TimeUnit.SECONDS);

        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertEquals(0, registry.activeJobCount());
        assertTrue(registry.health().available());
    }

    @Test
    void drainWaitsForPhysicalTerminationAfterLogicalCancellation() throws Exception {
        FlowJobRegistry registry = new FlowJobRegistry(16, Duration.ofMinutes(1), Duration.ofSeconds(1));
        FlowJobReference<Void> job = registry.create("physical", "flow:test");
        registry.start(job);
        CompletableFuture<Void> termination = new CompletableFuture<>();
        CountDownLatch cancellationRequested = new CountDownLatch(1);
        registry.bind(job, cancellationRequested::countDown, termination);

        CompletableFuture<Void> drain = registry.quiesce(Duration.ofSeconds(1));

        assertTrue(cancellationRequested.await(1, TimeUnit.SECONDS));
        assertFalse(drain.isDone());
        assertEquals(1, registry.physicalTaskCount());
        assertEquals(FlowJobRegistry.State.QUIESCING, registry.state());

        termination.complete(null);
        drain.join();

        assertEquals(FlowJobRegistry.State.QUIESCED, registry.state());
        assertEquals(0, registry.physicalTaskCount());
        assertTrue(registry.health().available());
        registry.resume();
        assertTrue(registry.admissionsOpen());
    }

    @Test
    void physicalDrainTimeoutFailsClosed() {
        FlowJobRegistry registry = new FlowJobRegistry(16, Duration.ofMinutes(1), Duration.ofMillis(40));
        FlowJobReference<Void> job = registry.create("physical", "flow:test");
        registry.start(job);
        registry.bind(job, () -> { }, new CompletableFuture<>());

        assertThrows(CompletionException.class, () -> registry.quiesce(Duration.ofMillis(40)).join());
        assertEquals(FlowJobRegistry.State.FAILED, registry.state());
        assertEquals(1, registry.physicalTaskCount());
        assertFalse(registry.health().available());
        assertTrue(registry.health().failures().containsKey("lifecycle"));
        assertThrows(IllegalStateException.class, registry::resume);
    }

    @Test
    void shutdownRetriesAfterTimedOutPhysicalWorkActuallyTerminates() {
        FlowJobRegistry registry = new FlowJobRegistry(16, Duration.ofMinutes(1), Duration.ofMillis(40));
        FlowJobReference<Void> job = registry.create("physical", "flow:test");
        registry.start(job);
        CompletableFuture<Void> termination = new CompletableFuture<>();
        registry.bind(job, () -> { }, termination);

        assertThrows(CompletionException.class, () -> registry.shutdownAsync().join());
        assertEquals(1, registry.physicalTaskCount());
        assertThrows(IllegalStateException.class, registry::resume);
        termination.complete(null);
        registry.shutdownAsync().join();
        assertEquals(0, registry.physicalTaskCount());
        assertTrue(registry.health().failures().isEmpty());
        assertFalse(registry.admissionsOpen());
    }

    @Test
    void launchCannotBypassClosedAdmission() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<Void> job = registry.create("launch", "flow:test");
        AtomicBoolean started = new AtomicBoolean();
        registry.closeAdmission();

        assertFalse(registry.launch(job, () -> started.set(true)));
        assertFalse(started.get());
    }

    @Test
    void notStartedRetirementCannotBypassAStartedJob() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<Void> job = registry.create("running", "flow:test");
        registry.start(job);
        registry.closeAdmission();

        assertFalse(registry.retireNotStarted(job, "JOB_NOT_STARTED", "Not Started", Map.of()));
        assertEquals(FlowJobReference.State.RUNNING, job.getState());
        assertEquals(job, registry.get(job.getId()));
        assertEquals(1, registry.activeJobCount());
    }

    @Test
    void physicalBindingRequiresTheRegisteredReferenceIdentity() {
        FlowJobRegistry registry = new FlowJobRegistry();
        registry.create("registered", "flow:test");
        FlowJobReference<Void> foreign = new FlowJobReference<>("foreign", "registered", "flow:test");

        assertThrows(IllegalArgumentException.class,
            () -> registry.bind(foreign, () -> { }, new CompletableFuture<>()));
    }
}
