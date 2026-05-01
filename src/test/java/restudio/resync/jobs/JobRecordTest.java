package restudio.resync.jobs;

import org.junit.jupiter.api.Test;

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
}
