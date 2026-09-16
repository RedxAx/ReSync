package restudio.resync.worldgen.datapack;

import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class WorldGenInstalledDatapackPersistenceParticipant
    implements CloseablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String DIRECTORY = "worldgen/installed-datapack-lifecycle";
    private final Path scopeRoot;
    private volatile Path root;
    private final WorldGenInstalledDatapackCapability capability;

    public WorldGenInstalledDatapackPersistenceParticipant(Path scopeRoot,
                                                            WorldGenInstalledDatapackCapability capability) throws IOException {
        Path scope = MigrationPaths.requireDirectory(scopeRoot, "scopeRoot");
        this.scopeRoot = scope;
        this.root = scope.resolve(DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(scope, root.getParent());
        Files.createDirectories(root);
        MigrationPaths.requireNoSymlinkTraversal(scope, root);
        this.capability = Objects.requireNonNull(capability, "capability");
    }

    @Override
    public String owner() {
        return WorldGenInstalledDatapackCapability.OWNER;
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public PersistenceParticipantClassification classification() {
        return PersistenceParticipantClassification.DERIVED_CACHE;
    }

    @Override
    public boolean owns(Path file) {
        MigrationPaths.requirePath(file, "file");
        return false;
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.empty();
    }

    @Override
    public void flush() throws IOException {
        run("flush", capability::flush);
    }

    @Override
    public void quiesce() throws IOException {
        run("quiesce", capability::quiesce);
    }

    @Override
    public void resume() throws IOException {
        run("resume", capability::resume);
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        WorldGenInstalledDatapackCapability.State current = capability.state();
        if (current != WorldGenInstalledDatapackCapability.State.QUIESCED
            && current != WorldGenInstalledDatapackCapability.State.FAILED) {
            throw new IOException("Installed Datapack Capability Must Be Quiesced Before Rebind");
        }
        Path active = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path rebound = active.resolve(DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(active, rebound.getParent());
        Files.createDirectories(rebound);
        MigrationPaths.requireNoSymlinkTraversal(active, rebound);
        root = rebound;
        if (capability instanceof WorldGenDatapackInstaller installer) {
            installer.invalidateRecoveryForRebind();
        }
    }

    @Override
    public void healthCheck() throws IOException {
        run("healthCheck", capability::healthCheck);
    }

    @Override
    public void readinessCheck() throws IOException {
        run("readinessCheck", () -> {
            if (capability.state() != WorldGenInstalledDatapackCapability.State.OPEN || !capability.available()) {
                String reason = capability.failureReason();
                throw new IOException("Installed Datapack Capability Is Unavailable"
                    + (reason == null || reason.isBlank() ? "" : ": " + reason));
            }
        });
    }

    @Override
    public void close() throws IOException {
        capability.close();
    }

    public WorldGenInstalledDatapackCapability capability() {
        return capability;
    }

    private void run(String operation, IoAction action) throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        try {
            action.run();
        } finally {
            if (TemporaryLifecycleDiagnostics.enabled()) {
                TemporaryLifecycleDiagnostics.event("worldgen_installed_datapack_persistence", started,
                    Map.of("operation", operation, "state", capability.state().name()));
            }
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
