package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class NetworkVariableCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_STRING_BYTES = 8192;
    private static final int MAXIMUM_VALUE_BYTES = 524288;

    private NetworkVariableCodec() {
    }

    public static byte[] encodeQuery(NetworkVariableQuery query) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            output.writeByte(query.scope().ordinal());
            writeOptionalString(output, query.scopeId());
            writeString(output, query.key());
        });
    }

    public static NetworkVariableQuery decodeQuery(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkVariableQuery query = new NetworkVariableQuery(readScope(input), readOptionalString(input), readString(input));
            requireEnd(input);
            return query;
        });
    }

    public static byte[] encodeMutation(NetworkVariableMutation mutation) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            output.writeByte(mutation.scope().ordinal());
            writeOptionalString(output, mutation.scopeId());
            writeString(output, mutation.key());
            output.writeByte(mutation.type().ordinal());
            output.writeLong(mutation.expectedRevision());
            output.writeLong(mutation.expiresAt());
            writeBytes(output, mutation.value());
        });
    }

    public static NetworkVariableMutation decodeMutation(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkVariableScope scope = readScope(input);
            String scopeId = readOptionalString(input);
            String key = readString(input);
            NetworkVariableType type = readType(input);
            long expectedRevision = input.readLong();
            long expiresAt = input.readLong();
            NetworkVariableMutation mutation = new NetworkVariableMutation(scope, scopeId, key, type, readBytes(input), expectedRevision, expiresAt);
            requireEnd(input);
            return mutation;
        });
    }

    public static byte[] encodeVariable(NetworkVariable variable) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, variable.networkId());
            output.writeByte(variable.scope().ordinal());
            writeOptionalString(output, variable.scopeId());
            writeString(output, variable.key());
            output.writeByte(variable.type().ordinal());
            output.writeLong(variable.revision());
            output.writeLong(variable.expiresAt());
            writeString(output, variable.originNodeId());
            output.writeLong(variable.updatedAt());
            writeBytes(output, variable.value());
        });
    }

    public static NetworkVariable decodeVariable(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            String networkId = readString(input);
            NetworkVariableScope scope = readScope(input);
            String scopeId = readOptionalString(input);
            String key = readString(input);
            NetworkVariableType type = readType(input);
            long revision = input.readLong();
            long expiresAt = input.readLong();
            String originNodeId = readString(input);
            long updatedAt = input.readLong();
            NetworkVariable variable = new NetworkVariable(networkId, scope, scopeId, key, type, readBytes(input), revision, expiresAt, originNodeId, updatedAt);
            requireEnd(input);
            return variable;
        });
    }

    private static void requireVersion(DataInputStream input) throws IOException {
        int version = input.readUnsignedShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Network Variable Format " + version);
        }
    }

    private static NetworkVariableScope readScope(DataInputStream input) throws IOException {
        int ordinal = input.readUnsignedByte();
        NetworkVariableScope[] values = NetworkVariableScope.values();
        if (ordinal >= values.length) {
            throw new IllegalArgumentException("Network Variable Scope Is Invalid");
        }
        return values[ordinal];
    }

    private static NetworkVariableType readType(DataInputStream input) throws IOException {
        int ordinal = input.readUnsignedByte();
        NetworkVariableType[] values = NetworkVariableType.values();
        if (ordinal >= values.length) {
            throw new IllegalArgumentException("Network Variable Type Is Invalid");
        }
        return values[ordinal];
    }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Variable Failed", exception);
        }
    }

    private static <T> T read(byte[] payload, Reader<T> reader) {
        if (payload == null || payload.length < Short.BYTES) {
            throw new IllegalArgumentException("Network Variable Payload Is Invalid");
        }
        try {
            return reader.read(new DataInputStream(new ByteArrayInputStream(payload)));
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Variable Payload Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Variable Failed", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Variable Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static void writeOptionalString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Variable Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Variable Text Is Invalid");
        }
        return new String(readExact(input, length), StandardCharsets.UTF_8);
    }

    private static String readOptionalString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Variable Text Is Invalid");
        }
        return new String(readExact(input, length), StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > MAXIMUM_VALUE_BYTES) {
            throw new IllegalArgumentException("Network Variable Value Is Too Large");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAXIMUM_VALUE_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Variable Value Is Invalid");
        }
        return readExact(input, length);
    }

    private static byte[] readExact(DataInputStream input, int length) throws IOException {
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Network Variable Payload Ended Early");
        }
        return value;
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IllegalArgumentException("Network Variable Payload Has Trailing Data");
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(DataInputStream input) throws IOException;
    }
}
