package restudio.resync.flow.handler.event;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.Log;
import restudio.resync.diagnostics.BoundedDiagnosticDeduplicator;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.handler.HandlerConfig;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.triggers.TriggerDispatcher;
import restudio.resync.api.ExtensionRegistryActivation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FlowEventRegistry {
    private static final Set<String> COMMAND_DERIVED_SOURCES = Set.of(
        "event.bound_command", "event.command_label", "event.args", "event.args_list", "event.args_count", "event.is_console"
    );
    private static final Set<String> SYSTEM_MANAGED_EVENT_IDS = Set.of(
        "event.server.start",
        "event.server.stop",
        "event.server.tick",
        "event.server.save",
        "event.plugin.enable",
        "event.plugin.disable",
        "event.world.load",
        "event.world.unload",
        "event.chunk.load",
        "event.chunk.unload",
        "event.resync.command"
    );

    private final TriggerDispatcher dispatcher;
    private final TypeAdapterRegistry typeAdapters;
    private final Map<String, EventNodeDefinition> eventDefinitions = new ConcurrentHashMap<>();
    private final Map<String, EventRegistration> registrations = new ConcurrentHashMap<>();
    private final boolean staging;
    private final BoundedDiagnosticDeduplicator reportedOutputMismatches = new BoundedDiagnosticDeduplicator(1024);
    private final BoundedDiagnosticDeduplicator reportedMappingFailures = new BoundedDiagnosticDeduplicator(1024);
    private volatile ExtensionRegistryActivation activation;
    private volatile Set<String> appliedManagedNodeTypes = Set.of();
    private volatile Consumer<ExtensionRegistryActivation.State> activationListener;
    private volatile boolean activationClosed;

    public FlowEventRegistry(TriggerDispatcher dispatcher) {
        this(dispatcher, new TypeAdapterRegistry(), false);
    }

    public FlowEventRegistry(TriggerDispatcher dispatcher, TypeAdapterRegistry typeAdapters) {
        this(dispatcher, typeAdapters, false);
    }

    private FlowEventRegistry(TriggerDispatcher dispatcher, TypeAdapterRegistry typeAdapters, boolean staging) {
        this.dispatcher = dispatcher;
        this.typeAdapters = typeAdapters != null ? typeAdapters : new TypeAdapterRegistry();
        this.staging = staging;
    }

    public synchronized void bindActivation(ExtensionRegistryActivation activation) {
        if (activationClosed) {
            return;
        }
        if (this.activation == activation) {
            return;
        }
        ExtensionRegistryActivation previous = this.activation;
        Consumer<ExtensionRegistryActivation.State> previousListener = activationListener;
        if (previous != null && previousListener != null) {
            previous.removeListener(previousListener);
        }
        this.activation = activation;
        this.activationListener = null;
        if (activation == null) {
            clearLiveProjection();
            return;
        }
        if (!staging) {
            Consumer<ExtensionRegistryActivation.State> listener = state -> activateSnapshot(state.events());
            activationListener = listener;
            try {
                activation.addListener(listener);
            } catch (RuntimeException | Error failure) {
                activation.removeListener(listener);
                activationListener = null;
                this.activation = null;
                try {
                    clearLiveProjection();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
    }

    public synchronized void closeActivation() {
        if (activationClosed) {
            return;
        }
        activationClosed = true;
        ExtensionRegistryActivation previous = activation;
        Consumer<ExtensionRegistryActivation.State> previousListener = activationListener;
        activation = null;
        activationListener = null;
        appliedManagedNodeTypes = Set.of();
        if (previous != null && previousListener != null) {
            previous.removeListener(previousListener);
        }
    }

    public void registerFromJson(List<NodeDefinition> definitions) {
        replaceDefinitions(definitions);
    }

    public void replaceDefinitions(List<NodeDefinition> definitions) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowEventRegistry.class, target -> {
                target.replaceDefinitionsLocal(definitions);
                return null;
            });
            return;
        }
        replaceDefinitionsLocal(definitions);
    }

    private void replaceDefinitionsLocal(List<NodeDefinition> definitions) {
        Map<String, EventRegistration> next = new LinkedHashMap<>();
        for (NodeDefinition def : definitions == null ? List.<NodeDefinition>of() : definitions) {
            EventRegistration registration = buildRegistration(def);
            if (registration != null) {
                next.put(registration.nodeType(), registration);
            }
        }
        if (staging) {
            registrations.clear();
            registrations.putAll(next);
            eventDefinitions.clear();
            next.forEach((id, registration) -> eventDefinitions.put(registration.eventDefinition().definition().getId(), registration.eventDefinition()));
            return;
        }
        FlowEventRegistry candidate = new FlowEventRegistry(dispatcher, typeAdapters, true);
        candidate.registrations.putAll(next);
        next.forEach((id, registration) -> candidate.eventDefinitions.put(registration.eventDefinition().definition().getId(), registration.eventDefinition()));
        replaceFromLocal(candidate);
    }

    private EventRegistration buildRegistration(NodeDefinition def) {
        if (def == null || !def.isTrigger()) {
            return null;
        }
        if (bindingContext(def) == null) {
            return null;
        }
        String eventClassName = def.getEventType();
        if (eventClassName == null || eventClassName.isBlank()) {
            if ("event.custom_content".equals(def.getId())) {
                return null;
            }
            Log.warn("[FlowEventRegistry] Trigger node missing eventType: " + def.getId());
            return null;
        }

        Class<? extends Event> eventClass;
        try {
            eventClass = Class.forName(eventClassName).asSubclass(Event.class);
        } catch (ClassNotFoundException e) {
            Log.warn("[FlowEventRegistry] Event class not found: " + eventClassName);
            return null;
        } catch (ClassCastException e) {
            Log.warn("[FlowEventRegistry] Event type does not extend Bukkit Event: " + eventClassName);
            return null;
        }

        HandlerConfig config = new HandlerConfig(def.getHandlerConfig());
        EventPriority priority = parsePriority(config.getString("priority", "NORMAL"));
        boolean ignoreCancelled = config.getBoolean("ignoreCancelled", false);
        boolean playerEvent = config.getBoolean("playerEvent", true);
        Function<Event, Map<String, Object>> variableExtractor = buildVariableExtractor(def);
        Function<Event, Player> playerExtractor = playerEvent ? buildPlayerExtractor(eventClass) : null;
        String context = bindingContext(def);
        return new EventRegistration(new EventNodeDefinition(def, eventClass), context, context,
            eventClass, priority, ignoreCancelled, variableExtractor, playerExtractor, def.getAliases().toArray(new String[0]));
    }

    private void registerLive(EventRegistration registration) {
        dispatcher.registerDefinition(registration.eventType(), registration.nodeType(), registration.eventClass(),
            registration.priority(), registration.ignoreCancelled(), registration.variableExtractor(),
            registration.playerExtractor(), registration.aliases());
    }

    public FlowEventRegistry copyForStaging() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().events();
        }
        return copyForStaging(typeAdapters);
    }

    public FlowEventRegistry copyForStaging(TypeAdapterRegistry stagedAdapters) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().events().copyForStaging(stagedAdapters);
        }
        FlowEventRegistry copy = new FlowEventRegistry(dispatcher, stagedAdapters, true);
        registrations.forEach((id, registration) -> {
            EventRegistration detached = copyRegistration(registration);
            copy.registrations.put(id, detached);
            copy.eventDefinitions.put(detached.eventDefinition().definition().getId(), detached.eventDefinition());
        });
        return copy;
    }

    private EventRegistration copyRegistration(EventRegistration registration) {
        EventNodeDefinition event = registration.eventDefinition();
        return new EventRegistration(new EventNodeDefinition(event.definition().copy(), event.eventClass()),
            registration.eventType(), registration.nodeType(), registration.eventClass(), registration.priority(),
            registration.ignoreCancelled(), registration.variableExtractor(), registration.playerExtractor(), registration.aliases());
    }

    public synchronized void replaceFrom(FlowEventRegistry staged) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowEventRegistry.class, target -> {
                target.replaceFromLocal(staged);
                return null;
            });
            return;
        }
        replaceFromLocal(staged);
    }

    private synchronized void replaceFromLocal(FlowEventRegistry staged) {
        if (staged == null) {
            throw new IllegalArgumentException("A staged event registry is required");
        }
        if (staging) {
            registrations.clear();
            eventDefinitions.clear();
            staged.registrations.forEach((id, registration) -> {
                EventRegistration detached = copyRegistration(registration);
                registrations.put(id, detached);
                eventDefinitions.put(detached.eventDefinition().definition().getId(), detached.eventDefinition());
            });
            return;
        }
        Map<String, EventRegistration> detached = new LinkedHashMap<>();
        staged.registrations.forEach((id, registration) -> detached.put(id, copyRegistration(registration)));
        dispatcher.replaceManagedDefinitions(registrations.values().stream().map(EventRegistration::nodeType).collect(Collectors.toSet()), detached.values().stream()
            .map(EventRegistration::dispatcherDefinition).toList());
        registrations.clear();
        registrations.putAll(detached);
        eventDefinitions.clear();
        detached.forEach((id, registration) -> eventDefinitions.put(registration.eventDefinition().definition().getId(), registration.eventDefinition()));
    }

    public synchronized void unregisterOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return;
        }
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowEventRegistry.class, target -> {
                target.unregisterOwnerLocal(owner);
                return null;
            });
            return;
        }
        unregisterOwnerLocal(owner);
    }

    private synchronized void unregisterOwnerLocal(String owner) {
        List<NodeDefinition> retained = registrations.values().stream()
            .map(registration -> registration.eventDefinition().definition())
            .filter(definition -> !owner.equalsIgnoreCase(definition.getOwner()))
            .toList();
        replaceDefinitionsLocal(retained);
    }

    private void registerLegacy(List<NodeDefinition> definitions) {
        for (NodeDefinition def : definitions) {
            EventRegistration registration = buildRegistration(def);
            if (registration != null) {
                registerLive(registration);
                registrations.put(registration.nodeType(), registration);
                eventDefinitions.put(def.getId(), registration.eventDefinition());
            }
        }
    }

    private EventPriority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return EventPriority.NORMAL;
        }
        try {
            return EventPriority.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EventPriority.NORMAL;
        }
    }

    Function<Event, Map<String, Object>> buildVariableExtractor(NodeDefinition def) {
        List<NodeDefinition.PinMapping> mappings = def.getOutputMappings();
        if (mappings.isEmpty()) {
            return event -> Map.of();
        }
        return event -> {
            Map<String, Object> commandVariables = Map.of();
            if ("event.command".equals(def.getId()) && event instanceof PlayerCommandPreprocessEvent command) {
                commandVariables = GlobalTriggers.commandEventVariables(command.getPlayer(), command.getMessage(), command.isCancelled());
            } else if ("event.server.command".equals(def.getId()) && event instanceof ServerCommandEvent command) {
                commandVariables = GlobalTriggers.commandEventVariables(command.getSender(), command.getCommand(), command.isCancelled());
            }
            Map<String, Object> vars = new LinkedHashMap<>(commandVariables);
            for (NodeDefinition.PinMapping mapping : mappings) {
                if (COMMAND_DERIVED_SOURCES.contains(mapping.source()) && mapping.source().equals(mapping.target())
                    && commandVariables.containsKey(mapping.source())) {
                    continue;
                }
                if (mapping.source().startsWith("event.")) {
                    String getterChain = mapping.source().substring(6);
                    Object value;
                    try {
                        value = resolveGetterChain(event, getterChain);
                    } catch (IllegalStateException exception) {
                        String failure = def.getId() + "." + mapping.target() + " from " + mapping.source() + ": " + exception.getMessage();
                        if (reportedMappingFailures.add(failure)) {
                            Log.warn("[FlowEventRegistry] Event output mapping failed: " + failure);
                        }
                        continue;
                    }
                    if (value != null) {
                        Object adapted = adaptOutput(def, mapping.target(), value);
                        if (adapted != null) {
                            vars.put(mapping.target(), adapted);
                            if (!mapping.source().equals(mapping.target())) {
                                vars.put(mapping.source(), adapted);
                            }
                        }
                    }
                }
            }
            return vars;
        };
    }

    private Object adaptOutput(NodeDefinition definition, String pinName, Object value) {
        NodeDefinition.PinDefinition pin = definition.getOutputs().stream().filter(candidate -> pinName.equals(candidate.getName())).findFirst().orElse(null);
        FlowDataType type = pin != null ? pin.getDataType() : null;
        if (type == null || type == FlowDataType.ANY || type.getJavaType() == null || type.getJavaType().isInstance(value)) {
            return value;
        }
        if (type.getParent() == FlowDataType.RESOURCE_REFERENCE) {
            return new FlowResourceReference(type.getId(), String.valueOf(value), "event", true, Map.of());
        }
        Object adapted = typeAdapters.adapt(value, type.getJavaType());
        if (adapted == null) {
            if (pin != null && pin.isOptional()) {
                return null;
            }
            String mismatch = definition.getId() + "." + pinName + " expected " + type.getId() + " but received " + value.getClass().getName();
            if (reportedOutputMismatches.add(mismatch)) {
                Log.warn("[FlowEventRegistry] Event output type mismatch: " + mismatch);
            }
        }
        return adapted;
    }

    private Object resolveGetterChain(Object target, String chain) {
        return resolveGetterChain(target, chain.split("\\."), 0);
    }

    private Object resolveGetterChain(Object current, String[] parts, int index) {
        if (current == null || index >= parts.length) {
            return current;
        }
        if (current instanceof Iterable<?> values) {
            List<Object> resolved = new ArrayList<>();
            for (Object value : values) {
                Object item = resolveGetterChain(value, parts, index);
                if (item != null) {
                    resolved.add(item);
                }
            }
            return resolved;
        }
        String part = parts[index];
        String property = camelCase(part);
        Method method = findMethod(current.getClass(), property);
        if (method == null) {
            method = findMethod(current.getClass(), "get" + capitalize(property));
        }
        if (method == null && !property.startsWith("is")) {
            method = findMethod(current.getClass(), "is" + capitalize(property));
        }
        if (method == null) {
            throw new IllegalStateException("Event getter is unavailable: " + current.getClass().getName() + "." + part);
        }
        try {
            return resolveGetterChain(method.invoke(current), parts, index + 1);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Event getter failed: " + current.getClass().getName() + "." + method.getName(), exception);
        }
    }

    private Method findMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String camelCase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean capitalizeNext = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(character));
                capitalizeNext = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    Function<Event, Player> buildPlayerExtractor(Class<? extends Event> eventClass) {
        Method cachedGetPlayer = findMethod(eventClass, "getPlayer");
        Method cachedGetDamager = findMethod(eventClass, "getDamager");
        Method cachedGetEntity = findMethod(eventClass, "getEntity");
        Method cachedGetWhoClicked = findMethod(eventClass, "getWhoClicked");
        Method cachedGetSender = findMethod(eventClass, "getSender");
        return event -> {
            Player player = invokePlayerExtractor(cachedGetPlayer, event);
            if (player != null) {
                return player;
            }
            player = invokePlayerExtractor(cachedGetDamager, event);
            if (player != null) {
                return player;
            }
            player = invokePlayerExtractor(cachedGetEntity, event);
            if (player != null) {
                return player;
            }
            player = invokePlayerExtractor(cachedGetWhoClicked, event);
            if (player != null) {
                return player;
            }
            return invokePlayerExtractor(cachedGetSender, event);
        };
    }

    private Player invokePlayerExtractor(Method method, Event event) {
        if (method == null) {
            return null;
        }
        try {
            Object result = method.invoke(event);
            if (result instanceof Player player) {
                return player;
            }
            return result instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to resolve player from event " + event.getEventName() + " using " + method.getName(), exception);
        }
    }

    private String normalizeEventKey(String key) {
        if (key == null) {
            return null;
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

    static boolean isSystemManagedEvent(String nodeId) {
        return nodeId != null && SYSTEM_MANAGED_EVENT_IDS.contains(nodeId.toLowerCase(Locale.ROOT));
    }

    public Map<String, EventNodeDefinition> getEventDefinitions() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().events().getEventDefinitions();
        }
        return Map.copyOf(eventDefinitions);
    }

    public static String bindingContext(NodeDefinition definition) {
        if (definition == null || !definition.isTrigger() || isSystemManagedEvent(definition.getId())
            || "event.click".equals(definition.getId()) || definition.getEventType() == null || definition.getEventType().isBlank()) {
            return null;
        }
        return NodeDefinition.sourceReference(OwnerId.of(definition.getOwner()), definition.getId()).canonicalText();
    }

    private void activateSnapshot(FlowEventRegistry staged) {
        if (activationClosed || staged == null || staged == this || staging) {
            return;
        }
        dispatcher.replaceManagedDefinitions(appliedManagedNodeTypes, staged.registrations.values().stream()
            .map(EventRegistration::dispatcherDefinition).toList());
        appliedManagedNodeTypes = staged.registrations.values().stream().map(EventRegistration::nodeType).collect(Collectors.toUnmodifiableSet());
    }

    private void clearLiveProjection() {
        Set<String> applied = appliedManagedNodeTypes;
        if (applied.isEmpty()) {
            return;
        }
        if (dispatcher != null) {
            dispatcher.replaceManagedDefinitions(applied, List.of());
        }
        appliedManagedNodeTypes = Set.of();
    }

    public record EventNodeDefinition(NodeDefinition definition, Class<? extends Event> eventClass) {
    }

    private record EventRegistration(
        EventNodeDefinition eventDefinition,
        String eventType,
        String nodeType,
        Class<? extends Event> eventClass,
        EventPriority priority,
        boolean ignoreCancelled,
        Function<Event, Map<String, Object>> variableExtractor,
        Function<Event, Player> playerExtractor,
        String[] aliases
    ) {
        private TriggerDispatcher.ManagedDefinition dispatcherDefinition() {
            return new TriggerDispatcher.ManagedDefinition(eventType, nodeType, eventClass, priority, ignoreCancelled,
                variableExtractor, playerExtractor, aliases);
        }
    }
}
