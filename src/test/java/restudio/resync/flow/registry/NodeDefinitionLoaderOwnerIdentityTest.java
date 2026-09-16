package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionLoaderOwnerIdentityTest {
    @Test
    void sameLocalIdAcrossDistinctOwnersIsAdmitted() {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(false);
        NodeDefinition alpha = definition("alpha", "shared.node");
        NodeDefinition beta = definition("beta", "shared.node");

        new NodeDefinitionLoader().validateAndRegister(List.of(alpha, beta), registry, null, "catalog");

        assertEquals(alpha, registry.get("alpha", "shared.node"));
        assertEquals(beta, registry.get("beta", "shared.node"));
        assertEquals(2, registry.getAllDefinitions().size());
    }

    @Test
    void exactOwnerAndLocalIdDuplicateIsDiagnosedBeforeRegistration() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(false);
        NodeDefinition first = definition("alpha", "shared.node");

        loader.validateAndRegister(List.of(first, definition("alpha", "shared.node")), registry, null, "catalog");

        assertNull(registry.get("alpha", "shared.node"));
        assertEquals(0, registry.getAllDefinitions().size());
        assertTrue(loader.getDiagnostics().stream().anyMatch(diagnostic -> "DUPLICATE_NODE_ID".equals(diagnostic.code())));
    }

    @Test
    void duplicateBatchDoesNotPublishAcceptedSiblingDefinitions() {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(false);
        registry.register("existing", definition("alpha", "shared.node"));
        NodeDefinitionLoader loader = new NodeDefinitionLoader();

        loader.validateAndRegister(List.of(
            definition("beta", "new.node"),
            definition("alpha", "shared.node")
        ), registry, null, "catalog");

        assertNull(registry.get("beta", "new.node"));
        assertEquals(1, registry.getAllDefinitions().size());
        assertTrue(loader.getDiagnostics().stream().anyMatch(diagnostic -> "DUPLICATE_NODE_ID".equals(diagnostic.code())));
    }

    private NodeDefinition definition(String owner, String id) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.DATA)
            .owner(owner)
            .build();
    }
}
