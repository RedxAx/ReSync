package restudio.resync.runtime.data;

import java.util.Locale;

final class RuntimeDataLabels {
    private RuntimeDataLabels() {
    }

    static String label(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String part : value.replace(':', '_').split("_")) {
            if (part.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            String lower = part.toLowerCase(Locale.ROOT);
            result.append(switch (lower) {
                case "resync" -> "ReSync";
                case "minecraft" -> "Minecraft";
                case "nexo" -> "Nexo";
                default -> Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
            });
        }
        return result.toString();
    }
}
