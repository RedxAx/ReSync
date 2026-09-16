package restudio.resync.flow.graph;

import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;

public record ExecutionTarget(
    ServerResourceLocator resource,
    NodeInstanceId startNodeId,
    long expectedRevision,
    CatalogBinding expectedBinding
) {
    public ExecutionTarget {
        resource = Objects.requireNonNull(resource, "Execution Target Resource Is Required");
        startNodeId = Objects.requireNonNull(startNodeId, "Execution Target Start Node Is Required");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Execution Target Revision Cannot Be Negative");
        }
        expectedBinding = Objects.requireNonNull(expectedBinding, "Execution Target Catalog Binding Is Required");
    }

    public boolean matches(CompiledExecutionPlan plan) {
        return plan != null
            && resource.equals(plan.graph())
            && expectedRevision == plan.graphRevision()
            && expectedBinding.equals(plan.catalogBinding())
            && plan.steps().stream().anyMatch(step -> startNodeId.equals(step.nodeId()));
    }

    public boolean matches(CompiledPlanCacheKey key) {
        return key != null
            && resource.equals(key.graph())
            && expectedRevision == key.graphRevision()
            && expectedBinding.equals(key.catalogBinding());
    }

    public CompiledExecutionPlan require(CompiledExecutionPlan plan) {
        if (!matches(plan)) {
            throw new IllegalStateException("Compiled Plan Does Not Match The Execution Target");
        }
        return plan;
    }

    public CompiledPlanCacheKey require(CompiledPlanCacheKey key) {
        if (!matches(key)) {
            throw new IllegalStateException("Compiled Plan Cache Key Does Not Match The Execution Target");
        }
        return key;
    }
}
