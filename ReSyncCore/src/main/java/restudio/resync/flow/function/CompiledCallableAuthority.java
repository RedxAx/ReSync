package restudio.resync.flow.function;

import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.CompiledPlanCacheKey;
import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.runtime.RuntimeExecutionProvenance;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface CompiledCallableAuthority {
    CompletionStage<Result> execute(Call call);

    default CompletionStage<Result> executeRequired(Call call) {
        Objects.requireNonNull(call, "Compiled Callable Call Is Required");
        CompletionStage<Result> execution = Objects.requireNonNull(execute(call), "Compiled Callable Execution Stage Is Required");
        return execution.thenApply(result -> Objects.requireNonNull(result, "Compiled Callable Result Is Required").require(call));
    }

    record Call(
        ExecutionTarget target,
        FunctionExecutionRequest request,
        RuntimeCancellationToken cancellationToken,
        RuntimeExecutionProvenance callerProvenance
    ) {
        public Call {
            target = Objects.requireNonNull(target, "Compiled Callable Target Is Required");
            request = Objects.requireNonNull(request, "Compiled Callable Function Request Is Required");
            cancellationToken = Objects.requireNonNull(cancellationToken, "Compiled Callable Cancellation Token Is Required");
            callerProvenance = Objects.requireNonNull(callerProvenance, "Compiled Callable Caller Provenance Is Required");
            requireFunctionTarget(target, request);
            requireBinding(target, callerProvenance);
            if (cancellationToken.deadlineMillis() > callerProvenance.deadlineMillis()) {
                throw new IllegalArgumentException("Compiled Callable Cancellation Deadline Cannot Exceed Its Caller Deadline");
            }
        }
    }

    record Result(
        ExecutionTarget target,
        CompiledPlanCacheKey cacheKey,
        CompiledExecutionRunner.ExecutionResult subplanResult,
        FunctionResult callableResult,
        RuntimeExecutionProvenance callerProvenance
    ) {
        public Result {
            target = Objects.requireNonNull(target, "Compiled Callable Result Target Is Required");
            cacheKey = Objects.requireNonNull(cacheKey, "Compiled Callable Result Cache Key Is Required");
            subplanResult = Objects.requireNonNull(subplanResult, "Compiled Callable Subplan Result Is Required");
            callableResult = Objects.requireNonNull(callableResult, "Compiled Callable Function Result Is Required");
            callerProvenance = Objects.requireNonNull(callerProvenance, "Compiled Callable Result Caller Provenance Is Required");
            target.require(cacheKey);
            requireFunctionTarget(target, callableResult.signature());
            requireBinding(target, callerProvenance);
            requireStatus(subplanResult.status(), callableResult.status());
        }

        public Result require(Call call) {
            Objects.requireNonNull(call, "Compiled Callable Call Is Required");
            if (!target.equals(call.target())
                || !callableResult.signature().equals(call.request().signature())
                || !callerProvenance.equals(call.callerProvenance())) {
                throw new IllegalStateException("Compiled Callable Result Does Not Match Its Call");
            }
            return this;
        }
    }

    private static void requireFunctionTarget(ExecutionTarget target, FunctionExecutionRequest request) {
        requireFunctionTarget(target, request.signature());
    }

    private static void requireFunctionTarget(ExecutionTarget target, FunctionSignature signature) {
        if (!target.resource().equals(signature.function().resource())
            || target.expectedRevision() != signature.revision().value()) {
            throw new IllegalArgumentException("Compiled Callable Function Does Not Match Its Execution Target");
        }
    }

    private static void requireBinding(ExecutionTarget target, RuntimeExecutionProvenance provenance) {
        if (provenance.catalogGeneration() != target.expectedBinding().generation()
            || !target.expectedBinding().catalogChecksum().equals(provenance.catalogHash())
            || !target.expectedBinding().bindingManifestHash().equals(provenance.runtimeManifestHash())) {
            throw new IllegalArgumentException("Compiled Callable Provenance Does Not Match Its Expected Catalog Binding");
        }
    }

    private static void requireStatus(CompiledExecutionRunner.Status subplan, FunctionResult.Status callable) {
        boolean matching = switch (subplan) {
            case SUCCESS -> callable == FunctionResult.Status.SUCCESS;
            case FAILURE -> callable == FunctionResult.Status.FAILURE;
            case CANCELLED -> callable == FunctionResult.Status.CANCELLED;
        };
        if (!matching) {
            throw new IllegalArgumentException("Compiled Callable And Subplan Results Have Different Statuses");
        }
    }
}
