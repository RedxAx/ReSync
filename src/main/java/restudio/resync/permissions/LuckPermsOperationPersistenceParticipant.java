package restudio.resync.permissions;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class LuckPermsOperationPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.runtime.luckperms-operations";
    public static final String DIRECTORY = "runtime";
    public static final String FILE_NAME = "luckperms-operations.json";
    private final Path scopeRoot;
    private final LuckPermsManagementService service;

    public LuckPermsOperationPersistenceParticipant(Path scopeRoot, LuckPermsManagementService service) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.service = Objects.requireNonNull(service, "service");
        Path expected = persistenceFile(this.scopeRoot);
        Path actual = MigrationPaths.requirePath(service.persistenceRoot(), "LuckPerms operation journal");
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("LuckPerms operation participant root does not match service journal");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return MigrationPaths.requirePath(service.persistenceRoot(), "LuckPerms operation journal");
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
        service.rebindPersistence(persistenceFile(scope));
    }

    @Override
    public void healthCheck() throws IOException {
        service.healthCheckPersistence();
    }

    private static Path persistenceFile(Path scopeRoot) {
        return MigrationPaths.resolveInside(scopeRoot, DIRECTORY + "/" + FILE_NAME);
    }
}
