package restudio.resync.flow;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class FlowStoragePersistenceParticipant implements RebindablePersistenceParticipant {
    public static final String OWNER = ProductionPersistenceOwners.FLOW_ASSETS;
    private static final String ASSETS_DIRECTORY = "assets";
    private volatile Path scopeRoot;
    private final FlowStorage storage;
    private final CoordinatorResolver coordinatorResolver;

    public FlowStoragePersistenceParticipant(Path scopeRoot, FlowStorage storage,
                                             CoordinatorResolver coordinatorResolver) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.coordinatorResolver = Objects.requireNonNull(coordinatorResolver, "coordinatorResolver");
        Path expected = this.scopeRoot.resolve(ASSETS_DIRECTORY).toAbsolutePath().normalize();
        if (!expected.equals(storage.getAssetsPath())) {
            throw new IllegalArgumentException("Flow Storage Participant Root Does Not Match Storage Assets Root");
        }
    }

    public FlowStoragePersistenceParticipant(Path scopeRoot, FlowStorage storage,
                                             AssetTransactionCoordinator coordinator) {
        this(scopeRoot, storage, resolverFor(coordinator));
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return storage.getAssetsPath();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public void flush() throws IOException {
        storage.flushPersistence();
    }

    @Override
    public void quiesce() {
        storage.quiescePersistence();
    }

    @Override
    public void resume() throws IOException {
        storage.resumePersistence();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidate = scope.resolve(ASSETS_DIRECTORY).toAbsolutePath().normalize();
        if (!candidate.startsWith(scope) || candidate.equals(scope)) {
            throw new IOException("Flow Storage Rebind Escaped Active Root");
        }
        AssetTransactionCoordinator candidateCoordinator = coordinatorResolver.resolve(candidate);
        if (candidateCoordinator == null || !candidate.equals(candidateCoordinator.canonicalRoot())) {
            throw new IOException("Flow Storage Rebind Coordinator Root Does Not Match Active Root");
        }
        storage.rebindPersistence(candidate, candidateCoordinator);
        scopeRoot = scope;
    }

    @Override
    public void healthCheck() throws IOException {
        storage.healthCheckPersistence();
    }

    @FunctionalInterface
    public interface CoordinatorResolver {
        AssetTransactionCoordinator resolve(Path candidateAssetsRoot) throws IOException;
    }

    private static AssetTransactionCoordinator requireCoordinator(Path candidateAssetsRoot,
                                                                  AssetTransactionCoordinator coordinator)
        throws IOException {
        AssetTransactionCoordinator required = Objects.requireNonNull(coordinator, "coordinator");
        Path actual = required.canonicalRoot().toAbsolutePath().normalize();
        if (!candidateAssetsRoot.equals(actual)) {
            throw new IOException("Flow Storage Rebind Coordinator Root Does Not Match Active Root");
        }
        return required;
    }

    private static CoordinatorResolver resolverFor(AssetTransactionCoordinator coordinator) {
        AssetTransactionCoordinator required = Objects.requireNonNull(coordinator, "coordinator");
        return candidate -> requireCoordinator(candidate, required);
    }
}
