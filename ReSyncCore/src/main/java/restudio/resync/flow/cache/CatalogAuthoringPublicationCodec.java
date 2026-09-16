package restudio.resync.flow.cache;

import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CatalogAuthoringPublicationCodec implements CanonicalCodec<CatalogAuthoringPublication> {
    private static final String KIND = "catalog-authoring";
    private static final Set<String> KNOWN = Set.of("kind", "binding", "contractVersion", "projectionVersion",
        "sections", "advertisedEditCapabilities");
    private static final Set<String> BINDING_KNOWN = Set.of("generation", "catalogChecksum", "bindingManifestHash");
    private static final Set<String> VERSION_KNOWN = Set.of("generation", "minor");
    private static final Set<String> SECTION_KNOWN = Set.of("section", "present", "acknowledged", "state", "entries");
    private static final Set<String> ENTRY_KNOWN = Set.of("key", "state", "requiredCapabilities", "requiredSections", "opaque", "data");

    @Override
    public JsonValue.JsonObject encode(CatalogAuthoringPublication publication) {
        Objects.requireNonNull(publication, "catalog authoring publication");
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("kind", KIND);
        known.put("binding", encodeBinding(publication.binding()));
        known.put("contractVersion", encodeVersion(publication.contractVersion()));
        known.put("projectionVersion", encodeVersion(publication.projectionVersion()));
        known.put("sections", publication.sections().stream().map(this::encodeSection).toList());
        known.put("advertisedEditCapabilities", publication.advertisedEditCapabilities().stream()
            .sorted(Comparator.comparing(ContractRef::canonicalText)).map(ContractRef::canonicalValue).toList());
        return object(known, publication.unknown());
    }

    @Override
    public CatalogAuthoringPublication decode(JsonValue value) {
        return decode(value, null);
    }

    private CatalogAuthoringPublication decode(CanonicalCodec.ValidatedJson validated) {
        return decode(validated.value(), validated);
    }

    CatalogAuthoringPublication decodeValidated(CanonicalCodec.ValidatedJson validated, JsonValue value) {
        return decode(Objects.requireNonNull(value, "catalog authoring validated value"),
            Objects.requireNonNull(validated, "catalog authoring validated JSON"));
    }

    private CatalogAuthoringPublication decode(JsonValue value, CanonicalCodec.ValidatedJson validated) {
        JsonValue.JsonObject object = object(value, "Catalog authoring publication");
        if (!KIND.equals(text(object, "kind"))) {
            throw new IllegalArgumentException("Unknown catalog authoring publication kind");
        }
        CatalogBinding binding = decodeBinding(require(object, "binding"));
        CatalogVersion contractVersion = decodeCatalogVersion(require(object, "contractVersion"));
        CatalogProjectionVersion projectionVersion = decodeProjectionVersion(require(object, "projectionVersion"));
        JsonValue.JsonArray sectionValues = requireArray(object, "sections");
        if (sectionValues.values().size() > CatalogAuthoringPublication.MAX_SECTION_COUNT) {
            throw new IllegalArgumentException("Catalog authoring publication contains too many sections");
        }
        if (sectionValues.values().size() != CatalogAuthoringPublication.MAX_SECTION_COUNT) {
            throw exactShape();
        }
        List<CatalogAuthoringPublication.SectionProjection> sections = new ArrayList<>(sectionValues.values().size());
        String previousSection = null;
        for (JsonValue sectionValue : sectionValues.values()) {
            CatalogAuthoringPublication.SectionProjection section = decodeSection(sectionValue, validated);
            String sectionName = section.section().wireName();
            if (previousSection != null && previousSection.compareTo(sectionName) >= 0) {
                throw exactShape();
            }
            previousSection = sectionName;
            sections.add(section);
        }
        JsonValue.JsonArray advertisedValues = requireArray(object, "advertisedEditCapabilities");
        Set<ContractRef<CapabilityId>> advertised = decodeCapabilities(advertisedValues);
        return new CatalogAuthoringPublication(binding, contractVersion, projectionVersion, sections, advertised,
            unknown(object, KNOWN));
    }

    public byte[] encodeBytes(CatalogAuthoringPublication publication) {
        return CanonicalCodec.super.encodeBytes(publication, CanonicalLimits.catalog());
    }

    public String encodeText(CatalogAuthoringPublication publication) {
        return CanonicalCodec.super.encodeText(publication, CanonicalLimits.catalog());
    }

    public CatalogAuthoringPublication decodeBytes(byte[] input) {
        return decode(CanonicalCodec.decodeValidated(input, CanonicalLimits.catalog()));
    }

    public CatalogAuthoringPublication decodeText(String input) {
        return decode(CanonicalCodec.decodeValidated(input, CanonicalLimits.catalog()));
    }

    private static Map<String, Object> encodeBinding(CatalogBinding binding) {
        return Map.of("generation", binding.generation(), "catalogChecksum", binding.catalogChecksum().canonicalText(),
            "bindingManifestHash", binding.bindingManifestHash().canonicalText());
    }

    private static Map<String, Object> encodeVersion(CatalogVersion version) {
        return Map.of("generation", version.generation(), "minor", version.minor());
    }

    private static Map<String, Object> encodeVersion(CatalogProjectionVersion version) {
        return Map.of("generation", version.generation(), "minor", version.minor());
    }

    private JsonValue.JsonObject encodeSection(CatalogAuthoringPublication.SectionProjection section) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("section", section.section().wireName());
        known.put("present", section.present());
        known.put("acknowledged", section.acknowledged());
        known.put("state", wireName(section.state()));
        known.put("entries", section.entries().stream().map(this::encodeEntry).toList());
        return object(known, section.unknown());
    }

    private JsonValue.JsonObject encodeEntry(CatalogAuthoringPublication.Entry entry) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("key", entry.key());
        known.put("state", wireName(entry.state()));
        known.put("requiredCapabilities", entry.requiredCapabilities().stream()
            .sorted(Comparator.comparing(ContractRef::canonicalText)).map(ContractRef::canonicalValue).toList());
        known.put("requiredSections", entry.requiredSections().stream()
            .sorted(Comparator.comparing(CatalogAuthoringPublication.Section::wireName)).map(CatalogAuthoringPublication.Section::wireName).toList());
        known.put("opaque", entry.opaque());
        known.put("data", entry.canonicalDataValue());
        return object(known, entry.unknown());
    }

    private static CatalogBinding decodeBinding(JsonValue value) {
        JsonValue.JsonObject object = object(value, "catalog authoring binding");
        rejectUnknown(object, BINDING_KNOWN, "catalog authoring binding");
        CatalogBinding binding = new CatalogBinding(requireLong(object, "generation"),
            new ContentHash(text(object, "catalogChecksum")), new ContentHash(text(object, "bindingManifestHash")));
        requireExact(object, "generation", JsonValue.of(binding.generation()));
        requireExact(object, "catalogChecksum", JsonValue.of(binding.catalogChecksum().canonicalText()));
        requireExact(object, "bindingManifestHash", JsonValue.of(binding.bindingManifestHash().canonicalText()));
        return binding;
    }

    private static CatalogVersion decodeCatalogVersion(JsonValue value) {
        JsonValue.JsonObject object = object(value, "catalog contract version");
        rejectUnknown(object, VERSION_KNOWN, "catalog contract version");
        CatalogVersion version = new CatalogVersion(requireInt(object, "generation"), requireInt(object, "minor"));
        requireExactVersion(object, version.generation(), version.minor());
        return version;
    }

    private static CatalogProjectionVersion decodeProjectionVersion(JsonValue value) {
        JsonValue.JsonObject object = object(value, "catalog projection version");
        rejectUnknown(object, VERSION_KNOWN, "catalog projection version");
        CatalogProjectionVersion version = new CatalogProjectionVersion(requireInt(object, "generation"),
            requireInt(object, "minor"));
        requireExactVersion(object, version.generation(), version.minor());
        return version;
    }

    private CatalogAuthoringPublication.SectionProjection decodeSection(JsonValue value,
                                                                         CanonicalCodec.ValidatedJson validated) {
        JsonValue.JsonObject object = object(value, "catalog authoring section");
        CatalogAuthoringPublication.Section section = CatalogAuthoringPublication.Section.fromWireName(text(object, "section"));
        boolean present = booleanValue(object, "present");
        boolean acknowledged = booleanValue(object, "acknowledged");
        CatalogCacheState state = enumValue(text(object, "state"), CatalogCacheState.class);
        JsonValue.JsonArray entryValues = requireArray(object, "entries");
        if (entryValues.values().size() > CatalogAuthoringPublication.MAX_SECTION_ENTRIES) {
            throw new IllegalArgumentException("Catalog authoring section contains too many entries");
        }
        List<CatalogAuthoringPublication.Entry> entries = new ArrayList<>(entryValues.values().size());
        String previousKey = null;
        for (JsonValue entryValue : entryValues.values()) {
            CatalogAuthoringPublication.Entry entry = decodeEntry(section, entryValue, validated);
            if (previousKey != null && previousKey.compareTo(entry.key()) >= 0) {
                throw exactShape();
            }
            previousKey = entry.key();
            entries.add(entry);
        }
        requireExact(object, "section", JsonValue.of(section.wireName()));
        requireExact(object, "present", JsonValue.of(present));
        requireExact(object, "acknowledged", JsonValue.of(acknowledged));
        requireExact(object, "state", JsonValue.of(wireName(state)));
        return new CatalogAuthoringPublication.SectionProjection(section, present, acknowledged, state, entries,
            unknown(object, SECTION_KNOWN));
    }

    private CatalogAuthoringPublication.Entry decodeEntry(CatalogAuthoringPublication.Section section, JsonValue value,
                                                            CanonicalCodec.ValidatedJson validated) {
        JsonValue.JsonObject object = object(value, "catalog authoring entry");
        String key = text(object, "key");
        ContractRef<CapabilityId> reference = ContractRef.parseCanonicalText(key, CapabilityId::new);
        CatalogCacheState state = enumValue(text(object, "state"), CatalogCacheState.class);
        Set<ContractRef<CapabilityId>> capabilities = decodeCapabilities(requireArray(object, "requiredCapabilities"));
        Set<CatalogAuthoringPublication.Section> sections = decodeSections(requireArray(object, "requiredSections"));
        boolean opaque = booleanValue(object, "opaque");
        JsonValue data = require(object, "data");
        Map<String, Object> unknown = unknown(object, ENTRY_KNOWN);
        CatalogAuthoringPublication.Entry entry = validated == null
            ? CatalogAuthoringPublication.Entry.fromCanonicalJson(section, reference, state, capabilities, sections,
                opaque, data, unknown)
            : CatalogAuthoringPublication.Entry.fromCanonicalJson(validated, section, reference, state, capabilities,
                sections, opaque, data, unknown);
        requireExact(object, "key", JsonValue.of(entry.key()));
        requireExact(object, "state", JsonValue.of(wireName(state)));
        requireExact(object, "opaque", JsonValue.of(opaque));
        return entry;
    }

    private static Set<ContractRef<CapabilityId>> decodeCapabilities(JsonValue.JsonArray values) {
        if (values.values().size() > CatalogAuthoringPublication.MAX_REQUIRED_CAPABILITIES) {
            throw new IllegalArgumentException("Catalog authoring capability list is too large");
        }
        Set<ContractRef<CapabilityId>> decoded = new LinkedHashSet<>();
        String previous = null;
        for (JsonValue value : values.values()) {
            ContractRef<CapabilityId> capability = IdentityCodec.decodeReference(value, CapabilityId::new);
            String canonical = capability.canonicalText();
            if (!IdentityCodec.encode(capability).equals(value)
                || previous != null && previous.compareTo(canonical) >= 0) {
                throw exactShape();
            }
            if (!decoded.add(capability)) {
                throw new IllegalArgumentException("Duplicate catalog authoring capability");
            }
            previous = canonical;
        }
        return Set.copyOf(decoded);
    }

    private static Set<CatalogAuthoringPublication.Section> decodeSections(JsonValue.JsonArray values) {
        if (values.values().size() > CatalogAuthoringPublication.MAX_SECTION_COUNT) {
            throw new IllegalArgumentException("Catalog authoring required section list is too large");
        }
        EnumSet<CatalogAuthoringPublication.Section> sections = EnumSet.noneOf(CatalogAuthoringPublication.Section.class);
        String previous = null;
        for (JsonValue value : values.values()) {
            if (!(value instanceof JsonValue.JsonString string)) {
                throw new IllegalArgumentException("Catalog authoring required sections must be text");
            }
            CatalogAuthoringPublication.Section section = CatalogAuthoringPublication.Section.fromWireName(string.value());
            if (previous != null && previous.compareTo(section.wireName()) >= 0) {
                throw exactShape();
            }
            if (!sections.add(section)) {
                throw new IllegalArgumentException("Duplicate catalog authoring required section");
            }
            previous = section.wireName();
        }
        return Set.copyOf(sections);
    }

    private static String wireName(CatalogCacheState state) {
        return state.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static JsonValue.JsonObject object(Map<String, Object> known, Map<String, Object> unknown) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        known.forEach((key, value) -> values.put(key, JsonValue.fromJava(value)));
        Map<String, JsonValue> unknownValues = new LinkedHashMap<>();
        if (unknown != null) {
            unknown.forEach((key, value) -> unknownValues.put(key, JsonValue.fromJava(value)));
        }
        return CanonicalCodec.mergeKnownFields(values, unknownValues);
    }

    private static JsonValue.JsonObject object(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static JsonValue require(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required catalog authoring field is missing: " + field);
        }
        return value;
    }

    private static String text(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException("Catalog authoring field must be text: " + field);
        }
        return string.value();
    }

    private static long requireLong(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Catalog authoring field must be an integer: " + field);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Catalog authoring integer is out of range: " + field, exception);
        }
    }

    private static int requireInt(JsonValue.JsonObject object, String field) {
        long value = requireLong(object, field);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Catalog authoring integer is out of range: " + field, exception);
        }
    }

    private static boolean booleanValue(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw new IllegalArgumentException("Catalog authoring field must be boolean: " + field);
        }
        return booleanValue.value();
    }

    private static JsonValue.JsonArray requireArray(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Catalog authoring field must be an array: " + field);
        }
        return array;
    }

    private static Map<String, Object> unknown(JsonValue.JsonObject object, Set<String> known) {
        Map<String, Object> values = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> {
            if (!known.contains(key)) {
                values.put(key, value.toJava());
            }
        });
        return values;
    }

    private static void rejectUnknown(JsonValue.JsonObject object, Set<String> known, String name) {
        Map<String, Object> unknown = unknown(object, known);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(name + " contains unknown fields: " + unknown.keySet());
        }
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown catalog authoring value: " + value, exception);
        }
    }

    private static void requireExactVersion(JsonValue.JsonObject object, int generation, int minor) {
        requireExact(object, "generation", JsonValue.of(generation));
        requireExact(object, "minor", JsonValue.of(minor));
    }

    private static void requireExact(JsonValue.JsonObject object, String field, JsonValue expected) {
        if (!expected.equals(require(object, field))) {
            throw exactShape();
        }
    }

    private static IllegalArgumentException exactShape() {
        return new IllegalArgumentException("Catalog authoring publication is not in the exact canonical shape");
    }
}
