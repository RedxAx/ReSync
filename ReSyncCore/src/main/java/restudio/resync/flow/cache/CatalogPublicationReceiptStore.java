package restudio.resync.flow.cache;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class CatalogPublicationReceiptStore implements CatalogPublicationReceiptOperations, CloseablePersistenceParticipant,
    PersistenceOwnershipProvider {
    public static final String OWNER = "resync.catalog-publication-receipts";
    public static final String FILE_NAME = "catalog-publication-receipts.json";
    private static final String KIND = "catalog-publication-receipts";
    private static final int VERSION = 2;
    private static final Set<String> DOCUMENT_FIELDS = Set.of("kind", "version", "receipts", "contentHash");
    private static final Set<String> LEGACY_RECEIPT_FIELDS = Set.of("sessionKey", "publicationKey", "revision", "dispatch",
        "clientReceipt", "cacheApplication", "diagnosticCode");
    private static final Set<String> V2_LEGACY_RECEIPT_FIELDS = Set.of("sessionKey", "ownerState", "publicationKey",
        "revision", "dispatch", "clientReceipt", "cacheApplication", "diagnosticCode");
    private static final Set<String> V2_CLAIMED_RECEIPT_FIELDS = Set.of("sessionKey", "ownerState", "ownerToken",
        "publicationKey", "revision", "dispatch", "clientReceipt", "cacheApplication", "diagnosticCode");

    private final Path scopeRoot;
    private final String fileName;
    private final boolean bootstrapMissing;
    private final CatalogPublicationReceiptTracker tracker;
    private Binding activeBinding;
    private PersistenceState persistenceState = PersistenceState.OPEN;

    public CatalogPublicationReceiptStore(Path path) {
        this(requireParent(path), path, new CatalogPublicationReceiptTracker(), false);
    }

    public CatalogPublicationReceiptStore(Path path, CatalogPublicationReceiptTracker tracker) {
        this(requireParent(path), path, tracker, false);
    }

    public CatalogPublicationReceiptStore(Path scopeRoot, Path path) {
        this(scopeRoot, path, new CatalogPublicationReceiptTracker(), true);
    }

    public CatalogPublicationReceiptStore(Path scopeRoot, Path path, CatalogPublicationReceiptTracker tracker) {
        this(scopeRoot, path, tracker, true);
    }

    private CatalogPublicationReceiptStore(Path scopeRoot, Path path, CatalogPublicationReceiptTracker tracker,
                                           boolean bootstrapMissing) {
        this.scopeRoot = prepareScope(scopeRoot);
        Path normalizedPath = preparePath(this.scopeRoot, path);
        if (bootstrapMissing && !FILE_NAME.equals(normalizedPath.getFileName().toString())) {
            throw new IllegalArgumentException("Catalog publication receipt participant must own " + FILE_NAME);
        }
        this.fileName = normalizedPath.getFileName().toString();
        this.bootstrapMissing = bootstrapMissing;
        this.tracker = Objects.requireNonNull(tracker, "Catalog publication receipt tracker is required");
        this.activeBinding = new Binding(normalizedPath, 0L);
        initialize();
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public synchronized Path root() {
        return activeBinding.path();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public synchronized boolean owns(Path file) {
        return activeBinding.path().equals(MigrationPaths.requirePath(file, "file"));
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).exactRoot().build();
    }

    public synchronized Path path() {
        return activeBinding.path();
    }

    public synchronized long generation() {
        return activeBinding.generation();
    }

    public synchronized boolean isQuiesced() {
        return persistenceState == PersistenceState.QUIESCED;
    }

    public synchronized boolean isClosed() {
        return persistenceState == PersistenceState.CLOSED;
    }

    @Override
    public synchronized CatalogPublicationReceipt.Claim claim(String sessionKey, String ownerToken) {
        return mutate(() -> tracker.claim(sessionKey, ownerToken));
    }

    @Override
    public synchronized CatalogPublicationReceipt.Claim adopt(String sessionKey, String ownerToken) {
        return mutate(() -> tracker.adopt(sessionKey, ownerToken));
    }

    @Override
    public synchronized CatalogPublicationReceipt.Claim adoptForRedispatch(String sessionKey, String ownerToken,
                                                                            CatalogCachePublication publication) {
        return mutate(() -> tracker.adoptForRedispatch(sessionKey, ownerToken, publication));
    }

    @Override
    public synchronized CatalogPublicationReceipt recordDispatch(String sessionKey, String ownerToken,
                                                                  CatalogCachePublication publication) {
        return mutate(() -> tracker.recordDispatch(sessionKey, ownerToken, publication));
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition tryRecordDispatch(String sessionKey, String ownerToken,
                                                                                 CatalogCachePublication publication) {
        return mutate(() -> tracker.tryRecordDispatch(sessionKey, ownerToken, publication));
    }

    @Override
    public synchronized void recordDispatchBatch(Map<String, String> sessionOwners,
                                                  CatalogCachePublication publication) {
        mutate(() -> {
            tracker.recordDispatchBatch(sessionOwners, publication);
            return null;
        });
    }

    @Override
    public synchronized CatalogPublicationReceipt.BatchTransition tryRecordDispatchBatch(
        Map<String, String> sessionOwners, CatalogCachePublication publication) {
        return mutate(() -> tracker.tryRecordDispatchBatch(sessionOwners, publication));
    }

    @Override
    public synchronized CatalogPublicationReceipt recordDispatchFailure(String sessionKey, String ownerToken,
                                                                          CatalogCachePublication publication,
                                                                          String diagnosticCode) {
        return mutate(() -> tracker.recordDispatchFailure(sessionKey, ownerToken, publication, diagnosticCode));
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition tryRecordDispatchFailure(String sessionKey,
                                                                                        String ownerToken,
                                                                                        CatalogCachePublication publication,
                                                                                        String diagnosticCode) {
        return mutate(() -> tracker.tryRecordDispatchFailure(sessionKey, ownerToken, publication, diagnosticCode));
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition acknowledgeClientReceipt(String sessionKey,
                                                                                        String ownerToken,
                                                                                        CatalogCacheKey publicationKey,
                                                                                        long revision) {
        return mutate(() -> tracker.acknowledgeClientReceipt(sessionKey, ownerToken, publicationKey, revision));
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition acknowledgeCacheApplication(String sessionKey,
                                                                                           String ownerToken,
                                                                                           CatalogCacheKey publicationKey,
                                                                                           long revision) {
        return mutate(() -> tracker.acknowledgeCacheApplication(sessionKey, ownerToken, publicationKey, revision));
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition rejectCacheApplication(String sessionKey,
                                                                                       String ownerToken,
                                                                                       CatalogCacheKey publicationKey,
                                                                                       long revision,
                                                                                       String diagnosticCode) {
        return mutate(() -> tracker.rejectCacheApplication(sessionKey, ownerToken, publicationKey, revision,
            diagnosticCode));
    }

    @Override
    public synchronized Optional<CatalogPublicationReceipt> baseline(String sessionKey) {
        return tracker.baseline(sessionKey);
    }

    @Override
    public synchronized List<CatalogPublicationReceipt> baselines() {
        return tracker.baselines();
    }

    @Override
    public synchronized List<String> pendingSessionKeys() {
        return tracker.pendingSessionKeys();
    }

    public synchronized Optional<CatalogPublicationReceiptReplay> replay(String sessionKey) {
        return tracker.baseline(sessionKey).map(CatalogPublicationReceiptReplay::from);
    }

    public synchronized List<CatalogPublicationReceiptReplay> pendingReplays() {
        return tracker.baselines().stream()
            .filter(receipt -> !receipt.converged())
            .map(CatalogPublicationReceiptReplay::from)
            .toList();
    }

    @Override
    public synchronized boolean remove(String sessionKey, String ownerToken) {
        return mutate(() -> tracker.remove(sessionKey, ownerToken));
    }

    @Override
    public synchronized void flush() throws IOException {
        requireNotClosed();
        persist(activeBinding, tracker.baselines());
    }

    @Override
    public synchronized void quiesce() throws IOException {
        requireNotClosed();
        if (persistenceState == PersistenceState.QUIESCED) {
            return;
        }
        flush();
        persistenceState = PersistenceState.QUIESCED;
    }

    @Override
    public synchronized void resume() throws IOException {
        requireNotClosed();
        if (persistenceState == PersistenceState.OPEN) {
            return;
        }
        List<CatalogPublicationReceipt> persisted = read(activeBinding.path(), true);
        if (!persisted.equals(tracker.baselines())) {
            throw new IOException("Catalog publication receipts are out of sync with the active file");
        }
        persistenceState = PersistenceState.OPEN;
    }

    @Override
    public synchronized void rebind(Path activeRoot) throws IOException {
        if (persistenceState != PersistenceState.QUIESCED) {
            throw new IOException("Catalog publication receipt persistence must be quiesced before rebind");
        }
        Path candidateScope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidatePath = candidateScope.resolve(fileName).toAbsolutePath().normalize();
        if (!candidatePath.startsWith(candidateScope) || candidatePath.getParent() == null
            || !candidatePath.getParent().equals(candidateScope)) {
            throw new IOException("Catalog publication receipt rebind escaped active root");
        }
        List<CatalogPublicationReceipt> candidate = read(candidatePath, true);
        if (candidatePath.equals(activeBinding.path())) {
            if (!candidate.equals(tracker.baselines())) {
                throw new IOException("Catalog publication receipts are out of sync with the active file");
            }
            return;
        }
        long nextGeneration;
        try {
            nextGeneration = Math.addExact(activeBinding.generation(), 1L);
        } catch (ArithmeticException exception) {
            throw new IOException("Catalog publication receipt binding generation overflowed", exception);
        }
        Binding previous = activeBinding;
        List<CatalogPublicationReceipt> previousReceipts = tracker.baselines();
        try {
            tracker.restore(candidate);
            activeBinding = new Binding(candidatePath, nextGeneration);
        } catch (RuntimeException exception) {
            activeBinding = previous;
            tracker.restore(previousReceipts);
            throw exception;
        }
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        requireNotClosed();
        List<CatalogPublicationReceipt> persisted = read(activeBinding.path(), true);
        if (!persisted.equals(tracker.baselines())) {
            throw new IOException("Catalog publication receipts are out of sync with the active file");
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

    public synchronized void reload() {
        requireWritable();
        List<CatalogPublicationReceipt> loaded = readUnchecked(activeBinding.path(), true);
        tracker.restore(loaded);
    }

    private <T> T mutate(Supplier<T> operation) {
        Objects.requireNonNull(operation, "Receipt operation is required");
        requireWritable();
        List<CatalogPublicationReceipt> before = tracker.baselines();
        T result = operation.get();
        List<CatalogPublicationReceipt> after = tracker.baselines();
        if (after.equals(before)) {
            return result;
        }
        try {
            persist(activeBinding, after);
            return result;
        } catch (IOException | RuntimeException exception) {
            tracker.restore(before);
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Catalog publication receipts cannot be persisted: " + activeBinding.path(), exception);
        }
    }

    private void persist(Binding binding, Collection<CatalogPublicationReceipt> values) throws IOException {
        byte[] bytes = encode(values);
        validatePath(binding.path(), false);
        AtomicFiles.write(binding.path(), bytes);
    }

    private void initialize() {
        try {
            if (!Files.exists(activeBinding.path(), LinkOption.NOFOLLOW_LINKS) && bootstrapMissing) {
                persist(activeBinding, List.of());
            }
            if (Files.exists(activeBinding.path(), LinkOption.NOFOLLOW_LINKS)) {
                tracker.restore(read(activeBinding.path(), true));
            } else {
                tracker.restore(List.of());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Catalog publication receipts cannot be read: " + activeBinding.path(), exception);
        }
    }

    private List<CatalogPublicationReceipt> read(Path candidate, boolean required) throws IOException {
        validatePath(candidate, required);
        return decode(Files.readAllBytes(candidate));
    }

    private List<CatalogPublicationReceipt> readUnchecked(Path candidate, boolean required) {
        try {
            return read(candidate, required);
        } catch (IOException exception) {
            throw new IllegalStateException("Catalog publication receipts cannot be read: " + candidate, exception);
        }
    }

    private void requireWritable() {
        if (persistenceState == PersistenceState.QUIESCED) {
            throw new IllegalStateException("Catalog publication receipt persistence is quiesced; mutation rejected");
        }
        if (persistenceState == PersistenceState.CLOSED) {
            throw new IllegalStateException("Catalog publication receipt persistence is closed; mutation rejected");
        }
    }

    private void requireNotClosed() throws IOException {
        if (persistenceState == PersistenceState.CLOSED) {
            throw new IOException("Catalog publication receipt persistence is closed");
        }
    }

    private static Path requireParent(Path path) {
        Path normalized = MigrationPaths.requirePath(path, "receiptPath");
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Receipt path must have a parent directory");
        }
        return parent;
    }

    private static Path prepareScope(Path value) {
        Path normalized = MigrationPaths.requirePath(value, "scopeRoot");
        try {
            if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(normalized);
            }
            return MigrationPaths.requireDirectory(normalized, "scopeRoot");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Receipt scope cannot be prepared: " + normalized, exception);
        }
    }

    private static Path preparePath(Path scopeRoot, Path value) {
        Path normalized = MigrationPaths.requirePath(value, "receiptPath");
        Path parent = normalized.getParent();
        if (parent == null || !parent.equals(scopeRoot) || normalized.equals(scopeRoot)) {
            throw new IllegalArgumentException("Receipt path must be a direct child of its scope root");
        }
        try {
            validatePath(normalized, false);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Receipt path cannot be prepared: " + normalized, exception);
        }
        return normalized;
    }

    private static void validatePath(Path value, boolean required) throws IOException {
        Path normalized = MigrationPaths.requirePath(value, "receiptPath");
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Receipt path must have a parent directory");
        }
        MigrationPaths.requireDirectory(parent, "receipt directory");
        MigrationPaths.requireNoSymlinkTraversal(parent, normalized);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (required) {
                throw new IOException("Catalog publication receipt file does not exist: " + normalized);
            }
            return;
        }
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Catalog publication receipt path must be a regular file: " + normalized);
        }
    }

    private record Binding(Path path, long generation) {
        private Binding {
            path = MigrationPaths.requirePath(path, "receipt path");
            if (generation < 0) {
                throw new IllegalArgumentException("Receipt binding generation must not be negative");
            }
        }
    }

    private enum PersistenceState {
        OPEN,
        QUIESCED,
        CLOSED
    }

    private static byte[] encode(Collection<CatalogPublicationReceipt> values) {
        Objects.requireNonNull(values, "Receipt baselines are required");
        List<CatalogPublicationReceipt> sorted = values.stream()
            .map(value -> Objects.requireNonNull(value, "Receipt baseline cannot be null"))
            .sorted(Comparator.comparing(CatalogPublicationReceipt::sessionKey))
            .toList();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("kind", KIND);
        base.put("version", VERSION);
        base.put("receipts", sorted.stream().map(receipt -> encodeReceipt(receipt, VERSION)).toList());
        base.put("contentHash", CanonicalHash.sha256(JsonValue.fromJava(base)));
        return JsonValue.fromJava(base).canonicalBytes();
    }

    private static Map<String, Object> encodeReceipt(CatalogPublicationReceipt receipt, int version) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("sessionKey", receipt.sessionKey());
        if (version == VERSION) {
            encoded.put("ownerState", wireName(receipt.ownerState()));
            if (receipt.ownerState() == CatalogPublicationReceipt.OwnerState.CLAIMED) {
                encoded.put("ownerToken", CatalogPublicationReceipt.requireOwnerToken(receipt.ownerToken()));
            }
        }
        encoded.put("publicationKey", receipt.publicationKey().canonicalText());
        encoded.put("revision", receipt.revision());
        encoded.put("dispatch", wireName(receipt.dispatch()));
        encoded.put("clientReceipt", wireName(receipt.clientReceipt()));
        encoded.put("cacheApplication", wireName(receipt.cacheApplication()));
        encoded.put("diagnosticCode", receipt.diagnosticCode());
        return encoded;
    }

    private static List<CatalogPublicationReceipt> decode(byte[] bytes) {
        JsonValue value = CanonicalCodec.decode(bytes);
        Map<String, Object> document = map(value.toJava(), "catalog publication receipt document");
        requireKnownFields(document, DOCUMENT_FIELDS, "catalog publication receipt document");
        if (!KIND.equals(text(document.get("kind"), "kind"))) {
            throw new IllegalArgumentException("Unsupported catalog publication receipt document");
        }
        int version = integer(document.get("version"), "version");
        if (version != 1 && version != VERSION) {
            throw new IllegalArgumentException("Unsupported catalog publication receipt document version: " + version);
        }
        Object rawReceipts = document.get("receipts");
        if (!(rawReceipts instanceof List<?> values)) {
            throw new IllegalArgumentException("Catalog publication receipts must be an array");
        }
        List<CatalogPublicationReceipt> receipts = new ArrayList<>(values.size());
        Set<String> sessionKeys = new HashSet<>();
        for (Object raw : values) {
            CatalogPublicationReceipt receipt = decodeReceipt(map(raw, "catalog publication receipt"), version);
            if (!sessionKeys.add(receipt.sessionKey())) {
                throw new IllegalArgumentException("Duplicate catalog publication receipt session: " + receipt.sessionKey());
            }
            receipts.add(receipt);
        }
        List<CatalogPublicationReceipt> sorted = receipts.stream()
            .sorted(Comparator.comparing(CatalogPublicationReceipt::sessionKey))
            .toList();
        if (!receipts.equals(sorted)) {
            throw new IllegalArgumentException("Catalog publication receipt order is not canonical");
        }
        Map<String, Object> normalizedBase = new LinkedHashMap<>();
        normalizedBase.put("kind", KIND);
        normalizedBase.put("version", version);
        normalizedBase.put("receipts", receipts.stream().map(receipt -> encodeReceipt(receipt, version)).toList());
        String expectedHash = CanonicalHash.sha256(JsonValue.fromJava(normalizedBase));
        String declaredHash = text(document.get("contentHash"), "contentHash");
        if (!expectedHash.equals(declaredHash)) {
            throw new IllegalArgumentException("Catalog publication receipt content hash does not match canonical content");
        }
        Map<String, Object> normalizedDocument = new LinkedHashMap<>(normalizedBase);
        normalizedDocument.put("contentHash", expectedHash);
        byte[] normalizedBytes = JsonValue.fromJava(normalizedDocument).canonicalBytes();
        if (!Arrays.equals(normalizedBytes, bytes)) {
            throw new IllegalArgumentException("Catalog publication receipt bytes do not match normalized canonical content");
        }
        return List.copyOf(receipts);
    }

    private static CatalogPublicationReceipt decodeReceipt(Map<String, Object> value, int version) {
        CatalogPublicationReceipt.OwnerState ownerState;
        String ownerToken;
        if (version == 1) {
            requireKnownFields(value, LEGACY_RECEIPT_FIELDS, "catalog publication receipt");
            ownerState = CatalogPublicationReceipt.OwnerState.LEGACY_UNCLAIMED;
            ownerToken = null;
        } else {
            requireKnownKeys(value, V2_CLAIMED_RECEIPT_FIELDS, "catalog publication receipt");
            ownerState = enumValue(value.get("ownerState"), CatalogPublicationReceipt.OwnerState.class, "ownerState");
            Set<String> expectedFields = ownerState == CatalogPublicationReceipt.OwnerState.CLAIMED
                ? V2_CLAIMED_RECEIPT_FIELDS : V2_LEGACY_RECEIPT_FIELDS;
            requireKnownFields(value, expectedFields, "catalog publication receipt");
            ownerToken = ownerState == CatalogPublicationReceipt.OwnerState.CLAIMED
                ? CatalogPublicationReceipt.requireOwnerToken(canonicalText(value.get("ownerToken"), "ownerToken"))
                : null;
        }
        String sessionKey = canonicalText(value.get("sessionKey"), "sessionKey");
        CatalogCacheKey publicationKey = CatalogCacheKey.parseCanonicalText(canonicalText(value.get("publicationKey"), "publicationKey"));
        long revision = longValue(value.get("revision"), "revision");
        CatalogPublicationReceipt.DispatchState dispatch = enumValue(value.get("dispatch"), CatalogPublicationReceipt.DispatchState.class, "dispatch");
        CatalogPublicationReceipt.ClientReceiptState clientReceipt = enumValue(value.get("clientReceipt"), CatalogPublicationReceipt.ClientReceiptState.class, "clientReceipt");
        CatalogPublicationReceipt.CacheApplicationState cacheApplication = enumValue(value.get("cacheApplication"), CatalogPublicationReceipt.CacheApplicationState.class, "cacheApplication");
        String diagnosticCode = canonicalText(value.get("diagnosticCode"), "diagnosticCode");
        return new CatalogPublicationReceipt(sessionKey, ownerToken, ownerState, publicationKey, revision, dispatch,
            clientReceipt, cacheApplication, diagnosticCode);
    }

    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(field + " keys must be strings");
            }
            result.put(text, item);
        });
        return result;
    }

    private static void requireKnownFields(Map<String, Object> value, Set<String> known, String field) {
        requireKnownKeys(value, known, field);
        for (String key : known) {
            if (!value.containsKey(key)) {
                throw new IllegalArgumentException(field + " is missing required field: " + key);
            }
        }
    }

    private static void requireKnownKeys(Map<String, Object> value, Set<String> known, String field) {
        for (String key : value.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException(field + " contains unknown field: " + key);
            }
        }
    }

    private static String canonicalText(Object value, String field) {
        String text = text(value, field);
        if (!text.equals(text.strip())) {
            throw new IllegalArgumentException(field + " must be canonical text");
        }
        return text;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return text;
    }

    private static long longValue(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private static int integer(Object value, String field) {
        long number = longValue(value, field);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is outside integer range");
        }
        return (int) number;
    }

    private static <E extends Enum<E>> E enumValue(Object value, Class<E> type, String field) {
        String wire = canonicalText(value, field);
        try {
            return Enum.valueOf(type, wire.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown " + field + ": " + wire, exception);
        }
    }

    private static String wireName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
