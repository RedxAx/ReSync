package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
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
                    @FlowPin(name = "message", dataType = FlowType.STRING),
                    @FlowPin(name = "level", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void debugLog(FlowContext ctx, FlowNode node) {
        String message = ctx.getInputValue(node, "message", String.class, "");
        String level = ctx.getInputValue(node, "level", String.class, "INFO");

        switch (level.toUpperCase()) {
            case "WARN" -> Bukkit.getLogger().warning("[Flow Debug] " + message);
            case "ERROR" -> Bukkit.getLogger().severe("[Flow Debug] " + message);
            default -> Bukkit.getLogger().info("[Flow Debug] " + message);
        }

        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "debug_print_variable", displayName = "Debug Print Variable", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "variable_name", dataType = FlowType.STRING)},
            outputs = {
                    @FlowPin(name = "value", dataType = FlowType.ANY),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void debugPrintVariable(FlowContext ctx, FlowNode node) {
        String variableName = ctx.getInputValue(node, "variable_name", String.class, "");
        Object value = ctx.getVariable(variableName);

        Bukkit.getLogger().info("[Flow Debug] Variable '" + variableName + "' = " +
                (value != null ? value : "null") +
                " (" + (value != null ? value.getClass().getSimpleName() : "null") + ")");

        ctx.setOutput(node, "value", value);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "debug_dump_variables", displayName = "Debug Dump Variables", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "scope", dataType = FlowType.STRING)},
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
            Bukkit.getLogger().info("[Flow Debug] Global Variables (" + globals.size() + "):");
        } else {
            Map<String, Object> locals = ctx.getLocalVariables();
            for (Map.Entry<String, Object> entry : locals.entrySet()) {
                variableInfo.add(entry.getKey() + " = " + (entry.getValue() != null ? entry.getValue() : "null"));
            }
            Bukkit.getLogger().info("[Flow Debug] Local Variables (" + locals.size() + "):");
        }

        for (String info : variableInfo) {
            Bukkit.getLogger().info("  " + info);
        }

        ctx.setOutput(node, "variables", variableInfo);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "debug_stack_trace", displayName = "Debug Stack Trace", category = NodeDefinition.NodeCategory.UTILITY,
            outputs = {
                    @FlowPin(name = "stack_trace", dataType = FlowType.STRING),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void debugStackTrace(FlowContext ctx, FlowNode node) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        List<String> stackList = new ArrayList<>();

        Bukkit.getLogger().info("[Flow Debug] Execution Stack:");
        for (int i = 1; i < Math.min(stackTrace.length, 20); i++) {
            String trace = stackTrace[i].toString();
            stackList.add(trace);
            Bukkit.getLogger().info("  " + trace);
        }

        ctx.setOutput(node, "stack_trace", String.join("\n", stackList));
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "debug_break", displayName = "Debug Break", category = NodeDefinition.NodeCategory.UTILITY,
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void debugBreak(FlowContext ctx, FlowNode node) {
        if (debugMode) {
            Bukkit.getLogger().warning("[Flow Debug] BREAKPOINT HIT - Execution paused");
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
        Bukkit.getLogger().info("[Flow] Debug mode " + (enabled ? "enabled" : "disabled"));
    }
}
