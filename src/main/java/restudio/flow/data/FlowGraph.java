package restudio.flow.data;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class FlowGraph {
    public static final int CURRENT_VERSION = 2;
    private String id;
    private int version;
    private Map<String, FlowNode> nodes;
    private List<FlowConnection> connections;
    private List<FlowVariable> localVariables;
    private boolean function;
    private String functionOwner;
    private String functionNamespace;
    private int functionVersion;
    private String functionDescription;
    private List<FunctionParameter> functionInputs;
    private List<FunctionParameter> functionOutputs;
    private List<EditorPassthrough> editorPassthroughs;
    private Map<String, Object> contentProperties;
    private String resourceType;
    private long resourceRevision;
    private String resourceHash;
    private String resourceMutationId;
    private transient Map<String, JsonElement> opaqueProperties;

    private transient Map<String, List<FlowConnection>> connectionsBySource = new HashMap<>();
    private transient Map<String, List<FlowConnection>> connectionsByTarget = new HashMap<>();
    private transient IdentityHashMap<FlowNode, String> nodeToId = new IdentityHashMap<>();
    private transient boolean indicesDirty = true;

    public static class FunctionParameter {
        private String name;
        private FlowDataType type;
        private FlowTypeRef typeRef;
        private String widget;
        private String optionsSource;
        private String defaultValue;

        public FunctionParameter() {
            this.name = "";
            this.type = FlowDataType.ANY;
            this.typeRef = null;
            this.widget = "";
            this.optionsSource = "";
            this.defaultValue = "";
        }

        public FunctionParameter(String name, FlowDataType type) {
            this.name = name;
            this.type = type != null ? type : FlowDataType.ANY;
            this.typeRef = FlowTypeRef.simple(this.type.getId()).normalizedGenerics();
            this.widget = "";
            this.optionsSource = "";
            this.defaultValue = "";
        }

        public FunctionParameter(String name, FlowDataType type, String widget, String optionsSource, String defaultValue) {
            this.name = name;
            this.type = type != null ? type : FlowDataType.ANY;
            this.typeRef = FlowTypeRef.simple(this.type.getId()).normalizedGenerics();
            this.widget = widget != null ? widget : "";
            this.optionsSource = optionsSource != null ? optionsSource : "";
            this.defaultValue = defaultValue != null ? defaultValue : "";
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public FlowDataType getType() {
            return type;
        }

        public void setType(FlowDataType type) {
            this.type = type;
            this.typeRef = FlowTypeRef.simple(type != null ? type.getId() : FlowDataType.ANY.getId()).normalizedGenerics();
        }

        public FlowTypeRef getTypeRef() {
            return (typeRef != null ? typeRef : FlowTypeRef.simple(type != null ? type.getId() : FlowDataType.ANY.getId())).normalizedGenerics();
        }

        public void setTypeRef(FlowTypeRef typeRef) {
            this.typeRef = (typeRef != null ? typeRef : FlowTypeRef.simple(type != null ? type.getId() : FlowDataType.ANY.getId())).normalizedGenerics();
        }

        public String getWidget() {
            return widget;
        }

        public void setWidget(String widget) {
            this.widget = widget;
        }

        public String getOptionsSource() {
            return optionsSource;
        }

        public void setOptionsSource(String optionsSource) {
            this.optionsSource = optionsSource;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }
    }

    public static class EditorPassthrough {
        private String nodeId;
        private String inputPin;

        public EditorPassthrough() {
            this.nodeId = "";
            this.inputPin = "";
        }

        public EditorPassthrough(String nodeId, String inputPin) {
            this.nodeId = nodeId;
            this.inputPin = inputPin;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getInputPin() {
            return inputPin;
        }

        public void setInputPin(String inputPin) {
            this.inputPin = inputPin;
        }
    }

    public FlowGraph() {
        this.id = UUID.randomUUID().toString();
        this.version = CURRENT_VERSION;
        this.nodes = new HashMap<>();
        this.connections = new ArrayList<>();
        this.localVariables = new ArrayList<>();
        this.function = false;
        this.functionOwner = "server";
        this.functionNamespace = "local";
        this.functionVersion = 1;
        this.functionDescription = "";
        this.functionInputs = new ArrayList<>();
        this.functionOutputs = new ArrayList<>();
        this.editorPassthroughs = new ArrayList<>();
        this.resourceType = "";
        this.resourceHash = "";
        this.resourceMutationId = "";
        rebuildIndices();
    }

    public FlowGraph(String id, Map<String, FlowNode> nodes, List<FlowConnection> connections, List<FlowVariable> localVariables) {
        this(id, nodes, connections, localVariables, false, new ArrayList<>(), new ArrayList<>());
    }

    public FlowGraph(String id, Map<String, FlowNode> nodes, List<FlowConnection> connections, List<FlowVariable> localVariables,
                     boolean function, List<FunctionParameter> functionInputs, List<FunctionParameter> functionOutputs) {
        this.id = id;
        this.version = CURRENT_VERSION;
        this.nodes = nodes != null ? nodes : new HashMap<>();
        this.connections = connections != null ? connections : new ArrayList<>();
        this.localVariables = localVariables != null ? localVariables : new ArrayList<>();
        this.function = function;
        this.functionOwner = "server";
        this.functionNamespace = "local";
        this.functionVersion = 1;
        this.functionDescription = "";
        this.functionInputs = functionInputs != null ? functionInputs : new ArrayList<>();
        this.functionOutputs = functionOutputs != null ? functionOutputs : new ArrayList<>();
        this.editorPassthroughs = new ArrayList<>();
        this.resourceType = "";
        this.resourceHash = "";
        this.resourceMutationId = "";
        rebuildIndices();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, FlowNode> getNodes() {
        ensureTrackedCollections();
        return nodes;
    }

    public void setNodes(Map<String, FlowNode> nodes) {
        this.nodes = trackedNodes(nodes);
        rebuildIndices();
    }

    public List<FlowConnection> getConnections() {
        ensureTrackedCollections();
        return connections;
    }

    public void setConnections(List<FlowConnection> connections) {
        this.connections = trackedConnections(connections);
        rebuildIndices();
    }

    public List<FlowVariable> getLocalVariables() {
        return localVariables;
    }

    public void setLocalVariables(List<FlowVariable> localVariables) {
        this.localVariables = localVariables;
    }

    public boolean isFunction() {
        return function;
    }

    public void setFunction(boolean function) {
        this.function = function;
    }

    public String getFunctionOwner() {
        return functionOwner != null && !functionOwner.isBlank() ? functionOwner : "server";
    }

    public void setFunctionOwner(String functionOwner) {
        this.functionOwner = functionOwner != null && !functionOwner.isBlank() ? functionOwner : "server";
    }

    public String getFunctionNamespace() {
        return functionNamespace != null && !functionNamespace.isBlank() ? functionNamespace : "local";
    }

    public void setFunctionNamespace(String functionNamespace) {
        this.functionNamespace = functionNamespace != null && !functionNamespace.isBlank() ? functionNamespace : "local";
    }

    public int getFunctionVersion() {
        return Math.max(1, functionVersion);
    }

    public void setFunctionVersion(int functionVersion) {
        this.functionVersion = Math.max(1, functionVersion);
    }

    public String getFunctionDescription() {
        return functionDescription != null ? functionDescription : "";
    }

    public void setFunctionDescription(String functionDescription) {
        this.functionDescription = functionDescription != null ? functionDescription : "";
    }

    public List<FunctionParameter> getFunctionInputs() {
        return functionInputs;
    }

    public void setFunctionInputs(List<FunctionParameter> functionInputs) {
        this.functionInputs = functionInputs;
    }

    public List<FunctionParameter> getFunctionOutputs() {
        return functionOutputs;
    }

    public void setFunctionOutputs(List<FunctionParameter> functionOutputs) {
        this.functionOutputs = functionOutputs;
    }

    public List<EditorPassthrough> getEditorPassthroughs() {
        if (editorPassthroughs == null) {
            editorPassthroughs = new ArrayList<>();
        }
        return editorPassthroughs;
    }

    public void setEditorPassthroughs(List<EditorPassthrough> editorPassthroughs) {
        this.editorPassthroughs = editorPassthroughs != null ? editorPassthroughs : new ArrayList<>();
    }

    public Map<String, Object> getContentProperties() {
        if (contentProperties == null) {
            contentProperties = new HashMap<>();
        }
        return contentProperties;
    }

    public void setContentProperties(Map<String, Object> contentProperties) {
        this.contentProperties = contentProperties != null ? contentProperties : new HashMap<>();
    }

    public String getResourceType() {
        return resourceType == null ? "" : resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType == null ? "" : resourceType;
    }

    public long getResourceRevision() {
        return resourceRevision;
    }

    public void setResourceRevision(long resourceRevision) {
        this.resourceRevision = Math.max(0, resourceRevision);
    }

    public String getResourceHash() {
        return resourceHash == null ? "" : resourceHash;
    }

    public void setResourceHash(String resourceHash) {
        this.resourceHash = resourceHash == null ? "" : resourceHash;
    }

    public String getResourceMutationId() {
        return resourceMutationId == null ? "" : resourceMutationId;
    }

    public void setResourceMutationId(String resourceMutationId) {
        this.resourceMutationId = resourceMutationId == null ? "" : resourceMutationId;
    }

    public Map<String, JsonElement> getOpaqueProperties() {
        if (opaqueProperties == null) {
            opaqueProperties = new HashMap<>();
        }
        return opaqueProperties;
    }

    public void setOpaqueProperties(Map<String, JsonElement> opaqueProperties) {
        this.opaqueProperties = opaqueProperties != null ? new HashMap<>(opaqueProperties) : new HashMap<>();
    }

    private void rebuildIndices() {
        ensureTrackedCollections();
        connectionsBySource = new HashMap<>();
        connectionsByTarget = new HashMap<>();
        nodeToId = new IdentityHashMap<>();

        if (nodes != null) {
            for (Map.Entry<String, FlowNode> entry : nodes.entrySet()) {
                nodeToId.put(entry.getValue(), entry.getKey());
            }
        }

        if (connections != null) {
            for (FlowConnection conn : connections) {
                connectionsBySource.computeIfAbsent(conn.getSourceNodeId(), k -> new ArrayList<>()).add(conn);
                connectionsByTarget.computeIfAbsent(conn.getTargetNodeId(), k -> new ArrayList<>()).add(conn);
            }
        }
        indicesDirty = false;
    }

    public List<FlowConnection> getConnectionsFromSource(String nodeId) {
        ensureIndicesBuilt();
        return connectionsBySource.getOrDefault(nodeId, Collections.emptyList());
    }

    public List<FlowConnection> getConnectionsToTarget(String nodeId) {
        ensureIndicesBuilt();
        return connectionsByTarget.getOrDefault(nodeId, Collections.emptyList());
    }

    public String findNodeId(FlowNode node) {
        ensureIndicesBuilt();
        return nodeToId.get(node);
    }

    public FlowGraph extractSubGraph(String startNodeId, String startPin) {
        ensureIndicesBuilt();
        Set<String> visitedNodes = new HashSet<>();
        List<FlowConnection> subConnections = new ArrayList<>();
        List<String> queue = new ArrayList<>();

        for (FlowConnection conn : connectionsBySource.getOrDefault(startNodeId, Collections.emptyList())) {
            if (conn.getSourcePin().equals(startPin)) {
                queue.add(conn.getTargetNodeId());
            }
        }

        while (!queue.isEmpty()) {
            String currentId = queue.removeFirst();
            if (!visitedNodes.add(currentId)) {
                continue;
            }
            for (FlowConnection conn : connectionsBySource.getOrDefault(currentId, Collections.emptyList())) {
                subConnections.add(conn);
                queue.add(conn.getTargetNodeId());
            }
        }

        Map<String, FlowNode> subNodes = new HashMap<>();
        for (String nodeId : visitedNodes) {
            FlowNode node = nodes.get(nodeId);
            if (node != null) {
                subNodes.put(nodeId, node);
            }
        }

        FlowGraph subGraph = new FlowGraph();
        subGraph.setNodes(subNodes);
        subGraph.setConnections(subConnections);
        return subGraph;
    }

    private void ensureIndicesBuilt() {
        ensureTrackedCollections();
        if (indicesDirty || connectionsBySource == null || connectionsByTarget == null || nodeToId == null) {
            rebuildIndices();
        }
    }

    private void ensureTrackedCollections() {
        if (!(nodes instanceof InvalidatingMap<?, ?>)) {
            nodes = trackedNodes(nodes);
            indicesDirty = true;
        }
        if (!(connections instanceof InvalidatingList<?>)) {
            connections = trackedConnections(connections);
            indicesDirty = true;
        }
    }

    private Map<String, FlowNode> trackedNodes(Map<String, FlowNode> values) {
        return new InvalidatingMap<>(values != null ? values : Map.of(), this::invalidateIndices);
    }

    private List<FlowConnection> trackedConnections(List<FlowConnection> values) {
        return new InvalidatingList<>(values != null ? values : List.of(), this::invalidateIndices);
    }

    private void invalidateIndices() {
        indicesDirty = true;
    }

    private static final class InvalidatingMap<K, V> extends HashMap<K, V> {
        private final Runnable invalidator;

        private InvalidatingMap(Map<K, V> values, Runnable invalidator) {
            super(values);
            this.invalidator = invalidator;
        }

        @Override
        public V put(K key, V value) {
            V previous = super.put(key, value);
            invalidator.run();
            return previous;
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> values) {
            if (!values.isEmpty()) {
                super.putAll(values);
                invalidator.run();
            }
        }

        @Override
        public V remove(Object key) {
            V previous = super.remove(key);
            if (previous != null) {
                invalidator.run();
            }
            return previous;
        }

        @Override
        public boolean remove(Object key, Object value) {
            boolean removed = super.remove(key, value);
            if (removed) {
                invalidator.run();
            }
            return removed;
        }

        @Override
        public void clear() {
            if (!isEmpty()) {
                super.clear();
                invalidator.run();
            }
        }

        @Override
        public V putIfAbsent(K key, V value) {
            V previous = super.putIfAbsent(key, value);
            if (previous == null) {
                invalidator.run();
            }
            return previous;
        }

        @Override
        public boolean replace(K key, V oldValue, V newValue) {
            boolean replaced = super.replace(key, oldValue, newValue);
            if (replaced) {
                invalidator.run();
            }
            return replaced;
        }

        @Override
        public V replace(K key, V value) {
            V previous = super.replace(key, value);
            if (previous != null) {
                invalidator.run();
            }
            return previous;
        }

        @Override
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            if (!isEmpty()) {
                super.replaceAll(function);
                invalidator.run();
            }
        }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            boolean present = containsKey(key);
            V value = super.computeIfAbsent(key, mappingFunction);
            if (!present && containsKey(key)) {
                invalidator.run();
            }
            return value;
        }

        @Override
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            boolean present = containsKey(key);
            V value = super.computeIfPresent(key, remappingFunction);
            if (present) {
                invalidator.run();
            }
            return value;
        }

        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            V value = super.compute(key, remappingFunction);
            invalidator.run();
            return value;
        }

        @Override
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            V result = super.merge(key, value, remappingFunction);
            invalidator.run();
            return result;
        }
    }

    private static final class InvalidatingList<E> extends ArrayList<E> {
        private final Runnable invalidator;

        private InvalidatingList(Collection<? extends E> values, Runnable invalidator) {
            super(values);
            this.invalidator = invalidator;
        }

        @Override
        public boolean add(E value) {
            boolean changed = super.add(value);
            if (changed) {
                invalidator.run();
            }
            return changed;
        }

        @Override
        public void add(int index, E element) {
            super.add(index, element);
            invalidator.run();
        }

        @Override
        public boolean addAll(Collection<? extends E> values) {
            boolean changed = super.addAll(values);
            if (changed) {
                invalidator.run();
            }
            return changed;
        }

        @Override
        public boolean addAll(int index, Collection<? extends E> values) {
            boolean changed = super.addAll(index, values);
            if (changed) {
                invalidator.run();
            }
            return changed;
        }

        @Override
        public E remove(int index) {
            E removed = super.remove(index);
            invalidator.run();
            return removed;
        }

        @Override
        public boolean remove(Object value) {
            boolean changed = super.remove(value);
            if (changed) {
                invalidator.run();
            }
            return changed;
        }

        @Override
        public boolean removeAll(Collection<?> values) {
            boolean changed = super.removeAll(values);
            if (changed) {
                invalidator.run();
            }
            return changed;
        }

        @Override
        public boolean retainAll(Collection<?> values) {
            boolean changed = super.retainAll(values);
            if (changed) {
                invalidator.run();
            }
            return changed;
        }

        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            boolean changed = super.removeIf(filter);
            if (changed) {
                invalidator.run();
            }
            return changed;
        }

        @Override
        public void clear() {
            if (!isEmpty()) {
                super.clear();
                invalidator.run();
            }
        }

        @Override
        public E set(int index, E element) {
            E previous = super.set(index, element);
            invalidator.run();
            return previous;
        }

        @Override
        public void replaceAll(UnaryOperator<E> operator) {
            if (!isEmpty()) {
                super.replaceAll(operator);
                invalidator.run();
            }
        }

        @Override
        public void sort(Comparator<? super E> comparator) {
            super.sort(comparator);
            invalidator.run();
        }
    }
}
