package restudio.resync.modules.flow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.core.CollaborationIdentity;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCollaborationServiceTest {
    @Test
    void broadcastsChatToEveryCapableFlowSession() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session senderSession = session("sender");
        Session recipientSession = session("recipient");
        sessions.add(senderSession);
        sessions.add(recipientSession);
        RecordingSender sender = new RecordingSender(sessions);
        FlowCollaborationService service = new FlowCollaborationService(sessions, sender);

        service.handleMessage(senderSession, ByteBuffer.wrap("""
            {"message":"Hello"}
            """.getBytes(StandardCharsets.UTF_8)));

        assertEquals(List.of("recipient", "sender"), sender.recipients.stream().sorted().toList());
        assertTrue(sender.payloads.stream().allMatch(payload -> payload.contains("\"message\":\"Hello\"")));
    }

    @Test
    void publishesValidatedCustomPresenceColor() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session session = session("sender", "collaboration_presence");
        Session observer = session("observer", "collaboration_presence");
        session.setCollaborationIdentity(new CollaborationIdentity("sender", "Sender", "", "restudio"));
        observer.setCollaborationIdentity(new CollaborationIdentity("observer", "Observer", "", "restudio"));
        sessions.addAll(List.of(session, observer));
        RecordingSender sender = new RecordingSender(sessions);
        FlowCollaborationService service = new FlowCollaborationService(sessions, sender);

        service.handleUpdate(session, ByteBuffer.wrap("""
            {"active":true,"color":305419896}
            """.getBytes(StandardCharsets.UTF_8)));

        assertTrue(sender.presenceByRecipient.get("observer").contains("\"color\":" + 0xFF345678));
        assertTrue(sender.presenceByRecipient.get("observer").contains("\"customColor\":true"));
    }

    @Test
    void keepsSeparateSessionsVisibleForTheSameAccount() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session direct = session("direct", "collaboration_presence");
        Session bridge = session("bridge", "collaboration_presence");
        Session other = session("other", "collaboration_presence");
        CollaborationIdentity owner = new CollaborationIdentity("user", "Alex", "", "restudio");
        direct.setCollaborationIdentity(owner);
        bridge.setCollaborationIdentity(new CollaborationIdentity("user", "Alex", "", "minecraft"));
        other.setCollaborationIdentity(new CollaborationIdentity("other", "Sam", "", "restudio"));
        sessions.addAll(List.of(direct, bridge, other));
        RecordingSender sender = new RecordingSender(sessions);
        FlowCollaborationService service = new FlowCollaborationService(sessions, sender);

        service.subscribe(direct);
        service.subscribe(bridge);
        service.subscribe(other);

        JsonObject snapshot = JsonParser.parseString(sender.presenceByRecipient.get("direct")).getAsJsonObject();
        assertEquals("user", snapshot.getAsJsonObject("selfIdentity").get("subjectId").getAsString());
        assertEquals(Set.of("direct"), snapshot.getAsJsonArray("selfSessionIds").asList().stream()
            .map(value -> value.getAsString()).collect(Collectors.toSet()));
        assertEquals(Set.of("bridge", "other"), snapshot.getAsJsonArray("collaborators").asList().stream()
            .map(value -> value.getAsJsonObject().get("sessionId").getAsString()).collect(Collectors.toSet()));
        assertTrue(sender.presenceByRecipient.get("direct").contains("\"sessionId\":\"bridge\""));
    }

    @Test
    void linksDirectAndMinecraftBridgeSessionsForTheSameClient() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session direct = session("direct", "remotely-device", "collaboration_presence");
        Session bridge = session("bridge", "bridge:15e748bd-d368-4bf8-a846-0936d51f405f:remotely-device", "collaboration_presence");
        Session other = session("other", "bridge:32740ba5-aa56-4c20-bd1f-d56e011dd93b:other-device", "collaboration_presence");
        CollaborationIdentity owner = new CollaborationIdentity("user", "Alex", "", "restudio");
        direct.setCollaborationIdentity(owner);
        bridge.setCollaborationIdentity(new CollaborationIdentity("user", "Alex", "", "minecraft"));
        other.setCollaborationIdentity(new CollaborationIdentity("other", "Sam", "", "minecraft"));
        sessions.addAll(List.of(direct, bridge, other));
        RecordingSender sender = new RecordingSender(sessions);
        FlowCollaborationService service = new FlowCollaborationService(sessions, sender);

        service.subscribe(direct);
        service.subscribe(bridge);
        service.subscribe(other);

        JsonObject directSnapshot = JsonParser.parseString(sender.presenceByRecipient.get("direct")).getAsJsonObject();
        assertEquals(Set.of("direct", "bridge"), directSnapshot.getAsJsonArray("selfSessionIds").asList().stream()
            .map(value -> value.getAsString()).collect(Collectors.toSet()));
        assertEquals(Set.of("other"), directSnapshot.getAsJsonArray("collaborators").asList().stream()
            .map(value -> value.getAsJsonObject().get("sessionId").getAsString()).collect(Collectors.toSet()));

        JsonObject bridgeSnapshot = JsonParser.parseString(sender.presenceByRecipient.get("bridge")).getAsJsonObject();
        assertEquals(Set.of("direct", "bridge"), bridgeSnapshot.getAsJsonArray("selfSessionIds").asList().stream()
            .map(value -> value.getAsString()).collect(Collectors.toSet()));
        assertEquals(Set.of(), bridgeSnapshot.getAsJsonArray("collaborators").asList().stream()
            .map(value -> value.getAsJsonObject().get("sessionId").getAsString()).collect(Collectors.toSet()));
    }

    @Test
    void minecraftPlayersOnlySeeRemotelyAppCollaborators() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session firstPlayer = session("first-player", "bridge:15e748bd-d368-4bf8-a846-0936d51f405f:first-device", "collaboration_presence");
        Session secondPlayer = session("second-player", "bridge:32740ba5-aa56-4c20-bd1f-d56e011dd93b:second-device", "collaboration_presence");
        Session app = session("app", "remotely-app", "collaboration_presence");
        firstPlayer.setCollaborationIdentity(new CollaborationIdentity("first", "First", "", "minecraft"));
        secondPlayer.setCollaborationIdentity(new CollaborationIdentity("second", "Second", "", "minecraft"));
        app.setCollaborationIdentity(new CollaborationIdentity("app", "App", "", "restudio"));
        sessions.addAll(List.of(firstPlayer, secondPlayer, app));
        RecordingSender sender = new RecordingSender(sessions);
        FlowCollaborationService service = new FlowCollaborationService(sessions, sender);

        service.subscribe(firstPlayer);
        service.subscribe(secondPlayer);
        service.subscribe(app);

        assertEquals(Set.of("app"), collaboratorIds(sender, "first-player"));
        assertEquals(Set.of("app"), collaboratorIds(sender, "second-player"));
        assertEquals(Set.of("first-player", "second-player"), collaboratorIds(sender, "app"));
    }

    @Test
    void publishesOrderedPresenceForMinecraftBridgeAndDistinctAppClient() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session player = session("player", "bridge:15e748bd-d368-4bf8-a846-0936d51f405f:remotely-mod", "collaboration_presence");
        Session app = session("app", "remotely-app", "collaboration_presence");
        player.setCollaborationIdentity(new CollaborationIdentity("player", "Alex", "", "minecraft"));
        app.setCollaborationIdentity(new CollaborationIdentity("app-user", "Sam", "", "restudio"));
        sessions.addAll(List.of(player, app));
        RecordingSender sender = new RecordingSender(sessions);
        FlowCollaborationService service = new FlowCollaborationService(sessions, sender);

        service.subscribe(player);
        long firstRevision = JsonParser.parseString(sender.presenceByRecipient.get("player")).getAsJsonObject()
            .get("revision").getAsLong();
        service.subscribe(app);

        JsonObject playerSnapshot = JsonParser.parseString(sender.presenceByRecipient.get("player")).getAsJsonObject();
        JsonObject appSnapshot = JsonParser.parseString(sender.presenceByRecipient.get("app")).getAsJsonObject();
        assertTrue(playerSnapshot.get("revision").getAsLong() > firstRevision);
        assertEquals(playerSnapshot.get("revision").getAsLong(), appSnapshot.get("revision").getAsLong());
        assertEquals(Set.of("player"), playerSnapshot.getAsJsonArray("selfSessionIds").asList().stream()
            .map(value -> value.getAsString()).collect(Collectors.toSet()));
        assertEquals(Set.of("app"), appSnapshot.getAsJsonArray("selfSessionIds").asList().stream()
            .map(value -> value.getAsString()).collect(Collectors.toSet()));
        assertEquals("app", playerSnapshot.getAsJsonArray("collaborators").get(0).getAsJsonObject().get("sessionId").getAsString());
        assertEquals("player", appSnapshot.getAsJsonArray("collaborators").get(0).getAsJsonObject().get("sessionId").getAsString());
    }

    @Test
    void publishesTheExplicitCommitAuthorAcrossThreads() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session session = session("direct", "resource_events");
        session.setCollaborationIdentity(new CollaborationIdentity("user", "Alex", "", "restudio"));
        sessions.add(session);
        RecordingSender sender = new RecordingSender(sessions);
        FlowCollaborationService service = new FlowCollaborationService(sessions, sender);

        service.saved(session, "flow", "main", "{}");

        JsonObject event = JsonParser.parseString(sender.resourcePayloads.getFirst()).getAsJsonObject();
        assertEquals("direct", event.get("authorSessionId").getAsString());
        assertEquals("user", event.getAsJsonObject("author").get("subjectId").getAsString());
    }

    private Session session(String id) {
        return session(id, "collaboration_chat");
    }

    private Set<String> collaboratorIds(RecordingSender sender, String sessionId) {
        JsonObject snapshot = JsonParser.parseString(sender.presenceByRecipient.get(sessionId)).getAsJsonObject();
        return snapshot.getAsJsonArray("collaborators").asList().stream()
            .map(value -> value.getAsJsonObject().get("sessionId").getAsString()).collect(Collectors.toSet());
    }

    private Session session(String id, String capability) {
        return session(id, id, capability);
    }

    private Session session(String id, String clientId, String capability) {
        ConnectionInfo connection = new ConnectionInfo(null, id.hashCode());
        connection.setClientCapabilities(Set.of(capability));
        return new Session(id, clientId, connection);
    }

    private static final class RecordingSender extends FlowPacketSender {
        private final List<String> recipients = new ArrayList<>();
        private final List<String> payloads = new ArrayList<>();
        private final List<String> presencePayloads = new ArrayList<>();
        private final List<String> resourcePayloads = new ArrayList<>();
        private final Map<String, String> presenceByRecipient = new ConcurrentHashMap<>();

        private RecordingSender(Set<Session> sessions) {
            super(null, 0, sessions);
        }

        @Override
        public void sendCollaborationMessage(Session session, String json) {
            recipients.add(session.getSessionId());
            payloads.add(json);
        }

        @Override
        public void sendPresenceSnapshot(Session session, String json) {
            presencePayloads.add(json);
            presenceByRecipient.put(session.getSessionId(), json);
        }

        @Override
        public void sendResourceChanged(Session session, String json) {
            resourcePayloads.add(json);
        }
    }
}
