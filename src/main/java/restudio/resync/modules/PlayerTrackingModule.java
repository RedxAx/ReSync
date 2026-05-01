package restudio.resync.modules;

import com.google.gson.Gson;
import restudio.resync.Log;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import restudio.flow.data.FlowGraph;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowExecutionListener;
import restudio.resync.player.PlayerDossier;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.player.PlayerTrackingPrivacyPolicy;
import restudio.resync.player.PlayerTrackingListener;
import restudio.resync.player.PlayerTrackingService;
import restudio.resync.player.PlayerTrackingUpdate;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTrackingModule implements Module, Listener, PlayerTrackingListener, FlowExecutionListener {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("playerTracking", "PlayerTracking", "player_tracking").withDependencies("flow");
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final Gson gson = new Gson();
    private ModuleContext context;
    private Codec codec;
    private int channelId;
    private PlayerTrackingService trackingService;
    private PlayerSessionLinkService sessionLinkService;
    private final PlayerTrackingPrivacyPolicy privacyPolicy = new PlayerTrackingPrivacyPolicy();

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
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        subscribedSessions.add(session);
        sendSnapshot(session);
        handleSubscribeBinding(session, req.getData());
    }

    @Override
    public void onUnsubscribe(Session session, UnsubscribeRequest req) {
        subscribedSessions.remove(session);
    }

    @Override
    public void cleanup(Session session) {
        subscribedSessions.remove(session);
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
            switch (request.action) {
                case "snapshot" -> sendSnapshot(session);
                case "dossier" -> sendDossier(session, request.playerId);
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
        List<PlayerDossier> dossiers = new ArrayList<>(trackingService.getDossiers());
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
            send(session, PlayerTrackingUpdate.delta("dossier", dossier));
        }
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
        for (Session session : subscribedSessions) {
            send(session, update);
        }
    }

    private void send(Session session, PlayerTrackingUpdate update) {
        if (session == null || update == null || !session.getConnection().getWebSocket().isOpen()) {
            return;
        }
        DataMessage message = new DataMessage();
        message.setChannel(channelId);
        message.setPayload(gson.toJson(update).getBytes(StandardCharsets.UTF_8));
        codec.sendMessage(session.getConnection().getWebSocket(), message, channelId, true);
    }

    private static class TrackingRequest {
        private String action;
        private String playerId;
    }
}
