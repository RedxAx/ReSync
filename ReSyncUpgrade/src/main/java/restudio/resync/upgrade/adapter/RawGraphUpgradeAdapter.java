package restudio.resync.upgrade.adapter;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.upgrade.command.RawGraphDocument;

import java.util.Objects;
import java.util.function.UnaryOperator;

public final class RawGraphUpgradeAdapter implements OfflineUpgradeAdapter {
    private final AdapterKey key;
    private final String owner;
    private final String relativePath;
    private final UnaryOperator<RawGraphDocument> transformer;

    public RawGraphUpgradeAdapter(AdapterKey key, String owner, String relativePath,
                                  UnaryOperator<RawGraphDocument> transformer) {
        this.key = Objects.requireNonNull(key, "key");
        this.owner = requireText(owner, "owner");
        this.relativePath = MigrationPaths.requireRelative(relativePath);
        this.transformer = Objects.requireNonNull(transformer, "transformer");
    }

    @Override
    public AdapterKey key() {
        return key;
    }

    @Override
    public String owner() {
        return owner;
    }

    @Override
    public boolean matches(String path) {
        return relativePath.equals(path);
    }

    @Override
    public String targetPath(String path) {
        if (!matches(path)) {
            throw new IllegalArgumentException("Raw Graph Adapter Path Does Not Match");
        }
        return relativePath;
    }

    @Override
    public TransformResult transform(String path, byte[] sourceBytes) {
        if (!matches(path)) {
            throw new IllegalArgumentException("Raw Graph Adapter Path Does Not Match");
        }
        RawGraphDocument source = RawGraphDocument.parse(Objects.requireNonNull(sourceBytes, "sourceBytes"));
        RawGraphDocument transformed = Objects.requireNonNull(transformer.apply(source), "transformed graph");
        byte[] output = transformed.canonicalBytes();
        return java.util.Arrays.equals(source.canonicalBytes(), output)
            ? TransformResult.unchanged(output)
            : TransformResult.changed(output);
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
