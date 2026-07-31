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

    private Session session(String id, String capability) {
        ConnectionInfo connection = new ConnectionInfo(null, id.hashCode());
        connection.setClientCapabilities(Set.of(capability));
        return new Session(id, id, connection);
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
