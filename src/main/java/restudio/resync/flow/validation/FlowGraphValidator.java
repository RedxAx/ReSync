package restudio.resync.flow.validation;

import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.SchedulePattern;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

public final class FlowGraphValidator {
    private static final String CALL_PARAMETERS_KEY = "__call_parameters";
    private static final Set<String> EXECUTION_CYCLE_BOUNDARY_IDS = Set.of(
        "loop_while",
        "loop.count",
        "loop.for.each",
        "loop.for.each.player",
        "loop.for.each.entity",
        "flow.loop_count",
        "flow.loop_for_each",
        "flow.loop_for_each_player",
        "flow.loop_for_each_entity"
    );
    private static final Set<String> EXECUTION_CYCLE_BOUNDARY_OPERATIONS = Set.of(
        "loop",
        "loop_count",
        "loop_for_each",
        "loop_for_each_player",
        "loop_for_each_entity",
        "loop_interval",
        "loop_while"
    );
    private final NodeDefinitionRegistry definitions;
    private final HandlerRegistry handlers;
    private final TypeAdapterRegistry adapters;
    private final OptionCatalogRegistry catalogs;
    private final FlowResourceRegistry resources;
    private final FlowGraphValidationRegistry extensionValidators;
    private final Clock clock;
    private final BiConsumer<FlowGraph, FlowGraphValidationResult> diagnosticSink;

    public FlowGraphValidator(NodeDefinitionRegistry definitions, HandlerRegistry handlers, TypeAdapterRegistry adapters, OptionCatalogRegistry catalogs) {
        this(definitions, handlers, adapters, catalogs, null);
    }

    public FlowGraphValidator(NodeDefinitionRegistry definitions, HandlerRegistry handlers, TypeAdapterRegistry adapters, OptionCatalogRegistry catalogs,
                              FlowResourceRegistry resources) {
        this(definitions, handlers, adapters, catalogs, resources, null);
    }

    public FlowGraphValidator(NodeDefinitionRegistry definitions, HandlerRegistry handlers, TypeAdapterRegistry adapters, OptionCatalogRegistry catalogs,
                              FlowResourceRegistry resources, FlowGraphValidationRegistry extensionValidators) {
        this(definitions, handlers, adapters, catalogs, resources, extensionValidators, Clock.systemUTC());
    }

    public FlowGraphValidator(NodeDefinitionRegistry definitions, HandlerRegistry handlers, TypeAdapterRegistry adapters, OptionCatalogRegistry catalogs,
                              FlowResourceRegistry resources, FlowGraphValidationRegistry extensionValidators, Clock clock) {
        this(definitions, handlers, adapters, catalogs, resources, extensionValidators, clock, null);
    }

    public FlowGraphValidator(NodeDefinitionRegistry definitions, HandlerRegistry handlers, TypeAdapterRegistry adapters, OptionCatalogRegistry catalogs,
                              FlowResourceRegistry resources, FlowGraphValidationRegistry extensionValidators, Clock clock,
                              BiConsumer<FlowGraph, FlowGraphValidationResult> diagnosticSink) {
        this(definitions, handlers, adapters, catalogs, resources, extensionValidators, clock, diagnosticSink, null);
    }

    public FlowGraphValidator(NodeDefinitionRegistry definitions, HandlerRegistry handlers, TypeAdapterRegistry adapters, OptionCatalogRegistry catalogs,
                              FlowResourceRegistry resources, FlowGraphValidationRegistry extensionValidators, Clock clock,
                              BiConsumer<FlowGraph, FlowGraphValidationResult> diagnosticSink,
                              LegacyRuntimeActivationGate legacyRuntimeGate) {
        this.definitions = definitions;
        this.handlers = handlers;
        this.adapters = adapters;
        this.catalogs = catalogs;
        this.resources = resources;
        this.extensionValidators = extensionValidators;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.diagnosticSink = diagnosticSink;
    }

    public FlowGraphValidationResult validate(FlowGraph graph) {
        List<FlowGraphDiagnostic> diagnostics = new ArrayList<>();
        if (graph == null) {
            diagnostics.add(error("GRAPH_REQUIRED", "", "", "", "Flow graph is required", "Select or create a Flow before saving or executing"));
            return publish(null, new FlowGraphValidationResult(diagnostics));
        }
        String graphId = graph.getId() != null ? graph.getId() : "";
        if (graphId.isBlank()) {
            diagnostics.add(error("GRAPH_ID_REQUIRED", graphId, "", "", "Flow graph ID is required", "Assign a stable Flow ID"));
        }
        if (graph.getVersion() < 1 || graph.getVersion() > FlowGraph.CURRENT_VERSION) {
            diagnostics.add(error("GRAPH_VERSION_UNSUPPORTED", graphId, "", "", "Unsupported Flow graph version: " + graph.getVersion(), "Migrate the Flow to version " + FlowGraph.CURRENT_VERSION));
        }
        validateFunctionSignature(graph, diagnostics);
        Map<String, NodeDefinition> resolved = validateNodes(graph, diagnostics);
        validateConnections(graph, resolved, diagnostics);
        validateDataCycles(graph, resolved, diagnostics);
        validateExecutionStructure(graph, resolved, diagnostics);
        if (extensionValidators != null) {
            diagnostics.addAll(extensionValidators.validate(graph));
        }
        return publish(graph, new FlowGraphValidationResult(diagnostics));
    }

    private FlowGraphValidationResult publish(FlowGraph graph, FlowGraphValidationResult result) {
        if (!result.valid() && diagnosticSink != null) {
            diagnosticSink.accept(graph, result);
        }
        return result;
    }

    private void validateFunctionSignature(FlowGraph graph, List<FlowGraphDiagnostic> diagnostics) {
        if (!graph.isFunction()) {
            return;
        }
        Set<FunctionParameterId> ids = new HashSet<>();
        validateFunctionParameters(graph, "input", graph.getFunctionInputs(), ids, diagnostics);
        validateFunctionParameters(graph, "output", graph.getFunctionOutputs(), ids, diagnostics);
    }

    private void validateFunctionParameters(FlowGraph graph, String direction, List<FlowGraph.FunctionParameter> parameters,
                                            Set<FunctionParameterId> ids, List<FlowGraphDiagnostic> diagnostics) {
        if (parameters == null) {
            diagnostics.add(error("FUNCTION_SIGNATURE_REQUIRED", graph.getId(), "", direction,
                "Function " + direction + " parameters are missing", "Restore the function signature parameter list"));
            return;
        }
        Set<String> legacyNames = new HashSet<>();
        for (int index = 0; index < parameters.size(); index++) {
            FlowGraph.FunctionParameter parameter = parameters.get(index);
            String location = direction + '[' + index + ']';
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                diagnostics.add(error("FUNCTION_PARAMETER_NAME_REQUIRED", graph.getId(), "", location,
                    "Function parameter name is required", "Assign a stable parameter name"));
                continue;
            }
            String name = parameter.getName();
            FunctionParameterId parameterId = parameter.getParameterId();
            if (parameterId != null && !ids.add(parameterId)) {
                diagnostics.add(error("FUNCTION_PARAMETER_ID_DUPLICATE", graph.getId(), "", parameterId.canonicalText(),
                    "Function declares the same " + direction + " parameter ID more than once: " + parameterId,
                    "Keep each function parameter ID unique across inputs and outputs"));
            } else if (parameterId == null && !legacyNames.add(name)) {
                diagnostics.add(error("FUNCTION_PARAMETER_DUPLICATE", graph.getId(), "", name,
                    "Function has more than one " + direction + " parameter named " + name, "Rename or remove the duplicate parameter"));
            }
            String widget = parameter.getWidget();
            if (widget != null && !widget.isBlank()) {
                try {
                    NodeDefinition.WidgetType.fromSerializedName(widget);
                } catch (IllegalArgumentException exception) {
                    diagnostics.add(error("FUNCTION_PARAMETER_WIDGET_INVALID", graph.getId(), "", name,
                        "Unknown function parameter widget: " + widget, "Select a supported schema widget"));
                }
            }
            String optionsSource = parameter.getOptionsSource();
            if (optionsSource != null && !optionsSource.isBlank() && (catalogs == null || catalogs.provider(optionsSource) == null)) {
                diagnostics.add(error("FUNCTION_PARAMETER_CATALOG_UNAVAILABLE", graph.getId(), "", name,
                    "Function parameter catalog is unavailable: " + optionsSource, "Install or enable the catalog capability"));
            }
        }
    }

    private Map<String, NodeDefinition> validateNodes(FlowGraph graph, List<FlowGraphDiagnostic> diagnostics) {
        Map<String, NodeDefinition> resolved = new HashMap<>();
        String graphId = graph.getId();
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            FlowNode node = entry.getValue();
            if (nodeId == null || nodeId.isBlank() || node == null) {
                diagnostics.add(error("NODE_INVALID", graphId, nodeId, "", "Flow contains an unnamed or null node", "Remove the invalid node or assign a stable node ID"));
                continue;
            }
            String type = node.getType();
            if (type == null || type.isBlank()) {
                diagnostics.add(error("NODE_TYPE_REQUIRED", graphId, nodeId, "", "Node type is required", "Select a registered node type"));
                continue;
            }
            String canonicalType = type;
            NodeDefinition definition = definitions != null ? definitions.get(canonicalType) : null;
            if (definition == null) {
                diagnostics.add(error("NODE_DEFINITION_MISSING", graphId, nodeId, "", "Node definition is unavailable: " + type, "Install the required extension or migrate the node"));
                continue;
            }
            definition = functionBoundaryDefinition(graph, canonicalType, definition);
            definition = functionCallDefinition(graph, nodeId, node, canonicalType, definition, diagnostics);
            resolved.put(nodeId, definition);
            if (node.getVersion() < 1 || node.getVersion() > definition.getSchemaVersion()) {
                diagnostics.add(error("NODE_VERSION_UNSUPPORTED", graphId, nodeId, "", "Node version " + node.getVersion() + " is incompatible with schema " + definition.getSchemaVersion(), "Migrate this node to the current schema"));
            }
            if (definition.isHidden() && definition.getHiddenReason().toLowerCase(Locale.ROOT).contains("unsupported")) {
                diagnostics.add(error("NODE_CAPABILITY_UNSUPPORTED", graphId, nodeId, "", "Node capability is unsupported: " + definition.getId(),
                    "Replace this migration-only node with a supported operation"));
                continue;
            }
            validateHandler(graphId, nodeId, definition, diagnostics);
            validateRequiredInputs(graph, nodeId, node, definition, diagnostics);
            validateLiteralInputs(graph, nodeId, node, definition, diagnostics);
            validateDefaults(graph, nodeId, node, definition, diagnostics);
            validateScheduleInputs(graph, nodeId, node, definition, diagnostics);
        }
        return resolved;
    }

    private NodeDefinition functionBoundaryDefinition(FlowGraph graph, String type, NodeDefinition definition) {
        if (!graph.isFunction() || !isFunctionBoundary(type, definition)) {
            return definition;
        }
        List<NodeDefinition.PinDefinition> inputs = new ArrayList<>();
        List<NodeDefinition.PinDefinition> outputs = new ArrayList<>();
        inputs.add(new NodeDefinition.PinDefinition(PinId.of("flow"), "flow", NodeDefinition.PinType.FLOW,
            NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION));
        outputs.add(new NodeDefinition.PinDefinition(PinId.of("flow"), "flow", NodeDefinition.PinType.FLOW,
            NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION));
        List<FlowGraph.FunctionParameter> parameters = isFunctionStart(type, definition) ? graph.getFunctionInputs() : graph.getFunctionOutputs();
        if (parameters != null) {
            for (FlowGraph.FunctionParameter parameter : parameters) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                    continue;
                }
                NodeDefinition.PinDirection direction = isFunctionStart(type, definition)
                    ? NodeDefinition.PinDirection.OUTPUT
                    : NodeDefinition.PinDirection.INPUT;
                NodeDefinition.PinDefinition pin = new NodeDefinition.PinBuilder(
                    CustomFunctionNodeDefinitions.parameterPinId(parameter, direction), parameter.getName(),
                    NodeDefinition.PinType.DATA, direction,
                    parameter.getType() != null ? parameter.getType() : FlowDataType.ANY
                ).runtimeName(CustomFunctionNodeDefinitions.parameterRuntimeName(parameter, direction))
                    .typeRef(parameter.getTypeRef()).build();
                if (direction == NodeDefinition.PinDirection.OUTPUT) {
                    outputs.add(pin);
                } else {
                    inputs.add(pin);
                }
            }
        }
        return definition.withPins(inputs, outputs);
    }

    private NodeDefinition functionCallDefinition(FlowGraph graph, String nodeId, FlowNode node, String type, NodeDefinition definition,
                                                  List<FlowGraphDiagnostic> diagnostics) {
        if (!"call.function".equals(type) && !"call_function".equals(type)) {
            return definition;
        }
        List<FlowGraph.FunctionParameter> declared = functionCallParameters(graph.getId(), nodeId, node, diagnostics);
        boolean dynamic = isInputConnected(graph, nodeId, definition, node, "function");
        Object selected = inputValue(node, definition, "function");
        String functionId = selected != null ? selected.toString().trim() : "";
        NodeDefinition signature = !dynamic && !functionId.isBlank() && definitions != null
            ? definitions.get("custom_function:" + functionId)
            : null;
        if (signature == null && declared.isEmpty()) {
            return definition;
        }
        List<NodeDefinition.PinDefinition> inputs = new ArrayList<>(definition.getInputs());
        List<NodeDefinition.PinDefinition> outputs = new ArrayList<>(definition.getOutputs());
        inputs.removeIf(pin -> "arguments".equals(pin.getName()));
        if (!declared.isEmpty()) {
            for (FlowGraph.FunctionParameter parameter : declared) {
                inputs.add(new NodeDefinition.PinBuilder(
                    CustomFunctionNodeDefinitions.parameterPinId(parameter, NodeDefinition.PinDirection.INPUT), parameter.getName(),
                    NodeDefinition.PinType.DATA,
                    NodeDefinition.PinDirection.INPUT,
                    parameter.getType()
                ).runtimeName(CustomFunctionNodeDefinitions.parameterRuntimeName(parameter, NodeDefinition.PinDirection.INPUT))
                    .typeRef(parameter.getTypeRef()).build());
            }
            if (signature != null) {
                validateFunctionCallContract(graph.getId(), nodeId, declared, signature, diagnostics);
            }
        } else if (signature != null) {
            appendUniquePins(inputs, signature.getInputs(), "flow");
        }
        if (signature != null) {
            appendUniquePins(outputs, signature.getOutputs(), "flow");
        }
        return definition.withPins(inputs, outputs);
    }

    private List<FlowGraph.FunctionParameter> functionCallParameters(String graphId, String nodeId, FlowNode node,
                                                                     List<FlowGraphDiagnostic> diagnostics) {
        if (node.getInputValues() == null || !(node.getInputValues().get(CALL_PARAMETERS_KEY) instanceof Iterable<?> values)) {
            return List.of();
        }
        List<FlowGraph.FunctionParameter> parameters = new ArrayList<>();
        Set<String> legacyNames = new HashSet<>();
        Set<FunctionParameterId> ids = new HashSet<>();
        int index = 0;
        for (Object value : values) {
            String location = CALL_PARAMETERS_KEY + '[' + index++ + ']';
            if (!(value instanceof Map<?, ?> entry) || entry.get("name") == null || entry.get("type") == null) {
                diagnostics.add(error("FUNCTION_CALL_ARGUMENT_INVALID", graphId, nodeId, location,
                    "Function argument needs a name and type", "Remove the invalid argument and add it again"));
                continue;
            }
            String name = entry.get("name").toString().trim();
            Object rawParameterId = entry.get("parameterId");
            FunctionParameterId parameterId = parseFunctionParameterId(rawParameterId, graphId, nodeId, location, diagnostics);
            if (rawParameterId != null && parameterId == null) {
                continue;
            }
            if (parameterId == null && !isTemplateName(name)) {
                diagnostics.add(error("FUNCTION_CALL_ARGUMENT_NAME_INVALID", graphId, nodeId, location,
                    "Function argument name is invalid: " + name, "Use letters, numbers, and underscores"));
                continue;
            }
            if (parameterId != null && !ids.add(parameterId)) {
                diagnostics.add(error("FUNCTION_CALL_ARGUMENT_ID_DUPLICATE", graphId, nodeId, parameterId.canonicalText(),
                    "Function argument is declared more than once with ID " + parameterId,
                    "Use each function parameter ID once"));
                continue;
            }
            if (parameterId == null && !legacyNames.add(name)) {
                diagnostics.add(error("FUNCTION_CALL_ARGUMENT_DUPLICATE", graphId, nodeId, name,
                    "Function argument is declared more than once: " + name, "Remove or rename the duplicate argument"));
                continue;
            }
            FlowTypeRef typeRef;
            try {
                typeRef = FlowTypeRef.parse(entry.get("type").toString()).normalizedGenerics();
            } catch (IllegalArgumentException exception) {
                diagnostics.add(error("FUNCTION_CALL_ARGUMENT_TYPE_INVALID", graphId, nodeId, name,
                    "Function argument type is invalid", "Choose a supported argument type"));
                continue;
            }
            if (!typeRef.isResolved() || containsAnyType(typeRef)) {
                diagnostics.add(error("FUNCTION_CALL_ARGUMENT_TYPE_REQUIRED", graphId, nodeId, name,
                    "Function argument needs a specific type", "Choose the type expected by the called function"));
                continue;
            }
            FlowGraph.FunctionParameter parameter = parameterId == null
                ? new FlowGraph.FunctionParameter(name, FlowDataType.fromString(typeRef.getTypeId()))
                : new FlowGraph.FunctionParameter(parameterId, name, FlowDataType.fromString(typeRef.getTypeId()));
            parameter.setTypeRef(typeRef);
            parameters.add(parameter);
        }
        return parameters;
    }

    private void validateFunctionCallContract(String graphId, String nodeId, List<FlowGraph.FunctionParameter> declared,
                                              NodeDefinition signature, List<FlowGraphDiagnostic> diagnostics) {
        Map<String, NodeDefinition.PinDefinition> expected = new HashMap<>();
        boolean identityBacked = false;
        for (NodeDefinition.PinDefinition pin : signature.getInputs()) {
            if (pin != null && !"flow".equals(pin.getName())) {
                expected.put(pin.getName(), pin);
                identityBacked |= pin.getName().startsWith("function-input-");
            }
        }
        Set<String> declaredKeys = new HashSet<>();
        for (FlowGraph.FunctionParameter parameter : declared) {
            String key = parameter.getParameterId() == null
                ? parameter.getName()
                : CustomFunctionNodeDefinitions.parameterPinId(parameter, NodeDefinition.PinDirection.INPUT).value();
            declaredKeys.add(key);
            NodeDefinition.PinDefinition expectedPin = expected.get(key);
            if (expectedPin == null && !identityBacked && parameter.getParameterId() == null) {
                expectedPin = expected.values().stream()
                    .filter(pin -> parameter.getName().equals(pin.getDisplayName()))
                    .findFirst().orElse(null);
            }
            if (expectedPin == null) {
                diagnostics.add(error("FUNCTION_CALL_ARGUMENT_UNKNOWN", graphId, nodeId, parameter.getName(),
                    "Called function has no input named " + parameter.getName(), "Match the argument names to the function inputs"));
                continue;
            }
            if (!expectedPin.getTypeRef().normalizedGenerics().equals(parameter.getTypeRef().normalizedGenerics())) {
                diagnostics.add(error("FUNCTION_CALL_ARGUMENT_TYPE_MISMATCH", graphId, nodeId, parameter.getName(),
                    "Argument " + parameter.getName() + " is " + parameter.getTypeRef() + " but the function expects " + expectedPin.getTypeRef(),
                    "Choose the same type as the function input"));
            }
        }
        for (NodeDefinition.PinDefinition expectedPin : expected.values()) {
            if (!declaredKeys.contains(expectedPin.getName())) {
                diagnostics.add(error("FUNCTION_CALL_ARGUMENT_MISSING", graphId, nodeId, expectedPin.getDisplayName(),
                    "Function call does not declare " + expectedPin.getDisplayName(), "Add the missing argument with the function input type"));
            }
        }
    }

    private FunctionParameterId parseFunctionParameterId(Object raw, String graphId, String nodeId, String location,
                                                         List<FlowGraphDiagnostic> diagnostics) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof FunctionParameterId parameterId) {
            return parameterId;
        }
        if (raw instanceof Map<?, ?> values) {
            Object nested = values.get("value");
            if (nested == null) {
                nested = values.get("canonicalText");
            }
            raw = nested;
        }
        if (raw == null) {
            diagnostics.add(error("FUNCTION_CALL_ARGUMENT_ID_INVALID", graphId, nodeId, location,
                "Function argument ID is invalid", "Use a canonical function parameter ID"));
            return null;
        }
        try {
            return FunctionParameterId.parseCanonicalText(raw.toString().trim());
        } catch (IllegalArgumentException exception) {
            diagnostics.add(error("FUNCTION_CALL_ARGUMENT_ID_INVALID", graphId, nodeId, location,
                "Function argument ID is invalid", "Use a canonical function parameter ID"));
            return null;
        }
    }

    private boolean containsAnyType(FlowTypeRef typeRef) {
        return "any".equals(typeRef.getTypeId()) || typeRef.getArguments().stream().anyMatch(this::containsAnyType);
    }

    private void appendUniquePins(List<NodeDefinition.PinDefinition> target, List<NodeDefinition.PinDefinition> additions, String excludedName) {
        Set<PinId> ids = new HashSet<>();
        for (NodeDefinition.PinDefinition pin : target) {
            if (pin != null && pin.getId() != null) {
                ids.add(pin.getId());
            }
        }
        for (NodeDefinition.PinDefinition pin : additions) {
            if (pin != null && !excludedName.equals(pin.getName()) && ids.add(pin.getId())) {
                target.add(pin);
            }
        }
    }

    private boolean isFunctionStartType(String type) {
        return FlowRuntime.isFunctionStartType(type);
    }

    private boolean isFunctionEndType(String type) {
        return "function_end".equals(type) || "function.end".equals(type) || "function.function_end".equals(type)
            || "restudio.resync/function_end".equals(type) || "builtin/function.end".equals(type);
    }

    private boolean isFunctionBoundary(String type, NodeDefinition definition) {
        return isFunctionStart(type, definition) || isFunctionEnd(type, definition);
    }

    private boolean isFunctionStart(String type, NodeDefinition definition) {
        if (isFunctionStartType(type)) {
            return true;
        }
        return hasHandlerOperation(definition, "function_start");
    }

    private boolean isFunctionEnd(String type, NodeDefinition definition) {
        if (isFunctionEndType(type)) {
            return true;
        }
        return hasHandlerOperation(definition, "function_end");
    }

    private boolean hasHandlerOperation(NodeDefinition definition, String operation) {
        if (definition == null || operation == null) {
            return false;
        }
        Map<String, Object> handlerConfig = definition.getHandlerConfig();
        return handlerConfig != null && operation.equals(handlerConfig.get("operation"));
    }

    private void validateScheduleInputs(FlowGraph graph, String nodeId, FlowNode node, NodeDefinition definition,
                                        List<FlowGraphDiagnostic> diagnostics) {
        if (!"ScheduleHandler".equals(definition.getHandler()) || definition.getHandlerConfig() == null) {
            return;
        }
        Object configuredOperation = definition.getHandlerConfig().get("operation");
        if (!(configuredOperation instanceof String operation)
            || !Set.of("schedule", "cron", "schedule_at_time").contains(operation)) {
            return;
        }
        boolean zoneConnected = isInputConnected(graph, nodeId, definition, node, "time_zone");
        String zoneValue = effectiveStringInput(graph, nodeId, node, definition, "time_zone");
        ZoneId zoneId;
        try {
            zoneId = zoneValue == null || zoneValue.isBlank() ? ZoneId.of("UTC") : ZoneId.of(zoneValue.trim());
        } catch (RuntimeException exception) {
            diagnostics.add(error("SCHEDULE_ZONE_INVALID", graph.getId(), nodeId, "time_zone", "Unknown time zone: " + zoneValue,
                "Select a valid IANA time zone"));
            return;
        }
        String inputName = switch (operation) {
            case "schedule" -> "time_string";
            case "cron" -> "expression";
            case "schedule_at_time" -> "time";
            default -> throw new IllegalStateException("Unsupported schedule operation: " + operation);
        };
        String value = effectiveStringInput(graph, nodeId, node, definition, inputName);
        if (value == null) {
            return;
        }
        try {
            SchedulePattern pattern = switch (operation) {
                case "schedule" -> SchedulePattern.daily(value, zoneId);
                case "cron" -> SchedulePattern.cron(value, zoneId);
                case "schedule_at_time" -> SchedulePattern.once(value, zoneId);
                default -> throw new IllegalStateException("Unsupported schedule operation: " + operation);
            };
            if ("cron".equals(operation)) {
                pattern.nextAfter(clock.instant());
            } else if ("schedule_at_time".equals(operation) && !zoneConnected && pattern.nextAfter(clock.instant()).isEmpty()) {
                diagnostics.add(error("SCHEDULE_TIME_NOT_FUTURE", graph.getId(), nodeId, inputName,
                    "Scheduled time must be in the future", "Choose an ISO-8601 time after the current instant"));
            }
        } catch (RuntimeException exception) {
            diagnostics.add(error("SCHEDULE_PATTERN_INVALID", graph.getId(), nodeId, inputName, scheduleValidationMessage(exception),
                "Correct the schedule value before saving or executing the Flow"));
        }
    }

    private String effectiveStringInput(FlowGraph graph, String nodeId, FlowNode node, NodeDefinition definition, String pinName) {
        if (isInputConnected(graph, nodeId, definition, node, pinName)) {
            return null;
        }
        Object value = inputValue(node, definition, pinName);
        if (value == null) {
            NodeDefinition.PinDefinition pin = findPin(definition.getInputs(), pinName);
            value = pin != null ? pin.getDefaultValue() : null;
        }
        return value instanceof String string ? string : null;
    }

    private boolean isInputConnected(FlowGraph graph, String nodeId, NodeDefinition definition, FlowNode node, String pinName) {
        if (definition == null) {
            return graph.getConnectionsToTarget(nodeId).stream().anyMatch(connection -> pinName.equals(connection.getTargetPin()));
        }
        NodeDefinition.PinDefinition expected = findInputPin(definition, node, pinName);
        return graph.getConnectionsToTarget(nodeId).stream().anyMatch(connection -> {
            NodeDefinition.PinDefinition actual = findInputPin(definition, node, connection.getTargetPin());
            return expected != null && actual != null && expected.getId().equals(actual.getId());
        });
    }

    private Object inputValue(FlowNode node, NodeDefinition definition, String pinName) {
        Map<String, Object> values = node != null && node.getInputValues() != null ? node.getInputValues() : Map.of();
        NodeDefinition.PinDefinition pin = definition != null ? findPin(definition.getInputs(), pinName) : null;
        if (pin != null) {
            if (values.containsKey(pin.getName())) {
                return values.get(pin.getName());
            }
            if (values.containsKey(pin.getRuntimeName())) {
                return values.get(pin.getRuntimeName());
            }
        }
        return values.get(pinName);
    }

    private boolean hasInputValue(Map<String, Object> values, NodeDefinition definition, String pinName) {
        if (values == null) {
            return false;
        }
        NodeDefinition.PinDefinition pin = definition != null ? findPin(definition.getInputs(), pinName) : null;
        if (pin != null && (values.containsKey(pin.getName()) || values.containsKey(pin.getRuntimeName()))) {
            return true;
        }
        return values.containsKey(pinName);
    }

    private boolean isPinVisible(FlowGraph graph, String nodeId, FlowNode node, NodeDefinition definition, NodeDefinition.PinDefinition pin) {
        Map<String, String> visibleWhen = pin.getVisibleWhen();
        if (visibleWhen == null || visibleWhen.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> condition : visibleWhen.entrySet()) {
            if (isInputConnected(graph, nodeId, definition, node, condition.getKey())) {
                continue;
            }
            Object actualValue = inputValue(node, definition, condition.getKey());
            if (actualValue == null) {
                NodeDefinition.PinDefinition controllingPin = findPin(definition.getInputs(), condition.getKey());
                actualValue = controllingPin != null ? controllingPin.getDefaultValue() : null;
            }
            String expectedValues = condition.getValue();
            if (expectedValues == null || expectedValues.isBlank()) {
                return false;
            }
            boolean matches = false;
            for (String expected : expectedValues.split(",")) {
                if (matchesVisibleValue(actualValue, expected.trim())) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesVisibleValue(Object actualValue, String expected) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        if (actualValue instanceof Iterable<?> values) {
            for (Object value : values) {
                if (value != null && matchesVisibleValue(value, expected)) {
                    return true;
                }
            }
            return false;
        }
        String actual = actualValue != null ? actualValue.toString().trim() : "";
        if (expected.endsWith("*")) {
            String prefix = expected.substring(0, expected.length() - 1);
            return actual.regionMatches(true, 0, prefix, 0, prefix.length());
        }
        return actual.equalsIgnoreCase(expected);
    }

    private boolean isPinVisibilityDynamic(FlowGraph graph, String nodeId, FlowNode node, NodeDefinition definition,
                                           NodeDefinition.PinDefinition pin) {
        Map<String, String> visibleWhen = pin.getVisibleWhen();
        return visibleWhen != null && visibleWhen.keySet().stream().anyMatch(pinName ->
            isInputConnected(graph, nodeId, definition, node, pinName));
    }

    private String scheduleValidationMessage(RuntimeException exception) {
        return exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : "Schedule value is invalid";
    }

    private void validateHandler(String graphId, String nodeId, NodeDefinition definition, List<FlowGraphDiagnostic> diagnostics) {
        if (definition.isTrigger()) {
            return;
        }
        String handler = definition.getHandler();
        if (handler == null || handler.isBlank() || handlers == null || !handlers.hasHandler(handler)) {
            diagnostics.add(error("HANDLER_UNAVAILABLE", graphId, nodeId, "", "Handler is unavailable: " + handler, "Install the required capability or replace the node"));
            return;
        }
        Object operation = definition.getHandlerConfig() != null ? definition.getHandlerConfig().get("operation") : null;
        if (operation instanceof String operationId && !handlers.hasOperation(handler, operationId)) {
            diagnostics.add(error("HANDLER_OPERATION_UNAVAILABLE", graphId, nodeId, "", "Handler operation is unavailable: " + handler + "." + operationId, "Migrate the node or install a compatible extension version"));
        }
    }

    private void validateRequiredInputs(FlowGraph graph, String nodeId, FlowNode node, NodeDefinition definition, List<FlowGraphDiagnostic> diagnostics) {
        Map<String, Object> values = node.getInputValues() != null ? node.getInputValues() : Map.of();
        for (NodeDefinition.PinDefinition pin : definition.getInputs()) {
            if (isExecution(pin) || !isPinVisible(graph, nodeId, node, definition, pin)
                || isPinVisibilityDynamic(graph, nodeId, node, definition, pin)) {
                continue;
            }
            if (pin.getRepeatable() != null) {
                if (pin.getDefaultValue() != null) {
                    continue;
                }
                for (int index = 1; index <= pin.getRepeatable().getMinItems(); index++) {
                    String pinName = index == 1 ? pin.getName() : pin.getName() + "_" + index;
                    validateRequiredInput(graph, nodeId, definition, values, pinName, diagnostics);
                }
                continue;
            }
            if (pin.isOptional() || pin.getDefaultValue() != null) {
                continue;
            }
            validateRequiredInput(graph, nodeId, definition, values, pin.getName(), diagnostics);
        }
    }

    private void validateRequiredInput(FlowGraph graph, String nodeId, NodeDefinition definition, Map<String, Object> values, String pinName,
                                       List<FlowGraphDiagnostic> diagnostics) {
        FlowNode node = graph.getNodes().get(nodeId);
        boolean connected = isInputConnected(graph, nodeId, definition, node, pinName);
        Object value = inputValue(node, definition, pinName);
        if (!connected && (!hasInputValue(values, definition, pinName) || value == null)) {
            FlowGraphDiagnostic diagnostic = definition.getSchemaVersion() >= 2
                ? error("REQUIRED_INPUT_MISSING", graph.getId(), nodeId, pinName, "Required input has no connection or literal value", "Connect " + pinName + " or provide a value")
                : warning("REQUIRED_INPUT_UNVERIFIED", graph.getId(), nodeId, pinName, "Legacy schema does not declare whether this unconnected input is required", "Migrate the definition to schema version 2 with explicit optional semantics");
            diagnostics.add(diagnostic);
        }
    }

    private void validateLiteralInputs(FlowGraph graph, String nodeId, FlowNode node, NodeDefinition definition, List<FlowGraphDiagnostic> diagnostics) {
        if (node.getInputValues() == null) {
            return;
        }
        String graphId = graph.getId();
        for (Map.Entry<String, Object> value : node.getInputValues().entrySet()) {
            if (isEditorMetadata(value.getKey(), definition)) {
                continue;
            }
            NodeDefinition.PinDefinition pin = findInputPin(definition, node, value.getKey());
            if (pin == null) {
                diagnostics.add(error("INPUT_PIN_UNKNOWN", graphId, nodeId, value.getKey(), "Literal targets an unknown input pin", "Remove the stale literal or migrate the node"));
                continue;
            }
            if (!isPinVisible(graph, nodeId, node, definition, pin)) {
                continue;
            }
            if (isInputConnected(graph, nodeId, definition, node, value.getKey())) {
                continue;
            }
            if (!isRepeatablePinActive(node, definition, pin, value.getKey())) {
                diagnostics.add(error("REPEATABLE_PIN_INACTIVE", graphId, nodeId, value.getKey(), "Literal targets an inactive repeatable input", "Add the repeatable item or remove the stale literal"));
                continue;
            }
            if (value.getValue() != null && !literalCompatible(value.getValue(), pin)) {
                diagnostics.add(error("LITERAL_TYPE_INVALID", graphId, nodeId, pin.getName(), "Literal value is incompatible with " + pin.getDataType().getId(), "Choose a value accepted by the pin type"));
                continue;
            }
            validateValueContract("LITERAL", graphId, nodeId, pin, value.getValue(), diagnostics);
            validateCatalogLiteral(graph, nodeId, node, definition, pin, value.getValue(), diagnostics);
        }
    }

    private void validateDefaults(FlowGraph graph, String nodeId, FlowNode node, NodeDefinition definition, List<FlowGraphDiagnostic> diagnostics) {
        String graphId = graph.getId();
        for (NodeDefinition.PinDefinition pin : definition.getInputs()) {
            String defaultValue = pin.getDefaultValue();
            if (defaultValue == null || isExecution(pin)) {
                continue;
            }
            if (!literalCompatible(defaultValue, pin)) {
                diagnostics.add(error("DEFAULT_TYPE_INVALID", graphId, nodeId, pin.getName(),
                    "Definition default is incompatible with " + pin.getDataType().getId(),
                    "Correct the node definition default or migrate the node schema"));
                continue;
            }
            validateValueContract("DEFAULT", graphId, nodeId, pin, defaultValue, diagnostics);
            if (isPinVisible(graph, nodeId, node, definition, pin)
                && !isInputConnected(graph, nodeId, definition, node, pin.getName())
                && !hasInputValue(node.getInputValues(), definition, pin.getName())) {
                validateCatalogLiteral(graph, nodeId, node, definition, pin, defaultValue, diagnostics);
            }
        }
    }

    private boolean literalCompatible(Object value, NodeDefinition.PinDefinition pin) {
        FlowDataType type = pin.getDataType();
        if (type == null || type == FlowDataType.ANY || type == FlowDataType.EXECUTION || pin.getOptionsSource() != null && value instanceof String) {
            return true;
        }
        Class<?> target = type.getJavaType();
        if (target == null || target.isInstance(value) || value instanceof Number && Number.class.isAssignableFrom(target)) {
            return true;
        }
        if (adapters == null || !adapters.canConvert(value.getClass(), target)) {
            return false;
        }
        try {
            return adapters.adapt(value, target) != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void validateValueContract(String prefix, String graphId, String nodeId, NodeDefinition.PinDefinition pin, Object value,
                                       List<FlowGraphDiagnostic> diagnostics) {
        if (value == null) {
            return;
        }
        if (!pin.getOptions().isEmpty() && value instanceof String text && !pin.getOptions().contains(text)) {
            diagnostics.add(error(prefix + "_OPTION_INVALID", graphId, nodeId, pin.getName(),
                "Value is not one of the declared options: " + text,
                "Choose one of the options declared by the node definition"));
        }
        NodeDefinition.PinConstraints constraints = pin.getConstraints();
        if (constraints == null) {
            return;
        }
        Object adapted = adaptValue(value, pin);
        if (!(adapted instanceof Number number)) {
            return;
        }
        double numeric = number.doubleValue();
        if (constraints.getMin() != null && numeric < constraints.getMin()) {
            diagnostics.add(error(prefix + "_BELOW_MINIMUM", graphId, nodeId, pin.getName(),
                "Value " + numeric + " is below the minimum " + constraints.getMin(),
                "Use a value greater than or equal to " + constraints.getMin()));
        }
        if (constraints.getMax() != null && numeric > constraints.getMax()) {
            diagnostics.add(error(prefix + "_ABOVE_MAXIMUM", graphId, nodeId, pin.getName(),
                "Value " + numeric + " is above the maximum " + constraints.getMax(),
                "Use a value less than or equal to " + constraints.getMax()));
        }
    }

    private Object adaptValue(Object value, NodeDefinition.PinDefinition pin) {
        FlowDataType type = pin.getDataType();
        Class<?> target = type != null ? type.getJavaType() : null;
        if (target == null || target.isInstance(value) || adapters == null) {
            return value;
        }
        try {
            Object adapted = adapters.adapt(value, target);
            return adapted != null ? adapted : value;
        } catch (RuntimeException exception) {
            return value;
        }
    }

    private void validateCatalogLiteral(FlowGraph graph, String nodeId, FlowNode node, NodeDefinition definition,
                                        NodeDefinition.PinDefinition pin, Object value,
                                        List<FlowGraphDiagnostic> diagnostics) {
        String graphId = graph.getId();
        if (value instanceof FlowResourceReference reference) {
            validateTypedResourceReference(graphId, nodeId, pin, reference, diagnostics);
            return;
        }
        String sourceId = pin.getOptionsSource();
        if (!(value instanceof String literal) || literal.isBlank() || sourceId == null || sourceId.isBlank()) {
            return;
        }
        if (catalogs == null) {
            boolean managedResource = isManagedResourceCatalog(pin, sourceId);
            diagnostics.add(error(managedResource ? "RESOURCE_CATALOG_UNAVAILABLE" : "CATALOG_UNAVAILABLE", graphId, nodeId, pin.getName(),
                (managedResource ? "Managed-resource catalog is unavailable: " : "Option catalog is unavailable: ") + sourceId,
                "Install or enable the catalog capability"));
            return;
        }
        OptionCatalogQuery query = new OptionCatalogQuery(sourceId, catalogContext(graph, nodeId, node, definition, pin));
        OptionCatalogRegistry.ResolvedCapture resolved;
        try {
            resolved = catalogs.resolveCapture(sourceId, query);
        } catch (OptionCatalogRegistry.ProviderUnavailable exception) {
            boolean managedResource = isManagedResourceCatalog(pin, sourceId);
            diagnostics.add(error(managedResource ? "RESOURCE_CATALOG_UNAVAILABLE" : "CATALOG_UNAVAILABLE", graphId, nodeId, pin.getName(),
                (managedResource ? "Managed-resource catalog is unavailable: " : "Option catalog is unavailable: ") + sourceId,
                "Install or enable the catalog capability"));
            return;
        } catch (RuntimeException exception) {
            diagnostics.add(error("CATALOG_QUERY_FAILED", graphId, nodeId, pin.getName(), "Option catalog capture could not be resolved: " + sourceId,
                "Check the catalog provider and try again"));
            return;
        }
        boolean dynamicContext = resolved.contextKeys().stream().anyMatch(key ->
            isInputConnected(graph, nodeId, definition, node, key));
        OptionCatalogCapture capture = resolved.capture();
        String normalizedStatus = capture.status().trim().toLowerCase(Locale.ROOT);
        if (dynamicContext && "invalid".equals(normalizedStatus)) {
            return;
        }
        if (!"available".equals(normalizedStatus)) {
            String code = switch (normalizedStatus) {
                case "invalid" -> "CATALOG_CONTEXT_INVALID";
                case "permission_restricted", "restricted", "forbidden" -> "CATALOG_PERMISSION_RESTRICTED";
                case "rejected" -> "CATALOG_REJECTED";
                default -> "CATALOG_UNAVAILABLE";
            };
            String providerDiagnostic = capture.diagnostic();
            String message = providerDiagnostic != null && !providerDiagnostic.isBlank()
                ? providerDiagnostic
                : "Option catalog " + sourceId + " is " + normalizedStatus.replace('_', ' ');
            diagnostics.add(error(code, graphId, nodeId, pin.getName(), message, "Correct the catalog context or enable the required capability"));
            return;
        }
        if (dynamicContext) {
            return;
        }
        List<String> values = capture.values();
        boolean matches = values != null && values.stream().anyMatch(candidate -> candidate != null
            && (candidate.equals(literal) || minecraftCatalogValueMatches(sourceId, candidate, literal)));
        if (!matches) {
            boolean managedResource = isManagedResourceCatalog(pin, sourceId);
            diagnostics.add(error(managedResource ? "RESOURCE_REFERENCE_UNRESOLVED" : "CATALOG_VALUE_UNRESOLVED", graphId, nodeId, pin.getName(),
                managedResource ? "Managed resource does not exist: " + literal : "Catalog value does not exist in " + sourceId + ": " + literal,
                managedResource ? "Create the resource or select an existing ID" : "Select a value supplied by the authoritative catalog"));
        }
    }

    private boolean minecraftCatalogValueMatches(String sourceId, String candidate, String literal) {
        if (!sourceId.startsWith("server:minecraft:") || candidate == null || literal == null) {
            return false;
        }
        String normalizedCandidate = candidate.trim().toLowerCase(Locale.ROOT);
        String normalizedLiteral = literal.trim().toLowerCase(Locale.ROOT);
        if (normalizedCandidate.equals(normalizedLiteral)) {
            return true;
        }
        String candidatePath = normalizedCandidate.startsWith("minecraft:") ? normalizedCandidate.substring("minecraft:".length()) : normalizedCandidate;
        String literalPath = normalizedLiteral.startsWith("minecraft:") ? normalizedLiteral.substring("minecraft:".length()) : normalizedLiteral;
        return candidatePath.replace('.', '_').replace('-', '_').equals(literalPath.replace('.', '_').replace('-', '_'));
    }

    private boolean isManagedResourceCatalog(NodeDefinition.PinDefinition pin, String sourceId) {
        FlowTypeRef typeRef = pin.getTypeRef();
        if (typeRef != null && "resource_reference".equals(typeRef.getTypeId())) {
            return true;
        }
        return resources != null && resources.adapters().stream()
            .anyMatch(adapter -> sourceId.equals(adapter.catalogSource()));
    }

    private Map<String, Object> catalogContext(FlowGraph graph, String nodeId, FlowNode node, NodeDefinition definition,
                                               NodeDefinition.PinDefinition pin) {
        Map<String, Object> context = new HashMap<>();
        if (node.getInputValues() != null) {
            context.putAll(node.getInputValues());
        }
        for (FlowConnection connection : graph.getConnectionsToTarget(nodeId)) {
            context.remove(connection.getTargetPin());
            NodeDefinition.PinDefinition target = findInputPin(definition, node, connection.getTargetPin());
            if (target != null) {
                context.remove(target.getName());
                context.remove(target.getRuntimeName());
            }
        }
        context.remove(pin.getName());
        context.remove(pin.getRuntimeName());
        context.put("$nodeType", node.getType() != null ? node.getType() : "");
        context.put("$pin", pin.getName());
        return context;
    }

    private void validateTypedResourceReference(String graphId, String nodeId, NodeDefinition.PinDefinition pin, FlowResourceReference reference,
                                                List<FlowGraphDiagnostic> diagnostics) {
        FlowTypeRef typeRef = pin.getTypeRef();
        if (typeRef == null || !"resource_reference".equals(typeRef.getTypeId())) {
            return;
        }
        if (reference.id().isBlank()) {
            diagnostics.add(error("RESOURCE_REFERENCE_ID_REQUIRED", graphId, nodeId, pin.getName(), "Managed-resource reference has no ID", "Select an existing resource"));
            return;
        }
        if (!typeRef.getArguments().isEmpty()) {
            String requiredKind = typeRef.getArguments().getFirst().getTypeId();
            if (!requiredKind.equals(reference.kind())) {
                diagnostics.add(error("RESOURCE_REFERENCE_KIND_MISMATCH", graphId, nodeId, pin.getName(),
                    "Resource kind " + reference.kind() + " is incompatible with " + requiredKind, "Select a " + requiredKind + " resource"));
                return;
            }
        }
        if (resources == null) {
            return;
        }
        FlowResourceAdapter<?> adapter = resources.get(reference.kind());
        if (adapter == null) {
            diagnostics.add(error("RESOURCE_AUTHORITY_UNAVAILABLE", graphId, nodeId, pin.getName(),
                "Managed-resource authority is unavailable: " + reference.kind(), "Install or enable the resource capability"));
            return;
        }
        try {
            if (adapter.get(reference.id()) == null) {
                diagnostics.add(error("RESOURCE_REFERENCE_UNRESOLVED", graphId, nodeId, pin.getName(),
                    "Managed resource does not exist: " + reference.id(), "Create the resource or select an existing ID"));
            }
        } catch (RuntimeException exception) {
            diagnostics.add(error("RESOURCE_RESOLUTION_FAILED", graphId, nodeId, pin.getName(),
                "Managed resource could not be resolved: " + reference.id(), "Check the resource service and try again"));
        }
    }

    private void validateConnections(FlowGraph graph, Map<String, NodeDefinition> resolved, List<FlowGraphDiagnostic> diagnostics) {
        Set<String> occupiedTargets = new HashSet<>();
        Map<String, Map<String, FlowTypeRef>> typeBindings = inferTypeBindings(graph, resolved, diagnostics);
        for (FlowConnection connection : graph.getConnections()) {
            if (connection == null) {
                diagnostics.add(error("CONNECTION_INVALID", graph.getId(), "", "", "Flow contains a null connection", "Remove the invalid connection"));
                continue;
            }
            String sourceNodeId = connection.getSourceNodeId();
            String targetNodeId = connection.getTargetNodeId();
            NodeDefinition sourceDefinition = resolved.get(sourceNodeId);
            NodeDefinition targetDefinition = resolved.get(targetNodeId);
            if (!graph.getNodes().containsKey(sourceNodeId) || !graph.getNodes().containsKey(targetNodeId)) {
                diagnostics.add(error("CONNECTION_NODE_MISSING", graph.getId(), targetNodeId, connection.getTargetPin(), "Connection references a missing node", "Remove the stale connection"));
                continue;
            }
            if (sourceDefinition == null || targetDefinition == null) {
                continue;
            }
            NodeDefinition.PinDefinition source = findPin(sourceDefinition.getOutputs(), connection.getSourcePin());
            NodeDefinition.PinDefinition target = findInputPin(targetDefinition, graph.getNodes().get(targetNodeId), connection.getTargetPin());
            if (source == null) {
                diagnostics.add(error("SOURCE_PIN_UNKNOWN", graph.getId(), sourceNodeId, connection.getSourcePin(), "Connection starts at an unknown output pin", "Reconnect from a current output pin"));
                continue;
            }
            if (target == null) {
                diagnostics.add(error("TARGET_PIN_UNKNOWN", graph.getId(), targetNodeId, connection.getTargetPin(), "Connection ends at an unknown input pin", "Reconnect to a current input pin"));
                continue;
            }
            if (!isRepeatablePinActive(graph.getNodes().get(targetNodeId), targetDefinition, target, connection.getTargetPin())) {
                diagnostics.add(error("REPEATABLE_PIN_INACTIVE", graph.getId(), targetNodeId, connection.getTargetPin(),
                    "Connection targets an inactive repeatable input", "Add the repeatable item or remove the stale connection"));
                continue;
            }
            FlowTypeRef sourceRef = resolveTypeVariables(source.getTypeRef(), typeBindings.get(sourceNodeId));
            FlowTypeRef targetRef = resolveTypeVariables(target.getTypeRef(), typeBindings.get(targetNodeId));
            if (!compatible(source, target, sourceRef, targetRef)) {
                diagnostics.add(error("CONNECTION_TYPE_INCOMPATIBLE", graph.getId(), targetNodeId, target.getName(),
                    "Cannot connect " + sourceRef + " to " + targetRef, "Insert an explicit conversion or choose a compatible pin"));
            }
            String targetKey = targetNodeId + '\u0000' + normalizedPinIdentity(target, connection.getTargetPin());
            if (!isExecution(target) && !occupiedTargets.add(targetKey)) {
                diagnostics.add(error("CONNECTION_TARGET_DUPLICATE", graph.getId(), targetNodeId, target.getName(),
                    "Input pin has more than one connection",
                    "Keep one connection or use a typed collection/repeatable input"));
            }
        }
    }

    private boolean compatible(NodeDefinition.PinDefinition source, NodeDefinition.PinDefinition target, FlowTypeRef sourceRef, FlowTypeRef targetRef) {
        if (isExecution(source) || isExecution(target)) {
            return isExecution(source) && isExecution(target);
        }
        if (sourceRef != null && targetRef != null && targetRef.isAssignableFrom(sourceRef)) {
            return true;
        }
        if (sourceRef != null && targetRef != null && (!sourceRef.getArguments().isEmpty() || !targetRef.getArguments().isEmpty())) {
            return false;
        }
        FlowDataType sourceType = sourceRef != null ? FlowDataType.fromString(sourceRef.getTypeId()) : source.getDataType();
        FlowDataType targetType = targetRef != null ? FlowDataType.fromString(targetRef.getTypeId()) : target.getDataType();
        if (sourceType == null || targetType == null || sourceType == FlowDataType.ANY || targetType == FlowDataType.ANY) {
            return true;
        }
        if (isTemporal(sourceType) || isTemporal(targetType)) {
            if (isTemporal(sourceType) && isTemporal(targetType)) {
                return sourceType == targetType;
            }
            FlowDataType numericType = isTemporal(sourceType) ? targetType : sourceType;
            return FlowDataType.NUMBER.isAssignableFrom(numericType);
        }
        Class<?> sourceClass = sourceType.getJavaType();
        Class<?> targetClass = targetType.getJavaType();
        return sourceClass != null && targetClass != null && adapters != null && adapters.canConvert(sourceClass, targetClass);
    }

    private boolean isTemporal(FlowDataType type) {
        return type == FlowDataType.INSTANT || type == FlowDataType.DURATION;
    }

    private String normalizedPinIdentity(NodeDefinition.PinDefinition pin, String persistedName) {
        if (pin == null) {
            return persistedName;
        }
        int index = repeatableIndex(pin, persistedName);
        return index > 1 ? pin.getName() + "_" + index : pin.getName();
    }

    private Map<String, Map<String, FlowTypeRef>> inferTypeBindings(FlowGraph graph, Map<String, NodeDefinition> resolved,
                                                                    List<FlowGraphDiagnostic> diagnostics) {
        Map<String, Map<String, FlowTypeRef>> bindings = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        int passes = Math.max(1, graph.getConnections().size() + 1);
        for (int pass = 0; pass < passes; pass++) {
            boolean changed = false;
            for (FlowConnection connection : graph.getConnections()) {
                if (connection == null) {
                    continue;
                }
                NodeDefinition sourceDefinition = resolved.get(connection.getSourceNodeId());
                NodeDefinition targetDefinition = resolved.get(connection.getTargetNodeId());
                if (sourceDefinition == null || targetDefinition == null) {
                    continue;
                }
                NodeDefinition.PinDefinition source = findPin(sourceDefinition.getOutputs(), connection.getSourcePin());
                NodeDefinition.PinDefinition target = findInputPin(targetDefinition, graph.getNodes().get(connection.getTargetNodeId()), connection.getTargetPin());
                if (source == null || target == null || isExecution(source) || isExecution(target)) {
                    continue;
                }
                FlowTypeRef sourceRef = resolveTypeVariables(source.getTypeRef(), bindings.get(connection.getSourceNodeId()));
                Map<String, FlowTypeRef> targetBindings = bindings.computeIfAbsent(connection.getTargetNodeId(), ignored -> new HashMap<>());
                changed |= bindTypeVariables(target.getTypeRef(), sourceRef, targetBindings, connection.getTargetNodeId(),
                    connection.getTargetPin(), graph.getId(), conflicts, diagnostics);
            }
            if (!changed) {
                break;
            }
        }
        return bindings;
    }

    private boolean bindTypeVariables(FlowTypeRef pattern, FlowTypeRef actual, Map<String, FlowTypeRef> bindings, String nodeId,
                                      String pinName, String graphId, Set<String> conflicts, List<FlowGraphDiagnostic> diagnostics) {
        if (pattern == null || actual == null || actual.isTypeVariable()) {
            return false;
        }
        if (pattern.isTypeVariable()) {
            if ("any".equals(actual.getTypeId()) && actual.getArguments().isEmpty()) {
                return false;
            }
            String variable = pattern.getTypeVariableName();
            FlowTypeRef existing = bindings.get(variable);
            if (existing == null) {
                bindings.put(variable, actual);
                return true;
            }
            FlowTypeRef merged = commonType(existing, actual);
            if (merged != null) {
                if (!merged.equals(existing)) {
                    bindings.put(variable, merged);
                    return true;
                }
                return false;
            }
            String conflict = nodeId + '\u0000' + variable;
            if (conflicts.add(conflict)) {
                diagnostics.add(error("GENERIC_TYPE_CONFLICT", graphId, nodeId, pinName,
                    "Type variable " + variable + " cannot represent both " + existing + " and " + actual,
                    "Use inputs with a compatible element type or add an explicit conversion"));
            }
            return false;
        }
        if (!pattern.getTypeId().equals(actual.getTypeId()) || pattern.getArguments().size() != actual.getArguments().size()) {
            return false;
        }
        boolean changed = false;
        for (int index = 0; index < pattern.getArguments().size(); index++) {
            changed |= bindTypeVariables(pattern.getArguments().get(index), actual.getArguments().get(index), bindings, nodeId, pinName,
                graphId, conflicts, diagnostics);
        }
        return changed;
    }

    private FlowTypeRef commonType(FlowTypeRef first, FlowTypeRef second) {
        if (first.equals(second) || first.isAssignableFrom(second)) {
            return first;
        }
        return second.isAssignableFrom(first) ? second : null;
    }

    private FlowTypeRef resolveTypeVariables(FlowTypeRef typeRef, Map<String, FlowTypeRef> bindings) {
        if (typeRef == null || bindings == null || bindings.isEmpty()) {
            return typeRef;
        }
        if (typeRef.isTypeVariable()) {
            return bindings.getOrDefault(typeRef.getTypeVariableName(), typeRef);
        }
        List<FlowTypeRef> arguments = typeRef.getArguments().stream().map(argument -> resolveTypeVariables(argument, bindings)).toList();
        return arguments.equals(typeRef.getArguments()) ? typeRef : new FlowTypeRef(typeRef.getTypeId(), arguments);
    }

    private void validateDataCycles(FlowGraph graph, Map<String, NodeDefinition> resolved, List<FlowGraphDiagnostic> diagnostics) {
        Map<String, List<String>> edges = new HashMap<>();
        for (FlowConnection connection : graph.getConnections()) {
            NodeDefinition sourceDefinition = resolved.get(connection.getSourceNodeId());
            NodeDefinition targetDefinition = resolved.get(connection.getTargetNodeId());
            NodeDefinition.PinDefinition source = sourceDefinition != null ? findPin(sourceDefinition.getOutputs(), connection.getSourcePin()) : null;
            NodeDefinition.PinDefinition target = targetDefinition != null ? findInputPin(targetDefinition, graph.getNodes().get(connection.getTargetNodeId()), connection.getTargetPin()) : null;
            if (source != null && target != null && !isExecution(source) && !isExecution(target)) {
                edges.computeIfAbsent(connection.getSourceNodeId(), ignored -> new ArrayList<>()).add(connection.getTargetNodeId());
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (String nodeId : graph.getNodes().keySet()) {
            if (hasCycle(nodeId, edges, visited, active)) {
                diagnostics.add(error("DATA_DEPENDENCY_CYCLE", graph.getId(), nodeId, "", "Data dependency cycle detected", "Break the cycle or use explicit state and sequencing"));
                return;
            }
        }
    }

    private boolean hasCycle(String nodeId, Map<String, List<String>> edges, Set<String> visited, Set<String> active) {
        if (active.contains(nodeId)) {
            return true;
        }
        if (!visited.add(nodeId)) {
            return false;
        }
        active.add(nodeId);
        for (String target : edges.getOrDefault(nodeId, List.of())) {
            if (hasCycle(target, edges, visited, active)) {
                return true;
            }
        }
        active.remove(nodeId);
        return false;
    }

    private void validateExecutionStructure(FlowGraph graph, Map<String, NodeDefinition> resolved, List<FlowGraphDiagnostic> diagnostics) {
        Map<String, List<String>> edges = new HashMap<>();
        Map<String, List<String>> uncontrolledEdges = new HashMap<>();
        Set<String> executionNodes = new HashSet<>();
        Set<String> roots = new HashSet<>();
        Set<String> fallbackRoots = new HashSet<>();
        Map<String, Integer> incoming = new HashMap<>();

        for (Map.Entry<String, NodeDefinition> entry : resolved.entrySet()) {
            String nodeId = entry.getKey();
            NodeDefinition definition = entry.getValue();
            boolean hasExecutionInput = definition.getInputs().stream().anyMatch(this::isExecution);
            boolean hasExecutionOutput = definition.getOutputs().stream().anyMatch(this::isExecution);
            if (hasExecutionInput || hasExecutionOutput || definition.isTrigger()) {
                executionNodes.add(nodeId);
            }
            if (definition.isTrigger() || isFunctionStart(definition)) {
                roots.add(nodeId);
            }
        }

        for (FlowConnection connection : graph.getConnections()) {
            if (connection == null) {
                continue;
            }
            NodeDefinition sourceDefinition = resolved.get(connection.getSourceNodeId());
            NodeDefinition targetDefinition = resolved.get(connection.getTargetNodeId());
            NodeDefinition.PinDefinition source = sourceDefinition != null ? findPin(sourceDefinition.getOutputs(), connection.getSourcePin()) : null;
            NodeDefinition.PinDefinition target = targetDefinition != null ? findInputPin(targetDefinition, graph.getNodes().get(connection.getTargetNodeId()), connection.getTargetPin()) : null;
            if (source == null || target == null || !isExecution(source) || !isExecution(target)) {
                continue;
            }
            edges.computeIfAbsent(connection.getSourceNodeId(), ignored -> new ArrayList<>()).add(connection.getTargetNodeId());
            if (!isExecutionCycleBoundary(sourceDefinition) && !isExecutionCycleBoundary(targetDefinition)) {
                uncontrolledEdges.computeIfAbsent(connection.getSourceNodeId(), ignored -> new ArrayList<>()).add(connection.getTargetNodeId());
            }
            incoming.put(connection.getTargetNodeId(), incoming.getOrDefault(connection.getTargetNodeId(), 0) + 1);
        }

        for (String nodeId : executionNodes) {
            if (incoming.getOrDefault(nodeId, 0) == 0) {
                fallbackRoots.add(nodeId);
            }
        }
        if (roots.isEmpty()) {
            roots.addAll(fallbackRoots);
        }

        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (String nodeId : executionNodes) {
            if (hasCycle(nodeId, uncontrolledEdges, visited, active)) {
                diagnostics.add(error("EXECUTION_CYCLE", graph.getId(), nodeId, "",
                    "Execution connection cycle detected",
                    "Replace the cycle with a Loop node or explicit scheduled state"));
                break;
            }
        }

        Set<String> reachable = new HashSet<>();
        for (String root : roots) {
            collectReachable(root, edges, reachable);
        }
        for (String nodeId : executionNodes) {
            if (reachable.contains(nodeId) || roots.contains(nodeId)) {
                continue;
            }
            NodeDefinition definition = resolved.get(nodeId);
            FlowGraphDiagnostic diagnostic = graph.isFunction() && isRequiredFunctionTerminal(definition)
                ? error("REQUIRED_SECTION_UNREACHABLE", graph.getId(), nodeId, "",
                    "Required function output is unreachable",
                    "Connect the function execution path to this output")
                : warning("EXECUTION_SECTION_UNREACHABLE", graph.getId(), nodeId, "",
                    "Execution section is unreachable from a graph entry",
                    "Connect it to a trigger/start path or remove the detached section");
            diagnostics.add(diagnostic);
        }
    }

    private boolean isExecutionCycleBoundary(NodeDefinition definition) {
        if (definition == null) {
            return false;
        }
        if (EXECUTION_CYCLE_BOUNDARY_IDS.contains(definition.getId())) {
            return true;
        }
        Map<String, Object> handlerConfig = definition.getHandlerConfig();
        Object operation = handlerConfig != null ? handlerConfig.get("operation") : null;
        return operation instanceof String value && EXECUTION_CYCLE_BOUNDARY_OPERATIONS.contains(value);
    }

    private void collectReachable(String nodeId, Map<String, List<String>> edges, Set<String> reachable) {
        if (!reachable.add(nodeId)) {
            return;
        }
        for (String target : edges.getOrDefault(nodeId, List.of())) {
            collectReachable(target, edges, reachable);
        }
    }

    private boolean isFunctionStart(NodeDefinition definition) {
        if (definition == null || definition.getId() == null) {
            return false;
        }
        String id = definition.getId();
        return isFunctionStartType(id) || hasHandlerOperation(definition, "function_start");
    }

    private boolean isRequiredFunctionTerminal(NodeDefinition definition) {
        if (definition == null || definition.getId() == null) {
            return false;
        }
        String id = definition.getId();
        return "function_output".equals(id) || "function.function_output".equals(id)
            || isFunctionEndType(id) || "return".equals(id)
            || hasHandlerOperation(definition, "function_output")
            || hasHandlerOperation(definition, "return_value");
    }

    private NodeDefinition.PinDefinition findPin(List<NodeDefinition.PinDefinition> pins, String name) {
        return resolvePin(pins, name).pin();
    }

    private NodeDefinition.PinDefinition findInputPin(NodeDefinition definition, FlowNode node, String name) {
        PinResolution declared = resolvePin(definition != null ? definition.getInputs() : null, name);
        if (declared.pin() != null || declared.ambiguous() || !isTemplateInput(node, name)) {
            return declared.pin();
        }
        return new NodeDefinition.PinDefinition(PinId.of(name), name, NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.ANY, true);
    }

    private PinResolution resolvePin(List<NodeDefinition.PinDefinition> pins, String name) {
        if (pins == null || name == null || name.isBlank()) {
            return PinResolution.missing();
        }
        List<NodeDefinition.PinDefinition> stableMatches = pins.stream()
            .filter(Objects::nonNull)
            .filter(pin -> name.equals(pin.getName()))
            .toList();
        if (stableMatches.size() == 1) {
            return PinResolution.found(stableMatches.getFirst());
        }
        if (stableMatches.size() > 1) {
            return PinResolution.ambiguousMatch();
        }
        List<NodeDefinition.PinDefinition> runtimeMatches = pins.stream()
            .filter(Objects::nonNull)
            .filter(pin -> name.equals(pin.getRuntimeName()))
            .toList();
        if (runtimeMatches.size() == 1) {
            return PinResolution.found(runtimeMatches.getFirst());
        }
        if (runtimeMatches.size() > 1) {
            return PinResolution.ambiguousMatch();
        }
        List<NodeDefinition.PinDefinition> repeatableMatches = new ArrayList<>();
        for (NodeDefinition.PinDefinition pin : pins) {
            if (pin == null || pin.getRepeatable() == null) {
                continue;
            }
            String base = repeatableBase(name, pin);
            if (base != null && isRepeatableIndex(base, name, pin.getRepeatable().getMaxItems())) {
                repeatableMatches.add(pin);
            }
        }
        if (repeatableMatches.size() == 1) {
            return PinResolution.found(repeatableMatches.getFirst());
        }
        if (repeatableMatches.size() > 1) {
            return PinResolution.ambiguousMatch();
        }
        if ("next".equals(name)) {
            return executionAlias(pins, "flow");
        }
        if ("flow".equals(name)) {
            return executionAlias(pins, "next");
        }
        return PinResolution.missing();
    }

    private PinResolution executionAlias(List<NodeDefinition.PinDefinition> pins, String stableName) {
        List<NodeDefinition.PinDefinition> matches = pins.stream()
            .filter(Objects::nonNull)
            .filter(pin -> (stableName.equals(pin.getName()) || stableName.equals(pin.getRuntimeName())) && isExecution(pin))
            .toList();
        return matches.size() == 1 ? PinResolution.found(matches.getFirst())
            : matches.size() > 1 ? PinResolution.ambiguousMatch() : PinResolution.missing();
    }

    private String repeatableBase(String name, NodeDefinition.PinDefinition pin) {
        String stablePrefix = pin.getName() + "_";
        if (name.startsWith(stablePrefix)) {
            return pin.getName();
        }
        String runtimePrefix = pin.getRuntimeName() + "_";
        return name.startsWith(runtimePrefix) ? pin.getRuntimeName() : null;
    }

    private boolean isRepeatableIndex(String base, String name, int maxItems) {
        String suffix = name.substring(base.length() + 1);
        if (suffix.isEmpty() || suffix.length() > 9 || !suffix.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int index = Integer.parseInt(suffix);
        return index >= 2 && index <= maxItems;
    }

    private record PinResolution(NodeDefinition.PinDefinition pin, boolean ambiguous) {
        private static PinResolution found(NodeDefinition.PinDefinition pin) {
            return new PinResolution(pin, false);
        }

        private static PinResolution ambiguousMatch() {
            return new PinResolution(null, true);
        }

        private static PinResolution missing() {
            return new PinResolution(null, false);
        }
    }

    private boolean isTemplateInput(FlowNode node, String name) {
        if (node == null || !isTemplateName(name) || node.getInputValues() == null) {
            return false;
        }
        for (Map.Entry<String, Object> input : node.getInputValues().entrySet()) {
            if (name.equals(input.getKey()) || !(input.getValue() instanceof String template)) {
                continue;
            }
            int index = 0;
            while (index < template.length()) {
                int start = template.indexOf('{', index);
                if (start < 0) {
                    break;
                }
                if (start + 1 < template.length() && template.charAt(start + 1) == '{') {
                    index = start + 2;
                    continue;
                }
                int end = template.indexOf('}', start + 1);
                if (end < 0) {
                    break;
                }
                String placeholder = template.substring(start + 1, end).trim();
                if (name.equals(placeholder)) {
                    return true;
                }
                index = end + 1;
            }
        }
        return false;
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
            char character = name.charAt(i);
            if (!Character.isLetterOrDigit(character) && character != '_') {
                return false;
            }
        }
        return true;
    }

    private boolean isEditorMetadata(String name, NodeDefinition definition) {
        if (name == null) {
            return false;
        }
        if (Set.of("event.resync.command", "event:resync_command").contains(definition.getId())
            && Set.of("command", "subcommands", "structured").contains(name)) {
            return true;
        }
        if ("__flow_branches".equals(name) || "__removed_optional_inputs".equals(name) || "__permission_count".equals(name)
            || CALL_PARAMETERS_KEY.equals(name) || "__function_signature".equals(name) || "__function_signature_issues".equals(name)) {
            return true;
        }
        if (!name.startsWith("__repeatable_count:")) {
            return false;
        }
        String groupId = name.substring("__repeatable_count:".length());
        return definition.getInputs().stream().map(NodeDefinition.PinDefinition::getRepeatable).filter(Objects::nonNull)
            .anyMatch(repeatable -> groupId.equals(repeatable.getGroupId()));
    }

    private boolean isRepeatablePinActive(FlowNode node, NodeDefinition definition, NodeDefinition.PinDefinition pin, String pinName) {
        NodeDefinition.RepeatablePin repeatable = pin.getRepeatable();
        if (repeatable == null || node == null) {
            return true;
        }
        int index = repeatableIndex(pin, pinName);
        if (index < 1) {
            return true;
        }
        int count = repeatable.getMinItems();
        Map<String, Object> values = node.getInputValues();
        if (values != null) {
            Object stored = values.get("__repeatable_count:" + repeatable.getGroupId());
            if (stored == null && "permissions".equals(repeatable.getGroupId())) {
                stored = values.get("__permission_count");
            }
            count = repeatableCount(stored, count);
            Object removed = values.get("__removed_optional_inputs");
            if (removed instanceof Iterable<?> names) {
                for (Object name : names) {
                    if (name != null && normalizedInputIdentity(definition, pinName).equals(
                        normalizedInputIdentity(definition, String.valueOf(name)))) {
                        return false;
                    }
                }
            }
        }
        return index <= Math.clamp(count, repeatable.getMinItems(), repeatable.getMaxItems());
    }

    private String normalizedInputIdentity(NodeDefinition definition, String pinName) {
        PinResolution resolved = resolvePin(definition != null ? definition.getInputs() : null, pinName);
        return resolved.pin() != null ? FlowRuntime.normalizeInputPin(definition, pinName) : pinName;
    }

    private int repeatableIndex(NodeDefinition.PinDefinition pin, String pinName) {
        if (pin == null || pinName == null) {
            return -1;
        }
        if (pin.getName().equals(pinName) || pin.getRuntimeName().equals(pinName)) {
            return 1;
        }
        for (String baseName : List.of(pin.getName(), pin.getRuntimeName())) {
            String prefix = baseName + "_";
            if (!pinName.startsWith(prefix)) {
                continue;
            }
            String suffix = pinName.substring(prefix.length());
            if (!suffix.isEmpty() && suffix.length() <= 9 && suffix.chars().allMatch(Character::isDigit)) {
                return Integer.parseInt(suffix);
            }
        }
        return -1;
    }

    private int repeatableCount(Object stored, int fallback) {
        if (stored instanceof Number number) {
            return number.intValue();
        }
        if (stored != null) {
            try {
                return Integer.parseInt(stored.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Repeatable pin count is not a valid integer: " + stored, exception);
            }
        }
        return fallback;
    }

    private boolean isExecution(NodeDefinition.PinDefinition pin) {
        return pin.getType() == NodeDefinition.PinType.FLOW || pin.getType() == NodeDefinition.PinType.EXEC || pin.getDataType() == FlowDataType.EXECUTION;
    }

    private FlowGraphDiagnostic error(String code, String graphId, String nodeId, String pin, String message, String remediation) {
        return new FlowGraphDiagnostic(FlowGraphDiagnostic.Severity.ERROR, code, graphId, nodeId, pin, message, remediation);
    }

    private FlowGraphDiagnostic warning(String code, String graphId, String nodeId, String pin, String message, String remediation) {
        return new FlowGraphDiagnostic(FlowGraphDiagnostic.Severity.WARNING, code, graphId, nodeId, pin, message, remediation);
    }
}
