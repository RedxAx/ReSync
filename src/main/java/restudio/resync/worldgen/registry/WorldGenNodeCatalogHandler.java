package restudio.resync.worldgen.registry;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public final class WorldGenNodeCatalogHandler implements NodeHandler {
    private final Set<String> operations;

    public WorldGenNodeCatalogHandler(Collection<WorldGenNodeDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("WorldGen node definitions are required");
        }
        operations = definitions.stream().map(WorldGenNodeDefinition::getId).map(WorldGenNodeCatalogHandler::operationFor).collect(Collectors.toUnmodifiableSet());
    }

    public static String operationFor(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("WorldGen node ID is required");
        }
        return "worldgen_node_" + nodeId;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register(WorldGenFlowCatalog.HANDLER_ID, this);
    }

    @Override
    public void execute(FlowContext context, FlowNode node) {
        throw new IllegalStateException("WorldGen graph nodes execute through the WorldGen compiler");
    }

    @Override
    public Set<String> getSupportedOperations() {
        return operations;
    }
}
