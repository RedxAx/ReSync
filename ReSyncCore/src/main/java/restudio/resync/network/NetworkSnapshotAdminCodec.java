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
import java.util.UUID;

public final class NetworkSnapshotAdminCodec {
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_STRING_BYTES = 4096;
    private static final int MAXIMUM_RESULTS = 100;

    private NetworkSnapshotAdminCodec() {
    }

    public static byte[] encodeQuery(NetworkSnapshotQuery query) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeUuid(output, query.playerId());
            output.writeInt(query.offset());
            output.writeInt(query.limit());
        });
    }

    public static NetworkSnapshotQuery decodeQuery(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkSnapshotQuery query = new NetworkSnapshotQuery(readUuid(input), input.readInt(), input.readInt());
            requireEnd(input);
            return query;
        });
    }

    public static byte[] encodeReference(String snapshotId) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, snapshotId);
        });
    }

    public static String decodeReference(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            String snapshotId = readString(input);
            requireEnd(input);
            return snapshotId;
        });
    }

    public static byte[] encodePin(NetworkSnapshotPin pin) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, pin.snapshotId());
            output.writeBoolean(pin.pinned());
        });
    }

    public static NetworkSnapshotPin decodePin(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkSnapshotPin pin = new NetworkSnapshotPin(readString(input), input.readBoolean());
            requireEnd(input);
            return pin;
        });
    }

    public static byte[] encodeRestore(NetworkSnapshotRestore restore) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, restore.snapshotId());
            writeString(output, restore.targetNodeId());
            output.writeLong(restore.deadline());
        });
    }

    public static NetworkSnapshotRestore decodeRestore(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkSnapshotRestore restore = new NetworkSnapshotRestore(readString(input), readString(input), input.readLong());
            requireEnd(input);
            return restore;
        });
    }

    public static byte[] encodeMetadata(NetworkSnapshotMetadata metadata) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeMetadata(output, metadata);
        });
    }

    public static NetworkSnapshotMetadata decodeMetadata(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkSnapshotMetadata metadata = readMetadata(input);
            requireEnd(input);
            return metadata;
        });
    }

    public static byte[] encodeList(List<NetworkSnapshotMetadata> snapshots) {
        if (snapshots == null || snapshots.size() > MAXIMUM_RESULTS) {
            throw new IllegalArgumentException("Network Snapshot Result Count Is Invalid");
        }
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            output.writeInt(snapshots.size());
            for (NetworkSnapshotMetadata snapshot : snapshots) {
                writeMetadata(output, snapshot);
            }
        });
    }

    public static List<NetworkSnapshotMetadata> decodeList(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            int count = input.readInt();
            if (count < 0 || count > MAXIMUM_RESULTS) {
                throw new IllegalArgumentException("Network Snapshot Result Count Is Invalid");
            }
            List<NetworkSnapshotMetadata> snapshots = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                snapshots.add(readMetadata(input));
            }
            requireEnd(input);
            return List.copyOf(snapshots);
        });
    }

    private static void writeMetadata(DataOutputStream output, NetworkSnapshotMetadata metadata) throws IOException {
        writeString(output, metadata.snapshotId());
        writeString(output, metadata.networkId());
        writeUuid(output, metadata.playerId());
        output.writeLong(metadata.fenceEpoch());
        writeString(output, metadata.family());
        output.writeInt(metadata.payloadBytes());
        writeString(output, metadata.payloadHash());
        output.writeInt(metadata.schemaVersion());
        output.writeInt(metadata.dataVersion());
        writeString(output, metadata.originNodeId());
        output.writeLong(metadata.createdAt());
        output.writeBoolean(metadata.pinned());
    }

    private static NetworkSnapshotMetadata readMetadata(DataInputStream input) throws IOException {
        return new NetworkSnapshotMetadata(readString(input), readString(input), readUuid(input), input.readLong(), readString(input), input.readInt(), readString(input), input.readInt(), input.readInt(), readString(input), input.readLong(), input.readBoolean());
    }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Snapshot Admin Payload Failed", exception);
        }
    }

    private static <T> T read(byte[] payload, Reader<T> reader) {
        if (payload == null || payload.length < Short.BYTES) {
            throw new IllegalArgumentException("Network Snapshot Admin Payload Is Invalid");
        }
        try {
            return reader.read(new DataInputStream(new ByteArrayInputStream(payload)));
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Snapshot Admin Payload Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Snapshot Admin Payload Failed", exception);
        }
    }

    private static void requireVersion(DataInputStream input) throws IOException {
        if (input.readUnsignedShort() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Network Snapshot Admin Format");
        }
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IllegalArgumentException("Network Snapshot Admin Payload Has Trailing Data");
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        String normalized = NetworkValues.required(value, "Snapshot Admin Text");
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Snapshot Admin Text Is Too Large");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length < 1 || length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Snapshot Admin Text Is Invalid");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Network Snapshot Admin Text Ended Early");
        }
        return new String(value, StandardCharsets.UTF_8);
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
