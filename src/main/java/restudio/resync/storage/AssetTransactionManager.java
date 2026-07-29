package restudio.resync.storage;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class AssetTransactionManager {
    private static final int JOURNAL_VERSION = 2;
    private static final String PREPARED = "PREPARED";
    private static final String COMMITTED = "COMMITTED";
    private final Path assetsRoot;
    private final Path transactionRoot;
    private final Path snapshotRoot;
    private final Gson gson;
    private int recoveredTransactions;

    public AssetTransactionManager(Path assetsRoot, Gson gson) throws IOException {
        Files.createDirectories(assetsRoot);
        this.assetsRoot = assetsRoot.toAbsolutePath().normalize().toRealPath();
        this.transactionRoot = this.assetsRoot.resolve(".transactions");
        this.snapshotRoot = this.assetsRoot.resolve(".snapshots");
        this.gson = gson;
        Files.createDirectories(transactionRoot);
        Files.createDirectories(snapshotRoot);
        recover();
    }

    public synchronized String commit(Map<Path, String> writes, String mutationId) throws IOException {
        return commit(writes, Set.of(), mutationId);
    }

    private String commit(Map<Path, String> writes, Set<Path> deletes, String mutationId) throws IOException {
        Map<Path, String> safeWrites = writes != null ? writes : Map.of();
        Set<Path> safeDeletes = deletes != null ? deletes : Set.of();
        if (safeWrites.isEmpty() && safeDeletes.isEmpty()) {
            return "";
        }
        recover();
        String safeMutationId = mutationId == null ? "" : mutationId;
        String committedTransaction = committedTransaction(safeMutationId);
        if (!committedTransaction.isBlank()) {
            return committedTransaction;
        }
        Map<Path, String> normalizedWrites = new LinkedHashMap<>();
        for (Map.Entry<Path, String> write : safeWrites.entrySet()) {
            Path target = requireAssetPath(write.getKey());
            if (normalizedWrites.containsKey(target)) {
                throw new IOException("Asset transaction contains duplicate write targets: " + write.getKey());
            }
            normalizedWrites.put(target, write.getValue());
        }
        Set<Path> normalizedDeletes = new LinkedHashSet<>();
        for (Path deleted : safeDeletes) {
            Path target = requireAssetPath(deleted);
            if (!normalizedDeletes.add(target)) {
                throw new IOException("Asset transaction contains duplicate delete targets: " + deleted);
            }
            if (normalizedWrites.containsKey(target)) {
                throw new IOException("Asset transaction cannot write and delete the same target: " + deleted);
            }
        }
        String transactionId = UUID.randomUUID().toString();
        Path transactionDir = transactionRoot.resolve(transactionId);
        Files.createDirectories(transactionDir);
        boolean preparedWritten = false;
        try {
            List<Entry> entries = new ArrayList<>();
            int index = 0;
            for (Map.Entry<Path, String> write : normalizedWrites.entrySet()) {
                Path target = write.getKey();
                String stagedName = "content-" + index++ + ".json";
                byte[] content = (write.getValue() == null ? "" : write.getValue()).getBytes(StandardCharsets.UTF_8);
                StorageSafety.writeBytesAtomic(transactionDir.resolve(stagedName), content);
                entries.add(new Entry(assetsRoot.relativize(target).toString(), stagedName, StorageSafety.sha256(content), false, Files.isRegularFile(target)));
            }
            for (Path target : normalizedDeletes) {
                entries.add(new Entry(assetsRoot.relativize(target).toString(), "", "", true, Files.isRegularFile(target)));
            }
            Journal prepared = new Journal(JOURNAL_VERSION, transactionId, safeMutationId, PREPARED, entries);
            StorageSafety.writeUtf8Atomic(transactionDir.resolve("journal.json"), gson.toJson(prepared));
            preparedWritten = true;
            apply(transactionDir, prepared);
            Journal committed = new Journal(prepared.version(), prepared.id(), prepared.mutationId(), COMMITTED, prepared.entries());
            StorageSafety.writeUtf8Atomic(transactionDir.resolve("journal.json"), gson.toJson(committed));
            return transactionId;
        } catch (IOException | RuntimeException failure) {
            if (!preparedWritten) {
                try {
                    discardUnpreparedTransaction(transactionDir);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    public synchronized int recover() throws IOException {
        if (!Files.isDirectory(transactionRoot)) {
            return 0;
        }
        int recovered = 0;
        try (Stream<Path> paths = Files.list(transactionRoot)) {
            for (Path transactionDir : paths.filter(Files::isDirectory).sorted(Comparator.comparingLong(this::journalModified).thenComparing(Path::toString)).toList()) {
                Path journalFile = transactionDir.resolve("journal.json");
                if (!Files.isRegularFile(journalFile)) {
                    continue;
                }
                Journal journal = readJournal(journalFile);
                if (COMMITTED.equals(journal.state())) {
                    continue;
                }
                if (journal.version() != JOURNAL_VERSION) {
                    throw new IOException("Unsupported prepared asset transaction version: " + journal.version());
                }
                if (!PREPARED.equals(journal.state())) {
                    throw new IOException("Unknown asset transaction state: " + journal.state());
                }
                apply(transactionDir, journal);
                StorageSafety.writeUtf8Atomic(journalFile, gson.toJson(new Journal(journal.version(), journal.id(), journal.mutationId(), COMMITTED, journal.entries())));
                recovered++;
            }
        }
        recoveredTransactions += recovered;
        return recovered;
    }

    public int getRecoveredTransactions() {
        return recoveredTransactions;
    }

    public synchronized RestorePreview previewRestore(String transactionId) throws IOException {
        Journal journal = committedJournal(transactionId);
        return new RestorePreview(transactionId, journal.entries().stream().map(Entry::target).sorted().toList());
    }

    public synchronized String restore(String transactionId, String mutationId) throws IOException {
        recover();
        Journal journal = committedJournal(transactionId);
        Path root = requireSnapshotRoot(transactionId);
        Map<Path, String> writes = new LinkedHashMap<>();
        Set<Path> deletes = new java.util.LinkedHashSet<>();
        for (Entry entry : journal.entries()) {
            Path target = requireAssetPath(assetsRoot.resolve(entry.target()));
            Path snapshot = root.resolve(entry.target()).normalize();
            boolean existed = entry.existed() != null ? entry.existed() : Files.isRegularFile(snapshot);
            if (existed) {
                if (!snapshot.startsWith(root) || !Files.isRegularFile(snapshot)) {
                    throw new IOException("Asset snapshot is incomplete: " + entry.target());
                }
                writes.put(target, StorageSafety.readUtf8(snapshot));
            } else {
                deletes.add(target);
            }
        }
        return commit(writes, deletes, mutationId);
    }

    private void apply(Path transactionDir, Journal journal) throws IOException {
        Path transactionSnapshotRoot = snapshotRoot.resolve(journal.id());
        for (Entry entry : journal.entries()) {
            Path target = requireAssetPath(assetsRoot.resolve(entry.target()));
            Path snapshot = transactionSnapshotRoot.resolve(entry.target()).normalize();
            if (!snapshot.startsWith(transactionSnapshotRoot)) {
                throw new IOException("Unsafe asset transaction snapshot: " + entry.target());
            }
            if (Boolean.TRUE.equals(entry.existed())) {
                Files.createDirectories(snapshot.getParent());
                if (!Files.exists(snapshot)) {
                    if (!Files.isRegularFile(target)) {
                        throw new IOException("Asset transaction lost its original target: " + entry.target());
                    }
                    StorageSafety.writeBytesAtomic(snapshot, Files.readAllBytes(target));
                }
            }
            if (entry.delete()) {
                StorageSafety.deleteIfExists(target);
                continue;
            }
            Path staged = transactionDir.resolve(entry.staged()).normalize();
            if (!staged.startsWith(transactionDir) || !Files.isRegularFile(staged)) {
                throw new IOException("Missing staged asset transaction content: " + entry.staged());
            }
            byte[] content = Files.readAllBytes(staged);
            if (!entry.hash().equals(StorageSafety.sha256(content))) {
                throw new IOException("Corrupt staged asset transaction content: " + entry.staged());
            }
            StorageSafety.writeBytesAtomic(target, content);
        }
    }

    private String committedTransaction(String mutationId) throws IOException {
        if (mutationId.isBlank()) {
            return "";
        }
        try (Stream<Path> paths = Files.list(transactionRoot)) {
            for (Path transactionDir : paths.filter(Files::isDirectory).toList()) {
                Path journalFile = transactionDir.resolve("journal.json");
                if (!Files.isRegularFile(journalFile)) {
                    continue;
                }
                Journal journal = readJournal(journalFile);
                if (COMMITTED.equals(journal.state()) && mutationId.equals(journal.mutationId())) {
                    return journal.id();
                }
            }
        }
        return "";
    }

    private Journal committedJournal(String transactionId) throws IOException {
        Path transactionDir = requireTransactionRoot(transactionId);
        Path journalFile = transactionDir.resolve("journal.json");
        if (!Files.isRegularFile(journalFile)) {
            throw new IOException("Asset transaction does not exist: " + transactionId);
        }
        Journal journal = readJournal(journalFile);
        if (!COMMITTED.equals(journal.state())) {
            throw new IOException("Asset transaction is not committed: " + transactionId);
        }
        return journal;
    }

    private Journal readJournal(Path journalFile) throws IOException {
        try {
            Journal journal = gson.fromJson(StorageSafety.readUtf8(journalFile), Journal.class);
            if (journal == null || journal.entries() == null || journal.state() == null) {
                throw new IOException("Incomplete asset transaction journal: " + journalFile);
            }
            return journal;
        } catch (RuntimeException failure) {
            throw new IOException("Corrupt asset transaction journal: " + journalFile, failure);
        }
    }

    private long journalModified(Path transactionDir) {
        try {
            return Files.getLastModifiedTime(transactionDir.resolve("journal.json")).toMillis();
        } catch (IOException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private Path requireAssetPath(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Asset transaction target is missing");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(assetsRoot) || normalized.equals(assetsRoot)
            || normalized.startsWith(transactionRoot) || normalized.startsWith(snapshotRoot)) {
            throw new IOException("Asset transaction target is outside the asset root: " + path);
        }
        Path existingParent = normalized.getParent();
        while (existingParent != null && !Files.exists(existingParent)) {
            existingParent = existingParent.getParent();
        }
        if (existingParent == null || !existingParent.toRealPath().startsWith(assetsRoot)) {
            throw new IOException("Asset transaction target escapes the asset root: " + path);
        }
        return normalized;
    }

    private void discardUnpreparedTransaction(Path transactionDir) throws IOException {
        if (!transactionDir.normalize().startsWith(transactionRoot) || !Files.isDirectory(transactionDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(transactionDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        StorageSafety.forceDirectory(transactionRoot);
    }

    private Path requireSnapshotRoot(String transactionId) throws IOException {
        requireTransactionId(transactionId);
        Path root = snapshotRoot.resolve(transactionId).normalize();
        if (!root.startsWith(snapshotRoot)) {
            throw new IOException("Unsafe asset snapshot id");
        }
        return root;
    }

    private Path requireTransactionRoot(String transactionId) throws IOException {
        requireTransactionId(transactionId);
        Path root = transactionRoot.resolve(transactionId).normalize();
        if (!root.startsWith(transactionRoot)) {
            throw new IOException("Unsafe asset transaction id");
        }
        return root;
    }

    private void requireTransactionId(String transactionId) throws IOException {
        if (transactionId == null || !transactionId.matches("[A-Za-z0-9-]{1,96}")) {
            throw new IOException("Invalid asset transaction id");
        }
    }

    private record Journal(int version, String id, String mutationId, String state, List<Entry> entries) {
    }

    private record Entry(String target, String staged, String hash, boolean delete, Boolean existed) {
    }

    public record RestorePreview(String transactionId, List<String> files) {
    }
}
