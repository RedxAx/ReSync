package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

@FunctionalInterface
public interface MigrationActivator {
    void activate(StagedMigration staged) throws IOException;

    default Optional<Path> activeRoot() throws IOException {
        return Optional.empty();
    }

    default Optional<Path> archivedSourceRoot() throws IOException {
        return Optional.empty();
    }
}
