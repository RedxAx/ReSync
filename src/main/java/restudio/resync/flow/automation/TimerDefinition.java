package restudio.resync.flow.automation;

import com.google.gson.JsonObject;

import java.util.Locale;

public record TimerDefinition(String id, String name, String description, AutomationScope scope, boolean persistent,
                              double defaultDuration, TimeUnit defaultUnit, double tickInterval) implements AutomationDefinition {
    public enum TimeUnit {
        TICKS(50D),
        SECONDS(1000D),
        MINUTES(60000D);

        private final double millis;

        TimeUnit(double millis) {
            this.millis = millis;
        }

        public long toMillis(double duration) {
            if (!Double.isFinite(duration) || duration < 0D || duration > Long.MAX_VALUE / millis) {
                throw new IllegalArgumentException("Timer duration is invalid");
            }
            return Math.round(duration * millis);
        }

        public static TimeUnit parse(String value) {
            return value == null || value.isBlank() ? SECONDS : valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public TimerDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Timer definition ID is required");
        }
        id = id.trim();
        name = name != null ? name : id;
        description = description != null ? description : "";
        scope = scope != null ? scope : AutomationScope.SERVER;
        defaultUnit = defaultUnit != null ? defaultUnit : TimeUnit.SECONDS;
        if (!Double.isFinite(defaultDuration) || defaultDuration < 0D || !Double.isFinite(tickInterval) || tickInterval < 0D) {
            throw new IllegalArgumentException("Timer duration and tick interval must be finite and non-negative");
        }
    }

    public static TimerDefinition from(JsonObject json, String fallbackId) {
        JsonObject value = json != null ? json : new JsonObject();
        String id = string(value, "id", fallbackId);
        return new TimerDefinition(id, string(value, "name", string(value, "displayName", id)),
            string(value, "description", ""), AutomationScope.parse(string(value, "scope", "server")),
            bool(value, "persistent", false), number(value, "defaultDuration", 0D),
            TimeUnit.parse(string(value, "defaultUnit", "seconds")), number(value, "tickInterval", 0D));
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
