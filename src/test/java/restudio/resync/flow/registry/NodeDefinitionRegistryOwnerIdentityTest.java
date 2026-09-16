package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionRegistryOwnerIdentityTest {
    @Test
    void duplicateOwnerAndLocalIdentityIsRejected() {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry();
        registry.register("alpha", definition("alpha", "shared.node"));

        assertThrows(IllegalArgumentException.class, () -> registry.register("other", definition("alpha", "shared.node")));
        assertEquals(1, registry.getAllDefinitions().size());
    }

    @Test
    void differentOwnersRetainTheSameLocalIdentity() {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry();
        NodeDefinition alpha = definition("alpha", "shared.node");
        NodeDefinition beta = definition("beta", "shared.node");
        registry.register("alpha-plugin", alpha);
        registry.register("beta-plugin", beta);

        assertEquals(2, registry.getAllDefinitions().size());
        assertTrue(registry.getAllDefinitions().keySet().stream().anyMatch(key -> key.startsWith("alpha") && key.indexOf(0) >= 0));
        assertTrue(registry.getAllDefinitions().keySet().stream().anyMatch(key -> key.startsWith("beta") && key.indexOf(0) >= 0));
        assertNull(registry.get("shared.node"));
        assertEquals(alpha, registry.get("alpha", "shared.node"));
        assertEquals(beta, registry.get("beta", "shared.node"));
        assertEquals("alpha-plugin", registry.getPluginForNode(alpha));
        assertEquals("beta-plugin", registry.getPluginForNode(beta));

        registry.unregisterPlugin("alpha-plugin");

        assertEquals(1, registry.getAllDefinitions().size());
        assertEquals(beta, registry.get("shared.node"));
    }

    @Test
    void pluginRemovalUsesRegisteredIdentityWhenDefinitionOwnerChanges() {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry();
        NodeDefinition definition = definition("alpha", "mutable.node");
        registry.register("alpha-plugin", definition);

        definition.assignOwner("changed");
        assertEquals("alpha-plugin", registry.getPluginForNode(definition));
        registry.unregisterPlugin("alpha-plugin");

        assertTrue(registry.getAllDefinitions().isEmpty());
        assertNull(registry.get("mutable.node"));
        assertTrue(registry.getPluginIds().isEmpty());
    }

    private NodeDefinition definition(String owner, String id) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.DATA)
            .owner(owner)
            .build();
    }
}
