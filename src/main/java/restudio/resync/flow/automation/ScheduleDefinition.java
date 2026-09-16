package restudio.resync.flow.automation;

import com.google.gson.JsonObject;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Locale;
import java.util.Objects;

public record ScheduleDefinition(String id, String name, String description, TargetType targetType, String targetId,
                                 TimingMode timingMode, double duration, TimerDefinition.TimeUnit unit, double initialDelay,
                                 String dateTime, String timeZone, String cron, AutomationScope scope, boolean persistent,
                                 OverlapPolicy overlapPolicy, ExistingTaskPolicy existingTaskPolicy, FailurePolicy failurePolicy,
                                 OfflinePolicy offlinePolicy, MissedRunPolicy missedRunPolicy) implements AutomationDefinition {
    public enum TargetType {
        FUNCTION,
        FLOW,
        COMMAND
    }

    public enum TimingMode {
        AFTER_DELAY,
        AT_TIME,
        REPEATING,
        CRON
    }

    public enum OverlapPolicy {
        SKIP,
        QUEUE,
        PARALLEL,
        REPLACE
    }

    public enum ExistingTaskPolicy {
        REPLACE,
        KEEP,
        FAIL
    }

    public enum FailurePolicy {
        CONTINUE,
        STOP
    }

    public enum OfflinePolicy {
        WAIT,
        SKIP,
        RUN_WITHOUT_PLAYER,
        CANCEL
    }

    public enum MissedRunPolicy {
        RUN_ONCE,
        SKIP,
        CANCEL
    }

    public ScheduleDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Schedule definition ID is required");
        }
        id = id.trim();
        name = name != null ? name : id;
        description = description != null ? description : "";
        targetType = targetType != null ? targetType : TargetType.FUNCTION;
        targetId = targetId != null ? targetId.trim() : "";
        timingMode = timingMode != null ? timingMode : TimingMode.AFTER_DELAY;
        unit = unit != null ? unit : TimerDefinition.TimeUnit.SECONDS;
        dateTime = dateTime != null ? dateTime : "";
        timeZone = timeZone == null || timeZone.isBlank() ? "UTC" : timeZone;
        cron = cron != null ? cron : "";
        scope = scope != null ? scope : AutomationScope.SERVER;
        overlapPolicy = overlapPolicy != null ? overlapPolicy : OverlapPolicy.SKIP;
        existingTaskPolicy = existingTaskPolicy != null ? existingTaskPolicy : ExistingTaskPolicy.REPLACE;
        failurePolicy = failurePolicy != null ? failurePolicy : FailurePolicy.CONTINUE;
        offlinePolicy = offlinePolicy != null ? offlinePolicy : OfflinePolicy.WAIT;
        missedRunPolicy = missedRunPolicy != null ? missedRunPolicy : MissedRunPolicy.RUN_ONCE;
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("Schedule target is required");
        }
        if (!Double.isFinite(duration) || duration < 0D || !Double.isFinite(initialDelay) || initialDelay < 0D) {
            throw new IllegalArgumentException("Schedule timing values must be finite and non-negative");
        }
    }

    public String targetResourceType() {
        return switch (targetType) {
            case FLOW -> "flow";
            case FUNCTION -> "function";
            case COMMAND -> "command";
        };
    }

    public ServerResourceLocator targetLocator(ServerId serverId) {
        Objects.requireNonNull(serverId, "Schedule target server ID is required");
        return new ServerResourceLocator(serverId,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(targetResourceType())), targetId);
    }

    public static ScheduleDefinition from(JsonObject json, String fallbackId) {
        JsonObject value = json != null ? json : new JsonObject();
        JsonObject timing = value.has("timing") && value.get("timing").isJsonObject() ? value.getAsJsonObject("timing") : value;
        JsonObject target = value.has("target") && value.get("target").isJsonObject() ? value.getAsJsonObject("target") : null;
        String id = string(value, "id", fallbackId);
        return new ScheduleDefinition(id, string(value, "name", string(value, "displayName", id)),
            string(value, "description", ""), enumeration(TargetType.class, targetType(value, target)),
            targetId(value, target),
            enumeration(TimingMode.class, string(value, "timingMode", string(timing, "mode", "after_delay"))),
            number(timing, "duration", 0D), TimerDefinition.TimeUnit.parse(string(timing, "unit", "seconds")),
            number(timing, "initialDelay", 0D), string(timing, "dateTime", ""), string(timing, "timeZone", "UTC"),
            string(timing, "cron", string(timing, "pattern", "")), AutomationScope.parse(string(value, "scope", "server")),
            bool(value, "persistent", false), enumeration(OverlapPolicy.class, string(value, "overlapPolicy", "skip")),
            enumeration(ExistingTaskPolicy.class, string(value, "existingTaskPolicy", "replace")),
            enumeration(FailurePolicy.class, string(value, "failurePolicy", "continue")),
            enumeration(OfflinePolicy.class, string(value, "offlinePolicy", "wait")),
            enumeration(MissedRunPolicy.class, string(value, "missedRunPolicy", "run_once")));
    }

    private static String targetType(JsonObject value, JsonObject target) {
        if (target != null && target.has("type") && target.get("type").isJsonObject()) {
            JsonObject type = target.getAsJsonObject("type");
            String ownerId = optionalString(type, "ownerId");
            if (ownerId != null && !"restudio.resync".equals(ownerId)) {
                throw new IllegalArgumentException("Schedule target must belong to the Core graph owner");
            }
            String localId = optionalString(type, "localId");
            if (localId != null) {
                String declared = optionalString(value, "targetType");
                if (declared != null) {
                    return declared;
                }
                return localId;
            }
        }
        String declared = optionalString(value, "targetType");
        if (declared != null) {
            return declared;
        }
        return string(target, "type", "function");
    }

    private static String targetId(JsonObject value, JsonObject target) {
        String declared = optionalTargetId(value, "targetId");
        String nested = optionalTargetId(target, "id");
        if (declared != null && nested != null && !declared.equals(nested)) {
            throw new IllegalArgumentException("Schedule target ID fields conflict");
        }
        return declared != null ? declared : nested != null ? nested : "";
    }

    private static <E extends Enum<E>> E enumeration(Class<E> type, String value) {
        String normalized = value == null ? "" : value.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        return Enum.valueOf(type, normalized);
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static String optionalString(JsonObject json, String key) {
        return json != null && json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static String optionalTargetId(JsonObject json, String key) {
        String value = optionalString(json, key);
        value = value != null ? value.trim() : null;
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
    }

    private static double number(JsonObject json, String key, double fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : fallback;
    }
}
