package restudio.resync.flow.nodes;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class PlaceholderNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("placeholder_parse", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);

            if (text == null || text.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "result", "");
                return;
            }

            try {
                String result = PlaceholderAPI.setPlaceholders(player, text);
                ctx.setNodeOutput(nodeId, "success", true);
                ctx.setNodeOutput(nodeId, "result", result);
            } catch (Exception e) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "result", text);
            }
        });

        registry.register("placeholder_set_relational", (ctx, node) -> {
            Player playerOne = ctx.getInputValue(node, "player_one", Player.class, null);
            Player playerTwo = ctx.getInputValue(node, "player_two", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);

            if (text == null || text.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "result", "");
                return;
            }

            try {
                String result = PlaceholderAPI.setRelationalPlaceholders(playerOne, playerTwo, text);
                ctx.setNodeOutput(nodeId, "success", true);
                ctx.setNodeOutput(nodeId, "result", result);
            } catch (Exception e) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "result", text);
            }
        });

        registry.register("placeholder_strip_brackets", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);

            if (text == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "result", "");
                return;
            }

            try {
                String result = PlaceholderAPI.setBracketPlaceholders(null, text);
                ctx.setNodeOutput(nodeId, "success", true);
                ctx.setNodeOutput(nodeId, "result", result);
            } catch (Exception e) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "result", text);
            }
        });
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (PlaceholderNodes.class) {
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

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "placeholder_parse", displayName = "Parse Placeholder", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "text", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "result", dataType = FlowType.STRING)
            })
    public void placeholderParse(FlowContext ctx, FlowNode node) {
        executeLegacy("placeholder_parse", ctx, node);
    }

    @DefineNode(id = "placeholder_set_relational", displayName = "Parse Relational Placeholder", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player_one", dataType = FlowType.PLAYER),
                    @FlowPin(name = "player_two", dataType = FlowType.PLAYER),
                    @FlowPin(name = "text", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "result", dataType = FlowType.STRING)
            })
    public void placeholderSetRelational(FlowContext ctx, FlowNode node) {
        executeLegacy("placeholder_set_relational", ctx, node);
    }

    @DefineNode(id = "placeholder_strip_brackets", displayName = "Strip Placeholder Brackets", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "text", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "result", dataType = FlowType.STRING)
            })
    public void placeholderStripBrackets(FlowContext ctx, FlowNode node) {
        executeLegacy("placeholder_strip_brackets", ctx, node);
    }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        return ctx.getRuntime().findNodeId(node);
    }
}
