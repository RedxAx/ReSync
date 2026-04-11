package restudio.resync.flow.triggers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TriggerRegistry {
    private final File file;
    private final Map<String, TriggerBinding> bindings = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public TriggerRegistry(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "triggers.json");
        load();
    }

    public synchronized List<TriggerBinding> getBindings() {
        return new ArrayList<>(bindings.values());
    }

    public synchronized List<TriggerBinding> getBindings(TriggerType type) {
        List<TriggerBinding> filtered = new ArrayList<>();
        for (TriggerBinding binding : bindings.values()) {
            if (binding.getType() == type) {
                filtered.add(binding);
            }
        }
        return filtered;
    }

    public synchronized void setBindings(List<TriggerBinding> newBindings) {
        bindings.clear();
        if (newBindings != null) {
            for (TriggerBinding binding : newBindings) {
                if (binding.getId() != null) {
                    bindings.put(binding.getId(), binding);
                }
            }
        }
        save();
    }

    public synchronized void addBinding(TriggerBinding binding) {
        if (binding == null || binding.getId() == null) {
            return;
        }
        bindings.put(binding.getId(), binding);
        save();
    }

    public synchronized void removeBinding(String id) {
        if (id == null) {
            return;
        }
        bindings.remove(id);
        save();
    }

    public synchronized void replaceFlowBindings(String flowId, TriggerType type, List<TriggerBinding> newBindings) {
        if (flowId == null || type == null) {
            return;
        }
        bindings.values().removeIf(binding -> flowId.equals(binding.getFlowId()) && type == binding.getType());
        if (newBindings != null) {
            for (TriggerBinding binding : newBindings) {
                if (binding.getId() != null) {
                    bindings.put(binding.getId(), binding);
                }
            }
        }
        save();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            List<TriggerBinding> loaded = gson.fromJson(json, new TypeToken<List<TriggerBinding>>() {}.getType());
            if (loaded != null) {
                for (TriggerBinding binding : loaded) {
                    if (binding.getId() != null) {
                        bindings.put(binding.getId(), binding);
                    }
                }
            }
        } catch (IOException e) {
            Log.warn("Failed to load trigger bindings: " + e.getMessage());
        }
    }

    private void save() {
        try {
            String json = gson.toJson(getBindings());
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("Failed to save trigger bindings: " + e.getMessage());
        }
    }
}
