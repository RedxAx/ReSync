package restudio.resync.flow;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.SimplePluginManager;
import restudio.flow.data.FlowGraph;
import restudio.resync.ReSync;
import restudio.resync.Log;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerDefinitions;
import restudio.resync.flow.triggers.TriggerDispatcher;
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
    private final TriggerDispatcher triggerDispatcher;
    private SystemEventListener systemEventListener;
    private final Gson gson = new Gson();

    private final Map<String, CommandTrigger> commandTriggers = new ConcurrentHashMap<>();
    private final Map<String, RuntimeFlowCommand> runtimeCommands = new ConcurrentHashMap<>();
    private static final Set<String> RESYNC_COMMAND_EVENT_TYPES = Set.of("event.resync.command", "event:resync_command");

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
        this.triggerDispatcher = new TriggerDispatcher(storage, executor, ReSync.getInstance());
        this.triggerDispatcher.registerFromContainer(new TriggerDefinitions());
        this.systemEventListener = new SystemEventListener(storage, executor, triggerRegistry);
        refreshBindings();
    }

    public TriggerDispatcher getTriggerDispatcher() {
        return triggerDispatcher;
    }

    public SystemEventListener getSystemEventListener() {
        return systemEventListener;
    }

    public void setSystemEventListener(SystemEventListener listener) {
        this.systemEventListener = listener;
    }

    private void setEventVariables(Player player, Map<String, Object> variables) {
        if (player != null) {
            variables.put("event.player", player);
        } else {
            variables.remove("event.player");
        }
    }

    public void registerTrigger(String eventType, String flowId) {
        FlowGraph graph = storage.getGraph(flowId);
        if (graph == null) {
            Log.warn("[ReSync] Failed to load flow for trigger: " + flowId);
            return;
        }

        String startNode = findStartNodeForEvent(graph, eventType);
        if (startNode == null) {
            startNode = findStartNode(graph);
        }
        if (startNode == null) {
            Log.warn("[ReSync] No event node found for trigger: " + eventType + " in flow: " + flowId);
            return;
        }

        triggerDispatcher.registerBinding(eventType.toLowerCase(), flowId, startNode);
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
            if (entry.getValue() != null && RESYNC_COMMAND_EVENT_TYPES.contains(entry.getValue().getType())) {
                startNode = entry.getKey();
                break;
            }
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
        Map<String, Object> eventVars = new java.util.HashMap<>();
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
        executor.execute(graph, trigger.startNode, player, event, eventVars);
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

    public void shutdownRuntimeCommands() {
        CommandMap commandMap = resolveCommandMap();
        if (commandMap == null) {
            runtimeCommands.clear();
            commandTriggers.clear();
            return;
        }
        Map<String, Command> knownCommands = resolveKnownCommands(commandMap);
        String pluginPrefix = ReSync.getInstance().getName().toLowerCase(Locale.ROOT) + ":";
        for (Map.Entry<String, RuntimeFlowCommand> entry : new ArrayList<>(runtimeCommands.entrySet())) {
            RuntimeFlowCommand command = entry.getValue();
            command.unregister(commandMap);
            if (knownCommands != null) {
                knownCommands.remove(entry.getKey(), command);
                knownCommands.remove(pluginPrefix + entry.getKey(), command);
            }
        }
        runtimeCommands.clear();
        commandTriggers.clear();
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
            RuntimeFlowCommand current = runtimeCommands.get(commandLabel);
            if (current != null && (knownCommands == null || knownCommands.get(commandLabel) == current || knownCommands.get(pluginPrefix + commandLabel) == current)) {
                continue;
            }
            if (current != null) {
                current.unregister(commandMap);
                runtimeCommands.remove(commandLabel);
            }
            removeKnownRuntimeCommand(commandMap, knownCommands, commandLabel);
            removeKnownRuntimeCommand(commandMap, knownCommands, pluginPrefix + commandLabel);
            RuntimeFlowCommand command = new RuntimeFlowCommand(commandLabel);
            commandMap.register(ReSync.getInstance().getName().toLowerCase(Locale.ROOT), command);
            runtimeCommands.put(commandLabel, command);
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.updateCommands();
        }
    }

    private void removeKnownRuntimeCommand(CommandMap commandMap, Map<String, Command> knownCommands, String key) {
        if (knownCommands == null || key == null) {
            return;
        }
        Command command = knownCommands.get(key);
        if (!isRuntimeFlowCommand(command)) {
            return;
        }
        command.unregister(commandMap);
        knownCommands.remove(key);
    }

    private boolean isRuntimeFlowCommand(Command command) {
        if (command == null) {
            return false;
        }
        return command instanceof RuntimeFlowCommand
            || RuntimeFlowCommand.class.getName().equals(command.getClass().getName())
            || "ReSync flow command".equals(command.getDescription());
    }

    public void refreshBindings() {
        triggerDispatcher.clearBindings();
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

    private String findStartNode(FlowGraph graph) {
        for (var entry : graph.getNodes().entrySet()) {
            String type = entry.getValue().getType();
            if (type != null && (type.startsWith("event:") || type.startsWith("event.") || "start".equals(type))) {
                return entry.getKey();
            }
        }
        return graph.getNodes().keySet().stream().findFirst().orElse(null);
    }

    private String findStartNodeForEvent(FlowGraph graph, String eventType) {
        String normalizedRequested = normalizeEventKey(eventType);
        String canonicalRequested = triggerDispatcher.resolveEventType(normalizedRequested);

        for (var entry : graph.getNodes().entrySet()) {
            String nodeType = entry.getValue().getType();
            String normalizedNode = normalizeEventKey(nodeType);
            if (normalizedNode == null) {
                continue;
            }

            if (normalizedRequested != null && normalizedRequested.equals(normalizedNode)) {
                return entry.getKey();
            }

            if (canonicalRequested == null) {
                continue;
            }

            if (canonicalRequested.equals(normalizedNode)) {
                return entry.getKey();
            }

            String canonicalNode = triggerDispatcher.resolveEventType(normalizedNode);
            if (canonicalRequested.equals(canonicalNode)) {
                return entry.getKey();
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
}
