package restudio.resync.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

public final class AuthorityEpoch {
    private final LongSupplier source;

    public AuthorityEpoch(LongSupplier source) {
        this.source = Objects.requireNonNull(source, "Authority epoch source is required");
    }

    public static AuthorityEpoch fixed(long epoch) {
        if (epoch < 1L) {
            throw new IllegalArgumentException("Authority epoch must be positive");
        }
        return new AuthorityEpoch(() -> epoch);
    }

    public long current() {
        long epoch = source.getAsLong();
        if (epoch < 1L) {
            throw new IllegalStateException("Authority epoch must be positive");
        }
        return epoch;
    }

    public boolean acceptsTyped(long incoming) {
        return incoming >= 1L && incoming == current();
    }

    public boolean acceptsLegacyJson(String json) {
        return acceptsLegacyJson(json, false);
    }

    public boolean acceptsLegacyJson(String json, boolean legacyCompatible) {
        OptionalLong incoming = read(json);
        if (incoming.isEmpty() || incoming.getAsLong() == 0L) {
            return legacyCompatible && current() >= 1L;
        }
        return incoming.getAsLong() >= 1L && incoming.getAsLong() == current();
    }

    public String withCurrentEpoch(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            return json;
        }
        parsed.getAsJsonObject().addProperty("authorityEpoch", current());
        return parsed.toString();
    }

    public OptionalLong read(String json) {
        if (json == null || json.isBlank()) {
            return OptionalLong.empty();
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            return OptionalLong.of(-1L);
        }
        if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("authorityEpoch")) {
            return OptionalLong.empty();
        }
        JsonElement value = parsed.getAsJsonObject().get("authorityEpoch");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive().isNumber()) {
            return OptionalLong.of(-1L);
        }
        try {
            long epoch = Long.parseLong(value.getAsString());
            return OptionalLong.of(epoch < 0L ? -1L : epoch);
        } catch (RuntimeException exception) {
            return OptionalLong.of(-1L);
        }
    }
}
