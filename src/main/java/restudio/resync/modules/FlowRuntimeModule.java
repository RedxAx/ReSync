package restudio.resync.modules;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowDataObjectAdapter;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.ReSync;
import restudio.resync.Log;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.ReSyncExtensionData;
import restudio.resync.api.ReSyncExtensionManager;
import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.api.RuntimeDataRegistry;
import restudio.resync.customcontent.CustomContentAccess;
import restudio.resync.customcontent.CustomBlocksPersistenceParticipant;
import restudio.resync.customcontent.CustomContentListener;
import restudio.resync.customcontent.CustomContentExecution;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customization.ReSyncJsonResourceStorage.ResourceListener;
import restudio.resync.core.Session;
import restudio.resync.dialog.DialogService;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.flow.CompiledCoreFlowExecutionBridge;
import restudio.resync.flow.CompiledFunctionExecutionBridge;
import restudio.resync.flow.CompiledGraphMetadata;
import restudio.resync.flow.CompiledGraphMetadataProvider;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowExecutionBridge;
import restudio.resync.flow.ServerCompiledPlanRepository;
import restudio.resync.flow.graph.CompiledPlanAuthority;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.FlowNodeAuditRecord;
import restudio.resync.flow.diagnostics.FlowDebugService;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.diagnostics.StructuredFlowDiagnosticReporter;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.ScoreboardRuntimeCapability;
import restudio.resync.flow.ScoreboardRuntimeListener;
import restudio.resync.flow.SystemEventListener;
import restudio.resync.flow.TabListService;
import restudio.flow.data.TypeRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationTaskPersistenceParticipant;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.automation.ScheduleDefinition;
import restudio.resync.flow.automation.TimerDefinition;
import restudio.resync.flow.automation.VariableDefinition;
import restudio.resync.flow.automation.VariableService;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.generic.BlockActionHandler;
import restudio.resync.flow.handler.generic.AbilityEffectHandler;
import restudio.resync.flow.handler.generic.ChatHandler;
import restudio.resync.flow.handler.generic.ColorHandler;
import restudio.resync.flow.handler.generic.ConversionHandler;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.handler.generic.CustomEventHandler;
import restudio.resync.flow.handler.generic.CustomContentHandler;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.handler.generic.DebugHandler;
import restudio.resync.flow.handler.generic.DiscordHandler;
import restudio.resync.flow.handler.generic.EconomyHandler;
import restudio.resync.flow.handler.generic.EntityActionHandler;
import restudio.resync.flow.handler.generic.FileHandler;
import restudio.resync.flow.handler.generic.ManagedFlowFileCapability;
import restudio.resync.flow.handler.generic.FlowControlHandler;
import restudio.resync.flow.handler.generic.FlowJobHandler;
import restudio.resync.flow.handler.generic.FunctionCatalogHandler;
import restudio.resync.flow.handler.generic.FunctionHandler;
import restudio.resync.flow.handler.generic.GenericListHandler;
import restudio.resync.flow.handler.generic.GenericMapHandler;
import restudio.resync.flow.handler.generic.GenericMathHandler;
import restudio.resync.flow.handler.generic.GenericStringHandler;
import restudio.resync.flow.handler.generic.HttpHandler;
import restudio.resync.flow.handler.generic.InventoryActionHandler;
import restudio.resync.flow.handler.generic.JsonHandler;
import restudio.resync.flow.handler.generic.LocationHandler;
import restudio.resync.flow.handler.generic.LogicHandler;
import restudio.resync.flow.handler.generic.ResultHandler;
import restudio.resync.flow.handler.generic.ResourceValueHandler;
import restudio.resync.flow.handler.generic.MenuHandler;
import restudio.resync.flow.handler.generic.MiscHandler;
import restudio.resync.flow.handler.generic.NetworkFlowHandler;
import restudio.resync.flow.handler.generic.ParticleHandler;
import restudio.resync.flow.handler.generic.PermissionHandler;
import restudio.resync.flow.handler.generic.PlaceholderHandler;
import restudio.resync.flow.handler.generic.PlayerActionHandler;
import restudio.resync.flow.handler.generic.RandomHandler;
import restudio.resync.flow.handler.generic.ReSyncRuntimeResourceHandler;
import restudio.resync.flow.handler.generic.RegionHandler;
import restudio.resync.flow.handler.generic.RegionPersistenceParticipant;
import restudio.resync.flow.handler.generic.ResourceDefinitionHandler;
import restudio.resync.flow.handler.generic.RestoredNodeHandler;
import restudio.resync.flow.handler.generic.RuntimeDataHandler;
import restudio.resync.flow.handler.generic.ScheduleHandler;
import restudio.resync.flow.handler.generic.ScoreboardHandler;
import restudio.resync.flow.handler.generic.ServerHandler;
import restudio.resync.flow.handler.generic.SoundHandler;
import restudio.resync.flow.handler.generic.TeamHandler;
import restudio.resync.flow.handler.generic.TextFormatHandler;
import restudio.resync.flow.handler.generic.TextResourceHandler;
import restudio.resync.text.ReTextService;
import restudio.resync.flow.handler.generic.TimeHandler;
import restudio.resync.flow.handler.generic.TimerHandler;
import restudio.resync.flow.handler.generic.TitleHandler;
import restudio.resync.flow.handler.generic.UuidHandler;
import restudio.resync.flow.handler.generic.VariableHandler;
import restudio.resync.flow.handler.generic.VariableScopeHandler;
import restudio.resync.flow.handler.generic.WorldActionHandler;
import restudio.resync.flow.handler.generic.WorldGenFlowHandler;
import restudio.resync.flow.function.TypedFunctionCapabilityProvider;
import restudio.resync.flow.function.TypedFunctionSourceProvider;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.server.ServerIdentityStore;
import restudio.resync.server.TemporaryLifecycleDiagnostics;
import restudio.resync.server.CoreGraphResourceAuthority;
import restudio.resync.server.CoreGraphMutationValidator;
import restudio.resync.server.FlowStorageCoreGraphResourceAuthority;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.migration.ReplacementActivationRecord;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.ReplacementRuntimeProviderAuthority;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.handler.family.JsonFamilyHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.network.NetworkFlowBridge;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;
import restudio.resync.flow.registry.AuthoredSourceProvenance;
import restudio.resync.flow.registry.ReplacementCatalogSource;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.flow.validation.FlowGraphValidator;
import restudio.resync.flow.validation.FlowGraphValidationResult;
import restudio.resync.flow.validation.FlowGraphValidationRegistry;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.messages.MessageLogService;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.modules.flow.FlowMutationPayloadReader;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceAuditRecord;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.modules.flow.WorldWorkspaceDocumentProvider;
import restudio.resync.modules.flow.BuiltinOptionCatalogService;
import restudio.resync.modules.flow.LuckPermsOptionCatalogService;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.protocol.ReSyncProtocolInventory;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.server.ConfigurationPersistenceParticipant;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.structure.StructureLibrary;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldManagementListener;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.WorldGenOperationService;
import restudio.resync.worldgen.registry.WorldGenFlowCatalogContribution;
import restudio.resync.runtime.LootTableService;
import restudio.resync.runtime.JsonRuntimeResourceValidator;
import restudio.resync.runtime.NpcService;
import restudio.resync.runtime.PlayerNpcRuntime;
import restudio.resync.runtime.ReSyncRuntimeContentAccess;
import restudio.resync.runtime.RuntimeFlowDispatcher;
import restudio.resync.runtime.TradeProfileService;
import restudio.resync.runtime.data.CustomContentItemDataAdapter;
import restudio.resync.runtime.data.ExternalItemDataAdapter;
import restudio.resync.runtime.data.RuntimeDataOptionCatalogService;
import restudio.resync.runtime.data.VanillaItemDataAdapter;
import restudio.resync.network.NetworkNodePresence;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.paper.ReSyncNetworkAgent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FlowRuntimeModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("flow", "Flow", "flow").withDependencies("flowJobs", "playerNpcPackets", "worldGen", "worldManagement");
    private static final Duration STOP_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();
    private FlowModule delegate;
    private FlowStorage storage;
    private FlowExecutor executor;
    private GuiManager guiManager;
    private GlobalTriggers globalTriggers;
    private FlowEventRegistry flowEventRegistry;
    private NetworkFlowBridge networkFlowBridge;
    private SystemEventListener systemEventListener;
    private ScoreboardRuntimeListener scoreboardRuntimeListener;
    private PropertyRegistry propertyRegistry;
    private CustomContentStorage customContentStorage;
    private CustomContentService customContentService;
    private CustomBlocksPersistenceParticipant customBlocksPersistenceParticipant;
    private CustomContentListener customContentListener;
    private LootTableService lootTableService;
    private TradeProfileService tradeProfileService;
    private NpcService npcService;
    private FlowTraceService traceService;
    private FlowDebugService debugService;
    private BukkitTask tickTask;
    private ModuleContext moduleContext;
    private ReSyncJsonResourceStorage jsonResourceStorage;
    private OptionCatalogRegistry optionCatalogRegistry;
    private RuntimeDataRegistry runtimeDataRegistry;
    private BuiltinOptionCatalogService builtinOptionCatalogs;
    private FlowResourceRegistry resourceRegistry;
    private FlowValueCodecRegistry valueCodecs;
    private AutomationDefinitionRegistry automationDefinitions;
    private final Map<String, AutomationCatalogProvider> automationCatalogProviders = new ConcurrentHashMap<>();
    private AutomationTaskService automationTasks;
    private PersistentVariableStore persistentVariables;
    private VariableService automationVariables;
    private ScheduleHandler scheduleHandler;
    private FlowGraphValidationRegistry graphValidationRegistry;
    private StructuredFlowDiagnosticReporter diagnosticReporter;
    private HandlerRegistry handlerRegistry;
    private RegionHandler regionHandler;
    private RegionPersistenceParticipant regionPersistenceParticipant;
    private volatile List<NodeDefinitionDiagnostic> nodeDefinitionDiagnostics = List.of();
    private volatile NodeInventoryCache nodeInventoryCache;
    private volatile RegistryChecksumCache registryChecksumCache;
    private volatile StaticCatalogReuse startupStaticCatalog;
    private final AtomicBoolean customFunctionDefinitionRefreshPending = new AtomicBoolean();
    private ResourceListener jsonResourceListener;
    private JsonRuntimeResourceValidator jsonResourceValidator;
    private ItemAttributeSchemaService itemAttributeSchemaService;
    private WorldGenProjectStorage worldGenStorage;
    private WorldGenOperationService worldGenOperations;
    private FlowJobRegistry flowJobs;
    private RuntimeBindingRegistry runtimeBindingRegistry;
    private RuntimePrincipalAuthority runtimePrincipalAuthority;
    private ManagedFlowFileCapability managedFlowFileCapability;
    private ServerId serverId;
    private CompiledGraphMetadataProvider compiledMetadataProvider;
    private CompiledCoreFlowExecutionBridge compiledBridge;
    private CompiledFunctionExecutionBridge compiledFunctionBridge;
    private CompiledTriggerExecution compiledTriggerExecution;
    private LegacyRuntimeActivationGate legacyRuntimeGate;
    private WorldManagementService worldManagementService;
    private WorldManagementListener worldResourceListener;
    private ReSyncNetworkAgent.Listener networkOptionCatalogListener;
    private ReSyncNetworkAgent networkOptionCatalogAgent;
    private volatile boolean nodeDefinitionReloadPending;
    private final AtomicBoolean nodeDefinitionReloadRequested = new AtomicBoolean();
    private final AtomicBoolean nodeDefinitionReloadInFlight = new AtomicBoolean();
    private volatile boolean stopPending;
    private volatile boolean stopped;
    private volatile CompletionStage<Void> stopCompletion;
    private volatile FlowExecutor.AdmissionFence stopAdmissionFence;
    private FlowExecutor.AdmissionFence startupAdmissionFence;
    private FlowExecutor.AdmissionFence fatalActivationFence;
    private volatile Throwable fatalActivationFault;
    private volatile boolean diagnosticsPublicationReady;
    private boolean runtimeAdmissionOpened;
    private boolean startupAutomationRestorePending;
    private boolean startupNpcRestorePending;
    private CatalogActivationAuthority replacementActivationAuthority = CatalogActivationAuthority.forbidden();
    private ReplacementActivationRecord.Values startupActivationRecord;
    private boolean freshInstall;
    private Path dataRoot;
    private Path persistenceDataRoot;
    private volatile boolean startupActivationComplete;
    private CatalogSettlement catalogSettlement;
    private CoreGraphResourceAuthority coreGraphResourceAuthority;
    private ServerCompiledPlanRepository compiledPlanRepository;
    private CustomContentExecution customContentExecution;
    private final TypedFunctionSourceProvider typedFunctionSourceProvider;
    private final TypedFunctionCapabilityProvider typedFunctionCapabilityProvider;
    private final ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority;

    public FlowRuntimeModule() {
        this(null, null, ReplacementRuntimeProviderAuthority.unavailable());
    }

    public FlowRuntimeModule(TypedFunctionSourceProvider typedFunctionSourceProvider,
                             TypedFunctionCapabilityProvider typedFunctionCapabilityProvider) {
        this(typedFunctionSourceProvider, typedFunctionCapabilityProvider, ReplacementRuntimeProviderAuthority.unavailable());
    }

    public FlowRuntimeModule(TypedFunctionSourceProvider typedFunctionSourceProvider,
                             TypedFunctionCapabilityProvider typedFunctionCapabilityProvider,
                             ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority) {
        this.typedFunctionSourceProvider = typedFunctionSourceProvider;
        this.typedFunctionCapabilityProvider = typedFunctionCapabilityProvider;
        this.replacementRuntimeProviderAuthority = replacementRuntimeProviderAuthority == null
            ? ReplacementRuntimeProviderAuthority.unavailable()
            : replacementRuntimeProviderAuthority;
    }

    public ReplacementRuntimeProviderAuthority replacementRuntimeProviderAuthority() {
        return replacementRuntimeProviderAuthority;
    }

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        long initializationStarted = TemporaryLifecycleDiagnostics.start();
        long initializationCpuStarted = currentThreadCpuNanos();
        long initializationStageStarted = initializationStarted;
        long initializationStageCpuStarted = initializationCpuStarted;
        this.moduleContext = context;
        diagnosticsPublicationReady = false;
        AuthorityEpoch authorityEpoch = context.getRequiredService(AuthorityEpoch.class);
        if (authorityEpoch.current() < 1L) {
            throw new IllegalStateException("Flow authority epoch must be positive");
        }
        runtimeBindingRegistry = context.getRequiredService(RuntimeBindingRegistry.class);
        CatalogPublicationReceiptStore publicationReceiptStore = context.getRequiredService(CatalogPublicationReceiptStore.class);
        ReSyncPersistenceCoordinator persistence = context.getRequiredService(ReSyncPersistenceCoordinator.class);
        persistence.requireRegisteredParticipant(publicationReceiptStore);
        persistenceDataRoot = persistence.dataRoot().toAbsolutePath().normalize();
        AssetTransactionCoordinator assetTransactions = context.getRequiredService(AssetTransactionCoordinator.class);
        try {
            dataRoot = persistence.activeDataRoot().toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("Flow Active Data Root Could Not Be Read", exception);
        }
        persistentVariables = new PersistentVariableStore(dataRoot);
        persistence.register(persistentVariables);
        legacyRuntimeGate = LegacyRuntimeActivationGate.runtime(dataRoot);
        managedFlowFileCapability = context.getService(ManagedFlowFileCapability.class);
        if (managedFlowFileCapability == null) {
            managedFlowFileCapability = ManagedFlowFileCapability.unavailable(dataRoot,
                new IllegalStateException("Managed flow-file capability service is unavailable"));
        }
        ServerIdentityStore identityStore = context.getRequiredService(ServerIdentityStore.class);
        freshInstall = identityStore.freshInstall();
        replacementActivationAuthority = context.getService(CatalogActivationAuthority.class);
        if (replacementActivationAuthority == null) {
            replacementActivationAuthority = CatalogActivationAuthority.fromCommittedMigration(dataRoot);
            if (!replacementActivationAuthority.approved()
                && (freshInstall || ReplacementActivationRecord.exists(dataRoot))) {
                replacementActivationAuthority = CatalogActivationAuthority.freshInstall();
            }
        }
        startupActivationRecord = loadStartupActivationRecord();
        serverId = identityStore.serverId();
        RuntimeAuthority runtimeAuthority = context.getService(RuntimeAuthority.class);
        if (runtimeAuthority == null) {
            runtimeAuthority = new RuntimeAuthority("resync:" + serverId.canonicalText());
        }
        RuntimePrincipal runtimePrincipal = context.getService(RuntimePrincipal.class);
        runtimePrincipalAuthority = context.getService(RuntimePrincipalAuthority.class);
        AssetPersistenceGate assetsGate = context.getRequiredService(AssetPersistenceGate.class);
        ConfigurationPersistenceParticipant configurationPersistence = Objects.requireNonNull(
            context.getConfig().getPersistenceParticipant(), "Configuration persistence participant is required");
        storage = new FlowStorage(dataRoot.toFile(), legacyRuntimeGate, assetsGate, configurationPersistence, serverId,
            assetTransactions);
        coreGraphResourceAuthority = createCoreGraphResourceAuthority(storage, serverId);
        itemAttributeSchemaService = new ItemAttributeSchemaService();
        customContentStorage = new CustomContentStorage(context.getPlugin(), dataRoot, itemAttributeSchemaService, legacyRuntimeGate,
            assetsGate, assetTransactions);
        jsonResourceStorage = context.getRequiredService(ReSyncJsonResourceStorage.class);
        optionCatalogRegistry = context.getRequiredService(OptionCatalogRegistry.class);
        runtimeDataRegistry = optionCatalogRegistry.runtimeData();
        resourceRegistry = new FlowResourceRegistry();
        resourceRegistry.setLiveRefreshExecutor(this::runLiveRefresh);
        valueCodecs = new FlowValueCodecRegistry(legacyRuntimeGate);
        automationDefinitions = new AutomationDefinitionRegistry(jsonResourceStorage);
        automationTasks = createAutomationTaskService(context, automationDefinitions);
        automationVariables = new VariableService(automationDefinitions, valueCodecs, persistentVariables, context.getPlugin());
        reportInitializationStage("foundation_storage", initializationStageStarted, initializationStageCpuStarted);
        initializationStageStarted = TemporaryLifecycleDiagnostics.start();
        initializationStageCpuStarted = currentThreadCpuNanos();
        builtinOptionCatalogs = new BuiltinOptionCatalogService(() -> customContentService, itemAttributeSchemaService);
        builtinOptionCatalogs.registerProviders(optionCatalogRegistry);
        runtimeDataRegistry.register(new VanillaItemDataAdapter());
        new RuntimeDataOptionCatalogService(runtimeDataRegistry).registerProviders(optionCatalogRegistry);
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            new LuckPermsOptionCatalogService().registerProviders(optionCatalogRegistry);
        }
        registerNetworkCatalog(optionCatalogRegistry, context.getPlugin());
        worldGenStorage = context.getRequiredService(WorldGenProjectStorage.class);
        worldGenOperations = context.getRequiredService(WorldGenOperationService.class);
        flowJobs = context.getRequiredService(FlowJobRegistry.class);
        worldManagementService = context.getRequiredService(WorldManagementService.class);
        registerCoreResourceCatalogs(optionCatalogRegistry, storage, customContentStorage, worldGenStorage, worldManagementService);
        registerResourceCatalogs(optionCatalogRegistry, jsonResourceStorage);
        reportInitializationStage("catalog_providers", initializationStageStarted, initializationStageCpuStarted);
        initializationStageStarted = TemporaryLifecycleDiagnostics.start();
        initializationStageCpuStarted = currentThreadCpuNanos();
        TypeAdapterRegistry typeAdapterRegistry = new TypeAdapterRegistry();
        handlerRegistry = new HandlerRegistry();
        FlowRegistry flowRegistry = new FlowRegistry();
        flowRegistry.setHandlerRegistry(handlerRegistry);
        propertyRegistry = new PropertyRegistry();
        registerNodeHandlers(handlerRegistry);
        context.getRequiredService(ReSyncPersistenceCoordinator.class).register(regionPersistenceParticipant);
        context.registerService(RegionPersistenceParticipant.class, regionPersistenceParticipant);
        TypeRegistry typeRegistry = new TypeRegistry();
        FlowDataObjectAdapter.setTypeRegistry(typeRegistry);
        NodeDefinitionLoader jsonLoader = new NodeDefinitionLoader();
        jsonLoader.setValidator(new NodeDefinitionValidator(handlerRegistry, optionCatalogRegistry, true));
        diagnosticReporter = context.getService(StructuredFlowDiagnosticReporter.class);
        if (diagnosticReporter == null) {
            diagnosticReporter = new StructuredFlowDiagnosticReporter(dataRoot.resolve("diagnostics"));
        }
        reportInitializationStage("registry_runtime", initializationStageStarted, initializationStageCpuStarted);
        long catalogPreparationStarted = TemporaryLifecycleDiagnostics.start();
        long catalogPreparationCpuStarted = currentThreadCpuNanos();
        Path nodesDir = persistenceDataRoot.resolve("nodes");
        ReplacementCatalogSource.Snapshot replacementSource = ReplacementCatalogSource.load(jsonLoader, nodesDir);
        List<CatalogSourceIngestor.CatalogSource> authoredCatalogSources =
            loadAuthoredCatalogSources(replacementSource.sourceInventory(), replacementSource.definitions());
        List<NodeDefinition> classpathDefs = new ArrayList<>(replacementSource.classpathDefinitions());
        removeUnavailable(classpathDefs, jsonLoader);
        List<NodeDefinition> localDefs = new ArrayList<>(replacementSource.localDefinitions());
        removeUnavailable(localDefs, jsonLoader);
        NodeDefinitionRegistry stagedRegistry = new NodeDefinitionRegistry(false);
        WorldGenFlowCatalogContribution worldGenCatalog = moduleContext.getRequiredService(WorldGenFlowCatalogContribution.class);
        worldGenCatalog.apply(stagedRegistry, handlerRegistry);
        jsonLoader.validateAndRegister(classpathDefs, stagedRegistry, handlerRegistry, "json-classpath");
        if (!localDefs.isEmpty()) {
            jsonLoader.validateAndRegister(localDefs, stagedRegistry, handlerRegistry, "json");
        }
        requireReplacementCatalog(jsonLoader, stagedRegistry);
        CatalogActivationAuthority.Decision startupAuthority = replacementStartupDecision(!stagedRegistry.getAllDefinitions().isEmpty());
        if (!startupAuthority.allowed()) {
            throw new IllegalStateException(startupAuthority.code() + ": " + startupAuthority.detail());
        }
        NodeDefinitionRegistry nodeDefinitionRegistry = new NodeDefinitionRegistry(false);
        nodeDefinitionRegistry.registerAll("worldgen", stagedRegistry.getDefinitionsForPlugin("worldgen"));
        nodeDefinitionRegistry.registerAll("json-classpath", stagedRegistry.getDefinitionsForPlugin("json-classpath"));
        nodeDefinitionRegistry.registerAll("json", stagedRegistry.getDefinitionsForPlugin("json"));
        nodeDefinitionDiagnostics = jsonLoader.getDiagnostics();
        startupStaticCatalog = StaticCatalogReuse.capture(nodeDefinitionRegistry, replacementSource.sourceInventory(),
            authoredCatalogSources, nodeDefinitionDiagnostics, handlerRegistry, optionCatalogRegistry, worldGenCatalog);
        CustomFunctionNodeDefinitions.rebuild(nodeDefinitionRegistry, storage);
        Map<String, NodeDefinition> startupDefinitions = nodeDefinitionRegistry.getAllDefinitions();
        int startupDefinitionCount = startupDefinitions.size();
        propertyRegistry.loadNodeDefinitions(startupDefinitions.values());
        TemporaryLifecycleDiagnostics.event("flow_initialize_stage", catalogPreparationStarted,
            TemporaryLifecycleDiagnostics.with(Map.of(
                "moduleId", "flow",
                "stageName", "catalog_prepare",
                "outcome", "complete",
                "sourceCount", authoredCatalogSources.size(),
                "definitionCount", startupDefinitionCount,
                "participantTimings", initializationTiming(catalogPreparationStarted, catalogPreparationCpuStarted))));
        initializationStageStarted = TemporaryLifecycleDiagnostics.start();
        initializationStageCpuStarted = currentThreadCpuNanos();
        graphValidationRegistry = new FlowGraphValidationRegistry();
        publishStructuredDiagnostics();
        FlowGraphValidator graphValidator = new FlowGraphValidator(nodeDefinitionRegistry, handlerRegistry, typeAdapterRegistry, optionCatalogRegistry,
            resourceRegistry, graphValidationRegistry, Clock.systemUTC(), this::reportGraphValidation, legacyRuntimeGate);
        storage.setGraphValidator(graphValidator);
        traceService = new FlowTraceService(500);
        debugService = new FlowDebugService(traceService);
        executor = new FlowExecutor(handlerRegistry, nodeDefinitionRegistry, typeAdapterRegistry, new HashMap<>(), legacyRuntimeGate);
        startupAdmissionFence = executor.fenceAdmissions();
        compiledFunctionBridge = new CompiledFunctionExecutionBridge(
            typedFunctionSourceProvider, typedFunctionCapabilityProvider);
        executor.configureCompiledFunctionBridge(compiledFunctionBridge);
        if (runtimePrincipalAuthority != null) {
            executor.configureCompiledFunctionRuntime(runtimeAuthority, runtimePrincipalAuthority,
                runtimeBindingRegistry.receiptStore(), serverId, runtimePrincipal, runtimeBindingRegistry.auditBoundary());
        }
        executor.setTraceService(traceService);
        executor.setDebugService(debugService);
        executor.setGraphValidator(graphValidator);
        executor.setExecutionAuthority(storage::isExecutionAuthorized);
        storage.setGraphChangeListener(this::handleCoreGraphChange);
        customContentService = new CustomContentService(customContentStorage, storage, executor, itemAttributeSchemaService);
        customBlocksPersistenceParticipant = new CustomBlocksPersistenceParticipant(dataRoot, customContentService.getVanillaProvider());
        context.getRequiredService(ReSyncPersistenceCoordinator.class).register(customBlocksPersistenceParticipant);
        context.registerService(CustomBlocksPersistenceParticipant.class, customBlocksPersistenceParticipant);
        runtimeDataRegistry.register(new CustomContentItemDataAdapter(customContentStorage, customContentService));
        runtimeDataRegistry.register(new ExternalItemDataAdapter(customContentService));
        jsonResourceValidator = new JsonRuntimeResourceValidator(customContentService, valueCodecs, legacyRuntimeGate);
        jsonResourceStorage.addInterceptor(jsonResourceValidator);
        customContentListener = new CustomContentListener(customContentStorage, customContentService);
        FlowResourcePacketRouter resourceBootstrap = new FlowResourcePacketRouter(storage, customContentStorage, customContentService, jsonResourceStorage, null, null, null, resourceRegistry, ignored -> {
        }, itemAttributeSchemaService, authorityEpoch, FlowMutationPayloadReader::legacyCompatible);
        resourceBootstrap.registerExternalLifecycle(worldGenStorage, worldManagementService);
        RuntimeFlowDispatcher runtimeFlowDispatcher = new RuntimeFlowDispatcher(storage, executor);
        lootTableService = new LootTableService(jsonResourceStorage, customContentService, runtimeFlowDispatcher, context.getPlugin());
        tradeProfileService = new TradeProfileService(jsonResourceStorage, customContentService, runtimeFlowDispatcher, context.getPlugin(), legacyRuntimeGate);
        TriggerRegistry triggerRegistry = new TriggerRegistry(dataRoot.resolve("triggers.json").toFile()) {
            @Override
            public ReSync getPlugin() {
                return context.getPlugin();
            }
        };
        globalTriggers = new GlobalTriggers(storage, executor, triggerRegistry, context.getRequiredService(ReTextService.class), false);
        networkFlowBridge = new NetworkFlowBridge(context.getPlugin());
        flowEventRegistry = new FlowEventRegistry(globalTriggers.getTriggerDispatcher(), typeAdapterRegistry);
        flowEventRegistry.registerFromJson(new ArrayList<>(startupDefinitions.values()));
        int channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        reportInitializationStage("execution_services", initializationStageStarted, initializationStageCpuStarted);
        long catalogRuntimeStarted = TemporaryLifecycleDiagnostics.start();
        long catalogRuntimeCpuStarted = currentThreadCpuNanos();
        delegate = new FlowModule(storage, context.getCodec(), channelId, triggerRegistry, globalTriggers, flowRegistry, nodeDefinitionRegistry, propertyRegistry, customContentStorage, customContentService,
            context.getService(ReSyncExtensionData.class), optionCatalogRegistry, jsonResourceStorage, context.getService(MessageLogService.class),
            context.getRequiredService(PlayerSessionLinkService.class), builtinOptionCatalogs, resourceRegistry, valueCodecs, flowJobs, runtimeBindingRegistry, handlerRegistry,
            replacementActivationAuthority, serverId, publicationReceiptStore, authoredCatalogSources, authorityEpoch,
            FlowMutationPayloadReader::legacyCompatible);
        TemporaryLifecycleDiagnostics.event("flow_initialize_stage", catalogRuntimeStarted,
            TemporaryLifecycleDiagnostics.with(Map.of(
                "moduleId", "flow",
                "stageName", "catalog_runtime",
                "outcome", "complete",
                "sourceCount", authoredCatalogSources.size(),
                "definitionCount", startupDefinitionCount,
                "bindingCount", runtimeBindingRegistry.snapshot().bindings().size(),
                "participantTimings", initializationTiming(catalogRuntimeStarted, catalogRuntimeCpuStarted))));
        initializationStageStarted = TemporaryLifecycleDiagnostics.start();
        initializationStageCpuStarted = currentThreadCpuNanos();
        delegate.setConversionAdapterRegistry(typeAdapterRegistry);
        delegate.setNodeRegistryDiagnosticsSupplier(this::nodeRegistryDiagnostics);
        delegate.setCustomFunctionDefinitionRefresh(this::requestCustomFunctionDefinitionRefresh);
        delegate.setTraceService(traceService);
        delegate.setDebugService(debugService);
        delegate.setExecutor(executor);
        delegate.registerWorkspaceDocumentProvider(new WorldWorkspaceDocumentProvider(worldManagementService));
        if (worldGenStorage != null) {
            worldGenStorage.setChangeListener(() -> delegate.broadcastOptionCatalog("server:resync:" + ReSyncResourceCatalog.WORLDGEN));
        }
        if (worldManagementService != null) {
            worldResourceListener = message -> delegate.broadcastOptionCatalog("server:resync:" + ReSyncResourceCatalog.WORLD);
            worldManagementService.addListener(worldResourceListener);
        }
        compiledMetadataProvider = new CompiledGraphMetadataProvider(
            delegate::activeCatalogRuntimeActivation,
            serverId,
            valueCodecs);
        customContentExecution = new CustomContentExecution(customContentStorage, coreGraphResourceAuthority,
            compiledMetadataProvider, serverId, assetTransactions);
        compiledPlanRepository = new ServerCompiledPlanRepository(
            customContentExecution, delegate::activeCatalogRuntimeActivation);
        compiledBridge = new CompiledCoreFlowExecutionBridge(
            delegate::activeCatalogRuntimeActivation,
            runtimeBindingRegistry,
            runtimeAuthority,
            runtimePrincipal,
            compiledPlanRepository);
        compiledPlanRepository.bindTemplateCompiler(compiledBridge::prepare);
        compiledPlanRepository.bindMetadataCompiler(compiledMetadataProvider::provide);
        executor.setCompiledExecutionAuthority((graph, metadata) -> metadata.resource().id().equals(graph.getId())
            && metadata.resource().resourceType().value().equals(graph.getResourceType())
            && compiledPlanRepository.isExecutionAuthorized(metadata, graph.getResourceRevision(), graph.getResourceHash()));
        executor.configureExecutionBridge(compiledBridge);
        compiledTriggerExecution = new CompiledTriggerExecution(
            executor,
            compiledMetadataProvider,
            compiledBridge,
            (reportId, diagnostics) -> {
                if (!diagnosticsPublicationReady) {
                    return null;
                }
                var stored = diagnosticReporter.reportDiagnostics(reportId, diagnostics);
                return stored == null ? null : stored.reportId();
            },
            runtimePrincipalAuthority,
            compiledPlanRepository);
        delegate.setCompiledTriggerExecution(compiledTriggerExecution);
        delegate.setRuntimePrincipalAuthority(runtimePrincipalAuthority);
        globalTriggers.setCompiledExecution(compiledTriggerExecution);
        runtimeFlowDispatcher.setCompiledExecution(compiledTriggerExecution);
        customContentExecution.bind(compiledPlanRepository, compiledTriggerExecution);
        customContentService.setCompiledExecution(customContentExecution);
        guiManager = new GuiManager(context.getServer(), storage, executor, delegate);
        context.registerService(FlowStorage.class, storage);
        context.registerService(CustomContentStorage.class, customContentStorage);
        context.registerService(CustomContentService.class, customContentService);
        context.registerService(ItemAttributeSchemaService.class, itemAttributeSchemaService);
        context.registerService(HandlerRegistry.class, handlerRegistry);
        context.registerService(PropertyRegistry.class, propertyRegistry);
        context.registerService(TypeRegistry.class, typeRegistry);
        context.registerService(TypeAdapterRegistry.class, typeAdapterRegistry);
        context.registerService(FlowRegistry.class, flowRegistry);
        context.registerService(NodeDefinitionRegistry.class, nodeDefinitionRegistry);
        context.registerService(TriggerRegistry.class, triggerRegistry);
        context.registerService(FlowEventRegistry.class, flowEventRegistry);
        context.registerService(FlowExecutor.class, executor);
        context.registerService(FlowTraceService.class, traceService);
        context.registerService(FlowDebugService.class, debugService);
        context.registerService(FlowGraphValidator.class, graphValidator);
        context.registerService(StructuredFlowDiagnosticReporter.class, diagnosticReporter);
        context.registerService(FlowGraphValidationRegistry.class, graphValidationRegistry);
        context.registerService(FlowResourceRegistry.class, resourceRegistry);
        context.registerService(FlowValueCodecRegistry.class, valueCodecs);
        context.registerService(ServerCompiledPlanRepository.class, compiledPlanRepository);
        context.registerService(CompiledPlanAuthority.class, compiledPlanRepository);
        context.registerService(CompiledGraphMetadataProvider.class, compiledMetadataProvider);
        context.registerService(CompiledCoreFlowExecutionBridge.class, compiledBridge);
        context.registerService(CompiledFunctionExecutionBridge.class, compiledFunctionBridge);
        context.registerService(AutomationDefinitionRegistry.class, automationDefinitions);
        context.registerService(AutomationTaskService.class, automationTasks);
        context.registerService(PersistentVariableStore.class, persistentVariables);
        context.registerService(VariableService.class, automationVariables);
        context.registerService(RuntimeDataRegistry.class, runtimeDataRegistry);
        context.registerService(ReplacementRuntimeProviderAuthority.class, replacementRuntimeProviderAuthority);
        context.registerService(NetworkFlowBridge.class, networkFlowBridge);
        context.registerService(FlowModule.class, delegate);
        context.registerService(LootTableService.class, lootTableService);
        context.registerService(TradeProfileService.class, tradeProfileService);
        context.registerService(GuiManager.class, guiManager);
        context.registerService(FlowRuntimeModule.class, this);
        FlowRuntimeAccess.configure(context.getPlugin(), () -> storage, () -> executor != null ? executor.getGlobalVariables() : null);
        FlowPacketSender editStateSender = new FlowPacketSender(context.getCodec(), channelId, Set.of(), flowJobs);
        editStateSender.setAuthorityEpoch(authorityEpoch);
        DialogService dialogService = new DialogService(
            context.getPlugin(),
            context.getRequiredService(ReSyncJsonResourceStorage.class),
            storage,
            executor,
            editStateSender::sendEditTargetState,
            context.getRequiredService(PlayerSessionLinkService.class),
            legacyRuntimeGate
        );
        context.registerService(DialogService.class, dialogService);
        npcService = new NpcService(context.getPlugin(), jsonResourceStorage, customContentService, runtimeFlowDispatcher, tradeProfileService, lootTableService, dialogService,
            context.getRequiredService(PlayerNpcRuntime.class), legacyRuntimeGate, dataRoot);
        context.registerService(NpcService.class, npcService);
        jsonResourceListener = (type, id, value, deleted) -> {
            AutomationCatalogProvider automationCatalog = automationCatalogProviders.get(type);
            if (automationCatalog != null) {
                context.getScheduler().execute(() -> refreshAutomationCatalog(automationCatalog));
            }
            if (ReSyncResourceCatalog.NPC_DEFINITION.equals(type)) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> npcService.reload(id, value, deleted));
                return;
            }
            if (ReSyncResourceCatalog.TRADE_PROFILE.equals(type)) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> tradeProfileService.reload(id, deleted));
            }
        };
        jsonResourceStorage.addListener(jsonResourceListener);
        ScoreboardRuntimeCapability scoreboardRuntimeCapability = ScoreboardRuntimeCapability.of(resourceRegistry);
        context.registerService(ScoreboardRuntimeCapability.class, scoreboardRuntimeCapability);
        ScoreboardTemplateManager.configureRuntimeCapability(scoreboardRuntimeCapability);
        ScoreboardTemplateManager.configureEditStateBridge(editStateSender::sendEditTargetState, context.getRequiredService(PlayerSessionLinkService.class));
        CustomContentAccess.configure(customContentStorage, customContentService);
        ReSyncRuntimeContentAccess.configure(lootTableService, tradeProfileService, npcService);
        reportInitializationStage("service_publish", initializationStageStarted, initializationStageCpuStarted);
        reportInitializationStage("initialize_total", initializationStarted, initializationCpuStarted);
    }

    public synchronized void bindCatalogSettlement(CatalogSettlement settlement) {
        if (catalogSettlement != null || startupActivationComplete || stopped || stopPending) {
            throw new IllegalStateException("Flow catalog settlement cannot be rebound");
        }
        catalogSettlement = Objects.requireNonNull(settlement, "Flow catalog settlement is required");
    }

    public interface CatalogSettlement {
        void settle(CatalogRuntimeActivation.ActivationRecord expected);
    }

    static void settleCatalog(CatalogRuntimeActivation.ActivationRecord expected,
                              Supplier<CatalogRuntimeActivation.ActivationRecord> current,
                              CatalogSettlement settlement) {
        if (settlement == null || expected == null || current.get() != expected) {
            throw new IllegalStateException("Final Flow catalog settlement authority is unavailable or changed");
        }
        settlement.settle(expected);
        if (current.get() != expected) {
            throw new IllegalStateException("Final Flow catalog changed during resource settlement");
        }
    }

    public synchronized void completeStartupActivation(PersistenceRootReadiness readiness) {
        ReSyncPersistenceCoordinator persistence = moduleContext.getRequiredService(ReSyncPersistenceCoordinator.class);
        if (readiness == null) {
            throw new IllegalStateException("Flow Startup Requires Sealed Restore-Ready Persistence");
        }
        ReSyncPersistenceCoordinator.ReadinessProof proof = persistence.validateRestoreReadiness(readiness)
            .orElseThrow(() -> new IllegalStateException("Flow Startup Requires Sealed Restore-Ready Persistence"));
        completeStartupActivation(proof);
    }

    public synchronized void completeStartupActivation(ReSyncPersistenceCoordinator.ReadinessProof proof) {
        if (startupActivationComplete) {
            return;
        }
        long startupStarted = TemporaryLifecycleDiagnostics.start();
        long customContentReady = startupStarted;
        long resourcesReady = startupStarted;
        long diagnosticsReady = startupStarted;
        long catalogReady = startupStarted;
        long delegateReady = startupStarted;
        long runtimeReady = startupStarted;
        boolean runtimeActivated = false;
        boolean runtimeActivationAttempted = false;
        try {
            ReSyncPersistenceCoordinator persistence = moduleContext.getRequiredService(ReSyncPersistenceCoordinator.class);
            PersistenceRootReadiness verified = proof == null ? PersistenceRootReadiness.empty() : proof.readiness();
            if (!verified.complete() || persistence.currentValidatedReadinessProof(verified)
                .filter(current -> current == proof).isEmpty()) {
                throw new IllegalStateException("Flow Startup Requires Sealed Restore-Ready Persistence");
            }
            if (delegate == null) {
                throw new IllegalStateException("Flow runtime delegate is not initialized");
            }
            diagnosticsPublicationReady = true;
            customContentStorage.preloadAll();
            customContentReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("flow_startup_stage", startupStarted,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "stageName", "customContent", "outcome", "complete")));
            storage.preloadAll();
            resourcesReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("flow_startup_stage", customContentReady,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "stageName", "resources", "outcome", "complete")));
            publishStructuredDiagnostics();
            diagnosticsReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("flow_startup_stage", resourcesReady,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "stageName", "diagnostics", "outcome", "complete")));
            nodeDefinitionReloadPending = true;
            if (!reloadNodeDefinitionsFenced()) {
                throw new IllegalStateException("Replacement catalog custom Function activation was rejected");
            }
            catalogReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("flow_startup_stage", diagnosticsReady,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "stageName", "catalog", "outcome", "complete")));
            var activation = delegate.activeCatalogRuntimeActivation();
            CatalogSnapshot active = activation.catalog();
            RuntimeBindingManifest manifest = activation.runtimeManifest();
            CatalogActivationAuthority.Decision decision = CatalogActivationAuthority.evaluate(
                active, manifest, verified, replacementActivationAuthority);
            if (!decision.allowed()) {
                throw new IllegalStateException(decision.code() + ": " + decision.detail());
            }
            if (startupActivationRecord == null && !freshInstall
                && (replacementActivationAuthority == null || !replacementActivationAuthority.requiresBindingContext())) {
                throw new IllegalStateException("Replacement activation record is required for an existing installation");
            }
            settleCatalog(activation, delegate::activeCatalogRuntimeActivation, catalogSettlement);
            customContentExecution.refreshAll();
            compiledPlanRepository.initialize();
            delegate.completeStartupActivation(verified);
            delegateReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("flow_startup_stage", catalogReady,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "stageName", "authority", "outcome", "complete")));
            ReplacementActivationRecord.Values expectedRecord = new ReplacementActivationRecord.Values(
                active.generation(), active.contentChecksum().canonicalText(), manifest.version(),
                manifest.bindingManifestHash().canonicalText(), verified.reportVersion(), verified.reportHash());
            ModuleContext startupContext = contextForStartupActivation();
            runtimeActivationAttempted = true;
            activateRuntime(startupContext);
            runtimeActivated = true;
            runtimeReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("flow_startup_stage", delegateReady,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "stageName", "runtime", "outcome", "complete")));
            if (!expectedRecord.equals(startupActivationRecord)) {
                persistStartupActivationRecord(expectedRecord);
            }
            if (startupAdmissionFence == null) {
                if (!runtimeAdmissionOpened) {
                    throw new IllegalStateException("Flow runtime startup admission fence is unavailable");
                }
            }
            startupActivationComplete = true;
            startupStaticCatalog = null;
            restoreDeferredStartupCallbacks();
            if (startupAdmissionFence != null) {
                startupAdmissionFence.close();
                startupAdmissionFence = null;
            }
            long startupReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("flow_startup", startupStarted,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "outcome", "complete",
                    "stageName", "restore", "moduleCount", 1, "nodeCount", nodeDefinitionDiagnostics.size(),
                    "runtimeBindingCount", runtimeBindingRegistry == null ? 0 : runtimeBindingRegistry.snapshot().bindings().size())));
        } catch (RuntimeException | Error failure) {
            startupActivationComplete = false;
            TemporaryLifecycleDiagnostics.terminal("flow_startup", startupStarted, Map.of("moduleId", "flow"),
                "failed", "FLOW.STARTUP_FAILED", failure.getClass().getSimpleName());
            if (!runtimeActivated && !runtimeActivationAttempted) {
                RuntimeException rollbackFailure = rollbackRuntimeActivation();
                if (rollbackFailure != null) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (delegate != null) {
                delegate.resetStartupActivation();
            }
            throw failure;
        }
    }

    private static long elapsedMillis(long started, long completed) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, completed - started));
    }

    public boolean startupActivationComplete() {
        return startupActivationComplete;
    }

    public synchronized ExtensionRegistryActivation registerExtensionRegistryActivation() {
        if (moduleContext == null || stopped || stopPending) {
            throw new IllegalStateException("Extension Registry Activation Requires An Initialized Flow Runtime");
        }
        NodeDefinitionRegistry nodeDefinitions = moduleContext.getRequiredService(NodeDefinitionRegistry.class);
        HandlerRegistry handlers = moduleContext.getRequiredService(HandlerRegistry.class);
        TypeAdapterRegistry typeAdapters = moduleContext.getRequiredService(TypeAdapterRegistry.class);
        FlowRegistry flowRegistry = moduleContext.getRequiredService(FlowRegistry.class);
        ReSyncExtensionData extensionData = moduleContext.getRequiredService(ReSyncExtensionData.class);
        ExtensionRegistryActivation.State expected = ExtensionRegistryActivation.capture(
            0, nodeDefinitions, handlers, propertyRegistry, optionCatalogRegistry, runtimeDataRegistry, valueCodecs,
            typeAdapters, graphValidationRegistry, resourceRegistry, extensionData, flowEventRegistry, flowRegistry);
        ExtensionRegistryActivation activation = moduleContext.getService(ExtensionRegistryActivation.class);
        if (activation == null) {
            activation = new ExtensionRegistryActivation(expected);
            moduleContext.registerService(ExtensionRegistryActivation.class, activation);
        } else if (!activation.snapshot().semanticallyEquals(expected)) {
            throw new IllegalStateException("Extension Registry Activation Was Published With A Different Flow Registry State");
        }
        activation.bind(nodeDefinitions, handlers, propertyRegistry, optionCatalogRegistry, runtimeDataRegistry, valueCodecs,
            typeAdapters, graphValidationRegistry, resourceRegistry, extensionData, flowEventRegistry, flowRegistry);
        return activation;
    }

    public synchronized CoreGraphResourceAuthority bindCoreGraphResourceAuthority() {
        if (moduleContext == null || stopped || stopPending) {
            throw new IllegalStateException("Core graph resource authority cannot be bound outside the Flow runtime lifecycle");
        }
        CoreGraphResourceAuthority existing = moduleContext.getService(CoreGraphResourceAuthority.class);
        if (existing != null) {
            if (existing == coreGraphResourceAuthority) {
                return existing;
            }
            throw new IllegalStateException("Core graph resource authority is already bound");
        }
        FlowStorage registeredStorage = moduleContext.getService(FlowStorage.class);
        ServerIdentityStore identityStore = moduleContext.getService(ServerIdentityStore.class);
        if (registeredStorage == null || identityStore == null || serverId == null) {
            throw new IllegalStateException("Core graph resource authority requires registered Flow storage and server identity");
        }
        ServerId registeredServerId = identityStore.serverId();
        if (registeredServerId == null || registeredStorage != storage || !registeredServerId.equals(serverId)) {
            throw new IllegalStateException("Registered Core graph authority dependencies do not match the Flow runtime");
        }
        CoreGraphResourceAuthority authority = coreGraphResourceAuthority;
        if (authority == null) {
            authority = createCoreGraphResourceAuthority(registeredStorage, registeredServerId);
        }
        if (!authority.available()) {
            throw new IllegalStateException(CoreGraphResourceAuthority.UNAVAILABLE_MESSAGE);
        }
        moduleContext.registerService(CoreGraphResourceAuthority.class, authority);
        coreGraphResourceAuthority = authority;
        return authority;
    }

    private CoreGraphResourceAuthority createCoreGraphResourceAuthority(FlowStorage registeredStorage,
                                                                          ServerId registeredServerId) {
        CoreGraphMutationValidator validator = new CoreGraphMutationValidator(registeredServerId,
            () -> delegate == null ? null : delegate.activeCatalogRuntimeActivation(),
            () -> replacementActivationAuthority,
            new CoreGraphMutationValidator.AdmissionExecutor() {
                @Override
                public <T> T execute(CatalogRuntimeActivation.ActivationRecord expected, Supplier<T> action) {
                    FlowModule activeDelegate = delegate;
                    if (activeDelegate == null) {
                        throw new IllegalStateException("Core graph admission fence is unavailable before Flow runtime startup");
                    }
                    return activeDelegate.executeCoreGraphAdmission(expected, action);
                }
            });
        return new FlowStorageCoreGraphResourceAuthority(registeredStorage, registeredServerId, validator,
            () -> optionCatalogRegistry);
    }

    private CatalogActivationAuthority.Decision replacementStartupDecision(boolean replacementDerived) {
        if (!replacementDerived) {
            return CatalogActivationAuthority.evaluateReplacement(false, replacementActivationAuthority);
        }
        if (replacementActivationAuthority == null || !replacementActivationAuthority.approved()) {
            return CatalogActivationAuthority.evaluateReplacement(true, replacementActivationAuthority);
        }
        return new CatalogActivationAuthority.Decision(true, "CATALOG.STARTUP_BINDING_PENDING",
            "Replacement catalog startup will be checked against the compiled runtime and live persistence readiness");
    }

    private ReplacementActivationRecord.Values loadStartupActivationRecord() {
        Path record = ReplacementActivationRecord.path(dataRoot);
        if (!Files.exists(record)) {
            if (!freshInstall && (replacementActivationAuthority == null || !replacementActivationAuthority.requiresBindingContext())) {
                throw new IllegalStateException("Replacement activation record is missing from an existing installation");
            }
            return null;
        }
        try {
            return ReplacementActivationRecord.read(dataRoot);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Replacement activation record could not be loaded", exception);
        }
    }

    private void persistStartupActivationRecord(ReplacementActivationRecord.Values record) {
        try {
            ReplacementActivationRecord.write(dataRoot, record);
            startupActivationRecord = ReplacementActivationRecord.read(dataRoot);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Replacement activation record could not be persisted", exception);
        }
    }

    private ModuleContext contextForStartupActivation() {
        if (moduleContext == null) {
            throw new IllegalStateException("Flow runtime module context is unavailable");
        }
        return moduleContext;
    }

    private void activateRuntime(ModuleContext context) {
        if (runtimeAdmissionOpened) {
            return;
        }
        try {
            if (systemEventListener == null) {
                TriggerRegistry triggerRegistry = context.getRequiredService(TriggerRegistry.class);
                systemEventListener = new SystemEventListener(storage, executor, triggerRegistry);
            }
            systemEventListener.setCompiledExecution(compiledTriggerExecution);
            globalTriggers.setSystemEventListener(systemEventListener);
            delegate.refreshRuntimeGraphBindings();
            globalTriggers.activateRuntimeBindings();
            Bukkit.getPluginManager().registerEvents(globalTriggers, context.getPlugin());
            Bukkit.getPluginManager().registerEvents(systemEventListener, context.getPlugin());
            scoreboardRuntimeListener = new ScoreboardRuntimeListener();
            Bukkit.getPluginManager().registerEvents(scoreboardRuntimeListener, context.getPlugin());
            Bukkit.getPluginManager().registerEvents(guiManager, context.getPlugin());
            Bukkit.getPluginManager().registerEvents(customContentListener, context.getPlugin());
            Bukkit.getPluginManager().registerEvents(lootTableService, context.getPlugin());
            Bukkit.getPluginManager().registerEvents(tradeProfileService, context.getPlugin());
            Bukkit.getPluginManager().registerEvents(npcService, context.getPlugin());
            if (customContentService != null) {
                customContentService.reconcileAllItems();
            }
            TabListService.startUpdater();
            tickTask = Bukkit.getScheduler().runTaskTimer(context.getPlugin(), () -> {
                systemEventListener.tick();
                CustomEventManager.getInstance().tick();
                if (customContentService != null) {
                    customContentService.tick();
                }
                if (customContentListener != null) {
                    customContentListener.tick();
                }
            }, 1L, 1L);
            runtimeAdmissionOpened = true;
            startupAutomationRestorePending = true;
            startupNpcRestorePending = true;
        } catch (RuntimeException | Error failure) {
            RuntimeException rollbackFailure = rollbackRuntimeActivation();
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private RuntimeException rollbackRuntimeActivation() {
        RuntimeException failure = null;
        failure = cleanupFailure(failure, () -> {
            if (resourceRegistry != null) {
                resourceRegistry.cancelPendingLiveRefreshes();
            }
        });
        BukkitTask task = tickTask;
        tickTask = null;
        failure = cleanupFailure(failure, () -> {
            if (task != null) {
                task.cancel();
            }
        });
        failure = cleanupFailure(failure, TabListService::stopUpdater);
        failure = cleanupFailure(failure, () -> {
            if (globalTriggers != null) {
                globalTriggers.shutdownRuntimeCommands();
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (globalTriggers != null) {
                HandlerList.unregisterAll(globalTriggers);
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (globalTriggers != null && globalTriggers.getTriggerDispatcher() != null) {
                globalTriggers.getTriggerDispatcher().shutdown();
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (systemEventListener != null) {
                HandlerList.unregisterAll(systemEventListener);
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (scoreboardRuntimeListener != null) {
                HandlerList.unregisterAll(scoreboardRuntimeListener);
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (guiManager != null) {
                HandlerList.unregisterAll(guiManager);
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (customContentListener != null) {
                HandlerList.unregisterAll(customContentListener);
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (lootTableService != null) {
                lootTableService.shutdown();
                HandlerList.unregisterAll(lootTableService);
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (tradeProfileService != null) {
                HandlerList.unregisterAll(tradeProfileService);
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (npcService != null) {
                HandlerList.unregisterAll(npcService);
            }
        });
        runtimeAdmissionOpened = false;
        startupAutomationRestorePending = false;
        startupNpcRestorePending = false;
        return failure;
    }

    private void restoreDeferredStartupCallbacks() {
        if (startupAutomationRestorePending) {
            automationTasks.restorePersistentTimers();
            scheduleHandler.restorePersistentSchedules(executor);
            startupAutomationRestorePending = false;
        }
        if (startupNpcRestorePending) {
            npcService.restorePersistentNpcs();
            startupNpcRestorePending = false;
        }
    }

    public CompletableFuture<Void> executeCompiled(FlowGraph graph, String startNodeId, Player player, Event event,
                                                   Map<String, Object> eventVars,
                                                   FlowExecutionBridge.MappingContext mappingContext,
                                                   CompiledGraphMetadata compiledGraphMetadata,
                                                   FlowExecutor.CompiledExecutionAuthority authority,
                                                   CompiledCoreFlowExecutionBridge bridge) {
        FlowExecutor runtimeExecutor = executor;
        if (runtimeExecutor == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Flow runtime executor is not initialized"));
        }
        return runtimeExecutor.executeCompiled(graph, startNodeId, player, event, eventVars, mappingContext,
            compiledGraphMetadata, authority, bridge);
    }

    public CompletableFuture<Void> executeCompiled(FlowGraph graph, String startNodeId,
                                                   CompiledRuntimeContext runtimeContext,
                                                   FlowExecutionBridge.MappingContext mappingContext,
                                                   CompiledGraphMetadata compiledGraphMetadata,
                                                   FlowExecutor.CompiledExecutionAuthority authority,
                                                   CompiledCoreFlowExecutionBridge bridge) {
        FlowExecutor runtimeExecutor = executor;
        if (runtimeExecutor == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Flow runtime executor is not initialized"));
        }
        return runtimeExecutor.executeCompiled(graph, startNodeId, runtimeContext, mappingContext, compiledGraphMetadata,
            authority, bridge);
    }

    public CompletableFuture<Void> executeCompiled(FlowGraph graph, String startNodeId, Player player, Event event,
                                                   Map<String, Object> eventVars) {
        FlowExecutor runtimeExecutor = executor;
        CompiledGraphMetadataProvider provider = compiledMetadataProvider;
        CompiledCoreFlowExecutionBridge bridge = compiledBridge;
        if (runtimeExecutor == null || provider == null || bridge == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Compiled flow runtime is not initialized"));
        }
        CompiledGraphMetadataProvider.Result result = provider.provide(graph);
        if (!result.accepted()) {
            return CompletableFuture.failedFuture(result.failure());
        }
        CompiledGraphMetadata metadata = result.metadata();
        return runtimeExecutor.executeCompiled(graph, startNodeId, player, event, eventVars, result.mappingContext(), metadata,
            new FlowExecutor.CompiledExecutionAuthority(
                CompiledCoreFlowExecutionBridge.authorityHash(),
                metadata.catalogBinding().catalogChecksum(),
                metadata.catalogBinding().bindingManifestHash()),
            bridge);
    }

    public CompletableFuture<Void> executeCompiled(FlowGraph graph, String startNodeId,
                                                   CompiledRuntimeContext runtimeContext) {
        FlowExecutor runtimeExecutor = executor;
        CompiledGraphMetadataProvider provider = compiledMetadataProvider;
        CompiledCoreFlowExecutionBridge bridge = compiledBridge;
        if (runtimeExecutor == null || provider == null || bridge == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Compiled flow runtime is not initialized"));
        }
        if (runtimeContext == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A typed compiled runtime context is required"));
        }
        CompiledGraphMetadataProvider.Result result = provider.provide(graph);
        if (!result.accepted()) {
            return CompletableFuture.failedFuture(result.failure());
        }
        CompiledGraphMetadata metadata = result.metadata();
        return runtimeExecutor.executeCompiled(graph, startNodeId, runtimeContext, result.mappingContext(), metadata,
            new FlowExecutor.CompiledExecutionAuthority(
                CompiledCoreFlowExecutionBridge.authorityHash(),
                metadata.catalogBinding().catalogChecksum(),
                metadata.catalogBinding().bindingManifestHash()),
            bridge);
    }

    public void reloadNodeDefinitions() {
        if (stopped || stopPending || fatalActivationFault != null) {
            nodeDefinitionReloadPending = false;
            nodeDefinitionReloadRequested.set(false);
            return;
        }
        if (!beginNodeDefinitionReload()) {
            return;
        }
        FlowExecutor runtimeExecutor = executor;
        if (runtimeExecutor == null) {
            try {
                reloadNodeDefinitionsFenced();
            } finally {
                completeNodeDefinitionReload();
            }
            return;
        }
        FlowExecutor.AdmissionFence admissionFence = runtimeExecutor.fenceAdmissions();
        runtimeExecutor.cancelPendingTasks();
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread() && !admissionFence.isDrained()) {
            deferReloadNodeDefinitions(runtimeExecutor, admissionFence);
            return;
        }
        try {
            if (!admissionFence.awaitDrained(STOP_DRAIN_TIMEOUT)) {
                throw new IllegalStateException("Flow runtime reload could not drain active executions before timeout");
            }
            reloadNodeDefinitionsFenced();
        } finally {
            admissionFence.close();
            completeNodeDefinitionReload();
        }
    }

    boolean beginNodeDefinitionReload() {
        nodeDefinitionReloadRequested.set(true);
        if (!nodeDefinitionReloadInFlight.compareAndSet(false, true)) {
            return false;
        }
        nodeDefinitionReloadRequested.set(false);
        nodeDefinitionReloadPending = false;
        return true;
    }

    void completeNodeDefinitionReload() {
        nodeDefinitionReloadInFlight.set(false);
        if (nodeDefinitionReloadRequested.get()) {
            nodeDefinitionReloadPending = true;
        }
    }

    private boolean requestCustomFunctionDefinitionRefresh() {
        if (stopped || stopPending || fatalActivationFault != null) {
            return false;
        }
        customFunctionDefinitionRefreshPending.set(true);
        return true;
    }

    public boolean retryPendingCatalogRuntimeCleanup() {
        return delegate != null && delegate.retryPendingCatalogRuntimeCleanup();
    }

    private void deferReloadNodeDefinitions(FlowExecutor runtimeExecutor, FlowExecutor.AdmissionFence admissionFence) {
        admissionFence.whenDrained().whenComplete((ignored, failure) -> {
            if (failure != null) {
                Log.error("Flow runtime reload could not drain active executions: " + failure.getMessage(), failure);
                admissionFence.close();
                completeNodeDefinitionReload();
                return;
            }
            Runnable reload = () -> {
                if (executor != runtimeExecutor) {
                    nodeDefinitionReloadPending = false;
                    admissionFence.close();
                    completeNodeDefinitionReload();
                    return;
                }
                try {
                    reloadNodeDefinitionsFenced();
                } catch (RuntimeException exception) {
                    Log.error("Flow runtime node definition reload failed: " + exception.getMessage(), exception);
                } finally {
                    admissionFence.close();
                    completeNodeDefinitionReload();
                }
            };
            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
                reload.run();
                return;
            }
            try {
                Bukkit.getScheduler().runTask(moduleContext.getPlugin(), reload);
            } catch (RuntimeException exception) {
                Log.error("Flow runtime node definition reload could not return to the primary thread: " + exception.getMessage(), exception);
                admissionFence.close();
                completeNodeDefinitionReload();
            }
        });
    }

    private boolean reloadNodeDefinitionsFenced() {
        if (fatalActivationFault != null) {
            nodeDefinitionReloadPending = false;
            return false;
        }
        HandlerRegistry handlerRegistry = moduleContext.getRequiredService(HandlerRegistry.class);
        NodeDefinitionRegistry nodeDefinitionRegistry = moduleContext.getRequiredService(NodeDefinitionRegistry.class);
        NodeDefinitionLoader jsonLoader = new NodeDefinitionLoader();
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlerRegistry, optionCatalogRegistry, true);
        jsonLoader.setValidator(validator);
        Path nodesDir = persistenceDataRoot.resolve("nodes");
        WorldGenFlowCatalogContribution worldGenCatalog = moduleContext.getRequiredService(WorldGenFlowCatalogContribution.class);
        StaticCatalogReuse staticReuse = resolveStartupStaticCatalog(nodesDir, handlerRegistry, worldGenCatalog);
        NodeDefinitionRegistry stagedRegistry;
        List<CatalogSourceIngestor.CatalogSource> authoredCatalogSources;
        if (staticReuse != null) {
            stagedRegistry = staticReuse.registry().copy();
            authoredCatalogSources = staticReuse.authoredCatalogSources();
        } else {
            ReplacementCatalogSource.Snapshot replacementSource = ReplacementCatalogSource.load(jsonLoader, nodesDir);
            List<NodeDefinition> classpathDefs = new ArrayList<>(replacementSource.classpathDefinitions());
            removeUnavailable(classpathDefs, jsonLoader);
            List<NodeDefinition> localDefs = new ArrayList<>(replacementSource.localDefinitions());
            removeUnavailable(localDefs, jsonLoader);
            stagedRegistry = new NodeDefinitionRegistry(false);
            HandlerRegistry worldGenHandlers = handlerRegistry.copy();
            worldGenCatalog.apply(stagedRegistry, worldGenHandlers);
            jsonLoader.validateAndRegister(classpathDefs, stagedRegistry, handlerRegistry, "json-classpath");
            if (!localDefs.isEmpty()) {
                jsonLoader.validateAndRegister(localDefs, stagedRegistry, handlerRegistry, "json");
            }
            authoredCatalogSources = loadAuthoredCatalogSources(replacementSource.sourceInventory(), replacementSource.definitions());
        }
        if (stagedRegistry.getAllDefinitions().isEmpty() || hasDefinitionErrors(jsonLoader)) {
            String message = stagedRegistry.getAllDefinitions().isEmpty()
                ? "Replacement Flow catalog is unavailable; the previous active definitions were retained"
                : "Replacement Flow catalog contains invalid definitions; the previous active definitions were retained";
            jsonLoader.recordFailure("CATALOG.CONTRIBUTION_REJECTED", "runtime://flow/catalog", message);
            nodeDefinitionDiagnostics = catalogDiagnostics(staticReuse, jsonLoader);
            publishStructuredDiagnostics();
            nodeDefinitionReloadPending = false;
            return false;
        }
        CatalogActivationAuthority.Decision activationDecision = CatalogActivationAuthority.evaluateReplacement(
            !stagedRegistry.getAllDefinitions().isEmpty(), replacementActivationAuthority);
        if (!activationDecision.allowed()) {
            jsonLoader.recordFailure("CATALOG.CONTRIBUTION_REJECTED", "runtime://flow/catalog", activationDecision.detail());
            nodeDefinitionDiagnostics = catalogDiagnostics(staticReuse, jsonLoader);
            publishStructuredDiagnostics();
            nodeDefinitionReloadPending = false;
            return false;
        }
        NodeDefinitionRegistry previousDefinitions = nodeDefinitionRegistry.copy();
        NodeDefinitionRegistry completeStagedRegistry = new NodeDefinitionRegistry(false);
        for (String pluginId : stagedRegistry.getPluginIds()) {
            completeStagedRegistry.registerAll(pluginId, stagedRegistry.getDefinitionsForPlugin(pluginId));
        }
        Set<String> stagedIdentities = new TreeSet<>();
        completeStagedRegistry.getAllDefinitions().values().forEach(definition ->
            stagedIdentities.add(nodeDefinitionIdentity(definition)));
        Set<String> rebuiltWorldgenIdentities = new TreeSet<>();
        for (NodeDefinition definition : completeStagedRegistry.getDefinitionsForPlugin("worldgen")) {
            rebuiltWorldgenIdentities.add(nodeDefinitionIdentity(definition));
        }
        for (String pluginId : nodeDefinitionRegistry.getPluginIds()) {
            if (isReloadOwnedDefinitionPlugin(pluginId)
                || CustomFunctionNodeDefinitions.PLUGIN_ID.equals(pluginId)) {
                continue;
            }
            for (NodeDefinition definition : nodeDefinitionRegistry.getDefinitionsForPlugin(pluginId)) {
                String identity = nodeDefinitionIdentity(definition);
                if ("worldgen".equals(pluginId) && rebuiltWorldgenIdentities.contains(identity)) {
                    continue;
                }
                if (stagedIdentities.add(identity)) {
                    completeStagedRegistry.register(pluginId, definition);
                }
            }
        }
        CustomFunctionNodeDefinitions.rebuild(completeStagedRegistry, storage);
        boolean startupCatalogReuseEligible = staticReuse != null;
        ReSyncExtensionManager extensionManager = moduleContext.getService(ReSyncExtensionManager.class);
        ExtensionRegistryActivation projectionActivation = moduleContext.getService(ExtensionRegistryActivation.class);
        ExtensionRegistryActivation.State previousProjection = projectionActivation != null
            ? projectionActivation.snapshot() : null;
        FlowModule.CatalogRuntimeTransaction preparedCore = null;
        FlowModule.CoreFirstActivationTransaction activationTransaction = null;
        try {
            if (extensionManager != null && projectionActivation == null) {
                throw new IllegalStateException("ReSync extension compatibility projection-only seam is unavailable: "
                    + "ExtensionRegistryActivation is not registered");
            }
            ReSyncExtensionData stagedExtensionDataValue = moduleContext.getService(ReSyncExtensionData.class);
            ReSyncExtensionData stagedExtensionData = stagedExtensionDataValue != null
                ? stagedExtensionDataValue : new ReSyncExtensionData();
            preparedCore = delegate.prepareCatalogRuntimeTransaction(completeStagedRegistry, handlerRegistry,
                stagedExtensionData, Set.of(), authoredCatalogSources, startupCatalogReuseEligible);
            Runnable projectionCommit = () -> {
                    if (projectionActivation != null) {
                        projectionActivation.publishDefinitionProjection(previousProjection, completeStagedRegistry);
                    } else {
                        nodeDefinitionRegistry.replaceFrom(completeStagedRegistry);
                        propertyRegistry.replaceNodeDefinitions(nodeDefinitionRegistry.getAllDefinitions().values());
                        if (flowEventRegistry != null) {
                            flowEventRegistry.replaceDefinitions(new ArrayList<>(nodeDefinitionRegistry.getAllDefinitions().values()));
                        }
                    }
                };
            Runnable projectionRollback = () -> {
                    if (projectionActivation == null) {
                        nodeDefinitionRegistry.replaceFrom(previousDefinitions);
                        propertyRegistry.replaceNodeDefinitions(nodeDefinitionRegistry.getAllDefinitions().values());
                        if (flowEventRegistry != null) {
                            flowEventRegistry.replaceDefinitions(new ArrayList<>(nodeDefinitionRegistry.getAllDefinitions().values()));
                        }
                    }
                };
            activationTransaction = startupActivationComplete && !preparedCore.noop()
                ? new FlowModule.CoreFirstActivationTransaction(
                    preparedCore::commitCore,
                    preparedCore::rollback,
                    this::settleReloadedCatalog,
                    projectionCommit,
                    projectionRollback,
                    preparedCore::finalizePublication)
                : new FlowModule.CoreFirstActivationTransaction(
                    preparedCore::commitCore,
                    preparedCore::rollback,
                    projectionCommit,
                    projectionRollback,
                    preparedCore::finalizePublication);
            if (!activationTransaction.commit()) {
                nodeDefinitionReloadPending = preparedCore.retryable();
                if (!nodeDefinitionReloadPending) {
                    Log.warn("Flow runtime node definition reload was rejected because "
                        + "FlowModule.CatalogRuntimeTransaction rejected the prepared catalog without a retryable BLOCKED/STALE outcome");
                }
                return false;
            }
            nodeDefinitionDiagnostics = catalogDiagnostics(staticReuse, jsonLoader);
            publishStructuredDiagnostics();
            nodeDefinitionReloadPending = false;
            return true;
        } catch (ExtensionRegistryActivation.StaleProjectionException exception) {
            nodeDefinitionReloadPending = activationTransaction != null && activationTransaction.compensated();
            if (activationTransaction != null && activationTransaction.compensationFailed()) {
                retainFatalActivationFault(exception);
            }
            nodeDefinitionDiagnostics = catalogDiagnostics(staticReuse, jsonLoader);
            publishStructuredDiagnostics();
            Log.warn(nodeDefinitionReloadPending
                ? "Flow node definition compatibility projection became stale and will retry"
                : "Flow node definition compatibility projection became stale but Core compensation was not completed");
            return false;
        } catch (RuntimeException exception) {
            nodeDefinitionReloadPending = false;
            if (activationTransaction != null && activationTransaction.compensationFailed()) {
                retainFatalActivationFault(exception);
            }
            nodeDefinitionDiagnostics = catalogDiagnostics(staticReuse, jsonLoader);
            publishStructuredDiagnostics();
            Log.warn("Flow node definition reload was rejected; retaining the previous active definitions: " + exception.getMessage());
            return false;
        } catch (Error failure) {
            if (activationTransaction != null && activationTransaction.compensationFailed()) {
                retainFatalActivationFault(failure);
            }
            throw failure;
        } finally {
            if (preparedCore != null) {
                preparedCore.close();
            }
        }
    }

    private void settleReloadedCatalog() {
        if (!startupActivationComplete) {
            throw new IllegalStateException("Post-start Flow catalog settlement requires completed startup activation");
        }
        CatalogRuntimeActivation.ActivationRecord activation = delegate.activeCatalogRuntimeActivation();
        settleCatalog(activation, delegate::activeCatalogRuntimeActivation, catalogSettlement);
        if (customContentExecution != null) {
            customContentExecution.refreshCatalog();
        }
        ReplacementActivationRecord.Values previous = startupActivationRecord;
        if (previous == null) {
            throw new IllegalStateException("Post-start Flow catalog settlement requires an active replacement record");
        }
        CatalogSnapshot catalog = activation.catalog();
        RuntimeBindingManifest manifest = activation.runtimeManifest();
        persistStartupActivationRecord(new ReplacementActivationRecord.Values(
            catalog.generation(), catalog.contentChecksum().canonicalText(), manifest.version(),
            manifest.bindingManifestHash().canonicalText(), previous.participantReadinessVersion(),
            previous.participantReadinessHash()));
    }

    private synchronized void retainFatalActivationFault(Throwable failure) {
        if (fatalActivationFault != null) {
            return;
        }
        fatalActivationFault = Objects.requireNonNull(failure, "Fatal activation failure is required");
        if (executor != null) {
            fatalActivationFence = executor.fenceAdmissions();
        }
        nodeDefinitionReloadPending = false;
        Log.error("Flow catalog activation compensation failed; runtime remains fenced: "
            + failure.getMessage(), failure);
    }

    private void reportGraphValidation(FlowGraph graph, FlowGraphValidationResult result) {
        if (!diagnosticsPublicationReady || diagnosticReporter == null) {
            return;
        }
        try {
            diagnosticReporter.reportGraphValidation(graph, result);
        } catch (RuntimeException exception) {
            Log.error("Structured flow graph diagnostics could not be durably persisted: " + exception.getMessage(), exception);
        }
    }

    private void publishStructuredDiagnostics() {
        if (!diagnosticsPublicationReady || diagnosticReporter == null) {
            return;
        }
        try {
            diagnosticReporter.reportNodeDefinitionDiagnostics(nodeDefinitionDiagnostics);
            if (optionCatalogRegistry != null) {
                diagnosticReporter.reportCatalogDiagnostics(optionCatalogRegistry.diagnostics());
            }
        } catch (RuntimeException exception) {
            Log.error("Structured flow node definition diagnostics could not be durably persisted: "
                + exception.getMessage(), exception);
        }
    }

    private void requireReplacementCatalog(NodeDefinitionLoader loader, NodeDefinitionRegistry registry) {
        if (registry != null && !registry.getAllDefinitions().isEmpty() && !hasDefinitionErrors(loader)) {
            return;
        }
        String message = registry == null || registry.getAllDefinitions().isEmpty()
            ? "Replacement Flow catalog is unavailable"
            : "Replacement Flow catalog contains invalid definitions";
        loader.recordFailure("CATALOG.CONTRIBUTION_REJECTED", "runtime://flow/catalog", message);
        nodeDefinitionDiagnostics = loader.getDiagnostics();
        publishStructuredDiagnostics();
        throw new IllegalStateException("CATALOG.CONTRIBUTION_REJECTED: " + message);
    }

    private StaticCatalogReuse resolveStartupStaticCatalog(Path nodesDir, HandlerRegistry handlers,
                                                            WorldGenFlowCatalogContribution worldGenCatalog) {
        StaticCatalogReuse cached = startupStaticCatalog;
        if (startupActivationComplete || cached == null) {
            return null;
        }
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ReplacementCatalogSource.class.getClassLoader();
        }
        ReplacementCatalogSource.SourceInventory currentInventory = ReplacementCatalogSource.inventory(classLoader, nodesDir);
        return cached.matches(currentInventory, handlers, optionCatalogRegistry, worldGenCatalog) ? cached : null;
    }

    private static List<NodeDefinitionDiagnostic> catalogDiagnostics(StaticCatalogReuse staticReuse,
                                                                      NodeDefinitionLoader loader) {
        List<NodeDefinitionDiagnostic> current = loader.getDiagnostics();
        if (staticReuse == null || staticReuse.diagnostics().isEmpty()) {
            return current;
        }
        if (current.isEmpty()) {
            return staticReuse.diagnostics();
        }
        List<NodeDefinitionDiagnostic> combined = new ArrayList<>(staticReuse.diagnostics().size() + current.size());
        combined.addAll(staticReuse.diagnostics());
        combined.addAll(current);
        return List.copyOf(combined);
    }

    static boolean sameCatalogSourceInventory(ReplacementCatalogSource.SourceInventory first,
                                              ReplacementCatalogSource.SourceInventory second) {
        return first != null && second != null
            && first.localRoot().equals(second.localRoot())
            && sameCatalogSourceFiles(first.classpathFiles(), second.classpathFiles())
            && sameCatalogSourceFiles(first.replacementClasspathFiles(), second.replacementClasspathFiles())
            && sameCatalogSourceFiles(first.localFiles(), second.localFiles());
    }

    private static boolean sameCatalogSourceFiles(List<NodeDefinitionLoader.SourceFile> first,
                                                  List<NodeDefinitionLoader.SourceFile> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            NodeDefinitionLoader.SourceFile left = first.get(index);
            NodeDefinitionLoader.SourceFile right = second.get(index);
            if (!Objects.equals(left.sourceName(), right.sourceName())
                || !Objects.equals(left.sourceUri(), right.sourceUri())
                || !Objects.equals(left.relativePath(), right.relativePath())
                || left.origin() != right.origin()
                || !Arrays.equals(left.bytes(), right.bytes())) {
                return false;
            }
        }
        return true;
    }

    private static ReplacementCatalogSource.SourceInventory copyCatalogSourceInventory(
        ReplacementCatalogSource.SourceInventory source) {
        return new ReplacementCatalogSource.SourceInventory(
            copyCatalogSourceFiles(source.classpathFiles()),
            copyCatalogSourceFiles(source.replacementClasspathFiles()),
            copyCatalogSourceFiles(source.localFiles()),
            source.localRoot());
    }

    private static List<NodeDefinitionLoader.SourceFile> copyCatalogSourceFiles(
        List<NodeDefinitionLoader.SourceFile> sources) {
        return sources.stream().map(source -> new NodeDefinitionLoader.SourceFile(
            source.sourceName(), source.sourceUri(), source.relativePath(), source.origin(),
            Arrays.copyOf(source.bytes(), source.bytes().length))).toList();
    }

    private List<CatalogSourceIngestor.CatalogSource> loadAuthoredCatalogSources(
        ReplacementCatalogSource.SourceInventory sourceInventory,
        List<NodeDefinition> definitions) {
        Map<String, CatalogSourceIngestor.CatalogSource> sources = new TreeMap<>();
        if (sourceInventory == null) {
            return List.of();
        }
        Map<String, AuthoredCatalogSourceProof> proofs = authoredCatalogSourceProofs(definitions);
        for (NodeDefinitionLoader.SourceFile source : sourceInventory.authoredFiles()) {
            CatalogProvenance.SourceKind kind = source.origin() == NodeDefinitionLoader.SourceOrigin.LOCAL
                ? CatalogProvenance.SourceKind.LOCAL : CatalogProvenance.SourceKind.BUNDLED;
            AuthoredCatalogSourceProof proof = proofs.get(source.sourceUri());
            if (proof == null) {
                throw new IllegalArgumentException("Authored Flow catalog source has no parsed provenance proof: " + source.sourceUri());
            }
            addAuthoredCatalogSource(source.bytes(), kind, source.sourceUri(), source.relativePath(), proof, sources);
        }
        return List.copyOf(sources.values());
    }

    private static Map<String, AuthoredCatalogSourceProof> authoredCatalogSourceProofs(List<NodeDefinition> definitions) {
        Map<String, AuthoredCatalogSourceProof> proofs = new HashMap<>();
        for (NodeDefinition definition : definitions) {
            if (definition.getAuthoredMetadata() == null || definition.getAuthoredMetadata().sourceProvenance() == null) {
                continue;
            }
            AuthoredSourceProvenance provenance = definition.getAuthoredMetadata().sourceProvenance();
            AuthoredCatalogSourceProof proof = new AuthoredCatalogSourceProof(
                OwnerId.of(provenance.owner()), ContentHash.parseCanonicalText(provenance.sourceHash()));
            AuthoredCatalogSourceProof previous = proofs.putIfAbsent(provenance.sourceUri(), proof);
            if (previous != null && !previous.equals(proof)) {
                throw new IllegalArgumentException("Authored Flow catalog source provenance is inconsistent: "
                    + provenance.sourceUri());
            }
        }
        return Map.copyOf(proofs);
    }

    private record AuthoredCatalogSourceProof(OwnerId owner, ContentHash sourceHash) {
        private AuthoredCatalogSourceProof {
            owner = Objects.requireNonNull(owner, "Authored catalog source owner proof is required");
            sourceHash = Objects.requireNonNull(sourceHash, "Authored catalog source hash proof is required");
        }
    }

    private record StaticCatalogReuse(NodeDefinitionRegistry registry,
                                      ReplacementCatalogSource.SourceInventory sourceInventory,
                                      List<CatalogSourceIngestor.CatalogSource> authoredCatalogSources,
                                      List<NodeDefinitionDiagnostic> diagnostics,
                                      HandlerCatalogIdentity handlerIdentity,
                                      OptionCatalogIdentity optionCatalogIdentity,
                                      PluginCatalogIdentity pluginIdentity,
                                      WorldGenCatalogIdentity worldGenIdentity) {
        private StaticCatalogReuse {
            registry = Objects.requireNonNull(registry, "Static catalog registry is required").copy();
            sourceInventory = copyCatalogSourceInventory(Objects.requireNonNull(sourceInventory,
                "Static catalog source inventory is required"));
            authoredCatalogSources = List.copyOf(Objects.requireNonNull(authoredCatalogSources,
                "Static authored catalog sources are required"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "Static catalog diagnostics are required"));
            handlerIdentity = Objects.requireNonNull(handlerIdentity, "Static catalog handler identity is required");
            optionCatalogIdentity = Objects.requireNonNull(optionCatalogIdentity, "Static option catalog identity is required");
            pluginIdentity = Objects.requireNonNull(pluginIdentity, "Static plugin identity is required");
            worldGenIdentity = Objects.requireNonNull(worldGenIdentity, "Static WorldGen catalog identity is required");
        }

        private static StaticCatalogReuse capture(NodeDefinitionRegistry registry,
                                                  ReplacementCatalogSource.SourceInventory sourceInventory,
                                                  List<CatalogSourceIngestor.CatalogSource> authoredCatalogSources,
                                                  List<NodeDefinitionDiagnostic> diagnostics,
                                                  HandlerRegistry handlers,
                                                  OptionCatalogRegistry optionCatalogs,
                                                  WorldGenFlowCatalogContribution worldGenCatalog) {
            return new StaticCatalogReuse(registry, sourceInventory, authoredCatalogSources, diagnostics,
                HandlerCatalogIdentity.capture(handlers), OptionCatalogIdentity.capture(optionCatalogs),
                PluginCatalogIdentity.capture(), WorldGenCatalogIdentity.capture(worldGenCatalog));
        }

        private boolean matches(ReplacementCatalogSource.SourceInventory currentInventory,
                                HandlerRegistry handlers,
                                OptionCatalogRegistry optionCatalogs,
                                WorldGenFlowCatalogContribution worldGenCatalog) {
            return sameCatalogSourceInventory(sourceInventory, currentInventory)
                && handlerIdentity.matches(handlers)
                && optionCatalogIdentity.matches(optionCatalogs)
                && pluginIdentity.matches()
                && worldGenIdentity.matches(worldGenCatalog);
        }
    }

    private record HandlerCatalogIdentity(Map<String, NodeHandler> handlers,
                                          Map<String, Set<String>> operations) {
        private static HandlerCatalogIdentity capture(HandlerRegistry registry) {
            Map<String, NodeHandler> handlers = registry.snapshot();
            Map<String, Set<String>> operations = new TreeMap<>();
            handlers.keySet().forEach(handlerId -> operations.put(handlerId,
                Set.copyOf(registry.getSupportedOperations(handlerId))));
            return new HandlerCatalogIdentity(Map.copyOf(handlers), Map.copyOf(operations));
        }

        private boolean matches(HandlerRegistry registry) {
            Map<String, NodeHandler> current = registry.snapshot();
            if (!handlers.keySet().equals(current.keySet())) {
                return false;
            }
            for (Map.Entry<String, NodeHandler> entry : handlers.entrySet()) {
                if (current.get(entry.getKey()) != entry.getValue()
                    || !operations.get(entry.getKey()).equals(registry.getSupportedOperations(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }

    private record OptionCatalogIdentity(Map<String, OptionCatalogProvider> providers) {
        private static OptionCatalogIdentity capture(OptionCatalogRegistry registry) {
            Map<String, OptionCatalogProvider> providers = new TreeMap<>();
            registry.providers().forEach(provider -> providers.put(provider.sourceId(), provider));
            return new OptionCatalogIdentity(Map.copyOf(providers));
        }

        private boolean matches(OptionCatalogRegistry registry) {
            List<OptionCatalogProvider> current = registry.providers();
            if (current.size() != providers.size()) {
                return false;
            }
            for (OptionCatalogProvider provider : current) {
                if (providers.get(provider.sourceId()) != provider) {
                    return false;
                }
            }
            return true;
        }
    }

    private record PluginCatalogIdentity(Map<String, Plugin> plugins) {
        private static PluginCatalogIdentity capture() {
            Map<String, Plugin> plugins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Arrays.stream(Bukkit.getPluginManager().getPlugins()).forEach(plugin -> plugins.put(plugin.getName(), plugin));
            return new PluginCatalogIdentity(Map.copyOf(plugins));
        }

        private boolean matches() {
            Plugin[] current = Bukkit.getPluginManager().getPlugins();
            if (current.length != plugins.size()) {
                return false;
            }
            for (Plugin plugin : current) {
                if (plugins.get(plugin.getName()) != plugin) {
                    return false;
                }
            }
            return true;
        }
    }

    private record WorldGenCatalogIdentity(WorldGenFlowCatalogContribution contribution,
                                           List<NodeDefinition> definitions) {
        private static WorldGenCatalogIdentity capture(WorldGenFlowCatalogContribution contribution) {
            return new WorldGenCatalogIdentity(Objects.requireNonNull(contribution,
                "WorldGen catalog contribution is required"), List.copyOf(contribution.definitions()));
        }

        private boolean matches(WorldGenFlowCatalogContribution current) {
            if (current != contribution || current.definitions().size() != definitions.size()) {
                return false;
            }
            for (int index = 0; index < definitions.size(); index++) {
                if (current.definitions().get(index) != definitions.get(index)) {
                    return false;
                }
            }
            return true;
        }
    }

    private void addAuthoredCatalogSource(byte[] bytes, CatalogProvenance.SourceKind kind,
                                          String sourceUri, String relativePath,
                                          AuthoredCatalogSourceProof proof,
                                          Map<String, CatalogSourceIngestor.CatalogSource> sources) {
        String key = canonicalCatalogRelativePath(relativePath);
        if (key.isBlank()) {
            throw new IllegalArgumentException("Authored Flow catalog source relative path is required: " + sourceUri);
        }
        CatalogSourceIngestor.CatalogSource existing = sources.get(key);
        if (existing != null) {
            if (existing.sourceKind() == CatalogProvenance.SourceKind.BUNDLED
                && kind == CatalogProvenance.SourceKind.BUNDLED
                && Arrays.equals(existing.bytes(), bytes)) {
                return;
            }
            throw new IllegalArgumentException("Duplicate authored Flow catalog relative path: " + relativePath
                + " from " + existing.sourceUri() + " and " + sourceUri);
        }
        sources.put(key, CatalogSourceIngestor.CatalogSource.prepared(proof.owner(), kind, sourceUri, "1.0.0",
            "resync-flow", bytes, proof.sourceHash()));
    }

    private static String nodeDefinitionIdentity(NodeDefinition definition) {
        return String.valueOf(definition.getOwner()) + '\u0000' + String.valueOf(definition.getId());
    }

    private static boolean isReloadOwnedDefinitionPlugin(String pluginId) {
        return "json".equals(pluginId) || "json-classpath".equals(pluginId);
    }

    private static String normalizeCatalogRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String[] segments = path.replace('\\', '/').split("/+", -1);
        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (!segment.isBlank() && !".".equals(segment)) {
                normalized.add(segment);
            }
        }
        return String.join("/", normalized);
    }

    private static String canonicalCatalogRelativePath(String path) {
        return normalizeCatalogRelativePath(path).toLowerCase(Locale.ROOT);
    }

    private boolean hasDefinitionErrors(NodeDefinitionLoader loader) {
        return loader != null && loader.getDiagnostics().stream()
            .anyMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR);
    }

    public Map<String, Object> nodeRegistryDiagnostics() {
        NodeDefinitionRegistry nodeDefinitionRegistry = moduleContext.getRequiredService(NodeDefinitionRegistry.class);
        HandlerRegistry handlerRegistry = moduleContext.getRequiredService(HandlerRegistry.class);
        ExtensionRegistryActivation registryActivation = moduleContext.getService(ExtensionRegistryActivation.class);
        ExtensionRegistryActivation.State registrySnapshot = null;
        if (registryActivation == null) {
            registryActivation = nodeDefinitionRegistry.activation();
        }
        if (registryActivation != null) {
            registrySnapshot = registryActivation.snapshot();
            nodeDefinitionRegistry = registrySnapshot.nodeDefinitions();
            handlerRegistry = registrySnapshot.handlers();
        }
        HandlerRegistry diagnosticHandlers = handlerRegistry;
        Map<String, NodeDefinition> definitionsByIdentity = nodeDefinitionRegistry.getAllDefinitions();
        Map<String, Object> diagnostics = new HashMap<>();
        List<String> definitionSets = new ArrayList<>(nodeDefinitionRegistry.getPluginIds());
        definitionSets.sort(String.CASE_INSENSITIVE_ORDER);
        ReSyncExtensionManager extensionManager = moduleContext.getService(ReSyncExtensionManager.class);
        List<String> externalPlugins = extensionManager != null ? new ArrayList<>(extensionManager.getPluginIds()) : new ArrayList<>();
        externalPlugins.sort(String.CASE_INSENSITIVE_ORDER);
        diagnostics.put("definitions", definitionsByIdentity.size());
        diagnostics.put("definitionSets", definitionSets.size());
        diagnostics.put("definitionSetIds", definitionSets);
        diagnostics.put("externalNodePlugins", externalPlugins.size());
        diagnostics.put("externalNodePluginIds", externalPlugins);
        if (delegate != null) {
            diagnostics.put("catalogPublication", delegate.catalogPublicationDiagnostics());
        }
        var activation = delegate != null ? delegate.activeCatalogRuntimeActivation() : null;
        diagnostics.put("checksum", registryChecksum(registrySnapshot, activation));
        diagnostics.put("runtimeBindingGeneration", activation != null ? activation.runtime().generation() : 0L);
        diagnostics.put("runtimeBindings", activation != null ? activation.runtime().bindings().size() : 0);
        diagnostics.put("runtimeBindingManifestHash", activation != null
            ? activation.runtime().bindingManifestHash().canonicalText() : "");
        diagnostics.put("catalogRuntimeCleanup", delegate != null
            ? delegate.activeCatalogRuntimeDiagnostics()
            : Map.of("healthy", false, "pending", true, "diagnostics", List.of()));
        Throwable activationFault = fatalActivationFault;
        diagnostics.put("fatalActivationFault", activationFault == null
            ? Map.of("active", false)
            : Map.of("active", true, "type", activationFault.getClass().getName(),
                "message", activationFault.getMessage() != null ? activationFault.getMessage() : ""));
        diagnostics.put("flowClients", delegate != null ? delegate.getSubscribedSessionCount() : 0);
        Map<String, Integer> categoryCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> referencedHandlers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> missingHandlers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> missingOperations = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> referencedCatalogs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> missingCatalogs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (NodeDefinition definition : definitionsByIdentity.values()) {
            categoryCounts.merge(definition.getCategory().getId(), 1, Integer::sum);
            String handler = definition.getHandler();
            if (handler != null && !handler.isBlank()) {
                referencedHandlers.add(handler);
                if (!handlerRegistry.hasHandler(handler)) {
                    missingHandlers.add(definition.getId() + " -> " + handler);
                }
                Object operation = definition.getHandlerConfig() != null ? definition.getHandlerConfig().get("operation") : null;
                if (operation instanceof String operationId && !handlerRegistry.hasOperation(handler, operationId)) {
                    missingOperations.add(definition.getId() + " -> " + handler + "." + operationId);
                }
            }
            collectCatalogReferences(definition, referencedCatalogs, missingCatalogs);
        }
        List<String> catalogIds = optionCatalogRegistry != null ? optionCatalogRegistry.providers().stream().map(OptionCatalogProvider::sourceId).toList() : List.of();
        List<Map<String, Object>> rejectionDetails = nodeDefinitionDiagnostics.stream().map(NodeDefinitionDiagnostic::toMap).toList();
        long rejectionCount = nodeDefinitionDiagnostics.stream()
            .filter(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR)
            .map(value -> value.source() + ':' + value.index() + ':' + value.nodeId())
            .distinct()
            .count();
        long executableDefinitions = definitionsByIdentity.values().stream().filter(definition -> isExecutable(definition, diagnosticHandlers)).count();
        long migrationOnlyDefinitions = definitionsByIdentity.values().stream().filter(this::isMigrationOnly).count();
        long supportedDefinitions = definitionsByIdentity.size() - migrationOnlyDefinitions;
        long executableSupportedDefinitions = definitionsByIdentity.values()
            .stream()
            .filter(definition -> !isMigrationOnly(definition) && isExecutable(definition, diagnosticHandlers))
            .count();
        diagnostics.put("categories", categoryCounts);
        diagnostics.put("registeredHandlers", handlerRegistry.getHandlerIds().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        diagnostics.put("referencedHandlers", List.copyOf(referencedHandlers));
        diagnostics.put("missingHandlers", List.copyOf(missingHandlers));
        diagnostics.put("missingOperations", List.copyOf(missingOperations));
        diagnostics.put("catalogs", catalogIds);
        diagnostics.put("catalogDiagnostics", optionCatalogRegistry != null ? optionCatalogRegistry.diagnostics() : List.of());
        diagnostics.put("referencedCatalogs", List.copyOf(referencedCatalogs));
        diagnostics.put("missingCatalogs", List.copyOf(missingCatalogs));
        diagnostics.put("rejectedDefinitions", rejectionCount);
        diagnostics.put("definitionDiagnostics", rejectionDetails);
        diagnostics.put("parity", rejectionCount == 0 && missingHandlers.isEmpty() && missingOperations.isEmpty() && missingCatalogs.isEmpty()
            && executableSupportedDefinitions == supportedDefinitions);
        TypeRegistry typeRegistry = moduleContext.getRequiredService(TypeRegistry.class);
        ReSyncExtensionData extensionData = moduleContext.getService(ReSyncExtensionData.class);
        List<Map<String, Object>> nodeInventory = buildNodeInventory(nodeDefinitionRegistry, handlerRegistry,
            definitionsByIdentity, registrySnapshot);
        List<Map<String, Object>> typeInventory = buildTypeInventory(typeRegistry);
        List<Map<String, Object>> categoryInventory = buildCategoryInventory(categoryCounts, extensionData);
        List<Map<String, Object>> propertyInventory = buildPropertyInventory();
        List<Map<String, Object>> handlerInventory = buildHandlerInventory(handlerRegistry);
        List<Map<String, Object>> catalogInventory = buildCatalogInventory();
        List<Map<String, Object>> resourceInventory = buildResourceInventory();
        List<Map<String, Object>> extensionInventory = extensionManager != null ? extensionManager.contributionInventory() : List.of();
        List<Map<String, Object>> protocolInventory = buildProtocolInventory();
        diagnostics.put("nodeInventory", nodeInventory);
        diagnostics.put("typeInventory", typeInventory);
        diagnostics.put("categoryInventory", categoryInventory);
        diagnostics.put("propertyInventory", propertyInventory);
        diagnostics.put("handlerInventory", handlerInventory);
        diagnostics.put("catalogInventory", catalogInventory);
        diagnostics.put("resourceInventory", resourceInventory);
        diagnostics.put("validatorInventory", graphValidationRegistry != null ? graphValidationRegistry.inventory() : List.of());
        List<FlowResourceAuditRecord> resourceAudit = resourceRegistry != null ? resourceRegistry.auditSnapshot() : List.of();
        diagnostics.put("resourceAuditCount", resourceAudit.size());
        diagnostics.put("resourceAudit", resourceAudit.stream().skip(Math.max(0, resourceAudit.size() - 50L)).toList());
        List<FlowNodeAuditRecord> nodeAudit = executor != null ? executor.auditSnapshot() : List.of();
        diagnostics.put("nodeAuditCount", nodeAudit.size());
        diagnostics.put("nodeAudit", nodeAudit.stream().skip(Math.max(0, nodeAudit.size() - 50L)).toList());
        List<FlowJobReference.Snapshot<?>> jobSnapshots = flowJobs != null ? flowJobs.snapshots("") : List.of();
        Map<String, Long> jobStates = jobSnapshots.stream().collect(Collectors.groupingBy(snapshot -> snapshot.state().name(), TreeMap::new, Collectors.counting()));
        diagnostics.put("jobCount", jobSnapshots.size());
        diagnostics.put("jobStates", jobStates);
        diagnostics.put("jobs", jobSnapshots.stream().limit(50).map(this::buildJobInventory).toList());
        diagnostics.put("protocolContracts", protocolInventory);
        diagnostics.put("extensionContributions", extensionData != null ? extensionData.contributionCounts() : Map.of());
        diagnostics.put("extensionContributionInventory", extensionInventory);
        diagnostics.put("definitionParity", Map.of(
            "source", nodeInventory.size(),
            "loaded", definitionsByIdentity.size(),
            "advertised", definitionsByIdentity.size(),
            "supported", supportedDefinitions,
            "migrationOnly", migrationOnlyDefinitions,
            "executable", executableDefinitions,
            "executableSupported", executableSupportedDefinitions,
            "rejected", rejectionCount
        ));
        diagnostics.put("inventoryCounts", Map.ofEntries(
            Map.entry("nodes", nodeInventory.size()),
            Map.entry("types", typeInventory.size()),
            Map.entry("categories", categoryInventory.size()),
            Map.entry("catalogs", catalogInventory.size()),
            Map.entry("properties", propertyInventory.size()),
            Map.entry("handlers", handlerInventory.size()),
            Map.entry("resources", resourceInventory.size()),
            Map.entry("validators", graphValidationRegistry != null ? graphValidationRegistry.inventory().size() : 0),
            Map.entry("protocolContracts", protocolInventory.size()),
            Map.entry("jobs", jobSnapshots.size()),
            Map.entry("extensions", extensionInventory.size())
        ));
        Map<String, Long> nodeDispositions = dispositionCounts(nodeInventory);
        Map<String, Long> resourceDispositions = dispositionCounts(resourceInventory);
        diagnostics.put("nodeDispositions", nodeDispositions);
        diagnostics.put("resourceDispositions", resourceDispositions);
        diagnostics.put("inventoryComplete", nodeDispositions.getOrDefault("incomplete", 0L) == 0L
            && nodeDispositions.getOrDefault("rejected", 0L) == 0L
            && resourceDispositions.getOrDefault("incomplete", 0L) == 0L
            && !protocolInventory.isEmpty());
        return diagnostics;
    }

    private String registryChecksum(ExtensionRegistryActivation.State registrySnapshot,
                                    CatalogRuntimeActivation.ActivationRecord activation) {
        if (delegate == null) {
            return "";
        }
        RegistryChecksumCache cached = registryChecksumCache;
        if (registrySnapshot != null && activation != null && cached != null
            && cached.matches(registrySnapshot, activation)) {
            return cached.checksum();
        }
        String checksum = delegate.getNodeRegistryChecksum();
        if (registrySnapshot == null || activation == null) {
            return checksum;
        }
        RegistryChecksumCache candidate = new RegistryChecksumCache(registrySnapshot, activation, checksum);
        synchronized (this) {
            RegistryChecksumCache current = registryChecksumCache;
            if (current != null && current.matches(registrySnapshot, activation)) {
                return current.checksum();
            }
            registryChecksumCache = candidate;
        }
        return checksum;
    }

    private Map<String, Object> buildJobInventory(FlowJobReference.Snapshot<?> snapshot) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", snapshot.id());
        item.put("kind", snapshot.kind());
        item.put("owner", snapshot.owner());
        item.put("createdAt", snapshot.createdAt().toEpochMilli());
        item.put("state", snapshot.state().name());
        item.put("progress", snapshot.progress());
        item.put("metadata", snapshot.metadata());
        item.put("cancellationRequested", snapshot.cancellationRequested());
        item.put("outcome", snapshot.outcome() == null ? null : Map.of(
            "success", snapshot.outcome().success(),
            "errorCode", snapshot.outcome().errorCode(),
            "message", snapshot.outcome().message(),
            "details", snapshot.outcome().details()
        ));
        return item;
    }

    private Map<String, Long> dispositionCounts(List<Map<String, Object>> inventory) {
        Map<String, Long> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map<String, Object> item : inventory) {
            String disposition = String.valueOf(item.getOrDefault("disposition", "unknown"));
            counts.merge(disposition, 1L, Long::sum);
        }
        return Map.copyOf(counts);
    }

    private boolean isExecutable(NodeDefinition definition, HandlerRegistry handlerRegistry) {
        if (definition.isHidden() && definition.getHiddenReason().toLowerCase(Locale.ROOT).contains("unsupported")) {
            return false;
        }
        if (definition.isTrigger()) {
            return true;
        }
        String handler = definition.getHandler();
        if (handler == null || !handlerRegistry.hasHandler(handler)) {
            return false;
        }
        Object operation = definition.getHandlerConfig() != null ? definition.getHandlerConfig().get("operation") : null;
        return !(operation instanceof String operationId) || handlerRegistry.hasOperation(handler, operationId);
    }

    private boolean isMigrationOnly(NodeDefinition definition) {
        String hiddenReason = definition.getHiddenReason().toLowerCase(Locale.ROOT);
        return definition.getKind() == NodeDefinition.NodeKind.ALIAS || definition.isDeprecated()
            || hiddenReason.contains("migrat") || hiddenReason.contains("deprecated");
    }

    private List<Map<String, Object>> buildNodeInventory(NodeDefinitionRegistry registry, HandlerRegistry handlers) {
        ExtensionRegistryActivation.State snapshot = null;
        ExtensionRegistryActivation activation = registry.activation();
        if (activation != null) {
            snapshot = activation.snapshot();
            registry = snapshot.nodeDefinitions();
            handlers = snapshot.handlers();
        }
        return buildNodeInventory(registry, handlers, registry.getAllDefinitions(), snapshot);
    }

    private List<Map<String, Object>> buildNodeInventory(NodeDefinitionRegistry registry, HandlerRegistry handlers,
                                                          Map<String, NodeDefinition> definitionsByIdentity,
                                                          ExtensionRegistryActivation.State snapshot) {
        List<NodeDefinitionDiagnostic> diagnostics = nodeDefinitionDiagnostics;
        NodeInventoryCache cached = nodeInventoryCache;
        if (snapshot != null && cached != null && cached.matches(snapshot, diagnostics)) {
            return cached.inventory();
        }
        List<NodeDefinition> definitions = new ArrayList<>(definitionsByIdentity.values());
        definitions.sort(Comparator.comparing(NodeDefinition::getId, String.CASE_INSENSITIVE_ORDER));
        List<Map<String, Object>> inventory = new ArrayList<>();
        for (NodeDefinition definition : definitions) {
            Map<String, Object> item = new LinkedHashMap<>();
            List<String> requirements = new ArrayList<>(List.of("NODE-001", "NODE-002", "NODE-020"));
            boolean catalogBacked = definition.getInputs().stream().anyMatch(pin -> pin.getOptionsSource() != null && !pin.getOptionsSource().isBlank())
                || definition.getOutputs().stream().anyMatch(pin -> pin.getOptionsSource() != null && !pin.getOptionsSource().isBlank());
            if (catalogBacked) {
                requirements.addAll(List.of("CAT-001", "CAT-002"));
            }
            if (definition.getHandler() != null && !definition.getHandler().isBlank()) {
                requirements.add("EXEC-001");
            }
            if (!definition.isHidden()) {
                requirements.add("NODE-030");
            } else {
                requirements.add("NODE-007");
            }
            if ("GenericListHandler".equals(definition.getHandler()) || "GenericMapHandler".equals(definition.getHandler())) {
                requirements.add("FLOW-012");
            }
            if ("ScheduleHandler".equals(definition.getHandler()) || "TimeHandler".equals(definition.getHandler())) {
                requirements.add("TIME-001");
            }
            if (definition.getSchemaVersion() > 1) {
                requirements.add("MIG-001");
            }
            List<String> incompleteReasons = new ArrayList<>();
            String hiddenReason = definition.getHiddenReason().toLowerCase(Locale.ROOT);
            boolean migrationOnly = isMigrationOnly(definition);
            boolean executable = isExecutable(definition, handlers);
            if (!executable && !migrationOnly) {
                incompleteReasons.add("handler_or_operation_unavailable");
            }
            if (!definition.isHidden() && (definition.getDescription() == null || definition.getDescription().isBlank())) {
                incompleteReasons.add("description_missing");
            }
            if (!definition.isHidden() && definition.getTags().isEmpty()) {
                incompleteReasons.add("search_tags_missing");
            }
            if (definition.isHidden() && !migrationOnly && (hiddenReason.contains("incomplete") || hiddenReason.contains("unsupported"))) {
                incompleteReasons.add("hidden_" + hiddenReason);
            }
            String disposition;
            if (migrationOnly) {
                disposition = "migration-only";
                requirements.add("NODE-008");
            } else if (!incompleteReasons.isEmpty()) {
                disposition = "incomplete";
            } else {
                disposition = "supported";
            }
            item.put("id", definition.getId());
            item.put("owner", registry.getPluginForNode(definition));
            item.put("category", definition.getCategory().getId());
            item.put("handler", definition.getHandler() != null ? definition.getHandler() : "");
            Object operation = definition.getHandlerConfig() != null ? definition.getHandlerConfig().get("operation") : null;
            item.put("operation", operation != null ? operation : "");
            item.put("schemaVersion", definition.getSchemaVersion());
            item.put("hidden", definition.isHidden());
            item.put("hiddenReason", definition.getHiddenReason());
            item.put("disposition", disposition);
            item.put("executable", executable);
            item.put("incompleteReasons", incompleteReasons);
            item.put("requirements", requirements.stream().distinct().toList());
            inventory.add(item);
        }
        Set<String> rejectedOrigins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (NodeDefinitionDiagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() != NodeDefinitionDiagnostic.Severity.ERROR) {
                continue;
            }
            String origin = diagnostic.source() + ":" + diagnostic.index() + ":" + diagnostic.nodeId();
            if (!rejectedOrigins.add(origin)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", diagnostic.nodeId());
            item.put("owner", diagnostic.source());
            item.put("category", "unresolved");
            item.put("handler", "");
            item.put("operation", "");
            item.put("schemaVersion", 0);
            item.put("disposition", "rejected");
            item.put("requirements", List.of("NODE-003", "NODE-004", "NODE-005"));
            item.put("diagnostic", diagnostic.toMap());
            inventory.add(item);
        }
        List<Map<String, Object>> result = List.copyOf(inventory);
        if (snapshot != null) {
            NodeInventoryCache candidate = new NodeInventoryCache(snapshot, diagnostics, result);
            synchronized (this) {
                NodeInventoryCache current = nodeInventoryCache;
                if (current != null && current.matches(snapshot, diagnostics)) {
                    return current.inventory();
                }
                nodeInventoryCache = candidate;
            }
        }
        return result;
    }

    private record NodeInventoryCache(ExtensionRegistryActivation.State activationState,
                                      List<NodeDefinitionDiagnostic> diagnostics,
                                      List<Map<String, Object>> inventory) {
        private boolean matches(ExtensionRegistryActivation.State state, List<NodeDefinitionDiagnostic> currentDiagnostics) {
            return activationState == state && diagnostics.equals(currentDiagnostics);
        }
    }

    private record RegistryChecksumCache(ExtensionRegistryActivation.State activationState,
                                         CatalogRuntimeActivation.ActivationRecord catalogActivation,
                                         String checksum) {
        private boolean matches(ExtensionRegistryActivation.State state,
                                CatalogRuntimeActivation.ActivationRecord activation) {
            return activationState == state && catalogActivation == activation;
        }
    }

    private List<Map<String, Object>> buildTypeInventory(TypeRegistry registry) {
        Map<String, FlowDataType> types = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        types.putAll(FlowDataType.registeredTypes());
        registry.getAll().forEach(type -> types.put(type.getId(), type));
        Set<String> builtInIds = FlowDataType.registeredTypes().keySet();
        List<Map<String, Object>> inventory = new ArrayList<>();
        for (Map.Entry<String, FlowDataType> entry : types.entrySet()) {
            FlowDataType type = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.getKey());
            item.put("canonicalId", type.getId());
            item.put("owner", builtInIds.contains(entry.getKey()) ? "standard" : "extension");
            item.put("disposition", entry.getKey().equalsIgnoreCase(type.getId()) ? "supported" : "migration-only");
            item.put("parent", type.getParent() != null ? type.getParent().getId() : "");
            item.put("javaType", type.getJavaType() != null ? type.getJavaType().getName() : "");
            item.put("serializedType", type.getDataClass() != null ? type.getDataClass().getName() : "");
            item.put("requirements", entry.getKey().equalsIgnoreCase(type.getId())
                ? List.of("TYPE-001", "TYPE-002", "TYPE-003")
                : List.of("TYPE-001", "TYPE-010", "MIG-010"));
            inventory.add(item);
        }
        return List.copyOf(inventory);
    }

    private List<Map<String, Object>> buildCategoryInventory(Map<String, Integer> categoryCounts, ReSyncExtensionData extensionData) {
        List<Map<String, Object>> inventory = new ArrayList<>();
        for (NodeDefinition.NodeCategory category : NodeDefinition.NodeCategory.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", category.getId());
            item.put("displayName", category.getDisplayName());
            item.put("owner", "standard");
            item.put("nodes", categoryCounts.getOrDefault(category.getId(), 0));
            item.put("disposition", "supported");
            item.put("requirements", List.of("NODE-001", "NODE-006"));
            inventory.add(item);
        }
        if (extensionData != null) {
            extensionData.categories().forEach(category -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", category.getId());
                item.put("displayName", category.getDisplayName());
                item.put("owner", "extension");
                item.put("nodes", categoryCounts.getOrDefault(category.getId(), 0));
                item.put("disposition", "supported");
                item.put("requirements", List.of("NODE-001", "NODE-006", "EXT-001"));
                inventory.add(item);
            });
        }
        return List.copyOf(inventory);
    }

    private List<Map<String, Object>> buildPropertyInventory() {
        List<Map<String, Object>> inventory = new ArrayList<>();
        for (String family : propertyRegistry.getFamilies()) {
            List<String> properties = new ArrayList<>(propertyRegistry.getProperties(family));
            properties.sort(String.CASE_INSENSITIVE_ORDER);
            for (String property : properties) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("family", family);
                item.put("property", property);
                item.put("owner", family.contains(":") ? family.substring(0, family.indexOf(':')) : "standard");
                item.put("type", propertyRegistry.getDataType(family, property).getId());
                item.put("actions", propertyRegistry.getActions(family, property));
                item.put("disposition", "supported");
                item.put("requirements", List.of("PROP-001", "PROP-002", "PROP-003"));
                inventory.add(item);
            }
        }
        return List.copyOf(inventory);
    }

    private List<Map<String, Object>> buildHandlerInventory(HandlerRegistry registry) {
        List<Map<String, Object>> inventory = new ArrayList<>();
        for (String handlerId : registry.getHandlerIds().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", handlerId);
            item.put("owner", handlerId.contains(":") ? handlerId.substring(0, handlerId.indexOf(':')) : "standard");
            item.put("operations", registry.getSupportedOperations(handlerId).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
            item.put("disposition", "supported");
            item.put("requirements", List.of("EXEC-001", "EXEC-010"));
            inventory.add(item);
        }
        return List.copyOf(inventory);
    }

    private List<Map<String, Object>> buildCatalogInventory() {
        if (optionCatalogRegistry == null) {
            return List.of();
        }
        return optionCatalogRegistry.providers().stream().map(provider -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", provider.sourceId());
            item.put("provider", provider.providerId());
            item.put("owner", provider.providerId());
            item.put("widget", provider.widgetType());
            item.put("searchable", provider.searchable());
            item.put("contextKeys", provider.contextKeys() != null
                ? provider.contextKeys().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()
                : List.of());
            item.put("disposition", "supported");
            item.put("requirements", List.of("CAT-001", "CAT-002", "CAT-003"));
            return item;
        }).toList();
    }

    private List<Map<String, Object>> buildResourceInventory() {
        return ReSyncResourceCatalog.all().stream().map(resource -> {
            FlowResourceAdapter<?> adapter = resourceRegistry != null ? resourceRegistry.get(resource.typeId()) : null;
            FlowResourceMetadata resourceMetadata = resourceRegistry != null ? resourceRegistry.metadata(resource.typeId()) : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", resource.typeId());
            item.put("owner", "standard");
            item.put("displayName", resource.displayName());
            item.put("defaultFolder", resource.defaultFolder());
            item.put("enabled", resource.enabled());
            item.put("jsonStorage", resource.jsonStorageSupported());
            item.put("catalog", optionCatalogRegistry != null && optionCatalogRegistry.contains("server:resync:" + resource.typeId()));
            item.put("adapter", adapter != null);
            item.put("operations", adapter != null ? adapter.supportedOperations().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList() : List.of());
            item.put("operationAvailability", resourceMetadata != null ? resourceMetadata.getOperationAvailability() : Map.of());
            item.put("lifecycle", adapter != null ? adapter.lifecycle() : "unavailable");
            item.put("durable", adapter != null && adapter.durable());
            item.put("changeEvents", adapter != null && adapter.changeEvents());
            item.put("activeRefresh", adapter != null && adapter.activeRefresh());
            item.put("authoritativeService", adapter != null ? adapter.authoritativeService() : "");
            List<String> incompleteReasons = new ArrayList<>();
            if (!resource.enabled()) incompleteReasons.add("resource_disabled");
            if (adapter == null) incompleteReasons.add("adapter_missing");
            if (optionCatalogRegistry == null || !optionCatalogRegistry.contains("server:resync:" + resource.typeId())) incompleteReasons.add("catalog_missing");
            item.put("incompleteReasons", incompleteReasons);
            item.put("disposition", incompleteReasons.isEmpty() ? "supported" : "incomplete");
            item.put("requirements", List.of("RES-001", "RES-002", "RES-003"));
            return item;
        }).toList();
    }

    private List<Map<String, Object>> buildProtocolInventory() {
        List<Map<String, Object>> inventory = new ArrayList<>(ReSyncProtocolInventory.snapshot());
        for (ReSyncManagedResource resource : ReSyncResourceCatalog.all()) {
            if (!resource.hasFlowPackets()) {
                continue;
            }
            ReSyncManagedResource.FlowPackets packets = resource.flowPackets();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "RESOURCE_" + resource.typeId().toUpperCase(Locale.ROOT));
            item.put("kind", "resource-packets");
            item.put("resource", resource.typeId());
            item.put("owner", "standard");
            item.put("disposition", "supported");
            item.put("request", Byte.toUnsignedInt(packets.request()));
            item.put("listRequest", Byte.toUnsignedInt(packets.listRequest()));
            item.put("data", Byte.toUnsignedInt(packets.data()));
            item.put("list", Byte.toUnsignedInt(packets.list()));
            item.put("save", Byte.toUnsignedInt(packets.save()));
            item.put("delete", Byte.toUnsignedInt(packets.delete()));
            item.put("saveAck", Byte.toUnsignedInt(packets.saveAck()));
            item.put("requirements", List.of("PROTO-001", "PROTO-002", "PROTO-003"));
            inventory.add(item);
        }
        return List.copyOf(inventory);
    }

    private void collectCatalogReferences(NodeDefinition definition, Set<String> referencedCatalogs, Set<String> missingCatalogs) {
        List<NodeDefinition.PinDefinition> pins = new ArrayList<>();
        pins.addAll(definition.getInputs());
        pins.addAll(definition.getOutputs());
        for (NodeDefinition.PinDefinition pin : pins) {
            String sourceId = pin.getOptionsSource();
            if (sourceId == null || sourceId.isBlank()) {
                continue;
            }
            referencedCatalogs.add(sourceId);
            if (optionCatalogRegistry == null || !optionCatalogRegistry.contains(sourceId)) {
                missingCatalogs.add(definition.getId() + "." + pin.getName() + " -> " + sourceId);
            }
        }
    }

    private void removeUnavailable(List<NodeDefinition> definitions, NodeDefinitionLoader loader) {
        definitions.removeIf(definition -> {
            if (!isUnavailable(definition)) {
                return false;
            }
            NodeDefinition.Availability availability = definition.getAvailability();
            loader.rejectUnavailable(definition, "Required plugin is unavailable: " + availability.getPlugin());
            return true;
        });
    }

    public FlowTraceService getTraceService() {
        return traceService;
    }

    private boolean isUnavailable(NodeDefinition def) {
        NodeDefinition.Availability availability = def.getAvailability();
        return availability != null && availability.getPlugin() != null && Bukkit.getPluginManager().getPlugin(availability.getPlugin()) == null;
    }

    private void registerNodeHandlers(HandlerRegistry handlerRegistry) {
        new AbilityEffectHandler(automationTasks).registerTo(handlerRegistry);
        new GenericMathHandler().registerTo(handlerRegistry);
        new GenericStringHandler().registerTo(handlerRegistry);
        new GenericListHandler().registerTo(handlerRegistry);
        new GenericMapHandler().registerTo(handlerRegistry);
        new VariableHandler().registerTo(handlerRegistry);
        new LogicHandler().registerTo(handlerRegistry);
        new ResultHandler().registerTo(handlerRegistry);
        new ResourceValueHandler().registerTo(handlerRegistry);
        new ConversionHandler().registerTo(handlerRegistry);
        new DebugHandler().registerTo(handlerRegistry);
        new DiscordHandler().registerTo(handlerRegistry);
        new ChatHandler().registerTo(handlerRegistry);
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            new EconomyHandler().registerTo(handlerRegistry);
        }
        new FileHandler(managedFlowFileCapability).registerTo(handlerRegistry);
        new FlowControlHandler().registerTo(handlerRegistry);
        new FlowJobHandler(flowJobs).registerTo(handlerRegistry);
        new HttpHandler().registerTo(handlerRegistry);
        new JsonHandler().registerTo(handlerRegistry);
        new LocationHandler().registerTo(handlerRegistry);
        new MenuHandler().registerTo(handlerRegistry);
        new ParticleHandler().registerTo(handlerRegistry);
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            new PermissionHandler().registerTo(handlerRegistry);
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHandler().registerTo(handlerRegistry);
        }
        new RandomHandler().registerTo(handlerRegistry);
        new RuntimeDataHandler(runtimeDataRegistry).registerTo(handlerRegistry);
        regionHandler = new RegionHandler();
        try {
            regionPersistenceParticipant = regionHandler.bindPersistence(dataRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Flow region persistence could not be initialized", exception);
        }
        regionHandler.registerTo(handlerRegistry);
        new ResourceDefinitionHandler().registerTo(handlerRegistry);
        new ScoreboardHandler().registerTo(handlerRegistry);
        new SoundHandler().registerTo(handlerRegistry);
        new ServerHandler().registerTo(handlerRegistry);
        new NetworkFlowHandler().registerTo(handlerRegistry);
        new TeamHandler().registerTo(handlerRegistry);
        new TextFormatHandler().registerTo(handlerRegistry);
        new TextResourceHandler(moduleContext.getRequiredService(ReTextService.class)).registerTo(handlerRegistry);
        scheduleHandler = new ScheduleHandler(storage, Clock.systemUTC(), automationDefinitions, automationTasks, valueCodecs,
            runtimePrincipalAuthority, serverId);
        scheduleHandler.registerTo(handlerRegistry);
        new TimerHandler(automationDefinitions, automationTasks).registerTo(handlerRegistry);
        new TitleHandler().registerTo(handlerRegistry);
        new TimeHandler().registerTo(handlerRegistry);
        new UuidHandler().registerTo(handlerRegistry);
        new ColorHandler().registerTo(handlerRegistry);
        new CustomEventHandler().registerTo(handlerRegistry);
        new CustomContentHandler().registerTo(handlerRegistry);
        new CustomFunctionCallHandler().registerTo(handlerRegistry);
        new VariableScopeHandler(automationVariables, persistentVariables).registerTo(handlerRegistry);
        new FunctionCatalogHandler(storage).registerTo(handlerRegistry);
        new FunctionHandler().registerTo(handlerRegistry);
        new PlayerActionHandler().registerTo(handlerRegistry);
        new EntityActionHandler().registerTo(handlerRegistry);
        new WorldActionHandler().registerTo(handlerRegistry);
        new WorldGenFlowHandler(worldGenOperations).registerTo(handlerRegistry);
        new BlockActionHandler().registerTo(handlerRegistry);
        new InventoryActionHandler().registerTo(handlerRegistry);
        new ReSyncRuntimeResourceHandler(resourceRegistry).registerTo(handlerRegistry);
        new MiscHandler().registerTo(handlerRegistry);
        new RestoredNodeHandler().registerTo(handlerRegistry);
        JsonFamilyHandler.registerFamilies(handlerRegistry, propertyRegistry);
    }

    @Override
    public void start(ModuleContext context) {
        if (context != moduleContext) {
            throw new IllegalStateException("Flow runtime startup context changed before activation");
        }
    }

    @Override
    public void stop(ModuleContext context) {
        prepareStop(context);
        CompletableFuture<Void> completion = finishStopAsync(context).toCompletableFuture();
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread() && !completion.isDone()) {
            throw new IllegalStateException("Flow runtime stop requires asynchronous completion while executions drain");
        }
        completion.join();
    }

    @Override
    public synchronized void prepareStop(ModuleContext context) {
        if (stopped || stopPending) {
            return;
        }
        if (context != moduleContext) {
            throw new IllegalStateException("Flow runtime shutdown context changed before deactivation");
        }
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Flow runtime shutdown requires the Bukkit primary thread");
        }
        stopPending = true;
        FlowExecutor runtimeExecutor = executor;
        FlowExecutor.AdmissionFence admissionFence = runtimeExecutor != null ? runtimeExecutor.fenceAdmissions() : null;
        stopAdmissionFence = admissionFence;
        if (resourceRegistry != null) {
            resourceRegistry.cancelPendingLiveRefreshes();
        }
        if (runtimeExecutor != null) {
            runtimeExecutor.cancelPendingTasks();
        }
        try {
            stopFenced(context, runtimeExecutor, admissionFence);
        } catch (RuntimeException exception) {
            if (admissionFence != null) {
                admissionFence.close();
            }
            stopAdmissionFence = null;
            stopPending = false;
            stopCompletion = CompletableFuture.failedFuture(exception);
            throw exception;
        }
        if (admissionFence == null || admissionFence.isDrained()) {
            stopped = true;
            stopPending = false;
            stopAdmissionFence = null;
            if (admissionFence != null) {
                admissionFence.close();
            }
            stopCompletion = CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public synchronized CompletionStage<Void> finishStopAsync(ModuleContext context) {
        if (stopped) {
            return CompletableFuture.completedFuture(null);
        }
        if (!stopPending || stopAdmissionFence == null) {
            return stopCompletion != null ? stopCompletion : CompletableFuture.failedFuture(
                new IllegalStateException("Flow runtime stop requires primary-thread preparation"));
        }
        if (stopCompletion != null && !stopCompletion.toCompletableFuture().isCompletedExceptionally()
            && !stopCompletion.toCompletableFuture().isCancelled()) {
            return stopCompletion;
        }
        FlowExecutor.AdmissionFence admissionFence = stopAdmissionFence;
        CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> {
            if (!admissionFence.awaitDrained(STOP_DRAIN_TIMEOUT)) {
                throw new IllegalStateException("Flow runtime stop could not drain active executions before timeout");
            }
        }).thenRun(() -> {
            synchronized (this) {
                stopped = true;
                stopPending = false;
                stopAdmissionFence = null;
            }
        }).whenComplete((unused, failure) -> {
            if (failure == null) {
                admissionFence.close();
            }
        });
        stopCompletion = completion.whenComplete((unused, failure) -> {
            if (failure != null) {
                synchronized (this) {
                    if (!stopped) {
                        stopPending = true;
                        stopAdmissionFence = admissionFence;
                    }
                }
            }
        });
        return stopCompletion;
    }

    @Override
    public CompletionStage<Void> stopAsync(ModuleContext context) {
        prepareStop(context);
        return finishStopAsync(context);
    }

    private void stopFenced(ModuleContext context, FlowExecutor runtimeExecutor, FlowExecutor.AdmissionFence admissionFence) {
        diagnosticsPublicationReady = false;
        RuntimeException failure = null;
        failure = cleanupFailure(failure, () -> {
            if (delegate != null) {
                delegate.deactivateCoreMutationSubscribers();
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (resourceRegistry != null) {
                resourceRegistry.cancelPendingLiveRefreshes();
            }
        });
        FlowExecutor.AdmissionFence pendingStartupFence = startupAdmissionFence;
        startupAdmissionFence = null;
        failure = cleanupFailure(failure, () -> {
            if (pendingStartupFence != null) pendingStartupFence.close();
        });
        FlowExecutor.AdmissionFence pendingFatalActivationFence = fatalActivationFence;
        fatalActivationFence = null;
        failure = cleanupFailure(failure, () -> {
            if (pendingFatalActivationFence != null) pendingFatalActivationFence.close();
        });
        CustomContentExecution contentExecution = customContentExecution;
        customContentExecution = null;
        failure = cleanupFailure(failure, () -> {
            if (contentExecution != null) contentExecution.close();
        });
        ServerCompiledPlanRepository planRepository = compiledPlanRepository;
        compiledPlanRepository = null;
        failure = cleanupFailure(failure, () -> {
            if (planRepository != null) planRepository.close();
        });
        WorldManagementListener worldListener = worldResourceListener;
        worldResourceListener = null;
        failure = cleanupFailure(failure, () -> {
            if (worldManagementService != null && worldListener != null) worldManagementService.removeListener(worldListener);
        });
        failure = cleanupFailure(failure, () -> {
            if (worldGenStorage != null) worldGenStorage.setChangeListener(null);
        });
        ReSyncNetworkAgent.Listener optionCatalogListener = networkOptionCatalogListener;
        ReSyncNetworkAgent optionCatalogAgent = networkOptionCatalogAgent;
        networkOptionCatalogListener = null;
        networkOptionCatalogAgent = null;
        failure = cleanupFailure(failure, () -> {
            if (optionCatalogAgent != null && optionCatalogListener != null) optionCatalogAgent.removeListener(optionCatalogListener);
        });
        failure = cleanupFailure(failure, () -> {
            if (networkFlowBridge != null) networkFlowBridge.disconnect();
        });
        failure = cleanupFailure(failure, () -> {
            if (systemEventListener != null) systemEventListener.onServerStop();
        });
        BukkitTask task = tickTask;
        tickTask = null;
        failure = cleanupFailure(failure, () -> {
            if (task != null) task.cancel();
        });
        failure = cleanupFailure(failure, TabListService::stopUpdater);
        AutomationTaskService taskService = automationTasks;
        automationTasks = null;
        failure = cleanupFailure(failure, () -> {
            if (taskService != null) taskService.shutdown();
        });
        failure = cleanupFailure(failure, () -> {
            if (globalTriggers != null) globalTriggers.shutdownRuntimeCommands();
        });
        failure = cleanupFailure(failure, () -> {
            if (globalTriggers != null) HandlerList.unregisterAll(globalTriggers);
        });
        failure = cleanupFailure(failure, () -> {
            if (globalTriggers != null && globalTriggers.getTriggerDispatcher() != null) {
                globalTriggers.getTriggerDispatcher().closeDefinitionAdmission();
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (flowEventRegistry != null) flowEventRegistry.closeActivation();
        });
        executor = null;
        failure = cleanupFailure(failure, () -> {
            if (runtimeExecutor != null) runtimeExecutor.shutdown();
        });
        HandlerRegistry registry = handlerRegistry;
        failure = cleanupFailure(failure, () -> {
            shutdownHandlers(registry);
            if (handlerRegistry == registry) {
                handlerRegistry = null;
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (systemEventListener != null) HandlerList.unregisterAll(systemEventListener);
        });
        failure = cleanupFailure(failure, () -> {
            if (scoreboardRuntimeListener != null) HandlerList.unregisterAll(scoreboardRuntimeListener);
        });
        failure = cleanupFailure(failure, () -> {
            if (guiManager != null) guiManager.shutdown();
        });
        failure = cleanupFailure(failure, () -> {
            if (guiManager != null) HandlerList.unregisterAll(guiManager);
        });
        failure = cleanupFailure(failure, () -> {
            if (customContentListener != null) HandlerList.unregisterAll(customContentListener);
        });
        failure = cleanupFailure(failure, () -> {
            if (lootTableService != null) {
                lootTableService.shutdown();
                HandlerList.unregisterAll(lootTableService);
            }
        });
        failure = cleanupFailure(failure, () -> {
            if (tradeProfileService != null) HandlerList.unregisterAll(tradeProfileService);
        });
        ResourceListener resourceListener = jsonResourceListener;
        jsonResourceListener = null;
        failure = cleanupFailure(failure, () -> {
            if (jsonResourceStorage != null && resourceListener != null) jsonResourceStorage.removeListener(resourceListener);
        });
        automationCatalogProviders.clear();
        JsonRuntimeResourceValidator resourceValidator = jsonResourceValidator;
        jsonResourceValidator = null;
        failure = cleanupFailure(failure, () -> {
            if (jsonResourceStorage != null && resourceValidator != null) jsonResourceStorage.removeInterceptor(resourceValidator);
        });
        failure = cleanupFailure(failure, () -> {
            if (npcService != null) npcService.shutdown();
        });
        failure = cleanupFailure(failure, () -> {
            if (npcService != null) HandlerList.unregisterAll(npcService);
        });
        failure = cleanupFailure(failure, FlowRuntimeAccess::clear);
        failure = cleanupFailure(failure, ScoreboardTemplateManager::clearRuntimeCapability);
        failure = cleanupFailure(failure, ScoreboardTemplateManager::clearEditStateBridge);
        failure = cleanupFailure(failure, CustomContentAccess::clear);
        failure = cleanupFailure(failure, ReSyncRuntimeContentAccess::clear);
        if (failure != null) throw failure;
    }

    static void shutdownHandlers(HandlerRegistry registry) {
        if (registry == null) {
            return;
        }
        registry.clearForShutdown();
    }

    private RuntimeException cleanupFailure(RuntimeException failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            if (failure == null) return exception;
            failure.addSuppressed(exception);
        }
        return failure;
    }

    private AutomationTaskService createAutomationTaskService(ModuleContext context,
                                                               AutomationDefinitionRegistry definitions) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ReSync Automation");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Path persistenceFile = dataRoot.resolve(AutomationTaskPersistenceParticipant.DIRECTORY)
                .resolve(AutomationTaskPersistenceParticipant.FILE_NAME);
            return new AutomationTaskService(context.getPlugin(), definitions, Clock.systemUTC(), scheduler, persistenceFile);
        } catch (RuntimeException | Error failure) {
            scheduler.shutdownNow();
            throw failure;
        }
    }

    private void registerResourceCatalogs(OptionCatalogRegistry registry, ReSyncJsonResourceStorage storage) {
        if (registry == null || storage == null) {
            return;
        }
        for (String type : storage.resourceTypes()) {
            if (Set.of(ReSyncResourceCatalog.VARIABLE_DEFINITION, ReSyncResourceCatalog.TIMER_DEFINITION,
                ReSyncResourceCatalog.SCHEDULE_DEFINITION).contains(type)) {
                registerAutomationCatalog(registry, storage, type);
            } else {
                registerResourceCatalog(registry, type, OptionCatalogProvider.CaptureAffinity.IO, () -> storage.listIds(type));
            }
        }
    }

    private void handleCoreGraphChange(FlowStorage.GraphChange change) {
        if (!startupActivationComplete) {
            return;
        }
        String graphId = change == null ? "" : change.id();
        String graphType = change == null ? "" : change.type();
        ServerCompiledPlanRepository repository = compiledPlanRepository;
        if (repository == null || graphId.isBlank()
            || !Set.of("flow", "function", "command").contains(graphType)) {
            return;
        }
        try {
            ServerResourceLocator resource = new ServerResourceLocator(serverId,
                ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(graphType)), graphId);
            repository.reconcile(resource);
        } catch (RuntimeException failure) {
            Log.warn("Compiled plan invalidation could not reconcile " + graphType + ":" + graphId + ": "
                + failure.getMessage());
        }
        if ("flow".equals(graphType)) {
            globalTriggers.refreshGraph(graphId);
        }
    }

    public CompiledRuntimeDiagnostics compiledRuntimeDiagnostics() {
        CompiledCoreFlowExecutionBridge bridge = compiledBridge;
        ServerCompiledPlanRepository repository = compiledPlanRepository;
        return new CompiledRuntimeDiagnostics(repository == null ? 0 : repository.cachedPlanCount(),
            bridge == null ? 0L : bridge.templatePreparationCount(),
            bridge == null ? 0 : bridge.activeInvocationCount(),
            repository == null ? 0 : repository.activeLeaseCount());
    }

    public record CompiledRuntimeDiagnostics(int residentPlans, long templatePreparations, int activeInvocations,
                                             int activePlanLeases) {
    }

    private void runLiveRefresh(Runnable refresh) {
        if (Bukkit.isPrimaryThread()) {
            refresh.run();
            return;
        }
        try {
            Bukkit.getScheduler().callSyncMethod(moduleContext.getPlugin(), () -> {
                refresh.run();
                return null;
            }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Live resource refresh was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Live resource refresh failed", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Live resource refresh timed out", exception);
        }
    }

    private void registerAutomationCatalog(OptionCatalogRegistry registry, ReSyncJsonResourceStorage storage, String type) {
        AutomationCatalogProvider provider = new AutomationCatalogProvider(storage, type);
        provider.refresh();
        if (registry.register(provider)) {
            automationCatalogProviders.put(type, provider);
        }
    }

    private void refreshAutomationCatalog(AutomationCatalogProvider provider) {
        if (stopped || stopPending || automationCatalogProviders.get(provider.type()) != provider) {
            return;
        }
        try {
            provider.refresh();
            FlowModule module = delegate;
            if (module != null) {
                module.broadcastOptionCatalog(provider.sourceId());
            }
        } catch (RuntimeException failure) {
            Log.error("Automation option catalog refresh failed for " + provider.type(), failure);
        }
    }

    private OptionCatalogItem automationCatalogItem(String type, ReSyncJsonResourceStorage.ResourceSnapshotValue value) {
        String id = value.id();
        JsonObject payload = value.value();
        return switch (type) {
            case ReSyncResourceCatalog.VARIABLE_DEFINITION -> {
                VariableDefinition definition = VariableDefinition.from(payload, id);
                String persistence = definition.persistent() ? "Persistent" : "Runtime";
                String typeName = displayType(definition.valueType().getTypeId());
                yield new OptionCatalogItem(id, definition.name(),
                    typeName + " · " + displayType(definition.scope().name()) + " · " + persistence,
                    "server", "Variables", Map.of(
                        "resourceType", type,
                        "valueType", definition.valueType().toString(),
                        "scope", definition.scope().name().toLowerCase(),
                        "persistent", definition.persistent(),
                        "description", definition.description()
                    ));
            }
            case ReSyncResourceCatalog.TIMER_DEFINITION -> {
                TimerDefinition definition = TimerDefinition.from(payload, id);
                String persistence = definition.persistent() ? "Persistent" : "Runtime";
                yield new OptionCatalogItem(id, definition.name(),
                    definition.defaultDuration() + " " + displayType(definition.defaultUnit().name()) + " · "
                        + displayType(definition.scope().name()) + " · " + persistence,
                    "server", "Timers", Map.of(
                        "resourceType", type,
                        "scope", definition.scope().name().toLowerCase(),
                        "persistent", definition.persistent(),
                        "defaultDuration", definition.defaultDuration(),
                        "defaultUnit", definition.defaultUnit().name().toLowerCase(),
                        "tickInterval", definition.tickInterval(),
                        "description", definition.description()
                    ));
            }
            case ReSyncResourceCatalog.SCHEDULE_DEFINITION -> {
                ScheduleDefinition definition = ScheduleDefinition.from(payload, id);
                String persistence = definition.persistent() ? "Persistent" : "Runtime";
                String target = scheduleTimingSummary(definition) + " · " + displayType(definition.targetType().name());
                yield new OptionCatalogItem(id, definition.name(),
                    target + " · " + displayType(definition.scope().name()) + " · " + persistence,
                    "server", "Schedules", Map.of(
                        "resourceType", type,
                        "scope", definition.scope().name().toLowerCase(),
                        "persistent", definition.persistent(),
                        "targetType", definition.targetType().name().toLowerCase(),
                        "targetId", definition.targetId(),
                        "timingMode", definition.timingMode().name().toLowerCase(),
                        "timingSummary", scheduleTimingSummary(definition),
                        "description", definition.description()
                    ));
            }
            default -> throw new IllegalArgumentException("Unknown automation catalog: " + type);
        };
    }

    private final class AutomationCatalogProvider implements OptionCatalogRegistry.PreparedCaptureProvider {
        private final ReSyncJsonResourceStorage storage;
        private final String type;
        private volatile PreparedAutomationCatalog prepared;

        private AutomationCatalogProvider(ReSyncJsonResourceStorage storage, String type) {
            this.storage = Objects.requireNonNull(storage, "Automation catalog storage is required");
            this.type = Objects.requireNonNull(type, "Automation catalog type is required");
        }

        @Override
        public String sourceId() {
            return "server:resync:" + type;
        }

        @Override
        public CaptureAffinity captureAffinity() {
            return CaptureAffinity.IO;
        }

        @Override
        public OptionCatalogCapture capture(OptionCatalogQuery query) {
            return refresh();
        }

        @Override
        public OptionCatalogCapture preparedCapture(OptionCatalogQuery query) {
            PreparedAutomationCatalog current = prepared;
            if (current == null) {
                return unavailable("Automation option catalog has not been captured");
            }
            try {
                return storage.isCurrent(current.source()) ? current.capture()
                    : unavailable("Automation option catalog is awaiting an authoritative refresh");
            } catch (RuntimeException failure) {
                return unavailable("Automation option catalog authority is unavailable");
            }
        }

        @Override
        public String revision() {
            return preparedCapture(new OptionCatalogQuery(sourceId(), Map.of())).revision();
        }

        @Override
        public String revision(OptionCatalogQuery query) {
            return preparedCapture(query).revision();
        }

        @Override
        public List<String> values() {
            return preparedCapture(new OptionCatalogQuery(sourceId(), Map.of())).values();
        }

        @Override
        public List<String> values(OptionCatalogQuery query) {
            return preparedCapture(query).values();
        }

        @Override
        public List<OptionCatalogItem> items() {
            return preparedCapture(new OptionCatalogQuery(sourceId(), Map.of())).items();
        }

        @Override
        public List<OptionCatalogItem> items(OptionCatalogQuery query) {
            return preparedCapture(query).items();
        }

        @Override
        public String status(OptionCatalogQuery query) {
            return preparedCapture(query).status();
        }

        @Override
        public String diagnostic(OptionCatalogQuery query) {
            return preparedCapture(query).diagnostic();
        }

        private OptionCatalogCapture refresh() {
            ReSyncJsonResourceStorage.ResourceSnapshot source = storage.readSnapshot(type);
            List<OptionCatalogItem> items = source.values().stream()
                .map(value -> automationCatalogItem(type, value))
                .toList();
            OptionCatalogCapture capture = new OptionCatalogCapture(type + ":" + source.revision(), items, "available", "");
            prepared = new PreparedAutomationCatalog(source, capture);
            return capture;
        }

        private OptionCatalogCapture unavailable(String diagnostic) {
            return new OptionCatalogCapture(type + ":unavailable", List.of(), "unavailable", diagnostic);
        }

        private String type() {
            return type;
        }
    }

    private record PreparedAutomationCatalog(ReSyncJsonResourceStorage.ResourceSnapshot source,
                                             OptionCatalogCapture capture) {
        private PreparedAutomationCatalog {
            Objects.requireNonNull(source, "Automation catalog source snapshot is required");
            Objects.requireNonNull(capture, "Automation option catalog capture is required");
        }
    }

    private String scheduleTimingSummary(ScheduleDefinition definition) {
        return switch (definition.timingMode()) {
            case AFTER_DELAY -> "After " + definition.duration() + " " + displayType(definition.unit().name());
            case AT_TIME -> definition.dateTime() + " " + definition.timeZone();
            case REPEATING -> "Every " + definition.duration() + " " + displayType(definition.unit().name());
            case CRON -> definition.cron() + " " + definition.timeZone();
        };
    }

    private String displayType(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        return Arrays.stream(parts).filter(part -> !part.isBlank())
            .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1)).collect(Collectors.joining(" "));
    }

    private void registerNetworkCatalog(OptionCatalogRegistry registry, ReSync plugin) {
        registry.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:network_node";
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.CALLER;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                ReSyncNetworkAgent agent = plugin.getNetworkAgent();
                if (agent == null) {
                    return new OptionCatalogCapture("network_node:unavailable", List.of(), "unavailable",
                        "Network Agent Is Not Configured");
                }
                Map<String, NetworkNodePresence> presence = agent.presenceSnapshot();
                boolean connected = agent.connected();
                List<NetworkNodePresence> ordered = presence.values().stream()
                    .sorted(Comparator.comparing(NetworkNodePresence::nodeId)).toList();
                List<String> fingerprints = ordered.stream().map(value -> value.nodeId() + ":" + value.status() + ":"
                    + value.players() + ":" + value.capacity() + ":" + value.observedAt()).toList();
                List<OptionCatalogItem> capturedItems = ordered.stream().map(value -> new OptionCatalogItem(value.nodeId(),
                    value.nodeId(), value.status().name() + " • " + value.players() + "/" + value.capacity() + " Players",
                    "server", networkServerGroup(value.status()), Map.of("status", value.status().name(), "players", value.players(),
                    "capacity", value.capacity(), "tps", value.tps(), "mspt", value.mspt(), "observedAt", value.observedAt()))).toList();
                return new OptionCatalogCapture(agent.networkId() + ":" + fingerprints.hashCode(), capturedItems,
                    connected ? "available" : "unavailable", connected ? "" : "Network Agent Is Disconnected");
            }

            @Override
            public String revision() {
                ReSyncNetworkAgent agent = plugin.getNetworkAgent();
                if (agent == null) {
                    return "network_node:unavailable";
                }
                List<String> fingerprints = agent.presenceSnapshot().values().stream().sorted(Comparator.comparing(NetworkNodePresence::nodeId))
                    .map(presence -> presence.nodeId() + ":" + presence.status() + ":" + presence.players() + ":" + presence.capacity()
                        + ":" + presence.observedAt()).toList();
                return agent.networkId() + ":" + fingerprints.hashCode();
            }

            @Override
            public List<String> values() {
                ReSyncNetworkAgent agent = plugin.getNetworkAgent();
                return agent == null ? List.of() : agent.presenceSnapshot().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }

            @Override
            public List<OptionCatalogItem> items() {
                ReSyncNetworkAgent agent = plugin.getNetworkAgent();
                if (agent == null) {
                    return List.of();
                }
                return agent.presenceSnapshot().values().stream().sorted(Comparator.comparing(NetworkNodePresence::nodeId)).map(presence ->
                    new OptionCatalogItem(presence.nodeId(), presence.nodeId(), presence.status().name() + " • " + presence.players() + "/"
                        + presence.capacity() + " Players", "server", networkServerGroup(presence.status()), Map.of(
                            "status", presence.status().name(),
                            "players", presence.players(),
                            "capacity", presence.capacity(),
                            "tps", presence.tps(),
                            "mspt", presence.mspt(),
                            "observedAt", presence.observedAt()
                        ))).toList();
            }

            @Override
            public FlowTypeRef runtimeDataType() {
                return FlowTypeRef.simple("network_node");
            }

            @Override
            public Class<?> runtimeDataClass() {
                return FlowResourceReference.class;
            }

            @Override
            public Object resolveRuntimeData(String value) {
                ReSyncNetworkAgent agent = plugin.getNetworkAgent();
                NetworkNodePresence presence = agent != null ? agent.presenceSnapshot().get(value) : null;
                Map<String, Object> metadata = presence == null ? Map.of() : Map.of(
                    "status", presence.status().name(),
                    "players", presence.players(),
                    "capacity", presence.capacity(),
                    "tps", presence.tps(),
                    "mspt", presence.mspt(),
                    "observedAt", presence.observedAt()
                );
                return new FlowResourceReference("network_node", value, "network", presence != null, metadata);
            }

            @Override
            public String status(OptionCatalogQuery query) {
                ReSyncNetworkAgent agent = plugin.getNetworkAgent();
                return agent != null && agent.connected() ? "available" : "unavailable";
            }

            @Override
            public String diagnostic(OptionCatalogQuery query) {
                ReSyncNetworkAgent agent = plugin.getNetworkAgent();
                return agent == null ? "Network Agent Is Not Configured" : agent.connected() ? "" : "Network Agent Is Disconnected";
            }
        });
        registry.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:network_server_group";
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.CALLER;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                return new OptionCatalogCapture(revision(), items(), "available", "");
            }

            @Override
            public String revision() {
                return "network_server_group:v1";
            }

            @Override
            public List<String> values() {
                return List.of("All Servers", "Online Servers", "Offline Servers");
            }

            @Override
            public List<OptionCatalogItem> items() {
                return List.of(
                    new OptionCatalogItem("All Servers", "All Servers", "Every server currently known to the network.", "server", "All Servers", Map.of()),
                    new OptionCatalogItem("Online Servers", "Online Servers", "Servers that are online, draining, or in maintenance.", "server", "Online Servers", Map.of()),
                    new OptionCatalogItem("Offline Servers", "Offline Servers", "Servers that are offline or revoked.", "server", "Offline Servers", Map.of())
                );
            }

            @Override
            public String status(OptionCatalogQuery query) {
                return "available";
            }

            @Override
            public String diagnostic(OptionCatalogQuery query) {
                return "";
            }
        });
        ReSyncNetworkAgent networkAgent = plugin.getNetworkAgent();
        if (networkAgent != null && networkOptionCatalogListener == null) {
            networkOptionCatalogListener = new ReSyncNetworkAgent.Listener() {
                @Override
                public void onPresenceChanged(NetworkNodePresence presence) {
                    delegate.broadcastOptionCatalog("server:resync:network_node");
                }

                @Override
                public void onConnected() {
                    delegate.broadcastOptionCatalog("server:resync:network_node");
                }

                @Override
                public void onDisconnected() {
                    delegate.broadcastOptionCatalog("server:resync:network_node");
                }
            };
            networkOptionCatalogAgent = networkAgent;
            networkAgent.addListener(networkOptionCatalogListener);
        }
    }

    private String networkServerGroup(NetworkNodeStatus status) {
        return isOnlineNetworkServer(status) ? "Online Servers" : "Offline Servers";
    }

    private boolean isOnlineNetworkServer(NetworkNodeStatus status) {
        return status == NetworkNodeStatus.ONLINE || status == NetworkNodeStatus.DRAINING || status == NetworkNodeStatus.MAINTENANCE;
    }

    private void registerCoreResourceCatalogs(OptionCatalogRegistry registry, FlowStorage flowStorage, CustomContentStorage contentStorage,
                                              WorldGenProjectStorage worldGenStorage, WorldManagementService worldManagementService) {
        registerResourceCatalog(registry, ReSyncResourceCatalog.FLOW, OptionCatalogProvider.CaptureAffinity.IO,
            () -> flowStorage.listGraphIds(ReSyncResourceCatalog.FLOW));
        registerResourceCatalog(registry, ReSyncResourceCatalog.FUNCTION, OptionCatalogProvider.CaptureAffinity.IO,
            () -> flowStorage.listGraphIds(ReSyncResourceCatalog.FUNCTION));
        registerResourceCatalog(registry, ReSyncResourceCatalog.COMMAND, OptionCatalogProvider.CaptureAffinity.IO,
            () -> flowStorage.listGraphIds(ReSyncResourceCatalog.COMMAND));
        registerResourceCatalog(registry, ReSyncResourceCatalog.GUI, OptionCatalogProvider.CaptureAffinity.IO, flowStorage::listGuiIds);
        registerResourceCatalog(registry, ReSyncResourceCatalog.SCOREBOARD, OptionCatalogProvider.CaptureAffinity.IO, flowStorage::listScoreboardIds);
        registerResourceCatalog(registry, ReSyncResourceCatalog.TAB, OptionCatalogProvider.CaptureAffinity.IO, flowStorage::listTabIds);
        registerResourceCatalog(registry, ReSyncResourceCatalog.PROJECT_METADATA, OptionCatalogProvider.CaptureAffinity.IO,
            flowStorage::listProjectMetadataIds);
        registerResourceCatalog(registry, ReSyncResourceCatalog.CUSTOM_CONTENT, OptionCatalogProvider.CaptureAffinity.IO, contentStorage::listIds);
        StructureLibrary structures = moduleContext.getRequiredService(StructureLibrary.class);
        registerResourceCatalog(registry, ReSyncResourceCatalog.STRUCTURE, OptionCatalogProvider.CaptureAffinity.CALLER,
            () -> structures.list().stream().map(value -> value.id()).toList());
        if (worldGenStorage != null) {
            registerResourceCatalog(registry, ReSyncResourceCatalog.WORLDGEN, OptionCatalogProvider.CaptureAffinity.IO,
                worldGenStorage::listProjectIds);
        }
        if (worldManagementService != null) {
            registerResourceCatalog(registry, ReSyncResourceCatalog.WORLD, OptionCatalogProvider.CaptureAffinity.SERVER_MAIN,
                () -> worldManagementService.createSnapshot().getWorlds().stream()
                .map(value -> value != null ? value.getWorldName() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
            registerResourceCatalog(registry, "world_generator", OptionCatalogProvider.CaptureAffinity.SERVER_MAIN,
                () -> worldManagementService.createSnapshot().getGeneratorDescriptors().stream()
                .map(value -> value != null ? value.getId() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        }
    }

    private void registerResourceCatalog(OptionCatalogRegistry registry, String type,
                                         OptionCatalogProvider.CaptureAffinity affinity, Supplier<List<String>> values) {
        registry.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:" + type;
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return affinity;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                List<String> capturedIds = List.copyOf(values.get());
                return new OptionCatalogCapture(resourceCatalogRevision(type, capturedIds), resourceCatalogItems(type, capturedIds),
                    "available", "");
            }

            @Override
            public String revision() {
                return resourceCatalogRevision(type, values.get());
            }

            @Override
            public List<String> values() {
                return values.get();
            }

            @Override
            public List<OptionCatalogItem> items() {
                return resourceCatalogItems(type, values());
            }
        });
    }

    private List<OptionCatalogItem> resourceCatalogItems(String type, List<String> ids) {
        ReSyncManagedResource resource = ReSyncResourceCatalog.byType(type);
        String resourceName = resource != null ? resource.displayName() : type;
        return ids.stream().map(id -> new OptionCatalogItem(id, id, resourceName, "resource", resourceName,
            Map.of("resourceType", type, "owner", "server", "available", true))).toList();
    }

    static String resourceCatalogRevision(String type, List<String> ids) {
        List<String> sortedIds = new ArrayList<>(ids != null ? ids : List.of());
        sortedIds.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        return type + ":" + sortedIds.size() + ":" + String.join(",", sortedIds);
    }

    private static Map<String, Long> initializationTiming(long started, long cpuStarted) {
        long wallMillis = started <= 0L ? 0L
            : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started));
        long cpuMillis = cpuStarted <= 0L ? 0L
            : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, currentThreadCpuNanos() - cpuStarted));
        return Map.of("wallMs", wallMillis, "cpuMs", cpuMillis);
    }

    private static void reportInitializationStage(String stageName, long started, long cpuStarted) {
        TemporaryLifecycleDiagnostics.event("flow_initialize_stage", started,
            TemporaryLifecycleDiagnostics.with(Map.of(
                "moduleId", "flow",
                "stageName", stageName,
                "outcome", "complete",
                "participantTimings", initializationTiming(started, cpuStarted))));
    }

    private static long currentThreadCpuNanos() {
        return THREAD_CPU.isCurrentThreadCpuTimeSupported()
            ? Math.max(0L, THREAD_CPU.getCurrentThreadCpuTime()) : 0L;
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        delegate.onSubscribe(session, req);
    }

    @Override
    public void onUnsubscribe(Session session, UnsubscribeRequest req) {
        delegate.onUnsubscribe(session, req);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        delegate.onData(session, req);
    }

    @Override
    public void onTick() {
        if (stopPending || stopped) {
            return;
        }
        if (customFunctionDefinitionRefreshPending.getAndSet(false)) {
            nodeDefinitionReloadPending = true;
        }
        if (nodeDefinitionReloadPending || nodeDefinitionReloadRequested.get()) {
            reloadNodeDefinitions();
        }
        delegate.onTick();
    }

    @Override
    public void cleanup(Session session) {
        delegate.cleanup(session);
    }
}
