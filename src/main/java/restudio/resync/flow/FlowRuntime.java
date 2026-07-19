package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.migration.IdCompatibilityLayer;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FlowRuntime {
    private static final String PASSTHROUGH_OUTPUT_PREFIX = "__passthrough:";
    private FlowGraph graph;
    private final Map<String, Object> nodeOutputs;
    private final Map<String, Object> localVariables;
    private final Map<String, Object> globalVariables;
    private final Map<String, Object> eventVariables;
    private final TypeAdapterRegistry typeAdapter;
    private final NodeDefinitionRegistry nodeDefinitions;
    private final IdCompatibilityLayer compatibility = new IdCompatibilityLayer();
    private final AtomicReference<String> triggeredOutputPin = new AtomicReference<>();
    private final ThreadLocal<Set<String>> resolvingPassthroughOutputs = ThreadLocal.withInitial(HashSet::new);
    private final Set<String> evaluatingNodes = ConcurrentHashMap.newKeySet();
    private final Set<String> executingFlowNodes = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> functionInputs = new HashMap<>();
    private final ExecutionAuthority executionAuthority;

    private final Deque<Frame> callStack = new ArrayDeque<>();
    private final Deque<LoopControl> loopControls = new ArrayDeque<>();
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

    private static final class LoopControl {
        private boolean breakRequested;
        private boolean continueRequested;
    }

    private static final class ExecutionAuthority {
        private final AtomicInteger operations = new AtomicInteger();
        private final AtomicBoolean eventMutationOpen = new AtomicBoolean();
        private final long startedAtNanos = System.nanoTime();
        private final String executionId = UUID.randomUUID().toString();
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(graph, typeAdapter, globalVariables, new HashMap<>(), null);
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, Map<String, Object> eventVariables) {
        this(graph, typeAdapter, globalVariables, eventVariables, null);
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, Map<String, Object> eventVariables,
                       NodeDefinitionRegistry nodeDefinitions) {
        this(graph, typeAdapter, globalVariables, eventVariables, nodeDefinitions, new ExecutionAuthority());
    }

    private FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, Map<String, Object> eventVariables,
                        NodeDefinitionRegistry nodeDefinitions, ExecutionAuthority executionAuthority) {
        this.graph = graph;
        this.nodeOutputs = new HashMap<>();
        this.localVariables = new HashMap<>();
        this.globalVariables = concurrentVariables(globalVariables);
        this.eventVariables = eventVariables != null ? eventVariables : new HashMap<>();
        this.typeAdapter = typeAdapter;
        this.nodeDefinitions = nodeDefinitions;
        this.executionAuthority = executionAuthority;

        if (graph.getLocalVariables() != null) {
            graph.getLocalVariables().forEach(var -> localVariables.put(var.getName(), var.getInitialValue()));
        }

        initializeServerGlobals();
    }

    private Map<String, Object> concurrentVariables(Map<String, Object> variables) {
        if (variables instanceof ConcurrentMap<?, ?>) {
            return variables;
        }
        Map<String, Object> concurrent = new ConcurrentHashMap<>();
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (key != null && value != null) {
                    concurrent.put(key, value);
                }
            });
        }
        return concurrent;
    }

    public FlowRuntime createSubRuntime(FlowGraph subGraph) {
        return new FlowRuntime(subGraph, typeAdapter, globalVariables, eventVariables, nodeDefinitions, executionAuthority);
    }

    private void initializeServerGlobals() {
        Server server = Bukkit.getServer();
        if (server == null) {
            return;
        }
        globalVariables.put("server.name", server.getName());
        globalVariables.put("server.version", Bukkit.getVersion());
        globalVariables.put("server.bukkit_version", Bukkit.getBukkitVersion());
        globalVariables.put("server.port", server.getPort());
    }

    public void triggerOutput(String pinName) {
        triggeredOutputPin.set(pinName);
    }

    public String consumeTriggeredOutput() {
        return triggeredOutputPin.getAndSet(null);
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
        return resolveInputRaw(node, pinName, new HashSet<>());
    }

    private Object resolveInputRaw(FlowNode node, String pinName, Set<String> resolvingTemplates) {
        String nodeId = findNodeId(node);
        if (nodeId != null) {
            for (FlowConnection conn : graph.getConnectionsToTarget(nodeId)) {
                if (conn.getTargetPin().equals(pinName)) {
                    if (conn.getEditorSourceNodeId() != null && !conn.getEditorSourceNodeId().isBlank() && isPassthroughOutputPin(conn.getEditorSourcePin())) {
                        return getNodeOutput(conn.getEditorSourceNodeId(), conn.getEditorSourcePin());
                    }
                    return getNodeOutput(conn.getSourceNodeId(), conn.getSourcePin());
                }
            }
        }

        if (node.getInputValues() != null && node.getInputValues().containsKey(pinName)) {
            Object value = node.getInputValues().get(pinName);
            if (value instanceof String text) {
                return renderStringTemplate(node, pinName, text, resolvingTemplates);
            }
            return value;
        }

        return resolveDefinitionDefault(node, pinName);
    }

    private Object resolveDefinitionDefault(FlowNode node, String pinName) {
        if (nodeDefinitions == null || node == null || node.getType() == null || pinName == null) {
            return null;
        }
        NodeDefinition definition = getDefinition(node);
        if (definition == null) {
            return null;
        }
        NodeDefinition.PinDefinition pin = inputDefinition(definition, pinName);
        if (pin == null || pin.getDefaultValue() == null) {
            return null;
        }
        Class<?> targetType = pin.getDataType() != null ? pin.getDataType().getJavaType() : null;
        if (targetType == null || targetType == Object.class) {
            return pin.getDefaultValue();
        }
        Object adapted = typeAdapter.adapt(pin.getDefaultValue(), targetType);
        return adapted != null ? adapted : pin.getDefaultValue();
    }

    private NodeDefinition.PinDefinition inputDefinition(NodeDefinition definition, String pinName) {
        NodeDefinition.PinDefinition direct = definition.getInputs().stream().filter(candidate -> pinName.equals(candidate.getName())).findFirst().orElse(null);
        if (direct != null) {
            return direct;
        }
        for (NodeDefinition.PinDefinition candidate : definition.getInputs()) {
            NodeDefinition.RepeatablePin repeatable = candidate.getRepeatable();
            String prefix = candidate.getName() + "_";
            if (repeatable == null || !pinName.startsWith(prefix)) {
                continue;
            }
            String suffix = pinName.substring(prefix.length());
            if (!suffix.isEmpty() && suffix.length() <= 9 && suffix.chars().allMatch(Character::isDigit)) {
                int index = Integer.parseInt(suffix);
                if (index >= 2 && index <= repeatable.getMaxItems()) return candidate;
            }
        }
        return null;
    }

    public NodeDefinition getDefinition(FlowNode node) {
        if (nodeDefinitions == null || node == null || node.getType() == null) {
            return null;
        }
        return nodeDefinitions.get(compatibility.mapToNew(node.getType()));
    }

    private String renderStringTemplate(FlowNode node, String pinName, String template, Set<String> resolvingTemplates) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String nodeId = findNodeId(node);
        String key = nodeId + ":" + pinName;
        if (!resolvingTemplates.add(key)) {
            return template;
        }
        try {
            StringBuilder result = new StringBuilder();
            int index = 0;
            while (index < template.length()) {
                char current = template.charAt(index);
                if (current == '{') {
                    if (index + 1 < template.length() && template.charAt(index + 1) == '{') {
                        result.append('{');
                        index += 2;
                        continue;
                    }
                    int end = template.indexOf('}', index + 1);
                    if (end > index + 1) {
                        String name = template.substring(index + 1, end).trim();
                        if (isTemplateName(name)) {
                            if (isTemplateReservedInput(pinName, name)) {
                                result.append('{').append(name).append('}');
                            } else {
                                Object value = resolveInputRaw(node, name, resolvingTemplates);
                                if (value != null) {
                                    result.append(value);
                                }
                            }
                            index = end + 1;
                            continue;
                        }
                    }
                } else if (current == '}' && index + 1 < template.length() && template.charAt(index + 1) == '}') {
                    result.append('}');
                    index += 2;
                    continue;
                }
                result.append(current);
                index++;
            }
            return result.toString();
        } finally {
            resolvingTemplates.remove(key);
        }
    }

    public void openEventMutationWindow(boolean available) {
        executionAuthority.eventMutationOpen.set(available);
    }

    public void closeEventMutationWindow() {
        executionAuthority.eventMutationOpen.set(false);
    }

    public boolean isEventMutationOpen() {
        return executionAuthority.eventMutationOpen.get();
    }

    private boolean isTemplateReservedInput(String pinName, String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        return name.equals(pinName);
    }

    private boolean isTemplateName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
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
            if (value == null) {
                globalVariables.remove(name);
            } else {
                globalVariables.put(name, value);
            }
        } else {
            if (value == null) {
                localVariables.remove(name);
            } else {
                localVariables.put(name, value);
            }
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
            Set<String> resolving = resolvingPassthroughOutputs.get();
            if (!resolving.add(key)) {
                return null;
            }
            try {
                if (nodeOutputs.containsKey(key)) {
                    return nodeOutputs.get(key);
                }
                FlowNode node = graph != null && graph.getNodes() != null ? graph.getNodes().get(nodeId) : null;
                return node != null ? resolveInputRaw(node, passthroughInputPin(pinName)) : null;
            } finally {
                resolving.remove(key);
                if (resolving.isEmpty()) {
                    resolvingPassthroughOutputs.remove();
                }
            }
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
        return beginFlowExecution(graph, nodeId);
    }

    public void endFlowExecution(String nodeId) {
        endFlowExecution(graph, nodeId);
    }

    public boolean beginFlowExecution(FlowGraph executionGraph, String nodeId) {
        return executingFlowNodes.add(executionNodeKey(executionGraph, nodeId));
    }

    public void endFlowExecution(FlowGraph executionGraph, String nodeId) {
        executingFlowNodes.remove(executionNodeKey(executionGraph, nodeId));
    }

    public void resetFlowExecutionPath() {
        executingFlowNodes.clear();
    }

    private String executionNodeKey(FlowGraph executionGraph, String nodeId) {
        String graphId = executionGraph != null && executionGraph.getId() != null ? executionGraph.getId() : "";
        return graphId + '\u0000' + nodeId;
    }

    public boolean acquireExecutionOperation(int maximumOperations) {
        return executionAuthority.operations.incrementAndGet() <= maximumOperations;
    }

    public boolean isWithinElapsedBudget(long maximumDurationMillis) {
        return maximumDurationMillis <= 0 || elapsedMillis() <= maximumDurationMillis;
    }

    public long elapsedMillis() {
        return Math.max(0L, (System.nanoTime() - executionAuthority.startedAtNanos) / 1_000_000L);
    }

    public String getExecutionId() {
        return executionAuthority.executionId;
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
        return findFunctionStartNodeId(graph);
    }

    public static String findFunctionStartNodeId(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return null;
        }
        return graph.getNodes().entrySet().stream()
            .filter(entry -> entry.getValue() != null && isFunctionStartType(entry.getValue().getType()))
            .map(Map.Entry::getKey)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .findFirst()
            .orElse(null);
    }

    private static boolean isFunctionStartType(String type) {
        return "function_start".equals(type) || "function.start".equals(type) || "function.function_start".equals(type);
    }
    
    public void setBreakLoopRequested(boolean requested) {
        LoopControl control = loopControls.peek();
        if (control != null) {
            control.breakRequested = requested;
            if (requested) {
                control.continueRequested = false;
            }
        }
    }
    
    public boolean isBreakLoopRequested() {
        LoopControl control = loopControls.peek();
        return control != null && control.breakRequested;
    }
    
    public void setContinueLoopRequested(boolean requested) {
        LoopControl control = loopControls.peek();
        if (control != null && !control.breakRequested) {
            control.continueRequested = requested;
        }
    }
    
    public boolean isContinueLoopRequested() {
        LoopControl control = loopControls.peek();
        return control != null && control.continueRequested;
    }
    
    public void resetLoopControl() {
        LoopControl control = loopControls.peek();
        if (control != null) {
            control.breakRequested = false;
            control.continueRequested = false;
        }
    }

    public void beginLoopControl() {
        loopControls.push(new LoopControl());
    }

    public void endLoopControl() {
        if (!loopControls.isEmpty()) {
            loopControls.pop();
        }
    }

    public boolean requestLoopBreak() {
        if (loopControls.isEmpty()) {
            return false;
        }
        setBreakLoopRequested(true);
        return true;
    }

    public boolean requestLoopContinue() {
        if (loopControls.isEmpty()) {
            return false;
        }
        setContinueLoopRequested(true);
        return true;
    }

    public boolean consumeContinueLoopRequested() {
        LoopControl control = loopControls.peek();
        if (control == null || !control.continueRequested) {
            return false;
        }
        control.continueRequested = false;
        return true;
    }

    public void cleanupThreadLocals() {
        triggeredOutputPin.set(null);
        resolvingPassthroughOutputs.remove();
        evaluatingNodes.clear();
        executingFlowNodes.clear();
    }
}
