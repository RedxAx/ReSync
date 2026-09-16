package restudio.resync.restore;

import restudio.resync.migration.Snapshot;
import restudio.resync.migration.StagedMigration;

import java.io.IOException;
import java.util.Objects;

@FunctionalInterface
public interface StagedRestoreValidator {
    void validate(Snapshot snapshot, StagedMigration staged) throws IOException;

    default StagedRestoreValidator andThen(StagedRestoreValidator next) {
        Objects.requireNonNull(next, "next");
        return (snapshot, staged) -> {
            validate(snapshot, staged);
            next.validate(snapshot, staged);
        };
    }
}
