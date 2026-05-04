package restudio.resync.flow.diagnostics;

public class FlowTraceRecord {
    private long sequence;
    private long timestamp;
    private String graphId;
    private String nodeId;
    private String nodeType;
    private String status;
    private long durationNanos;
    private String errorText;
    private int executionDepth;
    private String inputSummary;
    private String outputSummary;
    private String debugSessionId;
    private String eventType;
    private String sourceNodeId;
    private String sourcePin;
    private String targetNodeId;
    private String targetPin;
    private String reason;

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getGraphId() {
        return graphId;
    }

    public void setGraphId(String graphId) {
        this.graphId = graphId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getDurationNanos() {
        return durationNanos;
    }

    public void setDurationNanos(long durationNanos) {
        this.durationNanos = durationNanos;
    }

    public String getErrorText() {
        return errorText;
    }

    public void setErrorText(String errorText) {
        this.errorText = errorText;
    }

    public int getExecutionDepth() {
        return executionDepth;
    }

    public void setExecutionDepth(int executionDepth) {
        this.executionDepth = executionDepth;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public void setInputSummary(String inputSummary) {
        this.inputSummary = inputSummary;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public String getDebugSessionId() {
        return debugSessionId;
    }

    public void setDebugSessionId(String debugSessionId) {
        this.debugSessionId = debugSessionId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    public String getSourcePin() {
        return sourcePin;
    }

    public void setSourcePin(String sourcePin) {
        this.sourcePin = sourcePin;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(String targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    public String getTargetPin() {
        return targetPin;
    }

    public void setTargetPin(String targetPin) {
        this.targetPin = targetPin;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
