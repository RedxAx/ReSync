package restudio.resync.flow.handler.generic;

import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.ManagedFlowFileStoreContract;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class ManagedFlowFilePersistenceParticipant implements CloseablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = ManagedFlowFileStoreContract.OWNER;
    private final Path scopeRoot;
    private final ManagedFlowFileCapability capability;

    public ManagedFlowFilePersistenceParticipant(Path scopeRoot, ManagedFlowFileCapability capability) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.capability = Objects.requireNonNull(capability, "capability");
        Path expected = expectedRoot(this.scopeRoot);
        if (!expected.equals(MigrationPaths.requirePath(capability.persistenceRoot(), "managed flow-file root"))) {
            throw new IllegalArgumentException("Managed Flow File Capability Is Not Bound To Its Participant Root");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return capability.persistenceRoot();
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
    public boolean owns(Path file) {
        Path candidate = MigrationPaths.requirePath(file, "file");
        Path activeRoot = MigrationPaths.requirePath(root(), "managed flow-file root");
        return candidate.startsWith(activeRoot);
    }

    @Override
    public void flush() throws IOException {
        capability.healthCheck();
    }

    @Override
    public void quiesce() throws IOException {
        capability.quiesce();
    }

    @Override
    public void resume() throws IOException {
        capability.resume();
    }

    @Override
    public synchronized void rebind(Path activeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidate = expectedRoot(scope);
        MigrationPaths.requireDirectory(candidate, "managed flow-file root");
        capability.rebind(candidate);
    }

    @Override
    public void healthCheck() throws IOException {
        capability.healthCheck();
    }

    @Override
    public void close() throws IOException {
        capability.close();
    }

    private static Path expectedRoot(Path scopeRoot) {
        return scopeRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY).toAbsolutePath().normalize();
    }
}
