package restudio.resync.upgrade.adapter;

import restudio.resync.migration.MigrationOperationType;

import java.util.Objects;

public interface OfflineUpgradeAdapter {
    AdapterKey key();

    String owner();

    boolean matches(String relativePath);

    String targetPath(String relativePath);

    TransformResult transform(String relativePath, byte[] sourceBytes);

    default MigrationOperationType operationType(String sourcePath, String targetPath, TransformResult result) {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(result, "result");
        if (sourcePath.equals(targetPath)) {
            return result.changed() ? MigrationOperationType.CONVERT : MigrationOperationType.COPY;
        }
        return result.changed() ? MigrationOperationType.CONVERT : MigrationOperationType.MOVE;
    }

    default String wireId() {
        return key().wireId();
    }

    record AdapterKey(String id, int version) {
        public AdapterKey {
            id = requireText(id, "adapter id");
            if (version < 1) {
                throw new IllegalArgumentException("Adapter Version Must Be Positive");
            }
        }

        public String wireId() {
            return id + "#" + version;
        }

        private static String requireText(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0
                || normalized.indexOf('#') >= 0) {
                throw new IllegalArgumentException(field + " Is Invalid");
            }
            return normalized;
        }
    }

    record TransformResult(byte[] bytes, boolean changed) {
        public TransformResult {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        public static TransformResult unchanged(byte[] bytes) {
            return new TransformResult(bytes, false);
        }

        public static TransformResult changed(byte[] bytes) {
            return new TransformResult(bytes, true);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
