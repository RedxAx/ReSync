package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RuntimeCapabilityDescriptor(
    ContractRef<CapabilityId> capability,
    List<RuntimeOperationDescriptor> operations,
    Map<String, Object> unknown
) {
    public RuntimeCapabilityDescriptor {
        capability = Objects.requireNonNull(capability, "Capability Is Required");
        Objects.requireNonNull(operations, "Operations Are Required");
        ArrayList<RuntimeOperationDescriptor> sorted = new ArrayList<>(operations.stream()
            .map(operation -> Objects.requireNonNull(operation, "Operations Cannot Contain Null")).toList());
        sorted.sort((left, right) -> left.key().compareTo(right.key()));
        operations = List.copyOf(sorted);
        unknown = RuntimeCanonicalSupport.unknown(unknown, "Capability Unknown Data");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("Capability Requires At Least One Operation");
        }
        long distinct = operations.stream().map(RuntimeOperationDescriptor::key).distinct().count();
        if (distinct != operations.size()) {
            throw new IllegalArgumentException("Capability Contains Duplicate Operations");
        }
        ContractRef<CapabilityId> capabilityRef = capability;
        if (operations.stream().anyMatch(operation -> !capabilityRef.equals(operation.capability()))) {
            throw new IllegalArgumentException("Operation Capability Does Not Match Descriptor Capability");
        }
    }

    public RuntimeCapabilityDescriptor(ContractRef<CapabilityId> capability, List<RuntimeOperationDescriptor> operations) {
        this(capability, operations, Map.of());
    }

    public String canonical() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public static RuntimeCapabilityDescriptor fromCanonical(String value) {
        return RuntimeCanonicalDecoder.parse(value, RuntimeCanonicalDecoder::capability);
    }

    public static RuntimeCapabilityDescriptor fromCanonical(Map<String, ?> value) {
        return RuntimeCanonicalDecoder.capability(RuntimeCanonicalDecoder.object(value, "capability"));
    }

    public Map<String, Object> canonicalValue() {
        return RuntimeCanonicalSupport.merge(unknown, Map.of(
            "capability", capability.canonicalValue(),
            "operations", operations.stream().map(RuntimeOperationDescriptor::canonicalValue).toList()));
    }
}
