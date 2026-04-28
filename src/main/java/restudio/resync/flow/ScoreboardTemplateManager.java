package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import restudio.flow.data.ScoreboardDefinition;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ScoreboardTemplateManager {

    private static final Map<UUID, ActiveScoreboardState> ACTIVE_SCOREBOARDS = new ConcurrentHashMap<>();

    private ScoreboardTemplateManager() {
    }

    public static String getDefaultScoreboardId() {
        FlowStorage storage = getFlowStorage();
        return storage != null ? storage.getDefaultScoreboardId() : null;
    }

    public static boolean isDefaultScoreboardUsePapi() {
        FlowStorage storage = getFlowStorage();
        return storage != null && storage.isDefaultScoreboardUsePapi();
    }

    public static boolean clearDefaultScoreboard() {
        FlowStorage storage = getFlowStorage();
        if (storage == null) {
            return false;
        }
        storage.clearDefaultScoreboard();
        return true;
    }

    public static boolean setDefaultScoreboard(String id, boolean usePapi) {
        FlowStorage storage = getFlowStorage();
        if (storage == null || id == null || id.isBlank()) {
            return false;
        }
        ScoreboardDefinition definition = storage.getScoreboard(id);
        if (definition == null) {
            return false;
        }
        storage.setDefaultScoreboard(id, usePapi);
        applyTemplateToAll(definition, usePapi);
        return true;
    }

    public static void hideActive(Player player) {
        if (player == null) {
            return;
        }
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        ACTIVE_SCOREBOARDS.remove(player.getUniqueId());
    }

    public static boolean showTemplate(Player player, ScoreboardDefinition definition, boolean usePapi) {
        if (player == null || definition == null) {
            return false;
        }
        applyTemplate(player, definition, usePapi);
        ACTIVE_SCOREBOARDS.put(player.getUniqueId(), new ActiveScoreboardState(definition.getId(), usePapi));
        return true;
    }

    public static void refreshActiveTemplates(FlowStorage storage) {
        if (storage == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ActiveScoreboardState state = ACTIVE_SCOREBOARDS.get(player.getUniqueId());
            if (state != null) {
                ScoreboardDefinition definition = storage.getScoreboard(state.scoreboardId());
                if (definition != null) {
                    applyTemplate(player, definition, state.usePapi());
                }
            }
        }
        String defaultId = storage.getDefaultScoreboardId();
        if (defaultId != null && !defaultId.isBlank()) {
            boolean usePapi = storage.isDefaultScoreboardUsePapi();
            ScoreboardDefinition definition = storage.getScoreboard(defaultId);
            if (definition != null) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!ACTIVE_SCOREBOARDS.containsKey(player.getUniqueId())) {
                        applyTemplate(player, definition, usePapi);
                    }
                }
            }
        }
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
            ActiveScoreboardState state = ACTIVE_SCOREBOARDS.get(player.getUniqueId());
            if (state != null && scoreboardId.equalsIgnoreCase(state.scoreboardId())) {
                applyTemplate(player, definition, state.usePapi());
            }
        }
        String defaultId = storage.getDefaultScoreboardId();
        if (scoreboardId.equalsIgnoreCase(defaultId)) {
            boolean usePapi = storage.isDefaultScoreboardUsePapi();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!ACTIVE_SCOREBOARDS.containsKey(player.getUniqueId())) {
                    applyTemplate(player, definition, usePapi);
                }
            }
        }
    }

    public static void clearActiveTemplateReferences(String scoreboardId, boolean clearPlayers) {
        if (scoreboardId == null || scoreboardId.isBlank()) {
            return;
        }
        List<Player> affected = new ArrayList<>();
        for (Map.Entry<UUID, ActiveScoreboardState> entry : ACTIVE_SCOREBOARDS.entrySet()) {
            ActiveScoreboardState state = entry.getValue();
            if (state != null && scoreboardId.equalsIgnoreCase(state.scoreboardId())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    affected.add(player);
                }
                ACTIVE_SCOREBOARDS.remove(entry.getKey());
            }
        }
        if (clearPlayers) {
            for (Player player : affected) {
                hideActive(player);
            }
        }
    }

    public static void applyDefaultOnJoin(Player player) {
        if (player == null) {
            return;
        }
        FlowStorage storage = getFlowStorage();
        if (storage == null) {
            return;
        }
        String id = storage.getDefaultScoreboardId();
        if (id == null || id.isBlank()) {
            return;
        }
        ScoreboardDefinition definition = storage.getScoreboard(id);
        if (definition != null) {
            applyTemplate(player, definition, storage.isDefaultScoreboardUsePapi());
            ACTIVE_SCOREBOARDS.put(player.getUniqueId(), new ActiveScoreboardState(id, storage.isDefaultScoreboardUsePapi()));
        }
    }

    public static void clearTrackedPlayer(Player player) {
        if (player == null) {
            return;
        }
        ACTIVE_SCOREBOARDS.remove(player.getUniqueId());
    }

    private static void applyTemplateToAll(ScoreboardDefinition definition, boolean usePapi) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyTemplate(player, definition, usePapi);
        }
    }

    private static void applyTemplate(Player player, ScoreboardDefinition definition, boolean usePapi) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        Scoreboard scoreboard = manager.getNewScoreboard();
        String objectiveId = definition.getObjectiveId() != null ? definition.getObjectiveId() : definition.getId();
        String title = ReSyncPlaceholderUtil.apply(player, definition.getTitle(), usePapi);
        Objective objective = scoreboard.registerNewObjective(objectiveId, "dummy", TextFormatter.parse(title));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = definition.getLines();
        if (lines != null) {
            int scoreValue = lines.size();
            for (String line : lines) {
                String formatted = ReSyncPlaceholderUtil.apply(player, line, usePapi);
                String entry = TextFormatter.formatLegacy(formatted);
                if (entry.length() > 40) {
                    entry = entry.substring(0, 40);
                }
                Score score = objective.getScore(entry);
                score.setScore(scoreValue);
                scoreValue--;
            }
        }
        player.setScoreboard(scoreboard);
    }

    private static FlowStorage getFlowStorage() {
        return FlowRuntimeAccess.getStorage();
    }

    private record ActiveScoreboardState(String scoreboardId, boolean usePapi) {
    }
}
