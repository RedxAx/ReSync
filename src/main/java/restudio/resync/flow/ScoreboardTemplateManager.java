package restudio.resync.flow;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.score.BlankScoreFormat;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.flow.data.ScoreboardDefinition;
import restudio.resync.core.Session;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.player.PlayerSessionLinkService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class ScoreboardTemplateManager {
    private static final String[] LINE_ENTRIES = {"§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a", "§b", "§c", "§d", "§e"};
    private static final Pattern ANIMATION_PATTERN = Pattern.compile("%resync_animation[:_][^%]+%", Pattern.CASE_INSENSITIVE);
    private static final Map<UUID, ActiveScoreboardState> ACTIVE_SCOREBOARDS = new ConcurrentHashMap<>();
    private static final Map<UUID, PacketScoreboardState> PACKET_SCOREBOARDS = new ConcurrentHashMap<>();
    private static volatile EditTargetStateSender editTargetStateSender;
    private static volatile PlayerSessionLinkService sessionLinkService;

    private ScoreboardTemplateManager() {
    }

    public static void configureEditStateBridge(EditTargetStateSender sender, PlayerSessionLinkService linkService) {
        editTargetStateSender = sender;
        sessionLinkService = linkService;
    }

    public static void clearEditStateBridge() {
        editTargetStateSender = null;
        sessionLinkService = null;
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
        removePacketScoreboard(player);
        ACTIVE_SCOREBOARDS.remove(player.getUniqueId());
        publishState(player, null);
    }

    public static boolean showTemplate(Player player, String scoreboardId, boolean usePapi) {
        FlowStorage storage = getFlowStorage();
        if (storage == null || scoreboardId == null || scoreboardId.isBlank()) {
            return false;
        }
        return showTemplate(player, storage.getScoreboard(scoreboardId), usePapi);
    }

    public static boolean showTemplate(Player player, ScoreboardDefinition definition, boolean usePapi) {
        if (player == null || definition == null) {
            return false;
        }
        applyTemplate(player, definition, usePapi);
        ACTIVE_SCOREBOARDS.put(player.getUniqueId(), new ActiveScoreboardState(definition.getId(), usePapi));
        publishState(player, definition.getId());
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
                        ACTIVE_SCOREBOARDS.put(player.getUniqueId(), new ActiveScoreboardState(defaultId, usePapi));
                        publishState(player, defaultId);
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
                    ACTIVE_SCOREBOARDS.put(player.getUniqueId(), new ActiveScoreboardState(scoreboardId, usePapi));
                    publishState(player, scoreboardId);
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
        } else {
            for (Player player : affected) {
                publishState(player, null);
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
            publishState(player, id);
        }
    }

    public static void clearTrackedPlayer(Player player) {
        if (player == null) {
            return;
        }
        ACTIVE_SCOREBOARDS.remove(player.getUniqueId());
        PACKET_SCOREBOARDS.remove(player.getUniqueId());
        publishState(player, null);
    }

    public static void sendActiveState(Player player, Session session) {
        if (player == null || session == null) {
            return;
        }
        sendState(session, findActiveScoreboardId(player));
    }

    private static void applyTemplateToAll(ScoreboardDefinition definition, boolean usePapi) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyTemplate(player, definition, usePapi);
            ACTIVE_SCOREBOARDS.put(player.getUniqueId(), new ActiveScoreboardState(definition.getId(), usePapi));
            publishState(player, definition.getId());
        }
    }

    static boolean hasAnimatedTemplates(FlowStorage storage) {
        if (storage == null) {
            return false;
        }
        for (ActiveScoreboardState state : ACTIVE_SCOREBOARDS.values()) {
            if (state != null && hasAnimation(storage.getScoreboard(state.scoreboardId()))) {
                return true;
            }
        }
        String defaultId = storage.getDefaultScoreboardId();
        return defaultId != null && !defaultId.isBlank() && hasAnimation(storage.getScoreboard(defaultId));
    }

    private static void applyTemplate(Player player, ScoreboardDefinition definition, boolean usePapi) {
        if (!packetsAvailable()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PacketScoreboardState board = PACKET_SCOREBOARDS.get(playerId);
        Component title = TextFormatter.parseResolved(ReSyncPlaceholderUtil.apply(player, definition.getTitle(), true));
        List<String> sourceLines = definition.getLines() != null ? definition.getLines() : List.of();
        int lineCount = Math.min(sourceLines.size(), LINE_ENTRIES.length);
        if (board == null) {
            board = new PacketScoreboardState(objectiveId(player), 0);
            for (int index = 0; index < LINE_ENTRIES.length; index++) {
                send(player, new WrapperPlayServerTeams(teamId(board, index), WrapperPlayServerTeams.TeamMode.REMOVE, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null));
                send(player, new WrapperPlayServerResetScore(LINE_ENTRIES[index], board.objectiveId()));
            }
            send(player, new WrapperPlayServerScoreboardObjective(board.objectiveId(), WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, Component.empty(),
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
            send(player, new WrapperPlayServerScoreboardObjective(board.objectiveId(), WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, title,
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER, BlankScoreFormat.INSTANCE));
            send(player, new WrapperPlayServerDisplayScoreboard(1, board.objectiveId()));
        } else {
            send(player, new WrapperPlayServerScoreboardObjective(board.objectiveId(), WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE, title,
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER, BlankScoreFormat.INSTANCE));
        }
        for (int index = 0; index < lineCount; index++) {
            String entry = LINE_ENTRIES[index];
            Component line = TextFormatter.parseResolved(ReSyncPlaceholderUtil.apply(player, sourceLines.get(index), true));
            send(player, new WrapperPlayServerUpdateScore(entry, WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM, board.objectiveId(), lineCount - index,
                null, BlankScoreFormat.INSTANCE));
            send(player, teamPacket(board, index, index < board.lineCount() ? WrapperPlayServerTeams.TeamMode.UPDATE : WrapperPlayServerTeams.TeamMode.CREATE, line, entry));
        }
        for (int index = lineCount; index < board.lineCount(); index++) {
            send(player, new WrapperPlayServerTeams(teamId(board, index), WrapperPlayServerTeams.TeamMode.REMOVE, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null));
            send(player, new WrapperPlayServerResetScore(LINE_ENTRIES[index], board.objectiveId()));
        }
        PACKET_SCOREBOARDS.put(playerId, new PacketScoreboardState(board.objectiveId(), lineCount));
    }

    private static WrapperPlayServerTeams teamPacket(PacketScoreboardState board, int index, WrapperPlayServerTeams.TeamMode mode, Component line, String entry) {
        WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo = new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.empty(), line, Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS, WrapperPlayServerTeams.CollisionRule.ALWAYS, NamedTextColor.WHITE, WrapperPlayServerTeams.OptionData.NONE);
        return new WrapperPlayServerTeams(teamId(board, index), mode, teamInfo, mode == WrapperPlayServerTeams.TeamMode.CREATE ? List.of(entry) : List.of());
    }

    private static void removePacketScoreboard(Player player) {
        PacketScoreboardState board = PACKET_SCOREBOARDS.remove(player.getUniqueId());
        if (board == null || !packetsAvailable()) {
            return;
        }
        for (int index = 0; index < board.lineCount(); index++) {
            send(player, new WrapperPlayServerTeams(teamId(board, index), WrapperPlayServerTeams.TeamMode.REMOVE, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null));
            send(player, new WrapperPlayServerResetScore(LINE_ENTRIES[index], board.objectiveId()));
        }
        send(player, new WrapperPlayServerScoreboardObjective(board.objectiveId(), WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, Component.empty(),
            WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
    }

    private static boolean packetsAvailable() {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        return api != null && api.isInitialized();
    }

    private static void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private static String objectiveId(Player player) {
        return "rs" + player.getUniqueId().toString().replace("-", "").substring(0, 14);
    }

    private static String teamId(PacketScoreboardState board, int index) {
        return board.objectiveId() + ':' + index;
    }

    private static boolean hasAnimation(ScoreboardDefinition definition) {
        if (definition == null) {
            return false;
        }
        if (hasAnimation(definition.getTitle())) {
            return true;
        }
        return definition.getLines() != null && definition.getLines().stream().anyMatch(ScoreboardTemplateManager::hasAnimation);
    }

    private static boolean hasAnimation(String value) {
        return value != null && ANIMATION_PATTERN.matcher(value).find();
    }

    private static FlowStorage getFlowStorage() {
        return FlowRuntimeAccess.getStorage();
    }

    private static String findActiveScoreboardId(Player player) {
        ActiveScoreboardState state = ACTIVE_SCOREBOARDS.get(player.getUniqueId());
        return state != null ? state.scoreboardId() : null;
    }

    private static void publishState(Player player, String scoreboardId) {
        PlayerSessionLinkService links = sessionLinkService;
        if (player == null || links == null) {
            return;
        }
        sendState(links.getLinkedSession(player.getUniqueId()), scoreboardId);
    }

    private static void sendState(Session session, String scoreboardId) {
        EditTargetStateSender sender = editTargetStateSender;
        if (sender == null || session == null) {
            return;
        }
        sender.send(session, scoreboardId != null && !scoreboardId.isBlank(), "scoreboard", scoreboardId, null);
    }

    @FunctionalInterface
    public interface EditTargetStateSender {
        void send(Session session, boolean editable, String type, String resourceId, String flowId);
    }

    private record ActiveScoreboardState(String scoreboardId, boolean usePapi) {
    }

    private record PacketScoreboardState(String objectiveId, int lineCount) {
    }
}
