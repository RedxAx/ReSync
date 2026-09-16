package restudio.resync.flow.handler.generic;

import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class RegionPersistenceParticipant implements CloseablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.flow-regions";
    public static final String DIRECTORY = "flow-regions";

    private final Path scopeRoot;
    private final RegionHandler handler;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Object lifecycleMonitor = new Object();
    private final Object topologyMonitor = new Object();
    private final Map<String, Task<?>> fileTails = new HashMap<>();
    private final Set<Task<?>> pendingTasks = ConcurrentHashMap.newKeySet();
    private final Set<Task<?>> runningTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, PortableEntry> portableEntries = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final Map<String, Throwable> retainedFailures = new LinkedHashMap<>();
    private volatile Path activeScopeRoot;
    private volatile Path activeCanonicalScopeRoot;
    private volatile Object activeScopeFileKey;
    private volatile Path activeRoot;
    private volatile Path activeCanonicalRoot;
    private volatile Object activeRootFileKey;
    private boolean admissionOpen = true;
    private boolean closed;
    private boolean detached;

    public RegionPersistenceParticipant(Path scopeRoot, RegionHandler handler) throws IOException {
        this(scopeRoot, handler, newExecutor(), true);
    }

    public RegionPersistenceParticipant(Path scopeRoot) throws IOException {
        this(scopeRoot, new RegionHandler());
    }

    public RegionPersistenceParticipant(Path scopeRoot, RegionHandler handler, Executor executor) throws IOException {
        this(scopeRoot, handler, executor, false);
    }

    private RegionPersistenceParticipant(Path scopeRoot, RegionHandler handler, Executor executor, boolean ownsExecutor) throws IOException {
        this.scopeRoot = MigrationPaths.requireDirectory(scopeRoot, "scopeRoot");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownsExecutor && executor instanceof ExecutorService service ? service : null;
        try {
            Path root = expectedRoot(this.scopeRoot);
            ensureRoot(root, true);
            validateTree(root);
            Binding scopeBinding = binding(this.scopeRoot);
            Binding rootBinding = binding(root);
            Map<String, PortableEntry> entries = portableIndex(root);
            this.activeScopeRoot = this.scopeRoot;
            this.activeCanonicalScopeRoot = scopeBinding.canonicalRoot();
            this.activeScopeFileKey = scopeBinding.fileKey();
            this.activeRoot = root;
            this.activeCanonicalRoot = rootBinding.canonicalRoot();
            this.activeRootFileKey = rootBinding.fileKey();
            synchronized (topologyMonitor) {
                portableEntries.putAll(entries);
            }
            handler.attachPersistence(this);
        } catch (IOException | RuntimeException exception) {
            if (ownedExecutor != null) {
                ownedExecutor.shutdownNow();
            }
            throw exception;
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return activeRoot;
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).subtreeRoot().build();
    }

    @Override
    public boolean owns(Path file) {
        Path candidate = MigrationPaths.requirePath(file, "file");
        Path root = activeRoot;
        return candidate.startsWith(root);
    }

    public Path resolve(String requestedPath) {
        Path root = activeRoot;
        return MigrationPaths.resolveInside(root, requestedPath);
    }

    public boolean mutationAdmissionOpen() {
        synchronized (lifecycleMonitor) {
            return admissionOpen && !closed && !detached;
        }
    }

    public boolean isClosed() {
        synchronized (lifecycleMonitor) {
            return closed;
        }
    }

    public void requireMutationAdmission() {
        synchronized (lifecycleMonitor) {
            requireMutationAdmissionLocked();
        }
    }

    public void validateReadableFile(Path file) throws IOException {
        synchronized (topologyMonitor) {
            Path target = validateTarget(file, false);
            verifyBinding();
            validatePortableTargetLocked(target, false);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw new IOException("Region clipboard file is not a regular non-symbolic-link file: " + target);
            }
        }
    }

    public CompletableFuture<Void> write(Path file, byte[] bytes) {
        byte[] content = Objects.requireNonNull(bytes, "bytes").clone();
        RegionHandler.validateClipboardJson(new String(content, StandardCharsets.UTF_8));
        return enqueue(file, true, () -> {
            ensureTaskActive();
            Path target = targetForTask(file, true);
            synchronized (topologyMonitor) {
                verifyBinding();
                prepareTarget(target);
                AtomicFiles.write(target, content);
                refreshPortableEntriesLocked();
            }
            return null;
        });
    }

    public CompletableFuture<byte[]> readBytes(Path file) {
        return enqueue(file, false, () -> {
            ensureTaskActive();
            Path target = targetForTask(file, false);
            synchronized (topologyMonitor) {
                verifyBinding();
                validatePortableTargetLocked(target, false);
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                    throw new IOException("Region clipboard file is not a regular non-symbolic-link file: " + target);
                }
                MigrationPaths.requireNoSymlinkTraversal(activeRoot, target);
                return Files.readAllBytes(target);
            }
        });
    }

    public CompletableFuture<Void> save(Path file, String content) {
        Objects.requireNonNull(content, "content");
        return write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    public CompletableFuture<String> load(Path file) {
        return readBytes(file).thenApply(bytes -> {
            String content = new String(bytes, StandardCharsets.UTF_8);
            RegionHandler.validateClipboardJson(content);
            return content;
        });
    }

    @Override
    public void flush() throws IOException {
        boolean wasOpen;
        synchronized (lifecycleMonitor) {
            ensureLifecycleOpenLocked();
            wasOpen = admissionOpen;
            admissionOpen = false;
        }
        IOException failure = null;
        boolean acknowledged = false;
        try {
            failure = append(failure, drainPendingTasks());
            try {
                verifyBinding();
                forceFiles();
                acknowledged = true;
            } catch (IOException exception) {
                failure = append(failure, exception);
            }
            if (acknowledged) {
                failure = append(failure, acknowledgeFailures());
            }
        } finally {
            synchronized (lifecycleMonitor) {
                if (!closed) {
                    admissionOpen = wasOpen;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void quiesce() throws IOException {
        synchronized (lifecycleMonitor) {
            ensureLifecycleOpenLocked();
            admissionOpen = false;
        }
        IOException failure = drainPendingTasks();
        try {
            verifyBinding();
        } catch (IOException exception) {
            failure = append(failure, exception);
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void resume() throws IOException {
        synchronized (lifecycleMonitor) {
            ensureLifecycleOpenLocked();
            if (detached) {
                throw new IOException("Flow region handler is detached");
            }
            if (admissionOpen) {
                return;
            }
        }
        if (!pendingTasks.isEmpty()) {
            throw new IOException("Flow region persistence still has pending tasks");
        }
        verifyBinding();
        IOException retained = retainedFailure();
        if (retained != null) {
            throw retained;
        }
        synchronized (lifecycleMonitor) {
            ensureLifecycleOpenLocked();
            admissionOpen = true;
        }
    }

    @Override
    public synchronized void rebind(Path activeRoot) throws IOException {
        synchronized (lifecycleMonitor) {
            ensureLifecycleOpenLocked();
            if (detached) {
                throw new IOException("Flow region handler is detached");
            }
            if (admissionOpen) {
                throw new IOException("Flow region persistence must be quiesced before rebind");
            }
        }
        IOException failure = drainPendingTasks();
        if (failure != null) {
            throw failure;
        }
        IOException retained = retainedFailure();
        if (retained != null) {
            throw retained;
        }
        verifyBinding();
        Path candidateScope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidateRoot = expectedRoot(candidateScope);
        Binding candidateScopeBinding = binding(candidateScope);
        Candidate candidate = validateCandidateRoot(candidateRoot);
        this.activeScopeRoot = candidateScope;
        this.activeCanonicalScopeRoot = candidateScopeBinding.canonicalRoot();
        this.activeScopeFileKey = candidateScopeBinding.fileKey();
        this.activeRoot = candidateRoot;
        this.activeCanonicalRoot = candidate.binding().canonicalRoot();
        this.activeRootFileKey = candidate.binding().fileKey();
        synchronized (topologyMonitor) {
            portableEntries.clear();
            portableEntries.putAll(candidate.entries());
            reservations.clear();
        }
    }

    public void validateCandidate(Path candidateScope) throws IOException {
        synchronized (lifecycleMonitor) {
            ensureLifecycleOpenLocked();
            if (detached) {
                throw new IOException("Flow region handler is detached");
            }
        }
        Path scope = MigrationPaths.requireDirectory(candidateScope, "candidateScope");
        validateCandidateRoot(expectedRoot(scope));
    }

    @Override
    public void healthCheck() throws IOException {
        boolean wasOpen;
        synchronized (lifecycleMonitor) {
            ensureLifecycleOpenLocked();
            wasOpen = admissionOpen;
            admissionOpen = false;
        }
        IOException failure = null;
        boolean acknowledged = false;
        try {
            failure = append(failure, drainPendingTasks());
            try {
                verifyBinding();
                validateTree(activeRoot);
                synchronized (topologyMonitor) {
                    refreshPortableEntriesLocked();
                }
                acknowledged = true;
            } catch (IOException exception) {
                failure = append(failure, exception);
            }
            if (acknowledged) {
                failure = append(failure, acknowledgeFailures());
            }
        } finally {
            synchronized (lifecycleMonitor) {
                if (!closed) {
                    admissionOpen = wasOpen;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public void detachHandler() {
        synchronized (lifecycleMonitor) {
            if (!closed) {
                detached = true;
                admissionOpen = false;
            }
        }
    }

    @Override
    public void close() throws IOException {
        List<Task<?>> tasks;
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            admissionOpen = false;
            closed = true;
            detached = true;
            tasks = new ArrayList<>(pendingTasks);
            for (Task<?> task : tasks) {
                if (!task.started) {
                    cancelBeforeStartLocked(task);
                } else if (task.runner != null) {
                    task.runner.interrupt();
                }
            }
        }
        IOException failure = drainTasks(tasks, true);
        synchronized (lifecycleMonitor) {
            for (Task<?> task : pendingTasks) {
                if (!task.started) {
                    cancelBeforeStartLocked(task);
                }
            }
        }
        fileTails.clear();
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
        failure = append(failure, retainedFailure());
        if (failure != null) {
            throw failure;
        }
    }

    private <T> CompletableFuture<T> enqueue(Path file, boolean write, IoOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        Path target = MigrationPaths.requirePath(file, "file");
        synchronized (lifecycleMonitor) {
            requireMutationAdmissionLocked();
            target = validateTarget(target, write);
            synchronized (topologyMonitor) {
                try {
                    validatePortableTargetLocked(target, write);
                    String key = portableKey(activeRoot, target);
                    Task<?> previous = fileTails.get(key);
                    Task<T> task = new Task<>(target, key, write, operation);
                    task.reservationKeys = write ? reserveTargetLocked(target, true) : List.of();
                    fileTails.put(key, task);
                    pendingTasks.add(task);
                    try {
                        executor.execute(() -> runTask(task, previous));
                    } catch (RejectedExecutionException exception) {
                        task.failure = exception;
                        task.result.completeExceptionally(exception);
                        releaseTaskLocked(task);
                        task.finished.complete(null);
                        recordFailure(task, exception);
                    }
                    return task.result;
                } catch (IOException exception) {
                    retainedFailures.put(target.toString(), exception);
                    CompletableFuture<T> failed = new CompletableFuture<>();
                    failed.completeExceptionally(exception);
                    return failed;
                }
            }
        }
    }

    private Path targetForTask(Path file, boolean write) throws IOException {
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new IOException("Flow region persistence is closed");
            }
            return validateTarget(file, write);
        }
    }

    private <T> void runTask(Task<T> task, Task<?> previous) {
        synchronized (lifecycleMonitor) {
            if (task.cancelledBeforeStart || task.result.isCancelled()) {
                releaseTaskLocked(task);
                task.finished.complete(null);
                return;
            }
            task.started = true;
            task.runner = Thread.currentThread();
            runningTasks.add(task);
        }
        try {
            if (previous != null) {
                previous.finished.join();
            }
            ensureTaskActive();
            task.result.complete(task.operation.run());
        } catch (Throwable failure) {
            task.failure = unwrap(failure);
            task.result.completeExceptionally(task.failure);
            recordFailure(task, task.failure);
        } finally {
            synchronized (lifecycleMonitor) {
                task.runner = null;
                runningTasks.remove(task);
                releaseTaskLocked(task);
            }
            task.finished.complete(null);
        }
    }

    private void cancelBeforeStartLocked(Task<?> task) {
        if (task.started || task.cancelledBeforeStart) {
            return;
        }
        task.cancelledBeforeStart = true;
        task.result.cancel(false);
        releaseTaskLocked(task);
        task.finished.complete(null);
    }

    private void releaseTaskLocked(Task<?> task) {
        pendingTasks.remove(task);
        fileTails.remove(task.key, task);
        synchronized (topologyMonitor) {
            for (String key : task.reservationKeys) {
                Reservation reservation = reservations.get(key);
                if (reservation == null) {
                    continue;
                }
                int count = reservation.count() - 1;
                if (count <= 0) {
                    reservations.remove(key);
                } else {
                    reservations.put(key, new Reservation(reservation.relative(), reservation.directory(), count));
                }
            }
        }
    }

    private Path validateTarget(Path file, boolean write) {
        Path target = MigrationPaths.requirePath(file, "file");
        Path root = activeRoot;
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Region clipboard file is outside the flow-regions directory");
        }
        if (write && target.getFileName() == null) {
            throw new IllegalArgumentException("Region clipboard file name is required");
        }
        return target;
    }

    private void prepareTarget(Path target) throws IOException {
        MigrationPaths.requireNoSymlinkTraversal(activeRoot, target);
        Path parent = target.getParent();
        if (parent == null || !parent.startsWith(activeRoot)) {
            throw new IOException("Region clipboard file parent is outside the flow-regions directory");
        }
        validatePortableTargetLocked(target, true);
        Files.createDirectories(parent);
        MigrationPaths.requireNoSymlinkTraversal(activeRoot, target);
        if (Files.isSymbolicLink(target)
            || (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Region clipboard file must be a regular non-symbolic-link file: " + target);
        }
    }

    private IOException drainPendingTasks() {
        return drainTasks(new ArrayList<>(pendingTasks), false);
    }

    private IOException drainTasks(List<Task<?>> tasks, boolean cancellationAllowed) {
        IOException failure = null;
        for (Task<?> task : tasks) {
            task.finished.join();
            if (task.failure != null) {
                failure = append(failure, asIOException(task.failure));
            } else if (task.result.isCancelled() && !cancellationAllowed) {
                failure = append(failure, new IOException("Flow region persistence task was cancelled"));
            }
        }
        return failure;
    }

    private void forceFiles() throws IOException {
        synchronized (topologyMonitor) {
            validateTree(activeRoot);
            try (Stream<Path> paths = Files.walk(activeRoot)) {
                for (Path path : paths.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    MigrationPaths.requireNoSymlinkTraversal(activeRoot, path);
                    AtomicFiles.force(path);
                }
            }
        }
    }

    private Candidate validateCandidateRoot(Path candidateRoot) throws IOException {
        ensureRoot(candidateRoot, false);
        validateTree(candidateRoot);
        return new Candidate(binding(candidateRoot), portableIndex(candidateRoot));
    }

    private void validateTree(Path root) throws IOException {
        MigrationPaths.requireNoSymlinkTree(root);
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate -> !candidate.equals(root)).toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                    throw new IOException("Flow region persistence contains a non-regular file: " + path);
                }
                try {
                    RegionHandler.validateClipboardJson(Files.readString(path, StandardCharsets.UTF_8));
                } catch (RuntimeException exception) {
                    throw new IOException("Flow region clipboard file is invalid: " + path, exception);
                }
            }
        }
        portableIndex(root);
    }

    private Map<String, PortableEntry> portableIndex(Path root) throws IOException {
        Map<String, PortableEntry> entries = new HashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate -> !candidate.equals(root)).toList()) {
                Path relativePath = root.relativize(path);
                String relative = relativePath.toString().replace(java.io.File.separatorChar, '/');
                String key = relative.toLowerCase(Locale.ROOT);
                PortableEntry previous = entries.putIfAbsent(key, new PortableEntry(relative,
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)));
                if (previous != null && !previous.relative().equals(relative)) {
                    throw new IOException("Flow region persistence contains portable case-colliding paths: " + previous.relative() + " and " + relative);
                }
            }
        }
        for (Map.Entry<String, PortableEntry> entry : entries.entrySet()) {
            String[] segments = entry.getValue().relative().split("/");
            StringBuilder actualPrefix = new StringBuilder();
            for (int index = 0; index < segments.length - 1; index++) {
                if (actualPrefix.length() > 0) {
                    actualPrefix.append('/');
                }
                actualPrefix.append(segments[index]);
                String prefix = actualPrefix.toString().toLowerCase(Locale.ROOT);
                PortableEntry ancestor = entries.get(prefix);
                if (ancestor != null && !ancestor.directory()) {
                    throw new IOException("Flow region persistence contains an ancestor file-directory conflict: " + ancestor.relative());
                }
                if (ancestor != null && !ancestor.relative().equals(actualPrefix.toString())) {
                    throw new IOException("Flow region persistence contains portable case-colliding paths: " + ancestor.relative() + " and " + actualPrefix);
                }
            }
        }
        return entries;
    }

    private void validatePortableTargetLocked(Path target, boolean write) throws IOException {
        String relative = relative(activeRoot, target);
        String key = relative.toLowerCase(Locale.ROOT);
        PortableEntry existing = portableEntries.get(key);
        if (existing != null && !existing.relative().equals(relative)) {
            throw new IOException("Flow region persistence path collides by case: " + existing.relative() + " and " + relative);
        }
        if (write && existing != null && existing.directory()) {
            throw new IOException("Flow region persistence target is an existing directory: " + target);
        }
        StringBuilder actualPrefix = new StringBuilder();
        String[] segments = relative.split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            if (actualPrefix.length() > 0) {
                actualPrefix.append('/');
            }
            actualPrefix.append(segments[index]);
            String prefix = actualPrefix.toString().toLowerCase(Locale.ROOT);
            PortableEntry ancestor = portableEntries.get(prefix);
            if (ancestor != null && !ancestor.directory()) {
                throw new IOException("Flow region persistence target is beneath a file: " + ancestor.relative());
            }
            if (ancestor != null && !ancestor.relative().equals(actualPrefix.toString())) {
                throw new IOException("Flow region persistence path collides by case: " + ancestor.relative() + " and " + actualPrefix);
            }
            Reservation reservation = reservations.get(prefix);
            if (reservation != null && !reservation.directory()) {
                throw new IOException("Flow region persistence target is beneath a pending file: " + reservation.relative());
            }
            if (reservation != null && !reservation.relative().equals(actualPrefix.toString())) {
                throw new IOException("Flow region persistence path collides by case: " + reservation.relative() + " and " + actualPrefix);
            }
        }
        Reservation reservation = reservations.get(key);
        if (reservation != null && !reservation.relative().equals(relative)) {
            throw new IOException("Flow region persistence path collides by case: " + reservation.relative() + " and " + relative);
        }
        if (write && reservation != null && reservation.directory()) {
            throw new IOException("Flow region persistence target conflicts with a pending directory: " + target);
        }
    }

    private List<String> reserveTargetLocked(Path target, boolean write) throws IOException {
        String relative = relative(activeRoot, target);
        String[] segments = relative.split("/");
        List<String> keys = new ArrayList<>(segments.length);
        StringBuilder current = new StringBuilder();
        try {
            for (int index = 0; index < segments.length; index++) {
                if (current.length() > 0) {
                    current.append('/');
                }
                current.append(segments[index]);
                String path = current.toString();
                String key = path.toLowerCase(Locale.ROOT);
                boolean directory = index < segments.length - 1 || !write;
                Reservation existing = reservations.get(key);
                if (existing != null && (!existing.relative().equals(path) || existing.directory() != directory)) {
                    throw new IOException("Flow region persistence path reservation collides: " + path);
                }
                Reservation value = reservations.get(key);
                reservations.put(key, value == null
                    ? new Reservation(path, directory, 1)
                    : new Reservation(value.relative(), value.directory(), value.count() + 1));
                keys.add(key);
            }
        } catch (IOException exception) {
            releaseReservationsLocked(keys);
            throw exception;
        }
        return keys;
    }

    private void releaseReservationsLocked(List<String> keys) {
        for (String key : keys) {
            Reservation reservation = reservations.get(key);
            if (reservation == null) {
                continue;
            }
            int count = reservation.count() - 1;
            if (count <= 0) {
                reservations.remove(key);
            } else {
                reservations.put(key, new Reservation(reservation.relative(), reservation.directory(), count));
            }
        }
    }

    private void refreshPortableEntriesLocked() throws IOException {
        Map<String, PortableEntry> entries = portableIndex(activeRoot);
        portableEntries.clear();
        portableEntries.putAll(entries);
    }

    private void verifyBinding() throws IOException {
        verifyDirectory(activeScopeRoot, activeCanonicalScopeRoot, activeScopeFileKey, "data root");
        Path root = activeRoot;
        verifyDirectory(root, activeCanonicalRoot, activeRootFileKey, "flow region persistence root");
        MigrationPaths.requireNoSymlinkTraversal(root, root);
    }

    private static void verifyDirectory(Path path, Path canonicalPath, Object fileKey, String name) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(name + " is unavailable: " + path);
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (fileKey != null && !Objects.equals(fileKey, attributes.fileKey())) {
            throw new IOException(name + " identity changed: " + path);
        }
        if (!path.toRealPath().equals(canonicalPath)) {
            throw new IOException(name + " path changed: " + path);
        }
    }

    private static Binding binding(Path root) throws IOException {
        Path canonical = root.toRealPath();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Flow region persistence root must be a regular non-symbolic-link directory: " + root);
        }
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new Binding(canonical, attributes.fileKey());
    }

    private static Path expectedRoot(Path scopeRoot) throws IOException {
        Path root = scopeRoot.resolve(DIRECTORY).toAbsolutePath().normalize();
        if (!root.startsWith(scopeRoot) || root.equals(scopeRoot)) {
            throw new MigrationException("Flow Region Persistence Root Escaped Data Root");
        }
        return root;
    }

    private static void ensureRoot(Path root, boolean create) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Flow region persistence root cannot be a symbolic link: " + root);
        }
        if (create && Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            MigrationPaths.requireWritableParent(root);
            Files.createDirectories(root);
        }
        MigrationPaths.requireDirectory(root, "flow-regions root");
        MigrationPaths.requireNoSymlinkTraversal(root, root);
    }

    private void requireMutationAdmissionLocked() {
        ensureLifecycleOpenLocked();
        if (detached) {
            throw new IllegalStateException("Flow region handler is detached");
        }
        if (!admissionOpen) {
            throw new IllegalStateException("Flow region persistence is quiesced");
        }
    }

    private void ensureTaskActive() throws IOException {
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new IOException("Flow region persistence is closed");
            }
        }
    }

    private void ensureLifecycleOpenLocked() {
        if (closed) {
            throw new IllegalStateException("Flow region persistence is closed");
        }
    }

    private void recordFailure(Task<?> task, Throwable failure) {
        synchronized (lifecycleMonitor) {
            if (!closed && !task.cancelledBeforeStart) {
                retainedFailures.put(task.target.toString(), failure);
            }
        }
    }

    private IOException acknowledgeFailures() {
        synchronized (lifecycleMonitor) {
            IOException failure = null;
            for (Throwable value : retainedFailures.values()) {
                failure = append(failure, asIOException(value));
            }
            retainedFailures.clear();
            return failure;
        }
    }

    private IOException retainedFailure() {
        synchronized (lifecycleMonitor) {
            IOException failure = null;
            for (Throwable value : retainedFailures.values()) {
                failure = append(failure, asIOException(value));
            }
            return failure;
        }
    }

    private static String relative(Path root, Path target) {
        return root.relativize(target).toString().replace(java.io.File.separatorChar, '/');
    }

    private static String portableKey(Path root, Path target) {
        return relative(root, target).toLowerCase(Locale.ROOT);
    }

    private static IOException append(IOException primary, IOException next) {
        if (next == null) {
            return primary;
        }
        if (primary == null) {
            return next;
        }
        primary.addSuppressed(next);
        return primary;
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException) {
            return failure.getCause() == null ? failure : failure.getCause();
        }
        return failure;
    }

    private static IOException asIOException(Throwable failure) {
        if (failure instanceof IOException io) {
            return io;
        }
        return new IOException("Flow region persistence task failed", failure);
    }

    private static ExecutorService newExecutor() {
        AtomicLong sequence = new AtomicLong();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "resync-flow-regions-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    private record Binding(Path canonicalRoot, Object fileKey) {
    }

    private record Candidate(Binding binding, Map<String, PortableEntry> entries) {
    }

    private record PortableEntry(String relative, boolean directory) {
    }

    private record Reservation(String relative, boolean directory, int count) {
    }

    private final class Task<T> {
        private final Path target;
        private final String key;
        private final boolean write;
        private final IoOperation<T> operation;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private volatile Thread runner;
        private volatile boolean started;
        private volatile boolean cancelledBeforeStart;
        private volatile Throwable failure;
        private List<String> reservationKeys = List.of();

        private Task(Path target, String key, boolean write, IoOperation<T> operation) {
            this.target = target;
            this.key = key;
            this.write = write;
            this.operation = operation;
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
