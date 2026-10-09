package restudio.resync.network.paper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.network.NetworkResourceMetadata;
import restudio.resync.storage.StorageSafety;
import restudio.resync.storage.AssetTransactionCoordinator.CommittedAsset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class NetworkResourceManifestStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final int VERSION = 1;
    private final String fileName;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, BlockedResource> blocked = new LinkedHashMap<>(16, 0.75f, true);
    private String blockedPeer = "";
    private Path file;
    private String loadFailure;
    private boolean quiesced;

    NetworkResourceManifestStore(Path dataDirectory) {
        this(dataDirectory, "resource-manifest.json");
    }

    NetworkResourceManifestStore(Path dataDirectory, String fileName) {
        Path root = MigrationPaths.requirePath(dataDirectory, "dataDirectory").resolve("network").normalize();
        this.fileName = requireFileName(fileName);
        file = root.resolve(this.fileName).normalize();
        try {
            if (Files.isSymbolicLink(root)) {
                throw new IOException("ReSync Network Resource Manifest Root Is A Symbolic Link");
            }
            Files.createDirectories(root);
        } catch (IOException | RuntimeException exception) {
            loadFailure = reason(exception);
            Log.warn("ReSync network resource manifest is unavailable: " + loadFailure);
            return;
        }
        load();
    }

    synchronized Entry get(String type, String resourceId) {
        requireHealthy();
        return entries.get(key(type, resourceId));
    }

    synchronized Map<String, Entry> snapshot() {
        requireHealthy();
        return Map.copyOf(entries);
    }

    synchronized boolean blocked(String peer, NetworkResourceMetadata metadata) {
        return blocked(peer, metadata, null);
    }

    synchronized boolean blocked(String peer, NetworkResourceMetadata metadata, CommittedAsset localStamp) {
        requireHealthy();
        requirePeer(peer);
        BlockedResource value = blocked.get(metadata.key());
        if (value == null) {
            return false;
        }
        if (value.revision() == metadata.revision() && value.payloadHash().equals(metadata.payloadHash())
            && value.deleted() == metadata.deleted()
            && (value.localStamp() == null || value.localStamp().equals(localStamp))) {
            return true;
        }
        blocked.remove(metadata.key());
        return false;
    }

    synchronized boolean block(String peer, NetworkResourceMetadata metadata, String reason) {
        return block(peer, metadata.type(), metadata.resourceId(), metadata, null, reason);
    }

    synchronized boolean block(String peer, String type, String id, NetworkResourceMetadata metadata,
                               CommittedAsset localStamp, String reason) {
        requireWritable();
        requirePeer(peer);
        BlockedResource next = new BlockedResource(type, id, metadata == null ? 0L : metadata.revision(),
            metadata == null ? "" : metadata.payloadHash(), metadata != null && metadata.deleted(), reason, localStamp);
        if (next.equals(blocked.get(key(type, id)))) {
            return false;
        }
        blocked.put(key(type, id), next);
        while (blocked.size() > 128) {
            blocked.remove(blocked.keySet().iterator().next());
        }
        return true;
    }

    synchronized Map<String, BlockedResource> blockedResources(String peer) {
        requireHealthy();
        requirePeer(peer);
        return Map.copyOf(blocked);
    }

    synchronized void clearLocalBlock(String peer, String type, String id) {
        requireHealthy();
        requirePeer(peer);
        BlockedResource value = blocked.get(key(type, id));
        if (value != null && value.localStamp() != null) {
            blocked.remove(key(type, id));
        }
    }

    private void requirePeer(String peer) {
        if (!blockedPeer.equals(peer)) {
            blocked.clear();
            blockedPeer = peer;
        }
    }

    synchronized void put(NetworkResourceMetadata metadata) {
        requireWritable();
        Entry value = new Entry(metadata.type(), metadata.resourceId(), metadata.revision(), metadata.payloadHash(), metadata.deleted(), metadata.updatedAt());
        Map<String, Entry> next = new LinkedHashMap<>(entries);
        next.put(metadata.key(), value);
        save(next);
        entries.clear();
        entries.putAll(next);
        blocked.remove(metadata.key());
    }

    synchronized Path root() {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IllegalStateException("ReSync Network Resource Manifest Root Is Missing");
        }
        return parent;
    }

    synchronized Path file() {
        return file;
    }

    synchronized NetworkPersistenceDrainController.Component persistenceComponent(String owner) {
        return new NetworkPersistenceDrainController.Component() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path activePath() {
                return file();
            }

            @Override
            public void flush() throws IOException {
                NetworkResourceManifestStore.this.flush();
            }

            @Override
            public void quiesce() throws IOException {
                NetworkResourceManifestStore.this.quiesce();
            }

            @Override
            public void resume() throws IOException {
                NetworkResourceManifestStore.this.resume();
            }

            @Override
            public void validateRebind(Path candidateNetworkRoot) throws IOException {
                Path candidate = candidateNetworkRoot.resolve(fileName).normalize();
                if (!candidate.startsWith(candidateNetworkRoot) || candidate.equals(candidateNetworkRoot)) {
                    throw new IOException("ReSync Network Resource Manifest Rebind Escaped Network Root");
                }
                validateRoot(candidateNetworkRoot);
                read(candidate);
            }

            @Override
            public void rebind(Path candidateNetworkRoot) throws IOException {
                NetworkResourceManifestStore.this.rebind(candidateNetworkRoot);
            }

            @Override
            public void healthCheck() throws IOException {
                NetworkResourceManifestStore.this.healthCheck();
            }
        };
    }

    synchronized void flush() throws IOException {
        requireHealthy();
        validateRoot(root());
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Resource Manifest Is Not A Regular File");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !entries.isEmpty()) {
            throw new IOException("ReSync Network Resource Manifest Is Missing Its Acknowledged Baseline");
        }
        StorageSafety.forceDirectory(root());
    }

    synchronized void quiesce() throws IOException {
        requireHealthy();
        flush();
        quiesced = true;
    }

    synchronized void resume() throws IOException {
        requireHealthy();
        healthCheck();
        quiesced = false;
    }

    synchronized void rebind(Path networkRoot) throws IOException {
        if (!quiesced) {
            throw new IOException("ReSync Network Resource Manifest Must Be Quiesced Before Rebind");
        }
        Path candidateRoot = MigrationPaths.requireDirectory(networkRoot, "networkRoot");
        Path candidate = candidateRoot.resolve(fileName).normalize();
        if (!candidate.startsWith(candidateRoot) || candidate.equals(candidateRoot)) {
            throw new IOException("ReSync Network Resource Manifest Rebind Escaped Network Root");
        }
        Map<String, Entry> loaded = read(candidate);
        file = candidate;
        entries.clear();
        entries.putAll(loaded);
        blocked.clear();
        blockedPeer = "";
        loadFailure = null;
    }

    synchronized void healthCheck() throws IOException {
        requireHealthy();
        try {
            validateRoot(root());
            Map<String, Entry> loaded = read(file);
            if (!loaded.equals(entries)) {
                throw new IOException("ReSync Network Resource Manifest Changed Outside The Persistence Boundary");
            }
        } catch (IOException | RuntimeException exception) {
            loadFailure = reason(exception);
            throw exception;
        }
    }

    private void load() {
        try {
            entries.clear();
            entries.putAll(read(file));
            loadFailure = null;
        } catch (RuntimeException | IOException exception) {
            entries.clear();
            loadFailure = reason(exception);
            Log.warn("ReSync network resource manifest is unavailable: " + loadFailure);
        }
    }

    private Map<String, Entry> read(Path candidate) throws IOException {
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Resource Manifest Is Not A Regular File");
        }
        Manifest manifest = GSON.fromJson(StorageSafety.readUtf8(candidate), new TypeToken<Manifest>() {
        }.getType());
        if (manifest == null || manifest.version() != VERSION || manifest.entries() == null) {
            throw new IOException("ReSync Network Resource Manifest Is Invalid");
        }
        Map<String, Entry> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> value : manifest.entries().entrySet()) {
            Entry entry = value.getValue();
            if (entry == null || !key(entry.type(), entry.resourceId()).equals(value.getKey())) {
                throw new IOException("ReSync Network Resource Manifest Entry Identity Is Invalid");
            }
            if (loaded.put(key(entry.type(), entry.resourceId()), entry) != null) {
                throw new IOException("ReSync Network Resource Manifest Contains Duplicate Entries");
            }
        }
        return Map.copyOf(loaded);
    }

    private void save(Map<String, Entry> values) {
        try {
            StorageSafety.writeUtf8Atomic(file, GSON.toJson(new Manifest(VERSION, values)) + System.lineSeparator());
            loadFailure = null;
        } catch (IOException exception) {
            throw new IllegalStateException("Save ReSync Network Resource Manifest Failed", exception);
        }
    }

    private void requireHealthy() {
        if (loadFailure != null) {
            throw new IllegalStateException("ReSync Network Resource Manifest Is Unavailable: " + loadFailure);
        }
    }

    private void requireWritable() {
        requireHealthy();
        if (quiesced) {
            throw new IllegalStateException("ReSync Network Resource Manifest Persistence Is Quiesced");
        }
    }

    private void validateRoot(Path candidate) throws IOException {
        Path normalized = MigrationPaths.requirePath(candidate, "networkRoot");
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Resource Manifest Root Is Unavailable");
        }
    }

    private static String requireFileName(String value) {
        String normalized = value == null ? "" : value.trim();
        Path path = normalized.isBlank() ? null : Path.of(normalized);
        if (path == null || path.isAbsolute() || path.getNameCount() != 1 || normalized.contains("..")) {
            throw new IllegalArgumentException("ReSync Network Resource Manifest File Name Is Invalid");
        }
        return normalized;
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    static String key(String type, String resourceId) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String normalizedResourceId = resourceId == null ? "" : resourceId.trim();
        return normalizedType + "\u0000" + normalizedResourceId;
    }

    record Entry(String type, String resourceId, long revision, String payloadHash, boolean deleted, long updatedAt) {
        Entry {
            type = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
            resourceId = resourceId == null ? "" : resourceId.trim();
            payloadHash = payloadHash == null ? "" : payloadHash.trim();
            if (type.isBlank() || resourceId.isBlank() || payloadHash.isBlank() || revision < 1 || updatedAt < 0) {
                throw new IllegalArgumentException("ReSync Network Resource Manifest Entry Is Invalid");
            }
        }
    }

    record BlockedResource(String type, String resourceId, long revision, String payloadHash, boolean deleted,
                           String reason, CommittedAsset localStamp) {}

    private record Manifest(int version, Map<String, Entry> entries) {
    }
}
