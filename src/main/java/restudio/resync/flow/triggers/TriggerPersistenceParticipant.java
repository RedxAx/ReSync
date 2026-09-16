package restudio.resync.flow.triggers;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class TriggerPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = ProductionPersistenceOwners.TRIGGERS;
    private static final String FILE_NAME = "triggers.json";
    private final Path scopeRoot;
    private final TriggerRegistry registry;

    public TriggerPersistenceParticipant(Path scopeRoot, TriggerRegistry registry) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.registry = Objects.requireNonNull(registry, "registry");
        Path expected = this.scopeRoot.resolve(FILE_NAME).toAbsolutePath().normalize();
        if (!expected.equals(registry.getPersistenceFile())) {
            throw new IllegalArgumentException("Trigger Participant Root Does Not Match Registry File");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return registry.getPersistenceFile();
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).exactRoot().build();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public void flush() throws IOException {
        registry.flushPersistence();
    }

    @Override
    public void quiesce() throws IOException {
        registry.quiescePersistence();
    }

    @Override
    public void resume() throws IOException {
        registry.resumePersistence();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        registry.rebindPersistence(activeRoot);
    }

    @Override
    public void healthCheck() throws IOException {
        registry.healthCheckPersistence();
    }
}
