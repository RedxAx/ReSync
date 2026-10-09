package restudio.resync.flow.handler.generic;

import org.bukkit.entity.Player;
import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.resources.ReSyncResourceCatalog;
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
        return requireProject(ctx.getInputValue(node, "project"), currentServerId()).id();
    }

    static ServerResourceLocator requireProject(Object value, ServerId serverId) {
        if (!(value instanceof ServerResourceLocator locator)) {
            throw new IllegalArgumentException("WorldGen project reference must contain server, type, and ID");
        }
        if (serverId == null || !serverId.equals(locator.serverId())) {
            throw new IllegalArgumentException("WorldGen project reference server does not match this server");
        }
        if (!OwnerId.of("restudio.resync").equals(locator.owner())
            || !ResourceTypeId.of(ReSyncResourceCatalog.WORLDGEN).equals(locator.resourceType())) {
            throw new IllegalArgumentException("WorldGen project reference must identify a ReSync WorldGen project");
        }
        return locator;
    }

    private static ServerId currentServerId() {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || plugin.getReSyncServer() == null) {
            throw new IllegalStateException("ReSync server identity is unavailable");
        }
        String serverId = plugin.getReSyncServer().getCanonicalServerId();
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalStateException("ReSync server identity is unavailable");
        }
        return ServerId.parseCanonicalText(serverId);
    }

    private String owner(FlowContext ctx, FlowNode node) {
        return "flow:" + ctx.resolveNodeId(node);
    }
}
