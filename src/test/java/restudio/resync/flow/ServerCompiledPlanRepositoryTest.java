package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.CompiledExecutionStep;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.server.CoreGraphResourceAuthority;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCompiledPlanRepositoryTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("b1f93814-7478-4e60-887c-4be937f23f65"));
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final NodeInstanceId START = NodeInstanceId.deterministic("repository-start");
    private static final NodeInstanceId ALTERNATE = NodeInstanceId.deterministic("repository-alternate");
    private static final RuntimeRegistrySnapshot RUNTIME = RuntimeRegistrySnapshot.empty();
    private static final CatalogRuntimeActivation.ActivationRecord ACTIVATION = new CatalogRuntimeActivation.ActivationRecord(
        emptyCatalog(RUNTIME), RUNTIME);
    private static final CatalogBinding BINDING = new CatalogBinding(ACTIVATION.catalog().generation(),
        ACTIVATION.catalog().contentChecksum(), ACTIVATION.runtime().bindingManifestHash());

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void stopExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void supportedEntrySharesItsResidentTemplateAndClosure() {
        TestAuthority authority = new TestAuthority();
        GraphDocument graph = graph(resource("flow", "connected-entry"), 1, List.of());
        authority.put(graph);
        GraphConnection connection = new GraphConnection(ConnectionId.deterministic("resident-data-edge"),
            new GraphEndpoint(START, PinId.of("value")), new GraphEndpoint(ALTERNATE, PinId.of("input")));
        ServerCompiledPlanRepository repository = repository(authority, source -> new CompiledExecutionPlan(
            UUID.randomUUID(), source.resource(), source.revision(), source.catalogBinding(), source.checksum(),
            List.of(step(START), step(ALTERNATE)), List.of(connection), source.functions(), OpaqueData.empty()));
        TypeExpr text = TypeExpr.named(TypeReference.of("builtin", "string"));
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT, ContractRef.of(OWNER, CapabilityId.of("authorize")),
            RuntimeSemantics.Cancellation.NONE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of("success"), Set.of("failure"), Set.of(),
            new RuntimeFailureContract(text, Set.of("RUNTIME.HANDLER_FAILURE"), Set.of("failure"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(ContractRef.of(OWNER, CapabilityId.of("test")),
            ContractRef.of(OWNER, OperationId.of("execute")), List.of(
                new RuntimeOperationDescriptor.Pin(PinId.of("value"), RuntimeOperationDescriptor.Direction.OUTPUT, text),
                new RuntimeOperationDescriptor.Pin(PinId.of("input"), RuntimeOperationDescriptor.Direction.INPUT, text)), semantics);
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("resident-test"));
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0",
                invocation -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        CompiledExecutionRunner runner = new CompiledExecutionRunner(registry, new RuntimeAuthority("resident-test"));
        Map<NodeInstanceId, CompiledExecutionRunner.ExecutionTemplate> templates = new ConcurrentHashMap<>();
        repository.bindTemplateCompiler((plan, entry) -> templates.computeIfAbsent(entry,
            ignored -> runner.prepare(plan, entry)));
        repository.initialize();
        try (ServerCompiledPlanLease first = (ServerCompiledPlanLease) repository.acquire(target(graph, START));
             ServerCompiledPlanLease second = (ServerCompiledPlanLease) repository.acquire(target(graph, START))) {
            assertSame(templates.get(START), first.executionTemplate().orElseThrow());
            assertSame(first.executionTemplate().orElseThrow(), second.executionTemplate().orElseThrow());
            assertSame(first.functionClosure(), second.functionClosure());
        }
        assertEquals(Set.of(START), templates.keySet());
        assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(graph, ALTERNATE)));
    }

    @Test
    void functionSourcesRemainResidentWithoutGenericExecutionTemplates() {
        TestAuthority authority = new TestAuthority();
        FunctionSourceDocument function = function(resource("function", "typed-boundary"), 1, List.of());
        authority.put(function);
        AtomicInteger templateCompilations = new AtomicInteger();
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.bindTemplateCompiler((plan, entry) -> {
            templateCompilations.incrementAndGet();
            throw new IllegalStateException("Function Plans Must Use The Typed Function Execution Boundary");
        });

        repository.initialize();

        assertEquals(0, templateCompilations.get());
        assertTrue(repository.residentSource(function.graph().resource(), function.graph().revision()).orElseThrow()
            .functionSource() != null);
        CompiledPlanAdmissionException failure = assertThrows(CompiledPlanAdmissionException.class,
            () -> repository.acquire(target(function.graph(), START)));
        assertEquals(CompiledPlanAdmissionException.Reason.ENTRY_NODE_MISSING, failure.reason());
    }

    @Test
    void unexpectedTemplateFailureIsIsolatedFromRepositoryInitialization() {
        TestAuthority authority = new TestAuthority();
        GraphDocument graph = graph(resource("flow", "invalid-template"), 1, List.of());
        authority.put(graph);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.bindTemplateCompiler((plan, entry) -> {
            throw new IllegalStateException("Invalid Template");
        });

        repository.initialize();

        assertEquals(0, repository.cachedPlanCount());
        assertEquals(0, repository.negativePlanCount());
        assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(graph, START)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentReplacementFencesOnlyTheChangedResource(boolean sameResource) throws Exception {
        TestAuthority authority = new TestAuthority();
        GraphDocument first = graph(resource("flow", "parallel-first"), 1, List.of());
        GraphDocument second = sameResource ? first : graph(resource("flow", "parallel-second"), 1, List.of());
        authority.put(first);
        authority.put(second);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServerCompiledPlanRepository repository = repository(authority, source -> {
            if (source.resource().equals(first.resource()) && source.revision() == 2) {
                entered.countDown();
                await(release);
            }
            return plan(source);
        });
        repository.initialize();
        GraphDocument nextFirst = graph(first.resource(), 2, List.of());
        GraphDocument nextSecond = graph(second.resource(), sameResource ? 3 : 2, List.of());
        var firstState = authority.put(nextFirst);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        CompletableFuture<Boolean> firstReplacement = CompletableFuture.supplyAsync(() -> repository.replace(
            CoreGraphMutationEvent.live(nextFirst.resource(), 2, firstState.mutationId(), firstState.payloadChecksum(),
                ResourceActivationState.ACTIVE)), executor);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var secondState = authority.put(nextSecond);
        try {
            assertTrue(repository.replace(CoreGraphMutationEvent.live(nextSecond.resource(), nextSecond.revision(), secondState.mutationId(),
                secondState.payloadChecksum(), ResourceActivationState.ACTIVE)));
        } finally {
            release.countDown();
        }
        assertTrue(firstReplacement.get(5, TimeUnit.SECONDS));
        if (sameResource) {
            assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(nextFirst, START)));
        } else {
            try (ServerCompiledPlanLease firstLease = (ServerCompiledPlanLease) repository.acquire(target(nextFirst, START))) {
                assertEquals(2, firstLease.cacheKey().graphRevision());
            }
        }
        try (ServerCompiledPlanLease secondLease = (ServerCompiledPlanLease) repository.acquire(target(nextSecond, START))) {
            assertEquals(nextSecond.revision(), secondLease.cacheKey().graphRevision());
        }
    }

    @Test
    void concurrentSameKeyAcquisitionsCompileExactlyOnce() throws Exception {
        TestAuthority authority = new TestAuthority();
        GraphDocument graph = graph(resource("flow", "single-flight"), 1, List.of());
        authority.put(graph);
        AtomicInteger compilations = new AtomicInteger();
        ServerCompiledPlanRepository repository = new ServerCompiledPlanRepository(authority, () -> ACTIVATION,
            (source, catalog, runtime) -> {
                compilations.incrementAndGet();
                return plan(source);
            });
        repository.initialize();
        int admittedReads = authority.readCount();
        long admittedValidations = TemporaryLifecycleDiagnostics.hotPathSnapshot().filesystemValidationAttempts();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        executors.add(executor);
        List<CompletableFuture<ServerCompiledPlanLease>> acquisitions = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            acquisitions.add(CompletableFuture.supplyAsync(() ->
                (ServerCompiledPlanLease) repository.acquire(target(graph, START)), executor));
        }
        List<ServerCompiledPlanLease> leases = acquisitions.stream().map(CompletableFuture::join).toList();
        assertEquals(1, compilations.get());
        assertEquals(admittedReads, authority.readCount());
        assertEquals(admittedValidations, TemporaryLifecycleDiagnostics.hotPathSnapshot().filesystemValidationAttempts());
        assertEquals(8, repository.activeLeaseCount());
        leases.forEach(ServerCompiledPlanLease::close);
        assertEquals(0, repository.activeLeaseCount());
    }

    @Test
    void differentEntryNodesShareTheSameCompiledBody() {
        TestAuthority authority = new TestAuthority();
        GraphDocument graph = graph(resource("flow", "entry-independent"), 1, List.of());
        authority.put(graph);
        AtomicInteger compilations = new AtomicInteger();
        ServerCompiledPlanRepository repository = repository(authority, source -> {
            compilations.incrementAndGet();
            return plan(source);
        });
        repository.initialize();
        try (ServerCompiledPlanLease first = (ServerCompiledPlanLease) repository.acquire(target(graph, START));
             ServerCompiledPlanLease second = (ServerCompiledPlanLease) repository.acquire(target(graph, ALTERNATE))) {
            assertSame(first.plan(), second.plan());
            assertEquals(1, compilations.get());
        }
    }

    @Test
    void sameIdAcrossGraphTypesRemainsIsolated() {
        TestAuthority authority = new TestAuthority();
        GraphDocument flow = graph(resource("flow", "shared-id"), 1, List.of());
        GraphDocument command = graph(resource("command", "shared-id"), 1, List.of());
        authority.put(flow);
        authority.put(command);
        AtomicInteger compilations = new AtomicInteger();
        ServerCompiledPlanRepository repository = repository(authority, source -> {
            compilations.incrementAndGet();
            return plan(source);
        });
        repository.initialize();
        try (ServerCompiledPlanLease flowLease = (ServerCompiledPlanLease) repository.acquire(target(flow, START));
             ServerCompiledPlanLease commandLease = (ServerCompiledPlanLease) repository.acquire(target(command, START))) {
            assertNotEquals(flowLease.cacheKey(), commandLease.cacheKey());
            assertEquals(2, compilations.get());
        }
    }

    @Test
    void mutationRetiresLookupWhileAnExistingLeaseSurvives() {
        TestAuthority authority = new TestAuthority();
        GraphDocument first = graph(resource("flow", "retired"), 1, List.of());
        authority.put(first);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        ServerCompiledPlanLease oldLease = (ServerCompiledPlanLease) repository.acquire(target(first, START));
        GraphDocument second = graph(first.resource(), 2, List.of());
        CoreGraphResourceAuthority.CoreGraphResourceState secondState = authority.put(second);
        assertTrue(repository.replace(CoreGraphMutationEvent.live(second.resource(), second.revision(),
            secondState.mutationId(), secondState.payloadChecksum(), ResourceActivationState.ACTIVE)));
        assertTrue(oldLease.active());
        assertEquals(1, oldLease.plan().graphRevision());
        try (ServerCompiledPlanLease newLease = (ServerCompiledPlanLease) repository.acquire(target(second, START))) {
            assertEquals(2, newLease.plan().graphRevision());
        }
        oldLease.close();
        assertFalse(oldLease.active());
    }

    @ParameterizedTest
    @ValueSource(strings = {"command", "flow", "function"})
    void deletedCoreResourceRetiresItsAlreadyCompiledPlan(String type) {
        TestAuthority authority = new TestAuthority();
        ServerResourceLocator resource = resource(type, type + "-deleted");
        FunctionSourceDocument function = "function".equals(type) ? function(resource, 1, List.of()) : null;
        GraphDocument graph = function != null ? function.graph() : graph(resource, 1, List.of());
        if (function != null) {
            authority.put(function);
        } else {
            authority.put(graph);
        }
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        ServerCompiledPlanLease lease = (ServerCompiledPlanLease) repository.acquire(target(graph, START));
        assertEquals(1, repository.cachedPlanCount());

        authority.remove(resource);
        ContentHash priorPayloadHash = function != null ? function.checksum() : graph.checksum();
        assertTrue(repository.invalidate(CoreGraphMutationEvent.deleted(resource, 2L, UUID.randomUUID(),
            priorPayloadHash)));

        assertEquals(0, repository.cachedPlanCount());
        assertTrue(lease.active());
        CompiledPlanAdmissionException failure = assertThrows(CompiledPlanAdmissionException.class,
            () -> repository.acquire(target(graph, START)));
        assertEquals(CompiledPlanAdmissionException.Reason.RESOURCE_MISSING, failure.reason());
        lease.close();
    }

    @Test
    void mutationRetiresPlansThatPinAChangedTransitiveFunction() {
        TestAuthority authority = new TestAuthority();
        ServerResourceLocator firstFunction = resource("function", "retire-first");
        ServerResourceLocator secondFunction = resource("function", "retire-second");
        FunctionSourceDocument second = function(secondFunction, 1, List.of());
        FunctionSourceDocument first = function(firstFunction, 1, List.of(binding(secondFunction, 1)));
        GraphDocument root = graph(resource("flow", "retire-root"), 1, List.of(binding(firstFunction, 1)));
        authority.put(second);
        authority.put(first);
        authority.put(root);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        try (ServerCompiledPlanLease ignored = (ServerCompiledPlanLease) repository.acquire(target(root, START))) {
            assertEquals(3, repository.cachedPlanCount());
            CoreGraphResourceAuthority.CoreGraphResourceState state = authority.state(secondFunction).orElseThrow();
            assertTrue(repository.invalidate(CoreGraphMutationEvent.deleted(secondFunction, 2, UUID.randomUUID(),
                state.payloadChecksum())));
            assertEquals(0, repository.cachedPlanCount());
        }
    }

    @Test
    void deterministicRejectionIsNegativeCachedButUnexpectedFailureRetries() {
        TestAuthority authority = new TestAuthority();
        GraphDocument graph = graph(resource("flow", "negative"), 1, List.of());
        authority.put(graph);
        AtomicInteger rejectedCompilations = new AtomicInteger();
        ServerCompiledPlanRepository rejected = new ServerCompiledPlanRepository(authority, () -> ACTIVATION,
            (source, catalog, runtime) -> {
                rejectedCompilations.incrementAndGet();
                throw new CompiledPlanAdmissionException(CompiledPlanAdmissionException.Reason.COMPILATION_REJECTED,
                    source.resource(), "Rejected");
            });
        rejected.initialize();
        assertThrows(CompiledPlanAdmissionException.class, () -> rejected.acquire(target(graph, START)));
        assertThrows(CompiledPlanAdmissionException.class, () -> rejected.acquire(target(graph, START)));
        assertEquals(1, rejectedCompilations.get());

        AtomicInteger attempts = new AtomicInteger();
        ServerCompiledPlanRepository retryable = new ServerCompiledPlanRepository(authority, () -> ACTIVATION,
            (source, catalog, runtime) -> {
                if (attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("Transient compiler failure");
                }
                return plan(source);
            });
        retryable.initialize();
        assertThrows(CompiledPlanAdmissionException.class, () -> retryable.acquire(target(graph, START)));
        retryable.initialize();
        try (ServerCompiledPlanLease ignored = (ServerCompiledPlanLease) retryable.acquire(target(graph, START))) {
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void unchangedFunctionReconciliationRetainsCallersAndRecoversAfterReadFailure() {
        TestAuthority authority = new TestAuthority();
        FunctionSourceDocument function = function(resource("function", "reconcile-function"), 1, List.of());
        GraphDocument caller = graph(resource("flow", "reconcile-caller"), 1, List.of(binding(function.graph().resource(), 1)));
        authority.put(function);
        authority.put(caller);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        CompiledExecutionPlan original;
        try (var lease = repository.acquire(target(caller, START))) {
            original = lease.plan();
        }
        repository.reconcile(function.graph().resource());
        repository.reconcile(function.graph().resource());
        try (var lease = repository.acquire(target(caller, START))) {
            assertSame(original, lease.plan());
        }
        authority.failOnRead(1);
        assertThrows(IllegalStateException.class, () -> repository.reconcile(function.graph().resource()));
        assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(caller, START)));
        assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(function.graph(), START)));
        assertEquals(0, repository.negativePlanCount());
        authority.failOnRead(-1);
        repository.reconcile(function.graph().resource());
        try (var lease = repository.acquire(target(caller, START))) {
            assertSame(original, lease.plan());
        }
        authority.remove(function.graph().resource());
        repository.reconcile(function.graph().resource());
        assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(caller, START)));
        assertEquals(0, repository.activeLeaseCount());
        repository.close();
    }

    @Test
    void reconciliationRetriesSameMutationAfterPreparationReadFails() {
        TestAuthority authority = new TestAuthority();
        GraphDocument first = graph(resource("flow", "reconcile-retry"), 1, List.of());
        authority.put(first);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        GraphDocument second = graph(first.resource(), 2, List.of());
        authority.put(second);
        authority.failOnRead(2);
        repository.reconcile(second.resource());
        assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(second, START)));
        authority.failOnRead(-1);
        repository.reconcile(second.resource());
        try (var lease = repository.acquire(target(second, START))) {
            assertEquals(2, lease.plan().graphRevision());
        }
        assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(first, START)));
        repository.close();
    }

    @Test
    void cancellationTraitIncludesTheAdmittedFunctionClosure() {
        TestAuthority authority = new TestAuthority();
        ServerResourceLocator functionResource = resource("function", "cancelling");
        FunctionSourceDocument function = function(functionResource, 1, List.of());
        GraphDocument root = graph(resource("flow", "indirect-cancel"), 1, List.of(binding(functionResource, 1)));
        authority.put(function);
        authority.put(root);
        try (ServerCompiledPlanRepository repository = repository(authority, source -> {
            if (!source.resource().equals(functionResource)) {
                return plan(source);
            }
            CompiledExecutionStep step = step(START);
            CompiledExecutionStep cancellation = new CompiledExecutionStep(step.stepId(), START,
                ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("event_cancel")), step.handler(), Map.of(), Map.of(), OpaqueData.empty());
            return new CompiledExecutionPlan(UUID.randomUUID(), source.resource(), source.revision(), source.catalogBinding(),
                source.checksum(), List.of(cancellation), List.of(), source.functions(), OpaqueData.empty());
        })) {
            repository.initialize();
            assertTrue(repository.residentExecution(root.resource()).orElseThrow().synchronousEventWindow());
        }
    }

    @Test
    void transitiveFunctionClosureRemainsPinnedAfterInvalidation() {
        TestAuthority authority = new TestAuthority();
        ServerResourceLocator firstFunction = resource("function", "first");
        ServerResourceLocator secondFunction = resource("function", "second");
        FunctionBinding secondBinding = binding(secondFunction, 1);
        FunctionSourceDocument second = function(secondFunction, 1, List.of());
        FunctionSourceDocument first = function(firstFunction, 1, List.of(secondBinding));
        GraphDocument root = graph(resource("flow", "closure"), 1, List.of(binding(firstFunction, 1)));
        authority.put(second);
        authority.put(first);
        authority.put(root);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        ServerCompiledPlanLease lease = (ServerCompiledPlanLease) repository.acquire(target(root, START));
        assertEquals(Set.of(firstFunction, secondFunction), lease.functionClosure().keySet());
        try (ServerCompiledPlanLease secondLease = (ServerCompiledPlanLease) repository.acquire(target(root, START))) {
            assertSame(lease.functionClosure(), secondLease.functionClosure());
            assertThrows(UnsupportedOperationException.class, () -> secondLease.functionClosure().clear());
        }
        CoreGraphResourceAuthority.CoreGraphResourceState secondState = authority.state(secondFunction).orElseThrow();
        assertTrue(repository.invalidate(CoreGraphMutationEvent.deleted(secondFunction, 2, UUID.randomUUID(),
            secondState.payloadChecksum())));
        assertEquals(1, lease.requirePinned(secondFunction).plan().graphRevision());
        lease.close();
    }

    @Test
    void dependencyPlanCacheIsSharedAcrossRootsAndDirectAcquisition() {
        TestAuthority authority = new TestAuthority();
        ServerResourceLocator functionResource = resource("function", "shared-dependency");
        FunctionSourceDocument function = function(functionResource, 1, List.of());
        GraphDocument firstRoot = graph(resource("flow", "shared-root-a"), 1, List.of(binding(functionResource, 1)));
        GraphDocument secondRoot = graph(resource("flow", "shared-root-b"), 1, List.of(binding(functionResource, 1)));
        authority.put(function);
        authority.put(firstRoot);
        authority.put(secondRoot);
        Map<ServerResourceLocator, AtomicInteger> compilations = new ConcurrentHashMap<>();
        ServerCompiledPlanRepository repository = repository(authority, source -> {
            compilations.computeIfAbsent(source.resource(), ignored -> new AtomicInteger()).incrementAndGet();
            return plan(source);
        });
        repository.initialize();
        try (ServerCompiledPlanLease first = (ServerCompiledPlanLease) repository.acquire(target(firstRoot, START));
             ServerCompiledPlanLease second = (ServerCompiledPlanLease) repository.acquire(target(secondRoot, START));
             ServerCompiledPlanLease direct = (ServerCompiledPlanLease) repository.acquire(target(function.graph(), START))) {
            assertSame(first.requirePinned(functionResource).plan(), second.requirePinned(functionResource).plan());
            assertSame(first.requirePinned(functionResource).plan(), direct.plan());
            assertEquals(1, compilations.get(functionResource).get());
        }
    }

    @Test
    void concurrentRootsCompileTheirSharedDependencyOnce() throws Exception {
        TestAuthority authority = new TestAuthority();
        ServerResourceLocator functionResource = resource("function", "concurrent-dependency");
        FunctionSourceDocument function = function(functionResource, 1, List.of());
        GraphDocument firstRoot = graph(resource("flow", "concurrent-root-a"), 1, List.of(binding(functionResource, 1)));
        GraphDocument secondRoot = graph(resource("flow", "concurrent-root-b"), 1, List.of(binding(functionResource, 1)));
        authority.put(function);
        authority.put(firstRoot);
        authority.put(secondRoot);
        AtomicInteger dependencyCompilations = new AtomicInteger();
        ServerCompiledPlanRepository repository = repository(authority, source -> {
            if (source.resource().equals(functionResource)) {
                dependencyCompilations.incrementAndGet();
            }
            return plan(source);
        });
        repository.initialize();
        int admittedReads = authority.readCount();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executors.add(executor);
        CompletableFuture<ServerCompiledPlanLease> first = CompletableFuture.supplyAsync(() ->
            (ServerCompiledPlanLease) repository.acquire(target(firstRoot, START)), executor);
        CompletableFuture<ServerCompiledPlanLease> second = CompletableFuture.supplyAsync(() ->
            (ServerCompiledPlanLease) repository.acquire(target(secondRoot, START)), executor);
        try (ServerCompiledPlanLease firstLease = first.join(); ServerCompiledPlanLease secondLease = second.join()) {
            assertSame(firstLease.requirePinned(functionResource).plan(), secondLease.requirePinned(functionResource).plan());
            assertEquals(1, dependencyCompilations.get());
            assertEquals(admittedReads, authority.readCount());
        }
    }

    @Test
    void sameRevisionFunctionCycleIsRejectedWithoutDeadlock() {
        TestAuthority authority = new TestAuthority();
        ServerResourceLocator firstResource = resource("function", "cycle-a");
        ServerResourceLocator secondResource = resource("function", "cycle-b");
        FunctionSourceDocument first = function(firstResource, 1, List.of(binding(secondResource, 1)));
        FunctionSourceDocument second = function(secondResource, 1, List.of(binding(firstResource, 1)));
        authority.put(first);
        authority.put(second);
        Map<ServerResourceLocator, AtomicInteger> compilations = new ConcurrentHashMap<>();
        ServerCompiledPlanRepository repository = repository(authority, source -> {
            compilations.computeIfAbsent(source.resource(), ignored -> new AtomicInteger()).incrementAndGet();
            return plan(source);
        });
        repository.initialize();
        CompiledPlanAdmissionException failure = assertThrows(CompiledPlanAdmissionException.class,
            () -> repository.acquire(target(first.graph(), START)));
        assertEquals(CompiledPlanAdmissionException.Reason.FUNCTION_DEPENDENCY_CYCLE, failure.reason());
        assertEquals(1, compilations.get(firstResource).get());
        assertEquals(1, compilations.get(secondResource).get());
    }

    @Test
    void mutationEpochChangeRejectsAnInFlightAdmission() throws Exception {
        TestAuthority authority = new TestAuthority();
        GraphDocument first = graph(resource("flow", "epoch"), 1, List.of());
        authority.put(first);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServerCompiledPlanRepository repository = repository(authority, source -> {
            entered.countDown();
            await(release);
            return plan(source);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        CompletableFuture<Void> admission = CompletableFuture.runAsync(repository::initialize, executor);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        GraphDocument second = graph(first.resource(), 2, List.of());
        CoreGraphResourceAuthority.CoreGraphResourceState state = authority.put(second);
        repository.invalidate(CoreGraphMutationEvent.live(second.resource(), second.revision(), state.mutationId(),
            state.payloadChecksum(), ResourceActivationState.ACTIVE));
        release.countDown();
        CompletionException completion = assertThrows(CompletionException.class, admission::join);
        assertTrue(completion.getCause() instanceof IllegalStateException);
    }

    @Test
    void transientFinalAuthorityFailureRemainsRetryable() {
        TestAuthority authority = new TestAuthority();
        GraphDocument graph = graph(resource("flow", "final-authority"), 1, List.of());
        authority.put(graph);
        authority.failOnRead(2);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(graph, START)));
        authority.failOnRead(-1);
        repository.initialize();
        try (ServerCompiledPlanLease lease = (ServerCompiledPlanLease) repository.acquire(target(graph, START))) {
            assertEquals(graph.resource(), lease.plan().graph());
        }
    }

    @Test
    void authorityCannotRedirectARequestToAnotherTypedLocator() {
        TestAuthority authority = new TestAuthority();
        GraphDocument requested = graph(resource("flow", "typed-redirect"), 1, List.of());
        GraphDocument redirected = graph(resource("command", "typed-redirect"), 1, List.of());
        authority.put(requested);
        CoreGraphResourceAuthority.CoreGraphResourceState redirectedState = authority.put(redirected);
        authority.redirect(redirectedState);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        CompiledPlanAdmissionException failure = assertThrows(CompiledPlanAdmissionException.class,
            () -> repository.acquire(target(requested, START)));
        assertEquals(CompiledPlanAdmissionException.Reason.RESOURCE_IDENTITY_MISMATCH, failure.reason());
    }

    @Test
    void conflictingFunctionProvenanceFailsClosed() {
        TestAuthority authority = new TestAuthority();
        ServerResourceLocator firstFunction = resource("function", "collision-a");
        ServerResourceLocator secondFunction = resource("function", "collision-b");
        FunctionSourceDocument first = function(firstFunction, 1, List.of(binding(secondFunction, 1)));
        FunctionSourceDocument second = function(secondFunction, 1, List.of(binding(firstFunction, 2)));
        GraphDocument root = graph(resource("flow", "collision-root"), 1, List.of(binding(firstFunction, 1)));
        authority.put(first);
        authority.put(second);
        authority.put(root);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        CompiledPlanAdmissionException failure = assertThrows(CompiledPlanAdmissionException.class,
            () -> repository.acquire(target(root, START)));
        assertEquals(CompiledPlanAdmissionException.Reason.FUNCTION_SOURCE_PROVENANCE_COLLISION, failure.reason());
    }

    @Test
    void closeRejectsNewAdmissionsWithoutRevokingExistingLease() {
        TestAuthority authority = new TestAuthority();
        GraphDocument graph = graph(resource("flow", "close"), 1, List.of());
        authority.put(graph);
        ServerCompiledPlanRepository repository = repository(authority, ServerCompiledPlanRepositoryTest::plan);
        repository.initialize();
        ServerCompiledPlanLease lease = (ServerCompiledPlanLease) repository.acquire(target(graph, START));
        repository.close();
        assertTrue(lease.active());
        assertThrows(CompiledPlanAdmissionException.class, () -> repository.acquire(target(graph, START)));
        lease.close();
        assertEquals(0, repository.activeLeaseCount());
    }

    private static ServerCompiledPlanRepository repository(TestAuthority authority,
                                                           Function<GraphDocument, CompiledExecutionPlan> compiler) {
        return new ServerCompiledPlanRepository(authority, () -> ACTIVATION,
            (graph, catalog, runtime) -> compiler.apply(graph));
    }

    private static CatalogSnapshot emptyCatalog(RuntimeRegistrySnapshot runtime) {
        CatalogVersion version = new CatalogVersion(1, 0);
        String canonical = CatalogCanonicalizer.canonicalSnapshotContent(1, version, List.of(), Set.of(), List.of(),
            runtime.bindingManifestHash());
        return new CatalogSnapshot(1, version,
            CatalogCanonicalizer.contentChecksum(1, version, List.of(), Set.of(), List.of()),
            runtime.bindingManifestHash(), Set.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), canonical,
            runtime.bindingManifestHash());
    }

    private static ExecutionTarget target(GraphDocument graph, NodeInstanceId start) {
        return new ExecutionTarget(graph.resource(), start, graph.revision(), graph.catalogBinding());
    }

    private static GraphDocument graph(ServerResourceLocator resource, long revision, List<FunctionBinding> functions) {
        return new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(), List.of(), List.of(),
            List.of(), functions, OpaqueData.empty());
    }

    private static FunctionSourceDocument function(ServerResourceLocator resource, long revision,
                                                   List<FunctionBinding> functions) {
        GraphDocument graph = graph(resource, revision, functions);
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(revision),
            List.of(), List.of());
        return new FunctionSourceDocument(signature, graph);
    }

    private static FunctionBinding binding(ServerResourceLocator function, long revision) {
        return new FunctionBinding(function, revision, List.of(), List.of());
    }

    private static CompiledExecutionPlan plan(GraphDocument graph) {
        return new CompiledExecutionPlan(UUID.nameUUIDFromBytes(graph.checksum().canonicalText().getBytes()), graph.resource(),
            graph.revision(), graph.catalogBinding(), graph.checksum(), List.of(step(START), step(ALTERNATE)), List.of(),
            graph.functions(), OpaqueData.empty());
    }

    private static CompiledExecutionStep step(NodeInstanceId node) {
        CatalogNodeDescriptor.Handler handler = new CatalogNodeDescriptor.Handler(
            ContractRef.of(OWNER, CapabilityId.of("test")), ContractRef.of(OWNER, OperationId.of("execute")));
        return new CompiledExecutionStep(UUID.nameUUIDFromBytes(node.canonicalText().getBytes()), node,
            ContractRef.of(OWNER, NodeId.of("test")), handler, Map.of(), Map.of(), OpaqueData.empty());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class TestAuthority implements CoreGraphResourceAuthority {
        private final CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        private final Map<ServerResourceLocator, CoreGraphResourceState> states = new ConcurrentHashMap<>();
        private final AtomicInteger reads = new AtomicInteger();
        private volatile int failedRead = -1;
        private volatile CoreGraphResourceState redirected;

        private void failOnRead(int read) {
            reads.set(0);
            failedRead = read;
        }

        private int readCount() {
            return reads.get();
        }

        private void redirect(CoreGraphResourceState state) {
            redirected = state;
        }

        private void remove(ServerResourceLocator resource) {
            states.remove(resource);
        }

        private CoreGraphResourceState put(GraphDocument graph) {
            UUID mutationId = UUID.randomUUID();
            byte[] bytes = boundary.encode(graph, new CoreGraphStorageBoundary.AssetMetadata(
                graph.resource().resourceType().value(), graph.revision(), mutationId, ResourceActivationState.ACTIVE),
                graph.resource());
            CoreGraphResourceState state = CoreGraphResourceState.live(boundary.decode(bytes, graph.resource()));
            states.put(graph.resource(), state);
            return state;
        }

        private CoreGraphResourceState put(FunctionSourceDocument source) {
            GraphDocument graph = source.graph();
            UUID mutationId = UUID.randomUUID();
            byte[] bytes = boundary.encode(source, new CoreGraphStorageBoundary.AssetMetadata(
                graph.resource().resourceType().value(), graph.revision(), mutationId, ResourceActivationState.ACTIVE),
                graph.resource());
            CoreGraphResourceState state = CoreGraphResourceState.live(boundary.decode(bytes, graph.resource()));
            states.put(graph.resource(), state);
            return state;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
            return state(resource).map(CoreGraphResourceState::envelope);
        }

        @Override
        public List<CoreGraphResourceState> list(String type) {
            return states.values().stream().filter(state -> state.resource().resourceType().value().equals(type)).toList();
        }

        @Override
        public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
            if (reads.incrementAndGet() == failedRead) {
                throw new IllegalStateException("Transient authority failure");
            }
            return Optional.ofNullable(redirected != null ? redirected : states.get(resource));
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                     UUID mutationId, long expectedRevision,
                                                     ContentHash payloadChecksum) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                                   long expectedRevision,
                                                                   ContentHash payloadChecksum) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                         ResourceActivationState activationState, UUID mutationId,
                                                         long expectedRevision,
                                                         ContentHash payloadChecksum) {
            throw new UnsupportedOperationException();
        }
    }
}
