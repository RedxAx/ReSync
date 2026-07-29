package restudio.resync.flow;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowDataType;
import restudio.resync.Log;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
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
        Set<String> flowIds = new HashSet<>(storage.listFlowIds());
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

    static NodeDefinition buildDefinition(FlowGraph graph) {
        if (graph == null || graph.getId() == null || graph.getId().isBlank() || !graph.isFunction()) {
            throw new IllegalArgumentException("A callable function with a stable ID is required");
        }
        String flowId = graph.getId();
        String displayName = toDisplayName(flowId);
        NodeDefinition.Builder builder = new NodeDefinition.Builder(
            NODE_PREFIX + flowId,
            displayName,
            NodeDefinition.NodeCategory.FUNCTION
        ).priority(220)
            .color(NodeDefinition.NodeCategory.FUNCTION)
            .handler(CustomFunctionCallHandler.HANDLER_ID)
            .handlerConfig(Map.of("operation", CustomFunctionCallHandler.OPERATION, "functionId", flowId))
            .owner(graph.getFunctionOwner() + ":" + graph.getFunctionNamespace())
            .schemaVersion(graph.getFunctionVersion())
            .description(graph.getFunctionDescription().isBlank() ? "Run " + displayName + "." : graph.getFunctionDescription())
            .tags(List.of("function", graph.getFunctionNamespace(), displayName));

        builder.input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION);
        if (graph.getFunctionInputs() != null) {
            List<FlowGraph.FunctionParameter> inputs = new ArrayList<>(graph.getFunctionInputs());
            inputs.removeIf(param -> param == null || param.getName() == null || param.getName().isBlank());
            requireUniqueParameterNames(inputs, "input");
            inputs.sort(Comparator.comparing(FlowGraph.FunctionParameter::getName, String.CASE_INSENSITIVE_ORDER));
            for (FlowGraph.FunctionParameter param : inputs) {
                builder.input(parameterPin(param, NodeDefinition.PinDirection.INPUT));
            }
        }

        builder.output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION);
        if (graph.getFunctionOutputs() != null) {
            List<FlowGraph.FunctionParameter> outputs = new ArrayList<>(graph.getFunctionOutputs());
            outputs.removeIf(param -> param == null || param.getName() == null || param.getName().isBlank());
            requireUniqueParameterNames(outputs, "output");
            outputs.sort(Comparator.comparing(FlowGraph.FunctionParameter::getName, String.CASE_INSENSITIVE_ORDER));
            for (FlowGraph.FunctionParameter param : outputs) {
                builder.output(parameterPin(param, NodeDefinition.PinDirection.OUTPUT));
            }
        }

        return builder.build();
    }

    private static void requireUniqueParameterNames(List<FlowGraph.FunctionParameter> parameters, String direction) {
        Set<String> names = new HashSet<>();
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (!names.add(parameter.getName())) {
                throw new IllegalArgumentException("Duplicate function " + direction + " parameter: " + parameter.getName());
            }
        }
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

    private static NodeDefinition.PinDefinition parameterPin(FlowGraph.FunctionParameter parameter, NodeDefinition.PinDirection direction) {
        NodeDefinition.PinBuilder builder = new NodeDefinition.PinBuilder(parameter.getName(), NodeDefinition.PinType.DATA, direction, normalizeType(parameter.getType()))
            .typeRef(parameter.getTypeRef());
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

    private static NodeDefinition.WidgetType widget(String widget, String optionsSource) {
        if (widget != null && !widget.isBlank()) {
            try {
                return NodeDefinition.WidgetType.valueOf(widget.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown function parameter widget: " + widget, exception);
            }
        }
        return optionsSource != null && !optionsSource.isBlank() ? NodeDefinition.WidgetType.SEARCHABLE_LIST : null;
    }
}
