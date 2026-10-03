package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLeaseLifecycleTest {
    @Test
    void readinessExceptionsCompensateStagedResources() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("readiness-exception");
        RuntimeOperationDescriptor operation = operation("readiness-exception", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        AtomicBoolean compensated = new AtomicBoolean();
        RuntimeProviderLifecycle lifecycle = new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor ignored, List<RuntimeBindingDescriptor> bindings) {
                throw new IllegalStateException("not ready");
            }

            @Override
            public void compensate() {
                compensated.set(true);
            }

            @Override
            public void shutdown() {
            }
        };

        assertThrows(IllegalStateException.class, () -> registry.stage(
            providerDescriptor(provider),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
                CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok"))))),
            lifecycle));
        assertTrue(compensated.get());
    }

    @Test
    void compensationFailureDoesNotReplaceReadinessFailure() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("readiness-compensation-failure");
        RuntimeOperationDescriptor operation = operation("readiness-compensation-failure", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeProviderLifecycle lifecycle = new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor ignored, List<RuntimeBindingDescriptor> bindings) {
                throw new IllegalStateException("readiness failed");
            }

            @Override
            public void compensate() {
                throw new IllegalArgumentException("compensation failed");
            }

            @Override
            public void shutdown() {
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> registry.stage(
            providerDescriptor(provider),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
                CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok"))))),
            lifecycle));

        assertEquals("readiness failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("compensation failed", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void contextBoundaryRejectsSuccessWithoutPolicyEvidence() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(RuntimeTestSupport.nonEnforcingExecutionBoundary());
        ContractRef<ProviderId> provider = provider("missing-policy-evidence");
        RuntimeOperationDescriptor operation = operation("missing-policy-evidence", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok"))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "missing-policy-evidence")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.INVALID_INVOCATION", result.failure().diagnostic().code());
        lease.close();
    }

    @Test
    void contextBoundaryRejectsFailureWithoutHandlerEvidence() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(terminalBoundary(
            RuntimeResult.failure(new RuntimeFailure(diagnostic("RUNTIME.HANDLER_FAILURE"), false, value("failure", "not-controlled")))));
        ContractRef<ProviderId> provider = provider("missing-failure-evidence");
        RuntimeOperationDescriptor operation = operation("missing-failure-evidence", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.success(value("string", "unexpected"))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "missing-failure-evidence")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.INVALID_INVOCATION", result.failure().diagnostic().code());
        lease.close();
    }

    @Test
    void contextBoundaryRejectsCancellationWithoutHandlerEvidence() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(terminalBoundary(
            RuntimeResult.cancelled(diagnostic("RUNTIME.CANCELLED"), "cancelled")));
        ContractRef<ProviderId> provider = provider("missing-cancellation-evidence");
        RuntimeOperationDescriptor operation = operation("missing-cancellation-evidence", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.success(value("string", "unexpected"))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "missing-cancellation-evidence")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.INVALID_INVOCATION", result.failure().diagnostic().code());
        lease.close();
    }

    @Test
    void auditEventsUseOnlyOpaqueIdentityReferences() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("audit-reference");
        RuntimeOperationDescriptor operation = operation("audit-reference", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok"))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        lease.execute(operation.key(), Map.of(), "audit-key").toCompletableFuture().get(2, TimeUnit.SECONDS);

        RuntimeLeaseInput.AuditEvent event = lease.auditEvents().getFirst();
        assertFalse(event.authorityReference().contains("test-authority"));
        assertFalse(event.bindingReference().contains(operation.key().canonical()));
        assertNotEquals(lease.leaseId(), event.leaseId());
        lease.close();
    }

    @Test
    void externalAuditCanReenterTheLeaseWithoutHoldingItsLock() throws Exception {
        RuntimePlanLease[] leaseReference = new RuntimePlanLease[1];
        RuntimeAuditBoundary audit = new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit policy) {
                return true;
            }

            @Override
            public void record(RuntimeLeaseInput.AuditEvent event) {
                try {
                    CompletableFuture.supplyAsync(() -> leaseReference[0].auditEvents())
                        .get(1, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(RuntimeTestSupport.enforcingExecutionBoundary(), audit);
        ContractRef<ProviderId> provider = provider("audit-reentrant");
        RuntimeOperationDescriptor operation = operation("audit-reentrant", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok"))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        leaseReference[0] = lease;

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "audit-reentrant")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.SUCCESS, result.status());
        lease.close();
    }

    @Test
    void auditCallbackFailureCompletesTheOutcome() throws Exception {
        RuntimeAuditBoundary audit = new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit policy) {
                return true;
            }

            @Override
            public void record(RuntimeLeaseInput.AuditEvent event) {
                throw new IllegalStateException("audit unavailable");
            }
        };
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(RuntimeTestSupport.enforcingExecutionBoundary(), audit);
        ContractRef<ProviderId> provider = provider("audit-failure");
        RuntimeOperationDescriptor operation = operation("audit-failure", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok"))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "audit-failure")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.SUCCESS, result.status());
        lease.close();
    }

    @Test
    void failurePayloadIsRequiredByTheOperationContract() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("failure-payload");
        RuntimeOperationDescriptor operation = operation("failure-payload", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.failure(new RuntimeFailure(
                diagnostic("RUNTIME.HANDLER_FAILURE"), false))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "failure-payload")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.FAILURE_PAYLOAD_TYPE", result.failure().diagnostic().code());
        lease.close();
    }

    @Test
    void wrongFailurePayloadTypeIsRejectedByTheOperationContract() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("wrong-failure-payload");
        RuntimeOperationDescriptor operation = operation("wrong-failure-payload", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.failure(new RuntimeFailure(
                diagnostic("RUNTIME.HANDLER_FAILURE"), false, value("string", "wrong")))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "wrong-failure-payload")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.FAILURE_PAYLOAD_TYPE", result.failure().diagnostic().code());
        lease.close();
    }

    @Test
    void absentFailurePayloadStateIsRejectedByTheOperationContract() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("absent-failure-payload");
        RuntimeOperationDescriptor operation = operation("absent-failure-payload", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.failure(new RuntimeFailure(
                diagnostic("RUNTIME.HANDLER_FAILURE"), false, TypedValue.absent(type("failure"))))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "absent-failure-payload")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.FAILURE_PAYLOAD_TYPE", result.failure().diagnostic().code());
        lease.close();
    }

    @Test
    void cancellationDiagnosticAndBranchAreContractChecked() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("cancellation-contract");
        RuntimeOperationDescriptor operation = operation("cancellation-contract", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.cancelled(
                new RuntimeFailure(diagnostic("GRAPH.NULL"), false, value("failure", "cancelled")), "cancelled")));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "cancellation-contract")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.INVALID_INVOCATION", result.failure().diagnostic().code());
        lease.close();
    }

    @Test
    void cancellationPayloadIsRequiredByTheOperationContract() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("cancellation-payload");
        RuntimeOperationDescriptor operation = operation("cancellation-payload", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.cancelled(diagnostic("RUNTIME.CANCELLED"), "cancelled")));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "cancellation-payload")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.FAILURE_PAYLOAD_TYPE", result.failure().diagnostic().code());
        lease.close();
    }

    @Test
    void cancellationBranchMismatchIsRejected() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("cancellation-branch");
        RuntimeOperationDescriptor operation = operation("cancellation-branch", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.cancelled(
                new RuntimeFailure(diagnostic("RUNTIME.HANDLER_FAILURE"), false, value("failure", "cancelled")), "wrong")));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "cancellation-branch")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.RESULT_BRANCH_INVALID", result.failure().diagnostic().code());
        lease.close();
    }

    @Test
    void cancellationSettlesTheResultWhilePhysicalWorkStillFencesDrain() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("physical-cancellation");
        RuntimeOperationDescriptor operation = operation("physical-cancellation", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> pending);
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        RuntimeCancellationToken token = new RuntimeCancellationToken();
        CompletableFuture<RuntimeResult> execution = lease.execute(new RuntimeInvocation(
            operation.key(), Map.of(), "physical-cancellation", token)).toCompletableFuture();

        token.cancel();
        assertEquals(RuntimeResult.Status.CANCELLED, execution.get(2, TimeUnit.SECONDS).status());
        lease.close();
        CompletableFuture<Void> drained = lease.executionDrainSignal().toCompletableFuture();
        assertFalse(drained.isDone());
        assertFalse(lease.isReleased());
        assertFalse(pending.isDone());

        pending.complete(RuntimeResult.success(value("string", "late")));
        drained.get(2, TimeUnit.SECONDS);
        assertTrue(lease.isReleased());
        assertEquals(RuntimeResult.Status.CANCELLED, execution.join().status());
    }

    @Test
    void rejectedPrincipalSetupReleasesItsPhysicalAdmission() {
        RuntimeSecurityBoundary security = new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability) {
                return true;
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return true;
            }

            @Override
            public boolean requiresTrustedPrincipal() {
                return true;
            }
        };
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(RuntimeTestSupport.enforcingExecutionBoundary(), security);
        ContractRef<ProviderId> provider = provider("missing-principal");
        RuntimeOperationDescriptor operation = operation("missing-principal", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        AtomicBoolean called = new AtomicBoolean();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> {
            called.set(true);
            return CompletableFuture.completedFuture(RuntimeResult.success(value("string", "unexpected")));
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        assertThrows(IllegalStateException.class, () -> lease.execute(operation.key(), Map.of(), "missing-principal"));
        lease.close();

        assertFalse(called.get());
        assertTrue(lease.isReleased());
        assertTrue(lease.executionDrainSignal().toCompletableFuture().isDone());
    }

    @Test
    void cancellationTokenProducesTheDeclaredCancellationResult() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("cancellation-token");
        RuntimeOperationDescriptor operation = operation("cancellation-token", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> pending);
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        RuntimeCancellationToken token = new RuntimeCancellationToken();

        CompletableFuture<RuntimeResult> execution = lease.execute(new RuntimeInvocation(
            operation.key(), Map.of(), "cancellation-token", token)).toCompletableFuture();
        token.cancel();

        RuntimeResult result = execution.get(2, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.CANCELLED, result.status());
        assertEquals("RUNTIME.CANCELLED", result.failure().diagnostic().code());
        assertEquals("cancelled", result.branch());
        pending.complete(RuntimeResult.success(value("string", "late")));
        lease.close();
    }

    @Test
    void stagedHandlerCannotRunBeforeAtomicPublication() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("staged");
        RuntimeOperationDescriptor operation = operation("staged", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok"))));

        RuntimeBindingRegistry.StagedProvider staged = registry.stage(providerDescriptor(provider), List.of(binding));
        assertThrows(RuntimeCapabilityUnavailableException.class, () -> staged.bindings().getFirst().handler().orElseThrow()
            .execute(new RuntimeInvocation(operation.key(), "before", new RuntimeCancellationToken())));

        registry.publish(staged);
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        assertEquals(RuntimeResult.Status.SUCCESS, lease.execute(operation.key(), Map.of(), "after").toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        lease.close();
    }

    @Test
    void timeoutReturnsFailureAndRetainsLeaseUntilHandlerCompletes() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("timeout");
        RuntimeOperationDescriptor operation = operation("timeout", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 20);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> pending);
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "timeout").toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(RuntimeResult.Status.FAILURE, result.status());
        assertEquals("RUNTIME.EXECUTION_TIMEOUT", result.failure().diagnostic().code());
        assertFalse(lease.isReleased());
        pending.complete(RuntimeResult.success(value("string", "late")));
        lease.close();
        assertTrue(lease.isReleased());
    }

    @Test
    void expiredInvocationIsRejectedBeforeTheHandlerRuns() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("expired-before-handler");
        RuntimeOperationDescriptor operation = operation("expired-before-handler", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        AtomicBoolean called = new AtomicBoolean();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> {
            called.set(true);
            return CompletableFuture.completedFuture(RuntimeResult.success(value("string", "unexpected")));
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        RuntimeCancellationToken token = new RuntimeCancellationToken(System.currentTimeMillis() - 1);

        RuntimeResult result = lease.execute(new RuntimeInvocation(
                operation.key(), Map.of(), "expired-before-handler", token))
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.EXECUTION_TIMEOUT", result.failure().diagnostic().code());
        assertFalse(called.get());
        lease.close();
    }

    @Test
    void retryCannotRefreshTheInvocationDeadline() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("fixed-retry-deadline");
        RuntimeOperationDescriptor operation = operation("fixed-retry-deadline", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.SAFE, RuntimeSemantics.Idempotency.MUTATION_ID, 25);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> {
            calls.incrementAndGet();
            return pending;
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "fixed-retry-deadline")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.EXECUTION_TIMEOUT", result.failure().diagnostic().code());
        assertEquals(1, calls.get());
        pending.complete(RuntimeResult.success(value("string", "late")));
        lease.close();
    }

    @Test
    void effectiveDeadlineUsesTheMostRestrictiveCapabilityBudget() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("effective-deadline");
        RuntimeOperationDescriptor operation = operation("effective-deadline", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 50,
            100, RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Audit.METADATA);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        AtomicReference<RuntimeInvocation> observed = new AtomicReference<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
            observed.set(invocation);
            return pending;
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        long suppliedDeadline = System.currentTimeMillis() + 25;
        RuntimeCancellationToken token = new RuntimeCancellationToken(suppliedDeadline);

        RuntimeResult result = lease.execute(new RuntimeInvocation(
                operation.key(), Map.of(), "effective-deadline", token))
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("RUNTIME.EXECUTION_TIMEOUT", result.failure().diagnostic().code());
        assertTrue(observed.get().deadlineMillis() <= suppliedDeadline);
        pending.complete(RuntimeResult.success(value("string", "late")));
        lease.close();
    }

    @Test
    void mutationIdRetriesOnlyOnceAndDeduplicatesTheReceipt() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("retry");
        RuntimeOperationDescriptor operation = operation("retry", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.SAFE, RuntimeSemantics.Idempotency.MUTATION_ID, 0);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> {
            if (calls.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(RuntimeResult.failure(new RuntimeFailure(
                    diagnostic("RUNTIME.HANDLER_FAILURE"), true, value("failure", "retry"))));
            }
            return CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok")));
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult first = lease.execute(operation.key(), Map.of(), "mutation").toCompletableFuture().get(2, TimeUnit.SECONDS);
        RuntimeResult second = lease.execute(operation.key(), Map.of(), "mutation").toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(RuntimeResult.Status.SUCCESS, first.status());
        assertEquals(first, second);
        assertEquals(2, calls.get());
        lease.close();
    }

    @Test
    void policyRetryRequiresServerApproval() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(RuntimeTestSupport.enforcingExecutionBoundary(), false);
        ContractRef<ProviderId> provider = provider("policy-retry-denied");
        RuntimeOperationDescriptor operation = operation("policy-retry-denied", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.POLICY, RuntimeSemantics.Idempotency.MUTATION_ID, 0);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.failure(new RuntimeFailure(
                diagnostic("RUNTIME.HANDLER_FAILURE"), true, value("failure", "retry"))));
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "policy-retry-denied")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.FAILURE, result.status());
        assertEquals(1, calls.get());
        lease.close();
    }

    @Test
    void policyRetryRunsAfterServerApproval() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(RuntimeTestSupport.enforcingExecutionBoundary(), true);
        ContractRef<ProviderId> provider = provider("policy-retry-approved");
        RuntimeOperationDescriptor operation = operation("policy-retry-approved", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.POLICY, RuntimeSemantics.Idempotency.MUTATION_ID, 0);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> {
            if (calls.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(RuntimeResult.failure(new RuntimeFailure(
                    diagnostic("RUNTIME.HANDLER_FAILURE"), true, value("failure", "retry"))));
            }
            return CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok")));
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "policy-retry-approved")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.SUCCESS, result.status());
        assertEquals(2, calls.get());
        lease.close();
    }

    @Test
    void retryReauthorizesAndReconfirmsBeforeRunningAgain() throws Exception {
        AtomicInteger authorizations = new AtomicInteger();
        AtomicInteger confirmations = new AtomicInteger();
        RuntimeSecurityBoundary security = new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability) {
                return authorizations.incrementAndGet() == 1;
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                confirmations.incrementAndGet();
                return true;
            }

            @Override
            public boolean approvePolicyRetry(
                RuntimeAuthority authority,
                RuntimeBindingKey binding,
                RuntimeFailure failure,
                int attempt
            ) {
                return true;
            }
        };
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(RuntimeTestSupport.enforcingExecutionBoundary(), security);
        ContractRef<ProviderId> provider = provider("retry-reauthorize");
        RuntimeOperationDescriptor operation = operation("retry-reauthorize", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.POLICY, RuntimeSemantics.Idempotency.MUTATION_ID, 0);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.failure(new RuntimeFailure(
                diagnostic("RUNTIME.HANDLER_FAILURE"), true, value("failure", "retry"))));
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "retry-reauthorize")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.FAILURE, result.status());
        assertEquals(1, calls.get());
        assertEquals(2, authorizations.get());
        assertEquals(1, confirmations.get());
        lease.close();
    }

    @Test
    void retryApprovalFailureCompletesTheOutcomeWithoutRetrying() throws Exception {
        RuntimeSecurityBoundary security = new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability) {
                return true;
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return true;
            }

            @Override
            public boolean approvePolicyRetry(
                RuntimeAuthority authority,
                RuntimeBindingKey binding,
                RuntimeFailure failure,
                int attempt
            ) {
                throw new IllegalStateException("approval unavailable");
            }
        };
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(RuntimeTestSupport.enforcingExecutionBoundary(), security);
        ContractRef<ProviderId> provider = provider("retry-approval-failure");
        RuntimeOperationDescriptor operation = operation("retry-approval-failure", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.POLICY, RuntimeSemantics.Idempotency.MUTATION_ID, 0);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.failure(new RuntimeFailure(
                diagnostic("RUNTIME.HANDLER_FAILURE"), true, value("failure", "retry"))));
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "retry-approval-failure")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.FAILURE, result.status());
        assertEquals(1, calls.get());
        lease.close();
    }

    @Test
    void sensitiveHandlerDiagnosticsLoseEvidenceAndIdentity() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("sensitive-diagnostic");
        RuntimeOperationDescriptor operation = operation("sensitive-diagnostic", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0,
            50, RuntimeSemantics.SensitiveData.REDACTED, RuntimeSemantics.Audit.FULL_REDACTED);
        Diagnostic source = Diagnostic.builder("RUNTIME.HANDLER_FAILURE", DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
            .messageKey(new ContractRef<>(new OwnerId("handler.secret"), new CapabilityId("private")))
            .message("Runtime Failure")
            .arguments(Map.of("secret", "private"))
            .evidence(Map.of("token", "private"))
            .remediation("Retry the operation.")
            .correlationId(UUID.randomUUID())
            .build();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored ->
            CompletableFuture.completedFuture(RuntimeResult.failure(new RuntimeFailure(
                source, false, value("failure", "secret")))));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "sensitive-diagnostic")
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(result.failure().diagnostic().arguments().isEmpty());
        assertTrue(result.failure().diagnostic().evidence().isEmpty());
        assertNotEquals(source.correlationId(), result.failure().diagnostic().correlationId());
        assertEquals(TypedValue.State.NULL, result.failure().payload().state());
        lease.close();
    }

    @Test
    void unsafeNonCancellableWorkBlocksOrdinaryUnload() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("unsafe");
        RuntimeOperationDescriptor operation = operation("unsafe", RuntimeSemantics.Cancellation.NONE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0,
            RuntimeSemantics.Effect.EXTERNAL_IO);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> pending);
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        lease.execute(operation.key(), Map.of(), "unsafe");

        RuntimeUnloadResult result = registry.unload(provider);
        assertEquals(RuntimeUnloadResult.Status.BLOCKED, result.status());
        assertEquals(RuntimeProviderState.ACTIVE, registry.snapshot().provider(provider).orElseThrow().state());
        pending.complete(RuntimeResult.success(value("string", "done")));
        lease.close();
    }

    @Test
    void ordinaryUnloadNeverRevokesAProviderWithRemainingWork() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("ordinary-drain");
        RuntimeOperationDescriptor operation = operation("ordinary-drain", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> pending);
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 10, RuntimeSemantics.UnloadPolicy.BLOCK), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        lease.execute(operation.key(), Map.of(), "ordinary-drain");

        RuntimeUnloadResult result = registry.unload(provider);

        assertEquals(RuntimeUnloadResult.Status.BLOCKED, result.status());
        assertEquals(RuntimeProviderState.ACTIVE, registry.snapshot().provider(provider).orElseThrow().state());
        pending.complete(RuntimeResult.success(value("string", "done")));
        lease.close();
    }

    @Test
    void leaseAdmissionCompletesItsDrainSignalAfterClose() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("lease-admission-drain");
        RuntimeOperationDescriptor operation = operation("lease-admission-drain", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> pending);
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));

        CompletableFuture<RuntimeResult> execution = lease.execute(operation.key(), Map.of(), "lease-admission-drain")
            .toCompletableFuture();

        assertEquals(1, lease.activeInvocations());
        lease.close();
        assertFalse(lease.executionDrainSignal().toCompletableFuture().isDone());

        pending.complete(RuntimeResult.success(value("string", "done")));

        assertEquals(RuntimeResult.Status.SUCCESS, execution.get(2, TimeUnit.SECONDS).status());
        lease.executionDrainSignal().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(lease.executionDrainSignal().toCompletableFuture().isDone());
        assertEquals(0, lease.activeInvocations());
    }

    @Test
    void securityRevokeCanRevokeRemainingWorkOnlyOnTheExplicitEmergencyPath() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("security-revoke");
        RuntimeOperationDescriptor operation = operation("security-revoke", RuntimeSemantics.Cancellation.COOPERATIVE,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, 0);
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> pending);
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 10, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        lease.execute(operation.key(), Map.of(), "security-revoke");

        RuntimeUnloadResult result = registry.securityRevoke(provider);

        assertEquals(RuntimeUnloadResult.Status.REVOKED, result.status());
        assertEquals(RuntimeProviderState.REVOKED, registry.snapshot().provider(provider).orElseThrow().state());
        pending.complete(RuntimeResult.success(value("string", "done")));
        lease.close();
    }

    private static RuntimeLeaseInput.BindingRequirement requirement(RuntimeOperationDescriptor operation, RuntimeBinding binding) {
        return new RuntimeLeaseInput.BindingRequirement(operation.key(), binding.executionFingerprint(), List.of(), List.of(PinId.of("output")));
    }

    private static RuntimeExecutionBoundary terminalBoundary(RuntimeResult result) {
        return new RuntimeExecutionBoundary() {
            @Override
            public boolean supports(RuntimeExecutionContext context) {
                return true;
            }

            @Override
            public CompletableFuture<RuntimeResult> execute(
                RuntimeExecutionContext context,
                RuntimeInvocation invocation,
                RuntimeOperationHandler handler
            ) {
                return CompletableFuture.completedFuture(result);
            }

            @Override
            public boolean approveSafeRetry(RuntimeExecutionContext context, RuntimeFailure failure, int attempt) {
                return false;
            }
        };
    }

    private static RuntimeProviderDescriptor providerDescriptor(ContractRef<ProviderId> provider) {
        return new RuntimeProviderDescriptor(provider, "1.0.0", 0, 50, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static RuntimeOperationDescriptor operation(
        String id,
        RuntimeSemantics.Cancellation cancellation,
        RuntimeSemantics.UnloadPolicy unloadPolicy,
        RuntimeSemantics.Retry retry,
        RuntimeSemantics.Idempotency idempotency,
        long timeout
    ) {
        return operation(id, cancellation, unloadPolicy, retry, idempotency, timeout, RuntimeSemantics.Effect.STATE_MUTATING);
    }

    private static RuntimeOperationDescriptor operation(
        String id,
        RuntimeSemantics.Cancellation cancellation,
        RuntimeSemantics.UnloadPolicy unloadPolicy,
        RuntimeSemantics.Retry retry,
        RuntimeSemantics.Idempotency idempotency,
        long timeout,
        RuntimeSemantics.Effect effect
    ) {
        return operation(id, cancellation, unloadPolicy, retry, idempotency, timeout, 50, effect,
            RuntimeSemantics.Audit.METADATA, RuntimeSemantics.SensitiveData.NONE);
    }

    private static RuntimeOperationDescriptor operation(
        String id,
        RuntimeSemantics.Cancellation cancellation,
        RuntimeSemantics.UnloadPolicy unloadPolicy,
        RuntimeSemantics.Retry retry,
        RuntimeSemantics.Idempotency idempotency,
        long timeout,
        long hardDeadline,
        RuntimeSemantics.SensitiveData sensitiveData,
        RuntimeSemantics.Audit audit
    ) {
        return operation(id, cancellation, unloadPolicy, retry, idempotency, timeout, hardDeadline,
            RuntimeSemantics.Effect.STATE_MUTATING, audit, sensitiveData);
    }

    private static RuntimeOperationDescriptor operation(
        String id,
        RuntimeSemantics.Cancellation cancellation,
        RuntimeSemantics.UnloadPolicy unloadPolicy,
        RuntimeSemantics.Retry retry,
        RuntimeSemantics.Idempotency idempotency,
        long timeout,
        long hardDeadline,
        RuntimeSemantics.Effect effect,
        RuntimeSemantics.Audit audit,
        RuntimeSemantics.SensitiveData sensitiveData
    ) {
        return new RuntimeOperationDescriptor(
            capability(id),
            operationId(id),
            List.of(new RuntimeOperationDescriptor.Pin("output", RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
            new RuntimeSemantics(
                effect,
                RuntimeSemantics.ThreadMode.CURRENT,
                capability("authorization"),
                cancellation,
                timeout,
                0,
                hardDeadline,
                unloadPolicy,
                retry,
                idempotency,
                audit,
                RuntimeSemantics.Confirmation.NONE,
                sensitiveData,
                RuntimeSemantics.Determinism.DETERMINISTIC,
                Set.of("success"),
                Set.of("failure"),
                Set.of("cancelled"),
                new RuntimeFailureContract(type("failure"), Set.of("RUNTIME.HANDLER_FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.ATOMIC),
                Set.of(),
                Set.of()));
    }

    private static ContractRef<CapabilityId> capability(String id) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new CapabilityId(id));
    }

    private static ContractRef<OperationId> operationId(String id) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new OperationId(id));
    }

    private static ContractRef<ProviderId> provider(String id) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new ProviderId(id));
    }

    private static TypeExpr type(String id) {
        return TypeExpr.named(TypeReference.of("restudio.resync", id));
    }

    private static TypedValue value(String type, String value) {
        return TypedValue.value(type(type), value);
    }

    private static Diagnostic diagnostic(String code) {
        return Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
            .messageKey(new ContractRef<>(new OwnerId("restudio.resync"), new CapabilityId("runtime")))
            .message("Runtime Failure")
            .remediation("Retry the operation.")
            .correlationId(UUID.randomUUID())
            .build();
    }
}
