package restudio.resync.modules.flow;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.text.ReTextService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FlowBlueprintStartupBindingTest {
    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void commandResourcesRebuildMissingStartupBindingsFromTheirIdentity() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = new FlowStorage(plugin);
        FlowGraph graph = new FlowGraph("gui", Map.of(
            "start", new FlowNode("event.resync.command", 0, 0, Map.of())
        ), List.of(), List.of());
        storage.saveGraph(graph);
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        GlobalTriggers globalTriggers = new GlobalTriggers(storage,
            new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of()), triggers,
            new ReTextService(new ReSyncJsonResourceStorage(plugin)));

        new FlowBlueprintPacketHandler(storage, triggers, globalTriggers, null);

        List<TriggerBinding> bindings = new TriggerRegistry(plugin).getBindings(TriggerType.COMMAND);
        assertEquals(1, bindings.size());
        assertEquals("gui", bindings.getFirst().getFlowId());
        assertEquals("gui", bindings.getFirst().getContext());
        assertNotNull(Bukkit.getCommandMap().getCommand("gui"));
    }
}
