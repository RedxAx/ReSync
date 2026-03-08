package restudio.resync.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import restudio.resync.ReSync;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WorldStateStorage {
    private static final Type WORLD_LIST_TYPE = new TypeToken<List<WorldRegistryEntry>>() {
    }.getType();
    private static final Type PORTAL_LIST_TYPE = new TypeToken<List<WorldPortal>>() {
    }.getType();
    private static final Type PLAYER_STATES_TYPE = new TypeToken<Map<String, Map<String, WorldPlayerState>>>() {
    }.getType();
    private final ReSync plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path rootDirectory;
    private final Path worldsFile;
    private final Path portalsFile;
    private final Path playerStatesFile;

    public WorldStateStorage(ReSync plugin) {
        this.plugin = plugin;
        this.rootDirectory = plugin.getDataFolder().toPath().resolve("world-management");
        this.worldsFile = rootDirectory.resolve("worlds.json");
        this.portalsFile = rootDirectory.resolve("portals.json");
        this.playerStatesFile = rootDirectory.resolve("player-states.json");
        ensureDirectory();
    }

    public synchronized List<WorldRegistryEntry> loadWorlds() {
        if (!Files.exists(worldsFile)) {
            return new ArrayList<>();
        }
        try {
            String raw = Files.readString(worldsFile, StandardCharsets.UTF_8);
            List<WorldRegistryEntry> worlds = gson.fromJson(raw, WORLD_LIST_TYPE);
            return worlds == null ? new ArrayList<>() : worlds;
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to load world registry: " + exception.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void saveWorlds(Collection<WorldRegistryEntry> entries) {
        List<WorldRegistryEntry> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        try {
            Files.writeString(worldsFile, gson.toJson(payload), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save world registry: " + exception.getMessage());
        }
    }

    public synchronized List<WorldPortal> loadPortals() {
        if (!Files.exists(portalsFile)) {
            return new ArrayList<>();
        }
        try {
            String raw = Files.readString(portalsFile, StandardCharsets.UTF_8);
            List<WorldPortal> portals = gson.fromJson(raw, PORTAL_LIST_TYPE);
            return portals == null ? new ArrayList<>() : portals;
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to load portals: " + exception.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void savePortals(Collection<WorldPortal> entries) {
        List<WorldPortal> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        try {
            Files.writeString(portalsFile, gson.toJson(payload), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save portals: " + exception.getMessage());
        }
    }

    public synchronized Map<UUID, Map<String, WorldPlayerState>> loadPlayerStates() {
        if (!Files.exists(playerStatesFile)) {
            return new LinkedHashMap<>();
        }
        try {
            String raw = Files.readString(playerStatesFile, StandardCharsets.UTF_8);
            Map<String, Map<String, WorldPlayerState>> loaded = gson.fromJson(raw, PLAYER_STATES_TYPE);
            Map<UUID, Map<String, WorldPlayerState>> output = new LinkedHashMap<>();
            if (loaded == null) {
                return output;
            }
            for (Map.Entry<String, Map<String, WorldPlayerState>> entry : loaded.entrySet()) {
                try {
                    UUID playerId = UUID.fromString(entry.getKey());
                    Map<String, WorldPlayerState> states = entry.getValue() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entry.getValue());
                    output.put(playerId, states);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return output;
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to load player world states: " + exception.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public synchronized void savePlayerStates(Map<UUID, Map<String, WorldPlayerState>> states) {
        Map<String, Map<String, WorldPlayerState>> payload = new LinkedHashMap<>();
        if (states != null) {
            for (Map.Entry<UUID, Map<String, WorldPlayerState>> entry : states.entrySet()) {
                payload.put(entry.getKey().toString(), entry.getValue() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entry.getValue()));
            }
        }
        try {
            Files.writeString(playerStatesFile, gson.toJson(payload), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save player world states: " + exception.getMessage());
        }
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to create world management directory: " + exception.getMessage());
        }
    }
}
