package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ScoreboardHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ScoreboardHandler() {
        operations.put("scoreboard_create", (ctx, node) -> {
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
        });

        operations.put("scoreboard_remove", (ctx, node) -> {
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
        });

        operations.put("scoreboard_set_score", (ctx, node) -> {
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
                objective.getScore(player.getName()).setScore(score);
            }
        });

        operations.put("scoreboard_get_score", (ctx, node) -> {
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
                ctx.setOutput(node, "score", objective.getScore(player.getName()).getScore());
            }
        });

        operations.put("scoreboard_reset_score", (ctx, node) -> {
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
                objective.getScore(player.getName()).setScore(score);
            }
        });

        operations.put("scoreboard_set_display", (ctx, node) -> {
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
                    default -> DisplaySlot.SIDEBAR;
                };
                objective.setDisplaySlot(slot);
            }
        });

        operations.put("scoreboard_add_score", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 0);
            if (!objectiveId.isEmpty() && player != null) {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager != null) {
                    Objective objective = manager.getMainScoreboard().getObjective(objectiveId);
                    if (objective != null) {
                        int current = objective.getScore(player.getName()).getScore();
                        objective.getScore(player.getName()).setScore(current + amount);
                    }
                }
            }
        });

        operations.put("scoreboard_remove_score", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 0);
            if (!objectiveId.isEmpty() && player != null) {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager != null) {
                    Objective objective = manager.getMainScoreboard().getObjective(objectiveId);
                    if (objective != null) {
                        int current = objective.getScore(player.getName()).getScore();
                        objective.getScore(player.getName()).setScore(current - amount);
                    }
                }
            }
        });

        operations.put("scoreboard_get_objectives", (ctx, node) -> {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            List<String> objectives = new ArrayList<>();
            if (manager != null) {
                for (Objective obj : manager.getMainScoreboard().getObjectives()) {
                    objectives.add(obj.getName());
                }
            }
            ctx.setOutput(node, "objectives", objectives);
            ctx.setOutput(node, "objective_ids", objectives);
        });

        operations.put("scoreboard_set_name", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            String name = ctx.getInputValue(node, "name", String.class, "");
            if (!objectiveId.isEmpty()) {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager != null) {
                    Objective objective = manager.getMainScoreboard().getObjective(objectiveId);
                    if (objective != null) {
                        objective.setDisplayName(TextFormatter.formatLegacy(name));
                    }
                }
            }
        });

        operations.put("scoreboard_set_render_type", (ctx, node) -> {
            // RenderType API not available in this Bukkit version
        });

        operations.put("scoreboard_set_sidebar_line", (ctx, node) -> {
            Integer line = ctx.getInputValue(node, "line", Integer.class, 0);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text.isEmpty()) {
                text = ctx.getInputValue(node, "title", String.class, "");
            }
            // Sidebar line setting requires a specific sidebar objective implementation
            // This is a simplified stub
        });

        operations.put("scoreboard_clear_sidebar", (ctx, node) -> {
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "");
            if (!objectiveId.isEmpty()) {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager != null) {
                    Objective objective = manager.getMainScoreboard().getObjective(objectiveId);
                    if (objective != null) {
                        objective.setDisplaySlot(null);
                    }
                }
            }
        });

        operations.put("scoreboard_hide_active", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null) {
                player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            }
        });

        operations.put("scoreboard_show_template", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String templateId = ctx.getInputValue(node, "template_id", String.class, "");
            if (templateId.isEmpty()) {
                templateId = ctx.getInputValue(node, "scoreboard_id", String.class, "");
            }
            // Template-based scoreboard display is plugin-specific
            // Stub implementation
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ScoreboardHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        ctx.triggerOutput("flow");
    }
}
