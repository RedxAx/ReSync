package restudio.resync.flow;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.GuiElement;
import restudio.flow.data.Visual;
import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.modules.FlowModule;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.server.ReSyncServer;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;

public class GuiManager implements Listener {
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    private static final Set<GuiManager> INSTANCES = new CopyOnWriteArraySet<>();
    private final ReSyncServer server;
    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final FlowModule flowModule;
    private final PlayerSessionLinkService sessionLinkService;
    private final Map<UUID, GuiDefinition> openGuis = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> savedPlayerInventories = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> updateTasks = new ConcurrentHashMap<>();
    private final Map<UUID, String> updateTaskIds = new ConcurrentHashMap<>();
    private final Set<UUID> activeUpdates = ConcurrentHashMap.newKeySet();
    private final Set<UUID> refreshingPlayers = ConcurrentHashMap.newKeySet();

    public GuiManager(ReSyncServer server, FlowStorage storage, FlowExecutor executor, FlowModule flowModule) {
        this.server = server;
        this.storage = storage;
        this.executor = executor;
        this.flowModule = flowModule;
        this.sessionLinkService = server.getPlayerSessionLinkService();
        INSTANCES.add(this);
    }

    public static void refreshOpenGuis(GuiDefinition gui) {
        if (gui == null || gui.getId() == null || gui.getId().isBlank()) {
            return;
        }
        Runnable task = () -> {
            for (GuiManager manager : INSTANCES) {
                manager.refreshOpenGui(gui);
            }
        };
        if (FlowRuntimeAccess.getPlugin() != null) {
            Bukkit.getScheduler().runTask(FlowRuntimeAccess.getPlugin(), task);
        } else {
            task.run();
        }
    }

    public static GuiManager activeManager() {
        return INSTANCES.stream().findFirst().orElse(null);
    }

    public String getOpenGuiId(Player player) {
        GuiDefinition definition = player != null ? openGuis.get(player.getUniqueId()) : null;
        return definition != null ? definition.getId() : null;
    }

    public void closeGui(Player player) {
        if (player != null) {
            player.closeInventory();
        }
    }

    public void refreshPlayerGui(Player player, String guiId) {
        if (player == null || guiId == null || !guiId.equals(getOpenGuiId(player))) {
            return;
        }
        GuiDefinition definition = storage.getGui(guiId);
        if (definition != null) {
            openGui(player, definition, false);
        }
    }

    public void openGui(Player player, String guiId) {
        GuiDefinition def = storage.getGui(guiId);
        if (def != null) {
            if (def.getId() == null || def.getId().isBlank()) {
                def.setId(guiId);
            }
            openGui(player, def);
        }
    }

    public void openGui(Player player, GuiDefinition def) {
        openGui(player, def, true);
    }

    private void openGui(Player player, GuiDefinition def, boolean runOpenLifecycle) {
        if (player == null || def == null || !def.isEnabled()) {
            return;
        }
        RemotelyHolder holder = new RemotelyHolder(def, player);
        String title = def.getTitle() != null && !def.getTitle().isBlank() ? def.getTitle() : def.getId();
        int topSize = Math.clamp(def.getRows(), 1, 6) * 9;
        boolean extendInventory = def.isExtendToPlayerInventory();
        Inventory inv = Bukkit.createInventory(holder, topSize, TextFormatter.parse(title));

        if (extendInventory) {
            savePlayerInventory(player);
            clearPlayerInventory(player);
        } else {
            restorePlayerInventory(player);
        }

        for (GuiElement el : elements(def)) {
            ItemStack item = createItemStack(el.getVisual());
            if (item != null) {
                for (int slot : slots(el)) {
                    if (slot < 0) {
                        continue;
                    }
                    if (slot < topSize) {
                        inv.setItem(slot, item);
                    } else if (extendInventory) {
                        setPlayerInventorySlot(player, slot, topSize, item);
                    }
                }
            }
        }

        openGuis.put(player.getUniqueId(), def);
        boolean replacingManagedGui = player.getOpenInventory() != null
            && player.getOpenInventory().getTopInventory().getHolder() instanceof RemotelyHolder;
        if (!runOpenLifecycle && replacingManagedGui) {
            refreshingPlayers.add(player.getUniqueId());
        }
        player.openInventory(inv);
        if (runOpenLifecycle) {
            executeGuiFlow(def.getOpenFlowId(), player, null, Map.of("event.gui_id", guiId(def), "event.type", "gui_open"));
        }
        scheduleUpdates(player, def);

        String flowId = findFlowIdForGui(def);
        Session session = sessionLinkService.getLinkedSession(player.getUniqueId());
        if (session == null) {
            linkPlayerToSession(player);
            session = sessionLinkService.getLinkedSession(player.getUniqueId());
        }
        if (session != null) {
            flowModule.sendGuiState(session, true, def.getId(), flowId);
        }
    }

    public void sendOpenGuiState(Player player, Session session) {
        if (player == null || session == null) {
            return;
        }
        GuiDefinition def = openGuis.get(player.getUniqueId());
        if (def == null) {
            var view = player.getOpenInventory();
            if (view != null && view.getTopInventory().getHolder() instanceof RemotelyHolder holder) {
                def = holder.getGuiDefinition();
                if (def != null) {
                    openGuis.put(player.getUniqueId(), def);
                }
            }
        }
        if (def != null) {
            flowModule.sendGuiState(session, true, def.getId(), findFlowIdForGui(def));
        }
    }

    public void refreshOpenGui(GuiDefinition gui) {
        if (gui == null || gui.getId() == null || gui.getId().isBlank()) {
            return;
        }
        List<UUID> players = new ArrayList<>();
        for (Map.Entry<UUID, GuiDefinition> entry : openGuis.entrySet()) {
            GuiDefinition open = entry.getValue();
            if (open != null && gui.getId().equals(open.getId())) {
                players.add(entry.getKey());
            }
        }
        for (UUID playerId : players) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                openGuis.remove(playerId);
                continue;
            }
            var view = player.getOpenInventory();
            if (view == null || !(view.getTopInventory().getHolder() instanceof RemotelyHolder holder)) {
                openGuis.remove(playerId);
                continue;
            }
            GuiDefinition open = holder.getGuiDefinition();
            if (open != null && gui.getId().equals(open.getId())) {
                if (gui.isEnabled()) {
                    openGui(player, gui, false);
                } else {
                    closeGui(player);
                }
            }
        }
    }

    public static void refreshSessionGui(Session session, GuiDefinition gui) {
        if (session == null || gui == null || gui.getId() == null || gui.getId().isBlank()) {
            return;
        }
        Runnable task = () -> {
            for (GuiManager manager : INSTANCES) {
                UUID playerId = manager.sessionLinkService.getLinkedPlayer(session);
                Player player = playerId != null ? Bukkit.getPlayer(playerId) : null;
                if (player != null && player.isOnline()) {
                    manager.openGui(player, gui, false);
                }
            }
        };
        if (FlowRuntimeAccess.getPlugin() != null) {
            Bukkit.getScheduler().runTask(FlowRuntimeAccess.getPlugin(), task);
            Bukkit.getScheduler().runTaskLater(FlowRuntimeAccess.getPlugin(), task, 1L);
        } else {
            task.run();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        linkPlayerToSession(event.getPlayer());
    }

    public static ItemStack createItemStack(Visual visual) {
        if (visual == null) return null;

        Material material = Material.STONE;
        if (visual.getHeadTexture() != null && !visual.getHeadTexture().isBlank()) {
            material = Material.PLAYER_HEAD;
        }
        if (visual.getMaterial() != null) {
            try {
                material = Material.valueOf(visual.getMaterial().toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown GUI item material: " + visual.getMaterial(), exception);
            }
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            if (visual.getName() != null) {
                meta.displayName(TextFormatter.parseItemName(visual.getName()));
            }

            if (visual.getLore() != null && !visual.getLore().isEmpty()) {
                List<Component> loreLines = new ArrayList<>();
                for (String line : visual.getLore()) {
                    loreLines.add(TextFormatter.parseItemLore(line));
                }
                meta.lore(loreLines);
            }

            if (visual.getModelData() != null) {
                meta.setCustomModelData(visual.getModelData());
            }

            if (meta instanceof SkullMeta skullMeta && visual.getHeadTexture() != null && !visual.getHeadTexture().isBlank()) {
                try {
                    skullMeta.setOwnerProfile(Bukkit.createProfile(UUID.fromString(visual.getHeadTexture())));
                } catch (IllegalArgumentException exception) {
                    skullMeta.setOwnerProfile(Bukkit.createProfile(visual.getHeadTexture()));
                }
            }

            for (String flag : visual.getItemFlags() != null ? visual.getItemFlags() : List.<String>of()) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase()));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Unknown GUI item flag: " + flag, exception);
                }
            }

            item.setItemMeta(meta);
        }

        if (visual.isEnchanted()) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
        }

        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof RemotelyHolder holder)) return;

        GuiDefinition def = holder.getGuiDefinition();

        event.setCancelled(true);
        int slot = event.getRawSlot();
        int topSize = Math.clamp(def.getRows(), 1, 6) * 9;
        int maxSlot = topSize + (def.isExtendToPlayerInventory() ? PLAYER_INVENTORY_SLOTS : 0);
        if (slot < 0 || slot >= maxSlot) {
            return;
        }

        for (GuiElement el : elements(def)) {
            if (slots(el).contains(slot)) {
                playClickSound(player, def.getClickSound());
                String openGuiId = el.getOpenGuiId();
                if (openGuiId != null && !openGuiId.isBlank()) {
                    if (storage.getGui(openGuiId) != null) {
                        openGui(player, openGuiId);
                    } else {
                        player.sendMessage("GUI not found: " + openGuiId);
                    }
                    break;
                }
                String command = el.getCommand();
                if (command != null && !command.isBlank()) {
                    String preparedCommand = command.trim();
                    if (preparedCommand.startsWith("/")) {
                        preparedCommand = preparedCommand.substring(1);
                    }
                    if (!preparedCommand.isBlank()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), preparedCommand);
                    }
                    break;
                }
                if (el.getAction() != null && !el.getAction().isEmpty()) {
                    observeFlowExecution("GUI function", FunctionCallSupport.execute(storage, executor, el.getAction(), player, event,
                        createClickEventVariables(event, player)));
                    break;
                }
                String flowId = el.getFlowId();
                if (flowId != null) {
                    FlowGraph graph = storage.getGraph(flowId);
                    if (graph != null) {
                        observeFlowExecution(flowId,
                            executor.execute(graph, findStartNode(graph), player, event, createClickEventVariables(event, player)));
                    } else {
                        player.sendMessage("Flow not found: " + flowId);
                    }
                }
                break;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof RemotelyHolder holder)) {
            return;
        }
        GuiDefinition def = holder.getGuiDefinition();
        UUID playerId = event.getPlayer().getUniqueId();
        if (refreshingPlayers.remove(playerId)) {
            return;
        }
        boolean removed = openGuis.remove(event.getPlayer().getUniqueId(), def);
        cancelUpdates(playerId);
        if (removed && def != null && def.isExtendToPlayerInventory() && event.getPlayer() instanceof Player player) {
            restorePlayerInventory(player);
        }
        if (removed && def != null && event.getPlayer() instanceof Player player) {
            executeGuiFlow(def.getCloseFlowId(), player, event, Map.of("event.gui_id", guiId(def), "event.type", "gui_close"));
        }
        Session session = sessionLinkService.getLinkedSession(event.getPlayer().getUniqueId());
        if (session != null) {
            flowModule.sendGuiState(session, false, def != null ? def.getId() : null, null);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        openGuis.remove(event.getPlayer().getUniqueId());
        cancelUpdates(event.getPlayer().getUniqueId());
        restorePlayerInventory(event.getPlayer());
        sessionLinkService.unlinkPlayer(event.getPlayer().getUniqueId());
    }

    private String findStartNode(FlowGraph graph) {
        for (var entry : graph.getNodes().entrySet()) {
            if ("event.click".equals(entry.getValue().getType()) || "event:click".equals(entry.getValue().getType()) || "start".equals(entry.getValue().getType())) {
                return entry.getKey();
            }
        }
        return graph.getNodes().keySet().stream().findFirst().orElse(null);
    }

    private Map<String, Object> createClickEventVariables(InventoryClickEvent event, Player player) {
        Map<String, Object> eventVars = new HashMap<>();
        eventVars.put("event.player", player);
        eventVars.put("event.slot", event.getSlot());
        eventVars.put("event.raw_slot", event.getRawSlot());
        eventVars.put("event.button", event.getHotbarButton());
        eventVars.put("event.action", event.getAction().name());
        eventVars.put("event.item", event.getCurrentItem());
        eventVars.put("event.cursor_item", event.getCursor());
        eventVars.put("player", player);
        eventVars.put("clickedItem", event.getCurrentItem());
        eventVars.put("item", event.getCurrentItem());
        eventVars.put("slot", event.getSlot());
        return eventVars;
    }

    private String findFlowIdForGui(GuiDefinition def) {
        for (GuiElement el : elements(def)) {
            if (el.getFlowId() != null) {
                return el.getFlowId();
            }
        }
        return null;
    }

    public void linkPlayerToSession(Player player) {
        if (server.getSessionManager().getSessionCount() > 0) {
            Session firstSession = server.getSessionManager().getSessions().iterator().next();
            sessionLinkService.link(player.getUniqueId(), firstSession);
        }
    }

    public void shutdown() {
        for (UUID playerId : List.copyOf(updateTasks.keySet())) {
            cancelUpdates(playerId);
        }
        for (UUID playerId : List.copyOf(savedPlayerInventories.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                restorePlayerInventory(player);
            }
        }
        openGuis.clear();
        activeUpdates.clear();
        refreshingPlayers.clear();
        INSTANCES.remove(this);
    }

    private void scheduleUpdates(Player player, GuiDefinition definition) {
        UUID playerId = player.getUniqueId();
        cancelUpdates(playerId);
        int interval = definition.getUpdateIntervalTicks();
        String flowId = definition.getUpdateFlowId();
        if (interval <= 0 || flowId == null || flowId.isBlank() || FlowRuntimeAccess.getPlugin() == null) {
            return;
        }
        int normalizedInterval = Math.max(1, interval);
        String taskId = "gui_update_" + playerId;
        String resourceId = guiId(definition);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(FlowRuntimeAccess.getPlugin(),
            () -> runScheduledUpdate(playerId, resourceId, flowId), normalizedInterval, normalizedInterval);
        updateTasks.put(playerId, task);
        updateTaskIds.put(playerId, taskId);
        executor.registerPendingTask(taskId, flowId, task);
    }

    private void runScheduledUpdate(UUID playerId, String guiId, String flowId) {
        if (!activeUpdates.add(playerId)) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        GuiDefinition latest = storage.getGui(guiId);
        FlowGraph graph = storage.getGraph(flowId);
        if (player == null || !player.isOnline() || latest == null || graph == null || !guiId.equals(getOpenGuiId(player))) {
            activeUpdates.remove(playerId);
            cancelUpdates(playerId);
            return;
        }
        executor.execute(graph, findStartNode(graph), player, null, Map.of(
            "event.gui_id", guiId,
            "event.type", "gui_update",
            "event.update_flow_id", flowId
        )).whenComplete((ignored, failure) -> runOnMainThread(() -> {
            activeUpdates.remove(playerId);
            if (failure != null) {
                Log.warn("GUI Flow failed for " + flowId + ": " + flowFailureMessage(failure));
            }
            if (failure == null && guiId.equals(getOpenGuiId(player))) {
                GuiDefinition refreshed = storage.getGui(guiId);
                if (refreshed != null) {
                    openGui(player, refreshed, false);
                }
            }
        }));
    }

    private void cancelUpdates(UUID playerId) {
        BukkitTask task = updateTasks.remove(playerId);
        String taskId = updateTaskIds.remove(playerId);
        boolean cancelled = taskId != null && executor.cancelPendingTask(taskId);
        if (!cancelled && task != null && !task.isCancelled()) {
            task.cancel();
        }
        activeUpdates.remove(playerId);
    }

    private void executeGuiFlow(String flowId, Player player, Event event, Map<String, Object> eventVariables) {
        if (flowId == null || flowId.isBlank() || player == null) {
            return;
        }
        FlowGraph graph = storage.getGraph(flowId);
        if (graph != null) {
            observeFlowExecution(flowId, executor.execute(graph, findStartNode(graph), player, event, eventVariables));
        }
    }

    private static void observeFlowExecution(String flowId, CompletableFuture<?> execution) {
        execution.whenComplete((ignored, failure) -> {
            if (failure != null) {
                Log.warn("GUI Flow failed for " + flowId + ": " + flowFailureMessage(failure));
            }
        });
    }

    private static String flowFailureMessage(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String summary = cause.getMessage() != null && !cause.getMessage().isBlank() ? cause.getMessage() : cause.getClass().getSimpleName();
        Throwable detail = cause;
        while (detail.getCause() != null && detail.getCause() != detail) {
            detail = detail.getCause();
        }
        String detailMessage = detail.getMessage();
        return detail != cause && detailMessage != null && !detailMessage.isBlank() && !summary.contains(detailMessage)
            ? summary + ": " + detailMessage
            : summary;
    }

    private void runOnMainThread(Runnable action) {
        if (Bukkit.isPrimaryThread() || FlowRuntimeAccess.getPlugin() == null) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(FlowRuntimeAccess.getPlugin(), action);
        }
    }

    private static void playClickSound(Player player, String soundId) {
        if (player == null || soundId == null || soundId.isBlank()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), Sound.valueOf(soundId.toUpperCase()), 1.0f, 1.0f);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown GUI sound: " + soundId, exception);
        }
    }

    private static List<GuiElement> elements(GuiDefinition definition) {
        return definition != null && definition.getElements() != null
            ? definition.getElements().stream().filter(element -> element != null).toList()
            : List.of();
    }

    private static List<Integer> slots(GuiElement element) {
        return element != null && element.getSlots() != null ? element.getSlots() : List.of();
    }

    private static String guiId(GuiDefinition definition) {
        return definition != null && definition.getId() != null ? definition.getId() : "";
    }

    private void savePlayerInventory(Player player) {
        savedPlayerInventories.computeIfAbsent(player.getUniqueId(), id -> cloneInventory(player.getInventory().getStorageContents()));
    }

    private void clearPlayerInventory(Player player) {
        player.getInventory().setStorageContents(new ItemStack[PLAYER_INVENTORY_SLOTS]);
        player.updateInventory();
    }

    private void restorePlayerInventory(Player player) {
        ItemStack[] saved = savedPlayerInventories.remove(player.getUniqueId());
        if (saved != null) {
            player.getInventory().setStorageContents(saved);
            player.updateInventory();
        }
    }

    private ItemStack[] cloneInventory(ItemStack[] contents) {
        if (contents == null) {
            return new ItemStack[PLAYER_INVENTORY_SLOTS];
        }
        ItemStack[] copy = Arrays.copyOf(contents, PLAYER_INVENTORY_SLOTS);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] != null) {
                copy[i] = copy[i].clone();
            }
        }
        return copy;
    }

    private void setPlayerInventorySlot(Player player, int rawSlot, int topSize, ItemStack item) {
        int offset = rawSlot - topSize;
        if (offset < 0 || offset >= PLAYER_INVENTORY_SLOTS) {
            return;
        }
        int targetSlot = offset >= 27 ? offset - 27 : offset + 9;
        player.getInventory().setItem(targetSlot, item);
    }
}
