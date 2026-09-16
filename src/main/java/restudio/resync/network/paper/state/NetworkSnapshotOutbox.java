package restudio.resync.network.paper.state;

import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.NetworkSnapshotChunk;
import restudio.resync.network.NetworkTransferCodec;
import restudio.resync.network.PlayerStateSnapshot;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.network.paper.NetworkPersistenceDrainController;
import restudio.resync.storage.StorageSafety;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class NetworkSnapshotOutbox {
    private static final int MAGIC = 0x52534E53;
    private static final int FORMAT_VERSION = 1;
    private static final int MAXIMUM_ENTRIES = 64;
    private static final long MAXIMUM_TOTAL_BYTES = 268435456L;
    private static final long MAXIMUM_FILE_BYTES = 33554432L;
    private static final String STORAGE_TEMP_PREFIX = ".resync-";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String QUARANTINE_DIRECTORY = ".quarantine";
    private static final String QUARANTINE_CATEGORY = "network-exact-files";
    private static final String TEMPORARY_EVIDENCE_PREFIX = "temporary";
    private static final String EVIDENCE_SUFFIX = ".evidence";
    private static final String LOCK_FILE_NAME = ".outbox.lock";
    private static final ConcurrentMap<Path, Object> PROCESS_LOCKS = new ConcurrentHashMap<>();
    private Path directory;
    private boolean quiesced;

    public NetworkSnapshotOutbox(Path directory) {
        this.directory = MigrationPaths.requirePath(directory, "directory");
        try {
            if (Files.exists(this.directory, LinkOption.NOFOLLOW_LINKS)) {
                withLock(this.directory, () -> {
                    recoverCanonicalTemps(this.directory);
                    entries(this.directory);
                    return null;
                });
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Network Snapshot Outbox Durability State Is Invalid", exception);
        }
    }

    public synchronized void save(PlayerStateSnapshot snapshot) {
        requireWritable();
        Objects.requireNonNull(snapshot, "snapshot");
        validateSnapshotIntegrity(snapshot);
        try {
            ensureDirectory(directory);
            withLock(directory, () -> {
                recoverCanonicalTemps(directory);
                Path destination = path(snapshot.snapshotId());
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    validateSnapshotFile(destination);
                    PlayerStateSnapshot existing = read(destination);
                    if (!existing.equals(snapshot)) {
                        throw new IllegalStateException("Network Snapshot Outbox ID Is Already Used");
                    }
                    return null;
                }
                byte[] encoded = encode(snapshot);
                List<Path> entries = entries();
                long currentBytes = 0;
                for (Path entry : entries) {
                    currentBytes += Files.size(entry);
                }
                if (encoded.length > MAXIMUM_FILE_BYTES || entries.size() >= MAXIMUM_ENTRIES
                    || currentBytes > MAXIMUM_TOTAL_BYTES - encoded.length) {
                    throw new IllegalStateException("Network Snapshot Outbox Is Full");
                }
                StorageSafety.writeBytesAtomicStrict(destination, encoded);
                StorageSafety.forceDirectory(directory);
                return null;
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Persist Network Snapshot Outbox Failed", exception);
        }
    }

    public synchronized List<PlayerStateSnapshot> load() {
        requireHealthy();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            return withLock(directory, () -> {
                List<PlayerStateSnapshot> snapshots = new ArrayList<>();
                for (Path entry : entries()) {
                    snapshots.add(read(entry));
                }
                return List.copyOf(snapshots);
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Read Network Snapshot Outbox Failed", exception);
        }
    }

    public synchronized void remove(String snapshotId) {
        requireWritable();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            withLock(directory, () -> {
                String requestedId = requireSnapshotId(snapshotId);
                Path target = path(requestedId);
                recoverCanonicalTemps(directory);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    PlayerStateSnapshot existing = read(target);
                    if (!requestedId.equals(existing.snapshotId())) {
                        throw new IOException("Network Snapshot Outbox Entry Identity Is Invalid");
                    }
                    Files.delete(target);
                }
                StorageSafety.forceDirectory(directory);
                return null;
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Remove Network Snapshot Outbox Entry Failed", exception);
        }
    }

    public synchronized Path root() {
        return directory;
    }

    public synchronized NetworkPersistenceDrainController.Component persistenceComponent(String owner) {
        return new NetworkPersistenceDrainController.Component() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path activePath() {
                return root();
            }

            @Override
            public void flush() throws IOException {
                NetworkSnapshotOutbox.this.flush();
            }

            @Override
            public void quiesce() throws IOException {
                NetworkSnapshotOutbox.this.quiesce();
            }

            @Override
            public void resume() throws IOException {
                NetworkSnapshotOutbox.this.resume();
            }

            @Override
            public void validateRebind(Path candidateNetworkRoot) throws IOException {
                Path candidateRoot = MigrationPaths.requireDirectory(candidateNetworkRoot, "candidateNetworkRoot");
                Path candidate = candidateRoot.resolve("snapshot-outbox").normalize();
                if (!candidate.startsWith(candidateRoot) || candidate.equals(candidateRoot)) {
                    throw new IOException("Network Snapshot Outbox Rebind Escaped Network Root");
                }
                ensureDirectory(candidate);
                withLock(candidate, () -> {
                    validateDirectory(candidate);
                    recoverCanonicalTemps(candidate);
                    for (Path entry : entries(candidate)) {
                        readForHealth(entry);
                    }
                    return null;
                });
            }

            @Override
            public void rebind(Path candidateNetworkRoot) throws IOException {
                NetworkSnapshotOutbox.this.rebind(candidateNetworkRoot.resolve("snapshot-outbox").normalize());
            }

            @Override
            public void healthCheck() throws IOException {
                NetworkSnapshotOutbox.this.healthCheck();
            }
        };
    }

    public synchronized void flush() throws IOException {
        requireHealthy();
        ensureDirectory(directory);
        withLock(directory, () -> {
            validateDirectory(directory);
            recoverCanonicalTemps(directory);
            for (Path entry : entries()) {
                readForHealth(entry);
                StorageSafety.forceDirectory(entry.getParent());
            }
            StorageSafety.forceDirectory(directory);
            return null;
        });
    }

    public synchronized void quiesce() throws IOException {
        requireHealthy();
        flush();
        quiesced = true;
    }

    public synchronized void resume() throws IOException {
        requireHealthy();
        healthCheck();
        quiesced = false;
    }

    public synchronized void rebind(Path networkRoot) throws IOException {
        requireHealthy();
        if (!quiesced) {
            throw new IOException("Network Snapshot Outbox Must Be Quiesced Before Rebind");
        }
        Path candidate = MigrationPaths.requireDirectory(networkRoot, "networkRoot");
        withLock(candidate, () -> {
            validateDirectory(candidate);
            recoverCanonicalTemps(candidate);
            for (Path entry : entries(candidate)) {
                readForHealth(entry);
            }
            directory = candidate;
            return null;
        });
    }

    public synchronized void healthCheck() throws IOException {
        requireHealthy();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        withLock(directory, () -> {
            validateDirectory(directory);
            recoverCanonicalTemps(directory);
            for (Path entry : entries()) {
                readForHealth(entry);
            }
            return null;
        });
    }

    private List<Path> entries() throws IOException {
        return entries(directory);
    }

    private List<Path> entries(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        validateDirectory(root);
        recoverCanonicalTemps(root);
        try (var stream = Files.list(root)) {
            List<Path> files = new ArrayList<>(Math.min(MAXIMUM_ENTRIES, 16));
            var iterator = stream.iterator();
            long totalBytes = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Network Snapshot Outbox Contains A Symbolic Link");
                }
                String name = path.getFileName().toString();
                if (name.equals(QUARANTINE_DIRECTORY)) {
                    validateQuarantine(root);
                    continue;
                }
                if (name.equals(LOCK_FILE_NAME)) {
                    validateLockFile(path);
                    continue;
                }
                if (!isSnapshotName(name)) {
                    throw new IOException("Network Snapshot Outbox Contains An Unknown Entry: " + path);
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Network Snapshot Outbox Entry Is Not A Regular File");
                }
                if (files.size() >= MAXIMUM_ENTRIES) {
                    throw new IOException("Network Snapshot Outbox Contains Too Many Entries");
                }
                long fileBytes = Files.size(path);
                if (fileBytes <= 0 || fileBytes > MAXIMUM_FILE_BYTES) {
                    throw new IOException("Network Snapshot Outbox Entry Exceeds Its File Limit");
                }
                if (totalBytes > MAXIMUM_TOTAL_BYTES - fileBytes) {
                    throw new IOException("Network Snapshot Outbox Exceeds Its Total Size Limit");
                }
                totalBytes += fileBytes;
                files.add(path);
            }
            return files.stream().sorted(Comparator.comparingLong(this::modifiedAt)).toList();
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

    private void requireHealthy() {
        if (directory == null) {
            throw new IllegalStateException("Network Snapshot Outbox Is Unavailable");
        }
    }

    private void requireWritable() {
        requireHealthy();
        if (quiesced) {
            throw new IllegalStateException("Network Snapshot Outbox Persistence Is Quiesced");
        }
    }

    private void validateDirectory(Path root) throws IOException {
        Path normalized = MigrationPaths.requirePath(root, "directory");
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Network Snapshot Outbox Directory Is Unavailable");
        }
        MigrationPaths.requireNoSymlinkTraversal(normalized, normalized);
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
        Path normalizedPath = MigrationPaths.requirePath(path, "snapshot entry");
        Path parent = normalizedPath.getParent();
        if (parent == null) {
            throw new IOException("Network Snapshot Outbox Entry Parent Is Missing");
        }
        entries(parent);
        validateSnapshotFile(normalizedPath);
        long fileBytes = Files.size(normalizedPath);
        if (fileBytes <= 0 || fileBytes > MAXIMUM_FILE_BYTES) {
            throw new IllegalArgumentException("Network Snapshot Outbox Entry Is Invalid");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(normalizedPath)));
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
        validateSnapshotIntegrity(snapshot);
        Path expected = normalizedPath.getParent().resolve(
            NetworkPayloads.sha256(snapshot.snapshotId().getBytes(StandardCharsets.UTF_8)) + ".snapshot");
        if (!normalizedPath.equals(expected) || !chunks.getFirst().transferId().equals("owner:" + snapshot.snapshotId())) {
            throw new IllegalArgumentException("Network Snapshot Outbox Identity Is Invalid");
        }
        return snapshot;
    }

    private void readForHealth(Path path) throws IOException {
        try {
            read(path);
        } catch (RuntimeException exception) {
            throw new IOException("Network Snapshot Outbox Entry Is Invalid: " + path, exception);
        }
    }

    private static void validateSnapshotIntegrity(PlayerStateSnapshot snapshot) {
        if (!NetworkPayloads.sha256(snapshot.payload()).equals(snapshot.payloadHash())) {
            throw new IllegalArgumentException("Network Snapshot Outbox Payload Hash Does Not Match");
        }
    }

    private static String requireSnapshotId(String snapshotId) {
        String normalized = snapshotId == null ? "" : snapshotId.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Network Snapshot Outbox Snapshot ID Is Required");
        }
        return normalized;
    }

    private static void validateSnapshotFile(Path path) throws IOException {
        Path normalized;
        try {
            normalized = MigrationPaths.requirePath(path, "snapshot entry");
        } catch (IllegalArgumentException exception) {
            throw new IOException("Network Snapshot Outbox Entry Is Invalid", exception);
        }
        if (normalized.getFileName() == null || !isSnapshotName(normalized.getFileName().toString())) {
            throw new IOException("Network Snapshot Outbox Entry Name Is Invalid");
        }
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Network Snapshot Outbox Entry Is Not A Regular File");
        }
    }

    private static boolean isSnapshotName(String name) {
        if (name == null || name.length() != 73 || !name.endsWith(".snapshot")) {
            return false;
        }
        for (int index = 0; index < 64; index++) {
            char value = name.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTemporaryName(String name) {
        return name != null && name.endsWith(TEMP_SUFFIX)
            && isSnapshotName(name.substring(0, name.length() - TEMP_SUFFIX.length()));
    }

    private static boolean isStorageSafetyTemporaryName(String name) {
        if (name == null || name.length() != STORAGE_TEMP_PREFIX.length() + 36 + TEMP_SUFFIX.length()
            || !name.startsWith(STORAGE_TEMP_PREFIX) || !name.endsWith(TEMP_SUFFIX)) {
            return false;
        }
        return isCanonicalUuid(name.substring(STORAGE_TEMP_PREFIX.length(), name.length() - TEMP_SUFFIX.length()));
    }

    private static boolean isPotentialStorageSafetyTemporaryName(String name) {
        return name != null && name.startsWith(STORAGE_TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX);
    }

    private static boolean isCanonicalUuid(String value) {
        if (value.length() != 36) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (character != '-') {
                    return false;
                }
            } else if (!isLowercaseHex(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowercaseHex(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }

    private static void ensureDirectory(Path directory) throws IOException {
        Path normalized = MigrationPaths.requirePath(directory, "directory");
        Path existing = normalized;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("Network Snapshot Outbox Has No Existing Parent");
            }
        }
        MigrationPaths.requireDirectory(existing, "network snapshot outbox existing parent");
        StorageSafety.createDirectoriesNoSymlinks(existing, normalized);
    }

    private static void recoverCanonicalTemps(Path root) throws IOException {
        Path normalizedRoot = MigrationPaths.requirePath(root, "directory");
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        validateDirectoryPath(normalizedRoot);
        validateQuarantine(normalizedRoot);
        List<Path> files = new ArrayList<>(Math.min(MAXIMUM_ENTRIES + 1, 16));
        try (var stream = Files.list(normalizedRoot)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                String name = entry.getFileName().toString();
                if (name.equals(QUARANTINE_DIRECTORY) || name.equals(LOCK_FILE_NAME)) {
                    continue;
                }
                if (files.size() >= MAXIMUM_ENTRIES + 1) {
                    throw new IOException("Network Snapshot Outbox Contains Too Many Entries");
                }
                files.add(entry);
            }
        }
        for (Path entry : files) {
            if (Files.isSymbolicLink(entry)) {
                throw new IOException("Network Snapshot Outbox Contains A Symbolic Link");
            }
            String name = entry.getFileName().toString();
            if (name.equals(QUARANTINE_DIRECTORY)) {
                continue;
            }
            if (name.equals(LOCK_FILE_NAME)) {
                validateLockFile(entry);
                continue;
            }
            if (isSnapshotName(name)) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Network Snapshot Outbox Entry Is Not A Regular File");
                }
                continue;
            }
            if (isTemporaryName(name)) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Network Snapshot Outbox Temporary Entry Is Not A Regular File");
                }
                Path destination = normalizedRoot.resolve(name.substring(0, name.length() - TEMP_SUFFIX.length()));
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    moveEvidence(entry, normalizedRoot, destination.getFileName().toString());
                    throw new IOException("Network Snapshot Outbox Temporary Entry Collides With Its Target");
                }
                moveEvidence(entry, normalizedRoot, destination.getFileName().toString());
                continue;
            }
            if (isStorageSafetyTemporaryName(name)) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Network Snapshot Outbox Storage Temporary Entry Is Not A Regular File");
                }
                moveEvidence(entry, normalizedRoot, null);
                continue;
            }
            if (isPotentialStorageSafetyTemporaryName(name)) {
                throw new IOException("Network Snapshot Outbox Temporary Entry Name Is Invalid");
            }
            throw new IOException("Network Snapshot Outbox Contains An Unknown Entry: " + entry);
        }
    }

    private static void validateDirectoryPath(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Network Snapshot Outbox Directory Is Unavailable");
        }
        MigrationPaths.requireNoSymlinkTraversal(root, root);
    }

    private static void validateQuarantine(Path root) throws IOException {
        Path normalizedRoot = MigrationPaths.requirePath(root, "directory");
        Path quarantineParent = normalizedRoot.resolve(QUARANTINE_DIRECTORY).normalize();
        Path quarantine = quarantineParent.resolve(QUARANTINE_CATEGORY).normalize();
        if (!quarantine.startsWith(normalizedRoot) || quarantine.equals(normalizedRoot)
            || quarantine.getParent() == null || !quarantine.getParent().equals(quarantineParent)) {
            throw new IOException("Network Snapshot Outbox Quarantine Path Is Invalid");
        }
        if (Files.exists(quarantineParent, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(quarantineParent)
            || !Files.isDirectory(quarantineParent, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Network Snapshot Outbox Quarantine Parent Is Invalid");
        }
        if (!Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Network Snapshot Outbox Quarantine Is Invalid");
        }
        MigrationPaths.requireNoSymlinkTraversal(normalizedRoot, quarantine);
        try (var stream = Files.list(quarantine)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path artifact = iterator.next();
                if (artifact.getParent() == null || !artifact.getParent().equals(quarantine)
                    || artifact.getFileName() == null || Files.isSymbolicLink(artifact)
                    || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                    || !isEvidenceName(artifact.getFileName().toString())) {
                    throw new IOException("Network Snapshot Outbox Quarantine Contains An Invalid Entry");
                }
            }
        }
    }

    private static void validateLockFile(Path lock) throws IOException {
        if (Files.isSymbolicLink(lock) || !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Network Snapshot Outbox Lock File Is Invalid");
        }
    }

    private static <T> T withLock(Path root, IOCallable<T> action) throws IOException {
        Path normalizedRoot = MigrationPaths.requirePath(root, "directory");
        validateDirectoryPath(normalizedRoot);
        Path lock = normalizedRoot.resolve(LOCK_FILE_NAME).normalize();
        if (!lock.startsWith(normalizedRoot) || lock.equals(normalizedRoot)) {
            throw new IOException("Network Snapshot Outbox Lock Path Is Invalid");
        }
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            validateLockFile(lock);
        }
        Object processLock = PROCESS_LOCKS.computeIfAbsent(lock, ignored -> new Object());
        synchronized (processLock) {
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                validateLockFile(lock);
                channel.force(true);
                try (FileLock ignored = channel.lock()) {
                    return action.call();
                }
            }
        }
    }

    private static boolean isEvidenceName(String name) {
        if (name == null || !name.endsWith(EVIDENCE_SUFFIX)) {
            return false;
        }
        int uuidEnd = name.length() - EVIDENCE_SUFFIX.length();
        int uuidStart = uuidEnd - 36;
        int separator = uuidStart - 1;
        if (separator < 0 || name.charAt(separator) != '.') {
            return false;
        }
        String target = name.substring(0, separator);
        String identifier = name.substring(uuidStart, uuidEnd);
        try {
            return (isSnapshotName(target) || TEMPORARY_EVIDENCE_PREFIX.equals(target))
                && UUID.fromString(identifier).toString().equals(identifier);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void moveEvidence(Path temporary, Path root, String targetName) throws IOException {
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).resolve(QUARANTINE_CATEGORY).normalize();
        ensureDirectory(quarantine);
        validateQuarantine(root);
        Path evidence = null;
        String evidencePrefix = targetName == null ? TEMPORARY_EVIDENCE_PREFIX : targetName;
        for (int attempt = 0; attempt < 128; attempt++) {
            Path candidate = quarantine.resolve(evidencePrefix + "." + UUID.randomUUID() + EVIDENCE_SUFFIX);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                evidence = candidate;
                break;
            }
        }
        if (evidence == null) {
            throw new IOException("Unable To Reserve Network Snapshot Outbox Evidence Path");
        }
        try {
            Files.move(temporary, evidence, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, evidence);
        }
        if (Files.isSymbolicLink(evidence) || !Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Network Snapshot Outbox Evidence Is Invalid");
        }
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(quarantine.getParent());
    }

    @FunctionalInterface
    private interface IOCallable<T> {
        T call() throws IOException;
    }
}
