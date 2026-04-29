package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.bukkit.World;
import restudio.flow.data.FlowDataType;
import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.preview.WorldGenPreviewManager;
import restudio.resync.worldgen.registry.WorldGenNodeDefinitions;
import restudio.resync.worldgen.registry.WorldGenNodeRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
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

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.codec = context.getCodec();
        this.channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        this.previewManager = new WorldGenPreviewManager(context.getPlugin());
        WorldGenNodeDefinitions.registerDefaults(WorldGenNodeRegistry.getInstance());
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
        if (payload.length < 1) return;
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte packetId = buffer.get();
        try {
            switch (packetId) {
                case 0x20 -> handleSave(buffer);
                case 0x21 -> handlePreviewCreate(session, buffer);
                case 0x22 -> handlePreviewStop(buffer);
                case 0x24 -> sendRegistrySnapshot(session);
                case 0x30 -> handleSaveProject(buffer);
                case 0x31 -> handlePreviewApply(session, buffer);
                default -> Log.warn("Unknown worldgen packet: 0x" + String.format("%02X", packetId));
            }
        } catch (Exception e) {
            Log.error("Error handling worldgen packet 0x" + String.format("%02X", packetId) + ": " + e.getMessage());
            sendStatus(session, "", "error", e.getMessage());
        }
    }

    private void handleSave(ByteBuffer buffer) {
        WorldGenGraph graph = WorldGenSerializer.deserialize(readJson(buffer));
        if (graph != null) {
            graph.rebuildIndices();
        }
    }

    private void handleSaveProject(ByteBuffer buffer) {
        WorldGenProject project = WorldGenSerializer.deserializeProject(readJson(buffer));
        if (project != null) {
            project.rebuildIndices();
        }
    }

    private void handlePreviewCreate(Session session, ByteBuffer buffer) {
        PreviewCreateRequest request = gson.fromJson(readJson(buffer), PreviewCreateRequest.class);
        WorldGenGraph graph = gson.fromJson(gson.toJson(request.graph()), WorldGenGraph.class);
        graph.rebuildIndices();
        World.Environment environment = parseEnvironment(request.environment());
        previewManager.createPreview(
            request.previewId(),
            request.playerUuid(),
            graph,
            environment,
            request.seed(),
            preview -> sendStatus(session, request.previewId(), "ready", "Ready"),
            throwable -> sendStatus(session, request.previewId(), "error", throwable.getMessage())
        );
    }

    private void handlePreviewApply(Session session, ByteBuffer buffer) {
        PreviewApplyRequest request = gson.fromJson(readJson(buffer), PreviewApplyRequest.class);
        WorldGenProject project = gson.fromJson(gson.toJson(request.project()), WorldGenProject.class);
        if (project == null || project.getTerrainGraph() == null) {
            throw new IllegalArgumentException("Terrain Graph Missing");
        }
        project.rebuildIndices();
        long started = System.nanoTime();
        World.Environment environment = parseEnvironment(request.environment());
        previewManager.createPreview(
            request.previewId(),
            request.playerUuid(),
            project,
            environment,
            request.seed(),
            preview -> sendStatus(session, request.previewId(), "ready", "Ready " + Math.max(1L, (System.nanoTime() - started) / 1_000_000L) + "ms"),
            throwable -> sendStatus(session, request.previewId(), "error", throwable.getMessage())
        );
    }

    private void handlePreviewStop(ByteBuffer buffer) {
        PreviewStopRequest request = gson.fromJson(readJson(buffer), PreviewStopRequest.class);
        previewManager.stopPreview(request.previewId(), null, throwable -> Log.error("Error stopping worldgen preview: " + throwable.getMessage()));
    }

    private void sendRegistrySnapshot(Session session) {
        sendJsonPacket(session, (byte) 0x25, gson.toJson(WorldGenNodeRegistry.getInstance().getAllDefinitions()));
    }

    private void sendStatus(Session session, String previewId, String status, String message) {
        sendJsonPacket(session, (byte) 0x23, gson.toJson(Map.of("previewId", previewId == null ? "" : previewId, "status", status == null ? "error" : status, "message", message == null ? "" : message)));
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

    private void sendJsonPacket(Session session, byte packetId, String json) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);
        DataMessage message = new DataMessage();
        message.setChannel(channelId);
        message.setPayload(buffer.array());
        codec.sendMessage(session.getConnection().getWebSocket(), message, channelId, jsonBytes.length > 1024);
    }

    private record PreviewCreateRequest(WorldGenGraph graph, String previewId, String environment, long seed, String playerUuid) {
    }

    private record PreviewApplyRequest(WorldGenProject project, String previewId, String environment, long seed, String playerUuid) {
    }

    private record PreviewStopRequest(String previewId) {
    }
}
