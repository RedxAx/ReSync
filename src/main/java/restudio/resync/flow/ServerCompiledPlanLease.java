package restudio.resync.flow;

import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.CompiledPlanCacheKey;
import restudio.resync.flow.graph.CompiledPlanLease;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerCompiledPlanLease implements CompiledPlanLease {
    private final CompiledPlanCacheKey cacheKey;
    private final CompiledExecutionPlan plan;
    private final Map<ServerResourceLocator, PinnedPlan> closure;
    private final CompiledExecutionRunner.ExecutionTemplate executionTemplate;
    private final Runnable release;
    private final AtomicBoolean active = new AtomicBoolean(true);

    ServerCompiledPlanLease(CompiledPlanCacheKey cacheKey, CompiledExecutionPlan plan,
                            Map<ServerResourceLocator, PinnedPlan> closure,
                            CompiledExecutionRunner.ExecutionTemplate executionTemplate, Runnable release) {
        this.cacheKey = Objects.requireNonNull(cacheKey, "Compiled Plan Cache Key Is Required");
        this.plan = Objects.requireNonNull(plan, "Compiled Execution Plan Is Required");
        this.closure = Map.copyOf(Objects.requireNonNull(closure, "Compiled Function Closure Is Required"));
        this.executionTemplate = executionTemplate;
        this.release = Objects.requireNonNull(release, "Compiled Plan Lease Release Is Required");
        cacheKey.require(plan);
    }

    @Override
    public CompiledPlanCacheKey cacheKey() {
        return cacheKey;
    }

    @Override
    public CompiledExecutionPlan plan() {
        return plan;
    }

    @Override
    public boolean active() {
        return active.get();
    }

    @Override
    public Optional<CompiledExecutionRunner.ExecutionTemplate> executionTemplate() {
        return Optional.ofNullable(executionTemplate);
    }

    public Map<ServerResourceLocator, PinnedPlan> functionClosure() {
        return closure;
    }

    public Optional<PinnedPlan> pinned(ServerResourceLocator function) {
        Objects.requireNonNull(function, "Function Resource Is Required");
        if (!active()) {
            throw new IllegalStateException("Compiled Plan Lease Is Not Active");
        }
        return Optional.ofNullable(closure.get(function));
    }

    public PinnedPlan requirePinned(ServerResourceLocator function) {
        return pinned(function).orElseThrow(() -> new IllegalStateException(
            "Function Is Not Part Of The Compiled Plan Closure: " + function.canonicalText()));
    }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            release.run();
        }
    }

    public record PinnedPlan(CompiledPlanCacheKey cacheKey, CompiledExecutionPlan plan) {
        public PinnedPlan {
            cacheKey = Objects.requireNonNull(cacheKey, "Pinned Function Cache Key Is Required");
            plan = Objects.requireNonNull(plan, "Pinned Function Plan Is Required");
            cacheKey.require(plan);
        }
    }
}
