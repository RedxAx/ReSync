package restudio.resync.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.contracts.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncResourceCatalog;

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
        assertEquals(0xAD, ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_REQUEST);
        assertEquals(0xB3, ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_SAVE_ACK);
    }

    @Test
    void managedResourceCatalogKeepsLegacyFlowPacketIds() {
        assertEquals(ReSyncProtocolContract.FLOW_PACKET_REQUEST, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.FLOW).flowPackets().request());
        assertEquals(ReSyncProtocolContract.FLOW_PACKET_SAVE, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.FLOW).flowPackets().save());
        assertEquals(ReSyncProtocolContract.FLOW_PACKET_DELETE, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.FLOW).flowPackets().delete());
        assertEquals(ReSyncProtocolContract.FLOW_PACKET_LIST_REQUEST, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.FLOW).flowPackets().listRequest());
        assertEquals(ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_LIST_RESPONSE, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.CUSTOM_CONTENT).flowPackets().list());
        assertEquals(ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_DATA, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.CUSTOM_CONTENT).flowPackets().data());
        assertEquals(ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_SAVE, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.CUSTOM_CONTENT).flowPackets().save());
        assertEquals(ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_DELETE, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.CUSTOM_CONTENT).flowPackets().delete());
        assertEquals(ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_SAVE_ACK, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.CUSTOM_CONTENT).flowPackets().saveAck());
        assertEquals(ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_LIST_REQUEST, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.CUSTOM_CONTENT).flowPackets().listRequest());
        assertEquals(ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_REQUEST, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.ADVANCEMENT_TREE).flowPackets().request());
        assertEquals(ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_SAVE_ACK, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.ADVANCEMENT_TREE).flowPackets().saveAck());
    }
}
