package restudio.resync.flow;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.diagnostics.BoundedDiagnosticDeduplicator;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.triggers.TriggerDispatcher;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandInvocationParityTest {
    private Plugin plugin;

    @BeforeEach
    void setup() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void cleanup() {
        MockBukkit.unmock();
    }

    @Test
    void zeroArgumentsPreservePlayerAndConsoleIdentity() {
        verify(MockBukkit.getMock().addPlayer(), List.of(), false);
        verify(MockBukkit.getMock().getConsoleSender(), List.of(), true);
    }

    @Test
    void aliasAndMultipleArgumentsPreserveOriginalTokenBoundaries() {
        verify(MockBukkit.getMock().addPlayer(), List.of("one", "two words", ""), false);
        verify(MockBukkit.getMock().getConsoleSender(), List.of("one", "two words", ""), true);
    }

    @Test
    void exactDerivedMappingsPreservePlayerAndConsoleValuesWithoutGetterFailures() throws ReflectiveOperationException {
        List<NodeDefinition.PinMapping> mappings = List.of("event.bound_command", "event.command_label", "event.args",
                "event.args_list", "event.args_count", "event.is_console").stream()
            .map(source -> new NodeDefinition.PinMapping(source, source)).toList();
        var player = MockBukkit.getMock().addPlayer();
        var console = MockBukkit.getMock().getConsoleSender();
        Extraction playerExtraction = extraction("event.command", PlayerCommandPreprocessEvent.class, mappings);
        Extraction consoleExtraction = extraction("event.server.command", ServerCommandEvent.class, mappings);
        for (List<String> arguments : List.of(List.<String>of(), List.of("one", "two"))) {
            String command = "alias" + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments));
            PlayerCommandPreprocessEvent playerEvent = new PlayerCommandPreprocessEvent(player, "/" + command);
            ServerCommandEvent consoleEvent = new ServerCommandEvent(console, command);
            playerEvent.setCancelled(true);
            consoleEvent.setCancelled(true);
            Map<String, Object> playerVariables = playerExtraction.extractor().apply(playerEvent);
            Map<String, Object> consoleVariables = consoleExtraction.extractor().apply(consoleEvent);
            assertEquals(GlobalTriggers.commandEventVariables(player, "/" + command, true), playerVariables);
            assertEquals(GlobalTriggers.commandEventVariables(console, command, true), consoleVariables);
            assertEquals(arguments, playerVariables.get("event.args_list"));
            assertEquals(arguments.size(), consoleVariables.get("event.args_count"));
        }
        assertEquals(0, playerExtraction.mappingFailures());
        assertEquals(0, consoleExtraction.mappingFailures());
    }

    @Test
    void populatedTargetsDoNotHideAliasedUnknownOrUnseededMappingFailures() throws ReflectiveOperationException {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(MockBukkit.getMock().addPlayer(), "/alias one two");
        Extraction malformed = extraction("event.command", PlayerCommandPreprocessEvent.class, List.of(
            new NodeDefinition.PinMapping("event.args", "event.args_count"),
            new NodeDefinition.PinMapping("event.no_such_getter", "event.command_label")));
        Map<String, Object> variables = malformed.extractor().apply(event);
        assertEquals(2, variables.get("event.args_count"));
        assertEquals("alias", variables.get("event.command_label"));
        assertEquals(2, malformed.mappingFailures());
        Extraction unseeded = extraction("event.other", PlayerCommandPreprocessEvent.class,
            List.of(new NodeDefinition.PinMapping("event.args_count", "event.args_count")));
        assertTrue(unseeded.extractor().apply(event).isEmpty());
        assertEquals(1, unseeded.mappingFailures());
    }

    @Test
    void localAndOwnedWireEventSourcesShareOneStrictCanonicalIdentity() {
        OwnerId alpha = OwnerId.of("alpha");
        assertEquals(NodeDefinition.sourceReference(alpha, "event"), NodeDefinition.sourceReference(alpha, "alpha:event"));
        assertEquals("alpha/event", NodeDefinition.sourceReference(alpha, "alpha:event").canonicalText());
        assertNotEquals(NodeDefinition.sourceReference(alpha, "event"), NodeDefinition.sourceReference(OwnerId.of("beta"), "event"));
        assertEquals("alpha/event", FlowEventRegistry.bindingContext(extensionEvent("alpha", "event")));
        assertEquals("alpha/event", FlowEventRegistry.bindingContext(extensionEvent("alpha", "alpha:event")));
        for (String invalid : List.of("beta:event", "beta:", "alpha:bad:id", "alpha/event", "", " ", "alpha:", "alpha:   ")) {
            assertThrows(IllegalArgumentException.class, () -> NodeDefinition.sourceReference(alpha, invalid), invalid);
            assertThrows(IllegalArgumentException.class, () -> FlowEventRegistry.bindingContext(extensionEvent("alpha", invalid)), invalid);
        }
    }

    @Test
    void registeredExtensionEventsKeepOwnerCollisionsAndRemovalIsolated() {
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin);
        FlowEventRegistry registry = new FlowEventRegistry(dispatcher);
        try {
            registry.registerFromJson(List.of(extensionEvent("alpha", "alpha:event"), extensionEvent("beta", "beta:event")));
            assertEquals(Set.of("alpha/event", "beta/event"), dispatcher.getEventTypes());
            assertEquals("alpha/event", dispatcher.getNodeType("alpha/event"));
            assertEquals("beta/event", dispatcher.getNodeType("beta/event"));
            assertFalse(dispatcher.hasEventType("foreign/event"));
            assertEquals(Set.of("alpha:event", "beta:event"), registry.getEventDefinitions().keySet());
            assertThrows(IllegalArgumentException.class,
                () -> registry.replaceDefinitions(List.of(extensionEvent("alpha", "beta:event"))));
            assertEquals(Set.of("alpha/event", "beta/event"), dispatcher.getEventTypes());
            registry.unregisterOwner("alpha");
            assertFalse(dispatcher.hasEventType("alpha/event"));
            assertTrue(dispatcher.hasEventType("beta/event"));
            assertEquals(Set.of("beta:event"), registry.getEventDefinitions().keySet());
            registry.unregisterOwner("beta");
            assertTrue(dispatcher.getEventTypes().isEmpty());
            assertTrue(registry.getEventDefinitions().isEmpty());
        } finally {
            dispatcher.shutdown();
        }
    }

    private NodeDefinition extensionEvent(String owner, String id) {
        return new NodeDefinition.Builder(id, "Extension Event", NodeDefinition.NodeCategory.EVENT)
            .owner(owner).trigger(true).eventType(PlayerCommandPreprocessEvent.class.getName()).build();
    }

    private Extraction extraction(String id, Class<? extends Event> eventClass, List<NodeDefinition.PinMapping> mappings) {
        AtomicReference<Function<Event, Map<String, Object>>> extractor = new AtomicReference<>();
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin) {
            @Override
            public void registerDefinition(String eventType, String nodeType, Class<? extends Event> registeredClass,
                                           EventPriority priority, boolean ignoreCancelled,
                                           Function<Event, Map<String, Object>> variableExtractor,
                                           Function<Event, Player> playerExtractor, String[] aliases) {
                super.registerDefinition(eventType, nodeType, registeredClass, priority, ignoreCancelled,
                    variableExtractor, playerExtractor, aliases);
                assertEquals("restudio.resync/" + id, eventType);
                assertEquals(eventType, nodeType);
                assertEquals(eventClass, registeredClass);
                assertTrue(hasEventType(eventType));
                extractor.set(variableExtractor);
            }
        };
        FlowEventRegistry registry = new FlowEventRegistry(dispatcher);
        NodeDefinition definition = new NodeDefinition.Builder(id, "Command", NodeDefinition.NodeCategory.EVENT)
            .owner("restudio.resync").trigger(true).eventType(eventClass.getName()).outputMappings(mappings).build();
        registry.registerFromJson(List.of(definition));
        assertNotNull(extractor.get());
        return new Extraction(registry, extractor.get());
    }

    private record Extraction(FlowEventRegistry registry, Function<Event, Map<String, Object>> extractor) {
        private int mappingFailures() throws ReflectiveOperationException {
            Field field = FlowEventRegistry.class.getDeclaredField("reportedMappingFailures");
            field.setAccessible(true);
            return ((BoundedDiagnosticDeduplicator) field.get(registry)).size();
        }
    }

    private void verify(CommandSender sender, List<String> arguments, boolean console) {
        Map<String, Object> variables = GlobalTriggers.commandVariables(sender, "primary", "alias", arguments);
        assertEquals("primary", variables.get("event.bound_command"));
        assertEquals("alias", variables.get("event.command_label"));
        assertEquals(arguments, variables.get("event.args_list"));
        assertEquals(arguments.size(), variables.get("event.args_count"));
        assertEquals(String.join(" ", arguments), variables.get("event.args"));
        assertEquals(console, variables.get("event.is_console"));
        var player = console ? null : MockBukkit.getMock().getPlayer(sender.getName());
        var adapted = CompiledRuntimeContextAdapter.adapt(ServerId.deterministic("command-parity"), player, null, variables);
        assertTrue(adapted.accepted(), adapted.failure());
        assertEquals(arguments.size(), ((Number) adapted.context().variables().get("event.args_count").value()).intValue());
        assertEquals(arguments, adapted.context().variables().get("event.args_list").value());
        if (console) {
            assertNull(adapted.context().player());
        } else {
            assertEquals(player.getUniqueId(), adapted.context().player().uniqueId());
        }
    }
}
