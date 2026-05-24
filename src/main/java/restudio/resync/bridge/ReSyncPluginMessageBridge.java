package restudio.resync.bridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.protocol.FrameSender;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ReSyncPluginMessageBridge implements PluginMessageListener, Listener {
    public static final String CHANNEL = "resync:bridge";
    private final ReSync plugin;
    private final Map<UUID, BridgeSession> sessions = new ConcurrentHashMap<>();
    private static final int BRIDGE_PROTOCOL = 1;

    public ReSyncPluginMessageBridge(ReSync plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Log.info("[ReSync] Vanilla bridge registered on " + CHANNEL);
    }

    public void unregister() {
        for (BridgeSession session : sessions.values()) {
            close(session);
        }
        sessions.clear();
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
            BridgeSession session = sessions.computeIfAbsent(envelope.sessionId(), id -> new BridgeSession(id, player));
            if (!session.player.getUniqueId().equals(player.getUniqueId())) {
                return;
            }
            byte[] payload = session.chunker.accept(envelope);
            if (payload == null) {
                return;
            }
            if (envelope.type() == ReSyncBridgeEnvelope.HELLO) {
                handleHello(session, payload);
            } else if (envelope.type() == ReSyncBridgeEnvelope.DATA && session.authorized) {
                ensureConnection(session);
                plugin.getReSyncServer().onBridgeMessage(session.connection, player, payload);
            } else if (envelope.type() == ReSyncBridgeEnvelope.CLOSE) {
                close(session);
            }
        } catch (Exception ex) {
            Log.warn("[ReSync] Vanilla bridge rejected packet from " + player.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.values().removeIf(session -> {
            if (session.player.getUniqueId().equals(event.getPlayer().getUniqueId())) {
                close(session);
                return true;
            }
            return false;
        });
    }

    private void handleHello(BridgeSession session, byte[] payload) {
        Player player = session.player;
        BridgeHello hello = readHello(payload);
        Log.fine("Vanilla bridge hello from " + player.getName() + " mod=" + hello.modVersion + " server=" + hello.serverAddress);
        if (hello.protocolVersion != BRIDGE_PROTOCOL) {
            Log.warn("[ReSync] Vanilla bridge rejected " + player.getName() + ": unsupported protocol " + hello.protocolVersion);
            sendAuth(session, false, "Unsupported Bridge");
            close(session);
            return;
        }
        if (!player.isOp() && !player.hasPermission("resync.api.access")) {
            Log.warn("[ReSync] Vanilla bridge rejected " + player.getName() + ": no permission");
            sendAuth(session, false, "No Permission");
            close(session);
            return;
        }
        session.authorized = true;
        sendAuth(session, true, Bukkit.getServer().getName());
        Log.fine("Vanilla bridge authorized " + player.getName());
    }

    private void ensureConnection(BridgeSession session) {
        if (session.connection != null) {
            return;
        }
        Player player = session.player;
        session.connection = plugin.getReSyncServer().onBridgeOpen(new FrameSender() {
            @Override
            public void send(byte[] frame) {
                session.chunker.send(session.sessionId, session.sequence.getAndIncrement(), ReSyncBridgeEnvelope.DATA, frame, envelope -> player.sendPluginMessage(plugin, CHANNEL, envelope.encode()));
            }

            @Override
            public void close(int code, String reason) {
                session.chunker.send(session.sessionId, session.sequence.getAndIncrement(), ReSyncBridgeEnvelope.CLOSE, reason.getBytes(StandardCharsets.UTF_8), envelope -> player.sendPluginMessage(plugin, CHANNEL, envelope.encode()));
                ReSyncPluginMessageBridge.this.close(session);
            }
        });
    }

    private void sendAuth(BridgeSession session, boolean success, String displayName) {
        byte[] nameBytes = (displayName == null ? "Live Server" : displayName).getBytes(StandardCharsets.UTF_8);
        Map<String, Integer> channels = success && plugin.getReSyncServer() != null ? plugin.getReSyncServer().getBridgeChannels() : Map.of();
        int size = 1 + 4 + 4 + nameBytes.length + 4;
        for (String channel : channels.keySet()) {
            byte[] bytes = channel.getBytes(StandardCharsets.UTF_8);
            size += 4 + bytes.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put((byte) (success ? 1 : 0));
        buffer.putInt(2);
        buffer.putInt(nameBytes.length);
        buffer.put(nameBytes);
        buffer.putInt(channels.size());
        for (String channel : channels.keySet()) {
            putString(buffer, channel);
        }
        session.chunker.send(session.sessionId, session.sequence.getAndIncrement(), ReSyncBridgeEnvelope.AUTH_RESULT, buffer.array(), envelope -> {
            byte[] encoded = envelope.encode();
            session.player.sendPluginMessage(plugin, CHANNEL, encoded);
            Log.fine("Vanilla bridge auth result sent to " + session.player.getName() + " success=" + success + " bytes=" + encoded.length + " channels=" + channels.size());
        });
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

    private void putString(ByteBuffer buffer, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    private void close(BridgeSession session) {
        if (session == null || session.closed) {
            return;
        }
        session.closed = true;
        sessions.remove(session.sessionId, session);
        if (session.connection != null && plugin.getReSyncServer() != null) {
            plugin.getReSyncServer().onBridgeClose(session.connection);
        }
    }

    private static class BridgeSession {
        private final UUID sessionId;
        private final Player player;
        private final ReSyncBridgeChunker chunker = new ReSyncBridgeChunker();
        private final AtomicInteger sequence = new AtomicInteger(1);
        private ConnectionInfo connection;
        private boolean authorized;
        private boolean closed;

        private BridgeSession(UUID sessionId, Player player) {
            this.sessionId = sessionId;
            this.player = player;
        }
    }

    private record BridgeHello(int protocolVersion, String modVersion, String serverAddress) {
    }
}
