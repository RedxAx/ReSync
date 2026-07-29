package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class RecoverableJsonStore {
    private static final int SCHEMA_VERSION = 1;
    private final Path file;
    private final Path previous;
    private final Path quarantine;
    private final Gson gson;

    public RecoverableJsonStore(Path file, Gson gson) {
        this.file = file;
        this.previous = file.resolveSibling(file.getFileName() + ".previous");
        this.quarantine = file.getParent().resolve(".quarantine").resolve("journals");
        this.gson = gson;
    }

    public synchronized JsonElement load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return decode(StorageSafety.readUtf8(file));
        } catch (RuntimeException | IOException currentFailure) {
            quarantine(file);
            if (!Files.isRegularFile(previous)) {
                throw new IOException("No valid journal version remains: " + file, currentFailure);
            }
            byte[] recovered;
            JsonElement payload;
            try {
                recovered = Files.readAllBytes(previous);
                payload = decode(new String(recovered, StandardCharsets.UTF_8));
            } catch (RuntimeException | IOException previousFailure) {
                quarantine(previous);
                IOException failure = new IOException("Current and previous journal versions are corrupt: " + file, previousFailure);
                failure.addSuppressed(currentFailure);
                throw failure;
            }
            try {
                StorageSafety.writeBytesAtomic(file, recovered);
            } catch (IOException repairFailure) {
                repairFailure.addSuppressed(currentFailure);
                throw new IOException("Failed to repair recovered journal: " + file, repairFailure);
            }
            return payload;
        }
    }

    public synchronized void save(JsonElement payload) throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file)) {
            byte[] current = Files.readAllBytes(file);
            try {
                decode(new String(current, StandardCharsets.UTF_8));
                StorageSafety.writeBytesAtomic(previous, current);
            } catch (RuntimeException failure) {
                quarantine(file);
            }
        }
        long revision = Math.max(revision(file), revision(previous)) + 1L;
        JsonElement safePayload = payload != null ? payload.deepCopy() : new JsonObject();
        JsonObject envelope = new JsonObject();
        envelope.addProperty("schemaVersion", SCHEMA_VERSION);
        envelope.addProperty("revision", revision);
        envelope.addProperty("hash", StorageSafety.sha256(gson.toJson(safePayload)));
        envelope.add("payload", safePayload);
        StorageSafety.writeUtf8Atomic(file, gson.toJson(envelope));
    }

    private JsonElement decode(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            return parsed;
        }
        JsonObject object = parsed.getAsJsonObject();
        if (!object.has("schemaVersion") || !object.has("payload")) {
            return parsed;
        }
        if (object.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported journal schema version");
        }
        JsonElement payload = object.get("payload");
        String expected = object.has("hash") ? object.get("hash").getAsString() : "";
        String actual = StorageSafety.sha256(gson.toJson(payload));
        if (expected.isBlank() || !expected.equals(actual)) {
            throw new IllegalStateException("Journal integrity check failed");
        }
        return payload;
    }

    private long revision(Path candidate) {
        if (!Files.isRegularFile(candidate)) {
            return 0L;
        }
        try {
            JsonElement parsed = JsonParser.parseString(StorageSafety.readUtf8(candidate));
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("revision")) {
                return Math.max(0L, parsed.getAsJsonObject().get("revision").getAsLong());
            }
        } catch (RuntimeException | IOException ignored) {
        }
        return 0L;
    }

    private void quarantine(Path candidate) throws IOException {
        if (!Files.isRegularFile(candidate)) {
            return;
        }
        Files.createDirectories(quarantine);
        Path target = quarantine.resolve(candidate.getFileName() + "." + UUID.randomUUID() + ".corrupt");
        Files.copy(candidate, target);
        StorageSafety.forceDirectory(quarantine);
    }
}
