package restudio.resync.flow.catalog;

import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingDescriptor;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderState;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@FunctionalInterface
public interface CatalogBindingProof {
    Optional<ContentHash> activeFingerprint(RuntimeBindingKey binding);

    default Optional<ContentHash> activeBindingManifestHash() {
        return Optional.empty();
    }

    default CatalogBindingProof capture() {
        return this;
    }

    default boolean proves(RuntimeOperationDescriptor requirement) {
        return activeFingerprint(requirement.key()).filter(requirement.executionFingerprint()::equals).isPresent();
    }

    static CatalogBindingProof unavailable() {
        return binding -> Optional.empty();
    }

    static CatalogBindingProof live(RuntimeBindingRegistry registry) {
        Objects.requireNonNull(registry, "Runtime Binding Registry Is Required");
        return new CatalogBindingProof() {
            private final AtomicReference<CatalogBindingProof> direct = new AtomicReference<>();

            @Override
            public Optional<ContentHash> activeFingerprint(RuntimeBindingKey binding) {
                return directProof().activeFingerprint(binding);
            }

            @Override
            public Optional<ContentHash> activeBindingManifestHash() {
                return directProof().activeBindingManifestHash();
            }

            @Override
            public CatalogBindingProof capture() {
                return fromSnapshot(registry.snapshot());
            }

            private CatalogBindingProof directProof() {
                CatalogBindingProof current = direct.get();
                if (current != null) {
                    return current;
                }
                CatalogBindingProof created = fromSnapshot(registry.snapshot());
                if (direct.compareAndSet(null, created)) {
                    return created;
                }
                return direct.get();
            }
        };
    }

    static CatalogBindingProof snapshot(RuntimeRegistrySnapshot snapshot) {
        return fromSnapshot(snapshot);
    }

    private static CatalogBindingProof fromSnapshot(RuntimeRegistrySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Runtime Registry Snapshot Is Required");
        return new CatalogBindingProof() {
            @Override
            public Optional<ContentHash> activeFingerprint(RuntimeBindingKey binding) {
                return snapshot.binding(binding)
                    .filter(RuntimeBinding::available)
                    .filter(value -> snapshot.provider(value.provider())
                        .filter(provider -> provider.state() == RuntimeProviderState.ACTIVE)
                        .isPresent())
                    .map(RuntimeBinding::descriptor)
                    .map(CatalogBindingProof::runtimeOperation)
                    .map(RuntimeOperationDescriptor::executionFingerprint);
            }

            @Override
            public Optional<ContentHash> activeBindingManifestHash() {
                return Optional.of(snapshot.bindingManifestHash());
            }
        };
    }

    private static RuntimeOperationDescriptor runtimeOperation(RuntimeBindingDescriptor descriptor) {
        return new RuntimeOperationDescriptor(descriptor.capability(), descriptor.operation(), descriptor.pins(),
            descriptor.semantics(), descriptor.unknown());
    }

    static CatalogBindingProof fixed(Map<RuntimeBindingKey, ContentHash> fingerprints) {
        Map<RuntimeBindingKey, ContentHash> values = Map.copyOf(fingerprints);
        return binding -> Optional.ofNullable(values.get(binding));
    }

    static CatalogBindingProof fixed(Map<RuntimeBindingKey, ContentHash> fingerprints, ContentHash bindingManifestHash) {
        Map<RuntimeBindingKey, ContentHash> values = Map.copyOf(fingerprints);
        ContentHash manifestHash = Objects.requireNonNull(bindingManifestHash, "Binding Manifest Hash Is Required");
        return new CatalogBindingProof() {
            @Override
            public Optional<ContentHash> activeFingerprint(RuntimeBindingKey binding) {
                return Optional.ofNullable(values.get(binding));
            }

            @Override
            public Optional<ContentHash> activeBindingManifestHash() {
                return Optional.of(manifestHash);
            }
        };
    }
}
