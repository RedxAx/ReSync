package restudio.resync.flow;

public record FlowNodeAuditRecord(long timestamp, String executionId, String graphId, String nodeId, String nodeType,
                                  String playerId, String authorizationPolicy, String auditPolicy, String confirmationPolicy,
                                  boolean sensitive, boolean destructive, boolean allowed, String decisionCode) {
}
