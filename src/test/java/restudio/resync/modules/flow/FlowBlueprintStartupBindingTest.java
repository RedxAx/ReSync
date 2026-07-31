package restudio.resync.modules.flow;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        graph.setResourceType("command");
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

    @Test
    void disabledCommandRejectsAStaleRegisteredCommandBeforeRefresh() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = new FlowStorage(plugin);
        AtomicInteger executions = new AtomicInteger();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("event.resync.command", (context, node) -> {
        });
        handlers.register("count", (context, node) -> executions.incrementAndGet());
        FlowGraph graph = new FlowGraph("hello", Map.of(
            "start", new FlowNode("event.resync.command", 0, 0, Map.of("command", "hello")),
            "count", new FlowNode("count", 0, 0, Map.of())
        ), List.of(new FlowConnection("start", "flow", "count", "flow")), List.of());
        graph.setResourceType("command");
        storage.saveGraph(graph);
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        executor.setExecutionAuthority(storage::isExecutionAuthorized);
        GlobalTriggers globalTriggers = new GlobalTriggers(storage, executor, triggers,
            new ReTextService(new ReSyncJsonResourceStorage(plugin)));
        new FlowBlueprintPacketHandler(storage, triggers, globalTriggers, null);
        Command staleCommand = Bukkit.getCommandMap().getCommand("hello");
        assertNotNull(staleCommand);

        FlowGraph authoritative = storage.getGraph("command", "hello");
        authoritative.setEnabled(false);
        storage.saveGraph(authoritative);

        assertFalse(staleCommand.execute(Bukkit.getConsoleSender(), "hello", new String[0]));
        assertEquals(0, executions.get());
        globalTriggers.refreshBindings();
        assertNull(Bukkit.getCommandMap().getCommand("hello"));
    }
}
