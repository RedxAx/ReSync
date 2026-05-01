package restudio.resync.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import restudio.resync.Log;
import restudio.resync.ReSync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WorldOperationSafetyService {
    private static final int DEFAULT_MAX_RECORDS = 500;
    private static final long CONFIRMATION_TTL_MILLIS = 120_000L;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path auditFile;
    private final int maxRecords;
    private final Map<String, ConfirmationToken> confirmations = new ConcurrentHashMap<>();
    private final Map<String, WorldOperationResult> operationStatuses = new ConcurrentHashMap<>();
    private final List<WorldOperationAuditRecord> auditRecords = new ArrayList<>();

    public WorldOperationSafetyService(ReSync plugin) {
        this(plugin, DEFAULT_MAX_RECORDS);
    }

    public WorldOperationSafetyService(ReSync plugin, int maxRecords) {
        this(plugin.getDataFolder().toPath().resolve("world-audit.json"), maxRecords);
    }

    public WorldOperationSafetyService(Path auditFile, int maxRecords) {
        this.auditFile = auditFile;
        this.maxRecords = Math.max(50, maxRecords);
        load();
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
        String token = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        confirmations.put(token, new ConfirmationToken(token, action, worldName, actorClientId, now + CONFIRMATION_TTL_MILLIS));
        WorldOperationResult result = WorldOperationResult.failure(action, worldName, "ConfirmationRequired");
        result.setRequiresConfirmation(true);
        result.setActorClientId(actorClientId);
        result.setStartedAt(now);
        result.setFinishedAt(now);
        result.withData("confirmationToken", token);
        result.withData("expiresAt", now + CONFIRMATION_TTL_MILLIS);
        result.withData("parameters", parameters == null ? Map.of() : parameters);
        return result;
    }

    public boolean consumeConfirmation(String token, String action, String worldName, String actorClientId) {
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

    public WorldOperationAuditRecord begin(String operationId, String action, String actorClientId, String targetWorld, Map<String, Object> parameters) {
        WorldOperationAuditRecord record = new WorldOperationAuditRecord();
        long now = System.currentTimeMillis();
        record.setAuditId(UUID.randomUUID().toString());
        record.setOperationId(operationId);
        record.setAction(action);
        record.setActorClientId(actorClientId);
        record.setTargetWorld(targetWorld);
        record.setParameters(parameters);
        record.setStartedAt(now);
        record.setBackupAvailable(false);
        return record;
    }

    public synchronized void finish(WorldOperationAuditRecord record, WorldOperationResult result, Throwable failure) {
        if (record == null) {
            return;
        }
        long now = System.currentTimeMillis();
        record.setFinishedAt(now);
        record.setDurationMillis(Math.max(0L, now - record.getStartedAt()));
        record.setSuccess(result != null && result.isSuccess() && failure == null);
        record.setMessage(result != null ? result.getMessage() : null);
        record.setFailureReason(failure != null ? failure.getMessage() : result != null && !result.isSuccess() ? result.getMessage() : null);
        record.setSafetyBackupId(result != null ? result.getSafetyBackupId() : null);
        auditRecords.add(record);
        auditRecords.sort(Comparator.comparingLong(WorldOperationAuditRecord::getStartedAt).reversed());
        while (auditRecords.size() > maxRecords) {
            auditRecords.removeLast();
        }
        save();
    }

    public void rememberStatus(WorldOperationResult result) {
        if (result != null && result.getOperationId() != null && !result.getOperationId().isBlank()) {
            operationStatuses.put(result.getOperationId(), result);
        }
    }

    public WorldOperationResult getStatus(String operationId) {
        return operationStatuses.get(operationId);
    }

    public List<WorldOperationAuditRecord> snapshot(int limit) {
        int count = Math.max(1, Math.min(limit <= 0 ? 100 : limit, auditRecords.size()));
        return new ArrayList<>(auditRecords.subList(0, count));
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

    private synchronized void load() {
        if (!Files.exists(auditFile)) {
            return;
        }
        try {
            String json = Files.readString(auditFile, StandardCharsets.UTF_8);
            WorldOperationAuditRecord[] loaded = gson.fromJson(json, WorldOperationAuditRecord[].class);
            auditRecords.clear();
            if (loaded != null) {
                for (WorldOperationAuditRecord record : loaded) {
                    if (record != null) {
                        auditRecords.add(record);
                    }
                }
            }
        } catch (Exception exception) {
            Log.warn("Failed to load world audit records: " + exception.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(auditFile.getParent());
            Files.writeString(auditFile, gson.toJson(auditRecords), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            Log.warn("Failed to save world audit records: " + exception.getMessage());
        }
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private record ConfirmationToken(String token, String action, String worldName, String actorClientId, long expiresAt) {
    }
}
