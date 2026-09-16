package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.ProviderId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ReplacementRuntimeProviderRegistry {
    private final AtomicReference<Snapshot> active = new AtomicReference<>(Snapshot.empty());
    private final Object lifecycleMonitor = new Object();
    private final Map<ContractRef<ProviderId>, ProviderSlot> slots = new LinkedHashMap<>();

    public Snapshot snapshot() {
        return active.get();
    }

    public StagedProvider stage(
        RuntimeProviderDescriptor provider,
        Collection<BindingRegistration> bindings,
        RuntimeProviderLifecycle lifecycle
    ) {
        Objects.requireNonNull(provider, "Provider Is Required");
        Objects.requireNonNull(bindings, "Bindings Are Required");
        Objects.requireNonNull(lifecycle, "Provider Lifecycle Is Required");
        if (provider.state() != RuntimeProviderState.ACTIVE && provider.state() != RuntimeProviderState.STAGED) {
            throw new IllegalArgumentException("Only Active Or Staged Providers Can Be Prepared");
        }
        List<BindingRegistration> contribution = validateContribution(bindings);
        contribution.forEach(binding -> {
            if (!provider.provider().equals(binding.provider())) {
                throw new IllegalArgumentException("Binding Provider Does Not Match Contribution Provider: " + binding.key().canonical());
            }
            if (!provider.version().equals(binding.providerVersion())) {
                throw new IllegalArgumentException("Binding Provider Version Does Not Match Contribution Provider: " + binding.key().canonical());
            }
        });
        RuntimeProviderDescriptor stagedDescriptor = provider.state() == RuntimeProviderState.STAGED
            ? provider
            : provider.withState(RuntimeProviderState.STAGED);
        StagedProvider staged = new StagedProvider(stagedDescriptor, contribution, lifecycle);
        try {
            if (!lifecycle.ready(stagedDescriptor, contribution.stream()
                .map(binding -> binding.bindingDescriptor(stagedDescriptor))
                .toList())) {
                throw new RuntimeCapabilityUnavailableException(stagedDescriptor.provider(), "provider readiness failed");
            }
        } catch (RuntimeException | Error failure) {
            staged.compensate();
            throw failure;
        }
        synchronized (lifecycleMonitor) {
            try {
                validateActivationLocked(staged);
            } catch (RuntimeException | Error failure) {
                staged.compensate();
                throw failure;
            }
        }
        return staged;
    }

    public StagedProvider stage(
        RuntimeProviderDescriptor provider,
        Collection<BindingRegistration> bindings
    ) {
        return stage(provider, bindings, RuntimeProviderLifecycle.stateless());
    }

    public StagedProvider prepare(
        RuntimeProviderDescriptor provider,
        Collection<BindingRegistration> bindings,
        RuntimeProviderLifecycle lifecycle
    ) {
        return stage(provider, bindings, lifecycle);
    }

    public Snapshot activate(StagedProvider staged) {
        Objects.requireNonNull(staged, "Staged Provider Is Required");
        synchronized (lifecycleMonitor) {
            if (staged.closed.get()) {
                throw new IllegalStateException("Staged Provider Is Closed");
            }
            if (staged.activated.get()) {
                throw new IllegalStateException("Staged Provider Is Already Activated");
            }
            validateActivationLocked(staged);
            Snapshot current = active.get();
            Map<RuntimeBindingKey, BindingRegistration> nextBindings = new LinkedHashMap<>(current.bindings());
            staged.bindings.forEach(binding -> nextBindings.put(binding.key(), binding));
            Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> nextProviders = new LinkedHashMap<>(current.providers());
            RuntimeProviderDescriptor activeProvider = staged.provider.withState(RuntimeProviderState.ACTIVE);
            nextProviders.put(activeProvider.provider(), activeProvider);
            Snapshot next = Snapshot.create(current.generation() + 1, nextBindings, nextProviders);
            active.set(next);
            slots.put(activeProvider.provider(), new ProviderSlot(staged.lifecycle));
            staged.activated.set(true);
            return next;
        }
    }

    public Snapshot activate(
        RuntimeProviderDescriptor provider,
        Collection<BindingRegistration> bindings,
        RuntimeProviderLifecycle lifecycle
    ) {
        StagedProvider staged = stage(provider, bindings, lifecycle);
        try {
            return activate(staged);
        } catch (RuntimeException | Error failure) {
            staged.compensate();
            throw failure;
        }
    }

    public Snapshot activate(
        RuntimeProviderDescriptor provider,
        Collection<BindingRegistration> bindings
    ) {
        return activate(provider, bindings, RuntimeProviderLifecycle.stateless());
    }

    public ResolvedOperation resolve(RuntimeOperationDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "Runtime Operation Descriptor Is Required");
        return resolve(descriptor.capability(), descriptor.operation(), descriptor, descriptor.executionFingerprint());
    }

    public ResolvedOperation resolve(
        ContractRef<CapabilityId> capability,
        ContractRef<OperationId> operation,
        RuntimeOperationDescriptor descriptor,
        ContentHash fingerprint
    ) {
        Objects.requireNonNull(capability, "Capability Is Required");
        Objects.requireNonNull(operation, "Operation Is Required");
        Objects.requireNonNull(descriptor, "Runtime Operation Descriptor Is Required");
        Objects.requireNonNull(fingerprint, "Runtime Execution Fingerprint Is Required");
        RuntimeBindingKey key = new RuntimeBindingKey(capability, operation);
        synchronized (lifecycleMonitor) {
            if (!capability.equals(descriptor.capability()) || !operation.equals(descriptor.operation())) {
                throw unavailable(key, "RUNTIME.INVALID_INVOCATION", "authored capability or operation differs from the descriptor");
            }
            BindingRegistration binding = active.get().bindings().get(key);
            if (binding == null) {
                throw unavailable(key, "GRAPH.RUNTIME_BINDING_MISSING", "replacement runtime binding is not registered");
            }
            if (!binding.descriptor().equals(descriptor)
                || !binding.fingerprint().equals(fingerprint)
                || !descriptor.executionFingerprint().equals(fingerprint)) {
                throw unavailable(key, "RUNTIME.INVALID_INVOCATION", "runtime descriptor or execution fingerprint differs from the registered binding");
            }
            RuntimeProviderDescriptor provider = active.get().providers().get(binding.provider());
            ProviderSlot slot = slots.get(binding.provider());
            if (provider == null || slot == null) {
                throw unavailable(key, "GRAPH.RUNTIME_BINDING_MISSING", "runtime provider is not active");
            }
            if (provider.state() != RuntimeProviderState.ACTIVE || slot.unloadRequested.get()) {
                String code = provider.state() == RuntimeProviderState.REVOKED
                    ? "RUNTIME.PROVIDER_REVOKED"
                    : "RUNTIME.PROVIDER_DRAINING";
                throw unavailable(key, code, "runtime provider is unavailable");
            }
            slot.leases.incrementAndGet();
            return new ResolvedOperation(this, binding, new RuntimeBindingKey(capability, operation));
        }
    }

    public ResolvedOperation resolve(
        ContractRef<CapabilityId> capability,
        OperationId operation,
        RuntimeOperationDescriptor descriptor,
        ContentHash fingerprint
    ) {
        Objects.requireNonNull(operation, "Operation Is Required");
        return resolve(capability, ContractRef.of(capability.owner(), operation), descriptor, fingerprint);
    }

    public RuntimeUnloadResult unload(ContractRef<ProviderId> provider) {
        Objects.requireNonNull(provider, "Provider Is Required");
        long started = System.currentTimeMillis();
        RuntimeProviderLifecycle shutdown = null;
        RuntimeUnloadResult result;
        synchronized (lifecycleMonitor) {
            RuntimeProviderDescriptor descriptor = active.get().providers().get(provider);
            ProviderSlot slot = slots.get(provider);
            if (descriptor == null || slot == null) {
                return result(provider, RuntimeUnloadResult.Status.NOT_FOUND, 0, started);
            }
            slot.unloadRequested.set(true);
            if (descriptor.state() == RuntimeProviderState.ACTIVE) {
                updateProviderStateLocked(provider, RuntimeProviderState.DRAINING);
                descriptor = active.get().providers().get(provider);
            }
            int remaining = slot.leases.get();
            if (remaining > 0) {
                result = result(provider, RuntimeUnloadResult.Status.BLOCKED, remaining, started);
            } else {
                removeProviderLocked(provider);
                shutdown = slot.lifecycle;
                result = result(provider, RuntimeUnloadResult.Status.REMOVED, 0, started);
            }
        }
        if (shutdown != null) {
            shutdown.shutdown();
        }
        return result;
    }

    public RuntimeUnloadResult tryUnload(ContractRef<ProviderId> provider) {
        return unload(provider);
    }

    private List<BindingRegistration> validateContribution(Collection<BindingRegistration> values) {
        List<BindingRegistration> contribution = values.stream()
            .map(value -> Objects.requireNonNull(value, "Binding Cannot Be Null"))
            .toList();
        if (contribution.isEmpty()) {
            throw new IllegalArgumentException("Provider Contribution Requires A Binding");
        }
        Map<RuntimeBindingKey, BindingRegistration> unique = new LinkedHashMap<>();
        contribution.forEach(binding -> {
            if (unique.putIfAbsent(binding.key(), binding) != null) {
                throw new RuntimeBindingCollisionException(binding.key());
            }
        });
        return List.copyOf(contribution);
    }

    private void validateActivationLocked(StagedProvider staged) {
        Snapshot current = active.get();
        if (current.providers().containsKey(staged.provider.provider())) {
            throw new RuntimeBindingCollisionException(staged.provider.provider());
        }
        staged.bindings.forEach(binding -> {
            if (current.bindings().containsKey(binding.key())) {
                throw new RuntimeBindingCollisionException(binding.key());
            }
        });
    }

    private void release(ContractRef<ProviderId> provider) {
        RuntimeProviderLifecycle shutdown = null;
        synchronized (lifecycleMonitor) {
            ProviderSlot slot = slots.get(provider);
            if (slot == null) {
                return;
            }
            int remaining = slot.leases.decrementAndGet();
            if (remaining < 0) {
                slot.leases.incrementAndGet();
                throw new IllegalStateException("Runtime Provider Lease Released More Than Once");
            }
            RuntimeProviderDescriptor descriptor = active.get().providers().get(provider);
            if (remaining == 0 && slot.unloadRequested.get() && descriptor != null) {
                removeProviderLocked(provider);
                shutdown = slot.lifecycle;
            }
        }
        if (shutdown != null) {
            shutdown.shutdown();
        }
    }

    private void updateProviderStateLocked(ContractRef<ProviderId> provider, RuntimeProviderState state) {
        Snapshot current = active.get();
        RuntimeProviderDescriptor descriptor = current.providers().get(provider);
        if (descriptor == null) {
            return;
        }
        Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> nextProviders = new LinkedHashMap<>(current.providers());
        nextProviders.put(provider, descriptor.withState(state));
        active.set(Snapshot.create(current.generation() + 1, current.bindings(), nextProviders));
    }

    private void removeProviderLocked(ContractRef<ProviderId> provider) {
        Snapshot current = active.get();
        Map<RuntimeBindingKey, BindingRegistration> nextBindings = new LinkedHashMap<>(current.bindings());
        nextBindings.values().removeIf(binding -> provider.equals(binding.provider()));
        Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> nextProviders = new LinkedHashMap<>(current.providers());
        nextProviders.remove(provider);
        active.set(Snapshot.create(current.generation() + 1, nextBindings, nextProviders));
        slots.remove(provider);
    }

    private static RuntimeCapabilityUnavailableException unavailable(RuntimeBindingKey key, String code, String reason) {
        return new RuntimeCapabilityUnavailableException(key, code, reason);
    }

    private static RuntimeUnloadResult result(
        ContractRef<ProviderId> provider,
        RuntimeUnloadResult.Status status,
        int remaining,
        long started
    ) {
        return new RuntimeUnloadResult(provider, status, remaining, false, Math.max(0, System.currentTimeMillis() - started));
    }

    private static final class ProviderSlot {
        private final RuntimeProviderLifecycle lifecycle;
        private final AtomicInteger leases = new AtomicInteger();
        private final AtomicBoolean unloadRequested = new AtomicBoolean();

        private ProviderSlot(RuntimeProviderLifecycle lifecycle) {
            this.lifecycle = lifecycle;
        }
    }

    public record BindingRegistration(
        RuntimeOperationDescriptor descriptor,
        ContentHash fingerprint,
        ContractRef<ProviderId> provider,
        String providerVersion,
        RuntimeOperationHandler handler
    ) {
        public BindingRegistration {
            descriptor = Objects.requireNonNull(descriptor, "Runtime Operation Descriptor Is Required");
            fingerprint = Objects.requireNonNull(fingerprint, "Runtime Execution Fingerprint Is Required");
            provider = Objects.requireNonNull(provider, "Provider Is Required");
            providerVersion = Objects.requireNonNull(providerVersion, "Provider Version Is Required");
            handler = Objects.requireNonNull(handler, "Runtime Operation Handler Is Required");
            if (!providerVersion.equals(providerVersion.trim()) || providerVersion.isEmpty()) {
                throw new IllegalArgumentException("Provider Version Is Required");
            }
            if (!descriptor.executionFingerprint().equals(fingerprint)) {
                throw new IllegalArgumentException("Runtime Execution Fingerprint Does Not Match Descriptor");
            }
        }

        public BindingRegistration(
            RuntimeOperationDescriptor descriptor,
            ContractRef<ProviderId> provider,
            String providerVersion,
            RuntimeOperationHandler handler
        ) {
            this(descriptor, descriptor.executionFingerprint(), provider, providerVersion, handler);
        }

        public RuntimeBindingKey key() {
            return descriptor.key();
        }

        private RuntimeBindingDescriptor bindingDescriptor(RuntimeProviderDescriptor ignored) {
            return new RuntimeBindingDescriptor(
                descriptor,
                provider,
                providerVersion,
                true);
        }
    }

    public final class StagedProvider implements AutoCloseable {
        private final RuntimeProviderDescriptor provider;
        private final List<BindingRegistration> bindings;
        private final RuntimeProviderLifecycle lifecycle;
        private final AtomicBoolean activated = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean compensated = new AtomicBoolean();

        private StagedProvider(
            RuntimeProviderDescriptor provider,
            List<BindingRegistration> bindings,
            RuntimeProviderLifecycle lifecycle
        ) {
            this.provider = provider;
            this.bindings = List.copyOf(bindings);
            this.lifecycle = lifecycle;
        }

        public RuntimeProviderDescriptor provider() {
            return provider;
        }

        public List<BindingRegistration> bindings() {
            return bindings;
        }

        public boolean activated() {
            return activated.get();
        }

        @Override
        public void close() {
            if (activated.get()) {
                return;
            }
            closed.set(true);
            compensate();
        }

        private void compensate() {
            if (compensated.compareAndSet(false, true)) {
                lifecycle.compensate();
            }
        }
    }

    public final class ResolvedOperation implements AutoCloseable {
        private final ReplacementRuntimeProviderRegistry registry;
        private final BindingRegistration binding;
        private final RuntimeBindingKey key;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ResolvedOperation(
            ReplacementRuntimeProviderRegistry registry,
            BindingRegistration binding,
            RuntimeBindingKey key
        ) {
            this.registry = registry;
            this.binding = binding;
            this.key = key;
        }

        public RuntimeOperationDescriptor descriptor() {
            return binding.descriptor();
        }

        public ContentHash fingerprint() {
            return binding.fingerprint();
        }

        public ContractRef<ProviderId> provider() {
            return binding.provider();
        }

        public RuntimeOperationHandler handler() {
            return this::execute;
        }

        public CompletionStage<RuntimeResult> execute(RuntimeInvocation invocation) {
            Objects.requireNonNull(invocation, "Runtime Invocation Is Required");
            if (!key.equals(invocation.binding())) {
                throw unavailable(key, "RUNTIME.INVALID_INVOCATION", "runtime invocation binding differs from the resolved operation");
            }
            if (closed.get()) {
                throw unavailable(key, "RUNTIME.PROVIDER_DRAINING", "resolved operation is closed");
            }
            CompletionStage<RuntimeResult> result;
            try {
                result = binding.handler().execute(invocation);
                if (result == null) {
                    throw new IllegalStateException("Runtime Operation Handler Returned No Completion Stage");
                }
            } catch (RuntimeException | Error failure) {
                close();
                return CompletableFuture.failedFuture(failure);
            }
            return result.whenComplete((ignored, failure) -> close());
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registry.release(binding.provider());
            }
        }
    }

    public record Snapshot(
        long generation,
        Map<RuntimeBindingKey, BindingRegistration> bindings,
        Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> providers
    ) {
        public Snapshot {
            if (generation < 0) {
                throw new IllegalArgumentException("Runtime Snapshot Generation Cannot Be Negative");
            }
            bindings = immutableBindings(bindings);
            providers = immutableProviders(providers);
            Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> providerSnapshot = providers;
            bindings.forEach((key, value) -> {
                if (!key.equals(value.key())) {
                    throw new IllegalArgumentException("Runtime Snapshot Binding Key Does Not Match Registration");
                }
                if (!providerSnapshot.containsKey(value.provider())) {
                    throw new IllegalArgumentException("Runtime Snapshot Binding Provider Is Missing");
                }
            });
        }

        private static Map<RuntimeBindingKey, BindingRegistration> immutableBindings(
            Map<RuntimeBindingKey, BindingRegistration> values
        ) {
            Objects.requireNonNull(values, "Runtime Snapshot Bindings Are Required");
            Map<RuntimeBindingKey, BindingRegistration> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "Runtime Snapshot Binding Key Is Required"),
                Objects.requireNonNull(value, "Runtime Snapshot Binding Is Required")));
            return Map.copyOf(copy);
        }

        private static Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> immutableProviders(
            Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> values
        ) {
            Objects.requireNonNull(values, "Runtime Snapshot Providers Are Required");
            Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "Runtime Snapshot Provider Key Is Required"),
                Objects.requireNonNull(value, "Runtime Snapshot Provider Is Required")));
            copy.forEach((key, value) -> {
                if (!key.equals(value.provider())) {
                    throw new IllegalArgumentException("Runtime Snapshot Provider Key Does Not Match Descriptor");
                }
            });
            return Map.copyOf(copy);
        }

        private static Snapshot empty() {
            return new Snapshot(0, Map.of(), Map.of());
        }

        private static Snapshot create(
            long generation,
            Map<RuntimeBindingKey, BindingRegistration> bindings,
            Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> providers
        ) {
            return new Snapshot(generation, bindings, providers);
        }

        public Optional<BindingRegistration> binding(RuntimeBindingKey key) {
            return Optional.ofNullable(bindings.get(key));
        }

        public Optional<RuntimeProviderDescriptor> provider(ContractRef<ProviderId> provider) {
            return Optional.ofNullable(providers.get(provider));
        }
    }
}
