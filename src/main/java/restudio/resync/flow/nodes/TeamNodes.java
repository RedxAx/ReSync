package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class TeamNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("team_create", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("team_delete", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("team_set_display_name", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            String displayName = ctx.getInputValue(node, "name", String.class, "");
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
                team.setDisplayName(TextFormatter.formatLegacy(displayName));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("team_set_prefix", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("team_set_suffix", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("team_set_color", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("team_set_allow_friendly_fire", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("team_see_friendly_invisibles", (ctx, node) -> {
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
                team.setCanSeeFriendlyInvisibles(allow);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("team_add_player", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("team_remove_player", (ctx, node) -> {
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
            ctx.triggerOutput("flow");
        });

        registry.register("team_has_player", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            if (teamId.isEmpty() || player == null) {
                ctx.setNodeOutput(nodeId, "has_player", false);
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                ctx.setNodeOutput(nodeId, "has_player", false);
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team != null) {
                boolean hasPlayer = team.hasEntry(player.getName());
                ctx.setNodeOutput(nodeId, "has_player", hasPlayer);
            } else {
                ctx.setNodeOutput(nodeId, "has_player", false);
            }
        });

        registry.register("team_get_players", (ctx, node) -> {
            String teamId = ctx.getInputValue(node, "team_id", String.class, "");
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            if (teamId.isEmpty()) {
                ctx.setNodeOutput(nodeId, "players", new ArrayList<Player>());
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                ctx.setNodeOutput(nodeId, "players", new ArrayList<Player>());
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getTeam(teamId);
            if (team != null) {
                List<Player> players = new ArrayList<>();
                for (String entry : team.getEntries()) {
                    Player p = Bukkit.getPlayerExact(entry);
                    if (p != null) {
                        players.add(p);
                    }
                }
                ctx.setNodeOutput(nodeId, "players", players);
            } else {
                ctx.setNodeOutput(nodeId, "players", new ArrayList<Player>());
            }
        });

        registry.register("team_get_team", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            if (player == null) {
                ctx.setNodeOutput(nodeId, "team_id", "");
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                ctx.setNodeOutput(nodeId, "team_id", "");
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team team = scoreboard.getEntryTeam(player.getName());
            if (team != null) {
                ctx.setNodeOutput(nodeId, "team_id", team.getName());
            } else {
                ctx.setNodeOutput(nodeId, "team_id", "");
            }
        });

        registry.register("team_get_teams", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getMainScoreboard();
            List<String> teamIds = new ArrayList<>();
            for (Team team : scoreboard.getTeams()) {
                teamIds.add(team.getName());
            }
            ctx.setNodeOutput(nodeId, "team_ids", teamIds);
        });
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (TeamNodes.class) {
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

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "team_create", displayName = "Create Team", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "name", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamCreate(FlowContext ctx, FlowNode node) {
        executeLegacy("team_create", ctx, node);
    }

    @DefineNode(id = "team_delete", displayName = "Delete Team", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamDelete(FlowContext ctx, FlowNode node) {
        executeLegacy("team_delete", ctx, node);
    }

    @DefineNode(id = "team_set_display_name", displayName = "Set Team Display Name", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "name", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamSetDisplayName(FlowContext ctx, FlowNode node) {
        executeLegacy("team_set_display_name", ctx, node);
    }

    @DefineNode(id = "team_set_prefix", displayName = "Set Team Prefix", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "prefix", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamSetPrefix(FlowContext ctx, FlowNode node) {
        executeLegacy("team_set_prefix", ctx, node);
    }

    @DefineNode(id = "team_set_suffix", displayName = "Set Team Suffix", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "suffix", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamSetSuffix(FlowContext ctx, FlowNode node) {
        executeLegacy("team_set_suffix", ctx, node);
    }

    @DefineNode(id = "team_set_color", displayName = "Set Team Color", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "color", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamSetColor(FlowContext ctx, FlowNode node) {
        executeLegacy("team_set_color", ctx, node);
    }

    @DefineNode(id = "team_set_allow_friendly_fire", displayName = "Allow Friendly Fire", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "allow", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamSetAllowFriendlyFire(FlowContext ctx, FlowNode node) {
        executeLegacy("team_set_allow_friendly_fire", ctx, node);
    }

    @DefineNode(id = "team_see_friendly_invisibles", displayName = "See Friendly Invisibles", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "allow", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamSeeFriendlyInvisibles(FlowContext ctx, FlowNode node) {
        executeLegacy("team_see_friendly_invisibles", ctx, node);
    }

    @DefineNode(id = "team_add_player", displayName = "Add Player to Team", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamAddPlayer(FlowContext ctx, FlowNode node) {
        executeLegacy("team_add_player", ctx, node);
    }

    @DefineNode(id = "team_remove_player", displayName = "Remove Player from Team", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void teamRemovePlayer(FlowContext ctx, FlowNode node) {
        executeLegacy("team_remove_player", ctx, node);
    }

    @DefineNode(id = "team_has_player", displayName = "Has Player", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "team_id", dataType = FlowType.STRING),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER)
            },
            outputs = {@FlowPin(name = "has_player", dataType = FlowType.BOOLEAN)})
    public void teamHasPlayer(FlowContext ctx, FlowNode node) {
        executeLegacy("team_has_player", ctx, node);
    }

    @DefineNode(id = "team_get_players", displayName = "Get Team Players", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {@FlowPin(name = "team_id", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "players", dataType = FlowType.LIST)})
    public void teamGetPlayers(FlowContext ctx, FlowNode node) {
        executeLegacy("team_get_players", ctx, node);
    }

    @DefineNode(id = "team_get_team", displayName = "Get Player's Team", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "team_id", dataType = FlowType.STRING)})
    public void teamGetTeam(FlowContext ctx, FlowNode node) {
        executeLegacy("team_get_team", ctx, node);
    }

    @DefineNode(id = "team_get_teams", displayName = "Get All Teams", category = NodeDefinition.NodeCategory.SCOREBOARD,
            outputs = {@FlowPin(name = "team_ids", dataType = FlowType.LIST)})
    public void teamGetTeams(FlowContext ctx, FlowNode node) {
        executeLegacy("team_get_teams", ctx, node);
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
