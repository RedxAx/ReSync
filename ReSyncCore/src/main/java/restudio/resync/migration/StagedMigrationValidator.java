package restudio.resync.migration;

import java.io.IOException;

@FunctionalInterface
public interface StagedMigrationValidator {
    void validate(StagedMigration staged) throws IOException;
}
