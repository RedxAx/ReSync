package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ProviderId;

import java.util.Map;
import java.util.Objects;

public record RuntimeProviderDescriptor(
    ContractRef<ProviderId> provider,
    String version,
    RuntimeProviderState state,
    long drainDeadlineMillis,
    long hardDeadlineMillis,
    RuntimeSemantics.UnloadPolicy unloadPolicy,
    Map<String, Object> unknown
) {
    public RuntimeProviderDescriptor {
        provider = Objects.requireNonNull(provider, "Provider Is Required");
        version = Objects.requireNonNull(version, "Provider Version Is Required");
        state = Objects.requireNonNull(state, "Provider State Is Required");
        unloadPolicy = Objects.requireNonNull(unloadPolicy, "Unload Policy Is Required");
        unknown = RuntimeCanonicalSupport.unknown(unknown, "Provider Unknown Data");
        if (version.isEmpty() || version.length() > 128 || !version.equals(version.trim())) {
            throw new IllegalArgumentException("Provider Version Must Contain Between 1 And 128 Characters");
        }
        if (drainDeadlineMillis < 0 || hardDeadlineMillis < 0 || hardDeadlineMillis < drainDeadlineMillis) {
            throw new IllegalArgumentException("Provider Deadlines Are Invalid");
        }
    }

    public RuntimeProviderDescriptor(ContractRef<ProviderId> provider, String version, long drainDeadlineMillis, long hardDeadlineMillis, RuntimeSemantics.UnloadPolicy unloadPolicy) {
        this(provider, version, RuntimeProviderState.ACTIVE, drainDeadlineMillis, hardDeadlineMillis, unloadPolicy, Map.of());
    }

    public RuntimeProviderDescriptor(
        ContractRef<ProviderId> provider,
        String version,
        RuntimeProviderState state,
        long drainDeadlineMillis,
        long hardDeadlineMillis,
        RuntimeSemantics.UnloadPolicy unloadPolicy
    ) {
        this(provider, version, state, drainDeadlineMillis, hardDeadlineMillis, unloadPolicy, Map.of());
    }

    public RuntimeProviderDescriptor withState(RuntimeProviderState nextState) {
        return new RuntimeProviderDescriptor(provider, version, nextState, drainDeadlineMillis, hardDeadlineMillis, unloadPolicy, unknown);
    }

    public String canonical() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public static RuntimeProviderDescriptor fromCanonical(String value) {
        return RuntimeCanonicalDecoder.parse(value, RuntimeCanonicalDecoder::provider);
    }

    public static RuntimeProviderDescriptor fromCanonical(Map<String, ?> value) {
        return RuntimeCanonicalDecoder.provider(RuntimeCanonicalDecoder.object(value, "provider"));
    }

    public Map<String, Object> canonicalValue() {
        return RuntimeCanonicalSupport.merge(unknown, Map.of(
            "provider", provider.canonicalValue(),
            "version", version,
            "state", state.wireValue(),
            "drainDeadlineMillis", drainDeadlineMillis,
            "hardDeadlineMillis", hardDeadlineMillis,
            "unloadPolicy", unloadPolicy.wireValue()));
    }
}
