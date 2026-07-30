package restudio.resync.worldgen.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenTargetVersionTest {
    @Test
    void coversSupportedMinecraftRange() {
        assertEquals("1.21", WorldGenTargetVersion.supportedIds().getFirst());
        assertEquals("26.2", WorldGenTargetVersion.supportedIds().getLast());
        assertEquals(16, WorldGenTargetVersion.supportedIds().size());
    }

    @Test
    void mapsStableReleasesToDatapackVersions() {
        assertEquals("48", WorldGenTargetVersion.MINECRAFT_1_21.datapackVersion());
        assertEquals("81", WorldGenTargetVersion.MINECRAFT_1_21_8.datapackVersion());
        assertEquals("88.0", WorldGenTargetVersion.MINECRAFT_1_21_10.datapackVersion());
        assertEquals("94.1", WorldGenTargetVersion.MINECRAFT_1_21_11.datapackVersion());
        assertEquals("101.1", WorldGenTargetVersion.MINECRAFT_26_1.datapackVersion());
        assertEquals("101.1", WorldGenTargetVersion.MINECRAFT_26_1_1.datapackVersion());
        assertEquals("101.1", WorldGenTargetVersion.MINECRAFT_26_1_2.datapackVersion());
        assertEquals("107.1", WorldGenTargetVersion.MINECRAFT_26_2.datapackVersion());
    }

    @Test
    void exposesVersionSpecificCapabilities() {
        assertFalse(WorldGenTargetVersion.MINECRAFT_1_21_8.supports(WorldGenDatapackCapability.VERSIONED_PACK_METADATA));
        assertTrue(WorldGenTargetVersion.MINECRAFT_1_21_9.supports(WorldGenDatapackCapability.VERSIONED_PACK_METADATA));
        assertFalse(WorldGenTargetVersion.MINECRAFT_1_21_5.supports(WorldGenDatapackCapability.STRICT_JSON));
        assertTrue(WorldGenTargetVersion.MINECRAFT_1_21_6.supports(WorldGenDatapackCapability.STRICT_JSON));
        assertTrue(WorldGenTargetVersion.MINECRAFT_26_2.supports(WorldGenDatapackCapability.JAVA_25_RUNTIME));
        assertFalse(WorldGenTargetVersion.MINECRAFT_1_21_10.supports(WorldGenDatapackCapability.ENVIRONMENT_ATTRIBUTES));
        assertTrue(WorldGenTargetVersion.MINECRAFT_1_21_11.supports(WorldGenDatapackCapability.ENVIRONMENT_ATTRIBUTES));
        assertFalse(WorldGenTargetVersion.MINECRAFT_1_21_11.supports(WorldGenDatapackCapability.WORLD_CLOCKS));
        assertTrue(WorldGenTargetVersion.MINECRAFT_26_1.supports(WorldGenDatapackCapability.WORLD_CLOCKS));
        assertEquals(List.of(107, 1), WorldGenTargetVersion.MINECRAFT_26_2.exactDatapackFormat());
    }

    @Test
    void rejectsUnknownTargets() {
        assertEquals(WorldGenTargetVersion.MINECRAFT_1_21, WorldGenTargetVersion.require("1.21.0"));
        assertThrows(IllegalArgumentException.class, () -> WorldGenTargetVersion.require("1.20.6"));
        assertThrows(IllegalArgumentException.class, () -> WorldGenTargetVersion.require("26.2-rc-2"));
        assertThrows(IllegalArgumentException.class, () -> WorldGenTargetVersion.require("26.3"));
    }

    @Test
    void recognizesCompatiblePatchReleases() {
        assertTrue(WorldGenTargetVersion.MINECRAFT_1_21.isDatapackCompatibleWith(WorldGenTargetVersion.MINECRAFT_1_21_1));
        assertTrue(WorldGenTargetVersion.MINECRAFT_1_21_9.isDatapackCompatibleWith(WorldGenTargetVersion.MINECRAFT_1_21_10));
        assertTrue(WorldGenTargetVersion.MINECRAFT_26_1.isDatapackCompatibleWith(WorldGenTargetVersion.MINECRAFT_26_1_2));
        assertFalse(WorldGenTargetVersion.MINECRAFT_1_21_10.isDatapackCompatibleWith(WorldGenTargetVersion.MINECRAFT_1_21_11));
        assertFalse(WorldGenTargetVersion.MINECRAFT_26_1_2.isDatapackCompatibleWith(WorldGenTargetVersion.MINECRAFT_26_2));
    }

    @Test
    void resolvesAutomaticTargetsFromTheServer() {
        assertEquals(WorldGenTargetVersion.MINECRAFT_1_21_4, WorldGenTargetVersion.resolve(WorldGenTargetVersion.AUTOMATIC, "1.21.4"));
        assertEquals(WorldGenTargetVersion.MINECRAFT_26_2, WorldGenTargetVersion.resolve("", "26.2"));
        assertThrows(IllegalArgumentException.class, () -> WorldGenTargetVersion.resolve(WorldGenTargetVersion.AUTOMATIC, "1.20.6"));
        assertThrows(IllegalArgumentException.class, () -> WorldGenTargetVersion.resolve(WorldGenTargetVersion.AUTOMATIC, ""));
    }
}
