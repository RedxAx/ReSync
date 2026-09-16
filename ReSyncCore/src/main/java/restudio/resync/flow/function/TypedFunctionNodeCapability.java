package restudio.resync.flow.function;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.runtime.RuntimeBindingKey;

public record TypedFunctionNodeCapability(
    NodeInstanceId nodeId,
    ContractRef<NodeId> definition,
    int definitionVersion,
    int order,
    ContractRef<CapabilityId> capability,
    ContractRef<OperationId> operation,
    ContentHash executionFingerprint,
    Set<ConnectionId> handledConnections,
    TypedFunctionNodeCompiler compiler
) {
    public TypedFunctionNodeCapability {
        nodeId = Objects.requireNonNull(nodeId, "Typed Function Node ID Is Required");
        definition = Objects.requireNonNull(definition, "Typed Function Node Definition Is Required");
        if (definitionVersion < 1) {
            throw new IllegalArgumentException("Typed Function Node Definition Version Must Be Positive");
        }
        if (order < 0) {
            throw new IllegalArgumentException("Typed Function Node Order Cannot Be Negative");
        }
        capability = Objects.requireNonNull(capability, "Typed Function Node Capability Is Required");
        operation = Objects.requireNonNull(operation, "Typed Function Node Operation Is Required");
        executionFingerprint = Objects.requireNonNull(executionFingerprint, "Typed Function Node Execution Fingerprint Is Required");
        handledConnections = handledConnections == null ? Set.of() : Set.copyOf(handledConnections);
        compiler = Objects.requireNonNull(compiler, "Typed Function Node Compiler Is Required");
    }

    public RuntimeBindingKey binding() {
        return new RuntimeBindingKey(capability, operation);
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("capability", capability.canonicalValue());
        value.put("definition", Map.of("ownerId", definition.owner().canonicalText(), "localId", definition.id().canonicalText()));
        value.put("definitionVersion", definitionVersion);
        value.put("executionFingerprint", executionFingerprint.canonicalText());
        value.put("handledConnections", handledConnections.stream().sorted().map(ConnectionId::canonicalText).toList());
        value.put("nodeId", nodeId.canonicalText());
        value.put("operation", operation.canonicalValue());
        value.put("order", order);
        return Map.copyOf(value);
    }
}
