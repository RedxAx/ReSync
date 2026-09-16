package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

public interface PersistenceParticipant {
    String owner();

    Path root();

    default PersistenceParticipantClassification classification() {
        return PersistenceParticipantClassification.AUTHORITATIVE;
    }

    default boolean rootMayBeAbsent() {
        return false;
    }

    default Set<String> resumeDependencies() {
        return Set.of();
    }

    default boolean owns(Path file) {
        Path participantRoot = MigrationPaths.requirePath(root(), "participant root");
        Path candidate = MigrationPaths.requirePath(file, "file");
        return candidate.startsWith(participantRoot);
    }

    default void flush() throws IOException {
    }

    default void quiesce() throws IOException {
    }

    default void resume() throws IOException {
    }

    default void rebind(Path activeRoot) throws IOException {
    }

    default void healthCheck() throws IOException {
    }

    default void readinessCheck() throws IOException {
        healthCheck();
    }
}
