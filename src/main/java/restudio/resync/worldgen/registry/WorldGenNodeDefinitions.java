package restudio.resync.worldgen.registry;

import restudio.flow.data.FlowDataType;

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
        registry.register(WorldGenNodeDefinition.builder("output_biome", "Output Biome").input("biome", FlowDataType.FLOAT, 0f, "number").input("temperature", FlowDataType.FLOAT, 0.5f, "number").input("humidity", FlowDataType.FLOAT, 0.5f, "number").build());
        registry.register(WorldGenNodeDefinition.builder("output_block", "Output Block").input("block", FlowDataType.BLOCK, null, "material").input("y", FlowDataType.FLOAT, 0f, "number").input("replace", FlowDataType.FLOAT, 1f, "number").build());
    }
}
