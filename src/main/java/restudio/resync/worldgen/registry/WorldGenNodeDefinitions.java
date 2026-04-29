package restudio.resync.worldgen.registry;

import org.bukkit.TreeType;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import restudio.flow.data.FlowDataType;

import java.util.Arrays;
import java.util.List;

public final class WorldGenNodeDefinitions {
    private WorldGenNodeDefinitions() {
    }

    public static void registerDefaults(WorldGenNodeRegistry registry) {
        registry.register(WorldGenNodeDefinition.builder("simplex", "Simplex").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("perlin", "Perlin").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("value", "Value").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("cellular", "Cellular").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").input("distance_func", FlowDataType.STRING, "euclidean", "dropdown").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("white", "White").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("fbm", "Fractal FBm").input("source", FlowDataType.FLOAT, 0f, "number").input("octaves", FlowDataType.FLOAT, 4f, "number").input("lacunarity", FlowDataType.FLOAT, 2f, "number").input("gain", FlowDataType.FLOAT, 0.5f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("ridged", "Fractal Ridged").input("source", FlowDataType.FLOAT, 0f, "number").input("octaves", FlowDataType.FLOAT, 4f, "number").input("lacunarity", FlowDataType.FLOAT, 2f, "number").input("gain", FlowDataType.FLOAT, 0.5f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("ping_pong", "Ping Pong").input("source", FlowDataType.FLOAT, 0f, "number").input("octaves", FlowDataType.FLOAT, 4f, "number").input("lacunarity", FlowDataType.FLOAT, 2f, "number").input("gain", FlowDataType.FLOAT, 0.5f, "number").input("ping_pong_strength", FlowDataType.FLOAT, 2f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("add", "Add").input("a", FlowDataType.FLOAT, 0f, "number").input("b", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("multiply", "Multiply").input("a", FlowDataType.FLOAT, 1f, "number").input("b", FlowDataType.FLOAT, 1f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("remap", "Remap").input("in", FlowDataType.FLOAT, 0f, "number").input("from_min", FlowDataType.FLOAT, -1f, "number").input("from_max", FlowDataType.FLOAT, 1f, "number").input("to_min", FlowDataType.FLOAT, 0f, "number").input("to_max", FlowDataType.FLOAT, 128f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("clamp", "Clamp").input("in", FlowDataType.FLOAT, 0f, "number").input("min", FlowDataType.FLOAT, 0f, "number").input("max", FlowDataType.FLOAT, 1f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("abs", "Abs").input("in", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("min", "Min").input("a", FlowDataType.FLOAT, 0f, "number").input("b", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("max", "Max").input("a", FlowDataType.FLOAT, 0f, "number").input("b", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("domain_warp_gradient", "Domain Warp Gradient").input("source", FlowDataType.FLOAT, 0f, "number").input("amplitude", FlowDataType.FLOAT, 1f, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("domain_warp_simplex", "Domain Warp Simplex").input("source", FlowDataType.FLOAT, 0f, "number").input("amplitude", FlowDataType.FLOAT, 1f, "number").input("frequency", FlowDataType.FLOAT, 0.01f, "number").input("seed", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("terrace", "Terrace").input("in", FlowDataType.FLOAT, 0f, "number").input("step_count", FlowDataType.FLOAT, 8f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("seed_offset", "Seed Offset").input("in", FlowDataType.FLOAT, 0f, "number").input("offset", FlowDataType.SEED, 0, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("output_height", "Output Height").input("height", FlowDataType.FLOAT, 64f, "number").build());
        registry.register(WorldGenNodeDefinition.builder("output_biome", "Output Biome").input("biome", FlowDataType.BIOME, "minecraft:plains", "searchable", biomeOptions()).input("temperature", FlowDataType.FLOAT, 0.5f, "number").input("humidity", FlowDataType.FLOAT, 0.5f, "number").build());
        registry.register(WorldGenNodeDefinition.builder("output_block", "Output Block").input("block", FlowDataType.BLOCK, null, "material").input("y", FlowDataType.FLOAT, 0f, "number").input("replace", FlowDataType.FLOAT, 1f, "number").build());
        registry.register(WorldGenNodeDefinition.builder("biome_constant", "Biome Constant").input("biome", FlowDataType.BIOME, "minecraft:plains", "searchable", biomeOptions()).output("biome", FlowDataType.BIOME).build());
        registry.register(WorldGenNodeDefinition.builder("biome_select", "Biome Select").input("mask", FlowDataType.BOOLEAN, false, "toggle").input("true_biome", FlowDataType.BIOME, "minecraft:forest", "searchable", biomeOptions()).input("false_biome", FlowDataType.BIOME, "minecraft:plains", "searchable", biomeOptions()).output("biome", FlowDataType.BIOME).build());
        registry.register(WorldGenNodeDefinition.builder("biome_blend", "Biome Blend").input("a", FlowDataType.BIOME, "minecraft:plains", "searchable", biomeOptions()).input("b", FlowDataType.BIOME, "minecraft:forest", "searchable", biomeOptions()).input("weight", FlowDataType.FLOAT, 0.5f, "number").output("biome", FlowDataType.BIOME).build());
        registry.register(WorldGenNodeDefinition.builder("climate_map", "Climate Map").input("temperature", FlowDataType.FLOAT, 0.5f, "number").input("humidity", FlowDataType.FLOAT, 0.5f, "number").output("biome", FlowDataType.BIOME).build());
        registry.register(WorldGenNodeDefinition.builder("temperature", "Temperature").input("value", FlowDataType.FLOAT, 0.5f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("humidity", "Humidity").input("value", FlowDataType.FLOAT, 0.5f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("continentalness", "Continentalness").input("value", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("erosion", "Erosion").input("value", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("weirdness", "Weirdness").input("value", FlowDataType.FLOAT, 0f, "number").output("out", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("surface_rule", "Surface Rule").input("top", FlowDataType.BLOCK, "minecraft:grass_block", "material").input("filler", FlowDataType.BLOCK, "minecraft:dirt", "material").output("surface", FlowDataType.BLOCK).build());
        registry.register(WorldGenNodeDefinition.builder("material_layer", "Material Layer").input("block", FlowDataType.BLOCK, "minecraft:stone", "material").input("depth", FlowDataType.FLOAT, 3f, "number").output("surface", FlowDataType.BLOCK).build());
        registry.register(WorldGenNodeDefinition.builder("height_band", "Height Band").input("min", FlowDataType.FLOAT, 0f, "number").input("max", FlowDataType.FLOAT, 320f, "number").output("mask", FlowDataType.BOOLEAN).build());
        registry.register(WorldGenNodeDefinition.builder("slope_mask", "Slope Mask").input("min", FlowDataType.FLOAT, 0f, "number").input("max", FlowDataType.FLOAT, 1f, "number").output("mask", FlowDataType.BOOLEAN).build());
        registry.register(WorldGenNodeDefinition.builder("beach_rule", "Beach Rule").input("sand", FlowDataType.BLOCK, "minecraft:sand", "material").output("surface", FlowDataType.BLOCK).build());
        registry.register(WorldGenNodeDefinition.builder("underwater_rule", "Underwater Rule").input("block", FlowDataType.BLOCK, "minecraft:gravel", "material").output("surface", FlowDataType.BLOCK).build());
        registry.register(WorldGenNodeDefinition.builder("snow_rule", "Snow Rule").input("block", FlowDataType.BLOCK, "minecraft:snow_block", "material").output("surface", FlowDataType.BLOCK).build());
        registry.register(WorldGenNodeDefinition.builder("cave_noise", "Cave Noise").input("seed", FlowDataType.SEED, 0, "number").input("frequency", FlowDataType.FLOAT, 0.02f, "number").output("density", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("worm_cave", "Worm Cave").input("radius", FlowDataType.FLOAT, 3f, "number").output("density", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("cheese_cave", "Cheese Cave").input("threshold", FlowDataType.FLOAT, 0.6f, "number").output("density", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("ravine", "Ravine").input("width", FlowDataType.FLOAT, 6f, "number").output("density", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("carve_if", "Carve If").input("mask", FlowDataType.BOOLEAN, false, "toggle").input("density", FlowDataType.FLOAT, 0f, "number").output("density", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("density_combine", "Density Combine").input("a", FlowDataType.FLOAT, 0f, "number").input("b", FlowDataType.FLOAT, 0f, "number").output("density", FlowDataType.FLOAT).build());
        registry.register(WorldGenNodeDefinition.builder("ore_vein", "Ore Vein").input("block", FlowDataType.BLOCK, "minecraft:coal_ore", "material").input("size", FlowDataType.FLOAT, 8f, "number").output("feature", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("tree_feature", "Tree Feature").input("tree", FlowDataType.STRING, "TREE", "searchable", treeOptions()).output("feature", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("vegetation_patch", "Vegetation Patch").input("block", FlowDataType.BLOCK, "minecraft:grass", "material").output("feature", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("liquid_lake", "Liquid Lake").input("fluid", FlowDataType.BLOCK, "minecraft:water", "material").output("feature", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("disk", "Disk").input("block", FlowDataType.BLOCK, "minecraft:clay", "material").input("radius", FlowDataType.FLOAT, 4f, "number").output("feature", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("boulder", "Boulder").input("block", FlowDataType.BLOCK, "minecraft:mossy_cobblestone", "material").output("feature", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("scatter", "Scatter").input("feature", FlowDataType.STRING, "", "text").input("chance", FlowDataType.FLOAT, 0.1f, "number").output("placement", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("poisson_scatter", "Poisson Scatter").input("feature", FlowDataType.STRING, "", "text").input("spacing", FlowDataType.FLOAT, 12f, "number").output("placement", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("biome_filter", "Biome Filter").input("biome", FlowDataType.BIOME, "minecraft:plains", "dropdown").output("mask", FlowDataType.BOOLEAN).build());
        registry.register(WorldGenNodeDefinition.builder("height_filter", "Height Filter").input("min", FlowDataType.FLOAT, 0f, "number").input("max", FlowDataType.FLOAT, 320f, "number").output("mask", FlowDataType.BOOLEAN).build());
        registry.register(WorldGenNodeDefinition.builder("chance_filter", "Chance Filter").input("chance", FlowDataType.FLOAT, 0.5f, "number").input("salt", FlowDataType.SEED, 0, "number").output("mask", FlowDataType.BOOLEAN).build());
        registry.register(WorldGenNodeDefinition.builder("structure_placement", "Structure Placement").input("structure_id", FlowDataType.STRING, "", "searchable", structureOptions()).input("spacing", FlowDataType.FLOAT, 32f, "number").input("separation", FlowDataType.FLOAT, 8f, "number").input("salt", FlowDataType.SEED, 0, "number").output("structure", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("spawn_rule", "Spawn Rule").input("entity", FlowDataType.ENTITY_TYPE, "minecraft:zombie", "searchable", entityOptions()).input("weight", FlowDataType.FLOAT, 10f, "number").input("min_group", FlowDataType.FLOAT, 1f, "number").input("max_group", FlowDataType.FLOAT, 4f, "number").output("spawn", FlowDataType.STRING).build());
        registry.register(WorldGenNodeDefinition.builder("output_features", "Output Features").input("placements", FlowDataType.STRING, "", "text").build());
        registry.register(WorldGenNodeDefinition.builder("output_structures", "Output Structures").input("placements", FlowDataType.STRING, "", "text").build());
        registry.register(WorldGenNodeDefinition.builder("output_spawns", "Output Spawns").input("table", FlowDataType.STRING, "", "text").build());
    }

    private static List<String> biomeOptions() {
        return Arrays.stream(Biome.values()).map(biome -> "minecraft:" + biome.name().toLowerCase()).sorted().toList();
    }

    private static List<String> entityOptions() {
        return Arrays.stream(EntityType.values()).filter(EntityType::isSpawnable).map(type -> "minecraft:" + type.name().toLowerCase()).sorted().toList();
    }

    private static List<String> treeOptions() {
        return Arrays.stream(TreeType.values()).map(Enum::name).sorted().toList();
    }

    private static List<String> structureOptions() {
        try {
            Class<?> type = Class.forName("org.bukkit.StructureType");
            Object[] values = type.getEnumConstants();
            if (values == null) return List.of();
            return Arrays.stream(values).map(value -> "minecraft:" + ((Enum<?>) value).name().toLowerCase()).sorted().toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
