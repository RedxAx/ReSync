package restudio.resync.flow.nodes;

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

public class SystemEventNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("event:server_start", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String serverName = (String) ctx.getVariable("event.server_name");
            ctx.setNodeOutput(nodeId, "server_name", serverName != null ? serverName : "");
            ctx.triggerOutput("next");
        });

        registry.register("event:server_stop", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String serverName = (String) ctx.getVariable("event.server_name");
            ctx.setNodeOutput(nodeId, "server_name", serverName != null ? serverName : "");
            ctx.triggerOutput("next");
        });

        registry.register("event:plugin_enable", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String pluginName = (String) ctx.getVariable("event.plugin_name");
            ctx.setNodeOutput(nodeId, "plugin_name", pluginName != null ? pluginName : "");
            ctx.triggerOutput("next");
        });

        registry.register("event:plugin_disable", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String pluginName = (String) ctx.getVariable("event.plugin_name");
            ctx.setNodeOutput(nodeId, "plugin_name", pluginName != null ? pluginName : "");
            ctx.triggerOutput("next");
        });

        registry.register("event:world_load", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String worldName = (String) ctx.getVariable("event.world_name");
            ctx.setNodeOutput(nodeId, "world_name", worldName != null ? worldName : "");
            ctx.triggerOutput("next");
        });

        registry.register("event:world_unload", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String worldName = (String) ctx.getVariable("event.world_name");
            ctx.setNodeOutput(nodeId, "world_name", worldName != null ? worldName : "");
            ctx.triggerOutput("next");
        });

        registry.register("event:chunk_load", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Integer chunkX = (Integer) ctx.getVariable("event.chunk_x");
            Integer chunkZ = (Integer) ctx.getVariable("event.chunk_z");
            ctx.setNodeOutput(nodeId, "chunk_x", chunkX != null ? chunkX : 0);
            ctx.setNodeOutput(nodeId, "chunk_z", chunkZ != null ? chunkZ : 0);
            ctx.triggerOutput("next");
        });

        registry.register("event:chunk_unload", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Integer chunkX = (Integer) ctx.getVariable("event.chunk_x");
            Integer chunkZ = (Integer) ctx.getVariable("event.chunk_z");
            ctx.setNodeOutput(nodeId, "chunk_x", chunkX != null ? chunkX : 0);
            ctx.setNodeOutput(nodeId, "chunk_z", chunkZ != null ? chunkZ : 0);
            ctx.triggerOutput("next");
        });

        registry.register("event:server_tick", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Integer tickNumber = (Integer) ctx.getVariable("event.tick_number");
            ctx.setNodeOutput(nodeId, "tick_number", tickNumber != null ? tickNumber : 0);
            ctx.triggerOutput("next");
        });

        registry.register("event:server_save", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String worldName = (String) ctx.getVariable("event.world_name");
            ctx.setNodeOutput(nodeId, "world_name", worldName != null ? worldName : "");
            ctx.triggerOutput("next");
        });
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (SystemEventNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry tempRegistry = new FlowRegistry();
            registerLegacyNodes(tempRegistry);
            for (String type : tempRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, tempRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private static void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor != null) {
            executor.accept(ctx, node);
        } else {
            ctx.triggerOutput("next");
        }
    }

    @DefineNode(
        id = "event:server_start",
        displayName = "Server Start",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "server_name", dataType = FlowType.STRING),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void serverStart(FlowContext ctx, FlowNode node) {
        executeLegacy("event:server_start", ctx, node);
    }

    @DefineNode(
        id = "event:server_stop",
        displayName = "Server Stop",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "server_name", dataType = FlowType.STRING),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void serverStop(FlowContext ctx, FlowNode node) {
        executeLegacy("event:server_stop", ctx, node);
    }

    @DefineNode(
        id = "event:plugin_enable",
        displayName = "Plugin Enable",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "plugin_name", dataType = FlowType.STRING),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void pluginEnable(FlowContext ctx, FlowNode node) {
        executeLegacy("event:plugin_enable", ctx, node);
    }

    @DefineNode(
        id = "event:plugin_disable",
        displayName = "Plugin Disable",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "plugin_name", dataType = FlowType.STRING),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void pluginDisable(FlowContext ctx, FlowNode node) {
        executeLegacy("event:plugin_disable", ctx, node);
    }

    @DefineNode(
        id = "event:world_load",
        displayName = "World Load",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "world_name", dataType = FlowType.STRING),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void worldLoad(FlowContext ctx, FlowNode node) {
        executeLegacy("event:world_load", ctx, node);
    }

    @DefineNode(
        id = "event:world_unload",
        displayName = "World Unload",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "world_name", dataType = FlowType.STRING),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void worldUnload(FlowContext ctx, FlowNode node) {
        executeLegacy("event:world_unload", ctx, node);
    }

    @DefineNode(
        id = "event:chunk_load",
        displayName = "Chunk Load",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "chunk_x", dataType = FlowType.NUMBER),
            @FlowPin(name = "chunk_z", dataType = FlowType.NUMBER),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void chunkLoad(FlowContext ctx, FlowNode node) {
        executeLegacy("event:chunk_load", ctx, node);
    }

    @DefineNode(
        id = "event:chunk_unload",
        displayName = "Chunk Unload",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "chunk_x", dataType = FlowType.NUMBER),
            @FlowPin(name = "chunk_z", dataType = FlowType.NUMBER),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void chunkUnload(FlowContext ctx, FlowNode node) {
        executeLegacy("event:chunk_unload", ctx, node);
    }

    @DefineNode(
        id = "event:server_tick",
        displayName = "Server Tick",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "tick_number", dataType = FlowType.NUMBER),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void serverTick(FlowContext ctx, FlowNode node) {
        executeLegacy("event:server_tick", ctx, node);
    }

    @DefineNode(
        id = "event:server_save",
        displayName = "Server Save",
        category = NodeDefinition.NodeCategory.EVENT,
        outputs = {
            @FlowPin(name = "world_name", dataType = FlowType.STRING),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void serverSave(FlowContext ctx, FlowNode node) {
        executeLegacy("event:server_save", ctx, node);
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
