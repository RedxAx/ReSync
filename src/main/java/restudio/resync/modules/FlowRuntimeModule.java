package restudio.resync.modules;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.core.Session;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.ScoreboardRuntimeListener;
import restudio.resync.flow.SystemEventListener;
import restudio.resync.flow.TabListService;
import restudio.flow.data.TypeRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.BlockActionHandler;
import restudio.resync.flow.handler.generic.ColorHandler;
import restudio.resync.flow.handler.generic.ConversionHandler;
import restudio.resync.flow.migration.FlowGraphMigrator;
import restudio.resync.flow.handler.generic.CustomEventHandler;
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
import restudio.resync.flow.plugins.FlowNodePluginRegistry;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;

import java.util.HashMap;

public class FlowRuntimeModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("flow", "Flow", "flow");
    private FlowModule delegate;
    private FlowStorage storage;
    private FlowExecutor executor;
    private GuiManager guiManager;
    private GlobalTriggers globalTriggers;
    private SystemEventListener systemEventListener;
    private FlowNodePluginRegistry nodePluginRegistry;
    private ScoreboardRuntimeListener scoreboardRuntimeListener;
    private PropertyRegistry propertyRegistry;
    private BukkitTask tickTask;
    private ModuleContext moduleContext;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.moduleContext = context;
        storage = new FlowStorage(context.getPlugin());
        new FlowGraphMigrator(storage).migrateStoredFlows();
        storage.preloadAll();
        TypeAdapterRegistry typeAdapterRegistry = new TypeAdapterRegistry();
        HandlerRegistry handlerRegistry = new HandlerRegistry();
        FlowRegistry flowRegistry = new FlowRegistry();
        flowRegistry.setHandlerRegistry(handlerRegistry);
        propertyRegistry = new PropertyRegistry();
        new GenericMathHandler().registerTo(handlerRegistry);
        new GenericStringHandler().registerTo(handlerRegistry);
        new GenericListHandler().registerTo(handlerRegistry);
        new GenericMapHandler().registerTo(handlerRegistry);
        new VariableHandler().registerTo(handlerRegistry);
        new LogicHandler().registerTo(handlerRegistry);
        new ConversionHandler().registerTo(handlerRegistry);
        new DebugHandler().registerTo(handlerRegistry);
        new DiscordHandler().registerTo(handlerRegistry);
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
        new VariableScopeHandler().registerTo(handlerRegistry);
        new FunctionHandler().registerTo(handlerRegistry);
        new PlayerActionHandler().registerTo(handlerRegistry);
        new EntityActionHandler().registerTo(handlerRegistry);
        new WorldActionHandler().registerTo(handlerRegistry);
        new BlockActionHandler().registerTo(handlerRegistry);
        new InventoryActionHandler().registerTo(handlerRegistry);
        new MiscHandler().registerTo(handlerRegistry);
        new RestoredNodeHandler().registerTo(handlerRegistry);
        JsonFamilyHandler.registerFamilies(handlerRegistry);
        TypeRegistry typeRegistry = new TypeRegistry();
        restudio.flow.data.FlowDataObjectAdapter.setTypeRegistry(typeRegistry);
        NodeDefinitionRegistry nodeDefinitionRegistry = new NodeDefinitionRegistry();
        NodeDefinitionLoader jsonLoader = new NodeDefinitionLoader();
        jsonLoader.setValidator(new NodeDefinitionValidator(handlerRegistry, true));
        java.util.List<NodeDefinition> classpathDefs = jsonLoader.loadFromClasspath("nodes");
        classpathDefs.removeIf(this::isUnavailable);
        jsonLoader.validateAndRegister(classpathDefs, nodeDefinitionRegistry, handlerRegistry, "json-classpath");
        java.nio.file.Path nodesDir = context.getPlugin().getDataFolder().toPath().resolve("nodes");
        if (java.nio.file.Files.exists(nodesDir)) {
            java.util.List<NodeDefinition> jsonDefs = jsonLoader.loadFromDirectory(nodesDir);
            jsonDefs.removeIf(this::isUnavailable);
            jsonLoader.validateAndRegister(jsonDefs, nodeDefinitionRegistry, handlerRegistry, "json");
        }
        nodePluginRegistry = new FlowNodePluginRegistry(
            flowRegistry,
            nodeDefinitionRegistry,
            context.getPlugin().getDataFolder().toPath().resolve("flow-plugins")
        );
        nodePluginRegistry.loadInitialPlugins();
        executor = new FlowExecutor(handlerRegistry, nodeDefinitionRegistry, typeAdapterRegistry, new HashMap<>());
        TriggerRegistry triggerRegistry = new TriggerRegistry(context.getPlugin());
        globalTriggers = new GlobalTriggers(storage, executor, triggerRegistry);
        FlowEventRegistry flowEventRegistry = new FlowEventRegistry(globalTriggers.getTriggerDispatcher());
        flowEventRegistry.registerFromJson(new java.util.ArrayList<>(nodeDefinitionRegistry.getAllDefinitions().values()));
        systemEventListener = new SystemEventListener(storage, executor, triggerRegistry);
        int channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        delegate = new FlowModule(storage, context.getCodec(), channelId, triggerRegistry, globalTriggers, flowRegistry, nodeDefinitionRegistry, nodePluginRegistry, propertyRegistry);
        CustomFunctionNodeDefinitions.rebuild(nodeDefinitionRegistry, storage);
        guiManager = new GuiManager(context.getServer(), storage, executor, delegate);
        context.registerService(FlowStorage.class, storage);
        context.registerService(HandlerRegistry.class, handlerRegistry);
        context.registerService(TypeRegistry.class, typeRegistry);
        context.registerService(FlowRegistry.class, flowRegistry);
        context.registerService(NodeDefinitionRegistry.class, nodeDefinitionRegistry);
        context.registerService(FlowNodePluginRegistry.class, nodePluginRegistry);
        context.registerService(TriggerRegistry.class, triggerRegistry);
        context.registerService(FlowExecutor.class, executor);
        context.registerService(FlowModule.class, delegate);
        context.registerService(GuiManager.class, guiManager);
        context.registerService(FlowRuntimeModule.class, this);
        FlowRuntimeAccess.configure(context.getPlugin(), () -> storage, () -> executor != null ? executor.getGlobalVariables() : null);
    }

    public void reloadNodeDefinitions() {
        if (nodePluginRegistry != null) {
            nodePluginRegistry.shutdown();
        }
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
        new GenericMathHandler().registerTo(handlerRegistry);
        new GenericStringHandler().registerTo(handlerRegistry);
        new GenericListHandler().registerTo(handlerRegistry);
        new GenericMapHandler().registerTo(handlerRegistry);
        new VariableHandler().registerTo(handlerRegistry);
        new LogicHandler().registerTo(handlerRegistry);
        new ConversionHandler().registerTo(handlerRegistry);
        new DebugHandler().registerTo(handlerRegistry);
        new DiscordHandler().registerTo(handlerRegistry);
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
        new VariableScopeHandler().registerTo(handlerRegistry);
        new FunctionHandler().registerTo(handlerRegistry);
        new PlayerActionHandler().registerTo(handlerRegistry);
        new EntityActionHandler().registerTo(handlerRegistry);
        new WorldActionHandler().registerTo(handlerRegistry);
        new BlockActionHandler().registerTo(handlerRegistry);
        new InventoryActionHandler().registerTo(handlerRegistry);
        new MiscHandler().registerTo(handlerRegistry);
        new RestoredNodeHandler().registerTo(handlerRegistry);
        JsonFamilyHandler.registerFamilies(handlerRegistry);
        NodeDefinitionLoader jsonLoader = new NodeDefinitionLoader();
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlerRegistry, true);
        jsonLoader.setValidator(validator);
        java.util.List<NodeDefinition> classpathDefs = jsonLoader.loadFromClasspath("nodes");
        classpathDefs.removeIf(this::isUnavailable);
        jsonLoader.validateAndRegister(classpathDefs, nodeDefinitionRegistry, handlerRegistry, "json-classpath");
        java.nio.file.Path nodesDir = moduleContext.getPlugin().getDataFolder().toPath().resolve("nodes");
        if (java.nio.file.Files.exists(nodesDir)) {
            java.util.List<NodeDefinition> jsonDefs = jsonLoader.loadFromDirectory(nodesDir);
            jsonDefs.removeIf(this::isUnavailable);
            jsonLoader.validateAndRegister(jsonDefs, nodeDefinitionRegistry, handlerRegistry, "json");
        }
        nodePluginRegistry = new FlowNodePluginRegistry(
            moduleContext.getRequiredService(FlowRegistry.class),
            nodeDefinitionRegistry,
            moduleContext.getPlugin().getDataFolder().toPath().resolve("flow-plugins")
        );
        nodePluginRegistry.loadInitialPlugins();
        if (delegate != null) {
            delegate.refreshCustomFunctionDefinitions();
        }
    }

    private boolean isUnavailable(NodeDefinition def) {
        NodeDefinition.Availability availability = def.getAvailability();
        return availability != null && availability.getPlugin() != null && Bukkit.getPluginManager().getPlugin(availability.getPlugin()) == null;
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
        TabListService.startUpdater();
        tickTask = Bukkit.getScheduler().runTaskTimer(context.getPlugin(), () -> {
            systemEventListener.tick();
            CustomEventManager.getInstance().tick();
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
        if (nodePluginRegistry != null) {
            nodePluginRegistry.shutdown();
        }
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
        FlowRuntimeAccess.clear();
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
        if (nodePluginRegistry != null) {
            nodePluginRegistry.tick();
        }
        delegate.onTick();
    }

    @Override
    public void cleanup(Session session) {
        delegate.cleanup(session);
    }
}
