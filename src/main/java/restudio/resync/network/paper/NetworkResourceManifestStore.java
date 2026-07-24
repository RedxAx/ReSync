package restudio.resync.network.paper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.resync.network.NetworkResourceMetadata;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class NetworkResourceManifestStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final int VERSION = 1;
    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    NetworkResourceManifestStore(Path dataDirectory) {
        file = dataDirectory.toAbsolutePath().normalize().resolve("network/resource-manifest.json");
        try {
            load();
        } catch (RuntimeException | IOException exception) {
            entries.clear();
            Log.warn("Ignored invalid ReSync network resource manifest: " + exception.getMessage());
        }
    }

    synchronized Entry get(String type, String resourceId) {
        return entries.get(key(type, resourceId));
    }

    synchronized Map<String, Entry> snapshot() {
        return Map.copyOf(entries);
    }

    synchronized void put(NetworkResourceMetadata metadata) {
        entries.put(metadata.key(), new Entry(metadata.type(), metadata.resourceId(), metadata.revision(), metadata.payloadHash(), metadata.deleted(), metadata.updatedAt()));
        save();
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Manifest manifest = GSON.fromJson(StorageSafety.readUtf8(file), new TypeToken<Manifest>() {
        }.getType());
        if (manifest == null || manifest.version() != VERSION || manifest.entries() == null) {
            throw new IOException("ReSync Network Resource Manifest Is Invalid");
        }
        for (Entry entry : manifest.entries().values()) {
            if (entry != null) {
                entries.put(key(entry.type(), entry.resourceId()), entry);
            }
        }
    }

    private void save() {
        try {
            StorageSafety.writeUtf8Atomic(file, GSON.toJson(new Manifest(VERSION, entries)) + System.lineSeparator());
        } catch (IOException exception) {
            throw new IllegalStateException("Save ReSync Network Resource Manifest Failed", exception);
        }
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

    private record Manifest(int version, Map<String, Entry> entries) {
    }
}
