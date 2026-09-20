package restudio.resync.metadata;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonBoolean;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.metadata.MinecraftSchemaBundle.Applicability;
import restudio.resync.metadata.MinecraftSchemaBundle.Capability;
import restudio.resync.metadata.MinecraftSchemaBundle.EnumValue;
import restudio.resync.metadata.MinecraftSchemaBundle.Evidence;
import restudio.resync.metadata.MinecraftSchemaBundle.Field;
import restudio.resync.metadata.MinecraftSchemaBundle.ListValue;
import restudio.resync.metadata.MinecraftSchemaBundle.LiteralValue;
import restudio.resync.metadata.MinecraftSchemaBundle.MapValue;
import restudio.resync.metadata.MinecraftSchemaBundle.OpaqueValue;
import restudio.resync.metadata.MinecraftSchemaBundle.PresenceValue;
import restudio.resync.metadata.MinecraftSchemaBundle.Presentation;
import restudio.resync.metadata.MinecraftSchemaBundle.PrimitiveKind;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MinecraftSchemaBundleCodec implements CanonicalCodec<MinecraftSchemaBundle> {
    private static final Set<String> BUNDLE_FIELDS = Set.of("artifactFamily", "capabilities", "createdAt",
        "formatVersion", "minecraftVersion", "runtime", "schemas");
    private static final Set<String> RUNTIME_FIELDS = Set.of("apiVersion", "build", "softwareFamily", "softwareVersion");
    private static final Set<String> CAPABILITY_FIELDS = Set.of("available", "diagnostic", "id");
    private static final Set<String> SCHEMA_FIELDS = Set.of("applicability", "complete", "defaultValue", "description",
        "domain", "evidence", "examples", "id", "label", "presentation", "value");
    private static final Set<String> APPLICABILITY_FIELDS = Set.of("categories", "universal", "values");
    private static final Set<String> PRESENTATION_FIELDS = Set.of("category", "editor", "priority", "search");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("detail", "kind", "source");
    private static final Set<String> FIELD_FIELDS = Set.of("defaultValue", "description", "name", "required", "value");

    @Override
    public JsonValue encode(MinecraftSchemaBundle bundle) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("artifactFamily", JsonValue.of(MinecraftSchemaBundle.ARTIFACT_FAMILY.canonicalText()));
        fields.put("capabilities", array(bundle.capabilities().stream().map(this::capability).toList()));
        fields.put("createdAt", JsonValue.of(bundle.createdAt()));
        fields.put("formatVersion", JsonValue.of(bundle.formatVersion()));
        fields.put("minecraftVersion", JsonValue.of(bundle.minecraftVersion()));
        fields.put("runtime", runtime(bundle.runtime()));
        fields.put("schemas", array(bundle.schemas().stream().map(this::schema).toList()));
        return JsonValue.object(fields);
    }

    @Override
    public MinecraftSchemaBundle decode(JsonValue value) {
        JsonObject object = object(value, "Minecraft schema bundle");
        CanonicalCodec.rejectUnknownFields(object, BUNDLE_FIELDS);
        MetadataArtifactFamily family = MetadataArtifactFamily.parseCanonicalText(text(required(object, "artifactFamily"),
            "Minecraft schema artifact family"));
        if (!MinecraftSchemaBundle.ARTIFACT_FAMILY.equals(family)) {
            throw new IllegalArgumentException("Minecraft schema bundle has the wrong artifact family");
        }
        int formatVersion = integer(required(object, "formatVersion"), "Minecraft schema bundle format version");
        String minecraftVersion = text(required(object, "minecraftVersion"), "Minecraft version");
        String createdAt = text(required(object, "createdAt"), "Minecraft schema bundle creation time");
        RuntimeIdentity runtime = decodeRuntime(required(object, "runtime"));
        List<Capability> capabilities = decodeList(required(object, "capabilities"), "Minecraft schema capabilities",
            this::decodeCapability);
        List<Schema> schemas = decodeList(required(object, "schemas"), "Minecraft schemas", this::decodeSchema);
        MinecraftSchemaBundle bundle = new MinecraftSchemaBundle(formatVersion, minecraftVersion, createdAt, runtime,
            capabilities, schemas);
        requireOrder(capabilities, bundle.capabilities(), "Minecraft schema capabilities");
        requireOrder(schemas, bundle.schemas(), "Minecraft schemas");
        return bundle;
    }

    @Override
    public byte[] encodeBytes(MinecraftSchemaBundle bundle) {
        return encode(bundle).canonicalBytes(CanonicalLimits.catalog());
    }

    @Override
    public String encodeText(MinecraftSchemaBundle bundle) {
        return encode(bundle).canonicalText(CanonicalLimits.catalog());
    }

    @Override
    public MinecraftSchemaBundle decodeBytes(byte[] input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    @Override
    public MinecraftSchemaBundle decodeText(String input) {
        return decode(CanonicalCodec.decode(input, CanonicalLimits.catalog()));
    }

    private JsonValue runtime(RuntimeIdentity runtime) {
        return JsonValue.object(Map.of(
            "apiVersion", JsonValue.of(runtime.apiVersion()),
            "build", JsonValue.of(runtime.build()),
            "softwareFamily", JsonValue.of(runtime.softwareFamily()),
            "softwareVersion", JsonValue.of(runtime.softwareVersion())
        ));
    }

    private RuntimeIdentity decodeRuntime(JsonValue value) {
        JsonObject object = object(value, "Minecraft schema extraction runtime");
        CanonicalCodec.rejectUnknownFields(object, RUNTIME_FIELDS);
        return new RuntimeIdentity(text(required(object, "softwareFamily"), "Schema extraction software family"),
            text(required(object, "softwareVersion"), "Schema extraction software version"),
            text(required(object, "build"), "Schema extraction build"),
            text(required(object, "apiVersion"), "Schema extraction API version"));
    }

    private JsonValue capability(Capability capability) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("available", JsonValue.of(capability.available()));
        optional(fields, "diagnostic", capability.diagnostic());
        fields.put("id", JsonValue.of(capability.id()));
        return JsonValue.object(fields);
    }

    private Capability decodeCapability(JsonValue value) {
        JsonObject object = object(value, "Minecraft schema capability");
        CanonicalCodec.rejectUnknownFields(object, CAPABILITY_FIELDS);
        return new Capability(text(required(object, "id"), "Minecraft schema capability ID"),
            bool(required(object, "available"), "Minecraft schema capability availability"),
            optionalText(object, "diagnostic"));
    }

    private JsonValue schema(Schema schema) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("applicability", applicability(schema.applicability()));
        fields.put("complete", JsonValue.of(schema.complete()));
        optional(fields, "defaultValue", schema.defaultValue());
        fields.put("description", JsonValue.of(schema.description()));
        fields.put("domain", JsonValue.of(schema.key().domain()));
        fields.put("evidence", array(schema.evidence().stream().map(this::evidence).toList()));
        fields.put("examples", array(schema.examples()));
        fields.put("id", JsonValue.of(schema.key().id().canonicalText()));
        fields.put("label", JsonValue.of(schema.label()));
        fields.put("presentation", presentation(schema.presentation()));
        fields.put("value", schemaValue(schema.value()));
        return JsonValue.object(fields);
    }

    private Schema decodeSchema(JsonValue value) {
        JsonObject object = object(value, "Minecraft schema");
        CanonicalCodec.rejectUnknownFields(object, SCHEMA_FIELDS);
        return new Schema(new SchemaKey(text(required(object, "domain"), "Minecraft schema domain"),
            CatalogId.parseCanonicalText(text(required(object, "id"), "Minecraft schema ID"))),
            text(required(object, "label"), "Minecraft schema label"),
            text(required(object, "description"), "Minecraft schema description"),
            decodeSchemaValue(required(object, "value")), decodeApplicability(required(object, "applicability")),
            decodePresentation(required(object, "presentation")),
            decodeList(required(object, "evidence"), "Minecraft schema evidence", this::decodeEvidence),
            optional(object, "defaultValue"), jsonList(required(object, "examples"), "Minecraft schema examples"),
            bool(required(object, "complete"), "Minecraft schema completeness"));
    }

    private JsonValue applicability(Applicability applicability) {
        return JsonValue.object(Map.of(
            "categories", strings(applicability.categories()),
            "universal", JsonValue.of(applicability.universal()),
            "values", strings(applicability.values())
        ));
    }

    private Applicability decodeApplicability(JsonValue value) {
        JsonObject object = object(value, "Minecraft schema applicability");
        CanonicalCodec.rejectUnknownFields(object, APPLICABILITY_FIELDS);
        return new Applicability(bool(required(object, "universal"), "Minecraft schema universal applicability"),
            textList(required(object, "values"), "Minecraft schema applicable values"),
            textList(required(object, "categories"), "Minecraft schema applicability categories"));
    }

    private JsonValue presentation(Presentation presentation) {
        return JsonValue.object(Map.of(
            "category", JsonValue.of(presentation.category()),
            "editor", JsonValue.of(presentation.editor()),
            "priority", JsonValue.of(presentation.priority()),
            "search", strings(presentation.search())
        ));
    }

    private Presentation decodePresentation(JsonValue value) {
        JsonObject object = object(value, "Minecraft schema presentation");
        CanonicalCodec.rejectUnknownFields(object, PRESENTATION_FIELDS);
        return new Presentation(text(required(object, "category"), "Minecraft schema presentation category"),
            text(required(object, "editor"), "Minecraft schema editor"),
            integer(required(object, "priority"), "Minecraft schema presentation priority"),
            textList(required(object, "search"), "Minecraft schema search terms"));
    }

    private JsonValue evidence(Evidence evidence) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        optional(fields, "detail", evidence.detail());
        fields.put("kind", JsonValue.of(evidence.kind()));
        fields.put("source", JsonValue.of(evidence.source()));
        return JsonValue.object(fields);
    }

    private Evidence decodeEvidence(JsonValue value) {
        JsonObject object = object(value, "Minecraft schema evidence");
        CanonicalCodec.rejectUnknownFields(object, EVIDENCE_FIELDS);
        return new Evidence(text(required(object, "kind"), "Minecraft schema evidence kind"),
            text(required(object, "source"), "Minecraft schema evidence source"), optionalText(object, "detail"));
    }

    private JsonValue schemaValue(Value value) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        if (value instanceof PrimitiveValue primitive) {
            fields.put("primitive", JsonValue.of(primitive.kind().id()));
            optional(fields, "minimum", primitive.minimum());
            optional(fields, "maximum", primitive.maximum());
            optional(fields, "minimumLength", primitive.minimumLength());
            optional(fields, "maximumLength", primitive.maximumLength());
            fields.put("type", JsonValue.of("primitive"));
        } else if (value instanceof LiteralValue literal) {
            fields.put("type", JsonValue.of("literal"));
            fields.put("value", literal.value());
        } else if (value instanceof EnumValue enumeration) {
            fields.put("type", JsonValue.of("enum"));
            fields.put("values", array(enumeration.values()));
        } else if (value instanceof RegistryReferenceValue reference) {
            fields.put("catalog", JsonValue.of(reference.catalog().canonicalText()));
            fields.put("tagsAllowed", JsonValue.of(reference.tagsAllowed()));
            fields.put("type", JsonValue.of("registry_ref"));
        } else if (value instanceof ResourceLocationValue) {
            fields.put("type", JsonValue.of("resource_location"));
        } else if (value instanceof RecordValue record) {
            fields.put("additionalFields", JsonValue.of(record.additionalFields()));
            fields.put("fields", array(record.fields().stream().map(this::field).toList()));
            fields.put("type", JsonValue.of("record"));
        } else if (value instanceof ListValue list) {
            fields.put("items", schemaValue(list.items()));
            optional(fields, "maximumItems", list.maximumItems());
            optional(fields, "minimumItems", list.minimumItems());
            fields.put("type", JsonValue.of("list"));
            fields.put("uniqueItems", JsonValue.of(list.uniqueItems()));
        } else if (value instanceof MapValue map) {
            optional(fields, "keyCatalog", map.keyCatalog() != null ? map.keyCatalog().canonicalText() : null);
            optional(fields, "maximumEntries", map.maximumEntries());
            optional(fields, "minimumEntries", map.minimumEntries());
            fields.put("type", JsonValue.of("map"));
            fields.put("values", schemaValue(map.values()));
        } else if (value instanceof UnionValue union) {
            fields.put("alternatives", array(union.alternatives().stream().map(this::schemaValue).toList()));
            fields.put("type", JsonValue.of("union"));
        } else if (value instanceof PresenceValue) {
            fields.put("type", JsonValue.of("presence"));
        } else if (value instanceof OpaqueValue opaque) {
            fields.put("reason", JsonValue.of(opaque.reason()));
            fields.put("type", JsonValue.of("opaque"));
        } else {
            throw new IllegalArgumentException("Unsupported Minecraft schema value");
        }
        return JsonValue.object(fields);
    }

    private Value decodeSchemaValue(JsonValue value) {
        JsonObject object = object(value, "Minecraft schema value");
        String type = text(required(object, "type"), "Minecraft schema value type");
        return switch (type) {
            case "primitive" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("maximum", "maximumLength", "minimum",
                    "minimumLength", "primitive", "type"));
                yield new PrimitiveValue(PrimitiveKind.parse(text(required(object, "primitive"),
                    "Minecraft schema primitive kind")), optionalDecimal(object, "minimum"),
                    optionalDecimal(object, "maximum"), optionalInteger(object, "minimumLength"),
                    optionalInteger(object, "maximumLength"));
            }
            case "literal" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("type", "value"));
                yield new LiteralValue(required(object, "value"));
            }
            case "enum" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("type", "values"));
                yield new EnumValue(jsonList(required(object, "values"), "Minecraft schema enum values"));
            }
            case "registry_ref" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("catalog", "tagsAllowed", "type"));
                yield new RegistryReferenceValue(CatalogId.parseCanonicalText(text(required(object, "catalog"),
                    "Minecraft schema registry catalog")), bool(required(object, "tagsAllowed"),
                    "Minecraft schema registry tag allowance"));
            }
            case "resource_location" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("type"));
                yield new ResourceLocationValue();
            }
            case "record" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("additionalFields", "fields", "type"));
                yield new RecordValue(decodeList(required(object, "fields"), "Minecraft schema record fields",
                    this::decodeField), bool(required(object, "additionalFields"),
                    "Minecraft schema additional-field allowance"));
            }
            case "list" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("items", "maximumItems", "minimumItems", "type",
                    "uniqueItems"));
                yield new ListValue(decodeSchemaValue(required(object, "items")), optionalInteger(object, "minimumItems"),
                    optionalInteger(object, "maximumItems"), bool(required(object, "uniqueItems"),
                    "Minecraft schema unique-item constraint"));
            }
            case "map" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("keyCatalog", "maximumEntries", "minimumEntries",
                    "type", "values"));
                String catalog = optionalText(object, "keyCatalog");
                yield new MapValue(decodeSchemaValue(required(object, "values")),
                    catalog != null ? CatalogId.parseCanonicalText(catalog) : null,
                    optionalInteger(object, "minimumEntries"), optionalInteger(object, "maximumEntries"));
            }
            case "union" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("alternatives", "type"));
                yield new UnionValue(decodeList(required(object, "alternatives"),
                    "Minecraft schema union alternatives", this::decodeSchemaValue));
            }
            case "presence" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("type"));
                yield new PresenceValue();
            }
            case "opaque" -> {
                CanonicalCodec.rejectUnknownFields(object, Set.of("reason", "type"));
                yield new OpaqueValue(text(required(object, "reason"), "Minecraft opaque schema reason"));
            }
            default -> throw new IllegalArgumentException("Unknown Minecraft schema value type: " + type);
        };
    }

    private JsonValue field(Field field) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        optional(fields, "defaultValue", field.defaultValue());
        fields.put("description", JsonValue.of(field.description()));
        fields.put("name", JsonValue.of(field.name()));
        fields.put("required", JsonValue.of(field.required()));
        fields.put("value", schemaValue(field.value()));
        return JsonValue.object(fields);
    }

    private Field decodeField(JsonValue value) {
        JsonObject object = object(value, "Minecraft schema field");
        CanonicalCodec.rejectUnknownFields(object, FIELD_FIELDS);
        return new Field(text(required(object, "name"), "Minecraft schema field name"),
            text(required(object, "description"), "Minecraft schema field description"),
            decodeSchemaValue(required(object, "value")), bool(required(object, "required"),
            "Minecraft schema field requirement"), optional(object, "defaultValue"));
    }

    private static JsonObject object(JsonValue value, String field) {
        if (!(value instanceof JsonObject object)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return object;
    }

    private static JsonArray array(JsonValue value, String field) {
        if (!(value instanceof JsonArray array)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return array;
    }

    private static JsonArray array(List<? extends JsonValue> values) {
        return JsonValue.array(values);
    }

    private static JsonValue required(JsonObject object, String field) {
        return CanonicalCodec.requireField(object, field);
    }

    private static JsonValue optional(JsonObject object, String field) {
        return object.value(field);
    }

    private static String text(JsonValue value, String field) {
        if (!(value instanceof JsonString string)) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return string.value();
    }

    private static String optionalText(JsonObject object, String field) {
        JsonValue value = optional(object, field);
        return value == null ? null : text(value, field);
    }

    private static boolean bool(JsonValue value, String field) {
        if (!(value instanceof JsonBoolean bool)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return bool.value();
    }

    private static int integer(JsonValue value, String field) {
        BigDecimal decimal = decimal(value, field);
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an exactly representable integer", exception);
        }
    }

    private static BigDecimal decimal(JsonValue value, String field) {
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return number.value();
    }

    private static BigDecimal optionalDecimal(JsonObject object, String field) {
        JsonValue value = optional(object, field);
        return value == null ? null : decimal(value, field);
    }

    private static Integer optionalInteger(JsonObject object, String field) {
        JsonValue value = optional(object, field);
        return value == null ? null : integer(value, field);
    }

    private static List<String> textList(JsonValue value, String field) {
        return array(value, field).values().stream().map(item -> text(item, field + " value")).toList();
    }

    private static List<JsonValue> jsonList(JsonValue value, String field) {
        return List.copyOf(array(value, field).values());
    }

    private static JsonValue strings(List<String> values) {
        return array(values.stream().map(JsonValue::of).toList());
    }

    private static void optional(Map<String, JsonValue> fields, String name, String value) {
        if (value != null) fields.put(name, JsonValue.of(value));
    }

    private static void optional(Map<String, JsonValue> fields, String name, JsonValue value) {
        if (value != null) fields.put(name, value);
    }

    private static void optional(Map<String, JsonValue> fields, String name, BigDecimal value) {
        if (value != null) fields.put(name, JsonValue.of(value));
    }

    private static void optional(Map<String, JsonValue> fields, String name, Integer value) {
        if (value != null) fields.put(name, JsonValue.of(value));
    }

    private static <T> List<T> decodeList(JsonValue value, String field, Decoder<T> decoder) {
        JsonArray array = array(value, field);
        List<T> result = new ArrayList<>(array.values().size());
        for (JsonValue item : array.values()) result.add(decoder.decode(item));
        return result;
    }

    private static void requireOrder(List<?> decoded, List<?> canonical, String field) {
        if (!decoded.equals(canonical)) {
            throw new IllegalArgumentException(field + " are not in canonical order");
        }
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(JsonValue value);
    }
}
