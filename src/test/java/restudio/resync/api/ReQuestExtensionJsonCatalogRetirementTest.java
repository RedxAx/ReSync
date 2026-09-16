package restudio.resync.api;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.registry.NodeDefinitionLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReQuestExtensionJsonCatalogRetirementTest {
    private static final Path EXTENSION_SOURCE = Path.of("examples", "request-extension", "src", "main", "java", "restudio", "request", "ReQuestExtension.java");
    private static final Path CATALOG = Path.of("examples", "request-extension", "src", "main", "resources", "request", "nodes");
    private static final Set<String> WIRE_IDS = Set.of(
        "request:quest_info", "request:find_quest", "request:quest_list", "request:quest_profile", "request:create_quest",
        "request:quest_action", "request:quest_progress", "request:add_quest_xp", "request:quest_event_listen",
        "request:delete_quest", "request:can_start_quest", "request:start_quest", "request:add_progress",
        "request:set_progress", "request:complete_quest", "request:quit_quest", "request:reset_quest"
    );

    @Test
    void extensionRegistersTheStrictJsonCatalogWithoutAJavaNodeBuilder() throws Exception {
        String source = Files.readString(EXTENSION_SOURCE);

        assertEquals(1, source.lines().filter(line -> line.contains("context.flow().registerNodes(\"request/nodes\")")).count());
        assertFalse(source.contains("context.flow().registerNode("));
        assertFalse(source.contains("NodeDefinition"));
        assertTrue(source.contains("context.flow().registerHandler(HANDLER_ID, new ReQuestHandler(service));"));
        assertTrue(source.indexOf("context.flow().registerHandler(HANDLER_ID, new ReQuestHandler(service));")
            < source.indexOf("context.flow().registerNodes(\"request/nodes\");"));

        FlowDataType.registerExtensionType("request", new FlowDataType("request:quest", FlowDataType.STRING, String.class, null, 0x46B48A));
        try {
            NodeDefinitionLoader loader = new NodeDefinitionLoader();
            List<NodeDefinition> definitions = loader.loadReplacementFromDirectory(CATALOG);
            Set<String> wireIds = definitions.stream()
                .map(definition -> ReSyncExtensionManager.extensionWireDefinition("request", definition).getId())
                .collect(Collectors.toSet());

            assertEquals(17, definitions.size());
            assertEquals(WIRE_IDS, wireIds);
            assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
                loader.getDiagnostics().toString());
        } finally {
            FlowDataType.unregisterExtensionType("request", "request:quest");
        }
    }
}
