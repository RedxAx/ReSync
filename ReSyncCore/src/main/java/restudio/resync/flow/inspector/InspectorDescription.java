package restudio.resync.flow.inspector;

import java.util.Locale;
import java.util.Objects;

public final class InspectorDescription {
    private InspectorDescription() {
    }

    public static String title(String value, String subject) {
        String normalized = required(value, subject + " title");
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(subject + " title exceeds 128 characters");
        }
        return normalized;
    }

    public static String description(String title, String value, String subject) {
        String normalized = required(value, subject + " description");
        if (normalized.length() < 16 || normalized.length() > 240) {
            throw new IllegalArgumentException(subject + " description must contain between 16 and 240 characters");
        }
        if (title != null && normalized.equalsIgnoreCase(title.trim())) {
            throw new IllegalArgumentException(subject + " description cannot copy its title");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.matches("^(todo|tbd|placeholder|lorem ipsum|n/?a|none|unknown|fill in|coming soon)([ .,:;-].*)?$")) {
            throw new IllegalArgumentException(subject + " description is not authored");
        }
        return normalized;
    }

    public static String required(String value, String subject) {
        String normalized = Objects.requireNonNull(value, subject).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(subject + " is required");
        }
        return normalized;
    }
}
