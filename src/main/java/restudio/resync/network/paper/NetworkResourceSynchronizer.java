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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class NetworkResourceSynchronizer implements ReSyncNetworkAgent.Listener, FlowResourceMutationListener {
    private static final int LOCAL_CONFLICT_RETRIES = 3;
    private final ReSync plugin;
    private final ReSyncNetworkAgent agent;
    private final FlowResourceRegistry registry;
    private final ResourcePolicy policy;
    private final NetworkResourceManifestStore manifest;
    private final Consumer<NetworkResource> refresh;
    private final Map<String, CompletableFuture<Void>> work = new ConcurrentHashMap<>();
    private final Map<String, PendingMutation> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean synchronizing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private volatile boolean ready;

    public NetworkResourceSynchronizer(ReSync plugin, ReSyncNetworkAgent agent, FlowResourceRegistry registry, ResourcePolicy policy, Path dataDirectory, Consumer<NetworkResource> refresh) {
        this.plugin = plugin;
        this.agent = agent;
        this.registry = registry;
        this.policy = policy == null ? ResourcePolicy.disabled() : policy;
        this.manifest = new NetworkResourceManifestStore(dataDirectory);
        this.refresh = refresh != null ? refresh : ignored -> {
        };
    }

    public void start() {
        if (!policy.enabled()) {
            return;
        }
        registry.setMutationListener(this);
        agent.addListener(this);
        if (agent.connected()) {
            synchronize();
        }
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ready = false;
        agent.removeListener(this);
        registry.setMutationListener(FlowResourceMutationListener.NONE);
        work.clear();
        pending.clear();
    }

    @Override
    public void saved(String type, String resourceId, String payload) {
        if (closed.get() || applying.get() || !policy.includes(type)) {
            return;
        }
        submitLocal(new PendingMutation(type, resourceId, payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8), false));
    }

    @Override
    public void deleted(String type, String resourceId) {
        if (closed.get() || applying.get() || !policy.includes(type)) {
            return;
        }
        submitLocal(new PendingMutation(type, resourceId, new byte[0], true));
    }

    @Override
    public void onConnected() {
        synchronize();
    }

    @Override
    public void onResourceChanged(NetworkResource resource) {
        if (closed.get() || !policy.includes(resource.type()) || resource.originNodeId().equals(agent.nodeId())) {
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
        if (closed.get() || !agent.connected() || !synchronizing.compareAndSet(false, true)) {
            return;
        }
        ready = false;
        fetchRemote(NetworkResourceQuery.firstPage(), new ArrayList<>()).thenCompose(remote -> snapshotLocal().thenCompose(local -> reconcile(remote, local))).whenComplete((unused, throwable) -> {
            synchronizing.set(false);
            if (throwable != null) {
                Log.warn("ReSync network resource synchronization failed: " + rootMessage(throwable));
                return;
            }
            ready = true;
            flushPending();
        });
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

    private CompletableFuture<Map<String, LocalResource>> snapshotLocal() {
        CompletableFuture<Map<String, LocalResource>> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Map<String, LocalResource> resources = new LinkedHashMap<>();
                for (FlowResourceAdapter<?> adapter : registry.adapters()) {
                    if (!syncable(adapter)) {
                        continue;
                    }
                    try {
                        for (String id : adapter.listIds()) {
                            LocalResource resource = local(adapter, id);
                            if (resource != null) {
                                resources.put(resource.key(), resource);
                            }
                        }
                    } catch (RuntimeException exception) {
                        Log.warn("Read ReSync resource catalog failed for " + adapter.descriptor().typeId() + ": " + rootMessage(exception));
                    }
                }
                result.complete(Map.copyOf(resources));
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    private CompletableFuture<Void> reconcile(List<NetworkResourceMetadata> remoteResources, Map<String, LocalResource> localResources) {
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
            chain = chain.thenCompose(unused -> reconcile(key, authoritative, local, previous));
        }
        return chain;
    }

    private CompletableFuture<Void> reconcile(String key, NetworkResourceMetadata remote, LocalResource local, NetworkResourceManifestStore.Entry known) {
        if (remote == null) {
            if (local != null) {
                return publish(new PendingMutation(local.type(), local.resourceId(), local.payload(), false), 0);
            }
            if (known != null) {
                return publish(new PendingMutation(known.type(), known.resourceId(), new byte[0], true), 0);
            }
            return CompletableFuture.completedFuture(null);
        }
        if (known == null) {
            if (local == null || policy.conflictPolicy() == ResourceConflictPolicy.NETWORK_WINS) {
                return pull(remote);
            }
            return publish(mutation(local, remote), remote.revision());
        }
        boolean localChanged = !matches(local, known);
        boolean remoteChanged = !matches(remote, known);
        if (localChanged && remoteChanged) {
            return policy.conflictPolicy() == ResourceConflictPolicy.NETWORK_WINS ? pull(remote) : publish(mutation(local, remote), remote.revision());
        }
        if (remoteChanged) {
            return pull(remote);
        }
        if (localChanged) {
            return publish(mutation(local, remote), remote.revision());
        }
        manifest.put(remote);
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> pull(NetworkResourceMetadata metadata) {
        return agent.getResource(metadata.type(), metadata.resourceId()).thenCompose(resource -> resource.map(this::apply).orElseGet(() -> CompletableFuture.completedFuture(null)));
    }

    private CompletableFuture<Void> publish(PendingMutation mutation) {
        NetworkResourceManifestStore.Entry entry = manifest.get(mutation.type(), mutation.resourceId());
        return publish(mutation, entry == null ? 0 : entry.revision());
    }

    private CompletableFuture<Void> publish(PendingMutation mutation, long expectedRevision) {
        return publish(mutation, expectedRevision, 0);
    }

    private CompletableFuture<Void> publish(PendingMutation mutation, long expectedRevision, int conflictAttempts) {
        if (!agent.connected()) {
            pending.put(mutation.key(), mutation);
            return CompletableFuture.completedFuture(null);
        }
        NetworkResourceMutation request = new NetworkResourceMutation(mutation.type(), mutation.resourceId(), expectedRevision, mutation.payload(), mutation.deleted());
        return agent.setResource(request).thenAccept(resource -> {
            manifest.put(resource.metadata());
            pending.remove(mutation.key(), mutation);
        }).exceptionallyCompose(throwable -> {
            if (!rootMessage(throwable).contains("Network Resource Revision Conflict")) {
                pending.put(mutation.key(), mutation);
                return CompletableFuture.failedFuture(throwable);
            }
            return agent.getResource(mutation.type(), mutation.resourceId()).thenCompose(authoritative -> {
            if (authoritative.isEmpty()) {
                pending.put(mutation.key(), mutation);
                return CompletableFuture.completedFuture(null);
            }
            pending.remove(mutation.key(), mutation);
            NetworkResource current = authoritative.get();
            if (policy.conflictPolicy() == ResourceConflictPolicy.LOCAL_WINS && conflictAttempts < LOCAL_CONFLICT_RETRIES) {
                return publish(mutation, current.revision(), conflictAttempts + 1);
            }
            return apply(current);
            });
        });
    }

    private CompletableFuture<Void> apply(NetworkResource resource) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            FlowResourceAdapter<?> adapter = registry.get(resource.type());
            if (!syncable(adapter)) {
                result.complete(null);
                return;
            }
            applying.set(true);
            try {
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
        return result;
    }

    private void flushPending() {
        List.copyOf(pending.values()).forEach(mutation -> enqueue(mutation.key(), () -> publish(mutation)));
    }

    private void enqueue(String key, Supplier<CompletableFuture<Void>> operation) {
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
                if (throwable != null) {
                    Log.warn("ReSync shared resource operation failed: " + rootMessage(throwable));
                }
            });
            return next;
        });
    }

    private LocalResource local(FlowResourceAdapter<?> adapter, String id) {
        Object value = adapter.get(id);
        if (value == null) {
            return null;
        }
        String payload = serialize(adapter, value);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return new LocalResource(adapter.descriptor().typeId(), id, NetworkPayloads.sha256(bytes), bytes);
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

    private record LocalResource(String type, String resourceId, String payloadHash, byte[] payload) {
        private String key() {
            return NetworkResourceManifestStore.key(type, resourceId);
        }
    }

    private record PendingMutation(String type, String resourceId, byte[] payload, boolean deleted) {
        private String key() {
            return NetworkResourceManifestStore.key(type, resourceId);
        }
    }
}
