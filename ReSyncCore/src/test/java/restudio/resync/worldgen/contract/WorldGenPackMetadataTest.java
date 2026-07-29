package restudio.resync.worldgen.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorldGenPackMetadataTest {
    @Test
    void writesLegacyMetadataBefore1219() {
        Map<String, Object> pack = pack(WorldGenPackMetadata.create(WorldGenTargetVersion.MINECRAFT_1_21_8, "WorldGen"));
        assertEquals(81, pack.get("pack_format"));
        assertFalse(pack.containsKey("min_format"));
        assertFalse(pack.containsKey("max_format"));
    }

    @Test
    void writesExactVersionedMetadataFrom1219() {
        Map<String, Object> pack = pack(WorldGenPackMetadata.create(WorldGenTargetVersion.MINECRAFT_26_2, "WorldGen"));
        assertEquals(List.of(107, 1), pack.get("min_format"));
        assertEquals(List.of(107, 1), pack.get("max_format"));
        assertFalse(pack.containsKey("pack_format"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pack(Map<String, Object> metadata) {
        return (Map<String, Object>) metadata.get("pack");
    }
}
