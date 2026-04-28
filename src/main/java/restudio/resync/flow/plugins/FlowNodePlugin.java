package restudio.resync.flow.plugins;

import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;

import java.util.List;

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

    default List<FlowTypeMetadata> getCustomTypes() {
        return List.of();
    }

    default List<FlowCategoryMetadata> getCustomCategories() {
        return List.of();
    }

    default List<FlowOptionSourceMetadata> getCustomOptionSources() {
        return List.of();
    }

    default List<FlowConversionRule> getCustomConversionRules() {
        return List.of();
    }
}
