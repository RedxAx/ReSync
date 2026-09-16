package restudio.resync.api;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReSyncExtensionManagerNodeIdentityTest {
    private static final Path CATALOG = Path.of("examples", "request-extension", "src", "main", "resources", "request", "nodes");

    @Test
    void strictLocalExtensionDescriptorPublishesItsOwnerQualifiedWireIdentity() {
        NodeDefinition local = strictDefinition("quest_info");

        NodeDefinition wire = ReSyncExtensionManager.extensionWireDefinition("request", local);

        assertEquals("request:quest_info", wire.getId());
        assertEquals("request", wire.getOwner());
        assertEquals("quest_info", wire.getAuthoredMetadata().id());
        assertEquals("request:handler", wire.getHandler());
        assertEquals("info", wire.getHandlerConfig().get("operation"));
    }

    @Test
    void wireIdentityCollisionIsRejectedAcrossOwners() {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(false);
        NodeDefinition existing = new NodeDefinition.Builder("request:quest_info", "Existing", NodeDefinition.NodeCategory.UTILITY)
            .owner("other")
            .handler("other:handler")
            .handlerConfig(Map.of("operation", "existing"))
            .input("value", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .build();
        registry.register("other", existing);

        assertThrows(IllegalArgumentException.class,
            () -> ReSyncExtensionManager.requireAvailableExtensionWireId(registry, "request:quest_info"));
    }

    @Test
    void fullStrictCatalogPublishesOnlyRequestOwnedWireIds() {
        FlowDataType.registerExtensionType("request", new FlowDataType("request:quest", FlowDataType.STRING, String.class, null, 0x46B48A));
        try {
            List<NodeDefinition> localDefinitions = new NodeDefinitionLoader().loadReplacementFromDirectory(CATALOG);
            List<NodeDefinition> wireDefinitions = localDefinitions.stream()
                .map(value -> ReSyncExtensionManager.extensionWireDefinition("request", value))
                .toList();

            assertEquals(17, wireDefinitions.size());
            assertEquals(Set.of(
                "request:quest_info", "request:find_quest", "request:quest_list", "request:quest_profile",
                "request:create_quest", "request:quest_action", "request:quest_progress", "request:add_quest_xp",
                "request:quest_event_listen", "request:delete_quest", "request:can_start_quest", "request:start_quest",
                "request:add_progress", "request:set_progress", "request:complete_quest", "request:quit_quest",
                "request:reset_quest"
            ), wireDefinitions.stream().map(NodeDefinition::getId).collect(Collectors.toSet()));
            assertEquals(Set.of("request"), wireDefinitions.stream().map(NodeDefinition::getOwner).collect(Collectors.toSet()));
            assertEquals(Set.of("quest_info", "find_quest", "quest_list"), wireDefinitions.stream()
                .filter(value -> Set.of("request:quest_info", "request:find_quest", "request:quest_list").contains(value.getId()))
                .map(value -> value.getAuthoredMetadata().id()).collect(Collectors.toSet()));
        } finally {
            FlowDataType.unregisterExtensionType("request", "request:quest");
        }
    }

    @Test
    void legacyManualDefinitionsStillRequireTheirWirePrefix() {
        NodeDefinition legacy = new NodeDefinition.Builder("quest_info", "Legacy", NodeDefinition.NodeCategory.UTILITY)
            .handler("request:handler")
            .handlerConfig(Map.of("operation", "info"))
            .build();

        assertThrows(IllegalArgumentException.class, () -> ReSyncExtensionManager.extensionWireDefinition("request", legacy));
    }

    private NodeDefinition strictDefinition(String id) {
        String json = """
            [{
              "id":"%s",
              "owner":"request",
              "displayName":"Quest Info",
              "category":"UTILITY",
              "schemaVersion":2,
              "kind":"QUERY",
              "description":"Reads the selected quest definition and Player state.",
              "domain":"quest",
              "family":"quest",
              "lifecycle":"active",
              "handlerCapability":"quest.handler",
              "selectorIntent":"typed-reference",
              "inspectorIntent":"generic",
              "handler":"request:handler",
              "handlerConfig":{"operation":"info"},
              "inputs":[{"id":"quest","direction":"input","displayName":"Quest","name":"quest","dataType":"string","description":"Selects the quest definition used by this operation."}],
              "outputs":[{"id":"title","direction":"output","displayName":"Title","name":"title","dataType":"string","description":"Returns the title of the selected quest definition."}]
            }]
            """.formatted(id);
        List<NodeDefinition> definitions = new NodeDefinitionLoader().parseReplacement(
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "request/nodes/quest-info.json");
        return definitions.getFirst();
    }
}
