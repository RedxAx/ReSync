package restudio.resync.modules;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.ReSyncExtensionData;
import restudio.resync.api.ReSyncExtensionManager;
import restudio.resync.customcontent.CustomContentAccess;
import restudio.resync.customcontent.CustomContentListener;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customization.ReSyncJsonResourceStorage.ResourceListener;
import restudio.resync.core.Session;
import restudio.resync.dialog.DialogService;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.diagnostics.FlowDebugService;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.ScoreboardRuntimeListener;
import restudio.resync.flow.SystemEventListener;
import restudio.resync.flow.TabListService;
import restudio.flow.data.TypeRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.BlockActionHandler;
import restudio.resync.flow.handler.generic.AbilityEffectHandler;
import restudio.resync.flow.handler.generic.ChatHandler;
import restudio.resync.flow.handler.generic.ColorHandler;
import restudio.resync.flow.handler.generic.ConversionHandler;
import restudio.resync.flow.migration.FlowGraphMigrator;
import restudio.resync.flow.handler.generic.CustomEventHandler;
import restudio.resync.flow.handler.generic.CustomContentHandler;
import restudio.resync.flow.handler.generic.DebugHandler;
import restudio.resync.flow.handler.generic.DiscordHandler;
import restudio.resync.flow.handler.generic.EconomyHandler;
import restudio.resync.flow.handler.generic.EntityActionHandler;
import restudio.resync.flow.handler.generic.FileHandler;
import restudio.resync.flow.handler.generic.FlowControlHandler;
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
import restudio.resync.flow.handler.generic.MenuHandler;
import restudio.resync.flow.handler.generic.MiscHandler;
import restudio.resync.flow.handler.generic.ParticleHandler;
import restudio.resync.flow.handler.generic.PermissionHandler;
import restudio.resync.flow.handler.generic.PlaceholderHandler;
import restudio.resync.flow.handler.generic.PlayerActionHandler;
import restudio.resync.flow.handler.generic.RandomHandler;
import restudio.resync.flow.handler.generic.ReSyncRuntimeResourceHandler;
import restudio.resync.flow.handler.generic.RegionHandler;
import restudio.resync.flow.handler.generic.RestoredNodeHandler;
import restudio.resync.flow.handler.generic.ScheduleHandler;
import restudio.resync.flow.handler.generic.ScoreboardHandler;
import restudio.resync.flow.handler.generic.ServerHandler;
import restudio.resync.flow.handler.generic.SoundHandler;
import restudio.resync.flow.handler.generic.TeamHandler;
import restudio.resync.flow.handler.generic.TextFormatHandler;
import restudio.resync.flow.handler.generic.TimeHandler;
import restudio.resync.flow.handler.generic.TitleHandler;
import restudio.resync.flow.handler.generic.UuidHandler;
import restudio.resync.flow.handler.generic.VariableHandler;
import restudio.resync.flow.handler.generic.VariableScopeHandler;
import restudio.resync.flow.handler.generic.WorldActionHandler;
import restudio.resync.flow.handler.family.JsonFamilyHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.messages.MessageLogService;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.runtime.LootTableService;
import restudio.resync.runtime.NpcService;
import restudio.resync.runtime.PlayerNpcRuntime;
import restudio.resync.runtime.ReSyncRuntimeContentAccess;
import restudio.resync.runtime.RuntimeFlowDispatcher;
import restudio.resync.runtime.VillageProfileService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FlowRuntimeModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("flow", "Flow", "flow");
    private FlowModule delegate;
    private FlowStorage storage;
    private FlowExecutor executor;
    private GuiManager guiManager;
    private GlobalTriggers globalTriggers;
    private SystemEventListener systemEventListener;
    private ScoreboardRuntimeListener scoreboardRuntimeListener;
    private PropertyRegistry propertyRegistry;
    private CustomContentStorage customContentStorage;
    private CustomContentService customContentService;
    private CustomContentListener customContentListener;
    private LootTableService lootTableService;
    private VillageProfileService villageProfileService;
    private NpcService npcService;
    private FlowTraceService traceService;
    private FlowDebugService debugService;
    private BukkitTask tickTask;
    private ModuleContext moduleContext;
    private ReSyncJsonResourceStorage jsonResourceStorage;
    private ResourceListener jsonResourceListener;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.moduleContext = context;
        storage = new FlowStorage(context.getPlugin());
        customContentStorage = new CustomContentStorage(context.getPlugin());
        customContentStorage.preloadAll();
        TypeAdapterRegistry typeAdapterRegistry = new TypeAdapterRegistry();
        HandlerRegistry handlerRegistry = new HandlerRegistry();
        FlowRegistry flowRegistry = new FlowRegistry();
        flowRegistry.setHandlerRegistry(handlerRegistry);
        propertyRegistry = new PropertyRegistry();
        registerNodeHandlers(handlerRegistry);
        TypeRegistry typeRegistry = new TypeRegistry();
        restudio.flow.data.FlowDataObjectAdapter.setTypeRegistry(typeRegistry);
        NodeDefinitionRegistry nodeDefinitionRegistry = new NodeDefinitionRegistry();
        NodeDefinitionLoader jsonLoader = new NodeDefinitionLoader();
        jsonLoader.setValidator(new NodeDefinitionValidator(handlerRegistry, true));
        List<NodeDefinition> classpathDefs = jsonLoader.loadFromClasspath("nodes");
        classpathDefs.removeIf(this::isUnavailable);
        jsonLoader.validateAndRegister(classpathDefs, nodeDefinitionRegistry, handlerRegistry, "json-classpath");
        Path nodesDir = context.getPlugin().getDataFolder().toPath().resolve("nodes");
        if (Files.exists(nodesDir)) {
            List<NodeDefinition> jsonDefs = jsonLoader.loadFromDirectory(nodesDir);
            jsonDefs.removeIf(this::isUnavailable);
            jsonLoader.validateAndRegister(jsonDefs, nodeDefinitionRegistry, handlerRegistry, "json");
        }
        new FlowGraphMigrator(storage, nodeDefinitionRegistry).migrateStoredFlows();
        storage.preloadAll();
        traceService = new FlowTraceService(500);
        debugService = new FlowDebugService(traceService);
        executor = new FlowExecutor(handlerRegistry, nodeDefinitionRegistry, typeAdapterRegistry, new HashMap<>());
        executor.setTraceService(traceService);
        executor.setDebugService(debugService);
        customContentService = new CustomContentService(customContentStorage, storage, executor);
        customContentListener = new CustomContentListener(customContentStorage, customContentService);
        jsonResourceStorage = context.getRequiredService(ReSyncJsonResourceStorage.class);
        RuntimeFlowDispatcher runtimeFlowDispatcher = new RuntimeFlowDispatcher(storage, executor);
        lootTableService = new LootTableService(jsonResourceStorage, customContentService, runtimeFlowDispatcher);
        villageProfileService = new VillageProfileService(jsonResourceStorage, customContentService, runtimeFlowDispatcher, context.getPlugin());
        TriggerRegistry triggerRegistry = new TriggerRegistry(context.getPlugin());
        globalTriggers = new GlobalTriggers(storage, executor, triggerRegistry);
        FlowEventRegistry flowEventRegistry = new FlowEventRegistry(globalTriggers.getTriggerDispatcher());
        flowEventRegistry.registerFromJson(new ArrayList<>(nodeDefinitionRegistry.getAllDefinitions().values()));
        systemEventListener = new SystemEventListener(storage, executor, triggerRegistry);
        int channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        OptionCatalogRegistry optionCatalogRegistry = context.getService(OptionCatalogRegistry.class);
        registerResourceCatalogs(optionCatalogRegistry, jsonResourceStorage);
        delegate = new FlowModule(storage, context.getCodec(), channelId, triggerRegistry, globalTriggers, flowRegistry, nodeDefinitionRegistry, propertyRegistry, customContentStorage, customContentService, context.getService(ReSyncExtensionData.class), optionCatalogRegistry, jsonResourceStorage, context.getService(MessageLogService.class));
        delegate.setTraceService(traceService);
        delegate.setDebugService(debugService);
        delegate.setExecutor(executor);
        CustomFunctionNodeDefinitions.rebuild(nodeDefinitionRegistry, storage);
        guiManager = new GuiManager(context.getServer(), storage, executor, delegate);
        context.registerService(FlowStorage.class, storage);
        context.registerService(CustomContentStorage.class, customContentStorage);
        context.registerService(CustomContentService.class, customContentService);
        context.registerService(HandlerRegistry.class, handlerRegistry);
        context.registerService(TypeRegistry.class, typeRegistry);
        context.registerService(FlowRegistry.class, flowRegistry);
        context.registerService(NodeDefinitionRegistry.class, nodeDefinitionRegistry);
        context.registerService(TriggerRegistry.class, triggerRegistry);
        context.registerService(FlowExecutor.class, executor);
        context.registerService(FlowTraceService.class, traceService);
        context.registerService(FlowDebugService.class, debugService);
        context.registerService(FlowModule.class, delegate);
        context.registerService(LootTableService.class, lootTableService);
        context.registerService(VillageProfileService.class, villageProfileService);
        context.registerService(GuiManager.class, guiManager);
        context.registerService(FlowRuntimeModule.class, this);
        FlowRuntimeAccess.configure(context.getPlugin(), () -> storage, () -> executor != null ? executor.getGlobalVariables() : null);
        FlowPacketSender editStateSender = new FlowPacketSender(context.getCodec(), channelId, Set.of());
        DialogService dialogService = new DialogService(
            context.getPlugin(),
            context.getRequiredService(ReSyncJsonResourceStorage.class),
            storage,
            executor,
            editStateSender::sendEditTargetState,
            context.getRequiredService(PlayerSessionLinkService.class)
        );
        context.registerService(DialogService.class, dialogService);
        npcService = new NpcService(context.getPlugin(), jsonResourceStorage, customContentService, runtimeFlowDispatcher, villageProfileService, lootTableService, dialogService, context.getService(PlayerNpcRuntime.class));
        context.registerService(NpcService.class, npcService);
        jsonResourceListener = (type, id, value, deleted) -> {
            if (ReSyncResourceCatalog.NPC_DEFINITION.equals(type)) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> npcService.reload(id, value, deleted));
                return;
            }
            if (ReSyncResourceCatalog.VILLAGE_PROFILE.equals(type)) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> villageProfileService.reload(id, deleted));
            }
        };
        jsonResourceStorage.addListener(jsonResourceListener);
        ScoreboardTemplateManager.configureEditStateBridge(editStateSender::sendEditTargetState, context.getRequiredService(PlayerSessionLinkService.class));
        CustomContentAccess.configure(customContentStorage, customContentService);
        ReSyncRuntimeContentAccess.configure(lootTableService, villageProfileService, npcService);
    }

    public void reloadNodeDefinitions() {
        if (executor != null) {
            executor.cancelPendingTasks();
        }
        HandlerRegistry handlerRegistry = moduleContext.getRequiredService(HandlerRegistry.class);
        NodeDefinitionRegistry nodeDefinitionRegistry = moduleContext.getRequiredService(NodeDefinitionRegistry.class);
        TypeRegistry typeRegistry = moduleContext.getRequiredService(TypeRegistry.class);
        typeRegistry.clear();
        restudio.flow.data.FlowDataObjectAdapter.setTypeRegistry(typeRegistry);
        nodeDefinitionRegistry.clear();
        handlerRegistry.clear();
        propertyRegistry.clear();
        registerNodeHandlers(handlerRegistry);
        NodeDefinitionLoader jsonLoader = new NodeDefinitionLoader();
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlerRegistry, true);
        jsonLoader.setValidator(validator);
        List<NodeDefinition> classpathDefs = jsonLoader.loadFromClasspath("nodes");
        classpathDefs.removeIf(this::isUnavailable);
        jsonLoader.validateAndRegister(classpathDefs, nodeDefinitionRegistry, handlerRegistry, "json-classpath");
        Path nodesDir = moduleContext.getPlugin().getDataFolder().toPath().resolve("nodes");
        if (Files.exists(nodesDir)) {
            List<NodeDefinition> jsonDefs = jsonLoader.loadFromDirectory(nodesDir);
            jsonDefs.removeIf(this::isUnavailable);
            jsonLoader.validateAndRegister(jsonDefs, nodeDefinitionRegistry, handlerRegistry, "json");
        }
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
        return diagnostics;
    }

    public FlowTraceService getTraceService() {
        return traceService;
    }

    private boolean isUnavailable(NodeDefinition def) {
        NodeDefinition.Availability availability = def.getAvailability();
        return availability != null && availability.getPlugin() != null && Bukkit.getPluginManager().getPlugin(availability.getPlugin()) == null;
    }

    private void registerNodeHandlers(HandlerRegistry handlerRegistry) {
        new AbilityEffectHandler().registerTo(handlerRegistry);
        new GenericMathHandler().registerTo(handlerRegistry);
        new GenericStringHandler().registerTo(handlerRegistry);
        new GenericListHandler().registerTo(handlerRegistry);
        new GenericMapHandler().registerTo(handlerRegistry);
        new VariableHandler().registerTo(handlerRegistry);
        new LogicHandler().registerTo(handlerRegistry);
        new ConversionHandler().registerTo(handlerRegistry);
        new DebugHandler().registerTo(handlerRegistry);
        new DiscordHandler().registerTo(handlerRegistry);
        new ChatHandler().registerTo(handlerRegistry);
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            new EconomyHandler().registerTo(handlerRegistry);
        }
        new FileHandler().registerTo(handlerRegistry);
        new FlowControlHandler().registerTo(handlerRegistry);
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
        new RegionHandler().registerTo(handlerRegistry);
        new ScoreboardHandler().registerTo(handlerRegistry);
        new SoundHandler().registerTo(handlerRegistry);
        new ServerHandler().registerTo(handlerRegistry);
        new TeamHandler().registerTo(handlerRegistry);
        new TextFormatHandler().registerTo(handlerRegistry);
        new ScheduleHandler().registerTo(handlerRegistry);
        new TitleHandler().registerTo(handlerRegistry);
        new TimeHandler().registerTo(handlerRegistry);
        new UuidHandler().registerTo(handlerRegistry);
        new ColorHandler().registerTo(handlerRegistry);
        new CustomEventHandler().registerTo(handlerRegistry);
        new CustomContentHandler().registerTo(handlerRegistry);
        new VariableScopeHandler().registerTo(handlerRegistry);
        new FunctionHandler().registerTo(handlerRegistry);
        new PlayerActionHandler().registerTo(handlerRegistry);
        new EntityActionHandler().registerTo(handlerRegistry);
        new WorldActionHandler().registerTo(handlerRegistry);
        new BlockActionHandler().registerTo(handlerRegistry);
        new InventoryActionHandler().registerTo(handlerRegistry);
        new ReSyncRuntimeResourceHandler().registerTo(handlerRegistry);
        new MiscHandler().registerTo(handlerRegistry);
        new RestoredNodeHandler().registerTo(handlerRegistry);
        JsonFamilyHandler.registerFamilies(handlerRegistry);
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
        Bukkit.getPluginManager().registerEvents(villageProfileService, context.getPlugin());
        Bukkit.getPluginManager().registerEvents(npcService, context.getPlugin());
        npcService.spawnStartupNpcs();
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
        if (systemEventListener != null) {
            systemEventListener.onServerStop();
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        TabListService.stopUpdater();
        if (executor != null) {
            executor.cancelPendingTasks();
        }
        if (globalTriggers != null) {
            HandlerList.unregisterAll(globalTriggers);
        }
        if (systemEventListener != null) {
            HandlerList.unregisterAll(systemEventListener);
        }
        if (scoreboardRuntimeListener != null) {
            HandlerList.unregisterAll(scoreboardRuntimeListener);
        }
        if (guiManager != null) {
            HandlerList.unregisterAll(guiManager);
        }
        if (customContentListener != null) {
            HandlerList.unregisterAll(customContentListener);
        }
        if (lootTableService != null) {
            HandlerList.unregisterAll(lootTableService);
        }
        if (villageProfileService != null) {
            HandlerList.unregisterAll(villageProfileService);
        }
        if (jsonResourceStorage != null && jsonResourceListener != null) {
            jsonResourceStorage.removeListener(jsonResourceListener);
            jsonResourceListener = null;
        }
        if (npcService != null) {
            npcService.shutdown();
            HandlerList.unregisterAll(npcService);
        }
        FlowRuntimeAccess.clear();
        ScoreboardTemplateManager.clearEditStateBridge();
        CustomContentAccess.clear();
        ReSyncRuntimeContentAccess.clear();
    }

    private void registerResourceCatalogs(OptionCatalogRegistry registry, ReSyncJsonResourceStorage storage) {
        if (registry == null || storage == null) {
            return;
        }
        registerResourceCatalog(registry, storage, ReSyncResourceCatalog.LOOT_TABLE);
        registerResourceCatalog(registry, storage, ReSyncResourceCatalog.VILLAGE_PROFILE);
        registerResourceCatalog(registry, storage, ReSyncResourceCatalog.NPC_DEFINITION);
        registerResourceCatalog(registry, storage, ReSyncResourceCatalog.DIALOG);
    }

    private void registerResourceCatalog(OptionCatalogRegistry registry, ReSyncJsonResourceStorage storage, String type) {
        registry.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:" + type;
            }

            @Override
            public String revision() {
                return resourceCatalogRevision(type, storage.listIds(type));
            }

            @Override
            public List<String> values() {
                return storage.listIds(type);
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
