package restudio.resync.customcontent;

import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class CustomBlocksPersistenceParticipant
    implements CloseablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.custom-blocks";
    public static final String FILE_NAME = "custom-blocks.json";
    private final Path scopeRoot;
    private final VanillaContentProvider provider;

    public CustomBlocksPersistenceParticipant(Path scopeRoot, VanillaContentProvider provider) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.provider = Objects.requireNonNull(provider, "provider");
        Path expected = this.scopeRoot.resolve(FILE_NAME).toAbsolutePath().normalize();
        if (!expected.equals(provider.persistenceRoot())) {
            throw new IllegalArgumentException("Custom blocks participant root does not match provider root");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return provider.persistenceRoot();
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
        provider.flushPersistence();
    }

    @Override
    public void quiesce() throws IOException {
        provider.quiescePersistence();
    }

    @Override
    public void resume() throws IOException {
        provider.resumePersistence();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidate = scope.resolve(FILE_NAME).toAbsolutePath().normalize();
        if (!candidate.startsWith(scope) || candidate.getParent() == null || !candidate.getParent().equals(scope)) {
            throw new IOException("Custom blocks rebind escaped active root");
        }
        provider.rebindPersistence(candidate);
    }

    @Override
    public void healthCheck() throws IOException {
        provider.healthCheckPersistence();
    }

    @Override
    public void close() throws IOException {
        provider.closePersistence();
    }

    public VanillaContentProvider provider() {
        return provider;
    }

    public long generation() {
        return provider.generation();
    }
}
