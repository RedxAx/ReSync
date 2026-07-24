package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public final class ResourceValueHandler implements NodeHandler {
    private static final Gson GSON = new Gson();
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new LinkedHashMap<>();

    public ResourceValueHandler() {
        operations.put("run_graph", this::runGraph);
        operations.put("run_graph_id", this::runGraphId);
        operations.put("call_function", this::callFunction);
        operations.put("motd_details", this::motdDetails);
        operations.put("resource_details", this::resourceDetails);
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ResourceValueHandler", this);
    }

    @Override
    public Set<String> getSupportedOperations() {
        return Set.copyOf(operations.keySet());
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation", "");
        BiConsumer<FlowContext, FlowNode> action = operations.get(operation);
        if (action == null) {
            throw new IllegalArgumentException("Unknown resource value operation: " + operation);
        }
        action.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private void runGraph(FlowContext ctx, FlowNode node) {
        FlowGraph graph = ctx.getInputValue(node, "value", FlowGraph.class, null);
        if (graph == null) {
            throw new FlowHandlerException("GRAPH_VALUE_REQUIRED", "Flow or command value is required",
                "Connect the matching Get Flow or Get Command value");
        }
        executeGraph(ctx, node, graph);
    }

    private void runGraphId(FlowContext ctx, FlowNode node) {
        String inputPin = node.getHandlerConfig().getString("resource_pin", "resource");
        String resourceId = ctx.getInputValue(node, inputPin, String.class, "");
        if (resourceId == null || resourceId.isBlank()) {
            throw new FlowHandlerException("GRAPH_ID_REQUIRED", "Flow or command is required",
                "Select the Flow or command to run");
        }
        FlowStorage storage = FlowRuntimeAccess.getStorage();
        FlowGraph graph = storage != null ? storage.getGraph(resourceId) : null;
        String expectedType = node.getHandlerConfig().getString("resource_type", "");
        if (graph == null || !expectedType.equals(storage.getGraphResourceType(resourceId))) {
            throw new FlowHandlerException("GRAPH_NOT_FOUND", "Flow or command not found: " + resourceId,
                "Select an existing " + ("command".equals(expectedType) ? "command" : "Flow"));
        }
        executeGraph(ctx, node, graph);
    }

    private void executeGraph(FlowContext ctx, FlowNode node, FlowGraph graph) {
        FlowExecutor executor = requireExecutor(ctx);
        CompletableFuture<Void> execution = executor.execute(graph, ctx.getPlayer(), ctx.getEvent(),
            new LinkedHashMap<>(ctx.getRuntime().getEventVariables()));
        ctx.awaitBeforeContinuation(execution.thenRun(() ->
            ctx.setOutput(node, "result", FlowOperationResult.success(graph.getId()))));
    }

    private void callFunction(FlowContext ctx, FlowNode node) {
        FlowGraph function = ctx.getInputValue(node, "value", FlowGraph.class, null);
        if (function == null || !function.isFunction()) {
            throw new FlowHandlerException("FUNCTION_VALUE_REQUIRED", "Function value is required",
                "Connect the Value output from Get Function");
        }
        Map<String, Object> arguments = functionArguments(function, ctx.getInputValue(node, "arguments", Object.class, null));
        CompletableFuture<Map<String, Object>> execution = requireExecutor(ctx).executeFunction(function, ctx.getPlayer(), ctx.getEvent(), arguments,
            new LinkedHashMap<>(ctx.getRuntime().getEventVariables()));
        ctx.awaitBeforeContinuation(execution.thenAccept(results -> {
            ctx.setOutput(node, "results", results);
            ctx.setOutput(node, "result", FlowOperationResult.success(results));
        }));
    }

    private Map<String, Object> functionArguments(FlowGraph function, Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            values.forEach((name, argument) -> {
                if (name != null) {
                    arguments.put(name.toString(), argument);
                }
            });
            return arguments;
        }
        if (value == null) {
            return Map.of();
        }
        List<FlowGraph.FunctionParameter> inputs = function.getFunctionInputs() != null
            ? function.getFunctionInputs().stream()
                .filter(input -> input != null && input.getName() != null && !input.getName().isBlank())
                .toList()
            : List.of();
        if (inputs.size() != 1) {
            throw new FlowHandlerException("FUNCTION_ARGUMENTS_NEED_NAMES",
                "This function has multiple inputs, so each value needs an argument name",
                "Use Add Function Argument nodes and connect their Arguments output");
        }
        return Map.of(inputs.getFirst().getName(), value);
    }

    private void motdDetails(FlowContext ctx, FlowNode node) {
        JsonObject profile = ctx.getInputValue(node, "profile", JsonObject.class, null);
        if (profile == null) {
            throw new FlowHandlerException("MOTD_PROFILE_REQUIRED", "MOTD profile is required",
                "Connect the Value output from Get MOTD Profile");
        }
        ctx.setOutput(node, "id", text(profile, "id"));
        ctx.setOutput(node, "line_1", text(profile, "line1"));
        ctx.setOutput(node, "line_2", text(profile, "line2"));
        ctx.setOutput(node, "priority", number(profile, "priority"));
        ctx.setOutput(node, "online_players", number(profile, "onlinePlayers"));
        ctx.setOutput(node, "max_players", number(profile, "maxPlayers"));
    }

    private void resourceDetails(FlowContext ctx, FlowNode node) {
        Object value = ctx.getInputValue(node, "value", Object.class, null);
        if (value == null) {
            throw new FlowHandlerException("RESOURCE_VALUE_REQUIRED", "Loaded value is required",
                "Connect the matching Get node");
        }
        JsonElement serialized = value instanceof JsonElement json ? json.deepCopy() : GSON.toJsonTree(value);
        JsonObject details;
        if (serialized.isJsonObject()) {
            details = serialized.getAsJsonObject();
        } else {
            details = new JsonObject();
            details.add("value", serialized);
        }
        ctx.setOutput(node, "id", resourceId(details));
        ctx.setOutput(node, "details", structuredValue(details));
    }

    private FlowExecutor requireExecutor(FlowContext ctx) {
        FlowExecutor executor = ctx.getExecutor();
        if (executor == null) {
            throw new FlowHandlerException("FLOW_EXECUTOR_UNAVAILABLE", "Flow executor is unavailable",
                "Reload the Flow runtime and retry");
        }
        return executor;
    }

    private String text(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private double number(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsDouble() : 0;
    }

    private String resourceId(JsonObject value) {
        for (String key : List.of("id", "name", "key", "worldName")) {
            if (value.has(key) && value.get(key).isJsonPrimitive()) {
                return value.get(key).getAsString();
            }
        }
        return "";
    }

    private Object structuredValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            value.getAsJsonObject().entrySet().forEach(entry -> fields.put(entry.getKey(), structuredValue(entry.getValue())));
            return fields;
        }
        if (value.isJsonArray()) {
            return value.getAsJsonArray().asList().stream().map(this::structuredValue).toList();
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        if (value.getAsJsonPrimitive().isNumber()) {
            return value.getAsNumber();
        }
        return value.getAsString();
    }
}
