package restudio.resync.flow;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.TabDefinition;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class TabListService {
    private static final Pattern ANIMATION_PATTERN = Pattern.compile("%resync_animation[:_][^%]+%", Pattern.CASE_INSENSITIVE);
    private static final Map<UUID, ActiveTabState> ACTIVE_TABS = new ConcurrentHashMap<>();
    private static BukkitTask updaterTask;
    private static long updaterTick;

    private TabListService() {
    }

    public static synchronized void startUpdater() {
        stopUpdater();
        FlowStorage storage = getFlowStorage();
        if (storage == null || FlowRuntimeAccess.getPlugin() == null) {
            return;
        }
        updaterTick = 0L;
        updaterTask = Bukkit.getScheduler().runTaskTimer(FlowRuntimeAccess.getPlugin(), TabListService::refreshActive, 1L, 1L);
    }

    public static synchronized void stopUpdater() {
        if (updaterTask != null) {
            updaterTask.cancel();
            updaterTask = null;
        }
    }

    public static int getRefreshIntervalTicks() {
        FlowStorage storage = getFlowStorage();
        return storage != null ? Math.max(1, storage.getTabRefreshIntervalTicks()) : 20;
    }

    public static boolean setRefreshIntervalTicks(int ticks) {
        FlowStorage storage = getFlowStorage();
        if (storage == null) {
            return false;
        }
        storage.setTabRefreshIntervalTicks(Math.max(1, ticks));
        startUpdater();
        return true;
    }

    public static boolean applyTemplate(Player viewer, TabDefinition definition, boolean usePapi) {
        if (viewer == null || definition == null || !definition.isEnabled()) {
            return false;
        }
        String tabId = definition.getId() != null ? definition.getId() : "main";
        applyViewerHeaderFooter(viewer, definition, usePapi);
        ACTIVE_TABS.put(viewer.getUniqueId(), new ActiveTabState(tabId, usePapi));
        applyEntryFormat(viewer, definition.getEntryFormat(), usePapi);
        return true;
    }

    public static void applyTemplateToAll(TabDefinition definition, boolean usePapi) {
        if (definition == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyViewerHeaderFooter(player, definition, usePapi);
            ACTIVE_TABS.put(player.getUniqueId(), new ActiveTabState(definition.getId() != null ? definition.getId() : "main", usePapi));
            applyEntryFormat(player, definition.getEntryFormat(), usePapi);
        }
    }

    public static void clearForPlayer(Player player) {
        if (player == null) {
            return;
        }
        send(player, new WrapperPlayServerPlayerListHeaderAndFooter(Component.empty(), Component.empty()));
        for (Player target : Bukkit.getOnlinePlayers()) {
            sendEntryName(player, target, null);
        }
        ACTIVE_TABS.remove(player.getUniqueId());
    }

    public static void clearTrackedPlayer(Player player) {
        if (player == null) {
            return;
        }
        ACTIVE_TABS.remove(player.getUniqueId());
    }

    public static void resetEntryNames() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                sendEntryName(viewer, target, null);
            }
        }
    }

    public static void refreshActiveTabs(FlowStorage storage, String tabId) {
        if (storage == null || tabId == null || tabId.isBlank()) {
            return;
        }
        TabDefinition definition = storage.getTab(tabId);
        if (definition == null) {
            return;
        }
        if (!definition.isEnabled()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                ActiveTabState state = ACTIVE_TABS.get(player.getUniqueId());
                if (state != null && tabId.equalsIgnoreCase(state.tabId())) clearForPlayer(player);
            }
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ActiveTabState state = ACTIVE_TABS.get(player.getUniqueId());
            if (state != null && tabId.equalsIgnoreCase(state.tabId())) {
                applyViewerHeaderFooter(player, definition, state.usePapi());
                applyEntryFormat(player, definition.getEntryFormat(), state.usePapi());
            }
        }
        String defaultTabId = storage.getDefaultTabId();
        if (defaultTabId != null && defaultTabId.equalsIgnoreCase(tabId)) {
            boolean usePapi = storage.isDefaultTabUsePapi();
            for (Player player : Bukkit.getOnlinePlayers()) {
                ActiveTabState state = ACTIVE_TABS.get(player.getUniqueId());
                if (state == null || defaultTabId.equalsIgnoreCase(state.tabId())) {
                    applyViewerHeaderFooter(player, definition, usePapi);
                    ACTIVE_TABS.put(player.getUniqueId(), new ActiveTabState(defaultTabId, usePapi));
                    applyEntryFormat(player, definition.getEntryFormat(), usePapi);
                }
            }
        }
    }

    public static void clearActiveTabReferences(String tabId, boolean clearPlayers) {
        if (tabId == null || tabId.isBlank()) {
            return;
        }
        List<Player> affected = new ArrayList<>();
        for (Map.Entry<UUID, ActiveTabState> entry : ACTIVE_TABS.entrySet()) {
            ActiveTabState state = entry.getValue();
            if (state != null && tabId.equalsIgnoreCase(state.tabId())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    affected.add(player);
                }
                ACTIVE_TABS.remove(entry.getKey());
            }
        }
        if (clearPlayers) {
            for (Player player : affected) {
                clearForPlayer(player);
            }
        }
    }

    public static boolean setDefaultTab(String tabId, boolean usePapi) {
        FlowStorage storage = getFlowStorage();
        if (storage == null || tabId == null || tabId.isBlank()) {
            return false;
        }
        TabDefinition definition = storage.getTab(tabId);
        if (definition == null) {
            return false;
        }
        storage.setDefaultTab(tabId, usePapi);
        applyTemplateToAll(definition, usePapi);
        return true;
    }

    public static boolean clearDefaultTab() {
        FlowStorage storage = getFlowStorage();
        if (storage == null) {
            return false;
        }
        storage.clearDefaultTab();
        return true;
    }

    public static String getDefaultTabId() {
        FlowStorage storage = getFlowStorage();
        return storage != null ? storage.getDefaultTabId() : null;
    }

    public static boolean isDefaultTabUsePapi() {
        FlowStorage storage = getFlowStorage();
        return storage != null && storage.isDefaultTabUsePapi();
    }

    public static void applyDefaultOnJoin(Player player) {
        if (player == null) {
            return;
        }
        FlowStorage storage = getFlowStorage();
        if (storage == null) {
            return;
        }
        String tabId = storage.getDefaultTabId();
        if (tabId == null || tabId.isBlank()) {
            return;
        }
        TabDefinition definition = storage.getTab(tabId);
        if (definition != null) {
            applyTemplate(player, definition, storage.isDefaultTabUsePapi());
        }
    }

    private static void refreshActive() {
        FlowStorage storage = getFlowStorage();
        if (storage == null) {
            return;
        }
        long tick = ++updaterTick;
        boolean regularRefresh = Math.floorMod(tick, Math.max(1, storage.getTabRefreshIntervalTicks())) == 0;
        if (regularRefresh || hasAnimatedTabs(storage)) {
            String defaultTabId = storage.getDefaultTabId();
            if (defaultTabId != null && !defaultTabId.isBlank()) {
                TabDefinition defaultDefinition = storage.getTab(defaultTabId);
                if (defaultDefinition != null) {
                    applyTemplateToAll(defaultDefinition, storage.isDefaultTabUsePapi());
                }
            } else if (!ACTIVE_TABS.isEmpty()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    ActiveTabState state = ACTIVE_TABS.get(player.getUniqueId());
                    if (state == null) {
                        continue;
                    }
                    TabDefinition definition = storage.getTab(state.tabId());
                    if (definition != null) {
                        applyViewerHeaderFooter(player, definition, state.usePapi());
                        applyEntryFormat(player, definition.getEntryFormat(), state.usePapi());
                    }
                }
            }
        }
        if (regularRefresh || ScoreboardTemplateManager.hasAnimatedTemplates(storage)) {
            ScoreboardTemplateManager.refreshActiveTemplates(storage);
        }
    }

    private static boolean hasAnimatedTabs(FlowStorage storage) {
        for (ActiveTabState state : ACTIVE_TABS.values()) {
            if (state != null && hasAnimation(storage.getTab(state.tabId()))) {
                return true;
            }
        }
        String defaultId = storage.getDefaultTabId();
        return defaultId != null && !defaultId.isBlank() && hasAnimation(storage.getTab(defaultId));
    }

    private static boolean hasAnimation(TabDefinition definition) {
        return definition != null && (hasAnimation(definition.getHeader()) || hasAnimation(definition.getFooter()) || hasAnimation(definition.getEntryFormat()));
    }

    private static boolean hasAnimation(String value) {
        return value != null && ANIMATION_PATTERN.matcher(value).find();
    }

    private static FlowStorage getFlowStorage() {
        return FlowRuntimeAccess.getStorage();
    }

    private static void applyViewerHeaderFooter(Player viewer, TabDefinition definition, boolean usePapi) {
        Component header = TextFormatter.parseResolved(ReSyncPlaceholderUtil.apply(viewer, definition.getHeader(), true));
        Component footer = TextFormatter.parseResolved(ReSyncPlaceholderUtil.apply(viewer, definition.getFooter(), true));
        send(viewer, new WrapperPlayServerPlayerListHeaderAndFooter(header, footer));
    }

    private static void applyEntryFormat(Player viewer, String rawFormat, boolean usePapi) {
        String format = rawFormat != null && !rawFormat.isEmpty() ? rawFormat : "%player%";
        for (Player target : Bukkit.getOnlinePlayers()) {
            boolean hasPlayer = format.contains("%player%");
            String rendered = format.replace("%player%", target.getName());
            rendered = ReSyncPlaceholderUtil.apply(target, rendered, true);
            Component displayName = TextFormatter.parseResolved(rendered);
            if (!hasPlayer) {
                displayName = displayName.append(Component.space()).append(Component.text(target.getName()));
            }
            sendEntryName(viewer, target, displayName);
        }
    }

    private static void sendEntryName(Player viewer, Player target, Component displayName) {
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(target.getUniqueId());
        info.setDisplayName(displayName);
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME), info));
    }

    private static void send(Player player, PacketWrapper<?> packet) {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api != null && api.isInitialized()) {
            api.getPlayerManager().sendPacket(player, packet);
        }
    }

    private record ActiveTabState(String tabId, boolean usePapi) {
    }
}
