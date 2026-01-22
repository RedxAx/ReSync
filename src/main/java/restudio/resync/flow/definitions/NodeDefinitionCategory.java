package restudio.resync.flow.definitions;

import restudio.resync.flow.registry.NodeDefinitionRegistry;

public interface NodeDefinitionCategory {
    void registerNodes(NodeDefinitionRegistry registry);

    default String getCategoryName() {
        return this.getClass().getSimpleName();
    }
}
