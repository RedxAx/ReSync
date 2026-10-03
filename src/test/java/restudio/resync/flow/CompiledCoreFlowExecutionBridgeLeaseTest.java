package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.CompiledExecutionStep;
import restudio.resync.flow.graph.CompiledPlanCacheKey;
import restudio.resync.flow.graph.CompiledPlanLease;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledCoreFlowExecutionBridgeLeaseTest {
    @Test
    void anErrorReadingAnAdmittedPlanReleasesItsLeaseBeforeObservationCompletes() {
        assertStartFailure(Stage.PLAN, false);
    }

    @Test
    void anErrorReadingAnAdmittedTemplateReleasesItsLeaseBeforeObservationCompletes() {
        assertStartFailure(Stage.TEMPLATE, false);
    }

    @Test
    void anErrorPreparingAnAdmittedPlanReleasesItsLeaseBeforeObservationCompletes() {
        assertStartFailure(Stage.PREPARATION, false);
    }

    @Test
    void aReleaseErrorPreservesTheOriginalFailureAndDoesNotClaimPhysicalCompletion() {
        assertStartFailure(Stage.PLAN, true);
    }

    @Test
    void rethrowingTheOriginalErrorDuringReleaseDoesNotReplaceItWithSelfSuppressionFailure() {
        assertStartFailure(Stage.PLAN, true, true);
    }

    @Test
    void failedAdmissionAndFailedReleaseDoNotClaimPhysicalCompletion() {
        assertStartFailure(Stage.ADMISSION, true);
    }

    private void assertStartFailure(Stage stage, boolean releaseFails) {
        assertStartFailure(stage, releaseFails, false);
    }

    private void assertStartFailure(Stage stage, boolean releaseFails, boolean sameFailure) {
        OwnerId owner = OwnerId.of("restudio.resync");
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.deterministic("lease-error-test"),
            ContractRef.of(owner, ResourceTypeId.of("flow")), "lease-error");
        CatalogBinding binding = new CatalogBinding(1, ContentHash.of("0".repeat(64)), ContentHash.of("1".repeat(64)));
        NodeInstanceId node = NodeInstanceId.deterministic("lease-error-start");
        CompiledExecutionStep step = new CompiledExecutionStep(UUID.randomUUID(), node,
            ContractRef.of(owner, NodeId.of("start")), new CatalogNodeDescriptor.Handler(
                ContractRef.of(owner, CapabilityId.of("test")), ContractRef.of(owner, OperationId.of("test"))),
            Map.of(), Map.of(), OpaqueData.empty());
        CompiledExecutionPlan plan = new CompiledExecutionPlan(UUID.randomUUID(), resource, 1, binding,
            ContentHash.of("2".repeat(64)), List.of(step), List.of(), List.of(), OpaqueData.empty());
        AssertionError original = new AssertionError("Plan Start Failed");
        AssertionError release = sameFailure ? original : new AssertionError("Plan Release Failed");
        AtomicBoolean admitted = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        CompiledPlanLease lease = new CompiledPlanLease() {
            @Override
            public CompiledPlanCacheKey cacheKey() {
                return CompiledPlanCacheKey.from(plan);
            }

            @Override
            public boolean active() {
                return admitted.get();
            }

            @Override
            public CompiledExecutionPlan plan() {
                int read = reads.incrementAndGet();
                if (stage == Stage.ADMISSION || read > 1 && stage == Stage.PLAN) {
                    throw original;
                }
                return plan;
            }

            @Override
            public Optional<CompiledExecutionRunner.ExecutionTemplate> executionTemplate() {
                if (stage == Stage.TEMPLATE) {
                    throw original;
                }
                return Optional.empty();
            }

            @Override
            public void close() {
                if (releaseFails) {
                    throw release;
                }
                admitted.set(false);
            }
        };
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(() -> { throw original; },
            new RuntimeBindingRegistry(), new RuntimeAuthority("lease-error-test"), null, target -> lease);
        FlowGraph graph = new FlowGraph();
        graph.setId(resource.id());
        graph.setResourceType("flow");
        graph.setResourceRevision(1);
        CompiledGraphMetadata metadata = new CompiledGraphMetadata(resource, binding,
            SnapshotId.deterministic("lease-error-metadata"), Map.of("start", node), Map.of(), Map.of(), Map.of());
        CorrelationId invocation = CorrelationId.random();
        CompiledCoreFlowExecutionBridge.ExecutionObservation observation = bridge.observeInvocation(invocation);

        FlowExecutionBridge.Result result = bridge.execute(new FlowExecutionBridge.Context(graph, "start", null, metadata,
            CompiledRuntimeContext.empty(), invocation)).toCompletableFuture().join();
        CompiledCoreFlowExecutionBridge.ObservedExecution observed = observation.completion().toCompletableFuture().join();

        assertEquals(FlowExecutionBridge.Status.FAILED, result.status());
        assertSame(original, result.failure());
        assertSame(original, observed.failure());
        if (releaseFails) {
            assertTrue(admitted.get());
            assertEquals(sameFailure ? List.of() : List.of(release), List.of(original.getSuppressed()));
            assertFalse(observed.physicalComplete());
            assertEquals(false, observed.canonicalValue().get("physicalComplete"));
        } else {
            assertFalse(admitted.get());
            assertTrue(observed.physicalComplete());
        }
    }

    private enum Stage {
        ADMISSION,
        PLAN,
        TEMPLATE,
        PREPARATION
    }
}
