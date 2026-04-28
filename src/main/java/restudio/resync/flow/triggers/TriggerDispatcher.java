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
    private final Map<String, TriggerEntry> entriesByLookup = new ConcurrentHashMap<>();

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

    public void registerDefinition(String eventType, String nodeType, Class<? extends Event> eventClass,
                                   EventPriority priority, boolean ignoreCancelled,
                                   Function<Event, Map<String, Object>> variableExtractor,
                                   Function<Event, Player> playerExtractor,
                                   String[] aliases) {
        ConcurrentHashMap<String, String> triggerMap = new ConcurrentHashMap<>();
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
        TriggerEntry entry = new TriggerEntry(eventType, nodeType, eventClass, priority,
                ignoreCancelled, variableExtractor, playerExtractor, triggerMap,
                getPlayer, getEntity, getWhoClicked);
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

            Map<String, Object> eventVars = new java.util.HashMap<>();
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
            executor.execute(graph, trigger.getValue(), player, event, eventVars);
        }
    }

    public void registerBinding(String eventType, String flowId, String startNodeId) {
        TriggerEntry entry = resolveEntry(eventType);
        if (entry != null) {
            entry.triggerMap.put(flowId, startNodeId);
        }
    }

    public void unregisterBinding(String flowId) {
        for (TriggerEntry entry : new HashSet<>(entriesByType.values())) {
            entry.triggerMap.remove(flowId);
        }
    }

    public void clearBindings() {
        for (TriggerEntry entry : new HashSet<>(entriesByType.values())) {
            entry.triggerMap.clear();
        }
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

    public boolean hasEventType(String eventType) {
        return resolveEntry(eventType) != null;
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

    private TriggerEntry resolveEntry(String eventType) {
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
                    return null;
                }
            };
        }

        Class<?> eventClass = annotation.eventClass();
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
                    if (result instanceof Player p) return p;
                }
            } catch (Exception e) {
                return null;
            }
            try {
                if (cachedGetEntity != null) {
                    Object entity = cachedGetEntity.invoke(event);
                    if (entity instanceof Player p) return p;
                }
            } catch (Exception ignored) {
            }
            try {
                if (cachedGetWhoClicked != null) {
                    Object who = cachedGetWhoClicked.invoke(event);
                    if (who instanceof Player p) return p;
                }
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
        final Method cachedGetPlayer;
        final Method cachedGetEntity;
        final Method cachedGetWhoClicked;

        TriggerEntry(String eventType, String nodeType, Class<? extends Event> eventClass,
                     EventPriority priority, boolean ignoreCancelled,
                     Function<Event, Map<String, Object>> variableExtractor,
                     Function<Event, Player> playerExtractor,
                     ConcurrentHashMap<String, String> triggerMap,
                     Method cachedGetPlayer, Method cachedGetEntity, Method cachedGetWhoClicked) {
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
        }
    }
}
