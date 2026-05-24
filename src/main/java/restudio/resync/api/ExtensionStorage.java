package restudio.resync.api;

import java.nio.file.Path;

public interface ExtensionStorage {
    Path directory();

    default Path resolve(String path) {
        return directory().resolve(path).normalize();
    }
}
