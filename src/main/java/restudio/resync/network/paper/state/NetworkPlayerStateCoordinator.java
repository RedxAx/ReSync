package restudio.resync.network.paper.state;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.PlayerStateSnapshot;
import restudio.resync.network.PlayerLease;
import restudio.resync.network.PlayerTransfer;
import restudio.resync.network.paper.ReSyncNetworkAgent;
import restudio.resync.network.paper.NetworkPersistenceDrainController;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

public class NetworkPlayerStateCoordinator implements ReSyncNetworkAgent.TransferHandler, Listener {
    private final ReSync plugin;
    private final NetworkPlayerStateConfig config;
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> sourceTransfers = ConcurrentHashMap.newKeySet();
    private final Map<String, NetworkPlayerStateCodec.Captured> sourceStates = new ConcurrentHashMap<>();
    private final Map<String, NetworkPlayerStateCodec.Captured> targetStates = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerLease> ownership = new ConcurrentHashMap<>();
    private final NetworkSnapshotOutbox outbox;
    private final NetworkPersistenceDrainController persistenceDrain;
    private final PaperPlayerDataMutationAdmission playerDataAdmission;
    private volatile NetworkPersistenceDrainController.Registration outboxRegistration;
    private volatile NetworkPersistenceDrainController.Registration producerRegistration;
    private final AtomicBoolean replayingOutbox = new AtomicBoolean();
    private final AtomicBoolean persistenceQuiesced = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private boolean eventsRegistered;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pendingTasks = ConcurrentHashMap.newKeySet();
    private final Object shutdownMonitor = new Object();
    private CompletableFuture<Void> shutdownAttempt;
    private boolean shutdownCleanupStarted;
    private boolean shutdownFinalized;
    private boolean shutdownCancellationRequested;
    private CompletableFuture<Void> replacementCancellationCompletion;

    public NetworkPlayerStateCoordinator(ReSync plugin, NetworkPlayerStateConfig config) {
        this(plugin, config, plugin.getNetworkAgent() == null ? null : plugin.getNetworkAgent().persistenceDrain(), true,
            playerDataAdmission(plugin));
    }

    public NetworkPlayerStateCoordinator(ReSync plugin, NetworkPlayerStateConfig config, NetworkPersistenceDrainController persistenceDrain) {
        this(plugin, config, persistenceDrain, true, playerDataAdmission(plugin));
    }

    public NetworkPlayerStateCoordinator(ReSync plugin, NetworkPlayerStateConfig config,
                                         NetworkPersistenceDrainController persistenceDrain,
                                         boolean registerPersistence) {
        this(plugin, config, persistenceDrain, registerPersistence, playerDataAdmission(plugin));
    }

    public NetworkPlayerStateCoordinator(ReSync plugin, NetworkPlayerStateConfig config,
                                         NetworkPersistenceDrainController persistenceDrain,
                                         boolean registerPersistence,
                                         PaperPlayerDataMutationAdmission playerDataAdmission) {
        this.plugin = plugin;
        this.config = config;
        this.playerDataAdmission = Objects.requireNonNull(playerDataAdmission, "playerDataAdmission");
        if (!config.enabled()) {
            throw new IllegalArgumentException("Network Player State Coordinator Requires An Enabled Profile");
        }
        this.persistenceDrain = persistenceDrain;
        Path networkRoot = persistenceDrain == null
            ? Path.of(plugin.getDataFolder().getPath(), "network")
            : persistenceDrain.persistenceRoot();
        outbox = new NetworkSnapshotOutbox(networkRoot.resolve("snapshot-outbox"));
        if (registerPersistence) {
            registerPersistence();
        }
    }

    private void registerPersistence() {
        if (persistenceDrain == null) {
            return;
        }
        NetworkPersistenceDrainController.Registration registeredOutbox = null;
        NetworkPersistenceDrainController.Registration registeredProducer = null;
        try {
            registeredOutbox = persistenceDrain.register(outbox.persistenceComponent("player-state-outbox"));
            registeredProducer = persistenceDrain.registerProducer(persistenceProducer());
            outboxRegistration = registeredOutbox;
            producerRegistration = registeredProducer;
        } catch (RuntimeException exception) {
            if (registeredProducer != null) {
                registeredProducer.close();
            }
            if (registeredOutbox != null) {
                registeredOutbox.close();
            }
            throw exception;
        }
    }

    public void stagePersistenceReplacement(NetworkPlayerStateCoordinator previous) {
        requirePrimaryThread();
        if (persistenceDrain == null) {
            return;
        }
        if (previous == null || previous.persistenceDrain != persistenceDrain) {
            registerPersistence();
            return;
        }
        if (previous.outboxRegistration == null || previous.producerRegistration == null) {
            throw new IllegalStateException("Network Player State Coordinator Replacement Requires Active Persistence Registrations");
        }
        NetworkPersistenceDrainController.Registration replacementOutbox = persistenceDrain.replace(
            previous.outboxRegistration, outbox.persistenceComponent("player-state-outbox"));
        try {
            NetworkPersistenceDrainController.Registration replacementProducer = persistenceDrain.replaceProducer(
                previous.producerRegistration, persistenceProducer());
            outboxRegistration = replacementOutbox;
            producerRegistration = replacementProducer;
        } catch (RuntimeException exception) {
            persistenceDrain.restore(replacementOutbox, previous.outboxRegistration,
                previous.outbox.persistenceComponent("player-state-outbox"));
            throw exception;
        }
    }

    public void rollbackPersistenceReplacement(NetworkPlayerStateCoordinator previous) {
        requirePrimaryThread();
        if (persistenceDrain == null || previous == null || previous.persistenceDrain != persistenceDrain
            || outboxRegistration == null || producerRegistration == null
            || previous.outboxRegistration == null || previous.producerRegistration == null) {
            return;
        }
        persistenceDrain.restoreProducer(producerRegistration, previous.producerRegistration,
            previous.persistenceProducer());
        persistenceDrain.restore(outboxRegistration, previous.outboxRegistration,
            previous.outbox.persistenceComponent("player-state-outbox"));
        producerRegistration = null;
        outboxRegistration = null;
    }

    private NetworkPersistenceDrainController.Producer persistenceProducer() {
        return new NetworkPersistenceDrainController.Producer() {
            @Override
            public String owner() {
                return "player-state-coordinator";
            }

            @Override
            public void closeAdmission() {
                closeAdmissionForDrain();
            }

            @Override
            public void resumeAdmission() {
                resumeAdmissionAfterDrain();
            }
        };
    }

    public void start() {
        requirePrimaryThread();
        synchronized (shutdownMonitor) {
            if (shutdownCleanupStarted || shutdownFinalized) {
                throw new IllegalStateException("Network Player State Coordinator Is Shut Down");
            }
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (eventsRegistered) {
            return;
        }
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            eventsRegistered = true;
        } catch (RuntimeException exception) {
            started.set(false);
            HandlerList.unregisterAll(this);
            throw exception;
        }
    }

    public PaperPlayerDataMutationAdmission playerDataAdmission() {
        return playerDataAdmission;
    }

    public void stageStart() {
        requirePrimaryThread();
        synchronized (shutdownMonitor) {
            if (shutdownCleanupStarted || shutdownFinalized) {
                throw new IllegalStateException("Network Player State Coordinator Is Shut Down");
            }
            if (eventsRegistered) {
                return;
            }
            Bukkit.getPluginManager().registerEvents(this, plugin);
            eventsRegistered = true;
        }
    }

    public void activateStagedStart() {
        requirePrimaryThread();
        synchronized (shutdownMonitor) {
            if (!eventsRegistered) {
                throw new IllegalStateException("Network Player State Coordinator Has Not Been Staged");
            }
            started.set(true);
        }
    }

    public void disableAdmissionForReplacement() {
        requirePrimaryThread();
        started.set(false);
        closed.set(true);
        persistenceQuiesced.set(true);
    }

    public CompletionStage<Void> shutdown() {
        return shutdown(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
    }

    public CompletionStage<Void> shutdown(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Shutdown Timeout Must Be Non-Negative");
        }
        CompletableFuture<Void> attempt;
        synchronized (shutdownMonitor) {
            if (shutdownFinalized) {
                return CompletableFuture.completedFuture(null);
            }
            if (shutdownAttempt != null && !shutdownAttempt.isDone()) {
                return shutdownAttempt;
            }
            attempt = new CompletableFuture<>();
            shutdownAttempt = attempt;
        }
        try {
            prepareForShutdown();
        } catch (RuntimeException exception) {
            attempt.completeExceptionally(exception);
            return attempt;
        }
        return shutdownAfterPreparation(timeout, attempt);
    }

    public CompletionStage<Void> shutdownAfterPreparation(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Shutdown Timeout Must Be Non-Negative");
        }
        CompletableFuture<Void> attempt;
        synchronized (shutdownMonitor) {
            if (!shutdownCleanupStarted) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Network Player State Coordinator Shutdown Requires Main-Thread Preparation"));
            }
            if (shutdownFinalized) {
                return CompletableFuture.completedFuture(null);
            }
            if (shutdownAttempt != null && !shutdownAttempt.isDone()) {
                return shutdownAttempt;
            }
            attempt = new CompletableFuture<>();
            shutdownAttempt = attempt;
        }
        return shutdownAfterPreparation(timeout, attempt);
    }

    private CompletionStage<Void> shutdownAfterPreparation(Duration timeout, CompletableFuture<Void> attempt) {
        CompletableFuture<Void> pending = awaitPendingTasks(timeout);
        CompletableFuture<Void> idle = persistenceDrain == null
            ? CompletableFuture.completedFuture(null)
            : persistenceDrain.awaitIdle(timeout);
        CompletableFuture.allOf(pending, idle).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                attempt.completeExceptionally(rootCause(throwable));
                return;
            }
            if (persistenceDrain != null) {
                NetworkPersistenceDrainController.State state = persistenceDrain.state();
                if (state != NetworkPersistenceDrainController.State.OPEN
                    && state != NetworkPersistenceDrainController.State.QUIESCED
                    && state != NetworkPersistenceDrainController.State.CLOSED) {
                    attempt.completeExceptionally(new IOException("Network Player State Persistence Is Still " + state.name()));
                    return;
                }
            }
            try {
                synchronized (shutdownMonitor) {
                    if (attempt.isDone() || shutdownAttempt != attempt || shutdownCancellationRequested) {
                        return;
                    }
                    finalizeShutdown();
                    shutdownFinalized = true;
                }
                attempt.complete(null);
            } catch (RuntimeException finalizationFailure) {
                attempt.completeExceptionally(rootCause(finalizationFailure));
            }
        });
        return attempt;
    }

    public void prepareForShutdown() {
        requirePrimaryThread();
        boolean startCleanup = false;
        synchronized (shutdownMonitor) {
            if (!shutdownCleanupStarted) {
                shutdownCleanupStarted = true;
                startCleanup = true;
            }
        }
        if (!startCleanup) {
            return;
        }
        closed.set(true);
        persistenceQuiesced.set(true);
        replayingOutbox.set(false);
        if (eventsRegistered) {
            started.set(false);
            eventsRegistered = false;
            HandlerList.unregisterAll(this);
        }
    }

    public CompletionStage<Void> cancelShutdownForReplacement(Throwable failure) {
        requirePrimaryThread();
        Throwable reason = failure == null ? new IllegalStateException("Network Player State Replacement Was Cancelled") : failure;
        CompletableFuture<Void> attempt;
        CompletableFuture<Void> physicalCompletion;
        synchronized (shutdownMonitor) {
            if (!shutdownCleanupStarted || shutdownFinalized) {
                return CompletableFuture.completedFuture(null);
            }
            if (shutdownCancellationRequested) {
                return replacementCancellationCompletion == null
                    ? CompletableFuture.completedFuture(null) : replacementCancellationCompletion;
            }
            shutdownCancellationRequested = true;
            physicalCompletion = pendingTasks.isEmpty() ? CompletableFuture.completedFuture(null) : new CompletableFuture<>();
            replacementCancellationCompletion = physicalCompletion;
            attempt = shutdownAttempt;
        }
        if (attempt != null) {
            attempt.completeExceptionally(reason);
        }
        return physicalCompletion;
    }

    public boolean hasPendingWork() {
        synchronized (shutdownMonitor) {
            return !pendingTasks.isEmpty();
        }
    }

    public boolean shutdownPrepared() {
        synchronized (shutdownMonitor) {
            return shutdownCleanupStarted;
        }
    }

    public void finalizeShutdown() {
        frozenPlayers.clear();
        sourceTransfers.clear();
        sourceStates.clear();
        targetStates.clear();
        ownership.clear();
        pendingTasks.clear();
        boolean retainRegistrations = persistenceDrain != null && persistenceDrain.retainReplacementRegistrationsOnFinalize();
        if (!retainRegistrations) {
            if (producerRegistration != null) {
                producerRegistration.close();
            }
            if (outboxRegistration != null) {
                outboxRegistration.close();
            }
        }
        synchronized (shutdownMonitor) {
            shutdownFinalized = true;
        }
    }

    public void reactivateAfterReplacementFailure() {
        requirePrimaryThread();
        synchronized (shutdownMonitor) {
            if (!shutdownCleanupStarted) {
                return;
            }
            if (persistenceDrain != null && persistenceDrain.state() != NetworkPersistenceDrainController.State.OPEN) {
                throw new IllegalStateException("Network Player State Persistence Is Not Open For Coordinator Recovery");
            }
            if (outboxRegistration == null || !outboxRegistration.active()) {
                registerPersistence();
            }
            shutdownCleanupStarted = false;
            shutdownFinalized = false;
            shutdownCancellationRequested = false;
            replacementCancellationCompletion = null;
            shutdownAttempt = null;
            closed.set(false);
            persistenceQuiesced.set(false);
            replayingOutbox.set(false);
        }
    }

    private CompletableFuture<Void> awaitPendingTasks(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<Void> pending = awaitPendingTasks(deadline);
        if (!pending.isDone()) {
            pending.orTimeout(Math.max(1, timeout.toNanos()), TimeUnit.NANOSECONDS);
        }
        return pending;
    }

    private CompletableFuture<Void> awaitPendingTasks(long deadline) {
        List<CompletableFuture<?>> tasks = List.copyOf(pendingTasks);
        if (tasks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return CompletableFuture.failedFuture(new TimeoutException("Network Player State Tasks Did Not Drain"));
        }
        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
            .handle((unused, throwable) -> null)
            .thenCompose(unused -> awaitPendingTasks(deadline));
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static PaperPlayerDataMutationAdmission playerDataAdmission(ReSync plugin) {
        return plugin != null && plugin.getNetworkAgent() != null
            ? plugin.getNetworkAgent().playerDataAdmission()
            : PaperPlayerDataMutationAdmission.shared();
    }

    public void flushPersistence() throws IOException {
        requireOpen();
        outbox.flush();
    }

    public void quiescePersistence() throws IOException {
        requireOpen();
        closeAdmissionForDrain();
        if (!persistenceQuiesced.compareAndSet(false, true)) {
            return;
        }
        try {
            outbox.quiesce();
        } catch (IOException | RuntimeException exception) {
            persistenceQuiesced.set(false);
            throw exception;
        }
    }

    public void resumePersistence() throws IOException {
        if (closed.get()) {
            throw new IOException("Network Player State Coordinator Is Shut Down");
        }
        if (!persistenceQuiesced.get()) {
            return;
        }
        outbox.resume();
        persistenceQuiesced.set(false);
        resumeAdmissionAfterDrain();
    }

    public void rebindPersistence(Path networkRoot) throws IOException {
        requireOpen();
        if (!persistenceQuiesced.get()) {
            throw new IOException("Network player state persistence must be quiesced before rebind");
        }
        Path root = MigrationPaths.requireDirectory(networkRoot, "networkRoot");
        Path outboxRoot = root.resolve("snapshot-outbox").normalize();
        if (!outboxRoot.startsWith(root) || outboxRoot.equals(root)) {
            throw new IOException("Network player state rebind escaped network root");
        }
        Files.createDirectories(outboxRoot);
        outbox.rebind(outboxRoot);
    }

    public void healthCheckPersistence() throws IOException {
        if (closed.get()) {
            throw new IOException("Network Player State Coordinator Is Shut Down");
        }
        outbox.healthCheck();
    }

    @Override
    public CompletionStage<PlayerStateSnapshot> capture(PlayerTransfer transfer) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
        }
        NetworkPersistenceDrainController.Lease lease = admit("player-capture:" + transfer.transferId());
        if (persistenceDrain != null && lease == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Persistence Is Quiesced"));
        }
        CompletableFuture<PlayerStateSnapshot> task = onPlayer(transfer.playerId(), player -> {
            frozenPlayers.add(player.getUniqueId());
            sourceTransfers.add(transfer.transferId());
            player.closeInventory();
            NetworkPlayerStateCodec.Captured captured = NetworkPlayerStateCodec.capture(player, config);
            sourceStates.put(transfer.transferId(), captured);
            return captured;
        }).thenApplyAsync(captured -> {
            byte[] payload = NetworkPlayerStateCodec.encode(captured);
            long now = Instant.now().toEpochMilli();
            return new PlayerStateSnapshot(UUID.randomUUID().toString(), transfer.networkId(), transfer.playerId(), transfer.fenceEpoch(), config.family(), payload, NetworkPayloads.sha256(payload), NetworkPlayerStateCodec.SCHEMA_VERSION, captured.data().dataVersion(), transfer.sourceNodeId(), now, false);
        });
        return trackTask(task).whenComplete((unused, throwable) -> close(lease));
    }

    @Override
    public CompletionStage<Void> prepare(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
        }
        NetworkPersistenceDrainController.Lease lease = admit("player-prepare:" + transfer.transferId());
        if (persistenceDrain != null && lease == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Persistence Is Quiesced"));
        }
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            if (closed.get()) {
                throw new IllegalStateException("Network Player State Coordinator Is Shut Down");
            }
            if (!snapshot.family().equals(config.family()) || snapshot.schemaVersion() < 1 || snapshot.schemaVersion() > NetworkPlayerStateCodec.SCHEMA_VERSION || snapshot.dataVersion() > Bukkit.getUnsafe().getDataVersion()) {
                throw new IllegalArgumentException("Network Player State Is Not Compatible With The Target Realm");
            }
            NetworkPlayerStateCodec.Captured captured = NetworkPlayerStateCodec.decode(snapshot.payload());
            NetworkPlayerStateCodec.validate(captured, config);
            if (closed.get()) {
                throw new IllegalStateException("Network Player State Coordinator Is Shut Down");
            }
            targetStates.put(transfer.transferId(), captured);
            frozenPlayers.add(transfer.playerId());
        });
        return trackTask(task).whenComplete((unused, throwable) -> close(lease));
    }

    @Override
    public CompletionStage<Void> apply(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
        }
        NetworkPersistenceDrainController.Lease lease = admit("player-apply:" + transfer.transferId());
        if (persistenceDrain != null && lease == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Persistence Is Quiesced"));
        }
        NetworkPlayerStateCodec.Captured captured = targetStates.get(transfer.transferId());
        if (captured == null) {
            close(lease);
            return CompletableFuture.failedFuture(new IllegalStateException("Prepared Network Player State Is Missing"));
        }
        return trackTask(applyState(transfer.playerId(), captured).toCompletableFuture()).whenComplete((unused, throwable) -> close(lease));
    }

    @Override
    public void committed(PlayerTransfer transfer) {
        if (closed.get()) {
            return;
        }
        cleanup(transfer);
    }

    @Override
    public void recovering(PlayerTransfer transfer, boolean source) {
        if (closed.get()) {
            return;
        }
        frozenPlayers.add(transfer.playerId());
        if (source) {
            sourceTransfers.add(transfer.transferId());
        }
    }

    @Override
    public void ownershipChanged(PlayerLease lease) {
        if (closed.get()) {
            return;
        }
        ownership.put(lease.playerId(), lease);
    }

    @Override
    public void connected() {
        if (closed.get() || persistenceQuiesced.get()) {
            return;
        }
        replayOutbox();
    }

    @Override
    public void persistenceRebound(List<PlayerTransfer> transfers) {
        if (closed.get()) {
            return;
        }
        Set<String> activeTransferIds = transfers.stream().map(PlayerTransfer::transferId).collect(Collectors.toSet());
        Set<UUID> activePlayers = transfers.stream().map(PlayerTransfer::playerId).collect(Collectors.toSet());
        sourceTransfers.retainAll(activeTransferIds);
        sourceStates.keySet().retainAll(activeTransferIds);
        targetStates.keySet().retainAll(activeTransferIds);
        frozenPlayers.removeIf(playerId -> !activePlayers.contains(playerId));
        ReSyncNetworkAgent agent = plugin.getNetworkAgent();
        for (PlayerTransfer transfer : transfers) {
            frozenPlayers.add(transfer.playerId());
            if (agent != null && transfer.sourceNodeId().equals(agent.nodeId())) {
                sourceTransfers.add(transfer.transferId());
            }
        }
    }

    @Override
    public CompletionStage<Void> aborted(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        if (closed.get() && !shutdownRestorationAllowed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
        }
        NetworkPersistenceDrainController.Lease lease = admit("player-abort-restoration:" + transfer.transferId());
        if (persistenceDrain != null && lease == null && persistenceDrain.state() != NetworkPersistenceDrainController.State.QUIESCING) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Persistence Is Quiesced"));
        }
        boolean sourceNode = sourceTransfers.contains(transfer.transferId());
        NetworkPlayerStateCodec.Captured source = sourceNode ? sourceStates.get(transfer.transferId()) : null;
        if (sourceNode && source == null && snapshot != null && snapshot.family().equals(config.family())) {
            try {
                source = NetworkPlayerStateCodec.decode(snapshot.payload());
                NetworkPlayerStateCodec.validate(source, config);
            } catch (RuntimeException exception) {
                Log.warn("ReSync could not decode aborted player transfer " + transfer.transferId() + ": " + rootMessage(exception));
            }
        }
        if (source != null) {
            try {
                CompletionStage<Void> restoration = applyState(transfer.playerId(), source).thenRun(() -> cleanup(transfer));
                return trackRestoration(transfer, restoration, lease);
            } catch (RuntimeException exception) {
                close(lease);
                return CompletableFuture.failedFuture(exception);
            }
        }
        cleanup(transfer);
        if (lease != null) {
            lease.close();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> abortForShutdown(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        requirePrimaryThread();
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
        }
        NetworkPlayerStateCodec.Captured source = sourceStates.get(transfer.transferId());
        if (source == null && snapshot != null && snapshot.family().equals(config.family())) {
            try {
                source = NetworkPlayerStateCodec.decode(snapshot.payload());
                NetworkPlayerStateCodec.validate(source, config);
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        if (source == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Source Player State Is Missing"));
        }
        Player player = Bukkit.getPlayer(transfer.playerId());
        if (player == null || !player.isOnline()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Source Player Is Not Online On This Server"));
        }
        try {
            try (PaperPlayerDataMutationAdmission.Lease ignored = playerDataAdmission.acquirePdc(
                "player-abort-restoration-data:" + transfer.transferId(), transfer.playerId(), player.getWorld().getWorldFolder().toPath())) {
                Location destination = NetworkPlayerStateCodec.apply(player, source, config, playerDataAdmission);
                if (destination != null && !player.teleport(destination)) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Network Source Player Location Could Not Be Restored"));
                }
            }
            cleanup(transfer);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletionStage<Void> applyState(UUID playerId, NetworkPlayerStateCodec.Captured captured) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player Is Not Online On This Server"));
        }
        PaperPlayerDataMutationAdmission.Lease admission;
        try {
            admission = playerDataAdmission.acquirePdc("player-transfer-restoration-data:" + playerId,
                playerId, player.getWorld().getWorldFolder().toPath());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return onPlayer(playerId, current -> new AppliedPlayer(current,
            NetworkPlayerStateCodec.apply(current, captured, config, playerDataAdmission))).thenCompose(applied -> {
            Location destination = applied.destination();
            if (destination == null) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            return applied.player().teleportAsync(destination).thenCompose(success -> success
                ? CompletableFuture.<Void>completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException("Network Player Location Could Not Be Restored")));
        }).whenComplete((unused, throwable) -> admission.close());
    }

    private void cleanup(PlayerTransfer transfer) {
        frozenPlayers.remove(transfer.playerId());
        sourceStates.remove(transfer.transferId());
        targetStates.remove(transfer.transferId());
        sourceTransfers.remove(transfer.transferId());
    }

    private <T> CompletableFuture<T> onPlayer(UUID playerId, Function<Player, T> operation) {
        if (closed.get() && !shutdownRestorationAllowed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
        }
        CompletableFuture<T> future = trackTask(new CompletableFuture<>());
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            future.completeExceptionally(new IllegalStateException("Network Player Is Not Online On This Server"));
            return future;
        }
        boolean scheduled = player.getScheduler().execute(plugin, () -> {
            try {
                if (closed.get() && !shutdownRestorationAllowed()) {
                    future.completeExceptionally(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
                    return;
                }
                if (!player.isOnline()) {
                    throw new IllegalStateException("Network Player Left Before State Processing");
                }
                future.complete(operation.apply(player));
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        }, () -> future.completeExceptionally(new IllegalStateException("Network Player Left Before State Processing")), 1);
        if (!scheduled) {
            future.completeExceptionally(new IllegalStateException("Network Player Scheduler Is Retired"));
        }
        return future;
    }

    private boolean shutdownRestorationAllowed() {
        return shutdownCleanupStarted
            && persistenceDrain != null
            && persistenceDrain.state() == NetworkPersistenceDrainController.State.QUIESCING;
    }

    private boolean frozen(Player player) {
        return player != null && frozenPlayers.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (started.get() && event.getClass() == PlayerMoveEvent.class && frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (started.get() && event.getWhoClicked() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (started.get() && event.getWhoClicked() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (started.get() && frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (started.get() && event.getEntity() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (started.get() && frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (started.get() && frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHeldItem(PlayerItemHeldEvent event) {
        if (started.get() && frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (started.get() && frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (started.get() && event.getEntity() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent event) {
        if (started.get() && event.getEntity() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (started.get() && frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!started.get() || closed.get() || persistenceQuiesced.get()) {
            return;
        }
        Player player = event.getPlayer();
        String playerName = player.getName();
        if (frozen(player)) {
            return;
        }
        PlayerLease lease = ownership.get(player.getUniqueId());
        if (lease == null || !lease.pendingNodeId().isBlank()) {
            return;
        }
        NetworkPlayerStateCodec.Captured captured;
        try {
            captured = NetworkPlayerStateCodec.capture(player, config);
        } catch (RuntimeException exception) {
            Log.warn("ReSync could not capture disconnect state for " + playerName + ": " + rootMessage(exception));
            return;
        }
        NetworkPersistenceDrainController.Lease drainLease = admit("player-disconnect:" + player.getUniqueId());
        if (persistenceDrain != null && drainLease == null) {
            return;
        }
        CompletableFuture<?> task = CompletableFuture.supplyAsync(() -> NetworkPlayerStateCodec.encode(captured)).thenCompose(payload -> {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
            }
            long now = Instant.now().toEpochMilli();
            PlayerStateSnapshot snapshot = new PlayerStateSnapshot(UUID.randomUUID().toString(), lease.networkId(), lease.playerId(), lease.fenceEpoch(), config.family(), payload, NetworkPayloads.sha256(payload), NetworkPlayerStateCodec.SCHEMA_VERSION, captured.data().dataVersion(), lease.ownerNodeId(), now, false);
            outbox.save(snapshot);
            ReSyncNetworkAgent agent = plugin.getNetworkAgent();
            if (closed.get() || agent == null || !agent.connected()) {
                return CompletableFuture.completedFuture(lease);
            }
            return agent.saveOwnerSnapshot(snapshot).thenApply(saved -> {
                if (!closed.get()) {
                    outbox.remove(snapshot.snapshotId());
                }
                return saved;
            });
        });
        trackTask(task).whenComplete((saved, throwable) -> {
            close(drainLease);
            if (throwable != null) {
                Log.warn("ReSync could not save disconnect state for " + playerName + ": " + rootMessage(throwable));
            }
        });
    }

    private void replayOutbox() {
        if (closed.get() || persistenceQuiesced.get() || !replayingOutbox.compareAndSet(false, true)) {
            return;
        }
        NetworkPersistenceDrainController.Lease lease = admit("player-outbox-replay");
        if (persistenceDrain != null && lease == null) {
            replayingOutbox.set(false);
            return;
        }
        CompletableFuture<Void> task = CompletableFuture.supplyAsync(outbox::load).thenCompose(snapshots -> {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
            }
            CompletableFuture<Void> replay = CompletableFuture.completedFuture(null);
            for (PlayerStateSnapshot snapshot : snapshots) {
                replay = replay.thenCompose(ignored -> replaySnapshot(snapshot));
            }
            return replay;
        });
        trackTask(task).whenComplete((unused, throwable) -> {
            replayingOutbox.set(false);
            close(lease);
            if (throwable != null) {
                Log.warn("ReSync could not replay disconnect snapshots: " + rootMessage(throwable));
            }
        });
    }

    private CompletableFuture<Void> replaySnapshot(PlayerStateSnapshot snapshot) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player State Coordinator Is Shut Down"));
        }
        ReSyncNetworkAgent agent = plugin.getNetworkAgent();
        if (agent == null || !agent.connected()) {
            return CompletableFuture.completedFuture(null);
        }
        return agent.saveOwnerSnapshot(snapshot).thenAccept(saved -> outbox.remove(snapshot.snapshotId())).exceptionally(throwable -> {
            Log.warn("ReSync could not replay disconnect snapshot " + snapshot.snapshotId() + ": " + rootMessage(throwable));
            return null;
        });
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record AppliedPlayer(Player player, Location destination) {
    }

    private NetworkPersistenceDrainController.Lease admit(String operation) {
        if (closed.get() || persistenceDrain == null) {
            return null;
        }
        try {
            return persistenceDrain.tryAcquire(operation, Duration.ZERO).orElse(null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void close(NetworkPersistenceDrainController.Lease lease) {
        if (lease != null) {
            lease.close();
        }
    }

    <T> CompletableFuture<T> trackTask(CompletableFuture<T> task) {
        synchronized (shutdownMonitor) {
            pendingTasks.add(task);
        }
        task.whenComplete((unused, throwable) -> {
            CompletableFuture<Void> physicalCompletion = null;
            synchronized (shutdownMonitor) {
                pendingTasks.remove(task);
                if (replacementCancellationCompletion != null && pendingTasks.isEmpty()) {
                    physicalCompletion = replacementCancellationCompletion;
                    replacementCancellationCompletion = null;
                }
            }
            if (physicalCompletion != null) {
                physicalCompletion.complete(null);
            }
        });
        return task;
    }

    private CompletionStage<Void> trackRestoration(PlayerTransfer transfer, CompletionStage<Void> restoration, NetworkPersistenceDrainController.Lease lease) {
        CompletableFuture<Void> tracked;
        if (lease != null) {
            tracked = trackTask(restoration.toCompletableFuture()).whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    Log.warn("ReSync could not restore aborted player transfer " + transfer.transferId() + ": " + rootMessage(throwable));
                }
                lease.close();
            });
        } else if (persistenceDrain != null && persistenceDrain.state() == NetworkPersistenceDrainController.State.QUIESCING) {
            tracked = trackTask(trackRestorationDuringDrain(persistenceDrain, "player-abort-restoration:" + transfer.transferId(), restoration).toCompletableFuture());
        } else {
            tracked = trackTask(restoration.toCompletableFuture());
        }
        return tracked;
    }

    static CompletionStage<Void> trackRestorationDuringDrain(NetworkPersistenceDrainController persistenceDrain, String operation, CompletionStage<Void> restoration) {
        return Objects.requireNonNull(persistenceDrain, "persistenceDrain").trackDuringDrain(operation, Objects.requireNonNull(restoration, "restoration"));
    }

    private void closeAdmissionForDrain() {
        persistenceQuiesced.set(true);
    }

    private void resumeAdmissionAfterDrain() {
        if (closed.get()) {
            return;
        }
        persistenceQuiesced.set(false);
        if (plugin.getNetworkAgent() != null && plugin.getNetworkAgent().connected()) {
            replayOutbox();
        }
    }

    private void requireOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("Network Player State Coordinator Is Shut Down");
        }
    }

    private static void requirePrimaryThread() {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Network Player State Coordinator lifecycle must run on the Bukkit main thread");
        }
    }
}
