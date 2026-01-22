package restudio.resync.flow.plugins;

import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public interface FlowNodePlugin {
    String getPluginId();

    String getVersion();

    String getDescription();

    void registerNodes(FlowRegistry registry);

    default void unregisterNodes(FlowRegistry registry) {
    }

    void registerNodeDefinitions(NodeDefinitionRegistry registry);

    default void unregisterNodeDefinitions(NodeDefinitionRegistry registry) {
    }
}
