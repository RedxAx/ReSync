package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.flow.triggers.TriggerDefinitions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowCommandRuntimeBoundaryTest {
    @Test
    void preprocessCommandsExposeArgumentsWithoutInventingABoundCommand() {
        var server = MockBukkit.mock();
        try {
            var player = server.addPlayer();
            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/alias one two");
            Map<String, Object> variables = new HashMap<>();
            new TriggerDefinitions().onPlayerCommand(event, variables);
            assertEquals("/alias one two", variables.get("event.command"));
            assertEquals("alias", variables.get("event.command_label"));
            assertEquals("", variables.get("event.bound_command"));
            assertEquals(List.of("one", "two"), variables.get("event.args_list"));
            assertEquals(2, variables.get("event.args_count"));
            assertEquals(false, variables.get("event.is_console"));
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void handlerlessTriggersProjectDeclaredOutputsFromTypedContext() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));

        assertTrue(source.contains("compiledTriggerOutputs(definition, invocation.runtimeContext())"));
        assertTrue(source.contains("runtimeVariable(context.variables(), runtimeName, pinName)"));
        assertFalse(source.contains("legacyRuntimeResult(requirement, definition, Map.of(), flowOutputAliases(definition))"));
    }
}
