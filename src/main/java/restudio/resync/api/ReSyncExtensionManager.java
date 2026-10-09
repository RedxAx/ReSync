package restudio.resync.api;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.Log;
import restudio.resync.customcontent.CustomContentProvider;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowValueCodec;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.modules.FlowModule;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.handler.property.PropertyHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;
import restudio.resync.flow.validation.FlowGraphValidationRegistry;
import restudio.resync.flow.validation.FlowGraphValidationRule;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.filesystem.windows.WindowsFileMutation;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleContext;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.world.WorldMapExtension;
import restudio.resync.world.WorldMapService;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ReSyncExtensionManager implements ExtensionPersistenceParticipant.Controller {
    private static final long SCAN_INTERVAL_MS = 5000L;
    private static final String LIFECYCLE_RECOVERY_MARKER = ".resync-lifecycle-recovery";
    private static final String LIFECYCLE_RECOVERY_MARKER_VERSION = "1";
    private static final String LIFECYCLE_RECOVERY_LOCK = ".resync-lifecycle-recovery.lock";
    private static final String LIFECYCLE_RECOVERY_TEMP_PREFIX = LIFECYCLE_RECOVERY_MARKER + ".";
    private static final String LIFECYCLE_RECOVERY_TEMP_SUFFIX = ".tmp";
    private static final String LIFECYCLE_RECOVERY_PROBE_PREFIX = ".resync-lifecycle-capability-";
    private static final String LIFECYCLE_RECOVERY_PROBE_SOURCE_SUFFIX = ".source";
    private static final String LIFECYCLE_RECOVERY_PROBE_TEMP_SUFFIX = ".tmp";
    private static final int LIFECYCLE_RECOVERY_TEMP_RESERVATION_ATTEMPTS = 128;
    private static final int MAXIMUM_LIFECYCLE_RECOVERY_PROBE_ARTIFACTS = 16;
    private static final int MAXIMUM_LIFECYCLE_RECOVERY_PROBE_BYTES = 1024;
    private static final int MAXIMUM_LIFECYCLE_RECOVERY_MARKER_BYTES = 65536;

    enum LifecyclePhase {
        EXTERNAL_REMOVE,
        EXTERNAL_ADD,
        MAP_SWAP,
        OLD_STOP,
        NEW_START,
        CORE_COMMIT,
        ROLLBACK_REFRESH,
        COMPENSATION
    }

    @FunctionalInterface
    interface LifecycleFailureInjector {
        void after(LifecyclePhase phase);

        static LifecycleFailureInjector none() {
            return phase -> {
            };
        }
    }

    enum RecoveryPoint {
        BEFORE_MARKER_RENAME,
        AFTER_MARKER_PUBLISH,
        BEFORE_MARKER_CLEAR,
        AFTER_MARKER_CLEAR
    }

    @FunctionalInterface
    interface LifecycleRecoveryDurability {
        void at(RecoveryPoint point, Path marker);

        static LifecycleRecoveryDurability none() {
            return (point, marker) -> {
            };
        }
    }

    @FunctionalInterface
    interface LifecycleRecoveryBackend {
        void forceDirectory(Path directory) throws IOException;

        static LifecycleRecoveryBackend platform() {
            return new SystemLifecycleRecoveryBackend();
        }
    }

    static final class SystemLifecycleRecoveryBackend implements LifecycleRecoveryBackend {
        private final WindowsFileMutation windowsFileMutation;

        SystemLifecycleRecoveryBackend() {
            this(WindowsFileMutation.system());
        }

        SystemLifecycleRecoveryBackend(WindowsFileMutation windowsFileMutation) {
            this.windowsFileMutation = Objects.requireNonNull(windowsFileMutation, "Windows file mutation is required");
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            Path normalized = directory.toAbsolutePath().normalize();
            if (isWindows()) {
                if (!windowsFileMutation.available(normalized)) {
                    String reason = windowsFileMutation.unavailableReason(normalized);
                    throw new IOException(reason == null || reason.isBlank()
                        ? "Windows File Mutation Is Unavailable" : reason);
                }
                if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Extension directory is unavailable: " + normalized);
                }
                windowsFileMutation.flushParent(normalized.resolve(".resync-lifecycle-directory-entry"));
                return;
            }
            if (Files.isSymbolicLink(normalized)) {
                throw new IOException("Cannot Force A Symbolic Link: " + normalized);
            }
            try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }

    private final ModuleContext moduleContext;
    private volatile List<Path> extensionDirectories;
    private final Map<Path, JarState> jarStates = new ConcurrentHashMap<>();
    private final Set<String> deferredReloads = ConcurrentHashMap.newKeySet();
    private final Set<String> deferredUnloads = ConcurrentHashMap.newKeySet();
    private final Set<String> blockedReloads = ConcurrentHashMap.newKeySet();
    private final Set<String> blockedUnloads = ConcurrentHashMap.newKeySet();
    private final Set<FlowExecutor.AdmissionFence> retainedAdmissionFences =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final ReentrantReadWriteLock persistenceLock = new ReentrantReadWriteLock(true);
    private final LifecycleFailureInjector lifecycleFailureInjector;
    private final LifecycleRecoveryDurability recoveryDurability;
    private final LifecycleRecoveryBackend recoveryBackend;
    private final Set<Path> lifecycleMarkerPublications = ConcurrentHashMap.newKeySet();
    private final Map<Path, LifecycleRecoveryMarkerSnapshot> lifecycleMarkerPublicationSnapshots = new ConcurrentHashMap<>();
    private final ThreadLocal<LifecycleRecoveryLockSet> lifecycleRecoveryLocks = new ThreadLocal<>();
    private volatile ExtensionRegistryActivation registryActivation;
    private volatile FlowEventRegistry boundFlowEventRegistry;
    private volatile PendingLifecycleCompensation pendingLifecycleCompensation;
    private LifecycleTransaction pendingModuleTransaction;
    private final AtomicReference<LifecycleRetry> lifecycleRetry = new AtomicReference<>();
    private final Set<LifecycleTransaction> retainedTransactions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<URLClassLoader> retainedLoaders = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile PersistenceState persistenceState = PersistenceState.OPEN;
    private final List<ExtensionState> quiescedPersistenceExtensions = new ArrayList<>();
    private IOException persistenceFailure;
    private volatile boolean shutdownRequested;
    private volatile boolean lifecycleDegraded;
    private volatile boolean startupRecoveryRequired;
    private volatile boolean recoveryDurabilityAvailable;
    private volatile String recoveryDurabilityFailure = "";
    private volatile boolean lifecycleRecoveryLockReleaseFailed;
    private long lastScan;

    public ReSyncExtensionManager(ModuleContext moduleContext, Path extensionDirectory) {
        this(moduleContext, List.of(extensionDirectory), LifecycleFailureInjector.none(),
            LifecycleRecoveryDurability.none(), LifecycleRecoveryBackend.platform());
    }

    public ReSyncExtensionManager(ModuleContext moduleContext, List<Path> extensionDirectories) {
        this(moduleContext, extensionDirectories, LifecycleFailureInjector.none(),
            LifecycleRecoveryDurability.none(), LifecycleRecoveryBackend.platform());
    }

    ReSyncExtensionManager(ModuleContext moduleContext, Path extensionDirectory,
                            LifecycleRecoveryBackend recoveryBackend) {
        this(moduleContext, List.of(extensionDirectory), LifecycleFailureInjector.none(),
            LifecycleRecoveryDurability.none(), recoveryBackend);
    }

    ReSyncExtensionManager(ModuleContext moduleContext, List<Path> extensionDirectories,
                           LifecycleRecoveryBackend recoveryBackend) {
        this(moduleContext, extensionDirectories, LifecycleFailureInjector.none(),
            LifecycleRecoveryDurability.none(), recoveryBackend);
    }

    ReSyncExtensionManager(ModuleContext moduleContext, List<Path> extensionDirectories,
                           LifecycleFailureInjector lifecycleFailureInjector) {
        this(moduleContext, extensionDirectories, lifecycleFailureInjector,
            LifecycleRecoveryDurability.none(), LifecycleRecoveryBackend.platform());
    }

    ReSyncExtensionManager(ModuleContext moduleContext, List<Path> extensionDirectories,
                           LifecycleFailureInjector lifecycleFailureInjector,
                           LifecycleRecoveryBackend recoveryBackend) {
        this(moduleContext, extensionDirectories, lifecycleFailureInjector,
            LifecycleRecoveryDurability.none(), recoveryBackend);
    }

    ReSyncExtensionManager(ModuleContext moduleContext, List<Path> extensionDirectories,
                           LifecycleFailureInjector lifecycleFailureInjector,
                           LifecycleRecoveryDurability recoveryDurability) {
        this(moduleContext, extensionDirectories, lifecycleFailureInjector, recoveryDurability,
            LifecycleRecoveryBackend.platform());
    }

    ReSyncExtensionManager(ModuleContext moduleContext, List<Path> extensionDirectories,
                           LifecycleFailureInjector lifecycleFailureInjector,
                           LifecycleRecoveryDurability recoveryDurability,
                           LifecycleRecoveryBackend recoveryBackend) {
        this.moduleContext = moduleContext;
        this.extensionDirectories = normalizeDirectories(extensionDirectories);
        this.lifecycleFailureInjector = Objects.requireNonNull(lifecycleFailureInjector, "Lifecycle failure injector is required");
        this.recoveryDurability = Objects.requireNonNull(recoveryDurability, "Lifecycle recovery durability is required");
        this.recoveryBackend = Objects.requireNonNull(recoveryBackend, "Lifecycle recovery backend is required");
        ensureDirectory();
        lifecycleRecoveryLockReleaseFailed = false;
        recoveryDurabilityAvailable = probeRecoveryDurability() && !lifecycleRecoveryLockReleaseFailed;
        startupRecoveryRequired = hasLifecycleRecoveryMarker();
        if (!recoveryDurabilityAvailable) {
            Log.warn("ReSync extension lifecycle remains fenced: " + recoveryDurabilityFailure);
        }
    }

    public void loadInitialExtensions() {
        persistenceLock.writeLock().lock();
        try {
            requireRecoveryDurability();
            if (!reconcileStartupRecovery()) {
                throw new IllegalStateException("ReSync extension lifecycle recovery is pending");
            }
            requireRecoveryDurability();
            requirePersistenceOpen();
            scanDirectory();
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    public void tick() {
        persistenceLock.writeLock().lock();
        try {
            if (persistenceState != PersistenceState.OPEN) {
                return;
            }
            if (!recoveryDurabilityAvailable) {
                return;
            }
            if (startupRecoveryRequired && !reconcileStartupRecovery()) {
                return;
            }
            if (!recoveryDurabilityAvailable || startupRecoveryRequired) {
                return;
            }
            if (deferLifecycleRetry()) {
                return;
            }
            retryModuleLifecycle();
            retryPendingLifecycleCompensation();
            releaseRetainedLifecycle();
            if (!recoveryDurabilityAvailable || startupRecoveryRequired || pendingLifecycleCompensation != null
                || pendingModuleTransaction != null || moduleContext.getModuleRegistry().isRuntimeShutdownPending()) {
                return;
            }
            retryBlockedLifecycle();
            if (!recoveryDurabilityAvailable || startupRecoveryRequired) {
                return;
            }
            if (shutdownRequested) {
                finishShutdownIfIdle();
                return;
            }
            unloadDisabledOwners();
            if (!recoveryDurabilityAvailable || startupRecoveryRequired) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastScan < SCAN_INTERVAL_MS) {
                return;
            }
            lastScan = now;
            scanDirectory();
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    public ExtensionRegistration registerBukkitExtension(JavaPlugin owner, ReSyncExtension extension) {
        persistenceLock.writeLock().lock();
        try {
            requirePersistenceOpen();
            FlowExecutor.AdmissionFence admissionFence = fenceLegacyExecutions();
            if (admissionFence != null && isPrimaryThread() && !admissionFence.isDrained()) {
                closeAdmissionFence(admissionFence);
                throw new IllegalStateException("ReSync extension registration is deferred while Flow execution is active");
            }
            try {
                awaitLegacyExecutions(admissionFence);
                return registerExtension(owner, extension, null, null, admissionFence);
            } finally {
                closeAdmissionFence(admissionFence);
            }
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    @Override
    public Path persistenceRoot() {
        List<Path> directories = extensionDirectories;
        if (directories.size() != 1) {
            throw new IllegalStateException("Extension persistence requires exactly one extension root");
        }
        return directories.getFirst();
    }

    @Override
    public void flushPersistence() throws IOException {
        persistenceLock.writeLock().lock();
        try {
            if (persistenceState != PersistenceState.OPEN) {
                throw new IOException("Extension persistence is quiesced");
            }
            flushPersistenceInternal();
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    @Override
    public void quiescePersistence() throws IOException {
        persistenceLock.writeLock().lock();
        try {
            if (persistenceState == PersistenceState.QUIESCED) {
                return;
            }
            if (persistenceState == PersistenceState.FAILED) {
                recoverFailedPersistence();
            }
            if (persistenceState != PersistenceState.OPEN) {
                throw persistenceFailure != null ? persistenceFailure
                    : new IOException("Extension persistence quiesce is still in progress");
            }
            persistenceState = PersistenceState.QUIESCING;
            List<ExtensionState> quiesced = new ArrayList<>();
            try {
                flushPersistenceInternal();
                for (ExtensionState state : orderedExtensions()) {
                    state.extension.quiescePersistence();
                    quiesced.add(state);
                }
                quiescedPersistenceExtensions.clear();
                quiescedPersistenceExtensions.addAll(quiesced);
                persistenceFailure = null;
                persistenceState = PersistenceState.QUIESCED;
            } catch (IOException | RuntimeException exception) {
                IOException compensation = resumeQuiescedPersistence(quiesced);
                if (compensation == null) {
                    quiescedPersistenceExtensions.clear();
                    persistenceFailure = null;
                    persistenceState = PersistenceState.OPEN;
                } else {
                    exception.addSuppressed(compensation);
                    persistenceFailure = asPersistenceIOException("Extension persistence quiesce failed", exception);
                    persistenceState = PersistenceState.FAILED;
                }
                throw exception;
            }
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    private void recoverFailedPersistence() throws IOException {
        if (persistenceState != PersistenceState.FAILED) {
            return;
        }
        IOException failure = resumeQuiescedPersistence(quiescedPersistenceExtensions);
        if (failure != null) {
            persistenceFailure = failure;
            throw failure;
        }
        quiescedPersistenceExtensions.clear();
        persistenceFailure = null;
        persistenceState = PersistenceState.OPEN;
    }

    private IOException resumeQuiescedPersistence(List<ExtensionState> extensions) {
        IOException failure = null;
        List<ExtensionState> remaining = new ArrayList<>(extensions);
        for (int index = extensions.size() - 1; index >= 0; index--) {
            ExtensionState state = extensions.get(index);
            try {
                state.extension.resumePersistence();
                remaining.remove(state);
            } catch (IOException | RuntimeException exception) {
                failure = appendPersistenceFailure(failure,
                    "Extension persistence compensation failed for " + state.pluginId, exception);
            }
        }
        quiescedPersistenceExtensions.clear();
        quiescedPersistenceExtensions.addAll(remaining);
        return failure;
    }

    private IOException quiesceResumedPersistence(List<ExtensionState> extensions,
                                                   List<ExtensionState> restored) {
        IOException failure = null;
        for (int index = extensions.size() - 1; index >= 0; index--) {
            ExtensionState state = extensions.get(index);
            try {
                state.extension.quiescePersistence();
                restored.add(state);
            } catch (IOException | RuntimeException exception) {
                failure = appendPersistenceFailure(failure,
                    "Extension persistence resume compensation failed for " + state.pluginId, exception);
            }
        }
        return failure;
    }

    private IOException asPersistenceIOException(String message, Throwable failure) {
        return failure instanceof IOException ioException ? ioException : new IOException(message, failure);
    }

    private IOException appendPersistenceFailure(IOException current, String message, Throwable failure) {
        if (current == null) {
            return new IOException(message, failure);
        }
        current.addSuppressed(failure);
        return current;
    }

    @Override
    public void resumePersistence() throws IOException {
        persistenceLock.writeLock().lock();
        try {
            if (persistenceState == PersistenceState.OPEN) {
                return;
            }
            if (persistenceState == PersistenceState.FAILED) {
                recoverFailedPersistence();
                return;
            }
            if (persistenceState != PersistenceState.QUIESCED) {
                throw new IOException("Extension persistence is not quiesced");
            }
            healthCheckPersistenceInternal();
            List<ExtensionState> targets = List.copyOf(quiescedPersistenceExtensions);
            List<ExtensionState> resumed = new ArrayList<>();
            List<ExtensionState> remaining = new ArrayList<>(targets);
            IOException failure = null;
            for (int index = targets.size() - 1; index >= 0; index--) {
                ExtensionState state = targets.get(index);
                try {
                    state.extension.resumePersistence();
                    resumed.add(state);
                    remaining.remove(state);
                } catch (IOException | RuntimeException exception) {
                    failure = appendPersistenceFailure(failure,
                        "Extension persistence resume failed for " + state.pluginId, exception);
                }
            }
            if (failure != null) {
                List<ExtensionState> restored = new ArrayList<>();
                IOException compensation = quiesceResumedPersistence(resumed, restored);
                List<ExtensionState> retained = new ArrayList<>();
                for (ExtensionState state : targets) {
                    if (remaining.contains(state) || restored.contains(state)) {
                        retained.add(state);
                    }
                }
                quiescedPersistenceExtensions.clear();
                quiescedPersistenceExtensions.addAll(retained);
                if (compensation == null) {
                    persistenceState = PersistenceState.QUIESCED;
                    persistenceFailure = null;
                } else {
                    failure.addSuppressed(compensation);
                    persistenceFailure = failure;
                    persistenceState = PersistenceState.FAILED;
                }
                throw failure;
            }
            quiescedPersistenceExtensions.clear();
            persistenceFailure = null;
            persistenceState = PersistenceState.OPEN;
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    @Override
    public void rebindPersistence(Path activeRoot) throws IOException {
        persistenceLock.writeLock().lock();
        try {
            if (persistenceState != PersistenceState.QUIESCED) {
                throw new IOException("Extension persistence must be quiesced before rebind");
            }
            if (!activeExtensions().isEmpty() || !jarStates.isEmpty()) {
                throw new IOException("Extension persistence cannot rebind while an extension artifact is active");
            }
            Path candidate = requireExtensionRoot(activeRoot, "activeRoot");
            List<Path> previous = extensionDirectories;
            boolean previousDurability = recoveryDurabilityAvailable;
            String previousDurabilityFailure = recoveryDurabilityFailure;
            boolean previousLockReleaseFailure = lifecycleRecoveryLockReleaseFailed;
            boolean previousRecovery = startupRecoveryRequired;
            this.extensionDirectories = List.of(candidate);
            try {
                healthCheckPersistenceInternal();
                lifecycleRecoveryLockReleaseFailed = false;
                recoveryDurabilityAvailable = probeRecoveryDurability() && !lifecycleRecoveryLockReleaseFailed;
                if (!recoveryDurabilityAvailable) {
                    throw new IOException("Extension lifecycle durability is unavailable: " + recoveryDurabilityFailure);
                }
                startupRecoveryRequired = hasLifecycleRecoveryMarker();
            } catch (IOException | RuntimeException exception) {
                this.extensionDirectories = previous;
                recoveryDurabilityAvailable = previousDurability;
                recoveryDurabilityFailure = previousDurabilityFailure;
                lifecycleRecoveryLockReleaseFailed = previousLockReleaseFailure;
                startupRecoveryRequired = previousRecovery;
                throw exception;
            }
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    @Override
    public void healthCheckPersistence() throws IOException {
        persistenceLock.readLock().lock();
        try {
            healthCheckPersistenceInternal();
        } finally {
            persistenceLock.readLock().unlock();
        }
    }

    public void shutdown() {
        persistenceLock.writeLock().lock();
        try {
            shutdownRequested = true;
            if (startupRecoveryRequired && !reconcileStartupRecovery()) {
                return;
            }
            if (deferLifecycleRetry()) {
                return;
            }
            retryModuleLifecycle();
            retryPendingLifecycleCompensation();
            releaseRetainedLifecycle();
            if (pendingModuleTransaction != null || pendingLifecycleCompensation != null) {
                return;
            }
            for (String pluginId : new ArrayList<>(activeExtensions().keySet())) {
                unregister(pluginId);
            }
            retryBlockedLifecycle();
            finishShutdownIfIdle();
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    public boolean isShutdownPending() {
        persistenceLock.readLock().lock();
        try {
            if (!recoveryDurabilityAvailable) {
                return true;
            }
            if (pendingModuleTransaction != null || !retainedTransactions.isEmpty() || !retainedLoaders.isEmpty()
                || moduleContext.getModuleRegistry().isRuntimeShutdownPending()) {
                return true;
            }
            boolean retainedFence;
            synchronized (retainedAdmissionFences) {
                retainedFence = !retainedAdmissionFences.isEmpty();
            }
            return (shutdownRequested || lifecycleDegraded || startupRecoveryRequired)
                && (!activeExtensions().isEmpty() || !jarStates.isEmpty()
                || !deferredReloads.isEmpty() || !deferredUnloads.isEmpty()
                || !blockedReloads.isEmpty() || !blockedUnloads.isEmpty()
                || retainedFence || pendingLifecycleCompensation != null || startupRecoveryRequired || lifecycleDegraded);
        } finally {
            persistenceLock.readLock().unlock();
        }
    }

    private boolean deferLifecycleRetry() {
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            LifecycleRetry queued = lifecycleRetry.getAndSet(null);
            if (queued != null && queued.task != null) {
                queued.task.cancel();
            }
            return false;
        }
        if (pendingModuleTransaction == null && pendingLifecycleCompensation == null
            && !moduleContext.getModuleRegistry().isRuntimeShutdownPending()) {
            return false;
        }
        LifecycleRetry queued = lifecycleRetry.get();
        if (queued != null && queued.task != null && queued.task.isCancelled()) {
            lifecycleRetry.compareAndSet(queued, null);
        }
        if (moduleContext.getPlugin() == null || !moduleContext.getPlugin().isEnabled()) {
            return true;
        }
        LifecycleRetry retry = new LifecycleRetry();
        if (!lifecycleRetry.compareAndSet(null, retry)) {
            return true;
        }
        try {
            retry.task = Bukkit.getScheduler().runTask(moduleContext.getPlugin(), () -> runLifecycleRetry(retry));
        } catch (RuntimeException | Error failure) {
            lifecycleRetry.compareAndSet(retry, null);
            Log.warn("ReSync extension primary lifecycle retry could not be scheduled: " + failure.getMessage());
        }
        return true;
    }

    private void runLifecycleRetry(LifecycleRetry retry) {
        if (!persistenceLock.writeLock().tryLock()) {
            lifecycleRetry.compareAndSet(retry, null);
            return;
        }
        try {
            if (!lifecycleRetry.compareAndSet(retry, null)) {
                return;
            }
            if ((!shutdownRequested && persistenceState != PersistenceState.OPEN)
                || !recoveryDurabilityAvailable || startupRecoveryRequired) {
                return;
            }
            retryModuleLifecycle();
            retryPendingLifecycleCompensation();
            releaseRetainedLifecycle();
            if (shutdownRequested) {
                finishShutdownIfIdle();
            }
        } catch (RuntimeException | Error failure) {
            Log.warn("ReSync extension primary lifecycle retry failed: " + failure.getMessage());
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    private static final class LifecycleRetry {
        private volatile BukkitTask task;
    }

    private void retryModuleLifecycle() {
        if (deferLifecycleRetry()) {
            return;
        }
        try {
            moduleContext.getModuleRegistry().retryRuntimeModuleStops();
        } catch (RuntimeException | Error failure) {
            Log.warn("Runtime module retirement retry failed: " + failure.getMessage());
        }
        LifecycleTransaction transaction = pendingModuleTransaction;
        if (transaction != null) {
            transaction.commit(transaction.pendingPublishCatalog, transaction.pendingAdmissionFence);
            if (pendingModuleTransaction != transaction) {
                transaction.close();
                for (ExtensionState state : transaction.removedStates) {
                    tryCloseJar(state.jarPath);
                }
            }
        }
    }

    private void releaseRetainedLifecycle() {
        if (pendingModuleTransaction != null || pendingLifecycleCompensation != null
            || moduleContext.getModuleRegistry().isRuntimeShutdownPending()) {
            return;
        }
        for (LifecycleTransaction transaction : List.copyOf(retainedTransactions)) {
            retainedTransactions.remove(transaction);
            transaction.close();
        }
        for (URLClassLoader loader : List.copyOf(retainedLoaders)) {
            retainedLoaders.remove(loader);
            close(loader);
        }
    }

    boolean startupRecoveryRequired() {
        return startupRecoveryRequired;
    }

    boolean recoveryDurabilityAvailable() {
        return recoveryDurabilityAvailable;
    }

    private void finishShutdownIfIdle() {
        if (pendingModuleTransaction != null || pendingLifecycleCompensation != null
            || moduleContext.getModuleRegistry().isRuntimeShutdownPending()) {
            return;
        }
        if (!activeExtensions().isEmpty() || !deferredReloads.isEmpty() || !deferredUnloads.isEmpty()
            || !blockedReloads.isEmpty() || !blockedUnloads.isEmpty()) {
            return;
        }
        for (Map.Entry<Path, JarState> entry : new ArrayList<>(jarStates.entrySet())) {
            JarState jarState = entry.getValue();
            if (jarStates.remove(entry.getKey(), jarState)) {
                close(jarState.classLoader);
            }
        }
    }

    public Set<String> getPluginIds() {
        return activeRegistryState().extensionIds();
    }

    public ExtensionRegistryActivation.State activeRegistryState() {
        return registryActivation().snapshot();
    }

    private Map<String, ExtensionState> activeExtensions() {
        return extensionStates(activeRegistryState());
    }

    private ExtensionState activeExtension(String pluginId) {
        return activeExtensions().get(pluginId);
    }

    private Map<String, ExtensionState> extensionStates(ExtensionRegistryActivation.State state) {
        Map<String, ExtensionState> result = new LinkedHashMap<>();
        state.extensionLifecycles().forEach((pluginId, lifecycle) -> {
            if (!(lifecycle instanceof ExtensionState extension)) {
                throw new IllegalStateException("Extension registry lifecycle state is invalid: " + pluginId);
            }
            result.put(pluginId, extension);
        });
        return Map.copyOf(result);
    }

    public List<Map<String, Object>> contributionInventory() {
        return activeExtensions().values().stream()
            .sorted((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.pluginId, second.pluginId))
            .map(state -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("pluginId", state.pluginId);
                item.put("version", nullToEmpty(state.extension.getVersion()));
                item.put("description", nullToEmpty(state.extension.getDescription()));
                item.put("owner", state.owner != null ? state.owner.getName() : moduleContext.getPlugin().getName());
                item.put("source", state.jarPath != null ? state.jarPath.toString() : "bukkit");
                item.put("nodes", sorted(state.nodeIds));
                item.put("nodeWireIds", Map.copyOf(state.nodeWireIds));
                item.put("handlers", sorted(state.handlerIds));
                item.put("modules", sorted(state.moduleIds));
                item.put("properties", state.propertyIds.stream().map(value -> value.family() + "." + value.property()).sorted(String.CASE_INSENSITIVE_ORDER).toList());
                item.put("catalogs", sorted(state.optionCatalogIds));
                item.put("runtimeDataAdapters", sorted(state.runtimeDataAdapterIds));
                item.put("types", sorted(state.typeIds));
                item.put("conversions", state.conversions.stream().map(value -> value.source().getName() + " -> " + value.target().getName()).sorted(String.CASE_INSENSITIVE_ORDER).toList());
                item.put("resources", sorted(state.resourceTypeIds));
                item.put("validators", sorted(state.validatorIds));
                item.put("customContentProviders", sorted(state.customContentProviderIds));
                item.put("worldMapExtensions", sorted(state.worldMapExtensionIds));
                item.put("listeners", state.listeners.size());
                item.put("disposition", "supported");
                item.put("requirements", List.of("EXT-001", "EXT-002", "EXT-006", "EXT-008"));
                return Map.copyOf(item);
            })
            .toList();
    }

    private List<String> sorted(Set<String> values) {
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public boolean reloadExtensions() {
        persistenceLock.writeLock().lock();
        try {
            requirePersistenceOpen();
            FlowExecutor.AdmissionFence admissionFence = fenceLegacyExecutions();
            if (deferUntilLegacyExecutionsDrain(admissionFence, "reload:all",
                () -> reloadExtensionsAfterDrain(admissionFence))) {
                return false;
            }
            try {
                awaitLegacyExecutions(admissionFence);
                return reloadExtensionsAfterDrain(admissionFence);
            } finally {
                closeAdmissionFence(admissionFence);
            }
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    private boolean reloadExtensionsAfterDrain(FlowExecutor.AdmissionFence admissionFence) {
        LifecycleTransaction transaction = new LifecycleTransaction(admissionFence);
        try {
            for (ExtensionState state : new ArrayList<>(activeExtensions().values())) {
                if (!transaction.stageRuntimeRetirement(state)) {
                    blockedReloads.add(state.pluginId);
                    transaction.close();
                    return false;
                }
                transaction.stageReload(state);
            }
            return transaction.commit(true, admissionFence);
        } catch (RuntimeException exception) {
            Log.warn("ReSync extension reload batch was rejected: " + exception.getMessage());
            return false;
        } finally {
            transaction.close();
            closeAdmissionFence(admissionFence);
        }
    }

    private ExtensionRegistration registerExtension(JavaPlugin owner, ReSyncExtension extension, URLClassLoader classLoader,
                                                     Path jarPath, FlowExecutor.AdmissionFence admissionFence) {
        requirePersistenceOpen();
        LifecycleTransaction transaction = new LifecycleTransaction(admissionFence);
        try {
            ExtensionState state = transaction.stageAdd(owner, extension, classLoader, jarPath);
            if (!transaction.commit(true, admissionFence)) {
                throw new IllegalStateException("Flow catalog rejected extension " + state.pluginId);
            }
            Log.info("[ReSync] Registered extension " + state.pluginId + " " + nullToEmpty(extension.getVersion()));
            return new ExtensionHandle(state.pluginId);
        } finally {
            transaction.close();
        }
    }

    private void initializeExtension(ExtensionState state, ExtensionContext context) {
        initializeExtension(state, context, true);
    }

    private void initializeExtension(ExtensionState state, ExtensionContext context, boolean start) {
        Set<Listener> listenersBefore = registeredListeners(context.owner());
        try {
            state.extension.initialize(context);
            if (start) {
                state.extension.start();
            }
        } finally {
            if (context.transaction == null) {
                for (Listener listener : registeredListeners(context.owner())) {
                    if (!listenersBefore.contains(listener)) {
                        state.listeners.add(listener);
                    }
                }
            }
        }
    }

    private Set<Listener> registeredListeners(JavaPlugin owner) {
        Set<Listener> listeners = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RegisteredListener registeredListener : HandlerList.getRegisteredListeners(owner)) {
            listeners.add(registeredListener.getListener());
        }
        return listeners;
    }

    private boolean unregister(String pluginId) {
        persistenceLock.writeLock().lock();
        try {
            if (!persistenceOpenForLifecycle()) {
                return false;
            }
            ExtensionState state = activeExtension(pluginId);
            if (state == null) {
                return true;
            }
            FlowExecutor.AdmissionFence admissionFence = fenceLegacyExecutions();
            if (deferUntilLegacyExecutionsDrain(admissionFence, "unload:" + pluginId,
                () -> {
                    try {
                        awaitLegacyExecutions(admissionFence);
                        if (unregister(state, admissionFence)) {
                            tryCloseJar(state.jarPath);
                        }
                    } finally {
                        closeAdmissionFence(admissionFence);
                    }
                })) {
                return false;
            }
            try {
                awaitLegacyExecutions(admissionFence);
                boolean removed = unregister(state, admissionFence);
                if (removed) {
                    tryCloseJar(state.jarPath);
                }
                return removed;
            } finally {
                closeAdmissionFence(admissionFence);
            }
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    private boolean reloadExtension(ExtensionState state, FlowExecutor.AdmissionFence admissionFence) {
        if (shutdownRequested) {
            blockedReloads.remove(state.pluginId);
            return unregister(state, admissionFence);
        }
        blockedReloads.remove(state.pluginId);
        LifecycleTransaction transaction = new LifecycleTransaction(admissionFence);
        try {
            if (!transaction.stageRuntimeRetirement(state)) {
                blockedReloads.add(state.pluginId);
                Log.warn("ReSync extension reload deferred for " + state.pluginId + " while runtime work is active");
                return false;
            }
            transaction.stageReload(state);
            return transaction.commit(true, admissionFence);
        } catch (RuntimeException exception) {
            Log.warn("[ReSync] Failed to stage extension reload " + state.pluginId + ": " + exception.getMessage());
            return false;
        } finally {
            transaction.close();
        }
    }

    private boolean unregister(ExtensionState state, FlowExecutor.AdmissionFence admissionFence) {
        String pluginId = state.pluginId;
        blockedUnloads.remove(pluginId);
        if (activeExtension(pluginId) != state) {
            return false;
        }
        LifecycleTransaction transaction = new LifecycleTransaction(admissionFence);
        try {
            if (!transaction.stageRuntimeRetirement(state)) {
                blockedUnloads.add(pluginId);
                Log.warn("ReSync extension unload deferred for " + pluginId + " while runtime work is active");
                return false;
            }
            transaction.stageRemove(state);
            boolean committed = transaction.commit(true, admissionFence);
            if (committed) {
                Log.info("[ReSync] Unregistered extension " + pluginId);
            }
            return committed;
        } catch (RuntimeException exception) {
            Log.warn("[ReSync] Failed to unload extension " + pluginId + ": " + exception.getMessage());
            return false;
        } finally {
            transaction.close();
        }
    }

    private FlowExecutor.AdmissionFence fenceLegacyExecutions() {
        FlowExecutor executor = moduleContext.getService(FlowExecutor.class);
        return executor != null ? executor.fenceAdmissions() : null;
    }

    private void awaitLegacyExecutions(FlowExecutor.AdmissionFence admissionFence) {
        if (admissionFence != null) {
            admissionFence.awaitDrained();
        }
    }

    private boolean deferUntilLegacyExecutionsDrain(FlowExecutor.AdmissionFence admissionFence, String operation, Runnable action) {
        if (admissionFence == null || !isPrimaryThread() || admissionFence.isDrained()) {
            return false;
        }
        Set<String> pending = operation.startsWith("reload:") ? deferredReloads : deferredUnloads;
        if (!pending.add(operation)) {
            closeAdmissionFence(admissionFence);
            return true;
        }
        CompletableFuture<Void> drained = admissionFence.whenDrained();
        drained.whenComplete((ignored, failure) -> {
            pending.remove(operation);
            if (failure != null) {
                Log.error("ReSync extension lifecycle operation could not drain active executions: " + failure.getMessage(), failure);
                closeAdmissionFence(admissionFence);
                return;
            }
            Runnable continuation = () -> {
                persistenceLock.writeLock().lock();
                try {
                    if (persistenceState != PersistenceState.OPEN && !shutdownRequested) {
                        closeAdmissionFence(admissionFence);
                        return;
                    }
                    action.run();
                } catch (RuntimeException exception) {
                    Log.error("ReSync extension lifecycle operation failed: " + exception.getMessage(), exception);
                    closeAdmissionFence(admissionFence);
                } finally {
                    persistenceLock.writeLock().unlock();
                }
            };
            if (isPrimaryThread()) {
                continuation.run();
                return;
            }
            try {
                Bukkit.getScheduler().runTask(moduleContext.getPlugin(), continuation);
            } catch (RuntimeException exception) {
                Log.error("ReSync extension lifecycle operation could not return to the primary thread: " + exception.getMessage(), exception);
                closeAdmissionFence(admissionFence);
            }
        });
        return true;
    }

    private boolean isPrimaryThread() {
        return Bukkit.getServer() != null && Bukkit.isPrimaryThread();
    }

    private void closeAdmissionFence(FlowExecutor.AdmissionFence admissionFence) {
        if (admissionFence != null) {
            synchronized (retainedAdmissionFences) {
                if (retainedAdmissionFences.contains(admissionFence)) {
                    return;
                }
            }
            admissionFence.close();
        }
    }

    private void retainAdmissionFence(FlowExecutor.AdmissionFence admissionFence) {
        if (admissionFence != null) {
            synchronized (retainedAdmissionFences) {
                retainedAdmissionFences.add(admissionFence);
            }
        }
    }

    private void releaseAdmissionFence(FlowExecutor.AdmissionFence admissionFence) {
        if (admissionFence == null) {
            return;
        }
        boolean retained;
        synchronized (retainedAdmissionFences) {
            retained = retainedAdmissionFences.remove(admissionFence);
        }
        if (retained) {
            admissionFence.close();
        }
    }

    private void retryPendingLifecycleCompensation() {
        PendingLifecycleCompensation pending = pendingLifecycleCompensation;
        if (pending == null || deferLifecycleRetry()) {
            return;
        }
        if (!pending.retryActions.isEmpty()) {
            List<Runnable> remaining = new ArrayList<>();
            for (int index = 0; index < pending.retryActions.size(); index++) {
                Runnable action = pending.retryActions.get(index);
                try {
                    action.run();
                } catch (RuntimeException | Error failure) {
                    remaining.addAll(pending.retryActions.subList(index, pending.retryActions.size()));
                    Log.warn("ReSync extension lifecycle compensation retry failed: " + failure.getMessage());
                    break;
                }
            }
            pending.retryActions = List.copyOf(remaining);
            if (!remaining.isEmpty()) {
                return;
            }
        }
        if (!pending.catalogRuntimeRestorePending) {
            if (pending.recoveryMarkerPending && !clearLifecycleRecoveryMarker()) {
                return;
            }
            pending.recoveryMarkerPending = false;
            if (registryActivation != null && registryActivation.hasProjectionFailure()) {
                lifecycleDegraded = true;
                retainAdmissionFence(pending.admissionFence);
                return;
            }
            if (pendingLifecycleCompensation == pending) {
                pendingLifecycleCompensation = null;
                releaseAdmissionFence(pending.admissionFence);
                lifecycleDegraded = false;
            }
            return;
        }
        FlowModule flowModule = moduleContext.getService(FlowModule.class);
        if (flowModule == null) {
            return;
        }
        try {
            if (flowModule.catalogRuntimeCleanupPending() && !flowModule.retryPendingCatalogRuntimeCleanup()) {
                return;
            }
            if (!pending.restoreAttempted) {
                if (pending.catalogRuntimeTransaction != null) {
                    if (!pending.catalogRuntimeTransaction.rollback()) {
                        return;
                    }
                } else if (!flowModule.refreshCatalog()) {
                    return;
                }
                if (!flowModule.restoreCatalogPublicationKey(pending.previousCatalogPublicationKey)) {
                    return;
                }
                pending.restoreAttempted = true;
            }
            if (flowModule.catalogRuntimeCleanupPending() && !flowModule.retryPendingCatalogRuntimeCleanup()) {
                return;
            }
            if (flowModule.catalogRuntimeCleanupPending()) {
                return;
            }
            if (pending.recoveryMarkerPending && !clearLifecycleRecoveryMarker()) {
                return;
            }
            pending.recoveryMarkerPending = false;
            pending.catalogRuntimeRestorePending = false;
            if (registryActivation != null && registryActivation.hasProjectionFailure()) {
                lifecycleDegraded = true;
                retainAdmissionFence(pending.admissionFence);
                return;
            }
            if (pendingLifecycleCompensation == pending) {
                pendingLifecycleCompensation = null;
                releaseAdmissionFence(pending.admissionFence);
                lifecycleDegraded = false;
            }
        } catch (RuntimeException exception) {
            Log.warn("ReSync extension lifecycle compensation retry failed: " + exception.getMessage());
        }
    }

    private void unloadDisabledOwners() {
        if (activeExtensions().values().stream().noneMatch(state -> state.owner != null && !state.owner.isEnabled())) {
            return;
        }
        FlowExecutor.AdmissionFence admissionFence = fenceLegacyExecutions();
        if (deferUntilLegacyExecutionsDrain(admissionFence, "unload:disabled",
            () -> {
                try {
                    awaitLegacyExecutions(admissionFence);
                    unloadDisabledOwnersAfterDrain(admissionFence);
                } finally {
                    closeAdmissionFence(admissionFence);
                }
            })) {
            return;
        }
        try {
            awaitLegacyExecutions(admissionFence);
            unloadDisabledOwnersAfterDrain(admissionFence);
        } finally {
            closeAdmissionFence(admissionFence);
        }
    }

    private void unloadDisabledOwnersAfterDrain(FlowExecutor.AdmissionFence admissionFence) {
        List<ExtensionState> disabled = new ArrayList<>();
        for (ExtensionState state : new ArrayList<>(activeExtensions().values())) {
            if (state.owner != null && !state.owner.isEnabled()) {
                disabled.add(state);
            }
        }
        if (disabled.isEmpty()) {
            return;
        }
        LifecycleTransaction transaction = new LifecycleTransaction(admissionFence);
        try {
            boolean staged = true;
            for (ExtensionState state : disabled) {
                if (!transaction.stageRuntimeRetirement(state)) {
                    blockedUnloads.add(state.pluginId);
                    staged = false;
                    break;
                }
                if (!transaction.stageRemove(state)) {
                    staged = false;
                    break;
                }
            }
            if (staged && transaction.commit(true, admissionFence)) {
                for (ExtensionState state : disabled) {
                    tryCloseJar(state.jarPath);
                }
            }
        } catch (RuntimeException exception) {
            Log.warn("ReSync disabled extension unload transaction failed: " + exception.getMessage());
        } finally {
            transaction.close();
        }
    }

    private void scanDirectory() {
        ensureDirectory();
        Map<Path, Long> currentFiles = new ConcurrentHashMap<>();
        for (Path directory : extensionDirectories) {
            try (var stream = Files.list(directory)) {
                stream.filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).forEach(path -> {
                    try {
                        currentFiles.put(path, Files.getLastModifiedTime(path).toMillis());
                    } catch (IOException exception) {
                        Log.warn("Failed to stat extension jar " + path + ": " + exception.getMessage());
                    }
                });
            } catch (IOException exception) {
                Log.warn("Failed to scan extension directory " + directory + ": " + exception.getMessage());
            }
        }
        List<Path> changed = new ArrayList<>();
        for (Path existing : jarStates.keySet()) {
            Long modified = currentFiles.get(existing);
            JarState state = jarStates.get(existing);
            if (modified == null || state != null && state.lastModified != modified) {
                changed.add(existing);
            }
        }
        for (Path path : currentFiles.keySet()) {
            if (!jarStates.containsKey(path)) {
                changed.add(path);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        FlowExecutor.AdmissionFence admissionFence = fenceLegacyExecutions();
        Map<Path, Long> snapshot = Map.copyOf(currentFiles);
        if (deferUntilLegacyExecutionsDrain(admissionFence, "reload:jars",
            () -> {
                try {
                    awaitLegacyExecutions(admissionFence);
                    reconcileJarChangesAfterDrain(snapshot, changed, admissionFence);
                } finally {
                    closeAdmissionFence(admissionFence);
                }
            })) {
            return;
        }
        try {
            awaitLegacyExecutions(admissionFence);
            reconcileJarChangesAfterDrain(snapshot, changed, admissionFence);
        } finally {
            closeAdmissionFence(admissionFence);
        }
    }

    private void reconcileJarChangesAfterDrain(Map<Path, Long> currentFiles, List<Path> changed,
                                                FlowExecutor.AdmissionFence admissionFence) {
        LifecycleTransaction transaction = new LifecycleTransaction(admissionFence);
        Map<Path, URLClassLoader> stagedLoaders = new LinkedHashMap<>();
        Map<Path, List<String>> stagedPluginIds = new LinkedHashMap<>();
        List<JarState> retiredJars = new ArrayList<>();
        transaction.closeActions.add(() -> stagedLoaders.values().forEach(this::close));
        try {
            for (Path path : changed) {
                JarState previous = jarStates.get(path);
                if (previous == null) {
                    continue;
                }
                retiredJars.add(previous);
                for (String pluginId : previous.pluginIds) {
                    ExtensionState state = activeExtension(pluginId);
                    if (state != null && (!transaction.stageRuntimeRetirement(state) || !transaction.stageRemove(state))) {
                        throw new IllegalStateException("Runtime bindings are still active for extension " + pluginId);
                    }
                }
            }
            for (Path path : changed) {
                Long modified = currentFiles.get(path);
                if (modified == null) {
                    continue;
                }
                URLClassLoader classLoader = new URLClassLoader(new URL[]{path.toUri().toURL()}, ReSyncExtension.class.getClassLoader());
                stagedLoaders.put(path, classLoader);
                List<String> pluginIds = new ArrayList<>();
                ServiceLoader<ReSyncExtension> loader = ServiceLoader.load(ReSyncExtension.class, classLoader);
                for (ReSyncExtension extension : loader) {
                    if (extension == null || extension.getPluginId() == null || extension.getPluginId().isBlank()) {
                        continue;
                    }
                    String pluginId = normalizePluginId(extension.getPluginId());
                    if (transaction.stagedExtensions.containsKey(pluginId)) {
                        throw new IllegalArgumentException("Duplicate ReSync extension id: " + pluginId);
                    }
                    ExtensionState state = transaction.stageAdd(null, extension, classLoader, path);
                    pluginIds.add(state.pluginId);
                }
                if (pluginIds.isEmpty()) {
                    throw new IllegalStateException("Extension jar contains no valid ReSync extensions: " + path);
                }
                stagedPluginIds.put(path, pluginIds);
            }
            transaction.commitActions.add(() -> {
                for (Path path : changed) {
                    JarState previous = jarStates.remove(path);
                    if (previous != null && !retiredJars.contains(previous)) {
                        retiredJars.add(previous);
                    }
                    URLClassLoader classLoader = stagedLoaders.get(path);
                    List<String> pluginIds = stagedPluginIds.get(path);
                    if (classLoader != null && pluginIds != null) {
                        jarStates.put(path, new JarState(currentFiles.get(path), classLoader, pluginIds));
                    }
                }
                for (JarState retired : retiredJars) {
                    close(retired.classLoader);
                }
                stagedLoaders.clear();
            });
            transaction.commit(true, admissionFence);
        } catch (ServiceConfigurationError | LinkageError | Exception exception) {
            Log.error("[ReSync] Failed to reconcile extension jars: " + exception.getMessage(), exception);
        } finally {
            transaction.close();
        }
    }

    private void loadJar(Path jarPath, long modified) {
        FlowExecutor.AdmissionFence admissionFence = fenceLegacyExecutions();
        if (deferUntilLegacyExecutionsDrain(admissionFence, "reload:jar:" + jarPath,
            () -> {
                try {
                    awaitLegacyExecutions(admissionFence);
                    loadJarAfterDrain(jarPath, modified, admissionFence);
                } finally {
                    closeAdmissionFence(admissionFence);
                }
            })) {
            return;
        }
        try {
            awaitLegacyExecutions(admissionFence);
            loadJarAfterDrain(jarPath, modified, admissionFence);
        } finally {
            closeAdmissionFence(admissionFence);
        }
    }

    private void loadJarAfterDrain(Path jarPath, long modified, FlowExecutor.AdmissionFence admissionFence) {
        List<String> pluginIds = new ArrayList<>();
        URLClassLoader classLoader = null;
        LifecycleTransaction transaction = new LifecycleTransaction(admissionFence);
        boolean accepted = false;
        try {
            classLoader = new URLClassLoader(new URL[]{jarPath.toUri().toURL()}, ReSyncExtension.class.getClassLoader());
            ServiceLoader<ReSyncExtension> loader = ServiceLoader.load(ReSyncExtension.class, classLoader);
            for (ReSyncExtension extension : loader) {
                if (extension == null || extension.getPluginId() == null || transaction.stagedExtensions.containsKey(extension.getPluginId().trim().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                ExtensionState state = transaction.stageAdd(null, extension, classLoader, jarPath);
                pluginIds.add(state.pluginId);
            }
            accepted = !pluginIds.isEmpty() && transaction.commit(true, admissionFence);
            if (accepted) {
                jarStates.put(jarPath, new JarState(modified, classLoader, pluginIds));
            }
        } catch (ServiceConfigurationError | LinkageError | Exception exception) {
            Log.error("[ReSync] Failed to load extension jar " + jarPath + ": " + exception.getMessage(), exception);
        } finally {
            transaction.close();
            if (!accepted) {
                close(classLoader);
            }
        }
    }

    private void unloadJar(Path jarPath) {
        FlowExecutor.AdmissionFence admissionFence = fenceLegacyExecutions();
        if (deferUntilLegacyExecutionsDrain(admissionFence, "unload:jar:" + jarPath,
            () -> {
                try {
                    awaitLegacyExecutions(admissionFence);
                    unloadJarAfterDrain(jarPath, admissionFence);
                } finally {
                    closeAdmissionFence(admissionFence);
                }
            })) {
            return;
        }
        try {
            awaitLegacyExecutions(admissionFence);
            unloadJarAfterDrain(jarPath, admissionFence);
        } finally {
            closeAdmissionFence(admissionFence);
        }
    }

    private void unloadJarAfterDrain(Path jarPath, FlowExecutor.AdmissionFence admissionFence) {
        JarState state = jarStates.get(jarPath);
        if (state == null) {
            return;
        }
        List<ExtensionState> previous = state.pluginIds.stream()
            .map(this::activeExtension)
            .filter(Objects::nonNull)
            .toList();
        LifecycleTransaction transaction = new LifecycleTransaction(admissionFence);
        try {
            boolean staged = true;
            for (ExtensionState extension : previous) {
                if (!transaction.stageRuntimeRetirement(extension) || !transaction.stageRemove(extension)) {
                    staged = false;
                    break;
                }
            }
            boolean unloaded = staged && transaction.commit(true, admissionFence);
            transaction.close();
            if (!unloaded) {
                return;
            }
            if (jarStates.remove(jarPath, state)) {
                close(state.classLoader);
            }
            return;
        } catch (RuntimeException exception) {
            transaction.close();
            Log.warn("ReSync extension jar unload failed for " + jarPath + ": " + exception.getMessage());
        }
    }

    private void retryBlockedLifecycle() {
        for (String pluginId : new ArrayList<>(blockedUnloads)) {
            if (unregister(pluginId)) {
                blockedUnloads.remove(pluginId);
            }
        }
        for (String pluginId : new ArrayList<>(blockedReloads)) {
            ExtensionState state = activeExtension(pluginId);
            if (state == null) {
                blockedReloads.remove(pluginId);
                continue;
            }
            FlowExecutor.AdmissionFence admissionFence = fenceLegacyExecutions();
            if (deferUntilLegacyExecutionsDrain(admissionFence, "reload:" + pluginId,
                () -> {
                    try {
                        reloadExtension(state, admissionFence);
                    } finally {
                        closeAdmissionFence(admissionFence);
                    }
                })) {
                continue;
            }
            try {
                awaitLegacyExecutions(admissionFence);
                reloadExtension(state, admissionFence);
            } finally {
                closeAdmissionFence(admissionFence);
            }
        }
        if (shutdownRequested) {
            finishShutdownIfIdle();
        }
    }

    private void tryCloseJar(Path jarPath) {
        if (jarPath == null) {
            return;
        }
        JarState state = jarStates.get(jarPath);
        if (state != null && state.pluginIds.stream().noneMatch(activeExtensions()::containsKey)
            && jarStates.remove(jarPath, state)) {
            close(state.classLoader);
        }
    }

    private void ensureDirectory() {
        for (Path directory : extensionDirectories) {
            try {
                validateLifecycleDirectoryAncestors(directory);
                Files.createDirectories(directory);
            } catch (IOException exception) {
                Log.warn("Failed to create extension directory " + directory + ": " + exception.getMessage());
            }
        }
    }

    private String normalizePluginId(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("Extension plugin id is required");
        }
        String normalized = pluginId.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]{1,63}")) {
            throw new IllegalArgumentException("Invalid ReSync extension id: " + pluginId);
        }
        return normalized;
    }

    private static boolean isCanonicalPluginId(String pluginId) {
        return pluginId != null && pluginId.equals(pluginId.trim())
            && pluginId.equals(pluginId.toLowerCase(Locale.ROOT))
            && pluginId.matches("[a-z0-9][a-z0-9_.-]{1,63}");
    }

    private void validateNamespaced(String pluginId, String id, String kind) {
        if (id == null || id.isBlank() || !id.startsWith(pluginId + ":")) {
            throw new IllegalArgumentException(kind + " id must be namespaced as " + pluginId + ":name");
        }
    }

    private boolean hasLifecycleRecoveryMarker() {
        boolean evidence = false;
        LifecycleRecoveryLockSet acquired = null;
        for (Path directory : extensionDirectories) {
            try {
                if (acquired == null) {
                    acquired = acquireLifecycleRecoveryLocks();
                }
                if (inspectLifecycleRecoveryDirectory(directory).hasEvidence()) {
                    evidence = true;
                }
            } catch (IOException | RuntimeException exception) {
                evidence = true;
                Log.warn("ReSync extension lifecycle recovery evidence could not be inventoried: "
                    + exception.getMessage());
            }
        }
        releaseLifecycleRecoveryLocks(acquired);
        return evidence;
    }

    private boolean probeRecoveryDurability() {
        recoveryDurabilityFailure = "";
        if (extensionDirectories.isEmpty()) {
            recoveryDurabilityFailure = "No extension directory is configured";
            return false;
        }
        LifecycleRecoveryLockSet acquired = null;
        try {
            acquired = acquireLifecycleRecoveryLocks();
            for (Path directory : extensionDirectories) {
                Path safeDirectory = requireLifecycleDirectory(directory);
                LifecycleRecoveryInventory inventory = inspectLifecycleRecoveryDirectory(safeDirectory);
                cleanLifecycleRecoveryProbeArtifacts(safeDirectory, inventory.probeFiles());
                Path temporary = null;
                try {
                    LifecycleRecoveryProbeReservation reservation = reserveLifecycleRecoveryProbe(safeDirectory);
                    temporary = reservation.path();
                    Path target = safeDirectory.resolve(LIFECYCLE_RECOVERY_PROBE_PREFIX + reservation.identity()
                        + LIFECYCLE_RECOVERY_PROBE_TEMP_SUFFIX).toAbsolutePath().normalize();
                    byte[] payload = lifecycleRecoveryProbePayload(reservation.identity());
                    try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                        ByteBuffer bytes = ByteBuffer.wrap(payload);
                        while (bytes.hasRemaining()) {
                            channel.write(bytes);
                        }
                        channel.force(true);
                    }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                        throw new IOException("Lifecycle durability probe target is already occupied: " + target);
                    }
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException exception) {
                        throw new IOException("Lifecycle durability probe requires atomic publication", exception);
                    }
                    recoveryBackend.forceDirectory(safeDirectory);
                    if (!Files.deleteIfExists(target)) {
                        throw new IOException("Lifecycle durability probe target disappeared: " + target);
                    }
                    recoveryBackend.forceDirectory(safeDirectory);
                } catch (IOException | RuntimeException exception) {
                    recoveryDurabilityFailure = "Atomic lifecycle marker publication is unavailable for "
                        + safeDirectory + ": " + exception.getMessage();
                    return false;
                }
                if (!recoveryDurabilityFailure.isBlank()) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            recoveryDurabilityFailure = "Atomic lifecycle marker publication is unavailable: "
                + exception.getMessage();
            return false;
        } finally {
            releaseLifecycleRecoveryLocks(acquired);
        }
    }

    private boolean reconcileStartupRecovery() {
        if (!startupRecoveryRequired) {
            return true;
        }
        LifecycleRecoveryLockSet acquired = null;
        try {
            acquired = acquireLifecycleRecoveryLocks();
            Map<Path, LifecycleRecoveryMarkerSnapshot> expectedMarkers = new LinkedHashMap<>();
            for (Path directory : extensionDirectories) {
                LifecycleRecoveryInventory inventory = inspectLifecycleRecoveryDirectory(directory);
                if (!inventory.temporaryFiles().isEmpty() || !inventory.probeFiles().isEmpty()) {
                    return false;
                }
                Path markerPath = inventory.markerPath();
                if (markerPath == null) {
                    continue;
                }
                LifecycleRecoveryMarkerSnapshot snapshot = readLifecycleRecoveryMarkerSnapshot(markerPath);
                expectedMarkers.put(directory, snapshot);
                if (!recoveryMarkerReconciled(snapshot.marker())) {
                    return false;
                }
            }
            if (!clearLifecycleRecoveryMarker(true, expectedMarkers)) {
                return false;
            }
            if (hasLifecycleRecoveryMarker()) {
                return false;
            }
            startupRecoveryRequired = false;
            return true;
        } catch (IOException | RuntimeException exception) {
            Log.warn("ReSync extension lifecycle startup recovery remains fenced: " + exception.getMessage());
            return false;
        } finally {
            releaseLifecycleRecoveryLocks(acquired);
        }
    }

    private RecoveryMarker readLifecycleRecoveryMarker(Path markerPath) throws IOException {
        return readLifecycleRecoveryMarkerSnapshot(markerPath).marker();
    }

    private LifecycleRecoveryMarkerSnapshot readLifecycleRecoveryMarkerSnapshot(Path markerPath) throws IOException {
        Path normalizedMarker = markerPath.toAbsolutePath().normalize();
        if (normalizedMarker.getFileName() == null
            || !LIFECYCLE_RECOVERY_MARKER.equals(normalizedMarker.getFileName().toString())
            || normalizedMarker.getParent() == null
            || !normalizedMarker.equals(lifecycleRecoveryMarker(normalizedMarker.getParent()))) {
            throw new IOException("Lifecycle recovery marker identity is not canonical");
        }
        if (Files.isSymbolicLink(normalizedMarker)
            || !Files.isRegularFile(normalizedMarker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Lifecycle recovery marker is not a regular file");
        }
        BasicFileAttributes before = Files.readAttributes(normalizedMarker, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        long size = before.size();
        if (size > MAXIMUM_LIFECYCLE_RECOVERY_MARKER_BYTES) {
            throw new IOException("Lifecycle recovery marker is too large");
        }
        byte[] bytes = Files.readAllBytes(normalizedMarker);
        BasicFileAttributes after = Files.readAttributes(normalizedMarker, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (!sameLifecycleMarkerAttributes(before, after) || after.size() != bytes.length) {
            throw new IOException("Lifecycle recovery marker changed while it was being read");
        }
        String payload = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, payload.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Lifecycle recovery marker is not valid UTF-8");
        }
        Set<String> plugins = new HashSet<>();
        Set<String> providers = new HashSet<>();
        Set<String> worlds = new HashSet<>();
        Set<String> modules = new HashSet<>();
        Set<String> types = new HashSet<>();
        boolean versionSeen = false;
        String[] lines = payload.split("\n", -1);
        if (lines.length < 2 || !lines[lines.length - 1].isEmpty()) {
            throw new IOException("Lifecycle recovery marker is missing its canonical line ending");
        }
        for (int index = 0; index < lines.length - 1; index++) {
            String line = lines[index];
            if (line.isEmpty() || line.indexOf('\r') >= 0) {
                throw new IOException("Lifecycle recovery marker is malformed");
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1 || separator != line.lastIndexOf('=')) {
                throw new IOException("Lifecycle recovery marker is malformed");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (value.isBlank()) {
                throw new IOException("Lifecycle recovery marker value is malformed");
            }
            switch (key) {
                case "version" -> {
                    if (versionSeen || !LIFECYCLE_RECOVERY_MARKER_VERSION.equals(value)) {
                        throw new IOException("Lifecycle recovery marker version is unsupported");
                    }
                    versionSeen = true;
                }
                case "plugin" -> {
                    if (!isCanonicalPluginId(value)) {
                        throw new IOException("Lifecycle recovery marker plugin is not canonical");
                    }
                    if (!plugins.add(value)) {
                        throw new IOException("Lifecycle recovery marker contains a duplicate plugin");
                    }
                }
                case "provider" -> {
                    if (!providers.add(value)) {
                        throw new IOException("Lifecycle recovery marker contains a duplicate provider");
                    }
                }
                case "world" -> {
                    if (!worlds.add(value)) {
                        throw new IOException("Lifecycle recovery marker contains a duplicate world");
                    }
                }
                case "module" -> {
                    if (!modules.add(value)) {
                        throw new IOException("Lifecycle recovery marker contains a duplicate module");
                    }
                }
                case "type" -> {
                    if (!types.add(value)) {
                        throw new IOException("Lifecycle recovery marker contains a duplicate type");
                    }
                }
                default -> throw new IOException("Lifecycle recovery marker contains an unknown field");
            }
        }
        if (!versionSeen) {
            throw new IOException("Lifecycle recovery marker version is missing");
        }
        RecoveryMarker marker = new RecoveryMarker(plugins, providers, worlds, modules, types);
        if (!marker.encode().equals(payload)) {
            throw new IOException("Lifecycle recovery marker content is not canonical");
        }
        return new LifecycleRecoveryMarkerSnapshot(normalizedMarker, marker, bytes, before.fileKey(),
            before.creationTime(), before.lastModifiedTime(), before.size());
    }

    private void verifyLifecycleRecoveryMarker(LifecycleRecoveryMarkerSnapshot expected) throws IOException {
        LifecycleRecoveryMarkerSnapshot current = readLifecycleRecoveryMarkerSnapshot(expected.path());
        if (!sameLifecycleRecoveryMarker(expected, current)) {
            throw new IOException("Lifecycle recovery marker identity or content changed: " + expected.path());
        }
    }

    private static boolean sameLifecycleRecoveryMarker(LifecycleRecoveryMarkerSnapshot first,
                                                        LifecycleRecoveryMarkerSnapshot second) {
        return first.path().equals(second.path())
            && Objects.equals(first.fileKey(), second.fileKey())
            && first.creationTime().equals(second.creationTime())
            && first.lastModifiedTime().equals(second.lastModifiedTime())
            && first.size() == second.size()
            && Arrays.equals(first.content(), second.content());
    }

    private static boolean sameLifecycleMarkerAttributes(BasicFileAttributes first,
                                                         BasicFileAttributes second) {
        return first.isRegularFile() && second.isRegularFile()
            && Objects.equals(first.fileKey(), second.fileKey())
            && first.creationTime().equals(second.creationTime())
            && first.lastModifiedTime().equals(second.lastModifiedTime())
            && first.size() == second.size();
    }

    private boolean recoveryMarkerReconciled(RecoveryMarker marker) {
        if (moduleContext == null) {
            return false;
        }
        CustomContentService customContent = moduleContext.getService(CustomContentService.class);
        if (customContent != null && marker.providers().stream().anyMatch(customContent::hasProvider)) {
            return false;
        }
        WorldMapService worldMap = moduleContext.getService(WorldMapService.class);
        if (worldMap != null && marker.worlds().stream().anyMatch(id -> worldMap.getExtensions().stream()
            .anyMatch(extension -> id.equals(extension.getExtensionId())))) {
            return false;
        }
        if (marker.modules().stream().anyMatch(moduleContext.getModuleRegistry()::hasModule)) {
            return false;
        }
        Map<String, FlowDataType> registeredTypes = FlowDataType.registeredTypes();
        return marker.types().stream().noneMatch(typeId -> {
            FlowDataType type = registeredTypes.get(typeId.toLowerCase(Locale.ROOT));
            return type != null && marker.plugins().contains(type.getOwner());
        });
    }

    private void writeLifecycleRecoveryMarker(Collection<ExtensionState> states) {
        RecoveryMarker marker = RecoveryMarker.from(states);
        String payload = marker.encode();
        LifecycleRecoveryLockSet acquired = null;
        try {
            acquired = acquireLifecycleRecoveryLocks();
            for (Path directory : extensionDirectories) {
                LifecycleRecoveryInventory inventory = inspectLifecycleRecoveryDirectory(directory);
                if (inventory.hasEvidence()) {
                    throw new IOException("Lifecycle recovery directory already contains marker evidence: " + directory);
                }
                Path markerPath = lifecycleRecoveryMarker(directory);
                Path temporary = reserveLifecycleTemporary(directory, LIFECYCLE_RECOVERY_TEMP_PREFIX,
                    LIFECYCLE_RECOVERY_TEMP_SUFFIX);
                try {
                    try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                        ByteBuffer bytes = StandardCharsets.UTF_8.encode(payload);
                        while (bytes.hasRemaining()) {
                            channel.write(bytes);
                        }
                        channel.force(true);
                    }
                    recoveryDurability.at(RecoveryPoint.BEFORE_MARKER_RENAME, markerPath);
                    moveLifecycleRecoveryMarker(temporary, markerPath);
                    LifecycleRecoveryMarkerSnapshot published = readLifecycleRecoveryMarkerSnapshot(markerPath);
                    if (!Arrays.equals(published.content(), payload.getBytes(StandardCharsets.UTF_8))) {
                        throw new IOException("Published lifecycle recovery marker content does not match its payload");
                    }
                    lifecycleMarkerPublications.add(markerPath);
                    lifecycleMarkerPublicationSnapshots.put(markerPath, published);
                    forceLifecycleDirectory(directory);
                    if (!sameLifecycleRecoveryMarker(published, readLifecycleRecoveryMarkerSnapshot(markerPath))) {
                        throw new IOException("Published lifecycle recovery marker content does not match its identity");
                    }
                    recoveryDurability.at(RecoveryPoint.AFTER_MARKER_PUBLISH, markerPath);
                } catch (IOException | RuntimeException exception) {
                    startupRecoveryRequired = true;
                    retainLifecycleRecoveryTemporary(temporary, exception);
                    throw exception;
                }
            }
        } catch (IOException | RuntimeException exception) {
            startupRecoveryRequired = true;
            throw new IllegalStateException("ReSync extension lifecycle recovery marker could not be written", exception);
        } finally {
            releaseLifecycleRecoveryLocks(acquired);
        }
    }

    private boolean clearLifecycleRecoveryMarker() {
        return clearLifecycleRecoveryMarker(false, Map.of());
    }

    private boolean clearLifecycleRecoveryMarker(boolean allowUnowned) {
        return clearLifecycleRecoveryMarker(allowUnowned, Map.of());
    }

    private boolean clearLifecycleRecoveryMarker(boolean allowUnowned,
                                                 Map<Path, LifecycleRecoveryMarkerSnapshot> expectedMarkers) {
        LifecycleRecoveryLockSet acquired = null;
        boolean cleared = true;
        try {
            acquired = acquireLifecycleRecoveryLocks();
            for (Path directory : extensionDirectories) {
                Path markerPath = lifecycleRecoveryMarker(directory);
                try {
                    LifecycleRecoveryInventory inventory = inspectLifecycleRecoveryDirectory(directory);
                    if (!inventory.temporaryFiles().isEmpty() || !inventory.probeFiles().isEmpty()) {
                        cleared = false;
                        Log.warn("ReSync extension lifecycle recovery temporary evidence remains: " + directory);
                        continue;
                    }
                    LifecycleRecoveryMarkerSnapshot snapshot = null;
                    if (inventory.markerPath() != null) {
                        if (!allowUnowned && !lifecycleMarkerPublications.contains(markerPath)) {
                            cleared = false;
                            Log.warn("ReSync extension lifecycle recovery marker ownership is ambiguous: " + markerPath);
                            continue;
                        }
                        snapshot = readLifecycleRecoveryMarkerSnapshot(markerPath);
                        LifecycleRecoveryMarkerSnapshot expected = expectedMarkers.get(directory);
                        if (expected == null) {
                            expected = lifecycleMarkerPublicationSnapshots.get(markerPath);
                        }
                        if (expected != null && !sameLifecycleRecoveryMarker(expected, snapshot)) {
                            cleared = false;
                            Log.warn("ReSync extension lifecycle recovery marker changed before clear: " + markerPath);
                            continue;
                        }
                    }
                    recoveryDurability.at(RecoveryPoint.BEFORE_MARKER_CLEAR, markerPath);
                    if (snapshot != null) {
                        verifyLifecycleRecoveryMarker(snapshot);
                        if (Files.isSymbolicLink(markerPath)
                            || !Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)) {
                            cleared = false;
                            continue;
                        }
                        Files.deleteIfExists(markerPath);
                    }
                    forceLifecycleDirectory(directory);
                    recoveryDurability.at(RecoveryPoint.AFTER_MARKER_CLEAR, markerPath);
                    lifecycleMarkerPublications.remove(markerPath);
                    lifecycleMarkerPublicationSnapshots.remove(markerPath);
                } catch (IOException | RuntimeException exception) {
                    cleared = false;
                    Log.warn("ReSync extension lifecycle recovery marker remains: " + exception.getMessage());
                }
            }
        } catch (IOException | RuntimeException exception) {
            cleared = false;
            startupRecoveryRequired = true;
            Log.warn("ReSync extension lifecycle recovery marker lock is unavailable: " + exception.getMessage());
        } finally {
            releaseLifecycleRecoveryLocks(acquired);
        }
        if (cleared) {
            startupRecoveryRequired = hasLifecycleRecoveryMarker();
            if (startupRecoveryRequired) {
                cleared = false;
            }
        }
        return cleared;
    }

    private void moveLifecycleRecoveryMarker(Path temporary, Path marker) throws IOException {
        Path normalizedTemporary = temporary.toAbsolutePath().normalize();
        Path normalizedMarker = marker.toAbsolutePath().normalize();
        if (Files.exists(normalizedMarker, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalizedMarker)) {
            throw new IOException("Lifecycle recovery marker target is already occupied: " + normalizedMarker);
        }
        try {
            Files.move(normalizedTemporary, normalizedMarker, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Lifecycle recovery marker requires atomic publication", exception);
        }
        if (Files.exists(normalizedTemporary, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(normalizedMarker)
            || !Files.isRegularFile(normalizedMarker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Lifecycle recovery marker publication produced an invalid artifact");
        }
    }

    private Path reserveLifecycleTemporary(Path directory, String prefix, String suffix) throws IOException {
        Path normalizedDirectory = requireLifecycleDirectory(directory);
        for (int attempt = 0; attempt < LIFECYCLE_RECOVERY_TEMP_RESERVATION_ATTEMPTS; attempt++) {
            Path candidate = normalizedDirectory.resolve(prefix + UUID.randomUUID() + suffix)
                .toAbsolutePath().normalize();
            if (candidate.getParent() == null || !candidate.getParent().equals(normalizedDirectory)) {
                throw new IOException("Lifecycle recovery temporary identity escaped its directory");
            }
            try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Lifecycle recovery temporary is not a regular file: " + candidate);
            }
            return candidate;
        }
        throw new IOException("Unable to reserve a unique lifecycle recovery temporary file in: "
            + normalizedDirectory);
    }

    private LifecycleRecoveryProbeReservation reserveLifecycleRecoveryProbe(Path directory) throws IOException {
        Path normalizedDirectory = requireLifecycleDirectory(directory);
        for (int attempt = 0; attempt < LIFECYCLE_RECOVERY_TEMP_RESERVATION_ATTEMPTS; attempt++) {
            UUID identity = UUID.randomUUID();
            Path candidate = normalizedDirectory.resolve(LIFECYCLE_RECOVERY_PROBE_PREFIX + identity
                + LIFECYCLE_RECOVERY_PROBE_SOURCE_SUFFIX).toAbsolutePath().normalize();
            if (candidate.getParent() == null || !candidate.getParent().equals(normalizedDirectory)) {
                throw new IOException("Lifecycle recovery probe identity escaped its directory");
            }
            try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Lifecycle recovery probe is not a regular file: " + candidate);
            }
            return new LifecycleRecoveryProbeReservation(candidate, identity);
        }
        throw new IOException("Unable to reserve a unique lifecycle recovery probe in: " + normalizedDirectory);
    }

    private byte[] lifecycleRecoveryProbePayload(UUID identity) {
        return ("version=" + LIFECYCLE_RECOVERY_MARKER_VERSION + "\nprobe=" + identity + "\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private void cleanLifecycleRecoveryProbeArtifacts(Path directory, List<Path> probeFiles) throws IOException {
        if (probeFiles.isEmpty()) {
            return;
        }
        List<LifecycleRecoveryProbeSnapshot> expected = new ArrayList<>();
        for (Path probeFile : probeFiles) {
            expected.add(readLifecycleRecoveryProbeSnapshot(probeFile));
        }
        for (LifecycleRecoveryProbeSnapshot snapshot : expected) {
            LifecycleRecoveryProbeSnapshot current = readLifecycleRecoveryProbeSnapshot(snapshot.path());
            if (!sameLifecycleRecoveryProbe(snapshot, current)) {
                throw new IOException("Lifecycle recovery probe changed before cleanup: " + snapshot.path());
            }
            if (Files.isSymbolicLink(snapshot.path())
                || !Files.isRegularFile(snapshot.path(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Lifecycle recovery probe is not a regular file: " + snapshot.path());
            }
            if (!Files.deleteIfExists(snapshot.path())) {
                throw new IOException("Lifecycle recovery probe disappeared before cleanup: " + snapshot.path());
            }
        }
        forceLifecycleDirectory(directory);
    }

    private LifecycleRecoveryProbeSnapshot readLifecycleRecoveryProbeSnapshot(Path probePath) throws IOException {
        Path normalized = probePath.toAbsolutePath().normalize();
        UUID identity = lifecycleRecoveryProbeIdentity(normalized);
        if (Files.isSymbolicLink(normalized)
            || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Lifecycle recovery probe is not a regular file: " + normalized);
        }
        BasicFileAttributes before = Files.readAttributes(normalized, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (before.size() > MAXIMUM_LIFECYCLE_RECOVERY_PROBE_BYTES) {
            throw new IOException("Lifecycle recovery probe is too large: " + normalized);
        }
        byte[] content = Files.readAllBytes(normalized);
        BasicFileAttributes after = Files.readAttributes(normalized, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (!sameLifecycleMarkerAttributes(before, after) || after.size() != content.length) {
            throw new IOException("Lifecycle recovery probe changed while it was being read: " + normalized);
        }
        if (!Arrays.equals(content, lifecycleRecoveryProbePayload(identity))) {
            throw new IOException("Lifecycle recovery probe content is not canonical: " + normalized);
        }
        return new LifecycleRecoveryProbeSnapshot(normalized, content, before.fileKey(), before.creationTime(),
            before.lastModifiedTime(), before.size());
    }

    private UUID lifecycleRecoveryProbeIdentity(Path probePath) throws IOException {
        Path normalized = probePath.toAbsolutePath().normalize();
        if (normalized.getParent() == null || normalized.getFileName() == null) {
            throw new IOException("Lifecycle recovery probe identity is not canonical: " + normalized);
        }
        String name = normalized.getFileName().toString();
        if (!name.startsWith(LIFECYCLE_RECOVERY_PROBE_PREFIX)) {
            throw new IOException("Lifecycle recovery probe identity is not canonical: " + normalized);
        }
        String identity = name.substring(LIFECYCLE_RECOVERY_PROBE_PREFIX.length());
        if (identity.endsWith(LIFECYCLE_RECOVERY_PROBE_SOURCE_SUFFIX)) {
            identity = identity.substring(0, identity.length() - LIFECYCLE_RECOVERY_PROBE_SOURCE_SUFFIX.length());
        } else if (identity.endsWith(LIFECYCLE_RECOVERY_PROBE_TEMP_SUFFIX)) {
            identity = identity.substring(0, identity.length() - LIFECYCLE_RECOVERY_PROBE_TEMP_SUFFIX.length());
        } else {
            throw new IOException("Lifecycle recovery probe identity is malformed: " + normalized);
        }
        try {
            UUID uuid = UUID.fromString(identity);
            if (!uuid.toString().equals(identity)) {
                throw new IOException("Lifecycle recovery probe identity is malformed: " + normalized);
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Lifecycle recovery probe identity is malformed: " + normalized, exception);
        }
    }

    private static boolean sameLifecycleRecoveryProbe(LifecycleRecoveryProbeSnapshot first,
                                                      LifecycleRecoveryProbeSnapshot second) {
        return first.path().equals(second.path())
            && Objects.equals(first.fileKey(), second.fileKey())
            && first.creationTime().equals(second.creationTime())
            && first.lastModifiedTime().equals(second.lastModifiedTime())
            && first.size() == second.size()
            && Arrays.equals(first.content(), second.content());
    }

    private void retainLifecycleRecoveryTemporary(Path temporary, Throwable failure) {
        if (temporary == null || !Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.isSymbolicLink(temporary)
                || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Lifecycle recovery temporary evidence is not a regular file: " + temporary);
            }
        } catch (IOException | RuntimeException exception) {
            failure.addSuppressed(exception);
        }
    }

    private LifecycleRecoveryInventory inspectLifecycleRecoveryDirectory(Path directory) throws IOException {
        Path normalizedDirectory = requireLifecycleDirectory(directory);
        Path markerPath = null;
        List<Path> temporaryFiles = new ArrayList<>();
        List<Path> probeFiles = new ArrayList<>();
        try (var entries = Files.list(normalizedDirectory)) {
            for (Path entry : entries.toList()) {
                Path normalizedEntry = entry.toAbsolutePath().normalize();
                if (normalizedEntry.getParent() == null || !normalizedEntry.getParent().equals(normalizedDirectory)
                    || normalizedEntry.getFileName() == null) {
                    throw new IOException("Lifecycle recovery artifact identity is not canonical: " + entry);
                }
                String name = normalizedEntry.getFileName().toString();
                if (LIFECYCLE_RECOVERY_MARKER.equals(name)) {
                    if (Files.isSymbolicLink(normalizedEntry)
                        || !Files.isRegularFile(normalizedEntry, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Lifecycle recovery marker is not a regular file: " + normalizedEntry);
                    }
                    markerPath = normalizedEntry;
                    continue;
                }
                if (LIFECYCLE_RECOVERY_LOCK.equals(name)) {
                    if (Files.isSymbolicLink(normalizedEntry)
                        || !Files.isRegularFile(normalizedEntry, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Lifecycle recovery lock is not a regular file: " + normalizedEntry);
                    }
                    continue;
                }
                if (name.startsWith(LIFECYCLE_RECOVERY_PROBE_PREFIX)) {
                    lifecycleRecoveryProbeIdentity(normalizedEntry);
                    if (Files.isSymbolicLink(normalizedEntry)
                        || !Files.isRegularFile(normalizedEntry, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Lifecycle recovery probe is not a regular file: " + normalizedEntry);
                    }
                    if (probeFiles.size() >= MAXIMUM_LIFECYCLE_RECOVERY_PROBE_ARTIFACTS) {
                        throw new IOException("Too many lifecycle recovery probe artifacts in: " + normalizedDirectory);
                    }
                    probeFiles.add(normalizedEntry);
                    continue;
                }
                if (!name.startsWith(LIFECYCLE_RECOVERY_TEMP_PREFIX)) {
                    continue;
                }
                String identity = name.substring(LIFECYCLE_RECOVERY_TEMP_PREFIX.length(),
                    name.endsWith(LIFECYCLE_RECOVERY_TEMP_SUFFIX)
                        ? name.length() - LIFECYCLE_RECOVERY_TEMP_SUFFIX.length() : name.length());
                UUID uuid;
                try {
                    uuid = UUID.fromString(identity);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Lifecycle recovery temporary identity is malformed: " + normalizedEntry,
                        exception);
                }
                if (!name.endsWith(LIFECYCLE_RECOVERY_TEMP_SUFFIX) || !uuid.toString().equals(identity)) {
                    throw new IOException("Lifecycle recovery temporary identity is malformed: " + normalizedEntry);
                }
                if (Files.isSymbolicLink(normalizedEntry)
                    || !Files.isRegularFile(normalizedEntry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Lifecycle recovery temporary is not a regular file: " + normalizedEntry);
                }
                temporaryFiles.add(normalizedEntry);
            }
        }
        return new LifecycleRecoveryInventory(markerPath, temporaryFiles, probeFiles);
    }

    private void validateLifecycleDirectoryAncestors(Path directory) throws IOException {
        if (directory == null) {
            throw new IOException("Lifecycle recovery directory is required");
        }
        Path normalized = directory.toAbsolutePath().normalize();
        Path root = normalized.getRoot();
        if (root == null) {
            throw new IOException("Lifecycle recovery directory has no root: " + normalized);
        }
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("Lifecycle recovery directory root is a symbolic link: " + current);
        }
        for (Path part : root.relativize(normalized)) {
            current = current.resolve(part).normalize();
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Lifecycle recovery directory contains a symbolic-link ancestor: " + current);
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Lifecycle recovery directory ancestor is not a directory: " + current);
            }
        }
    }

    private Path requireLifecycleDirectory(Path directory) throws IOException {
        validateLifecycleDirectoryAncestors(directory);
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
            || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Lifecycle recovery directory is unavailable: " + normalized);
        }
        return normalized;
    }

    private LifecycleRecoveryLockSet acquireLifecycleRecoveryLocks() throws IOException {
        if (lifecycleRecoveryLocks.get() != null) {
            return null;
        }
        List<LifecycleRecoveryFileLock> locks = new ArrayList<>();
        try {
            for (Path directory : extensionDirectories) {
                Path normalizedDirectory = requireLifecycleDirectory(directory);
                Path lockPath = normalizedDirectory.resolve(LIFECYCLE_RECOVERY_LOCK)
                    .toAbsolutePath().normalize();
                if (lockPath.getParent() == null || !lockPath.getParent().equals(normalizedDirectory)
                    || Files.isSymbolicLink(lockPath)) {
                    throw new IOException("Lifecycle recovery lock identity is not canonical: " + lockPath);
                }
                FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                boolean retained = false;
                try {
                    if (Files.isSymbolicLink(lockPath)
                        || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Lifecycle recovery lock is not a regular file: " + lockPath);
                    }
                    FileLock lock;
                    try {
                        lock = channel.tryLock();
                    } catch (OverlappingFileLockException exception) {
                        throw new IOException("Lifecycle recovery lock is already held: " + lockPath, exception);
                    }
                    if (lock == null) {
                        throw new IOException("Lifecycle recovery lock is already held: " + lockPath);
                    }
                    locks.add(new LifecycleRecoveryFileLock(channel, lock));
                    retained = true;
                } finally {
                    if (!retained) {
                        channel.close();
                    }
                }
            }
            LifecycleRecoveryLockSet acquired = new LifecycleRecoveryLockSet(locks);
            lifecycleRecoveryLocks.set(acquired);
            return acquired;
        } catch (IOException | RuntimeException exception) {
            closeLifecycleRecoveryLocks(locks, exception);
            throw exception;
        }
    }

    private void releaseLifecycleRecoveryLocks(LifecycleRecoveryLockSet acquired) {
        if (acquired == null) {
            return;
        }
        try {
            acquired.close();
        } catch (IOException exception) {
            lifecycleRecoveryLockReleaseFailed = true;
            startupRecoveryRequired = true;
            recoveryDurabilityAvailable = false;
            recoveryDurabilityFailure = "Lifecycle recovery lock release failed: " + exception.getMessage();
            Log.warn(recoveryDurabilityFailure);
        } finally {
            if (lifecycleRecoveryLocks.get() == acquired) {
                lifecycleRecoveryLocks.remove();
            }
        }
    }

    private void closeLifecycleRecoveryLocks(List<LifecycleRecoveryFileLock> locks, Throwable failure) {
        for (int index = locks.size() - 1; index >= 0; index--) {
            try {
                locks.get(index).close();
            } catch (IOException exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    private void forceLifecycleDirectory(Path directory) throws IOException {
        recoveryBackend.forceDirectory(requireLifecycleDirectory(directory));
    }

    private Path lifecycleRecoveryMarker(Path directory) {
        return directory.resolve(LIFECYCLE_RECOVERY_MARKER).toAbsolutePath().normalize();
    }


    private Path extensionStorageRoot(ExtensionState state) throws IOException {
        Path root = requireExtensionRoot(persistenceRoot(), "persistenceRoot");
        Path extensionRoot = root.resolve(state.pluginId).normalize();
        if (!extensionRoot.startsWith(root) || extensionRoot.equals(root)) {
            throw new IOException("Extension storage escaped its owner root: " + state.pluginId);
        }
        Files.createDirectories(extensionRoot);
        return requireExtensionRoot(extensionRoot, "extensionRoot");
    }

    private <T> T accessStorage(ExtensionState state, boolean write, ExtensionStorage.StorageOperation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        persistenceLock.readLock().lock();
        try {
            if (state == null || (activeExtension(state.pluginId) != state && !state.lifecycleStorageAccess)) {
                throw new IOException("Extension storage owner is no longer active");
            }
            if (write && persistenceState != PersistenceState.OPEN) {
                throw new IOException("Extension persistence is quiesced");
            }
            Path root = extensionStorageRoot(state);
            return operation.apply(ExtensionStorage.files(root));
        } finally {
            persistenceLock.readLock().unlock();
        }
    }

    private void flushPersistenceInternal() throws IOException {
        for (ExtensionState state : orderedExtensions()) {
            state.extension.flushPersistence();
        }
    }

    private void healthCheckPersistenceInternal() throws IOException {
        Path root = requireExtensionRoot(persistenceRoot(), "persistenceRoot");
        for (ExtensionState state : orderedExtensions()) {
            if (state.jarPath != null && (!Files.isRegularFile(state.jarPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(state.jarPath))) {
                throw new IOException("Extension artifact is unavailable: " + state.jarPath);
            }
            Path extensionRoot = root.resolve(state.pluginId).normalize();
            if (!extensionRoot.startsWith(root) || extensionRoot.equals(root)) {
                throw new IOException("Extension storage escaped its owner root: " + state.pluginId);
            }
            if (Files.exists(extensionRoot, LinkOption.NOFOLLOW_LINKS)) {
                requireExtensionRoot(extensionRoot, "extensionRoot");
                state.extension.validatePersistenceRoot(extensionRoot);
                state.extension.healthCheckPersistence();
            }
        }
    }

    private Path requireExtensionRoot(Path root, String field) throws IOException {
        if (root == null) {
            throw new IOException(field + " is required");
        }
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(field + " must be an existing non-symlink directory: " + absolute);
        }
        Files.walkFileTree(absolute, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("Extension persistence contains a symbolic-link directory: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("Extension persistence contains an unsupported file: " + file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new IOException("Extension persistence cannot inspect: " + file, exception);
            }
        });
        return absolute;
    }

    private List<Path> normalizeDirectories(List<Path> directories) {
        if (directories == null) {
            return List.of();
        }
        return directories.stream()
            .filter(Objects::nonNull)
            .map(path -> path.toAbsolutePath().normalize())
            .distinct()
            .toList();
    }

    public boolean replaceNodeDefinitions(NodeDefinitionRegistry stagedDefinitions) {
        Objects.requireNonNull(stagedDefinitions, "Staged node definitions are required");
        persistenceLock.writeLock().lock();
        try {
            requirePersistenceOpen();
            FlowExecutor.AdmissionFence admissionFence = fenceLegacyExecutions();
            try {
                awaitLegacyExecutions(admissionFence);
                LifecycleTransaction transaction = new LifecycleTransaction(admissionFence);
                try {
                    transaction.stageNodeDefinitions(stagedDefinitions);
                    return transaction.commit(true, admissionFence);
                } finally {
                    transaction.close();
                }
            } finally {
                closeAdmissionFence(admissionFence);
            }
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    private void requirePersistenceOpen() {
        requireRecoveryDurability();
        if (persistenceState != PersistenceState.OPEN) {
            throw new IllegalStateException("Extension persistence is quiesced");
        }
        if (pendingModuleTransaction != null) {
            throw new IllegalStateException("Extension module retirement is pending");
        }
        if (pendingLifecycleCompensation != null) {
            throw new IllegalStateException("Extension lifecycle compensation is pending");
        }
        if (startupRecoveryRequired) {
            throw new IllegalStateException("Extension lifecycle recovery is pending");
        }
    }

    private boolean persistenceOpenForLifecycle() {
        return recoveryDurabilityAvailable && pendingModuleTransaction == null
            && pendingLifecycleCompensation == null && !startupRecoveryRequired
            && (persistenceState == PersistenceState.OPEN || shutdownRequested);
    }

    private void requireRecoveryDurability() {
        if (!recoveryDurabilityAvailable) {
            throw new IllegalStateException("Extension lifecycle durability is unavailable: "
                + recoveryDurabilityFailure);
        }
    }

    private List<ExtensionState> orderedExtensions() {
        return activeExtensions().values().stream()
            .sorted((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.pluginId, second.pluginId))
            .toList();
    }

    private ExtensionRegistryActivation registryActivation() {
        ExtensionRegistryActivation current = registryActivation;
        synchronized (moduleContext) {
            synchronized (this) {
                ExtensionRegistryActivation registered = moduleContext.getService(ExtensionRegistryActivation.class);
                if (current != null && registered != null && current != registered) {
                    throw new IllegalStateException("ReSync Extension Registry Activation Service Conflicts With The Manager Authority");
                }
                if (current == null) {
                    if (registered != null) {
                        validateRegistryActivation(registered);
                        current = registered;
                    } else {
                        ExtensionRegistryActivation.State observed = captureRegistryState(null);
                        ExtensionRegistryActivation lateRegistered = moduleContext.getService(ExtensionRegistryActivation.class);
                        if (lateRegistered != null) {
                            validateRegistryActivation(lateRegistered);
                            current = lateRegistered;
                        } else {
                            current = new ExtensionRegistryActivation(observed);
                            moduleContext.registerService(ExtensionRegistryActivation.class, current);
                            ExtensionRegistryActivation published = moduleContext.getService(ExtensionRegistryActivation.class);
                            if (published != current) {
                                throw new IllegalStateException("ReSync Extension Registry Activation Service Registration Conflicted");
                            }
                        }
                    }
                    registryActivation = current;
                } else if (registered == null) {
                    moduleContext.registerService(ExtensionRegistryActivation.class, current);
                    ExtensionRegistryActivation published = moduleContext.getService(ExtensionRegistryActivation.class);
                    if (published != current) {
                        throw new IllegalStateException("ReSync Extension Registry Activation Service Registration Conflicted");
                    }
                }
                FlowEventRegistry flowEvents = moduleContext.getService(FlowEventRegistry.class);
                FlowEventRegistry previousFlowEvents = boundFlowEventRegistry;
                try {
                    if (previousFlowEvents != flowEvents) {
                        if (previousFlowEvents != null) {
                            previousFlowEvents.bindActivation(null);
                        }
                        boundFlowEventRegistry = flowEvents;
                    }
                    current.bind(moduleContext.getService(NodeDefinitionRegistry.class),
                        moduleContext.getService(HandlerRegistry.class),
                        moduleContext.getService(PropertyRegistry.class),
                        moduleContext.getService(OptionCatalogRegistry.class),
                        moduleContext.getService(RuntimeDataRegistry.class),
                        moduleContext.getService(FlowValueCodecRegistry.class),
                        moduleContext.getService(TypeAdapterRegistry.class),
                        moduleContext.getService(FlowGraphValidationRegistry.class),
                        moduleContext.getService(FlowResourceRegistry.class),
                        moduleContext.getService(ReSyncExtensionData.class),
                        flowEvents,
                        moduleContext.getService(FlowRegistry.class));
                } catch (RuntimeException | Error failure) {
                    if (boundFlowEventRegistry == flowEvents) {
                        boundFlowEventRegistry = null;
                    }
                    throw failure;
                }
                return current;
            }
        }
    }

    private void validateRegistryActivation(ExtensionRegistryActivation activation) {
        if (activation.hasProjectionFailure()) {
            throw new IllegalStateException("ReSync Extension Registry Activation Has A Projection Failure",
                activation.projectionFailure());
        }
        ExtensionRegistryActivation.State current = activation.snapshot();
        ExtensionRegistryActivation.State observed = captureRegistryState(current);
        if (!current.semanticallyEquals(observed)) {
            Set<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            keys.addAll(current.fingerprint().keySet());
            keys.addAll(observed.fingerprint().keySet());
            List<String> differences = keys.stream()
                .filter(key -> !Objects.equals(current.fingerprint().get(key), observed.fingerprint().get(key)))
                .toList();
            throw new IllegalStateException("ReSync Extension Registry Activation Was Published With A Different Flow Registry State: "
                + differences);
        }
    }

    private ExtensionRegistryActivation.State captureRegistryState(ExtensionRegistryActivation.State baseline) {
        return ExtensionRegistryActivation.capture(
            baseline != null ? baseline.generation() : 0,
            moduleContext.getService(NodeDefinitionRegistry.class),
            moduleContext.getService(HandlerRegistry.class),
            moduleContext.getService(PropertyRegistry.class),
            moduleContext.getService(OptionCatalogRegistry.class),
            moduleContext.getService(RuntimeDataRegistry.class),
            moduleContext.getService(FlowValueCodecRegistry.class),
            moduleContext.getService(TypeAdapterRegistry.class),
            moduleContext.getService(FlowGraphValidationRegistry.class),
            moduleContext.getService(FlowResourceRegistry.class),
            moduleContext.getService(ReSyncExtensionData.class),
            moduleContext.getService(FlowEventRegistry.class),
            moduleContext.getService(FlowRegistry.class),
            baseline != null ? baseline.extensionLifecycles() : Map.of());
    }

    static NodeDefinition extensionWireDefinition(String pluginId, NodeDefinition definition) {
        if (pluginId == null || pluginId.isBlank() || definition == null) {
            throw new IllegalArgumentException("An extension ID and Flow node definition are required");
        }
        if (definition.getAuthoredMetadata() == null) {
            if (!definition.getId().startsWith(pluginId + ":")) {
                throw new IllegalArgumentException("Flow node id must be namespaced as " + pluginId + ":name");
            }
            definition.assignOwner(pluginId);
            return definition;
        }
        String localId = definition.getId();
        if (!localId.equals(definition.getAuthoredMetadata().id()) || localId.contains(":")) {
            throw new IllegalArgumentException("Strict extension Flow nodes must use a local authored ID");
        }
        definition.assignOwner(pluginId);
        return definition.withId(pluginId + ":" + localId);
    }

    static void requireAvailableExtensionWireId(NodeDefinitionRegistry definitions, String wireId) {
        if (definitions.getAllDefinitions().values().stream().anyMatch(value -> wireId.equals(value.getId()))) {
            throw new IllegalArgumentException("Duplicate extension Flow node wire ID: " + wireId);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void close(URLClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        if (pendingModuleTransaction != null || pendingLifecycleCompensation != null
            || moduleContext.getModuleRegistry().isRuntimeShutdownPending()) {
            retainedLoaders.add(classLoader);
            return;
        }
        try {
            classLoader.close();
        } catch (IOException exception) {
            Log.warn("Failed to close extension classloader: " + exception.getMessage());
        }
    }

    private record RecoveryMarker(
        Set<String> plugins,
        Set<String> providers,
        Set<String> worlds,
        Set<String> modules,
        Set<String> types
    ) {
        private RecoveryMarker {
            plugins = Set.copyOf(plugins);
            providers = Set.copyOf(providers);
            worlds = Set.copyOf(worlds);
            modules = Set.copyOf(modules);
            types = Set.copyOf(types);
        }

        private static RecoveryMarker from(Collection<ExtensionState> states) {
            Set<String> plugins = new HashSet<>();
            Set<String> providers = new HashSet<>();
            Set<String> worlds = new HashSet<>();
            Set<String> modules = new HashSet<>();
            Set<String> types = new HashSet<>();
            for (ExtensionState state : states) {
                plugins.add(state.pluginId);
                providers.addAll(state.customContentProviderIds);
                worlds.addAll(state.worldMapExtensionIds);
                modules.addAll(state.moduleIds);
                types.addAll(state.typeIds);
            }
            return new RecoveryMarker(plugins, providers, worlds, modules, types);
        }

        private String encode() {
            List<String> lines = new ArrayList<>();
            lines.add("version=" + LIFECYCLE_RECOVERY_MARKER_VERSION);
            append(lines, "plugin", plugins);
            append(lines, "provider", providers);
            append(lines, "world", worlds);
            append(lines, "module", modules);
            append(lines, "type", types);
            String payload = String.join("\n", lines) + "\n";
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_LIFECYCLE_RECOVERY_MARKER_BYTES) {
                throw new IllegalArgumentException("Lifecycle recovery marker content is too large");
            }
            return payload;
        }

        private static void append(List<String> lines, String key, Set<String> values) {
            values.stream().sorted().forEach(value -> {
                if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                    || value.indexOf('=') >= 0) {
                    throw new IllegalArgumentException("Lifecycle recovery marker value is invalid");
                }
                lines.add(key + "=" + value);
            });
        }
    }

    private record LifecycleRecoveryInventory(Path markerPath, List<Path> temporaryFiles, List<Path> probeFiles) {
        private LifecycleRecoveryInventory {
            temporaryFiles = List.copyOf(temporaryFiles);
            probeFiles = List.copyOf(probeFiles);
        }

        private boolean hasEvidence() {
            return markerPath != null || !temporaryFiles.isEmpty() || !probeFiles.isEmpty();
        }
    }

    private record LifecycleRecoveryProbeReservation(Path path, UUID identity) {
    }

    private record LifecycleRecoveryProbeSnapshot(
        Path path,
        byte[] content,
        Object fileKey,
        FileTime creationTime,
        FileTime lastModifiedTime,
        long size
    ) {
        private LifecycleRecoveryProbeSnapshot {
            content = content.clone();
        }
    }

    private record LifecycleRecoveryMarkerSnapshot(
        Path path,
        RecoveryMarker marker,
        byte[] content,
        Object fileKey,
        FileTime creationTime,
        FileTime lastModifiedTime,
        long size
    ) {
        private LifecycleRecoveryMarkerSnapshot {
            content = content.clone();
        }
    }

    private static final class LifecycleRecoveryFileLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private LifecycleRecoveryFileLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class LifecycleRecoveryLockSet implements AutoCloseable {
        private final List<LifecycleRecoveryFileLock> locks;

        private LifecycleRecoveryLockSet(List<LifecycleRecoveryFileLock> locks) {
            this.locks = List.copyOf(locks);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int index = locks.size() - 1; index >= 0; index--) {
                try {
                    locks.get(index).close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private final class LifecycleTransaction implements AutoCloseable {
        private final NodeDefinitionRegistry activeNodeDefinitions;
        private final HandlerRegistry activeHandlers;
        private final PropertyRegistry activeProperties;
        private final OptionCatalogRegistry activeOptionCatalogs;
        private final RuntimeDataRegistry activeRuntimeData;
        private final FlowRegistry activeFlowRegistry;
        private final FlowValueCodecRegistry activeValueCodecs;
        private final TypeAdapterRegistry activeTypeAdapters;
        private final FlowGraphValidationRegistry activeValidators;
        private final FlowResourceRegistry activeResources;
        private final ReSyncExtensionData activeExtensionData;
        private final FlowEventRegistry activeFlowEvents;
        private final Map<String, ExtensionState> previousExtensions = new LinkedHashMap<>();
        private final NodeDefinitionRegistry previousNodeDefinitions;
        private final HandlerRegistry previousHandlers;
        private final PropertyRegistry previousProperties;
        private final OptionCatalogRegistry previousOptionCatalogs;
        private final RuntimeDataRegistry previousRuntimeData;
        private final FlowRegistry previousFlowRegistry;
        private final FlowValueCodecRegistry previousValueCodecs;
        private final TypeAdapterRegistry previousTypeAdapters;
        private final FlowGraphValidationRegistry previousValidators;
        private final FlowResourceRegistry previousResources;
        private final ReSyncExtensionData previousExtensionData;
        private final Map<String, FlowDataType> previousExtensionTypes = new LinkedHashMap<>();
        private final NodeDefinitionRegistry nodeDefinitions;
        private final HandlerRegistry handlers;
        private final PropertyRegistry properties;
        private final OptionCatalogRegistry optionCatalogs;
        private final RuntimeDataRegistry runtimeData;
        private final FlowRegistry flowRegistry;
        private final FlowValueCodecRegistry valueCodecs;
        private final TypeAdapterRegistry typeAdapters;
        private final FlowGraphValidationRegistry validators;
        private final FlowResourceRegistry resources;
        private final ReSyncExtensionData extensionData;
        private final FlowEventRegistry flowEvents;
        private final Map<String, ExtensionState> stagedExtensions = new LinkedHashMap<>();
        private final List<ExtensionState> removedStates = new ArrayList<>();
        private final List<ExtensionState> addedStates = new ArrayList<>();
        private final List<FlowModule.RuntimeBindingRetirement> runtimeRetirements = new ArrayList<>();
        private final List<Runnable> externalCompensations = new ArrayList<>();
        private final List<Runnable> candidateLifecycleCompensations = new ArrayList<>();
        private final List<Runnable> retiredLifecycleCompensations = new ArrayList<>();
        private final List<Runnable> failedCompensations = new ArrayList<>();
        private final ExtensionRegistryActivation registryActivation;
        private final ExtensionRegistryActivation.State previousRegistryState;
        private final String previousCatalogPublicationKey;
        private boolean changed;
        private boolean committed;
        private boolean closed;
        private boolean preflightComplete;
        private boolean pendingPublishCatalog;
        private FlowExecutor.AdmissionFence pendingAdmissionFence;
        private final List<Runnable> commitActions = new ArrayList<>();
        private final List<Runnable> closeActions = new ArrayList<>();
        private final Set<String> stoppedModuleIds = new HashSet<>();
        private boolean runtimeActivationAttempted;
        private boolean recoveryMarkerActive;
        private ExtensionRegistryActivation.State stagedRegistryState;
        private ExtensionRegistryActivation.State publishedRegistryState;
        private FlowModule.CatalogRuntimeTransaction catalogRuntimeTransaction;

        private LifecycleTransaction(FlowExecutor.AdmissionFence admissionFence) {
            pendingAdmissionFence = admissionFence;
            registryActivation = registryActivation();
            previousRegistryState = registryActivation.snapshot();
            FlowModule activeFlowModule = moduleContext.getService(FlowModule.class);
            previousCatalogPublicationKey = activeFlowModule != null
                ? activeFlowModule.activeCatalogPublicationKey().orElse(null) : null;
            activeNodeDefinitions = moduleContext.getService(NodeDefinitionRegistry.class);
            activeHandlers = moduleContext.getService(HandlerRegistry.class);
            activeProperties = moduleContext.getService(PropertyRegistry.class);
            activeOptionCatalogs = moduleContext.getService(OptionCatalogRegistry.class);
            activeRuntimeData = moduleContext.getService(RuntimeDataRegistry.class);
            activeFlowRegistry = moduleContext.getService(FlowRegistry.class);
            activeValueCodecs = moduleContext.getService(FlowValueCodecRegistry.class);
            activeTypeAdapters = moduleContext.getService(TypeAdapterRegistry.class);
            activeValidators = moduleContext.getService(FlowGraphValidationRegistry.class);
            activeResources = moduleContext.getService(FlowResourceRegistry.class);
            activeExtensionData = moduleContext.getService(ReSyncExtensionData.class);
            activeFlowEvents = moduleContext.getService(FlowEventRegistry.class);
            previousExtensions.putAll(extensionStates(previousRegistryState));
            previousNodeDefinitions = activeNodeDefinitions != null ? activeNodeDefinitions.copy() : null;
            previousHandlers = activeHandlers != null ? activeHandlers.copy() : null;
            previousProperties = activeProperties != null ? activeProperties.copy() : null;
            previousOptionCatalogs = activeOptionCatalogs != null ? activeOptionCatalogs.copy() : null;
            previousRuntimeData = activeRuntimeData != null ? activeRuntimeData.copy() : null;
            previousFlowRegistry = activeFlowRegistry != null ? activeFlowRegistry.copy() : null;
            previousValueCodecs = activeValueCodecs != null ? activeValueCodecs.copy() : null;
            previousTypeAdapters = activeTypeAdapters != null ? activeTypeAdapters.copy() : null;
            previousValidators = activeValidators != null ? activeValidators.copy() : null;
            previousResources = activeResources != null ? activeResources.copy() : null;
            previousExtensionData = activeExtensionData != null ? activeExtensionData.copy() : null;
            nodeDefinitions = previousNodeDefinitions != null ? previousNodeDefinitions.copy() : new NodeDefinitionRegistry(false);
            handlers = previousHandlers != null ? previousHandlers.copy() : new HandlerRegistry();
            properties = previousProperties != null ? previousProperties.copy() : new PropertyRegistry();
            optionCatalogs = previousOptionCatalogs != null ? previousOptionCatalogs.copy() : new OptionCatalogRegistry();
            runtimeData = optionCatalogs.runtimeData();
            flowRegistry = previousFlowRegistry != null ? previousFlowRegistry.copy() : new FlowRegistry();
            flowRegistry.setHandlerRegistry(handlers);
            valueCodecs = previousValueCodecs != null ? previousValueCodecs.copy() : new FlowValueCodecRegistry();
            typeAdapters = previousTypeAdapters != null ? previousTypeAdapters.copy() : new TypeAdapterRegistry();
            validators = previousValidators != null ? previousValidators.copy() : new FlowGraphValidationRegistry();
            resources = previousResources != null ? previousResources.copy() : new FlowResourceRegistry();
            extensionData = previousExtensionData != null ? previousExtensionData.copy() : new ReSyncExtensionData();
            flowEvents = activeFlowEvents != null ? activeFlowEvents.copyForStaging(typeAdapters) : null;
            stagedExtensions.putAll(previousExtensions);
            Map<String, FlowDataType> registeredTypes = FlowDataType.registeredTypes();
            for (ExtensionState state : previousExtensions.values()) {
                for (String typeId : state.typeIds) {
                    FlowDataType type = registeredTypes.get(typeId.toLowerCase(Locale.ROOT));
                    if (type != null && state.pluginId.equals(type.getOwner())) {
                        previousExtensionTypes.put(typeId.toLowerCase(Locale.ROOT), type);
                    }
                }
            }
        }

        private <T> T service(Class<T> type) {
            Object service = stagedService(type);
            return service != null ? type.cast(service) : moduleContext.getService(type);
        }

        private <T> T requiredService(Class<T> type) {
            T service = service(type);
            if (service == null) {
                throw new IllegalStateException("Missing service: " + type.getName());
            }
            return service;
        }

        private Object stagedService(Class<?> type) {
            if (type == NodeDefinitionRegistry.class) return nodeDefinitions;
            if (type == HandlerRegistry.class) return handlers;
            if (type == PropertyRegistry.class) return properties;
            if (type == OptionCatalogRegistry.class) return optionCatalogs;
            if (type == RuntimeDataRegistry.class) return runtimeData;
            if (type == FlowRegistry.class) return flowRegistry;
            if (type == FlowValueCodecRegistry.class) return valueCodecs;
            if (type == TypeAdapterRegistry.class) return typeAdapters;
            if (type == FlowGraphValidationRegistry.class) return validators;
            if (type == FlowResourceRegistry.class) return resources;
            if (type == ReSyncExtensionData.class) return extensionData;
            if (type == FlowEventRegistry.class) return flowEvents;
            return null;
        }

        private ExtensionState stageAdd(JavaPlugin owner, ReSyncExtension extension, URLClassLoader classLoader, Path jarPath) {
            Objects.requireNonNull(extension, "Extension is required");
            String pluginId = normalizePluginId(extension.getPluginId());
            if (stagedExtensions.containsKey(pluginId)) {
                throw new IllegalArgumentException("Duplicate ReSync extension id: " + pluginId);
            }
            ExtensionState state = new ExtensionState(pluginId, owner, extension, classLoader, jarPath);
            state.lifecycleStorageAccess = true;
            stagedExtensions.put(pluginId, state);
            extensionData.addPlugin(pluginId, extension.getVersion(), extension.getDescription());
            try {
                initializeCandidate(state);
            } catch (RuntimeException | Error exception) {
                stagedExtensions.remove(pluginId, state);
                extensionData.removePlugin(pluginId);
                throw exception;
            }
            rebuildStagedEvents();
            changed = true;
            return state;
        }

        private ExtensionState stageReload(ExtensionState source) {
            if (source == null || stagedExtensions.get(source.pluginId) != source) {
                throw new IllegalArgumentException("Extension is not active in this lifecycle transaction");
            }
            removeStagedState(source);
            source.lifecycleStorageAccess = true;
            removedStates.add(source);
            ExtensionState replacement = new ExtensionState(source.pluginId, source.owner, source.extension, source.classLoader, source.jarPath);
            replacement.lifecycleStorageAccess = true;
            stagedExtensions.put(replacement.pluginId, replacement);
            extensionData.addPlugin(replacement.pluginId, replacement.extension.getVersion(), replacement.extension.getDescription());
            try {
                initializeCandidate(replacement);
            } catch (RuntimeException | Error exception) {
                stagedExtensions.remove(replacement.pluginId, replacement);
                throw exception;
            }
            rebuildStagedEvents();
            changed = true;
            return replacement;
        }

        private void initializeCandidate(ExtensionState state) {
            addedStates.add(state);
            candidateLifecycleCompensations.add(() -> stopCandidate(state));
            initializeExtension(state, new ExtensionContext(state, this), false);
        }

        private boolean stageRemove(ExtensionState state) {
            if (state == null || stagedExtensions.get(state.pluginId) != state) {
                return false;
            }
            removeStagedState(state);
            state.lifecycleStorageAccess = true;
            stagedExtensions.remove(state.pluginId, state);
            rebuildStagedEvents();
            removedStates.add(state);
            changed = true;
            return true;
        }

        private void stageNodeDefinitions(NodeDefinitionRegistry stagedDefinitions) {
            nodeDefinitions.replaceFrom(stagedDefinitions.copy());
            properties.replaceNodeDefinitions(nodeDefinitions.getAllDefinitions().values());
            rebuildStagedEvents();
            changed = true;
        }

        private void rebuildStagedEvents() {
            if (flowEvents != null) {
                flowEvents.replaceDefinitions(new ArrayList<>(nodeDefinitions.getAllDefinitions().values()));
            }
        }

        private boolean stageRuntimeRetirement(ExtensionState state) {
            FlowModule flowModule = moduleContext.getService(FlowModule.class);
            if (flowModule == null) {
                return true;
            }
            FlowModule.RuntimeBindingRetirement retirement = flowModule.stageRuntimeBindingRetirementForOwner(state.pluginId);
            if (retirement.blocked()) {
                retirement.close();
                return false;
            }
            if (!retirement.providers().isEmpty()) {
                runtimeRetirements.add(retirement);
            } else {
                retirement.close();
            }
            return true;
        }

        private boolean preflightCatalogRuntime() {
            FlowModule flowModule = moduleContext.getService(FlowModule.class);
            if (flowModule == null) {
                return true;
            }
            Set<ContractRef<ProviderId>> retirements = runtimeRetirements.stream()
                .flatMap(value -> value.providers().stream())
                .collect(Collectors.toSet());
            catalogRuntimeTransaction = flowModule.prepareCatalogRuntimeTransaction(
                nodeDefinitions, handlers, extensionData, retirements);
            return !catalogRuntimeTransaction.blocked();
        }

        private void removeStagedState(ExtensionState state) {
            extensionData.removePlugin(state.pluginId);
            nodeDefinitions.unregisterPlugin(state.pluginId);
            for (String nodeId : state.nodeIds) {
                flowRegistry.unregisterWithoutShutdown(nodeId);
            }
            for (String handlerId : state.handlerIds) {
                handlers.unregisterWithoutShutdown(handlerId);
            }
            for (PropertyRegistration property : state.propertyIds) {
                properties.unregister(property.family(), property.property());
            }
            for (String sourceId : state.optionCatalogIds) {
                optionCatalogs.unregister(sourceId);
            }
            for (String adapterId : state.runtimeDataAdapterIds) {
                runtimeData.unregister(adapterId);
            }
            for (String typeId : state.typeIds) {
                valueCodecs.unregister(typeId);
            }
            for (ConversionRegistration conversion : state.conversions) {
                typeAdapters.unregister(conversion.source(), conversion.target());
            }
            for (String resourceTypeId : state.resourceTypeIds) {
                resources.unregister(state.pluginId, resourceTypeId);
            }
            validators.unregisterOwner(state.pluginId);
        }

        private boolean hasCustomContentProvider(String providerId) {
            if (providerId == null) {
                return false;
            }
            return stagedExtensions.values().stream().anyMatch(state -> state.customContentProviderIds.stream()
                .anyMatch(id -> id.equalsIgnoreCase(providerId)));
        }

        private boolean replacesCustomContentProvider(String providerId) {
            return removedStates.stream().anyMatch(state -> state.customContentProviderIds.stream()
                .anyMatch(id -> id.equalsIgnoreCase(providerId)));
        }

        private void stageCustomContentProvider(ExtensionState state, CustomContentProvider provider) {
            if (hasCustomContentProvider(provider.getId())) {
                throw new IllegalArgumentException("Duplicate custom content provider id: " + provider.getId());
            }
            state.customContentProviderIds.add(provider.getId());
            state.customContentProviders.put(provider.getId(), provider);
        }

        private void stageWorldMapExtension(ExtensionState state, WorldMapExtension extension) {
            boolean duplicate = stagedExtensions.values().stream().anyMatch(value -> value.worldMapExtensionIds.stream()
                .anyMatch(id -> id.equalsIgnoreCase(extension.getExtensionId())));
            if (duplicate) {
                throw new IllegalArgumentException("Duplicate world map extension id: " + extension.getExtensionId());
            }
            state.worldMapExtensionIds.add(extension.getExtensionId());
            state.worldMapExtensions.put(extension.getExtensionId(), extension);
        }

        private boolean replacesWorldMapExtension(String extensionId) {
            return removedStates.stream().anyMatch(state -> state.worldMapExtensionIds.stream()
                .anyMatch(id -> id.equalsIgnoreCase(extensionId)));
        }

        private void stageModule(ExtensionState state, Module module) {
            if (stagedExtensions.values().stream().anyMatch(value -> value.moduleIds.contains(module.getModuleId()))) {
                throw new IllegalArgumentException("Duplicate module id: " + module.getModuleId());
            }
            if (moduleContext.getModuleRegistry().hasModule(module.getModuleId()) && !replacesModule(module.getModuleId())) {
                throw new IllegalArgumentException("Duplicate module id: " + module.getModuleId());
            }
            state.moduleIds.add(module.getModuleId());
            state.modules.put(module.getModuleId(), module);
        }

        private boolean replacesModule(String moduleId) {
            return removedStates.stream().anyMatch(state -> state.moduleIds.contains(moduleId));
        }

        private FlowDataType resolveType(String typeId) {
            if (typeId == null || typeId.isBlank()) {
                return FlowDataType.ANY;
            }
            String normalized = typeId.toLowerCase(Locale.ROOT);
            for (ExtensionState state : stagedExtensions.values()) {
                TypeRegistration registration = state.typeRegistrations.get(normalized);
                if (registration != null) {
                    return registration.type();
                }
            }
            return FlowDataType.registeredTypes().get(normalized);
        }

        private void activateTypes() {
            Map<String, FlowDataType> registered = FlowDataType.registeredTypes();
            Set<String> retired = removedStates.stream().flatMap(state -> state.typeIds.stream())
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            for (ExtensionState state : addedStates) {
                for (TypeRegistration registration : state.typeRegistrations.values()) {
                    String typeId = registration.type().getId().toLowerCase(Locale.ROOT);
                    FlowDataType existing = registered.get(typeId);
                    if (existing != null && !retired.contains(typeId)) {
                        throw new IllegalStateException("Flow type already active: " + typeId);
                    }
                }
            }
            for (ExtensionState state : removedStates) {
                for (String typeId : state.typeIds) {
                    FlowDataType.unregisterExtensionType(state.pluginId, typeId);
                }
            }
            for (ExtensionState state : addedStates) {
                for (TypeRegistration registration : state.typeRegistrations.values()) {
                    FlowDataType.registerExtensionType(state.pluginId, registration.type());
                }
            }
        }

        private void applyExternalRemovals() {
            for (ExtensionState state : removedStates) {
                removeExternal(state);
                lifecycleFailureInjector.after(LifecyclePhase.EXTERNAL_REMOVE);
            }
        }

        private void applyExternalAdditions() {
            for (ExtensionState state : addedStates) {
                addExternal(state);
                lifecycleFailureInjector.after(LifecyclePhase.EXTERNAL_ADD);
            }
        }

        private void removeExternal(ExtensionState state) {
            CustomContentService customContent = moduleContext.getService(CustomContentService.class);
            if (customContent != null) {
                for (String providerId : state.customContentProviderIds) {
                    CustomContentProvider provider = state.customContentProviders.get(providerId);
                    if (provider == null) {
                        throw new IllegalStateException("Missing custom content provider compensation: " + providerId);
                    }
                    if (customContent.hasProvider(providerId)) {
                        externalCompensations.add(() -> customContent.registerProvider(provider));
                        customContent.unregisterProvider(providerId);
                    }
                }
            }
            WorldMapService worldMap = moduleContext.getService(WorldMapService.class);
            if (worldMap != null) {
                for (String extensionId : state.worldMapExtensionIds) {
                    WorldMapExtension extension = state.worldMapExtensions.get(extensionId);
                    if (extension == null) {
                        throw new IllegalStateException("Missing world map extension compensation: " + extensionId);
                    }
                    boolean present = worldMap.getExtensions().stream()
                        .anyMatch(value -> extensionId.equals(value.getExtensionId()));
                    if (present) {
                        externalCompensations.add(() -> worldMap.registerExtension(extension));
                        worldMap.unregisterExtension(extensionId);
                    }
                }
            }
            for (Listener listener : state.listeners) {
                externalCompensations.add(() -> Bukkit.getPluginManager().registerEvents(listener,
                    state.owner != null ? state.owner : moduleContext.getPlugin()));
                HandlerList.unregisterAll(listener);
            }
            for (String moduleId : state.moduleIds) {
                Module module = state.modules.get(moduleId);
                if (module == null) {
                    throw new IllegalStateException("Missing runtime module compensation: " + moduleId);
                }
                if (moduleContext.getModuleRegistry().hasModule(moduleId)) {
                    if (stoppedModuleIds.add(moduleId)) {
                        externalCompensations.add(() -> moduleContext.getModuleRegistry().registerRuntimeModule(module, moduleContext));
                    }
                    moduleContext.getModuleRegistry().unregisterRuntimeModule(moduleId, moduleContext);
                }
            }
        }

        private void addExternal(ExtensionState state) {
            CustomContentService customContent = moduleContext.getService(CustomContentService.class);
            if (customContent != null) {
                for (CustomContentProvider provider : state.customContentProviders.values()) {
                    if (customContent.hasProvider(provider.getId())) {
                        throw new IllegalStateException("Custom content provider is already registered: " + provider.getId());
                    }
                    externalCompensations.add(() -> customContent.unregisterProvider(provider.getId()));
                    customContent.registerProvider(provider);
                }
            }
            WorldMapService worldMap = moduleContext.getService(WorldMapService.class);
            if (worldMap != null) {
                for (WorldMapExtension extension : state.worldMapExtensions.values()) {
                    boolean present = worldMap.getExtensions().stream()
                        .anyMatch(value -> extension.getExtensionId().equals(value.getExtensionId()));
                    if (present) {
                        throw new IllegalStateException("World map extension is already registered: " + extension.getExtensionId());
                    }
                    externalCompensations.add(() -> worldMap.unregisterExtension(extension.getExtensionId()));
                    worldMap.registerExtension(extension);
                }
            }
            for (Module module : state.modules.values()) {
                if (moduleContext.getModuleRegistry().hasModule(module.getModuleId())) {
                    throw new IllegalStateException("Runtime module is already registered: " + module.getModuleId());
                }
                externalCompensations.add(() -> moduleContext.getModuleRegistry().unregisterRuntimeModule(module.getModuleId(), moduleContext));
                moduleContext.getModuleRegistry().registerRuntimeModule(module, moduleContext);
            }
            for (Listener listener : state.listeners) {
                externalCompensations.add(() -> HandlerList.unregisterAll(listener));
                Bukkit.getPluginManager().registerEvents(listener, state.owner != null ? state.owner : moduleContext.getPlugin());
            }
        }

        private void compensateExternal() {
            RuntimeException failure = null;
            for (int index = externalCompensations.size() - 1; index >= 0; index--) {
                Runnable compensation = externalCompensations.get(index);
                try {
                    lifecycleFailureInjector.after(LifecyclePhase.COMPENSATION);
                    compensation.run();
                } catch (RuntimeException | Error exception) {
                    for (int remaining = index; remaining >= 0; remaining--) {
                        failedCompensations.add(externalCompensations.get(remaining));
                    }
                    failure = new IllegalStateException("ReSync extension external lifecycle compensation failed");
                    failure.addSuppressed(exception);
                    break;
                }
            }
            externalCompensations.clear();
            if (failure != null) {
                throw failure;
            }
        }

        private boolean commit(boolean publishCatalog) {
            return commit(publishCatalog, null);
        }

        private boolean commit(boolean publishCatalog, FlowExecutor.AdmissionFence admissionFence) {
            if (!changed) {
                committed = true;
                return true;
            }
            LifecycleRecoveryLockSet acquired = null;
            try {
                acquired = acquireLifecycleRecoveryLocks();
                return commitWithLifecycleRecoveryLock(publishCatalog, admissionFence);
            } catch (IOException | RuntimeException exception) {
                startupRecoveryRequired = true;
                throw new IllegalStateException("ReSync extension lifecycle recovery lock is unavailable", exception);
            } finally {
                releaseLifecycleRecoveryLocks(acquired);
            }
        }

        private boolean commitWithLifecycleRecoveryLock(boolean publishCatalog,
                                                        FlowExecutor.AdmissionFence admissionFence) {
            Map<String, NodeHandler> previousHandlerValues = activeHandlers != null ? activeHandlers.snapshot() : Map.of();
            try {
                if (!preflightComplete) {
                    if (!preflightCatalogRuntime()) {
                        return false;
                    }
                    recoveryMarkerActive = true;
                    List<ExtensionState> markerStates = new ArrayList<>(removedStates);
                    markerStates.addAll(addedStates);
                    writeLifecycleRecoveryMarker(markerStates);
                    preflightComplete = true;
                }
                if (!drainRemovedModules()) {
                    pendingModuleTransaction = this;
                    pendingPublishCatalog = publishCatalog;
                    pendingAdmissionFence = admissionFence;
                    retainAdmissionFence(admissionFence);
                    return false;
                }
                if (pendingModuleTransaction == this) {
                    pendingModuleTransaction = null;
                }
                for (ExtensionState state : addedStates) {
                    state.extension.start();
                    lifecycleFailureInjector.after(LifecyclePhase.NEW_START);
                }
                if (publishCatalog && catalogRuntimeTransaction != null) {
                    runtimeActivationAttempted = true;
                    if (!catalogRuntimeTransaction.commitCore()) {
                        throw new IllegalStateException("Flow catalog/runtime transaction rejected extension lifecycle batch");
                    }
                    lifecycleFailureInjector.after(LifecyclePhase.CORE_COMMIT);
                }
                for (ExtensionState state : removedStates) {
                    retiredLifecycleCompensations.add(() -> startRetired(state));
                    state.extension.stop();
                    lifecycleFailureInjector.after(LifecyclePhase.OLD_STOP);
                }
                activateTypes();
                applyExternalRemovals();
                applyExternalAdditions();
                stageRegistryCandidate();
                lifecycleFailureInjector.after(LifecyclePhase.MAP_SWAP);
                publishRegistryCandidate();
                if (publishCatalog && catalogRuntimeTransaction != null) {
                    catalogRuntimeTransaction.finalizePublication();
                }
                shutdownReplacedHandlers(previousHandlerValues, handlers.snapshot());
                if (!clearLifecycleRecoveryMarker()) {
                    throw new IllegalStateException("ReSync extension lifecycle recovery marker could not be cleared");
                }
                recoveryMarkerActive = false;
                externalCompensations.clear();
                candidateLifecycleCompensations.clear();
                retiredLifecycleCompensations.clear();
                committed = true;
                commitActions.forEach(Runnable::run);
                commitActions.clear();
                releaseAdmissionFence(admissionFence);
                return true;
            } catch (RuntimeException | Error exception) {
                if (pendingModuleTransaction == this) {
                    pendingModuleTransaction = null;
                }
                RuntimeException compensationFailure = null;
                boolean catalogRuntimeRestorePending = false;
                boolean recoveryMarkerPending = recoveryMarkerActive;
                boolean activeStateRestored = true;
                try {
                    rollbackLifecycle();
                } catch (RuntimeException | Error failure) {
                    compensationFailure = aggregateCompensationFailure(compensationFailure, failure);
                }
                try {
                    restoreActiveState(previousHandlerValues);
                } catch (RuntimeException | Error failure) {
                    activeStateRestored = false;
                    compensationFailure = aggregateCompensationFailure(compensationFailure, failure);
                }
                if (activeStateRestored) {
                    try {
                        restoreRetiredLifecycle();
                    } catch (RuntimeException | Error failure) {
                        compensationFailure = aggregateCompensationFailure(compensationFailure, failure);
                    }
                } else {
                    failedCompensations.addAll(retiredLifecycleCompensations);
                    retiredLifecycleCompensations.clear();
                }
                if (runtimeActivationAttempted) {
                    try {
                        catalogRuntimeRestorePending = !restoreCatalogRuntime();
                    } catch (RuntimeException | Error failure) {
                        compensationFailure = aggregateCompensationFailure(compensationFailure, failure);
                        catalogRuntimeRestorePending = true;
                    }
                }
                if (registryActivation.hasProjectionFailure()) {
                    compensationFailure = aggregateCompensationFailure(compensationFailure,
                        new IllegalStateException("Registry projection rollback failed"));
                }
                if (recoveryMarkerPending && compensationFailure == null
                    && !catalogRuntimeRestorePending && failedCompensations.isEmpty()) {
                    recoveryMarkerPending = !clearLifecycleRecoveryMarker();
                    if (!recoveryMarkerPending) {
                        recoveryMarkerActive = false;
                    }
                }
                if (catalogRuntimeRestorePending || recoveryMarkerPending) {
                    pendingLifecycleCompensation = new PendingLifecycleCompensation(admissionFence,
                        failedCompensations, catalogRuntimeRestorePending, recoveryMarkerPending,
                        previousCatalogPublicationKey, catalogRuntimeTransaction);
                } else if (!failedCompensations.isEmpty()) {
                    pendingLifecycleCompensation = new PendingLifecycleCompensation(admissionFence,
                        failedCompensations, false, false, previousCatalogPublicationKey, catalogRuntimeTransaction);
                }
                if (compensationFailure != null || catalogRuntimeRestorePending || !failedCompensations.isEmpty()
                    || recoveryMarkerPending) {
                    if (compensationFailure != null) {
                        exception.addSuppressed(compensationFailure);
                        Log.error("ReSync extension lifecycle compensation failed; runtime remains fenced: "
                            + compensationFailure.getMessage(), compensationFailure);
                    }
                    lifecycleDegraded = true;
                    retainAdmissionFence(admissionFence);
                }
                if (pendingLifecycleCompensation == null && !lifecycleDegraded) {
                    releaseAdmissionFence(admissionFence);
                }
                Log.warn("ReSync extension lifecycle batch was rejected: " + exception.getMessage());
                return false;
            }
        }

        private boolean drainRemovedModules() {
            Set<String> retiring = removedStates.stream().flatMap(state -> state.moduleIds.stream()).collect(Collectors.toSet());
            if (retiring.isEmpty()) {
                return true;
            }
            List<Module> order = new ArrayList<>(moduleContext.getModuleRegistry().getModules());
            Collections.reverse(order);
            for (Module module : order) {
                if (retiring.contains(module.getModuleId()) && stoppedModuleIds.add(module.getModuleId())) {
                    externalCompensations.add(() -> moduleContext.getModuleRegistry().registerRuntimeModule(module, moduleContext));
                }
            }
            return moduleContext.getModuleRegistry().drainRuntimeModules(retiring, moduleContext);
        }

        private RuntimeException aggregateCompensationFailure(RuntimeException current, Throwable failure) {
            RuntimeException aggregate = current;
            if (aggregate == null) {
                aggregate = new IllegalStateException("ReSync extension lifecycle compensation failed");
            }
            if (failure != aggregate) {
                aggregate.addSuppressed(failure);
            }
            return aggregate;
        }

        private void stopCandidate(ExtensionState state) {
            boolean previousAccess = state.lifecycleStorageAccess;
            state.lifecycleStorageAccess = true;
            try {
                state.extension.stop();
            } finally {
                state.lifecycleStorageAccess = previousAccess;
            }
        }

        private void startRetired(ExtensionState state) {
            ExtensionRegistryActivation.State current = registryActivation.snapshot();
            if (current != previousRegistryState || extensionStates(current).get(state.pluginId) != state) {
                throw new IllegalStateException("Retired extension state is not restored");
            }
            for (Module module : state.modules.values()) {
                if (moduleContext.getModuleRegistry().getModule(module.getModuleId()) != module
                    || moduleContext.getModuleRegistry().getModules().stream().noneMatch(started -> started == module)) {
                    throw new IllegalStateException("Retired extension module restoration is pending: " + module.getModuleId());
                }
            }
            state.extension.start();
        }

        private void stageRegistryCandidate() {
            stagedRegistryState = ExtensionRegistryActivation.capture(
                previousRegistryState.generation() + 1,
                nodeDefinitions,
                handlers,
                properties,
                optionCatalogs,
                runtimeData,
                valueCodecs,
                typeAdapters,
                validators,
                resources,
                extensionData,
                flowEvents,
                flowRegistry,
                stagedExtensions);
        }

        private void publishRegistryCandidate() {
            ExtensionRegistryActivation.State candidate = Objects.requireNonNull(stagedRegistryState,
                "Staged extension registry state is required");
            boolean force = !removedStates.isEmpty() || !addedStates.isEmpty();
            if (!force && previousRegistryState.semanticallyEquals(candidate)) {
                return;
            }
            publishedRegistryState = candidate;
            if (!registryActivation.compareAndSet(previousRegistryState, candidate, force)) {
                throw new IllegalStateException("Extension registry activation changed during lifecycle staging");
            }
        }

        private void rollbackLifecycle() {
            RuntimeException failure = null;
            boolean deferCandidates = Bukkit.getServer() != null && !Bukkit.isPrimaryThread();
            for (int index = candidateLifecycleCompensations.size() - 1; index >= 0; index--) {
                Runnable compensation = candidateLifecycleCompensations.get(index);
                if (deferCandidates) {
                    failedCompensations.add(compensation);
                    continue;
                }
                try {
                    lifecycleFailureInjector.after(LifecyclePhase.COMPENSATION);
                    compensation.run();
                } catch (RuntimeException | Error exception) {
                    failedCompensations.add(compensation);
                    if (failure == null) {
                        failure = new IllegalStateException("ReSync extension lifecycle compensation failed");
                    }
                    failure.addSuppressed(exception);
                }
            }
            candidateLifecycleCompensations.clear();
            try {
                compensateExternal();
            } catch (RuntimeException compensationFailure) {
                if (failure == null) {
                    failure = compensationFailure;
                } else {
                    failure.addSuppressed(compensationFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private void restoreRetiredLifecycle() {
            RuntimeException failure = null;
            for (int index = retiredLifecycleCompensations.size() - 1; index >= 0; index--) {
                Runnable compensation = retiredLifecycleCompensations.get(index);
                try {
                    lifecycleFailureInjector.after(LifecyclePhase.COMPENSATION);
                    compensation.run();
                } catch (RuntimeException | Error exception) {
                    failedCompensations.add(compensation);
                    if (failure == null) {
                        failure = new IllegalStateException("ReSync extension lifecycle compensation failed");
                    }
                    failure.addSuppressed(exception);
                }
            }
            retiredLifecycleCompensations.clear();
            if (failure != null) {
                throw failure;
            }
        }

        private void restoreActiveState(Map<String, NodeHandler> previousHandlerValues) {
            if (publishedRegistryState != null) {
                ExtensionRegistryActivation.State current = registryActivation.snapshot();
                if (current == publishedRegistryState) {
                    if (!registryActivation.compareAndSet(publishedRegistryState, previousRegistryState, true)) {
                        Log.error("ReSync extension registry activation could not roll back after lifecycle failure");
                        throw new IllegalStateException("ReSync extension registry activation could not roll back after lifecycle failure");
                    }
                } else if (current != previousRegistryState) {
                    Log.error("ReSync extension registry activation changed during lifecycle rollback");
                    throw new IllegalStateException("ReSync extension registry activation changed during lifecycle rollback");
                }
                shutdownCandidateHandlers(previousHandlerValues, publishedRegistryState.handlers().snapshot());
                publishedRegistryState = null;
            }
            restoreExtensionTypes();
        }

        private boolean restoreCatalogRuntime() {
            FlowModule flowModule = moduleContext.getService(FlowModule.class);
            lifecycleFailureInjector.after(LifecyclePhase.ROLLBACK_REFRESH);
            if (flowModule == null) {
                return true;
            }
            if (flowModule.catalogRuntimeCleanupPending() && !flowModule.retryPendingCatalogRuntimeCleanup()) {
                return false;
            }
            if (catalogRuntimeTransaction != null) {
                if (!catalogRuntimeTransaction.rollback()) {
                    return false;
                }
            } else if (!flowModule.refreshCatalog()) {
                return false;
            }
            return flowModule.restoreCatalogPublicationKey(previousCatalogPublicationKey);
        }

        private void restoreExtensionTypes() {
            Map<String, FlowDataType> current = FlowDataType.registeredTypes();
            for (ExtensionState state : addedStates) {
                for (String typeId : state.typeIds) {
                    FlowDataType type = current.get(typeId.toLowerCase(Locale.ROOT));
                    if (type != null && state.pluginId.equals(type.getOwner())) {
                        FlowDataType.unregisterExtensionType(state.pluginId, typeId);
                    }
                }
            }
            for (Map.Entry<String, FlowDataType> entry : previousExtensionTypes.entrySet()) {
                if (!FlowDataType.registeredTypes().containsKey(entry.getKey())) {
                    FlowDataType.registerExtensionType(entry.getValue().getOwner(), entry.getValue());
                }
            }
        }

        private void shutdownReplacedHandlers(Map<String, NodeHandler> previousValues, Map<String, NodeHandler> currentValues) {
            if (activeHandlers == null) {
                return;
            }
            Set<NodeHandler> retired = Collections.newSetFromMap(new IdentityHashMap<>());
            previousValues.forEach((id, handler) -> {
                if (currentValues.get(id) != handler) {
                    retired.add(handler);
                }
            });
            try {
                activeHandlers.shutdown(retired);
            } catch (RuntimeException exception) {
                Log.warn("ReSync Flow handler shutdown reported a failure: " + exception.getMessage());
            }
        }

        private void shutdownCandidateHandlers(Map<String, NodeHandler> previousValues, Map<String, NodeHandler> candidateValues) {
            if (activeHandlers == null) {
                return;
            }
            Set<NodeHandler> retired = Collections.newSetFromMap(new IdentityHashMap<>());
            candidateValues.forEach((id, handler) -> {
                if (previousValues.get(id) != handler) {
                    retired.add(handler);
                }
            });
            activeHandlers.shutdown(retired);
        }

        @Override
        public void close() {
            if (closed || pendingModuleTransaction == this) {
                return;
            }
            if (pendingLifecycleCompensation != null || moduleContext.getModuleRegistry().isRuntimeShutdownPending()) {
                if (!committed && !candidateLifecycleCompensations.isEmpty()) {
                    retainAdmissionFence(pendingAdmissionFence);
                }
                retainedTransactions.add(this);
                return;
            }
            if (!committed && !candidateLifecycleCompensations.isEmpty()) {
                List<Runnable> actions = new ArrayList<>(candidateLifecycleCompensations);
                Collections.reverse(actions);
                candidateLifecycleCompensations.clear();
                pendingLifecycleCompensation = new PendingLifecycleCompensation(pendingAdmissionFence,
                    actions, false, false, previousCatalogPublicationKey, catalogRuntimeTransaction);
                lifecycleDegraded = true;
                retainAdmissionFence(pendingAdmissionFence);
                retainedTransactions.add(this);
                retryPendingLifecycleCompensation();
                if (pendingLifecycleCompensation != null) {
                    return;
                }
                retainedTransactions.remove(this);
            }
            closed = true;
            if (catalogRuntimeTransaction != null) {
                catalogRuntimeTransaction.close();
            }
            for (FlowModule.RuntimeBindingRetirement retirement : runtimeRetirements) {
                retirement.close();
            }
            if (!committed) {
                for (ExtensionState state : addedStates) {
                    state.clearOwnedIds();
                }
            }
            addedStates.forEach(state -> state.lifecycleStorageAccess = false);
            removedStates.forEach(state -> state.lifecycleStorageAccess = false);
            closeActions.forEach(Runnable::run);
            closeActions.clear();
            releaseAdmissionFence(pendingAdmissionFence);
        }
    }

    private final class ExtensionContext implements ReSyncExtensionContext {
        private final ExtensionState state;
        private final LifecycleTransaction transaction;

        private ExtensionContext(ExtensionState state) {
            this(state, null);
        }

        private ExtensionContext(ExtensionState state, LifecycleTransaction transaction) {
            this.state = state;
            this.transaction = transaction;
        }

        @Override
        public String pluginId() {
            return state.pluginId;
        }

        @Override
        public JavaPlugin owner() {
            return state.owner != null ? state.owner : moduleContext.getPlugin();
        }

        @Override
        public FlowRegistration flow() {
            return new ExtensionFlowRegistration(state, transaction);
        }

        @Override
        public ModuleRegistration modules() {
            return new ExtensionModuleRegistration(state, transaction);
        }

        @Override
        public CustomContentRegistration customContent() {
            return provider -> {
                if (provider == null) {
                    return;
                }
                validateNamespaced(state.pluginId, provider.getId(), "Custom content provider");
                CustomContentService service = moduleContext.getService(CustomContentService.class);
                if (service != null && service.hasProvider(provider.getId()) && transaction == null) {
                    throw new IllegalArgumentException("Duplicate custom content provider id: " + provider.getId());
                }
                if (transaction != null && transaction.hasCustomContentProvider(provider.getId())) {
                    throw new IllegalArgumentException("Duplicate custom content provider id: " + provider.getId());
                }
                if (transaction != null && service != null && service.hasProvider(provider.getId())
                    && !transaction.replacesCustomContentProvider(provider.getId())) {
                    throw new IllegalArgumentException("Duplicate custom content provider id: " + provider.getId());
                }
                if (transaction != null) {
                    transaction.stageCustomContentProvider(state, provider);
                } else if (service != null) {
                    service.registerProvider(provider);
                    state.customContentProviderIds.add(provider.getId());
                    state.customContentProviders.put(provider.getId(), provider);
                }
            };
        }

        @Override
        public WorldMapRegistration worldMap() {
            return extension -> {
                if (extension == null) {
                    return;
                }
                validateNamespaced(state.pluginId, extension.getExtensionId(), "World map extension");
                WorldMapService service = moduleContext.getService(WorldMapService.class);
                boolean exists = service != null && service.getExtensions().stream()
                    .anyMatch(existing -> extension.getExtensionId().equals(existing.getExtensionId()));
                if (exists && (transaction == null || !transaction.replacesWorldMapExtension(extension.getExtensionId()))) {
                    throw new IllegalArgumentException("Duplicate world map extension id: " + extension.getExtensionId());
                }
                if (transaction != null) {
                    transaction.stageWorldMapExtension(state, extension);
                } else if (service != null) {
                    service.registerExtension(extension);
                    state.worldMapExtensionIds.add(extension.getExtensionId());
                    state.worldMapExtensions.put(extension.getExtensionId(), extension);
                }
            };
        }

        @Override
        public OptionCatalogRegistration optionCatalogs() {
            return provider -> {
                if (provider == null) {
                    return;
                }
                OptionCatalogRegistry registry = transaction != null ? transaction.optionCatalogs : moduleContext.getRequiredService(OptionCatalogRegistry.class);
                validateNamespaced(state.pluginId, provider.sourceId(), "Option catalog");
                if (registry.provider(provider.sourceId()) != null) {
                    throw new IllegalArgumentException("Duplicate option catalog id: " + provider.sourceId());
                }
                if (!registry.register(provider)) {
                    throw new IllegalArgumentException("Duplicate option catalog id: " + provider.sourceId());
                }
                state.optionCatalogIds.add(provider.sourceId());
                extensionData()
                    .addOptionSource(state.pluginId, new FlowOptionSourceMetadata(provider.sourceId(), provider.providerId(), provider.widgetType(), provider.searchable(), "", "string",
                        provider.contextKeys() != null ? provider.contextKeys().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList() : List.of()));
            };
        }

        @Override
        public RuntimeDataRegistration runtimeData() {
            return adapter -> {
                if (adapter == null) {
                    return;
                }
                validateNamespaced(state.pluginId, adapter.id(), "Runtime data adapter");
                RuntimeDataRegistry registry = transaction != null ? transaction.runtimeData : moduleContext.getRequiredService(RuntimeDataRegistry.class);
                if (!registry.register(adapter)) {
                    throw new IllegalArgumentException("Duplicate runtime data adapter id: " + adapter.id());
                }
                state.runtimeDataAdapterIds.add(adapter.id());
            };
        }

        @Override
        public EventRegistration events() {
            return listener -> {
                if (listener == null) {
                    return;
                }
                state.listeners.add(listener);
                if (transaction == null) {
                    Bukkit.getPluginManager().registerEvents(listener, owner());
                }
            };
        }

        @Override
        public ExtensionStorage storage() {
            persistenceLock.readLock().lock();
            try {
                requirePersistenceOpen();
                try {
                    extensionStorageRoot(state);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to create extension storage for " + state.pluginId, exception);
                }
            } finally {
                persistenceLock.readLock().unlock();
            }
            return new ExtensionStorage() {
                @Override
                public <T> T read(StorageOperation<T> operation) throws IOException {
                    return accessStorage(state, false, operation);
                }

                @Override
                public <T> T write(StorageOperation<T> operation) throws IOException {
                    return accessStorage(state, true, operation);
                }
            };
        }

        @Override
        public <T> T service(Class<T> type) {
            return transaction != null ? transaction.service(type) : moduleContext.getService(type);
        }

        @Override
        public <T> T requiredService(Class<T> type) {
            return transaction != null ? transaction.requiredService(type) : moduleContext.getRequiredService(type);
        }

        private ReSyncExtensionData extensionData() {
            return transaction != null ? transaction.extensionData : moduleContext.getRequiredService(ReSyncExtensionData.class);
        }
    }

    private final class ExtensionFlowRegistration implements ReSyncExtensionContext.FlowRegistration {
        private final ExtensionState state;
        private final LifecycleTransaction transaction;

        private ExtensionFlowRegistration(ExtensionState state, LifecycleTransaction transaction) {
            this.state = state;
            this.transaction = transaction;
        }

        @Override
        public FlowRegistry runtimeRegistry() {
            return transaction != null ? transaction.flowRegistry : moduleContext.getRequiredService(FlowRegistry.class);
        }

        @Override
        public NodeDefinitionRegistry nodeDefinitions() {
            return transaction != null ? transaction.nodeDefinitions : moduleContext.getRequiredService(NodeDefinitionRegistry.class);
        }

        @Override
        public HandlerRegistry handlers() {
            return transaction != null ? transaction.handlers : moduleContext.getRequiredService(HandlerRegistry.class);
        }

        @Override
        public PropertyRegistry properties() {
            return transaction != null ? transaction.properties : moduleContext.getRequiredService(PropertyRegistry.class);
        }

        @Override
        public void registerNode(NodeDefinition definition) {
            if (definition == null) {
                return;
            }
            String localId = definition.getId();
            NodeDefinition wireDefinition = extensionWireDefinition(state.pluginId, definition);
            requireAvailableExtensionWireId(nodeDefinitions(), wireDefinition.getId());
            nodeDefinitions().register(state.pluginId, wireDefinition);
            state.nodeIds.add(wireDefinition.getId());
            state.nodeWireIds.put(localId, wireDefinition.getId());
            if (wireDefinition.isTrigger()) {
                state.eventIds.add(wireDefinition.getId());
            }
        }

        @Override
        public void registerNodes(String resourcePath) {
            ClassLoader classLoader = state.classLoader != null ? state.classLoader : state.extension.getClass().getClassLoader();
            NodeDefinitionLoader loader = new NodeDefinitionLoader();
            List<NodeDefinition> definitions = loader.loadReplacementFromClassLoader(classLoader, resourcePath);
            for (NodeDefinition definition : definitions) {
                registerNode(definition);
            }
        }

        @Override
        public void registerHandler(String handlerId, NodeHandler handler) {
            validateNamespaced(state.pluginId, handlerId, "Handler");
            handlers().register(handlerId, handler);
            state.handlerIds.add(handlerId);
        }

        @Override
        public void registerProperty(String family, String property, PropertyHandler handler) {
            validateNamespaced(state.pluginId, property, "Property");
            properties().register(family, property, handler);
            state.propertyIds.add(new PropertyRegistration(family, property));
        }

        @Override
        public void registerType(FlowTypeMetadata metadata) {
            validateNamespaced(state.pluginId, metadata != null ? metadata.getId() : null, "Flow type");
            metadata.setAvailable(false);
            metadata.setUnavailableReason("No executable runtime type is registered");
            extensionData().addType(state.pluginId, metadata);
        }

        @Override
        public void registerType(FlowDataType type, FlowTypeMetadata metadata) {
            registerType(type, null, metadata);
        }

        @Override
        public void registerType(FlowDataType type, FlowValueCodec<?> codec, FlowTypeMetadata metadata) {
            String typeId = type != null ? type.getId() : null;
            validateNamespaced(state.pluginId, typeId, "Flow type");
            if (metadata == null || metadata.getId() == null || !typeId.equalsIgnoreCase(metadata.getId())) {
                throw new IllegalArgumentException("Flow type metadata must match runtime type " + typeId);
            }
            if (transaction != null) {
                registerStagedType(type, codec, metadata);
                return;
            }
            FlowDataType registered = FlowDataType.registerExtensionType(state.pluginId, type);
            FlowValueCodecRegistry valueCodecs = moduleContext.getRequiredService(FlowValueCodecRegistry.class);
            try {
                boolean boundarySafe = false;
                if (codec != null) {
                    if (!typeId.equalsIgnoreCase(codec.id())) {
                        throw new IllegalArgumentException("Flow codec id must match runtime type " + typeId);
                    }
                    valueCodecs.register(codec);
                    boundarySafe = true;
                } else if (registered.getParent() != null && valueCodecs.hasCodec(FlowTypeRef.simple(registered.getParent().getId()))) {
                    valueCodecs.registerAlias(typeId, registered.getParent().getId());
                    boundarySafe = true;
                }
                metadata.setOwner(state.pluginId);
                metadata.setRuntimeType(registered.getJavaType() != null ? registered.getJavaType().getName() : null);
                metadata.setAvailable(true);
                metadata.setUnavailableReason(null);
                metadata.setTransportable(boundarySafe);
                metadata.setPersistable(boundarySafe);
                metadata.setCodecId(boundarySafe ? "extension:" + state.pluginId + "/" + typeId : null);
                extensionData().addType(state.pluginId, metadata);
                state.typeIds.add(typeId);
            } catch (RuntimeException exception) {
                valueCodecs.unregister(typeId);
                FlowDataType.unregisterExtensionType(state.pluginId, typeId);
                throw exception;
            }
        }

        private void registerStagedType(FlowDataType type, FlowValueCodec<?> codec, FlowTypeMetadata metadata) {
            String typeId = type.getId();
            FlowValueCodecRegistry valueCodecs = transaction.valueCodecs;
            boolean boundarySafe = false;
            if (codec != null) {
                if (!typeId.equalsIgnoreCase(codec.id())) {
                    throw new IllegalArgumentException("Flow codec id must match runtime type " + typeId);
                }
                valueCodecs.register(codec);
                boundarySafe = true;
            } else if (type.getParent() != null && valueCodecs.hasCodec(FlowTypeRef.simple(type.getParent().getId()))) {
                valueCodecs.registerAlias(typeId, type.getParent().getId());
                boundarySafe = true;
            }
            metadata.setOwner(state.pluginId);
            metadata.setRuntimeType(type.getJavaType() != null ? type.getJavaType().getName() : null);
            metadata.setAvailable(true);
            metadata.setUnavailableReason(null);
            metadata.setTransportable(boundarySafe);
            metadata.setPersistable(boundarySafe);
            metadata.setCodecId(boundarySafe ? "extension:" + state.pluginId + "/" + typeId : null);
            extensionData().addType(state.pluginId, metadata);
            state.typeIds.add(typeId);
            state.typeRegistrations.put(typeId.toLowerCase(Locale.ROOT), new TypeRegistration(type, codec, metadata));
        }

        @Override
        public void registerCategory(FlowCategoryMetadata metadata) {
            validateNamespaced(state.pluginId, metadata != null ? metadata.getId() : null, "Flow category");
            extensionData().addCategory(state.pluginId, metadata);
        }

        @Override
        public void registerConversion(FlowConversionRule rule) {
            if (rule != null) {
                rule.setAvailability("unavailable: No executable conversion adapter is registered");
            }
            extensionData().addConversion(state.pluginId, rule);
        }

        @Override
        public <S, T> void registerConversion(Class<S> source, Class<T> target, Function<S, T> adapter, FlowConversionRule rule) {
            if (source == null || target == null || adapter == null || rule == null) {
                throw new IllegalArgumentException("Conversion classes, adapter, and rule are required");
            }
            FlowDataType sourceType = resolveType(rule.getSourceTypeId());
            FlowDataType targetType = resolveType(rule.getTargetTypeId());
            if (!sourceType.isResolved() || !targetType.isResolved()) {
                throw new IllegalArgumentException("Conversion types must be executable before registering the adapter");
            }
            TypeAdapterRegistry adapters = transaction != null ? transaction.typeAdapters : moduleContext.getRequiredService(TypeAdapterRegistry.class);
            adapters.register(source, target, adapter);
            rule.setImplementationId(state.pluginId + ":adapter/" + rule.getSourceTypeId() + "-to-" + rule.getTargetTypeId());
            rule.setAvailability("available");
            extensionData().addConversion(state.pluginId, rule);
            state.conversions.add(new ConversionRegistration(source, target));
        }

        @Override
        public void registerOptionSource(FlowOptionSourceMetadata metadata) {
            validateNamespaced(state.pluginId, metadata != null ? metadata.getId() : null, "Option source");
            extensionData().addOptionSource(state.pluginId, metadata);
        }

        @Override
        public void registerResource(FlowResourceAdapter<?> adapter) {
            String typeId = adapter != null && adapter.descriptor() != null ? adapter.descriptor().typeId() : null;
            validateNamespaced(state.pluginId, typeId, "Flow resource");
            FlowResourceRegistry resources = transaction != null ? transaction.resources : moduleContext.getRequiredService(FlowResourceRegistry.class);
            OptionCatalogRegistry catalogs = transaction != null ? transaction.optionCatalogs : moduleContext.getRequiredService(OptionCatalogRegistry.class);
            String catalogSource = adapter.catalogSource();
            resources.register(state.pluginId, adapter);
            boolean catalogRegistered = false;
            try {
                OptionCatalogProvider existingCatalog = catalogs.provider(catalogSource);
                if (existingCatalog != null) {
                    if (!state.optionCatalogIds.contains(catalogSource)) {
                        throw new IllegalStateException("Resource catalog source is owned by another contribution: " + catalogSource);
                    }
                } else {
                    catalogRegistered = catalogs.register(new OptionCatalogProvider() {
                        @Override
                        public String sourceId() {
                            return catalogSource;
                        }

                        @Override
                        public String revision() {
                            List<String> ids = new ArrayList<>(adapter.listIds());
                            ids.sort(String.CASE_INSENSITIVE_ORDER);
                            return typeId + ":" + ids.size() + ":" + String.join(",", ids);
                        }

                        @Override
                        public List<String> values() {
                            return adapter.listIds();
                        }
                    });
                    if (!catalogRegistered) {
                        throw new IllegalStateException("Resource catalog source is already registered: " + catalogSource);
                    }
                    state.optionCatalogIds.add(catalogSource);
                }
                state.resourceTypeIds.add(typeId);
                extensionData().addResource(state.pluginId, typeId);
            } catch (RuntimeException exception) {
                if (catalogRegistered) {
                    catalogs.unregister(catalogSource);
                }
                resources.unregister(state.pluginId, typeId);
                throw exception;
            }
        }

        @Override
        public void registerValidator(String validatorId, FlowGraphValidationRule validator) {
            validateNamespaced(state.pluginId, validatorId, "Flow validator");
            FlowGraphValidationRegistry validators = transaction != null ? transaction.validators : moduleContext.getRequiredService(FlowGraphValidationRegistry.class);
            validators.register(state.pluginId, validatorId, validator);
            state.validatorIds.add(validatorId);
        }

        private ReSyncExtensionData extensionData() {
            return transaction != null ? transaction.extensionData : moduleContext.getRequiredService(ReSyncExtensionData.class);
        }

        private FlowDataType resolveType(String typeId) {
            if (transaction != null) {
                FlowDataType staged = transaction.resolveType(typeId);
                if (staged != null) {
                    return staged;
                }
            }
            return FlowDataType.fromString(typeId);
        }
    }

    private final class ExtensionModuleRegistration implements ReSyncExtensionContext.ModuleRegistration {
        private final ExtensionState state;
        private final LifecycleTransaction transaction;

        private ExtensionModuleRegistration(ExtensionState state, LifecycleTransaction transaction) {
            this.state = state;
            this.transaction = transaction;
        }

        @Override
        public void register(Module module) {
            validateNamespaced(state.pluginId, module.getModuleId(), "Module");
            for (String channel : module.getChannels()) {
                validateNamespaced(state.pluginId, channel, "Channel");
            }
            if (transaction != null && !transaction.committed) {
                if (transaction.closed || !persistenceLock.isWriteLockedByCurrentThread()) {
                    throw new IllegalStateException("Extension module staging is unavailable");
                }
                transaction.stageModule(state, module);
                return;
            }
            runModuleChange(() -> {
                moduleContext.getModuleRegistry().registerRuntimeModule(module, moduleContext);
                state.moduleIds.add(module.getModuleId());
                state.modules.put(module.getModuleId(), module);
            });
        }

        @Override
        public void unregister(String moduleId) {
            validateNamespaced(state.pluginId, moduleId, "Module");
            if (transaction != null && !transaction.committed) {
                if (transaction.closed || !persistenceLock.isWriteLockedByCurrentThread()) {
                    throw new IllegalStateException("Extension module staging is unavailable");
                }
                state.modules.remove(moduleId);
                state.moduleIds.remove(moduleId);
                return;
            }
            boolean lifecycleAccess = persistenceLock.isWriteLockedByCurrentThread() && state.lifecycleStorageAccess;
            runModuleChange(() -> {
                moduleContext.getModuleRegistry().unregisterRuntimeModule(moduleId, moduleContext);
                if (!lifecycleAccess) {
                    state.moduleIds.remove(moduleId);
                    state.modules.remove(moduleId);
                }
            });
        }

        private void runModuleChange(Runnable change) {
            boolean lifecycleAccess = persistenceLock.isWriteLockedByCurrentThread() && state.lifecycleStorageAccess;
            persistenceLock.writeLock().lock();
            try {
                if (!lifecycleAccess) {
                    requirePersistenceOpen();
                }
                if (activeExtension(state.pluginId) != state) {
                    throw new IllegalStateException("Extension module owner is retired: " + state.pluginId);
                }
                change.run();
            } finally {
                persistenceLock.writeLock().unlock();
            }
        }
    }

    private final class ExtensionHandle implements ExtensionRegistration {
        private final String pluginId;

        private ExtensionHandle(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public String pluginId() {
            return pluginId;
        }

        @Override
        public void close() {
            unregister(pluginId);
        }
    }

    private static final class JarState {
        private final long lastModified;
        private final URLClassLoader classLoader;
        private final List<String> pluginIds;

        private JarState(long lastModified, URLClassLoader classLoader, List<String> pluginIds) {
            this.lastModified = lastModified;
            this.classLoader = classLoader;
            this.pluginIds = pluginIds;
        }
    }

    private static final class PendingLifecycleCompensation {
        private final FlowExecutor.AdmissionFence admissionFence;
        private List<Runnable> retryActions;
        private boolean catalogRuntimeRestorePending;
        private boolean restoreAttempted;
        private boolean recoveryMarkerPending;
        private final String previousCatalogPublicationKey;
        private final FlowModule.CatalogRuntimeTransaction catalogRuntimeTransaction;

        private PendingLifecycleCompensation(FlowExecutor.AdmissionFence admissionFence,
                                             List<Runnable> retryActions,
                                             boolean catalogRuntimeRestorePending,
                                             boolean recoveryMarkerPending,
                                             String previousCatalogPublicationKey,
                                             FlowModule.CatalogRuntimeTransaction catalogRuntimeTransaction) {
            this.admissionFence = admissionFence;
            this.retryActions = List.copyOf(retryActions);
            this.catalogRuntimeRestorePending = catalogRuntimeRestorePending;
            this.recoveryMarkerPending = recoveryMarkerPending;
            this.previousCatalogPublicationKey = previousCatalogPublicationKey;
            this.catalogRuntimeTransaction = catalogRuntimeTransaction;
        }
    }

    private static final class ExtensionState implements ExtensionRegistryActivation.ExtensionLifecycle {
        private final String pluginId;
        private final JavaPlugin owner;
        private final ReSyncExtension extension;
        private final URLClassLoader classLoader;
        private final Path jarPath;
        private final Set<String> nodeIds = new HashSet<>();
        private final Set<String> eventIds = new HashSet<>();
        private final Map<String, String> nodeWireIds = new LinkedHashMap<>();
        private final Set<String> handlerIds = new HashSet<>();
        private final Set<String> moduleIds = new HashSet<>();
        private final Set<PropertyRegistration> propertyIds = new HashSet<>();
        private final Set<String> optionCatalogIds = new HashSet<>();
        private final Set<String> runtimeDataAdapterIds = new HashSet<>();
        private final Set<String> typeIds = new HashSet<>();
        private final Set<ConversionRegistration> conversions = new HashSet<>();
        private final Set<String> resourceTypeIds = new HashSet<>();
        private final Set<String> validatorIds = new HashSet<>();
        private final Set<String> customContentProviderIds = new HashSet<>();
        private final Set<String> worldMapExtensionIds = new HashSet<>();
        private final Set<Listener> listeners = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<String, TypeRegistration> typeRegistrations = new LinkedHashMap<>();
        private final Map<String, Module> modules = new LinkedHashMap<>();
        private final Map<String, CustomContentProvider> customContentProviders = new LinkedHashMap<>();
        private final Map<String, WorldMapExtension> worldMapExtensions = new LinkedHashMap<>();
        private volatile boolean lifecycleStorageAccess;

        private ExtensionState(String pluginId, JavaPlugin owner, ReSyncExtension extension, URLClassLoader classLoader, Path jarPath) {
            this.pluginId = pluginId;
            this.owner = owner;
            this.extension = extension;
            this.classLoader = classLoader;
            this.jarPath = jarPath;
        }

        @Override
        public String extensionId() {
            return pluginId;
        }

        private void clearOwnedIds() {
            nodeIds.clear();
            eventIds.clear();
            nodeWireIds.clear();
            handlerIds.clear();
            moduleIds.clear();
            propertyIds.clear();
            optionCatalogIds.clear();
            runtimeDataAdapterIds.clear();
            typeIds.clear();
            conversions.clear();
            resourceTypeIds.clear();
            validatorIds.clear();
            customContentProviderIds.clear();
            worldMapExtensionIds.clear();
            listeners.clear();
            typeRegistrations.clear();
            modules.clear();
            customContentProviders.clear();
            worldMapExtensions.clear();
        }
    }

    private record PropertyRegistration(String family, String property) {
    }

    private record ConversionRegistration(Class<?> source, Class<?> target) {
    }

    private record TypeRegistration(FlowDataType type, FlowValueCodec<?> codec, FlowTypeMetadata metadata) {
    }

    private enum PersistenceState {
        OPEN,
        QUIESCING,
        QUIESCED,
        FAILED
    }
}
