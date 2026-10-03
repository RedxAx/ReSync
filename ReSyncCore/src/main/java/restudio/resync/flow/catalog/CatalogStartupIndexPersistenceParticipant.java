package restudio.resync.flow.catalog;

import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ScopedPersistenceParticipant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class CatalogStartupIndexPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.catalog-startup-index";
    private final ScopedPersistenceParticipant delegate;

    public CatalogStartupIndexPersistenceParticipant(Path dataRoot, Path root) throws IOException {
        Path indexRoot = Objects.requireNonNull(root, "Catalog startup index root is required").toAbsolutePath().normalize();
        Files.createDirectories(indexRoot);
        this.delegate = new ScopedPersistenceParticipant(OWNER, dataRoot, indexRoot, new ScopedPersistenceParticipant.Lifecycle() {
            @Override
            public void flush(Path activeRoot) {
            }

            @Override
            public void quiesce(Path activeRoot) {
                CatalogStartupIndex.invalidate(activeRoot);
            }

            @Override
            public void resume(Path activeRoot) {
            }

            @Override
            public void rebind(Path previousRoot, Path nextRoot) throws IOException {
                CatalogStartupIndex.invalidate(previousRoot);
                CatalogStartupIndex.invalidate(nextRoot);
                Files.createDirectories(nextRoot);
            }

            @Override
            public void healthCheck(Path activeRoot) {
            }
        });
    }

    @Override
    public String owner() {
        return delegate.owner();
    }

    @Override
    public PersistenceParticipantClassification classification() {
        return PersistenceParticipantClassification.DERIVED_CACHE;
    }

    @Override
    public Path root() {
        return delegate.root();
    }

    @Override
    public Path rebindScope() {
        return delegate.rebindScope();
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).subtreeRoot().build();
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void quiesce() throws IOException {
        delegate.quiesce();
    }

    @Override
    public void resume() throws IOException {
        delegate.resume();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        delegate.rebind(activeRoot);
    }

    @Override
    public void healthCheck() throws IOException {
        delegate.healthCheck();
    }
}
