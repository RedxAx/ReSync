package restudio.resync.worldgen.generator;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import restudio.resync.worldgen.pipeline.TerrainPipeline;

import java.util.List;

public class NodeGraphBiomeProvider extends BiomeProvider {
    private final TerrainPipeline pipeline;

    public NodeGraphBiomeProvider(TerrainPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return pipeline.getBiome(x, y, z, (int) worldInfo.getSeed(), worldInfo);
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return List.of(Biome.PLAINS, Biome.DESERT, Biome.FOREST, Biome.SAVANNA, Biome.TAIGA, Biome.SNOWY_PLAINS);
    }
}
