package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionOutputMap;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionResult;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.runtime.RuntimeLeaseInput;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionCallSupportTest {
    @Test
    void configuredCallsFailWhenTheExecutorIsUnavailable() {
        JsonObject call = new JsonObject();
        call.addProperty("functionId", "library:reward");

        FlowHandlerException failure = assertThrows(FlowHandlerException.class,
            () -> FunctionCallSupport.execute(null, null, call, null, null, Map.of()));

        assertEquals("FUNCTION_EXECUTOR_UNAVAILABLE", failure.getCode());
    }

    @Test
    void configuredCallsFailWhenTheFunctionIsUnavailable() {
        JsonObject call = new JsonObject();
        call.addProperty("functionId", "library:missing");
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());

        FlowHandlerException failure = assertThrows(FlowHandlerException.class,
            () -> FunctionCallSupport.execute(null, executor, call, null, null, Map.of()));

        assertEquals("FUNCTION_NOT_FOUND", failure.getCode());
        executor.shutdown();
    }

    @Test
    void inlineEvaluationRequiresAnAuthoritativeFunctionIdentity() {
        FlowGraph function = new FlowGraph();
        function.setId("inline_missing_start");
        JsonObject call = new JsonObject();
        call.addProperty("type", "inlineFunction");
        call.add("graph", new Gson().toJsonTree(function));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());

        FlowHandlerException failure = assertThrows(FlowHandlerException.class,
            () -> FunctionCallSupport.evaluate(null, executor, call, null, null, Map.of()));

        assertEquals("FUNCTION_CALL_REQUIRED", failure.getCode());
        executor.shutdown();
    }

    @Test
    void nativeTypedCallUsesExecutorAuthorityAndKeepsPinnedChildrenUntilPhysicalCompletion() {
        ServerId server = ServerId.of(UUID.randomUUID());
        ServerResourceLocator resource = new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), "parent");
        ServerResourceLocator child = new ServerResourceLocator(server, resource.type(), "child");
        CatalogBinding binding = new CatalogBinding(1L, ContentHash.of("a".repeat(64)), ContentHash.of("b".repeat(64)));
        TypeExpr text = TypeExpr.named(TypeReference.of("builtin", "string"));
        FunctionParameterId output = FunctionParameterId.random();
        FunctionParameterId input = FunctionParameterId.random();
        TypeExpr number = TypeExpr.named(TypeReference.of("builtin", "number"));
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(4L),
            List.of(new FunctionParameterContract(input, number, true, null, Map.of("name", "value"))),
            List.of(new FunctionParameterContract(output, text, true, null, Map.of("name", "result"))));
        FunctionBinding pinnedChild = new FunctionBinding(child, 2L, List.of(), List.of());
        FunctionSourceDocument source = new FunctionSourceDocument(signature,
            new GraphDocument(new CatalogVersion(1, 0), resource, 4L, binding, Set.of(), List.of(), List.of(),
                List.of(pinnedChild), OpaqueData.empty()));
        CompletableFuture<FunctionResult> physical = new CompletableFuture<>();
        AtomicReference<CompiledFunctionExecutionRequest> admitted = new AtomicReference<>();
        AtomicReference<RuntimeCancellationToken> admittedCancellation = new AtomicReference<>();
        CompiledFunctionExecutionBridge bridge = new CompiledFunctionExecutionBridge(new CompiledFunctionExecutionBridge.GraphFunctions() {
            @Override
            public Optional<CompiledFunctionExecutionBridge.FunctionAdmission> resolve(FunctionLocator locator, FunctionRevision revision) {
                return locator.equals(signature.function()) && revision.equals(signature.revision())
                    ? Optional.of(new CompiledFunctionExecutionBridge.FunctionAdmission(source, source.checksum(), binding.bindingManifestHash()))
                    : Optional.empty();
            }

            @Override
            public CompletionStage<FunctionResult> execute(CompiledFunctionExecutionRequest request, RuntimeCancellationToken cancellation) {
                admitted.set(request);
                admittedCancellation.set(cancellation);
                return physical;
            }
        });
        RuntimeAuthority authority = new RuntimeAuthority("native-function-test");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        var principal = principals.issuePersistentSystem("server-hooks");
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        executor.configureCompiledFunctionBridge(bridge);
        executor.configureCompiledFunctionRuntime(authority, principals, RuntimeReceiptStore.inMemory(true), server, principal,
            new RuntimeAuditBoundary() {
                @Override
                public boolean available(RuntimeSemantics.Audit audit) {
                    return true;
                }

                @Override
                public void record(RuntimeLeaseInput.AuditEvent event) {
                }
            });
        try {
            long deadline = System.currentTimeMillis() + 60_000L;
            CorrelationId invocation = CorrelationId.random();
            RuntimeCancellationToken cancellation = new RuntimeCancellationToken();
            FlowExecutor.FunctionInvocationContext context = executor.defaultFunctionInvocationContext(null, null,
                Map.of("runtime.sessionId", "qa-session"), invocation, deadline);
            assertThrows(FlowHandlerException.class, () -> FunctionCallSupport.normalizeSourceArguments(source, Map.of(7, "bad")));
            CompletableFuture<Map<String, Object>> execution = executor.executeFunctionSource(source, null, null,
                FunctionCallSupport.normalizeSourceArguments(source, BigDecimal.valueOf(-7)),
                Map.of("runtime.sessionId", "qa-session"), context, cancellation);

            assertFalse(execution.isDone());
            assertEquals(signature, admitted.get().execution().signature());
            assertEquals(BigDecimal.valueOf(-7), admitted.get().execution().inputs().values().get(input).value());
            assertEquals(invocation.value(), admitted.get().execution().invocationId());
            assertEquals(deadline, admitted.get().requestedDeadlineMillis());
            assertEquals("qa-session", admitted.get().sessionReference());
            assertSame(principal, admitted.get().principal());
            assertSame(cancellation, admittedCancellation.get());
            assertEquals(2L, source.graph().functions().getFirst().revision());
            physical.complete(FunctionResult.success(signature, new FunctionOutputMap(Map.of(output, TypedValue.value(text, "QA Complete"))), 0));

            assertEquals(Map.of("result", "QA Complete"), execution.join());
            assertTrue(physical.isDone());
        } finally {
            executor.shutdown();
        }
    }
}
