package restudio.resync.flow.handler.generic;

import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.network.NetworkFlowValues;
import restudio.resync.network.NetworkEventPublish;
import restudio.resync.network.NetworkNodePresence;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.NetworkPlayerRouteResult;
import restudio.resync.network.NetworkTransferIntent;
import restudio.resync.network.NetworkVariable;
import restudio.resync.network.NetworkVariableMutation;
import restudio.resync.network.NetworkVariableScope;
import restudio.resync.network.NetworkVariableType;
import restudio.resync.network.paper.ReSyncNetworkAgent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class NetworkFlowHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new LinkedHashMap<>();

    public NetworkFlowHandler() {
        operations.put("network_status", this::networkStatus);
        operations.put("network_servers", this::networkServers);
        operations.put("network_server_health", this::networkServerHealth);
        operations.put("network_variable_get", this::variableGet);
        operations.put("network_variable_set", this::variableSet);
        operations.put("network_variable_compare_set", this::variableCompareSet);
        operations.put("network_event_publish", this::eventPublish);
        operations.put("network_broadcast", this::broadcast);
        operations.put("network_proxy_command", this::proxyCommand);
        operations.put("network_node_mode", this::nodeMode);
        operations.put("network_player_route", this::playerRoute);
        operations.put("network_player_handoff", this::playerHandoff);
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("NetworkFlowHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> action = operation == null ? null : operations.get(operation);
        if (action == null) {
            fail(ctx, node, "Network Flow Operation Is Not Available");
            return;
        }
        try {
            action.accept(ctx, node);
        } catch (RuntimeException exception) {
            fail(ctx, node, rootMessage(exception));
        }
    }

    private void networkStatus(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = agent();
        ctx.setOutput(node, "connected", agent != null && agent.connected());
        ctx.setOutput(node, "network_id", agent == null ? "" : agent.networkId());
        ctx.setOutput(node, "node_id", reference("network_node", agent == null ? "" : agent.nodeId(), agent != null,
            Map.of("networkId", agent == null ? "" : agent.networkId())));
        ctx.triggerOutput("flow");
    }

    private void networkServers(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        List<FlowResourceReference> servers = agent.presenceSnapshot().values().stream().filter(observed -> observed.capacity() > 0)
            .sorted((left, right) -> left.nodeId().compareToIgnoreCase(right.nodeId())).map(this::presence).toList();
        ctx.setOutput(node, "servers", servers);
        ctx.setOutput(node, "count", servers.size());
        ctx.triggerOutput("flow");
    }

    private void networkServerHealth(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        String nodeId = ctx.getInputValue(node, "node_id", String.class, "");
        NetworkNodePresence observed = agent.presenceSnapshot().get(nodeId);
        ctx.setOutput(node, "found", observed != null);
        ctx.setOutput(node, "server", observed == null ? reference("network_node", nodeId, false, Map.of()) : presence(observed));
        ctx.setOutput(node, "status", observed == null ? "UNKNOWN" : observed.status().name());
        ctx.setOutput(node, "players", observed == null ? 0 : observed.players());
        ctx.setOutput(node, "capacity", observed == null ? 0 : observed.capacity());
        ctx.setOutput(node, "tps", observed == null ? -1 : observed.tps());
        ctx.setOutput(node, "mspt", observed == null ? -1 : observed.mspt());
        ctx.triggerOutput("flow");
    }

    private void variableGet(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        NetworkVariableScope scope = scope(ctx.getInputValue(node, "scope", String.class, "NETWORK"));
        String scopeId = ctx.getInputValue(node, "scope_id", String.class, "");
        String key = ctx.getInputValue(node, "key", String.class, "");
        async(ctx, node, () -> agent.getVariable(scope, scopeId, key).join(), (optional, ignored) -> {
            Optional<NetworkVariable> result = optional;
            ctx.setOutput(node, "exists", result.isPresent());
            ctx.setOutput(node, "variable", result.map(this::variable).orElse(null));
            ctx.setOutput(node, "value", result.map(NetworkFlowValues::decode).orElse(null));
            ctx.setOutput(node, "type", result.map(variable -> variable.type().name()).orElse(""));
            ctx.setOutput(node, "revision", result.map(NetworkVariable::revision).orElse(0L));
            ctx.setOutput(node, "expires_at", result.map(NetworkVariable::expiresAt).orElse(0L));
        });
    }

    private void variableSet(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        NetworkVariableScope scope = scope(ctx.getInputValue(node, "scope", String.class, "NETWORK"));
        String scopeId = ctx.getInputValue(node, "scope_id", String.class, "");
        String key = ctx.getInputValue(node, "key", String.class, "");
        NetworkVariableType type = NetworkFlowValues.type(ctx.getInputValue(node, "type", String.class, "STRING"));
        byte[] value = NetworkFlowValues.encode(type, ctx.getInputValue(node, "value"));
        long expiresAt = expiry(ctx.getInputValue(node, "ttl_seconds"));
        async(ctx, node, () -> upsert(agent, scope, scopeId, key, type, value, expiresAt, 3).join(), (variable, ignored) -> {
        });
    }

    private void variableCompareSet(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        NetworkVariableScope scope = scope(ctx.getInputValue(node, "scope", String.class, "NETWORK"));
        String scopeId = ctx.getInputValue(node, "scope_id", String.class, "");
        String key = ctx.getInputValue(node, "key", String.class, "");
        NetworkVariableType type = NetworkFlowValues.type(ctx.getInputValue(node, "type", String.class, "STRING"));
        byte[] value = NetworkFlowValues.encode(type, ctx.getInputValue(node, "value"));
        long expectedRevision = number(ctx.getInputValue(node, "expected_revision"), 0);
        long expiresAt = expiry(ctx.getInputValue(node, "ttl_seconds"));
        NetworkVariableMutation mutation = new NetworkVariableMutation(scope, scopeId, key, type, value, expectedRevision, expiresAt);
        async(ctx, node, () -> agent.setVariable(mutation).join(), (variable, ignored) -> {
        });
    }

    private void eventPublish(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        String eventId = ctx.getInputValue(node, "event_id", String.class, "");
        if (eventId.isBlank()) {
            eventId = UUID.randomUUID().toString();
        }
        String channel = ctx.getInputValue(node, "channel", String.class, "flow");
        String subject = ctx.getInputValue(node, "subject", String.class, "");
        long ttlSeconds = Math.clamp(number(ctx.getInputValue(node, "ttl_seconds"), 86400), 1, 604800);
        long createdAt = Instant.now().toEpochMilli();
        NetworkEventPublish event = new NetworkEventPublish(eventId, channel, subject, NetworkFlowValues.eventPayload(ctx.getInputValue(node, "data")), createdAt, createdAt + TimeUnit.SECONDS.toMillis(ttlSeconds));
        async(ctx, node, () -> agent.publishEvent(event).join(), (published, ignored) -> {
            ctx.setOutput(node, "event_id", published.eventId());
            ctx.setOutput(node, "created_at", published.createdAt());
            ctx.setOutput(node, "expires_at", published.expiresAt());
        });
    }

    private void broadcast(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        String message = ctx.getInputValue(node, "message", String.class, "");
        async(ctx, node, () -> agent.broadcast(message).join(), (unused, ignored) -> ctx.setOutput(node, "sent", true));
    }

    private void proxyCommand(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        String command = ctx.getInputValue(node, "command", String.class, "");
        async(ctx, node, () -> agent.executeProxyCommand(command).join(), (unused, ignored) -> ctx.setOutput(node, "executed", true));
    }

    private void nodeMode(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        String nodeId = ctx.getInputValue(node, "node_id", String.class, agent.nodeId());
        String mode = ctx.getInputValue(node, "mode", String.class, "ONLINE");
        NetworkNodeStatus status;
        try {
            status = NetworkNodeStatus.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Network Node Mode Is Invalid");
        }
        async(ctx, node, () -> agent.setNodeMode(nodeId, status).join(), (unused, ignored) -> ctx.setOutput(node, "applied", true));
    }

    private void playerRoute(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        Player player = ctx.getPlayerInput(node, "player");
        if (player == null) {
            throw new IllegalArgumentException("Network Player Is Required");
        }
        String routeName = ctx.getInputValue(node, "server", String.class, "");
        ctx.runAsync(() -> {
            try {
                NetworkPlayerRouteResult result = agent.routePlayer(player.getUniqueId(), routeName).join();
                ctx.runSync(() -> {
                    ctx.setOutput(node, "transferred", result.successful());
                    ctx.setOutput(node, "status", result.status().name());
                    ctx.setOutput(node, "server", reference("network_route", result.routeName(), result.successful(), Map.of("status", result.status().name())));
                    ctx.setOutput(node, "transfer_result", transferResult("", player.getUniqueId(), "", "", result));
                    ctx.setOutput(node, "success", result.successful());
                    ctx.setOutput(node, "error", result.successful() ? "" : result.status().name());
                    ctx.triggerOutput(result.successful() ? "flow" : "failed");
                });
            } catch (RuntimeException exception) {
                ctx.runSync(() -> fail(ctx, node, rootMessage(exception)));
            }
        });
    }

    private void playerHandoff(FlowContext ctx, FlowNode node) {
        ReSyncNetworkAgent agent = requireAgent();
        Player player = ctx.getPlayerInput(node, "player");
        if (player == null) {
            throw new IllegalArgumentException("Network Player Is Required");
        }
        UUID playerId = player.getUniqueId();
        String targetNodeId = ctx.getInputValue(node, "target_node", String.class, "");
        String routeName = ctx.getInputValue(node, "server", String.class, "");
        long timeoutSeconds = Math.clamp(number(ctx.getInputValue(node, "timeout_seconds"), 30), 5, 120);
        String transferId = UUID.randomUUID().toString();
        NetworkTransferIntent intent = new NetworkTransferIntent(transferId, playerId, agent.nodeId(), targetNodeId, Instant.now().plusSeconds(timeoutSeconds).toEpochMilli());
        ctx.runAsync(() -> {
            try {
                var transfer = agent.beginTransfer(intent).thenCompose(leased -> agent.awaitTargetReady(leased.transferId())).join();
                NetworkPlayerRouteResult result = agent.routePlayer(playerId, routeName).join();
                ctx.runSync(() -> {
                    ctx.setOutput(node, "transfer_id", transfer.transferId());
                    ctx.setOutput(node, "fence_epoch", transfer.fenceEpoch());
                    ctx.setOutput(node, "transferred", result.successful());
                    ctx.setOutput(node, "status", result.status().name());
                    ctx.setOutput(node, "server", reference("network_route", result.routeName(), result.successful(), Map.of("status", result.status().name())));
                    ctx.setOutput(node, "transfer_result", transferResult(transfer.transferId(), playerId, agent.nodeId(), targetNodeId, result));
                    ctx.setOutput(node, "success", result.successful());
                    ctx.setOutput(node, "error", result.successful() ? "" : result.status().name());
                    ctx.triggerOutput(result.successful() ? "flow" : "failed");
                });
            } catch (RuntimeException exception) {
                ctx.runSync(() -> fail(ctx, node, rootMessage(exception)));
            }
        });
    }

    private CompletableFuture<NetworkVariable> upsert(ReSyncNetworkAgent agent, NetworkVariableScope scope, String scopeId, String key, NetworkVariableType type, byte[] value, long expiresAt, int attempts) {
        return agent.getVariable(scope, scopeId, key).thenCompose(current -> agent.setVariable(new NetworkVariableMutation(scope, scopeId, key, type, value, current.map(NetworkVariable::revision).orElse(0L), expiresAt))).handle((stored, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(stored);
            }
            if (attempts <= 1 || !rootMessage(throwable).toLowerCase(Locale.ROOT).contains("revision")) {
                return CompletableFuture.<NetworkVariable>failedFuture(unwrap(throwable));
            }
            return upsert(agent, scope, scopeId, key, type, value, expiresAt, attempts - 1);
        }).thenCompose(future -> future);
    }

    private <T> void async(FlowContext ctx, FlowNode node, Supplier<T> operation, BiConsumer<T, FlowNode> success) {
        ctx.runAsync(() -> {
            try {
                T result = operation.get();
                ctx.runSync(() -> {
                    if (result instanceof NetworkVariable variable) {
                        ctx.setOutput(node, "variable", variable(variable));
                        ctx.setOutput(node, "value", NetworkFlowValues.decode(variable));
                        ctx.setOutput(node, "type", variable.type().name());
                        ctx.setOutput(node, "revision", variable.revision());
                        ctx.setOutput(node, "expires_at", variable.expiresAt());
                    }
                    success.accept(result, node);
                    ctx.setOutput(node, "success", true);
                    ctx.setOutput(node, "error", "");
                    ctx.triggerOutput("flow");
                });
            } catch (RuntimeException exception) {
                ctx.runSync(() -> fail(ctx, node, rootMessage(exception)));
            }
        });
    }

    private void fail(FlowContext ctx, FlowNode node, String error) {
        ctx.setOutput(node, "success", false);
        ctx.setOutput(node, "error", error == null ? "Network Flow Operation Failed" : error);
        ctx.triggerOutput("failed");
    }

    private FlowResourceReference presence(NetworkNodePresence presence) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", presence.status().name());
        metadata.put("players", presence.players());
        metadata.put("capacity", presence.capacity());
        metadata.put("tps", presence.tps());
        metadata.put("mspt", presence.mspt());
        metadata.put("heapUsed", presence.heapUsed());
        metadata.put("heapMaximum", presence.heapMaximum());
        metadata.put("observedAt", presence.observedAt());
        return reference("network_node", presence.nodeId(), true, metadata);
    }

    private Map<String, Object> variable(NetworkVariable variable) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("networkId", variable.networkId());
        value.put("scope", variable.scope().name());
        value.put("scopeId", variable.scopeId());
        value.put("key", variable.key());
        value.put("type", variable.type().name());
        value.put("value", NetworkFlowValues.decode(variable));
        value.put("revision", variable.revision());
        value.put("expiresAt", variable.expiresAt());
        value.put("originNodeId", variable.originNodeId());
        value.put("updatedAt", variable.updatedAt());
        return value;
    }

    private Map<String, Object> transferResult(String transferId, UUID playerId, String sourceNodeId, String targetNodeId,
                                                NetworkPlayerRouteResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("transferId", transferId);
        value.put("playerId", playerId.toString());
        value.put("sourceNodeId", sourceNodeId);
        value.put("targetNodeId", targetNodeId);
        value.put("route", result.routeName());
        value.put("status", result.status().name());
        value.put("successful", result.successful());
        return value;
    }

    private FlowResourceReference reference(String kind, String id, boolean available, Map<String, Object> metadata) {
        return new FlowResourceReference(kind, id, "network", available, metadata);
    }

    private ReSyncNetworkAgent requireAgent() {
        ReSyncNetworkAgent agent = agent();
        if (agent == null || !agent.connected()) {
            throw new IllegalStateException("ReSync Network Is Not Connected");
        }
        return agent;
    }

    private ReSyncNetworkAgent agent() {
        ReSync plugin = ReSync.getInstance();
        return plugin == null ? null : plugin.getNetworkAgent();
    }

    private NetworkVariableScope scope(String value) {
        try {
            return NetworkVariableScope.valueOf(value.trim().replace(' ', '_').toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Network Variable Scope Is Invalid");
        }
    }

    private long expiry(Object value) {
        long seconds = number(value, 0);
        return seconds <= 0 ? 0 : Instant.now().plusSeconds(seconds).toEpochMilli();
    }

    private long number(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = unwrap(throwable);
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
