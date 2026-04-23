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
import restudio.resync.flow.StandardNodes;
import restudio.resync.flow.SystemEventListener;
import restudio.resync.flow.TabListService;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.plugins.FlowNodePluginRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
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
    private BukkitTask tickTask;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        storage = new FlowStorage(context.getPlugin());
        storage.preloadAll();
        TypeAdapterRegistry typeAdapterRegistry = new TypeAdapterRegistry();
        FlowRegistry flowRegistry = new FlowRegistry();
        NodeDefinitionRegistry nodeDefinitionRegistry = new NodeDefinitionRegistry();
        StandardNodes.registerAll(flowRegistry, nodeDefinitionRegistry);
        nodePluginRegistry = new FlowNodePluginRegistry(
            flowRegistry,
            nodeDefinitionRegistry,
            context.getPlugin().getDataFolder().toPath().resolve("flow-plugins")
        );
        nodePluginRegistry.loadInitialPlugins();
        executor = new FlowExecutor(flowRegistry, typeAdapterRegistry, new HashMap<>());
        TriggerRegistry triggerRegistry = new TriggerRegistry(context.getPlugin());
        globalTriggers = new GlobalTriggers(storage, executor, triggerRegistry);
        systemEventListener = new SystemEventListener(storage, executor, triggerRegistry);
        int channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        delegate = new FlowModule(storage, context.getCodec(), channelId, triggerRegistry, globalTriggers, flowRegistry, nodeDefinitionRegistry, nodePluginRegistry);
        CustomFunctionNodeDefinitions.rebuild(nodeDefinitionRegistry, storage);
        guiManager = new GuiManager(context.getServer(), storage, executor, delegate);
        context.registerService(FlowStorage.class, storage);
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
