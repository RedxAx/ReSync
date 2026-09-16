package restudio.resync.migration;

import java.nio.file.Path;

public interface ResolvedPersistenceOwnership {
    boolean ownsResolved(Path file);
}
