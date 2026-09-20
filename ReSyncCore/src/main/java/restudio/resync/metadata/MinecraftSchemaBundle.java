package restudio.resync.metadata;

import restudio.resync.contract.canonical.JsonValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

public record MinecraftSchemaBundle(int formatVersion, String minecraftVersion, String createdAt,
                                    RuntimeIdentity runtime, List<Capability> capabilities, List<Schema> schemas) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final MetadataArtifactFamily ARTIFACT_FAMILY = MetadataArtifactFamily.of("minecraft_schema");
    public static final int MAX_SCHEMAS = 16_384;
    public static final int MAX_SCHEMA_NODES = 262_144;
    public static final int MAX_SCHEMA_DEPTH = 32;

    private static final Pattern OPAQUE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:+-]{0,191}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");

    public MinecraftSchemaBundle {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported Minecraft schema bundle format version: " + formatVersion);
        }
        minecraftVersion = opaqueVersion(minecraftVersion, "Minecraft version");
        createdAt = MetadataValidation.text(createdAt, "Minecraft schema bundle creation time", 128);
        runtime = Objects.requireNonNull(runtime, "Minecraft schema extraction runtime is required");
        capabilities = capabilities(capabilities);
        schemas = schemas(schemas);
        validateGraph(schemas);
    }

    public MinecraftSchemaBundle(String minecraftVersion, String createdAt, RuntimeIdentity runtime,
                                 List<Capability> capabilities, List<Schema> schemas) {
        this(CURRENT_FORMAT_VERSION, minecraftVersion, createdAt, runtime, capabilities, schemas);
    }

    public record RuntimeIdentity(String softwareFamily, String softwareVersion, String build, String apiVersion) {
        public RuntimeIdentity {
            softwareFamily = MetadataValidation.id(softwareFamily, "Schema extraction software family");
            softwareVersion = opaqueVersion(softwareVersion, "Schema extraction software version");
            build = MetadataValidation.text(build, "Schema extraction build", 512);
            apiVersion = MetadataValidation.text(apiVersion, "Schema extraction API version", 256);
        }
    }

    public record Capability(String id, boolean available, String diagnostic) implements Comparable<Capability> {
        public Capability {
            id = MetadataValidation.id(id, "Minecraft schema capability ID");
            diagnostic = MetadataValidation.optionalText(diagnostic, "Minecraft schema capability diagnostic", 2_048);
            if (!available && diagnostic == null) {
                throw new IllegalArgumentException("Unavailable Minecraft schema capability requires a diagnostic");
            }
        }

        @Override
        public int compareTo(Capability other) {
            return id.compareTo(Objects.requireNonNull(other, "Minecraft schema capability is required").id);
        }
    }

    public record SchemaKey(String domain, CatalogId id) implements Comparable<SchemaKey> {
        public SchemaKey {
            domain = MetadataValidation.id(domain, "Minecraft schema domain");
            id = Objects.requireNonNull(id, "Minecraft schema ID is required");
        }

        @Override
        public int compareTo(SchemaKey other) {
            int domainOrder = domain.compareTo(Objects.requireNonNull(other, "Minecraft schema key is required").domain);
            return domainOrder != 0 ? domainOrder : id.compareTo(other.id);
        }

        public String canonicalText() {
            return domain + "/" + id.canonicalText();
        }
    }

    public record Schema(SchemaKey key, String label, String description, Value value, Applicability applicability,
                         Presentation presentation, List<Evidence> evidence, JsonValue defaultValue,
                         List<JsonValue> examples, boolean complete) implements Comparable<Schema> {
        public Schema {
            key = Objects.requireNonNull(key, "Minecraft schema key is required");
            label = MetadataValidation.text(label, "Minecraft schema label", 256);
            description = MetadataValidation.text(description, "Minecraft schema description", 2_048);
            value = Objects.requireNonNull(value, "Minecraft schema value is required");
            applicability = Objects.requireNonNull(applicability, "Minecraft schema applicability is required");
            presentation = Objects.requireNonNull(presentation, "Minecraft schema presentation is required");
            evidence = freezeEvidence(evidence);
            examples = jsonValues(examples, "Minecraft schema examples", 64);
        }

        @Override
        public int compareTo(Schema other) {
            return key.compareTo(Objects.requireNonNull(other, "Minecraft schema is required").key);
        }
    }

    public record Applicability(boolean universal, List<String> values, List<String> categories) {
        public Applicability {
            values = sortedText(values, "Minecraft schema applicable value", 1_024, 65_536);
            categories = sortedIds(categories, "Minecraft schema applicability category", 256);
            if (universal && (!values.isEmpty() || !categories.isEmpty())) {
                throw new IllegalArgumentException("Universal Minecraft schema applicability cannot declare filters");
            }
            if (!universal && values.isEmpty() && categories.isEmpty()) {
                throw new IllegalArgumentException("Minecraft schema applicability must declare a target");
            }
        }

        public static Applicability any() {
            return new Applicability(true, List.of(), List.of());
        }
    }

    public record Presentation(String category, String editor, int priority, List<String> search) {
        public Presentation {
            category = MetadataValidation.text(category, "Minecraft schema presentation category", 256);
            editor = MetadataValidation.id(editor, "Minecraft schema editor");
            if (priority < 0 || priority > 1_000_000) {
                throw new IllegalArgumentException("Minecraft schema presentation priority is outside the supported range");
            }
            search = sortedText(search, "Minecraft schema search term", 256, 256);
        }
    }

    public record Evidence(String kind, String source, String detail) implements Comparable<Evidence> {
        public Evidence {
            kind = MetadataValidation.id(kind, "Minecraft schema evidence kind");
            source = MetadataValidation.text(source, "Minecraft schema evidence source", 512);
            detail = MetadataValidation.optionalText(detail, "Minecraft schema evidence detail", 2_048);
        }

        @Override
        public int compareTo(Evidence other) {
            int kindOrder = kind.compareTo(Objects.requireNonNull(other, "Minecraft schema evidence is required").kind);
            if (kindOrder != 0) return kindOrder;
            int sourceOrder = source.compareTo(other.source);
            if (sourceOrder != 0) return sourceOrder;
            return Comparator.nullsFirst(String::compareTo).compare(detail, other.detail);
        }
    }

    public sealed interface Value permits PrimitiveValue, LiteralValue, EnumValue, RegistryReferenceValue,
        ResourceLocationValue, RecordValue, ListValue, MapValue, UnionValue, PresenceValue, OpaqueValue {
    }

    public enum PrimitiveKind {
        BOOLEAN("boolean"), INTEGER("integer"), DECIMAL("decimal"), STRING("string");

        private final String id;

        PrimitiveKind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static PrimitiveKind parse(String value) {
            for (PrimitiveKind kind : values()) {
                if (kind.id.equals(value)) return kind;
            }
            throw new IllegalArgumentException("Unknown Minecraft schema primitive kind: " + value);
        }
    }

    public record PrimitiveValue(PrimitiveKind kind, BigDecimal minimum, BigDecimal maximum,
                                 Integer minimumLength, Integer maximumLength) implements Value {
        public PrimitiveValue {
            kind = Objects.requireNonNull(kind, "Minecraft schema primitive kind is required");
            minimum = normalizeDecimal(minimum);
            maximum = normalizeDecimal(maximum);
            if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("Minecraft schema primitive minimum exceeds maximum");
            }
            minimumLength = nonNegative(minimumLength, "Minecraft schema minimum length");
            maximumLength = nonNegative(maximumLength, "Minecraft schema maximum length");
            if (minimumLength != null && maximumLength != null && minimumLength > maximumLength) {
                throw new IllegalArgumentException("Minecraft schema minimum length exceeds maximum length");
            }
            boolean string = kind == PrimitiveKind.STRING;
            if (!string && (minimumLength != null || maximumLength != null)) {
                throw new IllegalArgumentException("Only string schemas may declare length constraints");
            }
            if (string && (minimum != null || maximum != null)) {
                throw new IllegalArgumentException("String schemas cannot declare numeric constraints");
            }
            if (kind == PrimitiveKind.BOOLEAN && (minimum != null || maximum != null)) {
                throw new IllegalArgumentException("Boolean schemas cannot declare numeric constraints");
            }
            if (kind == PrimitiveKind.INTEGER && (!exactInteger(minimum) || !exactInteger(maximum))) {
                throw new IllegalArgumentException("Integer schema bounds must be exactly representable integers");
            }
        }

        public static PrimitiveValue booleanValue() {
            return new PrimitiveValue(PrimitiveKind.BOOLEAN, null, null, null, null);
        }

        public static PrimitiveValue integer() {
            return new PrimitiveValue(PrimitiveKind.INTEGER, null, null, null, null);
        }

        public static PrimitiveValue decimal() {
            return new PrimitiveValue(PrimitiveKind.DECIMAL, null, null, null, null);
        }

        public static PrimitiveValue string() {
            return new PrimitiveValue(PrimitiveKind.STRING, null, null, null, null);
        }
    }

    public record LiteralValue(JsonValue value) implements Value {
        public LiteralValue {
            value = Objects.requireNonNull(value, "Minecraft schema literal value is required");
        }
    }

    public record EnumValue(List<JsonValue> values) implements Value {
        public EnumValue {
            values = jsonValues(values, "Minecraft schema enum values", 4_096);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Minecraft schema enum values must not be empty");
            }
            Set<String> canonical = new HashSet<>();
            for (JsonValue value : values) {
                if (!canonical.add(value.canonicalText())) {
                    throw new IllegalArgumentException("Minecraft schema enum values contain a duplicate");
                }
            }
        }
    }

    public record RegistryReferenceValue(CatalogId catalog, boolean tagsAllowed) implements Value {
        public RegistryReferenceValue {
            catalog = Objects.requireNonNull(catalog, "Minecraft schema registry catalog is required");
        }
    }

    public record ResourceLocationValue() implements Value {
    }

    public record Field(String name, String description, Value value, boolean required, JsonValue defaultValue) {
        public Field {
            name = fieldName(name);
            description = MetadataValidation.text(description, "Minecraft schema field description", 2_048);
            value = Objects.requireNonNull(value, "Minecraft schema field value is required");
        }
    }

    public record RecordValue(List<Field> fields, boolean additionalFields) implements Value {
        public RecordValue {
            Objects.requireNonNull(fields, "Minecraft schema record fields are required");
            if (fields.size() > 4_096) {
                throw new IllegalArgumentException("Minecraft schema record field count exceeds 4096");
            }
            List<Field> copy = new ArrayList<>(fields.size());
            Set<String> names = new HashSet<>();
            for (Field field : fields) {
                Field checked = Objects.requireNonNull(field, "Minecraft schema record field is required");
                if (!names.add(checked.name())) {
                    throw new IllegalArgumentException("Minecraft schema record contains a duplicate field: " + checked.name());
                }
                copy.add(checked);
            }
            fields = List.copyOf(copy);
        }
    }

    public record ListValue(Value items, Integer minimumItems, Integer maximumItems, boolean uniqueItems) implements Value {
        public ListValue {
            items = Objects.requireNonNull(items, "Minecraft schema list item value is required");
            minimumItems = nonNegative(minimumItems, "Minecraft schema minimum item count");
            maximumItems = nonNegative(maximumItems, "Minecraft schema maximum item count");
            if (minimumItems != null && maximumItems != null && minimumItems > maximumItems) {
                throw new IllegalArgumentException("Minecraft schema minimum item count exceeds maximum item count");
            }
        }
    }

    public record MapValue(Value values, CatalogId keyCatalog, Integer minimumEntries,
                           Integer maximumEntries) implements Value {
        public MapValue {
            values = Objects.requireNonNull(values, "Minecraft schema map value is required");
            minimumEntries = nonNegative(minimumEntries, "Minecraft schema minimum map entries");
            maximumEntries = nonNegative(maximumEntries, "Minecraft schema maximum map entries");
            if (minimumEntries != null && maximumEntries != null && minimumEntries > maximumEntries) {
                throw new IllegalArgumentException("Minecraft schema minimum map entries exceeds maximum map entries");
            }
        }
    }

    public record UnionValue(List<Value> alternatives) implements Value {
        public UnionValue {
            Objects.requireNonNull(alternatives, "Minecraft schema union alternatives are required");
            if (alternatives.size() < 2 || alternatives.size() > 256) {
                throw new IllegalArgumentException("Minecraft schema union must contain between 2 and 256 alternatives");
            }
            List<Value> copy = alternatives.stream()
                .map(value -> Objects.requireNonNull(value, "Minecraft schema union alternative is required")).toList();
            if (new HashSet<>(copy).size() != copy.size()) {
                throw new IllegalArgumentException("Minecraft schema union contains a duplicate alternative");
            }
            alternatives = List.copyOf(copy);
        }
    }

    public record PresenceValue() implements Value {
    }

    public record OpaqueValue(String reason) implements Value {
        public OpaqueValue {
            reason = MetadataValidation.text(reason, "Minecraft opaque schema reason", 2_048);
        }
    }

    private static List<Capability> capabilities(List<Capability> values) {
        Objects.requireNonNull(values, "Minecraft schema capabilities are required");
        if (values.size() > 256) {
            throw new IllegalArgumentException("Minecraft schema capability count exceeds 256");
        }
        List<Capability> sorted = new ArrayList<>(values.size());
        Set<String> ids = new HashSet<>();
        for (Capability value : values) {
            Capability checked = Objects.requireNonNull(value, "Minecraft schema capability is required");
            if (!ids.add(checked.id())) {
                throw new IllegalArgumentException("Minecraft schema capabilities contain a duplicate ID: " + checked.id());
            }
            sorted.add(checked);
        }
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private static List<Schema> schemas(List<Schema> values) {
        Objects.requireNonNull(values, "Minecraft schemas are required");
        if (values.size() > MAX_SCHEMAS) {
            throw new IllegalArgumentException("Minecraft schema count exceeds " + MAX_SCHEMAS);
        }
        List<Schema> sorted = new ArrayList<>(values.size());
        Set<SchemaKey> keys = new HashSet<>();
        for (Schema value : values) {
            Schema checked = Objects.requireNonNull(value, "Minecraft schema is required");
            if (!keys.add(checked.key())) {
                throw new IllegalArgumentException("Minecraft schemas contain a duplicate key: " + checked.key().canonicalText());
            }
            sorted.add(checked);
        }
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private static List<Evidence> freezeEvidence(List<Evidence> values) {
        Objects.requireNonNull(values, "Minecraft schema evidence is required");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Minecraft schema evidence must not be empty");
        }
        if (values.size() > 256) {
            throw new IllegalArgumentException("Minecraft schema evidence count exceeds 256");
        }
        TreeSet<Evidence> sorted = new TreeSet<>();
        for (Evidence value : values) {
            if (!sorted.add(Objects.requireNonNull(value, "Minecraft schema evidence is required"))) {
                throw new IllegalArgumentException("Minecraft schema evidence contains a duplicate");
            }
        }
        return List.copyOf(sorted);
    }

    private static List<JsonValue> jsonValues(List<JsonValue> values, String field, int maximum) {
        Objects.requireNonNull(values, field + " are required");
        if (values.size() > maximum) {
            throw new IllegalArgumentException(field + " count exceeds " + maximum);
        }
        return List.copyOf(values.stream().map(value -> Objects.requireNonNull(value, field + " cannot contain null")).toList());
    }

    private static List<String> sortedText(List<String> values, String field, int maximumCodePoints, int maximum) {
        Objects.requireNonNull(values, field + " values are required");
        if (values.size() > maximum) {
            throw new IllegalArgumentException(field + " count exceeds " + maximum);
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            String checked = MetadataValidation.text(value, field, maximumCodePoints);
            if (!sorted.add(checked)) {
                throw new IllegalArgumentException(field + " values contain a duplicate: " + checked);
            }
        }
        return List.copyOf(sorted);
    }

    private static List<String> sortedIds(List<String> values, String field, int maximum) {
        Objects.requireNonNull(values, field + " values are required");
        if (values.size() > maximum) {
            throw new IllegalArgumentException(field + " count exceeds " + maximum);
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            String checked = MetadataValidation.id(value, field);
            if (!sorted.add(checked)) {
                throw new IllegalArgumentException(field + " values contain a duplicate: " + checked);
            }
        }
        return List.copyOf(sorted);
    }

    private static String fieldName(String value) {
        String checked = MetadataValidation.text(value, "Minecraft schema field name", 128);
        if (!FIELD_NAME.matcher(checked).matches()) {
            throw new IllegalArgumentException("Minecraft schema field name contains unsafe characters");
        }
        return checked;
    }

    private static String opaqueVersion(String value, String field) {
        String checked = MetadataValidation.text(value, field, 192);
        if (!OPAQUE_VERSION.matcher(checked).matches() || ".".equals(checked) || "..".equals(checked) || checked.contains("..")) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return checked;
    }

    private static BigDecimal normalizeDecimal(BigDecimal value) {
        return value == null ? null : value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static boolean exactInteger(BigDecimal value) {
        if (value == null) return true;
        try {
            value.toBigIntegerExact();
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static Integer nonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private static void validateGraph(List<Schema> schemas) {
        int[] nodes = {0};
        for (Schema schema : schemas) {
            validateValue(schema.value(), 1, nodes);
            if (schema.complete() && containsOpaque(schema.value())) {
                throw new IllegalArgumentException("Complete Minecraft schema cannot contain an opaque value");
            }
        }
    }

    private static boolean containsOpaque(Value value) {
        if (value instanceof OpaqueValue) return true;
        if (value instanceof RecordValue record) {
            return record.fields().stream().anyMatch(field -> containsOpaque(field.value()));
        }
        if (value instanceof ListValue list) return containsOpaque(list.items());
        if (value instanceof MapValue map) return containsOpaque(map.values());
        if (value instanceof UnionValue union) return union.alternatives().stream().anyMatch(MinecraftSchemaBundle::containsOpaque);
        return false;
    }

    private static void validateValue(Value value, int depth, int[] nodes) {
        if (depth > MAX_SCHEMA_DEPTH) {
            throw new IllegalArgumentException("Minecraft schema exceeds maximum depth " + MAX_SCHEMA_DEPTH);
        }
        if (++nodes[0] > MAX_SCHEMA_NODES) {
            throw new IllegalArgumentException("Minecraft schema node count exceeds " + MAX_SCHEMA_NODES);
        }
        if (value instanceof RecordValue record) {
            for (Field field : record.fields()) validateValue(field.value(), depth + 1, nodes);
        } else if (value instanceof ListValue list) {
            validateValue(list.items(), depth + 1, nodes);
        } else if (value instanceof MapValue map) {
            validateValue(map.values(), depth + 1, nodes);
        } else if (value instanceof UnionValue union) {
            for (Value alternative : union.alternatives()) validateValue(alternative, depth + 1, nodes);
        }
    }
}
