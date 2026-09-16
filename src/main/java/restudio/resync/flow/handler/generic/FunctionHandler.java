package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FunctionCallSupport;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class FunctionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final Set<String> selfManagingOutputs = Set.of("call_function", "return_value", "function_end", "function_output");

    public FunctionHandler() {
        operations.put("call_function", (ctx, node) -> {
            String functionName = ctx.getInputValue(node, "function", String.class, "");
            if (functionName == null || functionName.isBlank()) {
                throw new FlowHandlerException("FUNCTION_ID_REQUIRED", "Function ID is required", "Select an existing function");
            }
            throw new FlowHandlerException("FUNCTION_DISPATCH_INVALID", "Function call bypassed executor dispatch",
                "Reload the Flow runtime and retry the function call", Map.of("functionId", functionName));
        });

        operations.put("argument", (ctx, node) -> {
            Map<String, Object> arguments = new LinkedHashMap<>();
            Map<?, ?> existing = ctx.getInputValue(node, "arguments", Map.class, Map.of());
            if (existing != null) {
                existing.forEach((key, value) -> {
                    if (key != null) {
                        arguments.put(key.toString(), value);
                    }
                });
            }
            String name = ctx.getInputValue(node, "name", String.class, "");
            FunctionParameterId parameterId = parameterId(ctx, node, "parameterId", name);
            if ((name == null || name.isBlank()) && parameterId == null) {
                throw new FlowHandlerException("FUNCTION_ARGUMENT_NAME_REQUIRED", "Argument name is required",
                    "Enter the matching function input name");
            }
            arguments.put(parameterId != null ? parameterId.canonicalText() : name.trim(),
                ctx.getInputValue(node, "value", Object.class, null));
            ctx.setOutput(node, "arguments", arguments);
        });

        operations.put("result", (ctx, node) -> {
            Map<?, ?> results = ctx.getInputValue(node, "results", Map.class, Map.of());
            String name = ctx.getInputValue(node, "name", String.class, "");
            FunctionParameterId parameterId = parameterId(ctx, node, "parameterId", name);
            if ((name == null || name.isBlank()) && parameterId == null) {
                throw new FlowHandlerException("FUNCTION_RESULT_NAME_REQUIRED", "Result name is required",
                    "Enter the matching function output name");
            }
            Object value = results != null && parameterId != null ? results.get(parameterId.canonicalText()) : null;
            if (value == null && results != null && parameterId == null) {
                value = results.get(name.trim());
            }
            ctx.setOutput(node, "value", value);
        });

        operations.put("return_value", (ctx, node) -> {
            Object returnValue = ctx.getInputValue(node, "value", Object.class, null);
            if (ctx.getRuntime().getCallDepth() <= 0 || !ctx.getRuntime().returnFromFunction(returnValue)) {
                throw new FlowHandlerException("FUNCTION_RETURN_OUTSIDE_CALL", "Return was used outside an active function call",
                    "Move Return into a callable function path");
            }
        });

        operations.put("function_start", (ctx, node) -> {
            FlowGraph graph = ctx.getRuntime().getGraph();
            if (graph != null && graph.getFunctionInputs() != null) {
                for (FlowGraph.FunctionParameter parameter : graph.getFunctionInputs()) {
                    if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                        continue;
                    }
                    Object value = runtimeInput(ctx, parameter);
                    String pin = CustomFunctionNodeDefinitions.parameterPinId(parameter,
                        NodeDefinition.PinDirection.OUTPUT).value();
                    ctx.setOutput(node, pin, value);
                    if (parameter.getParameterId() == null) {
                        ctx.setOutput(node, parameter.getName(), value);
                    }
                }
            }
        });

        operations.put("function_end", (ctx, node) -> {
            FlowGraph graph = ctx.getRuntime().getGraph();
            Map<String, Object> values = new HashMap<>();
            Map<FunctionParameterId, Object> identityValues = new LinkedHashMap<>();
            if (graph != null && graph.getFunctionOutputs() != null) {
                for (FlowGraph.FunctionParameter parameter : graph.getFunctionOutputs()) {
                    if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                        continue;
                    }
                    String pin = CustomFunctionNodeDefinitions.parameterPinId(parameter,
                        NodeDefinition.PinDirection.INPUT).value();
                    Object value = ctx.getInputValue(node, pin, Object.class, null);
                    if (value == null && parameter.isLegacyNameOnly()) {
                        value = ctx.getInputValue(node, parameter.getName(), Object.class, null);
                    }
                    if (parameter.getParameterId() != null) {
                        identityValues.put(parameter.getParameterId(), value);
                    } else {
                        values.put(parameter.getName(), value);
                    }
                }
            }
            boolean returned = identityValues.isEmpty()
                ? ctx.getRuntime().returnFromFunction(values)
                : ctx.getRuntime().returnFromFunctionById(identityValues);
            if (!returned) {
                ctx.triggerOutput("flow");
            }
        });

        operations.put("function_input", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            FunctionParameterId explicit = FunctionCallSupport.parameterId(ctx.getInputValue(node, "parameterId", Object.class, null));
            if ((name == null || name.isBlank()) && explicit == null) {
                throw new FlowHandlerException("FUNCTION_INPUT_NAME_REQUIRED", "Function input name is required",
                    "Select a declared function input");
            }
            FunctionParameterId parameterId = parameterId(ctx, node, "parameterId", name);
            Object value = parameterId != null ? ctx.getRuntime().getFunctionInput(parameterId)
                : ctx.getRuntime().getFunctionInput(name.trim());
            ctx.setOutput(node, "value", value);
        });

        operations.put("function_output", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            FunctionParameterId parameterId = parameterId(ctx, node, "parameterId", name);
            if ((name == null || name.isBlank()) && parameterId == null) {
                throw new FlowHandlerException("FUNCTION_OUTPUT_NAME_REQUIRED", "Function output name is required",
                    "Select a declared function output");
            }
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            boolean returned = ctx.getRuntime().getCallDepth() > 0 && (parameterId != null
                ? ctx.getRuntime().returnFromFunctionById(Map.of(parameterId, value))
                : ctx.getRuntime().returnFromFunction(Map.of(name.trim(), value)));
            if (!returned) {
                ctx.triggerOutput("flow");
            }
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("FunctionHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown function operation: " + operation);
        }
        op.accept(ctx, node);
        if (operation != null && selfManagingOutputs.contains(operation)) {
            return;
        }
        ctx.triggerOutput("flow");
    }

    private Object runtimeInput(FlowContext ctx, FlowGraph.FunctionParameter parameter) {
        if (parameter.getParameterId() != null) {
            return ctx.getRuntime().getFunctionInput(parameter.getParameterId());
        }
        return ctx.getRuntime().getFunctionInput(parameter.getName());
    }

    private FunctionParameterId parameterId(FlowContext ctx, FlowNode node, String pin, String name) {
        FunctionParameterId explicit = FunctionCallSupport.parameterId(ctx.getInputValue(node, pin, Object.class, null));
        if (explicit != null) {
            return explicit;
        }
        FlowGraph graph = ctx.getRuntime() != null ? ctx.getRuntime().getGraph() : null;
        List<FlowGraph.FunctionParameter> parameters = graph != null && graph.getFunctionInputs() != null
            ? graph.getFunctionInputs() : List.of();
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter != null && name != null && name.equals(parameter.getName())) {
                if (parameter.getParameterId() != null) {
                    throw new FlowHandlerException("FUNCTION_PARAMETER_ID_REQUIRED",
                        "Function parameter ID is required", "Select the declared Function parameter by ID");
                }
                return null;
            }
        }
        if (graph != null && graph.getFunctionOutputs() != null) {
            for (FlowGraph.FunctionParameter parameter : graph.getFunctionOutputs()) {
                if (parameter != null && name != null && name.equals(parameter.getName())) {
                    if (parameter.getParameterId() != null) {
                        throw new FlowHandlerException("FUNCTION_PARAMETER_ID_REQUIRED",
                            "Function parameter ID is required", "Select the declared Function parameter by ID");
                    }
                    return null;
                }
            }
        }
        return null;
    }
}
