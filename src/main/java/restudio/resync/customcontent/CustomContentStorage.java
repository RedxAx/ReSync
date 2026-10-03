package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomArmorDefinition;
import restudio.flow.data.CustomBlockDefinition;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.CustomItemDefinition;
import restudio.flow.data.FlowDataObject;
import restudio.flow.data.FlowDataObjectAdapter;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowDataTypeAdapter;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.Log;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.resources.JsonAssetInventory;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.server.AggregateResourceCreateStorage;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedState;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.ProjectMetadataLineage;
import restudio.resync.storage.StorageSafety;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class CustomContentStorage implements AutoCloseable {
    private static final Set<String> DEFINITION_FIELDS = Set.of("id", "enabled", "flowId", "type", "displayName", "provider",
        "externalId", "material", "customModelData", "armorSlot", "version", "graph", "lore", "tags", "abilities", "components");
    private final JavaPlugin plugin;
    private volatile File contentDir;
    private volatile Path contentPath;
    private volatile File assetsDir;
    private volatile JsonAssetStore<CustomContentDefinition> assetStore;
    private volatile AssetTransactionCoordinator coordinator;
    private volatile boolean closing;
    private volatile boolean closed;
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
            .registerTypeAdapter(FlowDataObject.class, new FlowDataObjectAdapter())
            .create();
    private final CustomContentValidator validator = new CustomContentValidator();
    private final ItemAttributeSchemaService attributeSchemaService;
    private final LegacyRuntimeActivationGate legacyRuntimeGate;
    private final AssetPersistenceGate assetsGate;
    private volatile GraphAdmission graphAdmission;

    @FunctionalInterface
    public interface GraphAdmission {
        void admit(CustomContentDefinition definition, FlowResourceMutationStamp intended);
    }

    public enum ProjectionUse {
        OPTION_CATALOG,
        SHUTDOWN_CLEANUP
    }

    CustomContentStorage(File dataFolder, AssetPersistenceGate assetsGate, AssetTransactionCoordinator coordinator) {
        this(null, dataFolder.toPath(), new ItemAttributeSchemaService(), LegacyRuntimeActivationGate.runtime(dataFolder),
            assetsGate, coordinator);
    }

    public CustomContentStorage(JavaPlugin plugin, Path activeRoot, ItemAttributeSchemaService attributeSchemaService,
                                LegacyRuntimeActivationGate legacyRuntimeGate, AssetPersistenceGate assetsGate,
                                AssetTransactionCoordinator coordinator) {
        this.plugin = plugin;
        this.attributeSchemaService = attributeSchemaService != null ? attributeSchemaService : new ItemAttributeSchemaService();
        this.legacyRuntimeGate = Objects.requireNonNull(legacyRuntimeGate, "legacyRuntimeGate");
        this.assetsGate = Objects.requireNonNull(assetsGate, "assetsGate");
        Path scope = MigrationPaths.requirePath(activeRoot, "activeRoot").toAbsolutePath().normalize();
        if (!scope.equals(assetsGate.scopeRoot().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Custom content persistence scope does not match the shared gate");
        }
        this.coordinator = requireCoordinator(scope, coordinator);
        this.contentDir = scope.resolve("custom-content").toFile();
        this.contentPath = contentDir.toPath();
        this.assetsDir = scope.resolve("assets").toFile();
        this.assetStore = createAssetStore(assetsDir.toPath(), contentPath, coordinator);
        if (legacyRuntimeGate.allowsLegacyMigration()) {
            repairMalformedFlowAliases();
        }
    }

    public void setGraphAdmission(GraphAdmission graphAdmission) {
        this.graphAdmission = graphAdmission;
    }

    private JsonAssetStore<CustomContentDefinition> createAssetStore(Path assetsRoot, Path legacyRoot,
                                                                      AssetTransactionCoordinator transactionCoordinator) {
        return new JsonAssetStore<>(
            assetsRoot,
            legacyRoot,
            ReSyncResourceCatalog.CUSTOM_CONTENT,
            ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.CUSTOM_CONTENT),
            this::deserializeDefinition,
            this::serializeDefinition,
            CustomContentDefinition::getId,
            this::defaultFolder,
            legacyRuntimeGate,
            transactionCoordinator,
            assetsGate::isOpen,
            this::acquireAssetMutation,
            this::mergeDefinitionPayload,
            ProjectMetadataLineage.writer(assetsRoot, gson)
        );
    }

    private JsonObject mergeDefinitionPayload(CustomContentDefinition definition, JsonObject existing, JsonObject serialized) {
        JsonObject merged = existing.deepCopy();
        DEFINITION_FIELDS.forEach(merged::remove);
        serialized.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue().deepCopy()));
        return merged;
    }

    private CustomContentDefinition deserializeDefinition(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        CustomContentDefinition definition = gson.fromJson(object, CustomContentDefinition.class);
        if (definition != null && object.has("graph") && object.get("graph").isJsonObject()) {
            definition.setGraph(FlowSerializer.deserialize(object.get("graph").toString()));
        }
        return definition;
    }

    private String serializeDefinition(CustomContentDefinition definition) {
        CustomContentDefinition required = Objects.requireNonNull(definition, "definition");
        JsonObject object = gson.toJsonTree(required).getAsJsonObject();
        if (required.getGraph() != null) {
            object.add("graph", JsonParser.parseString(FlowSerializer.serialize(required.getGraph())));
        }
        return gson.toJson(object);
    }

    private static AssetTransactionCoordinator requireCoordinator(Path activeRoot, AssetTransactionCoordinator coordinator) {
        AssetTransactionCoordinator required = Objects.requireNonNull(coordinator, "coordinator");
        Path expected = activeRoot.resolve("assets").toAbsolutePath().normalize();
        if (!expected.equals(required.canonicalRoot())) {
            throw new IllegalArgumentException("Custom content persistence root does not match the shared asset coordinator");
        }
        return required;
    }

    private JsonAssetStore.MutationLease acquireAssetMutation() {
        requireOpen();
        AssetPersistenceGate.MutationLease lease = assetsGate.acquire();
        return lease::close;
    }

    public Path getAssetsPath() {
        return assetsDir.toPath().toAbsolutePath().normalize();
    }

    public synchronized void validateActiveCoordinator(AssetTransactionCoordinator expected) throws IOException {
        AssetTransactionCoordinator required = Objects.requireNonNull(expected, "expectedCoordinator");
        requireOpen();
        if (coordinator != required) {
            throw new IOException("Custom content persistence coordinator identity does not match the shared coordinator");
        }
        Path root = MigrationPaths.requireDirectory(getAssetsPath(), "Custom content asset root");
        if (!root.equals(required.canonicalRoot())) {
            throw new IOException("Custom content persistence coordinator root does not match the active asset root");
        }
    }

    public synchronized void flushPersistence() throws IOException {
        requireOpen();
        MigrationPaths.requireDirectory(getAssetsPath(), "Custom content asset root");
        coordinator.flush();
    }

    public void quiescePersistence() {
        requireOpen();
        if (assetsGate.isOpen()) {
            throw new IllegalStateException("Shared asset persistence must be quiesced before custom content");
        }
    }

    public synchronized void resumePersistence() throws IOException {
        resumePersistenceWhileQuiesced();
    }

    public synchronized void resumePersistenceWhileQuiesced() throws IOException {
        requireOpen();
        if (assetsGate.isOpen()) {
            throw new IOException("Shared asset persistence must remain quiesced during custom content resume");
        }
        healthCheckPersistence();
    }

    public synchronized void resumePersistenceAfterHealthCheck() throws IOException {
        requireOpen();
        if (assetsGate.isOpen()) {
            throw new IOException("Shared asset persistence must remain quiesced during custom content resume");
        }
    }

    public synchronized void rebindPersistence(Path candidateScopeRoot,
                                               AssetTransactionCoordinator candidateCoordinator) throws IOException {
        requireOpen();
        if (assetsGate.isOpen()) {
            throw new IOException("Custom content persistence must be quiesced before rebind");
        }
        Path scope = MigrationPaths.requireDirectory(candidateScopeRoot, "activeRoot");
        Path candidateAssets = MigrationPaths.requireDirectory(scope.resolve("assets"), "Custom content asset root");
        Path candidateLegacy = scope.resolve("custom-content").toAbsolutePath().normalize();
        AssetTransactionCoordinator requiredCoordinator = requireCoordinator(scope, candidateCoordinator);
        JsonAssetStore<CustomContentDefinition> candidateStore = createAssetStore(candidateAssets, candidateLegacy, requiredCoordinator);
        try {
            candidateStore.healthCheck();
        } catch (IOException | RuntimeException failure) {
            candidateStore.close();
            throw failure;
        }
        JsonAssetStore<CustomContentDefinition> previousStore = assetStore;
        assetsDir = candidateAssets.toFile();
        contentDir = candidateLegacy.toFile();
        contentPath = candidateLegacy;
        coordinator = requiredCoordinator;
        assetStore = candidateStore;
        previousStore.close();
    }

    public synchronized void healthCheckPersistence() throws IOException {
        requireOpen();
        MigrationPaths.requireDirectory(getAssetsPath(), "Custom content asset root");
        coordinator.healthCheck();
        healthCheckPersistenceLocal();
    }

    public synchronized void healthCheckPersistenceLocal() throws IOException {
        requireOpen();
        Path root = MigrationPaths.requireDirectory(getAssetsPath(), "Custom content asset root");
        healthCheckPersistenceLocal(JsonAssetInventory.scan(root));
    }

    public synchronized void healthCheckPersistenceLocal(JsonAssetInventory inventory) throws IOException {
        requireOpen();
        MigrationPaths.requireDirectory(getAssetsPath(), "Custom content asset root");
        assetStore.healthCheckLocal(Objects.requireNonNull(inventory, "inventory"));
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (assetsGate.isOpen()) {
            throw new IOException("Custom content persistence must be quiesced before close");
        }
        closing = true;
        try {
            assetStore.close();
            closed = true;
        } finally {
            if (!closed) {
                closing = false;
            }
        }
    }

    private void requireOpen() {
        if (closing || closed) {
            throw new IllegalStateException("Custom content persistence is closed");
        }
    }

    JavaPlugin getPlugin() {
        return plugin;
    }

    public void preloadAll() {
        try (AssetPersistenceGate.MutationLease ignored = assetsGate.acquire()) {
            requireOpen();
            assetStore.preloadAll(definition -> validateDefinition(definition.getId(), definition));
        }
    }

    public CustomContentDefinition get(String id) {
        try (AssetPersistenceGate.MutationLease ignored = assetsGate.acquire()) {
            requireOpen();
            return getUnderLease(id);
        }
    }

    private CustomContentDefinition getUnderLease(String id) {
        String safeId = safeId(id, "load");
        if (safeId == null) {
            return null;
        }
        return validateDefinition(safeId, assetStore.get(safeId));
    }

    private CustomContentDefinition validateDefinition(String safeId, CustomContentDefinition definition) {
        if (definition == null) {
            return null;
        }
        List<String> errors = validator.validate(definition);
        if (!errors.isEmpty()) {
            if (plugin != null) plugin.getLogger().warning("Custom content " + safeId + " was not loaded: " + String.join("; ", errors));
            return null;
        }
        return definition;
    }

    public void save(CustomContentDefinition definition) {
        saveMutation(definition, UUID.randomUUID(), -1L, true, null);
    }

    public void save(CustomContentDefinition definition, UUID mutationId, long expectedRevision) {
        requireMutationRequest(mutationId, expectedRevision);
        saveMutation(definition, mutationId, expectedRevision, false, null);
    }

    public void save(CustomContentDefinition definition, UUID mutationId, long expectedRevision, String expectedPayloadHash) {
        requireMutationRequest(mutationId, expectedRevision);
        saveMutation(definition, mutationId, expectedRevision, false,
            Objects.requireNonNull(expectedPayloadHash, "Expected payload hash is required"));
    }

    public void save(CustomContentDefinition definition, long expectedRevision, UUID mutationId) {
        save(definition, mutationId, expectedRevision);
    }

    public JsonAssetStore.AggregateCreateResult create(CustomContentDefinition definition, UUID mutationId,
                                                        long expectedRevision, ResourcePresentationIntent presentation,
                                                        String expectedPayloadHash) {
        requireMutationRequest(mutationId, expectedRevision);
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(expectedPayloadHash, "expectedPayloadHash");
        if (definition == null) {
            throw new IllegalArgumentException("Invalid custom content definition");
        }
        try (AssetPersistenceGate.MutationLease ignored = assetsGate.acquire()) {
            requireOpen();
            String safeId = safeId(definition.getId(), "create");
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid custom content id");
            }
            try {
                List<String> errors = validator.validate(definition);
                if (!errors.isEmpty()) {
                    throw new IllegalArgumentException(String.join("; ", errors));
                }
                List<Map<String, Object>> componentErrors = attributeSchemaService.validate(definition.getMaterial(), definition.getComponents());
                if (!componentErrors.isEmpty()) {
                    throw new ItemAttributeValidationException(componentErrors);
                }
                long revision = Math.addExact(expectedRevision, 1L);
                FlowResourceMutationStamp intended = new FlowResourceMutationStamp(ReSyncResourceCatalog.CUSTOM_CONTENT,
                    safeId, revision, mutationId, expectedPayloadHash, false);
                admitGraph(definition, intended);
                Map<String, Object> canonicalPayload = gson.fromJson(serializeDefinition(definition), Map.class);
                if (!expectedPayloadHash.equals(ResourcePayloadCodecs.json().hashPayload(canonicalPayload).canonicalText())) {
                    throw AggregateResourceCreateStorage.rejectBeforeCommit("RESOURCE_PAYLOAD_INVALID",
                        "The item changed during validation. Create it again with the current editor.",
                        new IllegalStateException("Custom content changed during aggregate create validation: " + safeId));
                }
            } catch (AggregateResourceCreateStorage.PreCommitRejection rejection) {
                throw rejection;
            } catch (RuntimeException rejection) {
                throw AggregateResourceCreateStorage.rejectBeforeCommit(
                    ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.legacyValue(), rejection.getMessage(), rejection);
            }
            try {
                return assetStore.create(definition, Map.of(), mutationId, expectedRevision, presentation);
            } catch (JsonAssetStore.PreCommitConflictException rejection) {
                throw AggregateResourceCreateStorage.rejectBeforeCommit("RESOURCE_PATH_CONFLICT",
                    rejection.getMessage() + ". Choose another folder or name.", rejection);
            }
        }
    }

    private void saveMutation(CustomContentDefinition definition, UUID mutationId, long expectedRevision,
                              boolean normalizeComponents, String expectedPayloadHash) {
        if (definition == null) {
            throw new IllegalArgumentException("Invalid custom content definition");
        }
        try (AssetPersistenceGate.MutationLease ignored = assetsGate.acquire()) {
            requireOpen();
            if (normalizeComponents) {
                definition.setComponents(attributeSchemaService.customComponentsForMaterial(definition.getMaterial(), definition.getComponents()));
            }
            String safeId = safeId(definition.getId(), "save");
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid custom content id");
            }
            try {
                List<String> errors = validator.validate(definition);
                if (!errors.isEmpty()) {
                    throw new IllegalArgumentException(String.join(". ", errors));
                }
                List<Map<String, Object>> componentErrors = attributeSchemaService.validate(definition.getMaterial(), definition.getComponents());
                if (!componentErrors.isEmpty()) {
                    throw new ItemAttributeValidationException(componentErrors);
                }
                FlowResourceMutationStamp current = readMutationStamp(safeId);
                long revision = Math.addExact(expectedRevision < 0L ? current == null ? 0L : current.revision() : expectedRevision, 1L);
                String payloadHash = expectedPayloadHash == null
                    ? ResourcePayloadCodecs.json().hashPayload(gson.fromJson(serializeDefinition(definition), Map.class)).canonicalText()
                    : expectedPayloadHash;
                admitGraph(definition, new FlowResourceMutationStamp(ReSyncResourceCatalog.CUSTOM_CONTENT,
                    safeId, revision, mutationId, payloadHash, false));
            } catch (IllegalArgumentException rejection) {
                throw new FlowResourceAdapter.PreCommitRejection(rejection);
            }
            try {
                Snapshot snapshot = assetStore.coordinatorSnapshot();
                List<JsonAssetStore.PreparedMutation> mutations = new ArrayList<>();
                mutations.add(expectedPayloadHash == null
                    ? assetStore.prepareSave(snapshot, definition, Map.of(), mutationId, expectedRevision)
                    : assetStore.prepareSave(snapshot, definition, Map.of(), mutationId, expectedRevision, expectedPayloadHash));
                List<String> malformedAliases = malformedFlowAliases(definition, mutationId);
                for (String malformedAlias : malformedAliases) {
                    mutations.add(assetStore.prepareDelete(snapshot, malformedAlias, mutationId, -1L));
                }
                assetStore.commitPrepared(mutationId, snapshot, mutations);
                for (String malformedAlias : malformedAliases) {
                    Log.warn("Removed malformed custom content alias " + malformedAlias + " for " + safeId);
                }
            } catch (ResourceRevisionConflictException exception) {
                throw exception;
            } catch (IllegalArgumentException rejection) {
                throw new FlowResourceAdapter.PreCommitRejection(rejection);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to save custom content: " + safeId, e);
            }
        }
    }

    private void admitGraph(CustomContentDefinition definition, FlowResourceMutationStamp intended) {
        GraphAdmission admission = graphAdmission;
        if (admission == null || definition == null || definition.getGraph() == null) {
            return;
        }
        admission.admit(definition, intended);
    }

    public void delete(String id) {
        try (AssetPersistenceGate.MutationLease ignored = assetsGate.acquire()) {
            requireOpen();
            String safeId = safeId(id, "delete");
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid custom content id");
            }
            try {
                assetStore.delete(safeId);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to delete custom content: " + safeId, e);
            }
        }
    }

    public void delete(String id, UUID mutationId, long expectedRevision) {
        requireMutationRequest(mutationId, expectedRevision);
        try (AssetPersistenceGate.MutationLease ignored = assetsGate.acquire()) {
            requireOpen();
            String safeId = safeId(id, "delete");
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid custom content id");
            }
            try {
                assetStore.delete(safeId, mutationId, expectedRevision);
            } catch (ResourceRevisionConflictException exception) {
                throw exception;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to delete custom content: " + safeId, e);
            }
        }
    }

    public void delete(String id, long expectedRevision, UUID mutationId) {
        delete(id, mutationId, expectedRevision);
    }

    public boolean supportsAuthoritativeMutationIdentity() {
        return true;
    }

    public FlowResourceMutationStamp readMutationStamp(String id) {
        String safeId = safeId(id, "read mutation stamp");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid custom content id");
        }
        try (AssetPersistenceGate.MutationLease ignored = assetsGate.acquire()) {
            requireOpen();
            JsonAssetStore.AssetStamp stamp = assetStore.readStamp(safeId);
            if (stamp == null) {
                return null;
            }
            return new FlowResourceMutationStamp(ReSyncResourceCatalog.CUSTOM_CONTENT, safeId, stamp.revision(),
                stamp.mutationId(), stamp.payloadHash(), stamp.deleted());
        }
    }

    public boolean matchesCommittedPayloadRecovery(CustomContentDefinition previous, CustomContentDefinition requested,
                                                   CustomContentDefinition actual) {
        if (previous == null || requested == null || actual == null || requested.getId() == null
            || !requested.getId().equals(actual.getId()) || !requested.getId().equals(previous.getId())) {
            return false;
        }
        JsonObject requestedPayload = recoveryPayload(requested);
        JsonObject actualPayload = recoveryPayload(actual);
        JsonObject requestedGraph = requestedPayload.getAsJsonObject("graph");
        JsonObject actualGraph = actualPayload.getAsJsonObject("graph");
        if (requestedGraph == null || actualGraph == null || !requestedGraph.has("contentCoreGraph")
            || !actualGraph.has("contentCoreGraph")
            || !requestedGraph.get("contentCoreGraph").equals(actualGraph.get("contentCoreGraph"))) {
            return requestedPayload.equals(actualPayload);
        }
        requestedGraph.remove("nodes");
        requestedGraph.remove("connections");
        actualGraph.remove("nodes");
        actualGraph.remove("connections");
        return requestedPayload.equals(actualPayload);
    }

    private JsonObject recoveryPayload(CustomContentDefinition definition) {
        return JsonParser.parseString(serializeDefinition(definition)).getAsJsonObject();
    }

    public List<String> listIds() {
        try (AssetPersistenceGate.MutationLease ignored = assetsGate.acquire()) {
            requireOpen();
            return listIdsUnderLease();
        }
    }

    private List<String> listIdsUnderLease() {
        return assetStore.listIds().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<CustomContentDefinition> getAll() {
        try (AssetPersistenceGate.MutationLease ignored = assetsGate.acquire()) {
            requireOpen();
            return readDefinitions();
        }
    }

    public synchronized CategoryProjection readRuntimeDataCategoryProjection() {
        requireOpen();
        JsonAssetStore<CustomContentDefinition> store = assetStore;
        for (int attempt = 0; attempt < 3; attempt++) {
            Snapshot identity = store.coordinatorSnapshot();
            JsonAssetStore.ReadSnapshot<CustomContentDefinition> snapshot = store.readSnapshot();
            if (identity.rootSequence() != snapshot.rootSequence()) {
                continue;
            }
            Map<String, CategoryAsset> assets = currentCategoryAssets(identity);
            if (assets == null) {
                throw new IllegalStateException("Custom content category snapshot has incomplete asset lineage");
            }
            List<CustomContentDefinition> definitions = snapshot.values().stream()
                .map(value -> validateDefinition(value.id(), value.value()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CustomContentDefinition::getId, String.CASE_INSENSITIVE_ORDER))
                .toList();
            return new CategoryProjection(store, snapshot.root(), snapshot.rootSequence(), snapshot.revision(), definitions, assets);
        }
        throw new IllegalStateException("Custom content category state changed during capture");
    }

    public synchronized boolean isRuntimeDataCategoryProjectionCurrent(CategoryProjection projection) {
        requireOpen();
        if (projection == null || projection.assetStore != assetStore || !projection.root.equals(getAssetsPath())) {
            return false;
        }
        Snapshot current = assetStore.coordinatorSnapshot();
        if (current.rootSequence() == projection.rootSequence) {
            return true;
        }
        Map<String, CategoryAsset> assets = currentCategoryAssets(current);
        return assets != null && assets.equals(projection.assets);
    }

    public synchronized List<CustomContentDefinition> readProjection(ProjectionUse use) {
        Objects.requireNonNull(use, "use");
        requireOpen();
        if (assetsGate.isOpen()) {
            return readDefinitionsWithOpenGate();
        }
        return readDefinitions();
    }

    public List<CustomContentDefinition> readOptionCatalogProjection() {
        return readProjection(ProjectionUse.OPTION_CATALOG);
    }

    public List<CustomContentDefinition> readShutdownCleanupProjection() {
        return readProjection(ProjectionUse.SHUTDOWN_CLEANUP);
    }

    private List<CustomContentDefinition> readDefinitionsWithOpenGate() {
        return getAll();
    }

    private List<CustomContentDefinition> readDefinitions() {
        List<CustomContentDefinition> definitions = new ArrayList<>();
        for (String id : listIdsUnderLease()) {
            CustomContentDefinition definition = getUnderLease(id);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        definitions.sort(Comparator.comparing(CustomContentDefinition::getId, String.CASE_INSENSITIVE_ORDER));
        return definitions;
    }

    private Map<String, CategoryAsset> currentCategoryAssets(Snapshot snapshot) {
        Map<String, CategoryAsset> assets = new LinkedHashMap<>();
        List<Map.Entry<AssetKey, ExpectedState>> entries = snapshot.states().entrySet().stream()
            .filter(entry -> ReSyncResourceCatalog.CUSTOM_CONTENT.equals(entry.getKey().type()) && entry.getValue() instanceof Live)
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(AssetKey::id, String.CASE_INSENSITIVE_ORDER)))
            .toList();
        for (Map.Entry<AssetKey, ExpectedState> entry : entries) {
            Live live = (Live) entry.getValue();
            String mutation = snapshot.mutationValue(entry.getKey()).orElse(null);
            if (mutation == null) {
                return null;
            }
            assets.put(entry.getKey().id(), new CategoryAsset(live.revision(), live.hash(), mutation));
        }
        return Map.copyOf(assets);
    }

    public static final class CategoryProjection {
        private final JsonAssetStore<CustomContentDefinition> assetStore;
        private final Path root;
        private final long rootSequence;
        private final String revision;
        private final List<CustomContentDefinition> definitions;
        private final Map<String, CategoryAsset> assets;

        private CategoryProjection(JsonAssetStore<CustomContentDefinition> assetStore, Path root, long rootSequence, String revision,
                                   List<CustomContentDefinition> definitions, Map<String, CategoryAsset> assets) {
            this.assetStore = Objects.requireNonNull(assetStore, "Custom content category asset store is required");
            this.root = Objects.requireNonNull(root, "Custom content category root is required").toAbsolutePath().normalize();
            this.rootSequence = rootSequence;
            this.revision = Objects.requireNonNull(revision, "Custom content category revision is required");
            this.definitions = List.copyOf(Objects.requireNonNull(definitions, "Custom content category definitions are required"));
            this.assets = Map.copyOf(Objects.requireNonNull(assets, "Custom content category asset identities are required"));
        }

        public String revision() {
            return revision;
        }

        public List<CustomContentDefinition> definitions() {
            return definitions;
        }
    }

    private record CategoryAsset(long revision, String hash, String lineage) {
    }

    public List<CustomContentDefinition> getByFlow(String flowId) {
        if (flowId == null) {
            return List.of();
        }
        return getAll().stream().filter(definition -> flowId.equals(definition.getFlowId())).toList();
    }

    public CustomContentDefinition repairMalformedFlowIdentity(CustomContentDefinition definition) {
        if (definition == null || definition.getGraph() == null || definition.getId() == null || definition.getFlowId() == null
            || !definition.getId().equalsIgnoreCase(definition.getFlowId())) {
            return definition;
        }
        List<CustomContentDefinition> canonical = getByFlow(definition.getFlowId()).stream()
            .filter(existing -> existing.getId() != null && !existing.getId().equalsIgnoreCase(definition.getFlowId()))
            .toList();
        if (canonical.size() != 1) {
            return definition;
        }
        CustomContentDefinition original = canonical.getFirst();
        FlowGraph repairedGraph = repairGraph(definition, original);
        FlowNode start = CustomContentGraphAdapter.findStartNode(repairedGraph);
        if (start == null || start.getInputValues() == null) {
            return definition;
        }
        start.setType(CustomContentGraphAdapter.nodeType(original.getType()));
        Map<String, Object> inputs = start.getInputValues();
        inputs.put("content_id", original.getId());
        inputs.put("name", original.getDisplayName());
        inputs.put("provider", original.getProvider());
        inputs.put("external_id", original.getExternalId());
        inputs.put("material", original.getMaterial());
        inputs.put("custom_model_data", original.getCustomModelData() != null ? original.getCustomModelData() : "");
        inputs.put("components", new LinkedHashMap<>(original.getComponents() != null ? original.getComponents() : Map.of()));
        inputs.put("lore", String.join("\n", original.getLore() != null ? original.getLore() : List.of()));
        inputs.put("tags", String.join("\n", original.getTags() != null ? original.getTags() : List.of()));
        inputs.remove("armor_slot");
        if ("armor".equalsIgnoreCase(original.getType())) {
            CustomContentGraphAdapter.setContentConfiguration(repairedGraph, "armor_slot", original.getArmorSlot());
        } else {
            CustomContentGraphAdapter.removeContentConfiguration(repairedGraph, "armor_slot");
        }
        CustomContentDefinition repaired = CustomContentGraphAdapter.toDefinition(repairedGraph);
        if (repaired == null) {
            return definition;
        }
        repaired.setVersion(original.getVersion());
        Log.warn("Repaired malformed custom content identity " + definition.getId() + " to " + original.getId());
        return repaired;
    }

    private FlowGraph repairGraph(CustomContentDefinition malformed, CustomContentDefinition original) {
        FlowGraph malformedGraph = FlowSerializer.deserialize(FlowSerializer.serialize(malformed.getGraph()));
        FlowGraph sourceGraph = original.getGraph();
        if (sourceGraph == null || original.getType() == null || malformed.getType() == null
            || original.getType().equalsIgnoreCase(malformed.getType())) {
            return malformedGraph;
        }
        FlowGraph originalGraph = FlowSerializer.deserialize(FlowSerializer.serialize(sourceGraph));
        FlowNode malformedStart = CustomContentGraphAdapter.findStartNode(malformedGraph);
        String malformedStartId = malformedGraph.findNodeId(malformedStart);
        Set<String> transferred = new HashSet<>();
        for (Map.Entry<String, FlowNode> entry : malformedGraph.getNodes().entrySet()) {
            if (!entry.getKey().equals(malformedStartId) && !originalGraph.getNodes().containsKey(entry.getKey())) {
                originalGraph.getNodes().put(entry.getKey(), entry.getValue());
                transferred.add(entry.getKey());
            }
        }
        for (FlowConnection connection : malformedGraph.getConnections()) {
            if (transferred.contains(connection.getSourceNodeId()) && transferred.contains(connection.getTargetNodeId())
                && !originalGraph.getConnections().contains(connection)) {
                originalGraph.getConnections().add(connection);
            }
        }
        return originalGraph;
    }

    public List<CustomContentDefinition> getByType(String type) {
        if (type == null) {
            return List.of();
        }
        return getAll().stream().filter(definition -> type.equalsIgnoreCase(definition.getType())).toList();
    }

    public void ensureDefaultsForFlow(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return;
        }
        String itemId = flowId + ".default_item";
        String blockId = flowId + ".default_block";
        String armorId = flowId + ".default_armor";
        if (get(itemId) == null) {
            CustomItemDefinition item = new CustomItemDefinition();
            item.setId(itemId);
            item.setFlowId(flowId);
            item.setDisplayName("Default Item");
            item.setMaterial("STICK");
            item.getAbilities().add(new CustomAbilityBinding(itemId + ".use", "item.use", flowId));
            save(item);
        }
        if (get(blockId) == null) {
            CustomBlockDefinition block = new CustomBlockDefinition();
            block.setId(blockId);
            block.setFlowId(flowId);
            block.setDisplayName("Default Block");
            block.setMaterial("STONE");
            block.getAbilities().add(new CustomAbilityBinding(blockId + ".interact", "block.interact", flowId));
            save(block);
        }
        if (get(armorId) == null) {
            CustomArmorDefinition armor = new CustomArmorDefinition();
            armor.setId(armorId);
            armor.setFlowId(flowId);
            armor.setDisplayName("Default Armor");
            armor.setMaterial("IRON_CHESTPLATE");
            armor.setArmorSlot("chest");
            armor.getAbilities().add(new CustomAbilityBinding(armorId + ".tick", "armor.tick", flowId));
            save(armor);
        }
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            Log.warn("Rejected unsafe custom content id during " + action + ": " + id);
            return null;
        }
    }

    private String defaultFolder(CustomContentDefinition definition) {
        return switch (definition != null && definition.getType() != null ? definition.getType().toLowerCase() : "item") {
            case "armor" -> "Content/Armor";
            case "block" -> "Content/Blocks";
            case "projectile" -> "Content/Projectiles";
            default -> "Content/Items";
        };
    }

    private List<String> malformedFlowAliases(CustomContentDefinition definition, UUID mutationId) throws IOException {
        String id = definition != null ? definition.getId() : null;
        String flowId = definition != null ? definition.getFlowId() : null;
        if (id == null || flowId == null || id.equalsIgnoreCase(flowId)) {
            return List.of();
        }
        List<JsonAssetStore.ReplayOperation> replay = assetStore.replayOperations(mutationId);
        if (!replay.isEmpty()) {
            return historicalFlowAliases(id, flowId, replay);
        }
        return getByFlow(flowId).stream()
            .map(CustomContentDefinition::getId)
            .filter(Objects::nonNull)
            .filter(candidateId -> candidateId.equalsIgnoreCase(flowId))
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
            .toList();
    }

    static List<String> historicalFlowAliases(String canonicalId, String flowId,
                                              List<JsonAssetStore.ReplayOperation> operations) {
        List<JsonAssetStore.ReplayOperation> canonical = operations.stream()
            .filter(operation -> canonicalId.equals(operation.id()))
            .toList();
        if (canonical.size() != 1 || canonical.getFirst().deleted()) {
            throw new IllegalStateException("Persisted custom content mutation does not contain one canonical save: " + canonicalId);
        }
        List<JsonAssetStore.ReplayOperation> aliases = operations.stream()
            .filter(operation -> !canonicalId.equals(operation.id()))
            .toList();
        if (aliases.stream().anyMatch(operation -> !operation.deleted() || !operation.id().equalsIgnoreCase(flowId))) {
            throw new IllegalStateException("Persisted custom content mutation contains an unrelated alias: " + canonicalId);
        }
        long normalizedAliases = aliases.stream().map(operation -> operation.id().toLowerCase(Locale.ROOT)).distinct().count();
        if (normalizedAliases != aliases.size()) {
            throw new IllegalStateException("Persisted custom content mutation contains ambiguous aliases: " + canonicalId);
        }
        return aliases.stream().map(JsonAssetStore.ReplayOperation::id)
            .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
            .toList();
    }

    private void repairMalformedFlowAliases() {
        List<CustomContentDefinition> malformed = getAll().stream()
            .filter(definition -> definition.getId() != null && definition.getFlowId() != null && definition.getId().equalsIgnoreCase(definition.getFlowId()))
            .toList();
        for (CustomContentDefinition definition : malformed) {
            CustomContentDefinition current = get(definition.getId());
            if (current == null || current.getFlowId() == null || !current.getId().equalsIgnoreCase(current.getFlowId())) {
                continue;
            }
            CustomContentDefinition repaired = repairMalformedFlowIdentity(current);
            if (repaired == current) {
                continue;
            }
            try {
                save(repaired);
            } catch (RuntimeException exception) {
                Log.warn("Failed to repair malformed custom content alias " + definition.getId() + ": " + exception.getMessage());
            }
        }
    }

    private void requireMutationRequest(UUID mutationId, long expectedRevision) {
        Objects.requireNonNull(mutationId, "mutationId");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Expected custom content revision cannot be negative");
        }
    }

}
