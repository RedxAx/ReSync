package restudio.resync.worldgen.generator;

import org.bukkit.FeatureFlag;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.junit.jupiter.api.Test;
import restudio.resync.worldgen.pipeline.CompiledBiomePolicy;
import restudio.resync.worldgen.pipeline.PipelineNode;
import restudio.resync.worldgen.pipeline.StructureTerrainPolicy;
import restudio.resync.worldgen.pipeline.TerrainPipeline;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeGraphChunkGeneratorTest {
    private final WorldInfo worldInfo = new TestWorldInfo();

    @Test
    void reportsGeneratedTerrainHeightForVanillaStructurePlacement() {
        PipelineNode height = (context, upstreams) -> 92f;
        NodeGraphChunkGenerator generator = new NodeGraphChunkGenerator(pipeline(height, null, 63));

        assertEquals(92, generator.getBaseHeight(worldInfo, new Random(1), 32, -16, HeightMap.OCEAN_FLOOR_WG));
        assertEquals(92, generator.getBaseHeight(worldInfo, new Random(1), 32, -16, HeightMap.WORLD_SURFACE_WG));
    }

    @Test
    void reportsSeaLevelOnlyForHeightMapsThatIncludeFluid() {
        PipelineNode height = (context, upstreams) -> 40f;
        NodeGraphChunkGenerator generator = new NodeGraphChunkGenerator(pipeline(height, null, 63));

        assertEquals(40, generator.getBaseHeight(worldInfo, new Random(1), 0, 0, HeightMap.OCEAN_FLOOR_WG));
        assertEquals(63, generator.getBaseHeight(worldInfo, new Random(1), 0, 0, HeightMap.WORLD_SURFACE_WG));
    }

    @Test
    void findsTheGeneratedSurfaceForDensityTerrain() {
        PipelineNode density = (context, upstreams) -> context.y() <= 88 ? 1f : -1f;
        NodeGraphChunkGenerator generator = new NodeGraphChunkGenerator(pipeline(null, density, 63));

        assertEquals(88, generator.getBaseHeight(worldInfo, new Random(1), 0, 0, HeightMap.OCEAN_FLOOR_WG));
    }

    private TerrainPipeline pipeline(PipelineNode height, PipelineNode density, int seaLevel) {
        PipelineNode output = height != null ? height : density;
        return new TerrainPipeline(Map.of("output", output), Map.of(), List.of(output), height, density, null, null, null, null, null, null,
            null, null, null, null, null, null, new CompiledBiomePolicy(false, true, false, Map.of(), Map.of(), Map.of()), seaLevel,
            Material.STONE, Material.WATER, List.of(), StructureTerrainPolicy.DEFAULT);
    }

    private static final class TestWorldInfo implements WorldInfo {
        @Override
        public String getName() {
            return "worldgen-test";
        }

        @Override
        public UUID getUID() {
            return new UUID(0, 1);
        }

        @Override
        public World.Environment getEnvironment() {
            return World.Environment.NORMAL;
        }

        @Override
        public long getSeed() {
            return 42;
        }

        @Override
        public int getMinHeight() {
            return -64;
        }

        @Override
        public int getMaxHeight() {
            return 320;
        }

        @Override
        public BiomeProvider vanillaBiomeProvider() {
            return null;
        }

        @Override
        public Set<FeatureFlag> getFeatureFlags() {
            return Set.of();
        }
    }
}
