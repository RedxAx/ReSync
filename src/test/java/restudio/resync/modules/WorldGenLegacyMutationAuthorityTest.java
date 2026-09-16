package restudio.resync.modules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.server.AuthorityEpoch;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorldGenLegacyMutationAuthorityTest {
    @Test
    void graphOnlySaveFailsClosedWithCorrelatedTerminalResponse() throws Exception {
        CompressionPool compression = new CompressionPool(1, 1);
        try {
            Codec codec = new Codec(compression);
            WorldGenModule module = new WorldGenModule();
            set(module, "codec", codec);
            set(module, "channelId", 9);
            set(module, "authorityEpoch", AuthorityEpoch.fixed(7L));
            List<byte[]> frames = new ArrayList<>();
            FrameSender sender = new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    frames.add(frame);
                }

                @Override
                public void close(int code, String reason) {
                }
            };
            Session session = new Session("session", "client", new ConnectionInfo(null, sender, 1));
            String requestId = "worldGenSave:legacy:" + UUID.fromString("10000000-0000-4000-8000-000000000001");
            JsonObject envelope = new JsonObject();
            envelope.addProperty("action", "worldGenSave");
            envelope.addProperty("requestId", requestId);
            envelope.addProperty("mutationId", "20000000-0000-4000-8000-000000000002");
            envelope.addProperty("operationId", "30000000-0000-4000-8000-000000000003");
            envelope.addProperty("resourceId", "legacy");
            envelope.addProperty("authorityEpoch", 7L);
            envelope.addProperty("expectedRevision", 0L);
            envelope.add("data", JsonParser.parseString("{\"nodes\":{},\"connections\":[]}"));
            byte[] requestBytes = requestId.getBytes(StandardCharsets.UTF_8);
            byte[] envelopeBytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
            ByteBuffer packet = ByteBuffer.allocate(1 + Integer.BYTES + requestBytes.length + envelopeBytes.length);
            packet.put((byte) 0x20);
            packet.putInt(requestBytes.length);
            packet.put(requestBytes);
            packet.put(envelopeBytes);
            DataMessage request = new DataMessage();
            request.setPayload(packet.array());

            module.onData(session, request);

            assertEquals(1, frames.size());
            DataMessage response = (DataMessage) codec.decodePayload(codec.decodeFrame(frames.getFirst()));
            ByteBuffer responsePayload = ByteBuffer.wrap(response.getPayload());
            assertEquals(0x23, Byte.toUnsignedInt(responsePayload.get()));
            byte[] responseBytes = new byte[responsePayload.remaining()];
            responsePayload.get(responseBytes);
            JsonObject responseEnvelope = JsonParser.parseString(new String(responseBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(7L, responseEnvelope.get("authorityEpoch").getAsLong());
            JsonObject data = responseEnvelope.getAsJsonObject("data");
            assertEquals("worldGenSave", data.get("action").getAsString());
            assertEquals("legacy", data.get("resourceId").getAsString());
            assertEquals(requestId, data.get("requestId").getAsString());
            assertEquals("20000000-0000-4000-8000-000000000002", data.get("mutationId").getAsString());
            assertEquals("30000000-0000-4000-8000-000000000003", data.get("operationId").getAsString());
            assertEquals("error", data.get("status").getAsString());
            assertEquals("Legacy WorldGen Graph Save Is Retired; Use Typed WorldGen Project Mutation",
                data.get("message").getAsString());
            assertFalse(data.get("committed").getAsBoolean());
            assertFalse(data.get("forceRefresh").getAsBoolean());
        } finally {
            compression.close();
        }
    }

    private void set(WorldGenModule module, String fieldName, Object value) throws Exception {
        Field field = WorldGenModule.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(module, value);
    }
}
