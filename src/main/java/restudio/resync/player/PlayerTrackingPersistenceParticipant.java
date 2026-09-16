package restudio.resync.player;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class PlayerTrackingPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.player-dossiers";
    private static final String DOSSIER_DIRECTORY = "player-dossiers";
    private final Path scopeRoot;
    private final PlayerTrackingManager manager;

    public PlayerTrackingPersistenceParticipant(Path scopeRoot, PlayerTrackingManager manager) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.manager = Objects.requireNonNull(manager, "manager");
        Path expected = this.scopeRoot.resolve(DOSSIER_DIRECTORY).toAbsolutePath().normalize();
        if (!expected.equals(manager.getDossierDirectory())) {
            throw new IllegalArgumentException("Player tracking participant root does not match manager dossier root");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return manager.getDossierDirectory();
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
        manager.flushPersistence();
    }

    @Override
    public void quiesce() {
        manager.quiescePersistence();
    }

    @Override
    public void resume() throws IOException {
        manager.resumePersistence();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidate = scope.resolve(DOSSIER_DIRECTORY).toAbsolutePath().normalize();
        if (!candidate.startsWith(scope) || candidate.equals(scope)) {
            throw new IOException("Player tracking rebind escaped active root");
        }
        manager.rebindPersistence(MigrationPaths.requireDirectory(candidate, "player dossier rebind root"));
    }

    @Override
    public void healthCheck() throws IOException {
        manager.healthCheckPersistence();
    }
}
