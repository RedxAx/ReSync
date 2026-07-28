package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.NetworkResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPathSynchronizerTest {
    @Test
    void keepsTheNewestQueuedRemoteRevisionUntilItCompletes() {
        NetworkPathSynchronizer.RemoteChangeTracker tracker = new NetworkPathSynchronizer.RemoteChangeTracker();
        NetworkResource first = resource(1);
        NetworkResource latest = resource(2);

        tracker.track("path", first);
        tracker.track("path", latest);

        assertFalse(tracker.isCurrent("path", first));
        assertTrue(tracker.isCurrent("path", latest));
        tracker.complete("path", first);
        assertTrue(tracker.contains("path"));
        tracker.complete("path", latest);
        assertFalse(tracker.contains("path"));
    }

    @Test
    void backsOffFailedSynchronizationAttemptsWithinTheRetryLimit() {
        assertEquals(1_000, NetworkPathSynchronizer.synchronizationRetryDelay(1));
        assertEquals(2_000, NetworkPathSynchronizer.synchronizationRetryDelay(2));
        assertEquals(30_000, NetworkPathSynchronizer.synchronizationRetryDelay(16));
    }

    private NetworkResource resource(long revision) {
        byte[] payload = new byte[]{(byte) revision};
        return new NetworkResource("network", "server-path:test", "config/value.json", revision, NetworkPayloads.sha256(payload), payload, false, "node", revision);
    }
}
