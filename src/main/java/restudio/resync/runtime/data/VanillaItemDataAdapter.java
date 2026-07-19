package restudio.resync.runtime.data;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.RuntimeDataAdapter;
import restudio.resync.api.RuntimeDataCapability;
import restudio.resync.api.RuntimeDataQuery;
import restudio.resync.api.RuntimeDataRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class VanillaItemDataAdapter implements RuntimeDataAdapter<ItemStack> {
    public static final String ID = "minecraft:items";
    private final Map<Material, Set<String>> materialTags = discoverMaterialTags();
    private final List<RuntimeDataRecord> records = buildRecords();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String domain() {
        return "item";
    }

    @Override
    public FlowTypeRef valueType() {
        return FlowTypeRef.simple("itemstack");
    }

    @Override
    public Class<ItemStack> valueClass() {
        return ItemStack.class;
    }

    @Override
    public Set<RuntimeDataCapability> capabilities() {
        return Set.of(RuntimeDataCapability.ENUMERATE, RuntimeDataCapability.RESOLVE, RuntimeDataCapability.DESCRIBE);
    }

    @Override
    public String revision() {
        return ID + ":" + records.size() + ":" + records.hashCode();
    }

    @Override
    public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
        return records;
    }

    @Override
    public ItemStack resolve(RuntimeDataRecord record, int amount) {
        Material material = record != null ? Material.matchMaterial(record.id()) : null;
        return material != null && material.isItem() && !material.isAir() ? new ItemStack(material, Math.max(1, amount)) : null;
    }

    @Override
    public RuntimeDataRecord describe(ItemStack value) {
        if (value == null || value.getType().isAir()) {
            return null;
        }
        String id = value.getType().name().toLowerCase(Locale.ROOT);
        return records.stream().filter(record -> record.id().equals(id)).findFirst().orElse(null);
    }

    private List<RuntimeDataRecord> buildRecords() {
        List<RuntimeDataRecord> values = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isAir()) {
                continue;
            }
            Set<String> categories = categories(material);
            Set<String> tags = new LinkedHashSet<>(materialTags.getOrDefault(material, Set.of()));
            tags.add(material.getKey().getNamespace());
            tags.add(material.name().toLowerCase(Locale.ROOT));
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("material", material.name());
            attributes.put("namespace", material.getKey().getNamespace());
            attributes.put("block", material.isBlock());
            attributes.put("edible", material.isEdible());
            attributes.put("fuel", material.isFuel());
            attributes.put("maxStackSize", material.getMaxStackSize());
            attributes.put("maxDurability", (int) material.getMaxDurability());
            values.add(new RuntimeDataRecord(domain(), id(), material.name().toLowerCase(Locale.ROOT), RuntimeDataLabels.label(material.name()),
                "Vanilla item", categories, tags, attributes));
        }
        return List.copyOf(values);
    }

    private Set<String> categories(Material material) {
        Set<String> values = new LinkedHashSet<>();
        values.add("vanilla");
        values.add(material.isBlock() ? "blocks" : "items");
        if (material.isEdible()) {
            values.add("food");
        }
        if (material.isFuel()) {
            values.add("fuel");
        }
        if (material.getMaxDurability() > 0) {
            values.add("durable");
        }
        if (material.getMaxStackSize() == 1) {
            values.add("unstackable");
        }
        String name = material.name();
        classify(values, name, "swords", "_SWORD");
        classify(values, name, "axes", "_AXE");
        classify(values, name, "pickaxes", "_PICKAXE");
        classify(values, name, "shovels", "_SHOVEL");
        classify(values, name, "hoes", "_HOE");
        classify(values, name, "helmets", "_HELMET");
        classify(values, name, "chestplates", "_CHESTPLATE");
        classify(values, name, "leggings", "_LEGGINGS");
        classify(values, name, "boots", "_BOOTS");
        classify(values, name, "spawn_eggs", "_SPAWN_EGG");
        classify(values, name, "music_discs", "MUSIC_DISC_");
        classify(values, name, "potions", "POTION");
        classify(values, name, "books", "BOOK");
        if (values.stream().anyMatch(Set.of("swords", "axes")::contains)) {
            values.add("weapons");
        }
        if (values.stream().anyMatch(Set.of("axes", "pickaxes", "shovels", "hoes")::contains)) {
            values.add("tools");
        }
        if (values.stream().anyMatch(Set.of("helmets", "chestplates", "leggings", "boots")::contains)) {
            values.add("armor");
        }
        values.addAll(materialTags.getOrDefault(material, Set.of()));
        return values;
    }

    private static void classify(Set<String> categories, String name, String category, String token) {
        if (token.startsWith("_") ? name.endsWith(token) : name.contains(token)) {
            categories.add(category);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Material, Set<String>> discoverMaterialTags() {
        Map<Material, Set<String>> values = new LinkedHashMap<>();
        for (Field field : Tag.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Tag.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Tag<Material> tag = (Tag<Material>) field.get(null);
                String id = field.getName().toLowerCase(Locale.ROOT);
                for (Material material : Material.values()) {
                    if (material.isItem() && !material.isAir() && tag.isTagged(material)) {
                        values.computeIfAbsent(material, ignored -> new LinkedHashSet<>()).add(id);
                    }
                }
            } catch (IllegalAccessException | RuntimeException ignored) {
            }
        }
        return values;
    }
}
