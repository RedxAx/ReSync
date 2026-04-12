package restudio.resync.flow.nodes;

import restudio.resync.Log;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DebugNodes {

    private static boolean debugMode = false;

    @DefineNode(id = "debug_log", displayName = "Debug Log", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "message", dataType = FlowType.STRING),
                    @FlowPin(name = "level", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void debugLog(FlowContext ctx, FlowNode node) {
        String message = ctx.getInputValue(node, "message", String.class, "");
        String level = ctx.getInputValue(node, "level", String.class, "INFO");

        switch (level.toUpperCase()) {
            case "WARN" -> Log.warn("[Flow:Debug] " + message);
            case "ERROR" -> Log.error("[Flow:Debug] " + message);
            default -> Log.info("[Flow:Debug] " + message);
        }

        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "debug_print_variable", displayName = "Debug Print Variable", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "variable_name", dataType = FlowType.STRING)},
            outputs = {
                    @FlowPin(name = "value", dataType = FlowType.ANY),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void debugPrintVariable(FlowContext ctx, FlowNode node) {
        String variableName = ctx.getInputValue(node, "variable_name", String.class, "");
        Object value = ctx.getVariable(variableName);

        Log.info("[Flow:Debug] Variable '" + variableName + "' = " +
                (value != null ? value : "null") +
                " (" + (value != null ? value.getClass().getSimpleName() : "null") + ")");

        ctx.setOutput(node, "value", value);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "debug_dump_variables", displayName = "Debug Dump Variables", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "scope", dataType = FlowType.STRING)},
            outputs = {
                    @FlowPin(name = "variables", dataType = FlowType.LIST),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void debugDumpVariables(FlowContext ctx, FlowNode node) {
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
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "debug_stack_trace", displayName = "Debug Stack Trace", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)},
            outputs = {
                    @FlowPin(name = "stack_trace", dataType = FlowType.STRING),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void debugStackTrace(FlowContext ctx, FlowNode node) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        List<String> stackList = new ArrayList<>();

        Log.info("[Flow:Debug] Execution Stack:");
        for (int i = 1; i < Math.min(stackTrace.length, 20); i++) {
            String trace = stackTrace[i].toString();
            stackList.add(trace);
            Log.info("[Flow:Debug]   " + trace);
        }

        ctx.setOutput(node, "stack_trace", String.join("\n", stackList));
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "debug_break", displayName = "Debug Break", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void debugBreak(FlowContext ctx, FlowNode node) {
        if (debugMode) {
            Log.warn("[Flow:Debug] BREAKPOINT HIT");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ctx.triggerOutput("flow");
    }

    public static boolean isDebugMode() {
        return debugMode;
    }

    public static void setDebugMode(boolean enabled) {
        debugMode = enabled;
        Log.info("[Flow] Debug mode " + (enabled ? "enabled" : "disabled"));
    }
}
