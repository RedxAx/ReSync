package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class DebugNodes implements NodeCategory {
    
    private static boolean debugMode = false;
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("debug_log", (ctx, node) -> {
            String message = ctx.getInputValue(node, "message", String.class, "");
            String level = ctx.getInputValue(node, "level", String.class, "INFO");
            
            switch (level.toUpperCase()) {
                case "WARN":
                    Bukkit.getLogger().warning("[Flow Debug] " + message);
                    break;
                case "ERROR":
                    Bukkit.getLogger().severe("[Flow Debug] " + message);
                    break;
                case "DEBUG":
                default:
                    Bukkit.getLogger().info("[Flow Debug] " + message);
                    break;
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("debug_print_variable", (ctx, node) -> {
            String variableName = ctx.getInputValue(node, "variable_name", String.class, "");
            Object value = ctx.getVariable(variableName);
            
            Bukkit.getLogger().info("[Flow Debug] Variable '" + variableName + "' = " + 
                (value != null ? value.toString() : "null") + 
                " (" + (value != null ? value.getClass().getSimpleName() : "null") + ")");
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("debug_dump_variables", (ctx, node) -> {
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            List<String> variableInfo = new ArrayList<>();
            
            if ("global".equalsIgnoreCase(scope)) {
                Map<String, Object> globals = ctx.getGlobalVariables();
                for (Map.Entry<String, Object> entry : globals.entrySet()) {
                    variableInfo.add(entry.getKey() + " = " + 
                        (entry.getValue() != null ? entry.getValue().toString() : "null"));
                }
                Bukkit.getLogger().info("[Flow Debug] Global Variables (" + globals.size() + "):");
            } else {
                Map<String, Object> locals = ctx.getLocalVariables();
                for (Map.Entry<String, Object> entry : locals.entrySet()) {
                    variableInfo.add(entry.getKey() + " = " + 
                        (entry.getValue() != null ? entry.getValue().toString() : "null"));
                }
                Bukkit.getLogger().info("[Flow Debug] Local Variables (" + locals.size() + "):");
            }
            
            for (String info : variableInfo) {
                Bukkit.getLogger().info("  " + info);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "variables", variableInfo);
            ctx.triggerOutput("flow");
        });
        
        registry.register("debug_stack_trace", (ctx, node) -> {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            List<String> stackList = new ArrayList<>();
            
            Bukkit.getLogger().info("[Flow Debug] Execution Stack:");
            for (int i = 1; i < Math.min(stackTrace.length, 20); i++) {
                String trace = stackTrace[i].toString();
                stackList.add(trace);
                Bukkit.getLogger().info("  " + trace);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "stack_trace", String.join("\n", stackList));
            ctx.triggerOutput("flow");
        });
        
        registry.register("debug_break", (ctx, node) -> {
            if (debugMode) {
                Bukkit.getLogger().warning("[Flow Debug] BREAKPOINT HIT - Execution paused");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ctx.triggerOutput("flow");
        });
    }
    
    public static boolean isDebugMode() {
        return debugMode;
    }
    
    public static void setDebugMode(boolean enabled) {
        debugMode = enabled;
        Bukkit.getLogger().info("[Flow] Debug mode " + (enabled ? "enabled" : "disabled"));
    }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
