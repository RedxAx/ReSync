package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReSyncNetworkAgentReconnectTest {

    @Test
    void reconnectDelayBacksOffAndCapsAtOneMinute() {
        assertEquals(20, ReSyncNetworkAgent.reconnectDelayTicks(0, 0));
        assertEquals(40, ReSyncNetworkAgent.reconnectDelayTicks(20, 1));
        assertEquals(320, ReSyncNetworkAgent.reconnectDelayTicks(20, 4));
        assertEquals(1_200, ReSyncNetworkAgent.reconnectDelayTicks(100, 6));
        assertEquals(1_200, ReSyncNetworkAgent.reconnectDelayTicks(100, 100));
    }
}
