package restudio.resync.migration;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

public final class PersistenceOwnershipContext {
    private final Path sourceRoot;
    private final Path participantRoot;
    private final String participantRootRelative;

    public PersistenceOwnershipContext(Path sourceRoot, Path participantRoot) {
        this.sourceRoot = normalize(sourceRoot, "sourceRoot");
        this.participantRoot = normalize(participantRoot, "participantRoot");
        if (!this.participantRoot.startsWith(this.sourceRoot)) {
            throw new IllegalArgumentException("Participant Root Must Be Inside Source Root");
        }
        this.participantRootRelative = this.sourceRoot.equals(this.participantRoot)
            ? ""
            : relative(this.sourceRoot, this.participantRoot, "participantRoot");
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    public Path participantRoot() {
        return participantRoot;
    }

    public String participantRootRelative() {
        return participantRootRelative;
    }

    public String relativeToSource(Path file) {
        return relative(sourceRoot, file, "file");
    }

    public String relativeToParticipant(Path file) {
        return relative(participantRoot, file, "file");
    }

    private static Path normalize(Path path, String field) {
        return Objects.requireNonNull(path, field).toAbsolutePath().normalize();
    }

    private static String relative(Path root, Path path, String field) {
        Path normalized = normalize(path, field);
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IllegalArgumentException(field + " Must Be A Descendant Of Its Root");
        }
        return requireRelative(root.relativize(normalized).toString().replace(File.separatorChar, '/'), field);
    }

    private static String requireRelative(String value, String field) {
        try {
            return MigrationPaths.requireRelative(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " Is Invalid", exception);
        }
    }
}
