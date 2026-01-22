package restudio.resync.flow.nodes;

import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class VariableNodes implements NodeCategory {
    
    private static final String GLOBAL_PREFIX = "server.";
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("variable_access", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "get");
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 1.0);
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String nodeId = findNodeId(ctx, node);

            String normalizedMode = mode == null ? "get" : mode.trim().toLowerCase();
            String normalizedScope = scope == null ? "local" : scope.trim().toLowerCase();

            if (nodeId == null) {
                ctx.triggerOutput("flow");
                return;
            }
            if (!"list".equals(normalizedMode) && name.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            switch (normalizedMode) {
                case "get" -> {
                    Object resolved = resolveVariable(ctx, normalizedScope, name, player);
                    ctx.setNodeOutput(nodeId, "value", resolved);
                }
                case "set" -> setVariable(ctx, normalizedScope, name, value, player);
                case "exists" -> {
                    boolean exists = variableExists(ctx, normalizedScope, name, player);
                    ctx.setNodeOutput(nodeId, "exists", exists);
                }
                case "delete" -> deleteVariable(ctx, normalizedScope, name, player);
                case "list" -> {
                    List<String> variables = listVariables(ctx, normalizedScope, player);
                    ctx.setNodeOutput(nodeId, "variables", variables);
                }
                case "increment", "decrement", "multiply", "divide" -> {
                    double delta = amount != null ? amount : 1.0;
                    updateNumericVariable(ctx, normalizedMode, normalizedScope, name, delta, player);
                }
                default -> {
                }
            }

            ctx.triggerOutput("flow");
        });

        registry.register("variable_set_global", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            ctx.getGlobalVariables().put(GLOBAL_PREFIX + name, value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_set_local", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            ctx.setVariable(name, value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_set_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            String varKey = "player_vars_" + player.getUniqueId();
            Map<String, Object> playerVars = (Map<String, Object>) ctx.getGlobalVariables().computeIfAbsent(varKey, k -> new HashMap<>());
            playerVars.put(name, value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_get_global", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getGlobalVariables().get(GLOBAL_PREFIX + name);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_get_local", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getVariable(name);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_get_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = null;
            if (player != null) {
                String varKey = "player_vars_" + player.getUniqueId();
                Map<String, Object> playerVars = (Map<String, Object>) ctx.getGlobalVariables().get(varKey);
                if (playerVars != null) {
                    value = playerVars.get(name);
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_delete", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            if ("global".equalsIgnoreCase(scope)) {
                ctx.getGlobalVariables().remove(GLOBAL_PREFIX + name);
            } else if ("local".equalsIgnoreCase(scope)) {
                ctx.getLocalVariables().remove(name);
            } else if ("player".equalsIgnoreCase(scope) && ctx.getPlayer() != null) {
                String varKey = "player_vars_" + ctx.getPlayer().getUniqueId();
                Map<String, Object> playerVars = (Map<String, Object>) ctx.getGlobalVariables().get(varKey);
                if (playerVars != null) {
                    playerVars.remove(name);
                }
            }
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_exists", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            boolean exists = false;
            if ("global".equalsIgnoreCase(scope)) {
                exists = ctx.getGlobalVariables().containsKey(GLOBAL_PREFIX + name);
            } else if ("local".equalsIgnoreCase(scope)) {
                exists = ctx.getLocalVariables().containsKey(name);
            } else if ("player".equalsIgnoreCase(scope) && ctx.getPlayer() != null) {
                String varKey = "player_vars_" + ctx.getPlayer().getUniqueId();
                Map<String, Object> playerVars = (Map<String, Object>) ctx.getGlobalVariables().get(varKey);
                exists = playerVars != null && playerVars.containsKey(name);
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "exists", exists);
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_list_all", (ctx, node) -> {
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            List<String> varNames = new ArrayList<>();
            if ("global".equalsIgnoreCase(scope)) {
                for (String key : ctx.getGlobalVariables().keySet()) {
                    if (key.startsWith(GLOBAL_PREFIX)) {
                        varNames.add(key.substring(GLOBAL_PREFIX.length()));
                    }
                }
            } else if ("local".equalsIgnoreCase(scope)) {
                varNames.addAll(ctx.getLocalVariables().keySet());
            } else if ("player".equalsIgnoreCase(scope) && ctx.getPlayer() != null) {
                String varKey = "player_vars_" + ctx.getPlayer().getUniqueId();
                Map<String, Object> playerVars = (Map<String, Object>) ctx.getGlobalVariables().get(varKey);
                if (playerVars != null) {
                    varNames.addAll(playerVars.keySet());
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "variables", varNames);
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_increment", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Double amount = ctx.getInputValue(node, "amount", Double.class, 1.0);
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            Object current = null;
            String key = scope.equalsIgnoreCase("global") ? GLOBAL_PREFIX + name : name;
            if ("global".equalsIgnoreCase(scope)) {
                current = ctx.getGlobalVariables().get(key);
            } else if ("local".equalsIgnoreCase(scope)) {
                current = ctx.getLocalVariables().get(key);
            }
            double newValue = current instanceof Number ? ((Number) current).doubleValue() + amount : amount;
            if ("global".equalsIgnoreCase(scope)) {
                ctx.getGlobalVariables().put(key, newValue);
            } else if ("local".equalsIgnoreCase(scope)) {
                ctx.setVariable(key, newValue);
            }
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_decrement", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Double amount = ctx.getInputValue(node, "amount", Double.class, 1.0);
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            Object current = null;
            String key = scope.equalsIgnoreCase("global") ? GLOBAL_PREFIX + name : name;
            if ("global".equalsIgnoreCase(scope)) {
                current = ctx.getGlobalVariables().get(key);
            } else if ("local".equalsIgnoreCase(scope)) {
                current = ctx.getLocalVariables().get(key);
            }
            double newValue = current instanceof Number ? ((Number) current).doubleValue() - amount : -amount;
            if ("global".equalsIgnoreCase(scope)) {
                ctx.getGlobalVariables().put(key, newValue);
            } else if ("local".equalsIgnoreCase(scope)) {
                ctx.setVariable(key, newValue);
            }
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_multiply", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Double amount = ctx.getInputValue(node, "amount", Double.class, 2.0);
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            Object current = null;
            String key = scope.equalsIgnoreCase("global") ? GLOBAL_PREFIX + name : name;
            if ("global".equalsIgnoreCase(scope)) {
                current = ctx.getGlobalVariables().get(key);
            } else if ("local".equalsIgnoreCase(scope)) {
                current = ctx.getLocalVariables().get(key);
            }
            double newValue = current instanceof Number ? ((Number) current).doubleValue() * amount : 0.0;
            if ("global".equalsIgnoreCase(scope)) {
                ctx.getGlobalVariables().put(key, newValue);
            } else if ("local".equalsIgnoreCase(scope)) {
                ctx.setVariable(key, newValue);
            }
            ctx.triggerOutput("flow");
        });
        
        registry.register("variable_divide", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Double amount = ctx.getInputValue(node, "amount", Double.class, 2.0);
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            Object current = null;
            String key = scope.equalsIgnoreCase("global") ? GLOBAL_PREFIX + name : name;
            if ("global".equalsIgnoreCase(scope)) {
                current = ctx.getGlobalVariables().get(key);
            } else if ("local".equalsIgnoreCase(scope)) {
                current = ctx.getLocalVariables().get(key);
            }
            double newValue = current instanceof Number ? ((Number) current).doubleValue() / amount : 0.0;
            if ("global".equalsIgnoreCase(scope)) {
                ctx.getGlobalVariables().put(key, newValue);
            } else if ("local".equalsIgnoreCase(scope)) {
                ctx.setVariable(key, newValue);
            }
            ctx.triggerOutput("flow");
        });
    }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static Object resolveVariable(FlowContext ctx, String scope, String name, Player player) {
        return switch (scope) {
            case "global" -> ctx.getGlobalVariables().get(GLOBAL_PREFIX + name);
            case "player" -> {
                Map<String, Object> vars = getPlayerVars(ctx, player, false);
                yield vars != null ? vars.get(name) : null;
            }
            default -> ctx.getVariable(name);
        };
    }

    private static void setVariable(FlowContext ctx, String scope, String name, Object value, Player player) {
        switch (scope) {
            case "global" -> ctx.getGlobalVariables().put(GLOBAL_PREFIX + name, value);
            case "player" -> {
                Map<String, Object> vars = getPlayerVars(ctx, player, true);
                if (vars != null) {
                    vars.put(name, value);
                }
            }
            default -> ctx.setVariable(name, value);
        }
    }

    private static boolean variableExists(FlowContext ctx, String scope, String name, Player player) {
        return switch (scope) {
            case "global" -> ctx.getGlobalVariables().containsKey(GLOBAL_PREFIX + name);
            case "player" -> {
                Map<String, Object> vars = getPlayerVars(ctx, player, false);
                yield vars != null && vars.containsKey(name);
            }
            default -> ctx.getLocalVariables().containsKey(name);
        };
    }

    private static void deleteVariable(FlowContext ctx, String scope, String name, Player player) {
        switch (scope) {
            case "global" -> ctx.getGlobalVariables().remove(GLOBAL_PREFIX + name);
            case "player" -> {
                Map<String, Object> vars = getPlayerVars(ctx, player, false);
                if (vars != null) {
                    vars.remove(name);
                }
            }
            default -> ctx.getLocalVariables().remove(name);
        }
    }

    private static List<String> listVariables(FlowContext ctx, String scope, Player player) {
        List<String> names = new ArrayList<>();
        switch (scope) {
            case "global" -> {
                for (String key : ctx.getGlobalVariables().keySet()) {
                    if (key.startsWith(GLOBAL_PREFIX)) {
                        names.add(key.substring(GLOBAL_PREFIX.length()));
                    }
                }
            }
            case "player" -> {
                Map<String, Object> vars = getPlayerVars(ctx, player, false);
                if (vars != null) {
                    names.addAll(vars.keySet());
                }
            }
            default -> names.addAll(ctx.getLocalVariables().keySet());
        }
        return names;
    }

    private static void updateNumericVariable(FlowContext ctx, String mode, String scope, String name, double amount, Player player) {
        Map<String, Object> target;
        String key = name;
        switch (scope) {
            case "global" -> {
                target = ctx.getGlobalVariables();
                key = GLOBAL_PREFIX + name;
            }
            case "player" -> target = getPlayerVars(ctx, player, true);
            default -> target = ctx.getLocalVariables();
        }

        if (target == null) {
            return;
        }

        Object current = target.get(key);
        double base = current instanceof Number ? ((Number) current).doubleValue() : 0.0;
        double result = switch (mode) {
            case "decrement" -> base - amount;
            case "multiply" -> base * amount;
            case "divide" -> amount == 0 ? base : base / amount;
            default -> base + amount;
        };
        target.put(key, result);
    }

    private static Map<String, Object> getPlayerVars(FlowContext ctx, Player player, boolean create) {
        if (player == null) {
            return null;
        }
        String varKey = "player_vars_" + player.getUniqueId();
        if (create) {
            return (Map<String, Object>) ctx.getGlobalVariables().computeIfAbsent(varKey, k -> new HashMap<>());
        }
        return (Map<String, Object>) ctx.getGlobalVariables().get(varKey);
    }
}
