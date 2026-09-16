package restudio.resync.flow.graph;

import restudio.resync.flow.identity.ConnectionId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record GraphConnection(ConnectionId connectionId, GraphEndpoint source, GraphEndpoint target, OpaqueData unknown) {
    public GraphConnection {
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        source = Objects.requireNonNull(source, "source");
        target = Objects.requireNonNull(target, "target");
        unknown = unknown != null ? unknown : OpaqueData.empty();
        unknown.rejectKnownFields("connectionId", "source", "target");
    }

    public GraphConnection(ConnectionId connectionId, GraphEndpoint source, GraphEndpoint target) {
        this(connectionId, source, target, OpaqueData.empty());
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("connectionId", connectionId.canonicalText());
        values.put("source", source.canonicalValue());
        values.put("target", target.canonicalValue());
        return OpaqueData.mergeKnownFields(unknown, values);
    }
}
