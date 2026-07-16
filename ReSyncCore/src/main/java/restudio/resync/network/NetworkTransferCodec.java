package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class NetworkTransferCodec {
    public static final int MAXIMUM_CHUNK_BYTES = 262144;
    public static final int MAXIMUM_SNAPSHOT_BYTES = 16777216;
    public static final int MAXIMUM_CHUNKS = 256;
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_STRING_BYTES = 4096;

    private NetworkTransferCodec() {
    }

    public static byte[] encodeIntent(NetworkTransferIntent intent) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, intent.transferId(), false);
            writeUuid(output, intent.playerId());
            writeString(output, intent.sourceNodeId(), false);
            writeString(output, intent.targetNodeId(), false);
            output.writeLong(intent.deadline());
        });
    }

    public static NetworkTransferIntent decodeIntent(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkTransferIntent intent = new NetworkTransferIntent(readString(input, false), readUuid(input), readString(input, false), readString(input, false), input.readLong());
            requireEnd(input);
            return intent;
        });
    }

    public static byte[] encodeCheckpoint(NetworkTransferCheckpoint checkpoint) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, checkpoint.transferId(), false);
            writeString(output, checkpoint.snapshotId(), true);
            writeString(output, checkpoint.failure(), true);
        });
    }

    public static NetworkTransferCheckpoint decodeCheckpoint(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkTransferCheckpoint checkpoint = new NetworkTransferCheckpoint(readString(input, false), readString(input, true), readString(input, true));
            requireEnd(input);
            return checkpoint;
        });
    }

    public static byte[] encodeTransfer(PlayerTransfer transfer) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, transfer.transferId(), false);
            writeString(output, transfer.networkId(), false);
            writeUuid(output, transfer.playerId());
            writeString(output, transfer.sourceNodeId(), false);
            writeString(output, transfer.targetNodeId(), false);
            output.writeLong(transfer.fenceEpoch());
            output.writeByte(statusCode(transfer.status()));
            writeString(output, transfer.snapshotId(), true);
            writeString(output, transfer.failure(), true);
            output.writeLong(transfer.deadline());
            output.writeLong(transfer.createdAt());
            output.writeLong(transfer.updatedAt());
        });
    }

    public static PlayerTransfer decodeTransfer(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            PlayerTransfer transfer = new PlayerTransfer(readString(input, false), readString(input, false), readUuid(input), readString(input, false), readString(input, false), input.readLong(), status(input.readUnsignedByte()), readString(input, true), readString(input, true), input.readLong(), input.readLong(), input.readLong());
            requireEnd(input);
            return transfer;
        });
    }

    public static byte[] encodeChunk(NetworkSnapshotChunk chunk) {
        return write(output -> {
            output.writeShort(FORMAT_VERSION);
            writeString(output, chunk.transferId(), false);
            writeString(output, chunk.snapshotId(), false);
            writeString(output, chunk.networkId(), false);
            writeUuid(output, chunk.playerId());
            output.writeLong(chunk.fenceEpoch());
            writeString(output, chunk.family(), false);
            writeString(output, chunk.payloadHash(), false);
            output.writeInt(chunk.schemaVersion());
            output.writeInt(chunk.dataVersion());
            writeString(output, chunk.originNodeId(), false);
            output.writeLong(chunk.createdAt());
            output.writeInt(chunk.totalBytes());
            output.writeInt(chunk.chunkIndex());
            output.writeInt(chunk.chunkCount());
            writeBytes(output, chunk.payload());
        });
    }

    public static NetworkSnapshotChunk decodeChunk(byte[] payload) {
        return read(payload, input -> {
            requireVersion(input);
            NetworkSnapshotChunk chunk = new NetworkSnapshotChunk(readString(input, false), readString(input, false), readString(input, false), readUuid(input), input.readLong(), readString(input, false), readString(input, false), input.readInt(), input.readInt(), readString(input, false), input.readLong(), input.readInt(), input.readInt(), input.readInt(), readBytes(input));
            requireEnd(input);
            if (chunk.totalBytes() > MAXIMUM_SNAPSHOT_BYTES || chunk.chunkCount() > MAXIMUM_CHUNKS) {
                throw new IllegalArgumentException("Network Snapshot Exceeds Transfer Limits");
            }
            return chunk;
        });
    }

    public static List<NetworkSnapshotChunk> split(String transferId, PlayerStateSnapshot snapshot) {
        NetworkPayloads.requireLimit(snapshot.payload(), MAXIMUM_SNAPSHOT_BYTES);
        int totalBytes = snapshot.payload().length;
        int chunkCount = Math.max(1, (totalBytes + MAXIMUM_CHUNK_BYTES - 1) / MAXIMUM_CHUNK_BYTES);
        List<NetworkSnapshotChunk> chunks = new ArrayList<>(chunkCount);
        byte[] payload = snapshot.payload();
        for (int index = 0; index < chunkCount; index++) {
            int start = index * MAXIMUM_CHUNK_BYTES;
            int end = Math.min(totalBytes, start + MAXIMUM_CHUNK_BYTES);
            chunks.add(new NetworkSnapshotChunk(transferId, snapshot.snapshotId(), snapshot.networkId(), snapshot.playerId(), snapshot.fenceEpoch(), snapshot.family(), snapshot.payloadHash(), snapshot.schemaVersion(), snapshot.dataVersion(), snapshot.originNodeId(), snapshot.createdAt(), totalBytes, index, chunkCount, Arrays.copyOfRange(payload, start, end)));
        }
        return List.copyOf(chunks);
    }

    public static PlayerStateSnapshot assemble(List<NetworkSnapshotChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("Network Snapshot Chunks Are Required");
        }
        List<NetworkSnapshotChunk> ordered = chunks.stream().sorted(Comparator.comparingInt(NetworkSnapshotChunk::chunkIndex)).toList();
        NetworkSnapshotChunk first = ordered.getFirst();
        if (ordered.size() != first.chunkCount() || first.totalBytes() > MAXIMUM_SNAPSHOT_BYTES || first.chunkCount() > MAXIMUM_CHUNKS) {
            throw new IllegalArgumentException("Network Snapshot Chunk Set Is Incomplete");
        }
        Set<Integer> positions = new HashSet<>();
        ByteArrayOutputStream payload = new ByteArrayOutputStream(first.totalBytes());
        for (NetworkSnapshotChunk chunk : ordered) {
            if (!sameSnapshot(first, chunk) || !positions.add(chunk.chunkIndex())) {
                throw new IllegalArgumentException("Network Snapshot Chunk Set Is Inconsistent");
            }
            payload.writeBytes(chunk.payload());
        }
        byte[] value = payload.toByteArray();
        if (value.length != first.totalBytes() || !NetworkPayloads.sha256(value).equalsIgnoreCase(first.payloadHash())) {
            throw new IllegalArgumentException("Network Snapshot Payload Hash Does Not Match");
        }
        return new PlayerStateSnapshot(first.snapshotId(), first.networkId(), first.playerId(), first.fenceEpoch(), first.family(), value, first.payloadHash().toLowerCase(), first.schemaVersion(), first.dataVersion(), first.originNodeId(), first.createdAt(), false);
    }

    private static boolean sameSnapshot(NetworkSnapshotChunk expected, NetworkSnapshotChunk actual) {
        return expected.transferId().equals(actual.transferId()) && expected.snapshotId().equals(actual.snapshotId()) && expected.networkId().equals(actual.networkId()) && expected.playerId().equals(actual.playerId()) && expected.fenceEpoch() == actual.fenceEpoch() && expected.family().equals(actual.family()) && expected.payloadHash().equalsIgnoreCase(actual.payloadHash()) && expected.schemaVersion() == actual.schemaVersion() && expected.dataVersion() == actual.dataVersion() && expected.originNodeId().equals(actual.originNodeId()) && expected.createdAt() == actual.createdAt() && expected.totalBytes() == actual.totalBytes() && expected.chunkCount() == actual.chunkCount();
    }

    private static int statusCode(NetworkTransferStatus status) {
        return switch (status) {
            case INTENT -> 1;
            case SOURCE_LEASED -> 2;
            case SNAPSHOT_COMMITTED -> 3;
            case TARGET_READY -> 4;
            case CONNECTED -> 5;
            case APPLIED -> 6;
            case COMMITTED -> 7;
            case ABORTED -> 8;
            case TIMED_OUT -> 9;
        };
    }

    private static NetworkTransferStatus status(int code) {
        return switch (code) {
            case 1 -> NetworkTransferStatus.INTENT;
            case 2 -> NetworkTransferStatus.SOURCE_LEASED;
            case 3 -> NetworkTransferStatus.SNAPSHOT_COMMITTED;
            case 4 -> NetworkTransferStatus.TARGET_READY;
            case 5 -> NetworkTransferStatus.CONNECTED;
            case 6 -> NetworkTransferStatus.APPLIED;
            case 7 -> NetworkTransferStatus.COMMITTED;
            case 8 -> NetworkTransferStatus.ABORTED;
            case 9 -> NetworkTransferStatus.TIMED_OUT;
            default -> throw new IllegalArgumentException("Network Transfer Status Is Invalid");
        };
    }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Transfer Failed", exception);
        }
    }

    private static <T> T read(byte[] payload, Reader<T> reader) {
        if (payload == null || payload.length < Short.BYTES) {
            throw new IllegalArgumentException("Network Transfer Payload Is Invalid");
        }
        try {
            return reader.read(new DataInputStream(new ByteArrayInputStream(payload)));
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Transfer Payload Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Transfer Failed", exception);
        }
    }

    private static void requireVersion(DataInputStream input) throws IOException {
        int version = input.readUnsignedShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Network Transfer Format " + version);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeString(DataOutputStream output, String value, boolean optional) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if ((!optional && bytes.length == 0) || bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Transfer Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, boolean optional) throws IOException {
        int length = input.readUnsignedShort();
        if ((!optional && length == 0) || length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Transfer Text Is Invalid");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Network Transfer Text Ended Early");
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > MAXIMUM_CHUNK_BYTES) {
            throw new IllegalArgumentException("Network Snapshot Chunk Is Too Large");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAXIMUM_CHUNK_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Snapshot Chunk Is Invalid");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Network Snapshot Chunk Ended Early");
        }
        return value;
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IllegalArgumentException("Network Transfer Payload Has Trailing Data");
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
