package restudio.resync.worldgen.registry;

import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenOptionCatalogsTest {
    @Test
    void resolvesCatalogsFromTheProjectTargetVersion() {
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        WorldGenOptionCatalogs.register(registry);
        OptionCatalogQuery beforeSulfur = query("worldgen:blocks", "26.1.2");
        OptionCatalogQuery withSulfur = query("worldgen:blocks", "26.2");

        assertFalse(registry.values("worldgen:blocks", beforeSulfur).contains("minecraft:sulfur"));
        assertTrue(registry.values("worldgen:blocks", withSulfur).contains("minecraft:sulfur"));
        assertFalse(registry.values("worldgen:biomes", query("worldgen:biomes", "26.1.2")).contains("minecraft:sulfur_caves"));
        assertTrue(registry.values("worldgen:biomes", query("worldgen:biomes", "26.2")).contains("minecraft:sulfur_caves"));
        assertFalse(registry.values("worldgen:tree_features", query("worldgen:tree_features", "1.21")).contains("PALE_OAK"));
        assertTrue(registry.values("worldgen:tree_features", query("worldgen:tree_features", "1.21.4")).contains("PALE_OAK"));
        assertNotEquals(registry.provider("worldgen:blocks").revision(beforeSulfur), registry.provider("worldgen:blocks").revision(withSulfur));
    }

    private OptionCatalogQuery query(String source, String version) {
        return new OptionCatalogQuery(source, Map.of(WorldGenTargetVersion.OPTION_CONTEXT_KEY, version));
    }
}
