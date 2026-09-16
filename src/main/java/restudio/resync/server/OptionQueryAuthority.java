package restudio.resync.server;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface OptionQueryAuthority {
    OwnerId PROTOCOL_OWNER = OwnerId.of("restudio.resync");
    ContractRef<OperationId> OPERATION = ContractRef.of(PROTOCOL_OWNER, OperationId.of("option.query"));
    ContractRef<ResourceTypeId> PAGE_TYPE = ContractRef.of(PROTOCOL_OWNER, ResourceTypeId.of("option.page"));
    ContractRef<CapabilityId> PROTOCOL_CAPABILITY = ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY;

    Source require(ContractRef<InspectorFieldId> sourceRef, ContractRef<CapabilityId> query);

    static OptionQueryAuthority failClosed() {
        return (sourceRef, query) -> {
            throw new Rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "Authoritative option query sources are unavailable");
        };
    }

    record Source(ContractRef<InspectorFieldId> sourceRef, InspectorOptionSource descriptor, String providerSourceId,
                  long providerSourceEpoch, List<Diagnostic> diagnostics) {
        public Source {
            sourceRef = Objects.requireNonNull(sourceRef, "Option source reference is required");
            descriptor = Objects.requireNonNull(descriptor, "Option source descriptor is required");
            if (!sourceRef.id().equals(descriptor.id())) {
                throw new IllegalArgumentException("Option source reference does not match its descriptor");
            }
            providerSourceId = Objects.requireNonNull(providerSourceId, "Provider source ID is required").strip();
            if (providerSourceId.isBlank()) {
                throw new IllegalArgumentException("Provider source ID is required");
            }
            if (providerSourceEpoch < 1L) {
                throw new IllegalArgumentException("Provider source epoch must be positive");
            }
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public ContractRef<CapabilityId> query() {
            return descriptor.capability();
        }

        public long revision() {
            return providerSourceEpoch;
        }

        public String invalidationKey() {
            return descriptor.invalidationKey();
        }
    }

    final class Rejected extends IllegalArgumentException {
        private final ProtocolRejectionCode code;

        public Rejected(ProtocolRejectionCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "Option query rejection code is required");
        }

        public Rejected(ProtocolRejectionCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "Option query rejection code is required");
        }

        public ProtocolRejectionCode code() {
            return code;
        }
    }
}
