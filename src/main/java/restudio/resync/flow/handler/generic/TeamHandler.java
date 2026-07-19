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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class TeamHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public TeamHandler() {
        operations.put("team_create", (ctx, node) -> {
            String teamId = requireTeamId(ctx, node);
            String name = ctx.getInputValue(node, "name", String.class, "Team");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Team display name is required");
            Scoreboard scoreboard = requireScoreboard();
            if (scoreboard.getTeam(teamId) != null) throw new IllegalStateException("Team already exists: " + teamId);
            Team team = scoreboard.registerNewTeam(teamId);
            team.setDisplayName(TextFormatter.formatLegacy(name));
        });

        operations.put("team_remove", (ctx, node) -> {
            requireTeam(ctx, node).unregister();
        });

        operations.put("team_add_player", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            Team team = requireTeam(ctx, node);
            team.addEntry(player.getName());
            player.setScoreboard(requireScoreboard());
        });

        operations.put("team_remove_player", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            Team team = requireTeam(ctx, node);
            if (!team.removeEntry(player.getName())) throw new IllegalStateException("Player is not in team: " + team.getName());
        });

        operations.put("team_set_prefix", (ctx, node) -> {
            String prefix = ctx.getInputValue(node, "prefix", String.class, "");
            requireTeam(ctx, node).setPrefix(TextFormatter.formatLegacy(prefix));
        });

        operations.put("team_set_suffix", (ctx, node) -> {
            String suffix = ctx.getInputValue(node, "suffix", String.class, "");
            requireTeam(ctx, node).setSuffix(TextFormatter.formatLegacy(suffix));
        });

        operations.put("team_set_color", (ctx, node) -> {
            String colorName = ctx.getInputValue(node, "color", String.class, "WHITE");
            ChatColor color;
            try {
                color = ChatColor.valueOf(colorName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown team color: " + colorName, exception);
            }
            if (!color.isColor()) throw new IllegalArgumentException("Team color must be a color: " + colorName);
            requireTeam(ctx, node).setColor(color);
        });

        operations.put("team_set_friendly_fire", (ctx, node) -> {
            Boolean allow = ctx.getInputValue(node, "allow", Boolean.class, true);
            requireTeam(ctx, node).setAllowFriendlyFire(allow);
        });

        operations.put("team_get_players", (ctx, node) -> {
            ctx.setOutput(node, "players", new ArrayList<>(requireTeam(ctx, node).getEntries()));
        });

        operations.put("team_get_team", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            for (Team team : requireScoreboard().getTeams()) {
                if (team.hasEntry(player.getName())) {
                    ctx.setOutput(node, "team_id", team.getName());
                    return;
                }
            }
            ctx.setOutput(node, "team_id", "");
        });

        operations.put("team_get_teams", (ctx, node) -> {
            List<String> teams = new ArrayList<>();
            for (Team team : requireScoreboard().getTeams()) {
                teams.add(team.getName());
            }
            ctx.setOutput(node, "teams", teams);
        });

        operations.put("team_has_player", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            ctx.setOutput(node, "has", requireTeam(ctx, node).hasEntry(player.getName()));
        });

        operations.put("team_see_friendly_invisibles", (ctx, node) -> {
            Boolean see = ctx.getInputValue(node, "see", Boolean.class, true);
            requireTeam(ctx, node).setCanSeeFriendlyInvisibles(see);
        });

        operations.put("team_set_display_name", (ctx, node) -> {
            String displayName = ctx.getInputValue(node, "display_name", String.class, "");
            if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Team display name is required");
            requireTeam(ctx, node).setDisplayName(TextFormatter.formatLegacy(displayName));
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("TeamHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown team operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private static Scoreboard requireScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) throw new IllegalStateException("Scoreboard manager is unavailable");
        return manager.getMainScoreboard();
    }

    private static String requireTeamId(FlowContext context, FlowNode node) {
        String teamId = context.getInputValue(node, "team_id", String.class, "");
        if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("Team ID is required");
        if (teamId.length() > 128) throw new IllegalArgumentException("Team ID cannot exceed 128 characters");
        return teamId;
    }

    private static Team requireTeam(FlowContext context, FlowNode node) {
        String teamId = requireTeamId(context, node);
        Team team = requireScoreboard().getTeam(teamId);
        if (team == null) throw new IllegalArgumentException("Unknown team: " + teamId);
        return team;
    }

    private static Player requirePlayer(FlowContext context, FlowNode node) {
        Player player = context.getInputValue(node, "player", Player.class, context.getPlayer());
        if (player == null) throw new IllegalArgumentException("Player is required");
        return player;
    }
}
