package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.bukkit.entity.Player;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.RuntimeDataRegistry;
import restudio.resync.api.ReSyncExtensionData;
import restudio.resync.api.ReSyncExtensionManager;
import restudio.resync.api.ExtensionPersistenceParticipant;
import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.ChannelMuxer;
import restudio.resync.core.CollaborationIdentity;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionManager;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.core.SessionManager;
import restudio.resync.customcontent.CustomBlocksPersistenceParticipant;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.dialog.DialogService;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.flow.handler.generic.ManagedFlowFileCapability;
import restudio.resync.flow.handler.generic.ManagedFlowFilePersistenceParticipant;
import restudio.resync.flow.handler.generic.RegionPersistenceParticipant;
import restudio.resync.flow.handler.generic.SqliteManagedFlowFileCapability;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.automation.AutomationTaskPersistenceParticipant;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogStartupIndex;
import restudio.resync.flow.catalog.CatalogStartupIndexPersistenceParticipant;
import restudio.resync.flow.diagnostics.DiagnosticReportPersistenceParticipant;
import restudio.resync.flow.diagnostics.StructuredFlowDiagnosticReporter;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.runtime.ReplacementRuntimeProviderAuthority;
import restudio.resync.flow.runtime.DurableRuntimeReceiptStore;
import restudio.resync.flow.runtime.FlowRuntimeExecutionBoundary;
import restudio.resync.flow.runtime.FlowRuntimeSecurityBoundary;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeBoundaryHealth;
import restudio.resync.flow.runtime.RuntimeExecutionBoundary;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeSecurityBoundary;
import restudio.resync.flow.runtime.StructuredRuntimeAuditBoundary;
import restudio.resync.flow.triggers.TriggerPersistenceParticipant;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.flow.network.NetworkFlowBridge;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.EphemeralLifecycleParticipant;
import restudio.resync.migration.MigrationReportsPersistenceParticipant;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.AuthorityUseGrant;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.PersistenceReadinessCertificate;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ManagedFlowFileStoreContract;
import restudio.resync.migration.ProductionAuthorityBundle;
import restudio.resync.migration.ProductionAuthorityBundleExporter;
import restudio.resync.migration.ProductionAuthorityBundlePersistenceParticipant;
import restudio.resync.migration.ProductionAuthorityTrustAnchor;
import restudio.resync.migration.ReplacementActivationRecord;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.FreshInstallInputs;
import restudio.resync.migration.ScopedPersistenceParticipant;
import restudio.resync.migration.PersistenceShutdownStatus;
import restudio.resync.memory.MemoryMonitor;
import restudio.resync.messages.MessageLogService;
import restudio.resync.modules.ChunkTransportModule;
import restudio.resync.modules.ChatModule;
import restudio.resync.modules.AdvancementModule;
import restudio.resync.modules.FlowJobModule;
import restudio.resync.modules.FlowModule;
import restudio.resync.qa.QaService;
import restudio.resync.qa.ServerQaService;
import restudio.resync.qa.QaExecutionAdapter;
import restudio.resync.qa.QaResourceAdapter;
import restudio.resync.qa.QaGameAdapter;
import restudio.resync.qa.QaAdvancementAdapter;
import restudio.resync.modules.FlowRuntimeModule;
import restudio.resync.modules.MessageRewriteModule;
import restudio.resync.modules.LuckPermsManagementModule;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleContext;
import restudio.resync.modules.ModuleRegistry;
import restudio.resync.modules.MotdModule;
import restudio.resync.modules.PlayerNpcPacketModule;
import restudio.resync.modules.PlayerTrackingModule;
import restudio.resync.modules.RecipeModule;
import restudio.resync.modules.WorldManagementModule;
import restudio.resync.modules.WorldGenModule;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.network.paper.NetworkPathSynchronizer;
import restudio.resync.network.paper.NetworkPersistenceDrainController;
import restudio.resync.network.paper.NetworkPersistenceParticipant;
import restudio.resync.network.paper.NetworkResourceSynchronizer;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.network.paper.ReSyncNetworkAgent;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig;
import restudio.resync.network.paper.NetworkSettings;
import restudio.resync.network.paper.state.NetworkPlayerStateConfig;
import restudio.resync.network.paper.state.NetworkPlayerStateCoordinator;
import restudio.resync.permissions.LuckPermsManagementService;
import restudio.resync.permissions.LuckPermsBackendPersistenceCapability;
import restudio.resync.permissions.LuckPermsBackendPersistenceParticipant;
import restudio.resync.permissions.LuckPermsOperationPersistenceParticipant;
import restudio.resync.player.DefaultPlayerSessionLinkService;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.player.PlayerTrackingManager;
import restudio.resync.player.PlayerTrackingPersistenceParticipant;
import restudio.resync.player.PlayerTrackingService;
import restudio.resync.structure.StructureLibrary;
import restudio.resync.structure.StructurePersistenceParticipant;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldManagementPersistenceParticipant;
import restudio.resync.world.WorldAuditPersistenceParticipant;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.FrameHeader;
import restudio.resync.protocol.MessageType;
import restudio.resync.protocol.ProtocolEnvelopeBoundary;
import restudio.resync.protocol.ReSyncProtocolInventory;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.protocol.messages.ChannelRegistryMessage;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.ErrorMessage;
import restudio.resync.protocol.messages.HandshakeRequest;
import restudio.resync.protocol.messages.HandshakeResponse;
import restudio.resync.protocol.messages.Heartbeat;
import restudio.resync.protocol.messages.Message;
import restudio.resync.protocol.messages.ProtocolEnvelopeMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;
import restudio.resync.queue.RateLimiter;
import restudio.resync.queue.RequestQueue;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.runtime.RuntimeNotificationService;
import restudio.resync.runtime.NpcService;
import restudio.resync.runtime.PlayerNpcRuntime;
import restudio.resync.runtime.PlayerNpcPersistenceParticipant;
import restudio.resync.security.ClientAuthorizer;
import restudio.resync.security.ClientIdentity;
import restudio.resync.server.ReSyncConfig;
import restudio.resync.text.ReTextService;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetIntegrityService;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetsPersistenceParticipant;
import restudio.resync.upgrade.AssetCoordinatorMigration;
import restudio.resync.upgrade.AssetCoordinatorMigration.FreshRootAuthority;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.WorldGenGeneratedOutputPolicy;
import restudio.resync.worldgen.WorldGenGeneratedPersistenceParticipant;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackCapability;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackPersistenceParticipant;
import restudio.resync.flow.catalog.CatalogStartupIndexPersistenceParticipant;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public class ReSyncServer {
    private static final String HOSTED_SERVER_ID_FILE = ".resync-host-server-id";
    private static final Gson GSON = new Gson();
    private static final long BRIDGE_HANDSHAKE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(8);
    private static final long BRIDGE_HANDSHAKE_TIMEOUT_TICKS = 160L;
    private static final String FLOW_FILE_MIGRATION_REASON =
        "If legacy physical FileHandler bytes are present, they require an explicit manifest-backed offline migration; runtime startup does not scan or delete arbitrary legacy bytes";
    private final ReSync plugin;
    private final ReSyncConfig config;
    private final ReSyncPersistenceCoordinator persistence;
    private final Path operatorDataRoot;
    private final LifecycleDiagnosticFileSink lifecycleDiagnosticSink;
    private final Path dataRoot;
    private final AssetCoordinatorMigration.Result assetMigration;
    private final FreshRootAuthority freshRootAuthority;
    private final PaperPlayerDataMutationAdmission playerDataAdmission;
    private final PaperPlayerDataMutationAdmission.Installation playerDataAdmissionInstallation;
    private final ConnectionManager connectionManager;
    private final SessionManager sessionManager;
    private final ChannelMuxer channelMuxer;
    private final ModuleRegistry moduleRegistry;
    private final RequestQueue requestQueue;
    private final RateLimiter rateLimiter;
    private final CompressionPool compressionPool;
    private final Codec codec;
    private final AuthorityEpoch authorityEpoch;
    private final ProtocolEnvelopeDispatchBoundary protocolEnvelopeDispatch;
    private final ProtocolEnvelopeMailbox protocolEnvelopeMailbox;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService handshakeExecutor;
    private final OptionCatalogCaptureExecutor.Bounded optionCatalogExecutor;
    private final Map<ConnectionInfo, BridgeHandshake> bridgeHandshakes = new ConcurrentHashMap<>();
    private final Map<ConnectionInfo, CompletableFuture<Boolean>> authoringHandshakes = new ConcurrentHashMap<>();
    private final Semaphore handshakeProbes = new Semaphore(2);
    private final MemoryMonitor memoryMonitor;
    private final ModuleContext moduleContext;
    private final ClientAuthorizer clientAuthorizer;
    private final ReSyncExtensionManager extensionManager;
    private final ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority;
    private final LuckPermsBackendPersistenceCapability configuredLuckPermsBackendPersistence;
    private RebindablePersistenceParticipant dormantLuckPermsJournal;
    private final PlayerTrackingManager playerTrackingManager;
    private final StructureLibrary structureLibrary;
    private SqliteProtocolResourceMutationAuthority resourceMutationAuthority;
    private volatile FlowResourceProtocolEnvelopeHandler resourceProtocolHandler;
    private volatile ServerQaService qaService;
    private volatile AuthoringTemplateProducer authoringTemplateProducer;
    private volatile ProviderOptionQueryService providerOptionQueryService;
    private volatile String resourceMutationAuthorityUnavailableReason = "Durable resource mutation authority is unavailable";
    private final AtomicInteger openConnections = new AtomicInteger();
    private volatile ReSyncPersistenceTopology.Registration persistenceRegistration;
    private volatile ManagedFlowFileCapability managedFlowFileCapability;
    private volatile ManagedFlowFilePersistenceParticipant managedFlowFilePersistenceParticipant;
    private volatile DiagnosticReportPersistenceParticipant diagnosticsPersistenceParticipant;
    private ScopedPersistenceParticipant startupDiagnostics;
    private volatile ProductionAuthorityBundlePersistenceParticipant authorityBundlePersistenceParticipant;
    private final ProductionAuthorityIssuer authorityIssuer;
    private volatile MigrationReportsPersistenceParticipant migrationReportsPersistenceParticipant;
    private volatile AssetTransactionCoordinator assetTransactions;
    private volatile AssetsPersistenceParticipant assetsPersistenceParticipant;
    private final AtomicBoolean unownedAssetCleanupScheduled = new AtomicBoolean();
    private final AtomicBoolean unownedAssetCleanupStarted = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final AtomicBoolean coreShutdownPrepared = new AtomicBoolean();
    private final AtomicBoolean coreShutdownPipelineStarted = new AtomicBoolean();
    private final AtomicBoolean protocolShutdownAwaiting = new AtomicBoolean();
    private final AtomicBoolean coreShutdownFinalized = new AtomicBoolean();
    private final AtomicBoolean coreShutdownAwaitingModules = new AtomicBoolean();
    private final CompletableFuture<Void> coreShutdownCompletion = new CompletableFuture<>();
    private final ReSyncShutdownCoordinator shutdownCoordinator;
    private final Object shutdownRetryMonitor = new Object();
    private volatile CompletableFuture<Void> coreRetryAttempt;
    private volatile CompletionStage<Void> coreModuleShutdown = CompletableFuture.completedFuture(null);
    private volatile ReSyncNetworkAgentConfig networkConfig;
    private volatile ReSyncNetworkAgent networkAgent;
    private volatile List<NetworkPathSynchronizer> networkPathSynchronizers = List.of();
    private volatile NetworkResourceSynchronizer networkResourceSynchronizer;
    private volatile NetworkPlayerStateCoordinator networkPlayerStateCoordinator;
    private final AtomicBoolean networkPrepared = new AtomicBoolean();
    private final AtomicBoolean networkShutdownPrepared = new AtomicBoolean();
    private final AtomicBoolean networkStarted = new AtomicBoolean();
    private volatile String networkPreparationFailure;
    private volatile boolean networkPlayerStateEnabled;
    private volatile CompletableFuture<NetworkShutdownResult> networkShutdownAttempt;

    public ReSyncServer(ReSync plugin, ReSyncConfig config) {
        this(plugin, config, null, null, null, null, null, missingAssetMigration());
    }

    public ReSyncServer(ReSync plugin, ReSyncConfig config, ProtocolEnvelopeHandler protocolEnvelopeHandler) {
        this(plugin, config, protocolEnvelopeHandler, null, null, null, null, missingAssetMigration());
    }

    public ReSyncServer(ReSync plugin, ReSyncConfig config, ProtocolEnvelopeHandler protocolEnvelopeHandler,
                        ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority) {
        this(plugin, config, protocolEnvelopeHandler, replacementRuntimeProviderAuthority, null, null, null,
            missingAssetMigration());
    }

    public ReSyncServer(ReSync plugin, ReSyncConfig config, ReSyncPersistenceCoordinator persistence, Path activeRoot) {
        this(plugin, config, null, null, null, persistence, activeRoot, missingAssetMigration());
    }

    public ReSyncServer(ReSync plugin, ReSyncConfig config, ReSyncPersistenceCoordinator persistence, Path activeRoot,
                        AssetCoordinatorMigration.Result assetMigration) {
        this(plugin, config, null, null, null, persistence, activeRoot, assetMigration);
    }

    public ReSyncServer(ReSync plugin, ReSyncConfig config, ProtocolEnvelopeHandler protocolEnvelopeHandler,
                        ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority,
                        LuckPermsBackendPersistenceCapability luckPermsBackendPersistence) {
        this(plugin, config, protocolEnvelopeHandler, replacementRuntimeProviderAuthority,
            luckPermsBackendPersistence, null, null, missingAssetMigration());
    }

    private ReSyncServer(ReSync plugin, ReSyncConfig config, ProtocolEnvelopeHandler protocolEnvelopeHandler,
                         ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority,
                         LuckPermsBackendPersistenceCapability luckPermsBackendPersistence,
                         ReSyncPersistenceCoordinator suppliedPersistence, Path suppliedActiveRoot,
                         AssetCoordinatorMigration.Result assetMigration) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        PreparedPersistence preparedPersistence = preparePersistence(plugin, suppliedPersistence, suppliedActiveRoot);
        this.persistence = preparedPersistence.coordinator();
        this.operatorDataRoot = this.persistence.dataRoot().toAbsolutePath().normalize();
        this.lifecycleDiagnosticSink = TemporaryLifecycleDiagnostics.bind(this.operatorDataRoot);
        this.dataRoot = preparedPersistence.activeRoot();
        this.authorityEpoch = new AuthorityEpoch(() -> {
            try {
                return this.persistence.authorityEpoch();
            } catch (IOException exception) {
                throw new IllegalStateException("ReSync authority epoch could not be read", exception);
            }
        });
        this.assetMigration = Objects.requireNonNull(assetMigration, "assetMigration");
        PaperPlayerDataMutationAdmission admission = PaperPlayerDataMutationAdmission.forBukkitWorlds();
        this.playerDataAdmission = admission;
        PaperPlayerDataMutationAdmission.Installation admissionInstallation = null;
        long startupStarted = System.nanoTime();
        long modulesInitialized = startupStarted;
        long persistencePrepared = startupStarted;
        long modulesStarted = startupStarted;
        long flowReady = startupStarted;
        long authorityReady = startupStarted;
        long worldGenReady = startupStarted;
        long extensionsReady = startupStarted;
        try {
            admission.refreshFromBukkit();
            admissionInstallation = PaperPlayerDataMutationAdmission.installShared(admission);
            this.playerDataAdmissionInstallation = admissionInstallation;
            this.replacementRuntimeProviderAuthority = replacementRuntimeProviderAuthority == null
                ? ReplacementRuntimeProviderAuthority.unavailable()
                : replacementRuntimeProviderAuthority;
            this.configuredLuckPermsBackendPersistence = luckPermsBackendPersistence;
            ServerIdentityStore initialIdentity;
            this.freshRootAuthority = this.assetMigration
                .freshRootAuthority(this.persistence.coordinationRoot(), dataRoot).orElse(null);
            initialIdentity = ServerIdentityStore.open(dataRoot.resolve("server-id"), this.freshRootAuthority,
                hostedServerId(), LuckPermsOperationPersistenceParticipant::prepareFreshJournal);
            if (luckPermsBackendPersistence != null || Bukkit.getServer() != null
                && Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
                requireExistingRuntimeJournal(initialIdentity, dataRoot
                    .resolve(LuckPermsOperationPersistenceParticipant.DIRECTORY)
                    .resolve(LuckPermsOperationPersistenceParticipant.FILE_NAME));
            }
            TemporaryLifecycleDiagnostics.bindServerId(initialIdentity.serverId());
            TemporaryLifecycleDiagnostics.event("startup_diagnostics", startupStarted,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(initialIdentity.serverId(), null,
                    null, null, null, null, authorityEpoch.current(), null), "outcome", "enabled",
                    "property", TemporaryLifecycleDiagnostics.PROPERTY, "environment", TemporaryLifecycleDiagnostics.ENVIRONMENT));
            this.authorityIssuer = new ProductionAuthorityIssuer(initialIdentity);
            this.connectionManager = new ConnectionManager(30, 60);
            this.compressionPool = new CompressionPool(config.getCompression().getLevel(), 10);
            this.codec = new Codec(compressionPool, config.getMaxEncodedFrameBytes(), config.getMaxDecompressedPayloadBytes());
            this.channelMuxer = new ChannelMuxer();
            this.moduleRegistry = new ModuleRegistry();
            this.requestQueue = new RequestQueue(
                config.getQueue().getMaxGlobalRequests(),
                config.getQueue().getMaxRequestsPerClient(),
                4
            );
            this.memoryMonitor = new MemoryMonitor(config.getMemory().getSessionMemoryRatio());
            this.sessionManager = new SessionManager(memoryMonitor, 300, config.getMemory().getMaxMemoryPerSession());
            this.rateLimiter = new RateLimiter(1000, 10, 1000);
            this.clientAuthorizer = new ClientAuthorizer(config);
            this.protocolEnvelopeDispatch = new ProtocolEnvelopeDispatchBoundary(authorityEpoch, protocolEnvelopeHandler);
            this.protocolEnvelopeMailbox = new ProtocolEnvelopeMailbox(protocolEnvelopeDispatch,
                ProtocolEnvelopeMailbox.Limits.standard(config.getQueue().getMaxRequestsPerClient(),
                    config.getQueue().getMaxGlobalRequests(), config.getMaxDecompressedPayloadBytes()));
            this.scheduler = Executors.newScheduledThreadPool(2);
            this.handshakeExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), runnable -> {
                    Thread thread = new Thread(runnable, "ReSync-Handshake");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
            this.optionCatalogExecutor = OptionCatalogCaptureExecutor.bounded(2, Duration.ofSeconds(10),
                Bukkit::isPrimaryThread, runnable -> Bukkit.getScheduler().runTask(plugin, runnable), runnable -> {
                    Thread thread = new Thread(runnable, "ReSync-Option-Catalog");
                    thread.setDaemon(true);
                    return thread;
                });
            this.moduleContext = new ModuleContext(
                plugin,
                this,
                config,
                connectionManager,
                sessionManager,
                channelMuxer,
                moduleRegistry,
                requestQueue,
                rateLimiter,
                compressionPool,
                codec,
                scheduler,
                memoryMonitor
            );
            moduleContext.registerService(PaperPlayerDataMutationAdmission.class, playerDataAdmission);
            moduleContext.registerService(ServerIdentityStore.class, initialIdentity);
            moduleContext.registerService(AuthorityEpoch.class, authorityEpoch);
            this.playerTrackingManager = new PlayerTrackingManager(dataRoot);
            this.structureLibrary = StructureLibrary.get(plugin, dataRoot);
            this.shutdownCoordinator = new ReSyncShutdownCoordinator(
                () -> shutdownNetworkAfterPreparation().thenApply(result -> result != null && result.completed()),
                this::retryPreparedShutdown,
                this::finishCoreShutdown,
                this::coreShutdownCompletion);
        } catch (IOException exception) {
            try {
                admission.close();
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            if (admissionInstallation != null) {
                PaperPlayerDataMutationAdmission.clearSharedInstallation(admissionInstallation);
            }
            TemporaryLifecycleDiagnostics.close(lifecycleDiagnosticSink);
            throw new IllegalStateException("ReSync startup persistence could not be loaded: " + exception.getMessage(), exception);
        } catch (RuntimeException failure) {
            try {
                admission.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (admissionInstallation != null) {
                PaperPlayerDataMutationAdmission.clearSharedInstallation(admissionInstallation);
            }
            TemporaryLifecycleDiagnostics.close(lifecycleDiagnosticSink);
            throw failure;
        }
        try {
            registerCoreServices();
            Path extensionDirectory = dataRoot.resolve("extensions");
            this.extensionManager = new ReSyncExtensionManager(moduleContext, extensionDirectory);
            moduleContext.registerService(ReSyncExtensionManager.class, extensionManager);
            registerModules();
            moduleRegistry.initializeModules(moduleContext);
            modulesInitialized = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("startup_stage", startupStarted,
                Map.of("stageName", "modules", "outcome", "complete"));
            FlowRuntimeModule flowRuntimeModule = moduleContext.getService(FlowRuntimeModule.class);
            FlowModule flowModule = moduleContext.getService(FlowModule.class);
            CoreGraphResourceAuthority coreGraphResourceAuthority = null;
            if (flowRuntimeModule != null) {
                coreGraphResourceAuthority = flowRuntimeModule.bindCoreGraphResourceAuthority();
                if (!coreGraphResourceAuthority.available()) {
                    throw new IllegalStateException(CoreGraphResourceAuthority.UNAVAILABLE_MESSAGE);
                }
            }
            FlowResourceRegistry resourceRegistry = moduleContext.getService(FlowResourceRegistry.class);
            if (resourceRegistry != null) {
                if (coreGraphResourceAuthority == null || !coreGraphResourceAuthority.available()) {
                    throw new IllegalStateException(CoreGraphResourceAuthority.UNAVAILABLE_MESSAGE);
                }
                resourceRegistry.bindCoreGraphResourceAuthority(coreGraphResourceAuthority);
                resourceRegistry.requireGenericMutationAuthority(
                    "Legacy resource packet mutations are disabled; use the generic authoritative resource protocol");
            }
            if (flowRuntimeModule != null) {
                ExtensionRegistryActivation registryActivation = flowRuntimeModule.registerExtensionRegistryActivation();
                if (moduleContext.getRequiredService(ExtensionRegistryActivation.class) != registryActivation) {
                    throw new IllegalStateException("ReSync Extension Registry Activation Seam Is Inconsistent");
                }
            }
            if (resourceRegistry != null) {
                if (protocolEnvelopeHandler == null) {
                    ServerId serverId = moduleContext.getRequiredService(ServerIdentityStore.class).serverId();
                    CatalogPublicationReceiptStore receiptStore = moduleContext.getService(CatalogPublicationReceiptStore.class);
                    authoringTemplateProducer = flowRuntimeModule == null || flowModule == null || receiptStore == null ? null
                        : new AuthoringTemplateProducer(serverId, flowModule::activeCatalogRuntimeActivation,
                            () -> moduleContext.getService(CatalogActivationAuthority.class), receiptStore,
                            flowModule::catalogPublicationForSession);
                    OptionCatalogRegistry optionCatalogRegistry = moduleContext.getService(OptionCatalogRegistry.class);
                    if (flowModule != null) {
                        flowModule.setOptionCatalogCaptureExecutor(optionCatalogExecutor);
                    }
                    providerOptionQueryService = flowModule == null || optionCatalogRegistry == null ? null
                        : new ProviderOptionQueryService(serverId, optionCatalogRegistry, flowModule::activeCatalogRuntimeActivation,
                            flowModule::catalogPublicationForSession, optionCatalogExecutor,
                            new FlowResourceOptionQueryAdapter(resourceRegistry, serverId), sessionManager::getSessions,
                            session -> session != null && currentProtocolSession(session.getConnection(), session),
                            ProtocolOptionQueryAuthorizer.serverGranted(),
                            ProtocolResourceAuthorizer.serverGranted(), this::sendProviderOptionInvalidation,
                            authorityEpoch::current);
                    if (flowModule != null) {
                        flowModule.setProviderOptionQuerySourceChange(providerOptionQueryService == null ? null
                            : providerOptionQueryService::sourceChanged);
                    }
                    long authorityStarted = TemporaryLifecycleDiagnostics.start();
                    try {
                        resourceMutationAuthority = new SqliteProtocolResourceMutationAuthority(resourceRegistry, serverId,
                            dataRoot.resolve("runtime").resolve("resource-mutations.db"),
                            coreGraphResourceAuthority, ProtocolResourceAuthorizer.serverGranted(), authorityEpoch,
                            resourceRegistry, moduleContext.getRequiredService(
                                MigrationReportsPersistenceParticipant.class),
                            SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED);
                        setProtocolEnvelopeHandler(new FlowResourceProtocolEnvelopeHandler(resourceRegistry, serverId, resourceMutationAuthority,
                            ProtocolResourceAuthorizer.serverGranted(), authorityEpoch, authoringTemplateProducer, providerOptionQueryService));
                        TemporaryLifecycleDiagnostics.event("protocol_authority_binding", authorityStarted,
                            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, null,
                                null, null, null, null, authorityEpoch.current(), null), "outcome", "bound",
                                "durableCommitted", resourceMutationAuthority.durable(),
                                "coreAuthorityAvailable", resourceMutationAuthority.authoritativeCoreReads()));
                    } catch (RuntimeException exception) {
                        resourceMutationAuthorityUnavailableReason = persistenceReason(
                            "Durable resource mutation authority could not be created", exception);
                        resourceMutationAuthority = null;
                        setProtocolEnvelopeHandler(new FlowResourceProtocolEnvelopeHandler(resourceRegistry, serverId,
                            ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(), authorityEpoch,
                            authoringTemplateProducer, providerOptionQueryService));
                        TemporaryLifecycleDiagnostics.event("protocol_authority_binding", authorityStarted,
                            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, null,
                                null, null, null, null, authorityEpoch.current(), null), "outcome", "failed_closed",
                                "failure", exception.getClass().getSimpleName()));
                        Log.error(resourceMutationAuthorityUnavailableReason, exception);
                    }
                }
            }
            moduleRegistry.addListener(new ModuleRegistry.ModuleChangeListener() {
                @Override
                public void onModuleRegistered(Module module) {
                    publishChannelDelta(module.getChannels(), List.of());
                }

                @Override
                public void onModuleUnregistered(String moduleId, Set<String> channels) {
                    publishChannelDelta(List.of(), new ArrayList<>(channels));
                }
            });
            prepareNetworkTopology();
            registerAssetsPersistenceParticipant();
            ReSyncPersistenceTopology.Registration registration = persistenceRegistration;
            if (registration == null) {
                throw new IllegalStateException("ReSync persistence topology readiness is unavailable");
            }
            persistencePrepared = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("startup_stage", modulesInitialized,
                Map.of("stageName", "persistence", "outcome", "complete"));
            moduleRegistry.startModules(moduleContext);
            modulesStarted = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("startup_stage", persistencePrepared,
                Map.of("stageName", "moduleStart", "outcome", "complete"));
            ReSyncPersistenceCoordinator.ReadinessProof readinessProof = persistence
                .currentValidatedReadinessProof(registration.readiness())
                .orElseThrow(() -> new IllegalStateException("ReSync persistence topology is not restore-ready"));
            if (flowRuntimeModule != null) {
                SqliteProtocolResourceMutationAuthority settlementAuthority = resourceMutationAuthority;
                flowRuntimeModule.bindCatalogSettlement(expected -> {
                    if (settlementAuthority == null || resourceMutationAuthority != settlementAuthority
                        || flowModule == null || flowModule.activeCatalogRuntimeActivation() != expected) {
                        throw new IllegalStateException("Final Flow resource settlement authority is unavailable or changed");
                    }
                    settlementAuthority.settleCatalogBinding(new CatalogBinding(expected.catalog().generation(),
                        expected.catalog().contentChecksum(), expected.runtimeManifest().bindingManifestHash()));
                    if (resourceMutationAuthority != settlementAuthority
                        || flowModule.activeCatalogRuntimeActivation() != expected) {
                        throw new IllegalStateException("Final Flow resource authority changed during settlement");
                    }
                });
                flowRuntimeModule.completeStartupActivation(readinessProof);
                flowReady = System.nanoTime();
                TemporaryLifecycleDiagnostics.event("startup_stage", modulesStarted,
                    Map.of("stageName", "flow", "outcome", "complete"));
                readinessProof = completeStartupAuthorityActivation(registration, readinessProof);
                registration = persistenceRegistration;
                authorityReady = System.nanoTime();
                TemporaryLifecycleDiagnostics.event("startup_stage", flowReady,
                    Map.of("stageName", "authority", "outcome", "complete"));
            } else {
                flowReady = modulesStarted;
                authorityReady = modulesStarted;
            }
            WorldGenModule worldGenModule = moduleContext.getService(WorldGenModule.class);
            if (worldGenModule != null) {
                readinessProof = worldGenModule.completeStartupActivation(readinessProof);
                registration = registration.withReadiness(readinessProof.readiness());
                persistenceRegistration = registration;
            } else {
                readinessProof = persistence.completeStartupActivation(readinessProof, Set.of());
            }
            worldGenReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("startup_stage", authorityReady,
                Map.of("stageName", "worldgen", "outcome", "complete"));
            this.extensionManager.loadInitialExtensions();
            extensionsReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("startup_stage", worldGenReady,
                Map.of("stageName", "extensions", "outcome", "complete"));
            publishPersistenceReadinessCertificate(readinessProof);
            initializeQa();
            startScheduler();
            long startupReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("startup_stage", extensionsReady,
                Map.of("stageName", "readiness", "outcome", "complete", "totalElapsedMs",
                    startupMillis(startupStarted, startupReady)));
            Log.info("Startup completed in " + startupMillis(startupStarted, startupReady) + " ms"
                + " [Modules " + startupMillis(startupStarted, modulesInitialized) + " ms"
                + ", Persistence " + startupMillis(modulesInitialized, persistencePrepared) + " ms"
                + ", Module Start " + startupMillis(persistencePrepared, modulesStarted) + " ms"
                + ", Flow " + startupMillis(modulesStarted, flowReady) + " ms"
                + ", Authority " + startupMillis(flowReady, authorityReady) + " ms"
                + ", WorldGen " + startupMillis(authorityReady, worldGenReady) + " ms"
                + ", Extensions " + startupMillis(worldGenReady, extensionsReady) + " ms"
                + ", Readiness " + startupMillis(extensionsReady, startupReady) + " ms]");
        } catch (RuntimeException failure) {
            Log.error("ReSync startup failed; shutting down initialized modules", failure);
            try {
                shutdownSynchronously(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            closeUnownedAssetCoordinationAfterStartupFailure(failure);
            TemporaryLifecycleDiagnostics.close(lifecycleDiagnosticSink);
            throw failure;
        }
    }

    private void initializeQa() {
        FlowRuntimeModule runtime = moduleContext.getRequiredService(FlowRuntimeModule.class);
        FlowModule flow = moduleContext.getRequiredService(FlowModule.class);
        ServerQaService service = new ServerQaService(authorityEpoch::current);
        QaResourceAdapter resources = new QaResourceAdapter(
            () -> moduleContext.getRequiredService(FlowResourceRegistry.class), () -> resourceProtocolHandler,
            () -> moduleContext.getRequiredService(ServerIdentityStore.class).serverId(), flow::activeCatalogSnapshot,
            authorityEpoch, action -> {
                if (Bukkit.isPrimaryThread()) action.run();
                else Bukkit.getScheduler().runTask(moduleContext.getPlugin(), action);
            }, protocolEnvelopeMailbox::submitOperator);
        QaExecutionAdapter execution = runtime.createQaExecutionAdapter();
        QaGameAdapter game = new QaGameAdapter(moduleContext.getPlugin(),
            () -> moduleContext.getService(CustomContentStorage.class), () -> moduleContext.getService(CustomContentService.class),
            () -> moduleContext.getService(NpcService.class), () -> moduleContext.getService(PlayerNpcRuntime.class));
        QaAdvancementAdapter advancements = new QaAdvancementAdapter(() -> moduleContext.getService(AdvancementModule.class));
        service.register(resources.describe(), resources::invoke);
        service.register(execution.describe(), execution::invoke);
        service.register(game.describe(), game::invoke);
        service.register(advancements.describe(), advancements::invoke);
        moduleContext.registerService(QaService.class, service);
        qaService = service;
    }

    public QaService getQaService() {
        return qaService;
    }

    private static long startupMillis(long started, long completed) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, completed - started));
    }

    private ReSyncPersistenceCoordinator.ReadinessProof completeStartupAuthorityActivation(
        ReSyncPersistenceTopology.Registration registration,
        ReSyncPersistenceCoordinator.ReadinessProof readinessProof) {
        CatalogActivationAuthority activationAuthority = moduleContext.getRequiredService(CatalogActivationAuthority.class);
        ProductionAuthorityBundlePersistenceParticipant participant = authorityBundlePersistenceParticipant;
        if (!activationAuthority.requiresBindingContext()) {
            return readinessProof;
        }
        if (participant == null) {
            throw new IllegalStateException("Production authority bundle persistence participant is unavailable");
        }
        try {
            exportProductionAuthorityBundle(false, readinessProof);
            ReSyncPersistenceTopology.Registration current = persistenceRegistration;
            if (current == null) {
                throw new IllegalStateException("Production authority startup removed persistence registration");
            }
            return persistence.currentValidatedReadinessProof(current.readiness())
                .orElseThrow(() -> new IllegalStateException("Production authority startup invalidated persistence readiness"));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Production authority bundle could not be exported and verified before strict startup", exception);
        }
    }

    private synchronized ReSyncPersistenceTopology.Registration refreshPersistenceRegistration() {
        persistence.invalidateReadinessCertificate();
        ReSyncPersistenceTopology.Registration current = persistenceRegistration;
        ReSyncPersistenceTopology.Registration refreshed = refreshPersistenceRegistration(persistence, current);
        if (refreshed != null) {
            persistenceRegistration = refreshed;
        }
        return refreshed;
    }

    static ReSyncPersistenceTopology.Registration refreshPersistenceRegistration(
        ReSyncPersistenceCoordinator persistence,
        ReSyncPersistenceTopology.Registration registration) {
        return ReSyncPersistenceTopology.refresh(persistence, registration);
    }

    static ReSyncPersistenceTopology.Registration requireProductionAuthorityExportReady(
        ReSyncPersistenceCoordinator persistence,
        ReSyncPersistenceTopology.Registration registration) throws IOException {
        ReSyncPersistenceTopology.Registration current = refreshPersistenceRegistration(persistence, registration);
        if (current == null || !current.sealed()) {
            throw new IOException("Production Authority Bundle Requires A Sealed Persistence Topology");
        }
        if (persistence.validateRestoreReadiness(current.readiness()).isEmpty()) {
            throw new IOException("Production Authority Bundle Requires Current Restore-Ready Persistence");
        }
        return current;
    }

    public synchronized ReSyncPersistenceTopology.Registration refreshPersistenceReadiness() {
        ReSyncPersistenceTopology.Registration refreshed = refreshPersistenceRegistration();
        publishPersistenceReadinessCertificate();
        return refreshed;
    }

    private synchronized void publishPersistenceReadinessCertificate() {
        publishPersistenceReadinessCertificate(null);
    }

    private synchronized void publishPersistenceReadinessCertificate(
        ReSyncPersistenceCoordinator.ReadinessProof startupProof) {
        ReSyncPersistenceTopology.Registration registration = persistenceRegistration;
        if (registration == null || !registration.sealed() || !registration.readiness().complete()
            || !persistence.sealed() || persistence.shutdownStarted()) {
            persistence.invalidateReadinessCertificate();
            return;
        }
        try {
            ReSyncPersistenceCoordinator.ReadinessProof proof = startupProof == null
                ? currentOrValidateReadinessProof(registration.readiness()).orElse(null)
                : persistence.currentValidatedReadinessProof(registration.readiness())
                    .filter(current -> current == startupProof).orElse(null);
            if (proof == null || persistence.publishReadinessCertificate(proof).isEmpty()) {
                Log.warn("ReSync persistence readiness certificate was rejected as stale");
            }
        } catch (RuntimeException exception) {
            persistence.invalidateReadinessCertificate();
            Log.warn(persistenceReason("ReSync persistence readiness certificate could not be published", exception));
        }
    }

    private Optional<ReSyncPersistenceCoordinator.ReadinessProof> currentOrValidateReadinessProof(
        PersistenceRootReadiness readiness) {
        Optional<ReSyncPersistenceCoordinator.ReadinessProof> current = persistence.currentValidatedReadinessProof(readiness);
        return current.isPresent() ? current : persistence.validateRestoreReadiness(readiness);
    }

    private static PreparedPersistence preparePersistence(ReSync plugin, ReSyncPersistenceCoordinator suppliedPersistence,
                                                          Path suppliedActiveRoot) {
        Path originalRoot = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        try {
            MigrationPaths.requirePath(originalRoot, "originalRoot");
            if (Files.exists(originalRoot, LinkOption.NOFOLLOW_LINKS)) {
                MigrationPaths.requireDirectory(originalRoot, "originalRoot");
            }
            ReSyncPersistenceCoordinator coordinator = Objects.requireNonNull(
                suppliedPersistence, "Prepared ReSync Persistence Coordination Is Required");
            if (!originalRoot.equals(coordinator.dataRoot().toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Prepared ReSync Persistence Coordination Has A Different Data Root");
            }
            Path activeRoot = MigrationPaths.requireDirectory(suppliedActiveRoot, "activeRoot");
            Path preparedRoot = coordinator.activeDataRoot().toAbsolutePath().normalize();
            if (!preparedRoot.equals(activeRoot)) {
                throw new IllegalArgumentException("Prepared ReSync Active Root Does Not Match Persistence Coordination");
            }
            return new PreparedPersistence(coordinator, MigrationPaths.requireDirectory(activeRoot, "activeRoot"));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed To Prepare ReSync Active Data Root", exception);
        }
    }

    private static AssetCoordinatorMigration.Result missingAssetMigration() {
        throw new IllegalStateException("Verified Asset Coordinator Migration Result Is Required");
    }

    private void registerCoreServices() {
        long stageStarted = TemporaryLifecycleDiagnostics.start();
        LegacyRuntimeActivationGate legacyRuntimeGate = LegacyRuntimeActivationGate.runtime(dataRoot);
        ServerIdentityStore serverIdentity = moduleContext.getService(ServerIdentityStore.class);
        if (serverIdentity == null) {
            try {
                serverIdentity = ServerIdentityStore.open(dataRoot.resolve("server-id"));
            } catch (IOException exception) {
                throw new IllegalStateException("ReSync server identity could not be loaded", exception);
            }
        }
        CatalogActivationAuthority activationAuthority = CatalogActivationAuthority.fromCommittedMigration(dataRoot);
        if (!activationAuthority.approved()
            && (serverIdentity.freshInstall() || ReplacementActivationRecord.exists(dataRoot))) {
            activationAuthority = CatalogActivationAuthority.freshInstall();
        }
        TemporaryLifecycleDiagnostics.event("core_service_stage", stageStarted,
            Map.of("stageName", "activationAuthority", "outcome", "complete"));
        AssetPersistenceGate assetsGate = new AssetPersistenceGate(dataRoot);
        stageStarted = TemporaryLifecycleDiagnostics.start();
        StructuredFlowDiagnosticReporter diagnosticReporter = new StructuredFlowDiagnosticReporter(dataRoot.resolve("diagnostics"));
        TemporaryLifecycleDiagnostics.event("core_service_stage", stageStarted,
            Map.of("stageName", "diagnostics", "outcome", "complete"));
        stageStarted = TemporaryLifecycleDiagnostics.start();
        initializePersistenceCoordinator(diagnosticReporter, serverIdentity.freshInstall(), activationAuthority);
        TemporaryLifecycleDiagnostics.event("core_service_stage", stageStarted,
            Map.of("stageName", "persistenceParticipants", "outcome", "complete"));
        moduleContext.registerService(ReSyncPersistenceCoordinator.class, persistence);
        moduleContext.registerService(MigrationFence.class, persistence.fence());
        moduleContext.registerService(PersistenceParticipantRegistry.class, persistence.participants());
        stageStarted = TemporaryLifecycleDiagnostics.start();
        managedFlowFileCapability = createManagedFlowFileCapability(dataRoot, serverIdentity.freshInstall(), persistence);
        TemporaryLifecycleDiagnostics.event("core_service_stage", stageStarted,
            Map.of("stageName", "managedFlowFiles", "outcome", "complete"));
        moduleContext.registerService(ManagedFlowFileCapability.class, managedFlowFileCapability);
        AssetCoordinatorMigration.Result migration = Objects.requireNonNull(
            assetMigration, "Verified Asset Coordinator Migration Result Is Required");
        AssetTransactionCoordinator assetTransactions = null;
        try {
            stageStarted = TemporaryLifecycleDiagnostics.start();
            FreshProjectMetadataBootstrap.Admission projectMetadataAdmission =
                FreshProjectMetadataBootstrap.preflight(dataRoot, this.freshRootAuthority);
            assetTransactions = migration.openOrAdopt(dataRoot.resolve("assets"), GSON);
            TemporaryLifecycleDiagnostics.event("core_service_stage", stageStarted,
                Map.of("stageName", "assetCoordinator", "outcome", "complete"));
            stageStarted = TemporaryLifecycleDiagnostics.start();
            projectMetadataAdmission.ensure(serverIdentity, assetTransactions, GSON);
            TemporaryLifecycleDiagnostics.event("core_service_stage", stageStarted,
                Map.of("stageName", "projectMetadata", "outcome", "complete"));
        } catch (IOException exception) {
            if (assetTransactions != null) {
                try {
                    assetTransactions.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            throw new IllegalStateException("Shared Asset Transaction Coordination Could Not Be Opened Or Bootstrapped", exception);
        }
        this.assetTransactions = assetTransactions;
        moduleContext.registerService(AssetTransactionCoordinator.class, assetTransactions);
        ReSyncJsonResourceStorage jsonResourceStorage = new ReSyncJsonResourceStorage(
            plugin, legacyRuntimeGate, assetsGate, assetTransactions);
        MigrationReportsPersistenceParticipant migrationReports = new MigrationReportsPersistenceParticipant(dataRoot, jsonResourceStorage);
        migrationReportsPersistenceParticipant = migrationReports;
        try {
            migrationReports.admit();
            persistence.register(migrationReports);
        } catch (IOException | RuntimeException exception) {
            try {
                migrationReports.rollbackAdmission();
            } catch (IOException | RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Migration reports persistence could not be admitted", exception);
        }
        moduleContext.registerService(MigrationReportsPersistenceParticipant.class, migrationReports);
        moduleContext.registerService(PlayerTrackingManager.class, playerTrackingManager);
        moduleContext.registerService(PlayerTrackingService.class, playerTrackingManager);
        moduleContext.registerService(StructureLibrary.class, structureLibrary);
        moduleContext.registerService(PlayerSessionLinkService.class, new DefaultPlayerSessionLinkService());
        moduleContext.registerService(ModuleContext.class, moduleContext);
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        moduleContext.registerService(RuntimeDataRegistry.class, runtimeData);
        RuntimeAuthority runtimeAuthority = new RuntimeAuthority("resync:" + serverIdentity.serverId().canonicalText());
        RuntimePrincipalAuthority principalAuthority = new RuntimePrincipalAuthority(runtimeAuthority);
        RuntimePrincipal runtimePrincipal = principalAuthority.issueSystem("resync-runtime");
        AtomicReference<RuntimeBindingRegistry> runtimeRegistryReference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary runtimeSecurity = new FlowRuntimeSecurityBoundary(
            runtimeAuthority,
            () -> runtimeRegistryReference.get() == null ? null : runtimeRegistryReference.get().snapshot(),
            principalAuthority);
        StructuredRuntimeAuditBoundary runtimeAudit = new StructuredRuntimeAuditBoundary(
            diagnosticReporter, serverIdentity.serverId());
        FlowRuntimeExecutionBoundary runtimeExecution = FlowRuntimeExecutionBoundary.bukkit(plugin);
        RuntimeReceiptStore runtimeReceipts = new DurableRuntimeReceiptStore(dataRoot);
        RuntimeBindingRegistry runtimeRegistry = new RuntimeBindingRegistry(
            runtimeSecurity, runtimeAudit, runtimeExecution, runtimeReceipts, principalAuthority);
        runtimeRegistryReference.set(runtimeRegistry);
        moduleContext.registerService(RuntimeAuthority.class, runtimeAuthority);
        moduleContext.registerService(RuntimePrincipalAuthority.class, principalAuthority);
        moduleContext.registerService(RuntimePrincipal.class, runtimePrincipal);
        moduleContext.registerService(RuntimeSecurityBoundary.class, runtimeSecurity);
        moduleContext.registerService(RuntimeAuditBoundary.class, runtimeAudit);
        moduleContext.registerService(RuntimeExecutionBoundary.class, runtimeExecution);
        moduleContext.registerService(RuntimeReceiptStore.class, runtimeReceipts);
        moduleContext.registerService(RuntimeBindingRegistry.class, runtimeRegistry);
        moduleContext.registerService(RuntimeBoundaryHealth.class, new RuntimeBoundaryHealth(
            runtimeSecurity, runtimeAudit, runtimeExecution, runtimeRegistry, runtimeReceipts));
        moduleContext.registerService(ServerIdentityStore.class, serverIdentity);
        moduleContext.registerService(CatalogActivationAuthority.class, activationAuthority);
        moduleContext.registerService(OptionCatalogRegistry.class, new OptionCatalogRegistry(runtimeData));
        moduleContext.registerService(ReSyncExtensionData.class, new ReSyncExtensionData());
        moduleContext.registerService(ReSyncJsonResourceStorage.class, jsonResourceStorage);
        moduleContext.registerService(AssetPersistenceGate.class, assetsGate);
        moduleContext.registerService(StructuredFlowDiagnosticReporter.class, diagnosticReporter);
        moduleContext.registerService(MessageLogService.class, new MessageLogService());
        moduleContext.registerService(RuntimeNotificationService.class, new RuntimeNotificationService(moduleContext));
        ReTextService reTextService = new ReTextService(jsonResourceStorage);
        moduleContext.registerService(ReTextService.class, reTextService);
        jsonResourceStorage.addListener((type, id, value, deleted) -> {
            if (ReSyncResourceCatalog.RECIPE_DEFINITION.equals(type)) {
                RecipeModule recipes = moduleContext.getService(RecipeModule.class);
                if (recipes != null) {
                    Bukkit.getScheduler().runTask(plugin, recipes::reloadRecipes);
                }
                return;
            }
        });
        TextFormatter.configure(reTextService);
    }

    private ManagedFlowFileCapability createManagedFlowFileCapability(Path dataRoot, boolean freshInstall,
                                                                       ReSyncPersistenceCoordinator persistence) {
        ManagedFlowFileCapability capability = null;
        try {
            capability = new SqliteManagedFlowFileCapability(dataRoot, freshInstall);
            ManagedFlowFilePersistenceParticipant participant = new ManagedFlowFilePersistenceParticipant(dataRoot, capability);
            persistence.register(participant);
            managedFlowFilePersistenceParticipant = participant;
            return capability;
        } catch (RuntimeException exception) {
            if (capability != null) {
                try {
                    capability.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            String reason = exception.getMessage();
            Log.warn(reason == null || reason.isBlank()
                ? "Managed flow-file persistence is unavailable"
                : "Managed flow-file persistence is unavailable: " + reason);
            return ManagedFlowFileCapability.unavailable(dataRoot, exception);
        }
    }

    private void initializePersistenceCoordinator(StructuredFlowDiagnosticReporter diagnosticReporter,
                                                  boolean freshInstall,
                                                  CatalogActivationAuthority activationAuthority) {
        try {
            ServerIdentityStore identity = moduleContext.getRequiredService(ServerIdentityStore.class);
            persistence.register(identity);
            ProductionAuthorityIssuer.VerifiedAuthorityMaterial authorityMaterial =
                authorityIssuer.verifiedAuthorityMaterial();
            ProductionAuthorityBundle.TrustedAuthority trustedAuthority = authorityMaterial.trustedAuthority();
            ProductionAuthorityBundlePersistenceParticipant authorityParticipant =
                new ProductionAuthorityBundlePersistenceParticipant(
                    dataRoot, trustedAuthority, activationAuthority.requiresBindingContext(), freshInstall);
            persistence.register(authorityParticipant);
            authorityBundlePersistenceParticipant = authorityParticipant;
            moduleContext.registerService(ProductionAuthorityBundlePersistenceParticipant.class, authorityParticipant);
            CatalogPublicationReceiptStore publicationReceiptStore = new CatalogPublicationReceiptStore(
                dataRoot, dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
            try {
                persistence.register(publicationReceiptStore);
            } catch (RuntimeException exception) {
                try {
                    publicationReceiptStore.close();
                } catch (IOException | RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
            moduleContext.registerService(CatalogPublicationReceiptStore.class, publicationReceiptStore);
            Path diagnosticsRoot = dataRoot.resolve("diagnostics").toAbsolutePath().normalize();
            diagnosticsPersistenceParticipant = new DiagnosticReportPersistenceParticipant(dataRoot, diagnosticsRoot, diagnosticReporter);
            persistenceRegistration = ReSyncPersistenceTopology.failClosed(dataRoot, "Persistence topology registration is pending",
                uncoveredDataRootWriters(dataRoot));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed To Initialize ReSync Persistence Coordination", exception);
        }
    }

    private void prepareNetworkTopology() {
        try {
            Properties networkSettings = NetworkSettings.load(operatorDataRoot);
            ReSyncNetworkAgentConfig loaded = ReSyncNetworkAgentConfig.load(operatorDataRoot, dataRoot, networkSettings);
            networkConfig = loaded;
            if (!loaded.enabled()) {
                return;
            }
            MigrationFence fence = moduleContext.getRequiredService(MigrationFence.class);
            ReSyncNetworkAgent preparedAgent = new ReSyncNetworkAgent(plugin, loaded, fence, playerDataAdmission);
            NetworkResourceSynchronizer preparedResource = null;
            List<NetworkPathSynchronizer> preparedPaths = new ArrayList<>();
            NetworkPlayerStateCoordinator preparedPlayerState = null;
            ReSyncNetworkAgentConfig.ResourcePolicy resourcePolicy = loaded.chatEnabled()
                ? loaded.resources().withIncluded(ReSyncResourceCatalog.CHAT) : loaded.resources();
            resourcePolicy = resourcePolicy.enabled()
                ? resourcePolicy.withIncluded(ReSyncResourceCatalog.PROJECT_METADATA) : resourcePolicy;
            NetworkPlayerStateConfig playerStateConfig = null;
            try {
                FlowResourceRegistry resourceRegistry = moduleContext.getService(FlowResourceRegistry.class);
                if (resourcePolicy.enabled() && resourceRegistry != null) {
                    preparedResource = new NetworkResourceSynchronizer(plugin, preparedAgent, resourceRegistry,
                        resourcePolicy, dataRoot, resource -> {
                            FlowModule flowModule = moduleContext.getService(FlowModule.class);
                            if (flowModule != null) {
                                flowModule.refreshSharedResource(resource.type(), resource.resourceId(), resource.deleted());
                            }
                        }, preparedAgent.persistenceDrain(),
                        () -> moduleContext.getService(AssetTransactionCoordinator.class));
                }
                if (loaded.pathsEnabled()) {
                    Path serverDirectory = operatorDataRoot.getParent() == null ? null : operatorDataRoot.getParent().getParent();
                    if (serverDirectory == null) {
                        throw new IOException("ReSync Network Server Directory Is Unavailable");
                    }
                    for (ReSyncNetworkAgentConfig.PathPolicy policy : loaded.pathSyncs()) {
                        if (!policy.enabled()) {
                            continue;
                        }
                        preparedPaths.add(new NetworkPathSynchronizer(plugin, preparedAgent, loaded, policy,
                            serverDirectory, dataRoot, operatorDataRoot));
                    }
                }
                playerStateConfig = NetworkPlayerStateConfig.load(networkSettings);
                networkPlayerStateEnabled = playerStateConfig.enabled();
                if (playerStateConfig.enabled()) {
                    preparedPlayerState = new NetworkPlayerStateCoordinator(plugin, playerStateConfig,
                        preparedAgent.persistenceDrain(), true, playerDataAdmission);
                }
            } catch (IOException | RuntimeException failure) {
                preparedPaths.forEach(path -> {
                    path.prepareForShutdown();
                    path.finalizeShutdown();
                });
                if (preparedResource != null) {
                    preparedResource.prepareForShutdown();
                    preparedResource.finalizeShutdown();
                }
                if (preparedPlayerState != null) {
                    preparedPlayerState.prepareForShutdown();
                    preparedPlayerState.finalizeShutdown();
                }
                try {
                    preparedAgent.prepareForShutdown();
                    preparedAgent.shutdownAfterPreparation(() -> CompletableFuture.completedFuture(null))
                        .toCompletableFuture().join();
                } catch (IOException | RuntimeException exception) {
                    failure.addSuppressed(exception);
                }
                String resumeFailure = restorePlayerDataAdmissionAfterNetworkPreparationFailure(preparedAgent, failure);
                String preparationFailure = "ReSync network topology preparation failed";
                if (resumeFailure != null) {
                    preparationFailure += "; server-owned player data admission could not resume: " + resumeFailure;
                }
                throw new IllegalStateException(preparationFailure, failure);
            }
            NetworkPersistenceDrainController drain = preparedAgent.persistenceDrain();
            drain.expectComponent("credential");
            drain.expectComponent("transfer-recovery");
            drain.expectComponent("reconciliation-backups");
            if (resourcePolicy.enabled()) {
                drain.expectComponent("resource-manifest");
            }
            for (ReSyncNetworkAgentConfig.PathPolicy policy : loaded.pathSyncs()) {
                if (policy.enabled()) {
                    drain.expectComponent("path-manifest:" + policy.id());
                }
            }
            if (playerStateConfig != null && playerStateConfig.enabled()) {
                drain.expectComponent("player-state-outbox");
            }
            networkAgent = preparedAgent;
            networkResourceSynchronizer = preparedResource;
            networkPathSynchronizers = List.copyOf(preparedPaths);
            networkPlayerStateCoordinator = preparedPlayerState;
            networkPrepared.set(true);
        } catch (IOException | RuntimeException failure) {
            networkPreparationFailure = persistenceReason("ReSync network topology could not be prepared", failure);
            Log.warn(networkPreparationFailure);
        }
    }

    private String restorePlayerDataAdmissionAfterNetworkPreparationFailure(
        ReSyncNetworkAgent preparedAgent, Throwable failure) {
        if (preparedAgent == null || preparedAgent.playerDataAdmission() != playerDataAdmission
            || playerDataAdmission.state() != PaperPlayerDataMutationAdmission.State.QUIESCED) {
            return null;
        }
        try {
            playerDataAdmission.refreshFromBukkit();
            playerDataAdmission.resume();
        } catch (IOException | RuntimeException resumeFailure) {
            failure.addSuppressed(resumeFailure);
            String message = resumeFailure.getMessage();
            return message == null || message.isBlank() ? resumeFailure.getClass().getSimpleName() : message;
        }
        return null;
    }

    public synchronized void startNetwork() {
        if (!networkPrepared.get() || !networkStarted.compareAndSet(false, true)) {
            return;
        }
        ReSyncNetworkAgent preparedAgent = networkAgent;
        ReSyncNetworkAgentConfig loaded = networkConfig;
        try {
            if (loaded == null || preparedAgent == null) {
                throw new IllegalStateException("ReSync network topology is unavailable");
            }
            try {
                preparedAgent.persistenceDrain().healthCheckPersistence();
            } catch (IOException exception) {
                throw new IllegalStateException("ReSync network topology health check failed before startup", exception);
            }
            NetworkFlowBridge networkFlowBridge = moduleContext.getService(NetworkFlowBridge.class);
            if (networkFlowBridge != null) {
                networkFlowBridge.connect(preparedAgent);
            }
            ChatModule chatModule = moduleContext.getService(ChatModule.class);
            if (chatModule != null) {
                chatModule.connectNetwork(preparedAgent, loaded.chat());
            }
            if (networkPlayerStateCoordinator != null) {
                networkPlayerStateCoordinator.start();
                preparedAgent.setTransferHandler(networkPlayerStateCoordinator);
            }
            if (networkResourceSynchronizer != null) {
                networkResourceSynchronizer.start();
            }
            networkPathSynchronizers.forEach(NetworkPathSynchronizer::start);
            preparedAgent.start();
            try {
                preparedAgent.persistenceDrain().healthCheckPersistence();
            } catch (IOException exception) {
                throw new IllegalStateException("ReSync network topology health check failed after startup", exception);
            }
        } catch (RuntimeException failure) {
            networkStarted.set(false);
            shutdownNetwork();
            throw failure;
        }
    }

    private void registerAssetsPersistenceParticipant() {
        FlowStorage flowStorage = moduleContext.getService(FlowStorage.class);
        ReSyncJsonResourceStorage jsonStorage = moduleContext.getService(ReSyncJsonResourceStorage.class);
        CustomContentStorage customContentStorage = moduleContext.getService(CustomContentStorage.class);
        WorldGenProjectStorage worldGenStorage = moduleContext.getService(WorldGenProjectStorage.class);
        AssetPersistenceGate assetsGate = moduleContext.getService(AssetPersistenceGate.class);
        WorldManagementPersistenceParticipant.Controller worldManagementController =
            moduleContext.getService(WorldManagementPersistenceParticipant.Controller.class);
        ReSyncPersistenceCoordinator persistence = moduleContext.getRequiredService(ReSyncPersistenceCoordinator.class);
        AssetTransactionCoordinator assetTransactions = moduleContext.getRequiredService(AssetTransactionCoordinator.class);
        if (flowStorage == null || jsonStorage == null || customContentStorage == null || worldGenStorage == null
            || assetsGate == null) {
            throw new IllegalStateException("Shared Asset Persistence Services Are Incomplete");
        }
        try {
            AssetsPersistenceParticipant assetsParticipant = new AssetsPersistenceParticipant(dataRoot, assetsGate,
                assetTransactions, GSON, assetMigration,
                coordinator -> {
                    this.assetTransactions = coordinator;
                    moduleContext.registerService(AssetTransactionCoordinator.class, coordinator);
                    refreshPersistenceRegistration();
                },
                flowStorage, jsonStorage, customContentStorage, worldGenStorage);
            assetsPersistenceParticipant = assetsParticipant;
            moduleContext.registerService(AssetsPersistenceParticipant.class, assetsParticipant);
            registerPersistenceTopology(dataRoot, persistence, assetsParticipant, worldManagementController);
            ReSyncPersistenceTopology.Registration registration = persistenceRegistration;
            if (registration == null || !registration.sealed() || !persistence.sealed()) {
                throw new IllegalStateException("ReSync Persistence Coordination Did Not Reach Restore-Ready State");
            }
            activateConfigurationPersistence();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Shared Asset Persistence Could Not Be Registered", exception);
        }
    }

    private void activateConfigurationPersistence() {
        ConfigurationPersistenceParticipant configurationPersistence = config.getPersistenceParticipant();
        if (configurationPersistence == null) {
            throw new IllegalStateException("Configuration Persistence Participant Is Required");
        }
        try {
            configurationPersistence.activateAndFlush();
        } catch (IOException exception) {
            throw new IllegalStateException("Configuration Persistence Could Not Be Activated", exception);
        }
    }

    private void closeUnownedAssetCoordinationAfterStartupFailure(RuntimeException failure) {
        if (assetsPersistenceParticipant == null && assetTransactions == null) {
            return;
        }
        if (assetCoordinationOwnedByPersistence()) {
            return;
        }
        if (!coreShutdownFinalized.get()) {
            if (unownedAssetCleanupScheduled.compareAndSet(false, true)) {
                coreShutdownCompletion.whenComplete((unused, shutdownFailure) -> {
                    if (shutdownFailure != null || !coreShutdownFinalized.get()) {
                        return;
                    }
                    RuntimeException cleanup = new IllegalStateException(
                        "Unregistered Asset Coordination Cleanup Failed After Retained Startup Shutdown");
                    closeUnownedAssetCoordination(cleanup);
                    if (cleanup.getSuppressed().length > 0) {
                        Log.error(cleanup.getMessage(), cleanup);
                    }
                });
            }
            return;
        }
        closeUnownedAssetCoordination(failure);
    }

    private boolean assetCoordinationOwnedByPersistence() {
        AssetsPersistenceParticipant participant = assetsPersistenceParticipant;
        return participant != null
            && persistence.registeredParticipants().stream().anyMatch(existing -> existing == participant);
    }

    private void closeUnownedAssetCoordination(RuntimeException failure) {
        if (!unownedAssetCleanupStarted.compareAndSet(false, true) || assetCoordinationOwnedByPersistence()) {
            return;
        }
        AssetsPersistenceParticipant participant = assetsPersistenceParticipant;
        try {
            if (participant != null) {
                participant.close();
            } else if (assetTransactions != null) {
                assetTransactions.close();
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void registerPersistenceTopology(Path dataRoot, ReSyncPersistenceCoordinator persistence,
                                              AssetsPersistenceParticipant assetsParticipant) {
        registerPersistenceTopology(dataRoot, persistence, assetsParticipant,
            moduleContext.getService(WorldManagementPersistenceParticipant.Controller.class),
            "Shared asset persistence participant is unavailable");
    }

    private void registerPersistenceTopology(Path dataRoot, ReSyncPersistenceCoordinator persistence,
                                              AssetsPersistenceParticipant assetsParticipant,
                                              WorldManagementPersistenceParticipant.Controller worldManagementController) {
        registerPersistenceTopology(dataRoot, persistence, assetsParticipant, worldManagementController,
            "Shared asset persistence participant is unavailable");
    }

    private void registerPersistenceTopology(Path dataRoot, ReSyncPersistenceCoordinator persistence,
                                              AssetsPersistenceParticipant assetsParticipant,
                                              WorldManagementPersistenceParticipant.Controller worldManagementController,
                                              String assetsReason) {
        if (diagnosticsPersistenceParticipant == null) {
            String reason = "ReSync persistence topology remains unavailable: diagnostics participant is not initialized";
            persistenceRegistration = ReSyncPersistenceTopology.failClosed(dataRoot, reason, uncoveredDataRootWriters(dataRoot));
            Log.warn(reason);
            return;
        }
        List<ReSyncPersistenceTopology.Binding> bindings = new ArrayList<>();
        ServerIdentityStore identity = moduleContext.getService(ServerIdentityStore.class);
        if (identity != null) {
            try {
                materializeFreshRuntimeJournals(dataRoot, identity);
            } catch (IOException | RuntimeException exception) {
                Log.warn(persistenceReason("Fresh runtime journal materialization is unavailable", exception));
            }
            try {
                identity.healthCheck();
                bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                    ServerIdentityStore.OWNER, identity.root(), identity));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.unavailable(
                    ServerIdentityStore.OWNER,
                    dataRoot.resolve(ServerIdentityStore.FILE_NAME),
                    persistenceReason("ReSync install identity persistence is unavailable", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                ServerIdentityStore.OWNER,
                dataRoot.resolve(ServerIdentityStore.FILE_NAME),
                "ReSync server identity store is unavailable"));
        }
        CatalogPublicationReceiptStore publicationReceiptStore = moduleContext.getService(CatalogPublicationReceiptStore.class);
        if (publicationReceiptStore != null) {
            try {
                publicationReceiptStore.healthCheck();
                bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                    CatalogPublicationReceiptStore.OWNER, publicationReceiptStore.root(), publicationReceiptStore));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.unavailable(
                    CatalogPublicationReceiptStore.OWNER,
                    dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME),
                    persistenceReason("Catalog publication receipt persistence is unavailable", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                CatalogPublicationReceiptStore.OWNER,
                dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME),
                "Catalog publication receipt store is unavailable"));
        }
        ProductionAuthorityBundlePersistenceParticipant authorityParticipant =
            moduleContext.getService(ProductionAuthorityBundlePersistenceParticipant.class);
        if (authorityParticipant != null) {
            try {
                authorityParticipant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.derivedCache(
                    ProductionAuthorityBundlePersistenceParticipant.OWNER,
                    authorityParticipant.root(), authorityParticipant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.derivedUnavailable(
                    ProductionAuthorityBundlePersistenceParticipant.OWNER,
                    dataRoot.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY),
                    persistenceReason("Production authority output is unavailable", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.derivedUnavailable(
                ProductionAuthorityBundlePersistenceParticipant.OWNER,
                dataRoot.resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY),
                "Production authority output participant is unavailable"));
        }
        ConfigurationPersistenceParticipant configurationPersistence = config.getPersistenceParticipant();
        if (configurationPersistence != null) {
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                ConfigurationPersistenceParticipant.OWNER,
                configurationPersistence.root(),
                configurationPersistence));
        }
        TlsPersistenceParticipant tlsPersistence = persistence.registeredParticipants().stream()
            .filter(TlsPersistenceParticipant.class::isInstance).map(TlsPersistenceParticipant.class::cast)
            .findFirst().orElse(null);
        if (tlsPersistence != null) {
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                TlsPersistenceParticipant.OWNER, tlsPersistence.root(), tlsPersistence));
        } else if (config.getTls().isEnabled()) {
            bindings.add(ReSyncPersistenceTopology.unavailable(TlsPersistenceParticipant.OWNER,
                dataRoot.resolve("tls/resync-server.p12"), "TLS Identity Persistence Is Unavailable"));
        }
        Path diagnosticsRoot = dataRoot.resolve("diagnostics").toAbsolutePath().normalize();
        bindings.add(ReSyncPersistenceTopology.requiredForRestore(
            DiagnosticReportPersistenceParticipant.OWNER,
            diagnosticsRoot,
            diagnosticsPersistenceParticipant));
        Path retainedDiagnostics = dataRoot.resolve(FreshInstallInputs.DIAGNOSTICS_DIRECTORY);
        if (startupDiagnostics != null || Files.exists(retainedDiagnostics, LinkOption.NOFOLLOW_LINKS)) {
            if (startupDiagnostics == null) startupDiagnostics = retainedDiagnostics(dataRoot, retainedDiagnostics);
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                startupDiagnostics.owner(), startupDiagnostics.root(), startupDiagnostics));
        }
        MigrationReportsPersistenceParticipant migrationReportsParticipant =
            moduleContext.getService(MigrationReportsPersistenceParticipant.class);
        if (migrationReportsParticipant != null) {
            try {
                migrationReportsParticipant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                    MigrationReportsPersistenceParticipant.OWNER,
                    migrationReportsParticipant.root(),
                    migrationReportsParticipant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.unavailable(
                    MigrationReportsPersistenceParticipant.OWNER,
                    dataRoot.resolve(MigrationReportsPersistenceParticipant.DIRECTORY),
                    persistenceReason("Migration reports persistence is unavailable", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                MigrationReportsPersistenceParticipant.OWNER,
                dataRoot.resolve(MigrationReportsPersistenceParticipant.DIRECTORY),
                "Migration reports persistence participant is unavailable"));
        }
        if (assetsParticipant != null) {
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                AssetsPersistenceParticipant.OWNER,
                assetsParticipant.root(),
                assetsParticipant));
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                AssetsPersistenceParticipant.OWNER,
                dataRoot.resolve("assets"),
                assetsReason == null || assetsReason.isBlank() ? "Shared asset persistence participant is unavailable" : assetsReason));
        }
        WorldGenGeneratedPersistenceParticipant generatedParticipant =
            moduleContext.getService(WorldGenGeneratedPersistenceParticipant.class);
        Path generatedRoot = WorldGenGeneratedOutputPolicy.root(dataRoot);
        if (generatedParticipant != null) {
            try {
                generatedParticipant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.derivedCache(
                    WorldGenGeneratedOutputPolicy.OWNER, generatedParticipant.root(), generatedParticipant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.derivedUnavailable(
                    WorldGenGeneratedOutputPolicy.OWNER, generatedRoot, generatedParticipant,
                    persistenceReason("WorldGen generated output is unavailable and will be rebuilt", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.derivedUnavailable(
                WorldGenGeneratedOutputPolicy.OWNER, generatedRoot,
                "WorldGen generated output participant is unavailable and will be rebuilt"));
        }
        CatalogStartupIndexPersistenceParticipant catalogStartupIndexParticipant =
            moduleContext.getService(CatalogStartupIndexPersistenceParticipant.class);
        Path catalogStartupIndexRoot = dataRoot.resolve(CatalogStartupIndex.DIRECTORY).toAbsolutePath().normalize();
        if (catalogStartupIndexParticipant != null) {
            try {
                catalogStartupIndexParticipant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.derivedCache(
                    CatalogStartupIndexPersistenceParticipant.OWNER, catalogStartupIndexParticipant.root(),
                    catalogStartupIndexParticipant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.derivedUnavailable(
                    CatalogStartupIndexPersistenceParticipant.OWNER, catalogStartupIndexRoot, catalogStartupIndexParticipant,
                    persistenceReason("Catalog startup index is unavailable and will be rebuilt", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.derivedUnavailable(
                CatalogStartupIndexPersistenceParticipant.OWNER, catalogStartupIndexRoot,
                "Catalog startup index participant is unavailable and will be rebuilt"));
        }
        WorldGenInstalledDatapackPersistenceParticipant installedDatapackParticipant =
            moduleContext.getService(WorldGenInstalledDatapackPersistenceParticipant.class);
        Path installedDatapackRoot = dataRoot.resolve(WorldGenInstalledDatapackPersistenceParticipant.DIRECTORY)
            .toAbsolutePath().normalize();
        if (installedDatapackParticipant != null) {
            try {
                installedDatapackParticipant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.derivedCache(
                    WorldGenInstalledDatapackCapability.OWNER,
                    installedDatapackParticipant.root(), installedDatapackParticipant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.derivedUnavailable(
                    WorldGenInstalledDatapackCapability.OWNER, installedDatapackRoot, installedDatapackParticipant,
                    persistenceReason("Installed WorldGen datapack lifecycle is unavailable", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.derivedUnavailable(
                WorldGenInstalledDatapackCapability.OWNER,
                installedDatapackRoot,
                "Installed WorldGen datapack lifecycle participant is unavailable"));
        }
        WorldManagementPersistenceParticipant worldManagementParticipant = null;
        String worldManagementReason = "World management persistence participant is unavailable";
        if (worldManagementController != null) {
            try {
                worldManagementParticipant = new WorldManagementPersistenceParticipant(dataRoot, worldManagementController);
            } catch (RuntimeException exception) {
                worldManagementReason = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            }
        }
        if (worldManagementParticipant != null) {
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                WorldManagementPersistenceParticipant.OWNER,
                worldManagementParticipant.root(),
                worldManagementParticipant));
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                WorldManagementPersistenceParticipant.OWNER,
                dataRoot.resolve("world-management"),
                worldManagementReason));
        }
        WorldAuditPersistenceParticipant worldAuditParticipant = moduleContext.getService(WorldAuditPersistenceParticipant.class);
        if (worldAuditParticipant != null) {
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                WorldAuditPersistenceParticipant.OWNER,
                worldAuditParticipant.root(),
                worldAuditParticipant));
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                WorldAuditPersistenceParticipant.OWNER,
                dataRoot.resolve(WorldAuditPersistenceParticipant.FILE_NAME),
                "World audit persistence participant is unavailable"));
        }
        CustomBlocksPersistenceParticipant customBlocksParticipant = moduleContext.getService(CustomBlocksPersistenceParticipant.class);
        if (customBlocksParticipant != null) {
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                CustomBlocksPersistenceParticipant.OWNER,
                customBlocksParticipant.root(),
                customBlocksParticipant));
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                CustomBlocksPersistenceParticipant.OWNER,
                dataRoot.resolve(CustomBlocksPersistenceParticipant.FILE_NAME),
                "Custom block persistence participant is unavailable"));
        }
        RegionPersistenceParticipant regionParticipant = moduleContext.getService(RegionPersistenceParticipant.class);
        if (regionParticipant != null) {
            try {
                regionParticipant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                    RegionPersistenceParticipant.OWNER, regionParticipant.root(), regionParticipant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.unavailable(
                    RegionPersistenceParticipant.OWNER,
                    dataRoot.resolve(RegionPersistenceParticipant.DIRECTORY),
                    persistenceReason("Flow region persistence participant is unavailable", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                RegionPersistenceParticipant.OWNER,
                dataRoot.resolve(RegionPersistenceParticipant.DIRECTORY),
                "Flow region persistence participant is unavailable"));
        }
        ReSyncExtensionManager extensionManager = moduleContext.getService(ReSyncExtensionManager.class);
        if (extensionManager != null) {
            try {
                ExtensionPersistenceParticipant extensionParticipant = new ExtensionPersistenceParticipant(dataRoot, extensionManager);
                extensionParticipant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                    ExtensionPersistenceParticipant.OWNER,
                    extensionParticipant.root(),
                    extensionParticipant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.unavailable(
                    ExtensionPersistenceParticipant.OWNER,
                    dataRoot.resolve("extensions"),
                    persistenceReason("Extension persistence participant is unavailable", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                ExtensionPersistenceParticipant.OWNER,
                dataRoot.resolve("extensions"),
                "Extension persistence manager is unavailable"));
        }
        addManagedFlowFilePersistenceBinding(dataRoot, bindings);
        addPersistentVariablePersistenceBinding(dataRoot, bindings);
        addAutomationTaskPersistenceBinding(dataRoot, bindings);
        addRuntimePersistenceBindings(dataRoot, bindings);
        addResourceMutationPersistenceBinding(dataRoot, bindings);
        addBoundedPersistenceBindings(dataRoot, bindings);
        addNetworkPersistenceBinding(dataRoot, bindings);
        List<PersistenceRootReadiness.UncoveredWriter> uncoveredWriters = uncoveredDataRootWriters(dataRoot);
        uncoveredWriters = addUncoveredWriterGaps(dataRoot, bindings, uncoveredWriters, persistence);
        try {
            ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
                persistence, dataRoot, bindings, uncoveredWriters);
            if (!registration.sealed()) {
                Log.warn("Raw ReSync persistence registration did not seal: bindings=" + bindings.size()
                    + "; participantBindings=" + bindings.stream().filter(binding -> binding.participant() != null).count()
                    + "; registeredOwners=" + registration.registeredOwners().size()
                    + "; requiredGaps=" + registration.readiness().requiredGaps().stream()
                    .map(owner -> owner.owner() + " (" + owner.reason() + ")")
                    .collect(Collectors.joining("; ")));
            }
            persistenceRegistration = registration;
            if (!registration.sealed()) {
                List<PersistenceRootReadiness.Owner> requiredGaps = registration.readiness().requiredGaps();
                Log.warn("ReSync persistence coordination remains unavailable until every required participant has a proven lifecycle and atomic rebind: "
                    + (requiredGaps.isEmpty() ? "no required owner gap was reported" : requiredGaps.stream()
                    .map(owner -> owner.owner() + " (" + owner.reason() + ")")
                    .collect(Collectors.joining("; ")))
                    + "; coordinatorSealed=" + persistence.sealed()
                    + "; restoreReadinessCertificatePending=true");
            }
        } catch (IOException | RuntimeException exception) {
            String reason = exception.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = exception.getClass().getSimpleName();
            }
            persistenceRegistration = ReSyncPersistenceTopology.failClosed(dataRoot,
                "Persistence topology registration failed: " + reason, uncoveredWriters);
            Log.warn("ReSync persistence topology remains unavailable: " + reason);
        }
    }

    private static ScopedPersistenceParticipant retainedDiagnostics(Path scope, Path directory) {
        return new ScopedPersistenceParticipant("resync.startup-diagnostics", scope, directory, new ScopedPersistenceParticipant.Lifecycle() {
            @Override
            public void flush(Path root) throws IOException {
                healthCheck(root);
            }

            @Override
            public void quiesce(Path root) throws IOException {
                healthCheck(root);
            }

            @Override
            public void resume(Path root) throws IOException {
                healthCheck(root);
            }

            @Override
            public void rebind(Path previousRoot, Path nextRoot) throws IOException {
                healthCheck(nextRoot);
            }

            @Override
            public void healthCheck(Path root) throws IOException {
                if (!FreshInstallInputs.acceptsEntry(root)) throw new IOException("Retained Startup Diagnostics Are Invalid: " + root);
            }
        });
    }

    static void requireExistingRuntimeJournal(ServerIdentityStore identity, Path file) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Path journal = MigrationPaths.requirePath(file, "runtime journal");
        if (identity.freshInstall()) {
            return;
        }
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Required runtime journal is missing or is not a regular file: " + journal
                + ". Restore the journal from a verified backup before restarting ReSync. Do not replace it with an empty file.");
        }
    }

    private void materializeFreshRuntimeJournals(Path dataRoot, ServerIdentityStore identity) throws IOException {
        if (identity == null || !identity.freshInstall()) {
            return;
        }
        Path triggerFile = dataRoot.resolve("triggers.json").toAbsolutePath().normalize();
        materializeFreshTriggerJournal(identity, triggerFile);

        Path playerNpcFile = dataRoot.resolve(PlayerNpcPersistenceParticipant.DIRECTORY)
            .resolve(PlayerNpcPersistenceParticipant.FILE_NAME).toAbsolutePath().normalize();
        NpcService npcService = moduleContext.getService(NpcService.class);
        if (npcService != null && Files.notExists(playerNpcFile, LinkOption.NOFOLLOW_LINKS)) {
            materializeFreshRuntimeJournal(identity, playerNpcFile,
                new PlayerNpcPersistenceParticipant(dataRoot, npcService));
        }

        Path luckPermsJournal = dataRoot.resolve(LuckPermsOperationPersistenceParticipant.DIRECTORY)
            .resolve(LuckPermsOperationPersistenceParticipant.FILE_NAME).toAbsolutePath().normalize();
        LuckPermsOperationPersistenceParticipant luckPermsParticipant =
            moduleContext.getService(LuckPermsOperationPersistenceParticipant.class);
        if (luckPermsParticipant != null && Files.notExists(luckPermsJournal, LinkOption.NOFOLLOW_LINKS)) {
            materializeFreshRuntimeJournal(identity, luckPermsJournal, luckPermsParticipant);
        }
    }

    static void materializeFreshTriggerJournal(ServerIdentityStore identity, Path file) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Path expected = MigrationPaths.requirePath(file, "trigger journal");
        if (!identity.freshInstall() || !Files.notExists(expected, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        AtomicFiles.writeNew(expected, "[]\n".getBytes(StandardCharsets.UTF_8));
    }

    static void materializeFreshRuntimeJournal(ServerIdentityStore identity, Path file,
                                               PersistenceParticipant participant) throws IOException {
        Objects.requireNonNull(identity, "identity");
        Path expected = MigrationPaths.requirePath(file, "runtime journal");
        if (!identity.freshInstall() || !Files.notExists(expected, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        PersistenceParticipant owner = Objects.requireNonNull(participant, "participant");
        Path root = MigrationPaths.requirePath(owner.root(), "runtime journal participant root");
        if (!expected.equals(root)) {
            throw new IOException("Fresh runtime journal participant root does not match its journal: " + expected);
        }
        owner.flush();
    }

    private void addManagedFlowFilePersistenceBinding(Path dataRoot, List<ReSyncPersistenceTopology.Binding> bindings) {
        Path flowFilesRoot = dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY).toAbsolutePath().normalize();
        ManagedFlowFileCapability capability = moduleContext.getService(ManagedFlowFileCapability.class);
        if (capability == null) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                ManagedFlowFilePersistenceParticipant.OWNER,
                flowFilesRoot,
                "Managed flow-file capability is unavailable; File nodes remain disabled; " + FLOW_FILE_MIGRATION_REASON));
            return;
        }
        if (!capability.available()) {
            String reason = capability.failureReason();
            if (reason == null || reason.isBlank()) {
                reason = "Managed flow-file capability is unavailable; File nodes remain disabled";
            }
            bindings.add(ReSyncPersistenceTopology.unavailable(
                ManagedFlowFilePersistenceParticipant.OWNER,
                flowFilesRoot,
                reason + "; " + FLOW_FILE_MIGRATION_REASON));
            return;
        }
        ManagedFlowFilePersistenceParticipant participant = managedFlowFilePersistenceParticipant;
        if (participant == null) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                ManagedFlowFilePersistenceParticipant.OWNER,
                flowFilesRoot,
                "Managed flow-file persistence participant was not staged; File nodes remain disabled; "
                    + FLOW_FILE_MIGRATION_REASON));
            return;
        }
        try {
            participant.healthCheck();
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                ManagedFlowFilePersistenceParticipant.OWNER, participant.root(), participant));
        } catch (IOException | RuntimeException exception) {
            String reason = persistenceReason("Managed flow-file persistence is unavailable; File nodes remain disabled", exception);
            bindings.add(ReSyncPersistenceTopology.unavailable(
                ManagedFlowFilePersistenceParticipant.OWNER,
                flowFilesRoot,
                reason + "; " + FLOW_FILE_MIGRATION_REASON));
        }
    }

    static List<PersistenceRootReadiness.UncoveredWriter> addUncoveredWriterGaps(
        Path dataRoot,
        List<ReSyncPersistenceTopology.Binding> bindings,
        List<PersistenceRootReadiness.UncoveredWriter> inventory,
        ReSyncPersistenceCoordinator persistence
    ) {
        Set<String> boundOwners = new LinkedHashSet<>(bindings.stream()
            .map(ReSyncPersistenceTopology.Binding::owner)
            .toList());
        List<PersistenceRootReadiness.UncoveredWriter> diagnostics = new ArrayList<>();
        for (PersistenceRootReadiness.UncoveredWriter writer : inventory) {
            if (writer.externalAffected()) {
                diagnostics.add(writer);
                continue;
            }
            ReSyncPersistenceTopology.Binding matchingBinding = bindings.stream()
                .filter(binding -> binding.owner().equals(writer.id())
                    && binding.classification() == writer.classification()
                    && binding.root().toAbsolutePath().normalize().equals(writer.root().toAbsolutePath().normalize()))
                .findFirst()
                .orElse(null);
            if (matchingBinding != null) {
                if (matchingBinding.participant() != null && matchingBinding.unavailableReason().isBlank()) {
                    diagnostics.add(writer);
                }
                continue;
            }
            if (bindings.stream().anyMatch(binding -> ReSyncPersistenceTopology.ownsPath(dataRoot, binding, writer.root()))) {
                diagnostics.add(writer);
                continue;
            }
            if (findExactRegistered(persistence.registeredParticipants(), writer) != null) {
                diagnostics.add(writer);
                continue;
            }
            if (!writer.root().toAbsolutePath().normalize().startsWith(dataRoot.toAbsolutePath().normalize())) {
                diagnostics.add(writer);
                continue;
            }
            if (boundOwners.add(writer.id())) {
                bindings.add(ReSyncPersistenceTopology.unavailable(writer.id(), writer.root(), writer.reason()));
            }
        }
        return diagnostics;
    }

    private void addNetworkPersistenceBinding(Path dataRoot, List<ReSyncPersistenceTopology.Binding> bindings) {
        Path networkRoot = dataRoot.resolve("network").toAbsolutePath().normalize();
        if (networkConfig != null && !networkConfig.enabled()) {
            return;
        }
        if (networkAgent == null || networkConfig == null) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                NetworkPersistenceParticipant.OWNER,
                networkRoot,
                networkPreparationFailure == null
                    ? "ReSync network agent is disabled or unavailable"
                    : networkPreparationFailure));
            return;
        }
        try {
            NetworkPersistenceParticipant participant = new NetworkPersistenceParticipant(dataRoot,
                networkAgent.persistenceDrain());
            participant.healthCheck();
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                NetworkPersistenceParticipant.OWNER, participant.root(), participant));
        } catch (IOException | RuntimeException exception) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                NetworkPersistenceParticipant.OWNER,
                networkRoot,
                persistenceReason("ReSync network persistence participant is unavailable", exception)));
        }
    }

    public synchronized void prepareNetworkShutdown() {
        ReSyncNetworkAgent preparedAgent = networkAgent;
        if (networkShutdownPrepared.get()) {
            return;
        }
        if (preparedAgent == null || !networkPrepared.get()) {
            try {
                playerDataAdmission.requestQuiesce();
            } catch (IOException exception) {
                throw new IllegalStateException("ReSync player data shutdown admission could not be fenced", exception);
            }
            networkShutdownPrepared.set(true);
            return;
        }
        try {
            preparedAgent.prepareForShutdown();
        } catch (IOException exception) {
            throw new IllegalStateException("ReSync network shutdown admission could not be fenced", exception);
        }
        if (networkPlayerStateCoordinator != null) {
            networkPlayerStateCoordinator.prepareForShutdown();
        }
        if (networkResourceSynchronizer != null) {
            networkResourceSynchronizer.prepareForShutdown();
        }
        networkPathSynchronizers.forEach(NetworkPathSynchronizer::prepareForShutdown);
        networkShutdownPrepared.set(true);
    }

    public CompletionStage<NetworkShutdownResult> shutdownNetworkAfterPreparation() {
        ReSyncNetworkAgent preparedAgent = networkAgent;
        if (preparedAgent == null) {
            if (!networkShutdownPrepared.get()) {
                return CompletableFuture.completedFuture(NetworkShutdownResult.failed(
                    "ReSync shutdown requires successful primary-thread preparation",
                    NetworkPersistenceDrainController.State.OPEN));
            }
            synchronized (this) {
                if (networkShutdownAttempt != null) {
                    if (!networkShutdownAttempt.isDone()) {
                        return networkShutdownAttempt;
                    }
                    NetworkShutdownResult previous = networkShutdownAttempt.getNow(null);
                    if (previous != null && previous.completed()) {
                        return networkShutdownAttempt;
                    }
                }
                networkShutdownAttempt = new CompletableFuture<>();
            }
            CompletableFuture<NetworkShutdownResult> attempt = networkShutdownAttempt;
            CompletableFuture.runAsync(() -> {
                try {
                    playerDataAdmission.quiesce(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
                    attempt.complete(NetworkShutdownResult.success());
                } catch (IOException | RuntimeException exception) {
                    attempt.complete(NetworkShutdownResult.failed(
                        rootMessage(exception), NetworkPersistenceDrainController.State.FAILED));
                }
            });
            return attempt;
        }
        if (!networkShutdownPrepared.get()) {
            return CompletableFuture.completedFuture(NetworkShutdownResult.failed(
                "ReSync network shutdown requires successful primary-thread preparation",
                preparedAgent.persistenceDrain().state()));
        }
        synchronized (this) {
            if (networkShutdownAttempt != null) {
                if (!networkShutdownAttempt.isDone()) {
                    return networkShutdownAttempt;
                }
                NetworkShutdownResult previous = networkShutdownAttempt.getNow(null);
                if (previous != null && previous.completed()) {
                    return networkShutdownAttempt;
                }
            }
            networkShutdownAttempt = new CompletableFuture<>();
        }
        CompletableFuture<NetworkShutdownResult> attempt = networkShutdownAttempt;
        preparedAgent.shutdownAfterPreparation(this::finalizeNetworkChildren).whenComplete((agentResult, agentFailure) -> {
            if (agentFailure != null) {
                attempt.complete(NetworkShutdownResult.failed(rootMessage(agentFailure), preparedAgent.persistenceDrain().state()));
                return;
            }
            if (agentResult == null || !agentResult.completed()) {
                attempt.complete(NetworkShutdownResult.failed(
                    agentResult == null ? "Network agent shutdown did not complete" : agentResult.detail(),
                    agentResult == null ? preparedAgent.persistenceDrain().state() : agentResult.state()));
                return;
            }
            networkStarted.set(false);
            attempt.complete(NetworkShutdownResult.success());
        });
        return attempt;
    }

    public CompletionStage<NetworkShutdownResult> shutdownNetwork() {
        try {
            prepareNetworkShutdown();
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(NetworkShutdownResult.failed(
                rootMessage(exception), networkAgent == null ? NetworkPersistenceDrainController.State.OPEN : networkAgent.persistenceDrain().state()));
        }
        return shutdownNetworkAfterPreparation();
    }

    private CompletionStage<Void> finalizeNetworkChildren() {
        CompletionStage<Void> coordinator = networkPlayerStateCoordinator == null
            ? CompletableFuture.completedFuture(null)
            : networkPlayerStateCoordinator.shutdownAfterPreparation(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
        return coordinator.thenRun(() -> {
            networkPathSynchronizers.forEach(NetworkPathSynchronizer::finalizeShutdown);
            if (networkResourceSynchronizer != null) {
                networkResourceSynchronizer.finalizeShutdown();
            }
        });
    }

    private void addBoundedPersistenceBindings(Path dataRoot, List<ReSyncPersistenceTopology.Binding> bindings) {
        try {
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                PlayerTrackingPersistenceParticipant.OWNER,
                playerTrackingManager.getDossierDirectory(),
                new PlayerTrackingPersistenceParticipant(dataRoot, playerTrackingManager)));
        } catch (RuntimeException exception) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                PlayerTrackingPersistenceParticipant.OWNER,
                dataRoot.resolve("player-dossiers"),
                persistenceReason("Player dossier persistence participant is unavailable", exception)));
        }
        try {
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                StructurePersistenceParticipant.OWNER,
                structureLibrary.getStructuresDir(),
                new StructurePersistenceParticipant(dataRoot, structureLibrary)));
        } catch (RuntimeException exception) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                StructurePersistenceParticipant.OWNER,
                dataRoot.resolve(StructureLibrary.ROOT_DIRECTORY),
                persistenceReason("Structure persistence participant is unavailable", exception)));
        }
        try {
            TriggerRegistry triggerRegistry = moduleContext.getService(TriggerRegistry.class);
            if (triggerRegistry == null) {
                throw new IllegalStateException("Trigger registry service is unavailable");
            }
            TriggerPersistenceParticipant triggerParticipant = new TriggerPersistenceParticipant(dataRoot, triggerRegistry);
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                TriggerPersistenceParticipant.OWNER,
                triggerParticipant.root(),
                triggerParticipant));
        } catch (RuntimeException exception) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                TriggerPersistenceParticipant.OWNER,
                dataRoot.resolve("triggers.json"),
                persistenceReason("Trigger persistence participant is unavailable", exception)));
        }
    }

    private void addResourceMutationPersistenceBinding(Path dataRoot, List<ReSyncPersistenceTopology.Binding> bindings) {
        Path database = dataRoot.resolve("runtime").resolve("resource-mutations.db").toAbsolutePath().normalize();
        if (resourceMutationAuthority == null) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                SqliteProtocolResourceMutationPersistenceParticipant.OWNER,
                database,
                resourceMutationAuthorityUnavailableReason));
            return;
        }
        try {
            SqliteProtocolResourceMutationPersistenceParticipant participant =
                new SqliteProtocolResourceMutationPersistenceParticipant(dataRoot, resourceMutationAuthority);
            participant.healthCheck();
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                SqliteProtocolResourceMutationPersistenceParticipant.OWNER,
                participant.root(),
                participant));
        } catch (IOException | RuntimeException exception) {
            String reason = persistenceReason("Durable resource mutation persistence is unavailable", exception);
            disableResourceMutationAuthority(reason);
            bindings.add(ReSyncPersistenceTopology.unavailable(
                SqliteProtocolResourceMutationPersistenceParticipant.OWNER,
                database,
                reason));
        }
    }

    private void addAutomationTaskPersistenceBinding(Path dataRoot, List<ReSyncPersistenceTopology.Binding> bindings) {
        Path journal = dataRoot.resolve(AutomationTaskPersistenceParticipant.DIRECTORY)
            .resolve(AutomationTaskPersistenceParticipant.FILE_NAME).toAbsolutePath().normalize();
        AutomationTaskService automationTasks = moduleContext.getService(AutomationTaskService.class);
        if (automationTasks == null) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                AutomationTaskPersistenceParticipant.OWNER, journal,
                "Automation task service is unavailable"));
            return;
        }
        try {
            AutomationTaskPersistenceParticipant participant = new AutomationTaskPersistenceParticipant(dataRoot, automationTasks);
            participant.healthCheck();
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                AutomationTaskPersistenceParticipant.OWNER, participant.root(), participant));
        } catch (IOException | RuntimeException exception) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                AutomationTaskPersistenceParticipant.OWNER, journal,
                persistenceReason("Automation task persistence participant is unavailable", exception)));
        }
    }

    private void addPersistentVariablePersistenceBinding(Path dataRoot, List<ReSyncPersistenceTopology.Binding> bindings) {
        Path journal = dataRoot.resolve(PersistentVariableStore.FILE_NAME).toAbsolutePath().normalize();
        PersistentVariableStore store = moduleContext.getService(PersistentVariableStore.class);
        if (store == null) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                PersistentVariableStore.OWNER, journal,
                "Persistent variable store is unavailable"));
            return;
        }
        try {
            if (!journal.equals(store.root().toAbsolutePath().normalize())) {
                throw new IOException("Persistent variable store root does not match the active data root");
            }
            store.healthCheck();
            bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                PersistentVariableStore.OWNER, store.root(), store));
        } catch (IOException | RuntimeException exception) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                PersistentVariableStore.OWNER, journal,
                persistenceReason("Persistent variable persistence is unavailable", exception)));
        }
    }

    private void addRuntimePersistenceBindings(Path dataRoot, List<ReSyncPersistenceTopology.Binding> bindings) {
        Path runtimeReceiptFile = dataRoot.resolve(DurableRuntimeReceiptStore.DIRECTORY)
            .resolve(DurableRuntimeReceiptStore.FILE_NAME).toAbsolutePath().normalize();
        RuntimeReceiptStore runtimeReceipts = moduleContext.getService(RuntimeReceiptStore.class);
        if (runtimeReceipts instanceof DurableRuntimeReceiptStore participant) {
            try {
                participant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                    DurableRuntimeReceiptStore.OWNER, participant.root(), participant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.unavailable(
                    DurableRuntimeReceiptStore.OWNER, runtimeReceiptFile,
                    persistenceReason("Runtime invocation receipt persistence is unavailable", exception)));
            }
        } else {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                DurableRuntimeReceiptStore.OWNER, runtimeReceiptFile,
                "Durable runtime invocation receipt store is unavailable"));
        }

        Path playerNpcFile = dataRoot.resolve("runtime").resolve(PlayerNpcPersistenceParticipant.FILE_NAME)
            .toAbsolutePath().normalize();
        NpcService npcService = moduleContext.getService(NpcService.class);
        if (npcService == null) {
            bindings.add(ReSyncPersistenceTopology.unavailable(
                PlayerNpcPersistenceParticipant.OWNER, playerNpcFile,
                "Player NPC service is unavailable"));
        } else {
            try {
                PlayerNpcPersistenceParticipant participant = new PlayerNpcPersistenceParticipant(dataRoot, npcService);
                participant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                    PlayerNpcPersistenceParticipant.OWNER, participant.root(), participant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.unavailable(
                    PlayerNpcPersistenceParticipant.OWNER, playerNpcFile,
                    persistenceReason("Player NPC persistence participant is unavailable", exception)));
            }
        }

        LuckPermsManagementService luckPerms = moduleContext.getService(LuckPermsManagementService.class);
        Path luckPermsJournal = dataRoot.resolve("runtime").resolve(LuckPermsOperationPersistenceParticipant.FILE_NAME)
            .toAbsolutePath().normalize();
        if (luckPerms != null || Files.exists(luckPermsJournal, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (luckPerms == null && dormantLuckPermsJournal == null) {
                    dormantLuckPermsJournal = LuckPermsOperationPersistenceParticipant.dormant(dataRoot);
                }
                RebindablePersistenceParticipant participant = luckPerms == null
                    ? dormantLuckPermsJournal
                    : moduleContext.getService(LuckPermsOperationPersistenceParticipant.class);
                if (participant == null) {
                    throw new IllegalStateException("LuckPerms operation persistence participant is not registered by its module");
                }
                participant.healthCheck();
                bindings.add(ReSyncPersistenceTopology.requiredForRestore(
                    LuckPermsOperationPersistenceParticipant.OWNER, participant.root(), participant));
            } catch (IOException | RuntimeException exception) {
                bindings.add(ReSyncPersistenceTopology.unavailable(
                    LuckPermsOperationPersistenceParticipant.OWNER, luckPermsJournal,
                    persistenceReason("LuckPerms operation persistence participant is unavailable", exception)));
            }
        }
        LuckPermsBackendPersistenceCapability backendPersistence =
            moduleContext.getService(LuckPermsBackendPersistenceCapability.class);
        LuckPermsBackendPersistenceParticipant backendParticipant =
            moduleContext.getService(LuckPermsBackendPersistenceParticipant.class);
        if (luckPerms != null) {
            bindings.add(luckPermsBackendBinding(dataRoot, backendPersistence, backendParticipant));
        }
    }

    public static ReSyncPersistenceTopology.Binding luckPermsBackendBinding(
        Path dataRoot,
        LuckPermsBackendPersistenceCapability backendPersistence,
        LuckPermsBackendPersistenceParticipant backendParticipant) {
        Path root = dataRoot.toAbsolutePath().normalize().resolve(LuckPermsBackendPersistenceParticipant.DIRECTORY)
            .toAbsolutePath().normalize();
        if (backendPersistence == null || backendParticipant == null) {
            return ReSyncPersistenceTopology.derivedUnavailable(
                LuckPermsBackendPersistenceParticipant.OWNER,
                root,
                ReSyncUncoveredWriterInventory.LUCKPERMS_BACKEND_REASON);
        }
        if (backendParticipant.capability() != backendPersistence) {
            return ReSyncPersistenceTopology.unavailable(
                LuckPermsBackendPersistenceParticipant.OWNER,
                root,
                "LuckPerms backend persistence participant capability does not match the registered backend capability");
        }
        LuckPermsBackendPersistenceCapability.Readiness readiness = backendPersistence.readiness();
        if (!readiness.available()) {
            String reason = readiness.reason();
            if (reason == null || reason.isBlank()) {
                reason = "LuckPerms backend does not provide coordinated snapshot, quiesce, restore, and rebind semantics";
            }
            return ReSyncPersistenceTopology.unavailable(
                LuckPermsBackendPersistenceParticipant.OWNER, root, reason);
        }
        try {
            if (!root.equals(backendParticipant.root())) {
                throw new IllegalStateException("LuckPerms backend participant root does not match its module binding");
            }
            backendParticipant.healthCheck();
            return ReSyncPersistenceTopology.requiredForRestore(
                LuckPermsBackendPersistenceParticipant.OWNER, backendParticipant.root(), backendParticipant);
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage();
            String reason = message == null || message.isBlank()
                ? "LuckPerms backend persistence participant is unavailable"
                : "LuckPerms backend persistence participant is unavailable: " + message;
            return ReSyncPersistenceTopology.unavailable(
                LuckPermsBackendPersistenceParticipant.OWNER, root, reason);
        }
    }

    private void disableResourceMutationAuthority(String reason) {
        resourceMutationAuthorityUnavailableReason = reason == null || reason.isBlank()
            ? "Durable resource mutation authority is unavailable" : reason;
        SqliteProtocolResourceMutationAuthority authority = resourceMutationAuthority;
        resourceMutationAuthority = null;
        if (authority != null) {
            try {
                authority.close();
            } catch (RuntimeException exception) {
                Log.warn("Durable resource mutation authority close failed: " + exception.getMessage());
            }
        }
        FlowResourceRegistry resourceRegistry = moduleContext.getService(FlowResourceRegistry.class);
        ServerIdentityStore identity = moduleContext.getService(ServerIdentityStore.class);
        if (resourceRegistry != null && identity != null) {
            setProtocolEnvelopeHandler(new FlowResourceProtocolEnvelopeHandler(resourceRegistry, identity.serverId(),
                ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(), authorityEpoch,
                authoringTemplateProducer, providerOptionQueryService));
        }
    }

    private String persistenceReason(String fallback, RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : fallback + ": " + message;
    }

    private String persistenceReason(String fallback, Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : fallback + ": " + message;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current == null || current.getMessage() == null || current.getMessage().isBlank()) {
            return throwable == null ? "Unknown failure" : throwable.getClass().getSimpleName();
        }
        return current.getMessage();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? throwable : current;
    }

    private List<PersistenceRootReadiness.UncoveredWriter> uncoveredDataRootWriters(Path dataRoot) {
        List<PersistenceRootReadiness.UncoveredWriter> writers = new ArrayList<>(
            ReSyncUncoveredWriterInventory.forDataRoot(dataRoot, operatorDataRoot, networkConfig, networkPlayerStateEnabled,
                playerDataAdmission.worldRoots()));
        LuckPermsManagementService luckPerms = moduleContext.getService(LuckPermsManagementService.class);
        if (luckPerms == null || moduleContext.getService(LuckPermsBackendPersistenceParticipant.class) != null) {
            writers.removeIf(writer -> writer.id().equals(LuckPermsBackendPersistenceParticipant.OWNER));
        }
        PersistenceRootReadiness.Owner playerData = playerDataAdmission.readinessOwner();
        writers.add(PersistenceRootReadiness.UncoveredWriter.externalAffected(
            playerData.owner(), playerData.root(), playerData.reason(), "Bukkit/Paper world persistence authority"));
        return writers.stream()
            .sorted(Comparator.comparing(PersistenceRootReadiness.UncoveredWriter::id))
            .toList();
    }

    private static PersistenceParticipant findExactRegistered(
        Collection<PersistenceParticipant> participants,
        PersistenceRootReadiness.UncoveredWriter writer
    ) {
        Path root = writer.root().toAbsolutePath().normalize();
        return participants.stream()
            .filter(Objects::nonNull)
            .filter(participant -> writer.id().equals(participant.owner()))
            .filter(participant -> writer.classification() == participant.classification())
            .filter(participant -> root.equals(participant.root().toAbsolutePath().normalize()))
            .findFirst()
            .orElse(null);
    }

    private void registerModules() {
        coreModules(replacementRuntimeProviderAuthority, configuredLuckPermsBackendPersistence, playerDataAdmission)
            .forEach(moduleRegistry::registerModule);
    }

    static List<Module> coreModules() {
        return coreModules(ReplacementRuntimeProviderAuthority.unavailable());
    }

    static List<Module> coreModules(ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority) {
        return coreModules(replacementRuntimeProviderAuthority, null);
    }

    static List<Module> coreModules(ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority,
                                    LuckPermsBackendPersistenceCapability luckPermsBackendPersistence) {
        return coreModules(replacementRuntimeProviderAuthority, luckPermsBackendPersistence,
            PaperPlayerDataMutationAdmission.shared());
    }

    static List<Module> coreModules(ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority,
                                    LuckPermsBackendPersistenceCapability luckPermsBackendPersistence,
                                    PaperPlayerDataMutationAdmission playerDataAdmission) {
        ReplacementRuntimeProviderAuthority authority = replacementRuntimeProviderAuthority == null
            ? ReplacementRuntimeProviderAuthority.unavailable()
            : replacementRuntimeProviderAuthority;
        PaperPlayerDataMutationAdmission admission = playerDataAdmission == null
            ? PaperPlayerDataMutationAdmission.shared() : playerDataAdmission;
        List<Module> modules = new ArrayList<>(List.of(
            new ChunkTransportModule(),
            new PlayerNpcPacketModule(),
            new FlowJobModule(),
            new FlowRuntimeModule(null, null, authority),
            new ChatModule(),
            new MotdModule(),
            new MessageRewriteModule(),
            new RecipeModule(admission),
            new AdvancementModule(admission),
            new PlayerTrackingModule(),
            new WorldManagementModule(),
            new WorldGenModule()
        ));
        if (luckPermsBackendPersistence != null) {
            modules.add(new LuckPermsManagementModule(luckPermsBackendPersistence));
        } else if (Bukkit.getServer() != null && Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            modules.add(new LuckPermsManagementModule());
        }
        return List.copyOf(modules);
    }

    private void startScheduler() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                moduleRegistry.tickAll();
                extensionManager.tick();
                RuntimeReceiptStore runtimeReceipts = moduleContext.getService(RuntimeReceiptStore.class);
                RuntimeAuditBoundary runtimeAudit = moduleContext.getService(RuntimeAuditBoundary.class);
                if (runtimeReceipts != null && runtimeAudit != null && runtimeReceipts.retryPendingAudits() > 0) {
                    runtimeReceipts.retryPendingAudits(runtimeAudit);
                }
            } catch (Exception e) {
                Log.warn("Module tick failed: " + e.getMessage());
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        int current = openConnections.incrementAndGet();
        if (config.getMaxConnections() > 0 && current > config.getMaxConnections()) {
            openConnections.decrementAndGet();
            conn.close(1013, "Max connections reached");
            return;
        }
        connectionManager.createConnection(conn);
        Log.fine("Client connected: " + conn.getRemoteSocketAddress());
    }

    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        openConnections.updateAndGet(value -> Math.max(0, value - 1));
        ConnectionInfo connection = connectionManager.getConnection(conn);
        CompletableFuture<Boolean> authoringHandshake = connection != null ? authoringHandshakes.remove(connection) : null;
        if (authoringHandshake != null) {
            authoringHandshake.cancel(false);
        }
        protocolEnvelopeMailbox.retire(connection);
        if (connection != null) {
            connection.clearProtocolSession();
        }
        Session session = sessionManager.getSession(conn);
        if (session != null) {
            ProviderOptionQueryService optionQueries = providerOptionQueryService;
            if (optionQueries != null) {
                optionQueries.resetSession(session);
            }
            moduleRegistry.cleanupSession(session);
        }
        sessionManager.removeSession(conn);
        connectionManager.removeConnection(conn);
        Log.fine("Client disconnected: " + conn.getRemoteSocketAddress());
    }

    public ConnectionInfo onBridgeOpen(FrameSender sender) {
        return connectionManager.createVirtualConnection(sender);
    }

    public void onBridgeClose(ConnectionInfo info) {
        if (info == null) {
            return;
        }
        requireBridgeMainThread();
        synchronized (info) {
            BridgeHandshake attempt = bridgeHandshakes.remove(info);
            if (attempt != null) {
                attempt.cancelProbe(handshakeProbes);
            }
            info.clearProtocolSession();
            info.setState(ConnectionState.CLOSING);
            protocolEnvelopeMailbox.retire(info);
            Session session = sessionManager.getSession(info);
            if (session != null) {
                ProviderOptionQueryService optionQueries = providerOptionQueryService;
                if (optionQueries != null) {
                    optionQueries.resetSession(session);
                }
                moduleRegistry.cleanupSession(session);
            }
            sessionManager.removeSession(info);
            connectionManager.removeVirtualConnection(info);
        }
    }

    public Map<String, Integer> getBridgeChannels() {
        return channelMuxer.getNumericChannels();
    }

    public String getCanonicalServerId() {
        ServerIdentityStore identity = moduleContext.getService(ServerIdentityStore.class);
        return identity == null ? "" : identity.serverId().canonicalText();
    }

    public void onBridgeMessage(ConnectionInfo info, Player player, byte[] data) {
        if (info == null || player == null || data == null) {
            return;
        }
        MessageType messageType = identifyMessageType(data);
        ProtocolIngress protocolIngress = messageType == MessageType.PROTOCOL_ENVELOPE
            ? captureProtocolIngress(info)
            : null;
        try {
            String clientId = info.getClientId() != null ? info.getClientId() : player.getUniqueId().toString();
            if (!rateLimiter.tryConsume("global", 1, config.getQueue().getMaxGlobalRequests(), config.getQueue().getMaxGlobalRequests(), 1000)) {
                if (messageType == MessageType.PROTOCOL_ENVELOPE) {
                    sendProtocolTransportError(info, protocolIngress, 429, "Global rate limit exceeded");
                } else {
                    sendError(info, 429, "Global rate limit exceeded");
                }
                return;
            }
            if (!rateLimiter.tryConsume(clientId, 1, config.getQueue().getMaxRequestsPerClient(), config.getQueue().getMaxRequestsPerClient(), 1000)) {
                if (messageType == MessageType.PROTOCOL_ENVELOPE) {
                    sendProtocolTransportError(info, protocolIngress, 429, "Rate limit exceeded");
                } else {
                    sendError(info, 429, "Rate limit exceeded");
                }
                return;
            }
            FrameHeader outerHeader = new FrameHeader(data);
            messageType = outerHeader.getMessageType();
            if (messageType == MessageType.PROTOCOL_ENVELOPE) {
                if (!hasBridgeAccess(player)) {
                    sendProtocolResult(info, protocolIngress, ProtocolEnvelopeDispatchResult.rejected(
                        ProtocolRejectionCode.AUTHORIZATION_DENIED, "Protocol envelope operation is not authorized"));
                    closeProtocolIngress(info, protocolIngress, 1008, "No Permission");
                    return;
                }
                handleEncodedProtocolEnvelope(info, data, this::decodeProtocolEnvelopeFrame, protocolIngress);
                return;
            }
            Codec.Frame frame = codec.decodeFrame(data);
            messageType = frame.header.getMessageType();
            if (frame.header.getMessageType() == MessageType.DATA && !info.acceptInboundDataSequence(frame.header.getSequence())) {
                sendError(info, 409, "Stale data frame");
                return;
            }
            Message payload = codec.decodePayload(frame);
            handleBridgePayload(info, player, payload, frame.header, protocolIngress);
        } catch (Exception e) {
            if (messageType == MessageType.PROTOCOL_ENVELOPE) {
                sendProtocolResult(info, protocolIngress, ProtocolEnvelopeDispatchResult.rejected(
                    ProtocolRejectionCode.INVALID_PAYLOAD, "Protocol envelope message is malformed"));
                return;
            }
            String reason = e.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = e.getClass().getSimpleName();
            }
            Log.warn("Error handling bridge message: " + reason);
        }
    }

    public void onMessage(WebSocket conn, ByteBuffer message) {
        ConnectionInfo info = connectionManager.getConnection(conn);
        if (info == null) {
            conn.close(1002, "Unknown connection");
            return;
        }

        byte[] data = new byte[message.remaining()];
        message.get(data);

        MessageType messageType = identifyMessageType(data);
        ProtocolIngress protocolIngress = messageType == MessageType.PROTOCOL_ENVELOPE
            ? captureProtocolIngress(info)
            : null;
        try {
            String clientId = info.getClientId() != null ? info.getClientId() : conn.getRemoteSocketAddress().toString();
            if (!rateLimiter.tryConsume("global", 1, config.getQueue().getMaxGlobalRequests(), config.getQueue().getMaxGlobalRequests(), 1000)) {
                if (messageType == MessageType.PROTOCOL_ENVELOPE) {
                    sendProtocolTransportError(info, protocolIngress, 429, "Global rate limit exceeded");
                } else {
                    sendError(info, 429, "Global rate limit exceeded");
                }
                return;
            }
            if (!rateLimiter.tryConsume(clientId, 1, config.getQueue().getMaxRequestsPerClient(), config.getQueue().getMaxRequestsPerClient(), 1000)) {
                if (messageType == MessageType.PROTOCOL_ENVELOPE) {
                    sendProtocolTransportError(info, protocolIngress, 429, "Rate limit exceeded");
                } else {
                    sendError(info, 429, "Rate limit exceeded");
                }
                return;
            }
            Codec.Frame frame = codec.decodeFrame(data);
            messageType = frame.header.getMessageType();
            if (frame.header.getMessageType() == MessageType.DATA && !info.acceptInboundDataSequence(frame.header.getSequence())) {
                sendError(info, 409, "Stale data frame");
                return;
            }
            Message payload = codec.decodePayload(frame);
            handlePayload(info, payload, frame.header, protocolIngress);
        } catch (Exception e) {
            if (messageType == MessageType.PROTOCOL_ENVELOPE) {
                sendProtocolResult(info, protocolIngress, ProtocolEnvelopeDispatchResult.rejected(
                    ProtocolRejectionCode.INVALID_PAYLOAD, "Protocol envelope message is malformed"));
                return;
            }
            String reason = e.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = e.getClass().getSimpleName();
            }
            Log.warn("Error handling message: " + reason);
        }
    }

    private void handlePayload(ConnectionInfo info, Message payload, FrameHeader header,
                               ProtocolIngress protocolIngress) {
        if (info.getState() != ConnectionState.AUTHENTICATED && payload.getType() != MessageType.HANDSHAKE_REQUEST) {
            if (payload.getType() == MessageType.PROTOCOL_ENVELOPE) {
                handleProtocolEnvelope(info, (ProtocolEnvelopeMessage) payload, protocolIngress);
                return;
            }
            sendError(info, 401, "Handshake required");
            info.getFrameSender().close(1008, "Handshake required");
            return;
        }
        switch (payload.getType()) {
            case HANDSHAKE_REQUEST -> handleHandshake(info, (HandshakeRequest) payload);
            case SUBSCRIBE -> handleSubscribe(info, (SubscribeRequest) payload);
            case UNSUBSCRIBE -> handleUnsubscribe(info, (UnsubscribeRequest) payload);
            case DATA -> handleData(info, (DataMessage) payload);
            case HEARTBEAT -> handleHeartbeat(info, (Heartbeat) payload);
            case PROTOCOL_ENVELOPE -> handleProtocolEnvelope(info, (ProtocolEnvelopeMessage) payload, protocolIngress);
            default -> Log.warn("Unhandled message type: " + payload.getType());
        }
    }

    private void handleProtocolEnvelope(ConnectionInfo info, ProtocolEnvelopeMessage message,
                                        ProtocolIngress protocolIngress) {
        handleEncodedProtocolEnvelope(info, message.getPayload(), encoded -> encoded, protocolIngress);
    }

    private void handleEncodedProtocolEnvelope(ConnectionInfo info, byte[] encodedPayload,
                                               ProtocolEnvelopeMailbox.PayloadDecoder payloadDecoder,
                                               ProtocolIngress protocolIngress) {
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> ingressIdentity = protocolDiagnosticIdentity(info, null);
        if (protocolIngress == null) {
            TemporaryLifecycleDiagnostics.event("protocol_server_ingress", started,
                TemporaryLifecycleDiagnostics.with(ingressIdentity, "outcome", "suppressed",
                    "requestBytes", encodedPayload.length, "phase", "session_fence"));
            return;
        }
        Session session = protocolIngress.session();
        TemporaryLifecycleDiagnostics.event("protocol_server_ingress", started,
            TemporaryLifecycleDiagnostics.with(ingressIdentity, "outcome", "received",
                "requestBytes", encodedPayload.length, "phase", "mailbox_admission"));
        ProtocolEnvelopeMailbox.Admission admission = protocolEnvelopeMailbox.admitEncoded(info, session, encodedPayload,
            payloadDecoder, this::currentProtocolSession,
            (result, dispatchAuthorityEpoch) ->
                prepareProtocolDelivery(info, session, result, dispatchAuthorityEpoch, () -> true));
        if (!admission.accepted()) {
            ProtocolEnvelopeDispatchResult rejection = correlateMailboxRejection(encodedPayload, payloadDecoder,
                admission.rejection());
            TemporaryLifecycleDiagnostics.event("response_dispatch_decision", started,
                TemporaryLifecycleDiagnostics.with(ingressIdentity, "outcome", admission.status(),
                    "mailboxGeneration", admission.generation(), "requestBytes", encodedPayload.length));
            sendRejectedAdmission(info, protocolIngress, rejection);
        } else {
            TemporaryLifecycleDiagnostics.event("response_dispatch_decision", started,
                TemporaryLifecycleDiagnostics.with(ingressIdentity, "outcome", admission.status(),
                    "mailboxGeneration", admission.generation(), "requestBytes", encodedPayload.length));
        }
    }

    private boolean currentProtocolSession(ConnectionInfo connection, Session expectedSession) {
        return connection != null && expectedSession != null
            && connection.getState() == ConnectionState.AUTHENTICATED
            && sessionManager.getSession(connection) == expectedSession;
    }

    private MessageType identifyMessageType(byte[] data) {
        if (data == null || data.length < 2) {
            return null;
        }
        try {
            return MessageType.fromValue(data[1]);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private ProtocolIngress captureProtocolIngress(ConnectionInfo info) {
        try {
            Session session = sessionManager.getSession(info);
            if (!currentProtocolSession(info, session)) {
                return null;
            }
            return new ProtocolIngress(session, authorityEpoch.current());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean currentProtocolIngress(ConnectionInfo info, ProtocolIngress protocolIngress) {
        if (protocolIngress == null || !currentProtocolSession(info, protocolIngress.session())) {
            return false;
        }
        try {
            return authorityEpoch.current() == protocolIngress.authorityEpoch();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private byte[] decodeProtocolEnvelopeFrame(byte[] encodedFrame) {
        FrameHeader header = new FrameHeader(encodedFrame);
        if (header.getTotalLength() != encodedFrame.length) {
            throw new IllegalArgumentException("Protocol envelope frame length is invalid");
        }
        Codec.Frame frame = codec.decodeFrame(encodedFrame);
        if (frame.header.getMessageType() != MessageType.PROTOCOL_ENVELOPE) {
            throw new IllegalArgumentException("Protocol envelope frame type is invalid");
        }
        Message message = codec.decodePayload(frame);
        if (!(message instanceof ProtocolEnvelopeMessage protocolEnvelope)) {
            throw new IllegalArgumentException("Protocol envelope frame body is invalid");
        }
        return protocolEnvelope.getPayload();
    }

    private ProtocolEnvelopeMailbox.PendingDelivery prepareProtocolDelivery(ConnectionInfo info, Session expectedSession,
                                                                              ProtocolEnvelopeDispatchResult result) {
        return prepareProtocolDelivery(info, expectedSession, result, authorityEpoch.current(), () -> true);
    }

    private ProtocolEnvelopeMailbox.PendingDelivery prepareProtocolDelivery(ConnectionInfo info, Session expectedSession,
                                                                              ProtocolEnvelopeDispatchResult result,
                                                                              long deliveryAuthorityEpoch,
                                                                              ProtocolEnvelopeMailbox.EventFence eventFence) {
        try {
            return prepareProtocolDeliveryFrame(info, expectedSession, result, deliveryAuthorityEpoch, eventFence).delivery();
        } catch (RuntimeException exception) {
            return () -> {
                TemporaryLifecycleDiagnostics.event("response_dispatch", 0L,
                    TemporaryLifecycleDiagnostics.with(protocolDiagnosticIdentity(info, result.response()),
                        "outcome", "encode_failed", "failure", exception.getClass().getSimpleName()));
                if (!currentProtocolDelivery(info, expectedSession, result.response(), deliveryAuthorityEpoch, eventFence)) {
                    return FrameSender.SendResult.CLOSED;
                }
                try {
                    info.getFrameSender().close(1011, "Protocol response unavailable");
                } catch (RuntimeException ignored) {
                }
                return FrameSender.SendResult.CLOSED;
            };
        }
    }

    private EncodedProtocolDelivery prepareProtocolDeliveryFrame(ConnectionInfo info, Session expectedSession,
                                                                  ProtocolEnvelopeDispatchResult result) {
        return prepareProtocolDeliveryFrame(info, expectedSession, result, authorityEpoch.current(), () -> true);
    }

    private EncodedProtocolDelivery prepareProtocolDeliveryFrame(ConnectionInfo info, Session expectedSession,
                                                                  ProtocolEnvelopeDispatchResult result,
                                                                  long deliveryAuthorityEpoch,
                                                                  ProtocolEnvelopeMailbox.EventFence eventFence) {
        if (deliveryAuthorityEpoch < 1L) {
            throw new IllegalArgumentException("Protocol delivery authority epoch must be positive");
        }
        Objects.requireNonNull(eventFence, "Protocol delivery event fence is required");
        ProtocolEnvelope<Map<String, Object>> response = result.response();
        byte[] frame;
        Map<String, Object> identity;
        if (response != null) {
            ProtocolEnvelopeMessage message = new ProtocolEnvelopeMessage();
            message.setPayload(new ProtocolEnvelopeBoundary().encode(response));
            frame = codec.encodeFrame(message, 0, false);
            identity = protocolDiagnosticIdentity(info, response);
        } else {
            ErrorMessage error = new ErrorMessage();
            error.setErrorCode(result.transportCode());
            error.setErrorText(GSON.toJson(result.structured()));
            frame = codec.encodeFrame(error, 0, false);
            identity = protocolDiagnosticIdentity(info, null);
        }
        return prepareEncodedProtocolDelivery(info, expectedSession, response, frame, identity,
            deliveryAuthorityEpoch, eventFence);
    }

    private EncodedProtocolDelivery prepareProtocolTransportErrorFrame(ConnectionInfo info, Session expectedSession,
                                                                        int transportCode, String message,
                                                                        long deliveryAuthorityEpoch,
                                                                        ProtocolEnvelopeMailbox.EventFence eventFence) {
        ErrorMessage error = new ErrorMessage();
        error.setErrorCode(transportCode);
        error.setErrorText(message);
        byte[] frame = codec.encodeFrame(error, 0, false);
        return prepareEncodedProtocolDelivery(info, expectedSession, null, frame,
            protocolDiagnosticIdentity(info, null), deliveryAuthorityEpoch, eventFence);
    }

    private EncodedProtocolDelivery prepareEncodedProtocolDelivery(ConnectionInfo info, Session expectedSession,
                                                                    ProtocolEnvelope<Map<String, Object>> response,
                                                                    byte[] frame, Map<String, Object> identity,
                                                                    long deliveryAuthorityEpoch,
                                                                    ProtocolEnvelopeMailbox.EventFence eventFence) {
        ProtocolEnvelopeMailbox.PendingDelivery delivery = () -> {
            long deliveryStarted = TemporaryLifecycleDiagnostics.start();
            if (!currentProtocolDelivery(info, expectedSession, response, deliveryAuthorityEpoch, eventFence)) {
                return FrameSender.SendResult.CLOSED;
            }
            FrameSender.SendResult outcome;
            try {
                outcome = info.getFrameSender().trySend(frame);
            } catch (RuntimeException exception) {
                TemporaryLifecycleDiagnostics.event("response_dispatch", deliveryStarted,
                    TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed", "frameBytes", frame.length,
                        "maxFrameBytes", info.getMaxEncodedFrameBytes(), "failure", exception.getClass().getSimpleName()));
                return FrameSender.SendResult.CLOSED;
            }
            TemporaryLifecycleDiagnostics.event("response_dispatch", deliveryStarted,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", outcome, "frameBytes", frame.length,
                    "maxFrameBytes", info.getMaxEncodedFrameBytes()));
            return outcome;
        };
        return new EncodedProtocolDelivery(frame, delivery);
    }

    private boolean currentProtocolDelivery(ConnectionInfo info, Session expectedSession,
                                            ProtocolEnvelope<Map<String, Object>> response,
                                            long deliveryAuthorityEpoch,
                                            ProtocolEnvelopeMailbox.EventFence eventFence) {
        if (info == null || expectedSession == null || !currentProtocolSession(info, expectedSession)
            || !info.hasProtocolResourceAccess() || deliveryAuthorityEpoch < 1L) {
            return false;
        }
        try {
            if (authorityEpoch.current() != deliveryAuthorityEpoch) {
                return false;
            }
        } catch (RuntimeException exception) {
            return false;
        }
        if (response != null && response.authorityEpoch() != deliveryAuthorityEpoch) {
            return false;
        }
        boolean optionDelivery = response != null && (response.body() instanceof ProtocolBody.OptionPageResponse
            || response.body() instanceof ProtocolBody.OptionInvalidationEvent);
        if (optionDelivery && (!info.hasNegotiatedFlowCapability(OptionQueryAuthority.PROTOCOL_CAPABILITY.id().value())
            || ProtocolRequestAuthority.trustedClientId(info, expectedSession) == null)) {
            return false;
        }
        try {
            return eventFence.current();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void sendProviderOptionInvalidation(Session session, ProtocolEnvelope<Map<String, Object>> envelope,
                                                ProviderOptionQueryService.EventFence eventFence) {
        if (session == null || envelope == null || eventFence == null || !eventFence.current()) {
            return;
        }
        ConnectionInfo info = session.getConnection();
        try {
            ProtocolEnvelopeMailbox.EventFence deliveryFence = eventFence::current;
            EncodedProtocolDelivery encoded = prepareProtocolDeliveryFrame(info, session,
                ProtocolEnvelopeDispatchResult.handled(envelope), envelope.authorityEpoch(), deliveryFence);
            protocolEnvelopeMailbox.admitOutbound(info, session, encoded.frame(), this::currentProtocolSession,
                deliveryFence, encoded.delivery());
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("response_dispatch", 0L,
                TemporaryLifecycleDiagnostics.with(protocolDiagnosticIdentity(info, envelope),
                    "outcome", "encode_failed", "failure", exception.getClass().getSimpleName()));
        }
    }

    private record EncodedProtocolDelivery(byte[] frame, ProtocolEnvelopeMailbox.PendingDelivery delivery) {
        private EncodedProtocolDelivery {
            frame = frame.clone();
            delivery = Objects.requireNonNull(delivery, "Protocol delivery is required");
        }
    }

    private record ProtocolIngress(Session session, long authorityEpoch) {
        private ProtocolIngress {
            session = Objects.requireNonNull(session, "Protocol ingress session is required");
            if (authorityEpoch < 1L) {
                throw new IllegalArgumentException("Protocol ingress authority epoch must be positive");
            }
        }
    }

    private void handleBridgePayload(ConnectionInfo info, Player player, Message payload, FrameHeader header,
                                     ProtocolIngress protocolIngress) {
        if (!hasBridgeAccess(player)) {
            if (payload.getType() == MessageType.PROTOCOL_ENVELOPE) {
                ProtocolEnvelopeDispatchResult result = protocolEnvelopeDispatch.rejectPayload(
                    ((ProtocolEnvelopeMessage) payload).getPayload(),
                    ProtocolRejectionCode.AUTHORIZATION_DENIED,
                    "Protocol envelope operation is not authorized");
                sendProtocolResult(info, protocolIngress, result);
                closeProtocolIngress(info, protocolIngress, 1008, "No Permission");
                return;
            }
            sendError(info, 401, "No Permission");
            info.getFrameSender().close(1008, "No Permission");
            return;
        }
        if (payload.getType() == MessageType.HANDSHAKE_REQUEST) {
            handleBridgeHandshake(info, player, (HandshakeRequest) payload);
            return;
        }
        handlePayload(info, payload, header, protocolIngress);
    }

    private void handleHandshake(ConnectionInfo info, HandshakeRequest req) {
        protocolEnvelopeMailbox.retire(info);
        info.clearProtocolSession();
        if (req.getProtocolVersion() != 2) {
            sendError(info, 400, "Unsupported protocol version");
            info.getFrameSender().close(1003, "Unsupported protocol version");
            return;
        }

        ClientIdentity identity;
        try {
            identity = clientAuthorizer.authorize(req.getApiKey(), req.getClientId(), req.getClientVersion());
        } catch (SecurityException exception) {
            sendError(info, 401, exception.getMessage());
            info.getFrameSender().close(1008, exception.getMessage());
            return;
        }
        CollaborationIdentity collaborationIdentity = collaborationIdentity(req, identity.clientId());
        FlowModule flowModule = moduleContext.getService(FlowModule.class);
        if (flowModule != null && clientCapabilities(req.getCapabilitiesJson())
            .contains(FlowModule.CATALOG_AUTHORING_CAPABILITY) && !flowModule.catalogAuthoringAvailable()) {
            deferHandshakeForCatalogAuthoring(info, req, identity, collaborationIdentity, flowModule);
            return;
        }
        if (!completeHandshake(info, req, identity, collaborationIdentity)) {
            return;
        }
    }

    private void deferHandshakeForCatalogAuthoring(ConnectionInfo info, HandshakeRequest request,
                                                    ClientIdentity identity,
                                                    CollaborationIdentity collaborationIdentity,
                                                    FlowModule flowModule) {
        CompletableFuture<Boolean> timeout = new CompletableFuture<>();
        try {
            scheduler.schedule(() -> timeout.complete(false), 5L, TimeUnit.SECONDS);
        } catch (RejectedExecutionException exception) {
            failAuthoringHandshake(info, "Catalog Authoring Is Unavailable");
            return;
        }
        CompletableFuture<Boolean> pending = flowModule.ensureCatalogAuthoringAvailable()
            .applyToEither(timeout, available -> available);
        if (authoringHandshakes.putIfAbsent(info, pending) != null) {
            sendError(info, 409, "Handshake Is Already Pending");
            return;
        }
        pending.whenCompleteAsync((available, failure) -> {
            if (!authoringHandshakes.remove(info, pending)) {
                return;
            }
            synchronized (info) {
                ConnectionState state = info.getState();
                if (state == ConnectionState.CLOSING || state == ConnectionState.CLOSED
                    || state == ConnectionState.TIMED_OUT) {
                    return;
                }
                if (failure != null || !Boolean.TRUE.equals(available)
                    || !flowModule.catalogAuthoringAvailable()) {
                    failAuthoringHandshake(info, "Catalog Authoring Is Unavailable");
                    return;
                }
                try {
                    completeHandshake(info, request, identity, collaborationIdentity);
                } catch (RuntimeException exception) {
                    failAuthoringHandshake(info, "Handshake Failed");
                }
            }
        }, handshakeExecutor);
    }

    private void failAuthoringHandshake(ConnectionInfo info, String reason) {
        protocolEnvelopeMailbox.retire(info);
        info.clearProtocolSession();
        info.setState(ConnectionState.CLOSING);
        sendError(info, 503, reason);
        info.getFrameSender().close(1011, reason);
    }

    private void handleBridgeHandshake(ConnectionInfo info, Player player, HandshakeRequest req) {
        protocolEnvelopeMailbox.retire(info);
        info.clearProtocolSession();
        if (req.getProtocolVersion() != 2) {
            sendError(info, 400, "Unsupported protocol version");
            info.getFrameSender().close(1003, "Unsupported protocol version");
            return;
        }
        String requestedClientId = req.getClientId();
        String clientId = "bridge:" + player.getUniqueId() + ':' + (requestedClientId != null && !requestedClientId.isBlank() ? requestedClientId : "remotely");
        HandshakeRequest request = new HandshakeRequest();
        request.setClientId(clientId);
        request.setClientVersion(req.getClientVersion());
        request.setCapabilitiesJson(req.getCapabilitiesJson());
        request.setCollaborationProfileJson(req.getCollaborationProfileJson());
        BridgeHandshake attempt = new BridgeHandshake(request, player.getUniqueId(), player.getName(), System.nanoTime());
        synchronized (info) {
            if (!bridgeHandshakeOpen(info) || !hasBridgeAccess(player)) {
                return;
            }
            if (bridgeHandshakes.putIfAbsent(info, attempt) != null) {
                sendError(info, 409, "Handshake Is Already Pending");
                return;
            }
            if (!handshakeProbes.tryAcquire()) {
                bridgeHandshakes.remove(info, attempt);
                sendError(info, 503, "Handshake Checks Are Busy");
                return;
            }
            info.setState(ConnectionState.CONNECTED);
            try {
                Bukkit.getScheduler().runTaskLater(plugin, () -> timeoutBridgeHandshake(info, attempt),
                    BRIDGE_HANDSHAKE_TIMEOUT_TICKS);
            } catch (RuntimeException failure) {
                failBridgeHandshake(info, attempt, "Handshake Checks Are Unavailable");
                return;
            }
            try {
                Future<?> probe = handshakeExecutor.submit(() -> runBridgeHandshakeProbe(info, attempt));
                attempt.attachProbe(probe);
            } catch (RejectedExecutionException failure) {
                failBridgeHandshake(info, attempt, "Handshake Checks Are Unavailable");
            }
        }
    }

    private void runBridgeHandshakeProbe(ConnectionInfo info, BridgeHandshake attempt) {
        if (!attempt.startProbe()) {
            return;
        }
        try {
            prepareBridgeHandshake(info, attempt);
        } finally {
            attempt.finishProbe(handshakeProbes);
        }
    }

    private void prepareBridgeHandshake(ConnectionInfo info, BridgeHandshake attempt) {
        HandshakeCapabilities capabilities = null;
        RuntimeException failure = null;
        try {
            if (bridgeHandshakes.get(info) != attempt || !bridgeHandshakeOpen(info)) {
                bridgeHandshakes.remove(info, attempt);
                return;
            }
            ensureBridgeCatalogAuthoring(attempt);
            capabilities = prepareHandshakeCapabilities();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        HandshakeCapabilities prepared = capabilities;
        RuntimeException preparationFailure = failure;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> finishBridgeHandshake(info, attempt, prepared, preparationFailure));
        } catch (RuntimeException unavailable) {
            rejectBridgeHandshakeContinuation(info, attempt);
        }
    }

    private void ensureBridgeCatalogAuthoring(BridgeHandshake attempt) {
        FlowModule flowModule = moduleContext.getService(FlowModule.class);
        if (flowModule == null || !clientCapabilities(attempt.request().getCapabilitiesJson())
            .contains(FlowModule.CATALOG_AUTHORING_CAPABILITY) || flowModule.catalogAuthoringAvailable()) {
            return;
        }
        long remaining = BRIDGE_HANDSHAKE_TIMEOUT_NANOS - (System.nanoTime() - attempt.started());
        if (remaining <= 0L) {
            throw new IllegalStateException("Catalog Authoring Is Unavailable");
        }
        try {
            if (!Boolean.TRUE.equals(flowModule.ensureCatalogAuthoringAvailable().get(remaining,
                TimeUnit.NANOSECONDS)) || !flowModule.catalogAuthoringAvailable()) {
                throw new IllegalStateException("Catalog Authoring Is Unavailable");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Catalog Authoring Preparation Was Interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Catalog Authoring Is Unavailable", exception);
        }
    }

    private void rejectBridgeHandshakeContinuation(ConnectionInfo info, BridgeHandshake attempt) {
        synchronized (info) {
            if (!bridgeHandshakes.remove(info, attempt)) {
                return;
            }
            attempt.cancelProbe(handshakeProbes);
            protocolEnvelopeMailbox.retire(info);
            info.clearProtocolSession();
            info.setState(ConnectionState.CLOSING);
            info.getFrameSender().close(1011, "Handshake Completion Is Unavailable");
        }
    }

    private void finishBridgeHandshake(ConnectionInfo info, BridgeHandshake attempt,
                                       HandshakeCapabilities capabilities, RuntimeException failure) {
        synchronized (info) {
            if (bridgeHandshakes.get(info) != attempt) {
                return;
            }
            Player player = Bukkit.getPlayer(attempt.playerId());
            if (System.nanoTime() - attempt.started() > BRIDGE_HANDSHAKE_TIMEOUT_NANOS) {
                failBridgeHandshake(info, attempt, "Handshake Checks Timed Out");
                return;
            }
            if (!bridgeHandshakeOpen(info) || !hasBridgeAccess(player)) {
                failBridgeHandshake(info, attempt, "Handshake Authority Changed");
                return;
            }
            if (failure != null || capabilities == null) {
                failBridgeHandshake(info, attempt, "Handshake Checks Failed");
                return;
            }
            if (!capabilities.current(moduleContext, persistenceRegistration, playerDataAdmission)) {
                failBridgeHandshake(info, attempt, "Handshake Readiness Changed");
                return;
            }
            try {
                HandshakeRequest request = attempt.request();
                if (completeHandshake(info, request, new ClientIdentity(request.getClientId(), request.getClientVersion()),
                    new CollaborationIdentity(attempt.playerId().toString(), attempt.playerName(),
                        attempt.playerId().toString(), "minecraft"), capabilities)) {
                    linkBridgeSession(info, player);
                }
            } catch (RuntimeException exception) {
                failBridgeHandshake(info, attempt, "Handshake Completion Failed");
            } finally {
                bridgeHandshakes.remove(info, attempt);
            }
        }
    }

    private void timeoutBridgeHandshake(ConnectionInfo info, BridgeHandshake attempt) {
        synchronized (info) {
            if (bridgeHandshakes.get(info) != attempt) {
                return;
            }
            long remaining = BRIDGE_HANDSHAKE_TIMEOUT_NANOS - (System.nanoTime() - attempt.started());
            if (remaining > 0L) {
                long ticks = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining) / 50L);
                try {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> timeoutBridgeHandshake(info, attempt), ticks);
                } catch (RuntimeException failure) {
                    failBridgeHandshake(info, attempt, "Handshake Checks Are Unavailable");
                }
                return;
            }
            failBridgeHandshake(info, attempt, "Handshake Checks Timed Out");
        }
    }

    private boolean bridgeHandshakeOpen(ConnectionInfo info) {
        ConnectionState state = info.getState();
        return !coreShutdownPrepared.get() && !shutdownStarted.get()
            && (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED
                || state == ConnectionState.AUTHENTICATED);
    }

    private void failBridgeHandshake(ConnectionInfo info, BridgeHandshake attempt, String reason) {
        requireBridgeMainThread();
        synchronized (info) {
            if (bridgeHandshakes.remove(info, attempt)) {
                attempt.cancelProbe(handshakeProbes);
                info.clearProtocolSession();
                info.setState(ConnectionState.CLOSING);
                protocolEnvelopeMailbox.retire(info);
                Session session = sessionManager.getSession(info);
                try {
                    if (session != null) {
                        ProviderOptionQueryService optionQueries = providerOptionQueryService;
                        if (optionQueries != null) {
                            optionQueries.resetSession(session);
                        }
                        moduleRegistry.cleanupSession(session);
                    }
                } finally {
                    try {
                        if (session != null) {
                            sessionManager.removeSession(info);
                        }
                    } finally {
                        try {
                            sendError(info, 503, reason);
                        } finally {
                            try {
                                info.getFrameSender().close(1011, reason);
                            } finally {
                                connectionManager.removeVirtualConnection(info);
                            }
                        }
                    }
                }
            }
        }
    }

    private void requireBridgeMainThread() {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Bridge Lifecycle Must Run On The Bukkit Main Thread");
        }
    }

    private void linkBridgeSession(ConnectionInfo info, Player player) {
        Session session = sessionManager.getSession(info);
        sessionManager.linkPlayerToSession(player.getUniqueId(), session);
        PlayerSessionLinkService linkService = getPlayerSessionLinkService();
        if (linkService != null) {
            linkService.link(player.getUniqueId(), session);
        }
        GuiManager guiManager = getGuiManager();
        if (guiManager != null) {
            guiManager.sendOpenGuiState(player, session);
        }
        ScoreboardTemplateManager.sendActiveState(player, session);
        DialogService dialogService = moduleContext.getService(DialogService.class);
        if (dialogService != null) {
            dialogService.sendActiveState(player, session);
        }
    }

    private boolean completeHandshake(ConnectionInfo info, HandshakeRequest req, ClientIdentity identity, CollaborationIdentity collaborationIdentity) {
        return completeHandshake(info, req, identity, collaborationIdentity, null);
    }

    private boolean completeHandshake(ConnectionInfo info, HandshakeRequest req, ClientIdentity identity,
                                      CollaborationIdentity collaborationIdentity, HandshakeCapabilities prepared) {
        protocolEnvelopeMailbox.retire(info);
        info.clearProtocolSession();
        ServerId canonicalServerId = null;
        try {
            ServerIdentityStore identityStore = moduleContext.getService(ServerIdentityStore.class);
            canonicalServerId = identityStore != null ? identityStore.serverId() : null;
        } catch (RuntimeException exception) {
            Log.warn("Canonical server identity could not be read: " + exception.getMessage());
        }
        if (canonicalServerId == null) {
            sendError(info, 503, "Canonical server identity is unavailable");
            info.getFrameSender().close(1011, "Canonical server identity is unavailable");
            return false;
        }
        Session oldSession = sessionManager.getSession(info);
        if (oldSession != null) {
            ProviderOptionQueryService optionQueries = providerOptionQueryService;
            if (optionQueries != null) {
                optionQueries.resetSession(oldSession);
            }
            moduleRegistry.cleanupSession(oldSession);
            sessionManager.removeSession(info);
        }

        Session duplicate = sessionManager.getSessionByClientId(identity.clientId());
        if (duplicate != null && duplicate.getConnection() != info) {
            duplicate.getConnection().getFrameSender().close(1000, "Duplicate client replaced");
        }

        HandshakeResponse response = new HandshakeResponse();
        response.setSuccess(true);
        response.setMessage("Handshake successful");
        response.setServerProtocolVersion(2);
        response.setServerVersion("3.0.0");

        List<String> worlds = new ArrayList<>();
        Bukkit.getWorlds().forEach(world -> worlds.add(world.getName()));
        response.setWorlds(worlds);
        response.setSupportedTileSizes(new int[]{32, 64, 128, 256});
        response.setChannels(channelMuxer.getNumericChannels());
        Map<String, Object> capabilities = identityFirstCapabilities(canonicalServerId, capabilitySnapshot(prepared));
        ReSyncProtocolContract.FlowContract flowContract = ReSyncProtocolContract.FLOW_CONTRACT;
        FlowModule flowModule = moduleContext.getService(FlowModule.class);
        List<String> supportedFlowCapabilities = new ArrayList<>(flowContract.serverCapabilities());
        if (flowModule != null && flowModule.catalogAuthoringAvailable()
            && !supportedFlowCapabilities.contains(FlowModule.CATALOG_AUTHORING_CAPABILITY)) {
            supportedFlowCapabilities.add(FlowModule.CATALOG_AUTHORING_CAPABILITY);
        }
        List<String> negotiatedFlowCapabilities = supportedFlowCapabilities.stream()
            .filter(clientCapabilities(req.getCapabilitiesJson())::contains)
            .toList();
        capabilities.put("flowContract", Map.of(
            "version", flowContract.version(),
            "minimumClientVersion", flowContract.minimumClientVersion(),
            "supported", List.copyOf(supportedFlowCapabilities),
            "negotiated", negotiatedFlowCapabilities
        ));
        FlowStorage flowStorage = getFlowStorage();
        if (flowStorage != null) {
            if (prepared == null) {
                capabilities.put("durabilityHealth", flowStorage.getDurabilityHealth());
            } else {
                capabilities.put("durabilityHealth", prepared.durabilityHealth());
            }
        }
        response.setCapabilitiesJson(GSON.toJson(capabilities));

        if (prepared != null && !prepared.current(moduleContext, persistenceRegistration, playerDataAdmission)) {
            throw new IllegalStateException("Handshake Readiness Changed Before Publication");
        }

        info.setClientId(req.getClientId());
        info.setClientVersion(req.getClientVersion());
        info.setClientCapabilities(clientCapabilities(req.getCapabilitiesJson()));
        info.setNegotiatedFlowCapabilities(Set.copyOf(negotiatedFlowCapabilities));
        info.setProtocolResourceAccess(true);
        Session session = sessionManager.createSession(info, identity);
        try {
            protocolEnvelopeMailbox.activate(info, session);
            session.setCollaborationIdentity(collaborationIdentity);
            codec.sendMessage(info.getFrameSender(), response, 0, false);
            publishChannelSnapshot(info);
        } catch (RuntimeException exception) {
            protocolEnvelopeMailbox.retire(info);
            ProviderOptionQueryService optionQueries = providerOptionQueryService;
            if (optionQueries != null) {
                optionQueries.resetSession(session);
            }
            moduleRegistry.cleanupSession(session);
            sessionManager.removeSession(info);
            info.clearProtocolSession();
            throw exception;
        }
        Log.fine("Client authenticated: " + req.getClientId());
        return true;
    }

    static Map<String, Object> identityFirstCapabilities(ServerId serverId, Map<String, Object> authorityCapabilities) {
        if (serverId == null) {
            throw new IllegalArgumentException("Canonical server identity is required");
        }
        if (authorityCapabilities != null && authorityCapabilities.containsKey("serverId")) {
            throw new IllegalArgumentException("Authority capabilities must not replace canonical server identity");
        }
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("serverId", serverId.canonicalText());
        if (authorityCapabilities != null) {
            capabilities.putAll(authorityCapabilities);
        }
        return capabilities;
    }

    private CollaborationIdentity collaborationIdentity(HandshakeRequest request, String clientId) {
        String json = request.getCollaborationProfileJson();
        if (json == null || json.isBlank()) {
            return CollaborationIdentity.client(clientId);
        }
        try {
            CollaborationIdentity profile = GSON.fromJson(json, CollaborationIdentity.class);
            if (profile == null || profile.displayName().isBlank()) {
                return CollaborationIdentity.client(clientId);
            }
            String subjectId = safeIdentityId(profile.subjectId().isBlank() ? clientId : profile.subjectId());
            String avatar = safeIdentityText(profile.avatar(), 2048);
            if (!avatar.isBlank() && !avatar.startsWith("https://") && !avatar.startsWith("http://")) {
                avatar = "";
            }
            return new CollaborationIdentity(subjectId, safeIdentityText(profile.displayName(), 64), avatar, "restudio");
        } catch (RuntimeException exception) {
            return CollaborationIdentity.client(clientId);
        }
    }

    private String safeIdentityText(String value, int limit) {
        String safe = value != null ? value.trim() : "";
        return safe.length() <= limit ? safe : safe.substring(0, limit);
    }

    private String safeIdentityId(String value) {
        return safeIdentityText(value, 128).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Set<String> clientCapabilities(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return Set.of();
            }
            Set<String> capabilities = new LinkedHashSet<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() && !element.getAsString().isBlank()) {
                    capabilities.add(element.getAsString());
                }
            }
            return Set.copyOf(capabilities);
        } catch (RuntimeException exception) {
            return Set.of();
        }
    }

    private boolean hasBridgeAccess(Player player) {
        return player != null && player.isOnline() && (player.isOp() || player.hasPermission("resync.api.access"));
    }

    private void publishChannelSnapshot(ConnectionInfo info) {
        if (!supportsChannelRegistry(info)) {
            return;
        }
        ChannelRegistryMessage message = new ChannelRegistryMessage();
        message.setSnapshot(true);
        message.setChannels(channelMuxer.getNumericChannels());
        codec.sendMessage(info.getFrameSender(), message, 0, false);
    }

    private void publishChannelDelta(Iterable<String> addedChannels, List<String> removedChannels) {
        Map<String, Integer> added = new LinkedHashMap<>();
        Map<String, Integer> numericChannels = channelMuxer.getNumericChannels();
        if (addedChannels != null) {
            for (String channelId : addedChannels) {
                Integer numericId = numericChannels.get(channelId);
                if (numericId != null) {
                    added.put(channelId, numericId);
                }
            }
        }
        ChannelRegistryMessage message = new ChannelRegistryMessage();
        message.setSnapshot(false);
        message.setChannels(added);
        message.setRemovedChannels(removedChannels);
        for (Session session : sessionManager.getSessions()) {
            if (!supportsChannelRegistry(session.getConnection())) {
                continue;
            }
            codec.sendMessage(session.getConnection().getFrameSender(), message, 0, false);
        }
    }

    private boolean supportsChannelRegistry(ConnectionInfo info) {
        String version = info != null ? info.getClientVersion() : null;
        if (version == null || version.isBlank()) {
            return false;
        }
        String[] parts = version.split("\\.");
        try {
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 2 || (major == 2 && minor >= 1);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void handleSubscribe(ConnectionInfo info, SubscribeRequest req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            sendError(info, 401, "Not authenticated");
            return;
        }

        Module module = moduleRegistry.getModuleByChannel(req.getChannelId());
        if (module == null) {
            sendError(info, 404, "Unknown channel: " + req.getChannelId());
            return;
        }

        session.subscribeChannel(req.getChannelId());
        session.addModule(module);
        module.onSubscribe(session, req);

        ChannelMuxer.Channel channel = channelMuxer.getChannel(req.getChannelId());
        if (channel != null) {
            channel.incrementSubscribers();
        }
    }

    private void handleUnsubscribe(ConnectionInfo info, UnsubscribeRequest req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            return;
        }

        Module module = moduleRegistry.getModuleByChannel(req.getChannelId());
        if (module == null) {
            return;
        }

        module.onUnsubscribe(session, req);
        session.unsubscribeChannel(req.getChannelId());
        session.removeModule(req.getChannelId());

        ChannelMuxer.Channel channel = channelMuxer.getChannel(req.getChannelId());
        if (channel != null) {
            channel.decrementSubscribers();
        }
    }

    private void handleData(ConnectionInfo info, DataMessage req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            return;
        }

        ChannelMuxer.Channel channel = channelMuxer.getChannelByNumericId(req.getChannel());
        if (channel == null) {
            return;
        }

        Module module = moduleRegistry.getModuleByChannel(channel.getId());
        if (module == null) {
            return;
        }
        if (!session.getSubscribedChannels().contains(channel.getId())) {
            sendError(info, 403, "Channel subscription required: " + channel.getId());
            return;
        }

        session.updateActivity();
        module.onData(session, req);
    }

    private void handleHeartbeat(ConnectionInfo info, Heartbeat req) {
        connectionManager.updateHeartbeat(info);
        Session session = sessionManager.getSession(info);
        if (session != null) {
            session.updateActivity();
        }
    }

    private ServerId hostedServerId() throws IOException {
        Path pluginRoot = operatorDataRoot.getParent();
        if (pluginRoot == null) {
            return null;
        }
        Path marker = pluginRoot.resolve(HOSTED_SERVER_ID_FILE).toAbsolutePath().normalize();
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
            || Files.size(marker) > 128L) {
            throw new IOException("Hosted ReSync Server Identity Is Invalid");
        }
        String value = Files.readString(marker, StandardCharsets.UTF_8).strip();
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("Non-canonical identity");
            }
            return new ServerId(parsed);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Hosted ReSync Server Identity Is Invalid", exception);
        }
    }

    private ProtocolEnvelopeDispatchResult correlateMailboxRejection(byte[] encodedPayload,
                                                                      ProtocolEnvelopeMailbox.PayloadDecoder payloadDecoder,
                                                                      ProtocolEnvelopeDispatchResult rejection) {
        try {
            byte[] payload = payloadDecoder.decode(encodedPayload.clone());
            return protocolEnvelopeDispatch.rejectPayload(payload, rejection.rejectionCode(), rejection.message());
        } catch (RuntimeException exception) {
            return rejection;
        }
    }

    private void sendRejectedAdmission(ConnectionInfo info, ProtocolIngress protocolIngress,
                                       ProtocolEnvelopeDispatchResult result) {
        if (info == null || protocolIngress == null || result == null) {
            return;
        }
        ProtocolEnvelopeMailbox.EventFence ingressFence = () -> currentProtocolIngress(info, protocolIngress);
        try {
            EncodedProtocolDelivery encoded = prepareProtocolDeliveryFrame(info, protocolIngress.session(), result,
                protocolIngress.authorityEpoch(), ingressFence);
            encoded.delivery().tryDeliver();
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("response_dispatch", 0L,
                TemporaryLifecycleDiagnostics.with(protocolDiagnosticIdentity(info, result.response()),
                    "outcome", "encode_failed", "failure", exception.getClass().getSimpleName()));
        }
    }

    private void sendError(WebSocket conn, int code, String message) {
        if (conn == null) {
            return;
        }
        ErrorMessage error = new ErrorMessage();
        error.setErrorCode(code);
        error.setErrorText(message);
        codec.sendMessage(conn, error, 0, false);
    }

    private void sendError(ConnectionInfo info, int code, String message) {
        ErrorMessage error = new ErrorMessage();
        error.setErrorCode(code);
        error.setErrorText(message);
        codec.sendMessage(info.getFrameSender(), error, 0, false);
    }

    private void sendProtocolResult(ConnectionInfo info, ProtocolIngress protocolIngress,
                                    ProtocolEnvelopeDispatchResult result) {
        if (info == null || protocolIngress == null || result == null) {
            return;
        }
        ProtocolEnvelopeMailbox.EventFence ingressFence = () -> currentProtocolIngress(info, protocolIngress);
        try {
            EncodedProtocolDelivery encoded = prepareProtocolDeliveryFrame(info, protocolIngress.session(), result,
                protocolIngress.authorityEpoch(), ingressFence);
            admitProtocolDelivery(info, protocolIngress, encoded, ingressFence);
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("response_dispatch", 0L,
                TemporaryLifecycleDiagnostics.with(protocolDiagnosticIdentity(info, result.response()),
                    "outcome", "encode_failed", "failure", exception.getClass().getSimpleName()));
            closeProtocolIngress(info, protocolIngress, 1011, "Protocol response unavailable");
        }
    }

    private void sendProtocolTransportError(ConnectionInfo info, ProtocolIngress protocolIngress,
                                            int transportCode, String message) {
        if (info == null || protocolIngress == null) {
            return;
        }
        ProtocolEnvelopeMailbox.EventFence ingressFence = () -> currentProtocolIngress(info, protocolIngress);
        try {
            EncodedProtocolDelivery encoded = prepareProtocolTransportErrorFrame(info, protocolIngress.session(),
                transportCode, message, protocolIngress.authorityEpoch(), ingressFence);
            admitProtocolDelivery(info, protocolIngress, encoded, ingressFence);
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("response_dispatch", 0L,
                TemporaryLifecycleDiagnostics.with(protocolDiagnosticIdentity(info, null),
                    "outcome", "encode_failed", "failure", exception.getClass().getSimpleName()));
            closeProtocolIngress(info, protocolIngress, 1011, "Protocol response unavailable");
        }
    }

    private void admitProtocolDelivery(ConnectionInfo info, ProtocolIngress protocolIngress,
                                       EncodedProtocolDelivery encoded,
                                       ProtocolEnvelopeMailbox.EventFence ingressFence) {
        ProtocolEnvelopeMailbox.Admission admission = protocolEnvelopeMailbox.admitOutbound(info,
            protocolIngress.session(), encoded.frame(), this::currentProtocolSession, ingressFence, encoded.delivery());
        if (!admission.accepted()) {
            closeProtocolIngress(info, protocolIngress, 1013, "Protocol response unavailable");
        }
    }

    private void closeProtocolIngress(ConnectionInfo info, ProtocolIngress protocolIngress, int code, String reason) {
        if (!currentProtocolIngress(info, protocolIngress)) {
            return;
        }
        try {
            info.getFrameSender().close(code, reason);
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("response_dispatch", 0L,
                TemporaryLifecycleDiagnostics.with(protocolDiagnosticIdentity(info, null),
                    "outcome", "close_failed", "failure", exception.getClass().getSimpleName()));
        }
    }

    private Map<String, Object> protocolDiagnosticIdentity(ConnectionInfo info,
                                                           ProtocolEnvelope<Map<String, Object>> envelope) {
        Map<String, Object> identity = TemporaryLifecycleDiagnostics.identity(envelope == null ? null : envelope.serverId(),
            envelope == null || envelope.resource() == null ? null : envelope.resource().canonicalText(),
            envelope == null ? null : envelope.mutationId(), envelope == null ? null : envelope.requestId(),
            envelope == null ? null : envelope.correlationId(), envelope == null ? null : envelope.revision(),
            envelope == null ? null : envelope.authorityEpoch(), null);
        if (TemporaryLifecycleDiagnostics.enabled()) {
            identity = TemporaryLifecycleDiagnostics.with(identity, "connectionHash",
                TemporaryLifecycleDiagnostics.safeHash(info == null ? null : info.getConnectionId()));
        }
        return identity;
    }

    public void setProtocolEnvelopeHandler(ProtocolEnvelopeHandler handler) {
        resourceProtocolHandler = handler instanceof FlowResourceProtocolEnvelopeHandler resources ? resources : null;
        protocolEnvelopeDispatch.setHandler(handler);
    }

    public boolean supportsProtocolEnvelope() {
        return protocolEnvelopeDispatch.isSupported();
    }

    public void shutdown() {
        try {
            prepareNetworkShutdown();
            prepareCoreShutdown();
        } catch (RuntimeException exception) {
            Log.error("ReSync shutdown preparation failed: " + exception.getMessage(), exception);
            return;
        }
        continuePreparedShutdown(true).whenComplete((unused, failure) -> {
            if (failure != null) {
                Log.error("ReSync shutdown remains pending because network persistence did not finalize: "
                    + failure.getMessage(), failure);
            }
        });
    }

    public void shutdownSynchronously() {
        shutdownSynchronously(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT.multipliedBy(3));
    }

    private void shutdownSynchronously(Duration timeout) {
        prepareNetworkShutdown();
        prepareCoreShutdown();
        awaitShutdown(continuePreparedShutdown(true), timeout);
        if (!coreShutdownFinalized.get()) {
            throw new IllegalStateException("ReSync Synchronous Shutdown Remains Retained");
        }
    }

    static void awaitShutdown(CompletionStage<Void> shutdown, Duration timeout) {
        try {
            shutdown.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ReSync Shutdown Was Interrupted; Persistence Authority Is Retained", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("ReSync Shutdown Did Not Complete; Persistence Authority Is Retained", exception);
        }
    }

    public CompletionStage<Void> continuePreparedShutdown(boolean initialReady) {
        return shutdownCoordinator.continueShutdown(initialReady);
    }

    private boolean networkShutdownSucceeded() {
        CompletableFuture<NetworkShutdownResult> attempt = networkShutdownAttempt;
        if (attempt == null || !attempt.isDone() || attempt.isCompletedExceptionally()) {
            return false;
        }
        NetworkShutdownResult result = attempt.getNow(null);
        return result != null && result.completed();
    }

    public synchronized void prepareCoreShutdown() {
        if (!coreShutdownPrepared.compareAndSet(false, true)) {
            return;
        }
        bridgeHandshakes.values().forEach(attempt -> attempt.cancelProbe(handshakeProbes));
        bridgeHandshakes.clear();
        authoringHandshakes.values().forEach(handshake -> handshake.cancel(false));
        authoringHandshakes.clear();
        handshakeExecutor.shutdownNow();
        ServerQaService qa = qaService;
        if (qa != null) qa.close();
        protocolEnvelopeMailbox.closeAdmission();
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            FlowRuntimeModule runtime = moduleContext.getService(FlowRuntimeModule.class);
            if (runtime != null) runtime.closeLiveRefreshAdmission();
            try {
                protocolEnvelopeMailbox.whenIdle().get(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                Log.warn("Protocol shutdown was interrupted; active resource authority remains retained");
            } catch (ExecutionException | TimeoutException failure) {
                Log.warn("Protocol shutdown is still draining; active resource authority remains retained");
            }
        }
        prepareCoreShutdownAfterProtocolDrain();
    }

    private synchronized void prepareCoreShutdownAfterProtocolDrain() {
        if (!protocolEnvelopeMailbox.isIdle()) {
            if (protocolShutdownAwaiting.compareAndSet(false, true)) {
                protocolEnvelopeMailbox.whenIdle().whenCompleteAsync((unused, failure) -> {
                    protocolShutdownAwaiting.set(false);
                    prepareCoreShutdownAfterProtocolDrain();
                }, scheduler);
            }
            return;
        }
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread() && !moduleContext.getPlugin().isEnabled()) {
            Log.warn("Core shutdown requires retained primary preparation after resource drain");
            return;
        }
        if (!coreShutdownPipelineStarted.compareAndSet(false, true)) {
            return;
        }
        ReSyncPersistenceCoordinator persistence = moduleContext.getService(ReSyncPersistenceCoordinator.class);
        if (resourceMutationAuthority != null) {
            resourceMutationAuthority.closeMutationAdmission();
        }
        if (persistence != null) {
            persistence.beginShutdown();
        }
        try {
            extensionManager.shutdown();
        } catch (RuntimeException exception) {
            Log.error("ReSync extension shutdown failed", exception);
        }
        try {
            coreModuleShutdown = moduleRegistry.shutdownModulesAsync(moduleContext);
        } catch (RuntimeException exception) {
            coreModuleShutdown = CompletableFuture.failedFuture(exception);
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("code", "RESYNC.SHUTDOWN_FAILED");
            diagnostic.put("phase", "modules");
            diagnostic.put("persistence", persistence == null ? Map.of("state", "UNAVAILABLE") : persistence.shutdownStatus().payload());
            Log.error("ReSync shutdown failed: " + GSON.toJson(diagnostic), exception);
        }
        coreModuleShutdown.whenComplete((unused, failure) -> finishCoreShutdown());
    }

    public synchronized void finishCoreShutdown() {
        if (!coreShutdownPrepared.get() || coreShutdownFinalized.get()) {
            return;
        }
        if (!coreShutdownPipelineStarted.get()) {
            prepareCoreShutdownAfterProtocolDrain();
            return;
        }
        if (networkPrepared.get() && !networkShutdownSucceeded()) {
            return;
        }
        CompletableFuture<Void> modules = coreModuleShutdown.toCompletableFuture();
        if (!modules.isDone()) {
            if (coreShutdownAwaitingModules.compareAndSet(false, true)) {
                modules.whenComplete((unused, failure) -> {
                    coreShutdownAwaitingModules.set(false);
                    finishCoreShutdown();
                });
            }
            return;
        }
        if (modules.isCompletedExceptionally()) {
            try {
                modules.join();
            } catch (RuntimeException exception) {
                Log.error("ReSync module shutdown remains incomplete; persistence authority is retained", exception);
            }
            return;
        }
        ReSyncPersistenceCoordinator persistence = moduleContext.getService(ReSyncPersistenceCoordinator.class);
        if (persistence != null) {
            try {
                PersistenceShutdownStatus status = persistence.shutdownStatus().state() == PersistenceShutdownStatus.State.FAILED
                    ? persistence.retryClose() : persistence.close();
                if (status == null || status.state() != PersistenceShutdownStatus.State.CLOSED) {
                    Log.error("ReSync persistence shutdown remains incomplete; core authority is retained: "
                        + (status == null ? "unknown status" : status.reason()));
                    return;
                }
            } catch (IOException | RuntimeException exception) {
                Log.error("ReSync persistence shutdown failed", exception);
                return;
            }
        }
        SqliteProtocolResourceMutationAuthority authority = resourceMutationAuthority;
        if (authority != null) {
            try {
                authority.close();
            } catch (RuntimeException exception) {
                Log.error("ReSync resource mutation authority shutdown failed", exception);
                return;
            }
        }
        if (!closePlayerDataAdmission()) {
            return;
        }
        if (!coreShutdownFinalized.compareAndSet(false, true)) {
            return;
        }
        resourceMutationAuthority = null;
        ProviderOptionQueryService optionQueries = providerOptionQueryService;
        providerOptionQueryService = null;
        if (optionQueries != null) {
            optionQueries.close();
        }
        protocolEnvelopeMailbox.shutdown();
        OptionCatalogCaptureExecutor.ShutdownResult optionCaptureShutdown = optionCatalogExecutor.shutdown(Duration.ofMillis(100));
        if (!optionCaptureShutdown.terminated()) {
            Log.warn("Option catalog capture executor did not terminate within its shutdown grace: active="
                + optionCaptureShutdown.activeTasks() + ", queued=" + optionCaptureShutdown.queuedTasks());
        }
        scheduler.shutdown();
        connectionManager.shutdown();
        sessionManager.shutdown();
        requestQueue.shutdown();
        compressionPool.close();
        rateLimiter.resetAll();
        memoryMonitor.shutdown();
        TextFormatter.clear();
        shutdownStarted.set(true);
        TemporaryLifecycleDiagnostics.flush();
        TemporaryLifecycleDiagnostics.close(lifecycleDiagnosticSink);
        coreShutdownCompletion.complete(null);
    }

    private boolean closePlayerDataAdmission() {
        try {
            playerDataAdmission.close(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
            PaperPlayerDataMutationAdmission.clearSharedInstallation(playerDataAdmissionInstallation);
            return true;
        } catch (IOException | RuntimeException exception) {
            Log.error("ReSync player data admission shutdown remains incomplete", exception);
            return false;
        }
    }

    public CompletionStage<Void> coreShutdownCompletion() {
        return coreShutdownCompletion;
    }

    public CompletionStage<Void> retryCoreShutdown() {
        synchronized (shutdownRetryMonitor) {
            if (coreShutdownFinalized.get()) {
                return CompletableFuture.completedFuture(null);
            }
            if (!coreShutdownPrepared.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("ReSync Core Shutdown Has Not Been Prepared"));
            }
            if (networkPrepared.get() && !networkShutdownSucceeded()) {
                return CompletableFuture.failedFuture(new IllegalStateException("ReSync Network Shutdown Has Not Completed"));
            }
            if (coreRetryAttempt != null && !coreRetryAttempt.isDone()) {
                return coreRetryAttempt;
            }
            CompletableFuture<Void> attempt = new CompletableFuture<>();
            coreRetryAttempt = attempt;
            CompletionStage<Void> modules = coreModuleShutdown;
            if (modules.toCompletableFuture().isCompletedExceptionally() || modules.toCompletableFuture().isCancelled()) {
                try {
                    modules = moduleRegistry.shutdownModulesAsync(moduleContext);
                    coreModuleShutdown = modules;
                } catch (RuntimeException exception) {
                    attempt.completeExceptionally(exception);
                    return attempt;
                }
            }
            modules.whenComplete((unused, failure) -> {
                if (failure != null) {
                    attempt.completeExceptionally(rootCause(failure));
                    return;
                }
                finishCoreShutdown();
                if (coreShutdownFinalized.get()) {
                    attempt.complete(null);
                } else {
                    attempt.completeExceptionally(new IllegalStateException("ReSync Core Shutdown Remains Retained"));
                }
            });
            return attempt;
        }
    }

    public CompletionStage<Void> retryPreparedShutdown() {
        if (coreShutdownFinalized.get()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!networkShutdownPrepared.get() || !coreShutdownPrepared.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "ReSync Shutdown Primary Preparation Has Not Completed"));
        }
        CompletionStage<NetworkShutdownResult> network = networkShutdownSucceeded()
            ? CompletableFuture.completedFuture(NetworkShutdownResult.success())
            : shutdownNetworkAfterPreparation();
        return network.thenCompose(result -> {
            if (result == null || !result.completed()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    result == null ? "ReSync Network Shutdown Did Not Complete" : result.detail()));
            }
            finishCoreShutdown();
            return coreShutdownCompletion;
        });
    }

    public CompletionStage<Void> retryShutdown() {
        if (coreShutdownFinalized.get()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            if (!networkShutdownPrepared.get()) {
                prepareNetworkShutdown();
            }
            if (!coreShutdownPrepared.get()) {
                prepareCoreShutdown();
            }
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return continuePreparedShutdown(false);
    }

    static PersistenceShutdownStatus shutdownModulesBeforePersistence(ModuleRegistry moduleRegistry,
                                                                       ModuleContext moduleContext,
                                                                       ReSyncPersistenceCoordinator persistence) throws IOException {
        if (moduleRegistry == null) {
            throw new IllegalArgumentException("Module registry is required");
        }
        if (persistence == null) {
            throw new IllegalArgumentException("Persistence coordinator is required");
        }
        if (persistence.shutdownStatus().state() == PersistenceShutdownStatus.State.CLOSED
            || persistence.shutdownStatus().state() == PersistenceShutdownStatus.State.FAILED) {
            return persistence.shutdownStatus();
        }
        persistence.beginShutdown();
        RuntimeException moduleFailure = null;
        try {
            moduleRegistry.shutdownModules(moduleContext);
        } catch (RuntimeException exception) {
            moduleFailure = exception;
        }
        try {
            PersistenceShutdownStatus status = persistence.close();
            if (moduleFailure != null) {
                throw moduleFailure;
            }
            return status;
        } catch (IOException | RuntimeException exception) {
            if (moduleFailure != null) {
                exception.addSuppressed(moduleFailure);
            }
            throw exception;
        }
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public ModuleRegistry getModuleRegistry() {
        return moduleRegistry;
    }

    public RequestQueue getRequestQueue() {
        return requestQueue;
    }

    public ModuleContext getModuleContext() {
        return moduleContext;
    }

    public ReSyncExtensionManager getExtensionManager() {
        return extensionManager;
    }

    public ReSyncConfig getConfig() {
        return config;
    }

    public ReSyncNetworkAgent getNetworkAgent() {
        return networkAgent;
    }

    public PaperPlayerDataMutationAdmission playerDataAdmission() {
        return playerDataAdmission;
    }

    public NetworkPlayerStateCoordinator getNetworkPlayerStateCoordinator() {
        return networkPlayerStateCoordinator;
    }

    public synchronized void replaceNetworkPlayerStateCoordinator(NetworkPlayerStateCoordinator coordinator) {
        networkPlayerStateCoordinator = coordinator;
    }

    public NetworkResourceSynchronizer getNetworkResourceSynchronizer() {
        return networkResourceSynchronizer;
    }

    public List<NetworkPathSynchronizer> getNetworkPathSynchronizers() {
        return networkPathSynchronizers;
    }

    public Map<String, Object> readinessSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("apiEnabled", config.isEnabled());
        snapshot.put("bindHost", config.getBindHost());
        snapshot.put("connectedClients", sessionManager.getSessionCount());
        snapshot.put("maxConnections", config.getMaxConnections());
        snapshot.put("queueMaxGlobalRequests", config.getQueue().getMaxGlobalRequests());
        snapshot.put("queueMaxRequestsPerClient", config.getQueue().getMaxRequestsPerClient());
        snapshot.put("sessionMemoryBytes", sessionManager.getTotalSessionMemory());
        snapshot.put("sessionMemoryLimitBytes", memoryMonitor.getMaxMemoryForSessions());
        snapshot.put("authMode", "adminApiKey");
        snapshot.put("openConnections", openConnections.get());
        snapshot.put("capabilities", capabilitySnapshot());
        return snapshot;
    }

    public Map<String, Object> statusSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("apiEnabled", config.isEnabled());
        snapshot.put("bindHost", config.getBindHost());
        snapshot.put("connectedClients", sessionManager.getSessionCount());
        snapshot.put("maxConnections", config.getMaxConnections());
        snapshot.put("authMode", "adminApiKey");
        snapshot.put("openConnections", openConnections.get());
        snapshot.put("queueMaxGlobalRequests", config.getQueue().getMaxGlobalRequests());
        snapshot.put("queueMaxRequestsPerClient", config.getQueue().getMaxRequestsPerClient());
        snapshot.put("sessionMemoryBytes", sessionManager.getTotalSessionMemory());
        snapshot.put("sessionMemoryLimitBytes", memoryMonitor.getMaxMemoryForSessions());
        return snapshot;
    }

    private HandshakeCapabilities prepareHandshakeCapabilities() {
        ReSyncPersistenceCoordinator coordinator = moduleContext.getService(ReSyncPersistenceCoordinator.class);
        ReSyncPersistenceTopology.Registration registration = persistenceRegistration;
        ReSyncPersistenceCoordinator.ReadinessObservation observation = coordinator == null
            ? null : coordinator.readinessObservation();
        long authorityEpoch = observation == null ? currentAuthorityEpoch() : observation.authorityEpoch();
        TriggerRegistry triggerRegistry = moduleContext.getService(TriggerRegistry.class);
        TriggerRegistry.BindingObservation triggerObservation = triggerRegistry == null
            ? null : triggerRegistry.bindingObservation();
        if (triggerRegistry != null && !triggerRegistry.isCurrent(triggerObservation)) {
            throw new IllegalStateException("Trigger Binding State Is Transitioning");
        }
        Map<String, Object> persistenceCapability = coordinator == null ? Map.of()
            : cachedPersistenceCapability(coordinator, registration);
        RuntimeBoundaryHealth runtimeHealth = moduleContext.getService(RuntimeBoundaryHealth.class);
        RuntimeBoundaryHealth.Prepared runtimePrepared = runtimeHealth == null ? null : runtimeHealth.prepare();
        FlowStorage storage = getFlowStorage();
        FlowStorage.RuntimeObservation storageObservation = storage == null ? null
            : storage.observeRuntime().orElseThrow(() -> new IllegalStateException("Flow Storage Is Transitioning"));
        AssetIntegrityService.HealthReport durabilityHealth = storage == null ? null : storage.getDurabilityHealth();
        PaperPlayerDataMutationAdmission.ReadinessObservation playerDataReadiness =
            playerDataAdmission.readinessObservation();
        HandshakeCapabilities prepared = new HandshakeCapabilities(coordinator, registration, observation,
            authorityEpoch, triggerRegistry, triggerObservation, persistenceCapability, runtimeHealth,
            runtimePrepared, storage, storageObservation, durabilityHealth, playerDataAdmission, playerDataReadiness);
        if (!prepared.current(moduleContext, persistenceRegistration, playerDataAdmission)) {
            throw new IllegalStateException("Handshake Readiness Changed During Preparation");
        }
        return prepared;
    }

    private static final class BridgeHandshake {
        private static final int PROBE_PENDING = 0;
        private static final int PROBE_RUNNING = 1;
        private static final int PROBE_FINISHED = 2;
        private static final int PROBE_CANCELLED = 3;
        private final HandshakeRequest request;
        private final UUID playerId;
        private final String playerName;
        private final long started;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger probeState = new AtomicInteger(PROBE_PENDING);
        private final AtomicReference<Future<?>> probe = new AtomicReference<>();
        private final AtomicReference<Thread> probeThread = new AtomicReference<>();

        private BridgeHandshake(HandshakeRequest request, UUID playerId, String playerName, long started) {
            this.request = request;
            this.playerId = playerId;
            this.playerName = playerName;
            this.started = started;
        }

        private HandshakeRequest request() {
            return request;
        }

        private UUID playerId() {
            return playerId;
        }

        private String playerName() {
            return playerName;
        }

        private long started() {
            return started;
        }

        private void attachProbe(Future<?> future) {
            if (!probe.compareAndSet(null, future)) {
                future.cancel(true);
                throw new IllegalStateException("Handshake Probe Is Already Attached");
            }
            if (cancelled.get()) {
                future.cancel(true);
            }
        }

        private boolean startProbe() {
            if (!probeState.compareAndSet(PROBE_PENDING, PROBE_RUNNING)) {
                return false;
            }
            probeThread.set(Thread.currentThread());
            return true;
        }

        private void finishProbe(Semaphore permits) {
            probeThread.compareAndSet(Thread.currentThread(), null);
            if (probeState.compareAndSet(PROBE_RUNNING, PROBE_FINISHED)) {
                permits.release();
            }
        }

        private void cancelProbe(Semaphore permits) {
            cancelled.set(true);
            if (probeState.compareAndSet(PROBE_PENDING, PROBE_CANCELLED)) {
                permits.release();
            }
            Future<?> future = probe.get();
            if (future != null && probeThread.get() != Thread.currentThread()) {
                future.cancel(true);
            }
        }
    }

    private record HandshakeCapabilities(ReSyncPersistenceCoordinator coordinator,
                                         ReSyncPersistenceTopology.Registration registration,
                                         ReSyncPersistenceCoordinator.ReadinessObservation observation,
                                         long authorityEpoch,
                                         TriggerRegistry triggerRegistry,
                                         TriggerRegistry.BindingObservation triggerObservation,
                                         Map<String, Object> persistence,
                                         RuntimeBoundaryHealth runtimeHealth,
                                         RuntimeBoundaryHealth.Prepared runtimePrepared,
                                         FlowStorage storage, FlowStorage.RuntimeObservation storageObservation,
                                         AssetIntegrityService.HealthReport durabilityHealth,
                                         PaperPlayerDataMutationAdmission playerDataAdmission,
                                         PaperPlayerDataMutationAdmission.ReadinessObservation playerDataReadiness) {
        private boolean current(ModuleContext context, ReSyncPersistenceTopology.Registration currentRegistration,
                                PaperPlayerDataMutationAdmission currentPlayerDataAdmission) {
            return coordinator == context.getService(ReSyncPersistenceCoordinator.class)
                && registration == currentRegistration && (coordinator == null || coordinator.isCurrent(observation))
                && triggerRegistry == context.getService(TriggerRegistry.class)
                && (triggerRegistry == null || triggerRegistry.isCurrent(triggerObservation))
                && storage == context.getService(FlowStorage.class)
                && (storage == null || storage.isRuntimeObservationCurrent(storageObservation))
                && playerDataAdmission == currentPlayerDataAdmission
                && playerDataAdmission.isCurrent(playerDataReadiness);
        }

        private TriggerRegistry.BindingObservation triggerCapability(TriggerRegistry current) {
            if (triggerRegistry != current || !current.isCurrent(triggerObservation)) {
                throw new IllegalStateException("Trigger Binding State Changed During Handshake");
            }
            return triggerObservation;
        }

        private Map<String, Object> persistenceCapability(ReSyncPersistenceCoordinator current,
                                                         ReSyncPersistenceTopology.Registration currentRegistration) {
            return coordinator == current && registration == currentRegistration && coordinator.isCurrent(observation)
                ? persistence : Map.of("available", false, "restoreReady", false,
                    "unavailable", "Persistence Readiness Changed During Handshake");
        }

        private PaperPlayerDataMutationAdmission.Readiness playerDataReadiness(
            PaperPlayerDataMutationAdmission current) {
            if (playerDataAdmission != current || !current.isCurrent(playerDataReadiness)) {
                throw new IllegalStateException("Paper Player Data Readiness Changed During Handshake");
            }
            return playerDataReadiness.readiness();
        }
    }

    private Map<String, Object> capabilitySnapshot() {
        return capabilitySnapshot(null);
    }

    private Map<String, Object> capabilitySnapshot(HandshakeCapabilities prepared) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        long currentAuthorityEpoch = prepared == null ? currentAuthorityEpoch() : prepared.authorityEpoch();
        capabilities.put("authorityEpoch", currentAuthorityEpoch);
        capabilities.put("paperVersion", Bukkit.getMinecraftVersion());
        capabilities.put("placeholderApi", Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"));
        capabilities.put("protocolLib", Bukkit.getPluginManager().isPluginEnabled("ProtocolLib"));
        capabilities.put("recipeTypes", List.of("shaped", "shapeless", "furnace", "blasting", "smoking", "campfire", "stonecutting", "smithing_transform", "smithing_trim"));
        capabilities.put("packetHooks", Bukkit.getPluginManager().isPluginEnabled("ProtocolLib") ? List.of("native", "packet") : List.of("native"));
        ReSyncJsonResourceStorage jsonStorage = moduleContext.getService(ReSyncJsonResourceStorage.class);
        capabilities.put("resourceTypes", jsonStorage != null ? jsonStorage.resourceTypes() : List.of());
        FlowResourceRegistry resourceRegistry = moduleContext.getService(FlowResourceRegistry.class);
        if (resourceRegistry != null) {
            capabilities.put("managedResources", Map.of("version", 1, "descriptors", resourceRegistry.metadata()));
        }
        FlowModule flowModule = moduleContext.getService(FlowModule.class);
        if (flowModule != null) {
            flowModule.activeCatalogPublicationKey().ifPresent(key -> capabilities.put("catalogPublicationKey", key));
            flowModule.catalogAuthoringCapability().ifPresent(value -> capabilities.put("catalogAuthoring", value));
        }
        TriggerRegistry triggerRegistry = moduleContext.getService(TriggerRegistry.class);
        if (triggerRegistry != null) {
            TriggerRegistry.BindingObservation trigger = prepared == null ? null
                : prepared.triggerCapability(triggerRegistry);
            capabilities.put(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_CAPABILITY, Map.of(
                ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_EPOCH_FIELD,
                trigger == null ? triggerRegistry.bindingEpoch() : trigger.epoch(),
                ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_STATE_HASH_FIELD,
                trigger == null ? triggerRegistry.bindingHash() : trigger.hash()));
        }
        ReSyncPersistenceCoordinator persistence = moduleContext.getService(ReSyncPersistenceCoordinator.class);
        if (persistence != null) {
            capabilities.put("persistence", prepared == null
                ? cachedPersistenceCapability(persistence, persistenceRegistration)
                : prepared.persistenceCapability(persistence, persistenceRegistration));
        }
        LuckPermsBackendPersistenceCapability luckPermsBackend =
            moduleContext.getService(LuckPermsBackendPersistenceCapability.class);
        LuckPermsBackendPersistenceCapability.Readiness backendReadiness = luckPermsBackend == null
            ? LuckPermsBackendPersistenceCapability.unavailable().readiness()
            : luckPermsBackend.readiness();
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("available", backendReadiness.available());
        backend.put("snapshotReady", backendReadiness.snapshotReady());
        backend.put("restoreReady", backendReadiness.restoreReady());
        backend.put("rebindReady", backendReadiness.rebindReady());
        backend.put("state", backendReadiness.state().name());
        backend.put("adapterId", backendReadiness.adapterId());
        backend.put("generation", backendReadiness.generation());
        backend.put("activeAdmissions", backendReadiness.activeAdmissions());
        if (backendReadiness.activeRoot() != null) {
            backend.put("activeRoot", backendReadiness.activeRoot().toString());
        }
        if (backendReadiness.reason() != null && !backendReadiness.reason().isBlank()) {
            backend.put("reason", backendReadiness.reason());
        }
        capabilities.put("luckPermsBackendPersistence", Map.copyOf(backend));
        if (managedFlowFileCapability != null) {
            Map<String, Object> flowFiles = new LinkedHashMap<>();
            flowFiles.put("owner", ManagedFlowFilePersistenceParticipant.OWNER);
            flowFiles.put("available", managedFlowFileCapability.available());
            flowFiles.put("formatVersion", ManagedFlowFileStoreContract.FORMAT_VERSION);
            String failure = managedFlowFileCapability.failureReason();
            if (failure != null && !failure.isBlank()) {
                flowFiles.put("reason", failure);
            }
            capabilities.put("flowFileStorage", Map.copyOf(flowFiles));
        }
        RuntimeBoundaryHealth runtimeHealth = moduleContext.getService(RuntimeBoundaryHealth.class);
        if (runtimeHealth != null) {
            capabilities.put("runtimeBoundaries", prepared == null ? runtimeHealth.snapshot()
                : runtimeHealth.snapshot(prepared.runtimeHealth() == runtimeHealth ? prepared.runtimePrepared() : null));
        }
        capabilities.put("playerManagement", Map.of(
            "protocolVersion", 2,
            "channel", "player_tracking",
            "sections", List.of("overview", "activity", "history", "inventory", "enderChest", "effects", "extensions"),
            "operations", List.of("playerData", "onlineInventoryEdit", "gameRules", "liveSettings")
        ));
        PaperPlayerDataMutationAdmission.Readiness playerDataReadiness = prepared == null
            ? playerDataAdmission.readiness() : prepared.playerDataReadiness(playerDataAdmission);
        Map<String, Object> playerDataCapability = new LinkedHashMap<>();
        playerDataCapability.put("owner", PaperPlayerDataMutationAdmission.OWNER);
        playerDataCapability.put("mutationAvailable", playerDataReadiness.mutationAvailable());
        playerDataCapability.put("snapshotSupported", playerDataReadiness.snapshotSupported());
        playerDataCapability.put("restoreSupported", playerDataReadiness.restoreSupported());
        playerDataCapability.put("state", playerDataReadiness.state().name());
        playerDataCapability.put("activeWork", playerDataReadiness.activeWork());
        playerDataCapability.put("unloadedWorldPolicy", playerDataAdmission.unloadedWorldPolicy().name());
        playerDataCapability.put("reason", playerDataReadiness.reason());
        capabilities.put("paperPlayerDataAdmission", Map.copyOf(playerDataCapability));
        AdvancementModule advancementModule = moduleContext.getService(AdvancementModule.class);
        if (advancementModule != null) {
            capabilities.put("advancements", advancementModule.capabilityPayload());
        }
        MessageRewriteModule messageRewriteModule = moduleContext.getService(MessageRewriteModule.class);
        if (messageRewriteModule != null) {
            capabilities.put("messageRewrite", messageRewriteModule.capabilityPayload());
        }
        Map<String, Object> protocolEnvelope = protocolEnvelopeCapability(
            protocolEnvelopeDispatch.isSupported(),
            resourceMutationAuthority != null && resourceMutationAuthority.durable());
        if (!protocolEnvelope.isEmpty()) {
            protocolEnvelope = new LinkedHashMap<>(protocolEnvelope);
            protocolEnvelope.put("authorityEpoch", currentAuthorityEpoch);
            capabilities.put("protocolEnvelope", protocolEnvelope);
        }
        return capabilities;
    }

    private static Map<String, Object> cachedPersistenceCapability(
        ReSyncPersistenceCoordinator persistence,
        ReSyncPersistenceTopology.Registration registration) {
        Map<String, Object> capability = new LinkedHashMap<>();
        PersistenceReadinessCertificate certificate = registration == null
            ? persistence.currentReadinessCertificate().orElse(null)
            : persistence.currentReadinessCertificate(registration.readiness()).orElse(null);
        boolean sealed = registration != null && registration.sealed() && persistence.sealed()
            && !persistence.shutdownStarted();
        boolean certificateMatches = sealed && certificate != null;
        boolean luckPermsRestoreUnavailable = registration != null
            && registration.readiness().owner(LuckPermsBackendPersistenceParticipant.OWNER) != null
            && registration.readiness().owner(LuckPermsBackendPersistenceParticipant.OWNER).state()
                == PersistenceRootReadiness.State.UNAVAILABLE;
        boolean available = certificateMatches;
        boolean restoreReady = available && !luckPermsRestoreUnavailable;
        capability.put("available", available);
        capability.put("restoreReady", restoreReady);
        capability.put("shutdown", persistence.shutdownStatus().payload());
        capability.put("registeredOwners", registration == null ? List.of() : registration.registeredOwners());
        capability.put("ephemeralLifecycles", !certificateMatches
            ? List.of()
            : certificate.ephemeralLifecycles().stream()
                .map(ReSyncServer::ephemeralLifecycleCapability)
                .toList());
        capability.put("rebind", certificateMatches ? certificate.rebind().payload() : Map.of());
        if (registration != null) {
            capability.put("unavailableOwners", registration.unavailableOwners());
            capability.put("unavailableReasons", registration.unavailableReasons());
            capability.put("rootReadiness", registration.readiness().payload());
        }
        if (available) {
            capability.put("snapshot", true);
            if (restoreReady) {
                capability.put("restore", true);
            } else {
                capability.put("restoreUnavailable", luckPermsRestoreUnavailable
                    ? "Restore Requires A Coordinated LuckPerms Backend Participant"
                    : "Restore Requires Restore-Safe Participants And A Valid Active Root");
            }
        } else {
            capability.put("unavailable", "Snapshot And Restore Require A Sealed Participant Registry");
        }
        return Map.copyOf(capability);
    }

    private long currentAuthorityEpoch() {
        long epoch = authorityEpoch.current();
        if (epoch < 1L) {
            throw new IllegalStateException("ReSync authority epoch is not bound");
        }
        return epoch;
    }

    static Map<String, Object> persistenceCapability(ReSyncPersistenceCoordinator persistence) {
        return persistenceCapability(persistence, null);
    }

    static Map<String, Object> persistenceCapability(ReSyncPersistenceCoordinator persistence,
                                                     ReSyncPersistenceTopology.Registration registration) {
        return persistenceCapability(persistence, registration, true);
    }

    private static Map<String, Object> persistenceCapability(
        ReSyncPersistenceCoordinator persistence,
        ReSyncPersistenceTopology.Registration registration,
        boolean refresh) {
        if (persistence == null) {
            return Map.of("available", false, "reason", "Persistence Coordinator Is Unavailable");
        }
        ReSyncPersistenceTopology.Registration current = registration;
        if (refresh && registration != null) {
            persistence.invalidateReadinessCertificate();
            current = refreshPersistenceRegistration(persistence, registration);
            if (current != null && current.sealed()) {
                ReSyncPersistenceCoordinator.ReadinessProof proof = persistence
                    .validateRestoreReadiness(current.readiness())
                    .orElse(null);
                if (proof != null) {
                    persistence.publishReadinessCertificate(proof);
                }
            }
        }
        if (registration != null) {
            return cachedPersistenceCapability(persistence, current);
        }
        boolean sealed = !persistence.shutdownStarted() && persistence.sealed();
        PersistenceReadinessCertificate certificate = persistence.currentReadinessCertificate().orElse(null);
        boolean certificateMatches = certificate != null;
        boolean luckPermsRestoreUnavailable = current != null
            && current.readiness().owner(LuckPermsBackendPersistenceParticipant.OWNER) != null
            && current.readiness().owner(LuckPermsBackendPersistenceParticipant.OWNER).state()
                == PersistenceRootReadiness.State.UNAVAILABLE;
        boolean restoreReady = sealed && certificateMatches && !luckPermsRestoreUnavailable;
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("available", sealed);
        capability.put("restoreReady", restoreReady);
        capability.put("shutdown", persistence.shutdownStatus().payload());
        capability.put("registeredOwners", persistence.participants().participants().stream()
            .map(participant -> participant.owner()).sorted().toList());
        capability.put("ephemeralLifecycles", !certificateMatches
            ? List.of()
            : certificate.ephemeralLifecycles().stream().map(ReSyncServer::ephemeralLifecycleCapability).toList());
        capability.put("rebind", certificateMatches ? certificate.rebind().payload() : Map.of());
        if (current != null) {
            capability.put("unavailableOwners", current.unavailableOwners());
            capability.put("unavailableReasons", current.unavailableReasons());
            capability.put("rootReadiness", current.readiness().payload());
        }
        if (sealed) {
            capability.put("snapshot", true);
            if (restoreReady) {
                capability.put("restore", true);
            } else {
                capability.put("restoreUnavailable", luckPermsRestoreUnavailable
                    ? "Restore Requires A Coordinated LuckPerms Backend Participant"
                    : "Restore Requires Restore-Safe Participants And A Valid Active Root");
            }
        } else {
            capability.put("unavailable", "Snapshot And Restore Require A Sealed Participant Registry");
        }
        return Map.copyOf(capability);
    }

    private static Map<String, Object> ephemeralLifecycleCapability(EphemeralLifecycleParticipant participant) {
        EphemeralLifecycleParticipant.Health health = participant.lifecycleHealth();
        return Map.of(
            "owner", participant.owner(),
            "health", Map.of(
                "available", health.available(),
                "state", health.state(),
                "physicalTasks", health.physicalTasks(),
                "reason", health.reason()));
    }

    private static Map<String, Object> ephemeralLifecycleCapability(PersistenceReadinessCertificate.Ephemeral lifecycle) {
        return Map.of(
            "owner", lifecycle.owner(),
            "health", Map.of(
                "available", lifecycle.available(),
                "state", lifecycle.state(),
                "physicalTasks", lifecycle.physicalTasks(),
                "reason", lifecycle.reason()));
    }

    static Map<String, Object> protocolEnvelopeCapability(boolean supported) {
        return protocolEnvelopeCapability(supported, false);
    }

    static Map<String, Object> protocolEnvelopeCapability(boolean supported, boolean durableMutationAuthority) {
        if (!supported) {
            return Map.of();
        }
        Map<String, Object> capability = new LinkedHashMap<>(ReSyncProtocolInventory.protocolEnvelopeEntry());
        capability.put("supported", true);
        capability.put("messageType", MessageType.PROTOCOL_ENVELOPE.name());
        capability.put("mutationAuthority", durableMutationAuthority
            ? Map.of("supported", true, "durable", true, "legacyResourceMutations", "blocked")
            : Map.of("supported", false, "durable", false, "legacyResourceMutations", "blocked",
                "reason", "Durable resource mutation authority is unavailable"));
        return Map.copyOf(capability);
    }

    public FlowModule getFlowModule() {
        return moduleContext.getService(FlowModule.class);
    }

    public ProductionAuthorityBundle exportProductionAuthorityBundle() throws IOException {
        return exportProductionAuthorityBundle(true);
    }

    private ProductionAuthorityBundle exportProductionAuthorityBundle(boolean publishReadiness) throws IOException {
        return exportProductionAuthorityBundle(publishReadiness, null);
    }

    private ProductionAuthorityBundle exportProductionAuthorityBundle(
        boolean publishReadiness,
        ReSyncPersistenceCoordinator.ReadinessProof startupProof) throws IOException {
        ReSyncPersistenceCoordinator persistence = moduleContext.getRequiredService(ReSyncPersistenceCoordinator.class);
        ReSyncPersistenceTopology.Registration registration = persistenceRegistration;
        if (startupProof == null) {
            persistence.invalidateReadinessCertificate();
        } else if (registration == null || persistence.currentValidatedReadinessProof(registration.readiness())
            .filter(current -> current == startupProof).isEmpty()) {
            throw new IOException("Production Authority Bundle Requires The Current Startup Readiness Proof");
        }
        FlowModule flow = moduleContext.getRequiredService(FlowModule.class);
        CatalogActivationAuthority activationAuthority = moduleContext.getRequiredService(CatalogActivationAuthority.class);
        ServerIdentityStore identity = moduleContext.getRequiredService(ServerIdentityStore.class);
        if (registration == null || !registration.sealed() || !flow.startupActivationComplete() || !flow.isCatalogCoherent()) {
            throw new IOException("Production Authority Bundle Requires Complete Startup Activation");
        }
        if (startupProof == null) {
            registration = requireProductionAuthorityExportReady(persistence, registration);
        }
        Path target = ProductionAuthorityBundle.path(dataRoot);
        ProductionAuthorityBundle bundle = ProductionAuthorityBundleExporter.emit(new ProductionAuthorityBundleExporter.Source(
            flow.catalogRuntimeActivation(), activationAuthority, registration.readiness(), persistence, identity.serverId(),
            identity.productionAuthoritySigner(), startupProof), target);
        ReSyncPersistenceTopology.Registration refreshed;
        if (startupProof == null) {
            refreshed = refreshPersistenceRegistration();
        } else {
            ReSyncPersistenceCoordinator.ReadinessProof derived = persistence.deriveStartupReadiness(
                startupProof, Set.of(ProductionAuthorityBundlePersistenceParticipant.OWNER));
            refreshed = registration.withReadiness(derived.readiness());
            persistenceRegistration = refreshed;
        }
        PersistenceRootReadiness.Owner authorityOwner = refreshed != null
            ? refreshed.readiness().owner(ProductionAuthorityBundlePersistenceParticipant.OWNER) : null;
        if (authorityOwner == null || authorityOwner.state() != PersistenceRootReadiness.State.REGISTERED) {
            String reason = authorityOwner == null || authorityOwner.reason().isBlank()
                ? "Production Authority Bundle Persistence Is Unavailable" : authorityOwner.reason();
            throw new IOException(reason);
        }
        if (publishReadiness) {
            publishPersistenceReadinessCertificate();
        }
        return bundle;
    }

    public ProductionAuthorityBundle exportProductionAuthorityBundle(String relativePath) throws IOException {
        try {
            if (relativePath == null || !Path.of(ProductionAuthorityBundle.AUTHORITY_DIRECTORY,
                ProductionAuthorityBundle.AUTHORITY_FILE).equals(Path.of(relativePath))) {
                throw new IOException("Authority Bundle Export Path Is Fixed");
            }
        } catch (InvalidPathException exception) {
            throw new IOException("Authority Bundle Export Path Is Fixed", exception);
        }
        Path requested = MigrationPaths.resolveInside(
            dataRoot, relativePath);
        if (!ProductionAuthorityBundle.path(dataRoot).equals(requested)) {
            throw new IOException("Authority Bundle Export Path Is Fixed");
        }
        return exportProductionAuthorityBundle();
    }

    public ProductionAuthorityTrustAnchor exportProductionAuthorityTrustAnchor() throws IOException {
        return authorityIssuer.exportTrustAnchor();
    }

    public ProductionAuthorityTrustAnchor exportProductionAuthorityTrustAnchor(String relativePath) throws IOException {
        try {
            if (relativePath == null || !Path.of(ProductionAuthorityKeyStore.TRUST_ANCHOR_DIRECTORY,
                ProductionAuthorityKeyStore.TRUST_ANCHOR_FILE).equals(Path.of(relativePath))) {
                throw new IOException("Authority Trust Anchor Export Path Is Fixed");
            }
        } catch (InvalidPathException exception) {
            throw new IOException("Authority Trust Anchor Export Path Is Fixed", exception);
        }
        Path requested = MigrationPaths.resolveInside(
            dataRoot, relativePath);
        if (!authorityIssuer.trustAnchorPath().equals(requested)) {
            throw new IOException("Authority Trust Anchor Export Path Is Fixed");
        }
        return exportProductionAuthorityTrustAnchor();
    }

    public AuthorityUseGrant issueProductionAuthorityUseGrant(Path sourceRoot, String migrationId,
                                                                String invocationHash, String planPreimageHash,
                                                                String grantId) throws IOException {
        Path source = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot").toRealPath();
        ensureLocalAuthorityBundle(source);
        return authorityIssuer.issueUseGrant(source, migrationId, invocationHash, planPreimageHash, grantId);
    }

    public AuthorityUseGrant issueProductionAuthorityUseGrantFromPlanPreimage(Path sourceRoot, String migrationId,
                                                                                String invocationHash,
                                                                                Path planPreimage,
                                                                                String grantId) throws IOException {
        Path source = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot").toRealPath();
        ensureLocalAuthorityBundle(source);
        return authorityIssuer.issueUseGrantFromPlanPreimage(source, migrationId, invocationHash, planPreimage, grantId);
    }

    public Path productionAuthorityTrustAnchorPath() throws IOException {
        return authorityIssuer.trustAnchorPath();
    }

    public Path productionAuthorityUseGrantPath(String grantId) throws IOException {
        return authorityIssuer.grantPath(grantId, dataRoot);
    }

    private void ensureLocalAuthorityBundle(Path source) throws IOException {
        Path local = dataRoot.toRealPath();
        if (local.equals(source)) {
            exportProductionAuthorityBundle();
        }
    }

    public FlowStorage getFlowStorage() {
        return moduleContext.getService(FlowStorage.class);
    }

    public GuiManager getGuiManager() {
        return moduleContext.getService(GuiManager.class);
    }

    public FlowExecutor getFlowExecutor() {
        return moduleContext.getService(FlowExecutor.class);
    }

    public PlayerTrackingService getPlayerTrackingService() {
        return moduleContext.getService(PlayerTrackingService.class);
    }

    public StructureLibrary getStructureLibrary() {
        return structureLibrary;
    }

    public Path getDataRoot() {
        return dataRoot;
    }

    public PlayerSessionLinkService getPlayerSessionLinkService() {
        return moduleContext.getService(PlayerSessionLinkService.class);
    }

    public WorldManagementService getWorldManagementService() {
        return moduleContext.getService(WorldManagementService.class);
    }

    public LuckPermsBackendPersistenceCapability getLuckPermsBackendPersistence() {
        return moduleContext.getService(LuckPermsBackendPersistenceCapability.class);
    }

    public LuckPermsBackendPersistenceParticipant getLuckPermsBackendPersistenceParticipant() {
        return moduleContext.getService(LuckPermsBackendPersistenceParticipant.class);
    }

    public LuckPermsBackendPersistenceCapability.Readiness getLuckPermsBackendPersistenceReadiness() {
        LuckPermsBackendPersistenceCapability capability = getLuckPermsBackendPersistence();
        return capability == null ? LuckPermsBackendPersistenceCapability.unavailable().readiness() : capability.readiness();
    }

    private record PreparedPersistence(ReSyncPersistenceCoordinator coordinator, Path activeRoot) {
        private PreparedPersistence {
            Objects.requireNonNull(coordinator, "coordinator");
            activeRoot = MigrationPaths.requirePath(activeRoot, "activeRoot");
        }
    }

    public record NetworkShutdownResult(boolean completed, String detail,
                                        NetworkPersistenceDrainController.State state) {
        public NetworkShutdownResult {
            detail = detail == null ? "" : detail;
            state = state == null ? NetworkPersistenceDrainController.State.OPEN : state;
        }

        public static NetworkShutdownResult success() {
            return new NetworkShutdownResult(true, "", NetworkPersistenceDrainController.State.CLOSED);
        }

        public static NetworkShutdownResult failed(String detail,
                                                   NetworkPersistenceDrainController.State state) {
            return new NetworkShutdownResult(false, detail, state);
        }
    }
}
