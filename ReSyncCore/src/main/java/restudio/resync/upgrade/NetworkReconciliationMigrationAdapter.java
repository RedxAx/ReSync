package restudio.resync.upgrade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonBoolean;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineRecord;

public final class NetworkReconciliationMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String MANIFEST_PATH = "network/resource-manifest.json";
    public static final String NETWORK_STATE_PATH = "network/network-state.json";
    private static final String ADAPTER_ID = "network-reconciliation-v2";
    private static final String OWNER = ProductionPersistenceOwners.NETWORK;
    private static final int TARGET_VERSION = 2;
    private static final String EMPTY_PAYLOAD_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Set<String> ENTRY_FIELDS = Set.of("type", "resourceId", "resourceOwner", "revision", "mutationId", "payloadHash", "deleted", "tombstone", "updatedAt");

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public Adaptation adapt(Input input) throws IOException {
        List<Claim> claims = new ArrayList<>();
        SourceFile networkState = input.file(NETWORK_STATE_PATH).orElse(null);
        if (networkState != null) {
            claims.add(new Claim(networkState.relativePath(), OWNER));
            input.read(networkState);
        }
        SourceFile source = input.file(MANIFEST_PATH).orElse(null);
        if (source == null) {
            return Adaptation.claimed(claims);
        }
        claims.add(new Claim(source.relativePath(), OWNER));
        byte[] sourceBytes = input.read(source);
        JsonObject manifest;
        try {
            manifest = object(JsonValue.fromJava(CanonicalJson.parse(sourceBytes)), "Network resource manifest root");
        } catch (RuntimeException exception) {
            return new Adaptation(claims, List.of(), List.of(quarantine(source, MANIFEST_PATH, "NETWORK.MANIFEST_INVALID", reason(exception), List.of())));
        }
        int version;
        try {
            version = integer(manifest.value("version"), "Network resource manifest version");
        } catch (RuntimeException exception) {
            return new Adaptation(claims, List.of(), List.of(quarantine(source, MANIFEST_PATH, "NETWORK.MANIFEST_INVALID", reason(exception), List.of())));
        }
        if (version >= TARGET_VERSION) {
            return Adaptation.claimed(claims);
        }
        if (version != 1) {
            return new Adaptation(claims, List.of(), List.of(quarantine(source, MANIFEST_PATH, "NETWORK.MANIFEST_VERSION_UNSUPPORTED", "Network resource manifest version " + version + " cannot be migrated", List.of())));
        }

        Migration migration;
        try {
            migration = migrate(source, manifest);
        } catch (RuntimeException exception) {
            return new Adaptation(claims, List.of(), List.of(quarantine(source, MANIFEST_PATH, "NETWORK.MANIFEST_INVALID", reason(exception), List.of())));
        }
        if (!migration.quarantine().isEmpty()) {
            return new Adaptation(claims, List.of(), migration.quarantine());
        }
        Map<String, JsonValue> targetFields = new LinkedHashMap<>(manifest.fields());
        targetFields.remove("entries");
        targetFields.remove("resources");
        targetFields.put("version", JsonValue.of(TARGET_VERSION));
        targetFields.put("resources", JsonValue.array(migration.resources()));
        byte[] targetBytes = JsonValue.object(targetFields).canonicalBytes();
        List<Change> changes = MessageDigest.isEqual(sourceBytes, targetBytes)
            ? List.of()
            : List.of(new Change(ADAPTER_ID, MANIFEST_PATH, MANIFEST_PATH, targetBytes));
        return new Adaptation(claims, changes, List.of());
    }

    private Migration migrate(SourceFile source, JsonObject manifest) {
        List<Candidate> candidates = candidates(source, manifest.value("entries"));
        Map<Key, List<Candidate>> grouped = new LinkedHashMap<>();
        candidates.stream().sorted(Comparator.comparing(Candidate::key).thenComparing(Candidate::location)).forEach(candidate -> grouped.computeIfAbsent(candidate.key(), ignored -> new ArrayList<>()).add(candidate));
        List<JsonValue> resources = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        for (Map.Entry<Key, List<Candidate>> entry : grouped.entrySet()) {
            List<Candidate> values = entry.getValue();
            long newestRevision = values.stream().mapToLong(Candidate::revision).max().orElseThrow();
            List<Candidate> newest = values.stream().filter(candidate -> candidate.revision() == newestRevision).sorted(Comparator.comparing(Candidate::fingerprint).thenComparing(Candidate::location)).toList();
            Candidate winner = newest.getFirst();
            if (newest.stream().anyMatch(candidate -> !winner.sameMutation(candidate))) {
                newest.forEach(candidate -> quarantine.add(quarantine(source, candidate.location(), "NETWORK.EQUAL_REVISION_CONFLICT", "Equal network resource revisions contain different complete mutations", List.of(entry.getKey().text()))));
                continue;
            }
            resources.add(winner.target());
        }
        resources.sort(Comparator.comparing(JsonValue::canonicalText));
        return new Migration(List.copyOf(resources), List.copyOf(quarantine));
    }

    private List<Candidate> candidates(SourceFile source, JsonValue entriesValue) {
        if (entriesValue instanceof JsonObject entries) {
            List<Candidate> candidates = new ArrayList<>();
            entries.fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> candidates.add(candidate(source, entry.getKey(), entry.getValue())));
            return List.copyOf(candidates);
        }
        if (entriesValue instanceof JsonArray entries) {
            List<Candidate> candidates = new ArrayList<>();
            for (int index = 0; index < entries.values().size(); index++) {
                candidates.add(candidate(source, Integer.toString(index), entries.values().get(index)));
            }
            return List.copyOf(candidates);
        }
        throw new IllegalArgumentException("Network resource manifest entries must be an object or array");
    }

    private Candidate candidate(SourceFile source, String entryName, JsonValue value) {
        JsonObject entry = object(value, "Network resource manifest entry");
        String type = text(entry.value("type"), "Network resource type").trim().toLowerCase(Locale.ROOT);
        String resourceId = text(entry.value("resourceId"), "Network resource ID").trim();
        String resourceOwner = optionalText(entry.value("resourceOwner"), "resync").trim().toLowerCase(Locale.ROOT);
        long revision = positiveLong(entry.value("revision"), "Network resource revision");
        String payloadHash = digest(entry.value("payloadHash"), "Network resource payload hash");
        boolean tombstone = tombstone(entry);
        if (tombstone && !payloadHash.equals(EMPTY_PAYLOAD_HASH)) {
            throw new IllegalArgumentException("Network resource tombstone payload hash must match the canonical empty payload");
        }
        long updatedAt = nonNegativeLong(entry.value("updatedAt"), "Network resource update time", 0);
        String mutationId = optionalText(entry.value("mutationId"), "").trim();
        if (mutationId.isEmpty()) {
            mutationId = "migration-" + sha256(resourceOwner + "\u0000" + type + "\u0000" + resourceId + "\u0000" + revision + "\u0000" + payloadHash + "\u0000" + tombstone);
        }
        ResourceKey resourceKey = new ResourceKey(new ContractRef<>(new OwnerId(resourceOwner), new ResourceTypeId(type)), resourceId);
        Key key = new Key(resourceKey.owner().canonicalText(), resourceKey.resourceType().canonicalText(), resourceKey.id());
        Map<String, JsonValue> target = new LinkedHashMap<>();
        entry.fields().forEach((name, field) -> {
            if (!ENTRY_FIELDS.contains(name)) {
                target.put(name, field);
            }
        });
        target.put("key", JsonValue.fromJava(resourceKey.canonicalValue()));
        target.put("revision", JsonValue.of(revision));
        target.put("mutationId", JsonValue.of(mutationId));
        target.put("payloadHash", JsonValue.of(payloadHash));
        target.put("tombstone", JsonValue.of(tombstone));
        target.put("updatedAt", JsonValue.of(updatedAt));
        JsonObject targetValue = JsonValue.object(target);
        String location = MANIFEST_PATH + "#entries/" + entryName;
        return new Candidate(key, revision, mutationId, payloadHash, tombstone, location, targetValue, sha256(targetValue.canonicalBytes()));
    }

    private QuarantineRecord quarantine(SourceFile source, String location, String code, String reason, List<String> affected) {
        String recordId = "network-" + sha256(code + "\u0000" + location + "\u0000" + reason + "\u0000" + String.join("\u0000", affected)).substring(0, 24);
        List<String> references = new ArrayList<>(affected);
        if (!location.equals(source.relativePath())) {
            references.add(location);
        }
        return new QuarantineRecord(recordId, code, source.relativePath(), reason, references, "Resolve the complete typed network mutation before activation", source.sha256());
    }

    private JsonObject object(JsonValue value, String field) {
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new IllegalArgumentException(field + " must be an object");
    }

    private int integer(JsonValue value, String field) {
        long number = exactLong(value, field);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is outside the supported range");
        }
        return (int) number;
    }

    private long positiveLong(JsonValue value, String field) {
        long number = exactLong(value, field);
        if (number < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return number;
    }

    private long nonNegativeLong(JsonValue value, String field, long fallback) {
        if (value == null) {
            return fallback;
        }
        long number = exactLong(value, field);
        if (number < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return number;
    }

    private long exactLong(JsonValue value, String field) {
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private String text(JsonValue value, String field) {
        if (value instanceof JsonString text && !text.value().isBlank() && text.value().indexOf('\u0000') < 0 && text.value().indexOf('\n') < 0 && text.value().indexOf('\r') < 0) {
            return text.value();
        }
        throw new IllegalArgumentException(field + " is required");
    }

    private String optionalText(JsonValue value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return text(value, "Network resource text");
    }

    private String digest(JsonValue value, String field) {
        String digest = text(value, field).trim().toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
        return digest;
    }

    private boolean booleanValue(JsonValue value, boolean fallback) {
        return value == null ? fallback : booleanValue(value, "Network resource deletion state");
    }

    private boolean tombstone(JsonObject entry) {
        JsonValue tombstoneValue = entry.value("tombstone");
        JsonValue deletedValue = entry.value("deleted");
        boolean tombstone = booleanValue(tombstoneValue, false);
        boolean deleted = booleanValue(deletedValue, false);
        if (tombstoneValue != null && deletedValue != null && tombstone != deleted) {
            throw new IllegalArgumentException("Network resource deletion states conflict");
        }
        return tombstoneValue == null ? deleted : tombstone;
    }

    private boolean booleanValue(JsonValue value, String field) {
        if (value instanceof JsonBoolean bool) {
            return bool.value();
        }
        throw new IllegalArgumentException(field + " must be a boolean");
    }

    private String reason(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Migration(List<JsonValue> resources, List<QuarantineRecord> quarantine) {
    }

    private record Key(String owner, String type, String resourceId) implements Comparable<Key> {
        private String text() {
            return owner + "/" + type + "/" + resourceId;
        }

        @Override
        public int compareTo(Key other) {
            int ownerOrder = owner.compareTo(other.owner);
            if (ownerOrder != 0) {
                return ownerOrder;
            }
            int typeOrder = type.compareTo(other.type);
            return typeOrder != 0 ? typeOrder : resourceId.compareTo(other.resourceId);
        }
    }

    private record Candidate(Key key, long revision, String mutationId, String payloadHash, boolean tombstone, String location, JsonObject target, String fingerprint) {
        private boolean sameMutation(Candidate other) {
            return fingerprint.equals(other.fingerprint);
        }
    }
}
