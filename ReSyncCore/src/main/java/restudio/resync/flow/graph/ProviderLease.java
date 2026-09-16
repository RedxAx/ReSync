package restudio.resync.flow.graph;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.LeaseId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.runtime.RuntimeSemantics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ProviderLease(
    LeaseId leaseId,
    ContractRef<ProviderId> provider,
    ContractRef<CapabilityId> capability,
    ContractRef<OperationId> operation,
    ContentHash bindingFingerprint,
    long drainDeadlineMillis,
    long hardDeadlineMillis,
    RuntimeSemantics.UnloadPolicy unloadPolicy
) {
    public ProviderLease {
        leaseId = Objects.requireNonNull(leaseId, "leaseId");
        provider = Objects.requireNonNull(provider, "provider");
        capability = Objects.requireNonNull(capability, "capability");
        operation = Objects.requireNonNull(operation, "operation");
        bindingFingerprint = Objects.requireNonNull(bindingFingerprint, "bindingFingerprint");
        unloadPolicy = Objects.requireNonNull(unloadPolicy, "unloadPolicy");
        if (drainDeadlineMillis < 0 || hardDeadlineMillis < drainDeadlineMillis) {
            throw new IllegalArgumentException("Provider lease deadlines are invalid");
        }
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    Map<String, Object> canonicalValue() {
        var value = new LinkedHashMap<String, Object>();
        value.put("leaseId", leaseId.canonicalText());
        value.put("provider", provider.canonicalText());
        value.put("capability", capability.canonicalText());
        value.put("operation", operation.canonicalText());
        value.put("bindingFingerprint", bindingFingerprint.canonicalText());
        value.put("drainDeadlineMillis", drainDeadlineMillis);
        value.put("hardDeadlineMillis", hardDeadlineMillis);
        value.put("unloadPolicy", unloadPolicy.wireValue());
        return value;
    }
}
