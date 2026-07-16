package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class NetworkFrameCodec {
    private static final int MAGIC = 0x52534E57;
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_STRING_BYTES = 8192;
    private static final int MAXIMUM_SCOPES = 64;
    private final int maximumFrameBytes;
    private final int maximumPayloadBytes;

    public NetworkFrameCodec(int maximumFrameBytes, int maximumPayloadBytes) {
        if (maximumFrameBytes < 256 || maximumPayloadBytes < 0 || maximumPayloadBytes > maximumFrameBytes) {
            throw new IllegalArgumentException("Network Frame Limits Are Invalid");
        }
        this.maximumFrameBytes = maximumFrameBytes;
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    public byte[] encode(NetworkFrame frame) {
        byte[] payload = frame.payload();
        NetworkPayloads.requireLimit(payload, maximumPayloadBytes);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeShort(FORMAT_VERSION);
            output.writeInt(frame.context().protocolVersion());
            output.writeLong(frame.context().deadline());
            writeString(output, frame.context().networkId());
            writeString(output, frame.context().nodeId());
            writeString(output, frame.context().requestId());
            if (frame.context().authorizationScopes().size() > MAXIMUM_SCOPES) {
                throw new IllegalArgumentException("Network Request Has Too Many Authorization Scopes");
            }
            List<String> scopes = frame.context().authorizationScopes().stream().sorted().toList();
            output.writeShort(scopes.size());
            for (String scope : scopes) {
                writeString(output, scope);
            }
            writeString(output, frame.channel());
            output.writeShort(frame.type().code());
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > maximumFrameBytes) {
                throw new IllegalArgumentException("Encoded Network Frame Exceeds " + maximumFrameBytes + " Bytes");
            }
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Frame Failed", exception);
        }
    }

    public NetworkFrame decode(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded.length > maximumFrameBytes) {
            throw new IllegalArgumentException("Encoded Network Frame Size Is Invalid");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("Network Frame Magic Is Invalid");
            }
            int formatVersion = input.readUnsignedShort();
            if (formatVersion != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported Network Frame Format " + formatVersion);
            }
            int protocolVersion = input.readInt();
            long deadline = input.readLong();
            String networkId = readString(input);
            String nodeId = readString(input);
            String requestId = readString(input);
            int scopeCount = input.readUnsignedShort();
            if (scopeCount > MAXIMUM_SCOPES) {
                throw new IllegalArgumentException("Network Request Has Too Many Authorization Scopes");
            }
            Set<String> scopes = new LinkedHashSet<>();
            for (int index = 0; index < scopeCount; index++) {
                if (!scopes.add(readString(input))) {
                    throw new IllegalArgumentException("Network Request Has Duplicate Authorization Scopes");
                }
            }
            String channel = readString(input);
            NetworkFrameType type = NetworkFrameType.fromCode(input.readUnsignedShort());
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > maximumPayloadBytes || payloadLength > input.available()) {
                throw new IllegalArgumentException("Network Frame Payload Size Is Invalid");
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength || input.available() != 0) {
                throw new IllegalArgumentException("Network Frame Length Is Invalid");
            }
            return new NetworkFrame(new NetworkRequestContext(protocolVersion, networkId, nodeId, requestId, deadline, scopes), channel, type, payload);
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Frame Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Frame Failed", exception);
        }
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Frame String Is Too Long");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Frame String Size Is Invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Network Frame String Ended Early");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
