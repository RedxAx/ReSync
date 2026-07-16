package restudio.resync.worldgen.pipeline;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.generator.WorldInfo;
import restudio.resync.worldgen.data.WorldGenSpawnRule;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TerrainPipeline {
    public static final String ANY_POLICY_KEY = "worldgen:any";
    private final Map<String, PipelineNode> nodes;
    private final Map<PipelineNode, String> nodeIds;
    private final Map<String, Map<String, PipelineNode>> upstreams;
    private final List<PipelineNode> outputNodes;
    private final PipelineNode heightOutput;
    private final PipelineNode densityOutput;
    private final PipelineNode continentalnessOutput;
    private final PipelineNode erosionOutput;
    private final PipelineNode weirdnessOutput;
    private final PipelineNode depthOutput;
    private final PipelineNode temperatureOutput;
    private final PipelineNode humidityOutput;
    private final PipelineNode biomeOutput;
    private final PipelineNode blockOutput;
    private final PipelineNode caveOutput;
    private final PipelineNode featureOutput;
    private final PipelineNode structureOutput;
    private final PipelineNode spawnOutput;
    private final CompiledBiomePolicy biomePolicy;
    private final List<WorldGenSpawnRule> spawnRules;

    public TerrainPipeline(Map<String, PipelineNode> nodes, Map<String, Map<String, PipelineNode>> upstreams, List<PipelineNode> outputNodes,
                           PipelineNode heightOutput, PipelineNode biomeOutput, PipelineNode blockOutput) {
        this(nodes, upstreams, outputNodes, heightOutput, null, null, null, null, null, null, null, biomeOutput, blockOutput, null, null, null, null, true, true, true, 63, Material.STONE, Material.WATER, Map.of(), Map.of(), Map.of(), List.of());
    }

    public TerrainPipeline(Map<String, PipelineNode> nodes, Map<String, Map<String, PipelineNode>> upstreams, List<PipelineNode> outputNodes,
                           PipelineNode heightOutput, PipelineNode biomeOutput, PipelineNode blockOutput, PipelineNode caveOutput,
                           PipelineNode featureOutput, PipelineNode structureOutput, PipelineNode spawnOutput,
                           boolean vanillaFeaturesEnabled, boolean vanillaStructuresEnabled, boolean vanillaSpawnsEnabled,
                           Map<String, Boolean> biomeVanillaFeatureOverrides) {
        this(nodes, upstreams, outputNodes, heightOutput, null, null, null, null, null, null, null, biomeOutput, blockOutput, caveOutput, featureOutput, structureOutput, spawnOutput,
            new CompiledBiomePolicy(vanillaFeaturesEnabled, vanillaStructuresEnabled, vanillaSpawnsEnabled, biomeVanillaFeatureOverrides, Map.of(), Map.of()), 63, Material.STONE, Material.WATER, List.of());
    }

    public TerrainPipeline(Map<String, PipelineNode> nodes, Map<String, Map<String, PipelineNode>> upstreams, List<PipelineNode> outputNodes,
                           PipelineNode heightOutput, PipelineNode biomeOutput, PipelineNode blockOutput, PipelineNode caveOutput,
                           PipelineNode featureOutput, PipelineNode structureOutput, PipelineNode spawnOutput,
                           boolean vanillaFeaturesEnabled, boolean vanillaStructuresEnabled, boolean vanillaSpawnsEnabled,
                           Map<String, Boolean> biomeVanillaFeatureOverrides, Map<String, Boolean> biomeVanillaStructureOverrides,
                           Map<String, Boolean> biomeVanillaSpawnOverrides, List<WorldGenSpawnRule> spawnRules) {
        this(nodes, upstreams, outputNodes, heightOutput, null, null, null, null, null, null, null, biomeOutput, blockOutput, caveOutput, featureOutput, structureOutput, spawnOutput,
            new CompiledBiomePolicy(vanillaFeaturesEnabled, vanillaStructuresEnabled, vanillaSpawnsEnabled, biomeVanillaFeatureOverrides, biomeVanillaStructureOverrides, biomeVanillaSpawnOverrides), 63, Material.STONE, Material.WATER, spawnRules);
    }

    public TerrainPipeline(Map<String, PipelineNode> nodes, Map<String, Map<String, PipelineNode>> upstreams, List<PipelineNode> outputNodes,
                           PipelineNode heightOutput, PipelineNode densityOutput, PipelineNode continentalnessOutput, PipelineNode erosionOutput,
                           PipelineNode weirdnessOutput, PipelineNode depthOutput, PipelineNode temperatureOutput, PipelineNode humidityOutput,
                           PipelineNode biomeOutput, PipelineNode blockOutput, PipelineNode caveOutput,
                           PipelineNode featureOutput, PipelineNode structureOutput, PipelineNode spawnOutput,
                           boolean vanillaFeaturesEnabled, boolean vanillaStructuresEnabled, boolean vanillaSpawnsEnabled,
                           int seaLevel, Material defaultBlock, Material defaultFluid,
                           Map<String, Boolean> biomeVanillaFeatureOverrides, Map<String, Boolean> biomeVanillaStructureOverrides,
                           Map<String, Boolean> biomeVanillaSpawnOverrides, List<WorldGenSpawnRule> spawnRules) {
        this(nodes, upstreams, outputNodes, heightOutput, densityOutput, continentalnessOutput, erosionOutput, weirdnessOutput, depthOutput, temperatureOutput,
            humidityOutput, biomeOutput, blockOutput, caveOutput, featureOutput, structureOutput, spawnOutput,
            new CompiledBiomePolicy(vanillaFeaturesEnabled, vanillaStructuresEnabled, vanillaSpawnsEnabled, biomeVanillaFeatureOverrides, biomeVanillaStructureOverrides, biomeVanillaSpawnOverrides),
            seaLevel, defaultBlock, defaultFluid, spawnRules);
    }

    public TerrainPipeline(Map<String, PipelineNode> nodes, Map<String, Map<String, PipelineNode>> upstreams, List<PipelineNode> outputNodes,
                           PipelineNode heightOutput, PipelineNode densityOutput, PipelineNode continentalnessOutput, PipelineNode erosionOutput,
                           PipelineNode weirdnessOutput, PipelineNode depthOutput, PipelineNode temperatureOutput, PipelineNode humidityOutput,
                           PipelineNode biomeOutput, PipelineNode blockOutput, PipelineNode caveOutput,
                           PipelineNode featureOutput, PipelineNode structureOutput, PipelineNode spawnOutput,
                           CompiledBiomePolicy biomePolicy, int seaLevel, Material defaultBlock, Material defaultFluid,
                           List<WorldGenSpawnRule> spawnRules) {
        this.nodes = Map.copyOf(nodes);
        this.nodeIds = indexNodeIds(this.nodes);
        this.upstreams = copyUpstreams(upstreams);
        this.outputNodes = List.copyOf(outputNodes);
        this.heightOutput = heightOutput;
        this.densityOutput = densityOutput;
        this.continentalnessOutput = continentalnessOutput;
        this.erosionOutput = erosionOutput;
        this.weirdnessOutput = weirdnessOutput;
        this.depthOutput = depthOutput;
        this.temperatureOutput = temperatureOutput;
        this.humidityOutput = humidityOutput;
        this.biomeOutput = biomeOutput;
        this.blockOutput = blockOutput;
        this.caveOutput = caveOutput;
        this.featureOutput = featureOutput;
        this.structureOutput = structureOutput;
        this.spawnOutput = spawnOutput;
        this.biomePolicy = biomePolicy != null ? biomePolicy : new CompiledBiomePolicy(false, false, false, Map.of(), Map.of(), Map.of());
        this.seaLevel = seaLevel;
        this.defaultBlock = defaultBlock == null ? Material.STONE : defaultBlock;
        this.defaultFluid = defaultFluid == null ? Material.WATER : defaultFluid;
        this.spawnRules = spawnRules != null ? List.copyOf(spawnRules) : List.of();
    }

    private final int seaLevel;
    private final Material defaultBlock;
    private final Material defaultFluid;

    public float getHeight(float x, float z, long seed, WorldInfo worldInfo) {
        if (heightOutput == null) {
            return deriveHeight(x, z, seed, worldInfo);
        }
        Object value = evaluate(heightOutput, new EvalContext(x, 0f, z, seed, worldInfo), new HashMap<>());
        return asFloat(value, 64f);
    }

    public boolean hasDensityOutput() {
        return densityOutput != null;
    }

    public float getDensity(float x, float y, float z, long seed, WorldInfo worldInfo) {
        if (densityOutput != null) {
            return asFloat(evaluate(densityOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>()), 0f);
        }
        return (getHeight(x, z, seed, worldInfo) - y) / 12f;
    }

    public float getContinentalness(float x, float y, float z, long seed, WorldInfo worldInfo) {
        return field(continentalnessOutput, x, y, z, seed, worldInfo, 0f);
    }

    public float getErosion(float x, float y, float z, long seed, WorldInfo worldInfo) {
        return field(erosionOutput, x, y, z, seed, worldInfo, 0f);
    }

    public float getWeirdness(float x, float y, float z, long seed, WorldInfo worldInfo) {
        return field(weirdnessOutput, x, y, z, seed, worldInfo, 0f);
    }

    public float getDepth(float x, float y, float z, long seed, WorldInfo worldInfo) {
        return field(depthOutput, x, y, z, seed, worldInfo, 0f);
    }

    public float getTemperature(float x, float y, float z, long seed, WorldInfo worldInfo) {
        return field(temperatureOutput, x, y, z, seed, worldInfo, 0.5f);
    }

    public float getHumidity(float x, float y, float z, long seed, WorldInfo worldInfo) {
        return field(humidityOutput, x, y, z, seed, worldInfo, 0.5f);
    }

    private float field(PipelineNode output, float x, float y, float z, long seed, WorldInfo worldInfo, float fallback) {
        if (output == null) {
            return fallback;
        }
        return asFloat(evaluate(output, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>()), fallback);
    }

    private float deriveHeight(float x, float z, long seed, WorldInfo worldInfo) {
        int min = worldInfo == null ? -64 : worldInfo.getMinHeight();
        int max = worldInfo == null ? 320 : worldInfo.getMaxHeight();
        for (int y = max - 1; y >= min; y--) {
            if (getDensity(x, y, z, seed, worldInfo) > 0f) {
                return y;
            }
        }
        return seaLevel;
    }

    public Biome getBiome(float x, float y, float z, long seed, WorldInfo worldInfo) {
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

    public BiomeChoice getBiomeChoice(float x, float y, float z, long seed, WorldInfo worldInfo) {
        if (biomeOutput == null) {
            return new BiomeChoice("minecraft:plains", biomePolicy.defaultFeatures(), biomePolicy.defaultStructures(), biomePolicy.defaultSpawns());
        }
        Object raw = evaluate(biomeOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        if (raw instanceof BiomeChoice choice) {
            return choice;
        }
        String biomeId;
        if (raw instanceof Biome biome) {
            biomeId = "minecraft:" + biome.name().toLowerCase(Locale.ROOT);
        } else if (raw instanceof String id) {
            biomeId = normalizeBiomeId(id);
        } else {
            Biome biome = getBiome(x, y, z, seed, worldInfo);
            biomeId = "minecraft:" + biome.name().toLowerCase(Locale.ROOT);
        }
        Biome biome = biome(biomeId, Biome.PLAINS);
        return new BiomeChoice(biomeId, isVanillaFeaturesEnabled(biome), isVanillaStructuresEnabled(biome), isVanillaSpawnsEnabled(biome));
    }

    public Material getBlock(float x, float y, float z, long seed, float height, WorldInfo worldInfo) {
        if (blockOutput == null) return defaultSurfaceBlock(y, height);
        Object value = evaluate(blockOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        if (value instanceof Material material) return material;
        if (value instanceof String id) return material(id, defaultSurfaceBlock(y, height));
        return defaultSurfaceBlock(y, height);
    }

    public Material getDefaultBlock() {
        return defaultBlock;
    }

    public Material getDefaultFluid() {
        return defaultFluid;
    }

    public int getSeaLevel() {
        return seaLevel;
    }

    private Material defaultSurfaceBlock(float y, float height) {
        if (y >= height) {
            return height <= seaLevel + 1 ? Material.SAND : Material.GRASS_BLOCK;
        }
        if (y >= height - 4) {
            return height <= seaLevel + 1 ? Material.SANDSTONE : Material.DIRT;
        }
        return defaultBlock;
    }

    public float getCaveDensity(float x, float y, float z, long seed, WorldInfo worldInfo) {
        if (caveOutput == null) return 1f;
        return asFloat(evaluate(caveOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>()), 1f);
    }

    public String getFeaturePlacement(float x, float y, float z, long seed, WorldInfo worldInfo) {
        if (featureOutput == null) return "";
        Object value = evaluate(featureOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        return value == null ? "" : String.valueOf(value);
    }

    public String getStructurePlacement(float x, float y, float z, long seed, WorldInfo worldInfo) {
        if (structureOutput == null) return "";
        Object value = evaluate(structureOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        return value == null ? "" : String.valueOf(value);
    }

    public String getSpawnTable(float x, float y, float z, long seed, WorldInfo worldInfo) {
        if (spawnOutput == null) return "";
        Object value = evaluate(spawnOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        return value == null ? "" : String.valueOf(value);
    }

    public boolean isVanillaFeaturesEnabled() {
        return biomePolicy.defaultFeatures();
    }

    public boolean hasAnyVanillaFeaturesEnabled() {
        return biomePolicy.hasAnyFeatures();
    }

    public boolean isVanillaFeaturesEnabled(Biome biome) {
        return biomePolicy.features(biome);
    }

    public boolean isVanillaFeaturesEnabled(float x, float y, float z, long seed, WorldInfo worldInfo) {
        return getBiomeChoice(x, y, z, seed, worldInfo).keepVanillaFeatures();
    }

    public boolean isVanillaStructuresEnabled() {
        return biomePolicy.defaultStructures();
    }

    public boolean hasAnyVanillaStructuresEnabled() {
        return biomePolicy.hasAnyStructures();
    }

    public boolean isVanillaStructuresEnabled(Biome biome) {
        return biomePolicy.structures(biome);
    }

    public boolean isVanillaStructuresEnabled(float x, float y, float z, long seed, WorldInfo worldInfo) {
        return getBiomeChoice(x, y, z, seed, worldInfo).keepVanillaStructures();
    }

    public boolean isVanillaSpawnsEnabled() {
        return biomePolicy.defaultSpawns();
    }

    public boolean hasAnyVanillaSpawnsEnabled() {
        return biomePolicy.hasAnySpawns();
    }

    public boolean isVanillaSpawnsEnabled(Biome biome) {
        return biomePolicy.spawns(biome);
    }

    public boolean isVanillaSpawnsEnabled(float x, float y, float z, long seed, WorldInfo worldInfo) {
        return getBiomeChoice(x, y, z, seed, worldInfo).keepVanillaSpawns();
    }

    public List<WorldGenSpawnRule> getSpawnRules() {
        return spawnRules;
    }

    public EntityType entityType(String id, EntityType fallback) {
        if (id == null || id.isBlank()) return fallback;
        String value = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        try {
            return EntityType.valueOf(value.toUpperCase(Locale.ROOT));
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
        return nodeIds.getOrDefault(node, "");
    }

    private static float asFloat(Object value, float fallback) {
        if (value instanceof Number number) return number.floatValue();
        return fallback;
    }

    public static Material material(String id, Material fallback) {
        if (id == null || id.isBlank()) return fallback;
        String value = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        Material material = Material.matchMaterial(value.toUpperCase(Locale.ROOT));
        return material != null ? material : fallback;
    }

    public static Biome biome(String id, Biome fallback) {
        if (id == null || id.isBlank()) return fallback;
        String value = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        try {
            return Biome.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static String normalizeBiomeId(Object id) {
        if (id == null || String.valueOf(id).isBlank()) {
            return "minecraft:plains";
        }
        String value = String.valueOf(id).toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static Map<String, Map<String, PipelineNode>> copyUpstreams(Map<String, Map<String, PipelineNode>> upstreams) {
        Map<String, Map<String, PipelineNode>> copy = new HashMap<>();
        upstreams.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
        return Map.copyOf(copy);
    }

    private static Map<PipelineNode, String> indexNodeIds(Map<String, PipelineNode> nodes) {
        Map<PipelineNode, String> ids = new HashMap<>();
        nodes.forEach((id, node) -> ids.put(node, id));
        return Map.copyOf(ids);
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
