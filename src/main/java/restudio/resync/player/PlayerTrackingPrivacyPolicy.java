package restudio.resync.player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PlayerTrackingPrivacyPolicy {
    private static final Set<String> SENSITIVE_COMMANDS = Set.of("login", "register", "changepassword", "msg", "tell", "w", "r", "mail");

    public Map<String, Object> sanitizeChat(String message, String format, boolean captureChatText) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("length", message != null ? message.length() : 0);
        if (captureChatText) {
            data.put("message", message);
            data.put("format", format);
        }
        return data;
    }

    public Map<String, Object> sanitizeCommand(String rawCommand, boolean captureCommandArguments) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        String trimmed = rawCommand == null ? "" : rawCommand.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int split = trimmed.indexOf(' ');
        String command = split >= 0 ? trimmed.substring(0, split) : trimmed;
        String args = split >= 0 ? trimmed.substring(split + 1).trim() : "";
        String normalized = command.toLowerCase(Locale.ROOT);
        data.put("command", command);
        if (!args.isBlank()) {
            data.put("args", captureCommandArguments && !SENSITIVE_COMMANDS.contains(normalized) ? args : "[redacted]");
        }
        return data;
    }
}
