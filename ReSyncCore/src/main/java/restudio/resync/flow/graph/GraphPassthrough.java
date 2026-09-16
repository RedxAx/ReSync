package restudio.resync.flow.graph;

import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.PinId;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record GraphPassthrough(NodeInstanceId nodeId, PinId inputPin, List<ConnectionId> connectionIds,
                               OpaqueData unknown) {
    public GraphPassthrough {
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        inputPin = Objects.requireNonNull(inputPin, "inputPin");
        connectionIds = connectionIds == null ? List.of() : List.copyOf(connectionIds);
        Set<ConnectionId> identities = new HashSet<>();
        connectionIds.forEach(connection -> {
            Objects.requireNonNull(connection, "passthrough connection ID");
            if (!identities.add(connection)) {
                throw new IllegalArgumentException("Duplicate passthrough connection ID: " + connection);
            }
        });
        unknown = unknown != null ? unknown : OpaqueData.empty();
        unknown.rejectKnownFields("nodeId", "inputPin", "connectionIds");
    }

    public GraphPassthrough(NodeInstanceId nodeId, PinId inputPin) {
        this(nodeId, inputPin, List.of(), OpaqueData.empty());
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("nodeId", nodeId.canonicalText());
        values.put("inputPin", inputPin.canonicalText());
        if (!connectionIds.isEmpty()) {
            values.put("connectionIds", connectionIds.stream().sorted().map(ConnectionId::canonicalText).toList());
        }
        return OpaqueData.mergeKnownFields(unknown, values);
    }
}
