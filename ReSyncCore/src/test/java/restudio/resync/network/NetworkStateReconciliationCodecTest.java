package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkStateReconciliationCodecTest {
    @Test
    void roundTripsRequestsAndTasks() {
        NetworkStateReconciliationRequest request = new NetworkStateReconciliationRequest("transition", Set.of("lobby", "creative"), Set.of("inventory", "ender-chest"));
        NetworkStateReconciliationTask task = new NetworkStateReconciliationTask("transition", Set.of(UUID.randomUUID(), UUID.randomUUID()), Set.of("inventory"));

        assertEquals(request, NetworkStateReconciliationCodec.decodeRequest(NetworkStateReconciliationCodec.encodeRequest(request)));
        assertEquals(task, NetworkStateReconciliationCodec.decodeTask(NetworkStateReconciliationCodec.encodeTask(task)));
    }
}
