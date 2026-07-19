package restudio.resync.modules;

import com.google.gson.Gson;
import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.jobs.JobManager;
import restudio.resync.jobs.JobRecord;
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
import restudio.resync.world.WorldOperationSafetyService;
import restudio.resync.world.WorldProfileSettings;
import restudio.resync.worldgen.WorldGenProjectStorage;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

public class WorldManagementModule implements Module, WorldManagementListener {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("worldManagement", "WorldManagement", "world_management")
        .withDependencies("flowJobs", "playerTracking", "worldGen");
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final Gson gson = new Gson();
    private Codec codec;
    private int channelId;
    private WorldManagementService worldManagementService;
    private WorldOperationSafetyService safetyService;
    private ScheduledExecutorService scheduler;
    private JobManager jobManager;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.codec = context.getCodec();
        this.channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        this.scheduler = context.getScheduler();
        PlayerTrackingService trackingService = context.getRequiredService(PlayerTrackingService.class);
        WorldGenProjectStorage worldGenProjectStorage = context.getRequiredService(WorldGenProjectStorage.class);
        this.safetyService = new WorldOperationSafetyService(context.getPlugin());
        this.jobManager = new JobManager(context.getRequiredService(FlowJobRegistry.class), job -> broadcast(WorldChannelMessage.job("jobStatus", job.snapshot())));
        this.worldManagementService = new WorldManagementManager(context.getPlugin(), trackingService, worldGenProjectStorage);
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
                case "auditSnapshot" -> send(session, WorldChannelMessage.response(request.action, true, "AuditSnapshot", safetyService.snapshot(request.limit)));
                case "jobSnapshot" -> send(session, WorldChannelMessage.job("jobSnapshot", jobManager.activeOrRecentSnapshot(actor(session), 300000)));
                case "operationStatus" -> {
                    JobRecord<?> job = jobManager.get(request.operationId);
                    WorldOperationResult status = safetyService.getStatus(request.operationId);
                    Object data = job != null ? job.snapshot() : status != null ? status : Map.of();
                    send(session, WorldChannelMessage.response(request.action, job != null || status != null, job != null || status != null ? "OperationStatus" : "OperationMissing", data));
                }
                case "createSafetyBackup" -> respondResult(session, request.action, safetyService.unavailableBackupResult(request.action, request.worldName, actor(session)));
                case "restoreSafetyBackup" -> respondResult(session, request.action, safetyService.unavailableBackupResult(request.action, request.worldName, actor(session)));
                case "scanUnregisteredWorlds" -> startOperation(session, request, () -> worldManagementService.scanUnregisteredWorlds());
                case "importUnregisteredWorlds" -> startOperation(session, request, () -> worldManagementService.importUnregisteredWorlds());
                case "createWorld" -> startOperation(session, request,
                    () -> worldManagementService.createWorld(request.worldName, request.seed, request.environment, request.generator, request.generatorConfig));
                case "cloneWorld" -> startOperation(session, request,
                    () -> worldManagementService.cloneWorldAsync(request.sourceWorld, request.targetWorld, request.loadAfterClone));
                case "deleteWorld" -> startOperation(session, request,
                    () -> worldManagementService.deleteWorld(request.worldName, request.deleteFiles, request.fallbackWorld));
                case "loadWorld" -> startOperation(session, request, () -> worldManagementService.loadWorld(request.worldName));
                case "unloadWorld" -> startOperation(session, request,
                    () -> worldManagementService.unloadWorld(request.worldName, request.fallbackWorld));
                case "setGameRule" -> startOperation(session, request,
                    () -> worldManagementService.setGameRule(request.worldName, request.ruleName, request.ruleValue));
                case "setGameRules" -> startOperation(session, request,
                    () -> worldManagementService.setGameRules(request.worldName, request.gameRules));
                case "setDifficulty" -> startOperation(session, request,
                    () -> worldManagementService.setDifficulty(request.worldName, request.difficulty));
                case "setTimeLock" -> startOperation(session, request,
                    () -> worldManagementService.setTimeLock(request.worldName, request.enabled, request.lockedTime));
                case "setWeatherLock" -> startOperation(session, request,
                    () -> worldManagementService.setWeatherLock(request.worldName, request.enabled, request.storm, request.thundering));
                case "setIsolatedPlayerState" -> startOperation(session, request,
                    () -> worldManagementService.setIsolatedPlayerState(request.worldName, request.enabled));
                case "setWorldProfile" -> startOperation(session, request,
                    () -> worldManagementService.setWorldProfile(request.worldName, request.profileSettings));
                case "teleportPlayerToWorld" -> startOperation(session, request,
                    () -> worldManagementService.teleportPlayerToWorld(request.playerName, request.worldName, request.hasPosition ? request.destinationX : null,
                        request.hasPosition ? request.destinationY : null, request.hasPosition ? request.destinationZ : null,
                        request.hasRotation ? request.destinationYaw : null, request.hasRotation ? request.destinationPitch : null));
                case "teleportPlayerToWorldSpawn" -> startOperation(session, request,
                    () -> worldManagementService.teleportPlayerToWorldSpawn(request.playerName, request.worldName));
                case "createInventoryGroup" -> startOperation(session, request,
                    () -> worldManagementService.createInventoryGroup(request.inventoryGroup));
                case "updateInventoryGroup" -> startOperation(session, request,
                    () -> worldManagementService.updateInventoryGroup(request.inventoryGroup));
                case "deleteInventoryGroup" -> startOperation(session, request,
                    () -> worldManagementService.deleteInventoryGroup(request.groupId));
                case "whoWorld" -> respondResult(session, request.action,
                    worldManagementService.whoWorld(request.worldName));
                case "purgeWorld" -> startOperation(session, request,
                    () -> worldManagementService.purgeWorld(request.worldName, request.purgeMonsters, request.purgeAnimals, request.purgeAmbient,
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

    private void startOperation(Session session, String action, Supplier<WorldOperationResult> operation) {
        RequestMessage request = new RequestMessage();
        request.action = action;
        startOperation(session, request, operation);
    }

    private void startOperation(Session session, RequestMessage request, Supplier<WorldOperationResult> operation) {
        String action = request.action;
        String actorClientId = actor(session);
        Map<String, Object> parameters = request.parameters();
        JobRecord<WorldOperationResult> job = jobManager.create(action, actorClientId, request.targetWorldName(), request.requestId);
        String operationId = job.getJobId();
        if (job.getStatus().terminal() || job.getStatus().name().equals("RUNNING")) {
            send(session, WorldChannelMessage.job("jobAccepted", job.snapshot()));
            return;
        }
        var auditRecord = safetyService.begin(operationId, action, actorClientId, request.targetWorldName(), parameters);
        send(session, WorldChannelMessage.job("jobAccepted", job.snapshot()));
        scheduler.execute(() -> {
            WorldOperationResult result = null;
            Throwable failure = null;
            try {
                job.markRunning();
                jobManager.publish(job);
                result = operation.get();
                if (result != null) {
                    enrichResult(result, operationId, actorClientId, job.snapshot(), auditRecord.getAuditId());
                    safetyService.rememberStatus(result);
                    if (result.isSuccess()) {
                        job.markSucceeded(result, result.getMessage());
                    } else {
                        job.markFailed(result.getMessage(), null);
                    }
                    jobManager.publish(job);
                }
                respondResult(session, action, result);
            } catch (Exception exception) {
                failure = exception;
                Log.warn("WorldManagement operation failed: " + exception.getMessage());
                result = WorldOperationResult.failure(action, request.targetWorldName(), "RequestFailed");
                enrichResult(result, operationId, actorClientId, job.snapshot(), auditRecord.getAuditId());
                safetyService.rememberStatus(result);
                job.markFailed("RequestFailed", exception);
                jobManager.publish(job);
                send(session, WorldChannelMessage.response(action, false, "RequestFailed", result));
            } finally {
                safetyService.finish(auditRecord, result, failure);
            }
        });
    }

    private void enrichResult(WorldOperationResult result, String operationId, String actorClientId, Map<String, Object> jobSnapshot, String auditId) {
        result.setOperationId(operationId);
        result.setActorClientId(actorClientId);
        result.setStartedAt(numberValue(jobSnapshot.get("startedAt")));
        result.setFinishedAt(System.currentTimeMillis());
        result.setAuditId(auditId);
        result.setStatus(result.isSuccess() ? "succeeded" : "failed");
        result.withData("operationId", operationId);
        result.withData("jobId", operationId);
        result.withData("actorClientId", actorClientId);
        result.withData("startedAt", result.getStartedAt());
        result.withData("finishedAt", result.getFinishedAt());
        result.withData("auditId", auditId);
        result.withData("backupAvailable", false);
        result.withData("status", result.getStatus());
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : System.currentTimeMillis();
    }

    private String actor(Session session) {
        return session != null && session.getClientId() != null ? session.getClientId() : "unknown";
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
        if (session == null || message == null || session.getConnection() == null || !session.getConnection().isOpen()) {
            return;
        }
        DataMessage output = new DataMessage();
        output.setChannel(channelId);
        output.setPayload(gson.toJson(message).getBytes(StandardCharsets.UTF_8));
        codec.sendMessage(session.getConnection().getFrameSender(), output, channelId, true);
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
        private String operationId;
        private String requestId;
        private String confirmationToken;
        private int limit;
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

        private String targetWorldName() {
            if (worldName != null && !worldName.isBlank()) {
                return worldName;
            }
            if (targetWorld != null && !targetWorld.isBlank()) {
                return targetWorld;
            }
            if (sourceWorld != null && !sourceWorld.isBlank()) {
                return sourceWorld;
            }
            return "";
        }

        private Map<String, Object> parameters() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("worldName", worldName);
            data.put("sourceWorld", sourceWorld);
            data.put("targetWorld", targetWorld);
            data.put("fallbackWorld", fallbackWorld);
            data.put("deleteFiles", deleteFiles);
            data.put("loadAfterClone", loadAfterClone);
            data.put("playerName", playerName);
            data.put("groupId", groupId);
            data.put("generator", generator);
            data.put("environment", environment);
            data.put("purgeMonsters", purgeMonsters);
            data.put("purgeAnimals", purgeAnimals);
            data.put("purgeAmbient", purgeAmbient);
            data.put("purgeMisc", purgeMisc);
            data.put("purgeVehicles", purgeVehicles);
            data.put("purgeItems", purgeItems);
            return data;
        }
    }
}
