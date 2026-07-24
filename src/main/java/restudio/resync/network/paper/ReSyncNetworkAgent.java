package restudio.resync.network.paper;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.network.NetworkChannels;
import restudio.resync.network.NetworkEvent;
import restudio.resync.network.NetworkEventCodec;
import restudio.resync.network.NetworkEventPublish;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameCodec;
import restudio.resync.network.NetworkFrameType;
import restudio.resync.network.NetworkNodeMetrics;
import restudio.resync.network.NetworkNodeMode;
import restudio.resync.network.NetworkNodeModeCodec;
import restudio.resync.network.NetworkNodePresence;
import restudio.resync.network.NetworkNodePresenceCodec;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.NetworkOwnershipCodec;
import restudio.resync.network.NetworkPresenceCodec;
import restudio.resync.network.NetworkPlayerRoute;
import restudio.resync.network.NetworkPlayerRouteCodec;
import restudio.resync.network.NetworkPlayerRouteResult;
import restudio.resync.network.NetworkProxyAction;
import restudio.resync.network.NetworkProxyActionCodec;
import restudio.resync.network.NetworkProxyActionType;
import restudio.resync.network.NetworkRequestContext;
import restudio.resync.network.NetworkResource;
import restudio.resync.network.NetworkResourceCodec;
import restudio.resync.network.NetworkResourceKey;
import restudio.resync.network.NetworkResourceMutation;
import restudio.resync.network.NetworkResourcePage;
import restudio.resync.network.NetworkResourceQuery;
import restudio.resync.network.NetworkSnapshotChunk;
import restudio.resync.network.NetworkStateReconciliationCodec;
import restudio.resync.network.NetworkStateReconciliationTask;
import restudio.resync.network.NetworkTransferCheckpoint;
import restudio.resync.network.NetworkTransferCodec;
import restudio.resync.network.NetworkTransferIntent;
import restudio.resync.network.NetworkTransferStatus;
import restudio.resync.network.NetworkVariable;
import restudio.resync.network.NetworkVariableCodec;
import restudio.resync.network.NetworkVariableMutation;
import restudio.resync.network.NetworkVariableQuery;
import restudio.resync.network.NetworkVariableScope;
import restudio.resync.network.PlayerStateSnapshot;
import restudio.resync.network.PlayerLease;
import restudio.resync.network.PlayerTransfer;
import restudio.resync.network.paper.state.NetworkPlayerStateReconciler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ReSyncNetworkAgent {
    private static final int PROTOCOL_VERSION = 1;
    private static final long REQUEST_TIMEOUT_SECONDS = 10;
    private final ReSync plugin;
    private final ReSyncNetworkAgentConfig config;
    private final NetworkFrameCodec codec;
    private final NetworkPlayerStateReconciler stateReconciler;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean reconnectRequested = new AtomicBoolean();
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<String, CompletableFuture<NetworkFrame>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, NetworkNodePresence> presence = new ConcurrentHashMap<>();
    private final Map<String, PlayerTransfer> activeTransfers = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerLease> ownership = new ConcurrentHashMap<>();
    private final Map<String, PlayerStateSnapshot> transferSnapshots = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PlayerTransfer>> transferReadiness = new ConcurrentHashMap<>();
    private final Map<String, SnapshotAssembly> incomingSnapshots = new ConcurrentHashMap<>();
    private final Set<String> transferWork = ConcurrentHashMap.newKeySet();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile Client client;
    private volatile boolean authorized;
    private volatile String credential;
    private volatile TransferHandler transferHandler;
    private BukkitTask heartbeatTask;

    public ReSyncNetworkAgent(ReSync plugin, ReSyncNetworkAgentConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.codec = new NetworkFrameCodec(config.maximumFrameBytes(), config.maximumPayloadBytes());
        this.stateReconciler = new NetworkPlayerStateReconciler(plugin);
        this.credential = config.credential();
    }

    public void start() {
        if (!config.enabled()) {
            return;
        }
        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sendPresence, config.heartbeatIntervalTicks(), config.heartbeatIntervalTicks());
        connect();
    }

    public void shutdown() {
        stopping.set(true);
        authorized = false;
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        failPending(new IllegalStateException("ReSync Network Agent Stopped"));
        Client current = client;
        client = null;
        if (current != null) {
            current.close();
        }
        listeners.clear();
        presence.clear();
        activeTransfers.clear();
        ownership.clear();
        transferSnapshots.clear();
        incomingSnapshots.clear();
        transferWork.clear();
        stateReconciler.shutdown();
        transferReadiness.values().forEach(future -> future.completeExceptionally(new IllegalStateException("ReSync Network Agent Stopped")));
        transferReadiness.clear();
    }

    public boolean connected() {
        Client current = client;
        return authorized && current != null && current.isOpen();
    }

    public boolean hasActiveTransfers() {
        return !activeTransfers.isEmpty() || !transferWork.isEmpty();
    }

    public void reconnect() {
        if (stopping.get()) return;
        reconnectRequested.set(true);
        authorized = false;
        Client current = client;
        if (current != null && current.isOpen()) {
            current.close(1000, "Network Configuration Reloaded");
            return;
        }
        reconnectRequested.set(false);
        Bukkit.getScheduler().runTask(plugin, this::connect);
    }

    public String networkId() {
        return config.networkId();
    }

    public String nodeId() {
        return config.nodeId();
    }

    public Map<String, NetworkNodePresence> presenceSnapshot() {
        return Map.copyOf(presence);
    }

    public Optional<PlayerLease> ownership(UUID playerId) {
        return Optional.ofNullable(ownership.get(playerId));
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setTransferHandler(TransferHandler transferHandler) {
        this.transferHandler = transferHandler;
        if (transferHandler != null) {
            activeTransfers.values().forEach(this::resumeTransfer);
        }
    }

    public CompletableFuture<Optional<NetworkVariable>> getVariable(NetworkVariableScope scope, String scopeId, String key) {
        return request(NetworkChannels.VARIABLES, NetworkFrameType.VARIABLE_GET, NetworkVariableCodec.encodeQuery(new NetworkVariableQuery(scope, scopeId, key)), Set.of("variables.read")).thenApply(frame -> frame.payload().length == 0 ? Optional.empty() : Optional.of(NetworkVariableCodec.decodeVariable(frame.payload())));
    }

    public CompletableFuture<NetworkVariable> setVariable(NetworkVariableMutation mutation) {
        return request(NetworkChannels.VARIABLES, NetworkFrameType.VARIABLE_SET, NetworkVariableCodec.encodeMutation(mutation), Set.of("variables.write")).thenApply(frame -> NetworkVariableCodec.decodeVariable(frame.payload()));
    }

    public CompletableFuture<NetworkEvent> publishEvent(NetworkEventPublish event) {
        return request(NetworkChannels.EVENTS, NetworkFrameType.EVENT_PUBLISH, NetworkEventCodec.encodePublish(event), Set.of("events.publish")).thenApply(frame -> NetworkEventCodec.decodeEvent(frame.payload()));
    }

    public CompletableFuture<Optional<NetworkResource>> getResource(String type, String resourceId) {
        NetworkResourceKey key = new NetworkResourceKey(type, resourceId);
        return request(NetworkChannels.RESOURCES, NetworkFrameType.RESOURCE_GET, NetworkResourceCodec.encodeKey(key), Set.of("resources.read")).thenApply(frame -> frame.payload().length == 0 ? Optional.empty() : Optional.of(NetworkResourceCodec.decodeResource(frame.payload())));
    }

    public CompletableFuture<NetworkResourcePage> listResources(NetworkResourceQuery query) {
        return request(NetworkChannels.RESOURCES, NetworkFrameType.RESOURCE_LIST, NetworkResourceCodec.encodeQuery(query), Set.of("resources.read")).thenApply(frame -> NetworkResourceCodec.decodePage(frame.payload()));
    }

    public CompletableFuture<NetworkResource> setResource(NetworkResourceMutation mutation) {
        return request(NetworkChannels.RESOURCES, NetworkFrameType.RESOURCE_SET, NetworkResourceCodec.encodeMutation(mutation), Set.of("resources.write")).thenApply(frame -> NetworkResourceCodec.decodeResource(frame.payload()));
    }

    public CompletableFuture<Void> setNodeMode(String nodeId, NetworkNodeStatus status) {
        return request(NetworkChannels.CONTROL, NetworkFrameType.NODE_MODE_SET, NetworkNodeModeCodec.encode(new NetworkNodeMode(nodeId, status)), Set.of("nodes.manage")).thenApply(frame -> null);
    }

    public CompletableFuture<NetworkPlayerRouteResult> routePlayer(UUID playerId, String routeName) {
        return request(NetworkChannels.TRANSFER, NetworkFrameType.PLAYER_ROUTE, NetworkPlayerRouteCodec.encode(new NetworkPlayerRoute(playerId, routeName)), Set.of("players.route")).thenApply(frame -> NetworkPlayerRouteCodec.decodeResult(frame.payload()));
    }

    public CompletableFuture<PlayerTransfer> beginTransfer(NetworkTransferIntent intent) {
        if (transferHandler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("ReSync Player State Transfer Is Not Enabled"));
        }
        return request(NetworkChannels.TRANSFER, NetworkFrameType.TRANSFER_INTENT, NetworkTransferCodec.encodeIntent(intent), Set.of("state.transfer")).thenApply(frame -> NetworkTransferCodec.decodeTransfer(frame.payload())).thenApply(transfer -> {
            activeTransfers.put(transfer.transferId(), transfer);
            captureTransfer(transfer);
            return transfer;
        });
    }

    public CompletableFuture<PlayerTransfer> awaitTargetReady(String transferId) {
        PlayerTransfer current = activeTransfers.get(transferId);
        if (current != null && current.status().ordinal() >= NetworkTransferStatus.TARGET_READY.ordinal() && current.status() != NetworkTransferStatus.ABORTED && current.status() != NetworkTransferStatus.TIMED_OUT) {
            return CompletableFuture.completedFuture(current);
        }
        CompletableFuture<PlayerTransfer> readiness = transferReadiness.computeIfAbsent(transferId, ignored -> new CompletableFuture<>());
        return readiness.orTimeout(2, TimeUnit.MINUTES).whenComplete((transfer, throwable) -> transferReadiness.remove(transferId, readiness));
    }

    public CompletableFuture<PlayerTransfer> commitSnapshot(String transferId, PlayerStateSnapshot snapshot) {
        CompletableFuture<PlayerTransfer> result = CompletableFuture.completedFuture(null);
        for (NetworkSnapshotChunk chunk : NetworkTransferCodec.split(transferId, snapshot)) {
            result = result.thenCompose(previous -> request(NetworkChannels.TRANSFER, NetworkFrameType.SNAPSHOT_COMMIT, NetworkTransferCodec.encodeChunk(chunk), Set.of("state.transfer")).thenApply(frame -> NetworkTransferCodec.decodeTransfer(frame.payload())));
        }
        return result.thenApply(transfer -> {
            if (transfer == null) {
                throw new IllegalStateException("Network Snapshot Had No Transfer Response");
            }
            activeTransfers.put(transfer.transferId(), transfer);
            return transfer;
        });
    }

    public CompletableFuture<PlayerTransfer> markTargetReady(String transferId, String snapshotId) {
        return transferCheckpoint(NetworkFrameType.TARGET_READY, NetworkTransferCheckpoint.snapshot(transferId, snapshotId));
    }

    public CompletableFuture<PlayerTransfer> acknowledgeStateApplied(String transferId, String snapshotId) {
        return transferCheckpoint(NetworkFrameType.STATE_APPLIED, NetworkTransferCheckpoint.snapshot(transferId, snapshotId));
    }

    public CompletableFuture<PlayerTransfer> abortTransfer(String transferId, String failure) {
        return transferCheckpoint(NetworkFrameType.TRANSFER_ABORT, NetworkTransferCheckpoint.abort(transferId, failure));
    }

    public CompletableFuture<PlayerLease> saveOwnerSnapshot(PlayerStateSnapshot snapshot) {
        CompletableFuture<PlayerLease> result = CompletableFuture.completedFuture(null);
        for (NetworkSnapshotChunk chunk : NetworkTransferCodec.split("owner:" + snapshot.snapshotId(), snapshot)) {
            result = result.thenCompose(previous -> request(NetworkChannels.STATE, NetworkFrameType.OWNER_SNAPSHOT, NetworkTransferCodec.encodeChunk(chunk), Set.of("state.transfer")).thenApply(frame -> NetworkOwnershipCodec.decode(frame.payload())));
        }
        return result.thenApply(lease -> {
            if (lease == null) {
                throw new IllegalStateException("Owned Snapshot Had No Lease Response");
            }
            ownership.put(lease.playerId(), lease);
            return lease;
        });
    }

    private CompletableFuture<PlayerTransfer> transferCheckpoint(NetworkFrameType type, NetworkTransferCheckpoint checkpoint) {
        return request(NetworkChannels.TRANSFER, type, NetworkTransferCodec.encodeCheckpoint(checkpoint), Set.of("state.transfer")).thenApply(frame -> NetworkTransferCodec.decodeTransfer(frame.payload())).thenApply(transfer -> {
            activeTransfers.put(transfer.transferId(), transfer);
            return transfer;
        });
    }

    public CompletableFuture<Void> executeProxyCommand(String command) {
        return proxyAction(new NetworkProxyAction(NetworkProxyActionType.COMMAND, command), "proxy.command");
    }

    public CompletableFuture<Void> broadcast(String message) {
        return proxyAction(new NetworkProxyAction(NetworkProxyActionType.BROADCAST, message), "proxy.broadcast");
    }

    private CompletableFuture<Void> proxyAction(NetworkProxyAction action, String scope) {
        return request(NetworkChannels.CONTROL, NetworkFrameType.PROXY_ACTION, NetworkProxyActionCodec.encode(action), Set.of(scope)).thenApply(frame -> null);
    }

    private void connect() {
        if (stopping.get()) {
            return;
        }
        reconnectScheduled.set(false);
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-ReSync-Network", config.networkId());
            headers.put("X-ReSync-Node", config.nodeId());
            if (credential.isBlank()) {
                headers.put("X-ReSync-Enrollment", config.enrollmentToken());
            } else {
                headers.put("X-ReSync-Credential", credential);
            }
            Client next = new Client(URI.create(config.hubUrl()), headers);
            if (config.tls().enabled()) {
                next.setSocketFactory(ReSyncNetworkTls.create(config.tls()).getSocketFactory());
            }
            client = next;
            next.connect();
        } catch (Exception exception) {
            Log.warn("ReSync network connection failed: " + rootMessage(exception));
            scheduleReconnect();
        }
    }

    private void sendPresence() {
        Client current = client;
        if (!authorized || current == null || !current.isOpen()) {
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        double[] tps = Bukkit.getTPS();
        double currentTps = tps.length == 0 ? -1 : tps[0];
        NetworkNodeMetrics metrics = new NetworkNodeMetrics(config.networkId(), config.nodeId(), Bukkit.getOnlinePlayers().size(), config.capacity() > 0 ? config.capacity() : Bukkit.getMaxPlayers(), currentTps, Bukkit.getAverageTickTime(), runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), Instant.now().toEpochMilli());
        send(current, NetworkChannels.PRESENCE, NetworkFrameType.PRESENCE_DELTA, nextRequestId(), NetworkPresenceCodec.encode(metrics), Set.of("node.heartbeat", "presence.write"));
    }

    private CompletableFuture<NetworkFrame> request(String channel, NetworkFrameType type, byte[] payload, Set<String> scopes) {
        Client current = client;
        if (!authorized || current == null || !current.isOpen()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ReSync Network Is Not Connected"));
        }
        String requestId = nextRequestId();
        CompletableFuture<NetworkFrame> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        try {
            send(current, channel, type, requestId, payload, scopes);
        } catch (RuntimeException exception) {
            pendingRequests.remove(requestId, future);
            future.completeExceptionally(exception);
            return future;
        }
        return future.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((frame, throwable) -> pendingRequests.remove(requestId, future));
    }

    private void send(Client target, String channel, NetworkFrameType type, String requestId, byte[] payload, Set<String> scopes) {
        NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, config.networkId(), config.nodeId(), requestId, Instant.now().plusSeconds(REQUEST_TIMEOUT_SECONDS).toEpochMilli(), scopes);
        target.send(codec.encode(new NetworkFrame(context, channel, type, payload)));
    }

    private String nextRequestId() {
        return config.nodeId() + "-" + requestIds.incrementAndGet();
    }

    private void handle(NetworkFrame frame) {
        if (!frame.context().networkId().equals(config.networkId())) {
            throw new SecurityException("Network Hub Identity Does Not Match");
        }
        if (frame.type() == NetworkFrameType.ENROLL_ACK) {
            String issuedCredential = new String(frame.payload(), StandardCharsets.UTF_8).trim();
            try {
                config.saveCredential(issuedCredential);
            } catch (Exception exception) {
                throw new IllegalStateException("Persist Network Credential Failed", exception);
            }
            credential = issuedCredential;
            authorized = true;
            Log.info("ReSync network node enrolled as " + config.nodeId());
            sendPresence();
            notifyConnected();
            return;
        }
        if (frame.type() == NetworkFrameType.RESPONSE && frame.context().requestId().equals("session")) {
            authorized = true;
            Log.info("ReSync network node connected as " + config.nodeId());
            sendPresence();
            notifyConnected();
            return;
        }
        if (frame.type() == NetworkFrameType.ERROR) {
            String message = new String(frame.payload(), StandardCharsets.UTF_8);
            CompletableFuture<NetworkFrame> future = pendingRequests.remove(frame.context().requestId());
            if (future != null) {
                future.completeExceptionally(new IllegalStateException(message));
            } else {
                Log.warn("ReSync network hub rejected a request: " + message);
            }
            return;
        }
        if (frame.type() == NetworkFrameType.STATE_RECONCILE && frame.channel().equals(NetworkChannels.STATE)) {
            NetworkStateReconciliationTask task = NetworkStateReconciliationCodec.decodeTask(frame.payload());
            stateReconciler.reconcile(task).whenComplete((unused, throwable) -> respondToHub(frame, throwable));
            return;
        }
        CompletableFuture<NetworkFrame> future = pendingRequests.remove(frame.context().requestId());
        if (future != null) {
            future.complete(frame);
            return;
        }
        if ((frame.type() == NetworkFrameType.PRESENCE_SNAPSHOT || frame.type() == NetworkFrameType.PRESENCE_DELTA) && frame.channel().equals(NetworkChannels.PRESENCE)) {
            NetworkNodePresence observation = NetworkNodePresenceCodec.decode(config.networkId(), frame.payload());
            presence.put(observation.nodeId(), observation);
            listeners.forEach(listener -> listener.onPresenceChanged(observation));
            return;
        }
        if (frame.type() == NetworkFrameType.VARIABLE_CHANGED && frame.channel().equals(NetworkChannels.VARIABLES)) {
            NetworkVariable variable = NetworkVariableCodec.decodeVariable(frame.payload());
            listeners.forEach(listener -> listener.onVariableChanged(variable));
            return;
        }
        if (frame.type() == NetworkFrameType.RESOURCE_CHANGED && frame.channel().equals(NetworkChannels.RESOURCES)) {
            NetworkResource resource = NetworkResourceCodec.decodeResource(frame.payload());
            listeners.forEach(listener -> listener.onResourceChanged(resource));
            return;
        }
        if (frame.type() == NetworkFrameType.EVENT_DELIVERY && frame.channel().equals(NetworkChannels.EVENTS)) {
            deliverEvent(NetworkEventCodec.decodeEvent(frame.payload()));
            return;
        }
        if (frame.channel().equals(NetworkChannels.TRANSFER)) {
            handleTransfer(frame);
            return;
        }
        if (frame.type() == NetworkFrameType.OWNER_CLAIM && frame.channel().equals(NetworkChannels.STATE)) {
            PlayerLease lease = NetworkOwnershipCodec.decode(frame.payload());
            ownership.put(lease.playerId(), lease);
            TransferHandler handler = transferHandler;
            if (handler != null) {
                try {
                    handler.ownershipChanged(lease);
                } catch (RuntimeException exception) {
                    Log.warn("ReSync player ownership callback failed: " + rootMessage(exception));
                }
            }
        }
    }

    private void respondToHub(NetworkFrame request, Throwable throwable) {
        Client current = client;
        if (!authorized || current == null || !current.isOpen()) {
            return;
        }
        if (throwable == null) {
            send(current, request.channel(), NetworkFrameType.RESPONSE, request.context().requestId(), new byte[0], Set.of("state.reconcile"));
        } else {
            send(current, request.channel(), NetworkFrameType.ERROR, request.context().requestId(), rootMessage(throwable).getBytes(StandardCharsets.UTF_8), Set.of("state.reconcile"));
        }
    }

    private void handleTransfer(NetworkFrame frame) {
        if (frame.type() == NetworkFrameType.LEASE_GRANTED || frame.type() == NetworkFrameType.TARGET_READY || frame.type() == NetworkFrameType.TRANSFER_RECOVER || frame.type() == NetworkFrameType.PLAYER_CONNECTED || frame.type() == NetworkFrameType.TRANSFER_COMMIT || frame.type() == NetworkFrameType.TRANSFER_ABORT) {
            PlayerTransfer transfer = NetworkTransferCodec.decodeTransfer(frame.payload());
            activeTransfers.put(transfer.transferId(), transfer);
            if (frame.type() == NetworkFrameType.LEASE_GRANTED) {
                captureTransfer(transfer);
            } else if (frame.type() == NetworkFrameType.TARGET_READY) {
                CompletableFuture<PlayerTransfer> readiness = transferReadiness.remove(transfer.transferId());
                if (readiness != null) {
                    readiness.complete(transfer);
                }
            } else if (frame.type() == NetworkFrameType.TRANSFER_RECOVER) {
                resumeTransfer(transfer);
            } else if (frame.type() == NetworkFrameType.PLAYER_CONNECTED) {
                applyTransfer(transfer);
            } else if (frame.type() == NetworkFrameType.TRANSFER_COMMIT) {
                finishTransfer(transfer, false);
            } else {
                finishTransfer(transfer, true);
            }
            return;
        }
        if (frame.type() == NetworkFrameType.SNAPSHOT_COMMIT) {
            receiveSnapshot(NetworkTransferCodec.decodeChunk(frame.payload()));
        }
    }

    private void resumeTransfer(PlayerTransfer transfer) {
        TransferHandler handler = transferHandler;
        if (handler != null) {
            try {
                handler.recovering(transfer, transfer.sourceNodeId().equals(config.nodeId()));
            } catch (RuntimeException exception) {
                Log.warn("ReSync player transfer recovery callback failed: " + rootMessage(exception));
            }
        }
        if (transfer.sourceNodeId().equals(config.nodeId()) && transfer.status() == NetworkTransferStatus.SOURCE_LEASED) {
            captureTransfer(transfer);
        }
        if (transfer.targetNodeId().equals(config.nodeId())) {
            if (transfer.status().ordinal() >= NetworkTransferStatus.SNAPSHOT_COMMITTED.ordinal()) {
                prepareTransfer(transfer);
            }
            if (transfer.status().ordinal() >= NetworkTransferStatus.CONNECTED.ordinal()) {
                applyTransfer(transfer);
            }
        }
    }

    private void captureTransfer(PlayerTransfer transfer) {
        TransferHandler handler = transferHandler;
        String workId = transfer.transferId() + ":capture";
        if (handler == null || !transfer.sourceNodeId().equals(config.nodeId()) || transfer.status() != NetworkTransferStatus.SOURCE_LEASED || !transferWork.add(workId)) {
            return;
        }
        try {
            CompletionStage<PlayerStateSnapshot> capture = handler.capture(transfer);
            if (capture == null) {
                throw new IllegalStateException("Transfer Capture Did Not Return A Result");
            }
            capture.toCompletableFuture().thenCompose(snapshot -> commitSnapshot(transfer.transferId(), snapshot)).whenComplete((committed, throwable) -> {
                transferWork.remove(workId);
                if (throwable != null) {
                    failTransfer(transfer, "SOURCE_CAPTURE_FAILED", throwable);
                }
            });
        } catch (RuntimeException exception) {
            transferWork.remove(workId);
            failTransfer(transfer, "SOURCE_CAPTURE_FAILED", exception);
        }
    }

    private void receiveSnapshot(NetworkSnapshotChunk chunk) {
        if (!chunk.networkId().equals(config.networkId())) {
            throw new SecurityException("Transfer Snapshot Network Does Not Match");
        }
        PlayerTransfer active = activeTransfers.get(chunk.transferId());
        if (active == null) {
            return;
        }
        if (!active.playerId().equals(chunk.playerId()) || active.fenceEpoch() != chunk.fenceEpoch() || !active.snapshotId().equals(chunk.snapshotId())) {
            throw new SecurityException("Transfer Snapshot Does Not Match The Active Lease");
        }
        SnapshotAssembly assembly = incomingSnapshots.computeIfAbsent(chunk.transferId(), ignored -> new SnapshotAssembly(chunk));
        if (!assembly.add(chunk)) {
            return;
        }
        incomingSnapshots.remove(chunk.transferId(), assembly);
        PlayerStateSnapshot snapshot = NetworkTransferCodec.assemble(assembly.chunks());
        transferSnapshots.put(chunk.transferId(), snapshot);
        PlayerTransfer transfer = activeTransfers.get(chunk.transferId());
        if (transfer != null) {
            prepareTransfer(transfer);
        }
    }

    private void prepareTransfer(PlayerTransfer transfer) {
        TransferHandler handler = transferHandler;
        PlayerStateSnapshot snapshot = transferSnapshots.get(transfer.transferId());
        String workId = transfer.transferId() + ":prepare";
        if (handler == null || snapshot == null || !transfer.targetNodeId().equals(config.nodeId()) || transfer.status().ordinal() < NetworkTransferStatus.SNAPSHOT_COMMITTED.ordinal() || transfer.status().ordinal() >= NetworkTransferStatus.TARGET_READY.ordinal() || !transferWork.add(workId)) {
            return;
        }
        try {
            CompletionStage<Void> preparation = handler.prepare(transfer, snapshot);
            if (preparation == null) {
                throw new IllegalStateException("Transfer Preparation Did Not Return A Result");
            }
            preparation.toCompletableFuture().thenCompose(unused -> markTargetReady(transfer.transferId(), snapshot.snapshotId())).whenComplete((ready, throwable) -> {
                transferWork.remove(workId);
                if (throwable != null) {
                    failTransfer(transfer, "TARGET_PREPARATION_FAILED", throwable);
                }
            });
        } catch (RuntimeException exception) {
            transferWork.remove(workId);
            failTransfer(transfer, "TARGET_PREPARATION_FAILED", exception);
        }
    }

    private void applyTransfer(PlayerTransfer transfer) {
        TransferHandler handler = transferHandler;
        PlayerStateSnapshot snapshot = transferSnapshots.get(transfer.transferId());
        String workId = transfer.transferId() + ":apply";
        if (handler == null || snapshot == null || !transfer.targetNodeId().equals(config.nodeId()) || transfer.status().ordinal() < NetworkTransferStatus.CONNECTED.ordinal() || transfer.status().ordinal() >= NetworkTransferStatus.APPLIED.ordinal() || !transferWork.add(workId)) {
            return;
        }
        try {
            CompletionStage<Void> application = handler.apply(transfer, snapshot);
            if (application == null) {
                throw new IllegalStateException("Transfer Apply Did Not Return A Result");
            }
            application.toCompletableFuture().thenCompose(unused -> acknowledgeStateApplied(transfer.transferId(), snapshot.snapshotId())).whenComplete((committed, throwable) -> {
                transferWork.remove(workId);
                if (throwable != null) {
                    failTransfer(transfer, "TARGET_APPLY_FAILED", throwable);
                } else {
                    finishTransfer(committed, false);
                }
            });
        } catch (RuntimeException exception) {
            transferWork.remove(workId);
            failTransfer(transfer, "TARGET_APPLY_FAILED", exception);
        }
    }

    private void failTransfer(PlayerTransfer transfer, String failure, Throwable throwable) {
        Log.warn("ReSync player transfer " + transfer.transferId() + " failed: " + rootMessage(throwable));
        abortTransfer(transfer.transferId(), failure).thenAccept(aborted -> finishTransfer(aborted, true)).exceptionally(abortFailure -> {
            Log.warn("ReSync player transfer abort failed: " + rootMessage(abortFailure));
            return null;
        });
    }

    private void finishTransfer(PlayerTransfer transfer, boolean aborted) {
        PlayerStateSnapshot snapshot = transferSnapshots.get(transfer.transferId());
        activeTransfers.remove(transfer.transferId());
        transferSnapshots.remove(transfer.transferId());
        incomingSnapshots.remove(transfer.transferId());
        transferWork.removeIf(value -> value.startsWith(transfer.transferId() + ":"));
        CompletableFuture<PlayerTransfer> readiness = transferReadiness.remove(transfer.transferId());
        if (readiness != null) {
            if (aborted) {
                readiness.completeExceptionally(new IllegalStateException(transfer.failure().isBlank() ? "Player Transfer Aborted" : transfer.failure()));
            } else {
                readiness.complete(transfer);
            }
        }
        TransferHandler handler = transferHandler;
        if (handler == null) {
            return;
        }
        try {
            if ((!aborted && transfer.targetNodeId().equals(config.nodeId())) || (aborted && transfer.sourceNodeId().equals(config.nodeId()))) {
                String ownerNodeId = aborted ? transfer.sourceNodeId() : transfer.targetNodeId();
                PlayerLease lease = new PlayerLease(transfer.networkId(), transfer.playerId(), ownerNodeId, "", transfer.fenceEpoch(), 0, transfer.updatedAt());
                ownership.put(lease.playerId(), lease);
                handler.ownershipChanged(lease);
            }
            if (aborted) {
                handler.aborted(transfer, snapshot);
            } else {
                handler.committed(transfer);
            }
        } catch (RuntimeException exception) {
            Log.warn("ReSync player transfer completion callback failed: " + rootMessage(exception));
        }
    }

    private void deliverEvent(NetworkEvent event) {
        List<CompletableFuture<Void>> deliveries = new ArrayList<>();
        for (Listener listener : listeners) {
            try {
                CompletionStage<Void> delivery = listener.onEventReceived(event);
                if (delivery != null) {
                    deliveries.add(delivery.toCompletableFuture());
                }
            } catch (RuntimeException exception) {
                deliveries.add(CompletableFuture.failedFuture(exception));
            }
        }
        if (deliveries.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(deliveries.toArray(new CompletableFuture[0])).thenCompose(unused -> acknowledgeEvent(event.eventId())).exceptionally(throwable -> {
            Log.warn("ReSync network event delivery failed: " + rootMessage(throwable));
            return null;
        });
    }

    private CompletableFuture<Void> acknowledgeEvent(String eventId) {
        return request(NetworkChannels.EVENTS, NetworkFrameType.EVENT_ACK, NetworkEventCodec.encodeAcknowledgement(eventId), Set.of("events.consume")).thenApply(frame -> null);
    }

    private void failPending(Throwable throwable) {
        pendingRequests.values().forEach(future -> future.completeExceptionally(throwable));
        pendingRequests.clear();
    }

    private void notifyConnected() {
        TransferHandler handler = transferHandler;
        if (handler != null) {
            try {
                handler.connected();
            } catch (RuntimeException exception) {
                Log.warn("ReSync network connection callback failed: " + rootMessage(exception));
            }
        }
        listeners.forEach(listener -> {
            try {
                listener.onConnected();
            } catch (RuntimeException exception) {
                Log.warn("ReSync network listener connection callback failed: " + rootMessage(exception));
            }
        });
    }

    private void scheduleReconnect() {
        if (stopping.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::connect, Math.max(20, config.reconnectDelayTicks()));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public interface Listener {
        default void onPresenceChanged(NetworkNodePresence presence) {
        }

        default void onVariableChanged(NetworkVariable variable) {
        }

        default CompletionStage<Void> onEventReceived(NetworkEvent event) {
            return CompletableFuture.completedFuture(null);
        }

        default void onResourceChanged(NetworkResource resource) {
        }

        default void onConnected() {
        }
    }

    public interface TransferHandler {
        CompletionStage<PlayerStateSnapshot> capture(PlayerTransfer transfer);

        CompletionStage<Void> prepare(PlayerTransfer transfer, PlayerStateSnapshot snapshot);

        CompletionStage<Void> apply(PlayerTransfer transfer, PlayerStateSnapshot snapshot);

        default void committed(PlayerTransfer transfer) {
        }

        default void recovering(PlayerTransfer transfer, boolean source) {
        }

        default void ownershipChanged(PlayerLease lease) {
        }

        default void connected() {
        }

        default void aborted(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        }
    }

    private static final class SnapshotAssembly {
        private final NetworkSnapshotChunk first;
        private final Map<Integer, NetworkSnapshotChunk> chunks = new LinkedHashMap<>();
        private int receivedBytes;

        private SnapshotAssembly(NetworkSnapshotChunk first) {
            this.first = first;
        }

        private synchronized boolean add(NetworkSnapshotChunk chunk) {
            if (!sameSnapshot(first, chunk)) {
                throw new IllegalArgumentException("Network Snapshot Chunk Set Is Inconsistent");
            }
            NetworkSnapshotChunk previous = chunks.putIfAbsent(chunk.chunkIndex(), chunk);
            if (previous != null && !previous.equals(chunk)) {
                throw new IllegalArgumentException("Network Snapshot Chunk Position Changed");
            }
            if (previous == null) {
                receivedBytes += chunk.payload().length;
            }
            if (receivedBytes > first.totalBytes()) {
                throw new IllegalArgumentException("Network Snapshot Chunk Set Is Too Large");
            }
            return chunks.size() == first.chunkCount() && receivedBytes == first.totalBytes();
        }

        private synchronized List<NetworkSnapshotChunk> chunks() {
            return List.copyOf(chunks.values());
        }

        private static boolean sameSnapshot(NetworkSnapshotChunk expected, NetworkSnapshotChunk actual) {
            return expected.transferId().equals(actual.transferId()) && expected.snapshotId().equals(actual.snapshotId()) && expected.networkId().equals(actual.networkId()) && expected.playerId().equals(actual.playerId()) && expected.fenceEpoch() == actual.fenceEpoch() && expected.family().equals(actual.family()) && expected.payloadHash().equalsIgnoreCase(actual.payloadHash()) && expected.schemaVersion() == actual.schemaVersion() && expected.dataVersion() == actual.dataVersion() && expected.originNodeId().equals(actual.originNodeId()) && expected.createdAt() == actual.createdAt() && expected.totalBytes() == actual.totalBytes() && expected.chunkCount() == actual.chunkCount();
        }
    }

    private final class Client extends WebSocketClient {
        private Client(URI uri, Map<String, String> headers) {
            super(uri, headers);
            setConnectionLostTimeout(15);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            authorized = false;
        }

        @Override
        public void onMessage(String message) {
            close(1003, "Binary Network Frames Required");
        }

        @Override
        public void onMessage(ByteBuffer message) {
            byte[] encoded = new byte[message.remaining()];
            message.get(encoded);
            try {
                handle(codec.decode(encoded));
            } catch (RuntimeException exception) {
                Log.warn("ReSync network frame failed: " + rootMessage(exception));
                close(1008, "Invalid Network Frame");
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            authorized = false;
            presence.clear();
            failPending(new IllegalStateException("ReSync Network Disconnected: " + reason));
            if (!stopping.get()) {
                if (reconnectRequested.compareAndSet(true, false)) {
                    Bukkit.getScheduler().runTask(plugin, ReSyncNetworkAgent.this::connect);
                    return;
                }
                if ("Network Credential Rejected".equals(reason) && !credential.isBlank() && !config.enrollmentToken().isBlank()) {
                    try {
                        config.clearCredential();
                        credential = "";
                        Log.warn("ReSync network credential was rejected; retrying enrollment");
                    } catch (Exception exception) {
                        Log.warn("ReSync network credential reset failed: " + rootMessage(exception));
                    }
                }
                Log.warn("ReSync network node disconnected: " + reason);
                scheduleReconnect();
            }
        }

        @Override
        public void onError(Exception exception) {
            if (!stopping.get()) {
                Log.warn("ReSync network transport error: " + rootMessage(exception));
            }
        }
    }
}
