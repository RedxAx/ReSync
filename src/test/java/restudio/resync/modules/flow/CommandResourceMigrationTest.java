package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.flow.validation.FlowGraphValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandResourceMigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void legacyCommandPathsMoveIntoCommandGraphOnce() {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
        FlowGraph command = new FlowGraph();
        command.setId("Name_Color");
        command.setResourceType("command");
        command.setNodes(new HashMap<>(Map.of("start", new FlowNode("event.resync.command", 0, 0, Map.of()))));
        storage.saveGraph(command);
        TriggerRegistry triggers = new TriggerRegistry(tempDir.resolve("triggers.json").toFile());
        triggers.setBindings(List.of(new TriggerBinding("Name_Color:command", "Name_Color", TriggerType.COMMAND,
            "{\"command\":\"name_color\",\"subcommands\":[\"<text:colorMap>\"],\"structured\":false}")));

        new FlowBlueprintPacketHandler(storage, triggers, null, null);
        FlowGraph migrated = storage.getGraph("command", "Name_Color");
        long revision = migrated.getResourceRevision();

        assertEquals("name_color", migrated.getNodes().get("start").getInputValues().get("command"));
        assertEquals(List.of("<text:colorMap>"), migrated.getNodes().get("start").getInputValues().get("subcommands"));
        assertTrue(Files.exists(tempDir.resolve("assets/.migrations/command-bindings-v1.json")));

        new FlowBlueprintPacketHandler(storage, triggers, null, null);

        assertEquals(revision, storage.getGraph("command", "Name_Color").getResourceRevision());
    }

    @Test
    void malformedLegacyCommandContextDoesNotAbortInitialization() {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
        FlowGraph command = new FlowGraph();
        command.setId("broken_command");
        command.setResourceType("command");
        command.setNodes(new HashMap<>(Map.of("start", new FlowNode("event.resync.command", 0, 0, Map.of()))));
        storage.saveGraph(command);
        TriggerRegistry triggers = new TriggerRegistry(tempDir.resolve("triggers.json").toFile());
        triggers.setBindings(List.of(new TriggerBinding("broken_command:command", "broken_command", TriggerType.COMMAND, "{\"command\":")));

        assertDoesNotThrow(() -> new FlowBlueprintPacketHandler(storage, triggers, null, null));
        assertNull(storage.getGraph("command", "broken_command").getNodes().get("start").getInputValues().get("command"));
    }

    @Test
    void invalidLegacyGraphDoesNotAbortInitializationOrMutateCachedGraph() throws IOException {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
        FlowGraph command = new FlowGraph();
        command.setId("legacy_command");
        command.setResourceType("command");
        command.setNodes(new HashMap<>(Map.of(
            "start", new FlowNode("event.resync.command", 0, 0, Map.of()),
            "legacy", new FlowNode("missing.legacy.node", 200, 0, Map.of())
        )));
        storage.saveGraph(command);
        TriggerRegistry triggers = new TriggerRegistry(tempDir.resolve("triggers.json").toFile());
        triggers.setBindings(List.of(new TriggerBinding("legacy_command:command", "legacy_command", TriggerType.COMMAND,
            "{\"command\":\"legacy\",\"subcommands\":[\"<text:colors>\"],\"structured\":true}")));
        storage.setGraphValidator(new FlowGraphValidator(new NodeDefinitionRegistry(), new HandlerRegistry(), new TypeAdapterRegistry(), new OptionCatalogRegistry()));

        assertDoesNotThrow(() -> new FlowBlueprintPacketHandler(storage, triggers, null, null));

        assertNull(storage.getGraph("command", "legacy_command").getNodes().get("start").getInputValues().get("command"));
        assertTrue(Files.readString(tempDir.resolve("assets/.migrations/command-bindings-v1.json")).contains("legacy_command"));
    }
}
