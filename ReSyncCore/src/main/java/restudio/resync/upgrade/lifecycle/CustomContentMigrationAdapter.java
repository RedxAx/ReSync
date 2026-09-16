package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

public final class CustomContentMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String ID = "resync.lifecycle.custom-content-v1";
    public static final String OWNER = ProductionPersistenceOwners.FLOW_ASSETS;
    public static final String RESOURCE_TYPE = "custom_content";
    public static final String CODE_PATH_INVALID = "MIGRATION.CUSTOM_CONTENT_PATH_INVALID";
    public static final String CODE_PAYLOAD_INVALID = "MIGRATION.CUSTOM_CONTENT_PAYLOAD_INVALID";
    public static final String CODE_LINEAGE_INVALID = "MIGRATION.CUSTOM_CONTENT_LINEAGE_INVALID";
    public static final String CODE_TOMBSTONE_INVALID = "MIGRATION.CUSTOM_CONTENT_TOMBSTONE_INVALID";
    public static final String CODE_ALIAS_CONFLICT = "MIGRATION.CUSTOM_CONTENT_ALIAS_CONFLICT";
    public static final String CODE_CASE_ALIAS = "MIGRATION.CUSTOM_CONTENT_CASE_ALIAS";

    private static final String ASSET_PREFIX = "assets/Content/";
    private static final String ALIAS_PREFIX = "custom_content__";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,96}");
    private static final Map<String, String> FOLDER_TYPES = Map.of(
        "Items", "item",
        "Armor", "armor",
        "Blocks", "block",
        "Projectiles", "projectile");

    @Override
    public String adapterId() {
        return ID;
    }

    @Override
    public Adaptation adapt(Input input) throws IOException {
        Objects.requireNonNull(input, "input");
        List<SourceFile> sources = input.files().stream()
            .filter(source -> isCandidatePath(source.relativePath()))
            .sorted(Comparator.comparing(SourceFile::relativePath))
            .toList();
        List<Claim> claims = sources.stream().map(source -> new Claim(source.relativePath(), OWNER)).toList();
        if (sources.isEmpty() || sources.stream().anyMatch(source -> !OWNER.equals(source.owner()))) {
            return Adaptation.claimed(claims);
        }

        List<QuarantineRecord> quarantine = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        Set<String> blockedSources = new HashSet<>();
        detectCaseAliasedPaths(sources, quarantine, blockedSources);
        for (SourceFile source : sources) {
            if (blockedSources.contains(source.relativePath())) {
                continue;
            }
            PathContract path = path(source.relativePath());
            if (path.failure() != null) {
                quarantine.add(quarantine(source, CODE_PATH_INVALID, path.failure(), List.of(source.relativePath()),
                    "Place the custom content in the exact typed Content folder with an id-only JSON filename."));
                blockedSources.add(source.relativePath());
                continue;
            }
            try {
                byte[] bytes = input.read(source);
                Document document = document(path, bytes);
                candidates.add(new Candidate(source, document.path(), document, bytes));
            } catch (IllegalArgumentException exception) {
                String code = exception instanceof TombstoneException ? CODE_TOMBSTONE_INVALID
                    : exception instanceof LineageException ? CODE_LINEAGE_INVALID : CODE_PAYLOAD_INVALID;
                quarantine.add(quarantine(source, code, exception.getMessage(), List.of(path.canonicalPath()),
                    "Restore the exact custom content subtype, key, revision, opaque mutation identity, and graph payload."));
                blockedSources.add(source.relativePath());
            }
        }

        Map<String, List<Candidate>> identities = new TreeMap<>();
        for (Candidate candidate : candidates) {
            identities.computeIfAbsent(fold(candidate.document().id()), ignored -> new ArrayList<>()).add(candidate);
        }
        for (List<Candidate> group : identities.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<String> references = group.stream().map(candidate -> candidate.source().relativePath()).sorted().toList();
            for (Candidate candidate : group) {
                if (blockedSources.add(candidate.source().relativePath())) {
                    quarantine.add(quarantine(candidate.source(), CODE_ALIAS_CONFLICT,
                        "Multiple custom content paths resolve to the same case-insensitive content key.", references,
                        "Retain one authoritative content key and remove or quarantine every alias."));
                }
            }
        }

        Map<String, Candidate> canonicalPaths = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (!blockedSources.contains(candidate.source().relativePath())) {
                canonicalPaths.put(candidate.path().canonicalPath(), candidate);
            }
        }
        List<Change> changes = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (blockedSources.contains(candidate.source().relativePath()) || !candidate.path().alias()) {
                continue;
            }
            Candidate target = canonicalPaths.get(candidate.path().canonicalPath());
            SourceFile targetSource = input.file(candidate.path().canonicalPath()).orElse(null);
            if (target != null && !target.source().relativePath().equals(candidate.source().relativePath())
                || targetSource != null && !targetSource.relativePath().equals(candidate.source().relativePath())) {
                if (blockedSources.add(candidate.source().relativePath())) {
                    quarantine.add(quarantine(candidate.source(), CODE_ALIAS_CONFLICT,
                        "A canonical custom content path already exists for this alias.",
                        List.of(candidate.source().relativePath(), candidate.path().canonicalPath()),
                        "Retain one exact canonical content path and quarantine the conflicting alias."));
                }
                continue;
            }
            changes.add(new Change("move", candidate.source().relativePath(),
                candidate.path().canonicalPath(), candidate.bytes()));
        }
        return new Adaptation(claims, quarantine.isEmpty() ? changes : List.of(), quarantine);
    }

    private static void detectCaseAliasedPaths(List<SourceFile> sources, List<QuarantineRecord> quarantine,
                                               Set<String> blockedSources) {
        Map<String, List<SourceFile>> byPath = new TreeMap<>();
        for (SourceFile source : sources) {
            byPath.computeIfAbsent(fold(source.relativePath()), ignored -> new ArrayList<>()).add(source);
        }
        for (List<SourceFile> group : byPath.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<String> references = group.stream().map(SourceFile::relativePath).sorted().toList();
            for (SourceFile source : group) {
                if (blockedSources.add(source.relativePath())) {
                    quarantine.add(quarantine(source, CODE_CASE_ALIAS,
                        "Custom content paths differ only by case and cannot have one authoritative file.", references,
                        "Retain one exact path spelling and quarantine the case-alias duplicate."));
                }
            }
        }
    }

    private static boolean isCandidatePath(String relativePath) {
        return relativePath != null && relativePath.toLowerCase(Locale.ROOT).startsWith(ASSET_PREFIX.toLowerCase(Locale.ROOT));
    }

    private static PathContract path(String relativePath) {
        String[] segments = relativePath.split("/", -1);
        if (segments.length != 4 || !segments[0].equals("assets") || !segments[1].equals("Content")) {
            return PathContract.invalid("The custom content path must use assets/Content/{Items|Armor|Blocks|Projectiles}/{id}.json.");
        }
        String subtype = FOLDER_TYPES.get(segments[2]);
        if (subtype == null) {
            return PathContract.invalid("The custom content folder is not an authoritative subtype folder: " + segments[2]);
        }
        String fileName = segments[3];
        if (!fileName.endsWith(".json") || fileName.length() == ".json".length()) {
            return PathContract.invalid("Custom content files must be regular id-only JSON files.");
        }
        String stem = fileName.substring(0, fileName.length() - ".json".length());
        if (!SAFE_ID.matcher(stem).matches() || stem.equals(".") || stem.equals("..") || stem.contains("..")) {
            return PathContract.invalid("Custom content key is unsafe: " + stem);
        }
        boolean aliasCandidate = stem.startsWith(ALIAS_PREFIX) && stem.length() > ALIAS_PREFIX.length();
        String canonical = ASSET_PREFIX + segments[2] + "/" + stem + ".json";
        return new PathContract(relativePath, segments[2], subtype, stem, canonical, aliasCandidate, null);
    }

    private static Document document(PathContract path, byte[] bytes) {
        Object parsed = CanonicalJson.parseOpaque(bytes);
        Map<String, Object> value = object(parsed, "custom content");
        String resourceType = text(required(value, "resourceType"), "resourceType");
        if (!RESOURCE_TYPE.equals(resourceType)) {
            throw new IllegalArgumentException("Custom content resourceType must be " + RESOURCE_TYPE + ".");
        }
        String id = text(required(value, "id"), "id");
        PathContract boundPath = path.bind(id);
        String subtype = text(required(value, "type"), "type");
        if (!subtype.equals(boundPath.subtype())) {
            throw new IllegalArgumentException("Custom content path and subtype do not agree.");
        }
        if (value.containsKey("key") && !id.equals(text(value.get("key"), "key"))) {
            throw new IllegalArgumentException("Custom content key and id do not agree.");
        }
        if (value.containsKey("subtype") && !subtype.equals(text(value.get("subtype"), "subtype"))) {
            throw new IllegalArgumentException("Custom content subtype fields do not agree.");
        }
        if (value.containsKey("deleted") && !Boolean.FALSE.equals(value.get("deleted"))) {
            throw new TombstoneException("deleted custom content must be represented by a typed tombstone.");
        }
        long revision = revision(value);
        String mutation = mutation(value);
        String flowId = optionalText(value, "flowId");
        Object graphValue = value.get("graph");
        if (graphValue != null) {
            Map<String, Object> graph = object(graphValue, "graph");
            String graphId = optionalText(graph, "id");
            if (flowId != null && graphId != null && !flowId.equals(graphId)) {
                throw new IllegalArgumentException("Custom content flowId and graph id do not agree.");
            }
        }
        return new Document(boundPath, id, subtype, revision, mutation);
    }

    private static long revision(Map<String, Object> value) {
        Long result = null;
        for (String field : List.of("assetRevision", "resourceRevision", "revision")) {
            if (!value.containsKey(field)) {
                continue;
            }
            Object raw = value.get(field);
            if (!(raw instanceof BigDecimal number)) {
                throw new LineageException("Custom content " + field + " must be a positive integer.");
            }
            long current;
            try {
                current = number.longValueExact();
            } catch (ArithmeticException exception) {
                throw new LineageException("Custom content " + field + " must be a positive integer.", exception);
            }
            if (current < 1L || result != null && result != current) {
                throw new LineageException("Custom content revision fields do not agree.");
            }
            result = current;
        }
        if (result == null) {
            throw new LineageException("Custom content is missing its persisted revision.");
        }
        return result;
    }

    private static String mutation(Map<String, Object> value) {
        String result = null;
        for (String field : List.of("assetMutationId", "resourceMutationId", "mutationId")) {
            if (!value.containsKey(field)) {
                continue;
            }
            String current = opaqueText(value.get(field), field);
            if (result != null && !result.equals(current)) {
                throw new LineageException("Custom content mutation identity fields do not agree.");
            }
            result = current;
        }
        if (result == null) {
            throw new LineageException("Custom content is missing its persisted mutation identity.");
        }
        return result;
    }

    private static Object required(Map<String, Object> value, String field) {
        if (!value.containsKey(field) || value.get(field) == null) {
            throw new IllegalArgumentException("Custom content is missing " + field + ".");
        }
        return value.get(field);
    }

    private static String optionalText(Map<String, Object> value, String field) {
        return value.containsKey(field) ? text(value.get(field), field) : null;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())
            || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Custom content " + field + " must be non-blank single-line text.");
        }
        return CanonicalJson.requireNfc(text);
    }

    private static String opaqueText(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 512 || !text.equals(text.strip())
            || text.chars().anyMatch(Character::isISOControl)) {
            throw new LineageException("Custom content " + field + " must be non-blank single-line text.");
        }
        return text;
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Custom content " + field + " must be a JSON object.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Custom content " + field + " contains a non-string field name.");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String fold(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static QuarantineRecord quarantine(SourceFile source, String code, String reason,
                                               List<String> references, String action) {
        String recordId = "custom-content-" + CanonicalJson.sha256("migration.custom-content-quarantine",
            List.of(code, source.relativePath(), source.sha256())).substring(0, 24);
        return new QuarantineRecord(recordId, code, source.relativePath(), reason, references, action, source.sha256());
    }

    private record PathContract(String sourcePath, String folder, String subtype, String key, String canonicalPath,
                                boolean alias, String failure) {
        private static PathContract invalid(String failure) {
            return new PathContract("", "", "", "", "", false, failure);
        }

        private PathContract bind(String id) {
            String aliasKey = alias ? key.substring(ALIAS_PREFIX.length()) : key;
            if (!id.equals(key) && (!alias || !id.equals(aliasKey))) {
                throw new IllegalArgumentException("Custom content path and id do not agree.");
            }
            boolean resolvedAlias = alias && id.equals(aliasKey) && !id.equals(key);
            return new PathContract(sourcePath, folder, subtype, id, ASSET_PREFIX + folder + "/" + id + ".json",
                resolvedAlias, null);
        }
    }

    private record Document(PathContract path, String id, String subtype, long revision, String mutation) {
    }

    private record Candidate(SourceFile source, PathContract path, Document document, byte[] bytes) {
        private Candidate {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static final class LineageException extends IllegalArgumentException {
        private LineageException(String message) {
            super(message);
        }

        private LineageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class TombstoneException extends IllegalArgumentException {
        private TombstoneException(String message) {
            super(message);
        }
    }
}
