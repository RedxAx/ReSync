package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.FunctionParameterId;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FlowRuntime {
    private static final String PASSTHROUGH_OUTPUT_PREFIX = "__passthrough:";
    private static final Object MISSING_INPUT = new Object();
    private FlowGraph graph;
    private final Map<String, Object> nodeOutputs;
    private final Object nodeOutputsLock = new Object();
    private final Map<String, Object> localVariables;
    private final Map<String, Object> globalVariables;
    private final Map<String, Object> eventVariables;
    private final TypeAdapterRegistry typeAdapter;
    private final NodeDefinitionRegistry nodeDefinitions;
    private final AtomicReference<String> triggeredOutputPin = new AtomicReference<>();
    private final ThreadLocal<Set<String>> resolvingPassthroughOutputs = ThreadLocal.withInitial(HashSet::new);
    private final Set<String> evaluatingNodes = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, CompletableFuture<Void>> dataEvaluations = new ConcurrentHashMap<>();
    private final Object dataDependencyLock = new Object();
    private final Map<String, Set<String>> dataDependencies = new HashMap<>();
    private final Set<String> executingFlowNodes = ConcurrentHashMap.newKeySet();
    private final Map<FunctionParameterId, Object> functionInputs = new LinkedHashMap<>();
    private final Map<FunctionParameterId, Object> functionOutputs = new LinkedHashMap<>();
    private final ExecutionAuthority executionAuthority;
    private final CorrelationId invocationId;
    private BranchBaseline branchBaseline;
    private boolean serverGlobalsReady;

    private final Deque<Frame> callStack = new ArrayDeque<>();
    private final Deque<LoopControl> loopControls = new ArrayDeque<>();
    private boolean functionReturnRequested = false;
    private String returnedCallerNodeId;
    private final Map<FunctionParameterId, Object> returnedFunctionOutputs = new LinkedHashMap<>();
    private String debugSessionId;

    private static class Frame {
        final FlowGraph graph;
        final Map<String, Object> localVariables;
        final Map<String, Object> nodeOutputs;
        final String callerNodeId;
        final Map<FunctionParameterId, Object> functionInputs;
        final Map<FunctionParameterId, Object> functionOutputs;

        Frame(FlowGraph graph, Map<String, Object> localVars, Map<String, Object> nodeOutputs, String callerNodeId,
              Map<FunctionParameterId, Object> functionInputs, Map<FunctionParameterId, Object> functionOutputs) {
            this.graph = graph;
            this.localVariables = localVars;
            this.nodeOutputs = nodeOutputs;
            this.callerNodeId = callerNodeId;
            this.functionInputs = functionInputs;
            this.functionOutputs = functionOutputs;
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

    private record BranchBaseline(Map<String, Object> nodeOutputs,
                                  Map<String, Object> localVariables,
                                  Map<FunctionParameterId, Object> functionInputs,
                                  Map<FunctionParameterId, Object> functionOutputs,
                                  Map<FunctionParameterId, Object> returnedFunctionOutputs) {
    }

    public static final class DataEvaluation {
        private final CompletableFuture<Void> completion;
        private final boolean owner;

        private DataEvaluation(CompletableFuture<Void> completion, boolean owner) {
            this.completion = completion;
            this.owner = owner;
        }

        public CompletableFuture<Void> completion() {
            return completion;
        }

        public boolean owner() {
            return owner;
        }
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(graph, typeAdapter, globalVariables, new HashMap<>(), null);
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, Map<String, Object> eventVariables) {
        this(graph, typeAdapter, globalVariables, eventVariables, null);
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, Map<String, Object> eventVariables,
                       NodeDefinitionRegistry nodeDefinitions) {
        this(graph, typeAdapter, globalVariables, eventVariables, nodeDefinitions, new ExecutionAuthority(), CorrelationId.random());
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, Map<String, Object> eventVariables,
                       NodeDefinitionRegistry nodeDefinitions, LegacyRuntimeActivationGate legacyRuntimeGate) {
        this(graph, typeAdapter, globalVariables, eventVariables, nodeDefinitions, legacyRuntimeGate, CorrelationId.random());
    }

    public FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, Map<String, Object> eventVariables,
                       NodeDefinitionRegistry nodeDefinitions, LegacyRuntimeActivationGate legacyRuntimeGate, CorrelationId invocationId) {
        this(graph, typeAdapter, globalVariables, eventVariables, nodeDefinitions,
            new ExecutionAuthority(), invocationId != null ? invocationId : CorrelationId.random());
    }

    private FlowRuntime(FlowGraph graph, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, Map<String, Object> eventVariables,
                        NodeDefinitionRegistry nodeDefinitions, ExecutionAuthority executionAuthority, CorrelationId invocationId) {
        this.graph = graph;
        this.nodeOutputs = new LinkedHashMap<>();
        this.localVariables = new HashMap<>();
        this.globalVariables = concurrentVariables(globalVariables);
        this.eventVariables = concurrentVariables(eventVariables);
        this.typeAdapter = typeAdapter;
        this.nodeDefinitions = nodeDefinitions;
        this.executionAuthority = executionAuthority;
        this.invocationId = Objects.requireNonNull(invocationId, "Invocation ID Is Required");

        if (graph.getLocalVariables() != null) {
            graph.getLocalVariables().forEach(var -> localVariables.put(var.getName(), var.getInitialValue()));
        }
    }

    private Map<String, Object> concurrentVariables(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return new HashMap<>();
        }
        if (variables instanceof ConcurrentMap<?, ?>) {
            return variables;
        }
        HashMap<String, Object> copy = HashMap.newHashMap(variables.size());
        variables.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return copy;
    }

    public FlowRuntime createSubRuntime(FlowGraph subGraph) {
        return new FlowRuntime(subGraph, typeAdapter, globalVariables, eventVariables, nodeDefinitions, executionAuthority,
            CorrelationId.random());
    }

    FlowRuntime forkBranch() {
        FlowRuntime branch = new FlowRuntime(graph, typeAdapter, globalVariables, eventVariables, nodeDefinitions, executionAuthority, invocationId);
        Map<String, Object> outputSnapshot = nodeOutputSnapshot();
        branch.replaceNodeOutputs(outputSnapshot);
        branch.localVariables.clear();
        branch.localVariables.putAll(localVariables);
        branch.functionInputs.putAll(functionInputs);
        branch.functionOutputs.putAll(functionOutputs);
        branch.returnedFunctionOutputs.putAll(returnedFunctionOutputs);
        branch.callStack.addAll(copyFrames(callStack));
        branch.loopControls.addAll(copyLoopControls(loopControls));
        branch.functionReturnRequested = functionReturnRequested;
        branch.returnedCallerNodeId = returnedCallerNodeId;
        branch.debugSessionId = debugSessionId;
        branch.triggeredOutputPin.set(triggeredOutputPin.get());
        branch.executingFlowNodes.addAll(executingFlowNodes);
        branch.branchBaseline = new BranchBaseline(outputSnapshot, new LinkedHashMap<>(localVariables),
            new LinkedHashMap<>(functionInputs), new LinkedHashMap<>(functionOutputs), new LinkedHashMap<>(returnedFunctionOutputs));
        return branch;
    }

    void mergeCompletedBranches(List<FlowRuntime> branches) {
        if (branches == null || branches.isEmpty()) {
            return;
        }
        for (FlowRuntime branch : branches) {
            if (branch == null || branch.branchBaseline == null) {
                throw new IllegalStateException("Parallel Flow Branch State Is Missing");
            }
            if (!controlStateMatches(branch)) {
                throw new IllegalStateException("Parallel Flow Branch Control Frames Diverged");
            }
        }
        for (FlowRuntime branch : branches) {
            BranchBaseline baseline = branch.branchBaseline;
            mergeMapDelta(localVariables, baseline.localVariables(), branch.localVariables);
            synchronized (nodeOutputsLock) {
                mergeMapDelta(nodeOutputs, baseline.nodeOutputs(), branch.nodeOutputSnapshot());
            }
            mergeMapDelta(functionInputs, baseline.functionInputs(), branch.functionInputs);
            mergeMapDelta(functionOutputs, baseline.functionOutputs(), branch.functionOutputs);
            mergeMapDelta(returnedFunctionOutputs, baseline.returnedFunctionOutputs(), branch.returnedFunctionOutputs);
        }
        mergeLoopControls(branches);
    }

    private boolean controlStateMatches(FlowRuntime other) {
        if (other == null || graph != other.graph || functionReturnRequested != other.functionReturnRequested
            || !Objects.equals(returnedCallerNodeId, other.returnedCallerNodeId) || callStack.size() != other.callStack.size()
            || loopControls.size() != other.loopControls.size()) {
            return false;
        }
        var left = callStack.iterator();
        var right = other.callStack.iterator();
        while (left.hasNext() && right.hasNext()) {
            Frame leftFrame = left.next();
            Frame rightFrame = right.next();
            if (leftFrame.graph != rightFrame.graph || !Objects.equals(leftFrame.callerNodeId, rightFrame.callerNodeId)
                || !leftFrame.localVariables.equals(rightFrame.localVariables) || !leftFrame.nodeOutputs.equals(rightFrame.nodeOutputs)
                || !leftFrame.functionInputs.equals(rightFrame.functionInputs) || !leftFrame.functionOutputs.equals(rightFrame.functionOutputs)) {
                return false;
            }
        }
        return !left.hasNext() && !right.hasNext();
    }

    private void mergeLoopControls(List<FlowRuntime> branches) {
        if (branches.getFirst().loopControls.isEmpty()) {
            loopControls.clear();
            return;
        }
        List<LoopControl> merged = copyLoopControls(branches.getFirst().loopControls);
        for (int index = 0; index < merged.size(); index++) {
            LoopControl result = merged.get(index);
            for (FlowRuntime branch : branches) {
                LoopControl state = loopControlAt(branch.loopControls, index);
                result.breakRequested |= state.breakRequested;
                result.continueRequested |= state.continueRequested;
            }
            if (result.breakRequested) {
                result.continueRequested = false;
            }
        }
        loopControls.clear();
        loopControls.addAll(merged);
    }

    private LoopControl loopControlAt(Deque<LoopControl> controls, int index) {
        int current = 0;
        for (LoopControl control : controls) {
            if (current++ == index) {
                return control;
            }
        }
        throw new IllegalStateException("Parallel Flow Branch Loop Frame Is Missing");
    }

    private List<Frame> copyFrames(Deque<Frame> frames) {
        return frames.stream()
            .map(frame -> new Frame(frame.graph, new LinkedHashMap<>(frame.localVariables), new LinkedHashMap<>(frame.nodeOutputs),
                frame.callerNodeId, new LinkedHashMap<>(frame.functionInputs), new LinkedHashMap<>(frame.functionOutputs)))
            .toList();
    }

    private List<LoopControl> copyLoopControls(Deque<LoopControl> controls) {
        return controls.stream().map(control -> {
            LoopControl copy = new LoopControl();
            copy.breakRequested = control.breakRequested;
            copy.continueRequested = control.continueRequested;
            return copy;
        }).toList();
    }

    private Map<String, Object> nodeOutputSnapshot() {
        synchronized (nodeOutputsLock) {
            return new LinkedHashMap<>(nodeOutputs);
        }
    }

    private void replaceNodeOutputs(Map<String, Object> values) {
        synchronized (nodeOutputsLock) {
            nodeOutputs.clear();
            nodeOutputs.putAll(values);
        }
    }

    private <K, V> void mergeMapDelta(Map<K, V> target, Map<K, V> baseline, Map<K, V> branch) {
        for (K key : baseline.keySet()) {
            if (!branch.containsKey(key)) {
                target.remove(key);
            }
        }
        for (Map.Entry<K, V> entry : branch.entrySet()) {
            if (!baseline.containsKey(entry.getKey()) || !Objects.equals(baseline.get(entry.getKey()), entry.getValue())) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
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
        serverGlobalsReady = true;
    }

    private void ensureServerGlobals() {
        if (!serverGlobalsReady) {
            initializeServerGlobals();
        }
    }

    public void triggerOutput(String pinName) {
        triggerOutput(null, pinName);
    }

    public void triggerOutput(FlowNode node, String pinName) {
        triggeredOutputPin.set(normalizeOutputPin(node, pinName));
    }

    public String consumeTriggeredOutput() {
        return triggeredOutputPin.getAndSet(null);
    }

    public String getTriggeredOutputPin() {
        return triggeredOutputPin.get();
    }

    public void setTriggeredOutputPin(String pin) {
        triggeredOutputPin.set(normalizeOutputPin(pin));
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
                if (inputPinMatches(node, conn.getTargetPin(), pinName)) {
                    if (conn.getEditorSourceNodeId() != null && !conn.getEditorSourceNodeId().isBlank() && isPassthroughOutputPin(conn.getEditorSourcePin())) {
                        return getNodeOutput(conn.getEditorSourceNodeId(), conn.getEditorSourcePin());
                    }
                    return getNodeOutput(conn.getSourceNodeId(), conn.getSourcePin());
                }
            }
        }

        Object stored = findStoredInput(node, pinName);
        if (stored != MISSING_INPUT) {
            Object value = stored;
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
        NodeDefinition.PinDefinition pin = resolveInputPin(definition, pinName);
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

    public NodeDefinition getDefinition(FlowNode node) {
        if (nodeDefinitions == null || node == null || node.getType() == null) {
            return null;
        }
        return nodeDefinitions.get(node.getType());
    }

    public NodeDefinition.PinDefinition resolveInputPin(FlowNode node, String pinName) {
        return resolveInputPin(getDefinition(node), pinName);
    }

    public NodeDefinition.PinDefinition resolveOutputPin(FlowNode node, String pinName) {
        return resolveOutputPin(getDefinition(node), pinName);
    }

    public static NodeDefinition.PinDefinition resolveInputPin(NodeDefinition definition, String pinName) {
        return resolvePin(definition, pinName, NodeDefinition.PinDirection.INPUT);
    }

    public static NodeDefinition.PinDefinition resolveOutputPin(NodeDefinition definition, String pinName) {
        return resolvePin(definition, pinName, NodeDefinition.PinDirection.OUTPUT);
    }

    public String normalizeInputPin(FlowNode node, String pinName) {
        return normalizeInputPin(getDefinition(node), pinName);
    }

    public String normalizeOutputPin(FlowNode node, String pinName) {
        return normalizeOutputPin(getDefinition(node), pinName);
    }

    public String normalizeOutputPin(String nodeId, String pinName) {
        FlowNode node = graph != null && graph.getNodes() != null ? graph.getNodes().get(nodeId) : null;
        return node != null ? normalizeOutputPin(node, pinName) : normalizeOutputPin(pinName);
    }

    public String normalizeOutputPin(String pinName) {
        if (pinName == null || pinName.isBlank() || graph == null || graph.getNodes() == null) {
            return pinName;
        }
        String candidate = null;
        for (FlowNode node : graph.getNodes().values()) {
            String normalized = normalizeOutputPin(node, pinName);
            if (normalized.equals(pinName)) {
                continue;
            }
            if (candidate != null && !candidate.equals(normalized)) {
                return pinName;
            }
            candidate = normalized;
        }
        return candidate != null ? candidate : pinName;
    }

    public boolean inputPinMatches(FlowNode node, String persistedPin, String requestedPin) {
        return inputPinMatches(getDefinition(node), persistedPin, requestedPin);
    }

    public boolean outputPinMatches(FlowNode node, String persistedPin, String requestedPin) {
        return outputPinMatches(getDefinition(node), persistedPin, requestedPin);
    }

    public static boolean inputPinMatches(NodeDefinition definition, String persistedPin, String requestedPin) {
        return pinMatches(definition, persistedPin, requestedPin, NodeDefinition.PinDirection.INPUT);
    }

    public static boolean outputPinMatches(NodeDefinition definition, String persistedPin, String requestedPin) {
        return pinMatches(definition, persistedPin, requestedPin, NodeDefinition.PinDirection.OUTPUT);
    }

    public static String normalizeInputPin(NodeDefinition definition, String pinName) {
        return normalizePin(definition, pinName, NodeDefinition.PinDirection.INPUT);
    }

    public static String normalizeOutputPin(NodeDefinition definition, String pinName) {
        return normalizePin(definition, pinName, NodeDefinition.PinDirection.OUTPUT);
    }

    private static boolean pinMatches(NodeDefinition definition, String persistedPin, String requestedPin,
                                      NodeDefinition.PinDirection direction) {
        if (persistedPin == null || requestedPin == null) {
            return persistedPin == requestedPin;
        }
        return normalizePin(definition, persistedPin, direction).equals(normalizePin(definition, requestedPin, direction));
    }

    private static String normalizePin(NodeDefinition definition, String pinName, NodeDefinition.PinDirection direction) {
        if (pinName == null || pinName.isBlank() || definition == null) {
            return pinName;
        }
        NodeDefinition.PinDefinition pin = resolvePin(definition, pinName, direction);
        if (pin == null) {
            return pinName;
        }
        String stableName = pin.getName();
        if (pinName.equals(pin.getName()) || pinName.equals(pin.getRuntimeName())) {
            return stableName;
        }
        NodeDefinition.RepeatablePin repeatable = pin.getRepeatable();
        if (repeatable == null) {
            return stableName;
        }
        int separator = pinName.lastIndexOf('_');
        String suffix = separator >= 0 ? pinName.substring(separator + 1) : "";
        if (suffix.isEmpty() || suffix.length() > 9 || !suffix.chars().allMatch(Character::isDigit)) {
            return stableName;
        }
        int index = Integer.parseInt(suffix);
        return index <= 1 ? stableName : stableName + "_" + index;
    }

    private static NodeDefinition.PinDefinition resolvePin(NodeDefinition definition, String pinName,
                                                            NodeDefinition.PinDirection direction) {
        if (definition == null || pinName == null || pinName.isBlank()) {
            return null;
        }
        List<NodeDefinition.PinDefinition> pins = direction == NodeDefinition.PinDirection.INPUT
            ? definition.getInputs() : definition.getOutputs();
        for (NodeDefinition.PinDefinition pin : pins) {
            if (pinName.equals(pin.getName()) || pinName.equals(pin.getRuntimeName())) {
                return pin;
            }
        }
        for (NodeDefinition.PinDefinition pin : pins) {
            NodeDefinition.RepeatablePin repeatable = pin.getRepeatable();
            if (repeatable == null) {
                continue;
            }
            for (String base : List.of(pin.getName(), pin.getRuntimeName())) {
                String prefix = base + "_";
                if (!pinName.startsWith(prefix)) {
                    continue;
                }
                String suffix = pinName.substring(prefix.length());
                if (suffix.isEmpty() || suffix.length() > 9 || !suffix.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                int index = Integer.parseInt(suffix);
                if (index >= 1 && index <= repeatable.getMaxItems()) {
                    return pin;
                }
            }
        }
        return null;
    }

    private Object findStoredInput(FlowNode node, String pinName) {
        if (node == null || node.getInputValues() == null) {
            return MISSING_INPUT;
        }
        Map<String, Object> values = node.getInputValues();
        String canonical = normalizeInputPin(node, pinName);
        if (values.containsKey(canonical)) {
            return values.get(canonical);
        }
        if (values.containsKey(pinName)) {
            return values.get(pinName);
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (inputPinMatches(node, entry.getKey(), pinName)) {
                return entry.getValue();
            }
        }
        return MISSING_INPUT;
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
                            if (isTemplateReservedInput(node, pinName, name)) {
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

    private boolean isTemplateReservedInput(FlowNode node, String pinName, String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        return name.equals(pinName) || inputPinMatches(node, name, pinName);
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
            ensureServerGlobals();
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
        String normalizedPin = isPassthroughOutputPin(pinName) ? pinName : normalizeOutputPin(nodeId, pinName);
        synchronized (nodeOutputsLock) {
            nodeOutputs.put(nodeId + ":" + normalizedPin, value);
        }
    }

    public void clearNodeOutputs(String nodeId) {
        if (nodeId == null) {
            return;
        }
        String prefix = nodeId + ":";
        synchronized (nodeOutputsLock) {
            nodeOutputs.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    public boolean hasNodeOutput(String nodeId, String pinName) {
        if (isPassthroughOutputPin(pinName)) {
            synchronized (nodeOutputsLock) {
                if (nodeOutputs.containsKey(nodeId + ":" + pinName)) {
                    return true;
                }
            }
            return hasPassthroughInput(nodeId, passthroughInputPin(pinName));
        }
        String normalizedPin = normalizeOutputPin(nodeId, pinName);
        synchronized (nodeOutputsLock) {
            return nodeOutputs.containsKey(nodeId + ":" + normalizedPin)
                || (!normalizedPin.equals(pinName) && nodeOutputs.containsKey(nodeId + ":" + pinName));
        }
    }

    public Object getNodeOutput(String nodeId, String pinName) {
        if (isPassthroughOutputPin(pinName)) {
            String key = nodeId + ":" + pinName;
            Set<String> resolving = resolvingPassthroughOutputs.get();
            if (!resolving.add(key)) {
                return null;
            }
            try {
                synchronized (nodeOutputsLock) {
                    if (nodeOutputs.containsKey(key)) {
                        return nodeOutputs.get(key);
                    }
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
        String normalizedPin = normalizeOutputPin(nodeId, pinName);
        synchronized (nodeOutputsLock) {
            String key = nodeId + ":" + normalizedPin;
            if (nodeOutputs.containsKey(key)) {
                return nodeOutputs.get(key);
            }
            return !normalizedPin.equals(pinName) ? nodeOutputs.get(nodeId + ":" + pinName) : null;
        }
    }

    private boolean hasPassthroughInput(String nodeId, String inputPin) {
        FlowNode node = graph != null && graph.getNodes() != null ? graph.getNodes().get(nodeId) : null;
        if (node == null || inputPin == null || inputPin.isBlank()) {
            return false;
        }
        for (FlowConnection conn : graph.getConnectionsToTarget(nodeId)) {
            if (inputPinMatches(node, conn.getTargetPin(), inputPin)) {
                return true;
            }
        }
        return findStoredInput(node, inputPin) != MISSING_INPUT;
    }

    public boolean hasExplicitInput(FlowNode node, String pinName) {
        if (node == null || pinName == null || pinName.isBlank()) {
            return false;
        }
        String nodeId = findNodeId(node);
        if (nodeId != null && graph != null) {
            for (FlowConnection connection : graph.getConnectionsToTarget(nodeId)) {
                if (inputPinMatches(node, connection.getTargetPin(), pinName)) {
                    return true;
                }
            }
        }
        return findStoredInput(node, pinName) != MISSING_INPUT;
    }

    public boolean hasInputConnection(FlowNode node, String pinName) {
        if (node == null || pinName == null || pinName.isBlank()) {
            return false;
        }
        String nodeId = findNodeId(node);
        if (nodeId == null || graph == null) {
            return false;
        }
        return graph.getConnectionsToTarget(nodeId).stream()
            .anyMatch(connection -> inputPinMatches(node, connection.getTargetPin(), pinName));
    }

    private boolean isPassthroughOutputPin(String pinName) {
        return pinName != null && pinName.startsWith(PASSTHROUGH_OUTPUT_PREFIX);
    }

    private String passthroughInputPin(String outputPin) {
        return isPassthroughOutputPin(outputPin) ? outputPin.substring(PASSTHROUGH_OUTPUT_PREFIX.length()) : outputPin;
    }

    public boolean isEvaluating(String nodeId) {
        return evaluatingNodes.contains(nodeId) || dataEvaluations.containsKey(nodeId);
    }

    public void beginEvaluating(String nodeId) {
        evaluatingNodes.add(nodeId);
    }

    public void endEvaluating(String nodeId) {
        evaluatingNodes.remove(nodeId);
    }

    public DataEvaluation beginDataEvaluation(String nodeId) {
        CompletableFuture<Void> candidate = new CompletableFuture<>();
        CompletableFuture<Void> existing = dataEvaluations.putIfAbsent(nodeId, candidate);
        if (existing != null) {
            return new DataEvaluation(existing, false);
        }
        evaluatingNodes.add(nodeId);
        return new DataEvaluation(candidate, true);
    }

    public CompletableFuture<Void> awaitDataEvaluation(String nodeId) {
        CompletableFuture<Void> evaluation = dataEvaluations.get(nodeId);
        return evaluation != null ? evaluation : CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> currentDataEvaluation(String nodeId) {
        return dataEvaluations.get(nodeId);
    }

    public void completeDataEvaluation(String nodeId, DataEvaluation evaluation, Throwable failure) {
        if (evaluation == null || !evaluation.owner()) {
            return;
        }
        if (failure == null) {
            evaluation.completion().complete(null);
        } else {
            evaluation.completion().completeExceptionally(failure);
        }
        dataEvaluations.remove(nodeId, evaluation.completion());
        evaluatingNodes.remove(nodeId);
        clearDataDependencies(nodeId);
    }

    public boolean registerDataDependency(String ownerNodeId, String dependencyNodeId) {
        if (ownerNodeId == null || ownerNodeId.isBlank() || dependencyNodeId == null || dependencyNodeId.isBlank()) {
            return true;
        }
        synchronized (dataDependencyLock) {
            if (ownerNodeId.equals(dependencyNodeId) || reachesDataDependency(dependencyNodeId, ownerNodeId, new HashSet<>())) {
                return false;
            }
            dataDependencies.computeIfAbsent(ownerNodeId, ignored -> new HashSet<>()).add(dependencyNodeId);
            return true;
        }
    }

    public void clearDataDependencies(String ownerNodeId) {
        if (ownerNodeId == null || ownerNodeId.isBlank()) {
            return;
        }
        synchronized (dataDependencyLock) {
            dataDependencies.remove(ownerNodeId);
            dataDependencies.values().forEach(dependencies -> dependencies.remove(ownerNodeId));
        }
    }

    private boolean reachesDataDependency(String currentNodeId, String targetNodeId, Set<String> visited) {
        if (currentNodeId.equals(targetNodeId)) {
            return true;
        }
        if (!visited.add(currentNodeId)) {
            return false;
        }
        for (String dependencyNodeId : dataDependencies.getOrDefault(currentNodeId, Set.of())) {
            if (reachesDataDependency(dependencyNodeId, targetNodeId, visited)) {
                return true;
            }
        }
        return false;
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

    public CorrelationId getInvocationId() {
        return invocationId;
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

    public void callFunction(FlowGraph functionGraph, String callerNodeId, Map<?, ?> inputs) {
        Map<FunctionParameterId, Object> inputFrame = typedParameterFrame(inputs)
            .orElseGet(() -> adaptLegacyFunctionInputs(functionGraph, legacyParameterFrame(inputs)));
        callFunctionById(functionGraph, callerNodeId, inputFrame);
    }

    public void callFunctionById(FlowGraph functionGraph, String callerNodeId,
                                 Map<FunctionParameterId, Object> inputs) {
        validateParameterFrame(functionGraph, true, inputs);
        Map<String, Object> outputSnapshot;
        synchronized (nodeOutputsLock) {
            outputSnapshot = new LinkedHashMap<>(nodeOutputs);
        }
        Frame frame = new Frame(graph, new HashMap<>(localVariables), outputSnapshot, callerNodeId,
            new LinkedHashMap<>(functionInputs), new LinkedHashMap<>(functionOutputs));
        callStack.push(frame);

        this.graph = functionGraph;
        this.localVariables.clear();
        synchronized (nodeOutputsLock) {
            this.nodeOutputs.clear();
        }
        this.functionInputs.clear();
        this.functionOutputs.clear();

        if (functionGraph != null && functionGraph.getLocalVariables() != null) {
            functionGraph.getLocalVariables().forEach(var -> localVariables.put(var.getName(), var.getInitialValue()));
        }

        if (inputs != null) {
            this.functionInputs.putAll(inputs);
            for (FlowGraph.FunctionParameter parameter : functionParameters(functionGraph, true)) {
                FunctionParameterId id = parameterId(parameter);
                if (id != null && inputs.containsKey(id) && parameter.getName() != null && !parameter.getName().isBlank()) {
                    this.localVariables.put(parameter.getName(), inputs.get(id));
                }
            }
        }
    }

    public boolean returnFromFunction(Object returnValue) {
        List<FlowGraph.FunctionParameter> outputs = functionParameters(graph, false);
        if (outputs.size() == 1 && parameterId(outputs.getFirst()) != null) {
            Map<FunctionParameterId, Object> outputFrame = new LinkedHashMap<>();
            outputFrame.put(parameterId(outputs.getFirst()), returnValue);
            return returnFromFunctionById(outputFrame);
        }
        Map<String, Object> returnValues = new HashMap<>();
        returnValues.put("return", returnValue);
        return returnFromFunction(returnValues);
    }

    public boolean returnFromFunction(Map<?, ?> returnValues) {
        Map<FunctionParameterId, Object> outputFrame = typedParameterFrame(returnValues)
            .orElseGet(() -> adaptLegacyFunctionOutputs(graph, legacyParameterFrame(returnValues)));
        return returnFromFunctionById(outputFrame);
    }

    public boolean returnFromFunctionById(Map<FunctionParameterId, Object> returnValues) {
        if (callStack.isEmpty()) {
            return false;
        }

        Map<FunctionParameterId, Object> outputFrame = returnValues == null
            ? Map.of() : new LinkedHashMap<>(returnValues);
        FlowGraph functionGraph = graph;
        validateParameterFrame(functionGraph, false, outputFrame);
        Frame frame = callStack.pop();
        returnedFunctionOutputs.clear();
        returnedFunctionOutputs.putAll(outputFrame);

        this.graph = frame.graph;
        this.localVariables.clear();
        this.localVariables.putAll(frame.localVariables);
        synchronized (nodeOutputsLock) {
            this.nodeOutputs.clear();
            this.nodeOutputs.putAll(frame.nodeOutputs);
        }
        this.functionInputs.clear();
        this.functionInputs.putAll(frame.functionInputs);
        this.functionOutputs.clear();
        this.functionOutputs.putAll(outputFrame);

        if (frame.callerNodeId != null) {
            for (FlowGraph.FunctionParameter parameter : functionParameters(functionGraph, false)) {
                FunctionParameterId id = parameterId(parameter);
                if (id == null || !outputFrame.containsKey(id) || parameter.getName() == null || parameter.getName().isBlank()) {
                    continue;
                }
                synchronized (nodeOutputsLock) {
                    nodeOutputs.put(frame.callerNodeId + ":" + parameter.getName(), outputFrame.get(id));
                }
            }
        }

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
        FunctionParameterId id = parseFunctionParameterId(name);
        if (id != null) {
            return getFunctionInput(id);
        }
        FlowGraph.FunctionParameter parameter = legacyFunctionParameter(graph, true, name);
        return parameter != null ? getFunctionInput(parameterId(parameter)) : null;
    }

    public Map<String, Object> getFunctionInputs() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FlowGraph.FunctionParameter parameter : functionParameters(graph, true)) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            FunctionParameterId id = parameterId(parameter);
            if (id != null && functionInputs.containsKey(id)) {
                values.put(parameter.getName(), functionInputs.get(id));
            }
        }
        return values;
    }

    public Object getFunctionInput(FunctionParameterId id) {
        return id != null ? functionInputs.get(id) : null;
    }

    public Map<FunctionParameterId, Object> getFunctionInputsById() {
        return Collections.unmodifiableMap(functionInputs);
    }

    public Map<FunctionParameterId, Object> getReturnedFunctionOutputsById() {
        return Collections.unmodifiableMap(returnedFunctionOutputs);
    }

    public Map<FunctionParameterId, Object> getFunctionOutputsById() {
        return Collections.unmodifiableMap(functionOutputs);
    }

    public Map<FunctionParameterId, Object> adaptLegacyFunctionInputs(FlowGraph functionGraph, Map<String, Object> inputs) {
        if (functionGraph == null) {
            return Map.of();
        }
        functionGraph.adaptLegacyFunctionParameterIds();
        if (inputs == null || inputs.isEmpty()) {
            return Map.of();
        }
        Map<FunctionParameterId, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            FlowGraph.FunctionParameter parameter = functionParameter(functionGraph, true, entry.getKey());
            if (parameter == null) {
                throw new IllegalArgumentException("Unknown function input: " + entry.getKey());
            }
            FunctionParameterId id = requireParameterId(parameter);
            values.put(id, entry.getValue());
        }
        return values;
    }

    public Map<FunctionParameterId, Object> adaptLegacyFunctionOutputs(FlowGraph functionGraph, Map<String, Object> outputs) {
        if (functionGraph == null) {
            return Map.of();
        }
        functionGraph.adaptLegacyFunctionParameterIds();
        if (outputs == null || outputs.isEmpty()) {
            return Map.of();
        }
        Map<FunctionParameterId, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            FlowGraph.FunctionParameter parameter = functionParameter(functionGraph, false, entry.getKey());
            if (parameter == null) {
                throw new IllegalArgumentException("Unknown function output: " + entry.getKey());
            }
            values.put(requireParameterId(parameter), entry.getValue());
        }
        return values;
    }

    private List<FlowGraph.FunctionParameter> functionParameters(FlowGraph functionGraph, boolean inputs) {
        if (functionGraph == null) {
            return List.of();
        }
        List<FlowGraph.FunctionParameter> parameters = inputs ? functionGraph.getFunctionInputs() : functionGraph.getFunctionOutputs();
        return parameters != null ? parameters : List.of();
    }

    private FlowGraph.FunctionParameter functionParameter(FlowGraph functionGraph, boolean inputs, String name) {
        if (name == null) {
            return null;
        }
        return functionParameters(functionGraph, inputs).stream()
            .filter(parameter -> parameter != null && name.equals(parameter.getName()))
            .findFirst()
            .orElse(null);
    }

    private FlowGraph.FunctionParameter legacyFunctionParameter(FlowGraph functionGraph, boolean inputs, String name) {
        if (name == null) {
            return null;
        }
        return functionParameters(functionGraph, inputs).stream()
            .filter(parameter -> parameter != null && parameter.isLegacyNameOnly() && name.equals(parameter.getName()))
            .findFirst()
            .orElse(null);
    }

    private FunctionParameterId parseFunctionParameterId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FunctionParameterId.parseCanonicalText(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private FunctionParameterId parameterId(FlowGraph.FunctionParameter parameter) {
        return parameter != null ? parameter.getParameterId() : null;
    }

    private FunctionParameterId requireParameterId(FlowGraph.FunctionParameter parameter) {
        FunctionParameterId id = parameterId(parameter);
        if (id == null) {
            throw new IllegalArgumentException("Function parameter ID is required: " + parameter.getName());
        }
        return id;
    }

    private Optional<Map<FunctionParameterId, Object>> typedParameterFrame(Map<?, ?> values) {
        if (values == null || values.isEmpty() || values.keySet().stream().noneMatch(FunctionParameterId.class::isInstance)) {
            return Optional.empty();
        }
        Map<FunctionParameterId, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof FunctionParameterId id)) {
                throw new IllegalArgumentException("Function parameter frame keys must use FunctionParameterId");
            }
            typed.put(id, entry.getValue());
        }
        return Optional.of(typed);
    }

    private Map<String, Object> legacyParameterFrame(Map<?, ?> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> legacy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException("Legacy function parameter frame keys must use names");
            }
            legacy.put(name, entry.getValue());
        }
        return legacy;
    }

    private void validateParameterFrame(FlowGraph functionGraph, boolean inputs, Map<FunctionParameterId, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Set<FunctionParameterId> declared = new HashSet<>();
        for (FlowGraph.FunctionParameter parameter : functionParameters(functionGraph, inputs)) {
            if (parameter != null) {
                declared.add(requireParameterId(parameter));
            }
        }
        for (FunctionParameterId id : values.keySet()) {
            if (id == null || !declared.contains(id)) {
                throw new IllegalArgumentException("Unknown function parameter ID: " + id);
            }
        }
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

    public static boolean isFunctionStartType(String type) {
        return "function_start".equals(type) || "function.start".equals(type) || "function.function_start".equals(type)
            || "restudio.resync/function_start".equals(type) || "builtin/function.start".equals(type);
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
