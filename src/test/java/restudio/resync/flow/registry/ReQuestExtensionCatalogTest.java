package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReQuestExtensionCatalogTest {
    private static final Path CATALOG = Path.of("examples", "request-extension", "src", "main", "resources", "request", "nodes");
    private static final Set<String> IDS = Set.of(
        "quest_info", "find_quest", "quest_list", "quest_profile", "create_quest", "quest_action",
        "quest_progress", "add_quest_xp", "quest_event_listen", "delete_quest", "can_start_quest",
        "start_quest", "add_progress", "set_progress", "complete_quest", "quit_quest", "reset_quest"
    );
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
        Map.entry("quest_info", "info"),
        Map.entry("find_quest", "find"),
        Map.entry("quest_list", "list"),
        Map.entry("quest_profile", "profile"),
        Map.entry("create_quest", "create"),
        Map.entry("quest_action", "action"),
        Map.entry("quest_progress", "progress_family"),
        Map.entry("add_quest_xp", "xp"),
        Map.entry("quest_event_listen", "listen"),
        Map.entry("delete_quest", "delete"),
        Map.entry("can_start_quest", "can_start"),
        Map.entry("start_quest", "start"),
        Map.entry("add_progress", "progress"),
        Map.entry("set_progress", "set_progress"),
        Map.entry("complete_quest", "complete"),
        Map.entry("quit_quest", "quit"),
        Map.entry("reset_quest", "reset")
    );

    @Test
    void strictExtensionCatalogLoadsEverySupportedQuestOperation() {
        FlowDataType.registerExtensionType("request", new FlowDataType("request:quest", FlowDataType.STRING, String.class, null, 0x46B48A));
        try {
            NodeDefinitionLoader loader = new NodeDefinitionLoader();
            List<NodeDefinition> definitions = loader.loadReplacementFromDirectory(CATALOG);

            assertEquals(17, definitions.size());
            assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).collect(Collectors.toSet()));
            assertEquals(OPERATIONS, definitions.stream().collect(Collectors.toMap(NodeDefinition::getId,
                value -> String.valueOf(value.getHandlerConfig().get("operation")))));
            assertTrue(definitions.stream().allMatch(value -> "request".equals(value.getOwner())));
            assertTrue(definitions.stream().allMatch(value -> value.getAuthoredMetadata() != null));
            assertTrue(definitions.stream().allMatch(value -> value.getId().equals(value.getAuthoredMetadata().id())));
            assertTrue(definitions.stream().allMatch(value -> value.getSchemaVersion() == 2));
            assertTrue(definitions.stream().allMatch(value -> value.getInputs().stream().allMatch(pin -> !pin.getDescription().isBlank())));
            assertTrue(definitions.stream().allMatch(value -> value.getOutputs().stream().allMatch(pin -> !pin.getDescription().isBlank())));
            assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
                loader.getDiagnostics().toString());
        } finally {
            FlowDataType.unregisterExtensionType("request", "request:quest");
        }
    }
}
