package restudio.resync.worldgen.generator;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import restudio.resync.worldgen.pipeline.TerrainPipeline;
import restudio.resync.worldgen.pipeline.TerrainPipelineHolder;

import java.util.List;
import java.util.Random;

public class NodeGraphChunkGenerator extends ChunkGenerator {
    private final TerrainPipelineHolder pipelineHolder;

    public NodeGraphChunkGenerator(TerrainPipeline pipeline) {
        this(new TerrainPipelineHolder(pipeline));
    }

    public NodeGraphChunkGenerator(TerrainPipelineHolder pipelineHolder) {
        this.pipelineHolder = pipelineHolder;
    }

    public TerrainPipeline getPipeline() {
        return pipelineHolder.get();
    }

    public TerrainPipelineHolder getPipelineHolder() {
        return pipelineHolder;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        TerrainPipeline pipeline = pipelineHolder.get();
        int minHeight = worldInfo.getMinHeight();
        int maxHeight = worldInfo.getMaxHeight();
        long seed = worldInfo.getSeed();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkX * 16 + localX;
                int worldZ = chunkZ * 16 + localZ;
                if (pipeline.hasDensityOutput()) {
                    generateDensityColumn(worldInfo, pipeline, seed, chunkData, localX, localZ, worldX, worldZ, minHeight, maxHeight);
                } else {
                    int height = Math.max(minHeight, Math.min(maxHeight - 1, Math.round(pipeline.getHeight(worldX, worldZ, seed, worldInfo))));
                    for (int y = minHeight; y <= height; y++) {
                        Material material = y == height ? pipeline.getBlock(worldX, y, worldZ, seed, height, worldInfo) : pipeline.getDefaultBlock();
                        if (y < height - 3 && pipeline.getCaveDensity(worldX, y, worldZ, seed, worldInfo) < -0.38f) {
                            material = Material.AIR;
                        }
                        chunkData.setBlock(localX, y, localZ, material);
                    }
                    fillSea(pipeline, chunkData, localX, localZ, height + 1, minHeight, maxHeight);
                }
            }
        }
    }

    private void generateDensityColumn(WorldInfo worldInfo, TerrainPipeline pipeline, long seed, ChunkData chunkData, int localX, int localZ, int worldX, int worldZ, int minHeight, int maxHeight) {
        boolean[] solid = new boolean[maxHeight - minHeight];
        int surface = minHeight - 1;
        for (int y = minHeight; y < maxHeight; y++) {
            float density = pipeline.getDensity(worldX, y, worldZ, seed, worldInfo);
            boolean isSolid = density > 0f && pipeline.getCaveDensity(worldX, y, worldZ, seed, worldInfo) >= -0.38f;
            solid[y - minHeight] = isSolid;
            if (isSolid) {
                surface = y;
            }
        }
        for (int y = minHeight; y < maxHeight; y++) {
            if (solid[y - minHeight]) {
                Material material = y >= surface - 4 ? pipeline.getBlock(worldX, y, worldZ, seed, surface, worldInfo) : pipeline.getDefaultBlock();
                chunkData.setBlock(localX, y, localZ, material);
            } else if (y <= pipeline.getSeaLevel() && y >= surface) {
                chunkData.setBlock(localX, y, localZ, pipeline.getDefaultFluid());
            }
        }
    }

    private void fillSea(TerrainPipeline pipeline, ChunkData chunkData, int localX, int localZ, int startY, int minHeight, int maxHeight) {
        int seaLevel = Math.min(pipeline.getSeaLevel(), maxHeight - 1);
        for (int y = Math.max(startY, minHeight); y <= seaLevel; y++) {
            chunkData.setBlock(localX, y, localZ, pipeline.getDefaultFluid());
        }
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        TerrainPipeline pipeline = pipelineHolder.get();
        if (pipeline.hasDensityOutput()) {
            return;
        }
        long seed = worldInfo.getSeed();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkX * 16 + localX;
                int worldZ = chunkZ * 16 + localZ;
                int height = Math.round(pipeline.getHeight(worldX, worldZ, seed, worldInfo));
                Material material = pipeline.getBlock(worldX, height, worldZ, seed, height, worldInfo);
                if (height >= worldInfo.getMinHeight() && height < worldInfo.getMaxHeight()) {
                    chunkData.setBlock(localX, height, localZ, material);
                }
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
        return pipelineHolder.get().hasAnyVanillaFeaturesEnabled();
    }

    @Override
    public boolean shouldGenerateDecorations(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        TerrainPipeline pipeline = pipelineHolder.get();
        long seed = worldInfo != null ? worldInfo.getSeed() : 0L;
        int y = worldInfo != null ? Math.max(worldInfo.getMinHeight(), Math.min(worldInfo.getMaxHeight() - 1, 64)) : 64;
        return pipeline.isVanillaFeaturesEnabled((chunkX << 4) + 8, y, (chunkZ << 4) + 8, seed, worldInfo);
    }

    @Override
    public boolean shouldGenerateMobs() {
        return pipelineHolder.get().hasAnyVanillaSpawnsEnabled();
    }

    @Override
    public boolean shouldGenerateMobs(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        TerrainPipeline pipeline = pipelineHolder.get();
        long seed = worldInfo != null ? worldInfo.getSeed() : 0L;
        int y = worldInfo != null ? Math.max(worldInfo.getMinHeight(), Math.min(worldInfo.getMaxHeight() - 1, 64)) : 64;
        return pipeline.isVanillaSpawnsEnabled((chunkX << 4) + 8, y, (chunkZ << 4) + 8, seed, worldInfo);
    }

    @Override
    public boolean shouldGenerateStructures() {
        return pipelineHolder.get().hasAnyVanillaStructuresEnabled();
    }

    @Override
    public boolean shouldGenerateStructures(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        TerrainPipeline pipeline = pipelineHolder.get();
        long seed = worldInfo != null ? worldInfo.getSeed() : 0L;
        int y = worldInfo != null ? Math.max(worldInfo.getMinHeight(), Math.min(worldInfo.getMaxHeight() - 1, 64)) : 64;
        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        return pipeline.isVanillaStructuresEnabled(x, y, z, seed, worldInfo)
            && pipeline.isVanillaStructureTerrainSuitable(x, z, seed, worldInfo);
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new StructurePopulator(pipelineHolder));
    }

}
