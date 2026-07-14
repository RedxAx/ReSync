package restudio.resync.modules.flow;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Biome;
import org.bukkit.DyeColor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Recipe;
import org.bukkit.potion.PotionEffectType;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.core.Session;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FlowOptionCatalogPacketHandler {
    private final FlowPacketSender sender;
    private final CustomContentService customContentService;
    private final OptionCatalogRegistry optionCatalogRegistry;
    private final ItemAttributeSchemaService itemAttributeSchemaService = new ItemAttributeSchemaService();
    private final Map<String, CatalogSnapshot> customContentCatalogSnapshots = new HashMap<>();

    public FlowOptionCatalogPacketHandler(FlowPacketSender sender, CustomContentService customContentService) {
        this(sender, customContentService, null);
    }

    public FlowOptionCatalogPacketHandler(FlowPacketSender sender, CustomContentService customContentService, OptionCatalogRegistry optionCatalogRegistry) {
        this.sender = sender;
        this.customContentService = customContentService;
        this.optionCatalogRegistry = optionCatalogRegistry;
    }

    public void handle(Session session, ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return;
        }
        int sourceLength = buffer.getInt();
        if (sourceLength < 0 || sourceLength > FlowPacketSender.MAX_STRING_LENGTH || sourceLength > buffer.remaining()) {
            return;
        }
        byte[] sourceBytes = new byte[sourceLength];
        buffer.get(sourceBytes);
        String sourceId = new String(sourceBytes, StandardCharsets.UTF_8);
        String normalized = normalize(sourceId);
        if (normalized.equals("item_attribute_schema") || normalized.startsWith("item_attribute_schema:")) {
            String material = normalized.equals("item_attribute_schema") ? "" : normalized.substring("item_attribute_schema:".length());
            List<OptionCatalogItem> items = itemAttributeSchemaService.catalog(material);
            sender.sendOptionCatalog(session, sourceId, items.stream().map(OptionCatalogItem::value).toList(), items, itemAttributeSchemaService.revision(material));
            return;
        }
        OptionCatalogProvider provider = optionCatalogRegistry != null ? optionCatalogRegistry.provider(sourceId) : null;
        if (provider != null) {
            sender.sendOptionCatalog(session, sourceId, provider.values(), provider.items(), provider.revision());
            return;
        }
        if ("custom_content_recipe_item".equals(normalize(sourceId)) && customContentService != null) {
            List<OptionCatalogItem> items = customContentService.recipeItemCatalog();
            List<String> values = items.stream().map(OptionCatalogItem::value).toList();
            sender.sendOptionCatalog(session, sourceId, values, items, "recipe_item:" + Bukkit.getVersion());
            return;
        }
        sender.sendOptionCatalog(session, sourceId, values(sourceId), revision(sourceId));
    }

    public void broadcastCustomContentCatalogs() {
        for (CatalogSnapshot snapshot : customContentCatalogSnapshots()) {
            customContentCatalogSnapshots.put(snapshot.sourceId(), snapshot);
            snapshot.broadcast(sender);
        }
    }

    public void broadcastChangedCustomContentCatalogs() {
        for (CatalogSnapshot snapshot : customContentCatalogSnapshots()) {
            CatalogSnapshot previous = customContentCatalogSnapshots.get(snapshot.sourceId());
            if (snapshot.equals(previous)) {
                continue;
            }
            customContentCatalogSnapshots.put(snapshot.sourceId(), snapshot);
            snapshot.broadcast(sender);
        }
    }

    private List<CatalogSnapshot> customContentCatalogSnapshots() {
        List<CatalogSnapshot> snapshots = new ArrayList<>();
        for (String sourceId : List.of(
            "server:custom_content:recipe_item",
            "server:custom_content:provider",
            "server:custom_content:nexo_item",
            "server:custom_content:nexo_block",
            "server:custom_content:nexo_furniture",
            "server:custom_content:nexo_armor"
        )) {
            snapshots.add(customContentCatalogSnapshot(sourceId));
        }
        return snapshots;
    }

    private CatalogSnapshot customContentCatalogSnapshot(String sourceId) {
        OptionCatalogProvider provider = optionCatalogRegistry != null ? optionCatalogRegistry.provider(sourceId) : null;
        if (provider != null) {
            return new CatalogSnapshot(sourceId, provider.values(), provider.items(), provider.revision());
        }
        if ("custom_content_recipe_item".equals(normalize(sourceId)) && customContentService != null) {
            List<OptionCatalogItem> items = customContentService.recipeItemCatalog();
            return new CatalogSnapshot(sourceId, items.stream().map(OptionCatalogItem::value).toList(), items, "recipe_item:" + Bukkit.getVersion());
        }
        return new CatalogSnapshot(sourceId, values(sourceId), List.of(), revision(sourceId));
    }

    private record CatalogSnapshot(String sourceId, List<String> values, List<OptionCatalogItem> items, String revision) {
        CatalogSnapshot {
            sourceId = sourceId != null ? sourceId : "";
            values = values != null ? List.copyOf(values) : List.of();
            items = items != null ? items.stream().map(FlowOptionCatalogPacketHandler::normalizeCatalogItem).toList() : List.of();
            revision = revision != null ? revision : "";
        }

        private void broadcast(FlowPacketSender sender) {
            sender.broadcastOptionCatalog(sourceId, values, items, revision);
        }
    }

    private static OptionCatalogItem normalizeCatalogItem(OptionCatalogItem item) {
        if (item == null) {
            return new OptionCatalogItem("");
        }
        return new OptionCatalogItem(item.value(), item.label(), item.description(), item.icon(), item.group(), normalizeCatalogMetadata(item.metadata()));
    }

    private static Map<String, Object> normalizeCatalogMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            normalized.put(entry.getKey(), normalizeCatalogMetadataValue(entry.getValue()));
        }
        return normalized;
    }

    private static Object normalizeCatalogMetadataValue(Object value) {
        if (value == null) {
            return null;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            List<Object> normalized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                normalized.add(normalizeCatalogMetadataValue(Array.get(value, i)));
            }
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FlowOptionCatalogPacketHandler::normalizeCatalogMetadataValue).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalizeCatalogMetadataValue(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros();
            } catch (NumberFormatException ignored) {
                return number.doubleValue();
            }
        }
        return value;
    }

    private List<String> values(String sourceId) {
        OptionCatalogProvider provider = optionCatalogRegistry != null ? optionCatalogRegistry.provider(sourceId) : null;
        if (provider != null) {
            return provider.values();
        }
        return switch (normalize(sourceId)) {
            case "advancement" -> advancements();
            case "biome" -> registryKeys(Registry.BIOME);
            case "difficulty" -> List.of("peaceful", "easy", "normal", "hard");
            case "attribute" -> registryKeysByField("ATTRIBUTE");
            case "banner_pattern" -> registryKeysByField("BANNER_PATTERN");
            case "damage_type" -> registryKeysByField("DAMAGE_TYPE");
            case "dye_color" -> enumNames(DyeColor.values());
            case "enchantment" -> registryKeys(Registry.ENCHANTMENT);
            case "entity_type" -> enumNames(EntityType.values());
            case "axolotl_variant" -> List.of("lucy", "wild", "gold", "cyan", "blue");
            case "cat_variant" -> registryKeysByField("CAT_VARIANT");
            case "cat_sound_variant" -> registryKeysByField("CAT_SOUND_VARIANT");
            case "chicken_variant" -> registryKeysByField("CHICKEN_VARIANT");
            case "chicken_sound_variant" -> registryKeysByField("CHICKEN_SOUND_VARIANT");
            case "cow_variant" -> registryKeysByField("COW_VARIANT");
            case "cow_sound_variant" -> registryKeysByField("COW_SOUND_VARIANT");
            case "fox_variant" -> List.of("red", "snow");
            case "frog_variant" -> registryKeysByField("FROG_VARIANT");
            case "horse_variant" -> List.of("white", "creamy", "chestnut", "brown", "black", "gray", "dark_brown");
            case "llama_variant" -> List.of("creamy", "white", "brown", "gray");
            case "mooshroom_variant" -> List.of("red", "brown");
            case "painting_variant" -> registryKeysByFields("PAINTING_VARIANT", "ART");
            case "parrot_variant" -> List.of("red_blue", "blue", "green", "yellow_blue", "gray");
            case "pig_variant" -> registryKeysByField("PIG_VARIANT");
            case "pig_sound_variant" -> registryKeysByField("PIG_SOUND_VARIANT");
            case "rabbit_variant" -> List.of("brown", "white", "black", "white_splotched", "gold", "salt", "evil");
            case "salmon_size" -> List.of("small", "medium", "large");
            case "tropical_fish_pattern" -> List.of("kob", "sunstreak", "snooper", "dasher", "brinely", "spotty", "flopper", "stripey", "glitter", "blockfish", "betty", "clayfish");
            case "villager_type" -> registryKeysByField("VILLAGER_TYPE");
            case "wolf_variant" -> registryKeysByField("WOLF_VARIANT");
            case "wolf_sound_variant" -> registryKeysByField("WOLF_SOUND_VARIANT");
            case "zombie_nautilus_variant" -> registryKeysByField("ZOMBIE_NAUTILUS_VARIANT");
            case "gamemode" -> List.of("survival", "creative", "adventure", "spectator");
            case "material" -> enumNames(Material.values());
            case "block" -> blocks();
            case "instrument" -> registryKeysByField("INSTRUMENT");
            case "jukebox_song" -> registryKeysByField("JUKEBOX_SONG");
            case "trim_material" -> registryKeysByField("TRIM_MATERIAL");
            case "trim_pattern" -> registryKeysByField("TRIM_PATTERN");
            case "loot_table" -> registryKeys(Registry.LOOT_TABLES);
            case "recipe" -> recipes();
            case "particle" -> registryKeys(Registry.PARTICLE_TYPE);
            case "potion" -> potionTypes();
            case "potion_effect" -> potionEffects();
            case "sound" -> registryKeys(Registry.SOUNDS);
            case "world" -> Bukkit.getWorlds().stream().map(World::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            case "custom_content_provider" -> customContentService != null ? customContentService.getAvailableProviderIds() : List.of("vanilla");
            case "custom_content_nexo_item" -> customContentService != null ? customContentService.getProviderOptionIds("nexo", "item") : List.of();
            case "custom_content_nexo_block" -> customContentService != null ? customContentService.getProviderOptionIds("nexo", "block") : List.of();
            case "custom_content_nexo_furniture" -> customContentService != null ? customContentService.getProviderOptionIds("nexo", "furniture") : List.of();
            case "custom_content_nexo_armor" -> customContentService != null ? customContentService.getProviderOptionIds("nexo", "armor") : List.of();
            default -> List.of();
        };
    }

    private String revision(String sourceId) {
        OptionCatalogProvider provider = optionCatalogRegistry != null ? optionCatalogRegistry.provider(sourceId) : null;
        if (provider != null) {
            return provider.revision();
        }
        return normalize(sourceId) + ":" + Bukkit.getVersion();
    }

    private String normalize(String sourceId) {
        String value = sourceId != null ? sourceId.toLowerCase(Locale.ROOT) : "";
        if (value.startsWith("server:minecraft:")) {
            return value.substring("server:minecraft:".length());
        }
        if (value.startsWith("minecraft:")) {
            return value.substring("minecraft:".length());
        }
        if (value.startsWith("client:minecraft:")) {
            return value.substring("client:minecraft:".length());
        }
        if (value.startsWith("server:custom_content:")) {
            return "custom_content_" + value.substring("server:custom_content:".length());
        }
        return value;
    }

    private List<String> advancements() {
        List<String> values = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining(advancement -> values.add(key(advancement)));
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private String key(Advancement advancement) {
        NamespacedKey key = advancement.getKey();
        return key != null ? key.toString() : "";
    }

    private List<String> potionEffects() {
        List<String> values = new ArrayList<>();
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type != null) {
                values.add(type.getKey().toString());
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private List<String> potionTypes() {
        List<String> values = new ArrayList<>();
        try {
            Class<?> type = Class.forName("org.bukkit.potion.PotionType");
            Object[] constants = type.isEnum() ? type.getEnumConstants() : new Object[0];
            for (Object constant : constants) {
                String key = keyedValue(constant);
                values.add(key != null && !key.isBlank() ? key : "minecraft:" + constant.toString().toLowerCase(Locale.ROOT));
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private String keyedValue(Object value) {
        if (!(value instanceof Keyed keyed) || keyed.getKey() == null) {
            return "";
        }
        return keyed.getKey().toString();
    }

    private List<String> blocks() {
        List<String> values = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isBlock()) {
                values.add(material.name().toLowerCase(Locale.ROOT));
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private List<String> recipes() {
        List<String> values = new ArrayList<>();
        Bukkit.recipeIterator().forEachRemaining(recipe -> addRecipeKey(values, recipe));
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private void addRecipeKey(List<String> values, Recipe recipe) {
        if (recipe instanceof Keyed keyed) {
            values.add(keyed.getKey().toString());
        }
    }

    private <T extends Keyed> List<String> registryKeys(Registry<T> registry) {
        List<String> values = new ArrayList<>();
        for (T value : registry) {
            values.add(value.getKey().toString());
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private List<String> registryKeysByField(String fieldName) {
        List<String> values = new ArrayList<>();
        try {
            Field field = Registry.class.getField(fieldName);
            Object registry = field.get(null);
            if (registry instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    String key = keyedValue(value);
                    if (!key.isBlank()) {
                        values.add(key);
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private List<String> registryKeysByFields(String... fieldNames) {
        for (String fieldName : fieldNames) {
            List<String> values = registryKeysByField(fieldName);
            if (!values.isEmpty()) {
                return values;
            }
        }
        return List.of();
    }

    private <E extends Enum<E>> List<String> enumNames(E[] values) {
        List<String> result = new ArrayList<>();
        for (E value : values) {
            result.add(value.name().toLowerCase(Locale.ROOT));
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }
}
