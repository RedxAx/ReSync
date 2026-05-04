package restudio.resync.flow.diagnostics;

import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowRuntime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FlowDebugService {
    private static final long AUTO_RESUME_SECONDS = 60L;
    private final FlowTraceService traceService;
    private final Map<String, FlowDebugSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> breakpointsByGraph = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ReSyncFlow-Debugger");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean enabled;
    private volatile boolean pauseAll;

    public FlowDebugService(FlowTraceService traceService) {
        this.traceService = traceService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (traceService != null) {
            traceService.setEnabled(enabled);
        }
        if (!enabled) {
            pauseAll = false;
            for (FlowDebugSession session : sessions.values()) {
                resume(session.getSessionId(), "Disabled", "");
            }
        }
        emitState("state", enabled ? "Enabled" : "Disabled");
    }

    public void setBreakpoints(String graphId, Set<String> nodeIds) {
        if (graphId == null || graphId.isBlank()) {
            return;
        }
        breakpointsByGraph.put(graphId, nodeIds != null ? new HashSet<>(nodeIds) : new HashSet<>());
        emitState("breakpoints", graphId);
    }

    public Set<String> getBreakpoints(String graphId) {
        return Set.copyOf(breakpointsByGraph.getOrDefault(graphId, Set.of()));
    }

    public void pauseAll() {
        pauseAll = true;
        emitState("state", "Pause Requested");
    }

    public void resume(String sessionId, String reason, String nextStepMode) {
        FlowDebugSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        session.setStepMode(nextStepMode);
        session.setStepDepth(session.getCurrentDepth());
        completePause(session, "resumed", reason != null ? reason : "Resumed");
    }

    public void resumeAll(String reason) {
        pauseAll = false;
        for (FlowDebugSession session : sessions.values()) {
            resume(session.getSessionId(), reason, "");
        }
    }

    public void stop(String sessionId) {
        FlowDebugSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        session.setStopRequested(true);
        completePause(session, "stopped", "Stopped");
    }

    public void clear() {
        if (traceService != null) {
            traceService.clear();
        }
        emitState("cleared", "");
    }

    public CompletableFuture<Void> beforeNode(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId, int depth, String inputSummary) {
        if (!enabled || runtime == null || graph == null || nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }

        FlowDebugSession session = sessionFor(runtime, graph);
        session.setCurrentGraphId(graph.getId());
        session.setCurrentNodeId(nodeId);
        session.setCurrentNodeType(node != null ? node.getType() : "");
        session.setCurrentDepth(depth);
        session.setStatus("running");
        emitNode(session, graph, node, nodeId, depth, "nodeEntered", "running", "", inputSummary, "");

        if (session.isStopRequested()) {
            return CompletableFuture.failedFuture(new FlowDebugStoppedException(nodeId));
        }

        String reason = pauseReason(session, graph, nodeId, depth);
        if (reason.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> pause = new CompletableFuture<>();
        session.setPauseFuture(pause);
        session.setStatus("paused");
        session.setReason(reason);
        emitNode(session, graph, node, nodeId, depth, "paused", "paused", reason, inputSummary, "");
        session.setAutoResumeTask(scheduler.schedule(() -> completePause(session, "autoResumed", "Auto Resumed"), AUTO_RESUME_SECONDS, TimeUnit.SECONDS));
        return pause.thenCompose(ignored -> session.isStopRequested() ? CompletableFuture.failedFuture(new FlowDebugStoppedException(nodeId)) : CompletableFuture.completedFuture(null));
    }

    public void afterNode(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId, int depth, String status, String reason, String inputSummary, String outputSummary) {
        if (!enabled || runtime == null || graph == null || nodeId == null) {
            return;
        }
        FlowDebugSession session = sessionFor(runtime, graph);
        emitNode(session, graph, node, nodeId, depth, "nodeExited", status, reason, inputSummary, outputSummary);
    }

    public void connectionTraversed(FlowRuntime runtime, FlowGraph graph, FlowConnection connection, int depth) {
        if (!enabled || runtime == null || graph == null || connection == null) {
            return;
        }
        FlowDebugSession session = sessionFor(runtime, graph);
        FlowTraceRecord record = baseRecord(session, graph, null, null, depth, "connection", "running", "");
        record.setSourceNodeId(connection.getSourceNodeId());
        record.setSourcePin(connection.getSourcePin());
        record.setTargetNodeId(connection.getTargetNodeId());
        record.setTargetPin(connection.getTargetPin());
        trace(record);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "snapshot");
        snapshot.put("enabled", enabled);
        snapshot.put("pauseAll", pauseAll);
        snapshot.put("breakpoints", breakpointsByGraph);
        List<Map<String, Object>> sessionData = new ArrayList<>();
        for (FlowDebugSession session : sessions.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", session.getSessionId());
            item.put("graphId", session.getGraphId());
            item.put("currentGraphId", session.getCurrentGraphId());
            item.put("currentNodeId", session.getCurrentNodeId());
            item.put("currentNodeType", session.getCurrentNodeType());
            item.put("status", session.getStatus());
            item.put("reason", session.getReason());
            item.put("depth", session.getCurrentDepth());
            item.put("updatedAt", session.getUpdatedAt());
            sessionData.add(item);
        }
        snapshot.put("sessions", sessionData);
        return snapshot;
    }

    private FlowDebugSession sessionFor(FlowRuntime runtime, FlowGraph graph) {
        String sessionId = runtime.getDebugSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
            runtime.setDebugSessionId(sessionId);
        }
        String graphId = graph != null && graph.getId() != null ? graph.getId() : "";
        String id = sessionId;
        return sessions.computeIfAbsent(id, ignored -> new FlowDebugSession(id, graphId));
    }

    private String pauseReason(FlowDebugSession session, FlowGraph graph, String nodeId, int depth) {
        String graphId = graph != null ? graph.getId() : "";
        if (pauseAll) {
            return "Paused";
        }
        if (breakpointsByGraph.getOrDefault(graphId, Set.of()).contains(nodeId)) {
            return "Breakpoint";
        }
        return switch (session.getStepMode()) {
            case "into" -> "Step";
            case "over" -> depth <= session.getStepDepth() ? "Step" : "";
            case "out" -> depth < session.getStepDepth() ? "Step" : "";
            default -> "";
        };
    }

    private void completePause(FlowDebugSession session, String eventType, String reason) {
        if (session == null) {
            return;
        }
        var task = session.getAutoResumeTask();
        if (task != null) {
            task.cancel(false);
            session.setAutoResumeTask(null);
        }
        CompletableFuture<Void> pause = session.getPauseFuture();
        session.setPauseFuture(null);
        session.setStatus("running");
        session.setReason(reason);
        emitSession(session, eventType, reason);
        if (pause != null && !pause.isDone()) {
            pause.complete(null);
        }
    }

    private void emitNode(FlowDebugSession session, FlowGraph graph, FlowNode node, String nodeId, int depth, String eventType, String status, String reason, String inputSummary, String outputSummary) {
        FlowTraceRecord record = baseRecord(session, graph, node, nodeId, depth, eventType, status, reason);
        record.setInputSummary(inputSummary != null ? inputSummary : "");
        record.setOutputSummary(outputSummary != null ? outputSummary : "");
        trace(record);
    }

    private void emitSession(FlowDebugSession session, String eventType, String reason) {
        FlowTraceRecord record = new FlowTraceRecord();
        record.setDebugSessionId(session.getSessionId());
        record.setGraphId(session.getCurrentGraphId());
        record.setNodeId(session.getCurrentNodeId());
        record.setNodeType(session.getCurrentNodeType());
        record.setExecutionDepth(session.getCurrentDepth());
        record.setEventType(eventType);
        record.setStatus(session.getStatus());
        record.setReason(reason);
        trace(record);
    }

    private void emitState(String eventType, String reason) {
        FlowTraceRecord record = new FlowTraceRecord();
        record.setEventType(eventType);
        record.setStatus(enabled ? "enabled" : "disabled");
        record.setReason(reason);
        trace(record);
    }

    private FlowTraceRecord baseRecord(FlowDebugSession session, FlowGraph graph, FlowNode node, String nodeId, int depth, String eventType, String status, String reason) {
        FlowTraceRecord record = new FlowTraceRecord();
        record.setDebugSessionId(session.getSessionId());
        record.setGraphId(graph != null ? graph.getId() : "");
        record.setNodeId(nodeId != null ? nodeId : "");
        record.setNodeType(node != null ? node.getType() : "");
        record.setExecutionDepth(depth);
        record.setEventType(eventType);
        record.setStatus(status);
        record.setReason(reason != null ? reason : "");
        return record;
    }

    private void trace(FlowTraceRecord record) {
        if (traceService != null) {
            traceService.record(record);
        }
    }

    public static class FlowDebugStoppedException extends RuntimeException {
        private final String nodeId;

        public FlowDebugStoppedException(String nodeId) {
            super("Flow debug session stopped");
            this.nodeId = nodeId;
        }

        public String getNodeId() {
            return nodeId;
        }
    }
}
