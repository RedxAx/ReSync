package restudio.resync.upgrade.adapter;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;

import java.nio.file.Path;
import java.util.Objects;

public record OfflineUpgradeSnapshotInput(Path root, ImmutableSnapshotAdapter.View snapshot, Path provenanceRoot) {
    public OfflineUpgradeSnapshotInput(Path root, ImmutableSnapshotAdapter.View snapshot) {
        this(root, snapshot, root);
    }

    public OfflineUpgradeSnapshotInput {
        root = MigrationPaths.requirePath(root, "snapshotRoot");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        provenanceRoot = MigrationPaths.requirePath(provenanceRoot, "provenanceRoot");
    }
}
