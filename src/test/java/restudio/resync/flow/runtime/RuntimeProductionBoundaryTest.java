package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostics.StructuredFlowDiagnosticReporter;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeProductionBoundaryTest {
    private static final OwnerId OWNER = OwnerId.of("resync");
    private static final RuntimeAuthority AUTHORITY = new RuntimeAuthority("resync:server");
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OWNER, ProviderId.of("flow"));

    @Test
    void authorizationMembershipFollowsExactActiveSnapshotAndRevocation() {
        AtomicReference<RuntimeBindingRegistry> reference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY, () -> reference.get().snapshot());
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), RuntimeReceiptStore.inMemory(true));
        reference.set(registry);
        RuntimeOperationDescriptor allowed = operation("allowed", RuntimeSemantics.ThreadMode.CURRENT, RuntimeSemantics.Audit.NONE);
        RuntimeOperationDescriptor unavailable = operation("unavailable", RuntimeSemantics.ThreadMode.CURRENT, RuntimeSemantics.Audit.NONE);
        RuntimeBinding binding = RuntimeBinding.available(allowed, PROVIDER, "1.0.0",
            invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        assertFalse(security.authorize(AUTHORITY, allowed.semantics().authorization()));
        registry.activate(provider(), List.of(binding, RuntimeBinding.unavailable(unavailable, PROVIDER, "1.0.0")));
        RuntimeRegistrySnapshot active = registry.snapshot();
        ContractRef<CapabilityId> equal = ContractRef.of(OwnerId.of("resync"), CapabilityId.of("allowed"));
        assertTrue(security.authorize(AUTHORITY, equal));
        assertFalse(security.authorize(AUTHORITY, ContractRef.of(OwnerId.of("other"), CapabilityId.of("allowed"))));
        assertFalse(security.authorize(AUTHORITY, unavailable.semantics().authorization()));
        assertFalse(security.authorize(new RuntimeAuthority("resync:server"), equal));
        assertFalse(security.authorize(AUTHORITY, null));
        registry.unload(PROVIDER);
        assertFalse(security.authorize(AUTHORITY, equal));
        assertTrue(active.hasAuthorization(equal));
        registry.activate(provider(), List.of(binding));
        assertTrue(security.authorize(AUTHORITY, equal));
        reference.set(null);
        assertFalse(security.authorize(AUTHORITY, equal));
        assertFalse(new FlowRuntimeSecurityBoundary(AUTHORITY, () -> null).authorize(AUTHORITY, equal));
    }

    @Test
    void trustedAuthorityExecutesTheExactActiveBinding() throws Exception {
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        RuntimeOperationDescriptor operation = operation("execute", RuntimeSemantics.ThreadMode.CURRENT, RuntimeSemantics.Audit.NONE);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot());
        FlowRuntimeExecutionBoundary execution = new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(), execution, RuntimeReceiptStore.inMemory(true));
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));

        RuntimePlanLease lease = registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY));
        RuntimeResult result = lease.execute(operation.key(), Map.of(), "invocation-1").toCompletableFuture().get();
        lease.close();

        assertTrue(result.successful());
        assertEquals(1, calls.get());
    }

    @Test
    void durableMutationReplayUsesTheStoredOutcomeWithoutASecondSideEffect(@TempDir Path temporary) {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimePrincipal actor = principals.issuePlayer("player-1");
        AtomicInteger calls = new AtomicInteger();
        RuntimeOperationDescriptor operation = mutatingOperation("durable-replay");
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(temporary);
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(temporary.resolve("diagnostics"));
        StructuredRuntimeAuditBoundary audit = new StructuredRuntimeAuditBoundary(
            reporter, ServerId.deterministic("durable-replay"));
        AtomicReference<RuntimeBindingRegistry> firstReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary firstSecurity = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> firstReference.get() == null ? null : firstReference.get().snapshot(), principals);
        RuntimeBindingRegistry first = new RuntimeBindingRegistry(firstSecurity, audit,
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), receipts, principals);
        firstReference.set(first);
        first.activate(provider(), List.of(binding));
        RuntimePlanLease firstLease = first.acquire(input(operation, binding.executionFingerprint(), AUTHORITY, actor));

        RuntimeResult firstResult = firstLease.execute(operation.key(), Map.of(), "durable-replay")
            .toCompletableFuture().join();
        firstLease.close();

        DurableRuntimeReceiptStore restartedReceipts = new DurableRuntimeReceiptStore(temporary);
        AtomicReference<RuntimeBindingRegistry> secondReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary secondSecurity = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> secondReference.get() == null ? null : secondReference.get().snapshot(), principals);
        RuntimeBindingRegistry second = new RuntimeBindingRegistry(secondSecurity, audit,
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), restartedReceipts, principals);
        secondReference.set(second);
        second.activate(provider(), List.of(binding));
        RuntimePlanLease secondLease = second.acquire(input(operation, binding.executionFingerprint(), AUTHORITY, actor));

        RuntimeResult replay = secondLease.execute(operation.key(), Map.of(), "durable-replay")
            .toCompletableFuture().join();
        secondLease.close();

        assertTrue(firstResult.successful());
        assertTrue(replay.successful());
        assertEquals(1, calls.get());
        assertEquals(1, reporter.store().list().size());
    }

    @Test
    void concurrentSameActorMutationSharesTheDurableInFlightOutcome(@TempDir Path temporary) {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimePrincipal actor = principals.issuePlayer("player-concurrent");
        RuntimeOperationDescriptor operation = mutatingOperation("concurrent");
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<RuntimeResult> handlerOutcome = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return handlerOutcome;
        });
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(temporary);
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot(), principals);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), receipts, principals);
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY, actor));

        CompletionStage<RuntimeResult> first = lease.execute(operation.key(), Map.of(), "concurrent");
        CompletionStage<RuntimeResult> second = lease.execute(operation.key(), Map.of(), "concurrent");

        assertSame(first, second);
        assertEquals(1, calls.get());
        handlerOutcome.complete(RuntimeResult.success());
        assertTrue(first.toCompletableFuture().join().successful());
        lease.close();
        assertEquals(0, receipts.pendingAuditCount());
    }

    @Test
    void crossActorMutationReplayIsDeniedWithoutAnotherSideEffect(@TempDir Path temporary) {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimePrincipal firstActor = principals.issuePlayer("player-first");
        RuntimePrincipal secondActor = principals.issuePlayer("player-second");
        RuntimeOperationDescriptor operation = mutatingOperation("cross-actor");
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(temporary);
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot(), principals);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), receipts, principals);
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));

        RuntimePlanLease firstLease = registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY, firstActor));
        assertTrue(firstLease.execute(operation.key(), Map.of(), "cross-actor").toCompletableFuture().join().successful());
        firstLease.close();

        RuntimePlanLease secondLease = registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY, secondActor));
        RuntimeResult denied = secondLease.execute(operation.key(), Map.of(), "cross-actor").toCompletableFuture().join();
        secondLease.close();

        assertEquals(RuntimeResult.Status.FAILURE, denied.status());
        assertEquals("RUNTIME.AUTHORIZATION_DENIED", denied.failure().diagnostic().code());
        assertEquals(1, calls.get());
    }

    @Test
    void replayRejectsChangedRuntimeContextAndDeadline(@TempDir Path temporary) {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimePrincipal actor = principals.issuePlayer("player-context");
        RuntimeOperationDescriptor operation = mutatingOperation("context-fingerprint");
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(temporary);
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot(), principals);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), receipts, principals);
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY, actor));

        RuntimeResult first = lease.execute(new RuntimeInvocation(operation.key(), Map.of(), "context-fingerprint",
            new RuntimeCancellationToken()).withRuntimeContext(new CompiledRuntimeContext(
                new CompiledRuntimeContext.PlayerIdentity(UUID.fromString("11111111-1111-4111-8111-111111111111"), "Player"),
                null, Map.of()))) .toCompletableFuture().join();
        RuntimeResult changedContext = lease.execute(new RuntimeInvocation(operation.key(), Map.of(), "context-fingerprint",
            new RuntimeCancellationToken()).withRuntimeContext(CompiledRuntimeContext.empty())).toCompletableFuture().join();
        RuntimeResult changedDeadline = lease.execute(new RuntimeInvocation(operation.key(), Map.of(), "context-fingerprint",
            new RuntimeCancellationToken(System.currentTimeMillis() + 10_000L))).toCompletableFuture().join();
        lease.close();

        assertTrue(first.successful());
        assertEquals("RUNTIME.INVALID_INVOCATION", changedContext.failure().diagnostic().code());
        assertEquals("RUNTIME.INVALID_INVOCATION", changedDeadline.failure().diagnostic().code());
        assertEquals(1, calls.get());
    }

    @Test
    void quiescenceAllowsAdmittedInvocationToDurablyComplete(@TempDir Path temporary) {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimePrincipal actor = principals.issuePlayer("player-quiesce");
        RuntimeOperationDescriptor operation = mutatingOperation("quiesce-completion");
        CompletableFuture<RuntimeResult> handlerOutcome = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> handlerOutcome);
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(temporary);
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot(), principals);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), receipts, principals);
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY, actor));

        CompletionStage<RuntimeResult> outcome = lease.execute(operation.key(), Map.of(), "quiesce-completion");
        CompletableFuture<Void> quiesced = CompletableFuture.runAsync(() -> {
            try {
                receipts.quiesce();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });

        handlerOutcome.complete(RuntimeResult.success());
        assertTrue(outcome.toCompletableFuture().join().successful());
        quiesced.join();
        assertTrue(receipts.quiesced());
        lease.close();
    }

    @Test
    void completionDoesNotReportSuccessWhenReceiptPersistenceFails(@TempDir Path temporary) {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimePrincipal actor = principals.issuePlayer("player-persist-failure");
        RuntimeOperationDescriptor operation = mutatingOperation("persist-failure");
        AtomicInteger calls = new AtomicInteger();
        Path receiptDirectory = temporary.resolve(DurableRuntimeReceiptStore.DIRECTORY);
        Path receiptFile = temporary.resolve(DurableRuntimeReceiptStore.DIRECTORY)
            .resolve(DurableRuntimeReceiptStore.FILE_NAME);
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            try {
                Files.deleteIfExists(receiptFile);
                Files.deleteIfExists(receiptDirectory);
                Files.writeString(receiptDirectory, "blocked");
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(temporary);
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot(), principals);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), receipts, principals);
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY, actor));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "persist-failure").toCompletableFuture().join();
        lease.close();

        assertEquals(RuntimeResult.Status.FAILURE, result.status());
        assertEquals("RUNTIME.INVALID_INVOCATION", result.failure().diagnostic().code());
        assertEquals(1, calls.get());
    }

    @Test
    void postSideEffectAuditFailureRemainsPendingAcrossRestart(@TempDir Path temporary) {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimePrincipal actor = principals.issuePlayer("player-audit-recovery");
        RuntimeOperationDescriptor operation = mutatingOperation("audit-recovery");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger auditCalls = new AtomicInteger();
        AtomicReference<Boolean> failAudit = new AtomicReference<>(true);
        RuntimeAuditBoundary audit = new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit requested) {
                return true;
            }

            @Override
            public void record(RuntimeLeaseInput.AuditEvent event) {
                auditCalls.incrementAndGet();
                if (failAudit.get()) {
                    throw new IllegalStateException("audit unavailable");
                }
            }
        };
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(temporary);
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot(), principals);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit,
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), receipts, principals);
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY, actor));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "audit-recovery").toCompletableFuture().join();
        lease.close();

        assertTrue(result.successful());
        assertEquals(1, calls.get());
        assertEquals(1, auditCalls.get());
        assertEquals(1, receipts.pendingAuditCount());

        failAudit.set(false);
        DurableRuntimeReceiptStore restarted = new DurableRuntimeReceiptStore(temporary);
        assertEquals(1, restarted.retryPendingAudits(audit));
        assertEquals(2, auditCalls.get());
        assertEquals(0, restarted.pendingAuditCount());
        assertEquals(1, calls.get());
    }

    @Test
    void untrustedAuthorityIsDeniedBeforeHandlerSideEffects() {
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        RuntimeOperationDescriptor operation = operation("protected", RuntimeSemantics.ThreadMode.CURRENT, RuntimeSemantics.Audit.NONE);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot());
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), RuntimeReceiptStore.inMemory(true));
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));

        RuntimeCapabilityUnavailableException failure = assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.acquire(input(operation, binding.executionFingerprint(), new RuntimeAuthority("attacker"))));

        assertEquals("RUNTIME.AUTHORIZATION_DENIED", failure.diagnostic().code());
        assertEquals(0, calls.get());
    }

    @Test
    void forgedAuthorityWithTheSameIdentityIsDenied() {
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        RuntimeOperationDescriptor operation = operation("forged", RuntimeSemantics.ThreadMode.CURRENT, RuntimeSemantics.Audit.NONE);
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0",
            invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot());
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), RuntimeReceiptStore.inMemory(true));
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));

        RuntimeCapabilityUnavailableException failure = assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.acquire(input(operation, binding.executionFingerprint(), new RuntimeAuthority(AUTHORITY.identity()))));

        assertEquals("RUNTIME.AUTHORIZATION_DENIED", failure.diagnostic().code());
    }

    @Test
    void missingAuditBoundaryIsRejectedBeforeHandlerSideEffects() {
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        RuntimeOperationDescriptor operation = operation("audited", RuntimeSemantics.ThreadMode.CURRENT, RuntimeSemantics.Audit.METADATA);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot());
        RuntimeAuditBoundary unavailableAudit = new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit audit) {
                return audit == RuntimeSemantics.Audit.NONE;
            }

            @Override
            public void record(RuntimeLeaseInput.AuditEvent event) {
                throw new IllegalStateException("unavailable");
            }
        };
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, unavailableAudit,
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), RuntimeReceiptStore.inMemory(true));
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));

        assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY)));
        assertEquals(0, calls.get());
    }

    @Test
    void unavailableExecutionBoundaryIsRejectedBeforeHandlerSideEffects() {
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        RuntimeOperationDescriptor operation = operation("unavailable", RuntimeSemantics.ThreadMode.CURRENT, RuntimeSemantics.Audit.NONE);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot());
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(), RuntimeExecutionBoundary.unavailable(),
            RuntimeReceiptStore.inMemory(true));
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));

        assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.acquire(input(operation, binding.executionFingerprint(), AUTHORITY)));
        assertEquals(0, calls.get());
    }

    @Test
    void mismatchedLeaseFingerprintIsRejectedBeforeHandlerSideEffects() {
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        RuntimeOperationDescriptor operation = operation("fingerprint", RuntimeSemantics.ThreadMode.CURRENT, RuntimeSemantics.Audit.NONE);
        AtomicInteger calls = new AtomicInteger();
        RuntimeBinding binding = RuntimeBinding.available(operation, PROVIDER, "1.0.0", invocation -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot());
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true), RuntimeReceiptStore.inMemory(true));
        registryReference.set(registry);
        registry.activate(provider(), List.of(binding));

        ContentHash wrongFingerprint = ContentHash.of(CanonicalJson.sha256("wrong-runtime-fingerprint", Map.of("value", true)));
        assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.acquire(input(operation, wrongFingerprint, AUTHORITY)));
        assertEquals(0, calls.get());
    }

    @Test
    void structuredAuditIsDurablyPersisted(@TempDir Path temporary) {
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(temporary.resolve("diagnostics"));
        StructuredRuntimeAuditBoundary audit = new StructuredRuntimeAuditBoundary(reporter, ServerId.deterministic("runtime-boundary-test"));
        RuntimeLeaseInput.AuditEvent event = RuntimeAuditEvent.create(
            UUID.randomUUID(), AUTHORITY, operation("audit", RuntimeSemantics.ThreadMode.CURRENT, RuntimeSemantics.Audit.METADATA).key(),
            "operation-1", RuntimeResult.Status.SUCCESS, false).leaseEvent();

        audit.record(event);

        assertEquals(1, reporter.store().list().size());
        assertEquals("RUNTIME.INVOCATION_AUDIT", reporter.store().list().getFirst().diagnostics().diagnostics().getFirst().code());
    }

    @Test
    void structuredAuditSurvivesReporterRestart(@TempDir Path temporary) {
        Path directory = temporary.resolve("diagnostics");
        StructuredRuntimeAuditBoundary first = new StructuredRuntimeAuditBoundary(
            new StructuredFlowDiagnosticReporter(directory), ServerId.deterministic("runtime-boundary-restart"));
        RuntimeLeaseInput.AuditEvent event = RuntimeAuditEvent.create(
            UUID.randomUUID(), AUTHORITY, operation("restart-audit", RuntimeSemantics.ThreadMode.CURRENT,
                RuntimeSemantics.Audit.METADATA).key(), "operation-1", RuntimeResult.Status.SUCCESS, false).leaseEvent();

        first.record(event);

        StructuredFlowDiagnosticReporter restartedReporter = new StructuredFlowDiagnosticReporter(directory);
        StructuredRuntimeAuditBoundary restarted = new StructuredRuntimeAuditBoundary(
            restartedReporter, ServerId.deterministic("runtime-boundary-restart"));

        assertTrue(restarted.available());
        assertEquals(1, restartedReporter.store().list().size());
        restarted.record(event);
        assertEquals(1, restartedReporter.store().list().size());
    }

    @Test
    void boundaryHealthReportsInjectedProductionBoundaries(@TempDir Path temporary) {
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot());
        StructuredRuntimeAuditBoundary audit = new StructuredRuntimeAuditBoundary(
            new StructuredFlowDiagnosticReporter(temporary.resolve("diagnostics")),
            ServerId.deterministic("runtime-boundary-health"));
        FlowRuntimeExecutionBoundary execution = new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true);
        RuntimeReceiptStore receipts = RuntimeReceiptStore.inMemory(false);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit, execution, receipts);
        registryReference.set(registry);
        RuntimeBoundaryHealth health = new RuntimeBoundaryHealth(security, audit, execution, registry, receipts);

        assertTrue(health.ready());
        assertTrue((Boolean) ((Map<?, ?>) health.snapshot().get("security")).get("available"));
        assertTrue((Boolean) ((Map<?, ?>) health.snapshot().get("audit")).get("available"));
        assertFalse((Boolean) ((Map<?, ?>) health.snapshot().get("receipts")).get("durable"));
    }

    @Test
    void boundaryHealthReportsUnavailableExecutionBoundary(@TempDir Path temporary) {
        AtomicReference<RuntimeBindingRegistry> registryReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> registryReference.get() == null ? null : registryReference.get().snapshot());
        StructuredRuntimeAuditBoundary audit = new StructuredRuntimeAuditBoundary(
            new StructuredFlowDiagnosticReporter(temporary.resolve("diagnostics")),
            ServerId.deterministic("runtime-boundary-unavailable"));
        FlowRuntimeExecutionBoundary execution = new FlowRuntimeExecutionBoundary(null, null, () -> true);
        RuntimeReceiptStore receipts = RuntimeReceiptStore.inMemory(false);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit, execution, receipts);
        registryReference.set(registry);
        RuntimeBoundaryHealth health = new RuntimeBoundaryHealth(security, audit, execution, registry, receipts);

        assertFalse(health.ready());
        assertFalse((Boolean) ((Map<?, ?>) health.snapshot().get("execution")).get("available"));
    }

    private static RuntimeAuditBoundary audit() {
        return new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit audit) {
                return true;
            }

            @Override
            public void record(RuntimeLeaseInput.AuditEvent event) {
            }
        };
    }

    private static RuntimeLeaseInput input(RuntimeOperationDescriptor operation, ContentHash fingerprint, RuntimeAuthority authority) {
        return input(operation, fingerprint, authority, null);
    }

    private static RuntimeLeaseInput input(RuntimeOperationDescriptor operation, ContentHash fingerprint,
                                           RuntimeAuthority authority, RuntimePrincipal principal) {
        ContentHash plan = ContentHash.of(CanonicalJson.sha256("runtime-plan",
            Map.of("operation", operation.key().canonical())));
        return RuntimeLeaseInput.plan(List.of(new RuntimeLeaseInput.BindingRequirement(operation.key(), fingerprint)),
            plan, authority, principal);
    }

    private static RuntimeOperationDescriptor mutatingOperation(String id) {
        ContractRef<CapabilityId> capability = ContractRef.of(OWNER, CapabilityId.of(id));
        RuntimeSemantics semantics = new RuntimeSemantics(
            RuntimeSemantics.Effect.STATE_MUTATING,
            RuntimeSemantics.ThreadMode.CURRENT,
            capability,
            RuntimeSemantics.Cancellation.NONE,
            0,
            0,
            0,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.MUTATION_ID,
            RuntimeSemantics.Audit.METADATA,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of("success"),
            Set.of("failure"),
            Set.of(),
            new RuntimeFailureContract(
                TypeExpr.named(TypeReference.of("builtin", "any")),
                Set.of("RUNTIME.HANDLER_FAILURE"),
                Set.of("failure"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
        return new RuntimeOperationDescriptor(capability, ContractRef.of(OWNER, OperationId.of(id)),
            List.of(), semantics);
    }

    private static RuntimeProviderDescriptor provider() {
        return new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static RuntimeOperationDescriptor operation(String id, RuntimeSemantics.ThreadMode thread, RuntimeSemantics.Audit audit) {
        ContractRef<CapabilityId> capability = ContractRef.of(OWNER, CapabilityId.of(id));
        RuntimeSemantics semantics = new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            thread,
            capability,
            RuntimeSemantics.Cancellation.NONE,
            0,
            0,
            0,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.NONE,
            audit,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of("success"),
            Set.of("failure"),
            Set.of(),
            new RuntimeFailureContract(
                TypeExpr.named(TypeReference.of("builtin", "any")),
                Set.of("RUNTIME.HANDLER_FAILURE"),
                Set.of("failure"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
        return new RuntimeOperationDescriptor(capability, ContractRef.of(OWNER, OperationId.of(id)), List.of(), semantics);
    }
}
