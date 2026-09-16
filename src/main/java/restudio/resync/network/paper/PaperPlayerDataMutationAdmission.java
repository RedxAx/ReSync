package restudio.resync.network.paper;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import restudio.resync.filesystem.windows.WindowsFileIdentity;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceRootReadiness;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class PaperPlayerDataMutationAdmission {
    public static final String OWNER = "resync.paper.playerdata";
    public static final String SNAPSHOT_RESTORE_UNAVAILABLE = "Paper does not expose an atomic snapshot and restore contract for external world/playerdata files or PersistentDataContainer values";
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    public static final UnloadedWorldPolicy UNLOADED_WORLD_POLICY = UnloadedWorldPolicy.LOADED_WORLDS_ONLY;
    private static final AtomicReference<Installation> SHARED = new AtomicReference<>();
    private static final AtomicLong INSTALLATION_GENERATIONS = new AtomicLong();
    private static final PaperPlayerDataMutationAdmission UNAVAILABLE =
        new PaperPlayerDataMutationAdmission(List.of(), null);

    private final Object monitor = new Object();
    private final Set<Path> worldRoots = new LinkedHashSet<>();
    private final Set<Path> playerDataRoots = new LinkedHashSet<>();
    private final Map<Thread, Integer> activeByThread = new IdentityHashMap<>();
    private final Map<Path, PathProof> pinnedProofs = new LinkedHashMap<>();
    private final PathSafetyProbe pathSafety;
    private final PathObservationProbe pathObservation;
    private Path worldContainer;
    private State state = State.OPEN;
    private int activeWork;
    private volatile long generation;
    private volatile long readinessCurrentness;
    private String failureReason = "";
    private boolean rediscoveryRequired;

    public PaperPlayerDataMutationAdmission(Collection<Path> worldRoots) {
        this(worldRoots, null);
    }

    public PaperPlayerDataMutationAdmission(Collection<Path> worldRoots, Path worldContainer) {
        this(worldRoots, worldContainer, PaperPlayerDataMutationAdmission::requireDefaultPathSafety,
            PaperPlayerDataMutationAdmission::observeWithNio);
    }

    PaperPlayerDataMutationAdmission(Collection<Path> worldRoots, Path worldContainer, PathSafetyProbe pathSafety) {
        this(worldRoots, worldContainer, pathSafety, PaperPlayerDataMutationAdmission::observeWithNio);
    }

    PaperPlayerDataMutationAdmission(Collection<Path> worldRoots, Path worldContainer, PathSafetyProbe pathSafety,
                                     PathObservationProbe pathObservation) {
        this.pathSafety = Objects.requireNonNull(pathSafety, "pathSafety");
        this.pathObservation = Objects.requireNonNull(pathObservation, "pathObservation");
        if (worldContainer != null) {
            try {
                this.worldContainer = MigrationPaths.requirePath(worldContainer, "worldContainer");
            } catch (RuntimeException exception) {
                failureReason = reason(exception);
            }
        }
        registerInitialWorldRoots(worldRoots);
        rebuildPinnedProofs();
    }

    public static PaperPlayerDataMutationAdmission forBukkitWorlds() {
        Set<Path> worlds = new LinkedHashSet<>();
        Path container = null;
        if (Bukkit.getServer() != null) {
            for (World world : Bukkit.getWorlds()) {
                if (world != null && world.getWorldFolder() != null) {
                    worlds.add(world.getWorldFolder().toPath());
                }
            }
            if (Bukkit.getWorldContainer() != null) {
                container = Bukkit.getWorldContainer().toPath();
            }
        }
        return new PaperPlayerDataMutationAdmission(worlds, container);
    }

    public static PaperPlayerDataMutationAdmission shared() {
        Installation current = SHARED.get();
        return current == null ? UNAVAILABLE : current.admission();
    }

    static Installation sharedInstallation() {
        return SHARED.get();
    }

    public static Installation installShared(PaperPlayerDataMutationAdmission admission) {
        PaperPlayerDataMutationAdmission candidate = Objects.requireNonNull(admission, "admission");
        Installation current = SHARED.get();
        if (current != null && current.admission() == candidate) {
            return current;
        }
        Installation installation = new Installation(candidate, INSTALLATION_GENERATIONS.incrementAndGet());
        if (!SHARED.compareAndSet(null, installation)) {
            throw new IllegalStateException("Paper Player Data Admission Is Already Installed");
        }
        return installation;
    }

    public static boolean clearSharedInstallation(Installation installation) {
        Installation current = SHARED.get();
        return installation != null && current == installation
            && current.generation() == installation.generation()
            && SHARED.compareAndSet(current, null);
    }

    public void refreshFromBukkit() {
        if (Bukkit.getServer() == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Paper Player Data World Rediscovery Requires The Bukkit Main Thread");
        }
        Set<Path> worlds = new LinkedHashSet<>();
        String discoveryFailure = "";
        for (World world : Bukkit.getWorlds()) {
            if (world == null || world.getWorldFolder() == null) {
                discoveryFailure = appendFailure(discoveryFailure, "Loaded Bukkit World Has No World Folder");
            } else {
                worlds.add(world.getWorldFolder().toPath());
            }
        }
        Path container = Bukkit.getWorldContainer() == null ? null : Bukkit.getWorldContainer().toPath();
        List<Path> normalizedWorlds = new ArrayList<>();
        for (Path world : worlds) {
            try {
                normalizedWorlds.add(MigrationPaths.requirePath(world, "worldRoot"));
            } catch (RuntimeException exception) {
                discoveryFailure = appendFailure(discoveryFailure, reason(exception));
            }
        }
        Path normalizedContainer = null;
        if (container != null) {
            try {
                normalizedContainer = MigrationPaths.requirePath(container, "worldContainer");
            } catch (RuntimeException exception) {
                discoveryFailure = appendFailure(discoveryFailure, reason(exception));
            }
        } else {
            discoveryFailure = appendFailure(discoveryFailure, "Bukkit World Container Is Unavailable");
        }
        refreshNormalizedRoots(normalizedContainer, normalizedWorlds, discoveryFailure);
    }

    void refreshRoots(Collection<Path> roots, Path container) {
        List<Path> normalizedWorlds = new ArrayList<>();
        String discoveryFailure = "";
        if (roots != null) {
            for (Path world : roots) {
                try {
                    normalizedWorlds.add(MigrationPaths.requirePath(world, "worldRoot"));
                } catch (RuntimeException exception) {
                    discoveryFailure = appendFailure(discoveryFailure, reason(exception));
                }
            }
        } else {
            discoveryFailure = appendFailure(discoveryFailure, "World Roots Are Unavailable");
        }
        Path normalizedContainer = null;
        if (container != null) {
            try {
                normalizedContainer = MigrationPaths.requirePath(container, "worldContainer");
            } catch (RuntimeException exception) {
                discoveryFailure = appendFailure(discoveryFailure, reason(exception));
            }
        } else {
            discoveryFailure = appendFailure(discoveryFailure, "Bukkit World Container Is Unavailable");
        }
        refreshNormalizedRoots(normalizedContainer, normalizedWorlds, discoveryFailure);
    }

    private void refreshNormalizedRoots(Path normalizedContainer, Collection<Path> normalizedWorlds,
                                        String discoveryFailure) {
        List<Path> worlds = List.copyOf(normalizedWorlds == null ? List.of() : normalizedWorlds);
        synchronized (monitor) {
            if (state == State.CLOSED) {
                throw new IllegalStateException("Paper Player Data Admission Is CLOSED");
            }
            if (activeWork > 0 && (!worldRoots.equals(new LinkedHashSet<>(worlds))
                || !Objects.equals(worldContainer, normalizedContainer))) {
                throw new IllegalStateException("Paper Player Data Roots Cannot Be Rediscovered While Mutations Are Active");
            }
        }
        ProofBuild rootProofBuild = buildPinnedProofs(normalizedContainer, worlds, false);
        Map<Path, PathProof> rootProofs = new LinkedHashMap<>(rootProofBuild.proofs());
        String refreshFailure = appendFailure(discoveryFailure, rootProofBuild.failureReason());
        if (normalizedContainer != null) {
            try {
                requireCandidateRoot(normalizedContainer, "worldContainer", rootProofs);
            } catch (IOException exception) {
                refreshFailure = appendFailure(refreshFailure, reason(exception));
            }
        }
        for (Path world : worlds) {
            try {
                if (normalizedContainer == null || world.equals(normalizedContainer) || !world.startsWith(normalizedContainer)) {
                    throw new IOException("World Root Is Outside The Registered Bukkit World Container: " + world);
                }
                requireCandidateRoot(world, "worldRoot", rootProofs);
            } catch (IOException exception) {
                refreshFailure = appendFailure(refreshFailure, reason(exception));
            }
        }
        if (refreshFailure.isBlank()) {
            for (Path world : worlds) {
                try {
                    materializePlayerDataRoot(world, rootProofs);
                } catch (IOException exception) {
                    refreshFailure = appendFailure(refreshFailure, reason(exception));
                }
            }
        }
        ProofBuild proofBuild = buildPinnedProofs(normalizedContainer, worlds, true);
        synchronized (monitor) {
            if (state == State.CLOSED) {
                throw new IllegalStateException("Paper Player Data Admission Is CLOSED");
            }
            if (activeWork > 0 && (!worldRoots.equals(new LinkedHashSet<>(worlds))
                || !Objects.equals(worldContainer, normalizedContainer))) {
                throw new IllegalStateException("Paper Player Data Roots Cannot Be Rediscovered While Mutations Are Active");
            }
            invalidateReadinessLocked();
            worldContainer = normalizedContainer;
            failureReason = appendFailure(refreshFailure, proofBuild.failureReason());
            replaceWorldRootsLocked(worlds);
            pinnedProofs.clear();
            pinnedProofs.putAll(proofBuild.proofs());
            rediscoveryRequired = false;
        }
    }

    private void registerInitialWorldRoots(Collection<Path> roots) {
        if (roots == null) {
            return;
        }
        List<Path> normalized = new ArrayList<>();
        for (Path root : roots) {
            if (root == null) {
                failureReason = appendFailure(failureReason, "World Root Is Unavailable");
                continue;
            }
            try {
                normalized.add(MigrationPaths.requirePath(root, "worldRoot"));
            } catch (RuntimeException exception) {
                failureReason = appendFailure(failureReason, reason(exception));
            }
        }
        synchronized (monitor) {
            for (Path root : normalized) {
                if (worldContainer != null && (!root.startsWith(worldContainer) || root.equals(worldContainer))) {
                    failureReason = appendFailure(failureReason, "World Root Is Outside The World Container: " + root);
                    continue;
                }
                worldRoots.add(root);
                playerDataRoots.add(root.resolve("playerdata").toAbsolutePath().normalize());
            }
        }
    }

    private void replaceWorldRootsLocked(Collection<Path> roots) {
        List<Path> normalized = new ArrayList<>();
        if (roots != null) {
            normalized.addAll(roots);
        }
        synchronized (monitor) {
            worldRoots.clear();
            playerDataRoots.clear();
            for (Path root : normalized) {
                if (worldContainer != null && (!root.startsWith(worldContainer) || root.equals(worldContainer))) {
                    failureReason = appendFailure(failureReason, "World Root Is Outside The World Container: " + root);
                    continue;
                }
                worldRoots.add(root);
                playerDataRoots.add(root.resolve("playerdata").toAbsolutePath().normalize());
            }
        }
    }

    private void rebuildPinnedProofs() {
        Path container;
        List<Path> worlds;
        String existingFailure;
        synchronized (monitor) {
            container = worldContainer;
            worlds = List.copyOf(worldRoots);
            existingFailure = failureReason;
        }
        ProofBuild proofBuild = buildPinnedProofs(container, worlds);
        synchronized (monitor) {
            pinnedProofs.clear();
            pinnedProofs.putAll(proofBuild.proofs());
            failureReason = appendFailure(existingFailure, proofBuild.failureReason());
        }
    }

    private ProofBuild buildPinnedProofs(Path container, Collection<Path> worlds) {
        return buildPinnedProofs(container, worlds, true);
    }

    private ProofBuild buildPinnedProofs(Path container, Collection<Path> worlds, boolean includePlayerData) {
        Set<Path> paths = new LinkedHashSet<>();
        addProofPath(paths, container);
        if (worlds != null) {
            for (Path world : worlds) {
                addProofPath(paths, world);
                if (includePlayerData && world != null) {
                    addProofPath(paths, world.resolve("playerdata"));
                }
            }
        }
        Map<Path, PathProof> proofs = new LinkedHashMap<>();
        String failure = "";
        for (Path path : paths) {
            try {
                PathObservation observation = observePath(path);
                requireSafeObservation(path, observation);
                PathProof proof = PathProof.from(observation);
                pathSafety.requireSafe(path);
                proofs.put(path, proof);
            } catch (IOException | RuntimeException exception) {
                failure = appendFailure(failure, reason(exception));
            }
        }
        return new ProofBuild(proofs, failure);
    }

    private void requireCandidateRoot(Path root, String name, Map<Path, PathProof> proofs) throws IOException {
        Path normalized = normalizeAbsolutePath(root, name);
        PathProof proof = proofs.get(normalized);
        if (proof == null) {
            throw new IOException(name + " Safety Proof Is Unavailable: " + normalized);
        }
        PathObservation observation = observePath(normalized);
        requireSafeObservation(normalized, observation);
        if (!observation.directory()) {
            throw new IOException(name + " Is Not An Existing Non-Symbolic-Link Directory: " + normalized);
        }
        if (!proof.matches(observation)) {
            throw new IOException(name + " Safety Proof Changed Before Publication: " + normalized);
        }
        pathSafety.requireSafe(normalized);
    }

    private void materializePlayerDataRoot(Path world, Map<Path, PathProof> rootProofs) throws IOException {
        Path normalizedWorld = normalizeAbsolutePath(world, "worldRoot");
        requireCandidateRoot(normalizedWorld, "worldRoot", rootProofs);
        Path playerData = normalizedWorld.resolve("playerdata").normalize();
        if (!normalizedWorld.equals(playerData.getParent())) {
            throw new IOException("playerdata Root Is Not An Immediate Child Of Its World Root: " + playerData);
        }
        try {
            Files.createDirectory(playerData);
        } catch (FileAlreadyExistsException ignored) {
        }
        requireCandidateRoot(normalizedWorld, "worldRoot", rootProofs);
        PathObservation observation = observePath(playerData);
        requireSafeObservation(playerData, observation);
        if (!observation.directory()) {
            throw new IOException("playerdata Root Is Not An Existing Non-Symbolic-Link Directory: " + playerData);
        }
        pathSafety.requireSafe(playerData);
        try {
            if (!playerData.toRealPath(LinkOption.NOFOLLOW_LINKS).getParent()
                .equals(normalizedWorld.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("playerdata Root Does Not Resolve Under Its World Root: " + playerData);
            }
        } catch (IOException exception) {
            throw new IOException("playerdata Root Could Not Be Resolved: " + playerData, exception);
        }
    }

    private void addProofPath(Set<Path> paths, Path path) {
        if (path == null) {
            return;
        }
        Path normalized;
        try {
            normalized = MigrationPaths.requirePath(path, "proofPath");
        } catch (RuntimeException exception) {
            return;
        }
        Path current = normalized.getRoot();
        if (current != null) {
            paths.add(current);
        }
        for (Path part : normalized) {
            current = current == null ? part : current.resolve(part).normalize();
            paths.add(current);
        }
    }

    public Lease acquire(String operation) {
        String label = requireText(operation, "operation");
        synchronized (monitor) {
            requireHealthyOpen(label);
            return admit(label, null);
        }
    }

    public Optional<Lease> tryAcquire(String operation, Duration timeout) throws InterruptedException {
        String label = requireText(operation, "operation");
        requireTimeout(timeout, "timeout");
        synchronized (monitor) {
            if (state != State.OPEN || !rootFailures().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(admit(label, null));
        }
    }

    public Lease acquire(String operation, Path target) {
        String label = requireText(operation, "operation");
        Path normalized = normalizeTarget(target);
        synchronized (monitor) {
            requireHealthyOpen(label);
            return admit(label, normalized);
        }
    }

    public Optional<Lease> tryAcquire(String operation, Path target, Duration timeout) throws InterruptedException {
        String label = requireText(operation, "operation");
        requireTimeout(timeout, "timeout");
        Path normalized = normalizeTarget(target);
        synchronized (monitor) {
            if (state != State.OPEN || !rootFailures().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(admit(label, normalized));
        }
    }

    public Lease acquirePlayerData(String operation, UUID playerId, Path worldRoot) {
        Objects.requireNonNull(playerId, "playerId");
        Path root = MigrationPaths.requirePath(worldRoot, "worldRoot");
        synchronized (monitor) {
            if (!worldRoots.contains(root)) {
                throw new IllegalStateException("World Root Is Not Registered After Bukkit World Rediscovery: " + root);
            }
        }
        return acquire(operation, root.resolve("playerdata").resolve(playerId + ".dat"));
    }

    public Lease acquirePdc(String operation, UUID playerId, Path worldRoot) {
        return acquirePlayerData(operation, playerId, worldRoot);
    }

    public void requireTarget(Path target) {
        normalizeTarget(target);
    }

    public boolean owns(Path target) {
        try {
            requireTargetPath(target);
            return true;
        } catch (RuntimeException | IOException exception) {
            return false;
        }
    }

    public void quiesce() throws IOException {
        quiesce(DEFAULT_DRAIN_TIMEOUT);
    }

    public void quiesce(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (monitor) {
            if (state == State.QUIESCED) {
                return;
            }
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.FAILED) {
                throw new IOException("Paper Player Data Admission Is FAILED: " + failureReason);
            }
            invalidateReadinessLocked();
            rediscoveryRequired = Bukkit.getServer() != null;
            if (activeByThread.containsKey(Thread.currentThread())) {
                throw new IOException("Paper Player Data Admission Cannot Quiesce From An Active Mutation");
            }
            state = State.QUIESCING;
            generation++;
            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread() && activeWork > 0) {
                monitor.notifyAll();
                throw new IOException("Paper Player Data Admission Drain Must Continue Off The Bukkit Main Thread");
            }
            while (activeWork > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    invalidateReadinessLocked();
                    failureReason = "Paper Player Data Admission Drain Timed Out With " + activeWork + " Active Mutations";
                    state = State.FAILED;
                    monitor.notifyAll();
                    throw new IOException(failureReason);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    invalidateReadinessLocked();
                    failureReason = "Paper Player Data Admission Drain Was Interrupted";
                    state = State.FAILED;
                    monitor.notifyAll();
                    throw new IOException(failureReason, exception);
                }
            }
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.FAILED) {
                throw new IOException("Paper Player Data Admission Is FAILED: " + failureReason);
            }
            invalidateReadinessLocked();
            state = State.QUIESCED;
            generation++;
            monitor.notifyAll();
        }
    }

    public Health drain(Duration timeout) throws IOException {
        quiesce(timeout);
        return health();
    }

    public Health drain() throws IOException {
        return drain(DEFAULT_DRAIN_TIMEOUT);
    }

    public void resume() throws IOException {
        synchronized (monitor) {
            if (state == State.OPEN) {
                return;
            }
            if (state != State.QUIESCED) {
                throw new IOException("Paper Player Data Admission Is " + state.name());
            }
            if (Bukkit.getServer() != null && rediscoveryRequired) {
                throw new IOException("Paper Player Data Roots Must Be Rediscovered Before Resume");
            }
            Map<String, String> failures = rootFailures();
            if (!failures.isEmpty()) {
                invalidateReadinessLocked();
                failureReason = failures.values().stream().findFirst().orElse("Paper Player Data Roots Are Unavailable");
                state = State.FAILED;
                throw new IOException(failureReason);
            }
            invalidateReadinessLocked();
            state = State.OPEN;
            failureReason = "";
            generation++;
            monitor.notifyAll();
        }
    }

    public void close() {
        try {
            close(DEFAULT_DRAIN_TIMEOUT);
        } catch (IOException exception) {
            throw new IllegalStateException("Paper Player Data Admission Could Not Close: " + reason(exception), exception);
        }
    }

    public void close(Duration timeout) throws IOException {
        Duration wait = requireTimeout(timeout, "timeout");
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (monitor) {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.FAILED) {
                throw new IOException("Paper Player Data Admission Is FAILED: " + failureReason);
            }
            if (activeByThread.containsKey(Thread.currentThread())) {
                failCloseLocked("Paper Player Data Admission Cannot Close From An Active Mutation");
                throw new IOException(failureReason);
            }
            if (state == State.OPEN || state == State.QUIESCED) {
                invalidateReadinessLocked();
                state = State.QUIESCING;
                generation++;
                monitor.notifyAll();
            }
            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread() && activeWork > 0) {
                throw new IOException("Paper Player Data Admission Close Drain Must Continue Off The Bukkit Main Thread");
            }
            while (activeWork > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    failCloseLocked("Paper Player Data Admission Close Drain Timed Out With " + activeWork + " Active Mutations");
                    throw new IOException(failureReason);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failCloseLocked("Paper Player Data Admission Close Drain Was Interrupted");
                    throw new IOException(failureReason, exception);
                }
            }
            invalidateReadinessLocked();
            state = State.CLOSED;
            generation++;
            monitor.notifyAll();
        }
    }

    public void requestQuiesce() throws IOException {
        synchronized (monitor) {
            if (state == State.CLOSED || state == State.QUIESCED || state == State.QUIESCING) {
                return;
            }
            if (state == State.FAILED) {
                throw new IOException("Paper Player Data Admission Is FAILED: " + failureReason);
            }
            invalidateReadinessLocked();
            rediscoveryRequired = Bukkit.getServer() != null;
            state = State.QUIESCING;
            generation++;
            monitor.notifyAll();
        }
    }

    public Health health() {
        synchronized (monitor) {
            Map<String, String> failures = rootFailures();
            if (!failureReason.isBlank()) {
                failures.put(OWNER, failureReason);
            }
            return new Health(state == State.OPEN && failures.isEmpty(), state, activeWork, generation,
                List.copyOf(worldRoots), List.copyOf(playerDataRoots), Map.copyOf(failures));
        }
    }

    public Readiness readiness() {
        Health health = health();
        return new Readiness(health.available(), false, false, SNAPSHOT_RESTORE_UNAVAILABLE, health.state(), health.activeWork(),
            health.worldRoots(), health.playerDataRoots(), readinessRoot());
    }

    public ReadinessObservation readinessObservation() {
        for (int attempt = 0; attempt < 3; attempt++) {
            long currentness = readinessCurrentness;
            Readiness current = readiness();
            long lifecycleGeneration = generation;
            if (currentness == readinessCurrentness) {
                return new ReadinessObservation(this, lifecycleGeneration, currentness, current);
            }
        }
        throw new IllegalStateException("Paper Player Data Readiness Is Transitioning");
    }

    public boolean isCurrent(ReadinessObservation observation) {
        if (observation == null || observation.owner() != this) {
            return false;
        }
        long currentness = readinessCurrentness;
        long lifecycleGeneration = generation;
        return currentness == observation.currentness() && lifecycleGeneration == observation.generation()
            && currentness == readinessCurrentness;
    }

    public PersistenceRootReadiness.Owner unavailableReadiness() {
        return PersistenceRootReadiness.Owner.unavailable(OWNER, readinessRoot(), true, SNAPSHOT_RESTORE_UNAVAILABLE);
    }

    public PersistenceRootReadiness.Owner readinessOwner() {
        return unavailableReadiness();
    }

    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    public boolean isOpen() {
        return state() == State.OPEN;
    }

    public boolean isQuiesced() {
        State current = state();
        return current == State.QUIESCING || current == State.QUIESCED;
    }

    public int activeWorkCount() {
        synchronized (monitor) {
            return activeWork;
        }
    }

    public int activeLeaseCount() {
        return activeWorkCount();
    }

    public long generation() {
        synchronized (monitor) {
            return generation;
        }
    }

    public List<Path> worldRoots() {
        synchronized (monitor) {
            return List.copyOf(worldRoots);
        }
    }

    public List<Path> playerDataRoots() {
        synchronized (monitor) {
            return List.copyOf(playerDataRoots);
        }
    }

    public Path worldContainer() {
        synchronized (monitor) {
            return worldContainer;
        }
    }

    public UnloadedWorldPolicy unloadedWorldPolicy() {
        return UNLOADED_WORLD_POLICY;
    }

    public <T> T mutatePlayer(String operation, Player player, Supplier<T> mutation) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(mutation, "mutation");
        requirePrimaryThread("Paper Player Data Mutations Must Run On The Bukkit Main Thread");
        if (player.getWorld() == null || player.getWorld().getWorldFolder() == null) {
            throw new IllegalStateException("Paper Player Data World Root Is Unavailable");
        }
        try (Lease ignored = acquirePdc(operation, player.getUniqueId(), player.getWorld().getWorldFolder().toPath())) {
            return mutation.get();
        }
    }

    public void mutatePlayer(String operation, Player player, Runnable mutation) {
        Objects.requireNonNull(mutation, "mutation");
        mutatePlayer(operation, player, () -> {
            mutation.run();
            return null;
        });
    }

    private static void requirePrimaryThread(String message) {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(message);
        }
    }

    private Lease admit(String operation, Path target) {
        invalidateReadinessLocked();
        activeWork++;
        Thread owner = Thread.currentThread();
        activeByThread.merge(owner, 1, Integer::sum);
        return new Lease(operation, target, owner);
    }

    private Path normalizeTarget(Path target) {
        try {
            return requireTargetPath(target);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private Path requireTargetPath(Path target) throws IOException {
        Path normalized = MigrationPaths.requirePath(target, "playerDataTarget");
        Path root = null;
        synchronized (monitor) {
            for (Path candidate : playerDataRoots) {
                if (normalized.startsWith(candidate) && !normalized.equals(candidate)) {
                    root = candidate;
                    break;
                }
            }
        }
        if (root == null) {
            throw new IOException("Player Data Target Is Outside Registered playerdata Roots: " + normalized);
        }
        validateRoot(root, "playerDataRoot");
        Path parent = normalized.getParent();
        if (parent == null || !parent.equals(root)) {
            throw new IOException("Player Data Target Must Be An Immediate File In Its playerdata Root: " + normalized);
        }
        if (!isPlayerDataFile(normalized.getFileName().toString())) {
            throw new IOException("Player Data Target Must Be A UUID .dat File Or Its Reconciliation Temp File: " + normalized);
        }
        requireSafeTraversal(root, parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Player Data Target Parent Is Not A Directory: " + parent);
        }
        try {
            if (!parent.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Player Data Target Parent Does Not Match Its Registered Root: " + normalized);
            }
        } catch (IOException exception) {
            throw new IOException("Player Data Target Parent Could Not Be Resolved: " + normalized, exception);
        }
        boolean targetExists = Files.exists(normalized, LinkOption.NOFOLLOW_LINKS);
        if (targetExists) {
            PathObservation observation = observePath(normalized);
            requireSafeObservation(normalized, observation);
            if (!observation.regularFile()) {
                throw new IOException("Player Data Target Is Not A Regular File: " + normalized);
            }
            if (observation.fileKey() == null) {
                throw new IOException("Player Data Target Identity Proof Is Unsupported: " + normalized);
            }
            try {
                if (!normalized.toRealPath(LinkOption.NOFOLLOW_LINKS).getParent().equals(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                    throw new IOException("Player Data Target Resolved Outside Its Registered Root: " + normalized);
                }
            } catch (IOException exception) {
                throw new IOException("Player Data Target Could Not Be Resolved: " + normalized, exception);
            }
        }
        return normalized;
    }

    private static boolean isPlayerDataFile(String name) {
        String value = name.endsWith(".dat.resync.tmp") ? name.substring(0, name.length() - ".resync.tmp".length()) : name;
        if (!value.endsWith(".dat")) {
            return false;
        }
        try {
            String uuid = value.substring(0, value.length() - 4);
            return UUID.fromString(uuid).toString().equals(uuid);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void requireHealthyOpen(String operation) {
        if (state != State.OPEN) {
            throw new IllegalStateException("Paper Player Data Admission Is " + state.name() + ": " + operation);
        }
        Map<String, String> failures = rootFailures();
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Paper Player Data Admission Is Unavailable: " + failures);
        }
    }

    private Map<String, String> rootFailures() {
        List<Path> worlds;
        List<Path> playerData;
        Path container;
        String configurationFailure;
        synchronized (monitor) {
            worlds = List.copyOf(worldRoots);
            playerData = List.copyOf(playerDataRoots);
            container = worldContainer;
            configurationFailure = failureReason;
        }
        Map<String, String> failures = new LinkedHashMap<>();
        if (configurationFailure != null && !configurationFailure.isBlank()) {
            failures.put(OWNER, configurationFailure);
        }
        if (worlds.isEmpty() || playerData.isEmpty()) {
            failures.put(OWNER, "Paper Player Data Admission Has No Complete World/playerdata Root Set");
            return failures;
        }
        if (container == null) {
            failures.put("worldContainer", "Bukkit World Container Is Unavailable");
        } else {
            try {
                validateRoot(container, "worldContainer");
            } catch (IOException exception) {
                failures.put("worldContainer", reason(exception));
            }
        }
        for (Path world : worlds) {
            try {
                validateRoot(world, "worldRoot");
                if (container == null || world.equals(container) || !world.startsWith(container)) {
                    failures.put("worldRoot:" + world, "World Root Is Outside The Registered Bukkit World Container");
                }
            } catch (IOException exception) {
                failures.put("worldRoot:" + world, reason(exception));
            }
            Path playerDataRoot = world.resolve("playerdata").toAbsolutePath().normalize();
            if (!playerData.contains(playerDataRoot)) {
                failures.put("playerDataRoot:" + playerDataRoot, "World Root Does Not Have Its Exact playerdata Root");
            }
        }
        for (Path root : playerData) {
            try {
                validateRoot(root, "playerDataRoot");
                if (root.getParent() == null || !worlds.contains(root.getParent())) {
                    failures.put("playerDataRoot:" + root, "playerdata Root Is Not Owned By A Registered World Root");
                }
            } catch (IOException exception) {
                failures.put("playerDataRoot:" + root, reason(exception));
            }
        }
        return failures;
    }

    private void validateRoot(Path root, String name) throws IOException {
        Path normalized;
        try {
            normalized = normalizeAbsolutePath(root, name);
        } catch (RuntimeException exception) {
            throw new IOException(reason(exception), exception);
        }
        PathObservation observation;
        try {
            observation = observePath(normalized);
            requireSafeObservation(normalized, observation);
        } catch (IOException exception) {
            invalidatePinnedProofs(normalized);
            throw exception;
        }
        if (!observation.directory()) {
            throw new IOException(name + " Is Not An Existing Non-Symbolic-Link Directory: " + normalized);
        }
        requireSafeTraversal(normalized, normalized);
    }

    private void requireSafeTraversal(Path root, Path path) throws IOException {
        Path normalizedRoot = normalizeAbsolutePath(root, "root");
        Path normalizedPath = normalizeAbsolutePath(path, "path");
        Path current = normalizedRoot.getRoot();
        if (current != null && pathExistsWithoutFollowing(current)) {
            requirePinnedSafePath(current);
        }
        for (Path part : normalizedRoot) {
            current = current == null ? part : current.resolve(part).normalize();
            if (pathExistsWithoutFollowing(current)) {
                requirePinnedSafePath(current);
            }
        }
        current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(part).normalize();
            if (!pathExistsWithoutFollowing(current)) {
                break;
            }
            requirePinnedSafePath(current);
        }
    }

    private void requirePinnedSafePath(Path path) throws IOException {
        Path normalized = normalizeAbsolutePath(path, "path");
        PathProof proof;
        synchronized (monitor) {
            proof = pinnedProofs.get(normalized);
        }
        if (proof == null) {
            throw new IOException("Path Safety Proof Is Not Pinned For This Root Topology: " + normalized);
        }
        try {
            PathObservation observation = observePath(normalized);
            requireSafeObservation(normalized, observation);
            if (!proof.matches(observation)) {
                invalidatePinnedProofs(normalized);
                throw new IOException("Path Safety Proof Topology Changed: " + normalized);
            }
        } catch (IOException exception) {
            invalidatePinnedProofs(normalized);
            throw exception;
        }
    }

    private void requireSafeObservation(Path path, PathObservation observation) throws IOException {
        if (observation.symbolicLink() || observation.other() || Boolean.TRUE.equals(observation.reparsePoint())) {
            throw new IOException("Path Cannot Be A Symbolic Link Or Reparse Point: " + path);
        }
    }

    private void invalidatePinnedProofs(Path path) {
        synchronized (monitor) {
            invalidateReadinessLocked();
            pinnedProofs.keySet().removeIf(candidate -> candidate.startsWith(path));
        }
    }

    private PathObservation observePath(Path path) throws IOException {
        Path normalized = normalizeAbsolutePath(path, "path");
        PathObservation observation;
        try {
            observation = pathObservation.observe(normalized);
        } catch (RuntimeException exception) {
            throw new IOException("Path Identity Observation Failed: " + path, exception);
        }
        if (observation == null) {
            throw new IOException("Path Identity Observation Is Unavailable: " + path);
        }
        if (isWindows()) {
            requireWindowsObservation(normalized, observation);
        }
        return observation;
    }

    private static Path normalizeAbsolutePath(Path path, String name) {
        if (path == null) {
            throw new IllegalArgumentException(name + " Is Required");
        }
        return path.toAbsolutePath().normalize();
    }

    private static boolean pathExistsWithoutFollowing(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private static PathObservation observeWithNio(Path path) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "path");
        if (isWindows()) {
            WindowsFileIdentity.Observation observation = WindowsFileIdentity.system().observe(normalized);
            return PathObservation.fromWindows(observation);
        }
        BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return PathObservation.from(attributes, false);
    }

    private static void requireWindowsObservation(Path path, PathObservation observation) throws IOException {
        Path finalPath = observation.normalizedPath();
        if (finalPath == null || !finalPath.equals(path)) {
            throw new IOException("Windows Final Path Does Not Match Requested Path: " + path);
        }
        Boolean reparsePoint = observation.reparsePoint();
        if (reparsePoint == null) {
            throw new IOException("Windows Reparse-Point State Is Unavailable: " + path);
        }
        if (reparsePoint && observation.reparseTag() == 0) {
            throw new IOException("Windows Reparse-Point Tag Is Missing: " + path);
        }
        if (!reparsePoint && observation.reparseTag() != 0) {
            throw new IOException("Windows Reparse-Point Tag Is Unexpected: " + path);
        }
        int kinds = (observation.directory() ? 1 : 0) + (observation.regularFile() ? 1 : 0)
            + (observation.other() ? 1 : 0);
        if (kinds != 1) {
            throw new IOException("Windows File Kind Is Invalid: " + path);
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("windows");
    }

    private static void requireDefaultPathSafety(Path path) throws IOException {
        if (isWindows()) {
            return;
        }
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("linux") || osName.contains("mac") || osName.contains("unix")
            || osName.contains("bsd") || osName.contains("solaris") || osName.contains("aix")
            || osName.contains("android")) {
            return;
        }
        throw new IOException("Reparse-Point Safety Proof Is Unsupported On This Operating System: " + path);
    }

    public Path readinessRoot() {
        synchronized (monitor) {
            if (worldContainer != null) {
                return worldContainer;
            }
            if (!worldRoots.isEmpty()) {
                return worldRoots.stream().min(Comparator.comparing(Path::toString)).orElseThrow();
            }
        }
        return Path.of("worlds").toAbsolutePath().normalize();
    }

    private void release(Lease lease) {
        synchronized (monitor) {
            invalidateReadinessLocked();
            activeWork--;
            activeByThread.computeIfPresent(lease.owner, (thread, count) -> count <= 1 ? null : count - 1);
            monitor.notifyAll();
        }
    }

    private void failCloseLocked(String failure) {
        invalidateReadinessLocked();
        failureReason = failure;
        state = State.FAILED;
        generation++;
        monitor.notifyAll();
    }

    private void invalidateReadinessLocked() {
        readinessCurrentness++;
    }

    private static Duration requireTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " Must Be Non-Negative");
        }
        return timeout;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " Must Not Be Blank");
        }
        return value.trim();
    }

    private static String reason(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String appendFailure(String current, String failure) {
        String value = failure == null ? "" : failure.trim();
        if (value.isBlank()) {
            return current == null ? "" : current;
        }
        if (current == null || current.isBlank()) {
            return value;
        }
        return current + "; " + value;
    }

    public enum State {
        OPEN,
        QUIESCING,
        QUIESCED,
        FAILED,
        CLOSED
    }

    public enum UnloadedWorldPolicy {
        LOADED_WORLDS_ONLY
    }

    @FunctionalInterface
    interface PathSafetyProbe {
        void requireSafe(Path path) throws IOException;
    }

    @FunctionalInterface
    interface PathObservationProbe {
        PathObservation observe(Path path) throws IOException;
    }

    record PathObservation(Object fileKey, boolean symbolicLink, boolean directory, boolean regularFile,
                           boolean other, Boolean reparsePoint, Path normalizedPath, int reparseTag) {
        PathObservation(Object fileKey, boolean symbolicLink, boolean directory, boolean regularFile,
                        boolean other, Boolean reparsePoint) {
            this(fileKey, symbolicLink, directory, regularFile, other, reparsePoint, null, 0);
        }

        static PathObservation from(BasicFileAttributes attributes, Boolean reparsePoint) {
            return new PathObservation(attributes.fileKey(), attributes.isSymbolicLink(), attributes.isDirectory(),
                attributes.isRegularFile(), attributes.isOther(), reparsePoint);
        }

        static PathObservation fromWindows(WindowsFileIdentity.Observation observation) {
            return new PathObservation(observation.identity(), false, observation.directory(), observation.regularFile(),
                observation.other(), observation.reparsePoint(), observation.normalizedPath(), observation.reparseTag());
        }
    }

    private record PathProof(Object fileKey, boolean symbolicLink, boolean directory, boolean regularFile,
                             boolean other, Boolean reparsePoint, int reparseTag) {
        private static PathProof from(PathObservation observation) {
            if (observation.fileKey() == null) {
                throw new IllegalArgumentException("Path Identity Proof Is Unsupported");
            }
            return new PathProof(observation.fileKey(), observation.symbolicLink(), observation.directory(),
                observation.regularFile(), observation.other(), observation.reparsePoint(), observation.reparseTag());
        }

        private boolean matches(PathObservation observation) {
            return Objects.equals(fileKey, observation.fileKey())
                && symbolicLink == observation.symbolicLink()
                && directory == observation.directory()
                && regularFile == observation.regularFile()
                && other == observation.other()
                && Objects.equals(reparsePoint, observation.reparsePoint())
                && reparseTag == observation.reparseTag();
        }
    }

    private record ProofBuild(Map<Path, PathProof> proofs, String failureReason) {
        private ProofBuild {
            proofs = Map.copyOf(proofs == null ? Map.of() : proofs);
            failureReason = failureReason == null ? "" : failureReason;
        }
    }

    public record Installation(PaperPlayerDataMutationAdmission admission, long generation) {
        public Installation {
            Objects.requireNonNull(admission, "admission");
        }
    }

    public record Health(boolean available, State state, int activeWork, long generation,
                         List<Path> worldRoots, List<Path> playerDataRoots, Map<String, String> failures) {
        public Health {
            worldRoots = List.copyOf(worldRoots == null ? List.of() : worldRoots);
            playerDataRoots = List.copyOf(playerDataRoots == null ? List.of() : playerDataRoots);
            failures = Map.copyOf(failures == null ? Map.of() : failures);
        }
    }

    public record Readiness(boolean mutationAvailable, boolean snapshotSupported, boolean restoreSupported,
                            String reason, State state, int activeWork, List<Path> worldRoots,
                            List<Path> playerDataRoots, Path readinessRoot) {
        public Readiness {
            reason = reason == null ? "" : reason.trim();
            worldRoots = List.copyOf(worldRoots == null ? List.of() : worldRoots);
            playerDataRoots = List.copyOf(playerDataRoots == null ? List.of() : playerDataRoots);
            readinessRoot = MigrationPaths.requirePath(readinessRoot, "readinessRoot");
        }
    }

    public record ReadinessObservation(PaperPlayerDataMutationAdmission owner, long generation, long currentness,
                                       Readiness readiness) {
        public ReadinessObservation {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(readiness, "readiness");
        }
    }

    public final class Lease implements AutoCloseable {
        private final String operation;
        private final Path target;
        private final Thread owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(String operation, Path target, Thread owner) {
            this.operation = operation;
            this.target = target;
            this.owner = owner;
        }

        public String operation() {
            return operation;
        }

        public Path target() {
            return target;
        }

        public void validateTarget(Path path) {
            try {
                requireTargetPath(path);
            } catch (IOException exception) {
                throw new IllegalStateException("Player Data Mutation Target Became Unsafe: " + path, exception);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(this);
            }
        }
    }
}
