package restudio.resync.modules;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import restudio.flow.data.FlowJobReference;
import restudio.resync.Log;
import restudio.resync.flow.jobs.FlowJobCompletedEvent;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.migration.EphemeralLifecycleParticipant;
import restudio.resync.migration.ReSyncPersistenceCoordinator;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class FlowJobModule implements Module, EphemeralLifecycleParticipant {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("flowJobs", "FlowJobs");
    private static final Duration COMPLETION_DISPATCH_TIMEOUT = FlowJobRegistry.DEFAULT_DRAIN_TIMEOUT;
    private final Object completionMonitor = new Object();
    private final Map<String, FlowJobReference.Snapshot<?>> pendingCompletions = new ConcurrentHashMap<>();
    private final Set<String> publishedCompletions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean completionDispatchScheduled = new AtomicBoolean();
    private final CompletionEventDispatcher completionEventDispatcher;
    private volatile FlowJobRegistry registry;
    private volatile Plugin plugin;
    private volatile Consumer<FlowJobReference.Snapshot<?>> completionListener;
    private volatile boolean completionListenerAttached;
    private volatile CompletionStage<Void> shutdownDrain = CompletableFuture.completedFuture(null);
    private volatile CompletionStage<Void> shutdownFinish = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> completionDispatch = CompletableFuture.completedFuture(null);
    private CompletionPhase completionPhase = CompletionPhase.CLOSED;
    private long completionDispatchGeneration;
    private boolean shutdownStarted;
    private boolean shutdownFinishStarted;

    public FlowJobModule() {
        this(bukkitEventDispatcher());
    }

    FlowJobModule(CompletionEventDispatcher completionEventDispatcher) {
        this.completionEventDispatcher = Objects.requireNonNull(completionEventDispatcher, "completionEventDispatcher");
    }

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        FlowJobRegistry current = new FlowJobRegistry();
        Consumer<FlowJobReference.Snapshot<?>> listener = this::bufferCompletion;
        synchronized (completionMonitor) {
            plugin = context.getPlugin();
            pendingCompletions.clear();
            publishedCompletions.clear();
            completionDispatchScheduled.set(false);
            completionDispatchGeneration++;
            completionDispatch = CompletableFuture.completedFuture(null);
            shutdownDrain = CompletableFuture.completedFuture(null);
            shutdownFinish = CompletableFuture.completedFuture(null);
            completionPhase = CompletionPhase.OPEN;
            shutdownStarted = false;
            shutdownFinishStarted = false;
            registry = current;
            completionListener = listener;
            current.addListener(listener);
            completionListenerAttached = true;
        }
        context.registerService(FlowJobRegistry.class, current);
        ReSyncPersistenceCoordinator persistence = context.getService(ReSyncPersistenceCoordinator.class);
        if (persistence != null) {
            persistence.registerEphemeral(this);
        }
    }

    @Override
    public void stop(ModuleContext context) {
        startShutdown();
        finishStopAsync(context);
    }

    @Override
    public void prepareStop(ModuleContext context) {
        startShutdown();
    }

    @Override
    public CompletionStage<Void> finishStopAsync(ModuleContext context) {
        startShutdown();
        FlowJobRegistry current;
        CompletionStage<Void> drain;
        CompletableFuture<Void> finish;
        boolean retry;
        synchronized (completionMonitor) {
            current = registry;
            if (current == null) {
                return CompletableFuture.completedFuture(null);
            }
            retry = shutdownFinishStarted && shutdownFinish.toCompletableFuture().isCompletedExceptionally();
            if (shutdownFinishStarted && !retry) {
                return shutdownFinish;
            }
            shutdownFinishStarted = true;
            finish = new CompletableFuture<>();
            shutdownFinish = finish;
            drain = shutdownDrain;
        }
        if (retry) {
            try {
                drain = current.shutdownAsync();
            } catch (RuntimeException failure) {
                drain = CompletableFuture.failedFuture(failure);
            }
            shutdownDrain = drain;
        }
        finishShutdown(drain).whenComplete((unused, failure) -> {
            if (failure == null) {
                finish.complete(null);
            } else {
                finish.completeExceptionally(failure);
            }
        });
        return finish;
    }

    public CompletionStage<Void> prepareSnapshot() {
        FlowJobRegistry current = registry;
        if (current == null) {
            return CompletableFuture.completedFuture(null);
        }
        beginPersistenceBoundary();
        CompletionStage<Void> drain = current.prepareSnapshot();
        return drain.whenComplete((unused, failure) -> {
            if (failure == null) {
                detachCompletionListener();
            }
        });
    }

    public CompletionStage<Void> prepareRestore() {
        FlowJobRegistry current = registry;
        if (current == null) {
            return CompletableFuture.completedFuture(null);
        }
        beginPersistenceBoundary();
        CompletionStage<Void> drain = current.prepareRestore();
        return drain.whenComplete((unused, failure) -> {
            if (failure == null) {
                detachCompletionListener();
            }
        });
    }

    public void resumeAfterSnapshot() {
        resumeAfterBoundary(false);
    }

    public void resumeAfterRestore() {
        resumeAfterBoundary(true);
    }

    @Override
    public void onTick() {
        dispatchPendingCompletions(false);
    }

    public FlowJobRegistry.Health health() {
        return registry == null ? new FlowJobRegistry.Health(false, FlowJobRegistry.State.CLOSED, 0, 0, 0, Map.of("registry", "uninitialized"))
            : registry.health();
    }

    @Override
    public String owner() {
        return "resync.jobs.ephemeral";
    }

    @Override
    public EphemeralLifecycleParticipant.Health lifecycleHealth() {
        FlowJobRegistry.Health current = health();
        String reason = current.failures().isEmpty() ? "" : current.failures().toString();
        return new EphemeralLifecycleParticipant.Health(current.available(), current.state().name(), current.physicalTasks(), reason);
    }

    private void bufferCompletion(FlowJobReference.Snapshot<?> snapshot) {
        if (snapshot == null || !terminal(snapshot.state())) {
            return;
        }
        pendingCompletions.putIfAbsent(snapshot.id(), snapshot);
    }

    private void beginPersistenceBoundary() {
        synchronized (completionMonitor) {
            if (completionPhase == CompletionPhase.OPEN) {
                completionPhase = CompletionPhase.PERSISTENCE_BOUNDARY;
                invalidateDispatchLocked();
            }
        }
    }

    private void resumeAfterBoundary(boolean restore) {
        FlowJobRegistry current = registry;
        if (current == null) {
            return;
        }
        attachCompletionListener();
        if (restore) {
            current.resumeAfterRestore();
        } else {
            current.resumeAfterSnapshot();
        }
        synchronized (completionMonitor) {
            if (!shutdownStarted) {
                completionPhase = CompletionPhase.OPEN;
            }
        }
        dispatchPendingCompletions(false);
    }

    private void startShutdown() {
        FlowJobRegistry current;
        CompletableFuture<Void> drain;
        synchronized (completionMonitor) {
            current = registry;
            if (current == null || shutdownStarted) {
                return;
            }
            shutdownStarted = true;
            drain = new CompletableFuture<>();
            shutdownDrain = drain;
            completionPhase = CompletionPhase.SHUTTING_DOWN;
            invalidateDispatchLocked();
        }
        try {
            current.closeAdmission();
        } catch (RuntimeException failure) {
            Log.warn("Flow job admission close failed during shutdown", failure);
        }
        try {
            current.shutdownAsync().whenComplete((unused, failure) -> {
                if (failure == null) {
                    drain.complete(null);
                } else {
                    drain.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            drain.completeExceptionally(failure);
        }
    }

    private CompletionStage<Void> finishShutdown(CompletionStage<Void> drain) {
        return drain.thenCompose(unused -> beginShutdownPublication()).whenComplete((unused, failure) -> {
            if (failure == null) {
                detachCompletionListener();
            }
            synchronized (completionMonitor) {
                completionPhase = failure == null ? CompletionPhase.CLOSED : CompletionPhase.SHUTTING_DOWN;
            }
        });
    }

    private CompletionStage<Void> beginShutdownPublication() {
        synchronized (completionMonitor) {
            completionPhase = CompletionPhase.SHUTDOWN_DRAINING;
            invalidateDispatchLocked();
        }
        return dispatchPendingCompletions(true);
    }

    private void attachCompletionListener() {
        synchronized (completionMonitor) {
            if (!completionListenerAttached && registry != null && completionListener != null) {
                registry.addListener(completionListener);
                completionListenerAttached = true;
            }
        }
    }

    private void detachCompletionListener() {
        synchronized (completionMonitor) {
            if (completionListenerAttached && registry != null && completionListener != null) {
                registry.removeListener(completionListener);
                completionListenerAttached = false;
            }
        }
    }

    private CompletionStage<Void> dispatchPendingCompletions(boolean shutdownFlush) {
        CompletableFuture<Void> result;
        long generation;
        synchronized (completionMonitor) {
            if (pendingCompletions.isEmpty() || !dispatchAllowedLocked(shutdownFlush)) {
                return CompletableFuture.completedFuture(null);
            }
            if (completionDispatchScheduled.get()) {
                return completionDispatch;
            }
            completionDispatchScheduled.set(true);
            generation = ++completionDispatchGeneration;
            result = new CompletableFuture<>();
            completionDispatch = result;
        }
        boolean schedulerReady = schedulerAvailable();
        if (isPrimaryThread() && schedulerReady) {
            runDispatch(generation, shutdownFlush);
            return result;
        }
        if (!schedulerReady) {
            failDispatch(generation, shutdownFlush,
                new IllegalStateException("Flow Job Completion Scheduler Is Unavailable"));
            return result;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> runDispatch(generation, shutdownFlush));
            if (shutdownFlush) {
                CompletableFuture.delayedExecutor(COMPLETION_DISPATCH_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)
                    .execute(() -> {
                        if (!result.isDone()) {
                            failDispatch(generation, true,
                                new IllegalStateException("Flow Job Completion Dispatch Timed Out"));
                        }
                    });
            }
        } catch (RuntimeException failure) {
            failDispatch(generation, shutdownFlush, failure);
        }
        return result;
    }

    private void runDispatch(long generation, boolean shutdownFlush) {
        Throwable firstFailure = null;
        while (true) {
            FlowJobReference.Snapshot<?> snapshot;
            synchronized (completionMonitor) {
                if (!dispatchCurrentLocked(generation, shutdownFlush) || pendingCompletions.isEmpty()) {
                    break;
                }
                snapshot = pendingCompletions.entrySet().stream()
                    .min(Comparator.comparing((Map.Entry<String, FlowJobReference.Snapshot<?>> entry) -> entry.getValue().createdAt())
                        .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getValue)
                    .orElse(null);
                if (snapshot == null) {
                    break;
                }
                if (!publishedCompletions.add(snapshot.id())) {
                    pendingCompletions.remove(snapshot.id(), snapshot);
                    continue;
                }
                FlowJobCompletedEvent event;
                try {
                    event = new FlowJobCompletedEvent(snapshot);
                    completionEventDispatcher.verifyAvailable();
                } catch (RuntimeException | Error failure) {
                    publishedCompletions.remove(snapshot.id());
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                    break;
                }
                pendingCompletions.remove(snapshot.id(), snapshot);
                try {
                    completionEventDispatcher.dispatch(event);
                } catch (RuntimeException | Error failure) {
                    Log.error("Flow job completion event listener failed for " + snapshot.id(), failure);
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                }
            }
        }
        finishDispatch(generation, shutdownFlush, firstFailure);
    }

    private void finishDispatch(long generation, boolean shutdownFlush, Throwable failure) {
        synchronized (completionMonitor) {
            if (!dispatchCurrentLocked(generation, shutdownFlush)) {
                return;
            }
            completionDispatchScheduled.set(false);
            if (failure != null) {
                Log.error("Flow job completion event dispatch failed", failure);
                completionDispatch.completeExceptionally(failure);
                return;
            }
            completionDispatch.complete(null);
        }
    }

    private void failDispatch(long generation, boolean shutdownFlush, Throwable failure) {
        synchronized (completionMonitor) {
            if (!dispatchCurrentLocked(generation, shutdownFlush)) {
                return;
            }
            completionDispatchScheduled.set(false);
            Log.error("Flow job completion event dispatch unavailable", failure);
            completionDispatch.completeExceptionally(failure);
        }
    }

    private void invalidateDispatchLocked() {
        completionDispatchGeneration++;
        if (completionDispatchScheduled.getAndSet(false)) {
            completionDispatch.complete(null);
        }
    }

    private boolean dispatchAllowedLocked(boolean shutdownFlush) {
        return shutdownFlush ? completionPhase == CompletionPhase.SHUTDOWN_DRAINING : completionPhase == CompletionPhase.OPEN;
    }

    private boolean dispatchCurrentLocked(long generation, boolean shutdownFlush) {
        return completionDispatchScheduled.get() && generation == completionDispatchGeneration
            && dispatchAllowedLocked(shutdownFlush);
    }

    private boolean isPrimaryThread() {
        try {
            return Bukkit.getServer() != null && Bukkit.isPrimaryThread();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean schedulerAvailable() {
        try {
            return Bukkit.getServer() != null && plugin != null && plugin.isEnabled();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private enum CompletionPhase {
        OPEN,
        PERSISTENCE_BOUNDARY,
        SHUTTING_DOWN,
        SHUTDOWN_DRAINING,
        CLOSED
    }

    private static CompletionEventDispatcher bukkitEventDispatcher() {
        return new CompletionEventDispatcher() {
            @Override
            public void verifyAvailable() {
                if (Bukkit.getPluginManager() == null) {
                    throw new IllegalStateException("Flow Job Completion Plugin Manager Is Unavailable");
                }
            }

            @Override
            public void dispatch(FlowJobCompletedEvent event) {
                PluginManager manager = Bukkit.getPluginManager();
                if (manager == null) {
                    throw new IllegalStateException("Flow Job Completion Plugin Manager Is Unavailable");
                }
                manager.callEvent(event);
            }
        };
    }

    @FunctionalInterface
    interface CompletionEventDispatcher {
        void dispatch(FlowJobCompletedEvent event);

        default void verifyAvailable() {
        }
    }

    private boolean terminal(FlowJobReference.State state) {
        return state == FlowJobReference.State.SUCCEEDED || state == FlowJobReference.State.FAILED || state == FlowJobReference.State.CANCELLED;
    }
}
