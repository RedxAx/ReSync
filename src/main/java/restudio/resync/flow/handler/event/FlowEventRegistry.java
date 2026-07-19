package restudio.resync.flow.handler.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.Log;
import restudio.resync.diagnostics.BoundedDiagnosticDeduplicator;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerConfig;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.triggers.TriggerDispatcher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class FlowEventRegistry {
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
    private final BoundedDiagnosticDeduplicator reportedOutputMismatches = new BoundedDiagnosticDeduplicator(1024);
    private final BoundedDiagnosticDeduplicator reportedMappingFailures = new BoundedDiagnosticDeduplicator(1024);

    public FlowEventRegistry(TriggerDispatcher dispatcher) {
        this(dispatcher, new TypeAdapterRegistry());
    }

    public FlowEventRegistry(TriggerDispatcher dispatcher, TypeAdapterRegistry typeAdapters) {
        this.dispatcher = dispatcher;
        this.typeAdapters = typeAdapters != null ? typeAdapters : new TypeAdapterRegistry();
    }

    public void registerFromJson(List<NodeDefinition> definitions) {
        for (NodeDefinition def : definitions) {
            if (!def.isTrigger()) {
                continue;
            }
            if (isSystemManagedEvent(def.getId())) {
                continue;
            }
            String eventClassName = def.getEventType();
            if (eventClassName == null || eventClassName.isBlank()) {
                if ("event.custom_content".equals(def.getId())) {
                    continue;
                }
                Log.warn("[FlowEventRegistry] Trigger node missing eventType: " + def.getId());
                continue;
            }

            Class<? extends Event> eventClass;
            try {
                eventClass = Class.forName(eventClassName).asSubclass(Event.class);
            } catch (ClassNotFoundException e) {
                Log.warn("[FlowEventRegistry] Event class not found: " + eventClassName);
                continue;
            } catch (ClassCastException e) {
                Log.warn("[FlowEventRegistry] Event type does not extend Bukkit Event: " + eventClassName);
                continue;
            }

            HandlerConfig config = new HandlerConfig(def.getHandlerConfig());
            EventPriority priority = parsePriority(config.getString("priority", "NORMAL"));
            boolean ignoreCancelled = config.getBoolean("ignoreCancelled", false);
            boolean playerEvent = config.getBoolean("playerEvent", true);

            Function<Event, Map<String, Object>> variableExtractor = buildVariableExtractor(def);
            Function<Event, Player> playerExtractor = playerEvent ? buildPlayerExtractor(eventClass) : null;

            dispatcher.registerDefinition(
                normalizeEventKey(def.getId()),
                def.getId(),
                eventClass,
                priority,
                ignoreCancelled,
                variableExtractor,
                playerExtractor,
                def.getAliases().toArray(new String[0])
            );

            eventDefinitions.put(def.getId(), new EventNodeDefinition(def, eventClass));
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
            Map<String, Object> vars = new LinkedHashMap<>();
            for (NodeDefinition.PinMapping mapping : mappings) {
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
            return result instanceof Player player ? player : null;
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
        return Map.copyOf(eventDefinitions);
    }

    public record EventNodeDefinition(NodeDefinition definition, Class<? extends Event> eventClass) {
    }
}
