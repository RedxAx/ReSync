package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkFrameCodecTest {
    private final NetworkFrameCodec codec = new NetworkFrameCodec(4096, 2048);

    @Test
    void preservesTheSharedNetworkEnvelope() {
        NetworkRequestContext context = new NetworkRequestContext(1, "network", "proxy", "request", 10_000, Set.of("presence.write", "nodes.read"));
        NetworkFrame frame = new NetworkFrame(context, NetworkChannels.PRESENCE, NetworkFrameType.PRESENCE_DELTA, "payload".getBytes(StandardCharsets.UTF_8));

        assertEquals(frame, codec.decode(codec.encode(frame)));
    }

    @Test
    void rejectsOversizedAndTrailingPayloads() {
        NetworkRequestContext context = new NetworkRequestContext(1, "network", "proxy", "request", 10_000, Set.of());
        NetworkFrame oversized = new NetworkFrame(context, NetworkChannels.PRESENCE, NetworkFrameType.PRESENCE_DELTA, new byte[2049]);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(oversized));

        byte[] valid = codec.encode(new NetworkFrame(context, NetworkChannels.CONTROL, NetworkFrameType.HEARTBEAT, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Arrays.copyOf(valid, valid.length + 1)));
    }
    @Test
    void editorChunksAndFirstFrameAuthenticationRemainBoundedAndOrdered() {
        UUID id = UUID.randomUUID();
        byte[] bytes = {1, 2};
        NetworkEditorChunk first = new NetworkEditorChunk(id, 0, 0, 4, bytes);
        bytes[0] = 9;
        first.bytes()[0] = 8;
        NetworkEditorAssembler assembler = new NetworkEditorAssembler();
        assertNull(assembler.accept(NetworkEditorCodec.decodeChunk(NetworkEditorCodec.encodeChunk(first))));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(new NetworkEditorChunk(id, 1, 2, 4, new byte[]{3, 4})));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, assembler.accept(new NetworkEditorChunk(id, 0, 2, 4, new byte[]{3, 4})));
        assertThrows(IllegalArgumentException.class, () -> new NetworkEditorChunk(id, 1, 0, NetworkEditorChunk.MAXIMUM_FRAME_BYTES + 1, new byte[]{1}));
        NetworkAuthentication auth = new NetworkAuthentication("network", "operator", "credential", "", "");
        byte[] encoded = NetworkAuthenticationCodec.encode(auth);
        assertEquals(auth, NetworkAuthenticationCodec.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> NetworkAuthenticationCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
    }

}
