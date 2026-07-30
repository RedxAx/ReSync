package restudio.resync.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.resources.AssetFileFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class AssetIntegrityService {
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command");
    private static final Set<String> NON_FILE_TYPES = Set.of("world");
    private final Path assetsRoot;

    public AssetIntegrityService(Path assetsRoot) {
        this.assetsRoot = assetsRoot.toAbsolutePath().normalize();
    }

    public HealthReport scan(int recoveredTransactions) {
        List<Issue> issues = new ArrayList<>();
        List<ResourceIdentity> resources = new ArrayList<>();
        if (!Files.isDirectory(assetsRoot)) {
            issues.add(new Issue(Severity.CRITICAL, "ASSET_ROOT_MISSING", "", assetsRoot.toString(), "Asset storage is missing"));
            return report(issues, resources, recoveredTransactions);
        }
        try (Stream<Path> paths = Files.walk(assetsRoot)) {
            for (Path file : paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                if (isInternal(file) || file.equals(assetsRoot.resolve("project.json"))) {
                    continue;
                }
                inspect(file, resources, issues);
            }
        } catch (IOException failure) {
            issues.add(new Issue(Severity.CRITICAL, "ASSET_SCAN_FAILED", "", assetsRoot.toString(), failure.getMessage()));
        }
        Map<String, Path> metadataPaths = metadataPaths();
        detectDuplicates(resources, issues, metadataPaths);
        inspectMetadata(issues);
        return report(issues, resources, recoveredTransactions);
    }

    private void inspect(Path file, List<ResourceIdentity> resources, List<Issue> issues) {
        try {
            JsonElement parsed = JsonParser.parseString(StorageSafety.readUtf8(file));
            if (!parsed.isJsonObject()) {
                issues.add(issue(Severity.CRITICAL, "INVALID_RESOURCE", "", file, "Resource root is not an object"));
                return;
            }
            JsonObject object = parsed.getAsJsonObject();
            String type = text(object, AssetFileFormat.RESOURCE_TYPE);
            String id = text(object, "id");
            if (id.isBlank()) {
                id = AssetFileFormat.idFromIdOnlyFileName(file.getFileName().toString());
            }
            if (type.isBlank()) {
                issues.add(issue(Severity.WARNING, "MISSING_RESOURCE_TYPE", id, file, "Resource type is missing"));
            }
            if (id.isBlank()) {
                issues.add(issue(Severity.CRITICAL, "MISSING_RESOURCE_ID", "", file, "Resource id is missing"));
            }
            if (!AssetFileFormat.verify(file)) {
                issues.add(issue(Severity.CRITICAL, "HASH_MISMATCH", id, file, "Resource integrity check failed"));
            }
            resources.add(new ResourceIdentity(type, id, file, AssetFileFormat.readRevision(file), AssetFileFormat.readContentHash(file)));
        } catch (RuntimeException | IOException failure) {
            issues.add(issue(Severity.CRITICAL, "INVALID_JSON", "", file, failure.getMessage()));
        }
    }

    private void detectDuplicates(List<ResourceIdentity> resources, List<Issue> issues, Map<String, Path> metadataPaths) {
        Map<String, List<ResourceIdentity>> exact = new HashMap<>();
        Map<String, List<ResourceIdentity>> graphs = new HashMap<>();
        for (ResourceIdentity resource : resources) {
            if (resource.id().isBlank()) {
                continue;
            }
            exact.computeIfAbsent(key(resource.type(), resource.id()), ignored -> new ArrayList<>()).add(resource);
            if (GRAPH_TYPES.contains(resource.type())) {
                graphs.computeIfAbsent(resource.id(), ignored -> new ArrayList<>()).add(resource);
            }
        }
        for (Map.Entry<String, List<ResourceIdentity>> entry : exact.entrySet()) {
            List<ResourceIdentity> duplicates = entry.getValue();
            if (duplicates.size() > 1) {
                ResourceIdentity first = duplicates.getFirst();
                List<ResourceIdentity> canonical = duplicates.stream().filter(resource -> isCanonical(resource, metadataPaths.get(entry.getKey()))).toList();
                if (canonical.size() == 1) {
                    ResourceIdentity stale = duplicates.stream().filter(resource -> resource != canonical.getFirst()).findFirst().orElse(first);
                    issues.add(issue(Severity.WARNING, "ORPHANED_RESOURCE_COPY", stale.id(), stale.path(),
                        "An older " + stale.type() + " copy exists outside the project resource path"));
                } else {
                    issues.add(issue(Severity.CRITICAL, "DUPLICATE_IDENTITY", first.id(), first.path(), "Multiple " + first.type() + " resources use this id"));
                }
            }
        }
        for (Map.Entry<String, List<ResourceIdentity>> entry : graphs.entrySet()) {
            Set<String> types = new HashSet<>();
            entry.getValue().forEach(resource -> types.add(resource.type()));
            if (types.size() > 1) {
                List<ResourceIdentity> canonical = entry.getValue().stream()
                    .filter(resource -> isCanonical(resource, metadataPaths.get(key(resource.type(), resource.id())))).toList();
                Set<String> canonicalTypes = new HashSet<>();
                canonical.forEach(resource -> canonicalTypes.add(resource.type()));
                if (canonicalTypes.equals(types)) {
                    continue;
                }
                if (canonical.size() == 1) {
                    ResourceIdentity stale = entry.getValue().stream().filter(resource -> resource != canonical.getFirst()).findFirst().orElse(canonical.getFirst());
                    issues.add(issue(Severity.WARNING, "ORPHANED_GRAPH_COPY", stale.id(), stale.path(),
                        "An older graph copy uses a different resource type outside the project resource path"));
                } else {
                    issues.add(new Issue(Severity.CRITICAL, "AMBIGUOUS_GRAPH_IDENTITY", entry.getKey(), "",
                        "Graph id is declared as " + String.join(", ", types)));
                }
            }
        }
    }

    private void inspectMetadata(List<Issue> issues) {
        Path metadataFile = assetsRoot.resolve("project.json");
        if (!Files.isRegularFile(metadataFile)) {
            issues.add(issue(Severity.WARNING, "PROJECT_METADATA_MISSING", "project", metadataFile, "Project metadata is missing"));
            return;
        }
        try {
            JsonObject metadata = JsonParser.parseString(StorageSafety.readUtf8(metadataFile)).getAsJsonObject();
            JsonElement entries = metadata.get("resources");
            if (entries == null || !entries.isJsonArray()) {
                issues.add(issue(Severity.WARNING, "PROJECT_RESOURCES_MISSING", "project", metadataFile, "Project resource index is missing"));
                return;
            }
            Set<String> declared = new HashSet<>();
            for (JsonElement element : entries.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject resource = element.getAsJsonObject();
                String type = text(resource, "type");
                String id = text(resource, "id");
                String key = type + "\u0000" + id;
                if (!declared.add(key)) {
                    issues.add(issue(Severity.CRITICAL, "DUPLICATE_METADATA_IDENTITY", id, metadataFile, "Project metadata declares this resource more than once"));
                }
                if (NON_FILE_TYPES.contains(type)) {
                    continue;
                }
                Path expected = metadataPath(resource);
                if (expected == null || !Files.isRegularFile(expected) || !type.equals(AssetFileFormat.readResourceType(expected))) {
                    issues.add(issue(Severity.WARNING, "MISSING_RESOURCE_FILE", id, metadataFile, "Project metadata points to a missing " + type + " resource"));
                }
            }
        } catch (RuntimeException | IOException failure) {
            issues.add(issue(Severity.CRITICAL, "INVALID_PROJECT_METADATA", "project", metadataFile, failure.getMessage()));
        }
    }

    private Map<String, Path> metadataPaths() {
        Path metadataFile = assetsRoot.resolve("project.json");
        if (!Files.isRegularFile(metadataFile)) {
            return Map.of();
        }
        try {
            JsonObject metadata = JsonParser.parseString(StorageSafety.readUtf8(metadataFile)).getAsJsonObject();
            JsonElement entries = metadata.get("resources");
            if (entries == null || !entries.isJsonArray()) {
                return Map.of();
            }
            Map<String, Path> paths = new HashMap<>();
            for (JsonElement element : entries.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject resource = element.getAsJsonObject();
                Path path = metadataPath(resource);
                if (path != null) {
                    paths.putIfAbsent(key(text(resource, "type"), text(resource, "id")), path);
                }
            }
            return Map.copyOf(paths);
        } catch (RuntimeException | IOException failure) {
            return Map.of();
        }
    }

    private Path metadataPath(JsonObject resource) {
        String id = text(resource, "id");
        if (id.isBlank()) {
            return null;
        }
        String folder = text(resource, "path");
        Path directory = folder.isBlank() ? assetsRoot : assetsRoot.resolve(folder);
        Path path = directory.resolve(AssetFileFormat.idOnlyFileName(id)).toAbsolutePath().normalize();
        return path.startsWith(assetsRoot) ? path : null;
    }

    private boolean isCanonical(ResourceIdentity resource, Path expected) {
        return expected != null && resource.path().toAbsolutePath().normalize().equals(expected);
    }

    private String key(String type, String id) {
        return type + "\u0000" + id;
    }

    private HealthReport report(List<Issue> issues, List<ResourceIdentity> resources, int recoveredTransactions) {
        Status status = issues.stream().anyMatch(issue -> issue.severity() == Severity.CRITICAL)
            ? Status.CRITICAL
            : issues.isEmpty() ? Status.HEALTHY : Status.DEGRADED;
        return new HealthReport(status, resources.size(), recoveredTransactions, List.copyOf(issues));
    }

    private Issue issue(Severity severity, String code, String id, Path file, String message) {
        return new Issue(severity, code, id == null ? "" : id, assetsRoot.relativize(file.toAbsolutePath().normalize()).toString(), message == null ? "" : message);
    }

    private boolean isInternal(Path file) {
        Path relative = assetsRoot.relativize(file.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0) {
            return false;
        }
        String first = relative.getName(0).toString();
        return first.equals(".transactions") || first.equals(".snapshots") || first.equals(".quarantine") || first.equals(".durability")
            || first.equals(".tombstones") || first.equals(".migrations") || first.equals("migration-backups");
    }

    private String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public enum Status {
        HEALTHY,
        DEGRADED,
        CRITICAL
    }

    public enum Severity {
        WARNING,
        CRITICAL
    }

    public record Issue(Severity severity, String code, String resourceId, String path, String message) {
    }

    public record HealthReport(Status status, int resourceCount, int recoveredTransactions, List<Issue> issues) {
    }

    private record ResourceIdentity(String type, String id, Path path, long revision, String hash) {
    }
}
