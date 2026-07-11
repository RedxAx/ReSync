package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import restudio.resync.Log;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.World;
import restudio.flow.data.FlowGraph;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowExecutionListener;
import restudio.resync.player.PlayerDossier;
import restudio.resync.player.PlayerFacetState;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.player.PlayerTrackingPrivacyPolicy;
import restudio.resync.player.PlayerTrackingListener;
import restudio.resync.player.PlayerTrackingService;
import restudio.resync.player.PlayerTrackingUpdate;
import restudio.resync.player.PlayerControlAuthorizer;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTrackingModule implements Module, Listener, PlayerTrackingListener, FlowExecutionListener {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("playerTracking", "PlayerTracking", "player_tracking").withDependencies("flow");
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final Set<Session> legacyBroadSessions = ConcurrentHashMap.newKeySet();
    private final Map<Session, Set<UUID>> watchedPlayers = new ConcurrentHashMap<>();
    private final PlayerControlAuthorizer controlAuthorizer = new PlayerControlAuthorizer();
    private final Gson gson = new Gson();
    private ModuleContext context;
    private Codec codec;
    private int channelId;
    private PlayerTrackingService trackingService;
    private PlayerSessionLinkService sessionLinkService;
    private final PlayerTrackingPrivacyPolicy privacyPolicy = new PlayerTrackingPrivacyPolicy();
    private final Map<UUID, Integer> pendingLiveBroadcastTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLiveBroadcastAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> stateRevisions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> inventoryRevisions = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastInventorySignatures = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastScopedStateSignatures = new ConcurrentHashMap<>();
    private final Set<UUID> pendingInventoryRevisionPlayers = ConcurrentHashMap.newKeySet();
    private int liveStateTaskId = -1;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.context = context;
        this.codec = context.getCodec();
        this.channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        this.trackingService = context.getRequiredService(PlayerTrackingService.class);
        this.sessionLinkService = context.getRequiredService(PlayerSessionLinkService.class);
    }

    @Override
    public void start(ModuleContext context) {
        trackingService.addListener(this);
        Bukkit.getPluginManager().registerEvents(this, context.getPlugin());
        FlowExecutor flowExecutor = context.getService(FlowExecutor.class);
        if (flowExecutor != null) {
            flowExecutor.addExecutionListener(this);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            trackingService.markOnline(player, "bootstrap");
        }
        liveStateTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(context.getPlugin(), this::broadcastLivePlayerData, 20L, 20L);
    }

    @Override
    public void stop(ModuleContext context) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            trackingService.markOffline(player.getUniqueId(), player.getName(), "shutdown");
            sessionLinkService.unlinkPlayer(player.getUniqueId());
        }
        trackingService.removeListener(this);
        FlowExecutor flowExecutor = context.getService(FlowExecutor.class);
        if (flowExecutor != null) {
            flowExecutor.removeExecutionListener(this);
        }
        HandlerList.unregisterAll(this);
        subscribedSessions.clear();
        legacyBroadSessions.clear();
        watchedPlayers.clear();
        if (liveStateTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(liveStateTaskId);
            liveStateTaskId = -1;
        }
        for (Integer taskId : pendingLiveBroadcastTasks.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        pendingLiveBroadcastTasks.clear();
        lastLiveBroadcastAt.clear();
        stateRevisions.clear();
        inventoryRevisions.clear();
        lastInventorySignatures.clear();
        lastScopedStateSignatures.clear();
        pendingInventoryRevisionPlayers.clear();
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        subscribedSessions.add(session);
        boolean scoped = isScopedSubscription(req.getData());
        if (scoped) {
            watchedPlayers.put(session, ConcurrentHashMap.newKeySet());
            sendCapabilities(session);
        } else {
            legacyBroadSessions.add(session);
            sendSnapshot(session);
        }
        handleSubscribeBinding(session, req.getData());
    }

    private boolean isScopedSubscription(String data) {
        if (data == null || data.isBlank()) return false;
        try {
            JsonObject payload = gson.fromJson(data, JsonObject.class);
            return payload != null && payload.has("mode") && "scoped".equalsIgnoreCase(payload.get("mode").getAsString());
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onUnsubscribe(Session session, UnsubscribeRequest req) {
        subscribedSessions.remove(session);
        legacyBroadSessions.remove(session);
        watchedPlayers.remove(session);
    }

    @Override
    public void cleanup(Session session) {
        subscribedSessions.remove(session);
        legacyBroadSessions.remove(session);
        watchedPlayers.remove(session);
        sessionLinkService.unlinkSession(session);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        if (req.getPayload() == null || req.getPayload().length == 0) {
            sendSnapshot(session);
            return;
        }
        try {
            String json = new String(req.getPayload(), StandardCharsets.UTF_8);
            TrackingRequest request = gson.fromJson(json, TrackingRequest.class);
            if (request == null || request.action == null || request.action.isBlank()) {
                sendSnapshot(session);
                return;
            }
            if ("player_control".equalsIgnoreCase(request.type)) {
                handlePlayerControl(session, request);
                return;
            }
            switch (request.action) {
                case "snapshot" -> sendSnapshot(session);
                case "dossier" -> sendDossier(session, request.playerId);
                case "capabilities" -> sendCapabilities(session);
                case "watch" -> watchPlayer(session, request.playerId);
                case "unwatch" -> unwatchPlayer(session, request.playerId);
                case "link" -> linkSession(session, request.playerId);
                case "unlink" -> unlinkSession(session, request.playerId);
                default -> sendSnapshot(session);
            }
        } catch (Exception e) {
            Log.warn("PlayerTracking request failed: " + e.getMessage());
        }
    }

    @Override
    public void onUpdate(PlayerTrackingUpdate update) {
        broadcast(update);
    }

    @Override
    public void onFlowExecution(FlowGraph graph, String startNodeId, Player player, Event event) {
        if (player == null || graph == null) {
            return;
        }
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("flowId", graph.getId());
        data.put("startNodeId", startNodeId);
        data.put("eventType", event != null ? event.getEventName() : null);
        trackingService.recordEvent(player.getUniqueId(), player.getName(), getModuleId(), "flow", "execute", data);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        trackingService.markOnline(player, "bukkit");
        trackingService.recordEvent(player.getUniqueId(), player.getName(), getModuleId(), "state", "join", Map.of());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        trackingService.recordEvent(player.getUniqueId(), player.getName(), getModuleId(), "state", "quit", Map.of());
        trackingService.markOffline(player.getUniqueId(), player.getName(), "bukkit");
        sessionLinkService.unlinkPlayer(player.getUniqueId());
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        trackingService.recordEvent(player.getUniqueId(), player.getName(), getModuleId(), "chat", "message",
            privacyPolicy.sanitizeChat(event.getMessage(), event.getFormat(), context.getConfig().getPlayerTracking().isCaptureChatText()));
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        trackingService.recordEvent(player.getUniqueId(), player.getName(), getModuleId(), "command", "execute",
            privacyPolicy.sanitizeCommand(event.getMessage(), context.getConfig().getPlayerTracking().isCaptureCommandArguments()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleLivePlayerDataBroadcast(player, 1L, true, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleLivePlayerDataBroadcast(player, 1L, true, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scheduleLivePlayerDataBroadcast(player, 1L, true, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        scheduleLivePlayerDataBroadcast(event.getPlayer(), 1L, true, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            scheduleLivePlayerDataBroadcast(player, 1L, true, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        scheduleLivePlayerDataBroadcast(event.getPlayer(), 1L, true, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        scheduleLivePlayerDataBroadcast(event.getPlayer(), 1L, true, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        scheduleLivePlayerDataBroadcast(event.getPlayer(), 1L, true, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            scheduleLivePlayerDataBroadcast(player, 1L, true, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            scheduleLivePlayerDataBroadcast(player, 1L, true, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRegainHealth(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player) {
            scheduleLivePlayerDataBroadcast(player, 1L, true, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        scheduleLivePlayerDataBroadcast(event.getPlayer(), 1L, true, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLevelChange(PlayerLevelChangeEvent event) {
        scheduleLivePlayerDataBroadcast(event.getPlayer(), 1L, true, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ() && from.getYaw() == to.getYaw() && from.getPitch() == to.getPitch()) {
            return;
        }
        scheduleThrottledLivePlayerDataBroadcast(event.getPlayer(), 500L);
    }

    private void handleSubscribeBinding(Session session, String rawData) {
        if (rawData == null || rawData.isBlank()) {
            return;
        }
        try {
            TrackingRequest request = gson.fromJson(rawData, TrackingRequest.class);
            if (request != null && "link".equals(request.action)) {
                linkSession(session, request.playerId);
            }
        } catch (Exception ignored) {
        }
    }

    private void sendSnapshot(Session session) {
        List<PlayerDossier> dossiers = new ArrayList<>();
        for (PlayerDossier dossier : trackingService.getDossiers()) {
            dossiers.add(enrichDossier(dossier));
        }
        send(session, PlayerTrackingUpdate.snapshot(dossiers));
    }

    private void sendDossier(Session session, String playerId) {
        UUID uuid = parsePlayerId(playerId);
        if (uuid == null) {
            sendSnapshot(session);
            return;
        }
        PlayerDossier dossier = trackingService.getDossier(uuid);
        if (dossier != null) {
            send(session, PlayerTrackingUpdate.delta("dossier", enrichDossier(dossier)));
        }
    }

    private void sendCapabilities(Session session) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("type", "player_control_capabilities");
        response.put("version", 2);
        response.put("provider", "resync");
        response.put("sections", List.of("overview", "activity", "history", "inventory", "enderChest", "effects", "extensions"));
        response.put("operations", controlAuthorizer.operations(session));
        sendRaw(session, response);
    }

    private void handlePlayerControl(Session session, TrackingRequest request) {
        if (!controlAuthorizer.allows(session, request.action)) {
            sendControlResponse(session, request, false, "Unauthorized", Map.of());
            return;
        }
        switch (request.action) {
            case "inventoryEdit" -> handleInventoryEdit(session, request);
            case "inventoryEditBatch" -> handleInventoryEdit(session, request);
            case "playerDataSnapshot" -> sendPlayerDataSnapshot(session, request);
            case "inventorySnapshot" -> sendInventorySnapshot(session, request, false);
            case "enderSnapshot" -> sendInventorySnapshot(session, request, true);
            case "gameRulesList" -> sendGameRulesList(session, request);
            case "gameRuleSet" -> setGameRule(session, request);
            case "liveSettingsList" -> sendLiveSettingsList(session, request);
            case "liveSettingSet" -> setLiveSetting(session, request);
            default -> sendControlResponse(session, request, false, "UnsupportedOperation", Map.of());
        }
    }

    private void handleInventoryEdit(Session session, TrackingRequest request) {
        UUID uuid = parsePlayerId(request.playerId);
        List<InventoryEditRequest> edits = inventoryEdits(request);
        if (uuid == null || edits.isEmpty()) {
            sendControlResponse(session, request, false, "InvalidPlayerOrSlot", Map.of());
            return;
        }
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            sendControlResponse(session, request, false, "PlayerOffline", Map.of());
            return;
        }
        Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
            syncInventoryRevisionIfChanged(onlinePlayer);
            long currentInventoryRevision = inventoryRevision(uuid);
            if (request.baseInventoryRevision < 0L || request.baseInventoryRevision != currentInventoryRevision) {
                sendControlResponse(session, request, false, "InventoryChanged", Map.of("playerData", livePlayerData(onlinePlayer)));
                sendLivePlayerData(onlinePlayer, "inventoryChanged");
                return;
            }
            boolean success = true;
            LinkedHashMap<String, ItemStack> replacements = new LinkedHashMap<>();
            LinkedHashMap<String, ItemStack> previous = new LinkedHashMap<>();
            for (InventoryEditRequest edit : edits) {
                if (!isValidInventorySlot(onlinePlayer, edit.slot) || replacements.containsKey(edit.slot)) {
                    success = false;
                    break;
                }
                ItemStack replacement = itemFromRequest(edit);
                if (replacement == null && !requestsAir(edit)) {
                    success = false;
                    break;
                }
                replacements.put(edit.slot, replacement);
                ItemStack current = getInventoryItem(onlinePlayer, edit.slot);
                previous.put(edit.slot, current != null ? current.clone() : null);
            }
            if (success) {
                for (Map.Entry<String, ItemStack> edit : replacements.entrySet()) {
                    if (!applyInventoryEdit(onlinePlayer, edit.getKey(), edit.getValue())) {
                        success = false;
                        break;
                    }
                }
            }
            if (!success) {
                for (Map.Entry<String, ItemStack> entry : previous.entrySet()) applyInventoryEdit(onlinePlayer, entry.getKey(), entry.getValue());
                onlinePlayer.updateInventory();
            }
            if (success) {
                onlinePlayer.updateInventory();
                syncInventoryRevisionIfChanged(onlinePlayer);
                bumpStateRevision(onlinePlayer.getUniqueId());
                LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
                payload.put("playerData", livePlayerData(onlinePlayer));
                sendControlResponse(session, request, true, "InventoryUpdated", payload);
                sendLivePlayerData(onlinePlayer, "inventoryEdit");
            } else {
                sendControlResponse(session, request, false, "InventoryEditFailed", Map.of("playerData", livePlayerData(onlinePlayer)));
                sendLivePlayerData(onlinePlayer, "inventoryRollback");
            }
        });
    }

    private boolean isValidInventorySlot(Player player, String slot) {
        if (player == null || slot == null || slot.isBlank()) {
            return false;
        }
        try {
            if (slot.startsWith("enderchest.")) {
                int index = parseSlot(slot, "enderchest.");
                return index >= 0 && index < player.getEnderChest().getSize();
            }
            if (slot.startsWith("hotbar.")) {
                int index = parseSlot(slot, "hotbar.");
                return index >= 0 && index < 9;
            }
            if (slot.startsWith("inventory.")) {
                int index = parseSlot(slot, "inventory.");
                return index >= 0 && index < 27;
            }
            return switch (slot) {
                case "armor.head", "armor.chest", "armor.legs", "armor.feet", "weapon.offhand" -> true;
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    private List<InventoryEditRequest> inventoryEdits(TrackingRequest request) {
        if (request.edits != null && !request.edits.isEmpty()) {
            return request.edits.stream()
                    .filter(edit -> edit != null && edit.slot != null && !edit.slot.isBlank())
                    .toList();
        }
        if (request.slot == null || request.slot.isBlank()) {
            return List.of();
        }
        InventoryEditRequest edit = new InventoryEditRequest();
        edit.slot = request.slot;
        edit.itemId = request.itemId;
        edit.count = request.count;
        edit.item = request.item;
        return List.of(edit);
    }

    private boolean applyInventoryEdit(Player player, String slot, ItemStack item) {
        try {
            if (slot.startsWith("enderchest.")) {
                player.getEnderChest().setItem(parseSlot(slot, "enderchest."), item);
                return true;
            }
            if (slot.startsWith("hotbar.")) {
                player.getInventory().setItem(parseSlot(slot, "hotbar."), item);
                return true;
            }
            if (slot.startsWith("inventory.")) {
                player.getInventory().setItem(parseSlot(slot, "inventory.") + 9, item);
                return true;
            }
            switch (slot) {
                case "armor.head" -> player.getInventory().setHelmet(item);
                case "armor.chest" -> player.getInventory().setChestplate(item);
                case "armor.legs" -> player.getInventory().setLeggings(item);
                case "armor.feet" -> player.getInventory().setBoots(item);
                case "weapon.offhand" -> player.getInventory().setItemInOffHand(item);
                default -> {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ItemStack getInventoryItem(Player player, String slot) {
        if (slot.startsWith("enderchest.")) return player.getEnderChest().getItem(parseSlot(slot, "enderchest."));
        if (slot.startsWith("hotbar.")) return player.getInventory().getItem(parseSlot(slot, "hotbar."));
        if (slot.startsWith("inventory.")) return player.getInventory().getItem(parseSlot(slot, "inventory.") + 9);
        return switch (slot) {
            case "armor.head" -> player.getInventory().getHelmet();
            case "armor.chest" -> player.getInventory().getChestplate();
            case "armor.legs" -> player.getInventory().getLeggings();
            case "armor.feet" -> player.getInventory().getBoots();
            case "weapon.offhand" -> player.getInventory().getItemInOffHand();
            default -> null;
        };
    }

    private ItemStack itemFromRequest(InventoryEditRequest edit) {
        if (edit.item != null && !edit.item.isEmpty()) {
            try {
                LinkedHashMap<String, Object> serialized = new LinkedHashMap<>(edit.item);
                serialized.remove("encodedItem");
                return ItemStack.deserialize(serialized);
            } catch (Exception ignored) {
            }
        }
        String itemId = edit.itemId == null || edit.itemId.isBlank() ? "minecraft:air" : edit.itemId;
        int count = Math.max(0, edit.count);
        if (count <= 0 || "minecraft:air".equalsIgnoreCase(itemId)) {
            return null;
        }
        Material material = Material.matchMaterial(itemId);
        if (material == null && itemId.startsWith("minecraft:")) {
            material = Material.matchMaterial(itemId.substring("minecraft:".length()));
        }
        return material == null ? null : new ItemStack(material, Math.max(1, count));
    }

    private boolean requestsAir(InventoryEditRequest edit) {
        return edit != null && (edit.count <= 0 || "minecraft:air".equalsIgnoreCase(edit.itemId) || "air".equalsIgnoreCase(edit.itemId));
    }

    private int parseSlot(String slot, String prefix) {
        return Integer.parseInt(slot.substring(prefix.length()));
    }

    private void sendPlayerDataSnapshot(Session session, TrackingRequest request) {
        UUID uuid = parsePlayerId(request.playerId);
        Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
            Player player = uuid != null ? Bukkit.getPlayer(uuid) : null;
            if (player == null || !player.isOnline()) {
                sendControlResponse(session, request, false, "PlayerOffline", Map.of());
                return;
            }
            sendControlResponse(session, request, true, "PlayerData", Map.of("playerData", livePlayerData(player)));
        });
    }

    private void sendInventorySnapshot(Session session, TrackingRequest request, boolean ender) {
        UUID uuid = parsePlayerId(request.playerId);
        Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
            Player player = uuid != null ? Bukkit.getPlayer(uuid) : null;
            if (player == null || !player.isOnline()) {
                sendControlResponse(session, request, false, "PlayerOffline", Map.of());
                return;
            }
            Map<String, Object> data = ender
                    ? Map.of("enderChest", itemList(player.getEnderChest().getContents(), 0))
                    : Map.of("inventory", itemList(player.getInventory().getStorageContents(), 0), "armor", armorList(player), "offhand", itemList(new ItemStack[] { player.getInventory().getItemInOffHand() }, -106));
            sendControlResponse(session, request, true, ender ? "EnderSnapshot" : "InventorySnapshot", data);
        });
    }

    private void sendGameRulesList(Session session, TrackingRequest request) {
        World world = resolveWorld(request.worldName);
        if (world == null) {
            sendControlResponse(session, request, false, "WorldUnavailable", Map.of());
            return;
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String name : world.getGameRules()) {
            Object value = world.getGameRuleValue(name);
            rules.add(Map.of("key", name, "value", value == null ? "" : String.valueOf(value), "type", value instanceof Boolean ? "boolean" : value instanceof Number ? "integer" : "string"));
        }
        sendControlResponse(session, request, true, "GameRules", Map.of("worldName", world.getName(), "rules", rules));
    }

    private void setGameRule(Session session, TrackingRequest request) {
        World world = resolveWorld(request.worldName);
        if (world == null || request.key == null || request.value == null) {
            sendControlResponse(session, request, false, "InvalidGameRule", Map.of());
            return;
        }
        Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
            boolean success = world.setGameRuleValue(request.key, request.value);
            sendControlResponse(session, request, success, success ? "GameRuleUpdated" : "InvalidGameRule", Map.of());
        });
    }

    private void sendLiveSettingsList(Session session, TrackingRequest request) {
        World world = resolveWorld(request.worldName);
        if (world == null) {
            sendControlResponse(session, request, false, "WorldUnavailable", Map.of());
            return;
        }
        List<Map<String, Object>> settings = List.of(
                Map.of("key", "difficulty", "value", world.getDifficulty().name().toLowerCase(), "type", "string"),
                Map.of("key", "time", "value", String.valueOf(world.getTime()), "type", "integer"),
                Map.of("key", "storm", "value", String.valueOf(world.hasStorm()), "type", "boolean"),
                Map.of("key", "thundering", "value", String.valueOf(world.isThundering()), "type", "boolean")
        );
        sendControlResponse(session, request, true, "LiveSettings", Map.of("worldName", world.getName(), "settings", settings));
    }

    private void setLiveSetting(Session session, TrackingRequest request) {
        World world = resolveWorld(request.worldName);
        if (world == null || request.key == null || request.value == null) {
            sendControlResponse(session, request, false, "InvalidLiveSetting", Map.of());
            return;
        }
        Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
            boolean success = true;
            try {
                switch (request.key) {
                    case "time" -> world.setTime(Long.parseLong(request.value));
                    case "storm" -> world.setStorm(Boolean.parseBoolean(request.value));
                    case "thundering" -> world.setThundering(Boolean.parseBoolean(request.value));
                    case "difficulty" -> world.setDifficulty(org.bukkit.Difficulty.valueOf(request.value.toUpperCase()));
                    default -> success = false;
                }
            } catch (Exception e) {
                success = false;
            }
            sendControlResponse(session, request, success, success ? "LiveSettingUpdated" : "InvalidLiveSetting", Map.of());
        });
    }

    private World resolveWorld(String worldName) {
        if (worldName != null && !worldName.isBlank()) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) return world;
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
    }

    private PlayerDossier enrichDossier(PlayerDossier dossier) {
        if (dossier == null || dossier.getPlayerId() == null) {
            return dossier;
        }
        UUID uuid = parsePlayerId(dossier.getPlayerId());
        if (uuid == null) {
            return dossier;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return dossier;
        }
        PlayerDossier copy = dossier.copy();
        PlayerFacetState facet = new PlayerFacetState();
        facet.setFacetId("playerData");
        facet.setModuleId(getModuleId());
        facet.setUpdatedAt(System.currentTimeMillis());
        facet.setData(livePlayerData(player));
        copy.getFacets().put("playerData", facet);
        return copy;
    }

    private Map<String, Object> livePlayerData(Player player) {
        syncInventoryRevisionIfChanged(player);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("health", player.getHealth());
        data.put("food", player.getFoodLevel());
        data.put("saturation", player.getSaturation());
        data.put("experienceLevel", player.getLevel());
        data.put("experienceProgress", player.getExp());
        data.put("totalExperience", player.getTotalExperience());
        data.put("gameMode", player.getGameMode().name().toLowerCase());
        data.put("flying", player.isFlying());
        data.put("fallFlying", player.isGliding());
        data.put("stateRevision", stateRevision(player.getUniqueId()));
        data.put("inventoryRevision", inventoryRevision(player.getUniqueId()));
        data.put("location", locationData(player.getLocation()));
        data.put("inventory", itemList(player.getInventory().getStorageContents(), 0));
        data.put("armor", armorList(player));
        data.put("offhand", itemList(new ItemStack[] { player.getInventory().getItemInOffHand() }, -106));
        data.put("enderChest", itemList(player.getEnderChest().getContents(), 0));
        data.put("effects", player.getActivePotionEffects().stream().map(effect -> {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("id", effect.getType().getKey().toString());
            out.put("amplifier", effect.getAmplifier());
            out.put("duration", effect.getDuration());
            out.put("ambient", effect.isAmbient());
            out.put("showParticles", effect.hasParticles());
            return out;
        }).toList());
        data.put("attributes", attributes(player));
        return data;
    }

    private Map<String, Object> locationData(Location location) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("x", location.getX());
        data.put("y", location.getY());
        data.put("z", location.getZ());
        data.put("yaw", location.getYaw());
        data.put("pitch", location.getPitch());
        data.put("dimension", location.getWorld() != null ? location.getWorld().getName() : "");
        return data;
    }

    private List<Map<String, Object>> armorList(Player player) {
        List<Map<String, Object>> items = new ArrayList<>();
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            Map<String, Object> item = itemData(armor[i], 100 + i);
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private List<Map<String, Object>> itemList(ItemStack[] contents, int slotOffset) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (contents == null) {
            return items;
        }
        for (int i = 0; i < contents.length; i++) {
            int slot = slotOffset == -106 ? -106 : i + slotOffset;
            Map<String, Object> item = itemData(contents[i], slot);
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private Map<String, Object> itemData(ItemStack item, int slot) {
        if (item == null || item.getType().isAir()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("id", item.getType().getKey().toString());
        data.put("count", item.getAmount());
        data.put("slot", slot);
        data.put("tag", new LinkedHashMap<>(item.serialize()));
        return data;
    }

    private List<Map<String, Object>> attributes(Player player) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attribute attribute : Attribute.values()) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("id", attribute.getKey().toString());
            data.put("base", instance.getBaseValue());
            data.put("value", instance.getValue());
            out.add(data);
        }
        return out;
    }

    private void sendControlResult(Session session, String action, boolean success, String reason) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("type", "player_control_result");
        response.put("version", 1);
        response.put("action", action);
        response.put("success", success);
        response.put("reason", reason);
        sendRaw(session, response);
    }

    private void sendControlResponse(Session session, TrackingRequest request, boolean success, String reason, Map<String, Object> payload) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("type", "player_control_response");
        response.put("version", 2);
        response.put("requestId", request.requestId);
        response.put("action", request.action);
        response.put("success", success);
        response.put("reason", reason);
        if (payload != null) {
            response.putAll(payload);
        }
        sendRaw(session, response);
    }

    private void linkSession(Session session, String playerId) {
        UUID uuid = parsePlayerId(playerId);
        if (uuid != null && uuid.equals(sessionLinkService.getLinkedPlayer(session))) {
            sessionLinkService.link(uuid, session);
        }
    }

    private void unlinkSession(Session session, String playerId) {
        UUID uuid = parsePlayerId(playerId);
        if (uuid != null) {
            sessionLinkService.unlinkPlayer(uuid);
        } else {
            sessionLinkService.unlinkSession(session);
        }
    }

    private UUID parsePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void broadcast(PlayerTrackingUpdate update) {
        UUID playerId = update != null ? parsePlayerId(update.getPlayerId()) : null;
        for (Session session : subscribedSessions) {
            Set<UUID> watched = watchedPlayers.get(session);
            if (legacyBroadSessions.contains(session) || playerId != null && watched != null && watched.contains(playerId)) send(session, update);
        }
    }

    private void broadcastLivePlayerData() {
        if (subscribedSessions.isEmpty()) {
            return;
        }
        Set<UUID> legacyTargets = ConcurrentHashMap.newKeySet();
        if (!legacyBroadSessions.isEmpty()) Bukkit.getOnlinePlayers().forEach(player -> legacyTargets.add(player.getUniqueId()));
        for (UUID playerId : legacyTargets) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            sendLivePlayerData(player, "livePlayerData");
        }
        Set<UUID> scopedTargets = ConcurrentHashMap.newKeySet();
        for (Set<UUID> watched : watchedPlayers.values()) scopedTargets.addAll(watched);
        scopedTargets.removeAll(legacyTargets);
        for (UUID playerId : scopedTargets) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            String signature = liveStateSignature(player);
            if (Objects.equals(lastScopedStateSignatures.put(playerId, signature), signature)) continue;
            sendLivePlayerData(player, "livePlayerData");
        }
    }

    private String liveStateSignature(Player player) {
        return gson.toJson(livePlayerData(player));
    }

    private void scheduleThrottledLivePlayerDataBroadcast(Player player, long throttleMs) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastLiveBroadcastAt.get(player.getUniqueId());
        if (last != null && now - last < throttleMs) {
            return;
        }
        lastLiveBroadcastAt.put(player.getUniqueId(), now);
        scheduleLivePlayerDataBroadcast(player, 1L, false, false);
    }

    private void scheduleLivePlayerDataBroadcast(Player player, long delayTicks, boolean replacePending, boolean inventoryChanged) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (inventoryChanged) {
            pendingInventoryRevisionPlayers.add(uuid);
        }
        Integer existing = pendingLiveBroadcastTasks.get(uuid);
        if (existing != null) {
            if (!replacePending) {
                return;
            }
            Bukkit.getScheduler().cancelTask(existing);
        }
        int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(context.getPlugin(), () -> {
            pendingLiveBroadcastTasks.remove(uuid);
            Player current = Bukkit.getPlayer(uuid);
            if (current != null && current.isOnline()) {
                if (pendingInventoryRevisionPlayers.remove(uuid)) {
                    syncInventoryRevisionIfChanged(current);
                }
                bumpStateRevision(uuid);
                lastLiveBroadcastAt.put(uuid, System.currentTimeMillis());
                sendLivePlayerData(current, "livePlayerData");
            }
        }, Math.max(0L, delayTicks));
        pendingLiveBroadcastTasks.put(uuid, taskId);
    }

    private long stateRevision(UUID uuid) {
        return uuid == null ? 0L : stateRevisions.getOrDefault(uuid, 0L);
    }

    private long inventoryRevision(UUID uuid) {
        return uuid == null ? 0L : inventoryRevisions.getOrDefault(uuid, 0L);
    }

    private long bumpStateRevision(UUID uuid) {
        return uuid == null ? 0L : stateRevisions.merge(uuid, 1L, Long::sum);
    }

    private long bumpInventoryRevision(UUID uuid) {
        return uuid == null ? 0L : inventoryRevisions.merge(uuid, 1L, Long::sum);
    }

    private void syncInventoryRevisionIfChanged(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String signature = inventorySignature(player);
        String previous = lastInventorySignatures.put(uuid, signature);
        if (previous != null && !Objects.equals(previous, signature)) {
            bumpInventoryRevision(uuid);
        }
    }

    private String inventorySignature(Player player) {
        StringBuilder builder = new StringBuilder();
        appendInventorySignature(builder, player.getInventory().getStorageContents());
        appendInventorySignature(builder, player.getInventory().getArmorContents());
        appendInventorySignature(builder, new ItemStack[] { player.getInventory().getItemInOffHand() });
        appendInventorySignature(builder, player.getEnderChest().getContents());
        return builder.toString();
    }

    private void appendInventorySignature(StringBuilder builder, ItemStack[] contents) {
        if (contents == null) {
            builder.append("null;");
            return;
        }
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (item == null || item.getType().isAir()) {
                builder.append(index).append(":air;");
                continue;
            }
            builder.append(index).append(':')
                    .append(item.getType().getKey())
                    .append(':')
                    .append(item.getAmount())
                    .append(':')
                    .append(item.serialize().hashCode())
                    .append(';');
        }
    }

    private void sendLivePlayerData(Player player, String reason) {
        if (player == null || subscribedSessions.isEmpty()) {
            return;
        }
        PlayerDossier dossier = trackingService.getDossier(player.getUniqueId());
        if (dossier == null) {
            trackingService.markOnline(player, "live");
            dossier = trackingService.getDossier(player.getUniqueId());
        }
        if (dossier != null) {
            PlayerTrackingUpdate update = PlayerTrackingUpdate.delta(reason, enrichDossier(dossier));
            for (Session session : subscribedSessions) {
                Set<UUID> watched = watchedPlayers.get(session);
                if (legacyBroadSessions.contains(session) || watched != null && watched.contains(player.getUniqueId())) send(session, update);
            }
        }
    }

    private void watchPlayer(Session session, String playerId) {
        UUID uuid = parsePlayerId(playerId);
        if (uuid == null) return;
        watchedPlayers.computeIfAbsent(session, ignored -> ConcurrentHashMap.newKeySet()).add(uuid);
        sendDossier(session, playerId);
    }

    private void unwatchPlayer(Session session, String playerId) {
        UUID uuid = parsePlayerId(playerId);
        Set<UUID> watched = watchedPlayers.get(session);
        if (uuid != null && watched != null) watched.remove(uuid);
    }

    private void send(Session session, PlayerTrackingUpdate update) {
        if (session == null || update == null || !session.getConnection().isOpen()) {
            return;
        }
        DataMessage message = new DataMessage();
        message.setChannel(channelId);
        message.setPayload(gson.toJson(update).getBytes(StandardCharsets.UTF_8));
        codec.sendMessage(session.getConnection().getFrameSender(), message, channelId, true);
    }

    private void sendRaw(Session session, Object payload) {
        if (session == null || payload == null || !session.getConnection().isOpen()) {
            return;
        }
        DataMessage message = new DataMessage();
        message.setChannel(channelId);
        message.setPayload(gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
        codec.sendMessage(session.getConnection().getFrameSender(), message, channelId, true);
    }

    private static class TrackingRequest {
        private String type;
        private String requestId;
        private String action;
        private String playerId;
        private String playerName;
        private boolean online;
        private String slot;
        private String itemId;
        private int count;
        private long baseInventoryRevision = -1L;
        private Map<String, Object> item;
        private List<InventoryEditRequest> edits;
        private String worldName;
        private String key;
        private String value;
    }

    private static class InventoryEditRequest {
        private String slot;
        private String itemId;
        private int count;
        private Map<String, Object> item;
    }
}
