package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
