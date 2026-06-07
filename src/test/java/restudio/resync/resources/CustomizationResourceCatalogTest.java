package restudio.resync.resources;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomizationResourceCatalogTest {
    @Test
    void customizationResourcePacketsAreReservedAndUnique() {
        Set<Byte> packets = new HashSet<>();
        for (ReSyncManagedResource resource : ReSyncResourceCatalog.all()) {
            if (resource.flowPackets() == null) {
                continue;
            }
            assertTrue(packets.add(resource.flowPackets().request()), resource.typeId() + " request");
            assertTrue(packets.add(resource.flowPackets().listRequest()), resource.typeId() + " list request");
            assertTrue(packets.add(resource.flowPackets().data()), resource.typeId() + " data");
            assertTrue(packets.add(resource.flowPackets().list()), resource.typeId() + " list");
            assertTrue(packets.add(resource.flowPackets().save()), resource.typeId() + " save");
            assertTrue(packets.add(resource.flowPackets().delete()), resource.typeId() + " delete");
            assertTrue(packets.add(resource.flowPackets().saveAck()), resource.typeId() + " save ack");
        }
    }

    @Test
    void planResourceTypesUseExpectedAssetFolders() {
        assertEquals("Customization/Chat", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.CHAT));
        assertEquals("Customization/MOTDs", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.MOTD_PROFILE));
        assertEquals("Customization/Messages", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.MESSAGE_RULE));
        assertEquals("Content/Recipes", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.RECIPE_DEFINITION));
        assertEquals("Text/Templates", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.TEXT_TEMPLATE));
        assertEquals("Content/Advancements", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.ADVANCEMENT_TREE));
        assertEquals("Content/Dialogs", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.DIALOG));
        assertNotNull(ReSyncResourceCatalog.byFlowPacket((byte) 0xA8));
        assertNotNull(ReSyncResourceCatalog.byFlowPacket((byte) 0xAD));
        assertNotNull(ReSyncResourceCatalog.byFlowPacket((byte) 0xB3));
        assertNotNull(ReSyncResourceCatalog.byFlowPacket((byte) 0xB4));
        assertNotNull(ReSyncResourceCatalog.byFlowPacket((byte) 0xBA));
        assertNotNull(ReSyncResourceCatalog.byFlowPacket((byte) 0x67));
        assertNotNull(ReSyncResourceCatalog.byFlowPacket((byte) 0x6D));
    }
}
