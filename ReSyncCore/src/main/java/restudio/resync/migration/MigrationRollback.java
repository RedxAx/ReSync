package restudio.resync.migration;

import java.io.IOException;

@FunctionalInterface
public interface MigrationRollback {
    void rollback(StagedMigration staged) throws IOException;
}
