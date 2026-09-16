package restudio.resync.worldgen.registry;

import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.List;
import java.util.Map;

public final class WorldGenFlowCatalogContribution {
    private final List<NodeDefinition> definitions;
    private final WorldGenNodeCatalogHandler handler;

    private WorldGenFlowCatalogContribution(List<NodeDefinition> definitions,
                                             WorldGenNodeCatalogHandler handler) {
        this.definitions = List.copyOf(definitions);
        this.handler = handler;
    }

    public static WorldGenFlowCatalogContribution create() {
        List<WorldGenNodeDefinition> sourceDefinitions = WorldGenNodeDefinitions.defaultDefinitions();
        return new WorldGenFlowCatalogContribution(WorldGenFlowCatalog.flowDefinitions(sourceDefinitions),
            new WorldGenNodeCatalogHandler(sourceDefinitions));
    }

    public List<NodeDefinition> definitions() {
        return definitions;
    }

    public void apply(NodeDefinitionRegistry definitions, HandlerRegistry handlers) {
        if (definitions == null || handlers == null) {
            throw new IllegalArgumentException("WorldGen catalog registries are required");
        }
        handlers.register(WorldGenFlowCatalog.HANDLER_ID, handler);
        Map<String, NodeDefinition> existing = definitions.getAllDefinitions();
        List<NodeDefinition> missing = this.definitions.stream()
            .filter(definition -> !existing.containsKey(definition.getOwner() + '\u0000' + definition.getId()))
            .toList();
        if (!missing.isEmpty()) {
            definitions.registerAll("worldgen", missing);
        }
    }
}
