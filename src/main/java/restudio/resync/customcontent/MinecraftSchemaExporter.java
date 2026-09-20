package restudio.resync.customcontent;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import restudio.resync.advancement.PaperUnsafe;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.metadata.CatalogId;
import restudio.resync.metadata.MinecraftSchemaBundle;
import restudio.resync.metadata.MinecraftSchemaBundle.Applicability;
import restudio.resync.metadata.MinecraftSchemaBundle.Capability;
import restudio.resync.metadata.MinecraftSchemaBundle.EnumValue;
import restudio.resync.metadata.MinecraftSchemaBundle.Evidence;
import restudio.resync.metadata.MinecraftSchemaBundle.Field;
import restudio.resync.metadata.MinecraftSchemaBundle.ListValue;
import restudio.resync.metadata.MinecraftSchemaBundle.MapValue;
import restudio.resync.metadata.MinecraftSchemaBundle.OpaqueValue;
import restudio.resync.metadata.MinecraftSchemaBundle.PresenceValue;
import restudio.resync.metadata.MinecraftSchemaBundle.Presentation;
import restudio.resync.metadata.MinecraftSchemaBundle.PrimitiveValue;
import restudio.resync.metadata.MinecraftSchemaBundle.RecordValue;
import restudio.resync.metadata.MinecraftSchemaBundle.RegistryReferenceValue;
import restudio.resync.metadata.MinecraftSchemaBundle.ResourceLocationValue;
import restudio.resync.metadata.MinecraftSchemaBundle.RuntimeIdentity;
import restudio.resync.metadata.MinecraftSchemaBundle.Schema;
import restudio.resync.metadata.MinecraftSchemaBundle.SchemaKey;
import restudio.resync.metadata.MinecraftSchemaBundle.UnionValue;
import restudio.resync.metadata.MinecraftSchemaBundle.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MinecraftSchemaExporter {
    private static final CatalogId ATTRIBUTE_CATALOG = CatalogId.of("minecraft", "attribute");
    private static final List<JsonValue> ATTRIBUTE_OPERATIONS = strings("add_value", "add_multiplied_base", "add_multiplied_total");
    private static final List<JsonValue> EQUIPMENT_SLOTS = strings("any", "hand", "armor", "mainhand", "offhand", "head", "chest", "legs", "feet", "body");
    private static final List<JsonValue> DYE_COLORS = strings("white", "orange", "magenta", "light_blue", "yellow",
        "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private final ItemAttributeSchemaService components;

    public MinecraftSchemaExporter(ItemAttributeSchemaService components) {
        this.components = Objects.requireNonNull(components, "Item component schema service is required");
    }

    public MinecraftSchemaBundle export(String minecraftVersion, String createdAt) {
        RuntimeIdentity runtime = new RuntimeIdentity("paper", Bukkit.getMinecraftVersion(), Bukkit.getVersion(),
            Bukkit.getBukkitVersion());
        List<String> attributes = new ArrayList<>();
        for (Attribute attribute : Registry.ATTRIBUTE) {
            NamespacedKey key = Registry.ATTRIBUTE.getKey(attribute);
            if (key != null) attributes.add(key.toString());
        }
        return export(minecraftVersion, createdAt, runtime, attributes, PaperUnsafe.itemJsonRoundTripSupported());
    }

    MinecraftSchemaBundle export(String minecraftVersion, String createdAt, RuntimeIdentity runtime,
                                 List<String> attributeIds, boolean roundTripSupported) {
        if (!minecraftVersion.equals(runtime.softwareVersion())) {
            throw new IllegalArgumentException("Requested Minecraft version does not match the extraction runtime");
        }
        List<Schema> schemas = new ArrayList<>();
        List<OptionCatalogItem> componentItems = components.catalog("");
        for (OptionCatalogItem item : componentItems) schemas.add(component(item, runtime));
        for (String attribute : attributeIds) schemas.add(attribute(attribute, runtime));
        List<Capability> capabilities = List.of(
            new Capability("attributes", !attributeIds.isEmpty(), attributeIds.isEmpty() ? "Runtime attribute registry is empty" : null),
            new Capability("data_components", !componentItems.isEmpty(), componentItems.isEmpty() ? "Runtime data-component registry is empty" : null),
            new Capability("item_json_round_trip", roundTripSupported,
                roundTripSupported ? null : "Paper item JSON round trip is unavailable"));
        return new MinecraftSchemaBundle(minecraftVersion, createdAt, runtime, capabilities, schemas);
    }

    private Schema component(OptionCatalogItem item, RuntimeIdentity runtime) {
        Map<String, Object> metadata = item.metadata();
        Object sample = metadata.containsKey("defaultValue") ? metadata.get("defaultValue") : metadata.get("exampleValue");
        String editor = text(metadata.get("editor"), "schema");
        Value inferred = infer(metadata.get("schema"), sample);
        Value value = semantic(item.value(), editor, inferred);
        List<String> targets = texts(metadata.get("appliesTo"));
        boolean universal = targets.remove("any");
        List<Evidence> evidence = new ArrayList<>();
        String source = runtime.softwareFamily() + ":" + runtime.softwareVersion();
        if (bool(metadata.get("runtime"))) evidence.add(new Evidence("runtime_registry", source, null));
        if (bool(metadata.get("writable"))) evidence.add(new Evidence("paper_round_trip", source, null));
        if (bool(metadata.get("default")) || metadata.containsKey("exampleValue")) {
            evidence.add(new Evidence("observed_default", source, null));
        }
        evidence.add(new Evidence("sample_inference", "resync:item_component_schema", null));
        evidence.add(new Evidence("curated_presentation", "resync:item_attribute_ui_schema", null));
        JsonValue defaultValue = metadata.containsKey("defaultValue") ? JsonValue.fromJava(metadata.get("defaultValue")) : null;
        List<JsonValue> examples = metadata.containsKey("exampleValue")
            ? List.of(JsonValue.fromJava(metadata.get("exampleValue"))) : List.of();
        return new Schema(new SchemaKey("item_component", CatalogId.parseCanonicalText(item.value())), item.label(),
            item.description().isBlank() ? "Minecraft item data component." : item.description(), value,
            new Applicability(universal, List.of(), targets),
            new Presentation(item.group().isBlank() ? "Other" : item.group(), normalizeEditor(editor),
                integer(metadata.get("priority"), 900), texts(metadata.get("search"))), evidence, defaultValue, examples, false);
    }

    private Schema attribute(String id, RuntimeIdentity runtime) {
        CatalogId attribute = CatalogId.parseCanonicalText(id);
        String label = label(attribute.path());
        String source = runtime.softwareFamily() + ":" + runtime.softwareVersion();
        return new Schema(new SchemaKey("attribute", attribute), label, "Numeric value for " + label + ".",
            PrimitiveValue.decimal(), Applicability.any(),
            new Presentation("Attributes", "number", 500, search(attribute.path())),
            List.of(new Evidence("runtime_registry", source, null)), null, List.of(), false);
    }

    private Value semantic(String id, String editor, Value inferred) {
        if ("presence".equals(editor)) return new PresenceValue();
        return switch (id) {
            case "minecraft:attribute_modifiers" -> new ListValue(attributeModifier(), null, null, false);
            case "minecraft:rarity" -> new EnumValue(strings("common", "uncommon", "rare", "epic"));
            case "minecraft:instrument" -> registry("instrument");
            case "minecraft:damage_type" -> registry("damage_type");
            case "minecraft:item_model" -> registry("item_model");
            case "minecraft:provides_trim_material" -> registry("trim_material");
            case "minecraft:provides_banner_patterns" -> registry("banner_pattern");
            case "minecraft:dye", "minecraft:base_color" -> new EnumValue(DYE_COLORS);
            case "minecraft:enchantments", "minecraft:stored_enchantments" ->
                new MapValue(PrimitiveValue.integer(), CatalogId.of("minecraft", "enchantment"), null, null);
            case "minecraft:trim" -> new RecordValue(List.of(
                field("material", "Trim material.", registry("trim_material"), true),
                field("pattern", "Trim pattern.", registry("trim_pattern"), true)), false);
            default -> inferred;
        };
    }

    private RecordValue attributeModifier() {
        return new RecordValue(List.of(
            field("type", "Attribute to modify.", new RegistryReferenceValue(ATTRIBUTE_CATALOG, false), true),
            field("amount", "Modifier amount.", PrimitiveValue.decimal(), true),
            field("operation", "Modifier operation.", new EnumValue(ATTRIBUTE_OPERATIONS), true),
            field("slot", "Equipment slot or slot group.", new EnumValue(EQUIPMENT_SLOTS), true),
            field("id", "Stable modifier identity.", new ResourceLocationValue(), true)), false);
    }

    private Value infer(Object raw, Object sample) {
        if (!(raw instanceof Map<?, ?> schema)) return new OpaqueValue("Runtime sample did not expose a structured schema");
        String kind = text(schema.get("kind"), "raw");
        return switch (kind) {
            case "boolean" -> PrimitiveValue.booleanValue();
            case "number" -> integerSample(sample) ? PrimitiveValue.integer() : PrimitiveValue.decimal();
            case "string" -> PrimitiveValue.string();
            case "object" -> record(schema.get("fields"), sample);
            case "array" -> list(schema.get("items"), sample);
            default -> new OpaqueValue("Runtime sample uses an unsupported value shape");
        };
    }

    private Value record(Object rawFields, Object sample) {
        if (!(rawFields instanceof Map<?, ?> fields) || fields.isEmpty()) {
            return new RecordValue(List.of(), true);
        }
        Map<?, ?> samples = sample instanceof Map<?, ?> map ? map : Map.of();
        List<Field> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            if (entry.getKey() == null) continue;
            String name = entry.getKey().toString();
            result.add(field(name, label(name) + ".", infer(entry.getValue(), samples.get(name)), false));
        }
        return new RecordValue(result, true);
    }

    private Value list(Object rawItems, Object sample) {
        Object first = sample instanceof List<?> values && !values.isEmpty() ? values.getFirst() : null;
        Value items = rawItems != null ? infer(rawItems, first) : new OpaqueValue("Runtime sample list is empty");
        return new ListValue(items, null, null, false);
    }

    private static Field field(String name, String description, Value value, boolean required) {
        return new Field(name, description, value, required, null);
    }

    private static RegistryReferenceValue registry(String path) {
        return new RegistryReferenceValue(CatalogId.of("minecraft", path), false);
    }

    private static boolean integerSample(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
    }

    private static String normalizeEditor(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace(' ', '_');
        return normalized.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*") ? normalized : "schema";
    }

    private static String text(Object value, String fallback) {
        return value instanceof String string && !string.isBlank() ? string : fallback;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static List<String> texts(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return new ArrayList<>();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object entry : iterable) {
            if (entry != null && !entry.toString().isBlank()) values.add(entry.toString());
        }
        return new ArrayList<>(values);
    }

    private static List<String> search(String value) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(value);
        for (String term : value.split("[/_.-]+")) {
            if (!term.isBlank()) terms.add(term);
        }
        return List.copyOf(terms);
    }

    private static String label(String value) {
        StringBuilder result = new StringBuilder();
        for (String part : value.replace('/', '_').replace('.', '_').split("_+")) {
            if (part.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.isEmpty() ? value : result.toString();
    }

    private static List<JsonValue> strings(String... values) {
        List<JsonValue> result = new ArrayList<>(values.length);
        for (String value : values) result.add(JsonValue.of(value));
        return List.copyOf(result);
    }
}
