package restudio.resync.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class PlayerNpcInstanceStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final StorageWriter writer;
    private volatile Path file;
    private final Map<String, Position> positions = new LinkedHashMap<>();
    private PersistenceState state = PersistenceState.OPEN;

    PlayerNpcInstanceStorage(Path file) {
        this(file, StorageSafety::writeUtf8Atomic);
    }

    PlayerNpcInstanceStorage(Path file, StorageWriter writer) {
        this.file = requireFile(file);
        this.writer = writer == null ? StorageSafety::writeUtf8Atomic : writer;
        try {
            positions.putAll(loadStrict(this.file));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Player NPC instance storage could not be loaded: " + this.file, exception);
        }
    }

    synchronized Path file() {
        return file;
    }

    synchronized Map<String, Position> snapshot() {
        return Map.copyOf(positions);
    }

    synchronized boolean save(String id, Location location) {
        requireWritable();
        if (id == null || id.isBlank() || location == null || location.getWorld() == null) {
            return false;
        }
        Map<String, Position> next = new LinkedHashMap<>(positions);
        next.put(id.trim(), Position.from(location));
        return commit(next, file);
    }

    synchronized boolean remove(String id) {
        requireWritable();
        if (id == null || id.isBlank()) {
            return false;
        }
        id = id.trim();
        if (!positions.containsKey(id)) {
            return true;
        }
        Map<String, Position> next = new LinkedHashMap<>(positions);
        next.remove(id);
        return commit(next, file);
    }

    synchronized boolean contains(String id) {
        return id != null && !id.isBlank() && positions.containsKey(id.trim());
    }

    synchronized void flush() throws IOException {
        writeAndVerify(positions, file);
    }

    synchronized void quiesce() throws IOException {
        if (state == PersistenceState.QUIESCED) {
            return;
        }
        if (state != PersistenceState.OPEN) {
            throw new IOException("Player NPC instance persistence is already quiescing");
        }
        state = PersistenceState.QUIESCING;
        try {
            writeAndVerify(positions, file);
            state = PersistenceState.QUIESCED;
        } catch (IOException | RuntimeException failure) {
            state = PersistenceState.OPEN;
            throw failure;
        }
    }

    synchronized void resume() throws IOException {
        if (state == PersistenceState.OPEN) {
            return;
        }
        if (state != PersistenceState.QUIESCED) {
            throw new IOException("Player NPC instance persistence is not quiesced");
        }
        healthCheck();
        state = PersistenceState.OPEN;
    }

    synchronized void rebind(Path candidate) throws IOException {
        if (state != PersistenceState.QUIESCED) {
            throw new IOException("Player NPC instance persistence must be quiesced before rebind");
        }
        Path nextFile = requireFile(candidate);
        Map<String, Position> staged = loadStrict(nextFile);
        if (!Files.exists(nextFile, LinkOption.NOFOLLOW_LINKS)) {
            writeAndVerify(staged, nextFile);
        }
        Path previousFile = file;
        Map<String, Position> previous = new LinkedHashMap<>(positions);
        try {
            file = nextFile;
            positions.clear();
            positions.putAll(staged);
            healthCheck();
        } catch (IOException | RuntimeException failure) {
            file = previousFile;
            positions.clear();
            positions.putAll(previous);
            throw failure;
        }
    }

    synchronized void healthCheck() throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("Player NPC instance storage is missing: " + file);
        }
        Map<String, Position> persisted = loadStrict(file);
        if (!persisted.equals(positions)) {
            throw new IOException("Player NPC instance storage is out of sync: " + file);
        }
    }

    synchronized boolean isQuiesced() {
        return state == PersistenceState.QUIESCED;
    }

    synchronized boolean isWritable() {
        return state == PersistenceState.OPEN;
    }

    private boolean commit(Map<String, Position> next, Path target) {
        try {
            writeAndVerify(next, target);
            positions.clear();
            positions.putAll(next);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void writeAndVerify(Map<String, Position> values, Path target) throws IOException {
        Path normalized = requireFile(target);
        JsonObject root = new JsonObject();
        values.forEach((id, position) -> root.add(id, position.toJson()));
        String content = GSON.toJson(root);
        writer.write(normalized, content);
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
            || !content.equals(StorageSafety.readUtf8(normalized))) {
            throw new IOException("Player NPC instance storage write verification failed: " + normalized);
        }
    }

    private Map<String, Position> loadStrict(Path target) throws IOException {
        Path normalized = requireFile(target);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(normalized)) {
            throw new IOException("Player NPC instance storage must be a regular non-symbolic-link file: " + normalized);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(StorageSafety.readUtf8(normalized));
        } catch (RuntimeException exception) {
            throw new IOException("Player NPC instance storage contains invalid JSON: " + normalized, exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Player NPC instance storage root must be an object: " + normalized);
        }
        Map<String, Position> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
            String id = entry.getKey() == null ? "" : entry.getKey().trim();
            if (id.isBlank()) {
                throw new IOException("Player NPC instance storage contains a blank NPC ID: " + normalized);
            }
            Position position = Position.fromStrict(entry.getValue());
            if (loaded.putIfAbsent(id, position) != null) {
                throw new IOException("Player NPC instance storage contains a duplicate NPC ID: " + id);
            }
        }
        return loaded;
    }

    private Path requireFile(Path candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Player NPC instance storage file is required");
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || !"player-npcs.json".equals(normalized.getFileName().toString())) {
            throw new IllegalArgumentException("Player NPC persistence file must be runtime/player-npcs.json");
        }
        try {
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent)) {
                throw new IOException("Player NPC instance storage directory cannot be symbolic");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Player NPC instance storage directory could not be prepared: " + parent, exception);
        }
        return normalized;
    }

    private void requireWritable() {
        if (state != PersistenceState.OPEN) {
            throw new IllegalStateException("Player NPC instance persistence is " + state.name().toLowerCase() + "; mutation rejected");
        }
    }

    @FunctionalInterface
    interface StorageWriter {
        void write(Path file, String content) throws IOException;
    }

    record Position(String world, double x, double y, double z, float yaw, float pitch) {
        static Position from(Location location) {
            return new Position(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        }

        static Position fromStrict(JsonElement element) throws IOException {
            if (element == null || !element.isJsonObject()) {
                throw new IOException("Player NPC position must be an object");
            }
            JsonObject object = element.getAsJsonObject();
            String world = text(object, "world");
            double x = decimal(object, "x");
            double y = decimal(object, "y");
            double z = decimal(object, "z");
            double yaw = decimal(object, "yaw");
            double pitch = decimal(object, "pitch");
            if (world.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(yaw) || !Double.isFinite(pitch)) {
                throw new IOException("Player NPC position contains an invalid world or coordinate");
            }
            if (Math.abs(yaw) > 360D || Math.abs(pitch) > 90D) {
                throw new IOException("Player NPC position contains an invalid rotation");
            }
            return new Position(world, x, y, z, (float) yaw, (float) pitch);
        }

        Location resolve(Server server) {
            World resolvedWorld = server != null ? server.getWorld(world) : null;
            return resolvedWorld != null ? new Location(resolvedWorld, x, y, z, yaw, pitch) : null;
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("world", world);
            object.addProperty("x", x);
            object.addProperty("y", y);
            object.addProperty("z", z);
            object.addProperty("yaw", yaw);
            object.addProperty("pitch", pitch);
            return object;
        }

        private static String text(JsonObject object, String key) throws IOException {
            if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()) {
                throw new IOException("Player NPC position field is missing or invalid: " + key);
            }
            return object.get(key).getAsString().trim();
        }

        private static double decimal(JsonObject object, String key) throws IOException {
            if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isNumber()) {
                throw new IOException("Player NPC position field is missing or invalid: " + key);
            }
            try {
                return object.get(key).getAsDouble();
            } catch (RuntimeException exception) {
                throw new IOException("Player NPC position field is invalid: " + key, exception);
            }
        }
    }

    private enum PersistenceState {
        OPEN,
        QUIESCING,
        QUIESCED
    }
}
