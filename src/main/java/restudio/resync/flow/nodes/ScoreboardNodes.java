package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.List;

public class ScoreboardNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("scoreboard_create", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            String name = ctx.getInputValue(node, "name", String.class, "Objective");
            String criteria = ctx.getInputValue(node, "criteria", String.class, "dummy");

            if (objectiveId.isEmpty()) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective == null) {
                objective = scoreboard.registerNewObjective(objectiveId, criteria, TextFormatter.formatLegacy(name));
            } else {
                objective.setDisplayName(TextFormatter.formatLegacy(name));
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_delete", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");

            if (objectiveId.isEmpty()) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                objective.unregister();
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_set_display", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            String displaySlot = ctx.getInputValue(node, "display_slot", String.class, "sidebar");

            if (objectiveId.isEmpty()) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                DisplaySlot slot = switch (displaySlot.toLowerCase()) {
                    case "list" -> DisplaySlot.PLAYER_LIST;
                    case "below_name" -> DisplaySlot.BELOW_NAME;
                    default -> DisplaySlot.SIDEBAR;
                };
                objective.setDisplaySlot(slot);
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_set_score", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer score = ctx.getInputValue(node, "score", Integer.class, 0);

            if (objectiveId.isEmpty() || player == null) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                Score sc = objective.getScore(player.getName());
                sc.setScore(score);
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_add_score", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);

            if (objectiveId.isEmpty() || player == null) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                Score sc = objective.getScore(player.getName());
                sc.setScore(sc.getScore() + amount);
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_remove_score", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);

            if (objectiveId.isEmpty() || player == null) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                Score sc = objective.getScore(player.getName());
                sc.setScore(sc.getScore() - amount);
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_reset_score", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer score = ctx.getInputValue(node, "score", Integer.class, 0);

            if (objectiveId.isEmpty() || player == null) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                Score sc = objective.getScore(player.getName());
                sc.setScore(score);
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_get_score", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);

            if (objectiveId.isEmpty() || player == null) {
                return;
            }

            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                Score sc = objective.getScore(player.getName());
                ctx.setNodeOutput(nodeId, "score", sc.getScore());
            }
        });

        registry.register("scoreboard_get_objectives", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            List<String> objectiveIds = new ArrayList<>();

            for (Objective objective : scoreboard.getObjectives()) {
                objectiveIds.add(objective.getName());
            }

            ctx.setNodeOutput(nodeId, "objective_ids", objectiveIds);
        });

        registry.register("scoreboard_set_name", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            String name = ctx.getInputValue(node, "name", String.class, "Objective");

            if (objectiveId.isEmpty()) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                objective.setDisplayName(TextFormatter.formatLegacy(name));
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_set_render_type", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            String renderType = ctx.getInputValue(node, "render_type", String.class, "integer");

            if (objectiveId.isEmpty()) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                RenderType type = switch (renderType.toLowerCase()) {
                    case "hearts" -> RenderType.HEARTS;
                    default -> RenderType.INTEGER;
                };
                objective.setRenderType(type);
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_clear", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);

            if (objectiveId.isEmpty() || player == null) {
                return;
            }

            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }

            Scoreboard scoreboard = manager.getMainScoreboard();
            Objective objective = scoreboard.getObjective(objectiveId);

            if (objective != null) {
                scoreboard.resetScores(player.getName());
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
}
