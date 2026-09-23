package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public interface ReSyncDataFix {
    String id();

    int sourceVersion();

    default int targetVersion() {
        return sourceVersion() + 1;
    }

    void apply(Context context) throws IOException;

    record Context(Path root, int sourceVersion, int targetVersion) {
        public Context {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            if (sourceVersion < 1 || targetVersion != sourceVersion + 1) {
                throw new IllegalArgumentException("ReSync Data Fix Context Must Advance Exactly One Version");
            }
        }
    }
}
