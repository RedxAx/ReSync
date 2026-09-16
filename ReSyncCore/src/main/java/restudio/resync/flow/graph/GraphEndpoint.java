package restudio.resync.flow.graph;

import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record GraphEndpoint(NodeInstanceId nodeId, PinId pinId, RepeatableElementId elementId, BranchId branchId, OpaqueData unknown) {
    public GraphEndpoint {
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        pinId = Objects.requireNonNull(pinId, "pinId");
        unknown = unknown != null ? unknown : OpaqueData.empty();
        unknown.rejectKnownFields("nodeId", "pinId", "elementId", "branchId");
    }

    public GraphEndpoint(NodeInstanceId nodeId, PinId pinId) {
        this(nodeId, pinId, null, null, OpaqueData.empty());
    }

    public GraphEndpoint(NodeInstanceId nodeId, PinId pinId, RepeatableElementId elementId, BranchId branchId) {
        this(nodeId, pinId, elementId, branchId, OpaqueData.empty());
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("nodeId", nodeId.canonicalText());
        values.put("pinId", pinId.canonicalText());
        if (elementId != null) {
            values.put("elementId", elementId.canonicalText());
        }
        if (branchId != null) {
            values.put("branchId", branchId.canonicalText());
        }
        return OpaqueData.mergeKnownFields(unknown, values);
    }
}
