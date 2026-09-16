package restudio.resync.migration;

import java.util.Locale;

public enum MigrationOperationType {
    COPY("copy", true, true, true),
    GENERATE("generate", false, true, true),
    MOVE("move", true, true, false),
    RENAME("rename", true, true, false),
    REPLACE("replace", true, true, false),
    CONVERT("convert", true, true, false),
    DELETE("delete", true, false, false);

    private final String wireName;
    private final boolean sourceRequired;
    private final boolean targetRequired;
    private final boolean preservesSource;

    MigrationOperationType(String wireName, boolean sourceRequired, boolean targetRequired, boolean preservesSource) {
        this.wireName = wireName;
        this.sourceRequired = sourceRequired;
        this.targetRequired = targetRequired;
        this.preservesSource = preservesSource;
    }

    public String wireName() {
        return wireName;
    }

    public boolean sourceRequired() {
        return sourceRequired;
    }

    public boolean targetRequired() {
        return targetRequired;
    }

    public boolean preservesSource() {
        return preservesSource;
    }

    public static MigrationOperationType fromWire(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (MigrationOperationType type : values()) {
            if (type.wireName.equals(normalized)) {
                return type;
            }
        }
        if (normalized.equals("graph-copy") || normalized.equals("resource-copy")) {
            return COPY;
        }
        throw new IllegalArgumentException("Unsupported Migration Operation Type: " + value);
    }
}
