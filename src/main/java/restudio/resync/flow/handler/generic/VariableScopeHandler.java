package restudio.resync.flow.handler.generic;

import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class VariableScopeHandler implements NodeHandler {
    private static final String GLOBAL_PREFIX = "server.";
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public VariableScopeHandler() {
        operations.put("variable_access", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "get");
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            Boolean persist = ctx.getInputValue(node, "persist", Boolean.class, false);
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 1.0);
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());

            String normalizedMode = mode == null ? "get" : mode.trim().toLowerCase();
            String normalizedScope = scope == null ? "local" : scope.trim().toLowerCase();
            boolean persistent = Boolean.TRUE.equals(persist);

            if (!"list".equals(normalizedMode) && name.isEmpty()) {
                throw new IllegalArgumentException("Variable name is required for mode: " + normalizedMode);
            }

            Object valueOutput = null;
            boolean existsOutput = false;
            List<String> variablesOutput = List.of();

            switch (normalizedMode) {
                case "get" -> {
                }
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
                default -> throw new IllegalArgumentException("Unknown variable mode: " + mode);
            }

            if (!"list".equals(normalizedMode)) {
                valueOutput = persistent
                    ? resolvePersistentVariable(ctx, normalizedScope, name, player)
                    : resolveVariable(ctx, normalizedScope, name, player);
                existsOutput = persistent
                    ? persistentVariableExists(ctx, normalizedScope, name, player)
                    : valueOutput != null || variableExists(ctx, normalizedScope, name, player);
            }

            ctx.setOutput(node, "value", valueOutput);
            ctx.setOutput(node, "exists", existsOutput);
            ctx.setOutput(node, "variables", variablesOutput);
        });

        operations.put("variable_set_global", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            putOrRemove(ctx.getGlobalVariables(), GLOBAL_PREFIX + name, value);
        });

        operations.put("variable_set_local", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            ctx.setVariable(name, value);
        });

        operations.put("variable_set_player", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            setVariable(ctx, "player", name, value, player);
        });

        operations.put("variable_get_global", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getGlobalVariables().get(GLOBAL_PREFIX + name);
            ctx.setOutput(node, "value", value);
        });

        operations.put("variable_get_local", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getVariable(name);
            ctx.setOutput(node, "value", value);
        });

        operations.put("variable_get_player", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            ctx.setOutput(node, "value", resolveVariable(ctx, "player", name, player));
        });

        operations.put("variable_delete_global", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            ctx.getGlobalVariables().remove(GLOBAL_PREFIX + name);
        });

        operations.put("variable_delete_local", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            ctx.getLocalVariables().remove(name);
        });

        operations.put("variable_exists_global", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            boolean exists = ctx.getGlobalVariables().containsKey(GLOBAL_PREFIX + name);
            ctx.setOutput(node, "exists", exists);
        });

        operations.put("variable_exists_local", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            boolean exists = ctx.getLocalVariables().containsKey(name);
            ctx.setOutput(node, "exists", exists);
        });

        operations.put("variable_exists", (ctx, node) -> {
            String scope = ctx.getInputValue(node, "scope", String.class, "local");
            String name = ctx.getInputValue(node, "name", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            ctx.setOutput(node, "exists", variableExists(ctx, scope, name, player));
        });

        operations.put("variable_list_global", (ctx, node) -> {
            List<String> names = new ArrayList<>();
            for (String key : ctx.getGlobalVariables().keySet()) {
                if (key.startsWith(GLOBAL_PREFIX)) {
                    names.add(key.substring(GLOBAL_PREFIX.length()));
                }
            }
            ctx.setOutput(node, "variables", names);
        });

        operations.put("variable_list_local", (ctx, node) -> {
            List<String> names = new ArrayList<>(ctx.getLocalVariables().keySet());
            ctx.setOutput(node, "variables", names);
        });

        operations.put("variable_list_all", (ctx, node) -> {
            List<String> names = new ArrayList<>();
            names.addAll(listVariables(ctx, "global", ctx.getPlayer()));
            names.addAll(listVariables(ctx, "local", ctx.getPlayer()));
            names.addAll(listVariables(ctx, "player", ctx.getPlayer()));
            ctx.setOutput(node, "variables", names);
        });

        operations.put("variable_clear_global", (ctx, node) -> {
            ctx.getGlobalVariables().entrySet().removeIf(e -> e.getKey().startsWith(GLOBAL_PREFIX));
        });

        operations.put("variable_clear_local", (ctx, node) -> {
            ctx.getLocalVariables().clear();
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("VariableScopeHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown variable scope operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
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
            case "global" -> putOrRemove(ctx.getGlobalVariables(), GLOBAL_PREFIX + name, value);
            case "player" -> {
                Map<String, Object> vars = getPlayerVars(ctx, player, true);
                if (vars != null) {
                    putOrRemove(vars, name, value);
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
        String variableKey = key;
        target.compute(variableKey, (ignored, current) -> {
            double base = current instanceof Number ? ((Number) current).doubleValue() : 0.0;
            return switch (mode) {
                case "decrement" -> base - amount;
                case "multiply" -> base * amount;
                case "divide" -> amount == 0 ? base : base / amount;
                default -> base + amount;
            };
        });
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
            return (Map<String, Object>) ctx.getGlobalVariables().computeIfAbsent(varKey, key -> new ConcurrentHashMap<>());
        }
        return (Map<String, Object>) ctx.getGlobalVariables().get(varKey);
    }

    private static void putOrRemove(Map<String, Object> variables, String name, Object value) {
        if (value == null) {
            variables.remove(name);
        } else {
            variables.put(name, value);
        }
    }
}
