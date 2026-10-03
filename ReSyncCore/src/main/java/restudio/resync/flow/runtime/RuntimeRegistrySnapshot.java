package restudio.resync.flow.runtime;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.CapabilityId;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

public final class RuntimeRegistrySnapshot {
    private final long generation;
    private final Map<RuntimeBindingKey, RuntimeBinding> bindings;
    private final Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> providers;
    private final RuntimeBindingManifest manifest;
    private final Set<ContractRef<CapabilityId>> authorizations;

    private RuntimeRegistrySnapshot(
        long generation,
        Map<RuntimeBindingKey, RuntimeBinding> bindings,
        Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> providers,
        List<Diagnostic> diagnostics
    ) {
        if (generation < 0) {
            throw new IllegalArgumentException("Registry Generation Cannot Be Negative");
        }
        this.generation = generation;
        LinkedHashMap<ContractRef<ProviderId>, RuntimeProviderDescriptor> providerCopy = new LinkedHashMap<>();
        providers.forEach((key, value) -> providerCopy.put(
            Objects.requireNonNull(key, "Provider Key Cannot Be Null"),
            Objects.requireNonNull(value, "Provider Cannot Be Null")));
        this.providers = Collections.unmodifiableMap(providerCopy);
        providerCopy.forEach((key, provider) -> {
            if (!key.equals(provider.provider())) {
                throw new IllegalArgumentException("Snapshot Provider Key Does Not Match Provider Descriptor");
            }
        });
        LinkedHashMap<RuntimeBindingKey, RuntimeBinding> bindingCopy = new LinkedHashMap<>();
        bindings.forEach((key, value) -> bindingCopy.put(
            Objects.requireNonNull(key, "Binding Key Cannot Be Null"),
            Objects.requireNonNull(value, "Binding Cannot Be Null")));
        bindingCopy.forEach((key, binding) -> {
            if (!key.equals(binding.key())) {
                throw new IllegalArgumentException("Snapshot Binding Key Does Not Match Binding Descriptor");
            }
            if (!providerCopy.containsKey(binding.provider())) {
                throw new IllegalArgumentException("Snapshot Binding Provider Is Missing: " + binding.provider().canonicalText());
            }
        });
        this.bindings = Collections.unmodifiableMap(bindingCopy);
        Set<ContractRef<CapabilityId>> authorizations = new HashSet<>();
        bindingCopy.values().forEach(binding -> {
            if (binding.available()) {
                authorizations.add(binding.descriptor().semantics().authorization());
            }
        });
        this.authorizations = Set.copyOf(authorizations);
        this.manifest = buildManifest(this.bindings, this.providers, diagnostics);
    }

    public static RuntimeRegistrySnapshot empty() {
        return new RuntimeRegistrySnapshot(0, Map.of(), Map.of(), List.of());
    }

    static RuntimeRegistrySnapshot create(
        long generation,
        Map<RuntimeBindingKey, RuntimeBinding> bindings,
        Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> providers,
        List<Diagnostic> diagnostics
    ) {
        return new RuntimeRegistrySnapshot(generation, bindings, providers, diagnostics);
    }

    private static RuntimeBindingManifest buildManifest(
        Map<RuntimeBindingKey, RuntimeBinding> bindings,
        Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> providers,
        List<Diagnostic> diagnostics
    ) {
        List<RuntimeBindingDescriptor> descriptors = bindings.values().stream().map(RuntimeBinding::descriptor).toList();
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        bindings.values().forEach(binding -> fingerprints.put(binding.descriptor().key(), binding.executionFingerprint()));
        return RuntimeBindingManifest.create(providers.values(), descriptors, fingerprints, diagnostics);
    }

    public long generation() {
        return generation;
    }

    public Map<RuntimeBindingKey, RuntimeBinding> bindings() {
        return bindings;
    }

    public Map<ContractRef<ProviderId>, RuntimeProviderDescriptor> providers() {
        return providers;
    }

    public Optional<RuntimeBinding> binding(RuntimeBindingKey key) {
        return Optional.ofNullable(bindings.get(key));
    }

    public Optional<RuntimeProviderDescriptor> provider(ContractRef<ProviderId> provider) {
        return Optional.ofNullable(providers.get(provider));
    }

    public RuntimeBindingManifest manifest() {
        return manifest;
    }

    public ContentHash bindingManifestHash() {
        return manifest.bindingManifestHash();
    }

    public Collection<RuntimeBinding> bindingValues() {
        return bindings.values();
    }

    public boolean hasAuthorization(ContractRef<CapabilityId> capability) {
        return capability != null && authorizations.contains(capability);
    }
}
