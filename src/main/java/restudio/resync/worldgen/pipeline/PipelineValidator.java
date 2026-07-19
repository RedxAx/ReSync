package restudio.resync.worldgen.pipeline;

import restudio.resync.worldgen.data.WorldGenConnection;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenStage;
import restudio.resync.worldgen.registry.WorldGenNodeDefinition;
import restudio.resync.worldgen.registry.WorldGenNodeRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PipelineValidator {
    private PipelineValidator() {
    }

    static void validateTerrainStage(WorldGenGraph graph) {
        validateStage(graph, WorldGenStage.TERRAIN, Set.of(), false);
        boolean hasTerrainOutput = graph.getNodes().values().stream().anyMatch(node -> "output_height".equals(node.getType()) || "output_density".equals(node.getType()));
        if (!hasTerrainOutput) throw new CompilationException("TERRAIN Output Missing");
    }

    static void validateStage(WorldGenGraph graph, WorldGenStage stage, Set<String> requiredOutputs, boolean allowEmpty) {
        if (graph == null) {
            if (allowEmpty) return;
            throw new CompilationException(stage.name() + " Graph Missing");
        }
        if (allowEmpty && graph.getNodes().isEmpty()) {
            return;
        }
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        graph.rebuildIndices();
        validateDefinitions(graph, registry);
        validateDag(graph);
        validateTypes(graph, registry);
        validateSingleInputConnections(graph);
        validateSingleOutputs(graph, stage);
        for (String requiredOutput : requiredOutputs) {
            boolean found = graph.getNodes().values().stream().anyMatch(node -> requiredOutput.equals(node.getType()));
            if (!found) throw new CompilationException(stage.name() + " Output Missing");
        }
    }

    static void validateDag(WorldGenGraph graph) {
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

    static void validateTypes(WorldGenGraph graph, WorldGenNodeRegistry registry) {
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

    private static void validateDefinitions(WorldGenGraph graph, WorldGenNodeRegistry registry) {
        for (Map.Entry<String, WorldGenNode> entry : graph.getNodes().entrySet()) {
            WorldGenNode node = entry.getValue();
            if (node == null || registry.getDefinition(node.getType()) == null) throw new CompilationException("Unsupported Node " + (node == null ? entry.getKey() : node.getType()));
        }
    }

    private static void validateSingleInputConnections(WorldGenGraph graph) {
        Set<String> targets = new HashSet<>();
        for (WorldGenConnection connection : graph.getConnections()) {
            String target = connection.getTargetNodeId() + ":" + connection.getTargetPin();
            if (!targets.add(target)) throw new CompilationException("Duplicate Input Connection " + target);
        }
    }

    private static void validateSingleOutputs(WorldGenGraph graph, WorldGenStage stage) {
        Set<String> outputTypes = switch (stage) {
            case TERRAIN -> Set.of("output_height", "output_density", "output_continentalness", "output_erosion", "output_weirdness", "output_depth", "output_temperature", "output_humidity");
            case BIOME -> Set.of("output_biome");
            case SURFACE -> Set.of("output_block");
            case CAVE -> Set.of("carve_if");
            case FEATURE -> Set.of("output_features");
            case STRUCTURE -> Set.of("output_structures");
            case SPAWN -> Set.of("output_spawns");
        };
        Map<String, Integer> counts = new HashMap<>();
        for (WorldGenNode node : graph.getNodes().values()) {
            if (node != null && outputTypes.contains(node.getType())) {
                int count = counts.compute(node.getType(), (key, value) -> value == null ? 1 : value + 1);
                if (count > 1) throw new CompilationException(stage.name() + " Duplicate Output " + node.getType());
            }
        }
    }
}
