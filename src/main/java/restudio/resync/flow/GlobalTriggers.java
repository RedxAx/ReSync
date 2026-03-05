package restudio.resync.flow;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.SimplePluginManager;
import restudio.flow.data.FlowGraph;
import restudio.resync.ReSync;

import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Field;

public class GlobalTriggers implements Listener {
    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final TriggerRegistry triggerRegistry;
    private SystemEventListener systemEventListener;
    private final Gson gson = new Gson();
    
    private final Map<String, String> playerJoinTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerQuitTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerChatTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerSneakTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerDeathTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockBreakTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockPlaceTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerMoveTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerInteractTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerEntityInteractTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerEntityDamageTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> projectileShootTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> projectileHitTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerPickupTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerDropTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerConsumeTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerCraftTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerSmeltTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerEnchantTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerBedEnterTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerBedLeaveTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerRespawnTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerLevelUpTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerCommandTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerTabCompleteTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerTeleportTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerGameModeChangeTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerFlightToggleTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerVanishToggleTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerFishTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerShearTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerItemDamageTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerItemBreakTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> playerExpChangeTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entitySpawnTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityTargetTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityBreedTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityTameTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityTransformTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityDeathTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> itemMergeTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> chunkLoadTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> chunkUnloadTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityCombustTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityDamagedTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityHealTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityRegainHealthTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityPickupTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> entityDropTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockRedstoneTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockPhysicsTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> explosionTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockGrowTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockFromToTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> structureSpawnTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> worldSaveTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> weatherChangeTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> timeChangeTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockDispenseTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockFadeTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockFormTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> blockSpreadTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> leafDecayTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> signChangeTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> furnaceSmeltTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> inventoryOpenTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> inventoryCloseTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> notePlayTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> pistonExtendTriggers = new ConcurrentHashMap<>();
    private final Map<String, String> pistonRetractTriggers = new ConcurrentHashMap<>();
    private final Map<String, CommandTrigger> commandTriggers = new ConcurrentHashMap<>();
    private final Map<String, RuntimeFlowCommand> runtimeCommands = new ConcurrentHashMap<>();

    private static class CommandTrigger {
        private final String flowId;
        private final String startNode;
        private final String command;
        private final List<String> subcommands;
        private final List<List<String>> commandPaths;
        private final boolean structured;

        private CommandTrigger(String flowId, String startNode, String command, List<String> subcommands, List<List<String>> commandPaths, boolean structured) {
            this.flowId = flowId;
            this.startNode = startNode;
            this.command = command;
            this.subcommands = subcommands;
            this.commandPaths = commandPaths;
            this.structured = structured;
        }
    }

    private static class CommandContextPayload {
        private String command;
        private List<String> subcommands;
        private Boolean structured;
    }

    private class RuntimeFlowCommand extends Command {
        private final String baseLabel;

        private RuntimeFlowCommand(String label) {
            super(label);
            this.baseLabel = label;
            setDescription("ReSync flow command");
            setUsage("/" + label);
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            String normalizedLabel = normalizeCommandLabel(commandLabel);
            String joinedArgs = String.join(" ", args);
            Player player = sender instanceof Player p ? p : null;
            boolean isConsole = !(sender instanceof Player);
            boolean handled = false;
            for (CommandTrigger trigger : commandTriggers.values()) {
                if (trigger.command.equals(normalizedLabel) && executeCommandTrigger(trigger, normalizedLabel, joinedArgs, player, null, isConsole)) {
                    handled = true;
                }
            }
            return handled;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            List<String> completions = new ArrayList<>();
            String normalizedLabel = normalizeCommandLabel(alias != null ? alias : baseLabel);
            if (normalizedLabel == null) {
                return completions;
            }
            List<String> argsTokens = new ArrayList<>();
            String currentArg = "";
            if (args.length > 0) {
                currentArg = args[args.length - 1];
                for (int i = 0; i < args.length - 1; i++) {
                    if (!args[i].isBlank()) {
                        argsTokens.add(args[i]);
                    }
                }
            }
            for (CommandTrigger trigger : commandTriggers.values()) {
                if (!trigger.command.equals(normalizedLabel) || trigger.commandPaths.isEmpty()) {
                    continue;
                }
                completions.addAll(collectPathSuggestions(trigger, argsTokens, currentArg));
            }
            return completions;
        }
    }
    
    public GlobalTriggers(FlowStorage storage, FlowExecutor executor, TriggerRegistry triggerRegistry) {
        this.storage = storage;
        this.executor = executor;
        this.triggerRegistry = triggerRegistry;
        this.systemEventListener = new SystemEventListener(storage, executor, triggerRegistry);
        refreshBindings();
    }
    
    public SystemEventListener getSystemEventListener() {
        return systemEventListener;
    }
    
    public void setSystemEventListener(SystemEventListener listener) {
        this.systemEventListener = listener;
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
            case "move":
            case "player_move":
                playerMoveTriggers.put(flowId, startNode);
                break;
            case "interact":
            case "player_interact":
                playerInteractTriggers.put(flowId, startNode);
                break;
            case "entity_interact":
                playerEntityInteractTriggers.put(flowId, startNode);
                break;
            case "entity_damage":
                playerEntityDamageTriggers.put(flowId, startNode);
                break;
            case "shoot":
            case "projectile_shoot":
                projectileShootTriggers.put(flowId, startNode);
                break;
            case "projectile_hit":
                projectileHitTriggers.put(flowId, startNode);
                break;
            case "pickup":
            case "player_pickup":
                playerPickupTriggers.put(flowId, startNode);
                break;
            case "drop":
            case "player_drop":
                playerDropTriggers.put(flowId, startNode);
                break;
            case "consume":
            case "player_consume":
                playerConsumeTriggers.put(flowId, startNode);
                break;
            case "craft":
            case "player_craft":
                playerCraftTriggers.put(flowId, startNode);
                break;
            case "smelt":
            case "player_smelt":
                playerSmeltTriggers.put(flowId, startNode);
                break;
            case "enchant":
            case "player_enchant":
                playerEnchantTriggers.put(flowId, startNode);
                break;
            case "bed_enter":
                playerBedEnterTriggers.put(flowId, startNode);
                break;
            case "bed_leave":
                playerBedLeaveTriggers.put(flowId, startNode);
                break;
            case "respawn":
            case "player_respawn":
                playerRespawnTriggers.put(flowId, startNode);
                break;
            case "level_up":
            case "player_level_up":
                playerLevelUpTriggers.put(flowId, startNode);
                break;
            case "command":
            case "player_command":
                playerCommandTriggers.put(flowId, startNode);
                break;
            case "tab_complete":
            case "player_tab_complete":
                playerTabCompleteTriggers.put(flowId, startNode);
                break;
            case "teleport":
            case "player_teleport":
                playerTeleportTriggers.put(flowId, startNode);
                break;
            case "gamemode_change":
            case "player_gamemode_change":
                playerGameModeChangeTriggers.put(flowId, startNode);
                break;
            case "flight_toggle":
            case "player_flight_toggle":
                playerFlightToggleTriggers.put(flowId, startNode);
                break;
            case "vanish_toggle":
            case "player_vanish_toggle":
                playerVanishToggleTriggers.put(flowId, startNode);
                break;
            case "fish":
            case "player_fish":
                playerFishTriggers.put(flowId, startNode);
                break;
            case "shear":
            case "player_shear":
                playerShearTriggers.put(flowId, startNode);
                break;
            case "item_damage":
            case "player_item_damage":
                playerItemDamageTriggers.put(flowId, startNode);
                break;
            case "item_break":
            case "player_item_break":
                playerItemBreakTriggers.put(flowId, startNode);
                break;
            case "exp_change":
            case "player_exp_change":
                playerExpChangeTriggers.put(flowId, startNode);
                break;
            case "entity_spawn":
                entitySpawnTriggers.put(flowId, startNode);
                break;
            case "entity_target":
                entityTargetTriggers.put(flowId, startNode);
                break;
            case "entity_breed":
                entityBreedTriggers.put(flowId, startNode);
                break;
            case "entity_tame":
                entityTameTriggers.put(flowId, startNode);
                break;
            case "entity_transform":
                entityTransformTriggers.put(flowId, startNode);
                break;
            case "entity_death":
                entityDeathTriggers.put(flowId, startNode);
                break;
            case "item_merge":
                itemMergeTriggers.put(flowId, startNode);
                break;
            case "chunk_load":
                chunkLoadTriggers.put(flowId, startNode);
                break;
            case "chunk_unload":
                chunkUnloadTriggers.put(flowId, startNode);
                break;
            case "entity_combust":
                entityCombustTriggers.put(flowId, startNode);
                break;
            case "entity_damaged":
                entityDamagedTriggers.put(flowId, startNode);
                break;
            case "entity_heal":
                entityHealTriggers.put(flowId, startNode);
                break;
            case "entity_regain_health":
                entityRegainHealthTriggers.put(flowId, startNode);
                break;
            case "entity_pickup":
                entityPickupTriggers.put(flowId, startNode);
                break;
            case "entity_drop":
                entityDropTriggers.put(flowId, startNode);
                break;
            case "block_redstone":
                blockRedstoneTriggers.put(flowId, startNode);
                break;
            case "physics":
            case "block_physics":
                blockPhysicsTriggers.put(flowId, startNode);
                break;
            case "explosion":
                explosionTriggers.put(flowId, startNode);
                break;
            case "grow":
            case "block_grow":
                blockGrowTriggers.put(flowId, startNode);
                break;
            case "block_from_to":
                blockFromToTriggers.put(flowId, startNode);
                break;
            case "structure_spawn":
                structureSpawnTriggers.put(flowId, startNode);
                break;
            case "world_save":
                worldSaveTriggers.put(flowId, startNode);
                break;
            case "weather_change":
                weatherChangeTriggers.put(flowId, startNode);
                break;
            case "time_change":
                timeChangeTriggers.put(flowId, startNode);
                break;
            case "block_dispense":
                blockDispenseTriggers.put(flowId, startNode);
                break;
            case "block_fade":
                blockFadeTriggers.put(flowId, startNode);
                break;
            case "block_form":
                blockFormTriggers.put(flowId, startNode);
                break;
            case "block_spread":
                blockSpreadTriggers.put(flowId, startNode);
                break;
            case "leaf_decay":
                leafDecayTriggers.put(flowId, startNode);
                break;
            case "sign_change":
                signChangeTriggers.put(flowId, startNode);
                break;
            case "furnace_smelt":
                furnaceSmeltTriggers.put(flowId, startNode);
                break;
            case "inventory_open":
                inventoryOpenTriggers.put(flowId, startNode);
                break;
            case "inventory_close":
                inventoryCloseTriggers.put(flowId, startNode);
                break;
            case "note_play":
                notePlayTriggers.put(flowId, startNode);
                break;
            case "piston_extend":
                pistonExtendTriggers.put(flowId, startNode);
                break;
            case "piston_retract":
                pistonRetractTriggers.put(flowId, startNode);
                break;
            default:
                System.err.println("[ReSync] Unknown trigger type: " + eventType);
        }
    }

    private void registerCommandTrigger(TriggerBinding binding) {
        if (binding == null || binding.getFlowId() == null) {
            return;
        }
        FlowGraph graph = storage.getGraph(binding.getFlowId());
        if (graph == null) {
            return;
        }
        String startNode = null;
        for (var entry : graph.getNodes().entrySet()) {
            if ("event:resync_command".equals(entry.getValue().getType())) {
                startNode = entry.getKey();
                break;
            }
        }
        if (startNode == null) {
            startNode = findStartNodeForEvent(graph, "command");
        }
        if (startNode == null) {
            startNode = findStartNode(graph);
        }
        if (startNode == null) {
            return;
        }
        String context = binding.getContext();
        if (context == null || context.isBlank()) {
            return;
        }
        String command = null;
        List<String> subcommands = new ArrayList<>();
        boolean structured = false;
        String trimmed = context.trim();
        if (trimmed.startsWith("{")) {
            try {
                CommandContextPayload payload = gson.fromJson(trimmed, CommandContextPayload.class);
                if (payload != null) {
                    command = payload.command;
                    if (payload.subcommands != null) {
                        for (String subcommand : payload.subcommands) {
                            if (subcommand != null && !subcommand.isBlank()) {
                                subcommands.add(subcommand.trim());
                            }
                        }
                    }
                    structured = payload.structured != null && payload.structured;
                }
            } catch (Exception ignored) {
            }
        } else {
            command = trimmed;
        }
        String normalizedCommand = normalizeCommandLabel(command);
        if (normalizedCommand == null) {
            return;
        }
        List<List<String>> commandPaths = parseCommandPaths(subcommands);
        String key = binding.getId() != null ? binding.getId() : binding.getFlowId() + ":" + normalizedCommand;
        commandTriggers.put(key, new CommandTrigger(binding.getFlowId(), startNode, normalizedCommand, subcommands, commandPaths, structured));
    }

    private String normalizeCommandLabel(String label) {
        if (label == null) {
            return null;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    private List<String> parseArgsList(String args) {
        List<String> parsed = new ArrayList<>();
        if (args == null || args.isBlank()) {
            return parsed;
        }
        for (String part : args.split(" ")) {
            if (!part.isBlank()) {
                parsed.add(part);
            }
        }
        return parsed;
    }

    private List<List<String>> parseCommandPaths(List<String> entries) {
        List<List<String>> paths = new ArrayList<>();
        if (entries == null) {
            return paths;
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            List<String> tokens = new ArrayList<>();
            for (String token : entry.trim().split("\\s+")) {
                if (!token.isBlank()) {
                    tokens.add(token.trim());
                }
            }
            if (!tokens.isEmpty()) {
                paths.add(tokens);
            }
        }
        return paths;
    }

    private List<String> resolveDynamicTokenValues(String token) {
        List<String> values = new ArrayList<>();
        if (token == null || token.isBlank()) {
            return values;
        }
        if ("<online_player>".equalsIgnoreCase(token)) {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                values.add(onlinePlayer.getName());
            }
            return values;
        }
        if ("<offline_player>".equalsIgnoreCase(token)) {
            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                if (offlinePlayer.getName() != null && !offlinePlayer.getName().isBlank()) {
                    values.add(offlinePlayer.getName());
                }
            }
            return values;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("<player_with_perm:") && lower.endsWith(">")) {
            String permission = token.substring("<player_with_perm:".length(), token.length() - 1).trim();
            if (!permission.isBlank()) {
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (onlinePlayer.hasPermission(permission)) {
                        values.add(onlinePlayer.getName());
                    }
                }
            }
            return values;
        }
        return values;
    }

    private boolean tokenMatches(String token, String arg) {
        if (token == null) {
            return false;
        }
        if ("<any>".equalsIgnoreCase(token)) {
            return true;
        }
        List<String> dynamicValues = resolveDynamicTokenValues(token);
        if (!dynamicValues.isEmpty()) {
            return dynamicValues.stream().anyMatch(value -> value.equalsIgnoreCase(arg));
        }
        if (token.startsWith("<") && token.endsWith(">")) {
            return true;
        }
        return token.equalsIgnoreCase(arg);
    }

    private List<String> collectPathSuggestions(CommandTrigger trigger, List<String> argsTokens, String currentArg) {
        List<String> suggestions = new ArrayList<>();
        int index = argsTokens.size();
        for (List<String> path : trigger.commandPaths) {
            if (path.size() <= index) {
                continue;
            }
            boolean prefixMatches = true;
            for (int i = 0; i < index; i++) {
                if (path.size() <= i || !tokenMatches(path.get(i), argsTokens.get(i))) {
                    prefixMatches = false;
                    break;
                }
            }
            if (!prefixMatches) {
                continue;
            }
            String token = path.get(index);
            List<String> tokenValues = resolveDynamicTokenValues(token);
            if (tokenValues.isEmpty()) {
                tokenValues = List.of(token);
            }
            for (String value : tokenValues) {
                if (value.toLowerCase(Locale.ROOT).startsWith(currentArg.toLowerCase(Locale.ROOT))) {
                    suggestions.add(value);
                }
            }
        }
        return suggestions;
    }

    private boolean executeCommandTrigger(CommandTrigger trigger, String commandLabel, String args, Player player, Event event, boolean isConsole) {
        if (trigger == null || commandLabel == null) {
            return false;
        }
        List<String> argsList = parseArgsList(args);
        String firstArg = argsList.isEmpty() ? "" : argsList.getFirst();
        if (trigger.structured && !trigger.commandPaths.isEmpty()) {
            boolean matchedPath = false;
            for (List<String> path : trigger.commandPaths) {
                if (argsList.size() < path.size()) {
                    continue;
                }
                boolean allMatch = true;
                for (int i = 0; i < path.size(); i++) {
                    if (!tokenMatches(path.get(i), argsList.get(i))) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) {
                    matchedPath = true;
                    break;
                }
            }
            if (!matchedPath) {
                return false;
            }
        }
        FlowGraph graph = storage.getGraph(trigger.flowId);
        if (graph == null) {
            return false;
        }
        executor.clearEventVariables();
        Map<String, Object> eventVars = executor.getEventVariables();
        setEventVariables(player, eventVars);
        eventVars.put("event.command_label", commandLabel);
        eventVars.put("event.args", args);
        eventVars.put("event.args_list", new ArrayList<>(argsList));
        eventVars.put("event.args_count", argsList.size());
        eventVars.put("event.command_subcommand", firstArg);
        eventVars.put("event.command_structured", trigger.structured);
        eventVars.put("event.command_allowed_subcommands", new ArrayList<>(trigger.subcommands));
        eventVars.put("event.is_console", isConsole);
        eventVars.put("event.bound_command", trigger.command);
        executor.execute(graph, trigger.startNode, player, event);
        return true;
    }

    private CommandMap resolveCommandMap() {
        try {
            if (!(Bukkit.getPluginManager() instanceof SimplePluginManager pluginManager)) {
                return null;
            }
            Field commandMapField = SimplePluginManager.class.getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            Object value = commandMapField.get(pluginManager);
            if (value instanceof CommandMap commandMap) {
                return commandMap;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> resolveKnownCommands(CommandMap commandMap) {
        try {
            Field knownCommandsField = commandMap.getClass().getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            Object value = knownCommandsField.get(commandMap);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Command>) map;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void refreshRuntimeCommands() {
        CommandMap commandMap = resolveCommandMap();
        if (commandMap == null) {
            return;
        }
        Map<String, Command> knownCommands = resolveKnownCommands(commandMap);
        Set<String> desired = new HashSet<>();
        for (CommandTrigger trigger : commandTriggers.values()) {
            desired.add(trigger.command);
        }
        String pluginPrefix = ReSync.getInstance().getName().toLowerCase(Locale.ROOT) + ":";

        for (Map.Entry<String, RuntimeFlowCommand> entry : new ArrayList<>(runtimeCommands.entrySet())) {
            if (desired.contains(entry.getKey())) {
                continue;
            }
            RuntimeFlowCommand command = entry.getValue();
            command.unregister(commandMap);
            runtimeCommands.remove(entry.getKey());
            if (knownCommands != null) {
                knownCommands.remove(entry.getKey());
                knownCommands.remove(pluginPrefix + entry.getKey());
            }
        }

        for (String commandLabel : desired) {
            if (runtimeCommands.containsKey(commandLabel)) {
                continue;
            }
            RuntimeFlowCommand command = new RuntimeFlowCommand(commandLabel);
            commandMap.register(ReSync.getInstance().getName().toLowerCase(Locale.ROOT), command);
            runtimeCommands.put(commandLabel, command);
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.updateCommands();
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
        playerMoveTriggers.clear();
        playerInteractTriggers.clear();
        playerEntityInteractTriggers.clear();
        playerEntityDamageTriggers.clear();
        projectileShootTriggers.clear();
        projectileHitTriggers.clear();
        playerPickupTriggers.clear();
        playerDropTriggers.clear();
        playerConsumeTriggers.clear();
        playerCraftTriggers.clear();
        playerSmeltTriggers.clear();
        playerEnchantTriggers.clear();
        playerBedEnterTriggers.clear();
        playerBedLeaveTriggers.clear();
        playerRespawnTriggers.clear();
        playerLevelUpTriggers.clear();
        playerCommandTriggers.clear();
        playerTabCompleteTriggers.clear();
        playerTeleportTriggers.clear();
        playerGameModeChangeTriggers.clear();
        playerFlightToggleTriggers.clear();
        playerVanishToggleTriggers.clear();
        playerFishTriggers.clear();
        playerShearTriggers.clear();
        playerItemDamageTriggers.clear();
        playerItemBreakTriggers.clear();
        playerExpChangeTriggers.clear();
        entitySpawnTriggers.clear();
        entityTargetTriggers.clear();
        entityBreedTriggers.clear();
        entityTameTriggers.clear();
        entityTransformTriggers.clear();
        entityDeathTriggers.clear();
        itemMergeTriggers.clear();
        chunkLoadTriggers.clear();
        chunkUnloadTriggers.clear();
        entityCombustTriggers.clear();
        entityDamagedTriggers.clear();
        entityHealTriggers.clear();
        entityRegainHealthTriggers.clear();
        entityPickupTriggers.clear();
        entityDropTriggers.clear();
        blockRedstoneTriggers.clear();
        blockPhysicsTriggers.clear();
        explosionTriggers.clear();
        blockGrowTriggers.clear();
        blockFromToTriggers.clear();
        structureSpawnTriggers.clear();
        worldSaveTriggers.clear();
        weatherChangeTriggers.clear();
        timeChangeTriggers.clear();
        blockDispenseTriggers.clear();
        blockFadeTriggers.clear();
        blockFormTriggers.clear();
        blockSpreadTriggers.clear();
        leafDecayTriggers.clear();
        signChangeTriggers.clear();
        furnaceSmeltTriggers.clear();
        inventoryOpenTriggers.clear();
        inventoryCloseTriggers.clear();
        notePlayTriggers.clear();
        pistonExtendTriggers.clear();
        pistonRetractTriggers.clear();
        commandTriggers.clear();

        if (triggerRegistry == null) {
            return;
        }

        for (TriggerBinding binding : triggerRegistry.getBindings(TriggerType.EVENT)) {
            registerTrigger(binding.getContext(), binding.getFlowId());
        }
        for (TriggerBinding binding : triggerRegistry.getBindings(TriggerType.COMMAND)) {
            registerCommandTrigger(binding);
        }
        refreshRuntimeCommands();
        
        if (systemEventListener != null) {
            systemEventListener.refreshBindings();
            for (TriggerBinding binding : triggerRegistry.getBindings(TriggerType.SYSTEM)) {
                systemEventListener.registerTrigger(binding.getContext(), binding.getFlowId());
            }
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

    @EventHandler(priority = EventPriority.HIGHEST)
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

    @EventHandler(priority = EventPriority.HIGHEST)
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

    @EventHandler(priority = EventPriority.HIGHEST)
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

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        double distance = event.getFrom().distance(event.getTo());
        
        if (distance < 0.1) {
            return;
        }
        
        for (Map.Entry<String, String> entry : playerMoveTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.from_location", event.getFrom());
                eventVars.put("event.to_location", event.getTo());
                eventVars.put("event.distance", distance);
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerInteractTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.clicked_block", event.getClickedBlock());
                eventVars.put("event.clicked_entity", null);
                eventVars.put("event.action_type", event.getAction().name());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerEntityInteractTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.entity", event.getRightClicked());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Entity damager = null;
        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            damager = byEntityEvent.getDamager();
        }

        for (Map.Entry<String, String> entry : playerEntityDamageTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.damager", damager);
                eventVars.put("event.victim", event.getEntity());
                eventVars.put("event.damage", event.getDamage());
                eventVars.put("event.cause", event.getCause().name());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        
        for (Map.Entry<String, String> entry : projectileShootTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.projectile", event.getEntity());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        
        for (Map.Entry<String, String> entry : projectileHitTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.projectile", event.getEntity());
                eventVars.put("event.hit_entity", event.getHitEntity());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerPickupTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.item", event.getItem().getItemStack());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerDropTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.item", event.getItemDrop().getItemStack());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerConsumeTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.item", event.getItem());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        Player player = (Player) event.getWhoClicked();
        
        for (Map.Entry<String, String> entry : playerCraftTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.result", event.getRecipe().getResult());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        Player player = null;
        
        for (Map.Entry<String, String> entry : playerSmeltTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.player", player);
                eventVars.put("event.result", event.getResult());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerBedEnterTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.bed_location", event.getBed().getLocation());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerBedLeave(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerBedLeaveTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.bed_location", event.getBed().getLocation());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerRespawnTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.respawn_location", event.getRespawnLocation());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerLevelChange(PlayerLevelChangeEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerLevelUpTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.old_level", event.getOldLevel());
                eventVars.put("event.new_level", event.getNewLevel());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String[] parts = event.getMessage().split(" ", 2);
        String commandLabel = normalizeCommandLabel(parts[0]);
        if (commandLabel != null && runtimeCommands.containsKey(commandLabel)) {
            return;
        }
        String args = parts.length > 1 ? parts[1] : "";
        boolean commandHandled = false;
        
        for (Map.Entry<String, String> entry : playerCommandTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.command_label", commandLabel);
                eventVars.put("event.args", args);
                executor.execute(graph, entry.getValue(), player, event);
            }
        }

        for (CommandTrigger trigger : commandTriggers.values()) {
            if (trigger.command.equals(commandLabel) && executeCommandTrigger(trigger, commandLabel, args, player, event, false)) {
                commandHandled = true;
            }
        }
        if (commandHandled) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        String[] parts = event.getCommand().split(" ", 2);
        String commandLabel = normalizeCommandLabel(parts[0]);
        if (commandLabel != null && runtimeCommands.containsKey(commandLabel)) {
            return;
        }
        String args = parts.length > 1 ? parts[1] : "";
        boolean commandHandled = false;
        for (CommandTrigger trigger : commandTriggers.values()) {
            if (trigger.command.equals(commandLabel) && executeCommandTrigger(trigger, commandLabel, args, null, event, true)) {
                commandHandled = true;
            }
        }
        if (commandHandled) {
            event.setCommand("");
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerTeleportTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.from_location", event.getFrom());
                eventVars.put("event.to_location", event.getTo());
                eventVars.put("event.cause", event.getCause().name());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerGameModeChangeTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.old_gamemode", event.getNewGameMode().name());
                eventVars.put("event.new_gamemode", event.getNewGameMode().name());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerFlightToggleTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.is_flying", event.isFlying());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerFishTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.state", event.getState().name());
                eventVars.put("event.caught", event.getCaught());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerShearEntity(PlayerShearEntityEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerShearTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.entity", event.getEntity());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerItemDamage(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerItemDamageTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.item", event.getItem());
                eventVars.put("event.damage", event.getDamage());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerItemBreak(PlayerItemBreakEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerItemBreakTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.broken_item", event.getBrokenItem());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerExpChangeTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.amount", event.getAmount());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : entitySpawnTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                eventVars.put("event.location", event.getLocation());
                eventVars.put("event.entity_type", entity.getType().name());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetEvent event) {
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : entityTargetTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                eventVars.put("event.target", event.getTarget());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onEntityBreed(EntityBreedEvent event) {
        Entity entity1 = event.getEntity();
        Entity entity2 = event.getMother();
        Entity bredEntity = event.getEntity();
        
        for (Map.Entry<String, String> entry : entityBreedTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity1", entity1);
                eventVars.put("event.entity2", entity2);
                eventVars.put("event.experience", event.getExperience());
                eventVars.put("event.bred_entity", bredEntity);
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onEntityTame(EntityTameEvent event) {
        Entity entity = event.getEntity();
        Entity tamer = event.getOwner() instanceof Entity ? (Entity) event.getOwner() : null;
        
        for (Map.Entry<String, String> entry : entityTameTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                eventVars.put("event.tamer", tamer);
                eventVars.put("event.entity_type", entity.getType().name());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onEntityTransform(EntityTransformEvent event) {
        Entity oldEntity = event.getEntity();
        Entity newEntity = event.getTransformedEntity();
        
        for (Map.Entry<String, String> entry : entityTransformTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.old_entity", oldEntity);
                eventVars.put("event.new_entity", newEntity);
                eventVars.put("event.new_entity_type", newEntity.getType().name());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : entityDeathTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                eventVars.put("event.killer", event.getEntity().getKiller());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onEntityDamaged(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : entityDamagedTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damageByEntityEvent) {
                    eventVars.put("event.damager", damageByEntityEvent.getDamager());
                }
                eventVars.put("event.damage", event.getDamage());
                eventVars.put("event.cause", event.getCause().name());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onEntityRegainHealth(EntityRegainHealthEvent event) {
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : entityRegainHealthTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                eventVars.put("event.amount", event.getAmount());
                eventVars.put("event.reason", event.getRegainReason().name());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
        
        for (Map.Entry<String, String> entry : entityHealTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                eventVars.put("event.amount", event.getAmount());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onEntityPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : entityPickupTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                eventVars.put("event.item", event.getItem());
                eventVars.put("event.remaining", event.getRemaining());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onEntityDrop(EntityDropItemEvent event) {
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : entityDropTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                eventVars.put("event.dropped", event.getItemDrop());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onItemMerge(ItemMergeEvent event) {
        Item item1 = event.getEntity();
        Item item2 = event.getTarget();
        
        for (Map.Entry<String, String> entry : itemMergeTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.item1", item1);
                eventVars.put("event.item2", item2);
                eventVars.put("event.location", item1.getLocation());
                eventVars.put("event.result", item1.getItemStack());
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
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.chunk", event.getChunk());
                eventVars.put("event.world_name", event.getWorld().getName());
                eventVars.put("event.chunk_x", event.getChunk().getX());
                eventVars.put("event.chunk_z", event.getChunk().getZ());
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
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.chunk", event.getChunk());
                eventVars.put("event.world_name", event.getWorld().getName());
                eventVars.put("event.chunk_x", event.getChunk().getX());
                eventVars.put("event.chunk_z", event.getChunk().getZ());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockRedstone(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : blockRedstoneTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                eventVars.put("event.old_power", event.getOldCurrent());
                eventVars.put("event.new_power", event.getNewCurrent());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : blockPhysicsTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : explosionTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.location", entity.getLocation());
                eventVars.put("event.power", event.getRadius());
                eventVars.put("event.break_blocks", null);
                eventVars.put("event.fire", event.getFire());
                eventVars.put("event.entity", entity);
                eventVars.put("event.world_name", entity.getWorld().getName());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : blockGrowTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                eventVars.put("event.new_state", event.getNewState());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        Block fromBlock = event.getBlock();
        Block toBlock = event.getToBlock();
        
        for (Map.Entry<String, String> entry : blockFromToTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.from_block", fromBlock);
                eventVars.put("event.to_block", toBlock);
                eventVars.put("event.from_location", fromBlock.getLocation());
                eventVars.put("event.to_location", toBlock.getLocation());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        for (Map.Entry<String, String> entry : worldSaveTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.world_name", event.getWorld().getName());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        for (Map.Entry<String, String> entry : weatherChangeTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.world_name", event.getWorld().getName());
                eventVars.put("event.old_weather", event.toWeatherState() ? "CLEAR" : "RAIN");
                eventVars.put("event.new_weather", event.toWeatherState() ? "RAIN" : "CLEAR");
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onTimeSkip(TimeSkipEvent event) {
        for (Map.Entry<String, String> entry : timeChangeTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.world_name", event.getWorld().getName());
                eventVars.put("event.old_time", event.getWorld().getTime());
                eventVars.put("event.new_time", event.getWorld().getTime() + event.getSkipAmount());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : blockDispenseTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                eventVars.put("event.item", event.getItem());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : blockFadeTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.new_state", event.getNewState());
                eventVars.put("event.location", block.getLocation());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : blockFormTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.new_state", event.getNewState());
                eventVars.put("event.location", block.getLocation());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : blockSpreadTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.new_block", event.getNewState());
                eventVars.put("event.location", block.getLocation());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onLeavesDecay(LeavesDecayEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : leafDecayTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : signChangeTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                eventVars.put("event.lines", event.getLines());
                executor.execute(graph, entry.getValue(), (Player) event.getPlayer(), event);
            }
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        for (Map.Entry<String, String> entry : inventoryOpenTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.inventory_title", event.getView().getTitle());
                eventVars.put("event.inventory_type", event.getInventory().getType().name());
                eventVars.put("event.location", null);
                executor.execute(graph, entry.getValue(), (Player) event.getPlayer(), event);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        for (Map.Entry<String, String> entry : inventoryCloseTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.inventory_title", event.getView().getTitle());
                eventVars.put("event.inventory_type", event.getInventory().getType().name());
                eventVars.put("event.location", null);
                executor.execute(graph, entry.getValue(), (Player) event.getPlayer(), event);
            }
        }
    }

    @EventHandler
    public void onNotePlay(NotePlayEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : notePlayTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                eventVars.put("event.instrument", event.getInstrument().getType());
                eventVars.put("event.note", event.getNote().getId());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : pistonExtendTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                eventVars.put("event.length", event.getLength());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }

    @EventHandler
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : pistonRetractTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                executor.execute(graph, entry.getValue(), null, event);
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
            case "move", "player_move" -> "event:move";
            case "interact", "player_interact" -> "event:interact";
            case "entity_interact" -> "event:entity_interact";
            case "entity_damage" -> "event:entity_damage";
            case "shoot", "projectile_shoot" -> "event:shoot";
            case "projectile_hit" -> "event:projectile_hit";
            case "pickup", "player_pickup" -> "event:pickup";
            case "drop", "player_drop" -> "event:drop";
            case "consume", "player_consume" -> "event:consume";
            case "craft", "player_craft" -> "event:craft";
            case "smelt", "player_smelt" -> "event:smelt";
            case "enchant", "player_enchant" -> "event:enchant";
            case "bed_enter" -> "event:bed_enter";
            case "bed_leave" -> "event:bed_leave";
            case "respawn", "player_respawn" -> "event:respawn";
            case "level_up", "player_level_up" -> "event:level_up";
            case "command", "player_command" -> "event:command";
            case "tab_complete", "player_tab_complete" -> "event:tab_complete";
            case "teleport", "player_teleport" -> "event:teleport";
            case "gamemode_change", "player_gamemode_change" -> "event:gamemode_change";
            case "flight_toggle", "player_flight_toggle" -> "event:flight_toggle";
            case "vanish_toggle", "player_vanish_toggle" -> "event:vanish_toggle";
            case "fish", "player_fish" -> "event:fish";
            case "shear", "player_shear" -> "event:shear";
            case "item_damage", "player_item_damage" -> "event:item_damage";
            case "item_break", "player_item_break" -> "event:item_break";
            case "exp_change", "player_exp_change" -> "event:exp_change";
            case "entity_spawn" -> "event:entity_spawn";
            case "entity_target" -> "event:entity_target";
            case "entity_breed" -> "event:entity_breed";
            case "entity_tame" -> "event:entity_tame";
            case "entity_transform" -> "event:entity_transform";
            case "entity_death" -> "event:entity_death";
            case "item_merge" -> "event:item_merge";
            case "chunk_load" -> "event:chunk_load";
            case "chunk_unload" -> "event:chunk_unload";
            case "entity_combust" -> "event:entity_combust";
            case "entity_damaged" -> "event:entity_damaged";
            case "entity_heal" -> "event:entity_heal";
            case "entity_regain_health" -> "event:entity_regain_health";
            case "entity_pickup" -> "event:entity_pickup";
            case "entity_drop" -> "event:entity_drop";
            case "block_redstone" -> "event:block_redstone";
            case "physics", "block_physics" -> "event:physics";
            case "explosion" -> "event:explosion";
            case "grow", "block_grow" -> "event:grow";
            case "block_from_to" -> "event:block_from_to";
            case "structure_spawn" -> "event:structure_spawn";
            case "world_save" -> "event:world_save";
            case "weather_change" -> "event:weather_change";
            case "time_change" -> "event:time_change";
            case "block_dispense" -> "event:block_dispense";
            case "block_fade" -> "event:block_fade";
            case "block_form" -> "event:block_form";
            case "block_spread" -> "event:block_spread";
            case "leaf_decay" -> "event:leaf_decay";
            case "sign_change" -> "event:sign_change";
            case "furnace_smelt" -> "event:furnace_smelt";
            case "inventory_open" -> "event:inventory_open";
            case "inventory_close" -> "event:inventory_close";
            case "note_play" -> "event:note_play";
            case "piston_extend" -> "event:piston_extend";
            case "piston_retract" -> "event:piston_retract";
            default -> null;
        };
    }
    
    @EventHandler
    public void onPlayerVanishToggle(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        
        for (Map.Entry<String, String> entry : playerVanishToggleTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.is_vanished", event.isFlying());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }
    
    @EventHandler
    public void onPlayerShear(PlayerShearEntityEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : playerShearTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.entity", entity);
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }
    
    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        Entity entity = event.getEntity();
        
        for (Map.Entry<String, String> entry : entityCombustTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.entity", entity);
                eventVars.put("event.duration", event.getDuration());
                eventVars.put("event.cancelled", event.isCancelled());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
    
    @EventHandler
    public void onPlayerTabComplete(PlayerChatTabCompleteEvent event) {
        Player player = event.getPlayer();
        String chatMessage = event.getChatMessage();
        if (chatMessage != null && chatMessage.startsWith("/")) {
            String raw = chatMessage.substring(1);
            String[] parts = raw.split(" ", -1);
            String commandLabel = normalizeCommandLabel(parts.length > 0 ? parts[0] : "");
            List<String> argsTokens = new ArrayList<>();
            String currentArg = parts.length > 1 ? parts[parts.length - 1] : "";
            for (int i = 1; i < Math.max(1, parts.length - 1); i++) {
                if (!parts[i].isBlank()) {
                    argsTokens.add(parts[i]);
                }
            }
            if (commandLabel != null && !commandLabel.isBlank()) {
                for (CommandTrigger trigger : commandTriggers.values()) {
                    if (!trigger.command.equals(commandLabel) || trigger.commandPaths.isEmpty()) {
                        continue;
                    }
                    for (String suggestion : collectPathSuggestions(trigger, argsTokens, currentArg)) {
                        if (!event.getTabCompletions().contains(suggestion)) {
                            event.getTabCompletions().add(suggestion);
                        }
                    }
                }
            }
        }
        
        for (Map.Entry<String, String> entry : playerTabCompleteTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                setEventVariables(player, eventVars);
                eventVars.put("event.completions", String.join(",", event.getTabCompletions()));
                eventVars.put("event.completion_list", new ArrayList<>(event.getTabCompletions()));
                eventVars.put("event.command", event.getChatMessage());
                executor.execute(graph, entry.getValue(), player, event);
            }
        }
    }

    @EventHandler
    public void onServerTabComplete(TabCompleteEvent event) {
        String buffer = event.getBuffer();
        if (buffer == null || !buffer.startsWith("/")) {
            return;
        }
        String raw = buffer.substring(1);
        String[] parts = raw.split(" ", -1);
        String commandLabel = normalizeCommandLabel(parts.length > 0 ? parts[0] : "");
        List<String> argsTokens = new ArrayList<>();
        String currentArg = parts.length > 1 ? parts[parts.length - 1] : "";
        for (int i = 1; i < Math.max(1, parts.length - 1); i++) {
            if (!parts[i].isBlank()) {
                argsTokens.add(parts[i]);
            }
        }
        if (commandLabel == null || commandLabel.isBlank()) {
            return;
        }
        for (CommandTrigger trigger : commandTriggers.values()) {
            if (!trigger.command.equals(commandLabel) || trigger.commandPaths.isEmpty()) {
                continue;
            }
            for (String suggestion : collectPathSuggestions(trigger, argsTokens, currentArg)) {
                if (!event.getCompletions().contains(suggestion)) {
                    event.getCompletions().add(suggestion);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        for (CommandTrigger trigger : commandTriggers.values()) {
            event.getCommands().add(trigger.command);
        }
    }
    
    @EventHandler
    public void onStructureSpawn(BlockGrowEvent event) {
        if (event.isCancelled()) return;
        Block block = event.getBlock();
        
        for (Map.Entry<String, String> entry : structureSpawnTriggers.entrySet()) {
            FlowGraph graph = storage.getGraph(entry.getKey());
            if (graph != null) {
                executor.clearEventVariables();
                Map<String, Object> eventVars = executor.getEventVariables();
                eventVars.put("event.block", block);
                eventVars.put("event.location", block.getLocation());
                eventVars.put("event.new_state", event.getBlock().getType().name());
                executor.execute(graph, entry.getValue(), null, event);
            }
        }
    }
}
