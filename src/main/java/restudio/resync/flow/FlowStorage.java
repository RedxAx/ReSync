package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.Log;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetIntegrityService;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetProjectMetadata;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.StorageSafety;
import restudio.resync.flow.validation.FlowGraphValidationException;
import restudio.resync.flow.validation.FlowGraphValidationResult;
import restudio.resync.flow.validation.FlowGraphValidator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.server.ConfigurationPersistenceParticipant;
import restudio.resync.server.AggregateResourceCreateStorage;
import restudio.resync.server.TemporaryLifecycleDiagnostics;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class FlowStorage {
    private final File flowDir;
    private final File guiDir;
    private final File scoreboardDir;
    private final File tabDir;
    private final File projectMetadataDir;
    private volatile File assetsDir;
    private final File configFile;
    private final ConfigurationPersistenceParticipant configurationPersistence;
    private final Gson gson = new Gson();
    private volatile AssetTransactionCoordinator assetTransactions;
    private volatile AssetIntegrityService assetIntegrity;
    private final LegacyRuntimeActivationGate legacyRuntimeGate;
    private final AssetPersistenceGate assetsGate;
    private final ServerId serverId;
    private final Consumer<Path> assetTraversalObserver;
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command");
    private static final Set<String> TYPED_RESOURCE_TYPES = Set.of("gui", "scoreboard", "tab");
    private static final String DEFAULT_SCOREBOARD_ID_KEY = "flow.default-scoreboard.id";
    private static final String DEFAULT_SCOREBOARD_USE_PAPI_KEY = "flow.default-scoreboard.usePapi";
    private static final String DEFAULT_TAB_ID_KEY = "flow.default-tab.id";
    private static final String DEFAULT_TAB_USE_PAPI_KEY = "flow.default-tab.usePapi";
    private static final String REFRESH_INTERVAL_KEY = "flow.refresh-interval-ticks";
    private static final String PROJECT_METADATA_TYPE = "project_metadata";
    private static final String PROJECT_METADATA_LINEAGE_TYPE = "project_metadata.lineage";
    private static final String PROJECT_METADATA_LINEAGE_ID = "project";
    private static final String PROJECT_METADATA_LINEAGE_FORMAT = "project-metadata-lineage-v1";
    private static final int MAX_TYPED_COMMAND_REFRESH_PROOFS = 256;
    private final Map<String, FlowGraph> graphCache = new ConcurrentHashMap<>();
    private final CoreGraphStorageBoundary coreGraphStorage = new CoreGraphStorageBoundary();
    private final Map<String, GuiDefinition> guiCache = new ConcurrentHashMap<>();
    private final Map<String, ScoreboardDefinition> scoreboardCache = new ConcurrentHashMap<>();
    private final Map<String, TabDefinition> tabCache = new ConcurrentHashMap<>();
    private final Map<String, CachedResourceIdentity> guiCacheIdentities = new ConcurrentHashMap<>();
    private final Map<String, CachedResourceIdentity> scoreboardCacheIdentities = new ConcurrentHashMap<>();
    private final Map<String, CachedResourceIdentity> tabCacheIdentities = new ConcurrentHashMap<>();
    private final Map<String, RuntimeTabIdentity> runtimeTabIdentities = new ConcurrentHashMap<>();
    private volatile String defaultScoreboardId;
    private volatile boolean defaultScoreboardUsePapi = true;
    private volatile String defaultTabId;
    private volatile boolean defaultTabUsePapi = true;
    private volatile int tabRefreshIntervalTicks = 20;
    private volatile PersistenceState persistenceState = PersistenceState.OPEN;
    private volatile long persistenceGeneration;
    private volatile boolean persistenceTransition;
    private FlowGraphValidator graphValidator;
    private Function<FlowGraph, FlowGraphValidationResult> graphValidationFunction;
    private Consumer<GraphChange> graphChangeListener;
    private volatile Consumer<String> typedCommandGraphChangeListener;
    private long typedCommandGraphChangeListenerGeneration;
    private final Set<TypedCommandRefreshProof> typedCommandRefreshProofs = new LinkedHashSet<>();

    private enum PersistenceState {
        OPEN,
        QUIESCED
    }

    private record PersistenceGeneration(long generation, AssetTransactionCoordinator coordinator, Path root) {
    }

    private record FileStamp(long size, FileTime modified, Object fileKey) {
    }

    private record CachedResourceIdentity(long revision, String mutationId, String resourceHash) {
    }

    private record TypedCommandRefreshProof(ServerResourceLocator locator, long revision, UUID mutationId,
                                             boolean deleted) {
        private TypedCommandRefreshProof {
            locator = Objects.requireNonNull(locator, "Typed command refresh locator is required");
            if (revision < 1L) {
                throw new IllegalArgumentException("Typed command refresh revision must be positive");
            }
            mutationId = Objects.requireNonNull(mutationId, "Typed command refresh mutation ID is required");
        }
    }

    private record PreloadResource(Path path, boolean coordinated) {
    }

    private record CommandMigrationEvidence(Set<String> bindingIds, Set<String> graphIds) {
        private CommandMigrationEvidence {
            bindingIds = bindingIds == null ? Set.of() : Set.copyOf(bindingIds);
            graphIds = graphIds == null ? Set.of() : Set.copyOf(graphIds);
        }

        private Set<String> ids() {
            Set<String> ids = new HashSet<>(bindingIds);
            ids.addAll(graphIds);
            return ids;
        }
    }

    private record ReloadGraphState(FlowGraph graph, String type, Path file, FileStamp stamp,
                                    long revision, String mutationId, String resourceHash) {
    }

    public record GraphIdentity(String type, String id, long revision, String mutationId, boolean deleted, String payloadHash) {
        public GraphIdentity(String type, String id, long revision, String mutationId, boolean deleted) {
            this(type, id, revision, mutationId, deleted, "");
        }

        public GraphIdentity(String type, String id, long revision, String mutationId, String payloadHash, boolean deleted) {
            this(type, id, revision, mutationId, deleted, payloadHash);
        }

        public GraphIdentity {
            type = type == null ? "" : type;
            id = id == null ? "" : id;
            revision = Math.max(0L, revision);
            mutationId = mutationId == null ? "" : mutationId;
            payloadHash = payloadHash == null ? "" : payloadHash;
        }
    }

    public record ResourceIdentity(String type, String id, long revision, String mutationId, boolean deleted, String payloadHash) {
        public ResourceIdentity {
            type = type == null ? "" : type;
            id = id == null ? "" : id;
            revision = Math.max(0L, revision);
            mutationId = mutationId == null ? "" : mutationId;
            payloadHash = payloadHash == null ? "" : payloadHash;
        }
    }

    public record CoreAggregateCreate(CoreGraphStorageBoundary.Decoded primary,
                                      ResourceIdentity projectMetadataIdentity,
                                      String canonicalProjectMetadataJson, boolean replayed) {
        public CoreAggregateCreate {
            primary = Objects.requireNonNull(primary, "primary");
            projectMetadataIdentity = Objects.requireNonNull(projectMetadataIdentity, "projectMetadataIdentity");
            canonicalProjectMetadataJson = Objects.requireNonNull(canonicalProjectMetadataJson,
                "canonicalProjectMetadataJson");
            if (projectMetadataIdentity.deleted()) {
                throw new IllegalArgumentException("Aggregate Core create project metadata must be live");
            }
        }
    }

    public record TypedAggregateCreate(ResourceIdentity primaryIdentity, String canonicalPayloadJson,
                                       ResourceIdentity projectMetadataIdentity,
                                       String canonicalProjectMetadataJson, boolean replayed) {
        public TypedAggregateCreate {
            primaryIdentity = Objects.requireNonNull(primaryIdentity, "primaryIdentity");
            canonicalPayloadJson = Objects.requireNonNull(canonicalPayloadJson, "canonicalPayloadJson");
            projectMetadataIdentity = Objects.requireNonNull(projectMetadataIdentity, "projectMetadataIdentity");
            canonicalProjectMetadataJson = Objects.requireNonNull(canonicalProjectMetadataJson,
                "canonicalProjectMetadataJson");
            if (primaryIdentity.deleted() || projectMetadataIdentity.deleted()) {
                throw new IllegalArgumentException("Aggregate typed create resources must be live");
            }
        }
    }

    public record LegacyCoreRecoverySource(long revision, UUID mutationId, ContentHash assetHash,
                                           ContentHash payloadHash, String payloadKind) {
    }

    public record LegacyCoreRecoveryResult(CoreGraphStorageBoundary.CoreGraphTombstone tombstone,
                                           ResourceIdentity projectMetadataIdentity,
                                           String canonicalProjectMetadataJson) {
    }

    public record CoreRevisionRepairSource(long revision, UUID mutationId, ContentHash assetHash,
                                           ContentHash payloadHash, String payloadKind,
                                           ResourceActivationState activationState) {
        public CoreRevisionRepairSource {
            if (revision < 1L) {
                throw new IllegalArgumentException("Core revision repair source revision must be positive");
            }
            mutationId = Objects.requireNonNull(mutationId, "Core revision repair source mutation ID is required");
            assetHash = Objects.requireNonNull(assetHash, "Core revision repair source asset hash is required");
            payloadHash = Objects.requireNonNull(payloadHash, "Core revision repair source payload hash is required");
            payloadKind = Objects.requireNonNull(payloadKind, "Core revision repair source payload kind is required");
            activationState = Objects.requireNonNull(activationState,
                "Core revision repair source activation state is required");
        }
    }

    public record CoreRevisionRepairResult(CoreGraphStorageBoundary.Decoded repaired, boolean replayed) {
        public CoreRevisionRepairResult {
            repaired = Objects.requireNonNull(repaired, "Repaired Core graph is required");
        }
    }

    public record CoreCatalogRebindSource(CoreGraphStorageBoundary.Decoded decoded) {
        public CoreCatalogRebindSource {
            decoded = Objects.requireNonNull(decoded, "Core catalog rebind source is required");
        }
    }

    public record CoreCatalogRebindResult(CoreGraphStorageBoundary.Decoded rebound, boolean replayed) {
        public CoreCatalogRebindResult {
            rebound = Objects.requireNonNull(rebound, "Rebound Core graph is required");
        }
    }

    private record CoreSaveResult(CoreGraphStorageBoundary.Decoded primary,
                                  ResourceIdentity projectMetadataIdentity,
                                  String canonicalProjectMetadataJson, boolean replayed) {
    }

    public record ProjectMetadataReconciliation(
        Set<AssetTransactionCoordinator.AssetKey> missingDurableResources,
        Set<AssetTransactionCoordinator.AssetKey> removedStaleResources,
        Set<AssetTransactionCoordinator.AssetKey> restoredPresentationResources,
        int inspectedResourceCount, boolean changed) {
        public ProjectMetadataReconciliation {
            missingDurableResources = Set.copyOf(Objects.requireNonNull(
                missingDurableResources, "missingDurableResources"));
            removedStaleResources = Set.copyOf(Objects.requireNonNull(removedStaleResources, "removedStaleResources"));
            restoredPresentationResources = Set.copyOf(Objects.requireNonNull(
                restoredPresentationResources, "restoredPresentationResources"));
            inspectedResourceCount = Math.max(0, inspectedResourceCount);
        }
    }

    private record StoredGraphState(GraphIdentity identity, Path assetFile, Path tombstoneFile) {
    }

    private record LegacyGraphDeleteSource(Path path, String payloadHash) {
    }

    private enum CoreStateKind {
        ABSENT,
        LIVE,
        TOMBSTONED,
        LEGACY
    }

    private record CoreStoredState(CoreStateKind kind, CoreGraphStorageBoundary.Decoded decoded,
                                   CoreGraphStorageBoundary.CoreGraphTombstone tombstone,
                                   Path liveFile, List<Path> liveFiles) {
        private CoreStoredState {
            kind = Objects.requireNonNull(kind, "Core state kind is required");
            liveFiles = liveFiles == null ? List.of() : List.copyOf(liveFiles);
            if (kind == CoreStateKind.LIVE && decoded == null) {
                throw new IllegalArgumentException("Live Core state requires a decoded payload");
            }
            if (kind == CoreStateKind.TOMBSTONED && tombstone == null) {
                throw new IllegalArgumentException("Tombstoned Core state requires a tombstone");
            }
        }

        private static CoreStoredState absent() {
            return new CoreStoredState(CoreStateKind.ABSENT, null, null, null, List.of());
        }

        private static CoreStoredState legacy(Path liveFile, List<Path> liveFiles) {
            return new CoreStoredState(CoreStateKind.LEGACY, null, null, liveFile, liveFiles);
        }

        private static CoreStoredState live(CoreGraphStorageBoundary.Decoded decoded, Path liveFile,
                                            List<Path> liveFiles) {
            return new CoreStoredState(CoreStateKind.LIVE, decoded, null, liveFile, liveFiles);
        }

        private static CoreStoredState tombstoned(CoreGraphStorageBoundary.CoreGraphTombstone tombstone) {
            return new CoreStoredState(CoreStateKind.TOMBSTONED, null, tombstone, null, List.of());
        }

        private long revision() {
            return switch (kind) {
                case LIVE -> decoded.envelope().assetRevision();
                case TOMBSTONED -> tombstone.revision();
                case ABSENT, LEGACY -> 0L;
            };
        }

        private String mutationId() {
            return switch (kind) {
                case LIVE -> decoded.envelope().assetMutationId();
                case TOMBSTONED -> tombstone.mutationId().toString();
                case ABSENT, LEGACY -> "";
            };
        }
    }

    private record CoreProjectMetadata(ProjectMetadataSnapshot snapshot, String json) {
        private CoreProjectMetadata {
            snapshot = Objects.requireNonNull(snapshot, "Core project metadata snapshot is required");
        }
    }

    public FlowStorage(JavaPlugin plugin, AssetTransactionCoordinator assetTransactions) {
        this(plugin.getDataFolder(), LegacyRuntimeActivationGate.runtime(plugin.getDataFolder()), assetTransactions);
    }

    public FlowStorage(File dataFolder, AssetTransactionCoordinator assetTransactions) {
        this(dataFolder, LegacyRuntimeActivationGate.runtime(dataFolder), assetTransactions);
    }

    public FlowStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate,
                       AssetTransactionCoordinator assetTransactions) {
        this(dataFolder, legacyRuntimeGate, new AssetPersistenceGate(dataFolder.toPath()), assetTransactions);
    }

    public FlowStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate, AssetPersistenceGate assetsGate,
                       AssetTransactionCoordinator assetTransactions) {
        this(dataFolder, legacyRuntimeGate, assetsGate, newConfigurationPersistence(dataFolder), null, assetTransactions);
    }

    public FlowStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate, AssetPersistenceGate assetsGate,
                       ServerId serverId, AssetTransactionCoordinator assetTransactions) {
        this(dataFolder, legacyRuntimeGate, assetsGate, newConfigurationPersistence(dataFolder), serverId, assetTransactions);
    }

    FlowStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate, AssetPersistenceGate assetsGate,
                ServerId serverId, AssetTransactionCoordinator assetTransactions, Consumer<Path> assetTraversalObserver) {
        this(dataFolder, legacyRuntimeGate, assetsGate, newConfigurationPersistence(dataFolder), serverId,
            assetTransactions, assetTraversalObserver);
    }

    public FlowStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate, AssetPersistenceGate assetsGate,
                       ConfigurationPersistenceParticipant configurationPersistence,
                       AssetTransactionCoordinator assetTransactions) {
        this(dataFolder, legacyRuntimeGate, assetsGate, configurationPersistence, null, assetTransactions);
    }

    public FlowStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate, AssetPersistenceGate assetsGate,
                       ConfigurationPersistenceParticipant configurationPersistence, ServerId serverId,
                       AssetTransactionCoordinator assetTransactions) {
        this(dataFolder, legacyRuntimeGate, assetsGate, configurationPersistence, serverId, assetTransactions, ignored -> {
        });
    }

    FlowStorage(File dataFolder, LegacyRuntimeActivationGate legacyRuntimeGate, AssetPersistenceGate assetsGate,
                ConfigurationPersistenceParticipant configurationPersistence, ServerId serverId,
                AssetTransactionCoordinator assetTransactions, Consumer<Path> assetTraversalObserver) {
        this.flowDir = new File(dataFolder, "flows");
        this.guiDir = new File(dataFolder, "guis");
        this.scoreboardDir = new File(dataFolder, "scoreboards");
        this.tabDir = new File(dataFolder, "tabs");
        this.projectMetadataDir = new File(dataFolder, "project-metadata");
        this.assetsDir = new File(dataFolder, "assets");
        this.legacyRuntimeGate = Objects.requireNonNull(legacyRuntimeGate, "legacyRuntimeGate");
        this.assetsGate = Objects.requireNonNull(assetsGate, "assetsGate");
        this.assetTraversalObserver = Objects.requireNonNull(assetTraversalObserver, "assetTraversalObserver");
        requireAssetGateScope(this.assetsDir.toPath(), this.assetsGate);
        this.serverId = serverId;
        this.configurationPersistence = requireConfigurationPersistence(dataFolder, configurationPersistence);
        this.configFile = this.configurationPersistence.root().toFile();
        this.assetTransactions = requireAssetTransactions(this.assetsDir.toPath(), assetTransactions);

        try {
            Path assetsRoot = assetsDir.toPath().toAbsolutePath().normalize();
            MigrationPaths.requirePath(assetsRoot, "Flow asset root");
            if (!Files.exists(assetsRoot, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(assetsRoot);
            }
            MigrationPaths.requireDirectory(assetsRoot, "Flow asset root");
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to initialize the Flow asset root safely", exception);
        }
        this.assetIntegrity = new AssetIntegrityService(assetsDir.toPath());
        loadDefaultScoreboard();
        loadDefaultTab();
        loadTabRefreshConfig();
        if (legacyRuntimeGate.allowsLegacyMigration()) {
            cleanupBelowNameData();
            migrateLegacyAssets();
        }
    }

    public synchronized FlowGraph getGraph(String id) {
        String safeId = safeId(id, "load flow");
        if (safeId == null) {
            return null;
        }
        String type = resolveStoredGraphType(safeId);
        return type.isBlank() ? null : getGraph(type, safeId);
    }

    public synchronized Optional<CoreGraphStorageBoundary.Decoded> getCoreGraph(String type, String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            if (serverId == null) {
                throw new IllegalStateException("Core graph storage requires an authoritative server ID");
            }
            if (!GRAPH_TYPES.contains(type)) {
                throw new IllegalArgumentException("Core graph type must be flow, function, or command");
            }
            String safeId = safeId(id, "load Core graph");
            if (safeId == null) {
                return Optional.empty();
            }
            try {
                CoreProjectMetadata metadata = readCoreProjectMetadata();
                CoreStoredState state = readCoreStoredState(type, safeId, coreResource(type, safeId), metadata.snapshot());
                requireCoordinatedCoreState(requireAssetTransactions(), type, safeId, state);
                return state.kind() == CoreStateKind.LIVE ? Optional.of(state.decoded()) : Optional.empty();
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Failed to load Core graph " + type + ':' + safeId, exception);
            }
        }
    }

    public synchronized Optional<LegacyCoreRecoverySource> legacyCoreRecoverySource(ServerResourceLocator resource,
                                                                                     UUID sourceMutationId,
                                                                                     long sourceRevision,
                                                                                     ContentHash sourceAssetHash) {
        Objects.requireNonNull(resource, "Legacy Core recovery resource is required");
        Objects.requireNonNull(sourceMutationId, "Legacy Core recovery source mutation ID is required");
        Objects.requireNonNull(sourceAssetHash, "Legacy Core recovery source asset hash is required");
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            CoreProjectMetadata metadata = readCoreProjectMetadata();
            CoreStoredState stored = readCoreStoredState(resource.resourceType().value(), resource.id(), resource,
                metadata.snapshot());
            if (stored.kind() != CoreStateKind.LEGACY) {
                return Optional.empty();
            }
            return Optional.of(requireLegacyCoreRecoverySource(resource, sourceMutationId, sourceRevision,
                sourceAssetHash));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect legacy Core recovery source", exception);
        }
    }

    public synchronized Optional<CoreRevisionRepairResult> repairCoreRevisionSkew(
        ServerResourceLocator resource, CoreRevisionRepairSource source, UUID repairMutationId) {
        Objects.requireNonNull(resource, "Core revision repair resource is required");
        Objects.requireNonNull(source, "Core revision repair source is required");
        Objects.requireNonNull(repairMutationId, "Core revision repair mutation ID is required");
        if (serverId == null || !serverId.equals(resource.serverId()) || !GRAPH_TYPES.contains(
            resource.resourceType().value())) {
            throw new IllegalArgumentException("Core revision repair resource is outside this storage authority");
        }
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            AssetTransactionCoordinator coordinator = requireAssetTransactions();
            CoreRevisionRepairResult replay = coreRevisionRepairReplay(coordinator, resource, source,
                repairMutationId, true);
            if (replay != null) {
                return Optional.of(replay);
            }
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
            AssetTransactionCoordinator.AssetKey key = assetKey(resource.resourceType().value(), resource.id());
            AssetTransactionCoordinator.ExpectedState expected = snapshot.state(key).orElse(null);
            Path path = snapshot.path(key).map(value -> value.toAbsolutePath().normalize()).orElse(null);
            if (!(expected instanceof AssetTransactionCoordinator.Live live)
                || live.revision() != source.revision()
                || !snapshot.mutationValue(key).filter(source.mutationId().toString()::equals).isPresent()
                || path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            byte[] sourceBytes = Files.readAllBytes(path);
            if (!live.hash().equals(StorageSafety.sha256(sourceBytes))) {
                throw new IOException("Core revision repair source does not match coordinator state");
            }
            try {
                CoreGraphStorageBoundary.Decoded valid = coreGraphStorage.decode(sourceBytes, resource);
                if (!source.assetHash().equals(valid.envelope().assetHash())
                    || !source.mutationId().toString().equals(valid.envelope().assetMutationId())
                    || source.revision() != valid.envelope().assetRevision()
                    || !source.payloadKind().equals(valid.corePayloadKind())
                    || source.activationState() != valid.envelope().assetActivationState()
                    || !source.payloadHash().equals(corePayloadChecksum(valid))) {
                    throw new IOException("Core revision repair source receipt does not match the valid coordinated asset");
                }
                return Optional.empty();
            } catch (IllegalArgumentException invalid) {
            }
            CoreGraphStorageBoundary.RevisionSkew skew = coreGraphStorage.decodeOneBehindRevision(sourceBytes,
                resource);
            CoreGraphStorageBoundary.Decoded sourceAsset = skew.source();
            if (!source.assetHash().equals(sourceAsset.envelope().assetHash())
                || !source.mutationId().toString().equals(sourceAsset.envelope().assetMutationId())
                || source.revision() != sourceAsset.envelope().assetRevision()
                || !source.payloadKind().equals(sourceAsset.corePayloadKind())
                || source.activationState() != sourceAsset.envelope().assetActivationState()
                || !source.payloadHash().equals(corePayloadChecksum(sourceAsset))) {
                throw new IOException("Core revision repair source receipt does not match the coordinated asset");
            }
            long repairedRevision = Math.addExact(source.revision(), 1L);
            CoreGraphStorageBoundary.AssetMetadata repairedMetadata = new CoreGraphStorageBoundary.AssetMetadata(
                resource.resourceType().value(), repairedRevision, repairMutationId,
                source.activationState());
            CoreGraphStorageBoundary.Decoded repaired = coreGraphStorage.repairOneBehindRevision(sourceBytes,
                resource, repairedMetadata);
            byte[] repairedBytes = coreGraphStorage.encode(repaired);
            JsonObject scope = coreRevisionRepairScope(resource, source, repairMutationId);
            AssetTransactionCoordinator.TransactionRequest request = new AssetTransactionCoordinator.TransactionRequest(
                repairMutationId, snapshot.project(), List.of(AssetTransactionCoordinator.AssetDelta.write(key,
                path, expected, repairedBytes)), List.of());
            try {
                coordinator.withTransactionIntentScope(scope, () -> {
                    try {
                        return coordinator.transact(request);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
            CoreRevisionRepairResult recovered = coreRevisionRepairReplay(coordinator, resource, source,
                repairMutationId, false);
            if (recovered == null) {
                throw new IOException("Core revision repair transaction was not durably published");
            }
            evictGraphCache(resource.id());
            return Optional.of(recovered);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to repair Core graph revision skew "
                + resource.resourceType().value() + ':' + resource.id(), exception);
        }
    }

    public synchronized LegacyCoreRecoveryResult recoverLegacyCoreGraph(ServerResourceLocator resource,
                                                                         UUID sourceMutationId,
                                                                         long sourceRevision,
                                                                         ContentHash sourceAssetHash,
                                                                         UUID recoveryMutationId) {
        Objects.requireNonNull(resource, "Legacy Core recovery resource is required");
        Objects.requireNonNull(sourceMutationId, "Legacy Core recovery source mutation ID is required");
        Objects.requireNonNull(sourceAssetHash, "Legacy Core recovery source asset hash is required");
        Objects.requireNonNull(recoveryMutationId, "Legacy Core recovery mutation ID is required");
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            AssetTransactionCoordinator coordinator = requireAssetTransactions();
            LegacyCoreRecoveryResult replay = legacyCoreRecoveryReplay(coordinator, resource, sourceMutationId,
                sourceRevision, sourceAssetHash, recoveryMutationId);
            if (replay != null) {
                return replay;
            }
            LegacyCoreRecoverySource source = requireLegacyCoreRecoverySource(resource, sourceMutationId,
                sourceRevision, sourceAssetHash);
            CoreProjectMetadata projectMetadata = readCoreProjectMetadata();
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
            AssetTransactionCoordinator.AssetKey sourceKey = assetKey(resource.resourceType().value(), resource.id());
            AssetTransactionCoordinator.Live sourceState = new AssetTransactionCoordinator.Live(source.revision(),
                source.assetHash().canonicalText());
            Path sourcePath = snapshot.path(sourceKey).orElseThrow(() ->
                new IOException("Legacy Core recovery source path is unavailable"));
            byte[] sourceBytes = readCoreAssetBytes(sourcePath);
            AssetTransactionCoordinator.AssetKey quarantineKey = legacyCoreQuarantineKey(resource, sourceMutationId);
            Path quarantinePath = legacyCoreQuarantinePath(resource, sourceMutationId);
            AssetTransactionCoordinator.ExpectedState quarantineState = snapshot.state(quarantineKey)
                .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
            if (!(quarantineState instanceof AssetTransactionCoordinator.Missing)) {
                throw new IOException("Legacy Core recovery quarantine target already exists");
            }
            long tombstoneRevision = Math.addExact(source.revision(), 1L);
            CoreGraphStorageBoundary.CoreGraphTombstone tombstone = new CoreGraphStorageBoundary.CoreGraphTombstone(
                resource, tombstoneRevision, recoveryMutationId, source.payloadHash());
            byte[] tombstoneBytes = coreGraphStorage.encodeTombstone(tombstone);
            AssetTransactionCoordinator.AssetKey tombstoneKey = tombstoneKey(resource.resourceType().value(), resource.id());
            AssetTransactionCoordinator.ExpectedState tombstoneState = snapshot.state(tombstoneKey)
                .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
            if (!(tombstoneState instanceof AssetTransactionCoordinator.Missing)) {
                throw new IOException("Legacy Core recovery tombstone target already exists");
            }
            AssetTransactionCoordinator.AssetKey reportKey = legacyCoreRecoveryReportKey(recoveryMutationId);
            Path reportPath = legacyCoreRecoveryReportPath(recoveryMutationId);
            if (!(snapshot.state(reportKey).orElse(AssetTransactionCoordinator.Missing.INSTANCE)
                instanceof AssetTransactionCoordinator.Missing)) {
                throw new IOException("Legacy Core recovery report already exists");
            }
            byte[] reportBytes = gson.toJson(legacyCoreRecoveryReport(resource, source, recoveryMutationId))
                .getBytes(StandardCharsets.UTF_8);
            String metadataJson = coreMetadataWithoutResource(projectMetadata, resource.resourceType().value(), resource.id());
            List<AssetTransactionCoordinator.ProjectDelta> projectDeltas = projectDeltas(snapshot, metadataJson);
            if (projectDeltas.isEmpty()) {
                throw new IOException("Legacy Core recovery project metadata entry is unavailable");
            }
            List<AssetTransactionCoordinator.AssetDelta> deltas = new ArrayList<>(
                AssetTransactionCoordinator.Reclassification.of(sourceKey, sourcePath, sourceState, quarantineKey,
                    quarantinePath, quarantineState, sourceBytes).assets());
            deltas.add(AssetTransactionCoordinator.AssetDelta.write(tombstoneKey,
                graphTombstoneFile(resource.resourceType().value(), resource.id()), tombstoneState, tombstoneBytes));
            deltas.add(AssetTransactionCoordinator.AssetDelta.write(reportKey, reportPath,
                AssetTransactionCoordinator.Missing.INSTANCE, reportBytes));
            ResourceIdentity current = projectMetadataIdentity(snapshot, projectMetadataResourceId());
            long revision = Math.addExact(current == null ? 0L : current.revision(), 1L);
            String payloadHash = canonicalProjectMetadataPayloadHash(projectMetadataAfter(snapshot, projectDeltas));
            String lineage = projectMetadataLineage(projectMetadataResourceId(), revision, recoveryMutationId,
                payloadHash, false);
            AssetTransactionCoordinator.AssetKey lineageKey = projectMetadataLineageKey();
            deltas.add(AssetTransactionCoordinator.AssetDelta.write(lineageKey, projectMetadataLineageFile(),
                snapshot.state(lineageKey).orElse(AssetTransactionCoordinator.Missing.INSTANCE),
                lineage.getBytes(StandardCharsets.UTF_8)));
            JsonObject scope = legacyCoreRecoveryScope(resource, source, recoveryMutationId);
            AssetTransactionCoordinator.TransactionRequest request = new AssetTransactionCoordinator.TransactionRequest(
                recoveryMutationId, snapshot.project(), deltas, projectDeltas);
            try {
                coordinator.withTransactionIntentScope(scope, () -> {
                    try {
                        return coordinator.transact(request);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
            LegacyCoreRecoveryResult recovered = legacyCoreRecoveryReplay(coordinator, resource, sourceMutationId,
                sourceRevision, sourceAssetHash, recoveryMutationId);
            if (recovered == null) {
                throw new IOException("Legacy Core recovery transaction was not durably published");
            }
            evictGraphCache(resource.id());
            return recovered;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to recover legacy Core graph " + resource.resourceType().value()
                + ':' + resource.id(), exception);
        }
    }

    public synchronized FlowGraph getCommandGraph(String id) {
        if (serverId == null) {
            return getGraph("command", id);
        }
        Optional<CoreGraphStorageBoundary.Decoded> core;
        try {
            core = getCoreGraph("command", id);
        } catch (RuntimeException exception) {
            Log.warn("Failed to load authoritative Core command graph " + id + ": " + exception.getMessage());
            return null;
        }
        if (core.isPresent()) {
            CoreGraphStorageBoundary.Decoded decoded = core.orElseThrow();
            GraphDocument document = decoded.graphDocument();
            if (document == null) {
                throw new IllegalStateException("Core command payload is not a graph document: " + id);
            }
            return TypedCommandGraphAdapter.materialize(document,
                decoded.envelope().assetActivationState() == ResourceActivationState.ACTIVE,
                decoded.envelope().assetMutationId());
        }
        return null;
    }

    public CoreGraphStorageBoundary.Decoded saveCoreGraph(GraphDocument graph, ResourceActivationState activationState,
                                                          UUID mutationId, long expectedRevision) {
        return saveCoreGraph(graph, activationState, mutationId, expectedRevision, null);
    }

    public CoreGraphStorageBoundary.Decoded saveCoreGraph(GraphDocument graph, ResourceActivationState activationState,
                                                          UUID mutationId, long expectedRevision, RuntimeObservation validation) {
        Objects.requireNonNull(graph, "Core graph is required");
        return saveCoreGraphDocument(graph.resource().resourceType().value(), graph.resource().id(), graph, null,
            activationState, mutationId, expectedRevision, null, false, validation).primary();
    }

    public CoreGraphStorageBoundary.Decoded saveCoreGraph(FunctionSourceDocument source,
                                                           ResourceActivationState activationState,
                                                           UUID mutationId, long expectedRevision) {
        return saveCoreGraph(source, activationState, mutationId, expectedRevision, null);
    }

    public CoreGraphStorageBoundary.Decoded saveCoreGraph(FunctionSourceDocument source,
                                                           ResourceActivationState activationState,
                                                           UUID mutationId, long expectedRevision, RuntimeObservation validation) {
        Objects.requireNonNull(source, "Core function source is required");
        ServerResourceLocator resource = source.graph().resource();
        return saveCoreGraphDocument(resource.resourceType().value(), resource.id(), null, source,
            activationState, mutationId, expectedRevision, null, false, validation).primary();
    }

    public Optional<CoreCatalogRebindResult> rebindCoreCatalog(ServerResourceLocator resource,
                                                               CoreCatalogRebindSource source,
                                                               CoreGraphStorageBoundary.Decoded candidate,
                                                               UUID mutationId) {
        return rebindCoreCatalog(resource, source, candidate, mutationId, null);
    }

    public Optional<CoreCatalogRebindResult> rebindCoreCatalog(ServerResourceLocator resource,
                                                               CoreCatalogRebindSource source,
                                                               CoreGraphStorageBoundary.Decoded candidate,
                                                               UUID mutationId, RuntimeObservation validation) {
        Objects.requireNonNull(resource, "Core catalog rebind resource is required");
        Objects.requireNonNull(source, "Core catalog rebind source is required");
        Objects.requireNonNull(candidate, "Core catalog rebind candidate is required");
        Objects.requireNonNull(mutationId, "Core catalog rebind mutation ID is required");
        CoreGraphStorageBoundary.Decoded current = getCoreGraph(resource.resourceType().value(), resource.id())
            .orElse(null);
        if (current == null || !Arrays.equals(coreGraphStorage.encode(current),
            coreGraphStorage.encode(source.decoded()))) {
            return Optional.empty();
        }
        if (candidate.envelope().assetRevision() != Math.addExact(current.envelope().assetRevision(), 1L)
            || !mutationId.toString().equals(candidate.envelope().assetMutationId())
            || candidate.envelope().assetActivationState() != current.envelope().assetActivationState()) {
            throw new IllegalArgumentException("Core catalog rebind candidate metadata is invalid");
        }
        Object payload = candidate.payload();
        JsonObject scope = new JsonObject();
        scope.addProperty("format", "core-catalog-binding-rebind-v1");
        scope.addProperty("resource", resource.canonicalText());
        scope.addProperty("sourceRevision", current.envelope().assetRevision());
        scope.addProperty("sourceMutationId", current.envelope().assetMutationId());
        scope.addProperty("sourceAssetHash", current.envelope().assetHash().canonicalText());
        scope.addProperty("resultMutationId", mutationId.toString());
        CoreSaveResult result = requireAssetTransactions().withTransactionIntentScope(scope,
            () -> payload instanceof GraphDocument graph
                ? saveCoreGraphDocument(resource.resourceType().value(), resource.id(), graph, null,
                    current.envelope().assetActivationState(), mutationId, current.envelope().assetRevision(), null, false, validation)
                : saveCoreGraphDocument(resource.resourceType().value(), resource.id(), null,
                    (FunctionSourceDocument) payload, current.envelope().assetActivationState(), mutationId,
                    current.envelope().assetRevision(), null, false, validation));
        return Optional.of(new CoreCatalogRebindResult(result.primary(), result.replayed()));
    }

    public CoreAggregateCreate createCoreGraph(GraphDocument graph, ResourceActivationState activationState,
                                               UUID mutationId, long expectedRevision,
                                               ResourcePresentationIntent presentation) {
        return createCoreGraph(graph, activationState, mutationId, expectedRevision, presentation, null);
    }

    public CoreAggregateCreate createCoreGraph(GraphDocument graph, ResourceActivationState activationState,
                                               UUID mutationId, long expectedRevision,
                                               ResourcePresentationIntent presentation, RuntimeObservation validation) {
        Objects.requireNonNull(graph, "Core graph is required");
        CoreSaveResult result = saveCoreGraphDocument(graph.resource().resourceType().value(), graph.resource().id(),
            graph, null, activationState, mutationId, expectedRevision, presentation, true, validation);
        return new CoreAggregateCreate(result.primary(), result.projectMetadataIdentity(),
            result.canonicalProjectMetadataJson(), result.replayed());
    }

    public CoreAggregateCreate createCoreGraph(FunctionSourceDocument source,
                                               ResourceActivationState activationState, UUID mutationId,
                                               long expectedRevision, ResourcePresentationIntent presentation) {
        return createCoreGraph(source, activationState, mutationId, expectedRevision, presentation, null);
    }

    public CoreAggregateCreate createCoreGraph(FunctionSourceDocument source,
                                               ResourceActivationState activationState, UUID mutationId,
                                               long expectedRevision, ResourcePresentationIntent presentation,
                                               RuntimeObservation validation) {
        Objects.requireNonNull(source, "Core function source is required");
        ServerResourceLocator resource = source.graph().resource();
        CoreSaveResult result = saveCoreGraphDocument(resource.resourceType().value(), resource.id(), null, source,
            activationState, mutationId, expectedRevision, presentation, true, validation);
        return new CoreAggregateCreate(result.primary(), result.projectMetadataIdentity(),
            result.canonicalProjectMetadataJson(), result.replayed());
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone deleteCoreGraph(String type, String id,
                                                                         UUID mutationId, long expectedRevision) {
        if (serverId == null) {
            throw new IllegalStateException("Core graph storage requires an authoritative server ID");
        }
        if (!GRAPH_TYPES.contains(type)) {
            throw new IllegalArgumentException("Core graph type must be flow, function, or command");
        }
        String safeId = safeId(id, "delete Core graph");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid Core graph ID");
        }
        return deleteCoreGraph(coreResource(type, safeId), mutationId, expectedRevision);
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone deleteCoreGraph(String type, String id,
                                                                         long expectedRevision, UUID mutationId) {
        return deleteCoreGraph(type, id, mutationId, expectedRevision);
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone deleteCoreGraph(String type, String id,
                                                                         String mutationId, long expectedRevision) {
        return deleteCoreGraph(type, id, parseMutationId(mutationId), expectedRevision);
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone deleteCoreGraph(ServerResourceLocator resource,
                                                                         UUID mutationId, long expectedRevision) {
        Objects.requireNonNull(resource, "Core graph resource is required");
        if (serverId == null || !serverId.equals(resource.serverId())) {
            throw new IllegalArgumentException("Core graph resource server does not match storage identity");
        }
        if (!OwnerId.of("restudio.resync").equals(resource.owner())
            || !GRAPH_TYPES.contains(resource.resourceType().value())) {
            throw new IllegalArgumentException("Core graph resource locator is not authoritative");
        }
        String safeId = safeId(resource.id(), "delete Core graph");
        if (safeId == null || !safeId.equals(resource.id())) {
            throw new IllegalArgumentException("Invalid Core graph resource ID");
        }
        return deleteCoreGraphDocument(resource, mutationId, expectedRevision);
    }

    private CoreGraphStorageBoundary.CoreGraphTombstone deleteCoreGraphDocument(
        ServerResourceLocator resource, UUID mutationId, long expectedRevision) {
        Objects.requireNonNull(mutationId, "Core graph mutation ID is required");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Expected revision cannot be negative");
        }
        String type = resource.resourceType().value();
        String safeId = resource.id();
        CoreGraphStorageBoundary.CoreGraphTombstone committed;
        synchronized (this) {
            try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
                CoreProjectMetadata projectMetadata = readCoreProjectMetadata();
                CoreStoredState current = readCoreStoredState(type, safeId, resource, projectMetadata.snapshot());
                AssetTransactionCoordinator coordinator = requireAssetTransactions();
                AssetTransactionCoordinator.Snapshot coordinatorSnapshot = requireCoordinatedCoreState(
                    coordinator, type, safeId, current);
                if (current.kind() == CoreStateKind.LEGACY) {
                    throw new IllegalStateException("Legacy graph payload cannot be deleted through the Core authority: "
                        + type + ':' + safeId);
                }
                if (current.kind() == CoreStateKind.ABSENT) {
                    throw new IllegalStateException("Core graph does not exist: " + type + ':' + safeId);
                }
                String mutationValue = mutationId.toString();
                if (current.kind() == CoreStateKind.TOMBSTONED) {
                    CoreGraphStorageBoundary.CoreGraphTombstone existing = current.tombstone();
                    if (mutationId.equals(existing.mutationId())) {
                        if (expectedRevision != existing.revision() - 1L) {
                            throw new IllegalStateException("Mutation ID was already committed with a different expected revision: "
                                + mutationId);
                        }
                        return existing;
                    }
                    if (expectedRevision != existing.revision()) {
                        throw new ResourceRevisionConflictException(safeId, expectedRevision, existing.revision());
                    }
                    throw new IllegalStateException("Core graph is already tombstoned with a different mutation ID: "
                        + type + ':' + safeId);
                }
                CoreGraphStorageBoundary.Decoded live = current.decoded();
                long currentRevision = live.envelope().assetRevision();
                if (mutationValue.equals(live.envelope().assetMutationId())) {
                    throw new IllegalStateException("Mutation ID was already committed for Core graph save: " + mutationId);
                }
                if (expectedRevision != currentRevision) {
                    throw new ResourceRevisionConflictException(safeId, expectedRevision, currentRevision);
                }
                long revision = Math.addExact(currentRevision, 1L);
                CoreGraphStorageBoundary.CoreGraphTombstone tombstone = new CoreGraphStorageBoundary.CoreGraphTombstone(
                    resource, revision, mutationId, corePayloadChecksum(live));
                byte[] tombstoneBytes = coreGraphStorage.encodeTombstone(tombstone);
                String metadataJson = coreMetadataWithoutResource(projectMetadata, type, safeId);
                if (current.liveFiles().size() != 1) {
                    throw new IOException("Core graph deletion requires exactly one canonical live asset: " + type + ':' + safeId);
                }
                AssetTransactionCoordinator.TransactionResult transaction = transactAssets(coordinator, coordinatorSnapshot,
                    mutationId, List.of(
                    AssetMutation.delete(assetKey(type, safeId), current.liveFiles().getFirst()),
                    AssetMutation.write(tombstoneKey(type, safeId), graphTombstoneFile(type, safeId), tombstoneBytes)), metadataJson);
                AssetTransactionCoordinator.ExpectedState deleted = transaction.states().get(assetKey(type, safeId));
                if (!(deleted instanceof AssetTransactionCoordinator.Deleted) || deleted.revision() != revision) {
                    throw new IOException("Core graph delete revision diverged from the shared asset coordinator");
                }
                committed = verifyCoreGraphDeletion(type, safeId, resource,
                    tombstone, tombstoneBytes, current.liveFiles(), metadataJson);
                evictGraphCache(safeId);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to delete Core graph " + type + ':' + safeId, exception);
            }
        }
        notifyGraphChanged(type, safeId, typedCommandRefreshProof(resource, committed.revision(), committed.mutationId(), true));
        return committed;
    }

    public void publishCoreGraph(String id) {
        String safeId = safeId(id, "publish Core graph");
        if (safeId == null) {
            throw new IllegalArgumentException("Core graph ID is invalid");
        }
        publishCoreGraph(resolveStoredGraphType(safeId), safeId);
    }

    public void publishCoreGraph(String type, String id) {
        String safeId = safeId(id, "publish Core graph");
        if (safeId == null || type == null || !GRAPH_TYPES.contains(type)) {
            throw new IllegalArgumentException("Core graph identity is invalid");
        }
        long started = TemporaryLifecycleDiagnostics.start();
        RuntimeException publicationFailure = null;
        if (graphChangeListener != null) {
            try {
                graphChangeListener.accept(new GraphChange(type, safeId));
            } catch (Throwable failure) {
                Log.error("Flow graph post-commit listener failed for " + safeId, failure);
                publicationFailure = new IllegalStateException("Flow graph publication failed", failure);
            }
        }
        if (typedCommandGraphChangeListener != null) {
            try {
                typedCommandGraphChangeListener.accept(safeId);
            } catch (Throwable failure) {
                Log.error("Typed command graph post-commit listener failed for " + safeId, failure);
                IllegalStateException typedFailure = new IllegalStateException(
                    "Typed command graph publication failed", failure);
                if (publicationFailure == null) {
                    publicationFailure = typedFailure;
                } else {
                    publicationFailure.addSuppressed(typedFailure);
                }
            }
        }
        if (publicationFailure != null) {
            TemporaryLifecycleDiagnostics.event("core_storage_notification", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                    "core-graph:" + safeId, null, null, null, null, null, null), "outcome", "failed",
                    "transitionPublished", false, "notificationChannel", "core_graph"));
            throw publicationFailure;
        }
        TemporaryLifecycleDiagnostics.event("core_storage_notification", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                "core-graph:" + safeId, null, null, null, null, null, null), "outcome", "published",
                "transitionPublished", true, "notificationChannel", "core_graph"));
    }

    private CoreGraphStorageBoundary.CoreGraphTombstone verifyCoreGraphDeletion(
        String type, String id, ServerResourceLocator resource, CoreGraphStorageBoundary.CoreGraphTombstone expected,
        byte[] expectedBytes, List<Path> deletedFiles, String metadataJson) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path tombstoneFile = graphTombstoneFile(type, id);
        requireCoreStoragePath(root, tombstoneFile);
        if (!Files.isRegularFile(tombstoneFile, LinkOption.NOFOLLOW_LINKS)
            || !Arrays.equals(expectedBytes, readCoreAssetBytes(tombstoneFile))) {
            throw new IOException("Core graph deletion was acknowledged without publishing its tombstone: " + type + ':' + id);
        }
        CoreGraphStorageBoundary.CoreGraphTombstone actual;
        try {
            actual = coreGraphStorage.decodeTombstone(readCoreAssetBytes(tombstoneFile), resource);
        } catch (RuntimeException exception) {
            throw new IOException("Core graph deletion published an invalid tombstone: " + type + ':' + id, exception);
        }
        if (!expected.resource().equals(actual.resource()) || expected.revision() != actual.revision()
            || !expected.mutationId().equals(actual.mutationId())
            || !expected.priorPayloadHash().equals(actual.priorPayloadHash()) || !actual.deleted()) {
            throw new IOException("Core graph deletion published a different tombstone: " + type + ':' + id);
        }
        for (Path deletedFile : deletedFiles) {
            requireCoreStoragePath(root, deletedFile);
            if (Files.exists(deletedFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Core graph deletion left a live asset: " + deletedFile);
            }
        }
        verifyCoreProjectMetadata(root.resolve("project.json"), metadataJson);
        return actual;
    }

    private CoreSaveResult saveCoreGraphDocument(String type, String id, GraphDocument graph,
                                                 FunctionSourceDocument source,
                                                 ResourceActivationState activationState, UUID mutationId,
                                                 long expectedRevision, ResourcePresentationIntent presentation,
                                                 boolean createOnly) {
        return saveCoreGraphDocument(type, id, graph, source, activationState, mutationId, expectedRevision,
            presentation, createOnly, null);
    }

    private CoreSaveResult saveCoreGraphDocument(String type, String id, GraphDocument graph,
                                                 FunctionSourceDocument source,
                                                 ResourceActivationState activationState, UUID mutationId,
                                                 long expectedRevision, ResourcePresentationIntent presentation,
                                                 boolean createOnly, RuntimeObservation validation) {
        if (serverId == null) {
            throw new IllegalStateException("Core graph storage requires an authoritative server ID");
        }
        if (!GRAPH_TYPES.contains(type)) {
            throw new IllegalArgumentException("Core graph type must be flow, function, or command");
        }
        if ((graph == null) == (source == null)) {
            throw new IllegalArgumentException("Exactly one Core graph payload is required");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Expected revision cannot be negative");
        }
        if (createOnly) {
            Objects.requireNonNull(presentation, "presentation");
        }
        Objects.requireNonNull(activationState, "Core graph activation state is required");
        Objects.requireNonNull(mutationId, "Core graph mutation ID is required");
        String safeId = safeId(id, "save Core graph");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid Core graph ID");
        }
        ServerResourceLocator resource = new ServerResourceLocator(serverId,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), safeId);
        ServerResourceLocator payloadResource = graph != null ? graph.resource() : source.graph().resource();
        if (!resource.equals(payloadResource)) {
            throw new IllegalArgumentException("Core graph resource identity does not match storage identity");
        }
        CoreSaveResult committed;
        synchronized (this) {
            try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
                CoreProjectMetadata projectMetadata = readCoreProjectMetadata();
                CoreStoredState current = readCoreStoredState(type, safeId, resource, projectMetadata.snapshot());
                AssetTransactionCoordinator coordinator = requireAssetTransactions();
                AssetTransactionCoordinator.Snapshot coordinatorSnapshot = requireCoordinatedCoreState(
                    coordinator, type, safeId, current);
                if (current.kind() == CoreStateKind.LEGACY) {
                    throw new IllegalStateException("Legacy graph payload cannot be replaced by a Core save: "
                        + type + ':' + safeId);
                }
                long currentRevision = current.revision();
                if (current.kind() == CoreStateKind.LIVE && mutationId.toString().equals(current.mutationId())) {
                    CoreGraphStorageBoundary.Decoded decoded = current.decoded();
                    if (expectedRevision != currentRevision - 1L) {
                        throw new IllegalStateException("Mutation ID was already committed with a different expected revision: " + mutationId);
                    }
                    byte[] requested = encodeCoreGraph(graph, source,
                        new CoreGraphStorageBoundary.AssetMetadata(type, currentRevision, mutationId, activationState), resource);
                    CoreGraphStorageBoundary.Decoded requestedDecoded = coreGraphStorage.decode(requested, resource);
                    if (!sameCoreReplayState(decoded, requestedDecoded)) {
                        throw new IllegalStateException("Mutation ID was already committed with a different Core graph payload: " + mutationId);
                    }
                    String replayMetadataJson = canonicalProjectMetadataJson(coordinatorSnapshot.metadata().serializedJson());
                    if (createOnly) {
                        requirePresentation(projectMetadata.snapshot(), type, safeId, presentation);
                        ResourceIdentity replayIdentity = projectMetadataIdentity(coordinatorSnapshot,
                            projectMetadataResourceId());
                        if (replayIdentity == null || !mutationId.toString().equals(replayIdentity.mutationId())) {
                            throw new IllegalStateException("Aggregate Core create replay has no exact project metadata participant");
                        }
                        return new CoreSaveResult(decoded, replayIdentity, replayMetadataJson, true);
                    }
                    return new CoreSaveResult(decoded, null, replayMetadataJson, true);
                }
                if (validation != null) {
                    requireValidationCurrent(validation);
                }
                if (createOnly && current.kind() != CoreStateKind.ABSENT && current.kind() != CoreStateKind.TOMBSTONED) {
                    throw new IllegalStateException("Aggregate Core create target already has durable lineage: "
                        + type + ':' + safeId);
                }
                if (expectedRevision != currentRevision) {
                    throw new ResourceRevisionConflictException(safeId, expectedRevision, currentRevision);
                }
                validateCoreCommandCandidate(type, graph, source, activationState, mutationId, safeId);
                long revision = Math.addExact(currentRevision, 1L);
                CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(type, revision, mutationId, activationState);
                byte[] encoded = encodeCoreGraph(graph, source, metadata, resource);
                Path file = createOnly ? presentationAssetPath(safeId, presentation.path())
                    : current.kind() == CoreStateKind.TOMBSTONED
                    ? coordinatorSnapshot.path(assetKey(type, safeId))
                        .orElseThrow(() -> new IOException("Core graph tombstone path is missing from coordinator state"))
                    : coreGraphWriteFile(type, safeId, projectMetadata.snapshot());
                String metadataJson = createOnly
                    ? metadataWithPresentation(projectMetadata, type, safeId, presentation)
                    : coreMetadataWithResourcePath(projectMetadata, type, safeId,
                        assetFolderPath(assetsDir.toPath(), file), false);
                Path tombstoneFile = graphTombstoneFile(type, safeId);
                List<AssetMutation> mutations = new ArrayList<>();
                mutations.add(AssetMutation.write(assetKey(type, safeId), file, encoded));
                boolean physicalTombstone = coordinatorSnapshot.state(tombstoneKey(type, safeId))
                    .filter(AssetTransactionCoordinator.Live.class::isInstance).isPresent();
                if (current.kind() == CoreStateKind.TOMBSTONED && physicalTombstone) {
                    mutations.add(AssetMutation.delete(tombstoneKey(type, safeId), tombstoneFile));
                } else if (physicalTombstone) {
                    throw new IOException("Live Core graph has a physical tombstone: " + type + ':' + safeId);
                }
                String aggregateMetadataJson = createOnly
                    ? projectMetadataAfter(coordinatorSnapshot, projectDeltas(coordinatorSnapshot, metadataJson))
                    : null;
                ResourceIdentity previousMetadata = createOnly
                    ? projectMetadataIdentity(coordinatorSnapshot, projectMetadataResourceId()) : null;
                AssetTransactionCoordinator.TransactionResult transaction = transactAssets(coordinator, coordinatorSnapshot,
                    mutationId, mutations, metadataJson, validation == null ? null : validation.committedSequence());
                AssetTransactionCoordinator.ExpectedState saved = transaction.states().get(assetKey(type, safeId));
                if (!(saved instanceof AssetTransactionCoordinator.Live) || saved.revision() != revision) {
                    throw new IOException("Core graph save revision diverged from the shared asset coordinator");
                }
                verifyCoreGraphSave(file, encoded, tombstoneFile, metadataJson);
                evictGraphCache(safeId);
                CoreGraphStorageBoundary.Decoded primary = coreGraphStorage.decode(encoded, resource);
                ResourceIdentity metadataIdentity = null;
                if (createOnly) {
                    long metadataRevision = Math.addExact(
                        previousMetadata == null ? 0L : previousMetadata.revision(), 1L);
                    String metadataHash = canonicalProjectMetadataPayloadHash(aggregateMetadataJson);
                    long expectedProjectRevision = Math.addExact(coordinatorSnapshot.project().revision(), 1L);
                    if (!mutationId.equals(transaction.mutationId())
                        || transaction.project().revision() != expectedProjectRevision
                        || !transaction.project().hash().equals(AssetProjectMetadata.parse(aggregateMetadataJson).hash())) {
                        throw new IOException("Aggregate Core create project metadata result diverged from its transaction");
                    }
                    AssetTransactionCoordinator.Snapshot committedSnapshot = coordinator.read(Function.identity());
                    ResourceIdentity committedMetadata = projectMetadataIdentity(committedSnapshot,
                        projectMetadataResourceId());
                    if (!transaction.project().equals(committedSnapshot.project()) || committedMetadata == null
                        || committedMetadata.deleted() || committedMetadata.revision() != metadataRevision
                        || !mutationId.toString().equals(committedMetadata.mutationId())
                        || !metadataHash.equals(committedMetadata.payloadHash())) {
                        throw new IOException("Aggregate Core create metadata lineage differs from its transaction");
                    }
                    metadataIdentity = committedMetadata;
                }
                committed = new CoreSaveResult(primary, metadataIdentity,
                    createOnly ? aggregateMetadataJson : metadataJson, transaction.replay());
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to save Core graph " + type + ':' + safeId, exception);
            }
        }
        if (!createOnly) {
            notifyGraphChanged(type, safeId, typedCommandRefreshProof(resource,
                committed.primary().envelope().assetRevision(), mutationId, false));
        }
        return committed;
    }

    private byte[] encodeCoreGraph(GraphDocument graph, FunctionSourceDocument source,
                                   CoreGraphStorageBoundary.AssetMetadata metadata,
                                   ServerResourceLocator resource) {
        return graph != null ? coreGraphStorage.encode(graph, metadata, resource) : coreGraphStorage.encode(source, metadata, resource);
    }

    private void validateCoreCommandCandidate(String type, GraphDocument graph, FunctionSourceDocument source,
                                              ResourceActivationState activationState, UUID mutationId, String id) {
        if (!"command".equals(type)) {
            return;
        }
        GraphDocument document = graph != null ? graph : source.graph();
        boolean active = activationState == ResourceActivationState.ACTIVE;
        FlowGraph candidate = TypedCommandGraphAdapter.materialize(document, source, active, mutationId.toString());
        TypedCommandGraphAdapter.read(candidate);
        if (active) {
            ensureCommandLabelAvailable(candidate, id);
        }
    }

    private ContentHash corePayloadChecksum(CoreGraphStorageBoundary.Decoded decoded) {
        return decoded.graphDocument() != null
            ? decoded.graphDocument().checksum()
            : decoded.functionSourceDocument().checksum();
    }

    private boolean sameCoreReplayState(CoreGraphStorageBoundary.Decoded current,
                                        CoreGraphStorageBoundary.Decoded requested) {
        return current.envelope().equals(requested.envelope())
            && corePayloadChecksum(current).equals(corePayloadChecksum(requested));
    }

    private Path coreGraphWriteFile(String type, String id, ProjectMetadataSnapshot metadata) throws IOException {
        Path existing = findExactAssetResourceFile(type, id);
        Path file = existing;
        if (file == null) {
            ResourceEntrySnapshot resource = metadata != null ? metadata.findExactResource(type, id) : null;
            file = resource != null
                ? assetResourceFile(resource)
                : safeAssetFolder(defaultFolderForType(type)).resolve(AssetFileFormat.idOnlyFileName(id));
        }
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Core graph path escapes the asset root");
        }
        MigrationPaths.requireNoSymlinkTraversal(root, normalized);
        return collisionSafeWriteFile(type, id, normalized);
    }

    private static boolean containsCorePayloadKind(byte[] bytes) {
        byte[] marker = ('"' + CoreGraphStorageBoundary.CORE_PAYLOAD_KIND + '"').getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > 33_554_432) {
            throw new IllegalArgumentException("Core graph asset exceeds the maximum supported size");
        }
        boolean markerFound = false;
        outer:
        for (int index = 0; index <= bytes.length - marker.length; index++) {
            for (int offset = 0; offset < marker.length; offset++) {
                if (bytes[index + offset] != marker[offset]) {
                    continue outer;
                }
            }
            markerFound = true;
            break;
        }
        if (!markerFound) {
            return false;
        }
        JsonValue parsed = CanonicalCodec.decodePermissive(bytes);
        return parsed instanceof JsonValue.JsonObject object && object.contains(CoreGraphStorageBoundary.CORE_PAYLOAD_KIND);
    }

    private CoreGraphStorageBoundary.Decoded decodeCoreGraphAsset(Path file, String type, String id) throws IOException {
        byte[] bytes = readCoreAssetBytes(file);
        if (!containsCorePayloadKind(bytes)) {
            return null;
        }
        ServerResourceLocator expected = serverId == null ? null : coreResource(type, id);
        return expected == null ? coreGraphStorage.decode(bytes) : coreGraphStorage.decode(bytes, expected);
    }

    private boolean verifyGraphAsset(Path file, String type, String id) throws IOException {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.size(file) > 33_554_432L) {
            return AssetFileFormat.verify(file);
        }
        try {
            CoreGraphStorageBoundary.Decoded decoded = decodeCoreGraphAsset(file, type, id);
            return decoded != null || AssetFileFormat.verify(file);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private FlowGraph materializeCoreGraph(CoreGraphStorageBoundary.Decoded decoded, String type) {
        if (decoded == null) {
            return null;
        }
        GraphDocument document = decoded.graphDocument();
        if (document == null && decoded.functionSourceDocument() != null) {
            document = decoded.functionSourceDocument().graph();
        }
        if (document == null) {
            return null;
        }
        FlowGraph graph = TypedCommandGraphAdapter.materialize(document, decoded.functionSourceDocument(),
            decoded.envelope().assetActivationState() == ResourceActivationState.ACTIVE,
            decoded.envelope().assetMutationId());
        graph.setFunction("function".equals(type));
        graph.setResourceHash(decoded.envelope().assetHash().canonicalText());
        return graph;
    }

    private CoreStoredState readCoreStoredState(String type, String id, ServerResourceLocator resource,
                                                ProjectMetadataSnapshot metadata) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path tombstoneFile = graphTombstoneFile(type, id);
        requireCoreStoragePath(root, tombstoneFile);
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = null;
        if (Files.exists(tombstoneFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(tombstoneFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Core graph tombstone is not a regular file: " + type + ':' + id);
            }
            byte[] tombstoneBytes = readCoreAssetBytes(tombstoneFile);
            try {
                tombstone = coreGraphStorage.decodeTombstone(tombstoneBytes, resource);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Invalid Core graph tombstone: " + type + ':' + id, exception);
            }
        }

        List<Path> candidates = coreGraphCandidateFiles(type, id, metadata);
        List<Path> coreFiles = new ArrayList<>();
        List<Path> legacyFiles = new ArrayList<>();
        CoreGraphStorageBoundary.Decoded decoded = null;
        Path decodedFile = null;
        for (Path candidate : candidates) {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            requireCoreStoragePath(root, candidate);
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Core graph asset is not a regular file: " + type + ':' + id);
            }
            byte[] bytes = readCoreAssetBytes(candidate);
            if (!containsCorePayloadKind(bytes)) {
                legacyFiles.add(candidate);
                continue;
            }
            CoreGraphStorageBoundary.Decoded candidateDecoded;
            try {
                candidateDecoded = coreGraphStorage.decode(bytes, resource);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Invalid Core graph payload: " + type + ':' + id, exception);
            }
            coreFiles.add(candidate);
            if (decoded == null) {
                decoded = candidateDecoded;
                decodedFile = candidate;
            }
        }
        Path legacyFile = requireLegacyGraphCandidatePath(id);
        if (Files.exists(legacyFile, LinkOption.NOFOLLOW_LINKS)
            && coreGraphLegacyCandidateMatchesType(legacyFile, type, id, false)) {
            if (!Files.isRegularFile(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Legacy graph payload is not a regular file: " + type + ':' + id);
            }
            legacyFiles.add(legacyFile);
        }
        if (tombstone != null) {
            if (!coreFiles.isEmpty() || !legacyFiles.isEmpty()) {
                throw new IllegalStateException("Core graph has both a tombstone and a live or legacy payload: "
                    + type + ':' + id);
            }
            return CoreStoredState.tombstoned(tombstone);
        }
        if (!coreFiles.isEmpty() && !legacyFiles.isEmpty()) {
            throw new IllegalStateException("Core graph has both Core and legacy payloads: " + type + ':' + id);
        }
        if (coreFiles.size() > 1 || legacyFiles.size() > 1) {
            throw new IllegalStateException("Core graph has duplicate live payloads: " + type + ':' + id);
        }
        if (!coreFiles.isEmpty()) {
            return CoreStoredState.live(decoded, decodedFile, coreFiles);
        }
        if (!legacyFiles.isEmpty()) {
            return CoreStoredState.legacy(legacyFiles.getFirst(), legacyFiles);
        }
        return CoreStoredState.absent();
    }

    private Path legacyGraphCandidateFile(String id) {
        return flowDir.toPath().toAbsolutePath().normalize().resolve(id + ".json");
    }

    private byte[] readCoreAssetBytes(Path file) throws IOException {
        long size = Files.size(file);
        if (size > 33_554_432L) {
            throw new IOException("Core graph asset exceeds the maximum supported size: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 33_554_432) {
            throw new IOException("Core graph asset exceeds the maximum supported size: " + file);
        }
        return bytes;
    }

    private LegacyCoreRecoverySource requireLegacyCoreRecoverySource(ServerResourceLocator resource,
                                                                      UUID sourceMutationId, long sourceRevision,
                                                                      ContentHash sourceAssetHash) throws IOException {
        if (serverId == null || !serverId.equals(resource.serverId()) || !"flow".equals(resource.resourceType().value())
            || !OwnerId.of("restudio.resync").equals(resource.owner()) || sourceRevision != 1L) {
            throw new IOException("Legacy Core recovery source identity is not eligible");
        }
        String safeId = safeId(resource.id(), "inspect legacy Core recovery");
        if (safeId == null || !safeId.equals(resource.id()) || !safeId.startsWith("codex_runtime_probe_")) {
            throw new IOException("Legacy Core recovery source ID is invalid");
        }
        CoreProjectMetadata metadata = readCoreProjectMetadata();
        CoreStoredState stored = readCoreStoredState("flow", safeId, resource, metadata.snapshot());
        if (stored.kind() != CoreStateKind.LEGACY || stored.liveFiles().size() != 1 || stored.liveFile() == null) {
            throw new IOException("Legacy Core recovery requires exactly one legacy payload");
        }
        Path file = stored.liveFile().toAbsolutePath().normalize();
        AssetTransactionCoordinator.Snapshot snapshot = requireAssetTransactions().read(Function.identity());
        AssetTransactionCoordinator.AssetKey key = assetKey("flow", safeId);
        AssetTransactionCoordinator.ExpectedState coordinated = snapshot.state(key)
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        Path coordinatedPath = snapshot.path(key).map(path -> path.toAbsolutePath().normalize()).orElse(null);
        if (!(coordinated instanceof AssetTransactionCoordinator.Live live) || live.revision() != sourceRevision
            || !sourceAssetHash.canonicalText().equals(live.hash()) || !file.equals(coordinatedPath)
            || !snapshot.mutationValue(key).filter(sourceMutationId.toString()::equals).isPresent()) {
            throw new IOException("Legacy Core recovery source diverges from coordinator authority");
        }
        byte[] bytes = readCoreAssetBytes(file);
        if (!sourceAssetHash.canonicalText().equals(StorageSafety.sha256(bytes)) || !AssetFileFormat.verify(file)
            || AssetFileFormat.readRevision(file) != sourceRevision
            || !sourceMutationId.toString().equals(AssetFileFormat.readMutationId(file))) {
            throw new IOException("Legacy Core recovery source bytes are not authoritative");
        }
        JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject() || !disposableLegacyCoreGraph(parsed.getAsJsonObject(), safeId, sourceMutationId)) {
            throw new IOException("Legacy Core recovery source contains meaningful graph state: fields="
                + (parsed.isJsonObject() ? parsed.getAsJsonObject().keySet() : Set.of()));
        }
        return new LegacyCoreRecoverySource(sourceRevision, sourceMutationId, sourceAssetHash,
            new ContentHash(AssetFileFormat.readContentHash(file)), "legacy-flow-graph-v3");
    }

    private boolean disposableLegacyCoreGraph(JsonObject value, String id, UUID mutationId) {
        Set<String> fields = Set.of("id", "enabled", "version", "nodes", "connections", "localVariables",
            "function", "functionOwner", "functionNamespace", "functionVersion", "functionDescription",
            "functionInputs", "functionOutputs", "editorPassthroughs", "resourceType", "resourceRevision",
            "resourceHash", "resourceMutationId", "assetFormatVersion", "assetRevision", "assetMutationId", "assetHash");
        Set<String> actualFields = new LinkedHashSet<>(value.keySet());
        boolean emptyContentProperties = !actualFields.remove("contentProperties")
            || jsonObjectEmpty(value, "contentProperties");
        boolean exactDisplayName = !actualFields.remove("displayName") || id.equals(jsonText(value, "displayName"));
        boolean exactFolder = !actualFields.remove("folder") || "Blueprints/Flows".equals(jsonText(value, "folder"));
        return actualFields.equals(fields) && emptyContentProperties && id.equals(jsonText(value, "id"))
            && exactDisplayName && exactFolder && "flow".equals(jsonText(value, "resourceType"))
            && mutationId.toString().equals(jsonText(value, "resourceMutationId"))
            && mutationId.toString().equals(jsonText(value, "assetMutationId"))
            && "".equals(jsonText(value, "resourceHash")) && "server".equals(jsonText(value, "functionOwner"))
            && "local".equals(jsonText(value, "functionNamespace"))
            && "".equals(jsonText(value, "functionDescription")) && jsonBoolean(value, "enabled")
            && !jsonBoolean(value, "function") && jsonLong(value, "version") == 2L
            && jsonLong(value, "functionVersion") == 1L && jsonLong(value, "resourceRevision") == 1L
            && jsonLong(value, "assetFormatVersion") == AssetFileFormat.CURRENT_FORMAT_VERSION
            && jsonLong(value, "assetRevision") == 1L && jsonObjectEmpty(value, "nodes")
            && jsonArrayEmpty(value, "connections") && jsonArrayEmpty(value, "localVariables")
            && jsonArrayEmpty(value, "functionInputs") && jsonArrayEmpty(value, "functionOutputs")
            && jsonArrayEmpty(value, "editorPassthroughs");
    }

    private LegacyCoreRecoveryResult legacyCoreRecoveryReplay(AssetTransactionCoordinator coordinator,
                                                               ServerResourceLocator resource, UUID sourceMutationId,
                                                               long sourceRevision, ContentHash sourceAssetHash,
                                                               UUID recoveryMutationId) throws IOException {
        AssetTransactionCoordinator.MutationView mutation = coordinator.mutation(recoveryMutationId).orElse(null);
        if (mutation == null) {
            return null;
        }
        JsonObject intent = mutation.intent();
        JsonObject replayScope = intent == null ? null : intent.getAsJsonObject("scope");
        LegacyCoreRecoverySource source = new LegacyCoreRecoverySource(sourceRevision, sourceMutationId,
            sourceAssetHash, legacyCorePayloadHash(coordinator, resource, sourceMutationId), "legacy-flow-graph-v3");
        if (replayScope == null || !legacyCoreRecoveryScope(resource, source, recoveryMutationId).equals(replayScope)) {
            throw new IOException("Legacy Core recovery replay scope is invalid");
        }
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.AssetKey sourceKey = assetKey(resource.resourceType().value(), resource.id());
        AssetTransactionCoordinator.AssetKey quarantineKey = legacyCoreQuarantineKey(resource, sourceMutationId);
        AssetTransactionCoordinator.AssetKey tombstoneKey = tombstoneKey(resource.resourceType().value(), resource.id());
        AssetTransactionCoordinator.AssetKey reportKey = legacyCoreRecoveryReportKey(recoveryMutationId);
        AssetTransactionCoordinator.AssetKey lineageKey = projectMetadataLineageKey();
        Set<AssetTransactionCoordinator.AssetKey> recoveryKeys = Set.of(sourceKey, quarantineKey, tombstoneKey,
            reportKey, lineageKey);
        if (!recoveryKeys.equals(mutation.result().states().keySet())) {
            throw new IOException("Legacy Core recovery replay result keys are invalid: "
                + mutation.result().states().keySet());
        }
        if (!mutation.result().project().equals(snapshot.project())) {
            throw new IOException("Legacy Core recovery replay project result is stale");
        }
        for (AssetTransactionCoordinator.AssetKey key : recoveryKeys) {
            if (!snapshot.mutationValue(key).filter(recoveryMutationId.toString()::equals).isPresent()) {
                throw new IOException("Legacy Core recovery replay key is not owned by the recovery mutation: " + key);
            }
            if (!Objects.equals(mutation.result().states().get(key), snapshot.state(key).orElse(null))) {
                throw new IOException("Legacy Core recovery replay key state is stale: " + key);
            }
        }
        AssetTransactionCoordinator.ExpectedState sourceResult = snapshot.state(sourceKey).orElse(null);
        AssetTransactionCoordinator.ExpectedState quarantineResult = snapshot.state(quarantineKey).orElse(null);
        AssetTransactionCoordinator.ExpectedState tombstoneResult = snapshot.state(tombstoneKey).orElse(null);
        AssetTransactionCoordinator.ExpectedState reportResult = snapshot.state(reportKey).orElse(null);
        Path quarantinePath = snapshot.path(quarantineKey).map(path -> path.toAbsolutePath().normalize()).orElse(null);
        Path tombstonePath = snapshot.path(tombstoneKey).map(path -> path.toAbsolutePath().normalize()).orElse(null);
        Path reportPath = snapshot.path(reportKey).map(path -> path.toAbsolutePath().normalize()).orElse(null);
        byte[] expectedReport = gson.toJson(legacyCoreRecoveryReport(resource, source, recoveryMutationId))
            .getBytes(StandardCharsets.UTF_8);
        if (!(sourceResult instanceof AssetTransactionCoordinator.Deleted deleted)
            || deleted.revision() != sourceRevision + 1L
            || !(quarantineResult instanceof AssetTransactionCoordinator.Live quarantine)
            || quarantine.revision() != sourceRevision + 1L || !quarantine.hash().equals(sourceAssetHash.canonicalText())
            || !(tombstoneResult instanceof AssetTransactionCoordinator.Live tombstoneLive)
            || !(reportResult instanceof AssetTransactionCoordinator.Live reportLive)
            || quarantinePath == null || !quarantinePath.equals(legacyCoreQuarantinePath(resource, sourceMutationId))
            || tombstonePath == null || !tombstonePath.equals(graphTombstoneFile(resource.resourceType().value(), resource.id())
            .toAbsolutePath().normalize()) || reportPath == null
            || !reportPath.equals(legacyCoreRecoveryReportPath(recoveryMutationId))
            || !Files.isRegularFile(quarantinePath, LinkOption.NOFOLLOW_LINKS)
            || !sourceAssetHash.canonicalText().equals(StorageSafety.sha256(Files.readAllBytes(quarantinePath)))
            || !Files.isRegularFile(tombstonePath, LinkOption.NOFOLLOW_LINKS)
            || !tombstoneLive.hash().equals(StorageSafety.sha256(Files.readAllBytes(tombstonePath)))
            || !Files.isRegularFile(reportPath, LinkOption.NOFOLLOW_LINKS)
            || !reportLive.hash().equals(StorageSafety.sha256(Files.readAllBytes(reportPath)))
            || !Arrays.equals(expectedReport, Files.readAllBytes(reportPath))) {
            throw new IOException("Legacy Core recovery replay evidence is incomplete: source=" + sourceResult
                + ", quarantine=" + quarantineResult + ", tombstone=" + tombstoneResult + ", report=" + reportResult
                + ", quarantinePath=" + quarantinePath + ", tombstonePath=" + tombstonePath + ", reportPath=" + reportPath);
        }
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = coreGraphStorage.decodeTombstone(
            Files.readAllBytes(tombstonePath), resource);
        if (tombstone.revision() != sourceRevision + 1L || !recoveryMutationId.equals(tombstone.mutationId())
            || !source.payloadHash().equals(tombstone.priorPayloadHash())) {
            throw new IOException("Legacy Core recovery tombstone is invalid");
        }
        ResourceIdentity metadata = projectMetadataIdentity(snapshot, projectMetadataResourceId());
        if (metadata == null || !recoveryMutationId.toString().equals(metadata.mutationId())) {
            throw new IOException("Legacy Core recovery project metadata was not advanced");
        }
        return new LegacyCoreRecoveryResult(tombstone, metadata,
            canonicalProjectMetadataJson(snapshot.metadata().serializedJson()));
    }

    private ContentHash legacyCorePayloadHash(AssetTransactionCoordinator coordinator,
                                              ServerResourceLocator resource, UUID sourceMutationId) throws IOException {
        AssetTransactionCoordinator.AssetKey key = legacyCoreQuarantineKey(resource, sourceMutationId);
        Path path = coordinator.read(snapshot -> snapshot.paths().get(key));
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !AssetFileFormat.verify(path)) {
            throw new IOException("Legacy Core recovery quarantine payload is unavailable");
        }
        return new ContentHash(AssetFileFormat.readContentHash(path));
    }

    private AssetTransactionCoordinator.AssetKey legacyCoreQuarantineKey(ServerResourceLocator resource,
                                                                          UUID sourceMutationId) {
        return new AssetTransactionCoordinator.AssetKey("quarantine.legacy_admin." + resource.resourceType().value(),
            resource.id() + '@' + sourceMutationId);
    }

    private Path legacyCoreQuarantinePath(ServerResourceLocator resource, UUID sourceMutationId) {
        return assetsDir.toPath().resolve(".quarantine").resolve("legacy-admin-recovery")
            .resolve(sourceMutationId.toString()).resolve(resource.resourceType().value())
            .resolve(AssetFileFormat.idOnlyFileName(resource.id())).toAbsolutePath().normalize();
    }

    private AssetTransactionCoordinator.AssetKey legacyCoreRecoveryReportKey(UUID recoveryMutationId) {
        return new AssetTransactionCoordinator.AssetKey("quarantine.legacy_admin.report", recoveryMutationId.toString());
    }

    private Path legacyCoreRecoveryReportPath(UUID recoveryMutationId) {
        return assetsDir.toPath().resolve(".quarantine").resolve("legacy-admin-recovery")
            .resolve(recoveryMutationId.toString()).resolve("recovery.json").toAbsolutePath().normalize();
    }

    private JsonObject legacyCoreRecoveryScope(ServerResourceLocator resource, LegacyCoreRecoverySource source,
                                               UUID recoveryMutationId) {
        JsonObject scope = new JsonObject();
        scope.addProperty("format", "legacy-core-admin-recovery-v1");
        scope.addProperty("resource", resource.canonicalText());
        scope.addProperty("sourceRevision", source.revision());
        scope.addProperty("sourceMutationId", source.mutationId().toString());
        scope.addProperty("sourceAssetHash", source.assetHash().canonicalText());
        scope.addProperty("sourcePayloadHash", source.payloadHash().canonicalText());
        scope.addProperty("sourcePayloadKind", source.payloadKind());
        scope.addProperty("recoveryMutationId", recoveryMutationId.toString());
        return scope;
    }

    private JsonObject legacyCoreRecoveryReport(ServerResourceLocator resource, LegacyCoreRecoverySource source,
                                                UUID recoveryMutationId) {
        JsonObject report = legacyCoreRecoveryScope(resource, source, recoveryMutationId);
        report.addProperty("outcome", "quarantined");
        report.addProperty("quarantinePath", assetsDir.toPath().toAbsolutePath().normalize()
            .relativize(legacyCoreQuarantinePath(resource, source.mutationId())).toString().replace('\\', '/'));
        return report;
    }

    private CoreRevisionRepairResult coreRevisionRepairReplay(AssetTransactionCoordinator coordinator,
                                                               ServerResourceLocator resource,
                                                               CoreRevisionRepairSource source,
                                                               UUID repairMutationId, boolean replayed) throws IOException {
        AssetTransactionCoordinator.MutationView mutation = coordinator.mutation(repairMutationId).orElse(null);
        if (mutation == null) {
            return null;
        }
        JsonObject intent = mutation.intent();
        JsonObject scope = intent == null ? null : intent.getAsJsonObject("scope");
        if (scope == null || !coreRevisionRepairScope(resource, source, repairMutationId).equals(scope)) {
            throw new IOException("Core revision repair replay scope is invalid");
        }
        AssetTransactionCoordinator.AssetKey key = assetKey(resource.resourceType().value(), resource.id());
        if (!mutation.result().states().keySet().equals(Set.of(key))) {
            throw new IOException("Core revision repair replay result keys are invalid");
        }
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.ExpectedState state = snapshot.state(key).orElse(null);
        Path path = snapshot.path(key).map(value -> value.toAbsolutePath().normalize()).orElse(null);
        long repairedRevision = Math.addExact(source.revision(), 1L);
        if (!(state instanceof AssetTransactionCoordinator.Live live) || live.revision() != repairedRevision
            || !snapshot.mutationValue(key).filter(repairMutationId.toString()::equals).isPresent()
            || !Objects.equals(state, mutation.result().states().get(key))
            || !snapshot.project().equals(mutation.result().project())
            || path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Core revision repair replay evidence is incomplete");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (!live.hash().equals(StorageSafety.sha256(bytes))) {
            throw new IOException("Core revision repair replay bytes do not match coordinator state");
        }
        CoreGraphStorageBoundary.Decoded repaired = coreGraphStorage.decode(bytes, resource);
        if (repaired.envelope().assetRevision() != repairedRevision
            || !repairMutationId.toString().equals(repaired.envelope().assetMutationId())
            || repaired.envelope().assetActivationState() != source.activationState()
            || !source.payloadKind().equals(repaired.corePayloadKind())) {
            throw new IOException("Core revision repair replay payload is invalid");
        }
        CoreGraphStorageBoundary.AssetMetadata sourceMetadata = new CoreGraphStorageBoundary.AssetMetadata(
            resource.resourceType().value(), source.revision(), source.mutationId(), source.activationState());
        try {
            coreGraphStorage.verifyRevisionRepair(repaired, resource, sourceMetadata, source.assetHash());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Core revision repair replay does not preserve the source payload", exception);
        }
        return new CoreRevisionRepairResult(repaired, replayed);
    }

    private JsonObject coreRevisionRepairScope(ServerResourceLocator resource, CoreRevisionRepairSource source,
                                               UUID repairMutationId) {
        JsonObject scope = new JsonObject();
        scope.addProperty("format", "core-revision-skew-repair-v1");
        scope.addProperty("resource", resource.canonicalText());
        scope.addProperty("sourceRevision", source.revision());
        scope.addProperty("sourceMutationId", source.mutationId().toString());
        scope.addProperty("sourceAssetHash", source.assetHash().canonicalText());
        scope.addProperty("sourcePayloadHash", source.payloadHash().canonicalText());
        scope.addProperty("sourcePayloadKind", source.payloadKind());
        scope.addProperty("sourceActivationState", source.activationState().name());
        scope.addProperty("repairMutationId", repairMutationId.toString());
        return scope;
    }

    private String jsonText(JsonObject value, String name) {
        return value.has(name) && value.get(name).isJsonPrimitive() && value.get(name).getAsJsonPrimitive().isString()
            ? value.get(name).getAsString() : null;
    }

    private boolean jsonBoolean(JsonObject value, String name) {
        return value.has(name) && value.get(name).isJsonPrimitive() && value.get(name).getAsJsonPrimitive().isBoolean()
            && value.get(name).getAsBoolean();
    }

    private long jsonLong(JsonObject value, String name) {
        return value.has(name) && value.get(name).isJsonPrimitive() && value.get(name).getAsJsonPrimitive().isNumber()
            ? value.get(name).getAsLong() : Long.MIN_VALUE;
    }

    private boolean jsonObjectEmpty(JsonObject value, String name) {
        return value.has(name) && value.get(name).isJsonObject() && value.getAsJsonObject(name).isEmpty();
    }

    private boolean jsonArrayEmpty(JsonObject value, String name) {
        return value.has(name) && value.get(name).isJsonArray() && value.getAsJsonArray(name).isEmpty();
    }

    private List<Path> coreGraphCandidateFiles(String type, String id, ProjectMetadataSnapshot metadata) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Set<Path> candidates = new LinkedHashSet<>();
        AssetTransactionCoordinator.Snapshot snapshot = requireAssetTransactions().read(Function.identity());
        List<Map.Entry<AssetTransactionCoordinator.AssetKey, AssetTransactionCoordinator.ExpectedState>> coordinated =
            snapshot.states().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().canonical()))
                .toList();
        for (Map.Entry<AssetTransactionCoordinator.AssetKey, AssetTransactionCoordinator.ExpectedState> entry : coordinated) {
            AssetTransactionCoordinator.AssetKey key = entry.getKey();
            Path path = snapshot.path(key).orElse(null);
            boolean exact = type.equals(key.type()) && id.equals(key.id());
            boolean adoptedDuplicate = ("legacy-duplicate:" + type).equals(key.type())
                && coreGraphCandidateName(path, id);
            boolean coordinatedPayload = coreGraphCandidateName(path, id)
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && type.equals(AssetFileFormat.readResourceType(path));
            if (exact || adoptedDuplicate || coordinatedPayload) {
                addCoreGraphCandidate(candidates, root, path, type, id);
            }
        }
        if (metadata != null) {
            for (ResourceEntrySnapshot resource : metadata.resources()) {
                if (resource != null && type.equals(resource.type) && id.equals(resource.id)) {
                    addCoreGraphCandidate(candidates, root, assetResourceFile(resource), type, id);
                }
            }
        }
        addCoreGraphCandidate(candidates, root,
            safeAssetFolder(defaultFolderForType(type)).resolve(AssetFileFormat.idOnlyFileName(id)), type, id);
        return List.copyOf(candidates);
    }

    private void addCoreGraphCandidate(Set<Path> candidates, Path root, Path candidate, String type, String id) throws IOException {
        if (candidate == null) {
            return;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        requireCoreStoragePath(root, normalized);
        candidates.add(normalized);
        Path parent = normalized.getParent();
        if (parent != null) {
            Path legacyNamed = parent.resolve("flow__" + id + ".json").toAbsolutePath().normalize();
            requireCoreStoragePath(root, legacyNamed);
            if (Files.exists(legacyNamed, LinkOption.NOFOLLOW_LINKS)
                && coreGraphLegacyCandidateMatchesType(legacyNamed, type, id, true)) {
                candidates.add(legacyNamed);
            }
        }
    }

    private boolean coreGraphLegacyCandidateMatchesType(Path candidate, String type, String id,
                                                        boolean typedContext) throws IOException {
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        String declaredType = AssetFileFormat.readResourceType(candidate);
        if (!declaredType.isBlank()) {
            return type.equals(declaredType);
        }
        try {
            FlowGraph graph = FlowSerializer.deserialize(new String(readCoreAssetBytes(candidate), StandardCharsets.UTF_8));
            return graph != null && id.equals(graph.getId()) && type.equals(graphResourceType(graph));
        } catch (RuntimeException exception) {
            return typedContext || "flow".equals(type);
        }
    }

    private boolean coreGraphCandidateName(Path candidate, String id) {
        if (candidate == null || candidate.getFileName() == null) {
            return false;
        }
        String name = candidate.getFileName().toString();
        return AssetFileFormat.idOnlyFileName(id).equals(name) || ("flow__" + id + ".json").equals(name);
    }

    private ServerResourceLocator coreResource(String type, String id) {
        return new ServerResourceLocator(serverId,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }

    private void requireCoreStoragePath(Path root, Path path) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot) || normalized.equals(normalizedRoot)) {
            throw new IOException("Core graph path escapes the asset root: " + path);
        }
        MigrationPaths.requireNoSymlinkTraversal(normalizedRoot, normalized);
    }

    private Path requireLegacyGraphCandidatePath(String id) throws IOException {
        Path candidate = legacyGraphCandidateFile(id);
        try {
            return MigrationPaths.requirePath(candidate, "Legacy graph candidate");
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unsafe legacy graph candidate: " + candidate, exception);
        }
    }

    public synchronized FlowGraph getGraph(String type, String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "load flow");
            if (safeId == null) {
                return null;
            }
            String requestedType = type == null ? "" : type.trim();
            if (!requestedType.isBlank() && !GRAPH_TYPES.contains(requestedType)) {
                throw new IllegalArgumentException("Unknown graph resource type: " + type);
            }
            if (requestedType.isBlank()) {
                String resolvedType = resolveStoredGraphType(safeId);
                return resolvedType.isBlank() ? null : getGraph(resolvedType, safeId);
            }
            Path file = findAssetResourceFile(requestedType, safeId);
            if (file == null || !Files.exists(file)) {
                return null;
            }
            String actualType = AssetFileFormat.readResourceType(file);
            if (!requestedType.isBlank() && !requestedType.equals(actualType)) {
                return null;
            }
            String coordinatedType = actualType.isBlank() ? "flow" : actualType;
            if (serverId != null && GRAPH_TYPES.contains(coordinatedType)) {
                try {
                    CoreGraphStorageBoundary.Decoded fileCore = decodeCoreGraphAsset(file, coordinatedType, safeId);
                    if (fileCore != null) {
                        Optional<CoreGraphStorageBoundary.Decoded> core = getCoreGraph(coordinatedType, safeId);
                        if (core.isEmpty()) {
                            return null;
                        }
                        FlowGraph graph = materializeCoreGraph(core.orElseThrow(), coordinatedType);
                        if (graph == null) {
                            throw new IllegalStateException("Core graph payload is not materializable: " + safeId);
                        }
                        AssetTransactionCoordinator.Snapshot coordinatorSnapshot = requireCoordinatedLiveFile(
                            coordinatedType, safeId, file);
                        if (graphCacheIdentityMatches(graph, coordinatorSnapshot, coordinatedType, safeId, file, true)) {
                            graphCache.put(assetIndexKey(coordinatedType, safeId), graph);
                        }
                        return graph.copy();
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to load Core graph " + coordinatedType + ':' + safeId,
                        exception);
                }
            }
            AssetTransactionCoordinator.Snapshot coordinatorSnapshot;
            try {
                coordinatorSnapshot = requireCoordinatedLiveFile(coordinatedType, safeId, file);
                String cacheKey = assetIndexKey(coordinatedType, safeId);
                FlowGraph cached = graphCache.get(cacheKey);
                if (cached != null && graphCacheIdentityMatches(cached, coordinatorSnapshot, coordinatedType, safeId, file)) {
                    return cached.copy();
                }
                if (cached != null) {
                    graphCache.remove(cacheKey, cached);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Flow payload diverged from the shared asset coordinator: " + safeId, exception);
            }
            String cacheKey = assetIndexKey(actualType.isBlank() ? "flow" : actualType, safeId);

            try {
                if (!verifyGraphAsset(file, coordinatedType, safeId)) {
                    Log.warn("Flow integrity check failed: " + safeId);
                    return null;
                }
                String json = StorageSafety.readUtf8(file);
                FlowGraph graph = FlowSerializer.deserialize(json);
                applyResourceIdentity(graph, file);
                if (!graphCacheIdentityMatches(graph, coordinatorSnapshot, coordinatedType, safeId, file, true)) {
                    Log.warn("Skipped caching flow without a matching coordinator identity: " + safeId);
                    return graph.copy();
                }
                graphCache.put(cacheKey, graph);
                return graph.copy();
            } catch (IOException | RuntimeException e) {
                Log.warn("Failed to load flow: " + safeId + " - " + e.getMessage());
            }
            return null;
        }
    }

    public synchronized Path getAssetsPath() {
        return assetsDir.toPath().toAbsolutePath().normalize();
    }

    public synchronized void validateActiveCoordinator(AssetTransactionCoordinator expected) throws IOException {
        AssetTransactionCoordinator required = Objects.requireNonNull(expected, "expectedCoordinator");
        if (assetTransactions != required) {
            throw new IOException("Flow asset persistence coordinator identity does not match the shared coordinator");
        }
        try {
            requireAssetGateScope(assetsDir.toPath(), assetsGate);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Flow asset persistence gate scope does not match the active asset root", exception);
        }
        Path root = MigrationPaths.requireDirectory(assetsDir.toPath(), "Flow asset root");
        if (!root.equals(required.canonicalRoot())) {
            throw new IOException("Flow asset persistence coordinator root does not match the active asset root");
        }
    }

    public synchronized AssetIntegrityService.HealthReport getDurabilityHealth() {
        try {
            requireAssetTransactions().healthCheck();
        } catch (IOException exception) {
            throw new IllegalStateException("Flow asset coordinator health check failed", exception);
        }
        return getDurabilityHealthLocal();
    }

    public synchronized AssetIntegrityService.HealthReport getDurabilityHealthLocal() {
        return assetIntegrity.scan(0);
    }

    public synchronized void flushPersistence() throws IOException {
        Path root = getAssetsPath();
        MigrationPaths.requireDirectory(root, "Flow asset root");
        requireAssetTransactions().flush();
        StorageSafety.forceDirectory(root);
    }

    public synchronized void quiescePersistence() {
        boolean previousTransition = beginPersistenceTransition();
        try {
            requireAssetGateScope(assetsDir.toPath(), assetsGate);
            if (persistenceState == PersistenceState.QUIESCED && !assetsGate.isOpen()) {
                return;
            }
            assetsGate.quiesce();
            persistenceState = PersistenceState.QUIESCED;
            runtimeTabIdentities.clear();
        } finally {
            endPersistenceTransition(previousTransition);
        }
    }

    public synchronized void resumePersistence() throws IOException {
        boolean previousTransition = beginPersistenceTransition();
        try {
            requireAssetGateScope(assetsDir.toPath(), assetsGate);
            if (persistenceState != PersistenceState.QUIESCED) {
                if (!assetsGate.isOpen()) {
                    throw new IOException("Flow asset persistence gate is quiesced while storage is not resumable");
                }
                return;
            }
            resumePersistenceWhileQuiesced();
            requireAssetGateScope(assetsDir.toPath(), assetsGate);
            assetsGate.resume();
        } finally {
            endPersistenceTransition(previousTransition);
        }
    }

    public synchronized void resumePersistenceWhileQuiesced() throws IOException {
        resumePersistenceWhileQuiesced(false);
    }

    public synchronized void resumePersistenceAfterHealthCheck() throws IOException {
        resumePersistenceWhileQuiesced(true);
    }

    private void resumePersistenceWhileQuiesced(boolean healthVerified) throws IOException {
        boolean previousTransition = beginPersistenceTransition();
        try {
            requireAssetGateScope(assetsDir.toPath(), assetsGate);
            if (persistenceState != PersistenceState.QUIESCED) {
                if (!assetsGate.isOpen()) {
                    throw new IOException("Flow asset persistence gate is quiesced while storage is not resumable");
                }
                return;
            }
            MigrationPaths.requireDirectory(getAssetsPath(), "Flow asset root");
            if (!healthVerified) {
                healthCheckPersistence();
            }
            persistenceState = PersistenceState.OPEN;
        } finally {
            endPersistenceTransition(previousTransition);
        }
    }

    public synchronized void rebindPersistence(Path candidateAssetsRoot,
                                               AssetTransactionCoordinator candidateTransactions) throws IOException {
        boolean previousTransition = beginPersistenceTransition();
        try {
            if (persistenceState != PersistenceState.QUIESCED) {
                throw new IOException("Flow asset persistence must be quiesced before rebind");
            }
            if (assetsGate.isOpen()) {
                throw new IOException("Flow asset persistence gate must be quiesced before rebind");
            }
            Path candidate = MigrationPaths.requireDirectory(candidateAssetsRoot, "Flow asset rebind root");
            candidateTransactions = requireAssetTransactions(candidate, candidateTransactions);
            AssetIntegrityService candidateIntegrity = new AssetIntegrityService(candidate);
            candidateTransactions.healthCheck();
            AssetIntegrityService.HealthReport health = candidateIntegrity.scan(0);
            if (health.status() == AssetIntegrityService.Status.CRITICAL) {
                throw new IOException("Flow asset rebind rejected by integrity check: " + health.issues().stream()
                    .filter(issue -> issue.severity() == AssetIntegrityService.Severity.CRITICAL)
                    .map(AssetIntegrityService.Issue::code)
                    .distinct()
                    .sorted()
                    .toList());
            }
            assetsGate.rebind(candidate.getParent());
            assetsDir = candidate.toFile();
            assetTransactions = candidateTransactions;
            assetIntegrity = candidateIntegrity;
            clearCache();
        } finally {
            endPersistenceTransition(previousTransition);
        }
    }

    public Optional<RuntimeObservation> observeRuntime() {
        long generation = persistenceGeneration;
        if (persistenceTransition || persistenceState != PersistenceState.OPEN || !assetsGate.isOpen()) {
            return Optional.empty();
        }
        AssetTransactionCoordinator coordinator = assetTransactions;
        try {
            RuntimeObservation observation = new RuntimeObservation(this, generation, coordinator, coordinator.committedSequence());
            return isRuntimeObservationCurrent(observation) ? Optional.of(observation) : Optional.empty();
        } catch (IllegalStateException closed) {
            return Optional.empty();
        }
    }

    public boolean isRuntimeObservationCurrent(RuntimeObservation observation) {
        if (observation == null || observation.owner() != this || persistenceTransition
            || persistenceGeneration != observation.generation() || persistenceState != PersistenceState.OPEN
            || !assetsGate.isOpen() || assetTransactions != observation.coordinator()) {
            return false;
        }
        try {
            long sequence = observation.coordinator().committedSequence();
            return sequence == observation.committedSequence() && !persistenceTransition
                && persistenceGeneration == observation.generation() && persistenceState == PersistenceState.OPEN
                && assetsGate.isOpen() && assetTransactions == observation.coordinator();
        } catch (IllegalStateException closed) {
            return false;
        }
    }

    public record RuntimeObservation(FlowStorage owner, long generation, AssetTransactionCoordinator coordinator,
                                     long committedSequence) {
    }

    private boolean beginPersistenceTransition() {
        boolean previous = persistenceTransition;
        persistenceTransition = true;
        persistenceGeneration++;
        return previous;
    }

    private void endPersistenceTransition(boolean previous) {
        persistenceGeneration++;
        persistenceTransition = previous;
    }

    public synchronized void healthCheckPersistence() throws IOException {
        MigrationPaths.requireDirectory(getAssetsPath(), "Flow asset root");
        AssetIntegrityService.HealthReport health = getDurabilityHealth();
        requireHealthyDurability(health);
    }

    public synchronized void healthCheckPersistenceLocal() throws IOException {
        MigrationPaths.requireDirectory(getAssetsPath(), "Flow asset root");
        requireHealthyDurability(getDurabilityHealthLocal());
    }

    private void requireHealthyDurability(AssetIntegrityService.HealthReport health) throws IOException {
        if (health.status() == AssetIntegrityService.Status.CRITICAL) {
            throw new IOException("Flow asset persistence health check failed: " + health.issues().stream()
                .filter(issue -> issue.severity() == AssetIntegrityService.Severity.CRITICAL)
                .map(AssetIntegrityService.Issue::code)
                .distinct()
                .sorted()
                .toList());
        }
    }

    private synchronized AssetPersistenceGate.MutationLease requirePersistenceMutationOpen() {
        requireAssetGateScope(assetsDir.toPath(), assetsGate);
        if (persistenceState != PersistenceState.OPEN || !assetsGate.isOpen()) {
            throw new IllegalStateException("Flow asset persistence is QUIESCED; mutation rejected");
        }
        AssetPersistenceGate.MutationLease lease = assetsGate.acquire();
        if (persistenceState != PersistenceState.OPEN) {
            lease.close();
            throw new IllegalStateException("Flow asset persistence is QUIESCED; mutation rejected");
        }
        return lease;
    }

    public boolean allowsLegacyMigration() {
        return legacyRuntimeGate.allowsLegacyMigration();
    }

    public LegacyRuntimeActivationGate legacyRuntimeGate() {
        return legacyRuntimeGate;
    }

    public void recordBlockedLegacyOperation(String operation) {
        logBlockedLegacyOperation(operation);
    }

    public synchronized FlowGraph reloadGraph(String id) {
        String safeId = safeId(id, "reload flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        String type = resolveStoredGraphType(safeId);
        return type.isBlank() ? null : reloadGraph(type, safeId);
    }

    public synchronized FlowGraph reloadGraph(String type, String id) {
        String safeId = safeId(id, "reload flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        String requestedType = type == null ? "" : type.trim();
        if (!requestedType.isBlank() && !GRAPH_TYPES.contains(requestedType)) {
            throw new IllegalArgumentException("Unknown graph resource type: " + type);
        }
        if (requestedType.isBlank()) {
            String resolvedType = resolveStoredGraphType(safeId);
            return resolvedType.isBlank() ? null : reloadGraph(resolvedType, safeId);
        }
        PersistenceGeneration generation = capturePersistenceGeneration();
        for (int attempt = 0; attempt < 2; attempt++) {
            ReloadGraphState loaded;
            try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
                loaded = loadReloadGraph(requestedType, safeId);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to reload flow: " + safeId, exception);
            }
            if (loaded.graph() == null) {
                notifyGraphChanged(requestedType, safeId);
                return null;
            }
            requireValidGraph(loaded.graph());
            if (!isCurrentPersistenceGeneration(generation)) {
                throw new IllegalStateException("Flow persistence changed while validating the graph");
            }
            if (!reloadGraphStateMatchesCurrent(loaded, safeId, generation)) {
                if (attempt == 0) {
                    continue;
                }
                throw new IllegalStateException("Flow graph changed while validating the graph");
            }
            if (requestedType.isBlank()) {
                evictGraphCache(safeId);
            } else {
                graphCache.remove(assetIndexKey(loaded.type(), safeId));
            }
            cacheGraphIfCurrent(generation, loaded.type(), safeId, loaded.revision(), loaded.mutationId(), loaded.graph());
            notifyGraphChanged(loaded.type(), safeId);
            return loaded.graph().copy();
        }
        throw new IllegalStateException("Flow graph changed while validating the graph");
    }

    public synchronized void saveGraph(FlowGraph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Flow is required");
        }
        RuntimeObservation validation = validateGraphForMutation(graph);
        Long expectedRevision = graph != null && graph.getResourceRevision() > 0L ? graph.getResourceRevision() : null;
        saveGraph(graph, UUID.randomUUID(), expectedRevision, false, validation);
    }

    public synchronized void saveGraph(FlowGraph graph, UUID mutationId, long expectedRevision) {
        if (graph == null) {
            throw new IllegalArgumentException("Flow is required");
        }
        RuntimeObservation validation = validateGraphForMutation(graph);
        saveGraph(graph, mutationId, expectedRevision, true, validation);
    }

    public void saveGraph(FlowGraph graph, long expectedRevision, UUID mutationId) {
        saveGraph(graph, mutationId, expectedRevision);
    }

    public void saveGraph(FlowGraph graph, String mutationId, long expectedRevision) {
        saveGraph(graph, parseMutationId(mutationId), expectedRevision);
    }

    public synchronized GraphIdentity readGraphIdentity(String type, String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "read graph identity");
            if (safeId == null || type == null || !GRAPH_TYPES.contains(type)) {
                throw new IllegalArgumentException("Invalid graph identity");
            }
            StoredGraphState stored = readStoredGraphState(type, safeId).orElse(null);
            try {
                requireCoordinatedGraphState(requireAssetTransactions(), type, safeId, stored);
            } catch (IOException exception) {
                throw new IllegalStateException("Graph identity diverged from the shared asset coordinator: "
                    + type + ':' + safeId, exception);
            }
            return stored == null ? null : stored.identity();
        }
    }

    public GraphIdentity getGraphIdentity(String type, String id) {
        return readGraphIdentity(type, id);
    }

    public synchronized GraphIdentity readGraphIdentity(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "read graph identity");
            if (safeId == null) {
                return null;
            }
            String type = resolveStoredGraphType(safeId);
            return type.isBlank() ? null : readGraphIdentity(type, safeId);
        }
    }

    public synchronized void reclassifyGraph(FlowGraph graph, String targetType) {
        if (!Set.of("flow", "function", "command").contains(targetType)) {
            throw new IllegalArgumentException("Unsupported graph type: " + targetType);
        }
        if (graph == null) {
            throw new IllegalArgumentException("Flow is required");
        }
        RuntimeObservation validation = validateGraphForMutation(graph);
        reclassifyGraphAsset(graph, targetType, UUID.randomUUID(), validation);
    }

    private synchronized void reclassifyGraphAsset(FlowGraph graph, String targetType, UUID mutationId,
                                                   RuntimeObservation validation) {
        String safeId = safeId(graph.getId(), "reclassify flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        String sourceType = graphResourceType(graph);
        if (targetType.equals(sourceType)) {
            return;
        }
        PersistenceGeneration generation = capturePersistenceGeneration();
        long committedRevision = -1L;
        String committedMutation = mutationId.toString();
        try {
            try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
                requireValidationCurrent(validation);
                if ("command".equals(targetType)) {
                    FlowGraph commandCandidate = graph.copy();
                    commandCandidate.setResourceType("command");
                    commandCandidate.setFunction(false);
                    TypedCommandGraphAdapter.read(commandCandidate);
                    ensureCommandLabelAvailable(commandCandidate, safeId);
                }
                StoredGraphState stored = readStoredGraphState(sourceType, safeId)
                    .orElseThrow(() -> new IOException("Graph reclassification source does not exist: " + sourceType + ':' + safeId));
                if (stored.identity().deleted() || stored.assetFile() == null) {
                    throw new IOException("Graph reclassification source is not live: " + sourceType + ':' + safeId);
                }
                if (graph.getResourceRevision() > 0L && graph.getResourceRevision() != stored.identity().revision()) {
                    throw new ResourceRevisionConflictException(safeId, graph.getResourceRevision(), stored.identity().revision());
                }
                AssetTransactionCoordinator coordinator = requireAssetTransactions();
                AssetTransactionCoordinator.Snapshot snapshot = requireCoordinatedGraphState(
                    coordinator, sourceType, safeId, stored);
                AssetTransactionCoordinator.AssetKey sourceKey = assetKey(sourceType, safeId);
                AssetTransactionCoordinator.AssetKey targetKey = assetKey(targetType, safeId);
                AssetTransactionCoordinator.ExpectedState sourceState = snapshot.state(sourceKey)
                    .orElseThrow(() -> new IOException("Graph reclassification source is absent from the shared coordinator"));
                if (!(sourceState instanceof AssetTransactionCoordinator.Live)) {
                    throw new IOException("Graph reclassification source is not live in the shared coordinator");
                }
                if (snapshot.state(tombstoneKey(sourceType, safeId))
                    .filter(AssetTransactionCoordinator.Live.class::isInstance).isPresent()) {
                    throw new IOException("Graph reclassification source has a physical tombstone: " + sourceType + ':' + safeId);
                }
                AssetTransactionCoordinator.ExpectedState targetState = snapshot.state(targetKey)
                    .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
                if (!(targetState instanceof AssetTransactionCoordinator.Missing)) {
                    throw new IOException("Graph reclassification target identity already has durable lineage: " + targetType + ':' + safeId);
                }
                if (snapshot.state(tombstoneKey(targetType, safeId)).isPresent()) {
                    throw new IOException("Graph reclassification target tombstone already has durable lineage: "
                        + targetType + ':' + safeId);
                }
                Path preferred = safeAssetFolder(defaultFolderForType(targetType)).resolve(AssetFileFormat.idOnlyFileName(safeId));
                Path targetFile = collisionSafeWriteFile(targetType, safeId, preferred);
                if (stored.assetFile().toAbsolutePath().normalize().equals(targetFile.toAbsolutePath().normalize())) {
                    throw new IOException("Graph reclassification requires a distinct canonical target path");
                }
                long revision = Math.addExact(stored.identity().revision(), 1L);
                FlowGraph transformed = graph.copy();
                transformed.setFunction("function".equals(targetType));
                transformed.setResourceType(targetType);
                transformed.setResourceRevision(revision);
                transformed.setResourceHash("");
                transformed.setResourceMutationId(mutationId.toString());
                String encoded = AssetFileFormat.withResourceIdentity(
                    FlowSerializer.serialize(transformed), targetType, revision, mutationId.toString());
                String metadataJson = metadataWithResourcePath(targetType, safeId,
                    assetFolderPath(assetsDir.toPath(), targetFile), true);
                AssetTransactionCoordinator.Reclassification reclassification = AssetTransactionCoordinator.Reclassification.of(
                    sourceKey, stored.assetFile(), sourceState, targetKey, targetFile, targetState,
                    encoded.getBytes(StandardCharsets.UTF_8));
                List<AssetTransactionCoordinator.ProjectDelta> metadataDeltas = projectDeltas(snapshot, metadataJson);
                List<AssetTransactionCoordinator.AssetDelta> transactionAssets = new ArrayList<>(reclassification.assets());
                if (!metadataDeltas.isEmpty()) {
                    ResourceIdentity projectIdentity = projectMetadataIdentity(snapshot, projectMetadataResourceId());
                    long projectRevision = Math.addExact(projectIdentity == null ? 0L : projectIdentity.revision(), 1L);
                    String projectHash = canonicalProjectMetadataPayloadHash(metadataJson);
                    byte[] lineage = projectMetadataLineage(projectMetadataResourceId(), projectRevision, mutationId,
                        projectHash, false).getBytes(StandardCharsets.UTF_8);
                    AssetTransactionCoordinator.ExpectedState lineageState = snapshot.state(projectMetadataLineageKey())
                        .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
                    transactionAssets.add(AssetTransactionCoordinator.AssetDelta.write(projectMetadataLineageKey(),
                        projectMetadataLineageFile(), lineageState, lineage));
                }
                AssetTransactionCoordinator.TransactionResult result = coordinator.transact(
                    new AssetTransactionCoordinator.TransactionRequest(mutationId, snapshot.project(), transactionAssets,
                        metadataDeltas), validation.committedSequence());
                if (!(result.states().get(sourceKey) instanceof AssetTransactionCoordinator.Deleted sourceDeleted)
                    || sourceDeleted.revision() != revision
                    || !(result.states().get(targetKey) instanceof AssetTransactionCoordinator.Live targetLive)
                    || targetLive.revision() != revision
                    || Files.exists(stored.assetFile(), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)
                    || !targetLive.hash().equals(StorageSafety.sha256(Files.readAllBytes(targetFile)))) {
                    throw new IOException("Graph reclassification did not publish exact source and target lineage");
                }
                graph.setFunction(transformed.isFunction());
                graph.setResourceType(targetType);
                graph.setResourceRevision(revision);
                graph.setResourceMutationId(mutationId.toString());
                graph.setResourceHash(AssetFileFormat.contentHash(encoded));
                committedRevision = revision;
                graphCache.remove(assetIndexKey(sourceType, safeId));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reclassify flow: " + safeId, exception);
        }
        cacheGraphIfCurrent(generation, targetType, safeId, committedRevision, committedMutation, graph);
        notifyGraphChanged(sourceType, safeId);
        notifyGraphChanged(targetType, safeId);
    }

    public synchronized void restoreGraph(FlowGraph graph) {
        if (graph == null || graph.getId() == null) {
            return;
        }
        FlowGraph current = getGraph(graphResourceType(graph), graph.getId());
        graph.setResourceRevision(current != null ? current.getResourceRevision() : 0L);
        graph.setResourceMutationId("");
        saveGraph(graph);
    }

    private synchronized void saveGraph(FlowGraph graph, UUID mutationId, Long expectedRevision,
                                        boolean enforceExpectedRevision, RuntimeObservation validation) {
        if (graph == null) {
            throw new IllegalArgumentException("Flow is required");
        }
        Objects.requireNonNull(mutationId, "mutationId");
        if (expectedRevision != null && expectedRevision < 0L) {
            throw new IllegalArgumentException("Expected revision cannot be negative");
        }
        String safeId = safeId(graph.getId(), "save flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        PersistenceGeneration generation = capturePersistenceGeneration();
        String committedType = null;
        long committedRevision = -1L;
        String committedMutation = null;
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            String requestedType = graph.getResourceType();
            if (!Set.of("flow", "function", "command").contains(requestedType)) {
                requestedType = graphResourceTypeForWrite(graph);
            }
            AssetTransactionCoordinator coordinator = requireAssetTransactions();
            requireGraphSaveLineageType(graph, requestedType, safeId, coordinator.read(Function.identity()));
            if ("command".equals(requestedType)) {
                FlowGraph commandCandidate = graph.copy();
                commandCandidate.setResourceType("command");
                TypedCommandGraphAdapter.read(commandCandidate);
                ensureCommandLabelAvailable(commandCandidate, safeId);
            }
            Path preferredFile = writableGraphResourceFile(graph, safeId);
            if (preferredFile == null) {
                throw new IllegalStateException("Failed to resolve flow file");
            }
            Path file = collisionSafeWriteFile(requestedType, safeId, preferredFile);
            boolean existingFile = Files.exists(file);
            String existingType = existingFile ? AssetFileFormat.readResourceType(file) : "";
            String type = requestedType;
            String identityType = type;
            ensureValidGraphTombstone(identityType, safeId);
            StoredGraphState stored = readStoredGraphState(identityType, safeId)
                .orElseGet(() -> readStoredGraphState(type, safeId).orElse(null));
            AssetTransactionCoordinator.Snapshot coordinatorSnapshot = requireCoordinatedGraphState(
                coordinator, type, safeId, stored);
            if (stored != null && stored.identity().deleted()) {
                file = coordinatorSnapshot.path(assetKey(type, safeId))
                    .orElseThrow(() -> new IOException("Graph tombstone path is missing from coordinator state"));
            }
            Path storedFile = stored != null && stored.assetFile() != null ? stored.assetFile() : file;
            boolean storedLive = stored != null && !stored.identity().deleted();
            boolean exactExistingType = existingFile && type.equals(existingType);
            long currentRevision = stored != null ? stored.identity().revision() : exactExistingType ? AssetFileFormat.readRevision(file) : 0L;
            String currentMutationId = stored != null ? stored.identity().mutationId() : exactExistingType ? AssetFileFormat.readMutationId(file) : "";
            FlowGraph current = storedFile != null && Files.exists(storedFile) && storedLive
                ? FlowSerializer.deserialize(StorageSafety.readUtf8(storedFile)) : null;
            if (current != null) {
                applyResourceIdentity(current, storedFile);
            }
            if (enforceExpectedRevision && mutationId.toString().equals(currentMutationId)) {
                if (stored == null || stored.identity().deleted()) {
                    throw new IllegalStateException("Mutation ID was already committed for graph deletion: " + mutationId);
                }
                if (expectedRevision != null && expectedRevision != currentRevision - 1L) {
                    throw new IllegalStateException("Mutation ID was already committed with a different expected revision: " + mutationId);
                }
                if (current == null || !sameGraphPayload(graph, current, type)) {
                    throw new IllegalStateException("Mutation ID was already committed with a different graph payload: " + mutationId);
                }
                applyCurrentIdentity(graph, current);
                return;
            }
            if (enforceExpectedRevision && expectedRevision != currentRevision) {
                throw new ResourceRevisionConflictException(safeId, expectedRevision, currentRevision);
            }
            if (storedLive && (enforceExpectedRevision ? sameGraphPayload(graph, current, type) : sameGraphContent(graph, current))) {
                applyCurrentIdentity(graph, current);
                return;
            }
            requireValidationCurrent(validation);
            if (!enforceExpectedRevision && currentRevision > 0L && graph.getResourceRevision() > 0L
                && graph.getResourceRevision() != currentRevision) {
                throw new ResourceRevisionConflictException(safeId, graph.getResourceRevision(), currentRevision);
            }
            if (!enforceExpectedRevision && currentRevision == 0L && graph.getResourceRevision() > 0L) {
                graph.setResourceRevision(0L);
                graph.setResourceHash("");
                graph.setResourceMutationId("");
            }
            file = collisionSafeWriteFile(type, safeId, file);
            long revision = currentRevision + 1L;
            String mutationValue = mutationId.toString();
            graph.setFunction("function".equals(type));
            graph.setResourceType(type);
            graph.setResourceRevision(revision);
            graph.setResourceHash("");
            graph.setResourceMutationId(mutationValue);
            String json = FlowSerializer.serialize(graph);
            String assetJson = AssetFileFormat.withResourceIdentity(json, type, revision, mutationValue);
            String metadataJson = metadataWithResourcePath(type, safeId, assetFolderPath(assetsDir.toPath(), file), false);
            List<AssetMutation> mutations = new ArrayList<>();
            mutations.add(AssetMutation.write(assetKey(type, safeId), file, assetJson.getBytes(StandardCharsets.UTF_8)));
            boolean physicalTombstone = coordinatorSnapshot.state(tombstoneKey(type, safeId))
                .filter(AssetTransactionCoordinator.Live.class::isInstance).isPresent();
            if (stored != null && stored.identity().deleted() && physicalTombstone) {
                mutations.add(AssetMutation.delete(tombstoneKey(type, safeId), graphTombstoneFile(type, safeId)));
            } else if (physicalTombstone) {
                throw new IOException("Live graph has a physical tombstone: " + type + ':' + safeId);
            }
            AssetTransactionCoordinator.TransactionResult transaction = transactAssets(coordinator, coordinatorSnapshot,
                mutationId, mutations, metadataJson, validation.committedSequence());
            AssetTransactionCoordinator.ExpectedState saved = transaction.states().get(assetKey(type, safeId));
            if (!(saved instanceof AssetTransactionCoordinator.Live) || saved.revision() != revision) {
                throw new IOException("Graph save revision diverged from the shared asset coordinator");
            }
            graph.setResourceHash(AssetFileFormat.contentHash(assetJson));
            committedType = type;
            committedRevision = revision;
            committedMutation = mutationValue;
            evictGraphCache(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save flow: " + safeId, e);
        }
        cacheGraphIfCurrent(generation, committedType, safeId, committedRevision, committedMutation, graph);
        notifyGraphChanged(committedType, safeId);
    }

    private void requireGraphSaveLineageType(FlowGraph graph, String requestedType, String id,
                                             AssetTransactionCoordinator.Snapshot snapshot) {
        long revision = graph.getResourceRevision();
        String mutationId = graph.getResourceMutationId();
        if (revision <= 0L || mutationId == null || mutationId.isBlank()) {
            return;
        }
        AssetTransactionCoordinator.AssetKey requestedKey = assetKey(requestedType, id);
        AssetTransactionCoordinator.ExpectedState requestedState = snapshot.state(requestedKey).orElse(null);
        if (requestedState != null && requestedState.revision() == revision
            && mutationId.equals(snapshot.mutationValue(requestedKey).orElse(""))) {
            return;
        }
        for (String type : GRAPH_TYPES) {
            if (requestedType.equals(type)) {
                continue;
            }
            AssetTransactionCoordinator.AssetKey key = assetKey(type, id);
            AssetTransactionCoordinator.ExpectedState state = snapshot.state(key).orElse(null);
            if (state != null && state.revision() == revision
                && mutationId.equals(snapshot.mutationValue(key).orElse(""))) {
                throw new IllegalStateException("Graph lineage belongs to " + type + ':' + id
                    + "; use reclassifyGraph to change it to " + requestedType);
            }
        }
    }

    private boolean sameGraphContent(FlowGraph incoming, FlowGraph current) {
        if (incoming == null || current == null) {
            return false;
        }
        JsonObject incomingJson = gson.fromJson(FlowSerializer.serialize(incoming), JsonObject.class);
        JsonObject currentJson = gson.fromJson(FlowSerializer.serialize(current), JsonObject.class);
        for (String field : List.of("resourceRevision", "resourceHash", "resourceMutationId")) {
            incomingJson.remove(field);
            currentJson.remove(field);
        }
        return incomingJson.equals(currentJson);
    }

    private boolean sameGraphPayload(FlowGraph incoming, FlowGraph current, String type) {
        if (incoming == null || current == null) {
            return false;
        }
        FlowGraph normalized = incoming.copy();
        normalized.setResourceType(type);
        normalized.setFunction("function".equals(type));
        return sameGraphContent(normalized, current);
    }

    private void applyCurrentIdentity(FlowGraph graph, FlowGraph current) {
        graph.setFunction(current.isFunction());
        graph.setResourceType(current.getResourceType());
        graph.setResourceRevision(current.getResourceRevision());
        graph.setResourceHash(current.getResourceHash());
        graph.setResourceMutationId(current.getResourceMutationId());
    }

    public void setGraphValidator(FlowGraphValidator graphValidator) {
        this.graphValidator = graphValidator;
        this.graphValidationFunction = null;
    }

    public void setGraphValidator(Function<FlowGraph, FlowGraphValidationResult> graphValidator) {
        this.graphValidator = null;
        this.graphValidationFunction = graphValidator;
    }

    public void setGraphChangeListener(Consumer<GraphChange> graphChangeListener) {
        this.graphChangeListener = graphChangeListener;
    }

    public void setTypedCommandGraphChangeListener(Consumer<String> listener) {
        synchronized (typedCommandRefreshProofs) {
            typedCommandRefreshProofs.clear();
            typedCommandGraphChangeListener = listener;
            typedCommandGraphChangeListenerGeneration++;
        }
    }

    public boolean consumeTypedCommandRefreshProof(ServerResourceLocator locator, long revision, UUID mutationId,
                                                    boolean deleted) {
        long started = TemporaryLifecycleDiagnostics.start();
        Objects.requireNonNull(locator, "Typed command refresh locator is required");
        Objects.requireNonNull(mutationId, "Typed command refresh mutation ID is required");
        if (serverId == null || !serverId.equals(locator.serverId())
            || !OwnerId.of("restudio.resync").equals(locator.owner())
            || !GRAPH_TYPES.contains(locator.resourceType().value())) {
            return false;
        }
        boolean reused;
        synchronized (typedCommandRefreshProofs) {
            reused = typedCommandRefreshProofs.remove(new TypedCommandRefreshProof(locator, revision, mutationId, deleted));
        }
        TemporaryLifecycleDiagnostics.event("command_refresh_proof", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                locator.resourceType().value() + ':' + locator.id(), mutationId, null, null, revision, null, null),
                "outcome", reused ? "reused" : "missing", "deleted", deleted));
        return reused;
    }

    public FlowGraphValidationResult validateGraph(FlowGraph graph) {
        if (graphValidator != null) {
            return graphValidator.validate(graph);
        }
        if (graphValidationFunction == null) {
            return new FlowGraphValidationResult(List.of());
        }
        return graphValidationFunction.apply(graph);
    }

    public void requireValidGraph(FlowGraph graph) {
        FlowGraphValidationResult result = validateGraph(graph);
        if (!result.valid()) {
            throw new FlowGraphValidationException(result);
        }
        if ("command".equals(graphResourceType(graph))) {
            long commandStarts = graph.getNodes() != null ? graph.getNodes().values().stream()
                .filter(node -> node != null && ("event.resync.command".equals(node.getType()) || "event:resync_command".equals(node.getType())))
                .count() : 0L;
            if (commandStarts != 1L) {
                throw new IllegalArgumentException("Command graphs require exactly one Command Start node");
            }
        }
    }

    public synchronized void deleteGraph(String id) {
        UUID mutationId = UUID.randomUUID();
        String safeId = safeId(id, "delete flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        String type = resolveStoredGraphType(safeId);
        requireFunctionNotReferenced(type, safeId);
        deleteGraphFiles(type.isBlank() ? "flow" : type, safeId, mutationId, null, false);
    }

    public void deleteGraph(String id, UUID mutationId, long expectedRevision) {
        String type = resolveStoredGraphType(id);
        deleteGraph(type.isBlank() ? "flow" : type, id, mutationId, expectedRevision);
    }

    public void deleteGraph(String id, long expectedRevision, UUID mutationId) {
        deleteGraph(id, mutationId, expectedRevision);
    }

    public void deleteGraph(String id, String mutationId, long expectedRevision) {
        deleteGraph(id, parseMutationId(mutationId), expectedRevision);
    }

    public synchronized void deleteGraph(String type, String id) {
        UUID mutationId = UUID.randomUUID();
        String safeId = safeId(id, "delete flow");
        if (safeId == null || type == null || !Set.of("flow", "function", "command").contains(type)) {
            throw new IllegalArgumentException("Invalid flow identity");
        }
        requireFunctionNotReferenced(type, safeId);
        deleteGraphFiles(type, safeId, mutationId, null, false);
    }

    public synchronized void deleteGraph(String type, String id, UUID mutationId, long expectedRevision) {
        String safeId = safeId(id, "delete flow");
        if (safeId == null || type == null || !GRAPH_TYPES.contains(type)) {
            throw new IllegalArgumentException("Invalid flow identity");
        }
        requireFunctionNotReferenced(type, safeId);
        deleteGraphFiles(type, safeId, mutationId, expectedRevision, true);
    }

    public void deleteGraph(String type, String id, long expectedRevision, UUID mutationId) {
        deleteGraph(type, id, mutationId, expectedRevision);
    }

    public void deleteGraph(String type, String id, String mutationId, long expectedRevision) {
        deleteGraph(type, id, parseMutationId(mutationId), expectedRevision);
    }

    public void deleteGraph(String type, String id, long expectedRevision, String mutationId) {
        deleteGraph(type, id, parseMutationId(mutationId), expectedRevision);
    }

    public synchronized void forceDeleteGraph(String id) {
        String safeId = safeId(id, "delete flow");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid flow id");
        }
        String type = resolveStoredGraphType(safeId);
        deleteGraphFiles(type.isBlank() ? "flow" : type, safeId, UUID.randomUUID(), null, false);
    }

    public synchronized List<FlowFunctionReference> findFunctionReferences(String functionId) {
        String safeFunctionId = safeId(functionId, "analyze function references");
        if (safeFunctionId == null) {
            return List.of();
        }
        String expectedType = CustomFunctionNodeDefinitions.NODE_PREFIX + safeFunctionId;
        List<FlowFunctionReference> references = new ArrayList<>();
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            PersistenceGeneration generation = capturePersistenceGeneration();
            long rootSequence = requireAssetTransactions().read(AssetTransactionCoordinator.Snapshot::rootSequence);
            for (String graphId : listFlowIds()) {
                FlowGraph graph = getGraph("flow", graphId);
                if (graph == null || graph.getNodes() == null) {
                    continue;
                }
                for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
                    if (entry.getValue() != null && expectedType.equals(entry.getValue().getType())) {
                        references.add(new FlowFunctionReference(graphId, entry.getKey()));
                    }
                }
            }
            if (!isCurrentPersistenceGeneration(generation)
                || requireAssetTransactions().read(AssetTransactionCoordinator.Snapshot::rootSequence) != rootSequence) {
                throw new IllegalStateException("Flow persistence changed while analyzing function references");
            }
        }
        references.sort(Comparator.comparing(FlowFunctionReference::callerGraphId).thenComparing(FlowFunctionReference::nodeId));
        return List.copyOf(references);
    }

    private void requireFunctionNotReferenced(String type, String id) {
        if (!"function".equals(type)) {
            return;
        }
        List<FlowFunctionReference> references = findFunctionReferences(id).stream()
            .filter(reference -> !id.equals(reference.callerGraphId()))
            .toList();
        if (!references.isEmpty()) {
            throw new FlowFunctionInUseException(id, references);
        }
    }

    private RuntimeObservation validateGraphForMutation(FlowGraph graph) {
        RuntimeObservation validation = observeRuntime()
            .orElseThrow(() -> new IllegalStateException("Asset state is unavailable for graph validation"));
        requireValidGraph(graph);
        return validation;
    }

    private void requireValidationCurrent(RuntimeObservation validation) {
        if (!isRuntimeObservationCurrent(validation)) {
            throw new IllegalStateException("Asset state changed while validating the graph");
        }
    }

    private synchronized void deleteGraphFiles(String type, String safeId, UUID mutationId, Long expectedRevision,
                                                boolean enforceExpectedRevision) {
        PersistenceGeneration generation = capturePersistenceGeneration();
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            Objects.requireNonNull(mutationId, "mutationId");
            if (enforceExpectedRevision && expectedRevision < 0L) {
                throw new IllegalArgumentException("Expected revision cannot be negative");
            }
            ensureValidGraphTombstone(type, safeId);
            StoredGraphState stored = readStoredGraphState(type, safeId).orElse(null);
            AssetTransactionCoordinator coordinator = requireAssetTransactions();
            AssetTransactionCoordinator.Snapshot coordinatorSnapshot = requireCoordinatedGraphState(
                coordinator, type, safeId, stored);
            long currentRevision = stored != null ? stored.identity().revision() : 0L;
            String mutationValue = mutationId.toString();
            if (enforceExpectedRevision && stored != null && mutationValue.equals(stored.identity().mutationId())) {
                if (stored.identity().deleted()) {
                    if (expectedRevision != null && expectedRevision != currentRevision - 1L) {
                        throw new IllegalStateException("Mutation ID was already committed with a different expected revision: " + mutationId);
                    }
                    quarantineLegacyGraphAfterCommit(type, safeId, verifiedLegacyGraphDeleteSource(type, safeId));
                    graphCache.remove(assetIndexKey(type, safeId));
                    return;
                }
                throw new IllegalStateException("Mutation ID was already committed for graph save: " + mutationId);
            }
            if (enforceExpectedRevision && expectedRevision != currentRevision) {
                throw new ResourceRevisionConflictException(safeId, expectedRevision, currentRevision);
            }
            List<Path> liveFiles = graphAssetFiles(type, safeId);
            LegacyGraphDeleteSource legacySource = verifiedLegacyGraphDeleteSource(type, safeId);
            Path liveFile;
            String payloadHash;
            if (stored != null && !stored.identity().deleted()) {
                if (liveFiles.size() != 1 || stored.assetFile() == null
                    || !stored.assetFile().toAbsolutePath().normalize().equals(liveFiles.getFirst().toAbsolutePath().normalize())) {
                    throw new IOException("Graph deletion requires exactly one canonical live asset: " + type + ':' + safeId);
                }
                liveFile = liveFiles.getFirst();
                payloadHash = stored.identity().payloadHash();
            } else {
                if (!liveFiles.isEmpty()) {
                    throw new IOException("Legacy graph deletion requires zero canonical live assets: " + type + ':' + safeId);
                }
                if (legacySource == null) {
                    throw new IOException("Graph deletion requires exactly one verified live source: " + type + ':' + safeId);
                }
                liveFile = safeAssetFolder(defaultFolderForType(type)).resolve(AssetFileFormat.idOnlyFileName(safeId));
                payloadHash = legacySource.payloadHash();
            }
            if (!isCanonicalPayloadHash(payloadHash)) {
                throw new IllegalStateException("Cannot persist graph tombstone without the prior canonical payload hash: " + type + ':' + safeId);
            }
            long revision = currentRevision + 1L;
            JsonObject tombstone = new JsonObject();
            tombstone.addProperty("type", type);
            tombstone.addProperty("id", safeId);
            tombstone.addProperty("revision", Math.max(1L, revision));
            tombstone.addProperty("payloadHash", payloadHash);
            tombstone.addProperty("mutationId", mutationValue);
            tombstone.addProperty(AssetFileFormat.MUTATION_ID, mutationValue);
            tombstone.addProperty("deleted", true);
            tombstone.addProperty("deletedAt", System.currentTimeMillis());
            String metadataJson = metadataWithoutResource(type, safeId);
            AssetTransactionCoordinator.TransactionResult transaction = transactAssets(coordinator, coordinatorSnapshot,
                mutationId, List.of(
                AssetMutation.delete(assetKey(type, safeId), liveFile),
                AssetMutation.write(tombstoneKey(type, safeId), graphTombstoneFile(type, safeId),
                    gson.toJson(tombstone).getBytes(StandardCharsets.UTF_8))), metadataJson);
            AssetTransactionCoordinator.ExpectedState deleted = transaction.states().get(assetKey(type, safeId));
            if (!(deleted instanceof AssetTransactionCoordinator.Deleted) || deleted.revision() != revision) {
                throw new IOException("Graph delete revision diverged from the shared asset coordinator");
            }
            AssetTransactionCoordinator.Snapshot committed = coordinator.read(Function.identity());
            AssetTransactionCoordinator.ExpectedState committedDeleted = committed.state(assetKey(type, safeId)).orElse(null);
            Path committedPath = committed.path(assetKey(type, safeId)).orElse(null);
            if (!deleted.equals(committedDeleted) || committedPath == null
                || !committedPath.toAbsolutePath().normalize().equals(liveFile.toAbsolutePath().normalize())
                || !coordinatedMutationMatches(committed, assetKey(type, safeId), mutationValue)) {
                throw new IOException("Graph delete state diverged from the shared asset coordinator");
            }
            requirePhysicalTombstoneCoordinated(committed, type, safeId, graphTombstoneFile(type, safeId), mutationValue);
            quarantineLegacyGraphAfterCommit(type, safeId, legacySource);
            graphCache.remove(assetIndexKey(type, safeId));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete flow: " + safeId, e);
        }
        if (isCurrentPersistenceGeneration(generation)) {
            notifyGraphChanged(type, safeId);
        }
    }

    private void notifyGraphChanged(String graphType, String graphId) {
        notifyGraphChanged(graphType, graphId, null);
    }

    private void notifyGraphChanged(String graphType, String graphId, TypedCommandRefreshProof refreshProof) {
        long started = TemporaryLifecycleDiagnostics.start();
        boolean graphNotified = false;
        boolean typedCommandNotified = false;
        if (graphChangeListener != null && graphId != null && !graphId.isBlank()) {
            try {
                graphChangeListener.accept(new GraphChange(graphType, graphId));
                graphNotified = true;
            } catch (Throwable failure) {
                Log.error("Flow graph post-commit listener failed for " + graphId, failure);
            }
        }
        Consumer<String> typedCommandListener;
        long typedCommandListenerGeneration;
        synchronized (typedCommandRefreshProofs) {
            typedCommandListener = typedCommandGraphChangeListener;
            typedCommandListenerGeneration = typedCommandGraphChangeListenerGeneration;
            typedCommandRefreshProofs.remove(refreshProof);
        }
        if (typedCommandListener != null) {
            try {
                typedCommandListener.accept(graphId == null ? "" : graphId);
                typedCommandNotified = true;
                retainTypedCommandRefreshProof(refreshProof, typedCommandListenerGeneration);
            } catch (Throwable failure) {
                Log.error("Typed command graph post-commit listener failed for "
                    + (graphId == null ? "" : graphId), failure);
            }
        }
        TemporaryLifecycleDiagnostics.event("core_storage_notification", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                graphId == null ? null : graphType + ':' + graphId, refreshProof == null ? null : refreshProof.mutationId(),
                null, null, refreshProof == null ? null : refreshProof.revision(), null, null),
                "outcome", graphNotified || typedCommandNotified ? "notified" : "unavailable",
                "transitionPublished", graphNotified || typedCommandNotified,
                "notificationChannel", graphNotified && typedCommandNotified ? "graph_and_typed_command"
                    : graphNotified ? "graph" : typedCommandNotified ? "typed_command" : "none"));
    }

    public record GraphChange(String type, String id) {
        public GraphChange {
            type = type == null ? "" : type;
            id = id == null ? "" : id;
        }
    }

    private TypedCommandRefreshProof typedCommandRefreshProof(ServerResourceLocator locator, long revision,
                                                               UUID mutationId, boolean deleted) {
        if (!GRAPH_TYPES.contains(locator.resourceType().value())) {
            return null;
        }
        return new TypedCommandRefreshProof(locator, revision, mutationId, deleted);
    }

    private void retainTypedCommandRefreshProof(TypedCommandRefreshProof proof, long listenerGeneration) {
        if (proof == null) {
            return;
        }
        synchronized (typedCommandRefreshProofs) {
            if (listenerGeneration != typedCommandGraphChangeListenerGeneration) {
                return;
            }
            typedCommandRefreshProofs.remove(proof);
            typedCommandRefreshProofs.add(proof);
            while (typedCommandRefreshProofs.size() > MAX_TYPED_COMMAND_REFRESH_PROOFS) {
                var iterator = typedCommandRefreshProofs.iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }

    private GuiDefinition copyGui(GuiDefinition gui) {
        return gui == null ? null : FlowSerializer.deserializeGui(FlowSerializer.serializeGui(gui));
    }

    private ScoreboardDefinition copyScoreboard(ScoreboardDefinition scoreboard) {
        if (scoreboard == null) {
            return null;
        }
        ScoreboardDefinition copy = new ScoreboardDefinition();
        copy.setId(scoreboard.getId());
        copy.setEnabled(scoreboard.isEnabled());
        copy.setTitle(scoreboard.getTitle());
        copy.setObjectiveId(scoreboard.getObjectiveId());
        copy.setDisplaySlot(scoreboard.getDisplaySlot());
        copy.setLines(scoreboard.getLines() == null ? new ArrayList<>() : new ArrayList<>(scoreboard.getLines()));
        return copy;
    }

    private TabDefinition copyTab(TabDefinition tab) {
        if (tab == null) {
            return null;
        }
        TabDefinition copy = new TabDefinition(tab.getId());
        copy.setEnabled(tab.isEnabled());
        copy.setHeader(tab.getHeader());
        copy.setFooter(tab.getFooter());
        copy.setEntryFormat(tab.getEntryFormat());
        return copy;
    }

    public synchronized GuiDefinition getGui(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "load GUI");
            if (safeId == null) {
                return null;
            }
            GuiDefinition cached = guiCache.get(safeId);
            if (cached != null && cachedResourceIdentityMatches("gui", safeId, guiCacheIdentities.get(safeId))) {
                return copyGui(cached);
            }
            if (cached != null) {
                guiCache.remove(safeId, cached);
                guiCacheIdentities.remove(safeId);
            }

            Path file = resourceFile(guiDir, "gui", safeId, "load GUI");
            if (file != null && Files.exists(file)) {
                try {
                    String json = StorageSafety.readUtf8(file);
                    GuiDefinition gui = FlowSerializer.deserializeGui(json);
                    if (gui != null && (gui.getId() == null || gui.getId().isBlank())) {
                        gui.setId(safeId);
                    }
                    GuiDefinition stored = copyGui(gui);
                    guiCache.put(safeId, stored);
                    rememberResourceCacheIdentity("gui", safeId);
                    return copyGui(stored);
                } catch (IOException e) {
                    Log.warn("Failed to load GUI: " + safeId + " - " + e.getMessage());
                }
            }
            return null;
        }
    }

    public synchronized void saveGui(GuiDefinition gui) {
        saveGui(gui, UUID.randomUUID(), -1L, false);
    }

    public synchronized void saveGui(GuiDefinition gui, UUID mutationId, long expectedRevision) {
        requireResourceMutationRequest(mutationId, expectedRevision);
        saveGui(gui, mutationId, expectedRevision, true);
    }

    public synchronized TypedAggregateCreate createGui(GuiDefinition gui, UUID mutationId, long expectedRevision,
                                                        ResourcePresentationIntent presentation,
                                                        String expectedPayloadHash) {
        String safeId = gui != null ? safeId(gui.getId(), "create GUI") : null;
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid GUI id");
        }
        String json = FlowSerializer.serializeGui(gui);
        TypedAggregateCreate created = createTypedResource("gui", safeId, json, mutationId, expectedRevision,
            presentation, expectedPayloadHash);
        guiCache.put(safeId, FlowSerializer.deserializeGui(created.canonicalPayloadJson()));
        rememberResourceCacheIdentity("gui", safeId);
        return created;
    }

    private void saveGui(GuiDefinition gui, UUID mutationId, long expectedRevision, boolean enforceExpectedRevision) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            String safeId = gui != null ? safeId(gui.getId(), "save GUI") : null;
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid GUI id");
            }
            Path file = writableResourceFile(guiDir, "gui", safeId, "save GUI");
            if (file == null) {
                throw new IllegalStateException("Failed to resolve GUI file");
            }
            try {
                file = collisionSafeWriteFile("gui", safeId, file);
                String json = FlowSerializer.serializeGui(gui);
                SavedResource saved = transactResourceSave("gui", safeId, file, json, mutationId,
                    expectedRevision, enforceExpectedRevision);
                guiCache.put(safeId, FlowSerializer.deserializeGui(json));
                rememberResourceCacheIdentity("gui", safeId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to save GUI: " + safeId, e);
            }
        }
    }

    public synchronized void deleteGui(String id) {
        deleteGui(id, UUID.randomUUID(), -1L, false);
    }

    public synchronized void deleteGui(String id, UUID mutationId, long expectedRevision) {
        requireResourceMutationRequest(mutationId, expectedRevision);
        deleteGui(id, mutationId, expectedRevision, true);
    }

    private void deleteGui(String id, UUID mutationId, long expectedRevision, boolean enforceExpectedRevision) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            String safeId = safeId(id, "delete GUI");
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid GUI id");
            }
            try {
                deleteResourceFiles(guiDir, "gui", safeId, mutationId, expectedRevision, enforceExpectedRevision);
                guiCache.remove(safeId);
                guiCacheIdentities.remove(safeId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to delete GUI: " + safeId, e);
            }
        }
    }

    public synchronized ScoreboardDefinition getScoreboard(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "load scoreboard");
            if (safeId == null) {
                return null;
            }
            ScoreboardDefinition cached = scoreboardCache.get(safeId);
            if (cached != null && cachedResourceIdentityMatches("scoreboard", safeId, scoreboardCacheIdentities.get(safeId))) {
                return copyScoreboard(cached);
            }
            if (cached != null) {
                scoreboardCache.remove(safeId, cached);
                scoreboardCacheIdentities.remove(safeId);
            }

            Path file = resourceFile(scoreboardDir, "scoreboard", safeId, "load scoreboard");
            if (file != null && Files.exists(file)) {
                try {
                    String json = StorageSafety.readUtf8(file);
                    ScoreboardDefinition scoreboard = FlowSerializer.deserializeScoreboard(json);
                    if (scoreboard != null && (scoreboard.getId() == null || scoreboard.getId().isBlank())) {
                        scoreboard.setId(safeId);
                    }
                    ScoreboardDefinition stored = copyScoreboard(scoreboard);
                    scoreboardCache.put(safeId, stored);
                    rememberResourceCacheIdentity("scoreboard", safeId);
                    return copyScoreboard(stored);
                } catch (IOException e) {
                    Log.warn("Failed to load scoreboard: " + safeId + " - " + e.getMessage());
                }
            }
            return null;
        }
    }

    public synchronized void saveScoreboard(ScoreboardDefinition scoreboard) {
        saveScoreboard(scoreboard, UUID.randomUUID(), -1L, false);
    }

    public synchronized void saveScoreboard(ScoreboardDefinition scoreboard, UUID mutationId, long expectedRevision) {
        requireResourceMutationRequest(mutationId, expectedRevision);
        saveScoreboard(scoreboard, mutationId, expectedRevision, true);
    }

    public synchronized TypedAggregateCreate createScoreboard(ScoreboardDefinition scoreboard, UUID mutationId,
                                                               long expectedRevision,
                                                               ResourcePresentationIntent presentation,
                                                               String expectedPayloadHash) {
        String safeId = scoreboard != null ? safeId(scoreboard.getId(), "create scoreboard") : null;
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid scoreboard id");
        }
        String json = FlowSerializer.serializeScoreboard(scoreboard);
        TypedAggregateCreate created = createTypedResource("scoreboard", safeId, json, mutationId, expectedRevision,
            presentation, expectedPayloadHash);
        scoreboardCache.put(safeId, FlowSerializer.deserializeScoreboard(created.canonicalPayloadJson()));
        rememberResourceCacheIdentity("scoreboard", safeId);
        return created;
    }

    private void saveScoreboard(ScoreboardDefinition scoreboard, UUID mutationId, long expectedRevision,
                                boolean enforceExpectedRevision) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            String safeId = scoreboard != null ? safeId(scoreboard.getId(), "save scoreboard") : null;
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid scoreboard id");
            }
            Path file = writableResourceFile(scoreboardDir, "scoreboard", safeId, "save scoreboard");
            if (file == null) {
                throw new IllegalStateException("Failed to resolve scoreboard file");
            }
            try {
                file = collisionSafeWriteFile("scoreboard", safeId, file);
                String json = FlowSerializer.serializeScoreboard(scoreboard);
                SavedResource saved = transactResourceSave("scoreboard", safeId, file, json, mutationId,
                    expectedRevision, enforceExpectedRevision);
                scoreboardCache.put(safeId, FlowSerializer.deserializeScoreboard(json));
                rememberResourceCacheIdentity("scoreboard", safeId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to save scoreboard: " + safeId, e);
            }
        }
    }

    public synchronized void deleteScoreboard(String id) {
        deleteScoreboard(id, UUID.randomUUID(), -1L, false);
    }

    public synchronized void deleteScoreboard(String id, UUID mutationId, long expectedRevision) {
        requireResourceMutationRequest(mutationId, expectedRevision);
        deleteScoreboard(id, mutationId, expectedRevision, true);
    }

    private void deleteScoreboard(String id, UUID mutationId, long expectedRevision,
                                  boolean enforceExpectedRevision) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            String safeId = safeId(id, "delete scoreboard");
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid scoreboard id");
            }
            try {
                deleteResourceFiles(scoreboardDir, "scoreboard", safeId, mutationId, expectedRevision,
                    enforceExpectedRevision);
                scoreboardCache.remove(safeId);
                scoreboardCacheIdentities.remove(safeId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to delete scoreboard: " + safeId, e);
            }
        }
    }

    public synchronized TabDefinition getRuntimeTab(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "load runtime tab");
            if (safeId == null) {
                return null;
            }
            AssetTransactionCoordinator coordinator = assetTransactions;
            AssetTransactionCoordinator.AssetKey key = assetKey("tab", safeId);
            for (int attempt = 0; attempt < 3; attempt++) {
                AssetTransactionCoordinator.CommittedAsset committed = coordinator.committedAsset(key).orElse(null);
                if (committed == null || !(committed.state() instanceof AssetTransactionCoordinator.Live live)) {
                    tabCache.remove(safeId);
                    tabCacheIdentities.remove(safeId);
                    runtimeTabIdentities.remove(safeId);
                    return null;
                }
                RuntimeTabIdentity identity = new RuntimeTabIdentity(coordinator, persistenceGeneration, committed);
                TabDefinition cached = tabCache.get(safeId);
                if (cached != null && identity.equals(runtimeTabIdentities.get(safeId))) {
                    return copyTab(cached);
                }
                runtimeTabIdentities.remove(safeId);
                TabDefinition definition = getTab(safeId);
                if (!committed.equals(coordinator.committedAsset(key).orElse(null))) {
                    continue;
                }
                CachedResourceIdentity verified = tabCacheIdentities.get(safeId);
                if (definition == null || verified == null || verified.revision() != live.revision()
                    || !verified.mutationId().equals(committed.mutationId().value())) {
                    throw new IllegalStateException("Runtime tab does not match committed state: " + safeId);
                }
                runtimeTabIdentities.put(safeId, identity);
                return definition;
            }
            throw new IllegalStateException("Tab changed while preparing runtime definition: " + safeId);
        }
    }

    private record RuntimeTabIdentity(AssetTransactionCoordinator coordinator, long persistenceGeneration,
                                      AssetTransactionCoordinator.CommittedAsset asset) {
    }

    public synchronized TabDefinition getTab(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "load tab");
            if (safeId == null) {
                return null;
            }
            TabDefinition cached = tabCache.get(safeId);
            if (cached != null && cachedResourceIdentityMatches("tab", safeId, tabCacheIdentities.get(safeId))) {
                return copyTab(cached);
            }
            if (cached != null) {
                tabCache.remove(safeId, cached);
                tabCacheIdentities.remove(safeId);
            }

            Path file = resourceFile(tabDir, "tab", safeId, "load tab");
            if (file != null && Files.exists(file)) {
                try {
                    String json = StorageSafety.readUtf8(file);
                    TabDefinition tab = FlowSerializer.deserializeTab(json);
                    if (tab != null && (tab.getId() == null || tab.getId().isBlank())) {
                        tab.setId(safeId);
                    }
                    TabDefinition stored = copyTab(tab);
                    tabCache.put(safeId, stored);
                    rememberResourceCacheIdentity("tab", safeId);
                    return copyTab(stored);
                } catch (IOException e) {
                    Log.warn("Failed to load tab: " + safeId + " - " + e.getMessage());
                }
            }
            return null;
        }
    }

    public synchronized void saveTab(TabDefinition tab) {
        saveTab(tab, UUID.randomUUID(), -1L, false);
    }

    public synchronized void saveTab(TabDefinition tab, UUID mutationId, long expectedRevision) {
        requireResourceMutationRequest(mutationId, expectedRevision);
        saveTab(tab, mutationId, expectedRevision, true);
    }

    public synchronized TypedAggregateCreate createTab(TabDefinition tab, UUID mutationId, long expectedRevision,
                                                        ResourcePresentationIntent presentation,
                                                        String expectedPayloadHash) {
        String safeId = tab != null ? safeId(tab.getId(), "create tab") : null;
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid tab id");
        }
        String json = FlowSerializer.serializeTab(tab);
        TypedAggregateCreate created = createTypedResource("tab", safeId, json, mutationId, expectedRevision,
            presentation, expectedPayloadHash);
        tabCache.put(safeId, FlowSerializer.deserializeTab(created.canonicalPayloadJson()));
        rememberResourceCacheIdentity("tab", safeId);
        return created;
    }

    private void saveTab(TabDefinition tab, UUID mutationId, long expectedRevision,
                         boolean enforceExpectedRevision) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            String safeId = tab != null ? safeId(tab.getId(), "save tab") : null;
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid tab id");
            }
            Path file = writableResourceFile(tabDir, "tab", safeId, "save tab");
            if (file == null) {
                throw new IllegalStateException("Failed to resolve tab file");
            }
            try {
                file = collisionSafeWriteFile("tab", safeId, file);
                String json = FlowSerializer.serializeTab(tab);
                SavedResource saved = transactResourceSave("tab", safeId, file, json, mutationId,
                    expectedRevision, enforceExpectedRevision);
                tabCache.put(safeId, FlowSerializer.deserializeTab(json));
                rememberResourceCacheIdentity("tab", safeId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to save tab: " + safeId, e);
            }
        }
    }

    public synchronized void deleteTab(String id) {
        deleteTab(id, UUID.randomUUID(), -1L, false);
    }

    public synchronized void deleteTab(String id, UUID mutationId, long expectedRevision) {
        requireResourceMutationRequest(mutationId, expectedRevision);
        deleteTab(id, mutationId, expectedRevision, true);
    }

    private void deleteTab(String id, UUID mutationId, long expectedRevision, boolean enforceExpectedRevision) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            String safeId = safeId(id, "delete tab");
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid tab id");
            }
            try {
                deleteResourceFiles(tabDir, "tab", safeId, mutationId, expectedRevision,
                    enforceExpectedRevision);
                tabCache.remove(safeId);
                tabCacheIdentities.remove(safeId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to delete tab: " + safeId, e);
            }
        }
    }

    private TypedAggregateCreate createTypedResource(String type, String id, String json, UUID mutationId,
                                                      long expectedRevision, ResourcePresentationIntent presentation,
                                                      String expectedPayloadHash) {
        requireResourceMutationRequest(mutationId, expectedRevision);
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(expectedPayloadHash, "expectedPayloadHash");
        if (!TYPED_RESOURCE_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported aggregate typed resource: " + type);
        }
        long revision = Math.addExact(expectedRevision, 1L);
        String payloadHash = canonicalResourcePayloadHash(type, json);
        if (!expectedPayloadHash.equals(payloadHash)) {
            throw AggregateResourceCreateStorage.rejectBeforeCommit("RESOURCE_PAYLOAD_INVALID",
                "The resource defaults changed. Create the resource again with the current editor.",
                new IllegalArgumentException("Typed resource changed during aggregate create validation: " + type + ':' + id));
        }
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            CoreProjectMetadata projectMetadata = readCoreProjectMetadata();
            AssetTransactionCoordinator coordinator = requireAssetTransactions();
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
            AssetTransactionCoordinator.AssetKey key = assetKey(type, id);
            AssetTransactionCoordinator.ExpectedState current = snapshot.state(key)
                .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
            String currentMutation = snapshot.mutationValue(key).orElse("");
            if (current instanceof AssetTransactionCoordinator.Live && mutationId.toString().equals(currentMutation)) {
                ResourceIdentity primary = typedResourceIdentity(snapshot, type, id);
                if (primary == null || primary.revision() != revision || !expectedPayloadHash.equals(primary.payloadHash())) {
                    throw new IllegalStateException("Aggregate typed create replay does not match durable primary state");
                }
                requirePresentation(projectMetadata.snapshot(), type, id, presentation);
                Path expectedPath = presentationAssetPath(id, presentation.path());
                Path currentPath = snapshot.path(key).orElseThrow(() ->
                    new IllegalStateException("Aggregate typed create replay has no coordinated path"));
                if (!expectedPath.equals(currentPath.toAbsolutePath().normalize())) {
                    throw new IllegalStateException("Aggregate typed create replay path does not match durable state");
                }
                ResourceIdentity metadataIdentity = projectMetadataIdentity(snapshot, projectMetadataResourceId());
                if (metadataIdentity == null || !mutationId.toString().equals(metadataIdentity.mutationId())) {
                    throw new IllegalStateException("Aggregate typed create replay has no exact project metadata participant");
                }
                String canonicalPayload = typedCanonicalPayloadJson(type, StorageSafety.readUtf8(currentPath));
                return new TypedAggregateCreate(primary, canonicalPayload, metadataIdentity,
                    canonicalProjectMetadataJson(snapshot.metadata().serializedJson()), true);
            }
            if (!(current instanceof AssetTransactionCoordinator.Missing)
                && !(current instanceof AssetTransactionCoordinator.Deleted)) {
                throw new IllegalStateException("Aggregate typed create target already has durable lineage: " + type + ':' + id);
            }
            ResourceIdentity previous = typedResourceIdentity(snapshot, type, id);
            if ((previous == null ? 0L : previous.revision()) != expectedRevision) {
                throw new ResourceRevisionConflictException(id, expectedRevision, previous == null ? 0L : previous.revision());
            }
            if (current instanceof AssetTransactionCoordinator.Missing && snapshot.state(tombstoneKey(type, id)).isPresent()) {
                throw new IllegalStateException("Aggregate typed create target has orphaned tombstone lineage: " + type + ':' + id);
            }
            Path file = presentationAssetPath(id, presentation.path());
            String metadataJson = metadataWithPresentation(projectMetadata, type, id, presentation);
            String aggregateMetadataJson = projectMetadataAfter(snapshot, projectDeltas(snapshot, metadataJson));
            byte[] encoded = AssetFileFormat.withResourceIdentity(json, type, revision, mutationId.toString())
                .getBytes(StandardCharsets.UTF_8);
            ResourceIdentity previousMetadata = projectMetadataIdentity(snapshot, projectMetadataResourceId());
            List<AssetMutation> mutations = new ArrayList<>();
            mutations.add(AssetMutation.write(key, file, encoded));
            if (current instanceof AssetTransactionCoordinator.Deleted) {
                mutations.add(AssetMutation.delete(tombstoneKey(type, id), graphTombstoneFile(type, id)));
            }
            AssetTransactionCoordinator.TransactionResult transaction = transactAssets(coordinator, snapshot, mutationId,
                mutations, metadataJson);
            AssetTransactionCoordinator.ExpectedState saved = transaction.states().get(key);
            if (!(saved instanceof AssetTransactionCoordinator.Live live) || saved.revision() != revision
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(file)))) {
                throw new IOException("Aggregate typed create primary state diverged from its transaction");
            }
            verifyCoreProjectMetadata(assetsDir.toPath().resolve("project.json"), metadataJson);
            long metadataRevision = Math.addExact(previousMetadata == null ? 0L : previousMetadata.revision(), 1L);
            String metadataHash = canonicalProjectMetadataPayloadHash(aggregateMetadataJson);
            long expectedProjectRevision = Math.addExact(snapshot.project().revision(), 1L);
            if (!mutationId.equals(transaction.mutationId()) || transaction.project().revision() != expectedProjectRevision
                || !transaction.project().hash().equals(AssetProjectMetadata.parse(aggregateMetadataJson).hash())) {
                throw new IOException("Aggregate typed create project metadata result diverged from its transaction");
            }
            AssetTransactionCoordinator.Snapshot committedSnapshot = coordinator.read(Function.identity());
            ResourceIdentity primary = typedResourceIdentity(committedSnapshot, type, id);
            ResourceIdentity metadataIdentity = projectMetadataIdentity(committedSnapshot, projectMetadataResourceId());
            if (primary == null || primary.deleted() || primary.revision() != revision
                || !mutationId.toString().equals(primary.mutationId()) || !expectedPayloadHash.equals(primary.payloadHash())
                || metadataIdentity == null || metadataIdentity.deleted() || metadataIdentity.revision() != metadataRevision
                || !mutationId.toString().equals(metadataIdentity.mutationId())
                || !metadataHash.equals(metadataIdentity.payloadHash())
                || !transaction.project().equals(committedSnapshot.project())) {
                throw new IOException("Aggregate typed create durable state differs from its transaction");
            }
            return new TypedAggregateCreate(primary, typedCanonicalPayloadJson(type, StorageSafety.readUtf8(file)),
                metadataIdentity, aggregateMetadataJson, transaction.replay());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create typed resource " + type + ':' + id, exception);
        }
    }

    private ResourceIdentity typedResourceIdentity(AssetTransactionCoordinator.Snapshot snapshot, String type,
                                                   String id) throws IOException {
        AssetTransactionCoordinator.AssetKey key = assetKey(type, id);
        AssetTransactionCoordinator.ExpectedState state = snapshot.state(key)
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        if (state instanceof AssetTransactionCoordinator.Missing) {
            if (snapshot.state(tombstoneKey(type, id)).isPresent()) {
                throw new IllegalStateException("Resource tombstone exists without logical coordinator lineage: "
                    + type + ':' + id);
            }
            return null;
        }
        String mutationId = snapshot.mutationValue(key)
            .orElseThrow(() -> new IllegalStateException("Resource mutation identity is missing: " + type + ':' + id));
        if (state instanceof AssetTransactionCoordinator.Deleted) {
            Path tombstone = graphTombstoneFile(type, id);
            requirePhysicalTombstoneCoordinated(snapshot, type, id, tombstone, mutationId);
            GraphIdentity identity = readGraphTombstoneIdentity(type, id, tombstone);
            if (identity == null || identity.revision() != state.revision() || !mutationId.equals(identity.mutationId())) {
                throw new IllegalStateException("Resource tombstone identity diverged from the shared asset coordinator: "
                    + type + ':' + id);
            }
            return new ResourceIdentity(type, id, identity.revision(), identity.mutationId(), true,
                identity.payloadHash());
        }
        if (!(state instanceof AssetTransactionCoordinator.Live live)) {
            throw new IllegalStateException("Resource coordinator state is invalid: " + type + ':' + id);
        }
        Path file = snapshot.path(key)
            .orElseThrow(() -> new IllegalStateException("Live coordinated resource has no path: " + type + ':' + id));
        if (!live.hash().equals(StorageSafety.sha256(Files.readAllBytes(file)))) {
            throw new IllegalStateException("Resource payload diverged from the shared asset coordinator: " + type + ':' + id);
        }
        CachedResourceIdentity identity = resourceIdentity(type, id, snapshot, file)
            .orElseThrow(() -> new IllegalStateException("Resource identity diverged from the shared asset coordinator: "
                + type + ':' + id));
        String payloadHash = canonicalResourcePayloadHash(type, StorageSafety.readUtf8(file));
        return new ResourceIdentity(type, id, identity.revision(), identity.mutationId(), false, payloadHash);
    }

    public synchronized ResourceIdentity readResourceIdentity(String type, String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            if (!TYPED_RESOURCE_TYPES.contains(type)) {
                throw new IllegalArgumentException("Unsupported typed resource identity: " + type);
            }
            String safeId = safeId(id, "read resource identity");
            if (safeId == null) {
                throw new IllegalArgumentException("Invalid resource identity");
            }
            try {
                AssetTransactionCoordinator coordinator = requireAssetTransactions();
                AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
                return typedResourceIdentity(snapshot, type, safeId);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read resource identity: " + type + ':' + safeId, exception);
            }
        }
    }

    public synchronized Map<String, FlowGraph> getGraphCache() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            Map<String, FlowGraph> snapshot = new LinkedHashMap<>();
            graphCache.forEach((key, graph) -> snapshot.put(key, graph.copy()));
            return Map.copyOf(snapshot);
        }
    }

    public synchronized String getProjectMetadata(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            if (!isProjectMetadataId(id)) {
                return null;
            }
            String resourceId = projectMetadataResourceId();
            AssetTransactionCoordinator.Snapshot snapshot = requireAssetTransactions().read(Function.identity());
            ResourceIdentity identity = projectMetadataIdentity(snapshot, resourceId);
            return identity == null || identity.deleted() ? null
                : canonicalProjectMetadataJson(snapshot.metadata().serializedJson());
        }
    }

    public synchronized void saveProjectMetadata(String json) {
        ResourceIdentity current = readProjectMetadataIdentity(projectMetadataResourceId());
        saveProjectMetadata(json, UUID.randomUUID(), current == null ? 0L : current.revision());
    }

    public synchronized void saveProjectMetadata(String json, UUID mutationId, long expectedRevision) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            long started = TemporaryLifecycleDiagnostics.start();
            requireResourceMutationRequest(mutationId, expectedRevision);
            String resourceId = projectMetadataResourceId();
            Map<String, Object> diagnosticIdentity = TemporaryLifecycleDiagnostics.identity(serverId,
                PROJECT_METADATA_TYPE + ":" + resourceId, mutationId, null, null, expectedRevision, null,
                persistenceGeneration);
            try {
                long normalizeStarted = TemporaryLifecycleDiagnostics.start();
                String normalized = normalizeProjectMetadata(json);
                TemporaryLifecycleDiagnostics.event("project_metadata_normalize", normalizeStarted,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "outcome", "complete"));
                AssetTransactionCoordinator coordinator = requireAssetTransactions();
                long snapshotStarted = TemporaryLifecycleDiagnostics.start();
                AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
                TemporaryLifecycleDiagnostics.event("asset_coordinator_snapshot", snapshotStarted,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "outcome", "read",
                        "coordinatorSequence", snapshot.rootSequence(), "resourceCount", snapshot.states().size()));
                String payloadHash = canonicalProjectMetadataPayloadHash(
                    projectMetadataAfter(snapshot, projectDeltas(snapshot, normalized)));
                ResourceIdentity current = projectMetadataIdentity(snapshot, resourceId);
                long currentRevision = current == null ? 0L : current.revision();
                long revision = Math.addExact(expectedRevision, 1L);
                if (current != null && mutationId.toString().equals(current.mutationId())) {
                    if (current.deleted() || current.revision() != revision || !payloadHash.equals(current.payloadHash())) {
                        throw new IllegalStateException("Mutation ID was already committed with different project metadata state: " + mutationId);
                    }
                    TemporaryLifecycleDiagnostics.event("project_metadata_save", started,
                        TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "outcome", "replayed",
                            "resultRevision", current.revision()));
                    return;
                }
                if (currentRevision != expectedRevision) {
                    throw new ResourceRevisionConflictException(resourceId, expectedRevision, currentRevision);
                }
                String lineage = projectMetadataLineage(resourceId, revision, mutationId, payloadHash, false);
                transactAssets(coordinator, snapshot, mutationId,
                    List.of(AssetMutation.write(projectMetadataLineageKey(), projectMetadataLineageFile(),
                        lineage.getBytes(StandardCharsets.UTF_8))), normalized);
                requireProjectMetadataIdentity(resourceId, revision, mutationId, payloadHash, false);
                TemporaryLifecycleDiagnostics.event("project_metadata_save", started,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "outcome", "committed", "resultRevision", revision));
            } catch (IOException e) {
                TemporaryLifecycleDiagnostics.event("project_metadata_save", started,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "outcome", "failed",
                        "failure", e.getClass().getSimpleName()));
                throw new IllegalStateException("Failed to save project metadata: " + resourceId, e);
            }
        }
    }

    public synchronized void deleteProjectMetadata(String id) {
        ResourceIdentity current = readProjectMetadataIdentity(projectMetadataResourceId());
        deleteProjectMetadata(id, UUID.randomUUID(), current == null ? 0L : current.revision());
    }

    public synchronized void deleteProjectMetadata(String id, UUID mutationId, long expectedRevision) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            requireResourceMutationRequest(mutationId, expectedRevision);
            if (!isProjectMetadataId(id)) {
                throw new IllegalArgumentException("Unknown project metadata ID: " + id);
            }
            String resourceId = projectMetadataResourceId();
            try {
                AssetTransactionCoordinator coordinator = requireAssetTransactions();
                AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
                ResourceIdentity current = projectMetadataIdentity(snapshot, resourceId);
                long currentRevision = current == null ? 0L : current.revision();
                long revision = Math.addExact(expectedRevision, 1L);
                if (current != null && mutationId.toString().equals(current.mutationId())) {
                    if (!current.deleted() || current.revision() != revision) {
                        throw new IllegalStateException("Mutation ID was already committed with different project metadata state: " + mutationId);
                    }
                    return;
                }
                if (current == null || current.deleted() || currentRevision != expectedRevision) {
                    throw new ResourceRevisionConflictException(resourceId, expectedRevision, currentRevision);
                }
                String lineage = projectMetadataLineage(resourceId, revision, mutationId, current.payloadHash(), true);
                transactAssets(coordinator, snapshot, mutationId,
                    List.of(AssetMutation.write(projectMetadataLineageKey(), projectMetadataLineageFile(),
                        lineage.getBytes(StandardCharsets.UTF_8))), "{}");
                requireProjectMetadataIdentity(resourceId, revision, mutationId, current.payloadHash(), true);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to delete project metadata: " + resourceId, e);
            }
        }
    }

    public synchronized List<String> listProjectMetadataIds() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String resourceId = projectMetadataResourceId();
            ResourceIdentity identity = projectMetadataIdentity(requireAssetTransactions().read(Function.identity()), resourceId);
            return identity != null && !identity.deleted() ? List.of(resourceId) : List.of();
        }
    }

    public synchronized ResourceIdentity readProjectMetadataIdentity(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String resourceId = projectMetadataResourceId();
            if (!isProjectMetadataId(id)) {
                return null;
            }
            return projectMetadataIdentity(requireAssetTransactions().read(Function.identity()), resourceId);
        }
    }

    public synchronized ResourceIdentity recoverProjectMetadataLineage(UUID sourceMutationId, String sourceType,
                                                                        String sourceId, long sourceRevision,
                                                                        String sourceHash,
                                                                        long previousMetadataRevision,
                                                                        UUID previousMetadataMutationId,
                                                                        String previousMetadataHash) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            Objects.requireNonNull(sourceMutationId, "sourceMutationId");
            Objects.requireNonNull(previousMetadataMutationId, "previousMetadataMutationId");
            if (sourceType == null || sourceType.isBlank() || sourceId == null || sourceId.isBlank()
                || sourceRevision < 1L || !isCanonicalPayloadHash(sourceHash)
                || previousMetadataRevision < 1L || !isCanonicalPayloadHash(previousMetadataHash)) {
                throw new IllegalArgumentException("Project metadata lineage recovery identity is invalid");
            }
            try {
                AssetTransactionCoordinator coordinator = requireAssetTransactions();
                AssetTransactionCoordinator.MutationView source = coordinator.mutation(sourceMutationId)
                    .orElseThrow(() -> new IOException("Source resource mutation is absent from coordinator history"));
                AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
                return recoverProjectMetadataLineage(coordinator, snapshot, source, sourceType, sourceId,
                    sourceRevision, sourceHash, previousMetadataRevision, previousMetadataMutationId,
                    previousMetadataHash);
            } catch (IOException | ArithmeticException exception) {
                throw new IllegalStateException("Failed to recover project metadata lineage", exception);
            }
        }
    }

    public synchronized ResourceIdentity recoverUnreceiptedProjectMetadataLineage(long previousMetadataRevision,
                                                                                     UUID previousMetadataMutationId,
                                                                                     String previousMetadataHash) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            Objects.requireNonNull(previousMetadataMutationId, "previousMetadataMutationId");
            if (previousMetadataRevision < 1L || !isCanonicalPayloadHash(previousMetadataHash)) {
                throw new IllegalArgumentException("Project metadata lineage recovery baseline is invalid");
            }
            try {
                AssetTransactionCoordinator coordinator = requireAssetTransactions();
                AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
                UUID sourceMutationId = Optional.ofNullable(snapshot.projectLineage())
                    .flatMap(AssetTransactionCoordinator.AssetMutationId::uuid)
                    .orElseThrow(() -> new IOException("Current project metadata transition has no runtime mutation identity"));
                AssetTransactionCoordinator.MutationView source = coordinator.mutation(sourceMutationId)
                    .orElseThrow(() -> new IOException("Current project metadata transition is absent from coordinator history"));
                JsonObject intent = source.intent();
                if (!intent.has("scope") || !intent.get("scope").isJsonObject()) {
                    return null;
                }
                JsonObject scope = intent.getAsJsonObject("scope");
                if (!scope.has("action") || !scope.get("action").isJsonPrimitive()
                    || (!"worldGenProjectSave".equals(scope.get("action").getAsString())
                    && !"worldGenProjectDelete".equals(scope.get("action").getAsString()))) {
                    return null;
                }
                if ("worldGenProjectDelete".equals(scope.get("action").getAsString())) {
                    return recoverUnreceiptedWorldGenDeleteLineage(coordinator, snapshot, source, scope,
                        previousMetadataRevision, previousMetadataMutationId, previousMetadataHash);
                }
                String action = stringValue(scope, "action", "Current project metadata transition action is invalid");
                String sourceId = stringValue(scope, "resourceId", "Current project metadata transition resource is invalid");
                String scopeMutationId = stringValue(scope, "mutationId", "Current project metadata transition mutation is invalid");
                long expectedRevision = longValue(scope, "expectedRevision",
                    "Current project metadata transition expected revision is invalid");
                if (!"worldGenProjectSave".equals(action) || sourceId.isBlank()
                    || !sourceMutationId.toString().equals(scopeMutationId) || expectedRevision < 0L) {
                    throw new IOException("Current project metadata transition is not an exact WorldGen save");
                }
                String sourceType = ReSyncResourceCatalog.WORLDGEN;
                AssetTransactionCoordinator.AssetKey sourceKey = new AssetTransactionCoordinator.AssetKey(sourceType, sourceId);
                AssetTransactionCoordinator.AssetKey intentKey = new AssetTransactionCoordinator.AssetKey(sourceType + ".intent", sourceId);
                AssetTransactionCoordinator.AssetKey lineageKey = projectMetadataLineageKey();
                AssetTransactionCoordinator.ExpectedState state = source.result().states().get(sourceKey);
                Set<AssetTransactionCoordinator.AssetKey> resultKeys = source.result().states().keySet();
                boolean coupledLineage = resultKeys.equals(Set.of(sourceKey, intentKey, lineageKey));
                if (!(state instanceof AssetTransactionCoordinator.Live live)
                    || live.revision() != Math.addExact(expectedRevision, 1L)
                    || (!resultKeys.equals(Set.of(sourceKey, intentKey)) && !coupledLineage)) {
                    throw new IOException("Current WorldGen project metadata transition does not own one exact resource save");
                }
                Path sourcePath = snapshot.path(sourceKey)
                    .orElseThrow(() -> new IOException("Current WorldGen project metadata transition has no resource path"));
                String sourceHash = projectMetadataRecoverySourceHash(sourcePath);
                requireUnreceiptedWorldGenScope(snapshot, source, scope, sourceKey, intentKey, sourcePath,
                    sourceId, sourceMutationId, expectedRevision, sourceHash, coupledLineage);
                if (coupledLineage) {
                    requireProjectMetadataLineageBaseline(source, lineageKey, previousMetadataRevision,
                        previousMetadataMutationId, previousMetadataHash);
                    ResourceIdentity current = projectMetadataIdentity(snapshot, projectMetadataResourceId());
                    long revision = Math.addExact(previousMetadataRevision, 1L);
                    String payloadHash = canonicalProjectMetadataPayloadHash(
                        canonicalProjectMetadataJson(snapshot.metadata().serializedJson()));
                    AssetTransactionCoordinator.ExpectedState lineageState = snapshot.state(lineageKey)
                        .orElseThrow(() -> new IOException("Current WorldGen transition has no project metadata lineage"));
                    if (!lineageState.equals(source.result().states().get(lineageKey))
                        || !sourceMutationId.toString().equals(snapshot.mutationValue(lineageKey).orElse(""))
                        || current == null || current.deleted() || current.revision() != revision
                        || !current.mutationId().equals(sourceMutationId.toString())
                        || !current.payloadHash().equals(payloadHash)) {
                        throw new IOException("Current WorldGen project metadata lineage is not authoritative");
                    }
                    return current;
                }
                return recoverProjectMetadataLineage(coordinator, snapshot, source, sourceType, sourceId,
                    live.revision(), sourceHash, previousMetadataRevision, previousMetadataMutationId,
                    previousMetadataHash);
            } catch (IOException | ArithmeticException exception) {
                throw new IllegalStateException("Failed to recover unreceipted project metadata lineage", exception);
            }
        }
    }

    private ResourceIdentity recoverUnreceiptedWorldGenDeleteLineage(
        AssetTransactionCoordinator coordinator, AssetTransactionCoordinator.Snapshot snapshot,
        AssetTransactionCoordinator.MutationView source, JsonObject scope, long previousMetadataRevision,
        UUID previousMetadataMutationId, String previousMetadataHash) throws IOException {
        String sourceId = stringValue(scope, "resourceId", "Current WorldGen delete resource is invalid");
        String scopeMutationId = stringValue(scope, "mutationId", "Current WorldGen delete mutation is invalid");
        long expectedRevision = longValue(scope, "expectedRevision",
            "Current WorldGen delete expected revision is invalid");
        UUID sourceMutationId = source.mutationId();
        if (!"worldGenProjectDelete".equals(stringValue(scope, "action", "Current WorldGen delete action is invalid"))
            || sourceId.isBlank() || !sourceMutationId.toString().equals(scopeMutationId) || expectedRevision < 0L) {
            throw new IOException("Current project metadata transition is not an exact WorldGen delete");
        }
        AssetTransactionCoordinator.AssetKey sourceKey = new AssetTransactionCoordinator.AssetKey(
            ReSyncResourceCatalog.WORLDGEN, sourceId);
        AssetTransactionCoordinator.AssetKey tombstoneKey = new AssetTransactionCoordinator.AssetKey(
            ReSyncResourceCatalog.WORLDGEN + ".tombstone", sourceId);
        AssetTransactionCoordinator.AssetKey intentKey = new AssetTransactionCoordinator.AssetKey(
            ReSyncResourceCatalog.WORLDGEN + ".intent", sourceId);
        AssetTransactionCoordinator.AssetKey lineageKey = projectMetadataLineageKey();
        Set<AssetTransactionCoordinator.AssetKey> expectedKeys = Set.of(sourceKey, tombstoneKey, intentKey, lineageKey);
        AssetTransactionCoordinator.ExpectedState sourceState = source.result().states().get(sourceKey);
        if (!(sourceState instanceof AssetTransactionCoordinator.Deleted deleted)
            || deleted.revision() != Math.addExact(expectedRevision, 1L)
            || !source.result().states().keySet().equals(expectedKeys)) {
            throw new IOException("Current WorldGen project metadata transition does not own one exact resource delete");
        }
        requireUnreceiptedWorldGenDeleteScope(snapshot, source, scope, sourceKey, tombstoneKey, intentKey,
            lineageKey, sourceId, sourceMutationId, expectedRevision);
        if (!source.result().project().equals(snapshot.project())) {
            throw new IOException("Current WorldGen delete is not the current project metadata transition");
        }
        JsonArray projectDeltas = array(source.intent(), "projectDeltas",
            "Current WorldGen delete project delta is invalid");
        JsonElement resources = snapshot.metadata().document().get("resources");
        if (projectDeltas.size() != 1 || !projectDeltas.get(0).isJsonObject()
            || resources == null || !resources.isJsonArray()) {
            throw new IOException("Current WorldGen delete does not own one exact project metadata delta");
        }
        JsonObject projectDelta = projectDeltas.get(0).getAsJsonObject();
        JsonArray projectPath = array(projectDelta, "path", "Current WorldGen delete project delta path is invalid");
        if (projectPath.size() != 1 || !"resources".equals(projectPath.get(0).getAsString())
            || !"SET".equals(stringValue(projectDelta, "operation",
                "Current WorldGen delete project delta operation is invalid"))
            || !resources.equals(projectDelta.get("value"))
            || resources.getAsJsonArray().asList().stream().anyMatch(element -> element.isJsonObject()
                && ReSyncResourceCatalog.WORLDGEN.equals(element.getAsJsonObject().get("type").getAsString())
                && sourceId.equals(element.getAsJsonObject().get("id").getAsString()))) {
            throw new IOException("Current WorldGen delete project delta does not remove its resource");
        }
        for (AssetTransactionCoordinator.AssetKey key : expectedKeys) {
            if (!source.result().states().get(key).equals(snapshot.state(key).orElse(null))
                || !sourceMutationId.toString().equals(snapshot.mutationValue(key).orElse(""))) {
                throw new IOException("Current WorldGen delete coordinator lineage is not authoritative");
            }
        }
        String expectedDeletedHash = StorageSafety.sha256("deleted\n" + sourceKey.canonical() + "\n"
            + deleted.revision() + "\n" + sourceMutationId);
        if (!expectedDeletedHash.equals(deleted.hash())) {
            throw new IOException("Current WorldGen delete tombstone lineage is invalid");
        }
        Path sourcePath = snapshot.path(sourceKey)
            .orElseThrow(() -> new IOException("Current WorldGen delete has no source path"));
        if (Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Current WorldGen delete left a live source asset");
        }
        Path tombstonePath = snapshot.path(tombstoneKey)
            .orElseThrow(() -> new IOException("Current WorldGen delete has no tombstone path"));
        AssetTransactionCoordinator.ExpectedState tombstoneState = snapshot.state(tombstoneKey).orElse(null);
        if (!(tombstoneState instanceof AssetTransactionCoordinator.Live tombstoneLive)
            || !Files.isRegularFile(tombstonePath, LinkOption.NOFOLLOW_LINKS)
            || !tombstoneLive.hash().equals(StorageSafety.sha256(Files.readAllBytes(tombstonePath)))) {
            throw new IOException("Current WorldGen delete tombstone bytes are not authoritative");
        }
        GraphIdentity tombstone = readGraphTombstoneIdentity(ReSyncResourceCatalog.WORLDGEN, sourceId, tombstonePath);
        if (tombstone == null || tombstone.revision() != deleted.revision()
            || !sourceMutationId.toString().equals(tombstone.mutationId())) {
            throw new IOException("Current WorldGen delete tombstone identity is invalid");
        }
        Path intentPath = snapshot.path(intentKey)
            .orElseThrow(() -> new IOException("Current WorldGen delete has no semantic intent path"));
        AssetTransactionCoordinator.ExpectedState intentState = snapshot.state(intentKey).orElse(null);
        if (!(intentState instanceof AssetTransactionCoordinator.Live intentLive)
            || !Files.isRegularFile(intentPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Current WorldGen delete semantic intent is not authoritative");
        }
        byte[] intentBytes = Files.readAllBytes(intentPath);
        if (!intentLive.hash().equals(StorageSafety.sha256(intentBytes))) {
            throw new IOException("Current WorldGen delete semantic intent bytes are not authoritative");
        }
        JsonObject semanticIntent;
        try {
            semanticIntent = JsonParser.parseString(new String(intentBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Current WorldGen delete semantic intent is invalid", exception);
        }
        if (!semanticIntent.keySet().equals(Set.of("operation", "type"))
            || !"delete".equals(stringValue(semanticIntent, "operation",
                "Current WorldGen delete semantic operation is invalid"))
            || !ReSyncResourceCatalog.WORLDGEN.equals(stringValue(semanticIntent, "type",
                "Current WorldGen delete semantic type is invalid"))) {
            throw new IOException("Current WorldGen delete semantic intent identity is invalid");
        }
        requireProjectMetadataLineageBaseline(source, lineageKey, previousMetadataRevision,
            previousMetadataMutationId, previousMetadataHash);
        ResourceIdentity current = projectMetadataIdentity(snapshot, projectMetadataResourceId());
        long revision = Math.addExact(previousMetadataRevision, 1L);
        String payloadHash = canonicalProjectMetadataPayloadHash(
            canonicalProjectMetadataJson(snapshot.metadata().serializedJson()));
        if (current == null || current.deleted() || current.revision() != revision
            || !current.mutationId().equals(sourceMutationId.toString()) || !current.payloadHash().equals(payloadHash)) {
            throw new IOException("Current WorldGen delete project metadata lineage is not authoritative");
        }
        return current;
    }

    private void requireProjectMetadataLineageBaseline(AssetTransactionCoordinator.MutationView source,
                                                       AssetTransactionCoordinator.AssetKey lineageKey,
                                                       long previousMetadataRevision,
                                                       UUID previousMetadataMutationId,
                                                       String previousMetadataHash) throws IOException {
        JsonArray assets = array(source.intent(), "assets", "Current project metadata transition assets are invalid");
        List<JsonObject> matches = assets.asList().stream().filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .filter(asset -> lineageKey.type().equals(asset.has("type") ? asset.get("type").getAsString() : "")
                && lineageKey.id().equals(asset.has("id") ? asset.get("id").getAsString() : ""))
            .toList();
        if (matches.size() != 1) {
            throw new IOException("Current project metadata transition has no exact lineage asset");
        }
        JsonObject asset = matches.getFirst();
        JsonObject expected = object(asset, "expected", "Current project metadata lineage baseline is invalid");
        AssetTransactionCoordinator.ExpectedState resultState = source.result().states().get(lineageKey);
        String expectedHash = StorageSafety.sha256(projectMetadataLineage(projectMetadataResourceId(),
            previousMetadataRevision, previousMetadataMutationId, previousMetadataHash, false)
            .getBytes(StandardCharsets.UTF_8));
        Path lineagePath = projectMetadataLineageFile();
        if (!asset.keySet().equals(Set.of("expected", "id", "key", "operation", "path", "payloadHash", "payloadSize", "type"))
            || !expected.keySet().equals(Set.of("hash", "kind", "revision"))
            || !"WRITE".equals(stringValue(asset, "operation", "Current project metadata lineage operation is invalid"))
            || !lineageKey.canonical().equals(stringValue(asset, "key", "Current project metadata lineage key is invalid"))
            || !"LIVE".equals(stringValue(expected, "kind", "Current project metadata lineage baseline kind is invalid"))
            || longValue(expected, "revision", "Current project metadata lineage baseline revision is invalid") < 1L
            || !expectedHash.equals(stringValue(expected, "hash", "Current project metadata lineage baseline hash is invalid"))
            || !assetsDir.toPath().relativize(lineagePath).toString().replace('\\', '/').equals(
                stringValue(asset, "path", "Current project metadata lineage path is invalid"))
            || !(resultState instanceof AssetTransactionCoordinator.Live live)
            || !live.hash().equals(stringValue(asset, "payloadHash", "Current project metadata lineage payload hash is invalid"))
            || longValue(asset, "payloadSize", "Current project metadata lineage payload size is invalid") != Files.size(lineagePath)) {
            throw new IOException("Current project metadata transition does not extend the exact durable lineage baseline");
        }
    }

    private void requireUnreceiptedWorldGenDeleteScope(AssetTransactionCoordinator.Snapshot snapshot,
                                                       AssetTransactionCoordinator.MutationView source,
                                                       JsonObject scope,
                                                       AssetTransactionCoordinator.AssetKey sourceKey,
                                                       AssetTransactionCoordinator.AssetKey tombstoneKey,
                                                       AssetTransactionCoordinator.AssetKey intentKey,
                                                       AssetTransactionCoordinator.AssetKey lineageKey,
                                                       String sourceId, UUID sourceMutationId,
                                                       long expectedRevision) throws IOException {
        Set<String> fields = Set.of("actorClientId", "requestId", "mutationId", "operationId", "action",
            "resourceId", "expectedRevision", "payload", "worldGenIntentHash");
        if (!scope.keySet().equals(fields)) {
            throw new IOException("Current WorldGen delete scope is incomplete");
        }
        String actorClientId = stringValue(scope, "actorClientId", "Current WorldGen delete actor is invalid");
        String requestId = stringValue(scope, "requestId", "Current WorldGen delete request is invalid");
        String operationId = stringValue(scope, "operationId", "Current WorldGen delete operation is invalid");
        String intentHash = stringValue(scope, "worldGenIntentHash", "Current WorldGen delete intent hash is invalid");
        JsonObject payload = object(scope, "payload", "Current WorldGen delete payload is invalid");
        if (actorClientId.isBlank() || !requestId.equals(operationId)
            || !isCanonicalWorldGenOperationId(requestId, "worldGenProjectDelete", sourceId)
            || !isCanonicalPayloadHash(intentHash) || !payload.keySet().equals(Set.of("projectId"))
            || !sourceId.equals(stringValue(payload, "projectId", "Current WorldGen delete project ID is invalid"))) {
            throw new IOException("Current WorldGen delete scope identity is invalid");
        }
        Map<String, Object> hashInput = new LinkedHashMap<>();
        hashInput.put("actorClientId", actorClientId);
        hashInput.put("requestId", requestId);
        hashInput.put("mutationId", sourceMutationId.toString());
        hashInput.put("operationId", operationId);
        hashInput.put("action", "worldGenProjectDelete");
        hashInput.put("resourceId", sourceId);
        hashInput.put("expectedRevision", expectedRevision);
        hashInput.put("data", CanonicalJson.parseOpaque(payload.toString()));
        if (!intentHash.equals(CanonicalJson.sha256("worldgen-mutation", hashInput))) {
            throw new IOException("Current WorldGen delete scope intent hash is invalid");
        }
        JsonObject sourceIntent = source.intent();
        if (!sourceIntent.has("scope") || !scope.equals(sourceIntent.getAsJsonObject("scope"))) {
            throw new IOException("Current WorldGen delete scope is not durably bound");
        }
        if (!source.result().states().keySet().equals(Set.of(sourceKey, tombstoneKey, intentKey, lineageKey))) {
            throw new IOException("Current WorldGen delete result assets are not exact");
        }
    }

    private boolean isCanonicalWorldGenOperationId(String operationId, String action, String sourceId) {
        String prefix = action + ":" + sourceId + ":";
        if (operationId == null || !operationId.startsWith(prefix)) {
            return false;
        }
        String suffix = operationId.substring(prefix.length());
        try {
            return UUID.fromString(suffix).toString().equals(suffix);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private ResourceIdentity recoverProjectMetadataLineage(AssetTransactionCoordinator coordinator,
                                                            AssetTransactionCoordinator.Snapshot snapshot,
                                                            AssetTransactionCoordinator.MutationView source,
                                                            String sourceType, String sourceId, long sourceRevision,
                                                            String sourceHash, long previousMetadataRevision,
                                                            UUID previousMetadataMutationId,
                                                            String previousMetadataHash) throws IOException {
        String resourceId = projectMetadataResourceId();
        String payloadHash = canonicalProjectMetadataPayloadHash(
            canonicalProjectMetadataJson(snapshot.metadata().serializedJson()));
        ResourceIdentity committed = committedProjectMetadataLineage(coordinator, snapshot, source, sourceType,
            sourceId, sourceRevision, sourceHash, previousMetadataRevision, previousMetadataMutationId,
            previousMetadataHash, payloadHash);
        if (committed != null) {
            return committed;
        }
        UUID repairMutationId = projectMetadataLineageRepairMutationId(source.mutationId());
        ResourceIdentity replay = projectMetadataLineageRepairReplay(coordinator, snapshot, source,
            repairMutationId, sourceType, sourceId, sourceRevision, sourceHash,
            previousMetadataRevision, previousMetadataMutationId, previousMetadataHash, payloadHash);
        if (replay != null) {
            return replay;
        }
        requireProjectMetadataLineageRecoverySource(coordinator, snapshot, source, sourceType, sourceId,
            sourceRevision, sourceHash, previousMetadataRevision, previousMetadataMutationId,
            previousMetadataHash);
        long revision = Math.addExact(previousMetadataRevision, 1L);
        String lineage = projectMetadataLineage(resourceId, revision, repairMutationId, payloadHash, false);
        AssetTransactionCoordinator.AssetKey lineageKey = projectMetadataLineageKey();
        AssetTransactionCoordinator.ExpectedState expected = snapshot.state(lineageKey)
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        JsonObject scope = projectMetadataLineageRepairScope(source, sourceType, sourceId, sourceRevision,
            sourceHash, previousMetadataRevision, previousMetadataMutationId, previousMetadataHash,
            snapshot.project());
        AssetTransactionCoordinator.TransactionRequest request = new AssetTransactionCoordinator.TransactionRequest(
            repairMutationId, snapshot.project(), List.of(AssetTransactionCoordinator.AssetDelta.write(
            lineageKey, projectMetadataLineageFile(), expected, lineage.getBytes(StandardCharsets.UTF_8))), List.of());
        try {
            coordinator.withTransactionIntentScope(scope, () -> {
                try {
                    return coordinator.transact(request);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        AssetTransactionCoordinator.Snapshot repairedSnapshot = coordinator.read(Function.identity());
        ResourceIdentity repaired = projectMetadataLineageRepairReplay(coordinator, repairedSnapshot, source,
            repairMutationId, sourceType, sourceId, sourceRevision, sourceHash,
            previousMetadataRevision, previousMetadataMutationId, previousMetadataHash, payloadHash);
        if (repaired == null) {
            throw new IOException("Project metadata lineage repair was not durably published");
        }
        return repaired;
    }

    private ResourceIdentity committedProjectMetadataLineage(AssetTransactionCoordinator coordinator,
                                                              AssetTransactionCoordinator.Snapshot snapshot,
                                                              AssetTransactionCoordinator.MutationView source,
                                                              String sourceType, String sourceId,
                                                              long sourceRevision, String sourceHash,
                                                              long previousMetadataRevision,
                                                              UUID previousMetadataMutationId,
                                                              String previousMetadataHash,
                                                              String payloadHash) throws IOException {
        ResourceIdentity current = projectMetadataIdentity(snapshot, projectMetadataResourceId());
        long revision = Math.addExact(previousMetadataRevision, 1L);
        if (current == null || current.deleted() || current.revision() != revision
            || !current.mutationId().equals(source.mutationId().toString())
            || !current.payloadHash().equals(payloadHash)) {
            return null;
        }
        requireProjectMetadataLineageRecoverySourceMutation(coordinator, snapshot, source, sourceType, sourceId,
            sourceRevision, sourceHash, true);
        requireProjectMetadataLineageBaseline(source, projectMetadataLineageKey(), previousMetadataRevision,
            previousMetadataMutationId, previousMetadataHash);
        return current;
    }

    public String projectMetadataResourceId() {
        return serverId != null ? serverId.canonicalText() : "project";
    }

    public String normalizeProjectMetadataPayload(String json) {
        long started = TemporaryLifecycleDiagnostics.start();
        try {
            String normalized = normalizeProjectMetadata(json);
            TemporaryLifecycleDiagnostics.event("project_metadata_codec", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                    PROJECT_METADATA_TYPE + ":" + projectMetadataResourceId(), null, null, null, null, null,
                    persistenceGeneration), "outcome", "complete", "inputChars", json == null ? 0 : json.length(),
                    "outputChars", normalized.length(), "storageTraversal", false));
            return normalized;
        } catch (IOException exception) {
            TemporaryLifecycleDiagnostics.event("project_metadata_codec", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                    PROJECT_METADATA_TYPE + ":" + projectMetadataResourceId(), null, null, null, null, null,
                    persistenceGeneration), "outcome", "failed", "inputChars", json == null ? 0 : json.length(),
                    "failure", exception.getClass().getSimpleName(), "storageTraversal", false));
            throw new IllegalArgumentException("Flow project metadata is invalid", exception);
        }
    }

    public synchronized ProjectMetadataReconciliation reconcileProjectMetadataAssets() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            AssetTransactionCoordinator coordinator = requireAssetTransactions();
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
            ResourceIdentity identity = projectMetadataIdentity(snapshot, projectMetadataResourceId());
            if (identity == null || identity.deleted()) {
                return new ProjectMetadataReconciliation(Set.of(), Set.of(), Set.of(), 0, false);
            }
            ProjectMetadataSnapshot metadata = parseProjectMetadata(canonicalProjectMetadataJson(
                snapshot.metadata().serializedJson()));
            if (metadata == null) {
                throw new IllegalStateException("Flow project metadata could not be decoded");
            }
            ProjectMetadataReconciliation reconciliation = reconcileMissingAssetResources(metadata, snapshot);
            if (reconciliation.changed()) {
                saveProjectMetadata(serializeProjectMetadata(metadata), UUID.randomUUID(), identity.revision());
            }
            return reconciliation;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reconcile Flow project metadata", exception);
        }
    }

    private boolean isProjectMetadataId(String id) {
        String resourceId = projectMetadataResourceId();
        return id == null || id.isBlank() || "project".equals(id) || resourceId.equals(id);
    }

    public List<String> listFlowIds() {
        return listGraphIds("flow");
    }

    public synchronized List<String> listGraphIds(String resourceType) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            if (!Set.of("flow", "function", "command").contains(resourceType)) {
                throw new IllegalArgumentException("Unknown graph resource type: " + resourceType);
            }
            return requireAssetTransactions().read(snapshot -> listGraphIds(resourceType, preloadAssetFiles(snapshot)));
        }
    }

    private List<String> listGraphIds(String resourceType, Map<String, Path> index) {
        if (!Set.of("flow", "function", "command").contains(resourceType)) {
            throw new IllegalArgumentException("Unknown graph resource type: " + resourceType);
        }
        return listResourceIds(flowDir, resourceType, index).stream()
            .filter(id -> index.containsKey(assetIndexKey(resourceType, id))).toList();
    }

    public synchronized TypedCommandGraphAdapter.Snapshot typedCommandGraphSnapshot() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            Map<String, FlowGraph> graphs = new LinkedHashMap<>();
            Map<String, String> failures = new LinkedHashMap<>();
            for (String graphId : listGraphIds("command")) {
                FlowGraph graph = getCommandGraph(graphId);
                if (graph == null) {
                    failures.put(graphId, "The typed command graph could not be loaded or failed its integrity check");
                } else if (!graphId.equals(graph.getId())) {
                    failures.put(graphId, "The typed command graph ID does not match its typed storage identity");
                } else {
                    graphs.put(graphId, graph);
                }
            }
            return TypedCommandGraphAdapter.index(graphs, failures);
        }
    }

    public TypedCommandGraphAdapter.Snapshot getTypedCommandGraphSnapshot() {
        return typedCommandGraphSnapshot();
    }

    public List<TypedCommandGraphAdapter.CommandBinding> typedCommandBindings() {
        return typedCommandGraphSnapshot().activeBindings();
    }

    public List<TypedCommandGraphAdapter.CommandBinding> getTypedCommandBindings() {
        return typedCommandBindings();
    }

    public synchronized String getGraphResourceType(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "resolve graph resource type");
            if (safeId == null) {
                return "";
            }
            return resolveStoredGraphType(safeId);
        }
    }

    public String graphResourceType(FlowGraph graph) {
        if (graph == null) {
            return "";
        }
        if (GRAPH_TYPES.contains(graph.getResourceType())) {
            return graph.getResourceType();
        }
        if (graphHasCommandStartNode(graph)) {
            return "command";
        }
        return graph.isFunction() ? "function" : "flow";
    }

    public boolean isExecutionAuthorized(FlowGraph graph) {
        if (graph == null) {
            return true;
        }
        String type = graphResourceType(graph);
        if (!GRAPH_TYPES.contains(type)) {
            return true;
        }
        String id = graph.getId();
        boolean storedIdentity = graph.getResourceRevision() > 0L || !graph.getResourceHash().isBlank()
            || !graph.getResourceMutationId().isBlank();
        if (id == null || id.isBlank()) {
            return !"command".equals(type) && !storedIdentity;
        }
        FlowGraph current = getGraph(type, id);
        if (current != null) {
            return current.isEnabled();
        }
        return !"command".equals(type) && !storedIdentity;
    }

    public synchronized boolean hasStoredGraphVersion(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "inspect flow version");
            if (safeId == null) {
                return false;
            }
            String type = resolveStoredGraphType(safeId);
            if (type.isBlank()) {
                return false;
            }
            Path file = resourceFile(flowDir, type, safeId, "inspect flow version");
            if (file == null || !Files.exists(file)) {
                return false;
            }
            try {
                String json = StorageSafety.readUtf8(file);
                return json.contains("\"version\"");
            } catch (IOException e) {
                Log.warn("Failed to inspect flow version: " + safeId + " - " + e.getMessage());
            }
            return false;
        }
    }

    public Path backupGraphForMigration(String id, String migrationId) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "backup flow migration");
            String safeMigrationId = safeId(migrationId, "backup flow migration");
            if (safeId == null || safeMigrationId == null) {
                throw new IllegalArgumentException("Invalid migration backup identity");
            }
            String type = resolveStoredGraphType(safeId);
            Path source = resourceFile(flowDir, type.isBlank() ? "flow" : type, safeId, "backup flow migration");
            if (source == null || Files.notExists(source)) {
                throw new IllegalStateException("Flow source is unavailable for migration backup: " + safeId);
            }
            Path backup = assetsDir.toPath().resolve("migration-backups").resolve(safeMigrationId).resolve(safeId + ".json");
            try {
                StorageSafety.writeUtf8Atomic(backup, StorageSafety.readUtf8(source));
                return backup;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to back up flow migration: " + safeId, exception);
            }
        }
    }

    public Path backupGraphForMigration(String type, String id, String migrationId) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "backup graph migration");
            String safeMigrationId = safeId(migrationId, "backup graph migration");
            if (!GRAPH_TYPES.contains(type) || safeId == null || safeMigrationId == null) {
                throw new IllegalArgumentException("Invalid graph migration backup identity");
            }
            Path source = findAssetResourceFile(type, safeId);
            if (source == null || Files.notExists(source)) {
                throw new IllegalStateException("Graph source is unavailable for migration backup: " + type + ':' + safeId);
            }
            Path backup = assetsDir.toPath().resolve("migration-backups").resolve(safeMigrationId).resolve(type).resolve(safeId + ".json");
            try {
                StorageSafety.writeUtf8Atomic(backup, StorageSafety.readUtf8(source));
                return backup;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to back up graph migration: " + type + ':' + safeId, exception);
            }
        }
    }

    public String getTypedAutomationBackupGraphResourceType(String id) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            String safeId = safeId(id, "read flow migration backup");
            Path backupRoot = assetsDir.toPath().resolve("migration-backups");
            if (safeId == null || Files.notExists(backupRoot)) {
                return "";
            }
            try (Stream<Path> migrations = Files.list(backupRoot)) {
                return migrations.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("typed-automation-"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .map(path -> path.resolve(safeId + ".json"))
                    .filter(Files::isRegularFile)
                    .map(AssetFileFormat::readResourceType)
                    .filter(GRAPH_TYPES::contains)
                    .findFirst()
                    .orElse("");
            } catch (IOException exception) {
                Log.warn("Failed to inspect flow migration backups for " + safeId + ": " + exception.getMessage());
                return "";
            }
        }
    }

    public synchronized List<String> listGuiIds() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            return listResourceIds(guiDir, "gui", assetFiles());
        }
    }

    public synchronized Map<String, GuiDefinition> getGuiCache() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            Map<String, GuiDefinition> snapshot = new LinkedHashMap<>();
            guiCache.forEach((key, gui) -> snapshot.put(key, copyGui(gui)));
            return Map.copyOf(snapshot);
        }
    }

    public synchronized List<String> listScoreboardIds() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            return listResourceIds(scoreboardDir, "scoreboard", assetFiles());
        }
    }

    public synchronized Map<String, ScoreboardDefinition> getScoreboardCache() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            Map<String, ScoreboardDefinition> snapshot = new LinkedHashMap<>();
            scoreboardCache.forEach((key, scoreboard) -> snapshot.put(key, copyScoreboard(scoreboard)));
            return Map.copyOf(snapshot);
        }
    }

    public synchronized List<String> listTabIds() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            return listResourceIds(tabDir, "tab", assetFiles());
        }
    }

    public synchronized Map<String, TabDefinition> getTabCache() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            Map<String, TabDefinition> snapshot = new LinkedHashMap<>();
            tabCache.forEach((key, tab) -> snapshot.put(key, copyTab(tab)));
            return Map.copyOf(snapshot);
        }
    }

    public synchronized String getDefaultScoreboardId() {
        return defaultScoreboardId;
    }

    public synchronized boolean isDefaultScoreboardUsePapi() {
        return defaultScoreboardUsePapi;
    }

    public synchronized void setDefaultScoreboard(String scoreboardId, boolean usePapi) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            String normalized = scoreboardId != null ? scoreboardId.trim() : "";
            if (normalized.isBlank()) {
                clearDefaultScoreboard();
                return;
            }
            this.defaultScoreboardId = normalized;
            this.defaultScoreboardUsePapi = usePapi;
            persistDefaultScoreboard();
        }
    }

    public synchronized void clearDefaultScoreboard() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            this.defaultScoreboardId = null;
            this.defaultScoreboardUsePapi = true;
            persistDefaultScoreboard();
        }
    }

    public synchronized String getDefaultTabId() {
        return defaultTabId;
    }

    public synchronized boolean isDefaultTabUsePapi() {
        return defaultTabUsePapi;
    }

    public synchronized void setDefaultTab(String tabId, boolean usePapi) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            String normalized = tabId != null ? tabId.trim() : "";
            if (normalized.isBlank()) {
                clearDefaultTab();
                return;
            }
            this.defaultTabId = normalized;
            this.defaultTabUsePapi = usePapi;
            persistDefaultTab();
        }
    }

    public synchronized void clearDefaultTab() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            this.defaultTabId = null;
            this.defaultTabUsePapi = true;
            persistDefaultTab();
        }
    }

    public int getTabRefreshIntervalTicks() {
        return tabRefreshIntervalTicks;
    }

    public long committedSequence() {
        AssetTransactionCoordinator coordinator = assetTransactions;
        if (coordinator == null) {
            return 0L;
        }
        coordinator.requireOpen();
        return coordinator.committedSequence();
    }

    public synchronized void setTabRefreshIntervalTicks(int ticks) {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            this.tabRefreshIntervalTicks = Math.max(1, ticks);
            persistTabRefreshConfig();
        }
    }

    public synchronized void preloadAll() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            requireAssetTransactions().read(snapshot -> {
                preloadAll(snapshot, preloadAssetFiles(snapshot));
                return null;
            });
        }
    }

    private void preloadAll(AssetTransactionCoordinator.Snapshot snapshot, Map<String, Path> assetIndex) {
        List<String> flowIds = listGraphIds("flow", assetIndex);
        List<String> guiIds = listResourceIds(guiDir, "gui", assetIndex);
        List<String> scoreboardIds = listResourceIds(scoreboardDir, "scoreboard", assetIndex);
        List<String> tabIds = listResourceIds(tabDir, "tab", assetIndex);
        for (String id : flowIds) {
            preloadGraph(id, snapshot, assetIndex);
        }
        for (String id : guiIds) {
            preloadResource("gui", guiDir, id, "load GUI", snapshot, assetIndex, guiCache, guiCacheIdentities,
                FlowSerializer::deserializeGui, this::copyGui,
                (gui, safeId) -> {
                    if (gui != null && (gui.getId() == null || gui.getId().isBlank())) {
                        gui.setId(safeId);
                    }
                });
        }
        for (String id : scoreboardIds) {
            preloadResource("scoreboard", scoreboardDir, id, "load scoreboard", snapshot, assetIndex,
                scoreboardCache, scoreboardCacheIdentities, FlowSerializer::deserializeScoreboard,
                this::copyScoreboard,
                (scoreboard, safeId) -> {
                    if (scoreboard != null && (scoreboard.getId() == null || scoreboard.getId().isBlank())) {
                        scoreboard.setId(safeId);
                    }
                });
        }
        for (String id : tabIds) {
            preloadResource("tab", tabDir, id, "load tab", snapshot, assetIndex, tabCache, tabCacheIdentities,
                FlowSerializer::deserializeTab, this::copyTab,
                (tab, safeId) -> {
                    if (tab != null && (tab.getId() == null || tab.getId().isBlank())) {
                        tab.setId(safeId);
                    }
                });
        }
    }

    private FlowGraph preloadGraph(String id, AssetTransactionCoordinator.Snapshot snapshot,
                                   Map<String, Path> assetIndex) {
        String safeId = safeId(id, "load flow");
        if (safeId == null) {
            return null;
        }
        PreloadResource resource = preloadResourceFile(flowDir, "flow", safeId, "load flow", snapshot, assetIndex);
        if (resource == null || resource.path() == null || !Files.exists(resource.path())) {
            return null;
        }
        Path file = resource.path();
        String actualType = AssetFileFormat.readResourceType(file);
        String coordinatedType = actualType.isBlank() ? "flow" : actualType;
        if (!resource.coordinated()) {
            try {
                requireCoordinatedLiveFile(coordinatedType, safeId, file, snapshot);
            } catch (IOException exception) {
                throw new IllegalStateException("Flow payload diverged from the shared asset coordinator: " + safeId,
                    exception);
            }
        }
        String cacheKey = assetIndexKey(coordinatedType, safeId);
        FlowGraph cached = graphCache.get(cacheKey);
        try {
            if (cached != null && graphCacheIdentityMatches(cached, snapshot, coordinatedType, safeId, file, true)) {
                return cached.copy();
            }
            if (cached != null) {
                graphCache.remove(cacheKey, cached);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Flow payload diverged from the shared asset coordinator: " + safeId,
                exception);
        }
        try {
            CoreGraphStorageBoundary.Decoded core = decodeCoreGraphAsset(file, coordinatedType, safeId);
            FlowGraph graph = core == null ? null : materializeCoreGraph(core, coordinatedType);
            if (core != null && graph == null) {
                Log.warn("Failed to materialize Core graph: " + safeId);
                return null;
            }
            if (graph == null && !verifyGraphAsset(file, coordinatedType, safeId)) {
                Log.warn("Flow integrity check failed: " + safeId);
                return null;
            }
            if (graph == null) {
                graph = FlowSerializer.deserialize(StorageSafety.readUtf8(file));
                applyResourceIdentity(graph, file);
            }
            if (!graphCacheIdentityMatches(graph, snapshot, coordinatedType, safeId, file, true)) {
                Log.warn("Skipped caching flow without a matching coordinator identity: " + safeId);
                return graph.copy();
            }
            graphCache.put(cacheKey, graph);
            return graph.copy();
        } catch (IOException | RuntimeException exception) {
            Log.warn("Failed to load flow: " + safeId + " - " + exception.getMessage());
            return null;
        }
    }

    private <T> T preloadResource(String type, File legacyDirectory, String id, String action,
                                   AssetTransactionCoordinator.Snapshot snapshot, Map<String, Path> assetIndex,
                                   Map<String, T> cache,
                                   Map<String, CachedResourceIdentity> identities, Function<String, T> deserialize,
                                   Function<T, T> copy, BiConsumer<T, String> assignId) {
        String safeId = safeId(id, action);
        if (safeId == null) {
            return null;
        }
        PreloadResource resource = preloadResourceFile(legacyDirectory, type, safeId, action, snapshot, assetIndex);
        T cached = cache.get(safeId);
        if (cached != null && resource != null && resource.coordinated()
            && cachedResourceIdentityMatches(type, safeId, identities.get(safeId), snapshot, resource.path())) {
            return copy.apply(cached);
        }
        if (cached != null) {
            cache.remove(safeId, cached);
            identities.remove(safeId);
        }
        if (resource == null || resource.path() == null || !Files.exists(resource.path())) {
            return null;
        }
        try {
            T value = deserialize.apply(StorageSafety.readUtf8(resource.path()));
            assignId.accept(value, safeId);
            T stored = copy.apply(value);
            if (resource.coordinated()) {
                Optional<CachedResourceIdentity> identity = resourceIdentity(type, safeId, snapshot, resource.path());
                if (identity.isEmpty()) {
                    identities.remove(safeId);
                    cache.remove(safeId, stored);
                    Log.warn("Skipped caching " + action + " without a matching coordinator identity: " + safeId);
                    return copy.apply(stored);
                }
                cache.put(safeId, stored);
                identities.put(safeId, identity.get());
            } else {
                cache.put(safeId, stored);
                identities.remove(safeId);
            }
            return copy.apply(stored);
        } catch (IOException exception) {
            Log.warn("Failed to " + action + ": " + safeId + " - " + exception.getMessage());
            return null;
        }
    }

    private PreloadResource preloadResourceFile(File legacyDirectory, String type, String id, String action,
                                                 AssetTransactionCoordinator.Snapshot snapshot,
                                                 Map<String, Path> assetIndex) {
        Path assetFile = findAssetResourceFile(type, id, assetIndex);
        if (assetFile != null) {
            try {
                String coordinatedType = GRAPH_TYPES.contains(type) ? AssetFileFormat.readResourceType(assetFile) : type;
                requireCoordinatedLiveFile(coordinatedType.isBlank() ? type : coordinatedType, id, assetFile, snapshot);
            } catch (IOException exception) {
                throw new IllegalStateException("Resource payload diverged from the shared asset coordinator: "
                    + type + ':' + id, exception);
            }
            return new PreloadResource(assetFile, true);
        }
        if (GRAPH_TYPES.contains(type) && hasGraphTombstone(type, id)) {
            return null;
        }
        if (!legacyRuntimeGate.allowsLegacyFallback()) {
            logBlockedLegacyOperation(action);
            return null;
        }
        return legacyDirectory.exists() ? new PreloadResource(jsonFile(legacyDirectory, id, action), false) : null;
    }

    public synchronized void clearCache() {
        graphCache.clear();
        guiCache.clear();
        scoreboardCache.clear();
        tabCache.clear();
        guiCacheIdentities.clear();
        scoreboardCacheIdentities.clear();
        tabCacheIdentities.clear();
        runtimeTabIdentities.clear();
    }

    private String projectMetadataId(String id) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            return "project";
        }
    }

    private synchronized void loadDefaultScoreboard() {
        Properties properties = loadConfigProperties();
        String configuredId = properties.getProperty(DEFAULT_SCOREBOARD_ID_KEY, "").trim();
        if (!configuredId.isBlank()) {
            defaultScoreboardId = configuredId;
        }
        defaultScoreboardUsePapi = Boolean.parseBoolean(properties.getProperty(DEFAULT_SCOREBOARD_USE_PAPI_KEY, "true"));
        if (defaultScoreboardId != null || !defaultScoreboardUsePapi) {
            return;
        }
        File legacyFile = new File(configFile.getParentFile(), "default-scoreboard.cfg");
        if (!legacyFile.exists() || !legacyRuntimeGate.allowsLegacyFallback()) {
            if (legacyFile.exists()) {
                logBlockedLegacyOperation("load legacy default scoreboard configuration");
            }
            return;
        }
        try {
            List<String> lines = Files.readAllLines(legacyFile.toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                String id = lines.getFirst().trim();
                if (!id.isBlank()) {
                    defaultScoreboardId = id;
                }
            }
            if (lines.size() > 1) {
                defaultScoreboardUsePapi = Boolean.parseBoolean(lines.get(1).trim());
            }
            persistDefaultScoreboard();
        } catch (IOException e) {
            Log.warn("Failed to load default scoreboard config: " + e.getMessage());
        }
    }

    private synchronized void persistDefaultScoreboard() {
        Properties properties = loadConfigProperties();
        if (defaultScoreboardId == null || defaultScoreboardId.isBlank()) {
            properties.remove(DEFAULT_SCOREBOARD_ID_KEY);
            properties.remove(DEFAULT_SCOREBOARD_USE_PAPI_KEY);
        } else {
            properties.setProperty(DEFAULT_SCOREBOARD_ID_KEY, defaultScoreboardId);
            properties.setProperty(DEFAULT_SCOREBOARD_USE_PAPI_KEY, String.valueOf(defaultScoreboardUsePapi));
        }
        storeConfigProperties(properties);
    }

    private synchronized void loadDefaultTab() {
        Properties properties = loadConfigProperties();
        String configuredId = properties.getProperty(DEFAULT_TAB_ID_KEY, "").trim();
        if (!configuredId.isBlank()) {
            defaultTabId = configuredId;
        }
        defaultTabUsePapi = Boolean.parseBoolean(properties.getProperty(DEFAULT_TAB_USE_PAPI_KEY, "true"));
        if (defaultTabId != null || !defaultTabUsePapi) {
            return;
        }
        File legacyFile = new File(configFile.getParentFile(), "default-tab.cfg");
        if (!legacyFile.exists() || !legacyRuntimeGate.allowsLegacyFallback()) {
            if (legacyFile.exists()) {
                logBlockedLegacyOperation("load legacy default tab configuration");
            }
            return;
        }
        try {
            List<String> lines = Files.readAllLines(legacyFile.toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                String id = lines.getFirst().trim();
                if (!id.isBlank()) {
                    defaultTabId = id;
                }
            }
            if (lines.size() > 1) {
                defaultTabUsePapi = Boolean.parseBoolean(lines.get(1).trim());
            }
            persistDefaultTab();
        } catch (IOException e) {
            Log.warn("Failed to load default tab config: " + e.getMessage());
        }
    }

    private synchronized void persistDefaultTab() {
        Properties properties = loadConfigProperties();
        if (defaultTabId == null || defaultTabId.isBlank()) {
            properties.remove(DEFAULT_TAB_ID_KEY);
            properties.remove(DEFAULT_TAB_USE_PAPI_KEY);
        } else {
            properties.setProperty(DEFAULT_TAB_ID_KEY, defaultTabId);
            properties.setProperty(DEFAULT_TAB_USE_PAPI_KEY, String.valueOf(defaultTabUsePapi));
        }
        storeConfigProperties(properties);
    }

    private synchronized void loadTabRefreshConfig() {
        Properties properties = loadConfigProperties();
        String configuredInterval = properties.getProperty(REFRESH_INTERVAL_KEY, "").trim();
        if (!configuredInterval.isBlank()) {
            try {
                tabRefreshIntervalTicks = Math.max(1, Integer.parseInt(configuredInterval));
                return;
            } catch (NumberFormatException exception) {
                Log.warn("Invalid tab refresh interval in config: " + configuredInterval);
            }
        }
        File legacyFile = new File(configFile.getParentFile(), "tab-refresh.cfg");
        if (!legacyFile.exists() || !legacyRuntimeGate.allowsLegacyFallback()) {
            if (legacyFile.exists()) {
                logBlockedLegacyOperation("load legacy tab refresh configuration");
            }
            return;
        }
        try {
            String content = Files.readString(legacyFile.toPath(), StandardCharsets.UTF_8).trim();
            if (!content.isBlank()) {
                tabRefreshIntervalTicks = Math.max(1, Integer.parseInt(content));
                persistTabRefreshConfig();
            }
        } catch (IOException | NumberFormatException e) {
            Log.warn("Failed to load tab refresh config: " + e.getMessage());
        }
    }

    private synchronized void persistTabRefreshConfig() {
        Properties properties = loadConfigProperties();
        properties.setProperty(REFRESH_INTERVAL_KEY, String.valueOf(Math.max(1, tabRefreshIntervalTicks)));
        storeConfigProperties(properties);
    }

    private Properties loadConfigProperties() {
        return configurationPersistence.properties();
    }

    private void storeConfigProperties(Properties properties) {
        try {
            configurationPersistence.replacePropertiesAndFlush(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save config.properties", exception);
        }
    }

    private void cleanupBelowNameData() {
        if (!legacyRuntimeGate.allowsLegacyMigration()) {
            logBlockedLegacyOperation("cleanup legacy below-name data");
            return;
        }
        try {
            File legacyBelowNamesDir = new File(configFile.getParentFile(), "below-names");
            if (legacyBelowNamesDir.exists() && legacyBelowNamesDir.isDirectory()) {
                File[] files = legacyBelowNamesDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file != null && file.isFile()) {
                            Files.deleteIfExists(file.toPath());
                        }
                    }
                }
                Files.deleteIfExists(legacyBelowNamesDir.toPath());
            }
            File legacyDefault = new File(configFile.getParentFile(), "default-below-name.cfg");
            if (legacyDefault.exists()) {
                Files.deleteIfExists(legacyDefault.toPath());
            }
            Properties properties = loadConfigProperties();
            properties.remove("flow.default-below-name.id");
            properties.remove("flow.default-below-name.usePapi");
            storeConfigProperties(properties);
        } catch (IOException e) {
            Log.warn("Failed to cleanup below-name data: " + e.getMessage());
        }
    }

    private Path resourceFile(File legacyDirectory, String type, String id, String action) {
        Path assetFile = findAssetResourceFile(type, id);
        if (assetFile != null) {
            try {
                String coordinatedType = GRAPH_TYPES.contains(type) ? AssetFileFormat.readResourceType(assetFile) : type;
                requireCoordinatedLiveFile(coordinatedType.isBlank() ? type : coordinatedType, id, assetFile);
            } catch (IOException exception) {
                throw new IllegalStateException("Resource payload diverged from the shared asset coordinator: " + type + ':' + id,
                    exception);
            }
            return assetFile;
        }
        if (GRAPH_TYPES.contains(type) && hasGraphTombstone(type, id)) {
            return null;
        }
        if (!legacyRuntimeGate.allowsLegacyFallback()) {
            logBlockedLegacyOperation(action);
            return null;
        }
        return legacyDirectory.exists() ? jsonFile(legacyDirectory, id, action) : null;
    }

    private Path writableResourceFile(File legacyDirectory, String type, String id, String action) {
        Path assetFile = findAssetResourceFile(type, id);
        if (assetFile != null) {
            return writableAssetPath(assetFile, id);
        }
        String json = getProjectMetadata("project");
        ProjectMetadataSnapshot metadata = parseProjectMetadata(json);
        ResourceEntrySnapshot resource = metadata != null ? metadata.findResource(type, id) : null;
        if (resource != null) {
            return assetResourceFile(resource);
        }
        return assetResourceFile(newResource(type, id, id, defaultFolderForType(type)));
    }

    private Path writableGraphResourceFile(FlowGraph graph, String id) {
        String type = graphResourceType(graph);
        Path assetFile = findAssetResourceFile(type, id);
        if (assetFile != null) {
            return writableAssetPath(assetFile, id);
        }
        String json = getProjectMetadata("project");
        ProjectMetadataSnapshot metadata = parseProjectMetadata(json);
        ResourceEntrySnapshot resource = metadata != null ? metadata.findExactResource(type, id) : null;
        if (resource != null) {
            return assetResourceFile(resource);
        }
        return assetResourceFile(newResource(type, id, id, defaultFolderForType(type)));
    }

    private Path findAssetResourceFile(String type, String id) {
        if (!Files.exists(assetsDir.toPath())) {
            return null;
        }
        Path known = knownAssetResourceFile(type, id);
        if (known != null) {
            return known;
        }
        Map<String, Path> index = assetFiles();
        return findAssetResourceFile(type, id, index);
    }

    private Path findAssetResourceFile(String type, String id, Map<String, Path> index) {
        return index.get(assetIndexKey(type, id));
    }

    private Path findExactAssetResourceFile(String type, String id) {
        if (!GRAPH_TYPES.contains(type) || id == null || id.isBlank() || !Files.exists(assetsDir.toPath())) {
            return null;
        }
        Path known = knownAssetResourceFile(type, id);
        if (known != null) {
            return known;
        }
        return assetFiles().get(assetIndexKey(type, id));
    }

    private Path coordinatedAssetPath(String type, String id) {
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            return null;
        }
        try {
            Path path = requireAssetTransactions().read(snapshot -> snapshot.path(assetKey(type, id)).orElse(null));
            if (path == null) {
                return null;
            }
            Path root = assetsDir.toPath().toAbsolutePath().normalize();
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || normalized.equals(root) || isDurabilityInternalPath(root, normalized)) {
                return null;
            }
            return normalized;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Path knownAssetResourceFile(String type, String id) {
        if (type == null || type.isBlank() || id == null || id.isBlank() || !Files.isDirectory(assetsDir.toPath())) {
            return null;
        }
        Path coordinated = coordinatedAssetPath(type, id);
        Path valid = validAssetResourceFile(type, id, coordinated);
        if (valid != null) {
            return valid;
        }
        try {
            ProjectMetadataSnapshot metadata = parseProjectMetadata(getProjectMetadata("project"));
            ResourceEntrySnapshot resource = metadata != null ? metadata.findExactResource(type, id) : null;
            if (resource != null) {
                valid = validAssetResourceFile(type, id, assetResourceFile(resource));
                if (valid != null) {
                    return valid;
                }
            }
            return validAssetResourceFile(type, id,
                safeAssetFolder(defaultFolderForType(type)).resolve(AssetFileFormat.idOnlyFileName(id)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return null;
        }
    }

    private Path validAssetResourceFile(String type, String id, Path file) {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || isDurabilityInternalPath(root, normalized)
            || !type.equals(AssetFileFormat.readResourceType(normalized))) {
            return null;
        }
        return normalized;
    }

    private synchronized Map<String, Path> assetFiles() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            return requireAssetTransactions().read(this::preloadAssetFiles);
        }
    }

    private Map<String, Path> preloadAssetFiles(AssetTransactionCoordinator.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "Asset coordinator snapshot is required");
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Map<String, Path> index = new LinkedHashMap<>();
        List<Map.Entry<AssetTransactionCoordinator.AssetKey, AssetTransactionCoordinator.ExpectedState>> entries =
            snapshot.states().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().canonical()))
                .toList();
        for (Map.Entry<AssetTransactionCoordinator.AssetKey, AssetTransactionCoordinator.ExpectedState> entry : entries) {
            AssetTransactionCoordinator.AssetKey key = entry.getKey();
            if (!knownAssetType(key.type())) {
                continue;
            }
            AssetTransactionCoordinator.ExpectedState state = entry.getValue();
            if (!(state instanceof AssetTransactionCoordinator.Live)) {
                continue;
            }
            Path path = snapshot.path(key).orElse(null);
            if (path == null) {
                continue;
            }
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || isDurabilityInternalPath(root, normalized)) {
                continue;
            }
            String id = safeId(key.id(), "index preload asset");
            if (id == null) {
                continue;
            }
            index.put(assetIndexKey(key.type(), id), normalized);
        }
        return Map.copyOf(index);
    }

    private String assetIndexKey(String type, String id) {
        return type + "\n" + id;
    }

    private Stream<Path> walkAssetTree(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        assetTraversalObserver.accept(normalized);
        return Files.walk(normalized);
    }

    private void ensureCommandLabelAvailable(FlowGraph candidate, String candidateId) {
        Map<String, FlowGraph> graphs = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (String graphId : listGraphIds("command")) {
            if (candidateId.equals(graphId)) {
                continue;
            }
            FlowGraph graph = getGraph("command", graphId);
            if (graph == null) {
                failures.put(graphId, "The typed command graph could not be loaded or failed its integrity check");
            } else if (!graphId.equals(graph.getId())) {
                failures.put(graphId, "The typed command graph ID does not match its typed storage identity");
            } else {
                graphs.put(graphId, graph);
            }
        }
        graphs.put(candidateId, candidate);
        TypedCommandGraphAdapter.Snapshot snapshot = TypedCommandGraphAdapter.index(graphs, failures);
        boolean rejectedCandidate = snapshot.rejections().stream().anyMatch(rejection -> candidateId.equals(rejection.graphId()));
        if (rejectedCandidate) {
            throw new IllegalArgumentException(snapshot.rejections().stream()
                .filter(rejection -> candidateId.equals(rejection.graphId()))
                .map(TypedCommandGraphAdapter.Rejection::detail)
                .findFirst()
                .orElse("Typed command graph was rejected"));
        }
    }

    private void evictGraphCache(String id) {
        Set.of("flow", "function", "command").forEach(type -> graphCache.remove(assetIndexKey(type, id)));
    }

    private Path assetResourceFile(ResourceEntrySnapshot resource) {
        String normalized = normalizeAssetPath(resource.path);
        String fileName = assetResourceFileName(resource.type, resource.id);
        if (normalized.equals(fileName) || normalized.endsWith("/" + fileName)) {
            return assetsDir.toPath().toAbsolutePath().normalize().resolve(normalized).normalize();
        }
        return safeAssetFolder(normalized).resolve(fileName);
    }

    private String assetResourceFileName(String type, String id) {
        return AssetFileFormat.idOnlyFileName(id);
    }

    private boolean graphHasCommandStartNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return false;
        }
        return graph.getNodes().values().stream()
                .anyMatch(node -> node != null && ("event.resync.command".equals(node.getType()) || "event:resync_command".equals(node.getType())));
    }

    private Path writableAssetPath(Path assetFile, String id) {
        if (assetFile == null || AssetFileFormat.isIdOnlyFileName(assetFile.getFileName().toString())) {
            return assetFile;
        }
        return assetFile.getParent().resolve(AssetFileFormat.idOnlyFileName(id));
    }

    private String graphResourceTypeForWrite(FlowGraph graph) {
        return graphResourceType(graph);
    }

    private Path collisionSafeWriteFile(String type, String id, Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return file;
        }
        String existingType = AssetFileFormat.readResourceType(file);
        if (existingType.isBlank() || type.equals(existingType)) {
            return file;
        }
        String folder = conflictFolderForType(type, id, 1);
        Path target = safeAssetFolder(folder).resolve(AssetFileFormat.idOnlyFileName(id));
        Files.createDirectories(target.getParent());
        return target;
    }

    private String metadataWithResourcePath(String type, String id, String folder, boolean reclassifying) {
        ProjectMetadataSnapshot metadata = parseProjectMetadata(getProjectMetadata("project"));
        if (metadata == null) {
            metadata = new ProjectMetadataSnapshot();
        }
        if (reclassifying) {
            metadata.mutableResources().removeIf(resource -> resource != null && id.equals(resource.id) && GRAPH_TYPES.contains(resource.type) && !type.equals(resource.type));
        }
        ensureFolderPath(metadata, folder);
        ensureAssetResource(metadata, type, id, id, folder);
        return serializeProjectMetadata(metadata);
    }

    private CoreProjectMetadata readCoreProjectMetadata() throws IOException {
        String json = requireAssetTransactions().read(snapshot -> snapshot.metadata().serializedJson());
        try {
            String canonicalJson = canonicalProjectMetadataJson(json);
            JsonElement parsed = JsonParser.parseString(canonicalJson);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Core project metadata must be a JSON object");
            }
            ProjectMetadataSnapshot metadata = gson.fromJson(parsed, ProjectMetadataSnapshot.class);
            if (metadata == null) {
                throw new IllegalArgumentException("Core project metadata is empty");
            }
            return new CoreProjectMetadata(metadata, canonicalJson);
        } catch (RuntimeException exception) {
            throw new IOException("Malformed Core project metadata", exception);
        }
    }

    private String coreMetadataWithResourcePath(CoreProjectMetadata current, String type, String id,
                                                 String folder, boolean reclassifying) {
        ProjectMetadataSnapshot metadata = current.snapshot();
        if (reclassifying) {
            metadata.mutableResources().removeIf(resource -> resource != null && id.equals(resource.id)
                && GRAPH_TYPES.contains(resource.type) && !type.equals(resource.type));
        }
        ensureFolderPath(metadata, folder);
        ensureAssetResource(metadata, type, id, id, folder);
        return serializeProjectMetadata(metadata);
    }

    private String metadataWithPresentation(CoreProjectMetadata current, String type, String id,
                                            ResourcePresentationIntent presentation) {
        ProjectMetadataSnapshot metadata = current.snapshot();
        if (metadata.findExactResource(type, id) != null) {
            throw new IllegalStateException("Aggregate create project metadata identity already exists: "
                + type + ':' + id);
        }
        String path = normalizeAssetPath(presentation.path());
        int separator = path.lastIndexOf('/');
        String folder = separator >= 0 ? path.substring(0, separator) : "";
        ensureFolderPath(metadata, folder);
        if (metadata.resources().stream().anyMatch(resource -> resource != null && path.equals(resource.path)
            && (!type.equals(resource.type) || !id.equals(resource.id)))) {
            throw new IllegalArgumentException("Aggregate create presentation path is already in use: " + path);
        }
        ResourceEntrySnapshot resource = new ResourceEntrySnapshot();
        resource.type = type;
        resource.id = id;
        resource.displayName = presentation.displayName();
        resource.path = path;
        resource.sortOrder = presentation.sortOrder();
        metadata.mutableResources().add(resource);
        return serializeProjectMetadata(metadata);
    }

    private void requirePresentation(ProjectMetadataSnapshot metadata, String type, String id,
                                     ResourcePresentationIntent presentation) {
        ResourceEntrySnapshot resource = metadata.findExactResource(type, id);
        String path = normalizeAssetPath(presentation.path());
        if (resource == null || !presentation.displayName().equals(resource.displayName)
            || !path.equals(resource.path) || presentation.sortOrder() != resource.sortOrder) {
            throw new IllegalStateException("Aggregate create replay presentation does not match durable metadata");
        }
    }

    private Path presentationAssetPath(String id, String path) throws IOException {
        String normalized = normalizeAssetPath(path);
        String fileName = AssetFileFormat.idOnlyFileName(id);
        if (!normalized.equals(path) || !normalized.equals(fileName) && !normalized.endsWith("/" + fileName)) {
            throw new IllegalArgumentException("Aggregate create path must end with the resource ID: " + path);
        }
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path target = root.resolve(normalized).toAbsolutePath().normalize();
        requireCoreStoragePath(root, target);
        if (isDurabilityInternalAssetPath(normalized) || target.equals(root.resolve("project.json"))) {
            throw new IllegalArgumentException("Aggregate create path is outside coordinated asset storage: " + path);
        }
        return target;
    }

    private String coreMetadataWithoutResource(CoreProjectMetadata current, String type, String id) {
        ProjectMetadataSnapshot metadata = current.snapshot();
        if (!metadata.mutableResources().removeIf(resource -> resource != null && type.equals(resource.type)
            && id.equals(resource.id))) {
            return current.json();
        }
        return serializeProjectMetadata(metadata);
    }

    private void verifyCoreGraphSave(Path file, byte[] expectedBytes, Path tombstoneFile,
                                     String expectedMetadataJson) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        requireCoreStoragePath(root, file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || !Arrays.equals(expectedBytes, readCoreAssetBytes(file))) {
            throw new IOException("Core graph mutation was acknowledged without publishing the requested asset: " + file);
        }
        requireCoreStoragePath(root, tombstoneFile);
        if (Files.exists(tombstoneFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Core graph mutation left a tombstone: " + tombstoneFile);
        }
        verifyCoreProjectMetadata(root.resolve("project.json"), expectedMetadataJson);
    }

    private void verifyCoreProjectMetadata(Path file, String expectedJson) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        requireCoreStoragePath(root, file);
        boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        if (!exists || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Core project metadata was not published: " + file);
        }
        ProjectMetadataSnapshot expected = parseProjectMetadata(expectedJson == null ? "{}" : expectedJson);
        ProjectMetadataSnapshot actual = parseProjectMetadata(StorageSafety.readUtf8(file));
        if (expected == null || actual == null || !gson.toJson(expected).equals(gson.toJson(actual))) {
            throw new IOException("Core project metadata was acknowledged with different contents: " + file);
        }
    }

    private void applyResourceIdentity(FlowGraph graph, Path file) {
        String type = AssetFileFormat.readResourceType(file);
        graph.setFunction("function".equals(type));
        graph.setResourceType(type);
        graph.setResourceRevision(AssetFileFormat.readRevision(file));
        graph.setResourceHash(AssetFileFormat.readContentHash(file));
        graph.setResourceMutationId(AssetFileFormat.readMutationId(file));
    }

    private ReloadGraphState loadReloadGraph(String requestedType, String safeId) throws IOException {
        Path file = requestedType.isBlank() ? resourceFile(flowDir, "flow", safeId, "reload flow")
            : findAssetResourceFile(requestedType, safeId);
        if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (requestedType.isBlank()) {
                evictGraphCache(safeId);
            } else {
                graphCache.remove(assetIndexKey(requestedType, safeId));
            }
            return new ReloadGraphState(null, requestedType, file, null, 0L, "", "");
        }
        String actualType = AssetFileFormat.readResourceType(file);
        if (!requestedType.isBlank() && !requestedType.equals(actualType)) {
            return new ReloadGraphState(null, requestedType, file, null, 0L, "", "");
        }
        String coordinatedType = actualType.isBlank() ? "flow" : actualType;
        requireCoordinatedLiveFile(coordinatedType, safeId, file);
        CoreGraphStorageBoundary.Decoded core = decodeCoreGraphAsset(file, coordinatedType, safeId);
        FlowGraph graph = core == null ? null : materializeCoreGraph(core, coordinatedType);
        if (core != null && graph == null) {
            throw new IllegalStateException("Core graph payload is not materializable: " + safeId);
        }
        if (graph == null && !verifyGraphAsset(file, coordinatedType, safeId)) {
            throw new IllegalStateException("Flow integrity check failed: " + safeId);
        }
        if (graph == null) {
            graph = FlowSerializer.deserialize(StorageSafety.readUtf8(file));
            applyResourceIdentity(graph, file);
        }
        return new ReloadGraphState(graph, coordinatedType, file, readFileStamp(file),
            graph.getResourceRevision(), graph.getResourceMutationId(), graph.getResourceHash());
    }

    private boolean reloadGraphStateMatchesCurrent(ReloadGraphState expected, String id,
                                                     PersistenceGeneration generation) {
        if (!isCurrentPersistenceGeneration(generation)) {
            return false;
        }
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceReadOpen()) {
            if (!isCurrentPersistenceGeneration(generation)) {
                return false;
            }
            AssetTransactionCoordinator.Snapshot snapshot = requireCoordinatedLiveFile(expected.type(), id, expected.file());
            if (!verifyGraphAsset(expected.file(), expected.type(), id)
                || !expected.stamp().equals(readFileStamp(expected.file()))) {
                return false;
            }
            AssetTransactionCoordinator.ExpectedState state = snapshot.state(assetKey(expected.type(), id))
                .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
            if (!(state instanceof AssetTransactionCoordinator.Live live)
                || live.revision() != expected.revision()
                || !Objects.equals(expected.mutationId(), snapshot.mutationValue(assetKey(expected.type(), id)).orElse(""))) {
                return false;
            }
            return Objects.equals(expected.resourceHash(), AssetFileFormat.readContentHash(expected.file()));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private FileStamp readFileStamp(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    private UUID parseMutationId(String mutationId) {
        if (mutationId == null || mutationId.isBlank()) {
            throw new IllegalArgumentException("Mutation ID is required");
        }
        try {
            return UUID.fromString(mutationId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Mutation ID must be a UUID", exception);
        }
    }

    private void requireResourceMutationRequest(UUID mutationId, long expectedRevision) {
        Objects.requireNonNull(mutationId, "Mutation ID is required");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Expected resource revision must not be negative");
        }
    }

    private Optional<StoredGraphState> readStoredGraphState(String type, String id) {
        Path assetFile = findStoredGraphAssetFile(type, id);
        Path tombstoneFile = graphTombstoneFile(type, id);
        boolean physicalAsset = assetFile != null && Files.exists(assetFile, LinkOption.NOFOLLOW_LINKS);
        boolean physicalTombstone = Files.exists(tombstoneFile, LinkOption.NOFOLLOW_LINKS);
        GraphIdentity live = null;
        if (physicalAsset && !Files.isRegularFile(assetFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Graph live payload is not a regular file: " + type + ':' + id);
        }
        if (physicalAsset) {
            String payloadHash = readGraphPayloadHash(assetFile);
            if (isCanonicalPayloadHash(payloadHash)) {
                live = new GraphIdentity(type, id, AssetFileFormat.readRevision(assetFile), AssetFileFormat.readMutationId(assetFile), false,
                    payloadHash);
            }
        }
        GraphIdentity tombstone = readGraphTombstoneIdentity(type, id, tombstoneFile);
        if (physicalTombstone && tombstone == null) {
            throw new IllegalStateException("Invalid graph tombstone: " + type + ':' + id);
        }
        if (live == null && tombstone == null) {
            if (physicalAsset) {
                throw new IllegalStateException("Invalid graph live payload: " + type + ':' + id);
            }
            return Optional.empty();
        }
        if (live != null && tombstone != null) {
            throw new IllegalStateException("Graph has both a live payload and tombstone: " + type + ':' + id);
        }
        if (tombstone != null) {
            return Optional.of(new StoredGraphState(tombstone, assetFile, tombstoneFile));
        }
        return Optional.of(new StoredGraphState(live, assetFile, tombstoneFile));
    }

    private String resolveStoredGraphType(String id) {
        String safeId = safeId(id, "resolve graph type");
        if (safeId == null) {
            return "";
        }
        for (String type : List.of("flow", "function", "command")) {
            ensureValidGraphTombstone(type, safeId);
        }
        List<StoredGraphState> states = List.of("flow", "function", "command").stream()
            .map(type -> readStoredGraphState(type, safeId).orElse(null))
            .filter(Objects::nonNull)
            .toList();
        if (states.size() > 1) {
            throw new IllegalArgumentException("Untyped graph ID is ambiguous: " + safeId);
        }
        if (!states.isEmpty()) {
            return states.getFirst().identity().type();
        }
        if (!legacyRuntimeGate.allowsLegacyFallback()) {
            return "";
        }
        Path legacy = jsonFile(flowDir, safeId, "resolve legacy graph type");
        if (legacy == null || !Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        FlowGraph graph = readLegacyFlow(safeId);
        if (graph == null) {
            return "";
        }
        String type = graphResourceType(graph);
        return GRAPH_TYPES.contains(type) ? type : "";
    }

    private GraphIdentity readGraphTombstoneIdentity(String type, String id, Path tombstoneFile) {
        if (tombstoneFile == null || !Files.isRegularFile(tombstoneFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            String tombstoneJson = StorageSafety.readUtf8(tombstoneFile);
            JsonObject value = gson.fromJson(tombstoneJson, JsonObject.class);
            if (value != null && hasTombstoneString(value, "kind", CoreGraphStorageBoundary.TOMBSTONE_KIND)) {
                CoreGraphStorageBoundary.CoreGraphTombstone tombstone = coreGraphStorage.decodeTombstoneText(
                    tombstoneJson, coreResource(type, id));
                return new GraphIdentity(type, id, tombstone.revision(), tombstone.mutationId().toString(), true,
                    tombstone.priorPayloadHash().canonicalText());
            }
            if (value == null || !hasTombstoneString(value, "type", type) || !hasTombstoneString(value, "id", id)) {
                return null;
            }
            JsonElement deleted = value.get("deleted");
            if (deleted == null || !deleted.isJsonPrimitive() || !deleted.getAsJsonPrimitive().isBoolean() || !deleted.getAsBoolean()) {
                return null;
            }
            JsonElement revisionElement = value.get("revision");
            if (revisionElement == null || !revisionElement.isJsonPrimitive() || !revisionElement.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            long revision;
            try {
                revision = Long.parseLong(revisionElement.getAsString());
            } catch (NumberFormatException exception) {
                return null;
            }
            if (revision <= 0L) {
                return null;
            }
            boolean hasCanonicalMutation = value.has(AssetFileFormat.MUTATION_ID);
            boolean hasLegacyMutation = value.has("mutationId");
            String canonicalMutation = hasCanonicalMutation ? parseTombstoneMutationId(value.get(AssetFileFormat.MUTATION_ID)) : null;
            String legacyMutation = hasLegacyMutation ? parseTombstoneMutationId(value.get("mutationId")) : null;
            if ((hasCanonicalMutation && canonicalMutation == null) || (hasLegacyMutation && legacyMutation == null)
                || (!hasCanonicalMutation && !hasLegacyMutation)) {
                return null;
            }
            if (canonicalMutation != null && legacyMutation != null && !canonicalMutation.equals(legacyMutation)) {
                return null;
            }
            String mutationId = canonicalMutation != null ? canonicalMutation : legacyMutation;
            String payloadHash = parseTombstonePayloadHash(value);
            if (payloadHash == null) {
                return null;
            }
            return new GraphIdentity(type, id, revision, mutationId, true, payloadHash);
        } catch (IOException | RuntimeException exception) {
            Log.warn("Failed to read graph tombstone for " + type + ':' + id + ": " + exception.getMessage());
            return null;
        }
    }

    private boolean hasTombstoneString(JsonObject value, String field, String expected) {
        JsonElement element = value.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            && expected.equals(element.getAsString());
    }

    private String parseTombstoneMutationId(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            return null;
        }
        try {
            UUID mutationId = UUID.fromString(value);
            return mutationId.toString().equalsIgnoreCase(value) ? mutationId.toString() : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String parseTombstonePayloadHash(JsonObject value) {
        if (!value.has("payloadHash")) {
            return null;
        }
        JsonElement element = value.get("payloadHash");
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String payloadHash = element.getAsString();
        return isCanonicalPayloadHash(payloadHash) ? payloadHash : null;
    }

    private boolean isCanonicalPayloadHash(String payloadHash) {
        if (payloadHash == null || payloadHash.length() != 64) {
            return false;
        }
        for (int index = 0; index < payloadHash.length(); index++) {
            char value = payloadHash.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private String readGraphPayloadHash(Path assetFile) {
        if (assetFile == null || !Files.isRegularFile(assetFile, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            return canonicalGraphPayloadHash(StorageSafety.readUtf8(assetFile));
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }

    private LegacyGraphDeleteSource verifiedLegacyGraphDeleteSource(String type, String id) throws IOException {
        if (!GRAPH_TYPES.contains(type) || !legacyRuntimeGate.allowsLegacyFallback()) {
            return null;
        }
        Path legacyFile = requireLegacyGraphCandidatePath(id);
        if (!Files.exists(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Legacy graph deletion source is not a regular file: " + type + ':' + id);
        }
        String json = StorageSafety.readUtf8(legacyFile);
        FlowGraph graph;
        try {
            graph = FlowSerializer.deserialize(json);
        } catch (RuntimeException exception) {
            throw new IOException("Legacy graph deletion source is invalid: " + type + ':' + id, exception);
        }
        String payloadHash;
        try {
            payloadHash = canonicalGraphPayloadHash(json);
        } catch (RuntimeException exception) {
            throw new IOException("Legacy graph deletion source payload is invalid: " + type + ':' + id, exception);
        }
        if (graph == null || !id.equals(graph.getId()) || !type.equals(graphResourceType(graph))
            || !isCanonicalPayloadHash(payloadHash)) {
            throw new IOException("Legacy graph deletion source identity is invalid: " + type + ':' + id);
        }
        return new LegacyGraphDeleteSource(legacyFile, payloadHash);
    }

    private String canonicalGraphPayloadHash(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("Graph payload is not an object");
        }
        JsonObject payload = parsed.getAsJsonObject().deepCopy();
        for (String field : List.of("resourceType", "assetFormatVersion", "assetRevision", "assetHash", "assetMutationId",
            "resourceRevision", "resourceHash", "resourceMutationId", "revision", "mutationId")) {
            payload.remove(field);
        }
        return StorageSafety.sha256(gson.toJson(payload));
    }

    private String canonicalResourcePayloadHash(String type, String json) {
        String payload = typedCanonicalPayloadJson(type, json);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = gson.fromJson(payload, Map.class);
        if (value == null) {
            throw new IllegalArgumentException("Typed resource payload is not a JSON object: " + type);
        }
        return ResourcePayloadCodecs.json().hashPayload(value).canonicalText();
    }

    private String typedCanonicalPayloadJson(String type, String json) {
        return switch (type) {
            case "gui" -> FlowSerializer.serializeGui(FlowSerializer.deserializeGui(json));
            case "scoreboard" -> FlowSerializer.serializeScoreboard(FlowSerializer.deserializeScoreboard(json));
            case "tab" -> FlowSerializer.serializeTab(FlowSerializer.deserializeTab(json));
            default -> throw new IllegalArgumentException("Unsupported typed resource payload: " + type);
        };
    }

    private String normalizeProjectMetadata(String json) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            throw new IOException("Flow project metadata is invalid", exception);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IOException("Flow project metadata must be a JSON object");
        }
        JsonObject normalized = parsed.getAsJsonObject().deepCopy();
        ProjectMetadataSnapshot metadata = parseProjectMetadata(gson.toJson(normalized));
        if (metadata == null) {
            throw new IOException("Flow project metadata could not be decoded");
        }
        try {
            normalized.addProperty("serverId", canonicalProjectMetadataServerId(metadata));
        } catch (RuntimeException exception) {
            throw new IOException("Flow project metadata has a foreign server identity", exception);
        }
        return gson.toJson(normalized);
    }

    private String canonicalProjectMetadataJson(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("Flow project metadata must be a JSON object");
        }
        JsonObject normalized = parsed.getAsJsonObject().deepCopy();
        ProjectMetadataSnapshot metadata = parseProjectMetadata(gson.toJson(normalized));
        if (metadata == null) {
            throw new IllegalArgumentException("Flow project metadata could not be decoded");
        }
        normalized.addProperty("serverId", canonicalProjectMetadataServerId(metadata));
        return gson.toJson(normalized);
    }

    private String canonicalProjectMetadataServerId(ProjectMetadataSnapshot metadata) {
        String supplied = metadata.serverId;
        String canonical = projectMetadataResourceId();
        if (supplied != null && !supplied.isBlank() && !"project".equals(supplied) && !canonical.equals(supplied)) {
            throw new IllegalArgumentException("Flow project metadata server identity does not match storage identity");
        }
        return canonical;
    }

    private String serializeProjectMetadata(ProjectMetadataSnapshot metadata) {
        Objects.requireNonNull(metadata, "Project metadata is required");
        metadata.serverId = canonicalProjectMetadataServerId(metadata);
        return gson.toJson(metadata);
    }

    private String canonicalProjectMetadataPayloadHash(String json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> value = gson.fromJson(json, Map.class);
        if (value == null) {
            throw new IllegalArgumentException("Project metadata payload is not a JSON object");
        }
        return ResourcePayloadCodecs.json().hashPayload(value).canonicalText();
    }

    private AssetTransactionCoordinator.AssetKey projectMetadataLineageKey() {
        return new AssetTransactionCoordinator.AssetKey(PROJECT_METADATA_LINEAGE_TYPE, PROJECT_METADATA_LINEAGE_ID);
    }

    private Path projectMetadataLineageFile() {
        return assetsDir.toPath().resolve(".durability").resolve("project-metadata-lineage.v1.json");
    }

    private String projectMetadataLineage(String id, long revision, UUID mutationId, String payloadHash,
                                          boolean deleted) {
        JsonObject lineage = new JsonObject();
        lineage.addProperty("format", PROJECT_METADATA_LINEAGE_FORMAT);
        lineage.addProperty("type", PROJECT_METADATA_TYPE);
        lineage.addProperty("id", id);
        lineage.addProperty("revision", revision);
        lineage.addProperty("mutationId", mutationId.toString());
        lineage.addProperty("payloadHash", payloadHash);
        lineage.addProperty("deleted", deleted);
        return gson.toJson(lineage);
    }

    private ResourceIdentity projectMetadataIdentity(AssetTransactionCoordinator.Snapshot snapshot, String resourceId) {
        AssetTransactionCoordinator.AssetKey key = projectMetadataLineageKey();
        AssetTransactionCoordinator.ExpectedState state = snapshot.state(key)
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        if (state instanceof AssetTransactionCoordinator.Live live) {
            Path path = snapshot.path(key).orElse(null);
            if (path == null || !path.toAbsolutePath().normalize().equals(projectMetadataLineageFile().toAbsolutePath().normalize())
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Project metadata lineage file is unavailable");
            }
            try {
                byte[] bytes = Files.readAllBytes(path);
                if (!live.hash().equals(StorageSafety.sha256(bytes))) {
                    throw new IllegalStateException("Project metadata lineage differs from the shared asset coordinator");
                }
                JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    throw new IllegalStateException("Project metadata lineage is not a JSON object");
                }
                JsonObject lineage = parsed.getAsJsonObject();
                String format = requiredProjectMetadataString(lineage, "format");
                String type = requiredProjectMetadataString(lineage, "type");
                String id = requiredProjectMetadataString(lineage, "id");
                String mutationId = requiredProjectMetadataString(lineage, "mutationId");
                String payloadHash = requiredProjectMetadataString(lineage, "payloadHash");
                long revision = lineage.get("revision").getAsLong();
                boolean deleted = lineage.get("deleted").getAsBoolean();
                if (!PROJECT_METADATA_LINEAGE_FORMAT.equals(format) || !PROJECT_METADATA_TYPE.equals(type)
                    || !resourceId.equals(id) || revision < 1L || !isCanonicalPayloadHash(payloadHash)
                    || !mutationId.equals(snapshot.mutationValue(key).orElse(""))) {
                    throw new IllegalStateException("Project metadata lineage identity is invalid");
                }
                UUID.fromString(mutationId);
                return new ResourceIdentity(PROJECT_METADATA_TYPE, resourceId, revision, mutationId, deleted, payloadHash);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Failed to read project metadata lineage", exception);
            }
        }
        if (!(state instanceof AssetTransactionCoordinator.Missing)) {
            throw new IllegalStateException("Project metadata lineage has an invalid coordinator state");
        }
        if (snapshot.project().revision() < 1L) {
            return null;
        }
        String mutationId = snapshot.projectMutationValue()
            .orElseThrow(() -> new IllegalStateException("Project metadata has no durable mutation lineage"));
        String payloadHash = canonicalProjectMetadataPayloadHash(
            canonicalProjectMetadataJson(snapshot.metadata().serializedJson()));
        return new ResourceIdentity(PROJECT_METADATA_TYPE, resourceId, snapshot.project().revision(), mutationId,
            false, payloadHash);
    }

    private UUID projectMetadataLineageRepairMutationId(UUID sourceMutationId) {
        return UUID.nameUUIDFromBytes(("restudio.resync/project-metadata-lineage-repair-v1/" + sourceMutationId)
            .getBytes(StandardCharsets.UTF_8));
    }

    private void requireProjectMetadataLineageRecoverySource(AssetTransactionCoordinator coordinator,
                                                              AssetTransactionCoordinator.Snapshot snapshot,
                                                              AssetTransactionCoordinator.MutationView source,
                                                              String sourceType, String sourceId,
                                                              long sourceRevision, String sourceHash,
                                                              long previousMetadataRevision,
                                                              UUID previousMetadataMutationId,
                                                              String previousMetadataHash) throws IOException {
        String resourceId = projectMetadataResourceId();
        ResourceIdentity metadata = projectMetadataIdentity(snapshot, resourceId);
        if (metadata == null || metadata.deleted() || metadata.revision() != previousMetadataRevision
            || !metadata.mutationId().equals(previousMetadataMutationId.toString())
            || !metadata.payloadHash().equals(previousMetadataHash)) {
            throw new IOException("Project metadata lineage recovery baseline does not match durable lineage");
        }
        requireProjectMetadataLineageRecoverySourceMutation(coordinator, snapshot, source, sourceType, sourceId,
            sourceRevision, sourceHash);
    }

    private void requireProjectMetadataLineageRecoverySourceMutation(AssetTransactionCoordinator coordinator,
                                                                      AssetTransactionCoordinator.Snapshot snapshot,
                                                                      AssetTransactionCoordinator.MutationView source,
                                                                      String sourceType, String sourceId,
                                                                      long sourceRevision,
                                                                      String sourceHash) throws IOException {
        requireProjectMetadataLineageRecoverySourceMutation(coordinator, snapshot, source, sourceType, sourceId,
            sourceRevision, sourceHash, false);
    }

    private void requireProjectMetadataLineageRecoverySourceMutation(AssetTransactionCoordinator coordinator,
                                                                      AssetTransactionCoordinator.Snapshot snapshot,
                                                                      AssetTransactionCoordinator.MutationView source,
                                                                      String sourceType, String sourceId,
                                                                      long sourceRevision, String sourceHash,
                                                                      boolean coupledLineage) throws IOException {
        if (!source.result().project().equals(snapshot.project())) {
            throw new IOException("Source resource mutation is not the current project metadata transition");
        }
        AssetTransactionCoordinator.AssetKey sourceKey = new AssetTransactionCoordinator.AssetKey(sourceType, sourceId);
        AssetTransactionCoordinator.ExpectedState sourceState = snapshot.state(sourceKey)
            .orElseThrow(() -> new IOException("Source resource mutation has no durable asset state"));
        if (sourceState.revision() != sourceRevision
            || !sourceMutationId(source, snapshot, sourceKey).equals(source.mutationId())
            || !source.result().states().getOrDefault(sourceKey, AssetTransactionCoordinator.Missing.INSTANCE).equals(sourceState)) {
            throw new IOException("Source resource mutation does not match its durable asset result");
        }
        Path sourcePath = snapshot.path(sourceKey)
            .orElseThrow(() -> new IOException("Source resource mutation has no durable asset path"));
        JsonObject intent = source.intent();
        JsonObject expectedProject = object(intent, "expectedProject", "Source resource mutation expected project is invalid");
        long expectedRevision = longValue(expectedProject, "revision", "Source resource mutation expected project revision is invalid");
        String expectedHash = stringValue(expectedProject, "hash", "Source resource mutation expected project hash is invalid");
        if (!isCanonicalPayloadHash(expectedHash) || expectedRevision < 0L
            || source.result().project().revision() != Math.addExact(expectedRevision, 1L)) {
            throw new IOException("Source resource mutation project transition is not exactly one revision");
        }
        JsonObject projectBefore = projectMetadataRecoveryProjectBefore(coordinator, snapshot, source, expectedProject);
        Map<AssetTransactionCoordinator.AssetKey, JsonObject> assets = projectMetadataRecoveryAssets(source);
        JsonObject primary = assets.get(sourceKey);
        if (primary == null) {
            throw new IOException("Source resource mutation has no exact primary asset intent");
        }
        String primaryOperation = stringValue(primary, "operation", "Source resource mutation primary operation is invalid");
        JsonObject primaryExpected = object(primary, "expected", "Source resource mutation primary precondition is invalid");
        String expectedKind = stringValue(primaryExpected, "kind", "Source resource mutation primary precondition kind is invalid");
        long primaryExpectedRevision = longValue(primaryExpected, "revision",
            "Source resource mutation primary precondition revision is invalid");
        String primaryExpectedHash = exactStringValue(primaryExpected, "hash",
            "Source resource mutation primary precondition hash is invalid");
        if (primaryExpectedRevision != sourceRevision - 1L
            || ("MISSING".equals(expectedKind) && (primaryExpectedRevision != 0L || !primaryExpectedHash.isEmpty()))
            || (!"MISSING".equals(expectedKind) && !isCanonicalPayloadHash(primaryExpectedHash))) {
            throw new IOException("Source resource mutation primary precondition does not match its revision");
        }
        String sourceRelativePath = projectMetadataRecoveryPath(coordinator, sourcePath);
        if (!sourceRelativePath.equals(stringValue(primary, "path", "Source resource mutation primary path is invalid"))) {
            throw new IOException("Source resource mutation primary path does not match coordinated storage");
        }
        String operation;
        if (sourceState instanceof AssetTransactionCoordinator.Live live && "WRITE".equals(primaryOperation)) {
            operation = switch (expectedKind) {
                case "MISSING", "DELETED" -> "CREATE";
                case "LIVE" -> "SAVE";
                default -> throw new IOException("Source resource mutation primary precondition is unsupported");
            };
            requireProjectMetadataRecoveryLiveSource(snapshot, source, sourceKey, sourcePath, sourceRevision,
                sourceHash, live, primary);
        } else if (sourceState instanceof AssetTransactionCoordinator.Deleted deleted && "DELETE".equals(primaryOperation)
            && "LIVE".equals(expectedKind)) {
            operation = "DELETE";
            requireProjectMetadataRecoveryDeletedSource(snapshot, source, sourceKey, sourcePath, sourceRevision,
                sourceHash, deleted, primary, assets);
        } else {
            throw new IOException("Source resource mutation operation does not match its durable result");
        }
        JsonObject semanticIntent = projectMetadataRecoverySemanticIntent(snapshot, source, sourceType, sourceId,
            sourcePath, operation, assets, coupledLineage);
        JsonArray deltas = array(intent, "projectDeltas", "Source resource mutation project delta is invalid");
        if (deltas.isEmpty() || deltas.size() > 2) {
            throw new IOException("Source resource mutation does not own exact project metadata deltas");
        }
        Set<String> deltaPaths = new LinkedHashSet<>();
        JsonObject metadata = snapshot.metadata().document();
        for (JsonElement element : deltas) {
            if (!element.isJsonObject()) {
                throw new IOException("Source resource mutation does not own exact project metadata deltas");
            }
            JsonObject delta = element.getAsJsonObject();
            JsonArray path = array(delta, "path", "Source resource mutation project delta path is invalid");
            if (path.size() != 1 || !path.get(0).isJsonPrimitive() || !path.get(0).getAsJsonPrimitive().isString()) {
                throw new IOException("Source resource mutation project delta path is invalid");
            }
            String field = path.get(0).getAsString();
            if (!("resources".equals(field) || "folders".equals(field)) || !deltaPaths.add(field)) {
                throw new IOException("Source resource mutation does not own exact project metadata deltas");
            }
            JsonElement current = metadata.get(field);
            if (!"SET".equals(stringValue(delta, "operation", "Source resource mutation project delta operation is invalid"))
                || current == null || !current.isJsonArray() || !current.equals(delta.get("value"))) {
                throw new IOException("Source resource mutation project delta does not match current metadata");
            }
        }
        if (!deltaPaths.contains("resources")) {
            throw new IOException("Source resource mutation does not own one exact resources metadata delta");
        }
        requireProjectMetadataRecoveryEffect(projectBefore, metadata, sourceType, sourceId,
            sourceRelativePath, operation, semanticIntent, deltaPaths);
    }

    private JsonObject projectMetadataRecoveryProjectBefore(AssetTransactionCoordinator coordinator,
                                                             AssetTransactionCoordinator.Snapshot snapshot,
                                                             AssetTransactionCoordinator.MutationView source,
                                                             JsonObject expectedProject) throws IOException {
        Path root = coordinator.canonicalRoot().toAbsolutePath().normalize();
        Path bindingPath = root.resolve(".asset-coordinator").resolve("bindings")
            .resolve(source.mutationId() + ".json").normalize();
        if (!bindingPath.startsWith(root) || Files.isSymbolicLink(bindingPath)
            || !Files.isRegularFile(bindingPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source resource mutation project binding is unavailable");
        }
        JsonObject binding;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(bindingPath));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("binding is not an object");
            }
            binding = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Source resource mutation project binding is invalid", exception);
        }
        JsonObject bindingIntent = object(binding, "intent", "Source resource mutation project binding intent is invalid");
        JsonObject projectBefore = object(binding, "projectBefore", "Source resource mutation prior project metadata is invalid");
        JsonObject projectAfter = object(binding, "projectAfter", "Source resource mutation resulting project metadata is invalid");
        String beforeHash = stringValue(binding, "projectBeforeHash", "Source resource mutation prior project hash is invalid");
        String afterHash = stringValue(binding, "projectAfterHash", "Source resource mutation resulting project hash is invalid");
        AssetProjectMetadata before;
        AssetProjectMetadata after;
        try {
            before = AssetProjectMetadata.of(projectBefore);
            after = AssetProjectMetadata.of(projectAfter);
        } catch (RuntimeException exception) {
            throw new IOException("Source resource mutation project binding metadata is invalid", exception);
        }
        JsonElement projectWritten = binding.get("projectWritten");
        if (!source.mutationId().toString().equals(stringValue(binding, "mutationId",
            "Source resource mutation project binding mutation is invalid"))
            || !source.intentHash().equals(stringValue(binding, "intentHash",
            "Source resource mutation project binding hash is invalid"))
            || !source.intent().equals(bindingIntent) || !before.hash().equals(beforeHash)
            || !after.hash().equals(afterHash) || !expectedProject.get("hash").getAsString().equals(beforeHash)
            || !source.result().project().hash().equals(afterHash)
            || !projectAfter.equals(snapshot.metadata().document())
            || projectWritten == null || !projectWritten.isJsonPrimitive()
            || !projectWritten.getAsJsonPrimitive().isBoolean() || !projectWritten.getAsBoolean()) {
            throw new IOException("Source resource mutation project binding does not match durable history");
        }
        return projectBefore.deepCopy();
    }

    private Map<AssetTransactionCoordinator.AssetKey, JsonObject> projectMetadataRecoveryAssets(
        AssetTransactionCoordinator.MutationView source) throws IOException {
        JsonArray assetArray = array(source.intent(), "assets", "Source resource mutation asset intent is invalid");
        Map<AssetTransactionCoordinator.AssetKey, JsonObject> assets = new LinkedHashMap<>();
        for (JsonElement element : assetArray) {
            if (!element.isJsonObject()) {
                throw new IOException("Source resource mutation asset intent is invalid");
            }
            JsonObject asset = element.getAsJsonObject();
            AssetTransactionCoordinator.AssetKey key;
            try {
                key = new AssetTransactionCoordinator.AssetKey(
                    stringValue(asset, "type", "Source resource mutation asset type is invalid"),
                    stringValue(asset, "id", "Source resource mutation asset ID is invalid"));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Source resource mutation asset identity is invalid", exception);
            }
            if (assets.putIfAbsent(key, asset) != null) {
                throw new IOException("Source resource mutation repeats an asset identity");
            }
        }
        if (!assets.keySet().equals(source.result().states().keySet())) {
            throw new IOException("Source resource mutation asset intent does not match its durable result");
        }
        return Map.copyOf(assets);
    }

    private void requireProjectMetadataRecoveryLiveSource(AssetTransactionCoordinator.Snapshot snapshot,
                                                          AssetTransactionCoordinator.MutationView source,
                                                          AssetTransactionCoordinator.AssetKey sourceKey,
                                                          Path sourcePath, long sourceRevision, String sourceHash,
                                                          AssetTransactionCoordinator.Live live,
                                                          JsonObject primary) throws IOException {
        if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)
            || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(sourcePath)))
            || AssetFileFormat.readRevision(sourcePath) != sourceRevision
            || !source.mutationId().toString().equals(AssetFileFormat.readMutationId(sourcePath))
            || !sourceHash.equals(projectMetadataRecoverySourceHash(sourcePath))
            || !live.hash().equals(stringValue(primary, "payloadHash",
            "Source resource mutation primary payload hash is invalid"))) {
            throw new IOException("Source resource mutation bytes do not match the pending durable result");
        }
        requireProjectMetadataRecoveryAssetLineage(snapshot, source, sourceKey, live);
    }

    private void requireProjectMetadataRecoveryDeletedSource(AssetTransactionCoordinator.Snapshot snapshot,
                                                             AssetTransactionCoordinator.MutationView source,
                                                             AssetTransactionCoordinator.AssetKey sourceKey,
                                                             Path sourcePath, long sourceRevision, String sourceHash,
                                                             AssetTransactionCoordinator.Deleted deleted,
                                                             JsonObject primary,
                                                             Map<AssetTransactionCoordinator.AssetKey, JsonObject> assets) throws IOException {
        if (Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)
            || !exactStringValue(primary, "payloadHash", "Source resource mutation primary payload hash is invalid").isEmpty()) {
            throw new IOException("Deleted source resource mutation retains live primary bytes");
        }
        requireProjectMetadataRecoveryAssetLineage(snapshot, source, sourceKey, deleted);
        AssetTransactionCoordinator.AssetKey tombstoneKey = new AssetTransactionCoordinator.AssetKey(
            sourceKey.type() + ".tombstone", sourceKey.id());
        JsonObject tombstoneAsset = assets.get(tombstoneKey);
        AssetTransactionCoordinator.ExpectedState tombstoneState = snapshot.state(tombstoneKey).orElse(null);
        if (tombstoneAsset == null || !(tombstoneState instanceof AssetTransactionCoordinator.Live tombstoneLive)
            || !"WRITE".equals(stringValue(tombstoneAsset, "operation",
            "Source resource mutation tombstone operation is invalid"))
            || !tombstoneLive.hash().equals(stringValue(tombstoneAsset, "payloadHash",
            "Source resource mutation tombstone result hash is invalid"))) {
            throw new IOException("Deleted source resource mutation has no exact tombstone result");
        }
        requireProjectMetadataRecoveryAssetLineage(snapshot, source, tombstoneKey, tombstoneLive);
        Path tombstonePath = snapshot.path(tombstoneKey)
            .orElseThrow(() -> new IOException("Deleted source resource mutation has no tombstone path"));
        if (!Files.isRegularFile(tombstonePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Deleted source resource mutation tombstone is unavailable");
        }
        byte[] bytes = Files.readAllBytes(tombstonePath);
        JsonObject tombstone;
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("tombstone is not an object");
            }
            tombstone = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Deleted source resource mutation tombstone is invalid", exception);
        }
        JsonElement deletedValue = tombstone.get("deleted");
        if (!tombstoneLive.hash().equals(StorageSafety.sha256(bytes))
            || !sourceKey.type().equals(stringValue(tombstone, "type", "Source resource mutation tombstone type is invalid"))
            || !sourceKey.id().equals(stringValue(tombstone, "id", "Source resource mutation tombstone ID is invalid"))
            || AssetFileFormat.readRevision(tombstonePath) != sourceRevision
            || !source.mutationId().toString().equals(AssetFileFormat.readMutationId(tombstonePath))
            || !sourceHash.equals(stringValue(tombstone, "payloadHash",
            "Source resource mutation tombstone payload hash is invalid"))
            || deletedValue == null || !deletedValue.isJsonPrimitive()
            || !deletedValue.getAsJsonPrimitive().isBoolean() || !deletedValue.getAsBoolean()) {
            throw new IOException("Deleted source resource mutation tombstone does not match its durable result");
        }
    }

    private void requireProjectMetadataRecoveryAssetLineage(AssetTransactionCoordinator.Snapshot snapshot,
                                                            AssetTransactionCoordinator.MutationView source,
                                                            AssetTransactionCoordinator.AssetKey key,
                                                            AssetTransactionCoordinator.ExpectedState state) throws IOException {
        if (!state.equals(source.result().states().get(key))
            || !source.mutationId().toString().equals(snapshot.mutationValue(key).orElse(""))) {
            throw new IOException("Source resource mutation sidecar does not match durable lineage");
        }
    }

    private JsonObject projectMetadataRecoverySemanticIntent(AssetTransactionCoordinator.Snapshot snapshot,
                                                             AssetTransactionCoordinator.MutationView source,
                                                             String sourceType, String sourceId, Path sourcePath,
                                                             String operation,
                                                             Map<AssetTransactionCoordinator.AssetKey, JsonObject> assets,
                                                             boolean coupledLineage) throws IOException {
        AssetTransactionCoordinator.AssetKey sourceKey = new AssetTransactionCoordinator.AssetKey(sourceType, sourceId);
        AssetTransactionCoordinator.AssetKey intentKey = new AssetTransactionCoordinator.AssetKey(sourceType + ".intent", sourceId);
        AssetTransactionCoordinator.AssetKey tombstoneKey = new AssetTransactionCoordinator.AssetKey(sourceType + ".tombstone", sourceId);
        AssetTransactionCoordinator.AssetKey lineageKey = projectMetadataLineageKey();
        for (AssetTransactionCoordinator.AssetKey key : assets.keySet()) {
            if (!key.equals(sourceKey) && !key.equals(intentKey) && !key.equals(tombstoneKey)
                && !(coupledLineage && key.equals(lineageKey)) && !"blob".equals(key.type())) {
                throw new IOException("Source resource mutation includes an unrelated resource asset");
            }
        }
        JsonObject intentAsset = assets.get(intentKey);
        AssetTransactionCoordinator.ExpectedState intentState = snapshot.state(intentKey).orElse(null);
        if (intentAsset == null || !(intentState instanceof AssetTransactionCoordinator.Live intentLive)
            || !"WRITE".equals(stringValue(intentAsset, "operation",
            "Source resource mutation semantic intent operation is invalid"))
            || !intentLive.hash().equals(stringValue(intentAsset, "payloadHash",
            "Source resource mutation semantic intent result hash is invalid"))) {
            throw new IOException("Source resource mutation has no exact semantic intent");
        }
        requireProjectMetadataRecoveryAssetLineage(snapshot, source, intentKey, intentLive);
        Path intentPath = snapshot.path(intentKey)
            .orElseThrow(() -> new IOException("Source resource mutation semantic intent has no durable path"));
        if (!Files.isRegularFile(intentPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source resource mutation semantic intent is unavailable");
        }
        byte[] bytes = Files.readAllBytes(intentPath);
        if (!intentLive.hash().equals(StorageSafety.sha256(bytes))) {
            throw new IOException("Source resource mutation semantic intent bytes are not authoritative");
        }
        JsonObject semanticIntent;
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("semantic intent is not an object");
            }
            semanticIntent = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Source resource mutation semantic intent is invalid", exception);
        }
        String semanticOperation = stringValue(semanticIntent, "operation",
            "Source resource mutation semantic operation is invalid");
        String semanticType = stringValue(semanticIntent, "type", "Source resource mutation semantic type is invalid");
        if (!sourceType.equals(semanticType)
            || !("DELETE".equals(operation) ? "delete".equals(semanticOperation) : "save".equals(semanticOperation))) {
            throw new IOException("Source resource mutation semantic intent does not match its primary operation");
        }
        if ("DELETE".equals(operation)) {
            if (!semanticIntent.keySet().equals(Set.of("operation", "type"))) {
                throw new IOException("Source resource delete semantic intent fields are invalid");
            }
            return semanticIntent;
        }
        JsonObject tombstoneAsset = assets.get(tombstoneKey);
        if (tombstoneAsset != null) {
            AssetTransactionCoordinator.ExpectedState tombstoneState = snapshot.state(tombstoneKey).orElse(null);
            if (!(tombstoneState instanceof AssetTransactionCoordinator.Deleted tombstoneDeleted)
                || !"DELETE".equals(stringValue(tombstoneAsset, "operation",
                "Source resource mutation retired tombstone operation is invalid"))) {
                throw new IOException("Source resource mutation retired tombstone result is invalid");
            }
            requireProjectMetadataRecoveryAssetLineage(snapshot, source, tombstoneKey, tombstoneDeleted);
        }
        Set<String> expectedFields = semanticIntent.has("presentation")
            ? Set.of("operation", "type", "id", "payloadHash", "auxiliaryHash", "presentation")
            : Set.of("operation", "type", "id", "payloadHash", "auxiliaryHash");
        if (!semanticIntent.keySet().equals(expectedFields)
            || !sourceId.equals(stringValue(semanticIntent, "id", "Source resource mutation semantic ID is invalid"))
            || !isCanonicalPayloadHash(stringValue(semanticIntent, "payloadHash",
            "Source resource mutation semantic payload hash is invalid"))) {
            throw new IOException("Source resource save semantic intent fields are invalid");
        }
        for (Map.Entry<AssetTransactionCoordinator.AssetKey, JsonObject> entry : assets.entrySet()) {
            if (!"blob".equals(entry.getKey().type())) {
                continue;
            }
            JsonObject asset = entry.getValue();
            AssetTransactionCoordinator.ExpectedState blobState = snapshot.state(entry.getKey()).orElse(null);
            if (!(blobState instanceof AssetTransactionCoordinator.Live blobLive)
                || !"WRITE".equals(stringValue(asset, "operation", "Source resource mutation auxiliary operation is invalid"))
                || !blobLive.hash().equals(stringValue(asset, "payloadHash",
                "Source resource mutation auxiliary payload hash is invalid"))) {
                throw new IOException("Source resource mutation auxiliary asset operation is invalid");
            }
            requireProjectMetadataRecoveryAssetLineage(snapshot, source, entry.getKey(), blobLive);
            stringValue(asset, "path", "Source resource mutation auxiliary path is invalid");
        }
        String auxiliaryHash = projectMetadataRecoveryAuxiliaryHash(sourcePath);
        if (!auxiliaryHash.equals(exactStringValue(semanticIntent, "auxiliaryHash",
            "Source resource mutation semantic auxiliary hash is invalid"))) {
            throw new IOException("Source resource mutation semantic auxiliary hash does not match its durable assets");
        }
        return semanticIntent;
    }

    private String projectMetadataRecoveryAuxiliaryHash(Path sourcePath) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(sourcePath));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("source payload is not an object");
            }
            JsonElement value = parsed.getAsJsonObject().get("assetAuxiliaryHash");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("source auxiliary hash is unavailable");
            }
            String hash = value.getAsString();
            if (!hash.isEmpty() && !isCanonicalPayloadHash(hash)) {
                throw new IllegalArgumentException("source auxiliary hash is invalid");
            }
            return hash;
        } catch (RuntimeException exception) {
            throw new IOException("Source resource mutation auxiliary hash is invalid", exception);
        }
    }

    private String projectMetadataRecoveryPath(AssetTransactionCoordinator coordinator, Path sourcePath) throws IOException {
        Path root = coordinator.canonicalRoot().toAbsolutePath().normalize();
        Path normalized = sourcePath.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IOException("Source resource mutation path is outside coordinated storage");
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    private void requireProjectMetadataRecoveryEffect(JsonObject projectBefore, JsonObject projectAfter,
                                                      String sourceType, String sourceId, String sourcePath,
                                                      String operation, JsonObject semanticIntent,
                                                      Set<String> deltaPaths) throws IOException {
        JsonArray beforeResources = projectMetadataArray(projectBefore, "resources");
        JsonArray beforeFolders = projectMetadataArray(projectBefore, "folders");
        JsonArray resources = beforeResources.deepCopy();
        JsonArray folders = beforeFolders.deepCopy();
        List<JsonObject> matches = projectMetadataResourceMatches(resources, sourceType, sourceId);
        if (("CREATE".equals(operation) && !matches.isEmpty())
            || ("SAVE".equals(operation) && matches.size() != 1)
            || ("DELETE".equals(operation) && matches.size() != 1)) {
            throw new IOException("Source resource mutation presentation precondition is invalid for " + operation);
        }
        resources.asList().removeIf(element -> element.isJsonObject()
            && sourceType.equals(projectMetadataText(element.getAsJsonObject(), "type"))
            && sourceId.equals(projectMetadataText(element.getAsJsonObject(), "id")));
        if (!"DELETE".equals(operation)) {
            JsonObject resource = matches.isEmpty() ? new JsonObject() : matches.getFirst().deepCopy();
            resource.addProperty("type", sourceType);
            resource.addProperty("id", sourceId);
            resource.addProperty("path", sourcePath);
            if (semanticIntent.has("presentation")) {
                JsonObject presentation = object(semanticIntent, "presentation",
                    "Source resource mutation presentation intent is invalid");
                if (!presentation.keySet().equals(Set.of("displayName", "path", "sortOrder"))
                    || !sourcePath.equals(stringValue(presentation, "path",
                    "Source resource mutation presentation path is invalid"))) {
                    throw new IOException("Source resource mutation presentation intent does not match its asset path");
                }
                applyProjectMetadataPresentation(resource, presentation, "displayName");
                applyProjectMetadataPresentation(resource, presentation, "sortOrder");
            }
            ensureProjectMetadataRecoveryFolders(folders, sourcePath);
            resources.add(resource);
        }
        JsonObject expected = projectBefore.deepCopy();
        expected.add("resources", resources);
        if (!beforeFolders.equals(folders)) {
            expected.add("folders", folders);
        }
        if (!expected.equals(projectAfter)) {
            throw new IOException("Source resource mutation project metadata transition includes unrelated presentation changes");
        }
        Set<String> expectedDeltas = new LinkedHashSet<>();
        if (!beforeResources.equals(resources)) {
            expectedDeltas.add("resources");
        }
        if (!beforeFolders.equals(folders)) {
            expectedDeltas.add("folders");
        }
        if (!expectedDeltas.equals(deltaPaths)) {
            throw new IOException("Source resource mutation project deltas do not match its presentation effect");
        }
    }

    private JsonArray projectMetadataArray(JsonObject metadata, String field) throws IOException {
        JsonElement value = metadata.get(field);
        if (value == null || value.isJsonNull()) {
            return new JsonArray();
        }
        if (!value.isJsonArray()) {
            throw new IOException("Source resource mutation project metadata " + field + " is invalid");
        }
        return value.getAsJsonArray().deepCopy();
    }

    private List<JsonObject> projectMetadataResourceMatches(JsonArray resources, String sourceType,
                                                            String sourceId) throws IOException {
        List<JsonObject> matches = resources.asList().stream().filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .filter(resource -> sourceType.equals(projectMetadataText(resource, "type"))
                && sourceId.equals(projectMetadataText(resource, "id")))
            .toList();
        if (matches.size() > 1) {
            throw new IOException("Source resource mutation project metadata repeats its typed identity");
        }
        return matches;
    }

    private String projectMetadataText(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : "";
    }

    private void applyProjectMetadataPresentation(JsonObject resource, JsonObject presentation, String field) {
        JsonElement value = presentation.get(field);
        if (value != null && !value.isJsonNull()) {
            resource.add(field, value.deepCopy());
        }
    }

    private void ensureProjectMetadataRecoveryFolders(JsonArray folders, String resourcePath) throws IOException {
        int separator = resourcePath.lastIndexOf('/');
        if (separator < 1) {
            return;
        }
        String parent = "";
        for (String part : resourcePath.substring(0, separator).split("/")) {
            String path = parent.isBlank() ? part : parent + '/' + part;
            List<JsonObject> matches = folders.asList().stream().filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject).filter(folder -> path.equals(projectMetadataText(folder, "path"))).toList();
            if (matches.size() > 1) {
                throw new IOException("Source resource mutation project metadata repeats a folder path");
            }
            if (matches.isEmpty()) {
                JsonObject folder = new JsonObject();
                folder.addProperty("path", path);
                folder.addProperty("parentPath", parent);
                folder.addProperty("name", part);
                folder.addProperty("sortOrder", 0);
                folder.addProperty("collapsed", false);
                folders.add(folder);
            }
            parent = path;
        }
    }

    private UUID sourceMutationId(AssetTransactionCoordinator.MutationView source,
                                  AssetTransactionCoordinator.Snapshot snapshot,
                                  AssetTransactionCoordinator.AssetKey sourceKey) throws IOException {
        String value = snapshot.mutationValue(sourceKey)
            .orElseThrow(() -> new IOException("Source resource mutation has no coordinator lineage"));
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Source resource mutation coordinator lineage is invalid", exception);
        }
    }

    private void requireUnreceiptedWorldGenScope(AssetTransactionCoordinator.Snapshot snapshot,
                                                  AssetTransactionCoordinator.MutationView source,
                                                  JsonObject scope,
                                                  AssetTransactionCoordinator.AssetKey sourceKey,
                                                  AssetTransactionCoordinator.AssetKey intentKey,
                                                  Path sourcePath, String sourceId, UUID sourceMutationId,
                                                  long expectedRevision, String sourceHash,
                                                  boolean coupledLineage) throws IOException {
        Set<String> fields = Set.of("actorClientId", "requestId", "mutationId", "operationId", "action",
            "resourceId", "expectedRevision", "payload", "worldGenIntentHash");
        if (!scope.keySet().equals(fields)) {
            throw new IOException("Current WorldGen project metadata transition scope is incomplete");
        }
        String actorClientId = stringValue(scope, "actorClientId", "Current WorldGen actor is invalid");
        String requestId = stringValue(scope, "requestId", "Current WorldGen request is invalid");
        String operationId = stringValue(scope, "operationId", "Current WorldGen operation is invalid");
        String intentHash = stringValue(scope, "worldGenIntentHash", "Current WorldGen intent hash is invalid");
        JsonObject payload = object(scope, "payload", "Current WorldGen payload is invalid");
        String expectedOperation = "worldGenProjectSave:" + sourceId + ":" + sourceMutationId;
        if (actorClientId.isBlank() || !expectedOperation.equals(requestId) || !expectedOperation.equals(operationId)
            || !isCanonicalPayloadHash(intentHash)
            || !sourceId.equals(stringValue(payload, "id", "Current WorldGen payload ID is invalid"))) {
            throw new IOException("Current WorldGen project metadata transition identity is invalid");
        }
        Map<String, Object> hashInput = new LinkedHashMap<>();
        hashInput.put("actorClientId", actorClientId);
        hashInput.put("requestId", requestId);
        hashInput.put("mutationId", sourceMutationId.toString());
        hashInput.put("operationId", operationId);
        hashInput.put("action", "worldGenProjectSave");
        hashInput.put("resourceId", sourceId);
        hashInput.put("expectedRevision", expectedRevision);
        hashInput.put("data", CanonicalJson.parseOpaque(payload.toString()));
        if (!intentHash.equals(CanonicalJson.sha256("worldgen-mutation", hashInput))) {
            throw new IOException("Current WorldGen project metadata transition intent hash is invalid");
        }
        JsonObject sourcePayload;
        try {
            sourcePayload = JsonAssetStore.logicalPayload(JsonParser.parseString(Files.readString(sourcePath)).getAsJsonObject());
        } catch (RuntimeException exception) {
            throw new IOException("Current WorldGen project metadata transition payload is invalid", exception);
        }
        if (!sourcePayload.equals(payload)
            || !sourceHash.equals(ResourcePayloadCodecs.json().hashPayload(gson.fromJson(payload, Map.class)).canonicalText())) {
            throw new IOException("Current WorldGen scope payload does not match its durable asset");
        }
        AssetTransactionCoordinator.ExpectedState intentState = snapshot.state(intentKey)
            .orElseThrow(() -> new IOException("Current WorldGen transition has no durable semantic intent"));
        if (!(intentState instanceof AssetTransactionCoordinator.Live intentLive)
            || !intentState.equals(source.result().states().get(intentKey))
            || !sourceMutationId.toString().equals(snapshot.mutationValue(intentKey).orElse(""))) {
            throw new IOException("Current WorldGen semantic intent does not match its durable result");
        }
        Path intentPath = snapshot.path(intentKey)
            .orElseThrow(() -> new IOException("Current WorldGen semantic intent has no durable path"));
        byte[] intentBytes = Files.readAllBytes(intentPath);
        if (!Files.isRegularFile(intentPath, LinkOption.NOFOLLOW_LINKS)
            || !intentLive.hash().equals(StorageSafety.sha256(intentBytes))) {
            throw new IOException("Current WorldGen semantic intent bytes are not authoritative");
        }
        JsonObject semanticIntent;
        try {
            semanticIntent = JsonParser.parseString(new String(intentBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Current WorldGen semantic intent is invalid", exception);
        }
        JsonElement auxiliaryHash = semanticIntent.get("auxiliaryHash");
        if (!semanticIntent.keySet().equals(Set.of("operation", "type", "id", "payloadHash", "auxiliaryHash"))) {
            throw new IOException("Current WorldGen semantic intent fields are invalid");
        }
        if (!"save".equals(stringValue(semanticIntent, "operation", "Current WorldGen semantic operation is invalid"))
            || !ReSyncResourceCatalog.WORLDGEN.equals(stringValue(semanticIntent, "type", "Current WorldGen semantic type is invalid"))
            || !sourceId.equals(stringValue(semanticIntent, "id", "Current WorldGen semantic ID is invalid"))) {
            throw new IOException("Current WorldGen semantic intent identity is invalid");
        }
        String semanticPayloadHash = stringValue(semanticIntent, "payloadHash",
            "Current WorldGen semantic payload hash is invalid");
        String expectedSemanticPayloadHash;
        String durableSemanticPayloadHash;
        if (coupledLineage) {
            try {
                WorldGenProject project = WorldGenSerializer.deserializeProject(payload.toString());
                project.rebuildIndices();
                JsonObject serialized = JsonParser.parseString(WorldGenSerializer.serializeProjectOwned(project)).getAsJsonObject();
                expectedSemanticPayloadHash = StorageSafety.sha256(serialized.toString().getBytes(StandardCharsets.UTF_8));
                durableSemanticPayloadHash = StorageSafety.sha256(
                    WorldGenSerializer.ownedProjectPayload(sourcePayload).toString().getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException exception) {
                throw new IOException("Current WorldGen semantic intent payload cannot be normalized", exception);
            }
        } else {
            expectedSemanticPayloadHash = StorageSafety.sha256(sourcePayload.toString().getBytes(StandardCharsets.UTF_8));
            durableSemanticPayloadHash = expectedSemanticPayloadHash;
        }
        if (!expectedSemanticPayloadHash.equals(semanticPayloadHash)
            && !durableSemanticPayloadHash.equals(semanticPayloadHash)) {
            throw new IOException("Current WorldGen semantic intent payload hash does not match its scope");
        }
        if (auxiliaryHash == null || !auxiliaryHash.isJsonPrimitive()
            || !auxiliaryHash.getAsJsonPrimitive().isString() || !auxiliaryHash.getAsString().isEmpty()) {
            throw new IOException("Current WorldGen semantic intent auxiliary hash is invalid");
        }
        if (!sourceMutationId.equals(sourceMutationId(source, snapshot, sourceKey))) {
            throw new IOException("Current WorldGen semantic intent mutation lineage is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private String projectMetadataRecoverySourceHash(Path sourcePath) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(sourcePath));
            if (!parsed.isJsonObject()) {
                throw new IOException("Source resource mutation payload is not a JSON object");
            }
            JsonObject payload = JsonAssetStore.logicalPayload(parsed.getAsJsonObject());
            return ResourcePayloadCodecs.json().hashPayload(gson.fromJson(payload, Map.class)).canonicalText();
        } catch (RuntimeException exception) {
            throw new IOException("Source resource mutation payload is invalid", exception);
        }
    }

    private ResourceIdentity projectMetadataLineageRepairReplay(AssetTransactionCoordinator coordinator,
                                                                 AssetTransactionCoordinator.Snapshot snapshot,
                                                                 AssetTransactionCoordinator.MutationView source,
                                                                 UUID repairMutationId,
                                                                 String sourceType, String sourceId,
                                                                 long sourceRevision, String sourceHash,
                                                                 long previousMetadataRevision,
                                                                 UUID previousMetadataMutationId,
                                                                 String previousMetadataHash,
                                                                 String payloadHash) throws IOException {
        Optional<AssetTransactionCoordinator.MutationView> existing = coordinator.mutation(repairMutationId);
        if (existing.isEmpty()) {
            return null;
        }
        requireProjectMetadataLineageRecoverySourceMutation(coordinator, snapshot, source, sourceType, sourceId,
            sourceRevision, sourceHash);
        AssetTransactionCoordinator.MutationView repair = existing.get();
        JsonObject expectedScope = projectMetadataLineageRepairScope(source, sourceType, sourceId, sourceRevision,
            sourceHash, previousMetadataRevision, previousMetadataMutationId, previousMetadataHash,
            source.result().project());
        JsonObject intent = repair.intent();
        if (!intent.has("scope") || !intent.get("scope").isJsonObject()
            || !expectedScope.equals(intent.getAsJsonObject("scope"))
            || !repair.result().project().equals(source.result().project())
            || !repair.result().project().equals(snapshot.project())
            || !array(intent, "projectDeltas", "Project metadata lineage repair project delta is invalid").isEmpty()) {
            throw new IOException("Project metadata lineage repair history does not match its source mutation");
        }
        JsonArray assets = array(intent, "assets", "Project metadata lineage repair asset intent is invalid");
        if (assets.size() != 1 || !assets.get(0).isJsonObject()) {
            throw new IOException("Project metadata lineage repair does not own one exact asset");
        }
        JsonObject asset = assets.get(0).getAsJsonObject();
        AssetTransactionCoordinator.AssetKey lineageKey = projectMetadataLineageKey();
        if (!lineageKey.type().equals(stringValue(asset, "type", "Project metadata lineage repair type is invalid"))
            || !lineageKey.id().equals(stringValue(asset, "id", "Project metadata lineage repair ID is invalid"))
            || !"WRITE".equals(stringValue(asset, "operation", "Project metadata lineage repair operation is invalid"))) {
            throw new IOException("Project metadata lineage repair asset identity is invalid");
        }
        AssetTransactionCoordinator.ExpectedState state = snapshot.state(lineageKey)
            .orElseThrow(() -> new IOException("Project metadata lineage repair state is missing"));
        if (!state.equals(repair.result().states().get(lineageKey))
            || !repairMutationId.toString().equals(snapshot.mutationValue(lineageKey).orElse(""))) {
            throw new IOException("Project metadata lineage repair state does not match durable history");
        }
        ResourceIdentity metadata = projectMetadataIdentity(snapshot, projectMetadataResourceId());
        long revision = Math.addExact(previousMetadataRevision, 1L);
        if (metadata == null || metadata.deleted() || metadata.revision() != revision
            || !metadata.mutationId().equals(repairMutationId.toString()) || !metadata.payloadHash().equals(payloadHash)) {
            throw new IOException("Project metadata lineage repair identity is not authoritative");
        }
        return metadata;
    }

    private JsonObject projectMetadataLineageRepairScope(AssetTransactionCoordinator.MutationView source,
                                                          String sourceType, String sourceId,
                                                          long sourceRevision, String sourceHash,
                                                          long previousMetadataRevision,
                                                          UUID previousMetadataMutationId,
                                                          String previousMetadataHash,
                                                          AssetTransactionCoordinator.ExpectedProject projectAfter) {
        JsonObject expectedProject = source.intent().getAsJsonObject("expectedProject");
        JsonObject scope = new JsonObject();
        scope.addProperty("format", "project-metadata-lineage-repair-v1");
        scope.addProperty("sourceMutationId", source.mutationId().toString());
        scope.addProperty("sourceIntentHash", source.intentHash());
        scope.addProperty("sourceType", sourceType);
        scope.addProperty("sourceId", sourceId);
        scope.addProperty("sourceRevision", sourceRevision);
        scope.addProperty("sourceHash", sourceHash);
        scope.addProperty("previousMetadataRevision", previousMetadataRevision);
        scope.addProperty("previousMetadataMutationId", previousMetadataMutationId.toString());
        scope.addProperty("previousMetadataHash", previousMetadataHash);
        scope.addProperty("projectBeforeRevision", expectedProject.get("revision").getAsLong());
        scope.addProperty("projectBeforeHash", expectedProject.get("hash").getAsString());
        scope.addProperty("projectAfterRevision", projectAfter.revision());
        scope.addProperty("projectAfterHash", projectAfter.hash());
        return scope;
    }

    private JsonObject object(JsonObject owner, String field, String message) throws IOException {
        if (owner == null || !owner.has(field) || !owner.get(field).isJsonObject()) {
            throw new IOException(message);
        }
        return owner.getAsJsonObject(field);
    }

    private JsonArray array(JsonObject owner, String field, String message) throws IOException {
        if (owner == null || !owner.has(field) || !owner.get(field).isJsonArray()) {
            throw new IOException(message);
        }
        return owner.getAsJsonArray(field);
    }

    private String stringValue(JsonObject owner, String field, String message) throws IOException {
        try {
            String value = owner.get(field).getAsString();
            if (value == null || value.isBlank()) {
                throw new IOException(message);
            }
            return value;
        } catch (NullPointerException | UnsupportedOperationException | ClassCastException | IllegalStateException exception) {
            throw new IOException(message, exception);
        }
    }

    private String exactStringValue(JsonObject owner, String field, String message) throws IOException {
        try {
            JsonElement element = owner.get(field);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IOException(message);
            }
            return element.getAsString();
        } catch (NullPointerException | UnsupportedOperationException | ClassCastException | IllegalStateException exception) {
            throw new IOException(message, exception);
        }
    }

    private long longValue(JsonObject owner, String field, String message) throws IOException {
        try {
            return owner.get(field).getAsLong();
        } catch (NullPointerException | UnsupportedOperationException | ClassCastException | IllegalStateException exception) {
            throw new IOException(message, exception);
        }
    }

    private String requiredProjectMetadataString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalStateException("Project metadata lineage is missing " + field);
        }
        String value = object.get(field).getAsString();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Project metadata lineage has an empty " + field);
        }
        return value;
    }

    private void requireProjectMetadataIdentity(String resourceId, long revision, UUID mutationId,
                                                String payloadHash, boolean deleted) {
        ResourceIdentity identity = projectMetadataIdentity(requireAssetTransactions().read(Function.identity()), resourceId);
        if (identity == null || identity.revision() != revision || !mutationId.toString().equals(identity.mutationId())
            || !payloadHash.equals(identity.payloadHash()) || identity.deleted() != deleted) {
            throw new IllegalStateException("Project metadata mutation did not publish exact durable lineage");
        }
    }

    private void ensureValidGraphTombstone(String type, String id) {
        Path tombstoneFile = graphTombstoneFile(type, id);
        if (Files.isRegularFile(tombstoneFile) && readGraphTombstoneIdentity(type, id, tombstoneFile) == null) {
            throw new IllegalStateException("Invalid graph tombstone: " + type + ':' + id);
        }
    }

    private boolean hasGraphTombstone(String type, String id) {
        return GRAPH_TYPES.contains(type)
            && Files.isRegularFile(graphTombstoneFile(type, id), LinkOption.NOFOLLOW_LINKS);
    }

    private Path findStoredGraphAssetFile(String type, String id) {
        if (!GRAPH_TYPES.contains(type) || id == null || id.isBlank() || !Files.isDirectory(assetsDir.toPath())) {
            return null;
        }
        Path known = knownAssetResourceFile(type, id);
        if (known != null) {
            return known;
        }
        Path indexed = assetFiles().get(assetIndexKey(type, id));
        if (indexed != null && Files.isRegularFile(indexed) && type.equals(AssetFileFormat.readResourceType(indexed))) {
            return indexed;
        }
        return null;
    }

    private String metadataWithoutResource(String type, String id) {
        ProjectMetadataSnapshot metadata = parseProjectMetadata(getProjectMetadata("project"));
        if (metadata == null || !metadata.mutableResources().removeIf(resource -> resource != null && type.equals(resource.type) && id.equals(resource.id))) {
            return null;
        }
        return serializeProjectMetadata(metadata);
    }

    private List<Path> graphAssetFiles(String type, String id) throws IOException {
        AssetTransactionCoordinator.Snapshot snapshot = requireAssetTransactions().read(Function.identity());
        AssetTransactionCoordinator.AssetKey key = assetKey(type, id);
        if (!(snapshot.state(key).orElse(AssetTransactionCoordinator.Missing.INSTANCE)
            instanceof AssetTransactionCoordinator.Live)) {
            return List.of();
        }
        Path path = snapshot.path(key).orElse(null);
        if (path == null) {
            throw new IOException("Live coordinated graph has no canonical path: " + type + ':' + id);
        }
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || isDurabilityInternalPath(root, normalized)) {
            throw new IOException("Live coordinated graph path escapes the asset root: " + type + ':' + id);
        }
        return List.of(normalized);
    }

    private void quarantineLegacyGraphFile(String type, String id, Path verifiedLegacyFile) throws IOException {
        File legacyDirectory = GRAPH_TYPES.contains(type) ? flowDir : null;
        if (legacyDirectory == null || !legacyDirectory.exists()) {
            return;
        }
        if (!legacyRuntimeGate.allowsLegacyFallback()) {
            Path legacyFile = jsonFile(legacyDirectory, id, "delete " + type);
            if (legacyFile != null && Files.exists(legacyFile)) {
                throw new IOException("Legacy graph deletion is disabled: " + type + ':' + id);
            }
            return;
        }
        Path legacyFile = verifiedLegacyFile != null ? verifiedLegacyFile : jsonFile(legacyDirectory, id, "delete " + type);
        if (legacyFile == null || !Files.exists(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Legacy graph deletion source is not a regular file: " + type + ':' + id);
        }
        Path legacyQuarantine = assetsDir.toPath().getParent().resolve("legacy-quarantine").resolve("deletes")
            .resolve(UUID.randomUUID().toString())
            .resolve("legacy").resolve(type).resolve(legacyFile.getFileName());
        Files.createDirectories(legacyQuarantine.getParent());
        Files.move(legacyFile, legacyQuarantine);
        StorageSafety.forceDirectory(legacyQuarantine.getParent());
    }

    private void quarantineLegacyGraphAfterCommit(String type, String id, LegacyGraphDeleteSource source) {
        if (source == null) {
            return;
        }
        try {
            quarantineLegacyGraphFile(type, id, source.path());
        } catch (IOException | RuntimeException quarantineFailure) {
            Log.error("Committed graph delete could not quarantine the legacy payload: "
                + type + ':' + id, quarantineFailure);
        }
    }

    private Path safeAssetFolder(String path) {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path result = root;
        String normalized = normalizeAssetPath(path);
        if (!normalized.isBlank()) {
            for (String part : normalized.split("/")) {
                result = result.resolve(part);
            }
        }
        Path target = result.normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Unsafe assets folder: " + path);
        }
        return target;
    }

    private String normalizeAssetPath(String path) {
        String normalized = path != null ? path.replace('\\', '/').replaceAll("/+", "/").trim() : "";
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Unsafe assets path: " + path);
        }
        return normalized;
    }

    private String lastAssetSegment(String path) {
        String normalized = normalizeAssetPath(path);
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private void deleteResourceFiles(File legacyDirectory, String type, String id, UUID mutationId,
                                     long expectedRevision, boolean enforceExpectedRevision) throws IOException {
        Objects.requireNonNull(mutationId, "Mutation ID is required");
        Path legacyFile = null;
        if (legacyDirectory.exists()) {
            Path candidate = jsonFile(legacyDirectory, id, "delete " + type);
            if (candidate != null && Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (!legacyRuntimeGate.allowsLegacyFallback()) {
                    throw new IOException("Legacy resource deletion is disabled: " + type + ':' + id);
                }
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Legacy resource deletion source is not a regular file: " + type + ':' + id);
                }
                legacyFile = candidate;
            }
        }
        Path assetFile = findAssetResourceFile(type, id);
        AssetTransactionCoordinator coordinator = requireAssetTransactions();
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.ExpectedState current = snapshot.state(assetKey(type, id))
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        if (enforceExpectedRevision && current.revision() != expectedRevision) {
            throw new ResourceRevisionConflictException(id, expectedRevision, current.revision());
        }
        if (current instanceof AssetTransactionCoordinator.Missing) {
            if (assetFile != null) {
                throw new IOException("Uncoordinated asset cannot be deleted: " + type + ':' + id);
            }
            if (snapshot.state(tombstoneKey(type, id)).isPresent()) {
                throw new IOException("Resource tombstone exists without logical coordinator lineage: " + type + ':' + id);
            }
            quarantineLegacyResource(type, legacyFile);
            return;
        }
        if (current instanceof AssetTransactionCoordinator.Deleted) {
            if (assetFile != null) {
                throw new IOException("Deleted coordinated asset exists on disk: " + type + ':' + id);
            }
            requirePhysicalTombstoneCoordinated(snapshot, type, id, graphTombstoneFile(type, id),
                snapshot.mutationValue(assetKey(type, id)).orElse(""));
            quarantineLegacyResourceAfterCommit(type, legacyFile);
            return;
        }
        if (assetFile == null) {
            throw new IOException("Coordinated live asset is missing: " + type + ':' + id);
        }
        if (!(current instanceof AssetTransactionCoordinator.Live live)) {
            throw new IOException("Resource coordinator state is invalid: " + type + ':' + id);
        }
        Path coordinatedPath = snapshot.path(assetKey(type, id))
            .orElseThrow(() -> new IOException("Live coordinated resource has no path: " + type + ':' + id));
        if (!coordinatedPath.toAbsolutePath().normalize().equals(assetFile.toAbsolutePath().normalize())
            || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(assetFile)))) {
            throw new IOException("Resource payload diverged from the shared asset coordinator: " + type + ':' + id);
        }
        long revision = Math.addExact(current.revision(), 1L);
        String payloadHash = canonicalResourcePayloadHash(type, StorageSafety.readUtf8(assetFile));
        String encoded = resourceTombstone(type, id, revision, mutationId, payloadHash);
        String metadataJson = metadataWithoutResource(type, id);
        AssetTransactionCoordinator.TransactionResult result = transactAssets(coordinator, snapshot, mutationId,
            List.of(AssetMutation.delete(assetKey(type, id), assetFile),
                AssetMutation.write(tombstoneKey(type, id), graphTombstoneFile(type, id), encoded.getBytes(StandardCharsets.UTF_8))),
            metadataJson);
        if (!(result.states().get(assetKey(type, id)) instanceof AssetTransactionCoordinator.Deleted deleted)
            || deleted.revision() != revision
            || !(result.states().get(tombstoneKey(type, id)) instanceof AssetTransactionCoordinator.Live)) {
            throw new IOException("Resource deletion did not publish a durable tombstone: " + type + ':' + id);
        }
        quarantineLegacyResourceAfterCommit(type, legacyFile);
    }

    private void quarantineLegacyResourceAfterCommit(String type, Path legacyFile) {
        try {
            quarantineLegacyResource(type, legacyFile);
        } catch (IOException | RuntimeException quarantineFailure) {
            Log.error("Committed resource delete could not quarantine the legacy payload: "
                + type + ':' + legacyFile, quarantineFailure);
        }
    }

    private void quarantineLegacyResource(String type, Path legacyFile) throws IOException {
        if (legacyFile == null || !Files.exists(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Legacy resource deletion source is not a regular file: " + type + ':' + legacyFile);
        }
        Path legacyQuarantine = assetsDir.toPath().getParent().resolve("legacy-quarantine").resolve("deletes")
            .resolve(UUID.randomUUID().toString()).resolve("legacy").resolve(type).resolve(legacyFile.getFileName());
        Files.createDirectories(legacyQuarantine.getParent());
        Files.move(legacyFile, legacyQuarantine);
        StorageSafety.forceDirectory(legacyQuarantine.getParent());
    }

    private boolean isDurabilityInternalPath(Path root, Path path) {
        Path relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0) {
            return false;
        }
        return isDurabilityInternalAssetPath(relative.toString());
    }

    private boolean isDurabilityInternalAssetPath(String path) {
        String normalized = normalizeAssetPath(path);
        int separator = normalized.indexOf('/');
        String first = separator >= 0 ? normalized.substring(0, separator) : normalized;
        return first.equals(".asset-coordinator") || first.equals(".transactions") || first.equals(".snapshots")
            || first.equals(".quarantine") || first.equals(".durability") || first.equals(".tombstones")
            || first.equals(".migrations") || first.equals("migration-backups");
    }

    private boolean graphTombstoneBlocks(String type, String id, Path resource) {
        if (!GRAPH_TYPES.contains(type)) {
            return false;
        }
        Path tombstone = graphTombstoneFile(type, id);
        if (!Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isRegularFile(tombstone, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Graph tombstone is not a regular file: " + type + ':' + id);
        }
        GraphIdentity identity = readGraphTombstoneIdentity(type, id, tombstone);
        if (identity == null) {
            throw new IllegalStateException("Invalid graph tombstone: " + type + ':' + id);
        }
        if (resource != null && Files.exists(resource, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(resource, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Graph live payload is not a regular file: " + type + ':' + id);
            }
            throw new IllegalStateException("Graph has both a live payload and tombstone: " + type + ':' + id);
        }
        return true;
    }

    private Path graphTombstoneFile(String type, String id) {
        return assetsDir.toPath().resolve(".tombstones").resolve(type).resolve(id + ".json");
    }

    private void commitAssetWrite(Path target, String content) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.equals(assetsDir.toPath().toAbsolutePath().normalize().resolve("project.json"))) {
            throw new IOException("Flow migration cannot directly mutate an asset payload");
        }
        transactAssets(UUID.randomUUID(), List.of(), content);
    }

    private void quarantineDuplicate(Path root, Path path) throws IOException {
        quarantineAsset(root, path, "duplicates");
    }

    private void quarantineAsset(Path root, Path path, String category) throws IOException {
        Path relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        Path quarantineRoot = root.resolve(".quarantine").resolve(category).resolve(UUID.randomUUID().toString());
        Path target = quarantineRoot.resolve(relative).normalize();
        if (!target.startsWith(quarantineRoot)) {
            throw new IOException("Unsafe asset quarantine path: " + relative);
        }
        AssetTransactionCoordinator coordinator = requireAssetTransactions();
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        Path normalized = path.toAbsolutePath().normalize();
        AssetTransactionCoordinator.AssetKey key = snapshot.paths().entrySet().stream()
            .filter(entry -> entry.getValue().toAbsolutePath().normalize().equals(normalized))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new IOException("Cannot quarantine an uncoordinated asset: " + relative));
        UUID mutationId = UUID.randomUUID();
        transactAssets(coordinator, snapshot, mutationId,
            List.of(AssetMutation.write(key, target, Files.readAllBytes(path))), null);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Coordinated asset quarantine did not relocate the source: " + relative);
        }
    }

    private void syncAssetsFromProjectMetadata(String json, CommandMigrationEvidence commandEvidence) {
        ProjectMetadataSnapshot metadata = parseProjectMetadata(json);
        if (metadata == null) {
            throw new IllegalArgumentException("Flow project metadata is invalid");
        }
        try {
            Files.createDirectories(assetsDir.toPath());
            for (FolderEntrySnapshot folder : metadata.folders()) {
                Files.createDirectories(safeAssetFolder(folder.path));
            }
            boolean changed = false;
            if (legacyRuntimeGate.allowsLegacyMigration()) {
                changed |= moveResourceTypeToAssets(metadata, flowDir, "flow", commandEvidence);
                changed |= moveResourceTypeToAssets(metadata, new File(configFile.getParentFile(), "custom-content"), "custom_content", commandEvidence);
                changed |= moveResourceTypeToAssets(metadata, guiDir, "gui", commandEvidence);
                changed |= moveResourceTypeToAssets(metadata, scoreboardDir, "scoreboard", commandEvidence);
                changed |= moveResourceTypeToAssets(metadata, tabDir, "tab", commandEvidence);
                changed |= moveResourceTypeToAssets(metadata, new File(configFile.getParentFile(), "worldgen-projects"), "worldgen", commandEvidence);
                for (String type : ReSyncJsonResourceStorage.resourceTypesStatic()) {
                    changed |= moveResourceTypeToAssets(metadata, legacyJsonDirectory(type), type, commandEvidence);
                }
            } else {
                logBlockedLegacyOperation("sync legacy assets from project metadata");
            }
            if (changed) {
                String updatedJson = serializeProjectMetadata(metadata);
                commitAssetWrite(assetsDir.toPath().resolve("project.json"), updatedJson);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to sync coordinated assets", e);
        }
    }

    private synchronized void migrateLegacyAssets() {
        try (AssetPersistenceGate.MutationLease ignored = requirePersistenceMutationOpen()) {
            try {
                ProjectMetadataSnapshot metadata = loadMigrationMetadata();
                CommandMigrationEvidence commandEvidence = loadCommandFlowIds();
                boolean changed = ensureDefaultFolders(metadata);
                changed |= reconcileExistingAssetResources(metadata);
                changed |= reclassifyCommandResources(metadata, commandEvidence);
                changed |= migrateLegacyFlows(metadata, commandEvidence);
                changed |= migrateLegacyResources(metadata, guiDir, "gui", "GUIs");
                changed |= migrateLegacyResources(metadata, scoreboardDir, "scoreboard", "Customization/Scoreboards");
                changed |= migrateLegacyResources(metadata, tabDir, "tab", "Customization/Tabs");
                changed |= migrateLegacyCustomContent(metadata);
                changed |= migrateLegacyResources(metadata, new File(configFile.getParentFile(), "worldgen-projects"), "worldgen", "WorldGen");
                if (changed || !Files.exists(assetsDir.toPath().resolve("project.json"))) {
                    String json = serializeProjectMetadata(metadata);
                    commitAssetWrite(assetsDir.toPath().resolve("project.json"), json);
                }
                syncAssetsFromProjectMetadata(serializeProjectMetadata(metadata), commandEvidence);
                if (pruneMissingAssetResources(metadata)) {
                    String json = serializeProjectMetadata(metadata);
                    commitAssetWrite(assetsDir.toPath().resolve("project.json"), json);
                }
                cleanupEmptyAssetDirectories(metadata);
                cleanupLegacyAssetDirectories();
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Failed to migrate legacy assets through the shared coordinator", e);
            }
        }
    }

    private boolean reconcileExistingAssetResources(ProjectMetadataSnapshot metadata) throws IOException {
        boolean changed = false;
        Path root = assetsDir.toPath();
        if (!Files.exists(root)) {
            return false;
        }
        try (Stream<Path> paths = walkAssetTree(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                if (isDurabilityInternalPath(root, file)) {
                    continue;
                }
                AssetResourceName asset = parseAssetResourceName(file);
                if (asset == null) {
                    continue;
                }
                String id = safeId(asset.id(), "reconcile assets");
                if (id == null) {
                    continue;
                }
                String type = asset.type();
                if (isOrphanedAssetCopy(metadata, type, id, file)) {
                    quarantineDuplicate(root, file);
                    continue;
                }
                String folder = assetFolderPath(root, file);
                folder = canonicalManagedAssetFolder(type, folder);
                changed |= ensureFolderPath(metadata, folder);
                Path target = safeAssetFolder(folder).resolve(AssetFileFormat.idOnlyFileName(id));
                if (Files.exists(target) && !file.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                    String targetType = AssetFileFormat.readResourceType(target);
                    if (type.equals(targetType)) {
                        if (AssetFileFormat.isIdOnlyFileName(file.getFileName().toString())) {
                            quarantineDuplicate(root, file);
                        }
                        changed = true;
                        continue;
                    }
                    folder = conflictFolderForType(type, id, 1);
                    changed |= ensureFolderPath(metadata, folder);
                    target = safeAssetFolder(folder).resolve(AssetFileFormat.idOnlyFileName(id));
                    if (Files.exists(target) && type.equals(AssetFileFormat.readResourceType(target))) {
                        changed |= ensureAssetResource(metadata, type, id, id, folder);
                        if (AssetFileFormat.isIdOnlyFileName(file.getFileName().toString())) {
                            quarantineDuplicate(root, file);
                        }
                        changed = true;
                        continue;
                    }
                }
                changed |= ensureAssetResource(metadata, type, id, id, folder);
                boolean moved = !file.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize());
                if (AssetFileFormat.needsRewrite(file, type) || moved) {
                    coordinateTypedAsset(file, target, type, id, serializeProjectMetadata(metadata));
                }
            }
        }
        return changed;
    }

    private boolean isOrphanedAssetCopy(ProjectMetadataSnapshot metadata, String type, String id, Path file) {
        ResourceEntrySnapshot resource = metadata.findExactResource(type, id);
        if (resource == null) {
            return false;
        }
        try {
            Path canonical = safeAssetFolder(resource.path).resolve(AssetFileFormat.idOnlyFileName(id)).toAbsolutePath().normalize();
            return Files.isRegularFile(canonical) && type.equals(AssetFileFormat.readResourceType(canonical)) && !file.toAbsolutePath().normalize().equals(canonical);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean pruneMissingAssetResources(ProjectMetadataSnapshot metadata) throws IOException {
        return reconcileMissingAssetResources(metadata, requireAssetTransactions().read(Function.identity())).changed();
    }

    private ProjectMetadataReconciliation reconcileMissingAssetResources(
        ProjectMetadataSnapshot metadata, AssetTransactionCoordinator.Snapshot snapshot) throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        Path root = assetsDir.toPath();
        int resourcesBefore = metadata.mutableResources().size();
        int foldersBefore = metadata.mutableFolders().size();
        int inspectedResourceCount = 0;
        boolean changed = false;
        Set<AssetTransactionCoordinator.AssetKey> missingDurable = new LinkedHashSet<>();
        Set<AssetTransactionCoordinator.AssetKey> removedStale = new LinkedHashSet<>();
        Set<AssetTransactionCoordinator.AssetKey> restoredPresentation = new LinkedHashSet<>();
        List<Map.Entry<AssetTransactionCoordinator.AssetKey, AssetTransactionCoordinator.ExpectedState>> states =
            snapshot.states().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().canonical()))
                .toList();
        for (Map.Entry<AssetTransactionCoordinator.AssetKey, AssetTransactionCoordinator.ExpectedState> entry : states) {
            AssetTransactionCoordinator.AssetKey resourceKey = entry.getKey();
            if (!(entry.getValue() instanceof AssetTransactionCoordinator.Live) || !knownAssetType(resourceKey.type())) {
                continue;
            }
            String id = safeId(resourceKey.id(), "reconcile project metadata");
            if (id == null) {
                continue;
            }
            inspectedResourceCount++;
            Path coordinated = snapshot.path(resourceKey).orElse(null);
            if (!isCurrentCoordinatedAssetFile(root, coordinated, resourceKey.type(), id)) {
                missingDurable.add(resourceKey);
                continue;
            }
            if (metadata.findExactResource(resourceKey.type(), id) == null) {
                String folder = coordinatedPresentationFolder(root, coordinated, resourceKey.type());
                changed |= ensureFolderPath(metadata, folder);
                metadata.mutableResources().add(newResource(resourceKey.type(), id, id, folder));
                restoredPresentation.add(resourceKey);
                changed = true;
            }
        }
        for (ResourceEntrySnapshot resource : metadata.resources()) {
            if (resource == null || !knownAssetType(resource.type) || resource.id == null || resource.id.isBlank()) {
                continue;
            }
            AssetTransactionCoordinator.AssetKey resourceKey = assetKey(resource.type, resource.id);
            AssetTransactionCoordinator.ExpectedState state = snapshot.state(resourceKey)
                .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
            if (!(state instanceof AssetTransactionCoordinator.Live)) {
                removedStale.add(resourceKey);
            }
        }
        changed |= metadata.mutableResources().removeIf(resource -> resource != null
            && (isDurabilityInternalAssetPath(resource.path)
            || knownAssetType(resource.type) && resource.id != null && !resource.id.isBlank()
            && removedStale.contains(assetKey(resource.type, resource.id))));
        changed |= metadata.mutableFolders().removeIf(folder -> folder != null && isDurabilityInternalAssetPath(folder.path));
        TemporaryLifecycleDiagnostics.event("project_metadata_prune", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                PROJECT_METADATA_TYPE + ":" + projectMetadataResourceId(), null, null, null, null, null,
                persistenceGeneration), "outcome", changed ? "changed" : "unchanged", "storageTraversal", false,
                "coordinatorStateCount", snapshot.states().size(), "inspectedResourceCount", inspectedResourceCount,
                "resourceCount", resourcesBefore, "removedResourceCount",
                Math.max(0, resourcesBefore + restoredPresentation.size() - metadata.mutableResources().size()),
                "restoredResourceCount", restoredPresentation.size(),
                "missingDurableResourceCount", missingDurable.size(), "folderCount", foldersBefore,
                "removedFolderCount", Math.max(0, foldersBefore - metadata.mutableFolders().size())));
        return new ProjectMetadataReconciliation(
            missingDurable, removedStale, restoredPresentation, inspectedResourceCount, changed);
    }

    private boolean isCurrentCoordinatedAssetFile(Path root, Path file, String type, String id) {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot) || isDurabilityInternalPath(normalizedRoot, normalizedFile)) {
            return false;
        }
        AssetResourceName asset = parseAssetResourceName(normalizedFile);
        return asset != null && type.equals(asset.type()) && id.equals(asset.id());
    }

    private String coordinatedPresentationFolder(Path root, Path file, String type) {
        if (file == null) {
            return defaultFolderForType(type);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot) || isDurabilityInternalPath(normalizedRoot, normalizedFile)) {
            return defaultFolderForType(type);
        }
        String folder = assetFolderPath(normalizedRoot, normalizedFile);
        return folder.isBlank() ? defaultFolderForType(type) : folder;
    }

    private FlowGraph readAssetFlow(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            return FlowSerializer.deserialize(StorageSafety.readUtf8(file));
        } catch (IOException | RuntimeException e) {
            Log.warn("Failed to inspect graph asset during migration: " + file.getFileName() + " - " + e.getMessage());
            return null;
        }
    }

    private AssetResourceName parseAssetResourceName(Path file) {
        String fileName = file != null && file.getFileName() != null ? file.getFileName().toString() : "";
        if (fileName == null || !fileName.endsWith(".json")) {
            return null;
        }
        int separator = fileName.indexOf("__");
        String declaredType = normalizeAssetType(AssetFileFormat.readResourceType(file));
        String type = separator > 0 ? normalizeAssetType(fileName.substring(0, separator)) : declaredType;
        if (knownAssetType(declaredType)) {
            type = declaredType;
        }
        if (!knownAssetType(type)) {
            return null;
        }
        String id = separator > 0 ? fileName.substring(separator + 2, fileName.length() - 5)
            : AssetFileFormat.idFromIdOnlyFileName(fileName);
        return id.isBlank() ? null : new AssetResourceName(type, id);
    }

    private String normalizeAssetType(String type) {
        return "chat_channel".equals(type) ? "chat" : type;
    }

    private boolean knownAssetType(String type) {
        return type != null
                && !"project_metadata".equals(type)
                && !"world".equals(type)
                && ReSyncResourceCatalog.byType(type) != null;
    }

    private String conflictFolderForType(String type, String id, int index) {
        String suffix = index <= 1 ? type : type + "_" + index;
        String folder = AssetFileFormat.typedConflictFolder(defaultFolderForType(type), suffix);
        Path target = safeAssetFolder(folder).resolve(AssetFileFormat.idOnlyFileName(id));
        if (Files.exists(target) && !type.equals(AssetFileFormat.readResourceType(target))) {
            return conflictFolderForType(type, id, index + 1);
        }
        return folder;
    }

    private String assetFolderPath(Path root, Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return "";
        }
        Path relative = root.toAbsolutePath().normalize().relativize(parent.toAbsolutePath().normalize());
        String path = relative.toString().replace('\\', '/');
        return ".".equals(path) ? "" : normalizeAssetPath(path);
    }

    private boolean ensureFolderPath(ProjectMetadataSnapshot metadata, String path) {
        String normalized = normalizeAssetPath(path);
        if (normalized.isBlank()) {
            return false;
        }
        boolean changed = false;
        String parent = "";
        int order = metadata.folders().size();
        for (String part : normalized.split("/")) {
            String current = parent.isBlank() ? part : parent + "/" + part;
            changed |= ensureFolder(metadata, current, parent, order++);
            parent = current;
        }
        return changed;
    }

    private ProjectMetadataSnapshot loadMigrationMetadata() {
        Path assetMetadata = assetsDir.toPath().resolve("project.json");
        ProjectMetadataSnapshot metadata = readMigrationMetadata(assetMetadata);
        if (metadata != null) {
            return metadata;
        }
        if (Files.exists(assetMetadata, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Coordinated project metadata cannot be interpreted for Flow migration");
        }
        Path legacyMetadata = jsonFile(projectMetadataDir, "project", "load migration metadata");
        metadata = legacyMetadata != null ? readMigrationMetadata(legacyMetadata) : null;
        if (legacyMetadata != null && Files.exists(legacyMetadata, LinkOption.NOFOLLOW_LINKS) && metadata == null) {
            throw new IllegalStateException("Legacy project metadata cannot be interpreted for Flow migration");
        }
        return metadata != null ? metadata : new ProjectMetadataSnapshot();
    }

    private ProjectMetadataSnapshot readMigrationMetadata(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            return parseProjectMetadata(StorageSafety.readUtf8(file));
        } catch (IOException e) {
            Log.warn("Failed to read migration metadata: " + e.getMessage());
            return null;
        }
    }

    private boolean ensureDefaultFolders(ProjectMetadataSnapshot metadata) {
        boolean changed = false;
        changed |= ensureFolder(metadata, "Blueprints", "", 0);
        changed |= ensureFolder(metadata, "Blueprints/Flows", "Blueprints", 0);
        changed |= ensureFolder(metadata, "Blueprints/Functions", "Blueprints", 1);
        changed |= ensureFolder(metadata, "Blueprints/Commands", "Blueprints", 2);
        changed |= ensureFolder(metadata, "Content", "", 1);
        changed |= ensureFolder(metadata, "Content/Items", "Content", 0);
        changed |= ensureFolder(metadata, "Content/Armor", "Content", 1);
        changed |= ensureFolder(metadata, "Content/Blocks", "Content", 2);
        changed |= ensureFolder(metadata, "Content/Advancements", "Content", 3);
        changed |= ensureFolder(metadata, "Content/Dialogs", "Content", 4);
        changed |= ensureFolder(metadata, "GUIs", "", 2);
        changed |= ensureFolder(metadata, "Customization", "", 3);
        changed |= ensureFolder(metadata, "Customization/Scoreboards", "Customization", 0);
        changed |= ensureFolder(metadata, "Customization/Tabs", "Customization", 1);
        changed |= ensureFolder(metadata, "Text", "", 4);
        changed |= ensureFolder(metadata, "Text/Lists", "Text", 0);
        changed |= ensureFolder(metadata, "Text/Maps", "Text", 1);
        changed |= ensureFolder(metadata, "Text/Animations", "Text", 2);
        changed |= ensureFolder(metadata, "Text/Templates", "Text", 3);
        changed |= ensureFolder(metadata, "Worlds", "", 5);
        changed |= ensureFolder(metadata, "WorldGen", "", 6);
        changed |= ensureFolder(metadata, "Groups", "", 7);
        return changed;
    }

    private boolean ensureFolder(ProjectMetadataSnapshot metadata, String path, String parentPath, int sortOrder) {
        if (metadata.findFolder(path) != null) {
            return false;
        }
        FolderEntrySnapshot folder = new FolderEntrySnapshot();
        folder.path = normalizeAssetPath(path);
        folder.parentPath = normalizeAssetPath(parentPath);
        folder.name = lastAssetSegment(path);
        folder.sortOrder = sortOrder;
        metadata.mutableFolders().add(folder);
        return true;
    }

    private boolean reclassifyCommandResources(ProjectMetadataSnapshot metadata,
                                               CommandMigrationEvidence commandEvidence) {
        boolean changed = false;
        for (String id : commandEvidence.ids()) {
            ResourceEntrySnapshot existing = metadata.findExactResource("command", id);
            if (existing != null) {
                String folder = existing.path != null && !existing.path.isBlank() ? existing.path : "Blueprints/Commands";
                if ("Blueprints/Flows".equals(folder) || "Blueprints/Functions".equals(folder)) {
                    folder = "Blueprints/Commands";
                }
                if (!folder.equals(existing.path)) {
                    existing.path = folder;
                    changed = true;
                }
                continue;
            }
            if (metadata.findExactResource("flow", id) != null || metadata.findExactResource("function", id) != null) {
                continue;
            }
            if (!commandEvidence.bindingIds().contains(id) && !commandEvidence.graphIds().contains(id)) {
                continue;
            }
            String folder = "Blueprints/Commands";
            changed |= ensureResource(metadata, "command", id, id, folder);
        }
        return changed;
    }

    private String canonicalManagedAssetFolder(String type, String folder) {
        String ownRoot = defaultFolderForType(type);
        if (ownRoot == null || ownRoot.isBlank() || folder.equals(ownRoot) || folder.startsWith(ownRoot + "/")) {
            return folder;
        }
        for (var resource : ReSyncResourceCatalog.all()) {
            String foreignRoot = resource.defaultFolder();
            if (foreignRoot.isBlank() || foreignRoot.equals(ownRoot)) {
                continue;
            }
            if (folder.equals(foreignRoot)) {
                return ownRoot;
            }
            if (folder.startsWith(foreignRoot + "/")) {
                return ownRoot + folder.substring(foreignRoot.length());
            }
        }
        return folder;
    }

    private boolean migrateLegacyFlows(ProjectMetadataSnapshot metadata, CommandMigrationEvidence commandEvidence) throws IOException {
        boolean changed = false;
        for (String id : listSafeIds(flowDir)) {
            FlowGraph graph = readLegacyFlow(id);
            List<String> metadataTypes = graphMetadataTypes(metadata, id);
            String type = legacyGraphMigrationType(graph, metadataTypes, commandEvidence.bindingIds().contains(id));
            if (type.isBlank()) {
                Path legacy = jsonFile(flowDir, id, "quarantine ambiguous legacy graph");
                if (legacy != null && Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
                    quarantineLegacyMigrationSource("flow", legacy, "conflicts",
                        "The legacy graph matches multiple typed resources and has no unambiguous declared type");
                    changed = true;
                }
                continue;
            }
            ResourceEntrySnapshot existing = metadata.findExactResource(type, id);
            String folder = existing != null && existing.path != null && !existing.path.isBlank() ? existing.path : defaultFolderForType(type);
            changed |= ensureResource(metadata, type, id, id, folder);
        }
        return changed;
    }

    private List<String> graphMetadataTypes(ProjectMetadataSnapshot metadata, String id) {
        return GRAPH_TYPES.stream()
            .filter(type -> metadata.findExactResource(type, id) != null)
            .toList();
    }

    private String legacyGraphMigrationType(FlowGraph graph, List<String> metadataTypes, boolean commandBindingEvidence) {
        String declaredType = graph != null ? graph.getResourceType() : "";
        if (GRAPH_TYPES.contains(declaredType)) {
            return declaredType;
        }
        if (graph != null && graph.isFunction()) {
            return "function";
        }
        if (graphHasCommandStartNode(graph)) {
            return "command";
        }
        if (metadataTypes.size() == 1) {
            return metadataTypes.getFirst();
        }
        if (metadataTypes.isEmpty() && commandBindingEvidence) {
            return "command";
        }
        return metadataTypes.isEmpty() ? "flow" : "";
    }

    private FlowGraph readLegacyFlow(String id) {
        Path file = jsonFile(flowDir, id, "read legacy flow");
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            return FlowSerializer.deserialize(StorageSafety.readUtf8(file));
        } catch (IOException e) {
            Log.warn("Failed to read legacy flow during migration: " + id + " - " + e.getMessage());
            return null;
        }
    }

    private boolean migrateLegacyResources(ProjectMetadataSnapshot metadata, File directory, String type, String folder) {
        boolean changed = false;
        for (String id : listSafeIds(directory)) {
            changed |= ensureResource(metadata, type, id, id, folder);
        }
        return changed;
    }

    private boolean migrateLegacyCustomContent(ProjectMetadataSnapshot metadata) {
        boolean changed = false;
        File directory = new File(configFile.getParentFile(), "custom-content");
        for (String id : listSafeIds(directory)) {
            String folder = switch (legacyCustomContentType(id)) {
                case "armor" -> "Content/Armor";
                case "block" -> "Content/Blocks";
                case "projectile" -> "Content/Projectiles";
                default -> "Content/Items";
            };
            changed |= ensureResource(metadata, "custom_content", id, id, folder);
        }
        return changed;
    }

    private String legacyCustomContentType(String id) {
        Path file = jsonFile(new File(configFile.getParentFile(), "custom-content"), id, "read legacy custom content");
        if (file == null || !Files.exists(file)) {
            return "item";
        }
        try {
            CustomContentSnapshot content = gson.fromJson(StorageSafety.readUtf8(file), CustomContentSnapshot.class);
            return content != null && content.type != null ? content.type.toLowerCase() : "item";
        } catch (IOException e) {
            Log.warn("Failed to read legacy custom content during migration: " + id + " - " + e.getMessage());
            return "item";
        }
    }

    private boolean ensureResource(ProjectMetadataSnapshot metadata, String type, String id, String displayName, String folder) {
        ResourceEntrySnapshot resource = metadata.findExactResource(type, id);
        if (resource != null) {
            return false;
        }
        resource = new ResourceEntrySnapshot();
        copyResource(newResource(type, id, displayName, folder), resource);
        resource.sortOrder = metadata.resources().size();
        metadata.mutableResources().add(resource);
        return true;
    }

    private boolean ensureAssetResource(ProjectMetadataSnapshot metadata, String type, String id, String displayName, String folder) {
        boolean changed = GRAPH_TYPES.contains(type) && metadata.mutableResources().removeIf(resource -> resource != null && id.equals(resource.id)
            && GRAPH_TYPES.contains(resource.type) && !type.equals(resource.type) && !declaredAssetExists(resource));
        ResourceEntrySnapshot resource = metadata.findExactResource(type, id);
        if (resource == null) {
            resource = new ResourceEntrySnapshot();
            copyResource(newResource(type, id, displayName, folder), resource);
            resource.sortOrder = metadata.resources().size();
            metadata.mutableResources().add(resource);
            return true;
        }
        String normalizedFolder = normalizeAssetPath(folder);
        if (!normalizedFolder.equals(resource.path)) {
            resource.path = normalizedFolder;
            changed = true;
        }
        if (resource.displayName == null || resource.displayName.isBlank()) {
            resource.displayName = displayName == null || displayName.isBlank() ? id : displayName;
            changed = true;
        }
        return changed;
    }

    private boolean declaredAssetExists(ResourceEntrySnapshot resource) {
        if (resource == null || resource.id == null || resource.path == null) {
            return false;
        }
        try {
            Path file = safeAssetFolder(resource.path).resolve(AssetFileFormat.idOnlyFileName(resource.id));
            return Files.isRegularFile(file) && resource.type.equals(AssetFileFormat.readResourceType(file));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private CommandMigrationEvidence loadCommandFlowIds() {
        Set<String> bindingIds = new HashSet<>();
        File triggerFile = new File(configFile.getParentFile(), "triggers.json");
        if (triggerFile.exists()) {
            try {
                JsonElement element = gson.fromJson(Files.readString(triggerFile.toPath(), StandardCharsets.UTF_8), JsonElement.class);
                JsonArray bindings = element != null && element.isJsonArray()
                    ? element.getAsJsonArray()
                    : element != null && element.isJsonObject() && element.getAsJsonObject().has("bindings")
                        && element.getAsJsonObject().get("bindings").isJsonArray()
                        ? element.getAsJsonObject().getAsJsonArray("bindings") : null;
                if (bindings != null) {
                    for (JsonElement bindingElement : bindings) {
                        if (bindingElement == null || !bindingElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject binding = bindingElement.getAsJsonObject();
                        JsonElement type = binding.get("type");
                        JsonElement flowId = binding.get("flowId");
                        if (type == null || flowId == null || !"COMMAND".equalsIgnoreCase(type.getAsString())) {
                            continue;
                        }
                        String id = flowId.getAsString();
                        if (id.isBlank()) {
                            continue;
                        }
                        String safeBindingId = safeId(id, "load command binding");
                        if (safeBindingId != null) {
                            bindingIds.add(safeBindingId);
                        }
                    }
                }
            } catch (IOException e) {
                Log.warn("Failed to read command triggers during migration: " + e.getMessage());
            }
        }
        return new CommandMigrationEvidence(bindingIds, loadCommandFlowIdsFromStoredGraphs());
    }

    private Set<String> loadCommandFlowIdsFromStoredGraphs() {
        Set<String> ids = new HashSet<>();
        if (flowDir.exists()) {
            File[] files = flowDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    String id = file.getName().substring(0, file.getName().length() - 5);
                    if (safeId(id, "load command graph") != null && graphHasCommandStartNode(readAssetFlow(file.toPath()))) {
                        ids.add(id);
                    }
                }
            }
        }
        Path root = assetsDir.toPath();
        if (!Files.exists(root)) {
            return ids;
        }
        try (Stream<Path> paths = walkAssetTree(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (isDurabilityInternalPath(root, path)) {
                    continue;
                }
                AssetResourceName asset = parseAssetResourceName(path);
                if (asset == null || !("flow".equals(asset.type()) || "function".equals(asset.type()) || "command".equals(asset.type()))) {
                    continue;
                }
                if (safeId(asset.id(), "load command graph") != null && graphHasCommandStartNode(readAssetFlow(path))) {
                    ids.add(asset.id());
                }
            }
        } catch (IOException e) {
            Log.warn("Failed to scan command graphs during migration: " + e.getMessage());
        }
        return ids;
    }

    private boolean moveResourceTypeToAssets(ProjectMetadataSnapshot metadata, File legacyDirectory, String type,
                                             CommandMigrationEvidence commandEvidence) throws IOException {
        boolean changed = false;
        for (ResourceEntrySnapshot resource : metadata.resources()) {
            if (!storageTypeMatchesResource(type, resource.type)) {
                continue;
            }
            String safeId = safeId(resource.id, "sync assets");
            if (safeId == null) {
                continue;
            }
            Path legacy = legacyDirectory.exists() ? jsonFile(legacyDirectory, safeId, "sync assets") : null;
            boolean legacyExists = legacy != null && Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
            if (legacyExists && GRAPH_TYPES.contains(resource.type) && flowDir.equals(legacyDirectory)) {
                String legacyType = legacyGraphMigrationType(readLegacyFlow(safeId), graphMetadataTypes(metadata, safeId),
                    commandEvidence.bindingIds().contains(safeId));
                if (!resource.type.equals(legacyType)) {
                    continue;
                }
            }
            Path target = assetResourceFile(resource);
            Files.createDirectories(target.getParent());
            String assetType = GRAPH_TYPES.contains(resource.type) ? resource.type : type;
            Path currentAsset = findAssetResourceFile(assetType, safeId);
            if (currentAsset != null && !currentAsset.equals(target)) {
                if (Files.exists(target)) {
                    String targetType = AssetFileFormat.readResourceType(target);
                    if (resource.type.equals(targetType)) {
                        if (legacyExists) {
                            consumeLegacyDuplicate(resource.type, safeId, legacy, currentAsset);
                        }
                        continue;
                    }
                    String folder = conflictFolderForType(resource.type, safeId, 1);
                    resource.path = normalizeAssetPath(folder);
                    target = assetResourceFile(resource);
                    changed = true;
                }
                coordinateTypedAsset(currentAsset, target, resource.type, safeId, serializeProjectMetadata(metadata));
                if (legacyExists) {
                    consumeLegacyDuplicate(resource.type, safeId, legacy, target);
                }
                continue;
            } else if (currentAsset != null) {
                if (AssetFileFormat.needsRewrite(currentAsset, resource.type)) {
                    coordinateTypedAsset(currentAsset, currentAsset, resource.type, safeId, serializeProjectMetadata(metadata));
                }
                if (legacyExists) {
                    consumeLegacyDuplicate(resource.type, safeId, legacy, currentAsset);
                }
                continue;
            }
            if (legacyExists && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                coordinateTypedAsset(legacy, target, resource.type, safeId, serializeProjectMetadata(metadata));
                consumeLegacyPublished(resource.type, safeId, legacy, target);
            } else if (legacyExists) {
                String targetType = AssetFileFormat.readResourceType(target);
                if (resource.type.equals(targetType)) {
                    consumeLegacyDuplicate(resource.type, safeId, legacy, target);
                    continue;
                }
                String folder = conflictFolderForType(resource.type, safeId, 1);
                resource.path = normalizeAssetPath(folder);
                target = assetResourceFile(resource);
                changed = true;
                coordinateTypedAsset(legacy, target, resource.type, safeId, serializeProjectMetadata(metadata));
                consumeLegacyPublished(resource.type, safeId, legacy, target);
            }
        }
        return changed;
    }

    private void consumeLegacyPublished(String type, String id, Path legacy, Path target) throws IOException {
        requireCoordinatedLiveFile(type, id, target);
        quarantineLegacyMigrationSource(type, legacy, "consumed", "published into the coordinated typed asset");
    }

    private void consumeLegacyDuplicate(String type, String id, Path legacy, Path target) throws IOException {
        requireCoordinatedLiveFile(type, id, target);
        if (Arrays.equals(Files.readAllBytes(legacy), Files.readAllBytes(target))) {
            quarantineLegacyMigrationSource(type, legacy, "consumed", "verified byte-identical canonical duplicate");
            return;
        }
        quarantineLegacyMigrationSource(type, legacy, "conflicts",
            "the canonical typed asset already exists with different bytes");
    }

    private void quarantineLegacyMigrationSource(String type, Path source, String category, String reason) throws IOException {
        if (source == null || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Legacy migration source is not a regular file: " + type + ':' + source);
        }
        Path quarantine = assetsDir.toPath().getParent().resolve("legacy-quarantine").resolve("migration").resolve(category)
            .resolve(UUID.randomUUID().toString()).resolve(type).resolve(source.getFileName());
        Files.createDirectories(quarantine.getParent());
        Files.move(source, quarantine);
        StorageSafety.forceDirectory(quarantine.getParent());
        StorageSafety.forceDirectory(source.getParent());
        Log.warn("Legacy " + type + " migration source was " + category + ": " + source.getFileName() + " - " + reason);
    }

    private void coordinateTypedAsset(Path source, Path target, String type, String id,
                                      String projectJson) throws IOException {
        byte[] sourceBytes = Files.readAllBytes(source);
        String sourceJson = new String(sourceBytes, StandardCharsets.UTF_8);
        AssetTransactionCoordinator coordinator = requireAssetTransactions();
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.AssetKey key = assetKey(type, id);
        AssetTransactionCoordinator.ExpectedState current = snapshot.state(key)
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        Path coordinatedPath = snapshot.path(key).orElse(null);
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        if (normalizedSource.startsWith(root)
            && (coordinatedPath == null || !coordinatedPath.toAbsolutePath().normalize().equals(normalizedSource))) {
            throw new IOException("Asset migration source is not owned by its typed coordinator key: " + type + ':' + id);
        }
        long revision = Math.addExact(current.revision(), 1L);
        UUID mutationId = UUID.randomUUID();
        String encoded = AssetFileFormat.withResourceIdentity(sourceJson, type, revision, mutationId.toString());
        AssetTransactionCoordinator.TransactionResult result = transactAssets(coordinator, snapshot, mutationId,
            List.of(AssetMutation.write(key, target, encoded.getBytes(StandardCharsets.UTF_8))), projectJson);
        AssetTransactionCoordinator.ExpectedState migrated = result.states().get(key);
        if (!(migrated instanceof AssetTransactionCoordinator.Live live) || migrated.revision() != revision
            || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
            || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(target)))
            || !Arrays.equals(encoded.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target))) {
            throw new IOException("Asset migration revision diverged from the shared coordinator: " + type + ':' + id);
        }
    }

    private ProjectMetadataSnapshot parseProjectMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ProjectMetadataSnapshot metadata = gson.fromJson(json, ProjectMetadataSnapshot.class);
            return metadata != null ? metadata : null;
        } catch (Exception e) {
            Log.warn("Failed to parse project metadata: " + e.getMessage());
            return null;
        }
    }

    private Path projectMetadataFile(String id, String action) {
        return assetsDir.toPath().resolve(id + ".json");
    }

    private boolean storageTypeMatchesResource(String storageType, String resourceType) {
        if (storageType.equals(resourceType)) {
            return true;
        }
        return "flow".equals(storageType) && ("function".equals(resourceType) || "command".equals(resourceType));
    }

    private ResourceEntrySnapshot newResource(String type, String id, String displayName, String folder) {
        ResourceEntrySnapshot resource = new ResourceEntrySnapshot();
        resource.type = type;
        resource.id = id;
        resource.displayName = displayName == null || displayName.isBlank() ? id : displayName;
        resource.path = normalizeAssetPath(folder);
        return resource;
    }

    private void copyResource(ResourceEntrySnapshot source, ResourceEntrySnapshot target) {
        target.type = source.type;
        target.id = source.id;
        target.displayName = source.displayName;
        target.path = source.path;
    }

    private String defaultFolderForType(String type) {
        return ReSyncResourceCatalog.defaultFolder(type);
    }

    private File legacyJsonDirectory(String type) {
        String folder = switch (type) {
            case ReSyncResourceCatalog.CHAT -> "chat";
            case ReSyncResourceCatalog.MOTD_PROFILE -> "motd-profiles";
            case ReSyncResourceCatalog.MESSAGE_RULE -> "message-rules";
            case ReSyncResourceCatalog.RECIPE_DEFINITION -> "recipes";
            case ReSyncResourceCatalog.TEXT_TEMPLATE -> "text-templates";
            case ReSyncResourceCatalog.ADVANCEMENT_TREE -> "advancement-trees";
            case ReSyncResourceCatalog.DIALOG -> "dialogs";
            default -> type;
        };
        return new File(configFile.getParentFile(), folder);
    }

    private void cleanupLegacyAssetDirectories() {
        List<File> directories = List.of(
                flowDir,
                guiDir,
                scoreboardDir,
                tabDir,
                projectMetadataDir,
                new File(configFile.getParentFile(), "custom-content"),
                new File(configFile.getParentFile(), "worldgen-projects")
        );
        for (File directory : directories) {
            deleteKnownLegacyDirectory(directory);
        }
    }

    private void cleanupEmptyAssetDirectories(ProjectMetadataSnapshot metadata) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Set<Path> declared = new HashSet<>();
        for (FolderEntrySnapshot folder : metadata.folders()) {
            if (folder != null) {
                declared.add(safeAssetFolder(folder.path).toAbsolutePath().normalize());
            }
        }
        try (Stream<Path> paths = walkAssetTree(root)) {
            for (Path directory : paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                if (directory.equals(root) || declared.contains(directory) || isDurabilityInternalPath(root, directory)) {
                    continue;
                }
                try (Stream<Path> children = Files.list(directory)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        }
    }

    private void deleteKnownLegacyDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }
        Path dataRoot = configFile.getParentFile().toPath().toAbsolutePath().normalize();
        Path target = directory.toPath().toAbsolutePath().normalize();
        if (!target.startsWith(dataRoot) || target.equals(dataRoot) || target.equals(assetsDir.toPath().toAbsolutePath().normalize())) {
            Log.warn("Skipped unsafe legacy directory cleanup: " + directory.getPath());
            return;
        }
        try (Stream<Path> paths = walkAssetTree(target)) {
            for (Path path : paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                try (Stream<Path> children = Files.list(path)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (IOException e) {
            Log.warn("Failed to delete legacy assets directory: " + directory.getName() + " - " + e.getMessage());
        }
    }

    private Path jsonFile(File directory, String id, String action) {
        try {
            return StorageSafety.jsonFile(directory.toPath(), id);
        } catch (IOException | IllegalArgumentException e) {
            Log.warn("Failed to resolve " + action + ": " + id + " - " + e.getMessage());
            return null;
        }
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            Log.warn("Rejected unsafe id during " + action + ": " + id);
            return null;
        }
    }

    private List<String> listSafeIds(File directory) {
        List<String> ids = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return ids;
        }
        for (File file : files) {
            String name = file.getName();
            String id = name.substring(0, name.length() - 5);
            if (safeId(id, "list") != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<String> listResourceIds(File legacyDirectory, String type, Map<String, Path> index) {
        Set<String> ids = new HashSet<>();
        for (String key : index.keySet()) {
            AssetResourceName asset = assetFromIndexKey(key);
            if (assetListFileMatches(type, asset) && safeId(asset.id(), "list") != null) {
                ids.add(asset.id());
            }
        }
        if (legacyRuntimeGate.allowsLegacyFallback() && legacyDirectory.exists()) {
            File[] files = legacyDirectory.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    String id = name.substring(0, name.length() - 5);
                    if (safeId(id, "list") != null) {
                        ids.add(id);
                    }
                }
            }
        } else if (legacyDirectory.exists()) {
            logBlockedLegacyOperation("list legacy " + type + " resources");
        }
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void logBlockedLegacyOperation(String operation) {
        String action = operation == null || operation.isBlank() ? "legacy runtime operation" : operation.trim();
        Log.warn("Blocked " + action + ": " + legacyRuntimeGate.decision().code());
        legacyRuntimeGate.recordBlocked(action);
    }

    private AssetResourceName assetFromIndexKey(String key) {
        if (key == null) {
            return null;
        }
        int separator = key.indexOf('\n');
        if (separator <= 0 || separator >= key.length() - 1) {
            return null;
        }
        return new AssetResourceName(key.substring(0, separator), key.substring(separator + 1));
    }

    private boolean assetListFileMatches(String type, AssetResourceName asset) {
        return asset != null && type.equals(asset.type());
    }

    private record AssetResourceName(String type, String id) {
    }

    private static ConfigurationPersistenceParticipant newConfigurationPersistence(File dataFolder) {
        try {
            return new ConfigurationPersistenceParticipant(dataFolder.toPath());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize configuration persistence", exception);
        }
    }

    private static ConfigurationPersistenceParticipant requireConfigurationPersistence(
        File dataFolder, ConfigurationPersistenceParticipant persistence) {
        Objects.requireNonNull(persistence, "configurationPersistence");
        Path expected = dataFolder.toPath().toAbsolutePath().normalize()
            .resolve(ConfigurationPersistenceParticipant.FILE_NAME).normalize();
        if (!expected.equals(persistence.root())) {
            throw new IllegalArgumentException("Configuration persistence must own the data-root config.properties file");
        }
        return persistence;
    }

    private AssetTransactionCoordinator requireAssetTransactions() {
        return requireAssetTransactions(assetsDir.toPath(), assetTransactions);
    }

    private static void requireAssetGateScope(Path assetsRoot, AssetPersistenceGate gate) {
        Path expectedScope = assetsRoot.toAbsolutePath().normalize().getParent();
        Path actualScope = gate.scopeRoot().toAbsolutePath().normalize();
        if (expectedScope == null || !expectedScope.equals(actualScope)) {
            throw new IllegalArgumentException("Asset persistence gate scope must own the Flow data root");
        }
    }

    private synchronized AssetPersistenceGate.MutationLease requirePersistenceReadOpen() {
        requireAssetGateScope(assetsDir.toPath(), assetsGate);
        if (persistenceState != PersistenceState.OPEN || !assetsGate.isOpen()) {
            throw new IllegalStateException("Flow asset persistence is QUIESCED; read rejected");
        }
        AssetPersistenceGate.MutationLease lease = assetsGate.acquire();
        try {
            if (persistenceState != PersistenceState.OPEN) {
                throw new IllegalStateException("Flow asset persistence is QUIESCED; read rejected");
            }
            requireAssetTransactions();
            return lease;
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
    }

    private synchronized PersistenceGeneration capturePersistenceGeneration() {
        AssetTransactionCoordinator coordinator = requireAssetTransactions();
        return new PersistenceGeneration(persistenceGeneration, coordinator,
            coordinator.canonicalRoot().toAbsolutePath().normalize());
    }

    private boolean isCurrentPersistenceGeneration(PersistenceGeneration expected) {
        if (expected == null || persistenceGeneration != expected.generation()) {
            return false;
        }
        AssetTransactionCoordinator current = assetTransactions;
        return current == expected.coordinator()
            && current.canonicalRoot().toAbsolutePath().normalize().equals(expected.root())
            && assetsDir.toPath().toAbsolutePath().normalize().equals(expected.root());
    }

    private synchronized void cacheGraphIfCurrent(PersistenceGeneration expected, String type, String id,
                                                    long revision, String mutationId, FlowGraph graph) {
        AssetPersistenceGate.MutationLease lease;
        try {
            lease = requirePersistenceReadOpen();
        } catch (IllegalStateException rejected) {
            return;
        }
        try (lease) {
            if (!isCurrentPersistenceGeneration(expected)) {
                return;
            }
            AssetTransactionCoordinator.Snapshot snapshot = requireAssetTransactions().read(Function.identity());
            AssetTransactionCoordinator.ExpectedState state = snapshot.state(assetKey(type, id))
                .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
            Path file = snapshot.path(assetKey(type, id)).orElse(null);
            boolean currentFile = false;
            if (file != null) {
                try {
                    currentFile = graphCacheIdentityMatches(graph, snapshot, type, id, file);
                } catch (IOException ignored) {
                    return;
                }
            }
            if (state instanceof AssetTransactionCoordinator.Live live && live.revision() == revision
                && Objects.equals(mutationId, snapshot.mutationValue(assetKey(type, id)).orElse(""))
                && currentFile) {
                graphCache.put(assetIndexKey(type, id), graph.copy());
            }
        }
    }

    private boolean graphCacheIdentityMatches(FlowGraph cached, AssetTransactionCoordinator.Snapshot snapshot,
                                               String type, String id, Path file) throws IOException {
        return graphCacheIdentityMatches(cached, snapshot, type, id, file, false);
    }

    private boolean graphCacheIdentityMatches(FlowGraph cached, AssetTransactionCoordinator.Snapshot snapshot,
                                               String type, String id, Path file, boolean payloadHashVerified)
        throws IOException {
        AssetTransactionCoordinator.ExpectedState state = snapshot.state(assetKey(type, id))
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        if (!(state instanceof AssetTransactionCoordinator.Live live)
            || cached.getResourceRevision() != live.revision()
            || !Objects.equals(cached.getResourceMutationId(), snapshot.mutationValue(assetKey(type, id)).orElse(""))
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || !verifyGraphAsset(file, type, id)
            || !Objects.equals(cached.getResourceHash(), AssetFileFormat.readContentHash(file))) {
            return false;
        }
        return payloadHashVerified || live.hash().equals(StorageSafety.sha256(Files.readAllBytes(file)));
    }

    private boolean cachedResourceIdentityMatches(String type, String id, CachedResourceIdentity cached) {
        if (cached == null) {
            return false;
        }
        return currentResourceIdentity(type, id).map(cached::equals).orElse(false);
    }

    private boolean cachedResourceIdentityMatches(String type, String id, CachedResourceIdentity cached,
                                                  AssetTransactionCoordinator.Snapshot snapshot, Path file) {
        if (cached == null) {
            return false;
        }
        return resourceIdentity(type, id, snapshot, file).map(cached::equals).orElse(false);
    }

    private void rememberResourceCacheIdentity(String type, String id) {
        Map<String, CachedResourceIdentity> identities = resourceCacheIdentities(type);
        currentResourceIdentity(type, id).ifPresentOrElse(identity -> identities.put(id, identity),
            () -> identities.remove(id));
    }

    private void rememberResourceCacheIdentity(String type, String id,
                                               AssetTransactionCoordinator.Snapshot snapshot, Path file) {
        Map<String, CachedResourceIdentity> identities = resourceCacheIdentities(type);
        resourceIdentity(type, id, snapshot, file).ifPresentOrElse(identity -> identities.put(id, identity),
            () -> identities.remove(id));
    }

    private Map<String, CachedResourceIdentity> resourceCacheIdentities(String type) {
        return switch (type) {
            case "gui" -> guiCacheIdentities;
            case "scoreboard" -> scoreboardCacheIdentities;
            case "tab" -> tabCacheIdentities;
            default -> throw new IllegalArgumentException("Unsupported cached resource type: " + type);
        };
    }

    private Optional<CachedResourceIdentity> currentResourceIdentity(String type, String id) {
        try {
            AssetTransactionCoordinator coordinator = requireAssetTransactions();
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
            AssetTransactionCoordinator.AssetKey key = assetKey(type, id);
            AssetTransactionCoordinator.ExpectedState state = snapshot.state(key)
                .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
            if (!(state instanceof AssetTransactionCoordinator.Live live)) {
                return Optional.empty();
            }
            CachedResourceIdentity cached = resourceCacheIdentities(type).get(id);
            String mutation = snapshot.mutationValue(key).orElse("");
            if (cached != null && cached.revision() == live.revision() && cached.mutationId().equals(mutation)) {
                return Optional.of(cached);
            }
            Path file = snapshot.path(key).orElse(null);
            if (file == null
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !type.equals(AssetFileFormat.readResourceType(file))
                || live.revision() != AssetFileFormat.readRevision(file)
                || !mutation.equals(AssetFileFormat.readMutationId(file))
                || !AssetFileFormat.verify(file)) {
                return Optional.empty();
            }
            if (!live.hash().equals(StorageSafety.sha256(Files.readAllBytes(file)))) {
                return Optional.empty();
            }
            return Optional.of(new CachedResourceIdentity(live.revision(), mutation, AssetFileFormat.readContentHash(file)));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<CachedResourceIdentity> resourceIdentity(String type, String id,
                                                              AssetTransactionCoordinator.Snapshot snapshot, Path file) {
        if (snapshot == null || file == null) {
            return Optional.empty();
        }
        try {
            AssetTransactionCoordinator.AssetKey key = assetKey(type, id);
            AssetTransactionCoordinator.ExpectedState state = snapshot.state(key)
                .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
            if (!(state instanceof AssetTransactionCoordinator.Live live) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !snapshot.path(key).map(path -> path.toAbsolutePath().normalize()
                    .equals(file.toAbsolutePath().normalize())).orElse(false)
                || !type.equals(AssetFileFormat.readResourceType(file))
                || live.revision() != AssetFileFormat.readRevision(file)
                || !Objects.equals(snapshot.mutationValue(key).orElse(""), AssetFileFormat.readMutationId(file))
                || !AssetFileFormat.verify(file)) {
                return Optional.empty();
            }
            return Optional.of(new CachedResourceIdentity(live.revision(),
                snapshot.mutationValue(key).orElse(""), AssetFileFormat.readContentHash(file)));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static AssetTransactionCoordinator requireAssetTransactions(
        Path assetsRoot, AssetTransactionCoordinator assetTransactions) {
        Objects.requireNonNull(assetTransactions, "assetTransactions");
        Path expected = assetsRoot.toAbsolutePath().normalize();
        Path actual = assetTransactions.canonicalRoot().toAbsolutePath().normalize();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Asset transaction coordinator must own the Flow asset root");
        }
        assetTransactions.requireOpen();
        return assetTransactions;
    }

    private AssetTransactionCoordinator.TransactionResult transactAssets(
        UUID mutationId, List<AssetMutation> mutations, String projectJson) throws IOException {
        AssetTransactionCoordinator coordinator = requireAssetTransactions();
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        return transactAssets(coordinator, snapshot, mutationId, mutations, projectJson);
    }

    private AssetTransactionCoordinator.TransactionResult transactAssets(
        AssetTransactionCoordinator coordinator, AssetTransactionCoordinator.Snapshot snapshot,
        UUID mutationId, List<AssetMutation> mutations, String projectJson) throws IOException {
        return transactAssets(coordinator, snapshot, mutationId, mutations, projectJson, null);
    }

    private AssetTransactionCoordinator.TransactionResult transactAssets(
        AssetTransactionCoordinator coordinator, AssetTransactionCoordinator.Snapshot snapshot,
        UUID mutationId, List<AssetMutation> mutations, String projectJson, Long expectedSequence) throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        List<AssetTransactionCoordinator.ProjectDelta> projectDeltas = projectJson == null
            ? List.of() : projectDeltas(snapshot, projectJson);
        List<AssetMutation> effectiveMutations = new ArrayList<>(mutations);
        boolean explicitProjectLineage = effectiveMutations.stream()
            .anyMatch(mutation -> mutation.key().equals(projectMetadataLineageKey()));
        if (!projectDeltas.isEmpty() && !explicitProjectLineage) {
            String resourceId = projectMetadataResourceId();
            ResourceIdentity current = projectMetadataIdentity(snapshot, resourceId);
            long revision = Math.addExact(current == null ? 0L : current.revision(), 1L);
            String payloadHash = canonicalProjectMetadataPayloadHash(projectMetadataAfter(snapshot, projectDeltas));
            String lineage = projectMetadataLineage(resourceId, revision, mutationId, payloadHash, false);
            effectiveMutations.add(AssetMutation.write(projectMetadataLineageKey(), projectMetadataLineageFile(),
                lineage.getBytes(StandardCharsets.UTF_8)));
        }
        List<AssetTransactionCoordinator.AssetDelta> deltas = new ArrayList<>();
        for (AssetMutation mutation : effectiveMutations) {
            AssetTransactionCoordinator.ExpectedState expected = snapshot.state(mutation.key())
                .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
            Path previousPath = snapshot.path(mutation.key()).orElse(null);
            if (mutation.deleted()) {
                deltas.add(AssetTransactionCoordinator.AssetDelta.delete(mutation.key(), mutation.path(), expected));
            } else if (expected instanceof AssetTransactionCoordinator.Live live && previousPath != null && !previousPath.toAbsolutePath().normalize()
                .equals(mutation.path().toAbsolutePath().normalize())) {
                deltas.add(live.hash().equals(StorageSafety.sha256(mutation.content()))
                    ? AssetTransactionCoordinator.AssetDelta.relocate(
                        mutation.key(), previousPath, mutation.path(), live, mutation.content())
                    : AssetTransactionCoordinator.AssetDelta.relocateTransformed(
                        mutation.key(), previousPath, mutation.path(), live, mutation.content()));
            } else {
                deltas.add(AssetTransactionCoordinator.AssetDelta.write(
                    mutation.key(), mutation.path(), expected, mutation.content()));
            }
        }
        if (deltas.isEmpty() && projectDeltas.isEmpty()) {
            TemporaryLifecycleDiagnostics.event("asset_transaction", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, "asset_batch",
                    mutationId, null, null, snapshot.rootSequence(), null, persistenceGeneration), "outcome", "empty",
                    "assetCount", 0, "metadataDeltaCount", 0, "coordinatorSequenceBefore", snapshot.rootSequence()));
            return null;
        }
        Map<String, Object> identity = TemporaryLifecycleDiagnostics.identity(serverId,
            deltas.size() == 1 ? deltas.getFirst().key().canonical() : "asset_batch", mutationId, null, null,
            snapshot.rootSequence(), null, persistenceGeneration);
        try {
            AssetTransactionCoordinator.TransactionRequest request =
                new AssetTransactionCoordinator.TransactionRequest(mutationId, snapshot.project(), deltas, projectDeltas);
            AssetTransactionCoordinator.TransactionResult result = expectedSequence == null
                ? coordinator.transact(request) : coordinator.transact(request, expectedSequence.longValue());
            TemporaryLifecycleDiagnostics.event("asset_transaction", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", result.replay() ? "replayed" : "committed",
                    "assetCount", deltas.size(), "metadataDeltaCount", projectDeltas.size(),
                    "coordinatorSequenceBefore", snapshot.rootSequence(), "coordinatorSequenceAfter", result.rootSequence()));
            return result;
        } catch (IOException | RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("asset_transaction", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed", "assetCount", deltas.size(),
                    "metadataDeltaCount", projectDeltas.size(), "coordinatorSequenceBefore", snapshot.rootSequence(),
                    "failure", exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private SavedResource transactResourceSave(String type, String id, Path file, String json, UUID mutationId,
                                                long expectedRevision, boolean enforceExpectedRevision) throws IOException {
        AssetTransactionCoordinator coordinator = requireAssetTransactions();
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.AssetKey key = assetKey(type, id);
        AssetTransactionCoordinator.ExpectedState current = snapshot.state(key)
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        if (enforceExpectedRevision && current.revision() != expectedRevision) {
            throw new ResourceRevisionConflictException(id, expectedRevision, current.revision());
        }
        Path coordinatedPath = snapshot.path(key).orElse(null);
        if (current instanceof AssetTransactionCoordinator.Missing && snapshot.state(tombstoneKey(type, id)).isPresent()) {
            throw new IOException("Resource tombstone exists without logical coordinator lineage: " + type + ':' + id);
        }
        if (current instanceof AssetTransactionCoordinator.Live live) {
            if (coordinatedPath == null || !Files.isRegularFile(coordinatedPath, LinkOption.NOFOLLOW_LINKS)
                || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(coordinatedPath)))) {
                throw new IOException("Resource payload diverged from the shared asset coordinator: " + type + ':' + id);
            }
            file = coordinatedPath;
        } else if (current instanceof AssetTransactionCoordinator.Deleted) {
            if (coordinatedPath == null) {
                throw new IOException("Deleted coordinated resource has no retained path: " + type + ':' + id);
            }
            requirePhysicalTombstoneCoordinated(snapshot, type, id, graphTombstoneFile(type, id),
                snapshot.mutationValue(key).orElse(""));
            file = coordinatedPath;
        }
        long revision = Math.addExact(current.revision(), 1L);
        String encoded = AssetFileFormat.withResourceIdentity(json, type, revision, mutationId.toString());
        List<AssetMutation> mutations = new ArrayList<>();
        mutations.add(AssetMutation.write(key, file, encoded.getBytes(StandardCharsets.UTF_8)));
        boolean physicalTombstone = snapshot.state(tombstoneKey(type, id))
            .filter(AssetTransactionCoordinator.Live.class::isInstance).isPresent();
        if (current instanceof AssetTransactionCoordinator.Deleted && physicalTombstone) {
            mutations.add(AssetMutation.delete(tombstoneKey(type, id), graphTombstoneFile(type, id)));
        } else if (physicalTombstone) {
            throw new IOException("Live resource has a physical tombstone: " + type + ':' + id);
        }
        String metadataJson = metadataWithResourcePath(type, id, assetFolderPath(assetsDir.toPath(), file), false);
        AssetTransactionCoordinator.TransactionResult result = transactAssets(coordinator, snapshot, mutationId,
            mutations, metadataJson);
        AssetTransactionCoordinator.ExpectedState saved = result.states().get(key);
        if (!(saved instanceof AssetTransactionCoordinator.Live) || saved.revision() != revision) {
            throw new IOException("Resource save revision diverged from the shared asset coordinator: " + type + ':' + id);
        }
        return new SavedResource(file);
    }

    private String resourceTombstone(String type, String id, long revision, UUID mutationId, String payloadHash) {
        JsonObject tombstone = new JsonObject();
        tombstone.addProperty("type", type);
        tombstone.addProperty("id", id);
        tombstone.addProperty(AssetFileFormat.RESOURCE_TYPE, type);
        tombstone.addProperty(AssetFileFormat.REVISION, revision);
        tombstone.addProperty(AssetFileFormat.MUTATION_ID, mutationId.toString());
        tombstone.addProperty("revision", revision);
        tombstone.addProperty("mutationId", mutationId.toString());
        tombstone.addProperty("payloadHash", payloadHash);
        tombstone.addProperty("deleted", true);
        tombstone.addProperty("deletedAt", System.currentTimeMillis());
        return gson.toJson(tombstone);
    }

    private AssetTransactionCoordinator.Snapshot requireCoordinatedCoreState(
        AssetTransactionCoordinator coordinator, String type, String id, CoreStoredState stored) throws IOException {
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.ExpectedState coordinated = snapshot.state(assetKey(type, id))
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        switch (stored.kind()) {
            case ABSENT -> {
                if (!(coordinated instanceof AssetTransactionCoordinator.Missing)) {
                    throw new IOException("Core graph coordinator state is not missing: " + type + ':' + id);
                }
            }
            case LIVE -> {
                Path liveFile = stored.liveFile();
                if (!(coordinated instanceof AssetTransactionCoordinator.Live live)
                    || live.revision() != stored.revision() || liveFile == null
                    || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(liveFile)))
                    || !coordinatedMutationMatches(snapshot, assetKey(type, id), stored.mutationId())) {
                    throw new IOException("Core graph live state diverged from the shared asset coordinator: " + type + ':' + id);
                }
            }
            case TOMBSTONED -> {
                if (!(coordinated instanceof AssetTransactionCoordinator.Deleted deleted)
                    || deleted.revision() != stored.revision()
                    || !coordinatedMutationMatches(snapshot, assetKey(type, id), stored.mutationId())) {
                    throw new IOException("Core graph tombstone state diverged from the shared asset coordinator: " + type + ':' + id);
                }
                requirePhysicalTombstoneCoordinated(snapshot, type, id, graphTombstoneFile(type, id), stored.mutationId());
            }
            case LEGACY -> throw new IOException("Legacy Core graph is not coordinated: " + type + ':' + id);
        }
        return snapshot;
    }

    private AssetTransactionCoordinator.Snapshot requireCoordinatedLiveFile(
        String type, String id, Path file) throws IOException {
        AssetTransactionCoordinator.Snapshot snapshot = requireAssetTransactions().read(Function.identity());
        return requireCoordinatedLiveFile(type, id, file, snapshot);
    }

    private AssetTransactionCoordinator.Snapshot requireCoordinatedLiveFile(
        String type, String id, Path file, AssetTransactionCoordinator.Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "Asset coordinator snapshot is required");
        AssetTransactionCoordinator.AssetKey key = assetKey(type, id);
        AssetTransactionCoordinator.ExpectedState state = snapshot.state(key)
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        Path coordinatedPath = snapshot.path(key).orElse(null);
        if (!(state instanceof AssetTransactionCoordinator.Live live) || coordinatedPath == null
            || !coordinatedPath.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(file)))) {
            throw new IOException("Live asset does not match shared coordinator state: " + type + ':' + id);
        }
        return snapshot;
    }

    private AssetTransactionCoordinator.Snapshot requireCoordinatedGraphState(
        AssetTransactionCoordinator coordinator, String type, String id, StoredGraphState stored) throws IOException {
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.ExpectedState coordinated = snapshot.state(assetKey(type, id))
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        if (stored == null) {
            if (!(coordinated instanceof AssetTransactionCoordinator.Missing)
                || !(snapshot.state(tombstoneKey(type, id)).orElse(AssetTransactionCoordinator.Missing.INSTANCE)
                    instanceof AssetTransactionCoordinator.Missing)) {
                throw new IOException("Graph coordinator state is not missing: " + type + ':' + id);
            }
            return snapshot;
        }
        if (stored.identity().deleted()) {
            if (!(coordinated instanceof AssetTransactionCoordinator.Deleted deleted)
                || deleted.revision() != stored.identity().revision()
                || !coordinatedMutationMatches(snapshot, assetKey(type, id), stored.identity().mutationId())) {
                throw new IOException("Graph tombstone state diverged from the shared asset coordinator: " + type + ':' + id);
            }
            Path liveFile = snapshot.path(assetKey(type, id)).orElse(null);
            if (liveFile != null && Files.exists(liveFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Graph tombstone has a physical live sibling: " + type + ':' + id);
            }
            requirePhysicalTombstoneCoordinated(snapshot, type, id, stored.tombstoneFile(), stored.identity().mutationId());
            return snapshot;
        }
        if (!(coordinated instanceof AssetTransactionCoordinator.Live live)
            || live.revision() != stored.identity().revision() || stored.assetFile() == null
            || !snapshot.path(assetKey(type, id)).map(path -> path.toAbsolutePath().normalize()
                .equals(stored.assetFile().toAbsolutePath().normalize())).orElse(false)
            || !verifyGraphAsset(stored.assetFile(), type, id)
            || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(stored.assetFile())))
            || !coordinatedMutationMatches(snapshot, assetKey(type, id), stored.identity().mutationId())) {
            throw new IOException("Graph live state diverged from the shared asset coordinator: " + type + ':' + id);
        }
        requireGraphTombstoneLineageForLive(snapshot, type, id, stored.tombstoneFile());
        return snapshot;
    }

    private void requireGraphTombstoneLineageForLive(AssetTransactionCoordinator.Snapshot snapshot,
                                                      String type, String id, Path tombstoneFile) throws IOException {
        AssetTransactionCoordinator.AssetKey key = tombstoneKey(type, id);
        AssetTransactionCoordinator.ExpectedState tombstoneState = snapshot.state(key)
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        if (tombstoneState instanceof AssetTransactionCoordinator.Missing) {
            if (tombstoneFile != null && Files.exists(tombstoneFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Graph live state has a physical tombstone sibling: " + type + ':' + id);
            }
            return;
        }
        if (!(tombstoneState instanceof AssetTransactionCoordinator.Deleted)
            || !snapshot.path(key).map(path -> tombstoneFile != null
                && path.toAbsolutePath().normalize().equals(tombstoneFile.toAbsolutePath().normalize())).orElse(false)
            || (tombstoneFile != null && Files.exists(tombstoneFile, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Graph live state has inconsistent tombstone coordinator lineage: " + type + ':' + id);
        }
    }

    private boolean coordinatedMutationMatches(AssetTransactionCoordinator.Snapshot snapshot,
                                                 AssetTransactionCoordinator.AssetKey key,
                                                 String mutationId) {
        return mutationId != null && snapshot.mutationValue(key).filter(mutationId::equals).isPresent();
    }

    private void requirePhysicalTombstoneCoordinated(AssetTransactionCoordinator.Snapshot snapshot,
                                                      String type, String id, Path tombstone,
                                                      String mutationId) throws IOException {
        AssetTransactionCoordinator.AssetKey key = tombstoneKey(type, id);
        AssetTransactionCoordinator.ExpectedState state = snapshot.state(key)
            .orElse(AssetTransactionCoordinator.Missing.INSTANCE);
        Path liveFile = snapshot.path(assetKey(type, id)).orElse(null);
        if (!(state instanceof AssetTransactionCoordinator.Live live) || tombstone == null
            || !snapshot.path(key).map(path -> path.toAbsolutePath().normalize()
                .equals(tombstone.toAbsolutePath().normalize())).orElse(false)
            || !Files.isRegularFile(tombstone, LinkOption.NOFOLLOW_LINKS)
            || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(tombstone)))
            || !coordinatedMutationMatches(snapshot, key, mutationId)) {
            throw new IOException("Physical graph tombstone diverged from the shared asset coordinator: " + type + ':' + id);
        }
        if (liveFile != null && Files.exists(liveFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Physical graph tombstone has a live sibling: " + type + ':' + id);
        }
    }

    private List<AssetTransactionCoordinator.ProjectDelta> projectDeltas(
        AssetTransactionCoordinator.Snapshot snapshot, String projectJson) {
        JsonElement parsed = JsonParser.parseString(projectJson == null || projectJson.isBlank() ? "{}" : projectJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Flow project metadata must be a JSON object");
        }
        JsonObject desired = parsed.getAsJsonObject();
        JsonObject current = snapshot.metadata().document();
        List<AssetTransactionCoordinator.ProjectDelta> deltas = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : desired.entrySet()) {
            JsonElement value = preserveProjectCollectionFields(entry.getKey(), entry.getValue(), current.get(entry.getKey()));
            JsonElement previous = current.get(entry.getKey());
            if (!value.equals(previous)) {
                deltas.add(AssetTransactionCoordinator.ProjectDelta.set(List.of(entry.getKey()), value));
            }
        }
        for (String field : List.of("serverId", "folders", "resources")) {
            if (!desired.has(field) && current.has(field)) {
                deltas.add(AssetTransactionCoordinator.ProjectDelta.remove(List.of(field)));
            }
        }
        return List.copyOf(deltas);
    }

    private String projectMetadataAfter(AssetTransactionCoordinator.Snapshot snapshot,
                                        List<AssetTransactionCoordinator.ProjectDelta> deltas) {
        List<AssetProjectMetadata.Delta> metadataDeltas = deltas.stream()
            .map(delta -> new AssetProjectMetadata.Delta(delta.path(), delta.value(), delta.remove()))
            .toList();
        return snapshot.metadata().apply(metadataDeltas).serializedJson();
    }

    private JsonElement preserveProjectCollectionFields(String field, JsonElement desired, JsonElement current) {
        List<String> identity = switch (field) {
            case "resources" -> List.of("type", "id");
            case "folders" -> List.of("path");
            default -> List.of();
        };
        if (identity.isEmpty() || desired == null || !desired.isJsonArray() || current == null || !current.isJsonArray()) {
            return desired;
        }
        var merged = desired.getAsJsonArray().deepCopy();
        for (int index = 0; index < merged.size(); index++) {
            JsonElement candidate = merged.get(index);
            if (!candidate.isJsonObject()) {
                continue;
            }
            JsonObject desiredObject = candidate.getAsJsonObject();
            JsonObject currentObject = current.getAsJsonArray().asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(existing -> identity.stream().allMatch(key -> existing.has(key) && desiredObject.has(key)
                    && existing.get(key).equals(desiredObject.get(key))))
                .findFirst()
                .map(JsonObject::deepCopy)
                .orElseGet(JsonObject::new);
            desiredObject.entrySet().forEach(entry -> currentObject.add(entry.getKey(), entry.getValue()));
            merged.set(index, currentObject);
        }
        return merged;
    }

    private AssetTransactionCoordinator.AssetKey assetKey(String type, String id) {
        return new AssetTransactionCoordinator.AssetKey(type, id);
    }

    private AssetTransactionCoordinator.AssetKey tombstoneKey(String type, String id) {
        return assetKey("tombstone:" + type, id);
    }

    private record AssetMutation(AssetTransactionCoordinator.AssetKey key, Path path, byte[] content,
                                 boolean deleted) {
        private AssetMutation {
            Objects.requireNonNull(key, "Asset mutation key is required");
            Objects.requireNonNull(path, "Asset mutation path is required");
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        private static AssetMutation write(AssetTransactionCoordinator.AssetKey key, Path path, byte[] content) {
            return new AssetMutation(key, path, content, false);
        }

        private static AssetMutation delete(AssetTransactionCoordinator.AssetKey key, Path path) {
            return new AssetMutation(key, path, new byte[0], true);
        }
    }

    private record SavedResource(Path path) {
    }

    private static class ProjectMetadataSnapshot {
        private String serverId = "project";
        private List<FolderEntrySnapshot> folders = new ArrayList<>();
        private List<ResourceEntrySnapshot> resources = new ArrayList<>();

        private List<FolderEntrySnapshot> folders() {
            return folders != null ? folders : List.of();
        }

        private List<ResourceEntrySnapshot> resources() {
            return resources != null ? resources : List.of();
        }

        private List<FolderEntrySnapshot> mutableFolders() {
            if (folders == null) {
                folders = new ArrayList<>();
            }
            return folders;
        }

        private List<ResourceEntrySnapshot> mutableResources() {
            if (resources == null) {
                resources = new ArrayList<>();
            }
            return resources;
        }

        private FolderEntrySnapshot findFolder(String path) {
            for (FolderEntrySnapshot folder : folders()) {
                if (folder != null && path.equals(folder.path)) {
                    return folder;
                }
            }
            return null;
        }

        private ResourceEntrySnapshot findResource(String type, String id) {
            for (ResourceEntrySnapshot resource : resources()) {
                if (resource != null && type.equals(resource.type) && id.equals(resource.id)) {
                    return resource;
                }
            }
            return null;
        }

        private ResourceEntrySnapshot findExactResource(String type, String id) {
            for (ResourceEntrySnapshot resource : resources()) {
                if (resource != null && type.equals(resource.type) && id.equals(resource.id)) {
                    return resource;
                }
            }
            return null;
        }

    }

    private static class FolderEntrySnapshot {
        private String path = "";
        private String parentPath = "";
        private String name = "";
        private int sortOrder;
        private boolean collapsed;
    }

    private static class ResourceEntrySnapshot {
        private String type = "";
        private String id = "";
        private String displayName = "";
        private String path = "";
        private int sortOrder;
    }

    private static class CustomContentSnapshot {
        private String type = "";
    }

}
