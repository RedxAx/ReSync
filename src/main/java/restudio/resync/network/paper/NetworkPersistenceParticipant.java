package restudio.resync.network.paper;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class NetworkPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.network";
    private static final String NETWORK_DIRECTORY = "network";
    private volatile Path scopeRoot;
    private final Controller controller;

    public NetworkPersistenceParticipant(Path scopeRoot, Controller controller) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.controller = Objects.requireNonNull(controller, "controller");
        Path expected = this.scopeRoot.resolve(NETWORK_DIRECTORY).toAbsolutePath().normalize();
        if (!expected.equals(MigrationPaths.requirePath(controller.persistenceRoot(), "persistenceRoot"))) {
            throw new IllegalArgumentException("Network Participant Root Does Not Match Persistence Root");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        Path expected = scopeRoot.resolve(NETWORK_DIRECTORY).toAbsolutePath().normalize();
        Path actual = MigrationPaths.requirePath(controller.persistenceRoot(), "persistenceRoot");
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Network Participant Root Does Not Match Persistence Root");
        }
        return actual;
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
        Path candidate = scope.resolve(NETWORK_DIRECTORY).toAbsolutePath().normalize();
        if (!candidate.startsWith(scope) || candidate.equals(scope)) {
            throw new IOException("Network Persistence Rebind Escaped Active Root");
        }
        controller.rebindPersistence(candidate);
        scopeRoot = scope;
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
