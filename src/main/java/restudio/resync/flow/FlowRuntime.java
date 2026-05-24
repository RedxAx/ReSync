package restudio.resync.flow;

import org.bukkit.Bukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FlowRuntime {
    private static final String PASSTHROUGH_OUTPUT_PREFIX = "__passthrough:";
    private FlowGraph graph;
    private final Map<String, Object> nodeOutputs;
    private final Map<String, Object> localVariables;
    private final Map<String, Object> globalVariables;
    private final Map<String, Object> eventVariables;
    private final TypeAdapterRegistry typeAdapter;
    private final ThreadLocal<String> triggeredOutputPin = ThreadLocal.withInitial(() -> null);
    private final Set<String> evaluatingNodes = new HashSet<>();
    private final ThreadLocal<Set<String>> executingFlowNodes = ThreadLocal.withInitial(HashSet::new);
    private final Map<String, Object> functionInputs = new HashMap<>();

    private final Deque<Frame> callStack = new ArrayDeque<>();
    private boolean breakLoopRequested = false;
    private boolean continueLoopRequested = false;
    private boolean functionReturnRequested = false;
    private String returnedCallerNodeId;
    private String debugSessionId;

    private static class Frame {
        final FlowGraph graph;
        final Map<String, Object> localVariables;
        final Map<String, Object> nodeOutputs;
        final String callerNodeId;

        Frame(FlowGraph graph, Map<String, Object> localVars, Map<String, Object> nodeOutputs, String callerNodeId) {
            this.graph = graph;
            this.localVariables = localVars;
            this.nodeOutputs = nodeOutputs;
            this.callerNodeId = callerNodeId;
        }
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(graph, typeAdapter, globalVariables, new HashMap<>());
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, Map<String, Object> eventVariables) {
        this.graph = graph;
        this.nodeOutputs = new HashMap<>();
        this.localVariables = new HashMap<>();
        this.globalVariables = globalVariables != null ? globalVariables : new HashMap<>();
        this.eventVariables = eventVariables != null ? eventVariables : new HashMap<>();
        this.typeAdapter = typeAdapter;

        if (graph.getLocalVariables() != null) {
            graph.getLocalVariables().forEach(var -> localVariables.put(var.getName(), var.getInitialValue()));
        }

        initializeServerGlobals();
    }

    private void initializeServerGlobals() {
        globalVariables.put("server.name", Bukkit.getServer().getName());
        globalVariables.put("server.version", Bukkit.getVersion());
        globalVariables.put("server.bukkit_version", Bukkit.getBukkitVersion());
        globalVariables.put("server.port", Bukkit.getServer().getPort());
    }

    public void triggerOutput(String pinName) {
        triggeredOutputPin.set(pinName);
    }

    public String consumeTriggeredOutput() {
        String pin = triggeredOutputPin.get();
        triggeredOutputPin.set(null);
        return pin;
    }

    public String getTriggeredOutputPin() {
        return triggeredOutputPin.get();
    }

    public void setTriggeredOutputPin(String pin) {
        triggeredOutputPin.set(pin);
    }

    public Object resolveInput(FlowNode node, String pinName, Class<?> expectedType) {
        Object rawValue = resolveInputRaw(node, pinName);
        if (rawValue == null) return null;

        return typeAdapter.adapt(rawValue, expectedType);
    }

    public Object resolveInput(FlowNode node, String pinName) {
        return resolveInputRaw(node, pinName);
    }

    private Object resolveInputRaw(FlowNode node, String pinName) {
        String nodeId = findNodeId(node);
        if (nodeId == null) return null;

        for (FlowConnection conn : graph.getConnectionsToTarget(nodeId)) {
            if (conn.getTargetPin().equals(pinName)) {
                return getNodeOutput(conn.getSourceNodeId(), conn.getSourcePin());
            }
        }

        if (node.getInputValues() != null && node.getInputValues().containsKey(pinName)) {
            return node.getInputValues().get(pinName);
        }

        return null;
    }

    public Object getVariable(String name) {
        if (name.startsWith("event.")) {
            return eventVariables.get(name);
        }
        if (name.startsWith("server.")) {
            return globalVariables.get(name);
        }
        return localVariables.get(name);
    }

    public void setVariable(String name, Object value) {
        if (name.startsWith("server.")) {
            globalVariables.put(name, value);
        } else {
            localVariables.put(name, value);
        }
    }

    public <T> T getVariable(String name, Class<T> type) {
        Object value = getVariable(name);
        if (value == null) return null;
        return typeAdapter.adapt(value, type);
    }

    public <T> T getVariable(String name, Class<T> type, T defaultValue) {
        T value = getVariable(name, type);
        return value != null ? value : defaultValue;
    }

    public String findNodeId(FlowNode node) {
        return graph != null ? graph.findNodeId(node) : null;
    }

    public void setNodeOutput(String nodeId, String pinName, Object value) {
        nodeOutputs.put(nodeId + ":" + pinName, value);
    }

    public void clearNodeOutputs(String nodeId) {
        if (nodeId == null) {
            return;
        }
        String prefix = nodeId + ":";
        nodeOutputs.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public boolean hasNodeOutput(String nodeId, String pinName) {
        if (isPassthroughOutputPin(pinName)) {
            return nodeOutputs.containsKey(nodeId + ":" + pinName) || hasPassthroughInput(nodeId, passthroughInputPin(pinName));
        }
        return nodeOutputs.containsKey(nodeId + ":" + pinName);
    }

    public Object getNodeOutput(String nodeId, String pinName) {
        if (isPassthroughOutputPin(pinName)) {
            String key = nodeId + ":" + pinName;
            if (nodeOutputs.containsKey(key)) {
                return nodeOutputs.get(key);
            }
            FlowNode node = graph != null && graph.getNodes() != null ? graph.getNodes().get(nodeId) : null;
            return node != null ? resolveInputRaw(node, passthroughInputPin(pinName)) : null;
        }
        return nodeOutputs.get(nodeId + ":" + pinName);
    }

    private boolean hasPassthroughInput(String nodeId, String inputPin) {
        FlowNode node = graph != null && graph.getNodes() != null ? graph.getNodes().get(nodeId) : null;
        if (node == null || inputPin == null || inputPin.isBlank()) {
            return false;
        }
        for (FlowConnection conn : graph.getConnectionsToTarget(nodeId)) {
            if (inputPin.equals(conn.getTargetPin())) {
                return true;
            }
        }
        return node.getInputValues() != null && node.getInputValues().containsKey(inputPin);
    }

    private boolean isPassthroughOutputPin(String pinName) {
        return pinName != null && pinName.startsWith(PASSTHROUGH_OUTPUT_PREFIX);
    }

    private String passthroughInputPin(String outputPin) {
        return isPassthroughOutputPin(outputPin) ? outputPin.substring(PASSTHROUGH_OUTPUT_PREFIX.length()) : outputPin;
    }

    public boolean isEvaluating(String nodeId) {
        return evaluatingNodes.contains(nodeId);
    }

    public void beginEvaluating(String nodeId) {
        evaluatingNodes.add(nodeId);
    }

    public void endEvaluating(String nodeId) {
        evaluatingNodes.remove(nodeId);
    }

    public boolean beginFlowExecution(String nodeId) {
        return executingFlowNodes.get().add(nodeId);
    }

    public void endFlowExecution(String nodeId) {
        executingFlowNodes.get().remove(nodeId);
    }

    public Map<String, Object> getLocalVariables() {
        return localVariables;
    }

    public Map<String, Object> getGlobalVariables() {
        return globalVariables;
    }

    public Map<String, Object> getEventVariables() {
        return eventVariables;
    }

    public TypeAdapterRegistry getTypeAdapter() {
        return typeAdapter;
    }

    public FlowGraph getGraph() {
        return graph;
    }

    public void setGraph(FlowGraph graph) {
        this.graph = graph;
    }

    public void callFunction(FlowGraph functionGraph, String returnNodeId) {
        callFunction(functionGraph, returnNodeId, Collections.emptyMap());
    }

    public void callFunction(FlowGraph functionGraph, String callerNodeId, Map<String, Object> inputs) {
        Frame frame = new Frame(graph, new HashMap<>(localVariables), new HashMap<>(nodeOutputs), callerNodeId);
        callStack.push(frame);

        this.graph = functionGraph;
        this.localVariables.clear();
        this.nodeOutputs.clear();
        this.functionInputs.clear();

        if (functionGraph != null && functionGraph.getLocalVariables() != null) {
            functionGraph.getLocalVariables().forEach(var -> localVariables.put(var.getName(), var.getInitialValue()));
        }

        if (inputs != null) {
            this.functionInputs.putAll(inputs);
            this.localVariables.putAll(inputs);
        }
    }

    public boolean returnFromFunction(Object returnValue) {
        Map<String, Object> returnValues = new HashMap<>();
        returnValues.put("return", returnValue);
        return returnFromFunction(returnValues);
    }

    public boolean returnFromFunction(Map<String, Object> returnValues) {
        if (callStack.isEmpty()) return false;

        Frame frame = callStack.pop();
        this.graph = frame.graph;
        this.localVariables.clear();
        this.localVariables.putAll(frame.localVariables);
        this.nodeOutputs.clear();
        this.nodeOutputs.putAll(frame.nodeOutputs);

        if (frame.callerNodeId != null && returnValues != null) {
            for (Map.Entry<String, Object> entry : returnValues.entrySet()) {
                if (entry.getKey() != null) {
                    nodeOutputs.put(frame.callerNodeId + ":" + entry.getKey(), entry.getValue());
                }
            }
        }

        functionInputs.clear();
        functionReturnRequested = true;
        returnedCallerNodeId = frame.callerNodeId;

        return true;
    }

    public int getCallDepth() {
        return callStack.size();
    }

    public String getDebugSessionId() {
        return debugSessionId;
    }

    public void setDebugSessionId(String debugSessionId) {
        this.debugSessionId = debugSessionId;
    }

    public Object getFunctionInput(String name) {
        return functionInputs.get(name);
    }

    public Map<String, Object> getFunctionInputs() {
        return functionInputs;
    }

    public boolean consumeFunctionReturnRequested() {
        boolean requested = functionReturnRequested;
        functionReturnRequested = false;
        return requested;
    }

    public String consumeReturnedCallerNodeId() {
        String callerNodeId = returnedCallerNodeId;
        returnedCallerNodeId = null;
        return callerNodeId;
    }

    public String findFunctionStartNodeId() {
        if (graph == null || graph.getNodes() == null) {
            return null;
        }
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            if (entry.getValue() != null && isFunctionStartType(entry.getValue().getType())) {
                return entry.getKey();
            }
        }
        List<Map.Entry<String, FlowNode>> entries = new ArrayList<>(graph.getNodes().entrySet());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        return entries.isEmpty() ? null : entries.getFirst().getKey();
    }

    private boolean isFunctionStartType(String type) {
        return "function_start".equals(type) || "function.start".equals(type) || "function.function_start".equals(type);
    }
    
    public void setBreakLoopRequested(boolean requested) {
        this.breakLoopRequested = requested;
    }
    
    public boolean isBreakLoopRequested() {
        return breakLoopRequested;
    }
    
    public void setContinueLoopRequested(boolean requested) {
        this.continueLoopRequested = requested;
    }
    
    public boolean isContinueLoopRequested() {
        return continueLoopRequested;
    }
    
    public void resetLoopControl() {
        this.breakLoopRequested = false;
        this.continueLoopRequested = false;
    }

    public void cleanupThreadLocals() {
        triggeredOutputPin.remove();
        executingFlowNodes.remove();
    }
}
