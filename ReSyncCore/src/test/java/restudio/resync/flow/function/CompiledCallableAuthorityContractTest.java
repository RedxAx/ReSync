package restudio.resync.flow.function;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.CompiledPlanCacheKey;
import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.runtime.RuntimeExecutionProvenance;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompiledCallableAuthorityContractTest {
    private static final ServerResourceLocator FUNCTION = new ServerResourceLocator(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")),
        "callable");
    private static final NodeInstanceId ENTRY = NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final ContentHash GRAPH_HASH = hash('a');
    private static final ContentHash CATALOG_HASH = hash('b');
    private static final ContentHash RUNTIME_HASH = hash('c');
    private static final CatalogBinding BINDING = CatalogBinding.of(9, CATALOG_HASH, RUNTIME_HASH);
    private static final ExecutionTarget TARGET = new ExecutionTarget(FUNCTION, ENTRY, 4, BINDING);
    private static final FunctionSignature SIGNATURE = new FunctionSignature(
        FunctionLocator.of(FUNCTION), FunctionRevision.of(4), List.of(), List.of());

    @Test
    void callRequiresExactFunctionRevisionBindingCancellationAndCallerProvenance() {
        RuntimeCancellationToken cancellation = new RuntimeCancellationToken();
        RuntimeExecutionProvenance provenance = provenance(RUNTIME_HASH);
        FunctionExecutionRequest request = new FunctionExecutionRequest(
            SIGNATURE,
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            FunctionInputMap.empty());

        CompiledCallableAuthority.Call call = new CompiledCallableAuthority.Call(TARGET, request, cancellation, provenance);

        assertSame(cancellation, call.cancellationToken());
        assertSame(provenance, call.callerProvenance());
        assertEquals(TARGET, call.target());
        assertThrows(IllegalArgumentException.class, () -> new CompiledCallableAuthority.Call(
            new ExecutionTarget(FUNCTION, ENTRY, 5, BINDING), request, cancellation, provenance));
        assertThrows(IllegalArgumentException.class, () -> new CompiledCallableAuthority.Call(
            TARGET, request, cancellation, provenance(hash('d'))));
    }

    @Test
    void resultCarriesTheSubplanOutcomeAndRejectsStatusOrSignatureDrift() {
        RuntimeExecutionProvenance provenance = provenance(RUNTIME_HASH);
        CompiledPlanCacheKey key = new CompiledPlanCacheKey(FUNCTION, 4, GRAPH_HASH, BINDING);
        CompiledExecutionRunner.ExecutionResult subplan = new CompiledExecutionRunner.ExecutionResult(
            CompiledExecutionRunner.Status.SUCCESS, Map.of(), Map.of(), null);
        FunctionResult function = FunctionResult.success(SIGNATURE, FunctionOutputMap.empty(), 1);

        CompiledCallableAuthority.Result result = new CompiledCallableAuthority.Result(TARGET, key, subplan, function, provenance);

        assertSame(subplan, result.subplanResult());
        assertSame(function, result.callableResult());
        FunctionResult failure = FunctionResult.failure(SIGNATURE, List.of(FunctionDiagnostic.error(
            "FUNCTION.EXECUTION_FAILURE", "function-execution", "The compiled function failed.", null)), 1);
        assertThrows(IllegalArgumentException.class, () -> new CompiledCallableAuthority.Result(
            TARGET, key, subplan, failure, provenance));
    }

    @Test
    void authorityFailsClosedWhenAnImplementationReturnsTheWrongCallResult() {
        RuntimeExecutionProvenance provenance = provenance(RUNTIME_HASH);
        FunctionExecutionRequest request = new FunctionExecutionRequest(
            SIGNATURE,
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            FunctionInputMap.empty());
        CompiledCallableAuthority.Call call = new CompiledCallableAuthority.Call(
            TARGET, request, new RuntimeCancellationToken(), provenance);
        FunctionSignature drifted = new FunctionSignature(
            SIGNATURE.function(), SIGNATURE.revision(), List.of(), List.of(), Map.of("future", true));
        CompiledCallableAuthority.Result driftedResult = new CompiledCallableAuthority.Result(
            TARGET,
            new CompiledPlanCacheKey(FUNCTION, 4, GRAPH_HASH, BINDING),
            new CompiledExecutionRunner.ExecutionResult(CompiledExecutionRunner.Status.SUCCESS, Map.of(), Map.of(), null),
            FunctionResult.success(drifted, FunctionOutputMap.empty(), 1),
            provenance);
        CompiledCallableAuthority authority = ignored -> CompletableFuture.completedFuture(driftedResult);

        CompletionException failure = assertThrows(CompletionException.class, () -> authority.executeRequired(call).toCompletableFuture().join());

        assertEquals(IllegalStateException.class, failure.getCause().getClass());
    }

    private static RuntimeExecutionProvenance provenance(ContentHash runtimeManifestHash) {
        RuntimeAuthority authority = new RuntimeAuthority("compiled-callable-test");
        RuntimePrincipal principal = new RuntimePrincipalAuthority(authority).issuePersistentSystem("test");
        RuntimeBindingKey binding = new RuntimeBindingKey(
            ContractRef.of(OwnerId.of("restudio.resync"), CapabilityId.of("function-call")),
            ContractRef.of(OwnerId.of("restudio.resync"), OperationId.of("execute")));
        return new RuntimeExecutionProvenance(
            authority.identity(),
            principal,
            binding,
            ContractRef.of(OwnerId.of("restudio.resync"), ProviderId.of("function-runtime")),
            "1.0.0",
            3,
            runtimeManifestHash,
            BINDING.generation(),
            BINDING.catalogChecksum(),
            hash('d'),
            hash('e'),
            "compiled-callable-test",
            CorrelationId.of(UUID.fromString("55555555-5555-4555-8555-555555555555")),
            null,
            hash('f'),
            hash('1'),
            Long.MAX_VALUE,
            UUID.fromString("66666666-6666-4666-8666-666666666666"));
    }

    private static ContentHash hash(char value) {
        return ContentHash.of(String.valueOf(value).repeat(64));
    }
}
