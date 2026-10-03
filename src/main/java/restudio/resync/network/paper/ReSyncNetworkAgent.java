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
import restudio.resync.migration.MigrationFence;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class ReSyncNetworkAgent {
    private static final int PROTOCOL_VERSION = 1;
    private static final long REQUEST_TIMEOUT_SECONDS = 10;
    private static final int MAX_HELD_TRANSFER_EVENTS = 1_024;
    private static final long MAX_HELD_TRANSFER_BYTES = 4L * 1024L * 1024L;
    private final ReSync plugin;
    private final ReSyncNetworkAgentConfig config;
    private final NetworkFrameCodec codec;
    private final NetworkPlayerStateReconciler stateReconciler;
    private final PaperPlayerDataMutationAdmission playerDataAdmission;
    private final PaperPlayerDataMutationAdmission.Installation playerDataInstallation;
    private final boolean playerDataAdmissionOwner;
    private final NetworkPersistenceDrainController persistenceDrain;
    private final NetworkCredentialStore credentialStore;
    private final NetworkTransferRecoveryStore transferRecovery;
    private final NetworkPersistenceDrainController.Registration credentialRegistration;
    private final NetworkPersistenceDrainController.Registration transferRecoveryRegistration;
    private final NetworkPersistenceDrainController.Registration producerRegistration;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean shutdownDrainInProgress = new AtomicBoolean();
    private final AtomicBoolean shutdownFinalized = new AtomicBoolean();
    private final AtomicBoolean persistenceQuiesced = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean reconnectRequested = new AtomicBoolean();
    private final AtomicBoolean unavailableReported = new AtomicBoolean();
    private final AtomicInteger reconnectFailures = new AtomicInteger();
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<String, CompletableFuture<NetworkFrame>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, NetworkFrameType> pendingRequestTypes = new ConcurrentHashMap<>();
    private final Map<String, NetworkNodePresence> presence = new ConcurrentHashMap<>();
    private final Map<String, PlayerTransfer> activeTransfers = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerLease> ownership = new ConcurrentHashMap<>();
    private final Map<String, PlayerStateSnapshot> transferSnapshots = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PlayerTransfer>> transferReadiness = new ConcurrentHashMap<>();
    private final Map<String, SnapshotAssembly> incomingSnapshots = new ConcurrentHashMap<>();
    private final Set<String> transferWork = ConcurrentHashMap.newKeySet();
    private final Object transferAdmissionMonitor = new Object();
    private final Deque<TransferAdmissionEvent> heldTransferEvents = new ArrayDeque<>();
    private final Map<String, PlayerTransfer> overflowedTransferResumes = new LinkedHashMap<>();
    private final ThreadLocal<Boolean> replayingTransferEvents = ThreadLocal.withInitial(() -> false);
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object lifecycleMonitor = new Object();
    private volatile Client client;
    private final AtomicBoolean authorized = new AtomicBoolean();
    private volatile String credential;
    private volatile String recoveryFailure;
    private volatile TransferHandler transferHandler;
    private final Object transferHandlerMonitor = new Object();
    private TransferHandlerSwap stagedTransferHandler;
    private TransferReplacementFence transferReplacementFence;
    private boolean transferReplacementBlocked;
    private boolean transferReplacementDraining;
    private boolean transferAdmissionBackpressured;
    private long transferAdmissionBackpressureCount;
    private String transferAdmissionDiagnostic = "";
    private long transferAdmissionSequence;
    private int heldTransferEventCount;
    private long heldTransferBytes;
    private int transferCallbackAdmissions;
    private BukkitTask heartbeatTask;
    private CompletableFuture<ShutdownResult> shutdownAttempt;

    public ReSyncNetworkAgent(ReSync plugin, ReSyncNetworkAgentConfig config) {
        this(plugin, config, MigrationFence.systemWide());
    }

    public ReSyncNetworkAgent(ReSync plugin, ReSyncNetworkAgentConfig config, MigrationFence fence) {
        this(plugin, config, fence, null);
    }

    public ReSyncNetworkAgent(ReSync plugin, ReSyncNetworkAgentConfig config, MigrationFence fence,
                              PaperPlayerDataMutationAdmission admission) {
        this.plugin = plugin;
        this.config = config;
        this.codec = new NetworkFrameCodec(config.maximumFrameBytes(), config.maximumPayloadBytes());
        Path credentialFile = config.credentialFile();
        Path networkRoot = credentialFile.getParent();
        if (networkRoot == null) {
            throw new IllegalArgumentException("ReSync Network Credential Root Is Required");
        }
        this.persistenceDrain = new NetworkPersistenceDrainController(networkRoot, fence, NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
        PaperPlayerDataMutationAdmission admissionCandidate = null;
        PaperPlayerDataMutationAdmission.Installation installation = null;
        boolean admissionOwner = false;
        NetworkCredentialStore credentialStoreCandidate = null;
        NetworkTransferRecoveryStore transferRecoveryCandidate = null;
        NetworkPersistenceDrainController.Registration registeredCredential = null;
        NetworkPersistenceDrainController.Registration registeredRecovery = null;
        NetworkPersistenceDrainController.Registration registeredProducer = null;
        NetworkPlayerStateReconciler reconciler = null;
        String credentialCandidate = null;
        try {
            PaperPlayerDataMutationAdmission.Installation existingInstallation = PaperPlayerDataMutationAdmission.sharedInstallation();
            admissionOwner = admission == null && existingInstallation == null;
            admissionCandidate = admission == null
                ? existingInstallation == null ? PaperPlayerDataMutationAdmission.forBukkitWorlds() : existingInstallation.admission()
                : admission;
            admissionCandidate.refreshFromBukkit();
            installation = PaperPlayerDataMutationAdmission.installShared(admissionCandidate);
            credentialStoreCandidate = new NetworkCredentialStore(credentialFile, config.credential());
            transferRecoveryCandidate = new NetworkTransferRecoveryStore(networkRoot);
            NetworkCredentialStore credentialStore = credentialStoreCandidate;
            NetworkTransferRecoveryStore transferRecovery = transferRecoveryCandidate;
            credentialCandidate = credentialStore.value();
            registeredCredential = this.persistenceDrain.register(credentialStore.persistenceComponent("credential", () -> credential = credentialStore.value()));
            registeredRecovery = this.persistenceDrain.register(transferRecovery.persistenceComponent(this::refreshRecoveredTransfers));
            transferRecovery.snapshot().forEach(transfer -> activeTransfers.put(transfer.transferId(), transfer));
            registeredProducer = this.persistenceDrain.registerProducer(new NetworkPersistenceDrainController.Producer() {
                @Override
                public String owner() {
                    return "network-agent";
                }

                @Override
                public void closeAdmission() {
                    closePersistenceAdmission();
                }

                @Override
                public void resumeAdmission() {
                    try {
                        resumePersistenceAdmission();
                    } catch (IOException exception) {
                        throw new IllegalStateException("Network Persistence Admission Could Not Resume", exception);
                    }
                }

                @Override
                public CompletionStage<NetworkPersistenceDrainController.AbortResult> abortPending() {
                    return abortActiveTransfersForDrain();
                }
            });
            reconciler = new NetworkPlayerStateReconciler(plugin, persistenceDrain, admissionCandidate);
        } catch (RuntimeException exception) {
            if (reconciler != null) {
                reconciler.shutdown();
            }
            if (registeredProducer != null) {
                registeredProducer.close();
            }
            if (registeredRecovery != null) {
                registeredRecovery.close();
            }
            if (registeredCredential != null) {
                registeredCredential.close();
            }
            persistenceDrain.closePersistence();
            if (admissionOwner && admissionCandidate != null && installation != null) {
                try {
                    admissionCandidate.close();
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                PaperPlayerDataMutationAdmission.clearSharedInstallation(installation);
            }
            throw exception;
        }
        this.credentialStore = credentialStoreCandidate;
        this.transferRecovery = transferRecoveryCandidate;
        this.credentialRegistration = registeredCredential;
        this.transferRecoveryRegistration = registeredRecovery;
        this.producerRegistration = registeredProducer;
        this.stateReconciler = reconciler;
        this.playerDataAdmission = admissionCandidate;
        this.playerDataInstallation = installation;
        this.playerDataAdmissionOwner = admissionOwner;
        this.credential = credentialCandidate;
    }

    public NetworkPersistenceDrainController persistenceDrain() {
        return persistenceDrain;
    }

    public PaperPlayerDataMutationAdmission playerDataAdmission() {
        return playerDataAdmission;
    }

    public void requestPlayerDataAdmissionQuiesce() throws IOException {
        requirePrimaryThread();
        closePersistenceAdmission();
    }

    public void drainPlayerDataAdmission(Duration timeout) throws IOException {
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            throw new IOException("Paper Player Data Admission Drain Must Continue Off The Bukkit Main Thread");
        }
        playerDataAdmission.quiesce(timeout);
    }

    public void rediscoverPlayerDataRoots() throws IOException {
        requirePrimaryThread();
        playerDataAdmission.refreshFromBukkit();
    }

    public void resumePlayerDataAdmission() throws IOException {
        requirePrimaryThread();
        resumePersistenceAdmission();
    }

    public void start() {
        requirePrimaryThread();
        if (!config.enabled() || stopping.get() || persistenceQuiesced.get() || !started.compareAndSet(false, true)) {
            return;
        }
        try {
            stateReconciler.start();
            heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sendPresence, config.heartbeatIntervalTicks(), config.heartbeatIntervalTicks());
            connect();
        } catch (RuntimeException exception) {
            started.set(false);
            if (heartbeatTask != null) {
                heartbeatTask.cancel();
                heartbeatTask = null;
            }
            throw exception;
        }
    }

    public CompletionStage<ShutdownResult> shutdown() {
        return shutdown(() -> CompletableFuture.completedFuture(null));
    }

    public CompletionStage<ShutdownResult> shutdown(Supplier<? extends CompletionStage<Void>> finalizer) {
        Objects.requireNonNull(finalizer, "finalizer");
        try {
            prepareForShutdown();
        } catch (IOException | RuntimeException exception) {
            return CompletableFuture.completedFuture(ShutdownResult.failed(rootMessage(exception), persistenceDrain.state()));
        }
        return shutdownAfterPreparation(finalizer);
    }

    public void prepareForShutdown() throws IOException {
        requirePrimaryThread();
        if (shutdownFinalized.get()) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (stopping.compareAndSet(false, true)) {
                persistenceQuiesced.set(true);
                if (heartbeatTask != null) {
                    heartbeatTask.cancel();
                    heartbeatTask = null;
                }
                failPending(new IllegalStateException("ReSync Network Agent Stopped"));
                transferReadiness.values().forEach(future -> future.completeExceptionally(new IllegalStateException("ReSync Network Agent Stopped")));
                transferReadiness.clear();
            }
        }
        prepareSourceTransfersForShutdown();
        stateReconciler.prepareForShutdown();
        if (persistenceDrain.state() == NetworkPersistenceDrainController.State.OPEN) {
            persistenceDrain.beginShutdown();
        }
    }

    private void prepareSourceTransfersForShutdown() throws IOException {
        TransferHandler handler = transferHandler;
        List<PlayerTransfer> sourceTransfers = activeTransfers.values().stream()
            .filter(transfer -> transfer.sourceNodeId().equals(config.nodeId()) && !terminal(transfer.status()))
            .toList();
        if (sourceTransfers.isEmpty()) {
            return;
        }
        if (handler == null) {
            throw new IOException("Network source transfer restoration authority is unavailable");
        }
        for (PlayerTransfer transfer : sourceTransfers) {
            PlayerStateSnapshot snapshot = transferSnapshots.get(transfer.transferId());
            CompletionStage<Void> restoration;
            try {
                PlayerLease lease = new PlayerLease(transfer.networkId(), transfer.playerId(), transfer.sourceNodeId(), "",
                    transfer.fenceEpoch(), 0, transfer.updatedAt());
                ownership.put(lease.playerId(), lease);
                handler.ownershipChanged(lease);
                restoration = handler.abortForShutdown(transfer, snapshot);
                if (restoration == null) {
                    throw new IllegalStateException("Network source transfer restoration returned no completion");
                }
            } catch (RuntimeException exception) {
                throw new IOException("Network source transfer " + transfer.transferId() + " could not be restored", exception);
            }
            CompletableFuture<Void> completion = restoration.toCompletableFuture();
            if (!completion.isDone()) {
                throw new IOException("Network source transfer " + transfer.transferId() + " requires post-disable work");
            }
            try {
                completion.join();
            } catch (RuntimeException exception) {
                throw new IOException("Network source transfer " + transfer.transferId() + " could not be restored", exception);
            }
            forgetPreparedSourceTransfer(transfer);
        }
    }

    private void forgetPreparedSourceTransfer(PlayerTransfer transfer) throws IOException {
        try {
            transferRecovery.forget(transfer.transferId());
        } catch (RuntimeException exception) {
            recoveryFailure = rootMessage(exception);
            throw new IOException("Network source transfer recovery could not be removed", exception);
        }
        activeTransfers.remove(transfer.transferId(), transfer);
        transferSnapshots.remove(transfer.transferId());
        incomingSnapshots.remove(transfer.transferId());
        transferWork.removeIf(value -> value.startsWith(transfer.transferId() + ":"));
        CompletableFuture<PlayerTransfer> readiness = transferReadiness.remove(transfer.transferId());
        if (readiness != null) {
            readiness.completeExceptionally(new IllegalStateException("Player Transfer Aborted During Shutdown"));
        }
    }

    public CompletionStage<ShutdownResult> shutdownAfterPreparation(Supplier<? extends CompletionStage<Void>> finalizer) {
        Objects.requireNonNull(finalizer, "finalizer");
        if (!stopping.get()) {
            return CompletableFuture.completedFuture(ShutdownResult.failed(
                "ReSync Network Agent Shutdown Requires Main-Thread Preparation", persistenceDrain.state()));
        }
        synchronized (this) {
            if (shutdownFinalized.get()) {
                return CompletableFuture.completedFuture(ShutdownResult.success());
            }
            if (shutdownAttempt != null && !shutdownAttempt.isDone()) {
                return shutdownAttempt;
            }
            shutdownAttempt = new CompletableFuture<>();
        }
        CompletableFuture<ShutdownResult> attempt = shutdownAttempt;
        if (shutdownDrainInProgress.compareAndSet(false, true)) {
            CompletableFuture.runAsync(() -> {
                ShutdownResult result;
                try {
                    drainPersistenceForShutdown(playerDataAdmission, persistenceDrain,
                        NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
                    persistenceDrain.healthCheckPersistence();
                    CompletionStage<Void> finalization = Objects.requireNonNull(finalizer.get(), "finalization stage");
                    finalization.toCompletableFuture()
                        .orTimeout(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .join();
                    stateReconciler.finalizeShutdown();
                    disconnectAuthorized();
                    Client current = client;
                    client = null;
                    if (current != null) {
                        current.close();
                    }
                    setTransferHandler(null);
                    listeners.clear();
                    presence.clear();
                    activeTransfers.clear();
                    ownership.clear();
                    transferSnapshots.clear();
                    incomingSnapshots.clear();
                    transferWork.clear();
                    if (producerRegistration != null) {
                        producerRegistration.close();
                    }
                    if (transferRecoveryRegistration != null) {
                        transferRecoveryRegistration.close();
                    }
                    if (credentialRegistration != null) {
                        credentialRegistration.close();
                    }
                    persistenceDrain.closePersistence();
                    if (playerDataAdmissionOwner) {
                        playerDataAdmission.close();
                        PaperPlayerDataMutationAdmission.clearSharedInstallation(playerDataInstallation);
                    }
                    shutdownFinalized.set(true);
                    result = ShutdownResult.success();
                } catch (IOException | RuntimeException exception) {
                    Log.warn("ReSync network persistence shutdown did not fully quiesce: " + rootMessage(exception));
                    result = ShutdownResult.failed(rootMessage(exception), persistenceDrain.state());
                } finally {
                    shutdownDrainInProgress.set(false);
                }
                attempt.complete(result);
            });
        }
        return attempt;
    }

    private static void requirePrimaryThread() {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ReSync network agent lifecycle must run on the Bukkit main thread");
        }
    }

    private void scheduleConnect() {
        synchronized (lifecycleMonitor) {
            if (stopping.get()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, this::connect);
        }
    }

    static void drainPersistenceForShutdown(NetworkPersistenceDrainController persistenceDrain, Duration timeout) throws IOException {
        switch (persistenceDrain.state()) {
            case OPEN -> persistenceDrain.quiescePersistence(timeout);
            case FAILED -> persistenceDrain.recoverPersistence(timeout);
            case QUIESCED -> persistenceDrain.retryDegradedPersistence(timeout);
            case CLOSED -> {
            }
            case QUIESCING -> persistenceDrain.quiescePersistence(timeout);
        }
    }

    static void drainPersistenceForShutdown(PaperPlayerDataMutationAdmission playerDataAdmission,
                                            NetworkPersistenceDrainController persistenceDrain,
                                            Duration timeout) throws IOException {
        playerDataAdmission.quiesce(timeout);
        drainPersistenceForShutdown(persistenceDrain, timeout);
    }

    public boolean connected() {
        Client current = client;
        return authorized.get() && current != null && current.isOpen();
    }

    public boolean hasActiveTransfers() {
        synchronized (transferAdmissionMonitor) {
            return !activeTransfers.isEmpty() || !transferWork.isEmpty() || transferCallbackAdmissions > 0;
        }
    }

    public void reconnect() {
        if (stopping.get() || persistenceQuiesced.get()) return;
        reconnectRequested.set(true);
        Client current = client;
        if (current != null && current.isOpen()) {
            current.close(1000, "Network Configuration Reloaded");
            return;
        }
        reconnectRequested.set(false);
        scheduleConnect();
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
        setTransferHandler(transferHandler, true);
    }

    public void setTransferHandler(TransferHandler transferHandler, boolean resumeTransfers) {
        synchronized (transferHandlerMonitor) {
            this.transferHandler = transferHandler;
            stagedTransferHandler = null;
        }
        if (resumeTransfers && transferHandler != null) {
            activeTransfers.values().forEach(transfer -> {
                if (terminal(transfer.status())) {
                    finishTransfer(transfer, transfer.status() != NetworkTransferStatus.COMMITTED);
                } else {
                    resumeTransfer(transfer);
                }
            });
        }
    }

    public Optional<TransferReplacementFence> tryBeginTransferReplacement() {
        synchronized (transferAdmissionMonitor) {
            if (stopping.get() || transferReplacementFence != null || transferCallbackAdmissions > 0
                || !activeTransfers.isEmpty() || !transferWork.isEmpty()) {
                return Optional.empty();
            }
            transferReplacementBlocked = true;
            transferAdmissionBackpressured = false;
            transferAdmissionBackpressureCount = 0;
            transferAdmissionDiagnostic = "";
            TransferReplacementFence fence = new TransferReplacementFence();
            transferReplacementFence = fence;
            return Optional.of(fence);
        }
    }

    private TransferHandler acquireTransferHandler() {
        synchronized (transferAdmissionMonitor) {
            if (transferReplacementBlocked && !Boolean.TRUE.equals(replayingTransferEvents.get())) {
                return null;
            }
            TransferHandler handler = transferHandler;
            if (handler != null) {
                transferCallbackAdmissions++;
            }
            return handler;
        }
    }

    private void releaseTransferHandler() {
        synchronized (transferAdmissionMonitor) {
            transferCallbackAdmissions--;
            transferAdmissionMonitor.notifyAll();
        }
    }

    private boolean deferTransferEvent(Runnable event) {
        return deferTransferEvent(event, 1);
    }

    private boolean deferTransferEvent(Runnable event, long bytes) {
        Objects.requireNonNull(event, "event");
        synchronized (transferAdmissionMonitor) {
            if (!transferReplacementBlocked || Boolean.TRUE.equals(replayingTransferEvents.get())) {
                return false;
            }
            try {
                enqueueTransferEvent(event, bytes);
            } catch (IllegalStateException exception) {
                markTransferAdmissionBackpressure(exception.getMessage());
                throw exception;
            }
            return true;
        }
    }

    private void enqueueTransferEvent(Runnable event, long bytes) {
        long cost = Math.max(1, bytes);
        if (heldTransferEventCount >= MAX_HELD_TRANSFER_EVENTS || heldTransferBytes + cost > MAX_HELD_TRANSFER_BYTES) {
            throw new IllegalStateException("ReSync transfer replacement admission queue is full");
        }
        heldTransferEvents.addLast(new TransferAdmissionEvent(++transferAdmissionSequence, cost, event));
        heldTransferEventCount++;
        heldTransferBytes += cost;
    }

    private void replayTransferEvents(TransferReplacementFence fence) {
        while (true) {
            TransferAdmissionEvent deferred;
            String overflowedTransferId = null;
            synchronized (transferAdmissionMonitor) {
                deferred = heldTransferEvents.peekFirst();
                if (deferred == null && !overflowedTransferResumes.isEmpty()) {
                    PlayerTransfer transfer = overflowedTransferResumes.entrySet().iterator().next().getValue();
                    overflowedTransferId = transfer.transferId();
                    deferred = new TransferAdmissionEvent(++transferAdmissionSequence, 1, () -> resumeTransfer(transfer));
                }
                if (deferred == null) {
                    if (transferAdmissionBackpressured) {
                        transferReplacementDraining = false;
                        transferAdmissionMonitor.notifyAll();
                        return;
                    }
                    transferReplacementFence = null;
                    transferReplacementBlocked = false;
                    transferReplacementDraining = false;
                    transferAdmissionMonitor.notifyAll();
                    fence.completion.complete(null);
                    return;
                }
            }
            replayingTransferEvents.set(true);
            try {
                deferred.action().run();
            } catch (RuntimeException exception) {
                markTransferAdmissionBackpressure("Deferred transfer event failed: " + rootMessage(exception));
                Log.warn("ReSync deferred network transfer callback failed: " + rootMessage(exception));
                return;
            } finally {
                replayingTransferEvents.remove();
            }
            synchronized (transferAdmissionMonitor) {
                if (overflowedTransferId != null) {
                    overflowedTransferResumes.remove(overflowedTransferId);
                } else if (heldTransferEvents.peekFirst() == deferred) {
                    heldTransferEvents.removeFirst();
                    heldTransferEventCount--;
                    heldTransferBytes -= deferred.bytes();
                }
            }
        }
    }

    private void publishTransfer(PlayerTransfer transfer) {
        synchronized (transferAdmissionMonitor) {
            if (transferReplacementBlocked && !Boolean.TRUE.equals(replayingTransferEvents.get())) {
                long bytes = NetworkTransferCodec.encodeTransfer(transfer).length;
                try {
                    enqueueTransferEvent(() -> {
                        publishTransferAdmitted(transfer);
                        resumeTransfer(transfer);
                    }, bytes);
                } catch (IllegalStateException backpressure) {
                    if (!rememberTransfer(transfer)) {
                        markTransferAdmissionBackpressure("Transfer recovery persistence is full; admission remains fenced");
                        throw new IllegalStateException("ReSync transfer replacement admission is backpressured", backpressure);
                    }
                    activeTransfers.put(transfer.transferId(), transfer);
                    if (overflowedTransferResumes.size() >= MAX_HELD_TRANSFER_EVENTS
                        && !overflowedTransferResumes.containsKey(transfer.transferId())) {
                        markTransferAdmissionBackpressure("Transfer recovery replay backlog is full; admission remains fenced");
                    } else {
                        overflowedTransferResumes.put(transfer.transferId(), transfer);
                    }
                    Log.warn("ReSync transfer replacement admission is backpressured; transfer recovery remains retained");
                }
                return;
            }
            publishTransferAdmitted(transfer);
        }
    }

    private void publishTransferAdmitted(PlayerTransfer transfer) {
        if (!rememberTransfer(transfer)) {
            markTransferAdmissionBackpressure("Transfer recovery persistence is full; admission remains fenced");
            throw new IllegalStateException("ReSync transfer recovery persistence is unavailable");
        }
        activeTransfers.put(transfer.transferId(), transfer);
    }

    private void releaseTransferReplacement(TransferReplacementFence fence) {
        synchronized (transferAdmissionMonitor) {
            if (transferReplacementFence != fence || !transferReplacementBlocked) {
                return;
            }
            if (transferReplacementDraining) {
                return;
            }
            transferReplacementDraining = true;
        }
        CompletableFuture.runAsync(() -> replayTransferEvents(fence)).whenComplete((unused, failure) -> {
            if (failure != null) {
                fence.completion.completeExceptionally(failure);
                Log.error("ReSync transfer replacement replay failed; admission remains fail-closed: " + rootMessage(failure));
            }
        });
    }

    private void markTransferAdmissionBackpressure(String detail) {
        synchronized (transferAdmissionMonitor) {
            transferAdmissionBackpressured = true;
            transferAdmissionBackpressureCount++;
            transferAdmissionDiagnostic = detail == null || detail.isBlank()
                ? "Transfer replacement admission remains fenced"
                : detail;
            transferAdmissionMonitor.notifyAll();
        }
    }

    public TransferAdmissionHealth transferAdmissionHealth() {
        synchronized (transferAdmissionMonitor) {
            return new TransferAdmissionHealth(transferReplacementBlocked, transferReplacementDraining,
                heldTransferEventCount, heldTransferBytes, overflowedTransferResumes.size(),
                transferAdmissionBackpressured, transferAdmissionBackpressureCount, transferAdmissionDiagnostic);
        }
    }

    public TransferHandlerSwap stageTransferHandler(TransferHandler replacement) {
        synchronized (transferHandlerMonitor) {
            if (stagedTransferHandler != null) {
                throw new IllegalStateException("ReSync Network Transfer Handler Replacement Is Already Staged");
            }
            TransferHandlerSwap staged = new TransferHandlerSwap(transferHandler, replacement);
            stagedTransferHandler = staged;
            return staged;
        }
    }

    public void commitTransferHandler(TransferHandlerSwap staged, boolean resumeTransfers) {
        Objects.requireNonNull(staged, "staged");
        synchronized (transferHandlerMonitor) {
            if (stagedTransferHandler != staged) {
                throw new IllegalStateException("ReSync Network Transfer Handler Replacement Is Not Staged");
            }
            transferHandler = staged.replacement();
            stagedTransferHandler = null;
        }
        if (resumeTransfers && staged.replacement() != null) {
            resumeTransferHandler();
        }
    }

    public void rollbackTransferHandler(TransferHandlerSwap staged) {
        Objects.requireNonNull(staged, "staged");
        synchronized (transferHandlerMonitor) {
            if (stagedTransferHandler == staged) {
                stagedTransferHandler = null;
            } else if (transferHandler == staged.replacement()) {
                transferHandler = staged.previous();
            }
        }
    }

    public void resumeTransferHandler() {
        TransferHandler current = transferHandler;
        if (current != null) {
            setTransferHandler(current, true);
        }
    }

    public record TransferHandlerSwap(TransferHandler previous, TransferHandler replacement) {
    }

    public final class TransferReplacementFence {
        private boolean released;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        public void commit() {
            release();
        }

        public void rollback() {
            release();
        }

        public CompletionStage<Void> completion() {
            return completion;
        }

        private void release() {
            synchronized (transferAdmissionMonitor) {
                if (released) {
                    return;
                }
                released = true;
            }
            releaseTransferReplacement(this);
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
            publishTransfer(transfer);
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
            publishTransfer(transfer);
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
        return request(NetworkChannels.TRANSFER, type, NetworkTransferCodec.encodeCheckpoint(checkpoint), Set.of("state.transfer"))
            .thenApply(frame -> NetworkTransferCodec.decodeTransfer(frame.payload()))
            .thenApply(transfer -> {
                publishTransfer(transfer);
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
        if (!started.get() || stopping.get() || persistenceQuiesced.get()) {
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
            reportUnavailable(rootMessage(exception));
            scheduleReconnect();
        }
    }

    private void sendPresence() {
        Client current = client;
        if (persistenceQuiesced.get() || !authorized.get() || current == null || !current.isOpen()) {
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        double[] tps = Bukkit.getTPS();
        double currentTps = tps.length == 0 ? -1 : tps[0];
        NetworkNodeMetrics metrics = new NetworkNodeMetrics(config.networkId(), config.nodeId(), Bukkit.getOnlinePlayers().size(), config.capacity() > 0 ? config.capacity() : Bukkit.getMaxPlayers(), currentTps, Bukkit.getAverageTickTime(), runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), Instant.now().toEpochMilli());
        send(current, NetworkChannels.PRESENCE, NetworkFrameType.PRESENCE_DELTA, nextRequestId(), NetworkPresenceCodec.encode(metrics), Set.of("node.heartbeat", "presence.write"));
    }

    private CompletableFuture<NetworkFrame> request(String channel, NetworkFrameType type, byte[] payload, Set<String> scopes) {
        NetworkPersistenceDrainController.Lease lease;
        try {
            lease = persistenceDrain.acquire("network-request:" + type.name());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        CompletableFuture<NetworkFrame> request = requestRaw(channel, type, payload, scopes);
        return request.whenComplete((frame, throwable) -> lease.close());
    }

    private CompletableFuture<NetworkFrame> requestRaw(String channel, NetworkFrameType type, byte[] payload, Set<String> scopes) {
        Client current = client;
        if (!authorized.get() || current == null || !current.isOpen()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ReSync Network Is Not Connected"));
        }
        String requestId = nextRequestId();
        CompletableFuture<NetworkFrame> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        pendingRequestTypes.put(requestId, type);
        try {
            send(current, channel, type, requestId, payload, scopes);
        } catch (RuntimeException exception) {
            pendingRequests.remove(requestId, future);
            pendingRequestTypes.remove(requestId, type);
            future.completeExceptionally(exception);
            return future;
        }
        return future.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((frame, throwable) -> {
            pendingRequests.remove(requestId, future);
            pendingRequestTypes.remove(requestId, type);
        });
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
        boolean pendingDrainAbortResponse = frame.channel().equals(NetworkChannels.TRANSFER)
            && frame.type() == NetworkFrameType.TRANSFER_ABORT
            && pendingRequests.containsKey(frame.context().requestId())
            && pendingRequestTypes.get(frame.context().requestId()) == NetworkFrameType.TRANSFER_ABORT;
        if (stopping.get() && !pendingDrainAbortResponse) {
            return;
        }
        if (persistenceQuiesced.get() && frame.type() != NetworkFrameType.RESPONSE && frame.type() != NetworkFrameType.ERROR && !pendingDrainAbortResponse) {
            return;
        }
        if (frame.type() == NetworkFrameType.ENROLL_ACK) {
            String issuedCredential = new String(frame.payload(), StandardCharsets.UTF_8).trim();
            try {
                try (NetworkPersistenceDrainController.Lease ignored = persistenceDrain.acquire("credential-enrollment")) {
                    credentialStore.save(issuedCredential);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Persist Network Credential Failed", exception);
            }
            credential = credentialStore.value();
            boolean connected = authorized.compareAndSet(false, true);
            reconnectFailures.set(0);
            unavailableReported.set(false);
            Log.info("ReSync network node enrolled as " + config.nodeId());
            sendPresence();
            if (connected) {
                notifyConnected();
            }
            return;
        }
        if (frame.type() == NetworkFrameType.RESPONSE && frame.context().requestId().equals("session")) {
            boolean connected = authorized.compareAndSet(false, true);
            reconnectFailures.set(0);
            unavailableReported.set(false);
            Log.info("ReSync network node connected as " + config.nodeId());
            sendPresence();
            if (connected) {
                notifyConnected();
            }
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
            if (deferTransferEvent(() -> applyOwnershipClaim(lease))) {
                return;
            }
            applyOwnershipClaim(lease);
        }
    }

    private void applyOwnershipClaim(PlayerLease lease) {
        synchronized (transferAdmissionMonitor) {
            ownership.put(lease.playerId(), lease);
            TransferHandler handler = acquireTransferHandler();
            if (handler == null) {
                return;
            }
            try {
                handler.ownershipChanged(lease);
            } catch (RuntimeException exception) {
                Log.warn("ReSync player ownership callback failed: " + rootMessage(exception));
            } finally {
                releaseTransferHandler();
            }
        }
    }

    private void respondToHub(NetworkFrame request, Throwable throwable) {
        Client current = client;
        if (!authorized.get() || current == null || !current.isOpen()) {
            return;
        }
        if (throwable == null) {
            send(current, request.channel(), NetworkFrameType.RESPONSE, request.context().requestId(), new byte[0], Set.of("state.reconcile"));
        } else {
            send(current, request.channel(), NetworkFrameType.ERROR, request.context().requestId(), rootMessage(throwable).getBytes(StandardCharsets.UTF_8), Set.of("state.reconcile"));
        }
    }

    private void handleTransfer(NetworkFrame frame) {
        if (deferTransferEvent(() -> handleTransfer(frame), frame.payload().length)) {
            return;
        }
        synchronized (transferAdmissionMonitor) {
            transferCallbackAdmissions++;
            try {
                handleTransferAdmitted(frame);
            } finally {
                transferCallbackAdmissions--;
                transferAdmissionMonitor.notifyAll();
            }
        }
    }

    private void handleTransferAdmitted(NetworkFrame frame) {
        if (frame.type() == NetworkFrameType.LEASE_GRANTED || frame.type() == NetworkFrameType.TARGET_READY || frame.type() == NetworkFrameType.TRANSFER_RECOVER || frame.type() == NetworkFrameType.PLAYER_CONNECTED || frame.type() == NetworkFrameType.TRANSFER_COMMIT || frame.type() == NetworkFrameType.TRANSFER_ABORT) {
            PlayerTransfer transfer = NetworkTransferCodec.decodeTransfer(frame.payload());
            publishTransfer(transfer);
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
        if (deferTransferEvent(() -> resumeTransfer(transfer))) {
            return;
        }
        TransferHandler handler = acquireTransferHandler();
        if (handler != null) {
            try {
                handler.recovering(transfer, transfer.sourceNodeId().equals(config.nodeId()));
            } catch (RuntimeException exception) {
                Log.warn("ReSync player transfer recovery callback failed: " + rootMessage(exception));
            } finally {
                releaseTransferHandler();
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
        if (deferTransferEvent(() -> captureTransfer(transfer))) {
            return;
        }
        String workId = transfer.transferId() + ":capture";
        if (!transfer.sourceNodeId().equals(config.nodeId()) || transfer.status() != NetworkTransferStatus.SOURCE_LEASED) {
            return;
        }
        TransferHandler handler = acquireTransferHandler();
        if (handler == null) {
            return;
        }
        if (!transferWork.add(workId)) {
            releaseTransferHandler();
            return;
        }
        NetworkPersistenceDrainController.Lease lease = admit("transfer-capture:" + transfer.transferId());
        if (lease == null) {
            transferWork.remove(workId);
            releaseTransferHandler();
            failTransfer(transfer, "SOURCE_CAPTURE_REJECTED", new IllegalStateException("Network Persistence Admission Is Closed"));
            return;
        }
        try {
            CompletionStage<PlayerStateSnapshot> capture;
            try {
                capture = handler.capture(transfer);
            } finally {
                releaseTransferHandler();
            }
            if (capture == null) {
                throw new IllegalStateException("Transfer Capture Did Not Return A Result");
            }
            capture.toCompletableFuture().thenCompose(snapshot -> commitSnapshot(transfer.transferId(), snapshot)).whenComplete((committed, throwable) -> {
                transferWork.remove(workId);
                lease.close();
                if (throwable != null) {
                    failTransfer(transfer, "SOURCE_CAPTURE_FAILED", throwable);
                }
            });
        } catch (RuntimeException exception) {
            transferWork.remove(workId);
            lease.close();
            failTransfer(transfer, "SOURCE_CAPTURE_FAILED", exception);
        }
    }

    private void receiveSnapshot(NetworkSnapshotChunk chunk) {
        NetworkPersistenceDrainController.Lease lease = admit("transfer-snapshot:" + chunk.transferId());
        if (lease == null) {
            return;
        }
        try {
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
        } finally {
            lease.close();
        }
    }

    private void prepareTransfer(PlayerTransfer transfer) {
        if (deferTransferEvent(() -> prepareTransfer(transfer))) {
            return;
        }
        PlayerStateSnapshot snapshot = transferSnapshots.get(transfer.transferId());
        String workId = transfer.transferId() + ":prepare";
        if (snapshot == null || !transfer.targetNodeId().equals(config.nodeId()) || transfer.status().ordinal() < NetworkTransferStatus.SNAPSHOT_COMMITTED.ordinal() || transfer.status().ordinal() >= NetworkTransferStatus.TARGET_READY.ordinal()) {
            return;
        }
        TransferHandler handler = acquireTransferHandler();
        if (handler == null || !transferWork.add(workId)) {
            if (handler != null) {
                releaseTransferHandler();
            }
            return;
        }
        NetworkPersistenceDrainController.Lease lease = admit("transfer-prepare:" + transfer.transferId());
        if (lease == null) {
            transferWork.remove(workId);
            releaseTransferHandler();
            failTransfer(transfer, "TARGET_PREPARATION_REJECTED", new IllegalStateException("Network Persistence Admission Is Closed"));
            return;
        }
        try {
            CompletionStage<Void> preparation;
            try {
                preparation = handler.prepare(transfer, snapshot);
            } finally {
                releaseTransferHandler();
            }
            if (preparation == null) {
                throw new IllegalStateException("Transfer Preparation Did Not Return A Result");
            }
            preparation.toCompletableFuture().thenCompose(unused -> markTargetReady(transfer.transferId(), snapshot.snapshotId())).whenComplete((ready, throwable) -> {
                transferWork.remove(workId);
                lease.close();
                if (throwable != null) {
                    failTransfer(transfer, "TARGET_PREPARATION_FAILED", throwable);
                }
            });
        } catch (RuntimeException exception) {
            transferWork.remove(workId);
            lease.close();
            failTransfer(transfer, "TARGET_PREPARATION_FAILED", exception);
        }
    }

    private void applyTransfer(PlayerTransfer transfer) {
        if (deferTransferEvent(() -> applyTransfer(transfer))) {
            return;
        }
        PlayerStateSnapshot snapshot = transferSnapshots.get(transfer.transferId());
        String workId = transfer.transferId() + ":apply";
        if (snapshot == null || !transfer.targetNodeId().equals(config.nodeId()) || transfer.status().ordinal() < NetworkTransferStatus.CONNECTED.ordinal() || transfer.status().ordinal() >= NetworkTransferStatus.APPLIED.ordinal()) {
            return;
        }
        TransferHandler handler = acquireTransferHandler();
        if (handler == null || !transferWork.add(workId)) {
            if (handler != null) {
                releaseTransferHandler();
            }
            return;
        }
        NetworkPersistenceDrainController.Lease lease = admit("transfer-apply:" + transfer.transferId());
        if (lease == null) {
            transferWork.remove(workId);
            releaseTransferHandler();
            failTransfer(transfer, "TARGET_APPLY_REJECTED", new IllegalStateException("Network Persistence Admission Is Closed"));
            return;
        }
        try {
            CompletionStage<Void> application;
            try {
                application = handler.apply(transfer, snapshot);
            } finally {
                releaseTransferHandler();
            }
            if (application == null) {
                throw new IllegalStateException("Transfer Apply Did Not Return A Result");
            }
            application.toCompletableFuture().thenCompose(unused -> acknowledgeStateApplied(transfer.transferId(), snapshot.snapshotId())).whenComplete((committed, throwable) -> {
                transferWork.remove(workId);
                lease.close();
                if (throwable != null) {
                    failTransfer(transfer, "TARGET_APPLY_FAILED", throwable);
                } else {
                    finishTransfer(committed, false);
                }
            });
        } catch (RuntimeException exception) {
            transferWork.remove(workId);
            lease.close();
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

    private CompletionStage<NetworkPersistenceDrainController.AbortResult> abortActiveTransfersForDrain() {
        List<PlayerTransfer> pending = activeTransfers.values().stream().filter(transfer -> !terminal(transfer.status())).toList();
        String existingFailure = recoveryFailure;
        if (existingFailure != null) {
            return CompletableFuture.completedFuture(new NetworkPersistenceDrainController.AbortResult(false, true, existingFailure));
        }
        List<CompletableFuture<AbortOutcome>> outcomes = new ArrayList<>(activeTransfers.size());
        for (PlayerTransfer transfer : activeTransfers.values()) {
            if (!terminal(transfer.status())) {
                continue;
            }
            outcomes.add(finishTransfer(transfer, transfer.status() != NetworkTransferStatus.COMMITTED).handle((unused, throwable) -> throwable == null ? AbortOutcome.completed() : AbortOutcome.retained("Terminal player transfer completion did not finish: " + rootMessage(throwable))).toCompletableFuture());
        }
        for (PlayerTransfer transfer : pending) {
            if (!connected()) {
                outcomes.add(CompletableFuture.completedFuture(AbortOutcome.retained("Network connection unavailable; player transfer retained for recovery")));
                continue;
            }
            CompletableFuture<AbortOutcome> outcome;
            try {
                outcome = requestRaw(NetworkChannels.TRANSFER, NetworkFrameType.TRANSFER_ABORT, NetworkTransferCodec.encodeCheckpoint(NetworkTransferCheckpoint.abort(transfer.transferId(), "PERSISTENCE_DRAIN")), Set.of("state.transfer")).thenApply(frame -> NetworkTransferCodec.decodeTransfer(frame.payload())).thenCompose(aborted -> {
                    publishTransfer(aborted);
                    if (recoveryFailure != null) {
                        return CompletableFuture.completedFuture(AbortOutcome.failed(recoveryFailure));
                    }
                    return finishTransfer(aborted, true).handle((unused, throwable) -> throwable == null ? AbortOutcome.completed() : AbortOutcome.retained("Player transfer restoration did not complete: " + rootMessage(throwable)));
                }).exceptionally(throwable -> AbortOutcome.retained("Player transfer durable abort failed: " + rootMessage(throwable)));
            } catch (RuntimeException exception) {
                outcome = CompletableFuture.completedFuture(AbortOutcome.retained("Player transfer durable abort could not start: " + rootMessage(exception)));
            }
            outcomes.add(outcome);
        }
        return CompletableFuture.allOf(outcomes.toArray(new CompletableFuture[0])).thenApply(unused -> {
            boolean durable = recoveryFailure == null;
            boolean degraded = false;
            String detail = "";
            for (CompletableFuture<AbortOutcome> outcome : outcomes) {
                AbortOutcome result = outcome.join();
                durable &= result.durable();
                degraded |= result.degraded();
                if (detail.isBlank() && !result.detail().isBlank()) {
                    detail = result.detail();
                }
            }
            if (!activeTransfers.isEmpty()) {
                degraded = true;
                if (detail.isBlank()) {
                    detail = "Player transfer remains in durable recovery";
                }
            }
            if (durable && !degraded) {
                closeNetworkAdmission();
            }
            return new NetworkPersistenceDrainController.AbortResult(durable, degraded, detail);
        });
    }

    private boolean rememberTransfer(PlayerTransfer transfer) {
        try {
            transferRecovery.remember(transfer);
            return true;
        } catch (RuntimeException exception) {
            recoveryFailure = rootMessage(exception);
            Log.warn("ReSync player transfer recovery persistence failed: " + recoveryFailure);
            return false;
        }
    }

    private void refreshRecoveredTransfers() {
        if (deferTransferEvent(this::refreshRecoveredTransfers)) {
            return;
        }
        Map<String, PlayerTransfer> recovered = new LinkedHashMap<>();
        transferRecovery.snapshot().forEach(transfer -> recovered.put(transfer.transferId(), transfer));
        activeTransfers.clear();
        activeTransfers.putAll(recovered);
        transferSnapshots.clear();
        incomingSnapshots.clear();
        transferWork.clear();
        transferReadiness.entrySet().removeIf(entry -> {
            if (recovered.containsKey(entry.getKey())) {
                return false;
            }
            entry.getValue().completeExceptionally(new IllegalStateException("Network Player Transfer Was Removed During Persistence Rebind"));
            return true;
        });
        recoveryFailure = null;
        TransferHandler handler = transferHandler;
        if (handler != null) {
            handler.persistenceRebound(List.copyOf(recovered.values()));
        }
    }

    private static boolean terminal(NetworkTransferStatus status) {
        return status == NetworkTransferStatus.COMMITTED || status == NetworkTransferStatus.ABORTED || status == NetworkTransferStatus.TIMED_OUT;
    }

    private void closeNetworkAdmission() {
        disconnectAuthorized();
        Client current = client;
        if (current != null && current.isOpen()) {
            current.close(1000, "Network Persistence Quiesced");
        }
    }

    private record AbortOutcome(boolean durable, boolean degraded, String detail) {
        private static AbortOutcome completed() {
            return new AbortOutcome(true, false, "");
        }

        private static AbortOutcome retained(String detail) {
            return new AbortOutcome(true, true, detail);
        }

        private static AbortOutcome failed(String detail) {
            return new AbortOutcome(false, true, detail);
        }
    }

    private CompletionStage<Void> finishTransfer(PlayerTransfer transfer, boolean aborted) {
        CompletableFuture<Void> deferred = new CompletableFuture<>();
        if (deferTransferEvent(() -> finishTransfer(transfer, aborted).whenComplete((unused, throwable) -> {
            if (throwable == null) {
                deferred.complete(null);
            } else {
                deferred.completeExceptionally(throwable);
            }
        }))) {
            return deferred;
        }
        PlayerStateSnapshot snapshot = transferSnapshots.get(transfer.transferId());
        TransferHandler handler = acquireTransferHandler();
        CompletionStage<Void> completion;
        try {
            if (handler == null) {
                completion = CompletableFuture.failedFuture(new IllegalStateException("Network Player Transfer Completion Authority Is Unavailable"));
            } else {
                if ((!aborted && transfer.targetNodeId().equals(config.nodeId())) || (aborted && transfer.sourceNodeId().equals(config.nodeId()))) {
                    String ownerNodeId = aborted ? transfer.sourceNodeId() : transfer.targetNodeId();
                    PlayerLease lease = new PlayerLease(transfer.networkId(), transfer.playerId(), ownerNodeId, "", transfer.fenceEpoch(), 0, transfer.updatedAt());
                    ownership.put(lease.playerId(), lease);
                    handler.ownershipChanged(lease);
                }
                if (aborted) {
                    completion = handler.aborted(transfer, snapshot);
                } else {
                    handler.committed(transfer);
                    completion = CompletableFuture.completedFuture(null);
                }
            }
            if (completion == null) {
                completion = CompletableFuture.failedFuture(new IllegalStateException("Network Player Transfer Completion Did Not Return A Result"));
            }
        } catch (RuntimeException exception) {
            completion = CompletableFuture.failedFuture(exception);
        } finally {
            if (handler != null) {
                releaseTransferHandler();
            }
        }
        NetworkPersistenceDrainController.Lease lease = admit("transfer-finish:" + transfer.transferId());
        CompletableFuture<Void> tracked;
        if (lease != null) {
            tracked = completion.toCompletableFuture().whenComplete((unused, throwable) -> lease.close());
        } else if (persistenceDrain.state() == NetworkPersistenceDrainController.State.QUIESCING) {
            tracked = persistenceDrain.trackDuringDrain("transfer-finish:" + transfer.transferId(), completion);
        } else {
            tracked = completion.toCompletableFuture();
        }
        return tracked.thenCompose(unused -> {
            try {
                if (persistenceDrain.state() == NetworkPersistenceDrainController.State.QUIESCED) {
                    transferRecovery.forgetAfterQuiesce(transfer.transferId());
                } else {
                    transferRecovery.forget(transfer.transferId());
                }
            } catch (RuntimeException exception) {
                recoveryFailure = rootMessage(exception);
                return CompletableFuture.<Void>failedFuture(exception);
            }
            activeTransfers.remove(transfer.transferId(), transfer);
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
            return CompletableFuture.<Void>completedFuture(null);
        }).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                Log.warn("ReSync player transfer completion callback failed: " + rootMessage(throwable));
            }
        });
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
        pendingRequestTypes.clear();
    }

    private void notifyConnected() {
        if (deferTransferEvent(this::notifyConnected)) {
            return;
        }
        synchronized (transferAdmissionMonitor) {
            TransferHandler handler = acquireTransferHandler();
            if (handler != null) {
                try {
                    handler.connected();
                } catch (RuntimeException exception) {
                    Log.warn("ReSync network connection callback failed: " + rootMessage(exception));
                } finally {
                    releaseTransferHandler();
                }
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

    private boolean disconnectAuthorized() {
        if (!authorized.compareAndSet(true, false)) {
            return false;
        }
        presence.clear();
        listeners.forEach(listener -> {
            try {
                listener.onDisconnected();
            } catch (RuntimeException exception) {
                Log.warn("ReSync network listener disconnection callback failed: " + rootMessage(exception));
            }
        });
        return true;
    }

    private void scheduleReconnect() {
        if (stopping.get() || persistenceQuiesced.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (stopping.get() || persistenceQuiesced.get()) {
                reconnectScheduled.set(false);
                return;
            }
            try {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    reconnectScheduled.set(false);
                    if (!stopping.get()) {
                        connect();
                    }
                }, reconnectDelayTicks(config.reconnectDelayTicks(), reconnectFailures.getAndIncrement()));
            } catch (RuntimeException exception) {
                reconnectScheduled.set(false);
                throw exception;
            }
        }
    }

    static long reconnectDelayTicks(long configuredDelayTicks, int failures) {
        long baseDelay = Math.clamp(configuredDelayTicks, 20, 1_200);
        long multiplier = 1L << Math.clamp(failures, 0, 6);
        return Math.min(1_200, baseDelay * multiplier);
    }

    private void reportUnavailable(String reason) {
        if (unavailableReported.compareAndSet(false, true)) {
            Log.warn("ReSync network unavailable; retrying in background: " + reason);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private NetworkPersistenceDrainController.Lease admit(String operation) {
        try {
            return persistenceDrain.tryAcquire(operation, Duration.ZERO).orElse(null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void closePersistenceAdmission() {
        persistenceQuiesced.set(true);
        try {
            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
                playerDataAdmission.requestQuiesce();
            } else {
                playerDataAdmission.quiesce(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Paper player data admission could not quiesce", exception);
        }
    }

    private void resumePersistenceAdmission() throws IOException {
        if (stopping.get()) {
            return;
        }
        refreshPlayerDataAdmission();
        playerDataAdmission.resume();
        persistenceQuiesced.set(false);
        if (started.get() && config.enabled()) {
            if (!connected()) {
                connect();
            }
        }
    }

    private void refreshPlayerDataAdmission() throws IOException {
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            playerDataAdmission.refreshFromBukkit();
            return;
        }
        Future<Void> refresh = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            playerDataAdmission.refreshFromBukkit();
            return null;
        });
        try {
            refresh.get(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Paper Player Data World Rediscovery Was Interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Paper Player Data World Rediscovery Failed", exception);
        }
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

        default void onDisconnected() {
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

        default CompletionStage<Void> aborted(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
            return CompletableFuture.completedFuture(null);
        }

        default CompletionStage<Void> abortForShutdown(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
            return CompletableFuture.failedFuture(new IllegalStateException("Synchronous Network Source Transfer Restoration Is Unavailable"));
        }

        default void persistenceRebound(List<PlayerTransfer> transfers) {
        }
    }

    public record ShutdownResult(boolean completed, String detail, NetworkPersistenceDrainController.State state) {
        public ShutdownResult {
            detail = detail == null ? "" : detail;
            state = state == null ? NetworkPersistenceDrainController.State.OPEN : state;
        }

        public static ShutdownResult success() {
            return new ShutdownResult(true, "", NetworkPersistenceDrainController.State.CLOSED);
        }

        public static ShutdownResult failed(String detail, NetworkPersistenceDrainController.State state) {
            return new ShutdownResult(false, detail, state);
        }
    }

    private record TransferAdmissionEvent(long sequence, long bytes, Runnable action) {
    }

    public record TransferAdmissionHealth(boolean blocked, boolean draining, int queuedEvents, long queuedBytes,
                                          int overflowedTransfers, boolean backpressured, long backpressureCount,
                                          String diagnostic) {
        public TransferAdmissionHealth {
            diagnostic = diagnostic == null ? "" : diagnostic;
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
            authorized.set(false);
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
            if (client != this) {
                return;
            }
            disconnectAuthorized();
            failPending(new IllegalStateException("ReSync Network Disconnected: " + reason));
            if (!stopping.get()) {
                if (reconnectRequested.compareAndSet(true, false)) {
                    scheduleConnect();
                    return;
                }
                if ("Network Credential Rejected".equals(reason) && !credential.isBlank() && !config.enrollmentToken().isBlank()) {
                    try {
                        try (NetworkPersistenceDrainController.Lease ignored = persistenceDrain.acquire("credential-reset")) {
                            credentialStore.clear();
                            credential = credentialStore.value();
                        }
                        Log.warn("ReSync network credential was rejected; retrying enrollment");
                    } catch (Exception exception) {
                        Log.warn("ReSync network credential reset failed: " + rootMessage(exception));
                    }
                }
                reportUnavailable(reason);
                scheduleReconnect();
            }
        }

        @Override
        public void onError(Exception exception) {
            if (client == this && !stopping.get()) {
                reportUnavailable(rootMessage(exception));
                scheduleReconnect();
            }
        }
    }
}
