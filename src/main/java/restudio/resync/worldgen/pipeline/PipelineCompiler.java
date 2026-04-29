package restudio.resync.worldgen.pipeline;

import restudio.flow.data.FlowDataType;
import restudio.resync.worldgen.data.WorldGenConnection;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.evaluator.FractalEvaluator;
import restudio.resync.worldgen.evaluator.NoiseEvaluator;
import restudio.resync.worldgen.registry.WorldGenNodeDefinition;
import restudio.resync.worldgen.registry.WorldGenNodeDefinitions;
import restudio.resync.worldgen.registry.WorldGenNodeRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PipelineCompiler {
    public static TerrainPipeline compile(WorldGenGraph graph) {
        if (graph == null) throw new CompilationException("Graph Missing");
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        if (!registry.hasDefinitions()) {
            WorldGenNodeDefinitions.registerDefaults(registry);
        }
        graph.rebuildIndices();
        validateDag(graph);
        validateTypes(graph, registry);
        Map<String, PipelineNode> nodes = new HashMap<>();
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            nodes.put(entry.getKey(), createNode(entry.getValue()));
        }
        Map<String, Map<String, PipelineNode>> upstreams = new HashMap<>();
        for (WorldGenConnection connection : graph.getConnections()) {
            PipelineNode source = nodes.get(connection.getSourceNodeId());
            if (source != null) {
                upstreams.computeIfAbsent(connection.getTargetNodeId(), key -> new HashMap<>()).put(connection.getTargetPin(), source);
            }
        }
        List<PipelineNode> outputNodes = new ArrayList<>();
        PipelineNode heightOutput = null;
        PipelineNode biomeOutput = null;
        PipelineNode blockOutput = null;
        Set<String> nodesWithOutgoing = new HashSet<>();
        for (WorldGenConnection connection : graph.getConnections()) {
            nodesWithOutgoing.add(connection.getSourceNodeId());
        }
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            PipelineNode pipelineNode = nodes.get(entry.getKey());
            if (!nodesWithOutgoing.contains(entry.getKey())) {
                outputNodes.add(pipelineNode);
            }
            if ("output_height".equals(entry.getValue().getType())) heightOutput = pipelineNode;
            if ("output_biome".equals(entry.getValue().getType())) biomeOutput = pipelineNode;
            if ("output_block".equals(entry.getValue().getType())) blockOutput = pipelineNode;
        }
        if (heightOutput == null) throw new CompilationException("Output Height Missing");
        return new TerrainPipeline(nodes, upstreams, outputNodes, heightOutput, biomeOutput, blockOutput);
    }

    public static void invalidate(String graphId) {
    }

    private static void validateDag(WorldGenGraph graph) {
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        graph.getNodes().keySet().forEach(id -> incoming.put(id, 0));
        for (WorldGenConnection connection : graph.getConnections()) {
            incoming.compute(connection.getTargetNodeId(), (key, value) -> value == null ? 1 : value + 1);
            outgoing.computeIfAbsent(connection.getSourceNodeId(), key -> new ArrayList<>()).add(connection.getTargetNodeId());
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        incoming.forEach((id, count) -> {
            if (count == 0) queue.add(id);
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            visited++;
            for (String target : outgoing.getOrDefault(nodeId, List.of())) {
                int count = incoming.compute(target, (key, value) -> value == null ? 0 : value - 1);
                if (count == 0) queue.add(target);
            }
        }
        if (visited != graph.getNodes().size()) throw new CompilationException("Cycle Detected");
    }

    private static void validateTypes(WorldGenGraph graph, WorldGenNodeRegistry registry) {
        for (WorldGenConnection connection : graph.getConnections()) {
            WorldGenNode sourceNode = graph.getNodes().get(connection.getSourceNodeId());
            WorldGenNode targetNode = graph.getNodes().get(connection.getTargetNodeId());
            if (sourceNode == null || targetNode == null) throw new CompilationException("Connection Node Missing");
            WorldGenNodeDefinition sourceDefinition = registry.getDefinition(sourceNode.getType());
            WorldGenNodeDefinition targetDefinition = registry.getDefinition(targetNode.getType());
            if (sourceDefinition == null || targetDefinition == null) throw new CompilationException("Node Definition Missing");
            WorldGenNodeDefinition.PinDefinition sourcePin = sourceDefinition.output(connection.getSourcePin());
            WorldGenNodeDefinition.PinDefinition targetPin = targetDefinition.input(connection.getTargetPin());
            if (sourcePin == null || targetPin == null) throw new CompilationException("Pin Definition Missing");
            if (!sourcePin.dataType().equals(targetPin.dataType())) throw new CompilationException("Type Mismatch");
        }
    }

    private static PipelineNode createNode(WorldGenNode node) {
        return switch (node.getType()) {
            case "simplex" -> (ctx, upstreams) -> NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f));
            case "perlin" -> (ctx, upstreams) -> NoiseEvaluator.evaluatePerlin(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f));
            case "value" -> (ctx, upstreams) -> NoiseEvaluator.evaluateValue(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f));
            case "cellular" -> (ctx, upstreams) -> NoiseEvaluator.evaluateCellular(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f), String.valueOf(input(node, upstreams, "distance_func", "euclidean")));
            case "white" -> (ctx, upstreams) -> NoiseEvaluator.evaluateWhite(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx));
            case "fbm" -> (ctx, upstreams) -> FractalEvaluator.evaluateFBM(inputFloat(node, upstreams, "source", 0f), Math.round(inputFloat(node, upstreams, "octaves", 4f)), inputFloat(node, upstreams, "lacunarity", 2f), inputFloat(node, upstreams, "gain", 0.5f));
            case "ridged" -> (ctx, upstreams) -> FractalEvaluator.evaluateRidged(inputFloat(node, upstreams, "source", 0f), Math.round(inputFloat(node, upstreams, "octaves", 4f)), inputFloat(node, upstreams, "lacunarity", 2f), inputFloat(node, upstreams, "gain", 0.5f));
            case "ping_pong" -> (ctx, upstreams) -> FractalEvaluator.evaluatePingPong(inputFloat(node, upstreams, "source", 0f), Math.round(inputFloat(node, upstreams, "octaves", 4f)), inputFloat(node, upstreams, "lacunarity", 2f), inputFloat(node, upstreams, "gain", 0.5f), inputFloat(node, upstreams, "ping_pong_strength", 2f));
            case "add" -> (ctx, upstreams) -> inputFloat(node, upstreams, "a", 0f) + inputFloat(node, upstreams, "b", 0f);
            case "multiply" -> (ctx, upstreams) -> inputFloat(node, upstreams, "a", 1f) * inputFloat(node, upstreams, "b", 1f);
            case "remap" -> (ctx, upstreams) -> remap(inputFloat(node, upstreams, "in", 0f), inputFloat(node, upstreams, "from_min", -1f), inputFloat(node, upstreams, "from_max", 1f), inputFloat(node, upstreams, "to_min", 0f), inputFloat(node, upstreams, "to_max", 128f));
            case "clamp" -> (ctx, upstreams) -> clamp(inputFloat(node, upstreams, "in", 0f), inputFloat(node, upstreams, "min", 0f), inputFloat(node, upstreams, "max", 1f));
            case "abs" -> (ctx, upstreams) -> Math.abs(inputFloat(node, upstreams, "in", 0f));
            case "min" -> (ctx, upstreams) -> Math.min(inputFloat(node, upstreams, "a", 0f), inputFloat(node, upstreams, "b", 0f));
            case "max" -> (ctx, upstreams) -> Math.max(inputFloat(node, upstreams, "a", 0f), inputFloat(node, upstreams, "b", 0f));
            case "domain_warp_gradient", "domain_warp_simplex" -> (ctx, upstreams) -> inputFloat(node, upstreams, "source", 0f) + NoiseEvaluator.evaluateSimplex(ctx.x(), ctx.y(), ctx.z(), seed(node, upstreams, ctx), inputFloat(node, upstreams, "frequency", 0.01f)) * inputFloat(node, upstreams, "amplitude", 1f);
            case "terrace" -> (ctx, upstreams) -> terrace(inputFloat(node, upstreams, "in", 0f), inputFloat(node, upstreams, "step_count", 8f));
            case "seed_offset" -> (ctx, upstreams) -> inputFloat(node, upstreams, "in", 0f);
            case "output_height" -> (ctx, upstreams) -> inputFloat(node, upstreams, "height", 64f);
            case "output_biome" -> (ctx, upstreams) -> inputFloat(node, upstreams, "biome", 0f);
            case "output_block" -> (ctx, upstreams) -> input(node, upstreams, "block", null);
            default -> throw new CompilationException("Unsupported Node " + node.getType());
        };
    }

    private static Object input(WorldGenNode node, Map<String, PipelineNode> upstreams, String pin, Object fallback) {
        return TerrainPipeline.input(upstreams, pin, node.getInputValues().getOrDefault(pin, fallback));
    }

    private static float inputFloat(WorldGenNode node, Map<String, PipelineNode> upstreams, String pin, float fallback) {
        Object value = input(node, upstreams, pin, fallback);
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private static int seed(WorldGenNode node, Map<String, PipelineNode> upstreams, EvalContext context) {
        Object value = input(node, upstreams, "seed", context.seed());
        return value instanceof Number number ? number.intValue() + context.seed() : context.seed();
    }

    private static float remap(float value, float fromMin, float fromMax, float toMin, float toMax) {
        if (fromMax == fromMin) return toMin;
        return toMin + (value - fromMin) / (fromMax - fromMin) * (toMax - toMin);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float terrace(float value, float steps) {
        float count = Math.max(1f, steps);
        return Math.round(value * count) / count;
    }
}
