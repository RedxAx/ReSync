package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.function.CompiledFunctionRunner;
import restudio.resync.flow.function.FunctionExecutionRequest;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionOutputMap;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionResult;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceMaterializer;
import restudio.resync.flow.function.TypedFunctionCapabilitySet;
import restudio.resync.flow.function.TypedFunctionCompiler;
import restudio.resync.flow.function.TypedFunctionNodeCapability;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.catalog.CatalogVersion;
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
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.DurableRuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledFunctionExecutionBridgeTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(new OwnerId("resync"), ResourceTypeId.of("function")), "typed-function");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64)));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "text"));
    private static final FunctionParameterId INPUT = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final FunctionParameterId OUTPUT = FunctionParameterId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final FunctionParameterId SECOND_INPUT = FunctionParameterId.of(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    private static final FunctionParameterId SECOND_OUTPUT = FunctionParameterId.of(UUID.fromString("66666666-6666-4666-8666-666666666666"));
    private static final NodeInstanceId NODE = NodeInstanceId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(new OwnerId("typed"), CapabilityId.of("function-test"));
    private static final ContractRef<OperationId> OPERATION = ContractRef.of(new OwnerId("typed"), OperationId.of("copy"));
    private static final PinId VALUE = PinId.of("value");

    @Test
    void legacyOutputAdapterPreservesTypedLocatorsAndOptionalNulls() {
        TypeExpr reference = TypeExpr.resource(TypeReference.of("resync", "function"));
        TypeExpr optional = TypeExpr.optional(reference);
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(RESOURCE), FunctionRevision.of(4),
            List.of(), List.of(new FunctionParameterContract(OUTPUT, optional)));
        FlowGraph graph = new FlowGraph("typed-function", Map.of(), List.of(), List.of());
        graph.setFunction(true);
        graph.setFunctionOutputs(List.of(new FlowGraph.FunctionParameter(OUTPUT, "reference", FlowDataType.ANY)));
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(null, null);
        FunctionResult locator = FunctionResult.success(signature,
            new FunctionOutputMap(Map.of(OUTPUT, TypedValue.locator(optional, RESOURCE))), 0);
        FunctionResult absent = FunctionResult.success(signature,
            new FunctionOutputMap(Map.of(OUTPUT, TypedValue.nullValue(optional))), 0);

        assertEquals(RESOURCE, bridge.outputsForLegacyGraph(graph, locator).get("reference"));
        Map<String, Object> outputs = bridge.outputsForLegacyGraph(graph, absent);
        assertTrue(outputs.containsKey("reference"));
        assertEquals(null, outputs.get("reference"));

        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), RESOURCE, 4, BINDING,
            Set.of(), List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
        FunctionSourceDocument source = new FunctionSourceDocument(signature, document);
        assertEquals(RESOURCE, bridge.outputsForSource(source, locator).get(OUTPUT.canonicalText()));
        Map<String, Object> sourceOutputs = bridge.outputsForSource(source, absent);
        assertTrue(sourceOutputs.containsKey(OUTPUT.canonicalText()));
        assertEquals(null, sourceOutputs.get(OUTPUT.canonicalText()));
        FunctionSignature named = new FunctionSignature(signature.function(), signature.revision(), List.of(),
            List.of(new FunctionParameterContract(OUTPUT, optional, true, null, Map.of("name", "reference"))));
        assertThrows(IllegalArgumentException.class,
            () -> bridge.outputsForSource(new FunctionSourceDocument(named, document), locator));
        assertEquals(RESOURCE, bridge.outputsForSource(new FunctionSourceDocument(named, document),
            FunctionResult.success(named, locator.outputs(), 0)).get("reference"));
    }

    @Test
    void injectedTypedSourceAndCapabilitiesExecuteThroughExplicitCompiledEntryPoint() {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        try {
            FunctionResult result = executor.executeCompiledFunction(fixture.request(fixture.capabilities().fingerprint()),
                bridge).join();

            assertTrue(result.successful(), result.diagnostics()::toString);
            assertEquals(TypedValue.value(TEXT, "hello"), result.outputs().value(OUTPUT));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void configuredBridgeIsTheOnlyExecutorOwnedCompiledFunctionEntry() {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        try {
            executor.configureCompiledFunctionBridge(bridge);
            FunctionResult result = executor.executeCompiledFunction(fixture.request(fixture.capabilities().fingerprint())).join();

            assertTrue(result.successful(), result.diagnostics()::toString);
            assertEquals(TypedValue.value(TEXT, "hello"), result.outputs().value(OUTPUT));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void missingConfiguredBridgeFailsClosedWithoutLegacyAdmission() {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        try {
            CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> executor.executeCompiledFunction(fixture.request(fixture.capabilities().fingerprint())).join());

            assertTrue(failure.getCause() instanceof FlowExecutor.FlowExecutionException);
            assertEquals("FUNCTION_COMPILED_EXECUTION_UNAVAILABLE",
                ((FlowExecutor.FlowExecutionException) failure.getCause()).getCode());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void missingProviderAndBindingFailClosed() {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        try {
            FunctionResult missingProvider = executor.executeCompiledFunction(
                fixture.request(fixture.capabilities().fingerprint()),
                new CompiledFunctionExecutionBridge(null, source -> Optional.of(fixture.capabilities()))).join();
            assertTrue(missingProvider.failed());
            assertEquals("FUNCTION.PROVIDER_MISSING", missingProvider.diagnostics().getFirst().code());

            FunctionResult missingBinding = executor.executeCompiledFunction(
                new CompiledFunctionExecutionRequest(fixture.execution(), null, fixture.capabilities().fingerprint()),
                new CompiledFunctionExecutionBridge(
                    (function, revision) -> Optional.of(fixture.source()),
                    source -> Optional.of(fixture.capabilities()))).join();
            assertTrue(missingBinding.failed());
            assertEquals("FUNCTION.BINDING_MISSING", missingBinding.diagnostics().getFirst().code());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void revisionAndFingerprintMismatchesFailClosed() {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        FunctionSourceDocument wrongRevision = fixture(new FunctionRevision(5), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT))).source();
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(wrongRevision),
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        try {
            FunctionResult revisionMismatch = executor.executeCompiledFunction(
                fixture.request(fixture.capabilities().fingerprint()), bridge).join();
            assertTrue(revisionMismatch.failed());
            assertEquals("FUNCTION.RESOLVER_SOURCE_MISMATCH", revisionMismatch.diagnostics().getFirst().code());

            FunctionResult fingerprintMismatch = executor.executeCompiledFunction(
                fixture.request(new ContentHash("f".repeat(64))),
                new CompiledFunctionExecutionBridge(
                    (function, revision) -> Optional.of(fixture.source()),
                    source -> Optional.of(fixture.capabilities()))).join();
            assertTrue(fingerprintMismatch.failed());
            assertEquals("FUNCTION.FINGERPRINT_MISMATCH", fingerprintMismatch.diagnostics().getFirst().code());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void typedFailureNeverFallsBackToLegacyHandlers() {
        Fixture fixture = fixture(new FunctionRevision(4), node -> {
            throw new IllegalStateException("legacy mapping is forbidden");
        });
        AtomicBoolean legacyInvoked = new AtomicBoolean();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("source", (context, node) -> legacyInvoked.set(true));
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            new TypedFunctionCompiler(), new CompiledFunctionRunner(),
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        try {
            FunctionResult result = executor.executeCompiledFunction(fixture.request(fixture.capabilities().fingerprint()), bridge).join();

            assertTrue(result.failed());
            assertEquals("FUNCTION.COMPILER_STEP_FAILURE", result.diagnostics().getFirst().code());
            assertFalse(legacyInvoked.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void compiledFunctionExecutionHoldsAdmissionAcrossBridgeInvocation() throws Exception {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return Optional.of(fixture.source());
            },
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        CompletableFuture<FunctionResult> running = CompletableFuture.supplyAsync(() ->
            executor.executeCompiledFunction(fixture.request(fixture.capabilities().fingerprint()), bridge).join());
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            try (FlowExecutor.AdmissionFence fence = executor.fenceAdmissions()) {
                CompletableFuture<Void> drained = fence.whenDrained();
                assertFalse(drained.isDone());

                CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                    () -> executor.executeCompiledFunction(fixture.request(fixture.capabilities().fingerprint()), bridge).join());
                FlowExecutor.FlowExecutionException executionFailure =
                    org.junit.jupiter.api.Assertions.assertInstanceOf(FlowExecutor.FlowExecutionException.class, failure.getCause());
                assertEquals("EXECUTION_FENCED", executionFailure.getCode());
                assertFalse(running.isDone());

                release.countDown();
                FunctionResult result = running.get(5, TimeUnit.SECONDS);
                assertTrue(result.successful(), result.diagnostics()::toString);
                drained.get(5, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void compiledFunctionExecutionReleasesAdmissionAfterBridgeFailure() {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> {
                throw new IllegalStateException("source provider failure");
            },
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        try {
            FunctionResult result = executor.executeCompiledFunction(
                fixture.request(fixture.capabilities().fingerprint()), bridge).join();
            assertTrue(result.failed());
            assertEquals("FUNCTION.PROVIDER_FAILURE", result.diagnostics().getFirst().code());
            try (FlowExecutor.AdmissionFence fence = executor.fenceAdmissions()) {
                assertTrue(fence.whenDrained().isDone());
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void authenticatedFunctionDurabilityReplaysAcrossUpgradesAndDeniesAnotherActor() {
        AtomicInteger executions = new AtomicInteger();
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> {
            executions.incrementAndGet();
            return frame.withOutput(OUTPUT, frame.input(INPUT));
        });
        RuntimeAuthority authority = new RuntimeAuthority("function-authority");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal first = principals.issueAuthenticatedClient("client-a");
        RuntimePrincipal second = principals.issueAuthenticatedClient("client-b");
        RuntimeReceiptStore receipts = RuntimeReceiptStore.inMemory(true);
        List<restudio.resync.flow.runtime.RuntimeLeaseInput.AuditEvent> audits = new CopyOnWriteArrayList<>();
        RuntimeAuditBoundary audit = recordingAudit(audits);
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        executor.configureCompiledFunctionBridge(bridge);
        executor.configureCompiledFunctionRuntime(authority, principals, receipts, SERVER, first, audit);
        try {
            UUID invocationId = UUID.fromString("66666666-6666-4666-8666-666666666666");
            CompiledFunctionExecutionRequest request = authenticatedRequest(fixture, authority, first, invocationId, "session-a");
            FunctionResult firstResult = executor.executeCompiledFunction(request, bridge).join();
            FunctionResult replay = executor.executeCompiledFunction(request, bridge).join();
            FunctionResult upgradedReplay = executor.executeCompiledFunction(
                authenticatedRequest(fixture, authority, first, invocationId, "session-a",
                    new ContentHash("f".repeat(64))), bridge).join();

            assertTrue(firstResult.successful(), firstResult.diagnostics()::toString);
            assertTrue(replay.successful(), replay.diagnostics()::toString);
            assertTrue(upgradedReplay.successful(), upgradedReplay.diagnostics()::toString);
            assertEquals(1, executions.get());
            assertEquals(1, audits.size());

            CompletionException denied = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> executor.executeCompiledFunction(
                    authenticatedRequest(fixture, authority, second, invocationId, "session-b"), bridge).join());
            assertEquals("FUNCTION_AUTHORIZATION_DENIED",
                org.junit.jupiter.api.Assertions.assertInstanceOf(FlowExecutor.FlowExecutionException.class, denied.getCause()).getCode());
            assertEquals(1, executions.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void authenticatedFunctionAuditRecoversAfterRestart(@TempDir Path temporary) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> {
            executions.incrementAndGet();
            return frame.withOutput(OUTPUT, frame.input(INPUT));
        });
        RuntimeAuthority authority = new RuntimeAuthority("function-recovery-authority");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal principal = principals.issueAuthenticatedClient("client-a");
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(temporary);
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        executor.configureCompiledFunctionBridge(bridge);
        executor.configureCompiledFunctionRuntime(authority, principals, receipts, SERVER, principal,
            RuntimeAuditBoundary.unavailable());
        CompiledFunctionExecutionRequest request = authenticatedRequest(fixture, authority, principal,
            UUID.fromString("77777777-7777-4777-8777-777777777777"), "session-a");
        try {
            assertTrue(executor.executeCompiledFunction(request, bridge).join().successful());
            assertEquals(1, executions.get());
            assertEquals(1, receipts.pendingAuditCount());
        } finally {
            executor.shutdown();
        }

        receipts.quiesce();
        DurableRuntimeReceiptStore recovered = new DurableRuntimeReceiptStore(temporary);
        List<restudio.resync.flow.runtime.RuntimeLeaseInput.AuditEvent> audits = new CopyOnWriteArrayList<>();
        assertEquals(1, recovered.retryPendingAudits(recordingAudit(audits)));
        assertEquals(0, recovered.pendingAuditCount());

        FlowExecutor replayExecutor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        replayExecutor.configureCompiledFunctionBridge(bridge);
        replayExecutor.configureCompiledFunctionRuntime(authority, principals, recovered, SERVER, principal,
            recordingAudit(audits));
        try {
            assertTrue(replayExecutor.executeCompiledFunction(request, bridge).join().successful());
            assertEquals(1, executions.get());
        } finally {
            replayExecutor.shutdown();
        }
    }

    @Test
    void authenticatedFunctionReceiptLeaseFencesQuiesceAndRebind(@TempDir Path temporary) throws Exception {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        RuntimeAuthority authority = new RuntimeAuthority("function-lease-authority");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal principal = principals.issueAuthenticatedClient("client-a");
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(temporary);
        CountDownLatch auditEntered = new CountDownLatch(1);
        CountDownLatch auditRelease = new CountDownLatch(1);
        RuntimeAuditBoundary blockingAudit = new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit policy) {
                return true;
            }

            @Override
            public void record(restudio.resync.flow.runtime.RuntimeLeaseInput.AuditEvent event) {
                auditEntered.countDown();
                try {
                    auditRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        };
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        executor.configureCompiledFunctionBridge(bridge);
        executor.configureCompiledFunctionRuntime(authority, principals, receipts, SERVER, principal, blockingAudit);
        CompiledFunctionExecutionRequest request = authenticatedRequest(fixture, authority, principal,
            UUID.fromString("88888888-8888-4888-8888-888888888888"), "session-a");
        CompletableFuture<FunctionResult> running = CompletableFuture.supplyAsync(
            () -> executor.executeCompiledFunction(request, bridge).join());
        try {
            assertTrue(auditEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> quiescing = CompletableFuture.runAsync(() -> {
                try {
                    receipts.quiesce();
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            Thread.sleep(50);
            assertFalse(quiescing.isDone());
            auditRelease.countDown();
            assertTrue(running.get(5, TimeUnit.SECONDS).successful());
            quiescing.get(5, TimeUnit.SECONDS);

            Path candidate = temporary.resolve("candidate");
            Path candidateFile = candidate.resolve(DurableRuntimeReceiptStore.DIRECTORY)
                .resolve(DurableRuntimeReceiptStore.FILE_NAME);
            Files.createDirectories(candidateFile.getParent());
            Files.copy(receipts.root(), candidateFile, StandardCopyOption.REPLACE_EXISTING);
            receipts.rebind(candidate);
            receipts.healthCheck();
        } finally {
            auditRelease.countDown();
            executor.shutdown();
        }
    }

    @Test
    void authenticatedLegacyFunctionCallerUsesDurableContext() {
        AtomicInteger executions = new AtomicInteger();
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> {
            executions.incrementAndGet();
            return frame.withOutput(OUTPUT, frame.input(INPUT));
        });
        RuntimeAuthority authority = new RuntimeAuthority("function-caller-authority");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal principal = principals.issueSystem("server");
        RuntimeReceiptStore receipts = RuntimeReceiptStore.inMemory(true);
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        executor.configureCompiledFunctionBridge(bridge);
        executor.configureCompiledFunctionRuntime(authority, principals, receipts, SERVER, principal,
            recordingAudit(new CopyOnWriteArrayList<>()));
        FlowExecutor.FunctionInvocationContext context = new FlowExecutor.FunctionInvocationContext(principal,
            restudio.resync.flow.identity.CorrelationId.of(UUID.fromString("99999999-9999-4999-8999-999999999999")),
            new CompiledRuntimeContext(null, null, Map.of(), principal),
            restudio.resync.flow.runtime.RuntimeExecutionContext.NO_DEADLINE, "session-server");
        FlowGraph graph = new FlowGraph();
        graph.setId("typed-function");
        graph.setFunction(true);
        graph.setResourceType("function");
        graph.setResourceRevision(4);
        graph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter(INPUT, "value", FlowDataType.STRING)));
        graph.setFunctionOutputs(List.of(new FlowGraph.FunctionParameter(OUTPUT, "result", FlowDataType.STRING)));
        try {
            Map<String, Object> first = executor.executeFunction(graph, null, null, Map.of("value", "hello"), Map.of(), context).join();
            Map<String, Object> replay = executor.executeFunction(graph, null, null, Map.of("value", "hello"), Map.of(), context).join();
            assertEquals("hello", first.get("result"));
            assertEquals("hello", replay.get("result"));
            assertEquals(1, executions.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void canonicalizesLegacyResourceEventVariablesBeforeCoreRequestConstruction() {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));
        RuntimeAuthority authority = new RuntimeAuthority("function-context-authority");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal principal = principals.issueSystem("server");

        CompiledFunctionExecutionRequest request = bridge.requestForLegacyGraph(fixture.graph(), null, null,
            Map.of("value", "hello"), Map.of(
                "event.resource", new FlowResourceReference("quest", "main", "fixture"),
                "event.message", "ready"), SERVER, authority, principal,
            CorrelationId.deterministic("function-context"),
            new CompiledRuntimeContext(null, null, Map.of(), principal), RuntimeExecutionContext.NO_DEADLINE, null, null);

        Map<?, ?> eventVariables = (Map<?, ?>) request.execution().attributes().get("eventVariables");
        Map<?, ?> resource = (Map<?, ?>) eventVariables.get("event.resource");
        Map<?, ?> locator = (Map<?, ?>) resource.get("value");
        assertEquals(SERVER.canonicalText(), locator.get("serverId"));
        assertEquals("ready", ((Map<?, ?>) eventVariables.get("event.message")).get("value"));
    }

    @Test
    void rejectsLegacyResourceEventVariablesWithWrongServerIdentity() {
        Fixture fixture = fixture(new FunctionRevision(4), node -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT)));
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));
        RuntimeAuthority authority = new RuntimeAuthority("function-context-authority");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal principal = principals.issueSystem("server");
        ServerId otherServer = new ServerId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        FlowResourceReference wrongServer = new FlowResourceReference("quest", "main", "fixture", true,
            Map.of("serverId", otherServer.canonicalText()));

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> bridge.requestForLegacyGraph(fixture.graph(), null, null, Map.of("value", "hello"),
                Map.of("event.resource", wrongServer), SERVER, authority, principal,
                CorrelationId.deterministic("function-context-wrong-server"),
                new CompiledRuntimeContext(null, null, Map.of(), principal), RuntimeExecutionContext.NO_DEADLINE, null, null));

        assertEquals("Function Event Variables Are Unsupported: RESOURCE_REFERENCE_SERVER_MISMATCH", failure.getMessage());
    }

    @Test
    void legacyBoundaryParameterNamesResolveThroughExactIdsAfterReorderAndRename() {
        Fixture fixture = multiParameterFixture();
        FlowGraph graph = fixture.graph();
        graph.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter(SECOND_INPUT, "Renamed Second", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(INPUT, "Renamed First", FlowDataType.STRING)));
        graph.setFunctionOutputs(List.of(
            new FlowGraph.FunctionParameter(SECOND_OUTPUT, "Renamed Output Second", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(OUTPUT, "Renamed Output First", FlowDataType.STRING)));
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(
            (function, revision) -> Optional.of(fixture.source()),
            source -> Optional.of(fixture.capabilities()));

        RuntimeAuthority authority = new RuntimeAuthority("parameter-identity-authority");
        RuntimePrincipal principal = new RuntimePrincipalAuthority(authority).issueSystem("system");
        CompiledFunctionExecutionRequest request = bridge.requestForLegacyGraph(graph, null, null,
            Map.of("Renamed First", "first", "Renamed Second", "second"), Map.of(), SERVER, authority, principal,
            CorrelationId.deterministic("parameter-identity"),
            new CompiledRuntimeContext(null, null, Map.of(), principal), RuntimeExecutionContext.NO_DEADLINE, null, null);

        assertEquals(TypedValue.value(TEXT, "first"), request.execution().inputs().value(INPUT));
        assertEquals(TypedValue.value(TEXT, "second"), request.execution().inputs().value(SECOND_INPUT));
        FunctionResult result = FunctionResult.success(fixture.signature(), new FunctionOutputMap(Map.of(
            SECOND_OUTPUT, TypedValue.value(TEXT, "second-output"),
            OUTPUT, TypedValue.value(TEXT, "first-output"))), 0);
        assertEquals(Map.of("Renamed Output First", "first-output", "Renamed Output Second", "second-output"),
            bridge.outputsForLegacyGraph(graph, result));
    }

    private static Fixture fixture(FunctionRevision revision, restudio.resync.flow.function.TypedFunctionNodeCompiler compiler) {
        GraphNode node = new GraphNode(NODE, ContractRef.of(new OwnerId("typed"), NodeId.of("source")), 1,
            Map.of(VALUE, new PinValue(VALUE, TypedValue.value(TEXT, "hello"))));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), RESOURCE, revision.value(), BINDING,
            Set.of(), List.of(node), List.of(), List.of(), List.of(), OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(new restudio.resync.flow.function.FunctionLocator(RESOURCE), revision,
            List.of(new FunctionParameterContract(INPUT, TEXT, true)),
            List.of(new FunctionParameterContract(OUTPUT, TEXT, true)));
        FunctionSourceDocument source = new FunctionSourceMaterializer().materialize(signature, graph).source();
        TypedFunctionNodeCapability capability = new TypedFunctionNodeCapability(NODE,
            ContractRef.of(new OwnerId("typed"), NodeId.of("source")), 1, 0, CAPABILITY, OPERATION,
            new ContentHash("2".repeat(64)), Set.of(), compiler);
        FlowGraph legacyGraph = new FlowGraph();
        legacyGraph.setId("typed-function");
        legacyGraph.setFunction(true);
        legacyGraph.setResourceType("function");
        legacyGraph.setResourceRevision(revision.value());
        legacyGraph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter(INPUT, "value", FlowDataType.STRING)));
        legacyGraph.setFunctionOutputs(List.of(new FlowGraph.FunctionParameter(OUTPUT, "result", FlowDataType.STRING)));
        return new Fixture(legacyGraph, source, new TypedFunctionCapabilitySet(BINDING, List.of(capability)), signature);
    }

    private static Fixture multiParameterFixture() {
        GraphNode node = new GraphNode(NODE, ContractRef.of(new OwnerId("typed"), NodeId.of("source")), 1,
            Map.of(VALUE, new PinValue(VALUE, TypedValue.value(TEXT, "hello"))));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), RESOURCE, 4, BINDING,
            Set.of(), List.of(node), List.of(), List.of(), List.of(), OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(new restudio.resync.flow.function.FunctionLocator(RESOURCE),
            new FunctionRevision(4),
            List.of(new FunctionParameterContract(INPUT, TEXT, true), new FunctionParameterContract(SECOND_INPUT, TEXT, true)),
            List.of(new FunctionParameterContract(OUTPUT, TEXT, true), new FunctionParameterContract(SECOND_OUTPUT, TEXT, true)));
        FunctionSourceDocument source = new FunctionSourceMaterializer().materialize(signature, graph).source();
        TypedFunctionNodeCapability capability = new TypedFunctionNodeCapability(NODE,
            ContractRef.of(new OwnerId("typed"), NodeId.of("source")), 1, 0, CAPABILITY, OPERATION,
            new ContentHash("2".repeat(64)), Set.of(), compiledNode -> frame -> frame.withOutput(OUTPUT, frame.input(INPUT))
                .withOutput(SECOND_OUTPUT, frame.input(SECOND_INPUT)));
        FlowGraph legacyGraph = new FlowGraph();
        legacyGraph.setId("typed-function");
        legacyGraph.setFunction(true);
        legacyGraph.setResourceType("function");
        legacyGraph.setResourceRevision(4);
        legacyGraph.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter(INPUT, "first", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(SECOND_INPUT, "second", FlowDataType.STRING)));
        legacyGraph.setFunctionOutputs(List.of(
            new FlowGraph.FunctionParameter(OUTPUT, "first-result", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(SECOND_OUTPUT, "second-result", FlowDataType.STRING)));
        return new Fixture(legacyGraph, source, new TypedFunctionCapabilitySet(BINDING, List.of(capability)), signature);
    }

    private record Fixture(FlowGraph graph, FunctionSourceDocument source, TypedFunctionCapabilitySet capabilities,
                           FunctionSignature signature) {
        private FunctionExecutionRequest execution() {
            return execution(UUID.fromString("66666666-6666-4666-8666-666666666666"));
        }

        private FunctionExecutionRequest execution(UUID invocationId) {
            return new FunctionExecutionRequest(signature, invocationId,
                new restudio.resync.flow.function.FunctionInputMap(Map.of(INPUT, TypedValue.value(TEXT, "hello"))));
        }

        private CompiledFunctionExecutionRequest request(ContentHash fingerprint) {
            return new CompiledFunctionExecutionRequest(execution(), BINDING, fingerprint);
        }
    }

    private static CompiledFunctionExecutionRequest authenticatedRequest(Fixture fixture, RuntimeAuthority authority,
                                                                          RuntimePrincipal principal, UUID invocationId,
                                                                          String sessionReference) {
        return authenticatedRequest(fixture, authority, principal, invocationId, sessionReference,
            fixture.capabilities().fingerprint());
    }

    private static CompiledFunctionExecutionRequest authenticatedRequest(Fixture fixture, RuntimeAuthority authority,
                                                                          RuntimePrincipal principal, UUID invocationId,
                                                                          String sessionReference, ContentHash fingerprint) {
        return new CompiledFunctionExecutionRequest(fixture.execution(invocationId), BINDING, fingerprint, authority,
            principal, new CompiledRuntimeContext(null, null, Map.of(), principal), sessionReference,
            restudio.resync.flow.runtime.RuntimeExecutionContext.NO_DEADLINE);
    }

    private static RuntimeAuditBoundary recordingAudit(List<restudio.resync.flow.runtime.RuntimeLeaseInput.AuditEvent> events) {
        return new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit policy) {
                return true;
            }

            @Override
            public void record(restudio.resync.flow.runtime.RuntimeLeaseInput.AuditEvent event) {
                events.add(event);
            }
        };
    }
}
