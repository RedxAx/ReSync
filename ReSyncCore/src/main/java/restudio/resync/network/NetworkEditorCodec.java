package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Arrays;

public final class NetworkEditorCodec {
    private NetworkEditorCodec() {
    }

    public static byte[] encodeOpen(NetworkEditorOpen value) {
        return encode(output -> {
            uuid(output, value.tunnelId());
            string(output, value.targetNodeId(), 512);
        });
    }

    public static NetworkEditorOpen decodeOpen(byte[] bytes) {
        return decode(bytes, input -> new NetworkEditorOpen(uuid(input), string(input, 512)));
    }

    public static byte[] encodeOpened(UUID tunnelId) {
        return encode(output -> uuid(output, tunnelId));
    }

    public static UUID decodeOpened(byte[] bytes) {
        return decode(bytes, NetworkEditorCodec::uuid);
    }

    public static byte[] encodeClose(NetworkEditorClose value) {
        return encode(output -> {
            uuid(output, value.tunnelId());
            output.writeInt(value.code());
            string(output, value.reason(), 1024);
        });
    }

    public static NetworkEditorClose decodeClose(byte[] bytes) {
        return decode(bytes, input -> new NetworkEditorClose(uuid(input), input.readInt(), string(input, 1024)));
    }

    public static byte[] encodeChunk(NetworkEditorChunk value) {
        return encode(output -> {
            uuid(output, value.tunnelId());
            output.writeLong(value.sequence());
            output.writeInt(value.offset());
            output.writeInt(value.totalBytes());
            byte[] bytes = value.bytes();
            output.writeInt(bytes.length);
            output.write(bytes);
        });
    }

    public static NetworkEditorChunk decodeChunk(byte[] bytes) {
        return decode(bytes, input -> {
            UUID tunnelId = uuid(input);
            long sequence = input.readLong();
            int offset = input.readInt();
            int total = input.readInt();
            int length = input.readInt();
            if (length < 0 || length > NetworkEditorChunk.MAXIMUM_CHUNK_BYTES || length > input.available()) {
                throw new IllegalArgumentException("Editor Chunk Length Is Invalid");
            }
            return new NetworkEditorChunk(tunnelId, sequence, offset, total, input.readNBytes(length));
        });
    }

    static byte[] encode(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(1);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Network Editor Encode Failed", exception);
        }
    }

    static <T> T decode(byte[] bytes, Reader<T> reader) {
        if (bytes == null || bytes.length == 0 || bytes.length > NetworkEditorChunk.MAXIMUM_CHUNK_BYTES + 64) {
            throw new IllegalArgumentException("Network Editor Payload Size Is Invalid");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readUnsignedByte() != 1) throw new IllegalArgumentException("Network Editor Version Is Invalid");
            T value = reader.read(input);
            if (input.available() != 0) throw new IllegalArgumentException("Network Editor Payload Has Trailing Bytes");
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Network Editor Payload Is Truncated", exception);
        }
    }

    private static void uuid(DataOutputStream output, UUID value) throws IOException {
        String text = value.toString().replace("-", "");
        for (int index = 0; index < text.length(); index += 2) {
            output.writeByte(Integer.parseInt(text.substring(index, index + 2), 16));
        }
    }

    private static UUID uuid(DataInputStream input) throws IOException {
        String digits = "0123456789abcdef";
        StringBuilder text = new StringBuilder(36);
        for (int index = 0; index < 16; index++) {
            if (index == 4 || index == 6 || index == 8 || index == 10) text.append('-');
            int value = input.readUnsignedByte();
            text.append(digits.charAt(value >>> 4)).append(digits.charAt(value & 15));
        }
        return UUID.fromString(text.toString());
    }

    static void string(DataOutputStream output, String value, int limit) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IllegalArgumentException("Network Editor Field Is Too Long");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    static String string(DataInputStream input, int limit) throws IOException {
        int length = input.readUnsignedShort();
        if (length > limit || length > input.available()) throw new IllegalArgumentException("Network Editor Field Size Is Invalid");
        byte[] bytes = input.readNBytes(length);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("Network Editor Field Is Not UTF-8");
        return value;
    }

    @FunctionalInterface
    interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    interface Reader<T> {
        T read(DataInputStream input) throws IOException;
    }
}
