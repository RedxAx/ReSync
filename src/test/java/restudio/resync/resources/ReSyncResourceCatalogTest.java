package restudio.resync.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncResourceCatalogTest {
    @Test
    void mapsLegacyFlowPacketsToManagedResources() {
        assertEquals(ReSyncResourceCatalog.FLOW, ReSyncResourceCatalog.byFlowPacket((byte) 0x01).typeId());
        assertEquals(ReSyncResourceCatalog.FLOW, ReSyncResourceCatalog.byFlowPacket((byte) 0x03).typeId());
        assertEquals(ReSyncResourceCatalog.GUI, ReSyncResourceCatalog.byFlowPacket((byte) 0x11).typeId());
        assertEquals(ReSyncResourceCatalog.SCOREBOARD, ReSyncResourceCatalog.byFlowPacket((byte) 0x19).typeId());
        assertEquals(ReSyncResourceCatalog.TAB, ReSyncResourceCatalog.byFlowPacket((byte) 0x22).typeId());
        assertEquals(ReSyncResourceCatalog.CUSTOM_CONTENT, ReSyncResourceCatalog.byFlowPacket((byte) 0x34).typeId());
        assertEquals(ReSyncResourceCatalog.PROJECT_METADATA, ReSyncResourceCatalog.byFlowPacket((byte) 0x54).typeId());
    }

    @Test
    void keepsExistingDefaultFoldersStable() {
        assertEquals("Blueprints/Flows", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.FLOW));
        assertEquals("Blueprints/Functions", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.FUNCTION));
        assertEquals("Blueprints/Commands", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.COMMAND));
        assertEquals("GUIs", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.GUI));
        assertEquals("Customization/Scoreboards", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.SCOREBOARD));
        assertEquals("Customization/Tabs", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.TAB));
        assertEquals("Content/Items", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.CUSTOM_CONTENT));
        assertEquals("WorldGen", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.WORLDGEN));
        assertEquals("Worlds", ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.WORLD));
    }

    @Test
    void onlyCatalogsExistingResourceTypesForThisPass() {
        assertTrue(ReSyncResourceCatalog.all().stream().allMatch(ReSyncManagedResource::enabled));
    }
}
