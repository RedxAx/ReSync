package restudio.resync.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.entity.Player;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.server.ReSyncServer;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

public class PlayerTrackingManager implements PlayerTrackingService {
    private static final int MAX_RECENT_EVENTS = 250;
    private static final int MAX_SESSIONS = 100;
    private volatile Path dossierDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, PlayerDossier> dossiers = new ConcurrentHashMap<>();
    private final List<PlayerTrackingListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantReadWriteLock persistenceFence = new ReentrantReadWriteLock(true);
    private volatile PersistenceState persistenceState = PersistenceState.OPEN;

    private enum PersistenceState {
        OPEN,
        QUIESCED
    }

    public PlayerTrackingManager(ReSync plugin) {
        this(resolveDataRoot(plugin));
    }

    public PlayerTrackingManager(Path dataRoot) {
        Path scope = MigrationPaths.requirePath(dataRoot, "dataRoot");
        this.dossierDirectory = scope.resolve("player-dossiers").toAbsolutePath().normalize();
        ensureDirectory();
        recoverPersistence();
        loadAll();
    }

    public Path getDossierDirectory() {
        return dossierDirectory;
    }

    private static Path resolveDataRoot(ReSync plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin is required");
        }
        ReSyncServer server = plugin.getReSyncServer();
        return server == null ? plugin.getDataFolder().toPath() : server.getDataRoot();
    }

    public synchronized void flushPersistence() throws IOException {
        persistenceFence.writeLock().lock();
        try {
            requireDossierDirectory();
            StorageSafety.forceDirectory(dossierDirectory);
        } finally {
            persistenceFence.writeLock().unlock();
        }
    }

    public void quiescePersistence() {
        persistenceFence.writeLock().lock();
        try {
            persistenceState = PersistenceState.QUIESCED;
        } finally {
            persistenceFence.writeLock().unlock();
        }
    }

    public void resumePersistence() throws IOException {
        persistenceFence.writeLock().lock();
        try {
            healthCheckPersistenceLocked();
            persistenceState = PersistenceState.OPEN;
        } finally {
            persistenceFence.writeLock().unlock();
        }
    }

    public void rebindPersistence(Path candidateDossierRoot) throws IOException {
        persistenceFence.writeLock().lock();
        try {
            if (persistenceState != PersistenceState.QUIESCED) {
                throw new IOException("Player dossier persistence must be quiesced before rebind");
            }
            Path candidate = requireDossierDirectory(candidateDossierRoot, "player dossier rebind root");
            Map<UUID, PlayerDossier> loaded = readDossiers(candidate);
            dossierDirectory = candidate;
            dossiers.clear();
            dossiers.putAll(loaded);
        } finally {
            persistenceFence.writeLock().unlock();
        }
    }

    public void healthCheckPersistence() throws IOException {
        persistenceFence.writeLock().lock();
        try {
            healthCheckPersistenceLocked();
        } finally {
            persistenceFence.writeLock().unlock();
        }
    }

    @Override
    public Collection<PlayerDossier> getDossiers() {
        persistenceFence.readLock().lock();
        try {
            List<PlayerDossier> copies = new ArrayList<>();
            for (PlayerDossier dossier : dossiers.values()) {
                synchronized (dossier) {
                    copies.add(dossier.copy());
                }
            }
            copies.sort(Comparator.comparing(PlayerDossier::getPlayerName, String.CASE_INSENSITIVE_ORDER));
            return copies;
        } finally {
            persistenceFence.readLock().unlock();
        }
    }

    @Override
    public PlayerDossier getDossier(UUID playerId) {
        persistenceFence.readLock().lock();
        try {
            PlayerDossier dossier = dossiers.get(playerId);
            if (dossier == null) {
                return null;
            }
            synchronized (dossier) {
                return dossier.copy();
            }
        } finally {
            persistenceFence.readLock().unlock();
        }
    }

    @Override
    public void markOnline(Player player, String source) {
        if (player == null) {
            return;
        }
        update(player.getUniqueId(), player.getName(), dossier -> {
            long now = System.currentTimeMillis();
            dossier.setOnline(true);
            dossier.setLastSeenAt(now);
            if (dossier.getFirstSeenAt() <= 0) {
                dossier.setFirstSeenAt(now);
            }
            PlayerSessionRecord current = dossier.getActiveSession();
            if (current == null || current.getEndedAt() > 0) {
                PlayerSessionRecord session = new PlayerSessionRecord();
                session.setSessionId(UUID.randomUUID().toString());
                session.setSource(source);
                session.setStartedAt(now);
                dossier.setActiveSession(session);
            }
        }, "playerOnline");
    }

    @Override
    public void markOffline(UUID playerId, String playerName, String source) {
        if (playerId == null) {
            return;
        }
        update(playerId, playerName, dossier -> {
            long now = System.currentTimeMillis();
            dossier.setOnline(false);
            dossier.setLastSeenAt(now);
            PlayerSessionRecord session = dossier.getActiveSession();
            if (session != null && session.getEndedAt() <= 0) {
                session.setEndedAt(now);
                session.setDurationMs(Math.max(0L, now - session.getStartedAt()));
                if (source != null && !source.isBlank()) {
                    session.setSource(source);
                }
                dossier.setTotalPlayTimeMs(dossier.getTotalPlayTimeMs() + session.getDurationMs());
                dossier.getSessions().add(0, session.copy());
                trimSessions(dossier);
            }
            dossier.setActiveSession(null);
        }, "playerOffline");
    }

    @Override
    public void recordEvent(UUID playerId, String playerName, String moduleId, String category, String type, Map<String, Object> data) {
        if (playerId == null) {
            return;
        }
        update(playerId, playerName, dossier -> {
            long now = System.currentTimeMillis();
            dossier.setLastSeenAt(now);
            if (dossier.getFirstSeenAt() <= 0) {
                dossier.setFirstSeenAt(now);
            }
            PlayerEventRecord event = new PlayerEventRecord();
            event.setEventId(UUID.randomUUID().toString());
            event.setTimestamp(now);
            event.setModuleId(moduleId);
            event.setCategory(category);
            event.setType(type);
            event.setData(data == null ? Map.of() : data);
            dossier.getRecentEvents().add(0, event);
            while (dossier.getRecentEvents().size() > MAX_RECENT_EVENTS) {
                dossier.getRecentEvents().remove(dossier.getRecentEvents().size() - 1);
            }
        }, category + ':' + type);
    }

    @Override
    public void upsertFacet(UUID playerId, String playerName, String facetId, String moduleId, Map<String, Object> data) {
        upsertFacet(playerId, playerName, facetId, moduleId, null, data);
    }

    @Override
    public void upsertFacet(UUID playerId, String playerName, String facetId, String moduleId, PlayerFacetMetadata metadata, Map<String, Object> data) {
        if (playerId == null || facetId == null || facetId.isBlank()) {
            return;
        }
        update(playerId, playerName, dossier -> {
            PlayerFacetState facet = new PlayerFacetState();
            facet.setFacetId(facetId);
            facet.setModuleId(moduleId);
            facet.setUpdatedAt(System.currentTimeMillis());
            facet.setMetadata(metadata);
            facet.setData(data == null ? Map.of() : data);
            dossier.getFacets().put(facetId, facet);
        }, "facet:" + facetId);
    }

    @Override
    public void removeFacet(UUID playerId, String facetId) {
        if (playerId == null || facetId == null || facetId.isBlank()) {
            return;
        }
        update(playerId, null, dossier -> dossier.getFacets().remove(facetId), "facetRemoved:" + facetId);
    }

    @Override
    public void addListener(PlayerTrackingListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(PlayerTrackingListener listener) {
        listeners.remove(listener);
    }

    private void update(UUID playerId, String playerName, Consumer<PlayerDossier> mutator, String reason) {
        PlayerDossier snapshot;
        persistenceFence.readLock().lock();
        try {
            requirePersistenceOpen();
            snapshot = dossiers.compute(playerId, (id, current) -> {
                PlayerDossier candidate = current == null ? createDossier(id) : current.copy();
                if (playerName != null && !playerName.isBlank()) {
                    candidate.setPlayerName(playerName);
                }
                mutator.accept(candidate);
                save(candidate);
                return candidate;
            }).copy();
        } finally {
            persistenceFence.readLock().unlock();
        }
        notifyListeners(PlayerTrackingUpdate.delta(reason, snapshot));
    }

    private PlayerDossier createDossier(UUID playerId) {
        PlayerDossier dossier = new PlayerDossier();
        dossier.setPlayerId(playerId.toString());
        dossier.setPlayerName(playerId.toString());
        dossier.setFirstSeenAt(System.currentTimeMillis());
        dossier.setLastSeenAt(System.currentTimeMillis());
        dossier.setSessions(new ArrayList<>());
        dossier.setRecentEvents(new ArrayList<>());
        dossier.setFacets(new LinkedHashMap<>());
        return dossier;
    }

    private void trimSessions(PlayerDossier dossier) {
        while (dossier.getSessions().size() > MAX_SESSIONS) {
            dossier.getSessions().remove(dossier.getSessions().size() - 1);
        }
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(dossierDirectory);
            requireDossierDirectory();
        } catch (IOException e) {
            Log.warn("Failed to create dossier directory: " + e.getMessage());
        }
    }

    private void loadAll() {
        try (var stream = Files.list(dossierDirectory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .toList().forEach(this::load);
        } catch (IOException e) {
            Log.warn("Failed to load player dossiers: " + e.getMessage());
        }
    }

    private void load(Path path) {
        try {
            String fileName = path.getFileName().toString();
            if (!fileName.endsWith(".json")) {
                return;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Player dossier is not a regular file: " + fileName);
            }
            String fileId = StorageSafety.validateId(fileName.substring(0, fileName.length() - 5));
            String json = StorageSafety.readUtf8(path);
            PlayerDossier dossier = gson.fromJson(json, PlayerDossier.class);
            if (dossier == null || dossier.getPlayerId() == null || dossier.getPlayerId().isBlank()) {
                return;
            }
            StorageSafety.validateId(dossier.getPlayerId());
            if (!fileId.equals(dossier.getPlayerId())) {
                throw new IOException("Player dossier file id does not match its player id: " + fileName);
            }
            boolean changed = normalizeLoadedDossier(dossier);
            if (dossier.isOnline()) {
                dossier.setOnline(false);
                changed = true;
            }
            changed |= closeStaleSession(dossier);
            if (changed) {
                save(dossier);
            }
            dossiers.put(UUID.fromString(dossier.getPlayerId()), dossier);
        } catch (Exception e) {
            Log.warn("Failed to load dossier " + path.getFileName() + ": " + e.getMessage());
        }
    }

    private boolean normalizeLoadedDossier(PlayerDossier dossier) {
        boolean changed = false;
        if (dossier.getPlayerName() == null || dossier.getPlayerName().isBlank()) {
            dossier.setPlayerName(dossier.getPlayerId());
            changed = true;
        }
        if (dossier.getSessions() == null) {
            dossier.setSessions(new ArrayList<>());
            changed = true;
        }
        if (dossier.getRecentEvents() == null) {
            dossier.setRecentEvents(new ArrayList<>());
            changed = true;
        }
        if (dossier.getFacets() == null) {
            dossier.setFacets(new LinkedHashMap<>());
            changed = true;
        }
        return changed;
    }

    private void recoverPersistence() {
        try {
            Path root = requireDossierDirectory();
            int recovered = StorageSafety.recoverAtomicWrites(root);
            if (recovered > 0) {
                Log.warn("Preserved " + recovered + " interrupted player history writes in "
                    + root.resolve(".quarantine/atomic-writes"));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to recover player history persistence", exception);
        }
    }

    private boolean closeStaleSession(PlayerDossier dossier) {
        PlayerSessionRecord session = dossier.getActiveSession();
        if (session == null || session.getEndedAt() > 0) {
            dossier.setActiveSession(null);
            return session != null;
        }
        long now = System.currentTimeMillis();
        session.setEndedAt(now);
        session.setDurationMs(Math.max(0L, now - session.getStartedAt()));
        if (session.getSource() == null || session.getSource().isBlank()) {
            session.setSource("startupRecovery");
        }
        dossier.setLastSeenAt(now);
        dossier.setTotalPlayTimeMs(dossier.getTotalPlayTimeMs() + session.getDurationMs());
        dossier.getSessions().add(0, session.copy());
        trimSessions(dossier);
        dossier.setActiveSession(null);
        return true;
    }

    private void save(PlayerDossier dossier) {
        try {
            Path path = StorageSafety.jsonFile(dossierDirectory, dossier.getPlayerId());
            StorageSafety.writeUtf8Atomic(path, gson.toJson(dossier));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Failed to save player history: " + dossier.getPlayerId(), exception);
        }
    }

    private void requirePersistenceOpen() {
        if (persistenceState != PersistenceState.OPEN) {
            throw new IllegalStateException("Player dossier persistence is quiesced; mutation rejected");
        }
    }

    private void healthCheckPersistenceLocked() throws IOException {
        Path root = requireDossierDirectory();
        readDossiers(root);
        StorageSafety.forceDirectory(root);
    }

    private Path requireDossierDirectory() throws IOException {
        return requireDossierDirectory(dossierDirectory, "player dossier root");
    }

    private Path requireDossierDirectory(Path root, String name) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(root, name);
        MigrationPaths.requireNoSymlinkTree(normalized);
        return normalized;
    }

    private Map<UUID, PlayerDossier> readDossiers(Path root) throws IOException {
        StorageSafety.validateAtomicWriteRecovery(root);
        Map<UUID, PlayerDossier> loaded = new LinkedHashMap<>();
        try (var stream = Files.list(root)) {
            for (Path path : stream.sorted().toList()) {
                if (path.getFileName().toString().equals(".quarantine")) {
                    continue;
                }
                if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    throw new IOException("Player dossier root contains a non-regular file: " + path.getFileName());
                }
                String fileName = path.getFileName().toString();
                if (!fileName.endsWith(".json")) {
                    throw new IOException("Player dossier root contains an unexpected file: " + fileName);
                }
                String fileId;
                try {
                    fileId = StorageSafety.validateId(fileName.substring(0, fileName.length() - 5));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Player dossier has an unsafe file name: " + fileName, exception);
                }
                PlayerDossier dossier;
                try {
                    dossier = gson.fromJson(StorageSafety.readUtf8(path), PlayerDossier.class);
                } catch (RuntimeException exception) {
                    throw new IOException("Failed to parse player dossier: " + fileName, exception);
                }
                if (dossier == null || dossier.getPlayerId() == null || dossier.getPlayerId().isBlank()) {
                    throw new IOException("Player dossier is missing its player id: " + fileName);
                }
                try {
                    StorageSafety.validateId(dossier.getPlayerId());
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Player dossier has an unsafe player id: " + fileName, exception);
                }
                UUID playerId;
                try {
                    playerId = UUID.fromString(dossier.getPlayerId());
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Player dossier has an invalid player id: " + fileName, exception);
                }
                if (!fileId.equals(dossier.getPlayerId())) {
                    throw new IOException("Player dossier file id does not match its player id: " + fileName);
                }
                normalizeLoadedDossier(dossier);
                if (loaded.put(playerId, dossier) != null) {
                    throw new IOException("Duplicate player dossier id: " + dossier.getPlayerId());
                }
            }
        }
        return loaded;
    }

    private void notifyListeners(PlayerTrackingUpdate update) {
        for (PlayerTrackingListener listener : listeners) {
            listener.onUpdate(update);
        }
    }
}
