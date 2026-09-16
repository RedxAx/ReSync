package restudio.resync.flow.graph;

import java.util.Objects;
import java.util.Optional;

public interface CompiledPlanLease extends AutoCloseable {
    CompiledPlanCacheKey cacheKey();

    CompiledExecutionPlan plan();

    default Optional<CompiledExecutionRunner.ExecutionTemplate> executionTemplate() {
        return Optional.empty();
    }

    default boolean active() {
        return false;
    }

    default CompiledExecutionPlan requirePlan(ExecutionTarget target) {
        Objects.requireNonNull(target, "Execution Target Is Required");
        if (!active()) {
            throw new IllegalStateException("Compiled Plan Lease Is Not Active");
        }
        CompiledPlanCacheKey key = Objects.requireNonNull(cacheKey(), "Compiled Plan Lease Cache Key Is Required");
        CompiledExecutionPlan compiled = Objects.requireNonNull(plan(), "Compiled Plan Lease Plan Is Required");
        target.require(key);
        key.require(compiled);
        return target.require(compiled);
    }

    @Override
    void close();
}
