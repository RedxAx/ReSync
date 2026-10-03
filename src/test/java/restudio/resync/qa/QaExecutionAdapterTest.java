package restudio.resync.qa;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.CompiledRuntimeValueCodec;
import restudio.resync.flow.CompiledCoreFlowExecutionBridge;
import restudio.resync.flow.CompiledFunctionExecutionBridge;
import restudio.resync.flow.CompiledGraphMetadataProvider;
import restudio.resync.flow.CompiledFunctionExecutionRequest;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionOutputMap;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionResult;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.triggers.TriggerDispatcher;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaExecutionAdapterTest {
    @TempDir Path temporary;
    private Plugin plugin;
    private World world;
    private Player player;
    private CommandSender actor;
    private FlowExecutor executor;
    private FlowStorage storage;
    private AssetPersistenceGate gate;
    private AssetTransactionCoordinator transactions;
    private TriggerDispatcher dispatcher;
    private QaExecutionAdapter adapter;
    private ServerId serverId;
    private RuntimeAuthority authority;
    private RuntimePrincipalAuthority principals;
    private CompiledCoreFlowExecutionBridge flows;
    private CompiledTriggerExecution triggers;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = MockBukkit.getMock().addSimpleWorld("qa-world");
        world.loadChunk(0, 0);
        world.getBlockAt(2, 64, 4).setType(Material.STONE);
        player = MockBukkit.getMock().addPlayer();
        player.teleport(new Location(world, 2, 64, 4));
        actor = MockBukkit.getMock().getConsoleSender();
        actor.addAttachment(plugin, "resync.qa", true);
        serverId = ServerId.deterministic("qa-execution-test");
        gate = new AssetPersistenceGate(temporary);
        transactions = AssetTransactionCoordinator.open(temporary.resolve("assets"), new Gson());
        storage = new FlowStorage(temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary), gate, serverId, transactions);
        executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        authority = new RuntimeAuthority("qa-test");
        principals = new RuntimePrincipalAuthority(authority);
        flows = new CompiledCoreFlowExecutionBridge(
            () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)),
            () -> RuntimeBindingManifest.create(List.of(), List.of(), Map.of(), List.of()), new RuntimeBindingRegistry(), authority);
        triggers = new CompiledTriggerExecution(executor, new CompiledGraphMetadataProvider(
            () -> null, () -> null, serverId, new FlowValueCodecRegistry()), flows, diagnostics -> {}, principals);
        triggers.bindCoreStorage(storage, serverId);
        dispatcher = new TriggerDispatcher(storage, executor, plugin);
        adapter = new QaExecutionAdapter(plugin, serverId, storage, executor, triggers, flows,
            new CompiledFunctionExecutionBridge(null, null), authority, principals, dispatcher);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (dispatcher != null) {
                dispatcher.shutdown();
            }
            if (executor != null) {
                executor.cancelPendingTasks();
                executor.shutdown();
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
    void permissionIsRequiredForConsoleAndOffThreadInvocationCannotTouchTheWorld() {
        actor.addAttachment(plugin, "resync.qa", false);
        CompletionException denied = assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "event.inject", fixture()).toCompletableFuture().join());
        assertTrue(denied.getCause() instanceof SecurityException);
        actor.addAttachment(plugin, "resync.qa", true);

        CompletionException asynchronous = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(
            () -> adapter.invoke(actor, "event.inject", fixture()).toCompletableFuture().join()).join());
        assertTrue(asynchronous.getCause() instanceof SecurityException);
        assertEquals(Material.STONE, world.getBlockAt(2, 64, 4).getType());
    }

    @Test
    void injectedFixtureUsesTheRealDispatcherAndKeepsVanillaActionSeparate() {
        AtomicInteger extracted = new AtomicInteger();
        dispatcher.registerDefinition("block_break", "event:block_break", BlockBreakEvent.class, EventPriority.NORMAL, false,
            event -> { extracted.incrementAndGet(); return Map.of("event.player", ((BlockBreakEvent) event).getPlayer()); },
            event -> ((BlockBreakEvent) event).getPlayer(), new String[0]);
        Bukkit.getPluginManager().registerEvent(BlockBreakEvent.class, new Listener() {}, EventPriority.LOW,
            (listener, supplied) -> {
                BlockBreakEvent event = (BlockBreakEvent) supplied;
                assertSame(player, event.getPlayer());
                assertTrue(Bukkit.isPrimaryThread());
                event.setCancelled(true);
                event.setDropItems(false);
                event.setExpToDrop(7);
            }, plugin);

        Map<String, Object> result = adapter.invoke(actor, "event.inject", fixture()).toCompletableFuture().join();

        assertEquals(1, extracted.get());
        assertEquals("dispatch-only", result.get("semantics"));
        assertEquals(false, result.get("vanillaActionPerformed"));
        assertEquals(Map.of("cancelled", true, "dropItems", false, "experience", 7), result.get("eventAfterCompletion"));
        assertEquals(1, ((List<?>) result.get("dispatches")).size());
        assertEquals(Material.STONE, world.getBlockAt(2, 64, 4).getType());
        assertEquals(true, result.get("physicalComplete"));
    }

    @Test
    void nestedUnrelatedEventIsExcludedFromTheFixtureObservation() {
        AtomicInteger extracted = new AtomicInteger();
        AtomicBoolean nested = new AtomicBoolean();
        dispatcher.registerDefinition("block_break", "event:block_break", BlockBreakEvent.class, EventPriority.NORMAL, false,
            event -> { extracted.incrementAndGet(); return Map.of(); }, event -> player, new String[0]);
        Bukkit.getPluginManager().registerEvent(BlockBreakEvent.class, new Listener() {}, EventPriority.LOW,
            (listener, event) -> {
                if (nested.compareAndSet(false, true)) {
                    Bukkit.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(3, 64, 4), player));
                }
            }, plugin);

        Map<String, Object> result = adapter.invoke(actor, "event.inject", fixture()).toCompletableFuture().join();

        assertEquals(2, extracted.get());
        assertEquals(1, ((List<?>) result.get("dispatches")).size());
    }

    @Test
    void extractorFailureReportsARejectedDispatchAndPreservesTheActualFixture() {
        dispatcher.registerDefinition("block_break", "event:block_break", BlockBreakEvent.class, EventPriority.NORMAL, false,
            event -> { throw new IllegalStateException("Extractor Failed"); }, event -> player, new String[0]);

        Map<String, Object> result = adapter.invoke(actor, "event.inject", fixture()).toCompletableFuture().join();

        List<?> rejected = (List<?>) result.get("rejections");
        assertEquals(1, rejected.size());
        assertEquals("TRIGGER.CONTEXT_REJECTED", ((Map<?, ?>) rejected.getFirst()).get("code"));
        assertEquals("event-extraction-failed", ((Map<?, ?>) rejected.getFirst()).get("reason"));
        assertEquals("rejected", ((Map<?, ?>) ((List<?>) result.get("dispatches")).getFirst()).get("status"));
        assertEquals(Map.of("cancelled", false, "dropItems", true, "experience", 0), result.get("eventAfterCompletion"));
        assertEquals(Material.STONE, world.getBlockAt(2, 64, 4).getType());
        assertEquals(true, result.get("physicalComplete"));
    }

    @Test
    void aListenerUnloadingTheFixtureChunkLeavesBothSnapshotsUnavailable() {
        Bukkit.getPluginManager().registerEvent(BlockBreakEvent.class, new Listener() {}, EventPriority.NORMAL,
            (listener, event) -> assertTrue(world.unloadChunk(0, 0)), plugin);

        Map<String, Object> result = adapter.invoke(actor, "event.inject", fixture()).toCompletableFuture().join();

        assertEquals(false, ((Map<?, ?>) result.get("fixture")).get("blockAvailableAfterDispatch"));
        assertFalse(((Map<?, ?>) result.get("fixture")).containsKey("blockDataAfterDispatch"));
        assertEquals(Map.of("available", false), result.get("blockAfterCompletion"));
        assertFalse(world.isChunkLoaded(0, 0));
        assertEquals(true, result.get("physicalComplete"));
    }

    @Test
    void staleRevisionChecksumAndWrongTypedResourceCannotEnterExecution() {
        ContentHash hash = ContentHash.of("1".repeat(64));
        GraphDocument document = new GraphDocument(new ServerResourceLocator(serverId,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "same-id"), 1,
            new CatalogBinding(1, hash, hash), List.of(), List.of());
        storage.saveCoreGraph(document, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        FlowGraph graph = storage.getGraph("flow", "same-id");
        assertTrue(graph != null);

        assertRejected(Map.of("resourceType", "flow", "resourceId", "same-id", "revision", 2,
            "checksum", graph.getResourceHash(), "startNodeId", "start"));
        assertRejected(Map.of("resourceType", "flow", "resourceId", "same-id", "revision", 1,
            "checksum", "0".repeat(64), "startNodeId", "start"));
        assertRejected(Map.of("resourceType", "command", "resourceId", "same-id", "revision", 1,
            "checksum", graph.getResourceHash(), "startNodeId", "start"));
    }

    @Test
    void cancellationSuppressesFurtherCallbackLaunchesAndRetainsStartedPhysicalWork() throws Exception {
        CompletableFuture<Void> physical = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean laterStarted = new AtomicBoolean();
        CompletableFuture<Void> execution = executor.runOnMain(plugin, true, cancelled -> {
            started.set(true);
            executor.cancelPendingTasks();
            if (!cancelled.getAsBoolean()) {
                laterStarted.set(true);
            }
            return physical;
        });

        MockBukkit.getMock().getScheduler().performOneTick();
        assertTrue(started.get());
        assertFalse(laterStarted.get());
        assertFalse(execution.isDone());
        try (FlowExecutor.AdmissionFence fence = executor.fenceAdmissions()) {
            assertFalse(fence.whenDrained().isDone());
            physical.complete(null);
            assertThrows(CompletionException.class, execution::join);
            fence.whenDrained().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void functionDiscoveryReportsMissingTypedProviders() {
        Map<?, ?> descriptor = ((List<?>) adapter.describe().get("operations")).stream().map(item -> (Map<?, ?>) item)
            .filter(item -> "function.run".equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals(false, descriptor.get("supported"));
        assertEquals("Typed Function source and capability providers are unavailable", descriptor.get("reason"));
    }

    @Test
    void nestedTypedFunctionAdmissionUsesTheCompiledOwnerAndWaitsForPhysicalCompletion() throws Exception {
        FunctionSourceDocument source = functionSource("nested", 1);
        var stored = storage.saveCoreGraph(source, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        CompletableFuture<FunctionResult> physical = new CompletableFuture<>();
        AtomicReference<CompiledFunctionExecutionRequest> admitted = new AtomicReference<>();
        CompiledFunctionExecutionBridge functions = functionBridge(source, request -> {
            admitted.set(request);
            return physical;
        });
        executor.configureCompiledFunctionRuntime(authority, principals, RuntimeReceiptStore.inMemory(true), serverId);
        adapter = new QaExecutionAdapter(plugin, serverId, storage, executor, triggers, flows, functions, authority, principals, dispatcher);
        IllegalStateException legacy = assertThrows(IllegalStateException.class, () -> storage.getGraph("function", "nested"));
        assertEquals("Core graph static function bindings require compiled execution", legacy.getMessage());
        Map<String, Object> input = functionRequest(stored.envelope().assetHash().canonicalText(), "nested", 1);

        CompletableFuture<Map<String, Object>> result = adapter.invoke(actor, "function.run", input).toCompletableFuture();

        assertFalse(result.isDone());
        assertEquals(source.signature(), admitted.get().execution().signature());
        assertEquals(source.checksum().canonicalText(), admitted.get().execution().attributes().get("function.sourceChecksum"));
        FunctionParameterId parameter = source.signature().inputs().getFirst().id();
        assertEquals(BigDecimal.valueOf(7), admitted.get().execution().inputs().value(parameter).value());
        try (FlowExecutor.AdmissionFence fence = executor.fenceAdmissions()) {
            assertFalse(fence.whenDrained().isDone());
            FunctionParameterId output = source.signature().outputs().getFirst().id();
            physical.complete(FunctionResult.success(source.signature(), new FunctionOutputMap(Map.of(output,
                TypedValue.value(source.signature().outputs().getFirst().type(), BigDecimal.valueOf(8)))), 1));
            Map<String, Object> response = result.get(5, TimeUnit.SECONDS);
            assertEquals("success", response.get("status"));
            assertEquals(true, response.get("physicalComplete"));
            assertEquals(stored.envelope().assetHash().canonicalText(), ((Map<?, ?>) response.get("resource")).get("checksum"));
            fence.whenDrained().get(5, TimeUnit.SECONDS);
        }
        Map<String, Object> discovery = adapter.invoke(actor, "execution.discover", Map.of()).toCompletableFuture().join();
        assertEquals("nested", ((Map<?, ?>) ((List<?>) discovery.get("resources")).getFirst()).get("resourceId"));
    }

    @Test
    void staleInactiveAndUndeclaredTypedFunctionInputsCannotEnterTheCompiledOwner() {
        FunctionSourceDocument source = functionSource("rejected", 1);
        var stored = storage.saveCoreGraph(source, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0);
        AtomicInteger executions = new AtomicInteger();
        CompiledFunctionExecutionBridge functions = functionBridge(source, request -> {
            executions.incrementAndGet();
            return CompletableFuture.failedFuture(new AssertionError("Rejected Function Must Not Start"));
        });
        adapter = new QaExecutionAdapter(plugin, serverId, storage, executor, triggers, flows, functions, authority, principals, dispatcher);
        Map<String, Object> current = functionRequest(stored.envelope().assetHash().canonicalText(), "rejected", 1);
        Map<String, Object> stale = new LinkedHashMap<>(current);
        stale.put("revision", 2);
        Map<String, Object> wrongChecksum = new LinkedHashMap<>(current);
        wrongChecksum.put("checksum", "0".repeat(64));
        Map<String, Object> unknown = new LinkedHashMap<>(current);
        unknown.put("inputs", Map.of("unknown", 7));

        for (Map<String, Object> rejected : List.of(stale, wrongChecksum, unknown)) {
            CompletionException failure = assertThrows(CompletionException.class,
                () -> adapter.invoke(actor, "function.run", rejected).toCompletableFuture().join());
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
        }
        FunctionSourceDocument inactive = functionSource("inactive", 1);
        var inactiveStored = storage.saveCoreGraph(inactive, ResourceActivationState.INACTIVE, UUID.randomUUID(), 0);
        assertThrows(CompletionException.class, () -> adapter.invoke(actor, "function.run",
            functionRequest(inactiveStored.envelope().assetHash().canonicalText(), "inactive", 1)).toCompletableFuture().join());
        assertEquals(0, executions.get());
    }

    @Test
    void typedFunctionSourceAdmissionUsesDeclaredHostAndLocatorTypes() {
        FunctionSourceDocument template = functionSource("native-inputs", 1);
        FunctionParameterId playerInput = FunctionParameterId.deterministic("qa-native-player-input");
        FunctionParameterId resourceInput = FunctionParameterId.deterministic("qa-native-resource-input");
        TypeExpr playerType = TypeExpr.named(TypeReference.of("builtin", "player"));
        TypeExpr resourceType = TypeExpr.resource(TypeReference.of("restudio.resync", "function"));
        FunctionSignature signature = new FunctionSignature(template.signature().function(), template.signature().revision(),
            List.of(new FunctionParameterContract(playerInput, playerType, true, null, Map.of("name", "player")),
                new FunctionParameterContract(resourceInput, resourceType, true, null, Map.of("name", "reference"))), List.of());
        FunctionSourceDocument source = new FunctionSourceDocument(signature, template.graph());
        CompiledFunctionExecutionBridge bridge = functionBridge(source, request -> {
            throw new AssertionError("Source Admission Must Not Execute The Function");
        });
        ServerResourceLocator locator = new ServerResourceLocator(serverId, template.graph().resource().type(), "child");
        List<Object> references = List.of(locator, new FlowResourceReference("function", "child", "restudio.resync"),
            TypedValue.locator(resourceType, locator));
        for (Object reference : references) {
            CompiledFunctionExecutionRequest request = bridge.requestForSource(source, player, null,
                Map.of("player", player, resourceInput.canonicalText(), reference), Map.of(), serverId, authority,
                principals.issueSystem("server"), CorrelationId.random(), null, Long.MAX_VALUE, null, null);

            assertSame(player, CompiledRuntimeValueCodec.decode(serverId, request.execution().inputs().value(playerInput)));
            TypedValue admitted = request.execution().inputs().value(resourceInput);
            assertEquals(TypedValue.State.LOCATOR, admitted.state());
            assertEquals(locator, admitted.locator());
            if (reference instanceof TypedValue typed) {
                assertSame(typed, admitted);
            }
        }
        ServerResourceLocator foreign = new ServerResourceLocator(ServerId.deterministic("foreign"), locator.type(), locator.id());
        assertThrows(IllegalArgumentException.class, () -> bridge.requestForSource(source, player, null,
            Map.of("player", player, "reference", TypedValue.locator(resourceType, foreign)), Map.of(), serverId,
            authority, principals.issueSystem("server"), CorrelationId.random(), null, Long.MAX_VALUE, null, null));
        assertThrows(IllegalArgumentException.class, () -> bridge.requestForSource(source, player, null,
            Map.of("player", TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "number")), 1), "reference", locator),
            Map.of(), serverId, authority, principals.issueSystem("server"), CorrelationId.random(), null, Long.MAX_VALUE, null, null));
    }

    private FunctionSourceDocument functionSource(String id, long revision) {
        TypeExpr number = TypeExpr.named(TypeReference.of("builtin", "number"));
        FunctionParameterId input = FunctionParameterId.deterministic("qa-typed-function-input");
        FunctionParameterId output = FunctionParameterId.deterministic("qa-typed-function-output");
        ServerResourceLocator resource = new ServerResourceLocator(serverId,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), id);
        ServerResourceLocator child = new ServerResourceLocator(serverId, resource.type(), "child");
        ContentHash hash = ContentHash.of("1".repeat(64));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, revision, new CatalogBinding(1, hash, hash),
            Set.of(), List.of(), List.of(), List.of(), List.of(new FunctionBinding(child, 1, List.of(), List.of())), OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(revision),
            List.of(new FunctionParameterContract(input, number, true, null, Map.of("name", "value"))),
            List.of(new FunctionParameterContract(output, number)));
        return new FunctionSourceDocument(signature, graph);
    }

    private CompiledFunctionExecutionBridge functionBridge(FunctionSourceDocument source,
            Function<CompiledFunctionExecutionRequest, CompletionStage<FunctionResult>> execution) {
        return new CompiledFunctionExecutionBridge(new CompiledFunctionExecutionBridge.GraphFunctions() {
            @Override
            public Optional<CompiledFunctionExecutionBridge.FunctionAdmission> resolve(FunctionLocator function, FunctionRevision revision) {
                return source.signature().function().equals(function) && source.signature().revision().equals(revision)
                    ? Optional.of(new CompiledFunctionExecutionBridge.FunctionAdmission(source, source.checksum(), source.graph().catalogBinding().bindingManifestHash()))
                    : Optional.empty();
            }

            @Override
            public CompletionStage<FunctionResult> execute(CompiledFunctionExecutionRequest request, RuntimeCancellationToken parentCancellation) {
                return execution.apply(request);
            }
        });
    }

    private static Map<String, Object> functionRequest(String checksum, String id, long revision) {
        return Map.of("resourceType", "function", "resourceId", id, "revision", revision, "checksum", checksum,
            "inputs", Map.of(FunctionParameterId.deterministic("qa-typed-function-input").canonicalText(), BigDecimal.valueOf(7)));
    }

    private Map<String, Object> fixture() {
        return Map.of("eventType", "block_break", "worldId", world.getUID().toString(), "playerId", player.getUniqueId().toString(),
            "x", 2, "y", 64, "z", 4);
    }

    private void assertRejected(Map<String, Object> request) {
        CompletionException failure = assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "flow.run", request).toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertTrue(Set.of("The Requested Typed Graph Revision Or Checksum Is Not Current And Active",
            "The Authoritative Typed Graph Source Is Unavailable").contains(failure.getCause().getMessage()));
    }
}
