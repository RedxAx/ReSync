package restudio.resync.worldgen.pipeline;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.generator.WorldInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TerrainPipeline {
    private final Map<String, PipelineNode> nodes;
    private final Map<String, Map<String, PipelineNode>> upstreams;
    private final List<PipelineNode> outputNodes;
    private final PipelineNode heightOutput;
    private final PipelineNode biomeOutput;
    private final PipelineNode blockOutput;

    public TerrainPipeline(Map<String, PipelineNode> nodes, Map<String, Map<String, PipelineNode>> upstreams, List<PipelineNode> outputNodes,
                           PipelineNode heightOutput, PipelineNode biomeOutput, PipelineNode blockOutput) {
        this.nodes = Map.copyOf(nodes);
        this.upstreams = copyUpstreams(upstreams);
        this.outputNodes = List.copyOf(outputNodes);
        this.heightOutput = heightOutput;
        this.biomeOutput = biomeOutput;
        this.blockOutput = blockOutput;
    }

    public float getHeight(float x, float z, int seed, WorldInfo worldInfo) {
        Object value = evaluate(heightOutput, new EvalContext(x, 0f, z, seed, worldInfo), new HashMap<>());
        return asFloat(value, 64f);
    }

    public Biome getBiome(float x, float y, float z, int seed, WorldInfo worldInfo) {
        if (biomeOutput == null) return Biome.PLAINS;
        float value = asFloat(evaluate(biomeOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>()), 0f);
        Biome[] biomes = {Biome.PLAINS, Biome.FOREST, Biome.DESERT, Biome.SAVANNA, Biome.TAIGA, Biome.SNOWY_PLAINS};
        int index = Math.max(0, Math.min(biomes.length - 1, Math.round(Math.abs(value) * (biomes.length - 1))));
        return biomes[index];
    }

    public Material getBlock(float x, float y, float z, int seed, float height, WorldInfo worldInfo) {
        if (blockOutput == null) return y >= height ? Material.GRASS_BLOCK : Material.STONE;
        Object value = evaluate(blockOutput, new EvalContext(x, y, z, seed, worldInfo), new HashMap<>());
        if (value instanceof Material material) return material;
        return y >= height ? Material.GRASS_BLOCK : Material.STONE;
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
