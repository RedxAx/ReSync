package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.storage.RecoverableJsonStore;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PersistentVariableStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static volatile PersistentVariableStore instance;

    private final Path filePath;
    private final RecoverableJsonStore store;
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private PersistentVariableStore(Path filePath) {
        this.filePath = filePath;
        this.store = new RecoverableJsonStore(filePath, GSON);
    }

    public static PersistentVariableStore getInstance() {
        if (instance == null) {
            synchronized (PersistentVariableStore.class) {
                if (instance == null) {
                    Path basePath = ReSync.getInstance() != null
                        ? ReSync.getInstance().getDataFolder().toPath()
                        : Path.of(".");
                    instance = new PersistentVariableStore(basePath.resolve("flow-variables.json"));
                }
            }
        }
        return instance;
    }

    public Object get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        loadIfNeeded();
        return variables.get(key);
    }

    public boolean contains(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        loadIfNeeded();
        return variables.containsKey(key);
    }

    public Map<String, Object> getAll() {
        loadIfNeeded();
        return new LinkedHashMap<>(variables);
    }

    public synchronized void set(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        loadIfNeeded();
        if (value == null) {
            variables.remove(key);
        } else {
            variables.put(key, normalizeValue(value));
        }
        save();
    }

    public synchronized void remove(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        loadIfNeeded();
        variables.remove(key);
        save();
    }

    private void loadIfNeeded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            if (Files.exists(filePath)) {
                try {
                    Map<String, Object> data = GSON.fromJson(store.load(), MAP_TYPE);
                    if (data != null) {
                        variables.putAll(data);
                    }
                } catch (RuntimeException | IOException e) {
                    Log.warn("Failed to load persistent variables: " + e.getMessage());
                }
            }
            loaded = true;
        }
    }

    private void save() {
        try {
            store.save(GSON.toJsonTree(new LinkedHashMap<>(variables), MAP_TYPE));
        } catch (IOException e) {
            Log.warn("Failed to save persistent variables: " + e.getMessage());
        }
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object entry : iterable) {
                normalized.add(normalizeValue(entry));
            }
            return normalized;
        }
        return value.toString();
    }
}
