package restudio.resync.flow.automation;

import com.google.gson.JsonObject;

import java.util.Locale;

public record ScheduleDefinition(String id, String name, String description, TargetType targetType, String targetId,
                                 TimingMode timingMode, double duration, TimerDefinition.TimeUnit unit, double initialDelay,
                                 String dateTime, String timeZone, String cron, AutomationScope scope, boolean persistent,
                                 OverlapPolicy overlapPolicy, ExistingTaskPolicy existingTaskPolicy, FailurePolicy failurePolicy,
                                 OfflinePolicy offlinePolicy, MissedRunPolicy missedRunPolicy) implements AutomationDefinition {
    public enum TargetType {
        FUNCTION,
        FLOW
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

    public static ScheduleDefinition from(JsonObject json, String fallbackId) {
        JsonObject value = json != null ? json : new JsonObject();
        JsonObject timing = value.has("timing") && value.get("timing").isJsonObject() ? value.getAsJsonObject("timing") : value;
        JsonObject target = value.has("target") && value.get("target").isJsonObject() ? value.getAsJsonObject("target") : value;
        String id = string(value, "id", fallbackId);
        return new ScheduleDefinition(id, string(value, "name", string(value, "displayName", id)),
            string(value, "description", ""), enumeration(TargetType.class, string(value, "targetType", string(target, "type", "function"))),
            string(value, "targetId", string(target, "id", "")),
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

    private static <E extends Enum<E>> E enumeration(Class<E> type, String value) {
        String normalized = value == null ? "" : value.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        return Enum.valueOf(type, normalized);
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
    }

    private static double number(JsonObject json, String key, double fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : fallback;
    }
}
