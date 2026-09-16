package restudio.resync.flow.graph;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledPlanAuthorityContractTest {
    private static final ServerResourceLocator GRAPH = new ServerResourceLocator(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")),
        "compiled-authority");
    private static final NodeInstanceId START = NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final ContentHash GRAPH_HASH = hash('a');
    private static final ContentHash CATALOG_HASH = hash('b');
    private static final ContentHash RUNTIME_HASH = hash('c');
    private static final CatalogBinding BINDING = CatalogBinding.of(7, CATALOG_HASH, RUNTIME_HASH);

    @Test
    void targetAndCacheKeyRequireExactRevisionBindingChecksumAndStartNode() {
        CompiledExecutionPlan plan = plan();
        ExecutionTarget target = new ExecutionTarget(GRAPH, START, 4, BINDING);
        CompiledPlanCacheKey key = CompiledPlanCacheKey.from(plan);

        assertTrue(target.matches(plan));
        assertTrue(target.matches(key));
        assertTrue(key.matches(plan));
        assertEquals(GRAPH_HASH, key.graphChecksum());
        assertEquals(CATALOG_HASH, key.catalogChecksum());
        assertEquals(RUNTIME_HASH, key.runtimeManifestHash());
        assertTrue(key.canonicalText().contains(GRAPH_HASH.canonicalText()));

        assertFalse(new ExecutionTarget(GRAPH, START, 5, BINDING).matches(plan));
        assertFalse(new ExecutionTarget(GRAPH, NodeInstanceId.deterministic("missing"), 4, BINDING).matches(plan));
        assertFalse(new CompiledPlanCacheKey(GRAPH, 4, hash('d'), BINDING).matches(plan));
        assertFalse(new CompiledPlanCacheKey(GRAPH, 4, GRAPH_HASH,
            CatalogBinding.of(7, CATALOG_HASH, hash('e'))).matches(plan));
    }

    @Test
    void authorityClosesAndRejectsAnInactiveOrMismatchedLease() {
        ExecutionTarget target = new ExecutionTarget(GRAPH, START, 4, BINDING);
        TestLease inactive = new TestLease(CompiledPlanCacheKey.from(plan()), plan(), false);
        CompiledPlanAuthority inactiveAuthority = ignored -> inactive;

        assertThrows(IllegalStateException.class, () -> inactiveAuthority.acquireRequired(target));
        assertTrue(inactive.closed);

        TestLease mismatched = new TestLease(
            new CompiledPlanCacheKey(GRAPH, 4, hash('f'), BINDING), plan(), true);
        CompiledPlanAuthority mismatchedAuthority = ignored -> mismatched;

        assertThrows(IllegalStateException.class, () -> mismatchedAuthority.acquireRequired(target));
        assertTrue(mismatched.closed);
    }

    @Test
    void activeLeaseReturnsTheExistingCompiledPlanBody() {
        CompiledExecutionPlan plan = plan();
        ExecutionTarget target = new ExecutionTarget(GRAPH, START, 4, BINDING);
        TestLease lease = new TestLease(CompiledPlanCacheKey.from(plan), plan, true);

        try (CompiledPlanLease acquired = ((CompiledPlanAuthority) ignored -> lease).acquireRequired(target)) {
            assertEquals(plan, acquired.requirePlan(target));
        }

        assertTrue(lease.closed);
    }

    private static CompiledExecutionPlan plan() {
        CatalogNodeDescriptor.Handler handler = new CatalogNodeDescriptor.Handler(
            ContractRef.of(OwnerId.of("restudio.resync"), CapabilityId.of("execute")),
            ContractRef.of(OwnerId.of("restudio.resync"), OperationId.of("run")));
        CompiledExecutionStep step = new CompiledExecutionStep(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            START,
            ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("entry")),
            handler,
            Map.of(),
            Map.of(),
            OpaqueData.empty());
        return new CompiledExecutionPlan(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            GRAPH,
            4,
            BINDING,
            GRAPH_HASH,
            List.of(step),
            List.of(),
            List.of(),
            OpaqueData.empty());
    }

    private static ContentHash hash(char value) {
        return ContentHash.of(String.valueOf(value).repeat(64));
    }

    private static final class TestLease implements CompiledPlanLease {
        private final CompiledPlanCacheKey key;
        private final CompiledExecutionPlan plan;
        private final boolean active;
        private boolean closed;

        private TestLease(CompiledPlanCacheKey key, CompiledExecutionPlan plan, boolean active) {
            this.key = key;
            this.plan = plan;
            this.active = active;
        }

        @Override
        public CompiledPlanCacheKey cacheKey() {
            return key;
        }

        @Override
        public CompiledExecutionPlan plan() {
            return plan;
        }

        @Override
        public boolean active() {
            return active && !closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
