package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomArmorDefinition;
import restudio.flow.data.CustomBlockDefinition;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomItemDefinition;
import restudio.resync.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CustomContentStorage {
    private final File contentDir;
    private final Map<String, CustomContentDefinition> cache = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public CustomContentStorage(JavaPlugin plugin) {
        this.contentDir = new File(plugin.getDataFolder(), "custom-content");
        if (!contentDir.exists()) {
            contentDir.mkdirs();
        }
    }

    public void preloadAll() {
        File[] files = contentDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                CustomContentDefinition definition = gson.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), CustomContentDefinition.class);
                if (definition != null && definition.getId() != null) {
                    cache.put(definition.getId(), definition);
                }
            } catch (IOException e) {
                Log.warn("Failed to load custom content: " + file.getName() + " - " + e.getMessage());
            }
        }
    }

    public CustomContentDefinition get(String id) {
        if (id == null) {
            return null;
        }
        CustomContentDefinition cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        File file = new File(contentDir, id + ".json");
        if (!file.exists()) {
            return null;
        }
        try {
            CustomContentDefinition definition = gson.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), CustomContentDefinition.class);
            if (definition != null && definition.getId() != null) {
                cache.put(definition.getId(), definition);
            }
            return definition;
        } catch (IOException e) {
            Log.warn("Failed to load custom content: " + id + " - " + e.getMessage());
            return null;
        }
    }

    public void save(CustomContentDefinition definition) {
        if (definition == null || definition.getId() == null || definition.getId().isBlank()) {
            return;
        }
        cache.put(definition.getId(), definition);
        try {
            Files.writeString(new File(contentDir, definition.getId() + ".json").toPath(), gson.toJson(definition), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("Failed to save custom content: " + definition.getId() + " - " + e.getMessage());
        }
    }

    public void delete(String id) {
        if (id == null) {
            return;
        }
        cache.remove(id);
        try {
            Files.deleteIfExists(new File(contentDir, id + ".json").toPath());
        } catch (IOException e) {
            Log.warn("Failed to delete custom content: " + id + " - " + e.getMessage());
        }
    }

    public List<String> listIds() {
        List<String> ids = new ArrayList<>(cache.keySet());
        File[] files = contentDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                ids.add(name.substring(0, name.length() - 5));
            }
        }
        return ids.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<CustomContentDefinition> getAll() {
        List<CustomContentDefinition> definitions = new ArrayList<>();
        for (String id : listIds()) {
            CustomContentDefinition definition = get(id);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        definitions.sort(Comparator.comparing(CustomContentDefinition::getId, String.CASE_INSENSITIVE_ORDER));
        return definitions;
    }

    public List<CustomContentDefinition> getByFlow(String flowId) {
        if (flowId == null) {
            return List.of();
        }
        return getAll().stream().filter(definition -> flowId.equals(definition.getFlowId())).toList();
    }

    public List<CustomContentDefinition> getByType(String type) {
        if (type == null) {
            return List.of();
        }
        return getAll().stream().filter(definition -> type.equalsIgnoreCase(definition.getType())).toList();
    }

    public void ensureDefaultsForFlow(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return;
        }
        String itemId = flowId + ".default_item";
        String blockId = flowId + ".default_block";
        String armorId = flowId + ".default_armor";
        if (get(itemId) == null) {
            CustomItemDefinition item = new CustomItemDefinition();
            item.setId(itemId);
            item.setFlowId(flowId);
            item.setDisplayName("Default Item");
            item.setMaterial("STICK");
            item.getAbilities().add(new CustomAbilityBinding(itemId + ".use", "item.use", flowId));
            save(item);
        }
        if (get(blockId) == null) {
            CustomBlockDefinition block = new CustomBlockDefinition();
            block.setId(blockId);
            block.setFlowId(flowId);
            block.setDisplayName("Default Block");
            block.setMaterial("STONE");
            block.getAbilities().add(new CustomAbilityBinding(blockId + ".interact", "block.interact", flowId));
            save(block);
        }
        if (get(armorId) == null) {
            CustomArmorDefinition armor = new CustomArmorDefinition();
            armor.setId(armorId);
            armor.setFlowId(flowId);
            armor.setDisplayName("Default Armor");
            armor.setMaterial("IRON_CHESTPLATE");
            armor.setArmorSlot("chest");
            armor.getAbilities().add(new CustomAbilityBinding(armorId + ".tick", "armor.tick", flowId));
            save(armor);
        }
    }
}
