package restudio.resync.api;

import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class ExtensionPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.extensions";
    private static final String ROOT_DIRECTORY = "extensions";
    private final Path scopeRoot;
    private final Controller controller;

    public ExtensionPersistenceParticipant(Path scopeRoot, Controller controller) {
        this.scopeRoot = requirePath(scopeRoot, "scopeRoot");
        this.controller = Objects.requireNonNull(controller, "controller");
        Path expected = this.scopeRoot.resolve(ROOT_DIRECTORY).toAbsolutePath().normalize();
        Path actual = requirePath(controller.persistenceRoot(), "persistenceRoot");
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Extension participant root does not match persistence root");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return requirePath(controller.persistenceRoot(), "persistenceRoot");
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).subtreeRoot().build();
    }

    @Override
    public void flush() throws IOException {
        controller.flushPersistence();
    }

    @Override
    public void quiesce() throws IOException {
        controller.quiescePersistence();
    }

    @Override
    public void resume() throws IOException {
        controller.resumePersistence();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        Path scope = requireDirectory(activeRoot, "activeRoot");
        Path candidate = scope.resolve(ROOT_DIRECTORY).toAbsolutePath().normalize();
        if (!candidate.startsWith(scope) || candidate.equals(scope)) {
            throw new IOException("Extension rebind escaped active root");
        }
        controller.rebindPersistence(candidate);
    }

    @Override
    public void healthCheck() throws IOException {
        controller.healthCheckPersistence();
    }

    private static Path requirePath(Path path, String field) {
        if (path == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || hasSymbolicLinkAncestor(absolute)) {
            throw new IllegalArgumentException(field + " cannot contain symbolic links");
        }
        return absolute;
    }

    private static Path requireDirectory(Path path, String field) throws IOException {
        Path absolute = requirePath(path, field);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(field + " must be an existing directory: " + absolute);
        }
        return absolute;
    }

    private static boolean hasSymbolicLinkAncestor(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    public interface Controller {
        Path persistenceRoot();

        void flushPersistence() throws IOException;

        void quiescePersistence() throws IOException;

        void resumePersistence() throws IOException;

        void rebindPersistence(Path activeRoot) throws IOException;

        void healthCheckPersistence() throws IOException;
    }
}
