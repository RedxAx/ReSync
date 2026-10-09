package restudio.resync.velocity;

import org.junit.jupiter.api.Test;
import restudio.resync.network.NetworkChannels;
import restudio.resync.network.NetworkEditorChunk;
import restudio.resync.network.NetworkEditorClose;
import restudio.resync.network.NetworkEditorCodec;
import restudio.resync.network.NetworkEditorOpen;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameType;
import restudio.resync.network.NetworkRequestContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityEditorGatewayTest {
    @Test
    void forwardsNativeBytesAndReceiptsOnlyWhileBothSessionsAndEditorGrantRemainCurrent() {
        Wire wire = new Wire();
        VelocityEditorGateway gateway = new VelocityEditorGateway(wire);
        VelocityEditorGateway.Endpoint observer = wire.add("operator");
        VelocityEditorGateway.Endpoint backend = wire.add("backend");
        UUID first = UUID.randomUUID();
        gateway.receive(observer, frame(NetworkFrameType.EDITOR_OPEN, NetworkEditorCodec.encodeOpen(new NetworkEditorOpen(first, "backend"))));
        assertEquals(backend, wire.last().target());
        gateway.receive(backend, frame(NetworkFrameType.EDITOR_OPENED, NetworkEditorCodec.encodeOpened(first)));
        assertEquals(observer, wire.last().target());
        byte[] nativeBytes = {1, 2, 3};
        gateway.receive(observer, frame(NetworkFrameType.EDITOR_DATA, NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(first, 0, 0, 3, nativeBytes))));
        assertEquals(backend, wire.last().target());
        assertArrayEquals(nativeBytes, NetworkEditorCodec.decodeChunk(wire.last().payload()).bytes());
        gateway.receive(backend, frame(NetworkFrameType.EDITOR_DATA, NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(first, 0, 0, 0, new byte[0]))));
        assertEquals(observer, wire.last().target());
        assertTrue(NetworkEditorCodec.decodeChunk(wire.last().payload()).receipt());
        wire.editors = false;
        gateway.receive(observer, frame(NetworkFrameType.EDITOR_DATA, NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(first, 1, 0, 3, nativeBytes))));
        assertEquals(NetworkFrameType.EDITOR_CLOSE, wire.last().type());
        assertEquals(first, NetworkEditorCodec.decodeClose(wire.last().payload()).tunnelId());
        wire.editors = true;
        UUID second = UUID.randomUUID();
        gateway.receive(observer, frame(NetworkFrameType.EDITOR_OPEN, NetworkEditorCodec.encodeOpen(new NetworkEditorOpen(second, "backend"))));
        wire.add("backend");
        gateway.expire();
        assertEquals(observer, wire.last().target());
        NetworkEditorClose closed = NetworkEditorCodec.decodeClose(wire.last().payload());
        assertEquals(second, closed.tunnelId());
        int delivered = wire.sent.size();
        gateway.receive(backend, frame(NetworkFrameType.EDITOR_DATA, NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(second, 0, 0, 3, nativeBytes))));
        assertEquals(delivered, wire.sent.size());
    }

    private static NetworkFrame frame(NetworkFrameType type, byte[] payload) {
        return new NetworkFrame(new NetworkRequestContext(1, "network", "node", "request", 0, Set.of()), NetworkChannels.EDITOR, type, payload);
    }

    private record Sent(VelocityEditorGateway.Endpoint target, NetworkFrameType type, byte[] payload) {
    }

    private static final class Wire implements VelocityEditorGateway.Transport {
        private final Map<String, VelocityEditorGateway.Endpoint> endpoints = new HashMap<>();
        private final List<Sent> sent = new ArrayList<>();
        private boolean editors = true;

        private VelocityEditorGateway.Endpoint add(String nodeId) {
            VelocityEditorGateway.Endpoint endpoint = new VelocityEditorGateway.Endpoint(null, new Object(), nodeId);
            endpoints.put(nodeId, endpoint);
            return endpoint;
        }

        private Sent last() {
            return sent.getLast();
        }

        @Override
        public boolean active(VelocityEditorGateway.Endpoint endpoint) {
            return endpoint.equals(endpoints.get(endpoint.nodeId()));
        }

        @Override
        public boolean canOpen(VelocityEditorGateway.Endpoint endpoint) {
            return editors && "operator".equals(endpoint.nodeId());
        }

        @Override
        public VelocityEditorGateway.Endpoint backend(String nodeId) {
            return endpoints.get(nodeId);
        }

        @Override
        public boolean send(VelocityEditorGateway.Endpoint endpoint, NetworkFrameType type, UUID id, byte[] payload) {
            if (!active(endpoint)) return false;
            sent.add(new Sent(endpoint, type, payload));
            return true;
        }
    }
}
