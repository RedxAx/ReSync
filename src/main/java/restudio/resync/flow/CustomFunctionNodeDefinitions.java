package restudio.resync.flow;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.Log;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypeValueCodec;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class CustomFunctionNodeDefinitions {
    public static final String PLUGIN_ID = "custom_functions";
    public static final String NODE_PREFIX = "custom_function:";
    public static final String FUNCTION_DEFAULTS = "functionDefaults";
    public static final String FUNCTION_TYPES = "functionTypes";

    private CustomFunctionNodeDefinitions() {
    }

    public static List<NodeDefinition> rebuild(NodeDefinitionRegistry definitionRegistry, FlowStorage storage) {
        if (definitionRegistry == null || storage == null) {
            return List.of();
        }

        definitionRegistry.unregisterPlugin(PLUGIN_ID);
        List<NodeDefinition> definitions = new ArrayList<>();
        Set<String> flowIds = new HashSet<>(storage.listGraphIds("function"));
        flowIds.removeIf(flowId -> flowId == null || flowId.isBlank());
        List<String> sortedFlowIds = new ArrayList<>(flowIds);
        sortedFlowIds.sort(String.CASE_INSENSITIVE_ORDER);

        for (String flowId : sortedFlowIds) {
            try {
                FunctionSourceDocument source = storage.getCoreGraph("function", flowId)
                    .map(CoreGraphStorageBoundary.Decoded::functionSourceDocument).orElse(null);
                if (source == null) {
                    continue;
                }
                definitions.add(buildDefinition(source));
            } catch (RuntimeException exception) {
                Log.warn("Failed to advertise custom function " + flowId + ": " + exception.getMessage());
            }
        }

        definitionRegistry.registerAll(PLUGIN_ID, definitions);
        return definitions;
    }

    public static NodeDefinition buildDefinition(FlowGraph graph) {
        return buildDefinition(graph, Map.of(), true, false);
    }

    public static NodeDefinition buildDefinition(FunctionSourceDocument source) {
        Objects.requireNonNull(source, "A typed Function source is required");
        FlowGraph header = new FlowGraph();
        header.setId(source.signature().function().resource().id());
        header.setFunction(true);
        String owner = source.signature().function().resource().owner().value();
        Object declaredOwner = metadata(source, "functionOwner");
        if (declaredOwner != null) {
            owner = OwnerId.of(metadataText(declaredOwner, "functionOwner")).value();
        }
        header.setFunctionOwner(owner);
        Object namespace = metadata(source, "functionNamespace");
        if (namespace != null) {
            header.setFunctionNamespace(metadataText(namespace, "functionNamespace"));
        }
        Object version = metadata(source, "functionVersion");
        if (version != null) {
            if (!(version instanceof Number)) {
                throw new IllegalArgumentException("Function metadata version must be a positive integer");
            }
            int value;
            try {
                value = new BigDecimal(version.toString()).intValueExact();
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new IllegalArgumentException("Function metadata version must be a positive integer", exception);
            }
            if (value < 1) {
                throw new IllegalArgumentException("Function metadata version must be a positive integer");
            }
            header.setFunctionVersion(value);
        }
        Object description = metadata(source, "functionDescription");
        if (description != null) {
            header.setFunctionDescription(metadataText(description, "functionDescription"));
        }
        header.setFunctionInputs(parameters(source.signature().inputs()));
        header.setFunctionOutputs(parameters(source.signature().outputs()));
        boolean authenticateReQuest = declaredOwner != null && namespace != null && version != null && requestParameterTypes(source);
        return buildDefinition(header, source.signature().parameters(), authenticateReQuest, true);
    }

    private static boolean requestParameterTypes(FunctionSourceDocument source) {
        Set<String> expected = Set.of(TypeExpr.named(TypeReference.of("builtin", "player")).canonicalJson(),
            TypeExpr.named(TypeReference.of("builtin", "string")).canonicalJson());
        return source.signature().inputs().size() == 2
            && expected.equals(source.signature().inputs().stream().map(parameter -> parameter.type().canonicalJson()).collect(Collectors.toSet()));
    }

    private static Object metadata(FunctionSourceDocument source, String key) {
        Object signature = source.signature().unknown().get(key);
        Object document = source.unknown().fields().get(key);
        if (signature != null && document != null && !signature.equals(document)) {
            throw new IllegalArgumentException("Conflicting Function metadata: " + key);
        }
        return signature != null ? signature : document;
    }

    private static String metadataText(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())) {
            throw new IllegalArgumentException("Function metadata " + name + " must be nonblank text without surrounding whitespace");
        }
        return text;
    }

    private static List<FlowGraph.FunctionParameter> parameters(List<FunctionParameterContract> parameters) {
        return parameters.stream().map(parameter -> {
            FlowTypeRef type = parameterType(parameter.type());
            String name = parameter.unknown().containsKey("name")
                ? metadataText(parameter.unknown().get("name"), "parameter name") : parameter.id().canonicalText();
            String widget = parameter.unknown().containsKey("widget")
                ? optionalMetadataText(parameter.unknown().get("widget"), "parameter widget") : "";
            String optionsSource = parameter.unknown().containsKey("optionsSource")
                ? optionalMetadataText(parameter.unknown().get("optionsSource"), "parameter options source") : "";
            return new FlowGraph.FunctionParameter(parameter.id(), name, FlowDataType.fromString(type.getTypeId()),
                widget, optionsSource, parameterDefault(parameter.defaultValue()), type);
        }).toList();
    }

    private static String optionalMetadataText(Object value, String name) {
        if (!(value instanceof String text) || !text.equals(text.strip())) {
            throw new IllegalArgumentException("Function metadata " + name + " must be text without surrounding whitespace");
        }
        return text;
    }

    private static FlowTypeRef parameterType(TypeExpr type) {
        return switch (type) {
            case TypeExpr.Named named -> new FlowTypeRef(typeId(named.reference().ownerId(), named.reference().localId()),
                named.arguments().stream().map(CustomFunctionNodeDefinitions::parameterType).toList());
            case TypeExpr.OptionalType optional -> new FlowTypeRef("optional", List.of(parameterType(optional.element())));
            case TypeExpr.ListType list -> new FlowTypeRef("list", List.of(parameterType(list.element())));
            case TypeExpr.MapType map -> new FlowTypeRef("map", List.of(parameterType(map.key()), parameterType(map.value())));
            case TypeExpr.ResultType result -> new FlowTypeRef("result", List.of(parameterType(result.success()), parameterType(result.failure())));
            case TypeExpr.ResourceType resource -> new FlowTypeRef("resource_reference", List.of(FlowTypeRef.simple(
                typeId(resource.resourceType().ownerId(), resource.resourceType().localId()))));
            case TypeExpr.TupleType ignored -> FlowTypeRef.simple("any");
            case TypeExpr.UnionType ignored -> FlowTypeRef.simple("any");
            case TypeExpr.OpaqueType ignored -> FlowTypeRef.simple("any");
        };
    }

    private static String typeId(String owner, String local) {
        return "builtin".equals(owner) ? local : owner + ':' + local;
    }

    private static String parameterDefault(TypedValue value) {
        if (value == null || value.state() == TypedValue.State.ABSENT || value.state() == TypedValue.State.NULL) {
            return "";
        }
        Object material = value.locator() != null ? value.locator().canonicalText() : value.value();
        return material instanceof String text ? text : material instanceof Boolean
            ? material.toString() : CanonicalJson.canonicalize(material);
    }

    private static NodeDefinition buildDefinition(FlowGraph graph, Map<FunctionParameterId, FunctionParameterContract> contracts,
                                                   boolean authenticateReQuest, boolean typedSource) {
        if (graph == null || graph.getId() == null || graph.getId().isBlank() || !graph.isFunction()) {
            throw new IllegalArgumentException("A callable function with a stable ID is required");
        }
        FlowGraph definitionGraph = graph;
        if (hasLegacyParameters(graph)) {
            definitionGraph = graph.copy().adaptLegacyFunctionParameterIds();
        }
        String flowId = definitionGraph.getId();
        String displayName = toDisplayName(flowId);
        boolean authenticated = authenticateReQuest && ReQuestDescriptorIdentityAdapter.isAuthenticatedFunction(definitionGraph);
        Map<String, Object> handlerConfig = new LinkedHashMap<>();
        handlerConfig.put("operation", CustomFunctionCallHandler.OPERATION);
        handlerConfig.put("functionId", flowId);
        handlerConfig.put("functionOwner", definitionGraph.getFunctionOwner());
        handlerConfig.put("functionNamespace", definitionGraph.getFunctionNamespace());
        if (typedSource) {
            Map<String, Object> types = new LinkedHashMap<>();
            addTypes(types, definitionGraph.getFunctionInputs(), contracts, NodeDefinition.PinDirection.INPUT);
            addTypes(types, definitionGraph.getFunctionOutputs(), contracts, NodeDefinition.PinDirection.OUTPUT);
            handlerConfig.put(FUNCTION_TYPES, Map.copyOf(types));
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        addDefaults(defaults, definitionGraph.getFunctionInputs(), contracts, NodeDefinition.PinDirection.INPUT);
        addDefaults(defaults, definitionGraph.getFunctionOutputs(), contracts, NodeDefinition.PinDirection.OUTPUT);
        if (!defaults.isEmpty()) {
            handlerConfig.put(FUNCTION_DEFAULTS, Map.copyOf(defaults));
        }
        NodeDefinition.Builder builder = new NodeDefinition.Builder(
            NODE_PREFIX + flowId,
            displayName,
            NodeDefinition.NodeCategory.FUNCTION
        ).priority(220)
            .color(NodeDefinition.NodeCategory.FUNCTION)
            .handler(CustomFunctionCallHandler.HANDLER_ID)
            .handlerConfig(Map.copyOf(handlerConfig))
            .owner(definitionGraph.getFunctionOwner())
            .schemaVersion(authenticated ? ReQuestDescriptorIdentityAdapter.FUNCTION_VERSION : 1)
            .description(functionDescription(definitionGraph, displayName))
            .tags(List.of("function", definitionGraph.getFunctionNamespace(), displayName));

        builder.input(new NodeDefinition.PinBuilder(PinId.of("flow"), "flow", NodeDefinition.PinType.FLOW,
            NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION)
            .description("Starts this custom Function call.")
            .build());
        if (definitionGraph.getFunctionInputs() != null) {
            List<FlowGraph.FunctionParameter> inputs = new ArrayList<>(definitionGraph.getFunctionInputs());
            inputs.removeIf(param -> param == null || param.getName() == null || param.getName().isBlank());
            requireUniqueParameterIdentity(inputs, "input");
            inputs.sort(parameterComparator());
            for (FlowGraph.FunctionParameter param : inputs) {
                builder.input(parameterPin(param, NodeDefinition.PinDirection.INPUT, contracts.get(param.getParameterId())));
            }
        }

        builder.output(new NodeDefinition.PinBuilder(PinId.of("output_flow"), "flow", NodeDefinition.PinType.FLOW,
            NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION)
            .runtimeName("flow")
            .description("Continues after this custom Function returns.")
            .build());
        if (definitionGraph.getFunctionOutputs() != null) {
            List<FlowGraph.FunctionParameter> outputs = new ArrayList<>(definitionGraph.getFunctionOutputs());
            outputs.removeIf(param -> param == null || param.getName() == null || param.getName().isBlank());
            requireUniqueParameterIdentity(outputs, "output");
            outputs.sort(parameterComparator());
            for (FlowGraph.FunctionParameter param : outputs) {
                builder.output(parameterPin(param, NodeDefinition.PinDirection.OUTPUT, contracts.get(param.getParameterId())));
            }
        }

        NodeDefinition definition = builder.build();
        return authenticated
            ? ReQuestDescriptorIdentityAdapter.adapt(definition) : definition;
    }

    private static void addDefaults(Map<String, Object> defaults, List<FlowGraph.FunctionParameter> parameters,
                                    Map<FunctionParameterId, FunctionParameterContract> contracts, NodeDefinition.PinDirection direction) {
        if (parameters == null) {
            return;
        }
        for (FlowGraph.FunctionParameter parameter : parameters) {
            FunctionParameterContract contract = parameter != null && parameter.getParameterId() != null
                ? contracts.get(parameter.getParameterId()) : null;
            if (contract != null && contract.defaultValue() != null) {
                Object value = TypeValueCodec.INSTANCE.encode(contract.defaultValue()).toJava();
                if (defaults.putIfAbsent(parameterPinId(parameter, direction).value(), value) != null) {
                    throw new IllegalArgumentException("Duplicate Function default parameter identity: " + parameter.getParameterId());
                }
            }
        }
    }

    private static void addTypes(Map<String, Object> types, List<FlowGraph.FunctionParameter> parameters,
                                 Map<FunctionParameterId, FunctionParameterContract> contracts, NodeDefinition.PinDirection direction) {
        for (FlowGraph.FunctionParameter parameter : parameters) {
            FunctionParameterContract contract = Objects.requireNonNull(contracts.get(parameter.getParameterId()),
                "A typed Function parameter contract is required");
            Object value = TypeValueCodec.INSTANCE.encodeType(contract.type()).toJava();
            if (types.putIfAbsent(parameterPinId(parameter, direction).value(), value) != null) {
                throw new IllegalArgumentException("Duplicate Function type parameter identity: " + parameter.getParameterId());
            }
        }
    }

    public static String parameterKey(FlowGraph.FunctionParameter parameter) {
        if (parameter == null) {
            throw new IllegalArgumentException("Function parameter is required");
        }
        if (parameter.getParameterId() != null) {
            return parameter.getParameterId().canonicalText();
        }
        if (parameter.getName() == null || parameter.getName().isBlank()) {
            throw new IllegalArgumentException("Function parameter name is required");
        }
        return parameter.getName();
    }

    public static String parameterRuntimeName(FlowGraph.FunctionParameter parameter, NodeDefinition.PinDirection direction) {
        PinId pinId = parameterPinId(parameter, direction);
        String name = parameter.getName();
        return isCanonicalRuntimeName(name) ? name : pinId.value();
    }

    public static FlowGraph.FunctionParameter parameterForPin(FlowGraph graph, NodeDefinition.PinDefinition pin,
                                                               NodeDefinition.PinDirection direction) {
        if (pin == null) {
            return null;
        }
        return parameterForKey(graph, pin.getId().value(), direction, false);
    }

    public static FlowGraph.FunctionParameter parameterForKey(FlowGraph graph, String key,
                                                               NodeDefinition.PinDirection direction,
                                                               boolean allowLegacyNames) {
        if (graph == null || key == null || key.isBlank()) {
            return null;
        }
        String normalizedKey = key.strip();
        List<FlowGraph.FunctionParameter> parameters = direction == NodeDefinition.PinDirection.INPUT
            ? graph.getFunctionInputs() : graph.getFunctionOutputs();
        if (parameters == null || parameters.isEmpty()) {
            return null;
        }
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            if (parameter.getParameterId() != null
                && (normalizedKey.equals(parameter.getParameterId().canonicalText())
                    || normalizedKey.equals(parameterPinId(parameter, direction).value()))) {
                return parameter;
            }
            if (parameter.getParameterId() == null) {
                try {
                    if (normalizedKey.equals(parameterPinId(parameter, direction).value())) {
                        return parameter;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (!allowLegacyNames) {
            return null;
        }
        FlowGraph.FunctionParameter match = null;
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter == null || parameter.getName() == null || !normalizedKey.equals(parameter.getName())) {
                continue;
            }
            if (match != null) {
                throw new IllegalArgumentException("Ambiguous custom function "
                    + (direction == NodeDefinition.PinDirection.INPUT ? "input" : "output")
                    + " parameter name: " + normalizedKey);
            }
            match = parameter;
        }
        return match;
    }

    private static void requireUniqueParameterIdentity(List<FlowGraph.FunctionParameter> parameters, String direction) {
        Set<String> ids = new HashSet<>();
        Set<String> runtimeNames = new HashSet<>();
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (!runtimeNames.add(parameter.getName())) {
                throw new IllegalArgumentException("Duplicate function " + direction + " parameter runtime name: " + parameter.getName());
            }
            if (parameter.getParameterId() != null) {
                if (!ids.add(parameter.getParameterId().canonicalText())) {
                    throw new IllegalArgumentException("Duplicate function " + direction + " parameter ID: " + parameter.getParameterId());
                }
            }
        }
    }

    private static Comparator<FlowGraph.FunctionParameter> parameterComparator() {
        return Comparator.comparing(parameter -> parameter.getParameterId() != null
            ? parameter.getParameterId().canonicalText() : parameter.getName(), String.CASE_INSENSITIVE_ORDER);
    }

    private static String functionDescription(FlowGraph graph, String displayName) {
        String authored = prose(graph.getFunctionDescription());
        String displayPlaceholder = "Run " + displayName + ".";
        String idPlaceholder = "Run " + graph.getId() + ".";
        if (!authored.isEmpty() && !authored.equalsIgnoreCase(displayPlaceholder) && !authored.equalsIgnoreCase(idPlaceholder)) {
            return shorten(authored.length() >= 24 ? authored : "Calls " + prose(displayName) + " and waits for completion. " + authored, 280);
        }
        return shorten("Calls " + prose(displayName) + " and continues when it finishes."
            + functionParameters(graph.getFunctionInputs(), "Inputs", " Takes no inputs.")
            + functionParameters(graph.getFunctionOutputs(), "Outputs", " Returns no values."), 280);
    }

    private static String functionParameters(List<FlowGraph.FunctionParameter> parameters, String label, String empty) {
        if (parameters == null || parameters.isEmpty()) {
            return empty;
        }
        String names = parameters.stream().filter(Objects::nonNull).map(FlowGraph.FunctionParameter::getName)
            .filter(Objects::nonNull).filter(name -> !name.isBlank()).map(CustomFunctionNodeDefinitions::prose)
            .collect(Collectors.joining(", "));
        return names.isEmpty() ? empty : " " + label + ": " + shorten(names, 72) + ".";
    }

    private static String toDisplayName(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return "Function";
        }
        String cleaned = flowId.replace('_', ' ').replace('-', ' ').trim();
        if (cleaned.isBlank()) {
            return "Function";
        }
        String[] parts = cleaned.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.isEmpty() ? "Function" : out.toString();
    }

    private static FlowDataType normalizeType(FlowDataType type) {
        if (type == null) {
            return FlowDataType.ANY;
        }
        String id = type.getId();
        if ("map".equals(id) || "set".equals(id) || "queue".equals(id) || "stack".equals(id)) {
            return FlowDataType.ANY;
        }
        return type;
    }

    public static PinId parameterPinId(FlowGraph.FunctionParameter parameter, NodeDefinition.PinDirection direction) {
        if (parameter == null) {
            throw new IllegalArgumentException("Function parameter is required");
        }
        if (parameter.getParameterId() != null) {
            String prefix = direction == NodeDefinition.PinDirection.INPUT ? "function-input-" : "function-output-";
            return PinId.of(prefix + parameter.getParameterId().canonicalText());
        }
        if (parameter.getName() == null || parameter.getName().isBlank()) {
            throw new IllegalArgumentException("Function parameter name is required");
        }
        return PinId.of(parameter.getName());
    }

    private static NodeDefinition.PinDefinition parameterPin(FlowGraph.FunctionParameter parameter, NodeDefinition.PinDirection direction,
                                                              FunctionParameterContract contract) {
        NodeDefinition.PinBuilder builder = new NodeDefinition.PinBuilder(parameterPinId(parameter, direction), parameter.getName(),
            NodeDefinition.PinType.DATA, direction, normalizeType(parameter.getType()))
            .runtimeName(parameterRuntimeName(parameter, direction))
            .typeRef(parameter.getTypeRef())
            .description(parameterDescription(parameter, direction, contract));
        if (contract != null) {
            builder.optional(!contract.required());
        }
        NodeDefinition.WidgetType widget = widget(parameter.getWidget(), parameter.getOptionsSource());
        if (widget != null) {
            builder.widget(widget);
        }
        if (parameter.getOptionsSource() != null && !parameter.getOptionsSource().isBlank()) {
            builder.optionsSource(parameter.getOptionsSource());
        }
        if (parameter.getDefaultValue() != null && !parameter.getDefaultValue().isBlank()) {
            builder.defaultValue(parameter.getDefaultValue());
        }
        return builder.build();
    }

    private static String parameterDescription(FlowGraph.FunctionParameter parameter, NodeDefinition.PinDirection direction,
                                               FunctionParameterContract contract) {
        boolean input = direction == NodeDefinition.PinDirection.INPUT;
        Object metadata = contract != null ? contract.unknown().get("description") : null;
        String authored = metadata instanceof String text ? prose(text) : "";
        String explanation = authored.isEmpty()
            ? (input ? "Passes " : "Returns ") + prose(parameter.getName()) + (input ? " to this Function." : " from this Function.")
            : authored;
        String type = contract != null ? typeDescription(contract.type()) : typeDescription(parameter.getTypeRef());
        String details = " Type: " + shorten(type, 64) + ".";
        if (contract != null) {
            TypedValue value = contract.defaultValue();
            if (value == null || value.state() == TypedValue.State.ABSENT) {
                details += input ? contract.required() ? " An input is required." : " May be omitted. No default value is set."
                    : contract.required() ? " A return value is required." : " The Function may omit this output.";
            } else {
                String rendered = value.state() == TypedValue.State.NULL ? "null"
                    : value.state() == TypedValue.State.OPAQUE ? "the saved default" : defaultDescription(value);
                details += input ? " Uses " + rendered + " when no input is supplied." : " Default: " + rendered + ".";
            }
        }
        return shorten(explanation, 240 - details.length()) + details;
    }

    private static String typeDescription(TypeExpr type) {
        return switch (type) {
            case TypeExpr.Named named -> typeName(named.reference().localId()) + (named.arguments().isEmpty() ? ""
                : " Of " + named.arguments().stream().map(CustomFunctionNodeDefinitions::typeDescription).collect(Collectors.joining(" And ")));
            case TypeExpr.OptionalType optional -> "Optional " + typeDescription(optional.element());
            case TypeExpr.ListType list -> "List Of " + typeDescription(list.element());
            case TypeExpr.MapType map -> "Map From " + typeDescription(map.key()) + " To " + typeDescription(map.value());
            case TypeExpr.ResultType result -> "Success " + typeDescription(result.success()) + " Or Failure " + typeDescription(result.failure());
            case TypeExpr.ResourceType resource -> "Saved " + typeName(resource.resourceType().localId());
            case TypeExpr.TupleType tuple -> "Values (" + tuple.elements().stream().map(CustomFunctionNodeDefinitions::typeDescription).collect(Collectors.joining(", ")) + ")";
            case TypeExpr.UnionType union -> union.variants().stream().map(variant -> typeDescription(variant.type())).distinct().collect(Collectors.joining(" Or "));
            case TypeExpr.OpaqueType opaque -> typeName(opaque.reference().localId());
        };
    }

    private static String typeDescription(FlowTypeRef type) {
        return typeName(type.getTypeId()) + (type.getArguments().isEmpty() ? ""
            : " Of " + type.getArguments().stream().map(CustomFunctionNodeDefinitions::typeDescription).collect(Collectors.joining(" And ")));
    }

    private static String typeName(String id) {
        return switch (id) {
            case "string" -> "Text";
            case "boolean" -> "True Or False";
            case "any" -> "Any Value";
            default -> toDisplayName(id);
        };
    }

    private static String defaultDescription(TypedValue value) {
        Object material = value.locator() != null ? value.locator().id() : value.value();
        String text = CanonicalJson.canonicalize(material).replace(";", "\\u003b").replace("\u2014", "\\u2014");
        return text.length() <= 80 ? text : "the saved default";
    }

    private static String prose(String text) {
        return text != null ? text.replace('\u2014', ',').replace(';', '.').replaceAll("\\s+", " ").strip() : "";
    }

    private static String shorten(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int end = limit - 3;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + "...";
    }

    private static boolean hasLegacyParameters(FlowGraph graph) {
        return (graph.getFunctionInputs() != null && graph.getFunctionInputs().stream()
            .anyMatch(parameter -> parameter != null && parameter.getParameterId() == null))
            || (graph.getFunctionOutputs() != null && graph.getFunctionOutputs().stream()
            .anyMatch(parameter -> parameter != null && parameter.getParameterId() == null));
    }

    private static boolean isCanonicalRuntimeName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return PinId.of(value).value().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static NodeDefinition.WidgetType widget(String widget, String optionsSource) {
        if (widget != null && !widget.isBlank()) {
            try {
                return NodeDefinition.WidgetType.fromSerializedName(widget);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown function parameter widget: " + widget, exception);
            }
        }
        return optionsSource != null && !optionsSource.isBlank() ? NodeDefinition.WidgetType.SEARCHABLE_LIST : null;
    }
}
