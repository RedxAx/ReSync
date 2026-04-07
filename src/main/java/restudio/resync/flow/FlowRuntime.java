package restudio.resync.flow;

import org.bukkit.Bukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

public class FlowRuntime {
    private FlowGraph graph;
    private final Map<String, Object> nodeOutputs;
    private final Map<String, Object> localVariables;
    private final Map<String, Object> globalVariables;
    private final Map<String, Object> eventVariables;
    private final TypeAdapterRegistry typeAdapter;
    private final ThreadLocal<String> triggeredOutputPin = ThreadLocal.withInitial(() -> null);
    private final Set<String> evaluatingNodes = new HashSet<>();
    private final ThreadLocal<Set<String>> executingFlowNodes = ThreadLocal.withInitial(HashSet::new);
    
    private final Stack<Frame> callStack = new Stack<>();
    private boolean breakLoopRequested = false;
    private boolean continueLoopRequested = false;
    
    private static class Frame {
        final FlowGraph graph;
        final Map<String, Object> localVariables;
        final Map<String, Object> nodeOutputs;
        final String returnNodeId;
        
        Frame(FlowGraph graph, Map<String, Object> localVars, Map<String, Object> nodeOutputs, String returnNodeId) {
            this.graph = graph;
            this.localVariables = localVars;
            this.nodeOutputs = nodeOutputs;
            this.returnNodeId = returnNodeId;
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

        for (FlowConnection conn : graph.getConnections()) {
            if (conn.getTargetNodeId().equals(nodeId) && conn.getTargetPin().equals(pinName)) {
                String sourceKey = conn.getSourceNodeId() + ":" + conn.getSourcePin();
                return nodeOutputs.get(sourceKey);
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
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
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
        return nodeOutputs.containsKey(nodeId + ":" + pinName);
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
        Frame frame = new Frame(graph, localVariables, nodeOutputs, returnNodeId);
        callStack.push(frame);
    }

    public boolean returnFromFunction(Object returnValue) {
        if (callStack.isEmpty()) return false;
        
        Frame frame = callStack.pop();
        this.graph = frame.graph;
        this.localVariables.clear();
        this.localVariables.putAll(frame.localVariables);
        this.nodeOutputs.clear();
        this.nodeOutputs.putAll(frame.nodeOutputs);
        
        if (returnValue != null && frame.returnNodeId != null) {
            nodeOutputs.put(frame.returnNodeId + ":return", returnValue);
        }
        
        return true;
    }

    public int getCallDepth() {
        return callStack.size();
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
}
