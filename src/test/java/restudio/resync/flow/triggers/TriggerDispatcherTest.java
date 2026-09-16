package restudio.resync.flow.triggers;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerDispatcherTest {
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        TemporaryLifecycleDiagnostics.close();
        MockBukkit.unmock();
    }

    @Test
    void duplicateNormalizedEventDefinitionKeepsSingleDispatcherEntry() {
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin);

        dispatcher.registerDefinition("block_break", "event:block_break", BlockBreakEvent.class, EventPriority.NORMAL, false, emptyVariables(), event -> null, new String[0]);
        dispatcher.registerDefinition("event.block.break", "event.block.break", BlockBreakEvent.class, EventPriority.HIGHEST, false, emptyVariables(), event -> null, new String[0]);

        assertEquals(1, dispatcher.registeredEntryCount());
        assertTrue(dispatcher.hasEventType("block_break"));
        assertTrue(dispatcher.hasEventType("event.block.break"));
    }

    @Test
    void sameBukkitEventClassCanBackDifferentFlowEvents() {
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin);

        dispatcher.registerDefinition("entity_damage", "event:player_damage", EntityDamageEvent.class, EventPriority.NORMAL, false, emptyVariables(), event -> null, new String[0]);
        dispatcher.registerDefinition("entity_damaged", "event:entity_damaged", EntityDamageEvent.class, EventPriority.NORMAL, false, emptyVariables(), event -> null, new String[0]);

        assertEquals(2, dispatcher.registeredEntryCount());
        assertTrue(dispatcher.hasEventType("entity_damage"));
        assertTrue(dispatcher.hasEventType("entity_damaged"));
    }

    @Test
    void shutdownRemovesManagedDefinitionsAndListener() {
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin);
        dispatcher.registerDefinition("block_break", "event:block_break", BlockBreakEvent.class, EventPriority.NORMAL, false,
            emptyVariables(), event -> null, new String[0]);

        dispatcher.shutdown();

        assertEquals(0, dispatcher.registeredEntryCount());
        assertTrue(dispatcher.getEventTypes().isEmpty());
        assertFalse(dispatcher.hasEventType("block_break"));
    }

    @Test
    void disabledPluginCannotReopenManagedEventDefinitions() {
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin);
        dispatcher.registerDefinition("block_break", "event:block_break", BlockBreakEvent.class, EventPriority.NORMAL,
            false, emptyVariables(), event -> null, new String[0]);
        MockBukkit.getMock().getPluginManager().disablePlugin(plugin);

        assertDoesNotThrow(() -> dispatcher.replaceManagedDefinitions(Set.of("event:block_break"), List.of(
            new TriggerDispatcher.ManagedDefinition("entity_damage", "event:entity_damage", EntityDamageEvent.class,
                EventPriority.NORMAL, false, emptyVariables(), event -> null, new String[0]))));

        assertEquals(0, dispatcher.registeredEntryCount());
        assertTrue(dispatcher.getEventTypes().isEmpty());
    }

    @Test
    void closedDefinitionAdmissionRejectsLateActivationProjection() {
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin);
        dispatcher.registerDefinition("block_break", "event:block_break", BlockBreakEvent.class, EventPriority.NORMAL,
            false, emptyVariables(), event -> null, new String[0]);

        dispatcher.closeDefinitionAdmission();
        dispatcher.replaceManagedDefinitions(Set.of("event:block_break"), List.of(
            new TriggerDispatcher.ManagedDefinition("entity_damage", "event:entity_damage", EntityDamageEvent.class,
                EventPriority.NORMAL, false, emptyVariables(), event -> null, new String[0])));

        assertEquals(0, dispatcher.registeredEntryCount());
        assertTrue(dispatcher.getEventTypes().isEmpty());
    }

    @Test
    void extractorFailureProducesOneCorrelatedTerminalForEveryMatchedBinding() throws Exception {
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin);
        dispatcher.registerDefinition("test_event", "event:test", TestEvent.class, EventPriority.NORMAL, false,
            event -> { throw new IllegalStateException("failed"); }, event -> null, new String[0]);
        dispatcher.registerBinding("test_event", "flow", "start");
        CapturingDiagnosticSink diagnostics = new CapturingDiagnosticSink();
        bindDiagnostics(diagnostics);

        Field entriesField = TriggerDispatcher.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        Object entry = ((Iterable<?>) entriesField.get(dispatcher)).iterator().next();
        Method dispatch = Arrays.stream(TriggerDispatcher.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("dispatch") && method.getParameterCount() == 2)
            .findFirst().orElseThrow();
        dispatch.setAccessible(true);
        dispatch.invoke(dispatcher, entry, new TestEvent());

        List<DiagnosticEvent> chain = diagnostics.events.stream()
            .filter(event -> event.stage().startsWith("trigger_"))
            .toList();
        assertEquals(1L, chain.stream().filter(event -> event.stage().equals("trigger_ingress")).count());
        assertEquals(1L, chain.stream().filter(event -> event.stage().equals("trigger_execution_terminal")).count());
        assertEquals(1, chain.stream().map(event -> event.identity().correlationId()).distinct().count());
        assertEquals("TRIGGER.CONTEXT_REJECTED", chain.stream()
            .filter(event -> event.stage().equals("trigger_execution_terminal"))
            .findFirst().orElseThrow().value("diagnosticCode").toJava());
    }

    private Function<Event, Map<String, Object>> emptyVariables() {
        return event -> Map.of();
    }

    @Test
    void canonicalDefinitionBindingsKeepOwnerIdentityAndAreRemovedWithTheirDefinition() {
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin);
        String reference = "restudio.resync/event.block.break";
        dispatcher.replaceManagedDefinitions(Set.of(), List.of(new TriggerDispatcher.ManagedDefinition(
            reference, reference, BlockBreakEvent.class, EventPriority.HIGHEST, false, emptyVariables(), event -> null, new String[0])));
        assertTrue(dispatcher.hasEventType(reference));
        assertEquals(reference, dispatcher.getNodeType(reference));
        assertFalse(dispatcher.hasEventType("foreign.owner/event.block.break"));
        assertFalse(dispatcher.hasEventType("restudio_resync/event_block_break"));
        dispatcher.replaceManagedDefinitions(Set.of(reference), List.of());
        assertFalse(dispatcher.hasEventType(reference));
        assertEquals(0, dispatcher.registeredEntryCount());
    }

    private static void bindDiagnostics(DiagnosticSink sink) throws Exception {
        Method bind = TemporaryLifecycleDiagnostics.class.getDeclaredMethod("bind", DiagnosticSink.class);
        bind.setAccessible(true);
        bind.invoke(null, sink);
    }

    private static final class TestEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    private static final class CapturingDiagnosticSink implements DiagnosticSink {
        private final List<DiagnosticEvent> events = new ArrayList<>();

        @Override
        public Status status() {
            return Status.ready(Mode.VERBOSE);
        }

        @Override
        public Offer offer(DiagnosticEvent event) {
            events.add(event);
            return Offer.ACCEPTED;
        }

        @Override
        public Status pause() {
            return status();
        }

        @Override
        public Status resume() {
            return status();
        }

        @Override
        public Flush flush() {
            return events.isEmpty() ? Flush.EMPTY : Flush.FLUSHED;
        }

        @Override
        public void close() {
        }
    }
}
