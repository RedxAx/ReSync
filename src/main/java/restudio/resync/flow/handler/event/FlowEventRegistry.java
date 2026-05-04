package restudio.resync.flow.handler.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import restudio.resync.Log;
import restudio.resync.flow.handler.HandlerConfig;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.triggers.TriggerDispatcher;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class FlowEventRegistry {

    private final TriggerDispatcher dispatcher;
    private final Map<String, EventNodeDefinition> eventDefinitions = new ConcurrentHashMap<>();

    public FlowEventRegistry(TriggerDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void registerFromJson(List<NodeDefinition> definitions) {
        for (NodeDefinition def : definitions) {
            if (!def.isTrigger()) {
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
                eventClass = (Class<? extends Event>) Class.forName(eventClassName);
            } catch (ClassNotFoundException e) {
                Log.warn("[FlowEventRegistry] Event class not found: " + eventClassName);
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

    private Function<Event, Map<String, Object>> buildVariableExtractor(NodeDefinition def) {
        List<NodeDefinition.PinMapping> mappings = def.getOutputMappings();
        if (mappings.isEmpty()) {
            return event -> Map.of();
        }
        return event -> {
            Map<String, Object> vars = new LinkedHashMap<>();
            for (NodeDefinition.PinMapping mapping : mappings) {
                if (mapping.source().startsWith("event.")) {
                    String getterChain = mapping.source().substring(6);
                    Object value = resolveGetterChain(event, getterChain);
                    if (value != null) {
                        vars.put(mapping.target(), value);
                    }
                }
            }
            return vars;
        };
    }

    private Object resolveGetterChain(Object target, String chain) {
        String[] parts = chain.split("\\.");
        Object current = target;
        for (String part : parts) {
            if (current == null) {
                return null;
            }
            Method method = findMethod(current.getClass(), part);
            if (method == null) {
                method = findMethod(current.getClass(), "get" + capitalize(part));
            }
            if (method == null && !part.startsWith("is")) {
                method = findMethod(current.getClass(), "is" + capitalize(part));
            }
            if (method == null) {
                return null;
            }
            try {
                current = method.invoke(current);
            } catch (Exception e) {
                return null;
            }
        }
        return current;
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

    private Function<Event, Player> buildPlayerExtractor(Class<? extends Event> eventClass) {
        Method getPlayer = null;
        Method getEntity = null;
        Method getWhoClicked = null;
        try {
            getPlayer = eventClass.getMethod("getPlayer");
        } catch (NoSuchMethodException ignored) {
        }
        try {
            getEntity = eventClass.getMethod("getEntity");
        } catch (NoSuchMethodException ignored) {
        }
        try {
            getWhoClicked = eventClass.getMethod("getWhoClicked");
        } catch (NoSuchMethodException ignored) {
        }
        final Method cachedGetPlayer = getPlayer;
        final Method cachedGetEntity = getEntity;
        final Method cachedGetWhoClicked = getWhoClicked;
        return event -> {
            try {
                if (cachedGetPlayer != null) {
                    Object result = cachedGetPlayer.invoke(event);
                    if (result instanceof Player p) {
                        return p;
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                if (cachedGetEntity != null) {
                    Object entity = cachedGetEntity.invoke(event);
                    if (entity instanceof Player p) {
                        return p;
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                if (cachedGetWhoClicked != null) {
                    Object who = cachedGetWhoClicked.invoke(event);
                    if (who instanceof Player p) {
                        return p;
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        };
    }

    private String normalizeEventKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase(java.util.Locale.ROOT);
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

    public Map<String, EventNodeDefinition> getEventDefinitions() {
        return Map.copyOf(eventDefinitions);
    }

    public record EventNodeDefinition(NodeDefinition definition, Class<? extends Event> eventClass) {
    }
}
