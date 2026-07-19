package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.resync.Log;
import restudio.resync.flow.handler.FlowHandlerException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class FunctionCallSupport {
    private static final Gson GSON = new Gson();

    private FunctionCallSupport() {
    }

    public static boolean evaluate(FlowStorage storage, FlowExecutor executor, JsonObject call, Player player, Event event, Map<String, Object> vars) {
        if (call == null || call.isEmpty() || !hasCallableFunction(call)) {
            return true;
        }
        if (executor == null) {
            throw new FlowHandlerException("FUNCTION_EXECUTOR_UNAVAILABLE", "Function executor is unavailable",
                "Restore the Flow runtime before evaluating this function");
        }
        FlowGraph function = requireFunction(storage, call);
        try {
            Map<String, Object> outputs = executor.executeFunction(function, player, event, inputs(function, call, player, vars), vars).get(5, TimeUnit.SECONDS);
            Object result = first(outputs, "condition", "result", "return", "success");
            return result instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(result));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FlowHandlerException("FUNCTION_EVALUATION_INTERRUPTED", "Function evaluation was interrupted: " + function.getId(),
                "Retry the operation when the Flow runtime is available", Map.of("functionId", function.getId()));
        } catch (TimeoutException exception) {
            throw new FlowHandlerException("FUNCTION_EVALUATION_TIMEOUT", "Function evaluation timed out: " + function.getId(),
                "Reduce the function work or use an asynchronous action", Map.of("functionId", function.getId(), "timeoutSeconds", 5));
        } catch (ExecutionException exception) {
            Throwable cause = unwrapFailure(exception);
            if (cause instanceof FlowHandlerException handlerFailure) {
                throw handlerFailure;
            }
            if (cause instanceof FlowExecutor.FlowExecutionException executionFailure) {
                throw new FlowHandlerException(executionFailure.getCode(), executionFailure.getMessage(), executionFailure.getRemediation(),
                    executionFailure.getDetails());
            }
            throw new IllegalStateException("Function evaluation failed: " + function.getId(), cause);
        }
    }

    public static CompletableFuture<Map<String, Object>> execute(FlowStorage storage, FlowExecutor executor, JsonObject call, Player player, Event event,
                                                                  Map<String, Object> vars) {
        if (call == null || call.isEmpty() || !hasCallableFunction(call)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (executor == null) {
            throw new FlowHandlerException("FUNCTION_EXECUTOR_UNAVAILABLE", "Function executor is unavailable",
                "Restore the Flow runtime before executing this function");
        }
        FlowGraph function = requireFunction(storage, call);
        return executor.executeFunction(function, player, event, inputs(function, call, player, vars), vars).whenComplete((result, error) -> {
            if (error != null) Log.warn("Function execution failed for " + function.getId() + ": " + error.getMessage());
        });
    }

    private static FlowGraph requireFunction(FlowStorage storage, JsonObject call) {
        FlowGraph function = function(storage, call);
        String functionId = requestedFunctionId(call);
        if (function == null) {
            throw new FlowHandlerException("FUNCTION_NOT_FOUND", "Function not found: " + functionId,
                "Select an existing function or restore the missing function", Map.of("functionId", functionId));
        }
        if (!function.isFunction()) {
            throw new FlowHandlerException("FUNCTION_INVALID", "Flow is not callable as a function: " + functionId,
                "Select a callable function", Map.of("functionId", functionId));
        }
        return function;
    }

    private static Throwable unwrapFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static FlowGraph function(FlowStorage storage, JsonObject call) {
        String type = text(call, "type");
        if (("inlineFunction".equals(type) || "function".equals(type)) && call.has("graph") && call.get("graph").isJsonObject()) {
            FlowGraph graph = GSON.fromJson(call.getAsJsonObject("graph"), FlowGraph.class);
            if (graph != null) {
                graph.setFunction(true);
            }
            return graph;
        }
        String functionId = requestedFunctionId(call);
        return storage != null && !functionId.isBlank() && !"none".equalsIgnoreCase(functionId) ? storage.getGraph(functionId) : null;
    }

    private static String requestedFunctionId(JsonObject call) {
        String functionId = text(call, "functionId");
        return functionId.isBlank() ? text(call, "id") : functionId;
    }

    private static boolean hasCallableFunction(JsonObject call) {
        if (call.has("graph") && call.get("graph").isJsonObject()) {
            return true;
        }
        String functionId = text(call, "functionId");
        if (functionId.isBlank()) {
            functionId = text(call, "id");
        }
        return !functionId.isBlank() && !"none".equalsIgnoreCase(functionId);
    }

    private static Map<String, Object> inputs(FlowGraph function, JsonObject call, Player player, Map<String, Object> vars) {
        Map<String, Object> inputs = new HashMap<>();
        if (function != null && function.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter parameter : function.getFunctionInputs()) {
                if (parameter != null && parameter.getName() != null && !parameter.getName().isBlank() && parameter.getDefaultValue() != null && !parameter.getDefaultValue().isBlank()) {
                    inputs.put(parameter.getName(), coerce(value(parameter.getDefaultValue(), player, vars), parameter.getType()));
                }
            }
            for (FlowGraph.FunctionParameter parameter : function.getFunctionInputs()) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isBlank() || inputs.containsKey(parameter.getName())) {
                    continue;
                }
                Object contextValue = contextValue(parameter, player, vars);
                if (contextValue != null) {
                    inputs.put(parameter.getName(), coerce(contextValue, parameter.getType()));
                }
            }
        }
        JsonObject configured = call.has("inputs") && call.get("inputs").isJsonObject() ? call.getAsJsonObject("inputs") : new JsonObject();
        if (function != null && function.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter parameter : function.getFunctionInputs()) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isBlank() || !configured.has(parameter.getName())) {
                    continue;
                }
                inputs.put(parameter.getName(), coerce(value(configured.get(parameter.getName()), player, vars), parameter.getType()));
            }
        }
        return inputs;
    }

    private static Object contextValue(FlowGraph.FunctionParameter parameter, Player player, Map<String, Object> vars) {
        FlowDataType type = parameter != null ? parameter.getType() : null;
        if (type == null) {
            return null;
        }
        String name = parameter.getName() != null ? parameter.getName().toLowerCase(Locale.ROOT) : "";
        if (FlowDataType.BOOLEAN.isAssignableFrom(type)) {
            Object value;
            if (name.contains("right")) {
                value = valueFromVars(vars, "rightClick");
            } else if (name.contains("left")) {
                value = valueFromVars(vars, "leftClick");
            } else if (name.contains("shift") || name.contains("sneak")) {
                value = valueFromVars(vars, "shifting", "sneaking", "shiftClick");
            } else if (name.contains("success")) {
                value = valueFromVars(vars, "success", "event.success");
            } else {
                value = valueFromVars(vars, "success", "event.success", "rightClick", "leftClick", "shifting", "sneaking", "shiftClick");
            }
            return value != null ? value : false;
        }
        if (FlowDataType.PLAYER.isAssignableFrom(type)) {
            return player != null ? player : valueFromVars(vars, "event.player", "player");
        }
        String id = type.getId();
        if ("item".equals(id) || "material".equals(id)) {
            return valueFromVars(vars, "tradedItem", "resultItem", "event.item", "event.output", "event.source", "clickedItem", "craftedItem", "cookedItem", "sourceItem", "item", "output", "source");
        }
        if ("entity".equals(id) || "living_entity".equals(id)) {
            return valueFromVars(vars, "event.entity", "event.target", "entity", "target");
        }
        if ("block".equals(id)) {
            return valueFromVars(vars, "event.block", "block");
        }
        if ("location".equals(id)) {
            return valueFromVars(vars, "event.location", "location");
        }
        if ("number".equals(id) || "seed".equals(id) || "float".equals(id)) {
            return valueFromVars(vars, "event.slot", "slot", "event.amount", "amount");
        }
        if ("string".equals(id) || "component".equals(id)) {
            return valueFromVars(vars, "npcId", "profileId", "event.id", "hook", "event.recipe", "recipe", "event.world", "world", "event.permission", "permission");
        }
        return null;
    }

    private static Object valueFromVars(Map<String, Object> vars, String... keys) {
        if (vars == null) {
            return null;
        }
        for (String key : keys) {
            if (vars.containsKey(key) && vars.get(key) != null) {
                return vars.get(key);
            }
        }
        return null;
    }

    private static Object value(String text, Player player, Map<String, Object> vars) {
        return value(GSON.toJsonTree(text), player, vars);
    }

    private static Object value(JsonElement element, Player player, Map<String, Object> vars) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsNumber();
            }
            String text = element.getAsString();
            if ("$player".equals(text) || "$event.player".equals(text)) {
                return player;
            }
            if ("$clickedItem".equals(text)) {
                return valueFromVars(vars, "clickedItem", "event.item", "item");
            }
            if ("$craftedItem".equals(text)) {
                return valueFromVars(vars, "craftedItem", "event.output", "output");
            }
            if ("$cookedItem".equals(text)) {
                return valueFromVars(vars, "cookedItem", "event.output", "output");
            }
            if ("$sourceItem".equals(text)) {
                return valueFromVars(vars, "sourceItem", "event.source", "source");
            }
            if ("$tradedItem".equals(text)) {
                return valueFromVars(vars, "tradedItem", "event.item", "event.output", "resultItem");
            }
            if ("$resultItem".equals(text)) {
                return valueFromVars(vars, "resultItem", "event.output", "tradedItem");
            }
            if ("$location".equals(text) || "$event.location".equals(text)) {
                return valueFromVars(vars, "location", "event.location");
            }
            if ("$npcId".equals(text)) {
                return valueFromVars(vars, "npcId", "event.id");
            }
            if ("$profileId".equals(text)) {
                return valueFromVars(vars, "profileId", "event.id");
            }
            if ("$hook".equals(text)) {
                return valueFromVars(vars, "hook");
            }
            if ("$success".equals(text)) {
                return valueFromVars(vars, "success", "event.success");
            }
            if ("$rightClick".equals(text)) {
                return valueFromVars(vars, "rightClick");
            }
            if ("$leftClick".equals(text)) {
                return valueFromVars(vars, "leftClick");
            }
            if ("$shifting".equals(text) || "$sneaking".equals(text) || "$shiftClick".equals(text)) {
                return valueFromVars(vars, "shifting", "sneaking", "shiftClick");
            }
            if ("$recipe".equals(text)) {
                return valueFromVars(vars, "recipe", "event.recipe");
            }
            if ("$world".equals(text)) {
                return player != null ? player.getWorld().getName() : valueFromVars(vars, "world", "event.world");
            }
            if ("$slot".equals(text)) {
                return valueFromVars(vars, "slot", "event.slot");
            }
            if ("$amount".equals(text)) {
                return valueFromVars(vars, "amount", "event.amount");
            }
            if (text.startsWith("$") && vars != null) {
                return vars.get(text.substring(1));
            }
            return text;
        }
        return GSON.fromJson(element, Object.class);
    }

    private static Object coerce(Object value, FlowDataType type) {
        if (value == null || type == null || type == FlowDataType.ANY) {
            return value;
        }
        String id = type.getId();
        if ("boolean".equals(id)) {
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
        }
        if ("seed".equals(id)) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Function input is not a valid integer: " + value, exception);
            }
        }
        if ("float".equals(id)) {
            if (value instanceof Number number) {
                return number.floatValue();
            }
            try {
                return Float.parseFloat(String.valueOf(value));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Function input is not a valid float: " + value, exception);
            }
        }
        if ("number".equals(id)) {
            if (value instanceof Number number) {
                return number;
            }
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Function input is not a valid number: " + value, exception);
            }
        }
        if ("string".equals(id) || "component".equals(id) || "color".equals(id) || "uuid".equals(id) || "region".equals(id)) {
            return String.valueOf(value);
        }
        return value;
    }

    private static Object first(Map<String, Object> outputs, String... keys) {
        if (outputs == null || outputs.isEmpty()) {
            return false;
        }
        for (String key : keys) {
            if (outputs.containsKey(key)) {
                return outputs.get(key);
            }
        }
        return outputs.values().iterator().next();
    }

    private static String text(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
