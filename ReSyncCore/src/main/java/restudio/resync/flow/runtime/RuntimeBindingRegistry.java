package restudio.resync.flow.runtime;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.contract.diagnostic.RuntimePostCommitDiagnosticCode;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticMetricPolicy;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class RuntimeBindingRegistry {
    private final AtomicReference<RuntimeRegistrySnapshot> active = new AtomicReference<>(RuntimeRegistrySnapshot.empty());
    private final Object lifecycleMonitor = new Object();
    private final Map<ContractRef<ProviderId>, Set<RuntimePlanLease>> providerLeases = new HashMap<>();
    private final Map<RuntimeBindingKey, Set<RuntimePlanLease>> bindingLeases = new HashMap<>();
    private final Map<ContractRef<ProviderId>, List<GuardedHandler>> providerGuards = new HashMap<>();
    private final Map<ContractRef<ProviderId>, RuntimeProviderLifecycle> providerLifecycles = new HashMap<>();
    private final Set<RuntimeBindingKey> fencedBindings = new HashSet<>();
    private final Set<RuntimeBindingKey> revokedBindings = new HashSet<>();
    private final Clock clock;
    private final RuntimeSecurityBoundary securityBoundary;
    private final RuntimeAuditBoundary auditBoundary;
    private final RuntimeExecutionBoundary executionBoundary;
    private final RuntimeReceiptStore receiptStore;
    private final RuntimePrincipalAuthority principalAuthority;

    public RuntimeBindingRegistry() {
        this(Clock.systemUTC(), RuntimeSecurityBoundary.denyAll(), RuntimeAuditBoundary.unavailable(),
            RuntimeExecutionBoundary.unavailable(), RuntimeReceiptStore.inMemory(false));
    }

    public RuntimeBindingRegistry(Clock clock) {
        this(clock, RuntimeSecurityBoundary.denyAll(), RuntimeAuditBoundary.unavailable(),
            RuntimeExecutionBoundary.unavailable(), RuntimeReceiptStore.inMemory(false));
    }

    public RuntimeBindingRegistry(
        RuntimeSecurityBoundary securityBoundary,
        RuntimeAuditBoundary auditBoundary,
        RuntimeExecutionBoundary executionBoundary,
        RuntimeReceiptStore receiptStore
    ) {
        this(Clock.systemUTC(), securityBoundary, auditBoundary, executionBoundary, receiptStore, null);
    }

    public RuntimeBindingRegistry(
        RuntimeSecurityBoundary securityBoundary,
        RuntimeAuditBoundary auditBoundary,
        RuntimeExecutionBoundary executionBoundary,
        RuntimeReceiptStore receiptStore,
        RuntimePrincipalAuthority principalAuthority
    ) {
        this(Clock.systemUTC(), securityBoundary, auditBoundary, executionBoundary, receiptStore, principalAuthority);
    }

    public RuntimeBindingRegistry(
        Clock clock,
        RuntimeSecurityBoundary securityBoundary,
        RuntimeAuditBoundary auditBoundary,
        RuntimeExecutionBoundary executionBoundary,
        RuntimeReceiptStore receiptStore
    ) {
        this(clock, securityBoundary, auditBoundary, executionBoundary, receiptStore, null);
    }

    public RuntimeBindingRegistry(
        Clock clock,
        RuntimeSecurityBoundary securityBoundary,
        RuntimeAuditBoundary auditBoundary,
        RuntimeExecutionBoundary executionBoundary,
        RuntimeReceiptStore receiptStore,
        RuntimePrincipalAuthority principalAuthority
    ) {
        this.clock = Objects.requireNonNull(clock, "Clock Is Required");
        this.securityBoundary = Objects.requireNonNull(securityBoundary, "Runtime Security Boundary Is Required");
        this.auditBoundary = Objects.requireNonNull(auditBoundary, "Runtime Audit Boundary Is Required");
        this.executionBoundary = Objects.requireNonNull(executionBoundary, "Runtime Execution Boundary Is Required");
        this.receiptStore = Objects.requireNonNull(receiptStore, "Runtime Receipt Store Is Required");
        this.principalAuthority = principalAuthority;
    }

    public RuntimeRegistrySnapshot snapshot() {
        return active.get();
    }

    public RuntimeReceiptStore receiptStore() {
        return receiptStore;
    }

    public RuntimeAuditBoundary auditBoundary() {
        return auditBoundary;
    }

    public RuntimeReplacement prepareReplacement(
        Collection<RuntimeProviderContribution> additions,
        Collection<ContractRef<ProviderId>> retiring
    ) {
        Objects.requireNonNull(additions, "Runtime Provider Additions Are Required");
        Objects.requireNonNull(retiring, "Retiring Providers Are Required");
        RuntimeRegistrySnapshot baseline;
        Set<ContractRef<ProviderId>> retiringProviders = Set.copyOf(retiring);
        synchronized (lifecycleMonitor) {
            baseline = active.get();
            for (ContractRef<ProviderId> provider : retiringProviders) {
                Objects.requireNonNull(provider, "Retiring Provider Cannot Be Null");
                RuntimeProviderDescriptor descriptor = baseline.providers().get(provider);
                if (descriptor == null) {
                    throw new IllegalArgumentException("Retiring Provider Is Not Active: " + provider.canonicalText());
                }
                if (descriptor.state() != RuntimeProviderState.ACTIVE) {
                    throw new IllegalStateException("Retiring Provider Is Not Active: " + provider.canonicalText());
                }
            }
        }
        List<StagedProvider> staged = new ArrayList<>();
        try {
            for (RuntimeProviderContribution addition : additions) {
                Objects.requireNonNull(addition, "Runtime Provider Addition Cannot Be Null");
                StagedProvider value = stageReplacement(addition.provider(), addition.bindings(), addition.lifecycle(), retiringProviders, baseline);
                staged.add(value);
            }
            RuntimeReplacement replacement = new RuntimeReplacement(baseline, staged, retiringProviders);
            replacement.preview();
            return replacement;
        } catch (RuntimeException | Error failure) {
            for (StagedProvider value : staged) {
                try {
                    value.close();
                } catch (RuntimeException | Error compensationFailure) {
                    if (compensationFailure != failure) {
                        failure.addSuppressed(compensationFailure);
                    }
                }
            }
            rethrow(failure);
            throw new IllegalStateException("Unreachable");
        }
    }

    public RuntimeReplacement prepareReplacement(
        Collection<RuntimeProviderContribution> additions,
        Set<ContractRef<ProviderId>> retiring
    ) {
        return prepareReplacement(additions, (Collection<ContractRef<ProviderId>>) retiring);
    }

    public RuntimeReplacementResult commit(RuntimeReplacement replacement) {
        Objects.requireNonNull(replacement, "Runtime Replacement Is Required");
        return replacement.commit();
    }

    private StagedProvider stageReplacement(
        RuntimeProviderDescriptor provider,
        Collection<RuntimeBinding> contribution,
        RuntimeProviderLifecycle lifecycle,
        Set<ContractRef<ProviderId>> retiring,
        RuntimeRegistrySnapshot baseline
    ) {
        Objects.requireNonNull(provider, "Provider Is Required");
        Objects.requireNonNull(contribution, "Contribution Is Required");
        Objects.requireNonNull(lifecycle, "Provider Lifecycle Is Required");
        RuntimeProviderDescriptor stagedDescriptor;
        List<RuntimeBinding> bindings;
        synchronized (lifecycleMonitor) {
            if (active.get() != baseline) {
                throw new IllegalStateException("Runtime Active Snapshot Changed During Replacement Preparation");
            }
            validateProviderState(provider);
            bindings = validateContributionLocked(provider, contribution, retiring);
            stagedDescriptor = provider.state() == RuntimeProviderState.STAGED
                ? provider
                : provider.withState(RuntimeProviderState.STAGED);
        }
        StagedProvider staged = new StagedProvider(stagedDescriptor, bindings, lifecycle);
        try {
            if (!lifecycle.ready(stagedDescriptor, staged.bindings.stream().map(RuntimeBinding::descriptor).toList())) {
                throw new RuntimeCapabilityUnavailableException(stagedDescriptor.provider(), "provider readiness failed");
            }
            synchronized (lifecycleMonitor) {
                if (staged.closed.get()) {
                    throw new IllegalStateException("Staged Provider Is Closed");
                }
                if (active.get() != baseline) {
                    throw new IllegalStateException("Runtime Active Snapshot Changed During Replacement Preparation");
                }
                if (baseline.providers().containsKey(stagedDescriptor.provider())
                    && !retiring.contains(stagedDescriptor.provider())) {
                    throw new RuntimeBindingCollisionException(stagedDescriptor.provider());
                }
                validateContributionLocked(stagedDescriptor, staged.bindings, retiring);
            }
            return staged;
        } catch (RuntimeException | Error failure) {
            compensateAndRethrow(staged, failure);
            throw new IllegalStateException("Unreachable");
        }
    }

    public StagedProvider stage(RuntimeProviderDescriptor provider, Collection<RuntimeBinding> contribution) {
        return stage(provider, contribution, RuntimeProviderLifecycle.stateless());
    }

    public StagedProvider stage(
        RuntimeProviderDescriptor provider,
        Collection<RuntimeBinding> contribution,
        RuntimeProviderLifecycle lifecycle
    ) {
        Objects.requireNonNull(provider, "Provider Is Required");
        Objects.requireNonNull(contribution, "Contribution Is Required");
        Objects.requireNonNull(lifecycle, "Provider Lifecycle Is Required");
        RuntimeProviderDescriptor stagedDescriptor;
        List<RuntimeBinding> bindings;
        synchronized (lifecycleMonitor) {
            validateProviderState(provider);
            bindings = validateContributionLocked(provider, contribution);
            stagedDescriptor = provider.state() == RuntimeProviderState.STAGED
                ? provider
                : provider.withState(RuntimeProviderState.STAGED);
        }
        StagedProvider staged = new StagedProvider(stagedDescriptor, bindings, lifecycle);
        try {
            if (!lifecycle.ready(stagedDescriptor, staged.bindings.stream().map(RuntimeBinding::descriptor).toList())) {
                throw new RuntimeCapabilityUnavailableException(stagedDescriptor.provider(), "provider readiness failed");
            }
        } catch (RuntimeException | Error failure) {
            compensateAndRethrow(staged, failure);
        }
        try {
            synchronized (lifecycleMonitor) {
                if (staged.closed.get()) {
                    throw new IllegalStateException("Staged Provider Is Closed");
                }
                RuntimeRegistrySnapshot current = active.get();
                if (current.providers().containsKey(stagedDescriptor.provider())) {
                    throw new RuntimeBindingCollisionException(stagedDescriptor.provider());
                }
                validateContributionLocked(stagedDescriptor, staged.bindings);
            }
        } catch (RuntimeException | Error failure) {
            compensateAndRethrow(staged, failure);
        }
        return staged;
    }

    public StagedProvider prepare(RuntimeProviderDescriptor provider, Collection<RuntimeBinding> contribution) {
        return stage(provider, contribution);
    }

    public RuntimeRegistrySnapshot publish(StagedProvider staged) {
        Objects.requireNonNull(staged, "Staged Provider Is Required");
        RuntimeRegistrySnapshot published = null;
        Throwable failure = null;
        synchronized (lifecycleMonitor) {
            try {
                if (staged.closed.get()) {
                    throw new IllegalStateException("Staged Provider Is Closed");
                }
                if (staged.published.get()) {
                    throw new IllegalStateException("Staged Provider Is Already Published");
                }
                RuntimeProviderDescriptor descriptor = staged.provider;
                RuntimeRegistrySnapshot current = active.get();
                if (current.providers().containsKey(descriptor.provider())) {
                    throw new RuntimeBindingCollisionException(descriptor.provider());
                }
                validateContributionLocked(descriptor, staged.bindings);
                Map<RuntimeBindingKey, RuntimeBinding> nextBindings = new LinkedHashMap<>(current.bindings());
                List<GuardedHandler> guards = staged.guards;
                staged.bindings.forEach(binding -> nextBindings.put(binding.key(), binding));
                Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> nextProviders = new LinkedHashMap<>(current.providers());
                RuntimeProviderDescriptor activeProvider = descriptor.withState(RuntimeProviderState.ACTIVE);
                nextProviders.put(activeProvider.provider(), activeProvider);
                RuntimeRegistrySnapshot next = RuntimeRegistrySnapshot.create(
                    current.generation() + 1,
                    nextBindings,
                    nextProviders,
                    List.of());
                if (!active.compareAndSet(current, next)) {
                    throw new IllegalStateException("Runtime Active Snapshot Changed During Publication");
                }
                providerLeases.put(activeProvider.provider(), new HashSet<>());
                providerGuards.put(activeProvider.provider(), guards);
                providerLifecycles.put(activeProvider.provider(), staged.lifecycle);
                staged.published.set(true);
                guards.forEach(GuardedHandler::enable);
                published = next;
            } catch (RuntimeException | Error exception) {
                failure = exception;
            }
        }
        if (failure != null) {
            compensateAndRethrow(staged, failure);
        }
        return published;
    }

    public RuntimeRegistrySnapshot activate(StagedProvider staged) {
        return publish(staged);
    }

    public RuntimeRegistrySnapshot activate(RuntimeProviderDescriptor provider, Collection<RuntimeBinding> contribution) {
        return publish(stage(provider, contribution));
    }

    public RuntimeRegistrySnapshot activate(
        RuntimeProviderDescriptor provider,
        Collection<RuntimeBinding> contribution,
        RuntimeProviderLifecycle lifecycle
    ) {
        return publish(stage(provider, contribution, lifecycle));
    }

    public RuntimeBinding resolve(RuntimeBindingKey key) {
        Objects.requireNonNull(key, "Binding Key Is Required");
        synchronized (lifecycleMonitor) {
            if (revokedBindings.contains(key)) {
                throw new RuntimeCapabilityUnavailableException(key, "binding is revoked");
            }
            if (fencedBindings.contains(key)) {
                throw new RuntimeCapabilityUnavailableException(key, "binding is unloading");
            }
            RuntimeBinding binding = active.get().bindings().get(key);
            if (binding == null) {
                throw new RuntimeCapabilityUnavailableException(key, "binding is not registered");
            }
            RuntimeProviderDescriptor provider = active.get().providers().get(binding.provider());
            if (provider == null || provider.state() != RuntimeProviderState.ACTIVE) {
                throw new RuntimeCapabilityUnavailableException(key, provider == null ? "provider is revoked" : "provider is draining");
            }
            return binding;
        }
    }

    public RuntimePlanLease acquire(RuntimeLeaseInput input) {
        return acquire(input, null);
    }

    public RuntimePlanLease acquire(RuntimeLeaseInput input, RuntimeRegistrySnapshot expectedSnapshot) {
        Objects.requireNonNull(input, "Lease Input Is Required");
        ContentHash planFingerprint = input.planFingerprint();
        RuntimeAuthority authority = input.authority();
        if (planFingerprint == null) {
            throw new IllegalArgumentException("A Concrete Plan Fingerprint Is Required");
        }
        List<RuntimeLeaseInput.BindingRequirement> requirements = List.copyOf(
            Objects.requireNonNull(input.bindings(), "Lease Bindings Are Required")).stream()
            .map(value -> Objects.requireNonNull(value, "Lease Requirement Cannot Be Null"))
            .toList();
        if (requirements.isEmpty()) {
            throw new IllegalArgumentException("Lease Input Requires At Least One Binding");
        }
        RuntimeLeaseInput immutableInput = new RuntimeLeaseInput.Default(requirements, planFingerprint, authority,
            input.principal(), input.catalogGeneration(), input.catalogHash(), input.mutationId(),
            input.invocationId() == null ? CorrelationId.random() : input.invocationId(),
            input.requestedDeadlineMillis());
        LeasePreparation preparation;
        synchronized (lifecycleMonitor) {
            requireExpectedSnapshotLocked(expectedSnapshot, requirements.getFirst().binding());
            preparation = prepareLeaseLocked(requirements, planFingerprint);
        }
        for (RuntimeBinding binding : preparation.bindings.values()) {
            RuntimeSemantics semantics = binding.descriptor().semantics();
            RuntimeBindingKey key = binding.key();
            boolean principalRequired = semantics.effect() == RuntimeSemantics.Effect.STATE_MUTATING
                || semantics.effect() == RuntimeSemantics.Effect.DESTRUCTIVE
                || semantics.sensitiveData() != RuntimeSemantics.SensitiveData.NONE;
            if (principalAuthority != null) {
                boolean trustedPrincipal = input.principal() != null
                    && principalAuthority.trusts(input.principal(), authority);
                long requestedDeadline = input.requestedDeadlineMillis() == RuntimeExecutionContext.NO_DEADLINE
                    ? semantics.executionDeadlineMillis(System.currentTimeMillis())
                    : input.requestedDeadlineMillis();
                boolean principalAllowed = securityBoundary.principalAllowed(new RuntimeExecutionContext(
                    binding.descriptor(), authority, input.principal(), requestedDeadline));
                if ((input.principal() != null && !trustedPrincipal)
                    || (input.principal() == null && securityBoundary.requiresTrustedPrincipal())
                    || (principalRequired && (!trustedPrincipal || !principalAllowed))) {
                    throw new RuntimeCapabilityUnavailableException(key, "RUNTIME.AUTHORIZATION_DENIED",
                        "trusted runtime principal is required");
                }
            } else if (principalRequired && input.principal() != null && !securityBoundary.principalAllowed(
                new RuntimeExecutionContext(binding.descriptor(), authority, input.principal(),
                    semantics.executionDeadlineMillis(System.currentTimeMillis())))) {
                throw new RuntimeCapabilityUnavailableException(key, "RUNTIME.AUTHORIZATION_DENIED",
                    "runtime principal is not trusted");
            }
            if (!receiptStore.available() || receiptStore.quiesced()) {
                throw new RuntimeCapabilityUnavailableException(key, "RUNTIME.INVALID_INVOCATION",
                    "runtime durability is unavailable or quiesced");
            }
            if (!securityBoundary.authorize(authority, semantics.authorization())) {
                throw new RuntimeCapabilityUnavailableException(key, "RUNTIME.AUTHORIZATION_DENIED", "authorization not granted");
            }
            if (!securityBoundary.confirm(authority, key, semantics.confirmation())) {
                throw new RuntimeCapabilityUnavailableException(key, "RUNTIME.AUTHORIZATION_DENIED", "confirmation not granted");
            }
            if (!auditBoundary.available(semantics.audit())) {
                throw new RuntimeCapabilityUnavailableException(key, "RUNTIME.INVALID_INVOCATION", "required audit boundary is unavailable");
            }
            RuntimeExecutionContext executionContext = new RuntimeExecutionContext(
                binding.descriptor(), authority,
                input.principal(),
                semantics.executionDeadlineMillis(System.currentTimeMillis()));
            if (!executionBoundary.supports(executionContext)) {
                throw new RuntimeCapabilityUnavailableException(key, "RUNTIME.INVALID_INVOCATION", "required execution boundary is unavailable");
            }
            if ((semantics.idempotency() == RuntimeSemantics.Idempotency.MUTATION_ID
                || semantics.idempotency() == RuntimeSemantics.Idempotency.OPERATION_KEY)
                && !receiptStore.durable()) {
                throw new RuntimeCapabilityUnavailableException(key, "RUNTIME.INVALID_INVOCATION", "idempotency requires a durable receipt store");
            }
        }
        synchronized (lifecycleMonitor) {
            requireExpectedSnapshotLocked(expectedSnapshot, requirements.getFirst().binding());
            revalidateLeaseLocked(preparation);
            Map<ContractRef<ProviderId>, RuntimeCancellationToken> providerTokens = new LinkedHashMap<>();
            Map<RuntimeBindingKey, RuntimeCancellationToken> bindingTokens = new LinkedHashMap<>();
            for (ContractRef<ProviderId> provider : preparation.providers) {
                providerTokens.put(provider, new RuntimeCancellationToken());
            }
            for (RuntimeBindingKey key : preparation.bindings.keySet()) {
                bindingTokens.put(key, providerTokens.get(preparation.bindings.get(key).provider()).child());
            }
            AtomicReference<RuntimePlanLease> leaseReference = new AtomicReference<>();
            RuntimePlanLease lease = new RuntimePlanLease(
                preparation.bindings,
                preparation.fingerprints,
                preparation.requirements,
                providerTokens,
                bindingTokens,
                immutableInput,
                securityBoundary,
                auditBoundary,
                executionBoundary,
                receiptStore,
                active.get(),
                () -> release(leaseReference.get()));
            leaseReference.set(lease);
            for (ContractRef<ProviderId> provider : preparation.providers) {
                providerLeases.computeIfAbsent(provider, ignored -> new HashSet<>()).add(lease);
            }
            for (RuntimeBindingKey key : preparation.bindings.keySet()) {
                bindingLeases.computeIfAbsent(key, ignored -> new HashSet<>()).add(lease);
            }
            return lease;
        }
    }

    private void requireExpectedSnapshotLocked(RuntimeRegistrySnapshot expectedSnapshot, RuntimeBindingKey key) {
        if (expectedSnapshot == null) {
            return;
        }
        RuntimeRegistrySnapshot current = active.get();
        if (current != expectedSnapshot
            || current.generation() != expectedSnapshot.generation()
            || !current.bindingManifestHash().equals(expectedSnapshot.bindingManifestHash())) {
            throw new RuntimeCapabilityUnavailableException(key, "runtime activation changed during lease acquisition");
        }
    }

    private void release(RuntimePlanLease lease) {
        if (lease == null) {
            return;
        }
        List<Runnable> callbacks = new ArrayList<>();
        synchronized (lifecycleMonitor) {
            providerLeases.values().forEach(value -> value.remove(lease));
            bindingLeases.values().forEach(value -> value.remove(lease));
            List<RuntimeBindingKey> removableBindings = revokedBindings.stream()
                .filter(key -> bindingLeases.getOrDefault(key, Set.of()).isEmpty())
                .toList();
            for (RuntimeBindingKey key : removableBindings) {
                removeBindingLocked(key, callbacks);
                revokedBindings.remove(key);
                fencedBindings.remove(key);
            }
            List<ContractRef<ProviderId>> revokedProviders = providerLeases.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .filter(provider -> active.get().provider(provider)
                    .map(value -> value.state() == RuntimeProviderState.REVOKED)
                    .orElse(false))
                .toList();
            for (ContractRef<ProviderId> provider : revokedProviders) {
                removeProviderLocked(provider, callbacks);
            }
            lifecycleMonitor.notifyAll();
        }
        runCallbacks(callbacks);
    }

    public RuntimeUnloadResult unload(ContractRef<ProviderId> provider) {
        return unloadProvider(provider, false);
    }

    public RuntimeUnloadResult tryUnload(ContractRef<ProviderId> provider) {
        Objects.requireNonNull(provider, "Provider Is Required");
        long started = clock.millis();
        List<Runnable> callbacks = new ArrayList<>();
        RuntimeUnloadResult result;
        synchronized (lifecycleMonitor) {
            RuntimeProviderDescriptor descriptor = active.get().providers().get(provider);
            if (descriptor == null) {
                return result(provider, RuntimeUnloadResult.Status.NOT_FOUND, 0, false, started);
            }
            if (descriptor.state() == RuntimeProviderState.ACTIVE) {
                updateProviderStateLocked(provider, RuntimeProviderState.DRAINING);
                providerLeases.computeIfAbsent(provider, ignored -> new HashSet<>())
                    .forEach(lease -> lease.fenceForProvider(provider, false));
            } else if (descriptor.state() != RuntimeProviderState.DRAINING) {
                return result(provider, RuntimeUnloadResult.Status.ALREADY_UNLOADING, leaseCount(provider), false, started);
            }
            Set<RuntimePlanLease> leases = providerLeases.computeIfAbsent(provider, ignored -> new HashSet<>());
            if (!leases.isEmpty()) {
                return result(provider, RuntimeUnloadResult.Status.BLOCKED, leases.size(), false, started);
            }
            removeProviderLocked(provider, callbacks);
            result = result(provider, RuntimeUnloadResult.Status.REMOVED, 0, false, started);
        }
        runCallbacks(callbacks);
        return result;
    }

    public RuntimeUnloadResult securityRevoke(ContractRef<ProviderId> provider) {
        return unloadProvider(provider, true);
    }

    public RuntimeUnloadResult revoke(ContractRef<ProviderId> provider) {
        return securityRevoke(provider);
    }

    public RuntimeUnloadResult unload(RuntimeBindingKey key) {
        return unloadBinding(key, false);
    }

    public RuntimeUnloadResult tryUnload(RuntimeBindingKey key) {
        Objects.requireNonNull(key, "Binding Key Is Required");
        long started = clock.millis();
        List<Runnable> callbacks = new ArrayList<>();
        RuntimeBinding binding;
        RuntimeUnloadResult result;
        synchronized (lifecycleMonitor) {
            binding = active.get().bindings().get(key);
            if (binding == null) {
                return result(bindingProvider(key), RuntimeUnloadResult.Status.NOT_FOUND, 0, false, started);
            }
            if (!fencedBindings.contains(key)) {
                fencedBindings.add(key);
                bindingLeases.computeIfAbsent(key, ignored -> new HashSet<>())
                    .forEach(lease -> lease.fenceForBinding(key, false));
            }
            Set<RuntimePlanLease> leases = bindingLeases.computeIfAbsent(key, ignored -> new HashSet<>());
            if (!leases.isEmpty()) {
                return result(binding.provider(), RuntimeUnloadResult.Status.BLOCKED, leases.size(), false, started);
            }
            removeBindingLocked(key, callbacks);
            fencedBindings.remove(key);
            result = result(binding.provider(), RuntimeUnloadResult.Status.REMOVED, 0, false, started);
        }
        runCallbacks(callbacks);
        return result;
    }

    public RuntimeUnloadResult securityRevoke(RuntimeBindingKey key) {
        return unloadBinding(key, true);
    }

    private RuntimeUnloadResult unloadProvider(ContractRef<ProviderId> provider, boolean emergency) {
        Objects.requireNonNull(provider, "Provider Is Required");
        long started = clock.millis();
        List<Runnable> callbacks = new ArrayList<>();
        Set<RuntimePlanLease> leases;
        List<BindingLifecycle> policy;
        RuntimeUnloadResult result;
        synchronized (lifecycleMonitor) {
            RuntimeProviderDescriptor descriptor = active.get().providers().get(provider);
            if (descriptor == null) {
                return result(provider, RuntimeUnloadResult.Status.NOT_FOUND, 0, false, started);
            }
            if (descriptor.state() != RuntimeProviderState.ACTIVE) {
                return result(provider, RuntimeUnloadResult.Status.ALREADY_UNLOADING, leaseCount(provider), false, started);
            }
            List<RuntimeBinding> bindings = active.get().bindingValues().stream()
                .filter(binding -> provider.equals(binding.provider()))
                .toList();
            updateProviderStateLocked(provider, RuntimeProviderState.DRAINING);
            leases = providerLeases.computeIfAbsent(provider, ignored -> new HashSet<>());
            new ArrayList<>(leases).forEach(lease -> lease.fenceForProvider(provider, false));
            policy = bindings.stream()
                .map(binding -> lifecycle(binding, descriptor, emergency, started))
                .toList();
            if (!emergency && policy.stream().anyMatch(value -> value.blocking()
                && !bindingLeases.getOrDefault(value.key(), Set.of()).isEmpty())) {
                updateProviderStateLocked(provider, RuntimeProviderState.ACTIVE);
                new ArrayList<>(leases).forEach(lease -> lease.unfenceForProvider(provider));
                return result(provider, RuntimeUnloadResult.Status.BLOCKED, leases.size(), false, started);
            }
        }
        boolean cancellationRequested = waitFor(policy, started, leases);
        synchronized (lifecycleMonitor) {
            int remaining = leases.size();
            if (remaining > 0) {
                if (!emergency) {
                    updateProviderStateLocked(provider, RuntimeProviderState.ACTIVE);
                    new ArrayList<>(leases).forEach(lease -> lease.unfenceForProvider(provider));
                    return result(provider, RuntimeUnloadResult.Status.BLOCKED, remaining, cancellationRequested, started);
                }
                updateProviderStateLocked(provider, RuntimeProviderState.REVOKED);
                return result(provider, RuntimeUnloadResult.Status.REVOKED, remaining, cancellationRequested, started);
            }
            removeProviderLocked(provider, callbacks);
            result = result(provider, RuntimeUnloadResult.Status.REMOVED, 0, cancellationRequested, started);
        }
        runCallbacks(callbacks);
        return result;
    }

    private RuntimeUnloadResult unloadBinding(RuntimeBindingKey key, boolean emergency) {
        Objects.requireNonNull(key, "Binding Key Is Required");
        long started = clock.millis();
        List<Runnable> callbacks = new ArrayList<>();
        RuntimeBinding binding;
        Set<RuntimePlanLease> leases;
        BindingLifecycle lifecycle;
        RuntimeUnloadResult result;
        synchronized (lifecycleMonitor) {
            binding = active.get().bindings().get(key);
            if (binding == null) {
                return result(bindingProvider(key), RuntimeUnloadResult.Status.NOT_FOUND, 0, false, started);
            }
            if (!fencedBindings.add(key)) {
                return result(binding.provider(), RuntimeUnloadResult.Status.ALREADY_UNLOADING, leaseCount(key), false, started);
            }
            RuntimeProviderDescriptor provider = active.get().providers().get(binding.provider());
            leases = bindingLeases.computeIfAbsent(key, ignored -> new HashSet<>());
            new ArrayList<>(leases).forEach(lease -> lease.fenceForBinding(key, false));
            lifecycle = lifecycle(binding, provider, emergency, started);
            if (!emergency && lifecycle.blocking() && !leases.isEmpty()) {
                fencedBindings.remove(key);
                new ArrayList<>(leases).forEach(lease -> lease.unfenceForBinding(key));
                return result(binding.provider(), RuntimeUnloadResult.Status.BLOCKED, leases.size(), false, started);
            }
        }
        boolean cancellationRequested = waitFor(List.of(lifecycle), started, leases);
        synchronized (lifecycleMonitor) {
            int remaining = leases.size();
            if (remaining > 0) {
                if (!emergency) {
                    fencedBindings.remove(key);
                    new ArrayList<>(leases).forEach(lease -> lease.unfenceForBinding(key));
                    return result(binding.provider(), RuntimeUnloadResult.Status.BLOCKED, remaining, cancellationRequested, started);
                }
                revokedBindings.add(key);
                return result(binding.provider(), RuntimeUnloadResult.Status.REVOKED, remaining, cancellationRequested, started);
            }
            removeBindingLocked(key, callbacks);
            fencedBindings.remove(key);
            result = result(binding.provider(), RuntimeUnloadResult.Status.REMOVED, 0, cancellationRequested, started);
        }
        runCallbacks(callbacks);
        return result;
    }

    private BindingLifecycle lifecycle(
        RuntimeBinding binding,
        RuntimeProviderDescriptor provider,
        boolean emergency,
        long started
    ) {
        RuntimeSemantics semantics = binding.descriptor().semantics();
        RuntimeSemantics.UnloadPolicy policy = semantics.unloadPolicy();
        if (emergency && semantics.cancellable()) {
            policy = RuntimeSemantics.UnloadPolicy.SECURITY_CANCEL;
        }
        long drainMillis = provider == null
            ? semantics.drainDeadlineMillis()
            : Math.min(semantics.drainDeadlineMillis(), provider.drainDeadlineMillis());
        long hardMillis = provider == null
            ? semantics.hardDeadlineMillis()
            : Math.min(semantics.hardDeadlineMillis(), provider.hardDeadlineMillis());
        return new BindingLifecycle(
            binding.key(),
            deadline(started, drainMillis),
            deadline(started, hardMillis),
            policy,
            policy == RuntimeSemantics.UnloadPolicy.BLOCK || !semantics.cancellable(),
            false);
    }

    private boolean waitFor(
        List<BindingLifecycle> policies,
        long started,
        Set<RuntimePlanLease> leases
    ) {
        boolean cancellationRequested = false;
        long hardBudget = policies.stream()
            .mapToLong(value -> Math.max(0, value.hardDeadlineMillis() - started))
            .min()
            .orElse(0);
        long wallBudget = hardBudget > Long.MAX_VALUE - 1_000 ? Long.MAX_VALUE : hardBudget + 1_000;
        long wallStartedNanos = System.nanoTime();
        while (true) {
            List<Runnable> cancellationCallbacks = new ArrayList<>();
            long waitMillis;
            boolean hardDeadlineReached;
            long now;
            synchronized (lifecycleMonitor) {
                if (leases.isEmpty()) {
                    return cancellationRequested;
                }
                now = clock.millis();
                boolean blockingLease = policies.stream().anyMatch(value -> value.blocking()
                    && !bindingLeases.getOrDefault(value.key(), Set.of()).isEmpty());
                for (BindingLifecycle lifecycle : policies) {
                    if (now < lifecycle.drainDeadlineMillis() || lifecycle.securityCancellationRequested()) {
                        continue;
                    }
                    if (!blockingLease && lifecycle.policy() == RuntimeSemantics.UnloadPolicy.SECURITY_CANCEL) {
                        lifecycle.securityCancellationRequested(true);
                        cancellationRequested = true;
                        new ArrayList<>(bindingLeases.getOrDefault(lifecycle.key(), Set.of()))
                            .forEach(lease -> cancellationCallbacks.add(
                                () -> lease.fenceForBinding(lifecycle.key(), true)));
                    }
                }
                hardDeadlineReached = policies.stream().anyMatch(value -> now >= value.hardDeadlineMillis()
                    && !bindingLeases.getOrDefault(value.key(), Set.of()).isEmpty());
                waitMillis = Math.min(50L, Math.max(1L,
                    policies.stream()
                        .filter(value -> !bindingLeases.getOrDefault(value.key(), Set.of()).isEmpty())
                        .mapToLong(BindingLifecycle::hardDeadlineMillis).min().orElse(now) - now));
            }
            runCallbacks(cancellationCallbacks);
            if (hardDeadlineReached || RuntimeDeadline.elapsedNanos(wallStartedNanos, wallBudget)) {
                return cancellationRequested;
            }
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return cancellationRequested;
            }
        }
    }

    private LeasePreparation prepareLeaseLocked(
        List<RuntimeLeaseInput.BindingRequirement> requirements,
        ContentHash planFingerprint
    ) {
        RuntimeRegistrySnapshot current = active.get();
        Map<RuntimeBindingKey, RuntimeBinding> bindings = new LinkedHashMap<>();
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        Map<RuntimeBindingKey, RuntimeLeaseInput.BindingRequirement> leaseRequirements = new LinkedHashMap<>();
        Set<ContractRef<ProviderId>> providers = new HashSet<>();
        for (RuntimeLeaseInput.BindingRequirement requirement : requirements) {
            RuntimeBindingKey key = requirement.binding();
            RuntimeBinding binding = current.bindings().get(key);
            if (binding == null) {
                throw new RuntimeCapabilityUnavailableException(key, "binding is not registered");
            }
            if (fencedBindings.contains(key) || revokedBindings.contains(key)) {
                throw new RuntimeCapabilityUnavailableException(key, "binding is unloading");
            }
            RuntimeProviderDescriptor provider = current.providers().get(binding.provider());
            if (provider == null || provider.state() != RuntimeProviderState.ACTIVE) {
                throw new RuntimeCapabilityUnavailableException(key, "provider is "
                    + (provider == null ? "missing" : provider.state().name().toLowerCase()));
            }
            if (!binding.available()) {
                throw new RuntimeCapabilityUnavailableException(key, "binding is unavailable");
            }
            if (!requirement.fingerprint().equals(binding.executionFingerprint())) {
                throw new RuntimeCapabilityUnavailableException(key, "binding fingerprint changed");
            }
            requireExactPinContract(key, binding.descriptor(), requirement);
            RuntimeBinding existing = bindings.putIfAbsent(key, binding);
            if (existing != null && !requirement.fingerprint().equals(fingerprints.get(key))) {
                throw new RuntimeCapabilityUnavailableException(key, "duplicate binding fingerprints disagree");
            }
            fingerprints.putIfAbsent(key, requirement.fingerprint());
            RuntimeLeaseInput.BindingRequirement existingRequirement = leaseRequirements.putIfAbsent(key, requirement);
            if (existingRequirement != null && !existingRequirement.equals(requirement)) {
                throw new RuntimeCapabilityUnavailableException(key, "duplicate binding requirements disagree");
            }
            providers.add(binding.provider());
        }
        return new LeasePreparation(bindings, fingerprints, leaseRequirements, providers, planFingerprint);
    }

    private void revalidateLeaseLocked(LeasePreparation preparation) {
        RuntimeRegistrySnapshot current = active.get();
        for (Map.Entry<RuntimeBindingKey, RuntimeBinding> entry : preparation.bindings.entrySet()) {
            RuntimeBindingKey key = entry.getKey();
            RuntimeBinding binding = entry.getValue();
            if (current.bindings().get(key) != binding) {
                throw new RuntimeCapabilityUnavailableException(key, "binding changed during lease preparation");
            }
            if (fencedBindings.contains(key) || revokedBindings.contains(key)) {
                throw new RuntimeCapabilityUnavailableException(key, "binding is unloading");
            }
            RuntimeProviderDescriptor provider = current.providers().get(binding.provider());
            if (provider == null || provider.state() != RuntimeProviderState.ACTIVE) {
                throw new RuntimeCapabilityUnavailableException(key, "provider changed during lease preparation");
            }
            if (!binding.available() || !preparation.fingerprints.get(key).equals(binding.executionFingerprint())) {
                throw new RuntimeCapabilityUnavailableException(key, "binding changed during lease preparation");
            }
        }
    }

    private static void runCallbacks(List<Runnable> callbacks) {
        Throwable failure = null;
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
    }

    private static void compensateAndRethrow(StagedProvider staged, Throwable failure) {
        try {
            staged.compensate();
        } catch (RuntimeException | Error compensationFailure) {
            if (compensationFailure != failure) {
                failure.addSuppressed(compensationFailure);
            }
        }
        rethrow(failure);
    }

    private RuntimeBinding guarded(RuntimeBinding binding, List<GuardedHandler> guards) {
        if (binding.handler().isEmpty()) {
            return binding;
        }
        GuardedHandler guard = new GuardedHandler(binding.key(), binding.handler().orElseThrow());
        guards.add(guard);
        return new RuntimeBinding(binding.descriptor(), guard);
    }

    private List<RuntimeBinding> validateContributionLocked(
        RuntimeProviderDescriptor provider,
        Collection<RuntimeBinding> contribution
    ) {
        return validateContributionLocked(provider, contribution, Set.of());
    }

    private List<RuntimeBinding> validateContributionLocked(
        RuntimeProviderDescriptor provider,
        Collection<RuntimeBinding> contribution,
        Set<ContractRef<ProviderId>> replaceProviders
    ) {
        List<RuntimeBinding> bindings = contribution.stream()
            .map(binding -> Objects.requireNonNull(binding, "Contribution Cannot Contain Null"))
            .toList();
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("Provider Contribution Requires A Binding");
        }
        RuntimeRegistrySnapshot current = active.get();
        Set<RuntimeBindingKey> contributionKeys = new HashSet<>();
        for (RuntimeBinding binding : bindings) {
            validatePinContract(binding.descriptor());
            if (!provider.provider().equals(binding.provider())) {
                throw new IllegalArgumentException("Binding Provider Does Not Match Contribution Provider: " + binding.key().canonical());
            }
            if (!provider.version().equals(binding.descriptor().providerVersion())) {
                throw new IllegalArgumentException("Binding Provider Version Does Not Match Contribution Provider: " + binding.key().canonical());
            }
            RuntimeBinding existing = current.bindings().get(binding.key());
            if (!contributionKeys.add(binding.key())
                || (existing != null && !replaceProviders.contains(existing.provider()))) {
                throw new RuntimeBindingCollisionException(binding.key());
            }
            if (existing != null && replaceProviders.contains(existing.provider())
                && !existing.descriptor().pins().equals(binding.descriptor().pins())) {
                throw new IllegalArgumentException("Replacement Binding Pin Contract Does Not Match The Existing Operation: "
                    + binding.key().canonical());
            }
        }
        return bindings;
    }

    private static void requireExactPinContract(
        RuntimeBindingKey key,
        RuntimeBindingDescriptor descriptor,
        RuntimeLeaseInput.BindingRequirement requirement
    ) {
        validatePinContract(descriptor);
        List<RuntimeOperationDescriptor.Pin> inputPins = descriptor.pins().stream()
            .filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.INPUT)
            .toList();
        List<RuntimeOperationDescriptor.Pin> outputPins = descriptor.pins().stream()
            .filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.OUTPUT)
            .toList();
        if (!pinIds(inputPins).equals(requirement.inputPins())
            || !pinIds(outputPins).equals(requirement.outputPins())) {
            throw new RuntimeCapabilityUnavailableException(key,
                "plan pin bindings do not match the ordered operation pin contract");
        }
    }

    private static void validatePinContract(RuntimeBindingDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "Binding Descriptor Is Required");
        List<RuntimeOperationDescriptor.Pin> inputPins = descriptor.pins().stream()
            .filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.INPUT)
            .toList();
        List<RuntimeOperationDescriptor.Pin> outputPins = descriptor.pins().stream()
            .filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.OUTPUT)
            .toList();
        if (!inputTypes(inputPins).equals(descriptor.inputs()) || !outputTypes(outputPins).equals(descriptor.outputs())) {
            throw new IllegalArgumentException("Binding Pin Contract Does Not Match Its Input And Output Types");
        }
    }

    private static List<PinId> pinIds(List<RuntimeOperationDescriptor.Pin> pins) {
        return pins.stream().map(RuntimeOperationDescriptor.Pin::id).toList();
    }

    private static List<TypeExpr> inputTypes(List<RuntimeOperationDescriptor.Pin> pins) {
        return pins.stream().map(RuntimeOperationDescriptor.Pin::type).toList();
    }

    private static List<TypeExpr> outputTypes(List<RuntimeOperationDescriptor.Pin> pins) {
        return pins.stream().map(RuntimeOperationDescriptor.Pin::type).toList();
    }

    private void validateProviderState(RuntimeProviderDescriptor provider) {
        if (provider.state() != RuntimeProviderState.ACTIVE && provider.state() != RuntimeProviderState.STAGED) {
            throw new IllegalArgumentException("Only Active Or Staged Providers Can Be Prepared");
        }
    }

    private long deadline(long started, long timeoutMillis) {
        return RuntimeDeadline.deadlineMillisExact(started, timeoutMillis);
    }

    private RuntimeUnloadResult result(
        ContractRef<ProviderId> provider,
        RuntimeUnloadResult.Status status,
        int remaining,
        boolean cancellationRequested,
        long started
    ) {
        return new RuntimeUnloadResult(
            provider == null ? new ContractRef<>(new OwnerId("restudio.resync"), new ProviderId("unknown")) : provider,
            status,
            remaining,
            cancellationRequested,
            Math.max(0, clock.millis() - started));
    }

    private ContractRef<ProviderId> bindingProvider(RuntimeBindingKey key) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new ProviderId("unknown"));
    }

    private int leaseCount(ContractRef<ProviderId> provider) {
        return providerLeases.getOrDefault(provider, Set.of()).size();
    }

    private int leaseCount(RuntimeBindingKey key) {
        return bindingLeases.getOrDefault(key, Set.of()).size();
    }

    private void updateProviderStateLocked(ContractRef<ProviderId> provider, RuntimeProviderState state) {
        RuntimeRegistrySnapshot current = active.get();
        RuntimeProviderDescriptor descriptor = current.providers().get(provider);
        if (descriptor == null) {
            return;
        }
        Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> nextProviders = new LinkedHashMap<>(current.providers());
        nextProviders.put(provider, descriptor.withState(state));
        active.set(RuntimeRegistrySnapshot.create(current.generation() + 1, current.bindings(), nextProviders, List.of()));
    }

    private void removeBindingLocked(RuntimeBindingKey key, List<Runnable> callbacks) {
        RuntimeRegistrySnapshot current = active.get();
        RuntimeBinding removed = current.bindings().get(key);
        if (removed == null) {
            return;
        }
        disable(removed);
        Map<RuntimeBindingKey, RuntimeBinding> nextBindings = new LinkedHashMap<>(current.bindings());
        nextBindings.remove(key);
        active.set(RuntimeRegistrySnapshot.create(current.generation() + 1, nextBindings, current.providers(), List.of()));
        bindingLeases.remove(key);
        ContractRef<ProviderId> provider = removed.provider();
        boolean providerEmpty = nextBindings.values().stream().noneMatch(binding -> provider.equals(binding.provider()));
        if (providerEmpty) {
            removeProviderLocked(provider, callbacks);
        }
    }

    private void removeProviderLocked(ContractRef<ProviderId> provider, List<Runnable> callbacks) {
        RuntimeRegistrySnapshot current = active.get();
        Map<RuntimeBindingKey, RuntimeBinding> nextBindings = new LinkedHashMap<>(current.bindings());
        List<RuntimeBindingKey> removedKeys = nextBindings.values().stream()
            .filter(binding -> binding.provider().equals(provider))
            .map(RuntimeBinding::key)
            .toList();
        nextBindings.values().removeIf(binding -> binding.provider().equals(provider));
        Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> nextProviders = new LinkedHashMap<>(current.providers());
        nextProviders.remove(provider);
        active.set(RuntimeRegistrySnapshot.create(current.generation() + 1, nextBindings, nextProviders, List.of()));
        List<GuardedHandler> guards = providerGuards.remove(provider);
        if (guards != null) {
            guards.forEach(GuardedHandler::disable);
        }
        RuntimeProviderLifecycle lifecycle = providerLifecycles.remove(provider);
        providerLeases.remove(provider);
        removedKeys.forEach(bindingLeases::remove);
        removedKeys.forEach(fencedBindings::remove);
        removedKeys.forEach(revokedBindings::remove);
        callbacks.add(() -> receiptStore.releaseProvider(provider));
        if (lifecycle != null) {
            callbacks.add(lifecycle::shutdown);
        }
    }

    private void disable(RuntimeBinding binding) {
        binding.handler().filter(GuardedHandler.class::isInstance)
            .map(GuardedHandler.class::cast)
            .ifPresent(GuardedHandler::disable);
    }

    public record RuntimeProviderContribution(
        RuntimeProviderDescriptor provider,
        Collection<RuntimeBinding> bindings,
        RuntimeProviderLifecycle lifecycle
    ) {
        public RuntimeProviderContribution {
            provider = Objects.requireNonNull(provider, "Provider Is Required");
            bindings = List.copyOf(Objects.requireNonNull(bindings, "Provider Bindings Are Required"));
            lifecycle = Objects.requireNonNull(lifecycle, "Provider Lifecycle Is Required");
        }

        public RuntimeProviderContribution(RuntimeProviderDescriptor provider, Collection<RuntimeBinding> bindings) {
            this(provider, bindings, RuntimeProviderLifecycle.stateless());
        }
    }

    public enum ReplacementStatus {
        COMMITTED,
        COMMITTED_WITH_DIAGNOSTICS,
        NOOP,
        BLOCKED,
        STALE
    }

    public record RuntimePostCommitDiagnostic(
        ContractRef<ProviderId> provider,
        String operation,
        String code,
        String detail,
        int attempts,
        Map<String, Object> unknown
    ) {
        private static final OwnerId DIAGNOSTIC_OWNER = OwnerId.of("restudio.resync");

        public RuntimePostCommitDiagnostic(
            ContractRef<ProviderId> provider,
            String operation,
            String code,
            String detail,
            int attempts
        ) {
            this(provider, operation, code, detail, attempts, Map.of());
        }

        public RuntimePostCommitDiagnostic {
            provider = Objects.requireNonNull(provider, "Post-Commit Diagnostic Provider Is Required");
            operation = requireText(operation, "Post-Commit Diagnostic Operation");
            code = requireText(code, "Post-Commit Diagnostic Code");
            detail = requireText(detail, "Post-Commit Diagnostic Detail");
            if (attempts < 1) {
                throw new IllegalArgumentException("Post-Commit Diagnostic Attempts Must Be Positive");
            }
            RuntimePostCommitDiagnosticCode.require(code);
            DiagnosticCodeCatalog.defaultCatalog().require(code);
            unknown = RuntimeCanonicalSupport.unknown(unknown, "Post-Commit Diagnostic Unknown Data");
            RuntimeCanonicalSupport.rejectCollisions(unknown, "Post-Commit Diagnostic Unknown Data", Set.of(
                "provider", "operation", "code", "detail", "attempts"));
        }

        public Diagnostic toSharedDiagnostic() {
            DiagnosticCodeCatalog.Definition definition = DiagnosticCodeCatalog.defaultCatalog().require(code);
            return Diagnostic.builder(code, definition.severity(), definition.phase(), definition.stage())
                .messageKey(new ContractRef<>(DIAGNOSTIC_OWNER, new CapabilityId(definition.messageKey())))
                .message(definition.message())
                .arguments(arguments())
                .evidence(Map.of())
                .remediation(definition.remediation())
                .correlationId(correlationId())
                .unknown(unknown)
                .durable(definition.durable())
                .redaction(DiagnosticRedaction.PUBLIC)
                .metricPolicy(DiagnosticMetricPolicy.NONE)
                .build();
        }

        public Diagnostic diagnostic() {
            return toSharedDiagnostic();
        }

        public Diagnostic diagnosticContract() {
            return toSharedDiagnostic();
        }

        public Map<String, Object> arguments() {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("provider", provider.canonicalValue());
            arguments.put("operation", operation);
            arguments.put("detail", detail);
            arguments.put("attempts", attempts);
            return Map.copyOf(arguments);
        }

        public static RuntimePostCommitDiagnostic fromSharedDiagnostic(Diagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "Shared Post-Commit Diagnostic Is Required");
            RuntimePostCommitDiagnosticCode.require(diagnostic.code());
            Map<String, Object> arguments = diagnostic.arguments();
            return new RuntimePostCommitDiagnostic(
                provider(arguments.get("provider")),
                text(arguments.get("operation"), "operation"),
                diagnostic.code(),
                text(arguments.get("detail"), "detail"),
                integer(arguments.get("attempts"), "attempts"),
                diagnostic.unknown());
        }

        private UUID correlationId() {
            return UUID.nameUUIDFromBytes(JsonValue.fromJava(Map.of(
                "provider", provider.canonicalValue(),
                "operation", operation,
                "code", code,
                "detail", detail,
                "attempts", attempts,
                "unknown", unknown)).canonicalBytes());
        }

        private static ContractRef<ProviderId> provider(Object value) {
            if (value instanceof String text) {
                return ContractRef.parseCanonicalText(text, ProviderId::new);
            }
            if (!(value instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("provider must be a contract reference");
            }
            Map<String, Object> map = stringMap(raw, "provider");
            String owner = text(map.get("ownerId"), "provider.ownerId");
            String localId = text(map.get("localId"), "provider.localId");
            Map<String, Object> unknown = new LinkedHashMap<>(map);
            unknown.remove("ownerId");
            unknown.remove("localId");
            return new ContractRef<>(new OwnerId(owner), new ProviderId(localId), unknown);
        }

        private static Map<String, Object> stringMap(Map<?, ?> source, String field) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException(field + " keys must be text");
                }
                result.put(text, value);
            });
            return result;
        }

        private static String text(Object value, String field) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(field + " must be nonblank text");
            }
            return text;
        }

        private static int integer(Object value, String field) {
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            try {
                return new BigDecimal(number.toString()).intValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(field + " must be an integer", exception);
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank() || !value.equals(value.strip())) {
                throw new IllegalArgumentException(field + " Is Required");
            }
            return value;
        }
    }

    public record RuntimeReplacementResult(
        ReplacementStatus status,
        RuntimeRegistrySnapshot snapshot,
        int blockedLeases,
        List<RuntimePostCommitDiagnostic> diagnostics
    ) {
        public RuntimeReplacementResult {
            status = Objects.requireNonNull(status, "Replacement Status Is Required");
            snapshot = Objects.requireNonNull(snapshot, "Replacement Snapshot Is Required");
            if (blockedLeases < 0) {
                throw new IllegalArgumentException("Blocked Lease Count Cannot Be Negative");
            }
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            if (status != ReplacementStatus.COMMITTED_WITH_DIAGNOSTICS && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("Only A Committed Replacement May Carry Post-Commit Diagnostics");
            }
        }

        public RuntimeReplacementResult(ReplacementStatus status, RuntimeRegistrySnapshot snapshot, int blockedLeases) {
            this(status, snapshot, blockedLeases, List.of());
        }

        public boolean committed() {
            return status == ReplacementStatus.COMMITTED
                || status == ReplacementStatus.COMMITTED_WITH_DIAGNOSTICS
                || status == ReplacementStatus.NOOP;
        }

        public boolean pending() {
            return status == ReplacementStatus.BLOCKED || status == ReplacementStatus.STALE;
        }

        public boolean healthDegraded() {
            return !diagnostics.isEmpty();
        }
    }

    public final class RuntimeReplacement implements AutoCloseable {
        private final RuntimeRegistrySnapshot baseline;
        private final List<StagedProvider> additions;
        private final Set<ContractRef<ProviderId>> retiring;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean committed = new AtomicBoolean();
        private final Object commitMonitor = new Object();
        private volatile List<RetirementCleanup> postCommitCleanups = List.of();
        private volatile RuntimeRegistrySnapshot committedSnapshot;
        private RuntimeRegistrySnapshot previewSnapshot;

        private RuntimeReplacement(
            RuntimeRegistrySnapshot baseline,
            Collection<StagedProvider> additions,
            Set<ContractRef<ProviderId>> retiring
        ) {
            this.baseline = Objects.requireNonNull(baseline, "Replacement Baseline Is Required");
            this.additions = List.copyOf(additions);
            this.retiring = Set.copyOf(retiring);
        }

        public RuntimeRegistrySnapshot baseline() {
            return baseline;
        }

        public Set<ContractRef<ProviderId>> retiringProviders() {
            return retiring;
        }

        public List<RuntimeProviderDescriptor> additions() {
            return additions.stream().map(StagedProvider::provider).toList();
        }

        public boolean isNoop() {
            return additions.isEmpty() && retiring.isEmpty();
        }

        public RuntimeRegistrySnapshot preview() {
            synchronized (lifecycleMonitor) {
                ensureOpen();
                if (active.get() != baseline) {
                    throw new IllegalStateException("Runtime Active Snapshot Changed During Replacement");
                }
                if (previewSnapshot == null) {
                    previewSnapshot = buildSnapshotLocked(baseline);
                }
                return previewSnapshot;
            }
        }

        public int blockedLeases() {
            synchronized (lifecycleMonitor) {
                ensureOpen();
                if (active.get() != baseline) {
                    throw new IllegalStateException("Runtime Active Snapshot Changed During Replacement");
                }
                return blockedLeasesLocked();
            }
        }

        public RuntimeReplacementResult commit() {
            return commit(null);
        }

        public RuntimeReplacementResult commit(Consumer<RuntimeRegistrySnapshot> committedObserver) {
            synchronized (commitMonitor) {
                if (committed.get()) {
                    return retryPostCommitCleanup();
                }
                List<RetirementCleanup> cleanups = new ArrayList<>();
                List<RuntimePostCommitDiagnostic> diagnostics = new ArrayList<>();
                RuntimeRegistrySnapshot next;
                synchronized (lifecycleMonitor) {
                    ensureOpen();
                    RuntimeRegistrySnapshot current = active.get();
                    if (current != baseline) {
                        return new RuntimeReplacementResult(ReplacementStatus.STALE, current, 0);
                    }
                    if (isNoop()) {
                        committed.set(true);
                        committedSnapshot = current;
                        if (committedObserver != null) {
                            try {
                                committedObserver.accept(current);
                            } catch (RuntimeException | Error failure) {
                                diagnostics.add(observerDiagnostic(failure, 1));
                            }
                        }
                        postCommitCleanups = List.of();
                        return committedResult(current, ReplacementStatus.NOOP, diagnostics);
                    }
                    int blocked = blockedLeasesLocked();
                    if (blocked > 0) {
                        return new RuntimeReplacementResult(ReplacementStatus.BLOCKED, current, blocked);
                    }
                    next = previewSnapshot != null ? previewSnapshot : buildSnapshotLocked(current);
                    prepareRetireCleanupsLocked(cleanups);
                    active.set(next);
                    retireStateLocked();
                    for (StagedProvider addition : additions) {
                        RuntimeProviderDescriptor provider = addition.provider.withState(RuntimeProviderState.ACTIVE);
                        providerLeases.put(provider.provider(), new HashSet<>());
                        providerGuards.put(provider.provider(), addition.guards);
                        providerLifecycles.put(provider.provider(), addition.lifecycle);
                        addition.published.set(true);
                        addition.guards.forEach(GuardedHandler::enable);
                    }
                    committed.set(true);
                    committedSnapshot = next;
                    postCommitCleanups = List.copyOf(cleanups);
                    if (committedObserver != null) {
                        try {
                            committedObserver.accept(next);
                        } catch (RuntimeException | Error failure) {
                            diagnostics.add(observerDiagnostic(failure, 1));
                        }
                    }
                }
                diagnostics.addAll(runPostCommitCleanup());
                return committedResult(next, ReplacementStatus.COMMITTED, diagnostics);
            }
        }

        private RuntimeReplacementResult retryPostCommitCleanup() {
            List<RuntimePostCommitDiagnostic> diagnostics = runPostCommitCleanup();
            return committedResult(committedSnapshot, ReplacementStatus.COMMITTED, diagnostics);
        }

        private RuntimeReplacementResult committedResult(
            RuntimeRegistrySnapshot snapshot,
            ReplacementStatus cleanStatus,
            List<RuntimePostCommitDiagnostic> diagnostics
        ) {
            if (diagnostics.isEmpty()) {
                return new RuntimeReplacementResult(cleanStatus, snapshot, 0);
            }
            return new RuntimeReplacementResult(ReplacementStatus.COMMITTED_WITH_DIAGNOSTICS, snapshot, 0, diagnostics);
        }

        private List<RuntimePostCommitDiagnostic> runPostCommitCleanup() {
            List<RuntimePostCommitDiagnostic> diagnostics = new ArrayList<>();
            for (RetirementCleanup cleanup : postCommitCleanups) {
                diagnostics.addAll(cleanup.run());
            }
            return diagnostics;
        }

        private RuntimePostCommitDiagnostic observerDiagnostic(Throwable failure, int attempt) {
            return new RuntimePostCommitDiagnostic(
                new ContractRef<>(new OwnerId("restudio.resync"), new ProviderId("activation")),
                "committed-observer",
                RuntimePostCommitDiagnosticCode.OBSERVER_FAILED.wireName(),
                failureDetail(failure),
                attempt);
        }

        private RuntimeRegistrySnapshot buildSnapshotLocked(RuntimeRegistrySnapshot current) {
            Map<RuntimeBindingKey, RuntimeBinding> bindings = new LinkedHashMap<>(current.bindings());
            Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> providers = new LinkedHashMap<>(current.providers());
            for (ContractRef<ProviderId> provider : retiring) {
                providers.remove(provider);
                bindings.values().removeIf(binding -> provider.equals(binding.provider()));
            }
            for (StagedProvider addition : additions) {
                RuntimeProviderDescriptor provider = addition.provider.withState(RuntimeProviderState.ACTIVE);
                if (providers.putIfAbsent(provider.provider(), provider) != null) {
                    throw new RuntimeBindingCollisionException(provider.provider());
                }
                for (RuntimeBinding binding : addition.bindings) {
                    if (bindings.putIfAbsent(binding.key(), binding) != null) {
                        throw new RuntimeBindingCollisionException(binding.key());
                    }
                }
            }
            if (bindings.equals(current.bindings()) && providers.equals(current.providers())) {
                return current;
            }
            return RuntimeRegistrySnapshot.create(current.generation() + 1, bindings, providers, List.of());
        }

        private int blockedLeasesLocked() {
            Set<RuntimePlanLease> blocked = new HashSet<>();
            for (ContractRef<ProviderId> provider : retiring) {
                blocked.addAll(providerLeases.getOrDefault(provider, Set.of()));
                for (RuntimeBinding binding : baseline.bindingValues()) {
                    if (provider.equals(binding.provider())) {
                        blocked.addAll(bindingLeases.getOrDefault(binding.key(), Set.of()));
                    }
                }
            }
            return blocked.size();
        }

        private void prepareRetireCleanupsLocked(List<RetirementCleanup> cleanups) {
            for (ContractRef<ProviderId> provider : retiring) {
                List<GuardedHandler> guards = providerGuards.get(provider);
                RuntimeProviderLifecycle lifecycle = providerLifecycles.get(provider);
                cleanups.add(new RetirementCleanup(provider, guards, lifecycle));
            }
        }

        private void retireStateLocked() {
            for (ContractRef<ProviderId> provider : retiring) {
                providerGuards.remove(provider);
                providerLifecycles.remove(provider);
                providerLeases.remove(provider);
                List<RuntimeBindingKey> keys = baseline.bindingValues().stream()
                    .filter(binding -> provider.equals(binding.provider()))
                    .map(RuntimeBinding::key)
                    .toList();
                keys.forEach(bindingLeases::remove);
                keys.forEach(fencedBindings::remove);
                keys.forEach(revokedBindings::remove);
            }
        }

        private void ensureOpen() {
            if (closed.get() || committed.get()) {
                throw new IllegalStateException("Runtime Replacement Is Closed");
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                additions.forEach(StagedProvider::close);
            }
        }
    }

    private final class RetirementCleanup {
        private final ContractRef<ProviderId> provider;
        private final List<CleanupAction> actions;

        private RetirementCleanup(
            ContractRef<ProviderId> provider,
            List<GuardedHandler> guards,
            RuntimeProviderLifecycle lifecycle
        ) {
            this.provider = provider;
            List<GuardedHandler> guardedHandlers = guards == null ? List.of() : List.copyOf(guards);
            List<CleanupAction> values = new ArrayList<>();
            values.add(new CleanupAction("handler-retirement", () -> guardedHandlers.forEach(GuardedHandler::disable)));
            values.add(new CleanupAction("receipt-release", () -> receiptStore.releaseProvider(provider)));
            if (lifecycle != null) {
                values.add(new CleanupAction("provider-shutdown", lifecycle::shutdown));
            }
            this.actions = List.copyOf(values);
        }

        private synchronized List<RuntimePostCommitDiagnostic> run() {
            List<RuntimePostCommitDiagnostic> diagnostics = new ArrayList<>();
            for (CleanupAction action : actions) {
                RuntimePostCommitDiagnostic diagnostic = action.run(provider);
                if (diagnostic != null) {
                    diagnostics.add(diagnostic);
                }
            }
            return diagnostics;
        }
    }

    private final class CleanupAction {
        private final String operation;
        private final Runnable action;
        private boolean complete;
        private int attempts;

        private CleanupAction(String operation, Runnable action) {
            this.operation = operation;
            this.action = action;
        }

        private RuntimePostCommitDiagnostic run(ContractRef<ProviderId> provider) {
            if (complete) {
                return null;
            }
            attempts++;
            try {
                action.run();
                complete = true;
                return null;
            } catch (RuntimeException | Error failure) {
                return new RuntimePostCommitDiagnostic(provider, operation,
                    RuntimePostCommitDiagnosticCode.CLEANUP_FAILED.wireName(), failureDetail(failure), attempts);
            }
        }
    }

    private static String failureDetail(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message.strip());
    }

    public final class StagedProvider implements AutoCloseable {
        private final RuntimeProviderDescriptor provider;
        private final List<RuntimeBinding> bindings;
        private final List<GuardedHandler> guards;
        private final RuntimeProviderLifecycle lifecycle;
        private final AtomicBoolean published = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean compensated = new AtomicBoolean();

        private StagedProvider(RuntimeProviderDescriptor provider, List<RuntimeBinding> bindings, RuntimeProviderLifecycle lifecycle) {
            this.provider = provider;
            this.lifecycle = lifecycle;
            this.guards = new ArrayList<>();
            this.bindings = List.copyOf(bindings.stream().map(binding -> guarded(binding, guards)).toList());
        }

        public RuntimeProviderDescriptor provider() {
            return provider;
        }

        public List<RuntimeBinding> bindings() {
            return bindings;
        }

        public boolean published() {
            return published.get();
        }

        @Override
        public void close() {
            boolean compensateNow;
            synchronized (lifecycleMonitor) {
                compensateNow = !published.get();
                if (compensateNow) {
                    closed.set(true);
                    guards.forEach(GuardedHandler::disable);
                }
            }
            if (compensateNow) {
                compensate();
            }
        }

        private void compensate() {
            if (compensated.compareAndSet(false, true)) {
                lifecycle.compensate();
            }
        }
    }

    private static final class GuardedHandler implements RuntimeOperationHandler {
        private final RuntimeBindingKey key;
        private final RuntimeOperationHandler delegate;
        private final AtomicBoolean enabled = new AtomicBoolean();

        private GuardedHandler(RuntimeBindingKey key, RuntimeOperationHandler delegate) {
            this.key = key;
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<RuntimeResult> execute(RuntimeInvocation invocation) {
            if (!enabled.get()) {
                throw new RuntimeCapabilityUnavailableException(key, "provider is not active");
            }
            return delegate.execute(invocation);
        }

        private void enable() {
            enabled.set(true);
        }

        private void disable() {
            enabled.set(false);
        }
    }

    private static final class BindingLifecycle {
        private final RuntimeBindingKey key;
        private final long drainDeadlineMillis;
        private final long hardDeadlineMillis;
        private final RuntimeSemantics.UnloadPolicy policy;
        private final boolean blocking;
        private boolean securityCancellationRequested;

        private BindingLifecycle(
            RuntimeBindingKey key,
            long drainDeadlineMillis,
            long hardDeadlineMillis,
            RuntimeSemantics.UnloadPolicy policy,
            boolean blocking,
            boolean securityCancellationRequested
        ) {
            this.key = key;
            this.drainDeadlineMillis = drainDeadlineMillis;
            this.hardDeadlineMillis = hardDeadlineMillis;
            this.policy = policy;
            this.blocking = blocking;
            this.securityCancellationRequested = securityCancellationRequested;
        }

        private RuntimeBindingKey key() {
            return key;
        }

        private long drainDeadlineMillis() {
            return drainDeadlineMillis;
        }

        private long hardDeadlineMillis() {
            return hardDeadlineMillis;
        }

        private RuntimeSemantics.UnloadPolicy policy() {
            return policy;
        }

        private boolean blocking() {
            return blocking;
        }

        private boolean securityCancellationRequested() {
            return securityCancellationRequested;
        }

        private void securityCancellationRequested(boolean value) {
            securityCancellationRequested = value;
        }
    }

    private static final class LeasePreparation {
        private final Map<RuntimeBindingKey, RuntimeBinding> bindings;
        private final Map<RuntimeBindingKey, ContentHash> fingerprints;
        private final Map<RuntimeBindingKey, RuntimeLeaseInput.BindingRequirement> requirements;
        private final Set<ContractRef<ProviderId>> providers;

        private LeasePreparation(
            Map<RuntimeBindingKey, RuntimeBinding> bindings,
            Map<RuntimeBindingKey, ContentHash> fingerprints,
            Map<RuntimeBindingKey, RuntimeLeaseInput.BindingRequirement> requirements,
            Set<ContractRef<ProviderId>> providers,
            ContentHash ignoredPlanFingerprint
        ) {
            this.bindings = Map.copyOf(bindings);
            this.fingerprints = Map.copyOf(fingerprints);
            this.requirements = Map.copyOf(requirements);
            this.providers = Set.copyOf(providers);
        }
    }
}
