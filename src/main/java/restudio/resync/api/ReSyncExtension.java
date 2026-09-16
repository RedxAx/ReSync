package restudio.resync.api;

import java.io.IOException;
import java.nio.file.Path;

public interface ReSyncExtension {
    String getPluginId();

    String getVersion();

    String getDescription();

    default void initialize(ReSyncExtensionContext context) {
    }

    default void start() {
    }

    default void stop() {
    }

    default void flushPersistence() throws IOException {
    }

    default void quiescePersistence() throws IOException {
    }

    default void resumePersistence() throws IOException {
    }

    default void validatePersistenceRoot(Path root) throws IOException {
    }

    default void rebindPersistence(Path root) throws IOException {
    }

    default void healthCheckPersistence() throws IOException {
    }
}
