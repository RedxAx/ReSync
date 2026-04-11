package restudio.resync.modules;

import com.google.gson.Gson;
import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.player.PlayerTrackingService;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;
import restudio.resync.world.WorldChannelMessage;
import restudio.resync.world.WorldInventoryGroup;
import restudio.resync.world.WorldManagementListener;
import restudio.resync.world.WorldManagementManager;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldMapAction;
import restudio.resync.world.WorldMapActionResult;
import restudio.resync.world.WorldMapQuery;
import restudio.resync.world.WorldMapSnapshot;
import restudio.resync.world.WorldMapService;
import restudio.resync.world.WorldOperationResult;
import restudio.resync.world.WorldProfileSettings;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WorldManagementModule implements Module, WorldManagementListener {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("worldManagement", "WorldManagement", "world_management")
        .withDependencies("playerTracking");
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final Gson gson = new Gson();
    private Codec codec;
    private int channelId;
    private WorldManagementService worldManagementService;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.codec = context.getCodec();
        this.channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        PlayerTrackingService trackingService = context.getRequiredService(PlayerTrackingService.class);
        this.worldManagementService = new WorldManagementManager(context.getPlugin(), trackingService);
        this.worldManagementService.addListener(this);
        context.registerService(WorldManagementService.class, worldManagementService);
        context.registerService(WorldMapService.class, worldManagementService.getMapService());
    }

    @Override
    public void start(ModuleContext context) {
        worldManagementService.start();
    }

    @Override
    public void stop(ModuleContext context) {
        if (worldManagementService != null) {
            worldManagementService.removeListener(this);
            worldManagementService.stop();
        }
        subscribedSessions.clear();
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        subscribedSessions.add(session);
        send(session, WorldChannelMessage.response("snapshot", true, "Snapshot", worldManagementService.createSnapshot()));
    }

    @Override
    public void onUnsubscribe(Session session, UnsubscribeRequest req) {
        subscribedSessions.remove(session);
    }

    @Override
    public void cleanup(Session session) {
        subscribedSessions.remove(session);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        if (req.getPayload() == null || req.getPayload().length == 0) {
            send(session, WorldChannelMessage.response("snapshot", true, "Snapshot", worldManagementService.createSnapshot()));
            return;
        }
        try {
            String json = new String(req.getPayload(), StandardCharsets.UTF_8);
            RequestMessage request = gson.fromJson(json, RequestMessage.class);
            if (request == null || request.action == null || request.action.isBlank()) {
                send(session, WorldChannelMessage.error("unknown", "InvalidRequestAction"));
                return;
            }
            switch (request.action) {
                case "snapshot" -> send(session, WorldChannelMessage.response("snapshot", true, "Snapshot", worldManagementService.createSnapshot()));
                case "scanUnregisteredWorlds" -> respondResult(session, request.action, worldManagementService.scanUnregisteredWorlds());
                case "importUnregisteredWorlds" -> respondResult(session, request.action, worldManagementService.importUnregisteredWorlds());
                case "createWorld" -> respondResult(session, request.action,
                    worldManagementService.createWorld(request.worldName, request.seed, request.environment, request.generator, request.generatorConfig));
                case "cloneWorld" -> respondResult(session, request.action,
                    worldManagementService.cloneWorldAsync(request.sourceWorld, request.targetWorld, request.loadAfterClone));
                case "deleteWorld" -> respondResult(session, request.action,
                    worldManagementService.deleteWorld(request.worldName, request.deleteFiles, request.fallbackWorld));
                case "loadWorld" -> respondResult(session, request.action, worldManagementService.loadWorld(request.worldName));
                case "unloadWorld" -> respondResult(session, request.action,
                    worldManagementService.unloadWorld(request.worldName, request.fallbackWorld));
                case "setGameRule" -> respondResult(session, request.action,
                    worldManagementService.setGameRule(request.worldName, request.ruleName, request.ruleValue));
                case "setGameRules" -> respondResult(session, request.action,
                    worldManagementService.setGameRules(request.worldName, request.gameRules));
                case "setDifficulty" -> respondResult(session, request.action,
                    worldManagementService.setDifficulty(request.worldName, request.difficulty));
                case "setTimeLock" -> respondResult(session, request.action,
                    worldManagementService.setTimeLock(request.worldName, request.enabled, request.lockedTime));
                case "setWeatherLock" -> respondResult(session, request.action,
                    worldManagementService.setWeatherLock(request.worldName, request.enabled, request.storm, request.thundering));
                case "setIsolatedPlayerState" -> respondResult(session, request.action,
                    worldManagementService.setIsolatedPlayerState(request.worldName, request.enabled));
                case "setWorldProfile" -> respondResult(session, request.action,
                    worldManagementService.setWorldProfile(request.worldName, request.profileSettings));
                case "teleportPlayerToWorld" -> respondResult(session, request.action,
                    worldManagementService.teleportPlayerToWorld(request.playerName, request.worldName, request.hasPosition ? request.destinationX : null,
                        request.hasPosition ? request.destinationY : null, request.hasPosition ? request.destinationZ : null,
                        request.hasRotation ? request.destinationYaw : null, request.hasRotation ? request.destinationPitch : null));
                case "teleportPlayerToWorldSpawn" -> respondResult(session, request.action,
                    worldManagementService.teleportPlayerToWorldSpawn(request.playerName, request.worldName));
                case "createInventoryGroup" -> respondResult(session, request.action,
                    worldManagementService.createInventoryGroup(request.inventoryGroup));
                case "updateInventoryGroup" -> respondResult(session, request.action,
                    worldManagementService.updateInventoryGroup(request.inventoryGroup));
                case "deleteInventoryGroup" -> respondResult(session, request.action,
                    worldManagementService.deleteInventoryGroup(request.groupId));
                case "whoWorld" -> respondResult(session, request.action,
                    worldManagementService.whoWorld(request.worldName));
                case "purgeWorld" -> respondResult(session, request.action,
                    worldManagementService.purgeWorld(request.worldName, request.purgeMonsters, request.purgeAnimals, request.purgeAmbient,
                        request.purgeMisc, request.purgeVehicles, request.purgeItems));
                case "mapSnapshot" -> {
                    WorldMapSnapshot snapshot = worldManagementService.getMapService().createSnapshot(mapQuery(request));
                    send(session, WorldChannelMessage.response(request.action, true, "MapSnapshot", snapshot));
                }
                case "mapAction" -> {
                    WorldMapActionResult result = worldManagementService.getMapService().handleAction(mapAction(request));
                    send(session, WorldChannelMessage.response(request.action, result.isSuccess(), result.getMessage(), result));
                }
                default -> send(session, WorldChannelMessage.error(request.action, "UnknownWorldAction"));
            }
        } catch (Exception exception) {
            Log.warn("WorldManagement request failed: " + exception.getMessage());
            send(session, WorldChannelMessage.error("worldRequest", "RequestFailed"));
        }
    }

    @Override
    public void onTick() {
        if (worldManagementService != null) {
            worldManagementService.tick();
        }
    }

    @Override
    public void onMessage(WorldChannelMessage message) {
        broadcast(message);
    }

    private void respondResult(Session session, String action, WorldOperationResult result) {
        if (result == null) {
            send(session, WorldChannelMessage.error(action, "OperationReturnedNull"));
            return;
        }
        send(session, WorldChannelMessage.response(action, result.isSuccess(), result.getMessage(), result));
    }

    private WorldMapQuery mapQuery(RequestMessage request) {
        WorldMapQuery query = new WorldMapQuery();
        query.setWorldName(request.worldName);
        query.setCenterX(request.centerX);
        query.setCenterZ(request.centerZ);
        query.setZoom(request.zoom);
        query.setOptions(request.options);
        return query;
    }

    private WorldMapAction mapAction(RequestMessage request) {
        WorldMapAction action = new WorldMapAction();
        action.setExtensionId(request.extensionId);
        action.setActionId(request.extensionActionId);
        action.setWorldName(request.worldName);
        action.setData(request.options);
        return action;
    }

    private void broadcast(WorldChannelMessage message) {
        for (Session session : subscribedSessions) {
            send(session, message);
        }
    }

    private void send(Session session, WorldChannelMessage message) {
        if (session == null || message == null || session.getConnection() == null || !session.getConnection().getWebSocket().isOpen()) {
            return;
        }
        DataMessage output = new DataMessage();
        output.setChannel(channelId);
        output.setPayload(gson.toJson(message).getBytes(StandardCharsets.UTF_8));
        codec.sendMessage(session.getConnection().getWebSocket(), output, channelId, true);
    }

    private static class RequestMessage {
        private String action;
        private String worldName;
        private String sourceWorld;
        private String targetWorld;
        private String fallbackWorld;
        private String seed;
        private String environment;
        private String generator;
        private String generatorConfig;
        private String ruleName;
        private String ruleValue;
        private Map<String, String> gameRules;
        private WorldProfileSettings profileSettings;
        private String difficulty;
        private boolean enabled;
        private long lockedTime;
        private boolean storm;
        private boolean thundering;
        private boolean deleteFiles;
        private boolean loadAfterClone;
        private String playerName;
        private double destinationX;
        private double destinationY;
        private double destinationZ;
        private float destinationYaw;
        private float destinationPitch;
        private boolean hasPosition;
        private boolean hasRotation;
        private String extensionId;
        private String extensionActionId;
        private WorldInventoryGroup inventoryGroup;
        private String groupId;
        private boolean purgeMonsters;
        private boolean purgeAnimals;
        private boolean purgeAmbient;
        private boolean purgeMisc;
        private boolean purgeVehicles;
        private boolean purgeItems;
        private double centerX;
        private double centerZ;
        private int zoom;
        private Map<String, Object> options;
    }
}
