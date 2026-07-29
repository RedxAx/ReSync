package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FunctionCatalogHandler implements NodeHandler {
    private static final Set<String> OPERATIONS = Set.of("list", "find", "exists", "index", "at_index", "filter", "describe");
    private final FlowStorage storage;

    public FunctionCatalogHandler(FlowStorage storage) {
        this.storage = storage;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("FunctionCatalogHandler", this);
    }

    @Override
    public Set<String> getSupportedOperations() {
        return OPERATIONS;
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation", "");
        switch (operation) {
            case "list" -> ctx.setOutput(node, "functions", functions());
            case "find" -> find(ctx, node);
            case "exists" -> ctx.setOutput(node, "exists", function(text(ctx, node, "function")) != null);
            case "index" -> index(ctx, node);
            case "at_index" -> atIndex(ctx, node);
            case "filter" -> filter(ctx, node);
            case "describe" -> describe(ctx, node);
            default -> throw new IllegalArgumentException("Unknown function catalog operation: " + operation);
        }
    }

    private void find(FlowContext ctx, FlowNode node) {
        String query = text(ctx, node, "name");
        String found = functions().stream().filter(id -> id.equalsIgnoreCase(query)).findFirst().orElse("");
        boolean available = !found.isBlank();
        ctx.setOutput(node, "function", found);
        ctx.setOutput(node, "found", available);
        ctx.setOutput(node, "result", available
            ? FlowOperationResult.success(found)
            : FlowOperationResult.failure("FUNCTION_NOT_FOUND", "Function Not Found", Map.of("name", query)));
    }

    private void index(FlowContext ctx, FlowNode node) {
        List<String> values = stringList(ctx.getInputValue(node, "functions", List.class, List.of()));
        if (values.isEmpty()) {
            values = functions();
        }
        String function = text(ctx, node, "function");
        int index = -1;
        for (int current = 0; current < values.size(); current++) {
            if (values.get(current).equalsIgnoreCase(function)) {
                index = current;
                break;
            }
        }
        ctx.setOutput(node, "index", index);
        ctx.setOutput(node, "found", index >= 0);
    }

    private void filter(FlowContext ctx, FlowNode node) {
        String query = text(ctx, node, "query").toLowerCase(Locale.ROOT);
        List<String> matches = query.isBlank() ? functions() : functions().stream()
            .filter(id -> id.toLowerCase(Locale.ROOT).contains(query))
            .toList();
        ctx.setOutput(node, "functions", matches);
    }

    private void atIndex(FlowContext ctx, FlowNode node) {
        List<String> values = stringList(ctx.getInputValue(node, "functions", List.class, List.of()));
        if (values.isEmpty()) {
            values = functions();
        }
        int index = ctx.getInputValue(node, "index", Integer.class, -1);
        boolean available = index >= 0 && index < values.size();
        String function = available ? values.get(index) : "";
        ctx.setOutput(node, "function", function);
        ctx.setOutput(node, "found", available);
        ctx.setOutput(node, "result", available
            ? FlowOperationResult.success(function)
            : FlowOperationResult.failure("FUNCTION_INDEX_OUT_OF_RANGE", "Function Index Is Out Of Range", Map.of("index", index, "size", values.size())));
    }

    private void describe(FlowContext ctx, FlowNode node) {
        String id = text(ctx, node, "function");
        FlowGraph function = function(id);
        if (function == null) {
            ctx.setOutput(node, "inputs", Map.of());
            ctx.setOutput(node, "outputs", Map.of());
            ctx.setOutput(node, "result", FlowOperationResult.failure("FUNCTION_NOT_FOUND", "Function Not Found", Map.of("function", id)));
            return;
        }
        ctx.setOutput(node, "inputs", parameters(function.getFunctionInputs()));
        ctx.setOutput(node, "outputs", parameters(function.getFunctionOutputs()));
        ctx.setOutput(node, "result", FlowOperationResult.success(id));
    }

    private Map<String, String> parameters(List<FlowGraph.FunctionParameter> parameters) {
        Map<String, String> signature = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.stream()
                .filter(parameter -> parameter != null && parameter.getName() != null && !parameter.getName().isBlank())
                .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.getName(), right.getName()))
                .forEach(parameter -> signature.put(parameter.getName(), parameter.getTypeRef().toString()));
        }
        return signature;
    }

    private List<String> functions() {
        if (storage == null) {
            return List.of();
        }
        return storage.listFlowIds().stream()
            .filter(id -> function(id) != null)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private FlowGraph function(String id) {
        if (storage == null || id == null || id.isBlank()) {
            return null;
        }
        FlowGraph graph = storage.getGraph("function", id);
        return graph != null && graph.isFunction() ? graph : null;
    }

    private String text(FlowContext ctx, FlowNode node, String pin) {
        String value = ctx.getInputValue(node, pin, String.class, "");
        return value != null ? value.trim() : "";
    }

    private List<String> stringList(List<?> values) {
        return values.stream().filter(value -> value != null).map(Object::toString).toList();
    }
}
