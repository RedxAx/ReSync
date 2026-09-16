package restudio.resync.restore;

import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.migration.StagedMigration;

import java.io.IOException;

public final class FullRestoreValidator implements StagedRestoreValidator {
    @Override
    public void validate(Snapshot snapshot, StagedMigration staged) throws IOException {
        SnapshotVerification source = snapshot.manifest().verify(snapshot.root());
        source.requireVerified();
        SnapshotVerification stagedVerification = snapshot.manifest().verify(staged.root());
        stagedVerification.requireVerified();
    }
}
