package restudio.resync;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import restudio.resync.commands.ReSyncCommand;
import restudio.resync.bridge.ReSyncPluginMessageBridge;
import restudio.resync.network.paper.NetworkPathSynchronizer;
import restudio.resync.network.paper.NetworkPersistenceDrainController;
import restudio.resync.network.paper.NetworkResourceSynchronizer;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.network.paper.ReSyncNetworkAgent;
import restudio.resync.network.paper.state.NetworkPlayerStateConfig;
import restudio.resync.network.paper.state.NetworkPlayerStateCoordinator;
import restudio.resync.migration.FreshRootProvenance;
import restudio.resync.migration.LegacyInstallBoundary;
import restudio.resync.migration.ReSyncDataFixer;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.selection.InteractiveSelectionManager;
import restudio.resync.server.ReSyncServer;
import restudio.resync.server.ConfigLoader;
import restudio.resync.server.ReSyncConfig;
import restudio.resync.server.ReSyncTlsIdentity;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalogFiles;
import restudio.resync.upgrade.AssetCoordinatorMigration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReSync extends JavaPlugin {
    private static final ReSyncDataFixer DATA_FIXER = new ReSyncDataFixer(1, List.of());
    private static ReSync instance;
    private WebSocketServer wsServer;
    private ReSyncTlsIdentity.Prepared tlsIdentity;
    private volatile boolean webSocketApiReady;
    private ReSyncServer server;
    private ReSyncPluginMessageBridge pluginMessageBridge;
    private ReSyncNetworkAgent networkAgent;
    private List<NetworkPathSynchronizer> networkPathSynchronizers = List.of();
    private NetworkResourceSynchronizer networkResourceSynchronizer;
    private NetworkPlayerStateCoordinator networkPlayerStateCoordinator;
    private boolean networkStateReloadScheduled;
    private BukkitTask networkStateReloadTask;
    private volatile boolean lifecycleStopping;
    private volatile boolean shutdownRequested;
    private final AtomicBoolean networkStateReloadInProgress = new AtomicBoolean();
    private final Object networkLifecycleMonitor = new Object();
    private volatile PendingNetworkStateReload pendingNetworkStateReload;
    private volatile NetworkStateReloadWork networkStateReloadWork;
    private volatile CompletableFuture<NetworkPlayerStateConfig> networkStateReloadAttempt;
    private volatile CompletableFuture<Void> networkStateReloadAdmissionDrain;
    private volatile Path operatorDataRoot;
    private volatile Path activeDataRoot;
    private final Set<NetworkPersistenceDrainController.ReplacementLease> retainedNetworkStateReloadLeases = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean pluginMessageBridgeCleaned = new AtomicBoolean();
    private final AtomicBoolean interactiveSelectionManagerCleaned = new AtomicBoolean();
    private final AtomicBoolean placeholderExpansionCleaned = new AtomicBoolean();
    private final AtomicBoolean webSocketServerCleaned = new AtomicBoolean();
    private final AtomicBoolean pluginOwnedResourcesCleaned = new AtomicBoolean();
    private final AtomicBoolean shutdownReferencesCleared = new AtomicBoolean();
    private Object placeholderExpansion;
    private InteractiveSelectionManager interactiveSelectionManager;

    @Override
    public void onEnable() {
        instance = this;
        lifecycleStopping = false;
        shutdownRequested = false;
        pluginMessageBridgeCleaned.set(false);
        interactiveSelectionManagerCleaned.set(false);
        placeholderExpansionCleaned.set(false);
        webSocketServerCleaned.set(false);
        pluginOwnedResourcesCleaned.set(false);
        shutdownReferencesCleared.set(false);
        webSocketApiReady = false;
        tlsIdentity = null;
        Log.init(getLogger());
        DiagnosticCodeCatalogFiles.installConfiguredDefault();

        Path originalDataRoot = getDataFolder().toPath().toAbsolutePath().normalize();
        operatorDataRoot = originalDataRoot;
        ReSyncPersistenceCoordinator persistence = null;
        ReSyncConfig config;
        Path preparedDataRoot;
        AssetCoordinatorMigration.Result assetMigration;
        long bootstrapStarted = System.nanoTime();
        try {
            Path pluginRoot = originalDataRoot.getParent();
            if (pluginRoot == null) {
                throw new IOException("ReSync Data Root Has No Parent");
            }
            Path coordinationRoot = pluginRoot.resolve(".resync-coordination");
            LegacyInstallBoundary.Result legacy = LegacyInstallBoundary.prepare(originalDataRoot, coordinationRoot);
            if (legacy.archived()) {
                Log.warn("Pre-rewrite ReSync data was archived at " + legacy.dataBackup() + ". ReSync will start with clean data.");
            }
            ReSyncPersistenceCoordinator.PreparedBootstrap prepared = ReSyncPersistenceCoordinator.bootstrapPrepared(
                originalDataRoot, coordinationRoot);
            persistence = prepared.coordinator();
            preparedDataRoot = prepared.activeRoot();
            long activationReady = System.nanoTime();
            boolean freshBootstrap = persistence.freshBootstrap();
            ReSyncDataFixer.Result dataFix;
            if (freshBootstrap) {
                FreshRootProvenance provenance = persistence.freshRootProvenance()
                    .orElseThrow(() -> new IOException("Fresh ReSync Persistence Has No Provenance"));
                assetMigration = AssetCoordinatorMigration.prepareEmptyUnconsumed(coordinationRoot, provenance);
                dataFix = persistence.prepareDataFixes(DATA_FIXER, true);
                provenance.consume(assetMigration.artifactHash());
            } else {
                dataFix = persistence.prepareDataFixes(DATA_FIXER, false);
                assetMigration = prepareAssetMigration(persistence, dataFix.activeRoot());
            }
            preparedDataRoot = dataFix.activeRoot();
            if (dataFix.changed()) {
                Log.info("ReSync data upgraded from version " + dataFix.sourceVersion() + " to " + dataFix.targetVersion()
                    + " using " + String.join(", ", dataFix.appliedFixes()));
            }
            long assetMigrationReady = System.nanoTime();
            config = ConfigLoader.load(preparedDataRoot);
            long configReady = System.nanoTime();
            Log.info("Persistence bootstrap completed in " + elapsedMillis(bootstrapStarted, configReady) + " ms"
                + " [Activation " + elapsedMillis(bootstrapStarted, activationReady) + " ms"
                + ", Asset Migration " + elapsedMillis(activationReady, assetMigrationReady) + " ms"
                + ", Config " + elapsedMillis(assetMigrationReady, configReady) + " ms]");
        } catch (IOException | RuntimeException exception) {
            closeBootstrap(persistence, exception);
            throw new IllegalStateException("ReSync Persistence Bootstrap Failed", exception);
        }
        Log.setLevel(config.getLogLevel());

        if (!config.isEnabled()) {
            Log.info("ReSync is disabled in config");
            closeBootstrap(persistence, null);
            return;
        }

        try {
            if (config.getTls().isEnabled()) {
                tlsIdentity = ReSyncTlsIdentity.prepare(preparedDataRoot, config.getTls());
                Log.info("ReSync TLS identity ready with SPKI fingerprint " + tlsIdentity.metadata().spkiFingerprint());
            }
            persistence.register(tlsIdentity == null ? ReSyncTlsIdentity.persistence(preparedDataRoot, config.getTls())
                : tlsIdentity.persistence());
        } catch (Exception exception) {
            closeBootstrap(persistence, exception);
            throw new IllegalStateException("ReSync TLS Setup Failed", exception);
        }

        boolean persistenceAdopted = false;
        try {
            server = new ReSyncServer(this, config, persistence, preparedDataRoot, assetMigration);
            persistenceAdopted = true;
            activeDataRoot = preparedDataRoot;
            networkAgent = server.getNetworkAgent();
            networkResourceSynchronizer = server.getNetworkResourceSynchronizer();
            networkPathSynchronizers = server.getNetworkPathSynchronizers();
            networkPlayerStateCoordinator = server.getNetworkPlayerStateCoordinator();
            server.startNetwork();
            pluginMessageBridge = new ReSyncPluginMessageBridge(this);
            pluginMessageBridge.register();
            interactiveSelectionManager = new InteractiveSelectionManager(this);
            interactiveSelectionManager.start();
            wsServer = new WebSocketServer(new InetSocketAddress(config.getBindHost(), config.getPort())) {
                @Override
                public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    if (!webSocketApiReady) {
                        conn.close(1013, "ReSync WebSocket API Is Unavailable");
                        return;
                    }
                    ReSyncServer current = activeServer();
                    if (current != null) {
                        current.onOpen(conn, handshake);
                    }
                }

                @Override
                public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                    ReSyncServer current = activeServer();
                    if (current != null) {
                        current.onClose(conn, code, reason, remote);
                    }
                }

                @Override
                public void onMessage(WebSocket conn, String message) {
                    if (!webSocketApiReady) {
                        conn.close(1013, "ReSync WebSocket API Is Unavailable");
                        return;
                    }
                    ReSyncServer current = activeServer();
                    if (current != null) {
                        current.onMessage(conn, ByteBuffer.wrap(message.getBytes()));
                    }
                }

                @Override
                public void onMessage(WebSocket conn, ByteBuffer message) {
                    if (!webSocketApiReady) {
                        conn.close(1013, "ReSync WebSocket API Is Unavailable");
                        return;
                    }
                    ReSyncServer current = activeServer();
                    if (current != null) {
                        current.onMessage(conn, message);
                    }
                }

                @Override
                public void onError(WebSocket conn, Exception ex) {
                    if (conn == null) {
                        webSocketApiReady = false;
                        withdrawTlsMetadata();
                    }
                    if (expectedDisconnect(ex)) {
                        Log.fine("WebSocket peer disconnected: " + ex.getMessage());
                    } else {
                        Log.error("WebSocket error: " + ex.getMessage(), ex);
                    }
                }

                @Override
                public void onStart() {
                    try {
                        if (tlsIdentity != null) {
                            ReSyncTlsIdentity.publish(tlsIdentity);
                        }
                        webSocketApiReady = true;
                        Log.info("WebSocket server ready on " + config.getBindHost() + ":" + getPort());
                    } catch (Exception exception) {
                        webSocketApiReady = false;
                        withdrawTlsMetadata();
                        Log.error("ReSync TLS runtime metadata could not be published. WebSocket API disabled: " + exception.getMessage(), exception);
                    }
                }
            };

            if (tlsIdentity != null) {
                wsServer.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(tlsIdentity.sslContext()));
            }

            try {
                wsServer.start();
                Log.info("WebSocket server starting on " + config.getBindHost() + ":" + config.getPort());
            } catch (Exception exception) {
                Log.error("Failed to start WebSocket server: " + exception.getMessage(), exception);
            }

            if (getCommand("resync") != null) {
                ReSyncCommand command = new ReSyncCommand(this);
                getCommand("resync").setExecutor(command);
                getCommand("resync").setTabCompleter(command);
            } else {
                Log.warn("Command '/resync' not found in plugin.yml");
            }

            registerPlaceholderExpansion();
        } catch (RuntimeException exception) {
            if (persistenceAdopted) {
                abortStartup(exception);
            } else {
                closeBootstrap(persistence, exception);
            }
            throw exception;
        }
    }

    private static boolean expectedDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketException
                && "Connection reset".equalsIgnoreCase(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void withdrawTlsMetadata() {
        try {
            ReSyncTlsIdentity.withdraw(tlsIdentity);
        } catch (Exception exception) {
            Log.error("ReSync TLS runtime metadata could not be removed: " + exception.getMessage(), exception);
        }
    }

    private void closeBootstrap(ReSyncPersistenceCoordinator persistence, Throwable failure) {
        if (persistence == null) {
            return;
        }
        try {
            persistence.shutdown();
        } catch (IOException | RuntimeException cleanupFailure) {
            if (failure != null) {
                failure.addSuppressed(cleanupFailure);
            } else {
                Log.error("ReSync persistence bootstrap cleanup failed: " + cleanupFailure.getMessage(), cleanupFailure);
            }
        }
    }

    private static AssetCoordinatorMigration.Result prepareAssetMigration(ReSyncPersistenceCoordinator persistence,
                                                                          Path activeRoot) throws IOException {
        Path coordinationRoot = persistence.coordinationRoot();
        Path artifact = coordinationRoot.resolve(AssetCoordinatorMigration.ARTIFACT_RELATIVE_PATH).toAbsolutePath().normalize();
        if (Files.exists(artifact, LinkOption.NOFOLLOW_LINKS)) {
            return AssetCoordinatorMigration.load(coordinationRoot);
        }
        throw new IOException("Existing ReSync Persistence Requires A Verified Offline Migration Artifact");
    }

    private static long elapsedMillis(long started, long completed) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, completed - started));
    }

    private void abortStartup(Throwable failure) {
        lifecycleStopping = true;
        shutdownRequested = true;
        ReSyncServer current = server;
        if (current != null) {
            try {
                current.prepareNetworkShutdown();
                current.prepareCoreShutdown();
            } catch (RuntimeException preparationFailure) {
                failure.addSuppressed(preparationFailure);
            }
        }
        cleanupPluginOwnedResources();
        try {
            continueShutdown(true);
        } catch (RuntimeException shutdownFailure) {
            failure.addSuppressed(shutdownFailure);
        }
    }

    @Override
    public void onDisable() {
        shutdownRequested = true;
        boolean reloadReady;
        synchronized (networkLifecycleMonitor) {
            lifecycleStopping = true;
            cancelNetworkStateReloadTask();
            reloadReady = prepareNetworkStateReloadForDisable();
        }
        if (server != null) {
            try {
                server.prepareNetworkShutdown();
                server.prepareCoreShutdown();
            } catch (RuntimeException exception) {
                Log.error("ReSync shutdown primary preparation failed; core authority remains retained: "
                    + exception.getMessage(), exception);
            }
        }
        CompletableFuture<NetworkPlayerStateConfig> reloadAttempt = networkStateReloadAttempt;
        if (reloadAttempt != null) {
            reloadAttempt.completeExceptionally(new IllegalStateException("ReSync Is Shutting Down"));
        }
        cleanupPluginOwnedResources();
        continueShutdown(reloadReady);
        if (!reloadReady) {
            Log.warn("ReSync network state reload retained physical work during disable; finalization will retry without Bukkit scheduling");
        }
        Log.info("Plugin disabled.");
    }

    private void cleanupPluginOwnedResources() {
        boolean complete = true;
        ReSyncPluginMessageBridge bridge = pluginMessageBridge;
        if (!pluginMessageBridgeCleaned.get()) {
            if (bridge == null) {
                pluginMessageBridgeCleaned.set(true);
            } else {
                try {
                    bridge.unregister();
                    pluginMessageBridge = null;
                    pluginMessageBridgeCleaned.set(true);
                } catch (RuntimeException exception) {
                    complete = false;
                    Log.error("Error unregistering the plugin message bridge: " + exception.getMessage(), exception);
                }
            }
        }
        InteractiveSelectionManager selection = interactiveSelectionManager;
        if (!interactiveSelectionManagerCleaned.get()) {
            if (selection == null) {
                interactiveSelectionManagerCleaned.set(true);
            } else {
                try {
                    selection.shutdown();
                    interactiveSelectionManager = null;
                    interactiveSelectionManagerCleaned.set(true);
                } catch (RuntimeException exception) {
                    complete = false;
                    Log.error("Error stopping interactive selection: " + exception.getMessage(), exception);
                }
            }
        }
        Object placeholder = placeholderExpansion;
        if (!placeholderExpansionCleaned.get()) {
            if (placeholder == null) {
                placeholderExpansionCleaned.set(true);
            } else {
                try {
                    placeholder.getClass().getMethod("unregister").invoke(placeholder);
                    placeholderExpansion = null;
                    placeholderExpansionCleaned.set(true);
                } catch (Exception exception) {
                    complete = false;
                    Log.error("Error unregistering the placeholder expansion: " + exception.getMessage(), exception);
                }
            }
        }
        WebSocketServer currentWebSocketServer = wsServer;
        if (!webSocketServerCleaned.get()) {
            if (currentWebSocketServer == null) {
                webSocketServerCleaned.set(true);
            } else {
                try {
                    currentWebSocketServer.stop();
                    wsServer = null;
                    webSocketApiReady = false;
                    withdrawTlsMetadata();
                    webSocketServerCleaned.set(true);
                    Log.info("WebSocket server stopped.");
                } catch (Exception exception) {
                    complete = false;
                    Log.error("Error stopping WebSocket server: " + exception.getMessage(), exception);
                }
            }
        }
        boolean allCleaned = pluginMessageBridgeCleaned.get() && interactiveSelectionManagerCleaned.get()
            && placeholderExpansionCleaned.get() && webSocketServerCleaned.get();
        pluginOwnedResourcesCleaned.set(allCleaned && complete);
    }

    private void continueShutdown(boolean reloadReady) {
        ReSyncServer current = server;
        if (current == null) {
            cleanupPluginOwnedResources();
            if (reloadReady) {
                if (pluginOwnedResourcesCleaned.get()) {
                    clearShutdownReferences(null);
                } else {
                    cleanupPluginOwnedResources();
                    if (pluginOwnedResourcesCleaned.get()) {
                        clearShutdownReferences(null);
                    }
                }
            }
            return;
        }
        CompletionStage<Void> continuation;
        try {
            continuation = current.continuePreparedShutdown(reloadReady);
        } catch (RuntimeException exception) {
            continuation = CompletableFuture.failedFuture(exception);
        }
        continuation.whenComplete((unused, failure) -> {
            if (failure == null) {
                cleanupPluginOwnedResources();
                if (pluginOwnedResourcesCleaned.get()) {
                    clearShutdownReferences(current);
                } else {
                    cleanupPluginOwnedResources();
                    if (pluginOwnedResourcesCleaned.get()) {
                        clearShutdownReferences(current);
                    }
                }
            } else {
                Log.warn("ReSync shutdown remains retained for a scheduler-free retry: " + rootCause(failure).getMessage());
            }
        });
    }

    private void clearShutdownReferences(ReSyncServer expectedServer) {
        if (!pluginOwnedResourcesCleaned.get()) {
            return;
        }
        if (!shutdownReferencesCleared.compareAndSet(false, true)) {
            return;
        }
        if (expectedServer != null && server != expectedServer) {
            shutdownReferencesCleared.set(false);
            return;
        }
        networkPathSynchronizers = List.of();
        networkResourceSynchronizer = null;
        networkPlayerStateCoordinator = null;
        networkAgent = null;
        operatorDataRoot = null;
        activeDataRoot = null;
        if (expectedServer != null) {
            server = null;
        }
    }

    private ReSyncServer activeServer() {
        if (lifecycleStopping || shutdownRequested) {
            return null;
        }
        return server;
    }

    private void triggerDisabledShutdownRetry() {
        if (!lifecycleStopping || server == null) {
            return;
        }
        continueShutdown(false);
    }

    public static ReSync getInstance() {
        return instance;
    }

    public ReSyncServer getReSyncServer() {
        return server;
    }

    public ReSyncNetworkAgent getNetworkAgent() {
        return networkAgent;
    }

    public CompletionStage<Void> retryShutdown() {
        ReSyncServer current = server;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("ReSync Server Is Not Running"));
        }
        CompletionStage<Void> retry = current.retryShutdown();
        retry.whenComplete((unused, failure) -> {
            if (failure == null) {
                current.coreShutdownCompletion().whenComplete((ignored, coreFailure) -> {
                    if (coreFailure == null) {
                        cleanupPluginOwnedResources();
                        if (pluginOwnedResourcesCleaned.get()) {
                            clearShutdownReferences(current);
                        }
                    }
                });
            }
        });
        return retry;
    }

    public synchronized CompletionStage<NetworkPlayerStateConfig> reloadNetworkState() throws IOException {
        if (lifecycleStopping || shutdownRequested) {
            return CompletableFuture.failedFuture(new IllegalStateException("ReSync Is Shutting Down"));
        }
        if (networkAgent == null) throw new IllegalStateException("ReSync Network Agent Is Not Running");
        Path dataRoot = operatorDataRoot;
        if (dataRoot == null) {
            throw new IllegalStateException("ReSync Operator Data Root Is Unavailable");
        }
        NetworkPlayerStateConfig config = NetworkPlayerStateConfig.load(dataRoot);
        if (networkStateReloadAttempt != null && !networkStateReloadAttempt.isDone()) {
            return networkStateReloadAttempt;
        }
        CompletableFuture<NetworkPlayerStateConfig> result = new CompletableFuture<>();
        networkStateReloadAttempt = result;
        try {
            runOnMain(() -> beginNetworkStateReload(config, result));
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private void beginNetworkStateReload(NetworkPlayerStateConfig config, CompletableFuture<NetworkPlayerStateConfig> result) {
        if (lifecycleStopping || shutdownRequested) {
            result.completeExceptionally(new IllegalStateException("ReSync Is Shutting Down"));
            return;
        }
        ReSyncNetworkAgent currentAgent = networkAgent;
        if (currentAgent == null) {
            result.completeExceptionally(new IllegalStateException("ReSync Network Agent Is Not Running"));
            return;
        }
        if (currentAgent.hasActiveTransfers()) {
            scheduleNetworkStateReload(config, result);
            return;
        }
        CompletableFuture<Void> drain = new CompletableFuture<>();
        synchronized (networkLifecycleMonitor) {
            if (networkStateReloadAdmissionDrain != null) {
                return;
            }
            networkStateReloadAdmissionDrain = drain;
        }
        try {
            currentAgent.requestPlayerDataAdmissionQuiesce();
        } catch (IOException | RuntimeException exception) {
            synchronized (networkLifecycleMonitor) {
                if (networkStateReloadAdmissionDrain == drain) {
                    networkStateReloadAdmissionDrain = null;
                }
            }
            drain.completeExceptionally(exception);
            result.completeExceptionally(exception);
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                currentAgent.drainPlayerDataAdmission(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
                drain.complete(null);
            } catch (IOException | RuntimeException exception) {
                drain.completeExceptionally(new CompletionException(exception));
            }
        });
        drain.whenComplete((unused, failure) -> {
            if (drain.isCancelled()) {
                return;
            }
            synchronized (networkLifecycleMonitor) {
                if (networkStateReloadAdmissionDrain == drain) {
                    networkStateReloadAdmissionDrain = null;
                }
            }
            if (failure != null) {
                result.completeExceptionally(rootCause(failure));
                return;
            }
            try {
                runOnMain(() -> beginNetworkStateReloadAfterAdmissionDrain(config, result));
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
    }

    private void beginNetworkStateReloadAfterAdmissionDrain(NetworkPlayerStateConfig config,
                                                            CompletableFuture<NetworkPlayerStateConfig> result) {
        if (lifecycleStopping || shutdownRequested) {
            result.completeExceptionally(new IllegalStateException("ReSync Is Shutting Down"));
            return;
        }
        if (networkAgent == null) {
            result.completeExceptionally(new IllegalStateException("ReSync Network Agent Is Not Running"));
            return;
        }
        if (networkAgent.hasActiveTransfers()) {
            if (!resumePlayerDataAdmissionAfterReload()) {
                result.completeExceptionally(new IllegalStateException("Paper Player Data Admission Could Not Resume After Network State Reload Deferral"));
                return;
            }
            scheduleNetworkStateReload(config, result);
            return;
        }
        NetworkPlayerStateCoordinator previous;
        NetworkPlayerStateCoordinator replacement = null;
        ReSyncNetworkAgent.TransferHandlerSwap stagedTransferHandler = null;
        ReSyncNetworkAgent.TransferReplacementFence transferReplacementFence = null;
        boolean replacementPublished = false;
        Throwable retirementFailure = null;
        CompletionStage<Void> finalized;
        CompletionStage<Void> transferReplacementCompletion = null;
        NetworkPersistenceDrainController.ReplacementLease replacementLeaseCandidate = null;
        synchronized (networkLifecycleMonitor) {
            if (!networkStateReloadInProgress.compareAndSet(false, true)) {
                return;
            }
            if (lifecycleStopping || shutdownRequested) {
                networkStateReloadInProgress.set(false);
                result.completeExceptionally(new IllegalStateException("ReSync Is Shutting Down"));
                return;
            }
            previous = networkPlayerStateCoordinator;
            try {
                transferReplacementFence = networkAgent.tryBeginTransferReplacement().orElse(null);
                if (transferReplacementFence == null) {
                    networkStateReloadInProgress.set(false);
                    if (!resumePlayerDataAdmissionAfterReload()) {
                        result.completeExceptionally(new IllegalStateException(
                            "Paper Player Data Admission Could Not Resume Before Network State Reload Retry"));
                        return;
                    }
                    scheduleNetworkStateReload(config, result);
                    return;
                }
                replacementLeaseCandidate = networkAgent.persistenceDrain().beginReplacement("network-player-state-reload");
                if (config.enabled()) {
                    replacement = new NetworkPlayerStateCoordinator(this, config, networkAgent.persistenceDrain(), false,
                        networkAgent.playerDataAdmission());
                    replacement.stagePersistenceReplacement(previous);
                    replacement.stageStart();
                    stagedTransferHandler = networkAgent.stageTransferHandler(replacement);
                } else {
                    stagedTransferHandler = networkAgent.stageTransferHandler(null);
                }
                if (replacement != null) {
                    replacement.activateStagedStart();
                }
                networkAgent.commitTransferHandler(stagedTransferHandler, false);
                stagedTransferHandler = null;
                networkPlayerStateCoordinator = replacement;
                if (server != null) {
                    server.replaceNetworkPlayerStateCoordinator(replacement);
                }
                transferReplacementCompletion = transferReplacementFence.completion();
                transferReplacementFence.commit();
                transferReplacementFence = null;
                replacementPublished = true;
                if (previous != null) {
                    previous.disableAdmissionForReplacement();
                    previous.prepareForShutdown();
                }
            } catch (IOException | RuntimeException exception) {
                if (replacementPublished) {
                    retirementFailure = exception;
                    if (previous != null) {
                        try {
                            previous.disableAdmissionForReplacement();
                        } catch (RuntimeException admissionFailure) {
                            exception.addSuppressed(admissionFailure);
                        }
                    }
                } else {
                    boolean previousRetired = previous != null && previous.shutdownPrepared();
                    if (replacement != null && !previousRetired) {
                        rollbackStagedNetworkStateReplacement(replacement, previous, exception);
                    }
                    if (stagedTransferHandler != null) {
                        if (previousRetired && replacement != null) {
                            replacement.activateStagedStart();
                            networkAgent.commitTransferHandler(stagedTransferHandler, false);
                        } else {
                            networkAgent.rollbackTransferHandler(stagedTransferHandler);
                        }
                    }
                    networkPlayerStateCoordinator = previousRetired ? replacement : previous;
                    if (server != null) {
                        server.replaceNetworkPlayerStateCoordinator(previousRetired ? replacement : previous);
                    }
                    ReSyncNetworkAgent.TransferReplacementFence failedFence = transferReplacementFence;
                    if (failedFence != null) {
                        CompletionStage<Void> failedFenceCompletion = failedFence.completion();
                        NetworkPersistenceDrainController.ReplacementLease failedLease = replacementLeaseCandidate;
                        failedFence.rollback();
                        if (failedLease != null) {
                            failedFenceCompletion.whenComplete((unused, failure) -> {
                                if (failure == null) {
                                    failedLease.close();
                                }
                            });
                        }
                    } else if (replacementLeaseCandidate != null) {
                        replacementLeaseCandidate.close();
                    }
                    networkStateReloadInProgress.set(false);
                    if (!resumePlayerDataAdmissionAfterReload()) {
                        exception.addSuppressed(new IllegalStateException(
                            "Paper Player Data Admission Could Not Resume After Network State Reload Failure"));
                    }
                    result.completeExceptionally(exception);
                    return;
                }
            }
            try {
            CompletionStage<Void> coordinatorFinalized = retirementFailure != null
                ? CompletableFuture.failedFuture(retirementFailure)
                : previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.shutdownAfterPreparation(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
            finalized = transferReplacementCompletion == null
                ? coordinatorFinalized
                : coordinatorFinalized.thenCombine(transferReplacementCompletion, (unused, ignored) -> null);
            } catch (RuntimeException exception) {
                if (replacementPublished) {
                    finalized = CompletableFuture.failedFuture(exception);
                } else {
                    if (replacementLeaseCandidate != null) {
                        replacementLeaseCandidate.close();
                    }
                    networkStateReloadInProgress.set(false);
                    if (!resumePlayerDataAdmissionAfterReload()) {
                        exception.addSuppressed(new IllegalStateException(
                            "Paper Player Data Admission Could Not Resume After Network State Reload Failure"));
                    }
                    result.completeExceptionally(exception);
                    if (transferReplacementFence != null) {
                        transferReplacementFence.rollback();
                    }
                    return;
                }
            }
        }
        final NetworkPlayerStateCoordinator reloadPrevious = previous;
        final NetworkPlayerStateCoordinator reloadReplacement = replacement;
        final NetworkPersistenceDrainController.ReplacementLease replacementLease = replacementLeaseCandidate;
        NetworkStateReloadWork work = new NetworkStateReloadWork(config, reloadPrevious, reloadReplacement, result,
            replacementLease, finalized, replacementPublished);
        synchronized (networkLifecycleMonitor) {
            networkStateReloadWork = work;
        }
        finalized.whenComplete((unused, failure) -> {
            boolean schedule;
            synchronized (networkLifecycleMonitor) {
                if (!networkStateReloadInProgress.get()) {
                    if (!retainedNetworkStateReloadLeases.contains(replacementLease)) {
                        replacementLease.close();
                    }
                    return;
                }
                pendingNetworkStateReload = new PendingNetworkStateReload(config, reloadPrevious, reloadReplacement,
                    result, replacementLease, failure, work.replacementPublished());
                schedule = !lifecycleStopping;
            }
            if (schedule) {
                try {
                    runOnMain(this::completePendingNetworkStateReload);
                } catch (RuntimeException exception) {
                    Log.error("ReSync network state reload could not return to the Bukkit primary thread: " + exception.getMessage(), exception);
                }
            }
        });
    }

    private boolean prepareNetworkStateReloadForDisable() {
        if (!Thread.holdsLock(networkLifecycleMonitor)) {
            throw new IllegalStateException("Network reload lifecycle authority is not held");
        }
        CompletableFuture<Void> admissionDrain = networkStateReloadAdmissionDrain;
        if (admissionDrain != null) {
            networkStateReloadAdmissionDrain = null;
            admissionDrain.cancel(true);
            networkStateReloadInProgress.set(false);
            CompletableFuture<NetworkPlayerStateConfig> attempt = networkStateReloadAttempt;
            if (attempt != null) {
                attempt.completeExceptionally(new IllegalStateException("ReSync Network State Reload Was Cancelled For Shutdown"));
            }
            return true;
        }
        if (!networkStateReloadInProgress.get()) {
            return true;
        }
        PendingNetworkStateReload pending = pendingNetworkStateReload;
        if (pending == null) {
            NetworkStateReloadWork work = networkStateReloadWork;
            if (work == null || !work.finalized().toCompletableFuture().isDone()) {
                if (work == null) {
                    networkStateReloadInProgress.set(false);
                    CompletableFuture<NetworkPlayerStateConfig> attempt = networkStateReloadAttempt;
                    if (attempt != null) {
                        attempt.completeExceptionally(new IllegalStateException("ReSync Network State Reload Was Lost During Shutdown"));
                    }
                    return true;
                }
                IllegalStateException cancellation = new IllegalStateException("ReSync Network State Reload Was Cancelled For Shutdown");
                CompletionStage<Void> physical = work.previous() == null
                    ? CompletableFuture.completedFuture(null)
                    : work.previous().cancelShutdownForReplacement(cancellation);
                retainReloadLeaseUntilPhysicalCompletion(work.replacementLease(), work.previous(), physical);
                CompletableFuture<Void> finalized = work.finalized().toCompletableFuture();
                if (!finalized.isDone()) {
                    finalized.completeExceptionally(cancellation);
                }
                work.result().completeExceptionally(cancellation);
                networkStateReloadWork = null;
                pendingNetworkStateReload = null;
                networkStateReloadInProgress.set(false);
                return physical.toCompletableFuture().isDone() && (work.previous() == null || !work.previous().hasPendingWork());
            }
            if (pending == null) {
                Throwable failure = null;
                try {
                    work.finalized().toCompletableFuture().join();
                } catch (RuntimeException exception) {
                    failure = rootCause(exception);
                }
                pending = new PendingNetworkStateReload(work.config(), work.previous(), work.replacement(), work.result(),
                    work.replacementLease(), failure, work.replacementPublished());
                pendingNetworkStateReload = pending;
            }
        }
        completePendingNetworkStateReload(new IllegalStateException("ReSync Is Shutting Down"));
        return !networkStateReloadInProgress.get();
    }

    private void rollbackStagedNetworkStateReplacement(NetworkPlayerStateCoordinator replacement,
                                                       NetworkPlayerStateCoordinator previous,
                                                       Throwable failure) {
        try {
            if (previous == null || !previous.shutdownPrepared()) {
                replacement.rollbackPersistenceReplacement(previous);
                replacement.prepareForShutdown();
                replacement.finalizeShutdown();
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void cancelNetworkStateReloadTask() {
        networkStateReloadScheduled = false;
        BukkitTask task = networkStateReloadTask;
        networkStateReloadTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void scheduleNetworkStateReload(NetworkPlayerStateConfig config,
                                            CompletableFuture<NetworkPlayerStateConfig> result) {
        if (networkStateReloadScheduled) {
            return;
        }
        networkStateReloadScheduled = true;
        try {
            networkStateReloadTask = Bukkit.getScheduler().runTaskLater(this, () -> {
                networkStateReloadTask = null;
                networkStateReloadScheduled = false;
                beginNetworkStateReload(config, result);
            }, 1);
        } catch (RuntimeException exception) {
            networkStateReloadTask = null;
            networkStateReloadScheduled = false;
            result.completeExceptionally(exception);
        }
    }

    private void completePendingNetworkStateReload() {
        completePendingNetworkStateReload(null);
    }

    private void completePendingNetworkStateReload(Throwable schedulingFailure) {
        synchronized (networkLifecycleMonitor) {
            PendingNetworkStateReload pending = pendingNetworkStateReload;
            if (pending == null || !networkStateReloadInProgress.get()) {
                return;
            }
            try {
                Throwable failure = schedulingFailure != null ? schedulingFailure : pending.failure();
                if (!lifecycleStopping && !shutdownRequested && failure == null && !resumePlayerDataAdmissionAfterReload()) {
                    failure = new IllegalStateException("Paper Player Data Admission Could Not Resume After Network State Reload");
                } else if (!lifecycleStopping && !shutdownRequested && failure != null) {
                    resumePlayerDataAdmissionAfterReload();
                }
                if (failure != null) {
                    if (!lifecycleStopping && !shutdownRequested && pending.replacement() != null && networkAgent != null) {
                        networkAgent.resumeTransferHandler();
                    }
                    pending.result().completeExceptionally(rootCause(failure));
                    CompletionStage<Void> physical = pending.previous() == null
                        ? CompletableFuture.completedFuture(null)
                        : pending.replacementPublished()
                            ? retryPublishedCoordinatorShutdown(pending.previous(), failure)
                            : pending.previous().cancelShutdownForReplacement(failure);
                    retainReloadLeaseUntilPhysicalCompletion(pending.replacementLease(), pending.previous(), physical);
                } else {
                    if (!lifecycleStopping && !shutdownRequested && pending.replacement() != null && networkAgent != null) {
                        networkAgent.resumeTransferHandler();
                    }
                    pending.result().complete(pending.config());
                    pending.replacementLease().close();
                }
            } finally {
                pendingNetworkStateReload = null;
                networkStateReloadWork = null;
                networkStateReloadInProgress.set(false);
            }
        }
    }

    private CompletionStage<Void> retryPublishedCoordinatorShutdown(NetworkPlayerStateCoordinator previous,
                                                                     Throwable failure) {
        try {
            previous.disableAdmissionForReplacement();
            if (!previous.shutdownPrepared()) {
                previous.prepareForShutdown();
            }
            return previous.shutdownAfterPreparation(NetworkPersistenceDrainController.DEFAULT_DRAIN_TIMEOUT);
        } catch (RuntimeException exception) {
            exception.addSuppressed(failure);
            return CompletableFuture.failedFuture(exception);
        }
    }

    private boolean resumePlayerDataAdmissionAfterReload() {
        ReSyncNetworkAgent currentAgent = networkAgent;
        if (currentAgent == null) {
            return false;
        }
        try {
            currentAgent.rediscoverPlayerDataRoots();
            currentAgent.resumePlayerDataAdmission();
            return true;
        } catch (IOException | RuntimeException exception) {
            Log.error("Paper player data admission could not resume after network state reload: "
                + exception.getMessage(), exception);
            return false;
        }
    }

    private void retainReloadLeaseUntilPhysicalCompletion(NetworkPersistenceDrainController.ReplacementLease lease,
                                                          NetworkPlayerStateCoordinator previous,
                                                          CompletionStage<Void> physical) {
        if (lease == null) {
            return;
        }
        CompletableFuture<Void> completion = physical.toCompletableFuture();
        if (completion.isDone() && !completion.isCompletedExceptionally()
            && (previous == null || !previous.hasPendingWork())) {
            lease.close();
            finishRetainedShutdown();
            return;
        }
        retainedNetworkStateReloadLeases.add(lease);
        completion.whenComplete((unused, failure) -> {
            if (failure == null && (previous == null || !previous.hasPendingWork())) {
                if (retainedNetworkStateReloadLeases.remove(lease)) {
                    lease.close();
                    finishRetainedShutdown();
                }
            }
        });
    }

    private void finishRetainedShutdown() {
        if (server == null) {
            cleanupPluginOwnedResources();
            if (!pluginOwnedResourcesCleaned.get()) {
                cleanupPluginOwnedResources();
            }
            clearShutdownReferences(null);
        } else {
            triggerDisabledShutdownRetry();
        }
    }

    private void runOnMain(Runnable action) {
        if (lifecycleStopping) {
            if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
                throw new IllegalStateException("ReSync lifecycle action cannot leave the Bukkit primary thread during shutdown");
            }
            action.run();
            return;
        }
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTask(this, action);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private record PendingNetworkStateReload(NetworkPlayerStateConfig config,
                                             NetworkPlayerStateCoordinator previous,
                                             NetworkPlayerStateCoordinator replacement,
                                             CompletableFuture<NetworkPlayerStateConfig> result,
                                             NetworkPersistenceDrainController.ReplacementLease replacementLease,
                                             Throwable failure,
                                             boolean replacementPublished) {
    }

    private record NetworkStateReloadWork(NetworkPlayerStateConfig config,
                                          NetworkPlayerStateCoordinator previous,
                                          NetworkPlayerStateCoordinator replacement,
                                           CompletableFuture<NetworkPlayerStateConfig> result,
                                           NetworkPersistenceDrainController.ReplacementLease replacementLease,
                                           CompletionStage<Void> finalized,
                                           boolean replacementPublished) {
    }

    public InteractiveSelectionManager getInteractiveSelectionManager() {
        return interactiveSelectionManager;
    }

    private void registerPlaceholderExpansion() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            Log.fine("PlaceholderAPI not found, skipping PAPI hook");
            return;
        }
        try {
            Class<?> expansionClass = Class.forName("restudio.resync.placeholder.ReSyncPlaceholderExpansion");
            placeholderExpansion = expansionClass.getConstructor(ReSync.class).newInstance(this);
            boolean registered = (boolean) expansionClass.getMethod("register").invoke(placeholderExpansion);
            if (registered) {
                Log.info("PlaceholderAPI hook registered");
            } else {
                Log.warn("Failed to register PlaceholderAPI hook");
            }
        } catch (NoClassDefFoundError e) {
            Log.fine("PlaceholderAPI classes not available, skipping PAPI hook");
        } catch (Exception e) {
            Log.warn("Failed to register PlaceholderAPI hook: " + e.getMessage());
        }
    }
}
