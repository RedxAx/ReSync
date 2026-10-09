package restudio.resync.flow.triggers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.EventExecutor;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.Log;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.CompiledRuntimeContextAdapter;
import restudio.resync.flow.CompiledCoreFlowExecutionBridge;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.automation.AutomationReferences;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class TriggerDispatcher implements Listener {

    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final Plugin plugin;
    private final Map<String, TriggerEntry> entriesByType = new ConcurrentHashMap<>();
    private final Map<String, TriggerEntry> entriesByLookup = new ConcurrentHashMap<>();
    private final Map<ContractRef<NodeId>, TriggerEntry> entriesByDefinition = new ConcurrentHashMap<>();
    private final Set<TriggerEntry> entries = ConcurrentHashMap.newKeySet();
    private final Map<String, CoreGraphStorageBoundary.Decoded> graphSnapshots = new ConcurrentHashMap<>();
    private volatile CompiledTriggerExecution compiledExecution;
    private boolean definitionAdmissionOpen = true;
    private final Map<Event, EventObservation> eventObservations = Collections.synchronizedMap(new IdentityHashMap<>());

    private static final Map<String, String> LEGACY_EVENT_ALIASES = Map.ofEntries(
            Map.entry("chat", "async_chat"),
            Map.entry("join", "player_join"),
            Map.entry("quit", "player_quit"),
            Map.entry("sneak", "player_sneak"),
            Map.entry("death", "player_death"),
            Map.entry("move", "player_move"),
            Map.entry("bed_enter", "player_bed_enter"),
            Map.entry("bed_leave", "player_bed_leave"),
            Map.entry("respawn", "player_respawn"),
            Map.entry("level_up", "player_level_change"),
            Map.entry("interact", "player_interact"),
            Map.entry("entity_interact", "player_interact_entity"),
            Map.entry("pickup", "player_pickup_item"),
            Map.entry("drop", "player_drop_item"),
            Map.entry("consume", "player_item_consume"),
            Map.entry("shoot", "projectile_launch"),
            Map.entry("flight_toggle", "player_toggle_flight"),
            Map.entry("gamemode_change", "player_gamemode_change"),
            Map.entry("shear", "player_shear_entity"),
            Map.entry("command", "player_command"),
            Map.entry("exp_change", "player_exp_change"),
            Map.entry("explosion", "explosion_prime"),
            Map.entry("physics", "block_physics"),
            Map.entry("grow", "block_grow"),
            Map.entry("time_change", "time_skip"),
            Map.entry("leaf_decay", "leaves_decay"),
            Map.entry("piston_extend", "block_piston_extend"),
            Map.entry("piston_retract", "block_piston_retract")
    );

    public TriggerDispatcher(FlowStorage storage, FlowExecutor executor, Plugin plugin) {
        this.storage = storage;
        this.executor = executor;
        this.plugin = plugin;
    }

    public void setCompiledExecution(CompiledTriggerExecution compiledExecution) {
        this.compiledExecution = compiledExecution;
        if (compiledExecution != null) {
            graphSnapshots.entrySet().removeIf(entry -> !prepare(entry.getKey(), entry.getValue(), compiledExecution));
        }
    }

    public CompiledTriggerExecution getCompiledExecution() {
        return compiledExecution;
    }

    public List<Map<String, Object>> describeBindings() {
        return entries.stream().sorted(Comparator.comparing(entry -> entry.eventType)).map(entry -> Map.<String, Object>of(
            "eventType", entry.eventType, "nodeType", entry.nodeType, "eventClass", entry.eventClass.getName(),
            "priority", entry.priority.name(), "ignoreCancelled", entry.ignoreCancelled,
            "bindings", Map.copyOf(entry.triggerMap))).toList();
    }

    public EventObservation observeEvent(Event event) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Event Observation Requires The Server Thread");
        }
        EventObservation observation = new EventObservation(Objects.requireNonNull(event, "Observed Event Is Required"));
        synchronized (eventObservations) {
            if (eventObservations.containsKey(event)) {
                throw new IllegalStateException("The Event Already Has An Observer");
            }
            eventObservations.put(event, observation);
        }
        return observation;
    }

    public synchronized void shutdown() {
        HandlerList.unregisterAll(this);
        entriesByType.clear();
        entriesByLookup.clear();
        entriesByDefinition.clear();
        entries.clear();
        graphSnapshots.clear();
        compiledExecution = null;
    }

    public synchronized void closeDefinitionAdmission() {
        definitionAdmissionOpen = false;
        shutdown();
    }

    public synchronized void registerDefinition(String eventType, String nodeType, Class<? extends Event> eventClass,
                                                EventPriority priority, boolean ignoreCancelled,
                                                Function<Event, Map<String, Object>> variableExtractor,
                                                Function<Event, Player> playerExtractor,
                                                String[] aliases) {
        if (!definitionAdmissionOpen || plugin == null || !plugin.isEnabled()) {
            shutdown();
            return;
        }
        String normalizedEventType = normalizeEventKey(eventType);
        TriggerEntry existing = normalizedEventType != null ? entriesByType.get(normalizedEventType) : null;
        if (existing != null && existing.eventClass.equals(eventClass)) {
            indexEntryLookups(existing, eventType, nodeType, aliases);
            return;
        }

        ConcurrentHashMap<String, String> triggerMap = new ConcurrentHashMap<>();
        Method getPlayer = findMethod(eventClass, "getPlayer");
        Method getEntity = findMethod(eventClass, "getEntity");
        Method getWhoClicked = findMethod(eventClass, "getWhoClicked");
        Method getDefinitionId = findMethod(eventClass, "getDefinitionId");
        Method getEventType = findMethod(eventClass, "getEventType");
        TriggerEntry entry = new TriggerEntry(eventType, nodeType, eventClass, priority,
                ignoreCancelled, variableExtractor, playerExtractor, triggerMap,
                getPlayer, getEntity, getWhoClicked, getDefinitionId, getEventType, aliases);
        entries.add(entry);
        indexEntryLookups(entry, eventType, nodeType, aliases);

        EventExecutor bukkitExecutor = (listener, event) -> {
            if (!eventClass.isInstance(event)) return;
            dispatch(entry, event);
        };
        plugin.getServer().getPluginManager().registerEvent(eventClass, this, priority, bukkitExecutor, plugin, ignoreCancelled);
    }

    public synchronized void replaceManagedDefinitions(Set<String> managedNodeTypes, Collection<ManagedDefinition> replacements) {
        if (!definitionAdmissionOpen || plugin == null || !plugin.isEnabled()) {
            shutdown();
            return;
        }
        Set<String> managed = managedNodeTypes == null ? Set.of() : Set.copyOf(managedNodeTypes);
        Map<String, Map<String, String>> bindings = snapshotBindings();
        List<ManagedDefinition> previous = entries.stream().map(TriggerEntry::definition).toList();
        List<ManagedDefinition> retained = entries.stream()
            .filter(entry -> !managed.contains(entry.nodeType))
            .map(TriggerEntry::definition)
            .toList();
        try {
            HandlerList.unregisterAll(this);
            entriesByType.clear();
            entriesByLookup.clear();
            entriesByDefinition.clear();
            entries.clear();
            registerDefinitions(retained);
            registerDefinitions(replacements == null ? List.of() : replacements);
            restoreBindings(bindings);
        } catch (RuntimeException | Error failure) {
            try {
                HandlerList.unregisterAll(this);
                entriesByType.clear();
                entriesByLookup.clear();
                entriesByDefinition.clear();
                entries.clear();
                registerDefinitions(previous);
                restoreBindings(bindings);
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private void registerDefinitions(Collection<ManagedDefinition> definitions) {
        for (ManagedDefinition definition : definitions) {
            registerDefinition(definition.eventType(), definition.nodeType(), definition.eventClass(), definition.priority(),
                definition.ignoreCancelled(), definition.variableExtractor(), definition.playerExtractor(), definition.aliases());
        }
    }

    private void restoreBindings(Map<String, Map<String, String>> bindings) {
        bindings.forEach((eventType, values) -> values.forEach((flowId, startNodeId) -> registerBinding(eventType, flowId, startNodeId)));
    }

    private Map<String, Map<String, String>> snapshotBindings() {
        Map<String, Map<String, String>> bindings = new LinkedHashMap<>();
        for (TriggerEntry entry : entries) {
            bindings.computeIfAbsent(entry.eventType, ignored -> new LinkedHashMap<>()).putAll(entry.triggerMap);
        }
        return bindings;
    }

    private void dispatch(TriggerEntry entry, Event event) {
        long started = System.nanoTime();
        boolean measured = event instanceof BlockBreakEvent && !entry.triggerMap.isEmpty();
        try {
            CompletableFuture<Void> completion = dispatchEvent(entry, event);
            EventObservation observation = eventObservations.get(event);
            if (observation != null) {
                observation.dispatch(entry, completion);
            }
            if (measured) {
                completion.whenComplete((ignored, failure) -> TemporaryLifecycleDiagnostics.recordTiming(
                    TemporaryLifecycleDiagnostics.ExecutionPhase.EVENT_COMPLETION, System.nanoTime() - started));
            }
        } catch (RuntimeException | Error failure) {
            EventObservation observation = eventObservations.get(event);
            if (observation != null) {
                observation.dispatch(entry, CompletableFuture.failedFuture(failure));
            }
            if (measured) {
                TemporaryLifecycleDiagnostics.recordTiming(TemporaryLifecycleDiagnostics.ExecutionPhase.EVENT_COMPLETION,
                    System.nanoTime() - started);
            }
            throw failure;
        } finally {
            if (measured) {
                TemporaryLifecycleDiagnostics.recordTiming(TemporaryLifecycleDiagnostics.ExecutionPhase.BLOCK_DISPATCH,
                    System.nanoTime() - started);
            }
        }
    }

    private CompletableFuture<Void> dispatchEvent(TriggerEntry entry, Event event) {
        if (event instanceof PlayerInteractEvent interaction && interaction.getHand() == EquipmentSlot.OFF_HAND) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, Object> customVars;
        Player player;
        try {
            customVars = entry.variableExtractor.apply(event);
            if (customVars == null) {
                return CompletableFuture.completedFuture(null);
            }
            player = entry.playerExtractor != null ? entry.playerExtractor.apply(event) : null;
            if (player == null) {
                player = playerFromEventVariables(customVars);
            }
        } catch (RuntimeException failure) {
            EventObservation observation = eventObservations.get(event);
            if (observation != null && entry.triggerMap.isEmpty()) {
                observation.reject(entry, "", "", CorrelationId.random(), "TRIGGER.CONTEXT_REJECTED", "event-extraction-failed");
            }
            for (Map.Entry<String, String> trigger : entry.triggerMap.entrySet()) {
                CorrelationId invocationId = CorrelationId.random();
                if (observation != null) {
                    observation.reject(entry, trigger.getKey(), trigger.getValue(), invocationId,
                        "TRIGGER.CONTEXT_REJECTED", "event-extraction-failed");
                }
                long started = TemporaryLifecycleDiagnostics.start();
                Map<String, Object> ingress = ingressIdentity(entry, trigger, invocationId);
                TemporaryLifecycleDiagnostics.event("trigger_ingress", started, ingress);
                TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, ingress, "failed",
                    "TRIGGER.CONTEXT_REJECTED", "event-extraction-failed");
                CompiledTriggerExecution.warnInvocation("event|" + trigger.getKey() + "|TRIGGER.CONTEXT_REJECTED",
                    "Event trigger invocation failed correlationId=" + invocationId.canonicalText()
                        + " diagnosticCode=TRIGGER.CONTEXT_REJECTED");
            }
            return CompletableFuture.completedFuture(null);
        }

        List<DeferredInvocation> deferred = new ArrayList<>();
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        CompiledRuntimeContextAdapter.Result eventSnapshot = null;
        for (Map.Entry<String, String> trigger : entry.triggerMap.entrySet()) {
            CompiledCoreFlowExecutionBridge.ExecutionObservation invocationObservation = null;
            CorrelationId invocationId = CorrelationId.random();
            long started = TemporaryLifecycleDiagnostics.start();
            Map<String, Object> ingress = ingressIdentity(entry, trigger, invocationId);
            Map<String, Object> terminalIdentity = ingress;
            boolean ingressEmitted = false;
            String failureCode = "TRIGGER.GRAPH_UNAVAILABLE";
            String failureReason = "graph-resolution-failed";
            try {
                CoreGraphStorageBoundary.Decoded graph = graphSnapshots.get(trigger.getKey());
                if (graph == null || graph.envelope().assetActivationState() != ResourceActivationState.ACTIVE) {
                    rejectObservation(event, entry, trigger, invocationId, "TRIGGER.GRAPH_UNAVAILABLE",
                        graph == null ? "graph-unavailable" : "graph-disabled");
                    TemporaryLifecycleDiagnostics.event("trigger_ingress", started, ingress);
                    TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, ingress, "rejected",
                        "TRIGGER.GRAPH_UNAVAILABLE", graph == null ? "graph-unavailable" : "graph-disabled");
                    CompiledTriggerExecution.warnInvocation("event|" + trigger.getKey() + "|TRIGGER.GRAPH_UNAVAILABLE",
                        "Event trigger invocation rejected correlationId=" + invocationId.canonicalText()
                            + " diagnosticCode=TRIGGER.GRAPH_UNAVAILABLE");
                    continue;
                }
                failureCode = "TRIGGER.BINDING_REJECTED";
                failureReason = "binding-match-failed";
                if (!matchesAutomationBinding(entry, graph, trigger.getValue(), event)) {
                    continue;
                }
                TemporaryLifecycleDiagnostics.event("trigger_ingress", started, ingress);
                ingressEmitted = true;

                Map<String, Object> identity = TemporaryLifecycleDiagnostics.with(ingress,
                    "revision", graph.envelope().assetRevision(),
                    "startNodeId", trigger.getValue(), "graphHash", graph.envelope().assetHash().canonicalText(), "outcome", "matched");
                terminalIdentity = identity;
                TemporaryLifecycleDiagnostics.event("trigger_binding_selected", started,
                    TemporaryLifecycleDiagnostics.with(identity, "outcome", "selected"));

                failureCode = "TRIGGER.CONTEXT_REJECTED";
                failureReason = "context-adaptation-failed";
                Map<String, Object> eventVars = new HashMap<>();
                if (player != null) {
                    eventVars.put("event.player", player);
                }
                for (Map.Entry<String, Object> varEntry : customVars.entrySet()) {
                    String key = varEntry.getKey();
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    Object value = varEntry.getValue();
                    if (value == null) {
                        eventVars.remove(key);
                    } else {
                        eventVars.put(key, value);
                    }
                }
                CompiledTriggerExecution execution = compiledExecution;
                if (execution != null) {
                    EventObservation eventObservation = eventObservations.get(event);
                    boolean synchronousWindow = execution.requiresSynchronousEventWindow(graph);
                    if (eventObservation != null || !synchronousWindow) {
                        invocationObservation = execution.observeInvocation(invocationId);
                        if (eventObservation != null) {
                            eventObservation.invocation(entry, graph, trigger.getValue(), invocationObservation);
                        }
                    }
                    failureCode = "TRIGGER.EXECUTOR_REJECTED";
                    failureReason = "synchronous-rejection";
                    if (synchronousWindow) {
                        eventSnapshot = null;
                        long phaseStarted = System.nanoTime();
                        CompletableFuture<Void> future = execution.executeSource(graph, trigger.getValue(), player, event, eventVars,
                            null, invocationId, RuntimeExecutionContext.NO_DEADLINE);
                        if (invocationObservation != null) {
                            CompiledCoreFlowExecutionBridge.ExecutionObservation ownedObservation = invocationObservation;
                            future.whenComplete((ignored, failure) -> ownedObservation.finishWithoutExecution(failure));
                        }
                        completions.add(future);
                        TemporaryLifecycleDiagnostics.synchronousCancellationPrefix(System.nanoTime() - phaseStarted);
                        execution.observe(future, invocationId, "event:" + trigger.getKey());
                    } else {
                        if (eventSnapshot == null) {
                            eventSnapshot = execution.snapshotEvent(player, event, eventVars);
                        }
                        deferred.add(new DeferredInvocation(execution, graph, trigger.getValue(), player,
                            eventSnapshot, invocationId, "event:" + trigger.getKey(), invocationObservation));
                    }
                } else {
                    rejectObservation(event, entry, trigger, invocationId, "TRIGGER.EXECUTOR_UNAVAILABLE", "compiled-executor-unavailable");
                    TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, identity, "rejected",
                        "TRIGGER.EXECUTOR_UNAVAILABLE", "compiled-executor-unavailable");
                    CompiledTriggerExecution.warnInvocation("event|" + trigger.getKey() + "|TRIGGER.EXECUTOR_UNAVAILABLE",
                        "Event trigger invocation rejected correlationId=" + invocationId.canonicalText()
                            + " diagnosticCode=TRIGGER.EXECUTOR_UNAVAILABLE");
                }
            } catch (RuntimeException | Error failure) {
                rejectObservation(event, entry, trigger, invocationId, failureCode, failureReason);
                if (invocationObservation != null) {
                    invocationObservation.finishWithoutExecution(failure);
                }
                if (!ingressEmitted) {
                    TemporaryLifecycleDiagnostics.event("trigger_ingress", started, ingress);
                }
                TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, terminalIdentity, "failed",
                    failureCode, failureReason);
                CompiledTriggerExecution.warnInvocation("event|" + trigger.getKey() + "|" + failureCode,
                    "Event trigger invocation failed correlationId=" + invocationId.canonicalText()
                        + " diagnosticCode=" + failureCode);
            }
        }
        if (!deferred.isEmpty()) {
            TemporaryLifecycleDiagnostics.deferredBatch();
            CompletableFuture<Void> batch = executor.runOnMain(plugin, true, cancelled -> {
                List<CompletableFuture<Void>> results = new ArrayList<>();
                for (DeferredInvocation invocation : deferred) {
                    if (cancelled.getAsBoolean()) {
                        invocation.rejectObservation(new CancellationException("Deferred Event Batch Was Cancelled"));
                        continue;
                    }
                    try {
                        results.add(invocation.executePhysically());
                    } catch (RuntimeException | Error failure) {
                        invocation.rejectObservation(failure);
                        results.add(CompletableFuture.failedFuture(failure));
                    }
                }
                return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new));
            });
            completions.add(batch);
            batch.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    deferred.forEach(invocation -> invocation.rejectObservation(failure));
                }
            });
        }
        return CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
    }

    private void rejectObservation(Event event, TriggerEntry entry, Map.Entry<String, String> trigger,
            CorrelationId invocationId, String code, String reason) {
        EventObservation observation = eventObservations.get(event);
        if (observation != null) {
            observation.reject(entry, trigger.getKey(), trigger.getValue(), invocationId, code, reason);
        }
    }

    private Map<String, Object> ingressIdentity(TriggerEntry entry, Map.Entry<String, String> trigger,
                                                CorrelationId invocationId) {
        return TemporaryLifecycleDiagnostics.with(
            TemporaryLifecycleDiagnostics.identity(null, "flow:" + trigger.getKey(), "trigger-execution", null, null,
                invocationId, null, null, null, null),
            "source", "event", "eventType", entry.eventType, "bindingDefinition", entry.nodeType,
            "startNodeId", trigger.getValue(), "outcome", "matched");
    }

    private boolean matchesAutomationBinding(TriggerEntry entry, CoreGraphStorageBoundary.Decoded graph, String startNodeId, Event event) {
        if (graph == null || startNodeId == null || event == null) {
            return true;
        }
        GraphNode node = graph.graphDocument().nodes().stream()
            .filter(candidate -> candidate.instanceId().canonicalText().equals(startNodeId)).findFirst().orElse(null);
        if (node == null) {
            return false;
        }
        Method definitionGetter = entry.cachedDefinitionId;
        if (definitionGetter != null) {
            String selected = "";
            for (String pin : List.of("variable", "timer", "schedule")) {
                if (node.values().containsKey(PinId.of(pin))) {
                    selected = AutomationReferences.id(inputValue(node, pin));
                    break;
                }
            }
            Object actual = invoke(definitionGetter, event);
            if (!selected.isBlank() && (actual == null || !selected.equals(actual.toString()))) {
                return false;
            }
        }
        Method eventTypeGetter = entry.cachedEventType;
        Object selectedType = "event.schedule".equals(node.definition().id().value()) ? "fired" : inputValue(node, "event");
        if (eventTypeGetter != null && selectedType != null && !selectedType.toString().isBlank()) {
            Object actual = invoke(eventTypeGetter, event);
            return actual != null && normalizedValue(selectedType).equals(normalizedValue(actual));
        }
        return true;
    }

    private Object inputValue(GraphNode node, String pin) {
        var input = node.values().get(PinId.of(pin));
        if (input == null) {
            return null;
        }
        var value = input.value();
        return value.locator() == null ? value.value() : value.locator();
    }

    private Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private String normalizedValue(Object value) {
        return value != null ? value.toString().trim().replace(' ', '_').toLowerCase(Locale.ROOT) : "";
    }

    public void registerBinding(String eventType, String flowId, String startNodeId) {
        TriggerEntry entry = resolveEntry(eventType);
        if (entry != null) {
            if (storage != null && storage.hasCoreGraphAuthority()) {
                CoreGraphStorageBoundary.Decoded graph = storage.getCoreGraph("flow", flowId).orElse(null);
                if (graph == null) {
                    graphSnapshots.remove(flowId);
                    return;
                }
                CompiledTriggerExecution execution = compiledExecution;
                if (execution != null && !prepare(flowId, graph, execution)) {
                    return;
                }
                graphSnapshots.put(flowId, graph);
            }
            entry.triggerMap.put(flowId, startNodeId);
        }
    }

    public void refreshGraph(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return;
        }
        CoreGraphStorageBoundary.Decoded graph = storage.getCoreGraph("flow", flowId).orElse(null);
        if (graph == null) {
            graphSnapshots.remove(flowId);
            CompiledTriggerExecution execution = compiledExecution;
            if (execution != null) {
                execution.retire("flow", flowId);
            }
        } else {
            CompiledTriggerExecution execution = compiledExecution;
            if (execution != null && !prepare(flowId, graph, execution)) {
                unregisterBinding(flowId);
                return;
            }
            graphSnapshots.put(flowId, graph);
        }
    }

    private boolean prepare(String flowId, CoreGraphStorageBoundary.Decoded graph, CompiledTriggerExecution execution) {
        try {
            execution.prepareSource(graph);
            return true;
        } catch (RuntimeException exception) {
            Log.warn("Rejected runtime flow " + flowId + ": " + exception.getMessage(), exception);
            execution.retire("flow", flowId);
            return false;
        }
    }

    public void unregisterBinding(String flowId) {
        for (TriggerEntry entry : entries) {
            entry.triggerMap.remove(flowId);
        }
        graphSnapshots.remove(flowId);
        CompiledTriggerExecution execution = compiledExecution;
        if (execution != null) {
            execution.retire("flow", flowId);
        }
    }

    public void clearBindings() {
        for (TriggerEntry entry : entries) {
            entry.triggerMap.clear();
        }
        graphSnapshots.clear();
    }

    public String getNodeType(String eventType) {
        TriggerEntry entry = resolveEntry(eventType);
        return entry != null ? entry.nodeType : null;
    }

    public String resolveEventType(String eventType) {
        TriggerEntry entry = resolveEntry(eventType);
        return entry != null ? normalizeEventKey(entry.eventType) : null;
    }

    public Set<String> getEventTypes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(entriesByType.keySet()));
    }

    public record ManagedDefinition(
        String eventType,
        String nodeType,
        Class<? extends Event> eventClass,
        EventPriority priority,
        boolean ignoreCancelled,
        Function<Event, Map<String, Object>> variableExtractor,
        Function<Event, Player> playerExtractor,
        String[] aliases
    ) {
        public ManagedDefinition {
            aliases = aliases == null ? new String[0] : aliases.clone();
        }
    }

    public boolean hasEventType(String eventType) {
        return resolveEntry(eventType) != null;
    }

    int registeredEntryCount() {
        return entries.size();
    }

    private void indexLookup(String key, TriggerEntry entry, boolean includeInPublicTypes) {
        String normalized = normalizeEventKey(key);
        if (normalized == null) {
            return;
        }
        entriesByLookup.put(normalized, entry);
        if (includeInPublicTypes) {
            entriesByType.put(normalized, entry);
        }
    }

    private void indexEntryLookups(TriggerEntry entry, String eventType, String nodeType, String[] aliases) {
        if (nodeType != null && nodeType.contains("/")) {
            entriesByDefinition.put(ContractRef.parseCanonicalText(nodeType, NodeId::new), entry);
        }
        indexLookup(eventType, entry, true);
        for (String alias : aliases) {
            indexLookup(alias, entry, true);
        }

        if (nodeType != null && !nodeType.isEmpty()) {
            indexLookup(nodeType, entry, false);
            String normalizedNodeType = normalizeEventKey(nodeType);
            if (normalizedNodeType != null && normalizedNodeType.startsWith("event:")) {
                indexLookup(normalizedNodeType.substring(6), entry, false);
            }
        }

        String normalizedEventType = normalizeEventKey(eventType);
        if (normalizedEventType != null) {
            if (normalizedEventType.startsWith("player_") && normalizedEventType.length() > 7) {
                indexLookup(normalizedEventType.substring(7), entry, false);
            }
            if (normalizedEventType.startsWith("block_") && normalizedEventType.length() > 6) {
                indexLookup(normalizedEventType.substring(6), entry, false);
            }
            if (normalizedEventType.startsWith("async_") && normalizedEventType.length() > 6) {
                indexLookup(normalizedEventType.substring(6), entry, false);
            }

            for (Map.Entry<String, String> legacyAlias : LEGACY_EVENT_ALIASES.entrySet()) {
                if (legacyAlias.getValue().equals(normalizedEventType)) {
                    indexLookup(legacyAlias.getKey(), entry, false);
                }
            }
        }
    }

    private TriggerEntry resolveEntry(String eventType) {
        if (eventType != null && eventType.contains("/")) {
            try {
                return entriesByDefinition.get(ContractRef.parseCanonicalText(eventType, NodeId::new));
            } catch (IllegalArgumentException invalidReference) {
                return null;
            }
        }
        String normalized = normalizeEventKey(eventType);
        if (normalized == null) {
            return null;
        }

        TriggerEntry direct = entriesByLookup.get(normalized);
        if (direct != null) {
            return direct;
        }

        String legacy = LEGACY_EVENT_ALIASES.get(normalized);
        if (legacy != null) {
            TriggerEntry mapped = entriesByLookup.get(legacy);
            if (mapped != null) {
                return mapped;
            }
        }

        if (!normalized.startsWith("player_")) {
            TriggerEntry playerPrefixed = entriesByLookup.get("player_" + normalized);
            if (playerPrefixed != null) {
                return playerPrefixed;
            }
        }

        if (!normalized.startsWith("block_")) {
            TriggerEntry blockPrefixed = entriesByLookup.get("block_" + normalized);
            if (blockPrefixed != null) {
                return blockPrefixed;
            }
        }

        return null;
    }

    private String normalizeEventKey(String key) {
        if (key == null) {
            return null;
        }
        if (key.contains("/")) {
            try {
                return ContractRef.parseCanonicalText(key, NodeId::new).canonicalText();
            } catch (IllegalArgumentException invalidReference) {
                return null;
            }
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.startsWith("event:")) {
            normalized = normalized.substring(6);
        } else if (normalized.startsWith("event.")) {
            normalized = normalized.substring(6);
        }
        return normalized.replace('.', '_');
    }

    public void registerFromContainer(Object container) {
        for (Method method : container.getClass().getDeclaredMethods()) {
            FlowTrigger annotation = method.getAnnotation(FlowTrigger.class);
            if (annotation == null) continue;

            method.setAccessible(true);

            BiConsumer<Event, Map<String, Object>> varExtractor = buildVariableExtractor(method, container);
            Function<Event, Player> playerExtractor = buildPlayerExtractor(annotation, container);

            String nodeType = annotation.nodeType().isEmpty() ? "event:" + annotation.eventType() : annotation.nodeType();

            registerDefinition(
                    annotation.eventType(),
                    nodeType,
                    (Class<? extends Event>) annotation.eventClass(),
                    annotation.priority(),
                    annotation.ignoreCancelled(),
                    event -> {
                        Map<String, Object> vars = new LinkedHashMap<>();
                        varExtractor.accept(event, vars);
                        return vars;
                    },
                    playerExtractor,
                    annotation.aliases()
            );
        }
    }

    private BiConsumer<Event, Map<String, Object>> buildVariableExtractor(Method method, Object container) {
        return (event, vars) -> {
            try {
                method.invoke(container, event, vars);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("Variable extractor failed: " + method.getName(), cause);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Function<Event, Player> buildPlayerExtractor(FlowTrigger annotation, Object container) {
        if (!annotation.playerEvent()) {
            return null;
        }

        String extractorName = annotation.playerExtractor();
        if (!extractorName.isEmpty()) {
            Method extractorMethod;
            try {
                extractorMethod = container.getClass().getDeclaredMethod(extractorName, annotation.eventClass());
                extractorMethod.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Player extractor method not found: " + extractorName + "(" + annotation.eventClass().getSimpleName() + ")", e);
            }
            Object cont = container;
            return event -> {
                try {
                    return (Player) extractorMethod.invoke(cont, event);
                } catch (Exception e) {
                    throw new IllegalStateException("Player extractor failed: " + extractorName, e);
                }
            };
        }

        Class<?> eventClass = annotation.eventClass();
        Method getPlayer = findMethod(eventClass, "getPlayer");
        Method getEntity = findMethod(eventClass, "getEntity");
        Method getWhoClicked = findMethod(eventClass, "getWhoClicked");
        final Method cachedGetPlayer = getPlayer;
        final Method cachedGetEntity = getEntity;
        final Method cachedGetWhoClicked = getWhoClicked;
        return event -> {
            Player player = invokePlayerMethod(cachedGetPlayer, event);
            if (player != null) return player;
            player = invokePlayerMethod(cachedGetEntity, event);
            if (player != null) return player;
            player = invokePlayerMethod(cachedGetWhoClicked, event);
            if (player != null) return player;
            return null;
        };
    }

    private static Player playerFromEventVariables(Map<String, Object> variables) {
        Object associated = variables.get("event.player");
        if (associated instanceof Player player) {
            return player;
        }

        Object owner = variables.get("owner");
        if (owner instanceof Player player) {
            return player;
        }
        owner = variables.get("event.owner");
        return owner instanceof Player player ? player : null;
    }

    private static Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static Player invokePlayerMethod(Method method, Event event) {
        if (method == null) return null;
        try {
            Object value = method.invoke(event);
            return value instanceof Player player ? player : null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to resolve a player from " + event.getEventName() + " using " + method.getName(), exception);
        }
    }

    private static class TriggerEntry {
        final String eventType;
        final String nodeType;
        final Class<? extends Event> eventClass;
        final EventPriority priority;
        final boolean ignoreCancelled;
        final Function<Event, Map<String, Object>> variableExtractor;
        final Function<Event, Player> playerExtractor;
        final ConcurrentHashMap<String, String> triggerMap;
        final Method cachedGetPlayer;
        final Method cachedGetEntity;
        final Method cachedGetWhoClicked;
        final Method cachedDefinitionId;
        final Method cachedEventType;
        final String[] aliases;

        TriggerEntry(String eventType, String nodeType, Class<? extends Event> eventClass,
                     EventPriority priority, boolean ignoreCancelled,
                     Function<Event, Map<String, Object>> variableExtractor,
                     Function<Event, Player> playerExtractor,
                     ConcurrentHashMap<String, String> triggerMap,
                     Method cachedGetPlayer, Method cachedGetEntity, Method cachedGetWhoClicked,
                     Method cachedDefinitionId, Method cachedEventType,
                     String[] aliases) {
            this.eventType = eventType;
            this.nodeType = nodeType;
            this.eventClass = eventClass;
            this.priority = priority;
            this.ignoreCancelled = ignoreCancelled;
            this.variableExtractor = variableExtractor;
            this.playerExtractor = playerExtractor;
            this.triggerMap = triggerMap;
            this.cachedGetPlayer = cachedGetPlayer;
            this.cachedGetEntity = cachedGetEntity;
            this.cachedGetWhoClicked = cachedGetWhoClicked;
            this.cachedDefinitionId = cachedDefinitionId;
            this.cachedEventType = cachedEventType;
            this.aliases = aliases == null ? new String[0] : aliases.clone();
        }

        ManagedDefinition definition() {
            return new ManagedDefinition(eventType, nodeType, eventClass, priority, ignoreCancelled,
                variableExtractor, playerExtractor, aliases);
        }
    }

    private record DeferredInvocation(CompiledTriggerExecution execution, CoreGraphStorageBoundary.Decoded graph, String startNodeId,
                                      Player player, CompiledRuntimeContextAdapter.Result snapshot, CorrelationId invocationId,
                                      String source, CompiledCoreFlowExecutionBridge.ExecutionObservation observation) {
        private CompletableFuture<Void> execute() {
            long phaseStarted = System.nanoTime();
            CompletableFuture<Void> future = execution.executeSourceDeferred(graph, startNodeId, player, snapshot, invocationId);
            future.whenComplete((ignored, failure) -> {
                if (observation != null) {
                    observation.finishWithoutExecution(failure);
                }
                long completed = System.nanoTime();
                TemporaryLifecycleDiagnostics.deferredExecution(completed - phaseStarted);
            });
            execution.observe(future, invocationId, source);
            return future;
        }

        private CompletableFuture<Void> executePhysically() {
            CompletableFuture<Void> logical = execute();
            return observation == null ? logical : logical.handle((ignored, failure) -> failure)
                .thenCombine(observation.completion(), (failure, result) -> failure).thenCompose(failure -> failure == null
                    ? CompletableFuture.<Void>completedFuture(null) : CompletableFuture.<Void>failedFuture(failure)).toCompletableFuture();
        }

        private void rejectObservation(Throwable failure) {
            if (observation != null) {
                observation.finishWithoutExecution(failure);
            }
        }
    }

    public final class EventObservation implements AutoCloseable {
        private final Event event;
        private final List<CompletableFuture<Map<String, Object>>> invocations = new ArrayList<>();
        private final List<CompletableFuture<Map<String, Object>>> dispatches = new ArrayList<>();
        private final List<Map<String, Object>> rejections = new ArrayList<>();
        private CompletionStage<Map<String, Object>> completion;

        private EventObservation(Event event) {
            this.event = event;
        }

        private synchronized void invocation(TriggerEntry entry, CoreGraphStorageBoundary.Decoded graph, String startNodeId,
                CompiledCoreFlowExecutionBridge.ExecutionObservation observation) {
            Map<String, Object> selected = Map.of("eventType", entry.eventType, "resourceType", graph.envelope().resourceType(),
                "resourceId", graph.graphDocument().resource().id(), "revision", graph.envelope().assetRevision(), "checksum", graph.envelope().assetHash().canonicalText(),
                "startNodeId", startNodeId);
            invocations.add(observation.completion().thenApply(result -> {
                Map<String, Object> value = new LinkedHashMap<>(result.canonicalValue());
                value.putAll(selected);
                return Map.copyOf(value);
            }).toCompletableFuture());
        }

        private synchronized void dispatch(TriggerEntry entry, CompletableFuture<Void> execution) {
            boolean rejected = rejections.stream().anyMatch(rejection -> entry.eventType.equals(rejection.get("eventType")));
            dispatches.add(execution.handle((ignored, failure) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("eventType", entry.eventType);
                value.put("status", failure == null ? rejected ? "rejected" : "complete" : "failed");
                if (failure != null) {
                    value.put("failure", failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage());
                }
                return Map.copyOf(value);
            }));
        }

        private synchronized void reject(TriggerEntry entry, String flowId, String startNodeId,
                CorrelationId invocationId, String code, String reason) {
            rejections.add(Map.of("eventType", entry.eventType, "resourceType", "flow", "resourceId", flowId,
                "startNodeId", startNodeId, "invocationId", invocationId.canonicalText(), "code", code, "reason", reason));
        }

        public synchronized CompletionStage<Map<String, Object>> completion() {
            if (completion == null) {
                throw new IllegalStateException("The Event Observation Must Be Sealed After Dispatch");
            }
            return completion;
        }

        @Override
        public synchronized void close() {
            if (completion != null) {
                return;
            }
            eventObservations.remove(event, this);
            List<CompletableFuture<Map<String, Object>>> executions = List.copyOf(invocations);
            List<CompletableFuture<Map<String, Object>>> observedDispatches = List.copyOf(dispatches);
            List<Map<String, Object>> rejected = List.copyOf(rejections);
            List<CompletableFuture<?>> pending = new ArrayList<>(executions);
            pending.addAll(observedDispatches);
            completion = CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenApply(ignored -> Map.of(
                "invocations", executions.stream().map(CompletableFuture::join).toList(),
                "dispatches", observedDispatches.stream().map(CompletableFuture::join).toList(),
                "rejections", rejected,
                "physicalComplete", executions.stream().map(CompletableFuture::join)
                    .allMatch(execution -> Boolean.TRUE.equals(execution.get("physicalComplete")))))
                .minimalCompletionStage();
        }
    }
}
