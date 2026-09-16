package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.FlowGraph;
import restudio.resync.Log;
import restudio.resync.server.TemporaryLifecycleDiagnostics;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerDefinitions;
import restudio.resync.flow.triggers.TriggerDispatcher;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.text.ReTextService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class GlobalTriggers implements Listener {
    private final JavaPlugin plugin;
    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final TriggerRegistry triggerRegistry;
    private final TriggerDispatcher triggerDispatcher;
    private final ReTextService text;
    private SystemEventListener systemEventListener;
    private volatile boolean runtimeBindingsActive;
    private volatile Map<String, CommandTrigger> commandTriggers = Map.of();
    private final Map<String, RuntimeFlowCommand> runtimeCommands = new ConcurrentHashMap<>();
    private static class CommandTrigger {
        private final String bindingId;
        private final String flowId;
        private final String startNode;
        private final String command;
        private final List<String> aliases;
        private final List<String> subcommands;
        private final List<List<String>> commandPaths;
        private final boolean structured;
        private final boolean enabled;
        private final String permission;
        private final String permissionMessage;
        private final String description;
        private final String usage;
        private final Map<String, Object> metadata;
        private final FlowGraph graph;

        private CommandTrigger(TypedCommandGraphAdapter.CommandBinding binding, FlowGraph graph) {
            this.bindingId = binding.bindingId();
            this.flowId = binding.graphId();
            this.startNode = binding.nodeId();
            this.command = binding.command();
            this.aliases = binding.aliases();
            this.subcommands = binding.subcommands();
            this.commandPaths = binding.commandPaths();
            this.structured = binding.structured();
            this.enabled = binding.enabled();
            this.permission = binding.permission();
            this.permissionMessage = binding.permissionMessage();
            this.description = binding.description();
            this.usage = binding.usage();
            this.metadata = binding.metadata();
            this.graph = Objects.requireNonNull(graph, "Command Graph Snapshot Is Required");
        }

        private boolean matchesLabel(String label) {
            return command.equals(label) || aliases.contains(label);
        }
    }

    private class RuntimeFlowCommand extends Command {
        private final String baseLabel;
        private final CommandTrigger trigger;

        private RuntimeFlowCommand(CommandTrigger trigger) {
            super(trigger.command);
            this.baseLabel = trigger.command;
            this.trigger = trigger;
            setDescription(trigger.description.isBlank() ? "ReSync flow command" : trigger.description);
            setUsage(trigger.usage.isBlank() ? "/" + trigger.command : trigger.usage);
            if (!trigger.permission.isBlank()) {
                setPermission(trigger.permission);
            }
            if (!trigger.permissionMessage.isBlank()) {
                setPermissionMessage(trigger.permissionMessage);
            }
            if (!trigger.aliases.isEmpty()) {
                setAliases(trigger.aliases);
            }
        }

        private List<String> aliases() {
            return trigger.aliases;
        }

        private boolean matches(CommandTrigger expected, Map<String, Command> knownCommands, String pluginPrefix) {
            if (!trigger.bindingId.equals(expected.bindingId)
                || trigger.graph.getResourceRevision() != expected.graph.getResourceRevision()
                || !Objects.equals(trigger.graph.getResourceHash(), expected.graph.getResourceHash())
                || !trigger.command.equals(expected.command)
                || !trigger.aliases.equals(expected.aliases)
                || !trigger.subcommands.equals(expected.subcommands)
                || !trigger.commandPaths.equals(expected.commandPaths)
                || trigger.structured != expected.structured
                || trigger.enabled != expected.enabled
                || !trigger.permission.equals(expected.permission)
                || !trigger.permissionMessage.equals(expected.permissionMessage)
                || !trigger.description.equals(expected.description)
                || !trigger.usage.equals(expected.usage)
                || !trigger.metadata.equals(expected.metadata)) {
                return false;
            }
            return knownCommands == null
                || knownCommands.get(baseLabel) == this
                || knownCommands.get(pluginPrefix + baseLabel) == this;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            if (runtimeCommands.get(baseLabel) != this) {
                return false;
            }
            String normalizedLabel = normalizeCommandLabel(commandLabel);
            if (!testPermission(sender)) {
                return true;
            }
            return trigger.matchesLabel(normalizedLabel)
                && executeCommandTrigger(trigger, normalizedLabel, List.of(args), sender);
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
                if (!trigger.matchesLabel(normalizedLabel) || trigger.commandPaths.isEmpty()) {
                    continue;
                }
                completions.addAll(collectPathSuggestions(trigger, argsTokens, currentArg));
            }
            return completions;
        }
    }

    public GlobalTriggers(FlowStorage storage, FlowExecutor executor, TriggerRegistry triggerRegistry, ReTextService text) {
        this(storage, executor, triggerRegistry, text, true);
    }

    public GlobalTriggers(FlowStorage storage, FlowExecutor executor, TriggerRegistry triggerRegistry, ReTextService text,
                          boolean activateBindings) {
        this.plugin = triggerRegistry.getPlugin();
        this.storage = storage;
        this.executor = executor;
        this.triggerRegistry = triggerRegistry;
        this.text = text;
        this.triggerDispatcher = new TriggerDispatcher(storage, executor, triggerRegistry.getPlugin());
        this.triggerDispatcher.registerFromContainer(new TriggerDefinitions());
        if (activateBindings) {
            activateRuntimeBindings();
        }
    }

    public void activateRuntimeBindings() {
        if (systemEventListener == null) {
            systemEventListener = new SystemEventListener(storage, executor, triggerRegistry);
            systemEventListener.setCompiledExecution(triggerDispatcher.getCompiledExecution());
        }
        runtimeBindingsActive = true;
        storage.setTypedCommandGraphChangeListener(ignored -> refreshBindings());
        try {
            refreshBindings();
        } catch (RuntimeException | Error failure) {
            try {
                shutdownRuntimeCommands();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public TriggerDispatcher getTriggerDispatcher() {
        return triggerDispatcher;
    }

    public void setCompiledExecution(CompiledTriggerExecution compiledExecution) {
        triggerDispatcher.setCompiledExecution(compiledExecution);
        if (compiledExecution != null) {
            commandTriggers.values().stream().map(trigger -> trigger.graph).distinct().forEach(compiledExecution::prepare);
            if (systemEventListener != null) {
                systemEventListener.setCompiledExecution(compiledExecution);
            }
        }
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
        FlowGraph graph = storage.getGraph("flow", flowId);
        if (graph == null || !graph.isEnabled()) {
            Log.warn("[ReSync] Failed to load flow for trigger: " + flowId);
            return;
        }

        String startNode = findStartNodeForEvent(graph, eventType);
        if (startNode == null && !eventType.contains("/")) {
            startNode = findStartNode(graph);
        }
        if (startNode == null) {
            Log.warn("[ReSync] No event node found for trigger: " + eventType + " in flow: " + flowId);
            return;
        }

        triggerDispatcher.registerBinding(eventType.toLowerCase(), flowId, startNode);
    }

    private String normalizeCommandLabel(String label) {
        if (label == null) {
            return null;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0 && namespaceSeparator < normalized.length() - 1) {
            normalized = normalized.substring(namespaceSeparator + 1);
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
        for (String part : args.trim().split("\\s+")) {
            if (!part.isBlank()) {
                parsed.add(part);
            }
        }
        return parsed;
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
        if (lower.startsWith("<text:") && lower.endsWith(">")) {
            String reference = token.substring("<text:".length(), token.length() - 1).trim();
            boolean valuesMode = reference.toLowerCase(Locale.ROOT).endsWith(":values");
            String resourceId = valuesMode ? reference.substring(0, reference.length() - ":values".length()).trim() : reference;
            ReTextService.ReTextResource resource = text != null ? text.resource(resourceId) : null;
            if (resource == null) {
                return values;
            }
            if (resource.kind() == ReTextService.ReTextKind.LIST) {
                values.addAll(resource.lines());
            } else if (resource.kind() == ReTextService.ReTextKind.MAP) {
                values.addAll(valuesMode ? resource.entries().values() : resource.entries().keySet());
            }
            return new ArrayList<>(new LinkedHashSet<>(values));
        }
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
        if (isTextToken(token)) {
            return false;
        }
        if (token.startsWith("<") && token.endsWith(">")) {
            return true;
        }
        return token.equalsIgnoreCase(arg);
    }

    private boolean matchesCommandPath(List<String> path, List<String> args) {
        return matchesCommandPath(path, 0, args, 0);
    }

    private boolean matchesCommandPath(List<String> path, int pathIndex, List<String> args, int argIndex) {
        if (pathIndex >= path.size()) {
            return true;
        }
        if (argIndex >= args.size()) {
            return false;
        }
        String token = path.get(pathIndex);
        if (isTextToken(token)) {
            for (String value : resolveDynamicTokenValues(token)) {
                List<String> valueTokens = parseArgsList(value);
                if (!valueTokens.isEmpty() && matchesArguments(valueTokens, args, argIndex)
                    && matchesCommandPath(path, pathIndex + 1, args, argIndex + valueTokens.size())) {
                    return true;
                }
            }
            return false;
        }
        return tokenMatches(token, args.get(argIndex)) && matchesCommandPath(path, pathIndex + 1, args, argIndex + 1);
    }

    private boolean matchesArguments(List<String> expected, List<String> args, int argIndex) {
        if (argIndex + expected.size() > args.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).equalsIgnoreCase(args.get(argIndex + index))) {
                return false;
            }
        }
        return true;
    }

    private List<String> collectPathSuggestions(CommandTrigger trigger, List<String> argsTokens, String currentArg) {
        List<String> suggestions = new ArrayList<>();
        for (List<String> path : trigger.commandPaths) {
            collectPathSuggestions(path, 0, argsTokens, 0, currentArg, suggestions);
        }
        return new ArrayList<>(new LinkedHashSet<>(suggestions));
    }

    private void collectPathSuggestions(List<String> path, int pathIndex, List<String> args, int argIndex, String currentArg, List<String> suggestions) {
        if (pathIndex >= path.size()) {
            return;
        }
        String token = path.get(pathIndex);
        if (isTextToken(token)) {
            for (String value : resolveDynamicTokenValues(token)) {
                List<String> valueTokens = parseArgsList(value);
                if (valueTokens.isEmpty()) {
                    continue;
                }
                int valueIndex = 0;
                int cursor = argIndex;
                while (cursor < args.size() && valueIndex < valueTokens.size() && valueTokens.get(valueIndex).equalsIgnoreCase(args.get(cursor))) {
                    cursor++;
                    valueIndex++;
                }
                if (cursor < args.size() && valueIndex < valueTokens.size()) {
                    continue;
                }
                if (cursor == args.size() && valueIndex < valueTokens.size()) {
                    addSuggestion(valueTokens.get(valueIndex), currentArg, suggestions);
                    continue;
                }
                collectPathSuggestions(path, pathIndex + 1, args, cursor, currentArg, suggestions);
            }
            return;
        }
        if (argIndex < args.size()) {
            if (tokenMatches(token, args.get(argIndex))) {
                collectPathSuggestions(path, pathIndex + 1, args, argIndex + 1, currentArg, suggestions);
            }
            return;
        }
        List<String> values = resolveDynamicTokenValues(token);
        if (values.isEmpty()) {
            values = List.of(token);
        }
        values.forEach(value -> addSuggestion(value, currentArg, suggestions));
    }

    private void addSuggestion(String value, String currentArg, List<String> suggestions) {
        if (value.toLowerCase(Locale.ROOT).startsWith(currentArg.toLowerCase(Locale.ROOT))) {
            suggestions.add(value);
        }
    }

    private boolean isTextToken(String token) {
        return token != null && token.toLowerCase(Locale.ROOT).startsWith("<text:") && token.endsWith(">");
    }

    private boolean executeCommandTrigger(CommandTrigger trigger, String commandLabel, List<String> argsList, CommandSender sender) {
        if (trigger == null || commandLabel == null) {
            return false;
        }
        if (trigger.structured && !trigger.commandPaths.isEmpty()) {
            boolean matchedPath = trigger.commandPaths.stream().anyMatch(path -> matchesCommandPath(path, argsList));
            if (!matchedPath) {
                return false;
            }
        }
        CorrelationId invocationId = CorrelationId.random();
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> ingressIdentity = TemporaryLifecycleDiagnostics.with(
            TemporaryLifecycleDiagnostics.identity(null, "command:" + trigger.flowId, "trigger-execution", null, null,
                invocationId, null, null, null, null),
            "source", "command", "bindingId", trigger.bindingId, "startNodeId", trigger.startNode,
            "outcome", "matched");
        TemporaryLifecycleDiagnostics.event("trigger_ingress", started, ingressIdentity);
        Map<String, Object> terminalIdentity = ingressIdentity;
        String failureCode = "TRIGGER.GRAPH_UNAVAILABLE";
        String failureReason = "graph-resolution-failed";
        try {
            FlowGraph graph = trigger.graph;
            if (graph == null || !graph.isEnabled()) {
                TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, ingressIdentity, "rejected",
                    "TRIGGER.GRAPH_UNAVAILABLE", graph == null ? "graph-unavailable" : "graph-disabled");
                CompiledTriggerExecution.warnInvocation("command|" + trigger.flowId + "|TRIGGER.GRAPH_UNAVAILABLE",
                    "Command trigger invocation rejected correlationId=" + invocationId.canonicalText()
                        + " diagnosticCode=TRIGGER.GRAPH_UNAVAILABLE");
                return false;
            }
            Map<String, Object> bindingIdentity = TemporaryLifecycleDiagnostics.with(ingressIdentity,
                "revision", graph.getResourceRevision(), "graphHash", graph.getResourceHash(), "outcome", "selected");
            terminalIdentity = bindingIdentity;
            TemporaryLifecycleDiagnostics.event("trigger_binding_selected", started, bindingIdentity);
            failureCode = "TRIGGER.CONTEXT_REJECTED";
            failureReason = "context-adaptation-failed";
            Player player = sender instanceof Player current ? current : null;
            Map<String, Object> eventVars = commandVariables(sender, trigger.command, commandLabel, argsList);
            eventVars.put("event.command_structured", trigger.structured);
            eventVars.put("event.command_allowed_subcommands", trigger.subcommands);
            CompiledTriggerExecution execution = triggerDispatcher.getCompiledExecution();
            if (execution == null) {
                TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, bindingIdentity, "rejected",
                    "TRIGGER.EXECUTOR_UNAVAILABLE", "compiled-executor-unavailable");
                CompiledTriggerExecution.warnInvocation("command|" + trigger.flowId + "|TRIGGER.EXECUTOR_UNAVAILABLE",
                    "Command trigger invocation rejected correlationId=" + invocationId.canonicalText()
                        + " diagnosticCode=TRIGGER.EXECUTOR_UNAVAILABLE");
                return false;
            }
            failureCode = "TRIGGER.EXECUTOR_REJECTED";
            failureReason = "synchronous-rejection";
            CompletableFuture<Void> future = execution.execute(graph, trigger.startNode, player, null, eventVars, null, invocationId);
            execution.observe(future, invocationId, "command:" + trigger.flowId);
        } catch (RuntimeException failure) {
            TemporaryLifecycleDiagnostics.terminal("trigger_execution_terminal", started, terminalIdentity, "failed",
                failureCode, failureReason);
            CompiledTriggerExecution.warnInvocation("command|" + trigger.flowId + "|" + failureCode,
                "Command trigger invocation failed correlationId=" + invocationId.canonicalText()
                    + " diagnosticCode=" + failureCode);
            return false;
        }
        return true;
    }

    public static Map<String, Object> commandVariables(CommandSender sender, String boundCommand, String commandLabel, List<String> arguments) {
        List<String> args = List.copyOf(arguments);
        Map<String, Object> variables = new HashMap<>();
        if (sender instanceof Player player) {
            variables.put("event.player", player);
        }
        String joined = String.join(" ", args);
        variables.put("event.command", (sender instanceof Player ? "/" : "") + commandLabel + (joined.isEmpty() ? "" : " " + joined));
        variables.put("event.is_cancelled", false);
        variables.put("event.bound_command", boundCommand);
        variables.put("event.command_label", commandLabel);
        variables.put("event.args", joined);
        variables.put("event.args_list", args);
        variables.put("event.args_count", args.size());
        variables.put("event.is_console", sender instanceof ConsoleCommandSender);
        variables.put("event.command_subcommand", args.isEmpty() ? "" : args.getFirst());
        variables.put("event.command_structured", false);
        variables.put("event.command_allowed_subcommands", List.of());
        return variables;
    }

    public static Map<String, Object> commandEventVariables(CommandSender sender, String command, boolean cancelled) {
        String content = command.startsWith("/") ? command.substring(1) : command;
        String[] tokens = content.strip().split("\\s+");
        String label = tokens.length == 0 ? "" : tokens[0];
        List<String> args = tokens.length < 2 ? List.of() : List.of(tokens).subList(1, tokens.length);
        Map<String, Object> variables = commandVariables(sender, "", label, args);
        variables.put("event.command", command);
        variables.put("event.is_cancelled", cancelled);
        return variables;
    }

    private CommandMap resolveCommandMap() {
        try {
            return Bukkit.getServer().getCommandMap();
        } catch (RuntimeException exception) {
            Log.warn("Unable to access the server command map: " + exception.getMessage());
            return null;
        }
    }

    private Map<String, Command> resolveKnownCommands(CommandMap commandMap) {
        try {
            return commandMap.getKnownCommands();
        } catch (RuntimeException exception) {
            Log.warn("Unable to access registered server commands: " + exception.getMessage());
            return null;
        }
    }

    public void shutdownRuntimeCommands() {
        runtimeBindingsActive = false;
        storage.setTypedCommandGraphChangeListener(null);
        CommandMap commandMap = resolveCommandMap();
        if (commandMap == null) {
            runtimeCommands.clear();
            commandTriggers = Map.of();
            return;
        }
        Map<String, Command> knownCommands = resolveKnownCommands(commandMap);
        String pluginPrefix = plugin.getName().toLowerCase(Locale.ROOT) + ":";
        for (Map.Entry<String, RuntimeFlowCommand> entry : new ArrayList<>(runtimeCommands.entrySet())) {
            RuntimeFlowCommand command = entry.getValue();
            command.unregister(commandMap);
            if (knownCommands != null) {
                knownCommands.remove(entry.getKey(), command);
                knownCommands.remove(pluginPrefix + entry.getKey(), command);
                command.aliases().forEach(alias -> {
                    knownCommands.remove(alias, command);
                    knownCommands.remove(pluginPrefix + alias, command);
                });
            }
        }
        runtimeCommands.clear();
        commandTriggers = Map.of();
    }

    private void refreshRuntimeCommands() {
        CommandMap commandMap = resolveCommandMap();
        if (commandMap == null) {
            return;
        }
        Map<String, Command> knownCommands = resolveKnownCommands(commandMap);
        Map<String, CommandTrigger> desired = new LinkedHashMap<>();
        commandTriggers.values().forEach(trigger -> desired.put(trigger.command, trigger));
        String pluginPrefix = plugin.getName().toLowerCase(Locale.ROOT) + ":";

        for (Map.Entry<String, RuntimeFlowCommand> entry : new ArrayList<>(runtimeCommands.entrySet())) {
            if (desired.containsKey(entry.getKey()) && entry.getValue().matches(desired.get(entry.getKey()), knownCommands, pluginPrefix)) {
                continue;
            }
            RuntimeFlowCommand command = entry.getValue();
            command.unregister(commandMap);
            runtimeCommands.remove(entry.getKey());
            if (knownCommands != null) {
                knownCommands.remove(entry.getKey(), command);
                knownCommands.remove(pluginPrefix + entry.getKey(), command);
                command.aliases().forEach(alias -> {
                    knownCommands.remove(alias, command);
                    knownCommands.remove(pluginPrefix + alias, command);
                });
            }
        }

        for (Map.Entry<String, CommandTrigger> desiredEntry : desired.entrySet()) {
            String commandLabel = desiredEntry.getKey();
            CommandTrigger trigger = desiredEntry.getValue();
            RuntimeFlowCommand current = runtimeCommands.get(commandLabel);
            if (current != null && current.matches(trigger, knownCommands, pluginPrefix)) {
                continue;
            }
            if (current != null) {
                current.unregister(commandMap);
                runtimeCommands.remove(commandLabel);
                if (knownCommands != null) {
                    knownCommands.remove(commandLabel, current);
                    knownCommands.remove(pluginPrefix + commandLabel, current);
                    current.aliases().forEach(alias -> {
                        knownCommands.remove(alias, current);
                        knownCommands.remove(pluginPrefix + alias, current);
                    });
                }
            }
            boolean externalCollision = knownCommands != null
                && triggerLabelsOwnedByOtherCommand(trigger, knownCommands, pluginPrefix);
            if (externalCollision) {
                Log.warn("Rejected typed command registration because a server command already owns: " + commandLabel);
                continue;
            }
            removeKnownRuntimeCommand(commandMap, knownCommands, commandLabel);
            removeKnownRuntimeCommand(commandMap, knownCommands, pluginPrefix + commandLabel);
            RuntimeFlowCommand command = new RuntimeFlowCommand(trigger);
            commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), command);
            runtimeCommands.put(commandLabel, command);
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.updateCommands();
        }
    }

    private boolean triggerLabelsOwnedByOtherCommand(CommandTrigger trigger, Map<String, Command> knownCommands, String pluginPrefix) {
        return trigger.aliases.stream().anyMatch(alias -> (knownCommands.containsKey(alias) && !isRuntimeFlowCommand(knownCommands.get(alias)))
                || (knownCommands.containsKey(pluginPrefix + alias) && !isRuntimeFlowCommand(knownCommands.get(pluginPrefix + alias))))
            || (knownCommands.containsKey(trigger.command) && !isRuntimeFlowCommand(knownCommands.get(trigger.command)))
            || (knownCommands.containsKey(pluginPrefix + trigger.command) && !isRuntimeFlowCommand(knownCommands.get(pluginPrefix + trigger.command)));
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
            || RuntimeFlowCommand.class.getName().equals(command.getClass().getName());
    }

    public void refreshBindings() {
        if (!runtimeBindingsActive) {
            return;
        }
        long started = TemporaryLifecycleDiagnostics.start();
        if (!Bukkit.isPrimaryThread()) {
            try {
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    refreshBindingsNow();
                    return null;
                }).get(10, TimeUnit.SECONDS);
                TemporaryLifecycleDiagnostics.event("command_refresh_wait", started,
                    TemporaryLifecycleDiagnostics.with(Map.of("operation", "refreshBindings", "outcome", "complete",
                        "queueWaitMs", elapsedMillis(started))));
            } catch (Exception exception) {
                TemporaryLifecycleDiagnostics.terminal("command_refresh_wait", started, Map.of("operation", "refreshBindings"),
                    "failed", "COMMAND_REFRESH.WAIT_FAILED", exception.getClass().getSimpleName());
                throw new IllegalStateException("Could not refresh flow commands on the server thread", exception);
            }
            return;
        }
        refreshBindingsNow();
        TemporaryLifecycleDiagnostics.event("command_refresh_wait", started,
            TemporaryLifecycleDiagnostics.with(Map.of("operation", "refreshBindings", "outcome", "complete",
                "queueWaitMs", 0L)));
    }

    private synchronized void refreshBindingsNow() {
        if (!runtimeBindingsActive) {
            return;
        }
        long started = TemporaryLifecycleDiagnostics.start();
        TypedCommandGraphAdapter.Snapshot commandSnapshot = storage.getTypedCommandGraphSnapshot();
        commandSnapshot.rejections().forEach(rejection -> Log.warn("Rejected typed command graph " + rejection.graphId() + ": " + rejection.detail()));
        Map<String, CommandTrigger> nextCommands = new LinkedHashMap<>();
        for (TypedCommandGraphAdapter.CommandBinding binding : commandSnapshot.activeBindings()) {
            FlowGraph graph = storage.getCommandGraph(binding.graphId());
            if (graph == null) {
                Log.warn("Rejected typed command graph " + binding.graphId() + ": command graph snapshot is unavailable");
                continue;
            }
            CompiledTriggerExecution execution = triggerDispatcher.getCompiledExecution();
            if (execution != null) {
                try {
                    execution.prepare(graph);
                } catch (RuntimeException exception) {
                    Log.warn("Rejected typed command graph " + binding.graphId() + ": " + exception.getMessage(), exception);
                    execution.retire("command", binding.graphId());
                    continue;
                }
            }
            CommandTrigger trigger = new CommandTrigger(binding, graph);
            for (String label : binding.labels()) {
                nextCommands.put(label + "\u0000" + binding.bindingId(), trigger);
            }
        }
        triggerDispatcher.clearBindings();
        commandTriggers = Collections.unmodifiableMap(new LinkedHashMap<>(nextCommands));

        if (triggerRegistry == null) {
            TemporaryLifecycleDiagnostics.event("command_refresh", started,
                TemporaryLifecycleDiagnostics.with(Map.of("operation", "refreshBindings", "activeCount", commandSnapshot.activeBindings().size(),
                    "rejectionCount", commandSnapshot.rejections().size(), "runtimeCount", runtimeCommands.size(),
                    "outcome", "noTriggerRegistry")));
            return;
        }

        for (TriggerBinding binding : triggerRegistry.getBindings(TriggerType.EVENT)) {
            registerTrigger(binding.getContext(), binding.getFlowId());
        }
        refreshRuntimeCommands();

        if (systemEventListener != null) {
            systemEventListener.refreshBindings();
            for (TriggerBinding binding : triggerRegistry.getBindings(TriggerType.SYSTEM)) {
                systemEventListener.registerTrigger(binding.getContext(), binding.getFlowId());
            }
        }
        TemporaryLifecycleDiagnostics.event("command_refresh", started,
            TemporaryLifecycleDiagnostics.with(Map.of("operation", "refreshBindings", "activeCount", commandSnapshot.activeBindings().size(),
                "rejectionCount", commandSnapshot.rejections().size(), "runtimeCount", runtimeCommands.size(),
                "eventCount", triggerRegistry.getBindings(TriggerType.EVENT).size(),
                "systemCount", triggerRegistry.getBindings(TriggerType.SYSTEM).size(), "outcome", "complete")));
    }

    private static long elapsedMillis(long started) {
        return started == 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started));
    }

    public void refreshGraph(String graphId) {
        if (!runtimeBindingsActive) {
            return;
        }
        triggerDispatcher.refreshGraph(graphId);
        if (systemEventListener != null) {
            systemEventListener.refreshGraph(graphId);
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
        if (eventType != null && eventType.contains("/")) {
            if (!triggerDispatcher.hasEventType(eventType)) {
                return null;
            }
            return graph.getNodes().entrySet().stream()
                .filter(entry -> entry.getValue() != null && (eventType.equals(entry.getValue().getType())
                    || eventType.equals(executor.eventBindingContext(entry.getValue().getType()))))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        }
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
