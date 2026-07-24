package restudio.resync.modules.flow;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Biome;
import org.bukkit.DyeColor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Recipe;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.DisplaySlot;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.flow.data.FlowTypeRef;

import java.lang.reflect.Field;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class BuiltinOptionCatalogService {
    private static final List<CatalogDefinition> CATALOGS = List.of(
        catalog("advancement", true),
        catalog("biome", false),
        catalog("difficulty", false),
        catalog("display_slot", false),
        catalog("attribute", true),
        catalog("banner_pattern", true),
        catalog("damage_type", true),
        catalog("dye_color", false),
        catalog("enchantment", true),
        catalog("entity_type", false),
        catalog("entity_data_property", true),
        catalog("entity_writable_data_property", true),
        catalog("entity_text_property", true),
        catalog("entity_boolean_property", true),
        catalog("entity_number_property", true),
        catalog("entity_vector_property", true),
        catalog("entity_location_property", true),
        catalog("entity_reference_property", true),
        catalog("item_component_number", true),
        catalog("item_component_boolean", true),
        catalog("item_component_text", true),
        catalog("item_component_object", true),
        catalog("item_component_list", true),
        catalog("item_component_presence", true),
        catalog("axolotl_variant", false),
        catalog("cat_variant", true),
        catalog("cat_sound_variant", false),
        catalog("chicken_variant", false),
        catalog("chicken_sound_variant", false),
        catalog("cow_variant", false),
        catalog("cow_sound_variant", false),
        catalog("fox_variant", false),
        catalog("frog_variant", false),
        catalog("horse_variant", false),
        catalog("llama_variant", false),
        catalog("mooshroom_variant", false),
        catalog("painting_variant", true),
        catalog("parrot_variant", false),
        catalog("pig_variant", false),
        catalog("pig_sound_variant", false),
        catalog("rabbit_variant", false),
        catalog("salmon_size", false),
        catalog("tropical_fish_pattern", false),
        catalog("villager_type", false),
        catalog("wolf_variant", true),
        catalog("wolf_sound_variant", false),
        catalog("zombie_nautilus_variant", false),
        catalog("gamemode", false),
        catalog("instrument", true),
        catalog("jukebox_song", true),
        catalog("material", true),
        catalog("named_text_color", false),
        resourceCatalog("network_node_status", false),
        resourceCatalog("network_scope", false),
        resourceCatalog("network_variable_type", false),
        resourceCatalog("resource_kind", true),
        resourceCatalog("time_zone", true),
        resourceCatalog("locale", true),
        catalog("block", true),
        catalog("loot_table", true),
        catalog("recipe", true),
        catalog("particle", true),
        catalog("potion", true),
        catalog("potion_effect", false),
        catalog("sound", true),
        catalog("text_decoration", false),
        catalog("trim_material", true),
        catalog("trim_pattern", true),
        catalog("world", true)
    );
    private static final List<CatalogDefinition> CUSTOM_CONTENT_CATALOGS = List.of(
        customContentCatalog("provider", false),
        customContentCatalog("asset", true),
        customContentCatalog("recipe_item", true),
        customContentCatalog("nexo_item", true),
        customContentCatalog("nexo_block", true),
        customContentCatalog("nexo_furniture", true),
        customContentCatalog("nexo_armor", true)
    );

    private final Supplier<CustomContentService> customContentService;
    private final ItemAttributeSchemaService itemAttributeSchemaService;
    private final List<OptionCatalogProvider> providers;

    public BuiltinOptionCatalogService(Supplier<CustomContentService> customContentService, ItemAttributeSchemaService itemAttributeSchemaService) {
        this.customContentService = customContentService;
        this.itemAttributeSchemaService = itemAttributeSchemaService;
        providers = Stream.concat(Stream.concat(CATALOGS.stream(), CUSTOM_CONTENT_CATALOGS.stream()).map(this::provider), Stream.of(itemAttributeProvider())).toList();
    }

    public void registerProviders(OptionCatalogRegistry registry) {
        if (registry == null) {
            return;
        }
        providers.forEach(registry::register);
    }

    public ItemAttributeSchemaService itemAttributeSchemaService() {
        return itemAttributeSchemaService;
    }

    private OptionCatalogProvider itemAttributeProvider() {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return ItemAttributeSchemaService.SOURCE;
            }

            @Override
            public Set<String> contextKeys() {
                return Set.of("material");
            }

            @Override
            public String revision() {
                return itemAttributeSchemaService.revision("");
            }

            @Override
            public String revision(OptionCatalogQuery query) {
                return itemAttributeSchemaService.revision(query != null ? query.text("material") : "");
            }

            @Override
            public List<String> values() {
                return items().stream().map(OptionCatalogItem::value).toList();
            }

            @Override
            public List<String> values(OptionCatalogQuery query) {
                return items(query).stream().map(OptionCatalogItem::value).toList();
            }

            @Override
            public List<OptionCatalogItem> items() {
                return itemAttributeSchemaService.catalog("");
            }

            @Override
            public List<OptionCatalogItem> items(OptionCatalogQuery query) {
                return itemAttributeSchemaService.catalog(query != null ? query.text("material") : "");
            }
        };
    }

    private OptionCatalogProvider provider(CatalogDefinition definition) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return definition.sourceId();
            }

            @Override
            public String providerId() {
                return definition.providerId();
            }

            @Override
            public String widgetType() {
                return definition.searchable() ? "SEARCHABLE_LIST" : "DROPDOWN";
            }

            @Override
            public boolean searchable() {
                return definition.searchable();
            }

            @Override
            public Set<String> contextKeys() {
                return definition.key().equals("custom_content_asset") ? Set.of("provider", "content_type") : Set.of();
            }

            @Override
            public FlowTypeRef runtimeDataType() {
                return catalogRuntimeType(definition.key());
            }

            @Override
            public Class<?> runtimeDataClass() {
                return catalogRuntimeClass(definition.key());
            }

            @Override
            public Object resolveRuntimeData(String value) {
                return resolveCatalogValue(definition.key(), value);
            }

            @Override
            public String revision() {
                List<String> values = values();
                return sourceId() + ":" + Bukkit.getVersion() + ":" + values.size() + ":" + values.hashCode();
            }

            @Override
            public String revision(OptionCatalogQuery query) {
                if (!definition.key().equals("custom_content_asset")) {
                    return revision();
                }
                List<String> values = values(query);
                return sourceId() + ":" + customContentProvider(query) + ":" + customContentType(query) + ":" + values.size() + ":" + values.hashCode();
            }

            @Override
            public List<String> values() {
                return resolve(definition.key());
            }

            @Override
            public List<String> values(OptionCatalogQuery query) {
                return definition.key().equals("custom_content_asset") ? customContentAssets(query) : values();
            }

            @Override
            public String status(OptionCatalogQuery query) {
                if (!definition.key().equals("custom_content_asset")) {
                    return "available";
                }
                CustomContentService service = customContentService.get();
                String provider = customContentProvider(query);
                String contentType = customContentType(query);
                if (service == null) {
                    return "unavailable";
                }
                if (provider.isBlank() || contentType.isBlank()) {
                    return "invalid";
                }
                return service.isProviderAvailable(provider) ? "available" : "unavailable";
            }

            @Override
            public String diagnostic(OptionCatalogQuery query) {
                if (!definition.key().equals("custom_content_asset")) {
                    return "";
                }
                if (customContentService.get() == null) {
                    return "Custom content service is unavailable";
                }
                String provider = customContentProvider(query);
                if (provider.isBlank()) {
                    return "Custom content provider context is required";
                }
                if (!customContentService.get().isProviderAvailable(provider)) {
                    return "Custom content provider is unavailable: " + provider;
                }
                return customContentType(query).isBlank() ? "Custom content type context is required" : "";
            }

            @Override
            public List<OptionCatalogItem> items() {
                if (definition.key().equals("custom_content_recipe_item")) {
                    CustomContentService service = customContentService.get();
                    return service != null ? service.recipeItemCatalog() : List.of();
                }
                return richItems(definition, values());
            }

            @Override
            public List<OptionCatalogItem> items(OptionCatalogQuery query) {
                if (definition.key().equals("custom_content_recipe_item")) {
                    return items();
                }
                if (definition.key().equals("custom_content_asset")) {
                    return customContentAssetItems(definition, query);
                }
                if (query == null || query.context().isEmpty()) {
                    return items();
                }
                return richItems(definition, values(query));
            }
        };
    }

    private List<String> customContentAssets(OptionCatalogQuery query) {
        CustomContentService service = customContentService.get();
        String provider = customContentProvider(query);
        String contentType = customContentType(query);
        if (service == null || provider.isBlank() || contentType.isBlank() || !service.isProviderAvailable(provider)) {
            return List.of();
        }
        return service.getProviderOptionIds(provider, contentType);
    }

    private List<OptionCatalogItem> customContentAssetItems(CatalogDefinition definition, OptionCatalogQuery query) {
        String provider = customContentProvider(query);
        String contentType = customContentType(query);
        String group = displayLabel(provider);
        CustomContentService service = customContentService.get();
        List<OptionCatalogItem> providerItems = service != null ? service.getProviderOptionCatalog(provider, contentType) : List.of();
        return providerItems.stream().map(item -> {
            Map<String, Object> metadata = new LinkedHashMap<>(item.metadata());
            metadata.put("source", definition.sourceId());
            metadata.put("catalog", definition.key());
            return new OptionCatalogItem(item.value(), item.label(), item.description(), item.icon(),
                !item.group().isBlank() ? item.group() : group, metadata);
        }).toList();
    }

    private String customContentProvider(OptionCatalogQuery query) {
        return query != null ? query.text("provider").trim().toLowerCase(Locale.ROOT) : "";
    }

    private String customContentType(OptionCatalogQuery query) {
        if (query == null) {
            return "";
        }
        String contentType = query.text("content_type").trim().toLowerCase(Locale.ROOT);
        if (!contentType.isBlank()) {
            return contentType;
        }
        String nodeType = query.text("$nodeType").trim().toLowerCase(Locale.ROOT);
        int separator = Math.max(nodeType.lastIndexOf('.'), nodeType.lastIndexOf(':'));
        return separator >= 0 && separator + 1 < nodeType.length() ? nodeType.substring(separator + 1) : "";
    }

    private List<OptionCatalogItem> richItems(CatalogDefinition definition, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().map(value -> richItem(definition, value)).toList();
    }

    private OptionCatalogItem richItem(CatalogDefinition definition, String value) {
        int namespaceSeparator = value.indexOf(':');
        String namespace = namespaceSeparator > 0 ? value.substring(0, namespaceSeparator) : definition.providerId();
        String terminalValue = namespaceSeparator >= 0 && namespaceSeparator + 1 < value.length() ? value.substring(namespaceSeparator + 1) : value;
        String label = displayLabel(terminalValue);
        String group = displayLabel(namespace);
        String catalogName = displayLabel(definition.key().replace("custom_content_", ""));
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(value);
        aliases.add(value.toLowerCase(Locale.ROOT));
        aliases.add(terminalValue);
        aliases.add(terminalValue.replace('_', ' '));
        aliases.add(label);
        Map<String, Object> metadata = Map.of(
            "aliases", aliases.stream().filter(alias -> alias != null && !alias.isBlank()).toList(),
            "available", true,
            "owner", "builtin",
            "provider", definition.providerId(),
            "source", definition.sourceId(),
            "catalog", definition.key()
        );
        return new OptionCatalogItem(value, label, catalogName + " provided by " + group + ".", "", group, metadata);
    }

    private String displayLabel(String value) {
        if (value == null || value.isBlank()) {
            return "Other";
        }
        if ("resync".equalsIgnoreCase(value)) {
            return "ReSync";
        }
        if ("minecraft".equalsIgnoreCase(value)) {
            return "Minecraft";
        }
        if ("custom_content".equalsIgnoreCase(value)) {
            return "Custom Content";
        }
        String[] words = value.replace('-', '_').split("_+");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return label.isEmpty() ? value : label.toString();
    }

    private List<String> resolve(String key) {
        CustomContentService service = customContentService.get();
        return switch (key) {
            case "advancement" -> advancements();
            case "biome" -> registryKeys(Registry.BIOME);
            case "difficulty" -> List.of("peaceful", "easy", "normal", "hard");
            case "display_slot" -> enumNames(DisplaySlot.values());
            case "attribute" -> registryKeysByField("ATTRIBUTE");
            case "banner_pattern" -> registryKeysByField("BANNER_PATTERN");
            case "damage_type" -> registryKeysByField("DAMAGE_TYPE");
            case "dye_color" -> enumNames(DyeColor.values());
            case "enchantment" -> registryKeys(Registry.ENCHANTMENT);
            case "entity_type" -> enumNames(EntityType.values());
            case "entity_data_property" -> entityDataProperties();
            case "entity_writable_data_property" -> entityDataProperties().stream().filter(value -> !"type".equals(value) && !"uuid".equals(value)).toList();
            case "entity_text_property" -> List.of("custom_name");
            case "entity_boolean_property" -> List.of("custom_name_visible", "glowing", "silent", "invulnerable", "gravity", "visual_fire", "ai", "collidable", "can_pickup_items", "persistent", "remove_when_far_away", "age_locked", "baby", "incendiary", "powered", "ignited");
            case "entity_number_property" -> List.of("fire_ticks", "freeze_ticks", "ticks_lived", "fall_distance", "portal_cooldown", "health", "absorption", "age", "fuse_ticks", "yield", "explosion_radius", "max_fuse_ticks");
            case "entity_vector_property" -> List.of("velocity");
            case "entity_location_property" -> List.of("location");
            case "entity_reference_property" -> List.of("target");
            case "item_component_number" -> itemComponentProperties("number", false);
            case "item_component_boolean" -> itemComponentProperties("boolean", false);
            case "item_component_text" -> itemComponentProperties("string", false);
            case "item_component_object" -> itemComponentProperties("object", false);
            case "item_component_list" -> itemComponentProperties("array", false);
            case "item_component_presence" -> itemComponentProperties("object", true);
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
            case "named_text_color" -> List.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
            case "network_node_status" -> List.of("ONLINE", "DRAINING", "MAINTENANCE");
            case "network_scope" -> List.of("NETWORK", "REALM", "GROUP", "SERVER", "PLAYER");
            case "network_variable_type" -> List.of("BOOLEAN", "INTEGER", "DECIMAL", "STRING", "JSON", "UUID", "BYTES");
            case "resource_kind" -> ReSyncResourceCatalog.all().stream().map(resource -> resource.typeId()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            case "time_zone" -> ZoneId.getAvailableZoneIds().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            case "locale" -> Stream.of(Locale.getAvailableLocales()).map(Locale::toLanguageTag).filter(value -> !value.isBlank() && !"und".equals(value))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
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
            case "text_decoration" -> enumNames(TextDecoration.values());
            case "world" -> Bukkit.getWorlds().stream().map(World::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            case "custom_content_provider" -> service != null ? service.getAvailableProviderIds() : List.of("vanilla");
            case "custom_content_asset" -> List.of();
            case "custom_content_recipe_item" -> service != null ? service.recipeItemCatalog().stream().map(OptionCatalogItem::value).toList() : List.of();
            case "custom_content_nexo_item" -> service != null ? service.getProviderOptionIds("nexo", "item") : List.of();
            case "custom_content_nexo_block" -> service != null ? service.getProviderOptionIds("nexo", "block") : List.of();
            case "custom_content_nexo_furniture" -> service != null ? service.getProviderOptionIds("nexo", "furniture") : List.of();
            case "custom_content_nexo_armor" -> service != null ? service.getProviderOptionIds("nexo", "armor") : List.of();
            default -> List.of();
        };
    }

    private FlowTypeRef catalogRuntimeType(String key) {
        return FlowTypeRef.simple(switch (key) {
            case "material", "block" -> "material";
            case "biome" -> "biome";
            case "difficulty" -> "difficulty";
            case "display_slot" -> "display_slot";
            case "enchantment" -> "enchantment";
            case "entity_type" -> "entity_type";
            case "gamemode" -> "gamemode";
            case "potion_effect" -> "potion_effect";
            case "sound" -> "sound";
            case "text_decoration" -> "text_decoration";
            default -> "string";
        });
    }

    private Class<?> catalogRuntimeClass(String key) {
        return switch (key) {
            case "material", "block" -> Material.class;
            case "biome" -> Biome.class;
            case "difficulty" -> Difficulty.class;
            case "display_slot" -> DisplaySlot.class;
            case "enchantment" -> Enchantment.class;
            case "entity_type" -> EntityType.class;
            case "gamemode" -> GameMode.class;
            case "potion_effect" -> PotionEffectType.class;
            case "sound" -> Sound.class;
            case "text_decoration" -> TextDecoration.class;
            default -> String.class;
        };
    }

    private Object resolveCatalogValue(String key, String value) {
        NamespacedKey namespacedKey = namespacedKey(value);
        return switch (key) {
            case "material", "block" -> Material.matchMaterial(value);
            case "biome" -> namespacedKey != null ? Registry.BIOME.get(namespacedKey) : null;
            case "difficulty" -> enumValue(Difficulty.class, value);
            case "display_slot" -> enumValue(DisplaySlot.class, value);
            case "enchantment" -> namespacedKey != null ? Registry.ENCHANTMENT.get(namespacedKey) : null;
            case "entity_type" -> namespacedKey != null ? Registry.ENTITY_TYPE.get(namespacedKey) : null;
            case "gamemode" -> enumValue(GameMode.class, value);
            case "potion_effect" -> namespacedKey != null ? Registry.MOB_EFFECT.get(namespacedKey) : null;
            case "sound" -> namespacedKey != null ? Registry.SOUNDS.get(namespacedKey) : null;
            case "text_decoration" -> enumValue(TextDecoration.class, value);
            default -> value;
        };
    }

    private NamespacedKey namespacedKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try {
            return value != null ? Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<String> advancements() {
        List<String> values = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining(advancement -> values.add(key(advancement)));
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private String key(Advancement advancement) {
        return advancement.getKey() != null ? advancement.getKey().toString() : "";
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

    private List<String> entityDataProperties() {
        List<String> values = new ArrayList<>(List.of(
            "type", "uuid", "custom_name", "custom_name_visible", "glowing", "silent", "invulnerable", "gravity", "visual_fire",
            "fire_ticks", "freeze_ticks", "ticks_lived", "fall_distance", "portal_cooldown", "velocity", "location", "health", "absorption",
            "ai", "collidable", "can_pickup_items", "persistent", "remove_when_far_away", "target", "age", "age_locked", "baby", "fuse_ticks",
            "yield", "incendiary", "explosion_radius", "max_fuse_ticks", "powered", "ignited"
        ));
        registryKeysByField("ATTRIBUTE").stream().map(value -> "attribute:" + value).forEach(values::add);
        return values.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> itemComponentProperties(String valueKind, boolean presence) {
        return itemAttributeSchemaService.catalog("").stream()
            .filter(item -> valueKind.equals(String.valueOf(item.metadata().get("valueKind"))))
            .filter(item -> presence == "presence".equals(String.valueOf(item.metadata().get("editor"))))
            .map(OptionCatalogItem::value)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
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
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Potion type catalog is unavailable", exception);
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
        } catch (NoSuchFieldException exception) {
            return List.of();
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Bukkit registry catalog is unavailable: " + fieldName, exception);
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

    private static CatalogDefinition catalog(String key, boolean searchable) {
        return new CatalogDefinition("server:minecraft:" + key, "minecraft", key, searchable);
    }

    private static CatalogDefinition customContentCatalog(String key, boolean searchable) {
        return new CatalogDefinition("server:custom_content:" + key, "custom_content", "custom_content_" + key, searchable);
    }

    private static CatalogDefinition resourceCatalog(String key, boolean searchable) {
        return new CatalogDefinition("server:resync:" + key, "resync", key, searchable);
    }

    private record CatalogDefinition(String sourceId, String providerId, String key, boolean searchable) {
    }
}
