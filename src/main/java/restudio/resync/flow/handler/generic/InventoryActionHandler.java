package restudio.resync.flow.handler.generic;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import restudio.flow.data.FlowNode;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class InventoryActionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final ItemAttributeSchemaService itemComponents = new ItemAttributeSchemaService();

    public InventoryActionHandler() {
        operations.put("player_has_item", (ctx, node) -> {
            Player player = ctx.getPlayer();
            if (player == null) throw new IllegalArgumentException("Player is required");
            String matName = ctx.getInputValue(node, "material", String.class, "STONE");
            Material mat = Material.getMaterial(matName.toUpperCase());
            if (mat == null) throw new IllegalArgumentException("Inventory material is invalid");
            boolean hasItem = false;
            int count = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == mat) {
                    hasItem = true;
                    count += item.getAmount();
                }
            }
            ctx.setOutput(node, "has", hasItem);
            ctx.setOutput(node, "count", count);
        });

        operations.put("player_remove_item", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            String matName = ctx.getInputValue(node, "material", String.class, "STONE");
            int amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (amount < 1) throw new IllegalArgumentException("Item amount must be positive");
            Material mat = requireMaterial(matName);
            if (countMaterial(player.getInventory(), mat) < amount) throw new IllegalArgumentException("Player inventory does not contain the requested material amount");
            ItemStack toRemove = new ItemStack(mat, amount);
            player.getInventory().removeItem(toRemove);
        });

        operations.put("player_clear_inv", (ctx, node) -> {
            Player player = ctx.getPlayer();
            if (player == null) throw new IllegalArgumentException("Player is required");
            player.getInventory().clear();
        });

        operations.put("inventory_open_gui", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            String title = ctx.getInputValue(node, "title", String.class, "Inventory");
            Integer rows = ctx.getInputValue(node, "rows", Integer.class, 1);
            if (rows < 1 || rows > 6) throw new IllegalArgumentException("Inventory rows must be between 1 and 6");
            if (title.isBlank()) throw new IllegalArgumentException("Inventory title is required");
            Inventory inventory = Bukkit.createInventory(null, rows * 9, TextFormatter.parse(title));
            player.openInventory(inventory);
        });

        operations.put("inventory_close", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            player.closeInventory();
        });

        operations.put("inventory_set_title", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            String title = ctx.getInputValue(node, "title", String.class, "Inventory");
            if (title.isBlank()) throw new IllegalArgumentException("Inventory title is required");
            player.getOpenInventory().setTitle(title);
        });

        operations.put("inventory_set_rows", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            Integer rows = ctx.getInputValue(node, "rows", Integer.class, 1);
            if (rows < 1 || rows > 6) throw new IllegalArgumentException("Inventory rows must be between 1 and 6");
            resizeOpenInventory(player, rows);
        });

        operations.put("inventory_get_contents", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            ctx.setOutput(node, "contents", inventory.getContents());
        });

        operations.put("inventory_set_contents", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            ItemStack[] contents = ctx.getInputValue(node, "contents", ItemStack[].class, new ItemStack[0]);
            if (contents.length > inventory.getSize()) throw new IllegalArgumentException("Inventory contents exceed the inventory size");
            inventory.setContents(contents);
        });

        operations.put("inventory_add_item", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            ItemStack item = requireItem(ctx, node, "item");
            addItemFully(inventory, item.clone());
        });

        operations.put("inventory_remove_item", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            ItemStack item = requireItem(ctx, node, "item");
            if (!inventory.containsAtLeast(item, item.getAmount())) throw new IllegalArgumentException("Inventory does not contain the requested item amount");
            inventory.removeItem(item.clone());
        });

        operations.put("inventory_has_item", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            ItemStack item = requireItem(ctx, node, "item");
            boolean hasItem = inventory.containsAtLeast(item, item.getAmount());
            ctx.setOutput(node, "has", hasItem);
        });

        operations.put("inventory_count_item", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            String materialName = ctx.getInputValue(node, "material", String.class, "");
            int count = 0;
            Material material = requireMaterial(materialName);
            for (ItemStack item : inventory.getContents()) {
                if (item != null && item.getType() == material) count += item.getAmount();
            }
            ctx.setOutput(node, "count", count);
        });

        operations.put("inventory_get_slot", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            int slot = requireSlot(ctx.getInputValue(node, "slot", Integer.class, 0), inventory);
            ItemStack item = inventory.getItem(slot);
            ctx.setOutput(node, "item", item);
        });

        operations.put("inventory_set_slot", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            int slot = requireSlot(ctx.getInputValue(node, "slot", Integer.class, 0), inventory);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            inventory.setItem(slot, item);
        });

        operations.put("inventory_clear_slot", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            int slot = requireSlot(ctx.getInputValue(node, "slot", Integer.class, 0), inventory);
            inventory.setItem(slot, null);
        });

        operations.put("inventory_move_item", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            int fromSlot = requireSlot(ctx.getInputValue(node, "from_slot", Integer.class, 0), inventory);
            int toSlot = requireSlot(ctx.getInputValue(node, "to_slot", Integer.class, 1), inventory);
            if (fromSlot == toSlot) throw new IllegalArgumentException("Inventory move slots must be different");
            ItemStack item = inventory.getItem(fromSlot);
            if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Source inventory slot is empty");
            if (inventory.getItem(toSlot) != null && !inventory.getItem(toSlot).getType().isAir()) throw new IllegalArgumentException("Destination inventory slot is occupied");
            inventory.setItem(toSlot, item);
            inventory.setItem(fromSlot, null);
        });

        operations.put("inventory_swap_items", (ctx, node) -> {
            Inventory inventory = requireOpenInventory(ctx, node);
            int slot1 = requireSlot(ctx.getInputValue(node, "slot1", Integer.class, 0), inventory);
            int slot2 = requireSlot(ctx.getInputValue(node, "slot2", Integer.class, 1), inventory);
            if (slot1 == slot2) throw new IllegalArgumentException("Inventory swap slots must be different");
            ItemStack item1 = inventory.getItem(slot1);
            ItemStack item2 = inventory.getItem(slot2);
            inventory.setItem(slot1, item2);
            inventory.setItem(slot2, item1);
        });

        operations.put("inventory_clear", (ctx, node) -> {
            requireOpenInventory(ctx, node).clear();
        });

        operations.put("inventory_update", (ctx, node) -> {
            requirePlayer(ctx, node).updateInventory();
        });

        operations.put("inventory_has_space", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            ItemStack item = requireItem(ctx, node, "item");
            boolean hasSpace = hasSpaceFor(inventory, item);
            ctx.setOutput(node, "has_space", hasSpace);
        });

        operations.put("inventory_count_material", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            Material material = requireMaterial(ctx.getInputValue(node, "material", String.class, ""));
            int count = 0;
            for (ItemStack item : inventory.getContents()) {
                if (item != null && item.getType() == material) {
                    count += item.getAmount();
                }
            }
            ctx.setOutput(node, "count", count);
        });

        operations.put("inventory_get_first_empty", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            int slot = inventory.firstEmpty();
            ctx.setOutput(node, "slot_index", slot);
        });

        operations.put("inventory_sort", (ctx, node) -> {
            sortInventory(requireInventory(ctx, node, "inventory"));
        });

        operations.put("inventory_get_all", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            List<ItemStack> items = Arrays.stream(inventory.getContents()).filter(item -> item != null && item.getType() != Material.AIR).toList();
            ctx.setOutput(node, "items_list", new ArrayList<>(items));
        });

        operations.put("inventory_clear_all", (ctx, node) -> {
            requireInventory(ctx, node, "inventory").clear();
        });

        operations.put("inventory_size", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            int size = inventory.getSize();
            ctx.setOutput(node, "size", size);
        });

        operations.put("inventory_get_storage_contents", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            ItemStack[] storageContents = inventory.getStorageContents();
            ctx.setOutput(node, "items_list", storageContents);
        });

        operations.put("inventory_get_max_stack_size", (ctx, node) -> {
            Material material = requireMaterial(ctx.getInputValue(node, "material", String.class, ""));
            int maxStackSize = material.getMaxStackSize();
            ctx.setOutput(node, "max_size", maxStackSize);
        });

        operations.put("inventory_contains_at_least", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            Material material = requireMaterial(ctx.getInputValue(node, "material", String.class, ""));
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (amount < 1) throw new IllegalArgumentException("Item amount must be positive");
            boolean contains = false;
            int total = 0;
            for (ItemStack item : inventory.getContents()) {
                if (item != null && item.getType() == material) {
                    total += item.getAmount();
                    if (total >= amount) {
                        contains = true;
                        break;
                    }
                }
            }
            ctx.setOutput(node, "contains", contains);
        });

        operations.put("inventory_remove_any", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            Material material = requireMaterial(ctx.getInputValue(node, "material", String.class, ""));
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (amount < 1) throw new IllegalArgumentException("Item amount must be positive");
            if (countMaterial(inventory, material) < amount) throw new IllegalArgumentException("Inventory does not contain the requested material amount");
            removeAny(inventory, material, amount);
        });

        operations.put("inventory_set_all_contents", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            ItemStack[] contents = ctx.getInputValue(node, "items_list", ItemStack[].class, new ItemStack[0]);
            if (contents.length > inventory.getSize()) throw new IllegalArgumentException("Inventory contents exceed the inventory size");
            inventory.setContents(contents);
        });

        operations.put("inventory_add_to_slot", (ctx, node) -> {
            Inventory inventory = requireInventory(ctx, node, "inventory");
            int slot = requireSlot(ctx.getInputValue(node, "slot", Integer.class, 0), inventory);
            ItemStack item = requireItem(ctx, node, "item");
            inventory.setItem(slot, item.clone());
        });

        operations.put("item_create", (ctx, node) -> {
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            Material material = requireMaterial(materialName);
            if (amount < 1 || amount > material.getMaxStackSize()) throw new IllegalArgumentException("Item amount must be between 1 and " + material.getMaxStackSize());
            ItemStack item = new ItemStack(material, amount);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_material", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Material material = requireMaterial(materialName);
            item.setType(material);
            if (item.getAmount() > material.getMaxStackSize()) item.setAmount(material.getMaxStackSize());
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_amount", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (amount < 1 || amount > item.getMaxStackSize()) throw new IllegalArgumentException("Item amount must be between 1 and " + item.getMaxStackSize());
            item.setAmount(amount);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_damage", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            Integer damage = ctx.getInputValue(node, "damage", Integer.class, 0);
            ItemMeta meta = requireItemMeta(item);
            if (!(meta instanceof Damageable damageable)) throw new IllegalArgumentException("Item does not support damage");
            int maxDamage = damageable.hasMaxDamage() ? damageable.getMaxDamage() : item.getType().getMaxDurability();
            if (damage < 0 || maxDamage > 0 && damage > maxDamage) throw new IllegalArgumentException("Item damage must be between 0 and " + maxDamage);
            damageable.setDamage(damage);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_max_damage", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            Integer maxDamage = ctx.getInputValue(node, "max_damage", Integer.class, (int) item.getType().getMaxDurability());
            if (maxDamage < 1) throw new IllegalArgumentException("Maximum item damage must be positive");
            ItemMeta meta = requireItemMeta(item);
            if (!(meta instanceof Damageable damageable)) throw new IllegalArgumentException("Item does not support damage");
            if (damageable.getDamage() > maxDamage) damageable.setDamage(maxDamage);
            damageable.setMaxDamage(maxDamage);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_unbreakable", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            Boolean unbreakable = ctx.getInputValue(node, "unbreakable", Boolean.class, true);
            ItemMeta meta = requireItemMeta(item);
            meta.setUnbreakable(unbreakable);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_custom_name", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String name = ctx.getInputValue(node, "name", String.class, "");
            ItemMeta meta = requireItemMeta(item);
            meta.displayName(TextFormatter.parseItemName(name));
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_lore", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String lore = ctx.getInputValue(node, "lore", String.class, "");
            ItemMeta meta = requireItemMeta(item);
            meta.lore(TextFormatter.parseItemLoreLines(lore));
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_add_lore", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String loreLine = ctx.getInputValue(node, "lore", String.class, "");
            ItemMeta meta = requireItemMeta(item);
            List<Component> loreList = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            loreList.add(TextFormatter.parseItemLore(loreLine));
            meta.lore(loreList);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_clear_lore", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            ItemMeta meta = requireItemMeta(item);
            meta.lore(null);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_flags", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            ItemMeta meta = requireItemMeta(item);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_add_flag", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String flagName = ctx.getInputValue(node, "flag", String.class, "");
            ItemFlag flag = itemFlag(flagName);
            ItemMeta meta = requireItemMeta(item);
            meta.addItemFlags(flag);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_remove_flag", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String flagName = ctx.getInputValue(node, "flag", String.class, "");
            ItemFlag flag = itemFlag(flagName);
            ItemMeta meta = requireItemMeta(item);
            if (!meta.getItemFlags().contains(flag)) throw new IllegalArgumentException("Item does not contain flag: " + flagName);
            meta.removeItemFlags(flag);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_add_enchant", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            Integer level = ctx.getInputValue(node, "level", Integer.class, 1);
            Enchantment enchant = enchantment(enchantName);
            if (level < 1 || level > 255) throw new IllegalArgumentException("Enchantment level must be between 1 and 255");
            ItemMeta meta = requireItemMeta(item);
            meta.addEnchant(enchant, level, true);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_remove_enchant", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            Enchantment enchant = enchantment(enchantName);
            ItemMeta meta = requireItemMeta(item);
            if (!meta.hasEnchant(enchant)) throw new IllegalArgumentException("Item does not contain enchantment: " + enchantName);
            meta.removeEnchant(enchant);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_clear_enchants", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            ItemMeta meta = requireItemMeta(item);
            meta.getEnchants().keySet().forEach(meta::removeEnchant);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_custom_model", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            Integer modelData = ctx.getInputValue(node, "model_data", Integer.class, 0);
            if (modelData < 0) throw new IllegalArgumentException("Custom model data cannot be negative");
            ItemMeta meta = requireItemMeta(item);
            meta.setCustomModelData(modelData);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_color", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            Integer red = ctx.getInputValue(node, "red", Integer.class, 255);
            Integer green = ctx.getInputValue(node, "green", Integer.class, 255);
            Integer blue = ctx.getInputValue(node, "blue", Integer.class, 255);
            if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255) throw new IllegalArgumentException("RGB values must be between 0 and 255");
            if (!(requireItemMeta(item) instanceof LeatherArmorMeta meta)) throw new IllegalArgumentException("Item is not leather armor");
            meta.setColor(Color.fromRGB(red, green, blue));
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_skull_owner", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String owner = ctx.getInputValue(node, "owner", String.class, "");
            if (owner.isBlank()) throw new IllegalArgumentException("Skull owner is required");
            if (!(requireItemMeta(item) instanceof SkullMeta meta)) throw new IllegalArgumentException("Item is not a player head");
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_book_pages", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String title = ctx.getInputValue(node, "title", String.class, "");
            String author = ctx.getInputValue(node, "author", String.class, "");
            String pages = ctx.getInputValue(node, "pages", String.class, "");
            if (!(requireItemMeta(item) instanceof BookMeta meta)) throw new IllegalArgumentException("Item is not a book");
            if (!title.isEmpty()) meta.setTitle(TextFormatter.formatLegacy(title));
            if (!author.isEmpty()) meta.setAuthor(TextFormatter.formatLegacy(author));
            List<String> pageList = Arrays.stream(pages.split("\n---\n", -1)).map(TextFormatter::formatLegacy).toList();
            if (pageList.size() > 100) throw new IllegalArgumentException("Book cannot contain more than 100 pages");
            meta.setPages(pageList);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_potion_effect", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String effectName = ctx.getInputValue(node, "effect", String.class, "");
            Integer duration = ctx.getInputValue(node, "duration", Integer.class, 200);
            Integer amplifier = ctx.getInputValue(node, "amplifier", Integer.class, 0);
            if (!(requireItemMeta(item) instanceof PotionMeta meta)) throw new IllegalArgumentException("Item is not a potion");
            if (effectName.isBlank()) throw new IllegalArgumentException("Potion effect is required");
            PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase(Locale.ROOT));
            if (type == null) throw new IllegalArgumentException("Unknown potion effect: " + effectName);
            if (duration < 1 || duration > 72_000) throw new IllegalArgumentException("Potion duration must be between 1 and 72000 ticks");
            if (amplifier < 0 || amplifier > 255) throw new IllegalArgumentException("Potion amplifier must be between 0 and 255");
            meta.addCustomEffect(new PotionEffect(type, duration, amplifier), true);
            item.setItemMeta(meta);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_get_nbt", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            ctx.setOutput(node, "nbt", Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        });

        operations.put("item_set_nbt", (ctx, node) -> {
            requireItem(ctx, node, "item");
            String nbt = ctx.getInputValue(node, "nbt", String.class, "");
            if (nbt.isBlank()) throw new IllegalArgumentException("Serialized item data is required");
            ItemStack decoded;
            try {
                decoded = ItemStack.deserializeBytes(Base64.getDecoder().decode(nbt));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Serialized item data is invalid", exception);
            }
            ctx.setOutput(node, "item", decoded);
        });

        operations.put("item_get_components", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            ctx.setOutput(node, "components", itemComponents.componentsFromStack(item));
        });

        operations.put("item_get_component", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String component = componentId(ctx.getInputValue(node, "component", String.class, ""));
            Map<String, Object> components = itemComponents.componentsFromStack(item);
            ctx.setOutput(node, "value", components.get(component));
            ctx.setOutput(node, "exists", components.containsKey(component));
        });

        operations.put("item_set_component", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String component = componentId(ctx.getInputValue(node, "component", String.class, ""));
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            if (value == null) throw new IllegalArgumentException("Item component value is required");
            ctx.setOutput(node, "item", itemComponents.applyComponents(item.clone(), Map.of(component, value)));
        });

        operations.put("item_get_typed_component", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String component = componentId(ctx.getInputValue(node, "component", String.class, ""));
            String valuePin = node.getHandlerConfig().getString("valuePin", "value");
            String expectedKind = node.getHandlerConfig().getString("valueKind", "object");
            Map<String, Object> components = itemComponents.componentsFromStack(item);
            boolean exists = components.containsKey(component);
            Object value = components.get(component);
            if ("presence".equals(expectedKind)) {
                ctx.setOutput(node, valuePin, exists);
            } else {
                requireComponentKind(component, value, expectedKind);
                ctx.setOutput(node, valuePin, value);
            }
            ctx.setOutput(node, "exists", exists);
        });

        operations.put("item_set_typed_component", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String component = componentId(ctx.getInputValue(node, "component", String.class, ""));
            String valuePin = node.getHandlerConfig().getString("valuePin", "value");
            String expectedKind = node.getHandlerConfig().getString("valueKind", "object");
            Object value = ctx.getInputValue(node, valuePin, Object.class, null);
            if ("presence".equals(expectedKind)) {
                Map<String, Object> change = Boolean.TRUE.equals(value) ? Map.of(component, Map.of()) : Collections.singletonMap(component, null);
                ctx.setOutput(node, "item", itemComponents.applyComponents(item.clone(), change));
                return;
            }
            if (value == null) throw new IllegalArgumentException("Item component value is required");
            requireComponentKind(component, value, expectedKind);
            ctx.setOutput(node, "item", itemComponents.applyComponents(item.clone(), Map.of(component, value)));
        });

        operations.put("item_typed_component", (ctx, node) -> {
            String action = ctx.getInputValue(node, "action", String.class, "get");
            if ("get".equalsIgnoreCase(action)) {
                operations.get("item_get_typed_component").accept(ctx, node);
                ctx.setOutput(node, "item", requireItem(ctx, node, "item"));
            } else if ("set".equalsIgnoreCase(action)) {
                operations.get("item_set_typed_component").accept(ctx, node);
                ctx.setOutput(node, "exists", true);
            } else {
                throw new IllegalArgumentException("Unknown item component action: " + action);
            }
        });

        operations.put("item_attribute_modifier", (ctx, node) -> {
            String attribute = ctx.getInputValue(node, "attribute", String.class, "minecraft:attack_damage");
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            String operation = ctx.getInputValue(node, "operation", String.class, "add_value");
            String slot = ctx.getInputValue(node, "slot", String.class, "any");
            String id = ctx.getInputValue(node, "id", String.class, "");
            if (attribute == null || attribute.isBlank()) throw new IllegalArgumentException("Item attribute is required");
            if (amount == null || !Double.isFinite(amount)) throw new IllegalArgumentException("Item attribute amount must be finite");
            if (!List.of("add_value", "add_multiplied_base", "add_multiplied_total").contains(operation)) throw new IllegalArgumentException("Unknown item attribute operation: " + operation);
            if (id == null || id.isBlank()) id = "resync:" + attribute.substring(attribute.indexOf(':') + 1).replace('.', '_') + "_modifier";
            Map<String, Object> modifier = new LinkedHashMap<>();
            modifier.put("type", attribute);
            modifier.put("amount", amount);
            modifier.put("operation", operation);
            modifier.put("slot", slot);
            modifier.put("id", id);
            ctx.setOutput(node, "modifier", modifier);
        });

        operations.put("item_attribute_modifier_list", (ctx, node) -> {
            List<Object> modifiers = new ArrayList<>();
            Object existing = ctx.getInputValue(node, "modifiers", Object.class, null);
            if (existing != null) {
                if (!(existing instanceof List<?> values)) throw new IllegalArgumentException("Attribute modifiers input must be an Item Component List");
                modifiers.addAll(values);
            }
            Object modifier = ctx.getInputValue(node, "modifier", Object.class, null);
            if (!(modifier instanceof Map<?, ?>)) throw new IllegalArgumentException("Item attribute modifier is required");
            modifiers.add(modifier);
            ctx.setOutput(node, "modifiers", modifiers);
        });

        operations.put("item_component_object_field", (ctx, node) -> {
            Map<String, Object> component = new LinkedHashMap<>();
            Object existing = ctx.getInputValue(node, "component_value", Object.class, null);
            if (existing != null) {
                if (!(existing instanceof Map<?, ?> values)) throw new IllegalArgumentException("Component value input must be an Item Component");
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (entry.getKey() != null) component.put(entry.getKey().toString(), entry.getValue());
                }
            }
            String field = ctx.getInputValue(node, "field", String.class, "");
            if (field == null || field.isBlank()) throw new IllegalArgumentException("Item component field is required");
            String valuePin = node.getHandlerConfig().getString("valuePin", "value");
            component.put(field, ctx.getInputValue(node, valuePin, Object.class, null));
            ctx.setOutput(node, "component_value", component);
        });

        operations.put("item_component_list_entry", (ctx, node) -> {
            List<Object> items = new ArrayList<>();
            Object existing = ctx.getInputValue(node, "items", Object.class, null);
            if (existing != null) {
                if (!(existing instanceof List<?> values)) throw new IllegalArgumentException("Component items input must be an Item Component List");
                items.addAll(values);
            }
            String valuePin = node.getHandlerConfig().getString("valuePin", "value");
            items.add(ctx.getInputValue(node, valuePin, Object.class, null));
            ctx.setOutput(node, "items", items);
        });

        operations.put("item_remove_component", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String component = componentId(ctx.getInputValue(node, "component", String.class, ""));
            ctx.setOutput(node, "item", itemComponents.applyComponents(item.clone(), Collections.singletonMap(component, null)));
        });

        operations.put("item_apply_components", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            Object value = ctx.getInputValue(node, "components", Object.class, null);
            if (!(value instanceof Map<?, ?> rawComponents)) throw new IllegalArgumentException("Item components must be a map");
            Map<String, Object> components = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawComponents.entrySet()) {
                if (entry.getKey() != null) components.put(componentId(entry.getKey().toString()), entry.getValue());
            }
            ctx.setOutput(node, "item", itemComponents.applyComponents(item.clone(), components));
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("InventoryActionHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown inventory action operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private static Player requirePlayer(FlowContext context, FlowNode node) {
        Player player = context.getInputValue(node, "player", Player.class, context.getPlayer());
        if (player == null) throw new IllegalArgumentException("Player is required");
        return player;
    }

    private static Inventory requireOpenInventory(FlowContext context, FlowNode node) {
        return requirePlayer(context, node).getOpenInventory().getTopInventory();
    }

    private Inventory requireInventory(FlowContext context, FlowNode node, String inputName) {
        Object input = context.getInputValue(node, inputName, Object.class, null);
        if (input instanceof Inventory inventory) return inventory;
        if (input instanceof Player player) return player.getInventory();
        if (input == null && context.getPlayer() != null) return context.getPlayer().getInventory();
        if (input == null) throw new IllegalArgumentException("Inventory input is required: " + inputName);
        throw new IllegalArgumentException("Inventory input must be an inventory or player: " + inputName);
    }

    private static ItemStack requireItem(FlowContext context, FlowNode node, String inputName) {
        ItemStack item = context.getInputValue(node, inputName, ItemStack.class, null);
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Item input is required: " + inputName);
        return item;
    }

    private static ItemMeta requireItemMeta(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Item does not support metadata");
        return meta;
    }

    private static Material requireMaterial(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Material is required");
        Material material = Material.matchMaterial(value);
        if (material == null || material.isAir()) throw new IllegalArgumentException("Unknown material: " + value);
        return material;
    }

    private static String componentId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Item component is required");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private static void requireComponentKind(String component, Object value, String expectedKind) {
        if (value == null) return;
        String actualKind = switch (value) {
            case Number ignored -> "number";
            case Boolean ignored -> "boolean";
            case String ignored -> "string";
            case List<?> ignored -> "array";
            case Map<?, ?> ignored -> "object";
            default -> "raw";
        };
        if (!expectedKind.equals(actualKind)) {
            throw new IllegalArgumentException("Item component " + component + " requires " + expectedKind + " data, received " + actualKind);
        }
    }

    private static int requireSlot(int slot, Inventory inventory) {
        if (slot < 0 || slot >= inventory.getSize()) throw new IllegalArgumentException("Inventory slot must be between 0 and " + (inventory.getSize() - 1));
        return slot;
    }

    private static void resizeOpenInventory(Player player, int rows) {
        Inventory current = player.getOpenInventory().getTopInventory();
        int size = rows * 9;
        if (current.getSize() == size) return;
        ItemStack[] contents = current.getContents();
        for (int slot = size; slot < contents.length; slot++) {
            ItemStack overflow = contents[slot];
            if (overflow == null || overflow.getType().isAir()) continue;
            Map<Integer, ItemStack> remaining = player.getInventory().addItem(overflow.clone());
            remaining.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
        Inventory resized = Bukkit.createInventory(null, size, player.getOpenInventory().title());
        resized.setContents(Arrays.copyOf(contents, Math.min(contents.length, size)));
        player.openInventory(resized);
    }

    private static void addItemFully(Inventory inventory, ItemStack item) {
        if (!hasSpaceFor(inventory, item)) throw new IllegalArgumentException("Inventory does not have enough space for the item");
        Map<Integer, ItemStack> remaining = inventory.addItem(item);
        if (!remaining.isEmpty()) throw new IllegalStateException("Inventory capacity changed while adding the item");
    }

    private static boolean hasSpaceFor(Inventory inventory, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack existing : inventory.getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= item.getMaxStackSize();
            } else if (existing.isSimilar(item)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static int countMaterial(Inventory inventory, Material material) {
        int count = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() == material) count += item.getAmount();
        }
        return count;
    }

    private static ItemFlag itemFlag(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Item flag is required");
        }
        try {
            return ItemFlag.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown item flag: " + value, exception);
        }
    }

    private static Enchantment enchantment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Enchantment is required");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        Enchantment enchantment = key != null ? Enchantment.getByKey(key) : null;
        if (enchantment == null) {
            throw new IllegalArgumentException("Unknown enchantment: " + value);
        }
        return enchantment;
    }

    private void sortInventory(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }
        items.sort(Comparator.comparing((ItemStack i) -> i.getType().name()).thenComparing(i -> i.getAmount(), Comparator.reverseOrder()));
        inventory.clear();
        for (ItemStack item : items) {
            inventory.addItem(item);
        }
    }

    private void removeAny(Inventory inventory, Material material, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.getSize() && remaining > 0; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == material) {
                int stackAmount = item.getAmount();
                if (stackAmount <= remaining) {
                    inventory.setItem(i, null);
                    remaining -= stackAmount;
                } else {
                    item.setAmount(stackAmount - remaining);
                    remaining = 0;
                }
            }
        }
    }
}
