package restudio.resync.modules.flow;

import java.util.Map;

public record FlowResourceAuditRecord(long timestamp, String operation, String resourceType, String resourceId, String source,
                                      String flowId, String nodeId, String actor, boolean success, String errorCode,
                                      Map<String, Object> details) {
    public FlowResourceAuditRecord {
        operation = operation != null ? operation : "";
        resourceType = resourceType != null ? resourceType : "";
        resourceId = resourceId != null ? resourceId : "";
        source = source != null ? source : "";
        flowId = flowId != null ? flowId : "";
        nodeId = nodeId != null ? nodeId : "";
        actor = actor != null ? actor : "";
        errorCode = errorCode != null ? errorCode : "";
        details = details != null ? Map.copyOf(details) : Map.of();
    }
}
