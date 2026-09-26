package restudio.resync.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.Log;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.storage.AssetProjectMetadata;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetDelta;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.Deleted;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedState;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedProject;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.AssetTransactionCoordinator.Missing;
import restudio.resync.storage.AssetTransactionCoordinator.MutationView;
import restudio.resync.storage.AssetTransactionCoordinator.ProjectDelta;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.AssetTransactionCoordinator.StateConflictException;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionRequest;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionResult;
import restudio.resync.storage.StorageSafety;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class JsonAssetStore<T> implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long NO_EXPECTED_REVISION = -1L;
    private static final int MAX_READ_RETRIES = 3;
    private static final String AUXILIARY_HASH = "assetAuxiliaryHash";
    private static final String EMPTY_PAYLOAD_HASH = ResourcePayloadCodecs.json().hashPayload(Map.of()).canonicalText();

    public record AssetStamp(String type, String id, long revision, String mutationValue, String payloadHash, boolean deleted) {
        public AssetStamp {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(mutationValue, "mutationValue");
            Objects.requireNonNull(payloadHash, "payloadHash");
            if (mutationValue.isBlank() || mutationValue.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Asset lineage is invalid");
            }
            if (revision < 1L) {
                throw new IllegalArgumentException("Asset revision must be positive");
            }
            if (!payloadHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Asset payload hash must be lowercase hexadecimal");
            }
        }

        public AssetStamp(String type, String id, long revision, UUID mutationId, boolean deleted) {
            this(type, id, revision, mutationId.toString(), EMPTY_PAYLOAD_HASH, deleted);
        }

        public AssetStamp(String type, String id, long revision, UUID mutationId, String payloadHash, boolean deleted) {
            this(type, id, revision, mutationId.toString(), payloadHash, deleted);
        }

        public Optional<UUID> runtimeMutationId() {
            try {
                UUID parsed = UUID.fromString(mutationValue);
                return parsed.toString().equals(mutationValue) ? Optional.of(parsed) : Optional.empty();
            } catch (IllegalArgumentException failure) {
                return Optional.empty();
            }
        }

        public UUID mutationId() {
            return runtimeMutationId().orElseThrow(() -> new IllegalStateException("Asset lineage is not a runtime UUID: " + mutationValue));
        }
    }

    public record SnapshotValue<T>(String id, T value, AssetStamp stamp) {
        public SnapshotValue {
            Objects.requireNonNull(id, "Snapshot value ID is required");
            Objects.requireNonNull(value, "Snapshot value is required");
            Objects.requireNonNull(stamp, "Snapshot value stamp is required");
            if (!id.equals(stamp.id()) || stamp.deleted()) {
                throw new IllegalArgumentException("Snapshot value identity does not match its live stamp");
            }
        }
    }

    public record ReadSnapshot<T>(Path root, long rootSequence, String revision, List<SnapshotValue<T>> values) {
        public ReadSnapshot {
            root = Objects.requireNonNull(root, "Snapshot root is required").toAbsolutePath().normalize();
            if (rootSequence < 0L) {
                throw new IllegalArgumentException("Snapshot root sequence cannot be negative");
            }
            revision = Objects.requireNonNull(revision, "Snapshot revision is required");
            if (!revision.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Snapshot revision must be lowercase hexadecimal");
            }
            values = List.copyOf(Objects.requireNonNull(values, "Snapshot values are required"));
        }
    }

    public record ReplayOperation(String id, boolean deleted) {
        public ReplayOperation {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("Replay operation id is blank");
            }
        }
    }

    private record ProjectResourceEdit(String type, String id, String displayName, String path,
                                       Integer sortOrder, boolean remove) {
    }

    public record AggregateCreateResult(AssetStamp primaryStamp, String canonicalPayloadJson,
                                        AssetStamp projectMetadataStamp, String canonicalProjectMetadataJson,
                                        boolean replayed) {
        public AggregateCreateResult {
            primaryStamp = Objects.requireNonNull(primaryStamp, "primaryStamp");
            canonicalPayloadJson = Objects.requireNonNull(canonicalPayloadJson, "canonicalPayloadJson");
            projectMetadataStamp = Objects.requireNonNull(projectMetadataStamp, "projectMetadataStamp");
            canonicalProjectMetadataJson = Objects.requireNonNull(canonicalProjectMetadataJson,
                "canonicalProjectMetadataJson");
            if (primaryStamp.deleted() || projectMetadataStamp.deleted()) {
                throw new IllegalArgumentException("Aggregate create result must be live");
            }
        }
    }

    public static final class PreCommitConflictException extends RuntimeException {
        public PreCommitConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record PreparedCommit(TransactionResult transaction, List<ProjectDelta> projectDeltas,
                                  AssetDelta projectMetadataLineage) {
    }

    private record AggregatePrimary(JsonObject payload, Live state) {
    }

    private record CachedValue(String json, AssetStamp stamp) {
        private CachedValue {
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(stamp, "stamp");
        }
    }

    private record CoordinatorLineage(long revision, String mutationValue, boolean deleted) {
        private CoordinatorLineage {
            Objects.requireNonNull(mutationValue, "mutationValue");
        }
    }

    public static final class PreparedMutation {
        private final JsonAssetStore<?> owner;
        private final UUID mutationId;
        private final long snapshotSequence;
        private final ExpectedProject expectedProject;
        private final List<AssetDelta> assets;
        private final ProjectResourceEdit projectEdit;
        private final AssetKey primaryKey;
        private final long nextRevision;
        private final boolean deleted;
        private final Runnable committed;
        private final TransactionResult replay;

        private PreparedMutation(JsonAssetStore<?> owner, UUID mutationId, Snapshot snapshot, List<AssetDelta> assets,
                                 ProjectResourceEdit projectEdit, AssetKey primaryKey, long nextRevision, boolean deleted,
                                 Runnable committed, TransactionResult replay) {
            this.owner = owner;
            this.mutationId = mutationId;
            this.snapshotSequence = snapshot.rootSequence();
            this.expectedProject = snapshot.project();
            this.assets = List.copyOf(assets);
            this.projectEdit = projectEdit;
            this.primaryKey = primaryKey;
            this.nextRevision = nextRevision;
            this.deleted = deleted;
            this.committed = committed;
            this.replay = replay;
        }
    }
    public interface JsonReader<T> {
        T read(String json);
    }

    public interface JsonWriter<T> {
        String write(T value);
    }

    public interface IdExtractor<T> {
        String id(T value);
    }

    public interface FolderResolver<T> {
        String folder(T value);
    }

    @FunctionalInterface
    public interface PayloadMerger<T> {
        JsonObject merge(T value, JsonObject existing, JsonObject serialized);
    }

    @FunctionalInterface
    public interface ProjectMetadataLineageWriter {
        AssetDelta write(Snapshot snapshot, List<ProjectDelta> projectDeltas, UUID mutationId) throws IOException;
    }

    @FunctionalInterface
    public interface MutationLeaseProvider {
        MutationLease acquire();
    }

    public interface MutationLease extends AutoCloseable {
        @Override
        void close();
    }

    public static JsonObject mergePayload(JsonObject existing, JsonObject serialized, Collection<String> ownedFields) {
        return mergePayload(existing, serialized, ownedFields, true);
    }

    public static boolean matchesLegacyMergedPayload(JsonObject existing, JsonObject serialized, JsonObject actual,
                                                     Collection<String> ownedFields) {
        return actual != null && mergePayload(existing, serialized, ownedFields, false).equals(actual);
    }

    private static JsonObject mergePayload(JsonObject existing, JsonObject serialized, Collection<String> ownedFields,
                                           boolean replaceTopLevelOwnedObjects) {
        Objects.requireNonNull(serialized, "serialized");
        Set<String> owned = ownedFields == null ? Set.of() : Set.copyOf(ownedFields);
        List<List<String>> ownedPaths = owned.stream()
            .filter(field -> field != null && !field.isBlank())
            .map(JsonAssetStore::pathParts)
            .toList();
        return mergeObject(existing, serialized, "", ownedPaths, replaceTopLevelOwnedObjects);
    }

    private static JsonObject mergeObject(JsonObject existing, JsonObject serialized, String path,
                                          List<List<String>> ownedPaths, boolean replaceTopLevelOwnedObjects) {
        JsonObject merged = existing == null ? new JsonObject() : existing.deepCopy();
        for (String field : ownedMissingFields(existing, serialized, path, ownedPaths)) {
            merged.remove(field);
        }
        for (String field : directOwnedFields(path, ownedPaths)) {
            if (!serialized.has(field)) {
                merged.remove(field);
            }
        }
        for (Map.Entry<String, JsonElement> entry : serialized.entrySet()) {
            String childPath = path.isBlank() ? entry.getKey() : path + "." + entry.getKey();
            JsonElement previous = existing != null ? existing.get(entry.getKey()) : null;
            merged.add(entry.getKey(), mergeElement(previous, entry.getValue(), childPath, ownedPaths,
                replaceTopLevelOwnedObjects));
        }
        return merged;
    }

    private static JsonElement mergeElement(JsonElement existing, JsonElement serialized, String path,
                                            List<List<String>> ownedPaths, boolean replaceTopLevelOwnedObjects) {
        if (serialized == null || serialized.isJsonNull()) {
            return serialized == null ? JsonNull.INSTANCE : serialized.deepCopy();
        }
        if (serialized.isJsonObject()) {
            List<String> actualPath = pathParts(path);
            if (isFullyOwnedPath(actualPath, ownedPaths, replaceTopLevelOwnedObjects)) {
                return serialized.deepCopy();
            }
            return mergeObject(existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : null,
                serialized.getAsJsonObject(), path, ownedPaths, replaceTopLevelOwnedObjects);
        }
        if (!serialized.isJsonArray() || existing == null || !existing.isJsonArray()) {
            return serialized.deepCopy();
        }
        JsonArray merged = new JsonArray();
        JsonArray oldArray = existing.getAsJsonArray();
        List<JsonElement> oldValues = oldArray.asList();
        Set<Integer> matched = new LinkedHashSet<>();
        List<JsonElement> newValues = serialized.getAsJsonArray().asList();
        for (int index = 0; index < newValues.size(); index++) {
            JsonElement current = newValues.get(index);
            int oldIndex = matchingArrayIndex(current, oldValues, matched, index);
            JsonElement previous = oldIndex >= 0 ? oldValues.get(oldIndex) : null;
            if (oldIndex >= 0) {
                matched.add(oldIndex);
            }
            merged.add(mergeElement(previous, current, path + "[]", ownedPaths, replaceTopLevelOwnedObjects));
        }
        return merged;
    }

    private static int matchingArrayIndex(JsonElement current, List<JsonElement> oldValues, Set<Integer> matched, int fallback) {
        if (current != null && current.isJsonObject()) {
            JsonObject object = current.getAsJsonObject();
            for (String identity : List.of("id", "key", "name", "uuid", "type")) {
                if (!object.has(identity) || object.get(identity).isJsonNull()) {
                    continue;
                }
                String value = object.get(identity).toString();
                for (int index = 0; index < oldValues.size(); index++) {
                    JsonElement previous = oldValues.get(index);
                    if (matched.contains(index) || previous == null || !previous.isJsonObject()) {
                        continue;
                    }
                    JsonObject previousObject = previous.getAsJsonObject();
                    if (previousObject.has(identity) && value.equals(previousObject.get(identity).toString())) {
                        return index;
                    }
                }
            }
        }
        return fallback < oldValues.size() && !matched.contains(fallback) ? fallback : -1;
    }

    private static Set<String> directOwnedFields(String path, List<List<String>> ownedPaths) {
        Set<String> direct = new LinkedHashSet<>();
        List<String> actual = pathParts(path);
        for (List<String> candidate : ownedPaths) {
            if (candidate.size() != actual.size() + 1 || !matchesPath(candidate, actual)) {
                continue;
            }
            String field = candidate.getLast();
            if (!"*".equals(field)) {
                direct.add(field);
            }
        }
        return direct;
    }

    private static Set<String> ownedMissingFields(JsonObject existing, JsonObject serialized, String path,
                                                  List<List<String>> ownedPaths) {
        if (existing == null) {
            return Set.of();
        }
        Set<String> missing = new LinkedHashSet<>();
        List<String> actual = pathParts(path);
        for (String field : existing.keySet()) {
            if (serialized.has(field)) {
                continue;
            }
            List<String> child = new ArrayList<>(actual);
            child.add(field);
            if (ownedPaths.stream().anyMatch(candidate -> candidate.size() >= child.size()
                && matchesPath(candidate, child))) {
                missing.add(field);
            }
        }
        return missing;
    }

    private static boolean isFullyOwnedPath(List<String> actual, List<List<String>> ownedPaths,
                                            boolean replaceTopLevelOwnedObjects) {
        boolean exact = false;
        for (List<String> candidate : ownedPaths) {
            if (candidate.size() < actual.size()) {
                continue;
            }
            if (!matchesPath(candidate, actual)) {
                continue;
            }
            if (candidate.size() == actual.size()) {
                exact = true;
            }
        }
        if (!exact || !replaceTopLevelOwnedObjects && actual.size() <= 1) {
            return false;
        }
        return ownedPaths.stream().noneMatch(candidate -> candidate.size() > actual.size() && matchesPath(candidate, actual));
    }

    private static List<String> pathParts(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String raw : path.split("\\.")) {
            if (raw.endsWith("[]")) {
                parts.add(raw.substring(0, raw.length() - 2));
                parts.add("*");
            } else if (raw.chars().allMatch(Character::isDigit)) {
                parts.add("*");
            } else {
                parts.add(raw);
            }
        }
        return parts;
    }

    private static boolean matchesPath(List<String> candidate, List<String> actual) {
        for (int index = 0; index < actual.size(); index++) {
            String expected = candidate.get(index);
            if (!"*".equals(expected) && !expected.equals(actual.get(index))) {
                return false;
            }
        }
        return true;
    }

    private final Path assetsRoot;
    private final String typeId;
    private final String defaultFolder;
    private final JsonReader<T> reader;
    private final JsonWriter<T> writer;
    private final IdExtractor<T> idExtractor;
    private final FolderResolver<T> folderResolver;
    private final PayloadMerger<T> payloadMerger;
    private final AssetTransactionCoordinator coordinator;
    private final AssetTransactionCoordinator.ListenerRegistration coordinatorListener;
    private final BooleanSupplier mutationAdmission;
    private final MutationLeaseProvider mutationLeaseProvider;
    private final ProjectMetadataLineageWriter projectMetadataLineageWriter;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();
    private final Object cacheLock = new Object();
    private final AtomicLong cacheGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public JsonAssetStore(Path assetsRoot, Path legacyDirectory, String typeId, String defaultFolder, JsonReader<T> reader, JsonWriter<T> writer,
                          IdExtractor<T> idExtractor, FolderResolver<T> folderResolver, LegacyRuntimeActivationGate legacyRuntimeGate,
                          AssetTransactionCoordinator coordinator, BooleanSupplier mutationAdmission) {
        this(assetsRoot, legacyDirectory, typeId, defaultFolder, reader, writer, idExtractor, folderResolver, legacyRuntimeGate,
            coordinator, mutationAdmission, () -> {
                if (!mutationAdmission.getAsBoolean()) {
                    throw new IllegalStateException("JSON resource persistence is QUIESCED; mutation rejected");
                }
                return () -> {
                };
            });
    }

    public JsonAssetStore(Path assetsRoot, Path legacyDirectory, String typeId, String defaultFolder, JsonReader<T> reader, JsonWriter<T> writer,
                          IdExtractor<T> idExtractor, FolderResolver<T> folderResolver, LegacyRuntimeActivationGate legacyRuntimeGate,
                          AssetTransactionCoordinator coordinator, BooleanSupplier mutationAdmission,
                          MutationLeaseProvider mutationLeaseProvider) {
        this(assetsRoot, legacyDirectory, typeId, defaultFolder, reader, writer, idExtractor, folderResolver, legacyRuntimeGate,
            coordinator, mutationAdmission, mutationLeaseProvider,
            (value, existing, serialized) -> mergePayload(existing, serialized, serialized.keySet()));
    }

    public JsonAssetStore(Path assetsRoot, Path legacyDirectory, String typeId, String defaultFolder, JsonReader<T> reader, JsonWriter<T> writer,
                          IdExtractor<T> idExtractor, FolderResolver<T> folderResolver, LegacyRuntimeActivationGate legacyRuntimeGate,
                          AssetTransactionCoordinator coordinator, BooleanSupplier mutationAdmission,
                          MutationLeaseProvider mutationLeaseProvider, PayloadMerger<T> payloadMerger) {
        this(assetsRoot, legacyDirectory, typeId, defaultFolder, reader, writer, idExtractor, folderResolver, legacyRuntimeGate,
            coordinator, mutationAdmission, mutationLeaseProvider, payloadMerger, null);
    }

    public JsonAssetStore(Path assetsRoot, Path legacyDirectory, String typeId, String defaultFolder, JsonReader<T> reader, JsonWriter<T> writer,
                          IdExtractor<T> idExtractor, FolderResolver<T> folderResolver, LegacyRuntimeActivationGate legacyRuntimeGate,
                          AssetTransactionCoordinator coordinator, BooleanSupplier mutationAdmission,
                          MutationLeaseProvider mutationLeaseProvider, PayloadMerger<T> payloadMerger,
                          ProjectMetadataLineageWriter projectMetadataLineageWriter) {
        this.assetsRoot = Objects.requireNonNull(assetsRoot, "assetsRoot").toAbsolutePath().normalize();
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.defaultFolder = defaultFolder == null ? "" : defaultFolder;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.idExtractor = Objects.requireNonNull(idExtractor, "idExtractor");
        this.folderResolver = folderResolver;
        this.payloadMerger = Objects.requireNonNull(payloadMerger, "payloadMerger");
        Objects.requireNonNull(legacyRuntimeGate, "legacyRuntimeGate");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.mutationAdmission = Objects.requireNonNull(mutationAdmission, "mutationAdmission");
        this.mutationLeaseProvider = Objects.requireNonNull(mutationLeaseProvider, "mutationLeaseProvider");
        this.projectMetadataLineageWriter = projectMetadataLineageWriter;
        if (!this.coordinator.canonicalRoot().equals(this.assetsRoot)) {
            throw new IllegalArgumentException("JSON Asset Coordinator Root Does Not Match Asset Store Root");
        }
        this.coordinatorListener = this.coordinator.addListener(result -> {
            if (result.states().keySet().stream().anyMatch(this::ownsCoordinatorKey)) {
                clearCache();
            }
        });
    }

    public T get(String id) {
        requireOpen();
        String safeId = safeId(id, "load");
        if (safeId == null) {
            return null;
        }
        IOException lastFailure = null;
        for (int attempt = 0; attempt < MAX_READ_RETRIES; attempt++) {
            long generation;
            AssetStamp before;
            String json;
            boolean lineageHit = false;
            try (MutationLease ignored = acquireMutationLease()) {
                generation = cacheGeneration.get();
                Snapshot snapshot = coordinator.read(current -> current);
                CoordinatorLineage lineage = coordinatorLineage(safeId, snapshot);
                CachedValue cached = cacheGet(safeId);
                if (cached != null && matches(cached.stamp(), lineage)) {
                    before = cached.stamp();
                    json = cached.json();
                    lineageHit = true;
                } else {
                    if (cached != null) {
                        synchronized (cacheLock) {
                            if (generation != cacheGeneration.get()) {
                                continue;
                            }
                            cache.remove(safeId, cached);
                        }
                    }
                    before = currentStamp(safeId, snapshot);
                    if (before == null) {
                        synchronized (cacheLock) {
                            if (generation != cacheGeneration.get()) {
                                continue;
                            }
                            cache.remove(safeId);
                            return null;
                        }
                    }
                    json = before.deleted() ? null : readCurrentJson(safeId, snapshot);
                }
            } catch (IOException exception) {
                lastFailure = exception;
                continue;
            }
            T value = readValue(json);
            String valueId = value == null ? null : safeId(idExtractor.id(value), "load");
            try (MutationLease ignored = acquireMutationLease()) {
                synchronized (cacheLock) {
                    if (generation != cacheGeneration.get()
                        || (!lineageHit && !matches(before, coordinatorLineage(safeId, coordinator.read(current -> current))))) {
                        continue;
                    }
                    if (value == null) {
                        cache.remove(safeId);
                        return null;
                    }
                    cache.put(valueId != null ? valueId : safeId, new CachedValue(json, before));
                    return value;
                }
            }
        }
        cacheRemove(safeId);
        if (lastFailure != null) {
            Log.warn("Failed to load " + typeId + ": " + safeId + " - " + lastFailure.getMessage());
        } else {
            Log.warn("Failed to load " + typeId + ": " + safeId + " because its state changed during the read");
        }
        return null;
    }

    public void preloadAll(Consumer<T> consumer) {
        requireOpen();
        Objects.requireNonNull(consumer, "consumer");
        try (MutationLease ignored = acquireMutationLease()) {
            coordinator.read(snapshot -> {
                snapshot.states().entrySet().stream()
                    .filter(entry -> entry.getKey().type().equals(typeId) && entry.getValue() instanceof Live)
                    .map(entry -> entry.getKey().id())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(id -> {
                        T value = preloadFromSnapshot(id, snapshot);
                        if (value != null) {
                            consumer.accept(value);
                        }
                    });
                return null;
            });
        }
    }

    private T preloadFromSnapshot(String safeId, Snapshot snapshot) {
        long generation = cacheGeneration.get();
        try {
            AssetStamp before = currentStamp(safeId, snapshot);
            if (before == null) {
                synchronized (cacheLock) {
                    if (generation == cacheGeneration.get()) {
                        cache.remove(safeId);
                    }
                }
                return null;
            }
            CachedValue cached = cacheGet(safeId);
            if (cached != null && !before.equals(cached.stamp())) {
                synchronized (cacheLock) {
                    if (generation != cacheGeneration.get()) {
                        return null;
                    }
                    cache.remove(safeId, cached);
                }
                cached = null;
            }
            String json = cached == null ? readCurrentJson(safeId, snapshot) : cached.json();
            T value = readValue(json);
            String valueId = value == null ? null : safeId(idExtractor.id(value), "load");
            synchronized (cacheLock) {
                if (generation != cacheGeneration.get() || !Objects.equals(before, currentStamp(safeId, snapshot))) {
                    return null;
                }
                if (value == null) {
                    cache.remove(safeId);
                    return null;
                }
                cache.put(valueId != null ? valueId : safeId, new CachedValue(json, before));
                return value;
            }
        } catch (IOException exception) {
            cacheRemove(safeId);
            Log.warn("Failed to load " + typeId + ": " + safeId + " - " + exception.getMessage());
            return null;
        }
    }

    public T readUncached(String id) {
        requireOpen();
        String safeId = safeId(id, "reload");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        String json;
        AssetStamp before;
        long generation;
        try (MutationLease ignored = acquireMutationLease()) {
            generation = cacheGeneration.get();
            before = currentStamp(safeId);
            json = readCurrentJson(safeId);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reload " + typeId + ": " + safeId, exception);
        }
        T value = readValue(json);
        try (MutationLease ignored = acquireMutationLease()) {
            if (generation != cacheGeneration.get() || !Objects.equals(before, currentStamp(safeId))) {
                throw new IllegalStateException("JSON resource changed during uncached read: " + safeId);
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to validate " + typeId + " read: " + safeId, exception);
        }
    }

    public void publishValidated(String id, T value, AssetStamp expected) {
        requireOpen();
        String safeId = safeId(id, "reload");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        long generation = cacheGeneration.get();
        String valueId = value == null ? null : safeId(idExtractor.id(value), "reload");
        try (MutationLease ignored = acquireMutationLease()) {
            synchronized (cacheLock) {
                if (generation != cacheGeneration.get()) {
                    throw new IllegalStateException("JSON resource changed before cache publication: " + safeId);
                }
                AssetStamp actual;
                try {
                    actual = currentStamp(safeId);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to validate " + typeId + " cache publication: " + safeId,
                        exception);
                }
                if (!Objects.equals(expected, actual)) {
                    throw new IllegalStateException("JSON resource changed before cache publication: " + safeId);
                }
                if (value == null) {
                    cache.remove(safeId);
                    return;
                }
                if (!safeId.equals(valueId)) {
                    throw new IllegalStateException("JSON resource identity does not match its typed key: " + safeId);
                }
                if (actual == null || actual.deleted()) {
                    throw new IllegalStateException("Cannot publish a live JSON resource without a live durable state: " + safeId);
                }
                String durableJson;
                try {
                    durableJson = readCurrentJson(safeId);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to read durable " + typeId + " cache payload: " + safeId,
                        exception);
                }
                AssetStamp confirmed;
                try {
                    confirmed = currentStamp(safeId);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to confirm " + typeId + " cache publication: " + safeId,
                        exception);
                }
                if (generation != cacheGeneration.get() || !Objects.equals(expected, confirmed)) {
                    throw new IllegalStateException("JSON resource changed before cache publication: " + safeId);
                }
                if (durableJson == null) {
                    throw new IllegalStateException("Live JSON resource has no durable payload: " + safeId);
                }
                cache.put(safeId, new CachedValue(durableJson, confirmed));
            }
        }
    }

    private String readCurrentJson(String safeId) throws IOException {
        Snapshot snapshot = coordinator.read(current -> current);
        return readCurrentJson(safeId, snapshot);
    }

    private String readCurrentJson(String safeId, Snapshot snapshot) throws IOException {
        AssetKey key = assetKey(safeId);
        ExpectedState state = snapshot.state(key).orElse(null);
        if (!(state instanceof Live live)) {
            return null;
        }
        Path file = snapshot.path(key).orElseThrow(() -> new IOException("Live JSON resource has no coordinator path: " + safeId));
        byte[] bytes = Files.readAllBytes(file);
        if (!live.hash().equals(StorageSafety.sha256(bytes))) {
            throw new IOException("Live JSON resource does not match coordinator state: " + safeId);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private T readValue(String json) {
        return json == null ? null : reader.read(json);
    }

    public void save(T value) {
        saveMutation(value, Map.of(), UUID.randomUUID(), NO_EXPECTED_REVISION);
    }

    public void save(T value, Map<Path, byte[]> binaryWrites) {
        saveMutation(value, binaryWrites, UUID.randomUUID(), NO_EXPECTED_REVISION);
    }

    public void save(T value, UUID mutationId, long expectedRevision) {
        requireExpectedRevision(expectedRevision);
        saveMutation(value, Map.of(), mutationId, expectedRevision);
    }

    public void save(T value, long expectedRevision, UUID mutationId) {
        save(value, mutationId, expectedRevision);
    }

    public void save(T value, Map<Path, byte[]> binaryWrites, UUID mutationId, long expectedRevision) {
        requireExpectedRevision(expectedRevision);
        saveMutation(value, binaryWrites, mutationId, expectedRevision);
    }

    public void save(T value, UUID mutationId, long expectedRevision, Map<Path, byte[]> binaryWrites) {
        save(value, binaryWrites, mutationId, expectedRevision);
    }

    public void save(T value, long expectedRevision, UUID mutationId, Map<Path, byte[]> binaryWrites) {
        save(value, binaryWrites, mutationId, expectedRevision);
    }

    public AggregateCreateResult create(T value, Map<Path, byte[]> binaryWrites, UUID mutationId,
                                        long expectedRevision, ResourcePresentationIntent presentation) {
        requireOpen();
        requireMutationId(mutationId);
        requireExpectedRevision(expectedRevision);
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Aggregate create expected revision must be nonnegative");
        }
        if (projectMetadataLineageWriter == null) {
            throw new IllegalStateException("Aggregate create project metadata lineage is unavailable");
        }
        Objects.requireNonNull(presentation, "presentation");
        if (value == null) {
            throw new IllegalArgumentException("Invalid " + typeId);
        }
        String safeId = safeId(idExtractor.id(value), "create");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        Map<Path, byte[]> writes = binaryWrites == null ? Map.of() : binaryWrites;
        try (MutationLease ignored = acquireMutationLease()) {
            Snapshot snapshot = coordinatorSnapshot();
            ProjectResourceEdit edit = presentationEdit(safeId, presentation);
            PreparedMutation plan = prepareSave(snapshot, value, writes, mutationId, expectedRevision, edit, true, null);
            PreparedCommit committed = commitPreparedInternal(mutationId, snapshot, List.of(plan), true);
            AssetProjectMetadata projectMetadata = snapshot.metadata().apply(committed.projectDeltas().stream()
                .map(delta -> new AssetProjectMetadata.Delta(delta.path(), delta.value(), delta.remove()))
                .toList());
            long expectedProjectRevision = snapshot.project().revision() + (committed.projectDeltas().isEmpty() ? 0L : 1L);
            if (committed.transaction().project().revision() != expectedProjectRevision
                || !committed.transaction().project().hash().equals(projectMetadata.hash())) {
                throw new IllegalStateException("Aggregate create project metadata result does not match its transaction");
            }
            AggregatePrimary primary = aggregatePrimary(snapshot, committed, plan, safeId, mutationId);
            JsonObject primaryPayload = primary.payload();
            AssetStamp primaryStamp = new AssetStamp(typeId, safeId, primary.state().revision(), mutationId.toString(),
                canonicalPayloadHash(GSON.toJson(primaryPayload)), false);
            AssetStamp metadataStamp = projectMetadataStamp(snapshot, committed, projectMetadata, mutationId);
            return new AggregateCreateResult(primaryStamp, GSON.toJson(primaryPayload), metadataStamp,
                projectMetadata.serializedJson(), committed.transaction().replay());
        } catch (IOException exception) {
            throw coordinatedFailure("create", safeId, exception);
        }
    }

    private void saveMutation(T value, Map<Path, byte[]> binaryWrites, UUID mutationId, long expectedRevision) {
        requireOpen();
        requireMutationId(mutationId);
        if (value == null) {
            throw new IllegalArgumentException("Invalid " + typeId);
        }
        String safeId = safeId(idExtractor.id(value), "save");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        Map<Path, byte[]> safeBinaryWrites = binaryWrites != null ? binaryWrites : Map.of();
        try (MutationLease ignored = acquireMutationLease()) {
            saveCoordinated(value, safeId, safeBinaryWrites, mutationId, expectedRevision);
        }
    }

    public void delete(String id) {
        deleteMutation(id, UUID.randomUUID(), NO_EXPECTED_REVISION);
    }

    public void delete(String id, UUID mutationId, long expectedRevision) {
        requireExpectedRevision(expectedRevision);
        deleteMutation(id, mutationId, expectedRevision);
    }

    public void delete(String id, long expectedRevision, UUID mutationId) {
        delete(id, mutationId, expectedRevision);
    }

    private void deleteMutation(String id, UUID mutationId, long expectedRevision) {
        requireOpen();
        requireMutationId(mutationId);
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        try (MutationLease ignored = acquireMutationLease()) {
            deleteCoordinated(safeId, mutationId, expectedRevision);
        }
    }

    private String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    public List<String> listIds() {
        requireOpen();
        Snapshot snapshot = coordinator.read(current -> current);
        return snapshot.states().entrySet().stream()
            .filter(entry -> entry.getKey().type().equals(typeId) && entry.getValue() instanceof Live)
            .map(entry -> entry.getKey().id())
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public ReadSnapshot<T> readSnapshot() {
        requireOpen();
        try (MutationLease ignored = acquireMutationLease()) {
            return coordinator.read(snapshot -> {
                List<String> ids = snapshot.states().entrySet().stream()
                    .filter(entry -> entry.getKey().type().equals(typeId) && entry.getValue() instanceof Live)
                    .map(entry -> entry.getKey().id())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
                List<SnapshotValue<T>> values = new ArrayList<>(ids.size());
                StringBuilder revision = new StringBuilder(typeId).append('\n');
                for (String id : ids) {
                    try {
                        AssetStamp stamp = currentStamp(id, snapshot);
                        if (stamp == null || stamp.deleted()) {
                            throw new IOException("Live JSON resource has no live stamp: " + id);
                        }
                        String json = readCurrentJson(id, snapshot);
                        T value = readValue(json);
                        String valueId = value == null ? null : safeId(idExtractor.id(value), "snapshot");
                        if (value == null || !id.equals(valueId)) {
                            throw new IOException("Live JSON resource snapshot identity mismatch: " + id);
                        }
                        values.add(new SnapshotValue<>(id, value, stamp));
                        revision.append(id).append('\n')
                            .append(stamp.revision()).append('\n')
                            .append(stamp.mutationValue()).append('\n')
                            .append(stamp.payloadHash()).append('\n');
                    } catch (IOException exception) {
                        throw new IllegalStateException("Failed to capture " + typeId + " snapshot: " + id, exception);
                    }
                }
                String fingerprint = StorageSafety.sha256(revision.toString().getBytes(StandardCharsets.UTF_8));
                return new ReadSnapshot<>(assetsRoot, snapshot.rootSequence(), fingerprint, values);
            });
        }
    }

    public void migrateLegacyAssets() {
        requireOpen();
        throw new IllegalStateException("Legacy JSON asset migration requires verified asset coordinator adoption");
    }

    public void clearCache() {
        synchronized (cacheLock) {
            cacheGeneration.incrementAndGet();
            cache.clear();
        }
    }

    public long cacheGeneration() {
        return cacheGeneration.get();
    }

    public void healthCheck() throws IOException {
        requireOpen();
        coordinator.healthCheck();
        healthCheckLocal();
    }

    public void healthCheckLocal() throws IOException {
        healthCheckLocal(JsonAssetInventory.scan(assetsRoot));
    }

    public void healthCheckLocal(JsonAssetInventory inventory) throws IOException {
        requireOpen();
        JsonAssetInventory requiredInventory = Objects.requireNonNull(inventory, "inventory");
        if (!assetsRoot.equals(requiredInventory.root())) {
            throw new IOException("JSON Asset Inventory Root Does Not Match Store Root: " + requiredInventory.root());
        }
        Map<String, List<Path>> ownedAssets = collectOwnedAssetFiles(requiredInventory);
        validateDefaultFolderFiles(requiredInventory, ownedAssets);
        for (Map.Entry<String, List<Path>> entry : ownedAssets.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new IOException("Duplicate " + typeId + " asset identity: " + entry.getKey());
            }
            validateCanonicalFile(entry.getValue().getFirst(), entry.getKey());
        }
        validateTombstones(requiredInventory);
    }

    public T reload(String id) {
        return reload(id, null);
    }

    public T reload(String id, Consumer<T> validator) {
        requireOpen();
        String safeId = safeId(id, "reload");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        for (int attempt = 0; attempt < MAX_READ_RETRIES; attempt++) {
            CachedValue previous;
            String json;
            AssetStamp before;
            long generation;
            try (MutationLease ignored = acquireMutationLease()) {
                generation = cacheGeneration.get();
                previous = cacheGet(safeId);
                before = currentStamp(safeId);
                cacheRemove(safeId);
                json = before == null ? null : readCurrentJson(safeId);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to reload " + typeId + ": " + safeId, exception);
            }
            T value = readValue(json);
            try {
                if (value != null && validator != null) {
                    validator.accept(value);
                }
            } catch (RuntimeException exception) {
                restoreReloadCache(safeId, previous, generation, before);
                throw exception;
            }
            String valueId;
            try {
                valueId = value == null ? null : safeId(idExtractor.id(value), "reload");
            } catch (RuntimeException failure) {
                restoreReloadCache(safeId, previous, generation, before);
                throw failure;
            }
            try (MutationLease ignored = acquireMutationLease()) {
                synchronized (cacheLock) {
                    if (generation != cacheGeneration.get() || !Objects.equals(before, currentStamp(safeId))) {
                        continue;
                    }
                    if (value == null) {
                        cache.remove(safeId);
                        return null;
                    }
                    if (json == null) {
                        throw new IllegalStateException("Validated JSON resource has no durable payload: " + safeId);
                    }
                    cache.put(valueId != null ? valueId : safeId, new CachedValue(json, before));
                    return value;
                }
            } catch (IOException exception) {
                restoreReloadCache(safeId, previous, generation, before);
                throw new IllegalStateException("Failed to validate " + typeId + " reload: " + safeId, exception);
            }
        }
        throw new IllegalStateException("JSON resource changed during reload: " + safeId);
    }

    private void restoreReloadCache(String safeId, CachedValue previous, long generation, AssetStamp expected) {
        try (MutationLease ignored = acquireMutationLease()) {
            synchronized (cacheLock) {
                if (generation != cacheGeneration.get() || !Objects.equals(expected, currentStamp(safeId))) {
                    return;
                }
                if (previous == null || expected == null) {
                    cache.remove(safeId);
                } else {
                    cache.put(safeId, previous);
                }
            }
        } catch (IOException exception) {
            cacheRemove(safeId);
        }
    }

    public Path findAssetFile(String id) {
        requireOpen();
        String safeId = safeId(id, "search");
        if (safeId == null) {
            return null;
        }
        Snapshot snapshot = coordinator.read(current -> current);
        AssetKey key = assetKey(safeId);
        return snapshot.state(key).orElse(null) instanceof Live ? snapshot.path(key).orElse(null) : null;
    }

    public AssetStamp readStamp(String id) {
        requireOpen();
        String safeId = safeId(id, "stamp");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        try {
            Snapshot snapshot = coordinator.read(current -> current);
            CoordinatorLineage lineage = coordinatorLineage(safeId, snapshot);
            CachedValue cached = cacheGet(safeId);
            if (cached != null && matches(cached.stamp(), lineage)) {
                return cached.stamp();
            }
            return currentStamp(safeId, snapshot);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) exception;
            }
            throw new IllegalStateException("Failed to read " + typeId + " stamp: " + safeId, exception);
        }
    }

    public AssetStamp readIdentity(String id) {
        return readStamp(id);
    }

    public AssetStamp stamp(String id) {
        return readStamp(id);
    }

    public Optional<AssetStamp> findStamp(String id) {
        return Optional.ofNullable(readStamp(id));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            coordinatorListener.close();
            clearCache();
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("JSON asset store is closed: " + typeId);
        }
    }

    private void saveCoordinated(T value, String safeId, Map<Path, byte[]> binaryWrites, UUID mutationId, long expectedRevision) {
        Snapshot snapshot = coordinatorSnapshot();
        commitPrepared(mutationId, snapshot, List.of(prepareSave(snapshot, value, binaryWrites, mutationId, expectedRevision)));
    }

    private void deleteCoordinated(String safeId, UUID mutationId, long expectedRevision) {
        Snapshot snapshot = coordinatorSnapshot();
        commitPrepared(mutationId, snapshot, List.of(prepareDelete(snapshot, safeId, mutationId, expectedRevision)));
    }

    public Snapshot coordinatorSnapshot() {
        requireOpen();
        return coordinator.read(current -> current);
    }

    public List<ReplayOperation> replayOperations(UUID mutationId) throws IOException {
        requireOpen();
        requireMutationId(mutationId);
        Optional<MutationView> history = coordinator.mutation(mutationId);
        if (history.isEmpty()) {
            return List.of();
        }
        JsonElement assets = history.get().intent().get("assets");
        if (assets == null || !assets.isJsonArray()) {
            throw new IOException("Persisted JSON resource mutation intent has no assets");
        }
        Map<String, ReplayOperation> operations = new LinkedHashMap<>();
        for (JsonElement element : assets.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IOException("Persisted JSON resource mutation intent contains a non-object asset");
            }
            JsonObject asset = element.getAsJsonObject();
            if (!typeId.equals(text(asset, "type"))) {
                continue;
            }
            String id = text(asset, "id");
            try {
                new AssetKey(typeId, id);
            } catch (RuntimeException failure) {
                throw new IOException("Persisted JSON resource mutation intent has an invalid identity", failure);
            }
            String operation = text(asset, "operation");
            ReplayOperation replay = switch (operation) {
                case "WRITE" -> new ReplayOperation(id, false);
                case "DELETE" -> new ReplayOperation(id, true);
                default -> throw new IOException("Persisted JSON resource mutation intent has an unsupported operation: " + operation);
            };
            if (operations.putIfAbsent(id, replay) != null) {
                throw new IOException("Persisted JSON resource mutation intent repeats an identity: " + id);
            }
        }
        return operations.values().stream().sorted((left, right) -> left.id().compareTo(right.id())).toList();
    }

    public PreparedMutation prepareSave(Snapshot snapshot, T value, Map<Path, byte[]> binaryWrites,
                                        UUID mutationId, long expectedRevision) {
        return prepareSave(snapshot, value, binaryWrites, mutationId, expectedRevision, null, false, null);
    }

    public PreparedMutation prepareSave(Snapshot snapshot, T value, Map<Path, byte[]> binaryWrites,
                                        UUID mutationId, long expectedRevision, String expectedPayloadHash) {
        return prepareSave(snapshot, value, binaryWrites, mutationId, expectedRevision, null, false,
            Objects.requireNonNull(expectedPayloadHash, "Expected payload hash is required"));
    }

    private PreparedMutation prepareSave(Snapshot snapshot, T value, Map<Path, byte[]> binaryWrites,
                                         UUID mutationId, long expectedRevision, ProjectResourceEdit requestedEdit,
                                         boolean createOnly, String expectedPayloadHash) {
        requireOpen();
        requireMutationId(mutationId);
        requireExpectedRevision(expectedRevision);
        Objects.requireNonNull(snapshot, "snapshot");
        if (!mutationAdmission.getAsBoolean()) {
            throw new IllegalStateException("JSON resource persistence is QUIESCED; mutation rejected");
        }
        if (value == null) {
            throw new IllegalArgumentException("Invalid " + typeId);
        }
        String safeId = safeId(idExtractor.id(value), "save");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        Map<Path, byte[]> writes = binaryWrites == null ? Map.of() : binaryWrites;
        try {
            AssetKey key = assetKey(safeId);
            ExpectedState current = snapshot.state(key).orElse(Missing.INSTANCE);
            if (createOnly && current instanceof Live
                && !snapshot.mutationValue(key).filter(mutationId.toString()::equals).isPresent()) {
                throw new IllegalStateException("Aggregate create target already exists: " + typeId + ':' + safeId);
            }
            Optional<MutationView> history = coordinator.mutation(mutationId);
            if (isCurrentMutation(snapshot, key, mutationId)) {
                requireSaveReplay(snapshot, key, value, safeId, writes, mutationId, expectedRevision, requestedEdit);
                TransactionResult replay = history.orElseThrow().result();
                return new PreparedMutation(this, mutationId, snapshot, List.of(), requestedEdit, key, current.revision(), false,
                    this::clearCache, replay);
            }
            if (history.isPresent()) {
                TransactionResult replay = requireHistoricalSaveReplay(history.get(), key, value, writes, mutationId,
                    expectedRevision, requestedEdit);
                return new PreparedMutation(this, mutationId, snapshot, List.of(), requestedEdit, key,
                    replay.states().get(key).revision(), false, this::clearCache, replay);
            }
            checkExpectedRevision(safeId, expectedRevision, current.revision());
            if (current instanceof Deleted) {
                requireDeletedEvidence(snapshot, key, safeId);
            }
            long nextRevision = Math.addExact(current.revision(), 1L);
            JsonObject existing = current instanceof Live ? existingPayload(snapshot, key, safeId) : new JsonObject();
            String json = assetJson(value, existing, nextRevision, mutationId, auxiliaryWritesHash(writes));
            if (expectedPayloadHash != null && !expectedPayloadHash.equals(canonicalPayloadHash(json))) {
                throw new IllegalArgumentException("The resource changed during validation. Save it again with the current editor.");
            }
            Path target = requestedEdit != null && !(current instanceof Live)
                ? presentationAssetPath(safeId, requestedEdit.path())
                : snapshot.path(key).orElseGet(() -> defaultAssetPath(safeId, value));
            if (requestedEdit != null && !target.equals(presentationAssetPath(safeId, requestedEdit.path()))) {
                throw new IllegalStateException("Aggregate create path differs from existing coordinated state: " + typeId + ':' + safeId);
            }
            List<AssetDelta> assets = new ArrayList<>();
            assets.add(AssetDelta.write(key, target, current, json.getBytes(StandardCharsets.UTF_8)));
            addTombstoneRetirement(snapshot, safeId, assets);
            addBinaryWrites(snapshot, writes, assets);
            addIntentWrite(snapshot, safeId, saveIntentBytes(value, writes, requestedEdit), assets);
            String relative = assetsRoot.relativize(target).toString().replace('\\', '/');
            ProjectResourceEdit edit = requestedEdit != null ? requestedEdit
                : new ProjectResourceEdit(typeId, safeId, null, relative, null, false);
            return new PreparedMutation(this, mutationId, snapshot, assets, edit, key, nextRevision, false,
                this::clearCache, null);
        } catch (IOException exception) {
            throw coordinatedFailure("prepare save", safeId, exception);
        }
    }

    public PreparedMutation prepareDelete(Snapshot snapshot, String id, UUID mutationId, long expectedRevision) {
        requireOpen();
        requireMutationId(mutationId);
        requireExpectedRevision(expectedRevision);
        Objects.requireNonNull(snapshot, "snapshot");
        if (!mutationAdmission.getAsBoolean()) {
            throw new IllegalStateException("JSON resource persistence is QUIESCED; mutation rejected");
        }
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        try {
            AssetKey key = assetKey(safeId);
            ExpectedState current = snapshot.state(key).orElse(Missing.INSTANCE);
            Optional<MutationView> history = coordinator.mutation(mutationId);
            if (isCurrentMutation(snapshot, key, mutationId)) {
                requireDeleteReplay(snapshot, key, safeId, mutationId, expectedRevision);
                TransactionResult replay = history.orElseThrow().result();
                    return new PreparedMutation(this, mutationId, snapshot, List.of(), null, key, current.revision(), true,
                    () -> {
                        cacheRemove(safeId);
                    }, replay);
            }
            if (history.isPresent()) {
                TransactionResult replay = requireHistoricalDeleteReplay(history.get(), key, safeId, expectedRevision);
                return new PreparedMutation(this, mutationId, snapshot, List.of(), null, key,
                    replay.states().get(key).revision(), true, this::clearCache, replay);
            }
            checkExpectedRevision(safeId, expectedRevision, current.revision());
            long nextRevision = Math.addExact(current.revision(), 1L);
            String payloadHash = current instanceof Live ? livePayloadHash(snapshot, key, safeId)
                : current instanceof Deleted ? requireDeletedEvidence(snapshot, key, safeId).payloadHash() : EMPTY_PAYLOAD_HASH;
            Path target = snapshot.path(key).orElseGet(() -> defaultAssetPath(safeId, null));
            AssetKey tombstoneKey = tombstoneKey(safeId);
            ExpectedState tombstoneState = snapshot.state(tombstoneKey).orElse(Missing.INSTANCE);
            byte[] tombstoneBytes = GSON.toJson(tombstone(safeId, nextRevision, mutationId, payloadHash)).getBytes(StandardCharsets.UTF_8);
            List<AssetDelta> assets = List.of(
                AssetDelta.delete(key, target, current),
                AssetDelta.write(tombstoneKey, tombstonePath(safeId), tombstoneState, tombstoneBytes),
                intentWrite(snapshot, safeId, deleteIntentBytes()));
            ProjectResourceEdit edit = new ProjectResourceEdit(typeId, safeId, null, "", null, true);
            return new PreparedMutation(this, mutationId, snapshot, assets, edit, key, nextRevision, true, () -> {
                cacheRemove(safeId);
            }, null);
        } catch (IOException exception) {
            throw coordinatedFailure("prepare delete", safeId, exception);
        }
    }

    public TransactionResult commitPrepared(UUID mutationId, Snapshot snapshot, List<PreparedMutation> mutations) {
        return commitPreparedInternal(mutationId, snapshot, mutations).transaction();
    }

    private PreparedCommit commitPreparedInternal(UUID mutationId, Snapshot snapshot,
                                                  List<PreparedMutation> mutations) {
        return commitPreparedInternal(mutationId, snapshot, mutations, false);
    }

    private PreparedCommit commitPreparedInternal(UUID mutationId, Snapshot snapshot,
                                                   List<PreparedMutation> mutations,
                                                   boolean classifyPreCommitConflicts) {
        requireOpen();
        requireMutationId(mutationId);
        Objects.requireNonNull(snapshot, "snapshot");
        List<PreparedMutation> plans = List.copyOf(Objects.requireNonNull(mutations, "mutations"));
        if (plans.isEmpty()) {
            throw new IllegalArgumentException("Prepared JSON resource mutation list is empty");
        }
        TransactionResult replay = null;
        List<AssetDelta> assets = new ArrayList<>();
        for (PreparedMutation plan : plans) {
            if (plan.owner.coordinator != coordinator || !plan.mutationId.equals(mutationId)
                || plan.snapshotSequence != snapshot.rootSequence() || !plan.expectedProject.equals(snapshot.project())) {
                throw new IllegalArgumentException("Prepared JSON resource mutation does not belong to this coordinator snapshot");
            }
            if (plan.replay != null) {
                if (replay != null && !replay.intentHash().equals(plan.replay.intentHash())) {
                    throw new IllegalStateException("Prepared JSON resource replays do not share one mutation intent");
                }
                replay = plan.replay;
            } else {
                assets.addAll(plan.assets);
            }
        }
        if (replay != null && plans.stream().anyMatch(plan -> plan.replay == null)) {
            throw new IllegalStateException("Prepared JSON resource mutation mixes replayed and new work");
        }
        if (replay != null) {
            requireCompleteReplay(replay, plans);
        }
        try (MutationLease ignored = acquireMutationLease()) {
            TransactionResult result;
            List<ProjectDelta> projectDeltas = List.of();
            AssetDelta projectMetadataLineage = null;
            if (replay != null) {
                result = replay.replay() ? replay : new TransactionResult(replay.rootSequence(), replay.mutationId(),
                    replay.intentHash(), replay.states(), replay.project(), true);
            } else {
                projectDeltas = projectMetadataChanges(snapshot, plans);
                if (!projectDeltas.isEmpty() && projectMetadataLineageWriter != null) {
                    AssetDelta lineage = projectMetadataLineageWriter.write(snapshot, projectDeltas, mutationId);
                    if (lineage != null) {
                        if (assets.stream().anyMatch(delta -> delta.key().equals(lineage.key()) || delta.path().equals(lineage.path()))) {
                            throw new IllegalStateException("Project metadata lineage augmentation produced a duplicate asset delta");
                        }
                        assets.add(lineage);
                        projectMetadataLineage = lineage;
                    }
                }
                result = coordinator.transact(new TransactionRequest(mutationId, snapshot.project(), assets, projectDeltas));
                for (PreparedMutation plan : plans) {
                    ExpectedState committed = result.states().get(plan.primaryKey);
                    if (plan.deleted && !(committed instanceof Deleted) || !plan.deleted && !(committed instanceof Live)
                        || committed == null || committed.revision() != plan.nextRevision) {
                        throw new IllegalStateException("Asset coordinator did not persist the prepared JSON resource mutation");
                    }
                }
            }
            plans.forEach(plan -> plan.committed.run());
            return new PreparedCommit(result, projectDeltas, projectMetadataLineage);
        } catch (StateConflictException conflict) {
            for (PreparedMutation plan : plans) {
                ExpectedState authoritative = conflict.authoritativeStates().get(plan.primaryKey);
                if (authoritative != null) {
                    long attempted = plan.assets.stream().filter(delta -> delta.key().equals(plan.primaryKey)).findFirst()
                        .map(delta -> delta.expected().revision()).orElse(Math.max(0L, plan.nextRevision - 1L));
                    throw new ResourceRevisionConflictException(plan.primaryKey.id(), attempted, authoritative.revision());
                }
            }
            throw coordinatedFailure("commit", mutationId.toString(), conflict);
        } catch (AssetTransactionCoordinator.AssetPathConflictException conflict) {
            if (classifyPreCommitConflicts) {
                throw new PreCommitConflictException(conflict.getMessage(), conflict);
            }
            throw coordinatedFailure("commit", mutationId.toString(), conflict);
        } catch (IOException exception) {
            throw coordinatedFailure("commit", mutationId.toString(), exception);
        }
    }

    private AssetKey assetKey(String safeId) {
        return new AssetKey(typeId, safeId);
    }

    private AssetKey tombstoneKey(String safeId) {
        return new AssetKey(typeId + ".tombstone", safeId);
    }

    private AssetKey intentKey(String safeId) {
        return new AssetKey(typeId + ".intent", safeId);
    }

    private AssetKey blobKey(Path path) throws IOException {
        Path normalized = requireAuxiliaryPath(path);
        String relative = assetsRoot.relativize(normalized).toString().replace('\\', '/');
        return new AssetKey("blob", StorageSafety.sha256(relative.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean ownsCoordinatorKey(AssetKey key) {
        return key.type().equals(typeId) || key.type().equals(typeId + ".tombstone") || key.type().equals(typeId + ".intent");
    }

    private boolean isCurrentMutation(Snapshot snapshot, AssetKey key, UUID mutationId) {
        return snapshot.mutationValue(key).filter(mutationId.toString()::equals).isPresent();
    }

    private TransactionResult requireHistoricalSaveReplay(MutationView history, AssetKey key, T value,
                                                           Map<Path, byte[]> binaryWrites, UUID mutationId,
                                                           long expectedRevision,
                                                           ProjectResourceEdit presentation) throws IOException {
        JsonObject asset = historicalAsset(history, key, "WRITE");
        long priorRevision = historicalExpectedRevision(asset);
        checkExpectedRevision(key.id(), expectedRevision, priorRevision);
        JsonObject intent = historicalAsset(history, intentKey(key.id()), "WRITE");
        byte[] bytes = saveIntentBytes(value, binaryWrites, presentation);
        if (!StorageSafety.sha256(bytes).equals(text(intent, "payloadHash"))
            || intent.get("payloadSize").getAsLong() != bytes.length) {
            throw new IllegalStateException("Mutation ID payload does not match its persisted JSON resource intent: " + key.id());
        }
        ExpectedState result = history.result().states().get(key);
        if (!(result instanceof Live live) || live.revision() != priorRevision + 1L) {
            throw new IOException("Persisted JSON resource save result is incomplete: " + key.id());
        }
        return history.result();
    }

    private TransactionResult requireHistoricalDeleteReplay(MutationView history, AssetKey key, String safeId,
                                                             long expectedRevision) throws IOException {
        JsonObject asset = historicalAsset(history, key, "DELETE");
        long priorRevision = historicalExpectedRevision(asset);
        checkExpectedRevision(safeId, expectedRevision, priorRevision);
        JsonObject intent = historicalAsset(history, intentKey(safeId), "WRITE");
        byte[] intentBytes = deleteIntentBytes();
        if (!StorageSafety.sha256(intentBytes).equals(text(intent, "payloadHash"))
            || intent.get("payloadSize").getAsLong() != intentBytes.length) {
            throw new IllegalStateException("Mutation ID delete intent does not match its persisted JSON resource intent: " + safeId);
        }
        ExpectedState result = history.result().states().get(key);
        if (!(result instanceof Deleted deleted) || deleted.revision() != priorRevision + 1L) {
            throw new IOException("Persisted JSON resource delete result is incomplete: " + safeId);
        }
        return history.result();
    }

    private JsonObject historicalAsset(MutationView history, AssetKey key, String operation) throws IOException {
        JsonElement assets = history.intent().get("assets");
        if (assets == null || !assets.isJsonArray()) {
            throw new IOException("Persisted JSON resource mutation intent has no assets");
        }
        List<JsonObject> matches = assets.getAsJsonArray().asList().stream().filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .filter(asset -> key.type().equals(text(asset, "type")) && key.id().equals(text(asset, "id")))
            .toList();
        if (matches.size() != 1 || !operation.equals(text(matches.getFirst(), "operation"))) {
            throw new IllegalStateException("Mutation ID is already bound to a different JSON resource operation: " + key.id());
        }
        return matches.getFirst();
    }

    private long historicalExpectedRevision(JsonObject asset) throws IOException {
        try {
            JsonObject expected = asset.getAsJsonObject("expected");
            long revision = expected.get("revision").getAsLong();
            if (revision < 0L) {
                throw new IllegalArgumentException("negative revision");
            }
            return revision;
        } catch (RuntimeException failure) {
            throw new IOException("Persisted JSON resource mutation intent has an invalid expected revision", failure);
        }
    }

    private void requireCompleteReplay(TransactionResult replay, List<PreparedMutation> plans) {
        Set<AssetKey> requested = plans.stream().map(plan -> plan.primaryKey).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<AssetKey> persisted = replay.states().keySet().stream()
            .filter(key -> !"blob".equals(key.type()) && !"project_metadata.lineage".equals(key.type())
                && !key.type().endsWith(".tombstone") && !key.type().endsWith(".intent"))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!requested.equals(persisted)) {
            throw new IllegalStateException("Prepared JSON resource replay does not match the complete persisted mutation intent");
        }
    }

    private void requireSaveReplay(Snapshot snapshot, AssetKey key, T value, String safeId, Map<Path, byte[]> binaryWrites,
                                   UUID mutationId, long expectedRevision,
                                   ProjectResourceEdit presentation) throws IOException {
        ExpectedState state = snapshot.state(key).orElseThrow();
        if (!(state instanceof Live live)) {
            throw new IllegalStateException("Mutation ID already belongs to a deleted " + typeId + ": " + safeId);
        }
        checkExpectedRevision(safeId, expectedRevision, live.revision() - 1L);
        String json = assetJson(value, existingPayload(snapshot, key, safeId), live.revision(), mutationId,
            auxiliaryWritesHash(binaryWrites));
        Path path = snapshot.path(key).orElseThrow(() -> new IOException("JSON resource replay has no path: " + safeId));
        if (presentation != null && !path.equals(presentationAssetPath(safeId, presentation.path()))) {
            throw new IllegalStateException("Mutation ID path does not match the persisted " + typeId + ": " + safeId);
        }
        if (!live.hash().equals(StorageSafety.sha256(json.getBytes(StandardCharsets.UTF_8)))
            || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(path)))) {
            throw new IllegalStateException("Mutation ID payload does not match the persisted " + typeId + ": " + safeId);
        }
        requireCurrentIntent(snapshot, safeId, saveIntentBytes(value, binaryWrites, presentation), mutationId);
        requireCurrentBlobs(snapshot, binaryWrites);
    }

    private void requireDeleteReplay(Snapshot snapshot, AssetKey key, String safeId, UUID mutationId,
                                     long expectedRevision) throws IOException {
        ExpectedState state = snapshot.state(key).orElseThrow();
        if (!(state instanceof Deleted deleted)) {
            throw new IllegalStateException("Mutation ID already belongs to a live " + typeId + ": " + safeId);
        }
        checkExpectedRevision(safeId, expectedRevision, deleted.revision() - 1L);
        AssetKey tombstoneKey = tombstoneKey(safeId);
        if (!snapshot.mutationValue(tombstoneKey).filter(mutationId.toString()::equals).isPresent()
            || !(snapshot.state(tombstoneKey).orElse(null) instanceof Live)) {
            throw new IOException("JSON resource replay tombstone lineage is incomplete: " + safeId);
        }
        currentStamp(safeId);
        requireCurrentIntent(snapshot, safeId, deleteIntentBytes(), mutationId);
    }

    private void requireCurrentIntent(Snapshot snapshot, String safeId, byte[] requested, UUID mutationId) throws IOException {
        AssetKey key = intentKey(safeId);
        ExpectedState state = snapshot.state(key)
            .orElseThrow(() -> new IOException("JSON resource replay has no semantic intent: " + safeId));
        if (!(state instanceof Live live)
            || snapshot.mutationValue(key).filter(mutationId.toString()::equals).isEmpty()) {
            throw new IOException("JSON resource replay semantic intent lineage is incomplete: " + safeId);
        }
        Path path = snapshot.path(key)
            .orElseThrow(() -> new IOException("JSON resource replay semantic intent has no path: " + safeId));
        byte[] persisted = Files.readAllBytes(path);
        String requestedHash = StorageSafety.sha256(requested);
        if (!live.hash().equals(requestedHash) || !live.hash().equals(StorageSafety.sha256(persisted))) {
            throw new IllegalStateException("Mutation ID semantic intent does not match the requested JSON resource operation: " + safeId);
        }
    }

    private void requireCurrentBlobs(Snapshot snapshot, Map<Path, byte[]> binaryWrites) throws IOException {
        for (Map.Entry<Path, byte[]> entry : binaryWrites.entrySet()) {
            Path requestedPath = requireAuxiliaryPath(entry.getKey());
            AssetKey key = blobKey(requestedPath);
            ExpectedState state = snapshot.state(key)
                .orElseThrow(() -> new IOException("JSON resource replay blob has no coordinator state: " + requestedPath));
            if (!(state instanceof Live live)) {
                throw new IOException("JSON resource replay blob is not live: " + requestedPath);
            }
            Path persistedPath = snapshot.path(key)
                .orElseThrow(() -> new IOException("JSON resource replay blob has no coordinator path: " + requestedPath));
            byte[] requested = entry.getValue() == null ? new byte[0] : entry.getValue();
            if (!persistedPath.equals(requestedPath) || !live.hash().equals(StorageSafety.sha256(requested))
                || !live.hash().equals(StorageSafety.sha256(Files.readAllBytes(persistedPath)))) {
                throw new IllegalStateException("Mutation ID blob does not match coordinated asset storage: " + requestedPath);
            }
        }
    }

    private void addTombstoneRetirement(Snapshot snapshot, String safeId, List<AssetDelta> assets) {
        AssetKey key = tombstoneKey(safeId);
        ExpectedState state = snapshot.state(key).orElse(null);
        if (state instanceof Live) {
            Path path = snapshot.path(key).orElseThrow();
            assets.add(AssetDelta.delete(key, path, state));
        }
    }

    private void addBinaryWrites(Snapshot snapshot, Map<Path, byte[]> binaryWrites, List<AssetDelta> assets) throws IOException {
        for (Map.Entry<Path, byte[]> entry : binaryWrites.entrySet()) {
            Path path = requireAuxiliaryPath(entry.getKey());
            byte[] bytes = entry.getValue() == null ? new byte[0] : entry.getValue().clone();
            AssetKey key = blobKey(path);
            ExpectedState expected = snapshot.state(key).orElse(Missing.INSTANCE);
            String hash = StorageSafety.sha256(bytes);
            if (expected instanceof Live live && live.hash().equals(hash)) {
                continue;
            }
            assets.add(AssetDelta.write(key, path, expected, bytes));
        }
    }

    private void addIntentWrite(Snapshot snapshot, String safeId, byte[] bytes, List<AssetDelta> assets) {
        assets.add(intentWrite(snapshot, safeId, bytes));
    }

    private AssetDelta intentWrite(Snapshot snapshot, String safeId, byte[] bytes) {
        AssetKey key = intentKey(safeId);
        ExpectedState expected = snapshot.state(key).orElse(Missing.INSTANCE);
        Path path = assetsRoot.resolve(".mutation-intents").resolve(typeId).resolve(AssetFileFormat.idOnlyFileName(safeId)).normalize();
        return AssetDelta.write(key, path, expected, bytes);
    }

    private byte[] saveIntentBytes(T value, Map<Path, byte[]> binaryWrites,
                                   ProjectResourceEdit presentation) throws IOException {
        JsonObject intent = new JsonObject();
        intent.addProperty("operation", "save");
        intent.addProperty("type", typeId);
        intent.addProperty("id", idExtractor.id(value));
        intent.addProperty("payloadHash", StorageSafety.sha256(serializedPayload(value).toString().getBytes(StandardCharsets.UTF_8)));
        intent.addProperty("auxiliaryHash", auxiliaryWritesHash(binaryWrites));
        if (presentation != null) {
            JsonObject presentationJson = new JsonObject();
            presentationJson.addProperty("displayName", presentation.displayName());
            presentationJson.addProperty("path", presentation.path());
            presentationJson.addProperty("sortOrder", presentation.sortOrder());
            intent.add("presentation", presentationJson);
        }
        return GSON.toJson(intent).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] deleteIntentBytes() {
        JsonObject intent = new JsonObject();
        intent.addProperty("operation", "delete");
        intent.addProperty("type", typeId);
        return GSON.toJson(intent).getBytes(StandardCharsets.UTF_8);
    }

    private Path requireAuxiliaryPath(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Auxiliary asset target is missing");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(assetsRoot) || normalized.equals(assetsRoot) || isInternalAssetPath(normalized)
            || normalized.equals(assetsRoot.resolve("project.json"))) {
            throw new IOException("Auxiliary asset target is outside the asset root: " + path);
        }
        return normalized;
    }

    private List<ProjectDelta> projectMetadataChanges(Snapshot snapshot, List<PreparedMutation> plans) throws IOException {
        JsonObject project = snapshot.metadata().document();
        if (project.has("resources") && !project.get("resources").isJsonArray() && !project.get("resources").isJsonNull()) {
            throw new IOException("Project metadata resources are not an array");
        }
        if (project.has("folders") && !project.get("folders").isJsonArray() && !project.get("folders").isJsonNull()) {
            throw new IOException("Project metadata folders are not an array");
        }
        JsonArray original = project.has("resources") && project.get("resources").isJsonArray()
            ? project.getAsJsonArray("resources").deepCopy() : new JsonArray();
        JsonArray originalFolders = project.has("folders") && project.get("folders").isJsonArray()
            ? project.getAsJsonArray("folders").deepCopy() : new JsonArray();
        JsonArray resources = original.deepCopy();
        JsonArray folders = originalFolders.deepCopy();
        for (PreparedMutation plan : plans) {
            ProjectResourceEdit edit = plan.projectEdit;
            if (edit == null) {
                continue;
            }
            List<JsonObject> matches = resources.asList().stream().filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(resource -> edit.type().equals(text(resource, "type")) && edit.id().equals(text(resource, "id")))
                .toList();
            if (matches.size() > 1) {
                throw new IOException("Project metadata contains duplicate JSON resource identity: " + edit.type() + "/" + edit.id());
            }
            if (!edit.remove() && edit.displayName() != null && !matches.isEmpty()) {
                throw new IOException("Aggregate create project metadata identity already exists: " + edit.type() + "/" + edit.id());
            }
            if (!edit.remove() && resources.asList().stream().filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject).anyMatch(resource -> edit.path().equals(text(resource, "path"))
                    && (!edit.type().equals(text(resource, "type")) || !edit.id().equals(text(resource, "id"))))) {
                throw new IOException("Project metadata resource path is already in use: " + edit.path());
            }
            resources.asList().removeIf(element -> element != null && element.isJsonObject()
                && edit.type().equals(text(element.getAsJsonObject(), "type")) && edit.id().equals(text(element.getAsJsonObject(), "id")));
            if (!edit.remove()) {
                ensurePresentationFolders(folders, edit.path());
                JsonObject resource = matches.isEmpty() ? new JsonObject() : matches.getFirst().deepCopy();
                resource.addProperty("type", edit.type());
                resource.addProperty("id", edit.id());
                resource.addProperty("path", edit.path());
                if (edit.displayName() != null) {
                    resource.addProperty("displayName", edit.displayName());
                }
                if (edit.sortOrder() != null) {
                    resource.addProperty("sortOrder", edit.sortOrder());
                }
                resources.add(resource);
            }
        }
        List<ProjectDelta> deltas = new ArrayList<>();
        if (!folders.equals(originalFolders)) {
            deltas.add(ProjectDelta.set(List.of("folders"), folders));
        }
        if (!resources.equals(original)) {
            deltas.add(ProjectDelta.set(List.of("resources"), resources));
        }
        return List.copyOf(deltas);
    }

    private void ensurePresentationFolders(JsonArray folders, String resourcePath) throws IOException {
        int separator = resourcePath.lastIndexOf('/');
        if (separator < 1) {
            return;
        }
        String[] parts = resourcePath.substring(0, separator).split("/");
        String parent = "";
        for (String part : parts) {
            String path = parent.isBlank() ? part : parent + '/' + part;
            List<JsonObject> matches = folders.asList().stream().filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject).filter(folder -> path.equals(text(folder, "path"))).toList();
            if (matches.size() > 1) {
                throw new IOException("Project metadata contains duplicate folder path: " + path);
            }
            if (matches.isEmpty()) {
                JsonObject folder = new JsonObject();
                folder.addProperty("path", path);
                folder.addProperty("parentPath", parent);
                folder.addProperty("name", part);
                folder.addProperty("sortOrder", 0);
                folder.addProperty("collapsed", false);
                folders.add(folder);
            }
            parent = path;
        }
    }

    private String livePayloadHash(Snapshot snapshot, AssetKey key, String safeId) throws IOException {
        Path path = snapshot.path(key).orElseThrow(() -> new IOException("Live JSON resource has no coordinator path: " + safeId));
        return readStampFile(path, safeId, false, true).payloadHash();
    }

    private AssetStamp requireDeletedEvidence(Snapshot snapshot, AssetKey primaryKey, String safeId) throws IOException {
        ExpectedState primary = snapshot.state(primaryKey).orElseThrow();
        if (!(primary instanceof Deleted)) {
            throw new IOException("JSON resource is not deleted: " + safeId);
        }
        String primaryLineage = snapshot.mutationValue(primaryKey)
            .orElseThrow(() -> new IOException("Deleted JSON resource has no coordinator lineage: " + safeId));
        AssetKey tombstoneKey = tombstoneKey(safeId);
        ExpectedState tombstone = snapshot.state(tombstoneKey)
            .orElseThrow(() -> new IOException("Deleted JSON resource has no tombstone state: " + safeId));
        if (!(tombstone instanceof Live live)) {
            throw new IOException("Deleted JSON resource tombstone is not live: " + safeId);
        }
        String tombstoneLineage = snapshot.mutationValue(tombstoneKey)
            .orElseThrow(() -> new IOException("Deleted JSON resource tombstone has no coordinator lineage: " + safeId));
        if (!primaryLineage.equals(tombstoneLineage)) {
            throw new IOException("Deleted JSON resource tombstone lineage does not match its primary state: " + safeId);
        }
        Path path = snapshot.path(tombstoneKey)
            .orElseThrow(() -> new IOException("Deleted JSON resource tombstone has no coordinator path: " + safeId));
        byte[] bytes = Files.readAllBytes(path);
        if (!live.hash().equals(StorageSafety.sha256(bytes))) {
            throw new IOException("Deleted JSON resource tombstone bytes do not match coordinator state: " + safeId);
        }
        AssetStamp stamp = readStampFile(path, safeId, true, true);
        if (stamp.revision() != primary.revision() || !stamp.mutationValue().equals(primaryLineage)) {
            throw new IOException("Deleted JSON resource tombstone identity does not match its primary state: " + safeId);
        }
        return stamp;
    }

    private Path tombstonePath(String safeId) {
        return assetsRoot.resolve(".tombstones").resolve(typeId).resolve(AssetFileFormat.idOnlyFileName(safeId)).normalize();
    }

    private Path defaultAssetPath(String safeId, T value) {
        String folder = value != null && folderResolver != null ? folderResolver.folder(value) : defaultFolder;
        return safeAssetFolder(AssetFileFormat.canonicalFolder(folder, defaultFolder))
            .resolve(AssetFileFormat.idOnlyFileName(safeId)).normalize();
    }

    private ProjectResourceEdit presentationEdit(String safeId, ResourcePresentationIntent presentation) {
        Path target = presentationAssetPath(safeId, presentation.path());
        String relative = assetsRoot.relativize(target).toString().replace('\\', '/');
        if (!relative.equals(presentation.path())) {
            throw new IllegalArgumentException("Aggregate create presentation path is not canonical: " + presentation.path());
        }
        return new ProjectResourceEdit(typeId, safeId, presentation.displayName(), relative,
            presentation.sortOrder(), false);
    }

    private AggregatePrimary aggregatePrimary(Snapshot snapshot, PreparedCommit committed, PreparedMutation plan,
                                              String safeId, UUID mutationId) throws IOException {
        AssetKey key = assetKey(safeId);
        ExpectedState resultState = committed.transaction().states().get(key);
        if (!(resultState instanceof Live live) || live.revision() != plan.nextRevision
            || !mutationId.equals(committed.transaction().mutationId())) {
            throw new IOException("Aggregate create primary state is not the exact committed resource: " + safeId);
        }
        byte[] bytes;
        List<AssetDelta> primaryDeltas = plan.assets.stream().filter(delta -> key.equals(delta.key())).toList();
        if (primaryDeltas.size() == 1) {
            AssetDelta delta = primaryDeltas.getFirst();
            bytes = delta.content();
            if (delta.deleted() || !live.hash().equals(StorageSafety.sha256(bytes))) {
                throw new IOException("Aggregate create primary delta does not match its committed state: " + safeId);
            }
        } else if (primaryDeltas.isEmpty()) {
            ExpectedState snapshotState = snapshot.state(key).orElse(null);
            Path path = snapshot.path(key).orElse(null);
            if (!live.equals(snapshotState) || path == null
                || snapshot.mutationValue(key).filter(mutationId.toString()::equals).isEmpty()) {
                throw new IOException("Aggregate create replay has no exact primary state: " + safeId);
            }
            bytes = Files.readAllBytes(path);
            if (!live.hash().equals(StorageSafety.sha256(bytes))) {
                throw new IOException("Aggregate create replay primary bytes differ from coordinator state: " + safeId);
            }
        } else {
            throw new IOException("Aggregate create transaction repeats its primary resource: " + safeId);
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("payload is not an object");
            }
            JsonObject object = parsed.getAsJsonObject();
            if (!typeId.equals(text(object, "resourceType")) || !safeId.equals(text(object, "id"))
                || live.revision() != exactLong(object, AssetFileFormat.REVISION, assetsRoot)
                || !mutationId.toString().equals(exactText(object, AssetFileFormat.MUTATION_ID, assetsRoot))) {
                throw new IllegalArgumentException("payload identity differs from committed state");
            }
            return new AggregatePrimary(logicalPayload(object), live);
        } catch (RuntimeException exception) {
            throw new IOException("Aggregate create primary payload is invalid: " + safeId, exception);
        }
    }

    private Path presentationAssetPath(String safeId, String path) {
        String normalized = normalizeAssetPath(path);
        if (!normalized.equals(path) || (!normalized.endsWith("/" + AssetFileFormat.idOnlyFileName(safeId))
            && !normalized.equals(AssetFileFormat.idOnlyFileName(safeId)))) {
            throw new IllegalArgumentException("Aggregate create path must end with the resource ID: " + path);
        }
        Path target = assetsRoot.resolve(normalized.replace('/', File.separatorChar)).toAbsolutePath().normalize();
        Path root = assetsRoot.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || isInternalAssetPath(target)
            || target.equals(root.resolve("project.json"))) {
            throw new IllegalArgumentException("Aggregate create path is outside coordinated asset storage: " + path);
        }
        Path ancestor = target.getParent();
        while (ancestor != null && ancestor.startsWith(root) && !ancestor.equals(root)) {
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(ancestor) || !Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalArgumentException("Aggregate create path is not a writable file: " + path);
            }
            ancestor = ancestor.getParent();
        }
        return target;
    }

    private AssetStamp projectMetadataStamp(Snapshot snapshot, PreparedCommit committed,
                                            AssetProjectMetadata metadata, UUID mutationId) throws IOException {
        AssetDelta lineage = committed.projectMetadataLineage();
        if (lineage != null) {
            ExpectedState committedLineage = committed.transaction().states().get(lineage.key());
            if (!(committedLineage instanceof Live live) || lineage.deleted()
                || !live.hash().equals(StorageSafety.sha256(lineage.content()))) {
                throw new IOException("Aggregate create project metadata lineage differs from its transaction");
            }
            return projectMetadataStamp(lineage.content(), metadata, mutationId);
        }
        if (!snapshot.project().equals(committed.transaction().project())) {
            throw new IllegalStateException("Aggregate create replay no longer has the exact project metadata state");
        }
        AssetKey key = new AssetKey("project_metadata.lineage", "project");
        if (!snapshot.mutationValue(key).filter(mutationId.toString()::equals).isPresent()) {
            throw new IllegalStateException("Aggregate create replay has no exact project metadata lineage");
        }
        Path path = snapshot.path(key)
            .orElseThrow(() -> new IOException("Aggregate create replay project metadata lineage has no path"));
        return projectMetadataStamp(Files.readAllBytes(path), metadata, mutationId);
    }

    @SuppressWarnings("unchecked")
    private AssetStamp projectMetadataStamp(byte[] bytes, AssetProjectMetadata metadata,
                                            UUID mutationId) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("lineage is not an object");
            }
            JsonObject lineage = parsed.getAsJsonObject();
            String type = exactText(lineage, "type", assetsRoot);
            String id = exactText(lineage, "id", assetsRoot);
            String mutation = exactText(lineage, "mutationId", assetsRoot);
            String payloadHash = exactText(lineage, "payloadHash", assetsRoot);
            String format = exactText(lineage, "format", assetsRoot);
            long revision = exactLong(lineage, "revision", assetsRoot);
            boolean deleted = lineage.has("deleted") && lineage.get("deleted").getAsBoolean();
            Map<String, Object> payload = GSON.fromJson(metadata.serializedJson(), Map.class);
            String expectedHash = ResourcePayloadCodecs.json().hashPayload(payload).canonicalText();
            if (!"project-metadata-lineage-v1".equals(format) || !"project_metadata".equals(type)
                || id.isBlank() || revision < 1L || deleted
                || !mutationId.toString().equals(mutation) || !expectedHash.equals(payloadHash)) {
                throw new IllegalArgumentException("lineage identity does not match aggregate create");
            }
            return new AssetStamp(type, id, revision, mutation, payloadHash, false);
        } catch (RuntimeException exception) {
            throw new IOException("Aggregate create project metadata lineage is invalid", exception);
        }
    }

    private IllegalStateException coordinatedFailure(String operation, String safeId, IOException failure) {
        return new IllegalStateException("Failed to " + operation + " " + typeId + ": " + safeId, failure);
    }

    private JsonObject tombstone(String safeId, long revision, UUID mutationId, String payloadHash) {
        String mutation = mutationId.toString();
        JsonObject tombstone = new JsonObject();
        tombstone.addProperty("type", typeId);
        tombstone.addProperty("id", safeId);
        tombstone.addProperty("resourceType", typeId);
        tombstone.addProperty("assetFormatVersion", AssetFileFormat.CURRENT_FORMAT_VERSION);
        tombstone.addProperty("revision", revision);
        tombstone.addProperty(AssetFileFormat.REVISION, revision);
        tombstone.addProperty("mutationId", mutation);
        tombstone.addProperty(AssetFileFormat.MUTATION_ID, mutation);
        tombstone.addProperty("payloadHash", payloadHash);
        tombstone.addProperty("deleted", true);
        tombstone.addProperty("deletedAt", System.currentTimeMillis());
        return tombstone;
    }

    private CoordinatorLineage coordinatorLineage(String safeId, Snapshot snapshot) {
        AssetKey key = assetKey(safeId);
        ExpectedState state = snapshot.state(key).orElse(null);
        if (state instanceof Live live) {
            return snapshot.mutationValue(key)
                .map(mutation -> new CoordinatorLineage(live.revision(), mutation, false))
                .orElse(null);
        }
        if (state instanceof Deleted deleted) {
            return snapshot.mutationValue(key)
                .map(mutation -> new CoordinatorLineage(deleted.revision(), mutation, true))
                .orElse(null);
        }
        return null;
    }

    private static boolean matches(AssetStamp stamp, CoordinatorLineage lineage) {
        return stamp != null && lineage != null
            && stamp.revision() == lineage.revision()
            && stamp.mutationValue().equals(lineage.mutationValue())
            && stamp.deleted() == lineage.deleted();
    }

    private AssetStamp currentStamp(String safeId) throws IOException {
        Snapshot snapshot = coordinator.read(current -> current);
        return currentStamp(safeId, snapshot);
    }

    private AssetStamp currentStamp(String safeId, Snapshot snapshot) throws IOException {
        AssetKey key = assetKey(safeId);
        ExpectedState state = snapshot.state(key).orElse(null);
        if (state == null) {
            return null;
        }
        String mutationValue = snapshot.mutationValue(key)
            .orElseThrow(() -> new IOException("JSON resource has no coordinator mutation lineage: " + safeId));
        AssetStamp stamp;
        if (state instanceof Live live) {
            Path path = snapshot.path(key)
                .orElseThrow(() -> new IOException("JSON resource has no coordinator path: " + safeId));
            if (!live.hash().equals(StorageSafety.sha256(Files.readAllBytes(path)))) {
                throw new IOException("JSON resource bytes do not match coordinator state: " + safeId);
            }
            stamp = readStampFile(path, safeId, false, true);
        } else if (state instanceof Deleted) {
            stamp = requireDeletedEvidence(snapshot, key, safeId);
        } else {
            return null;
        }
        if (stamp.revision() != state.revision() || !stamp.mutationValue().equals(mutationValue)) {
            throw new IOException("JSON resource identity does not match coordinator lineage: " + safeId);
        }
        return stamp;
    }

    private AssetStamp readStampFile(Path file, String safeId, boolean deleted, boolean verifyLive) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(StorageSafety.readUtf8(file));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON resource identity: " + file, exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("JSON resource identity is not an object: " + file);
        }
        JsonObject object = parsed.getAsJsonObject();
        if (!typeId.equals(text(object, "resourceType")) || !safeId.equals(text(object, "id"))) {
            throw new IOException("JSON resource identity mismatch: " + file);
        }
        long revision = exactLong(object, AssetFileFormat.REVISION, file);
        String mutation = exactText(object, AssetFileFormat.MUTATION_ID, file);
        if (revision < 1L || mutation.isBlank()) {
            throw new IOException("JSON resource identity is incomplete: " + file);
        }
        agreeLegacyRevision(object, "revision", revision, file);
        agreeLegacyRevision(object, "resourceRevision", revision, file);
        agreeLegacyMutation(object, "mutationId", mutation, file);
        agreeLegacyMutation(object, "resourceMutationId", mutation, file);
        String payloadHash;
        if (deleted) {
            if (!typeId.equals(text(object, "type")) || !object.has("deleted") || !exactBoolean(object, "deleted", file)) {
                throw new IOException("JSON resource tombstone identity failed: " + file);
            }
            payloadHash = exactText(object, "payloadHash", file);
            if (!payloadHash.matches("[0-9a-f]{64}")) {
                throw new IOException("JSON resource tombstone payload hash is invalid: " + file);
            }
        } else {
            if (object.has("deleted") && exactBoolean(object, "deleted", file)) {
                throw new IOException("JSON resource live identity is marked deleted: " + file);
            }
            if (verifyLive && (AssetFileFormat.readContentHash(file).isBlank() || !AssetFileFormat.verify(file))) {
                throw new IOException("JSON resource integrity failed: " + file);
            }
            payloadHash = canonicalPayloadHash(GSON.toJson(object));
        }
        return new AssetStamp(typeId, safeId, revision, mutation, payloadHash, deleted);
    }

    private void checkExpectedRevision(String safeId, long expectedRevision, long currentRevision) {
        if (expectedRevision >= 0L && expectedRevision != currentRevision) {
            throw new ResourceRevisionConflictException(safeId, expectedRevision, currentRevision);
        }
    }

    private void requireExpectedRevision(long expectedRevision) {
        if (expectedRevision < NO_EXPECTED_REVISION) {
            throw new IllegalArgumentException("Expected asset revision cannot be less than " + NO_EXPECTED_REVISION);
        }
    }

    private void requireMutationId(UUID mutationId) {
        Objects.requireNonNull(mutationId, "mutationId");
    }

    private void agreeLegacyRevision(JsonObject object, String key, long canonical, Path file) throws IOException {
        if (object.has(key) && exactLong(object, key, file) != canonical) {
            throw new IOException("JSON resource revision fields disagree: " + file);
        }
    }

    private void agreeLegacyMutation(JsonObject object, String key, String canonical, Path file) throws IOException {
        if (object.has(key) && !canonical.equals(exactText(object, key, file))) {
            throw new IOException("JSON resource mutation fields disagree: " + file);
        }
    }

    private long exactLong(JsonObject object, String key, Path file) throws IOException {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IOException("JSON resource identity is missing " + key + ": " + file);
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException exception) {
            throw new IOException("JSON resource identity has invalid " + key + ": " + file, exception);
        }
    }

    private String exactText(JsonObject object, String key, Path file) throws IOException {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IOException("JSON resource identity is missing " + key + ": " + file);
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException exception) {
            throw new IOException("JSON resource identity has invalid " + key + ": " + file, exception);
        }
    }

    private String assetJson(T value, JsonObject existing, long revision, UUID mutationId, String auxiliaryHash) throws IOException {
        JsonObject serialized = serializedPayload(value);
        JsonObject merged;
        try {
            merged = Objects.requireNonNull(payloadMerger.merge(value, existing.deepCopy(), serialized.deepCopy()),
                "payload merger result");
        } catch (RuntimeException failure) {
            throw new IOException("Failed to merge JSON resource payload", failure);
        }
        String payload = withAuxiliaryHash(GSON.toJson(merged), auxiliaryHash);
        return AssetFileFormat.withResourceIdentity(payload, typeId, revision, mutationId.toString());
    }

    private JsonObject serializedPayload(T value) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(writer.write(value));
        } catch (RuntimeException failure) {
            throw new IOException("Invalid serialized JSON resource payload", failure);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Serialized JSON resource payload is not an object");
        }
        return logicalPayload(parsed.getAsJsonObject());
    }

    private JsonObject existingPayload(Snapshot snapshot, AssetKey key, String safeId) throws IOException {
        ExpectedState state = snapshot.state(key).orElseThrow();
        if (!(state instanceof Live live)) {
            return new JsonObject();
        }
        Path path = snapshot.path(key).orElseThrow(() -> new IOException("Live JSON resource has no coordinator path: " + safeId));
        byte[] bytes = Files.readAllBytes(path);
        if (!live.hash().equals(StorageSafety.sha256(bytes))) {
            throw new IOException("Live JSON resource does not match coordinator state: " + safeId);
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Live JSON resource payload is not an object: " + safeId);
            }
            return logicalPayload(parsed.getAsJsonObject());
        } catch (RuntimeException failure) {
            throw new IOException("Live JSON resource payload is invalid: " + safeId, failure);
        }
    }

    public static JsonObject logicalPayload(JsonObject source) {
        JsonObject payload = source.deepCopy();
        for (String field : List.of("resourceType", "assetFormatVersion", "assetRevision", "assetHash", "assetMutationId",
            "resourceRevision", "resourceHash", "resourceMutationId", "revision", "mutationId", AUXILIARY_HASH)) {
            payload.remove(field);
        }
        return payload;
    }

    private String withAuxiliaryHash(String json, String auxiliaryHash) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON resource payload", exception);
        }
        if (!parsed.isJsonObject()) {
            return json;
        }
        JsonObject object = parsed.getAsJsonObject();
        object.addProperty(AUXILIARY_HASH, auxiliaryHash);
        return GSON.toJson(object);
    }

    private String auxiliaryWritesHash(Map<Path, byte[]> binaryWrites) throws IOException {
        if (binaryWrites == null || binaryWrites.isEmpty()) {
            return "";
        }
        List<String> entries = new ArrayList<>();
        for (Map.Entry<Path, byte[]> entry : binaryWrites.entrySet()) {
            Path target = entry.getKey();
            if (target == null) {
                throw new IOException("Auxiliary asset target is missing");
            }
            Path normalized = target.toAbsolutePath().normalize();
            requireAuxiliaryPath(normalized);
            String relative = assetsRoot.relativize(normalized).toString().replace('\\', '/');
            byte[] content = entry.getValue() != null ? entry.getValue() : new byte[0];
            entries.add(relative + "\n" + StorageSafety.sha256(content));
        }
        entries.sort(String::compareTo);
        return StorageSafety.sha256(String.join("\n", entries).getBytes(StandardCharsets.UTF_8));
    }

    private boolean exactBoolean(JsonObject object, String key, Path file) throws IOException {
        if (!object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isBoolean()) {
            throw new IOException("JSON resource identity has invalid " + key + ": " + file);
        }
        return object.getAsJsonPrimitive(key).getAsBoolean();
    }

    @SuppressWarnings("unchecked")
    private String canonicalPayloadHash(String json) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON resource payload", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("JSON resource payload is not an object");
        }
        JsonObject payload = parsed.getAsJsonObject().deepCopy();
        for (String field : List.of("resourceType", "assetFormatVersion", "assetRevision", "assetHash", "assetMutationId",
            "resourceRevision", "resourceHash", "resourceMutationId", "revision", "mutationId", AUXILIARY_HASH)) {
            payload.remove(field);
        }
        return ResourcePayloadCodecs.json().hashPayload(GSON.fromJson(payload, Map.class)).canonicalText();
    }

    private void validateCanonicalFile(Path file, String expectedId) throws IOException {
        JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(StorageSafety.readUtf8(file));
            if (!parsed.isJsonObject()) {
                throw new IOException("JSON resource is not an object: " + file);
            }
            object = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON resource: " + file, exception);
        }
        if (!typeId.equals(AssetFileFormat.readResourceType(file))
                || AssetFileFormat.readContentHash(file).isBlank()
                || !AssetFileFormat.verify(file)) {
            throw new IOException("JSON resource integrity failed: " + file);
        }
        try {
            T value = reader.read(GSON.toJson(object));
            String id = value != null ? safeId(idExtractor.id(value), "health check") : null;
            if (id == null || !id.equals(expectedId)) {
                throw new IOException("JSON resource identity failed: " + file);
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("JSON resource identity failed: " + file, exception);
        }
    }

    private Map<String, List<Path>> collectOwnedAssetFiles(JsonAssetInventory inventory) {
        Map<String, List<Path>> owned = new LinkedHashMap<>();
        for (JsonAssetInventory.Entry entry : inventory.filesForType(typeId)) {
            if (typeId.equals(entry.type()) && safeId(entry.id(), "health check") != null) {
                owned.computeIfAbsent(entry.id(), ignored -> new ArrayList<>()).add(entry.path());
            }
        }
        return owned;
    }

    private void validateDefaultFolderFiles(JsonAssetInventory inventory, Map<String, List<Path>> ownedAssets) throws IOException {
        Path folder = safeAssetFolder(defaultFolder);
        for (JsonAssetInventory.Entry entry : inventory.jsonFilesUnder(folder)) {
            if (!typeId.equals(entry.type()) && ReSyncResourceCatalog.byType(entry.type()) != null) {
                continue;
            }
            if (!typeId.equals(entry.type()) || !ownedAssets.containsKey(entry.id())) {
                throw new IOException("Unrecognized " + typeId + " asset file: " + entry.path());
            }
        }
    }

    private void validateTombstones(JsonAssetInventory inventory) throws IOException {
        Path root = assetsRoot.resolve(".tombstones").resolve(typeId);
        List<Path> unexpectedDirectories = inventory.directoriesDirectlyUnder(root);
        if (!unexpectedDirectories.isEmpty()) {
            throw new IOException("Invalid JSON resource tombstone directory: " + unexpectedDirectories.getFirst());
        }
        for (JsonAssetInventory.Entry entry : inventory.filesDirectlyUnder(root)) {
            String fileName = entry.fileName();
            String expectedId = canonicalFileId(fileName);
            if (expectedId.isBlank() || safeId(expectedId, "tombstone health check") == null) {
                throw new IOException("Invalid JSON resource tombstone file: " + entry.path());
            }
            readStampFile(entry.path(), expectedId, true, true);
        }
    }

    private String canonicalFileId(String fileName) {
        return fileName != null && fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : "";
    }

    private MutationLease acquireMutationLease() {
        MutationLease lease = mutationLeaseProvider.acquire();
        if (lease == null) {
            throw new IllegalStateException("JSON resource mutation admission is unavailable");
        }
        return lease;
    }

    private CachedValue cacheGet(String id) {
        synchronized (cacheLock) {
            return cache.get(id);
        }
    }

    private void cacheRemove(String id) {
        synchronized (cacheLock) {
            cache.remove(id);
        }
    }

    private boolean isInternalAssetPath(Path file) {
        Path root = assetsRoot.toAbsolutePath().normalize();
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0) {
            return false;
        }
        return Set.of(".transactions", ".snapshots", ".asset-coordinator", ".mutation-intents", ".quarantine", ".durability",
                ".tombstones", ".migrations", "migration-backups")
            .contains(relative.getName(0).toString());
    }

    private Path safeAssetFolder(String folder) {
        Path root = assetsRoot.toAbsolutePath().normalize();
        Path result = root;
        String normalized = normalizeAssetPath(folder);
        if (!normalized.isBlank()) {
            for (String part : normalized.split("/")) {
                result = result.resolve(part);
                if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(result) || !Files.isDirectory(result, LinkOption.NOFOLLOW_LINKS))) {
                    throw new IllegalArgumentException("Unsafe assets folder: " + folder);
                }
            }
        }
        Path target = result.normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Unsafe assets folder: " + folder);
        }
        return target;
    }

    private String normalizeAssetPath(String path) {
        String normalized = path != null ? path.replace('\\', '/').replaceAll("/+", "/").trim() : "";
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Unsafe assets path: " + path);
        }
        return normalized;
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException exception) {
            Log.warn("Rejected unsafe " + typeId + " id during " + action + ": " + id);
            return null;
        }
    }

}
