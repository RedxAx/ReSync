package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.*;
import org.bukkit.event.world.*;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SystemEventListener implements Listener {
    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final TriggerRegistry triggerRegistry;
    private final AtomicInteger tickCounter = new AtomicInteger(0);
    
    private final Map<String, String> serverStartTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> serverStopTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> pluginEnableTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> pluginDisableTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> worldLoadTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> worldUnloadTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> chunkLoadTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> chunkUnloadTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> serverTickTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> serverSaveTriggers = new ConcurrentHashMap<>();
    
    public SystemEventListener(FlowStorage storage, FlowExecutor executor, TriggerRegistry triggerRegistry) {
        this.storage = storage;
        this.executor = executor;
        this.triggerRegistry = triggerRegistry;
        refreshBindings();
    }
    
    public void registerTrigger(String eventType, String flowId) {
        FlowGraph graph = storage.getGraph(flowId);
        if (graph == null) {
            System.err.println("[ReSync] Failed to load flow for trigger: " + flowId);
            return;
        }
        
        String startNode = findStartNodeForEvent(graph, eventType);
        if (startNode == null) {
            startNode = findStartNode(graph);
        }
        if (startNode == null) {
            System.err.println("[ReSync] No event node found for trigger: " + eventType + " in flow: " + flowId);
            return;
        }
        
        String key = eventType.toLowerCase();
        switch (key) {
            case "event:server_start":
            case "server_start":
                serverStartTriggers.put(flowId, startNode);
                break;
            case "event:server_stop":
            case "server_stop":
                serverStopTriggers.put(flowId, startNode);
                break;
            case "event:plugin_enable":
            case "plugin_enable":
                pluginEnableTriggers.put(flowId, startNode);
                break;
            case "event:plugin_disable":
            case "plugin_disable":
                pluginDisableTriggers.put(flowId, startNode);
                break;
            case "event:world_load":
            case "world_load":
                worldLoadTriggers.put(flowId, startNode);
                break;
            case "event:world_unload":
            case "world_unload":
                worldUnloadTriggers.put(flowId, startNode);
                break;
            case "event:chunk_load":
            case "chunk_load":
                chunkLoadTriggers.put(flowId, startNode);
                break;
            case "event:chunk_unload":
            case "chunk_unload":
                chunkUnloadTriggers.put(flowId, startNode);
                break;
            case "event:server_tick":
            case "server_tick":
                serverTickTriggers.put(flowId, startNode);
                break;
            case "event:server_save":
            case "server_save":
                serverSaveTriggers.put(flowId, startNode);
                break;
            default:
                System.err.println("[ReSync] Unknown system trigger type: " + eventType);
        }
    }
    
    public void refreshBindings() {
        serverStartTriggers.clear();
        serverStopTriggers.clear();
        pluginEnableTriggers.clear();
        pluginDisableTriggers.clear();
        worldLoadTriggers.clear();
        worldUnloadTriggers.clear();
        chunkLoadTriggers.clear();
        chunkUnloadTriggers.clear();
        serverTickTriggers.clear();
        serverSaveTriggers.clear();
        
        if (triggerRegistry == null) {
            return;
        }
        
        for (TriggerBinding binding : triggerRegistry.getBindings(TriggerType.EVENT)) {
            String context = binding.getContext();
            if (isSystemEvent(context)) {
                registerTrigger(context, binding.getFlowId());
            }
        }
    }
    
    private boolean isSystemEvent(String eventType) {
        String key = eventType.toLowerCase();
        return key.equals("event:server_start") || key.equals("server_start") ||
               key.equals("event:server_stop") || key.equals("server_stop") ||
               key.equals("event:plugin_enable") || key.equals("plugin_enable") ||
               key.equals("event:plugin_disable") || key.equals("plugin_disable") ||
               key.equals("event:world_load") || key.equals("world_load") ||
               key.equals("event:world_unload") || key.equals("world_unload") ||
               key.equals("event:chunk_load") || key.equals("chunk_load") ||
               key.equals("event:chunk_unload") || key.equals("chunk_unload") ||
               key.equals("event:server_tick") || key.equals("server_tick") ||
               key.equals("event:server_save") || key.equals("server_save");
    }
    
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        for (Map.Entry<String, String> entry : serverStartTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.server_name", Bukkit.getServer().getName());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin plugin = event.getPlugin();
        
        for (Map.Entry<String, String> entry : pluginDisableTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.plugin_name", plugin.getName());
                executor.getEventVariables().put("event.plugin_instance", plugin);
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        Plugin plugin = event.getPlugin();
        
        for (Map.Entry<String, String> entry : pluginEnableTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.plugin_name", plugin.getName());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        for (Map.Entry<String, String> entry : worldLoadTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.world_name", event.getWorld().getName());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        for (Map.Entry<String, String> entry : worldUnloadTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.world_name", event.getWorld().getName());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Map.Entry<String, String> entry : chunkLoadTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.chunk_x", event.getChunk().getX());
                executor.getEventVariables().put("event.chunk_z", event.getChunk().getZ());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Map.Entry<String, String> entry : chunkUnloadTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.chunk_x", event.getChunk().getX());
                executor.getEventVariables().put("event.chunk_z", event.getChunk().getZ());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    public void tick() {
        int tick = tickCounter.incrementAndGet();
        
        for (Map.Entry<String, String> entry : serverTickTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.tick_number", tick);
                executor.execute(graph, entry.getValue(), null, null);
            }
        }
    }

    public void onServerStop() {
        for (Map.Entry<String, String> entry : serverStopTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.server_name", Bukkit.getServer().getName());
                executor.execute(graph, entry.getValue(), null, null);
            }
        }
    }
    
    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        for (Map.Entry<String, String> entry : serverSaveTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                executor.getEventVariables().put("event.world_name", event.getWorld().getName());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    private String findStartNodeForEvent(FlowGraph graph, String eventType) {
        for (var entry : graph.getNodes().entrySet()) {
            String nodeType = entry.getValue().getType();
            if (nodeType != null && nodeType.equalsIgnoreCase(eventType)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    private String findStartNode(FlowGraph graph) {
        for (var entry : graph.getNodes().entrySet()) {
            String nodeType = entry.getValue().getType();
            if (nodeType != null && nodeType.startsWith("event:")) {
                return entry.getKey();
            }
        }
        return null;
    }
}
