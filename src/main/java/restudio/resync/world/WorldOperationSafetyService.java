package restudio.resync.world;

import restudio.resync.Log;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class WorldOperationSafetyService {
    private static final int DEFAULT_MAX_RECORDS = 500;
    private static final long CONFIRMATION_TTL_MILLIS = 120_000L;
    private final int maxRecords;
    private final Object persistenceMonitor = new Object();
    private final Map<String, ConfirmationToken> confirmations = new LinkedHashMap<>();
    private final Map<String, WorldOperationResult> operationStatuses = new LinkedHashMap<>();
    private final Map<WorldOperationAuditRecord, PendingLease> pendingRecords = new IdentityHashMap<>();
    private final List<WorldOperationAuditRecord> auditRecords = new ArrayList<>();
    private volatile ActiveBinding activeBinding;
    private volatile WorldExternalPersistenceCapability externalPersistenceCapability;
    private boolean quiesced;
    private boolean quiescing;
    private boolean quiesceFailed;
    private boolean closing;
    private boolean closed;
    private IOException persistenceFailure;

    private static final String DOCUMENT_KIND = "resync.world-audit";
    private static final int DOCUMENT_VERSION = 2;
    private static final long LEASE_DRAIN_TIMEOUT_MILLIS = 2_000L;
    static final int MAXIMUM_AUDIT_FILE_BYTES = 16 * 1024 * 1024;
    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> DOCUMENT_FIELDS = Set.of("contentHash", "kind", "records", "version");
    private static final Set<String> RECORD_FIELDS = Set.of("action", "actorClientId", "auditId", "backupAvailable", "durationMillis", "failureReason", "finishedAt", "message", "operationId", "parameters", "safetyBackupId", "startedAt", "success", "targetWorld");

    public WorldOperationSafetyService(Path auditFile) {
        this(auditFile, DEFAULT_MAX_RECORDS);
    }

    public WorldOperationSafetyService(Path auditFile, int maxRecords) {
        this.maxRecords = Math.max(50, maxRecords);
        externalPersistenceCapability = WorldExternalPersistenceCapability.unavailable();
        Path root = MigrationPaths.requirePath(auditFile, "auditFile");
        try {
            Files.createDirectories(requireParent(root));
            DecodedRecords decoded = readRecords(root, false);
            synchronized (persistenceMonitor) {
                activeBinding = new ActiveBinding(root, 0L);
                auditRecords.addAll(decoded.records());
                boolean exists = Files.exists(root, LinkOption.NOFOLLOW_LINKS);
                if (!exists) {
                    writeRecords(root, auditRecords);
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to initialize world audit persistence", exception);
        }
    }

    public boolean isDangerous(String action) {
        if (action == null) {
            return false;
        }
        return switch (action) {
            case "deleteWorld", "unloadWorld", "cloneWorld", "purgeWorld", "createInventoryGroup", "updateInventoryGroup", "deleteInventoryGroup",
                 "createPortal", "resizePortal", "deletePortal", "setPortalEnabled", "setPortalDestination", "setPortalBounds", "createSignPortal",
                 "deleteSignPortal", "createWorld" -> true;
            default -> false;
        };
    }

    public WorldOperationResult requireConfirmation(String action, String worldName, String actorClientId, Map<String, Object> parameters) {
        synchronized (persistenceMonitor) {
            requireMutationAdmission();
            String token = UUID.randomUUID().toString().replace("-", "");
            long now = System.currentTimeMillis();
            long expiresAt = now + CONFIRMATION_TTL_MILLIS;
            confirmations.put(token, new ConfirmationToken(token, action, worldName, actorClientId, expiresAt));
            WorldOperationResult result = WorldOperationResult.failure(action, worldName, "ConfirmationRequired");
            result.setRequiresConfirmation(true);
            result.setActorClientId(actorClientId);
            result.setStartedAt(now);
            result.setFinishedAt(now);
            result.withData("confirmationToken", token);
            result.withData("expiresAt", expiresAt);
            result.withData("parameters", parameters == null ? Map.of() : new LinkedHashMap<>(parameters));
            return result;
        }
    }

    public boolean consumeConfirmation(String token, String action, String worldName, String actorClientId) {
        synchronized (persistenceMonitor) {
            requireMutationAdmission();
            if (token == null || token.isBlank()) {
                return false;
            }
            ConfirmationToken confirmation = confirmations.remove(token);
            if (confirmation == null || confirmation.expiresAt < System.currentTimeMillis()) {
                return false;
            }
            if (!safeEquals(confirmation.action, action)) {
                return false;
            }
            if (confirmation.worldName != null && worldName != null && !safeEquals(confirmation.worldName, worldName)) {
                return false;
            }
            return confirmation.actorClientId == null || actorClientId == null || safeEquals(confirmation.actorClientId, actorClientId);
        }
    }

    public WorldOperationAuditRecord begin(String operationId, String action, String actorClientId, String targetWorld, Map<String, Object> parameters) {
        synchronized (persistenceMonitor) {
            requireMutationAdmission();
            requireText(operationId, "operationId");
            requireText(action, "action");
            JsonValue normalizedParameters = JsonValue.fromJava(parameters == null ? Map.of() : parameters);
            if (!(normalizedParameters instanceof JsonValue.JsonObject)) {
                throw new IllegalArgumentException("World audit parameters must be an object");
            }
            WorldOperationAuditRecord record = new WorldOperationAuditRecord();
            long now = System.currentTimeMillis();
            record.setAuditId(UUID.randomUUID().toString());
            record.setOperationId(operationId);
            record.setAction(action);
            record.setActorClientId(actorClientId);
            record.setTargetWorld(targetWorld);
            Map<String, Object> safeParameters;
            try {
                safeParameters = copyParameterMap(parameters == null ? Map.of() : parameters);
                record.setParameters(copyParameterMap(safeParameters));
            } catch (IOException exception) {
                throw new IllegalArgumentException("World audit parameters are not JSON-compatible", exception);
            }
            record.setStartedAt(now);
            record.setBackupAvailable(false);
            try {
                pendingRecords.put(record, new PendingLease(activeBinding.generation(), record.getAuditId(), record.getOperationId(),
                    record.getAction(), record.getActorClientId(), record.getTargetWorld(), copyParameterMap(safeParameters), record.getStartedAt()));
            } catch (IOException exception) {
                throw new IllegalArgumentException("World audit parameters are not JSON-compatible", exception);
            }
            return record;
        }
    }

    public void finish(WorldOperationAuditRecord record, WorldOperationResult result, Throwable failure) {
        if (record == null) {
            return;
        }
        synchronized (persistenceMonitor) {
            if (closed) {
                throw new IllegalStateException("World audit persistence is closed");
            }
            PendingLease pending = pendingRecords.get(record);
            if (pending == null) {
                requireMutationAdmission();
                throw new IllegalStateException("World audit record was not admitted");
            }
            if (pending.generation() != activeBinding.generation()) {
                throw new IllegalStateException("World audit record belongs to an inactive persistence generation");
            }
            WorldOperationResult completedResult = result == null ? null : copyResult(result);
            pendingRecords.remove(record);
            long now = System.currentTimeMillis();
            WorldOperationAuditRecord completed = new WorldOperationAuditRecord();
            completed.setAuditId(pending.auditId());
            completed.setOperationId(pending.operationId());
            completed.setAction(pending.action());
            completed.setActorClientId(pending.actorClientId());
            completed.setTargetWorld(pending.targetWorld());
            completed.setParameters(pending.parameters());
            completed.setStartedAt(pending.startedAt());
            completed.setBackupAvailable(false);
            completed.setFinishedAt(now);
            completed.setDurationMillis(Math.max(0L, now - pending.startedAt()));
            completed.setSuccess(completedResult != null && completedResult.isSuccess() && failure == null);
            completed.setMessage(completedResult != null ? completedResult.getMessage() : null);
            completed.setFailureReason(completed.isSuccess() ? null : failure != null ? failureMessage(failure) : completedResult != null ? failureMessage(completedResult.getMessage()) : "Operation failed");
            completed.setSafetyBackupId(completedResult != null ? completedResult.getSafetyBackupId() : null);
            completed.setBackupAvailable(completed.getSafetyBackupId() != null && !completed.getSafetyBackupId().isBlank());
            record.setAuditId(pending.auditId());
            record.setOperationId(pending.operationId());
            record.setAction(pending.action());
            record.setActorClientId(pending.actorClientId());
            record.setTargetWorld(pending.targetWorld());
            record.setParameters(pending.parameters());
            record.setStartedAt(pending.startedAt());
            record.setFinishedAt(now);
            record.setDurationMillis(completed.getDurationMillis());
            record.setSuccess(completed.isSuccess());
            record.setMessage(completed.getMessage());
            record.setFailureReason(completed.getFailureReason());
            record.setSafetyBackupId(completed.getSafetyBackupId());
            record.setBackupAvailable(completed.isBackupAvailable());
            auditRecords.add(completed);
            auditRecords.sort(recordComparator());
            while (auditRecords.size() > maxRecords) {
                auditRecords.removeLast();
            }
            try {
                writeRecords(activeBinding.file(), auditRecords);
            } catch (IOException exception) {
                persistenceFailure = exception;
                Log.warn("Failed to save world audit records: " + exception.getMessage());
            } finally {
                persistenceMonitor.notifyAll();
            }
        }
    }

    public void rememberStatus(WorldOperationResult result) {
        synchronized (persistenceMonitor) {
            if (result == null || result.getOperationId() == null || result.getOperationId().isBlank()) {
                requireMutationAdmission();
                return;
            }
            if (closed || ((quiesced || quiescing || quiesceFailed || closing) && !hasPendingOperation(result.getOperationId()))) {
                requireMutationAdmission();
            }
            operationStatuses.put(result.getOperationId(), copyResult(result));
        }
    }

    public WorldOperationResult getStatus(String operationId) {
        synchronized (persistenceMonitor) {
            WorldOperationResult result = operationStatuses.get(operationId);
            return result == null ? null : copyResult(result);
        }
    }

    public List<WorldOperationAuditRecord> snapshot(int limit) {
        synchronized (persistenceMonitor) {
            awaitSnapshotLeases();
            int count = Math.min(limit <= 0 ? 100 : limit, auditRecords.size());
            return copyRecords(auditRecords.subList(0, count));
        }
    }

    public WorldOperationResult unavailableBackupResult(String action, String worldName, String actorClientId) {
        WorldOperationResult result = WorldOperationResult.failure(action, worldName, "BackupUnavailable");
        long now = System.currentTimeMillis();
        result.setActorClientId(actorClientId);
        result.setStartedAt(now);
        result.setFinishedAt(now);
        result.withData("backupAvailable", false);
        result.withData("reason", "No ReSync server-side backup provider is configured");
        return result;
    }

    public Path persistenceRoot() {
        return activeBinding.file();
    }

    public Path path() {
        return persistenceRoot();
    }

    public long persistenceGeneration() {
        return activeBinding.generation();
    }

    public long generation() {
        return persistenceGeneration();
    }

    public int activeOperationCount() {
        synchronized (persistenceMonitor) {
            return pendingRecords.size();
        }
    }

    public List<String> activeOperationIds() {
        synchronized (persistenceMonitor) {
            return pendingRecords.values().stream()
                .map(PendingLease::operationId)
                .filter(operationId -> operationId != null && !operationId.isBlank())
                .toList();
        }
    }

    public boolean isOperationActive(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return false;
        }
        synchronized (persistenceMonitor) {
            return pendingRecords.values().stream().anyMatch(lease -> operationId.equals(lease.operationId()));
        }
    }

    public void bindExternalPersistence(WorldExternalPersistenceCapability capability) {
        externalPersistenceCapability = Objects.requireNonNull(capability, "capability");
    }

    public void clearExternalPersistence() {
        externalPersistenceCapability = WorldExternalPersistenceCapability.unavailable();
    }

    public WorldExternalPersistenceCapability externalPersistence() {
        return externalPersistenceCapability;
    }

    public WorldExternalPersistenceCapability.MutationLease acquireExternalMutation(String action, String worldName) {
        return externalPersistence().acquireReplacementMutation(action, worldName);
    }

    public WorldExternalPersistenceCapability.MutationLease acquireNormalMutation(String action, String worldName) {
        return externalPersistence().acquireNormalMutation(action, worldName);
    }

    public boolean isQuiesced() {
        synchronized (persistenceMonitor) {
            return quiesced;
        }
    }

    public boolean isClosed() {
        synchronized (persistenceMonitor) {
            return closed;
        }
    }

    public void flushPersistence() throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            ensureHealthy();
            try {
                writeRecords(activeBinding.file(), auditRecords);
            } catch (IOException exception) {
                persistenceFailure = exception;
                throw exception;
            }
        }
    }

    public void quiescePersistence() throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            if (quiesced) {
                ensureHealthy();
                return;
            }
            quiescing = true;
            quiesceFailed = false;
            try {
                awaitLeases();
                ensureHealthy();
                writeRecords(activeBinding.file(), auditRecords);
                quiesced = true;
                quiescing = false;
            } catch (IOException | RuntimeException exception) {
                quiesceFailed = true;
                quiescing = true;
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw exception;
            } finally {
                persistenceMonitor.notifyAll();
            }
        }
    }

    public void resumePersistence() throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            if (closing || (quiescing && !quiesceFailed)) {
                throw new IOException("World audit persistence lifecycle transition is active");
            }
            if (!quiesced && !quiesceFailed) {
                return;
            }
            if (!pendingRecords.isEmpty()) {
                throw new IOException("World audit persistence still has admitted operations");
            }
            if (persistenceFailure != null) {
                throw new IOException("World audit persistence is unavailable", persistenceFailure);
            }
            List<WorldOperationAuditRecord> persisted = readRecords(activeBinding.file(), true).records();
            if (!recordsEqual(persisted, auditRecords)) {
                throw new IOException("World audit records are out of sync with the active file");
            }
            quiesced = false;
            quiescing = false;
            quiesceFailed = false;
        }
    }

    public void rebindPersistence(Path candidateFile) throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            if (closing || quiescing || !quiesced) {
                throw new IOException("World audit persistence must be quiesced before rebind");
            }
            if (!pendingRecords.isEmpty()) {
                throw new IOException("World audit persistence has active operation records");
            }
            Path nextFile = MigrationPaths.requirePath(candidateFile, "candidateFile");
            DecodedRecords decoded = readRecords(nextFile, true);
            long generation;
            try {
                generation = Math.addExact(activeBinding.generation(), 1L);
            } catch (ArithmeticException exception) {
                throw new IOException("World audit persistence generation overflowed", exception);
            }
            ActiveBinding nextBinding = new ActiveBinding(nextFile, generation);
            auditRecords.clear();
            auditRecords.addAll(decoded.records());
            confirmations.clear();
            operationStatuses.clear();
            activeBinding = nextBinding;
        }
    }

    public void healthCheckPersistence() throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            ensureHealthy();
            List<WorldOperationAuditRecord> persisted = readRecords(activeBinding.file(), true).records();
            if (!recordsEqual(persisted, auditRecords)) {
                throw new IOException("World audit records are out of sync with the active file");
            }
        }
    }

    public void closePersistence() throws IOException {
        synchronized (persistenceMonitor) {
            if (closed) {
                return;
            }
            if (closing) {
                throw new IOException("World audit persistence shutdown is already in progress");
            }
            closing = true;
            try {
                if (!quiesced) {
                    quiescing = true;
                    quiesceFailed = false;
                    awaitLeases();
                    ensureHealthy();
                    writeRecords(activeBinding.file(), auditRecords);
                    quiesced = true;
                    quiescing = false;
                } else {
                    ensureHealthy();
                    List<WorldOperationAuditRecord> persisted = readRecords(activeBinding.file(), true).records();
                    if (!recordsEqual(persisted, auditRecords)) {
                        throw new IOException("World audit records are out of sync with the active file");
                    }
                }
                if (!pendingRecords.isEmpty()) {
                    throw new IOException("World audit persistence has active operation records");
                }
                closed = true;
            } catch (IOException | RuntimeException exception) {
                quiesceFailed = true;
                quiescing = true;
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw exception;
            } finally {
                if (!closed) {
                    closing = false;
                }
                persistenceMonitor.notifyAll();
            }
        }
    }

    private DecodedRecords readRecords(Path file, boolean required) throws IOException {
        Path normalized = MigrationPaths.requirePath(file, "auditFile");
        if (Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (required) {
                throw new IOException("World audit file does not exist: " + normalized);
            }
            return new DecodedRecords(new ArrayList<>());
        }
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World audit file is not a regular file: " + normalized);
        }
        try {
            byte[] bytes = readBoundedFile(normalized);
            JsonValue value;
            try {
                value = CanonicalCodec.decode(bytes);
            } catch (IllegalArgumentException canonicalFailure) {
                throw new IOException("World audit file is not canonical: " + normalized, canonicalFailure);
            }
            if (value instanceof JsonValue.JsonObject) {
                return decodeCanonicalRecords(value);
            }
            throw new IOException("World audit file is not a versioned document: " + normalized);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("World audit file is malformed: " + normalized, exception);
        }
    }

    private DecodedRecords decodeCanonicalRecords(JsonValue value) throws IOException {
        if (!(value instanceof JsonValue.JsonObject document)) {
            throw new IOException("World audit document must be an object");
        }
        requireFields(document.fields(), DOCUMENT_FIELDS, "world audit document");
        if (!DOCUMENT_KIND.equals(requiredString(document, "kind"))) {
            throw new IOException("World audit document kind is unsupported");
        }
        long version = requiredLong(document, "version");
        if (version != DOCUMENT_VERSION) {
            throw new IOException("World audit document version is unsupported");
        }
        JsonValue recordsValue = requiredField(document, "records");
        if (!(recordsValue instanceof JsonValue.JsonArray recordsArray)) {
            throw new IOException("World audit records must be an array");
        }
        String expectedHash = requiredString(document, "contentHash");
        if (!HASH_PATTERN.matcher(expectedHash).matches()) {
            throw new IOException("World audit content hash is invalid");
        }
        List<WorldOperationAuditRecord> records = new ArrayList<>();
        for (JsonValue item : recordsArray.values()) {
            WorldOperationAuditRecord record = decodeCanonicalRecord(item);
            records.add(record);
        }
        if (!expectedHash.equals(contentHash(records, Math.toIntExact(version)))) {
            throw new IOException("World audit content hash does not match canonical records");
        }
        validateRecords(records);
        List<WorldOperationAuditRecord> ordered = orderedRecords(records);
        for (int index = 0; index < records.size(); index++) {
            if (!Objects.equals(records.get(index).getAuditId(), ordered.get(index).getAuditId())) {
                throw new IOException("World audit records are not in canonical order");
            }
        }
        return new DecodedRecords(records);
    }

    private WorldOperationAuditRecord decodeCanonicalRecord(JsonValue value) throws IOException {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IOException("World audit records must be objects");
        }
        requireFields(object.fields(), RECORD_FIELDS, "world audit record");
        WorldOperationAuditRecord record = new WorldOperationAuditRecord();
        record.setAuditId(requiredString(object, "auditId"));
        record.setOperationId(requiredString(object, "operationId"));
        record.setAction(requiredString(object, "action"));
        record.setActorClientId(nullableString(object, "actorClientId"));
        record.setTargetWorld(nullableString(object, "targetWorld"));
        JsonValue parameters = requiredField(object, "parameters");
        if (!(parameters instanceof JsonValue.JsonObject parameterObject)) {
            throw new IOException("World audit parameters must be an object");
        }
        Object parameterJava = parameterObject.toJava();
        if (!(parameterJava instanceof Map<?, ?> parameterMap)) {
            throw new IOException("World audit parameters are malformed");
        }
        record.setParameters(copyParameterMap(parameterMap));
        record.setSuccess(requiredBoolean(object, "success"));
        record.setMessage(nullableString(object, "message"));
        record.setFailureReason(nullableString(object, "failureReason"));
        record.setSafetyBackupId(nullableString(object, "safetyBackupId"));
        record.setBackupAvailable(requiredBoolean(object, "backupAvailable"));
        record.setStartedAt(requiredLong(object, "startedAt"));
        record.setFinishedAt(requiredLong(object, "finishedAt"));
        record.setDurationMillis(requiredLong(object, "durationMillis"));
        return record;
    }

    private void validateRecords(List<WorldOperationAuditRecord> records) throws IOException {
        Set<String> auditIds = new HashSet<>();
        Set<String> caseFoldedIds = new HashSet<>();
        for (WorldOperationAuditRecord record : orderedRecords(records)) {
            validateRecord(record, auditIds, caseFoldedIds);
        }
    }

    private void validateRecord(WorldOperationAuditRecord record, Set<String> auditIds, Set<String> caseFoldedIds) throws IOException {
        if (record == null || record.getAuditId() == null || record.getAuditId().isBlank()
            || record.getOperationId() == null || record.getOperationId().isBlank()
            || record.getAction() == null || record.getAction().isBlank()) {
            throw new IOException("World audit record has missing identity or action");
        }
        requireText(record.getAuditId(), "auditId");
        requireText(record.getOperationId(), "operationId");
        requireText(record.getAction(), "action");
        requireNullableText(record.getActorClientId(), "actorClientId");
        requireNullableText(record.getTargetWorld(), "targetWorld");
        requireNullableText(record.getMessage(), "message");
        requireNullableText(record.getFailureReason(), "failureReason");
        requireNullableText(record.getSafetyBackupId(), "safetyBackupId");
        if (!auditIds.add(record.getAuditId()) || !caseFoldedIds.add(record.getAuditId().toLowerCase(Locale.ROOT))) {
            throw new IOException("World audit record has a duplicate audit ID: " + record.getAuditId());
        }
        if (record.getStartedAt() < 0L || record.getFinishedAt() < record.getStartedAt() || record.getDurationMillis() < 0L) {
            throw new IOException("World audit record has invalid timing");
        }
        try {
            if (Math.subtractExact(record.getFinishedAt(), record.getStartedAt()) != record.getDurationMillis()) {
                throw new IOException("World audit record duration does not match timestamps");
            }
        } catch (ArithmeticException exception) {
            throw new IOException("World audit record timing overflowed", exception);
        }
        if (record.isSuccess() && record.getFailureReason() != null) {
            throw new IOException("Successful world audit records cannot have failure diagnostics");
        }
        if (!record.isSuccess() && (record.getFailureReason() == null || record.getFailureReason().isBlank())) {
            throw new IOException("Failed world audit records require failure diagnostics");
        }
        boolean hasBackupId = record.getSafetyBackupId() != null && !record.getSafetyBackupId().isBlank();
        if (record.isBackupAvailable() != hasBackupId) {
            throw new IOException("World audit backup availability must match the backup ID");
        }
    }

    private void writeRecords(Path file, List<WorldOperationAuditRecord> records) throws IOException {
        Path normalized = MigrationPaths.requirePath(file, "auditFile");
        Path parent = requireParent(normalized);
        Files.createDirectories(parent);
        AtomicFiles.write(normalized, canonicalBytes(records));
    }

    private boolean recordsEqual(List<WorldOperationAuditRecord> left, List<WorldOperationAuditRecord> right) {
        try {
            return Arrays.equals(canonicalBytes(left), canonicalBytes(right));
        } catch (IOException exception) {
            return false;
        }
    }

    private void requireMutationAdmission() {
        if (closed || closing) {
            throw new IllegalStateException("World audit persistence is closed");
        }
        if (quiesced || quiescing || quiesceFailed || persistenceFailure != null) {
            throw new IllegalStateException("World audit persistence is quiesced");
        }
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("World audit persistence is closed");
        }
    }

    private boolean hasPendingOperation(String operationId) {
        return pendingRecords.values().stream().anyMatch(lease -> operationId.equals(lease.operationId()));
    }

    private void awaitLeases() throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(LEASE_DRAIN_TIMEOUT_MILLIS);
        while (!pendingRecords.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new IOException("World audit persistence could not drain admitted operations");
            }
            try {
                long millis = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining));
                persistenceMonitor.wait(Math.min(LEASE_DRAIN_TIMEOUT_MILLIS, millis));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("World audit persistence lease drain was interrupted", exception);
            }
        }
    }

    private void awaitSnapshotLeases() {
        if (!quiescing && !quiesceFailed && !closing) {
            return;
        }
        try {
            awaitLeases();
            ensureHealthy();
        } catch (IOException exception) {
            throw new IllegalStateException("World audit snapshot is unavailable while persistence is not ready", exception);
        }
    }

    private void ensureHealthy() throws IOException {
        if (persistenceFailure != null) {
            throw new IOException("World audit persistence is unavailable", persistenceFailure);
        }
        if (quiesceFailed) {
            throw new IOException("World audit persistence lease drain failed");
        }
    }

    private byte[] canonicalBytes(List<WorldOperationAuditRecord> records) throws IOException {
        validateRecords(records);
        List<Map<String, Object>> values = new ArrayList<>();
        for (WorldOperationAuditRecord record : orderedRecords(records)) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("auditId", record.getAuditId());
            value.put("operationId", record.getOperationId());
            value.put("action", record.getAction());
            value.put("actorClientId", record.getActorClientId());
            value.put("targetWorld", record.getTargetWorld());
            value.put("parameters", record.getParameters() == null ? Map.of() : new LinkedHashMap<>(record.getParameters()));
            value.put("success", record.isSuccess());
            value.put("message", record.getMessage());
            value.put("failureReason", record.getFailureReason());
            value.put("safetyBackupId", record.getSafetyBackupId());
            value.put("backupAvailable", record.isBackupAvailable());
            value.put("startedAt", record.getStartedAt());
            value.put("finishedAt", record.getFinishedAt());
            value.put("durationMillis", record.getDurationMillis());
            values.add(value);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", DOCUMENT_KIND);
        body.put("version", DOCUMENT_VERSION);
        body.put("records", values);
        try {
            body.put("contentHash", CanonicalHash.sha256(JsonValue.fromJava(body)));
            return JsonValue.fromJava(body).canonicalBytes();
        } catch (RuntimeException exception) {
            throw new IOException("World audit document is not canonical JSON", exception);
        }
    }

    private String contentHash(List<WorldOperationAuditRecord> records) throws IOException {
        return contentHash(records, DOCUMENT_VERSION);
    }

    private String contentHash(List<WorldOperationAuditRecord> records, int version) throws IOException {
        if (version <= 0) {
            throw new IOException("World audit content hash version is invalid");
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (WorldOperationAuditRecord record : orderedRecords(records)) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("auditId", record.getAuditId());
            value.put("operationId", record.getOperationId());
            value.put("action", record.getAction());
            value.put("actorClientId", record.getActorClientId());
            value.put("targetWorld", record.getTargetWorld());
            value.put("parameters", record.getParameters() == null ? Map.of() : new LinkedHashMap<>(record.getParameters()));
            value.put("success", record.isSuccess());
            value.put("message", record.getMessage());
            value.put("failureReason", record.getFailureReason());
            value.put("safetyBackupId", record.getSafetyBackupId());
            value.put("backupAvailable", record.isBackupAvailable());
            value.put("startedAt", record.getStartedAt());
            value.put("finishedAt", record.getFinishedAt());
            value.put("durationMillis", record.getDurationMillis());
            values.add(value);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", DOCUMENT_KIND);
        body.put("version", version);
        body.put("records", values);
        try {
            return CanonicalHash.sha256(JsonValue.fromJava(body));
        } catch (RuntimeException exception) {
            throw new IOException("World audit content hash cannot be computed", exception);
        }
    }

    private byte[] readBoundedFile(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAXIMUM_AUDIT_FILE_BYTES) {
            throw new IOException("World audit file exceeds the maximum byte budget: " + file);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAXIMUM_AUDIT_FILE_BYTES + 1);
            if (bytes.length > MAXIMUM_AUDIT_FILE_BYTES) {
                throw new IOException("World audit file exceeds the maximum byte budget: " + file);
            }
            return bytes;
        }
    }

    private void requireFields(Map<String, JsonValue> fields, Set<String> expected, String label) throws IOException {
        if (!fields.keySet().equals(expected)) {
            Set<String> unknown = new HashSet<>(fields.keySet());
            unknown.removeAll(expected);
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(fields.keySet());
            throw new IOException(label + " fields are invalid; unknown=" + unknown + ", missing=" + missing);
        }
    }

    private JsonValue requiredField(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = object.value(name);
        if (value == null) {
            throw new IOException("Required world audit field is missing: " + name);
        }
        return value;
    }

    private String requiredString(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = requiredField(object, name);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IOException("World audit field must be a string: " + name);
        }
        return string.value();
    }

    private String nullableString(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = requiredField(object, name);
        if (value instanceof JsonValue.JsonNull) {
            return null;
        }
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IOException("World audit field must be a string or null: " + name);
        }
        return string.value();
    }

    private boolean requiredBoolean(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = requiredField(object, name);
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw new IOException("World audit field must be a boolean: " + name);
        }
        return booleanValue.value();
    }

    private long requiredLong(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = requiredField(object, name);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IOException("World audit field must be an integer: " + name);
        }
        return exactLong(number.value(), name);
    }

    private Map<String, Object> copyParameterMap(Map<?, ?> source) throws IOException {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException("World audit parameter keys must be strings");
            }
            copy.put(key, copyParameterValue(entry.getValue()));
        }
        return copy;
    }

    private Object copyParameterValue(Object value) throws IOException {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return copyParameterMap(map);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            for (Object item : iterable) {
                copy.add(copyParameterValue(item));
            }
            return copy;
        }
        throw new IOException("World audit parameter value is not JSON-compatible");
    }

    private long exactLong(BigDecimal value, String name) throws IOException {
        if (value.scale() > 0) {
            throw new IOException("World audit field must be an integer: " + name);
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IOException("World audit integer is out of range: " + name, exception);
        }
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("World audit " + name + " must be nonblank canonical text");
        }
    }

    private void requireNullableText(String value, String name) throws IOException {
        if (value != null && (value.isBlank() || !value.equals(value.strip()))) {
            throw new IOException("World audit " + name + " must be canonical text");
        }
    }

    private String failureMessage(Throwable failure) {
        if (failure == null) {
            return "Operation failed";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private String failureMessage(String message) {
        return message == null || message.isBlank() ? "Operation failed" : message;
    }

    private List<WorldOperationAuditRecord> orderedRecords(List<WorldOperationAuditRecord> records) {
        return copyRecords(records).stream().sorted(recordComparator()).toList();
    }

    private Comparator<WorldOperationAuditRecord> recordComparator() {
        return Comparator.comparingLong(WorldOperationAuditRecord::getStartedAt).reversed()
            .thenComparing(WorldOperationAuditRecord::getAuditId, CanonicalJson::compareCodePoints);
    }

    private record DecodedRecords(List<WorldOperationAuditRecord> records) {
    }

    private Path requireParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("World audit file has no parent: " + file);
        }
        return parent;
    }

    private List<WorldOperationAuditRecord> copyRecords(List<WorldOperationAuditRecord> source) {
        List<WorldOperationAuditRecord> copy = new ArrayList<>();
        if (source != null) {
            for (WorldOperationAuditRecord record : source) {
                copy.add(copyRecord(record));
            }
        }
        return copy;
    }

    private WorldOperationAuditRecord copyRecord(WorldOperationAuditRecord source) {
        WorldOperationAuditRecord copy = new WorldOperationAuditRecord();
        if (source == null) {
            return copy;
        }
        copy.setAuditId(source.getAuditId());
        copy.setOperationId(source.getOperationId());
        copy.setAction(source.getAction());
        copy.setActorClientId(source.getActorClientId());
        copy.setTargetWorld(source.getTargetWorld());
        try {
            copy.setParameters(source.getParameters() == null ? Map.of() : copyParameterMap(source.getParameters()));
        } catch (IOException exception) {
            throw new IllegalStateException("World audit parameters are not JSON-compatible", exception);
        }
        copy.setSuccess(source.isSuccess());
        copy.setMessage(source.getMessage());
        copy.setFailureReason(source.getFailureReason());
        copy.setSafetyBackupId(source.getSafetyBackupId());
        copy.setBackupAvailable(source.isBackupAvailable());
        copy.setStartedAt(source.getStartedAt());
        copy.setFinishedAt(source.getFinishedAt());
        copy.setDurationMillis(source.getDurationMillis());
        return copy;
    }

    private WorldOperationResult copyResult(WorldOperationResult source) {
        WorldOperationResult copy = new WorldOperationResult();
        copy.setSuccess(source.isSuccess());
        copy.setAction(source.getAction());
        copy.setMessage(source.getMessage());
        copy.setWorldName(source.getWorldName());
        copy.setOperationId(source.getOperationId());
        copy.setActorClientId(source.getActorClientId());
        copy.setStartedAt(source.getStartedAt());
        copy.setFinishedAt(source.getFinishedAt());
        copy.setSafetyBackupId(source.getSafetyBackupId());
        copy.setAuditId(source.getAuditId());
        copy.setStatus(source.getStatus());
        copy.setRequiresConfirmation(source.isRequiresConfirmation());
        copy.setData(source.getData() == null ? Map.of() : new LinkedHashMap<>(source.getData()));
        return copy;
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private record ActiveBinding(Path file, long generation) {
        private ActiveBinding {
            file = MigrationPaths.requirePath(file, "auditFile");
            if (generation < 0L) {
                throw new IllegalArgumentException("World audit generation must not be negative");
            }
        }
    }

    private record PendingLease(long generation, String auditId, String operationId, String action,
                                String actorClientId, String targetWorld, Map<String, Object> parameters,
                                long startedAt) {
        private PendingLease {
            parameters = new LinkedHashMap<>(parameters == null ? Map.of() : parameters);
        }
    }

    private record ConfirmationToken(String token, String action, String worldName, String actorClientId, long expiresAt) {
    }
}
