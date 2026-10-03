package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.*;
import org.bukkit.event.world.*;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class SystemEventListener implements Listener {
    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final TriggerRegistry triggerRegistry;
    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private volatile CompiledTriggerExecution compiledExecution;
    
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
    private final Map<String, GraphSnapshot> graphSnapshots = new ConcurrentHashMap<>();
    
    private record GraphSnapshot(CoreGraphStorageBoundary.Decoded source, FlowGraph graph) {
        private boolean enabled() {
            return source != null ? source.envelope().assetActivationState() == ResourceActivationState.ACTIVE : graph.isEnabled();
        }

        private long revision() {
            return source != null ? source.envelope().assetRevision() : graph.getResourceRevision();
        }

        private String hash() {
            return source != null ? source.envelope().assetHash().canonicalText() : graph.getResourceHash();
        }
    }

    private GraphSnapshot graphSnapshot(String flowId) {
        if (storage.hasCoreGraphAuthority()) {
            CompiledTriggerExecution execution = compiledExecution;
            CoreGraphStorageBoundary.Decoded source = execution == null
                ? storage.getCoreGraph("flow", flowId).orElse(null) : execution.source("flow", flowId).orElse(null);
            return source == null ? null : new GraphSnapshot(source, null);
        }
        FlowGraph graph = storage.getGraph("flow", flowId);
        return graph == null ? null : new GraphSnapshot(null, graph);
    }

    private void prepare(GraphSnapshot snapshot, CompiledTriggerExecution execution) {
        if (snapshot.source() != null) {
            execution.prepareSource(snapshot.source());
        } else {
            execution.prepare(snapshot.graph());
        }
    }

    public SystemEventListener(FlowStorage storage, FlowExecutor executor, TriggerRegistry triggerRegistry) {
        this.storage = storage;
        this.executor = executor;
        this.triggerRegistry = triggerRegistry;
        refreshBindings();
    }
    
    public void registerTrigger(String eventType, String flowId) {
        if (flowId == null || flowId.isBlank()) {
            warnRegistration(flowId, eventType, "TRIGGER.GRAPH_UNAVAILABLE", "graph-id-unavailable");
            return;
        }
        GraphSnapshot graph;
        try {
            graph = graphSnapshot(flowId);
        } catch (RuntimeException failure) {
            rejectFlow(flowId, eventType, failure);
            return;
        }
        if (graph == null) {
            warnRegistration(flowId, eventType, "TRIGGER.GRAPH_UNAVAILABLE", "graph-unavailable");
            removeFlowBindings(flowId);
            return;
        }
        
        String startNode = findStartNodeForEvent(graph, eventType);
        if (startNode == null) {
            startNode = findStartNode(graph);
        }
        if (startNode == null) {
            warnRegistration(flowId, eventType, "TRIGGER.START_NODE_UNAVAILABLE", "start-node-unavailable");
            removeFlowBindings(flowId);
            return;
        }
        CompiledTriggerExecution execution = compiledExecution;
        if (execution != null) {
            try {
                prepare(graph, execution);
            } catch (RuntimeException failure) {
                rejectFlow(flowId, eventType, failure);
                return;
            }
        }
        graphSnapshots.put(flowId, graph);
        
        String key = normalizeEventKey(eventType);
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
                warnRegistration(flowId, eventType, "TRIGGER.EVENT_TYPE_UNAVAILABLE", "event-type-unavailable");
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
        graphSnapshots.clear();
        
        if (triggerRegistry == null) {
            return;
        }
        
        for (TriggerType type : List.of(TriggerType.EVENT, TriggerType.SYSTEM)) {
            for (TriggerBinding binding : triggerRegistry.getBindings(type)) {
                String context = binding.getContext();
                if (isSystemEvent(context)) {
                    registerTrigger(context, binding.getFlowId());
                }
            }
        }
    }

    public void setCompiledExecution(CompiledTriggerExecution compiledExecution) {
        this.compiledExecution = compiledExecution;
        if (compiledExecution != null) {
            for (Map.Entry<String, GraphSnapshot> entry : Map.copyOf(graphSnapshots).entrySet()) {
                try {
                    prepare(entry.getValue(), compiledExecution);
                } catch (RuntimeException failure) {
                    rejectFlow(entry.getKey(), "system-event", failure);
                }
            }
        }
    }

    private void rejectFlow(String flowId, String eventType, RuntimeException failure) {
        String reason = failure instanceof CompiledPlanAdmissionException admission
            ? admission.reason().name().toLowerCase(Locale.ROOT) : failure.getClass().getSimpleName();
        boolean reported = false;
        if (triggerRegistry != null) {
            for (TriggerType type : List.of(TriggerType.EVENT, TriggerType.SYSTEM)) {
                for (TriggerBinding binding : triggerRegistry.getBindings(type)) {
                    if (flowId.equals(binding.getFlowId()) && isSystemEvent(binding.getContext())) {
                        warnRegistration(flowId, binding.getContext(), "TRIGGER.PLAN_UNAVAILABLE", reason);
                        reported = true;
                    }
                }
            }
        }
        if (!reported) {
            warnRegistration(flowId, eventType, "TRIGGER.PLAN_UNAVAILABLE", reason);
        }
        removeFlowBindings(flowId);
        CompiledTriggerExecution execution = compiledExecution;
        if (execution != null) {
            execution.retire("flow", flowId);
        }
    }

    private void removeFlowBindings(String flowId) {
        graphSnapshots.remove(flowId);
        serverStartTriggers.remove(flowId);
        serverStopTriggers.remove(flowId);
        pluginEnableTriggers.remove(flowId);
        pluginDisableTriggers.remove(flowId);
        worldLoadTriggers.remove(flowId);
        worldUnloadTriggers.remove(flowId);
        chunkLoadTriggers.remove(flowId);
        chunkUnloadTriggers.remove(flowId);
        serverTickTriggers.remove(flowId);
        serverSaveTriggers.remove(flowId);
    }

    CompletableFuture<Void> dispatch(FlowGraph graph, String startNodeId, Event event,
                                     Map<String, Object> eventVars) {
        return dispatch(graph == null ? "" : graph.getId(), startNodeId, "system", event, eventVars, graph);
    }

    private CompletableFuture<Void> dispatch(String flowId, String startNodeId, String eventType, Event event,
                                             Map<String, Object> eventVars, FlowGraph suppliedGraph) {
        CorrelationId invocationId = CorrelationId.random();
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> ingress = TemporaryLifecycleDiagnostics.with(
            TemporaryLifecycleDiagnostics.identity(null, "flow:" + flowId, "trigger-execution", null, null,
                invocationId, null, null, null, null),
            "source", "system-event", "eventType", eventType, "startNodeId", startNodeId, "outcome", "matched");
        TemporaryLifecycleDiagnostics.event("trigger_ingress", started, ingress);
        Map<String, Object> terminalIdentity = ingress;
        String failureCode = "TRIGGER.GRAPH_UNAVAILABLE";
        String failureReason = "graph-resolution-failed";
        try {
            GraphSnapshot snapshot = suppliedGraph != null ? new GraphSnapshot(null, suppliedGraph) : graphSnapshots.get(flowId);
            if (snapshot == null || !snapshot.enabled()) {
                TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, ingress, "rejected",
                    "TRIGGER.GRAPH_UNAVAILABLE", snapshot == null ? "graph-unavailable" : "graph-disabled");
                warn(flowId, invocationId, "TRIGGER.GRAPH_UNAVAILABLE", "rejected");
                return failed("CORE_EXECUTION_UNAVAILABLE", "The system trigger graph is unavailable", startNodeId);
            }
            Map<String, Object> binding = TemporaryLifecycleDiagnostics.with(ingress, "revision", snapshot.revision(),
                "graphHash", snapshot.hash(), "outcome", "selected");
            terminalIdentity = binding;
            TemporaryLifecycleDiagnostics.event("trigger_binding_selected", started, binding);
            CompiledTriggerExecution execution = compiledExecution;
            if (execution == null) {
                TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, binding, "rejected",
                    "TRIGGER.EXECUTOR_UNAVAILABLE", "compiled-executor-unavailable");
                warn(flowId, invocationId, "TRIGGER.EXECUTOR_UNAVAILABLE", "rejected");
                return failed("CORE_EXECUTION_UNAVAILABLE", "Compiled Core trigger execution is not initialized", startNodeId);
            }
            failureCode = "TRIGGER.EXECUTOR_REJECTED";
            failureReason = "synchronous-rejection";
            CompletableFuture<Void> future;
            if (snapshot.source() != null) {
                future = execution.executeSource(snapshot.source(), startNodeId, null, event, eventVars, null, invocationId,
                    RuntimeExecutionContext.NO_DEADLINE);
            } else {
                FlowGraph graph = snapshot.graph();
                future = execution.execute(graph, startNodeId, null, event, eventVars, null, invocationId);
            }
            execution.observe(future, invocationId, "system-event:" + eventType + ":" + flowId);
            return future;
        } catch (RuntimeException failure) {
            TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, terminalIdentity, "failed",
                failureCode, failureReason);
            warn(flowId, invocationId, failureCode, "failed");
            return failed("CORE_EXECUTION_FAILED", "System trigger execution could not be admitted", startNodeId);
        }
    }

    private void dispatch(String flowId, String startNodeId, String eventType, Event event, Map<String, Object> eventVars) {
        dispatch(flowId, startNodeId, eventType, event, eventVars, null);
    }

    public void refreshGraph(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return;
        }
        GraphSnapshot graph;
        try {
            graph = graphSnapshot(flowId);
        } catch (RuntimeException failure) {
            rejectFlow(flowId, "system-event", failure);
            return;
        }
        if (graph == null) {
            removeFlowBindings(flowId);
            CompiledTriggerExecution execution = compiledExecution;
            if (execution != null) {
                execution.retire("flow", flowId);
            }
        } else if (graphSnapshots.containsKey(flowId)) {
            CompiledTriggerExecution execution = compiledExecution;
            if (execution != null) {
                try {
                    prepare(graph, execution);
                } catch (RuntimeException failure) {
                    rejectFlow(flowId, "system-event", failure);
                    return;
                }
            }
            boolean retained = false;
            for (Map.Entry<String, Map<String, String>> entry : eventBindings().entrySet()) {
                Map<String, String> bindings = entry.getValue();
                if (bindings.containsKey(flowId)) {
                    String start = findStartNodeForEvent(graph, entry.getKey());
                    if (start == null) {
                        start = findStartNode(graph);
                    }
                    if (start == null) {
                        bindings.remove(flowId);
                    } else {
                        bindings.put(flowId, start);
                        retained = true;
                    }
                }
            }
            if (retained) {
                graphSnapshots.put(flowId, graph);
            } else {
                removeFlowBindings(flowId);
            }
        } else if (triggerRegistry != null) {
            for (TriggerType type : List.of(TriggerType.EVENT, TriggerType.SYSTEM)) {
                for (TriggerBinding binding : triggerRegistry.getBindings(type)) {
                    if (flowId.equals(binding.getFlowId()) && isSystemEvent(binding.getContext())) {
                        registerTrigger(binding.getContext(), flowId);
                    }
                }
            }
        }
    }

    private CompletableFuture<Void> failed(String code, String message, String startNodeId) {
        return CompletableFuture.failedFuture(new FlowExecutor.FlowExecutionException(code, message, null, startNodeId,
            "Restore the compiled Core trigger boundary before dispatching system triggers"));
    }

    private void warn(String flowId, CorrelationId invocationId, String code, String outcome) {
        CompiledTriggerExecution.warnInvocation("system-event|" + flowId + "|" + code,
            "Compiled system trigger invocation " + outcome + " correlationId=" + invocationId.canonicalText()
                + " diagnosticCode=" + code + " source=system-event:" + flowId);
    }

    private void warnRegistration(String flowId, String eventType, String code, String outcome) {
        String resourceId = flowId == null || flowId.isBlank() ? "unknown" : flowId;
        String triggerType = eventType == null || eventType.isBlank() ? "unknown" : normalizeEventKey(eventType);
        CompiledTriggerExecution.warnInvocation("system-event-registration|" + resourceId + "|" + triggerType + "|" + code,
            "Compiled system trigger registration " + outcome + " diagnosticCode=" + code
                + " resourceType=flow resourceId=" + resourceId + " eventType=" + triggerType);
    }
    
    private boolean isSystemEvent(String eventType) {
        String key = normalizeEventKey(eventType);
        return key.equals("server_start") || key.equals("server_stop") ||
               key.equals("plugin_enable") || key.equals("plugin_disable") ||
               key.equals("world_load") || key.equals("world_unload") ||
               key.equals("chunk_load") || key.equals("chunk_unload") ||
               key.equals("server_tick") || key.equals("server_save");
    }

    private String normalizeEventKey(String eventType) {
        if (eventType == null) {
            return "";
        }
        String key = eventType.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("event:")) {
            key = key.substring(6);
        } else if (key.startsWith("event.")) {
            key = key.substring(6);
        }
        return key.replace('.', '_');
    }
    
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        for (Map.Entry<String, String> entry : serverStartTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.server_name", Bukkit.getServer().getName());
            dispatch(entry.getKey(), entry.getValue(), "server_start", event, eventVars);
        }
    }
    
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin plugin = event.getPlugin();
        
        for (Map.Entry<String, String> entry : pluginDisableTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.plugin_name", plugin.getName());
            eventVars.put("event.plugin_instance", pluginIdentity(plugin));
            dispatch(entry.getKey(), entry.getValue(), "plugin_disable", event, eventVars);
        }
    }

    private Map<String, Object> pluginIdentity(Plugin plugin) {
        return Map.of("kind", "bukkit-plugin", "name", plugin.getName(), "version", plugin.getDescription().getVersion());
    }
    
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        Plugin plugin = event.getPlugin();
        
        for (Map.Entry<String, String> entry : pluginEnableTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.plugin_name", plugin.getName());
            dispatch(entry.getKey(), entry.getValue(), "plugin_enable", event, eventVars);
        }
    }
    
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        for (Map.Entry<String, String> entry : worldLoadTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.world_name", event.getWorld().getName());
            dispatch(entry.getKey(), entry.getValue(), "world_load", event, eventVars);
        }
    }
    
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        for (Map.Entry<String, String> entry : worldUnloadTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.world_name", event.getWorld().getName());
            dispatch(entry.getKey(), entry.getValue(), "world_unload", event, eventVars);
        }
    }
    
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Map.Entry<String, String> entry : chunkLoadTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.chunk_x", event.getChunk().getX());
            eventVars.put("event.chunk_z", event.getChunk().getZ());
            dispatch(entry.getKey(), entry.getValue(), "chunk_load", event, eventVars);
        }
    }
    
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Map.Entry<String, String> entry : chunkUnloadTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.chunk_x", event.getChunk().getX());
            eventVars.put("event.chunk_z", event.getChunk().getZ());
            dispatch(entry.getKey(), entry.getValue(), "chunk_unload", event, eventVars);
        }
    }
    
    public void tick() {
        int tick = tickCounter.incrementAndGet();
        
        for (Map.Entry<String, String> entry : serverTickTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.tick_number", tick);
            dispatch(entry.getKey(), entry.getValue(), "server_tick", null, eventVars);
        }
    }

    public void onServerStop() {
        for (Map.Entry<String, String> entry : serverStopTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.server_name", Bukkit.getServer().getName());
            dispatch(entry.getKey(), entry.getValue(), "server_stop", null, eventVars);
        }
    }
    
    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        for (Map.Entry<String, String> entry : serverSaveTriggers.entrySet()) {
            Map<String, Object> eventVars = new HashMap<>();
            eventVars.put("event.world_name", event.getWorld().getName());
            dispatch(entry.getKey(), entry.getValue(), "server_save", event, eventVars);
        }
    }
    
    private Map<String, Map<String, String>> eventBindings() {
        return Map.of("server_start", serverStartTriggers, "server_stop", serverStopTriggers,
            "plugin_enable", pluginEnableTriggers, "plugin_disable", pluginDisableTriggers,
            "world_load", worldLoadTriggers, "world_unload", worldUnloadTriggers,
            "chunk_load", chunkLoadTriggers, "chunk_unload", chunkUnloadTriggers,
            "server_tick", serverTickTriggers, "server_save", serverSaveTriggers);
    }

    private String findStartNodeForEvent(GraphSnapshot graph, String eventType) {
        if (graph.source() == null) {
            return findStartNodeForEvent(graph.graph(), eventType);
        }
        String expected = normalizeEventKey(eventType);
        for (GraphNode node : graph.source().graphDocument().nodes()) {
            String type = node.definition().canonicalText();
            String context = executor == null ? null : executor.eventBindingContext(type);
            if (expected.equals(normalizeEventKey(node.definition().id().value()))
                || expected.equals(normalizeEventKey(context))) {
                return node.instanceId().canonicalText();
            }
        }
        return null;
    }

    private String findStartNode(GraphSnapshot graph) {
        if (graph.source() == null) {
            return findStartNode(graph.graph());
        }
        CompiledTriggerExecution execution = compiledExecution;
        return execution == null ? executor.findStartNode(graph.source().graphDocument()) : execution.findStartNode(graph.source());
    }

    private String findStartNodeForEvent(FlowGraph graph, String eventType) {
        String expected = normalizeEventKey(eventType);
        for (var entry : graph.getNodes().entrySet()) {
            String nodeType = entry.getValue().getType();
            String localNodeType = nodeType != null && nodeType.contains("/")
                ? nodeType.substring(nodeType.indexOf('/') + 1) : nodeType;
            String bindingContext = executor == null ? null : executor.eventBindingContext(nodeType);
            if (expected.equals(normalizeEventKey(nodeType))
                || expected.equals(normalizeEventKey(localNodeType))
                || expected.equals(normalizeEventKey(bindingContext))) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    private String findStartNode(FlowGraph graph) {
        for (var entry : graph.getNodes().entrySet()) {
            String nodeType = entry.getValue().getType();
            if (nodeType != null && (nodeType.startsWith("event:") || nodeType.startsWith("event."))) {
                return entry.getKey();
            }
        }
        return null;
    }
}
