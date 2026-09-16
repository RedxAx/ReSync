package restudio.resync.flow.graph;

import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;

public record CompiledPlanCacheKey(
    ServerResourceLocator graph,
    long graphRevision,
    ContentHash graphChecksum,
    CatalogBinding catalogBinding
) {
    public CompiledPlanCacheKey {
        graph = Objects.requireNonNull(graph, "Compiled Plan Cache Graph Is Required");
        if (graphRevision < 0) {
            throw new IllegalArgumentException("Compiled Plan Cache Graph Revision Cannot Be Negative");
        }
        graphChecksum = Objects.requireNonNull(graphChecksum, "Compiled Plan Cache Graph Checksum Is Required");
        catalogBinding = Objects.requireNonNull(catalogBinding, "Compiled Plan Cache Catalog Binding Is Required");
    }

    public static CompiledPlanCacheKey from(CompiledExecutionPlan plan) {
        Objects.requireNonNull(plan, "Compiled Execution Plan Is Required");
        return new CompiledPlanCacheKey(plan.graph(), plan.graphRevision(), plan.graphHash(), plan.catalogBinding());
    }

    public long catalogGeneration() {
        return catalogBinding.generation();
    }

    public ContentHash catalogChecksum() {
        return catalogBinding.catalogChecksum();
    }

    public ContentHash runtimeManifestHash() {
        return catalogBinding.bindingManifestHash();
    }

    public boolean matches(CompiledExecutionPlan plan) {
        return plan != null
            && graph.equals(plan.graph())
            && graphRevision == plan.graphRevision()
            && graphChecksum.equals(plan.graphHash())
            && catalogBinding.equals(plan.catalogBinding());
    }

    public CompiledExecutionPlan require(CompiledExecutionPlan plan) {
        if (!matches(plan)) {
            throw new IllegalStateException("Compiled Plan Does Not Match Its Cache Key");
        }
        return plan;
    }

    public String canonicalText() {
        return graph.canonicalText() + "|" + graphRevision + "|" + graphChecksum.canonicalText() + "|" + catalogBinding.canonicalText();
    }
}
