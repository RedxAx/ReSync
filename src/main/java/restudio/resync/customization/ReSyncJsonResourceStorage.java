package restudio.resync.customization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.Log;
import restudio.resync.flow.automation.ScheduleDefinition;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.MigrationReportsPersistenceParticipant;
import restudio.resync.migration.RecipeMigrationReportContract;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.resources.JsonAssetInventory;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.resources.RecipeSchemaNormalizer;
import restudio.resync.server.AggregateResourceCreateStorage;
import restudio.resync.storage.AssetProjectMetadata;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetDelta;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedState;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.AssetTransactionCoordinator.Missing;
import restudio.resync.storage.AssetTransactionCoordinator.ProjectDelta;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.ProjectMetadataLineage;
import restudio.resync.storage.StorageSafety;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReSyncJsonResourceStorage {
    private static final int MAX_ICON_BYTES = 1024 * 1024;
    private static final int MAX_ICON_TEXT = 4 * ((MAX_ICON_BYTES + 2) / 3);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, JsonAssetStore<JsonObject>> stores = new LinkedHashMap<>();
    private final Map<String, CachedIconData> iconDataCache = new ConcurrentHashMap<>();
    private final List<ResourceListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ResourceMutationInterceptor> interceptors = new CopyOnWriteArrayList<>();
    private volatile LegacyRuntimeActivationGate legacyRuntimeGate;
    private volatile Path activeScopeRoot;
    private volatile Path assetsRoot;
    private volatile AssetTransactionCoordinator coordinator;
    private volatile AssetTransactionCoordinator.ListenerRegistration coordinatorListener;
    private final AssetPersistenceGate assetsGate;
    private final AssetInventoryFactory assetInventoryFactory;
    private volatile PersistenceState persistenceState = PersistenceState.OPEN;
    private volatile long snapshotGeneration;
    private final ReentrantReadWriteLock persistenceFence = new ReentrantReadWriteLock(true);
    private RecipeMigrationReportContract.Report pendingRecipeMigrationReport;
    private MigrationReportsPersistenceParticipant migrationReportsAuthority;

    private enum PersistenceState {
        OPEN,
        QUIESCED,
        CLOSED
    }

    @FunctionalInterface
    interface AssetInventoryFactory {
        JsonAssetInventory scan(Path root) throws IOException;
    }

    private record CachedIconData(long revision, String mutation, String data, String hash) {
    }

    public record ResourceSnapshotValue(String id, JsonObject value, JsonAssetStore.AssetStamp stamp) {
        public ResourceSnapshotValue {
            id = Objects.requireNonNull(id, "Resource snapshot value ID is required");
            value = Objects.requireNonNull(value, "Resource snapshot value is required").deepCopy();
            stamp = Objects.requireNonNull(stamp, "Resource snapshot value stamp is required");
        }

        @Override
        public JsonObject value() {
            return value.deepCopy();
        }
    }

    public record ResourceSnapshot(String type, Path root, long rootSequence, String revision, List<ResourceSnapshotValue> values) {
        public ResourceSnapshot {
            type = Objects.requireNonNull(type, "Resource snapshot type is required");
            root = Objects.requireNonNull(root, "Resource snapshot root is required").toAbsolutePath().normalize();
            revision = Objects.requireNonNull(revision, "Resource snapshot revision is required");
            values = List.copyOf(Objects.requireNonNull(values, "Resource snapshot values are required"));
        }
    }

    public ReSyncJsonResourceStorage(JavaPlugin plugin, LegacyRuntimeActivationGate legacyRuntimeGate,
                                     AssetPersistenceGate assetsGate, AssetTransactionCoordinator coordinator) {
        this(plugin, legacyRuntimeGate, assetsGate, coordinator, JsonAssetInventory::scan);
    }

    ReSyncJsonResourceStorage(JavaPlugin plugin, LegacyRuntimeActivationGate legacyRuntimeGate,
                              AssetPersistenceGate assetsGate, AssetTransactionCoordinator coordinator,
                              AssetInventoryFactory assetInventoryFactory) {
        Objects.requireNonNull(plugin, "plugin");
        this.legacyRuntimeGate = Objects.requireNonNull(legacyRuntimeGate, "legacyRuntimeGate");
        this.assetsGate = Objects.requireNonNull(assetsGate, "assetsGate");
        this.assetInventoryFactory = Objects.requireNonNull(assetInventoryFactory, "assetInventoryFactory");
        AssetTransactionCoordinator sharedCoordinator = Objects.requireNonNull(coordinator, "coordinator");
        Path root = sharedCoordinator.canonicalRoot();
        Path scope = Objects.requireNonNull(root.getParent(), "coordinator root parent").toAbsolutePath().normalize();
        if (!assetsGate.scopeRoot().toAbsolutePath().normalize().equals(scope)) {
            throw new IllegalArgumentException("Shared asset persistence gate scope does not match coordinator root");
        }
        initializeStores(scope, sharedCoordinator);
    }

    private void initializeStores(Path scopeRoot, AssetTransactionCoordinator candidateCoordinator) {
        Map<String, JsonAssetStore<JsonObject>> candidateStores = Map.of();
        AssetTransactionCoordinator.ListenerRegistration candidateListener = null;
        try {
            candidateCoordinator.healthCheck();
            Path candidateAssetsRoot = candidateCoordinator.canonicalRoot();
            candidateStores = createStores(scopeRoot, candidateAssetsRoot, candidateCoordinator, legacyRuntimeGate);
            JsonAssetInventory inventory = assetInventoryFactory.scan(candidateAssetsRoot);
            for (JsonAssetStore<JsonObject> store : candidateStores.values()) {
                store.healthCheckLocal(inventory);
            }
            candidateListener = registerCoordinatorListener(candidateCoordinator);
            synchronized (stores) {
                stores.clear();
                stores.putAll(candidateStores);
            }
            activeScopeRoot = scopeRoot.toAbsolutePath().normalize();
            assetsRoot = candidateAssetsRoot.toAbsolutePath().normalize();
            coordinator = candidateCoordinator;
            coordinatorListener = candidateListener;
        } catch (IOException | RuntimeException failure) {
            if (candidateListener != null) {
                candidateListener.close();
            }
            try {
                closeStores(candidateStores.values(), failure);
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Failed to initialize JSON resource persistence", failure);
        }
    }

    private Map<String, JsonAssetStore<JsonObject>> createStores(Path scopeRoot, Path candidateAssetsRoot,
                                                                  AssetTransactionCoordinator candidateCoordinator,
                                                                  LegacyRuntimeActivationGate candidateLegacyRuntimeGate) {
        Map<String, JsonAssetStore<JsonObject>> candidateStores = new LinkedHashMap<>();
        try {
            for (String type : resourceTypesStatic()) {
                ReSyncManagedResource resource = ReSyncResourceCatalog.byType(type);
                candidateStores.put(type, new JsonAssetStore<>(
                    candidateAssetsRoot,
                    scopeRoot.resolve(legacyFolder(type)),
                    type,
                    resource.defaultFolder(),
                    json -> parse(type, json),
                    gson::toJson,
                    this::id,
                    value -> folder(value, resource.defaultFolder()),
                    candidateLegacyRuntimeGate,
                    candidateCoordinator,
                    this::persistenceMutationOpen,
                    this::acquireStoreMutation,
                    payloadMerger(type),
                    ProjectMetadataLineage.writer(candidateAssetsRoot, gson),
                    JsonObject::deepCopy
                ));
            }
            return candidateStores;
        } catch (RuntimeException failure) {
            try {
                closeStores(candidateStores.values(), failure);
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public Path getAssetsPath() {
        return assetsRoot;
    }

    public synchronized void validateActiveCoordinator(AssetTransactionCoordinator expected) throws IOException {
        AssetTransactionCoordinator required = Objects.requireNonNull(expected, "expectedCoordinator");
        AssetTransactionCoordinator current = requireCoordinator();
        if (current != required) {
            throw new IOException("JSON resource persistence coordinator identity does not match the shared coordinator");
        }
        Path root = requireDirectory(assetsRoot, "JSON resource asset root");
        if (!root.equals(required.canonicalRoot())) {
            throw new IOException("JSON resource persistence coordinator root does not match the active asset root");
        }
    }

    public Path getScopePath() {
        return activeScopeRoot;
    }

    public Path getMigrationReportsPath() {
        return activeScopeRoot.resolve(".migrations").toAbsolutePath().normalize();
    }

    public synchronized void flushPersistence() throws IOException {
        requireAssetsRoot();
        requireCoordinator().flush();
    }

    public synchronized void quiescePersistence() {
        if (persistenceState == PersistenceState.CLOSED) {
            return;
        }
        assetsGate.quiesce();
        persistenceFence.writeLock().lock();
        try {
            persistenceState = PersistenceState.QUIESCED;
        } finally {
            persistenceFence.writeLock().unlock();
        }
    }

    public synchronized void resumePersistence() throws IOException {
        resumePersistenceWhileQuiesced();
        assetsGate.resume();
    }

    public synchronized void resumePersistenceWhileQuiesced() throws IOException {
        resumePersistenceWhileQuiesced(false);
    }

    public synchronized void resumePersistenceAfterHealthCheck() throws IOException {
        resumePersistenceWhileQuiesced(true);
    }

    private void resumePersistenceWhileQuiesced(boolean healthVerified) throws IOException {
        requireAssetsRoot();
        if (!healthVerified) {
            healthCheckPersistence();
        }
        persistenceFence.writeLock().lock();
        try {
            persistenceState = PersistenceState.OPEN;
        } finally {
            persistenceFence.writeLock().unlock();
        }
    }

    public synchronized void rebindPersistence(Path candidateScopeRoot, AssetTransactionCoordinator candidateCoordinator) throws IOException {
        if (persistenceState != PersistenceState.QUIESCED) {
            throw new IOException("JSON resource persistence must be quiesced before rebind");
        }
        Path scope = requireDirectory(candidateScopeRoot, "activeRoot");
        Path candidateAssetsRoot = requireDirectory(scope.resolve("assets"), "JSON resource rebind root");
        AssetTransactionCoordinator sharedCoordinator = Objects.requireNonNull(candidateCoordinator, "candidateCoordinator");
        if (!sharedCoordinator.canonicalRoot().equals(candidateAssetsRoot)) {
            throw new IOException("JSON resource coordinator root does not match rebind asset root");
        }
        sharedCoordinator.healthCheck();
        LegacyRuntimeActivationGate candidateLegacyRuntimeGate = legacyRuntimeGate.isCompatibilityMode()
            ? LegacyRuntimeActivationGate.compatibility(scope) : LegacyRuntimeActivationGate.runtime(scope);
        Map<String, JsonAssetStore<JsonObject>> candidateStores = Map.of();
        AssetTransactionCoordinator.ListenerRegistration candidateListener = null;
        try {
            candidateStores = createStores(scope, candidateAssetsRoot, sharedCoordinator, candidateLegacyRuntimeGate);
            JsonAssetInventory inventory = assetInventoryFactory.scan(candidateAssetsRoot);
            for (JsonAssetStore<JsonObject> store : candidateStores.values()) {
                store.healthCheckLocal(inventory);
            }
            candidateListener = registerCoordinatorListener(sharedCoordinator);
            assetsGate.rebind(scope);
        } catch (IOException | RuntimeException failure) {
            if (candidateListener != null) {
                candidateListener.close();
            }
            closeStores(candidateStores.values(), failure);
            throw failure;
        }
        AssetTransactionCoordinator.ListenerRegistration admittedListener = candidateListener;
        Map<String, JsonAssetStore<JsonObject>> previous;
        AssetTransactionCoordinator.ListenerRegistration previousListener = coordinatorListener;
        synchronized (stores) {
            previous = Map.copyOf(stores);
            stores.clear();
            stores.putAll(candidateStores);
        }
        activeScopeRoot = scope;
        assetsRoot = candidateAssetsRoot;
        coordinator = sharedCoordinator;
        legacyRuntimeGate = candidateLegacyRuntimeGate;
        coordinatorListener = admittedListener;
        snapshotGeneration++;
        pendingRecipeMigrationReport = null;
        iconDataCache.clear();
        closeStores(previous.values(), null);
        if (previousListener != null) {
            previousListener.close();
        }
    }

    public synchronized void healthCheckPersistence() throws IOException {
        requireAssetsRoot();
        requireCoordinator().healthCheck();
        healthCheckPersistenceLocal();
    }

    public synchronized void healthCheckPersistenceLocal() throws IOException {
        requireAssetsRoot();
        healthCheckPersistenceLocal(assetInventoryFactory.scan(assetsRoot));
    }

    public synchronized void healthCheckPersistenceLocal(JsonAssetInventory inventory) throws IOException {
        requireAssetsRoot();
        Map<String, JsonAssetStore<JsonObject>> current;
        synchronized (stores) {
            current = Map.copyOf(stores);
        }
        JsonAssetInventory requiredInventory = Objects.requireNonNull(inventory, "inventory");
        for (JsonAssetStore<JsonObject> store : current.values()) {
            store.healthCheckLocal(requiredInventory);
        }
    }

    public synchronized void closePersistence() throws IOException {
        if (persistenceState == PersistenceState.CLOSED) {
            return;
        }
        persistenceFence.writeLock().lock();
        try {
            Map<String, JsonAssetStore<JsonObject>> current;
            synchronized (stores) {
                current = Map.copyOf(stores);
            }
            closeStores(current.values(), null);
            AssetTransactionCoordinator.ListenerRegistration listener = coordinatorListener;
            if (listener != null) {
                listener.close();
                coordinatorListener = null;
            }
            synchronized (stores) {
                stores.clear();
            }
            iconDataCache.clear();
            persistenceState = PersistenceState.CLOSED;
        } finally {
            persistenceFence.writeLock().unlock();
        }
    }

    private boolean persistenceMutationOpen() {
        return persistenceState == PersistenceState.OPEN && assetsGate.isOpen();
    }

    private AssetPersistenceGate.MutationLease acquirePersistenceMutation() {
        AssetPersistenceGate.MutationLease sharedLease = assetsGate.acquire();
        persistenceFence.readLock().lock();
        if (persistenceState != PersistenceState.OPEN) {
            persistenceFence.readLock().unlock();
            sharedLease.close();
            throw new IllegalStateException("JSON resource persistence is QUIESCED; mutation rejected");
        }
        return () -> {
            persistenceFence.readLock().unlock();
            sharedLease.close();
        };
    }

    private JsonAssetStore.MutationLease acquireStoreMutation() {
        AssetPersistenceGate.MutationLease lease = acquirePersistenceMutation();
        return lease::close;
    }

    private JsonAssetStore.PayloadMerger<JsonObject> payloadMerger(String type) {
        Set<String> ownedFields = ownedFieldPaths(type);
        return (value, existing, serialized) -> {
            return JsonAssetStore.mergePayload(existing, serialized, ownedFields);
        };
    }

    private Set<String> ownedFieldPaths(String type) {
        return switch (type) {
            case ReSyncResourceCatalog.CHAT -> Set.of(
                "id", "folder", "displayName", "channel", "channel.prefix", "channel.range", "channel.speakPermission",
                "channel.readPermission", "channel.allowMiniMessage", "channel.miniMessagePermission", "format", "format.template",
                "rule", "rule.contains", "rule.action", "rule.replacement", "rule.channel", "rule.flowId", "privateMessages",
                "privateMessages.sender", "privateMessages.receiver", "privateMessages.spy", "privateMessages.privateMessageFlow",
                "mention", "mention.template", "mention.mentionFlow", "ignore", "ignore.players", "enabled");
            case ReSyncResourceCatalog.COMPONENT_BUILDER -> Set.of(
                "id", "folder", "name", "displayName", "description", "enabled", "scope", "scope.kind",
                "scope.value", "components");
            case ReSyncResourceCatalog.MOTD_PROFILE -> Set.of(
                "id", "folder", "line1", "line2", "priority", "playerCountMode", "onlinePlayers", "maxPlayers", "icon",
                "iconData", "iconHash", "enabled");
            case ReSyncResourceCatalog.MESSAGE_RULE -> Set.of(
                "id", "folder", "source", "sources", "contains", "replacement", "action", "priority", "enabled", "permission",
                "players", "flowPredicate", "flowId");
            case ReSyncResourceCatalog.TEXT_TEMPLATE -> Set.of(
                "id", "folder", "kind", "text", "mode", "frameMillis", "width", "visibleCharacters", "frames", "values",
                "entries", "colors", "color", "secondaryColor");
            case ReSyncResourceCatalog.RECIPE_DEFINITION -> Set.of(
                "id", "folder", "type", "output", "output.material", "output.amount", "shape", "ingredients", "experience",
                "cookingTime", "craftedFlow", "cookedFlow", "deniedFlow", "conditions", "conditions.permission", "conditions.world",
                "enabled");
            case ReSyncResourceCatalog.ADVANCEMENT_TREE -> Set.of("id", "folder", "displayName", "enabled", "nodes");
            case ReSyncResourceCatalog.DIALOG -> Set.of(
                "id", "folder", "displayName", "enabled", "type", "title", "external_title", "pause", "can_close_with_escape",
                "after_action", "columns", "body", "inputs", "actions");
            case ReSyncResourceCatalog.TRADE_PROFILE -> Set.of(
                "id", "folder", "displayName", "enabled", "profession", "villagerType", "level", "maxUses", "restockTicks",
                "lootTable", "offers", "hooks", "hooks.openAction", "hooks.completeAction", "hooks.deniedAction");
            case ReSyncResourceCatalog.NPC_DEFINITION -> Set.of(
                "id", "folder", "displayName", "enabled", "entityType", "skin", "skin.username", "ai", "gravity", "invulnerable",
                "followPlayer", "followRange", "dialog", "tradeProfile", "lootTable", "equipment", "equipment.mainHand",
                "equipment.offHand", "equipment.helmet", "equipment.chestplate", "equipment.leggings", "equipment.boots", "hooks",
                "hooks.spawnAction", "hooks.interactAction", "hooks.rightClickAction", "hooks.leftClickAction", "hooks.damageAction",
                "hooks.deathAction", "hooks.despawnAction");
            case ReSyncResourceCatalog.LOOT_TABLE -> Set.of(
                "id", "folder", "displayName", "enabled", "trigger", "trigger.event", "trigger.target", "trigger.entity", "trigger.tool",
                "trigger.overrideDrops", "pools", "hooks", "hooks.beforeRollAction", "hooks.afterRollAction", "hooks.deniedRollAction");
            case ReSyncResourceCatalog.VARIABLE_DEFINITION -> Set.of(
                "id", "folder", "name", "displayName", "description", "valueType", "type", "scope", "persistent", "defaultValue");
            case ReSyncResourceCatalog.TIMER_DEFINITION -> Set.of(
                "id", "folder", "name", "displayName", "description", "scope", "persistent", "defaultDuration", "defaultUnit",
                "tickInterval");
            case ReSyncResourceCatalog.SCHEDULE_DEFINITION -> Set.of(
                "id", "folder", "name", "displayName", "description", "targetType", "targetId", "timingMode", "duration", "unit",
                "initialDelay", "dateTime", "timeZone", "cron", "scope", "persistent", "overlapPolicy", "existingTaskPolicy",
                "failurePolicy", "offlinePolicy", "missedRunPolicy", "timing", "timing.mode", "timing.duration", "timing.unit",
                "timing.initialDelay", "timing.dateTime", "timing.timeZone", "timing.cron", "timing.pattern", "target", "target.type",
                "target.id");
            default -> Set.of("id", "folder");
        };
    }

    private AssetTransactionCoordinator requireCoordinator() throws IOException {
        AssetTransactionCoordinator current = coordinator;
        if (current == null || persistenceState == PersistenceState.CLOSED) {
            throw new IOException("JSON resource persistence is closed");
        }
        return current;
    }

    private AssetTransactionCoordinator.ListenerRegistration registerCoordinatorListener(
        AssetTransactionCoordinator candidateCoordinator) {
        return candidateCoordinator.addListener(result -> {
            if (result.states().keySet().stream().anyMatch(key -> "blob".equals(key.type()))) {
                iconDataCache.clear();
            }
        });
    }

    private void closeStores(Iterable<JsonAssetStore<JsonObject>> candidates, Throwable priorFailure) throws IOException {
        IOException failure = null;
        for (JsonAssetStore<JsonObject> store : candidates) {
            try {
                store.close();
            } catch (RuntimeException closeFailure) {
                if (priorFailure != null) {
                    priorFailure.addSuppressed(closeFailure);
                } else if (failure == null) {
                    failure = new IOException("Failed to close JSON resource persistence binding", closeFailure);
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void requireAssetsRoot() throws IOException {
        requireDirectory(assetsRoot, "JSON resource asset root");
    }

    private Path requireDirectory(Path path, String name) throws IOException {
        Path normalized = Objects.requireNonNull(path, name).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized)) {
            throw new IOException(name + " must be an existing non-symbolic-link directory: " + normalized);
        }
        return normalized;
    }

    public JsonObject get(String type, String id) {
        JsonObject value = logicalPayload(requireStore(type).get(id));
        normalizeAssetId(value, id);
        if (value != null && ReSyncResourceCatalog.MOTD_PROFILE.equals(type)) {
            try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
                return motdProfileForClient(value);
            }
        }
        return value;
    }

    public List<String> listIds(String type) {
        try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
            return requireStore(type).listIds();
        }
    }

    public long snapshotGeneration() {
        if (!persistenceMutationOpen()) {
            throw new IllegalStateException("JSON resource snapshots are unavailable while persistence is closed");
        }
        return snapshotGeneration;
    }

    public long committedSequence() {
        try {
            return requireCoordinator().committedSequence();
        } catch (IOException exception) {
            throw new IllegalStateException("JSON resource snapshot authority is unavailable", exception);
        }
    }

    public ResourceSnapshot readSnapshot(String type) {
        JsonAssetStore.ReadSnapshot<JsonObject> snapshot = requireStore(type).readSnapshot();
        List<ResourceSnapshotValue> values = snapshot.values().stream().map(value -> {
            JsonObject payload = logicalPayload(value.value());
            normalizeAssetId(payload, value.id());
            return new ResourceSnapshotValue(value.id(), payload, value.stamp());
        }).toList();
        return new ResourceSnapshot(type, snapshot.root(), snapshot.rootSequence(), snapshot.revision(), values);
    }

    public boolean isCurrent(ResourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Resource snapshot is required");
        requireStore(snapshot.type());
        try {
            AssetTransactionCoordinator currentCoordinator = requireCoordinator();
            if (!currentCoordinator.canonicalRoot().equals(snapshot.root())) {
                return false;
            }
            return currentCoordinator.read(current -> {
                long liveCount = current.states().entrySet().stream()
                    .filter(entry -> entry.getKey().type().equals(snapshot.type()) && entry.getValue() instanceof Live)
                    .count();
                if (liveCount != snapshot.values().size()) {
                    return false;
                }
                for (ResourceSnapshotValue value : snapshot.values()) {
                    AssetKey key = new AssetKey(snapshot.type(), value.id());
                    ExpectedState state = current.state(key).orElse(null);
                    if (!(state instanceof Live) || state.revision() != value.stamp().revision()
                        || !current.mutationValue(key).filter(value.stamp().mutationValue()::equals).isPresent()) {
                        return false;
                    }
                }
                return true;
            });
        } catch (IOException exception) {
            throw new IllegalStateException("JSON resource snapshot authority is unavailable", exception);
        }
    }

    public void save(String type, JsonObject value) {
        saveMutation(type, value, UUID.randomUUID(), -1L, false);
    }

    public void save(String type, JsonObject value, UUID mutationId, long expectedRevision) {
        requireMutationRequest(mutationId, expectedRevision);
        saveMutation(type, value, mutationId, expectedRevision, true);
    }

    public void save(String type, JsonObject value, long expectedRevision, UUID mutationId) {
        save(type, value, mutationId, expectedRevision);
    }

    public JsonAssetStore.AggregateCreateResult create(String type, JsonObject value, UUID mutationId,
                                                       long expectedRevision,
                                                       ResourcePresentationIntent presentation,
                                                       String expectedPayloadHash) {
        requireMutationRequest(mutationId, expectedRevision);
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(expectedPayloadHash, "expectedPayloadHash");
        JsonAssetStore<JsonObject> store = requireStore(type);
        JsonObject working = value == null ? null : value.deepCopy();
        String safeId = id(working);
        JsonAssetStore.AggregateCreateResult result;
        try {
            byte[] motdIcon;
            try {
                normalizeAssetId(working, safeId);
                validate(type, working);
                if (ReSyncResourceCatalog.RECIPE_DEFINITION.equals(type)) {
                    RecipeSchemaNormalizer.normalize(working);
                }
                motdIcon = ReSyncResourceCatalog.MOTD_PROFILE.equals(type) ? prepareMotdIcon(working) : null;
                if (safeId == null || safeId.isBlank()) {
                    throw new IllegalArgumentException("Invalid JSON resource id");
                }
            } catch (IllegalArgumentException failure) {
                throw rejectAggregateCreate(failure);
            }
            JsonAssetStore.AssetStamp before;
            long beforeGeneration;
            try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
                before = store.readStamp(safeId);
                beforeGeneration = store.cacheGeneration();
            }
            Map<Path, byte[]> binaryWrites;
            try {
                for (ResourceMutationInterceptor interceptor : interceptors) {
                    interceptor.beforeSave(type, working);
                }
                if (!safeId.equals(id(working))) {
                    throw new IllegalArgumentException("JSON resource identity changed during create: " + safeId);
                }
                Map<String, Object> canonicalPayload = gson.fromJson(gson.toJson(working), Map.class);
                if (!expectedPayloadHash.equals(ResourcePayloadCodecs.json().hashPayload(canonicalPayload).canonicalText())) {
                    throw rejectAggregateCreate("RESOURCE_PAYLOAD_INVALID",
                        new IllegalArgumentException("JSON resource changed during aggregate create validation: " + safeId));
                }
                Path iconPath = motdIcon == null ? null : resolveIconPath(text(working, "icon"));
                if (motdIcon != null && iconPath == null) {
                    throw new IllegalArgumentException("MOTD icon target must be inside coordinated asset storage");
                }
                binaryWrites = motdIcon == null ? Map.of() : Map.of(iconPath, motdIcon);
            } catch (IllegalArgumentException failure) {
                throw rejectAggregateCreate(failure);
            }
            try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
                JsonAssetStore.AssetStamp afterCallbacks = store.readStamp(safeId);
                if (!Objects.equals(before, afterCallbacks) || beforeGeneration != store.cacheGeneration()) {
                    throw new IllegalStateException("JSON resource changed during create callbacks: " + safeId);
                }
                try {
                    result = store.create(working, binaryWrites, mutationId, expectedRevision, presentation);
                } catch (JsonAssetStore.PreCommitConflictException failure) {
                    throw AggregateResourceCreateStorage.rejectBeforeCommit("RESOURCE_PATH_CONFLICT",
                        failure.getMessage() + ". Choose another folder or name.", failure);
                }
            }
        } catch (RuntimeException failure) {
            notifySaveFailure(type, working, failure);
            throw failure;
        }
        FlowResourceMutationStamp stamp = mutationStamp(result.primaryStamp());
        completePostCommitRecovery(type, safeId, stamp.mutationId(), stamp.revision(), false);
        return result;
    }

    private AggregateResourceCreateStorage.PreCommitRejection rejectAggregateCreate(RuntimeException failure) {
        return rejectAggregateCreate(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.legacyValue(), failure);
    }

    private AggregateResourceCreateStorage.PreCommitRejection rejectAggregateCreate(String errorCode,
                                                                                      RuntimeException failure) {
        return AggregateResourceCreateStorage.rejectBeforeCommit(errorCode, failure.getMessage(), failure);
    }

    public void delete(String type, String id) {
        deleteMutation(type, id, UUID.randomUUID(), -1L, false);
    }

    public void delete(String type, String id, UUID mutationId, long expectedRevision) {
        requireMutationRequest(mutationId, expectedRevision);
        deleteMutation(type, id, mutationId, expectedRevision, true);
    }

    public void delete(String type, String id, long expectedRevision, UUID mutationId) {
        delete(type, id, mutationId, expectedRevision);
    }

    public boolean supportsAuthoritativeMutationIdentity() {
        return true;
    }

    public JsonAssetStore.AssetStamp readAssetStamp(String type, String id) {
        try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
            return requireStore(type).readStamp(id);
        }
    }

    public FlowResourceMutationStamp readMutationStamp(String type, String id) {
        try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
            return mutationStamp(requireStore(type).readStamp(id));
        }
    }

    public void completePostCommitRecovery(String type, String id, UUID mutationId, long revision, boolean deleted) {
        Objects.requireNonNull(mutationId, "Committed mutation ID is required");
        if (revision < 1L) throw new IllegalArgumentException("Committed revision must be positive");
        publishCommitted(committedResource(type, id, mutationId, revision, deleted));
    }

    private CommittedResource committedResource(String type, String id, UUID mutationId, long revision, Boolean deleted) {
        try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
            JsonAssetStore<JsonObject> store = requireStore(type);
            JsonAssetStore.AssetStamp before = store.readStamp(id);
            if (before == null) {
                if (mutationId != null || deleted != null) throw new IllegalStateException("Committed JSON resource is unavailable: " + type + "/" + id);
                return null;
            }
            FlowResourceMutationStamp stamp = mutationStamp(before);
            if (!type.equals(stamp.type()) || !id.equals(stamp.id()) || mutationId != null && !mutationId.equals(stamp.mutationId())
                || revision > 0L && revision != stamp.revision() || deleted != null && deleted != stamp.deleted()) {
                throw new IllegalStateException("Committed JSON resource identity changed: " + type + "/" + id);
            }
            JsonObject value = stamp.deleted() ? null : logicalPayload(store.get(id));
            if (!stamp.deleted() && value == null || !before.equals(store.readStamp(id))) {
                throw new IllegalStateException("Committed JSON resource changed during projection capture: " + type + "/" + id);
            }
            normalizeAssetId(value, id);
            return new CommittedResource(stamp, value);
        }
    }

    private FlowResourceMutationStamp mutationStamp(JsonAssetStore.AssetStamp stamp) {
        if (stamp == null) return null;
        UUID runtimeMutationId = stamp.runtimeMutationId().orElseThrow(() -> new IllegalStateException(
            "Adopted JSON resource lineage cannot be exposed as a runtime mutation UUID: " + stamp.mutationValue()));
        return new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision(), runtimeMutationId,
            stamp.payloadHash(), stamp.deleted());
    }

    private void publishCommitted(CommittedResource committed) {
        if (committed == null) return;
        FlowResourceMutationStamp stamp = committed.stamp();
        JsonObject value = committed.value();
        for (ResourceMutationInterceptor interceptor : interceptors) {
            interceptor.afterCommit(stamp.type(), stamp.id(), value == null ? null : value.deepCopy(), stamp);
        }
        notifyListeners(stamp.type(), stamp.id(), value, stamp.deleted());
    }

    private record CommittedResource(FlowResourceMutationStamp stamp, JsonObject value) {
    }

    public boolean matchesCommittedPayloadRecovery(String type, JsonObject previous, JsonObject requested,
                                                   JsonObject actual) {
        if (previous == null || requested == null) {
            return false;
        }
        if (!ReSyncResourceCatalog.MOTD_PROFILE.equals(type)) {
            return JsonAssetStore.matchesLegacyMergedPayload(previous, requested, actual, ownedFieldPaths(type));
        }
        JsonObject normalized = requested.deepCopy();
        try {
            prepareMotdIcon(normalized);
        } catch (RuntimeException failure) {
            return false;
        }
        return JsonAssetStore.matchesLegacyMergedPayload(previous, normalized, actual, ownedFieldPaths(type));
    }

    public JsonObject reload(String type, String id) {
        JsonAssetStore<JsonObject> store = requireStore(type);
        try {
            store.reload(id, candidate -> {
                normalizeAssetId(candidate, id);
                for (ResourceMutationInterceptor interceptor : interceptors) {
                    interceptor.beforeSave(type, candidate);
                }
                if (candidate != null && !Objects.equals(id(candidate), id)) {
                    throw new IllegalArgumentException("JSON resource identity changed during reload: " + id);
                }
            });
        } catch (RuntimeException failure) {
            notifySaveFailure(type, null, failure);
            throw failure;
        }
        CommittedResource committed = committedResource(type, id, null, 0L, null);
        publishCommitted(committed);
        return committed == null || committed.value() == null ? null : committed.value().deepCopy();
    }

    public void addListener(ResourceListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(ResourceListener listener) {
        listeners.remove(listener);
    }

    public void addInterceptor(ResourceMutationInterceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
        }
    }

    public void removeInterceptor(ResourceMutationInterceptor interceptor) {
        interceptors.remove(interceptor);
    }

    public synchronized void migrateLegacyAssets() {
        throw new IllegalStateException("Legacy JSON resource migration requires verified asset coordinator adoption");
    }

    public synchronized void bindMigrationReportsAuthority(MigrationReportsPersistenceParticipant authority) {
        Objects.requireNonNull(authority, "authority");
        if (!activeScopeRoot.equals(authority.rebindScope())) {
            throw new IllegalArgumentException("Migration reports authority scope does not match JSON resource storage scope");
        }
        if (migrationReportsAuthority != null && migrationReportsAuthority != authority) {
            throw new IllegalStateException("Migration reports authority is already bound");
        }
        migrationReportsAuthority = authority;
    }

    public synchronized void flushMigrationReports(MigrationReportsPersistenceParticipant authority, Path reportsRoot) throws IOException {
        if (persistenceState == PersistenceState.CLOSED) {
            throw new IOException("JSON resource persistence is closed");
        }
        Path root = requireMigrationReportsRoot(reportsRoot);
        if (migrationReportsAuthority != authority || authority == null || !authority.isAdmitted()
            || !authority.root().equals(root)) {
            throw new IOException("Migration reports can only be flushed by the admitted participant authority");
        }
        Path reportFile = recipeMigrationReportFile(root);
        if (pendingRecipeMigrationReport == null) {
            if (Files.exists(reportFile)) {
                validateRecipeMigrationReport(reportFile);
            }
            return;
        }
        RecipeMigrationReportContract.Report pending = pendingRecipeMigrationReport;
        byte[] expected = pending.canonicalJson().getBytes(StandardCharsets.UTF_8);
        try {
            AtomicFiles.writeNew(reportFile, expected);
        } catch (FileAlreadyExistsException collision) {
            RecipeMigrationReportContract.Report existing = readRecipeMigrationReport(reportFile);
            byte[] actual = Files.readAllBytes(reportFile);
            if (!pending.equals(existing) || !Arrays.equals(expected, actual)) {
                IOException failure = new IOException("Recipe migration report already exists with different content");
                failure.addSuppressed(collision);
                throw failure;
            }
        }
        validateRecipeMigrationReport(reportFile);
        pendingRecipeMigrationReport = null;
    }

    public synchronized boolean hasPendingMigrationReports() {
        return pendingRecipeMigrationReport != null;
    }

    public boolean allowsLegacyMigration() {
        return false;
    }

    private JsonAssetStore<JsonObject> requireStore(String type) {
        if (persistenceState == PersistenceState.CLOSED) {
            throw new IllegalStateException("JSON resource persistence is closed");
        }
        JsonAssetStore<JsonObject> store;
        synchronized (stores) {
            store = stores.get(type);
        }
        if (store == null) {
            throw new IllegalArgumentException("Unknown resource type: " + type);
        }
        return store;
    }

    private void saveMutation(String type, JsonObject value, UUID mutationId, long expectedRevision,
                              boolean authoritative) {
        JsonAssetStore<JsonObject> store = requireStore(type);
        JsonObject working = value == null ? null : value.deepCopy();
        String safeId = id(working);
        try {
            byte[] motdIcon;
            try {
                normalizeAssetId(working, safeId);
                validate(type, working);
                if (ReSyncResourceCatalog.RECIPE_DEFINITION.equals(type)) {
                    RecipeSchemaNormalizer.normalize(working);
                }
                motdIcon = ReSyncResourceCatalog.MOTD_PROFILE.equals(type) ? prepareMotdIcon(working) : null;
                if (safeId == null || safeId.isBlank()) {
                    throw new IllegalArgumentException("Invalid JSON resource id");
                }
            } catch (IllegalArgumentException rejection) {
                throw new FlowResourceAdapter.PreCommitRejection(rejection);
            }
            JsonAssetStore.AssetStamp before;
            long beforeGeneration;
            try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
                before = store.readStamp(safeId);
                beforeGeneration = store.cacheGeneration();
            }
            Path iconPath;
            try {
                for (ResourceMutationInterceptor interceptor : interceptors) {
                    interceptor.beforeSave(type, working);
                }
                if (!safeId.equals(id(working))) {
                    throw new IllegalArgumentException("JSON resource identity changed during save: " + safeId);
                }
                iconPath = motdIcon == null ? null : resolveIconPath(text(working, "icon"));
                if (motdIcon != null && iconPath == null) {
                    throw new IllegalArgumentException("MOTD icon target must be inside coordinated asset storage");
                }
            } catch (IllegalArgumentException rejection) {
                throw new FlowResourceAdapter.PreCommitRejection(rejection);
            }
            Map<Path, byte[]> binaryWrites = motdIcon == null ? Map.of() : Map.of(iconPath, motdIcon);
            try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
                JsonAssetStore.AssetStamp afterCallbacks = store.readStamp(safeId);
                if (!Objects.equals(before, afterCallbacks) || beforeGeneration != store.cacheGeneration()) {
                    throw new IllegalStateException("JSON resource changed during save callbacks: " + safeId);
                }
                if (authoritative) {
                    store.save(working, binaryWrites, mutationId, expectedRevision);
                } else {
                    store.save(working, binaryWrites);
                }
            }
        } catch (RuntimeException failure) {
            notifySaveFailure(type, working, failure);
            throw failure;
        }
        if (authoritative) {
            completePostCommitRecovery(type, safeId, mutationId, Math.addExact(expectedRevision, 1L), false);
        } else {
            publishCommitted(committedResource(type, safeId, null, 0L, false));
        }
    }

    private void deleteMutation(String type, String id, UUID mutationId, long expectedRevision,
                                boolean authoritative) {
        JsonAssetStore<JsonObject> store = requireStore(type);
        JsonAssetStore.AssetStamp before;
        long beforeGeneration;
        try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
            before = store.readStamp(id);
            beforeGeneration = store.cacheGeneration();
        } catch (RuntimeException failure) {
            notifyDeleteFailure(type, id, failure);
            throw failure;
        }
        try {
            for (ResourceMutationInterceptor interceptor : interceptors) {
                interceptor.beforeDelete(type, id);
            }
            try (AssetPersistenceGate.MutationLease ignored = acquirePersistenceMutation()) {
                JsonAssetStore.AssetStamp afterCallbacks = store.readStamp(id);
                if (!Objects.equals(before, afterCallbacks) || beforeGeneration != store.cacheGeneration()) {
                    throw new IllegalStateException("JSON resource changed during delete callbacks: " + id);
                }
                if (authoritative) {
                    store.delete(id, mutationId, expectedRevision);
                } else {
                    store.delete(id);
                }
            }
        } catch (RuntimeException failure) {
            notifyDeleteFailure(type, id, failure);
            throw failure;
        }
        if (authoritative) {
            completePostCommitRecovery(type, id, mutationId, Math.addExact(expectedRevision, 1L), true);
        } else {
            publishCommitted(committedResource(type, id, null, 0L, true));
        }
    }

    private void requireMutationRequest(UUID mutationId, long expectedRevision) {
        Objects.requireNonNull(mutationId, "mutationId");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Expected resource revision cannot be negative");
        }
    }

    private Path requireMigrationReportsRoot(Path reportsRoot) throws IOException {
        Path expected = MigrationPaths.requirePath(activeScopeRoot.resolve(".migrations"), "migration reports root");
        Path candidate = MigrationPaths.requirePath(reportsRoot, "migration reports root");
        if (!expected.equals(candidate)) {
            throw new IOException("Migration reports root must be dataRoot/.migrations");
        }
        if (Files.exists(candidate)) {
            MigrationPaths.requireDirectory(candidate, "migration reports root");
        }
        return candidate;
    }

    private Path recipeMigrationReportFile(Path reportsRoot) {
        Path root = MigrationPaths.requirePath(reportsRoot, "migration reports root");
        return root.resolve(RecipeMigrationReportContract.FILE_NAME).toAbsolutePath().normalize();
    }

    private RecipeMigrationReportContract.Report readRecipeMigrationReport(Path reportFile) {
        try {
            return RecipeMigrationReportContract.read(reportFile);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Recipe migration report could not be read", exception);
        }
    }

    private void validateRecipeMigrationReport(Path reportFile) {
        readRecipeMigrationReport(reportFile);
    }

    public List<String> resourceTypes() {
        return resourceTypesStatic();
    }

    public static List<String> resourceTypesStatic() {
        return ReSyncResourceCatalog.jsonStorageTypes();
    }

    private String id(JsonObject value) {
        if (value == null || !value.has("id") || value.get("id").isJsonNull()) {
            return "";
        }
        return value.get("id").getAsString();
    }

    private void normalizeAssetId(JsonObject value, String id) {
        if (value == null || id == null || id.isBlank()) {
            return;
        }
        value.addProperty("id", id);
    }

    private String folder(JsonObject value, String defaultFolder) {
        if (value == null || !value.has("folder") || value.get("folder").isJsonNull()) {
            return AssetFileFormat.canonicalFolder(defaultFolder, defaultFolder);
        }
        String folder = value.get("folder").getAsString();
        return AssetFileFormat.canonicalFolder(folder, defaultFolder);
    }

    private String legacyFolder(String type) {
        return switch (type) {
            case ReSyncResourceCatalog.CHAT -> "chat";
            case ReSyncResourceCatalog.MOTD_PROFILE -> "motd-profiles";
            case ReSyncResourceCatalog.MESSAGE_RULE -> "message-rules";
            case ReSyncResourceCatalog.RECIPE_DEFINITION -> "recipes";
            case ReSyncResourceCatalog.TEXT_TEMPLATE -> "text-templates";
            case ReSyncResourceCatalog.ADVANCEMENT_TREE -> "advancement-trees";
            case ReSyncResourceCatalog.DIALOG -> "dialogs";
            case ReSyncResourceCatalog.TRADE_PROFILE -> "trade-profiles";
            case ReSyncResourceCatalog.NPC_DEFINITION -> "npcs";
            case ReSyncResourceCatalog.LOOT_TABLE -> "loot-tables";
            default -> type;
        };
    }

    private void notifyListeners(String type, String id, JsonObject value, boolean deleted) {
        JsonObject logical = logicalPayload(value);
        for (ResourceListener listener : listeners) {
            try {
                listener.resourceChanged(type, id, logical == null ? null : logical.deepCopy(), deleted);
            } catch (Throwable failure) {
                Log.error("JSON resource post-commit listener failed for " + type + "/" + id, failure);
            }
        }
    }

    private JsonObject parse(String type, String json) {
        JsonObject value = gson.fromJson(json, JsonObject.class);
        validate(type, value);
        return value;
    }

    private void validate(String type, JsonObject value) {
        if (ReSyncResourceCatalog.SCHEDULE_DEFINITION.equals(type)) {
            ScheduleDefinition.from(value, id(value));
        }
    }

    private JsonObject logicalPayload(JsonObject value) {
        return value == null ? null : JsonAssetStore.logicalPayload(value);
    }

    private void notifySaveFailure(String type, JsonObject value, RuntimeException failure) {
        for (ResourceMutationInterceptor interceptor : interceptors) {
            try {
                interceptor.afterSaveFailure(type, value, failure);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private void notifyDeleteFailure(String type, String id, RuntimeException failure) {
        for (ResourceMutationInterceptor interceptor : interceptors) {
            try {
                interceptor.afterDeleteFailure(type, id, failure);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private JsonObject motdProfileForClient(JsonObject value) {
        JsonObject copy = value.deepCopy();
        if (!text(copy, "iconData").isBlank()) {
            return copy;
        }
        Path icon = resolveIconPath(text(copy, "icon"));
        if (icon == null) {
            return copy;
        }
        try {
            CachedIconData data = cachedIconData(icon);
            if (data == null) {
                return copy;
            }
            copy.addProperty("iconData", data.data());
            if (text(copy, "iconHash").isBlank()) {
                copy.addProperty("iconHash", data.hash());
            }
        } catch (IOException ignored) {
        }
        return copy;
    }

    private CachedIconData cachedIconData(Path icon) throws IOException {
        Path root = assetsRoot.toAbsolutePath().normalize();
        Path path = icon.toAbsolutePath().normalize();
        if (path.equals(root) || !path.startsWith(root)) {
            throw new IOException("MOTD icon is outside coordinated asset storage");
        }
        String relative = root.relativize(path).toString().replace('\\', '/');
        AssetKey key = new AssetKey("blob", StorageSafety.sha256(relative.getBytes(StandardCharsets.UTF_8)));
        Snapshot snapshot = requireCoordinator().read(current -> current);
        ExpectedState state = snapshot.state(key).orElse(null);
        String mutation = snapshot.mutationValue(key).orElse(null);
        if (!(state instanceof Live live) || mutation == null
            || !snapshot.path(key).filter(path::equals).isPresent()) {
            iconDataCache.remove(relative);
            throw new IOException("MOTD icon has no committed asset state");
        }
        CachedIconData cached = iconDataCache.get(relative);
        if (cached != null && cached.revision() == live.revision()
            && cached.mutation().equals(mutation) && cached.hash().equals(live.hash())) {
            return cached;
        }
        MigrationPaths.requireNoSymlinkTraversal(root, path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("MOTD icon is not a regular asset file");
        }
        byte[] bytes = readIconBytes(path);
        if (!live.hash().equals(StorageSafety.sha256(bytes))) {
            iconDataCache.remove(relative);
            throw new IOException("MOTD icon does not match its committed asset state");
        }
        BufferedImage image = validPngIcon(bytes);
        if (image == null) {
            iconDataCache.remove(relative);
            return null;
        }
        CachedIconData fresh = new CachedIconData(live.revision(), mutation, Base64.getEncoder().encodeToString(bytes), live.hash());
        boolean published = requireCoordinator().read(current -> {
            if (current.state(key).filter(live::equals).isPresent()
                && current.mutationValue(key).filter(mutation::equals).isPresent()
                && current.path(key).filter(path::equals).isPresent()) {
                iconDataCache.put(relative, fresh);
                return true;
            }
            return false;
        });
        if (!published) {
            throw new IOException("MOTD icon changed during asset admission");
        }
        return fresh;
    }

    private byte[] prepareMotdIcon(JsonObject value) {
        String iconData = text(value, "iconData");
        if (iconData.isBlank()) {
            return null;
        }
        if (iconData.length() > MAX_ICON_TEXT + 128) {
            throw new IllegalArgumentException("MOTD icon exceeds its byte limit");
        }
        String encoded = stripImageDataPrefix(iconData);
        if (encoded.length() > MAX_ICON_TEXT) {
            throw new IllegalArgumentException("MOTD icon exceeds its byte limit");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MOTD icon must be valid PNG data");
        }
        BufferedImage image = validPngIcon(bytes);
        if (image == null) {
            throw new IllegalArgumentException("MOTD icon must be 64x64 PNG");
        }
        String actualHash = sha256(bytes);
        String hash = text(value, "iconHash");
        if (!hash.isBlank() && (!hash.matches("[0-9a-f]{64}") || !hash.equals(actualHash))) {
            throw new IllegalArgumentException("MOTD icon hash does not match its PNG data");
        }
        hash = actualHash;
        value.addProperty("iconHash", hash);
        String relative = "assets/motd-icons/" + hash + ".png";
        value.addProperty("icon", relative);
        return bytes;
    }

    private byte[] readIconBytes(Path path) throws IOException {
        if (Files.size(path) > MAX_ICON_BYTES) {
            throw new IOException("MOTD icon exceeds its byte limit");
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_ICON_BYTES + 1);
            if (bytes.length > MAX_ICON_BYTES) {
                throw new IOException("MOTD icon exceeds its byte limit");
            }
            return bytes;
        }
    }

    private BufferedImage validPngIcon(byte[] bytes) {
        if (!hasPngSignature(bytes) || bytes.length > MAX_ICON_BYTES) {
            return null;
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    return null;
                }
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != 64 || reader.getHeight(0) != 64) {
                    return null;
                }
                BufferedImage image = reader.read(0);
                return image != null && image.getWidth() == 64 && image.getHeight() == 64 ? image : null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private boolean hasPngSignature(byte[] bytes) {
        return bytes != null && bytes.length >= 8
            && bytes[0] == (byte) 0x89
            && bytes[1] == 0x50
            && bytes[2] == 0x4E
            && bytes[3] == 0x47
            && bytes[4] == 0x0D
            && bytes[5] == 0x0A
            && bytes[6] == 0x1A
            && bytes[7] == 0x0A;
    }

    private Path resolveIconPath(String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        Path path = Path.of(icon);
        if (path.isAbsolute()) {
            return null;
        }
        Path resolved = activeScopeRoot.resolve(path).toAbsolutePath().normalize();
        Path root = assetsRoot.toAbsolutePath().normalize();
        return resolved.equals(root) || !resolved.startsWith(root) ? null : resolved;
    }

    private String stripImageDataPrefix(String data) {
        int comma = data.indexOf(',');
        return data.startsWith("data:image/") && comma >= 0 ? data.substring(comma + 1) : data;
    }

    private String text(JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) {
            return "";
        }
        return value.get(key).getAsString();
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @FunctionalInterface
    public interface ResourceListener {
        void resourceChanged(String type, String id, JsonObject value, boolean deleted);
    }

    public interface ResourceMutationInterceptor {
        default void beforeSave(String type, JsonObject value) {
        }

        default void beforeDelete(String type, String id) {
        }

        default void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
        }

        default void afterSaveFailure(String type, JsonObject value, RuntimeException failure) {
        }

        default void afterDeleteFailure(String type, String id, RuntimeException failure) {
        }
    }
}
