package restudio.resync.network.paper;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.NetworkResource;
import restudio.resync.network.NetworkResourceCodec;
import restudio.resync.network.NetworkResourceMetadata;
import restudio.resync.network.NetworkResourceMutation;
import restudio.resync.network.NetworkResourceQuery;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig.PathPolicy;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig.ResourceConflictPolicy;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class NetworkPathSynchronizer implements ReSyncNetworkAgent.Listener {
    static final String RESOURCE_TYPE_PREFIX = "server-path:";
    private static final long COMMAND_SETTLE_TICKS = 40;
    private static final int LOCAL_CONFLICT_RETRIES = 3;
    private static final long SCAN_INTERVAL_TICKS = 100;
    private static final long MINIMUM_SYNC_RETRY_MILLIS = 1_000;
    private static final long MAXIMUM_SYNC_RETRY_MILLIS = 30_000;
    private final ReSync plugin;
    private final ReSyncNetworkAgent agent;
    private final PathPolicy policy;
    private final String resourceType;
    private final Path serverDirectory;
    private final List<PathRoot> roots;
    private final Set<Path> protectedPaths;
    private final int maximumPayloadBytes;
    private final NetworkResourceManifestStore manifest;
    private final Map<String, CompletableFuture<Void>> work = new ConcurrentHashMap<>();
    private final Map<String, PendingMutation> pending = new ConcurrentHashMap<>();
    private final RemoteChangeTracker remoteChanges = new RemoteChangeTracker();
    private final AtomicBoolean synchronizing = new AtomicBoolean();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean ready;
    private volatile int synchronizationFailures;
    private volatile long nextSynchronizationAttemptAt;
    private BukkitTask scanTask;
    private BukkitTask commandTask;

    public NetworkPathSynchronizer(ReSync plugin, ReSyncNetworkAgent agent, ReSyncNetworkAgentConfig config, PathPolicy policy, Path serverDirectory, Path dataDirectory) {
        this.plugin = plugin;
        this.agent = agent;
        Path normalizedDataDirectory = dataDirectory.toAbsolutePath().normalize();
        if (policy == null) {
            throw new IllegalArgumentException("Path Sync Policy Is Required");
        }
        this.policy = policy;
        this.resourceType = RESOURCE_TYPE_PREFIX + policy.id();
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
        this.roots = roots(this.policy.entries());
        this.maximumPayloadBytes = Math.min(config == null ? 0 : config.maximumPayloadBytes(), NetworkResourceCodec.MAXIMUM_RESOURCE_BYTES);
        Set<Path> protectedPaths = new LinkedHashSet<>();
        protectedPaths.add(normalizedDataDirectory.resolve("network"));
        protectedPaths.add(normalizedDataDirectory.resolve("resync.properties"));
        protectedPaths.add(normalizedDataDirectory.resolve("config.properties"));
        if (config != null && config.credentialFile() != null) {
            protectedPaths.add(config.credentialFile().toAbsolutePath().normalize());
        }
        if (config != null && config.tls().trustStore() != null) {
            protectedPaths.add(config.tls().trustStore().toAbsolutePath().normalize());
        }
        this.protectedPaths = Set.copyOf(protectedPaths);
        this.manifest = new NetworkResourceManifestStore(dataDirectory, "path-manifest-" + policy.id() + ".json");
    }

    public void start() {
        if (!policy.enabled()) {
            return;
        }
        agent.addListener(this);
        scanTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::scan, SCAN_INTERVAL_TICKS, SCAN_INTERVAL_TICKS);
        if (agent.connected()) {
            synchronize();
        }
    }

    public synchronized void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ready = false;
        agent.removeListener(this);
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        if (commandTask != null) {
            commandTask.cancel();
            commandTask = null;
        }
        work.clear();
        pending.clear();
        remoteChanges.clear();
    }

    @Override
    public void onConnected() {
        synchronizationFailures = 0;
        nextSynchronizationAttemptAt = 0;
        synchronize();
    }

    @Override
    public void onResourceChanged(NetworkResource resource) {
        if (closed.get() || !resourceType.equals(resource.type()) || resource.originNodeId().equals(agent.nodeId()) || root(resource.resourceId()).isEmpty()) {
            return;
        }
        NetworkResourceManifestStore.Entry known = manifest.get(resource.type(), resource.resourceId());
        if (known != null && resource.revision() <= known.revision()) {
            return;
        }
        String key = resource.metadata().key();
        remoteChanges.track(key, resource);
        enqueue(key, () -> applyRemoteChange(key, resource));
    }

    private void synchronize() {
        if (closed.get() || !agent.connected() || System.currentTimeMillis() < nextSynchronizationAttemptAt || !synchronizing.compareAndSet(false, true)) {
            return;
        }
        ready = false;
        fetchRemote(NetworkResourceQuery.firstPage(), new ArrayList<>())
            .thenCombine(CompletableFuture.supplyAsync(this::snapshotLocal), Reconciliation::new)
            .thenCompose(reconciliation -> reconcile(reconciliation.remote(), reconciliation.local()))
            .whenComplete((unused, throwable) -> {
                synchronizing.set(false);
                if (throwable != null) {
                    scheduleSynchronizationRetry();
                    Log.warn("ReSync path synchronization failed: " + rootMessage(throwable));
                    return;
                }
                synchronizationFailures = 0;
                nextSynchronizationAttemptAt = 0;
                clearAppliedRemoteChanges();
                ready = true;
                flushPending();
            });
    }

    private CompletableFuture<Void> applyRemoteChange(String key, NetworkResource resource) {
        if (!remoteChanges.isCurrent(key, resource)) {
            return CompletableFuture.completedFuture(null);
        }
        return apply(resource).whenComplete((unused, throwable) -> {
            if (throwable == null) {
                remoteChanges.complete(key, resource);
                return;
            }
            ready = false;
            scheduleSynchronizationRetry();
        });
    }

    private void scheduleSynchronizationRetry() {
        int failures = Math.min(synchronizationFailures + 1, 16);
        synchronizationFailures = failures;
        nextSynchronizationAttemptAt = System.currentTimeMillis() + synchronizationRetryDelay(failures);
    }

    static long synchronizationRetryDelay(int failures) {
        long multiplier = 1L << Math.clamp(failures - 1, 0, 15);
        return Math.min(MAXIMUM_SYNC_RETRY_MILLIS, MINIMUM_SYNC_RETRY_MILLIS * multiplier);
    }

    private void clearAppliedRemoteChanges() {
        remoteChanges.removeApplied(resource -> {
            NetworkResourceManifestStore.Entry applied = manifest.get(resource.type(), resource.resourceId());
            return applied != null && applied.revision() >= resource.revision();
        });
    }

    private CompletableFuture<List<NetworkResourceMetadata>> fetchRemote(NetworkResourceQuery query, List<NetworkResourceMetadata> resources) {
        return agent.listResources(query).thenCompose(page -> {
            page.resources().stream().filter(metadata -> resourceType.equals(metadata.type()) && root(metadata.resourceId()).isPresent()).forEach(resources::add);
            if (!page.hasNext()) {
                return CompletableFuture.completedFuture(List.copyOf(resources));
            }
            return fetchRemote(new NetworkResourceQuery(page.nextType(), page.nextResourceId(), 128), resources);
        });
    }

    private Map<String, LocalFile> snapshotLocal() {
        Map<String, LocalFile> files = new LinkedHashMap<>();
        for (PathRoot root : roots) {
            if (!Files.exists(root.path(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(root.path())) {
                Log.warn("Skipped Path Sync entry because it is a symbolic link: " + root.id());
                continue;
            }
            if (Files.isRegularFile(root.path(), LinkOption.NOFOLLOW_LINKS)) {
                readLocalFile(root.path(), files);
                continue;
            }
            if (!Files.isDirectory(root.path(), LinkOption.NOFOLLOW_LINKS)) {
                Log.warn("Skipped Path Sync entry because it is not a regular file or folder: " + root.id());
                continue;
            }
            try (Stream<Path> paths = Files.walk(root.path())) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)).forEach(path -> readLocalFile(path, files));
            } catch (IOException exception) {
                Log.warn("Scan Path Sync entry failed for " + root.id() + ": " + rootMessage(exception));
            }
        }
        return Map.copyOf(files);
    }

    private void readLocalFile(Path path, Map<String, LocalFile> files) {
        if (protectedPath(path) || path.getFileName() != null && path.getFileName().toString().endsWith(".resync.tmp")) {
            return;
        }
        String resourceId = resourceId(path);
        if (resourceId.getBytes(StandardCharsets.UTF_8).length > 2_048 || remoteChanges.contains(key(resourceId))) {
            return;
        }
        try {
            long size = Files.size(path);
            if (size > maximumPayloadBytes) {
                Log.warn("Skipped Path Sync file larger than " + maximumPayloadBytes + " bytes: " + resourceId);
                return;
            }
            byte[] payload = Files.readAllBytes(path);
            files.put(key(resourceId), new LocalFile(resourceId, NetworkPayloads.sha256(payload), payload));
        } catch (IOException exception) {
            Log.warn("Read Path Sync file failed for " + resourceId + ": " + rootMessage(exception));
        }
    }

    private CompletableFuture<Void> reconcile(List<NetworkResourceMetadata> remoteResources, Map<String, LocalFile> localFiles) {
        Map<String, NetworkResourceMetadata> remote = new LinkedHashMap<>();
        remoteResources.forEach(metadata -> remote.put(metadata.key(), metadata));
        Map<String, NetworkResourceManifestStore.Entry> known = manifest.snapshot();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(remote.keySet());
        keys.addAll(localFiles.keySet());
        known.forEach((key, entry) -> {
            if (resourceType.equals(entry.type()) && root(entry.resourceId()).isPresent()) {
                keys.add(key);
            }
        });
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String key : keys) {
            chain = chain.thenCompose(unused -> reconcile(remote.get(key), localFiles.get(key), known.get(key)));
        }
        return chain;
    }

    private CompletableFuture<Void> reconcile(NetworkResourceMetadata remote, LocalFile local, NetworkResourceManifestStore.Entry known) {
        if (remote == null) {
            if (local != null) {
                return publish(new PendingMutation(local.resourceId(), local.payload(), false), 0);
            }
            if (known != null) {
                if (localFileExists(known.resourceId())) {
                    return CompletableFuture.completedFuture(null);
                }
                return publish(new PendingMutation(known.resourceId(), new byte[0], true), 0);
            }
            return CompletableFuture.completedFuture(null);
        }
        if (local == null && !remote.deleted() && localFileExists(remote.resourceId())) {
            manifest.put(remote);
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

    private void scan() {
        if (closed.get() || !agent.connected()) {
            return;
        }
        if (!ready) {
            synchronize();
            return;
        }
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            Map<String, LocalFile> local = snapshotLocal();
            Map<String, NetworkResourceManifestStore.Entry> known = manifest.snapshot();
            local.forEach((key, file) -> {
                NetworkResourceManifestStore.Entry entry = known.get(key);
                if (!remoteChanges.contains(key) && !matches(file, entry)) {
                    submitLocal(new PendingMutation(file.resourceId(), file.payload(), false));
                }
            });
            known.forEach((key, entry) -> {
                if (resourceType.equals(entry.type()) && root(entry.resourceId()).isPresent() && !entry.deleted() && !local.containsKey(key) && !remoteChanges.contains(key) && !localFileExists(entry.resourceId())) {
                    submitLocal(new PendingMutation(entry.resourceId(), new byte[0], true));
                }
            });
        } finally {
            scanning.set(false);
        }
    }

    private void submitLocal(PendingMutation mutation) {
        pending.put(key(mutation.resourceId()), mutation);
        if (ready && agent.connected()) {
            enqueue(key(mutation.resourceId()), () -> publish(mutation));
        }
    }

    private CompletableFuture<Void> pull(NetworkResourceMetadata metadata) {
        return agent.getResource(metadata.type(), metadata.resourceId()).thenCompose(resource -> resource.map(this::apply).orElseGet(() -> CompletableFuture.completedFuture(null)));
    }

    private CompletableFuture<Void> publish(PendingMutation mutation) {
        NetworkResourceManifestStore.Entry entry = manifest.get(resourceType, mutation.resourceId());
        return publish(mutation, entry == null ? 0 : entry.revision());
    }

    private CompletableFuture<Void> publish(PendingMutation mutation, long expectedRevision) {
        return publish(mutation, expectedRevision, 0);
    }

    private CompletableFuture<Void> publish(PendingMutation mutation, long expectedRevision, int conflictAttempts) {
        if (!agent.connected()) {
            pending.put(key(mutation.resourceId()), mutation);
            return CompletableFuture.completedFuture(null);
        }
        NetworkResourceMutation request = new NetworkResourceMutation(resourceType, mutation.resourceId(), expectedRevision, mutation.payload(), mutation.deleted());
        return agent.setResource(request).thenAccept(resource -> {
            manifest.put(resource.metadata());
            pending.remove(key(mutation.resourceId()), mutation);
        }).exceptionallyCompose(throwable -> {
            if (!rootMessage(throwable).contains("Network Resource Revision Conflict")) {
                pending.put(key(mutation.resourceId()), mutation);
                return CompletableFuture.failedFuture(throwable);
            }
            return agent.getResource(resourceType, mutation.resourceId()).thenCompose(authoritative -> {
                if (authoritative.isEmpty()) {
                    pending.put(key(mutation.resourceId()), mutation);
                    return CompletableFuture.completedFuture(null);
                }
                pending.remove(key(mutation.resourceId()), mutation);
                NetworkResource current = authoritative.get();
                if (policy.conflictPolicy() == ResourceConflictPolicy.LOCAL_WINS && conflictAttempts < LOCAL_CONFLICT_RETRIES) {
                    return publish(mutation, current.revision(), conflictAttempts + 1);
                }
                return apply(current);
            });
        });
    }

    private CompletableFuture<Void> apply(NetworkResource resource) {
        return CompletableFuture.runAsync(() -> {
            PathRoot root = root(resource.resourceId()).orElseThrow(() -> new IllegalArgumentException("Shared file is outside the selected paths"));
            Path target = safeTarget(resource.resourceId());
            try {
                boolean changed;
                if (resource.deleted()) {
                    changed = Files.deleteIfExists(target);
                    if (changed) {
                        deleteEmptyParents(target.getParent(), root.path());
                    }
                } else {
                    if (resource.payload().length > maximumPayloadBytes) {
                        throw new IllegalArgumentException("Shared file exceeds the configured size limit");
                    }
                    Path parent = target.getParent();
                    if (parent == null) {
                        throw new IllegalArgumentException("Shared file path is invalid");
                    }
                    changed = !matchesTarget(target, resource.payloadHash(), resource.payload().length);
                    if (changed) {
                        Files.createDirectories(parent);
                        safeTarget(resource.resourceId());
                        Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".resync.tmp");
                        Files.write(temporary, resource.payload());
                        try {
                            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } catch (AtomicMoveNotSupportedException exception) {
                            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                        } finally {
                            Files.deleteIfExists(temporary);
                        }
                    }
                }
                manifest.put(resource.metadata());
                if (changed) {
                    scheduleCommands();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Apply Shared Server File Failed", exception);
            }
        });
    }

    private boolean matchesTarget(Path target, String payloadHash, int payloadBytes) throws IOException {
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == payloadBytes && NetworkPayloads.sha256(Files.readAllBytes(target)).equals(payloadHash);
    }

    private synchronized void scheduleCommands() {
        if (closed.get() || policy.commands().isEmpty()) {
            return;
        }
        if (commandTask != null) {
            commandTask.cancel();
        }
        commandTask = Bukkit.getScheduler().runTaskLater(plugin, this::runCommands, COMMAND_SETTLE_TICKS);
    }

    private void runCommands() {
        synchronized (this) {
            commandTask = null;
        }
        if (closed.get()) {
            return;
        }
        for (String command : policy.commands()) {
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                    Log.warn("Path Sync command was not accepted for " + policy.name() + ": " + command);
                }
            } catch (RuntimeException exception) {
                Log.warn("Path Sync command failed for " + policy.name() + ": " + rootMessage(exception));
            }
        }
    }

    private Path safeTarget(String resourceId) {
        Path target = serverDirectory.resolve(resourceId.replace('/', File.separatorChar)).normalize();
        if (!target.startsWith(serverDirectory) || root(resourceId).isEmpty() || protectedPath(target)) {
            throw new IllegalArgumentException("Shared file is outside the selected paths");
        }
        Path current = serverDirectory;
        Path relative = serverDirectory.relativize(target);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Shared file path contains a symbolic link");
            }
        }
        return target;
    }

    private void deleteEmptyParents(Path directory, Path stop) throws IOException {
        Path current = directory;
        while (current != null && !current.equals(stop) && current.startsWith(stop)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private Optional<PathRoot> root(String resourceId) {
        if (resourceId == null || resourceId.isBlank() || resourceId.startsWith("/") || resourceId.contains("\\") || resourceId.contains("\u0000")) {
            return Optional.empty();
        }
        Path candidate;
        try {
            candidate = serverDirectory.resolve(resourceId).normalize();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (!candidate.startsWith(serverDirectory) || protectedPath(candidate)) {
            return Optional.empty();
        }
        return roots.stream().filter(root -> candidate.startsWith(root.path())).findFirst();
    }

    private List<PathRoot> roots(Set<String> configured) {
        List<PathRoot> selected = configured.stream().map(id -> new PathRoot(id, serverDirectory.resolve(id.replace('/', File.separatorChar)).normalize())).filter(root -> root.path().startsWith(serverDirectory)).sorted(Comparator.comparingInt(root -> root.path().getNameCount())).toList();
        List<PathRoot> result = new ArrayList<>();
        for (PathRoot root : selected) {
            if (result.stream().noneMatch(existing -> root.path().startsWith(existing.path()))) {
                result.add(root);
            }
        }
        return List.copyOf(result);
    }

    private String resourceId(Path file) {
        return serverDirectory.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private boolean localFileExists(String resourceId) {
        try {
            return Files.isRegularFile(safeTarget(resourceId), LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void flushPending() {
        List.copyOf(pending.values()).forEach(mutation -> enqueue(key(mutation.resourceId()), () -> publish(mutation)));
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
                    Log.warn("ReSync path operation failed: " + rootMessage(throwable));
                }
            });
            return next;
        });
    }

    private PendingMutation mutation(LocalFile local, NetworkResourceMetadata remote) {
        return local == null ? new PendingMutation(remote.resourceId(), new byte[0], true) : new PendingMutation(local.resourceId(), local.payload(), false);
    }

    private boolean matches(LocalFile local, NetworkResourceManifestStore.Entry known) {
        if (known == null) {
            return false;
        }
        return known.deleted() ? local == null : local != null && local.payloadHash().equals(known.payloadHash());
    }

    private boolean matches(NetworkResourceMetadata remote, NetworkResourceManifestStore.Entry known) {
        return remote.revision() == known.revision() && remote.deleted() == known.deleted() && remote.payloadHash().equals(known.payloadHash());
    }

    private String key(String resourceId) {
        return NetworkResourceManifestStore.key(resourceType, resourceId);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private boolean protectedPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return protectedPaths.stream().anyMatch(protectedPath -> normalized.equals(protectedPath) || normalized.startsWith(protectedPath));
    }

    private record PathRoot(String id, Path path) {
    }

    private record LocalFile(String resourceId, String payloadHash, byte[] payload) {
    }

    private record PendingMutation(String resourceId, byte[] payload, boolean deleted) {
    }

    private record Reconciliation(List<NetworkResourceMetadata> remote, Map<String, LocalFile> local) {
    }

    static final class RemoteChangeTracker {
        private final Map<String, NetworkResource> changes = new ConcurrentHashMap<>();

        void track(String key, NetworkResource resource) {
            changes.compute(key, (ignored, current) -> current == null || resource.revision() > current.revision() ? resource : current);
        }

        boolean isCurrent(String key, NetworkResource resource) {
            return changes.get(key) == resource;
        }

        void complete(String key, NetworkResource resource) {
            changes.remove(key, resource);
        }

        boolean contains(String key) {
            return changes.containsKey(key);
        }

        void removeApplied(Predicate<NetworkResource> predicate) {
            changes.entrySet().removeIf(entry -> predicate.test(entry.getValue()));
        }

        void clear() {
            changes.clear();
        }
    }
}
