package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.core.Session;
import restudio.resync.contracts.ReSyncProtocolContract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowPacketSenderTest {
    @Test
    void graphSaveAcknowledgementsUseTheirResourcePacket() {
        RecordingSender sender = new RecordingSender();

        sender.sendGraphSaveAck(null, "function", "requestMessage", "save-1", 13L, "hash");

        ByteBuffer payload = ByteBuffer.wrap(sender.payload);
        assertEquals(ReSyncProtocolContract.resource("function").flowPackets().saveAck(), payload.get());
        assertEquals("requestMessage", readString(payload));
        assertEquals("save-1", readString(payload));
        assertEquals(13L, payload.getLong());
        assertEquals("hash", readString(payload));

        sender.sendGraphSaveAck(null, "command", "race", "save-2", 4L, "next");
        assertEquals(ReSyncProtocolContract.resource("command").flowPackets().saveAck(), sender.payload[0]);
    }

    private static String readString(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.getInt()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class RecordingSender extends FlowPacketSender {
        private byte[] payload = new byte[0];

        private RecordingSender() {
            super(null, 0, Set.of());
        }

        @Override
        public void sendRaw(Session session, byte[] payload, boolean compress) {
            this.payload = payload;
        }
    }
}
