package restudio.resync.restore;

import restudio.resync.migration.MigrationException;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.StagedMigration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public interface RestoreActivation {
    record Candidate(Snapshot snapshot, StagedMigration staged) {
        public Candidate {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            staged = Objects.requireNonNull(staged, "staged");
        }
    }

    Optional<Path> activeRoot() throws IOException;

    String planHash(Snapshot snapshot);

    StagedMigration stage(Snapshot snapshot, Path stagingRoot) throws IOException;

    void activate(StagedMigration staged) throws IOException;

    void validateBeforeActivation(Snapshot snapshot, StagedMigration staged) throws IOException;

    void rollback(StagedMigration staged) throws IOException;

    void verify(Snapshot snapshot, StagedMigration staged) throws IOException;

    void discard(Path stagingRoot) throws IOException;

    default Candidate prepare(Snapshot snapshot, StagedMigration staged, StagedRestoreValidator validator) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(staged, "staged");
        Objects.requireNonNull(validator, "validator");
        if (!planHash(snapshot).equals(staged.planHash())) {
            throw new MigrationException("Restore Staged Plan Hash Does Not Match");
        }
        validator.validate(snapshot, staged);
        validateBeforeActivation(snapshot, staged);
        return new Candidate(snapshot, staged);
    }

    default void swap(Candidate candidate) throws IOException {
        Candidate checked = Objects.requireNonNull(candidate, "candidate");
        validateBeforeActivation(checked.snapshot(), checked.staged());
        activate(checked.staged());
    }
}
