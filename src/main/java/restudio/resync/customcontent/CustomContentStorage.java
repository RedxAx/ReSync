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
import restudio.resync.storage.StorageSafety;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CustomContentStorage {
    private final File contentDir;
    private final Path contentPath;
    private final Map<String, CustomContentDefinition> cache = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final CustomContentValidator validator = new CustomContentValidator();

    public CustomContentStorage(JavaPlugin plugin) {
        this.contentDir = new File(plugin.getDataFolder(), "custom-content");
        this.contentPath = contentDir.toPath();
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
                String id = file.getName().substring(0, file.getName().length() - 5);
                if (safeId(id, "preload") == null) {
                    continue;
                }
                CustomContentDefinition definition = gson.fromJson(StorageSafety.readUtf8(file.toPath()), CustomContentDefinition.class);
                if (definition != null && safeId(definition.getId(), "preload") != null) {
                    cache.put(definition.getId(), definition);
                }
            } catch (IOException e) {
                Log.warn("Failed to load custom content: " + file.getName() + " - " + e.getMessage());
            }
        }
    }

    public CustomContentDefinition get(String id) {
        String safeId = safeId(id, "load");
        if (safeId == null) {
            return null;
        }
        CustomContentDefinition cached = cache.get(safeId);
        if (cached != null) {
            return cached;
        }
        Path file;
        try {
            file = StorageSafety.jsonFile(contentPath, safeId);
        } catch (IOException | IllegalArgumentException e) {
            Log.warn("Failed to resolve custom content: " + safeId + " - " + e.getMessage());
            return null;
        }
        if (!file.toFile().exists()) {
            return null;
        }
        try {
            CustomContentDefinition definition = gson.fromJson(StorageSafety.readUtf8(file), CustomContentDefinition.class);
            if (definition != null && safeId(definition.getId(), "load") != null) {
                cache.put(definition.getId(), definition);
            }
            return definition;
        } catch (IOException e) {
            Log.warn("Failed to load custom content: " + safeId + " - " + e.getMessage());
            return null;
        }
    }

    public void save(CustomContentDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Invalid custom content definition");
        }
        String safeId = safeId(definition.getId(), "save");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid custom content id");
        }
        List<String> errors = validator.validate(definition);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        try {
            StorageSafety.writeUtf8Atomic(StorageSafety.jsonFile(contentPath, safeId), gson.toJson(definition));
            cache.put(safeId, definition);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save custom content: " + safeId, e);
        }
    }

    public void delete(String id) {
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid custom content id");
        }
        try {
            StorageSafety.deleteIfExists(StorageSafety.jsonFile(contentPath, safeId));
            cache.remove(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete custom content: " + safeId, e);
        }
    }

    public List<String> listIds() {
        List<String> ids = new ArrayList<>(cache.keySet());
        File[] files = contentDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                String id = name.substring(0, name.length() - 5);
                if (safeId(id, "list") != null) {
                    ids.add(id);
                }
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

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            Log.warn("Rejected unsafe custom content id during " + action + ": " + id);
            return null;
        }
    }
}
