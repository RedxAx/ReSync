package restudio.resync.worldgen.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.datapack.Datapack;
import org.bukkit.Bukkit;
import restudio.resync.filesystem.windows.WindowsFileIdentity;
import restudio.resync.filesystem.windows.WindowsFileMutation;
import restudio.resync.migration.MigrationFence;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.SecureDirectoryStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class WorldGenDatapackInstaller implements WorldGenInstalledDatapackCapability {
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    private static final String STAGE_PREFIX = ".resync-stage-";
    private static final String BACKUP_PREFIX = ".resync-backup-";
    private static final String RETIRED_PREFIX = ".resync-retire-";
    private static final String JOURNAL_PREFIX = ".resync-install-";
    private static final String JOURNAL_SUFFIX = ".txn";
    private static final String JOURNAL_TEMP_SUFFIX = ".tmp";
    private static final String BOOTSTRAP_SUFFIX = ".bootstrap";
    private static final String BOOTSTRAP_TEMP_SUFFIX = ".tmp";
    private final WorldRootProvider worldRootProvider;
    private final RuntimeVersionProvider runtimeVersionProvider;
    private final PackActivator packActivator;
    private final DirectoryDurability directoryDurability;
    private final MigrationFence mutationFence;
    private final Duration drainTimeout;
    private final ReparsePointProbe reparsePointProbe;
    private final MutationObserver mutationObserver;
    private final WindowsFileMutation windowsFileMutation;
    private final Object monitor = new Object();
    private final Object recoveryMonitor = new Object();
    private State state = State.OPEN;
    private int activeOperations;
    private String failureReason = "";
    private volatile boolean startupHealthPending = true;
    private long recoveryGeneration;
    private long recoveredGeneration = -1L;
    private Path recoveredWorldRoot;
    private boolean rebindRecoveryPending;

    public WorldGenDatapackInstaller() {
        this(
            () -> Bukkit.getWorldContainer().toPath(),
            Bukkit::getMinecraftVersion,
            new BukkitPackActivator(),
            DirectoryDurability.system(),
            new MigrationFence(),
            DEFAULT_DRAIN_TIMEOUT
        );
    }

    public WorldGenDatapackInstaller(MigrationFence mutationFence) {
        this(
            () -> Bukkit.getWorldContainer().toPath(),
            Bukkit::getMinecraftVersion,
            new BukkitPackActivator(),
            DirectoryDurability.system(),
            mutationFence,
            DEFAULT_DRAIN_TIMEOUT
        );
    }

    public WorldGenDatapackInstaller(Path worldContainer, String runtimeVersion, PackActivator packActivator,
                                     DirectoryDurability directoryDurability) {
        this(
            () -> worldContainer,
            () -> runtimeVersion,
            packActivator,
            directoryDurability,
            new MigrationFence(),
            DEFAULT_DRAIN_TIMEOUT
        );
    }

    public WorldGenDatapackInstaller(Path worldContainer, String runtimeVersion, PackActivator packActivator,
                                     DirectoryDurability directoryDurability, WindowsFileMutation windowsFileMutation) {
        this(
            () -> worldContainer,
            () -> runtimeVersion,
            packActivator,
            directoryDurability,
            new MigrationFence(),
            DEFAULT_DRAIN_TIMEOUT,
            ReparsePointProbe.system(),
            MutationObserver.none(),
            windowsFileMutation
        );
    }

    public WorldGenDatapackInstaller(Path worldContainer, String runtimeVersion, PackActivator packActivator,
                                     DirectoryDurability directoryDurability, MigrationFence mutationFence,
                                     Duration drainTimeout) {
        this(
            () -> worldContainer,
            () -> runtimeVersion,
            packActivator,
            directoryDurability,
            mutationFence,
            drainTimeout
        );
    }

    public WorldGenDatapackInstaller(Path worldContainer, String runtimeVersion, PackActivator packActivator,
                                     DirectoryDurability directoryDurability, MigrationFence mutationFence,
                                     Duration drainTimeout, ReparsePointProbe reparsePointProbe,
                                     MutationObserver mutationObserver) {
        this(
            () -> worldContainer,
            () -> runtimeVersion,
            packActivator,
            directoryDurability,
            mutationFence,
            drainTimeout,
            reparsePointProbe,
            mutationObserver
        );
    }

    public WorldGenDatapackInstaller(Path worldContainer, String runtimeVersion, PackActivator packActivator,
                                     DirectoryDurability directoryDurability, MigrationFence mutationFence,
                                     Duration drainTimeout, WindowsFileMutation windowsFileMutation) {
        this(
            () -> worldContainer,
            () -> runtimeVersion,
            packActivator,
            directoryDurability,
            mutationFence,
            drainTimeout,
            ReparsePointProbe.system(),
            MutationObserver.none(),
            windowsFileMutation
        );
    }

    public WorldGenDatapackInstaller(WorldRootProvider worldRootProvider, RuntimeVersionProvider runtimeVersionProvider,
                                     PackActivator packActivator, DirectoryDurability directoryDurability) {
        this(worldRootProvider, runtimeVersionProvider, packActivator, directoryDurability, new MigrationFence(), DEFAULT_DRAIN_TIMEOUT);
    }

    public WorldGenDatapackInstaller(WorldRootProvider worldRootProvider, RuntimeVersionProvider runtimeVersionProvider,
                                     PackActivator packActivator, DirectoryDurability directoryDurability,
                                     MigrationFence mutationFence, Duration drainTimeout) {
        this(worldRootProvider, runtimeVersionProvider, packActivator, directoryDurability, mutationFence, drainTimeout,
            ReparsePointProbe.system(), MutationObserver.none());
    }

    public WorldGenDatapackInstaller(WorldRootProvider worldRootProvider, RuntimeVersionProvider runtimeVersionProvider,
                                     PackActivator packActivator, DirectoryDurability directoryDurability,
                                     MigrationFence mutationFence, Duration drainTimeout,
                                     ReparsePointProbe reparsePointProbe, MutationObserver mutationObserver) {
        this(worldRootProvider, runtimeVersionProvider, packActivator, directoryDurability, mutationFence, drainTimeout,
            reparsePointProbe, mutationObserver, WindowsFileMutation.system());
    }

    public WorldGenDatapackInstaller(WorldRootProvider worldRootProvider, RuntimeVersionProvider runtimeVersionProvider,
                                     PackActivator packActivator, DirectoryDurability directoryDurability,
                                     MigrationFence mutationFence, Duration drainTimeout,
                                     WindowsFileMutation windowsFileMutation) {
        this(worldRootProvider, runtimeVersionProvider, packActivator, directoryDurability, mutationFence, drainTimeout,
            ReparsePointProbe.system(), MutationObserver.none(), windowsFileMutation);
    }

    public WorldGenDatapackInstaller(WorldRootProvider worldRootProvider, RuntimeVersionProvider runtimeVersionProvider,
                                     PackActivator packActivator, DirectoryDurability directoryDurability,
                                     MigrationFence mutationFence, Duration drainTimeout,
                                     ReparsePointProbe reparsePointProbe, MutationObserver mutationObserver,
                                     WindowsFileMutation windowsFileMutation) {
        this.worldRootProvider = Objects.requireNonNull(worldRootProvider, "worldRootProvider");
        this.runtimeVersionProvider = Objects.requireNonNull(runtimeVersionProvider, "runtimeVersionProvider");
        this.packActivator = Objects.requireNonNull(packActivator, "packActivator");
        this.directoryDurability = Objects.requireNonNull(directoryDurability, "directoryDurability");
        this.mutationFence = Objects.requireNonNull(mutationFence, "mutationFence");
        this.drainTimeout = requireTimeout(drainTimeout, "drainTimeout");
        this.reparsePointProbe = Objects.requireNonNull(reparsePointProbe, "reparsePointProbe");
        this.mutationObserver = Objects.requireNonNull(mutationObserver, "mutationObserver");
        this.windowsFileMutation = Objects.requireNonNull(windowsFileMutation, "windowsFileMutation");
    }

    public WorldGenInstalledDatapackCapability capability() {
        return this;
    }

    public WorldGenInstalledDatapackCapability installedDatapackCapability() {
        return this;
    }

    public InstallResult install(WorldGenDatapackBuild build, String worldName) {
        return install(build, worldName, true);
    }

    public InstallResult installPreview(WorldGenDatapackBuild build, String worldName) {
        return install(build, worldName, false);
    }

    public InstallResult installWithHandoff(WorldGenDatapackBuild build, String worldName,
                                            MigrationFence.MutationLease mutation) {
        if (mutation == null) {
            return failureResult("Compile Install Handoff Is Required");
        }
        return install(build, worldName, true, mutation);
    }

    public InstallResult installPreviewWithHandoff(WorldGenDatapackBuild build, String worldName,
                                                   MigrationFence.MutationLease mutation) {
        if (mutation == null) {
            return failureResult("Compile Install Handoff Is Required");
        }
        return install(build, worldName, false, mutation);
    }

    private InstallResult install(WorldGenDatapackBuild build, String worldName, boolean activate) {
        return install(build, worldName, activate, null);
    }

    private InstallResult install(WorldGenDatapackBuild build, String worldName, boolean activate,
                                  MigrationFence.MutationLease handoff) {
        if (build == null || build.getFolder() == null) {
            return failureResult("Datapack Build Missing");
        }
        if (worldName == null || worldName.isBlank()) {
            return failureResult("World Name Required");
        }
        try (OperationLease ignored = handoff == null ? acquireOperation() : acquireOperation(handoff)) {
            synchronized (recoveryMonitor) {
                return installInternal(build, worldName.trim(), activate);
            }
        } catch (IOException | IllegalArgumentException exception) {
            return failureResult(message(exception, "Datapack Install Failed"));
        } catch (RuntimeException exception) {
            markFailed(message(exception, "Datapack Install Failed"));
            return failureResult(message(exception, "Datapack Install Failed"));
        }
    }

    @Override
    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    @Override
    public boolean available() {
        boolean pending = startupHealthPending;
        synchronized (monitor) {
            if (state == State.FAILED || state == State.CLOSED) {
                return false;
            }
        }
        try {
            Path root = requireWorldContainer();
            return checkFilesystemSupport(root, !pending);
        } catch (IOException | RuntimeException exception) {
            if (!pending) {
                markFailed(message(exception, "World Container Is Unavailable"));
            }
            return false;
        }
    }

    @Override
    public String failureReason() {
        synchronized (monitor) {
            return failureReason;
        }
    }

    @Override
    public boolean admissionOpen() {
        synchronized (monitor) {
            return state == State.OPEN;
        }
    }

    @Override
    public int activeOperationCount() {
        synchronized (monitor) {
            return activeOperations;
        }
    }

    @Override
    public void flush() throws IOException {
        ensureUsable();
        if (!awaitIdle(drainTimeout)) {
            throw fail("Installed Datapack Flush Timed Out With " + activeOperationCount() + " Active Operations");
        }
        try {
            healthCheckInternal();
        } catch (IOException | RuntimeException exception) {
            markFailed(message(exception, "Installed Datapack Flush Failed"));
            throw exception;
        }
    }

    @Override
    public void quiesce() throws IOException {
        quiesce(drainTimeout);
    }

    public void quiesce(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        boolean deferHealthFailure;
        synchronized (monitor) {
            while (state == State.QUIESCING) {
                awaitStateChange();
            }
            if (state == State.CLOSED || state == State.QUIESCED) {
                return;
            }
            if (state == State.FAILED) {
                throw failure("Installed Datapack Capability Is Failed");
            }
            state = State.QUIESCING;
            deferHealthFailure = startupHealthPending;
        }
        if (!awaitIdle(wait)) {
            throw fail("Installed Datapack Drain Timed Out With " + activeOperationCount() + " Active Operations");
        }
        try {
            healthCheckInternal(!deferHealthFailure);
        } catch (IOException | RuntimeException exception) {
            if (!deferHealthFailure) {
                markFailed(message(exception, "Installed Datapack Quiesce Failed"));
                throw exception;
            }
            synchronized (monitor) {
                failureReason = message(exception, "Installed Datapack Startup Health Is Pending");
            }
        }
        synchronized (monitor) {
            state = State.QUIESCED;
            monitor.notifyAll();
        }
    }

    public boolean awaitIdle(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (monitor) {
            while (activeOperations > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Installed Datapack Drain Was Interrupted", exception);
                }
            }
            return true;
        }
    }

    @Override
    public void resume() throws IOException {
        boolean pendingOpen;
        synchronized (monitor) {
            if (state == State.OPEN && !startupHealthPending) {
                return;
            }
            pendingOpen = state == State.OPEN;
            if (!pendingOpen && state != State.QUIESCED) {
                throw failure("Installed Datapack Capability Cannot Resume From " + state.name());
            }
        }
        if (!pendingOpen) {
            beginResumeRecoveryGeneration();
        }
        try {
            healthCheckInternal();
        } catch (IOException | RuntimeException exception) {
            markFailed(message(exception, "Installed Datapack Resume Failed"));
            throw exception;
        }
        synchronized (monitor) {
            if ((pendingOpen && state != State.OPEN) || (!pendingOpen && state != State.QUIESCED)) {
                throw failure("Installed Datapack Capability Changed During Resume");
            }
            state = State.OPEN;
            failureReason = "";
            startupHealthPending = false;
            monitor.notifyAll();
        }
    }

    @Override
    public void healthCheck() throws IOException {
        boolean pending = startupHealthPending;
        if (pending) {
            synchronized (monitor) {
                if (state == State.FAILED) {
                    throw failure("Installed Datapack Capability Is Failed");
                }
                if (state == State.CLOSED) {
                    throw failure("Installed Datapack Capability Is Closed");
                }
                if (state != State.OPEN && state != State.QUIESCED) {
                    throw failure("Installed Datapack Admission Is " + state.name());
                }
            }
        } else {
            ensureUsable();
        }
        try {
            healthCheckInternal(!pending);
        } catch (IOException | RuntimeException exception) {
            if (!pending) {
                markFailed(message(exception, "Installed Datapack Health Check Failed"));
            }
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        State current = state();
        if (current == State.CLOSED) {
            return;
        }
        if (current == State.OPEN) {
            quiesce(drainTimeout);
        }
        synchronized (monitor) {
            if (state == State.FAILED) {
                throw failure("Installed Datapack Capability Is Failed");
            }
            state = State.CLOSED;
            monitor.notifyAll();
        }
    }

    private InstallResult installInternal(WorldGenDatapackBuild build, String worldName, boolean activate) throws IOException {
        ensureUsable();
        requireSecureMutationSupport(requireWorldContainer());
        WorldGenTargetVersion target = WorldGenTargetVersion.require(build.getMinecraftVersion());
        WorldGenTargetVersion runtime = WorldGenTargetVersion.require(runtimeVersionProvider.runtimeVersion());
        if (!target.isDatapackCompatibleWith(runtime)) {
            return failureResult("Minecraft Version Mismatch: Pack " + target.id() + ", Server " + runtime.id());
        }
        if (build.getRevision() <= 0L) {
            throw new IOException("Generated Datapack Build Revision Is Invalid");
        }
        String packName = requirePackName(build.getPackName());
        String projectId = requireProjectId(build.getProjectId());
        Path source = requireSource(build.getFolder());
        if (!isOwnedManifest(source.resolve("resync-manifest.json"), build, packName)) {
            throw new IOException("Generated Datapack Is Not ReSync-Owned");
        }
        Map<String, String> sourceFingerprint = fingerprintTree(source);
        String sourceHash = treeHash(sourceFingerprint);
        Path datapacks = resolveDatapacks(worldName);
        recoverTargetForMutation(datapacks);
        Path targetPath = datapacks.resolve(packName).normalize();
        requireInside(datapacks, targetPath, "datapack");
        PreviousTarget previousTarget = validateExistingTarget(targetPath, build, packName);
        ActivationState previousActivation = activate
            ? packActivator.capture(packName, worldName)
            : ActivationState.unknown();
        if (activate && !previousActivation.known()) {
            throw new IOException("Installed Datapack Previous Enabled State Is Unavailable");
        }
        List<RetiredPack> retiredPacks = activate
            ? findPriorRevisions(datapacks, projectId, packName, build.getRevision(), worldName)
            : List.of();
        Transaction transaction = stage(source, sourceFingerprint, sourceHash, datapacks, targetPath, packName, worldName,
            projectId, build, previousTarget, previousActivation, retiredPacks);
        boolean enabled = false;
        boolean finalizationStarted = false;
        try {
            if (!fingerprintTree(source).equals(sourceFingerprint)) {
                throw new IOException("Generated Datapack Changed During Install");
            }
            if (activate) {
                writeJournal(transaction, Phase.ACTIVATING);
                for (RetiredPack retiredPack : transaction.retiredPacks()) {
                    packActivator.restore(retiredPack.packName(), transaction.worldName(), ActivationState.absent());
                }
                enabled = packActivator.enable(packName, worldName);
                if (!enabled) {
                    throw new IOException("Worldgen Datapack Couldn't Be Enabled");
                }
                validateOwnedTree(transaction.target(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
                    transaction.targetHash());
                writeJournal(transaction, Phase.ACTIVATED);
            } else {
                validateOwnedTree(transaction.target(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
                    transaction.targetHash());
                writeJournal(transaction, Phase.ACTIVATED);
            }
            finalizationStarted = true;
            finalizeTransaction(transaction);
            return new InstallResult(true, enabled, enabled ? "Datapack Enabled" : "Datapack Installed", true);
        } catch (IOException | RuntimeException exception) {
            if (finalizationStarted) {
                markFailed("Installed Datapack Finalization Failed: " + message(exception, "Unknown Failure"));
            } else {
                try {
                    rollbackTransaction(transaction, true);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                    markFailed("Installed Datapack Rollback Failed: " + message(rollbackFailure, "Unknown Failure"));
                }
            }
            throw exception;
        }
    }

    private OperationLease acquireOperation() throws IOException {
        ensureUsable();
        MigrationFence.MutationLease mutation = mutationFence.beginMutation();
        synchronized (monitor) {
            if (state != State.OPEN) {
                mutation.close();
                throw failure("Installed Datapack Admission Is " + state.name());
            }
            activeOperations++;
            return new OperationLease(mutation, true);
        }
    }

    private OperationLease acquireOperation(MigrationFence.MutationLease handoff) throws IOException {
        Objects.requireNonNull(handoff, "handoff");
        ensureUsable();
        synchronized (monitor) {
            if (state != State.OPEN) {
                throw failure("Installed Datapack Admission Is " + state.name());
            }
            activeOperations++;
            return new OperationLease(handoff, false);
        }
    }

    private void ensureUsable() throws IOException {
        synchronized (monitor) {
            if (state == State.FAILED) {
                throw failure("Installed Datapack Capability Is Failed");
            }
            if (state == State.CLOSED) {
                throw failure("Installed Datapack Capability Is Closed");
            }
            if (state != State.OPEN && state != State.QUIESCED) {
                throw failure("Installed Datapack Admission Is " + state.name());
            }
        }
        Path root;
        try {
            root = requireWorldContainer();
        } catch (IOException | RuntimeException exception) {
            markFailed(message(exception, "World Container Is Unavailable"));
            throw exception;
        }
        if (!checkFilesystemSupport(root)) {
            throw failure("Installed Datapack Capability Is Unavailable");
        }
    }

    private void healthCheckInternal() throws IOException {
        healthCheckInternal(true);
    }

    private void healthCheckInternal(boolean failClosed) throws IOException {
        Path root = requireWorldContainer();
        if (!checkFilesystemSupport(root, failClosed)) {
            throw failure("Installed Datapack Capability Is Unavailable");
        }
        requireSecureMutationSupport(root);
        recoverLifecycleGeneration(root);
    }

    void invalidateRecoveryForRebind() {
        synchronized (recoveryMonitor) {
            recoveryGeneration++;
            rebindRecoveryPending = true;
        }
    }

    private void beginResumeRecoveryGeneration() {
        synchronized (recoveryMonitor) {
            if (!rebindRecoveryPending) {
                recoveryGeneration++;
            }
            rebindRecoveryPending = false;
        }
    }

    private void recoverLifecycleGeneration(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        synchronized (recoveryMonitor) {
            if (!normalized.equals(recoveredWorldRoot)) {
                recoveryGeneration++;
                recoveredWorldRoot = normalized;
            }
            if (recoveredGeneration == recoveryGeneration) {
                return;
            }
            recoverAllTransactions(root);
            recoveredGeneration = recoveryGeneration;
        }
    }

    private void recoverTargetForMutation(Path datapacks) throws IOException {
        synchronized (recoveryMonitor) {
            recoveryGeneration++;
            recoverTransactions(datapacks);
        }
    }

    private boolean checkFilesystemSupport(Path root) {
        return checkFilesystemSupport(root, true);
    }

    private boolean checkFilesystemSupport(Path root, boolean failClosed) {
        if (isWindows()) {
            try {
                if (!windowsFileMutation.available(root)) {
                    String reason = windowsFileMutation.unavailableReason(root);
                    if (failClosed) {
                        markFailed(reason == null || reason.isBlank()
                            ? "Windows File Mutation Is Unavailable" : reason);
                    }
                    return false;
                }
            } catch (RuntimeException exception) {
                if (failClosed) {
                    markFailed(message(exception, "Windows File Mutation Is Unavailable"));
                }
                return false;
            }
        }
        return checkDirectoryDurability(root, failClosed);
    }

    private boolean checkDirectoryDurability(Path root) {
        return checkDirectoryDurability(root, true);
    }

    private boolean checkDirectoryDurability(Path root, boolean failClosed) {
        try {
            if (directoryDurability.available(root)) {
                return true;
            }
        } catch (RuntimeException exception) {
            if (failClosed) {
                markFailed(message(exception, "Directory Durability Is Unavailable"));
            }
            return false;
        }
        String reason;
        try {
            reason = directoryDurability.unavailableReason(root);
        } catch (RuntimeException exception) {
            reason = message(exception, "Directory Durability Is Unavailable");
        }
        if (failClosed) {
            markFailed(reason == null || reason.isBlank() ? "Directory Durability Is Unavailable" : reason);
        }
        return false;
    }

    private Path requireWorldContainer() throws IOException {
        Path root = worldRootProvider.worldRoot();
        if (root == null) {
            throw new IOException("World Container Is Required");
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World Container Is Not A Directory: " + normalized);
        }
        if (isWindows() && !windowsFileMutation.available(normalized)) {
            String reason = windowsFileMutation.unavailableReason(normalized);
            throw new IOException(reason == null || reason.isBlank()
                ? "Windows File Mutation Is Unavailable" : reason);
        }
        requireNoSymlinkAncestors(normalized);
        canonicalPath(normalized);
        return normalized;
    }

    private Path resolveDatapacks(String worldName) throws IOException {
        Path root = requireWorldContainer();
        String safeWorld = requireWorldName(worldName);
        Path world = root.resolve(safeWorld).normalize();
        requireInside(root, world, "world");
        requireSafeAncestors(root, world);
        if (Files.exists(world, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World Is Not A Directory: " + safeWorld);
        }
        Files.createDirectories(world);
        requireSafeAncestors(root, world);
        canonicalPath(world);
        forceDirectory(world);
        Path datapacks = world.resolve("datapacks").normalize();
        requireInside(world, datapacks, "datapacks");
        if (Files.exists(datapacks, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(datapacks)) {
            throw new IOException("World Datapacks Directory Cannot Be A Symbolic Link");
        }
        Files.createDirectories(datapacks);
        requireSafeAncestors(root, datapacks);
        canonicalPath(datapacks);
        forceDirectory(datapacks);
        forcePath(root);
        return datapacks;
    }

    private Path requireSource(Path source) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Generated Datapack Folder Is Missing: " + normalized);
        }
        requireNoSymlinkAncestors(normalized);
        canonicalPath(normalized);
        validateTree(normalized);
        if (!Files.isRegularFile(normalized.resolve("pack.mcmeta"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Generated Datapack Metadata Is Missing");
        }
        return normalized;
    }

    private Transaction stage(Path source, Map<String, String> sourceFingerprint, String sourceHash, Path datapacks,
                              Path target, String packName, String worldName, String projectId, WorldGenDatapackBuild build,
                              PreviousTarget previousTarget, ActivationState previousActivation,
                              List<RetiredPack> retiredPacks) throws IOException {
        String token = UUID.randomUUID().toString().replace("-", "");
        Path stage = datapacks.resolve(STAGE_PREFIX + token + "-" + packName).normalize();
        Path backup = datapacks.resolve(BACKUP_PREFIX + token + "-" + packName).normalize();
        Path retiredRoot = datapacks.resolve(RETIRED_PREFIX + token).normalize();
        Path journal = datapacks.resolve(JOURNAL_PREFIX + token + JOURNAL_SUFFIX).normalize();
        requireInside(datapacks, stage, "staging");
        requireInside(datapacks, backup, "backup");
        requireInside(datapacks, retiredRoot, "retirement");
        requireInside(datapacks, journal, "journal");
        List<RetiredPack> boundRetiredPacks = retiredPacks.stream()
            .map(retiredPack -> retiredPack.withBackupTarget(retiredRoot.resolve(retiredPack.packName()).normalize()))
            .toList();
        Transaction transaction = new Transaction(datapacks, target, stage, backup, journal, worldName, packName,
            WorldGenInstalledDatapackCapability.OWNER,
            projectId, build.getRevision(), source,
            sourceHash, sourceHash, previousTarget.exists(), previousTarget.hash(), previousTarget.fileKey(), previousActivation,
            retiredRoot, boundRetiredPacks);
        boolean targetReplaced = false;
        try {
            copyTree(source, stage);
            validateTree(stage);
            if (!fingerprintTree(stage).equals(sourceFingerprint)) {
                throw new IOException("Staged Datapack Validation Failed");
            }
            forceTree(stage);
            validateTree(stage);
            if (!fingerprintTree(stage).equals(sourceFingerprint)) {
                throw new IOException("Staged Datapack Validation Failed");
            }
            writeBootstrap(transaction);
            writeJournal(transaction, Phase.PREPARED);
            deleteBootstrap(transaction.journal(), transaction.datapacks());
            boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (previousTarget.exists()) {
                if (!targetExists) {
                    throw new IOException("Installed Datapack Previous Target Changed During Install");
                }
                validateOwnedTree(target, projectId, packName, build.getRevision(), previousTarget.hash());
            } else if (targetExists) {
                throw new IOException("Installed Datapack Target Appeared During Install");
            }
            if (targetExists) {
                writeJournal(transaction, Phase.BACKUP_MOVING);
                moveAtomic(target, backup, transaction.previousTargetFileKey());
                validatePreviousTarget(backup, transaction);
            }
            if (!transaction.retiredPacks().isEmpty()) {
                validateDestructiveParent(retiredRoot, "retirement root");
                Files.createDirectory(retiredRoot);
                requireSafeAncestors(datapacks, retiredRoot);
                writeJournal(transaction, Phase.RETIRING);
                for (int index = 0; index < transaction.retiredPacks().size(); index++) {
                    RetiredPack retiredPack = transaction.retiredPacks().get(index);
                    Path original = retiredPack.originalTarget();
                    Path retirement = retiredPack.backupTarget();
                    requireInside(retiredRoot, retirement, "retirement pack");
                    if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(original)
                        || !Files.isDirectory(original, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Installed Prior Datapack Changed During Install: " + original);
                    }
                    validateRetiredPack(original, retiredPack);
                    transaction = withRetiredState(transaction, index, RetirementState.MOVING);
                    writeJournal(transaction, Phase.RETIRING);
                    moveAtomic(original, retirement, retiredPack.originalFileKey());
                    validateRetiredPack(retirement, retiredPack);
                    transaction = withRetiredState(transaction, index, RetirementState.RETIRED);
                    writeJournal(transaction, Phase.RETIRING);
                }
            }
            if (targetExists || !transaction.retiredPacks().isEmpty()) {
                writeJournal(transaction, Phase.BACKUP_MOVED);
            }
            writeJournal(transaction, Phase.TARGET_MOVING);
            moveAtomic(stage, target, fileKey(stage));
            targetReplaced = true;
            validateOwnedTree(target, projectId, packName, build.getRevision(), sourceHash);
            forceTree(target);
            forceDirectory(datapacks);
            writeJournal(transaction, Phase.TARGET_MOVED);
            return transaction;
        } catch (IOException | RuntimeException exception) {
            try {
                rollbackTransaction(transaction, targetReplaced);
            } catch (IOException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
                markFailed("Installed Datapack Rollback Failed: " + message(rollbackFailure, "Unknown Failure"));
            }
            throw exception;
        }
    }

    private void finalizeTransaction(Transaction transaction) throws IOException {
        validateOwnedTree(transaction.target(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
            transaction.targetHash());
        if (Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
            validatePreviousTarget(transaction.backup(), transaction);
            deleteTree(transaction.backup());
            forceDirectory(transaction.datapacks());
        }
        for (RetiredPack retiredPack : transaction.retiredPacks()) {
            Path retirement = retiredPack.backupTarget();
            if (Files.exists(retirement, LinkOption.NOFOLLOW_LINKS)) {
                validateRetiredPack(retirement, retiredPack);
            }
        }
        if (Files.exists(transaction.retiredRoot(), LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(transaction.retiredRoot());
            forceDirectory(transaction.datapacks());
        }
        deleteBootstrap(transaction.journal(), transaction.datapacks());
        deleteJournal(transaction.journal(), transaction.datapacks());
    }

    private void rollbackTransaction(Transaction transaction, boolean targetReplaced) throws IOException {
        boolean targetExists = Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS);
        boolean backupExists = Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS);
        if (targetExists && Files.isSymbolicLink(transaction.target())) {
            throw new IOException("Installed Datapack Target Became A Symbolic Link");
        }
        if (backupExists && Files.isSymbolicLink(transaction.backup())) {
            throw new IOException("Installed Datapack Backup Became A Symbolic Link");
        }
        if (targetExists) {
            if (targetReplaced || backupExists) {
                validateOwnedTree(transaction.target(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
                    transaction.targetHash());
                deleteTree(transaction.target());
                targetExists = false;
            } else if (transaction.previousTargetExists()) {
                validatePreviousTarget(transaction.target(), transaction);
            } else {
                throw new IOException("Installed Datapack Rollback Found An Unexpected Target");
            }
        }
        if (backupExists) {
            validatePreviousTarget(transaction.backup(), transaction);
                moveAtomic(transaction.backup(), transaction.target(), transaction.previousTargetFileKey());
            targetExists = true;
        }
        for (RetiredPack retiredPack : transaction.retiredPacks()) {
            Path original = retiredPack.originalTarget();
            Path retirement = retiredPack.backupTarget();
            boolean originalExists = Files.exists(original, LinkOption.NOFOLLOW_LINKS);
            boolean retirementExists = Files.exists(retirement, LinkOption.NOFOLLOW_LINKS);
            if ((originalExists && Files.isSymbolicLink(original)) || (retirementExists && Files.isSymbolicLink(retirement))) {
                throw new IOException("Installed Prior Datapack Became A Symbolic Link");
            }
            if (originalExists && retirementExists) {
                throw new IOException("Installed Prior Datapack Rollback Is Ambiguous: " + original);
            }
            if (retirementExists) {
                validateRetiredPack(retirement, retiredPack);
                moveAtomic(retirement, original, retiredPack.backupFileKey());
            } else if (originalExists) {
                validateRetiredPack(original, retiredPack);
            }
        }
        if (Files.exists(transaction.stage(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(transaction.journal(), LinkOption.NOFOLLOW_LINKS)) {
                validateOwnedTree(transaction.stage(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
                    transaction.sourceHash());
            } else {
                validateTreeShape(transaction.stage());
            }
            deleteTree(transaction.stage());
        }
        if (Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(transaction.backup());
        }
        if (Files.exists(transaction.retiredRoot(), LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(transaction.retiredRoot());
        }
        forceDirectory(transaction.datapacks());
        restoreActivation(transaction);
        for (RetiredPack retiredPack : transaction.retiredPacks()) {
            restoreActivation(retiredPack.packName(), transaction.worldName(), retiredPack.previousActivation());
        }
        deleteBootstrap(transaction.journal(), transaction.datapacks());
        deleteJournal(transaction.journal(), transaction.datapacks());
    }

    private void restoreActivation(Transaction transaction) throws IOException {
        restoreActivation(transaction.packName(), transaction.worldName(), transaction.previousActivation());
    }

    private void restoreActivation(String packName, String worldName, ActivationState activation) throws IOException {
        try {
            packActivator.restore(packName, worldName, activation);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Installed Datapack Previous Enabled State Could Not Be Restored", exception);
        }
    }

    private Transaction withRetiredState(Transaction transaction, int index, RetirementState state) {
        List<RetiredPack> retiredPacks = new ArrayList<>(transaction.retiredPacks());
        retiredPacks.set(index, retiredPacks.get(index).withState(state));
        return new Transaction(transaction.datapacks(), transaction.target(), transaction.stage(), transaction.backup(),
            transaction.journal(), transaction.worldName(), transaction.packName(), transaction.owner(), transaction.projectId(),
            transaction.buildRevision(), transaction.source(), transaction.sourceHash(), transaction.targetHash(),
            transaction.previousTargetExists(), transaction.previousTargetHash(), transaction.previousTargetFileKey(),
            transaction.previousActivation(),
            transaction.retiredRoot(), retiredPacks);
    }

    private void recoverAllTransactions(Path root) throws IOException {
        canonicalPath(root);
        List<Path> worlds;
        try (var stream = Files.list(root)) {
            worlds = stream.toList();
        }
        for (Path entry : worlds) {
            if (Files.isSymbolicLink(entry)) {
                throw new IOException("World Container Entry Cannot Be A Symbolic Link: " + entry);
            }
        }
        worlds = worlds.stream().filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList();
        for (Path world : worlds) {
            canonicalPath(world);
            Path datapacks = world.resolve("datapacks").normalize();
            if (Files.isSymbolicLink(datapacks)) {
                throw new IOException("World Datapacks Directory Cannot Be A Symbolic Link: " + datapacks);
            }
            if (Files.exists(datapacks, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(datapacks, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("World Datapacks Path Is Not A Directory: " + datapacks);
            }
            if (Files.isDirectory(datapacks, LinkOption.NOFOLLOW_LINKS)) {
                canonicalPath(datapacks);
                recoverTransactions(datapacks);
            }
        }
    }

    private void recoverTransactions(Path datapacks) throws IOException {
        canonicalPath(datapacks);
        requireSecureMutationSupport(datapacks);
        recoverJournalTemps(datapacks);
        List<Path> journals;
        try (var stream = Files.list(datapacks)) {
            journals = stream
                .filter(path -> {
                    String name = path.getFileName().toString();
                    if (!name.startsWith(JOURNAL_PREFIX)) {
                        return false;
                    }
                    if (!name.endsWith(JOURNAL_SUFFIX)) {
                        throw new InvalidTransactionMarkerException(path);
                    }
                    return true;
                })
                .toList();
        }
        Set<Path> referencedArtifacts = new HashSet<>();
        for (Path journal : journals) {
            if (Files.isSymbolicLink(journal) || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Installed Datapack Transaction Marker Is Invalid: " + journal);
            }
            canonicalPath(journal);
            JournalTransaction transaction = readTransaction(datapacks, journal);
            referencedArtifacts.add(transaction.transaction().stage().getFileName());
            referencedArtifacts.add(transaction.transaction().backup().getFileName());
            referencedArtifacts.add(transaction.transaction().retiredRoot().getFileName());
            recoverTransaction(transaction.transaction(), transaction.phase(), transaction.topology());
            deleteBootstrap(journal, datapacks);
        }
        recoverBootstrapArtifacts(datapacks);
        recoverOrphanArtifacts(datapacks, referencedArtifacts);
    }

    private void recoverJournalTemps(Path datapacks) throws IOException {
        List<Path> temporaryJournals;
        try (var stream = Files.list(datapacks)) {
            temporaryJournals = stream.filter(path -> path.getFileName().toString().startsWith(JOURNAL_PREFIX)
                && path.getFileName().toString().endsWith(JOURNAL_SUFFIX + JOURNAL_TEMP_SUFFIX)).toList();
        }
        for (Path temporaryJournal : temporaryJournals) {
            if (Files.isSymbolicLink(temporaryJournal) || !Files.isRegularFile(temporaryJournal, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Installed Datapack Temporary Transaction Marker Is Invalid: " + temporaryJournal);
            }
            canonicalPath(temporaryJournal);
            String temporaryName = temporaryJournal.getFileName().toString();
            String journalName = temporaryName.substring(0, temporaryName.length() - JOURNAL_TEMP_SUFFIX.length());
            Path journal = datapacks.resolve(journalName).normalize();
            if (!isJournalName(journalName)) {
                throw new IOException("Installed Datapack Temporary Transaction Marker Name Is Invalid: " + temporaryJournal);
            }
            boolean journalValid = false;
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(journal) || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Installed Datapack Transaction Marker Is Invalid: " + journal);
                }
                try {
                    readTransaction(datapacks, journal);
                    journalValid = true;
                } catch (IOException | RuntimeException ignored) {
                }
            }
            Map<String, String> candidate;
            try {
                candidate = readJournalValues(temporaryJournal);
            } catch (IOException exception) {
                if (journalValid) {
                    deleteJournalTemporary(temporaryJournal);
                    continue;
                }
                throw exception;
            }
            if (!"3".equals(candidate.get("version")) || !WorldGenInstalledDatapackCapability.OWNER.equals(candidate.get("owner"))
                || !journalName.equals(journal.getFileName().toString()) || !Phase.isKnown(candidate.getOrDefault("phase", ""))) {
                if (journalValid) {
                    deleteJournalTemporary(temporaryJournal);
                    continue;
                }
                Path bootstrapPath = datapacks.resolve(journalName + BOOTSTRAP_SUFFIX).normalize();
                Bootstrap bootstrap = Files.exists(bootstrapPath, LinkOption.NOFOLLOW_LINKS)
                    ? readBootstrap(datapacks, bootstrapPath, journalName) : null;
                if (recoverPreparedMarker(datapacks, temporaryJournal, journalName, bootstrap)) {
                    continue;
                }
                throw new IOException("Installed Datapack Temporary Transaction Marker Is Invalid: " + temporaryJournal);
            }
            if (!journalValid) {
                replaceAtomic(temporaryJournal, journal);
                continue;
            }
            deleteJournalTemporary(temporaryJournal);
        }
    }

    private void recoverBootstrapArtifacts(Path datapacks) throws IOException {
        List<Path> bootstrapMarkers;
        try (var stream = Files.list(datapacks)) {
            bootstrapMarkers = stream.filter(path -> path.getFileName().toString().startsWith(JOURNAL_PREFIX)
                && path.getFileName().toString().endsWith(BOOTSTRAP_SUFFIX)).toList();
        }
        for (Path bootstrap : bootstrapMarkers) {
            String bootstrapName = bootstrap.getFileName().toString();
            String journalName = bootstrapName.substring(0, bootstrapName.length() - BOOTSTRAP_SUFFIX.length());
            if (!isJournalName(journalName)) {
                throw new IOException("Installed Datapack Bootstrap Marker Name Is Invalid: " + bootstrap);
            }
            Path journal = datapacks.resolve(journalName).normalize();
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                deleteBootstrap(journal, datapacks);
                continue;
            }
            recoverPreparedMarker(datapacks, bootstrap, journalName, readBootstrap(datapacks, bootstrap, journalName));
        }
        List<Path> bootstrapTemps;
        try (var stream = Files.list(datapacks)) {
            bootstrapTemps = stream.filter(path -> path.getFileName().toString().startsWith(JOURNAL_PREFIX)
                && path.getFileName().toString().endsWith(BOOTSTRAP_SUFFIX + BOOTSTRAP_TEMP_SUFFIX)).toList();
        }
        for (Path bootstrapTemp : bootstrapTemps) {
            String temporaryName = bootstrapTemp.getFileName().toString();
            String bootstrapName = temporaryName.substring(0, temporaryName.length() - BOOTSTRAP_TEMP_SUFFIX.length());
            String journalName = bootstrapName.substring(0, bootstrapName.length() - BOOTSTRAP_SUFFIX.length());
            if (!isJournalName(journalName)) {
                throw new IOException("Installed Datapack Temporary Bootstrap Marker Name Is Invalid: " + bootstrapTemp);
            }
            Path journal = datapacks.resolve(journalName).normalize();
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                deleteFile(bootstrapTemp, "temporary bootstrap marker");
                continue;
            }
            if (!recoverPreparedMarker(datapacks, bootstrapTemp, journalName, null)) {
                throw new IOException("Installed Datapack Temporary Bootstrap Marker Is Invalid: " + bootstrapTemp);
            }
        }
    }

    private boolean recoverPreparedMarker(Path datapacks, Path marker, String journalName, Bootstrap bootstrap)
        throws IOException {
        validateMarker(marker, "transaction marker");
        if (bootstrap == null && !hasOwnedMarkerPrefix(marker)) {
            return false;
        }
        String token = journalName.substring(JOURNAL_PREFIX.length(), JOURNAL_PREFIX.length() + 32);
        Path stage = bootstrap == null ? findPreparedStage(datapacks, token) : bootstrap.stage();
        if (stage == null) {
            return false;
        }
        String packName;
        String projectId;
        long revision;
        String stageHash;
        String stageFileKey;
        if (bootstrap == null) {
            String name = stage.getFileName().toString();
            packName = name.substring(STAGE_PREFIX.length() + 33);
            ManifestIdentity identity = readManifestIdentity(stage);
            if (identity == null) {
                return false;
            }
            projectId = identity.projectId();
            revision = identity.revision();
            stageHash = treeHash(fingerprintTree(stage));
            stageFileKey = fileKey(stage);
        } else {
            packName = bootstrap.packName();
            projectId = bootstrap.projectId();
            revision = bootstrap.buildRevision();
            stageHash = bootstrap.stageHash();
            stageFileKey = bootstrap.stageFileKey();
            requireCanonicalValue(datapacks, bootstrap.datapacksCanonical(), "bootstrap datapacks", marker);
            requireFileKey(datapacks, bootstrap.datapacksFileKey(), "bootstrap datapacks", marker);
            if (!bootstrap.stage().equals(stage)) {
                throw new IOException("Installed Datapack Bootstrap Staging Path Changed: " + marker);
            }
        }
        requirePackName(packName);
        requireProjectId(projectId);
        validateOwnedTree(stage, projectId, packName, revision, stageHash);
        requireFileKey(stage, stageFileKey, "bootstrap staging", marker);
        Path backup = datapacks.resolve(BACKUP_PREFIX + token + "-" + packName).normalize();
        Path retiredRoot = datapacks.resolve(RETIRED_PREFIX + token).normalize();
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) || Files.exists(retiredRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installed Datapack Bootstrap Has Advanced Artifacts: " + marker);
        }
        Path target = datapacks.resolve(packName).normalize();
        requireInside(datapacks, target, "bootstrap target");
        if (bootstrap != null) {
            validateBootstrapTarget(bootstrap, target, marker);
        }
        deleteTree(stage);
        deleteFile(marker, "partial transaction marker");
        deleteBootstrap(datapacks.resolve(journalName), datapacks);
        forceDirectory(datapacks);
        return true;
    }

    private void validateBootstrapTarget(Bootstrap bootstrap, Path target, Path marker) throws IOException {
        if (!samePathString(target, bootstrap.target())) {
            throw new IOException("Installed Datapack Bootstrap Target Path Is Invalid: " + marker);
        }
        boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (targetExists != bootstrap.previousTargetExists()) {
            throw new IOException("Installed Datapack Bootstrap Target Presence Changed: " + marker);
        }
        if (targetExists) {
            validateOwnedTree(target, bootstrap.projectId(), bootstrap.packName(), bootstrap.buildRevision(),
                bootstrap.previousTargetHash());
            requireFileKey(target, bootstrap.previousTargetFileKey(), "bootstrap target", marker);
        }
    }

    private Path findPreparedStage(Path datapacks, String token) throws IOException {
        List<Path> stages;
        try (var stream = Files.list(datapacks)) {
            stages = stream.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(STAGE_PREFIX + token + "-");
            }).toList();
        }
        if (stages.size() != 1) {
            return null;
        }
        Path stage = stages.getFirst();
        String name = stage.getFileName().toString();
        requireArtifactName(name, STAGE_PREFIX, "prepared staging");
        if (!isOwnedOrphanArtifact(stage)) {
            return null;
        }
        return stage;
    }

    private boolean hasOwnedMarkerPrefix(Path marker) throws IOException {
        String value = Files.readString(marker, StandardCharsets.UTF_8);
        return value.startsWith("version=3\nowner=" + WorldGenInstalledDatapackCapability.OWNER + "\n")
            || value.startsWith("version=1\nowner=" + WorldGenInstalledDatapackCapability.OWNER + "\n");
    }

    private void validateMarker(Path marker, String field) throws IOException {
        if (marker == null || Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installed Datapack " + field + " Is Invalid: " + marker);
        }
        canonicalPath(marker);
    }

    private Bootstrap readBootstrap(Path datapacks, Path bootstrap, String journalName) throws IOException {
        validateMarker(bootstrap, "bootstrap marker");
        Map<String, String> values = readJournalValues(bootstrap);
        if (!"1".equals(requiredJournalField(values, "version", bootstrap))
            || !WorldGenInstalledDatapackCapability.OWNER.equals(requiredJournalField(values, "owner", bootstrap))) {
            throw new IOException("Installed Datapack Bootstrap Marker Is Invalid: " + bootstrap);
        }
        String world = requiredJournalField(values, "world", bootstrap);
        String actualWorld = datapacks.getParent() == null || datapacks.getParent().getFileName() == null
            ? "" : datapacks.getParent().getFileName().toString();
        if (!world.equals(actualWorld)) {
            throw new IOException("Installed Datapack Bootstrap World Does Not Match Its Directory: " + bootstrap);
        }
        String packName = requirePackName(requiredJournalField(values, "pack", bootstrap));
        String projectId = requireProjectId(requiredJournalField(values, "projectId", bootstrap));
        long revision = parseRevision(requiredJournalField(values, "buildRevision", bootstrap), bootstrap);
        String sourceHash = requiredJournalField(values, "sourceHash", bootstrap);
        requireHash(sourceHash, "bootstrap sourceHash", bootstrap);
        if (!"PREPARED".equals(requiredJournalField(values, "phase", bootstrap))) {
            throw new IOException("Installed Datapack Bootstrap Phase Is Invalid: " + bootstrap);
        }
        String token = journalName.substring(JOURNAL_PREFIX.length(), JOURNAL_PREFIX.length() + 32);
        String stageName = requiredJournalField(values, "stage", bootstrap);
        if (!stageName.equals(STAGE_PREFIX + token + "-" + packName)) {
            throw new IOException("Installed Datapack Bootstrap Staging Path Is Invalid: " + bootstrap);
        }
        Path stage = datapacks.resolve(stageName).normalize();
        Path target = absoluteJournalPath(requiredJournalField(values, "target", bootstrap), "target", bootstrap);
        String datapacksCanonical = requiredJournalField(values, "datapacks", bootstrap);
        String datapacksFileKey = requiredJournalField(values, "datapacksFileKey", bootstrap);
        String stageCanonical = requiredJournalField(values, "stageCanonical", bootstrap);
        String stageFileKey = requiredJournalField(values, "stageFileKey", bootstrap);
        String targetCanonical = requiredJournalField(values, "targetCanonical", bootstrap);
        String targetFileKey = requiredJournalField(values, "targetFileKey", bootstrap);
        requireCanonicalValue(datapacks, datapacksCanonical, "bootstrap datapacks", bootstrap);
        requireFileKey(datapacks, datapacksFileKey, "bootstrap datapacks", bootstrap);
        requireCanonicalExpected(stage, stageCanonical, "bootstrap staging", bootstrap);
        requireCanonicalExpected(target, targetCanonical, "bootstrap target", bootstrap);
        boolean previousTargetExists = parseBoolean(requiredJournalField(values, "previousTargetExists", bootstrap),
            "bootstrap previousTargetExists", bootstrap);
        String previousTargetHash = requiredJournalField(values, "previousTargetHash", bootstrap);
        String previousTargetFileKey = requiredJournalField(values, "previousTargetFileKey", bootstrap);
        if (previousTargetExists) {
            requireHash(previousTargetHash, "bootstrap previousTargetHash", bootstrap);
            if (previousTargetFileKey.isBlank()) {
                throw new IOException("Installed Datapack Bootstrap Previous Target Identity Is Missing: " + bootstrap);
            }
        } else if (!previousTargetHash.isBlank() || !previousTargetFileKey.isBlank()) {
            throw new IOException("Installed Datapack Bootstrap Previous Target Identity Is Invalid: " + bootstrap);
        }
        return new Bootstrap(datapacksCanonical, datapacksFileKey, packName, projectId, revision, sourceHash, stage,
            stageFileKey, target, previousTargetExists, previousTargetHash, previousTargetFileKey);
    }

    private void deleteJournalTemporary(Path temporaryJournal) throws IOException {
        validateDestructiveSource(temporaryJournal, "temporary transaction marker");
        validateDestructiveParent(temporaryJournal, "temporary transaction marker parent");
        canonicalPath(temporaryJournal);
        canonicalPath(temporaryJournal.getParent());
        deleteFile(temporaryJournal, "temporary transaction marker");
    }

    private boolean isJournalName(String name) {
        return name.length() == JOURNAL_PREFIX.length() + 32 + JOURNAL_SUFFIX.length()
            && name.startsWith(JOURNAL_PREFIX)
            && name.endsWith(JOURNAL_SUFFIX)
            && name.substring(JOURNAL_PREFIX.length(), JOURNAL_PREFIX.length() + 32).matches("[0-9a-f]{32}");
    }

    private static final class InvalidTransactionMarkerException extends RuntimeException {
        private InvalidTransactionMarkerException(Path path) {
            super("Installed Datapack Transaction Marker Name Is Invalid: " + path);
        }
    }

    private void recoverOrphanArtifacts(Path datapacks, Set<Path> referencedArtifacts) throws IOException {
        boolean removed = false;
        try (var stream = Files.list(datapacks)) {
            for (Path artifact : stream.toList()) {
                String name = artifact.getFileName().toString();
                if (referencedArtifacts.contains(artifact.getFileName())) {
                    continue;
                }
                if (name.startsWith(STAGE_PREFIX)) {
                    requireArtifactName(name, STAGE_PREFIX, "orphan staging");
                    if (Files.isSymbolicLink(artifact)) {
                        throw new IOException("Orphan Datapack Staging Path Is A Symbolic Link: " + artifact);
                    }
                    if (isOwnedOrphanArtifact(artifact)) {
                        deleteTree(artifact);
                        removed = true;
                    }
                } else if (name.startsWith(BACKUP_PREFIX)) {
                    requireArtifactName(name, BACKUP_PREFIX, "orphan backup");
                    if (Files.isSymbolicLink(artifact)) {
                        throw new IOException("Orphan Datapack Backup Path Is A Symbolic Link: " + artifact);
                    }
                    if (isOwnedOrphanArtifact(artifact)) {
                        deleteTree(artifact);
                        removed = true;
                    }
                } else if (name.startsWith(RETIRED_PREFIX)) {
                    requireRetirementRootName(name);
                    if (Files.isSymbolicLink(artifact) || !Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Orphan Datapack Retirement Root Is Invalid: " + artifact);
                    }
                    canonicalPath(artifact);
                    boolean owned = true;
                    boolean hasChild = false;
                    try (var children = Files.list(artifact)) {
                        for (Path child : children.toList()) {
                            hasChild = true;
                            if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                                || !isOwnedOrphanArtifact(child)) {
                                owned = false;
                                break;
                            }
                        }
                    }
                    if (owned && hasChild) {
                        deleteTree(artifact);
                        removed = true;
                    }
                }
            }
        }
        if (removed) {
            forceDirectory(datapacks);
        }
    }

    private boolean isOwnedOrphanArtifact(Path artifact) throws IOException {
        if (!Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(artifact)) {
            return false;
        }
        canonicalPath(artifact);
        ManifestIdentity identity = readManifestIdentity(artifact);
        if (identity == null || identity.revision() <= 0L) {
            return false;
        }
        String name = artifact.getFileName().toString();
        if (!name.equals(identity.packName())
            && !isArtifactForPack(name, STAGE_PREFIX, identity.packName())
            && !isArtifactForPack(name, BACKUP_PREFIX, identity.packName())) {
            return false;
        }
        validateTree(artifact);
        String hash = treeHash(fingerprintTree(artifact));
        return isOwnedManifest(artifact.resolve("resync-manifest.json"), identity.projectId(), identity.packName(), identity.revision())
            && hash.matches("[0-9a-f]{64}");
    }

    private boolean isArtifactForPack(String name, String prefix, String packName) {
        return name.length() > prefix.length() + 33
            && name.startsWith(prefix)
            && name.substring(prefix.length(), prefix.length() + 32).matches("[0-9a-f]{32}")
            && name.charAt(prefix.length() + 32) == '-'
            && name.substring(prefix.length() + 33).equals(packName);
    }

    private void recoverTransaction(Transaction transaction, Phase phase, JournalTopology topology) throws IOException {
        validateTransactionTopology(transaction, phase, topology);
        validateOwnedTree(transaction.source(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
            transaction.sourceHash());
        validateRetirementArtifacts(transaction, phase);
        if (phase == Phase.ACTIVATED) {
            recoverCommittedTransaction(transaction);
        } else {
            recoverRolledBackTransaction(transaction, phase);
        }
    }

    private void validateTransactionTopology(Transaction transaction, Phase phase, JournalTopology topology) throws IOException {
        requireCanonicalValue(transaction.datapacks(), topology.datapacksCanonical(), "datapacks", transaction.journal());
        requireFileKey(transaction.datapacks(), topology.datapacksFileKey(), "datapacks", transaction.journal());
        requireCanonicalValue(transaction.source(), topology.sourceCanonical(), "source", transaction.journal());
        requireFileKey(transaction.source(), topology.sourceFileKey(), "source", transaction.journal());
        validateOptionalTopology(transaction.target(), topology.targetCanonical(), topology.targetFileKey(), "target", transaction.journal());
        validateOptionalTopology(transaction.stage(), topology.stageCanonical(), topology.stageFileKey(), "stage", transaction.journal());
        validateOptionalTopology(transaction.backup(), topology.backupCanonical(), topology.backupFileKey(), "backup", transaction.journal());
        validateOptionalTopology(transaction.retiredRoot(), topology.retiredRootCanonical(), topology.retiredRootFileKey(),
            "retiredRoot", transaction.journal());
        for (RetiredPack retiredPack : transaction.retiredPacks()) {
            validateOptionalTopology(retiredPack.originalTarget(), retiredPack.originalTarget().toString(),
                retiredPack.originalFileKey(), "retired original", transaction.journal());
            validateOptionalTopology(retiredPack.backupTarget(), retiredPack.backupTarget().toString(),
                retiredPack.backupFileKey(), "retired retirement", transaction.journal());
        }
    }

    private void validateOptionalTopology(Path path, String expectedCanonical, String expectedFileKey, String field,
                                          Path journal) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Installed Datapack " + field + " Became A Symbolic Link: " + journal);
        }
        requireCanonicalValue(path, expectedCanonical, field, journal);
        requireFileKey(path, expectedFileKey, field, journal);
    }

    private void requireFileKey(Path path, String expected, String field, Path journal) throws IOException {
        if (expected == null || expected.isBlank()) {
            return;
        }
        String actual = fileKey(path);
        if (!matchesFileKey(path, expected, actual)) {
            throw new IOException("Installed Datapack " + field + " File Identity Changed: " + journal);
        }
    }

    private boolean matchesFileKey(Path path, String expected, String actual) throws IOException {
        return expected.equals(actual) || (isWindows() && expected.equals(legacyFileKey(path)));
    }

    private void recoverCommittedTransaction(Transaction transaction) throws IOException {
        if (!Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Committed Datapack Target Is Missing");
        }
        validateOwnedTree(transaction.target(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
            transaction.targetHash());
        if (Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
            validatePreviousTarget(transaction.backup(), transaction);
            deleteTree(transaction.backup());
        }
        if (Files.exists(transaction.stage(), LinkOption.NOFOLLOW_LINKS)) {
            validateOwnedTree(transaction.stage(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
                transaction.sourceHash());
            deleteTree(transaction.stage());
        }
        for (RetiredPack retiredPack : transaction.retiredPacks()) {
            if (retiredPack.state() != RetirementState.RETIRED
                || !Files.exists(retiredPack.backupTarget(), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(retiredPack.originalTarget(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Committed Prior Datapack Was Not Retired: " + retiredPack.originalTarget());
            }
            validateRetiredPack(retiredPack.backupTarget(), retiredPack);
        }
        if (Files.exists(transaction.retiredRoot(), LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(transaction.retiredRoot());
        }
        forceDirectory(transaction.datapacks());
        deleteJournal(transaction.journal(), transaction.datapacks());
    }

    private void recoverRolledBackTransaction(Transaction transaction, Phase phase) throws IOException {
        boolean targetExists = Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS);
        boolean backupExists = Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS);
        if (backupExists) {
            validatePreviousTarget(transaction.backup(), transaction);
            if (targetExists) {
                validateOwnedTree(transaction.target(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
                    transaction.targetHash());
                deleteTree(transaction.target());
            }
            moveAtomic(transaction.backup(), transaction.target(), transaction.previousTargetFileKey());
        } else if (transaction.previousTargetExists()) {
            if (!targetExists) {
                throw new IOException("Installed Datapack Transaction Lost Its Previous Target");
            }
            if (treeHash(fingerprintTree(transaction.target())).equals(transaction.previousTargetHash())) {
                validatePreviousTarget(transaction.target(), transaction);
            } else {
                validateOwnedTree(transaction.target(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
                    transaction.targetHash());
                deleteTree(transaction.target());
            }
        } else if (targetExists) {
            validateOwnedTree(transaction.target(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
                transaction.targetHash());
            deleteTree(transaction.target());
        }
        if (Files.exists(transaction.stage(), LinkOption.NOFOLLOW_LINKS)) {
            validateOwnedTree(transaction.stage(), transaction.projectId(), transaction.packName(), transaction.buildRevision(),
                transaction.sourceHash());
            deleteTree(transaction.stage());
        }
        if (Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installed Datapack Transaction Backup Was Not Restored");
        }
        restoreRetiredPacks(transaction);
        if (Files.exists(transaction.retiredRoot(), LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(transaction.retiredRoot());
        }
        forceDirectory(transaction.datapacks());
        restoreActivation(transaction);
        for (RetiredPack retiredPack : transaction.retiredPacks()) {
            restoreActivation(retiredPack.packName(), transaction.worldName(), retiredPack.previousActivation());
        }
        deleteJournal(transaction.journal(), transaction.datapacks());
    }

    private void restoreRetiredPacks(Transaction transaction) throws IOException {
        for (RetiredPack retiredPack : transaction.retiredPacks()) {
            Path original = retiredPack.originalTarget();
            Path retirement = retiredPack.backupTarget();
            boolean originalExists = Files.exists(original, LinkOption.NOFOLLOW_LINKS);
            boolean retirementExists = Files.exists(retirement, LinkOption.NOFOLLOW_LINKS);
            if (originalExists && retirementExists) {
                throw new IOException("Installed Prior Datapack Rollback Is Ambiguous: " + original);
            }
            if (retirementExists) {
                validateRetiredPack(retirement, retiredPack);
                moveAtomic(retirement, original, retiredPack.backupFileKey());
                validateRetiredPack(original, retiredPack);
            } else if (originalExists) {
                validateRetiredPack(original, retiredPack);
            } else {
                throw new IOException("Installed Prior Datapack Was Stranded: " + original);
            }
        }
    }

    private JournalTransaction readTransaction(Path datapacks, Path journal) throws IOException {
        Map<String, String> values = readJournalValues(journal);
        String version = values.get("version");
        boolean legacy = "2".equals(version);
        if (!legacy && !"3".equals(version)) {
            throw new IOException("Installed Datapack Transaction Marker Version Is Invalid: " + journal);
        }
        String world = requiredJournalField(values, "world", journal);
        String pack = requiredJournalField(values, "pack", journal);
        String phase = requiredJournalField(values, "phase", journal);
        String owner = requiredJournalField(values, "owner", journal);
        String projectId = requiredJournalField(values, "projectId", journal);
        String buildRevision = requiredJournalField(values, "buildRevision", journal);
        String sourceName = requiredJournalField(values, "source", journal);
        String sourceHash = requiredJournalField(values, "sourceHash", journal);
        String targetHash = requiredJournalField(values, "targetHash", journal);
        String previousTargetExists = requiredJournalField(values, "previousTargetExists", journal);
        String previousTargetHash = requiredJournalField(values, "previousTargetHash", journal);
        String previousEnabledKnown = requiredJournalField(values, "previousEnabledKnown", journal);
        String previousEnabled = requiredJournalField(values, "previousEnabled", journal);
        String targetName = requiredJournalField(values, "target", journal);
        String stageName = requiredJournalField(values, "stage", journal);
        String backupName = requiredJournalField(values, "backup", journal);
        requireProjectId(projectId);
        requireWorldName(world);
        requirePackName(pack);
        long revision = parseRevision(buildRevision, journal);
        requireHash(sourceHash, "sourceHash", journal);
        requireHash(targetHash, "targetHash", journal);
        boolean hadTarget = parseBoolean(previousTargetExists, "previousTargetExists", journal);
        if (hadTarget) {
            requireHash(previousTargetHash, "previousTargetHash", journal);
        } else if (!previousTargetHash.isBlank()) {
            throw new IOException("Installed Datapack Previous Target Hash Is Invalid: " + journal);
        }
        boolean activationKnown = parseBoolean(previousEnabledKnown, "previousEnabledKnown", journal);
        String previousEnabledPresentValue = legacy ? values.get("previousEnabledPresent")
            : requiredJournalField(values, "previousEnabledPresent", journal);
        boolean activationPresent = previousEnabledPresentValue == null
            ? activationKnown
            : parseBoolean(previousEnabledPresentValue, "previousEnabledPresent", journal);
        boolean wasEnabled = parseBoolean(previousEnabled, "previousEnabled", journal);
        if ((!activationKnown && (activationPresent || wasEnabled)) || (!activationPresent && wasEnabled)) {
            throw new IOException("Installed Datapack Previous Activation State Is Inconsistent: " + journal);
        }
        Path source = absoluteJournalPath(sourceName, "source", journal);
        String actualWorld = datapacks.getParent() == null || datapacks.getParent().getFileName() == null
            ? "" : datapacks.getParent().getFileName().toString();
        if (!world.equals(actualWorld)) {
            throw new IOException("Installed Datapack Transaction World Does Not Match Its Directory");
        }
        if (!Phase.isKnown(phase)) {
            throw new IOException("Installed Datapack Transaction Phase Is Invalid: " + journal);
        }
        String journalName = journal.getFileName().toString();
        if (journalName.length() != JOURNAL_PREFIX.length() + 32 + JOURNAL_SUFFIX.length()) {
            throw new IOException("Installed Datapack Transaction Marker Name Is Invalid: " + journal);
        }
        String token = journalName.substring(JOURNAL_PREFIX.length(), JOURNAL_PREFIX.length() + 32);
        if (!token.matches("[0-9a-f]{32}")) {
            throw new IOException("Installed Datapack Transaction Marker Token Is Invalid: " + journal);
        }
        if (!stageName.equals(STAGE_PREFIX + token + "-" + pack)) {
            throw new IOException("Installed Datapack Transaction Staging Path Is Invalid: " + journal);
        }
        if (!backupName.equals(BACKUP_PREFIX + token + "-" + pack)) {
            throw new IOException("Installed Datapack Transaction Backup Path Is Invalid: " + journal);
        }
        Path target = absoluteJournalPath(targetName, "target", journal);
        Path expectedTarget = datapacks.resolve(pack).toAbsolutePath().normalize();
        if (!samePathString(target, expectedTarget)) {
            throw new IOException("Installed Datapack Transaction Target Path Is Invalid: " + journal);
        }
        Path stage = datapacks.resolve(stageName).normalize();
        Path backup = datapacks.resolve(backupName).normalize();
        requireInside(datapacks, target, "transaction target");
        requireInside(datapacks, stage, "transaction staging");
        requireInside(datapacks, backup, "transaction backup");
        ActivationState previousActivation = activationKnown
            ? new ActivationState(true, activationPresent, activationPresent && wasEnabled)
            : ActivationState.unknown();
        Path retiredRoot = datapacks.resolve(RETIRED_PREFIX + token).normalize();
        requireInside(datapacks, retiredRoot, "transaction retirement");
        List<RetiredPack> retiredPacks = legacy ? List.of() : readRetiredPacks(values, datapacks, retiredRoot, journal);
        for (RetiredPack retiredPack : retiredPacks) {
            if (!projectId.equals(retiredPack.projectId()) || retiredPack.packName().equals(pack)
                || retiredPack.revision() >= revision) {
                throw new IOException("Installed Datapack Retired Identity Does Not Match Its Revision: " + journal);
            }
        }
        String previousTargetFileKey = legacy ? values.getOrDefault("previousTargetFileKey", "")
            : requiredJournalField(values, "previousTargetFileKey", journal);
        if (!legacy && hadTarget && previousTargetFileKey.isBlank()) {
            throw new IOException("Installed Datapack Previous Target File Identity Is Missing: " + journal);
        }
        if (!hadTarget && !previousTargetFileKey.isBlank()) {
            throw new IOException("Installed Datapack Previous Target File Identity Is Invalid: " + journal);
        }
        JournalTopology topology = legacy
            ? legacyJournalTopology(datapacks, source, target, stage, backup, retiredRoot)
            : readJournalTopology(values, datapacks, source, target, stage, backup, retiredRoot, journal);
        if (!legacy) {
            String recoveryState = requiredJournalField(values, "recoveryState", journal);
            if (!("COMMIT".equals(recoveryState) || "ROLLBACK".equals(recoveryState))) {
                throw new IOException("Installed Datapack Transaction Recovery State Is Invalid: " + journal);
            }
            if (phase.equals(Phase.ACTIVATED.name()) != "COMMIT".equals(recoveryState)) {
                throw new IOException("Installed Datapack Transaction Recovery State Does Not Match Its Phase: " + journal);
            }
        }
        return new JournalTransaction(new Transaction(datapacks, target, stage, backup, journal, world, pack, owner, projectId,
            revision, source, sourceHash, targetHash, hadTarget, previousTargetHash, previousTargetFileKey, previousActivation,
            retiredRoot, retiredPacks), Phase.valueOf(phase), topology);
    }

    private Map<String, String> readJournalValues(Path journal) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || values.put(line.substring(0, separator), line.substring(separator + 1)) != null) {
                throw new IOException("Installed Datapack Transaction Marker Is Malformed: " + journal);
            }
        }
        return values;
    }

    private String requiredJournalField(Map<String, String> values, String field, Path journal) throws IOException {
        String value = values.get(field);
        if (value == null) {
            throw new IOException("Installed Datapack Transaction Marker Is Incomplete: " + journal);
        }
        requireJournalValue(value, field);
        return value;
    }

    private Path absoluteJournalPath(String value, String field, Path journal) throws IOException {
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException exception) {
            throw new IOException("Installed Datapack Transaction " + field + " Path Is Invalid: " + journal, exception);
        }
        if (!path.isAbsolute()) {
            throw new IOException("Installed Datapack Transaction " + field + " Path Is Not Absolute: " + journal);
        }
        return path.toAbsolutePath().normalize();
    }

    private JournalTopology legacyJournalTopology(Path datapacks, Path source, Path target, Path stage,
                                                  Path backup, Path retiredRoot) throws IOException {
        return new JournalTopology(canonicalPath(datapacks), fileKey(datapacks), canonicalPath(source), fileKey(source),
            canonicalPathExpected(target), fileKeyIfPresent(target), canonicalPathExpected(stage), fileKeyIfPresent(stage),
            canonicalPathExpected(backup), fileKeyIfPresent(backup), canonicalPathExpected(retiredRoot),
            fileKeyIfPresent(retiredRoot));
    }

    private JournalTopology readJournalTopology(Map<String, String> values, Path datapacks, Path source, Path target,
                                                Path stage, Path backup, Path retiredRoot, Path journal) throws IOException {
        String datapacksCanonical = requiredJournalField(values, "datapacks", journal);
        String datapacksFileKey = requiredJournalField(values, "datapacksFileKey", journal);
        if (datapacksFileKey.isBlank()) {
            throw new IOException("Installed Datapack Directory File Identity Is Missing: " + journal);
        }
        String sourceCanonical = requiredJournalField(values, "sourceCanonical", journal);
        String sourceFileKey = requiredJournalField(values, "sourceFileKey", journal);
        if (sourceFileKey.isBlank()) {
            throw new IOException("Installed Datapack Source File Identity Is Missing: " + journal);
        }
        String targetCanonical = requiredJournalField(values, "targetCanonical", journal);
        String targetFileKey = requiredJournalField(values, "targetFileKey", journal);
        String stageCanonical = requiredJournalField(values, "stageCanonical", journal);
        String stageFileKey = requiredJournalField(values, "stageFileKey", journal);
        String backupCanonical = requiredJournalField(values, "backupCanonical", journal);
        String backupFileKey = requiredJournalField(values, "backupFileKey", journal);
        String retiredRootCanonical = requiredJournalField(values, "retiredRootCanonical", journal);
        String retiredRootFileKey = requiredJournalField(values, "retiredRootFileKey", journal);
        requireCanonicalValue(datapacks, datapacksCanonical, "datapacks", journal);
        requireCanonicalValue(source, sourceCanonical, "source", journal);
        requireCanonicalExpected(target, targetCanonical, "target", journal);
        requireCanonicalExpected(stage, stageCanonical, "stage", journal);
        requireCanonicalExpected(backup, backupCanonical, "backup", journal);
        requireCanonicalExpected(retiredRoot, retiredRootCanonical, "retiredRoot", journal);
        return new JournalTopology(datapacksCanonical, datapacksFileKey, sourceCanonical, sourceFileKey,
            targetCanonical, targetFileKey, stageCanonical, stageFileKey, backupCanonical, backupFileKey,
            retiredRootCanonical, retiredRootFileKey);
    }

    private List<RetiredPack> readRetiredPacks(Map<String, String> values, Path datapacks, Path retiredRoot,
                                               Path journal) throws IOException {
        String countValue = requiredJournalField(values, "retiredCount", journal);
        int count;
        try {
            count = Integer.parseInt(countValue);
        } catch (NumberFormatException exception) {
            throw new IOException("Installed Datapack Retired Count Is Invalid: " + journal, exception);
        }
        if (count < 0 || count > 10000) {
            throw new IOException("Installed Datapack Retired Count Is Invalid: " + journal);
        }
        List<RetiredPack> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String prefix = "retired." + index + ".";
            String projectId = requiredJournalField(values, prefix + "projectId", journal);
            String packName = requiredJournalField(values, prefix + "packName", journal);
            long revision = parseRevision(requiredJournalField(values, prefix + "revision", journal), journal);
            requireProjectId(projectId);
            requirePackName(packName);
            if (!names.add(packName)) {
                throw new IOException("Installed Datapack Retired Identity Is Duplicated: " + journal);
            }
            String originalPathValue = requiredJournalField(values, prefix + "originalPath", journal);
            String retirementPathValue = requiredJournalField(values, prefix + "retirementPath", journal);
            Path original = absoluteJournalPath(originalPathValue, prefix + "originalPath", journal);
            Path retirement = absoluteJournalPath(retirementPathValue, prefix + "retirementPath", journal);
            requireInside(datapacks, original, "retired original");
            requireInside(retiredRoot, retirement, "retired retirement");
            requireCanonicalExpected(original, originalPathValue, prefix + "originalPath", journal);
            requireCanonicalExpected(retirement, retirementPathValue, prefix + "retirementPath", journal);
            if (!packName.equals(original.getFileName().toString()) || !packName.equals(retirement.getFileName().toString())) {
                throw new IOException("Installed Datapack Retired Identity Path Does Not Match Its Name: " + journal);
            }
            String originalFileKey = requiredJournalField(values, prefix + "originalFileKey", journal);
            String retirementFileKey = requiredJournalField(values, prefix + "retirementFileKey", journal);
            if (originalFileKey.isBlank() || retirementFileKey.isBlank()) {
                throw new IOException("Installed Datapack Retired File Identity Is Missing: " + journal);
            }
            if (!originalFileKey.equals(retirementFileKey)) {
                throw new IOException("Installed Datapack Retired File Identity Changed: " + journal);
            }
            String hash = requiredJournalField(values, prefix + "hash", journal);
            requireHash(hash, prefix + "hash", journal);
            boolean activationKnown = parseBoolean(requiredJournalField(values, prefix + "previousEnabledKnown", journal),
                prefix + "previousEnabledKnown", journal);
            boolean activationPresent = parseBoolean(requiredJournalField(values, prefix + "previousEnabledPresent", journal),
                prefix + "previousEnabledPresent", journal);
            boolean enabled = parseBoolean(requiredJournalField(values, prefix + "previousEnabled", journal),
                prefix + "previousEnabled", journal);
            if (!activationKnown || (!activationPresent && enabled)) {
                throw new IOException("Installed Datapack Retired Activation State Is Inconsistent: " + journal);
            }
            String stateValue = requiredJournalField(values, prefix + "state", journal);
            if (!RetirementState.isKnown(stateValue)) {
                throw new IOException("Installed Datapack Retired State Is Invalid: " + journal);
            }
            result.add(new RetiredPack(packName, projectId, revision, original, retirement, hash,
                activationKnown ? new ActivationState(true, activationPresent, activationPresent && enabled) : ActivationState.unknown(),
                originalFileKey, retirementFileKey, RetirementState.valueOf(stateValue)));
        }
        return List.copyOf(result);
    }

    private void requireCanonicalValue(Path path, String expected, String field, Path journal) throws IOException {
        if (!expected.equals(canonicalPath(path))) {
            throw new IOException("Installed Datapack Transaction " + field + " Canonical Path Changed: " + journal);
        }
    }

    private void requireCanonicalExpected(Path path, String expected, String field, Path journal) throws IOException {
        if (!expected.equals(canonicalPathExpected(path))) {
            throw new IOException("Installed Datapack Transaction " + field + " Canonical Path Changed: " + journal);
        }
    }

    private boolean samePathString(Path first, Path second) {
        return first.toAbsolutePath().normalize().toString().equals(second.toAbsolutePath().normalize().toString());
    }

    private void writeBootstrap(Transaction transaction) throws IOException {
        Path bootstrap = bootstrapPath(transaction.journal());
        Path temporary = bootstrap.resolveSibling(bootstrap.getFileName() + BOOTSTRAP_TEMP_SUFFIX);
        requireJournalParent(transaction.datapacks(), bootstrap);
        if (Files.exists(bootstrap, LinkOption.NOFOLLOW_LINKS) || Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installed Datapack Bootstrap Marker Already Exists");
        }
        JournalTopology topology = journalTopology(transaction);
        StringBuilder value = new StringBuilder();
        appendJournal(value, "version", "1");
        appendJournal(value, "owner", transaction.owner());
        appendJournal(value, "world", transaction.worldName());
        appendJournal(value, "pack", transaction.packName());
        appendJournal(value, "projectId", transaction.projectId());
        appendJournal(value, "buildRevision", Long.toString(transaction.buildRevision()));
        appendJournal(value, "sourceHash", transaction.sourceHash());
        appendJournal(value, "datapacks", topology.datapacksCanonical());
        appendJournal(value, "datapacksFileKey", topology.datapacksFileKey());
        appendJournal(value, "target", topology.targetCanonical());
        appendJournal(value, "targetCanonical", topology.targetCanonical());
        appendJournal(value, "targetFileKey", topology.targetFileKey());
        appendJournal(value, "previousTargetExists", Boolean.toString(transaction.previousTargetExists()));
        appendJournal(value, "previousTargetHash", transaction.previousTargetHash());
        appendJournal(value, "previousTargetFileKey", transaction.previousTargetFileKey());
        appendJournal(value, "stage", transaction.stage().getFileName().toString());
        appendJournal(value, "stageCanonical", topology.stageCanonical());
        appendJournal(value, "stageFileKey", topology.stageFileKey());
        appendJournal(value, "phase", Phase.PREPARED.name());
        Files.writeString(temporary, value.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        forcePath(temporary);
        replaceAtomic(temporary, bootstrap);
        forceDirectory(transaction.datapacks());
    }

    private Path bootstrapPath(Path journal) throws IOException {
        Path normalized = requireAbsolutePath(journal, "journal");
        Path fileName = normalized.getFileName();
        if (fileName == null || !isJournalName(fileName.toString())) {
            throw new IOException("Installed Datapack Transaction Marker Name Is Invalid: " + journal);
        }
        return normalized.resolveSibling(fileName + BOOTSTRAP_SUFFIX);
    }

    private void deleteBootstrap(Path journal, Path datapacks) throws IOException {
        Path bootstrap = bootstrapPath(journal);
        Path temporary = bootstrap.resolveSibling(bootstrap.getFileName() + BOOTSTRAP_TEMP_SUFFIX);
        boolean deleted = false;
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            deleteFile(temporary, "temporary bootstrap marker");
            deleted = true;
        }
        if (Files.exists(bootstrap, LinkOption.NOFOLLOW_LINKS)) {
            deleteFile(bootstrap, "bootstrap marker");
            deleted = true;
        }
        if (deleted) {
            forceDirectory(datapacks);
        }
    }

    private void writeJournal(Transaction transaction, Phase phase) throws IOException {
        if (Files.isSymbolicLink(transaction.journal())) {
            throw new IOException("Installed Datapack Transaction Marker Is A Symbolic Link");
        }
        requireJournalValue(transaction.owner(), "owner");
        requireJournalValue(transaction.worldName(), "world");
        requireJournalValue(transaction.packName(), "pack");
        requireJournalValue(transaction.projectId(), "projectId");
        JournalTopology topology = journalTopology(transaction);
        String source = topology.sourceCanonical();
        String target = topology.targetCanonical();
        String stage = topology.stageCanonical();
        String backup = topology.backupCanonical();
        String retiredRoot = topology.retiredRootCanonical();
        requireJournalValue(source, "source");
        requireJournalValue(target, "target");
        requireJournalValue(stage, "stageCanonical");
        requireJournalValue(backup, "backupCanonical");
        requireJournalValue(retiredRoot, "retiredRootCanonical");
        StringBuilder value = new StringBuilder();
        appendJournal(value, "version", "3");
        appendJournal(value, "owner", transaction.owner());
        appendJournal(value, "world", transaction.worldName());
        appendJournal(value, "pack", transaction.packName());
        appendJournal(value, "projectId", transaction.projectId());
        appendJournal(value, "buildRevision", Long.toString(transaction.buildRevision()));
        appendJournal(value, "source", source);
        appendJournal(value, "sourceCanonical", source);
        appendJournal(value, "sourceHash", transaction.sourceHash());
        appendJournal(value, "sourceFileKey", topology.sourceFileKey());
        appendJournal(value, "targetHash", transaction.targetHash());
        appendJournal(value, "previousTargetExists", Boolean.toString(transaction.previousTargetExists()));
        appendJournal(value, "previousTargetHash", transaction.previousTargetHash());
        appendJournal(value, "previousTargetFileKey", transaction.previousTargetFileKey());
        appendJournal(value, "previousEnabledKnown", Boolean.toString(transaction.previousActivation().known()));
        appendJournal(value, "previousEnabledPresent", Boolean.toString(transaction.previousActivation().present()));
        appendJournal(value, "previousEnabled", Boolean.toString(transaction.previousActivation().enabled()));
        appendJournal(value, "recoveryState", phase == Phase.ACTIVATED ? "COMMIT" : "ROLLBACK");
        appendJournal(value, "datapacks", topology.datapacksCanonical());
        appendJournal(value, "datapacksFileKey", topology.datapacksFileKey());
        appendJournal(value, "target", target);
        appendJournal(value, "targetCanonical", target);
        appendJournal(value, "targetFileKey", topology.targetFileKey());
        appendJournal(value, "stage", transaction.stage().getFileName().toString());
        appendJournal(value, "stageCanonical", stage);
        appendJournal(value, "stageFileKey", topology.stageFileKey());
        appendJournal(value, "backup", transaction.backup().getFileName().toString());
        appendJournal(value, "backupCanonical", backup);
        appendJournal(value, "backupFileKey", topology.backupFileKey());
        appendJournal(value, "retiredRoot", transaction.retiredRoot().getFileName().toString());
        appendJournal(value, "retiredRootCanonical", retiredRoot);
        appendJournal(value, "retiredRootFileKey", topology.retiredRootFileKey());
        appendJournal(value, "retiredCount", Integer.toString(transaction.retiredPacks().size()));
        for (int index = 0; index < transaction.retiredPacks().size(); index++) {
            RetiredPack retiredPack = transaction.retiredPacks().get(index);
            String prefix = "retired." + index + ".";
            appendJournal(value, prefix + "projectId", retiredPack.projectId());
            appendJournal(value, prefix + "packName", retiredPack.packName());
            appendJournal(value, prefix + "revision", Long.toString(retiredPack.revision()));
            appendJournal(value, prefix + "originalPath", canonicalPathExpected(retiredPack.originalTarget()));
            appendJournal(value, prefix + "originalFileKey", retiredPack.originalFileKey());
            appendJournal(value, prefix + "retirementPath", canonicalPathExpected(retiredPack.backupTarget()));
            appendJournal(value, prefix + "retirementFileKey", retiredPack.backupFileKey());
            appendJournal(value, prefix + "hash", retiredPack.hash());
            appendJournal(value, prefix + "previousEnabledKnown", Boolean.toString(retiredPack.previousActivation().known()));
            appendJournal(value, prefix + "previousEnabledPresent", Boolean.toString(retiredPack.previousActivation().present()));
            appendJournal(value, prefix + "previousEnabled", Boolean.toString(retiredPack.previousActivation().enabled()));
            appendJournal(value, prefix + "state", retiredPack.state().name());
        }
        appendJournal(value, "phase", phase.name());
        Path temp = transaction.journal().resolveSibling(transaction.journal().getFileName() + JOURNAL_TEMP_SUFFIX);
        requireJournalParent(transaction.datapacks(), transaction.journal());
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installed Datapack Transaction Temporary Marker Already Exists");
        }
        Files.writeString(temp, value.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        forcePath(temp);
        replaceAtomic(temp, transaction.journal());
        forceDirectory(transaction.datapacks());
    }

    private void appendJournal(StringBuilder value, String field, String content) throws IOException {
        requireJournalValue(content, field);
        value.append(field).append('=').append(content).append('\n');
    }

    private void replaceAtomic(Path source, Path target) throws IOException {
        validateDestructiveSource(source, "journal temporary marker");
        validateDestructiveParent(target, "journal marker");
        moveEntry(source, target, true, "", "journal marker replacement");
    }

    private void requireJournalValue(String value, String field) throws IOException {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("Installed Datapack Transaction " + field + " Is Not A Single-Line Value");
        }
    }

    private JournalTopology journalTopology(Transaction transaction) throws IOException {
        return new JournalTopology(
            canonicalPath(transaction.datapacks()),
            fileKey(transaction.datapacks()),
            canonicalPath(transaction.source()),
            fileKey(transaction.source()),
            canonicalPathExpected(transaction.target()),
            fileKeyIfPresent(transaction.target()),
            canonicalPathExpected(transaction.stage()),
            fileKeyIfPresent(transaction.stage()),
            canonicalPathExpected(transaction.backup()),
            fileKeyIfPresent(transaction.backup()),
            canonicalPathExpected(transaction.retiredRoot()),
            fileKeyIfPresent(transaction.retiredRoot())
        );
    }

    private String canonicalPath(Path path) throws IOException {
        Path normalized = requireAbsolutePath(path, "path");
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Installed Datapack Path Is A Symbolic Link: " + normalized);
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installed Datapack Path Is Missing: " + normalized);
        }
        if (isWindows()) {
            WindowsFileIdentity.Observation observation = windowsObservation(normalized);
            return observation.path().toString();
        }
        requireNoReparsePoint(normalized);
        Path real;
        try {
            real = normalized.toRealPath();
        } catch (IOException exception) {
            throw new IOException("Installed Datapack Path Cannot Be Canonicalized: " + normalized, exception);
        }
        if (!real.toString().equals(normalized.toString())) {
            throw new IOException("Installed Datapack Path Has An Alias Or Reparse Point: " + normalized);
        }
        return real.toString();
    }

    private String canonicalPathExpected(Path path) throws IOException {
        Path normalized = requireAbsolutePath(path, "path");
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return canonicalPath(normalized);
        }
        List<String> missing = new ArrayList<>();
        Path existing = normalized;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name == null || existing.getParent() == null) {
                throw new IOException("Installed Datapack Path Has No Existing Parent: " + normalized);
            }
            missing.add(name.toString());
            existing = existing.getParent();
        }
        String canonical = canonicalPath(existing);
        for (int index = missing.size() - 1; index >= 0; index--) {
            canonical = Path.of(canonical).resolve(missing.get(index)).toString();
        }
        if (canonical.isBlank()) {
            throw new IOException("Installed Datapack Path Has No Parent: " + normalized);
        }
        return canonical;
    }

    private void requireNoReparsePoint(Path path) throws IOException {
        if (!isWindows()) {
            return;
        }
        WindowsFileIdentity.Observation observation = windowsObservation(path);
        if (observation.isReparsePoint()) {
            throw new IOException("Installed Datapack Windows Reparse Point Is Not Allowed: " + path);
        }
    }

    private WindowsFileIdentity.Observation windowsObservation(Path path) throws IOException {
        Path normalized = requireAbsolutePath(path, "path");
        WindowsFileIdentity.Observation observation = windowsFileMutation.observe(normalized);
        if (!normalized.toString().equals(observation.path().toString())) {
            throw new IOException("Installed Datapack Windows Final Path Changed: " + normalized);
        }
        if (observation.isReparsePoint()) {
            throw new IOException("Installed Datapack Windows Reparse Point Is Not Allowed: " + normalized);
        }
        return observation;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private Path requireAbsolutePath(Path path, String field) throws IOException {
        if (path == null) {
            throw new IOException("Installed Datapack " + field + " Is Missing");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.isAbsolute()) {
            throw new IOException("Installed Datapack " + field + " Is Not Absolute");
        }
        return normalized;
    }

    private String fileKey(Path path) throws IOException {
        Path normalized = requireAbsolutePath(path, "path");
        if (Files.isSymbolicLink(normalized) || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installed Datapack Path Is Not A Stable File: " + normalized);
        }
        if (isWindows()) {
            windowsObservation(normalized);
            BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (attributes.fileKey() != null) {
                return attributes.fileKey().toString();
            }
            return windowsFileKey(windowsObservation(normalized).fileIdInfo());
        }
        requireNoReparsePoint(normalized);
        BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return fileKey(attributes);
    }

    private String fileKeyIfPresent(Path path) throws IOException {
        Path normalized = requireAbsolutePath(path, "path");
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        return fileKey(normalized);
    }

    private String fileKey(BasicFileAttributes attributes) throws IOException {
        if (attributes.fileKey() != null) {
            return attributes.fileKey().toString();
        }
        throw new IOException("Installed Datapack Unique File Identity Is Unavailable");
    }

    private String windowsFileKey(WindowsFileIdentity.FileIdInfo identity) throws IOException {
        if (identity == null) {
            throw new IOException("Installed Datapack Windows Unique File Identity Is Unavailable");
        }
        return "windows:" + Long.toUnsignedString(identity.volumeSerialNumber()) + ':'
            + HexFormat.of().formatHex(identity.identifier()) + ':' + identity.fallback();
    }

    private String legacyFileKey(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.fileKey() != null) {
            return attributes.fileKey().toString();
        }
        String type = attributes.isDirectory() ? "directory" : attributes.isRegularFile() ? "file" : "other";
        return "fallback:" + type + ':' + attributes.creationTime().toMillis();
    }

    private void requireJournalParent(Path datapacks, Path journal) throws IOException {
        Path parent = journal.getParent();
        if (parent == null || !parent.equals(datapacks.toAbsolutePath().normalize())) {
            throw new IOException("Installed Datapack Transaction Marker Parent Is Invalid");
        }
        canonicalPath(datapacks);
        if (Files.isSymbolicLink(journal)) {
            throw new IOException("Installed Datapack Transaction Marker Is A Symbolic Link");
        }
    }

    private void deleteJournal(Path journal, Path datapacks) throws IOException {
        Path temporary = journal.resolveSibling(journal.getFileName() + JOURNAL_TEMP_SUFFIX);
        boolean deleted = false;
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            deleteJournalTemporary(temporary);
            deleted = true;
        }
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Installed Datapack Transaction Marker Is Not A File");
            }
            validateDestructiveSource(journal, "transaction marker");
            validateDestructiveParent(journal, "transaction marker parent");
            canonicalPath(journal);
            canonicalPath(journal.getParent());
            deleteFile(journal, "transaction marker");
            deleted = true;
        }
        if (deleted) {
            forceDirectory(datapacks);
        }
    }

    private void validateDestructiveSource(Path source, String field) throws IOException {
        if (source == null || Files.isSymbolicLink(source) || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installed Datapack " + field + " Is Invalid: " + source);
        }
        canonicalPath(source);
    }

    private void validateDestructiveParent(Path target, String field) throws IOException {
        if (target == null || target.getParent() == null) {
            throw new IOException("Installed Datapack " + field + " Has No Stable Parent");
        }
        canonicalPath(target.getParent());
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Installed Datapack " + field + " Is A Symbolic Link: " + target);
        }
    }

    private void deleteFile(Path file, String field) throws IOException {
        validateDestructiveSource(file, field);
        Path parent = file.getParent();
        Path name = file.getFileName();
        if (parent == null || name == null) {
            throw new IOException("Installed Datapack " + field + " Has No Stable Parent");
        }
        if (isWindows()) {
            WindowsFileIdentity.Observation observation = windowsObservation(file);
            if (!observation.isRegularFile()) {
                throw new IOException("Installed Datapack " + field + " Is Not A Regular File: " + file);
            }
            windowsFileMutation.deleteFile(file);
            return;
        }
        try (SecureDirectoryStream<Path> directory = openSecureDirectory(parent, field + " parent")) {
            BasicFileAttributes attributes = secureAttributes(directory, name, field);
            requireMovableEntry(attributes, file, field);
            String key = fileKey(attributes);
            mutationObserver.beforeMutation(file, null);
            requireSecureEntry(directory, name, key, field);
            directory.deleteFile(name);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("Installed Datapack Secure Delete Is Unavailable: " + field, exception);
        }
    }

    private void moveAtomic(Path source, Path target) throws IOException {
        moveAtomic(source, target, "");
    }

    private void moveAtomic(Path source, Path target, String expectedSourceFileKey) throws IOException {
        moveEntry(source, target, false, expectedSourceFileKey, "datapack move");
    }

    private void moveEntry(Path source, Path target, boolean replaceExisting, String expectedSourceFileKey,
                            String field) throws IOException {
        validateDestructiveSource(source, "move source");
        validateDestructiveParent(target, "move destination");
        Path sourceParent = source.getParent();
        Path targetParent = target.getParent();
        Path sourceName = source.getFileName();
        Path targetName = target.getFileName();
        if (sourceParent == null || targetParent == null || sourceName == null || targetName == null) {
            throw new IOException("Installed Datapack Move Has No Stable Parent");
        }
        if (isWindows()) {
            String sourceKey = fileKey(source);
            if (!expectedSourceFileKey.isBlank() && !matchesFileKey(source, expectedSourceFileKey, sourceKey)) {
                throw new IOException("Installed Datapack move source File Identity Changed: " + target);
            }
            mutationObserver.beforeMutation(source, target);
            if (replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                validateDestructiveSource(target, field + " destination");
                deleteFile(target, field + " destination");
            }
            windowsFileMutation.renameNoReplace(source, target);
            return;
        }
        try (SecureDirectoryStream<Path> sourceDirectory = openSecureDirectory(sourceParent, "move source parent");
             SecureDirectoryStream<Path> targetDirectory = openSecureDirectory(targetParent, "move destination parent")) {
            BasicFileAttributes sourceAttributes = secureAttributes(sourceDirectory, sourceName, field + " source");
            requireMovableEntry(sourceAttributes, source, field + " source");
            String sourceKey = fileKey(sourceAttributes);
            if (!expectedSourceFileKey.isBlank() && !expectedSourceFileKey.equals(sourceKey)) {
                throw new IOException("Installed Datapack move source File Identity Changed: " + target);
            }
            BasicFileAttributes targetAttributes = secureOptionalAttributes(targetDirectory, targetName);
            String targetKey = "";
            if (targetAttributes != null) {
                requireMovableEntry(targetAttributes, target, field + " destination");
                requireNoReparsePoint(target);
                targetKey = fileKey(targetAttributes);
                if (!replaceExisting) {
                    throw new IOException("Installed Datapack Move Destination Already Exists: " + target);
                }
            }
            mutationObserver.beforeMutation(source, target);
            requireSecureEntry(sourceDirectory, sourceName, sourceKey, field + " source");
            if (targetAttributes == null) {
                if (secureOptionalAttributes(targetDirectory, targetName) != null) {
                    throw new IOException("Installed Datapack Move Destination Appeared: " + target);
                }
            } else {
                requireSecureEntry(targetDirectory, targetName, targetKey, field + " destination");
            }
            try {
                sourceDirectory.move(sourceName, targetDirectory, targetName);
            } catch (UnsupportedOperationException exception) {
                throw new IOException("Installed Datapack Secure Move Is Unavailable: " + field, exception);
            }
        }
    }

    private void requireMovableEntry(BasicFileAttributes attributes, Path path, String field) throws IOException {
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
            || (!attributes.isDirectory() && !attributes.isRegularFile())) {
            throw new IOException("Installed Datapack " + field + " Is Invalid: " + path);
        }
    }

    private BasicFileAttributes secureAttributes(SecureDirectoryStream<Path> directory, Path name, String field)
        throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Installed Datapack Secure Attributes Are Unavailable: " + field);
        }
        try {
            return view.readAttributes();
        } catch (NoSuchFileException exception) {
            throw new IOException("Installed Datapack " + field + " Is Missing", exception);
        }
    }

    private BasicFileAttributes secureOptionalAttributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Installed Datapack Secure Attributes Are Unavailable");
        }
        try {
            return view.readAttributes();
        } catch (NoSuchFileException exception) {
            return null;
        }
    }

    private void requireSecureEntry(SecureDirectoryStream<Path> directory, Path name, String expectedKey, String field)
        throws IOException {
        BasicFileAttributes attributes = secureAttributes(directory, name, field);
        requireMovableEntry(attributes, name, field);
        if (!expectedKey.equals(fileKey(attributes))) {
            throw new IOException("Installed Datapack " + field + " File Identity Changed");
        }
    }

    @SuppressWarnings("unchecked")
    private SecureDirectoryStream<Path> openSecureDirectory(Path directory, String field) throws IOException {
        Path normalized = requireAbsolutePath(directory, field);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Installed Datapack " + field + " Is Not A Directory: " + normalized);
        }
        canonicalPath(normalized);
        DirectoryStream<Path> stream = Files.newDirectoryStream(normalized);
        if (!(stream instanceof SecureDirectoryStream<?>)) {
            stream.close();
            throw new IOException("Installed Datapack Secure Directory Operations Are Unavailable: " + normalized);
        }
        return (SecureDirectoryStream<Path>) stream;
    }

    private void requireSecureMutationSupport(Path root) throws IOException {
        if (isWindows()) {
            requireWindowsMutation(root);
            return;
        }
        try (SecureDirectoryStream<Path> ignored = openSecureDirectory(root, "mutation root")) {
        }
    }

    private void requireWindowsMutation(Path root) throws IOException {
        if (!windowsFileMutation.available(root)) {
            String reason = windowsFileMutation.unavailableReason(root);
            throw new IOException(reason == null || reason.isBlank()
                ? "Windows File Mutation Is Unavailable" : reason);
        }
        windowsObservation(root);
    }

    private void copyTree(Path source, Path target) throws IOException {
        canonicalPath(source);
        validateDestructiveParent(target, "staging destination");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Datapack Staging Path Already Exists");
        }
        canonicalPath(target.getParent());
        Files.createDirectory(target);
        canonicalPath(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory) || !attributes.isDirectory()) {
                    throw new IOException("Generated Datapack Contains An Invalid Directory");
                }
                canonicalPath(directory);
                if (!directory.equals(source)) {
                    Path destination = target.resolve(source.relativize(directory)).normalize();
                    requireInside(target, destination, "staged directory");
                    requireSafeAncestors(target, destination.getParent() == null ? target : destination.getParent());
                    Files.createDirectory(destination);
                    canonicalPath(destination);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("Generated Datapack Contains An Invalid File");
                }
                canonicalPath(file);
                Path destination = target.resolve(source.relativize(file)).normalize();
                requireInside(target, destination, "staged file");
                requireSafeAncestors(target, destination.getParent() == null ? target : destination.getParent());
                Files.createDirectories(destination.getParent());
                canonicalPath(destination.getParent());
                Files.copy(file, destination, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void validateTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Datapack Tree Is Not A Directory: " + root);
        }
        canonicalPath(root);
        Path metadata = root.resolve("pack.mcmeta").normalize();
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Datapack Metadata Is Missing: " + root);
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(metadata));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("pack")) {
                throw new IOException("Datapack Metadata Is Invalid: " + metadata);
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Datapack Metadata Is Invalid: " + metadata, exception);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory) || !attributes.isDirectory()) {
                    throw new IOException("Datapack Tree Contains An Invalid Directory: " + directory);
                }
                canonicalPath(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("Datapack Tree Contains An Invalid File: " + file);
                }
                canonicalPath(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new IOException("Datapack Tree Cannot Be Inspected: " + file, exception);
            }
        });
    }

    private void validateTreeShape(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Datapack Tree Is Not A Directory: " + root);
        }
        canonicalPath(root);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory) || !attributes.isDirectory()) {
                    throw new IOException("Datapack Tree Contains An Invalid Directory: " + directory);
                }
                canonicalPath(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("Datapack Tree Contains An Invalid File: " + file);
                }
                canonicalPath(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new IOException("Datapack Tree Cannot Be Inspected: " + file, exception);
            }
        });
    }

    private Map<String, String> fingerprintTree(Path root) throws IOException {
        canonicalPath(root);
        Map<String, String> values = new HashMap<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    canonicalPath(path);
                    continue;
                }
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Datapack Tree Contains An Invalid File: " + path);
                }
                canonicalPath(path);
                values.put(root.relativize(path).toString(), sha256(path));
            }
        }
        return values;
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            StringBuilder value = new StringBuilder(64);
            for (byte part : digest.digest()) {
                value.append(String.format(Locale.ROOT, "%02x", part));
            }
            return value.toString();
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Datapack Fingerprint Failed", exception);
        }
    }

    private void forceTree(Path root) throws IOException {
        canonicalPath(root);
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Datapack Tree Contains A Symbolic Link: " + path);
            }
            canonicalPath(path);
            forcePath(path);
        }
    }

    private void forceDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Datapack Directory Is Invalid: " + directory);
        }
        canonicalPath(directory);
        forcePath(directory);
    }

    private void forcePath(Path path) throws IOException {
        if (isWindows()) {
            WindowsFileIdentity.Observation observation = windowsObservation(path);
            if (observation.isDirectory()) {
                windowsFileMutation.flushParent(path);
            } else if (observation.isRegularFile()) {
                windowsFileMutation.flush(path);
            } else {
                throw new IOException("Installed Datapack Path Is Not Flushable: " + path);
            }
            return;
        }
        directoryDurability.force(path);
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        validateDestructiveSource(root, "tree root");
        Path parent = root.getParent();
        Path name = root.getFileName();
        if (parent == null || name == null) {
            throw new IOException("Installed Datapack Tree Root Has No Stable Parent");
        }
        if (isWindows()) {
            WindowsFileIdentity.Observation observation = windowsObservation(root);
            if (!observation.isDirectory()) {
                throw new IOException("Installed Datapack Tree Root Is Not A Directory: " + root);
            }
            windowsFileMutation.deleteTree(root);
            return;
        }
        try (SecureDirectoryStream<Path> directory = openSecureDirectory(parent, "tree parent")) {
            BasicFileAttributes attributes = secureAttributes(directory, name, "tree root");
            requireDirectoryEntry(attributes, root, "tree root");
            String key = fileKey(attributes);
            mutationObserver.beforeMutation(root, null);
            requireSecureDirectoryEntry(directory, name, key, "tree root");
            try (SecureDirectoryStream<Path> tree = openSecureChildDirectory(directory, name, root)) {
                deleteTreeContents(tree, root);
            }
            requireSecureDirectoryEntry(directory, name, key, "tree root");
            directory.deleteDirectory(name);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("Installed Datapack Secure Tree Delete Is Unavailable: " + root, exception);
        }
    }

    private void deleteTreeContents(SecureDirectoryStream<Path> directory, Path logicalRoot) throws IOException {
        try (var entries = directory) {
            for (Path entry : entries) {
                Path name = entry.getFileName();
                if (name == null) {
                    throw new IOException("Installed Datapack Tree Entry Has No Name: " + logicalRoot);
                }
                Path logical = logicalRoot.resolve(name.toString()).normalize();
                BasicFileAttributes attributes = secureAttributes(directory, name, "tree entry");
                requireNoReparsePoint(logical);
                String key = fileKey(attributes);
                if (attributes.isDirectory()) {
                    try (SecureDirectoryStream<Path> child = openSecureChildDirectory(directory, name, logical)) {
                        deleteTreeContents(child, logical);
                    }
                    mutationObserver.beforeMutation(logical, null);
                    requireSecureDirectoryEntry(directory, name, key, "tree directory");
                    directory.deleteDirectory(name);
                } else if (attributes.isRegularFile()) {
                    mutationObserver.beforeMutation(logical, null);
                    requireSecureEntry(directory, name, key, "tree file");
                    directory.deleteFile(name);
                } else {
                    throw new IOException("Datapack Tree Contains An Invalid Entry: " + logical);
                }
            }
        }
    }

    private SecureDirectoryStream<Path> openSecureChildDirectory(SecureDirectoryStream<Path> parent, Path name,
                                                                  Path logical) throws IOException {
        try {
            return parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            throw new IOException("Installed Datapack Tree Directory Is Missing: " + logical, exception);
        }
    }

    private void requireDirectoryEntry(BasicFileAttributes attributes, Path path, String field) throws IOException {
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
            throw new IOException("Installed Datapack " + field + " Is Not A Directory: " + path);
        }
    }

    private void requireSecureDirectoryEntry(SecureDirectoryStream<Path> directory, Path name, String expectedKey,
                                              String field) throws IOException {
        BasicFileAttributes attributes = secureAttributes(directory, name, field);
        requireDirectoryEntry(attributes, name, field);
        if (!expectedKey.equals(fileKey(attributes))) {
            throw new IOException("Installed Datapack " + field + " File Identity Changed");
        }
    }

    private PreviousTarget validateExistingTarget(Path target, WorldGenDatapackBuild build, String packName) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return PreviousTarget.absent();
        }
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Existing Datapack Is Not A ReSync Directory");
        }
        validateTree(target);
        Path manifest = target.resolve("resync-manifest.json").normalize();
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || !isOwnedManifest(manifest, build, packName)) {
            throw new IOException("Existing Datapack Is Not ReSync-Owned");
        }
        return new PreviousTarget(true, treeHash(fingerprintTree(target)), fileKey(target));
    }

    private boolean isOwnedManifest(Path manifest, WorldGenDatapackBuild build, String packName) throws IOException {
        String projectId = requireProjectId(build.getProjectId());
        return isOwnedManifest(manifest, projectId, packName, build.getRevision());
    }

    private boolean isOwnedManifest(Path manifest, String projectId, String packName, long revision) throws IOException {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
            if (!parsed.isJsonObject()) {
                return false;
            }
            JsonObject value = parsed.getAsJsonObject();
            if (!WorldGenInstalledDatapackCapability.OWNER.equals(value.has("owner") ? value.get("owner").getAsString() : "")) {
                return false;
            }
            if (!packName.equals(value.has("packName") ? value.get("packName").getAsString() : "")) {
                return false;
            }
            if (!projectId.isBlank() && !projectId.equals(value.has("projectId") ? value.get("projectId").getAsString() : "")) {
                return false;
            }
            return revision > 0L && value.has("revision") && value.get("revision").getAsLong() == revision;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void validateOwnedTree(Path root, String projectId, String packName, long revision, String expectedHash) throws IOException {
        validateTree(root);
        if (!isOwnedManifest(root.resolve("resync-manifest.json"), projectId, packName, revision)) {
            throw new IOException("Installed Datapack Tree Is Not ReSync-Owned: " + root);
        }
        String actualHash = treeHash(fingerprintTree(root));
        if (!expectedHash.equals(actualHash)) {
            throw new IOException("Installed Datapack Tree Hash Does Not Match Its Transaction: " + root);
        }
    }

    private void validatePreviousTarget(Path root, Transaction transaction) throws IOException {
        validateOwnedTree(root, transaction.projectId(), transaction.packName(), transaction.buildRevision(),
            transaction.previousTargetHash());
        requireFileKey(root, transaction.previousTargetFileKey(), "previous target", transaction.journal());
    }

    private List<RetiredPack> findPriorRevisions(Path datapacks, String projectId, String currentPackName,
                                                 long currentRevision, String worldName) throws IOException {
        if (!Files.isDirectory(datapacks, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<RetiredPack> result = new ArrayList<>();
        try (var stream = Files.list(datapacks)) {
            for (Path candidate : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                String name = candidate.getFileName().toString();
                if (name.equals(currentPackName) || name.startsWith(STAGE_PREFIX) || name.startsWith(BACKUP_PREFIX)
                    || name.startsWith(RETIRED_PREFIX) || name.startsWith(JOURNAL_PREFIX)) {
                    continue;
                }
                if (Files.isSymbolicLink(candidate)) {
                    throw new IOException("Installed Prior Datapack Is A Symbolic Link: " + candidate);
                }
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                canonicalPath(candidate);
                ManifestIdentity identity = readManifestIdentity(candidate);
                if (identity == null) {
                    continue;
                }
                if (!projectId.equals(identity.projectId())) {
                    continue;
                }
                if (!name.equals(identity.packName())) {
                    throw new IOException("Installed Prior Datapack Identity Does Not Match Its Directory: " + candidate);
                }
                if (identity.revision() <= 0L) {
                    throw new IOException("Installed Prior Datapack Revision Is Invalid: " + candidate);
                }
                if (identity.revision() >= currentRevision) {
                    continue;
                }
                validateRetiredPack(candidate, new RetiredPack(identity.packName(), identity.projectId(), identity.revision(),
                    candidate, null, treeHash(fingerprintTree(candidate)), ActivationState.unknown(), fileKey(candidate), "",
                    RetirementState.ORIGINAL));
                ActivationState activation = packActivator.capture(identity.packName(), worldName);
                if (activation == null || !activation.known()) {
                    throw new IOException("Installed Prior Datapack Previous Enabled State Is Unavailable: " + identity.packName());
                }
                result.add(new RetiredPack(identity.packName(), identity.projectId(), identity.revision(), candidate, null,
                    treeHash(fingerprintTree(candidate)), activation, fileKey(candidate), "", RetirementState.ORIGINAL));
            }
        }
        result.sort(Comparator.comparingLong(RetiredPack::revision).thenComparing(RetiredPack::packName));
        return List.copyOf(result);
    }

    private ManifestIdentity readManifestIdentity(Path root) throws IOException {
        Path manifest = root.resolve("resync-manifest.json").normalize();
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject value = parsed.getAsJsonObject();
            String owner = value.has("owner") && value.get("owner").isJsonPrimitive() ? value.get("owner").getAsString() : "";
            if (!WorldGenInstalledDatapackCapability.OWNER.equals(owner)) {
                return null;
            }
            String projectId = value.has("projectId") && value.get("projectId").isJsonPrimitive()
                ? value.get("projectId").getAsString() : "";
            String packName = value.has("packName") && value.get("packName").isJsonPrimitive()
                ? value.get("packName").getAsString() : "";
            if (projectId.isBlank() || packName.isBlank() || !value.has("revision") || !value.get("revision").isJsonPrimitive()) {
                throw new IOException("Installed Datapack Owner Manifest Is Incomplete: " + root);
            }
            requireProjectId(projectId);
            requirePackName(packName);
            long revision = value.get("revision").getAsLong();
            return new ManifestIdentity(projectId, packName, revision);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Installed Datapack Owner Manifest Is Invalid: " + root, exception);
        }
    }

    private void validateRetiredPack(Path root, RetiredPack retiredPack) throws IOException {
        if (retiredPack == null || retiredPack.projectId() == null || retiredPack.packName() == null
            || retiredPack.revision() <= 0L || retiredPack.hash() == null) {
            throw new IOException("Installed Prior Datapack Identity Is Invalid");
        }
        validateTree(root);
        if (!isOwnedManifest(root.resolve("resync-manifest.json"), retiredPack.projectId(), retiredPack.packName(), retiredPack.revision())) {
            throw new IOException("Installed Prior Datapack Is Not ReSync-Owned: " + root);
        }
        if (!retiredPack.hash().equals(treeHash(fingerprintTree(root)))) {
            throw new IOException("Installed Prior Datapack Hash Does Not Match Its Identity: " + root);
        }
        String expectedFileKey = retiredPack.originalFileKey().isBlank() ? retiredPack.backupFileKey() : retiredPack.originalFileKey();
        requireFileKey(root, expectedFileKey, "retired datapack", root);
    }

    private void validateRetirementArtifacts(Transaction transaction, Phase phase) throws IOException {
        Path retiredRoot = transaction.retiredRoot();
        boolean rootExists = Files.exists(retiredRoot, LinkOption.NOFOLLOW_LINKS);
        if (rootExists && (Files.isSymbolicLink(retiredRoot) || !Files.isDirectory(retiredRoot, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Installed Datapack Retirement Root Is Invalid");
        }
        Set<String> expectedNames = new HashSet<>();
        for (RetiredPack retiredPack : transaction.retiredPacks()) {
            Path original = retiredPack.originalTarget();
            Path backup = retiredPack.backupTarget();
            if (backup == null || !backup.startsWith(retiredRoot)) {
                throw new IOException("Installed Datapack Retirement Path Is Invalid");
            }
            expectedNames.add(backup.getFileName().toString());
            boolean originalExists = Files.exists(original, LinkOption.NOFOLLOW_LINKS);
            boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
            if ((originalExists && Files.isSymbolicLink(original)) || (backupExists && Files.isSymbolicLink(backup))) {
                throw new IOException("Installed Prior Datapack Retirement Contains A Symbolic Link");
            }
            if (originalExists && backupExists) {
                throw new IOException("Installed Prior Datapack Retirement Is Ambiguous: " + original);
            }
            if (originalExists) {
                validateRetiredPack(original, retiredPack);
                requireFileKey(original, retiredPack.originalFileKey(), "retired original", transaction.journal());
            }
            if (backupExists) {
                validateRetiredPack(backup, retiredPack);
                requireFileKey(backup, retiredPack.backupFileKey(), "retired retirement", transaction.journal());
            }
            switch (retiredPack.state()) {
                case ORIGINAL -> {
                    if (!originalExists || backupExists) {
                        throw new IOException("Installed Prior Datapack Retirement State Is Invalid: " + original);
                    }
                }
                case MOVING -> {
                    if (originalExists == backupExists) {
                        throw new IOException("Installed Prior Datapack Retirement State Is Ambiguous: " + original);
                    }
                }
                case RETIRED -> {
                    if (originalExists || !backupExists) {
                        throw new IOException("Installed Prior Datapack Retirement State Is Invalid: " + original);
                    }
                }
            }
        }
        if (rootExists) {
            try (var stream = Files.list(retiredRoot)) {
                for (Path child : stream.toList()) {
                    if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                        || !expectedNames.contains(child.getFileName().toString())) {
                        throw new IOException("Installed Datapack Retirement Root Contains An Unexpected Entry: " + child);
                    }
                }
            }
            if (phase == Phase.ACTIVATED) {
                for (RetiredPack retiredPack : transaction.retiredPacks()) {
                    if (retiredPack.state() != RetirementState.RETIRED) {
                        throw new IOException("Committed Prior Datapack Retirement State Is Invalid");
                    }
                }
            }
        }
    }

    private record ManifestIdentity(String projectId, String packName, long revision) {
    }

    private String treeHash(Map<String, String> fingerprint) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fingerprint.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                });
            return HexFormat.of().formatHex(digest.digest());
        } catch (RuntimeException exception) {
            throw new IOException("Datapack Tree Hash Failed", exception);
        } catch (Exception exception) {
            throw new IOException("Datapack Tree Hash Failed", exception);
        }
    }

    private void requireArtifactName(String name, String prefix, String field) throws IOException {
        if (name.length() <= prefix.length() + 33 || !name.startsWith(prefix)) {
            throw new IOException("Installed Datapack " + field + " Name Is Invalid: " + name);
        }
        int tokenStart = prefix.length();
        String token = name.substring(tokenStart, tokenStart + 32);
        if (!token.matches("[0-9a-f]{32}") || name.charAt(tokenStart + 32) != '-') {
            throw new IOException("Installed Datapack " + field + " Token Is Invalid: " + name);
        }
        requirePackName(name.substring(tokenStart + 33));
    }

    private void requireRetirementRootName(String name) throws IOException {
        if (name.length() != RETIRED_PREFIX.length() + 32 || !name.startsWith(RETIRED_PREFIX)
            || !name.substring(RETIRED_PREFIX.length()).matches("[0-9a-f]{32}")) {
            throw new IOException("Installed Datapack Retirement Root Name Is Invalid: " + name);
        }
    }

    private String requireProjectId(String projectId) {
        if (projectId == null || projectId.isBlank() || projectId.indexOf('\u0000') >= 0
            || projectId.indexOf('\n') >= 0 || projectId.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Project Identity Required");
        }
        return projectId.trim();
    }

    private long parseRevision(String value, Path journal) throws IOException {
        try {
            long revision = Long.parseLong(value);
            if (revision <= 0L) {
                throw new NumberFormatException();
            }
            return revision;
        } catch (NumberFormatException exception) {
            throw new IOException("Installed Datapack Build Revision Is Invalid: " + journal, exception);
        }
    }

    private boolean parseBoolean(String value, String field, Path journal) throws IOException {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IOException("Installed Datapack " + field + " Is Invalid: " + journal);
        }
        return Boolean.parseBoolean(value);
    }

    private void requireHash(String value, String field, Path journal) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IOException("Installed Datapack " + field + " Is Invalid: " + journal);
        }
    }

    private String requireWorldName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("World Name Required");
        }
        String value = worldName.trim();
        if (value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
            || value.indexOf(':') >= 0 || value.contains("/") || value.contains("\\")
            || value.equals(".") || value.equals("..") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid World Identity");
        }
        return value;
    }

    private String requirePackName(String packName) {
        if (packName == null || packName.isBlank()) {
            throw new IllegalArgumentException("Datapack Name Required");
        }
        String value = packName.trim();
        if (value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
            || value.indexOf(':') >= 0 || value.contains("/") || value.contains("\\")
            || value.equals(".") || value.equals("..") || value.contains("..")
            || value.startsWith(STAGE_PREFIX) || value.startsWith(BACKUP_PREFIX) || value.startsWith(RETIRED_PREFIX)
            || value.startsWith(JOURNAL_PREFIX)) {
            throw new IllegalArgumentException("Invalid Datapack Name");
        }
        return value;
    }

    private void requireInside(Path root, Path child, String field) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot) || normalizedChild.equals(normalizedRoot)) {
            throw new IOException(field + " Escaped Its Root");
        }
    }

    private void requireSafeAncestors(Path root, Path path) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        requireInsideOrEqual(normalizedRoot, normalizedPath, "path");
        Path current = normalizedRoot;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("Symbolic Link Traversal Is Not Allowed: " + current);
        }
        for (Path part : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(part).normalize();
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new IOException("Symbolic Link Traversal Is Not Allowed: " + current);
                }
                canonicalPath(current);
            }
        }
    }

    private void requireNoSymlinkAncestors(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new IOException("Symbolic Link Ancestor Is Not Allowed: " + current);
                }
                canonicalPath(current);
            }
        }
    }

    private void requireInsideOrEqual(Path root, Path child, String field) throws IOException {
        if (!child.startsWith(root)) {
            throw new IOException(field + " Escaped Its Root");
        }
    }

    private void awaitStateChange() throws IOException {
        try {
            monitor.wait();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Installed Datapack Lifecycle Was Interrupted", exception);
        }
    }

    private void releaseOperation(MigrationFence.MutationLease mutation, boolean releaseMutation) {
        synchronized (monitor) {
            activeOperations--;
            monitor.notifyAll();
        }
        if (releaseMutation) {
            mutation.close();
        }
    }

    private void markFailed(String reason) {
        synchronized (monitor) {
            if (state != State.CLOSED) {
                state = State.FAILED;
                failureReason = reason == null || reason.isBlank() ? "Installed Datapack Capability Failed" : reason;
                monitor.notifyAll();
            }
        }
    }

    private IOException fail(String reason) {
        markFailed(reason);
        return failure(reason);
    }

    private IOException failure(String fallback) {
        synchronized (monitor) {
            String reason = failureReason == null || failureReason.isBlank() ? fallback : failureReason;
            return new IOException(reason);
        }
    }

    private InstallResult failureResult(String message) {
        boolean available;
        synchronized (monitor) {
            available = state != State.FAILED && state != State.CLOSED;
        }
        return new InstallResult(false, false, message == null || message.isBlank() ? "Datapack Install Failed" : message, available);
    }

    private String message(Throwable exception, String fallback) {
        return exception != null && exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : fallback;
    }

    private static Duration requireTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " Must Be Non-Negative");
        }
        return timeout;
    }

    private static final class BukkitPackActivator implements PackActivator {
        @Override
        public boolean enable(String packName) {
            return enable(packName, "");
        }

        @Override
        public ActivationState capture(String packName, String worldName) {
            try {
                Bukkit.getDatapackManager().refreshPacks();
                Datapack pack = find(packName);
                return pack == null ? ActivationState.absent() : ActivationState.known(pack.isEnabled());
            } catch (RuntimeException exception) {
                return ActivationState.unknown();
            }
        }

        @Override
        public boolean enable(String packName, String worldName) {
            try {
                Bukkit.getDatapackManager().refreshPacks();
                Datapack pack = find(packName);
                if (pack == null) {
                    return false;
                }
                pack.setEnabled(true);
                return pack.isEnabled();
            } catch (RuntimeException exception) {
                return false;
            }
        }

        @Override
        public void restore(String packName, String worldName, ActivationState state) throws IOException {
            if (state == null || !state.known()) {
                return;
            }
            try {
                Bukkit.getDatapackManager().refreshPacks();
                Datapack pack = find(packName);
                if (!state.present()) {
                    if (pack != null) {
                        pack.setEnabled(false);
                        if (pack.isEnabled()) {
                            throw new IOException("Installed Datapack Previous Enabled State Was Not Restored");
                        }
                    }
                    Bukkit.getDatapackManager().refreshPacks();
                    if (find(packName) != null) {
                        throw new IOException("Installed Datapack Previous Enabled State Pack Was Not Removed");
                    }
                    return;
                }
                if (pack == null) {
                    throw new IOException("Installed Datapack Previous Enabled State Pack Is Missing");
                }
                pack.setEnabled(state.enabled());
                if (pack.isEnabled() != state.enabled()) {
                    throw new IOException("Installed Datapack Previous Enabled State Was Not Restored");
                }
            } catch (RuntimeException ignored) {
                throw new IOException("Installed Datapack Previous Enabled State Could Not Be Restored", ignored);
            }
        }

        private Datapack find(String packName) {
            for (Datapack pack : Bukkit.getDatapackManager().getPacks()) {
                if (pack.getName().equals(packName) || pack.getName().equals("file/" + packName)) {
                    return pack;
                }
            }
            return null;
        }
    }

    private record Transaction(Path datapacks, Path target, Path stage, Path backup, Path journal,
                               String worldName, String packName, String owner, String projectId,
                               long buildRevision, Path source, String sourceHash, String targetHash,
                               boolean previousTargetExists, String previousTargetHash, String previousTargetFileKey,
                               ActivationState previousActivation, Path retiredRoot,
                               List<RetiredPack> retiredPacks) {
        private Transaction {
            retiredPacks = List.copyOf(retiredPacks == null ? List.of() : retiredPacks);
        }
    }

    private record JournalTransaction(Transaction transaction, Phase phase, JournalTopology topology) {
    }

    private record Bootstrap(String datapacksCanonical, String datapacksFileKey, String packName, String projectId,
                             long buildRevision, String stageHash, Path stage, String stageFileKey, Path target,
                             boolean previousTargetExists, String previousTargetHash, String previousTargetFileKey) {
    }

    private record PreviousTarget(boolean exists, String hash, String fileKey) {
        private static PreviousTarget absent() {
            return new PreviousTarget(false, "", "");
        }
    }

    private record JournalTopology(String datapacksCanonical, String datapacksFileKey,
                                   String sourceCanonical, String sourceFileKey,
                                   String targetCanonical, String targetFileKey,
                                   String stageCanonical, String stageFileKey,
                                   String backupCanonical, String backupFileKey,
                                   String retiredRootCanonical, String retiredRootFileKey) {
    }

    private record PathIdentity(String canonical, String fileKey) {
    }

    private record RetiredPack(String packName, String projectId, long revision,
                               Path originalTarget, Path backupTarget, String hash,
                               ActivationState previousActivation, String originalFileKey,
                               String backupFileKey, RetirementState state) {
        private RetiredPack withBackupTarget(Path target) {
            return new RetiredPack(packName, projectId, revision, originalTarget, target, hash, previousActivation,
                originalFileKey, originalFileKey, RetirementState.ORIGINAL);
        }

        private RetiredPack withState(RetirementState value) {
            return new RetiredPack(packName, projectId, revision, originalTarget, backupTarget, hash,
                previousActivation, originalFileKey, backupFileKey, value);
        }
    }

    private enum RetirementState {
        ORIGINAL,
        MOVING,
        RETIRED;

        private static boolean isKnown(String value) {
            try {
                valueOf(value);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }

    private enum Phase {
        PREPARED,
        BACKUP_MOVING,
        RETIRING,
        BACKUP_MOVED,
        TARGET_MOVING,
        TARGET_MOVED,
        ACTIVATING,
        ACTIVATED;

        private static boolean isKnown(String value) {
            try {
                valueOf(value);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }

    private final class OperationLease implements AutoCloseable {
        private final MigrationFence.MutationLease mutation;
        private final boolean releaseMutation;
        private boolean closed;

        private OperationLease(MigrationFence.MutationLease mutation, boolean releaseMutation) {
            this.mutation = mutation;
            this.releaseMutation = releaseMutation;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                releaseOperation(mutation, releaseMutation);
            }
        }
    }

    @FunctionalInterface
    public interface WorldRootProvider {
        Path worldRoot();
    }

    @FunctionalInterface
    public interface ReparsePointProbe {
        boolean isReparsePoint(Path path) throws IOException;

        static ReparsePointProbe system() {
            WindowsFileIdentity identity = WindowsFileIdentity.system();
            return path -> !isWindows() ? false : identity.observe(path).isReparsePoint();
        }
    }

    @FunctionalInterface
    public interface MutationObserver {
        void beforeMutation(Path source, Path target) throws IOException;

        static MutationObserver none() {
            return (source, target) -> {
            };
        }
    }

    @FunctionalInterface
    public interface RuntimeVersionProvider {
        String runtimeVersion();
    }

    @FunctionalInterface
    public interface PackActivator {
        boolean enable(String packName);

        default ActivationState capture(String packName, String worldName) {
            return ActivationState.absent();
        }

        default boolean enable(String packName, String worldName) {
            return enable(packName);
        }

        default void restore(String packName, String worldName, ActivationState state) throws IOException {
        }
    }

    public record ActivationState(boolean known, boolean present, boolean enabled) {
        public static ActivationState known(boolean enabled) {
            return new ActivationState(true, true, enabled);
        }

        public static ActivationState absent() {
            return new ActivationState(true, false, false);
        }

        public static ActivationState unknown() {
            return new ActivationState(false, false, false);
        }
    }

    public interface DirectoryDurability {
        boolean available(Path root);

        String unavailableReason(Path root);

        void force(Path path) throws IOException;

        static DirectoryDurability system() {
            return new SystemDirectoryDurability();
        }

        static DirectoryDurability noop() {
            return new DirectoryDurability() {
                @Override
                public boolean available(Path root) {
                    return true;
                }

                @Override
                public String unavailableReason(Path root) {
                    return "";
                }

                @Override
                public void force(Path path) {
                }
            };
        }

        static DirectoryDurability unavailable(String reason) {
            String message = reason == null || reason.isBlank() ? "Directory Durability Is Unavailable" : reason;
            return new DirectoryDurability() {
                @Override
                public boolean available(Path root) {
                    return false;
                }

                @Override
                public String unavailableReason(Path root) {
                    return message;
                }

                @Override
                public void force(Path path) {
                }
            };
        }
    }

    private static final class SystemDirectoryDurability implements DirectoryDurability {
        private final WindowsFileMutation windowsFileMutation = WindowsFileMutation.system();

        @Override
        public boolean available(Path root) {
            if (isWindows()) {
                return windowsFileMutation.available(root);
            }
            try {
                force(root);
                return true;
            } catch (IOException exception) {
                return false;
            }
        }

        @Override
        public String unavailableReason(Path root) {
            return isWindows()
                ? windowsFileMutation.unavailableReason(root)
                : "Installed Datapack Directory Durability Probe Failed";
        }

        @Override
        public void force(Path path) throws IOException {
            if (isWindows()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    windowsFileMutation.flushParent(path);
                } else {
                    windowsFileMutation.flush(path);
                }
                return;
            }
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Cannot Force A Symbolic Link: " + path);
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }

    public record InstallResult(boolean installed, boolean enabled, String message, boolean available) {
        public InstallResult(boolean installed, boolean enabled, String message) {
            this(installed, enabled, message, true);
        }
    }
}
