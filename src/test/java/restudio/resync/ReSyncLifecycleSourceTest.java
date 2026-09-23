package restudio.resync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncLifecycleSourceTest {
    @Test
    void disableDoesNotPollReloadOrFinishCoreAfterNetworkFailure() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/ReSync.java"));
        String serverSource = Files.readString(Path.of("src/main/java/restudio/resync/server/ReSyncServer.java"));

        assertFalse(source.contains("Thread.sleep"));
        assertFalse(source.contains("awaitNetworkStateReloadFinalization"));
        assertTrue(source.contains("beginReplacement(\"network-player-state-reload\")"));
        assertTrue(source.contains("stagePersistenceReplacement"));
        assertTrue(source.contains("stageTransferHandler(replacement)"));
        assertTrue(source.contains("triggerDisabledShutdownRetry()"));
        assertTrue(source.contains("current.continuePreparedShutdown(reloadReady)"));
        assertTrue(source.contains("cancelShutdownForReplacement"));
        assertFalse(source.contains("previous.start()"));
        assertTrue(source.contains("public CompletionStage<Void> retryShutdown()"));
        assertTrue(source.contains("pending.replacementLease().close()"));
        assertTrue(source.contains("cleanupPluginOwnedResources()"));
        assertTrue(source.contains("clearShutdownReferences(current)"));
        assertTrue(source.contains("activeServer()"));
        assertTrue(source.contains("disableAdmissionForReplacement()"));
        assertTrue(source.contains("replacementPublished"));
        int fenceCommit = source.indexOf("transferReplacementFence.commit();");
        int oldRetirement = source.indexOf("previous.disableAdmissionForReplacement();", fenceCommit);
        assertTrue(fenceCommit >= 0);
        assertTrue(oldRetirement > fenceCommit);
        assertTrue(serverSource.contains("public CompletionStage<Void> retryCoreShutdown()"));
        assertTrue(serverSource.contains("public CompletionStage<Void> retryShutdown()"));
        assertTrue(serverSource.contains("ReSyncShutdownCoordinator"));
        assertTrue(serverSource.contains("shutdownCoordinator.continueShutdown(initialReady)"));
        assertTrue(serverSource.contains("persistence.retryClose()"));
        assertTrue(serverSource.contains("networkShutdownPrepared"));
    }

    @Test
    void heartbeatKeepsConnectionAndAuthenticatedSessionAlive() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/server/ReSyncServer.java"));
        int start = source.indexOf("private void handleHeartbeat");
        int end = source.indexOf("private void sendError", start);

        assertTrue(start >= 0 && end > start);
        String heartbeat = source.substring(start, end);
        assertTrue(heartbeat.contains("connectionManager.updateHeartbeat(info);"));
        assertTrue(heartbeat.contains("Session session = sessionManager.getSession(info);"));
        assertTrue(heartbeat.contains("session.updateActivity();"));
    }
}
