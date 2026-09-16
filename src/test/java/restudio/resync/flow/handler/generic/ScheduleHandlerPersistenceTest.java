package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.CompiledFunctionExecutionBridge;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationInstanceKey;
import restudio.resync.flow.automation.AutomationOwner;
import restudio.resync.flow.automation.AutomationScope;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.automation.ScheduleDefinition;
import restudio.resync.flow.automation.TimerDefinition;
import restudio.resync.flow.function.CompiledFunctionRunner;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceMaterializer;
import restudio.resync.flow.function.TypedFunctionCapabilitySet;
import restudio.resync.flow.function.TypedFunctionCompiler;
import restudio.resync.flow.function.TypedFunctionNodeCapability;
import restudio.resync.flow.function.TypedFunctionNodeCompiler;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.DurableRuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimeExecutionProvenance;
import restudio.resync.flow.runtime.RuntimeLeaseInput;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleHandlerPersistenceTest {
    private static final ServerId SERVER = ServerId.deterministic("schedule-e2e-server");
    private static final ServerId FUNCTION_SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(FUNCTION_SERVER,
        ContractRef.of(new OwnerId("resync"), ResourceTypeId.of("function")), "scheduled-function");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("0".repeat(64)),
        new ContentHash("1".repeat(64)));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "text"));
    private static final FunctionParameterId INPUT = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final FunctionParameterId OUTPUT = FunctionParameterId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final NodeInstanceId NODE = NodeInstanceId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(new OwnerId("typed"), CapabilityId.of("function-test"));
    private static final ContractRef<OperationId> OPERATION = ContractRef.of(new OwnerId("typed"), OperationId.of("copy"));
    private static final PinId VALUE = PinId.of("value");
    private JavaPlugin plugin;
    private ReSyncJsonResourceStorage definitionsStorage;
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (definitionsStorage != null) {
                definitionsStorage.closePersistence();
            }
            if (assetsGate != null) {
                assetsGate.quiesce();
            }
            if (coordinator != null) {
                coordinator.close();
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void persistentScheduleReplaysAfterReceiptCompletionBeforeTaskTransition(@TempDir Path temporary) throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        RuntimeAuthority authority = new RuntimeAuthority("schedule-e2e-authority");
        RuntimePrincipalAuthority firstPrincipals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal creator = firstPrincipals.issueAuthenticatedClient("client-a");
        DurableRuntimeReceiptStore firstReceipts = new DurableRuntimeReceiptStore(temporary.resolve("receipts"));
        BlockingReceiptStore blockingReceipts = new BlockingReceiptStore(firstReceipts);
        AtomicInteger executions = new AtomicInteger();
        CompiledFunctionExecutionBridgeFixture fixture = fixture(executions);
        Clock firstClock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC);
        Clock restoredClock = Clock.fixed(Instant.parse("2026-08-17T00:05:00Z"), ZoneOffset.UTC);
        Path taskFile = temporary.resolve("runtime").resolve("automation-tasks.json");
        ScheduledExecutorService firstScheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService restoredScheduler = Executors.newSingleThreadScheduledExecutor();
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        assetsGate = new AssetPersistenceGate(scope);
        coordinator = new AssetTransactionCoordinator(scope.resolve("assets"), new Gson());
        LegacyRuntimeActivationGate gate = LegacyRuntimeActivationGate.runtime(scope);
        FlowStorage flowStorage = new FlowStorage(plugin.getDataFolder(), gate, assetsGate, coordinator) {
            @Override
            public FlowGraph getGraph(String id) {
                return fixture.graph();
            }

            @Override
            public FlowGraph getGraph(String type, String id) {
                return "function".equals(type) && fixture.graph().getId().equals(id) ? fixture.graph() : null;
            }
        };
        definitionsStorage = new ReSyncJsonResourceStorage(plugin, gate, assetsGate, coordinator);
        AutomationDefinitionRegistry definitions = new AutomationDefinitionRegistry(definitionsStorage);
        MockBukkit.unmock();
        CapturingExecutor firstExecutor = executor(authority, firstPrincipals, creator, blockingReceipts, fixture.bridge());
        AutomationTaskService firstTasks = new AutomationTaskService(null, null, firstClock, firstScheduler, taskFile);
        ScheduleDefinition definition = definition();
        AutomationOwner owner = new AutomationOwner("server", null);
        AutomationInstanceKey key = new AutomationInstanceKey(definition.id(), definition.scope(), owner.id());
        FlowExecutor.FunctionInvocationContext creatorContext = firstExecutor.functionInvocationContext(null, null,
            Map.of("runtime.sessionId", "client-session"), creator, CorrelationId.deterministic("schedule-create"),
            RuntimeExecutionContext.NO_DEADLINE);
        ScheduleHandler firstHandler = new ScheduleHandler(flowStorage, firstClock, definitions, firstTasks, null,
            firstPrincipals, SERVER);
        Supplier<CompletableFuture<Object>> firstInvocation = firstHandler.scheduleInvocation(definition, owner,
            Map.of(INPUT.canonicalText(), "hello"), 1, null, firstExecutor, creatorContext, creator.canonical(), "client-session");
        try {
            firstTasks.startSchedule(new AutomationTaskService.ScheduleRequest(definition, owner, 0L, 0L, null,
                firstInvocation, Map.of(INPUT.canonicalText(), "hello"), 1,
                creator.canonical(), "client-session", definition.targetLocator(SERVER)));
            assertTrue(blockingReceipts.completed.await(5, TimeUnit.SECONDS), firstTasks.snapshots().toString());
            assertEquals(1, executions.get());
            assertEquals(AutomationTaskService.State.ACTIVE, firstTasks.check(key).state());
            FlowExecutor.FunctionInvocationContext firstContext = firstExecutor.invocation();
            assertNotNull(firstContext);

            firstTasks.shutdown();
            JsonObject journal = JsonParser.parseString(Files.readString(firstTasks.persistenceRoot())).getAsJsonObject();
            assertEquals(1, journal.getAsJsonArray("payload").size(), journal.toString());
            JsonObject task = journal.getAsJsonArray("payload").get(0).getAsJsonObject();
            String persistedTaskId = task.get("taskId").getAsString();
            long persistedNextRun = task.get("nextRun").getAsLong();
            assertTrue(task.get("invocationPending").getAsBoolean());
            assertEquals(firstClock.millis(), persistedNextRun);
            assertEquals(creator.canonical(), task.get("creatorPrincipal").getAsString());
            assertEquals("client-session", task.get("creatorSessionReference").getAsString());
            assertEquals(definition.targetLocator(SERVER).canonicalText(), task.get("targetLocator").getAsString());
            assertEquals("function", task.get("targetType").getAsString());
            assertEquals("scheduled-function", task.get("targetId").getAsString());

            blockingReceipts.release.countDown();
            assertTrue(blockingReceipts.returned.await(5, TimeUnit.SECONDS));
            firstExecutor.shutdown();
            firstReceipts.quiesce();
            JsonObject firstReceiptDocument = JsonParser.parseString(Files.readString(firstReceipts.root())).getAsJsonObject();
            JsonObject firstReceipt = firstReceiptDocument.getAsJsonArray("entries").get(0).getAsJsonObject();
            String firstReceiptKey = firstReceipt.get("key").toString();
            String firstInputHash = firstReceipt.get("inputHash").getAsString();

            RuntimePrincipalAuthority restoredPrincipals = new RuntimePrincipalAuthority(authority);
            RuntimePrincipal restoredDefault = restoredPrincipals.issueSystem("resync-runtime");
            DurableRuntimeReceiptStore restoredReceipts = new DurableRuntimeReceiptStore(temporary.resolve("receipts"));
            CapturingExecutor restoredExecutor = executor(authority, restoredPrincipals, restoredDefault, restoredReceipts,
                fixture.bridge());
            AutomationTaskService restoredTasks = new AutomationTaskService(null, null, restoredClock, restoredScheduler, taskFile);
            ScheduleHandler restoredHandler = new ScheduleHandler(flowStorage, restoredClock, definitions, restoredTasks, null,
                restoredPrincipals, SERVER);
            try {
                restoredTasks.restorePersistentSchedules(state -> {
                    Supplier<CompletableFuture<Object>> invocation = restoredHandler.scheduleInvocation(definition, owner,
                        state.arguments(), state.signatureVersion(), null, restoredExecutor, null,
                        state.creatorPrincipal(), state.creatorSessionReference());
                    return new AutomationTaskService.ScheduleRequest(definition, owner, 0L, 0L, null, invocation,
                        state.arguments(), state.signatureVersion(), state.creatorPrincipal(), state.creatorSessionReference(),
                        definition.targetLocator(SERVER));
                });
                waitForTaskState(restoredTasks, persistedTaskId, AutomationTaskService.State.FINISHED);
                assertEquals(1, executions.get());
                FlowExecutor.FunctionInvocationContext restoredContext = restoredExecutor.invocation();
                assertNotNull(restoredContext);
                assertEquals(firstContext.invocationId(), restoredContext.invocationId());
                assertEquals(firstContext.runtimeContext().canonicalValue(), restoredContext.runtimeContext().canonicalValue());
                assertEquals(Map.of(INPUT.canonicalText(), "hello"), firstExecutor.inputs());
                assertEquals(firstExecutor.inputs(), restoredExecutor.inputs());
                assertEquals(firstContext.principal().canonical(), restoredContext.principal().canonical());
                assertNotEquals(creator.canonical(), restoredContext.principal().canonical());
                assertEquals(creator.canonical(), restoredContext.creatorPrincipal());
                assertEquals("client-session", restoredContext.creatorSessionReference());
                assertEquals(1L, restoredTasks.task(persistedTaskId).runCount());
                assertEquals(persistedNextRun, restoredTasks.task(persistedTaskId).lastRun());
                restoredTasks.shutdown();
            } finally {
                restoredExecutor.shutdown();
                restoredReceipts.quiesce();
            }
            JsonObject receiptDocument = JsonParser.parseString(Files.readString(restoredReceipts.root())).getAsJsonObject();
            JsonObject receipt = receiptDocument.getAsJsonArray("entries").get(0).getAsJsonObject();
            assertEquals(firstReceiptKey, receipt.get("key").toString());
            assertEquals(firstInputHash, receipt.get("inputHash").getAsString());
            JsonObject provenance = receipt.getAsJsonObject("provenance");
            assertEquals("system:resync:schedule:" + SERVER.canonicalText(),
                provenance.getAsJsonObject("principal").get("kind").getAsString() + ":"
                    + provenance.getAsJsonObject("principal").get("identity").getAsString());
            assertEquals(creator.canonical(), provenance.get("creatorPrincipal").getAsString());
            assertEquals("client-session", provenance.get("creatorSessionReference").getAsString());
        } finally {
            blockingReceipts.release.countDown();
            if (!firstReceipts.quiesced()) {
                firstReceipts.quiesce();
            }
            if (firstExecutor != null) {
                firstExecutor.shutdown();
            }
            firstScheduler.shutdownNow();
            restoredScheduler.shutdownNow();
        }
    }

    private void waitForTaskState(AutomationTaskService service, String taskId, AutomationTaskService.State expected)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            AutomationTaskService.TaskSnapshot task = service.task(taskId);
            if (task != null && task.state() == expected) {
                return;
            }
            Thread.sleep(10L);
        }
        AutomationTaskService.TaskSnapshot task = service.task(taskId);
        assertEquals(expected, task != null ? task.state() : AutomationTaskService.State.INACTIVE);
    }

    private CapturingExecutor executor(RuntimeAuthority authority, RuntimePrincipalAuthority principals,
                                       RuntimePrincipal principal, RuntimeReceiptStore receipts,
                                       CompiledFunctionExecutionBridge bridge) {
        CapturingExecutor executor = new CapturingExecutor();
        executor.configureCompiledFunctionBridge(bridge);
        executor.configureCompiledFunctionRuntime(authority, principals, receipts, SERVER, principal,
            RuntimeAuditBoundary.unavailable());
        return executor;
    }

    private static final class CapturingExecutor extends FlowExecutor {
        private final AtomicReference<FunctionInvocationContext> invocation = new AtomicReference<>();
        private final AtomicReference<Map<String, Object>> invocationInputs = new AtomicReference<>();

        private CapturingExecutor() {
            super(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        }

        @Override
        public CompletableFuture<Map<String, Object>> executeFunction(FlowGraph functionGraph,
                                                                        org.bukkit.entity.Player player,
                                                                        org.bukkit.event.Event event,
                                                                        Map<String, Object> inputs,
                                                                        Map<String, Object> eventVars,
                                                                        FunctionInvocationContext invocationContext) {
            invocation.set(invocationContext);
            invocationInputs.set(Map.copyOf(inputs));
            return super.executeFunction(functionGraph, player, event, inputs, eventVars, invocationContext);
        }

        private FunctionInvocationContext invocation() {
            return invocation.get();
        }

        private Map<String, Object> inputs() {
            return invocationInputs.get();
        }
    }

    private ScheduleDefinition definition() {
        return new ScheduleDefinition("schedule-e2e", "Schedule E2E", "", ScheduleDefinition.TargetType.FUNCTION,
            "scheduled-function", ScheduleDefinition.TimingMode.AFTER_DELAY, 0D, TimerDefinition.TimeUnit.SECONDS,
            0D, "", "UTC", "", AutomationScope.SERVER, true, ScheduleDefinition.OverlapPolicy.SKIP,
            ScheduleDefinition.ExistingTaskPolicy.REPLACE, ScheduleDefinition.FailurePolicy.CONTINUE,
            ScheduleDefinition.OfflinePolicy.WAIT, ScheduleDefinition.MissedRunPolicy.RUN_ONCE);
    }

    private CompiledFunctionExecutionBridgeFixture fixture(AtomicInteger executions) {
        GraphNode node = new GraphNode(NODE, ContractRef.of(new OwnerId("typed"), NodeId.of("source")), 1,
            Map.of(VALUE, new PinValue(VALUE, TypedValue.value(TEXT, "hello"))));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), RESOURCE, 1, BINDING,
            Set.of(), List.of(node), List.of(), List.of(), List.of(), OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(RESOURCE), FunctionRevision.of(1),
            List.of(new FunctionParameterContract(INPUT, TEXT, true)),
            List.of(new FunctionParameterContract(OUTPUT, TEXT, true)));
        FunctionSourceDocument source = new FunctionSourceMaterializer().materialize(signature, graph).source();
        TypedFunctionNodeCapability capability = new TypedFunctionNodeCapability(NODE,
            ContractRef.of(new OwnerId("typed"), NodeId.of("source")), 1, 0, CAPABILITY, OPERATION,
            new ContentHash("2".repeat(64)), Set.of(), nodeCompiler(executions));
        TypedFunctionCapabilitySet capabilities = new TypedFunctionCapabilitySet(BINDING, List.of(capability));
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(source), ignored -> Optional.of(capabilities));
        FlowGraph functionGraph = new FlowGraph();
        functionGraph.setId("scheduled-function");
        functionGraph.setFunction(true);
        functionGraph.setResourceType("function");
        functionGraph.setResourceRevision(1L);
        functionGraph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter(INPUT, "value", FlowDataType.STRING)));
        functionGraph.setFunctionOutputs(List.of(new FlowGraph.FunctionParameter(OUTPUT, "result", FlowDataType.STRING)));
        return new CompiledFunctionExecutionBridgeFixture(functionGraph, bridge);
    }

    private TypedFunctionNodeCompiler nodeCompiler(AtomicInteger executions) {
        return node -> frame -> {
            executions.incrementAndGet();
            return frame.withOutput(OUTPUT, frame.input(INPUT));
        };
    }

    private record CompiledFunctionExecutionBridgeFixture(FlowGraph graph, CompiledFunctionExecutionBridge bridge) {
    }

    private static final class BlockingReceiptStore implements RuntimeReceiptStore {
        private final DurableRuntimeReceiptStore delegate;
        private final CountDownLatch completed = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch returned = new CountDownLatch(1);

        private BlockingReceiptStore(DurableRuntimeReceiptStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Claim claim(Key key, ContentHash inputHash) {
            return delegate.claim(key, inputHash);
        }

        @Override
        public InvocationLease acquireInvocationLease() {
            return delegate.acquireInvocationLease();
        }

        @Override
        public void reserve(Key key, ContentHash inputHash, RuntimeExecutionProvenance provenance,
                            RuntimeLeaseInput.AuditEvent auditAttempt) {
            delegate.reserve(key, inputHash, provenance, auditAttempt);
        }

        @Override
        public void complete(Key key, RuntimeResult result, RuntimeExecutionProvenance provenance,
                             RuntimeLeaseInput.AuditEvent auditEvent) {
            delegate.complete(key, result, provenance, auditEvent);
            completed.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Receipt completion release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Receipt completion was interrupted", exception);
            } finally {
                returned.countDown();
            }
        }

        @Override
        public void recordPendingAudit(Key key, RuntimeExecutionProvenance provenance,
                                       RuntimeLeaseInput.AuditEvent auditEvent, Throwable failure) {
            delegate.recordPendingAudit(key, provenance, auditEvent, failure);
        }

        @Override
        public void markAuditRecorded(Key key, RuntimeLeaseInput.AuditEvent auditEvent) {
            delegate.markAuditRecorded(key, auditEvent);
        }

        @Override
        public boolean durable() {
            return delegate.durable();
        }

        @Override
        public void releaseProvider(ContractRef<restudio.resync.flow.identity.ProviderId> provider) {
            delegate.releaseProvider(provider);
        }

        @Override
        public void quiesce() throws java.io.IOException {
            delegate.quiesce();
        }
    }
}
