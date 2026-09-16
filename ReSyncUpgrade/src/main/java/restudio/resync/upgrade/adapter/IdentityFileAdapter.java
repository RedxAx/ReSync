package restudio.resync.upgrade.adapter;

import restudio.resync.migration.MigrationPaths;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Objects;

public final class IdentityFileAdapter implements OfflineUpgradeAdapter {
    public static final AdapterKey KEY = new AdapterKey("resync.identity", 1);

    private final Set<String> relativePaths;
    private final String owner;

    public IdentityFileAdapter(String relativePath, String owner) {
        this(java.util.List.of(relativePath), owner);
    }

    public IdentityFileAdapter(Collection<String> relativePaths, String owner) {
        Set<String> paths = new LinkedHashSet<>();
        for (String path : relativePaths == null ? Set.<String>of() : relativePaths) {
            paths.add(MigrationPaths.requireRelative(path));
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("At Least One Identity Path Is Required");
        }
        this.relativePaths = paths.stream().sorted(Comparator.naturalOrder()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.owner = requireText(owner, "owner");
    }

    public Set<String> relativePaths() {
        return relativePaths;
    }

    public IdentityFileAdapter withAdditionalPaths(Collection<String> additionalPaths) {
        Set<String> merged = new LinkedHashSet<>(relativePaths);
        if (additionalPaths != null) {
            additionalPaths.forEach(path -> merged.add(MigrationPaths.requireRelative(path)));
        }
        return new IdentityFileAdapter(merged, owner);
    }

    @Override
    public AdapterKey key() {
        return KEY;
    }

    @Override
    public String owner() {
        return owner;
    }

    @Override
    public boolean matches(String path) {
        return relativePaths.contains(path);
    }

    @Override
    public String targetPath(String path) {
        if (!matches(path)) {
            throw new IllegalArgumentException("Identity Adapter Path Does Not Match");
        }
        return path;
    }

    @Override
    public TransformResult transform(String path, byte[] sourceBytes) {
        if (!matches(path)) {
            throw new IllegalArgumentException("Identity Adapter Path Does Not Match");
        }
        return TransformResult.unchanged(Objects.requireNonNull(sourceBytes, "sourceBytes"));
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0
            || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return normalized;
    }
}
