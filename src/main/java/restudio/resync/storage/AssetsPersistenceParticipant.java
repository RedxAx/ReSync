package restudio.resync.storage;

import com.google.gson.Gson;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.resources.JsonAssetInventory;
import restudio.resync.upgrade.AssetCoordinatorMigration;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AssetsPersistenceParticipant
    implements CloseablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = ProductionPersistenceOwners.FLOW_ASSETS;
    private static final String ASSETS_DIRECTORY = "assets";
    private final AssetPersistenceGate gate;
    private final Gson gson;
    private final AssetCoordinatorMigration.Result assetMigration;
    private final Consumer<AssetTransactionCoordinator> coordinatorPublisher;
    private final FlowStorage flowStorage;
    private final ReSyncJsonResourceStorage jsonStorage;
    private final CustomContentStorage customContentStorage;
    private final WorldGenProjectStorage worldGenStorage;
    private final CoordinatorFlush coordinatorFlush;
    private final AssetInventoryFactory assetInventoryFactory;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile AssetTransactionCoordinator coordinator;
    private volatile IOException lifecycleFailure;
    private boolean healthCheckedWhileQuiesced;

    public AssetsPersistenceParticipant(Path scopeRoot, AssetPersistenceGate gate, AssetTransactionCoordinator coordinator,
                                        Gson gson, AssetCoordinatorMigration.Result assetMigration,
                                        Consumer<AssetTransactionCoordinator> coordinatorPublisher,
                                        FlowStorage flowStorage,
                                        ReSyncJsonResourceStorage jsonStorage, CustomContentStorage customContentStorage,
                                        WorldGenProjectStorage worldGenStorage) {
        this(scopeRoot, gate, coordinator, gson, assetMigration, coordinatorPublisher, flowStorage, jsonStorage,
            customContentStorage, worldGenStorage, active -> active.flush(), JsonAssetInventory::scan);
    }

    AssetsPersistenceParticipant(Path scopeRoot, AssetPersistenceGate gate, AssetTransactionCoordinator coordinator,
                                 Gson gson, AssetCoordinatorMigration.Result assetMigration,
                                 Consumer<AssetTransactionCoordinator> coordinatorPublisher,
                                 FlowStorage flowStorage,
                                 ReSyncJsonResourceStorage jsonStorage, CustomContentStorage customContentStorage,
                                 WorldGenProjectStorage worldGenStorage, CoordinatorFlush coordinatorFlush) {
        this(scopeRoot, gate, coordinator, gson, assetMigration, coordinatorPublisher, flowStorage, jsonStorage,
            customContentStorage, worldGenStorage, coordinatorFlush, JsonAssetInventory::scan);
    }

    AssetsPersistenceParticipant(Path scopeRoot, AssetPersistenceGate gate, AssetTransactionCoordinator coordinator,
                                 Gson gson, AssetCoordinatorMigration.Result assetMigration,
                                 Consumer<AssetTransactionCoordinator> coordinatorPublisher,
                                 FlowStorage flowStorage,
                                 ReSyncJsonResourceStorage jsonStorage, CustomContentStorage customContentStorage,
                                 WorldGenProjectStorage worldGenStorage, CoordinatorFlush coordinatorFlush,
                                 AssetInventoryFactory assetInventoryFactory) {
        Path scope = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.assetMigration = Objects.requireNonNull(assetMigration, "assetMigration");
        this.coordinatorPublisher = Objects.requireNonNull(coordinatorPublisher, "coordinatorPublisher");
        this.flowStorage = Objects.requireNonNull(flowStorage, "flowStorage");
        this.jsonStorage = Objects.requireNonNull(jsonStorage, "jsonStorage");
        this.customContentStorage = Objects.requireNonNull(customContentStorage, "customContentStorage");
        this.worldGenStorage = Objects.requireNonNull(worldGenStorage, "worldGenStorage");
        this.coordinatorFlush = Objects.requireNonNull(coordinatorFlush, "coordinatorFlush");
        this.assetInventoryFactory = Objects.requireNonNull(assetInventoryFactory, "assetInventoryFactory");
        Path expected = scope.resolve(ASSETS_DIRECTORY).toAbsolutePath().normalize();
        if (!expected.equals(root()) || !expected.equals(coordinator.canonicalRoot()) || !expected.equals(flowStorage.getAssetsPath())
            || !expected.equals(jsonStorage.getAssetsPath())
            || !expected.equals(customContentStorage.getAssetsPath()) || !expected.equals(worldGenStorage.getAssetsPath())) {
            throw new IllegalArgumentException("Shared Assets Participant Roots Do Not Match");
        }
        if (!scope.equals(gate.scopeRoot())) {
            throw new IllegalArgumentException("Shared Assets Participant Scope Does Not Match Gate");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return gate.scopeRoot().resolve(ASSETS_DIRECTORY).toAbsolutePath().normalize();
    }

    @Override
    public Path rebindScope() {
        return gate.scopeRoot();
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).subtreeRoot().build();
    }

    @Override
    public synchronized void flush() throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        requireOpen();
        healthCheckedWhileQuiesced = false;
        Path assetsRoot = MigrationPaths.requireDirectory(root(), "Shared assets root");
        AssetTransactionCoordinator active = coordinator;
        if (!assetsRoot.equals(active.canonicalRoot())) {
            throw new IOException("Shared assets coordinator root does not match the active asset root");
        }
        flowStorage.validateActiveCoordinator(active);
        jsonStorage.validateActiveCoordinator(active);
        customContentStorage.validateActiveCoordinator(active);
        worldGenStorage.validateActiveCoordinator(active);
        coordinatorFlush.run(active);
        StorageSafety.forceDirectory(assetsRoot);
        TemporaryLifecycleDiagnostics.event("assets_persistence_flush", started,
            TemporaryLifecycleDiagnostics.with(Map.of("typedKey", OWNER, "operation", "flush",
                "participantCount", 4, "outcome", "complete")));
    }

    @Override
    public void quiesce() throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        requireOpen();
        healthCheckedWhileQuiesced = false;
        gate.quiesce();
        boolean flowQuiesced = false;
        boolean jsonQuiesced = false;
        boolean customQuiesced = false;
        boolean worldGenQuiesced = false;
        try {
            flowStorage.quiescePersistence();
            flowQuiesced = true;
            jsonStorage.quiescePersistence();
            jsonQuiesced = true;
            customContentStorage.quiescePersistence();
            customQuiesced = true;
            worldGenStorage.quiescePersistence();
            worldGenQuiesced = true;
        } catch (RuntimeException failure) {
            if (worldGenQuiesced) {
                resumeAfterQuiesceFailure(worldGenStorage::resumePersistenceWhileQuiesced, failure);
            }
            if (customQuiesced) {
                resumeAfterQuiesceFailure(customContentStorage::resumePersistenceWhileQuiesced, failure);
            }
            if (jsonQuiesced) {
                resumeAfterQuiesceFailure(jsonStorage::resumePersistenceWhileQuiesced, failure);
            }
            if (flowQuiesced) {
                resumeAfterQuiesceFailure(flowStorage::resumePersistenceWhileQuiesced, failure);
            }
            gate.resume();
            throw failure;
        }
        TemporaryLifecycleDiagnostics.event("assets_persistence_quiesce", started,
            TemporaryLifecycleDiagnostics.with(Map.of("typedKey", OWNER, "operation", "quiesce",
                "participantCount", 4, "outcome", "complete")));
    }

    @Override
    public synchronized void resume() throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        requireOpen();
        if (gate.isOpen()) {
            gate.quiesce();
        }
        boolean healthVerified = healthCheckedWhileQuiesced;
        healthCheckedWhileQuiesced = false;
        IOException failure = null;
        failure = resume(healthVerified ? flowStorage::resumePersistenceAfterHealthCheck
            : flowStorage::resumePersistenceWhileQuiesced, failure);
        failure = resume(healthVerified ? jsonStorage::resumePersistenceAfterHealthCheck
            : jsonStorage::resumePersistenceWhileQuiesced, failure);
        failure = resume(healthVerified ? customContentStorage::resumePersistenceAfterHealthCheck
            : customContentStorage::resumePersistenceWhileQuiesced, failure);
        failure = resume(healthVerified ? worldGenStorage::resumePersistenceAfterHealthCheck
            : worldGenStorage::resumePersistenceWhileQuiesced, failure);
        if (failure != null) {
            failure = quiesceAfterResumeFailure(worldGenStorage::quiescePersistence, failure);
            failure = quiesceAfterResumeFailure(customContentStorage::quiescePersistence, failure);
            failure = quiesceAfterResumeFailure(jsonStorage::quiescePersistence, failure);
            failure = quiesceAfterResumeFailure(flowStorage::quiescePersistence, failure);
            gate.quiesce();
            throw failure;
        }
        gate.resume();
        TemporaryLifecycleDiagnostics.event("assets_persistence_resume", started,
            TemporaryLifecycleDiagnostics.with(Map.of("typedKey", OWNER, "operation", "resume",
                "participantCount", 4, "outcome", "complete")));
    }

    @Override
    public synchronized void rebind(Path activeRoot) throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        requireOpen();
        healthCheckedWhileQuiesced = false;
        if (gate.isOpen()) {
            throw new IOException("Shared assets persistence must be quiesced before rebind");
        }
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path previous = gate.scopeRoot();
        AssetTransactionCoordinator previousCoordinator = coordinator;
        Path candidateRoot = scope.resolve(ASSETS_DIRECTORY).toAbsolutePath().normalize();
        boolean coordinatorChanged = !candidateRoot.equals(previousCoordinator.canonicalRoot());
        AssetTransactionCoordinator candidate = coordinatorChanged
            ? assetMigration.openOrAdopt(candidateRoot, gson)
            : previousCoordinator;
        if (!coordinatorChanged && scope.equals(previous)) {
            candidate.healthCheck();
            flowStorage.validateActiveCoordinator(candidate);
            jsonStorage.validateActiveCoordinator(candidate);
            customContentStorage.validateActiveCoordinator(candidate);
            worldGenStorage.validateActiveCoordinator(candidate);
            return;
        }
        boolean flowRebound = false;
        boolean jsonRebound = false;
        boolean customRebound = false;
        boolean worldGenRebound = false;
        try {
            candidate.healthCheck();
            flowStorage.rebindPersistence(scope.resolve(ASSETS_DIRECTORY), candidate);
            flowRebound = true;
            jsonStorage.rebindPersistence(scope, candidate);
            jsonRebound = true;
            customContentStorage.rebindPersistence(scope, candidate);
            customRebound = true;
            worldGenStorage.rebindPersistence(scope, candidate);
            worldGenRebound = true;
            gate.rebind(scope);
            coordinator = candidate;
            coordinatorPublisher.accept(candidate);
        } catch (IOException | RuntimeException failure) {
            if (worldGenRebound) {
                rollback(() -> worldGenStorage.rebindPersistence(previous, previousCoordinator), failure);
            }
            if (customRebound) {
                rollback(() -> customContentStorage.rebindPersistence(previous, previousCoordinator), failure);
            }
            if (jsonRebound) {
                rollback(() -> jsonStorage.rebindPersistence(previous, previousCoordinator), failure);
            }
            if (flowRebound) {
                rollback(() -> flowStorage.rebindPersistence(previous.resolve(ASSETS_DIRECTORY), previousCoordinator), failure);
            }
            rollback(() -> gate.rebind(previous), failure);
            coordinator = previousCoordinator;
            rollback(() -> coordinatorPublisher.accept(previousCoordinator), failure);
            if (coordinatorChanged) {
                try {
                    candidate.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
        if (coordinatorChanged) {
            try {
                previousCoordinator.close();
            } catch (IOException failure) {
                lifecycleFailure = failure;
            }
        }
        TemporaryLifecycleDiagnostics.event("assets_persistence_rebind", started,
            TemporaryLifecycleDiagnostics.with(Map.of("typedKey", OWNER, "operation", "rebind",
                "participantCount", 4, "outcome", "complete")));
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        requireOpen();
        if (lifecycleFailure != null) {
            throw new IOException("Previous Shared Asset Coordinator Could Not Be Released", lifecycleFailure);
        }
        if (healthCheckedWhileQuiesced && !gate.isOpen()) {
            readinessCheck();
            TemporaryLifecycleDiagnostics.event("assets_persistence_health", started,
                TemporaryLifecycleDiagnostics.with(Map.of("typedKey", OWNER, "operation", "health",
                    "participantCount", 4, "outcome", "reused_quiesced_proof")));
            return;
        }
        AssetTransactionCoordinator active = coordinator;
        synchronized (flowStorage) {
            synchronized (jsonStorage) {
                synchronized (customContentStorage) {
                    synchronized (worldGenStorage) {
                        active.withHealthCheckScope(() -> {
                            Path assetsRoot = MigrationPaths.requireDirectory(root(), "Shared assets root");
                            if (!assetsRoot.equals(active.canonicalRoot())) {
                                throw new IOException("Shared assets coordinator root does not match the active asset root");
                            }
                            flowStorage.validateActiveCoordinator(active);
                            jsonStorage.validateActiveCoordinator(active);
                            customContentStorage.validateActiveCoordinator(active);
                            worldGenStorage.validateActiveCoordinator(active);
                            flowStorage.healthCheckPersistenceLocal();
                            JsonAssetInventory inventory = assetInventoryFactory.scan(assetsRoot);
                            jsonStorage.healthCheckPersistenceLocal(inventory);
                            customContentStorage.healthCheckPersistenceLocal(inventory);
                            worldGenStorage.healthCheckPersistenceLocal(inventory);
                        });
                        healthCheckedWhileQuiesced = !gate.isOpen();
                    }
                }
            }
        }
        TemporaryLifecycleDiagnostics.event("assets_persistence_health", started,
            TemporaryLifecycleDiagnostics.with(Map.of("typedKey", OWNER, "operation", "health",
                "participantCount", 4, "outcome", "complete")));
    }

    @Override
    public synchronized void readinessCheck() throws IOException {
        requireOpen();
        if (lifecycleFailure != null) {
            throw new IOException("Previous Shared Asset Coordinator Could Not Be Released", lifecycleFailure);
        }
        AssetTransactionCoordinator active = coordinator;
        Path activeRoot = MigrationPaths.requireDirectory(root(), "Shared assets root");
        if (!activeRoot.equals(active.canonicalRoot())) {
            throw new IOException("Shared assets coordinator root does not match the active asset root");
        }
        active.read(snapshot -> snapshot.rootSequence());
        flowStorage.validateActiveCoordinator(active);
        jsonStorage.validateActiveCoordinator(active);
        customContentStorage.validateActiveCoordinator(active);
        worldGenStorage.validateActiveCoordinator(active);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed.get()) {
            return;
        }
        if (gate.isOpen()) {
            quiesce();
        }
        IOException failure = null;
        failure = close(jsonStorage::closePersistence, "JSON Asset Storage", failure);
        failure = close(customContentStorage::close, "Custom Content Storage", failure);
        failure = close(worldGenStorage::closePersistence, "WorldGen Storage", failure);
        failure = close(coordinator::close, "Shared Asset Coordinator", failure);
        if (lifecycleFailure != null) {
            failure = append(failure,
                new IOException("Previous Shared Asset Coordinator Could Not Be Released", lifecycleFailure));
        }
        if (failure != null) {
            throw failure;
        }
        closed.set(true);
    }

    private void requireOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("Shared assets persistence participant is closed");
        }
    }

    private static IOException resume(IoAction action, IOException failure) {
        try {
            action.run();
            return failure;
        } catch (IOException | RuntimeException exception) {
            IOException next = exception instanceof IOException ioException
                ? ioException
                : new IOException("Shared assets persistence resume failed", exception);
            if (failure == null) {
                return next;
            }
            failure.addSuppressed(next);
            return failure;
        }
    }

    private static IOException close(IoAction action, String target, IOException failure) {
        try {
            action.run();
            return failure;
        } catch (IOException | RuntimeException exception) {
            IOException next = exception instanceof IOException ioException
                ? ioException
                : new IOException(target + " Could Not Be Closed", exception);
            return append(failure, next);
        }
    }

    private static IOException append(IOException failure, IOException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static void resumeAfterQuiesceFailure(IoAction action, Exception failure) {
        try {
            action.run();
        } catch (IOException | RuntimeException resumeFailure) {
            failure.addSuppressed(resumeFailure);
        }
    }

    private static IOException quiesceAfterResumeFailure(IoAction action, IOException failure) {
        try {
            action.run();
        } catch (IOException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        return failure;
    }

    private static void rollback(IoAction action, Exception failure) {
        try {
            action.run();
        } catch (IOException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface CoordinatorFlush {
        void run(AssetTransactionCoordinator coordinator) throws IOException;
    }

    @FunctionalInterface
    interface AssetInventoryFactory {
        JsonAssetInventory scan(Path root) throws IOException;
    }
}
