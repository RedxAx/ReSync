package restudio.resync.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WorldStateStorage {
    public static final String QUARANTINE_DIRECTORY = ".quarantine/world-state";
    private static final String QUARANTINE_CONTAINER = ".quarantine";
    private static final String ATOMIC_TEMP_PREFIX = ".resync-";
    private static final String ATOMIC_TEMP_SUFFIX = ".tmp";
    private static final int UUID_LENGTH = 36;
    static final long MAXIMUM_STATE_FILE_BYTES = 16L * 1024L * 1024L;
    private static final List<String> AUTHORITATIVE_FILE_NAMES = List.of(
        "worlds.json", "portals.json", "inventory-groups.json", "sign-portals.json", "player-states.json");
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
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private volatile Path rootDirectory;
    private volatile Path worldsFile;
    private volatile Path portalsFile;
    private volatile Path inventoryGroupsFile;
    private volatile Path signPortalsFile;
    private volatile Path playerStatesFile;
    private volatile boolean quiesced;

    public WorldStateStorage(Path dataRoot) {
        Path scope = MigrationPaths.requirePath(dataRoot, "dataRoot");
        updateRoot(scope.resolve("world-management"));
        ensureDirectory();
    }

    record State(List<WorldRegistryEntry> worlds, List<WorldPortal> portals,
                 List<WorldInventoryGroup> inventoryGroups, List<WorldSignPortal> signPortals,
                 Map<UUID, Map<String, WorldPlayerState>> playerStates) {
    }

    public Path getRootPath() {
        return rootDirectory;
    }

    synchronized State loadState() {
        requireHealthy("load world management state");
        try {
            return readState(rootDirectory);
        } catch (IOException exception) {
            throw invalidState("Failed to load world management state", exception);
        }
    }

    synchronized State readCandidateState(Path candidateRoot) throws IOException {
        Path root = requireRoot(candidateRoot);
        validateTree(root);
        return readState(root);
    }

    public synchronized List<WorldRegistryEntry> loadWorlds() {
        requireHealthy("load world registry");
        try {
            return readList(worldsFile, WORLD_LIST_TYPE, "world registry");
        } catch (IOException exception) {
            throw invalidState("Failed to load world registry", exception);
        }
    }

    public synchronized void saveWorlds(Collection<WorldRegistryEntry> entries) {
        requireWritable();
        requireHealthy("save world registry");
        List<WorldRegistryEntry> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        write(worldsFile, gson.toJson(payload), "world registry");
    }

    public synchronized List<WorldPortal> loadPortals() {
        requireHealthy("load portals");
        try {
            return readList(portalsFile, PORTAL_LIST_TYPE, "world portals");
        } catch (IOException exception) {
            throw invalidState("Failed to load portals", exception);
        }
    }

    public synchronized void savePortals(Collection<WorldPortal> entries) {
        requireWritable();
        requireHealthy("save portals");
        List<WorldPortal> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        write(portalsFile, gson.toJson(payload), "world portals");
    }

    public synchronized List<WorldInventoryGroup> loadInventoryGroups() {
        requireHealthy("load world inventory groups");
        try {
            return readList(inventoryGroupsFile, INVENTORY_GROUP_LIST_TYPE, "world inventory groups");
        } catch (IOException exception) {
            throw invalidState("Failed to load inventory groups", exception);
        }
    }

    public synchronized void saveInventoryGroups(Collection<WorldInventoryGroup> entries) {
        requireWritable();
        requireHealthy("save world inventory groups");
        List<WorldInventoryGroup> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        write(inventoryGroupsFile, gson.toJson(payload), "world inventory groups");
    }

    public synchronized List<WorldSignPortal> loadSignPortals() {
        requireHealthy("load world sign portals");
        try {
            return readList(signPortalsFile, SIGN_PORTAL_LIST_TYPE, "world sign portals");
        } catch (IOException exception) {
            throw invalidState("Failed to load sign portals", exception);
        }
    }

    public synchronized void saveSignPortals(Collection<WorldSignPortal> entries) {
        requireWritable();
        requireHealthy("save world sign portals");
        List<WorldSignPortal> payload = new ArrayList<>();
        if (entries != null) {
            payload.addAll(entries);
        }
        write(signPortalsFile, gson.toJson(payload), "world sign portals");
    }

    public synchronized Map<UUID, Map<String, WorldPlayerState>> loadPlayerStates() {
        requireHealthy("load player world states");
        try {
            return readPlayerStates(playerStatesFile);
        } catch (IOException exception) {
            throw invalidState("Failed to load player world states", exception);
        }
    }

    public synchronized void savePlayerStates(Map<UUID, Map<String, WorldPlayerState>> states) {
        requireWritable();
        requireHealthy("save player world states");
        Map<String, Map<String, WorldPlayerState>> payload = new LinkedHashMap<>();
        if (states != null) {
            for (Map.Entry<UUID, Map<String, WorldPlayerState>> entry : states.entrySet()) {
                payload.put(entry.getKey().toString(), entry.getValue() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entry.getValue()));
            }
        }
        write(playerStatesFile, gson.toJson(payload), "player world states");
    }

    private void ensureDirectory() {
        try {
            Path scope = rootDirectory.getParent();
            if (scope == null) {
                throw new IOException("World management root has no scope parent");
            }
            Path normalizedScope = MigrationPaths.requirePath(scope, "dataRoot");
            if (Files.notExists(normalizedScope, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(normalizedScope);
            }
            StorageSafety.createDirectoriesNoSymlinks(normalizedScope, rootDirectory);
            validateTree(rootDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize world management persistence", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to initialize world management persistence", exception);
        }
    }

    public synchronized void flushPersistence() throws IOException {
        validateTree(rootDirectory);
        StorageSafety.forceDirectory(rootDirectory);
    }

    public synchronized void quiescePersistence() throws IOException {
        flushPersistence();
        quiesced = true;
    }

    public synchronized void resumePersistence() throws IOException {
        healthCheckPersistence();
        quiesced = false;
    }

    public synchronized void rebindPersistence(Path candidateRoot) throws IOException {
        if (!quiesced) {
            throw new IOException("World management persistence must be quiesced before rebind");
        }
        Path nextRoot = requireRoot(candidateRoot);
        validateTree(nextRoot);
        updateRoot(nextRoot);
    }

    public synchronized void healthCheckPersistence() throws IOException {
        validateTree(rootDirectory);
    }

    private void requireWritable() {
        if (quiesced) {
            throw new IllegalStateException("World management persistence is quiesced");
        }
    }

    private static Path requireRoot(Path root) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(root, "world management root");
        MigrationPaths.requireNoSymlinkTraversal(normalized, normalized);
        return normalized;
    }

    private <T> List<T> readList(Path file, Type type, String label) throws IOException {
        validateFile(file, label);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new ArrayList<>();
        }
        requireReadableSize(file, label);
        try {
            String raw = StorageSafety.readUtf8(file);
            List<T> value = gson.fromJson(raw, type);
            if (value == null) {
                throw new IOException("Candidate " + label + " must be an array");
            }
            return value;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Failed to read candidate " + label, exception);
        }
    }

    private Map<UUID, Map<String, WorldPlayerState>> readPlayerStates(Path file) throws IOException {
        validateFile(file, "player world states");
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new LinkedHashMap<>();
        }
        requireReadableSize(file, "player world states");
        try {
            String raw = StorageSafety.readUtf8(file);
            Map<String, Map<String, WorldPlayerState>> loaded = gson.fromJson(raw, PLAYER_STATES_TYPE);
            Map<UUID, Map<String, WorldPlayerState>> output = new LinkedHashMap<>();
            if (loaded == null) {
                throw new IOException("Candidate player states must be an object");
            }
            for (Map.Entry<String, Map<String, WorldPlayerState>> entry : loaded.entrySet()) {
                if (entry.getKey() == null) {
                    throw new IOException("Candidate player state has a missing player id");
                }
                UUID playerId;
                try {
                    String key = entry.getKey();
                    playerId = UUID.fromString(key);
                    if (!playerId.toString().equals(key)) {
                        throw new IOException("Candidate player state has a non-canonical player id");
                    }
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Candidate player state has an invalid player id", exception);
                }
                if (output.containsKey(playerId)) {
                    throw new IOException("Candidate player state has duplicate player ids");
                }
                Map<String, WorldPlayerState> states = entry.getValue() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(entry.getValue());
                output.put(playerId, states);
            }
            return output;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Failed to read candidate player states", exception);
        }
    }

    private State readState(Path root) throws IOException {
        return new State(
            readList(root.resolve("worlds.json"), WORLD_LIST_TYPE, "world registry"),
            readList(root.resolve("portals.json"), PORTAL_LIST_TYPE, "world portals"),
            readList(root.resolve("inventory-groups.json"), INVENTORY_GROUP_LIST_TYPE, "world inventory groups"),
            readList(root.resolve("sign-portals.json"), SIGN_PORTAL_LIST_TYPE, "world sign portals"),
            readPlayerStates(root.resolve("player-states.json")));
    }

    private void requireHealthy(String operation) {
        try {
            validateTree(rootDirectory);
        } catch (IOException exception) {
            throw invalidState("Cannot " + operation + " because world management persistence is invalid", exception);
        }
    }

    private IllegalStateException invalidState(String message, IOException exception) {
        Log.error(message + ": " + exception.getMessage(), exception);
        return new IllegalStateException(message, exception);
    }

    private void write(Path file, String content, String label) {
        try {
            StorageSafety.writeUtf8AtomicStrict(file, content);
        } catch (IOException exception) {
            throw invalidState("Failed to save " + label, exception);
        }
    }

    private void validateTree(Path root) throws IOException {
        Path normalizedRoot = requireRoot(root);
        MigrationPaths.requireNoSymlinkTree(normalizedRoot);
        recoverAtomicTemps(normalizedRoot);
        MigrationPaths.requireNoSymlinkTree(normalizedRoot);
        try (var entries = Files.list(normalizedRoot)) {
            for (Path child : entries.toList()) {
                String name = child.getFileName().toString();
                if (AUTHORITATIVE_FILE_NAMES.contains(name)) {
                    validateFile(child, "world management file");
                } else if (name.equals(QUARANTINE_CONTAINER)) {
                    validateQuarantine(normalizedRoot);
                } else {
                    throw new IOException("World management root contains an unknown entry: " + child);
                }
            }
        }
        validateQuarantine(normalizedRoot);
        readList(normalizedRoot.resolve("worlds.json"), WORLD_LIST_TYPE, "world registry");
        readList(normalizedRoot.resolve("portals.json"), PORTAL_LIST_TYPE, "world portals");
        readList(normalizedRoot.resolve("inventory-groups.json"), INVENTORY_GROUP_LIST_TYPE, "world inventory groups");
        readList(normalizedRoot.resolve("sign-portals.json"), SIGN_PORTAL_LIST_TYPE, "world sign portals");
        readPlayerStates(normalizedRoot.resolve("player-states.json"));
    }

    private void recoverAtomicTemps(Path root) throws IOException {
        List<Path> temporaryFiles = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path child : entries.toList()) {
                String name = child.getFileName().toString();
                if (AUTHORITATIVE_FILE_NAMES.contains(name) || name.equals(QUARANTINE_CONTAINER)) {
                    continue;
                }
                if (!isAtomicTempName(name)) {
                    throw new IOException("World management root contains an unknown entry: " + child);
                }
                requireAtomicTempFile(child);
                temporaryFiles.add(child);
            }
        }
        validateQuarantine(root);
        if (temporaryFiles.isEmpty()) {
            return;
        }
        Path quarantine = prepareQuarantine(root);
        for (Path temporary : temporaryFiles) {
            Path target = quarantine.resolve(temporary.getFileName()).toAbsolutePath().normalize();
            if (!target.startsWith(quarantine) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("World management atomic temp quarantine target collides: " + target);
            }
        }
        for (Path temporary : temporaryFiles) {
            Path target = quarantine.resolve(temporary.getFileName()).toAbsolutePath().normalize();
            requireAtomicTempFile(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                throw new IOException("Failed to quarantine world management atomic temp: " + temporary, exception);
            }
            requireAtomicTempFile(target);
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("World management atomic temp remained after quarantine: " + temporary);
            }
        }
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(quarantine.getParent());
        StorageSafety.forceDirectory(root);
        validateQuarantine(root);
    }

    private Path prepareQuarantine(Path root) throws IOException {
        Path container = root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        MigrationPaths.requireNoSymlinkTraversal(root, quarantine);
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(container);
        }
        if (Files.isSymbolicLink(container) || !Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World management quarantine container is invalid: " + container);
        }
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(quarantine);
        }
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World management quarantine root is invalid: " + quarantine);
        }
        return quarantine;
    }

    private void validateQuarantine(Path root) throws IOException {
        Path container = root.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(container) || !Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World management quarantine container is invalid: " + container);
        }
        Path quarantine = root.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        try (var entries = Files.list(container)) {
            for (Path child : entries.toList()) {
                if (!child.equals(quarantine)) {
                    throw new IOException("World management quarantine contains an unknown entry: " + child);
                }
            }
        }
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World management quarantine root is missing: " + quarantine);
        }
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World management quarantine root is invalid: " + quarantine);
        }
        try (var entries = Files.list(quarantine)) {
            for (Path child : entries.toList()) {
                if (!isAtomicTempName(child.getFileName().toString())) {
                    throw new IOException("World management quarantine contains an unknown entry: " + child);
                }
                requireAtomicTempFile(child);
            }
        }
    }

    private void validateFile(Path file, String label) throws IOException {
        Path normalized = MigrationPaths.requirePath(file, label);
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(label + " must be a regular non-symbolic-link file: " + normalized);
        }
    }

    private static void requireReadableSize(Path file, String label) throws IOException {
        long size = Files.size(file);
        if (size > MAXIMUM_STATE_FILE_BYTES) {
            throw new IOException(label + " exceeds the maximum size of " + MAXIMUM_STATE_FILE_BYTES + " bytes: " + file);
        }
    }

    private static void requireAtomicTempFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World management atomic temp must be a regular non-symbolic-link file: " + file);
        }
    }

    private static boolean isAtomicTempName(String name) {
        if (name == null || name.length() != ATOMIC_TEMP_PREFIX.length() + UUID_LENGTH + ATOMIC_TEMP_SUFFIX.length()
            || !name.startsWith(ATOMIC_TEMP_PREFIX) || !name.endsWith(ATOMIC_TEMP_SUFFIX)) {
            return false;
        }
        int uuidStart = ATOMIC_TEMP_PREFIX.length();
        for (int index = 0; index < UUID_LENGTH; index++) {
            char character = name.charAt(uuidStart + index);
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (character != '-') {
                    return false;
                }
            } else if (!isLowercaseHex(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowercaseHex(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f';
    }

    private void updateRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        rootDirectory = normalized;
        worldsFile = normalized.resolve("worlds.json");
        portalsFile = normalized.resolve("portals.json");
        inventoryGroupsFile = normalized.resolve("inventory-groups.json");
        signPortalsFile = normalized.resolve("sign-portals.json");
        playerStatesFile = normalized.resolve("player-states.json");
    }
}
