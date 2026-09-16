package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.ProviderId;

import java.util.Objects;

public final class ReplacementRuntimeProviderAuthority {
    private static final ContractRef<ProviderId> UNAVAILABLE_PROVIDER = ContractRef.of(
        new OwnerId("restudio.resync"),
        new ProviderId("replacement-runtime-authority"));
    private final ReplacementRuntimeProviderRegistry registry;

    private ReplacementRuntimeProviderAuthority(ReplacementRuntimeProviderRegistry registry) {
        this.registry = registry;
    }

    public static ReplacementRuntimeProviderAuthority unavailable() {
        return new ReplacementRuntimeProviderAuthority(null);
    }

    public static ReplacementRuntimeProviderAuthority of(ReplacementRuntimeProviderRegistry registry) {
        return new ReplacementRuntimeProviderAuthority(Objects.requireNonNull(registry, "Replacement Runtime Provider Registry Is Required"));
    }

    public boolean configured() {
        return registry != null;
    }

    public ReplacementRuntimeProviderRegistry.Snapshot snapshot() {
        return registryOrThrow().snapshot();
    }

    public ReplacementRuntimeProviderRegistry.ResolvedOperation resolve(RuntimeOperationDescriptor descriptor) {
        return registryOrThrow().resolve(descriptor);
    }

    public ReplacementRuntimeProviderRegistry.ResolvedOperation resolve(
        ContractRef<CapabilityId> capability,
        ContractRef<OperationId> operation,
        RuntimeOperationDescriptor descriptor,
        ContentHash fingerprint
    ) {
        return registryOrThrow().resolve(capability, operation, descriptor, fingerprint);
    }

    private ReplacementRuntimeProviderRegistry registryOrThrow() {
        if (registry == null) {
            throw new RuntimeCapabilityUnavailableException(
                UNAVAILABLE_PROVIDER,
                "replacement runtime provider authority is unavailable");
        }
        return registry;
    }
}
