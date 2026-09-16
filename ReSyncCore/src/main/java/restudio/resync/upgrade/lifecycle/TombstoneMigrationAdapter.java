package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.storage.CoreGraphAssetCodec;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

public final class TombstoneMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String ID = "resync.lifecycle.tombstones";
    public static final String OWNER = ProductionPersistenceOwners.FLOW_ASSETS;
    private static final String TOMBSTONE_SEGMENT = "/.tombstones/";
    private static final String CANONICAL_ROOT = "assets/.tombstones/";
    private static final String TRIGGERS = "triggers.json";
    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Set<String> KNOWN_FIELDS = Set.of("type", "id", "revision", "mutationId", "deleted");

    @Override
    public String adapterId() {
        return ID;
    }

    @Override
    public Adaptation adapt(Input input) throws IOException {
        Objects.requireNonNull(input, "input");
        List<Claim> claims = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        Map<Identity, List<Candidate>> candidates = new LinkedHashMap<>();

        for (SourceFile source : input.files()) {
            if (!isTombstonePath(source.relativePath())) {
                continue;
            }
            addClaim(claims, source.relativePath(), OWNER);
            if (!source.owner().equals(OWNER)) {
                continue;
            }
            PathIdentity pathIdentity = pathIdentity(source.relativePath());
            if (pathIdentity == null) {
                quarantine.add(quarantine(source, "MIGRATION.TOMBSTONE_PATH_INVALID", "Tombstone path must end with .tombstones/{type}/{id}.json.",
                    List.of(source.relativePath()), "Move the tombstone to its typed canonical path before importing it."));
                continue;
            }
            try {
                Candidate candidate = candidate(source, pathIdentity, input.read(source));
                candidates.computeIfAbsent(candidate.identity(), ignored -> new ArrayList<>()).add(candidate);
            } catch (IllegalArgumentException exception) {
                quarantine.add(quarantine(source, "MIGRATION.TOMBSTONE_INVALID", exception.getMessage(), List.of(source.relativePath()),
                    "Repair the typed identity, revision, mutation identity, and deletion marker before importing this tombstone."));
            }
        }

        for (Map.Entry<Identity, List<Candidate>> entry : candidates.entrySet()) {
            List<Candidate> group = entry.getValue();
            group.sort(Candidate.NEWEST_FIRST);
            Candidate winner = group.getFirst();
            String target = winner.identity().canonicalPath();
            Candidate canonical = group.stream().filter(candidate -> candidate.source().relativePath().equals(target)).findFirst().orElse(null);
            List<Change> groupChanges = new ArrayList<>();
            if (canonical == null) {
                groupChanges.add(new Change("replace", winner.source().relativePath(), target, winner.canonicalBytes()));
            } else if (!canonical.source().sha256().equals(winner.canonicalHash())) {
                groupChanges.add(new Change("canonicalize-tombstone", canonical.source().relativePath(), target, winner.canonicalBytes()));
            }
            if (canonical != null && !winner.source().relativePath().equals(target)) {
                groupChanges.add(delete(winner.source(), "retire-noncanonical-tombstone"));
            }
            for (int index = 1; index < group.size(); index++) {
                Candidate duplicate = group.get(index);
                if (!duplicate.source().relativePath().equals(target)) {
                    quarantine.add(quarantine(duplicate.source(), "MIGRATION.TOMBSTONE_DUPLICATE",
                        "A newer canonical tombstone exists for the same typed resource.",
                        List.of(duplicate.identity().canonicalText(), winner.source().relativePath()),
                        "Retain the selected highest revision tombstone and inspect this preserved duplicate in migration quarantine."));
                }
            }
            boolean commandGroupBlocked = payloadProtection(input, winner, claims, quarantine);
            if (commandGroupBlocked) {
                List<String> dependencies = quarantine.stream().map(QuarantineRecord::sourceLocation).distinct().sorted().toList();
                for (Candidate candidate : group) {
                    quarantine.add(quarantine(candidate.source(), "MIGRATION.TOMBSTONE_COMMAND_GROUP_BLOCKED",
                        "The Command tombstone is retained because applying it would strand a legacy command binding.",
                        dependencies,
                        "Review the Command graph, tombstone, and trigger registry as one dependency group."));
                }
            } else {
                changes.addAll(groupChanges);
            }
        }

        return new Adaptation(claims, changes, quarantine);
    }

    private static Candidate candidate(SourceFile source, PathIdentity pathIdentity, byte[] bytes) {
        Map<String, Object> value = object(CanonicalJson.parseOpaque(bytes));
        if (CoreGraphAssetCodec.TOMBSTONE_KIND.equals(value.get("kind"))) {
            return currentCandidate(source, pathIdentity, bytes);
        }
        String type = text(value.get("type"), "Tombstone type");
        String id = text(value.get("id"), "Tombstone ID");
        if (!type.equals(pathIdentity.type()) || !id.equals(pathIdentity.id())) {
            throw new IllegalArgumentException("Tombstone path, type, and ID do not agree");
        }
        new ResourceTypeId(type);
        if (!RESOURCE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Tombstone ID is invalid");
        }
        long revision = positiveLong(value.get("revision"), "Tombstone revision");
        String mutationId = text(value.get("mutationId"), "Tombstone mutation identity");
        Object deleted = value.get("deleted");
        if (deleted != null && !Boolean.TRUE.equals(deleted)) {
            throw new IllegalArgumentException("Tombstone deletion marker must be true");
        }

        Map<String, Object> canonical = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!KNOWN_FIELDS.contains(key)) {
                canonical.put(key, item);
            }
        });
        canonical.put("deleted", true);
        canonical.put("id", id);
        canonical.put("mutationId", mutationId);
        canonical.put("revision", revision);
        canonical.put("type", type);
        byte[] canonicalBytes = CanonicalJson.canonicalBytes(canonical);
        Identity identity = new Identity(type, id);
        return new Candidate(source, identity, revision, mutationId, canonicalBytes, sha256(canonicalBytes));
    }

    private static Candidate currentCandidate(SourceFile source, PathIdentity pathIdentity, byte[] bytes) {
        CoreGraphAssetCodec codec = new CoreGraphAssetCodec();
        CoreGraphAssetCodec.Tombstone tombstone = codec.decodeTombstone(bytes);
        ServerResourceLocator resource = tombstone.resource();
        String type = resource.resourceType().value();
        String id = resource.id();
        if (!type.equals(pathIdentity.type()) || !id.equals(pathIdentity.id())) {
            throw new IllegalArgumentException("Tombstone path, nested resource type, and nested resource ID do not agree");
        }
        byte[] canonicalBytes = codec.encodeTombstone(tombstone);
        return new Candidate(source, new Identity(type, id), tombstone.revision(), tombstone.mutationId().toString(), canonicalBytes, sha256(canonicalBytes));
    }

    private static boolean payloadProtection(Input input, Candidate tombstone, List<Claim> claims,
                                             List<QuarantineRecord> quarantine) throws IOException {
        boolean blockedCommandPayload = false;
        for (SourceFile source : input.files()) {
            if (!isAssetPath(source.relativePath()) || isTombstonePath(source.relativePath()) || !source.relativePath().endsWith(".json")) {
                continue;
            }
            Payload payload = payload(input, source);
            if (payload == null || !payload.matches(tombstone.identity())) {
                continue;
            }
            if (!source.owner().equals(OWNER)) {
                continue;
            }
            if (payload.aliasConflict() != null) {
                addClaim(claims, source.relativePath(), OWNER);
                quarantine.add(quarantine(source, "MIGRATION.TOMBSTONE_PAYLOAD_ALIAS_CONFLICT",
                    payload.aliasConflict(), List.of(tombstone.identity().canonicalText(), tombstone.source().relativePath()),
                    "Reconcile the payload identity and revision aliases before applying the tombstone."));
                blockedCommandPayload |= tombstone.identity().type().equals("command");
            } else if (payload.revision() == null) {
                addClaim(claims, source.relativePath(), OWNER);
                quarantine.add(quarantine(source, "MIGRATION.TOMBSTONE_PAYLOAD_REVISION_INVALID",
                    "A live payload matching this tombstone has no valid positive revision and cannot override the deletion.",
                    List.of(tombstone.identity().canonicalText(), tombstone.source().relativePath()),
                    "Repair or quarantine the malformed live payload while retaining the canonical tombstone."));
                blockedCommandPayload |= tombstone.identity().type().equals("command");
            } else if (payload.revision() <= tombstone.revision()) {
                addClaim(claims, source.relativePath(), OWNER);
                quarantine.add(quarantine(source, "MIGRATION.TOMBSTONE_BLOCKS_STALE_PAYLOAD",
                    "The canonical tombstone is at least as new as this live payload, so the payload cannot restore the deleted resource.",
                    List.of(tombstone.identity().canonicalText(), tombstone.source().relativePath()),
                    "Keep the tombstone authoritative and inspect the stale payload in the recoverable source snapshot."));
                blockedCommandPayload |= tombstone.identity().type().equals("command");
            }
        }
        if (!blockedCommandPayload) {
            return false;
        }
        SourceFile triggers = input.file(TRIGGERS).orElse(null);
        if (triggers == null) {
            return false;
        }
        TriggerCommandMigrationAdapter.CommandReferences references =
            TriggerCommandMigrationAdapter.commandReferences(input.read(triggers));
        if (references.valid() && !references.contains(tombstone.identity().id())) {
            return false;
        }
        addClaim(claims, triggers.relativePath(), ProductionPersistenceOwners.TRIGGERS);
        String code = references.valid()
            ? "MIGRATION.TOMBSTONE_COMMAND_BINDING_BLOCKED"
            : "MIGRATION.TOMBSTONE_COMMAND_BINDING_INVALID";
        String reason = references.valid()
            ? "The trigger registry still binds the Command resource blocked by this tombstone."
            : "The malformed trigger registry may bind the Command resource blocked by this tombstone and must fail closed.";
        quarantine.add(quarantine(triggers, code, reason,
            List.of(tombstone.identity().canonicalText(), tombstone.source().relativePath()),
            "Review the trigger registry with the Command graph and tombstone as one dependency group."));
        return true;
    }

    private static Payload payload(Input input, SourceFile source) throws IOException {
        byte[] bytes = input.read(source);
        try {
            CoreGraphAssetCodec.Asset asset = new CoreGraphAssetCodec().decode(bytes);
            ServerResourceLocator resource = asset.graphDocument() == null
                ? asset.functionSourceDocument().graph().resource()
                : asset.graphDocument().resource();
            return Payload.exact(new Identity(resource.resourceType().value(), resource.id()), asset.envelope().assetRevision());
        } catch (IllegalArgumentException exception) {
            return legacyPayload(bytes);
        }
    }

    private static Payload legacyPayload(byte[] bytes) {
        try {
            Map<String, Object> value = object(CanonicalJson.parseOpaque(bytes));
            Object idValue = value.get("id");
            if (!(idValue instanceof String)) {
                return null;
            }
            String id = text(idValue, "Payload ID");
            if (!RESOURCE_ID.matcher(id).matches()) {
                return null;
            }
            Set<Identity> identities = new LinkedHashSet<>();
            boolean invalidIdentityAlias = false;
            for (String field : List.of("type", "resourceType")) {
                if (!value.containsKey(field)) {
                    continue;
                }
                try {
                    String type = text(value.get(field), "Payload " + field);
                    new ResourceTypeId(type);
                    identities.add(new Identity(type, id));
                } catch (IllegalArgumentException exception) {
                    invalidIdentityAlias = true;
                }
            }
            if (identities.isEmpty()) {
                return null;
            }
            Long revision = null;
            boolean revisionAliasConflict = false;
            boolean invalidRevisionAlias = false;
            int revisionAliases = 0;
            for (String field : List.of("revision", "resourceRevision")) {
                if (!value.containsKey(field)) {
                    continue;
                }
                revisionAliases++;
                Long candidate = optionalPositiveLong(value.get(field));
                if (candidate != null && revision != null && !revision.equals(candidate)) {
                    revisionAliasConflict = true;
                } else if (candidate != null) {
                    revision = candidate;
                } else {
                    invalidRevisionAlias = true;
                }
            }
            String conflict = invalidIdentityAlias || identities.size() > 1 || revisionAliasConflict
                || revisionAliases > 1 && invalidRevisionAlias
                ? "Payload identity or revision aliases conflict"
                : null;
            return new Payload(Set.copyOf(identities), revision, conflict);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static PathIdentity pathIdentity(String path) {
        int marker = path.indexOf(TOMBSTONE_SEGMENT);
        int offset;
        if (marker >= 0) {
            offset = marker + TOMBSTONE_SEGMENT.length();
        } else if (path.startsWith(".tombstones/")) {
            offset = ".tombstones/".length();
        } else {
            return null;
        }
        String suffix = path.substring(offset);
        String[] parts = suffix.split("/", -1);
        if (parts.length != 2 || !parts[1].endsWith(".json") || parts[1].length() == ".json".length()) {
            return null;
        }
        return new PathIdentity(parts[0], parts[1].substring(0, parts[1].length() - ".json".length()));
    }

    private static boolean isTombstonePath(String path) {
        return path.startsWith(".tombstones/") || path.contains(TOMBSTONE_SEGMENT);
    }

    private static boolean isAssetPath(String path) {
        return path.startsWith("assets/") || path.contains("/assets/");
    }

    private static Change delete(SourceFile source, String kind) {
        return new Change(kind, source.relativePath(), "", null);
    }

    private static void addClaim(List<Claim> claims, String path, String owner) {
        if (claims.stream().noneMatch(claim -> claim.relativePath().equals(path))) {
            claims.add(new Claim(path, owner));
        }
    }

    private static QuarantineRecord quarantine(SourceFile source, String code, String reason, List<String> references, String action) {
        String identity = CanonicalJson.sha256("migration.tombstone-quarantine", List.of(code, source.relativePath(), source.sha256()));
        return new QuarantineRecord("tombstone-" + identity.substring(0, 24), code, source.relativePath(), reason, references, action, source.sha256());
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Tombstone must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("Tombstone contains a non-string field name");
            }
            result.put(text, item);
        });
        return result;
    }

    private static long positiveLong(Object value, String field) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        try {
            long result = number.longValueExact();
            if (result < 1) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be a positive integer", exception);
        }
    }

    private static Long optionalPositiveLong(Object value) {
        try {
            return positiveLong(value, "Payload revision");
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\0') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must be non-blank single-line text");
        }
        return CanonicalJson.requireNfc(text);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record PathIdentity(String type, String id) {
    }

    private record Identity(String type, String id) {
        private String canonicalPath() {
            return CANONICAL_ROOT + type + "/" + id + ".json";
        }

        private String canonicalText() {
            return type + "/" + id;
        }
    }

    private record Candidate(SourceFile source, Identity identity, long revision, String mutationId, byte[] canonicalBytes, String canonicalHash) {
        private static final Comparator<Candidate> NEWEST_FIRST = Comparator.comparingLong(Candidate::revision).reversed()
            .thenComparing(Candidate::mutationId, Comparator.reverseOrder())
            .thenComparing(Candidate::canonicalHash, Comparator.reverseOrder())
            .thenComparing(candidate -> candidate.source().relativePath());

        private Candidate {
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }

    private record Payload(Set<Identity> identities, Long revision, String aliasConflict) {
        private static Payload exact(Identity identity, Long revision) {
            return new Payload(Set.of(identity), revision, null);
        }

        private boolean matches(Identity identity) {
            return identities.contains(identity);
        }
    }
}
