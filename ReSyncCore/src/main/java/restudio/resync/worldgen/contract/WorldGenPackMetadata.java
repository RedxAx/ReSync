package restudio.resync.worldgen.contract;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorldGenPackMetadata {
    private WorldGenPackMetadata() {
    }

    public static Map<String, Object> create(WorldGenTargetVersion target, String description) {
        Map<String, Object> pack = new LinkedHashMap<>();
        if (target.supports(WorldGenDatapackCapability.VERSIONED_PACK_METADATA)) {
            pack.put("min_format", target.exactDatapackFormat());
            pack.put("max_format", target.exactDatapackFormat());
        } else {
            pack.put("pack_format", target.datapackMajor());
        }
        pack.put("description", description);
        return Map.of("pack", pack);
    }
}
