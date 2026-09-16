package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.contract.diagnostic.RuntimePostCommitDiagnosticCode;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeBindingReplacementTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");

    @Test
    void failedProviderReadinessDoesNotChangeActiveRuntime() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        RuntimeRegistrySnapshot before = registry.snapshot();
        ContractRef<ProviderId> provider = provider("failed");
        RuntimeProviderLifecycle lifecycle = new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor ignored, List<RuntimeBindingDescriptor> bindings) {
                return false;
            }

            @Override
            public void shutdown() {
            }

            @Override
            public void compensate() {
            }
        };

        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.prepareReplacement(
            List.of(new RuntimeBindingRegistry.RuntimeProviderContribution(descriptor(provider), lifecycleBinding(provider), lifecycle)),
            Set.of()));

        assertSame(before, registry.snapshot());
        assertTrue(registry.snapshot().providers().isEmpty());
    }

    @Test
    void failedReplacementCompensatesEveryStagedProviderWhenOneCompensationFails() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> firstProvider = provider("rollback-first");
        ContractRef<ProviderId> secondProvider = provider("rollback-second");
        ContractRef<ProviderId> failedProvider = provider("rollback-failed");
        AtomicBoolean firstCompensated = new AtomicBoolean();
        AtomicBoolean secondCompensated = new AtomicBoolean();
        RuntimeProviderLifecycle firstLifecycle = lifecycle(true, firstCompensated, true);
        RuntimeProviderLifecycle secondLifecycle = lifecycle(true, secondCompensated, false);
        RuntimeProviderLifecycle failedLifecycle = lifecycle(false, new AtomicBoolean(), false);

        RuntimeCapabilityUnavailableException failure = assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.prepareReplacement(
                List.of(
                    new RuntimeBindingRegistry.RuntimeProviderContribution(descriptor(firstProvider),
                        List.of(binding(firstProvider, "rollback-first-operation")), firstLifecycle),
                    new RuntimeBindingRegistry.RuntimeProviderContribution(descriptor(secondProvider),
                        List.of(binding(secondProvider, "rollback-second-operation")), secondLifecycle),
                    new RuntimeBindingRegistry.RuntimeProviderContribution(descriptor(failedProvider),
                        List.of(binding(failedProvider, "rollback-failed-operation")), failedLifecycle)),
                Set.of()));

        assertTrue(failure.getMessage().contains(failedProvider.canonicalText()));
        assertTrue(firstCompensated.get());
        assertTrue(secondCompensated.get());
        assertTrue(registry.snapshot().providers().isEmpty());
    }

    @Test
    void blockedRetirementLeavesProviderActiveAndCanBeRetriedExplicitly() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("retiring");
        RuntimeBinding binding = binding(provider, "retiring-operation");
        registry.activate(descriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(new RuntimeLeaseInput.Default(
            List.of(new RuntimeLeaseInput.BindingRequirement(binding.key(), binding.executionFingerprint(), List.of(), List.of(PinId.of("output")))),
            restudio.resync.flow.identity.ContentHash.of("a".repeat(64)),
            new RuntimeAuthority("test-authority")));

        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), Set.of(provider));
        RuntimeRegistrySnapshot before = registry.snapshot();
        RuntimeRegistrySnapshot preview = replacement.preview();
        RuntimeBindingRegistry.RuntimeReplacementResult blocked = replacement.commit();

        assertEquals(RuntimeBindingRegistry.ReplacementStatus.BLOCKED, blocked.status());
        assertEquals(1, blocked.blockedLeases());
        assertSame(before, registry.snapshot());
        assertEquals(RuntimeProviderState.ACTIVE, registry.snapshot().provider(provider).orElseThrow().state());

        lease.close();
        RuntimeBindingRegistry.RuntimeReplacementResult committed = replacement.commit();
        assertEquals(RuntimeBindingRegistry.ReplacementStatus.COMMITTED, committed.status());
        assertSame(preview, committed.snapshot());
        assertSame(preview, registry.snapshot());
        assertTrue(registry.snapshot().provider(provider).isEmpty());
        replacement.close();
    }

    @Test
    void convenienceActivationCompensatesStagingWhenRetirementIsBlocked() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> oldProvider = provider("convenience-old");
        ContractRef<ProviderId> newProvider = provider("convenience-new");
        RuntimeBinding oldBinding = binding(oldProvider, "convenience-old-operation");
        RuntimeBinding newBinding = binding(newProvider, "convenience-new-operation");
        registry.activate(descriptor(oldProvider), List.of(oldBinding));
        RuntimeRegistrySnapshot baseline = registry.snapshot();
        RuntimePlanLease lease = registry.acquire(new RuntimeLeaseInput.Default(
            List.of(new RuntimeLeaseInput.BindingRequirement(oldBinding.key(), oldBinding.executionFingerprint(), List.of(), List.of(PinId.of("output")))),
            restudio.resync.flow.identity.ContentHash.of("b".repeat(64)),
            new RuntimeAuthority("test-authority")));
        AtomicBoolean compensated = new AtomicBoolean();
        RuntimeProviderLifecycle lifecycle = new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor ignored, List<RuntimeBindingDescriptor> bindings) {
                return true;
            }

            @Override
            public void compensate() {
                compensated.set(true);
            }

            @Override
            public void shutdown() {
            }
        };
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(
            List.of(new RuntimeBindingRegistry.RuntimeProviderContribution(descriptor(newProvider), List.of(newBinding), lifecycle)),
            Set.of(oldProvider));
        CatalogSnapshot activeCatalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(baseline))
            .compile(List.of(), 1)
            .snapshot()
            .orElseThrow();
        CatalogSnapshot candidate = new CatalogCompiler(new CatalogVersion(1, 0),
            CatalogBindingProof.snapshot(replacement.preview()))
            .compile(List.of(), 2)
            .snapshot()
            .orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(activeCatalog, baseline);

        CatalogRuntimeActivation.ActivationResult result = activation.activate(candidate, replacement, null);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.BLOCKED, result.status());
        assertTrue(compensated.get());
        assertSame(baseline, registry.snapshot());
        lease.close();
    }

    @Test
    void additionsAndRetirementsSwapAsOneRuntimeSnapshot() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> oldProvider = provider("old");
        ContractRef<ProviderId> newProvider = provider("new");
        RuntimeBinding oldBinding = binding(oldProvider, "old-operation");
        RuntimeBinding newBinding = binding(newProvider, "new-operation");
        registry.activate(descriptor(oldProvider), List.of(oldBinding));
        RuntimeRegistrySnapshot before = registry.snapshot();

        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(
            List.of(new RuntimeBindingRegistry.RuntimeProviderContribution(descriptor(newProvider), List.of(newBinding))),
            Set.of(oldProvider));
        RuntimeRegistrySnapshot preview = replacement.preview();
        assertSame(preview, replacement.preview());
        assertTrue(preview.provider(newProvider).isPresent());
        assertTrue(preview.provider(oldProvider).isEmpty());
        assertSame(before, registry.snapshot());

        RuntimeBindingRegistry.RuntimeReplacementResult result = replacement.commit();
        assertEquals(RuntimeBindingRegistry.ReplacementStatus.COMMITTED, result.status());
        assertSame(preview, result.snapshot());
        assertSame(preview, registry.snapshot());
        assertEquals(before.generation() + 1, registry.snapshot().generation());
        assertTrue(registry.snapshot().provider(newProvider).isPresent());
        assertTrue(registry.snapshot().provider(oldProvider).isEmpty());
        assertTrue(registry.snapshot().binding(newBinding.key()).isPresent());
        assertTrue(registry.snapshot().binding(oldBinding.key()).isEmpty());
    }

    @Test
    void postCommitCleanupFailuresReturnCommittedDiagnosticsAndRetryOnlyPendingActions() {
        AtomicInteger receiptReleaseCalls = new AtomicInteger();
        AtomicInteger shutdownCalls = new AtomicInteger();
        RuntimeReceiptStore receiptStore = new RuntimeReceiptStore() {
            private final RuntimeReceiptStore delegate = RuntimeReceiptStore.inMemory(false);

            @Override
            public Claim claim(RuntimeReceiptStore.Key key, restudio.resync.flow.identity.ContentHash inputHash) {
                return delegate.claim(key, inputHash);
            }

            @Override
            public boolean durable() {
                return delegate.durable();
            }

            @Override
            public void releaseProvider(ContractRef<ProviderId> provider) {
                if (receiptReleaseCalls.incrementAndGet() == 1) {
                    throw new IllegalStateException("receipt cleanup failed");
                }
                delegate.releaseProvider(provider);
            }
        };
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry(
            RuntimeTestSupport.enforcingExecutionBoundary(),
            RuntimeSecurityBoundary.denyAll(),
            receiptStore);
        ContractRef<ProviderId> provider = provider("post-commit-failure");
        RuntimeBinding binding = binding(provider, "post-commit-operation");
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
                if (shutdownCalls.incrementAndGet() == 1) {
                    throw new IllegalStateException("shutdown cleanup failed");
                }
            }
        };
        registry.activate(descriptor(provider), List.of(binding), lifecycle);
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), Set.of(provider));

        RuntimeBindingRegistry.RuntimeReplacementResult first = replacement.commit();

        assertEquals(RuntimeBindingRegistry.ReplacementStatus.COMMITTED_WITH_DIAGNOSTICS, first.status());
        assertTrue(first.committed());
        assertTrue(first.healthDegraded());
        assertEquals(2, first.diagnostics().size());
        assertTrue(first.diagnostics().stream().allMatch(value -> value.code().equals(
            RuntimePostCommitDiagnosticCode.CLEANUP_FAILED.wireName())));
        assertTrue(registry.snapshot().provider(provider).isEmpty());

        RuntimeBindingRegistry.RuntimeReplacementResult retry = replacement.commit();

        assertEquals(RuntimeBindingRegistry.ReplacementStatus.COMMITTED, retry.status());
        assertTrue(retry.diagnostics().isEmpty());
        assertEquals(2, receiptReleaseCalls.get());
        assertEquals(2, shutdownCalls.get());

        RuntimeBindingRegistry.RuntimeReplacementResult stable = replacement.commit();

        assertEquals(RuntimeBindingRegistry.ReplacementStatus.COMMITTED, stable.status());
        assertEquals(2, receiptReleaseCalls.get());
        assertEquals(2, shutdownCalls.get());
    }

    @Test
    void postCommitDiagnosticsAdaptThroughTheSharedCatalogAndPreserveUnknownFields() {
        ContractRef<ProviderId> provider = new ContractRef<>(
            new OwnerId("extension.example"),
            new ProviderId("runtime"),
            Map.of("providerFuture", Map.of("preserved", true)));
        RuntimeBindingRegistry.RuntimePostCommitDiagnostic local = new RuntimeBindingRegistry.RuntimePostCommitDiagnostic(
            provider,
            "provider-shutdown",
            RuntimePostCommitDiagnosticCode.CLEANUP_FAILED.wireName(),
            "java.lang.IllegalStateException: shutdown cleanup failed",
            2,
            Map.of("diagnosticFuture", Map.of("preserved", true)));

        var shared = local.toSharedDiagnostic();

        assertEquals(local.code(), shared.code());
        assertEquals("error", shared.severity().wireName());
        assertEquals("environment", shared.phase().wireName());
        assertEquals("post-commit-cleanup", shared.stage());
        assertTrue(shared.durable());
        assertEquals(DiagnosticCodeCatalog.Retryability.SAFE, shared.retryability());
        assertEquals(provider.canonicalValue(), shared.arguments().get("provider"));
        assertEquals(local.operation(), shared.arguments().get("operation"));
        assertEquals(local.detail(), shared.arguments().get("detail"));
        assertEquals(local.attempts(), shared.arguments().get("attempts"));
        assertEquals(Map.of("preserved", true), ((Map<?, ?>) shared.unknown().get("diagnosticFuture")));

        RuntimeBindingRegistry.RuntimePostCommitDiagnostic restored =
            RuntimeBindingRegistry.RuntimePostCommitDiagnostic.fromSharedDiagnostic(shared);
        assertEquals(local.provider(), restored.provider());
        assertEquals(local.operation(), restored.operation());
        assertEquals(local.code(), restored.code());
        assertEquals(local.detail(), restored.detail());
        assertEquals(local.attempts(), restored.attempts());
        assertEquals(local.unknown(), restored.unknown());
    }

    @Test
    void observerPostCommitDiagnosticUsesItsOwnRetryContract() {
        RuntimeBindingRegistry.RuntimePostCommitDiagnostic diagnostic = new RuntimeBindingRegistry.RuntimePostCommitDiagnostic(
            provider("observer"),
            "committed-observer",
            RuntimePostCommitDiagnosticCode.OBSERVER_FAILED.wireName(),
            "java.lang.IllegalStateException: observer failed",
            1);

        assertEquals("post-commit-observer", diagnostic.toSharedDiagnostic().stage());
        assertEquals(DiagnosticCodeCatalog.Retryability.AFTER_RECONCILIATION,
            diagnostic.toSharedDiagnostic().retryability());
    }

    @Test
    void committedObserverFailureIsRetryableWithoutInvokingTheObserverAgain() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), Set.of());
        AtomicInteger observerCalls = new AtomicInteger();

        RuntimeBindingRegistry.RuntimeReplacementResult first = replacement.commit(ignored -> {
            observerCalls.incrementAndGet();
            throw new IllegalStateException("activation callback failed");
        });

        assertEquals(RuntimeBindingRegistry.ReplacementStatus.COMMITTED_WITH_DIAGNOSTICS, first.status());
        assertTrue(first.committed());
        assertEquals(1, observerCalls.get());

        RuntimeBindingRegistry.RuntimeReplacementResult retry = replacement.commit();

        assertEquals(RuntimeBindingRegistry.ReplacementStatus.COMMITTED, retry.status());
        assertTrue(retry.diagnostics().isEmpty());
        assertEquals(1, observerCalls.get());
    }

    @Test
    void activationRecordSwapsBeforeRetiringProviderShutdown() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> oldProvider = provider("record-old");
        ContractRef<ProviderId> newProvider = provider("record-new");
        RuntimeBinding oldBinding = binding(oldProvider, "record-old-operation");
        RuntimeBinding newBinding = binding(newProvider, "record-new-operation");
        AtomicReference<CatalogRuntimeActivation> activationReference = new AtomicReference<>();
        AtomicBoolean observedNewRuntime = new AtomicBoolean();
        AtomicInteger shutdownCalls = new AtomicInteger();
        RuntimeProviderLifecycle oldLifecycle = new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor ignored, List<RuntimeBindingDescriptor> bindings) {
                return true;
            }

            @Override
            public void compensate() {
            }

            @Override
            public void shutdown() {
                observedNewRuntime.set(activationReference.get().active().runtime().provider(newProvider).isPresent());
                if (shutdownCalls.incrementAndGet() == 1) {
                    throw new IllegalStateException("shutdown cleanup failed");
                }
            }
        };
        registry.activate(descriptor(oldProvider), List.of(oldBinding), oldLifecycle);
        RuntimeRegistrySnapshot baseline = registry.snapshot();
        CatalogSnapshot activeCatalog = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(baseline))
            .compile(List.of(), 1)
            .snapshot()
            .orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(activeCatalog, baseline);
        activationReference.set(activation);
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(
            List.of(new RuntimeBindingRegistry.RuntimeProviderContribution(descriptor(newProvider), List.of(newBinding))),
            Set.of(oldProvider));
        CatalogSnapshot candidate = new CatalogCompiler(new CatalogVersion(1, 0),
            CatalogBindingProof.snapshot(replacement.preview()))
            .compile(List.of(), 2)
            .snapshot()
            .orElseThrow();

        CatalogRuntimeActivation.ActivationTransaction transaction = activation.stage(candidate, replacement, null);
        CatalogRuntimeActivation.ActivationResult result = transaction.commit();

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED_WITH_DIAGNOSTICS, result.status());
        assertTrue(result.committed());
        assertEquals(1, result.diagnostics().size());
        assertTrue(observedNewRuntime.get());
        assertSame(registry.snapshot(), result.record().runtime());

        CatalogRuntimeActivation.ActivationResult retry = transaction.commit();

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, retry.status());
        assertTrue(retry.diagnostics().isEmpty());
        assertEquals(2, shutdownCalls.get());
        assertSame(result.record(), activation.active());
    }

    @Test
    void externalRuntimeMutationMakesReplacementStaleWithoutRollback() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> oldProvider = provider("old-stale");
        ContractRef<ProviderId> newProvider = provider("new-stale");
        RuntimeBinding oldBinding = binding(oldProvider, "old-stale-operation");
        registry.activate(descriptor(oldProvider), List.of(oldBinding));
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(
            List.of(new RuntimeBindingRegistry.RuntimeProviderContribution(descriptor(newProvider),
                List.of(binding(newProvider, "new-stale-operation")))),
            Set.of(oldProvider));
        RuntimeRegistrySnapshot preview = replacement.preview();
        registry.activate(descriptor(provider("external")), List.of(binding(provider("external"), "external-operation")));
        RuntimeRegistrySnapshot external = registry.snapshot();

        RuntimeBindingRegistry.RuntimeReplacementResult result = replacement.commit();

        assertEquals(RuntimeBindingRegistry.ReplacementStatus.STALE, result.status());
        assertSame(external, result.snapshot());
        assertSame(external, registry.snapshot());
        assertTrue(preview.provider(newProvider).isPresent());
        assertTrue(registry.snapshot().provider(oldProvider).isPresent());
        assertTrue(registry.snapshot().provider(newProvider).isEmpty());
        replacement.close();
    }

    @Test
    void expectedRuntimeSnapshotRejectsLeaseAcquisitionAfterRegistryGenerationChanges() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("snapshot-bound");
        RuntimeBinding binding = binding(provider, "snapshot-bound-operation");
        registry.activate(descriptor(provider), List.of(binding));
        RuntimeRegistrySnapshot expected = registry.snapshot();
        registry.activate(descriptor(provider("external-snapshot")),
            List.of(binding(provider("external-snapshot"), "external-snapshot-operation")));

        RuntimeLeaseInput input = new RuntimeLeaseInput.Default(
            List.of(new RuntimeLeaseInput.BindingRequirement(binding.key(), binding.executionFingerprint(), List.of(), List.of(PinId.of("output")))),
            restudio.resync.flow.identity.ContentHash.of("b".repeat(64)),
            new RuntimeAuthority("test-authority"));

        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.acquire(input, expected));
    }

    private static RuntimeProviderDescriptor descriptor(ContractRef<ProviderId> provider) {
        return new RuntimeProviderDescriptor(provider, "1.0.0", 100, 500, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static List<RuntimeBinding> lifecycleBinding(ContractRef<ProviderId> provider) {
        return List.of(binding(provider, "failed-operation"));
    }

    private static RuntimeBinding binding(ContractRef<ProviderId> provider, String operationLocalId) {
        RuntimeOperationDescriptor descriptor = new RuntimeOperationDescriptor(
            ContractRef.of(OWNER, CapabilityId.of(operationLocalId + "-capability")),
            ContractRef.of(OWNER, OperationId.of(operationLocalId)),
            List.of(new RuntimeOperationDescriptor.Pin("output", RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
            RuntimeTestOperation.semantics());
        return RuntimeBinding.available(descriptor, provider, "1.0.0",
            ignored -> CompletableFuture.completedFuture(RuntimeResult.success(
                TypedValue.value(type("string"), "ok"))));
    }

    private static ContractRef<ProviderId> provider(String localId) {
        return ContractRef.of(OWNER, ProviderId.of(localId));
    }

    private static RuntimeProviderLifecycle lifecycle(boolean ready, AtomicBoolean compensated, boolean failCompensation) {
        return new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor ignored, List<RuntimeBindingDescriptor> bindings) {
                return ready;
            }

            @Override
            public void compensate() {
                compensated.set(true);
                if (failCompensation) {
                    throw new IllegalStateException("compensation failed");
                }
            }

            @Override
            public void shutdown() {
            }
        };
    }

    private static TypeExpr type(String localId) {
        return TypeExpr.named(TypeReference.of(OWNER.value(), localId));
    }

    private static final class RuntimeTestOperation {
        private static RuntimeSemantics semantics() {
            return new RuntimeSemantics(
                RuntimeSemantics.Effect.PURE,
                RuntimeSemantics.ThreadMode.CURRENT,
                ContractRef.of(OWNER, CapabilityId.of("authorization")),
                RuntimeSemantics.Cancellation.NONE,
                0,
                0,
                0,
                RuntimeSemantics.UnloadPolicy.DRAIN,
                RuntimeSemantics.Retry.NEVER,
                RuntimeSemantics.Idempotency.INTRINSIC,
                RuntimeSemantics.Audit.NONE,
                RuntimeSemantics.Confirmation.NONE,
                RuntimeSemantics.SensitiveData.NONE,
                RuntimeSemantics.Determinism.DETERMINISTIC,
                Set.of("success"),
                Set.of("failure"),
                Set.of(),
                new RuntimeFailureContract(type("failure"), Set.of("RUNTIME.FAILURE"), Set.of("failure"),
                    RuntimeFailureContract.CommitBoundary.NO_MUTATION),
                Set.of(),
                Set.of());
        }
    }
}
