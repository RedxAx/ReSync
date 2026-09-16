package restudio.resync.bridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.MessageType;
import restudio.resync.server.ReSyncServer;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ReSyncPluginMessageBridge implements PluginMessageListener, Listener {
    public static final String CHANNEL = "resync:bridge";
    private static final int BRIDGE_PROTOCOL_V1 = 1;
    private static final int BRIDGE_PROTOCOL_V2 = 2;
    private static final int MAX_OUTBOUND_QUEUE_PACKETS = 256;
    private static final long MAX_OUTBOUND_QUEUE_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_BULK_QUEUE_PACKETS = 192;
    private static final long MAX_BULK_QUEUE_BYTES = 3L * 1024L * 1024L;
    private static final int MAX_DRAIN_PACKETS = 24;
    private static final long MAX_DRAIN_BYTES = 384L * 1024L;
    private static final long MAX_DRAIN_NANOS = 2_000_000L;
    private final ReSync plugin;
    private final Map<UUID, BridgeSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<BridgeSession> drainReady = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainTaskScheduled = new AtomicBoolean();
    private BukkitTask monitorTask;

    public ReSyncPluginMessageBridge(ReSync plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::monitorSessions, 20L, 20L);
        Log.info("[ReSync] Vanilla bridge registered on " + CHANNEL);
    }

    public void unregister() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        for (BridgeSession session : List.copyOf(sessions.values())) {
            close(session);
        }
        sessions.clear();
        drainReady.clear();
        drainTaskScheduled.set(false);
        HandlerList.unregisterAll(this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || player == null || message == null) {
            return;
        }
        try {
            ReSyncBridgeEnvelope envelope = ReSyncBridgeEnvelope.decode(message);
            BridgeSession session = sessions.get(envelope.sessionId());
            if (session == null) {
                if (envelope.type() != ReSyncBridgeEnvelope.HELLO) {
                    return;
                }
                BridgeSession candidate = new BridgeSession(envelope.sessionId(), player);
                BridgeSession existing = sessions.putIfAbsent(envelope.sessionId(), candidate);
                session = existing == null ? candidate : existing;
            }
            if (!session.player.getUniqueId().equals(player.getUniqueId())) {
                return;
            }
            if (!session.isOpen()) {
                if (envelope.type() == ReSyncBridgeEnvelope.CLOSE) {
                    close(session);
                }
                return;
            }
            byte[] payload;
            synchronized (session.inboundFence) {
                payload = session.chunker.accept(envelope);
            }
            if (payload == null) {
                return;
            }
            if (envelope.type() == ReSyncBridgeEnvelope.HELLO) {
                handleHello(session, payload);
            } else if (envelope.type() == ReSyncBridgeEnvelope.DATA && session.authorized) {
                if (!hasBridgeAccess(player)) {
                    closeUnauthorized(session);
                    return;
                }
                ensureConnection(session);
                ReSyncServer server = plugin.getReSyncServer();
                ConnectionInfo connection = session.connection;
                if (server == null || connection == null || !session.isOpen()) {
                    close(session);
                    return;
                }
                server.onBridgeMessage(connection, player, payload);
            } else if (envelope.type() == ReSyncBridgeEnvelope.CLOSE) {
                close(session);
            }
        } catch (Exception ex) {
            Log.warn("[ReSync] Vanilla bridge rejected packet from " + player.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (BridgeSession session : List.copyOf(sessions.values())) {
            if (session.player.getUniqueId().equals(event.getPlayer().getUniqueId())) {
                close(session);
            }
        }
    }

    private void monitorSessions() {
        for (BridgeSession session : sessions.values()) {
            if (session.authorized && session.isOpen() && !hasBridgeAccess(session.player)) {
                closeUnauthorized(session);
            }
        }
    }

    private void handleHello(BridgeSession session, byte[] payload) {
        Player player = session.player;
        BridgeHello hello = readHello(payload);
        Log.fine("Vanilla bridge hello from " + player.getName() + " mod=" + hello.modVersion + " server=" + hello.serverAddress);
        if (!isSupportedBridgeProtocol(hello.protocolVersion)) {
            Log.warn("[ReSync] Vanilla bridge rejected " + player.getName() + ": unsupported protocol " + hello.protocolVersion);
            sendAuth(session, false, "Unsupported Bridge");
            closeAfterPending(session);
            return;
        }
        session.bridgeProtocolVersion = hello.protocolVersion;
        if (!hasBridgeAccess(player)) {
            Log.warn("[ReSync] Vanilla bridge rejected " + player.getName() + ": no permission");
            sendAuth(session, false, "No Permission");
            closeAfterPending(session);
            return;
        }
        if (!sendAuth(session, true, Bukkit.getServer().getName())) {
            Log.warn("[ReSync] Vanilla bridge rejected " + player.getName() + ": canonical server identity unavailable");
            closeAfterPending(session);
            return;
        }
        session.authorized = true;
        Log.fine("Vanilla bridge authorized " + player.getName());
    }

    private boolean isSupportedBridgeProtocol(int protocolVersion) {
        return protocolVersion == BRIDGE_PROTOCOL_V1 || protocolVersion == BRIDGE_PROTOCOL_V2;
    }

    private void ensureConnection(BridgeSession session) {
        if (!session.isOpen() || session.connection != null) {
            return;
        }
        long generation;
        FrameSender sender;
        synchronized (session.activationFence) {
            if (!session.isOpen() || session.connection != null || session.openInFlight) {
                return;
            }
            generation = session.generation.get();
            session.openInFlight = true;
            sender = new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    if (tryEnqueueOutbound(session, generation, ReSyncBridgeEnvelope.DATA, frame) != FrameSender.SendResult.ACCEPTED) {
                        requestClose(session, generation, "Bridge output unavailable", false);
                        if (Bukkit.isPrimaryThread()) {
                            completeClose(session);
                        }
                    }
                }

                @Override
                public FrameSender.SendResult trySend(byte[] frame) {
                    return tryEnqueueOutbound(session, generation, ReSyncBridgeEnvelope.DATA, frame);
                }

                @Override
                public int getMaxEncodedFrameBytes() {
                    return ReSyncBridgeChunker.CHUNK_SIZE;
                }

                @Override
                public void close(int code, String reason) {
                    requestClose(session, generation, reason, true);
                }
            };
        }
        openConnection(session, sender, generation);
    }

    private void openConnection(BridgeSession session, FrameSender sender, long generation) {
        ConnectionInfo openedConnection = null;
        boolean failed = false;
        try {
            openedConnection = openBridgeConnection(sender);
            failed = openedConnection == null;
        } catch (RuntimeException exception) {
            failed = true;
        }
        boolean stale = false;
        synchronized (session.activationFence) {
            session.openInFlight = false;
            if (openedConnection != null && session.state.get() == BridgeSessionState.OPEN
                && session.generation.get() == generation && session.connection == null) {
                session.connection = openedConnection;
            } else {
                stale = openedConnection != null;
            }
        }
        if (stale) {
            closeStaleConnection(openedConnection);
        }
        if (failed) {
            requestClose(session, generation, "Bridge unavailable", true);
        }
    }

    protected ConnectionInfo openBridgeConnection(FrameSender sender) {
        ReSyncServer server = plugin.getReSyncServer();
        return server == null ? null : server.onBridgeOpen(sender);
    }

    private void closeStaleConnection(ConnectionInfo connection) {
        if (connection == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            closeBridgeConnection(connection);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> closeBridgeConnection(connection));
        } catch (RuntimeException exception) {
            Log.warn("[ReSync] Vanilla bridge could not schedule stale connection cleanup: " + exception.getMessage());
        }
    }

    protected void closeBridgeConnection(ConnectionInfo connection) {
        ReSyncServer server = plugin.getReSyncServer();
        if (server != null) {
            server.onBridgeClose(connection);
        }
    }

    private boolean sendAuth(BridgeSession session, boolean success, String displayName) {
        ReSyncServer server = plugin.getReSyncServer();
        String canonicalServerId = success ? canonicalServerId(server) : "";
        boolean responseSuccess = success && (session.bridgeProtocolVersion == BRIDGE_PROTOCOL_V1 || !canonicalServerId.isBlank());
        boolean includeCanonicalServerId = responseSuccess && !canonicalServerId.isBlank();
        String responseName = responseSuccess || !success ? displayName : "ReSync Unavailable";
        byte[] nameBytes = (responseName == null ? "Live Server" : responseName).getBytes(StandardCharsets.UTF_8);
        Map<String, Integer> channels = Map.of();
        if (responseSuccess && server != null) {
            Map<String, Integer> availableChannels = server.getBridgeChannels();
            if (availableChannels != null) {
                channels = availableChannels;
            }
        }
        byte[] serverIdBytes = canonicalServerId.getBytes(StandardCharsets.UTF_8);
        int size = 1 + 4 + 4 + nameBytes.length + 4;
        for (String channel : channels.keySet()) {
            byte[] bytes = channel.getBytes(StandardCharsets.UTF_8);
            size += 4 + bytes.length;
        }
        if (includeCanonicalServerId) {
            size += 4 + serverIdBytes.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put((byte) (responseSuccess ? 1 : 0));
        buffer.putInt(2);
        buffer.putInt(nameBytes.length);
        buffer.put(nameBytes);
        buffer.putInt(channels.size());
        for (String channel : channels.keySet()) {
            putString(buffer, channel);
        }
        if (includeCanonicalServerId) {
            putString(buffer, canonicalServerId);
        }
        boolean queued = enqueueOutbound(session, session.generation.get(), ReSyncBridgeEnvelope.AUTH_RESULT, buffer.array());
        if (queued) {
            Log.fine("Vanilla bridge auth result queued for " + session.player.getName() + " success=" + responseSuccess + " bytes=" + buffer.capacity() + " channels=" + channels.size());
        }
        return responseSuccess && queued;
    }

    private String canonicalServerId(ReSyncServer server) {
        if (server == null) {
            return "";
        }
        try {
            String value = server.getCanonicalServerId();
            if (value == null || value.isBlank()) {
                return "";
            }
            ServerId serverId = ServerId.parseCanonicalText(value);
            return serverId.canonicalText().equals(value) ? value : "";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private BridgeHello readHello(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload == null ? new byte[0] : payload);
        int protocolVersion = buffer.remaining() >= 4 ? buffer.getInt() : -1;
        String modVersion = readString(buffer);
        String serverAddress = readString(buffer);
        return new BridgeHello(protocolVersion, modVersion, serverAddress);
    }

    private String readString(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return "";
        }
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            return "";
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private boolean hasBridgeAccess(Player player) {
        return player != null && player.isOnline() && (player.isOp() || player.hasPermission("resync.api.access"));
    }

    private void putString(ByteBuffer buffer, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    private void closeUnauthorized(BridgeSession session) {
        if (session == null || !session.isOpen()) {
            return;
        }
        String name = session.player != null ? session.player.getName() : session.sessionId.toString();
        Log.warn("[ReSync] Vanilla bridge closed " + name + ": no permission");
        sendClose(session, "No Permission");
    }

    private void sendClose(BridgeSession session, String reason) {
        if (session == null) {
            return;
        }
        requestClose(session, session.generation.get(), reason, true);
    }

    private void closeAfterPending(BridgeSession session) {
        if (session == null) {
            return;
        }
        boolean closeNow = false;
        synchronized (session.activationFence) {
            if (session.state.get() != BridgeSessionState.OPEN) {
                return;
            }
            if (!session.state.compareAndSet(BridgeSessionState.OPEN, BridgeSessionState.CLOSING)) {
                return;
            }
            long closingGeneration = session.generation.incrementAndGet();
            ArrayDeque<OutboundPacket> pendingAuth = new ArrayDeque<>();
            for (OutboundPacket packet : session.outbound) {
                if (packet.type() == ReSyncBridgeEnvelope.AUTH_RESULT) {
                    pendingAuth.addLast(new OutboundPacket(closingGeneration, packet.type(), packet.priority(), packet.encoded()));
                }
            }
            clearOutboundLocked(session);
            session.outbound.addAll(pendingAuth);
            for (OutboundPacket packet : pendingAuth) {
                session.outboundBytes += packet.encoded().length;
                if (packet.priority() == OutboundPriority.BULK) {
                    session.bulkPackets++;
                    session.bulkBytes += packet.encoded().length;
                }
            }
            try {
                List<OutboundPacket> closePackets = encodeOutbound(session, closingGeneration, ReSyncBridgeEnvelope.CLOSE,
                    "Bridge rejected".getBytes(StandardCharsets.UTF_8));
                addOutboundLocked(session, closePackets);
            } catch (RuntimeException exception) {
                closeNow = true;
            }
            closeNow |= session.outbound.isEmpty();
        }
        scheduleDrain(session);
        if (closeNow && Bukkit.isPrimaryThread()) {
            completeClose(session);
        }
    }

    private void close(BridgeSession session) {
        if (session == null) {
            return;
        }
        requestClose(session, session.generation.get(), null, false);
        if (Bukkit.isPrimaryThread()) {
            completeClose(session);
        } else {
            scheduleDrain(session);
        }
    }

    private void requestClose(BridgeSession session, long expectedGeneration, String reason, boolean notifyPeer) {
        boolean changed = false;
        synchronized (session.activationFence) {
            if (session.generation.get() != expectedGeneration || session.state.get() != BridgeSessionState.OPEN
                || !session.state.compareAndSet(BridgeSessionState.OPEN, BridgeSessionState.CLOSING)) {
                return;
            }
            long closingGeneration = session.generation.incrementAndGet();
            clearOutboundLocked(session);
            if (notifyPeer) {
                try {
                    List<OutboundPacket> closePackets = encodeOutbound(session, closingGeneration, ReSyncBridgeEnvelope.CLOSE,
                        (reason == null ? "" : reason).getBytes(StandardCharsets.UTF_8));
                    addOutboundLocked(session, closePackets);
                } catch (RuntimeException exception) {
                    clearOutboundLocked(session);
                }
            }
            changed = true;
        }
        if (changed && (notifyPeer || !Bukkit.isPrimaryThread())) {
            scheduleDrain(session);
        }
    }

    private boolean enqueueOutbound(BridgeSession session, long expectedGeneration, byte type, byte[] payload) {
        return tryEnqueueOutbound(session, expectedGeneration, type, payload) == FrameSender.SendResult.ACCEPTED;
    }

    private FrameSender.SendResult tryEnqueueOutbound(BridgeSession session, long expectedGeneration, byte type, byte[] payload) {
        List<OutboundPacket> packets;
        synchronized (session.activationFence) {
            if (session.state.get() != BridgeSessionState.OPEN || session.generation.get() != expectedGeneration) {
                TemporaryLifecycleDiagnostics.event("bridge_backpressure", 0L,
                    TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null, "bridge", null,
                        null, null, null, null, expectedGeneration), "outcome", FrameSender.SendResult.CLOSED,
                        "queuePackets", session.outbound.size(), "queueBytes", session.outboundBytes,
                        "frameBytes", payload == null ? 0 : payload.length));
                return FrameSender.SendResult.CLOSED;
            }
            try {
                packets = encodeOutbound(session, expectedGeneration, type, payload);
            } catch (RuntimeException exception) {
                TemporaryLifecycleDiagnostics.event("bridge_backpressure", 0L,
                    TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null, "bridge", null,
                        null, null, null, null, expectedGeneration), "outcome", FrameSender.SendResult.CLOSED,
                        "queuePackets", session.outbound.size(), "queueBytes", session.outboundBytes,
                        "frameBytes", payload == null ? 0 : payload.length, "failure", exception.getClass().getSimpleName()));
                return FrameSender.SendResult.CLOSED;
            }
            if (!addOutboundLocked(session, packets)) {
                TemporaryLifecycleDiagnostics.event("bridge_backpressure", 0L,
                    TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null, "bridge", null,
                        null, null, null, null, expectedGeneration), "outcome", FrameSender.SendResult.BACKPRESSURED,
                        "queuePackets", session.outbound.size(), "queueBytes", session.outboundBytes,
                        "frameBytes", payload == null ? 0 : payload.length, "chunkCount", packets.size()));
                return FrameSender.SendResult.BACKPRESSURED;
            }
        }
        return scheduleDrain(session) ? FrameSender.SendResult.ACCEPTED : FrameSender.SendResult.CLOSED;
    }

    private List<OutboundPacket> encodeOutbound(BridgeSession session, long generation, byte type, byte[] payload) {
        List<OutboundPacket> packets = new ArrayList<>();
        int sequence = session.sequence.getAndIncrement();
        OutboundPriority priority = outboundPriority(type, payload);
        session.chunker.send(session.sessionId, sequence, type, payload,
            envelope -> packets.add(new OutboundPacket(generation, type, priority, envelope.encode())));
        return packets;
    }

    private boolean addOutboundLocked(BridgeSession session, List<OutboundPacket> packets) {
        long bytes = 0L;
        int bulkPackets = 0;
        long bulkBytes = 0L;
        for (OutboundPacket packet : packets) {
            bytes += packet.encoded().length;
            if (packet.priority() == OutboundPriority.BULK) {
                bulkPackets++;
                bulkBytes += packet.encoded().length;
            }
        }
        if (packets.size() > MAX_OUTBOUND_QUEUE_PACKETS - session.outbound.size()
            || bytes > MAX_OUTBOUND_QUEUE_BYTES - session.outboundBytes
            || bulkPackets > MAX_BULK_QUEUE_PACKETS - session.bulkPackets
            || bulkBytes > MAX_BULK_QUEUE_BYTES - session.bulkBytes) {
            return false;
        }
        session.outbound.addAll(packets);
        session.outboundBytes += bytes;
        session.bulkPackets += bulkPackets;
        session.bulkBytes += bulkBytes;
        return true;
    }

    private void clearOutboundLocked(BridgeSession session) {
        session.outbound.clear();
        session.outboundBytes = 0L;
        session.bulkPackets = 0;
        session.bulkBytes = 0L;
    }

    private OutboundPriority outboundPriority(byte bridgeType, byte[] payload) {
        if (bridgeType != ReSyncBridgeEnvelope.DATA || payload == null || payload.length < 2) {
            return OutboundPriority.CONTROL;
        }
        try {
            MessageType messageType = MessageType.fromValue(payload[1]);
            return messageType == MessageType.DATA ? OutboundPriority.BULK : OutboundPriority.CONTROL;
        } catch (RuntimeException exception) {
            return OutboundPriority.CONTROL;
        }
    }

    private boolean scheduleDrain(BridgeSession session) {
        if (session == null) {
            return false;
        }
        if (!session.drainScheduled.compareAndSet(false, true)) {
            return true;
        }
        drainReady.offer(session);
        if (scheduleDrainTask()) {
            return true;
        }
        drainReady.remove(session);
        session.drainScheduled.set(false);
        failDrainScheduling(session);
        return false;
    }

    private boolean scheduleDrainTask() {
        if (!drainTaskScheduled.compareAndSet(false, true)) {
            return true;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, this::drainReadySessions);
            return true;
        } catch (RuntimeException exception) {
            drainTaskScheduled.set(false);
            BridgeSession session;
            while ((session = drainReady.poll()) != null) {
                session.drainScheduled.set(false);
                failDrainScheduling(session);
            }
            return false;
        }
    }

    private void failDrainScheduling(BridgeSession session) {
        synchronized (session.activationFence) {
            if (session.state.compareAndSet(BridgeSessionState.OPEN, BridgeSessionState.CLOSING)) {
                session.generation.incrementAndGet();
            }
            clearOutboundLocked(session);
        }
        if (Bukkit.isPrimaryThread()) {
            completeClose(session);
        }
    }

    private void drainReadySessions() {
        if (!Bukkit.isPrimaryThread()) {
            drainTaskScheduled.set(false);
            scheduleDrainTask();
            return;
        }
        long started = System.nanoTime();
        int packets = 0;
        long bytes = 0L;
        while (packets < MAX_DRAIN_PACKETS && bytes < MAX_DRAIN_BYTES
            && System.nanoTime() - started < MAX_DRAIN_NANOS) {
            BridgeSession session = drainReady.poll();
            if (session == null) {
                break;
            }
            session.drainScheduled.set(false);
            OutboundPacket packet = claimOutbound(session);
            if (packet == null) {
                if (isClosingAndIdle(session)) {
                    completeClose(session);
                }
                continue;
            }
            if (packets > 0 && packet.encoded().length > MAX_DRAIN_BYTES - bytes) {
                deferOutbound(session, packet);
                scheduleDrain(session);
                break;
            }
            boolean sent = false;
            try {
                if (session.player != null && session.player.isOnline()) {
                    session.player.sendPluginMessage(plugin, CHANNEL, packet.encoded());
                    sent = true;
                }
            } catch (RuntimeException exception) {
                sent = false;
            }
            packets++;
            bytes += packet.encoded().length;
            if (settleOutbound(session, packet, sent)) {
                completeClose(session);
            } else if (hasPendingOutbound(session)) {
                scheduleDrain(session);
            }
        }
        if (!drainReady.isEmpty()) {
            TemporaryLifecycleDiagnostics.event("bridge_drain", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null, "bridge", null,
                    null, null, null, null, null), "outcome", "retained", "packets", packets, "bytes", bytes,
                    "readySessions", true));
        }
        drainTaskScheduled.set(false);
        if (!drainReady.isEmpty()) {
            scheduleDrainTask();
        }
    }

    private OutboundPacket claimOutbound(BridgeSession session) {
        synchronized (session.activationFence) {
            if (session.state.get() == BridgeSessionState.CLOSED) {
                clearOutboundLocked(session);
                return null;
            }
            if (session.inFlight != null) {
                return null;
            }
            while (!session.outbound.isEmpty()) {
                OutboundPacket packet = session.outbound.removeFirst();
                session.outboundBytes -= packet.encoded().length;
                if (packet.priority() == OutboundPriority.BULK) {
                    session.bulkPackets--;
                    session.bulkBytes -= packet.encoded().length;
                }
                BridgeSessionState state = session.state.get();
                if (packet.generation() != session.generation.get()
                    || state == BridgeSessionState.OPEN && packet.type() == ReSyncBridgeEnvelope.CLOSE
                    || state == BridgeSessionState.CLOSING && packet.type() != ReSyncBridgeEnvelope.AUTH_RESULT
                    && packet.type() != ReSyncBridgeEnvelope.CLOSE) {
                    continue;
                }
                session.inFlight = packet;
                return packet;
            }
            return null;
        }
    }

    private boolean hasPendingOutbound(BridgeSession session) {
        synchronized (session.activationFence) {
            return session.state.get() != BridgeSessionState.CLOSED && session.inFlight == null
                && !session.outbound.isEmpty();
        }
    }

    private void deferOutbound(BridgeSession session, OutboundPacket packet) {
        synchronized (session.activationFence) {
            if (session.inFlight != packet) {
                return;
            }
            session.inFlight = null;
            if (session.state.get() == BridgeSessionState.CLOSED || packet.generation() != session.generation.get()) {
                return;
            }
            session.outbound.addFirst(packet);
            session.outboundBytes += packet.encoded().length;
            if (packet.priority() == OutboundPriority.BULK) {
                session.bulkPackets++;
                session.bulkBytes += packet.encoded().length;
            }
        }
    }

    private boolean isClosingAndIdle(BridgeSession session) {
        synchronized (session.activationFence) {
            return session.state.get() == BridgeSessionState.CLOSING && session.inFlight == null
                && session.outbound.isEmpty();
        }
    }

    private boolean settleOutbound(BridgeSession session, OutboundPacket packet, boolean sent) {
        synchronized (session.activationFence) {
            if (session.inFlight == packet) {
                session.inFlight = null;
            }
            if (session.state.get() == BridgeSessionState.CLOSED) {
                clearOutboundLocked(session);
                return true;
            }
            if (!sent) {
                transitionToClosingLocked(session);
                clearOutboundLocked(session);
                return true;
            }
            if (packet.type() == ReSyncBridgeEnvelope.CLOSE) {
                clearOutboundLocked(session);
                return true;
            }
            if (session.state.get() == BridgeSessionState.CLOSING && session.outbound.isEmpty()) {
                return true;
            }
            return false;
        }
    }

    private void transitionToClosingLocked(BridgeSession session) {
        if (session.state.compareAndSet(BridgeSessionState.OPEN, BridgeSessionState.CLOSING)) {
            session.generation.incrementAndGet();
        }
    }

    private void completeClose(BridgeSession session) {
        if (!Bukkit.isPrimaryThread()) {
            scheduleDrain(session);
            return;
        }
        ConnectionInfo connection;
        boolean notifyServer;
        synchronized (session.activationFence) {
            if (session.state.get() == BridgeSessionState.OPEN) {
                return;
            }
            session.state.compareAndSet(BridgeSessionState.CLOSING, BridgeSessionState.CLOSED);
            if (session.state.get() != BridgeSessionState.CLOSED) {
                return;
            }
            clearOutboundLocked(session);
            session.drainScheduled.set(false);
            session.chunker.clear();
            session.authorized = false;
            connection = session.connection;
            notifyServer = connection != null && session.cleanupDelivered.compareAndSet(false, true);
        }
        sessions.remove(session.sessionId, session);
        if (notifyServer) {
            closeBridgeConnection(connection);
        }
    }

    private static class BridgeSession {
        private final UUID sessionId;
        private final Player player;
        private final ReSyncBridgeChunker chunker = new ReSyncBridgeChunker();
        private final AtomicInteger sequence = new AtomicInteger(1);
        private final AtomicLong generation = new AtomicLong(1L);
        private final AtomicReference<BridgeSessionState> state = new AtomicReference<>(BridgeSessionState.OPEN);
        private final AtomicBoolean drainScheduled = new AtomicBoolean();
        private final AtomicBoolean cleanupDelivered = new AtomicBoolean();
        private final Object activationFence = new Object();
        private final Object inboundFence = new Object();
        private final ArrayDeque<OutboundPacket> outbound = new ArrayDeque<>();
        private volatile int bridgeProtocolVersion = BRIDGE_PROTOCOL_V2;
        private volatile ConnectionInfo connection;
        private volatile boolean authorized;
        private boolean openInFlight;
        private OutboundPacket inFlight;
        private long outboundBytes;
        private int bulkPackets;
        private long bulkBytes;

        private BridgeSession(UUID sessionId, Player player) {
            this.sessionId = sessionId;
            this.player = player;
        }

        private boolean isOpen() {
            return state.get() == BridgeSessionState.OPEN;
        }
    }

    private enum BridgeSessionState {
        OPEN,
        CLOSING,
        CLOSED
    }

    private enum OutboundPriority {
        CONTROL,
        BULK
    }

    private record OutboundPacket(long generation, byte type, OutboundPriority priority, byte[] encoded) {
    }

    private record BridgeHello(int protocolVersion, String modVersion, String serverAddress) {
    }
}
