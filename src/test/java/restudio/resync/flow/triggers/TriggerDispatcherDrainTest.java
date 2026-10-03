package restudio.resync.flow.triggers;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import restudio.resync.flow.CompiledCoreFlowExecutionBridge;
import restudio.resync.flow.CompiledFunctionExecutionBridge;
import restudio.resync.flow.CompiledGraphMetadataProvider;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.ServerCompiledPlanRepository;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.FlowRuntimeExecutionBoundary;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSecurityBoundary;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.qa.QaExecutionAdapter;
import restudio.resync.server.CoreGraphMutationValidator;
import restudio.resync.server.FlowStorageCoreGraphResourceAuthority;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerDispatcherDrainTest {
    @TempDir Path temporary;
    private final CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
    private final AtomicInteger calls = new AtomicInteger();
    private Plugin plugin;
    private FlowExecutor executor;
    private AssetPersistenceGate gate;
    private AssetTransactionCoordinator transactions;
    private ServerCompiledPlanRepository plans;
    private TriggerDispatcher dispatcher;
    private QaExecutionAdapter adapter;
    private boolean throwError;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        OwnerId owner = OwnerId.of("restudio.resync");
        ContractRef<CapabilityId> capability = ContractRef.of(owner, CapabilityId.of("deferred-test"));
        ContractRef<OperationId> operationId = ContractRef.of(owner, OperationId.of("deferred-test"));
        ContractRef<ProviderId> provider = ContractRef.of(owner, ProviderId.of("deferred-test"));
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.MAIN, capability,
            RuntimeSemantics.Cancellation.COOPERATIVE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of("failed"),
            new RuntimeFailureContract(TypeExpr.named(TypeReference.of("builtin", "string")), Set.of("RUNTIME.FAILURE"),
                Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(capability, operationId, List.of(), semantics);
        RuntimeSecurityBoundary security = new RuntimeSecurityBoundary() {
            @Override public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> requested) {
                return capability.equals(requested);
            }
            @Override public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return confirmation == RuntimeSemantics.Confirmation.NONE;
            }
        };
        FlowRuntimeExecutionBoundary boundary = new FlowRuntimeExecutionBoundary(
            task -> { throw new AssertionError("The Server Thread Must Run Directly"); },
            task -> { throw new AssertionError("No Worker Was Requested"); }, () -> true);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, RuntimeAuditBoundary.unavailable(), boundary,
            RuntimeReceiptStore.inMemory(false));
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(
            RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
                calls.incrementAndGet();
                if (throwError) {
                    throw new AssertionError("Handler Error");
                }
                executor.cancelPendingTasks();
                return pending;
            })));
        CatalogVersion version = new CatalogVersion(1, 0);
        CatalogNodeDescriptor definition = CatalogNodeDescriptor.builder("deferred_test").domain("event").family("block")
            .displayName("Deferred Test").description("Run the deferred event test.").category(capability)
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed", "The handler failed.",
                List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The handler returned a failure.")))))
            .handler(new CatalogNodeDescriptor.Handler(capability, operationId)).semantics(semantics).requiredCapabilities(Set.of(capability))
            .metadata(Map.of("sourceNodeId", "deferred_test", "handlerConfig", Map.of())).build();
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0", new CatalogContractRange(version, version),
            CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/deferred-test.json", "1.0.0", "test", "deferred-test"))
            .categories(List.of(new CatalogCategoryDescriptor(CapabilityId.of("deferred-test"), "Events", "Deferred event handler tests.", 1)))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("deferred-test"), 1, false, InspectorFallback.GENERIC)))
            .definitions(List.of(definition)).runtimeRequirements(List.of(operation)).build();
        var compilation = new CatalogCompiler(version, CatalogBindingProof.live(registry)).compile(List.of(contribution), 1);
        assertTrue(compilation.accepted(), compilation.diagnostics()::toString);
        CatalogSnapshot catalog = compilation.snapshot().orElseThrow();
        CatalogRuntimeActivation.ActivationRecord activation = new CatalogRuntimeActivation.ActivationRecord(catalog, registry.snapshot());
        ServerId server = ServerId.deterministic("deferred-test");
        gate = new AssetPersistenceGate(temporary);
        transactions = AssetTransactionCoordinator.open(temporary.resolve("assets"), new Gson());
        FlowStorage storage = new FlowStorage(temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary), gate, server, transactions);
        CoreGraphMutationValidator validator = new CoreGraphMutationValidator(server, () -> activation, CatalogActivationAuthority::freshInstall);
        plans = new ServerCompiledPlanRepository(new FlowStorageCoreGraphResourceAuthority(storage, server, validator), () -> activation);
        RuntimeAuthority authority = RuntimeAuthority.anonymous();
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(() -> activation, registry,
            authority, null, plans);
        CompiledGraphMetadataProvider metadata = new CompiledGraphMetadataProvider(() -> activation, server, new FlowValueCodecRegistry());
        NodeInstanceId start = NodeInstanceId.deterministic("deferred-start");
        for (String id : List.of("first", "second")) {
            GraphDocument graph = new GraphDocument(new ServerResourceLocator(server, ContractRef.of(owner, ResourceTypeId.of("flow")), id),
                1, new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()),
                List.of(new GraphNode(start, ContractRef.of(owner, NodeId.of("deferred_test")), 1, Map.of())), List.of());
            storage.saveCoreGraph(graph, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        }
        plans.bindTemplateCompiler(bridge::prepare);
        plans.bindMetadataCompiler(metadata::provide);
        plans.initialize();
        CompiledTriggerExecution execution = new CompiledTriggerExecution(executor, metadata, bridge, (report, diagnostics) -> report, null, plans);
        execution.bindCoreStorage(storage, server);
        dispatcher = new TriggerDispatcher(storage, executor, plugin);
        dispatcher.setCompiledExecution(execution);
        dispatcher.registerDefinition("block_break", "event:block_break", BlockBreakEvent.class, EventPriority.NORMAL, false,
            event -> Map.of(), event -> null, new String[0]);
        dispatcher.registerBinding("block_break", "first", start.canonicalText());
        dispatcher.registerBinding("block_break", "second", start.canonicalText());
        adapter = new QaExecutionAdapter(plugin, server, storage, executor, execution, bridge,
            new CompiledFunctionExecutionBridge(null, null), authority, new RuntimePrincipalAuthority(authority), dispatcher);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            pending.complete(RuntimeResult.success());
            if (dispatcher != null) {
                dispatcher.shutdown();
            }
            if (executor != null) {
                executor.cancelPendingTasks();
                executor.shutdown();
            }
            if (plans != null) {
                plans.close();
            }
            if (gate != null) {
                gate.quiesce();
            }
            if (transactions != null) {
                transactions.close();
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void cancellingAMidBatchLaunchSuppressesLaterGraphsAndWaitsForStartedPhysicalWork() throws Exception {
        CompletableFuture<Map<String, Object>> observation = dispatch();
        assertFalse(observation.isDone());

        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(1, calls.get());
        assertFalse(observation.isDone());
        pending.complete(RuntimeResult.success());

        Map<String, Object> result = observation.get(5, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
        assertEquals(true, result.get("physicalComplete"));
        List<?> invocations = (List<?>) result.get("invocations");
        assertEquals(2, invocations.size());
        assertEquals(1, invocations.stream().map(value -> (Map<?, ?>) value).filter(value -> "rejected".equals(value.get("status"))).count());
        try (FlowExecutor.AdmissionFence fence = executor.fenceAdmissions()) {
            assertTrue(fence.whenDrained().isDone());
        }
    }

    @Test
    void handlerErrorsSettleEveryObservedInvocationAndReleaseTheBatchAdmission() throws Exception {
        throwError = true;
        CompletableFuture<Map<String, Object>> observation = dispatch();
        MockBukkit.getMock().getScheduler().performOneTick();

        Map<String, Object> result = observation.get(5, TimeUnit.SECONDS);
        assertEquals(2, calls.get());
        assertEquals(true, result.get("physicalComplete"));
        assertEquals(2, ((List<?>) result.get("invocations")).size());
        try (FlowExecutor.AdmissionFence fence = executor.fenceAdmissions()) {
            assertTrue(fence.whenDrained().isDone());
        }
    }

    @Test
    void aPostDispatchSnapshotErrorWaitsForStartedHandlersToPhysicallyFinish() throws Exception {
        AtomicBoolean failRead = new AtomicBoolean();
        WorldMock world = new WorldMock() {
            private BlockMock selected;

            @Override
            public BlockMock getBlockAt(int x, int y, int z) {
                if (x != 2 || y != 64 || z != 4) {
                    return super.getBlockAt(x, y, z);
                }
                if (selected == null) {
                    selected = new BlockMock(Material.STONE, new Location(this, x, y, z)) {
                        @Override
                        public BlockData getBlockData() {
                            if (failRead.get()) {
                                throw new AssertionError("Fixture Snapshot Failed");
                            }
                            return super.getBlockData();
                        }
                    };
                }
                return selected;
            }
        };
        world.setName("snapshot-world");
        MockBukkit.getMock().addWorld(world);
        world.loadChunk(0, 0);
        Player player = MockBukkit.getMock().addPlayer();
        player.teleport(new Location(world, 2, 64, 4));
        CommandSender actor = MockBukkit.getMock().getConsoleSender();
        actor.addAttachment(plugin, "resync.qa", true);
        Bukkit.getPluginManager().registerEvent(BlockBreakEvent.class, new Listener() {}, EventPriority.MONITOR,
            (listener, event) -> failRead.set(true), plugin);

        CompletableFuture<Map<String, Object>> result = adapter.invoke(actor, "event.inject", Map.of("eventType", "block_break",
            "worldId", world.getUID().toString(), "playerId", player.getUniqueId().toString(), "x", 2, "y", 64, "z", 4))
            .toCompletableFuture();
        assertFalse(result.isDone());
        MockBukkit.getMock().getScheduler().performOneTick();
        assertEquals(1, calls.get());
        assertFalse(result.isDone());
        pending.complete(RuntimeResult.success());

        Map<String, Object> completed = result.get(5, TimeUnit.SECONDS);
        assertEquals(true, completed.get("physicalComplete"));
        assertEquals("Fixture Snapshot Failed", ((Map<?, ?>) completed.get("dispatchObservationFailure")).get("message"));
        assertEquals(false, ((Map<?, ?>) completed.get("fixture")).get("blockAvailableAfterDispatch"));
        assertTrue(((Map<?, ?>) completed.get("fixture")).get("originalBlockData") instanceof String);
        assertEquals(Map.of("available", false), completed.get("blockAfterCompletion"));
    }

    private CompletableFuture<Map<String, Object>> dispatch() {
        BlockBreakEvent event = new BlockBreakEvent(MockBukkit.getMock().addSimpleWorld("event-world").getBlockAt(2, 64, 4),
            MockBukkit.getMock().addPlayer());
        TriggerDispatcher.EventObservation observation = dispatcher.observeEvent(event);
        Bukkit.getPluginManager().callEvent(event);
        observation.close();
        return observation.completion().toCompletableFuture();
    }
}
