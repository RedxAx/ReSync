package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.HashMap;
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

        operations.put("return_value", (ctx, node) -> {
            Object returnValue = ctx.getInputValue(node, "value", Object.class, null);
            if (!ctx.getRuntime().returnFromFunction(returnValue)) {
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
                    Object value = ctx.getRuntime().getFunctionInput(parameter.getName());
                    ctx.setOutput(node, parameter.getName(), value);
                }
            }
        });

        operations.put("function_end", (ctx, node) -> {
            FlowGraph graph = ctx.getRuntime().getGraph();
            Map<String, Object> values = new HashMap<>();
            if (graph != null && graph.getFunctionOutputs() != null) {
                for (FlowGraph.FunctionParameter parameter : graph.getFunctionOutputs()) {
                    if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                        continue;
                    }
                    values.put(parameter.getName(), ctx.getInputValue(node, parameter.getName(), Object.class, null));
                }
            }
            if (!ctx.getRuntime().returnFromFunction(values)) {
                ctx.triggerOutput("flow");
            }
        });

        operations.put("function_input", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            if (name == null || name.isBlank()) {
                throw new FlowHandlerException("FUNCTION_INPUT_NAME_REQUIRED", "Function input name is required",
                    "Select a declared function input");
            }
            Object value = ctx.getRuntime().getFunctionInput(name);
            ctx.setOutput(node, "value", value);
        });

        operations.put("function_output", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            if (name == null || name.isBlank()) {
                throw new FlowHandlerException("FUNCTION_OUTPUT_NAME_REQUIRED", "Function output name is required",
                    "Select a declared function output");
            }
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            Map<String, Object> values = new HashMap<>();
            values.put(name, value);
            if (!ctx.getRuntime().returnFromFunction(values)) {
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
}
