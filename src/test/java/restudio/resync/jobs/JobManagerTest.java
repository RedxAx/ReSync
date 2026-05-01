package restudio.resync.jobs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobManagerTest {
    @Test
    void snapshotsFilterByActorClientId() {
        JobManager manager = new JobManager(null);
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
        JobManager manager = new JobManager(null);
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
}
