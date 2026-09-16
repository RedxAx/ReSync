package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;

import java.util.Map;
import java.util.Objects;

public record RuntimeBindingKey(
    ContractRef<CapabilityId> capability,
    ContractRef<OperationId> operation
) implements Comparable<RuntimeBindingKey> {
    public RuntimeBindingKey {
        capability = Objects.requireNonNull(capability, "Capability Is Required");
        operation = Objects.requireNonNull(operation, "Operation Is Required");
    }

    public String canonical() {
        return capability.canonicalText() + "#" + operation.canonicalText();
    }

    public String canonicalKey() {
        return canonical();
    }

    public Map<String, Object> canonicalValue() {
        return Map.of(
            "capability", capability.canonicalValue(),
            "operation", operation.canonicalValue());
    }

    @Override
    public int compareTo(RuntimeBindingKey other) {
        return canonical().compareTo(other.canonical());
    }
}
