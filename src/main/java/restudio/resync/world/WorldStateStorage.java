package restudio.resync.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.lang.reflect.Type;
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
    private static final Type INVENTORY_GROUP_LIST_TYPE = new TypeToken<List<WorldInventoryGroup>>() {
    }.getType();
    private static final Type SIGN_PORTAL_LIST_TYPE = new TypeToken<List<WorldSignPortal>>() {
    }.getType();
    private static final Type PLAYER_STATES_TYPE = new TypeToken<Map<String, Map<String, WorldPlayerState>>>() {
    }.getType();
    private final ReSync plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path rootDirectory;
    private final Path worldsFile;
    private final Path portalsFile;
    private final Path inventoryGroupsFile;
    private final Path signPortalsFile;
    private final Path playerStatesFile;

    public WorldStateStorage(ReSync plugin) {
        this.plugin = plugin;
        this.rootDirectory = plugin.getDataFolder().toPath().resolve("world-management");
        this.worldsFile = rootDirectory.resolve("worlds.json");
        this.portalsFile = rootDirectory.resolve("portals.json");
        this.inventoryGroupsFile = rootDirectory.resolve("inventory-groups.json");
        this.signPortalsFile = rootDirectory.resolve("sign-portals.json");
        this.playerStatesFile = rootDirectory.resolve("player-states.json");
        ensureDirectory();
    }

    public synchronized List<WorldRegistryEntry> loadWorlds() {
        if (!Files.exists(worldsFile)) {
            return new ArrayList<>();
        }
        try {
            String raw = StorageSafety.readUtf8(worldsFile);
            List<WorldRegistryEntry> worlds = gson.fromJson(raw, WORLD_LIST_TYPE);
            return worlds == null ? new ArrayList<>() : worlds;
        } catch (Exception exception) {
            Log.warn("Failed to load world registry: " + exception.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void saveWorlds(Collection<WorldRegistryEntry> entries) {
        List<WorldRegistryEntry> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        try {
            StorageSafety.writeUtf8Atomic(worldsFile, gson.toJson(payload));
        } catch (IOException exception) {
            Log.warn("Failed to save world registry: " + exception.getMessage());
        }
    }

    public synchronized List<WorldPortal> loadPortals() {
        if (!Files.exists(portalsFile)) {
            return new ArrayList<>();
        }
        try {
            String raw = StorageSafety.readUtf8(portalsFile);
            List<WorldPortal> portals = gson.fromJson(raw, PORTAL_LIST_TYPE);
            return portals == null ? new ArrayList<>() : portals;
        } catch (Exception exception) {
            Log.warn("Failed to load portals: " + exception.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void savePortals(Collection<WorldPortal> entries) {
        List<WorldPortal> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        try {
            StorageSafety.writeUtf8Atomic(portalsFile, gson.toJson(payload));
        } catch (IOException exception) {
            Log.warn("Failed to save portals: " + exception.getMessage());
        }
    }

    public synchronized List<WorldInventoryGroup> loadInventoryGroups() {
        if (!Files.exists(inventoryGroupsFile)) {
            return new ArrayList<>();
        }
        try {
            String raw = StorageSafety.readUtf8(inventoryGroupsFile);
            List<WorldInventoryGroup> groups = gson.fromJson(raw, INVENTORY_GROUP_LIST_TYPE);
            return groups == null ? new ArrayList<>() : groups;
        } catch (Exception exception) {
            Log.warn("Failed to load inventory groups: " + exception.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void saveInventoryGroups(Collection<WorldInventoryGroup> entries) {
        List<WorldInventoryGroup> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        try {
            StorageSafety.writeUtf8Atomic(inventoryGroupsFile, gson.toJson(payload));
        } catch (IOException exception) {
            Log.warn("Failed to save inventory groups: " + exception.getMessage());
        }
    }

    public synchronized List<WorldSignPortal> loadSignPortals() {
        if (!Files.exists(signPortalsFile)) {
            return new ArrayList<>();
        }
        try {
            String raw = StorageSafety.readUtf8(signPortalsFile);
            List<WorldSignPortal> portals = gson.fromJson(raw, SIGN_PORTAL_LIST_TYPE);
            return portals == null ? new ArrayList<>() : portals;
        } catch (Exception exception) {
            Log.warn("Failed to load sign portals: " + exception.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void saveSignPortals(Collection<WorldSignPortal> entries) {
        List<WorldSignPortal> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        try {
            StorageSafety.writeUtf8Atomic(signPortalsFile, gson.toJson(payload));
        } catch (IOException exception) {
            Log.warn("Failed to save sign portals: " + exception.getMessage());
        }
    }

    public synchronized Map<UUID, Map<String, WorldPlayerState>> loadPlayerStates() {
        if (!Files.exists(playerStatesFile)) {
            return new LinkedHashMap<>();
        }
        try {
            String raw = StorageSafety.readUtf8(playerStatesFile);
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
            Log.warn("Failed to load player world states: " + exception.getMessage());
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
            StorageSafety.writeUtf8Atomic(playerStatesFile, gson.toJson(payload));
        } catch (IOException exception) {
            Log.warn("Failed to save player world states: " + exception.getMessage());
        }
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException exception) {
            Log.warn("Failed to create world management directory: " + exception.getMessage());
        }
    }
}
