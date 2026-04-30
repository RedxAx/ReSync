package restudio.resync.worldgen.pipeline;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.generator.WorldInfo;
import restudio.resync.worldgen.data.WorldGenSpawnRule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TerrainPipeline {
    public static final String ANY_POLICY_KEY = "worldgen:any";
    private final Map<String, PipelineNode> nodes;
    private final Map<String, Map<String, PipelineNode>> upstreams;
    private final List<PipelineNode> outputNodes;
    private final PipelineNode heightOutput;
    private final PipelineNode biomeOutput;
    private final PipelineNode blockOutput;
    private final PipelineNode caveOutput;
    private final PipelineNode featureOutput;
    private final PipelineNode structureOutput;
    private final PipelineNode spawnOutput;
    private final boolean vanillaFeaturesEnabled;
    private final boolean vanillaStructuresEnabled;
    private final boolean vanillaSpawnsEnabled;
    private final Map<String, Boolean> biomeVanillaFeatureOverrides;
    private final Map<String, Boolean> biomeVanillaStructureOverrides;
    private final Map<String, Boolean> biomeVanillaSpawnOverrides;
    private final List<WorldGenSpawnRule> spawnRules;

    public TerrainPipeline(Map<String, PipelineNode> nodes, Map<String, Map<String, PipelineNode>> upstreams, List<PipelineNode> outputNodes,
                           PipelineNode heightOutput, PipelineNode biomeOutput, PipelineNode blockOutput) {
        this(nodes, upstreams, outputNodes, heightOutput, biomeOutput, blockOutput, null, null, null, null, true, true, true, Map.of(), Map.of(), Map.of(), List.of());
    }

    public TerrainPipeline(Map<String, PipelineNode> nodes, Map<String, Map<String, PipelineNode>> upstreams, List<PipelineNode> outputNodes,
                           PipelineNode heightOutput, PipelineNode biomeOutput, PipelineNode blockOutput, PipelineNode caveOutput,
                           PipelineNode featureOutput, PipelineNode structureOutput, PipelineNode spawnOutput,
                           boolean vanillaFeaturesEnabled, boolean vanillaStructuresEnabled, boolean vanillaSpawnsEnabled,
                           Map<String, Boolean> biomeVanillaFeatureOverrides) {
        this(nodes, upstreams, outputNodes, heightOutput, biomeOutput, blockOutput, caveOutput, featureOutput, structureOutput, spawnOutput,
            vanillaFeaturesEnabled, vanillaStructuresEnabled, vanillaSpawnsEnabled, biomeVanillaFeatureOverrides, Map.of(), Map.of(), List.of());
    }

    public TerrainPipeline(Map<String, PipelineNode> nodes, Map<String, Map<String, PipelineNode>> upstreams, List<PipelineNode> outputNodes,
                           PipelineNode heightOutput, PipelineNode biomeOutput, PipelineNode blockOutput, PipelineNode caveOutput,
                           PipelineNode featureOutput, PipelineNode structureOutput, PipelineNode spawnOutput,
                           boolean vanillaFeaturesEnabled, boolean vanillaStructuresEnabled, boolean vanillaSpawnsEnabled,
                           Map<String, Boolean> biomeVanillaFeatureOverrides, Map<String, Boolean> biomeVanillaStructureOverrides,
                           Map<String, Boolean> biomeVanillaSpawnOverrides, List<WorldGenSpawnRule> spawnRules) {
        this.nodes = Map.copyOf(nodes);
        this.upstreams = copyUpstreams(upstreams);
        this.outputNodes = List.copyOf(outputNodes);
        this.heightOutput = heightOutput;
        this.biomeOutput = biomeOutput;
        this.blockOutput = blockOutput;
        this.caveOutput = caveOutput;
        this.featureOutput = featureOutput;
        this.structureOutput = structureOutput;
        this.spawnOutput = spawnOutput;
        this.vanillaFeaturesEnabled = vanillaFeaturesEnabled;
        this.vanillaStructuresEnabled = vanillaStructuresEnabled;
        this.vanillaSpawnsEnabled = vanillaSpawnsEnabled;
        this.biomeVanillaFeatureOverrides = biomeVanillaFeatureOverrides != null ? Map.copyOf(biomeVanillaFeatureOverrides) : Map.of();
        this.biomeVanillaStructureOverrides = biomeVanillaStructureOverrides != null ? Map.copyOf(biomeVanillaStructureOverrides) : Map.of();
        this.biomeVanillaSpawnOverrides = biomeVanillaSpawnOverrides != null ? Map.copyOf(biomeVanillaSpawnOverrides) : Map.of();
        this.spawnRules = spawnRules != null ? List.copyOf(spawnRules) : List.of();
    }

    public float getHeight(float x, float z, int seed, WorldInfo worldInfo) {
        Object value = evaluate(heightOutput, new EvalContext(x, 0f, z, seed, worldInfo), new HashMap<>());
        return asFloat(value, 64f);
    }

    public Biome getBiome(float x, float y, float z, int seed, WorldInfo worldInfo) {
        if (biomeOutput == null) return Biome.PLAINS;
        Object raw = evaluate(biomeOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        if (raw instanceof BiomeChoice choice) return biome(choice.biomeId(), Biome.PLAINS);
        if (raw instanceof Biome biome) return biome;
        if (raw instanceof String id) return biome(id, Biome.PLAINS);
        float value = asFloat(raw, 0f);
        Biome[] biomes = {Biome.PLAINS, Biome.FOREST, Biome.DESERT, Biome.SAVANNA, Biome.TAIGA, Biome.SNOWY_PLAINS};
        int index = Math.max(0, Math.min(biomes.length - 1, Math.round(Math.abs(value) * (biomes.length - 1))));
        return biomes[index];
    }

    public BiomeChoice getBiomeChoice(float x, float y, float z, int seed, WorldInfo worldInfo) {
        if (biomeOutput == null) {
            return new BiomeChoice("minecraft:plains", vanillaFeaturesEnabled, vanillaStructuresEnabled, vanillaSpawnsEnabled);
        }
        Object raw = evaluate(biomeOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        if (raw instanceof BiomeChoice choice) {
            return choice;
        }
        String biomeId;
        if (raw instanceof Biome biome) {
            biomeId = "minecraft:" + biome.name().toLowerCase(java.util.Locale.ROOT);
        } else if (raw instanceof String id) {
            biomeId = normalizeBiomeId(id);
        } else {
            Biome biome = getBiome(x, y, z, seed, worldInfo);
            biomeId = "minecraft:" + biome.name().toLowerCase(java.util.Locale.ROOT);
        }
        Biome biome = biome(biomeId, Biome.PLAINS);
        return new BiomeChoice(biomeId, isVanillaFeaturesEnabled(biome), isVanillaStructuresEnabled(biome), isVanillaSpawnsEnabled(biome));
    }

    public Material getBlock(float x, float y, float z, int seed, float height, WorldInfo worldInfo) {
        if (blockOutput == null) return y >= height ? Material.GRASS_BLOCK : Material.STONE;
        Object value = evaluate(blockOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        if (value instanceof Material material) return material;
        if (value instanceof String id) return material(id, y >= height ? Material.GRASS_BLOCK : Material.STONE);
        return y >= height ? Material.GRASS_BLOCK : Material.STONE;
    }

    public float getCaveDensity(float x, float y, float z, int seed, WorldInfo worldInfo) {
        if (caveOutput == null) return 1f;
        return asFloat(evaluate(caveOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>()), 1f);
    }

    public String getFeaturePlacement(float x, float y, float z, int seed, WorldInfo worldInfo) {
        if (featureOutput == null) return "";
        Object value = evaluate(featureOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        return value == null ? "" : String.valueOf(value);
    }

    public String getStructurePlacement(float x, float y, float z, int seed, WorldInfo worldInfo) {
        if (structureOutput == null) return "";
        Object value = evaluate(structureOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        return value == null ? "" : String.valueOf(value);
    }

    public String getSpawnTable(float x, float y, float z, int seed, WorldInfo worldInfo) {
        if (spawnOutput == null) return "";
        Object value = evaluate(spawnOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        return value == null ? "" : String.valueOf(value);
    }

    public boolean isVanillaFeaturesEnabled() {
        return vanillaFeaturesEnabled;
    }

    public boolean hasAnyVanillaFeaturesEnabled() {
        return vanillaFeaturesEnabled || biomeVanillaFeatureOverrides.containsValue(true);
    }

    public boolean isVanillaFeaturesEnabled(Biome biome) {
        if (biome == null) return vanillaFeaturesEnabled;
        String key = "minecraft:" + biome.name().toLowerCase(java.util.Locale.ROOT);
        return biomeVanillaFeatureOverrides.getOrDefault(key, vanillaFeaturesEnabled);
    }

    public boolean isVanillaFeaturesEnabled(float x, float y, float z, int seed, WorldInfo worldInfo) {
        return getBiomeChoice(x, y, z, seed, worldInfo).keepVanillaFeatures();
    }

    public boolean isVanillaStructuresEnabled() {
        return vanillaStructuresEnabled;
    }

    public boolean hasAnyVanillaStructuresEnabled() {
        return vanillaStructuresEnabled || biomeVanillaStructureOverrides.containsValue(true);
    }

    public boolean isVanillaStructuresEnabled(Biome biome) {
        if (biome == null) return vanillaStructuresEnabled;
        String key = "minecraft:" + biome.name().toLowerCase(java.util.Locale.ROOT);
        return biomeVanillaStructureOverrides.getOrDefault(key, vanillaStructuresEnabled);
    }

    public boolean isVanillaStructuresEnabled(float x, float y, float z, int seed, WorldInfo worldInfo) {
        return getBiomeChoice(x, y, z, seed, worldInfo).keepVanillaStructures();
    }

    public boolean isVanillaSpawnsEnabled() {
        return vanillaSpawnsEnabled;
    }

    public boolean hasAnyVanillaSpawnsEnabled() {
        return vanillaSpawnsEnabled || biomeVanillaSpawnOverrides.containsValue(true);
    }

    public boolean isVanillaSpawnsEnabled(Biome biome) {
        if (biome == null) return vanillaSpawnsEnabled;
        String key = "minecraft:" + biome.name().toLowerCase(java.util.Locale.ROOT);
        return biomeVanillaSpawnOverrides.getOrDefault(key, vanillaSpawnsEnabled);
    }

    public boolean isVanillaSpawnsEnabled(float x, float y, float z, int seed, WorldInfo worldInfo) {
        return getBiomeChoice(x, y, z, seed, worldInfo).keepVanillaSpawns();
    }

    public List<WorldGenSpawnRule> getSpawnRules() {
        return spawnRules;
    }

    public EntityType entityType(String id, EntityType fallback) {
        if (id == null || id.isBlank()) return fallback;
        String value = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        try {
            return EntityType.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    Object evaluate(PipelineNode node, EvalContext context, Map<PipelineNode, Object> memo) {
        if (node == null) return 0f;
        if (memo.containsKey(node)) return memo.get(node);
        Map<String, PipelineNode> nodeUpstreams = upstreams.getOrDefault(findNodeId(node), Map.of());
        Object value = node.evaluate(context, new MemoizingUpstreamMap(nodeUpstreams, context, memo));
        memo.put(node, value);
        return value;
    }

    private String findNodeId(PipelineNode node) {
        for (Map.Entry<String, PipelineNode> entry : nodes.entrySet()) {
            if (entry.getValue() == node) return entry.getKey();
        }
        return "";
    }

    private static float asFloat(Object value, float fallback) {
        if (value instanceof Number number) return number.floatValue();
        return fallback;
    }

    public static Material material(String id, Material fallback) {
        if (id == null || id.isBlank()) return fallback;
        String value = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        Material material = Material.matchMaterial(value.toUpperCase(java.util.Locale.ROOT));
        return material != null ? material : fallback;
    }

    public static Biome biome(String id, Biome fallback) {
        if (id == null || id.isBlank()) return fallback;
        String value = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        try {
            return Biome.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static String normalizeBiomeId(Object id) {
        if (id == null || String.valueOf(id).isBlank()) {
            return "minecraft:plains";
        }
        String value = String.valueOf(id).toLowerCase(java.util.Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static Map<String, Map<String, PipelineNode>> copyUpstreams(Map<String, Map<String, PipelineNode>> upstreams) {
        Map<String, Map<String, PipelineNode>> copy = new HashMap<>();
        upstreams.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
        return Map.copyOf(copy);
    }

    private class MemoizingUpstreamMap extends HashMap<String, PipelineNode> {
        private final EvalContext context;
        private final Map<PipelineNode, Object> memo;

        private MemoizingUpstreamMap(Map<String, PipelineNode> upstreams, EvalContext context, Map<PipelineNode, Object> memo) {
            super(upstreams);
            this.context = context;
            this.memo = memo;
        }

        Object value(String pin, Object fallback) {
            PipelineNode node = get(pin);
            return node != null ? evaluate(node, context, memo) : fallback;
        }
    }

    public static Object input(Map<String, PipelineNode> upstreams, String pin, Object fallback) {
        if (upstreams instanceof TerrainPipeline.MemoizingUpstreamMap map) {
            return map.value(pin, fallback);
        }
        PipelineNode node = upstreams.get(pin);
        return node != null ? node.evaluate(new EvalContext(0f, 0f, 0f, 0, null), Map.of()) : fallback;
    }
}
