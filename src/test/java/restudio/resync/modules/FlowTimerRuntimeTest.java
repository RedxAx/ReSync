package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.GraphCompiler;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.ServerHandler;
import restudio.resync.flow.handler.generic.TimerHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.FlowRuntimeExecutionBoundary;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeSecurityBoundary;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowTimerRuntimeTest {
    private static final ServerId SERVER = CanonicalProjectMetadataFixture.serverId();
    private static final NodeInstanceId TIMER_NODE = NodeInstanceId.deterministic("compiled-timer-runtime-node");
    private static final Map<String, NodeInstanceId> BROADCAST_NODES = Map.of(
        "active", NodeInstanceId.deterministic("compiled-timer-active-broadcast"),
        "paused", NodeInstanceId.deterministic("compiled-timer-paused-broadcast"),
        "inactive", NodeInstanceId.deterministic("compiled-timer-inactive-broadcast")
    );
    private static final PinId TIMER = PinId.of("timer");
    private static final PinId ACTION = PinId.of("action");
    private static final PinId DURATION = PinId.of("duration");
    private static final PinId UNIT = PinId.of("unit");
    private static final PinId OUTPUT_TIMER = PinId.of("output_timer");
    private static final PinId STATE = PinId.of("state");
    private static final List<PinId> DATA_OUTPUTS = List.of(OUTPUT_TIMER, STATE, PinId.of("remaining"), PinId.of("elapsed"),
        PinId.of("output_duration"), PinId.of("progress"), PinId.of("progress_percent"));

    @TempDir
    Path temporary;

    private JavaPlugin plugin;
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;
    private ReSyncJsonResourceStorage json;
    private AutomationTaskService tasks;
    private ScheduledExecutorService scheduler;
    private FlowExecutor executor;
    private GlobalTriggers triggers;
    private FlowJobRegistry jobs;
    private CatalogPublicationReceiptStore receipts;
    private FlowModule module;
    private RuntimeBindingRegistry runtime;
    private NodeDefinition timerDefinition;
    private NodeDefinition broadcastDefinition;
    private PlayerMock recipient;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Path root = temporary.resolve("runtime");
        Files.createDirectories(root);
        assetsGate = new AssetPersistenceGate(root);
        coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(coordinator);
        json = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.compatibility(root), assetsGate, coordinator);
        json.save(ReSyncResourceCatalog.TIMER_DEFINITION, JsonParser.parseString("""
            {
              "id": "compiled_timer",
              "name": "Compiled Timer",
              "scope": "server",
              "persistent": false,
              "defaultDuration": 60,
              "defaultUnit": "seconds",
              "tickInterval": 0
            }
            """).getAsJsonObject());
        AutomationDefinitionRegistry automation = new AutomationDefinitionRegistry(json);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        tasks = new AutomationTaskService(plugin, automation, Clock.systemUTC(), scheduler, null);
        TimerHandler timerHandler = new TimerHandler(automation, tasks);
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("TimerHandler", timerHandler);
        handlers.register("ServerHandler", new ServerHandler());
        List<NodeDefinition> currentDefinitions = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes");
        timerDefinition = currentDefinitions.stream()
            .filter(definition -> "automation.timer".equals(definition.getId()))
            .findFirst()
            .orElseThrow();
        broadcastDefinition = currentDefinitions.stream()
            .filter(definition -> "server.system_broadcast".equals(definition.getId()))
            .findFirst()
            .orElseThrow();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register("timer-runtime-test", timerDefinition);
        definitions.register("timer-runtime-test", broadcastDefinition);
        FlowStorage storage = new FlowStorage(root.toFile(), LegacyRuntimeActivationGate.compatibility(root), assetsGate, SERVER, coordinator);
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        executor = new FlowExecutor(handlers, adapters, Map.of());
        TriggerRegistry triggerRegistry = new TriggerRegistry(plugin);
        triggers = new GlobalTriggers(storage, executor, triggerRegistry, null, false);
        jobs = new FlowJobRegistry();
        runtime = new RuntimeBindingRegistry(new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability) {
                return true;
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return confirmation == RuntimeSemantics.Confirmation.NONE;
            }
        }, RuntimeAuditBoundary.unavailable(), new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true),
            RuntimeReceiptStore.inMemory(false));
        receipts = new CatalogPublicationReceiptStore(root, root.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        module = new FlowModule(storage, null, 1, triggerRegistry, triggers, null, definitions, new PropertyRegistry(), null,
            null, null, new OptionCatalogRegistry(), json, null, null, null, new FlowResourceRegistry(), new FlowValueCodecRegistry(),
            jobs, runtime, handlers, CatalogActivationAuthority.freshInstall(), SERVER, receipts, authoredSources());
        module.setExecutor(executor);
        module.setConversionAdapterRegistry(adapters);
        recipient = MockBukkit.getMock().addPlayer();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (module != null) {
                module.deactivateCoreMutationSubscribers();
            }
            if (triggers != null) {
                triggers.shutdownRuntimeCommands();
                triggers.getTriggerDispatcher().shutdown();
            }
            if (tasks != null) {
                tasks.shutdown();
            }
            if (executor != null) {
                executor.shutdown();
            }
            if (jobs != null) {
                jobs.shutdownAsync().join();
            }
            if (json != null) {
                json.closePersistence();
            }
            if (receipts != null) {
                receipts.close();
            }
            if (assetsGate != null) {
                assetsGate.quiesce();
            }
            if (coordinator != null) {
                coordinator.close();
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void compiledTimerRuntimeRoutesEveryActionWithTypedReferenceAndDataOutputs() {
        assertRoute(execute("Check"), "inactive", "inactive");
        assertRoute(execute("Start"), "active", "active");
        assertRoute(execute("Pause"), "paused", "paused");
        assertRoute(execute("Resume"), "active", "active");
        assertRoute(execute("Stop"), "inactive", "cancelled");
    }

    private Map<PinId, TypedValue> execute(String action) {
        CatalogRuntimeActivation.ActivationRecord activation = module.activeCatalogRuntimeActivation();
        CatalogNodeDescriptor descriptor = activation.catalog().definition(
            ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("automation.timer"))).orElseThrow().descriptor();
        Map<PinId, PinValue> values = new LinkedHashMap<>();
        values.put(TIMER, new PinValue(TIMER, TypedValue.locator(pin(descriptor, TIMER).type(),
            new ServerResourceLocator(SERVER, ContractRef.of(OwnerId.of("builtin"),
                ResourceTypeId.of(ReSyncResourceCatalog.TIMER_DEFINITION)), "compiled_timer"))));
        values.put(ACTION, new PinValue(ACTION, TypedValue.value(pin(descriptor, ACTION).type(), action)));
        if ("Start".equals(action)) {
            values.put(DURATION, new PinValue(DURATION, TypedValue.value(pin(descriptor, DURATION).type(), 60D)));
            values.put(UNIT, new PinValue(UNIT, TypedValue.value(pin(descriptor, UNIT).type(), "Seconds")));
        }
        CatalogBinding binding = new CatalogBinding(activation.catalog().generation(), activation.catalog().contentChecksum(),
            activation.catalog().bindingManifestHash());
        List<GraphNode> nodes = new ArrayList<>();
        nodes.add(new GraphNode(TIMER_NODE,
            ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("automation.timer")), timerDefinition.getSchemaVersion(), values));
        BROADCAST_NODES.values().forEach(node -> nodes.add(new GraphNode(node,
            ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("server.system_broadcast")),
            broadcastDefinition.getSchemaVersion(), Map.of())));
        List<GraphConnection> connections = new ArrayList<>();
        BROADCAST_NODES.forEach((branch, node) -> {
            connections.add(new GraphConnection(ConnectionId.deterministic("compiled-timer-" + branch + "-flow"),
                new GraphEndpoint(TIMER_NODE, PinId.of(branch)), new GraphEndpoint(node, PinId.of("flow"))));
            connections.add(new GraphConnection(ConnectionId.deterministic("compiled-timer-" + branch + "-state"),
                new GraphEndpoint(TIMER_NODE, STATE), new GraphEndpoint(node, PinId.of("message"))));
        });
        GraphDocument graph = new GraphDocument(new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "compiled-timer-runtime"), 1,
            binding, nodes, connections);
        CompiledExecutionPlan plan = new GraphCompiler(activation.runtime().manifest()).compile(graph, activation.catalog());
        CompiledExecutionRunner runner = new CompiledExecutionRunner(module::activeCatalogRuntimeActivation, runtime,
            RuntimeAuthority.anonymous());
        CompiledExecutionRunner.ExecutionResult execution = runner.execute(plan, TIMER_NODE).toCompletableFuture().join();

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, execution.status(), () -> action + ": " + execution.failure());
        assertNotNull(execution.nodeResults().get(TIMER_NODE));
        assertNotNull(execution.nodeResults().get(BROADCAST_NODES.get(actionBranch(action))));
        assertEquals(actionState(action), recipient.nextMessage());
        return execution.nodeResults().get(TIMER_NODE).outputs();
    }

    private String actionBranch(String action) {
        return switch (action) {
            case "Start", "Resume" -> "active";
            case "Pause" -> "paused";
            default -> "inactive";
        };
    }

    private String actionState(String action) {
        return "Stop".equals(action) ? "cancelled" : actionBranch(action);
    }

    private void assertRoute(Map<PinId, TypedValue> outputs, String expectedBranch, String expectedState) {
        assertTrue(outputs.get(PinId.of(expectedBranch)).hasValue());
        for (String branch : List.of("active", "paused", "inactive")) {
            if (!branch.equals(expectedBranch)) {
                assertFalse(outputs.get(PinId.of(branch)).hasValue());
            }
        }
        assertEquals(expectedState, outputs.get(STATE).value());
        assertEquals("compiled_timer", outputs.get(OUTPUT_TIMER).locator().id());
        assertEquals(OwnerId.of("builtin"), outputs.get(OUTPUT_TIMER).locator().owner());
        DATA_OUTPUTS.forEach(pin -> assertNotNull(outputs.get(pin), pin.canonicalText()));
    }

    private CatalogNodeDescriptor.Pin pin(CatalogNodeDescriptor descriptor, PinId id) {
        return descriptor.pins().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
    }

    private List<CatalogSourceIngestor.CatalogSource> authoredSources() throws Exception {
        return List.of(authoredSource("nodes/automation.json"), authoredSource("nodes/server.json"));
    }

    private CatalogSourceIngestor.CatalogSource authoredSource(String path) throws Exception {
        try (var stream = FlowTimerRuntimeTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing authored catalog source: " + path);
            }
            return new CatalogSourceIngestor.CatalogSource(OwnerId.of("restudio.resync"), CatalogProvenance.SourceKind.BUNDLED,
                "classpath:/" + path, "1.0.0", "resync-flow", stream.readAllBytes());
        }
    }
}
