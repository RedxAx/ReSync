package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.TabDefinition;
import restudio.resync.flow.nodes.ScoreboardNodes;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TabListService {
    private static final Map<UUID, ActiveTabState> ACTIVE_TABS = new ConcurrentHashMap<>();
    private static BukkitTask updaterTask;

    private TabListService() {
    }

    public static synchronized void startUpdater() {
        stopUpdater();
        FlowStorage storage = getFlowStorage();
        if (storage == null || FlowRuntimeAccess.getPlugin() == null) {
            return;
        }
        int interval = Math.max(1, storage.getTabRefreshIntervalTicks());
        updaterTask = Bukkit.getScheduler().runTaskTimer(FlowRuntimeAccess.getPlugin(), TabListService::refreshActive, interval, interval);
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
        if (viewer == null || definition == null) {
            return false;
        }
        String tabId = definition.getId() != null ? definition.getId() : "main";
        applyViewerHeaderFooter(viewer, definition, usePapi);
        ACTIVE_TABS.put(viewer.getUniqueId(), new ActiveTabState(tabId, usePapi));
        applyEntryFormat(definition.getEntryFormat(), usePapi);
        return true;
    }

    public static void applyTemplateToAll(TabDefinition definition, boolean usePapi) {
        if (definition == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyViewerHeaderFooter(player, definition, usePapi);
            ACTIVE_TABS.put(player.getUniqueId(), new ActiveTabState(definition.getId() != null ? definition.getId() : "main", usePapi));
        }
        applyEntryFormat(definition.getEntryFormat(), usePapi);
    }

    public static void clearForPlayer(Player player) {
        if (player == null) {
            return;
        }
        player.setPlayerListHeader("");
        player.setPlayerListFooter("");
        ACTIVE_TABS.remove(player.getUniqueId());
    }

    public static void clearTrackedPlayer(Player player) {
        if (player == null) {
            return;
        }
        ACTIVE_TABS.remove(player.getUniqueId());
    }

    public static void resetEntryNames() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setPlayerListName(player.getName());
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
        for (Player player : Bukkit.getOnlinePlayers()) {
            ActiveTabState state = ACTIVE_TABS.get(player.getUniqueId());
            if (state != null && tabId.equalsIgnoreCase(state.tabId())) {
                applyViewerHeaderFooter(player, definition, state.usePapi());
                applyEntryFormat(definition.getEntryFormat(), state.usePapi());
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
                }
            }
            applyEntryFormat(definition.getEntryFormat(), usePapi);
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
            resetEntryNames();
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
                    applyEntryFormat(definition.getEntryFormat(), state.usePapi());
                }
            }
        }
        ScoreboardNodes.refreshActiveTemplates(storage);
    }

    private static FlowStorage getFlowStorage() {
        return FlowRuntimeAccess.getStorage();
    }

    private static void applyViewerHeaderFooter(Player viewer, TabDefinition definition, boolean usePapi) {
        String header = TextFormatter.formatLegacy(ReSyncPlaceholderUtil.apply(viewer, definition.getHeader(), usePapi));
        String footer = TextFormatter.formatLegacy(ReSyncPlaceholderUtil.apply(viewer, definition.getFooter(), usePapi));
        viewer.setPlayerListHeader(header);
        viewer.setPlayerListFooter(footer);
    }

    private static void applyEntryFormat(String rawFormat, boolean usePapi) {
        String format = rawFormat != null && !rawFormat.isEmpty() ? rawFormat : "%player%";
        for (Player target : Bukkit.getOnlinePlayers()) {
            String rendered = ReSyncPlaceholderUtil.apply(target, format, usePapi);
            if (!rendered.contains("%player%")) {
                rendered = rendered + " %player%";
            }
            rendered = rendered.replace("%player%", target.getName());
            target.setPlayerListName(TextFormatter.formatLegacy(rendered));
        }
    }

    private record ActiveTabState(String tabId, boolean usePapi) {
    }
}
