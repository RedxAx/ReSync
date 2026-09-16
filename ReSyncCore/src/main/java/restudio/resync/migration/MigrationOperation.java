package restudio.resync.migration;

import java.util.Optional;

import restudio.resync.flow.identity.ResourceKey;

public record MigrationOperation(String kind, String adapterId, String sourcePath, String targetPath, String sourceHash, String targetHash, Optional<ResourceKey> resource) {
    public MigrationOperation(String kind, String adapterId, String sourcePath, String targetPath, String sourceHash, String targetHash) {
        this(kind, adapterId, sourcePath, targetPath, sourceHash, targetHash, Optional.empty());
    }

    public MigrationOperation(MigrationOperationType type, String adapterId, String sourcePath, String targetPath, String sourceHash, String targetHash, ResourceKey resource) {
        this(type.wireName(), adapterId, sourcePath, targetPath, sourceHash, targetHash, Optional.ofNullable(resource));
    }

    public MigrationOperation(MigrationOperationType type, String adapterId, String sourcePath, String targetPath, String sourceHash, String targetHash) {
        this(type, adapterId, sourcePath, targetPath, sourceHash, targetHash, null);
    }

    public MigrationOperation {
        kind = MigrationCanonical.requireText(kind, "operation kind");
        adapterId = MigrationCanonical.requireText(adapterId, "adapterId");
        sourcePath = normalizePath(sourcePath, "sourcePath");
        targetPath = normalizePath(targetPath, "targetPath");
        if (sourcePath.isEmpty() && targetPath.isEmpty()) {
            throw new IllegalArgumentException("Migration Operation Must Touch A Path");
        }
        sourceHash = normalizeHash(sourceHash, "sourceHash");
        targetHash = normalizeHash(targetHash, "targetHash");
        resource = resource == null ? Optional.empty() : resource;
    }

    public MigrationOperationType type() {
        return MigrationOperationType.fromWire(kind);
    }

    public MigrationOperationType operationType() {
        return type();
    }

    public Optional<ResourceKey> resourceKey() {
        return resource;
    }

    String canonical() {
        String base = "operation=" + MigrationCanonical.encode(kind) + '|' + MigrationCanonical.encode(adapterId) + '|' + MigrationCanonical.encode(sourcePath) + '|' + MigrationCanonical.encode(targetPath) + '|' + sourceHash + '|' + targetHash;
        return resource.map(value -> base + '|' + MigrationCanonical.encode(value.canonicalText())).orElse(base) + '\n';
    }

    private static String normalizePath(String value, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return MigrationPaths.requireRelative(value);
    }

    private static String normalizeHash(String value, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return MigrationCanonical.requireDigest(value, field);
    }
}
