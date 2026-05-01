package restudio.resync.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.contracts.ReSyncProtocolContract;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReSyncProtocolContractTest {
    @Test
    void flowPacketIdsMatchRuntimeHandlers() {
        assertEquals(0x01, ReSyncProtocolContract.FLOW_PACKET_REQUEST);
        assertEquals(0x03, ReSyncProtocolContract.FLOW_PACKET_SAVE);
        assertEquals(0x08, ReSyncProtocolContract.FLOW_PACKET_DELETE);
        assertEquals(0x09, ReSyncProtocolContract.FLOW_PACKET_LIST_REQUEST);
        assertEquals(0x31, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_LIST_RESPONSE);
        assertEquals(0x32, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_DATA);
        assertEquals(0x33, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_SAVE);
        assertEquals(0x34, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_DELETE);
        assertEquals(0x35, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_SAVE_ACK);
        assertEquals(0x36, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_LIST_REQUEST);
        assertEquals(0x44, ReSyncProtocolContract.FLOW_PACKET_JOB);
    }
}
