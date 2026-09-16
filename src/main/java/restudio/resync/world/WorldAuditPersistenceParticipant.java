package restudio.resync.world;

import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class WorldAuditPersistenceParticipant
    implements CloseablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.world-audit";
    public static final String FILE_NAME = "world-audit.json";
    private final Path scopeRoot;
    private final WorldOperationSafetyService service;

    public WorldAuditPersistenceParticipant(Path scopeRoot, WorldOperationSafetyService service) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.service = Objects.requireNonNull(service, "service");
        Path expected = this.scopeRoot.resolve(FILE_NAME).toAbsolutePath().normalize();
        if (!expected.equals(service.persistenceRoot())) {
            throw new IllegalArgumentException("World audit participant root does not match service root");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return service.persistenceRoot();
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).exactRoot().build();
    }

    @Override
    public boolean owns(Path file) {
        return root().equals(MigrationPaths.requirePath(file, "file"));
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public void flush() throws IOException {
        service.flushPersistence();
    }

    @Override
    public void quiesce() throws IOException {
        service.quiescePersistence();
    }

    @Override
    public void resume() throws IOException {
        service.resumePersistence();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidate = scope.resolve(FILE_NAME).toAbsolutePath().normalize();
        if (!candidate.startsWith(scope) || candidate.getParent() == null || !candidate.getParent().equals(scope)) {
            throw new IOException("World audit rebind escaped active root");
        }
        service.rebindPersistence(candidate);
    }

    @Override
    public void healthCheck() throws IOException {
        service.healthCheckPersistence();
    }

    @Override
    public void close() throws IOException {
        service.closePersistence();
    }

    public WorldOperationSafetyService service() {
        return service;
    }

    public long generation() {
        return service.generation();
    }
}
