package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeBindingRegistryTest {
    @Test
    void collisionIsRejectedWithoutChangingTheActiveSnapshot() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<CapabilityId> capability = capability("capability");
        ContractRef<OperationId> operation = operationId("operation");
        ContractRef<ProviderId> firstProvider = providerRef("first");
        ContractRef<ProviderId> secondProvider = providerRef("second");
        RuntimeOperationDescriptor descriptor = operation(capability, operation, "first-branch");

        RuntimeRegistrySnapshot before = registry.activate(
            provider(firstProvider),
            List.of(RuntimeBinding.available(descriptor, firstProvider, "1.0.0", completedHandler())));
        RuntimeBinding collision = RuntimeBinding.available(
            new RuntimeOperationDescriptor(capability, operation, List.of(
                new RuntimeOperationDescriptor.Pin("output", RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
                descriptor.semantics()),
            secondProvider,
            "1.0.0",
            completedHandler());

        assertThrows(RuntimeBindingCollisionException.class, () -> registry.activate(provider(secondProvider), List.of(collision)));

        RuntimeRegistrySnapshot after = registry.snapshot();
        assertEquals(before.generation(), after.generation());
        assertEquals(before.bindingManifestHash(), after.bindingManifestHash());
        assertEquals(firstProvider, after.binding(new RuntimeBindingKey(capability, operation)).orElseThrow().provider());
        assertTrue(after.provider(secondProvider).isEmpty());
    }

    @Test
    void leaseKeepsProviderAliveUntilTheInFlightOperationCompletes() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = providerRef("provider");
        RuntimeOperationDescriptor operation = operation(capability("capability"), operationId("operation"), "failure");
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", ignored -> pending);
        registry.activate(provider(provider, 5_000, 5_000, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(binding));
        RuntimeBindingKey key = operation.key();
        RuntimeLeaseInput input = input(key, binding.descriptor().fingerprint());
        RuntimePlanLease lease = registry.acquire(input);

        CompletableFuture<RuntimeResult> execution = lease.execute(key, Map.of(), "mutation-1").toCompletableFuture();
        lease.close();
        assertFalse(lease.isReleased());

        CompletableFuture<RuntimeUnloadResult> unload = CompletableFuture.supplyAsync(() -> registry.unload(provider));
        waitFor(() -> registry.snapshot().provider(provider).map(value -> value.state() == RuntimeProviderState.DRAINING).orElse(false));
        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.acquire(input));

        pending.complete(RuntimeResult.success(value("string", "done")));
        assertEquals(RuntimeResult.Status.SUCCESS, execution.get(5, TimeUnit.SECONDS).status());
        RuntimeUnloadResult result = unload.get(5, TimeUnit.SECONDS);
        assertEquals(RuntimeUnloadResult.Status.REMOVED, result.status());
        assertTrue(registry.snapshot().provider(provider).isEmpty());
    }

    @Test
    void tryUnloadReturnsBlockedWithoutWaitingForLease() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = providerRef("try-unload");
        RuntimeOperationDescriptor operation = operation(capability("try-unload-capability"), operationId("try-unload-operation"), "failure");
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", completedHandler());
        registry.activate(provider(provider, 5_000, 5_000, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(operation.key(), binding.executionFingerprint()));

        RuntimeUnloadResult blocked = registry.tryUnload(provider);

        assertEquals(RuntimeUnloadResult.Status.BLOCKED, blocked.status());
        assertEquals(RuntimeProviderState.DRAINING, registry.snapshot().provider(provider).orElseThrow().state());
        assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.acquire(input(operation.key(), binding.executionFingerprint())));

        lease.close();
        assertEquals(RuntimeUnloadResult.Status.REMOVED, registry.tryUnload(provider).status());
    }

    @Test
    void blockedBindingTryUnloadCanBeRetriedAfterLeaseCloses() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = providerRef("try-unload-binding");
        RuntimeOperationDescriptor operation = operation(
            capability("try-unload-binding-capability"), operationId("try-unload-binding-operation"), "failure");
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", completedHandler());
        registry.activate(provider(provider, 5_000, 5_000, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(operation.key(), binding.executionFingerprint()));

        RuntimeUnloadResult blocked = registry.tryUnload(operation.key());

        assertEquals(RuntimeUnloadResult.Status.BLOCKED, blocked.status());
        assertEquals(RuntimeProviderState.ACTIVE, registry.snapshot().provider(provider).orElseThrow().state());
        lease.close();

        assertEquals(RuntimeUnloadResult.Status.REMOVED, registry.tryUnload(operation.key()).status());
        assertTrue(registry.snapshot().binding(operation.key()).isEmpty());
    }

    @Test
    void receiptClaimCanReenterTheLeaseWithoutHoldingItsLock() throws Exception {
        RuntimeReceiptStore delegate = RuntimeReceiptStore.inMemory(true);
        AtomicReference<RuntimePlanLease> leaseReference = new AtomicReference<>();
        RuntimeReceiptStore store = new RuntimeReceiptStore() {
            @Override
            public Claim claim(Key key, ContentHash inputHash) {
                try {
                    CompletableFuture.runAsync(() -> leaseReference.get().auditEvents()).get(1, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                return delegate.claim(key, inputHash);
            }

            @Override
            public boolean durable() {
                return delegate.durable();
            }

            @Override
            public void releaseProvider(ContractRef<ProviderId> provider) {
                delegate.releaseProvider(provider);
            }
        };
        RuntimeSecurityBoundary security = new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability) {
                return true;
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return true;
            }
        };
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(
            RuntimeTestSupport.enforcingExecutionBoundary(), security, store);
        ContractRef<ProviderId> provider = providerRef("reentrant-receipt");
        RuntimeOperationDescriptor operation = operation(
            capability("reentrant-receipt-capability"), operationId("reentrant-receipt-operation"), "failure");
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", completedHandler());
        registry.activate(provider(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(operation.key(), binding.executionFingerprint()));
        leaseReference.set(lease);

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "reentrant-receipt")
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.SUCCESS, result.status());
        lease.close();
    }

    @Test
    void shutdownCallbackCanReenterAfterLifecycleLockRelease() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = providerRef("reentrant-shutdown");
        RuntimeOperationDescriptor operation = operation(capability("reentrant-capability"), operationId("reentrant-operation"), "failure");
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", completedHandler());
        RuntimeLeaseInput input = input(operation.key(), binding.executionFingerprint());
        AtomicBoolean reentered = new AtomicBoolean();
        RuntimeProviderLifecycle lifecycle = new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor ignored, List<RuntimeBindingDescriptor> bindings) {
                return true;
            }

            @Override
            public void compensate() {
            }

            @Override
            public void shutdown() {
                try {
                    CompletableFuture.runAsync(() -> {
                        try {
                            registry.acquire(input);
                        } catch (RuntimeCapabilityUnavailableException expected) {
                            reentered.set(true);
                        }
                    }).get(1, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };

        registry.activate(provider(provider), List.of(binding), lifecycle);

        assertEquals(RuntimeUnloadResult.Status.REMOVED, registry.unload(provider).status());
        assertTrue(reentered.get());
    }

    @Test
    void unavailableBindingCannotBeLeased() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = providerRef("provider");
        RuntimeOperationDescriptor operation = operation(capability("capability"), operationId("operation"), "failure");
        RuntimeBinding binding = RuntimeBinding.unavailable(operation, provider, "1.0.0");
        registry.activate(provider(provider), List.of(binding));

        assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.acquire(input(operation.key(), binding.descriptor().fingerprint())));
        assertNotNull(registry.resolve(operation.key()));
        assertFalse(registry.resolve(operation.key()).available());
    }

    @Test
    void invocationCarriesImmutableTypedPinValues() {
        RuntimeBindingKey key = new RuntimeBindingKey(capability("capability"), operationId("operation"));
        PinId pin = new PinId("input");
        TypedValue value = value("string", "input");
        RuntimeInvocation invocation = new RuntimeInvocation(
            key,
            Map.of(pin, value),
            "mutation-1",
            new RuntimeCancellationToken());

        assertEquals(value, invocation.inputs().get(pin));
        assertThrows(UnsupportedOperationException.class, () -> invocation.inputs().put(pin, value));
    }

    @Test
    void handlerFailureUsesSharedDiagnostic() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = providerRef("provider");
        RuntimeOperationDescriptor operation = operation(capability("capability"), operationId("operation"), "failure");
        RuntimeBinding binding = RuntimeBinding.available(
            operation,
            provider,
            "1.0.0",
            ignored -> {
                throw new IllegalStateException("handler failure");
            });
        registry.activate(provider(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(operation.key(), binding.descriptor().fingerprint()));

        RuntimeResult result = lease.execute(operation.key(), Map.of(), "mutation-1").toCompletableFuture().get(5, TimeUnit.SECONDS);
        lease.close();

        assertEquals(RuntimeResult.Status.FAILURE, result.status());
        assertEquals("RUNTIME.HANDLER_FAILURE", result.failure().diagnostic().code());
        assertEquals("execution", result.failure().diagnostic().stage());
        assertFalse(result.failure().retryable());
    }

    @Test
    void manifestHashDoesNotDependOnContributionOrder() {
        ContractRef<ProviderId> firstProvider = providerRef("first");
        ContractRef<ProviderId> secondProvider = providerRef("second");
        RuntimeOperationDescriptor firstOperation = operation(capability("capability-a"), operationId("operation-a"), "failure");
        RuntimeOperationDescriptor secondOperation = operation(capability("capability-b"), operationId("operation-b"), "failure");
        RuntimeBinding first = RuntimeBinding.available(firstOperation, firstProvider, "1.0.0", completedHandler());
        RuntimeBinding second = RuntimeBinding.available(secondOperation, secondProvider, "1.0.0", completedHandler());

        RuntimeBindingRegistry left = RuntimeTestSupport.registry();
        left.activate(provider(firstProvider), List.of(first));
        left.activate(provider(secondProvider), List.of(second));
        RuntimeBindingRegistry right = RuntimeTestSupport.registry();
        right.activate(provider(secondProvider), List.of(second));
        right.activate(provider(firstProvider), List.of(first));

        assertEquals(left.snapshot().bindingManifestHash(), right.snapshot().bindingManifestHash());
        assertEquals(left.snapshot().manifest().canonicalForm(), right.snapshot().manifest().canonicalForm());
    }

    @Test
    void runtimeHashesUseExplicitDomainsAndContentHash() {
        ContractRef<ProviderId> provider = providerRef("provider");
        RuntimeOperationDescriptor operation = operation(capability("capability"), operationId("operation"), "failure");
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", completedHandler());
        RuntimeBindingDescriptor descriptor = binding.descriptor();
        RuntimeBindingManifest manifest = RuntimeBindingManifest.create(
            List.of(provider(provider)),
            List.of(descriptor),
            Map.of(descriptor.key(), descriptor.fingerprint()),
            List.of());

        assertEquals(ContentHash.of(CanonicalJson.sha256("runtime-binding", descriptor.canonicalValueWithoutFingerprint())), descriptor.fingerprint());
        assertEquals(ContentHash.of(CanonicalJson.sha256("runtime-manifest", CanonicalJson.parse(manifest.canonicalForm()))), manifest.bindingManifestHash());
        assertNotEquals(descriptor.fingerprint(), ContentHash.of(CanonicalJson.sha256("runtime-manifest", descriptor.canonicalValueWithoutFingerprint())));
        assertTrue(manifest.canonicalForm().startsWith("{"));
    }

    private static RuntimeOperationDescriptor operation(
        ContractRef<CapabilityId> capability,
        ContractRef<OperationId> operation,
        String failureBranch
    ) {
        return new RuntimeOperationDescriptor(
            capability,
            operation,
            List.of(new RuntimeOperationDescriptor.Pin("output", RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
            semantics(failureBranch));
    }

    private static RuntimeSemantics semantics(String failureBranch) {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.STATE_MUTATING,
            RuntimeSemantics.ThreadMode.MAIN,
            capability("authorization"),
            RuntimeSemantics.Cancellation.COOPERATIVE,
            1_000,
            100,
            5_000,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.MUTATION_ID,
            RuntimeSemantics.Audit.METADATA,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of("success"),
            Set.of(failureBranch),
            Set.of("cancelled"),
            new RuntimeFailureContract(
                type("failure"),
                Set.of("RUNTIME.FAILURE"),
                Set.of(failureBranch),
                RuntimeFailureContract.CommitBoundary.ATOMIC),
            Set.of(resourceType("resource-read")),
            Set.of(resourceType("resource-write")));
    }

    private static RuntimeProviderDescriptor provider(ContractRef<ProviderId> provider) {
        return provider(provider, 100, 500, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static RuntimeProviderDescriptor provider(
        ContractRef<ProviderId> provider,
        long drain,
        long hard,
        RuntimeSemantics.UnloadPolicy policy
    ) {
        return new RuntimeProviderDescriptor(provider, "1.0.0", drain, hard, policy);
    }

    private static RuntimeLeaseInput input(RuntimeBindingKey key, ContentHash fingerprint) {
        return RuntimeTestSupport.input(new RuntimeLeaseInput.BindingRequirement(
            key, fingerprint, List.of(), List.of(PinId.of("output"))));
    }

    private static RuntimeOperationHandler completedHandler() {
        return ignored -> CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok")));
    }

    private static ContractRef<CapabilityId> capability(String localId) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new CapabilityId(localId));
    }

    private static ContractRef<OperationId> operationId(String localId) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new OperationId(localId));
    }

    private static ContractRef<ProviderId> providerRef(String localId) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new ProviderId(localId));
    }

    private static ContractRef<ResourceTypeId> resourceType(String localId) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new ResourceTypeId(localId));
    }

    private static TypeExpr type(String localId) {
        return TypeExpr.named(TypeReference.of("restudio.resync", localId));
    }

    private static TypedValue value(String typeLocalId, String value) {
        return TypedValue.value(type(typeLocalId), value);
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
