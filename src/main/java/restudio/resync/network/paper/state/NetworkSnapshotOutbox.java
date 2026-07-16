package restudio.resync.network.paper.state;

import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.NetworkSnapshotChunk;
import restudio.resync.network.NetworkTransferCodec;
import restudio.resync.network.PlayerStateSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class NetworkSnapshotOutbox {
    private static final int MAGIC = 0x52534E53;
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_ENTRIES = 64;
    private static final long MAXIMUM_TOTAL_BYTES = 268435456L;
    private static final long MAXIMUM_FILE_BYTES = 33554432L;
    private final Path directory;

    public NetworkSnapshotOutbox(Path directory) {
        this.directory = directory;
    }

    public synchronized void save(PlayerStateSnapshot snapshot) {
        try {
            Files.createDirectories(directory);
            Path destination = path(snapshot.snapshotId());
            if (Files.exists(destination)) {
                PlayerStateSnapshot existing = read(destination);
                if (!existing.equals(snapshot)) {
                    throw new IllegalStateException("Network Snapshot Outbox ID Is Already Used");
                }
                return;
            }
            byte[] encoded = encode(snapshot);
            List<Path> entries = entries();
            long currentBytes = 0;
            for (Path entry : entries) {
                currentBytes += Files.size(entry);
            }
            if (entries.size() >= MAXIMUM_ENTRIES || currentBytes + encoded.length > MAXIMUM_TOTAL_BYTES) {
                throw new IllegalStateException("Network Snapshot Outbox Is Full");
            }
            Path temporary = directory.resolve(destination.getFileName() + ".tmp");
            Files.write(temporary, encoded);
            move(temporary, destination);
        } catch (IOException exception) {
            throw new IllegalStateException("Persist Network Snapshot Outbox Failed", exception);
        }
    }

    public synchronized List<PlayerStateSnapshot> load() {
        try {
            List<PlayerStateSnapshot> snapshots = new ArrayList<>();
            for (Path entry : entries()) {
                snapshots.add(read(entry));
            }
            return List.copyOf(snapshots);
        } catch (IOException exception) {
            throw new IllegalStateException("Read Network Snapshot Outbox Failed", exception);
        }
    }

    public synchronized void remove(String snapshotId) {
        try {
            Files.deleteIfExists(path(snapshotId));
        } catch (IOException exception) {
            throw new IllegalStateException("Remove Network Snapshot Outbox Entry Failed", exception);
        }
    }

    private List<Path> entries() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".snapshot")).sorted(Comparator.comparingLong(this::modifiedAt)).toList();
        }
    }

    private long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            throw new IllegalStateException("Read Network Snapshot Outbox Time Failed", exception);
        }
    }

    private Path path(String snapshotId) {
        return directory.resolve(NetworkPayloads.sha256(snapshotId.getBytes(StandardCharsets.UTF_8)) + ".snapshot");
    }

    private byte[] encode(PlayerStateSnapshot snapshot) {
        try {
            List<NetworkSnapshotChunk> chunks = NetworkTransferCodec.split("owner:" + snapshot.snapshotId(), snapshot);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeShort(FORMAT_VERSION);
            output.writeInt(chunks.size());
            for (NetworkSnapshotChunk chunk : chunks) {
                byte[] encoded = NetworkTransferCodec.encodeChunk(chunk);
                output.writeInt(encoded.length);
                output.write(encoded);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Snapshot Outbox Entry Failed", exception);
        }
    }

    private PlayerStateSnapshot read(Path path) throws IOException {
        long fileBytes = Files.size(path);
        if (fileBytes <= 0 || fileBytes > MAXIMUM_FILE_BYTES) {
            throw new IllegalArgumentException("Network Snapshot Outbox Entry Is Invalid");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(path)));
        if (input.readInt() != MAGIC || input.readUnsignedShort() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Network Snapshot Outbox Format Is Invalid");
        }
        int chunkCount = input.readInt();
        if (chunkCount <= 0 || chunkCount > NetworkTransferCodec.MAXIMUM_CHUNKS) {
            throw new IllegalArgumentException("Network Snapshot Outbox Chunk Count Is Invalid");
        }
        List<NetworkSnapshotChunk> chunks = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            int length = input.readInt();
            if (length <= 0 || length > NetworkTransferCodec.MAXIMUM_CHUNK_BYTES + 32768 || length > input.available()) {
                throw new IllegalArgumentException("Network Snapshot Outbox Chunk Is Invalid");
            }
            byte[] encoded = input.readNBytes(length);
            if (encoded.length != length) {
                throw new EOFException("Network Snapshot Outbox Chunk Ended Early");
            }
            chunks.add(NetworkTransferCodec.decodeChunk(encoded));
        }
        if (input.available() != 0) {
            throw new IllegalArgumentException("Network Snapshot Outbox Has Trailing Data");
        }
        PlayerStateSnapshot snapshot = NetworkTransferCodec.assemble(chunks);
        if (!path.equals(path(snapshot.snapshotId())) || !chunks.getFirst().transferId().equals("owner:" + snapshot.snapshotId())) {
            throw new IllegalArgumentException("Network Snapshot Outbox Identity Is Invalid");
        }
        return snapshot;
    }

    private void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
