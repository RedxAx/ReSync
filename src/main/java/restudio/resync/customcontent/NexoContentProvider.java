package restudio.resync.customcontent;

import com.nexomc.nexo.api.NexoBlocks;
import com.nexomc.nexo.api.NexoFurniture;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import com.nexomc.nexo.mechanics.custom_block.CustomBlockMechanic;
import com.nexomc.nexo.mechanics.furniture.FurnitureMechanic;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class NexoContentProvider implements CustomContentProvider {
    private final CustomContentStorage contentStorage;
    private final VanillaContentProvider vanillaProvider;

    public NexoContentProvider(CustomContentStorage contentStorage, VanillaContentProvider vanillaProvider) {
        this.contentStorage = contentStorage;
        this.vanillaProvider = vanillaProvider;
    }

    @Override
    public String getId() {
        return "nexo";
    }

    @Override
    public boolean isAvailable() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Nexo");
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public ItemStack createItem(CustomContentDefinition definition, int amount) {
        if (!isAvailable()) {
            return null;
        }
        String itemId = externalId(definition);
        if (itemId.isBlank()) {
            return null;
        }
        try {
            ItemBuilder builder = NexoItems.itemFromId(itemId);
            if (builder == null) {
                return null;
            }
            ItemStack item = builder.build();
            if (item == null) {
                return null;
            }
            item.setAmount(Math.max(1, amount));
            return vanillaProvider.stampItem(item, definition);
        } catch (Throwable throwable) {
            Log.warn("Failed to create Nexo item " + itemId + ": " + throwable.getMessage());
            return null;
        }
    }

    @Override
    public String identifyItem(ItemStack item) {
        if (!isAvailable() || item == null) {
            return null;
        }
        String stamped = vanillaProvider.identifyItem(item);
        if (isNexoDefinition(stamped)) {
            return stamped;
        }
        try {
            String itemId = NexoItems.idFromItem(item);
            return contentIdForExternal(itemId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public String identifyBlock(Location location) {
        if (!isAvailable() || location == null) {
            return null;
        }
        String stamped = vanillaProvider.identifyBlock(location);
        if (isNexoDefinition(stamped)) {
            return stamped;
        }
        try {
            CustomBlockMechanic blockMechanic = NexoBlocks.customBlockMechanic(location);
            if (blockMechanic != null) {
                return contentIdForExternal(blockMechanic.getItemID());
            }
            FurnitureMechanic furnitureMechanic = NexoFurniture.furnitureMechanic(location);
            if (furnitureMechanic != null) {
                return contentIdForExternal(furnitureMechanic.getItemID());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public void markPlacedBlock(Location location, CustomContentDefinition definition) {
        vanillaProvider.markPlacedBlock(location, definition);
    }

    @Override
    public void clearPlacedBlock(Location location) {
        vanillaProvider.clearPlacedBlock(location);
    }

    public List<String> itemIds() {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            Set<String> excluded = new HashSet<>();
            excluded.addAll(lowerSet(blockIds()));
            excluded.addAll(lowerSet(furnitureIds()));
            excluded.addAll(lowerSet(armorIds()));
            return NexoItems.itemNames().stream()
                .filter(id -> id != null && !excluded.contains(id.toLowerCase(Locale.ROOT)))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public List<String> blockIds() {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            return Arrays.stream(NexoBlocks.blockIDs()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public List<String> furnitureIds() {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            return Arrays.stream(NexoFurniture.furnitureIDs()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public List<String> armorIds() {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            return NexoItems.itemNames().stream()
                .filter(this::isArmorItem)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public List<String> blockContentIds() {
        Set<String> values = new LinkedHashSet<>();
        values.addAll(blockIds());
        values.addAll(furnitureIds());
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private boolean isArmorItem(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        try {
            ItemBuilder builder = NexoItems.itemFromId(itemId);
            if (builder == null) {
                return false;
            }
            ItemStack item = builder.build();
            return item != null && isArmorMaterial(item.getType());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isArmorMaterial(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_HELMET")
            || name.endsWith("_CHESTPLATE")
            || name.endsWith("_LEGGINGS")
            || name.endsWith("_BOOTS")
            || name.equals("TURTLE_HELMET");
    }

    private Set<String> lowerSet(List<String> values) {
        Set<String> set = new HashSet<>();
        for (String value : values) {
            if (value != null) {
                set.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    private String contentIdForExternal(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        for (CustomContentDefinition definition : contentStorage.getAll()) {
            if (definition != null && "nexo".equalsIgnoreCase(definition.getProvider()) && externalId.equalsIgnoreCase(externalId(definition))) {
                return definition.getId();
            }
        }
        return null;
    }

    private boolean isNexoDefinition(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            return false;
        }
        CustomContentDefinition definition = contentStorage.get(contentId);
        return definition != null && "nexo".equalsIgnoreCase(definition.getProvider());
    }

    private String externalId(CustomContentDefinition definition) {
        if (definition == null) {
            return "";
        }
        String externalId = definition.getExternalId();
        String value = externalId != null && !externalId.isBlank() ? externalId : definition.getId();
        return value != null ? value.toLowerCase(Locale.ROOT).trim() : "";
    }
}
