package restudio.resync.flow.plugins;

import restudio.flow.data.TypeRegistry;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public interface FlowPluginExtension {
    String getPluginId();

    String getVersion();

    String getDescription();

    default void registerHandlers(HandlerRegistry registry) {
    }

    default void unregisterHandlers(HandlerRegistry registry) {
    }

    default void registerProperties(PropertyRegistry registry) {
    }

    default void unregisterProperties(PropertyRegistry registry) {
    }

    default void registerTypes(TypeRegistry registry) {
    }

    default void unregisterTypes(TypeRegistry registry) {
    }

    default void registerNodeDefinitions(NodeDefinitionLoader loader, NodeDefinitionRegistry registry) {
    }

    default void unregisterNodeDefinitions(NodeDefinitionRegistry registry) {
    }

    default void registerNodes(FlowRegistry registry) {
    }

    default void unregisterNodes(FlowRegistry registry) {
    }
}
