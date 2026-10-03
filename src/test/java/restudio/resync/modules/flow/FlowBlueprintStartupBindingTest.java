package restudio.resync.modules.flow;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import org.bukkit.event.block.BlockBreakEvent;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.text.ReTextService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowBlueprintStartupBindingTest {
    @TempDir
    Path tempDir;
    private final List<ReSyncJsonResourceStorage> jsonStorages = new ArrayList<>();
    private final List<AssetPersistenceGate> assetsGates = new ArrayList<>();
    private final List<AssetTransactionCoordinator> coordinators = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        try {
            for (ReSyncJsonResourceStorage storage : jsonStorages.reversed()) {
                storage.closePersistence();
            }
            for (AssetPersistenceGate assetsGate : assetsGates.reversed()) {
                assetsGate.quiesce();
            }
            for (AssetTransactionCoordinator coordinator : coordinators.reversed()) {
                coordinator.close();
            }
        } finally {
            jsonStorages.clear();
            assetsGates.clear();
            coordinators.clear();
            MockBukkit.unmock();
        }
    }

    @Test
    void commandResourcesRegisterFromTypedGraphsWithoutLegacyBindings() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = flowStorage(plugin);
        FlowGraph graph = new FlowGraph("gui", Map.of(
            "start", new FlowNode("event.resync.command", 0, 0, Map.of())
        ), List.of(), List.of());
        graph.setResourceType("command");
        storage.saveGraph(graph);
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        GlobalTriggers globalTriggers = new GlobalTriggers(storage,
            new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of()), triggers,
            new ReTextService(jsonStorage(plugin)));

        blueprintHandler(storage, triggers, globalTriggers);

        List<TriggerBinding> bindings = new TriggerRegistry(plugin).getBindings(TriggerType.COMMAND);
        assertEquals(0, bindings.size());
        assertNotNull(Bukkit.getCommandMap().getCommand("gui"));
    }

    @Test
    void runtimeStartupDoesNotMigrateLegacyCommandRows() {
        MockBukkit.mock();
        FlowStorage storage = flowStorage(tempDir);
        FlowGraph graph = new FlowGraph("runtime-command", Map.of(
            "start", new FlowNode("event.resync.command", 0, 0, Map.of())
        ), List.of(), List.of());
        graph.setResourceType("command");
        storage.saveGraph(graph);
        long revision = storage.getGraph("command", "runtime-command").getResourceRevision();
        TriggerRegistry triggers = new TriggerRegistry(tempDir.resolve("triggers.json").toFile());
        triggers.setBindings(List.of(new TriggerBinding("runtime-command:command", "runtime-command",
            TriggerType.COMMAND, "{\"command\":\"runtime\"}")));
        blueprintHandler(storage, triggers, null);

        FlowGraph unchanged = storage.getGraph("command", "runtime-command");
        assertEquals(revision, unchanged.getResourceRevision());
        assertNull(unchanged.getNodes().get("start").getInputValues().get("command"));
        assertTrue(Files.notExists(tempDir.resolve("assets/.migrations/command-bindings-v1.json")));
    }

    @Test
    void disabledCommandRejectsAStaleRegisteredCommandBeforeRefresh() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = flowStorage(plugin);
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
            new ReTextService(jsonStorage(plugin)));
        blueprintHandler(storage, triggers, globalTriggers);
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

    @Test
    void typedCommandGraphSaveUpdateAndDeleteReplaceRuntimeAuthority() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = flowStorage(plugin);
        FlowGraph graph = commandGraph("typed", "first");
        storage.saveGraph(graph);
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        GlobalTriggers globalTriggers = new GlobalTriggers(storage,
            new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of()), triggers,
            new ReTextService(jsonStorage(plugin)));
        blueprintHandler(storage, triggers, globalTriggers);

        assertNotNull(Bukkit.getCommandMap().getCommand("first"));
        FlowGraph updated = storage.getGraph("command", "typed");
        updated.getNodes().get("start").getInputValues().put("command", "second");
        storage.saveGraph(updated);
        assertNull(Bukkit.getCommandMap().getCommand("first"));
        assertNotNull(Bukkit.getCommandMap().getCommand("second"));

        storage.deleteGraph("command", "typed");
        assertNull(Bukkit.getCommandMap().getCommand("second"));
    }

    @Test
    void legacyCommandRowsCannotRegisterOrOverrideTypedCommands() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = flowStorage(plugin);
        storage.saveGraph(commandGraph("typed", "typed"));
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        triggers.setBindings(List.of(new TriggerBinding("legacy:command", "typed", TriggerType.COMMAND, "legacy")));
        new GlobalTriggers(storage,
            new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of()), triggers,
            new ReTextService(jsonStorage(plugin)));

        assertNotNull(Bukkit.getCommandMap().getCommand("typed"));
        assertNull(Bukkit.getCommandMap().getCommand("legacy"));
    }

    @Test
    void commandRefreshDoesNotRemoveEventOrSystemBindings() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = flowStorage(plugin);
        storage.saveGraph(commandGraph("typed", "typed"));
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        triggers.setBindings(List.of(
            new TriggerBinding("event-flow:event", "event-flow", TriggerType.EVENT, "player_join"),
            new TriggerBinding("system-flow:system", "system-flow", TriggerType.SYSTEM, "server_start")
        ));
        GlobalTriggers globalTriggers = new GlobalTriggers(storage,
            new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of()), triggers,
            new ReTextService(jsonStorage(plugin)));
        FlowBlueprintPacketHandler handler = blueprintHandler(storage, triggers, globalTriggers);

        handler.refreshGraphBinding("command", "typed", false);

        TriggerRegistry reloaded = new TriggerRegistry(plugin);
        assertEquals(1, reloaded.getBindings(TriggerType.EVENT).size());
        assertEquals(1, reloaded.getBindings(TriggerType.SYSTEM).size());
    }

    @Test
    void deferredRuntimeDoesNotRegisterCommandsThroughBlueprintConstruction() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = flowStorage(plugin);
        storage.saveGraph(commandGraph("typed", "typed"));
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        GlobalTriggers globalTriggers = new GlobalTriggers(storage,
            new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of()), triggers,
            new ReTextService(jsonStorage(plugin)), false);

        blueprintHandler(storage, triggers, globalTriggers);

        assertNull(Bukkit.getCommandMap().getCommand("typed"));
        globalTriggers.activateRuntimeBindings();
        assertNotNull(Bukkit.getCommandMap().getCommand("typed"));
    }

    @Test
    void commandRefreshNeverRemovesOrReclaimsAnExternalCommand() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = flowStorage(plugin);
        storage.saveGraph(commandGraph("typed", "typed"));
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        GlobalTriggers globalTriggers = new GlobalTriggers(storage,
            new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of()), triggers,
            new ReTextService(jsonStorage(plugin)));
        Command external = new Command("typed") {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return true;
            }
        };
        Bukkit.getCommandMap().getKnownCommands().put("typed", external);

        globalTriggers.refreshBindings();

        assertEquals(external, Bukkit.getCommandMap().getCommand("typed"));
    }

    @Test
    void externalCommandDeletionReloadRemovesTheRuntimeRegistration() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = flowStorage(plugin);
        storage.saveGraph(commandGraph("typed", "typed"));
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        new GlobalTriggers(storage,
            new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of()), triggers,
            new ReTextService(jsonStorage(plugin)));
        assertNotNull(Bukkit.getCommandMap().getCommand("typed"));
        Path commandFile;
        try (var files = Files.walk(storage.getAssetsPath())) {
            commandFile = files.filter(path -> path.getFileName().toString().equals("typed.json")).findFirst().orElseThrow();
        }
        Files.delete(commandFile);

        storage.reloadGraph("command", "typed");

        assertNull(Bukkit.getCommandMap().getCommand("typed"));
    }

    @Test
    void tamperedCommandReloadKeepsTheLastValidRuntimeRegistration() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage storage = flowStorage(plugin);
        storage.saveGraph(commandGraph("typed", "typed"));
        TriggerRegistry triggers = new TriggerRegistry(plugin);
        new GlobalTriggers(storage,
            new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of()), triggers,
            new ReTextService(jsonStorage(plugin)));
        Path commandFile;
        try (var files = Files.walk(storage.getAssetsPath())) {
            commandFile = files.filter(path -> path.getFileName().toString().equals("typed.json")).findFirst().orElseThrow();
        }
        Files.writeString(commandFile, Files.readString(commandFile).replace("\"command\":\"typed\"", "\"command\":\"tampered\""));

        assertThrows(IllegalStateException.class, () -> storage.reloadGraph("command", "typed"));

        assertNotNull(Bukkit.getCommandMap().getCommand("typed"));
        assertNull(Bukkit.getCommandMap().getCommand("tampered"));
    }

    private ReSyncJsonResourceStorage jsonStorage(JavaPlugin plugin) {
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        AssetPersistenceGate assetsGate = new AssetPersistenceGate(scope);
        try {
            AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(scope.resolve("assets"), new Gson());
            assetsGates.add(assetsGate);
            coordinators.add(coordinator);
            ReSyncJsonResourceStorage storage = new ReSyncJsonResourceStorage(
                plugin, LegacyRuntimeActivationGate.runtime(scope), assetsGate, coordinator);
            jsonStorages.add(storage);
            return storage;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open flow blueprint test persistence", exception);
        }
    }

    @Test
    void canonicalBlockBreakCreatesAndRemovesOnlyItsOwnedFlowBinding() {
        MockBukkit.mock();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register("resync", new NodeDefinition.Builder("event.block.break", "Block Break", NodeDefinition.NodeCategory.EVENT)
            .owner("restudio.resync").trigger(true).eventType(BlockBreakEvent.class.getName()).build());
        FlowStorage storage = flowStorage(tempDir);
        FlowGraph flow = new FlowGraph("break-flow", Map.of(
            "first", new FlowNode("unrelated", 0, 0, Map.of()),
            "break", new FlowNode("restudio.resync/event.block.break", 0, 0, Map.of()),
            "foreign", new FlowNode("foreign.owner/event.block.break", 0, 0, Map.of())), List.of(), List.of());
        flow.setResourceType("flow");
        storage.saveGraph(flow);
        TriggerRegistry triggers = new TriggerRegistry(tempDir.resolve("triggers.json").toFile());
        triggers.setBindings(List.of(new TriggerBinding("system", "break-flow", TriggerType.SYSTEM, "server_start")));
        FlowBlueprintPacketHandler handler = new FlowBlueprintPacketHandler(storage, triggers, null, definitions, null,
            null, AuthorityEpoch.fixed(1L), false);
        handler.refreshGraphBinding("flow", "break-flow", false);
        List<TriggerBinding> bindings = triggers.getBindings(TriggerType.EVENT);
        assertEquals(1, bindings.size());
        assertEquals("restudio.resync/event.block.break", bindings.getFirst().getContext());
        assertEquals("break-flow", bindings.getFirst().getFlowId());
        handler.refreshGraphBinding("flow", "break-flow", true);
        assertTrue(triggers.getBindings(TriggerType.EVENT).isEmpty());
        assertEquals(1, triggers.getBindings(TriggerType.SYSTEM).size());
    }

    @Test
    void typedEventBindingsAndFunctionNotificationsDoNotProjectStaticCallsToLegacyGraphs() {
        MockBukkit.mock();
        ServerId server = ServerId.of(UUID.randomUUID());
        FlowStorage storage = flowStorage(tempDir, server);
        OwnerId owner = OwnerId.of("restudio.resync");
        ServerResourceLocator child = new ServerResourceLocator(server, ContractRef.of(owner, ResourceTypeId.of("function")), "child");
        FunctionBinding call = new FunctionBinding(child, 1, List.of(), List.of());
        ContentHash hash = ContentHash.of("1".repeat(64));
        CatalogBinding catalog = new CatalogBinding(1, hash, hash);
        ServerResourceLocator flow = new ServerResourceLocator(server, ContractRef.of(owner, ResourceTypeId.of("flow")), "typed-event");
        GraphNode event = new GraphNode(NodeInstanceId.of(UUID.randomUUID()), ContractRef.of(owner, NodeId.of("event.block.break")), 1, Map.of());
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), flow, 1, catalog, Set.of(), List.of(event), List.of(), List.of(call), OpaqueData.empty());
        storage.saveCoreGraph(graph, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        ServerResourceLocator function = new ServerResourceLocator(server, child.type(), "parent");
        GraphDocument body = new GraphDocument(new CatalogVersion(1, 0), function, 1, catalog, Set.of(), List.of(), List.of(), List.of(call), OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(function), FunctionRevision.of(1), List.of(), List.of());
        storage.saveCoreGraph(new FunctionSourceDocument(signature, body), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        assertThrows(IllegalStateException.class, () -> storage.getGraph("flow", "typed-event"));
        assertThrows(IllegalStateException.class, () -> storage.getGraph("function", "parent"));
        TriggerRegistry triggers = new TriggerRegistry(tempDir.resolve("triggers.json").toFile());
        triggers.setBindings(List.of(new TriggerBinding("system", "typed-event", TriggerType.SYSTEM, "server_start")));
        FlowBlueprintPacketHandler handler = new FlowBlueprintPacketHandler(storage, triggers, null, new NodeDefinitionRegistry(false), null,
            null, AuthorityEpoch.fixed(1L), false);

        handler.refreshAllGraphBindings();
        handler.refreshGraphBinding("function", "parent", false);
        assertEquals(1, triggers.getBindings(TriggerType.EVENT).size());
        assertEquals("block_break", triggers.getBindings(TriggerType.EVENT).getFirst().getContext());
        GraphDocument inactive = new GraphDocument(new CatalogVersion(1, 0), flow, 2, catalog, Set.of(), List.of(event), List.of(), List.of(call), OpaqueData.empty());
        storage.saveCoreGraph(inactive, ResourceActivationState.INACTIVE, UUID.randomUUID(), 1);
        handler.refreshGraphBinding("flow", "typed-event", false);
        assertTrue(triggers.getBindings(TriggerType.EVENT).isEmpty());
        assertEquals(1, triggers.getBindings(TriggerType.SYSTEM).size());
    }

    private FlowStorage flowStorage(JavaPlugin plugin) {
        return flowStorage(plugin.getDataFolder().toPath().toAbsolutePath().normalize());
    }

    private FlowStorage flowStorage(Path scope) {
        return flowStorage(scope, null);
    }

    private FlowStorage flowStorage(Path scope, ServerId serverId) {
        AssetPersistenceGate assetsGate = new AssetPersistenceGate(scope);
        try {
            AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(scope.resolve("assets"), new Gson());
            assetsGates.add(assetsGate);
            coordinators.add(coordinator);
            return new FlowStorage(scope.toFile(), LegacyRuntimeActivationGate.runtime(scope), assetsGate, serverId, coordinator);
        } catch (IOException exception) {
            assetsGate.quiesce();
            throw new IllegalStateException("Failed to open flow blueprint test persistence", exception);
        }
    }

    private FlowGraph commandGraph(String id, String command) {
        FlowGraph graph = new FlowGraph(id, Map.of(
            "start", new FlowNode("event.resync.command", 0, 0, Map.of("command", command))
        ), List.of(), List.of());
        graph.setResourceType("command");
        return graph;
    }

    private FlowBlueprintPacketHandler blueprintHandler(FlowStorage storage, TriggerRegistry triggers,
                                                        GlobalTriggers globalTriggers) {
        return new FlowBlueprintPacketHandler(storage, triggers, globalTriggers, NodeDefinitionRegistry.getInstance(), null,
            null, AuthorityEpoch.fixed(1L), false);
    }
}
