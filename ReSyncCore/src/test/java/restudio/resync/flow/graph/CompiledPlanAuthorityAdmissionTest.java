package restudio.resync.flow.graph;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledPlanAuthorityAdmissionTest {
    private static final ServerResourceLocator GRAPH = new ServerResourceLocator(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "admission-failure");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));
    private static final ExecutionTarget TARGET = new ExecutionTarget(GRAPH,
        NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222")), 4, BINDING);
    private static final CompiledPlanCacheKey KEY = new CompiledPlanCacheKey(GRAPH, 4, new ContentHash("c".repeat(64)), BINDING);

    @Test
    void errorsAcrossLeaseValidationCloseAdmissionAndPreserveTheOriginalFailure() {
        for (Boundary boundary : Boundary.values()) {
            AssertionError original = new AssertionError("validation failed at " + boundary);
            FailingLease lease = new FailingLease(boundary, original, null);
            CompiledPlanAuthority authority = target -> lease;

            assertSame(original, assertThrows(AssertionError.class, () -> authority.acquireRequired(TARGET)));
            assertTrue(lease.closed);
            assertEquals(0, original.getSuppressed().length);
        }
    }

    @Test
    void aCloseErrorIsSuppressedWithoutReplacingTheValidationRuntimeFailure() {
        IllegalStateException original = new IllegalStateException("validation failed");
        AssertionError closeFailure = new AssertionError("close failed");
        FailingLease lease = new FailingLease(Boundary.PLAN, original, closeFailure);
        CompiledPlanAuthority authority = target -> lease;

        assertSame(original, assertThrows(IllegalStateException.class, () -> authority.acquireRequired(TARGET)));
        assertTrue(lease.closed);
        assertEquals(1, original.getSuppressed().length);
        assertSame(closeFailure, original.getSuppressed()[0]);
    }

    @Test
    void aCloseRuntimeFailureIsSuppressedWithoutReplacingTheValidationError() {
        AssertionError original = new AssertionError("validation failed");
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        FailingLease lease = new FailingLease(Boundary.CACHE_KEY, original, closeFailure);
        CompiledPlanAuthority authority = target -> lease;

        assertSame(original, assertThrows(AssertionError.class, () -> authority.acquireRequired(TARGET)));
        assertTrue(lease.closed);
        assertEquals(1, original.getSuppressed().length);
        assertSame(closeFailure, original.getSuppressed()[0]);
    }

    @Test
    void repeatingTheSameFailureDuringCloseCannotCauseSelfSuppressionToLoseTheOriginal() {
        AssertionError original = new AssertionError("validation and close failed");
        FailingLease lease = new FailingLease(Boundary.ACTIVE, original, original);
        CompiledPlanAuthority authority = target -> lease;

        assertSame(original, assertThrows(AssertionError.class, () -> authority.acquireRequired(TARGET)));
        assertTrue(lease.closed);
        assertEquals(0, original.getSuppressed().length);
    }

    private enum Boundary {
        ACTIVE, CACHE_KEY, PLAN
    }

    private static final class FailingLease implements CompiledPlanLease {
        private final Boundary boundary;
        private final Throwable validationFailure;
        private final Throwable closeFailure;
        private boolean closed;

        private FailingLease(Boundary boundary, Throwable validationFailure, Throwable closeFailure) {
            this.boundary = boundary;
            this.validationFailure = validationFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public boolean active() {
            if (boundary == Boundary.ACTIVE) {
                fail(validationFailure);
            }
            return true;
        }

        @Override
        public CompiledPlanCacheKey cacheKey() {
            if (boundary == Boundary.CACHE_KEY) {
                fail(validationFailure);
            }
            return KEY;
        }

        @Override
        public CompiledExecutionPlan plan() {
            fail(validationFailure);
            throw new AssertionError("Failed Plan Admission Cannot Return A Plan");
        }

        @Override
        public void close() {
            closed = true;
            if (closeFailure != null) {
                fail(closeFailure);
            }
        }

        private static void fail(Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (Error) failure;
        }
    }
}
