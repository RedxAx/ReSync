package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowStorage;
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
            FlowStorage storage = FlowRuntimeAccess.getStorage();
            if (storage == null) {
                storage = new FlowStorage(ReSync.getInstance());
            }
            FlowGraph functionGraph = storage.getGraph(functionName);
            if (functionGraph != null) {
                String returnNodeId = ctx.resolveNodeId(node);
                ctx.getRuntime().callFunction(functionGraph, returnNodeId);
            } else {
                Log.warn("[Flow] Function not found: " + functionName);
                ctx.triggerOutput("flow");
            }
        });

        operations.put("return_value", (ctx, node) -> {
            Object returnValue = ctx.getInputValue(node, "value", Object.class, null);
            if (!ctx.getRuntime().returnFromFunction(returnValue)) {
                Log.warn("[Flow] return called outside function");
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
            Object value = ctx.getRuntime().getFunctionInput(name);
            ctx.setOutput(node, "value", value);
        });

        operations.put("function_output", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
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
        if (op != null) {
            op.accept(ctx, node);
        }
        if (operation != null && selfManagingOutputs.contains(operation)) {
            return;
        }
        ctx.triggerOutput("flow");
    }
}
