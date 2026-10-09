package restudio.resync.flow;

import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CompiledGraphMetadata(
    ServerResourceLocator resource,
    CatalogBinding catalogBinding,
    SnapshotId snapshotId,
    Map<String, NodeInstanceId> nodeInstances,
    Map<String, ContractRef<NodeId>> definitions,
    Map<PinAddress, PinId> pins,
    Map<ConnectionAddress, ConnectionId> connections,
    Map<PinAddress, TypedValue> inputValues,
    Map<String, String> handlerConfigCanonical,
    FunctionSourceDocument functionSource
) {
    private static final OwnerId RESOURCE_OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command", "custom_content");

    public CompiledGraphMetadata {
        resource = Objects.requireNonNull(resource, "Compiled Graph Resource Is Required");
        if (!RESOURCE_OWNER.equals(resource.owner()) || !GRAPH_TYPES.contains(resource.resourceType().value())) {
            throw new IllegalArgumentException("Compiled Graph Resource Must Use The Authoritative Owner And Type");
        }
        catalogBinding = Objects.requireNonNull(catalogBinding, "Compiled Graph Catalog Binding Is Required");
        snapshotId = Objects.requireNonNull(snapshotId, "Compiled Graph Snapshot ID Is Required");
        nodeInstances = immutableTextMap(nodeInstances, "Node Instance Metadata", NodeInstanceId.class);
        definitions = immutableDefinitionMap(definitions);
        pins = immutablePinMap(pins, "Pin Metadata");
        connections = immutableConnectionMap(connections, "Connection Metadata");
        inputValues = immutableInputValues(inputValues);
        handlerConfigCanonical = immutableHandlerConfigMap(handlerConfigCanonical);
        if (functionSource != null && !functionSource.graph().resource().equals(resource)) {
            throw new IllegalArgumentException("Function Source Metadata Must Match The Compiled Graph Resource");
        }
        if (functionSource != null && !"function".equals(resource.resourceType().value())) {
            throw new IllegalArgumentException("Function Source Metadata Requires The Function Resource Type");
        }
        requireUniqueValues(nodeInstances, "Node Instance Metadata");
        requireUniqueValues(connections, "Connection Metadata");
    }

    public CompiledGraphMetadata(
        ServerResourceLocator resource,
        CatalogBinding catalogBinding,
        SnapshotId snapshotId,
        Map<String, NodeInstanceId> nodeInstances,
        Map<String, ContractRef<NodeId>> definitions,
        Map<PinAddress, PinId> pins,
        Map<ConnectionAddress, ConnectionId> connections
    ) {
        this(resource, catalogBinding, snapshotId, nodeInstances, definitions, pins, connections, Map.of(), Map.of(), null);
    }

    public CompiledGraphMetadata(
        ServerResourceLocator resource,
        CatalogBinding catalogBinding,
        SnapshotId snapshotId,
        Map<String, NodeInstanceId> nodeInstances,
        Map<String, ContractRef<NodeId>> definitions,
        Map<PinAddress, PinId> pins,
        Map<ConnectionAddress, ConnectionId> connections,
        Map<PinAddress, TypedValue> inputValues
    ) {
        this(resource, catalogBinding, snapshotId, nodeInstances, definitions, pins, connections, inputValues, Map.of(), null);
    }

    public CompiledGraphMetadata(
        ServerResourceLocator resource,
        CatalogBinding catalogBinding,
        SnapshotId snapshotId,
        Map<String, NodeInstanceId> nodeInstances,
        Map<String, ContractRef<NodeId>> definitions,
        Map<PinAddress, PinId> pins,
        Map<ConnectionAddress, ConnectionId> connections,
        Map<PinAddress, TypedValue> inputValues,
        Map<String, String> handlerConfigCanonical
    ) {
        this(resource, catalogBinding, snapshotId, nodeInstances, definitions, pins, connections,
            inputValues, handlerConfigCanonical, null);
    }

    private static <T> Map<String, T> immutableTextMap(Map<String, T> values, String label, Class<?> valueType) {
        Objects.requireNonNull(values, label + " Is Required");
        List<Map.Entry<String, T>> entries = new ArrayList<>();
        values.forEach((key, value) -> {
            requireText(key, label + " Keys");
            if (value == null || !valueType.isInstance(value)) {
                throw new IllegalArgumentException(label + " Values Have An Invalid Type");
            }
            entries.add(Map.entry(key, value));
        });
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<String, T> ordered = new LinkedHashMap<>();
        entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(ordered);
    }

    private static Map<PinAddress, PinId> immutablePinMap(Map<PinAddress, PinId> values, String label) {
        Objects.requireNonNull(values, label + " Is Required");
        List<Map.Entry<PinAddress, PinId>> entries = new ArrayList<>();
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, label + " Keys Cannot Be Null");
            Objects.requireNonNull(value, label + " Values Cannot Be Null");
            if (!((Object) value instanceof PinId)) {
                throw new IllegalArgumentException(label + " Values Have An Invalid Type");
            }
            entries.add(Map.entry(key, value));
        });
        entries.sort(Map.Entry.comparingByKey(Comparator.comparing(PinAddress::canonicalText)));
        LinkedHashMap<PinAddress, PinId> ordered = new LinkedHashMap<>();
        entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(ordered);
    }

    private static Map<ConnectionAddress, ConnectionId> immutableConnectionMap(Map<ConnectionAddress, ConnectionId> values, String label) {
        Objects.requireNonNull(values, label + " Is Required");
        List<Map.Entry<ConnectionAddress, ConnectionId>> entries = new ArrayList<>();
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, label + " Keys Cannot Be Null");
            Objects.requireNonNull(value, label + " Values Cannot Be Null");
            if (!((Object) value instanceof ConnectionId)) {
                throw new IllegalArgumentException(label + " Values Have An Invalid Type");
            }
            entries.add(Map.entry(key, value));
        });
        entries.sort(Map.Entry.comparingByKey(Comparator.comparing(ConnectionAddress::canonicalText)));
        LinkedHashMap<ConnectionAddress, ConnectionId> ordered = new LinkedHashMap<>();
        entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(ordered);
    }

    private static Map<PinAddress, TypedValue> immutableInputValues(Map<PinAddress, TypedValue> values) {
        Objects.requireNonNull(values, "Typed Input Metadata Is Required");
        List<Map.Entry<PinAddress, TypedValue>> entries = new ArrayList<>();
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, "Typed Input Metadata Keys Cannot Be Null");
            Objects.requireNonNull(value, "Typed Input Metadata Values Cannot Be Null");
            entries.add(Map.entry(key, value));
        });
        entries.sort(Map.Entry.comparingByKey(Comparator.comparing(PinAddress::canonicalText)));
        LinkedHashMap<PinAddress, TypedValue> ordered = new LinkedHashMap<>();
        entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(ordered);
    }

    private static Map<String, ContractRef<NodeId>> immutableDefinitionMap(Map<String, ContractRef<NodeId>> values) {
        Objects.requireNonNull(values, "Definition Metadata Is Required");
        List<Map.Entry<String, ContractRef<NodeId>>> entries = new ArrayList<>();
        values.forEach((key, value) -> {
            requireText(key, "Definition Metadata Keys");
            Object rawValue = value;
            if (rawValue == null || !(rawValue instanceof ContractRef<?> reference)) {
                throw new IllegalArgumentException("Definition Metadata Values Have An Invalid Type");
            }
            if (!(reference.id() instanceof NodeId)) {
                throw new IllegalArgumentException("Definition Metadata Values Have An Invalid Type");
            }
            entries.add(Map.entry(key, value));
        });
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<String, ContractRef<NodeId>> ordered = new LinkedHashMap<>();
        entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(ordered);
    }

    private static Map<String, String> immutableHandlerConfigMap(Map<String, String> values) {
        Objects.requireNonNull(values, "Handler Configuration Metadata Is Required");
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        values.forEach((key, value) -> {
            requireText(key, "Handler Configuration Metadata Keys");
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Handler Configuration Metadata Values Must Be Canonical JSON");
            }
            try {
                CanonicalJson.parse(value);
                if (!value.equals(CanonicalJson.canonicalize(CanonicalJson.parse(value)))) {
                    throw new IllegalArgumentException("Handler Configuration Metadata Values Must Be Canonical JSON");
                }
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Handler Configuration Metadata Values Must Be Canonical JSON", failure);
            }
            entries.add(Map.entry(key, value));
        });
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(ordered);
    }

    private static <K, V> void requireUniqueValues(Map<K, V> values, String label) {
        if (values.size() != values.values().stream().distinct().count()) {
            throw new IllegalArgumentException(label + " Values Must Be Unique");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(label + " Must Be Canonical Non-Blank Text");
        }
    }

    public record PinAddress(String nodeId, String pin) implements Comparable<PinAddress> {
        public PinAddress {
            requireText(nodeId, "Pin Metadata Node IDs");
            requireText(pin, "Pin Metadata Pins");
        }

        public String canonicalText() {
            return nodeId + "\u0000" + pin;
        }

        @Override
        public int compareTo(PinAddress other) {
            return canonicalText().compareTo(other.canonicalText());
        }
    }

    public record ConnectionAddress(
        String sourceNodeId,
        String sourcePin,
        String targetNodeId,
        String targetPin,
        RepeatableElementId sourceElementId,
        BranchId sourceBranchId,
        RepeatableElementId targetElementId,
        BranchId targetBranchId
    ) implements Comparable<ConnectionAddress> {
        public ConnectionAddress {
            requireText(sourceNodeId, "Connection Metadata Source Node IDs");
            requireText(sourcePin, "Connection Metadata Source Pins");
            requireText(targetNodeId, "Connection Metadata Target Node IDs");
            requireText(targetPin, "Connection Metadata Target Pins");
        }

        public ConnectionAddress(String sourceNodeId, String sourcePin, String targetNodeId, String targetPin) {
            this(sourceNodeId, sourcePin, targetNodeId, targetPin, null, null, null, null);
        }

        public static ConnectionAddress of(GraphEndpoint source, GraphEndpoint target) {
            return new ConnectionAddress(source.nodeId().canonicalText(), source.pinId().canonicalText(),
                target.nodeId().canonicalText(), target.pinId().canonicalText(), source.elementId(), source.branchId(),
                target.elementId(), target.branchId());
        }

        public String canonicalText() {
            String base = sourceNodeId + "\u0000" + sourcePin + "\u0000" + targetNodeId + "\u0000" + targetPin;
            return sourceElementId == null && sourceBranchId == null && targetElementId == null && targetBranchId == null
                ? base : base + "\u0000" + (sourceElementId == null ? "" : sourceElementId.canonicalText())
                    + "\u0000" + (sourceBranchId == null ? "" : sourceBranchId.canonicalText())
                    + "\u0000" + (targetElementId == null ? "" : targetElementId.canonicalText())
                    + "\u0000" + (targetBranchId == null ? "" : targetBranchId.canonicalText());
        }

        @Override
        public int compareTo(ConnectionAddress other) {
            return canonicalText().compareTo(other.canonicalText());
        }
    }
}
