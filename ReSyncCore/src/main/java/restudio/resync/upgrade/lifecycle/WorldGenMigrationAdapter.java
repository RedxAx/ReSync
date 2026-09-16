package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
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
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

public final class WorldGenMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String ID = "resync.lifecycle.worldgen-v1";
    public static final String OWNER = ProductionPersistenceOwners.FLOW_ASSETS;
    public static final String ROOT = "assets/WorldGen/";
    public static final String TYPE = "worldgen";
    public static final String INVALID_CODE = "MIGRATION.WORLDGEN_ASSET_INVALID";
    public static final String PATH_CODE = "MIGRATION.WORLDGEN_ASSET_PATH_INVALID";
    public static final String ID_COLLISION_CODE = "MIGRATION.WORLDGEN_ASSET_ID_COLLISION";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,96}");
    private static final Set<String> TYPE_FIELDS = Set.of("resourceType", "type");
    private static final Set<String> REVISION_FIELDS = Set.of("assetRevision", "resourceRevision", "revision");
    private static final Set<String> MUTATION_FIELDS = Set.of("assetMutationId", "resourceMutationId", "mutationId");

    @Override
    public String adapterId() {
        return ID;
    }

    @Override
    public Adaptation adapt(Input input) throws IOException {
        Objects.requireNonNull(input, "input");
        List<Claim> claims = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        Map<String, List<SourceFile>> pathAliases = new TreeMap<>();
        for (SourceFile source : input.files()) {
            if (candidatePath(source.relativePath())) {
                pathAliases.computeIfAbsent(source.relativePath().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(source);
            }
        }
        pathAliases.values().removeIf(group -> group.size() < 2);
        pathAliases.values().forEach(group -> group.sort(Comparator.comparing(SourceFile::relativePath)));
        for (SourceFile source : input.files()) {
            if (!candidatePath(source.relativePath())) {
                continue;
            }
            claims.add(new Claim(source.relativePath(), OWNER));
            if (!source.owner().equals(OWNER)) {
                continue;
            }
            List<SourceFile> aliasGroup = pathAliases.get(source.relativePath().toLowerCase(Locale.ROOT));
            if (aliasGroup != null) {
                List<String> references = aliasGroup.stream().map(SourceFile::relativePath).toList();
                quarantine.add(quarantine(source, ID_COLLISION_CODE,
                    "WorldGen asset metadata paths alias on case-insensitive file systems.", references));
                continue;
            }
            try {
                String id = pathId(source.relativePath());
                Map<String, Object> value = object(CanonicalJson.parseOpaque(input.read(source)), source.relativePath());
                validate(value, source.relativePath(), id);
                candidates.add(new Candidate(source, id));
            } catch (IllegalArgumentException exception) {
                String code = pathCode(source.relativePath()) ? PATH_CODE : INVALID_CODE;
                quarantine.add(quarantine(source, code, message(exception), List.of(source.relativePath())));
            }
        }
        addIdentityCollisions(candidates, quarantine);
        return new Adaptation(claims, List.of(), quarantine);
    }

    private static void addIdentityCollisions(List<Candidate> candidates, List<QuarantineRecord> quarantine) {
        Map<String, List<Candidate>> byFoldedId = new TreeMap<>();
        for (Candidate candidate : candidates) {
            byFoldedId.computeIfAbsent(candidate.id().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(candidate);
        }
        for (List<Candidate> group : byFoldedId.values()) {
            Set<String> identities = new HashSet<>();
            for (Candidate candidate : group) {
                identities.add(candidate.id());
            }
            if (group.size() < 2 || identities.size() < 2) {
                continue;
            }
            List<String> references = group.stream().map(candidate -> candidate.source().relativePath()).sorted().toList();
            for (Candidate candidate : group) {
                quarantine.add(quarantine(candidate.source(), ID_COLLISION_CODE,
                    "WorldGen asset IDs differ only by case and cannot map to one deterministic path.", references));
            }
        }
    }

    private static void validate(Map<String, Object> value, String sourcePath, String fileId) {
        String type = requiredText(value, "resource type", TYPE_FIELDS);
        if (!TYPE.equals(type)) {
            throw new IllegalArgumentException("WorldGen asset resource type must be " + TYPE);
        }
        String id = requiredText(value, "id", Set.of("id"));
        requireId(id, "WorldGen asset id");
        if (!id.equals(fileId)) {
            throw new IllegalArgumentException("WorldGen asset path and declared ID do not agree");
        }
        positiveLong(value, "revision", REVISION_FIELDS);
        requiredOpaque(value, "mutation identity", MUTATION_FIELDS);
        optionalFormatVersion(value);
        optionalHash(value, "asset hash", Set.of("assetHash"));
        optionalHash(value, "resource hash", Set.of("resourceHash", "payloadHash"));
        optionalHash(value, "prior payload hash", Set.of("priorPayloadHash"));
        optionalDeletionMarker(value);
        optionalPath(value, "original logical path", Set.of("originalLogicalPath", "originalPath"));
        optionalPath(value, "canonical future path", Set.of("canonicalFuturePath", "path"));
        if (sourcePath.isBlank()) {
            throw new IllegalArgumentException("WorldGen asset source path is required");
        }
    }

    private static String pathId(String path) {
        if (!path.startsWith(ROOT)) {
            throw new IllegalArgumentException("WorldGen asset path must use " + ROOT);
        }
        String fileName = path.substring(ROOT.length());
        if (fileName.isBlank() || fileName.indexOf('/') >= 0 || !fileName.endsWith(".json")) {
            throw new IllegalArgumentException("WorldGen asset path must be assets/WorldGen/{id}.json");
        }
        String id = fileName.substring(0, fileName.length() - ".json".length());
        requireId(id, "WorldGen asset file ID");
        return id;
    }

    private static boolean candidatePath(String path) {
        return path.startsWith(ROOT) || path.toLowerCase(Locale.ROOT).startsWith(ROOT.toLowerCase(Locale.ROOT));
    }

    private static boolean pathCode(String path) {
        try {
            pathId(path);
            return false;
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(field + " must contain a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " contains a non-text field name");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String requiredText(Map<String, Object> value, String field, Set<String> aliases) {
        String result = aliasText(value, field, aliases);
        if (result == null) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return result;
    }

    private static String requiredOpaque(Map<String, Object> value, String field, Set<String> aliases) {
        String result = requiredText(value, field, aliases);
        if (!result.equals(result.strip()) || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is not a valid opaque value");
        }
        return result;
    }

    private static String aliasText(Map<String, Object> value, String field, Set<String> aliases) {
        String result = null;
        String selected = null;
        for (String alias : aliases) {
            if (!value.containsKey(alias)) {
                continue;
            }
            Object raw = value.get(alias);
            if (!(raw instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(field + " must be non-blank text");
            }
            text = CanonicalJson.requireNfc(text);
            if (result != null && !result.equals(text)) {
                throw new IllegalArgumentException(field + " aliases disagree");
            }
            result = text;
            selected = alias;
        }
        if (result != null) {
            return result;
        }
        for (String key : value.keySet()) {
            if (aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(key)) && !key.equals(selected)) {
                throw new IllegalArgumentException(field + " contains an unsupported case alias");
            }
        }
        return null;
    }

    private static long positiveLong(Map<String, Object> value, String field, Set<String> aliases) {
        Long result = null;
        for (String alias : aliases) {
            if (!value.containsKey(alias)) {
                continue;
            }
            Object raw = value.get(alias);
            if (!(raw instanceof BigDecimal number)) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            long parsed;
            try {
                parsed = number.longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(field + " must be an integer", exception);
            }
            if (parsed < 1L || result != null && result != parsed) {
                throw new IllegalArgumentException(field + " aliases disagree or are not positive");
            }
            result = parsed;
        }
        if (result == null) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return result;
    }

    private static void optionalFormatVersion(Map<String, Object> value) {
        if (!value.containsKey("assetFormatVersion")) {
            return;
        }
        Object raw = value.get("assetFormatVersion");
        if (!(raw instanceof BigDecimal number)) {
            throw new IllegalArgumentException("asset format version must be an integer");
        }
        try {
            if (number.longValueExact() < 1L) {
                throw new IllegalArgumentException("asset format version must be positive");
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("asset format version must be an integer", exception);
        }
    }

    private static void optionalHash(Map<String, Object> value, String field, Set<String> aliases) {
        String hash = aliasText(value, field, aliases);
        if (hash != null && !hash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
    }

    private static void optionalDeletionMarker(Map<String, Object> value) {
        String field = "deleted";
        if (!value.containsKey(field)) {
            return;
        }
        Object raw = value.get(field);
        if (!(raw instanceof Boolean flag) || flag) {
            throw new IllegalArgumentException(field + " must be false for a live asset");
        }
    }

    private static void optionalPath(Map<String, Object> value, String field, Set<String> aliases) {
        String path = aliasText(value, field, aliases);
        if (path == null) {
            return;
        }
        MigrationPaths.requireRelative(path);
        if (!Normalizer.normalize(path, Normalizer.Form.NFC).equals(path)) {
            throw new IllegalArgumentException(field + " must be NFC normalized");
        }
    }

    private static void requireId(String value, String field) {
        if (!CanonicalJson.requireNfc(value).equals(value) || !SAFE_ID.matcher(value).matches()
            || value.equals(".") || value.equals("..") || value.contains("..") || value.endsWith(".json")) {
            throw new IllegalArgumentException(field + " is unsafe");
        }
    }

    private static QuarantineRecord quarantine(SourceFile source, String code, String reason, List<String> references) {
        String recordId = "worldgen-" + CanonicalJson.sha256("migration.worldgen-quarantine",
            List.of(code, source.relativePath(), source.sha256(), reason)).substring(0, 24);
        return new QuarantineRecord(recordId, code, source.relativePath(), reason, references,
            "Repair the typed WorldGen identity and rerun the lifecycle migration.", source.sha256());
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
            ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record Candidate(SourceFile source, String id) {
    }
}
