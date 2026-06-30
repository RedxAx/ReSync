package restudio.resync.flow.triggers;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private Function<Event, Map<String, Object>> emptyVariables() {
        return event -> Map.of();
    }
}
