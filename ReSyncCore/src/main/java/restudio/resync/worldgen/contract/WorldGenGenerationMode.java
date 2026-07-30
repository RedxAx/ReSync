package restudio.resync.worldgen.contract;

import java.util.Arrays;
import java.util.Locale;

public enum WorldGenGenerationMode {
    VANILLA("vanilla", "Vanilla"),
    HYBRID("hybrid", "Hybrid");

    private final String id;
    private final String displayName;

    WorldGenGenerationMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static WorldGenGenerationMode resolve(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(mode -> mode.id.equals(normalized) || mode.displayName.toLowerCase(Locale.ROOT).equals(normalized))
            .findFirst()
            .orElse(HYBRID);
    }
}
