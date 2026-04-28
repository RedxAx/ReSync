package restudio.resync.flow;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        List<String> sortedFlowIds = new ArrayList<>(flowIds);
        sortedFlowIds.sort(String.CASE_INSENSITIVE_ORDER);

        for (String flowId : sortedFlowIds) {
            FlowGraph graph = storage.getGraph(flowId);
            if (graph == null || !graph.isFunction()) {
                continue;
            }
            definitions.add(buildDefinition(graph));
        }

        definitionRegistry.registerAll(PLUGIN_ID, definitions);
        return definitions;
    }

    private static NodeDefinition buildDefinition(FlowGraph graph) {
        String flowId = graph.getId();
        String displayName = toDisplayName(flowId);
        NodeDefinition.Builder builder = new NodeDefinition.Builder(
            NODE_PREFIX + flowId,
            displayName,
            NodeDefinition.NodeCategory.FUNCTION
        ).priority(220).color(NodeDefinition.NodeCategory.FUNCTION);

        builder.input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION);
        if (graph.getFunctionInputs() != null) {
            List<FlowGraph.FunctionParameter> inputs = new ArrayList<>(graph.getFunctionInputs());
            inputs.sort(Comparator.comparing(FlowGraph.FunctionParameter::getName, String.CASE_INSENSITIVE_ORDER));
            for (FlowGraph.FunctionParameter param : inputs) {
                if (param == null || param.getName() == null || param.getName().isBlank()) {
                    continue;
                }
                builder.input(param.getName(), NodeDefinition.PinType.DATA, normalizeType(param.getType()));
            }
        }

        builder.output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION);
        if (graph.getFunctionOutputs() != null) {
            List<FlowGraph.FunctionParameter> outputs = new ArrayList<>(graph.getFunctionOutputs());
            outputs.sort(Comparator.comparing(FlowGraph.FunctionParameter::getName, String.CASE_INSENSITIVE_ORDER));
            for (FlowGraph.FunctionParameter param : outputs) {
                if (param == null || param.getName() == null || param.getName().isBlank()) {
                    continue;
                }
                builder.output(param.getName(), NodeDefinition.PinType.DATA, normalizeType(param.getType()));
            }
        }

        return builder.build();
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
}
