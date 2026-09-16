package restudio.resync.worldgen;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.Log;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.resources.JsonAssetInventory;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.Deleted;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedState;
import restudio.resync.storage.AssetTransactionCoordinator.MutationView;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionResult;
import restudio.resync.storage.ProjectMetadataLineage;
import restudio.resync.storage.StorageSafety;
import restudio.resync.server.TemporaryLifecycleDiagnostics;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class WorldGenProjectStorage {
    private volatile File assetsDir;
    private volatile JsonAssetStore<WorldGenProject> assetStore;
    private volatile AssetTransactionCoordinator coordinator;
    private final LegacyRuntimeActivationGate legacyRuntimeGate;
    private final AssetPersistenceGate assetsGate;
    private volatile boolean closed;
    private volatile Runnable generatedRecipeUpdater = () -> {
    };
    private volatile GeneratedRecipeReadiness generatedRecipeReadiness = () -> true;
    private volatile boolean generatedRecipeRetryRequired;
    private volatile Runnable changeListener = () -> {
    };

    public WorldGenProjectStorage(JavaPlugin plugin) {
        this(plugin, LegacyRuntimeActivationGate.runtime(plugin.getDataFolder()), missingCoordinator());
    }

    public WorldGenProjectStorage(JavaPlugin plugin, LegacyRuntimeActivationGate legacyRuntimeGate) {
        this(plugin, legacyRuntimeGate, missingCoordinator());
    }

    public WorldGenProjectStorage(JavaPlugin plugin, LegacyRuntimeActivationGate legacyRuntimeGate,
                                  AssetTransactionCoordinator coordinator) {
        this(plugin.getDataFolder(), legacyRuntimeGate, new AssetPersistenceGate(plugin.getDataFolder().toPath()), coordinator);
    }

    public WorldGenProjectStorage(JavaPlugin plugin, LegacyRuntimeActivationGate legacyRuntimeGate,
                                  AssetPersistenceGate assetsGate) {
        this(plugin, legacyRuntimeGate, assetsGate, missingCoordinator());
    }

    public WorldGenProjectStorage(JavaPlugin plugin, LegacyRuntimeActivationGate legacyRuntimeGate,
                                  AssetPersistenceGate assetsGate, AssetTransactionCoordinator coordinator) {
        this(plugin.getDataFolder(), legacyRuntimeGate, assetsGate, coordinator);
    }

    public WorldGenProjectStorage(File dataFolder) {
        this(dataFolder, LegacyRuntimeActivationGate.runtime(dataFolder), missingCoordinator());
    }

    public WorldGenProjectStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate) {
        this(dataFolder, legacyRuntimeGate, missingCoordinator());
    }

    public WorldGenProjectStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate,
                                  AssetTransactionCoordinator coordinator) {
        this(dataFolder, legacyRuntimeGate, new AssetPersistenceGate(dataFolder.toPath()), coordinator);
    }

    public WorldGenProjectStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate,
                                  AssetPersistenceGate assetsGate) {
        this(dataFolder, legacyRuntimeGate, assetsGate, missingCoordinator());
    }

    public WorldGenProjectStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate,
                                  AssetPersistenceGate assetsGate, AssetTransactionCoordinator coordinator) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Path scope = dataFolder.toPath().toAbsolutePath().normalize();
        Path assetsRoot = scope.resolve("assets").toAbsolutePath().normalize();
        AssetTransactionCoordinator sharedCoordinator = Objects.requireNonNull(coordinator, "coordinator");
        AssetPersistenceGate sharedGate = Objects.requireNonNull(assetsGate, "assetsGate");
        if (!assetsRoot.equals(sharedCoordinator.canonicalRoot())) {
            throw new IllegalArgumentException("WorldGen asset coordinator root does not match the active asset root");
        }
        if (!scope.equals(sharedGate.scopeRoot())) {
            throw new IllegalArgumentException("WorldGen asset gate scope does not match the active root");
        }
        this.assetsDir = assetsRoot.toFile();
        this.legacyRuntimeGate = Objects.requireNonNull(legacyRuntimeGate, "legacyRuntimeGate");
        this.assetsGate = sharedGate;
        this.coordinator = sharedCoordinator;
        this.assetStore = createAssetStore(assetsRoot, scope.resolve("worldgen-projects"), sharedCoordinator);
        requireCoordinatorOpen(sharedCoordinator);
    }

    private JsonAssetStore<WorldGenProject> createAssetStore(Path assetsRoot, Path legacyRoot,
                                                              AssetTransactionCoordinator sharedCoordinator) {
        return new JsonAssetStore<>(
            assetsRoot,
            legacyRoot,
            ReSyncResourceCatalog.WORLDGEN,
            ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.WORLDGEN),
            WorldGenSerializer::deserializeProject,
            WorldGenSerializer::serializeProjectOwned,
            WorldGenProject::getId,
            null,
            legacyRuntimeGate,
            sharedCoordinator,
            assetsGate::isOpen,
            this::acquireAssetMutation,
            WorldGenSerializer::mergeProjectPayload,
            ProjectMetadataLineage.writerIfPresent(assetsRoot, new Gson())
        );
    }

    private JsonAssetStore.MutationLease acquireAssetMutation() {
        AssetPersistenceGate.MutationLease lease = assetsGate.acquire();
        return lease::close;
    }

    public synchronized Path getAssetsPath() {
        requireStorageOpen();
        return assetsDir.toPath().toAbsolutePath().normalize();
    }

    public synchronized void validateActiveCoordinator(AssetTransactionCoordinator expected) throws IOException {
        AssetTransactionCoordinator required = Objects.requireNonNull(expected, "expectedCoordinator");
        requireStorageOpen();
        if (coordinator != required) {
            throw new IOException("WorldGen persistence coordinator identity does not match the shared coordinator");
        }
        Path root = MigrationPaths.requireDirectory(assetsDir.toPath(), "WorldGen asset root");
        if (!root.equals(required.canonicalRoot())) {
            throw new IOException("WorldGen persistence coordinator root does not match the active asset root");
        }
        Path scope = root.getParent();
        if (scope == null || !scope.equals(assetsGate.scopeRoot())) {
            throw new IOException("WorldGen persistence gate is not bound to the active root");
        }
    }

    public synchronized AssetTransactionCoordinator currentCoordinator() {
        requireAvailable();
        return coordinator;
    }

    public synchronized <T> T withCurrentCoordinator(CoordinatorOperation<T> operation) throws IOException {
        requireAvailable();
        return Objects.requireNonNull(operation, "operation").apply(coordinator);
    }

    public boolean legacyCompatibilityEnabled() {
        return legacyRuntimeGate.isCompatibilityMode();
    }

    public synchronized void flushPersistence() throws IOException {
        Path assetsRoot = requireBoundRoot();
        assetStore.healthCheck();
        StorageSafety.forceDirectory(assetsRoot);
    }

    public synchronized void quiescePersistence() {
        requireStorageOpen();
        assetsGate.quiesce();
    }

    public synchronized void resumePersistence() throws IOException {
        resumePersistenceWhileQuiesced();
        assetsGate.resume();
    }

    public synchronized void resumePersistenceWhileQuiesced() throws IOException {
        healthCheckPersistence();
    }

    public synchronized void resumePersistenceAfterHealthCheck() throws IOException {
        requireBoundRootLocal();
        if (assetsGate.isOpen()) {
            throw new IOException("Shared asset persistence must remain quiesced during WorldGen resume");
        }
    }

    public synchronized void rebindPersistence(Path candidateScopeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(candidateScopeRoot, "activeRoot");
        Path candidateAssets = scope.resolve("assets").toAbsolutePath().normalize();
        if (!candidateAssets.equals(coordinator.canonicalRoot())) {
            throw new IOException("WorldGen persistence rebind requires the candidate shared asset coordinator");
        }
        rebindPersistence(scope, coordinator);
    }

    public synchronized void rebindPersistence(Path candidateScopeRoot,
                                               AssetTransactionCoordinator candidateCoordinator) throws IOException {
        requireStorageOpen();
        if (assetsGate.isOpen()) {
            throw new IOException("WorldGen persistence must be quiesced before rebind");
        }
        Path scope = MigrationPaths.requireDirectory(candidateScopeRoot, "activeRoot");
        Path candidateAssets = MigrationPaths.requireDirectory(scope.resolve("assets"), "WorldGen asset root");
        AssetTransactionCoordinator sharedCoordinator = Objects.requireNonNull(candidateCoordinator, "candidateCoordinator");
        if (!candidateAssets.equals(sharedCoordinator.canonicalRoot())) {
            throw new IOException("WorldGen candidate coordinator root does not match the candidate asset root");
        }
        requireCoordinatorOpen(sharedCoordinator);
        Path candidateLegacy = scope.resolve("worldgen-projects").toAbsolutePath().normalize();
        JsonAssetStore<WorldGenProject> candidateStore = createAssetStore(candidateAssets, candidateLegacy, sharedCoordinator);
        try {
            candidateStore.healthCheck();
            JsonAssetStore<WorldGenProject> previousStore = assetStore;
            previousStore.close();
            assetsDir = candidateAssets.toFile();
            coordinator = sharedCoordinator;
            assetStore = candidateStore;
        } catch (IOException | RuntimeException exception) {
            candidateStore.close();
            throw exception;
        }
    }

    public synchronized void healthCheckPersistence() throws IOException {
        requireBoundRoot();
        coordinator.healthCheck();
        healthCheckPersistenceLocal();
    }

    public synchronized void healthCheckPersistenceLocal() throws IOException {
        Path root = requireBoundRootLocal();
        healthCheckPersistenceLocal(JsonAssetInventory.scan(root));
    }

    public synchronized void healthCheckPersistenceLocal(JsonAssetInventory inventory) throws IOException {
        requireBoundRootLocal();
        assetStore.healthCheckLocal(Objects.requireNonNull(inventory, "inventory"));
    }

    public synchronized void closePersistence() throws IOException {
        if (closed) {
            return;
        }
        assetStore.close();
        closed = true;
    }

    public synchronized WorldGenProject getProject(String id) {
        requireAvailable();
        String safeId = safeId(id, "load");
        if (safeId == null) {
            return null;
        }
        return readExactProject(safeId, null);
    }

    public synchronized void saveProject(WorldGenProject project) {
        requireAvailable();
        if (project == null) {
            throw new IllegalArgumentException("Invalid WorldGen project");
        }
        String safeId = safeId(project.getId(), "save");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        JsonAssetStore.AssetStamp stamp = assetStore.readStamp(safeId);
        saveProject(project, UUID.randomUUID(), stamp == null ? 0L : stamp.revision());
    }

    public synchronized TransactionResult saveProject(WorldGenProject project, UUID mutationId, long expectedRevision) {
        return saveProject(project, mutationId, expectedRevision, null);
    }

    public synchronized TransactionResult saveProject(WorldGenProject project, UUID mutationId, long expectedRevision,
                                                       JsonObject intentScope) {
        long started = TemporaryLifecycleDiagnostics.start();
        requireAvailable();
        requireMutationRequest(mutationId, expectedRevision);
        if (project == null) {
            throw new IllegalArgumentException("Invalid WorldGen project");
        }
        String safeId = safeId(project.getId(), "save");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        project.rebuildIndices();
        requireIntentScope(mutationId, intentScope);
        Snapshot snapshot = assetStore.coordinatorSnapshot();
        AssetTransactionCoordinator sharedCoordinator = coordinator;
        TransactionResult result;
        try {
            result = sharedCoordinator.withTransactionIntentScope(intentScope, () -> assetStore.commitPrepared(mutationId, snapshot,
                List.of(assetStore.prepareSave(snapshot, project, Map.of(), mutationId, expectedRevision))));
        } catch (ResourceRevisionConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to save WorldGen project: " + safeId, exception);
        }
        publishCommittedChange(result);
        TemporaryLifecycleDiagnostics.event("worldgen_storage_save", started,
            TemporaryLifecycleDiagnostics.with(Map.of("typedKey", "worldgen", "operation", "save",
                "resourceId", safeId, "revision", result.rootSequence(), "outcome", "committed")));
        return result;
    }

    public synchronized void saveProject(WorldGenProject project, long expectedRevision, UUID mutationId) {
        saveProject(project, mutationId, expectedRevision);
    }

    public synchronized void deleteProject(String id) {
        requireAvailable();
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        JsonAssetStore.AssetStamp stamp = assetStore.readStamp(safeId);
        deleteProject(safeId, UUID.randomUUID(), stamp == null ? 0L : stamp.revision());
    }

    public synchronized TransactionResult deleteProject(String id, UUID mutationId, long expectedRevision) {
        return deleteProject(id, mutationId, expectedRevision, null);
    }

    public synchronized TransactionResult deleteProject(String id, UUID mutationId, long expectedRevision,
                                                         JsonObject intentScope) {
        long started = TemporaryLifecycleDiagnostics.start();
        requireAvailable();
        requireMutationRequest(mutationId, expectedRevision);
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        requireIntentScope(mutationId, intentScope);
        Snapshot snapshot = assetStore.coordinatorSnapshot();
        AssetTransactionCoordinator sharedCoordinator = coordinator;
        TransactionResult result;
        try {
            result = sharedCoordinator.withTransactionIntentScope(intentScope, () -> assetStore.commitPrepared(mutationId, snapshot,
                List.of(assetStore.prepareDelete(snapshot, safeId, mutationId, expectedRevision))));
        } catch (ResourceRevisionConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to delete WorldGen project: " + safeId, exception);
        }
        publishCommittedChange(result);
        TemporaryLifecycleDiagnostics.event("worldgen_storage_delete", started,
            TemporaryLifecycleDiagnostics.with(Map.of("typedKey", "worldgen", "operation", "delete",
                "resourceId", safeId, "revision", result.rootSequence(), "outcome", "committed")));
        return result;
    }

    public synchronized void deleteProject(String id, long expectedRevision, UUID mutationId) {
        deleteProject(id, mutationId, expectedRevision);
    }

    public synchronized FlowResourceMutationStamp readMutationStamp(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "stamp");
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid WorldGen project id");
            }
            JsonAssetStore.AssetStamp stamp = assetStore.readStamp(safeId);
            if (stamp == null) {
                return null;
            }
            return new FlowResourceMutationStamp(ReSyncResourceCatalog.WORLDGEN, safeId, stamp.revision(),
                stamp.mutationId(), stamp.payloadHash(), stamp.deleted());
        }
    }

    public synchronized void completePostCommitRecovery(String id, UUID mutationId, long revision, boolean deleted) {
        requireAvailable();
        String safeId = safeId(id, "recipe recovery");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        Objects.requireNonNull(mutationId, "mutationId");
        if (revision < 1L) {
            throw new IllegalArgumentException("WorldGen project revision must be positive");
        }
        MutationView history;
        try {
            history = coordinator.mutation(mutationId).orElseThrow(
                () -> new IllegalStateException("WorldGen mutation is not durably recorded: " + mutationId));
        } catch (IOException exception) {
            throw new IllegalStateException("WorldGen mutation history could not be read: " + mutationId, exception);
        }
        ExpectedState state = history.result().states().get(new AssetKey(ReSyncResourceCatalog.WORLDGEN, safeId));
        if (state == null || state.revision() != revision || (state instanceof Deleted) != deleted) {
            throw new IllegalStateException("WorldGen recipe recovery does not match its durable mutation: " + mutationId);
        }
        if (generatedRecipeRetryRequired || generatedRecipeNeedsRepair()) {
            publishGeneratedRecipe();
            if (generatedRecipeNeedsRepair()) {
                throw new IllegalStateException("WorldGen generated rebuild recipe remains out of date");
            }
        }
        generatedRecipeRetryRequired = false;
    }

    public synchronized List<String> listProjectIds() {
        long started = TemporaryLifecycleDiagnostics.start();
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            Set<String> ids = new HashSet<>(assetStore.listIds());
            List<String> result = ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            TemporaryLifecycleDiagnostics.event("worldgen_storage_list", started,
                TemporaryLifecycleDiagnostics.with(Map.of("typedKey", "worldgen", "operation", "list",
                    "count", result.size(), "outcome", "complete")));
            return result;
        }
    }

    public synchronized WorldGenProject reloadProject(String id, Consumer<WorldGenProject> validator) {
        requireAvailable();
        String safeId = safeId(id, "reload");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        WorldGenProject project = readExactProject(safeId, validator);
        publishChange();
        return project;
    }

    public synchronized void setChangeListener(Runnable changeListener) {
        requireStorageOpen();
        this.changeListener = changeListener != null ? changeListener : () -> {
        };
    }

    public synchronized void setGeneratedRecipeUpdater(Runnable generatedRecipeUpdater) {
        setGeneratedRecipeUpdater(generatedRecipeUpdater, () -> true);
    }

    public synchronized void setGeneratedRecipeUpdater(Runnable generatedRecipeUpdater,
                                                        GeneratedRecipeReadiness generatedRecipeReadiness) {
        requireStorageOpen();
        this.generatedRecipeUpdater = generatedRecipeUpdater != null ? generatedRecipeUpdater : () -> {
        };
        this.generatedRecipeReadiness = generatedRecipeReadiness != null ? generatedRecipeReadiness : () -> true;
    }

    private void publishChange() {
        try {
            publishGeneratedRecipe();
        } catch (RuntimeException exception) {
            Log.warn("Failed to refresh WorldGen generated recipes: " + exception.getMessage());
        }
        notifyChangeListener();
    }

    private void publishCommittedChange(TransactionResult result) {
        Throwable recipeFailure = null;
        if (!result.replay() || generatedRecipeRetryRequired || generatedRecipeNeedsRepair()) {
            try {
                publishGeneratedRecipe();
            } catch (RuntimeException | Error exception) {
                recipeFailure = exception;
            }
        }
        if (!result.replay()) {
            notifyChangeListener();
        }
        if (recipeFailure != null) {
            throw new CommittedMutationException(result, recipeFailure);
        }
    }

    private void publishGeneratedRecipe() {
        long started = TemporaryLifecycleDiagnostics.start();
        try {
            generatedRecipeUpdater.run();
            generatedRecipeRetryRequired = false;
            TemporaryLifecycleDiagnostics.event("worldgen_recipe", started,
                TemporaryLifecycleDiagnostics.with(Map.of("typedKey", "worldgen", "operation", "publish",
                    "outcome", "complete")));
        } catch (RuntimeException | Error exception) {
            generatedRecipeRetryRequired = true;
            TemporaryLifecycleDiagnostics.terminal("worldgen_recipe", started, Map.of("typedKey", "worldgen"),
                "failed", "WORLDGEN.RECIPE_UPDATE_FAILED", exception.getClass().getSimpleName());
            Log.warn("Failed to refresh WorldGen generated recipes: " + exception.getMessage());
            throw exception;
        }
    }

    private boolean generatedRecipeNeedsRepair() {
        try {
            return !generatedRecipeReadiness.isReady();
        } catch (IOException | RuntimeException exception) {
            Log.warn("WorldGen generated recipe readiness could not be verified: " + exception.getMessage());
            return true;
        }
    }

    private void notifyChangeListener() {
        try {
            changeListener.run();
        } catch (RuntimeException exception) {
            Log.warn("Failed to publish WorldGen project catalog change: " + exception.getMessage());
        }
    }

    private Path requireBoundRoot() throws IOException {
        Path assetsRoot = requireBoundRootLocal();
        try {
            requireCoordinatorOpen(coordinator);
        } catch (IllegalStateException exception) {
            throw new IOException("WorldGen shared asset coordinator is unavailable", exception);
        }
        return assetsRoot;
    }

    private Path requireBoundRootLocal() throws IOException {
        requireStorageOpen();
        Path assetsRoot = MigrationPaths.requireDirectory(assetsDir.toPath(), "WorldGen asset root");
        if (!assetsRoot.equals(coordinator.canonicalRoot())) {
            throw new IOException("WorldGen persistence is not bound to its shared asset coordinator");
        }
        Path scope = assetsRoot.getParent();
        if (scope == null || !scope.equals(assetsGate.scopeRoot())) {
            throw new IOException("WorldGen persistence gate is not bound to the active root");
        }
        return assetsRoot;
    }

    private void requireAvailable() {
        requireStorageOpen();
        Path assetsRoot = assetsDir.toPath().toAbsolutePath().normalize();
        Path scope = assetsRoot.getParent();
        if (!assetsRoot.equals(coordinator.canonicalRoot()) || scope == null || !scope.equals(assetsGate.scopeRoot())) {
            throw new IllegalStateException("WorldGen persistence is not bound to the active shared asset scope");
        }
        requireCoordinatorOpen(coordinator);
    }

    private AssetPersistenceGate.MutationLease requirePersistenceReadOpen() {
        AssetPersistenceGate.MutationLease lease = assetsGate.acquire();
        try {
            requireAvailable();
            return lease;
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
    }

    private void requireStorageOpen() {
        if (closed) {
            throw new IllegalStateException("WorldGen persistence is closed");
        }
    }

    private static void requireCoordinatorOpen(AssetTransactionCoordinator sharedCoordinator) {
        sharedCoordinator.read(snapshot -> snapshot.rootSequence());
    }

    private static AssetTransactionCoordinator missingCoordinator() {
        throw new IllegalStateException("WorldGen persistence requires the shared asset transaction coordinator");
    }

    private void requireMutationRequest(UUID mutationId, long expectedRevision) {
        Objects.requireNonNull(mutationId, "mutationId");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Expected WorldGen project revision cannot be negative");
        }
    }

    private void requireIntentScope(UUID mutationId, JsonObject intentScope) {
        if (intentScope == null) {
            return;
        }
        try {
            coordinator.mutation(mutationId).ifPresent(history -> {
                JsonObject intent = history.intent();
                JsonElement persistedScope = intent.get("scope");
                if (persistedScope == null || !intentScope.equals(persistedScope)) {
                    throw new IllegalStateException("WorldGen mutation identity conflicts with its durable replay scope: " + mutationId);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("WorldGen durable replay scope could not be read", exception);
        }
    }

    private WorldGenProject readExactProject(String safeId, Consumer<WorldGenProject> validator) {
        long started = TemporaryLifecycleDiagnostics.start();
        JsonAssetStore.AssetStamp before;
        WorldGenProject candidate;
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            before = assetStore.readStamp(safeId);
            candidate = assetStore.readUncached(safeId);
            validateExactProject(safeId, before, candidate);
        }
        if (candidate != null && validator != null) {
            validator.accept(candidate);
        }
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            JsonAssetStore.AssetStamp afterValidation = assetStore.readStamp(safeId);
            if (!Objects.equals(before, afterValidation)) {
                throw changedWhileLoading(safeId);
            }
            WorldGenProject published = assetStore.readUncached(safeId);
            validateExactProject(safeId, afterValidation, published);
            assetStore.publishValidated(safeId, published, afterValidation);
            JsonAssetStore.AssetStamp afterPublication = assetStore.readStamp(safeId);
            if (!Objects.equals(afterValidation, afterPublication)) {
                assetStore.clearCache();
                throw changedWhileLoading(safeId);
            }
            WorldGenProject result = defensiveCopy(published);
            TemporaryLifecycleDiagnostics.event("worldgen_storage_load", started,
                TemporaryLifecycleDiagnostics.with(Map.of("typedKey", "worldgen", "operation", "load",
                    "resourceId", safeId, "outcome", result == null ? "missing" : "complete",
                    "revision", afterPublication == null ? 0L : afterPublication.revision())));
            return result;
        }
    }

    private void validateExactProject(String safeId, JsonAssetStore.AssetStamp stamp, WorldGenProject project) {
        if (project == null) {
            if (stamp != null && !stamp.deleted()) {
                throw new IllegalStateException("WorldGen coordinator state is live but its project payload is unavailable: " + safeId);
            }
            return;
        }
        if (stamp == null || stamp.deleted()) {
            throw new IllegalStateException("WorldGen project payload has no live coordinator state: " + safeId);
        }
        if (!safeId.equals(project.getId())) {
            throw new IllegalStateException("Persisted WorldGen project identity does not match its typed key: " + safeId);
        }
    }

    private IllegalStateException changedWhileLoading(String safeId) {
        return new IllegalStateException("WorldGen project changed while its exact typed state was loading: " + safeId);
    }

    private WorldGenProject defensiveCopy(WorldGenProject project) {
        return project == null ? null : WorldGenSerializer.deserializeProject(WorldGenSerializer.serializeProject(project));
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException exception) {
            Log.warn("Rejected unsafe WorldGen project id during " + action + ": " + id);
            return null;
        }
    }

    @FunctionalInterface
    public interface CoordinatorOperation<T> {
        T apply(AssetTransactionCoordinator coordinator) throws IOException;
    }

    @FunctionalInterface
    public interface GeneratedRecipeReadiness {
        boolean isReady() throws IOException;
    }

    public static final class CommittedMutationException extends IllegalStateException {
        private final TransactionResult transaction;

        public CommittedMutationException(TransactionResult transaction, Throwable cause) {
            super("WorldGen authoritative mutation committed but its generated rebuild recipe could not be updated",
                cause);
            this.transaction = Objects.requireNonNull(transaction, "transaction");
        }

        public TransactionResult transaction() {
            return transaction;
        }
    }
}
