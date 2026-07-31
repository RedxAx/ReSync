package restudio.resync.modules.flow;

import com.google.gson.Gson;
import restudio.resync.core.CollaborationIdentity;
import restudio.resync.core.Session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FlowCollaborationService implements FlowResourceCommitListener {
    private static final int[] COLORS = {
        0xFFE85D75, 0xFF4D96FF, 0xFF6BCB77, 0xFFFFB84C, 0xFF9D4EDD, 0xFF00B4D8, 0xFFF28482, 0xFF43AA8B
    };
    private final Set<Session> sessions;
    private final FlowPacketSender sender;
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, Presence> presence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastMessages = new ConcurrentHashMap<>();
    private final ThreadLocal<Session> actor = new ThreadLocal<>();
    private final ThreadLocal<Boolean> resourceEventsSuppressed = ThreadLocal.withInitial(() -> false);

    public FlowCollaborationService(Set<Session> sessions, FlowPacketSender sender) {
        this.sessions = sessions;
        this.sender = sender;
    }

    public void subscribe(Session session) {
        presence.put(session.getSessionId(), presence(session, Update.inactive()));
        publishPresence();
    }

    public void cleanup(Session session) {
        presence.remove(session.getSessionId());
        lastMessages.remove(session.getSessionId());
        publishPresence();
    }

    public void handleUpdate(Session session, ByteBuffer buffer) {
        String json = StandardCharsets.UTF_8.decode(buffer).toString();
        Update update;
        try {
            update = gson.fromJson(json, Update.class);
        } catch (RuntimeException exception) {
            sender.sendError(session, "INVALID_PRESENCE", "Presence update is invalid");
            return;
        }
        presence.put(session.getSessionId(), presence(session, update != null ? update : Update.inactive()));
        publishPresence();
    }

    public void handleMessage(Session session, ByteBuffer buffer) {
        ChatRequest request;
        try {
            request = gson.fromJson(StandardCharsets.UTF_8.decode(buffer).toString(), ChatRequest.class);
        } catch (RuntimeException exception) {
            sender.sendError(session, "INVALID_CHAT", "Chat message is invalid");
            return;
        }
        String message = safe(request != null ? request.message() : "", 240);
        if (message.isBlank()) {
            sender.sendError(session, "INVALID_CHAT", "Chat message is empty");
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastMessages.get(session.getSessionId());
        if (previous != null && now - previous < 350L) {
            sender.sendError(session, "CHAT_RATE_LIMITED", "Wait before sending another message");
            return;
        }
        lastMessages.put(session.getSessionId(), now);
        Presence current = presence.get(session.getSessionId());
        if (current == null) {
            current = presence(session, Update.inactive());
        }
        ChatMessage chat = new ChatMessage(UUID.randomUUID().toString(), session.getSessionId(), session.getCollaborationIdentity(),
            current.resourceType(), current.resourceId(), current.color(), message, now);
        String json = gson.toJson(chat);
        for (Session recipient : sessions) {
            if (supports(recipient, "collaboration_chat")) {
                sender.sendCollaborationMessage(recipient, json);
            }
        }
    }

    public void enter(Session session) {
        actor.set(session);
    }

    public void exit() {
        actor.remove();
    }

    public void suppressResourceEvents(Runnable action) {
        boolean previous = resourceEventsSuppressed.get();
        resourceEventsSuppressed.set(true);
        try {
            action.run();
        } finally {
            resourceEventsSuppressed.set(previous);
        }
    }

    @Override
    public void saved(String type, String resourceId, String payload) {
        publishResource(actor.get(), false, type, resourceId, payload);
    }

    @Override
    public void saved(Session session, String type, String resourceId, String payload) {
        publishResource(session != null ? session : actor.get(), false, type, resourceId, payload);
    }

    @Override
    public void deleted(String type, String resourceId) {
        publishResource(actor.get(), true, type, resourceId, "");
    }

    @Override
    public void deleted(Session session, String type, String resourceId) {
        publishResource(session != null ? session : actor.get(), true, type, resourceId, "");
    }

    private Presence presence(Session session, Update update) {
        CollaborationIdentity identity = session.getCollaborationIdentity();
        boolean customColor = update.color() != null;
        int color = customColor ? 0xFF000000 | update.color() & 0x00FFFFFF : COLORS[Math.floorMod(identity.subjectId().hashCode(), COLORS.length)];
        return new Presence(session.getSessionId(), session.getClientId(), identity, safe(update.resourceType(), 64), safe(update.resourceId(), 256),
            safe(update.viewId(), 128), coordinate(update.x()), coordinate(update.y()),
            update.active(), update.typing(), color, customColor, System.currentTimeMillis());
    }

    private void publishPresence() {
        ArrayList<Presence> collaborators = new ArrayList<>(presence.values());
        collaborators.sort(Comparator.comparing(value -> value.identity().displayName(), String.CASE_INSENSITIVE_ORDER));
        List<Presence> snapshot = List.copyOf(collaborators);
        for (Session session : sessions) {
            if (supports(session, "collaboration_presence")) {
                List<String> selfSessionIds = List.of(session.getSessionId());
                List<Presence> remote = snapshot.stream()
                    .filter(value -> !value.sessionId().equals(session.getSessionId()))
                    .toList();
                sender.sendPresenceSnapshot(session, gson.toJson(new Snapshot(session.getSessionId(), session.getCollaborationIdentity(), selfSessionIds, remote)));
            }
        }
    }

    private void publishResource(Session source, boolean deleted, String type, String resourceId, String payload) {
        if (resourceEventsSuppressed.get()) {
            return;
        }
        ResourceEvent event = new ResourceEvent(safe(type, 64), safe(resourceId, 256), payload != null ? payload : "",
            source != null ? source.getSessionId() : "", source != null ? source.getCollaborationIdentity() : null, System.currentTimeMillis());
        String json = gson.toJson(event);
        for (Session session : sessions) {
            if (!supports(session, "resource_events")) {
                continue;
            }
            if (deleted) {
                sender.sendResourceDeleted(session, json);
            } else {
                sender.sendResourceChanged(session, json);
            }
        }
    }

    private boolean supports(Session session, String capability) {
        return session != null && session.getConnection().getClientCapabilities().contains(capability);
    }

    private String safe(String value, int limit) {
        String safe = value != null ? value.trim() : "";
        return safe.length() <= limit ? safe : safe.substring(0, limit);
    }

    private double coordinate(double value) {
        return Double.isFinite(value) ? Math.clamp(value, 0.0, 1.0) : 0.0;
    }

    private record Update(String resourceType, String resourceId, String viewId, double x, double y, boolean active, boolean typing, Integer color) {
        private static Update inactive() {
            return new Update("", "", "", 0.0, 0.0, false, false, null);
        }
    }

    private record Presence(String sessionId, String clientId, CollaborationIdentity identity, String resourceType, String resourceId, String viewId,
                            double x, double y, boolean active, boolean typing, int color, boolean customColor, long updatedAt) {
    }

    private record Snapshot(String selfSessionId, CollaborationIdentity selfIdentity, List<String> selfSessionIds, List<Presence> collaborators) {
    }

    private record ChatRequest(String message) {
    }

    private record ChatMessage(String id, String authorSessionId, CollaborationIdentity author, String resourceType,
                               String resourceId, int color, String message, long sentAt) {
    }

    private record ResourceEvent(String type, String resourceId, String payload, String authorSessionId,
                                 CollaborationIdentity author, long changedAt) {
    }
}
