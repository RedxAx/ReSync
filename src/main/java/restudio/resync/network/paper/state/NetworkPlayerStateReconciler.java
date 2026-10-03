package restudio.resync.network.paper.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.ReSync;
import restudio.resync.network.NetworkStateReconciliationTask;
import restudio.resync.network.paper.NetworkPersistenceDrainController;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.StorageSafety;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class NetworkPlayerStateReconciler implements Listener {
    private static final Set<String> SUPPORTED_FAMILIES = Set.of("inventory", "ender-chest");
    private static final String BACKUP_DIRECTORY = "reconciliation-backups";
    private static final String BACKUP_OWNER = "reconciliation-backups";
    private static final String BACKUP_METADATA_SUFFIX = ".resync-backup.json";
    private static final String BACKUP_METADATA_TEMP_SUFFIX = ".resync-backup.tmp";
    private static final String BACKUP_METADATA_QUARANTINE = ".quarantine/backups";
    private static final String BACKUP_METADATA_KIND = "network-player-backup";
    private static final int MAXIMUM_BACKUP_METADATA_BYTES = 16_384;
    private static final int BACKUP_METADATA_RESERVATION_ATTEMPTS = 128;
    private static final String PLAYER_TEMP_SUFFIX = ".resync.tmp";
    private static final String PLAYER_TEMP_QUARANTINE = ".quarantine/player-data";
    private static final int PLAYER_TEMP_RESERVATION_ATTEMPTS = 128;
    private final ReSync plugin;
    private final NetworkPersistenceDrainController persistenceDrain;
    private final PaperPlayerDataMutationAdmission playerDataAdmission;
    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<ReconciliationContext> activeContexts = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> playerLockCounts = new HashMap<>();
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean shutdownPrepared = new AtomicBoolean();
    private final AtomicBoolean shutdownFinalized = new AtomicBoolean();
    private final CompletableFuture<Void> shutdownCompletion = new CompletableFuture<>();
    private final NetworkPersistenceDrainController.Registration backupRegistration;
    private Path backupRoot;
    private boolean backupQuiesced;
    private int activeReconciliations;

    private final class ReconciliationContext {
        private final NetworkStateReconciliationTask task;
        private final CompletableFuture<Void> result;
        private final NetworkPersistenceDrainController.Lease lease;
        private final Set<BukkitTask> scheduledTasks = ConcurrentHashMap.newKeySet();
        private final Set<Runnable> cleanupActions = ConcurrentHashMap.newKeySet();
        private final Object workMonitor = new Object();
        private final AtomicBoolean finished = new AtomicBoolean();
        private int activeWork;

        private ReconciliationContext(NetworkStateReconciliationTask task, CompletableFuture<Void> result,
                                      NetworkPersistenceDrainController.Lease lease) {
            this.task = task;
            this.result = result;
            this.lease = lease;
        }

        private void track(BukkitTask scheduledTask) {
            if (scheduledTask == null) {
                result.completeExceptionally(new IllegalStateException("ReSync player-state reconciliation task was not scheduled"));
                return;
            }
            scheduledTasks.add(scheduledTask);
            if (finished.get() || result.isDone()) {
                scheduledTask.cancel();
            }
        }

        private void cancel() {
            for (BukkitTask scheduledTask : scheduledTasks.toArray(BukkitTask[]::new)) {
                try {
                    scheduledTask.cancel();
                } catch (RuntimeException ignored) {
                }
            }
            result.completeExceptionally(new IllegalStateException("ReSync player-state reconciliation was cancelled"));
        }

        private void deferCleanup(Runnable action) {
            if (finished.get()) {
                action.run();
                return;
            }
            cleanupActions.add(action);
            if (finished.get() && cleanupActions.remove(action)) {
                action.run();
            }
        }

        private void closeCleanup() {
            for (Runnable action : cleanupActions.toArray(Runnable[]::new)) {
                if (cleanupActions.remove(action)) {
                    action.run();
                }
            }
        }

        private void run(Runnable action) {
            if (!enterWork()) {
                return;
            }
            try {
                action.run();
            } finally {
                exitWork();
            }
        }

        private boolean enterWork() {
            synchronized (workMonitor) {
                if (result.isDone()) {
                    return false;
                }
                activeWork++;
                return true;
            }
        }

        private void exitWork() {
            boolean complete;
            synchronized (workMonitor) {
                activeWork--;
                complete = result.isDone() && activeWork == 0;
            }
            if (complete) {
                completeReconciliation(this);
            }
        }

        private void resultCompleted() {
            boolean complete;
            synchronized (workMonitor) {
                complete = activeWork == 0;
            }
            if (complete) {
                completeReconciliation(this);
            }
        }
    }

    public NetworkPlayerStateReconciler(ReSync plugin, NetworkPersistenceDrainController persistenceDrain) {
        this(plugin, persistenceDrain, PaperPlayerDataMutationAdmission.shared());
    }

    public NetworkPlayerStateReconciler(ReSync plugin, NetworkPersistenceDrainController persistenceDrain,
                                        PaperPlayerDataMutationAdmission playerDataAdmission) {
        this.plugin = plugin;
        this.persistenceDrain = persistenceDrain;
        this.playerDataAdmission = Objects.requireNonNull(playerDataAdmission, "playerDataAdmission");
        this.backupRoot = persistenceDrain == null
            ? plugin == null ? null : Path.of(plugin.getDataFolder().getPath(), "network", BACKUP_DIRECTORY).toAbsolutePath().normalize()
            : persistenceDrain.persistenceRoot().resolve(BACKUP_DIRECTORY).toAbsolutePath().normalize();
        NetworkPersistenceDrainController.Registration registered = null;
        try {
            if (backupRoot != null) {
                MigrationPaths.requirePath(backupRoot, "reconciliationBackupRoot");
                createBackupRoot(backupRoot);
                validateBackupRoot(backupRoot);
            }
            if (persistenceDrain != null) {
                registered = persistenceDrain.register(new BackupComponent());
            }
        } catch (IOException | RuntimeException exception) {
            if (registered != null) {
                registered.close();
            }
            throw new IllegalStateException("Initialize ReSync player-state reconciliation backups failed", exception);
        }
        backupRegistration = registered;
    }

    public void start() {
        if (shutdownPrepared.get() || shutdownFinalized.get()) {
            throw new IllegalStateException("ReSync player-state reconciler is shut down");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        } catch (RuntimeException exception) {
            started.set(false);
            HandlerList.unregisterAll(this);
            throw exception;
        }
    }

    public PaperPlayerDataMutationAdmission playerDataAdmission() {
        return playerDataAdmission;
    }

    public CompletableFuture<Void> reconcile(NetworkStateReconciliationTask task) {
        Objects.requireNonNull(task, "task");
        NetworkPersistenceDrainController.Lease lease = null;
        if (persistenceDrain != null) {
            try {
                lease = persistenceDrain.acquire("player-reconciliation:" + task.transitionId());
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        if (!SUPPORTED_FAMILIES.containsAll(task.families())) {
            if (lease != null) {
                lease.close();
            }
            return CompletableFuture.failedFuture(new IllegalArgumentException("Only Item State Can Be Reconciled"));
        }
        if (task.playerIds().isEmpty()) {
            if (lease != null) {
                lease.close();
            }
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (lifecycleMonitor) {
            if (shutdownPrepared.get() || shutdownFinalized.get()) {
                if (lease != null) {
                    lease.close();
                }
                return CompletableFuture.failedFuture(new IllegalStateException("ReSync player-state reconciler is shut down"));
            }
            ReconciliationContext context = new ReconciliationContext(task, result, lease);
            result.whenComplete((unused, throwable) -> context.resultCompleted());
            try {
                lockPlayers(task.playerIds());
                activeReconciliations++;
                activeContexts.add(context);
                BukkitTask scheduledTask = Bukkit.getScheduler().runTask(plugin, () -> context.run(() -> {
                    try {
                        begin(context);
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                    }
                }));
                context.track(scheduledTask);
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        }
        return result;
    }

    private void completeReconciliation(ReconciliationContext context) {
        if (!context.finished.compareAndSet(false, true)) {
            return;
        }
        try {
            context.closeCleanup();
            if (context.lease != null) {
                context.lease.close();
            }
        } finally {
            synchronized (lifecycleMonitor) {
                activeContexts.remove(context);
                unlockPlayers(context.task.playerIds());
                activeReconciliations--;
                if (shutdownPrepared.get() && activeReconciliations == 0) {
                    finalizeShutdownLocked();
                }
            }
        }
    }

    private void lockPlayers(Set<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            playerLockCounts.merge(playerId, 1, Integer::sum);
            lockedPlayers.add(playerId);
        }
    }

    private void unlockPlayers(Set<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            Integer count = playerLockCounts.get(playerId);
            if (count == null || count <= 1) {
                playerLockCounts.remove(playerId);
                lockedPlayers.remove(playerId);
            } else {
                playerLockCounts.put(playerId, count - 1);
            }
        }
    }

    public CompletionStage<Void> shutdown() {
        try {
            prepareForShutdown();
            synchronized (lifecycleMonitor) {
                if (activeReconciliations == 0) {
                    finalizeShutdownLocked();
                }
                return shutdownCompletion;
            }
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public void prepareForShutdown() {
        requirePrimaryThread();
        ReconciliationContext[] contexts;
        synchronized (lifecycleMonitor) {
            if (!shutdownPrepared.compareAndSet(false, true)) {
                return;
            }
            started.set(false);
            contexts = activeContexts.toArray(ReconciliationContext[]::new);
        }
        for (ReconciliationContext context : contexts) {
            context.cancel();
        }
    }

    public void finalizeShutdown() {
        synchronized (lifecycleMonitor) {
            if (activeReconciliations == 0) {
                finalizeShutdownLocked();
            }
        }
    }

    private void finalizeShutdownLocked() {
        if (!shutdownFinalized.compareAndSet(false, true)) {
            return;
        }
        HandlerList.unregisterAll(this);
        playerLockCounts.clear();
        lockedPlayers.clear();
        if (backupRegistration != null) {
            backupRegistration.close();
        }
        shutdownCompletion.complete(null);
    }

    private static void requirePrimaryThread() {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ReSync player-state reconciler lifecycle must run on the Bukkit main thread");
        }
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (shutdownPrepared.get() || shutdownFinalized.get() || lockedPlayers.contains(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Player State Is Being Reconciled");
        }
    }

    private void begin(ReconciliationContext context) {
        NetworkStateReconciliationTask task = context.task;
        CompletableFuture<Void> result = context.result;
        if (result.isDone() || shutdownPrepared.get() || shutdownFinalized.get()) {
            return;
        }
        playerDataAdmission.refreshFromBukkit();
        PaperPlayerDataMutationAdmission.Lease dataLease = playerDataAdmission.acquire("player-reconciliation:" + task.transitionId());
        context.deferCleanup(dataLease::close);
        if (result.isDone() || shutdownPrepared.get() || shutdownFinalized.get()) {
            return;
        }
        Set<UUID> online = new LinkedHashSet<>();
        List<CompletableFuture<Void>> onlineClears = new ArrayList<>();
        Set<Path> directories = playerDataDirectories();
        for (UUID playerId : task.playerIds()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            online.add(playerId);
            CompletableFuture<Void> cleared = new CompletableFuture<>();
            boolean scheduled = player.getScheduler().execute(plugin, () -> context.run(() -> {
                if (shutdownPrepared.get() || shutdownFinalized.get()) {
                    cleared.completeExceptionally(new IllegalStateException("Player State Reconciliation Was Cancelled"));
                    return;
                }
                try {
                    try (PaperPlayerDataMutationAdmission.Lease ignored = playerDataAdmission.acquirePlayerData(
                        "player-reconciliation-online:" + player.getUniqueId(), player.getUniqueId(),
                        player.getWorld().getWorldFolder().toPath())) {
                        clearOnline(player, task.families());
                        player.saveData();
                    }
                    cleared.complete(null);
                } catch (RuntimeException exception) {
                    cleared.completeExceptionally(exception);
                }
            }), () -> cleared.completeExceptionally(new IllegalStateException("Player Left During State Reconciliation")), 1L);
            if (!scheduled) {
                cleared.completeExceptionally(new IllegalStateException("Player State Reconciliation Could Not Be Scheduled"));
            }
            onlineClears.add(cleared);
        }
        CompletableFuture.allOf(onlineClears.toArray(new CompletableFuture[0])).whenComplete((unused, throwable) ->
            context.run(() -> {
                if (throwable != null) {
                    result.completeExceptionally(throwable);
                    return;
                }
                synchronized (lifecycleMonitor) {
                    if (shutdownPrepared.get() || shutdownFinalized.get()) {
                        result.completeExceptionally(new IllegalStateException("ReSync player-state reconciler is shutting down"));
                        return;
                    }
                    try {
                        BukkitTask scheduledTask = Bukkit.getScheduler().runTaskAsynchronously(plugin,
                            () -> context.run(() -> reconcileOffline(context, online, directories)));
                        context.track(scheduledTask);
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                    }
                }
            }));
    }

    private void reconcileOffline(ReconciliationContext context, Set<UUID> online, Set<Path> directories) {
        NetworkStateReconciliationTask task = context.task;
        CompletableFuture<Void> result = context.result;
        if (result.isDone() || shutdownPrepared.get() || shutdownFinalized.get()) {
            return;
        }
        try {
            if (result.isDone() || shutdownPrepared.get() || shutdownFinalized.get()) {
                return;
            }
            Path backupDirectory = backupDirectory(task.transitionId());
            for (Path directory : directories) {
                if (result.isDone() || shutdownPrepared.get() || shutdownFinalized.get()) {
                    return;
                }
                recoverPlayerDataTemps(directory);
            }
            for (UUID playerId : task.playerIds()) {
                if (result.isDone() || shutdownPrepared.get() || shutdownFinalized.get()) {
                    return;
                }
                if (online.contains(playerId)) {
                    continue;
                }
                for (Path directory : directories) {
                    if (result.isDone() || shutdownPrepared.get() || shutdownFinalized.get()) {
                        return;
                    }
                    Path file = directory.resolve(playerId + ".dat");
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        String directoryId = worldDirectoryId(directory);
                        reconcileFile(file, backupDirectory.resolve(directoryId).resolve(playerId + ".dat"),
                            task.transitionId(), playerId, task.families());
                    }
                }
            }
            result.complete(null);
        } catch (Exception exception) {
            result.completeExceptionally(exception);
        }
    }

    private void reconcileFile(Path file, Path backup, String transitionId, UUID playerId,
                               Set<String> families) throws IOException {
        try (PaperPlayerDataMutationAdmission.Lease ignored = playerDataAdmission.acquire(
            "player-reconciliation-file:" + file.getFileName(), file)) {
            ignored.validateTarget(file);
            NbtTag root = Nbt.readCompressed(file);
            if (root == null || root.type() != Nbt.COMPOUND) {
                throw new IOException("Player Data Is Not A Compound: " + file.getFileName());
            }
            boolean changed = false;
            if (families.contains("inventory")) {
                changed |= Nbt.clearList(root, "Inventory");
            }
            if (families.contains("ender-chest")) {
                changed |= Nbt.clearList(root, "EnderItems");
            }
            if (!changed) {
                return;
            }
            ignored.validateTarget(file);
            ensureBackup(file, backup, transitionId, playerId);
            ignored.validateTarget(file);
            Nbt.writeCompressed(file, ignored, root);
        }
    }

    private void clearOnline(Player player, Set<String> families) {
        if (families.contains("inventory")) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[player.getInventory().getArmorContents().length]);
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            player.setItemOnCursor(new ItemStack(Material.AIR));
        }
        if (families.contains("ender-chest")) {
            player.getEnderChest().clear();
        }
        player.updateInventory();
    }

    private Set<Path> playerDataDirectories() {
        return Set.copyOf(playerDataAdmission.playerDataRoots());
    }

    private static UUID playerIdFromFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        String name = file.getFileName().toString();
        if (!name.endsWith(".dat")) {
            return null;
        }
        String value = name.substring(0, name.length() - 4);
        try {
            UUID playerId = UUID.fromString(value);
            return playerId.toString().equalsIgnoreCase(value) ? playerId : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static void recoverPlayerDataTemps(Path directory) throws IOException {
        Path root = MigrationPaths.requireDirectory(directory, "playerDataRoot");
        MigrationPaths.requireNoSymlinkTraversal(root, root);
        List<PlayerTemp> artifacts = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path candidate : stream.toList()) {
                PlayerTemp artifact = parsePlayerTemp(candidate);
                if (artifact == null) {
                    continue;
                }
                validatePlayerTemp(candidate, root);
                artifacts.add(artifact);
            }
        }
        if (artifacts.isEmpty()) {
            return;
        }
        Path quarantine = playerTempQuarantine(root);
        for (PlayerTemp artifact : artifacts) {
            quarantinePlayerTemp(artifact, root, quarantine);
        }
    }

    private static Path reservePlayerTemp(Path file) throws IOException {
        Path target = MigrationPaths.requirePath(file, "playerDataTarget");
        Path parent = target.getParent();
        UUID playerId = playerIdFromFile(target);
        if (parent == null || playerId == null) {
            throw new IOException("Player Data Target Is Not A Canonical Player File: " + target);
        }
        MigrationPaths.requireDirectory(parent, "playerDataRoot");
        MigrationPaths.requireNoSymlinkTraversal(parent, parent);
        String targetName = playerId + ".dat";
        for (int attempt = 0; attempt < PLAYER_TEMP_RESERVATION_ATTEMPTS; attempt++) {
            Path candidate = parent.resolve("." + targetName + "." + UUID.randomUUID() + PLAYER_TEMP_SUFFIX)
                .toAbsolutePath().normalize();
            if (!candidate.getParent().equals(parent)) {
                throw new IOException("Player Data Temporary Target Escaped Its Root: " + candidate);
            }
            try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            validatePlayerTemp(candidate, parent);
            return candidate;
        }
        throw new IOException("Unable To Reserve A Unique Player Data Temporary File: " + target);
    }

    private static void validatePlayerTemp(Path temp, Path root) throws IOException {
        Path normalizedRoot;
        Path normalized;
        try {
            normalizedRoot = MigrationPaths.requireDirectory(root, "playerDataRoot");
            normalized = MigrationPaths.requirePath(temp, "playerDataTemporaryTarget");
        } catch (IllegalArgumentException exception) {
            throw new IOException("Player Data Temporary Target Is Invalid", exception);
        }
        if (normalized.getParent() == null || !normalized.getParent().equals(normalizedRoot)
            || parsePlayerTemp(normalized) == null) {
            throw new IOException("Player Data Temporary Target Is Not Canonical: " + normalized);
        }
        MigrationPaths.requireNoSymlinkTraversal(normalizedRoot, normalizedRoot);
        if (Files.isSymbolicLink(normalized)
            || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Player Data Temporary Target Is Not A Safe Regular File: " + normalized);
        }
    }

    private static Path playerTempQuarantine(Path root) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "playerDataRoot");
        Path quarantine = normalizedRoot.resolve(PLAYER_TEMP_QUARANTINE).toAbsolutePath().normalize();
        if (!quarantine.startsWith(normalizedRoot) || quarantine.equals(normalizedRoot)) {
            throw new IOException("Player Data Temporary Quarantine Escaped Its Root: " + quarantine);
        }
        StorageSafety.createDirectoriesNoSymlinks(normalizedRoot, quarantine);
        MigrationPaths.requireNoSymlinkTraversal(normalizedRoot, quarantine);
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Player Data Temporary Quarantine Is Not A Safe Directory: " + quarantine);
        }
        return quarantine;
    }

    private static void quarantinePlayerTemp(PlayerTemp artifact, Path root, Path quarantine) throws IOException {
        Path source = MigrationPaths.requirePath(artifact.path(), "playerDataTemporarySource");
        if (source.getParent() == null || !source.getParent().equals(root)) {
            throw new IOException("Player Data Temporary Source Is Outside Its Root: " + source);
        }
        validatePlayerTemp(source, root);
        Path ownerDirectory = quarantine.resolve(artifact.playerId().toString()).toAbsolutePath().normalize();
        if (!ownerDirectory.startsWith(quarantine) || ownerDirectory.equals(quarantine)) {
            throw new IOException("Player Data Temporary Quarantine Owner Escaped Its Root: " + ownerDirectory);
        }
        StorageSafety.createDirectoriesNoSymlinks(quarantine, ownerDirectory);
        MigrationPaths.requireNoSymlinkTraversal(quarantine, ownerDirectory);
        Path destination = ownerDirectory.resolve(source.getFileName()).toAbsolutePath().normalize();
        if (!destination.getParent().equals(ownerDirectory)
            || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Player Data Temporary Quarantine Destination Is Already Occupied: " + destination);
        }
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Player Data Temporary Quarantine Requires Atomic Publication: " + source, exception);
        }
        validatePlayerTemp(destination, ownerDirectory);
        StorageSafety.forceDirectory(ownerDirectory);
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(root);
    }

    private static PlayerTemp parsePlayerTemp(Path path) throws IOException {
        if (path == null || path.getFileName() == null) {
            return null;
        }
        String name = path.getFileName().toString();
        if (!name.endsWith(PLAYER_TEMP_SUFFIX)) {
            return null;
        }
        String legacyBase = name.substring(0, name.length() - PLAYER_TEMP_SUFFIX.length());
        if (legacyBase.endsWith(".dat")) {
            String value = legacyBase.substring(0, legacyBase.length() - 4);
            UUID playerId = canonicalUuid(value);
            if (playerId != null) {
                return new PlayerTemp(path, playerId);
            }
            if (looksLikeUuid(value)) {
                throw ambiguousPlayerTemp(path);
            }
        }
        if (name.startsWith(".")) {
            String body = name.substring(1, name.length() - PLAYER_TEMP_SUFFIX.length());
            int marker = body.indexOf(".dat.");
            if (marker > 0) {
                String value = body.substring(0, marker);
                String token = body.substring(marker + ".dat.".length());
                UUID playerId = canonicalUuid(value);
                if (playerId == null || canonicalUuid(token) == null) {
                    throw ambiguousPlayerTemp(path);
                }
                return new PlayerTemp(path, playerId);
            }
        }
        String playerPrefix = legacyBase.startsWith(".") ? legacyBase.substring(1) : legacyBase;
        int marker = playerPrefix.indexOf(".dat.");
        if (marker > 0 && looksLikeUuid(playerPrefix.substring(0, marker))) {
            throw ambiguousPlayerTemp(path);
        }
        return null;
    }

    private static UUID canonicalUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean looksLikeUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static IOException ambiguousPlayerTemp(Path path) {
        return new IOException("Player Data Temporary Evidence Has An Ambiguous Name: " + path);
    }

    private record PlayerTemp(Path path, UUID playerId) {
    }

    private void validateBackupTarget(Path backup) throws IOException {
        Path root = backupRoot;
        if (root == null || backup == null) {
            throw new IOException("ReSync player-state reconciliation backup root is unavailable");
        }
        Path normalized = MigrationPaths.requirePath(backup, "reconciliationBackupTarget");
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IOException("ReSync reconciliation backup target escaped its root");
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("ReSync reconciliation backup target has no parent");
        }
        validateBackupPathComponents(root, parent);
        if (Files.isSymbolicLink(normalized)
            || Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync reconciliation backup target is not a safe regular file");
        }
    }

    private void validateBackupPathComponents(Path root, Path path) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "reconciliationBackupRoot");
        Path normalizedPath = MigrationPaths.requirePath(path, "reconciliationBackupParent");
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new IOException("ReSync reconciliation backup parent escaped its root");
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(part).normalize();
            if (Files.isSymbolicLink(current)
                || Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("ReSync reconciliation backup parent is not a safe directory");
            }
        }
    }

    private void ensureBackup(Path source, Path backup, String transitionId, UUID playerId) throws IOException {
        Path root = backupRoot;
        if (root == null) {
            throw new IOException("ReSync player-state reconciliation backup root is unavailable");
        }
        StorageSafety.createDirectoriesNoSymlinks(root, backup.getParent());
        validateBackupTarget(backup);
        Path metadata = backupMetadata(backup);
        validateBackupTarget(metadata);
        UUID sourcePlayerId = playerIdFromFile(source);
        if (sourcePlayerId == null || !sourcePlayerId.equals(playerId)) {
            throw new IOException("ReSync player-state backup player identity mismatch: " + source);
        }
        String sourcePath = source.toAbsolutePath().normalize().toString();
        String sourcePathHash = StorageSafety.sha256(sourcePath);
        String contentHash = sha256File(source);
        boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
        boolean metadataExists = Files.exists(metadata, LinkOption.NOFOLLOW_LINKS);
        if (metadataExists && !backupExists) {
            throw new IOException("ReSync player-state backup metadata exists without its backup: " + backup);
        }
        if (backupExists) {
            if (metadataExists) {
                validateMetadata(metadata, transitionId, playerId, sourcePath, sourcePathHash, contentHash);
            } else if (!legacyBackupCompatible(backup, source, transitionId, playerId)) {
                throw new IOException("ReSync player-state backup identity is ambiguous: " + backup);
            }
            validateBackupContent(backup, contentHash);
            if (!metadataExists) {
                writeMetadata(metadata, transitionId, playerId, sourcePath, sourcePathHash, contentHash);
            }
            return;
        }
        StorageSafety.copyIfAbsentAtomic(source, backup);
        validateBackupTarget(backup);
        if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync player-state backup was not published: " + backup);
        }
        validateBackupContent(backup, contentHash);
        writeMetadata(metadata, transitionId, playerId, sourcePath, sourcePathHash, contentHash);
    }

    private boolean legacyBackupCompatible(Path backup, Path source, String transitionId, UUID playerId) throws IOException {
        if (transitionId == null || transitionId.isBlank() || !transitionId.equals(safeName(transitionId))) {
            return false;
        }
        Path root = backupRoot;
        Path normalized = MigrationPaths.requirePath(backup, "reconciliationBackupTarget");
        Path worldDirectory = normalized.getParent();
        Path transitionDirectory = worldDirectory == null ? null : worldDirectory.getParent();
        return root != null
            && worldDirectory != null
            && transitionDirectory != null
            && transitionDirectory.getParent() != null
            && transitionDirectory.getParent().equals(root)
            && worldDirectory.getFileName().toString().equals(worldDirectoryId(source.getParent()))
            && transitionDirectory.getFileName().toString().equals(transitionId)
            && normalized.getFileName().toString().equals(playerId + ".dat")
            && normalized.getFileName().equals(source.getFileName());
    }

    private Path backupMetadata(Path backup) throws IOException {
        if (backup == null || backup.getFileName() == null) {
            throw new IOException("ReSync player-state backup metadata target is invalid");
        }
        return backup.resolveSibling(backup.getFileName() + BACKUP_METADATA_SUFFIX);
    }

    private void writeMetadata(Path metadata, String transitionId, UUID playerId, String sourcePath,
                               String sourcePathHash, String contentHash) throws IOException {
        validateBackupTarget(metadata);
        JsonObject object = new JsonObject();
        object.addProperty("version", 1);
        object.addProperty("kind", BACKUP_METADATA_KIND);
        object.addProperty("playerId", playerId.toString());
        object.addProperty("transitionId", transitionId == null ? "" : transitionId);
        object.addProperty("sourcePath", sourcePath);
        object.addProperty("sourcePathHash", sourcePathHash);
        object.addProperty("contentHash", contentHash);
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_BACKUP_METADATA_BYTES) {
            throw new IOException("ReSync player-state backup metadata exceeds its limit: " + metadata);
        }
        Path temporary = reserveBackupMetadataTemp(metadata);
        boolean published = false;
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            validateBackupTarget(temporary);
            Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            StorageSafety.forceDirectory(metadata.getParent());
        } catch (FileAlreadyExistsException alreadyPresent) {
            try {
                quarantineBackupMetadataTemp(temporary);
            } catch (IOException quarantineFailure) {
                alreadyPresent.addSuppressed(quarantineFailure);
                throw alreadyPresent;
            }
            validateMetadata(metadata, transitionId, playerId, sourcePath, sourcePathHash, contentHash);
            return;
        } catch (IOException | RuntimeException failure) {
            if (!published && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    quarantineBackupMetadataTemp(temporary);
                } catch (IOException | RuntimeException quarantineFailure) {
                    failure.addSuppressed(quarantineFailure);
                }
            }
            throw failure;
        }
        validateBackupTarget(metadata);
        validateMetadata(metadata, transitionId, playerId, sourcePath, sourcePathHash, contentHash);
    }

    private Path reserveBackupMetadataTemp(Path metadata) throws IOException {
        validateBackupTarget(metadata);
        Path parent = metadata.getParent();
        if (parent == null) {
            throw new IOException("ReSync player-state backup metadata target has no parent: " + metadata);
        }
        for (int attempt = 0; attempt < BACKUP_METADATA_RESERVATION_ATTEMPTS; attempt++) {
            Path temporary = parent.resolve("." + metadata.getFileName() + "." + UUID.randomUUID()
                + BACKUP_METADATA_TEMP_SUFFIX).toAbsolutePath().normalize();
            if (!temporary.getParent().equals(parent)) {
                throw new IOException("ReSync player-state backup metadata temporary target escaped its parent: " + temporary);
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            validateBackupTarget(temporary);
            return temporary;
        }
        throw new IOException("Unable to reserve a unique ReSync player-state backup metadata temporary file: " + metadata);
    }

    private void quarantineBackupMetadataTemp(Path temporary) throws IOException {
        validateBackupTarget(temporary);
        Path root = backupRoot;
        if (root == null) {
            throw new IOException("ReSync player-state reconciliation backup root is unavailable");
        }
        Path quarantine = root.resolve(BACKUP_METADATA_QUARANTINE).toAbsolutePath().normalize();
        StorageSafety.createDirectoriesNoSymlinks(root, quarantine);
        MigrationPaths.requireNoSymlinkTraversal(root, quarantine);
        Path destination = quarantine.resolve(temporary.getFileName()).toAbsolutePath().normalize();
        if (!destination.getParent().equals(quarantine)
            || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync player-state backup metadata quarantine destination is occupied: " + destination);
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("ReSync player-state backup metadata quarantine requires atomic publication: " + temporary,
                exception);
        }
        validateBackupTarget(destination);
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(root);
    }

    private void validateMetadata(Path metadata, String transitionId, UUID playerId, String sourcePath,
                                  String sourcePathHash, String contentHash) throws IOException {
        validateBackupTarget(metadata);
        if (Files.size(metadata) > MAXIMUM_BACKUP_METADATA_BYTES) {
            throw new IOException("ReSync player-state backup metadata exceeds its limit: " + metadata);
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("ReSync player-state backup metadata is not an object: " + metadata);
            }
            JsonObject object = parsed.getAsJsonObject();
            if (object.size() != 7
                || object.get("version") == null
                || object.get("kind") == null
                || object.get("playerId") == null
                || object.get("transitionId") == null
                || object.get("sourcePath") == null
                || object.get("sourcePathHash") == null
                || object.get("contentHash") == null
                || object.get("version").getAsInt() != 1
                || !BACKUP_METADATA_KIND.equals(object.get("kind").getAsString())
                || !playerId.toString().equals(object.get("playerId").getAsString())
                || !Objects.equals(transitionId == null ? "" : transitionId, object.get("transitionId").getAsString())
                || !sourcePath.equals(object.get("sourcePath").getAsString())
                || !sourcePathHash.equals(object.get("sourcePathHash").getAsString())
                || !contentHash.equals(object.get("contentHash").getAsString())) {
                throw new IOException("ReSync player-state backup metadata identity mismatch: " + metadata);
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("ReSync player-state backup metadata is malformed: " + metadata, exception);
        }
    }

    private void validateBackupContent(Path backup, String expectedHash) throws IOException {
        if (!expectedHash.equals(sha256File(backup))) {
            throw new IOException("ReSync player-state backup content hash mismatch: " + backup);
        }
    }

    private static String sha256File(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 Is Unavailable For Backup Content Identity", exception);
        }
    }

    private String worldDirectoryId(Path directory) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                directory.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return "world-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 Is Unavailable For Reconciliation Backup Identity", exception);
        }
    }

    private String safeName(String value) {
        String normalized = value == null ? "" : value.trim();
        String safe = normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "transition-" + StorageSafety.sha256(normalized).substring(0, 32) : safe;
    }

    private synchronized Path backupDirectory(String transitionId) throws IOException {
        if (backupRoot == null) {
            throw new IOException("ReSync player-state reconciliation backup root is unavailable");
        }
        if (backupQuiesced) {
            throw new IOException("ReSync player-state reconciliation backups are quiesced");
        }
        Path directory = backupRoot.resolve(safeName(transitionId)).normalize();
        if (!directory.startsWith(backupRoot) || directory.equals(backupRoot)) {
            throw new IOException("ReSync player-state reconciliation backup path escaped its root");
        }
        return directory;
    }

    private synchronized void validateBackupRoot(Path root) throws IOException {
        Path normalized = MigrationPaths.requirePath(root, "reconciliationBackupRoot");
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync player-state reconciliation backup root is unavailable");
        }
        MigrationPaths.requireNoSymlinkTraversal(normalized, normalized);
        recoverBackupMetadataTemps(normalized);
        try (var stream = Files.walk(normalized)) {
            for (Path entry : stream.toList()) {
                if (Files.isSymbolicLink(entry)
                    || (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))) {
                    throw new IOException("ReSync player-state reconciliation backup tree contains an invalid entry");
                }
            }
        }
    }

    private void recoverBackupMetadataTemps(Path root) throws IOException {
        Path quarantine = root.resolve(BACKUP_METADATA_QUARANTINE).toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            for (Path entry : stream.toList()) {
                if (entry.equals(root) || entry.startsWith(quarantine)) {
                    continue;
                }
                Path fileName = entry.getFileName();
                if (fileName == null || !fileName.toString().endsWith(BACKUP_METADATA_TEMP_SUFFIX)) {
                    continue;
                }
                if (!isBackupMetadataTempName(fileName.toString())) {
                    throw new IOException("ReSync player-state backup metadata temporary evidence has an ambiguous name: " + entry);
                }
                MigrationPaths.requireNoSymlinkTraversal(root, entry);
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("ReSync player-state backup metadata temporary evidence is not a regular file: " + entry);
                }
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        StorageSafety.createDirectoriesNoSymlinks(root, quarantine);
        MigrationPaths.requireNoSymlinkTraversal(root, quarantine);
        for (Path candidate : candidates) {
            Path destination = quarantine.resolve(candidate.getFileName()).toAbsolutePath().normalize();
            if (!destination.getParent().equals(quarantine)
                || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("ReSync player-state backup metadata quarantine destination is occupied: " + destination);
            }
            try {
                Files.move(candidate, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("ReSync player-state backup metadata recovery requires atomic publication: " + candidate,
                    exception);
            }
            if (Files.isSymbolicLink(destination)
                || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("ReSync player-state backup metadata quarantine is not a regular file: " + destination);
            }
        }
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(root);
    }

    private static boolean isBackupMetadataTempName(String name) {
        if (name == null || !name.startsWith(".") || !name.endsWith(BACKUP_METADATA_TEMP_SUFFIX)) {
            return false;
        }
        String body = name.substring(1, name.length() - BACKUP_METADATA_TEMP_SUFFIX.length());
        int marker = body.lastIndexOf('.');
        if (marker <= 0) {
            return false;
        }
        String metadataName = body.substring(0, marker);
        String token = body.substring(marker + 1);
        return metadataName.endsWith(BACKUP_METADATA_SUFFIX) && canonicalUuid(token) != null;
    }

    private static void createBackupRoot(Path directory) throws IOException {
        Path normalized = MigrationPaths.requirePath(directory, "reconciliationBackupRoot");
        Path existing = normalized;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("ReSync player-state reconciliation backup root has no existing parent");
            }
        }
        MigrationPaths.requireDirectory(existing, "reconciliationBackupRootParent");
        StorageSafety.createDirectoriesNoSymlinks(existing, normalized);
    }

    private synchronized void flushBackups() throws IOException {
        validateBackupRoot(backupRoot);
        StorageSafety.forceDirectory(backupRoot);
    }

    private synchronized void quiesceBackups() throws IOException {
        flushBackups();
        backupQuiesced = true;
    }

    private synchronized void resumeBackups() throws IOException {
        validateBackupRoot(backupRoot);
        backupQuiesced = false;
    }

    private synchronized void rebindBackups(Path candidateNetworkRoot) throws IOException {
        Path candidateRoot = MigrationPaths.requireDirectory(candidateNetworkRoot, "candidateNetworkRoot");
        Path candidate = candidateRoot.resolve(BACKUP_DIRECTORY).normalize();
        StorageSafety.createDirectoriesNoSymlinks(candidateRoot, candidate);
        validateBackupRoot(candidate);
        backupRoot = candidate;
    }

    private final class BackupComponent implements NetworkPersistenceDrainController.Component {
        @Override
        public String owner() {
            return BACKUP_OWNER;
        }

        @Override
        public Path activePath() {
            synchronized (NetworkPlayerStateReconciler.this) {
                return backupRoot;
            }
        }

        @Override
        public void flush() throws IOException {
            flushBackups();
        }

        @Override
        public void quiesce() throws IOException {
            quiesceBackups();
        }

        @Override
        public void resume() throws IOException {
            resumeBackups();
        }

        @Override
        public void validateRebind(Path candidateNetworkRoot) throws IOException {
            Path candidateRoot = MigrationPaths.requireDirectory(candidateNetworkRoot, "candidateNetworkRoot");
            Path candidate = candidateRoot.resolve(BACKUP_DIRECTORY).normalize();
            StorageSafety.createDirectoriesNoSymlinks(candidateRoot, candidate);
            validateBackupRoot(candidate);
        }

        @Override
        public void rebind(Path candidateNetworkRoot) throws IOException {
            rebindBackups(candidateNetworkRoot);
        }

        @Override
        public void healthCheck() throws IOException {
            validateBackupRoot(backupRoot);
        }
    }

    private record NbtTag(byte type, String name, Object value) {
    }

    private record NbtList(byte elementType, List<Object> values) {
    }

    private static final class Nbt {
        private static final byte END = 0;
        private static final byte BYTE = 1;
        private static final byte SHORT = 2;
        private static final byte INT = 3;
        private static final byte LONG = 4;
        private static final byte FLOAT = 5;
        private static final byte DOUBLE = 6;
        private static final byte BYTE_ARRAY = 7;
        private static final byte STRING = 8;
        private static final byte LIST = 9;
        private static final byte COMPOUND = 10;
        private static final byte INT_ARRAY = 11;
        private static final byte LONG_ARRAY = 12;
        private static final int MAXIMUM_NBT_DEPTH = 64;
        private static final int MAXIMUM_DECODED_BYTES = 64 * 1024 * 1024;
        private static final int MAXIMUM_COMPRESSED_BYTES = 32 * 1024 * 1024;
        private static final int MAXIMUM_COLLECTION_SIZE = 1_000_000;
        private static final long MAXIMUM_COLLECTION_ALLOCATION_BYTES = 16L * 1024 * 1024;
        private static final int MAXIMUM_STRING_BYTES = 65_535;
        private static final int MAXIMUM_NAME_BYTES = 4_096;

        private static NbtTag readCompressed(Path file) throws IOException {
            if (Files.size(file) > MAXIMUM_COMPRESSED_BYTES) {
                throw new IOException("NBT Compressed Data Exceeds Limit: " + file);
            }
            try (InputStream fileInput = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                 CompressedInputStream compressed = new CompressedInputStream(fileInput);
                 GZIPInputStream gzip = new GZIPInputStream(compressed);
                 NbtInput input = new NbtInput(gzip)) {
                NbtTag root = read(input, 0);
                if (input.read() != -1) {
                    throw new IOException("NBT Data Has Trailing Decoded Bytes");
                }
                return root;
            }
        }

        private static void writeCompressed(Path file, PaperPlayerDataMutationAdmission.Lease lease, NbtTag root) throws IOException {
            lease.validateTarget(file);
            Path temp = reservePlayerTemp(file);
            boolean published = false;
            try {
                try (OutputStream fileOutput = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     CappedOutputStream capped = new CappedOutputStream(fileOutput, MAXIMUM_COMPRESSED_BYTES,
                         "NBT Compressed Data Exceeds Limit");
                     DataOutputStream output = new DataOutputStream(new GZIPOutputStream(capped))) {
                    write(output, root);
                }
                validatePlayerTemp(temp, temp.getParent());
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    channel.force(true);
                }
                lease.validateTarget(file);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                published = true;
                lease.validateTarget(file);
                StorageSafety.forceDirectory(file.getParent());
            } catch (IOException | RuntimeException failure) {
                if (!published && Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        quarantinePlayerTemp(parsePlayerTemp(temp), temp.getParent(), playerTempQuarantine(temp.getParent()));
                    } catch (IOException | RuntimeException quarantineFailure) {
                        failure.addSuppressed(quarantineFailure);
                    }
                }
                throw failure;
            }
        }

        private static boolean clearList(NbtTag root, String name) {
            List<NbtTag> compound = compound(root.value());
            for (int index = 0; index < compound.size(); index++) {
                NbtTag tag = compound.get(index);
                if (tag.name().equals(name) && tag.type() == LIST) {
                    NbtList list = tag.value() instanceof NbtList value ? value : new NbtList(COMPOUND, List.of());
                    if (list.values().isEmpty()) {
                        return false;
                    }
                    compound.set(index, new NbtTag(LIST, name, new NbtList(list.elementType(), new ArrayList<>())));
                    return true;
                }
            }
            return false;
        }

        private static NbtTag read(NbtInput input, int depth) throws IOException {
            ensureDepth(depth);
            byte type = input.readByte();
            return type == END ? null : new NbtTag(type, readName(input), readPayload(input, type, depth));
        }

        private static Object readPayload(NbtInput input, byte type, int depth) throws IOException {
            return switch (type) {
                case BYTE -> input.readByte();
                case SHORT -> input.readShort();
                case INT -> input.readInt();
                case LONG -> input.readLong();
                case FLOAT -> input.readFloat();
                case DOUBLE -> input.readDouble();
                case BYTE_ARRAY -> readByteArray(input);
                case STRING -> readString(input);
                case LIST -> readList(input, depth + 1);
                case COMPOUND -> readCompound(input, depth + 1);
                case INT_ARRAY -> readIntArray(input);
                case LONG_ARRAY -> readLongArray(input);
                default -> throw new IOException("Unsupported NBT Tag " + type);
            };
        }

        private static List<NbtTag> readCompound(NbtInput input, int depth) throws IOException {
            ensureDepth(depth);
            List<NbtTag> tags = new ArrayList<>();
            while (true) {
                byte type = input.readByte();
                if (type == END) {
                    return tags;
                }
                if (tags.size() >= MAXIMUM_COLLECTION_SIZE) {
                    throw new IOException("NBT Compound Size Exceeds Limit");
                }
                tags.add(new NbtTag(type, readName(input), readPayload(input, type, depth)));
            }
        }

        private static NbtList readList(NbtInput input, int depth) throws IOException {
            ensureDepth(depth);
            byte type = input.readByte();
            int size = boundedSize(input.readInt());
            if (type == END && size != 0) {
                throw new IOException("NBT END List Has A Nonzero Size");
            }
            input.requireAllocation(Math.multiplyExact((long) size, Long.BYTES));
            List<Object> values = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                values.add(readPayload(input, type, depth));
            }
            return new NbtList(type, values);
        }

        private static byte[] readByteArray(NbtInput input) throws IOException {
            int size = boundedSize(input.readInt());
            input.requireAllocation(size);
            byte[] values = new byte[size];
            input.readFully(values);
            return values;
        }

        private static int[] readIntArray(NbtInput input) throws IOException {
            int size = boundedSize(input.readInt());
            input.requireAllocation(Math.multiplyExact((long) size, Integer.BYTES));
            int[] values = new int[size];
            for (int index = 0; index < values.length; index++) {
                values[index] = input.readInt();
            }
            return values;
        }

        private static long[] readLongArray(NbtInput input) throws IOException {
            int size = boundedSize(input.readInt());
            input.requireAllocation(Math.multiplyExact((long) size, Long.BYTES));
            long[] values = new long[size];
            for (int index = 0; index < values.length; index++) {
                values[index] = input.readLong();
            }
            return values;
        }

        private static void write(DataOutputStream output, NbtTag tag) throws IOException {
            output.writeByte(tag.type());
            if (tag.type() == END) {
                return;
            }
            writeString(output, tag.name());
            writePayload(output, tag.type(), tag.value());
        }

        private static void writePayload(DataOutputStream output, byte type, Object value) throws IOException {
            switch (type) {
                case BYTE -> output.writeByte(((Number) value).byteValue());
                case SHORT -> output.writeShort(((Number) value).shortValue());
                case INT -> output.writeInt(((Number) value).intValue());
                case LONG -> output.writeLong(((Number) value).longValue());
                case FLOAT -> output.writeFloat(((Number) value).floatValue());
                case DOUBLE -> output.writeDouble(((Number) value).doubleValue());
                case BYTE_ARRAY -> writeByteArray(output, (byte[]) value);
                case STRING -> writeString(output, String.valueOf(value));
                case LIST -> writeList(output, (NbtList) value);
                case COMPOUND -> writeCompound(output, compound(value));
                case INT_ARRAY -> writeIntArray(output, (int[]) value);
                case LONG_ARRAY -> writeLongArray(output, (long[]) value);
                default -> throw new IOException("Unsupported NBT Tag " + type);
            }
        }

        private static void writeCompound(DataOutputStream output, List<NbtTag> tags) throws IOException {
            for (NbtTag tag : tags) {
                write(output, tag);
            }
            output.writeByte(END);
        }

        private static void writeList(DataOutputStream output, NbtList list) throws IOException {
            output.writeByte(list.elementType());
            output.writeInt(list.values().size());
            for (Object value : list.values()) {
                writePayload(output, list.elementType(), value);
            }
        }

        private static void writeByteArray(DataOutputStream output, byte[] values) throws IOException {
            output.writeInt(values.length);
            output.write(values);
        }

        private static void writeIntArray(DataOutputStream output, int[] values) throws IOException {
            output.writeInt(values.length);
            for (int value : values) {
                output.writeInt(value);
            }
        }

        private static void writeLongArray(DataOutputStream output, long[] values) throws IOException {
            output.writeInt(values.length);
            for (long value : values) {
                output.writeLong(value);
            }
        }

        private static String readName(NbtInput input) throws IOException {
            return readString(input, MAXIMUM_NAME_BYTES, "NBT Name");
        }

        private static String readString(NbtInput input) throws IOException {
            return readString(input, MAXIMUM_STRING_BYTES, "NBT String");
        }

        private static String readString(NbtInput input, int maximumBytes, String description) throws IOException {
            int size = input.readUnsignedShort();
            if (size > maximumBytes) {
                throw new IOException(description + " Exceeds Limit");
            }
            input.requireAllocation(size);
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static void writeString(DataOutputStream output, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 65_535) {
                throw new IOException("NBT String Is Too Long");
            }
            output.writeShort(bytes.length);
            output.write(bytes);
        }

        @SuppressWarnings("unchecked")
        private static List<NbtTag> compound(Object value) {
            return (List<NbtTag>) value;
        }

        private static int boundedSize(int size) throws IOException {
            if (size < 0 || size > MAXIMUM_COLLECTION_SIZE) {
                throw new IOException("NBT Collection Size Is Invalid");
            }
            return size;
        }

        private static void ensureDepth(int depth) throws IOException {
            if (depth > MAXIMUM_NBT_DEPTH) {
                throw new IOException("NBT Nesting Depth Exceeds Limit");
            }
        }

        private static final class NbtInput extends DataInputStream {
            private final DecodedInputStream decoded;

            private NbtInput(InputStream input) {
                this(new DecodedInputStream(input));
            }

            private NbtInput(DecodedInputStream decoded) {
                super(decoded);
                this.decoded = decoded;
            }

            private void requireAllocation(long bytes) throws IOException {
                if (bytes < 0 || bytes > MAXIMUM_DECODED_BYTES - decoded.count()) {
                    throw new IOException("NBT Allocation Exceeds Decoded Data Limit");
                }
                if (bytes > MAXIMUM_COLLECTION_ALLOCATION_BYTES) {
                    throw new IOException("NBT Allocation Exceeds Collection Limit");
                }
            }
        }

        private static final class CompressedInputStream extends CappedInputStream {
            private CompressedInputStream(InputStream delegate) {
                super(delegate, MAXIMUM_COMPRESSED_BYTES, "NBT Compressed Data Exceeds Limit");
            }
        }

        private static final class DecodedInputStream extends CappedInputStream {
            private DecodedInputStream(InputStream delegate) {
                super(delegate, MAXIMUM_DECODED_BYTES, "NBT Decoded Data Exceeds Limit");
            }
        }

        private static final class CappedOutputStream extends OutputStream {
            private final OutputStream delegate;
            private final long maximum;
            private final String limitMessage;
            private long count;

            private CappedOutputStream(OutputStream delegate, long maximum, String limitMessage) {
                this.delegate = delegate;
                this.maximum = maximum;
                this.limitMessage = limitMessage;
            }

            @Override
            public void write(int value) throws IOException {
                ensureCapacity(1);
                delegate.write(value);
                count++;
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (offset < 0 || length < 0 || length > bytes.length - offset) {
                    throw new IndexOutOfBoundsException();
                }
                ensureCapacity(length);
                delegate.write(bytes, offset, length);
                count += length;
            }

            @Override
            public void flush() throws IOException {
                delegate.flush();
            }

            @Override
            public void close() throws IOException {
                delegate.close();
            }

            private void ensureCapacity(long bytes) throws IOException {
                if (bytes < 0 || bytes > maximum - count) {
                    throw new IOException(limitMessage);
                }
            }
        }

        private static class CappedInputStream extends InputStream {
            private final InputStream delegate;
            private final long maximum;
            private final String limitMessage;
            private long count;

            private CappedInputStream(InputStream delegate, long maximum, String limitMessage) {
                this.delegate = delegate;
                this.maximum = maximum;
                this.limitMessage = limitMessage;
            }

            @Override
            public int read() throws IOException {
                if (count >= maximum) {
                    int value = delegate.read();
                    if (value >= 0) {
                        throw new IOException(limitMessage);
                    }
                    return value;
                }
                int value = delegate.read();
                if (value >= 0) {
                    count++;
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (offset < 0 || length < 0 || length > bytes.length - offset) {
                    throw new IndexOutOfBoundsException();
                }
                long remaining = maximum - count;
                int permitted = (int) Math.min(length, remaining);
                if (permitted == 0) {
                    int read = delegate.read(bytes, offset, 1);
                    if (read > 0) {
                        throw new IOException(limitMessage);
                    }
                    return read;
                }
                int read = delegate.read(bytes, offset, permitted);
                if (read > 0) {
                    count += read;
                }
                return read;
            }

            @Override
            public void close() throws IOException {
                delegate.close();
            }

            protected long count() {
                return count;
            }
        }
    }
}
