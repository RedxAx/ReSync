package restudio.resync.worldgen.generator;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import restudio.resync.worldgen.pipeline.BiomeChoice;
import restudio.resync.worldgen.pipeline.TerrainPipeline;
import restudio.resync.worldgen.pipeline.TerrainPipelineHolder;

import java.util.Random;

public class WorldGenFeaturePopulator extends BlockPopulator {
    private final TerrainPipelineHolder pipelineHolder;

    public WorldGenFeaturePopulator(TerrainPipeline pipeline) {
        this(new TerrainPipelineHolder(pipeline));
    }

    public WorldGenFeaturePopulator(TerrainPipelineHolder pipelineHolder) {
        this.pipelineHolder = pipelineHolder;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion limitedRegion) {
    }

    @Override
    public void populate(World world, Random random, Chunk source) {
        placeFeatures(world, random, source);
    }

    public void placeFeatures(World world, Random random, Chunk source) {
        if (world == null || source == null || pipelineHolder == null || pipelineHolder.get() == null) {
            return;
        }
        TerrainPipeline pipeline = pipelineHolder.get();
        int seed = (int) world.getSeed();
        int baseX = source.getX() << 4;
        int baseZ = source.getZ() << 4;
        BiomeChoice choice = pipeline.getBiomeChoice(baseX + 8, world.getSeaLevel(), baseZ + 8, seed, null);
        Biome biome = TerrainPipeline.biome(choice.biomeId(), Biome.PLAINS);
        if (choice.keepVanillaFeatures()) {
            placeVanillaLikeBiomeFeatures(world, random, source, biome);
            return;
        }
        String placement = pipeline.getFeaturePlacement(baseX + 8, world.getSeaLevel(), baseZ + 8, seed, null);
        if (placement == null || !placement.startsWith("tree:")) {
            return;
        }
        double chance = parseChance(placement, 0.08d);
        if (random.nextDouble() > chance) {
            return;
        }
        int x = baseX + random.nextInt(16);
        int z = baseZ + random.nextInt(16);
        int y = world.getHighestBlockYAt(x, z) + 1;
        Material ground = world.getBlockAt(x, y - 1, z).getType();
        if (ground.isAir() || ground == Material.WATER || ground == Material.LAVA) {
            return;
        }
        TreeType treeType = parseTreeType(placement.substring("tree:".length()).split("\\|")[0]);
        world.generateTree(new Location(world, x, y, z), treeType);
    }

    private void placeVanillaLikeBiomeFeatures(World world, Random random, Chunk source, Biome biome) {
        int treeAttempts = treeAttempts(biome);
        int grassAttempts = grassAttempts(biome);
        int flowerAttempts = flowerAttempts(biome);
        for (int i = 0; i < treeAttempts; i++) {
            if (random.nextDouble() <= treeChance(biome)) {
                placeTree(world, random, source, treeType(biome));
            }
        }
        for (int i = 0; i < grassAttempts; i++) {
            placePlant(world, random, source, Material.SHORT_GRASS);
        }
        for (int i = 0; i < flowerAttempts; i++) {
            placePlant(world, random, source, flower(random, biome));
        }
    }

    private void placeTree(World world, Random random, Chunk source, TreeType treeType) {
        int baseX = source.getX() << 4;
        int baseZ = source.getZ() << 4;
        int x = baseX + random.nextInt(16);
        int z = baseZ + random.nextInt(16);
        int y = world.getHighestBlockYAt(x, z) + 1;
        if (!canPlantOn(world.getBlockAt(x, y - 1, z).getType()) || !world.getBlockAt(x, y, z).isEmpty()) {
            return;
        }
        world.generateTree(new Location(world, x, y, z), treeType);
    }

    private void placePlant(World world, Random random, Chunk source, Material plant) {
        int baseX = source.getX() << 4;
        int baseZ = source.getZ() << 4;
        int x = baseX + random.nextInt(16);
        int z = baseZ + random.nextInt(16);
        int y = world.getHighestBlockYAt(x, z) + 1;
        if (!canPlantOn(world.getBlockAt(x, y - 1, z).getType()) || !world.getBlockAt(x, y, z).isEmpty()) {
            return;
        }
        world.getBlockAt(x, y, z).setType(plant, false);
    }

    private boolean canPlantOn(Material material) {
        return material == Material.GRASS_BLOCK || material == Material.DIRT || material == Material.PODZOL || material == Material.COARSE_DIRT || material == Material.ROOTED_DIRT;
    }

    private int treeAttempts(Biome biome) {
        return switch (biome) {
            case FOREST, BIRCH_FOREST, DARK_FOREST, OLD_GROWTH_BIRCH_FOREST -> 5;
            case TAIGA, OLD_GROWTH_PINE_TAIGA, OLD_GROWTH_SPRUCE_TAIGA, SNOWY_TAIGA -> 4;
            case JUNGLE, BAMBOO_JUNGLE, SPARSE_JUNGLE -> 7;
            case SAVANNA, SAVANNA_PLATEAU, WINDSWEPT_SAVANNA -> 2;
            case PLAINS, SUNFLOWER_PLAINS, MEADOW -> 1;
            default -> 0;
        };
    }

    private double treeChance(Biome biome) {
        return switch (biome) {
            case FOREST, BIRCH_FOREST, DARK_FOREST, OLD_GROWTH_BIRCH_FOREST, TAIGA, OLD_GROWTH_PINE_TAIGA, OLD_GROWTH_SPRUCE_TAIGA, SNOWY_TAIGA, JUNGLE, BAMBOO_JUNGLE, SPARSE_JUNGLE -> 0.75d;
            case SAVANNA, SAVANNA_PLATEAU, WINDSWEPT_SAVANNA -> 0.45d;
            case PLAINS, SUNFLOWER_PLAINS, MEADOW -> 0.12d;
            default -> 0d;
        };
    }

    private int grassAttempts(Biome biome) {
        return switch (biome) {
            case DESERT, BADLANDS, ERODED_BADLANDS, WOODED_BADLANDS, SNOWY_PLAINS, ICE_SPIKES -> 0;
            case FOREST, BIRCH_FOREST, DARK_FOREST, PLAINS, SUNFLOWER_PLAINS, MEADOW, SAVANNA, SAVANNA_PLATEAU, WINDSWEPT_SAVANNA -> 32;
            case TAIGA, OLD_GROWTH_PINE_TAIGA, OLD_GROWTH_SPRUCE_TAIGA, SNOWY_TAIGA -> 18;
            case JUNGLE, BAMBOO_JUNGLE, SPARSE_JUNGLE -> 42;
            default -> 10;
        };
    }

    private int flowerAttempts(Biome biome) {
        return switch (biome) {
            case PLAINS, SUNFLOWER_PLAINS, MEADOW, FOREST, BIRCH_FOREST -> 6;
            default -> 1;
        };
    }

    private TreeType treeType(Biome biome) {
        return switch (biome) {
            case BIRCH_FOREST, OLD_GROWTH_BIRCH_FOREST -> TreeType.BIRCH;
            case DARK_FOREST -> TreeType.DARK_OAK;
            case TAIGA, OLD_GROWTH_PINE_TAIGA, OLD_GROWTH_SPRUCE_TAIGA, SNOWY_TAIGA -> TreeType.REDWOOD;
            case JUNGLE, BAMBOO_JUNGLE, SPARSE_JUNGLE -> TreeType.SMALL_JUNGLE;
            case SAVANNA, SAVANNA_PLATEAU, WINDSWEPT_SAVANNA -> TreeType.ACACIA;
            default -> TreeType.TREE;
        };
    }

    private Material flower(Random random, Biome biome) {
        if (biome == Biome.SUNFLOWER_PLAINS) {
            return Material.DANDELION;
        }
        Material[] flowers = {Material.DANDELION, Material.POPPY, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.CORNFLOWER};
        return flowers[random.nextInt(flowers.length)];
    }

    private double parseChance(String placement, double fallback) {
        String[] parts = placement.split("\\|");
        if (parts.length < 3 || !"scatter".equals(parts[1])) {
            return fallback;
        }
        try {
            return Math.max(0d, Math.min(1d, Double.parseDouble(parts[2])));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private TreeType parseTreeType(String value) {
        if (value == null || value.isBlank()) {
            return TreeType.TREE;
        }
        try {
            return TreeType.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            return TreeType.TREE;
        }
    }
}
