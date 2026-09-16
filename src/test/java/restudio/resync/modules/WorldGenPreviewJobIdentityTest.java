package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.jobs.JobManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenPreviewJobIdentityTest {
    @Test
    void previewJobIdentitySurvivesAcceptedAndReconnectSnapshots() {
        for (String action : List.of("worldGenPreviewCreate", "worldGenPreviewApply", "worldGenPreviewStop")) {
            FlowJobRegistry registry = new FlowJobRegistry();
            JobManager manager = new JobManager(registry, null);
            String suffix = action.substring("worldGenPreview".length()).toLowerCase();
            String requestId = "request-" + suffix;
            String mutationId = "mutation-" + suffix;
            String operationId = "operation-" + suffix;

            JobManager.StartedJob<String> started = manager.createStarted(action, "client", "preview", requestId,
                mutationId, operationId, 7L, "intent-" + suffix);

            assertTrue(started.started());
            assertIdentity(started.job().snapshot(), action, requestId, mutationId, operationId);
            Map<String, Object> reconnect = manager.activeOrRecentSnapshot("client", 300_000L).stream()
                .filter(snapshot -> operationId.equals(snapshot.get("operationId")))
                .findFirst()
                .orElseThrow();
            assertIdentity(reconnect, action, requestId, mutationId, operationId);
            assertEquals(7L, reconnect.get("expectedRevision"));
            assertEquals("intent-" + suffix, reconnect.get("intentHash"));

            started.job().markSucceeded("preview", "Ready");
            started.execution().complete();
            manager.shutdown();
        }
    }

    @Test
    void previewModuleCarriesMutationIdentityIntoJobsAndStatus() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/WorldGenModule.java"));

        assertEquals(3, count(source, "beginPreviewJob(session, payload, previewId)"));
        assertTrue(source.contains("mutation.operationId(), mutation.expectedRevision(), mutation.intentHash()"));
        assertTrue(source.contains("mutation.action(), previewId, mutation.requestId(), mutation.mutationId()"));
        assertTrue(source.contains("requirePreviewMutationIdentity(payload)"));
        assertTrue(source.contains("data.addProperty(\"action\", mutation.action())"));
        assertTrue(source.contains("data.addProperty(\"resourceId\", mutation.resourceId())"));
        assertTrue(source.contains("data.addProperty(\"requestId\", mutation.requestId())"));
        assertTrue(source.contains("data.addProperty(\"mutationId\", mutation.mutationId())"));
        assertTrue(source.contains("data.addProperty(\"operationId\", mutation.operationId())"));
        assertTrue(source.contains("snapshot.put(\"resourceId\", target == null ? \"\" : target)"));
        assertTrue(source.contains("sendJob(session, \"jobSnapshot\", snapshots)"));
        assertTrue(source.contains("activeOrRecentSnapshot("));
        assertTrue(source.contains("session != null ? session.getClientId() : \"unknown\", 300000"));
    }

    @Test
    void changedPreviewMutationIdentityConflictsInsteadOfReplaying() {
        FlowJobRegistry registry = new FlowJobRegistry();
        JobManager manager = new JobManager(registry, null);

        JobManager.StartedJob<String> original = manager.createStarted("worldGenPreviewCreate", "client", "preview",
            "request", "mutation", "operation", 7L, "intent-a");
        assertTrue(original.started());
        original.job().markSucceeded("preview", "Ready");

        JobManager.StartedJob<String> changed = manager.createStarted("worldGenPreviewCreate", "client", "preview",
            "request", "mutation", "operation", 8L, "intent-b");

        assertTrue(changed.identityConflict());
        assertEquals(original.job().getJobId(), changed.job().getJobId());
        manager.shutdown();
    }

    private void assertIdentity(Map<String, Object> snapshot, String action, String requestId, String mutationId,
                                String operationId) {
        assertEquals(action, snapshot.get("action"));
        assertEquals("preview", snapshot.get("target"));
        assertEquals(requestId, snapshot.get("requestId"));
        assertEquals(mutationId, snapshot.get("mutationId"));
        assertEquals(operationId, snapshot.get("operationId"));
    }

    private int count(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
