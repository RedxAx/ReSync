package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionLoaderBatchAdmissionTest {
    @Test
    void acceptedCatalogPublishesInOneAuthoredOrderBatch() {
        TrackingRegistry registry = new TrackingRegistry();
        List<NodeDefinition> definitions = new ArrayList<>();
        for (int index = 0; index < 512; index++) {
            definitions.add(definition("owner-" + index % 4, "node-" + index));
        }

        new NodeDefinitionLoader().validateAndRegister(definitions, registry, null, "catalog");

        assertEquals(1, registry.publications);
        assertEquals(definitions, registry.published);
        assertEquals(definitions, registry.getDefinitionsForPlugin("catalog"));
    }

    @Test
    void admissionUsesOwnerQualifiedSetInsteadOfPerDefinitionRegistryPublication() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/flow/registry/NodeDefinitionLoader.java"));
        String method = source.substring(source.indexOf("public void validateAndRegister"), source.indexOf("public void rejectUnavailable"));

        assertTrue(method.contains("Set<AdmissionIdentity> acceptedIdentities"));
        assertTrue(method.contains("registry.registerAll(pluginId, accepted)"));
        assertFalse(method.contains("new NodeDefinitionRegistry"));
        assertFalse(method.contains("admissionRegistry.register"));
    }

    private NodeDefinition definition(String owner, String id) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.DATA)
            .owner(owner)
            .build();
    }

    private static final class TrackingRegistry extends NodeDefinitionRegistry {
        private int publications;
        private List<NodeDefinition> published = List.of();

        private TrackingRegistry() {
            super(false);
        }

        @Override
        public synchronized void registerAll(String pluginId, List<NodeDefinition> nodeDefinitions) {
            publications++;
            published = List.copyOf(nodeDefinitions);
            super.registerAll(pluginId, nodeDefinitions);
        }
    }
}
