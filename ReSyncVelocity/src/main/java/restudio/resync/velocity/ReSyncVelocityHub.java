package restudio.resync.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import restudio.resync.network.NetworkChannels;
import restudio.resync.network.NetworkCredentials;
import restudio.resync.network.NetworkEvent;
import restudio.resync.network.NetworkEventCodec;
import restudio.resync.network.NetworkEventPublish;
import restudio.resync.network.NetworkEventTopics;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameCodec;
import restudio.resync.network.NetworkFrameType;
import restudio.resync.network.NetworkNode;
import restudio.resync.network.NetworkNodeMetrics;
import restudio.resync.network.NetworkNodeMode;
import restudio.resync.network.NetworkNodeModeCodec;
import restudio.resync.network.NetworkNodePresence;
import restudio.resync.network.NetworkNodePresenceCodec;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.NetworkOwnershipCodec;
import restudio.resync.network.NetworkPresenceCodec;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.NetworkPlayerRoute;
import restudio.resync.network.NetworkPlayerRouteCodec;
import restudio.resync.network.NetworkPlayerRouteResult;
import restudio.resync.network.NetworkPlayerRouteStatus;
import restudio.resync.network.NetworkPlayerLifecycle;
import restudio.resync.network.NetworkPlayerLifecycleCodec;
import restudio.resync.network.NetworkProxyAction;
import restudio.resync.network.NetworkProxyActionCodec;
import restudio.resync.network.NetworkProxyActionType;
import restudio.resync.network.NetworkRequestContext;
import restudio.resync.network.NetworkResource;
import restudio.resync.network.NetworkResourceCodec;
import restudio.resync.network.NetworkResourceKey;
import restudio.resync.network.NetworkResourceMetadata;
import restudio.resync.network.NetworkResourceMutation;
import restudio.resync.network.NetworkResourceQuery;
import restudio.resync.network.NetworkRoute;
import restudio.resync.network.NetworkRouteSet;
import restudio.resync.network.NetworkRouteSetCodec;
import restudio.resync.network.NetworkSnapshotAdminCodec;
import restudio.resync.network.NetworkSnapshotChunk;
import restudio.resync.network.NetworkSnapshotMetadata;
import restudio.resync.network.NetworkSnapshotPin;
import restudio.resync.network.NetworkSnapshotQuery;
import restudio.resync.network.NetworkSnapshotRestore;
import restudio.resync.network.NetworkStateReconciliationCodec;
import restudio.resync.network.NetworkStateReconciliationRequest;
import restudio.resync.network.NetworkStateReconciliationTask;
import restudio.resync.network.NetworkTransferCheckpoint;
import restudio.resync.network.NetworkTransferCodec;
import restudio.resync.network.NetworkTransferIntent;
import restudio.resync.network.NetworkTransferStatus;
import restudio.resync.network.NetworkVariable;
import restudio.resync.network.NetworkVariableCodec;
import restudio.resync.network.NetworkVariableMutation;
import restudio.resync.network.NetworkVariableQuery;
import restudio.resync.network.PlayerStateSnapshot;
import restudio.resync.network.PlayerLease;
import restudio.resync.network.PlayerTransfer;
import restudio.resync.network.SqliteNetworkHubStore;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ReSyncVelocityHub extends WebSocketServer {
    private static final int PROTOCOL_VERSION = 1;
    private static final int EVENT_DELIVERY_BATCH = 100;
    private static final int MAXIMUM_ACTIVE_SNAPSHOT_UPLOADS = 32;
    private static final long MAXIMUM_EVENT_RETENTION_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final long MAXIMUM_TRANSFER_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final long MAXIMUM_SNAPSHOT_UPLOAD_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final long MAXIMUM_MANUAL_RESTORE_MILLIS = TimeUnit.MINUTES.toMillis(15);
    private static final int RECONCILIATION_BATCH_SIZE = 5_000;
    private final VelocityNetworkConfig config;
    private final Logger logger;
    private final ProxyServer proxyServer;
    private final SqliteNetworkHubStore store;
    private final NetworkFrameCodec codec;
    private final VelocityRouteRegistry routes;
    private final NetworkEventDeliveryService events;
    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> connectionsByNode = new ConcurrentHashMap<>();
    private final Map<String, NetworkNodeMetrics> latestMetrics = new ConcurrentHashMap<>();
    private final Map<String, VelocityNetworkConfig.EnrollmentNode> enrollmentNodes = new ConcurrentHashMap<>();
    private final Map<String, NetworkNodeStatus> nodeModes = new ConcurrentHashMap<>();
    private final SnapshotUploadRegistry snapshotUploads = new SnapshotUploadRegistry(MAXIMUM_ACTIVE_SNAPSHOT_UPLOADS, MAXIMUM_SNAPSHOT_UPLOAD_MILLIS);
    private final Map<String, CompletableFuture<PlayerTransfer>> transferReadiness = new ConcurrentHashMap<>();
    private final Map<String, PendingReconciliation> pendingReconciliations = new ConcurrentHashMap<>();
    private final List<Consumer<NetworkResource>> resourceListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong lifecycleOrder = new AtomicLong();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "resync-velocity-heartbeats");
        thread.setDaemon(true);
        return thread;
    });

    public ReSyncVelocityHub(VelocityNetworkConfig config, Logger logger, ProxyServer proxyServer) {
        super(new InetSocketAddress(config.bindHost(), config.port()));
        this.config = config;
        this.logger = logger;
        this.proxyServer = proxyServer;
        this.store = new SqliteNetworkHubStore(config.databasePath());
        this.codec = new NetworkFrameCodec(config.maximumFrameBytes(), config.maximumPayloadBytes());
        this.enrollmentNodes.putAll(config.enrollmentNodes());
        this.routes = new VelocityRouteRegistry(proxyServer, new VelocityRouteRegistry.NodeState() {
            @Override
            public boolean managed(String nodeId) {
                return enrollmentNodes.containsKey(nodeId);
            }

            @Override
            public boolean connected(String nodeId) {
                return connectionsByNode.containsKey(nodeId);
            }

            @Override
            public NetworkNodeStatus status(String nodeId) {
                return nodeModes.getOrDefault(nodeId, NetworkNodeStatus.ONLINE);
            }

            @Override
            public NetworkNodeMetrics metrics(String nodeId) {
                return latestMetrics.get(nodeId);
            }
        }, config.routes(), config.maintenanceRoute());
        this.events = new NetworkEventDeliveryService(store, config.networkId(), EVENT_DELIVERY_BATCH, new NetworkEventDeliveryService.DeliveryTarget() {
            @Override
            public Set<String> nodes() {
                return sessions.values().stream().map(Session::nodeId).collect(Collectors.toUnmodifiableSet());
            }

            @Override
            public boolean available(String nodeId) {
                WebSocket connection = connectionsByNode.get(nodeId);
                Session session = connection == null ? null : sessions.get(connection);
                return connection != null && session != null && sessionScopes(session).contains("events.consume");
            }

            @Override
            public void send(String nodeId, NetworkEvent event) {
                WebSocket connection = connectionsByNode.get(nodeId);
                ReSyncVelocityHub.this.send(connection, NetworkFrameType.EVENT_DELIVERY, NetworkChannels.EVENTS, "event-" + event.eventId(), NetworkEventCodec.encodeEvent(event), Set.of("events.consume"));
            }

            @Override
            public void failed(String nodeId, Throwable throwable) {
                logger.warn("Failed to deliver pending network events to {}", nodeId, throwable);
            }
        });
    }

    public void startHub() throws Exception {
        store.open().join();
        long now = Instant.now().toEpochMilli();
        store.registerNode(new NetworkNode(config.networkId(), config.nodeId(), config.displayName(), "PROXY", Set.of("hub", "presence", "routing"), NetworkNodeStatus.ONLINE, now, 0)).join();
        for (VelocityNetworkConfig.EnrollmentNode node : enrollmentNodes.values()) {
            store.registerNode(new NetworkNode(config.networkId(), node.nodeId(), node.displayName(), node.role(), node.capabilities(), NetworkNodeStatus.OFFLINE, 0, 0)).join();
            store.seedEnrollment(config.networkId(), node.nodeId(), node.tokenHash(), node.expiresAt(), now).join();
        }
        store.listNodeMetrics(config.networkId()).join().forEach(metrics -> latestMetrics.put(metrics.nodeId(), metrics));
        if (config.tls().enabled()) {
            setWebSocketFactory(new DefaultSSLWebSocketServerFactory(VelocityNetworkTls.create(config.tls())));
        }
        start();
        heartbeatExecutor.scheduleWithFixedDelay(this::maintainRuntime, config.heartbeatTimeoutMillis(), Math.max(1000, config.heartbeatTimeoutMillis() / 3), TimeUnit.MILLISECONDS);
    }

    public void stopHub() {
        heartbeatExecutor.shutdownNow();
        transferReadiness.values().forEach(future -> future.completeExceptionally(new IllegalStateException("Network Hub Stopping")));
        transferReadiness.clear();
        snapshotUploads.discardOwners("Network Hub Stopping");
        sessions.keySet().forEach(connection -> connection.close(CloseFrame.NORMAL, "Network Hub Stopping"));
        try {
            stop(5000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        try {
            store.updateNodeStatus(config.networkId(), config.nodeId(), NetworkNodeStatus.OFFLINE, Instant.now().toEpochMilli()).join();
        } catch (RuntimeException exception) {
            logger.warn("Failed to record proxy shutdown", exception);
        }
        store.close();
    }

    public CompletableFuture<List<NetworkResource>> resources(String type) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return resourceMetadata(NetworkResourceQuery.firstPage(), normalizedType, new ArrayList<>()).thenCompose(metadata -> {
            List<CompletableFuture<Optional<NetworkResource>>> requests = metadata.stream()
                .map(resource -> store.getResource(config.networkId(), resource.type(), resource.resourceId()))
                .toList();
            return CompletableFuture.allOf(requests.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> requests.stream().map(CompletableFuture::join).flatMap(Optional::stream).filter(resource -> !resource.deleted()).toList());
        });
    }

    public void addResourceListener(Consumer<NetworkResource> listener) {
        if (listener != null) {
            resourceListeners.add(listener);
        }
    }

    private CompletableFuture<List<NetworkResourceMetadata>> resourceMetadata(NetworkResourceQuery query, String type, List<NetworkResourceMetadata> resources) {
        return store.listResources(config.networkId(), query).thenCompose(page -> {
            page.resources().stream().filter(resource -> type.equals(resource.type()) && !resource.deleted()).forEach(resources::add);
            if (!page.hasNext()) {
                return CompletableFuture.completedFuture(List.copyOf(resources));
            }
            return resourceMetadata(new NetworkResourceQuery(page.nextType(), page.nextResourceId(), 128), type, resources);
        });
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        String networkId = header(handshake, "X-ReSync-Network");
        String nodeId = header(handshake, "X-ReSync-Node");
        if (!config.networkId().equals(networkId) || nodeId.isBlank()) {
            connection.close(CloseFrame.POLICY_VALIDATION, "Unknown Network Node");
            return;
        }
        try {
            reloadManagedNodes(nodeId);
        } catch (RuntimeException exception) {
            connection.close(CloseFrame.UNEXPECTED_CONDITION, "Network Configuration Reload Failed");
            return;
        }
        VelocityNetworkConfig.EnrollmentNode node = enrollmentNodes.get(nodeId);
        if (node == null) {
            connection.close(CloseFrame.POLICY_VALIDATION, "Unknown Network Node");
            return;
        }
        String credential = header(handshake, "X-ReSync-Credential");
        String enrollment = header(handshake, "X-ReSync-Enrollment");
        if (!credential.isBlank()) {
            authenticate(connection, node, credential);
            return;
        }
        if (!enrollment.isBlank()) {
            enroll(connection, node, enrollment);
            return;
        }
        connection.close(CloseFrame.POLICY_VALIDATION, "Network Credential Required");
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        Session session = removeSession(connection, "Network Session Closed");
        if (session == null || !connectionsByNode.remove(session.nodeId(), connection)) {
            return;
        }
        pendingReconciliations.entrySet().removeIf(entry -> {
            if (!entry.getValue().nodeId().equals(session.nodeId())) {
                return false;
            }
            entry.getValue().result().completeExceptionally(new IllegalStateException("ReSync Backend Disconnected During State Reconciliation"));
            return true;
        });
        long now = Instant.now().toEpochMilli();
        store.updateNodeStatus(config.networkId(), session.nodeId(), NetworkNodeStatus.OFFLINE, now).thenAccept(node -> publishPresence(presence(node, NetworkNodeStatus.OFFLINE, now))).exceptionally(throwable -> {
            logger.warn("Failed to mark network node {} offline", session.nodeId(), throwable);
            return null;
        });
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        connection.close(CloseFrame.REFUSE, "Binary Network Frames Required");
    }

    @Override
    public void onMessage(WebSocket connection, ByteBuffer message) {
        Session session = sessions.get(connection);
        if (session == null) {
            connection.close(CloseFrame.POLICY_VALIDATION, "Network Authentication Pending");
            return;
        }
        byte[] encoded = new byte[message.remaining()];
        message.get(encoded);
        NetworkFrame frame = null;
        try {
            frame = codec.decode(encoded);
            validateSession(frame, session);
            PendingReconciliation reconciliation = pendingReconciliations.remove(frame.context().requestId());
            if (reconciliation != null) {
                if (!reconciliation.nodeId().equals(session.nodeId())) {
                    throw new SecurityException("State Reconciliation Response Came From The Wrong Node");
                }
                if (frame.type() == NetworkFrameType.ERROR) {
                    reconciliation.result().completeExceptionally(new IllegalStateException(new String(frame.payload(), StandardCharsets.UTF_8)));
                } else if (frame.type() == NetworkFrameType.RESPONSE) {
                    reconciliation.result().complete(null);
                } else {
                    reconciliation.result().completeExceptionally(new IllegalStateException("State Reconciliation Returned An Invalid Response"));
                }
                return;
            }
            if (frame.type() == NetworkFrameType.HEARTBEAT && frame.channel().equals(NetworkChannels.CONTROL)) {
                heartbeat(connection, session, frame, null);
                return;
            }
            if (frame.type() == NetworkFrameType.PRESENCE_DELTA && frame.channel().equals(NetworkChannels.PRESENCE)) {
                heartbeat(connection, session, frame, NetworkPresenceCodec.decode(config.networkId(), session.nodeId(), frame.payload()));
                return;
            }
            if (frame.type() == NetworkFrameType.ROUTE_RECONCILE && frame.channel().equals(NetworkChannels.ROUTING)) {
                requireScope(session, "routes.write");
                reconcileRoutes(session.nodeId(), frame.payload());
                send(connection, NetworkFrameType.RESPONSE, NetworkChannels.ROUTING, frame.context().requestId(), new byte[0], sessionScopes(session));
                return;
            }
            if (frame.type() == NetworkFrameType.NODE_MODE_SET && frame.channel().equals(NetworkChannels.CONTROL)) {
                requireScope(session, "nodes.manage");
                setNodeMode(session.nodeId(), NetworkNodeModeCodec.decode(frame.payload()));
                send(connection, NetworkFrameType.RESPONSE, NetworkChannels.CONTROL, frame.context().requestId(), new byte[0], sessionScopes(session));
                return;
            }
            if (frame.type() == NetworkFrameType.PROXY_ACTION && frame.channel().equals(NetworkChannels.CONTROL)) {
                proxyAction(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.VARIABLE_GET && frame.channel().equals(NetworkChannels.VARIABLES)) {
                variableGet(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.VARIABLE_SET && frame.channel().equals(NetworkChannels.VARIABLES)) {
                variableSet(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.EVENT_PUBLISH && frame.channel().equals(NetworkChannels.EVENTS)) {
                eventPublish(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.EVENT_ACK && frame.channel().equals(NetworkChannels.EVENTS)) {
                eventAcknowledge(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.RESOURCE_GET && frame.channel().equals(NetworkChannels.RESOURCES)) {
                resourceGet(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.RESOURCE_LIST && frame.channel().equals(NetworkChannels.RESOURCES)) {
                resourceList(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.RESOURCE_SET && frame.channel().equals(NetworkChannels.RESOURCES)) {
                resourceSet(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.PLAYER_ROUTE && frame.channel().equals(NetworkChannels.TRANSFER)) {
                playerRoute(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.TRANSFER_INTENT && frame.channel().equals(NetworkChannels.TRANSFER)) {
                transferIntent(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.SNAPSHOT_COMMIT && frame.channel().equals(NetworkChannels.TRANSFER)) {
                snapshotCommit(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.TARGET_READY && frame.channel().equals(NetworkChannels.TRANSFER)) {
                targetReady(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.STATE_APPLIED && frame.channel().equals(NetworkChannels.TRANSFER)) {
                stateApplied(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.TRANSFER_ABORT && frame.channel().equals(NetworkChannels.TRANSFER)) {
                transferAbort(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.OWNER_SNAPSHOT && frame.channel().equals(NetworkChannels.STATE)) {
                ownerSnapshot(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.SNAPSHOT_LIST && frame.channel().equals(NetworkChannels.STATE)) {
                snapshotList(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.SNAPSHOT_READ && frame.channel().equals(NetworkChannels.STATE)) {
                snapshotRead(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.SNAPSHOT_PIN && frame.channel().equals(NetworkChannels.STATE)) {
                snapshotPin(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.SNAPSHOT_RESTORE && frame.channel().equals(NetworkChannels.STATE)) {
                snapshotRestore(connection, session, frame);
                return;
            }
            if (frame.type() == NetworkFrameType.STATE_RECONCILE && frame.channel().equals(NetworkChannels.STATE)) {
                stateReconcile(connection, session, frame);
                return;
            }
            sendError(connection, frame.context().requestId(), "Network Operation Is Not Available");
        } catch (RuntimeException exception) {
            sendError(connection, frame == null ? "invalid" : frame.context().requestId(), rootMessage(exception));
        }
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        logger.warn("ReSync network hub transport error", exception);
    }

    @Override
    public void onStart() {
        logger.info("ReSync network hub listening on {}:{}{}", config.bindHost(), config.port(), config.tls().enabled() ? " with TLS" : "");
    }

    private void authenticate(WebSocket connection, VelocityNetworkConfig.EnrollmentNode node, String credential) {
        store.authenticateNode(config.networkId(), node.nodeId(), NetworkCredentials.hash(credential)).whenComplete((authenticated, throwable) -> {
            if (throwable != null || !authenticated) {
                connection.close(CloseFrame.POLICY_VALIDATION, "Network Credential Rejected");
                return;
            }
            authorize(connection, node, "", NetworkFrameType.RESPONSE);
        });
    }

    private void enroll(WebSocket connection, VelocityNetworkConfig.EnrollmentNode node, String enrollment) {
        String credential = NetworkCredentials.generate();
        store.enrollNode(config.networkId(), node.nodeId(), NetworkCredentials.hash(enrollment), NetworkCredentials.hash(credential), Instant.now().toEpochMilli()).whenComplete((enrolled, throwable) -> {
            if (throwable != null || !enrolled) {
                connection.close(CloseFrame.POLICY_VALIDATION, "Enrollment Token Rejected");
                return;
            }
            authorize(connection, node, credential, NetworkFrameType.ENROLL_ACK);
        });
    }

    private void authorize(WebSocket connection, VelocityNetworkConfig.EnrollmentNode node, String credential, NetworkFrameType responseType) {
        long now = Instant.now().toEpochMilli();
        Set<String> scopes = scopes(node.capabilities());
        Session session = new Session(node.nodeId(), scopes);
        WebSocket previous = connectionsByNode.put(node.nodeId(), connection);
        sessions.put(connection, session);
        if (previous != null && previous != connection) {
            removeSession(previous, "Network Session Replaced");
            previous.close(CloseFrame.NORMAL, "Network Node Reconnected");
        }
        NetworkNodeStatus status = nodeModes.getOrDefault(node.nodeId(), NetworkNodeStatus.ONLINE);
        store.updateNodeStatus(config.networkId(), node.nodeId(), status, now).whenComplete((updated, throwable) -> {
            if (throwable != null) {
                removeSession(connection, "Network Session Registration Failed");
                connectionsByNode.remove(node.nodeId(), connection);
                connection.close(CloseFrame.UNEXPECTED_CONDITION, "Node Registration Failed");
                return;
            }
            if (!isActiveSession(connection, session)) {
                return;
            }
            send(connection, responseType, NetworkChannels.CONTROL, "session", credential.getBytes(StandardCharsets.UTF_8), scopes);
            if (scopes.contains("presence.read")) {
                sendPresenceSnapshot(connection);
            }
            if (scopes.contains("events.consume")) {
                events.deliver(node.nodeId());
            }
            if (scopes.contains("state.transfer")) {
                recoverTransfers(node.nodeId());
                recoverOwnership(node.nodeId());
            }
        });
    }

    private void heartbeat(WebSocket connection, Session session, NetworkFrame request, NetworkNodeMetrics metrics) {
        long now = Instant.now().toEpochMilli();
        NetworkNodeStatus status = nodeModes.getOrDefault(session.nodeId(), NetworkNodeStatus.ONLINE);
        CompletableFuture<?> update = store.updateNodeStatus(config.networkId(), session.nodeId(), status, now);
        if (metrics != null) {
            update = update.thenCompose(node -> store.updateNodeMetrics(new NetworkNodeMetrics(config.networkId(), session.nodeId(), metrics.players(), metrics.capacity(), metrics.tps(), metrics.mspt(), metrics.heapUsed(), metrics.heapMaximum(), now)));
        }
        update.whenComplete((unused, throwable) -> {
            if (throwable != null) {
                sendError(connection, request.context().requestId(), rootMessage(throwable));
                return;
            }
            if (metrics != null) {
                NetworkNodeMetrics observed = new NetworkNodeMetrics(config.networkId(), session.nodeId(), metrics.players(), metrics.capacity(), metrics.tps(), metrics.mspt(), metrics.heapUsed(), metrics.heapMaximum(), now);
                latestMetrics.put(session.nodeId(), observed);
                publishPresence(new NetworkNodePresence(config.networkId(), session.nodeId(), status, observed.players(), observed.capacity(), observed.tps(), observed.mspt(), observed.heapUsed(), observed.heapMaximum(), observed.observedAt()));
            }
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.CONTROL, request.context().requestId(), new byte[0], sessionScopes(session));
        });
    }

    private void validateSession(NetworkFrame frame, Session session) {
        if (!frame.context().networkId().equals(config.networkId()) || !frame.context().nodeId().equals(session.nodeId())) {
            throw new SecurityException("Network Frame Identity Does Not Match The Session");
        }
        if (frame.context().expired(Instant.now().toEpochMilli())) {
            throw new IllegalStateException("Network Frame Deadline Expired");
        }
        if (frame.type() == NetworkFrameType.PRESENCE_DELTA && !sessionScopes(session).contains("presence.write")) {
            throw new SecurityException("Network Node Cannot Publish Presence");
        }
    }

    private void maintainRuntime() {
        expireHeartbeats();
        long now = Instant.now().toEpochMilli();
        store.purgeExpiredVariables(now).exceptionally(throwable -> {
            logger.warn("Failed to purge expired network variables", throwable);
            return 0;
        });
        store.purgeEvents(now - MAXIMUM_EVENT_RETENTION_MILLIS, now).exceptionally(throwable -> {
            logger.warn("Failed to purge expired network events", throwable);
            return 0;
        });
        store.purgeSnapshots(config.networkId(), now - config.snapshotRetentionMillis(), config.snapshotRetentionPerPlayerFamily(), now).exceptionally(throwable -> {
            logger.warn("Failed to apply network snapshot retention", throwable);
            return 0;
        });
        snapshotUploads.expireOwners(now);
        expireTransfers(now);
    }

    private void expireHeartbeats() {
        long now = Instant.now().toEpochMilli();
        long staleBefore = now - config.heartbeatTimeoutMillis();
        store.listNodes(config.networkId()).thenAccept(nodes -> nodes.stream().filter(node -> !node.nodeId().equals(config.nodeId()) && node.status() != NetworkNodeStatus.OFFLINE && node.status() != NetworkNodeStatus.REVOKED && node.heartbeatAt() < staleBefore).forEach(node -> {
            WebSocket connection = connectionsByNode.remove(node.nodeId());
            if (connection != null) {
                removeSession(connection, "Network Session Heartbeat Timed Out");
                connection.close(CloseFrame.GOING_AWAY, "Heartbeat Timeout");
            }
            store.updateNodeStatus(config.networkId(), node.nodeId(), NetworkNodeStatus.OFFLINE, now).thenAccept(updated -> publishPresence(presence(updated, NetworkNodeStatus.OFFLINE, now)));
        })).exceptionally(throwable -> {
            logger.warn("Failed to expire network heartbeats", throwable);
            return null;
        });
    }

    private Set<String> scopes(Set<String> capabilities) {
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add("node.heartbeat");
        if (capabilities.contains("presence")) {
            scopes.add("presence.write");
        }
        if (capabilities.contains("observe")) {
            scopes.add("presence.read");
        }
        if (capabilities.contains("routing")) {
            scopes.add("routes.write");
        }
        if (capabilities.contains("operate")) {
            scopes.add("nodes.manage");
        }
        if (capabilities.contains("command")) {
            scopes.add("proxy.command");
        }
        if (capabilities.contains("broadcast")) {
            scopes.add("proxy.broadcast");
        }
        if (capabilities.contains("variables")) {
            scopes.add("variables.read");
            scopes.add("variables.write");
        }
        if (capabilities.contains("events")) {
            scopes.add("events.publish");
            scopes.add("events.consume");
        }
        if (capabilities.contains("resources")) {
            scopes.add("resources.read");
            scopes.add("resources.write");
        }
        if (capabilities.contains("transfer")) {
            scopes.add("players.route");
            scopes.add("state.reconcile");
        }
        if (capabilities.contains("state-admin")) {
            scopes.add("state.inspect");
            scopes.add("state.restore");
        }
        if (capabilities.stream().anyMatch(capability -> capability.startsWith("state:"))) {
            scopes.add("state.transfer");
        }
        return Set.copyOf(scopes);
    }

    private void stateReconcile(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.restore");
        NetworkStateReconciliationRequest request = NetworkStateReconciliationCodec.decodeRequest(frame.payload());
        Set<String> families = request.families().stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (!Set.of("inventory", "ender-chest").containsAll(families)) {
            throw new IllegalArgumentException("Only Item State Can Be Reconciled");
        }
        store.listLeases(config.networkId()).thenCompose(leases -> {
            if (leases.stream().anyMatch(lease -> !lease.pendingNodeId().isBlank())) {
                return CompletableFuture.failedFuture(new IllegalStateException("A Player State Transfer Is Still Active"));
            }
            PlayerLease unavailableOwner = leases.stream().filter(lease -> !request.nodeIds().contains(lease.ownerNodeId())).findFirst().orElse(null);
            if (unavailableOwner != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Owner Backend " + unavailableOwner.ownerNodeId() + " Must Remain In The ReSync Realm"));
            }
            List<CompletableFuture<Void>> reconciliations = request.nodeIds().stream().sorted().map(nodeId -> reconcileNode(request.transitionId(), nodeId, families, leases)).toList();
            return CompletableFuture.allOf(reconciliations.toArray(new CompletableFuture[0]));
        }).thenCompose(unused -> store.appendAudit(config.networkId(), session.nodeId(), "state.reconciled", request.transitionId(), String.join(",", families), Instant.now().toEpochMilli())).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.STATE, frame.context().requestId(), new byte[0], sessionScopes(session));
        });
    }

    private CompletableFuture<Void> reconcileNode(String transitionId, String nodeId, Set<String> families, List<PlayerLease> leases) {
        Set<UUID> stalePlayers = leases.stream().filter(lease -> !lease.ownerNodeId().equals(nodeId)).map(PlayerLease::playerId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (stalePlayers.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        WebSocket target = connectionsByNode.get(nodeId);
        Session targetSession = target == null ? null : sessions.get(target);
        if (target == null || targetSession == null || !sessionScopes(targetSession).contains("state.reconcile")) {
            return CompletableFuture.failedFuture(new IllegalStateException("ReSync Backend " + nodeId + " Is Not Connected"));
        }
        List<UUID> players = List.copyOf(stalePlayers);
        CompletableFuture<Void> batches = CompletableFuture.completedFuture(null);
        for (int start = 0; start < players.size(); start += RECONCILIATION_BATCH_SIZE) {
            int end = Math.min(players.size(), start + RECONCILIATION_BATCH_SIZE);
            Set<UUID> batch = Set.copyOf(players.subList(start, end));
            batches = batches.thenCompose(unused -> reconcileBatch(target, nodeId, transitionId, batch, families));
        }
        return batches;
    }

    private CompletableFuture<Void> reconcileBatch(WebSocket target, String nodeId, String transitionId, Set<UUID> players, Set<String> families) {
        String requestId = "reconcile-" + lifecycleOrder.incrementAndGet();
        CompletableFuture<Void> result = new CompletableFuture<>();
        PendingReconciliation pending = new PendingReconciliation(nodeId, result);
        pendingReconciliations.put(requestId, pending);
        result.orTimeout(120, TimeUnit.SECONDS).whenComplete((unused, throwable) -> pendingReconciliations.remove(requestId, pending));
        send(target, NetworkFrameType.STATE_RECONCILE, NetworkChannels.STATE, requestId, NetworkStateReconciliationCodec.encodeTask(new NetworkStateReconciliationTask(transitionId, players, families)), Set.of("state.reconcile"));
        return result;
    }

    private void requireScope(Session session, String scope) {
        if (!sessionScopes(session).contains(scope)) {
            throw new SecurityException("Network Session Requires " + scope);
        }
    }

    private Set<String> sessionScopes(Session session) {
        VelocityNetworkConfig.EnrollmentNode node = enrollmentNodes.get(session.nodeId());
        return node == null ? session.scopes() : scopes(node.capabilities());
    }

    private void variableGet(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "variables.read");
        NetworkVariableQuery query = NetworkVariableCodec.decodeQuery(frame.payload());
        store.getVariable(config.networkId(), query.scope(), query.scopeId(), query.key(), Instant.now().toEpochMilli()).whenComplete((variable, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            byte[] payload = variable.map(NetworkVariableCodec::encodeVariable).orElseGet(() -> new byte[0]);
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.VARIABLES, frame.context().requestId(), payload, sessionScopes(session));
        });
    }

    private void resourceGet(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "resources.read");
        NetworkResourceKey key = NetworkResourceCodec.decodeKey(frame.payload());
        store.getResource(config.networkId(), key.type(), key.resourceId()).whenComplete((resource, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            byte[] payload = resource.map(NetworkResourceCodec::encodeResource).orElseGet(() -> new byte[0]);
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.RESOURCES, frame.context().requestId(), payload, sessionScopes(session));
        });
    }

    private void resourceList(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "resources.read");
        NetworkResourceQuery query = NetworkResourceCodec.decodeQuery(frame.payload());
        store.listResources(config.networkId(), query).whenComplete((page, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.RESOURCES, frame.context().requestId(), NetworkResourceCodec.encodePage(page), sessionScopes(session));
        });
    }

    private void resourceSet(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "resources.write");
        NetworkResourceMutation mutation = NetworkResourceCodec.decodeMutation(frame.payload());
        store.compareAndSetResource(config.networkId(), session.nodeId(), mutation, Instant.now().toEpochMilli()).whenComplete((resource, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            byte[] payload = NetworkResourceCodec.encodeResource(resource);
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.RESOURCES, frame.context().requestId(), payload, sessionScopes(session));
            resourceListeners.forEach(listener -> {
                try {
                    listener.accept(resource);
                } catch (RuntimeException exception) {
                    logger.warn("ReSync network resource listener failed", exception);
                }
            });
            publishResource(resource, session.nodeId());
        });
    }

    private void publishResource(NetworkResource resource, String sourceNodeId) {
        byte[] payload = NetworkResourceCodec.encodeResource(resource);
        sessions.forEach((connection, session) -> {
            if (!session.nodeId().equals(sourceNodeId) && sessionScopes(session).contains("resources.read")) {
                send(connection, NetworkFrameType.RESOURCE_CHANGED, NetworkChannels.RESOURCES, "resource-" + resource.type() + "-" + resource.revision(), payload, Set.of("resources.read"));
            }
        });
    }

    private void variableSet(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "variables.write");
        NetworkVariableMutation mutation = NetworkVariableCodec.decodeMutation(frame.payload());
        long now = Instant.now().toEpochMilli();
        if (mutation.expiresAt() > 0 && mutation.expiresAt() <= now) {
            throw new IllegalArgumentException("Network Variable Expiry Must Be In The Future");
        }
        NetworkVariable desired = new NetworkVariable(config.networkId(), mutation.scope(), mutation.scopeId(), mutation.key(), mutation.type(), mutation.value(), mutation.expectedRevision(), mutation.expiresAt(), session.nodeId(), now);
        store.compareAndSetVariable(desired, mutation.expectedRevision()).whenComplete((stored, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            byte[] payload = NetworkVariableCodec.encodeVariable(stored);
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.VARIABLES, frame.context().requestId(), payload, sessionScopes(session));
            publishVariable(stored);
        });
    }

    private void publishVariable(NetworkVariable variable) {
        byte[] payload = NetworkVariableCodec.encodeVariable(variable);
        sessions.forEach((connection, session) -> {
            if (sessionScopes(session).contains("variables.read")) {
                send(connection, NetworkFrameType.VARIABLE_CHANGED, NetworkChannels.VARIABLES, "variable-change-" + variable.revision(), payload, Set.of("variables.read"));
            }
        });
    }

    private void eventPublish(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "events.publish");
        NetworkEventPublish request = NetworkEventCodec.decodePublish(frame.payload());
        long now = Instant.now().toEpochMilli();
        if (request.createdAt() > now + TimeUnit.SECONDS.toMillis(10) || request.createdAt() < now - MAXIMUM_EVENT_RETENTION_MILLIS || request.expiresAt() <= now || request.expiresAt() > request.createdAt() + MAXIMUM_EVENT_RETENTION_MILLIS) {
            throw new IllegalArgumentException("Network Event Expiry Must Be Within Seven Days");
        }
        NetworkEvent event = new NetworkEvent(request.eventId(), config.networkId(), request.channel(), request.subject(), request.payload(), session.nodeId(), request.createdAt(), request.expiresAt());
        events.publish(event, now).whenComplete((stored, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.EVENTS, frame.context().requestId(), NetworkEventCodec.encodeEvent(stored), sessionScopes(session));
            events.deliverAll();
        });
    }

    public void publishPlayerLifecycle(NetworkPlayerLifecycle lifecycle) {
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - lifecycle.occurredAt()) > TimeUnit.SECONDS.toMillis(30)) {
            throw new IllegalArgumentException("Network Player Lifecycle Time Is Stale");
        }
        long orderedAt = lifecycleOrder.updateAndGet(previous -> Math.max(lifecycle.occurredAt(), previous + 1));
        NetworkEvent event = new NetworkEvent(UUID.randomUUID().toString(), config.networkId(), NetworkEventTopics.PLAYER_LIFECYCLE, lifecycle.type().name(), NetworkPlayerLifecycleCodec.encode(lifecycle), config.nodeId(), orderedAt, now + TimeUnit.DAYS.toMillis(1));
        events.publishWithoutAudit(event).exceptionally(throwable -> {
            logger.warn("Failed to publish player lifecycle event {}", lifecycle.type(), throwable);
            return null;
        });
    }

    private void eventAcknowledge(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "events.consume");
        String eventId = NetworkEventCodec.decodeAcknowledgement(frame.payload());
        events.acknowledge(eventId, session.nodeId(), Instant.now().toEpochMilli()).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.EVENTS, frame.context().requestId(), new byte[0], sessionScopes(session));
            events.deliver(session.nodeId());
        });
    }

    private void ownerSnapshot(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.transfer");
        NetworkSnapshotChunk chunk = NetworkTransferCodec.decodeChunk(frame.payload());
        if (!chunk.transferId().equals("owner:" + chunk.snapshotId()) || !chunk.networkId().equals(config.networkId()) || !chunk.originNodeId().equals(session.nodeId())) {
            throw new SecurityException("Owned Snapshot Identity Does Not Match The Session");
        }
        String realm = chunk.family().contains("/") ? chunk.family().substring(0, chunk.family().indexOf('/')) : chunk.family();
        if (!stateRealms(session.nodeId()).contains(realm)) {
            throw new SecurityException("Owned Snapshot Realm Does Not Match The Session");
        }
        store.getLease(config.networkId(), chunk.playerId()).whenComplete((stored, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            PlayerLease lease = stored.orElse(null);
            if (lease == null || !lease.ownerNodeId().equals(session.nodeId()) || !lease.pendingNodeId().isBlank() || lease.fenceEpoch() != chunk.fenceEpoch()) {
                sendError(connection, frame.context().requestId(), "Snapshot Writer Does Not Own The Current Fence");
                return;
            }
            try {
                if (!isActiveSession(connection, session)) {
                    return;
                }
                SnapshotUploadRegistry.OwnerUpload assembly = snapshotUploads.ownerUpload(chunk.snapshotId(), session, chunk, Instant.now().toEpochMilli());
                if (!isActiveSession(connection, session)) {
                    snapshotUploads.discardOwner(chunk.snapshotId(), assembly, "Network Session Closed");
                    throw new IllegalStateException("Network Session Is No Longer Active");
                }
                boolean complete = assembly.add(chunk);
                if (!complete) {
                    send(connection, NetworkFrameType.OWNER_SNAPSHOT, NetworkChannels.STATE, frame.context().requestId(), NetworkOwnershipCodec.encode(lease), sessionScopes(session));
                    return;
                }
                assembly.result().whenComplete((savedLease, failure) -> {
                    if (failure != null) {
                        sendError(connection, frame.context().requestId(), rootMessage(failure));
                    } else {
                        send(connection, NetworkFrameType.OWNER_SNAPSHOT, NetworkChannels.STATE, frame.context().requestId(), NetworkOwnershipCodec.encode(savedLease), sessionScopes(session));
                    }
                });
                if (assembly.claimCommit()) {
                    PlayerStateSnapshot snapshot;
                    try {
                        snapshot = NetworkTransferCodec.assemble(assembly.chunks());
                    } catch (RuntimeException exception) {
                        assembly.fail(exception);
                        snapshotUploads.removeOwner(chunk.snapshotId(), assembly);
                        return;
                    }
                    store.saveOwnerSnapshot(snapshot).thenCompose(saved -> store.getLease(config.networkId(), saved.playerId()).thenApply(current -> current.orElseThrow(() -> new IllegalStateException("Player Ownership Is Missing After Snapshot Save")))).whenComplete((savedLease, failure) -> {
                        if (failure != null) {
                            assembly.fail(failure);
                        } else {
                            assembly.complete(savedLease);
                        }
                        snapshotUploads.removeOwner(chunk.snapshotId(), assembly);
                    });
                }
            } catch (RuntimeException exception) {
                sendError(connection, frame.context().requestId(), rootMessage(exception));
            }
        });
    }

    private boolean isActiveSession(WebSocket connection, Session session) {
        return sessions.get(connection) == session && connectionsByNode.get(session.nodeId()) == connection;
    }

    private Session removeSession(WebSocket connection, String reason) {
        Session session = sessions.remove(connection);
        if (session != null) {
            snapshotUploads.discardOwners(session, reason);
            events.remove(session.nodeId());
        }
        return session;
    }

    private void snapshotList(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.inspect");
        NetworkSnapshotQuery query = NetworkSnapshotAdminCodec.decodeQuery(frame.payload());
        store.listSnapshots(config.networkId(), query.playerId(), query.offset(), query.limit()).whenComplete((snapshots, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            List<NetworkSnapshotMetadata> metadata = snapshots.stream().map(NetworkSnapshotMetadata::from).toList();
            send(connection, NetworkFrameType.SNAPSHOT_LIST, NetworkChannels.STATE, frame.context().requestId(), NetworkSnapshotAdminCodec.encodeList(metadata), sessionScopes(session));
        });
    }

    private void snapshotRead(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.inspect");
        String snapshotId = NetworkSnapshotAdminCodec.decodeReference(frame.payload());
        store.getSnapshot(snapshotId).whenComplete((stored, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            PlayerStateSnapshot snapshot = stored.filter(value -> value.networkId().equals(config.networkId())).orElse(null);
            if (snapshot == null) {
                sendError(connection, frame.context().requestId(), "Player Snapshot Does Not Exist");
                return;
            }
            send(connection, NetworkFrameType.SNAPSHOT_READ, NetworkChannels.STATE, frame.context().requestId(), NetworkSnapshotAdminCodec.encodeMetadata(NetworkSnapshotMetadata.from(snapshot)), sessionScopes(session));
        });
    }

    private void snapshotPin(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.restore");
        NetworkSnapshotPin pin = NetworkSnapshotAdminCodec.decodePin(frame.payload());
        store.getSnapshot(pin.snapshotId()).thenCompose(stored -> {
            PlayerStateSnapshot snapshot = stored.filter(value -> value.networkId().equals(config.networkId())).orElseThrow(() -> new IllegalStateException("Player Snapshot Does Not Exist"));
            return store.pinSnapshot(snapshot.snapshotId(), pin.pinned()).thenCompose(unused -> store.appendAudit(config.networkId(), session.nodeId(), pin.pinned() ? "snapshot.pinned" : "snapshot.unpinned", snapshot.snapshotId(), snapshot.payloadHash(), Instant.now().toEpochMilli())).thenCompose(unused -> store.getSnapshot(snapshot.snapshotId())).thenApply(updated -> updated.orElseThrow(() -> new IllegalStateException("Player Snapshot Does Not Exist")));
        }).whenComplete((snapshot, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            send(connection, NetworkFrameType.SNAPSHOT_PIN, NetworkChannels.STATE, frame.context().requestId(), NetworkSnapshotAdminCodec.encodeMetadata(NetworkSnapshotMetadata.from(snapshot)), sessionScopes(session));
        });
    }

    private void snapshotRestore(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.restore");
        NetworkSnapshotRestore request = NetworkSnapshotAdminCodec.decodeRestore(frame.payload());
        long now = Instant.now().toEpochMilli();
        if (request.deadline() <= now || request.deadline() > now + MAXIMUM_MANUAL_RESTORE_MILLIS) {
            throw new IllegalArgumentException("Snapshot Restore Deadline Must Be Within Fifteen Minutes");
        }
        requireTransferNode(request.targetNodeId(), "Restore Target");
        requireStateTransferSession(request.targetNodeId(), "Restore Target");
        store.getSnapshot(request.snapshotId()).thenCompose(stored -> {
            PlayerStateSnapshot source = stored.filter(value -> value.networkId().equals(config.networkId())).orElseThrow(() -> new IllegalStateException("Player Snapshot Does Not Exist"));
            if (proxyServer.getPlayer(source.playerId()).isPresent()) {
                throw new IllegalStateException("Player Must Be Offline Before Snapshot Restore");
            }
            String realm = source.family().contains("/") ? source.family().substring(0, source.family().indexOf('/')) : source.family();
            if (!stateRealms(request.targetNodeId()).contains(realm)) {
                throw new IllegalStateException("Restore Target Does Not Share The Snapshot Realm");
            }
            String transferId = UUID.randomUUID().toString();
            return store.beginRestore(transferId, config.networkId(), source.playerId(), request.targetNodeId(), source.snapshotId(), request.deadline(), now).thenCompose(transfer -> store.getSnapshot(transfer.snapshotId()).thenCompose(restored -> {
                PlayerStateSnapshot snapshot = restored.orElseThrow(() -> new IllegalStateException("Prepared Player Restore Snapshot Is Missing"));
                CompletableFuture<PlayerTransfer> readiness = awaitTransferReady(transfer, request.deadline());
                deliverSnapshot(transfer, snapshot);
                return readiness;
            })).thenCompose(ready -> store.appendAudit(config.networkId(), session.nodeId(), "snapshot.restore.ready", ready.transferId(), source.snapshotId(), Instant.now().toEpochMilli()).thenApply(unused -> ready));
        }).whenComplete((transfer, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            send(connection, NetworkFrameType.SNAPSHOT_RESTORE, NetworkChannels.STATE, frame.context().requestId(), NetworkTransferCodec.encodeTransfer(transfer), sessionScopes(session));
        });
    }

    private void transferIntent(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.transfer");
        NetworkTransferIntent intent = NetworkTransferCodec.decodeIntent(frame.payload());
        long now = Instant.now().toEpochMilli();
        if (!intent.sourceNodeId().equals(session.nodeId())) {
            throw new SecurityException("Transfer Source Does Not Match The Session");
        }
        if (intent.deadline() <= now || intent.deadline() > now + MAXIMUM_TRANSFER_MILLIS) {
            throw new IllegalArgumentException("Transfer Deadline Must Be Within Two Minutes");
        }
        requireTransferNode(intent.sourceNodeId(), "Source");
        requireTransferNode(intent.targetNodeId(), "Target");
        requireStateTransferSession(intent.targetNodeId(), "Target");
        requireSharedStateRealm(intent.sourceNodeId(), intent.targetNodeId());
        Player player = proxyServer.getPlayer(intent.playerId()).orElseThrow(() -> new IllegalStateException("Network Player Is Not Connected"));
        String currentRoute = player.getCurrentServer().map(server -> server.getServerInfo().getName().toLowerCase(Locale.ROOT)).orElse("");
        NetworkRoute current = routes.route(currentRoute);
        if (current == null || !current.nodeId().equals(intent.sourceNodeId())) {
            throw new SecurityException("Transfer Source Does Not Own The Connected Player");
        }
        store.beginTransfer(intent.transferId(), config.networkId(), intent.playerId(), intent.sourceNodeId(), intent.targetNodeId(), intent.deadline(), now).thenCompose(transfer -> store.appendAudit(config.networkId(), session.nodeId(), "transfer.intent", transfer.transferId(), NetworkPayloads.sha256(frame.payload()), now).thenApply(unused -> transfer)).whenComplete((transfer, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            send(connection, NetworkFrameType.LEASE_GRANTED, NetworkChannels.TRANSFER, frame.context().requestId(), NetworkTransferCodec.encodeTransfer(transfer), sessionScopes(session));
        });
    }

    private void snapshotCommit(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.transfer");
        NetworkSnapshotChunk chunk = NetworkTransferCodec.decodeChunk(frame.payload());
        if (!chunk.networkId().equals(config.networkId()) || !chunk.originNodeId().equals(session.nodeId())) {
            throw new SecurityException("Snapshot Identity Does Not Match The Session");
        }
        store.getTransfer(chunk.transferId()).whenComplete((stored, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            PlayerTransfer transfer = stored.orElse(null);
            if (transfer == null) {
                sendError(connection, frame.context().requestId(), "Player Transfer Does Not Exist");
                return;
            }
            try {
                requireTransferSource(session, transfer);
                requireSnapshotTransfer(chunk, transfer);
                if (transfer.status().ordinal() >= NetworkTransferStatus.SNAPSHOT_COMMITTED.ordinal()) {
                    if (!transfer.snapshotId().equals(chunk.snapshotId())) {
                        throw new IllegalStateException("Transfer Already Uses A Different Snapshot");
                    }
                    send(connection, NetworkFrameType.SNAPSHOT_COMMIT, NetworkChannels.TRANSFER, frame.context().requestId(), NetworkTransferCodec.encodeTransfer(transfer), sessionScopes(session));
                    return;
                }
                SnapshotUploadRegistry.TransferUpload assembly = snapshotUploads.transferUpload(chunk, Instant.now().toEpochMilli());
                boolean complete = assembly.add(chunk);
                if (!complete) {
                    send(connection, NetworkFrameType.SNAPSHOT_COMMIT, NetworkChannels.TRANSFER, frame.context().requestId(), NetworkTransferCodec.encodeTransfer(transfer), sessionScopes(session));
                    return;
                }
                assembly.result().whenComplete((committed, failure) -> {
                    if (failure != null) {
                        sendError(connection, frame.context().requestId(), rootMessage(failure));
                    } else {
                        send(connection, NetworkFrameType.SNAPSHOT_COMMIT, NetworkChannels.TRANSFER, frame.context().requestId(), NetworkTransferCodec.encodeTransfer(committed), sessionScopes(session));
                    }
                });
                if (assembly.claimCommit()) {
                    PlayerStateSnapshot snapshot;
                    try {
                        snapshot = NetworkTransferCodec.assemble(assembly.chunks());
                    } catch (RuntimeException exception) {
                        assembly.fail(exception);
                        snapshotUploads.removeTransfer(transfer.transferId(), assembly);
                        return;
                    }
                    store.commitSnapshot(transfer.transferId(), snapshot).whenComplete((committedSnapshot, failure) -> {
                        if (failure != null) {
                            assembly.fail(failure);
                            snapshotUploads.removeTransfer(transfer.transferId(), assembly);
                            return;
                        }
                        store.getTransfer(transfer.transferId()).whenComplete((advanced, readFailure) -> {
                            if (readFailure != null || advanced.isEmpty()) {
                                assembly.fail(readFailure == null ? new IllegalStateException("Committed Transfer Is Missing") : readFailure);
                            } else {
                                PlayerTransfer committed = advanced.orElseThrow();
                                assembly.complete(committed);
                                deliverSnapshot(committed, committedSnapshot);
                            }
                            snapshotUploads.removeTransfer(transfer.transferId(), assembly);
                        });
                    });
                }
            } catch (RuntimeException exception) {
                sendError(connection, frame.context().requestId(), rootMessage(exception));
            }
        });
    }

    private void targetReady(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.transfer");
        NetworkTransferCheckpoint checkpoint = NetworkTransferCodec.decodeCheckpoint(frame.payload());
        store.getTransfer(checkpoint.transferId()).thenCompose(stored -> {
            PlayerTransfer transfer = stored.orElseThrow(() -> new IllegalStateException("Player Transfer Does Not Exist"));
            requireTransferTarget(session, transfer);
            if (!checkpoint.snapshotId().isBlank() && !checkpoint.snapshotId().equals(transfer.snapshotId())) {
                throw new IllegalStateException("Ready Snapshot Does Not Match The Transfer");
            }
            return store.markTargetReady(transfer.transferId(), Instant.now().toEpochMilli());
        }).whenComplete((transfer, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
            } else {
                send(connection, NetworkFrameType.TARGET_READY, NetworkChannels.TRANSFER, frame.context().requestId(), NetworkTransferCodec.encodeTransfer(transfer), sessionScopes(session));
                notifyTransfer(transfer.sourceNodeId(), NetworkFrameType.TARGET_READY, transfer);
                CompletableFuture<PlayerTransfer> readiness = transferReadiness.remove(transfer.transferId());
                if (readiness != null) {
                    readiness.complete(transfer);
                }
            }
        });
    }

    public boolean requiresPlayerStateTransfer(String sourceRoute, String targetRoute) {
        NetworkRoute source = routes.route(sourceRoute);
        NetworkRoute target = routes.route(targetRoute);
        if (source == null || target == null || source.nodeId().equals(target.nodeId())) {
            return false;
        }
        Set<String> sourceRealms = stateRealms(source.nodeId());
        return !sourceRealms.isEmpty() && stateRealms(target.nodeId()).stream().anyMatch(sourceRealms::contains);
    }

    public boolean requiresInitialPlayerState(String targetRoute) {
        NetworkRoute target = routes.route(targetRoute);
        return target != null && !stateRealms(target.nodeId()).isEmpty();
    }

    public CompletableFuture<RegisteredServer> prepareInitialPlayer(UUID playerId, String targetRoute, long deadline) {
        long now = Instant.now().toEpochMilli();
        NetworkRoute target = routes.route(targetRoute);
        if (target == null || stateRealms(target.nodeId()).isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Initial Player State Realm Is Not Available"));
        }
        RegisteredServer targetServer = proxyServer.getServer(target.routeName()).orElseThrow(() -> new IllegalStateException("Initial Player Target Is Not Registered"));
        try {
            requireTransferNode(target.nodeId(), "Target");
            requireStateTransferSession(target.nodeId(), "Target");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        String realm = stateRealms(target.nodeId()).stream().sorted().findFirst().orElseThrow();
        return store.getLease(config.networkId(), playerId).thenCompose(stored -> {
            PlayerLease lease = stored.orElse(null);
            if (lease == null) {
                return store.claimOwnership(config.networkId(), playerId, target.nodeId(), now).thenApply(claimed -> {
                    notifyOwnership(target.nodeId(), claimed);
                    return targetServer;
                });
            }
            if (lease.ownerNodeId().equals(target.nodeId()) && lease.pendingNodeId().isBlank()) {
                notifyOwnership(target.nodeId(), lease);
                return CompletableFuture.completedFuture(targetServer);
            }
            if (!lease.pendingNodeId().isBlank()) {
                if (!lease.pendingNodeId().equals(target.nodeId())) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Player Already Has A Different Pending State Target"));
                }
                return prepareSnapshotRestore(playerId, target, realm, deadline, targetServer);
            }
            RegisteredServer owner = routes.routes().values().stream().filter(route -> route.nodeId().equals(lease.ownerNodeId()) && stateRealms(route.nodeId()).contains(realm) && acceptsRoute(route.routeName())).map(NetworkRoute::routeName).map(proxyServer::getServer).flatMap(Optional::stream).findFirst().orElse(null);
            if (owner != null && connectionsByNode.containsKey(lease.ownerNodeId())) {
                return CompletableFuture.completedFuture(owner);
            }
            return prepareSnapshotRestore(playerId, target, realm, deadline, targetServer);
        });
    }

    private CompletableFuture<RegisteredServer> prepareSnapshotRestore(UUID playerId, NetworkRoute target, String realm, long deadline, RegisteredServer targetServer) {
        long now = Instant.now().toEpochMilli();
        return store.getActiveTransfer(config.networkId(), playerId, now).thenCompose(active -> {
            PlayerTransfer existing = active.orElse(null);
            if (existing != null) {
                if (!existing.targetNodeId().equals(target.nodeId()) || existing.status().ordinal() < NetworkTransferStatus.SNAPSHOT_COMMITTED.ordinal()) {
                    throw new IllegalStateException("Player Already Has A Different Active Restore");
                }
                return store.getSnapshot(existing.snapshotId()).thenCompose(snapshot -> {
                    PlayerStateSnapshot value = snapshot.orElseThrow(() -> new IllegalStateException("Player Restore Snapshot Is Missing"));
                    deliverSnapshot(existing, value);
                    return awaitTransferReady(existing, deadline).thenApply(ready -> targetServer);
                });
            }
            return store.latestSnapshotInRealm(config.networkId(), playerId, realm).thenCompose(snapshot -> {
                PlayerStateSnapshot value = snapshot.orElseThrow(() -> new IllegalStateException("No Restorable Player State Snapshot Is Available"));
                String transferId = UUID.randomUUID().toString();
                return store.beginRestore(transferId, config.networkId(), playerId, target.nodeId(), value.snapshotId(), deadline, now).thenCompose(transfer -> store.getSnapshot(transfer.snapshotId()).thenCompose(restored -> {
                    PlayerStateSnapshot restorable = restored.orElseThrow(() -> new IllegalStateException("Prepared Player Restore Snapshot Is Missing"));
                    CompletableFuture<PlayerTransfer> readiness = awaitTransferReady(transfer, deadline);
                    deliverSnapshot(transfer, restorable);
                    return readiness.thenApply(ready -> targetServer);
                }));
            });
        });
    }

    public void playerJoined(UUID playerId, String targetRoute) {
        NetworkRoute target = routes.route(targetRoute);
        if (target == null || stateRealms(target.nodeId()).isEmpty()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        store.getActiveTransfer(config.networkId(), playerId, now).thenCompose(active -> active.isPresent() ? CompletableFuture.<PlayerLease>completedFuture(null) : store.claimOwnership(config.networkId(), playerId, target.nodeId(), now)).thenAccept(lease -> {
            if (lease == null) {
                return;
            }
            if (!lease.ownerNodeId().equals(target.nodeId()) || !lease.pendingNodeId().isBlank()) {
                logger.warn("Player joined state realm on {} while ownership remains on {}", target.nodeId(), lease.ownerNodeId());
                return;
            }
            notifyOwnership(target.nodeId(), lease);
        }).exceptionally(throwable -> {
            logger.warn("Failed to claim initial player ownership on {}", target.nodeId(), throwable);
            return null;
        });
    }

    private void notifyOwnership(String nodeId, PlayerLease lease) {
        WebSocket connection = connectionsByNode.get(nodeId);
        Session session = connection == null ? null : sessions.get(connection);
        if (connection != null && session != null && sessionScopes(session).contains("state.transfer")) {
            send(connection, NetworkFrameType.OWNER_CLAIM, NetworkChannels.STATE, "ownership-" + lease.playerId(), NetworkOwnershipCodec.encode(lease), Set.of("state.transfer"));
        }
    }

    public CompletableFuture<PlayerTransfer> preparePlayerTransfer(UUID playerId, String sourceRoute, String targetRoute, long deadline) {
        long now = Instant.now().toEpochMilli();
        if (deadline <= now || deadline > now + MAXIMUM_TRANSFER_MILLIS) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Transfer Deadline Must Be Within Two Minutes"));
        }
        NetworkRoute source = routes.route(sourceRoute);
        NetworkRoute target = routes.route(targetRoute);
        if (source == null || target == null || !requiresPlayerStateTransfer(sourceRoute, targetRoute)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Player State Transfer Realm Is Not Available"));
        }
        try {
            requireTransferNode(source.nodeId(), "Source");
            requireTransferNode(target.nodeId(), "Target");
            requireStateTransferSession(source.nodeId(), "Source");
            requireStateTransferSession(target.nodeId(), "Target");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return store.getActiveTransfer(config.networkId(), playerId, now).thenCompose(active -> {
            PlayerTransfer existing = active.orElse(null);
            if (existing != null) {
                if (!existing.sourceNodeId().equals(source.nodeId()) || !existing.targetNodeId().equals(target.nodeId())) {
                    throw new IllegalStateException("Player Already Has A Different Active Transfer");
                }
                return awaitTransferReady(existing, deadline);
            }
            String transferId = UUID.randomUUID().toString();
            return store.beginTransfer(transferId, config.networkId(), playerId, source.nodeId(), target.nodeId(), deadline, now).thenCompose(transfer -> store.appendAudit(config.networkId(), config.nodeId(), "transfer.intent", transfer.transferId(), sourceRoute + "->" + targetRoute, now).thenApply(unused -> transfer)).thenCompose(transfer -> {
                CompletableFuture<PlayerTransfer> readiness = awaitTransferReady(transfer, deadline);
                notifyTransfer(transfer.sourceNodeId(), NetworkFrameType.LEASE_GRANTED, transfer);
                return readiness;
            });
        });
    }

    private CompletableFuture<PlayerTransfer> awaitTransferReady(PlayerTransfer transfer, long deadline) {
        if (transfer.status().ordinal() >= NetworkTransferStatus.TARGET_READY.ordinal()) {
            return CompletableFuture.completedFuture(transfer);
        }
        CompletableFuture<PlayerTransfer> readiness = transferReadiness.computeIfAbsent(transfer.transferId(), ignored -> new CompletableFuture<>());
        long timeout = Math.max(1, deadline - Instant.now().toEpochMilli());
        readiness.orTimeout(timeout, TimeUnit.MILLISECONDS).whenComplete((ready, throwable) -> {
            transferReadiness.remove(transfer.transferId(), readiness);
            if (throwable != null) {
                store.abortTransfer(transfer.transferId(), "TARGET_READY_TIMEOUT", Instant.now().toEpochMilli()).thenAccept(aborted -> {
                    snapshotUploads.removeTransfer(aborted.transferId());
                    notifyTransfer(aborted.sourceNodeId(), NetworkFrameType.TRANSFER_ABORT, aborted);
                    notifyTransfer(aborted.targetNodeId(), NetworkFrameType.TRANSFER_ABORT, aborted);
                }).exceptionally(abortFailure -> {
                    logger.warn("Failed to abort timed out player transfer {}", transfer.transferId(), abortFailure);
                    return null;
                });
            }
        });
        return readiness;
    }

    public void playerConnected(UUID playerId, String sourceRoute, String targetRoute) {
        long now = Instant.now().toEpochMilli();
        store.getActiveTransfer(config.networkId(), playerId, now).thenAccept(stored -> stored.ifPresent(transfer -> {
            if (!matchesTransferRoutes(transfer, sourceRoute, targetRoute) || transfer.status() != NetworkTransferStatus.TARGET_READY) {
                return;
            }
            store.markConnected(transfer.transferId(), now).thenAccept(connected -> notifyTransfer(connected.targetNodeId(), NetworkFrameType.PLAYER_CONNECTED, connected)).exceptionally(throwable -> {
                logger.warn("Failed to mark player transfer {} connected", transfer.transferId(), throwable);
                return null;
            });
        })).exceptionally(throwable -> {
            logger.warn("Failed to inspect connected player transfer", throwable);
            return null;
        });
    }

    public void playerTransferFailed(UUID playerId, String sourceRoute, String targetRoute, String failure) {
        long now = Instant.now().toEpochMilli();
        store.getActiveTransfer(config.networkId(), playerId, now).thenAccept(stored -> stored.filter(transfer -> matchesTransferRoutes(transfer, sourceRoute, targetRoute)).ifPresent(transfer -> store.abortTransfer(transfer.transferId(), failure, now).thenAccept(aborted -> {
            snapshotUploads.removeTransfer(aborted.transferId());
            failReadiness(aborted);
            notifyTransfer(aborted.sourceNodeId(), NetworkFrameType.TRANSFER_ABORT, aborted);
            notifyTransfer(aborted.targetNodeId(), NetworkFrameType.TRANSFER_ABORT, aborted);
        }).exceptionally(throwable -> {
            logger.warn("Failed to abort player transfer {}", transfer.transferId(), throwable);
            return null;
        }))).exceptionally(throwable -> {
            logger.warn("Failed to inspect failed player transfer", throwable);
            return null;
        });
    }

    private boolean matchesTransferRoutes(PlayerTransfer transfer, String sourceRoute, String targetRoute) {
        NetworkRoute source = routes.route(sourceRoute);
        NetworkRoute target = routes.route(targetRoute);
        boolean sourceMatches = sourceRoute == null || sourceRoute.isBlank() || (source != null && source.nodeId().equals(transfer.sourceNodeId()));
        return sourceMatches && target != null && target.nodeId().equals(transfer.targetNodeId());
    }

    private void stateApplied(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.transfer");
        NetworkTransferCheckpoint checkpoint = NetworkTransferCodec.decodeCheckpoint(frame.payload());
        long now = Instant.now().toEpochMilli();
        store.getTransfer(checkpoint.transferId()).thenCompose(stored -> {
            PlayerTransfer transfer = stored.orElseThrow(() -> new IllegalStateException("Player Transfer Does Not Exist"));
            requireTransferTarget(session, transfer);
            return store.acknowledgeApplied(transfer.transferId(), checkpoint.snapshotId(), now);
        }).thenCompose(applied -> store.commitTransfer(applied.transferId(), Instant.now().toEpochMilli())).whenComplete((transfer, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            send(connection, NetworkFrameType.TRANSFER_COMMIT, NetworkChannels.TRANSFER, frame.context().requestId(), NetworkTransferCodec.encodeTransfer(transfer), sessionScopes(session));
            notifyTransfer(transfer.sourceNodeId(), NetworkFrameType.TRANSFER_COMMIT, transfer);
            if (!transfer.targetNodeId().equals(session.nodeId())) {
                notifyTransfer(transfer.targetNodeId(), NetworkFrameType.TRANSFER_COMMIT, transfer);
            }
        });
    }

    private void transferAbort(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "state.transfer");
        NetworkTransferCheckpoint checkpoint = NetworkTransferCodec.decodeCheckpoint(frame.payload());
        if (checkpoint.failure().isBlank()) {
            throw new IllegalArgumentException("Transfer Failure Is Required");
        }
        store.getTransfer(checkpoint.transferId()).thenCompose(stored -> {
            PlayerTransfer transfer = stored.orElseThrow(() -> new IllegalStateException("Player Transfer Does Not Exist"));
            requireTransferMember(session, transfer);
            return store.abortTransfer(transfer.transferId(), checkpoint.failure(), Instant.now().toEpochMilli());
        }).whenComplete((transfer, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
                return;
            }
            snapshotUploads.removeTransfer(transfer.transferId());
            failReadiness(transfer);
            send(connection, NetworkFrameType.TRANSFER_ABORT, NetworkChannels.TRANSFER, frame.context().requestId(), NetworkTransferCodec.encodeTransfer(transfer), sessionScopes(session));
            if (!transfer.sourceNodeId().equals(session.nodeId())) {
                notifyTransfer(transfer.sourceNodeId(), NetworkFrameType.TRANSFER_ABORT, transfer);
            }
            if (!transfer.targetNodeId().equals(session.nodeId())) {
                notifyTransfer(transfer.targetNodeId(), NetworkFrameType.TRANSFER_ABORT, transfer);
            }
        });
    }

    private void requireSnapshotTransfer(NetworkSnapshotChunk chunk, PlayerTransfer transfer) {
        if (!chunk.playerId().equals(transfer.playerId()) || chunk.fenceEpoch() != transfer.fenceEpoch() || !chunk.originNodeId().equals(transfer.sourceNodeId())) {
            throw new SecurityException("Snapshot Does Not Match The Transfer Lease");
        }
        String realm = chunk.family().contains("/") ? chunk.family().substring(0, chunk.family().indexOf('/')) : chunk.family();
        if (!stateRealms(transfer.sourceNodeId()).contains(realm) || !stateRealms(transfer.targetNodeId()).contains(realm)) {
            throw new SecurityException("Snapshot Realm Does Not Match The Transfer Nodes");
        }
        if (transfer.status() != NetworkTransferStatus.SOURCE_LEASED && transfer.status().ordinal() < NetworkTransferStatus.SNAPSHOT_COMMITTED.ordinal()) {
            throw new IllegalStateException("Transfer Is Not Accepting A Snapshot");
        }
    }

    private void requireTransferNode(String nodeId, String role) {
        if (!enrollmentNodes.containsKey(nodeId) || !connectionsByNode.containsKey(nodeId) || !routes.containsNode(nodeId)) {
            throw new IllegalStateException("Transfer " + role + " Is Not An Online Routed Node");
        }
    }

    private void requireStateTransferSession(String nodeId, String role) {
        WebSocket connection = connectionsByNode.get(nodeId);
        Session session = connection == null ? null : sessions.get(connection);
        if (session == null || !sessionScopes(session).contains("state.transfer")) {
            throw new IllegalStateException("Transfer " + role + " State Agent Is Not Available");
        }
    }

    private void requireSharedStateRealm(String sourceNodeId, String targetNodeId) {
        Set<String> sourceRealms = stateRealms(sourceNodeId);
        if (sourceRealms.isEmpty() || stateRealms(targetNodeId).stream().noneMatch(sourceRealms::contains)) {
            throw new IllegalStateException("Transfer Nodes Do Not Share A Player State Realm");
        }
    }

    private Set<String> stateRealms(String nodeId) {
        VelocityNetworkConfig.EnrollmentNode node = enrollmentNodes.get(nodeId);
        if (node == null) {
            return Set.of();
        }
        return node.capabilities().stream().filter(capability -> capability.startsWith("state:") && capability.length() > "state:".length()).map(capability -> capability.substring("state:".length())).collect(Collectors.toUnmodifiableSet());
    }

    private void requireTransferSource(Session session, PlayerTransfer transfer) {
        if (!transfer.sourceNodeId().equals(session.nodeId())) {
            throw new SecurityException("Only The Transfer Source Can Commit Its Snapshot");
        }
    }

    private void requireTransferTarget(Session session, PlayerTransfer transfer) {
        if (!transfer.targetNodeId().equals(session.nodeId())) {
            throw new SecurityException("Only The Transfer Target Can Advance This Checkpoint");
        }
    }

    private void requireTransferMember(Session session, PlayerTransfer transfer) {
        if (!transfer.sourceNodeId().equals(session.nodeId()) && !transfer.targetNodeId().equals(session.nodeId())) {
            throw new SecurityException("Only A Transfer Participant Can Abort It");
        }
    }

    private void deliverSnapshot(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        notifyTransfer(transfer.targetNodeId(), NetworkFrameType.TRANSFER_RECOVER, transfer);
        deliverSnapshotToNode(transfer.targetNodeId(), transfer, snapshot);
    }

    private void deliverSnapshotToNode(String nodeId, PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        WebSocket connection = connectionsByNode.get(nodeId);
        Session session = connection == null ? null : sessions.get(connection);
        if (connection == null || session == null || !sessionScopes(session).contains("state.transfer")) {
            return;
        }
        for (NetworkSnapshotChunk chunk : NetworkTransferCodec.split(transfer.transferId(), snapshot)) {
            send(connection, NetworkFrameType.SNAPSHOT_COMMIT, NetworkChannels.TRANSFER, "snapshot-" + transfer.transferId() + "-" + chunk.chunkIndex(), NetworkTransferCodec.encodeChunk(chunk), Set.of("state.transfer"));
        }
    }

    private void notifyTransfer(String nodeId, NetworkFrameType type, PlayerTransfer transfer) {
        WebSocket connection = connectionsByNode.get(nodeId);
        Session session = connection == null ? null : sessions.get(connection);
        if (connection != null && session != null && sessionScopes(session).contains("state.transfer")) {
            send(connection, type, NetworkChannels.TRANSFER, "transfer-" + transfer.transferId() + "-" + type.code(), NetworkTransferCodec.encodeTransfer(transfer), Set.of("state.transfer"));
        }
    }

    private void failReadiness(PlayerTransfer transfer) {
        CompletableFuture<PlayerTransfer> readiness = transferReadiness.remove(transfer.transferId());
        if (readiness != null) {
            readiness.completeExceptionally(new IllegalStateException(transfer.failure().isBlank() ? "Player Transfer Aborted" : transfer.failure()));
        }
    }

    private void recoverTransfers(String nodeId) {
        long now = Instant.now().toEpochMilli();
        store.recoverableTransfers(config.networkId(), now).thenAccept(transfers -> transfers.stream().filter(transfer -> transfer.deadline() > now && (transfer.sourceNodeId().equals(nodeId) || transfer.targetNodeId().equals(nodeId))).forEach(transfer -> reconcileTransferLocation(transfer).thenAccept(reconciled -> recoverTransfer(nodeId, reconciled)).exceptionally(throwable -> {
            logger.warn("Failed to reconcile player transfer {}", transfer.transferId(), throwable);
            return null;
        }))).exceptionally(throwable -> {
            logger.warn("Failed to recover player transfers for {}", nodeId, throwable);
            return null;
        });
    }

    private void recoverOwnership(String nodeId) {
        long now = Instant.now().toEpochMilli();
        routes.routes().values().stream().filter(route -> route.nodeId().equals(nodeId)).map(NetworkRoute::routeName).map(proxyServer::getServer).flatMap(Optional::stream).flatMap(server -> server.getPlayersConnected().stream()).map(Player::getUniqueId).distinct().forEach(playerId -> store.getActiveTransfer(config.networkId(), playerId, now).thenCompose(active -> active.isPresent() ? CompletableFuture.<PlayerLease>completedFuture(null) : store.claimOwnership(config.networkId(), playerId, nodeId, now)).thenAccept(lease -> {
            if (lease != null && lease.ownerNodeId().equals(nodeId) && lease.pendingNodeId().isBlank()) {
                notifyOwnership(nodeId, lease);
            }
        }).exceptionally(throwable -> {
            logger.warn("Failed to recover player ownership on {}", nodeId, throwable);
            return null;
        }));
    }

    private CompletableFuture<PlayerTransfer> reconcileTransferLocation(PlayerTransfer transfer) {
        if (transfer.status() != NetworkTransferStatus.TARGET_READY) {
            return CompletableFuture.completedFuture(transfer);
        }
        Player player = proxyServer.getPlayer(transfer.playerId()).orElse(null);
        String routeName = player == null ? "" : player.getCurrentServer().map(server -> server.getServerInfo().getName().toLowerCase(Locale.ROOT)).orElse("");
        NetworkRoute route = routes.route(routeName);
        if (route == null || !route.nodeId().equals(transfer.targetNodeId())) {
            return CompletableFuture.completedFuture(transfer);
        }
        return store.markConnected(transfer.transferId(), Instant.now().toEpochMilli());
    }

    private void recoverTransfer(String nodeId, PlayerTransfer transfer) {
        if (transfer.status() == NetworkTransferStatus.APPLIED) {
            store.commitTransfer(transfer.transferId(), Instant.now().toEpochMilli()).thenAccept(committed -> {
                notifyTransfer(committed.sourceNodeId(), NetworkFrameType.TRANSFER_COMMIT, committed);
                notifyTransfer(committed.targetNodeId(), NetworkFrameType.TRANSFER_COMMIT, committed);
            }).exceptionally(throwable -> {
                logger.warn("Failed to commit recovered player transfer {}", transfer.transferId(), throwable);
                return null;
            });
            return;
        }
        if (transfer.sourceNodeId().equals(nodeId)) {
            notifyTransfer(nodeId, transfer.status() == NetworkTransferStatus.SOURCE_LEASED ? NetworkFrameType.LEASE_GRANTED : NetworkFrameType.TRANSFER_RECOVER, transfer);
            if (transfer.status().ordinal() >= NetworkTransferStatus.SNAPSHOT_COMMITTED.ordinal() && !transfer.snapshotId().isBlank()) {
                store.getSnapshot(transfer.snapshotId()).thenAccept(snapshot -> snapshot.ifPresent(value -> deliverSnapshotToNode(nodeId, transfer, value)));
            }
        }
        if (!transfer.targetNodeId().equals(nodeId)) {
            return;
        }
        notifyTransfer(nodeId, NetworkFrameType.TRANSFER_RECOVER, transfer);
        if (transfer.status().ordinal() >= NetworkTransferStatus.SNAPSHOT_COMMITTED.ordinal() && !transfer.snapshotId().isBlank()) {
            store.getSnapshot(transfer.snapshotId()).thenAccept(snapshot -> snapshot.ifPresent(value -> {
                deliverSnapshot(transfer, value);
                if (transfer.status().ordinal() >= NetworkTransferStatus.CONNECTED.ordinal()) {
                    notifyTransfer(nodeId, NetworkFrameType.PLAYER_CONNECTED, transfer);
                }
            }));
        }
    }

    private void expireTransfers(long now) {
        store.recoverableTransfers(config.networkId(), now).thenCompose(transfers -> store.expireTransfers(config.networkId(), now).thenApply(expired -> transfers.stream().filter(transfer -> transfer.deadline() <= now).toList())).thenAccept(expired -> expired.forEach(transfer -> store.getTransfer(transfer.transferId()).thenAccept(stored -> stored.ifPresent(timedOut -> {
            snapshotUploads.removeTransfer(timedOut.transferId());
            failReadiness(timedOut);
            notifyTransfer(timedOut.sourceNodeId(), NetworkFrameType.TRANSFER_ABORT, timedOut);
            notifyTransfer(timedOut.targetNodeId(), NetworkFrameType.TRANSFER_ABORT, timedOut);
        })))).exceptionally(throwable -> {
            logger.warn("Failed to expire player transfers", throwable);
            return null;
        });
    }

    private void playerRoute(WebSocket connection, Session session, NetworkFrame frame) {
        requireScope(session, "players.route");
        NetworkPlayerRoute request = NetworkPlayerRouteCodec.decode(frame.payload());
        if (!routes.contains(request.routeName()) || !acceptsRoute(request.routeName())) {
            throw new IllegalStateException("Network Player Route Is Not Available");
        }
        RegisteredServer server = proxyServer.getServer(request.routeName()).orElseThrow(() -> new IllegalStateException("Network Player Route Is Not Registered"));
        var player = proxyServer.getPlayer(request.playerId()).orElseThrow(() -> new IllegalStateException("Network Player Is Not Connected"));
        long now = Instant.now().toEpochMilli();
        NetworkRoute targetRoute = routes.route(request.routeName());
        store.getActiveTransfer(config.networkId(), request.playerId(), now).whenComplete((active, transferFailure) -> {
            if (transferFailure != null) {
                sendError(connection, frame.context().requestId(), rootMessage(transferFailure));
                return;
            }
            PlayerTransfer transfer = active.orElse(null);
            if (transfer != null && (!transfer.targetNodeId().equals(targetRoute.nodeId()) || transfer.status() != NetworkTransferStatus.TARGET_READY)) {
                sendError(connection, frame.context().requestId(), "Player State Transfer Is Not Ready For This Route");
                return;
            }
            connectPlayer(connection, session, frame, request, server, player, now);
        });
    }

    private void connectPlayer(WebSocket connection, Session session, NetworkFrame frame, NetworkPlayerRoute request, RegisteredServer server, Player player, long now) {
        store.appendAudit(config.networkId(), session.nodeId(), "player.route.requested", request.routeName(), NetworkPayloads.sha256(frame.payload()), now).whenComplete((unused, auditFailure) -> {
            if (auditFailure != null) {
                sendError(connection, frame.context().requestId(), rootMessage(auditFailure));
                return;
            }
            player.createConnectionRequest(server).connect().whenComplete((result, throwable) -> {
                if (throwable != null) {
                    store.appendAudit(config.networkId(), session.nodeId(), "player.route.failed", request.routeName(), throwable.getClass().getSimpleName(), Instant.now().toEpochMilli()).whenComplete((failedAudit, auditThrowable) -> sendError(connection, frame.context().requestId(), auditThrowable == null ? rootMessage(throwable) : rootMessage(auditThrowable)));
                    return;
                }
                NetworkPlayerRouteStatus status = NetworkPlayerRouteStatus.valueOf(result.getStatus().name());
                NetworkPlayerRouteResult response = new NetworkPlayerRouteResult(status, request.routeName());
                store.appendAudit(config.networkId(), session.nodeId(), "player.route.completed", request.routeName(), status.name(), Instant.now().toEpochMilli()).whenComplete((completedAudit, auditThrowable) -> {
                    if (auditThrowable != null) {
                        sendError(connection, frame.context().requestId(), rootMessage(auditThrowable));
                    } else {
                        send(connection, NetworkFrameType.RESPONSE, NetworkChannels.TRANSFER, frame.context().requestId(), NetworkPlayerRouteCodec.encodeResult(response), sessionScopes(session));
                    }
                });
            });
        });
    }

    private void reconcileRoutes(String actorNodeId, byte[] payload) {
        NetworkRouteSet desired = NetworkRouteSetCodec.decode(payload);
        String fingerprint = NetworkPayloads.sha256(payload);
        routes.reconcile(desired, fingerprint, () -> reloadManagedNodes(actorNodeId), () -> store.appendAudit(config.networkId(), actorNodeId, "routes.reconciled", String.valueOf(desired.revision()), fingerprint, Instant.now().toEpochMilli()).join());
    }

    private void setNodeMode(String actorNodeId, NetworkNodeMode mode) {
        if (!enrollmentNodes.containsKey(mode.nodeId())) {
            throw new IllegalArgumentException("Network Node Is Not Runtime Managed");
        }
        if (!connectionsByNode.containsKey(mode.nodeId())) {
            throw new IllegalStateException("Network Node Is Offline");
        }
        RegisteredServer source = mode.status() == NetworkNodeStatus.MAINTENANCE ? routes.serverForNode(mode.nodeId()) : null;
        RegisteredServer destination = source == null || source.getPlayersConnected().isEmpty() ? null : maintenanceDestination(source.getServerInfo().getName());
        if (source != null && !source.getPlayersConnected().isEmpty() && destination == null) {
            throw new IllegalStateException("No Healthy Maintenance Destination Is Available");
        }
        NetworkNodeStatus previous = nodeModes.get(mode.nodeId());
        if (mode.status() == NetworkNodeStatus.ONLINE) {
            nodeModes.remove(mode.nodeId());
        } else {
            nodeModes.put(mode.nodeId(), mode.status());
        }
        long now = Instant.now().toEpochMilli();
        try {
            NetworkNode updated = store.updateNodeStatus(config.networkId(), mode.nodeId(), mode.status(), now).join();
            store.appendAudit(config.networkId(), actorNodeId, "node.mode", mode.nodeId(), mode.status().name(), now).join();
            publishPresence(presence(updated, mode.status(), now));
        } catch (RuntimeException exception) {
            if (previous == null) {
                nodeModes.remove(mode.nodeId());
            } else {
                nodeModes.put(mode.nodeId(), previous);
            }
            throw exception;
        }
        if (source != null && destination != null) {
            source.getPlayersConnected().forEach(player -> player.createConnectionRequest(destination).fireAndForget());
        }
    }

    private void proxyAction(WebSocket connection, Session session, NetworkFrame frame) {
        NetworkProxyAction action = NetworkProxyActionCodec.decode(frame.payload());
        if (action.type() == NetworkProxyActionType.BROADCAST) {
            requireScope(session, "proxy.broadcast");
            store.appendAudit(config.networkId(), session.nodeId(), "proxy.broadcast.requested", "proxy", NetworkPayloads.sha256(frame.payload()), Instant.now().toEpochMilli()).join();
            proxyServer.sendMessage(Component.text(action.value()));
            send(connection, NetworkFrameType.RESPONSE, NetworkChannels.CONTROL, frame.context().requestId(), new byte[0], sessionScopes(session));
            return;
        }
        requireScope(session, "proxy.command");
        store.appendAudit(config.networkId(), session.nodeId(), "proxy.command.requested", "proxy", NetworkPayloads.sha256(frame.payload()), Instant.now().toEpochMilli()).join();
        proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), action.value()).whenComplete((executed, throwable) -> {
            if (throwable != null) {
                sendError(connection, frame.context().requestId(), rootMessage(throwable));
            } else if (!executed) {
                sendError(connection, frame.context().requestId(), "Proxy Command Was Not Accepted");
            } else {
                send(connection, NetworkFrameType.RESPONSE, NetworkChannels.CONTROL, frame.context().requestId(), new byte[0], sessionScopes(session));
            }
        });
    }

    public boolean acceptsRoute(String routeName) {
        return routes.accepts(routeName);
    }

    public InitialRoutingDecision initialRoutingDestination(Player player, String originalRoute) {
        VelocityRouteRegistry.RoutingDecision decision = routes.initialDestination(player, originalRoute);
        return new InitialRoutingDecision(decision.matched(), decision.destination());
    }

    public record InitialRoutingDecision(boolean matched, RegisteredServer destination) {
    }

    private synchronized void reloadManagedNodes(String actorNodeId) {
        VelocityNetworkConfig refreshed;
        try {
            refreshed = VelocityNetworkConfigLoader.load(config.dataDirectory());
        } catch (Exception exception) {
            throw new IllegalStateException("Reload ReSync Network Configuration Failed", exception);
        }
        if (!refreshed.enabled() || !refreshed.networkId().equals(config.networkId()) || !refreshed.nodeId().equals(config.nodeId()) || !refreshed.enrollmentNodes().containsKey(actorNodeId)) {
            throw new IllegalStateException("ReSync Network Identity Changed During Runtime Reconciliation");
        }
        long now = Instant.now().toEpochMilli();
        Map<String, VelocityNetworkConfig.EnrollmentNode> previous = new LinkedHashMap<>(enrollmentNodes);
        for (VelocityNetworkConfig.EnrollmentNode node : refreshed.enrollmentNodes().values()) {
            if (!previous.containsKey(node.nodeId())) {
                store.registerNode(new NetworkNode(config.networkId(), node.nodeId(), node.displayName(), node.role(), node.capabilities(), NetworkNodeStatus.OFFLINE, 0, 0)).join();
            }
            store.seedEnrollment(config.networkId(), node.nodeId(), node.tokenHash(), node.expiresAt(), now).join();
        }
        Set<String> removed = new LinkedHashSet<>(previous.keySet());
        removed.removeAll(refreshed.enrollmentNodes().keySet());
        for (String nodeId : removed) {
            WebSocket connection = connectionsByNode.remove(nodeId);
            if (connection != null) {
                removeSession(connection, "Network Node Removed");
                connection.close(CloseFrame.POLICY_VALIDATION, "Network Node Removed");
            }
            events.remove(nodeId);
            store.revokeNode(config.networkId(), nodeId, now).join();
            nodeModes.remove(nodeId);
            NetworkNodeMetrics metrics = latestMetrics.get(nodeId);
            publishPresence(new NetworkNodePresence(config.networkId(), nodeId, NetworkNodeStatus.REVOKED, metrics == null ? 0 : metrics.players(), metrics == null ? 0 : metrics.capacity(), metrics == null ? -1 : metrics.tps(), metrics == null ? -1 : metrics.mspt(), metrics == null ? 0 : metrics.heapUsed(), metrics == null ? 0 : metrics.heapMaximum(), now));
        }
        enrollmentNodes.clear();
        enrollmentNodes.putAll(refreshed.enrollmentNodes());
        connectionsByNode.keySet().forEach(nodeId -> {
            WebSocket connection = connectionsByNode.get(nodeId);
            Session session = connection == null ? null : sessions.get(connection);
            if (session != null && sessionScopes(session).contains("events.consume")) {
                events.deliver(nodeId);
            } else {
                events.remove(nodeId);
            }
            VelocityNetworkConfig.EnrollmentNode previousNode = previous.get(nodeId);
            if (session != null && sessionScopes(session).contains("presence.read") && (previousNode == null || !previousNode.capabilities().contains("observe"))) {
                sendPresenceSnapshot(connection);
            }
        });
    }

    public RegisteredServer maintenanceDestination(String sourceRoute) {
        return routes.maintenanceDestination(sourceRoute);
    }

    private void sendPresenceSnapshot(WebSocket connection) {
        store.listNodes(config.networkId()).thenCombine(store.listNodeMetrics(config.networkId()), (nodes, metrics) -> {
            metrics.forEach(value -> latestMetrics.put(value.nodeId(), value));
            return nodes;
        }).thenAccept(nodes -> nodes.forEach(node -> send(connection, NetworkFrameType.PRESENCE_SNAPSHOT, NetworkChannels.PRESENCE, "presence-snapshot", NetworkNodePresenceCodec.encode(presence(node, node.status(), node.heartbeatAt())), Set.of("presence.read")))).exceptionally(throwable -> {
            logger.warn("Failed to send network presence snapshot", throwable);
            return null;
        });
    }

    private void publishPresence(NetworkNodePresence presence) {
        byte[] payload = NetworkNodePresenceCodec.encode(presence);
        sessions.forEach((connection, session) -> {
            if (sessionScopes(session).contains("presence.read")) {
                send(connection, NetworkFrameType.PRESENCE_DELTA, NetworkChannels.PRESENCE, "presence-delta", payload, Set.of("presence.read"));
            }
        });
    }

    private NetworkNodePresence presence(NetworkNode node, NetworkNodeStatus status, long observedAt) {
        NetworkNodeMetrics metrics = latestMetrics.get(node.nodeId());
        if (metrics == null) {
            return new NetworkNodePresence(config.networkId(), node.nodeId(), status, 0, 0, -1, -1, 0, 0, observedAt);
        }
        return new NetworkNodePresence(config.networkId(), node.nodeId(), status, metrics.players(), metrics.capacity(), metrics.tps(), metrics.mspt(), metrics.heapUsed(), metrics.heapMaximum(), Math.max(observedAt, metrics.observedAt()));
    }

    private void sendError(WebSocket connection, String requestId, String message) {
        send(connection, NetworkFrameType.ERROR, NetworkChannels.CONTROL, requestId, message.getBytes(StandardCharsets.UTF_8), Set.of());
    }

    private void send(WebSocket connection, NetworkFrameType type, String channel, String requestId, byte[] payload, Set<String> scopes) {
        if (connection == null || !connection.isOpen()) {
            return;
        }
        NetworkRequestContext context = new NetworkRequestContext(PROTOCOL_VERSION, config.networkId(), config.nodeId(), requestId, Instant.now().plusSeconds(10).toEpochMilli(), scopes);
        connection.send(codec.encode(new NetworkFrame(context, channel, type, payload)));
    }

    private String header(ClientHandshake handshake, String name) {
        String value = handshake.getFieldValue(name);
        return value == null ? "" : value.trim();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Session(String nodeId, Set<String> scopes) {
    }

    private record PendingReconciliation(String nodeId, CompletableFuture<Void> result) {
    }
}
