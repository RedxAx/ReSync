package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import restudio.flow.data.FlowGraph;

import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalTriggers implements Listener {
    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final TriggerRegistry triggerRegistry;
    
    private final Map<String, String> playerJoinTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerQuitTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerChatTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerSneakTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerDeathTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockBreakTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockPlaceTriggers = new ConcurrentHashMap<>();
    
    public GlobalTriggers(FlowStorage storage, FlowExecutor executor, TriggerRegistry triggerRegistry) {
        this.storage = storage;
        this.executor = executor;
        this.triggerRegistry = triggerRegistry;
        refreshBindings();
    }

    private void setEventVariables(Player player, Map<String, Object> variables) {
        variables.put("event.player", player);
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
            case "join":
            case "player_join":
                playerJoinTriggers.put(flowId, startNode);
                break;
            case "quit":
            case "player_quit":
                playerQuitTriggers.put(flowId, startNode);
                break;
            case "chat":
            case "player_chat":
                playerChatTriggers.put(flowId, startNode);
                break;
            case "sneak":
            case "player_sneak":
                playerSneakTriggers.put(flowId, startNode);
                break;
            case "death":
            case "player_death":
                playerDeathTriggers.put(flowId, startNode);
                break;
            case "block_break":
                blockBreakTriggers.put(flowId, startNode);
                break;
            case "block_place":
                blockPlaceTriggers.put(flowId, startNode);
                break;
            default:
                System.err.println("[ReSync] Unknown trigger type: " + eventType);
        }
    }

    public void refreshBindings() {
        playerJoinTriggers.clear();
        playerQuitTriggers.clear();
        playerChatTriggers.clear();
        playerSneakTriggers.clear();
        playerDeathTriggers.clear();
        blockBreakTriggers.clear();
        blockPlaceTriggers.clear();

        if (triggerRegistry == null) {
            return;
        }

        for (TriggerBinding binding : triggerRegistry.getBindings(TriggerType.EVENT)) {
            registerTrigger(binding.getContext(), binding.getFlowId());
        }
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerJoinTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.join_message", event.getJoinMessage());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        for (Map.Entry<String, String> entry : playerQuitTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.quit_message", event.getQuitMessage());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        for (Map.Entry<String, String> entry : playerChatTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.message", event.getMessage());
                eventVars.put("event.format", event.getFormat());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();

        for (Map.Entry<String, String> entry : playerSneakTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.is_sneaking", event.isSneaking());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        for (Map.Entry<String, String> entry : playerDeathTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.death_message", event.getDeathMessage());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        for (Map.Entry<String, String> entry : blockBreakTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.block", event.getBlock());
                eventVars.put("event.is_cancelled", event.isCancelled());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        for (Map.Entry<String, String> entry : blockPlaceTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.block", event.getBlock());
                eventVars.put("event.placed_against", event.getBlockAgainst());
                eventVars.put("event.is_cancelled", event.isCancelled());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    private String findStartNode(FlowGraph graph) {
        for (var entry : graph.getNodes().entrySet()) {
            String type = entry.getValue().getType();
            if (type != null && (type.startsWith("event:") || "start".equals(type))) {
                return entry.getKey();
            }
        }
        return graph.getNodes().keySet().stream().findFirst().orElse(null);
    }

    private String findStartNodeForEvent(FlowGraph graph, String eventType) {
        String nodeType = mapEventNodeType(eventType);
        if (nodeType == null) {
            return null;
        }
        for (var entry : graph.getNodes().entrySet()) {
            if (nodeType.equals(entry.getValue().getType())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String mapEventNodeType(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType.toLowerCase()) {
            case "join", "player_join" -> "event:join";
            case "quit", "player_quit" -> "event:quit";
            case "chat", "player_chat" -> "event:chat";
            case "sneak", "player_sneak" -> "event:sneak";
            case "death", "player_death" -> "event:death";
            case "block_break" -> "event:block_break";
            case "block_place" -> "event:block_place";
            default -> null;
        };
    }
}
