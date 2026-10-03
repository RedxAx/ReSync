package restudio.resync.qa;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import restudio.resync.flow.CompiledCoreFlowExecutionBridge;
import restudio.resync.flow.CompiledFunctionExecutionBridge;
import restudio.resync.flow.CompiledFunctionExecutionRequest;
import restudio.resync.flow.CompiledRuntimeContextAdapter;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.triggers.TriggerDispatcher;
import restudio.resync.server.ProtocolRequestAuthority;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class QaExecutionAdapter {
    private static final Map<String, Object> DESCRIPTION = Map.of("operations", List.of(
        operation("execution.describe", "Describe execution QA operations", List.of(), List.of()),
        operation("execution.discover", "List existing typed graphs and registered event bindings", List.of(), List.of()),
        operation("flow.run", "Run an existing Flow or Command graph through its compiled runtime",
            List.of("resourceType", "resourceId", "revision", "checksum", "startNodeId"),
            List.of("variables", "playerId", "timeoutMillis", "invocationId")),
        operation("function.run", "Run an existing typed Function and return its typed result",
            List.of("resourceType", "resourceId", "revision", "checksum"),
            List.of("inputs", "variables", "playerId", "timeoutMillis", "invocationId")),
        operation("event.inject", "Dispatch a Block Break event fixture to real registered listeners",
            List.of("eventType", "worldId", "playerId", "x", "y", "z"),
            List.of("cancelled", "dropItems", "experience"))),
        "permission", "resync.qa", "completion", "Tracked physical execution has ended",
        "nodeResults", "Final result for each executed node", "eventSemantics", "dispatch-only",
        "vanillaActionPerformed", false);
    private final Plugin plugin;
    private final ServerId serverId;
    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final CompiledTriggerExecution triggers;
    private final CompiledCoreFlowExecutionBridge flows;
    private final CompiledFunctionExecutionBridge functions;
    private final RuntimeAuthority authority;
    private final RuntimePrincipalAuthority principals;
    private final TriggerDispatcher dispatcher;
    private final Map<String, Object> description;

    public QaExecutionAdapter(Plugin plugin, ServerId serverId, FlowStorage storage, FlowExecutor executor,
            CompiledTriggerExecution triggers, CompiledCoreFlowExecutionBridge flows,
            CompiledFunctionExecutionBridge functions, RuntimeAuthority authority,
            RuntimePrincipalAuthority principals, TriggerDispatcher dispatcher) {
        this.plugin = Objects.requireNonNull(plugin, "QA Plugin Is Required");
        this.serverId = Objects.requireNonNull(serverId, "QA Server Identity Is Required");
        this.storage = Objects.requireNonNull(storage, "QA Graph Storage Is Required");
        this.executor = Objects.requireNonNull(executor, "QA Execution Owner Is Required");
        this.triggers = Objects.requireNonNull(triggers, "QA Compiled Trigger Owner Is Required");
        this.flows = Objects.requireNonNull(flows, "QA Compiled Flow Owner Is Required");
        this.functions = Objects.requireNonNull(functions, "QA Typed Function Owner Is Required");
        this.authority = Objects.requireNonNull(authority, "QA Runtime Authority Is Required");
        this.principals = Objects.requireNonNull(principals, "QA Principal Authority Is Required");
        this.dispatcher = Objects.requireNonNull(dispatcher, "QA Event Dispatcher Is Required");
        if (principals.authority() != authority) {
            throw new IllegalArgumentException("QA Principal And Runtime Authorities Must Match");
        }
        if (functions.hasTypedFunctionProviders()) {
            description = DESCRIPTION;
        } else {
            Map<String, Object> value = new LinkedHashMap<>(DESCRIPTION);
            value.put("operations", ((List<?>) DESCRIPTION.get("operations")).stream().map(item -> {
                Map<String, Object> operation = (Map<String, Object>) item;
                if (!"function.run".equals(operation.get("id"))) {
                    return operation;
                }
                Map<String, Object> unavailable = new LinkedHashMap<>(operation);
                unavailable.put("supported", false);
                unavailable.put("reason", "Typed Function source and capability providers are unavailable");
                return Map.copyOf(unavailable);
            }).toList());
            description = Map.copyOf(value);
        }
    }

    public Map<String, Object> describe() {
        return description;
    }

    public CompletionStage<Map<String, Object>> invoke(CommandSender actor, String operation, Map<String, Object> input) {
        try {
            String operator = ProtocolRequestAuthority.trustedOperatorId(actor);
            if (operator == null) {
                throw new SecurityException("QA Requires Permission And Admission On The Server Thread");
            }
            if (!plugin.isEnabled()) {
                throw new IllegalStateException("QA Execution Is Unavailable While The Plugin Is Stopped");
            }
            Map<String, Object> request = input == null ? Map.of() : input;
            return switch (Objects.requireNonNull(operation, "QA Operation Is Required")) {
                case "execution.describe" -> CompletableFuture.completedFuture(describe());
                case "execution.discover" -> CompletableFuture.completedFuture(discover());
                case "flow.run" -> runFlow(actor, operator, request);
                case "function.run" -> runFunction(actor, operator, request);
                case "event.inject" -> admitEvent(request);
                default -> throw new IllegalArgumentException("Unknown Execution QA Operation: " + operation);
            };
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<Map<String, Object>> runFlow(CommandSender actor, String operator, Map<String, Object> input) {
        CoreGraphStorageBoundary.Decoded source = graph(input, Set.of("flow", "command"));
        String start = text(input, "startNodeId");
        if (source.graphDocument().nodes().stream().noneMatch(node -> node.instanceId().canonicalText().equals(start))) {
            throw new IllegalArgumentException("The Requested Start Node Is Not In The Typed Graph");
        }
        RuntimePrincipal principal = principal(actor, operator);
        Map<String, Object> committed = resource(source);
        return triggers.executeSourceObserved(source, start, player(actor, input), null, object(input, "variables"), principal,
            invocationId(input), deadline(input)).thenApply(result -> withResource(committed, result.canonicalValue()));
    }

    private CompletionStage<Map<String, Object>> runFunction(CommandSender actor, String operator, Map<String, Object> input) {
        if (!functions.hasTypedFunctionProviders()) {
            throw new IllegalStateException("Typed Function Source And Capability Providers Are Unavailable");
        }
        CoreGraphStorageBoundary.Decoded source = function(input);
        RuntimePrincipal principal = principal(actor, operator);
        Player player = player(actor, input);
        Map<String, Object> variables = object(input, "variables");
        CompiledRuntimeContextAdapter.Result context = CompiledRuntimeContextAdapter.adapt(serverId, principal, player, null, variables);
        if (!context.accepted()) {
            throw new IllegalArgumentException("The Function Runtime Context Was Rejected: " + context.failure());
        }
        CorrelationId invocationId = invocationId(input);
        Map<String, Object> committed = resource(source);
        CompiledFunctionExecutionRequest request = functions.requestForSource(source.functionSourceDocument(), player, null, object(input, "inputs"),
            variables, serverId, authority, principal, invocationId, context.context(), deadline(input), operator, null);
        return executor.executeCompiledFunction(request, functions).thenApply(result -> {
            Map<String, Object> output = new LinkedHashMap<>(result.canonicalValue());
            output.put("invocationId", invocationId.canonicalText());
            output.put("physicalComplete", true);
            return withResource(committed, output);
        });
    }

    private CompletionStage<Map<String, Object>> injectEvent(Map<String, Object> input) {
        if (!"block_break".equals(text(input, "eventType"))) {
            throw new IllegalArgumentException("The Supported Event Fixture Is Block Break");
        }
        World world = Bukkit.getWorld(UUID.fromString(text(input, "worldId")));
        Player player = onlinePlayer(text(input, "playerId"));
        if (world == null || player.getWorld() != world) {
            throw new IllegalArgumentException("The Fixture Requires A Loaded World And A Player In That World");
        }
        int x = integer(input, "x");
        int y = integer(input, "y");
        int z = integer(input, "z");
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
            throw new IllegalArgumentException("The Fixture Position Must Be Inside A Loaded Chunk And World Height");
        }
        int experience = input.containsKey("experience") ? integer(input, "experience") : 0;
        if (experience < 0) {
            throw new IllegalArgumentException("Fixture Experience Cannot Be Negative");
        }
        Block block = world.getBlockAt(x, y, z);
        String originalData = block.getBlockData().getAsString();
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setCancelled(bool(input, "cancelled", false));
        event.setDropItems(bool(input, "dropItems", true));
        event.setExpToDrop(experience);
        UUID worldId = world.getUID();
        Map<String, Object> fixtureIdentity = Map.of("eventType", "block_break", "worldId", worldId.toString(),
            "playerId", player.getUniqueId().toString(), "x", x, "y", y, "z", z, "originalBlockData", originalData);
        Map<String, Object> initialEvent = Map.of("cancelled", event.isCancelled(), "dropItems", event.isDropItems(),
            "experience", event.getExpToDrop());
        TriggerDispatcher.EventObservation observation = dispatcher.observeEvent(event);
        Throwable dispatchFailure = null;
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException | Error failure) {
            dispatchFailure = failure;
        } finally {
            observation.close();
        }
        Map<String, Object> fixtureSnapshot = new LinkedHashMap<>(fixtureIdentity);
        Map<String, Object> eventSnapshot = initialEvent;
        Map<String, Object> snapshotFailure = Map.of();
        try {
            boolean available = Bukkit.getWorld(worldId) == world && world.isChunkLoaded(x >> 4, z >> 4);
            fixtureSnapshot.put("blockAvailableAfterDispatch", available);
            if (available) {
                fixtureSnapshot.put("blockDataAfterDispatch", block.getBlockData().getAsString());
            }
            eventSnapshot = Map.of("cancelled", event.isCancelled(), "dropItems", event.isDropItems(),
                "experience", event.getExpToDrop());
        } catch (RuntimeException | Error failure) {
            fixtureSnapshot.put("blockAvailableAfterDispatch", false);
            snapshotFailure = Map.of("type", failure.getClass().getName(), "message",
                failure.getMessage() == null ? "Event Snapshot Failed" : failure.getMessage());
        }
        Map<String, Object> fixture = Map.copyOf(fixtureSnapshot);
        Map<String, Object> eventResult = eventSnapshot;
        Map<String, Object> dispatchObservationFailure = snapshotFailure;
        Throwable failure = dispatchFailure;
        return observation.completion().thenCompose(observed -> onMain(() -> {
            Map<String, Object> output = eventResult(observed, fixture, eventResult, failure, dispatchObservationFailure);
            output.put("eventAfterCompletion", Map.of("cancelled", event.isCancelled(), "dropItems", event.isDropItems(),
                "experience", event.getExpToDrop()));
            boolean available = Bukkit.getWorld(worldId) == world && world.isChunkLoaded(x >> 4, z >> 4);
            output.put("blockAfterCompletion", available ? Map.of("available", true, "blockData", block.getBlockData().getAsString())
                : Map.of("available", false));
            return Map.copyOf(output);
        }).exceptionally(finalSnapshotFailure -> {
            Map<String, Object> output = eventResult(observed, fixture, eventResult, failure, dispatchObservationFailure);
            output.put("blockAfterCompletion", Map.of("available", false));
            output.put("finalObservationFailure", "The Server Thread Snapshot Is Unavailable");
            return Map.copyOf(output);
        }));
    }

    private CompletionStage<Map<String, Object>> admitEvent(Map<String, Object> input) {
        AtomicReference<CompletionStage<Map<String, Object>>> execution = new AtomicReference<>();
        return executor.runOnMain(plugin, () -> execution.set(injectEvent(input))).thenCompose(ignored -> execution.get());
    }

    private static Map<String, Object> eventResult(Map<String, Object> observed, Map<String, Object> fixture,
            Map<String, Object> eventResult, Throwable failure, Map<String, Object> snapshotFailure) {
        Map<String, Object> output = new LinkedHashMap<>(observed);
        output.put("semantics", "dispatch-only");
        output.put("vanillaActionPerformed", false);
        output.put("fixture", fixture);
        output.put("eventAfterDispatch", eventResult);
        if (!snapshotFailure.isEmpty()) {
            output.put("dispatchObservationFailure", snapshotFailure);
        }
        if (failure != null) {
            output.put("dispatchFailure", Map.of("type", failure.getClass().getName(), "message",
                failure.getMessage() == null ? "Event Dispatch Failed" : failure.getMessage()));
        }
        return output;
    }

    private Map<String, Object> discover() {
        List<Map<String, Object>> graphs = new ArrayList<>();
        for (String type : List.of("flow", "function", "command")) {
            for (String id : storage.listGraphIds(type)) {
                storage.getCoreGraph(type, id).ifPresent(source -> {
                    GraphDocument document = source.graphDocument() != null ? source.graphDocument() : source.functionSourceDocument().graph();
                    Map<String, Object> value = new LinkedHashMap<>(resource(source));
                    value.put("enabled", source.envelope().assetActivationState() == ResourceActivationState.ACTIVE);
                    value.put("nodeIds", document.nodes().stream().map(node -> node.instanceId().canonicalText()).sorted().toList());
                    graphs.add(Map.copyOf(value));
                });
            }
        }
        return Map.of("resources", List.copyOf(graphs), "eventBindings", dispatcher.describeBindings());
    }

    private CoreGraphStorageBoundary.Decoded function(Map<String, Object> input) {
        String type = text(input, "resourceType");
        String id = text(input, "resourceId");
        long revision = number(input, "revision").longValueExact();
        String checksum = ContentHash.of(text(input, "checksum")).canonicalText();
        if (!"function".equals(type) || revision < 1) {
            throw new IllegalArgumentException("A Typed Function And Positive Revision Are Required");
        }
        CoreGraphStorageBoundary.Decoded source = storage.getCoreGraph(type, id).orElseThrow(() ->
            new IllegalArgumentException("The Authoritative Typed Function Source Is Unavailable"));
        ServerResourceLocator expected = new ServerResourceLocator(serverId,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), id);
        if (source.functionSourceDocument() == null || !expected.equals(source.functionSourceDocument().graph().resource())
            || source.envelope().assetActivationState() != ResourceActivationState.ACTIVE
            || source.envelope().assetRevision() != revision || !checksum.equals(source.envelope().assetHash().canonicalText())) {
            throw new IllegalArgumentException("The Requested Typed Function Source Is Not Active Or Current");
        }
        return source;
    }

    private CoreGraphStorageBoundary.Decoded graph(Map<String, Object> input, Set<String> allowedTypes) {
        String type = text(input, "resourceType");
        String id = text(input, "resourceId");
        long revision = number(input, "revision").longValueExact();
        String checksum = ContentHash.of(text(input, "checksum")).canonicalText();
        if (!allowedTypes.contains(type) || revision < 1) {
            throw new IllegalArgumentException("A Supported Typed Graph And Positive Revision Are Required");
        }
        CoreGraphStorageBoundary.Decoded source = triggers.source(type, id).orElseThrow(() ->
            new IllegalArgumentException("The Authoritative Typed Graph Source Is Unavailable"));
        if (source.envelope().assetRevision() != revision || !checksum.equals(source.envelope().assetHash().canonicalText())
            || source.envelope().assetActivationState() != ResourceActivationState.ACTIVE) {
            throw new IllegalArgumentException("The Requested Typed Graph Revision Or Checksum Is Not Current And Active");
        }
        return source;
    }

    private RuntimePrincipal principal(CommandSender actor, String operator) {
        return actor instanceof Player player ? principals.issuePlayer(player.getUniqueId().toString())
            : principals.issueSystem(operator);
    }

    private static Player player(CommandSender actor, Map<String, Object> input) {
        return input.containsKey("playerId") ? onlinePlayer(text(input, "playerId")) : actor instanceof Player player ? player : null;
    }

    private static Player onlinePlayer(String identity) {
        Player player = Bukkit.getPlayer(UUID.fromString(identity));
        if (player == null || !player.isOnline()) {
            throw new IllegalArgumentException("The Fixture Player Must Be Online");
        }
        return player;
    }

    private static CorrelationId invocationId(Map<String, Object> input) {
        return input.containsKey("invocationId") ? CorrelationId.parseCanonicalText(text(input, "invocationId")) : CorrelationId.random();
    }

    private static long deadline(Map<String, Object> input) {
        long timeout = input.containsKey("timeoutMillis") ? number(input, "timeoutMillis").longValueExact() : 30_000L;
        if (timeout < 1 || timeout > 60_000L) {
            throw new IllegalArgumentException("QA Timeout Must Be Between 1 And 60000 Milliseconds");
        }
        return Math.addExact(System.currentTimeMillis(), timeout);
    }

    private static Map<String, Object> withResource(Map<String, Object> resource, Map<String, Object> result) {
        Map<String, Object> output = new LinkedHashMap<>(result);
        output.put("resource", resource);
        return Map.copyOf(output);
    }

    private static Map<String, Object> resource(CoreGraphStorageBoundary.Decoded source) {
        GraphDocument graph = source.graphDocument() != null ? source.graphDocument() : source.functionSourceDocument().graph();
        return Map.of("resourceType", graph.resource().resourceType().value(), "resourceId", graph.resource().id(),
            "revision", source.envelope().assetRevision(), "checksum", source.envelope().assetHash().canonicalText());
    }

    private CompletionStage<Map<String, Object>> onMain(Supplier<Map<String, Object>> action) {
        AtomicReference<Map<String, Object>> result = new AtomicReference<>();
        return executor.runOnMain(plugin, () -> result.set(action.get())).thenApply(ignored -> result.get());
    }

    private static Map<String, Object> operation(String id, String description, List<String> required, List<String> optional) {
        return Map.of("id", id, "description", description, "supported", true,
            "input", Map.of("required", required, "optional", optional));
    }

    private static String text(Map<String, Object> input, String name) {
        if (!(input.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("QA Field Must Be Nonblank Text: " + name);
        }
        return value;
    }

    private static BigDecimal number(Map<String, Object> input, String name) {
        if (!(input.get(name) instanceof Number value)) {
            throw new IllegalArgumentException("QA Field Must Be A Number: " + name);
        }
        return new BigDecimal(value.toString());
    }

    private static int integer(Map<String, Object> input, String name) {
        return number(input, name).intValueExact();
    }

    private static boolean bool(Map<String, Object> input, String name, boolean fallback) {
        if (!input.containsKey(name)) {
            return fallback;
        }
        if (!(input.get(name) instanceof Boolean value)) {
            throw new IllegalArgumentException("QA Field Must Be Boolean: " + name);
        }
        return value;
    }

    private static Map<String, Object> object(Map<String, Object> input, String name) {
        if (!input.containsKey(name)) {
            return Map.of();
        }
        if (!(input.get(name) instanceof Map<?, ?> value)) {
            throw new IllegalArgumentException("QA Field Must Be An Object: " + name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!(key instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("QA Object Keys Must Be Nonblank Text: " + name);
            }
            result.put(text, item);
        });
        return result;
    }
}
