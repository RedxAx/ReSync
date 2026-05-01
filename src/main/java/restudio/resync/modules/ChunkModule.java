package restudio.resync.modules;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import restudio.resync.ReSync;
import restudio.resync.cache.LRUCache;
import restudio.resync.core.Session;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.ErrorMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;
import restudio.resync.queue.Priority;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

public class ChunkModule implements Module {
    private static final String CHANNEL_ID = "chunks";
    private static final ModuleMetadata METADATA = ModuleMetadata.of("chunksLegacyHandler", "ChunksLegacyHandler", CHANNEL_ID);
    private static final int CHUNK_CHANNEL = 1000;
    private static final int REQUEST_BATCH_MAGIC = 0x52435131;
    private static final int RESPONSE_BATCH_MAGIC = 0x52435031;
    private static final int MAX_WORLD_NAME_LENGTH = 256;
    private static final int MAX_REQUESTS_PER_MESSAGE = 1024;
    private static final int MAX_LOADS_PER_TICK = 32;
    private static final int MAX_BATCHED_CHUNKS = 96;
    private static final int MAX_BATCHED_BYTES = 131072;

    private final Codec codec;
    private final LRUCache<ChunkKey, byte[]> chunkCache;
    private final ConcurrentHashMap<String, Session> subscribers;
    private final ConcurrentHashMap<ChunkKey, Set<Session>> pendingLoads;
    private final ConcurrentHashMap<Session, SessionBatch> batches;
    private final ConcurrentHashMap<ChunkKey, Priority> queuedLoads;
    private final Set<ChunkKey> activeLoads;
    private final EnumMap<Priority, ConcurrentLinkedQueue<ChunkRequest>> loadQueues;

    public ChunkModule(Codec codec, int maxCacheSize, long cacheTtlMinutes) {
        this.codec = codec;
        this.chunkCache = new LRUCache<>(maxCacheSize, cacheTtlMinutes * 60 * 1000);
        this.subscribers = new ConcurrentHashMap<>();
        this.pendingLoads = new ConcurrentHashMap<>();
        this.batches = new ConcurrentHashMap<>();
        this.queuedLoads = new ConcurrentHashMap<>();
        this.activeLoads = ConcurrentHashMap.newKeySet();
        this.loadQueues = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            this.loadQueues.put(priority, new ConcurrentLinkedQueue<>());
        }
    }

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public String getChannelId() {
        return CHANNEL_ID;
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        subscribers.put(session.getSessionId(), session);
    }

    @Override
    public void onUnsubscribe(Session session, UnsubscribeRequest req) {
        subscribers.remove(session.getSessionId());
        batches.remove(session);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        byte[] payload = req.getPayload();
        if (payload == null || payload.length < 4) {
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int marker = buffer.getInt();
        if (marker == REQUEST_BATCH_MAGIC) {
            handleBatchRequest(session, buffer);
            return;
        }

        buffer.position(0);
        handleLegacyRequest(session, buffer);
    }

    private void handleBatchRequest(Session session, ByteBuffer buffer) {
        if (buffer.remaining() < 8) {
            return;
        }

        int worldLength = buffer.getInt();
        if (worldLength <= 0 || worldLength > MAX_WORLD_NAME_LENGTH || buffer.remaining() < worldLength + 4) {
            return;
        }

        String world = readSizedString(buffer, worldLength);
        int requestCount = buffer.getInt();
        if (requestCount <= 0 || requestCount > MAX_REQUESTS_PER_MESSAGE) {
            return;
        }

        for (int i = 0; i < requestCount; i++) {
            if (buffer.remaining() < 12) {
                return;
            }

            int chunkX = buffer.getInt();
            int chunkZ = buffer.getInt();
            Priority priority = Priority.fromValue(buffer.getInt());
            handleChunkRequest(session, world, chunkX, chunkZ, priority);
        }
    }

    private void handleLegacyRequest(Session session, ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return;
        }

        int worldLength = buffer.getInt();
        if (worldLength <= 0 || worldLength > MAX_WORLD_NAME_LENGTH || buffer.remaining() < worldLength + 12) {
            return;
        }

        String world = readSizedString(buffer, worldLength);
        int chunkX = buffer.getInt();
        int chunkZ = buffer.getInt();
        Priority priority = Priority.fromValue(buffer.getInt());
        handleChunkRequest(session, world, chunkX, chunkZ, priority);
    }

    private void handleChunkRequest(Session session, String world, int chunkX, int chunkZ, Priority priority) {
        ChunkKey key = new ChunkKey(world, chunkX, chunkZ);
        byte[] cached = chunkCache.get(key);
        if (cached != null) {
            sendChunkData(session, cached);
            return;
        }

        pendingLoads.compute(key, (ignored, sessions) -> {
            Set<Session> waiting = sessions != null ? sessions : ConcurrentHashMap.newKeySet();
            waiting.add(session);
            return waiting;
        });

        if (!activeLoads.contains(key)) {
            Priority queuedPriority = queuedLoads.compute(key, (ignored, existingPriority) -> {
                if (existingPriority == null || priority.getValue() < existingPriority.getValue()) {
                    return priority;
                }
                return existingPriority;
            });
            loadQueues.get(queuedPriority).offer(new ChunkRequest(world, chunkX, chunkZ, queuedPriority));
        }
    }

    private void dispatchChunkLoads() {
        int dispatched = 0;
        while (dispatched < MAX_LOADS_PER_TICK) {
            ChunkRequest request = pollNextRequest();
            if (request == null) {
                return;
            }

            ChunkKey key = request.key();
            Priority queuedPriority = queuedLoads.remove(key);
            if (queuedPriority == null) {
                continue;
            }

            Set<Session> waitingSessions = pendingLoads.get(key);
            if (waitingSessions == null || waitingSessions.isEmpty()) {
                continue;
            }

            if (!activeLoads.add(key)) {
                continue;
            }

            loadChunkAsync(request.withPriority(queuedPriority));
            dispatched++;
        }
    }

    private ChunkRequest pollNextRequest() {
        for (Priority priority : Priority.values()) {
            ChunkRequest request = loadQueues.get(priority).poll();
            if (request != null) {
                Priority queuedPriority = queuedLoads.get(request.key());
                if (queuedPriority == null || queuedPriority != request.priority()) {
                    continue;
                }
                return request;
            }
        }
        return null;
    }

    private void loadChunkAsync(ChunkRequest request) {
        ChunkKey key = request.key();
        World world = Bukkit.getWorld(request.world());
        if (world == null) {
            activeLoads.remove(key);
            notifyError(key, "World not found: " + request.world());
            return;
        }

        world.getChunkAtAsync(request.chunkX(), request.chunkZ())
            .whenComplete((chunk, throwable) -> {
                if (throwable != null || chunk == null) {
                    activeLoads.remove(key);
                    String error = throwable != null ? throwable.getMessage() : "Unknown chunk load failure";
                    notifyError(key, "Failed to load chunk: " + error);
                    return;
                }
                ReSync plugin = ReSync.getInstance();
                if (plugin == null || !plugin.isEnabled()) {
                    activeLoads.remove(key);
                    notifyError(key, "Failed to load chunk: Plugin not enabled");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> finishChunkLoad(key, chunk));
            });
    }

    private void finishChunkLoad(ChunkKey key, Chunk chunk) {
        try {
            byte[] chunkData = encodeChunk(chunk);
            activeLoads.remove(key);

            chunkCache.put(key, chunkData);
            Set<Session> waitingSessions = pendingLoads.remove(key);
            if (waitingSessions == null) {
                return;
            }

            for (Session waitingSession : waitingSessions) {
                if (waitingSession.getConnection().getWebSocket().isOpen()) {
                    sendChunkData(waitingSession, chunkData);
                }
            }
        } catch (Exception e) {
            notifyError(key, "Failed to encode chunk: " + e.getMessage());
        }
    }

    private void notifyError(ChunkKey key, String error) {
        queuedLoads.remove(key);
        activeLoads.remove(key);

        Set<Session> waitingSessions = pendingLoads.remove(key);
        if (waitingSessions == null) {
            return;
        }

        for (Session waitingSession : waitingSessions) {
            sendError(waitingSession, error);
        }
    }

    private byte[] encodeChunk(Chunk chunk) {
        short[] heights = new short[256];
        short[] paletteIndices = new short[256];
        List<String> palette = new ArrayList<>();
        Map<String, Short> paletteLookup = new ConcurrentHashMap<>();

        int worldX = chunk.getX() << 4;
        int worldZ = chunk.getZ() << 4;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int blockY = chunk.getWorld().getHighestBlockYAt(worldX + x, worldZ + z);
                String blockId = chunk.getBlock(x, blockY, z).getType().getKey().toString();

                short paletteIndex = paletteLookup.computeIfAbsent(blockId, ignored -> {
                    short created = (short) palette.size();
                    palette.add(blockId);
                    return created;
                });

                int index = z * 16 + x;
                heights[index] = (short) blockY;
                paletteIndices[index] = paletteIndex;
            }
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeInt(chunk.getX());
            dos.writeInt(chunk.getZ());
            dos.writeShort(palette.size());

            for (String blockId : palette) {
                byte[] bytes = blockId.getBytes(StandardCharsets.UTF_8);
                dos.writeShort(bytes.length);
                dos.write(bytes);
            }

            for (int i = 0; i < 256; i++) {
                dos.writeShort(heights[i]);
                dos.writeShort(paletteIndices[i]);
            }

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode chunk", e);
        }
    }

    private void sendChunkData(Session session, byte[] chunkData) {
        if (!session.getConnection().getWebSocket().isOpen()) {
            return;
        }

        SessionBatch batch = batches.computeIfAbsent(session, ignored -> new SessionBatch());
        batch.lock.lock();
        try {
            batch.chunks.add(chunkData);
            batch.payloadBytes += chunkData.length;
            if (batch.chunks.size() >= MAX_BATCHED_CHUNKS || batch.payloadBytes >= MAX_BATCHED_BYTES) {
                flushBatch(session, batch);
            }
        } finally {
            batch.lock.unlock();
        }
    }

    private void flushBatch(Session session, SessionBatch batch) {
        if (batch.chunks.isEmpty()) {
            return;
        }

        ByteBuffer payloadBuffer = ByteBuffer.allocate(8 + batch.payloadBytes + batch.chunks.size() * Integer.BYTES);
        payloadBuffer.putInt(RESPONSE_BATCH_MAGIC);
        payloadBuffer.putInt(batch.chunks.size());
        for (byte[] chunk : batch.chunks) {
            payloadBuffer.putInt(chunk.length);
            payloadBuffer.put(chunk);
        }

        batch.chunks.clear();
        batch.payloadBytes = 0;

        DataMessage message = new DataMessage();
        message.setPayload(payloadBuffer.array());
        message.setChannel(CHUNK_CHANNEL);

        byte[] frame = codec.encodeFrame(message, CHUNK_CHANNEL, true, true);
        session.getConnection().getWebSocket().send(frame);
        session.getConnection().addBytesSent(frame.length);
    }

    @Override
    public void onTick() {
        dispatchChunkLoads();

        for (Map.Entry<Session, SessionBatch> entry : batches.entrySet()) {
            Session session = entry.getKey();
            SessionBatch batch = entry.getValue();

            if (!session.getConnection().getWebSocket().isOpen()) {
                batches.remove(session);
                continue;
            }

            batch.lock.lock();
            try {
                flushBatch(session, batch);
            } finally {
                batch.lock.unlock();
            }
        }
    }

    private void sendError(Session session, String errorText) {
        if (!session.getConnection().getWebSocket().isOpen()) {
            return;
        }

        ErrorMessage error = new ErrorMessage();
        error.setErrorCode(500);
        error.setErrorText(errorText);

        byte[] frame = codec.encodeFrame(error, 0, false);
        session.getConnection().getWebSocket().send(frame);
    }

    private static String readSizedString(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void cleanup(Session session) {
        subscribers.remove(session.getSessionId());
        batches.remove(session);

        for (Map.Entry<ChunkKey, Set<Session>> entry : pendingLoads.entrySet()) {
            Set<Session> waitingSessions = entry.getValue();
            waitingSessions.remove(session);
            if (waitingSessions.isEmpty()) {
                pendingLoads.remove(entry.getKey(), waitingSessions);
                queuedLoads.remove(entry.getKey());
            }
        }
    }

    public int getSubscriberCount() {
        return subscribers.size();
    }

    public int getCacheSize() {
        return chunkCache.size();
    }

    private record ChunkKey(String world, int x, int z) {
    }

    private record ChunkRequest(String world, int chunkX, int chunkZ, Priority priority) {
        private ChunkKey key() {
            return new ChunkKey(world, chunkX, chunkZ);
        }

        private ChunkRequest withPriority(Priority updatedPriority) {
            return new ChunkRequest(world, chunkX, chunkZ, updatedPriority);
        }
    }

    private static final class SessionBatch {
        private final List<byte[]> chunks = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        private int payloadBytes;
    }
}
