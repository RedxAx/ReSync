package restudio.resync.network.paper;

import org.bukkit.Bukkit;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationContext;
import restudio.resync.modules.flow.FlowResourceMutationListener;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.NetworkResource;
import restudio.resync.network.NetworkResourceMetadata;
import restudio.resync.network.NetworkResourceMutation;
import restudio.resync.network.NetworkResourcePage;
import restudio.resync.network.NetworkResourceQuery;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig.ResourceConflictPolicy;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig.ResourcePolicy;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.CommittedAsset;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class NetworkResourceSynchronizer implements ReSyncNetworkAgent.Listener, FlowResourceMutationListener {
    private static final int LOCAL_CONFLICT_RETRIES = 3;
    private final ReSync plugin;
    private final ReSyncNetworkAgent agent;
    private final FlowResourceRegistry registry;
    private final Supplier<AssetTransactionCoordinator> coordinator;
    private final ResourcePolicy policy;
    private final NetworkResourceManifestStore manifest;
    private final NetworkPersistenceDrainController persistenceDrain;
    private final NetworkPersistenceDrainController.Registration manifestRegistration;
    private final NetworkPersistenceDrainController.Registration producerRegistration;
    private final Consumer<NetworkResource> refresh;
    private final Map<String, CompletableFuture<Void>> work = new ConcurrentHashMap<>();
    private final Map<String, PendingMutation> pending = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<LocalSnapshot>> snapshots = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean synchronizing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean persistenceQuiesced = new AtomicBoolean();
    private final AtomicLong localGeneration = new AtomicLong();
    private final AtomicInteger synchronizationFailures = new AtomicInteger();
    private final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private volatile boolean ready;
    private volatile String startupFailure;

    public NetworkResourceSynchronizer(ReSync plugin, ReSyncNetworkAgent agent, FlowResourceRegistry registry, ResourcePolicy policy, Path dataDirectory, Consumer<NetworkResource> refresh) {
        this(plugin, agent, registry, policy, dataDirectory, refresh, agent == null ? null : agent.persistenceDrain());
    }

    public NetworkResourceSynchronizer(ReSync plugin, ReSyncNetworkAgent agent, FlowResourceRegistry registry, ResourcePolicy policy, Path dataDirectory, Consumer<NetworkResource> refresh, NetworkPersistenceDrainController persistenceDrain) {
        this(plugin, agent, registry, policy, dataDirectory, refresh, persistenceDrain, () -> null);
    }

    public NetworkResourceSynchronizer(ReSync plugin, ReSyncNetworkAgent agent, FlowResourceRegistry registry, ResourcePolicy policy, Path dataDirectory, Consumer<NetworkResource> refresh, NetworkPersistenceDrainController persistenceDrain, Supplier<AssetTransactionCoordinator> coordinator) {
        this.plugin = plugin;
        this.agent = agent;
        this.registry = registry;
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.policy = policy == null ? ResourcePolicy.disabled() : policy;
        this.manifest = new NetworkResourceManifestStore(dataDirectory);
        this.persistenceDrain = persistenceDrain;
        NetworkPersistenceDrainController.Registration registeredManifest = null;
        NetworkPersistenceDrainController.Registration registeredProducer = null;
        if (persistenceDrain != null) {
            try {
                registeredManifest = persistenceDrain.register(manifest.persistenceComponent("resource-manifest"));
                registeredProducer = persistenceDrain.registerProducer(new NetworkPersistenceDrainController.Producer() {
                    @Override
                    public String owner() {
                        return "resource-synchronizer";
                    }

                    @Override
                    public void closeAdmission() {
                        closeAdmissionForDrain();
                    }

                    @Override
                    public void resumeAdmission() {
                        resumeAdmissionAfterDrain();
                    }
                });
            } catch (RuntimeException exception) {
                if (registeredProducer != null) {
                    registeredProducer.close();
                }
                if (registeredManifest != null) {
                    registeredManifest.close();
                }
                throw exception;
            }
        }
        manifestRegistration = registeredManifest;
        producerRegistration = registeredProducer;
        this.refresh = refresh != null ? refresh : ignored -> {
        };
    }

    public void start() {
        requirePrimaryThread();
        if (!policy.enabled()) {
            return;
        }
        try {
            manifest.healthCheck();
        } catch (IOException | RuntimeException exception) {
            startupFailure = rootMessage(exception);
            prepareForShutdown();
            Log.warn("ReSync network resource persistence is unavailable: " + startupFailure);
            return;
        }
        try {
            registry.setMutationListener(this);
            agent.addListener(this);
            if (agent.connected()) {
                synchronize();
            }
        } catch (RuntimeException exception) {
            shutdown();
            throw exception;
        }
    }

    public void shutdown() {
        requirePrimaryThread();
        prepareForShutdown();
        finalizeShutdown();
    }

    public void prepareForShutdown() {
        requirePrimaryThread();
        synchronized (lifecycleMonitor) {
            closeAdmissionForDrain();
            closed.set(true);
            prepareBukkitAdmission();
        }
    }

    public void finalizeShutdown() {
        if (producerRegistration != null) {
            producerRegistration.close();
        }
        if (manifestRegistration != null) {
            manifestRegistration.close();
        }
    }

    public void flushPersistence() throws IOException {
        manifest.flush();
    }

    public void quiescePersistence() throws IOException {
        closeAdmissionForDrain();
        if (!persistenceQuiesced.compareAndSet(false, true)) {
            return;
        }
        try {
            manifest.quiesce();
        } catch (IOException | RuntimeException exception) {
            persistenceQuiesced.set(false);
            throw exception;
        }
    }

    public void resumePersistence() throws IOException {
        if (!persistenceQuiesced.get()) {
            return;
        }
        manifest.resume();
        persistenceQuiesced.set(false);
        resumeAdmissionAfterDrain();
    }

    public void rebindPersistence(Path networkRoot) throws IOException {
        if (!persistenceQuiesced.get()) {
            throw new IOException("ReSync network resource persistence must be quiesced before rebind");
        }
        manifest.rebind(networkRoot);
    }

    public void healthCheckPersistence() throws IOException {
        if (startupFailure != null) {
            throw new IOException("ReSync network resource synchronization startup failed: " + startupFailure);
        }
        manifest.healthCheck();
    }

    @Override
    public void saved(String type, String resourceId, String payload) {
        if (closed.get() || applying.get() || persistenceQuiesced.get() || !policy.includes(type)) {
            return;
        }
        localGeneration.incrementAndGet();
        submitLocal(new PendingMutation(type, resourceId, payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8), false));
    }

    @Override
    public void deleted(String type, String resourceId) {
        if (closed.get() || applying.get() || persistenceQuiesced.get() || !policy.includes(type)) {
            return;
        }
        localGeneration.incrementAndGet();
        submitLocal(new PendingMutation(type, resourceId, new byte[0], true));
    }

    @Override
    public void onConnected() {
        synchronizationFailures.set(0);
        synchronize();
    }

    @Override
    public void onResourceChanged(NetworkResource resource) {
        if (closed.get() || persistenceQuiesced.get() || !policy.includes(resource.type()) || resource.originNodeId().equals(agent.nodeId())) {
            return;
        }
        NetworkResourceManifestStore.Entry known = manifest.get(resource.type(), resource.resourceId());
        if (known != null && resource.revision() <= known.revision()) {
            return;
        }
        enqueue(resource.metadata().key(), () -> apply(resource));
    }

    private void submitLocal(PendingMutation mutation) {
        String key = mutation.key();
        pending.put(key, mutation);
        if (!ready || !agent.connected()) {
            return;
        }
        enqueue(key, () -> publish(mutation));
    }

    private void synchronize() {
        if (closed.get() || persistenceQuiesced.get() || !agent.connected() || !synchronizing.compareAndSet(false, true)) {
            return;
        }
        NetworkPersistenceDrainController.Lease lease = admit("resource-synchronization");
        if (persistenceDrain != null && lease == null) {
            synchronizing.set(false);
            return;
        }
        ready = false;
        fetchRemote(NetworkResourceQuery.firstPage(), new ArrayList<>()).thenCompose(remote -> snapshotLocal().thenCompose(local -> reconcile(remote, local))).whenComplete((unused, throwable) -> {
            synchronizing.set(false);
            if (lease != null) {
                lease.close();
            }
            if (throwable != null) {
                Log.warn("ReSync network resource synchronization failed: " + rootMessage(throwable));
                scheduleRetry();
                return;
            }
            synchronizationFailures.set(0);
            ready = true;
            flushPending();
        });
    }

    private void scheduleRetry() {
        if (closed.get() || persistenceQuiesced.get() || !agent.connected()) {
            return;
        }
        int failures = synchronizationFailures.updateAndGet(previous -> Math.min(previous + 1, 6));
        long delay = Math.min(600L, 20L << (failures - 1));
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!ready) {
                    synchronize();
                }
            }, delay);
        } catch (RuntimeException exception) {
            Log.warn("ReSync network resource synchronization retry could not be scheduled: " + rootMessage(exception));
        }
    }

    private CompletableFuture<List<NetworkResourceMetadata>> fetchRemote(NetworkResourceQuery query, List<NetworkResourceMetadata> resources) {
        return agent.listResources(query).thenCompose(page -> {
            resources.addAll(page.resources());
            if (!page.hasNext()) {
                return CompletableFuture.completedFuture(List.copyOf(resources));
            }
            return fetchRemote(new NetworkResourceQuery(page.nextType(), page.nextResourceId(), 128), resources);
        });
    }

    private CompletableFuture<LocalSnapshot> snapshotLocal() {
        return snapshotLocal(0);
    }

    private CompletableFuture<LocalSnapshot> snapshotLocal(int retries) {
        CompletableFuture<LocalSnapshot> result = new CompletableFuture<>();
        snapshots.add(result);
        result.whenComplete((unused, failure) -> snapshots.remove(result));
        synchronized (lifecycleMonitor) {
            if (closed.get() || persistenceQuiesced.get()) {
                result.completeExceptionally(new IllegalStateException("ReSync network resource persistence is quiesced"));
                return result;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        long generation = localGeneration.get();
                        List<FlowResourceAdapter<?>> adapters = List.copyOf(registry.adapters());
                        AssetTransactionCoordinator active = coordinator.get();
                        List<FlowResourceAdapter<?>> staged = active == null ? List.of() : adapters.stream()
                            .filter(adapter -> syncable(adapter) && adapter.coordinatedNetworkScan()).toList();
                        List<FlowResourceAdapter<?>> immediate = adapters.stream().filter(adapter -> !staged.contains(adapter)).toList();
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            try {
                                ScanPlan plan = plan(active, staged, immediate, generation);
                                Bukkit.getScheduler().runTask(plugin, () -> captureBatch(plan, 0, new LinkedHashMap<>(), result, retries));
                            } catch (RuntimeException exception) {
                                result.completeExceptionally(exception);
                            }
                        });
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                    }
                });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        }
        return result;
    }

    private ScanPlan plan(AssetTransactionCoordinator active, List<FlowResourceAdapter<?>> staged,
                          List<FlowResourceAdapter<?>> immediate, long generation) {
        if (active == null || staged.isEmpty()) {
            return new ScanPlan(active, active == null ? 0L : active.committedSequence(), generation,
                List.of(), immediate, Map.of(), Map.of());
        }
        return active.read(snapshot -> {
            Map<String, FlowResourceAdapter<?>> byType = new LinkedHashMap<>();
            staged.forEach(adapter -> byType.put(adapter.descriptor().typeId(), adapter));
            List<ScanItem> items = new ArrayList<>();
            Map<String, CommittedAsset> identities = new LinkedHashMap<>();
            snapshot.states().forEach((key, state) -> {
                FlowResourceAdapter<?> adapter = byType.get(key.type());
                if (adapter == null) {
                    return;
                }
                identities.put(NetworkResourceManifestStore.key(key.type(), key.id()), snapshotIdentity(snapshot, key));
                if (state instanceof Live) {
                    items.add(new ScanItem(adapter, key.id()));
                }
            });
            items.sort((left, right) -> {
                int type = left.adapter().descriptor().typeId().compareTo(right.adapter().descriptor().typeId());
                return type != 0 ? type : left.id().compareTo(right.id());
            });
            return new ScanPlan(active, snapshot.rootSequence(), generation, List.copyOf(items), immediate,
                Map.copyOf(identities), Map.copyOf(byType));
        });
    }

    private void captureBatch(ScanPlan plan, int index, Map<String, SerializedResource> captured,
                              CompletableFuture<LocalSnapshot> result, int retries) {
        if (result.isDone()) {
            return;
        }
        try {
            requireCurrent(plan);
            long deadline = System.nanoTime() + 2_000_000L;
            int next = index;
            while (next < plan.items().size() && next - index < 16 && (next == index || System.nanoTime() < deadline)) {
                ScanItem item = plan.items().get(next++);
                SerializedResource resource = captured(item.adapter(), item.id());
                if (resource != null) {
                    captured.put(resource.key(), resource);
                }
            }
            if (next < plan.items().size()) {
                int continuation = next;
                Bukkit.getScheduler().runTask(plugin, () -> captureBatch(plan, continuation, captured, result, retries));
            } else {
                captured.putAll(captureLocalResources(plan.immediate()));
                requireCurrent(plan);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> finishSnapshot(plan, captured, result, retries));
            }
        } catch (RuntimeException exception) {
            if (exception instanceof StaleSnapshotException) {
                retrySnapshot(result, retries);
            } else {
                result.completeExceptionally(exception);
            }
        }
    }

    private void finishSnapshot(ScanPlan plan, Map<String, SerializedResource> captured,
                                CompletableFuture<LocalSnapshot> result, int retries) {
        if (result.isDone()) {
            return;
        }
        try {
            Map<String, LocalResource> resources = materialize(captured);
            requireCurrent(plan);
            result.complete(new LocalSnapshot(resources, plan.coordinator(), plan.identities(), plan.adapters()));
        } catch (StaleSnapshotException exception) {
            retrySnapshot(result, retries);
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
    }

    private void retrySnapshot(CompletableFuture<LocalSnapshot> result, int retries) {
        if (retries >= 2) {
            result.completeExceptionally(new StaleSnapshotException());
            return;
        }
        snapshotLocal(retries + 1).whenComplete((retry, failure) -> {
            if (failure == null) {
                result.complete(retry);
            } else {
                result.completeExceptionally(failure);
            }
        });
    }

    private void requireCurrent(ScanPlan plan) {
        if (closed.get() || persistenceQuiesced.get() || localGeneration.get() != plan.generation()
            || plan.coordinator() != coordinator.get()
            || plan.coordinator() != null && plan.coordinator().committedSequence() != plan.sequence()) {
            throw new StaleSnapshotException();
        }
        plan.adapters().forEach((type, adapter) -> {
            if (registry.get(type) != adapter) {
                throw new StaleSnapshotException();
            }
        });
    }

    static CommittedAsset snapshotIdentity(Snapshot snapshot, AssetKey key) {
        return new CommittedAsset(snapshot.states().get(key), snapshot.paths().get(key), snapshot.lineages().get(key));
    }

    Map<String, LocalResource> scanLocalResources() {
        return materialize(captureLocalResources());
    }

    private Map<String, SerializedResource> captureLocalResources() {
        return captureLocalResources(List.copyOf(registry.adapters()));
    }

    private Map<String, SerializedResource> captureLocalResources(List<FlowResourceAdapter<?>> adapters) {
        Map<String, SerializedResource> resources = new LinkedHashMap<>();
        for (FlowResourceAdapter<?> adapter : adapters) {
            if (!syncable(adapter)) {
                continue;
            }
            try {
                for (String id : adapter.listIds()) {
                    SerializedResource resource = captured(adapter, id);
                    if (resource != null) {
                        resources.put(resource.key(), resource);
                    }
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Read ReSync resource catalog failed for " + adapter.descriptor().typeId(), exception);
            }
        }
        return Map.copyOf(resources);
    }

    private Map<String, LocalResource> materialize(Map<String, SerializedResource> captured) {
        Map<String, LocalResource> resources = new LinkedHashMap<>();
        captured.forEach((key, resource) -> {
            byte[] bytes = resource.payload().getBytes(StandardCharsets.UTF_8);
            resources.put(key, new LocalResource(resource.type(), resource.resourceId(), NetworkPayloads.sha256(bytes), bytes));
        });
        return Map.copyOf(resources);
    }

    private CompletableFuture<Void> reconcile(List<NetworkResourceMetadata> remoteResources, LocalSnapshot snapshot) {
        Map<String, LocalResource> localResources = snapshot.resources();
        Map<String, NetworkResourceMetadata> remote = new LinkedHashMap<>();
        remoteResources.stream().filter(this::syncable).forEach(metadata -> remote.put(metadata.key(), metadata));
        Map<String, NetworkResourceManifestStore.Entry> known = manifest.snapshot();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(remote.keySet());
        keys.addAll(localResources.keySet());
        known.forEach((key, entry) -> {
            if (syncable(registry.get(entry.type()))) {
                keys.add(key);
            }
        });
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String key : keys) {
            NetworkResourceMetadata authoritative = remote.get(key);
            LocalResource local = localResources.get(key);
            NetworkResourceManifestStore.Entry previous = known.get(key);
            chain = chain.thenCompose(unused -> {
                snapshot.requireCurrent(key, coordinator, registry);
                return reconcile(key, authoritative, local, previous, snapshot);
            });
        }
        return chain;
    }

    private CompletableFuture<Void> reconcile(String key, NetworkResourceMetadata remote, LocalResource local, NetworkResourceManifestStore.Entry known, LocalSnapshot snapshot) {
        if (remote == null) {
            if (local != null) {
                return publish(new PendingMutation(local.type(), local.resourceId(), local.payload(), false), 0, snapshot);
            }
            if (known != null) {
                return publish(new PendingMutation(known.type(), known.resourceId(), new byte[0], true), 0, snapshot);
            }
            return CompletableFuture.completedFuture(null);
        }
        if (known == null) {
            if (local == null || policy.conflictPolicy() == ResourceConflictPolicy.NETWORK_WINS) {
                return pull(remote, snapshot);
            }
            return publish(mutation(local, remote), remote.revision(), snapshot);
        }
        boolean localChanged = !matches(local, known);
        boolean remoteChanged = !matches(remote, known);
        if (localChanged && remoteChanged) {
            return policy.conflictPolicy() == ResourceConflictPolicy.NETWORK_WINS ? pull(remote, snapshot) : publish(mutation(local, remote), remote.revision(), snapshot);
        }
        if (remoteChanged) {
            return pull(remote, snapshot);
        }
        if (localChanged) {
            return publish(mutation(local, remote), remote.revision(), snapshot);
        }
        manifest.put(remote);
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> pull(NetworkResourceMetadata metadata, LocalSnapshot snapshot) {
        return agent.getResource(metadata.type(), metadata.resourceId()).thenCompose(resource -> {
            snapshot.requireCurrent(metadata.key(), coordinator, registry);
            return resource.map(value -> apply(value, snapshot)).orElseGet(() -> CompletableFuture.completedFuture(null));
        });
    }

    private CompletableFuture<Void> publish(PendingMutation mutation) {
        NetworkResourceManifestStore.Entry entry = manifest.get(mutation.type(), mutation.resourceId());
        return publish(mutation, entry == null ? 0 : entry.revision());
    }

    private CompletableFuture<Void> publish(PendingMutation mutation, long expectedRevision) {
        return publish(mutation, expectedRevision, null);
    }

    private CompletableFuture<Void> publish(PendingMutation mutation, long expectedRevision, LocalSnapshot snapshot) {
        return publish(mutation, expectedRevision, 0, snapshot);
    }

    private CompletableFuture<Void> publish(PendingMutation mutation, long expectedRevision, int conflictAttempts,
                                            LocalSnapshot snapshot) {
        if (persistenceQuiesced.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ReSync network resource persistence is quiesced"));
        }
        if (snapshot != null) {
            try {
                snapshot.requireCurrent(mutation.key(), coordinator, registry);
            } catch (StaleSnapshotException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        if (!agent.connected()) {
            pending.put(mutation.key(), mutation);
            return CompletableFuture.completedFuture(null);
        }
        NetworkResourceMutation request = new NetworkResourceMutation(mutation.type(), mutation.resourceId(), expectedRevision, mutation.payload(), mutation.deleted());
        return agent.setResource(request).thenAccept(resource -> {
            if (snapshot != null) {
                snapshot.requireCurrent(mutation.key(), coordinator, registry);
            }
            manifest.put(resource.metadata());
            pending.remove(mutation.key(), mutation);
        }).exceptionallyCompose(throwable -> {
            if (staleSnapshot(throwable)) {
                return CompletableFuture.failedFuture(throwable);
            }
            if (!rootMessage(throwable).contains("Network Resource Revision Conflict")) {
                pending.putIfAbsent(mutation.key(), mutation);
                return CompletableFuture.failedFuture(throwable);
            }
            return agent.getResource(mutation.type(), mutation.resourceId()).thenCompose(authoritative -> {
            if (snapshot != null) {
                snapshot.requireCurrent(mutation.key(), coordinator, registry);
            }
            if (authoritative.isEmpty()) {
                pending.putIfAbsent(mutation.key(), mutation);
                return CompletableFuture.completedFuture(null);
            }
            pending.remove(mutation.key(), mutation);
            NetworkResource current = authoritative.get();
            if (policy.conflictPolicy() == ResourceConflictPolicy.LOCAL_WINS && conflictAttempts < LOCAL_CONFLICT_RETRIES) {
                return publish(mutation, current.revision(), conflictAttempts + 1, snapshot);
            }
            return apply(current, snapshot);
            });
        });
    }

    private CompletableFuture<Void> apply(NetworkResource resource) {
        return apply(resource, null);
    }

    private CompletableFuture<Void> apply(NetworkResource resource, LocalSnapshot snapshot) {
        if (persistenceQuiesced.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ReSync network resource persistence is quiesced"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (lifecycleMonitor) {
            if (closed.get() || persistenceQuiesced.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("ReSync network resource persistence is quiesced"));
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (snapshot != null) {
                        try {
                            snapshot.requireCurrent(resource.metadata().key(), coordinator, registry);
                        } catch (RuntimeException exception) {
                            result.completeExceptionally(exception);
                            return;
                        }
                    }
                    FlowResourceAdapter<?> adapter = registry.get(resource.type());
                    if (!syncable(adapter)) {
                        result.complete(null);
                        return;
                    }
                    applying.set(true);
                    try {
                        NetworkResourceManifestStore.Entry known = manifest.get(resource.type(), resource.resourceId());
                        if (known != null && resource.revision() < known.revision()) {
                            result.complete(null);
                            return;
                        }
                        localGeneration.incrementAndGet();
                        if (resource.deleted()) {
                            if (adapter.get(resource.resourceId()) != null) {
                                FlowOperationResult<?> deleted = registry.delete(resource.type(), resource.resourceId(), FlowResourceMutationContext.system());
                                if (!deleted.success()) {
                                    throw new IllegalStateException(deleted.message());
                                }
                            }
                        } else {
                            Object value = adapter.deserialize(new String(resource.payload(), StandardCharsets.UTF_8));
                            if (value == null || !resource.resourceId().equals(id(adapter, value))) {
                                throw new IllegalArgumentException("Shared Resource ID Does Not Match Its Payload");
                            }
                            FlowOperationResult<?> saved = registry.save(resource.type(), value, FlowResourceMutationContext.system());
                            if (!saved.success()) {
                                throw new IllegalStateException(saved.message());
                            }
                        }
                        refresh.accept(resource);
                        manifest.put(resource.metadata());
                        result.complete(null);
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                    } finally {
                        applying.remove();
                    }
                });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        }
        return result;
    }

    private void flushPending() {
        List.copyOf(pending.values()).forEach(mutation -> enqueue(mutation.key(), () -> publish(mutation)));
    }

    private void enqueue(String key, Supplier<CompletableFuture<Void>> operation) {
        NetworkPersistenceDrainController.Lease lease = admit("resource-operation:" + key);
        if (persistenceDrain != null && lease == null) {
            return;
        }
        work.compute(key, (ignored, previous) -> {
            CompletableFuture<Void> base = previous == null ? CompletableFuture.completedFuture(null) : previous.handle((unused, throwable) -> null);
            CompletableFuture<Void> next = base.thenCompose(unused -> {
                if (closed.get()) {
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    return operation.get();
                } catch (RuntimeException exception) {
                    return CompletableFuture.failedFuture(exception);
                }
            });
            next.whenComplete((unused, throwable) -> {
                work.remove(key, next);
                if (lease != null) {
                    lease.close();
                }
                if (throwable != null) {
                    Log.warn("ReSync shared resource operation failed: " + rootMessage(throwable));
                }
            });
            return next;
        });
    }

    private NetworkPersistenceDrainController.Lease admit(String operation) {
        if (persistenceDrain == null) {
            return null;
        }
        try {
            return persistenceDrain.tryAcquire(operation, Duration.ZERO).orElse(null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void closeAdmissionForDrain() {
        ready = false;
        persistenceQuiesced.set(true);
        snapshots.forEach(snapshot -> snapshot.completeExceptionally(new IllegalStateException("ReSync network resource persistence is quiesced")));
    }

    private void prepareBukkitAdmission() {
        if (agent != null) {
            agent.removeListener(this);
        }
        if (registry != null) {
            registry.setMutationListener(FlowResourceMutationListener.NONE);
        }
    }

    private void resumeAdmissionAfterDrain() {
        if (closed.get()) {
            return;
        }
        persistenceQuiesced.set(false);
        if (registry != null) {
            registry.setMutationListener(this);
        }
        if (agent == null) {
            return;
        }
        agent.addListener(this);
        if (agent.connected()) {
            synchronize();
        }
    }

    private static void requirePrimaryThread() {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Network resource synchronizer lifecycle must run on the Bukkit main thread");
        }
    }

    private SerializedResource captured(FlowResourceAdapter<?> adapter, String id) {
        Object value = adapter.get(id);
        if (value == null) {
            return null;
        }
        String payload = serialize(adapter, value);
        return new SerializedResource(adapter.descriptor().typeId(), id, payload);
    }

    private boolean syncable(NetworkResourceMetadata metadata) {
        return policy.enabled() && policy.includes(metadata.type()) && syncable(registry.get(metadata.type()));
    }

    private boolean syncable(FlowResourceAdapter<?> adapter) {
        if (adapter == null || !adapter.durable()) {
            return false;
        }
        return policy.enabled() && policy.includes(adapter.descriptor().typeId()) && adapter.supportedOperations().containsAll(Set.of("get", "save", "delete"));
    }

    private PendingMutation mutation(LocalResource local, NetworkResourceMetadata remote) {
        return local == null ? new PendingMutation(remote.type(), remote.resourceId(), new byte[0], true) : new PendingMutation(local.type(), local.resourceId(), local.payload(), false);
    }

    private boolean matches(LocalResource local, NetworkResourceManifestStore.Entry known) {
        return known.deleted() ? local == null : local != null && local.payloadHash().equals(known.payloadHash());
    }

    private boolean matches(NetworkResourceMetadata remote, NetworkResourceManifestStore.Entry known) {
        return remote.revision() == known.revision() && remote.deleted() == known.deleted() && remote.payloadHash().equals(known.payloadHash());
    }

    @SuppressWarnings("unchecked")
    private String serialize(FlowResourceAdapter<?> adapter, Object value) {
        return ((FlowResourceAdapter<Object>) adapter).serialize(value);
    }

    @SuppressWarnings("unchecked")
    private String id(FlowResourceAdapter<?> adapter, Object value) {
        return ((FlowResourceAdapter<Object>) adapter).id(value);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private boolean staleSnapshot(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof StaleSnapshotException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record LocalResource(String type, String resourceId, String payloadHash, byte[] payload) {
        private String key() {
            return NetworkResourceManifestStore.key(type, resourceId);
        }
    }

    private record SerializedResource(String type, String resourceId, String payload) {
        private String key() {
            return NetworkResourceManifestStore.key(type, resourceId);
        }
    }

    private record ScanItem(FlowResourceAdapter<?> adapter, String id) {
    }

    private record ScanPlan(AssetTransactionCoordinator coordinator, long sequence, long generation,
                            List<ScanItem> items, List<FlowResourceAdapter<?>> immediate,
                            Map<String, CommittedAsset> identities,
                            Map<String, FlowResourceAdapter<?>> adapters) {
    }

    private record LocalSnapshot(Map<String, LocalResource> resources, AssetTransactionCoordinator coordinator,
                                 Map<String, CommittedAsset> identities,
                                 Map<String, FlowResourceAdapter<?>> adapters) {
        private void requireCurrent(String key, Supplier<AssetTransactionCoordinator> currentCoordinator,
                                    FlowResourceRegistry registry) {
            int separator = key.indexOf('\u0000');
            if (separator < 1 || !adapters.containsKey(key.substring(0, separator))) {
                return;
            }
            String type = key.substring(0, separator);
            if (coordinator != currentCoordinator.get() || registry.get(type) != adapters.get(type)) {
                throw new StaleSnapshotException();
            }
            AssetKey asset = new AssetKey(type, key.substring(separator + 1));
            if (!Objects.equals(identities.get(key), coordinator.committedAsset(asset).orElse(null))) {
                throw new StaleSnapshotException();
            }
        }
    }

    private static final class StaleSnapshotException extends IllegalStateException {
        private StaleSnapshotException() {
            super("ReSync resources changed during network snapshot");
        }
    }

    private record PendingMutation(String type, String resourceId, byte[] payload, boolean deleted) {
        private String key() {
            return NetworkResourceManifestStore.key(type, resourceId);
        }
    }
}
