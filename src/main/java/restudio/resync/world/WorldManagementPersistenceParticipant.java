package restudio.resync.world;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class WorldManagementPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.world-management";
    private static final String ROOT_DIRECTORY = "world-management";
    private final Path scopeRoot;
    private final Controller controller;

    public WorldManagementPersistenceParticipant(Path scopeRoot, Controller controller) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.controller = Objects.requireNonNull(controller, "controller");
        Path expected = this.scopeRoot.resolve(ROOT_DIRECTORY).toAbsolutePath().normalize();
        if (!expected.equals(MigrationPaths.requirePath(controller.persistenceRoot(), "persistenceRoot"))) {
            throw new IllegalArgumentException("World management participant root does not match persistence root");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return MigrationPaths.requirePath(controller.persistenceRoot(), "persistenceRoot");
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
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidate = scope.resolve(ROOT_DIRECTORY).toAbsolutePath().normalize();
        if (!candidate.startsWith(scope) || candidate.equals(scope)) {
            throw new IOException("World management rebind escaped active root");
        }
        controller.rebindPersistence(candidate);
    }

    @Override
    public void healthCheck() throws IOException {
        controller.healthCheckPersistence();
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
