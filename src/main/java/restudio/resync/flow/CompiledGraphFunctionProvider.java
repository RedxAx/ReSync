package restudio.resync.flow;

import restudio.resync.flow.function.FunctionDiagnostic;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionResult;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeCancellationToken;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class CompiledGraphFunctionProvider implements CompiledFunctionExecutionBridge.GraphFunctions {
    private final ServerCompiledPlanRepository plans;
    private final CompiledCoreFlowExecutionBridge bridge;

    public CompiledGraphFunctionProvider(ServerCompiledPlanRepository plans, CompiledCoreFlowExecutionBridge bridge) {
        this.plans = Objects.requireNonNull(plans, "Resident Function Plan Authority Is Required");
        this.bridge = Objects.requireNonNull(bridge, "Compiled Function Runtime Owner Is Required");
    }

    @Override
    public Optional<CompiledFunctionExecutionBridge.FunctionAdmission> resolve(FunctionLocator function, FunctionRevision revision) {
        return source(function, revision).map(value -> new CompiledFunctionExecutionBridge.FunctionAdmission(
            value.functionSource(), value.payloadChecksum(), value.key().catalogBinding().bindingManifestHash()));
    }

    private Optional<ServerCompiledPlanRepository.ResidentSource> source(FunctionLocator function, FunctionRevision revision) {
        return plans.residentSource(function.resource(), revision.value()).filter(value -> value.functionSource() != null
            && value.entryNodeId() != null && value.activationState() == ResourceActivationState.ACTIVE
            && value.functionSource().signature().function().equals(function)
            && value.functionSource().signature().revision().equals(revision));
    }

    @Override
    public CompletionStage<FunctionResult> execute(CompiledFunctionExecutionRequest request, RuntimeCancellationToken parentCancellation) {
        try {
            ServerCompiledPlanRepository.ResidentSource current = source(request.function(), request.revision()).orElseThrow(() ->
                new IllegalStateException("The Requested Function Revision Is Not Active In The Resident Plan Authority"));
            Object checksum = request.execution().attributes().get("function.sourceChecksum");
            if (!current.functionSource().signature().equals(request.execution().signature())
                || !current.key().catalogBinding().equals(request.catalogBinding())
                || !current.key().catalogBinding().bindingManifestHash().equals(request.capabilityFingerprint())
                || !current.payloadChecksum().canonicalText().equals(checksum)) {
                throw new IllegalArgumentException("The Requested Function Signature, Checksum Or Runtime Binding Is Not Current");
            }
            RuntimeCancellationToken cancellation = parentCancellation == null ? new RuntimeCancellationToken() : parentCancellation;
            if (request.execution().cancellation().requested()) {
                return CompletableFuture.completedFuture(FunctionResult.cancelled(request.execution().signature(),
                    FunctionDiagnostic.error("FUNCTION.CANCELLED", "function-admission", "Function Cancellation Was Requested", null), 0));
            }
            CompiledExecutionRunner.FunctionExecutionHandle handle;
            CompiledRuntimeContext context = request.runtimeContext() == null
                ? new CompiledRuntimeContext(null, null, Map.of(), request.principal()) : request.runtimeContext();
            handle = bridge.executeFunctionObserved(current.functionSource(), current.entryNodeId(), request.execution().inputs(),
                cancellation, context, CorrelationId.of(request.execution().invocationId()),
                Math.min(request.requestedDeadlineMillis(), request.execution().cancellation().deadlineMillis()));
            CompletableFuture<FunctionResult> completion = new CompletableFuture<>();
            handle.result().whenComplete((result, failure) -> handle.physicalCompletion().whenComplete((ignored, physicalFailure) -> {
                if (failure != null) {
                    completion.completeExceptionally(failure);
                } else if (physicalFailure != null) {
                    completion.completeExceptionally(physicalFailure);
                } else {
                    completion.complete(result);
                }
            }));
            return completion.minimalCompletionStage();
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
