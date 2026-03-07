package restudio.resync.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.entity.Player;
import restudio.resync.ReSync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

public class PlayerTrackingManager implements PlayerTrackingService {
    private static final int MAX_RECENT_EVENTS = 250;
    private static final int MAX_SESSIONS = 100;
    private final ReSync plugin;
    private final Path dossierDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, PlayerDossier> dossiers = new ConcurrentHashMap<>();
    private final List<PlayerTrackingListener> listeners = new CopyOnWriteArrayList<>();

    public PlayerTrackingManager(ReSync plugin) {
        this.plugin = plugin;
        this.dossierDirectory = plugin.getDataFolder().toPath().resolve("player-dossiers");
        ensureDirectory();
        loadAll();
    }

    @Override
    public Collection<PlayerDossier> getDossiers() {
        List<PlayerDossier> copies = new ArrayList<>();
        for (PlayerDossier dossier : dossiers.values()) {
            synchronized (dossier) {
                copies.add(dossier.copy());
            }
        }
        copies.sort(Comparator.comparing(PlayerDossier::getPlayerName, String.CASE_INSENSITIVE_ORDER));
        return copies;
    }

    @Override
    public PlayerDossier getDossier(UUID playerId) {
        PlayerDossier dossier = dossiers.get(playerId);
        if (dossier == null) {
            return null;
        }
        synchronized (dossier) {
            return dossier.copy();
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
        if (playerId == null || facetId == null || facetId.isBlank()) {
            return;
        }
        update(playerId, playerName, dossier -> {
            PlayerFacetState facet = new PlayerFacetState();
            facet.setFacetId(facetId);
            facet.setModuleId(moduleId);
            facet.setUpdatedAt(System.currentTimeMillis());
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

    private void update(UUID playerId, String playerName, java.util.function.Consumer<PlayerDossier> mutator, String reason) {
        PlayerDossier dossier = dossiers.computeIfAbsent(playerId, this::createDossier);
        PlayerDossier snapshot;
        synchronized (dossier) {
            if (playerName != null && !playerName.isBlank()) {
                dossier.setPlayerName(playerName);
            }
            mutator.accept(dossier);
            snapshot = dossier.copy();
            save(snapshot);
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
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to create dossier directory: " + e.getMessage());
        }
    }

    private void loadAll() {
        try (var stream = Files.list(dossierDirectory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(this::load);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load player dossiers: " + e.getMessage());
        }
    }

    private void load(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            PlayerDossier dossier = gson.fromJson(json, PlayerDossier.class);
            if (dossier == null || dossier.getPlayerId() == null || dossier.getPlayerId().isBlank()) {
                return;
            }
            dossier.setOnline(false);
            dossier.setActiveSession(null);
            dossiers.put(UUID.fromString(dossier.getPlayerId()), dossier);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load dossier " + path.getFileName() + ": " + e.getMessage());
        }
    }

    private void save(PlayerDossier dossier) {
        try {
            Path path = dossierDirectory.resolve(dossier.getPlayerId() + ".json");
            Files.writeString(path, gson.toJson(dossier), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save dossier " + dossier.getPlayerId() + ": " + e.getMessage());
        }
    }

    private void notifyListeners(PlayerTrackingUpdate update) {
        for (PlayerTrackingListener listener : listeners) {
            listener.onUpdate(update);
        }
    }
}
