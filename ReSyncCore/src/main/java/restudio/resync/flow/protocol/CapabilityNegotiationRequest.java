package restudio.resync.flow.protocol;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;

import java.util.Set;

public record CapabilityNegotiationRequest(CatalogVersion minimumVersion, CatalogVersion maximumVersion,
                                           Set<CapabilityRequirement> capabilities, ContentHash catalogChecksum,
                                           ContentHash bindingManifestHash) {
    public CapabilityNegotiationRequest {
        minimumVersion = java.util.Objects.requireNonNull(minimumVersion, "minimumVersion");
        maximumVersion = java.util.Objects.requireNonNull(maximumVersion, "maximumVersion");
        if (minimumVersion.compareTo(maximumVersion) > 0) {
            throw new IllegalArgumentException("Minimum contract version must not exceed maximum version");
        }
        capabilities = ProtocolValues.set(capabilities, "capabilities");
    }

    public Set<ContractRef<CapabilityId>> capabilityIds() {
        return capabilities.stream().map(CapabilityRequirement::capability).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
