package restudio.resync.flow.triggers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.storage.StorageSafety;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class TriggerRegistry {
    private static final String FILE_NAME = "triggers.json";
    private static final int MAX_BINDINGS = 4096;
    private static final int MAX_BINDING_TEXT = 1024;
    private final JavaPlugin plugin;
    private volatile Path file;
    private final Map<String, TriggerBinding> bindings = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private PersistenceState persistenceState = PersistenceState.OPEN;
    private long bindingEpoch = 1L;
    private long bindingObservationGeneration;
    private volatile BindingObservation bindingObservation;

    private record PersistedTriggerState(long bindingEpoch, List<TriggerBinding> bindings) {
    }

    private enum PersistenceState {
        OPEN,
        QUIESCED
    }

    public TriggerRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = requireFile(plugin.getDataFolder().toPath().resolve(FILE_NAME));
        load();
    }

    public TriggerRegistry(File file) {
        this.plugin = null;
        this.file = requireFile(file.toPath());
        load();
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public synchronized Path getPersistenceFile() {
        return file;
    }

    public synchronized Path getPersistenceScope() {
        return file.getParent();
    }

    public synchronized List<TriggerBinding> getBindings() {
        return bindings.values().stream().map(this::copyBinding).toList();
    }

    public synchronized List<TriggerBinding> getBindings(TriggerType type) {
        List<TriggerBinding> filtered = new ArrayList<>();
        for (TriggerBinding binding : bindings.values()) {
            if (binding.getType() == type) {
                filtered.add(copyBinding(binding));
            }
        }
        return filtered;
    }

    public synchronized long bindingEpoch() {
        return bindingEpoch;
    }

    public synchronized String bindingHash() {
        return bindingHashValues(bindings.values());
    }

    public synchronized BindingState bindingState() {
        return new BindingState(bindingEpoch, bindingHashValues(bindings.values()),
            bindings.values().stream().map(binding -> new TriggerBinding(binding.getId(), binding.getFlowId(),
                binding.getType(), binding.getContext())).toList());
    }

    public BindingObservation bindingObservation() {
        return bindingObservation;
    }

    public boolean isCurrent(BindingObservation observation) {
        return observation != null && observation.available() && observation == bindingObservation;
    }

    public String bindingHash(List<TriggerBinding> candidates) {
        return bindingHashValues(candidates != null ? candidates : List.of());
    }

    public synchronized BindingMutationResult setBindingsPreservingTypesIfCurrent(List<TriggerBinding> newBindings,
                                                                                    Set<TriggerType> preservedTypes,
                                                                                    long expectedEpoch,
                                                                                    String expectedHash) {
        if (expectedEpoch != bindingEpoch || expectedHash == null || !expectedHash.equals(bindingHash())) {
            return BindingMutationResult.rejected(bindingEpoch, bindingHash());
        }
        setBindingsPreservingTypes(newBindings, preservedTypes);
        return BindingMutationResult.accepted(bindingEpoch, bindingHash());
    }

    public synchronized void setBindings(List<TriggerBinding> newBindings) {
        requireWritable();
        List<TriggerBinding> candidates = validatedBindings(newBindings);
        Map<String, TriggerBinding> previous = new LinkedHashMap<>(bindings);
        invalidateBindingObservation();
        bindings.clear();
        for (TriggerBinding binding : candidates) {
            bindings.put(binding.getId(), binding);
        }
        save(previous);
    }

    public synchronized void setBindingsPreservingType(List<TriggerBinding> newBindings, TriggerType preservedType) {
        setBindingsPreservingTypes(newBindings, Set.of(preservedType));
    }

    public synchronized void setBindingsPreservingTypes(List<TriggerBinding> newBindings, Set<TriggerType> preservedTypes) {
        requireWritable();
        List<TriggerBinding> candidates = validatedBindings(newBindings);
        Map<String, TriggerBinding> previous = new LinkedHashMap<>(bindings);
        Set<TriggerType> preserved = preservedTypes != null ? Set.copyOf(preservedTypes) : Set.of();
        List<TriggerBinding> preservedBindings = bindings.values().stream().filter(binding -> preserved.contains(binding.getType())).toList();
        invalidateBindingObservation();
        bindings.clear();
        for (TriggerBinding binding : candidates) {
            if (!preserved.contains(binding.getType())) {
                bindings.put(binding.getId(), binding);
            }
        }
        for (TriggerBinding binding : preservedBindings) {
            if (binding.getId() != null) {
                bindings.put(binding.getId(), binding);
            }
        }
        save(previous);
    }

    public synchronized void addBinding(TriggerBinding binding) {
        requireWritable();
        List<TriggerBinding> candidates = validatedBindings(binding == null ? List.of() : List.of(binding));
        if (candidates.isEmpty()) return;
        Map<String, TriggerBinding> previous = new LinkedHashMap<>(bindings);
        TriggerBinding candidate = candidates.getFirst();
        invalidateBindingObservation();
        bindings.put(candidate.getId(), candidate);
        save(previous);
    }

    public synchronized void removeBinding(String id) {
        requireWritable();
        if (id == null) {
            return;
        }
        Map<String, TriggerBinding> previous = new LinkedHashMap<>(bindings);
        invalidateBindingObservation();
        bindings.remove(id);
        save(previous);
    }

    public synchronized void removeFlowBindings(String flowId) {
        requireWritable();
        if (flowId == null) {
            return;
        }
        Map<String, TriggerBinding> previous = new LinkedHashMap<>(bindings);
        invalidateBindingObservation();
        bindings.values().removeIf(binding -> flowId.equals(binding.getFlowId()));
        save(previous);
    }

    public synchronized void replaceFlowBindings(String flowId, TriggerType type, List<TriggerBinding> newBindings) {
        requireWritable();
        if (flowId == null || type == null) {
            return;
        }
        List<TriggerBinding> candidates = validatedBindings(newBindings);
        Map<String, TriggerBinding> previous = new LinkedHashMap<>(bindings);
        invalidateBindingObservation();
        bindings.values().removeIf(binding -> flowId.equals(binding.getFlowId()) && type == binding.getType());
        for (TriggerBinding binding : candidates) {
            if (flowId.equals(binding.getFlowId()) && type == binding.getType()) {
                bindings.put(binding.getId(), binding);
            }
        }
        save(previous);
    }

    public synchronized void flushPersistence() throws IOException {
        invalidateBindingObservation();
        flushPersistenceCurrent();
        publishBindingObservation();
    }

    private void flushPersistenceCurrent() throws IOException {
        Path activeFile = requireActiveFile();
        if (Files.exists(activeFile, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(activeFile);
            readBindings(activeFile);
            StorageSafety.forceDirectory(activeFile.getParent());
        } else {
            Files.createDirectories(activeFile.getParent());
            StorageSafety.forceDirectory(activeFile.getParent());
        }
    }

    public synchronized void quiescePersistence() throws IOException {
        if (persistenceState == PersistenceState.QUIESCED) {
            return;
        }
        invalidateBindingObservation();
        flushPersistenceCurrent();
        persistenceState = PersistenceState.QUIESCED;
        publishBindingObservation();
    }

    public synchronized void resumePersistence() throws IOException {
        invalidateBindingObservation();
        healthCheckPersistenceCurrent();
        persistenceState = PersistenceState.OPEN;
        publishBindingObservation();
    }

    public synchronized void rebindPersistence(Path candidateScopeRoot) throws IOException {
        if (persistenceState != PersistenceState.QUIESCED) {
            throw new IOException("Trigger persistence must be quiesced before rebind");
        }
        invalidateBindingObservation();
        Path scope = MigrationPaths.requireDirectory(candidateScopeRoot, "activeRoot");
        Path candidateFile = scope.resolve(FILE_NAME).toAbsolutePath().normalize();
        if (!candidateFile.startsWith(scope) || candidateFile.equals(scope)) {
            throw new IOException("Trigger rebind escaped active root");
        }
        PersistedTriggerState candidateState = readState(candidateFile);
        Map<String, TriggerBinding> candidateBindings = toBindingMap(candidateState.bindings());
        if (!Files.exists(candidateFile, LinkOption.NOFOLLOW_LINKS)) {
            StorageSafety.forceDirectory(scope);
        }
        file = candidateFile;
        bindings.clear();
        bindings.putAll(candidateBindings);
        bindingEpoch = Math.max(bindingEpoch, candidateState.bindingEpoch());
        publishBindingObservation();
    }

    public synchronized void healthCheckPersistence() throws IOException {
        invalidateBindingObservation();
        healthCheckPersistenceCurrent();
        publishBindingObservation();
    }

    private void healthCheckPersistenceCurrent() throws IOException {
        Path activeFile = requireActiveFile();
        PersistedTriggerState persistedState = readState(activeFile);
        if (!sameBindings(bindings, toBindingMap(persistedState.bindings()))
            || persistedState.bindingEpoch() != bindingEpoch) {
            throw new IOException("Trigger persistence is out of sync with triggers.json");
        }
        if (Files.exists(activeFile, LinkOption.NOFOLLOW_LINKS)) {
            StorageSafety.forceDirectory(activeFile.getParent());
        }
    }

    private void load() {
        bindings.clear();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            publishBindingObservation();
            return;
        }
        try {
            PersistedTriggerState state = readState(file);
            bindings.putAll(toBindingMap(state.bindings()));
            bindingEpoch = Math.max(bindingEpoch, state.bindingEpoch());
            publishBindingObservation();
        } catch (IOException e) {
            invalidateBindingObservation();
            throw new IllegalStateException("Failed to load trigger bindings: " + file, e);
        }
    }

    private void save(Map<String, TriggerBinding> previous) {
        try {
            long nextEpoch = nextBindingEpoch(bindingEpoch);
            String json = gson.toJson(new PersistedTriggerState(nextEpoch, new ArrayList<>(bindings.values())));
            StorageSafety.writeUtf8Atomic(requireActiveFile(), json);
            bindingEpoch = nextEpoch;
            publishBindingObservation();
        } catch (IOException e) {
            bindings.clear();
            bindings.putAll(previous);
            publishBindingObservation();
            Log.warn("Failed to save trigger bindings: " + e.getMessage());
            throw new IllegalStateException("Failed to save trigger bindings", e);
        }
    }

    private Path requireActiveFile() throws IOException {
        Path activeFile = MigrationPaths.requirePath(file, "trigger file");
        Path parent = activeFile.getParent();
        if (parent == null) {
            throw new IOException("Trigger file has no parent");
        }
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(parent);
        }
        MigrationPaths.requireDirectory(parent, "trigger persistence root");
        return activeFile;
    }

    private Map<String, TriggerBinding> readBindings(Path candidateFile) throws IOException {
        return toBindingMap(readState(candidateFile).bindings());
    }

    private PersistedTriggerState readState(Path candidateFile) throws IOException {
        Path normalized = requireFile(candidateFile);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return new PersistedTriggerState(1L, List.of());
        }
        requireRegularFile(normalized);
        try {
            JsonElement root = JsonParser.parseString(Files.readString(normalized, StandardCharsets.UTF_8));
            long epoch = 1L;
            JsonElement bindingsValue = root;
            if (root != null && root.isJsonObject()) {
                JsonObject state = root.getAsJsonObject();
                JsonElement epochValue = state.get("bindingEpoch");
                if (epochValue != null && epochValue.isJsonPrimitive() && epochValue.getAsJsonPrimitive().isNumber()) {
                    epoch = epochValue.getAsLong();
                }
                bindingsValue = state.get("bindings");
            }
            if (epoch < 1L || bindingsValue == null || !bindingsValue.isJsonArray()) {
                throw new IOException("Invalid trigger persistence state");
            }
            List<TriggerBinding> loaded = gson.fromJson(bindingsValue, new TypeToken<List<TriggerBinding>>() {
            }.getType());
            if (loaded == null) {
                return new PersistedTriggerState(epoch, List.of());
            }
            return new PersistedTriggerState(epoch, validatedBindings(loaded));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid trigger bindings: " + exception.getMessage(), exception);
        }
    }

    private Map<String, TriggerBinding> toBindingMap(List<TriggerBinding> values) {
        Map<String, TriggerBinding> result = new LinkedHashMap<>();
        if (values != null) {
            for (TriggerBinding binding : values) {
                result.put(binding.getId(), binding);
            }
        }
        return result;
    }

    private List<TriggerBinding> validatedBindings(List<TriggerBinding> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_BINDINGS) {
            throw new IllegalArgumentException("Trigger binding count exceeds the supported limit");
        }
        Map<String, TriggerBinding> unique = new LinkedHashMap<>();
        for (TriggerBinding binding : values) {
            if (binding == null || binding.getId() == null || binding.getId().isBlank()
                || binding.getFlowId() == null || binding.getFlowId().isBlank() || binding.getType() == null
                || binding.getId().length() > MAX_BINDING_TEXT || binding.getFlowId().length() > MAX_BINDING_TEXT
                || binding.getContext() != null && binding.getContext().length() > MAX_BINDING_TEXT) {
                throw new IllegalArgumentException("Trigger binding is invalid");
            }
            if (unique.putIfAbsent(binding.getId(), new TriggerBinding(binding.getId(), binding.getFlowId(),
                binding.getType(), binding.getContext())) != null) {
                throw new IllegalArgumentException("Duplicate trigger binding ID: " + binding.getId());
            }
        }
        return List.copyOf(unique.values());
    }

    private static Path requireFile(Path candidate) {
        return MigrationPaths.requirePath(candidate, "trigger file");
    }

    private static void requireRegularFile(Path candidate) throws IOException {
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Trigger file must be a regular non-symbolic-link file: " + candidate);
        }
    }

    private static void validateBinding(TriggerBinding binding) throws IOException {
        if (binding == null || binding.getId() == null || binding.getId().isBlank()) {
            throw new IOException("Trigger binding ID is required");
        }
        if (binding.getType() == null) {
            throw new IOException("Trigger binding type is required: " + binding.getId());
        }
    }

    private static boolean sameBindings(Map<String, TriggerBinding> first, Map<String, TriggerBinding> second) {
        if (!first.keySet().equals(second.keySet())) {
            return false;
        }
        for (String id : first.keySet()) {
            TriggerBinding left = first.get(id);
            TriggerBinding right = second.get(id);
            if (!sameBinding(left, right)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameBinding(TriggerBinding first, TriggerBinding second) {
        if (first == null || second == null) {
            return first == second;
        }
        return Objects.equals(first.getId(), second.getId())
            && Objects.equals(first.getFlowId(), second.getFlowId())
            && first.getType() == second.getType()
            && Objects.equals(first.getContext(), second.getContext());
    }

    private void requireWritable() {
        if (persistenceState != PersistenceState.OPEN) {
            throw new IllegalStateException("Trigger persistence is quiesced; mutation rejected");
        }
    }

    private String bindingHashValues(Iterable<TriggerBinding> values) {
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (TriggerBinding binding : values) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", binding != null ? binding.getId() : null);
            value.put("flowId", binding != null ? binding.getFlowId() : null);
            value.put("type", binding != null && binding.getType() != null ? binding.getType().name() : null);
            value.put("context", binding != null ? binding.getContext() : null);
            canonical.add(value);
        }
        canonical.sort(Comparator.comparing(value -> Objects.toString(value.get("id"), "")));
        return CanonicalJson.sha256(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDING_HASH_DOMAIN, canonical);
    }

    private long nextBindingEpoch(long current) {
        return current == Long.MAX_VALUE ? Long.MAX_VALUE : current + 1L;
    }

    private void invalidateBindingObservation() {
        long generation = nextBindingEpoch(bindingObservationGeneration);
        bindingObservationGeneration = generation;
        bindingObservation = new BindingObservation(generation, bindingEpoch, bindingHashValues(bindings.values()), false);
    }

    private void publishBindingObservation() {
        long generation = nextBindingEpoch(bindingObservationGeneration);
        bindingObservationGeneration = generation;
        bindingObservation = new BindingObservation(generation, bindingEpoch, bindingHashValues(bindings.values()),
            persistenceState == PersistenceState.OPEN);
    }

    public record BindingMutationResult(boolean accepted, long epoch, String hash) {
        private static BindingMutationResult accepted(long epoch, String hash) {
            return new BindingMutationResult(true, epoch, hash);
        }

        private static BindingMutationResult rejected(long epoch, String hash) {
            return new BindingMutationResult(false, epoch, hash);
        }
    }

    public record BindingState(long epoch, String hash, List<TriggerBinding> bindings) {
        public BindingState {
            bindings = bindings == null ? List.of() : bindings.stream()
                .filter(Objects::nonNull)
                .map(binding -> new TriggerBinding(binding.getId(), binding.getFlowId(), binding.getType(), binding.getContext()))
                .toList();
        }
    }

    public record BindingObservation(long generation, long epoch, String hash, boolean available) {
        public BindingObservation {
            if (generation < 1L || epoch < 1L || hash == null) {
                throw new IllegalArgumentException("Trigger binding observation is invalid");
            }
        }
    }

    private TriggerBinding copyBinding(TriggerBinding binding) {
        return new TriggerBinding(binding.getId(), binding.getFlowId(), binding.getType(), binding.getContext());
    }
}
