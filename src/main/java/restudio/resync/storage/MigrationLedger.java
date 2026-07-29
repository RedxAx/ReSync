package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MigrationLedger {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_MAP = new TypeToken<Map<String, Entry>>() { }.getType();
    private final RecoverableJsonStore store;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public MigrationLedger(Path assetsRoot) throws IOException {
        Path durabilityRoot = assetsRoot.resolve(".durability");
        Files.createDirectories(durabilityRoot);
        this.store = new RecoverableJsonStore(durabilityRoot.resolve("migrations.json"), GSON);
        if (Files.isRegularFile(durabilityRoot.resolve("migrations.json"))) {
            Map<String, Entry> loaded = GSON.fromJson(store.load(), ENTRY_MAP);
            if (loaded != null) {
                entries.putAll(loaded);
            }
        }
    }

    public synchronized boolean isCommitted(String migrationId, String resourceId, String sourceHash) {
        Entry entry = entries.get(key(migrationId, resourceId, sourceHash));
        return entry != null && "COMMITTED".equals(entry.state());
    }

    public synchronized void prepare(String migrationId, String resourceId, String sourceType, long sourceRevision, String sourceHash, int targetVersion, long fenceEpoch) throws IOException {
        entries.put(key(migrationId, resourceId, sourceHash), new Entry(migrationId, resourceId, sourceType, sourceRevision, sourceHash, targetVersion, fenceEpoch, "PREPARED", "", Instant.now().toString()));
        persist();
    }

    public synchronized void commit(String migrationId, String resourceId, String sourceHash) throws IOException {
        update(migrationId, resourceId, sourceHash, "COMMITTED", "");
    }

    public synchronized void fail(String migrationId, String resourceId, String sourceHash, String diagnostic) throws IOException {
        update(migrationId, resourceId, sourceHash, "FAILED", diagnostic);
    }

    private void update(String migrationId, String resourceId, String sourceHash, String state, String diagnostic) throws IOException {
        String key = key(migrationId, resourceId, sourceHash);
        Entry current = entries.get(key);
        if (current == null) {
            return;
        }
        entries.put(key, new Entry(current.migrationId(), current.resourceId(), current.sourceType(), current.sourceRevision(), current.sourceHash(), current.targetVersion(), current.fenceEpoch(), state, diagnostic == null ? "" : diagnostic, Instant.now().toString()));
        persist();
    }

    private void persist() throws IOException {
        store.save(GSON.toJsonTree(entries, ENTRY_MAP));
    }

    private String key(String migrationId, String resourceId, String sourceHash) {
        return migrationId + "\u0000" + resourceId + "\u0000" + sourceHash;
    }

    public static Fence acquireFence(Path assetsRoot) throws IOException {
        Path durabilityRoot = assetsRoot.resolve(".durability");
        Files.createDirectories(durabilityRoot);
        FileChannel channel = FileChannel.open(durabilityRoot.resolve("migration.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("Another process owns the migration fence", failure);
        }
        if (lock == null) {
            channel.close();
            throw new IOException("Another process owns the migration fence");
        }
        Path epochFile = durabilityRoot.resolve("migration.epoch");
        long epoch = 0L;
        if (Files.isRegularFile(epochFile)) {
            try {
                epoch = Long.parseLong(StorageSafety.readUtf8(epochFile).trim());
            } catch (RuntimeException ignored) {
            }
        }
        epoch++;
        StorageSafety.writeUtf8Atomic(epochFile, Long.toString(epoch));
        return new Fence(channel, lock, epoch);
    }

    public record Entry(String migrationId, String resourceId, String sourceType, long sourceRevision, String sourceHash, int targetVersion, long fenceEpoch, String state, String diagnostic, String updatedAt) {
    }

    public static final class Fence implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final long epoch;

        private Fence(FileChannel channel, FileLock lock, long epoch) {
            this.channel = channel;
            this.lock = lock;
            this.epoch = epoch;
        }

        public long epoch() {
            return epoch;
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
