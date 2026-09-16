package restudio.resync.migration;

import java.io.IOException;

@FunctionalInterface
public interface ActivatedMigrationVerifier {
    void verify(StagedMigration staged) throws IOException;
}
