package restudio.resync.upgrade.adapter;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.QuarantineRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface OfflineUpgradeSnapshotAdapter {
    OfflineUpgradeAdapter.AdapterKey key();

    String owner();

    boolean claims(OfflineUpgradeSnapshotInput input);

    SnapshotTransform transform(OfflineUpgradeSnapshotInput input) throws IOException;

    default String wireId() {
        return key().wireId();
    }

    default boolean owns(String relativePath, String sourceOwner) {
        return owner().equals(sourceOwner);
    }

    record SnapshotTransform(Collection<? extends FileTransform> files,
                             Collection<String> claimedPaths,
                             Collection<? extends QuarantineRecord> quarantines) {
        public SnapshotTransform {
            List<FileTransform> normalizedFiles = new ArrayList<>(files == null ? List.of() : files);
            if (normalizedFiles.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Snapshot transform files cannot contain null values");
            }
            normalizedFiles.sort(Comparator.comparing(FileTransform::sourcePath).thenComparing(FileTransform::targetPath));
            Set<String> identities = new java.util.HashSet<>();
            for (FileTransform file : normalizedFiles) {
                String identity = file.sourcePath() + "\u0000" + file.targetPath();
                if (!identities.add(identity)) {
                    throw new IllegalArgumentException("Duplicate Snapshot Transform File: " + identity);
                }
            }
            files = List.copyOf(normalizedFiles);

            List<String> normalizedClaims = new ArrayList<>(claimedPaths == null ? List.of() : claimedPaths);
            normalizedClaims.replaceAll(path -> MigrationPaths.requireRelative(path));
            normalizedClaims = normalizedClaims.stream().distinct().sorted().toList();
            claimedPaths = List.copyOf(normalizedClaims);

            List<QuarantineRecord> normalizedQuarantines = new ArrayList<>(quarantines == null ? List.of() : quarantines);
            if (normalizedQuarantines.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Snapshot transform quarantines cannot contain null values");
            }
            normalizedQuarantines.sort(Comparator.comparing(QuarantineRecord::recordId));
            quarantines = List.copyOf(normalizedQuarantines);
        }

        public SnapshotTransform(Collection<? extends FileTransform> files,
                                 Collection<String> claimedPaths) {
            this(files, claimedPaths, List.of());
        }
    }

    record FileTransform(String sourcePath, String targetPath, byte[] bytes, MigrationOperationType operationType) {
        public FileTransform {
            sourcePath = sourcePath == null || sourcePath.isBlank() ? "" : MigrationPaths.requireRelative(sourcePath);
            targetPath = MigrationPaths.requireRelative(targetPath);
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (operationType == MigrationOperationType.DELETE) {
                throw new IllegalArgumentException("Snapshot file transforms cannot delete files");
            }
            if (operationType == MigrationOperationType.GENERATE && targetPath.isBlank()) {
                throw new IllegalArgumentException("Generated snapshot files require a target path");
            }
        }

        public FileTransform(String sourcePath, String targetPath, byte[] bytes) {
            this(sourcePath, targetPath, bytes, null);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
