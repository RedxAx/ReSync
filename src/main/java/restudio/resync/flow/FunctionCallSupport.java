package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.Log;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.identity.FunctionParameterId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
        FlowExecutor.FunctionInvocationContext invocation = executor == null ? null
            : executor.defaultFunctionInvocationContext(player, event, vars);
        return evaluate(storage, executor, call, player, event, vars, invocation);
    }

    public static boolean evaluate(FlowStorage storage, FlowExecutor executor, JsonObject call, Player player, Event event,
                                   Map<String, Object> vars, FlowExecutor.FunctionInvocationContext invocation) {
        if (call == null || call.isEmpty() || !hasCallableFunction(call)) {
            return true;
        }
        if (executor == null) {
            throw new FlowHandlerException("FUNCTION_EXECUTOR_UNAVAILABLE", "Function executor is unavailable",
                "Restore the Flow runtime before evaluating this function");
        }
        FlowGraph function = requireFunction(storage, call);
        try {
            Map<String, Object> outputs = invocation == null
                ? executor.executeFunction(function, player, event, inputs(function, call, player, vars), vars)
                    .get(5, TimeUnit.SECONDS)
                : executor.executeFunction(function, player, event, inputs(function, call, player, vars), vars, invocation)
                    .get(5, TimeUnit.SECONDS);
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
        FlowExecutor.FunctionInvocationContext invocation = executor == null ? null
            : executor.defaultFunctionInvocationContext(player, event, vars);
        return execute(storage, executor, call, player, event, vars, invocation);
    }

    public static CompletableFuture<Map<String, Object>> execute(FlowStorage storage, FlowExecutor executor, JsonObject call,
                                                                  Player player, Event event, Map<String, Object> vars,
                                                                  FlowExecutor.FunctionInvocationContext invocation) {
        if (call == null || call.isEmpty() || !hasCallableFunction(call)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (executor == null) {
            throw new FlowHandlerException("FUNCTION_EXECUTOR_UNAVAILABLE", "Function executor is unavailable",
                "Restore the Flow runtime before executing this function");
        }
        FlowGraph function = requireFunction(storage, call);
        CompletableFuture<Map<String, Object>> execution = invocation == null
            ? executor.executeFunction(function, player, event, inputs(function, call, player, vars), vars)
            : executor.executeFunction(function, player, event, inputs(function, call, player, vars), vars, invocation);
        return execution.whenComplete((result, error) -> {
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
            FlowGraph graph;
            try {
                graph = FlowSerializer.deserialize(call.getAsJsonObject("graph").toString());
            } catch (RuntimeException failure) {
                throw new FlowHandlerException("FUNCTION_INLINE_SERIALIZATION_FAILED", "Inline function could not be restored",
                    "Persist the function graph with the active Flow serializer before calling it",
                    Map.of("cause", failure.getClass().getSimpleName()));
            }
            if (graph != null) {
                graph.setFunction(true);
            }
            return graph;
        }
        String functionId = requestedFunctionId(call);
        return storage != null && !functionId.isBlank() && !"none".equalsIgnoreCase(functionId) ? storage.getGraph("function", functionId) : null;
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
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (function != null && function.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter parameter : function.getFunctionInputs()) {
                if (parameter != null && parameter.getName() != null && !parameter.getName().isBlank() && parameter.getDefaultValue() != null && !parameter.getDefaultValue().isBlank()) {
                    inputs.put(parameterKey(parameter), coerce(value(parameter.getDefaultValue(), player, vars), parameter.getType()));
                }
            }
            for (FlowGraph.FunctionParameter parameter : function.getFunctionInputs()) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isBlank() || inputs.containsKey(parameterKey(parameter))) {
                    continue;
                }
                Object contextValue = contextValue(parameter, player, vars);
                if (contextValue != null) {
                    inputs.put(parameterKey(parameter), coerce(contextValue, parameter.getType()));
                }
            }
        }
        if (function != null && function.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter parameter : function.getFunctionInputs()) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                    continue;
                }
                JsonElement configured = configuredValue(call, parameter);
                if (configured != null) {
                    inputs.put(parameterKey(parameter), coerce(value(configured, player, vars), parameter.getType()));
                }
            }
        }
        return inputs;
    }

    public static String parameterKey(FlowGraph.FunctionParameter parameter) {
        Objects.requireNonNull(parameter, "Function parameter is required");
        FunctionParameterId id = parameter.getParameterId();
        if (id != null) {
            return id.canonicalText();
        }
        return parameter.getName() != null ? parameter.getName().trim() : "";
    }

    public static String parameterPinKey(FlowGraph.FunctionParameter parameter, boolean input) {
        FunctionParameterId id = parameter != null ? parameter.getParameterId() : null;
        if (id == null) {
            return parameterKey(parameter);
        }
        return (input ? "function-input-" : "function-output-") + id.canonicalText();
    }

    public static FunctionParameterId parameterId(Object raw) {
        if (raw instanceof FunctionParameterId id) {
            return id;
        }
        if (raw instanceof Map<?, ?> value) {
            Object nested = value.get("value");
            if (nested == null) {
                nested = value.get("canonicalText");
            }
            return parameterId(nested);
        }
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        try {
            return FunctionParameterId.parseCanonicalText(raw.toString().trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static Map<String, Object> normalizeArguments(FlowGraph function, Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> supplied)) {
            List<FlowGraph.FunctionParameter> parameters = function != null && function.getFunctionInputs() != null
                ? function.getFunctionInputs().stream().filter(Objects::nonNull).filter(parameter -> parameter.getName() != null
                    && !parameter.getName().isBlank()).toList() : List.of();
            if (parameters.size() != 1) {
                throw new FlowHandlerException("FUNCTION_ARGUMENTS_NEED_NAMES",
                    "This function has multiple inputs, so each value needs an argument name",
                    "Use Add Function Argument nodes and connect their Arguments output");
            }
            return Map.of(parameterKey(parameters.getFirst()), value);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        supplied.forEach((key, argument) -> {
            if (key == null) {
                return;
            }
            arguments.put(argumentKey(function, key), argument);
        });
        return arguments;
    }

    public static Object valueForArgument(Map<?, ?> values, FlowGraph.FunctionParameter parameter) {
        if (values == null || parameter == null) {
            return null;
        }
        String key = parameterKey(parameter);
        if (values.containsKey(key)) {
            return values.get(key);
        }
        if (parameter.isLegacyNameOnly()) {
            String name = parameter.getName();
            return name != null ? values.get(name) : null;
        }
        return null;
    }

    private static String argumentKey(FlowGraph function, Object rawKey) {
        FunctionParameterId id = parameterId(rawKey);
        String text = rawKey.toString().trim();
        if (id != null) {
            return id.canonicalText();
        }
        if (function != null && function.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter parameter : function.getFunctionInputs()) {
                if (parameter == null || parameter.getName() == null) {
                    continue;
                }
                if (text.equals(parameterPinKey(parameter, true))
                    || (parameter.isLegacyNameOnly() && text.equals(parameter.getName()))) {
                    return parameterKey(parameter);
                }
            }
        }
        return text;
    }

    private static JsonElement configuredValue(JsonObject call, FlowGraph.FunctionParameter parameter) {
        for (String field : List.of("inputs", "arguments", "parameterValues")) {
            if (!call.has(field) || !call.get(field).isJsonObject()) {
                continue;
            }
            JsonObject values = call.getAsJsonObject(field);
            List<String> keys = parameter.isLegacyNameOnly()
                ? List.of(parameterKey(parameter), parameter.getName())
                : List.of(parameterKey(parameter), parameterPinKey(parameter, true));
            for (String key : keys) {
                if (key != null && !key.isBlank() && values.has(key)) {
                    return unwrapConfiguredValue(values.get(key));
                }
            }
        }
        for (String field : List.of("parameters", "inputs", "arguments")) {
            if (!call.has(field) || !call.get(field).isJsonArray()) {
                continue;
            }
            for (JsonElement item : call.getAsJsonArray(field)) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject entry = item.getAsJsonObject();
                FunctionParameterId id = parameterId(entry.has("parameterId") ? entry.get("parameterId") : null);
                if ((id != null && parameter.getParameterId() != null && id.equals(parameter.getParameterId()))
                    || (parameter.isLegacyNameOnly() && text(entry, "name").equals(parameter.getName()))) {
                    return unwrapConfiguredValue(entry.has("value") ? entry.get("value") : entry.get("input"));
                }
            }
        }
        return null;
    }

    private static JsonElement unwrapConfiguredValue(JsonElement value) {
        if (value != null && value.isJsonObject() && value.getAsJsonObject().has("value")) {
            return value.getAsJsonObject().get("value");
        }
        return value;
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
