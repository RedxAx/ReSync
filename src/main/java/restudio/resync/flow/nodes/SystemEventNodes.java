package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class SystemEventNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
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
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
