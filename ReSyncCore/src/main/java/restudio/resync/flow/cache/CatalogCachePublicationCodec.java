package restudio.resync.flow.cache;

import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.ServerId;

import java.util.AbstractList;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.Set;

public final class CatalogCachePublicationCodec implements CanonicalCodec<CatalogCachePublication> {
    private static final CatalogAuthoringPublicationCodec DEFAULT_AUTHORING_CODEC = new CatalogAuthoringPublicationCodec();
    public static final CatalogCachePublicationCodec INSTANCE = new CatalogCachePublicationCodec();
    public static final String AUTHORING_PUBLICATION_HASH_DOMAIN = "catalog-authoring";
    private static final Set<String> KNOWN = Set.of("kind", "serverId", "catalogGeneration", "snapshotChecksum",
        "bindingManifestHash", "catalogBinding", "projectionVersion", "revision", "entries", "authoringPublication");
    private static final Set<String> ENTRY_KNOWN = Set.of("definitionKey", "revision", "tombstone", "state",
        "requiredCapabilities", "opaque", "data");
    private final CatalogAuthoringPublicationCodec authoringPublicationCodec;

    public CatalogCachePublicationCodec() {
        this(DEFAULT_AUTHORING_CODEC);
    }

    public CatalogCachePublicationCodec(CatalogAuthoringPublicationCodec authoringPublicationCodec) {
        this.authoringPublicationCodec = Objects.requireNonNull(authoringPublicationCodec, "Authoring publication codec is required");
    }

    public CatalogAuthoringPublicationCodec authoringPublicationCodec() {
        return authoringPublicationCodec;
    }

    public static ContentHash authoringPublicationChecksum(CatalogAuthoringPublication publication) {
        Objects.requireNonNull(publication, "Authoring publication is required");
        byte[] canonical = DEFAULT_AUTHORING_CODEC.encodeBytes(publication);
        return new ContentHash(CanonicalHash.sha256(AUTHORING_PUBLICATION_HASH_DOMAIN, canonical));
    }

    public static ContentHash semanticAuthoringChecksum(CatalogAuthoringPublication publication) {
        return authoringPublicationChecksum(publication);
    }

    @Override
    public JsonValue.JsonObject encode(CatalogCachePublication publication) {
        Objects.requireNonNull(publication, "publication");
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("kind", publication.kind().name().toLowerCase(Locale.ROOT));
        known.put("serverId", publication.serverId().canonicalText());
        known.put("catalogGeneration", publication.catalogGeneration());
        known.put("snapshotChecksum", publication.snapshotChecksum().canonicalText());
        if (publication.catalogBinding() != null) {
            if (publication.projectionVersion().legacy()) {
                known.put("bindingManifestHash", publication.catalogBinding().bindingManifestHash().canonicalText());
            } else {
                known.put("catalogBinding", encodeCatalogBinding(publication.catalogBinding()));
            }
        }
        known.put("projectionVersion", Map.of("generation", publication.projectionVersion().generation(),
            "minor", publication.projectionVersion().minor()));
        known.put("revision", publication.revision());
        known.put("entries", publication.entries().stream().map(this::encodeEntry).toList());
        if (publication.authoringPublication() != null) {
            known.put("authoringPublication", authoringPublicationCodec.encode(publication.authoringPublication()));
        }
        return object(known, publication.unknown());
    }

    @Override
    public CatalogCachePublication decode(JsonValue value) {
        return decode(value, null);
    }

    private CatalogCachePublication decode(CanonicalCodec.ValidatedJson validated) {
        return decode(validated.value(), validated);
    }

    private CatalogCachePublication decode(JsonValue value, CanonicalCodec.ValidatedJson validated) {
        JsonValue.JsonObject object = object(value, "Catalog cache publication");
        CatalogCachePublication.Kind kind = enumValue(text(object, "kind"), CatalogCachePublication.Kind.class);
        ServerId serverId = ServerId.parseCanonicalText(text(object, "serverId"));
        long generation = requireLong(object, "catalogGeneration");
        ContentHash checksum = new ContentHash(text(object, "snapshotChecksum"));
        CatalogProjectionVersion projectionVersion = decodeProjectionVersion(require(object, "projectionVersion"));
        boolean hasCatalogBinding = object.contains("catalogBinding");
        boolean hasBindingManifestHash = object.contains("bindingManifestHash");
        CatalogBinding catalogBinding;
        ContentHash bindingManifestHash = null;
        if (projectionVersion.legacy()) {
            if (hasCatalogBinding) {
                throw new IllegalArgumentException("Legacy catalog publications must use top-level bindingManifestHash");
            }
            bindingManifestHash = optionalText(object, "bindingManifestHash").map(ContentHash::new).orElse(null);
            catalogBinding = bindingManifestHash == null ? null
                : new CatalogBinding(generation, checksum, bindingManifestHash);
        } else {
            if (hasBindingManifestHash) {
                throw new IllegalArgumentException("Current catalog publications must use nested catalogBinding");
            }
            catalogBinding = optional(object, "catalogBinding")
                .map(CatalogCachePublicationCodec::decodeCatalogBinding).orElse(null);
        }
        if (projectionVersion.requiresCatalogBinding() && catalogBinding == null) {
            throw new IllegalArgumentException("Current catalog publications require an exact catalog binding");
        }
        if (catalogBinding != null && (catalogBinding.generation() != generation
            || !catalogBinding.catalogChecksum().equals(checksum))) {
            throw new IllegalArgumentException("Catalog publication binding does not match publication identity");
        }
        long revision = requireLong(object, "revision");
        requireExact(object, "kind", JsonValue.of(kind.name().toLowerCase(Locale.ROOT)));
        requireExact(object, "serverId", JsonValue.of(serverId.canonicalText()));
        requireExact(object, "catalogGeneration", JsonValue.of(generation));
        requireExact(object, "snapshotChecksum", JsonValue.of(checksum.canonicalText()));
        requireExact(object, "projectionVersion", JsonValue.fromJava(Map.of("generation", projectionVersion.generation(),
            "minor", projectionVersion.minor())));
        requireExact(object, "revision", JsonValue.of(revision));
        if (catalogBinding != null) {
            requireExact(object, projectionVersion.legacy() ? "bindingManifestHash" : "catalogBinding",
                projectionVersion.legacy() ? JsonValue.of(catalogBinding.bindingManifestHash().canonicalText())
                    : JsonValue.fromJava(encodeCatalogBinding(catalogBinding)));
        }
        JsonValue.JsonArray entryValues = requireArray(object, "entries");
        List<CatalogCachePublication.Entry> entries = new ArrayList<>(entryValues.values().size());
        String previousDefinitionKey = null;
        for (JsonValue entryValue : entryValues.values()) {
            CatalogCachePublication.Entry entry = decodeEntry(entryValue, validated);
            String definitionKey = entry.definitionKey().canonicalText();
            if (previousDefinitionKey != null && previousDefinitionKey.compareTo(definitionKey) >= 0) {
                throw exactShape();
            }
            previousDefinitionKey = definitionKey;
            entries.add(entry);
        }
        JsonValue authoringValue = object.value("authoringPublication");
        CatalogAuthoringPublication authoringPublication = authoringValue == null ? null
            : validated == null ? authoringPublicationCodec.decode(authoringValue)
                : authoringPublicationCodec.decodeValidated(validated, authoringValue);
        if (validated == null && authoringPublication != null
            && !authoringPublicationCodec.encode(authoringPublication).equals(authoringValue)) {
            throw exactShape();
        }
        CatalogCacheKey key = new CatalogCacheKey(serverId, generation, checksum, projectionVersion,
            catalogBinding == null ? null : catalogBinding.bindingManifestHash());
        CatalogCachePublication publication = new CatalogCachePublication(kind, key, catalogBinding, revision,
            new DecodedEntries(entries),
            authoringPublication, unknown(object, KNOWN));
        return publication;
    }

    public byte[] encodeBytes(CatalogCachePublication publication) {
        return CanonicalCodec.super.encodeBytes(publication, CanonicalLimits.catalog());
    }

    public String encodeText(CatalogCachePublication publication) {
        return CanonicalCodec.super.encodeText(publication, CanonicalLimits.catalog());
    }

    public CatalogCachePublication decodeBytes(byte[] input) {
        return decode(CanonicalCodec.decodeValidated(input, CanonicalLimits.catalog()));
    }

    public ValidatedPublication decodeValidatedPublication(byte[] input) {
        byte[] stableInput = Objects.requireNonNull(input, "Canonical publication bytes are required").clone();
        return new ValidatedPublication(decodeBytes(stableInput), stableInput);
    }

    public static final class ValidatedPublication {
        private final CatalogCachePublication publication;
        private final byte[] canonicalBytes;

        private ValidatedPublication(CatalogCachePublication publication, byte[] canonicalBytes) {
            this.publication = Objects.requireNonNull(publication, "Catalog publication is required");
            this.canonicalBytes = Objects.requireNonNull(canonicalBytes, "Canonical publication bytes are required").clone();
        }

        public CatalogCachePublication publication() {
            return publication;
        }

        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }

    public CatalogCachePublication decodeText(String input) {
        return decode(CanonicalCodec.decodeValidated(input, CanonicalLimits.catalog()));
    }

    private JsonValue.JsonObject encodeEntry(CatalogCachePublication.Entry entry) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("definitionKey", IdentityCodec.encode(entry.definitionKey()));
        known.put("revision", entry.revision());
        known.put("tombstone", entry.tombstone());
        if (!entry.tombstone()) {
            known.put("state", entry.state().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            known.put("requiredCapabilities", entry.requiredCapabilities().stream()
                .map(IdentityCodec::encode).toList());
            known.put("opaque", entry.opaque());
            known.put("data", entry.data().canonicalValue());
        }
        return object(known, entry.unknown());
    }

    private CatalogCachePublication.Entry decodeEntry(JsonValue value, CanonicalCodec.ValidatedJson validated) {
        JsonValue.JsonObject object = object(value, "Catalog cache publication entry");
        JsonValue definitionKeyValue = require(object, "definitionKey");
        ContractRef<NodeId> definitionKey = IdentityCodec.decodeReference(definitionKeyValue, NodeId::new);
        long revision = requireLong(object, "revision");
        boolean tombstone = booleanValue(object, "tombstone");
        if (!IdentityCodec.encode(definitionKey).equals(definitionKeyValue)) {
            throw exactShape();
        }
        requireExact(object, "revision", JsonValue.of(revision));
        requireExact(object, "tombstone", JsonValue.of(tombstone));
        if (tombstone) {
            rejectTombstoneFields(object);
            return new CatalogCachePublication.Entry(definitionKey, revision, true, null, Set.of(), false, null,
                unknown(object, ENTRY_KNOWN));
        }
        CatalogCacheState state = enumValue(text(object, "state"), CatalogCacheState.class);
        JsonValue.JsonArray requiredValues = requireArray(object, "requiredCapabilities");
        Set<ContractRef<CapabilityId>> required = decodeCapabilities(requiredValues);
        boolean opaque = booleanValue(object, "opaque");
        JsonValue dataValue = require(object, "data");
        requireExact(object, "state", JsonValue.of(state.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        requireExact(object, "opaque", JsonValue.of(opaque));
        CatalogCacheOpaque data = validated == null
            ? CatalogCacheOpaque.fromCanonicalJson(dataValue)
            : CatalogCacheOpaque.fromCanonicalJson(validated, dataValue);
        return new CatalogCachePublication.Entry(definitionKey, revision, false, state, required, opaque, data,
            unknown(object, ENTRY_KNOWN));
    }

    private static CatalogProjectionVersion decodeProjectionVersion(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Catalog projection version");
        return new CatalogProjectionVersion(requireInt(object, "generation"), requireInt(object, "minor"));
    }

    private static Map<String, Object> encodeCatalogBinding(CatalogBinding binding) {
        return Map.of("generation", binding.generation(),
            "catalogChecksum", binding.catalogChecksum().canonicalText(),
            "bindingManifestHash", binding.bindingManifestHash().canonicalText());
    }

    private static CatalogBinding decodeCatalogBinding(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Catalog publication binding");
        CatalogBinding binding = new CatalogBinding(requireLong(object, "generation"),
            new ContentHash(text(object, "catalogChecksum")),
            new ContentHash(text(object, "bindingManifestHash")));
        Set<String> known = Set.of("generation", "catalogChecksum", "bindingManifestHash");
        if (!unknown(object, known).isEmpty()) {
            throw new IllegalArgumentException("Unknown catalog publication binding fields are not permitted");
        }
        return binding;
    }

    private static Optional<JsonValue> optional(JsonValue.JsonObject object, String field) {
        return Optional.ofNullable(object.value(field));
    }

    private static Optional<String> optionalText(JsonValue.JsonObject object, String field) {
        return optional(object, field).map(value -> {
            if (!(value instanceof JsonValue.JsonString string)) {
                throw new IllegalArgumentException("Catalog publication field must be text: " + field);
            }
            return string.value();
        });
    }

    private static Set<ContractRef<CapabilityId>> decodeCapabilities(JsonValue.JsonArray values) {
        if (values.values().size() > CatalogCachePublication.MAX_REQUIRED_CAPABILITIES) {
            throw new IllegalArgumentException("Catalog publication capability list is too large");
        }
        LinkedHashSet<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>(values.values().size());
        String previousKey = null;
        for (JsonValue value : values.values()) {
            ContractRef<CapabilityId> capability = IdentityCodec.decodeReference(value, CapabilityId::new);
            String canonicalKey = capability.canonicalText();
            if (!IdentityCodec.encode(capability).equals(value)
                || previousKey != null && previousKey.compareTo(canonicalKey) >= 0) {
                throw exactShape();
            }
            if (!capabilities.add(capability)) {
                throw new IllegalArgumentException("Duplicate catalog publication required capability");
            }
            previousKey = canonicalKey;
        }
        return new DecodedCapabilities(capabilities);
    }

    private static void rejectTombstoneFields(JsonValue.JsonObject object) {
        for (String field : Set.of("state", "requiredCapabilities", "opaque", "data")) {
            if (object.contains(field)) {
                throw new IllegalArgumentException("Tombstone publication entries cannot carry " + field);
            }
        }
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
            throw new IllegalArgumentException("Required catalog publication field is missing: " + field);
        }
        return value;
    }

    private static String text(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException("Catalog publication field must be text: " + field);
        }
        return string.value();
    }

    private static long requireLong(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Catalog publication field must be an integer: " + field);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Catalog publication integer is out of range: " + field, exception);
        }
    }

    private static int requireInt(JsonValue.JsonObject object, String field) {
        long value = requireLong(object, field);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Catalog publication integer is out of range: " + field, exception);
        }
    }

    private static boolean booleanValue(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw new IllegalArgumentException("Catalog publication field must be boolean: " + field);
        }
        return booleanValue.value();
    }

    private static JsonValue.JsonArray requireArray(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Catalog publication field must be an array: " + field);
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

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown catalog publication value: " + value, exception);
        }
    }

    private static void requireExact(JsonValue.JsonObject object, String field, JsonValue expected) {
        if (!expected.equals(require(object, field))) {
            throw exactShape();
        }
    }

    private static IllegalArgumentException exactShape() {
        return new IllegalArgumentException("Catalog cache publication is not in the exact canonical shape");
    }

    static final class DecodedEntries extends AbstractList<CatalogCachePublication.Entry> implements RandomAccess {
        private final List<CatalogCachePublication.Entry> entries;

        private DecodedEntries(List<CatalogCachePublication.Entry> entries) {
            this.entries = List.copyOf(entries);
        }

        List<CatalogCachePublication.Entry> validated(long revision) {
            for (CatalogCachePublication.Entry entry : entries) {
                if (entry.revision() > revision) {
                    throw new IllegalArgumentException("Catalog publication entry revision exceeds publication revision");
                }
            }
            return this;
        }

        @Override
        public CatalogCachePublication.Entry get(int index) {
            return entries.get(index);
        }

        @Override
        public int size() {
            return entries.size();
        }
    }

    static final class DecodedCapabilities extends AbstractSet<ContractRef<CapabilityId>> {
        private final Set<ContractRef<CapabilityId>> capabilities;

        private DecodedCapabilities(Set<ContractRef<CapabilityId>> capabilities) {
            this.capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));
        }

        @Override
        public Iterator<ContractRef<CapabilityId>> iterator() {
            return capabilities.iterator();
        }

        @Override
        public int size() {
            return capabilities.size();
        }
    }
}
