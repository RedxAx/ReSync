package restudio.resync.jobs;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowJobReference;
import restudio.resync.flow.jobs.FlowJobRegistry;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRecordTest {
    @Test
    void transitionsFromPendingToRunningToSucceeded() {
        JobRecord<String> job = new JobRecord<>("job-1", "deleteWorld", "client", "world");

        assertEquals(JobStatus.PENDING, job.getStatus());
        assertTrue(job.markRunning());
        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertTrue(job.markSucceeded("ok", "Done"));
        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        assertTrue(job.getFuture().isDone());
        Map<String, Object> snapshot = job.snapshot();
        assertEquals("Done", snapshot.get("message"));
        assertEquals("ok", snapshot.get("result"));
        assertTrue((long) snapshot.get("finishedAt") > 0L);
    }

    @Test
    void cancelsBeforeRunning() {
        JobRecord<String> job = new JobRecord<>("job-1", "deleteWorld", "client", "world");

        assertTrue(job.cancel("Cancelled"));
        assertEquals(JobStatus.CANCELLED, job.getStatus());
        assertTrue(job.getFuture().isCancelled());
    }

    @Test
    void runningJobCanFail() {
        JobRecord<String> job = new JobRecord<>("job-1", "deleteWorld", "client", "world");

        assertTrue(job.markRunning());
        assertTrue(job.markFailed("Failed", new IllegalStateException("disk")));
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertTrue(job.getFuture().isCompletedExceptionally());
    }

    @Test
    void duplicateCompletionIsIgnored() {
        JobRecord<String> job = new JobRecord<>("job-1", "deleteWorld", "client", "world");

        assertTrue(job.markRunning());
        assertTrue(job.markSucceeded("ok", "Done"));
        assertFalse(job.markFailed("Failed", null));
        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
    }

    @Test
    void canonicalCancellationClearsAStaleSuccessPayload() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<String> reference = registry.create("preview", "client");
        JobRecord<String> job = new JobRecord<>("request", "preview", "client", "target", reference, registry);
        assertTrue(job.markRunning());

        assertTrue(registry.cancel(reference));
        job.synchronizeCanonical();

        assertEquals(JobStatus.CANCELLED, job.getStatus());
        assertFalse(job.snapshot().containsKey("result"));
        assertEquals("Job Cancelled", job.snapshot().get("message"));
    }

    @Test
    void rejectedCanonicalSuccessRestoresCancellationState() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<String> reference = registry.create("preview", "client");
        JobRecord<String> job = new JobRecord<>("request", "preview", "client", "target", reference, registry);
        assertTrue(job.markRunning());

        assertTrue(reference.requestCancellation());
        assertFalse(job.markSucceeded("late", "Late Success"));
        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertEquals("Cancellation Requested", job.snapshot().get("message"));
        assertFalse(job.snapshot().containsKey("result"));

        assertTrue(registry.completeCancellationAfterPhysical(reference));
        assertEquals(JobStatus.CANCELLED, job.getStatus());
    }

    @Test
    void concurrentCanonicalTerminalCallersKeepTheWinningPayload() throws Exception {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<String> reference = registry.create("preview", "client");
        JobRecord<String> job = new JobRecord<>("request", "preview", "client", "target", reference, registry);
        assertTrue(job.markRunning());
        CountDownLatch terminalListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseTerminalListener = new CountDownLatch(1);
        registry.addListener(snapshot -> {
            if (switch (snapshot.state()) {
                case SUCCEEDED, FAILED, CANCELLED -> true;
                case PENDING, RUNNING, CANCELLING -> false;
            }) {
                terminalListenerEntered.countDown();
                try {
                    releaseTerminalListener.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> winner = executor.submit(() -> job.markSucceeded("winner", "Winner"));
            assertTrue(terminalListenerEntered.await(5, TimeUnit.SECONDS));
            Future<Boolean> loser = executor.submit(() -> job.markFailed("Loser", new IllegalStateException("loser")));
            assertFalse(loser.get(5, TimeUnit.SECONDS));
            releaseTerminalListener.countDown();
            assertTrue(winner.get(5, TimeUnit.SECONDS));
        } finally {
            releaseTerminalListener.countDown();
            executor.shutdownNow();
        }
        Map<String, Object> snapshot = job.snapshot();
        assertEquals(JobStatus.SUCCEEDED.wireName(), snapshot.get("status"));
        assertEquals("Winner", snapshot.get("message"));
        assertEquals("winner", snapshot.get("result"));
        assertTrue((long) snapshot.get("finishedAt") > 0L);
    }

    @Test
    void canonicalFailureSnapshotFallsBackToOutcomeMessageWithoutErrorDetail() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowJobReference<String> reference = registry.create("preview", "client");
        JobRecord<String> job = new JobRecord<>("request", "preview", "client", "target", reference, registry);
        assertTrue(job.markRunning());

        assertTrue(registry.fail(reference, "CANONICAL_FAILURE", "Canonical Failure", Map.of()));

        Map<String, Object> snapshot = job.snapshot();
        assertEquals("Canonical Failure", snapshot.get("message"));
        assertEquals("Canonical Failure", snapshot.get("errorText"));
    }
}
