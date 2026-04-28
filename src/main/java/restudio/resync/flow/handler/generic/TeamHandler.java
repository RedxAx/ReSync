package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
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

public class TeamHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public TeamHandler() {
        operations.put("team_create", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            String name = ctx.getInputValue(node, "name", String.class, "Team");
            if (teamId.isEmpty()) {
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team == null) {
                team = scoreboard.registerNewTeam(teamId);
                team.setDisplayName(TextFormatter.formatLegacy(name));
            }
        });

        operations.put("team_remove", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            if (teamId.isEmpty()) {
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team != null) {
                team.unregister();
            }
        });

        operations.put("team_add_player", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (teamId.isEmpty() || player == null) {
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team != null) {
                team.addEntry(player.getName());
                player.setScoreboard(scoreboard);
            }
        });

        operations.put("team_remove_player", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (teamId.isEmpty() || player == null) {
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team != null) {
                team.removeEntry(player.getName());
            }
        });

        operations.put("team_set_prefix", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            String prefix = ctx.getInputValue(node, "prefix", String.class, "");
            if (teamId.isEmpty()) {
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team != null) {
                team.setPrefix(TextFormatter.formatLegacy(prefix));
            }
        });

        operations.put("team_set_suffix", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            String suffix = ctx.getInputValue(node, "suffix", String.class, "");
            if (teamId.isEmpty()) {
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team != null) {
                team.setSuffix(TextFormatter.formatLegacy(suffix));
            }
        });

        operations.put("team_set_color", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            String colorName = ctx.getInputValue(node, "color", String.class, "WHITE");
            if (teamId.isEmpty()) {
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team != null) {
                try {
                    ChatColor color = ChatColor.valueOf(colorName.toUpperCase());
                    team.setColor(color);
                } catch (IllegalArgumentException e) {
                    team.setColor(ChatColor.WHITE);
                }
            }
        });

        operations.put("team_set_friendly_fire", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            Boolean allow = ctx.getInputValue(node, "allow", Boolean.class, true);
            if (teamId.isEmpty()) {
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team != null) {
                team.setAllowFriendlyFire(allow);
            }
        });

        operations.put("team_get_players", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            if (!teamId.isEmpty()) {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager != null) {
                    Team team = manager.getMainScoreboard().getTeam(teamId);
                    if (team != null) {
                        ctx.setOutput(node, "players", new ArrayList<>(team.getEntries()));
                    } else {
                        ctx.setOutput(node, "players", new ArrayList<>());
                    }
                }
            } else {
                ctx.setOutput(node, "players", new ArrayList<>());
            }
        });

        operations.put("team_get_team", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null) {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager != null) {
                    for (Team team : manager.getMainScoreboard().getTeams()) {
                        if (team.hasEntry(player.getName())) {
                            ctx.setOutput(node, "team_id", team.getName());
                            return;
                        }
                    }
                }
            }
            ctx.setOutput(node, "team_id", "");
        });

        operations.put("team_get_teams", (ctx, node) -> {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            List<String> teams = new ArrayList<>();
            if (manager != null) {
                for (Team team : manager.getMainScoreboard().getTeams()) {
                    teams.add(team.getName());
                }
            }
            ctx.setOutput(node, "teams", teams);
        });

        operations.put("team_has_player", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            boolean has = false;
            if (!teamId.isEmpty() && player != null) {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager != null) {
                    Team team = manager.getMainScoreboard().getTeam(teamId);
                    has = team != null && team.hasEntry(player.getName());
                }
            }
            ctx.setOutput(node, "has", has);
        });

        operations.put("team_see_friendly_invisibles", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            Boolean see = ctx.getInputValue(node, "see", Boolean.class, true);
            if (!teamId.isEmpty()) {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager != null) {
                    Team team = manager.getMainScoreboard().getTeam(teamId);
                    if (team != null) {
                        team.setCanSeeFriendlyInvisibles(see);
                    }
                }
            }
        });

        operations.put("team_set_display_name", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            String displayName = ctx.getInputValue(node, "display_name", String.class, "");
            if (!teamId.isEmpty()) {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager != null) {
                    Team team = manager.getMainScoreboard().getTeam(teamId);
                    if (team != null) {
                        team.setDisplayName(TextFormatter.formatLegacy(displayName));
                    }
                }
            }
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("TeamHandler", this);
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
