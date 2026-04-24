package restudio.resync.flow.nodes;

import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.VisibleWhen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class VariableNodes {
    
    private static final String GLOBAL_PREFIX = "server.";
    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;
    
    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("variable_access", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "get");
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            Boolean persist = ctx.getInputValue(node, "persist", Boolean.class, false);
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 1.0);
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String nodeId = findNodeId(ctx, node);

            String normalizedMode = mode == null ? "get" : mode.trim().toLowerCase();
            String normalizedScope = scope == null ? "local" : scope.trim().toLowerCase();
            boolean persistent = Boolean.TRUE.equals(persist);

            if (nodeId == null) {
                ctx.triggerOutput("flow");
                return;
            }
            if (!"list".equals(normalizedMode) && name.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            Object valueOutput = null;
            boolean existsOutput = false;
            List<String> variablesOutput = List.of();

            switch (normalizedMode) {
                case "set" -> {
                    setVariable(ctx, normalizedScope, name, value, player);
                    if (persistent) {
                        persistVariable(ctx, normalizedScope, name, value, player);
                    }
                }
                case "delete" -> {
                    deleteVariable(ctx, normalizedScope, name, player);
                    if (persistent) {
                        deletePersistentVariable(ctx, normalizedScope, name, player);
                    }
                }
                case "list" -> {
                    variablesOutput = persistent
                        ? listPersistentVariables(ctx, normalizedScope, player)
                        : listVariables(ctx, normalizedScope, player);
                    valueOutput = variablesOutput;
                    existsOutput = !variablesOutput.isEmpty();
                }
                case "increment", "decrement", "multiply", "divide" -> {
                    double delta = amount != null ? amount : 1.0;
                    if (persistent) {
                        updateNumericPersistent(ctx, normalizedMode, normalizedScope, name, delta, player);
                    } else {
                        updateNumericVariable(ctx, normalizedMode, normalizedScope, name, delta, player);
                    }
                }
                case "get", "exists" -> {
                }
                default -> {
                }
            }

            if (!"list".equals(normalizedMode)) {
                valueOutput = persistent
                    ? resolvePersistentVariable(ctx, normalizedScope, name, player)
                    : resolveVariable(ctx, normalizedScope, name, player);
                existsOutput = persistent
                    ? persistentVariableExists(ctx, normalizedScope, name, player)
                    : valueOutput != null || variableExists(ctx, normalizedScope, name, player);
            }

            ctx.setNodeOutput(nodeId, "value", valueOutput);
            ctx.setNodeOutput(nodeId, "exists", existsOutput);
            ctx.setNodeOutput(nodeId, "variables", variablesOutput);
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

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (VariableNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "variable_access", displayName = "Variable", category = NodeDefinition.NodeCategory.VARIABLE, priority = -10,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "mode", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            optionsSource = "variable:mode", defaultValue = "get"),
                    @FlowPin(name = "scope", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            optionsSource = "variable:scope", defaultValue = "local"),
                    @FlowPin(name = "persist", dataType = FlowType.BOOLEAN, widget = NodeDefinition.WidgetType.TOGGLE, defaultValue = "false"),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER,
                            visibleWhen = {@VisibleWhen(pin = "scope", value = "player")}),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "value", dataType = FlowType.ANY,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "set")}),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER, defaultValue = "1",
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "increment,decrement,multiply,divide")})
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "value", dataType = FlowType.ANY,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "get,set,increment,decrement,multiply,divide")}),
                    @FlowPin(name = "exists", dataType = FlowType.BOOLEAN,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "exists")}),
                    @FlowPin(name = "variables", dataType = FlowType.LIST,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "list")})
            })
    public void variableAccess(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_access", ctx, node);
    }

    @DefineNode(id = "variable_set_global", displayName = "Set Global Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void variableSetGlobal(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_set_global", ctx, node);
    }

    @DefineNode(id = "variable_set_local", displayName = "Set Local Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void variableSetLocal(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_set_local", ctx, node);
    }

    @DefineNode(id = "variable_set_player", displayName = "Set Player Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void variableSetPlayer(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_set_player", ctx, node);
    }

    @DefineNode(id = "variable_get_global", displayName = "Get Global Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            })
    public void variableGetGlobal(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_get_global", ctx, node);
    }

    @DefineNode(id = "variable_get_local", displayName = "Get Local Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            })
    public void variableGetLocal(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_get_local", ctx, node);
    }

    @DefineNode(id = "variable_get_player", displayName = "Get Player Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "name", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            })
    public void variableGetPlayer(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_get_player", ctx, node);
    }

    @DefineNode(id = "variable_delete", displayName = "Delete Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "scope", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void variableDelete(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_delete", ctx, node);
    }

    @DefineNode(id = "variable_exists", displayName = "Variable Exists", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "scope", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "exists", dataType = FlowType.BOOLEAN)
            })
    public void variableExists(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_exists", ctx, node);
    }

    @DefineNode(id = "variable_list_all", displayName = "List All Variables", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "scope", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "variables", dataType = FlowType.LIST)
            })
    public void variableListAll(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_list_all", ctx, node);
    }

    @DefineNode(id = "variable_increment", displayName = "Increment Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER),
                    @FlowPin(name = "scope", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void variableIncrement(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_increment", ctx, node);
    }

    @DefineNode(id = "variable_decrement", displayName = "Decrement Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER),
                    @FlowPin(name = "scope", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void variableDecrement(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_decrement", ctx, node);
    }

    @DefineNode(id = "variable_multiply", displayName = "Multiply Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER),
                    @FlowPin(name = "scope", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void variableMultiply(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_multiply", ctx, node);
    }

    @DefineNode(id = "variable_divide", displayName = "Divide Variable", category = NodeDefinition.NodeCategory.VARIABLE, hidden = true,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER),
                    @FlowPin(name = "scope", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void variableDivide(FlowContext ctx, FlowNode node) {
        executeLegacy("variable_divide", ctx, node);
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

    private static Object resolvePersistentVariable(FlowContext ctx, String scope, String name, Player player) {
        String key = buildPersistentKey(ctx, scope, name, player);
        if (key == null) {
            return null;
        }
        PersistentVariableStore store = PersistentVariableStore.getInstance();
        if (!store.contains(key)) {
            return null;
        }
        Object value = store.get(key);
        setVariable(ctx, scope, name, value, player);
        return value;
    }

    private static boolean persistentVariableExists(FlowContext ctx, String scope, String name, Player player) {
        String key = buildPersistentKey(ctx, scope, name, player);
        if (key == null) {
            return false;
        }
        return PersistentVariableStore.getInstance().contains(key);
    }

    private static void persistVariable(FlowContext ctx, String scope, String name, Object value, Player player) {
        String key = buildPersistentKey(ctx, scope, name, player);
        if (key == null) {
            return;
        }
        PersistentVariableStore.getInstance().set(key, value);
    }

    private static void deletePersistentVariable(FlowContext ctx, String scope, String name, Player player) {
        String key = buildPersistentKey(ctx, scope, name, player);
        if (key == null) {
            return;
        }
        PersistentVariableStore.getInstance().remove(key);
    }

    private static List<String> listPersistentVariables(FlowContext ctx, String scope, Player player) {
        String prefix = buildPersistentPrefix(ctx, scope, player);
        if (prefix == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String key : PersistentVariableStore.getInstance().getAll().keySet()) {
            if (key.startsWith(prefix)) {
                names.add(key.substring(prefix.length()));
            }
        }
        return names;
    }

    private static void updateNumericPersistent(FlowContext ctx, String mode, String scope, String name, double amount, Player player) {
        String key = buildPersistentKey(ctx, scope, name, player);
        if (key == null) {
            return;
        }
        PersistentVariableStore store = PersistentVariableStore.getInstance();
        Object current = store.get(key);
        double base = current instanceof Number ? ((Number) current).doubleValue() : 0.0;
        double result = switch (mode) {
            case "decrement" -> base - amount;
            case "multiply" -> base * amount;
            case "divide" -> amount == 0 ? base : base / amount;
            default -> base + amount;
        };
        setVariable(ctx, scope, name, result, player);
        store.set(key, result);
    }

    private static String buildPersistentKey(FlowContext ctx, String scope, String name, Player player) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String prefix = buildPersistentPrefix(ctx, scope, player);
        if (prefix == null) {
            return null;
        }
        return prefix + name;
    }

    private static String buildPersistentPrefix(FlowContext ctx, String scope, Player player) {
        String normalizedScope = scope == null ? "local" : scope;
        if ("global".equals(normalizedScope)) {
            return "global.";
        }
        if ("player".equals(normalizedScope)) {
            if (player == null) {
                return null;
            }
            return "player." + player.getUniqueId() + ".";
        }
        String graphId = ctx.getRuntime().getGraph() != null ? ctx.getRuntime().getGraph().getId() : "local";
        if (graphId == null || graphId.isBlank()) {
            graphId = "local";
        }
        return "local." + graphId + ".";
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
