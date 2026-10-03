package restudio.resync.flow.runtime;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class FlowRuntimeExecutionBoundary implements RuntimeExecutionBoundary {
    private final Executor mainExecutor;
    private final Executor asynchronousExecutor;
    private final BooleanSupplier primaryThread;
    private final BooleanSupplier enabled;

    public FlowRuntimeExecutionBoundary(Executor mainExecutor, Executor asynchronousExecutor, BooleanSupplier primaryThread) {
        this(mainExecutor, asynchronousExecutor, primaryThread, () -> true);
    }

    public FlowRuntimeExecutionBoundary(
        Executor mainExecutor,
        Executor asynchronousExecutor,
        BooleanSupplier primaryThread,
        BooleanSupplier enabled
    ) {
        this.mainExecutor = mainExecutor;
        this.asynchronousExecutor = asynchronousExecutor;
        this.primaryThread = Objects.requireNonNull(primaryThread, "Primary Thread Supplier Is Required");
        this.enabled = Objects.requireNonNull(enabled, "Execution Boundary Availability Supplier Is Required");
    }

    public static FlowRuntimeExecutionBoundary bukkit(Plugin plugin) {
        Objects.requireNonNull(plugin, "Runtime Plugin Is Required");
        return new FlowRuntimeExecutionBoundary(
            task -> Bukkit.getScheduler().runTask(plugin, task),
            task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task),
            Bukkit::isPrimaryThread,
            plugin::isEnabled);
    }

    public boolean available() {
        try {
            return mainExecutor != null && asynchronousExecutor != null && enabled.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public boolean supports(RuntimeExecutionContext context) {
        if (context == null || !available()) {
            return false;
        }
        return switch (context.thread()) {
            case MAIN -> mainExecutor != null;
            case ASYNCHRONOUS -> asynchronousExecutor != null;
            case CURRENT, COMPOSED -> true;
        };
    }

    @Override
    public CompletionStage<RuntimeResult> execute(
        RuntimeExecutionContext context,
        RuntimeInvocation invocation,
        RuntimeOperationHandler handler
    ) {
        Objects.requireNonNull(context, "Runtime Execution Context Is Required");
        Objects.requireNonNull(invocation, "Runtime Invocation Is Required");
        Objects.requireNonNull(handler, "Runtime Operation Handler Is Required");
        if (!supports(context)) {
            throw new IllegalStateException("Runtime Execution Boundary Is Unavailable For " + context.thread().wireValue());
        }
        CompletableFuture<RuntimeResult> result = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        RuntimeCancellationToken.Registration cancellation = invocation.cancellationToken().onCancel(() -> {
            if (claimed.compareAndSet(false, true)) {
                result.completeExceptionally(new RuntimeOperationCancelledException());
            }
        });
        result.whenComplete((ignored, failure) -> cancellation.close());
        Runnable task = () -> {
            if (claimed.compareAndSet(false, true)) {
                cancellation.close();
                invoke(context, invocation, handler, result);
            }
        };
        switch (context.thread()) {
            case MAIN -> {
                if (primaryThread.getAsBoolean()) {
                    task.run();
                } else {
                    mainExecutor.execute(task);
                }
            }
            case ASYNCHRONOUS -> {
                if (primaryThread.getAsBoolean()) {
                    asynchronousExecutor.execute(task);
                } else {
                    task.run();
                }
            }
            case CURRENT, COMPOSED -> task.run();
        }
        return result;
    }

    @Override
    public boolean approveSafeRetry(RuntimeExecutionContext context, RuntimeFailure failure, int attempt) {
        return context != null && failure != null && attempt == 0 && supports(context);
    }

    private void invoke(
        RuntimeExecutionContext context,
        RuntimeInvocation invocation,
        RuntimeOperationHandler handler,
        CompletableFuture<RuntimeResult> result
    ) {
        if (result.isDone()) {
            return;
        }
        try {
            if (!available()) {
                throw new IllegalStateException("Runtime Execution Boundary Is Unavailable");
            }
            invocation.throwIfCancelled();
            if (context.deadlineExceeded()) {
                throw new RuntimeOperationCancelledException();
            }
            if (context.thread() == RuntimeSemantics.ThreadMode.MAIN && !primaryThread.getAsBoolean()) {
                throw new IllegalStateException("Runtime Main-Thread Boundary Was Violated");
            }
            if (context.thread() == RuntimeSemantics.ThreadMode.ASYNCHRONOUS && primaryThread.getAsBoolean()) {
                throw new IllegalStateException("Runtime Asynchronous Boundary Was Violated");
            }
            context.establishThread(context.thread());
            context.establishEffect(context.effect());
            CompletionStage<RuntimeResult> stage = handler.execute(invocation);
            if (stage == null) {
                throw new IllegalStateException("Runtime Operation Handler Returned No Completion Stage");
            }
            stage.whenComplete((value, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    result.complete(value);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }
}
