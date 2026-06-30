package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomArmorDefinition;
import restudio.flow.data.CustomBlockDefinition;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomItemDefinition;
import restudio.flow.data.FlowDataObject;
import restudio.flow.data.FlowDataObjectAdapter;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowDataTypeAdapter;
import restudio.resync.Log;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.StorageSafety;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CustomContentStorage {
    private final File contentDir;
    private final Path contentPath;
    private final File assetsDir;
    private final JsonAssetStore<CustomContentDefinition> assetStore;
    private final Map<String, CustomContentDefinition> cache = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
            .registerTypeAdapter(FlowDataObject.class, new FlowDataObjectAdapter())
            .create();
    private final CustomContentValidator validator = new CustomContentValidator();
    private final ItemAttributeSchemaService attributeSchemaService = new ItemAttributeSchemaService();

    public CustomContentStorage(JavaPlugin plugin) {
        this(plugin.getDataFolder());
    }

    CustomContentStorage(File dataFolder) {
        this.contentDir = new File(dataFolder, "custom-content");
        this.contentPath = contentDir.toPath();
        this.assetsDir = new File(dataFolder, "assets");
        this.assetStore = new JsonAssetStore<>(
            assetsDir.toPath(),
            contentPath,
            ReSyncResourceCatalog.CUSTOM_CONTENT,
            ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.CUSTOM_CONTENT),
            json -> gson.fromJson(json, CustomContentDefinition.class),
            gson::toJson,
            CustomContentDefinition::getId,
            this::defaultFolder
        );
        if (!assetsDir.exists()) {
            assetsDir.mkdirs();
        }
        migrateLegacyAssets();
    }

    public void preloadAll() {
        for (String id : listIds()) {
            get(id);
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
        CustomContentDefinition definition = assetStore.get(safeId);
        if (definition != null) {
            cache.put(safeId, definition);
        }
        return definition;
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
        List<Map<String, Object>> componentErrors = attributeSchemaService.validate(definition.getMaterial(), definition.getComponents());
        if (!componentErrors.isEmpty()) {
            throw new ItemAttributeValidationException(componentErrors);
        }
        try {
            assetStore.save(definition);
            cache.put(safeId, definition);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save custom content: " + safeId, e);
        }
    }

    public void delete(String id) {
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid custom content id");
        }
        try {
            assetStore.delete(safeId);
            cache.remove(safeId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete custom content: " + safeId, e);
        }
    }

    public List<String> listIds() {
        Set<String> ids = new HashSet<>(cache.keySet());
        ids.addAll(assetStore.listIds());
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
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

    private String defaultFolder(CustomContentDefinition definition) {
        return switch (definition != null && definition.getType() != null ? definition.getType().toLowerCase() : "item") {
            case "armor" -> "Content/Armor";
            case "block" -> "Content/Blocks";
            default -> "Content/Items";
        };
    }

    private void migrateLegacyAssets() {
        assetStore.migrateLegacyAssets();
    }
}
