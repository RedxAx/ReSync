package restudio.resync.flow.graph;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonBoolean;
import restudio.resync.contract.canonical.JsonValue.JsonNull;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ModeId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeValueCodec;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class GraphDocumentCodec implements CanonicalCodec<GraphDocument> {
    public static final GraphDocumentCodec INSTANCE = new GraphDocumentCodec();

    private static final Set<String> GRAPH_FIELDS = Set.of("schemaVersion", "resource", "revision", "catalogBinding",
        "requiredCapabilities", "nodes", "connections", "passthroughs", "variables", "functions");
    private static final Set<String> NODE_FIELDS = Set.of("instanceId", "definition", "definitionVersion", "modeId", "values",
        "inspector", "branches", "repeatables", "inspectorState", "position");
    private static final Set<String> CONNECTION_FIELDS = Set.of("connectionId", "source", "target");
    private static final Set<String> PASSTHROUGH_FIELDS = Set.of("nodeId", "inputPin", "connectionIds");
    private static final Set<String> ENDPOINT_FIELDS = Set.of("nodeId", "pinId", "elementId", "branchId");
    private static final Set<String> PIN_VALUE_FIELDS = Set.of("value");
    private static final Set<String> BRANCH_FIELDS = Set.of("branchId", "selectedCaseId", "cases");
    private static final Set<String> CASE_FIELDS = Set.of("caseId", "values", "inspectorState");
    private static final Set<String> REPEATABLE_FIELDS = Set.of("groupId", "ordered", "elements");
    private static final Set<String> ELEMENT_FIELDS = Set.of("elementId", "values");
    private static final Set<String> INSPECTOR_STATE_FIELDS = Set.of("kind", "state", "fields", "fallback", "draftId", "baseRevision");
    private static final Set<String> VARIABLE_FIELDS = Set.of("variableId", "name", "type", "value");
    private static final Set<String> FUNCTION_FIELDS = Set.of("function", "revision", "inputs", "outputs");
    private static final Set<String> PARAMETER_FIELDS = Set.of("parameterId", "name", "type", "description", "default");

    private GraphDocumentCodec() {
    }

    public static GraphDocumentCodec instance() {
        return INSTANCE;
    }

    @Override
    public JsonObject encode(GraphDocument value) {
        Objects.requireNonNull(value, "Graph document is required");
        rejectLegacyInspectorValues(value);
        return requireObject(JsonValue.fromJava(value.canonicalValue()), "Graph document");
    }

    @Override
    public GraphDocument decode(JsonValue value) {
        GraphDocument decoded = decodeDocument(requireObject(value, "Graph document"));
        String expected = encode(decoded).canonicalText();
        if (!expected.equals(value.canonicalText())) {
            throw new IllegalArgumentException("Graph document is not in the exact canonical shape");
        }
        return decoded;
    }

    private static void rejectLegacyInspectorValues(GraphDocument graph) {
        for (GraphNode node : graph.nodes()) {
            if (!node.inspector().isEmpty()) {
                throw new IllegalArgumentException("Core graph codec does not encode legacy pin inspector values");
            }
            rejectLegacyInspectorState(node.inspectorState());
            for (BranchBinding branch : node.branches()) {
                for (BranchCase branchCase : branch.cases()) {
                    rejectLegacyInspectorState(branchCase.inspectorState());
                }
            }
        }
    }

    private static void rejectLegacyInspectorState(InspectorState state) {
        if (!state.legacyFields().isEmpty()) {
            throw new IllegalArgumentException("Core graph codec does not encode legacy pin inspector values");
        }
    }

    private static GraphDocument decodeDocument(JsonObject object) {
        CatalogVersion schemaVersion = decodeCatalogVersion(required(object, "schemaVersion"));
        ServerResourceLocator resource = IdentityCodec.decodeLocator(required(object, "resource"));
        long revision = longValue(required(object, "revision"), "revision", 0, Long.MAX_VALUE);
        CatalogBinding catalogBinding = decodeCatalogBinding(required(object, "catalogBinding"));
        Set<ContractRef<CapabilityId>> requiredCapabilities = decodeCapabilities(required(object, "requiredCapabilities"));
        List<GraphNode> nodes = decodeList(required(object, "nodes"), GraphDocumentCodec::decodeNode, "nodes");
        List<GraphConnection> connections = decodeList(required(object, "connections"), GraphDocumentCodec::decodeConnection, "connections");
        List<GraphPassthrough> passthroughs = optionalList(object, "passthroughs", GraphDocumentCodec::decodePassthrough);
        List<GraphVariable> variables = optionalList(object, "variables", GraphDocumentCodec::decodeVariable);
        List<FunctionBinding> functions = optionalList(object, "functions", GraphDocumentCodec::decodeFunction);
        Set<ServerResourceLocator> functionIds = new HashSet<>();
        for (FunctionBinding function : functions) {
            if (!functionIds.add(function.function())) {
                throw new IllegalArgumentException("Duplicate function binding identity: " + function.function());
            }
        }
        return new GraphDocument(schemaVersion, resource, revision, catalogBinding, requiredCapabilities, nodes, connections,
            passthroughs, variables, functions, OpaqueData.of(unknown(object, GRAPH_FIELDS)));
    }

    private static CatalogVersion decodeCatalogVersion(JsonValue value) {
        JsonObject object = requireObject(value, "schemaVersion");
        int generation = intValue(required(object, "generation"), "schemaVersion.generation", 1, Integer.MAX_VALUE);
        int minor = intValue(required(object, "minor"), "schemaVersion.minor", 0, Integer.MAX_VALUE);
        rejectUnexpected(object, Set.of("generation", "minor"), "schemaVersion", false);
        return new CatalogVersion(generation, minor);
    }

    private static CatalogBinding decodeCatalogBinding(JsonValue value) {
        JsonObject object = requireObject(value, "catalogBinding");
        long generation = longValue(required(object, "generation"), "catalogBinding.generation", 1, Long.MAX_VALUE);
        ContentHash catalogChecksum = new ContentHash(string(required(object, "catalogChecksum"), "catalogBinding.catalogChecksum"));
        ContentHash bindingManifestHash = new ContentHash(string(required(object, "bindingManifestHash"), "catalogBinding.bindingManifestHash"));
        rejectUnexpected(object, Set.of("generation", "catalogChecksum", "bindingManifestHash"), "catalogBinding", false);
        return new CatalogBinding(generation, catalogChecksum, bindingManifestHash);
    }

    private static Set<ContractRef<CapabilityId>> decodeCapabilities(JsonValue value) {
        JsonArray array = requireArray(value, "requiredCapabilities");
        LinkedHashSet<ContractRef<CapabilityId>> result = new LinkedHashSet<>();
        for (JsonValue member : array.values()) {
            ContractRef<CapabilityId> capability = IdentityCodec.decodeReference(member, CapabilityId::new);
            if (!result.add(capability)) {
                throw new IllegalArgumentException("Duplicate required capability identity");
            }
        }
        return Set.copyOf(result);
    }

    private static GraphNode decodeNode(JsonValue value) {
        JsonObject object = requireObject(value, "node");
        NodeInstanceId instanceId = NodeInstanceId.parseCanonicalText(string(required(object, "instanceId"), "node.instanceId"));
        ContractRef<NodeId> definition = IdentityCodec.decodeReference(required(object, "definition"), NodeId::new);
        int definitionVersion = intValue(required(object, "definitionVersion"), "node.definitionVersion", 1, Integer.MAX_VALUE);
        ModeId modeId = optionalString(object, "modeId", ModeId::new);
        Map<PinId, PinValue> values = decodePinValues(required(object, "values"), "node.values");
        Map<InspectorFieldId, TypedValue> inspector = decodeInspectorValues(required(object, "inspector"));
        List<BranchBinding> branches = decodeList(required(object, "branches"), GraphDocumentCodec::decodeBranch, "node.branches");
        List<RepeatableBinding> repeatables = decodeList(required(object, "repeatables"), GraphDocumentCodec::decodeRepeatable, "node.repeatables");
        InspectorState inspectorState = decodeInspectorState(required(object, "inspectorState"));
        JsonObject position = requireObject(required(object, "position"), "node.position");
        double x = finiteNumber(required(position, "x"), "node.position.x");
        double y = finiteNumber(required(position, "y"), "node.position.y");
        rejectUnexpected(position, Set.of("x", "y"), "node.position", false);
        return new GraphNode(instanceId, definition, definitionVersion, modeId, values, inspector, branches, repeatables,
            inspectorState, x, y, OpaqueData.of(unknown(object, NODE_FIELDS)));
    }

    private static GraphConnection decodeConnection(JsonValue value) {
        JsonObject object = requireObject(value, "connection");
        ConnectionId connectionId = ConnectionId.parseCanonicalText(string(required(object, "connectionId"), "connection.connectionId"));
        GraphEndpoint source = decodeEndpoint(required(object, "source"));
        GraphEndpoint target = decodeEndpoint(required(object, "target"));
        return new GraphConnection(connectionId, source, target, OpaqueData.of(unknown(object, CONNECTION_FIELDS)));
    }

    private static GraphPassthrough decodePassthrough(JsonValue value) {
        JsonObject object = requireObject(value, "passthrough");
        NodeInstanceId nodeId = NodeInstanceId.parseCanonicalText(string(required(object, "nodeId"), "passthrough.nodeId"));
        PinId inputPin = new PinId(string(required(object, "inputPin"), "passthrough.inputPin"));
        List<ConnectionId> connectionIds = object.contains("connectionIds")
            ? decodeList(required(object, "connectionIds"), member ->
                ConnectionId.parseCanonicalText(string(member, "passthrough.connectionIds member")), "passthrough.connectionIds")
            : List.of();
        return new GraphPassthrough(nodeId, inputPin, connectionIds, OpaqueData.of(unknown(object, PASSTHROUGH_FIELDS)));
    }

    private static GraphEndpoint decodeEndpoint(JsonValue value) {
        JsonObject object = requireObject(value, "endpoint");
        NodeInstanceId nodeId = NodeInstanceId.parseCanonicalText(string(required(object, "nodeId"), "endpoint.nodeId"));
        PinId pinId = new PinId(string(required(object, "pinId"), "endpoint.pinId"));
        RepeatableElementId elementId = optionalString(object, "elementId", RepeatableElementId::parseCanonicalText);
        BranchId branchId = optionalString(object, "branchId", BranchId::new);
        return new GraphEndpoint(nodeId, pinId, elementId, branchId, OpaqueData.of(unknown(object, ENDPOINT_FIELDS)));
    }

    private static GraphVariable decodeVariable(JsonValue value) {
        JsonObject object = requireObject(value, "variable");
        UUID variableId = uuid(string(required(object, "variableId"), "variable.variableId"), "variable.variableId");
        String name = string(required(object, "name"), "variable.name");
        TypeExpr type = TypeValueCodec.INSTANCE.decodeType(required(object, "type"));
        TypedValue typedValue = object.contains("value") ? TypeValueCodec.INSTANCE.decode(required(object, "value")) : null;
        return new GraphVariable(variableId, name, type, typedValue, OpaqueData.of(unknown(object, VARIABLE_FIELDS)));
    }

    private static FunctionBinding decodeFunction(JsonValue value) {
        JsonObject object = requireObject(value, "function binding");
        ServerResourceLocator function = IdentityCodec.decodeLocator(required(object, "function"));
        long revision = longValue(required(object, "revision"), "function.revision", 0, Long.MAX_VALUE);
        List<FunctionParameter> inputs = decodeList(required(object, "inputs"), GraphDocumentCodec::decodeParameter, "function.inputs");
        List<FunctionParameter> outputs = decodeList(required(object, "outputs"), GraphDocumentCodec::decodeParameter, "function.outputs");
        Set<FunctionParameterId> parameterIds = new HashSet<>();
        inputs.forEach(parameter -> addParameterId(parameterIds, parameter));
        outputs.forEach(parameter -> addParameterId(parameterIds, parameter));
        return new FunctionBinding(function, revision, inputs, outputs, OpaqueData.of(unknown(object, FUNCTION_FIELDS)));
    }

    private static void addParameterId(Set<FunctionParameterId> ids, FunctionParameter parameter) {
        if (!ids.add(parameter.parameterId())) {
            throw new IllegalArgumentException("Duplicate function parameter identity: " + parameter.parameterId());
        }
    }

    private static FunctionParameter decodeParameter(JsonValue value) {
        JsonObject object = requireObject(value, "function parameter");
        FunctionParameterId parameterId = FunctionParameterId.parseCanonicalText(string(required(object, "parameterId"), "parameter.parameterId"));
        String name = string(required(object, "name"), "parameter.name");
        TypeExpr type = TypeValueCodec.INSTANCE.decodeType(required(object, "type"));
        String description = string(required(object, "description"), "parameter.description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("parameter.description must not be blank");
        }
        TypedValue defaultValue = object.contains("default") ? TypeValueCodec.INSTANCE.decode(required(object, "default")) : null;
        return new FunctionParameter(parameterId, name, type, description, defaultValue, OpaqueData.of(unknown(object, PARAMETER_FIELDS)));
    }

    private static BranchBinding decodeBranch(JsonValue value) {
        JsonObject object = requireObject(value, "branch");
        BranchId branchId = new BranchId(string(required(object, "branchId"), "branch.branchId"));
        CaseId selectedCaseId = new CaseId(string(required(object, "selectedCaseId"), "branch.selectedCaseId"));
        List<BranchCase> cases = decodeList(required(object, "cases"), GraphDocumentCodec::decodeCase, "branch.cases");
        return new BranchBinding(branchId, selectedCaseId, cases, OpaqueData.of(unknown(object, BRANCH_FIELDS)));
    }

    private static BranchCase decodeCase(JsonValue value) {
        JsonObject object = requireObject(value, "branch case");
        CaseId caseId = new CaseId(string(required(object, "caseId"), "case.caseId"));
        Map<PinId, PinValue> values = decodePinValues(required(object, "values"), "case.values");
        InspectorState state = decodeInspectorState(required(object, "inspectorState"));
        return new BranchCase(caseId, values, state, OpaqueData.of(unknown(object, CASE_FIELDS)));
    }

    private static RepeatableBinding decodeRepeatable(JsonValue value) {
        JsonObject object = requireObject(value, "repeatable binding");
        RepeatableGroupId groupId = new RepeatableGroupId(string(required(object, "groupId"), "repeatable.groupId"));
        boolean ordered = bool(required(object, "ordered"), "repeatable.ordered");
        List<RepeatableElement> elements = decodeList(required(object, "elements"), GraphDocumentCodec::decodeElement, "repeatable.elements");
        return new RepeatableBinding(groupId, ordered, elements, OpaqueData.of(unknown(object, REPEATABLE_FIELDS)));
    }

    private static RepeatableElement decodeElement(JsonValue value) {
        JsonObject object = requireObject(value, "repeatable element");
        RepeatableElementId elementId = RepeatableElementId.parseCanonicalText(string(required(object, "elementId"), "element.elementId"));
        Map<PinId, PinValue> values = decodePinValues(required(object, "values"), "element.values");
        return new RepeatableElement(elementId, values, OpaqueData.of(unknown(object, ELEMENT_FIELDS)));
    }

    private static Map<PinId, PinValue> decodePinValues(JsonValue value, String name) {
        JsonObject object = requireObject(value, name);
        LinkedHashMap<PinId, PinValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.fields().entrySet()) {
            PinId pinId = new PinId(entry.getKey());
            PinValue pinValue = decodePinValue(entry.getKey(), entry.getValue());
            if (!resultMappingPut(result, pinId, pinValue)) {
                throw new IllegalArgumentException("Duplicate pin value identity: " + pinId);
            }
        }
        return Map.copyOf(result);
    }

    private static boolean resultMappingPut(Map<PinId, PinValue> result, PinId pinId, PinValue value) {
        return result.put(pinId, value) == null;
    }

    private static PinValue decodePinValue(String pinIdText, JsonValue value) {
        JsonObject object = requireObject(value, "pin value");
        TypedValue typed = TypeValueCodec.INSTANCE.decode(required(object, "value"));
        return new PinValue(new PinId(pinIdText), typed, OpaqueData.of(unknown(object, PIN_VALUE_FIELDS)));
    }

    private static Map<InspectorFieldId, TypedValue> decodeInspectorValues(JsonValue value) {
        JsonObject object = requireObject(value, "inspector");
        LinkedHashMap<InspectorFieldId, TypedValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.fields().entrySet()) {
            String key = entry.getKey();
            JsonObject field = requireObject(entry.getValue(), "inspector field");
            if (!field.contains("state") || !field.contains("type")) {
                throw new IllegalArgumentException("Inspector fields must use typed InspectorFieldId values");
            }
            result.put(InspectorFieldId.of(key), TypeValueCodec.INSTANCE.decode(field));
        }
        return result;
    }

    private static InspectorState decodeInspectorState(JsonValue value) {
        JsonObject object = requireObject(value, "inspector state");
        if (!"inspector-state".equals(string(required(object, "kind"), "inspectorState.kind"))) {
            throw new IllegalArgumentException("inspectorState.kind must be inspector-state");
        }
        InspectorState.State state = parseInspectorState(string(required(object, "state"), "inspectorState.state"));
        Map<InspectorFieldId, TypedValue> fields = decodeInspectorValues(required(object, "fields"));
        InspectorState.Fallback fallback = parseFallback(string(required(object, "fallback"), "inspectorState.fallback"));
        UUID draftId = optionalString(object, "draftId", valueText -> uuid(valueText, "inspectorState.draftId"));
        Long baseRevision = object.contains("baseRevision") ? longValue(required(object, "baseRevision"), "inspectorState.baseRevision", 0, Long.MAX_VALUE) : null;
        return new InspectorState(state, fields, fallback, draftId, baseRevision, OpaqueData.of(unknown(object, INSPECTOR_STATE_FIELDS)));
    }

    private static InspectorState.Fallback parseFallback(String value) {
        return switch (value) {
            case "editable" -> InspectorState.Fallback.EDITABLE;
            case "read-only-field" -> InspectorState.Fallback.READ_ONLY_FIELD;
            case "read-only-node" -> InspectorState.Fallback.READ_ONLY_NODE;
            case "read-only-graph" -> InspectorState.Fallback.READ_ONLY_GRAPH;
            default -> throw new IllegalArgumentException("Invalid inspectorState.fallback: " + value);
        };
    }

    private static <T> List<T> decodeList(JsonValue value, Function<JsonValue, T> decoder, String name) {
        JsonArray array = requireArray(value, name);
        ArrayList<T> result = new ArrayList<>(array.values().size());
        for (JsonValue member : array.values()) {
            result.add(Objects.requireNonNull(decoder.apply(member), name + " member"));
        }
        return List.copyOf(result);
    }

    private static <T> List<T> optionalList(JsonObject object, String field, Function<JsonValue, T> decoder) {
        return object.contains(field) ? decodeList(required(object, field), decoder, field) : List.of();
    }

    private static Map<String, Object> unknown(JsonObject object, Set<String> known) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> {
            if (!known.contains(key)) {
                values.put(key, value.toJava());
            }
        });
        return values;
    }

    private static void rejectUnexpected(JsonObject object, Set<String> known, String name, boolean allowUnknown) {
        if (!allowUnknown) {
            Set<String> unexpected = new LinkedHashSet<>(object.fields().keySet());
            unexpected.removeAll(known);
            if (!unexpected.isEmpty()) {
                throw new IllegalArgumentException(name + " contains unknown fields: " + unexpected);
            }
        }
    }

    private static JsonValue required(JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required JSON field is missing: " + field);
        }
        if (value instanceof JsonNull) {
            throw new IllegalArgumentException("Required JSON field cannot be null: " + field);
        }
        return value;
    }

    private static JsonObject requireObject(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static JsonArray requireArray(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonArray array)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return array;
    }

    private static String string(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonString string)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return string.value();
    }

    private static boolean bool(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonBoolean booleanValue)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return booleanValue.value();
    }

    private static int intValue(JsonValue value, String name, int minimum, int maximum) {
        long number = longValue(value, name, minimum, maximum);
        return Math.toIntExact(number);
    }

    private static long longValue(JsonValue value, String name, long minimum, long maximum) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonNumber number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        BigDecimal decimal = number.value();
        if (decimal.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            long result = decimal.longValueExact();
            if (result < minimum || result > maximum) {
                throw new IllegalArgumentException(name + " is outside its allowed range");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static double finiteNumber(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonNumber number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        double result = number.value().doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return result;
    }

    private static UUID uuid(String value, String name) {
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equals(value)) {
                throw new IllegalArgumentException(name + " must be canonical");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be a canonical UUID", exception);
        }
    }

    private static InspectorState.State parseInspectorState(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (InspectorState.State candidate : InspectorState.State.values()) {
            if (candidate.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Invalid inspectorState.state: " + value);
    }

    private static <T> T optionalString(JsonObject object, String field, Function<String, T> factory) {
        if (!object.contains(field)) {
            return null;
        }
        return factory.apply(string(required(object, field), field));
    }

}
