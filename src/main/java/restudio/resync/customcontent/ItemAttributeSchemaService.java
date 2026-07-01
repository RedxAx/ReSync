package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.advancement.PaperUnsafe;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ItemAttributeSchemaService {
    public static final String SOURCE = "server:minecraft:item_attribute_schema";

    private static final Pattern MINECRAFT_KEY = Pattern.compile("minecraft:[a-z0-9_./-]+");
    private static final Set<String> SPECIALIZED_ITEM_COMPONENTS = Set.of(
        "minecraft:axolotl/variant",
        "minecraft:cat/variant",
        "minecraft:cat/collar",
        "minecraft:chicken/variant",
        "minecraft:cow/variant",
        "minecraft:fox/variant",
        "minecraft:frog/variant",
        "minecraft:horse/variant",
        "minecraft:llama/variant",
        "minecraft:map_color",
        "minecraft:map_decorations",
        "minecraft:map_id",
        "minecraft:map_post_processing",
        "minecraft:mooshroom/variant",
        "minecraft:painting/variant",
        "minecraft:parrot/variant",
        "minecraft:pig/variant",
        "minecraft:rabbit/variant",
        "minecraft:salmon/size",
        "minecraft:sheep/color",
        "minecraft:shulker/color",
        "minecraft:tropical_fish/base_color",
        "minecraft:tropical_fish/pattern",
        "minecraft:tropical_fish/pattern_color",
        "minecraft:villager/variant",
        "minecraft:wolf/variant",
        "minecraft:wolf/sound_variant",
        "minecraft:wolf/collar"
    );
    private static final AttributeUiProfile FALLBACK_UI_PROFILE = new AttributeUiProfile("Other", 900, List.of("any"), "schema", List.of());
    private static final Map<String, AttributeUiProfile> UI_PROFILES = loadUiProfiles();
    private final Gson gson = new Gson();
    private final List<String> injectedComponentIds;
    private final Map<String, Object> injectedExamples;
    private final Boolean injectedRoundTripSupport;
    private final Map<String, Boolean> injectedApplicability;
    private volatile List<String> componentIds;
    private volatile Set<String> nonValuedComponentIds;
    private volatile Map<String, Object> examples;
    private volatile Map<String, Set<String>> exampleMaterials;

    public ItemAttributeSchemaService() {
        this(null, null, null, null);
    }

    ItemAttributeSchemaService(List<String> componentIds, Map<String, Object> examples, Boolean roundTripSupport) {
        this(componentIds, examples, roundTripSupport, null);
    }

    ItemAttributeSchemaService(List<String> componentIds, Map<String, Object> examples, Boolean roundTripSupport, Map<String, Boolean> applicability) {
        this.injectedComponentIds = componentIds != null ? List.copyOf(componentIds) : null;
        this.injectedExamples = examples != null ? new LinkedHashMap<>(examples) : null;
        this.injectedRoundTripSupport = roundTripSupport;
        this.injectedApplicability = applicability != null ? new LinkedHashMap<>(applicability) : null;
    }

    public List<OptionCatalogItem> catalog(String materialName) {
        Material material = injectedComponentIds != null ? null : material(materialName);
        Map<String, Object> defaults = material != null ? componentsFromStack(new ItemStack(material)) : Map.of();
        Map<String, Object> examplesByComponent = examples();
        Map<String, List<Object>> candidateExamples = injectedComponentIds == null ? candidateExamples(material) : Map.of();
        List<String> runtimeIds = componentIds();
        Set<String> runtimeIdSet = new LinkedHashSet<>(runtimeIds);
        Set<String> ids = new LinkedHashSet<>(runtimeIds);
        if (runtimeIds.isEmpty()) {
            ids.addAll(UI_PROFILES.keySet());
        }
        ids.addAll(defaults.keySet());
        ids.addAll(examplesByComponent.keySet());
        ids.addAll(candidateExamples.keySet());
        List<OptionCatalogItem> items = new ArrayList<>();
        for (String id : ids) {
            boolean runtimeKnown = runtimeIdSet.contains(id);
            Object defaultValue = defaults.get(id);
            Object exampleValue = examplesByComponent.containsKey(id) ? examplesByComponent.get(id) : candidateExampleValue(material, id, candidateExamples.get(id));
            boolean generatedExample = !examplesByComponent.containsKey(id) && exampleValue != null;
            AttributeUiProfile profile = uiProfile(id);
            if (!componentIsSupported(runtimeKnown, defaultValue, exampleValue)) {
                continue;
            }
            boolean applicable = componentAppliesToMaterial(material, id, defaultValue, exampleValue, generatedExample, profile);
            boolean advanced = defaultValue == null && exampleValue == null;
            Object value = defaultValue != null ? defaultValue : exampleValue != null ? exampleValue : advancedDefaultValue(id);
            String group = profile.category();
            int priority = componentPriority(profile, defaultValue != null, applicable, advanced);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("id", id);
            metadata.put("material", material != null ? material.name() : "");
            metadata.put("schemaVersion", 1);
            metadata.put("source", "paper_datacomponent_runtime");
            metadata.put("runtime", runtimeKnown);
            metadata.put("default", defaultValue != null);
            metadata.put("advanced", advanced);
            metadata.put("applicable", applicable);
            metadata.put("recommended", defaultValue != null || applicable && priority < 500);
            metadata.put("category", group);
            metadata.put("priority", priority);
            metadata.put("editor", profile.editor());
            metadata.put("appliesTo", profile.appliesTo());
            metadata.put("search", profile.search());
            metadata.put("editableJson", false);
            if (defaultValue != null) {
                metadata.put("defaultValue", defaultValue);
            }
            if (exampleValue != null) {
                metadata.put("exampleValue", exampleValue);
            }
            metadata.put("valueKind", valueKind(value));
            metadata.put("schema", schema(value));
            metadata.put("ui", uiMetadata(profile, priority, applicable, defaultValue != null, advanced));
            items.add(new OptionCatalogItem(id, label(id), description(id), "", group, metadata));
        }
        items.sort(Comparator.comparingInt((OptionCatalogItem item) -> groupRank(item.group()))
            .thenComparingInt(item -> booleanMetadata(item.metadata(), "applicable", false) ? 0 : 1)
            .thenComparingInt(item -> booleanMetadata(item.metadata(), "recommended", false) ? 0 : 1)
            .thenComparingInt(item -> numberMetadata(item.metadata(), "priority", 900))
            .thenComparing(OptionCatalogItem::label, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    private boolean componentIsSupported(boolean runtimeKnown, Object defaultValue, Object exampleValue) {
        return runtimeKnown || defaultValue != null || exampleValue != null;
    }

    public List<String> values(String materialName) {
        return catalog(materialName).stream().map(OptionCatalogItem::value).toList();
    }

    public String revision(String materialName) {
        return SOURCE + ":" + Bukkit.getVersion() + ":" + (materialName != null ? materialName.toLowerCase(Locale.ROOT) : "all");
    }

    public List<Map<String, Object>> validate(String materialName, Map<String, Object> components) {
        if (components == null || components.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String key : components.keySet()) {
            if (!isNamespacedComponentId(key)) {
                errors.add(error(key, "Component id must be namespaced"));
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        if (!itemJsonRoundTripSupported()) {
            return List.of(error("", "Item component JSON is not available on this server"));
        }
        Material material = material(materialName);
        if (material == null) {
            return List.of(error("", "Material does not exist: " + materialName));
        }
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            try {
                applyComponents(new ItemStack(material), Map.of(entry.getKey(), entry.getValue()));
            } catch (RuntimeException failure) {
                errors.add(error(entry.getKey(), failure.getMessage()));
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        try {
            applyComponents(new ItemStack(material), components);
            return List.of();
        } catch (RuntimeException failure) {
            return List.of(error("", failure.getMessage()));
        }
    }

    public ItemStack applyComponents(ItemStack base, Map<String, Object> components) {
        if (base == null || components == null || components.isEmpty()) {
            return base;
        }
        if (!itemJsonRoundTripSupported()) {
            throw new IllegalStateException("Item component JSON is not available on this server");
        }
        JsonObject root = PaperUnsafe.serializeItemAsJson(base.clone());
        JsonObject patch = root.has("components") && root.get("components").isJsonObject() ? root.getAsJsonObject("components") : new JsonObject();
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            String id = normalizeComponentId(entry.getKey());
            if (entry.getValue() == null) {
                patch.remove(id);
            } else {
                patch.add(id, normalizeItemTextComponent(id, gson.toJsonTree(entry.getValue())));
            }
        }
        root.add("components", patch);
        return PaperUnsafe.deserializeItemFromJson(root);
    }

    private JsonElement normalizeItemTextComponent(String id, JsonElement value) {
        return switch (id) {
            case "minecraft:custom_name", "minecraft:item_name" -> normalizeTextComponent(value);
            case "minecraft:lore" -> normalizeLoreComponent(value);
            default -> value;
        };
    }

    private JsonElement normalizeLoreComponent(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return value;
        }
        JsonArray normalized = new JsonArray();
        for (JsonElement element : value.getAsJsonArray()) {
            normalized.add(normalizeTextComponent(element));
        }
        return normalized;
    }

    private JsonElement normalizeTextComponent(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return value;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (!object.has("italic")) {
                object.addProperty("italic", false);
            }
            return object;
        }
        if (value.isJsonArray()) {
            JsonArray normalized = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) {
                normalized.add(normalizeTextComponent(element));
            }
            return normalized;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            JsonObject object = new JsonObject();
            object.addProperty("text", value.getAsString());
            object.addProperty("italic", false);
            return object;
        }
        return value;
    }

    public Map<String, Object> componentsFromStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !PaperUnsafe.serializeItemAsJsonSupported()) {
            return Map.of();
        }
        try {
            JsonObject root = PaperUnsafe.serializeItemAsJson(stack);
            if (root == null || !root.has("components") || !root.get("components").isJsonObject()) {
                return Map.of();
            }
            return jsonObjectToMap(root.getAsJsonObject("components"));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private List<String> componentIds() {
        List<String> cached = componentIds;
        if (cached != null) {
            return cached;
        }
        List<String> ids = discoverComponentIds();
        componentIds = ids;
        return ids;
    }

    private List<String> discoverComponentIds() {
        if (injectedComponentIds != null) {
            return injectedComponentIds.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        Set<String> ids = new LinkedHashSet<>();
        discoverRegistryComponentKeys(ids);
        try {
            Class<?> types = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            for (Field field : types.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object component = field.get(null);
                String key = keyFromComponent(component, field.getName());
                if (key != null && !key.isBlank()) {
                    ids.add(key);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        if (ids.isEmpty()) {
            ids.addAll(examples().keySet());
        }
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void discoverRegistryComponentKeys(Set<String> ids) {
        try {
            Class<?> keys = Class.forName("io.papermc.paper.registry.keys.DataComponentTypeKeys");
            for (Field field : keys.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object component = field.get(null);
                String key = keyFromComponent(component, field.getName());
                if (key != null && !key.isBlank()) {
                    ids.add(key);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    private Set<String> nonValuedComponentIds() {
        Set<String> cached = nonValuedComponentIds;
        if (cached != null) {
            return cached;
        }
        Set<String> ids = discoverNonValuedComponentIds();
        nonValuedComponentIds = ids;
        return ids;
    }

    private Set<String> discoverNonValuedComponentIds() {
        if (injectedComponentIds != null) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        try {
            Class<?> types = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            Class<?> nonValuedType = Class.forName("io.papermc.paper.datacomponent.DataComponentType$NonValued");
            for (Field field : types.getFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !nonValuedType.isAssignableFrom(field.getType())) {
                    continue;
                }
                Object component = field.get(null);
                String key = keyFromComponent(component, field.getName());
                if (key != null && !key.isBlank()) {
                    ids.add(key);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return Set.copyOf(ids);
    }

    private boolean componentAppliesToMaterial(Material material, String id, Object defaultValue, Object exampleValue, boolean generatedExample, AttributeUiProfile profile) {
        if (defaultValue != null) {
            return true;
        }
        if (injectedApplicability != null) {
            return Boolean.TRUE.equals(injectedApplicability.get(id));
        }
        if (injectedComponentIds != null) {
            return exampleValue != null || Boolean.TRUE.equals(injectedRoundTripSupport);
        }
        if (material == null) {
            return exampleValue != null || componentIds().contains(id);
        }
        if (generatedExample) {
            return profileAppliesToMaterial(material, profile);
        }
        if (exampleValue == null) {
            return profileAppliesToMaterial(material, profile);
        }
        if (!itemJsonRoundTripSupported()) {
            return profileAppliesToMaterial(material, profile);
        }
        Set<String> origins = exampleMaterials().getOrDefault(id, Set.of());
        if (origins.contains(material.name())) {
            return true;
        }
        if (origins.size() < 2) {
            return false;
        }
        try {
            applyComponents(new ItemStack(material), Map.of(id, exampleValue));
            return true;
        } catch (RuntimeException ignored) {
            return profileAppliesToMaterial(material, profile);
        }
    }

    private boolean profileAppliesToMaterial(Material material, AttributeUiProfile profile) {
        if (profile == null || profile.appliesTo().isEmpty() || profile.appliesTo().contains("any")) {
            return true;
        }
        if (material == null) {
            return false;
        }
        String name = material.name();
        for (String target : profile.appliesTo()) {
            if (materialMatchesTarget(material, name, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean materialMatchesTarget(Material material, String name, String target) {
        return switch (target != null ? target : "") {
            case "any", "adventure", "creative" -> true;
            case "weapon" -> materialContains(name, "SWORD", "AXE", "TRIDENT", "MACE", "BOW", "CROSSBOW");
            case "armor" -> materialContains(name, "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "ELYTRA", "HORSE_ARMOR", "WOLF_ARMOR");
            case "tool" -> materialContains(name, "PICKAXE", "SHOVEL", "HOE", "SHEARS", "BRUSH", "FISHING_ROD", "FLINT_AND_STEEL");
            case "food" -> material.isEdible();
            case "potion" -> materialContains(name, "POTION");
            case "tipped_arrow", "projectile" -> materialContains(name, "ARROW", "SNOWBALL", "EGG", "FIREWORK_ROCKET", "FIRE_CHARGE", "WIND_CHARGE");
            case "container" -> materialContains(name, "CHEST", "BARREL", "SHULKER_BOX", "BUNDLE", "HOPPER", "DISPENSER", "DROPPER", "FURNACE", "BLAST_FURNACE", "SMOKER", "BREWING_STAND", "CHISELED_BOOKSHELF");
            case "shulker_box" -> materialContains(name, "SHULKER_BOX");
            case "bundle" -> materialContains(name, "BUNDLE");
            case "entity", "entity_item" -> materialContains(name, "SPAWN_EGG", "BUCKET", "ARMOR_STAND", "PAINTING", "ITEM_FRAME", "MINECART", "BOAT");
            case "block" -> material.isBlock();
            case "block_entity" -> material.isBlock() && materialContains(name, "CHEST", "BARREL", "SHULKER_BOX", "FURNACE", "HOPPER", "DISPENSER", "DROPPER", "BREWING_STAND", "BEEHIVE", "BEE_NEST", "LECTERN", "SIGN", "SKULL", "HEAD", "SPAWNER", "VAULT", "JUKEBOX", "CHISELED_BOOKSHELF");
            case "banner" -> materialContains(name, "BANNER");
            case "banner_pattern_provider" -> materialContains(name, "BANNER_PATTERN");
            case "bee_container" -> materialContains(name, "BEEHIVE", "BEE_NEST");
            case "book" -> materialContains(name, "BOOK");
            case "bucket" -> materialContains(name, "BUCKET");
            case "compass" -> materialContains(name, "COMPASS");
            case "crossbow" -> materialContains(name, "CROSSBOW");
            case "debug_stick" -> materialContains(name, "DEBUG_STICK");
            case "decorated_pot" -> materialContains(name, "DECORATED_POT");
            case "dye" -> materialContains(name, "DYE");
            case "dyeable" -> materialContains(name, "LEATHER", "WOLF_ARMOR");
            case "elytra" -> materialContains(name, "ELYTRA");
            case "equipment" -> materialMatchesTarget(material, name, "armor") || materialMatchesTarget(material, name, "weapon") || materialContains(name, "SHIELD", "CARVED_PUMPKIN", "SKULL", "HEAD");
            case "firework_rocket" -> materialContains(name, "FIREWORK_ROCKET");
            case "firework_star" -> materialContains(name, "FIREWORK_STAR");
            case "goat_horn" -> materialContains(name, "GOAT_HORN");
            case "head" -> materialContains(name, "HEAD", "SKULL");
            case "knowledge_book" -> materialContains(name, "KNOWLEDGE_BOOK");
            case "leather_armor" -> materialContains(name, "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS");
            case "map" -> materialContains(name, "MAP");
            case "music_disc" -> materialContains(name, "MUSIC_DISC");
            case "ominous_bottle" -> materialContains(name, "OMINOUS_BOTTLE");
            case "shield" -> materialContains(name, "SHIELD");
            case "sulfur_cube" -> materialContains(name, "SULFUR_CUBE");
            case "suspicious_stew" -> materialContains(name, "SUSPICIOUS_STEW");
            case "totem" -> materialContains(name, "TOTEM");
            case "trim_material" -> materialContains(name, "INGOT", "CRYSTAL", "QUARTZ", "DIAMOND", "EMERALD", "LAPIS", "AMETHYST", "NETHERITE", "REDSTONE", "COPPER");
            case "villager_trade" -> true;
            default -> false;
        };
    }

    private boolean materialContains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private Object advancedDefaultValue(String id) {
        return nonValuedComponentIds().contains(id) ? Map.of() : new LinkedHashMap<>();
    }

    private int groupRank(String group) {
        return switch (group != null ? group : "") {
            case "Text" -> 0;
            case "Visuals" -> 1;
            case "Use" -> 2;
            case "Combat" -> 3;
            case "Durability" -> 4;
            case "Enchanting" -> 5;
            case "Inventory" -> 6;
            case "Rules" -> 7;
            case "Effects" -> 8;
            case "World" -> 9;
            case "Entity Variants" -> 10;
            case "Data" -> 11;
            default -> 12;
        };
    }

    private int componentPriority(AttributeUiProfile profile, boolean defaultValue, boolean applicable, boolean advanced) {
        int priority = profile.priority();
        if (defaultValue) {
            priority -= 200;
        } else if (!applicable) {
            priority += 250;
        }
        if (advanced) {
            priority += 100;
        }
        return Math.max(0, priority);
    }

    private int numberMetadata(Map<String, Object> metadata, String key, int fallback) {
        Object value = metadata != null ? metadata.get(key) : null;
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean booleanMetadata(Map<String, Object> metadata, String key, boolean fallback) {
        Object value = metadata != null ? metadata.get(key) : null;
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString());
        }
        return fallback;
    }

    private Map<String, Object> uiMetadata(AttributeUiProfile profile, int priority, boolean applicable, boolean defaultValue, boolean advanced) {
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("category", profile.category());
        ui.put("priority", priority);
        ui.put("applicable", applicable);
        ui.put("default", defaultValue);
        ui.put("advanced", advanced);
        ui.put("editor", profile.editor());
        ui.put("appliesTo", profile.appliesTo());
        ui.put("search", profile.search());
        return ui;
    }

    private AttributeUiProfile uiProfile(String id) {
        AttributeUiProfile exact = UI_PROFILES.get(id);
        if (exact != null) {
            return exact;
        }
        String value = id != null ? id.toLowerCase(Locale.ROOT) : "";
        if (value.contains("name") || value.contains("lore") || value.contains("tooltip")) {
            return new AttributeUiProfile("Text", 160, List.of("any"), "schema", List.of("text", "name", "tooltip"));
        }
        if (value.contains("model") || value.contains("color") || value.contains("trim") || value.contains("glint")) {
            return new AttributeUiProfile("Visuals", 260, List.of("any"), "schema", List.of("visual", "model", "color"));
        }
        if (value.contains("food") || value.contains("consume") || value.contains("use_")) {
            return new AttributeUiProfile("Use", 320, List.of("food", "tool", "any"), "schema", List.of("use", "eat", "consume"));
        }
        if (value.contains("damage") || value.contains("weapon") || value.contains("projectile") || value.contains("attribute")) {
            return new AttributeUiProfile("Combat", 360, List.of("weapon", "armor", "tool"), "schema", List.of("combat", "damage", "attack"));
        }
        if (value.contains("enchant")) {
            return new AttributeUiProfile("Enchanting", 420, List.of("any"), "schema", List.of("enchant", "magic", "level"));
        }
        if (value.contains("container") || value.contains("bundle") || value.contains("contents")) {
            return new AttributeUiProfile("Inventory", 460, List.of("container", "bundle"), "schema", List.of("inventory", "item", "slot"));
        }
        if (SPECIALIZED_ITEM_COMPONENTS.contains(value) || value.contains("variant")) {
            return new AttributeUiProfile("Entity Variants", 760, List.of("entity"), "registry", List.of("variant", "entity"));
        }
        return FALLBACK_UI_PROFILE;
    }

    private static Map<String, AttributeUiProfile> loadUiProfiles() {
        Map<String, AttributeUiProfile> profiles = new LinkedHashMap<>();
        InputStream stream = ItemAttributeSchemaService.class.getResourceAsStream("/resync/item_attribute_ui_schema.json");
        if (stream == null) {
            return Map.of();
        }
        try {
            JsonObject root = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject components = root.has("components") && root.get("components").isJsonObject() ? root.getAsJsonObject("components") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : components.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                profiles.put(entry.getKey(), new AttributeUiProfile(
                    string(value, "category", FALLBACK_UI_PROFILE.category()),
                    integer(value, "priority", FALLBACK_UI_PROFILE.priority()),
                    stringList(value.get("appliesTo"), FALLBACK_UI_PROFILE.appliesTo()),
                    string(value, "editor", FALLBACK_UI_PROFILE.editor()),
                    stringList(value.get("search"), List.of())
                ));
            }
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        return Map.copyOf(profiles);
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : fallback;
    }

    private static List<String> stringList(JsonElement element, List<String> fallback) {
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item != null && item.isJsonPrimitive()) {
                values.add(item.getAsString());
            }
        }
        return values.isEmpty() ? fallback : List.copyOf(values);
    }

    private Map<String, List<Object>> candidateExamples(Material material) {
        Map<String, List<Object>> candidates = new LinkedHashMap<>();
        String itemModel = material != null ? "minecraft:" + material.name().toLowerCase(Locale.ROOT) : "minecraft:stick";
        candidates.put("minecraft:custom_model_data", List.of(Map.of("floats", List.of(1.0))));
        candidates.put("minecraft:item_model", List.of(itemModel));
        candidates.put("minecraft:item_name", List.of(Map.of("text", "Item Name"), "Item Name"));
        candidates.put("minecraft:custom_name", List.of(Map.of("text", "Custom Name"), "Custom Name"));
        candidates.put("minecraft:lore", List.of(List.of(Map.of("text", "Lore")), List.of("Lore")));
        candidates.put("minecraft:tooltip_display", List.of(Map.of("hidden_components", List.of("minecraft:attribute_modifiers")), Map.of()));
        candidates.put("minecraft:can_break", List.of(List.of(Map.of("blocks", "minecraft:stone"))));
        candidates.put("minecraft:can_place_on", List.of(List.of(Map.of("blocks", "minecraft:stone"))));
        candidates.put("minecraft:attribute_modifiers", List.of(List.of(Map.of(
                "type", "minecraft:generic.attack_damage",
                "amount", 1.0,
                "operation", "add_value",
                "slot", "mainhand",
                "id", "remotely:attack_damage"
        ))));
        candidates.put("minecraft:trim", List.of(Map.of(
            "material", "minecraft:iron",
            "pattern", "minecraft:sentry"
        )));
        Map<String, Object> fireworkExplosion = Map.of(
            "shape", "small_ball",
            "colors", List.of(0xFF0000),
            "fade_colors", List.of(0xFFFF00),
            "has_trail", false,
            "has_twinkle", false
        );
        candidates.put("minecraft:firework_explosion", List.of(fireworkExplosion));
        candidates.put("minecraft:fireworks", List.of(Map.of(
            "flight_duration", 1,
            "explosions", List.of(fireworkExplosion)
        )));
        candidates.put("minecraft:banner_patterns", List.of(List.of(Map.of(
            "pattern", "minecraft:stripe_bottom",
            "color", "white"
        ))));
        Map<String, Object> arrowStack = Map.of("id", "minecraft:arrow", "count", 1);
        Map<String, Object> appleStack = Map.of("id", "minecraft:apple", "count", 1);
        candidates.put("minecraft:charged_projectiles", List.of(List.of(arrowStack)));
        candidates.put("minecraft:bundle_contents", List.of(List.of(appleStack)));
        candidates.put("minecraft:container", List.of(List.of(Map.of(
            "slot", 0,
            "item", Map.of("id", "minecraft:stone", "count", 1)
        ))));
        candidates.put("minecraft:enchantment_glint_override", List.of(true));
        candidates.put("minecraft:max_stack_size", List.of(64));
        candidates.put("minecraft:max_damage", List.of(100));
        candidates.put("minecraft:damage", List.of(0));
        candidates.put("minecraft:repair_cost", List.of(0));
        candidates.put("minecraft:rarity", List.of("common"));
        candidates.put("minecraft:enchantable", List.of(Map.of("value", 10)));
        candidates.put("minecraft:ominous_bottle_amplifier", List.of(1));
        candidates.put("minecraft:instrument", List.of("minecraft:ponder_goat_horn"));
        candidates.put("minecraft:jukebox_playable", List.of("minecraft:13"));
        candidates.put("minecraft:glider", List.of(Map.of()));
        candidates.put("minecraft:intangible_projectile", List.of(Map.of()));
        candidates.put("minecraft:damage_resistant", List.of(Map.of("types", "#minecraft:is_fire")));
        candidates.put("minecraft:weapon", List.of(Map.of(
            "item_damage_per_attack", 1,
            "disable_blocking_for_seconds", 0.0
        )));
        candidates.put("minecraft:equippable", List.of(equippableExample(material)));
        candidates.put("minecraft:unbreakable", List.of(Map.of(), true));
        candidates.put("minecraft:dyed_color", List.of(16777215));
        candidates.put("minecraft:enchantments", List.of(Map.of(
            "minecraft:unbreaking", 1
        )));
        candidates.put("minecraft:stored_enchantments", List.of(Map.of(
            "minecraft:unbreaking", 1
        )));
        candidates.put("minecraft:potion_contents", List.of(
            Map.of("potion", "minecraft:water"),
            Map.of("custom_color", 16777215)
        ));
        candidates.put("minecraft:food", List.of(
            Map.of("nutrition", 1, "saturation", 0.1),
            Map.of("nutrition", 1, "saturation_modifier", 0.1)
        ));
        candidates.put("minecraft:consumable", List.of(Map.of(
            "consume_seconds", 1.6,
            "animation", "eat",
            "sound", "minecraft:entity.generic.eat",
            "has_consume_particles", true
        )));
        candidates.put("minecraft:use_cooldown", List.of(Map.of(
            "seconds", 1.0,
            "cooldown_group", "minecraft:generic"
        )));
        candidates.put("minecraft:use_remainder", List.of(Map.of("id", "minecraft:bowl", "count", 1)));
        candidates.put("minecraft:tooltip_style", List.of("minecraft:default"));
        candidates.put("minecraft:writable_book_content", List.of(Map.of("pages", List.of("Page"))));
        candidates.put("minecraft:written_book_content", List.of(Map.of(
            "title", "Title",
            "author", "Author",
            "generation", 0,
            "pages", List.of(Map.of("text", "Page")),
            "resolved", false
        )));
        candidates.put("minecraft:profile", List.of(Map.of("name", "Steve")));
        candidates.put("minecraft:base_color", List.of("white"));
        candidates.put("minecraft:provides_trim_material", List.of("minecraft:iron"));
        candidates.put("minecraft:provides_banner_patterns", List.of("#minecraft:pattern_item/flower"));
        candidates.put("minecraft:pot_decorations", List.of(List.of("minecraft:brick", "minecraft:brick", "minecraft:brick", "minecraft:brick")));
        candidates.put("minecraft:map_color", List.of(16777215));
        candidates.put("minecraft:map_id", List.of(0));
        candidates.put("minecraft:map_decorations", List.of(Map.of(
            "marker", Map.of(
                "type", "minecraft:red_x",
                "x", 0.0,
                "z", 0.0,
                "rotation", 0.0
            )
        )));
        candidates.put("minecraft:map_post_processing", List.of("lock"));
        candidates.put("minecraft:tool", List.of(Map.of(
            "rules", List.of(Map.of(
                "blocks", "minecraft:stone",
                "speed", 1.0,
                "correct_for_drops", true
            )),
            "default_mining_speed", 1.0,
            "damage_per_block", 1,
            "can_destroy_blocks_in_creative", true
        )));
        candidates.put("minecraft:blocks_attacks", List.of(Map.of(
            "block_delay_seconds", 0.25,
            "disable_cooldown_scale", 1.0,
            "damage_reductions", List.of(Map.of(
                "horizontal_blocking_angle", 90.0,
                "base", 0.0,
                "factor", 1.0
            )),
            "item_damage", Map.of(
                "threshold", 1.0,
                "base", 1.0,
                "factor", 1.0
            ),
            "block_sound", "minecraft:item.shield.block"
        )));
        candidates.put("minecraft:death_protection", List.of(Map.of(
            "death_effects", List.of(Map.of(
                "type", "minecraft:play_sound",
                "sound", "minecraft:item.totem.use"
            ))
        )));
        candidates.put("minecraft:repairable", List.of(Map.of("items", "minecraft:iron_ingot")));
        candidates.put("minecraft:lock", List.of("Key"));
        candidates.put("minecraft:creative_slot_lock", List.of(Map.of()));
        candidates.put("minecraft:container_loot", List.of(Map.of(
            "loot_table", "minecraft:chests/simple_dungeon",
            "seed", 0
        )));
        candidates.put("minecraft:bees", List.of(List.of(Map.of(
            "entity_data", Map.of("id", "minecraft:bee"),
            "ticks_in_hive", 0,
            "min_ticks_in_hive", 2400
        ))));
        candidates.put("minecraft:potion_duration_scale", List.of(1.0));
        candidates.put("minecraft:suspicious_stew_effects", List.of(List.of(Map.of(
            "id", "minecraft:night_vision",
            "duration", 100
        ))));
        candidates.put("minecraft:lodestone_tracker", List.of(Map.of(
            "target", Map.of(
                "pos", List.of(0, 64, 0),
                "dimension", "minecraft:overworld"
            ),
            "tracked", true
        )));
        candidates.put("minecraft:note_block_sound", List.of("minecraft:block.note_block.harp"));
        candidates.put("minecraft:break_sound", List.of("minecraft:entity.item.break"));
        candidates.put("minecraft:block_state", List.of(Map.of("waterlogged", "true")));
        candidates.put("minecraft:block_entity_data", List.of(Map.of("id", "minecraft:chest")));
        candidates.put("minecraft:bucket_entity_data", List.of(Map.of(
            "NoAI", 1,
            "Age", 0
        )));
        candidates.put("minecraft:entity_data", List.of(Map.of(
            "id", "minecraft:pig",
            "Health", 1.0
        )));
        candidates.put("minecraft:debug_stick_state", List.of(Map.of("minecraft:furnace", "facing")));
        candidates.put("minecraft:recipes", List.of(List.of("minecraft:crafting_table")));
        candidates.put("minecraft:custom_data", List.of(Map.of("remotely", Map.of("id", "example"))));
        candidates.put("minecraft:additional_trade_cost", List.of(1));
        candidates.put("minecraft:use_effects", List.of(Map.of(
            "can_sprint", true,
            "interact_vibrations", true,
            "speed_multiplier", 1.0
        )));
        candidates.put("minecraft:minimum_attack_charge", List.of(1.0));
        candidates.put("minecraft:damage_type", List.of("minecraft:generic"));
        candidates.put("minecraft:dye", List.of("white"));
        candidates.put("minecraft:piercing_weapon", List.of(Map.of(
            "deals_knockback", true,
            "dismounts", false,
            "sound", "minecraft:item.trident.throw",
            "hit_sound", "minecraft:item.trident.hit"
        )));
        candidates.put("minecraft:kinetic_weapon", List.of(Map.of(
            "contact_cooldown_ticks", 20,
            "delay_ticks", 0,
            "dismount_conditions", Map.of(
                "max_duration_ticks", 20,
                "min_speed", 0.0,
                "min_relative_speed", 0.0
            ),
            "forward_movement", 1.0,
            "damage_multiplier", 1.0
        )));
        candidates.put("minecraft:attack_range", List.of(Map.of(
            "min_reach", 0.0,
            "max_reach", 3.0,
            "min_creative_reach", 0.0,
            "max_creative_reach", 5.0,
            "hitbox_margin", 0.0,
            "mob_factor", 1.0
        )));
        candidates.put("minecraft:swing_animation", List.of(Map.of(
            "type", "whack",
            "duration", 4
        )));
        candidates.put("minecraft:sulfur_cube_content", List.of("minecraft:green_wool", Map.of("id", "minecraft:green_wool", "count", 1)));
        candidates.put("minecraft:axolotl/variant", List.of("lucy"));
        candidates.put("minecraft:cat/variant", List.of("minecraft:tabby"));
        candidates.put("minecraft:cat/sound_variant", List.of("minecraft:classic"));
        candidates.put("minecraft:cat/collar", List.of("red"));
        candidates.put("minecraft:chicken/variant", List.of("minecraft:temperate"));
        candidates.put("minecraft:chicken/sound_variant", List.of("minecraft:classic"));
        candidates.put("minecraft:cow/variant", List.of("minecraft:temperate"));
        candidates.put("minecraft:cow/sound_variant", List.of("minecraft:classic"));
        candidates.put("minecraft:fox/variant", List.of("red"));
        candidates.put("minecraft:frog/variant", List.of("minecraft:temperate"));
        candidates.put("minecraft:horse/variant", List.of("white"));
        candidates.put("minecraft:llama/variant", List.of("creamy"));
        candidates.put("minecraft:mooshroom/variant", List.of("red"));
        candidates.put("minecraft:parrot/variant", List.of("red_blue"));
        candidates.put("minecraft:pig/variant", List.of("minecraft:temperate"));
        candidates.put("minecraft:pig/sound_variant", List.of("minecraft:classic"));
        candidates.put("minecraft:rabbit/variant", List.of("brown"));
        candidates.put("minecraft:salmon/size", List.of("medium"));
        candidates.put("minecraft:sheep/color", List.of("white"));
        candidates.put("minecraft:shulker/color", List.of("purple"));
        candidates.put("minecraft:tropical_fish/base_color", List.of("white"));
        candidates.put("minecraft:tropical_fish/pattern", List.of("kob"));
        candidates.put("minecraft:tropical_fish/pattern_color", List.of("white"));
        candidates.put("minecraft:wolf/variant", List.of("minecraft:pale"));
        candidates.put("minecraft:wolf/sound_variant", List.of("minecraft:classic"));
        candidates.put("minecraft:wolf/collar", List.of("red"));
        candidates.put("minecraft:villager/variant", List.of("minecraft:plains"));
        candidates.put("minecraft:painting/variant", List.of("minecraft:kebab"));
        candidates.put("minecraft:zombie_nautilus/variant", List.of("minecraft:temperate"));
        return candidates;
    }

    private Map<String, Object> equippableExample(Material material) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("slot", defaultEquipmentSlot(material));
        value.put("equip_sound", "minecraft:item.armor.equip_generic");
        value.put("dispensable", true);
        value.put("swappable", true);
        value.put("damage_on_hurt", true);
        value.put("equip_on_interact", false);
        return value;
    }

    private String defaultEquipmentSlot(Material material) {
        String name = material != null ? material.name() : "";
        if (materialContains(name, "HELMET", "HEAD", "SKULL", "CARVED_PUMPKIN")) {
            return "head";
        }
        if (materialContains(name, "CHESTPLATE", "ELYTRA")) {
            return "chest";
        }
        if (materialContains(name, "LEGGINGS")) {
            return "legs";
        }
        if (materialContains(name, "BOOTS")) {
            return "feet";
        }
        if (materialContains(name, "WOLF_ARMOR", "HORSE_ARMOR")) {
            return "body";
        }
        if (materialContains(name, "SHIELD")) {
            return "offhand";
        }
        return "mainhand";
    }

    private Object candidateExampleValue(Material material, String id, List<Object> candidates) {
        if (material == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (!itemJsonRoundTripSupported()) {
            return null;
        }
        for (Object candidate : candidates) {
            try {
                applyComponents(new ItemStack(material), Map.of(id, candidate));
                return candidate;
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private String keyFromComponent(Object component, String fieldName) {
        if (component == null) {
            return "minecraft:" + fieldName.toLowerCase(Locale.ROOT);
        }
        for (String methodName : List.of("key", "getKey")) {
            try {
                Method method = component.getClass().getMethod(methodName);
                Object value = method.invoke(component);
                if (value != null) {
                    Matcher matcher = MINECRAFT_KEY.matcher(value.toString().toLowerCase(Locale.ROOT));
                    if (matcher.find()) {
                        return matcher.group();
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        Matcher matcher = MINECRAFT_KEY.matcher(component.toString().toLowerCase(Locale.ROOT));
        if (matcher.find()) {
            return matcher.group();
        }
        return "minecraft:" + fieldName.toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> examples() {
        if (injectedExamples != null) {
            return new LinkedHashMap<>(injectedExamples);
        }
        collectExamplesIfNeeded();
        return examples;
    }

    private Map<String, Set<String>> exampleMaterials() {
        if (injectedExamples != null) {
            return Map.of();
        }
        collectExamplesIfNeeded();
        return exampleMaterials;
    }

    private synchronized void collectExamplesIfNeeded() {
        Map<String, Object> cached = examples;
        Map<String, Set<String>> cachedMaterials = exampleMaterials;
        if (cached != null && cachedMaterials != null) {
            return;
        }
        Map<String, Object> collected = new LinkedHashMap<>();
        Map<String, Set<String>> materialsByComponent = new LinkedHashMap<>();
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isAir()) {
                continue;
            }
            Map<String, Object> components = componentsFromStack(new ItemStack(material));
            for (Map.Entry<String, Object> entry : components.entrySet()) {
                collected.putIfAbsent(entry.getKey(), entry.getValue());
                materialsByComponent.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).add(material.name());
            }
        }
        Map<String, Object> sorted = new LinkedHashMap<>();
        collected.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(key -> sorted.put(key, collected.get(key)));
        Map<String, Set<String>> sortedMaterials = new LinkedHashMap<>();
        materialsByComponent.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(key -> sortedMaterials.put(key, Set.copyOf(materialsByComponent.get(key))));
        examples = sorted;
        exampleMaterials = sortedMaterials;
    }

    private boolean itemJsonRoundTripSupported() {
        return injectedRoundTripSupport != null ? injectedRoundTripSupport : PaperUnsafe.itemJsonRoundTripSupported();
    }

    private Map<String, Object> schema(Object value) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("kind", valueKind(value));
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    fields.put(entry.getKey().toString(), schema(entry.getValue()));
                }
            }
            schema.put("fields", fields);
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            schema.put("items", schema(list.getFirst()));
        }
        return schema;
    }

    private String valueKind(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        return "raw";
    }

    private String label(String id) {
        String explicit = switch (id != null ? id : "") {
            case "minecraft:base_color" -> "Base Color";
            case "minecraft:bees" -> "Stored Bees";
            case "minecraft:blocks_attacks" -> "Blocks Attacks";
            case "minecraft:block_entity_data" -> "Block Entity Data";
            case "minecraft:block_state" -> "Block State";
            case "minecraft:break_sound" -> "Break Sound";
            case "minecraft:bucket_entity_data" -> "Bucket Entity Data";
            case "minecraft:container_loot" -> "Container Loot";
            case "minecraft:creative_slot_lock" -> "Creative Slot Lock";
            case "minecraft:custom_data" -> "Custom Data";
            case "minecraft:dye" -> "Dye";
            case "minecraft:death_protection" -> "Death Protection";
            case "minecraft:debug_stick_state" -> "Debug Stick State";
            case "minecraft:entity_data" -> "Entity Data";
            case "minecraft:equippable" -> "Equippable";
            case "minecraft:lock" -> "Container Lock";
            case "minecraft:lodestone_tracker" -> "Lodestone Tracker";
            case "minecraft:note_block_sound" -> "Note Block Sound";
            case "minecraft:potion_duration_scale" -> "Potion Duration Scale";
            case "minecraft:pot_decorations" -> "Pot Decorations";
            case "minecraft:profile" -> "Player Profile";
            case "minecraft:provides_banner_patterns" -> "Provides Banner Patterns";
            case "minecraft:provides_trim_material" -> "Provides Trim Material";
            case "minecraft:recipes" -> "Recipes";
            case "minecraft:repairable" -> "Repairable";
            case "minecraft:sulfur_cube_content" -> "Sulfur Cube Content";
            case "minecraft:suspicious_stew_effects" -> "Suspicious Stew Effects";
            case "minecraft:tool" -> "Tool";
            case "minecraft:tooltip_style" -> "Tooltip Style";
            case "minecraft:writable_book_content" -> "Writable Book Content";
            case "minecraft:written_book_content" -> "Written Book Content";
            default -> "";
        };
        if (!explicit.isBlank()) {
            return explicit;
        }
        String cleaned = id != null && id.contains(":") ? id.substring(id.indexOf(':') + 1) : String.valueOf(id);
        StringBuilder builder = new StringBuilder();
        for (String part : cleaned.replace('_', ' ').replace('.', ' ').replace('/', ' ').split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return builder.isEmpty() ? cleaned : builder.toString();
    }

    private String description(String id) {
        return switch (id != null ? id : "") {
            case "minecraft:food" -> "Nutrition And Saturation";
            case "minecraft:consumable" -> "Use Time And Animation";
            case "minecraft:use_effects" -> "Use Movement And Signals";
            case "minecraft:use_cooldown" -> "Reusable Item Delay";
            case "minecraft:use_remainder" -> "Item Left After Use";
            case "minecraft:custom_model_data", "minecraft:item_model" -> "Item Model";
            case "minecraft:lore", "minecraft:item_name", "minecraft:custom_name" -> "Display Text";
            case "minecraft:tooltip_display" -> "Tooltip Visibility";
            case "minecraft:tooltip_style" -> "Tooltip Styling";
            case "minecraft:writable_book_content", "minecraft:written_book_content" -> "Book Pages";
            case "minecraft:profile" -> "Player Head Profile";
            case "minecraft:can_break" -> "Adventure Break Rules";
            case "minecraft:can_place_on" -> "Adventure Placement Rules";
            case "minecraft:creative_slot_lock" -> "Creative Inventory Lock";
            case "minecraft:lock" -> "Container Lock";
            case "minecraft:additional_trade_cost" -> "Villager Trade Cost";
            case "minecraft:attribute_modifiers" -> "Stats And Equipment Slots";
            case "minecraft:minimum_attack_charge" -> "Minimum Attack Charge";
            case "minecraft:damage_type" -> "Attack Damage Type";
            case "minecraft:piercing_weapon" -> "Piercing Weapon Behavior";
            case "minecraft:kinetic_weapon" -> "Kinetic Weapon Behavior";
            case "minecraft:attack_range" -> "Attack Reach";
            case "minecraft:blocks_attacks" -> "Attack Blocking";
            case "minecraft:swing_animation" -> "Swing Animation";
            case "minecraft:death_protection" -> "Death Protection";
            case "minecraft:trim" -> "Armor Trim Material And Pattern";
            case "minecraft:provides_trim_material" -> "Trim Material Provider";
            case "minecraft:firework_explosion" -> "Firework Shape And Colors";
            case "minecraft:fireworks" -> "Rocket Flight And Explosions";
            case "minecraft:banner_patterns" -> "Banner Patterns";
            case "minecraft:provides_banner_patterns" -> "Banner Pattern Provider";
            case "minecraft:base_color" -> "Base Banner Color";
            case "minecraft:pot_decorations" -> "Decorated Pot";
            case "minecraft:charged_projectiles" -> "Loaded Projectiles";
            case "minecraft:bundle_contents" -> "Bundle Items";
            case "minecraft:container" -> "Stored Items";
            case "minecraft:container_loot" -> "Container Loot";
            case "minecraft:bees" -> "Stored Bees";
            case "minecraft:enchantment_glint_override" -> "Visual Shine Override";
            case "minecraft:max_stack_size" -> "Stack Size";
            case "minecraft:max_damage", "minecraft:damage", "minecraft:unbreakable", "minecraft:repairable" -> "Durability";
            case "minecraft:enchantable" -> "Enchanting Power";
            case "minecraft:instrument" -> "Goat Horn Sound";
            case "minecraft:jukebox_playable" -> "Jukebox Song";
            case "minecraft:glider" -> "Elytra Flight";
            case "minecraft:equippable" -> "Equipment Slot And Sounds";
            case "minecraft:lodestone_tracker" -> "Compass Tracking";
            case "minecraft:note_block_sound", "minecraft:break_sound" -> "Item Sound";
            case "minecraft:block_state" -> "Block State";
            case "minecraft:block_entity_data" -> "Block Entity Data";
            case "minecraft:bucket_entity_data", "minecraft:entity_data" -> "Entity Data";
            case "minecraft:debug_stick_state" -> "Debug Stick State";
            case "minecraft:recipes" -> "Recipe Unlocks";
            case "minecraft:custom_data" -> "Custom Data";
            case "minecraft:damage_resistant" -> "Ignored Damage Types";
            case "minecraft:weapon" -> "Weapon Durability And Shield Disable";
            case "minecraft:tool" -> "Tool Rules";
            case "minecraft:intangible_projectile" -> "Projectile Pickup Behavior";
            case "minecraft:ominous_bottle_amplifier" -> "Ominous Bottle";
            case "minecraft:rarity" -> "Rarity";
            case "minecraft:dyed_color" -> "Item Color";
            case "minecraft:dye" -> "Dye Color";
            case "minecraft:enchantments", "minecraft:stored_enchantments" -> "Enchantments";
            case "minecraft:potion_contents" -> "Potion Contents";
            case "minecraft:potion_duration_scale" -> "Potion Duration";
            case "minecraft:suspicious_stew_effects" -> "Stew Effects";
            case "minecraft:sulfur_cube_content" -> "Absorbed Item";
            case "minecraft:cat/sound_variant", "minecraft:chicken/sound_variant", "minecraft:cow/sound_variant", "minecraft:pig/sound_variant" -> "Entity Sound Variant";
            case "minecraft:zombie_nautilus/variant" -> "Entity Variant";
            default -> "Raw Component Data";
        };
    }

    private Material material(String materialName) {
        Material material = Material.matchMaterial(materialName != null && !materialName.isBlank() ? materialName : "STICK");
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }

    private String normalizeComponentId(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String value = id.trim().toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private boolean isNamespacedComponentId(String id) {
        String value = id != null ? id.trim().toLowerCase(Locale.ROOT) : "";
        return value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    private Map<String, Object> error(String component, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("component", component != null ? component : "");
        error.put("message", message != null ? message : "Invalid component");
        return error;
    }

    private Map<String, Object> jsonObjectToMap(JsonObject object) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            map.put(entry.getKey(), jsonToValue(entry.getValue()));
        }
        return map;
    }

    private Object jsonToValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsNumber();
            }
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                values.add(jsonToValue(item));
            }
            return values;
        }
        if (element.isJsonObject()) {
            return jsonObjectToMap(element.getAsJsonObject());
        }
        return null;
    }

    private record AttributeUiProfile(String category, int priority, List<String> appliesTo, String editor, List<String> search) {
    }
}
