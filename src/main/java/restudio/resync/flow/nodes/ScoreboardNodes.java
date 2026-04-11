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
import restudio.resync.Log;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScoreboardNodes {
    private static final Map<UUID, ActiveTemplateState> ACTIVE_TEMPLATES = new ConcurrentHashMap<>();
    private static final Map<String, java.util.function.BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
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

        registry.register("scoreboard_show_template", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String scoreboardId = ctx.getInputValue(node, "scoreboard_id", String.class, "");
            Boolean usePapi = ctx.getInputValue(node, "use_papi", Boolean.class, true);

            if (player == null || scoreboardId == null || scoreboardId.isBlank()) {
                ctx.triggerOutput("flow");
                return;
            }

            ScoreboardDefinition definition = getScoreboardDefinition(scoreboardId);
            if (definition != null) {
                showTemplate(player, definition, Boolean.TRUE.equals(usePapi));
            } else {
                Log.warn("Scoreboard definition not found: " + scoreboardId);
            }

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_hide_active", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            hideActive(player);
            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_set_sidebar_line", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "resync");
            String title = ctx.getInputValue(node, "title", String.class, "Scoreboard");
            String line = ctx.getInputValue(node, "line", String.class, "");
            Integer score = ctx.getInputValue(node, "score", Integer.class, 1);
            Boolean usePapi = ctx.getInputValue(node, "use_papi", Boolean.class, true);

            runSync(() -> {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (player != null && manager != null) {
                    Scoreboard board = player.getScoreboard();
                    if (board == null || board == manager.getMainScoreboard()) {
                        board = manager.getNewScoreboard();
                        player.setScoreboard(board);
                    }
                    String resolvedObjectiveId = sanitizeObjectiveId(objectiveId, "resync");
                    Objective objective = board.getObjective(resolvedObjectiveId);
                    String renderedTitle = TextFormatter.formatLegacy(applyPlaceholders(player, title, Boolean.TRUE.equals(usePapi)));
                    if (objective == null) {
                        objective = board.registerNewObjective(resolvedObjectiveId, "dummy", renderedTitle);
                    } else {
                        objective.setDisplayName(renderedTitle);
                    }
                    objective.setDisplaySlot(DisplaySlot.SIDEBAR);

                    int targetScore = score != null ? score : 1;
                    for (String entry : new ArrayList<>(board.getEntries())) {
                        Score existing = objective.getScore(entry);
                        if (existing.isScoreSet() && existing.getScore() == targetScore) {
                            board.resetScores(entry);
                        }
                    }
                    Set<String> used = new HashSet<>(board.getEntries());
                    String renderedLine = TextFormatter.formatLegacy(applyPlaceholders(player, line, Boolean.TRUE.equals(usePapi)));
                    String entry = uniqueEntry(renderedLine, used, targetScore);
                    objective.getScore(entry).setScore(targetScore);
                }
            });

            ctx.triggerOutput("flow");
        });

        registry.register("scoreboard_clear_sidebar", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String objectiveId = ctx.getInputValue(node, "objective_id", String.class, "resync");
            runSync(() -> {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (player != null && manager != null) {
                    Scoreboard board = player.getScoreboard();
                    if (board != null && board != manager.getMainScoreboard()) {
                        Objective objective = board.getObjective(sanitizeObjectiveId(objectiveId, "resync"));
                        if (objective != null) {
                            for (String entry : new ArrayList<>(board.getEntries())) {
                                board.resetScores(entry);
                            }
                        }
                    }
                }
            });
            ctx.triggerOutput("flow");
        });
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (ScoreboardNodes.class) {
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
        java.util.function.BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "scoreboard_create", displayName = "Create Objective", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "criteria", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardCreate(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_create", ctx, node);
    }

    @DefineNode(id = "scoreboard_delete", displayName = "Delete Objective", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardDelete(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_delete", ctx, node);
    }

    @DefineNode(id = "scoreboard_set_display", displayName = "Set Display Slot", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "display_slot", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardSetDisplay(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_set_display", ctx, node);
    }

    @DefineNode(id = "scoreboard_set_score", displayName = "Set Score", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "score", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardSetScore(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_set_score", ctx, node);
    }

    @DefineNode(id = "scoreboard_add_score", displayName = "Add Score", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardAddScore(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_add_score", ctx, node);
    }

    @DefineNode(id = "scoreboard_remove_score", displayName = "Remove Score", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardRemoveScore(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_remove_score", ctx, node);
    }

    @DefineNode(id = "scoreboard_reset_score", displayName = "Reset Score", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "score", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardResetScore(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_reset_score", ctx, node);
    }

    @DefineNode(id = "scoreboard_get_score", displayName = "Get Score", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER)
            },
            outputs = {@FlowPin(name = "score", dataType = FlowType.NUMBER)})
    public void scoreboardGetScore(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_get_score", ctx, node);
    }

    @DefineNode(id = "scoreboard_get_objectives", displayName = "Get Objectives", category = NodeDefinition.NodeCategory.SCOREBOARD,
            outputs = {@FlowPin(name = "objective_ids", dataType = FlowType.LIST)})
    public void scoreboardGetObjectives(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_get_objectives", ctx, node);
    }

    @DefineNode(id = "scoreboard_set_name", displayName = "Set Objective Name", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "name", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardSetName(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_set_name", ctx, node);
    }

    @DefineNode(id = "scoreboard_set_render_type", displayName = "Set Render Type", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "render_type", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardSetRenderType(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_set_render_type", ctx, node);
    }

    @DefineNode(id = "scoreboard_clear", displayName = "Clear Score", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardClear(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_clear", ctx, node);
    }

    @DefineNode(id = "scoreboard_show_template", displayName = "Show Scoreboard", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "scoreboard_id", dataType = FlowType.STRING),
                    @FlowPin(name = "use_papi", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardShowTemplate(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_show_template", ctx, node);
    }

    @DefineNode(id = "scoreboard_hide_active", displayName = "Hide Scoreboard", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardHideActive(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_hide_active", ctx, node);
    }

    @DefineNode(id = "scoreboard_set_sidebar_line", displayName = "Set Sidebar Line", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING),
                    @FlowPin(name = "title", dataType = FlowType.STRING),
                    @FlowPin(name = "line", dataType = FlowType.STRING),
                    @FlowPin(name = "score", dataType = FlowType.NUMBER),
                    @FlowPin(name = "use_papi", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardSetSidebarLine(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_set_sidebar_line", ctx, node);
    }

    @DefineNode(id = "scoreboard_clear_sidebar", displayName = "Clear Sidebar", category = NodeDefinition.NodeCategory.SCOREBOARD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "objective_id", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void scoreboardClearSidebar(FlowContext ctx, FlowNode node) {
        executeLegacy("scoreboard_clear_sidebar", ctx, node);
    }

    public static boolean showTemplate(Player player, ScoreboardDefinition definition, boolean usePapi) {
        if (player == null || definition == null) {
            return false;
        }
        AtomicBoolean applied = new AtomicBoolean(false);
        runSync(() -> {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            String scoreboardId = definition.getId() != null ? definition.getId() : "resync";
            Scoreboard playerBoard = manager.getNewScoreboard();
            String objectiveId = sanitizeObjectiveId(definition.getObjectiveId(), scoreboardId);
            String title = TextFormatter.formatLegacy(applyPlaceholders(player, definition.getTitle(), usePapi));
            Objective objective = playerBoard.getObjective(objectiveId);
            if (objective == null) {
                objective = playerBoard.registerNewObjective(objectiveId, "dummy", title);
            } else {
                objective.setDisplayName(title);
            }
            DisplaySlot slot = parseDisplaySlot(definition.getDisplaySlot());
            objective.setDisplaySlot(slot);
            if (slot == DisplaySlot.SIDEBAR) {
                applySidebarTemplateLines(player, objective, definition.getLines(), usePapi);
            } else {
                applyPlayerSlotTemplateLines(player, objective, definition.getLines(), usePapi);
            }
            player.setScoreboard(playerBoard);
            ACTIVE_TEMPLATES.put(player.getUniqueId(), new ActiveTemplateState(scoreboardId, usePapi));
            applied.set(true);
        });
        return applied.get();
    }

    public static void hideActive(Player player) {
        if (player == null) {
            return;
        }
        runSync(() -> {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager != null) {
                player.setScoreboard(manager.getMainScoreboard());
            }
            ACTIVE_TEMPLATES.remove(player.getUniqueId());
        });
    }

    public static void clearTrackedPlayer(Player player) {
        if (player == null) {
            return;
        }
        ACTIVE_TEMPLATES.remove(player.getUniqueId());
    }

    public static boolean setPlayerSlotScore(Player player, DisplaySlot slot, String objectiveId, String title, int score) {
        if (player == null) {
            return false;
        }
        return setSlotEntryScore(player, slot, objectiveId, title, player.getName(), score);
    }

    public static boolean setSlotEntryScore(Player viewer, DisplaySlot slot, String objectiveId, String title, String entryName, int score) {
        if (viewer == null || slot == null || entryName == null || entryName.isBlank()) {
            return false;
        }
        AtomicBoolean applied = new AtomicBoolean(false);
        runSync(() -> {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard board = viewer.getScoreboard();
            if (board == null || board == manager.getMainScoreboard()) {
                board = manager.getNewScoreboard();
                viewer.setScoreboard(board);
            }
            String resolvedObjectiveId = sanitizeObjectiveId(objectiveId, "resync");
            String resolvedTitle = TextFormatter.formatLegacy(title != null && !title.isBlank() ? title : resolvedObjectiveId);
            Objective objective = board.getObjective(resolvedObjectiveId);
            if (objective == null) {
                objective = board.registerNewObjective(resolvedObjectiveId, "dummy", resolvedTitle);
            } else {
                objective.setDisplayName(resolvedTitle);
            }
            objective.setDisplaySlot(slot);
            objective.getScore(entryName).setScore(score);
            applied.set(true);
        });
        return applied.get();
    }

    public static void refreshActiveTemplates(FlowStorage storage, String scoreboardId) {
        if (storage == null || scoreboardId == null || scoreboardId.isBlank()) {
            return;
        }
        ScoreboardDefinition definition = storage.getScoreboard(scoreboardId);
        if (definition == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ActiveTemplateState state = ACTIVE_TEMPLATES.get(player.getUniqueId());
            if (state != null && scoreboardId.equalsIgnoreCase(state.scoreboardId())) {
                showTemplate(player, definition, state.usePapi());
            }
        }
        String defaultId = storage.getDefaultScoreboardId();
        if (defaultId != null && defaultId.equalsIgnoreCase(scoreboardId)) {
            boolean usePapi = storage.isDefaultScoreboardUsePapi();
            for (Player player : Bukkit.getOnlinePlayers()) {
                ActiveTemplateState state = ACTIVE_TEMPLATES.get(player.getUniqueId());
                if (state == null || defaultId.equalsIgnoreCase(state.scoreboardId())) {
                    showTemplate(player, definition, usePapi);
                }
            }
        }
    }

    public static void refreshActiveTemplates(FlowStorage storage) {
        if (storage == null) {
            return;
        }
        String defaultId = storage.getDefaultScoreboardId();
        boolean defaultUsePapi = storage.isDefaultScoreboardUsePapi();
        ScoreboardDefinition defaultDefinition = (defaultId != null && !defaultId.isBlank()) ? storage.getScoreboard(defaultId) : null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            ActiveTemplateState state = ACTIVE_TEMPLATES.get(player.getUniqueId());
            if (state == null) {
                if (defaultDefinition != null) {
                    showTemplate(player, defaultDefinition, defaultUsePapi);
                }
                continue;
            }
            if (defaultDefinition != null && defaultId.equalsIgnoreCase(state.scoreboardId())) {
                showTemplate(player, defaultDefinition, defaultUsePapi);
                continue;
            }
            ScoreboardDefinition definition = storage.getScoreboard(state.scoreboardId());
            if (definition != null) {
                showTemplate(player, definition, state.usePapi());
            }
        }
    }

    public static void clearActiveTemplateReferences(String scoreboardId, boolean hideAffectedPlayers) {
        if (scoreboardId == null || scoreboardId.isBlank()) {
            return;
        }
        List<Player> affected = new ArrayList<>();
        for (Map.Entry<UUID, ActiveTemplateState> entry : ACTIVE_TEMPLATES.entrySet()) {
            ActiveTemplateState state = entry.getValue();
            if (state != null && scoreboardId.equalsIgnoreCase(state.scoreboardId())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    affected.add(player);
                }
                ACTIVE_TEMPLATES.remove(entry.getKey());
            }
        }
        if (hideAffectedPlayers) {
            for (Player player : affected) {
                hideActive(player);
            }
        }
    }

    public static boolean setDefaultScoreboard(String scoreboardId, boolean usePapi) {
        FlowStorage storage = getFlowStorage();
        if (storage == null || scoreboardId == null || scoreboardId.isBlank()) {
            return false;
        }
        ScoreboardDefinition definition = storage.getScoreboard(scoreboardId);
        if (definition == null) {
            return false;
        }
        storage.setDefaultScoreboard(scoreboardId, usePapi);
        for (Player player : Bukkit.getOnlinePlayers()) {
            showTemplate(player, definition, usePapi);
        }
        return true;
    }

    public static boolean clearDefaultScoreboard() {
        FlowStorage storage = getFlowStorage();
        if (storage == null) {
            return false;
        }
        storage.clearDefaultScoreboard();
        return true;
    }

    public static String getDefaultScoreboardId() {
        FlowStorage storage = getFlowStorage();
        return storage != null ? storage.getDefaultScoreboardId() : null;
    }

    public static boolean isDefaultScoreboardUsePapi() {
        FlowStorage storage = getFlowStorage();
        return storage != null && storage.isDefaultScoreboardUsePapi();
    }

    public static void applyDefaultOnJoin(Player player) {
        if (player == null) {
            return;
        }
        FlowStorage storage = getFlowStorage();
        if (storage == null) {
            return;
        }
        String defaultId = storage.getDefaultScoreboardId();
        if (defaultId == null || defaultId.isBlank()) {
            return;
        }
        ScoreboardDefinition definition = storage.getScoreboard(defaultId);
        if (definition != null) {
            showTemplate(player, definition, storage.isDefaultScoreboardUsePapi());
        }
    }

    public static boolean clearPlayerObjective(Player player, String objectiveId) {
        if (player == null) {
            return false;
        }
        AtomicBoolean changed = new AtomicBoolean(false);
        runSync(() -> {
            Scoreboard board = player.getScoreboard();
            if (board == null) {
                return;
            }
            Objective objective = board.getObjective(sanitizeObjectiveId(objectiveId, "resync"));
            if (objective != null) {
                objective.unregister();
                changed.set(true);
            }
        });
        return changed.get();
    }

    private static void applySidebarTemplateLines(Player player, Objective objective, List<String> lines, boolean usePapi) {
        List<String> source = lines != null ? lines : List.of();
        int scoreValue = Math.min(15, source.size());
        Set<String> usedEntries = new HashSet<>();
        for (int i = 0; i < source.size() && i < 15; i++) {
            String rendered = TextFormatter.formatLegacy(applyPlaceholders(player, source.get(i), usePapi));
            String entry = uniqueEntry(rendered, usedEntries, i);
            objective.getScore(entry).setScore(scoreValue--);
        }
    }

    private static void applyPlayerSlotTemplateLines(Player player, Objective objective, List<String> lines, boolean usePapi) {
        List<String> source = lines != null ? lines : List.of();
        int applied = 0;
        for (String line : source) {
            ParsedScoreEntry parsed = parsePlayerScoreLine(applyPlaceholders(player, line, usePapi), player);
            if (parsed == null) {
                continue;
            }
            objective.getScore(parsed.entry()).setScore(parsed.score());
            applied++;
        }
        if (applied == 0) {
            objective.getScore(player.getName()).setScore(0);
        }
    }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static ScoreboardDefinition getScoreboardDefinition(String scoreboardId) {
        FlowStorage storage = getFlowStorage();
        if (storage == null || scoreboardId == null || scoreboardId.isBlank()) {
            return null;
        }
        return storage.getScoreboard(scoreboardId);
    }

    private static FlowStorage getFlowStorage() {
        return FlowRuntimeAccess.getStorage();
    }

    private static DisplaySlot parseDisplaySlot(String displaySlot) {
        if (displaySlot == null) {
            return DisplaySlot.SIDEBAR;
        }
        return switch (displaySlot.toLowerCase()) {
            default -> DisplaySlot.SIDEBAR;
        };
    }

    private static String sanitizeObjectiveId(String objectiveId, String fallback) {
        String resolved = objectiveId != null && !objectiveId.isBlank() ? objectiveId : fallback;
        if (resolved == null || resolved.isBlank()) {
            resolved = "resync";
        }
        return resolved.length() > 16 ? resolved.substring(0, 16) : resolved;
    }

    private static ParsedScoreEntry parsePlayerScoreLine(String text, Player fallbackPlayer) {
        if (fallbackPlayer == null) {
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int colon = trimmed.indexOf(':');
        int equals = trimmed.indexOf('=');
        int separator;
        if (colon < 0) {
            separator = equals;
        } else if (equals < 0) {
            separator = colon;
        } else {
            separator = Math.min(colon, equals);
        }
        if (separator > 0 && separator < trimmed.length() - 1) {
            String entryName = normalizePlayerEntry(trimmed.substring(0, separator), fallbackPlayer);
            Integer score = parseInteger(trimmed.substring(separator + 1).trim());
            if (entryName != null && score != null) {
                return new ParsedScoreEntry(entryName, score);
            }
        }
        Integer directScore = parseInteger(trimmed);
        if (directScore != null) {
            return new ParsedScoreEntry(fallbackPlayer.getName(), directScore);
        }
        String entryName = normalizePlayerEntry(trimmed, fallbackPlayer);
        if (entryName == null) {
            return null;
        }
        return new ParsedScoreEntry(entryName, 0);
    }

    private static String normalizePlayerEntry(String raw, Player fallbackPlayer) {
        if (fallbackPlayer == null) {
            return null;
        }
        String candidate = ChatColor.stripColor(TextFormatter.formatLegacy(raw != null ? raw : "")).trim();
        if (candidate.isBlank()) {
            return fallbackPlayer.getName();
        }
        Player online = Bukkit.getPlayerExact(candidate);
        if (online != null) {
            return online.getName();
        }
        return candidate;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void runSync(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }
        var plugin = FlowRuntimeAccess.getPlugin();
        if (plugin == null) {
            return;
        }
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                runnable.run();
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.warn("Scoreboard task interrupted: " + e.getMessage());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Log.warn("Scoreboard task failed: " + cause.getMessage());
        }
    }

    private record ParsedScoreEntry(String entry, int score) {
    }

    private record ActiveTemplateState(String scoreboardId, boolean usePapi) {
    }

    private static String applyPlaceholders(Player player, String text, boolean usePapi) {
        return ReSyncPlaceholderUtil.apply(player, text, usePapi);
    }

    private static String uniqueEntry(String source, Set<String> used, int salt) {
        String candidate = source == null ? " " : source;
        if (candidate.isEmpty()) {
            candidate = " ";
        }
        if (candidate.length() > 40) {
            candidate = candidate.substring(0, 40);
        }
        if (!used.contains(candidate)) {
            used.add(candidate);
            return candidate;
        }
        ChatColor[] colors = ChatColor.values();
        for (int i = 0; i < colors.length; i++) {
            String suffix = colors[Math.floorMod(salt + i, colors.length)].toString();
            int maxLength = Math.max(0, 40 - suffix.length());
            String prefixed = candidate.length() > maxLength ? candidate.substring(0, maxLength) : candidate;
            String withSuffix = prefixed + suffix;
            if (!used.contains(withSuffix)) {
                used.add(withSuffix);
                return withSuffix;
            }
        }
        used.add(candidate);
        return candidate;
    }
}
