package restudio.resync.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import restudio.resync.Log;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class PlayerNpcInstanceStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final StorageWriter writer;
    private final Map<String, Position> positions = new LinkedHashMap<>();

    PlayerNpcInstanceStorage(Path file) {
        this(file, StorageSafety::writeUtf8Atomic);
    }

    PlayerNpcInstanceStorage(Path file, StorageWriter writer) {
        this.file = file;
        this.writer = writer;
        load();
    }

    synchronized Map<String, Position> snapshot() {
        return Map.copyOf(positions);
    }

    synchronized boolean save(String id, Location location) {
        if (id == null || id.isBlank() || location == null || location.getWorld() == null) {
            return false;
        }
        Map<String, Position> next = new LinkedHashMap<>(positions);
        next.put(id, Position.from(location));
        return commit(next);
    }

    synchronized boolean remove(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        if (!positions.containsKey(id)) {
            return true;
        }
        Map<String, Position> next = new LinkedHashMap<>(positions);
        next.remove(id);
        return commit(next);
    }

    synchronized boolean contains(String id) {
        return id != null && positions.containsKey(id);
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(StorageSafety.readUtf8(file));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Root must be an object");
            }
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                Position position = Position.from(entry.getValue());
                if (!entry.getKey().isBlank() && position != null) {
                    positions.put(entry.getKey(), position);
                }
            }
        } catch (IOException | RuntimeException exception) {
            positions.clear();
            Log.warn("Failed to load persistent Player NPC instances: " + exception.getMessage());
        }
    }

    private boolean commit(Map<String, Position> next) {
        if (file == null) {
            return false;
        }
        JsonObject root = new JsonObject();
        next.forEach((id, position) -> root.add(id, position.toJson()));
        try {
            writer.write(file, GSON.toJson(root));
            positions.clear();
            positions.putAll(next);
            return true;
        } catch (IOException exception) {
            Log.warn("Failed to save persistent Player NPC instances: " + exception.getMessage());
            return false;
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

        static Position from(JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            String world = text(object, "world");
            double x = decimal(object, "x");
            double y = decimal(object, "y");
            double z = decimal(object, "z");
            double yaw = decimal(object, "yaw");
            double pitch = decimal(object, "pitch");
            if (world.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(yaw) || !Double.isFinite(pitch)) {
                return null;
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

        private static String text(JsonObject object, String key) {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString().trim() : "";
        }

        private static double decimal(JsonObject object, String key) {
            try {
                return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : 0.0;
            } catch (RuntimeException exception) {
                return Double.NaN;
            }
        }
    }
}
