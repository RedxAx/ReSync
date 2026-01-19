package restudio.resync.modules;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import restudio.resync.cache.LRUCache;
import restudio.resync.core.Session;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;
import restudio.resync.queue.Priority;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkModule implements Module {
    private static final String CHANNEL_ID = "chunks";
    private final Codec codec;
    private final LRUCache<ChunkKey, byte[]> chunkCache;
    private final ConcurrentHashMap<String, Session> subscribers;
    private final ConcurrentHashMap<ChunkKey, java.util.Set<Session>> pendingLoads;
    private final ConcurrentHashMap<Session, SessionBatch> batches;
    private final int maxCacheSize;
    private final long cacheTtlMs;

    public ChunkModule(Codec codec, int maxCacheSize, long cacheTtlMinutes) {
        this.codec = codec;
        this.maxCacheSize = maxCacheSize;
        this.cacheTtlMs = cacheTtlMinutes * 60 * 1000;
        this.chunkCache = new LRUCache<>(maxCacheSize, cacheTtlMinutes * 60 * 1000);
        this.subscribers = new ConcurrentHashMap<>();
        this.pendingLoads = new ConcurrentHashMap<>();
        this.batches = new ConcurrentHashMap<>();
    }

    @Override
    public String getChannelId() {
        return CHANNEL_ID;
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        subscribers.put(session.getSessionId(), session);
        session.subscribeChannel(CHANNEL_ID);
        session.addModule(this);
    }

    @Override
    public void onUnsubscribe(Session session, UnsubscribeRequest req) {
        subscribers.remove(session.getSessionId());
        session.unsubscribeChannel(CHANNEL_ID);
        session.removeModule(CHANNEL_ID);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        if (req.isServerResponse()) {
            return;
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(req.getPayload());
        
        if (buffer.remaining() < 4) {
            return;
        }
        
        int firstInt = buffer.getInt();
        
        if (firstInt > 0 && firstInt <= 64 && buffer.remaining() >= firstInt + 12) {
            String world = readStringFromBuffer(buffer, firstInt);
            int chunkX = buffer.getInt();
            int chunkZ = buffer.getInt();
            int priorityValue = buffer.getInt();

            Priority priority = Priority.fromValue(priorityValue);

            ChunkKey key = new ChunkKey(world, chunkX, chunkZ);

            if (chunkCache.get(key) != null) {
                sendChunkData(session, key);
                return;
            }

            pendingLoads.compute(key, (k, sessions) -> {
                if (sessions == null) {
                    ChunkRequest chunkRequest = new ChunkRequest(world, chunkX, chunkZ, session);
                    loadChunkAsync(chunkRequest, priority);
                    sessions = ConcurrentHashMap.newKeySet();
                }
                sessions.add(session);
                return sessions;
            });
        }
    }

    private void loadChunkAsync(ChunkRequest request, Priority priority) {
        World world = Bukkit.getWorld(request.world);
        if (world == null) {
            notifyError(request.world, request.chunkX, request.chunkZ, "World not found: " + request.world);
            return;
        }

        world.getChunkAtAsync(request.chunkX, request.chunkZ)
            .thenApplyAsync(chunk -> {
                try {
                    return encodeChunk(chunk);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to encode chunk", e);
                }
            })
                .thenAccept(chunkData -> {
                ChunkKey key = new ChunkKey(request.world, request.chunkX, request.chunkZ);
                updateCache(key, chunkData);
                
                java.util.Set<Session> waitingSessions = pendingLoads.remove(key);
                if (waitingSessions != null) {
                    for (Session s : waitingSessions) {
                        if (s.getConnection().getWebSocket().isOpen()) {
                            sendChunkData(s, key);
                        }
                    }
                }
            })
            .exceptionally(ex -> {
                notifyError(request.world, request.chunkX, request.chunkZ, "Failed to load chunk: " + ex.getMessage());
                return null;
            });
    }
    
    private void notifyError(String world, int x, int z, String error) {
        ChunkKey key = new ChunkKey(world, x, z);
        java.util.Set<Session> waitingSessions = pendingLoads.remove(key);
        if (waitingSessions != null) {
            for (Session s : waitingSessions) {
                sendError(s, error);
            }
        }
    }

    private byte[] encodeChunk(Chunk chunk) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

        try {
            dos.writeInt(chunk.getX());
            dos.writeInt(chunk.getZ());

            int worldX = chunk.getX() << 4;
            int worldZ = chunk.getZ() << 4;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int blockY = chunk.getWorld().getHighestBlockYAt(worldX + x, worldZ + z);
                    String blockId = chunk.getBlock(x, blockY, z).getType().getKey().toString();

                    dos.writeInt(blockY);
                    byte[] blockIdBytes = blockId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    dos.writeInt(blockIdBytes.length);
                    dos.write(blockIdBytes);
                }
            }

            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to encode chunk", e);
        }
    }

    private void sendChunkData(Session session, ChunkKey chunkKey) {
        if (!session.getConnection().getWebSocket().isOpen()) {
            return;
        }

        byte[] cachedData = chunkCache.get(chunkKey);
        if (cachedData == null) {
            return;
        }

        SessionBatch batch = batches.computeIfAbsent(session, s -> new SessionBatch());
        batch.lock.lock();
        try {
            batch.buffer.write(cachedData);
            if (batch.buffer.size() >= 65536) {
                flushBatch(session, batch);
            }
        } catch (java.io.IOException e) {
        } finally {
            batch.lock.unlock();
        }
    }

    private void flushBatch(Session session, SessionBatch batch) {
        byte[] payload = batch.buffer.toByteArray();
        batch.buffer.reset();
        
        DataMessage message = new DataMessage();
        message.setPayload(payload);
        message.setChannel(1000);
        message.setServerResponse(true);

        byte[] frame = codec.encodeFrame(message, 1000, true, true);
        session.getConnection().getWebSocket().send(frame);
        session.getConnection().addBytesSent(frame.length);
    }

    @Override
    public void onTick() {
        for (java.util.Map.Entry<Session, SessionBatch> entry : batches.entrySet()) {
            Session session = entry.getKey();
            SessionBatch batch = entry.getValue();
            
            if (session.getConnection().getWebSocket().isOpen()) {
                batch.lock.lock();
                try {
                    if (batch.buffer.size() > 0) {
                        flushBatch(session, batch);
                    }
                } finally {
                    batch.lock.unlock();
                }
            } else {
                batches.remove(session);
            }
        }
    }

    private void updateCache(ChunkKey key, byte[] data) {
        chunkCache.put(key, data);
    }

    private void sendError(Session session, String errorText) {
        restudio.resync.protocol.messages.ErrorMessage error = new restudio.resync.protocol.messages.ErrorMessage();
        error.setErrorCode(500);
        error.setErrorText(errorText);
        
        byte[] frame = codec.encodeFrame(error, 0, false);
        session.getConnection().getWebSocket().send(frame);
    }

    private static String readString(ByteBuffer buffer) {
        int length = buffer.getInt();
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes);
    }

    private static String readStringFromBuffer(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes);
    }

    private static void writeString(ByteBuffer buffer, String str) {
        byte[] bytes = str.getBytes();
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    @Override
    public void cleanup(Session session) {
        subscribers.remove(session.getSessionId());
    }

    public int getSubscriberCount() {
        return subscribers.size();
    }

    public int getCacheSize() {
        return chunkCache.size();
    }

    private static class ChunkKey {
        final String world;
        final int x;
        final int z;
        final long timestamp;

        ChunkKey(String world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkKey chunkKey = (ChunkKey) o;
            return x == chunkKey.x && z == chunkKey.z && world.equals(chunkKey.world);
        }

        @Override
        public int hashCode() {
            return world.hashCode() + 31 * x + 31 * 31 * z;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    private static class ChunkRequest {
        final String world;
        final int chunkX;
        final int chunkZ;
        final Session session;

        ChunkRequest(String world, int chunkX, int chunkZ, Session session) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.session = session;
        }
    }

    private static class SessionBatch {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(65536);
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    }
}
