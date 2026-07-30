package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        sessions.add(session);
        RecordingSender sender = new RecordingSender(sessions);
        FlowCollaborationService service = new FlowCollaborationService(sessions, sender);

        service.handleUpdate(session, ByteBuffer.wrap("""
            {"active":true,"color":305419896}
            """.getBytes(StandardCharsets.UTF_8)));

        assertTrue(sender.presencePayloads.getFirst().contains("\"color\":" + 0xFF345678));
        assertTrue(sender.presencePayloads.getFirst().contains("\"customColor\":true"));
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
        }
    }
}
