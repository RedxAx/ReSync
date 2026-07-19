package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ScoreboardHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ScoreboardHandler() {
        operations.put("scoreboard_create", (ctx, node) -> {
            String objectiveId = requireObjectiveId(ctx, node);
            String name = ctx.getInputValue(node, "name", String.class, "Objective");
            String criteria = ctx.getInputValue(node, "criteria", String.class, "dummy");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Scoreboard objective name is required");
            if (criteria == null || criteria.isBlank()) throw new IllegalArgumentException("Scoreboard criteria is required");
            Scoreboard scoreboard = requireScoreboard();
            if (scoreboard.getObjective(objectiveId) != null) throw new IllegalStateException("Scoreboard objective already exists: " + objectiveId);
            scoreboard.registerNewObjective(objectiveId, criteria, TextFormatter.formatLegacy(name));
        });

        operations.put("scoreboard_remove", (ctx, node) -> {
            requireObjective(ctx, node).unregister();
        });

        operations.put("scoreboard_set_score", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            Integer score = ctx.getInputValue(node, "score", Integer.class, 0);
            requireObjective(ctx, node).getScore(player.getName()).setScore(score);
        });

        operations.put("scoreboard_get_score", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            ctx.setOutput(node, "score", requireObjective(ctx, node).getScore(player.getName()).getScore());
        });

        operations.put("scoreboard_reset_score", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            requireObjective(ctx, node).getScore(player.getName()).resetScore();
        });

        operations.put("scoreboard_set_display", (ctx, node) -> {
            String displaySlot = ctx.getInputValue(node, "display_slot", String.class, "sidebar");
            DisplaySlot slot = switch (displaySlot.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
                case "sidebar" -> DisplaySlot.SIDEBAR;
                case "list", "player_list" -> DisplaySlot.PLAYER_LIST;
                case "below_name", "belowname" -> DisplaySlot.BELOW_NAME;
                default -> throw new IllegalArgumentException("Unknown scoreboard display slot: " + displaySlot);
            };
            requireObjective(ctx, node).setDisplaySlot(slot);
        });

        operations.put("scoreboard_add_score", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 0);
            Objective objective = requireObjective(ctx, node);
            int current = objective.getScore(player.getName()).getScore();
            objective.getScore(player.getName()).setScore(Math.addExact(current, amount));
        });

        operations.put("scoreboard_remove_score", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 0);
            Objective objective = requireObjective(ctx, node);
            int current = objective.getScore(player.getName()).getScore();
            objective.getScore(player.getName()).setScore(Math.subtractExact(current, amount));
        });

        operations.put("scoreboard_get_objectives", (ctx, node) -> {
            List<String> objectives = new ArrayList<>();
            for (Objective obj : requireScoreboard().getObjectives()) {
                objectives.add(obj.getName());
            }
            ctx.setOutput(node, "objectives", objectives);
            ctx.setOutput(node, "objective_ids", objectives);
        });

        operations.put("scoreboard_set_name", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Scoreboard objective name is required");
            requireObjective(ctx, node).setDisplayName(TextFormatter.formatLegacy(name));
        });

        operations.put("scoreboard_set_render_type", (ctx, node) -> {
            String renderType = ctx.getInputValue(node, "render_type", String.class, "integer");
            RenderType type = switch (renderType.toLowerCase(Locale.ROOT)) {
                case "integer" -> RenderType.INTEGER;
                case "hearts" -> RenderType.HEARTS;
                default -> throw new IllegalArgumentException("Unknown scoreboard render type: " + renderType);
            };
            requireObjective(ctx, node).setRenderType(type);
        });

        operations.put("scoreboard_set_sidebar_line", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            String text = ctx.getInputValue(node, "title", String.class, "");
            Integer line = ctx.getInputValue(node, "line", Integer.class, 0);
            Integer score = ctx.getInputValue(node, "score", Integer.class, line);
            boolean usePapi = ctx.getInputValue(node, "use_papi", Boolean.class, false);
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Scoreboard sidebar line text is required");
            Objective objective = requirePlayerObjective(ctx, node, player);
            String entry = ReSyncPlaceholderUtil.apply(player, text, usePapi);
            objective.getScore(TextFormatter.formatLegacy(entry)).setScore(score);
        });

        operations.put("scoreboard_clear_sidebar", (ctx, node) -> {
            requireObjective(ctx, node).setDisplaySlot(null);
        });

        operations.put("scoreboard_hide_active", (ctx, node) -> {
            ScoreboardTemplateManager.hideActive(requirePlayer(ctx, node));
        });

        operations.put("scoreboard_show_template", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            String templateId = ctx.getInputValue(node, "template_id", String.class, "");
            if (templateId.isEmpty()) {
                templateId = ctx.getInputValue(node, "scoreboard_id", String.class, "");
            }
            if (templateId.isBlank()) throw new IllegalArgumentException("Scoreboard template ID is required");
            boolean usePapi = ctx.getInputValue(node, "use_papi", Boolean.class, false);
            if (!ScoreboardTemplateManager.showTemplate(player, templateId, usePapi)) throw new IllegalArgumentException("Unknown scoreboard template: " + templateId);
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ScoreboardHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown scoreboard operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private static Scoreboard requireScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) throw new IllegalStateException("Scoreboard manager is unavailable");
        return manager.getMainScoreboard();
    }

    private static String requireObjectiveId(FlowContext context, FlowNode node) {
        String objectiveId = context.getInputValue(node, "objective_id", String.class, "");
        if (objectiveId == null || objectiveId.isBlank()) throw new IllegalArgumentException("Scoreboard objective ID is required");
        if (objectiveId.length() > 128) throw new IllegalArgumentException("Scoreboard objective ID cannot exceed 128 characters");
        return objectiveId;
    }

    private static Objective requireObjective(FlowContext context, FlowNode node) {
        String objectiveId = requireObjectiveId(context, node);
        Objective objective = requireScoreboard().getObjective(objectiveId);
        if (objective == null) throw new IllegalArgumentException("Unknown scoreboard objective: " + objectiveId);
        return objective;
    }

    private static Objective requirePlayerObjective(FlowContext context, FlowNode node, Player player) {
        String objectiveId = requireObjectiveId(context, node);
        Objective objective = player.getScoreboard().getObjective(objectiveId);
        if (objective == null) throw new IllegalArgumentException("Player scoreboard does not contain objective: " + objectiveId);
        return objective;
    }

    private static Player requirePlayer(FlowContext context, FlowNode node) {
        Player player = context.getPlayerInput(node, "player");
        if (player == null) throw new IllegalArgumentException("Player is required");
        return player;
    }
}
