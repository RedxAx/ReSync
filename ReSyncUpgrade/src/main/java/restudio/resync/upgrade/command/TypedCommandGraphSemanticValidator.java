package restudio.resync.upgrade.command;

import restudio.resync.contract.canonical.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class TypedCommandGraphSemanticValidator {
    private static final Set<String> COMMAND_NODE_TYPES = Set.of("event.resync.command", "event:resync_command");
    private static final List<String> COMMAND_FIELDS = List.of("command", "commandLabel", "label", "name");
    private static final List<String> PATH_FIELDS = List.of("commandPaths", "subcommands", "allowedSubcommands");
    private static final List<String> ALIAS_FIELDS = List.of("aliases", "commandAliases");

    private TypedCommandGraphSemanticValidator() {
    }

    static void validate(RawGraphDocument graph, String expectedGraphId) {
        Objects.requireNonNull(graph, "graph");
        JsonValue.JsonObject root = graph.root();
        String graphId = requiredString(root.value("id"), "graph id");
        if (expectedGraphId != null && !expectedGraphId.equals(graphId)) {
            throw invalid("COMMAND_GRAPH_INVALID", "The typed command graph ID does not match its source identity");
        }
        if (!"command".equals(requiredString(root.value("resourceType"), "graph resource type"))) {
            throw invalid("COMMAND_GRAPH_INVALID", "The graph resource type is not command");
        }
        JsonValue.JsonObject contentProperties = optionalObject(root.value("contentProperties"), "contentProperties");
        JsonValue.JsonObject nodes = requiredObject(root.value("nodes"), "nodes");
        List<Map.Entry<String, JsonValue.JsonObject>> commandNodes = new ArrayList<>();
        for (Map.Entry<String, JsonValue> entry : nodes.fields().entrySet()) {
            String nodeId = requiredText(entry.getKey(), "node id");
            JsonValue.JsonObject node = requiredObject(entry.getValue(), "node " + nodeId);
            validateNodeMap(node.value("inputValues"), "inputValues");
            validateNodeMap(node.value("handlerConfig"), "handlerConfig");
            JsonValue typeValue = node.value("type");
            if (typeValue == null || typeValue instanceof JsonValue.JsonNull) {
                continue;
            }
            if (!(typeValue instanceof JsonValue.JsonString type)) {
                throw invalid("COMMAND_GRAPH_INVALID", "Node type must be a string");
            }
            if (COMMAND_NODE_TYPES.contains(type.value().trim().toLowerCase(Locale.ROOT))) {
                commandNodes.add(Map.entry(nodeId, node));
            }
        }
        if (commandNodes.size() != 1) {
            throw invalid("COMMAND_GRAPH_INVALID", "A typed command graph requires exactly one command start node");
        }

        Map<String, JsonValue> nodeValues = new LinkedHashMap<>();
        nodeValues.putAll(mapValues(commandNodes.getFirst().getValue().value("handlerConfig"), "handlerConfig"));
        nodeValues.putAll(mapValues(commandNodes.getFirst().getValue().value("inputValues"), "inputValues"));
        String command = textFromSources(root, contentProperties, nodeValues, COMMAND_FIELDS, false);
        if (command == null || command.isBlank()) {
            command = graphId;
        }
        normalizeLabel(command, "command");
        validateAliases(selectValue(root, contentProperties, nodeValues, ALIAS_FIELDS));
        validatePaths(selectValue(root, contentProperties, nodeValues, PATH_FIELDS));
        validateBoolean(selectValue(root, contentProperties, nodeValues, List.of("structured")), "structured");
        textFromSources(root, contentProperties, nodeValues, List.of("permission"), true);
        textFromSources(root, contentProperties, nodeValues, List.of("permissionMessage"), true);
        textFromSources(root, contentProperties, nodeValues, List.of("description", "commandDescription"), true);
        textFromSources(root, contentProperties, nodeValues, List.of("usage", "commandUsage"), true);
    }

    private static String textFromSources(JsonValue.JsonObject root, JsonValue.JsonObject contentProperties,
                                          Map<String, JsonValue> nodeValues, List<String> keys, boolean optional) {
        String selected = null;
        for (String key : keys) {
            List<JsonValue> values = values(root, contentProperties, nodeValues, key);
            for (JsonValue value : values) {
                String text = value instanceof JsonValue.JsonNull ? null : requiredString(value, key, optional);
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (selected != null && !selected.equals(text)) {
                    throw invalid("COMMAND_GRAPH_AMBIGUOUS", "Command metadata fields disagree for " + keys);
                }
                selected = text;
            }
        }
        return selected;
    }

    private static JsonValue selectValue(JsonValue.JsonObject root, JsonValue.JsonObject contentProperties,
                                         Map<String, JsonValue> nodeValues, List<String> keys) {
        JsonValue selected = null;
        boolean selectedSet = false;
        for (String key : keys) {
            for (JsonValue value : values(root, contentProperties, nodeValues, key)) {
                if (selectedSet && !Objects.equals(selected, value)) {
                    throw invalid("COMMAND_GRAPH_AMBIGUOUS", "Command metadata fields disagree for " + keys);
                }
                selected = value;
                selectedSet = true;
            }
        }
        return selectedSet && !(selected instanceof JsonValue.JsonNull) ? selected : null;
    }

    private static List<JsonValue> values(JsonValue.JsonObject root, JsonValue.JsonObject contentProperties,
                                          Map<String, JsonValue> nodeValues, String key) {
        List<JsonValue> values = new ArrayList<>();
        JsonValue rootValue = root.value(key);
        if (rootValue != null && !(rootValue instanceof JsonValue.JsonNull)) {
            values.add(rootValue);
        } else if (contentProperties != null && contentProperties.value(key) != null
            && !(contentProperties.value(key) instanceof JsonValue.JsonNull)) {
            values.add(contentProperties.value(key));
        }
        if (nodeValues.containsKey(key)) {
            values.add(nodeValues.get(key));
        }
        return values;
    }

    private static void validateAliases(JsonValue value) {
        if (value == null) {
            return;
        }
        JsonValue.JsonArray aliases = requiredArray(value, "command aliases");
        for (JsonValue alias : aliases.values()) {
            normalizeLabel(requiredString(alias, "alias"), "alias");
        }
    }

    private static void validatePaths(JsonValue value) {
        if (value == null) {
            return;
        }
        if (value instanceof JsonValue.JsonString string) {
            validatePathTokens(string.value());
            return;
        }
        JsonValue.JsonArray paths = requiredArray(value, "command paths");
        boolean stringList = paths.values().stream().allMatch(item -> item instanceof JsonValue.JsonNull || item instanceof JsonValue.JsonString);
        if (stringList) {
            for (JsonValue item : paths.values()) {
                validatePathTokens(requiredString(item, "subcommand"));
            }
            return;
        }
        for (JsonValue path : paths.values()) {
            if (path instanceof JsonValue.JsonString string) {
                validatePathTokens(string.value());
                continue;
            }
            JsonValue.JsonArray tokens = requiredArray(path, "command path");
            if (tokens.values().isEmpty()) {
                throw invalid("COMMAND_GRAPH_INVALID", "Command paths cannot be empty");
            }
            for (JsonValue token : tokens.values()) {
                requiredText(requiredString(token, "command path token"), "command path token");
            }
        }
    }

    private static void validatePathTokens(String value) {
        String text = requiredText(value, "command path");
        if (text.isBlank()) {
            throw invalid("COMMAND_GRAPH_INVALID", "Command paths cannot be empty");
        }
        for (String token : text.split("\\s+")) {
            requiredText(token, "command path token");
        }
    }

    private static void validateBoolean(JsonValue value, String field) {
        if (value != null && !(value instanceof JsonValue.JsonBoolean)) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be a boolean");
        }
    }

    private static void validateNodeMap(JsonValue value, String field) {
        if (value != null && !(value instanceof JsonValue.JsonObject)) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be an object");
        }
    }

    private static Map<String, JsonValue> mapValues(JsonValue value, String field) {
        if (value == null || value instanceof JsonValue.JsonNull) {
            return Map.of();
        }
        return requiredObject(value, field).fields();
    }

    private static JsonValue.JsonObject optionalObject(JsonValue value, String field) {
        if (value == null) {
            return null;
        }
        return requiredObject(value, field);
    }

    private static JsonValue.JsonObject requiredObject(JsonValue value, String field) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be an object");
        }
        return object;
    }

    private static JsonValue.JsonArray requiredArray(JsonValue value, String field) {
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be a string or array");
        }
        return array;
    }

    private static String requiredString(JsonValue value, String field) {
        return requiredString(value, field, false);
    }

    private static String requiredString(JsonValue value, String field, boolean optional) {
        if (value == null || value instanceof JsonValue.JsonNull) {
            return optional ? "" : null;
        }
        if (!(value instanceof JsonValue.JsonString string)) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be a string");
        }
        return string.value().trim();
    }

    private static String normalizeLabel(String value, String field) {
        String normalized = requiredText(value, field).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int separator = normalized.indexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            normalized = normalized.substring(separator + 1);
        }
        if (normalized.isBlank() || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be one command label");
        }
        return normalized;
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw invalid("COMMAND_GRAPH_INVALID", field + " must be a non-blank single-line string");
        }
        return value.trim();
    }

    private static IllegalArgumentException invalid(String code, String detail) {
        return new IllegalArgumentException(code + ": " + detail);
    }
}
