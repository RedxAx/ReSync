package restudio.resync.modules;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowDataObjectAdapter;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowJobReference;
import restudio.resync.ReSync;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.ReSyncExtensionData;
import restudio.resync.api.ReSyncExtensionManager;
import restudio.resync.api.RuntimeDataRegistry;
import restudio.resync.customcontent.CustomContentAccess;
import restudio.resync.customcontent.CustomContentListener;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customization.ReSyncJsonResourceStorage.ResourceListener;
import restudio.resync.core.Session;
import restudio.resync.dialog.DialogService;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowNodeAuditRecord;
import restudio.resync.flow.diagnostics.FlowDebugService;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.ScoreboardRuntimeListener;
import restudio.resync.flow.SystemEventListener;
import restudio.resync.flow.TabListService;
import restudio.flow.data.TypeRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.automation.ScheduleDefinition;
import restudio.resync.flow.automation.TimerDefinition;
import restudio.resync.flow.automation.VariableDefinition;
import restudio.resync.flow.automation.VariableService;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.BlockActionHandler;
import restudio.resync.flow.handler.generic.AbilityEffectHandler;
import restudio.resync.flow.handler.generic.ChatHandler;
import restudio.resync.flow.handler.generic.ColorHandler;
import restudio.resync.flow.handler.generic.ConversionHandler;
import restudio.resync.flow.migration.FlowGraphMigrator;
import restudio.resync.flow.migration.TypedAutomationGraphMigrator;
import restudio.resync.flow.handler.generic.CustomEventHandler;
import restudio.resync.flow.handler.generic.CustomContentHandler;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.handler.generic.DebugHandler;
import restudio.resync.flow.handler.generic.DiscordHandler;
import restudio.resync.flow.handler.generic.EconomyHandler;
import restudio.resync.flow.handler.generic.EntityActionHandler;
import restudio.resync.flow.handler.generic.FileHandler;
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
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.flow.handler.family.JsonFamilyHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.network.NetworkFlowBridge;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.flow.validation.FlowGraphValidator;
import restudio.resync.flow.validation.FlowGraphValidationRegistry;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.messages.MessageLogService;
import restudio.resync.modules.flow.FlowPacketSender;
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
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldManagementListener;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.WorldGenOperationService;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FlowRuntimeModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("flow", "Flow", "flow").withDependencies("flowJobs", "playerNpcPackets", "worldGen", "worldManagement");
    private FlowModule delegate;
    private FlowStorage storage;
    private FlowExecutor executor;
    private GuiManager guiManager;
    private GlobalTriggers globalTriggers;
    private NetworkFlowBridge networkFlowBridge;
    private SystemEventListener systemEventListener;
    private ScoreboardRuntimeListener scoreboardRuntimeListener;
    private PropertyRegistry propertyRegistry;
    private CustomContentStorage customContentStorage;
    private CustomContentService customContentService;
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
    private AutomationTaskService automationTasks;
    private VariableService automationVariables;
    private ScheduleHandler scheduleHandler;
    private FlowGraphValidationRegistry graphValidationRegistry;
    private HandlerRegistry handlerRegistry;
    private List<NodeDefinitionDiagnostic> nodeDefinitionDiagnostics = List.of();
    private ResourceListener jsonResourceListener;
    private JsonRuntimeResourceValidator jsonResourceValidator;
    private ItemAttributeSchemaService itemAttributeSchemaService;
    private WorldGenProjectStorage worldGenStorage;
    private WorldGenOperationService worldGenOperations;
    private FlowJobRegistry flowJobs;
    private WorldManagementService worldManagementService;
    private WorldManagementListener worldResourceListener;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.moduleContext = context;
        storage = new FlowStorage(context.getPlugin());
        itemAttributeSchemaService = new ItemAttributeSchemaService();
        customContentStorage = new CustomContentStorage(context.getPlugin(), itemAttributeSchemaService);
        customContentStorage.preloadAll();
        jsonResourceStorage = context.getRequiredService(ReSyncJsonResourceStorage.class);
        optionCatalogRegistry = context.getRequiredService(OptionCatalogRegistry.class);
        runtimeDataRegistry = optionCatalogRegistry.runtimeData();
        resourceRegistry = new FlowResourceRegistry();
        resourceRegistry.setLiveRefreshExecutor(this::runLiveRefresh);
        valueCodecs = new FlowValueCodecRegistry();
        automationDefinitions = new AutomationDefinitionRegistry(jsonResourceStorage);
        automationTasks = new AutomationTaskService(context.getPlugin(), automationDefinitions);
        automationVariables = new VariableService(automationDefinitions, valueCodecs, context.getPlugin());
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
        TypeAdapterRegistry typeAdapterRegistry = new TypeAdapterRegistry();
        handlerRegistry = new HandlerRegistry();
        FlowRegistry flowRegistry = new FlowRegistry();
        flowRegistry.setHandlerRegistry(handlerRegistry);
        propertyRegistry = new PropertyRegistry();
        registerNodeHandlers(handlerRegistry);
        TypeRegistry typeRegistry = new TypeRegistry();
        FlowDataObjectAdapter.setTypeRegistry(typeRegistry);
        NodeDefinitionRegistry nodeDefinitionRegistry = new NodeDefinitionRegistry();
        NodeDefinitionLoader jsonLoader = new NodeDefinitionLoader();
        jsonLoader.setValidator(new NodeDefinitionValidator(handlerRegistry, optionCatalogRegistry, true));
        List<NodeDefinition> classpathDefs = jsonLoader.loadFromClasspath("nodes");
        removeUnavailable(classpathDefs, jsonLoader);
        jsonLoader.validateAndRegister(classpathDefs, nodeDefinitionRegistry, handlerRegistry, "json-classpath");
        Path nodesDir = context.getPlugin().getDataFolder().toPath().resolve("nodes");
        if (Files.exists(nodesDir)) {
            List<NodeDefinition> jsonDefs = jsonLoader.loadFromDirectory(nodesDir);
            removeUnavailable(jsonDefs, jsonLoader);
            jsonLoader.validateAndRegister(jsonDefs, nodeDefinitionRegistry, handlerRegistry, "json");
        }
        propertyRegistry.loadNodeDefinitions(nodeDefinitionRegistry.getAllDefinitions().values());
        nodeDefinitionDiagnostics = jsonLoader.getDiagnostics();
        graphValidationRegistry = new FlowGraphValidationRegistry();
        FlowGraphValidator graphValidator = new FlowGraphValidator(nodeDefinitionRegistry, handlerRegistry, typeAdapterRegistry, optionCatalogRegistry,
            resourceRegistry, graphValidationRegistry);
        storage.setGraphValidator(graphValidator);
        traceService = new FlowTraceService(500);
        debugService = new FlowDebugService(traceService);
        executor = new FlowExecutor(handlerRegistry, nodeDefinitionRegistry, typeAdapterRegistry, new HashMap<>());
        executor.setTraceService(traceService);
        executor.setDebugService(debugService);
        executor.setGraphValidator(graphValidator);
        executor.setExecutionAuthority(storage::isExecutionAuthorized);
        storage.setGraphChangeListener(executor::cancelPendingTasks);
        customContentService = new CustomContentService(customContentStorage, storage, executor, itemAttributeSchemaService);
        runtimeDataRegistry.register(new CustomContentItemDataAdapter(customContentStorage, customContentService));
        runtimeDataRegistry.register(new ExternalItemDataAdapter(customContentService));
        jsonResourceValidator = new JsonRuntimeResourceValidator(customContentService, valueCodecs);
        jsonResourceStorage.addInterceptor(jsonResourceValidator);
        customContentListener = new CustomContentListener(customContentStorage, customContentService);
        FlowResourcePacketRouter resourceBootstrap = new FlowResourcePacketRouter(storage, customContentStorage, customContentService, jsonResourceStorage, null, null, null, resourceRegistry, ignored -> {
        }, itemAttributeSchemaService);
        resourceBootstrap.registerExternalLifecycle(worldGenStorage, worldManagementService);
        storage.preloadAll();
        CustomFunctionNodeDefinitions.rebuild(nodeDefinitionRegistry, storage);
        new FlowGraphMigrator(storage, nodeDefinitionRegistry).migrateStoredFlows();
        new TypedAutomationGraphMigrator(storage, jsonResourceStorage, nodeDefinitionRegistry).migrateStoredFlows();
        CustomFunctionNodeDefinitions.rebuild(nodeDefinitionRegistry, storage);
        RuntimeFlowDispatcher runtimeFlowDispatcher = new RuntimeFlowDispatcher(storage, executor);
        lootTableService = new LootTableService(jsonResourceStorage, customContentService, runtimeFlowDispatcher);
        tradeProfileService = new TradeProfileService(jsonResourceStorage, customContentService, runtimeFlowDispatcher, context.getPlugin());
        TriggerRegistry triggerRegistry = new TriggerRegistry(context.getPlugin());
        globalTriggers = new GlobalTriggers(storage, executor, triggerRegistry, context.getRequiredService(ReTextService.class));
        networkFlowBridge = new NetworkFlowBridge(context.getPlugin());
        FlowEventRegistry flowEventRegistry = new FlowEventRegistry(globalTriggers.getTriggerDispatcher(), typeAdapterRegistry);
        flowEventRegistry.registerFromJson(new ArrayList<>(nodeDefinitionRegistry.getAllDefinitions().values()));
        automationTasks.restorePersistentTimers();
        scheduleHandler.restorePersistentSchedules(executor);
        systemEventListener = new SystemEventListener(storage, executor, triggerRegistry);
        globalTriggers.setSystemEventListener(systemEventListener);
        globalTriggers.refreshBindings();
        int channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        delegate = new FlowModule(storage, context.getCodec(), channelId, triggerRegistry, globalTriggers, flowRegistry, nodeDefinitionRegistry, propertyRegistry, customContentStorage, customContentService,
            context.getService(ReSyncExtensionData.class), optionCatalogRegistry, jsonResourceStorage, context.getService(MessageLogService.class),
            context.getRequiredService(PlayerSessionLinkService.class), builtinOptionCatalogs, resourceRegistry, valueCodecs, flowJobs);
        delegate.setNodeRegistryDiagnosticsSupplier(this::nodeRegistryDiagnostics);
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
        guiManager = new GuiManager(context.getServer(), storage, executor, delegate);
        context.registerService(FlowStorage.class, storage);
        context.registerService(CustomContentStorage.class, customContentStorage);
        context.registerService(CustomContentService.class, customContentService);
        context.registerService(ItemAttributeSchemaService.class, itemAttributeSchemaService);
        context.registerService(HandlerRegistry.class, handlerRegistry);
        context.registerService(TypeRegistry.class, typeRegistry);
        context.registerService(TypeAdapterRegistry.class, typeAdapterRegistry);
        context.registerService(FlowRegistry.class, flowRegistry);
        context.registerService(NodeDefinitionRegistry.class, nodeDefinitionRegistry);
        context.registerService(TriggerRegistry.class, triggerRegistry);
        context.registerService(FlowExecutor.class, executor);
        context.registerService(FlowTraceService.class, traceService);
        context.registerService(FlowDebugService.class, debugService);
        context.registerService(FlowGraphValidator.class, graphValidator);
        context.registerService(FlowGraphValidationRegistry.class, graphValidationRegistry);
        context.registerService(FlowResourceRegistry.class, resourceRegistry);
        context.registerService(FlowValueCodecRegistry.class, valueCodecs);
        context.registerService(AutomationDefinitionRegistry.class, automationDefinitions);
        context.registerService(AutomationTaskService.class, automationTasks);
        context.registerService(VariableService.class, automationVariables);
        context.registerService(RuntimeDataRegistry.class, runtimeDataRegistry);
        context.registerService(NetworkFlowBridge.class, networkFlowBridge);
        context.registerService(FlowModule.class, delegate);
        context.registerService(LootTableService.class, lootTableService);
        context.registerService(TradeProfileService.class, tradeProfileService);
        context.registerService(GuiManager.class, guiManager);
        context.registerService(FlowRuntimeModule.class, this);
        FlowRuntimeAccess.configure(context.getPlugin(), () -> storage, () -> executor != null ? executor.getGlobalVariables() : null);
        FlowPacketSender editStateSender = new FlowPacketSender(context.getCodec(), channelId, Set.of(), flowJobs);
        DialogService dialogService = new DialogService(
            context.getPlugin(),
            context.getRequiredService(ReSyncJsonResourceStorage.class),
            storage,
            executor,
            editStateSender::sendEditTargetState,
            context.getRequiredService(PlayerSessionLinkService.class)
        );
        context.registerService(DialogService.class, dialogService);
        npcService = new NpcService(context.getPlugin(), jsonResourceStorage, customContentService, runtimeFlowDispatcher, tradeProfileService, lootTableService, dialogService,
            context.getRequiredService(PlayerNpcRuntime.class));
        context.registerService(NpcService.class, npcService);
        jsonResourceListener = (type, id, value, deleted) -> {
            if (ReSyncResourceCatalog.NPC_DEFINITION.equals(type)) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> npcService.reload(id, value, deleted));
                return;
            }
            if (ReSyncResourceCatalog.TRADE_PROFILE.equals(type)) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> tradeProfileService.reload(id, deleted));
            }
        };
        jsonResourceStorage.addListener(jsonResourceListener);
        ScoreboardTemplateManager.configureEditStateBridge(editStateSender::sendEditTargetState, context.getRequiredService(PlayerSessionLinkService.class));
        CustomContentAccess.configure(customContentStorage, customContentService);
        ReSyncRuntimeContentAccess.configure(lootTableService, tradeProfileService, npcService);
    }

    public void reloadNodeDefinitions() {
        if (executor != null) {
            executor.cancelPendingTasks();
        }
        HandlerRegistry handlerRegistry = moduleContext.getRequiredService(HandlerRegistry.class);
        NodeDefinitionRegistry nodeDefinitionRegistry = moduleContext.getRequiredService(NodeDefinitionRegistry.class);
        TypeRegistry typeRegistry = moduleContext.getRequiredService(TypeRegistry.class);
        typeRegistry.clear();
        FlowDataObjectAdapter.setTypeRegistry(typeRegistry);
        nodeDefinitionRegistry.clear();
        handlerRegistry.clear();
        propertyRegistry.clear();
        registerNodeHandlers(handlerRegistry);
        NodeDefinitionLoader jsonLoader = new NodeDefinitionLoader();
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlerRegistry, optionCatalogRegistry, true);
        jsonLoader.setValidator(validator);
        List<NodeDefinition> classpathDefs = jsonLoader.loadFromClasspath("nodes");
        removeUnavailable(classpathDefs, jsonLoader);
        jsonLoader.validateAndRegister(classpathDefs, nodeDefinitionRegistry, handlerRegistry, "json-classpath");
        Path nodesDir = moduleContext.getPlugin().getDataFolder().toPath().resolve("nodes");
        if (Files.exists(nodesDir)) {
            List<NodeDefinition> jsonDefs = jsonLoader.loadFromDirectory(nodesDir);
            removeUnavailable(jsonDefs, jsonLoader);
            jsonLoader.validateAndRegister(jsonDefs, nodeDefinitionRegistry, handlerRegistry, "json");
        }
        nodeDefinitionDiagnostics = jsonLoader.getDiagnostics();
        ReSyncExtensionManager extensionManager = moduleContext.getService(ReSyncExtensionManager.class);
        if (extensionManager != null) {
            extensionManager.reloadExtensions();
        }
        if (delegate != null) {
            delegate.refreshCustomFunctionDefinitions();
        }
    }

    public Map<String, Object> nodeRegistryDiagnostics() {
        NodeDefinitionRegistry nodeDefinitionRegistry = moduleContext.getRequiredService(NodeDefinitionRegistry.class);
        HandlerRegistry handlerRegistry = moduleContext.getRequiredService(HandlerRegistry.class);
        Map<String, Object> diagnostics = new HashMap<>();
        List<String> definitionSets = nodeDefinitionRegistry.getPluginIds();
        definitionSets.sort(String.CASE_INSENSITIVE_ORDER);
        ReSyncExtensionManager extensionManager = moduleContext.getService(ReSyncExtensionManager.class);
        List<String> externalPlugins = extensionManager != null ? new ArrayList<>(extensionManager.getPluginIds()) : new ArrayList<>();
        externalPlugins.sort(String.CASE_INSENSITIVE_ORDER);
        diagnostics.put("definitions", nodeDefinitionRegistry.getAllDefinitions().size());
        diagnostics.put("definitionSets", definitionSets.size());
        diagnostics.put("definitionSetIds", definitionSets);
        diagnostics.put("externalNodePlugins", externalPlugins.size());
        diagnostics.put("externalNodePluginIds", externalPlugins);
        diagnostics.put("checksum", delegate != null ? delegate.getNodeRegistryChecksum() : "");
        diagnostics.put("flowClients", delegate != null ? delegate.getSubscribedSessionCount() : 0);
        Map<String, Integer> categoryCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> referencedHandlers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> missingHandlers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> missingOperations = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> referencedCatalogs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> missingCatalogs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (NodeDefinition definition : nodeDefinitionRegistry.getAllDefinitions().values()) {
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
        long executableDefinitions = nodeDefinitionRegistry.getAllDefinitions().values().stream().filter(definition -> isExecutable(definition, handlerRegistry)).count();
        long migrationOnlyDefinitions = nodeDefinitionRegistry.getAllDefinitions().values().stream().filter(this::isMigrationOnly).count();
        long supportedDefinitions = nodeDefinitionRegistry.getAllDefinitions().size() - migrationOnlyDefinitions;
        long executableSupportedDefinitions = nodeDefinitionRegistry.getAllDefinitions().values()
            .stream()
            .filter(definition -> !isMigrationOnly(definition) && isExecutable(definition, handlerRegistry))
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
        List<Map<String, Object>> nodeInventory = buildNodeInventory(nodeDefinitionRegistry, handlerRegistry);
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
            "loaded", nodeDefinitionRegistry.getAllDefinitions().size(),
            "advertised", nodeDefinitionRegistry.getAllDefinitions().size(),
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
        List<NodeDefinition> definitions = new ArrayList<>(registry.getAllDefinitions().values());
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
            item.put("owner", registry.getPluginForNode(definition.getId()));
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
        for (NodeDefinitionDiagnostic diagnostic : nodeDefinitionDiagnostics) {
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
        return List.copyOf(inventory);
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
        new FileHandler().registerTo(handlerRegistry);
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
        new RegionHandler().registerTo(handlerRegistry);
        new ResourceDefinitionHandler().registerTo(handlerRegistry);
        new ScoreboardHandler().registerTo(handlerRegistry);
        new SoundHandler().registerTo(handlerRegistry);
        new ServerHandler().registerTo(handlerRegistry);
        new NetworkFlowHandler().registerTo(handlerRegistry);
        new TeamHandler().registerTo(handlerRegistry);
        new TextFormatHandler().registerTo(handlerRegistry);
        new TextResourceHandler(moduleContext.getRequiredService(ReTextService.class)).registerTo(handlerRegistry);
        scheduleHandler = new ScheduleHandler(storage, Clock.systemUTC(), automationDefinitions, automationTasks, valueCodecs);
        scheduleHandler.registerTo(handlerRegistry);
        new TimerHandler(automationDefinitions, automationTasks).registerTo(handlerRegistry);
        new TitleHandler().registerTo(handlerRegistry);
        new TimeHandler().registerTo(handlerRegistry);
        new UuidHandler().registerTo(handlerRegistry);
        new ColorHandler().registerTo(handlerRegistry);
        new CustomEventHandler().registerTo(handlerRegistry);
        new CustomContentHandler().registerTo(handlerRegistry);
        new CustomFunctionCallHandler().registerTo(handlerRegistry);
        new VariableScopeHandler(automationVariables).registerTo(handlerRegistry);
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
        if (delegate != null) {
            delegate.refreshCustomFunctionDefinitions();
        }
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
        npcService.restorePersistentNpcs();
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
    }

    @Override
    public void stop(ModuleContext context) {
        RuntimeException failure = null;
        WorldManagementListener worldListener = worldResourceListener;
        worldResourceListener = null;
        failure = cleanupFailure(failure, () -> {
            if (worldManagementService != null && worldListener != null) worldManagementService.removeListener(worldListener);
        });
        failure = cleanupFailure(failure, () -> {
            if (worldGenStorage != null) worldGenStorage.setChangeListener(null);
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
        FlowExecutor runtimeExecutor = executor;
        executor = null;
        failure = cleanupFailure(failure, () -> {
            if (runtimeExecutor != null) runtimeExecutor.shutdown();
        });
        HandlerRegistry registry = handlerRegistry;
        handlerRegistry = null;
        failure = cleanupFailure(failure, () -> {
            if (registry != null) registry.clear();
        });
        failure = cleanupFailure(failure, () -> {
            if (globalTriggers != null) globalTriggers.shutdownRuntimeCommands();
        });
        failure = cleanupFailure(failure, () -> {
            if (globalTriggers != null) HandlerList.unregisterAll(globalTriggers);
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
            if (lootTableService != null) HandlerList.unregisterAll(lootTableService);
        });
        failure = cleanupFailure(failure, () -> {
            if (tradeProfileService != null) HandlerList.unregisterAll(tradeProfileService);
        });
        ResourceListener resourceListener = jsonResourceListener;
        jsonResourceListener = null;
        failure = cleanupFailure(failure, () -> {
            if (jsonResourceStorage != null && resourceListener != null) jsonResourceStorage.removeListener(resourceListener);
        });
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
        failure = cleanupFailure(failure, ScoreboardTemplateManager::clearEditStateBridge);
        failure = cleanupFailure(failure, CustomContentAccess::clear);
        failure = cleanupFailure(failure, ReSyncRuntimeContentAccess::clear);
        if (failure != null) throw failure;
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

    private void registerResourceCatalogs(OptionCatalogRegistry registry, ReSyncJsonResourceStorage storage) {
        if (registry == null || storage == null) {
            return;
        }
        for (String type : storage.resourceTypes()) {
            if (Set.of(ReSyncResourceCatalog.VARIABLE_DEFINITION, ReSyncResourceCatalog.TIMER_DEFINITION,
                ReSyncResourceCatalog.SCHEDULE_DEFINITION).contains(type)) {
                registerAutomationCatalog(registry, storage, type);
            } else {
                registerResourceCatalog(registry, type, () -> storage.listIds(type));
            }
        }
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
        registry.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:" + type;
            }

            @Override
            public String revision() {
                List<String> fingerprints = storage.listIds(type).stream().map(id -> {
                    JsonObject value = storage.get(type, id);
                    return id + ":" + (value != null ? value.toString().hashCode() : 0);
                }).toList();
                return type + ":" + fingerprints.hashCode();
            }

            @Override
            public List<String> values() {
                return storage.listIds(type);
            }

            @Override
            public List<OptionCatalogItem> items() {
                return values().stream().map(id -> automationCatalogItem(type, id)).toList();
            }
        });
    }

    private OptionCatalogItem automationCatalogItem(String type, String id) {
        return switch (type) {
            case ReSyncResourceCatalog.VARIABLE_DEFINITION -> {
                VariableDefinition definition = automationDefinitions.variable(id);
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
                TimerDefinition definition = automationDefinitions.timer(id);
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
                ScheduleDefinition definition = automationDefinitions.schedule(id);
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
    }

    private String networkServerGroup(NetworkNodeStatus status) {
        return isOnlineNetworkServer(status) ? "Online Servers" : "Offline Servers";
    }

    private boolean isOnlineNetworkServer(NetworkNodeStatus status) {
        return status == NetworkNodeStatus.ONLINE || status == NetworkNodeStatus.DRAINING || status == NetworkNodeStatus.MAINTENANCE;
    }

    private void registerCoreResourceCatalogs(OptionCatalogRegistry registry, FlowStorage flowStorage, CustomContentStorage contentStorage,
                                              WorldGenProjectStorage worldGenStorage, WorldManagementService worldManagementService) {
        registerResourceCatalog(registry, ReSyncResourceCatalog.FLOW, () -> flowStorage.listGraphIds(ReSyncResourceCatalog.FLOW));
        registerResourceCatalog(registry, ReSyncResourceCatalog.FUNCTION, () -> flowStorage.listGraphIds(ReSyncResourceCatalog.FUNCTION));
        registerResourceCatalog(registry, ReSyncResourceCatalog.COMMAND, () -> flowStorage.listGraphIds(ReSyncResourceCatalog.COMMAND));
        registerResourceCatalog(registry, ReSyncResourceCatalog.GUI, flowStorage::listGuiIds);
        registerResourceCatalog(registry, ReSyncResourceCatalog.SCOREBOARD, flowStorage::listScoreboardIds);
        registerResourceCatalog(registry, ReSyncResourceCatalog.TAB, flowStorage::listTabIds);
        registerResourceCatalog(registry, ReSyncResourceCatalog.PROJECT_METADATA, flowStorage::listProjectMetadataIds);
        registerResourceCatalog(registry, ReSyncResourceCatalog.CUSTOM_CONTENT, contentStorage::listIds);
        if (worldGenStorage != null) {
            registerResourceCatalog(registry, ReSyncResourceCatalog.WORLDGEN, worldGenStorage::listProjectIds);
        }
        if (worldManagementService != null) {
            registerResourceCatalog(registry, ReSyncResourceCatalog.WORLD, () -> worldManagementService.createSnapshot().getWorlds().stream()
                .map(value -> value != null ? value.getWorldName() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
            registerResourceCatalog(registry, "world_generator", () -> worldManagementService.createSnapshot().getGeneratorDescriptors().stream()
                .map(value -> value != null ? value.getId() : "")
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        }
    }

    private void registerResourceCatalog(OptionCatalogRegistry registry, String type, Supplier<List<String>> values) {
        registry.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:" + type;
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
                ReSyncManagedResource resource = ReSyncResourceCatalog.byType(type);
                String resourceName = resource != null ? resource.displayName() : type;
                return values().stream().map(id -> new OptionCatalogItem(id, id, resourceName, "resource", resourceName,
                    Map.of("resourceType", type, "owner", "server", "available", true))).toList();
            }
        });
    }

    static String resourceCatalogRevision(String type, List<String> ids) {
        List<String> sortedIds = new ArrayList<>(ids != null ? ids : List.of());
        sortedIds.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        return type + ":" + sortedIds.size() + ":" + String.join(",", sortedIds);
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
        delegate.onTick();
    }

    @Override
    public void cleanup(Session session) {
        delegate.cleanup(session);
    }
}
