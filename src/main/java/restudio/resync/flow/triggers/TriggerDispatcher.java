package restudio.resync.flow.triggers;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class TriggerDispatcher implements Listener {

    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final Plugin plugin;
    private final Map<String, TriggerEntry> entriesByType = new ConcurrentHashMap<>();
    private final Map<String, String> eventTypeToNodeType = new ConcurrentHashMap<>();

    public TriggerDispatcher(FlowStorage storage, FlowExecutor executor, Plugin plugin) {
        this.storage = storage;
        this.executor = executor;
        this.plugin = plugin;
    }

    public void registerDefinition(String eventType, String nodeType, Class<? extends Event> eventClass,
                                   EventPriority priority, boolean ignoreCancelled,
                                   Function<Event, Map<String, Object>> variableExtractor,
                                   Function<Event, Player> playerExtractor,
                                   String[] aliases) {
        ConcurrentHashMap<String, String> triggerMap = new ConcurrentHashMap<>();
        TriggerEntry entry = new TriggerEntry(eventType, nodeType, eventClass, priority,
                ignoreCancelled, variableExtractor, playerExtractor, triggerMap);
        entriesByType.put(eventType, entry);
        for (String alias : aliases) {
            entriesByType.put(alias, entry);
        }
        if (!nodeType.isEmpty()) {
            eventTypeToNodeType.put(eventType, nodeType);
        }

        org.bukkit.plugin.EventExecutor bukkitExecutor = (listener, event) -> {
            if (!eventClass.isInstance(event)) return;
            dispatch(entry, event);
        };
        plugin.getServer().getPluginManager().registerEvent(eventClass, this, priority, bukkitExecutor, plugin, ignoreCancelled);
    }

    private void dispatch(TriggerEntry entry, Event event) {
        Map<String, Object> customVars = entry.variableExtractor.apply(event);
        if (customVars == null) return;

        Player player = entry.playerExtractor != null ? entry.playerExtractor.apply(event) : null;

        for (Map.Entry<String, String> trigger : entry.triggerMap.entrySet()) {
            FlowGraph graph = storage.getGraph(trigger.getKey());
            if (graph == null) continue;

            executor.clearEventVariables();
            Map<String, Object> eventVars = executor.getEventVariables();
            if (player != null) {
                eventVars.put("event.player", player);
            }
            eventVars.putAll(customVars);
            executor.execute(graph, trigger.getValue(), player, event);
        }
    }

    public void registerBinding(String eventType, String flowId, String startNodeId) {
        TriggerEntry entry = entriesByType.get(eventType);
        if (entry != null) {
            entry.triggerMap.put(flowId, startNodeId);
        }
    }

    public void unregisterBinding(String flowId) {
        for (TriggerEntry entry : entriesByType.values()) {
            entry.triggerMap.remove(flowId);
        }
    }

    public void clearBindings() {
        for (TriggerEntry entry : entriesByType.values()) {
            entry.triggerMap.clear();
        }
    }

    public String getNodeType(String eventType) {
        return eventTypeToNodeType.get(eventType);
    }

    public Set<String> getEventTypes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(entriesByType.keySet()));
    }

    public boolean hasEventType(String eventType) {
        return entriesByType.containsKey(eventType);
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
                    return null;
                }
            };
        }

        Class<?> eventClass = annotation.eventClass();
        return event -> {
            try {
                Method getPlayer = eventClass.getMethod("getPlayer");
                Object result = getPlayer.invoke(event);
                if (result instanceof Player p) return p;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                return null;
            }
            try {
                Method getEntity = eventClass.getMethod("getEntity");
                Object entity = getEntity.invoke(event);
                if (entity instanceof Player p) return p;
            } catch (Exception ignored) {
            }
            try {
                Method getWhoClicked = eventClass.getMethod("getWhoClicked");
                Object who = getWhoClicked.invoke(event);
                if (who instanceof Player p) return p;
            } catch (Exception ignored) {
            }
            return null;
        };
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

        TriggerEntry(String eventType, String nodeType, Class<? extends Event> eventClass,
                     EventPriority priority, boolean ignoreCancelled,
                     Function<Event, Map<String, Object>> variableExtractor,
                     Function<Event, Player> playerExtractor,
                     ConcurrentHashMap<String, String> triggerMap) {
            this.eventType = eventType;
            this.nodeType = nodeType;
            this.eventClass = eventClass;
            this.priority = priority;
            this.ignoreCancelled = ignoreCancelled;
            this.variableExtractor = variableExtractor;
            this.playerExtractor = playerExtractor;
            this.triggerMap = triggerMap;
        }
    }
}
