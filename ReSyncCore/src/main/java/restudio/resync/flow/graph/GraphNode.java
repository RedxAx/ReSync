package restudio.resync.flow.graph;

import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ModeId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypedValue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GraphNode {
    private final NodeInstanceId instanceId;
    private final ContractRef<NodeId> definition;
    private final int definitionVersion;
    private final ModeId modeId;
    private final Map<PinId, PinValue> values;
    private final Map<PinId, PinValue> inspector;
    private final Map<InspectorFieldId, TypedValue> inspectorFields;
    private final List<BranchBinding> branches;
    private final List<RepeatableBinding> repeatables;
    private final InspectorState inspectorState;
    private final double x;
    private final double y;
    private final OpaqueData unknown;

    public GraphNode(NodeInstanceId instanceId, ContractRef<NodeId> definition, int definitionVersion, ModeId modeId,
                     Map<PinId, PinValue> values, Map<?, ?> inspector,
                     List<BranchBinding> branches, List<RepeatableBinding> repeatables,
                     InspectorState inspectorState, double x, double y, OpaqueData unknown) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.definition = Objects.requireNonNull(definition, "definition");
        if (definitionVersion < 1) {
            throw new IllegalArgumentException("Node definition version must be positive");
        }
        this.definitionVersion = definitionVersion;
        this.modeId = modeId;
        this.values = GraphCollections.pinValues(values);
        GraphCollections.InspectorValues inspectorValues = GraphCollections.inspectorValues(inspector);
        this.inspector = inspectorValues.pinValues();
        this.inspectorFields = inspectorValues.fields();
        this.branches = List.copyOf(branches != null ? branches : List.of());
        this.repeatables = List.copyOf(repeatables != null ? repeatables : List.of());
        validateStructuralIds(this.branches, this.repeatables);
        this.inspectorState = inspectorState != null ? inspectorState : InspectorState.empty();
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Node position must be finite");
        }
        this.x = x;
        this.y = y;
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        this.unknown.rejectKnownFields("instanceId", "definition", "definitionVersion", "modeId", "values", "inspector", "branches", "repeatables", "inspectorState", "position");
    }

    public GraphNode(NodeInstanceId instanceId, ContractRef<NodeId> definition, int definitionVersion, Map<PinId, PinValue> values) {
        this(instanceId, definition, definitionVersion, null, values, Map.of(), List.of(), List.of(), InspectorState.empty(), 0, 0, OpaqueData.empty());
    }

    public NodeInstanceId instanceId() {
        return instanceId;
    }

    public ContractRef<NodeId> definition() {
        return definition;
    }

    public int definitionVersion() {
        return definitionVersion;
    }

    public ModeId modeId() {
        return modeId;
    }

    public Map<PinId, PinValue> values() {
        return values;
    }

    public Map<PinId, PinValue> inspector() {
        return inspector;
    }

    public Map<InspectorFieldId, TypedValue> inspectorFields() {
        return inspectorFields;
    }

    public Map<InspectorFieldId, TypedValue> inspectorValues() {
        return inspectorFields;
    }

    public List<BranchBinding> branches() {
        return branches;
    }

    public List<RepeatableBinding> repeatables() {
        return repeatables;
    }

    public InspectorState inspectorState() {
        return inspectorState;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public OpaqueData unknown() {
        return unknown;
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("instanceId", instanceId.canonicalText());
        values.put("definition", definition.canonicalValue());
        values.put("definitionVersion", definitionVersion);
        if (modeId != null) {
            values.put("modeId", modeId.canonicalText());
        }
        values.put("values", PinValue.canonicalValues(this.values));
        LinkedHashMap<String, Object> inspectorValues = new LinkedHashMap<>();
        this.inspector.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> inspectorValues.put(entry.getKey().canonicalText(), entry.getValue().canonicalValue()));
        this.inspectorFields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (inspectorValues.put(entry.getKey().canonicalText(), entry.getValue().canonicalValue()) != null) {
                throw new IllegalArgumentException("Duplicate inspector field identity: " + entry.getKey());
            }
        });
        values.put("inspector", inspectorValues);
        values.put("branches", branches.stream().map(BranchBinding::canonicalValue).toList());
        values.put("repeatables", repeatables.stream().map(RepeatableBinding::canonicalValue).toList());
        values.put("inspectorState", inspectorState.canonicalValue());
        values.put("position", Map.of("x", x, "y", y));
        return OpaqueData.mergeKnownFields(unknown, values);
    }

    private static void validateStructuralIds(List<BranchBinding> branches, List<RepeatableBinding> repeatables) {
        Set<BranchId> branchIds = new HashSet<>();
        branches.forEach(branch -> {
            BranchBinding value = Objects.requireNonNull(branch, "branch");
            if (!branchIds.add(value.branchId())) {
                throw new IllegalArgumentException("Duplicate branch ID: " + value.branchId());
            }
        });
        Set<RepeatableGroupId> groupIds = new HashSet<>();
        repeatables.forEach(repeatable -> {
            RepeatableBinding value = Objects.requireNonNull(repeatable, "repeatable");
            if (!groupIds.add(value.groupId())) {
                throw new IllegalArgumentException("Duplicate repeatable group ID: " + value.groupId());
            }
        });
    }
}
