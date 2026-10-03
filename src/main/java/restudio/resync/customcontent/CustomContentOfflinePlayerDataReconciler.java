package restudio.resync.customcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.advancement.PaperUnsafe;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

class CustomContentOfflinePlayerDataReconciler {
    private static final String BACKUP_DIRECTORY = "network/reconciliation-backups/custom-content";
    private static final String BACKUP_METADATA_SUFFIX = ".resync-backup.json";
    private static final String BACKUP_METADATA_TEMP_SUFFIX = ".resync-backup.tmp";
    private static final String BACKUP_METADATA_QUARANTINE = ".quarantine/backups";
    private static final String BACKUP_METADATA_KIND = "custom-content-player-backup";
    private static final int MAXIMUM_BACKUP_METADATA_BYTES = 16_384;
    private static final int BACKUP_METADATA_RESERVATION_ATTEMPTS = 128;
    private static final String PLAYER_TEMP_SUFFIX = ".resync.tmp";
    private static final String PLAYER_TEMP_QUARANTINE = ".quarantine/player-data";
    private static final int PLAYER_TEMP_RESERVATION_ATTEMPTS = 128;
    private static final ReconcileScheduler BUKKIT_SCHEDULER = (plugin, asynchronous, callback) -> {
        BukkitTask task = asynchronous
            ? Bukkit.getScheduler().runTaskAsynchronously(plugin, callback)
            : Bukkit.getScheduler().runTask(plugin, callback);
        return task::cancel;
    };
    private final CustomContentItemReconciler itemReconciler;
    private final PaperPlayerDataMutationAdmission playerDataAdmission;
    private final ReconcileScheduler scheduler;
    private final Predicate<JavaPlugin> pluginActivity;
    private final Map<Path, FileReconcileState> fileStates = new ConcurrentHashMap<>();
    private final Set<ScheduledCallback> scheduledCallbacks = ConcurrentHashMap.newKeySet();
    private final Set<JavaPlugin> lifecyclePlugins = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final AtomicBoolean backupMetadataTempsRecovered = new AtomicBoolean();

    CustomContentOfflinePlayerDataReconciler(CustomContentItemReconciler itemReconciler) {
        this(itemReconciler, PaperPlayerDataMutationAdmission.shared(), BUKKIT_SCHEDULER,
            plugin -> plugin != null && plugin.isEnabled());
    }

    CustomContentOfflinePlayerDataReconciler(CustomContentItemReconciler itemReconciler,
                                             PaperPlayerDataMutationAdmission playerDataAdmission) {
        this(itemReconciler, playerDataAdmission, BUKKIT_SCHEDULER,
            plugin -> plugin != null && plugin.isEnabled());
    }

    CustomContentOfflinePlayerDataReconciler(CustomContentItemReconciler itemReconciler,
                                             PaperPlayerDataMutationAdmission playerDataAdmission,
                                             ReconcileScheduler scheduler,
                                             Predicate<JavaPlugin> pluginActivity) {
        this.itemReconciler = itemReconciler;
        this.playerDataAdmission = Objects.requireNonNull(playerDataAdmission, "playerDataAdmission");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginActivity = Objects.requireNonNull(pluginActivity, "pluginActivity");
    }

    void shutdown() {
        ScheduledCallback[] callbacks;
        synchronized (lifecycleMonitor) {
            shutdown.compareAndSet(false, true);
            callbacks = scheduledCallbacks.toArray(ScheduledCallback[]::new);
        }
        for (ScheduledCallback callback : callbacks) {
            callback.cancel();
        }
        awaitScheduledCallbacks();
        for (FileReconcileState state : fileStates.values()) {
            synchronized (state) {
                state.pending.clear();
                state.active = false;
            }
        }
    }

    private void awaitScheduledCallbacks() {
        boolean interrupted = false;
        synchronized (lifecycleMonitor) {
            while (!scheduledCallbacks.isEmpty()) {
                try {
                    lifecycleMonitor.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void reconcileAsync(String contentId, boolean clearDeleted) {
        JavaPlugin plugin = ReSync.getInstance();
        if (shutdown.get() || !pluginActive(plugin)) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            reconcileAsync(plugin, contentId, clearDeleted);
            return;
        }
        scheduleSync(plugin, () -> reconcileAsync(plugin, contentId, clearDeleted));
    }

    private void reconcileAsync(JavaPlugin plugin, String contentId, boolean clearDeleted) {
        if (!pluginActive(plugin) || !PaperUnsafe.itemJsonRoundTripSupported()) {
            return;
        }
        if (backupMetadataTempsRecovered.compareAndSet(false, true)) {
            try {
                recoverBackupMetadataTemps(plugin);
            } catch (IOException | RuntimeException exception) {
                backupMetadataTempsRecovered.set(false);
                Log.warn("Failed to recover custom content backup metadata: " + exception.getMessage());
                return;
            }
        }
        playerDataAdmission.refreshFromBukkit();
        OfflinePlayerDataSnapshot snapshot = snapshotPlayerDataState();
        if (snapshot.directories().isEmpty()) {
            return;
        }
        scheduleAsync(plugin, () -> reconcileSnapshot(plugin, snapshot, contentId, clearDeleted));
    }

    private OfflinePlayerDataSnapshot snapshotPlayerDataState() {
        Set<UUID> onlinePlayers = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(player.getUniqueId());
        }
        Set<Path> directories = new LinkedHashSet<>(playerDataAdmission.playerDataRoots());
        return new OfflinePlayerDataSnapshot(directories, onlinePlayers);
    }

    private void reconcileSnapshot(JavaPlugin plugin, OfflinePlayerDataSnapshot snapshot, String contentId, boolean clearDeleted) {
        for (Path playerDataDirectory : playerDataDirectories(snapshot)) {
            if (!pluginActive(plugin)) {
                return;
            }
            reconcileDirectory(plugin, playerDataDirectory, contentId, clearDeleted, snapshot.onlinePlayers());
        }
    }

    private void reconcileDirectory(JavaPlugin plugin, Path directory, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
        if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            MigrationPaths.requireNoSymlinkTraversal(directory, directory);
            try (PaperPlayerDataMutationAdmission.Lease ignored = playerDataAdmission.acquire(
                "custom-content-player-data-temp-recovery:" + directory.getFileName())) {
                recoverPlayerDataTemps(directory);
            }
            try (var stream = Files.list(directory)) {
                for (Path file : stream.filter(path -> playerIdFromFile(path) != null).toList()) {
                    if (!pluginActive(plugin)) {
                        return;
                    }
                    if (isOnlinePlayerFile(file, onlinePlayers)) {
                        continue;
                    }
                    reconcileFile(plugin, file, contentId, clearDeleted, onlinePlayers);
                }
            }
        } catch (IOException exception) {
            Log.warn("Failed to scan offline player data: " + exception.getMessage());
        }
    }

    private void reconcileFile(JavaPlugin plugin, Path file, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
        if (shutdown.get()) {
            return;
        }
        Path fileKey = file.toAbsolutePath().normalize();
        FileReconcileState state = fileStates.computeIfAbsent(fileKey, ignored -> new FileReconcileState());
        synchronized (state) {
            if (state.active) {
                state.pending.add(new FileReconcileRequest(file, contentId, clearDeleted, onlinePlayers));
                return;
            }
            state.active = true;
        }
        reconcileFilePass(plugin, file, fileKey, state, contentId, clearDeleted, onlinePlayers);
    }

    private void reconcileFilePass(JavaPlugin plugin, Path file, Path fileKey, FileReconcileState state, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
        if (shutdown.get()) {
            finishFile(plugin, fileKey, state);
            return;
        }
        PaperPlayerDataMutationAdmission.Lease lease = null;
        boolean handedOff = false;
        try {
            lease = playerDataAdmission.acquire(
                "custom-content-offline-player-data:" + file.getFileName(), file);
            lease.validateTarget(file);
            NbtTag root = Nbt.readCompressed(file);
            if (root != null && root.type() == Nbt.COMPOUND && root.value() instanceof List<?>) {
                handedOff = transformFile(plugin, file, fileKey, state, lease, root, contentId, clearDeleted, onlinePlayers);
            }
        } catch (Exception exception) {
            Log.warn("Failed to reconcile offline player items in " + file.getFileName() + ": " + exception.getMessage());
        } finally {
            if (!handedOff) {
                closeFileLease(plugin, fileKey, state, lease);
            }
        }
    }

    private boolean transformFile(JavaPlugin plugin, Path file, Path fileKey, FileReconcileState state,
                                  PaperPlayerDataMutationAdmission.Lease lease, NbtTag root, String contentId,
                                  boolean clearDeleted, Set<UUID> onlinePlayers) {
        if (!pluginActive(plugin)) {
            return false;
        }
        return scheduleSync(plugin,
            () -> transformFileOnSync(plugin, file, fileKey, state, lease, root, contentId, clearDeleted, onlinePlayers),
            () -> closeFileLease(plugin, fileKey, state, lease));
    }

    private void transformFileOnSync(JavaPlugin plugin, Path file, Path fileKey, FileReconcileState state,
                                     PaperPlayerDataMutationAdmission.Lease lease, NbtTag root, String contentId,
                                     boolean clearDeleted, Set<UUID> onlinePlayers) {
        boolean handedOff = false;
        try {
            if (!pluginActive(plugin) || isOnlinePlayerFile(file, onlinePlayers) || isCurrentlyOnlinePlayerFile(file)) {
                return;
            }
            boolean changed = false;
            changed |= reconcileItemList(root, "Inventory", contentId, clearDeleted);
            changed |= reconcileItemList(root, "EnderItems", contentId, clearDeleted);
            if (changed) {
                handedOff = writeFile(plugin, file, fileKey, state, lease, root, contentId, onlinePlayers);
            }
        } catch (RuntimeException exception) {
            Log.warn("Failed to transform offline player items in " + file.getFileName() + ": " + exception.getMessage());
        } finally {
            if (!handedOff) {
                closeFileLease(plugin, fileKey, state, lease);
            }
        }
    }

    private boolean writeFile(JavaPlugin plugin, Path file, Path fileKey, FileReconcileState state,
                              PaperPlayerDataMutationAdmission.Lease lease, NbtTag root, String contentId,
                              Set<UUID> onlinePlayers) {
        if (!pluginActive(plugin)) {
            return false;
        }
        if (isOnlinePlayerFile(file, onlinePlayers) || isCurrentlyOnlinePlayerFile(file)) {
            return false;
        }
        return scheduleSync(plugin, () -> {
                try {
                    if (!pluginActive(plugin) || isOnlinePlayerFile(file, onlinePlayers) || isCurrentlyOnlinePlayerFile(file)) {
                        return;
                    }
                    Path backup = backupTarget(plugin, file, contentId);
                    lease.validateTarget(file);
                    ensureBackup(plugin, file, contentId, backup);
                    lease.validateTarget(file);
                    Nbt.writeCompressed(file, lease, root);
                } catch (Exception exception) {
                    Log.warn("Failed to write reconciled offline player items in " + file.getFileName() + ": " + exception.getMessage());
                } finally {
                    closeFileLease(plugin, fileKey, state, lease);
                }
            },
            () -> closeFileLease(plugin, fileKey, state, lease));
    }

    private void closeFileLease(JavaPlugin plugin, Path fileKey, FileReconcileState state,
                                PaperPlayerDataMutationAdmission.Lease lease) {
        if (lease != null) {
            lease.close();
        }
        finishFile(plugin, fileKey, state);
    }

    private void finishFile(JavaPlugin plugin, Path fileKey, FileReconcileState state) {
        FileReconcileRequest pending;
        synchronized (state) {
            if (shutdown.get() || !pluginActive(plugin)) {
                state.pending.clear();
                state.active = false;
                return;
            }
            pending = state.pending.pollFirst();
            if (pending == null) {
                state.active = false;
                return;
            }
        }
        FileReconcileRequest next = pending;
        if (!scheduleAsync(plugin,
            () -> reconcileFilePass(plugin, next.file(), fileKey, state, next.contentId(), next.clearDeleted(), next.onlinePlayers()),
            () -> cancelPendingFile(state, next))) {
            synchronized (state) {
                if (shutdown.get() || !pluginActive(plugin)) {
                    state.pending.clear();
                } else {
                    state.pending.addFirst(next);
                }
                state.active = false;
            }
        }
    }

    private void cancelPendingFile(FileReconcileState state, FileReconcileRequest request) {
        synchronized (state) {
            state.pending.remove(request);
            state.active = false;
        }
    }

    private boolean reconcileItemList(NbtTag root, String key, String contentId, boolean clearDeleted) {
        NbtTag tag = Nbt.find(root, key);
        if (tag == null || tag.type() != Nbt.LIST || !(tag.value() instanceof NbtList list) || list.elementType() != Nbt.COMPOUND) {
            return false;
        }
        boolean changed = false;
        for (int index = 0; index < list.values().size(); index++) {
            Object value = list.values().get(index);
            if (!(value instanceof List<?> compound)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<NbtTag> currentCompound = (List<NbtTag>) compound;
            List<NbtTag> updatedCompound = reconcileItemCompound(currentCompound, contentId, clearDeleted);
            if (updatedCompound != currentCompound) {
                list.values().set(index, updatedCompound);
                changed = true;
            }
        }
        return changed;
    }

    private List<NbtTag> reconcileItemCompound(List<NbtTag> compound, String contentId, boolean clearDeleted) {
        JsonObject json = Nbt.toJsonObject(compound);
        if (!json.has("count") && json.has("Count")) {
            json.add("count", json.get("Count"));
        }
        json.remove("Count");
        if (!json.has("id") || !json.has("count")) {
            return compound;
        }
        ItemStack item;
        try {
            item = PaperUnsafe.deserializeItemFromJson(json);
        } catch (RuntimeException ignored) {
            return compound;
        }
        ItemStack updated = itemReconciler.transformItem(item, contentId, clearDeleted);
        if (updated == item) {
            return compound;
        }
        JsonObject updatedJson;
        try {
            updatedJson = PaperUnsafe.serializeItemAsJson(updated);
        } catch (RuntimeException ignored) {
            return compound;
        }
        updatedJson.remove("DataVersion");
        NbtTag slot = Nbt.find(compound, "Slot");
        List<NbtTag> replacement = Nbt.fromJsonObject(updatedJson, compound);
        if (slot != null) {
            Nbt.put(replacement, slot);
        }
        return replacement;
    }

    private boolean isOnlinePlayerFile(Path file, Set<UUID> onlinePlayers) {
        UUID playerId = playerIdFromFile(file);
        return playerId != null && onlinePlayers.contains(playerId);
    }

    private boolean isCurrentlyOnlinePlayerFile(Path file) {
        if (Bukkit.getServer() == null) {
            return false;
        }
        UUID playerId = playerIdFromFile(file);
        return playerId != null && Bukkit.getPlayer(playerId) != null;
    }

    static UUID playerIdFromFile(Path file) {
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

    private Set<Path> playerDataDirectories(OfflinePlayerDataSnapshot snapshot) {
        return Set.copyOf(snapshot.directories());
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
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "playerDataRoot");
        Path normalized = Objects.requireNonNull(temp, "temp").toAbsolutePath().normalize();
        if (normalized.getParent() == null || !normalized.getParent().equals(normalizedRoot)
            || parsePlayerTemp(normalized) == null) {
            throw new IOException("Player Data Temporary Target Is Not Canonical: " + normalized);
        }
        MigrationPaths.requireNoSymlinkTraversal(normalizedRoot, normalized);
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

    private Path backupTarget(JavaPlugin plugin, Path source, String contentId) throws IOException {
        Path dataRoot = MigrationPaths.requireDirectory(plugin.getDataFolder().toPath(), "pluginDataRoot");
        Path root = dataRoot.resolve(BACKUP_DIRECTORY).toAbsolutePath().normalize();
        StorageSafety.createDirectoriesNoSymlinks(dataRoot, root);
        MigrationPaths.requireDirectory(root, "customContentReconciliationBackupRoot");
        String content = safeContentId(contentId);
        String sourceId = sourceIdentity(source);
        Path target = root.resolve(content).resolve(sourceId).resolve(source.getFileName());
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Custom content reconciliation backup target escaped its root");
        }
        return target;
    }

    private String safeContentId(String contentId) {
        String content = contentId == null || contentId.isBlank() ? "unknown" : contentId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return content.isBlank() ? "unknown" : content;
    }

    private String sourceIdentity(Path source) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                source.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return "source-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 Is Unavailable For Custom Content Backup Identity", exception);
        }
    }

    private void ensureBackup(JavaPlugin plugin, Path source, String contentId, Path backup) throws IOException {
        Path dataRoot = MigrationPaths.requireDirectory(plugin.getDataFolder().toPath(), "pluginDataRoot");
        Path root = dataRoot.resolve(BACKUP_DIRECTORY).toAbsolutePath().normalize();
        StorageSafety.createDirectoriesNoSymlinks(dataRoot, root);
        StorageSafety.createDirectoriesNoSymlinks(root, backup.getParent());
        validateBackupTarget(plugin, backup);
        Path metadata = backupMetadata(backup);
        validateBackupTarget(plugin, metadata);
        UUID playerId = playerIdFromFile(source);
        if (playerId == null) {
            throw new IOException("Custom content backup source is not a canonical player file: " + source);
        }
        String sourcePath = source.toAbsolutePath().normalize().toString();
        String sourcePathHash = StorageSafety.sha256(sourcePath);
        String contentHash = sha256File(source);
        boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
        boolean metadataExists = Files.exists(metadata, LinkOption.NOFOLLOW_LINKS);
        if (metadataExists && !backupExists) {
            throw new IOException("Custom content backup metadata exists without its backup: " + backup);
        }
        if (backupExists) {
            if (metadataExists) {
                validateMetadata(plugin, metadata, playerId, contentId, sourcePath, sourcePathHash, contentHash);
            } else if (!legacyBackupCompatible(plugin, backup, source, contentId)) {
                throw new IOException("Custom content backup identity is ambiguous: " + backup);
            }
            validateBackupContent(backup, contentHash);
            if (!metadataExists) {
                writeMetadata(plugin, metadata, playerId, contentId, sourcePath, sourcePathHash, contentHash);
            }
            return;
        }
        StorageSafety.copyIfAbsentAtomic(source, backup);
        validateBackupTarget(plugin, backup);
        if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Custom content backup was not published: " + backup);
        }
        validateBackupContent(backup, contentHash);
        writeMetadata(plugin, metadata, playerId, contentId, sourcePath, sourcePathHash, contentHash);
    }

    private boolean legacyBackupCompatible(JavaPlugin plugin, Path backup, Path source, String contentId) throws IOException {
        if (contentId == null || contentId.isBlank() || !contentId.equals(safeContentId(contentId))) {
            return false;
        }
        Path root = plugin.getDataFolder().toPath().resolve(BACKUP_DIRECTORY).toAbsolutePath().normalize();
        Path normalized = MigrationPaths.requirePath(backup, "customContentReconciliationBackupTarget");
        Path sourceDirectory = normalized.getParent();
        Path contentDirectory = sourceDirectory == null ? null : sourceDirectory.getParent();
        return sourceDirectory != null
            && contentDirectory != null
            && contentDirectory.getParent() != null
            && contentDirectory.getParent().equals(root)
            && contentDirectory.getFileName().toString().equals(contentId)
            && sourceDirectory.getFileName().toString().equals(sourceIdentity(source))
            && normalized.getFileName().equals(source.getFileName());
    }

    private Path backupMetadata(Path backup) throws IOException {
        if (backup == null || backup.getFileName() == null) {
            throw new IOException("Custom content backup metadata target is invalid");
        }
        return backup.resolveSibling(backup.getFileName() + BACKUP_METADATA_SUFFIX);
    }

    private void writeMetadata(JavaPlugin plugin, Path metadata, UUID playerId, String contentId,
                               String sourcePath, String sourcePathHash, String contentHash) throws IOException {
        validateBackupTarget(plugin, metadata);
        JsonObject object = new JsonObject();
        object.addProperty("version", 1);
        object.addProperty("kind", BACKUP_METADATA_KIND);
        object.addProperty("playerId", playerId.toString());
        object.addProperty("contentId", contentId == null ? "" : contentId);
        object.addProperty("sourcePath", sourcePath);
        object.addProperty("sourcePathHash", sourcePathHash);
        object.addProperty("contentHash", contentHash);
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_BACKUP_METADATA_BYTES) {
            throw new IOException("Custom content backup metadata exceeds its limit: " + metadata);
        }
        Path temporary = reserveBackupMetadataTemp(plugin, metadata);
        boolean published = false;
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            validateBackupTarget(plugin, temporary);
            Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            StorageSafety.forceDirectory(metadata.getParent());
        } catch (FileAlreadyExistsException alreadyPresent) {
            try {
                quarantineBackupMetadataTemp(plugin, temporary);
            } catch (IOException quarantineFailure) {
                alreadyPresent.addSuppressed(quarantineFailure);
                throw alreadyPresent;
            }
            validateMetadata(plugin, metadata, playerId, contentId, sourcePath, sourcePathHash, contentHash);
            return;
        } catch (IOException | RuntimeException failure) {
            if (!published && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    quarantineBackupMetadataTemp(plugin, temporary);
                } catch (IOException | RuntimeException quarantineFailure) {
                    failure.addSuppressed(quarantineFailure);
                }
            }
            throw failure;
        }
        validateBackupTarget(plugin, metadata);
        validateMetadata(plugin, metadata, playerId, contentId, sourcePath, sourcePathHash, contentHash);
    }

    private Path reserveBackupMetadataTemp(JavaPlugin plugin, Path metadata) throws IOException {
        validateBackupTarget(plugin, metadata);
        Path parent = metadata.getParent();
        if (parent == null) {
            throw new IOException("Custom content backup metadata target has no parent: " + metadata);
        }
        for (int attempt = 0; attempt < BACKUP_METADATA_RESERVATION_ATTEMPTS; attempt++) {
            Path temporary = parent.resolve("." + metadata.getFileName() + "." + UUID.randomUUID()
                + BACKUP_METADATA_TEMP_SUFFIX).toAbsolutePath().normalize();
            if (!temporary.getParent().equals(parent)) {
                throw new IOException("Custom content backup metadata temporary target escaped its parent: " + temporary);
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            validateBackupTarget(plugin, temporary);
            return temporary;
        }
        throw new IOException("Unable to reserve a unique custom content backup metadata temporary file: " + metadata);
    }

    private void quarantineBackupMetadataTemp(JavaPlugin plugin, Path temporary) throws IOException {
        validateBackupTarget(plugin, temporary);
        Path dataRoot = MigrationPaths.requireDirectory(plugin.getDataFolder().toPath(), "pluginDataRoot");
        Path root = dataRoot.resolve(BACKUP_DIRECTORY).toAbsolutePath().normalize();
        StorageSafety.createDirectoriesNoSymlinks(dataRoot, root);
        Path quarantine = root.resolve(BACKUP_METADATA_QUARANTINE).toAbsolutePath().normalize();
        StorageSafety.createDirectoriesNoSymlinks(root, quarantine);
        MigrationPaths.requireNoSymlinkTraversal(root, quarantine);
        Path destination = quarantine.resolve(temporary.getFileName()).toAbsolutePath().normalize();
        if (!destination.getParent().equals(quarantine)
            || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Custom content backup metadata quarantine destination is occupied: " + destination);
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Custom content backup metadata quarantine requires atomic publication: " + temporary,
                exception);
        }
        validateBackupTarget(plugin, destination);
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(root);
    }

    private void validateMetadata(JavaPlugin plugin, Path metadata, UUID playerId, String contentId,
                                  String sourcePath, String sourcePathHash, String contentHash) throws IOException {
        validateBackupTarget(plugin, metadata);
        if (Files.size(metadata) > MAXIMUM_BACKUP_METADATA_BYTES) {
            throw new IOException("Custom content backup metadata exceeds its limit: " + metadata);
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Custom content backup metadata is not an object: " + metadata);
            }
            JsonObject object = parsed.getAsJsonObject();
            if (object.size() != 7
                || object.get("version") == null
                || object.get("kind") == null
                || object.get("playerId") == null
                || object.get("contentId") == null
                || object.get("sourcePath") == null
                || object.get("sourcePathHash") == null
                || object.get("contentHash") == null
                || object.get("version").getAsInt() != 1
                || !BACKUP_METADATA_KIND.equals(object.get("kind").getAsString())
                || !playerId.toString().equals(object.get("playerId").getAsString())
                || !Objects.equals(contentId == null ? "" : contentId, object.get("contentId").getAsString())
                || !sourcePath.equals(object.get("sourcePath").getAsString())
                || !sourcePathHash.equals(object.get("sourcePathHash").getAsString())
                || !contentHash.equals(object.get("contentHash").getAsString())) {
                throw new IOException("Custom content backup metadata identity mismatch: " + metadata);
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Custom content backup metadata is malformed: " + metadata, exception);
        }
    }

    private void validateBackupContent(Path backup, String expectedHash) throws IOException {
        if (!expectedHash.equals(sha256File(backup))) {
            throw new IOException("Custom content backup content hash mismatch: " + backup);
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

    private void validateBackupTarget(JavaPlugin plugin, Path target) throws IOException {
        Path root = plugin.getDataFolder().toPath().resolve(BACKUP_DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireDirectory(root, "customContentReconciliationBackupRoot");
        Path normalized = MigrationPaths.requirePath(target, "customContentReconciliationBackupTarget");
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IOException("Custom content reconciliation backup target escaped its root");
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Custom content reconciliation backup target has no parent");
        }
        validateBackupPathComponents(root, parent);
        if (Files.isSymbolicLink(normalized)
            || Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Custom content reconciliation backup target is not safe");
        }
        StorageSafety.forceDirectory(parent);
    }

    private void validateBackupPathComponents(Path root, Path path) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "customContentReconciliationBackupRoot");
        Path normalizedPath = MigrationPaths.requirePath(path, "customContentReconciliationBackupParent");
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new IOException("Custom content reconciliation backup parent escaped its root");
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(part).normalize();
            if (Files.isSymbolicLink(current)
                || Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Custom content reconciliation backup parent is not a safe directory");
            }
        }
    }

    private void recoverBackupMetadataTemps(JavaPlugin plugin) throws IOException {
        Path dataRoot = MigrationPaths.requireDirectory(plugin.getDataFolder().toPath(), "pluginDataRoot");
        Path root = dataRoot.resolve(BACKUP_DIRECTORY).toAbsolutePath().normalize();
        StorageSafety.createDirectoriesNoSymlinks(dataRoot, root);
        MigrationPaths.requireDirectory(root, "customContentReconciliationBackupRoot");
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
                    throw new IOException("Custom content backup metadata temporary evidence has an ambiguous name: " + entry);
                }
                MigrationPaths.requireNoSymlinkTraversal(root, entry);
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Custom content backup metadata temporary evidence is not a regular file: " + entry);
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
                throw new IOException("Custom content backup metadata quarantine destination is occupied: " + destination);
            }
            try {
                Files.move(candidate, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Custom content backup metadata recovery requires atomic publication: " + candidate,
                    exception);
            }
            if (Files.isSymbolicLink(destination)
                || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Custom content backup metadata quarantine is not a regular file: " + destination);
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

    private boolean pluginActive(JavaPlugin plugin) {
        return pluginActivity.test(plugin);
    }

    private boolean scheduleSync(JavaPlugin plugin, Runnable action) {
        return scheduleSync(plugin, action, () -> {
        });
    }

    private boolean scheduleSync(JavaPlugin plugin, Runnable action, Runnable cancellation) {
        return schedule(plugin, false, action, cancellation);
    }

    private boolean scheduleAsync(JavaPlugin plugin, Runnable action) {
        return scheduleAsync(plugin, action, () -> {
        });
    }

    private boolean scheduleAsync(JavaPlugin plugin, Runnable action, Runnable cancellation) {
        return schedule(plugin, true, action, cancellation);
    }

    private boolean schedule(JavaPlugin plugin, boolean asynchronous, Runnable action, Runnable cancellation) {
        if (shutdown.get() || !pluginActive(plugin)) {
            return false;
        }
        registerLifecycle(plugin);
        ScheduledCallback callback = new ScheduledCallback(action, cancellation);
        synchronized (lifecycleMonitor) {
            if (shutdown.get()) {
                return false;
            }
            scheduledCallbacks.add(callback);
        }
        try {
            ScheduledTask task = scheduler.schedule(plugin, asynchronous, callback);
            if (task == null) {
                callback.cancel();
                return true;
            }
            callback.accept(task);
            return true;
        } catch (RuntimeException ignored) {
            callback.cancel();
            return true;
        }
    }

    private void registerLifecycle(JavaPlugin plugin) {
        if (scheduler != BUKKIT_SCHEDULER || plugin == null || Bukkit.getServer() == null || !Bukkit.isPrimaryThread()
            || !lifecyclePlugins.add(plugin)) {
            return;
        }
        Listener listener = new Listener() {
        };
        try {
            plugin.getServer().getPluginManager().registerEvent(PluginDisableEvent.class, listener, EventPriority.MONITOR,
                (ignored, event) -> {
                    if (((PluginDisableEvent) event).getPlugin() == plugin) {
                        shutdown();
                    }
                }, plugin);
        } catch (RuntimeException exception) {
            lifecyclePlugins.remove(plugin);
        }
    }

    interface ReconcileScheduler {
        ScheduledTask schedule(JavaPlugin plugin, boolean asynchronous, Runnable callback);
    }

    interface ScheduledTask {
        void cancel();
    }

    private final class ScheduledCallback implements Runnable {
        private static final int NEW = 0;
        private static final int ACCEPTED = 1;
        private static final int RUNNING = 2;
        private static final int COMPLETED = 3;
        private static final int CANCELLED = 4;

        private final Runnable action;
        private final Runnable cancellation;
        private int state = NEW;
        private ScheduledTask task;

        private ScheduledCallback(Runnable action, Runnable cancellation) {
            this.action = Objects.requireNonNull(action, "action");
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        }

        private synchronized boolean accept(ScheduledTask scheduledTask) {
            if (state != NEW) {
                if (state == CANCELLED && task == null) {
                    scheduledTask.cancel();
                }
                return false;
            }
            task = scheduledTask;
            state = ACCEPTED;
            return true;
        }

        private void cancel() {
            ScheduledTask scheduledTask;
            synchronized (this) {
                if (state != NEW && state != ACCEPTED) {
                    return;
                }
                state = CANCELLED;
                scheduledTask = task;
            }
            try {
                if (scheduledTask != null) {
                    scheduledTask.cancel();
                }
            } finally {
                try {
                    cancellation.run();
                } finally {
                    synchronized (lifecycleMonitor) {
                        scheduledCallbacks.remove(this);
                        lifecycleMonitor.notifyAll();
                    }
                }
            }
        }

        @Override
        public void run() {
            synchronized (this) {
                if (state != ACCEPTED) {
                    return;
                }
                state = RUNNING;
            }
            try {
                action.run();
            } finally {
                synchronized (this) {
                    state = COMPLETED;
                }
                synchronized (lifecycleMonitor) {
                    scheduledCallbacks.remove(this);
                    lifecycleMonitor.notifyAll();
                }
            }
        }
    }

    private record OfflinePlayerDataSnapshot(Set<Path> directories, Set<UUID> onlinePlayers) {
    }

    private record FileReconcileRequest(Path file, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
    }

    private static final class FileReconcileState {
        private final ArrayDeque<FileReconcileRequest> pending = new ArrayDeque<>();
        private boolean active;
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

        private Nbt() {
        }

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

        private static NbtTag read(NbtInput input, int depth) throws IOException {
            ensureDepth(depth);
            byte type = input.readByte();
            if (type == END) {
                return null;
            }
            String name = readName(input);
            return new NbtTag(type, name, readPayload(input, type, depth));
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
            byte elementType = input.readByte();
            int size = boundedSize(input.readInt());
            if (elementType == END && size != 0) {
                throw new IOException("NBT END List Has A Nonzero Size");
            }
            input.requireAllocation(Math.multiplyExact((long) size, Long.BYTES));
            List<Object> values = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                values.add(readPayload(input, elementType, depth));
            }
            return new NbtList(elementType, values);
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
                case BYTE_ARRAY -> writeByteArray(output, value instanceof byte[] bytes ? bytes : new byte[0]);
                case STRING -> writeString(output, String.valueOf(value));
                case LIST -> writeList(output, value instanceof NbtList list ? list : new NbtList(END, new ArrayList<>()));
                case COMPOUND -> writeCompound(output, compoundValue(value));
                case INT_ARRAY -> writeIntArray(output, value instanceof int[] ints ? ints : new int[0]);
                case LONG_ARRAY -> writeLongArray(output, value instanceof long[] longs ? longs : new long[0]);
                default -> {
                }
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

        private static void writeString(DataOutputStream output, String value) throws IOException {
            byte[] bytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (bytes.length > MAXIMUM_STRING_BYTES) {
                throw new IOException("NBT String Is Too Long");
            }
            output.writeShort(bytes.length);
            output.write(bytes);
        }

        private static NbtTag find(NbtTag tag, String name) {
            if (tag == null || tag.type() != COMPOUND) {
                return null;
            }
            return find(compoundValue(tag.value()), name);
        }

        private static NbtTag find(List<NbtTag> compound, String name) {
            if (compound == null) {
                return null;
            }
            for (NbtTag child : compound) {
                if (name.equals(child.name())) {
                    return child;
                }
            }
            return null;
        }

        private static void put(List<NbtTag> compound, NbtTag tag) {
            for (int index = 0; index < compound.size(); index++) {
                if (compound.get(index).name().equals(tag.name())) {
                    compound.set(index, tag);
                    return;
                }
            }
            compound.add(tag);
        }

        private static JsonObject toJsonObject(List<NbtTag> compound) {
            JsonObject object = new JsonObject();
            for (NbtTag tag : compound) {
                if (!"Slot".equals(tag.name())) {
                    object.add(tag.name(), toJson(tag));
                }
            }
            return object;
        }

        private static JsonElement toJson(NbtTag tag) {
            return switch (tag.type()) {
                case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> new JsonPrimitive((Number) tag.value());
                case STRING -> new JsonPrimitive(String.valueOf(tag.value()));
                case COMPOUND -> toJsonObject(compoundValue(tag.value()));
                case LIST -> toJsonArray((NbtList) tag.value());
                case BYTE_ARRAY -> toJsonArray((byte[]) tag.value());
                case INT_ARRAY -> toJsonArray((int[]) tag.value());
                case LONG_ARRAY -> toJsonArray((long[]) tag.value());
                default -> new JsonObject();
            };
        }

        private static JsonArray toJsonArray(NbtList list) {
            JsonArray array = new JsonArray();
            for (Object value : list.values()) {
                array.add(toJson(new NbtTag(list.elementType(), "", value)));
            }
            return array;
        }

        private static JsonArray toJsonArray(byte[] values) {
            JsonArray array = new JsonArray();
            for (byte value : values) {
                array.add(value);
            }
            return array;
        }

        private static JsonArray toJsonArray(int[] values) {
            JsonArray array = new JsonArray();
            for (int value : values) {
                array.add(value);
            }
            return array;
        }

        private static JsonArray toJsonArray(long[] values) {
            JsonArray array = new JsonArray();
            for (long value : values) {
                array.add(value);
            }
            return array;
        }

        private static List<NbtTag> fromJsonObject(JsonObject object, List<NbtTag> template) {
            List<NbtTag> tags = new ArrayList<>();
            for (String key : object.keySet()) {
                tags.add(fromJson(key, object.get(key), find(template, key)));
            }
            return tags;
        }

        private static NbtTag fromJson(String name, JsonElement element) {
            return fromJson(name, element, null);
        }

        private static NbtTag fromJson(String name, JsonElement element, NbtTag template) {
            if (element == null || element.isJsonNull()) {
                return new NbtTag(template != null ? template.type() : STRING, name, defaultValue(template));
            }
            if (element.isJsonObject()) {
                List<NbtTag> templateCompound = template != null && template.type() == COMPOUND ? compoundValue(template.value()) : List.of();
                return new NbtTag(COMPOUND, name, fromJsonObject(element.getAsJsonObject(), templateCompound));
            }
            if (element.isJsonArray()) {
                return fromJsonArrayTag(name, element.getAsJsonArray(), template);
            }
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (template != null && isNumericType(template.type()) && primitive.isNumber()) {
                return new NbtTag(template.type(), name, numberValue(primitive, template.type()));
            }
            if (template != null && template.type() == BYTE && primitive.isBoolean()) {
                return new NbtTag(BYTE, name, (byte) (primitive.getAsBoolean() ? 1 : 0));
            }
            if (template != null && template.type() == STRING) {
                return new NbtTag(STRING, name, primitive.isString() ? primitive.getAsString() : primitive.toString());
            }
            if (primitive.isString()) {
                return new NbtTag(STRING, name, primitive.getAsString());
            }
            if (primitive.isBoolean()) {
                return new NbtTag(BYTE, name, (byte) (primitive.getAsBoolean() ? 1 : 0));
            }
            if ("count".equals(name) || isIntegral(primitive)) {
                return new NbtTag(INT, name, primitive.getAsInt());
            }
            return new NbtTag(DOUBLE, name, primitive.getAsDouble());
        }

        private static NbtTag fromJsonArrayTag(String name, JsonArray array, NbtTag template) {
            if (template != null) {
                if (template.type() == BYTE_ARRAY) {
                    return new NbtTag(BYTE_ARRAY, name, byteArrayFromJson(array));
                }
                if (template.type() == INT_ARRAY) {
                    return new NbtTag(INT_ARRAY, name, intArrayFromJson(array));
                }
                if (template.type() == LONG_ARRAY) {
                    return new NbtTag(LONG_ARRAY, name, longArrayFromJson(array));
                }
                if (template.type() == LIST && template.value() instanceof NbtList templateList) {
                    return new NbtTag(LIST, name, fromJsonArray(array, templateList));
                }
            }
            return new NbtTag(LIST, name, fromJsonArray(array));
        }

        private static NbtList fromJsonArray(JsonArray array) {
            return fromJsonArray(array, null);
        }

        private static NbtList fromJsonArray(JsonArray array, NbtList template) {
            if (array.isEmpty()) {
                return new NbtList(template != null ? template.elementType() : END, new ArrayList<>());
            }
            byte elementType = template != null && template.elementType() != END ? template.elementType() : arrayElementType(array);
            List<Object> values = new ArrayList<>();
            List<Object> templateValues = template != null ? template.values() : List.of();
            for (int index = 0; index < array.size(); index++) {
                NbtTag templateTag = index < templateValues.size() ? new NbtTag(elementType, "", templateValues.get(index)) : null;
                NbtTag tag = fromJson("", array.get(index), templateTag);
                values.add(tag.value());
            }
            return new NbtList(elementType, values);
        }

        private static byte arrayElementType(JsonArray array) {
            for (JsonElement element : array) {
                if (element != null && !element.isJsonNull()) {
                    return fromJson("", element).type();
                }
            }
            return END;
        }

        private static byte[] byteArrayFromJson(JsonArray array) {
            byte[] values = new byte[array.size()];
            for (int index = 0; index < array.size(); index++) {
                values[index] = array.get(index).getAsByte();
            }
            return values;
        }

        private static int[] intArrayFromJson(JsonArray array) {
            int[] values = new int[array.size()];
            for (int index = 0; index < array.size(); index++) {
                values[index] = array.get(index).getAsInt();
            }
            return values;
        }

        private static long[] longArrayFromJson(JsonArray array) {
            long[] values = new long[array.size()];
            for (int index = 0; index < array.size(); index++) {
                values[index] = array.get(index).getAsLong();
            }
            return values;
        }

        private static boolean isNumericType(byte type) {
            return type == BYTE || type == SHORT || type == INT || type == LONG || type == FLOAT || type == DOUBLE;
        }

        private static Object numberValue(JsonPrimitive primitive, byte type) {
            return switch (type) {
                case BYTE -> primitive.getAsByte();
                case SHORT -> primitive.getAsShort();
                case INT -> primitive.getAsInt();
                case LONG -> primitive.getAsLong();
                case FLOAT -> primitive.getAsFloat();
                case DOUBLE -> primitive.getAsDouble();
                default -> primitive.getAsInt();
            };
        }

        private static Object defaultValue(NbtTag template) {
            if (template == null) {
                return "";
            }
            return switch (template.type()) {
                case BYTE -> (byte) 0;
                case SHORT -> (short) 0;
                case INT -> 0;
                case LONG -> 0L;
                case FLOAT -> 0.0f;
                case DOUBLE -> 0.0d;
                case BYTE_ARRAY -> new byte[0];
                case LIST -> template.value() instanceof NbtList list ? new NbtList(list.elementType(), new ArrayList<>()) : new NbtList(END, new ArrayList<>());
                case COMPOUND -> new ArrayList<NbtTag>();
                case INT_ARRAY -> new int[0];
                case LONG_ARRAY -> new long[0];
                default -> "";
            };
        }

        @SuppressWarnings("unchecked")
        private static List<NbtTag> compoundValue(Object value) {
            if (value instanceof List<?> list) {
                return (List<NbtTag>) list;
            }
            return new ArrayList<>();
        }

        private static boolean isIntegral(JsonPrimitive primitive) {
            if (!primitive.isNumber()) {
                return false;
            }
            try {
                double value = primitive.getAsDouble();
                return Math.rint(value) == value;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
    }
}
