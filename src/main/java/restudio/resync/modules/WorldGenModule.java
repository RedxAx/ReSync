package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import restudio.flow.data.FlowDataType;
import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.jobs.JobManager;
import restudio.resync.jobs.JobRecord;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.preview.WorldGenPreviewManager;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.WorldGenCompileDiagnostics;
import restudio.resync.worldgen.registry.WorldGenNodeDefinitions;
import restudio.resync.worldgen.registry.WorldGenNodeRegistry;
import restudio.resync.worldgen.runtime.WorldGenRuntimeListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WorldGenModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("worldGen", "WorldGen", "worldgen");
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final Gson gson = new GsonBuilder()
        .registerTypeAdapter(FlowDataType.class, new TypeAdapter<FlowDataType>() {
            @Override
            public void write(JsonWriter out, FlowDataType value) throws IOException {
                out.value(value == null ? FlowDataType.ANY.getId() : value.getId());
            }

            @Override
            public FlowDataType read(JsonReader in) throws IOException {
                return FlowDataType.fromString(in.nextString());
            }
        })
        .create();
    private Codec codec;
    private int channelId;
    private WorldGenPreviewManager previewManager;
    private WorldGenProjectStorage projectStorage;
    private WorldGenRuntimeListener runtimeListener;
    private JobManager jobManager;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.codec = context.getCodec();
        this.channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        this.previewManager = new WorldGenPreviewManager(context.getPlugin());
        this.projectStorage = new WorldGenProjectStorage(context.getPlugin());
        this.runtimeListener = new WorldGenRuntimeListener(context.getPlugin());
        this.jobManager = new JobManager(job -> broadcastJob("jobStatus", job.snapshot()));
        this.runtimeListener.start();
        WorldGenNodeDefinitions.registerDefaults(WorldGenNodeRegistry.getInstance());
        previewManager.cleanupOrphanedPreviews();
    }

    @Override
    public void stop(ModuleContext context) {
        if (previewManager != null) {
            previewManager.stopAllPreviews();
        }
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        subscribedSessions.add(session);
    }

    @Override
    public void cleanup(Session session) {
        subscribedSessions.remove(session);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        byte[] payload = req.getPayload();
        if (payload == null || payload.length < 1) {
            sendStatus(session, "", "error", "EmptyWorldGenPacket");
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte packetId = buffer.get();
        try {
            switch (packetId) {
                case 0x20 -> handleSave(buffer);
                case 0x21 -> handlePreviewCreate(session, buffer);
                case 0x22 -> handlePreviewStop(session, buffer);
                case 0x24 -> sendRegistrySnapshot(session);
                case 0x30 -> handleSaveProject(session, buffer);
                case 0x31 -> handlePreviewApply(session, buffer);
                case 0x32 -> handleProjectRequest(session, buffer);
                case 0x33 -> handleProjectDelete(session, buffer);
                case 0x34 -> sendProjectList(session);
                case 0x3A -> sendJobSnapshot(session);
                default -> {
                    Log.warn("Unknown worldgen packet: 0x" + String.format("%02X", packetId));
                    sendStatus(session, "", "error", "UnknownWorldGenPacket");
                }
            }
        } catch (Exception e) {
            Log.error("Error handling worldgen packet 0x" + String.format("%02X", packetId) + ": " + e.getMessage());
            sendStatus(session, "", "error", e.getMessage());
        }
    }

    private void handleSave(ByteBuffer buffer) {
        MutationJson payload = readMutationJson(buffer);
        WorldGenGraph graph = WorldGenSerializer.deserialize(payload.json());
        if (graph != null) {
            graph.rebuildIndices();
        }
    }

    private void handleSaveProject(Session session, ByteBuffer buffer) {
        MutationJson payload = readMutationJson(buffer);
        JobRecord<String> job = beginJob(session, "saveWorldGenProject", "", payload.requestId());
        if (job == null) {
            return;
        }
        try {
            WorldGenProject project = WorldGenSerializer.deserializeProject(payload.json());
            if (project != null) {
                if (project.getId() == null || project.getId().isBlank()) {
                    project.setId(UUID.randomUUID().toString());
                }
                project.rebuildIndices();
                WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project);
                sendJsonPacket(session, (byte) 0x38, gson.toJson(diagnostics));
                if (!diagnostics.isSuccess()) {
                    throw new IllegalArgumentException("WorldGen Compile Failed");
                }
                projectStorage.saveProject(project);
                sendJsonPacket(session, (byte) 0x37, project.getId());
                succeedJob(job, project.getId(), "Saved");
            } else {
                throw new IllegalArgumentException("Invalid WorldGen Project");
            }
        } catch (Exception exception) {
            failJob(job, exception.getMessage(), exception);
            throw exception;
        }
    }

    private void handleProjectRequest(Session session, ByteBuffer buffer) {
        String projectId = readJson(buffer);
        WorldGenProject project = projectStorage.getProject(projectId);
        if (project != null) {
            sendJsonPacket(session, (byte) 0x35, WorldGenSerializer.serializeProject(project));
        } else {
            sendStatus(session, "", "error", "WorldGen Project Missing");
        }
    }

    private void handleProjectDelete(Session session, ByteBuffer buffer) {
        MutationJson payload = readMutationJson(buffer);
        String projectId = payload.json();
        JobRecord<String> job = beginJob(session, "deleteWorldGenProject", projectId, payload.requestId());
        if (job == null) {
            return;
        }
        try {
            projectStorage.deleteProject(projectId);
            succeedJob(job, projectId, "Deleted");
        } catch (Exception exception) {
            failJob(job, exception.getMessage(), exception);
            throw exception;
        }
    }

    private void sendProjectList(Session session) {
        sendJsonPacket(session, (byte) 0x36, gson.toJson(projectStorage.listProjectIds()));
    }

    private void handlePreviewCreate(Session session, ByteBuffer buffer) {
        MutationJson payload = readMutationJson(buffer);
        PreviewCreateRequest request = gson.fromJson(payload.json(), PreviewCreateRequest.class);
        JobRecord<String> job = beginJob(session, "createWorldGenPreview", request.previewId(), payload.requestId());
        if (job == null) {
            return;
        }
        WorldGenGraph graph = gson.fromJson(gson.toJson(request.graph()), WorldGenGraph.class);
        graph.rebuildIndices();
        World.Environment environment = parseEnvironment(request.environment());
        previewManager.createPreview(
            request.previewId(),
            request.playerUuid(),
            graph,
            environment,
            request.seed(),
            preview -> {
                sendStatus(session, request.previewId(), "ready", "Ready");
                succeedJob(job, request.previewId(), "Ready");
            },
            throwable -> {
                String message = throwable != null ? throwable.getMessage() : "Preview Failed";
                sendStatus(session, request.previewId(), "error", message);
                failJob(job, message, throwable);
            }
        );
    }

    private void handlePreviewApply(Session session, ByteBuffer buffer) {
        MutationJson payload = readMutationJson(buffer);
        PreviewApplyRequest request = gson.fromJson(payload.json(), PreviewApplyRequest.class);
        JobRecord<String> job = beginJob(session, "applyWorldGenPreview", request.previewId(), payload.requestId());
        if (job == null) {
            return;
        }
        WorldGenProject project = null;
        if (request.draftProject() != null) {
            project = gson.fromJson(gson.toJson(request.draftProject()), WorldGenProject.class);
        }
        if (project == null && request.project() != null) {
            project = gson.fromJson(gson.toJson(request.project()), WorldGenProject.class);
        }
        if (project == null && request.projectId() != null && !request.projectId().isBlank()) {
            project = projectStorage.getProject(request.projectId());
        }
        if (project == null || project.getTerrainGraph() == null) {
            throw new IllegalArgumentException("Terrain Graph Missing");
        }
        project.rebuildIndices();
        WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project);
        sendJsonPacket(session, (byte) 0x38, gson.toJson(diagnostics));
        if (!diagnostics.isSuccess()) {
            failJob(job, "WorldGen Compile Failed", null);
            throw new IllegalArgumentException("WorldGen Compile Failed");
        }
        long started = System.nanoTime();
        World.Environment environment = parseEnvironment(request.environment());
        previewManager.createPreview(
            request.previewId(),
            request.playerUuid(),
            project,
            environment,
            request.seed(),
            preview -> {
                String message = previewReadyMessage(preview, started);
                sendStatus(session, request.previewId(), "ready", message);
                succeedJob(job, request.previewId(), message);
            },
            throwable -> {
                String message = throwable != null ? throwable.getMessage() : "Preview Failed";
                sendStatus(session, request.previewId(), "error", message);
                failJob(job, message, throwable);
            }
        );
    }

    private String previewReadyMessage(WorldGenPreviewManager.PreviewWorld preview, long started) {
        long elapsed = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        if (preview == null || preview.datapackBuild() == null) {
            return "Ready " + elapsed + "ms";
        }
        String suffix = preview.datapackBuild().getWarnings().isEmpty() ? "" : " · " + preview.datapackBuild().getWarnings().size() + " Warnings";
        return "Ready " + elapsed + "ms · Datapack " + preview.datapackBuild().getFileCount() + " Files" + suffix;
    }

    private void handlePreviewStop(Session session, ByteBuffer buffer) {
        MutationJson payload = readMutationJson(buffer);
        PreviewStopRequest request = gson.fromJson(payload.json(), PreviewStopRequest.class);
        JobRecord<String> job = beginJob(session, "stopWorldGenPreview", request.previewId(), payload.requestId());
        if (job == null) {
            return;
        }
        previewManager.stopPreview(request.previewId(), () -> succeedJob(job, request.previewId(), "Stopped"), throwable -> {
            String message = throwable != null ? throwable.getMessage() : "Stop Failed";
            Log.error("Error stopping worldgen preview: " + message);
            failJob(job, message, throwable);
        });
    }

    private void sendRegistrySnapshot(Session session) {
        sendJsonPacket(session, (byte) 0x25, gson.toJson(Map.of(
            "nodes", WorldGenNodeRegistry.getInstance().getAllDefinitions(),
            "capabilities", Map.of(
                "backend", "bukkit",
                "datapackBackend", "compile_only",
                "datapackBiomes", true,
                "datapackFeatures", false,
                "datapackStructures", false,
                "datapackSpawns", true,
                "liveDatapackActivation", false,
                "previewDatapackCompile", true,
                "minecraftVersion", Bukkit.getMinecraftVersion()
            )
        )));
    }

    private void sendStatus(Session session, String previewId, String status, String message) {
        sendJsonPacket(session, (byte) 0x23, gson.toJson(Map.of("previewId", previewId == null ? "" : previewId, "status", status == null ? "error" : status, "message", message == null ? "" : message)));
    }

    private JobRecord<String> beginJob(Session session, String action, String target) {
        return beginJob(session, action, target, null);
    }

    private JobRecord<String> beginJob(Session session, String action, String target, String requestId) {
        JobRecord<String> job = jobManager.create(action, session != null ? session.getClientId() : "unknown", target == null ? "" : target, requestId);
        sendJob(session, "jobAccepted", job.snapshot());
        if (!job.markRunning()) {
            return null;
        }
        jobManager.publish(job);
        return job;
    }

    private void succeedJob(JobRecord<String> job, String result, String message) {
        if (job != null && job.markSucceeded(result, message == null || message.isBlank() ? "Succeeded" : message)) {
            jobManager.publish(job);
        }
    }

    private void failJob(JobRecord<String> job, String message, Throwable throwable) {
        if (job != null && job.markFailed(message == null || message.isBlank() ? "Failed" : message, throwable)) {
            jobManager.publish(job);
        }
    }

    private void broadcastJob(String action, Object data) {
        for (Session session : subscribedSessions) {
            sendJob(session, action, data);
        }
    }

    private void sendJob(Session session, String action, Object data) {
        sendJsonPacket(session, (byte) 0x39, gson.toJson(Map.of(
            "type", "job",
            "action", action == null ? "jobStatus" : action,
            "data", data == null ? Map.of() : data,
            "timestamp", System.currentTimeMillis()
        )));
    }

    private void sendJobSnapshot(Session session) {
        sendJob(session, "jobSnapshot", jobManager.activeOrRecentSnapshot(session != null ? session.getClientId() : "unknown", 300000));
    }

    private World.Environment parseEnvironment(String value) {
        if (value == null || value.isBlank() || "CUSTOM".equalsIgnoreCase(value)) {
            return World.Environment.NORMAL;
        }
        try {
            return World.Environment.valueOf(value);
        } catch (Exception ignored) {
            return World.Environment.NORMAL;
        }
    }

    private String readJson(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private MutationJson readMutationJson(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return new MutationJson(null, "");
        }
        byte first = buffer.get(buffer.position());
        if (first == '{' || first == '[' || first == '"' || first == '-' || Character.isDigit((char) first)) {
            return new MutationJson(null, readJson(buffer));
        }
        if (buffer.remaining() < Integer.BYTES) {
            return new MutationJson(null, readJson(buffer));
        }
        int start = buffer.position();
        int requestIdLength = buffer.getInt();
        if (requestIdLength <= 0 || requestIdLength > 256 || requestIdLength > buffer.remaining()) {
            buffer.position(start);
            return new MutationJson(null, readJson(buffer));
        }
        byte[] requestBytes = new byte[requestIdLength];
        buffer.get(requestBytes);
        return new MutationJson(new String(requestBytes, StandardCharsets.UTF_8), readJson(buffer));
    }

    private void sendJsonPacket(Session session, byte packetId, String json) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);
        DataMessage message = new DataMessage();
        message.setChannel(channelId);
        message.setPayload(buffer.array());
        codec.sendMessage(session.getConnection().getFrameSender(), message, channelId, jsonBytes.length > 1024);
    }

    private record PreviewCreateRequest(WorldGenGraph graph, String previewId, String environment, long seed, String playerUuid) {
    }

    private record PreviewApplyRequest(String projectId, WorldGenProject draftProject, WorldGenProject project, String previewId, String environment, long seed, String playerUuid) {
    }

    private record PreviewStopRequest(String previewId) {
    }

    private record MutationJson(String requestId, String json) {
    }
}
