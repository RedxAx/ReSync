package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MigrationLedger {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DURABILITY_DIRECTORY = ".durability";
    private static final String LEDGER_FILE = "migrations.json";
    private static final String LEDGER_LOCK_FILE = "migration-ledger.lock";
    private static final String EPOCH_FILE = "migration.epoch";
    private static final String LOCK_FILE = "migration.lock";
    private final Path durabilityRoot;
    private final RecoverableJsonStore store;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, JsonObject> entryExtras = new LinkedHashMap<>();
    private final JsonObject unknownTopLevel = new JsonObject();

    public MigrationLedger(Path assetsRoot) throws IOException {
        Path root = ensureDirectory(assetsRoot, "assetsRoot");
        this.durabilityRoot = ensureDirectory(root.resolve(DURABILITY_DIRECTORY), "migration durability root");
        RecoverableJsonStore.recoverCanonicalArtifacts(durabilityRoot,
            durabilityRoot.resolve(".quarantine").resolve("journals"));
        this.store = new RecoverableJsonStore(durabilityRoot.resolve(LEDGER_FILE), GSON);
        Path ledgerFile = durabilityRoot.resolve(LEDGER_FILE);
        requireOptionalRegular(ledgerFile, "Migration ledger");
        requireOptionalRegular(durabilityRoot.resolve(LEDGER_LOCK_FILE), "Migration ledger lock");
        requireOptionalRegular(durabilityRoot.resolve(LOCK_FILE), "Migration lock");
        Path epochFile = durabilityRoot.resolve(EPOCH_FILE);
        requireOptionalRegular(epochFile, "Migration epoch");
        loadEntries();
    }

    public synchronized boolean isCommitted(String migrationId, String resourceId, String sourceHash) {
        try {
            loadEntries();
        } catch (IOException failure) {
            throw new IllegalStateException("Migration ledger cannot be reloaded", failure);
        }
        Entry entry = entries.get(key(migrationId, resourceId, sourceHash));
        return entry != null && "COMMITTED".equals(entry.state());
    }

    public synchronized void prepare(String migrationId, String resourceId, String sourceType, long sourceRevision,
                                      String sourceHash, int targetVersion, long fenceEpoch) throws IOException {
        requireText(migrationId, "migrationId");
        requireText(resourceId, "resourceId");
        requireText(sourceType, "sourceType");
        requireText(sourceHash, "sourceHash");
        if (sourceRevision < 0L) {
            throw new IllegalArgumentException("sourceRevision must not be negative");
        }
        if (targetVersion < 0) {
            throw new IllegalArgumentException("targetVersion must not be negative");
        }
        requireEpoch(fenceEpoch);
        String entryKey = key(migrationId, resourceId, sourceHash);
        mutate(() -> prepareLoaded(entryKey, migrationId, resourceId, sourceType, sourceRevision,
            sourceHash, targetVersion, fenceEpoch));
    }

    public synchronized void commit(String migrationId, String resourceId, String sourceHash) throws IOException {
        String entryKey = key(migrationId, resourceId, sourceHash);
        mutate(() -> updateLoaded(entryKey, "COMMITTED", ""));
    }

    public synchronized void fail(String migrationId, String resourceId, String sourceHash, String diagnostic) throws IOException {
        String entryKey = key(migrationId, resourceId, sourceHash);
        mutate(() -> updateLoaded(entryKey, "FAILED", diagnostic == null ? "" : diagnostic));
    }

    private void prepareLoaded(String entryKey, String migrationId, String resourceId, String sourceType,
                               long sourceRevision, String sourceHash, int targetVersion, long fenceEpoch) throws IOException {
        if (unknownTopLevel.has(entryKey)) {
            throw new IOException("Migration ledger key conflicts with unknown top-level metadata: " + entryKey);
        }
        Entry current = entries.get(entryKey);
        if (current != null) {
            boolean checkFence = "PREPARED".equals(current.state());
            validatePrepareIdentity(current, migrationId, resourceId, sourceType, sourceRevision, sourceHash,
                targetVersion, fenceEpoch, checkFence);
            if ("COMMITTED".equals(current.state()) || "PREPARED".equals(current.state())) {
                return;
            }
            if (!"FAILED".equals(current.state())) {
                throw new IOException("Migration ledger state cannot be prepared: " + current.state());
            }
        }
        Entry replacement = new Entry(migrationId, resourceId, sourceType, sourceRevision, sourceHash,
            targetVersion, fenceEpoch, "PREPARED", "", Instant.now().toString());
        JsonObject previousExtras = entryExtras.get(entryKey);
        entries.put(entryKey, replacement);
        if (current == null) {
            entryExtras.remove(entryKey);
        }
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            restore(entryKey, current, previousExtras);
            throw failure;
        }
    }

    private void updateLoaded(String entryKey, String state, String diagnostic) throws IOException {
        Entry current = entries.get(entryKey);
        if (current == null) {
            return;
        }
        if (!isAllowedTransition(current.state(), state)) {
            throw new IOException("Migration ledger transition is not allowed: " + current.state() + " to " + state);
        }
        if (current.state().equals(state)) {
            return;
        }
        Entry replacement = new Entry(current.migrationId(), current.resourceId(), current.sourceType(),
            current.sourceRevision(), current.sourceHash(), current.targetVersion(), current.fenceEpoch(), state,
            diagnostic, Instant.now().toString());
        entries.put(entryKey, replacement);
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            entries.put(entryKey, current);
            throw failure;
        }
    }

    private boolean isAllowedTransition(String current, String next) {
        return switch (current) {
            case "PREPARED" -> "PREPARED".equals(next) || "COMMITTED".equals(next) || "FAILED".equals(next);
            case "FAILED" -> "FAILED".equals(next) || "PREPARED".equals(next);
            case "COMMITTED" -> "COMMITTED".equals(next);
            default -> false;
        };
    }

    private void validatePrepareIdentity(Entry current, String migrationId, String resourceId, String sourceType,
                                         long sourceRevision, String sourceHash, int targetVersion, long fenceEpoch,
                                         boolean checkFence) {
        if (!Objects.equals(current.migrationId(), migrationId) || !Objects.equals(current.resourceId(), resourceId)
            || !Objects.equals(current.sourceType(), sourceType) || current.sourceRevision() != sourceRevision
            || !Objects.equals(current.sourceHash(), sourceHash) || current.targetVersion() != targetVersion
            || (checkFence && current.fenceEpoch() != fenceEpoch)) {
            throw new IllegalArgumentException("Migration ledger prepare conflicts with existing identity");
        }
    }

    private void persist() throws IOException {
        JsonObject payload = new JsonObject();
        unknownTopLevel.entrySet().forEach(entry -> payload.add(entry.getKey(), entry.getValue().deepCopy()));
        entries.forEach((key, entry) -> {
            JsonObject serialized = GSON.toJsonTree(entry).getAsJsonObject();
            JsonObject extras = entryExtras.get(key);
            if (extras != null) {
                extras.entrySet().forEach(item -> {
                    if (!serialized.has(item.getKey())) {
                        serialized.add(item.getKey(), item.getValue().deepCopy());
                    }
                });
            }
            payload.add(key, serialized);
        });
        store.save(payload);
    }

    private void loadEntries() throws IOException {
        Path epochFile = durabilityRoot.resolve(EPOCH_FILE);
        if (existsNoFollow(epochFile)) {
            readEpoch(epochFile);
        }
        entries.clear();
        entryExtras.clear();
        List<String> topLevelNames = new ArrayList<>();
        unknownTopLevel.entrySet().forEach(entry -> topLevelNames.add(entry.getKey()));
        topLevelNames.forEach(unknownTopLevel::remove);
        Path ledgerFile = durabilityRoot.resolve(LEDGER_FILE);
        if (!existsNoFollow(ledgerFile)) {
            return;
        }
        try {
            JsonElement payload = store.load();
            if (payload == null || payload.isJsonNull()) {
                return;
            }
            if (!payload.isJsonObject()) {
                throw new IllegalStateException("Migration ledger payload must be an object");
            }
            Map<String, Entry> loaded = new LinkedHashMap<>();
            Map<String, JsonObject> loadedExtras = new LinkedHashMap<>();
            JsonObject object = payload.getAsJsonObject();
            object.entrySet().forEach(item -> {
                if (containsControlExceptDelimiter(item.getKey())) {
                    throw new IllegalStateException("Migration ledger field name contains a control character");
                }
                if (item.getKey().indexOf('\u0000') >= 0 && !isEntryCandidate(item.getKey())) {
                    throw new IllegalStateException("Migration ledger entry key is structurally ambiguous");
                }
                if (!isEntryCandidate(item.getKey())) {
                    unknownTopLevel.add(item.getKey(), item.getValue().deepCopy());
                    return;
                }
                Entry entry = GSON.fromJson(item.getValue(), Entry.class);
                loaded.put(item.getKey(), entry);
                loadedExtras.put(item.getKey(), extractEntryExtras(item.getValue().getAsJsonObject()));
            });
            validateEntries(loaded);
            entries.putAll(loaded);
            entryExtras.putAll(loadedExtras);
        } catch (RuntimeException failure) {
            throw new IOException("Migration ledger is invalid: " + ledgerFile, failure);
        }
    }

    private static boolean isEntryCandidate(String key) {
        return isEntryKey(key);
    }

    private static boolean isEntryKey(String key) {
        if (key == null) {
            return false;
        }
        int first = key.indexOf('\u0000');
        int second = first < 0 ? -1 : key.indexOf('\u0000', first + 1);
        return first > 0 && second > first + 1 && key.indexOf('\u0000', second + 1) < 0
            && second < key.length() - 1;
    }

    private static boolean containsControlExceptDelimiter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\u0000' && Character.isISOControl(character)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject extractEntryExtras(JsonObject object) {
        JsonObject extras = new JsonObject();
        object.entrySet().forEach(entry -> {
            if (containsAnyControl(entry.getKey())) {
                throw new IllegalStateException("Migration ledger entry field name contains a control character");
            }
            if (!isKnownEntryField(entry.getKey())) {
                extras.add(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        return extras;
    }

    private static boolean isKnownEntryField(String name) {
        return switch (name) {
            case "migrationId", "resourceId", "sourceType", "sourceRevision", "sourceHash", "targetVersion",
                "fenceEpoch", "state", "diagnostic", "updatedAt" -> true;
            default -> false;
        };
    }

    private void mutate(LedgerMutation mutation) throws IOException {
        try (LedgerLock ignored = openLedgerLock()) {
            loadEntries();
            mutation.run();
        }
    }

    private LedgerLock openLedgerLock() throws IOException {
        Path lockPath = durabilityRoot.resolve(LEDGER_LOCK_FILE);
        requireOptionalRegular(lockPath, "Migration ledger lock");
        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            requireRegular(lockPath, "Migration ledger lock");
            channel.force(true);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("Another process owns the migration ledger lock", failure);
            }
            if (lock == null) {
                throw new IOException("Another process owns the migration ledger lock");
            }
            StorageSafety.forceDirectory(durabilityRoot);
            return new LedgerLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private String key(String migrationId, String resourceId, String sourceHash) {
        return requireText(migrationId, "migrationId") + "\u0000"
            + requireText(resourceId, "resourceId") + "\u0000"
            + requireText(sourceHash, "sourceHash");
    }

    public static Fence acquireFence(Path assetsRoot) throws IOException {
        Path root = ensureDirectory(assetsRoot, "assetsRoot");
        Path durabilityRoot = ensureDirectory(root.resolve(DURABILITY_DIRECTORY), "migration durability root");
        Path lockPath = durabilityRoot.resolve(LOCK_FILE);
        Path ledgerLockPath = durabilityRoot.resolve(LEDGER_LOCK_FILE);
        Path epochPath = durabilityRoot.resolve(EPOCH_FILE);
        boolean hadDurabilityState = hasDurabilityState(durabilityRoot);
        requireOptionalRegular(lockPath, "Migration lock");
        requireOptionalRegular(ledgerLockPath, "Migration ledger lock");
        requireOptionalRegular(epochPath, "Migration epoch");

        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            requireRegular(lockPath, "Migration lock");
            channel.force(true);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("Another process owns the migration fence", failure);
            }
            if (lock == null) {
                throw new IOException("Another process owns the migration fence");
            }

            RecoverableJsonStore.recoverCanonicalArtifacts(durabilityRoot,
                durabilityRoot.resolve(".quarantine").resolve("journals"));
            long epoch;
            if (existsNoFollow(epochPath)) {
                epoch = readEpoch(epochPath);
            } else {
                if (hadDurabilityState) {
                    throw new IOException("Migration epoch is missing and cannot be reset");
                }
                epoch = 1L;
            }
            StorageSafety.writeUtf8AtomicStrict(epochPath, Long.toString(epoch));
            StorageSafety.forceDirectory(durabilityRoot);
            return new Fence(channel, lock, epoch);
        } catch (IOException | RuntimeException failure) {
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private static long readEpoch(Path epochFile) throws IOException {
        requireRegular(epochFile, "Migration epoch");
        String value = StorageSafety.readUtf8(epochFile);
        if (value.isBlank()) {
            throw new IOException("Migration epoch is blank: " + epochFile);
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                throw new IOException("Migration epoch is malformed: " + epochFile);
            }
        }
        long epoch;
        try {
            epoch = Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IOException("Migration epoch is out of range: " + epochFile, failure);
        }
        if (epoch <= 0L || epoch == Long.MAX_VALUE) {
            throw new IOException("Migration epoch is outside the supported range: " + epochFile);
        }
        return epoch + 1L;
    }

    private static boolean hasDurabilityState(Path durabilityRoot) throws IOException {
        try (var stream = Files.list(durabilityRoot)) {
            return stream.anyMatch(path -> !path.getFileName().toString().equals(LEDGER_LOCK_FILE));
        }
    }

    private static Path ensureDirectory(Path candidate, String field) throws IOException {
        Path normalized = MigrationPaths.requirePath(Objects.requireNonNull(candidate, field), field);
        if (existsNoFollow(normalized)) {
            requireDirectory(normalized, field);
            return normalized;
        }
        Path existing = normalized;
        while (!existsNoFollow(existing)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException(field + " has no existing parent: " + normalized);
            }
        }
        requireDirectory(existing, field + " existing parent");
        StorageSafety.createDirectoriesNoSymlinks(existing, normalized);
        requireDirectory(normalized, field);
        return normalized;
    }

    private static void validateEntries(Map<String, Entry> loaded) {
        for (Map.Entry<String, Entry> item : loaded.entrySet()) {
            if (item.getKey() == null || item.getValue() == null) {
                throw new IllegalStateException("Migration ledger contains a null entry");
            }
            Entry entry = item.getValue();
            String expectedKey = entry.migrationId() + "\u0000" + entry.resourceId() + "\u0000" + entry.sourceHash();
            if (!item.getKey().equals(expectedKey)) {
                throw new IllegalStateException("Migration ledger entry key does not match its identity");
            }
            requireText(entry.migrationId(), "migrationId");
            requireText(entry.resourceId(), "resourceId");
            requireText(entry.sourceType(), "sourceType");
            requireText(entry.sourceHash(), "sourceHash");
            if (entry.sourceRevision() < 0L || entry.targetVersion() < 0) {
                throw new IllegalStateException("Migration ledger entry contains a negative source value");
            }
            requireEpoch(entry.fenceEpoch());
            if (!isKnownState(entry.state())) {
                throw new IllegalStateException("Migration ledger entry contains an unknown state");
            }
            if (entry.diagnostic() == null || entry.updatedAt() == null) {
                throw new IllegalStateException("Migration ledger entry contains a null field");
            }
        }
    }

    private static boolean isKnownState(String state) {
        return "PREPARED".equals(state) || "COMMITTED".equals(state) || "FAILED".equals(state);
    }

    private void restore(String key, Entry previous, JsonObject previousExtras) {
        if (previous == null) {
            entries.remove(key);
            entryExtras.remove(key);
            return;
        }
        entries.put(key, previous);
        if (previousExtras == null) {
            entryExtras.remove(key);
        } else {
            entryExtras.put(key, previousExtras);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " contains a control character");
            }
        }
        return value;
    }

    private static void requireEpoch(long epoch) {
        if (epoch <= 0L) {
            throw new IllegalArgumentException("fenceEpoch must be positive");
        }
    }

    private static void requireOptionalRegular(Path candidate, String description) throws IOException {
        if (existsNoFollow(candidate)) {
            requireRegular(candidate, description);
        }
    }

    private static void requireRegular(Path candidate, String description) throws IOException {
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " must be a regular non-symbolic-link file: " + candidate);
        }
    }

    private static void requireDirectory(Path candidate, String description) throws IOException {
        if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " must be a non-symbolic-link directory: " + candidate);
        }
    }

    private static boolean existsNoFollow(Path candidate) throws IOException {
        try {
            Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    @FunctionalInterface
    private interface LedgerMutation {
        void run() throws IOException;
    }

    public record Entry(String migrationId, String resourceId, String sourceType, long sourceRevision,
                        String sourceHash, int targetVersion, long fenceEpoch, String state, String diagnostic,
                        String updatedAt) {
    }

    private static final class LedgerLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private LedgerLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    public static final class Fence implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final long epoch;
        private boolean closed;

        private Fence(FileChannel channel, FileLock lock, long epoch) {
            this.channel = channel;
            this.lock = lock;
            this.epoch = epoch;
        }

        public long epoch() {
            return epoch;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
