package restudio.resync.upgrade;

import java.util.Objects;
import java.util.regex.Pattern;

public record UpgradeVersion(String value) {
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]*(?:\\.[0-9]+){0,2}");

    public UpgradeVersion {
        value = Objects.requireNonNull(value, "value").trim();
        if (!VERSION.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Upgrade Version: " + value);
        }
    }

    public static UpgradeVersion of(String value) {
        return new UpgradeVersion(value);
    }

    public static UpgradeVersion current() {
        return new UpgradeVersion("1.0.0");
    }
}
