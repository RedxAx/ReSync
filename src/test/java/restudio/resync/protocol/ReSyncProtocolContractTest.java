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
        assertEquals(0x04, ReSyncProtocolContract.FLOW_PACKET_GUI_STATE);
        assertEquals(0x05, ReSyncProtocolContract.FLOW_PACKET_ERROR);
        assertEquals(0x06, ReSyncProtocolContract.FLOW_PACKET_TRIGGER_UPDATE);
        assertEquals(0x08, ReSyncProtocolContract.FLOW_PACKET_DELETE);
        assertEquals(0x09, ReSyncProtocolContract.FLOW_PACKET_LIST_REQUEST);
        assertEquals(0x31, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_LIST_RESPONSE);
        assertEquals(0x32, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_DATA);
        assertEquals(0x33, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_SAVE);
        assertEquals(0x34, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_DELETE);
        assertEquals(0x35, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_SAVE_ACK);
        assertEquals(0x36, ReSyncProtocolContract.CUSTOM_CONTENT_PACKET_LIST_REQUEST);
        assertEquals(0x44, ReSyncProtocolContract.FLOW_PACKET_JOB);
        assertEquals(0x48, ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_REQUEST);
        assertEquals(0x49, ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_RESULT);
        assertEquals(0x27, ReSyncProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW_REQUEST);
        assertEquals(0x28, ReSyncProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW);
        assertEquals(0x5A, ReSyncProtocolContract.FLOW_PACKET_EDIT_TARGET_STATE);
        assertEquals(0x60, ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_OPEN);
        assertEquals(0x61, ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_APPLY);
        assertEquals(0x62, ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_RESULT);
        assertEquals(0x63, ReSyncProtocolContract.FLOW_PACKET_OPEN_CUSTOM_CONTENT);
        assertEquals((byte) 0xAD, ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_REQUEST);
        assertEquals((byte) 0xB3, ReSyncProtocolContract.ADVANCEMENT_TREE_PACKET_SAVE_ACK);
        assertEquals((byte) 0xB4, ReSyncProtocolContract.DIALOG_PACKET_REQUEST);
        assertEquals((byte) 0xBA, ReSyncProtocolContract.DIALOG_PACKET_SAVE_ACK);
        assertEquals((byte) 0xBB, ReSyncProtocolContract.MESSAGE_LOG_PACKET_REQUEST);
        assertEquals((byte) 0xBC, ReSyncProtocolContract.MESSAGE_LOG_PACKET_RESPONSE);
        assertEquals((byte) 0xBD, ReSyncProtocolContract.TRADE_PROFILE_PACKET_REQUEST);
        assertEquals((byte) 0xC3, ReSyncProtocolContract.TRADE_PROFILE_PACKET_SAVE_ACK);
        assertEquals((byte) 0xC4, ReSyncProtocolContract.NPC_DEFINITION_PACKET_REQUEST);
        assertEquals((byte) 0xCA, ReSyncProtocolContract.NPC_DEFINITION_PACKET_SAVE_ACK);
        assertEquals((byte) 0xCB, ReSyncProtocolContract.LOOT_TABLE_PACKET_REQUEST);
        assertEquals((byte) 0xD1, ReSyncProtocolContract.LOOT_TABLE_PACKET_SAVE_ACK);
        assertEquals((byte) 0x67, ReSyncProtocolContract.CHAT_PACKET_REQUEST);
        assertEquals((byte) 0x6D, ReSyncProtocolContract.CHAT_PACKET_SAVE_ACK);
    }

    @Test
    void managedResourceCatalogKeepsFlowPacketIds() {
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
        assertEquals(ReSyncProtocolContract.DIALOG_PACKET_REQUEST, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.DIALOG).flowPackets().request());
        assertEquals(ReSyncProtocolContract.DIALOG_PACKET_SAVE_ACK, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.DIALOG).flowPackets().saveAck());
        assertEquals(ReSyncProtocolContract.TRADE_PROFILE_PACKET_REQUEST, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.TRADE_PROFILE).flowPackets().request());
        assertEquals(ReSyncProtocolContract.TRADE_PROFILE_PACKET_SAVE_ACK, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.TRADE_PROFILE).flowPackets().saveAck());
        assertEquals(ReSyncProtocolContract.NPC_DEFINITION_PACKET_REQUEST, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.NPC_DEFINITION).flowPackets().request());
        assertEquals(ReSyncProtocolContract.NPC_DEFINITION_PACKET_SAVE_ACK, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.NPC_DEFINITION).flowPackets().saveAck());
        assertEquals(ReSyncProtocolContract.LOOT_TABLE_PACKET_REQUEST, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.LOOT_TABLE).flowPackets().request());
        assertEquals(ReSyncProtocolContract.LOOT_TABLE_PACKET_SAVE_ACK, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.LOOT_TABLE).flowPackets().saveAck());
        assertEquals(ReSyncProtocolContract.CHAT_PACKET_REQUEST, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.CHAT).flowPackets().request());
        assertEquals(ReSyncProtocolContract.CHAT_PACKET_SAVE_ACK, ReSyncResourceCatalog.byType(ReSyncResourceCatalog.CHAT).flowPackets().saveAck());
    }
}
