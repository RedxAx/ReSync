package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

public final class JsonAssetMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String ID = "resync.lifecycle.json-assets-v1";
    public static final String OWNER = ProductionPersistenceOwners.FLOW_ASSETS;
    public static final String INVALID_CODE = "MIGRATION.JSON_ASSET_INVALID";
    public static final String CONFLICT_CODE = "MIGRATION.JSON_ASSET_CONFLICT";
    public static final String TOMBSTONE_CONFLICT_CODE = "MIGRATION.JSON_ASSET_TOMBSTONE_CONFLICT";

    private static final String ASSETS_PREFIX = "assets/";
    private static final String TOMBSTONE_PREFIX = "assets/.tombstones/";
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,96}");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
        "chat", "motd_profile", "message_rule", "recipe_definition", "text_template",
        "advancement_tree", "dialog", "trade_profile", "npc_definition", "loot_table");
    private static final List<ResourceSpec> RESOURCE_SPECS = resourceSpecs();
    private static final Map<String, ResourceSpec> RESOURCE_BY_TYPE = RESOURCE_SPECS.stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceSpec::type, value -> value));
    private static final Map<String, String> LEGACY_FOLDERS = Map.of(
        "chat", "chat",
        "motd_profile", "motd-profiles",
        "message_rule", "message-rules",
        "recipe_definition", "recipes",
        "text_template", "text-templates",
        "advancement_tree", "advancement-trees",
        "dialog", "dialogs",
        "trade_profile", "trade-profiles",
        "npc_definition", "npcs",
        "loot_table", "loot-tables");

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
        Map<String, SourceFile> paths = new HashMap<>();
        Map<String, List<Candidate>> candidates = new TreeMap<>();
        Map<String, Tombstone> tombstones = new TreeMap<>();
        Map<String, List<SourceFile>> pathAliases = new TreeMap<>();

        for (SourceFile source : input.files()) {
            if (candidatePath(source.relativePath()) != null) {
                pathAliases.computeIfAbsent(source.relativePath().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(source);
            }
        }
        pathAliases.values().removeIf(group -> group.size() < 2);
        pathAliases.values().forEach(group -> group.sort(Comparator.comparing(SourceFile::relativePath)));

        for (SourceFile source : input.files()) {
            paths.putIfAbsent(source.relativePath().toLowerCase(Locale.ROOT), source);
            TombstonePath tombstonePath = tombstonePath(source.relativePath());
            if (tombstonePath != null && source.owner().equals(OWNER)) {
                try {
                    Tombstone tombstone = readTombstone(input, source, tombstonePath);
                    tombstones.put(tombstone.identity().folded(), tombstone);
                } catch (IllegalArgumentException exception) {
                    continue;
                }
            }
            CandidatePath candidatePath = candidatePath(source.relativePath());
            if (candidatePath == null) {
                continue;
            }
            claims.add(new Claim(source.relativePath(), OWNER));
            if (!source.owner().equals(OWNER)) {
                continue;
            }
            List<SourceFile> aliasGroup = pathAliases.get(source.relativePath().toLowerCase(Locale.ROOT));
            if (aliasGroup != null) {
                List<String> references = aliasGroup.stream().map(SourceFile::relativePath).toList();
                boolean invalidCanonicalCase = candidatePath.canonical() && !canonicalRootCase(candidatePath);
                quarantine.add(quarantine(source, invalidCanonicalCase ? INVALID_CODE : CONFLICT_CODE,
                    invalidCanonicalCase
                        ? "JSON asset path does not use the authoritative folder casing."
                        : "Multiple JSON asset metadata paths alias on case-insensitive file systems.",
                    references, "Retain exactly one typed resource path using its authoritative casing."));
                continue;
            }
            if (!source.relativePath().toLowerCase(Locale.ROOT).endsWith(".json")) {
                quarantine.add(quarantine(source, INVALID_CODE, "JSON asset files must use the .json extension.",
                    List.of(source.relativePath()), "Retain only typed JSON resource files in the managed resource folders."));
                continue;
            }
            try {
                byte[] bytes = input.read(source);
                ParsedAsset parsed = parseAsset(source, candidatePath, bytes);
                candidates.computeIfAbsent(parsed.identity().folded(), ignored -> new ArrayList<>())
                    .add(new Candidate(source, parsed));
            } catch (IllegalArgumentException exception) {
                quarantine.add(quarantine(source, INVALID_CODE, reason(exception), List.of(source.relativePath()),
                    "Repair the typed resource identity, canonical folder, revision, and mutation lineage before activation."));
            }
        }

        List<Candidate> accepted = new ArrayList<>();
        for (List<Candidate> group : candidates.values()) {
            group.sort(Comparator.comparing(candidate -> candidate.source().relativePath()));
            if (group.size() > 1) {
                List<String> references = group.stream().map(candidate -> candidate.source().relativePath()).toList();
                for (Candidate candidate : group) {
                    quarantine.add(quarantine(candidate.source(), CONFLICT_CODE,
                        "Multiple JSON asset paths resolve to the same typed resource identity.", references,
                        "Retain exactly one typed resource path and quarantine the conflicting aliases."));
                }
                continue;
            }
            Candidate candidate = group.getFirst();
            Tombstone tombstone = tombstones.get(candidate.parsed().identity().folded());
            if (tombstone != null && tombstone.revision() >= candidate.parsed().revision()) {
                quarantine.add(quarantine(candidate.source(), TOMBSTONE_CONFLICT_CODE,
                    "A tombstone at an equal or newer revision cannot coexist with this live JSON asset.",
                    List.of(candidate.source().relativePath(), tombstone.source().relativePath()),
                    "Resolve the typed live and tombstone lineage before activation."));
                continue;
            }
            accepted.add(candidate);
        }

        for (Candidate candidate : accepted) {
            String target = candidate.parsed().targetPath();
            SourceFile targetSource = paths.get(target.toLowerCase(Locale.ROOT));
            if (targetSource != null && !targetSource.relativePath().equals(candidate.source().relativePath())) {
                quarantine.add(quarantine(candidate.source(), CONFLICT_CODE,
                    "The deterministic canonical JSON asset path is occupied by another source.",
                    List.of(candidate.source().relativePath(), targetSource.relativePath(), target),
                    "Retain exactly one source for the canonical typed resource path."));
                continue;
            }
            List<String> collisions = new ArrayList<>();
            for (Candidate other : accepted) {
                if (other == candidate) {
                    continue;
                }
                if (hierarchicalCollision(target, other.parsed().targetPath())) {
                    collisions.add(other.source().relativePath());
                }
            }
            for (SourceFile other : input.files()) {
                if (!other.relativePath().equals(candidate.source().relativePath())
                    && hierarchicalCollision(target, other.relativePath())) {
                    collisions.add(other.relativePath());
                }
            }
            if (!collisions.isEmpty()) {
                List<String> references = new ArrayList<>(collisions);
                references.add(candidate.source().relativePath());
                quarantine.add(quarantine(candidate.source(), CONFLICT_CODE,
                    "The deterministic canonical JSON asset path has a file or directory hierarchy conflict.",
                    references, "Retain one non-overlapping canonical typed resource path."));
                continue;
            }
            if (!candidate.parsed().canonical()) {
                changes.add(new Change("move", candidate.source().relativePath(), target, candidate.parsed().bytes()));
            }
        }
        return new Adaptation(claims, changes, quarantine);
    }

    private static ParsedAsset parseAsset(SourceFile source, CandidatePath path, byte[] bytes) {
        Object parsed = CanonicalJson.parseOpaque(bytes);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("JSON resource payload must be an object");
        }
        Map<String, Object> value = object(raw);
        String type = resourceType(value, "resource type");
        if (!path.spec().type().equals(type)) {
            throw new IllegalArgumentException("JSON resource type does not match its authoritative folder: " + source.relativePath());
        }
        String id = text(value.get("id"), "JSON resource ID");
        if (!path.id().equals(id) || !validId(id)) {
            throw new IllegalArgumentException("JSON resource ID does not match its file name: " + source.relativePath());
        }
        long revision = lineageLong(value, "resource revision", "assetRevision", "resourceRevision", "revision");
        String mutation = lineageText(value, "resource mutation", "assetMutationId", "resourceMutationId", "mutationId");
        if (deleted(value)) {
            throw new IllegalArgumentException("Live JSON resource cannot be marked deleted");
        }
        String folder = folder(value, path.spec());
        if (path.canonical()) {
            if (!path.folder().equals(folder)) {
                throw new IllegalArgumentException("JSON resource folder does not match its canonical path: " + source.relativePath());
            }
        }
        String target = targetPath(folder, id);
        if (path.canonical() && !target.equals(source.relativePath())) {
            throw new IllegalArgumentException("JSON resource path is not its deterministic canonical path: " + source.relativePath());
        }
        return new ParsedAsset(new Identity(type, id), revision, mutation, folder, target, bytes, path.canonical());
    }

    private static Tombstone readTombstone(Input input, SourceFile source, TombstonePath path) throws IOException {
        Object parsed = CanonicalJson.parseOpaque(input.read(source));
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("JSON tombstone must be an object");
        }
        Map<String, Object> value = object(raw);
        String type = resourceType(value, "tombstone resource type");
        String id = text(value.get("id"), "Tombstone resource ID");
        if (!path.type().equals(type) || !path.id().equals(id)) {
            throw new IllegalArgumentException("Tombstone path and identity do not agree");
        }
        ResourceSpec spec = RESOURCE_BY_TYPE.get(type);
        if (spec == null || !validId(id) || !deleted(value)) {
            throw new IllegalArgumentException("Tombstone is not a JSON resource tombstone");
        }
        long revision = lineageLong(value, "tombstone revision", "assetRevision", "resourceRevision", "revision");
        String mutation = lineageText(value, "tombstone mutation", "assetMutationId", "resourceMutationId", "mutationId");
        return new Tombstone(new Identity(type, id), revision, mutation, source);
    }

    private static CandidatePath candidatePath(String relativePath) {
        String path = MigrationPaths.requireRelative(relativePath);
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith(ASSETS_PREFIX)) {
            String assetPath = path.substring(ASSETS_PREFIX.length());
            int separator = assetPath.lastIndexOf('/');
            if (separator <= 0) {
                return null;
            }
            String folder = assetPath.substring(0, separator);
            String file = assetPath.substring(separator + 1);
            String id = stripJson(file);
            if (id.isBlank()) {
                return null;
            }
            ResourceSpec spec = specForFolder(folder);
            return spec == null ? null : new CandidatePath(spec, folder, id, true);
        }
        for (ResourceSpec spec : RESOURCE_SPECS) {
            String legacy = LEGACY_FOLDERS.get(spec.type());
            if (legacy != null) {
                CandidatePath candidate = directLegacy(path, legacy, spec);
                if (candidate != null) {
                    return candidate;
                }
            }
            CandidatePath canonicalRoot = directLegacy(path, spec.folder(), spec);
            if (canonicalRoot != null) {
                return canonicalRoot;
            }
        }
        return null;
    }

    private static CandidatePath directLegacy(String path, String root, ResourceSpec spec) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String lowerRoot = root.toLowerCase(Locale.ROOT);
        String prefix = lowerRoot + "/";
        if (!lowerPath.startsWith(prefix)) {
            return null;
        }
        String suffix = path.substring(prefix.length());
        if (suffix.indexOf('/') >= 0) {
            return null;
        }
        String id = stripJson(suffix);
        return id.isBlank() ? null : new CandidatePath(spec, spec.folder(), id, false);
    }

    private static ResourceSpec specForFolder(String folder) {
        String lower = folder.toLowerCase(Locale.ROOT);
        return RESOURCE_SPECS.stream()
            .filter(spec -> lower.equals(spec.folder().toLowerCase(Locale.ROOT))
                || lower.startsWith(spec.folder().toLowerCase(Locale.ROOT) + "/"))
            .findFirst().orElse(null);
    }

    private static boolean canonicalRootCase(CandidatePath path) {
        return path.folder().equals(path.spec().folder()) || path.folder().startsWith(path.spec().folder() + "/");
    }

    private static TombstonePath tombstonePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(TOMBSTONE_PREFIX)) {
            return null;
        }
        String suffix = path.substring(TOMBSTONE_PREFIX.length());
        String[] parts = suffix.split("/", -1);
        if (parts.length != 2) {
            return null;
        }
        String id = stripJson(parts[1]);
        return id.isBlank() ? null : new TombstonePath(parts[0], id);
    }

    private static String folder(Map<String, Object> value, ResourceSpec spec) {
        Object folder = value.get("folder");
        if (folder == null) {
            return spec.folder();
        }
        if (!(folder instanceof String result)) {
            throw new IllegalArgumentException("JSON resource folder must be text");
        }
        if (result.isBlank()) {
            return spec.folder();
        }
        String normalized = MigrationPaths.requireRelative(result);
        String lower = normalized.toLowerCase(Locale.ROOT);
        String expected = spec.folder().toLowerCase(Locale.ROOT);
        if (!lower.equals(expected) && !lower.startsWith(expected + "/")) {
            throw new IllegalArgumentException("JSON resource folder is outside its authoritative protocol folder");
        }
        if (!normalized.equals(spec.folder()) && !normalized.startsWith(spec.folder() + "/")) {
            throw new IllegalArgumentException("JSON resource folder uses a case-alias of its authoritative protocol folder");
        }
        return normalized;
    }

    private static String targetPath(String folder, String id) {
        return ASSETS_PREFIX + folder + "/" + id + ".json";
    }

    private static Map<String, Object> object(Map<?, ?> raw) {
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("JSON resource contains a non-string field name");
            }
            value.put(name, item);
        });
        return value;
    }

    private static String lineageText(Map<String, Object> value, String field, String... keys) {
        String result = null;
        for (String key : keys) {
            if (!value.containsKey(key) || value.get(key) == null) {
                continue;
            }
            String current = text(value.get(key), field);
            if (result != null && !result.equals(current)) {
                throw new IllegalArgumentException("JSON resource lineage fields disagree for " + field);
            }
            result = current;
        }
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("JSON resource " + field + " is required");
        }
        return result;
    }

    private static String resourceType(Map<String, Object> value, String field) {
        return value.containsKey("resourceType")
            ? lineageText(value, field, "resourceType")
            : lineageText(value, field, "type");
    }

    private static long lineageLong(Map<String, Object> value, String field, String... keys) {
        Long result = null;
        for (String key : keys) {
            if (!value.containsKey(key) || value.get(key) == null) {
                continue;
            }
            Object raw = value.get(key);
            if (!(raw instanceof BigDecimal number)) {
                throw new IllegalArgumentException("JSON resource " + field + " must be an integer");
            }
            long current;
            try {
                current = number.longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("JSON resource " + field + " must be an integer", exception);
            }
            if (current < 1L) {
                throw new IllegalArgumentException("JSON resource " + field + " must be positive");
            }
            if (result != null && result.longValue() != current) {
                throw new IllegalArgumentException("JSON resource lineage fields disagree for " + field);
            }
            result = current;
        }
        if (result == null) {
            throw new IllegalArgumentException("JSON resource " + field + " is required");
        }
        return result;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\u0000') >= 0
            || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must be non-blank single-line text");
        }
        return text;
    }

    private static boolean deleted(Map<String, Object> value) {
        Object deleted = value.get("deleted");
        if (deleted == null) {
            return false;
        }
        if (!(deleted instanceof Boolean flag)) {
            throw new IllegalArgumentException("JSON resource deletion marker must be boolean");
        }
        return flag;
    }

    private static String stripJson(String value) {
        if (value == null || !value.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return "";
        }
        String id = value.substring(0, value.length() - 5);
        return validId(id) ? id : "";
    }

    private static boolean validId(String value) {
        return value != null && ID_PATTERN.matcher(value).matches() && value.equals(value.strip())
            && !value.equals(".") && !value.equals("..") && !value.contains("..") && !value.endsWith(".json");
    }

    private static boolean hierarchicalCollision(String first, String second) {
        String left = first.toLowerCase(Locale.ROOT);
        String right = second.toLowerCase(Locale.ROOT);
        return left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/");
    }

    private static List<ResourceSpec> resourceSpecs() {
        List<ResourceSpec> result = new ArrayList<>();
        for (ReSyncProtocolContract.ResourceContract contract : ReSyncProtocolContract.RESOURCE_CONTRACTS) {
            if (contract.jsonStorageSupported() && SUPPORTED_TYPES.contains(contract.typeId())) {
                result.add(new ResourceSpec(contract.typeId(), contract.defaultFolder()));
            }
        }
        result.sort(Comparator.comparing(ResourceSpec::type));
        return List.copyOf(result);
    }

    private static QuarantineRecord quarantine(SourceFile source, String code, String reason,
                                               List<String> references, String action) {
        String recordId = "json-asset-" + sha256(code + "\n" + source.relativePath() + "\n"
            + source.sha256() + "\n" + String.join("\n", references)).substring(0, 24);
        return new QuarantineRecord(recordId, code, source.relativePath(), reason, references, action, source.sha256());
    }

    private static String reason(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
            ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ResourceSpec(String type, String folder) {
        private ResourceSpec {
            type = Objects.requireNonNull(type, "type");
            folder = MigrationPaths.requireRelative(folder);
        }
    }

    private record CandidatePath(ResourceSpec spec, String folder, String id, boolean canonical) {
    }

    private record ParsedAsset(Identity identity, long revision, String mutation, String folder,
                               String targetPath, byte[] bytes, boolean canonical) {
        private ParsedAsset {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record Candidate(SourceFile source, ParsedAsset parsed) {
    }

    private record Identity(String type, String id) {
        private String folded() {
            return type.toLowerCase(Locale.ROOT) + "\u0000" + id.toLowerCase(Locale.ROOT);
        }
    }

    private record TombstonePath(String type, String id) {
    }

    private record Tombstone(Identity identity, long revision, String mutation, SourceFile source) {
    }
}
