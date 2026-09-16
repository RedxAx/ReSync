package restudio.resync.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.jobs.FlowJobRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobsPersistenceBoundaryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void genericJobRegistriesDoNotCreateADataRootJournal() throws IOException {
        Path jobsRoot = Files.createDirectory(temporaryDirectory.resolve("jobs"));
        FlowJobRegistry flowJobs = new FlowJobRegistry();
        JobManager packetJobs = new JobManager(new FlowJobRegistry(), null);

        var flowJob = flowJobs.create("flow", "client");
        JobRecord<String> packetJob = packetJobs.create("saveFlow", "client", "flow");
        packetJob.markRunning();

        assertEquals(flowJob, flowJobs.get(flowJob.getId()));
        assertEquals(packetJob, packetJobs.get(packetJob.getJobId()));
        try (var files = Files.list(jobsRoot)) {
            assertTrue(files.findAny().isEmpty());
        }
    }
}
