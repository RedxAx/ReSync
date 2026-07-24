package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class NetworkResourceCodec {
    public static final int MAXIMUM_RESOURCE_BYTES = 500_000;
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_NETWORK_BYTES = 512;
    private static final int MAXIMUM_TYPE_BYTES = 128;
    private static final int MAXIMUM_ID_BYTES = 2048;
    private static final int MAXIMUM_ORIGIN_BYTES = 512;
    private static final int MAXIMUM_HASH_BYTES = 128;

    private NetworkResourceCodec() {
    }

    public static byte[] encodeKey(NetworkResourceKey key) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, key.type(), MAXIMUM_TYPE_BYTES);
            writeString(output, key.resourceId(), MAXIMUM_ID_BYTES);
        });
    }

    public static NetworkResourceKey decodeKey(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkResourceKey key = new NetworkResourceKey(readString(input, MAXIMUM_TYPE_BYTES), readString(input, MAXIMUM_ID_BYTES));
            requireEnd(input);
            return key;
        });
    }

    public static byte[] encodeQuery(NetworkResourceQuery query) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeOptionalString(output, query.afterType(), MAXIMUM_TYPE_BYTES);
            writeOptionalString(output, query.afterResourceId(), MAXIMUM_ID_BYTES);
            output.writeShort(query.limit());
        });
    }

    public static NetworkResourceQuery decodeQuery(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkResourceQuery query = new NetworkResourceQuery(readOptionalString(input, MAXIMUM_TYPE_BYTES), readOptionalString(input, MAXIMUM_ID_BYTES), input.readUnsignedShort());
            requireEnd(input);
            return query;
        });
    }

    public static byte[] encodeMutation(NetworkResourceMutation mutation) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, mutation.type(), MAXIMUM_TYPE_BYTES);
            writeString(output, mutation.resourceId(), MAXIMUM_ID_BYTES);
            output.writeLong(mutation.expectedRevision());
            output.writeBoolean(mutation.deleted());
            writeBytes(output, mutation.payload(), MAXIMUM_RESOURCE_BYTES);
        });
    }

    public static NetworkResourceMutation decodeMutation(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            String type = readString(input, MAXIMUM_TYPE_BYTES);
            String resourceId = readString(input, MAXIMUM_ID_BYTES);
            long expectedRevision = input.readLong();
            boolean deleted = input.readBoolean();
            NetworkResourceMutation mutation = new NetworkResourceMutation(type, resourceId, expectedRevision, readBytes(input, MAXIMUM_RESOURCE_BYTES), deleted);
            requireEnd(input);
            return mutation;
        });
    }

    public static byte[] encodeResource(NetworkResource resource) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, resource.networkId(), MAXIMUM_NETWORK_BYTES);
            writeMetadata(output, resource.metadata());
            writeBytes(output, resource.payload(), MAXIMUM_RESOURCE_BYTES);
        });
    }

    public static NetworkResource decodeResource(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            String networkId = readString(input, MAXIMUM_NETWORK_BYTES);
            NetworkResourceMetadata metadata = readMetadata(input);
            NetworkResource resource = new NetworkResource(networkId, metadata.type(), metadata.resourceId(), metadata.revision(), metadata.payloadHash(), readBytes(input, MAXIMUM_RESOURCE_BYTES), metadata.deleted(), metadata.originNodeId(), metadata.updatedAt());
            requireEnd(input);
            return resource;
        });
    }

    public static byte[] encodePage(NetworkResourcePage page) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            output.writeShort(page.resources().size());
            for (NetworkResourceMetadata metadata : page.resources()) {
                writeMetadata(output, metadata);
            }
            writeOptionalString(output, page.nextType(), MAXIMUM_TYPE_BYTES);
            writeOptionalString(output, page.nextResourceId(), MAXIMUM_ID_BYTES);
        });
    }

    public static NetworkResourcePage decodePage(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            int size = input.readUnsignedShort();
            if (size > 128) {
                throw new IllegalArgumentException("Network Resource Page Is Too Large");
            }
            List<NetworkResourceMetadata> resources = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                resources.add(readMetadata(input));
            }
            NetworkResourcePage page = new NetworkResourcePage(resources, readOptionalString(input, MAXIMUM_TYPE_BYTES), readOptionalString(input, MAXIMUM_ID_BYTES));
            requireEnd(input);
            return page;
        });
    }

    private static void writeMetadata(DataOutputStream output, NetworkResourceMetadata metadata) throws IOException {
        writeString(output, metadata.type(), MAXIMUM_TYPE_BYTES);
        writeString(output, metadata.resourceId(), MAXIMUM_ID_BYTES);
        output.writeLong(metadata.revision());
        writeString(output, metadata.payloadHash(), MAXIMUM_HASH_BYTES);
        output.writeBoolean(metadata.deleted());
        writeString(output, metadata.originNodeId(), MAXIMUM_ORIGIN_BYTES);
        output.writeLong(metadata.updatedAt());
    }

    private static NetworkResourceMetadata readMetadata(DataInputStream input) throws IOException {
        return new NetworkResourceMetadata(readString(input, MAXIMUM_TYPE_BYTES), readString(input, MAXIMUM_ID_BYTES), input.readLong(), readString(input, MAXIMUM_HASH_BYTES), input.readBoolean(), readString(input, MAXIMUM_ORIGIN_BYTES), input.readLong());
    }

    private static void requireVersion(DataInputStream input) throws IOException {
        int version = input.readUnsignedShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Network Resource Format " + version);
        }
    }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Resource Failed", exception);
        }
    }

    private static <T> T read(byte[] payload, Reader<T> reader) {
        if (payload == null || payload.length < Short.BYTES) {
            throw new IllegalArgumentException("Network Resource Payload Is Invalid");
        }
        try {
            return reader.read(new DataInputStream(new ByteArrayInputStream(payload)));
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Resource Payload Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Resource Failed", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maximumBytes) {
            throw new IllegalArgumentException("Network Resource Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static void writeOptionalString(DataOutputStream output, String value, int maximumBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) {
            throw new IllegalArgumentException("Network Resource Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > maximumBytes || length > input.available()) {
            throw new IllegalArgumentException("Network Resource Text Is Invalid");
        }
        return new String(readExact(input, length), StandardCharsets.UTF_8);
    }

    private static String readOptionalString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readUnsignedShort();
        if (length > maximumBytes || length > input.available()) {
            throw new IllegalArgumentException("Network Resource Text Is Invalid");
        }
        return new String(readExact(input, length), StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] value, int maximumBytes) throws IOException {
        NetworkPayloads.requireLimit(value, maximumBytes);
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes || length > input.available()) {
            throw new IllegalArgumentException("Network Resource Data Is Invalid");
        }
        return readExact(input, length);
    }

    private static byte[] readExact(DataInputStream input, int length) throws IOException {
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Network Resource Payload Ended Early");
        }
        return value;
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IllegalArgumentException("Network Resource Payload Has Trailing Data");
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
