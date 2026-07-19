package restudio.resync.flow.handler.generic;

import org.bukkit.entity.Player;
import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.worldgen.WorldGenOperationService;

import java.util.Map;
import java.util.Set;

public final class WorldGenFlowHandler implements NodeHandler {
    private static final Set<String> OPERATIONS = Set.of("worldgen_validate", "worldgen_compile", "worldgen_install", "worldgen_preview", "worldgen_preview_stop");
    private final WorldGenOperationService service;

    public WorldGenFlowHandler(WorldGenOperationService service) {
        if (service == null) {
            throw new IllegalArgumentException("WorldGen operation service is required");
        }
        this.service = service;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("WorldGenFlowHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("Unknown WorldGen Flow operation: " + operation);
        }
        switch (operation) {
            case "worldgen_validate" -> validate(ctx, node);
            case "worldgen_compile" -> accept(ctx, node, service.compileProject(projectId(ctx, node), owner(ctx, node)));
            case "worldgen_install" -> accept(ctx, node, service.installProject(projectId(ctx, node), ctx.getInputValue(node, "world_name", String.class, ""), owner(ctx, node)));
            case "worldgen_preview" -> preview(ctx, node);
            case "worldgen_preview_stop" -> accept(ctx, node, service.stopPreview(ctx.getInputValue(node, "preview_id", String.class, ""), owner(ctx, node)));
            default -> throw new IllegalArgumentException("Unknown WorldGen Flow operation: " + operation);
        }
    }

    @Override
    public Set<String> getSupportedOperations() {
        return OPERATIONS;
    }

    private void validate(FlowContext ctx, FlowNode node) {
        FlowOperationResult<Map<String, Object>> result = service.validateProject(projectId(ctx, node));
        ctx.setOutput(node, "result", result);
        ctx.setOutput(node, "diagnostics", result.success() ? result.value() : result.details());
        ctx.setOutput(node, "valid", result.success());
        ctx.setOutput(node, "success", result.success());
        ctx.setOutput(node, "error_code", result.errorCode());
        ctx.setOutput(node, "message", result.message());
        ctx.triggerOutput(result.success() ? "flow" : "failed");
    }

    private void preview(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
        Number seed = ctx.getInputValue(node, "seed", Number.class, 0L);
        FlowJobReference<Map<String, Object>> job = service.previewProject(
            projectId(ctx, node),
            ctx.getInputValue(node, "preview_id", String.class, ""),
            player != null ? player.getUniqueId().toString() : "",
            ctx.getInputValue(node, "environment", String.class, "NORMAL"),
            seed.longValue(),
            owner(ctx, node)
        );
        accept(ctx, node, job);
    }

    private void accept(FlowContext ctx, FlowNode node, FlowJobReference<Map<String, Object>> job) {
        boolean accepted = job != null && job.getState() != FlowJobReference.State.FAILED;
        FlowOperationResult<?> outcome = job != null ? job.snapshot().outcome() : null;
        ctx.setOutput(node, "job", job);
        ctx.setOutput(node, "success", accepted);
        ctx.setOutput(node, "error_code", accepted || outcome == null ? "" : outcome.errorCode());
        ctx.setOutput(node, "message", accepted ? "Job Accepted" : outcome != null ? outcome.message() : "WorldGen Job Rejected");
        ctx.triggerOutput(accepted ? "flow" : "failed");
    }

    private String projectId(FlowContext ctx, FlowNode node) {
        Object value = ctx.getInputValue(node, "project");
        if (value instanceof FlowResourceReference reference) {
            return reference.id();
        }
        if (value != null) {
            return String.valueOf(value);
        }
        return ctx.getInputValue(node, "project_id", String.class, "");
    }

    private String owner(FlowContext ctx, FlowNode node) {
        return "flow:" + ctx.resolveNodeId(node);
    }
}
