package restudio.resync.flow.runtime;

import java.util.concurrent.CompletionStage;

public interface RuntimeExecutionBoundary {
    @Deprecated
    default boolean supports(RuntimeBindingDescriptor descriptor) {
        return false;
    }

    boolean supports(RuntimeExecutionContext context);

    @Deprecated
    default CompletionStage<RuntimeResult> execute(
        RuntimeBindingDescriptor descriptor,
        RuntimeAuthority authority,
        RuntimeInvocation invocation,
        RuntimeOperationHandler handler
    ) {
        throw new IllegalStateException("Runtime Context Execution Is Required");
    }

    CompletionStage<RuntimeResult> execute(
        RuntimeExecutionContext context,
        RuntimeInvocation invocation,
        RuntimeOperationHandler handler
    );

    boolean approveSafeRetry(
        RuntimeExecutionContext context,
        RuntimeFailure failure,
        int attempt
    );

    @Deprecated
    default boolean approveRetry(
        RuntimeExecutionContext context,
        RuntimeSemantics.Retry policy,
        RuntimeFailure failure,
        int attempt
    ) {
        return policy == RuntimeSemantics.Retry.SAFE && approveSafeRetry(context, failure, attempt);
    }

    static RuntimeExecutionBoundary unavailable() {
        return new RuntimeExecutionBoundary() {
            @Override
            public boolean supports(RuntimeExecutionContext context) {
                return false;
            }

            @Override
            public CompletionStage<RuntimeResult> execute(
                RuntimeExecutionContext context,
                RuntimeInvocation invocation,
                RuntimeOperationHandler handler
            ) {
                throw new IllegalStateException("Runtime Execution Boundary Is Unavailable");
            }

            @Override
            public boolean approveSafeRetry(
                RuntimeExecutionContext context,
                RuntimeFailure failure,
                int attempt
            ) {
                return false;
            }
        };
    }
}
