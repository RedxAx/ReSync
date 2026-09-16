package restudio.resync.migration;

import java.nio.file.Path;
import java.util.Objects;

public record Snapshot(Path root, Path manifestPath, Path statePath, SnapshotMetadata metadata, SnapshotManifest manifest, SnapshotState state, SnapshotVerification verification) {
    public Snapshot {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        manifestPath = Objects.requireNonNull(manifestPath, "manifestPath").toAbsolutePath().normalize();
        statePath = Objects.requireNonNull(statePath, "statePath").toAbsolutePath().normalize();
        metadata = Objects.requireNonNull(metadata, "metadata");
        manifest = Objects.requireNonNull(manifest, "manifest");
        state = Objects.requireNonNull(state, "state");
        verification = Objects.requireNonNull(verification, "verification");
    }

    public boolean verified() {
        return state == SnapshotState.VERIFIED && verification.verified();
    }
}
