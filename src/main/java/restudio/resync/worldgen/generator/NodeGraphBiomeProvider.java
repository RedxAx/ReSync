package restudio.resync.worldgen.generator;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import restudio.resync.worldgen.pipeline.TerrainPipeline;
import restudio.resync.worldgen.pipeline.TerrainPipelineHolder;

import java.util.List;

public class NodeGraphBiomeProvider extends BiomeProvider {
    private final TerrainPipelineHolder pipelineHolder;

    public NodeGraphBiomeProvider(TerrainPipeline pipeline) {
        this(new TerrainPipelineHolder(pipeline));
    }

    public NodeGraphBiomeProvider(TerrainPipelineHolder pipelineHolder) {
        this.pipelineHolder = pipelineHolder;
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return pipelineHolder.get().getBiome(x, y, z, (int) worldInfo.getSeed(), worldInfo);
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return List.of(Biome.PLAINS, Biome.DESERT, Biome.FOREST, Biome.SAVANNA, Biome.TAIGA, Biome.SNOWY_PLAINS);
    }
}
