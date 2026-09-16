package restudio.resync.network.paper;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.network.NetworkTransferCodec;
import restudio.resync.network.PlayerTransfer;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NetworkTransferRecoveryStore {
    private static final String DIRECTORY = "transfer-recovery";
    private static final String SUFFIX = ".transfer";
    private static final int MAXIMUM_ENTRIES = 1_024;
    private static final int MAXIMUM_FILE_BYTES = 32_768;
    private final String owner;
    private final Map<String, PlayerTransfer> transfers = new LinkedHashMap<>();
    private Path directory;
    private boolean quiesced;

    NetworkTransferRecoveryStore(Path networkRoot) {
        this.owner = "transfer-recovery";
        this.directory = MigrationPaths.requirePath(networkRoot, "networkRoot").resolve(DIRECTORY).normalize();
        try {
            load();
        } catch (IOException exception) {
            throw new IllegalStateException("Load ReSync Network Transfer Recovery Failed", exception);
        }
    }

    synchronized void remember(PlayerTransfer transfer) {
        requireWritable();
        if (transfers.size() >= MAXIMUM_ENTRIES && !transfers.containsKey(transfer.transferId())) {
            throw new IllegalStateException("ReSync Network Transfer Recovery Is Full");
        }
        try {
            write(transfer);
            transfers.put(transfer.transferId(), transfer);
        } catch (IOException exception) {
            throw new IllegalStateException("Persist ReSync Network Transfer Recovery Failed", exception);
        }
    }

    synchronized void forget(String transferId) {
        requireWritable();
        forgetStored(transferId);
    }

    synchronized void forgetAfterQuiesce(String transferId) {
        requireHealthy();
        forgetStored(transferId);
    }

    private void forgetStored(String transferId) {
        try {
            Files.deleteIfExists(path(transferId));
            StorageSafety.forceDirectory(directory);
            transfers.remove(transferId);
        } catch (IOException exception) {
            throw new IllegalStateException("Remove ReSync Network Transfer Recovery Failed", exception);
        }
    }

    synchronized List<PlayerTransfer> snapshot() {
        return List.copyOf(transfers.values());
    }

    synchronized Path root() {
        return directory;
    }

    synchronized void flush() throws IOException {
        requireHealthy();
        validateDirectory(directory);
        loadFrom(directory, false);
        StorageSafety.forceDirectory(directory);
    }

    synchronized void quiesce() throws IOException {
        flush();
        quiesced = true;
    }

    synchronized void resume() throws IOException {
        healthCheck();
        quiesced = false;
    }

    synchronized void validateRebind(Path candidateNetworkRoot) throws IOException {
        Path candidate = candidateNetworkRoot.resolve(DIRECTORY).normalize();
        if (!candidate.startsWith(candidateNetworkRoot) || candidate.equals(candidateNetworkRoot)) {
            throw new IOException("ReSync Network Transfer Recovery Rebind Escaped Network Root");
        }
        Files.createDirectories(candidate);
        validateDirectory(candidate);
        try (var stream = Files.list(candidate)) {
            for (Path file : stream.toList()) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("ReSync Network Transfer Recovery Contains An Invalid Entry");
                }
                if (file.getFileName().toString().endsWith(SUFFIX)) {
                    decode(Files.readAllBytes(file));
                }
            }
        }
    }

    synchronized void rebind(Path candidateNetworkRoot) throws IOException {
        validateRebind(candidateNetworkRoot);
        Path candidate = candidateNetworkRoot.resolve(DIRECTORY).normalize();
        directory = candidate;
        transfers.clear();
        load();
    }

    synchronized void healthCheck() throws IOException {
        requireHealthy();
        validateDirectory(directory);
        loadFrom(directory, false);
    }

    NetworkPersistenceDrainController.Component persistenceComponent() {
        return persistenceComponent(() -> {
        });
    }

    NetworkPersistenceDrainController.Component persistenceComponent(Runnable rebound) {
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
                NetworkTransferRecoveryStore.this.flush();
            }

            @Override
            public void quiesce() throws IOException {
                NetworkTransferRecoveryStore.this.quiesce();
            }

            @Override
            public void resume() throws IOException {
                NetworkTransferRecoveryStore.this.resume();
            }

            @Override
            public void validateRebind(Path candidateNetworkRoot) throws IOException {
                NetworkTransferRecoveryStore.this.validateRebind(candidateNetworkRoot);
            }

            @Override
            public void rebind(Path candidateNetworkRoot) throws IOException {
                NetworkTransferRecoveryStore.this.rebind(candidateNetworkRoot);
                rebound.run();
            }

            @Override
            public void healthCheck() throws IOException {
                NetworkTransferRecoveryStore.this.healthCheck();
            }
        };
    }

    private void load() throws IOException {
        Files.createDirectories(directory);
        validateDirectory(directory);
        loadFrom(directory, true);
    }

    private void loadFrom(Path source, boolean replace) throws IOException {
        Map<String, PlayerTransfer> loaded = new LinkedHashMap<>();
        try (var stream = Files.list(source)) {
            List<Path> files = stream.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            if (files.size() > MAXIMUM_ENTRIES) {
                throw new IOException("ReSync Network Transfer Recovery Has Too Many Entries");
            }
            for (Path file : files) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("ReSync Network Transfer Recovery Entry Is Invalid");
                }
                PlayerTransfer transfer = decode(Files.readAllBytes(file));
                if (!file.equals(pathIn(source, transfer.transferId()))) {
                    throw new IOException("ReSync Network Transfer Recovery Identity Is Invalid");
                }
                if (loaded.put(transfer.transferId(), transfer) != null) {
                    throw new IOException("ReSync Network Transfer Recovery Contains Duplicate Entries");
                }
            }
        }
        if (!replace && !loaded.equals(transfers)) {
            throw new IOException("ReSync Network Transfer Recovery Changed Outside The Persistence Boundary");
        }
        if (replace) {
            transfers.clear();
        }
        transfers.putAll(loaded);
    }

    private void write(PlayerTransfer transfer) throws IOException {
        byte[] encoded = NetworkTransferCodec.encodeTransfer(transfer);
        if (encoded.length > MAXIMUM_FILE_BYTES) {
            throw new IOException("ReSync Network Transfer Recovery Entry Is Too Large");
        }
        StorageSafety.writeBytesAtomic(path(transfer.transferId()), encoded);
    }

    private PlayerTransfer decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAXIMUM_FILE_BYTES) {
            throw new IOException("ReSync Network Transfer Recovery Entry Is Invalid");
        }
        try {
            return NetworkTransferCodec.decodeTransfer(encoded);
        } catch (RuntimeException exception) {
            throw new IOException("ReSync Network Transfer Recovery Entry Is Invalid", exception);
        }
    }

    private Path path(String transferId) {
        return pathIn(directory, transferId);
    }

    private Path pathIn(Path root, String transferId) {
        return root.resolve(StorageSafety.sha256(transferId) + SUFFIX).normalize();
    }

    private void validateDirectory(Path root) throws IOException {
        Path normalized = MigrationPaths.requirePath(root, "transferRecoveryRoot");
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Transfer Recovery Root Is Unavailable");
        }
        MigrationPaths.requireNoSymlinkTraversal(normalized, normalized);
    }

    private void requireHealthy() {
        if (directory == null) {
            throw new IllegalStateException("ReSync Network Transfer Recovery Is Unavailable");
        }
    }

    private void requireWritable() {
        requireHealthy();
        if (quiesced) {
            throw new IllegalStateException("ReSync Network Transfer Recovery Is Quiesced");
        }
    }
}
