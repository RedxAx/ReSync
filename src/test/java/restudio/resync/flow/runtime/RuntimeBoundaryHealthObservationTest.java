package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostics.StructuredFlowDiagnosticReporter;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeBoundaryHealthObservationTest {
    private static final RuntimeAuthority AUTHORITY = new RuntimeAuthority("resync:health-observation");
    private static final RuntimePrincipal PRINCIPAL = new RuntimePrincipalAuthority(AUTHORITY).issueSystem("health");
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OwnerId.of("resync"), ProviderId.of("health"));
    private static final RuntimeBindingKey BINDING = new RuntimeBindingKey(
        ContractRef.of(OwnerId.of("resync"), CapabilityId.of("health")),
        ContractRef.of(OwnerId.of("resync"), OperationId.of("observe")));
    private static final ContentHash PLAN = hash("plan");
    private static final ContentHash EXECUTION = hash("execution");
    private static final ContentHash INPUT = hash("input");

    @Test
    void preparationOnlySamplesStorageAndCompletionChecksCurrentRuntime(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        fixture.securityReads().set(0);
        fixture.executionReads().set(0);

        RuntimeBoundaryHealth.Prepared prepared = CompletableFuture.supplyAsync(fixture.health()::prepare)
            .get(5, TimeUnit.SECONDS);

        assertEquals(0, fixture.securityReads().get());
        assertEquals(0, fixture.executionReads().get());
        Map<String, Object> healthy = fixture.health().snapshot(prepared);
        assertTrue(flag(healthy, "audit", "available"));
        assertTrue(flag(healthy, "receipts", "available"));
        assertEquals(1, fixture.securityReads().get());
        assertEquals(1, fixture.executionReads().get());
        fixture.enabled().set(false);
        assertFalse(flag(fixture.health().snapshot(prepared), "execution", "available"));
    }

    @Test
    void completionDoesNotAcquireReceiptOrReporterMonitors(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        RuntimeBoundaryHealth.Prepared prepared = fixture.health().prepare();
        Field field = DurableRuntimeReceiptStore.class.getDeclaredField("monitor");
        field.setAccessible(true);
        Object receiptMonitor = field.get(fixture.receipts());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            synchronized (receiptMonitor) {
                synchronized (fixture.reporter()) {
                    held.countDown();
                    await(release);
                }
            }
        });
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            Map<String, Object> result = CompletableFuture.supplyAsync(() -> fixture.health().snapshot(prepared))
                .get(1, TimeUnit.SECONDS);
            assertTrue(flag(result, "audit", "available"));
            assertTrue(flag(result, "receipts", "available"));
        } finally {
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reporterQuiesceResumeAndRebindRejectOldEvidence(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        RuntimeBoundaryHealth.Prepared prepared = fixture.health().prepare();
        fixture.reporter().quiesce();
        fixture.reporter().resume();

        Map<String, Object> stale = fixture.health().snapshot(prepared);
        assertFalse(flag(stale, "audit", "available"));
        assertFalse(flag(stale, "audit", "current"));
        assertTrue(flag(stale, "receipts", "available"));
        assertFalse(flag(fixture.health().snapshot(fixture.health().prepare()), "audit", "available"));
        fixture.reporter().healthCheck();
        assertTrue(flag(fixture.health().snapshot(fixture.health().prepare()), "audit", "available"));

        prepared = fixture.health().prepare();
        fixture.reporter().quiesce();
        fixture.reporter().rebind(directory.resolve("rebound-diagnostics"));
        assertFalse(flag(fixture.health().snapshot(fixture.health().prepare()), "audit", "available"));
        fixture.reporter().resume();
        assertFalse(flag(fixture.health().snapshot(prepared), "audit", "available"));
        assertFalse(flag(fixture.health().snapshot(fixture.health().prepare()), "audit", "available"));
        fixture.reporter().healthCheck();
        assertTrue(flag(fixture.health().snapshot(fixture.health().prepare()), "audit", "available"));
    }

    @Test
    void receiptDrainIsUnstableAndResumeDoesNotReviveOldEvidence(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        RuntimeBoundaryHealth.Prepared prepared = fixture.health().prepare();
        RuntimeReceiptStore.InvocationLease lease = fixture.receipts().acquireInvocationLease();
        CompletableFuture<Void> drain = CompletableFuture.runAsync(() -> {
            try {
                fixture.receipts().quiesce();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fixture.receipts().observation().stable() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertFalse(fixture.receipts().observation().stable());
            assertFalse(flag(fixture.health().snapshot(prepared), "receipts", "available"));
            RuntimeBoundaryHealth.Prepared duringDrain = fixture.health().prepare();
            assertFalse(duringDrain.receiptsStable());
        } finally {
            lease.close();
            drain.get(5, TimeUnit.SECONDS);
        }
        fixture.receipts().resume();
        assertFalse(flag(fixture.health().snapshot(prepared), "receipts", "available"));
        assertTrue(flag(fixture.health().snapshot(fixture.health().prepare()), "receipts", "available"));
    }

    @Test
    void receiptFaultAndReporterReadFailureCannotAdvertiseOldHealth(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        RuntimeBoundaryHealth.Prepared prepared = fixture.health().prepare();
        Files.delete(fixture.receipts().root());
        assertThrows(IOException.class, fixture.receipts()::healthCheck);
        assertFalse(flag(fixture.health().snapshot(prepared), "receipts", "available"));
        assertFalse(flag(fixture.health().snapshot(fixture.health().prepare()), "receipts", "available"));

        Fixture auditFixture = fixture(directory.resolve("audit-failure"));
        RuntimeBoundaryHealth.Prepared auditPrepared = auditFixture.health().prepare();
        Path reports = auditFixture.reporter().store().directory();
        Files.move(reports, directory.resolve("previous-diagnostics"));
        Files.writeString(reports, "unavailable");
        assertThrows(RuntimeException.class, auditFixture.reporter()::healthCheck);
        assertFalse(flag(auditFixture.health().snapshot(auditPrepared), "audit", "available"));
        assertFalse(flag(auditFixture.health().snapshot(auditFixture.health().prepare()), "audit", "available"));
    }

    @Test
    void pendingAuditChangesRejectPreparedEvidence(@TempDir Path directory) {
        Fixture fixture = fixture(directory);
        RuntimeBoundaryHealth.Prepared beforePending = fixture.health().prepare();
        RuntimeReceiptStore.Key key = key("pending-audit");
        RuntimeExecutionProvenance provenance = provenance("pending-audit");
        RuntimeLeaseInput.AuditEvent event = event("pending-audit", provenance);

        fixture.receipts().reserve(key, INPUT, provenance, event);
        fixture.receipts().complete(key, RuntimeResult.success(), provenance, event);

        Map<String, Object> stale = fixture.health().snapshot(beforePending);
        assertFalse(flag(stale, "receipts", "available"));
        assertFalse(flag(stale, "receipts", "current"));
        assertFalse(flag(stale, "audit", "available"));
        assertTrue(flag(stale, "audit", "degraded"));

        RuntimeBoundaryHealth.Prepared pending = fixture.health().prepare();
        Map<String, Object> pendingSnapshot = fixture.health().snapshot(pending);
        assertEquals(1, number(pendingSnapshot, "receipts", "pendingAudits"));
        assertTrue(flag(pendingSnapshot, "receipts", "current"));
        assertTrue(flag(pendingSnapshot, "audit", "degraded"));

        fixture.receipts().markAuditRecorded(key, event);

        assertFalse(flag(fixture.health().snapshot(pending), "receipts", "available"));
        Map<String, Object> recorded = fixture.health().snapshot(fixture.health().prepare());
        assertEquals(0, number(recorded, "receipts", "pendingAudits"));
        assertFalse(flag(recorded, "audit", "degraded"));
    }

    @Test
    void receiptRebindAndResumeRejectOldEvidence(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("source");
        Path target = directory.resolve("target");
        Fixture fixture = fixture(source);
        new DurableRuntimeReceiptStore(target);
        RuntimeBoundaryHealth.Prepared beforeRebind = fixture.health().prepare();

        fixture.receipts().quiesce();
        fixture.receipts().rebind(target);

        Map<String, Object> rebound = fixture.health().snapshot(beforeRebind);
        assertFalse(flag(rebound, "receipts", "available"));
        assertFalse(flag(rebound, "receipts", "current"));
        assertFalse(flag(rebound, "audit", "available"));
        assertTrue(flag(rebound, "audit", "degraded"));

        RuntimeBoundaryHealth.Prepared quiesced = fixture.health().prepare();
        Map<String, Object> quiescedSnapshot = fixture.health().snapshot(quiesced);
        assertFalse(flag(quiescedSnapshot, "receipts", "available"));
        assertTrue(flag(quiescedSnapshot, "receipts", "quiesced"));
        assertTrue(flag(quiescedSnapshot, "receipts", "current"));

        fixture.receipts().resume();

        assertFalse(flag(fixture.health().snapshot(beforeRebind), "receipts", "available"));
        assertFalse(flag(fixture.health().snapshot(quiesced), "receipts", "available"));
        assertTrue(flag(fixture.health().snapshot(fixture.health().prepare()), "receipts", "available"));
    }

    @Test
    void registryReplacementAndDifferentOwnerRejectPreparedEvidence(@TempDir Path directory) {
        Fixture fixture = fixture(directory);
        RuntimeBoundaryHealth.Prepared prepared = fixture.health().prepare();
        OwnerId owner = OwnerId.of("resync");
        ContractRef<CapabilityId> capability = ContractRef.of(owner, CapabilityId.of("health-test"));
        ContractRef<ProviderId> provider = ContractRef.of(owner, ProviderId.of("health-test"));
        RuntimeSemantics semantics = new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, capability,
            RuntimeSemantics.Cancellation.NONE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.NONE, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of("success"), Set.of("failure"), Set.of(),
            new RuntimeFailureContract(TypeExpr.named(TypeReference.of("builtin", "any")),
                Set.of("RUNTIME.HANDLER_FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(), Set.of());
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(capability,
            ContractRef.of(owner, OperationId.of("health-test")), List.of(), semantics);
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0",
            invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        fixture.registry().activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0,
            RuntimeSemantics.UnloadPolicy.DRAIN), List.of(binding));

        Map<String, Object> stale = fixture.health().snapshot(prepared);
        assertFalse(flag(stale, "receipts", "available"));
        assertFalse(flag(stale, "audit", "available"));
        assertTrue(flag(stale, "security", "available"));
        assertTrue(flag(fixture.health().snapshot(fixture.health().prepare()), "audit", "available"));
        Fixture other = fixture(directory.resolve("other"));
        assertFalse(flag(other.health().snapshot(fixture.health().prepare()), "receipts", "available"));
    }

    private static Fixture fixture(Path directory) {
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(directory);
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(directory.resolve("diagnostics"));
        StructuredRuntimeAuditBoundary audit = new StructuredRuntimeAuditBoundary(reporter, ServerId.deterministic("health-observation"));
        AtomicReference<RuntimeBindingRegistry> reference = new AtomicReference<>();
        AtomicInteger securityReads = new AtomicInteger();
        AtomicInteger executionReads = new AtomicInteger();
        AtomicBoolean enabled = new AtomicBoolean(true);
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(new RuntimeAuthority("resync:health"), () -> {
            securityReads.incrementAndGet();
            return reference.get().snapshot();
        });
        FlowRuntimeExecutionBoundary execution = new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run,
            () -> true, () -> {
                executionReads.incrementAndGet();
                return enabled.get();
            });
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(security, audit, execution, receipts);
        reference.set(registry);
        RuntimeBoundaryHealth health = new RuntimeBoundaryHealth(security, audit, execution, registry, receipts);
        return new Fixture(health, receipts, reporter, registry, securityReads, executionReads, enabled);
    }

    private static boolean flag(Map<String, Object> snapshot, String boundary, String key) {
        return Boolean.TRUE.equals(((Map<?, ?>) snapshot.get(boundary)).get(key));
    }

    private static int number(Map<String, Object> snapshot, String boundary, String key) {
        return ((Number) ((Map<?, ?>) snapshot.get(boundary)).get(key)).intValue();
    }

    private static RuntimeReceiptStore.Key key(String idempotencyKey) {
        return new RuntimeReceiptStore.Key(PROVIDER, PLAN, BINDING, EXECUTION, AUTHORITY.identity(), idempotencyKey,
            PRINCIPAL.canonical());
    }

    private static RuntimeExecutionProvenance provenance(String idempotencyKey) {
        return new RuntimeExecutionProvenance(
            AUTHORITY.identity(), PRINCIPAL, BINDING, PROVIDER, "1.0.0", 1, hash("manifest"),
            1, hash("catalog"), PLAN, EXECUTION, idempotencyKey,
            CorrelationId.deterministic("health-observation:" + idempotencyKey), null, INPUT,
            hash("context"), RuntimeExecutionContext.NO_DEADLINE,
            UUID.nameUUIDFromBytes(("health-observation:" + idempotencyKey).getBytes(StandardCharsets.UTF_8)));
    }

    private static RuntimeLeaseInput.AuditEvent event(String idempotencyKey, RuntimeExecutionProvenance provenance) {
        return new RuntimeLeaseInput.AuditEvent(
            UUID.nameUUIDFromBytes(("health-audit:" + idempotencyKey).getBytes(StandardCharsets.UTF_8)),
            AUTHORITY.identity(), BINDING.canonical(), idempotencyKey,
            RuntimeResult.Status.SUCCESS, false, "outcome", provenance);
    }

    private static ContentHash hash(String value) {
        return ContentHash.of(CanonicalJson.sha256("runtime-boundary-health-observation", Map.of("value", value)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Observation Test Latch Timed Out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(RuntimeBoundaryHealth health, DurableRuntimeReceiptStore receipts,
                           StructuredFlowDiagnosticReporter reporter, RuntimeBindingRegistry registry,
                           AtomicInteger securityReads, AtomicInteger executionReads, AtomicBoolean enabled) {
    }
}
