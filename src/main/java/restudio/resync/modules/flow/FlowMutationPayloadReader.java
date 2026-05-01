package restudio.resync.modules.flow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class FlowMutationPayloadReader {
    private FlowMutationPayloadReader() {
    }

    public static FlowMutationPayload read(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return new FlowMutationPayload(null, "");
        }
        byte first = buffer.get(buffer.position());
        if (first == '{' || first == '[' || first == '"' || first == '-' || Character.isDigit((char) first)) {
            return new FlowMutationPayload(null, readRemaining(buffer));
        }
        if (buffer.remaining() < Integer.BYTES) {
            return new FlowMutationPayload(null, readRemaining(buffer));
        }
        int start = buffer.position();
        int requestIdLength = buffer.getInt();
        if (requestIdLength <= 0 || requestIdLength > 256 || requestIdLength > buffer.remaining()) {
            buffer.position(start);
            return new FlowMutationPayload(null, readRemaining(buffer));
        }
        byte[] requestBytes = new byte[requestIdLength];
        buffer.get(requestBytes);
        return new FlowMutationPayload(new String(requestBytes, StandardCharsets.UTF_8), readRemaining(buffer));
    }

    private static String readRemaining(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
