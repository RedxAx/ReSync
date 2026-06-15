package restudio.resync.runtime;

import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.simple.PacketPlayReceiveEvent;
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.flow.util.TextFormatter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerNpcPacketRuntime implements PlayerNpcRuntime, Listener {
    private final JavaPlugin plugin;
    private final PacketEventsAPI<Plugin> packetEvents;
    private final RuntimeNotificationService notifications;
    private final InteractionDispatcher dispatcher;
    private final Map<String, PacketNpc> npcs = new ConcurrentHashMap<>();
    private final Map<Integer, String> idsByEntity = new ConcurrentHashMap<>();
    private final Map<String, SkinTextures> skinCache = new ConcurrentHashMap<>();
    private final Map<String, Long> interactionDebounce = new ConcurrentHashMap<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(2_000_000);
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final PacketListenerCommon packetListener;
    private final int followTaskId;

    public PlayerNpcPacketRuntime(JavaPlugin plugin, PacketEventsAPI<Plugin> packetEvents, RuntimeNotificationService notifications, InteractionDispatcher dispatcher) {
        this.plugin = plugin;
        this.packetEvents = packetEvents;
        this.notifications = notifications;
        this.dispatcher = dispatcher;
        this.packetListener = createPacketListener();
        packetEvents.getEventManager().registerListener(packetListener);
        var task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFollowPlayers, 2L, 2L);
        followTaskId = task.getTaskId();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return "";
    }

    @Override
    public boolean spawn(String id, JsonObject definition, org.bukkit.Location location) {
        if (id == null || id.isBlank() || definition == null || location == null || location.getWorld() == null) {
            return false;
        }
        PacketNpc existing = npcs.get(id);
        if (existing != null) {
            return true;
        }
        PacketNpc npc = new PacketNpc(id, nextEntityId.incrementAndGet(), profile(id, definition), location.clone(), copy(definition));
        npcs.put(id, npc);
        idsByEntity.put(npc.entityId(), id);
        if (!showToVisiblePlayers(npc)) {
            npcs.remove(id);
            idsByEntity.remove(npc.entityId());
            return false;
        }
        resolveSkin(definition).thenAccept(textures -> {
            if (textures == null) {
                return;
            }
            PacketNpc active = npcs.get(id);
            if (active == null) {
                return;
            }
            active.profile().getTextureProperties().clear();
            active.profile().getTextureProperties().add(new TextureProperty("textures", textures.value(), textures.signature()));
            Bukkit.getScheduler().runTask(plugin, () -> reshow(active));
        });
        return true;
    }

    @Override
    public boolean despawn(String id) {
        PacketNpc npc = npcs.remove(id);
        if (npc == null) {
            return false;
        }
        idsByEntity.remove(npc.entityId());
        clearInteractionDebounce(id);
        hideFromAll(npc);
        return true;
    }

    @Override
    public boolean reload(String id, JsonObject definition, boolean deleted, org.bukkit.Location fallbackLocation) {
        if (deleted || definition == null) {
            return despawn(id);
        }
        PacketNpc npc = npcs.get(id);
        if (npc == null) {
            return false;
        }
        org.bukkit.Location location = fallbackLocation != null ? fallbackLocation : npc.location();
        npc.definition(copy(definition));
        npc.location(location.clone());
        npc.profile(profile(id, definition));
        reshow(npc);
        resolveSkin(definition).thenAccept(textures -> {
            if (textures == null) {
                return;
            }
            PacketNpc active = npcs.get(id);
            if (active == null) {
                return;
            }
            active.profile().getTextureProperties().clear();
            active.profile().getTextureProperties().add(new TextureProperty("textures", textures.value(), textures.signature()));
            Bukkit.getScheduler().runTask(plugin, () -> reshow(active));
        });
        return true;
    }

    @Override
    public boolean isActive(String id) {
        return npcs.containsKey(id);
    }

    @Override
    public org.bukkit.Location location(String id) {
        PacketNpc npc = npcs.get(id);
        return npc != null ? npc.location().clone() : null;
    }

    @Override
    public void shutdown() {
        for (PacketNpc npc : List.copyOf(npcs.values())) {
            hideFromAll(npc);
        }
        npcs.clear();
        idsByEntity.clear();
        interactionDebounce.clear();
        Bukkit.getScheduler().cancelTask(followTaskId);
        packetEvents.getEventManager().unregisterListener(packetListener);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> showVisibleTo(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onWorldChanged(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> showVisibleTo(event.getPlayer()));
    }

    private PacketListenerCommon createPacketListener() {
        return new SimplePacketListenerAbstract(PacketListenerPriority.MONITOR) {
            @Override
            public void onPacketPlayReceive(PacketPlayReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
                    return;
                }
                Player player = event.getPlayer() instanceof Player p ? p : null;
                if (player == null) {
                    return;
                }
                WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
                String id = idsByEntity.get(packet.getEntityId());
                if (id == null) {
                    return;
                }
                if (packet.getHand() == InteractionHand.OFF_HAND) {
                    return;
                }
                event.setCancelled(true);
                boolean leftClick = packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
                boolean shifting = packet.isSneaking().orElse(false);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!shouldDispatchInteraction(player, id)) {
                        return;
                    }
                    PacketNpc npc = npcs.get(id);
                    org.bukkit.Location location = npc != null ? npc.location() : player.getLocation();
                    dispatcher.interact(id, player, location, leftClick, shifting);
                });
            }
        };
    }

    private void tickFollowPlayers() {
        for (PacketNpc npc : npcs.values()) {
            if (npc == null) {
                continue;
            }
            boolean moved = tickGravity(npc);
            if (!bool(npc.definition(), "followPlayer", false)) {
                if (moved) {
                    sendTeleportToVisiblePlayers(npc);
                }
                continue;
            }
            Player target = nearestPlayer(npc.location(), decimal(npc.definition(), "followRange", 12.0));
            if (target == null) {
                if (moved) {
                    sendTeleportToVisiblePlayers(npc);
                }
                continue;
            }
            org.bukkit.Location location = npc.location();
            LookAngles angles = lookAt(location, target.getEyeLocation());
            location.setYaw(smoothAngle(location.getYaw(), angles.yaw(), 0.35F));
            location.setPitch(smoothAngle(location.getPitch(), angles.pitch(), 0.35F));
            if (moved) {
                sendTeleportToVisiblePlayers(npc);
            } else {
                sendLookToVisiblePlayers(npc);
            }
        }
    }

    private boolean tickGravity(PacketNpc npc) {
        if (!bool(npc.definition(), "gravity", true)) {
            npc.verticalVelocity(0);
            return false;
        }
        org.bukkit.Location location = npc.location();
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (onGround(location, npc.verticalVelocity())) {
            double groundY = Math.floor(location.getY() - 0.05) + 1.0;
            boolean moved = Math.abs(location.getY() - groundY) > 0.001 || npc.verticalVelocity() != 0;
            location.setY(groundY);
            npc.verticalVelocity(0);
            return moved;
        }
        double velocity = Math.max(npc.verticalVelocity() - 0.16, -3.92);
        double nextY = Math.max(location.getWorld().getMinHeight(), location.getY() + velocity);
        npc.verticalVelocity(velocity);
        location.setY(nextY);
        if (onGround(location, velocity)) {
            location.setY(Math.floor(location.getY() - 0.05) + 1.0);
            npc.verticalVelocity(0);
        }
        return true;
    }

    private boolean onGround(org.bukkit.Location location, double velocity) {
        if (location == null || location.getWorld() == null || velocity > 0) {
            return false;
        }
        int blockY = (int) Math.floor(location.getY() - 0.05);
        if (blockY < location.getWorld().getMinHeight()) {
            return true;
        }
        Material type = location.getWorld().getBlockAt(location.getBlockX(), blockY, location.getBlockZ()).getType();
        return type.isSolid();
    }

    private Player nearestPlayer(org.bukkit.Location origin, double range) {
        if (origin == null || origin.getWorld() == null || range <= 0) {
            return null;
        }
        Player nearest = null;
        double nearestDistance = range * range;
        for (Player player : origin.getWorld().getPlayers()) {
            if (player == null || player.isDead() || !player.isOnline()) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(origin);
            if (distance <= nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void sendLookToVisiblePlayers(PacketNpc npc) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!sameWorld(viewer.getWorld(), npc.location().getWorld())) {
                continue;
            }
            try {
                send(viewer, new WrapperPlayServerEntityHeadLook(npc.entityId(), npc.location().getYaw()));
                send(viewer, new WrapperPlayServerEntityRotation(npc.entityId(), npc.location().getYaw(), npc.location().getPitch(), bool(npc.definition(), "gravity", true)));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void sendTeleportToVisiblePlayers(PacketNpc npc) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!sameWorld(viewer.getWorld(), npc.location().getWorld())) {
                continue;
            }
            try {
                send(viewer, new WrapperPlayServerEntityTeleport(npc.entityId(), packetLocation(npc.location()), onGround(npc.location(), npc.verticalVelocity())));
                send(viewer, new WrapperPlayServerEntityHeadLook(npc.entityId(), npc.location().getYaw()));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private LookAngles lookAt(org.bukkit.Location origin, org.bukkit.Location target) {
        double dx = target.getX() - origin.getX();
        double dy = target.getY() - (origin.getY() + 1.62);
        double dz = target.getZ() - origin.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new LookAngles(yaw, Math.clamp(pitch, -90F, 90F));
    }

    private float smoothAngle(float current, float target, float factor) {
        float delta = ((target - current + 540F) % 360F) - 180F;
        return current + delta * factor;
    }

    private void showVisibleTo(Player player) {
        for (PacketNpc npc : npcs.values()) {
            if (sameWorld(player.getWorld(), npc.location().getWorld())) {
                show(player, npc);
            } else {
                hide(player, npc);
            }
        }
    }

    private boolean showToVisiblePlayers(PacketNpc npc) {
        boolean attempted = false;
        boolean sent = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sameWorld(player.getWorld(), npc.location().getWorld())) {
                attempted = true;
                sent |= show(player, npc);
            }
        }
        return !attempted || sent;
    }

    private void reshow(PacketNpc npc) {
        hideFromAll(npc);
        showToVisiblePlayers(npc);
    }

    private boolean show(Player player, PacketNpc npc) {
        try {
            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(npc.profile(), false, 0, GameMode.CREATIVE, nameComponent(npc), null, 0, true);
            send(player, new WrapperPlayServerPlayerInfoUpdate(playerInfoActions(), List.of(info)));
            send(player, teamRemovePacket(npc));
            send(player, teamCreatePacket(npc));
            send(player, spawnPacket(npc));
            send(player, new WrapperPlayServerEntityHeadLook(npc.entityId(), npc.location().getYaw()));
            send(player, new WrapperPlayServerEntityRotation(npc.entityId(), npc.location().getYaw(), npc.location().getPitch(), bool(npc.definition(), "gravity", true)));
            send(player, new WrapperPlayServerEntityMetadata(npc.entityId(), metadata(npc)));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    send(player, new WrapperPlayServerPlayerInfoRemove(List.of(npc.profile().getUUID())));
                } catch (RuntimeException ignored) {
                }
            }, 40L);
            return true;
        } catch (RuntimeException exception) {
            notifications.broadcastError("ReSync Player NPCs do not support this server version: " + exception.getMessage());
            return false;
        }
    }

    private void hideFromAll(PacketNpc npc) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            hide(player, npc);
        }
    }

    private void hide(Player player, PacketNpc npc) {
        try {
            send(player, new WrapperPlayServerDestroyEntities(npc.entityId()));
            send(player, teamRemovePacket(npc));
            send(player, new WrapperPlayServerPlayerInfoRemove(List.of(npc.profile().getUUID())));
        } catch (RuntimeException ignored) {
        }
    }

    private void send(Player player, PacketWrapper<?> packet) {
        packetEvents.getPlayerManager().sendPacket(player, packet);
    }

    private PacketWrapper<?> spawnPacket(PacketNpc npc) {
        Location location = packetLocation(npc.location());
        if (packetEvents.getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_20_2)) {
            return new WrapperPlayServerSpawnEntity(npc.entityId(), npc.profile().getUUID(), EntityTypes.PLAYER, location, location.getYaw(), 0, null);
        }
        return new WrapperPlayServerSpawnPlayer(npc.entityId(), npc.profile().getUUID(), location, metadata(npc));
    }

    private PacketWrapper<?> teamCreatePacket(PacketNpc npc) {
        Component name = nameComponent(npc);
        WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            name,
            name,
            Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE
        );
        return new WrapperPlayServerTeams(teamName(npc), WrapperPlayServerTeams.TeamMode.CREATE, teamInfo, npc.profile().getName());
    }

    private PacketWrapper<?> teamRemovePacket(PacketNpc npc) {
        return new WrapperPlayServerTeams(teamName(npc), WrapperPlayServerTeams.TeamMode.REMOVE, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null);
    }

    private List<EntityData<?>> metadata(PacketNpc npc) {
        int skinLayerIndex = packetEvents.getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_9) ? 16 : 17;
        return List.of(
            new EntityData<>(5, EntityDataTypes.BOOLEAN, !bool(npc.definition(), "gravity", true)),
            new EntityData<>(skinLayerIndex, EntityDataTypes.BYTE, (byte) 0x7F)
        );
    }

    private EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> playerInfoActions() {
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.of(
            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME
        );
        if (packetEvents.getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_4)) {
            actions.add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT);
        }
        return actions;
    }

    private Location packetLocation(org.bukkit.Location location) {
        return new Location(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private boolean shouldDispatchInteraction(Player player, String id) {
        long now = System.currentTimeMillis();
        String key = player.getUniqueId() + ":" + id;
        Long previous = interactionDebounce.put(key, now);
        return previous == null || now - previous > 300L;
    }

    private void clearInteractionDebounce(String id) {
        String marker = ":" + id;
        interactionDebounce.keySet().removeIf(key -> key.endsWith(marker));
    }

    private UserProfile profile(String id, JsonObject definition) {
        UserProfile profile = new UserProfile(uuid(id), profileName());
        SkinTextures textures = rawSkin(definition);
        if (textures != null) {
            profile.getTextureProperties().add(new TextureProperty("textures", textures.value(), textures.signature()));
        }
        return profile;
    }

    private CompletableFuture<SkinTextures> resolveSkin(JsonObject definition) {
        SkinTextures raw = rawSkin(definition);
        if (raw != null) {
            return CompletableFuture.completedFuture(raw);
        }
        String uuid = skinText(definition, "uuid", "skinUuid");
        if (!uuid.isBlank()) {
            return skinByUuid(uuid);
        }
        String username = skinText(definition, "username", "skinUsername");
        if (!username.isBlank()) {
            return skinByUsername(username);
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<SkinTextures> skinByUsername(String username) {
        String key = "name:" + username.toLowerCase(Locale.ROOT);
        SkinTextures cached = skinCache.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username)).GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenCompose(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return CompletableFuture.completedFuture(null);
                }
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                String uuid = text(root, "id");
                return uuid.isBlank() ? CompletableFuture.completedFuture(null) : skinByUuid(uuid);
            })
            .thenApply(textures -> {
                if (textures != null) {
                    skinCache.put(key, textures);
                }
                return textures;
            })
            .exceptionally(error -> null);
    }

    private CompletableFuture<SkinTextures> skinByUuid(String uuid) {
        String clean = uuid.replace("-", "");
        String key = "uuid:" + clean.toLowerCase(Locale.ROOT);
        SkinTextures cached = skinCache.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + clean + "?unsigned=false")).GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return null;
                }
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray properties = root.has("properties") && root.get("properties").isJsonArray() ? root.getAsJsonArray("properties") : new JsonArray();
                for (JsonElement element : properties) {
                    JsonObject property = element.getAsJsonObject();
                    if ("textures".equals(text(property, "name"))) {
                        SkinTextures textures = new SkinTextures(text(property, "value"), text(property, "signature"));
                        skinCache.put(key, textures);
                        return textures;
                    }
                }
                return null;
            })
            .exceptionally(error -> null);
    }

    private SkinTextures rawSkin(JsonObject definition) {
        String texture = skinText(definition, "texture", "skinTexture");
        String signature = skinText(definition, "signature", "skinSignature");
        return texture.isBlank() ? null : new SkinTextures(texture, signature);
    }

    private String skinText(JsonObject definition, String nested, String flat) {
        if (definition != null && definition.has("skin") && definition.get("skin").isJsonObject()) {
            String value = text(definition.getAsJsonObject("skin"), nested);
            if (!value.isBlank()) {
                return value;
            }
        }
        return text(definition, flat);
    }

    private String displayName(PacketNpc npc) {
        return displayName(npc.id(), npc.definition());
    }

    private Component nameComponent(PacketNpc npc) {
        return TextFormatter.parse(displayName(npc));
    }

    private String displayName(String id, JsonObject definition) {
        String value = text(definition, "displayName");
        return value.isBlank() ? id : value;
    }

    private String profileName() {
        return "";
    }

    private String teamName(PacketNpc npc) {
        return "rsn" + npc.entityId();
    }

    private boolean sameWorld(World left, World right) {
        return left != null && right != null && left.getUID().equals(right.getUID());
    }

    private UUID uuid(String id) {
        return UUID.nameUUIDFromBytes(("resync:npc:" + id).getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject copy(JsonObject source) {
        return source == null ? new JsonObject() : source.deepCopy();
    }

    private String text(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double decimal(JsonObject object, String key, double fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record SkinTextures(String value, String signature) {
    }

    private record LookAngles(float yaw, float pitch) {
    }

    private static final class PacketNpc {
        private final String id;
        private final int entityId;
        private UserProfile profile;
        private org.bukkit.Location location;
        private JsonObject definition;
        private double verticalVelocity;

        private PacketNpc(String id, int entityId, UserProfile profile, org.bukkit.Location location, JsonObject definition) {
            this.id = id;
            this.entityId = entityId;
            this.profile = profile;
            this.location = location;
            this.definition = definition;
        }

        private String id() {
            return id;
        }

        private int entityId() {
            return entityId;
        }

        private UserProfile profile() {
            return profile;
        }

        private void profile(UserProfile profile) {
            this.profile = profile;
        }

        private org.bukkit.Location location() {
            return location;
        }

        private void location(org.bukkit.Location location) {
            this.location = location;
        }

        private JsonObject definition() {
            return definition;
        }

        private void definition(JsonObject definition) {
            this.definition = definition;
        }

        private double verticalVelocity() {
            return verticalVelocity;
        }

        private void verticalVelocity(double verticalVelocity) {
            this.verticalVelocity = verticalVelocity;
        }
    }
}
