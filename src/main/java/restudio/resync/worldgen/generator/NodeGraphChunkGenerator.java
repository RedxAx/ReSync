package restudio.resync.worldgen.generator;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import restudio.resync.worldgen.pipeline.TerrainPipeline;

import java.util.Random;

public class NodeGraphChunkGenerator extends ChunkGenerator {
    private final TerrainPipeline pipeline;

    public NodeGraphChunkGenerator(TerrainPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int minHeight = worldInfo.getMinHeight();
        int maxHeight = worldInfo.getMaxHeight();
        int seed = (int) worldInfo.getSeed();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkX * 16 + localX;
                int worldZ = chunkZ * 16 + localZ;
                int height = Math.max(minHeight, Math.min(maxHeight - 1, Math.round(pipeline.getHeight(worldX, worldZ, seed, worldInfo))));
                for (int y = minHeight; y <= height; y++) {
                    chunkData.setBlock(localX, y, localZ, y == height ? Material.GRASS_BLOCK : Material.STONE);
                }
            }
        }
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int seed = (int) worldInfo.getSeed();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkX * 16 + localX;
                int worldZ = chunkZ * 16 + localZ;
                int height = Math.round(pipeline.getHeight(worldX, worldZ, seed, worldInfo));
                Material material = pipeline.getBlock(worldX, height, worldZ, seed, height, worldInfo);
                chunkData.setBlock(localX, height, localZ, material);
            }
        }
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return true;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return true;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return true;
    }
}
