package restudio.resync.flow;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowDataType;
import restudio.resync.Log;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CustomFunctionNodeDefinitions {
    public static final String PLUGIN_ID = "custom_functions";
    public static final String NODE_PREFIX = "custom_function:";

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
                FlowGraph graph = storage.getGraph("function", flowId);
                if (graph == null || !graph.isFunction()) {
                    continue;
                }
                definitions.add(buildDefinition(graph));
            } catch (RuntimeException exception) {
                Log.warn("Failed to advertise custom function " + flowId + ": " + exception.getMessage());
            }
        }

        definitionRegistry.registerAll(PLUGIN_ID, definitions);
        return definitions;
    }

    public static NodeDefinition buildDefinition(FlowGraph graph) {
        if (graph == null || graph.getId() == null || graph.getId().isBlank() || !graph.isFunction()) {
            throw new IllegalArgumentException("A callable function with a stable ID is required");
        }
        FlowGraph definitionGraph = graph;
        if (hasLegacyParameters(graph)) {
            definitionGraph = graph.copy().adaptLegacyFunctionParameterIds();
        }
        String flowId = definitionGraph.getId();
        String displayName = toDisplayName(flowId);
        NodeDefinition.Builder builder = new NodeDefinition.Builder(
            NODE_PREFIX + flowId,
            displayName,
            NodeDefinition.NodeCategory.FUNCTION
        ).priority(220)
            .color(NodeDefinition.NodeCategory.FUNCTION)
            .handler(CustomFunctionCallHandler.HANDLER_ID)
            .handlerConfig(Map.of(
                "operation", CustomFunctionCallHandler.OPERATION,
                "functionId", flowId,
                "functionOwner", definitionGraph.getFunctionOwner(),
                "functionNamespace", definitionGraph.getFunctionNamespace()))
            .owner(definitionGraph.getFunctionOwner())
            .schemaVersion(callSchemaVersion(definitionGraph))
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
                builder.input(parameterPin(param, NodeDefinition.PinDirection.INPUT));
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
                builder.output(parameterPin(param, NodeDefinition.PinDirection.OUTPUT));
            }
        }

        NodeDefinition definition = builder.build();
        return ReQuestDescriptorIdentityAdapter.isAuthenticatedFunction(definitionGraph)
            ? ReQuestDescriptorIdentityAdapter.adapt(definition) : definition;
    }

    private static int callSchemaVersion(FlowGraph graph) {
        return ReQuestDescriptorIdentityAdapter.isAuthenticatedFunction(graph)
            ? ReQuestDescriptorIdentityAdapter.FUNCTION_VERSION : 1;
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
        String authored = graph.getFunctionDescription().strip();
        String displayPlaceholder = "Run " + displayName + ".";
        String idPlaceholder = "Run " + graph.getId() + ".";
        if (authored.length() >= 24 && authored.length() <= 280
            && !authored.equalsIgnoreCase(displayPlaceholder) && !authored.equalsIgnoreCase(idPlaceholder)) {
            return authored;
        }
        return "Runs the " + displayName + " custom Function with its declared inputs and outputs.";
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

    private static NodeDefinition.PinDefinition parameterPin(FlowGraph.FunctionParameter parameter, NodeDefinition.PinDirection direction) {
        NodeDefinition.PinBuilder builder = new NodeDefinition.PinBuilder(parameterPinId(parameter, direction), parameter.getName(),
            NodeDefinition.PinType.DATA, direction, normalizeType(parameter.getType()))
            .runtimeName(parameterRuntimeName(parameter, direction))
            .typeRef(parameter.getTypeRef())
            .description(parameterDescription(parameter, direction));
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

    private static String parameterDescription(FlowGraph.FunctionParameter parameter, NodeDefinition.PinDirection direction) {
        return direction == NodeDefinition.PinDirection.INPUT
            ? "Provides " + parameter.getName() + " as an input to this custom Function."
            : "Returns " + parameter.getName() + " as an output from this custom Function.";
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
