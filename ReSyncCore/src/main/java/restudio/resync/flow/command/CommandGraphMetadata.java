package restudio.resync.flow.command;

import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record CommandGraphMetadata(String commandLabel, boolean structured, List<String> commandPaths) {
    private static final String COMMAND_LABEL = "commandLabel";
    private static final String STRUCTURED = "structured";
    private static final String COMMAND_PATHS = "commandPaths";

    public CommandGraphMetadata {
        commandLabel = normalizeLabel(commandLabel);
        commandPaths = normalizePaths(commandPaths);
    }

    public static CommandGraphMetadata from(GraphDocument document) {
        Objects.requireNonNull(document, "Graph document is required");
        OpaqueData unknown = document.unknown();
        String commandLabel = unknown.contains(COMMAND_LABEL)
            ? requireString(unknown.get(COMMAND_LABEL), COMMAND_LABEL)
            : document.resource().id();
        boolean structured = unknown.contains(STRUCTURED)
            ? requireBoolean(unknown.get(STRUCTURED), STRUCTURED)
            : false;
        List<String> commandPaths = unknown.contains(COMMAND_PATHS)
            ? requireStrings(unknown.get(COMMAND_PATHS), COMMAND_PATHS)
            : List.of();
        return new CommandGraphMetadata(commandLabel, structured, commandPaths);
    }

    public GraphDocument apply(GraphDocument document) {
        Objects.requireNonNull(document, "Graph document is required");
        Map<String, Object> fields = new LinkedHashMap<>(document.unknown().fields());
        fields.remove(COMMAND_LABEL);
        fields.remove(STRUCTURED);
        fields.remove(COMMAND_PATHS);
        fields.put(COMMAND_LABEL, commandLabel);
        fields.put(STRUCTURED, structured);
        fields.put(COMMAND_PATHS, commandPaths);
        return new GraphDocument(document.schemaVersion(), document.resource(), document.revision(), document.catalogBinding(),
            document.requiredCapabilities(), document.nodes(), document.connections(), document.passthroughs(), document.variables(), document.functions(),
            OpaqueData.of(fields));
    }

    private static String normalizeLabel(String value) {
        String normalized = Objects.requireNonNull(value, "Command label is required").trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int namespace = normalized.indexOf(':');
        if (namespace >= 0 && namespace < normalized.length() - 1) {
            normalized = normalized.substring(namespace + 1);
        }
        if (normalized.isBlank() || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Command label must be a non-blank single token");
        }
        return normalized;
    }

    private static List<String> normalizePaths(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                throw new IllegalArgumentException("Command paths must contain only strings");
            }
            String path = value.trim().replaceAll("\\s+", " ");
            if (path.isBlank()) {
                continue;
            }
            normalized.add(path);
        }
        return List.copyOf(normalized);
    }

    private static String requireString(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Command metadata " + field + " must be a string");
        }
        return text;
    }

    private static boolean requireBoolean(Object value, String field) {
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("Command metadata " + field + " must be a boolean");
        }
        return booleanValue;
    }

    private static List<String> requireStrings(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Command metadata " + field + " must be a list of strings");
        }
        ArrayList<String> result = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof String text)) {
                throw new IllegalArgumentException("Command metadata " + field + " must contain only strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }
}
