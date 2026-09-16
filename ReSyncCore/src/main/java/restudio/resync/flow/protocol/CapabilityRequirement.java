package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

import java.util.Objects;

public record CapabilityRequirement(ContractRef<CapabilityId> capability, int minimumVersion, boolean required) {
    public CapabilityRequirement {
        capability = Objects.requireNonNull(capability, "capability");
        if (minimumVersion < 1) {
            throw new IllegalArgumentException("Capability version must be positive");
        }
    }
}
