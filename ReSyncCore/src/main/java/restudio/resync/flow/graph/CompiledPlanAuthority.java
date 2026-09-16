package restudio.resync.flow.graph;

import java.util.Objects;

@FunctionalInterface
public interface CompiledPlanAuthority {
    CompiledPlanLease acquire(ExecutionTarget target);

    default CompiledPlanLease acquireRequired(ExecutionTarget target) {
        Objects.requireNonNull(target, "Execution Target Is Required");
        CompiledPlanLease lease = Objects.requireNonNull(acquire(target), "Compiled Plan Authority Lease Is Required");
        try {
            lease.requirePlan(target);
            return lease;
        } catch (RuntimeException failure) {
            try {
                lease.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
