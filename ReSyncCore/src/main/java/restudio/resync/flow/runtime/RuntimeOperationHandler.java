package restudio.resync.flow.runtime;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface RuntimeOperationHandler {
    CompletionStage<RuntimeResult> execute(RuntimeInvocation invocation);
}
