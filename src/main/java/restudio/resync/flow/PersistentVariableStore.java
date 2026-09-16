package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ResolvedPersistenceOwnership;
import restudio.resync.storage.RecoverableJsonStore;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PersistentVariableStore implements CloseablePersistenceParticipant, ResolvedPersistenceOwnership {
    public static final String OWNER = "resync.flow-variables";
    public static final String FILE_NAME = "flow-variables.json";
    private static final String PREVIOUS_SUFFIX = ".previous";
    private static final String QUARANTINE_DIRECTORY = ".quarantine";
    private static final String JOURNAL_DIRECTORY = "journals";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static volatile PersistentVariableStore instance;

    private final Path scopeRoot;
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private volatile Binding activeBinding;
    private PersistenceState persistenceState = PersistenceState.OPEN;
    private boolean loaded;
    private long generation;

    public PersistentVariableStore(Path scopeRoot) {
        this.scopeRoot = prepareScope(scopeRoot);
        Path file = persistenceFile(this.scopeRoot);
        try {
            validateFile(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                new RecoverableJsonStore(file, GSON).save(GSON.toJsonTree(Map.of(), MAP_TYPE));
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Persistent variable store cannot be initialized: " + file, exception);
        }
        this.activeBinding = new Binding(file, new RecoverableJsonStore(file, GSON));
        loadIfNeeded();
    }

    public static PersistentVariableStore getInstance() {
        PersistentVariableStore current = instance;
        if (current != null && !current.isClosed()) {
            return current;
        }
        synchronized (PersistentVariableStore.class) {
            current = instance;
            if (current == null || current.isClosed()) {
                Path basePath = ReSync.getInstance() != null
                    ? ReSync.getInstance().getDataFolder().toPath()
                    : Path.of(".");
                current = new PersistentVariableStore(basePath);
                instance = current;
            }
            return current;
        }
    }

    public synchronized Object get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        loadIfNeeded();
        return copyValue(variables.get(key));
    }

    public synchronized boolean contains(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        loadIfNeeded();
        return variables.containsKey(key);
    }

    public synchronized Map<String, Object> getAll() {
        loadIfNeeded();
        return copyMap(variables);
    }

    public synchronized void set(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        requireWritable();
        loadIfNeeded();
        Map<String, Object> next = copyMap(variables);
        if (value == null) {
            next.remove(key);
        } else {
            next.put(key, normalizeValue(value));
        }
        if (sameValues(next, variables)) {
            return;
        }
        persist(next);
        replaceVariables(next);
    }

    public synchronized void remove(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        requireWritable();
        loadIfNeeded();
        if (!variables.containsKey(key)) {
            return;
        }
        Map<String, Object> next = copyMap(variables);
        next.remove(key);
        persist(next);
        replaceVariables(next);
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public synchronized Path root() {
        return activeBinding.file();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public synchronized boolean owns(Path file) {
        Path candidate = MigrationPaths.requirePath(file, "file");
        return ownsResolved(candidate);
    }

    @Override
    public synchronized boolean ownsResolved(Path file) {
        Path candidate = file;
        Path journal = activeBinding.file();
        Path previous = previous(journal);
        Path quarantine = quarantine(journal);
        return candidate.equals(journal)
            || candidate.equals(previous)
            || (candidate.getParent() != null && candidate.getParent().equals(quarantine)
                && candidate.getFileName() != null
                && candidate.getFileName().toString().startsWith(journal.getFileName().toString() + "."));
    }

    @Override
    public synchronized void flush() throws IOException {
        requireNotClosed();
        loadIfNeeded();
        persistChecked(variables);
    }

    @Override
    public synchronized void quiesce() throws IOException {
        requireNotClosed();
        if (persistenceState == PersistenceState.QUIESCED) {
            return;
        }
        if (persistenceState != PersistenceState.OPEN) {
            throw new IOException("Persistent variable store is not open");
        }
        try {
            flush();
            persistenceState = PersistenceState.QUIESCED;
        } catch (IOException | RuntimeException failure) {
            persistenceState = PersistenceState.OPEN;
            throw failure;
        }
    }

    @Override
    public synchronized void resume() throws IOException {
        requireNotClosed();
        if (persistenceState == PersistenceState.OPEN) {
            return;
        }
        if (persistenceState != PersistenceState.QUIESCED) {
            throw new IOException("Persistent variable store is not quiesced");
        }
        try {
            Map<String, Object> persisted = read(activeBinding);
            if (!sameValues(persisted, variables)) {
                throw new IOException("Persistent variable store is out of sync with the active file");
            }
            persistenceState = PersistenceState.OPEN;
        } catch (IOException | RuntimeException failure) {
            persistenceState = PersistenceState.QUIESCED;
            throw failure;
        }
    }

    @Override
    public synchronized void rebind(Path activeRoot) throws IOException {
        requireNotClosed();
        if (persistenceState != PersistenceState.QUIESCED) {
            throw new IOException("Persistent variable store must be quiesced before rebind");
        }
        Path candidateScope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidateFile = persistenceFile(candidateScope);
        validateFile(candidateFile);
        Binding candidateBinding = new Binding(candidateFile, new RecoverableJsonStore(candidateFile, GSON));
        Map<String, Object> candidateValues = read(candidateBinding);
        if (candidateFile.equals(activeBinding.file())) {
            if (!sameValues(candidateValues, variables)) {
                throw new IOException("Persistent variable store is out of sync with the active file");
            }
            return;
        }
        Binding previousBinding = activeBinding;
        Map<String, Object> previousValues = copyMap(variables);
        long previousGeneration = generation;
        try {
            if (!Files.exists(candidateFile, LinkOption.NOFOLLOW_LINKS)) {
                candidateBinding.store().save(GSON.toJsonTree(Map.of(), MAP_TYPE));
            }
            activeBinding = candidateBinding;
            replaceVariables(candidateValues);
            loaded = true;
            generation = Math.addExact(previousGeneration, 1L);
        } catch (RuntimeException failure) {
            activeBinding = previousBinding;
            replaceVariables(previousValues);
            loaded = true;
            generation = previousGeneration;
            throw new IOException("Persistent variable store rebind could not be committed", failure);
        }
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        requireNotClosed();
        loadIfNeeded();
        Map<String, Object> persisted = read(activeBinding);
        if (!sameValues(persisted, variables)) {
            throw new IOException("Persistent variable store is out of sync with the active file");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (persistenceState == PersistenceState.CLOSED) {
            return;
        }
        if (persistenceState == PersistenceState.OPEN) {
            quiesce();
        }
        persistenceState = PersistenceState.CLOSED;
    }

    public synchronized boolean isPersistenceQuiesced() {
        return persistenceState == PersistenceState.QUIESCED;
    }

    public synchronized boolean isClosed() {
        return persistenceState == PersistenceState.CLOSED;
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized Path persistenceRoot() {
        return activeBinding.file();
    }

    private void loadIfNeeded() {
        if (loaded) {
            return;
        }
        try {
            replaceVariables(read(activeBinding));
            loaded = true;
        } catch (IOException | RuntimeException failure) {
            Log.warn("Failed to load persistent variables: " + failure.getMessage());
            throw new IllegalStateException("Failed to load persistent variables: " + activeBinding.file(), failure);
        }
    }

    private Map<String, Object> read(Binding binding) throws IOException {
        Path file = binding.file();
        validateFile(file);
        JsonElement payload = binding.store().load();
        if (payload == null || payload.isJsonNull()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> values = GSON.fromJson(payload, MAP_TYPE);
        if (values == null) {
            return new LinkedHashMap<>();
        }
        return normalizeMap(values);
    }

    private void persist(Map<String, Object> next) {
        try {
            persistChecked(next);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Failed to save persistent variables: " + activeBinding.file(), failure);
        }
    }

    private void persistChecked(Map<String, Object> next) throws IOException {
        validateFile(activeBinding.file());
        activeBinding.store().save(GSON.toJsonTree(next, MAP_TYPE));
    }

    private void replaceVariables(Map<String, Object> next) {
        variables.clear();
        variables.putAll(copyMap(next));
    }

    private void requireWritable() {
        if (persistenceState != PersistenceState.OPEN) {
            throw new IllegalStateException("Persistent variable store is "
                + persistenceState.name().toLowerCase() + "; mutation rejected");
        }
    }

    private void requireNotClosed() throws IOException {
        if (persistenceState == PersistenceState.CLOSED) {
            throw new IOException("Persistent variable store is closed");
        }
    }

    private static Path prepareScope(Path value) {
        Path scope = MigrationPaths.requirePath(value, "scopeRoot");
        try {
            if (!Files.exists(scope, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(scope);
            }
            return MigrationPaths.requireDirectory(scope, "scopeRoot");
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Persistent variable store scope is invalid: " + scope, exception);
        }
    }

    private static Path persistenceFile(Path scope) {
        Path file = scope.resolve(FILE_NAME).toAbsolutePath().normalize();
        if (!file.startsWith(scope) || file.getParent() == null || !file.getParent().equals(scope)) {
            throw new IllegalArgumentException("Persistent variable file escaped its scope root");
        }
        return file;
    }

    private static void validateFile(Path file) throws IOException {
        Path normalized;
        try {
            normalized = MigrationPaths.requirePath(file, "persistent variable file");
        } catch (RuntimeException exception) {
            throw new IOException("Persistent variable file path is invalid: " + file, exception);
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Persistent variable file has no parent");
        }
        MigrationPaths.requireDirectory(parent, "persistent variable directory");
        MigrationPaths.requireNoSymlinkTraversal(parent, normalized);
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
            && (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized))) {
            throw new IOException("Persistent variable file must be a regular non-symbolic-link file: " + normalized);
        }
    }

    private static Path previous(Path journal) {
        return journal.resolveSibling(journal.getFileName() + PREVIOUS_SUFFIX);
    }

    private static Path quarantine(Path journal) {
        return journal.getParent().resolve(QUARANTINE_DIRECTORY).resolve(JOURNAL_DIRECTORY);
    }

    private static Map<String, Object> normalizeMap(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Persistent variable key must not be blank");
            }
            normalized.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return normalized;
    }

    private static Object normalizeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object entry : iterable) {
                normalized.add(normalizeValue(entry));
            }
            return normalized;
        }
        return value.toString();
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }

    private static boolean sameValues(Object first, Object second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (first instanceof Number firstNumber && second instanceof Number secondNumber) {
            return Double.compare(firstNumber.doubleValue(), secondNumber.doubleValue()) == 0;
        }
        if (first instanceof Map<?, ?> firstMap && second instanceof Map<?, ?> secondMap) {
            if (firstMap.size() != secondMap.size() || !firstMap.keySet().equals(secondMap.keySet())) {
                return false;
            }
            for (Object key : firstMap.keySet()) {
                if (!sameValues(firstMap.get(key), secondMap.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (first instanceof Iterable<?> firstIterable && second instanceof Iterable<?> secondIterable) {
            var firstIterator = firstIterable.iterator();
            var secondIterator = secondIterable.iterator();
            while (firstIterator.hasNext() && secondIterator.hasNext()) {
                if (!sameValues(firstIterator.next(), secondIterator.next())) {
                    return false;
                }
            }
            return !firstIterator.hasNext() && !secondIterator.hasNext();
        }
        return first.equals(second);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            for (Object entry : iterable) {
                copy.add(copyValue(entry));
            }
            return copy;
        }
        return value;
    }

    private record Binding(Path file, RecoverableJsonStore store) {
        private Binding {
            file = MigrationPaths.requirePath(file, "persistent variable file");
            store = Objects.requireNonNull(store, "store");
        }
    }

    private enum PersistenceState {
        OPEN,
        QUIESCED,
        CLOSED
    }
}
