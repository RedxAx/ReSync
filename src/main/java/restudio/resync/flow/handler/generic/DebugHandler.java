package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.Log;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class DebugHandler implements NodeHandler {
    private static boolean debugMode = false;
    private final ConcurrentHashMap<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public DebugHandler() {
        operations.put("debug_log", (ctx, node) -> {
            String message = ctx.getInputValue(node, "message", String.class, "");
            String level = ctx.getInputValue(node, "level", String.class, "INFO");

            switch (level.toUpperCase()) {
                case "WARN" -> Log.warn("[Flow:Debug] " + message);
                case "ERROR" -> Log.error("[Flow:Debug] " + message);
                default -> Log.info("[Flow:Debug] " + message);
            }
        });

        operations.put("debug_print_variable", (ctx, node) -> {
            String variableName = ctx.getInputValue(node, "variable_name", String.class, "");
            Object value = ctx.getVariable(variableName);

            Log.info("[Flow:Debug] Variable '" + variableName + "' = " +
                    (value != null ? value : "null") +
                    " (" + (value != null ? value.getClass().getSimpleName() : "null") + ")");

            ctx.setOutput(node, "value", value);
        });

        operations.put("debug_dump_variables", (ctx, node) -> {
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            List<String> variableInfo = new ArrayList<>();

            if ("global".equalsIgnoreCase(scope)) {
                Map<String, Object> globals = ctx.getGlobalVariables();
                for (Map.Entry<String, Object> entry : globals.entrySet()) {
                    variableInfo.add(entry.getKey() + " = " + (entry.getValue() != null ? entry.getValue() : "null"));
                }
                Log.info("[Flow:Debug] Global Variables (" + globals.size() + "):");
            } else {
                Map<String, Object> locals = ctx.getLocalVariables();
                for (Map.Entry<String, Object> entry : locals.entrySet()) {
                    variableInfo.add(entry.getKey() + " = " + (entry.getValue() != null ? entry.getValue() : "null"));
                }
                Log.info("[Flow:Debug] Local Variables (" + locals.size() + "):");
            }

            for (String info : variableInfo) {
                Log.info("[Flow:Debug]   " + info);
            }

            ctx.setOutput(node, "variables", variableInfo);
        });

        operations.put("debug_stack_trace", (ctx, node) -> {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            List<String> stackList = new ArrayList<>();

            Log.info("[Flow:Debug] Execution Stack:");
            for (int i = 1; i < Math.min(stackTrace.length, 20); i++) {
                String trace = stackTrace[i].toString();
                stackList.add(trace);
                Log.info("[Flow:Debug]   " + trace);
            }

            ctx.setOutput(node, "stack_trace", String.join("\n", stackList));
        });

        operations.put("debug_break", (ctx, node) -> {
            if (debugMode) {
                Log.warn("[Flow:Debug] BREAKPOINT HIT");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("DebugHandler", this);
    }

    public static boolean isDebugMode() {
        return debugMode;
    }

    public static void setDebugMode(boolean enabled) {
        debugMode = enabled;
        Log.info("[Flow] Debug mode " + (enabled ? "enabled" : "disabled"));
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown debug operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }
}
