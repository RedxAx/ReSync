package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.storage.StorageSafety;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VanillaContentProvider implements CustomContentProvider {
    private final NamespacedKey contentTypeKey;
    private final NamespacedKey contentIdKey;
    private final NamespacedKey contentVersionKey;
    private final NamespacedKey instanceIdKey;
    private final File blockFile;
    private final Gson gson = new Gson();
    private final Type mapType = new TypeToken<Map<String, String>>() {}.getType();
    private final Map<String, String> blocks = new HashMap<>();
    private final ItemAttributeSchemaService attributeSchemaService;

    public VanillaContentProvider() {
        this(ReSync.getInstance(), new ItemAttributeSchemaService());
    }

    public VanillaContentProvider(ItemAttributeSchemaService attributeSchemaService) {
        this(ReSync.getInstance(), attributeSchemaService);
    }

    public VanillaContentProvider(JavaPlugin plugin, ItemAttributeSchemaService attributeSchemaService) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        this.contentTypeKey = new NamespacedKey(plugin, "content_type");
        this.contentIdKey = new NamespacedKey(plugin, "content_id");
        this.contentVersionKey = new NamespacedKey(plugin, "content_version");
        this.instanceIdKey = new NamespacedKey(plugin, "instance_id");
        this.blockFile = new File(plugin.getDataFolder(), "custom-blocks.json");
        this.attributeSchemaService = attributeSchemaService != null ? attributeSchemaService : new ItemAttributeSchemaService();
        loadBlocks();
    }

    @Override
    public String getId() {
        return "vanilla";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public ItemStack createItem(CustomContentDefinition definition, int amount) {
        Material material = Material.matchMaterial(definition.getMaterial() != null ? definition.getMaterial() : "STICK");
        ItemStack item = new ItemStack(material != null ? material : Material.STICK, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (definition.getDisplayName() != null && !definition.getDisplayName().isBlank()) {
                meta.displayName(TextFormatter.parseItemName(definition.getDisplayName()));
            }
            if (definition.getLore() != null && !definition.getLore().isEmpty()) {
                meta.lore(definition.getLore().stream().map(TextFormatter::parseItemLore).toList());
            }
            if (definition.getCustomModelData() != null) {
                meta.setCustomModelData(definition.getCustomModelData());
            }
            item.setItemMeta(meta);
        }
        item = applyComponents(item, definition);
        return stampItem(item, definition);
    }

    public ItemStack stampItem(ItemStack item, CustomContentDefinition definition) {
        return stampItem(item, definition, UUID.randomUUID().toString());
    }

    public ItemStack restampItem(ItemStack item, CustomContentDefinition definition, String instanceId) {
        return stampItem(item, definition, instanceId != null && !instanceId.isBlank() ? instanceId : UUID.randomUUID().toString());
    }

    private ItemStack stampItem(ItemStack item, CustomContentDefinition definition, String instanceId) {
        if (item == null || definition == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(contentTypeKey, PersistentDataType.STRING, definition.getType());
        meta.getPersistentDataContainer().set(contentIdKey, PersistentDataType.STRING, definition.getId());
        meta.getPersistentDataContainer().set(contentVersionKey, PersistentDataType.INTEGER, definition.getVersion());
        meta.getPersistentDataContainer().set(instanceIdKey, PersistentDataType.STRING, instanceId);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public String identifyItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(contentIdKey, PersistentDataType.STRING);
    }

    public String getInstanceId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null ? meta.getPersistentDataContainer().getOrDefault(instanceIdKey, PersistentDataType.STRING, "") : "";
    }

    public String getStampedContentId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null ? meta.getPersistentDataContainer().getOrDefault(contentIdKey, PersistentDataType.STRING, "") : "";
    }

    public ItemStack clearStamp(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().remove(contentTypeKey);
        meta.getPersistentDataContainer().remove(contentIdKey);
        meta.getPersistentDataContainer().remove(contentVersionKey);
        meta.getPersistentDataContainer().remove(instanceIdKey);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public String identifyBlock(Location location) {
        return location != null ? blocks.get(blockKey(location)) : null;
    }

    @Override
    public void markPlacedBlock(Location location, CustomContentDefinition definition) {
        if (location == null || definition == null) {
            return;
        }
        blocks.put(blockKey(location), definition.getId());
        saveBlocks();
    }

    @Override
    public void clearPlacedBlock(Location location) {
        if (location == null) {
            return;
        }
        blocks.remove(blockKey(location));
        saveBlocks();
    }

    public Map<String, String> getPlacedBlocks() {
        return new HashMap<>(blocks);
    }

    private ItemStack applyComponents(ItemStack item, CustomContentDefinition definition) {
        if (definition.getComponents() == null || definition.getComponents().isEmpty()) {
            return item;
        }
        try {
            return attributeSchemaService.applyComponents(item, definition.getComponents());
        } catch (RuntimeException failure) {
            Log.warn("Failed to apply custom content components for " + definition.getId() + ": " + failure.getMessage());
            return item;
        }
    }

    private String blockKey(Location location) {
        String world = location.getWorld() != null ? location.getWorld().getName() : "";
        return world + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void loadBlocks() {
        if (!blockFile.exists()) {
            return;
        }
        try {
            Map<String, String> loaded = gson.fromJson(StorageSafety.readUtf8(blockFile.toPath()), mapType);
            if (loaded != null) {
                blocks.putAll(loaded);
            }
        } catch (IOException e) {
            Log.warn("Failed to load custom blocks: " + e.getMessage());
        }
    }

    private void saveBlocks() {
        try {
            StorageSafety.writeUtf8Atomic(blockFile.toPath(), gson.toJson(blocks));
        } catch (IOException e) {
            Log.warn("Failed to save custom blocks: " + e.getMessage());
        }
    }
}
