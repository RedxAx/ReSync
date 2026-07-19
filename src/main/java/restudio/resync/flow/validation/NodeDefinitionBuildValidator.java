package restudio.resync.flow.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class NodeDefinitionBuildValidator {
    private static final Set<String> KNOWN_DATA_TYPES = Set.of("execution", "any", "string", "number", "boolean");
    private static final Set<String> CLOCK_DOMAINS = Set.of("wall_time", "monotonic_elapsed", "server_ticks", "world_day_time");
    private static final Pattern OPTION_SOURCE_ID = Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_.-]+)+");
    private static final Pattern FLOW_DATA_TYPE_ID = Pattern.compile("new\\s+FlowDataType\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern FLOW_DATA_TYPE_ALIAS = Pattern.compile("REGISTRY\\.put\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern OPERATION_ID = Pattern.compile("operations\\.put\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern SUPPORTED_OPERATIONS = Pattern.compile("SUPPORTED_OPERATIONS\\s*=\\s*Set\\.of\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern REGISTRY_REGISTER = Pattern.compile("registry\\.register\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern RESTORED_ID = Pattern.compile("\"((?:entity|player)_[^\"]+)\"");

    private NodeDefinitionBuildValidator() {
    }

    public static void main(String[] args) throws IOException {
        Path projectDir = args.length > 0 ? Path.of(args[0]) : Path.of("");
        Path nodeRoot = projectDir.resolve("src/main/resources/nodes/migrated");
        Path sourceRoot = projectDir.resolve("src/main/java");

        List<String> errors = new ArrayList<>();
        Set<String> dataTypes = loadDataTypes(sourceRoot.resolve("restudio/flow/data/FlowDataType.java"));
        Map<String, Set<String>> handlerOperations = loadHandlerOperations(sourceRoot.resolve("restudio/resync/flow/handler"));
        Set<String> handlerIds = loadHandlerIds(sourceRoot.resolve("restudio/resync/flow/handler"), handlerOperations);
        List<JsonObject> definitions = loadDefinitions(nodeRoot, errors);

        validateDefinitions(definitions, dataTypes, handlerIds, handlerOperations, errors);
        validateMigrationMap(nodeRoot.resolve("_id_migration_map.json"), definitions, errors);

        System.out.println("definitions=" + definitions.size()
            + " handlers=" + handlerIds.size()
            + " handlersWithOps=" + handlerOperations.size()
            + " dataTypes=" + dataTypes.size()
            + " errors=" + errors.size());

        if (!errors.isEmpty()) {
            errors.stream().limit(100).forEach(error -> System.err.println("[node-definition-validation] " + error));
            throw new IllegalStateException("Node definition validation failed with " + errors.size() + " error(s)");
        }
    }

    private static Set<String> loadDataTypes(Path flowDataType) throws IOException {
        Set<String> dataTypes = new TreeSet<>(KNOWN_DATA_TYPES);
        String text = Files.readString(flowDataType, StandardCharsets.UTF_8);
        Matcher idMatcher = FLOW_DATA_TYPE_ID.matcher(text);
        while (idMatcher.find()) {
            dataTypes.add(idMatcher.group(1));
        }
        Matcher aliasMatcher = FLOW_DATA_TYPE_ALIAS.matcher(text);
        while (aliasMatcher.find()) {
            dataTypes.add(aliasMatcher.group(1));
        }
        return dataTypes;
    }

    private static Map<String, Set<String>> loadHandlerOperations(Path handlerRoot) throws IOException {
        Map<String, Set<String>> operations = new HashMap<>();
        try (Stream<Path> paths = Files.walk(handlerRoot)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith("Handler.java"))
                .forEach(path -> {
                    try {
                        String handlerName = path.getFileName().toString().replace(".java", "");
                        String text = Files.readString(path, StandardCharsets.UTF_8);
                        Set<String> ids = new TreeSet<>();
                        Matcher matcher = OPERATION_ID.matcher(text);
                        while (matcher.find()) {
                            ids.add(matcher.group(1));
                        }
                        Matcher supported = SUPPORTED_OPERATIONS.matcher(text);
                        while (supported.find()) {
                            Matcher literal = STRING_LITERAL.matcher(supported.group(1));
                            while (literal.find()) {
                                ids.add(literal.group(1));
                            }
                        }
                        if (!ids.isEmpty()) {
                            operations.put(handlerName, ids);
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
        }
        return operations;
    }

    private static Set<String> loadHandlerIds(Path handlerRoot, Map<String, Set<String>> handlerOperations) throws IOException {
        Set<String> handlerIds = new TreeSet<>(handlerOperations.keySet());
        try (Stream<Path> paths = Files.walk(handlerRoot)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith("Handler.java"))
                .forEach(path -> {
                    try {
                        String handlerName = path.getFileName().toString().replace(".java", "");
                        handlerIds.add(handlerName);
                        String text = Files.readString(path, StandardCharsets.UTF_8);
                        Matcher registerMatcher = REGISTRY_REGISTER.matcher(text);
                        while (registerMatcher.find()) {
                            handlerIds.add(registerMatcher.group(1));
                        }
                        if (handlerName.equals("RestoredNodeHandler")) {
                            Matcher restoredMatcher = RESTORED_ID.matcher(text);
                            while (restoredMatcher.find()) {
                                handlerIds.add(restoredMatcher.group(1));
                            }
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
        }
        handlerIds.addAll(Set.of("player", "entity", "world", "block", "inventory", "itemstack"));
        return handlerIds;
    }

    private static List<JsonObject> loadDefinitions(Path nodeRoot, List<String> errors) throws IOException {
        List<JsonObject> definitions = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(nodeRoot)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().startsWith("_"))
                .forEach(path -> {
                    try {
                        JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                        if (root.isJsonArray()) {
                            for (JsonElement element : root.getAsJsonArray()) {
                                if (element.isJsonObject()) {
                                    definitions.add(element.getAsJsonObject());
                                }
                            }
                        } else if (root.isJsonObject()) {
                            definitions.add(root.getAsJsonObject());
                        } else {
                            errors.add(path + " is not a JSON object or array");
                        }
                    } catch (Exception e) {
                        errors.add(path + " failed to parse: " + e.getMessage());
                    }
                });
        }
        return definitions;
    }

    private static void validateDefinitions(List<JsonObject> definitions, Set<String> dataTypes, Set<String> handlerIds, Map<String, Set<String>> handlerOperations, List<String> errors) {
        Set<String> ids = new HashSet<>();
        Map<String, String> visibleNames = new HashMap<>();
        for (JsonObject definition : definitions) {
            String id = string(definition, "id");
            if (id == null || id.isBlank()) {
                errors.add("node without id");
                continue;
            }
            if (!ids.add(id)) {
                errors.add("duplicate node id " + id);
            }
            String displayName = string(definition, "displayName");
            String category = string(definition, "category");
            boolean hidden = bool(definition, "hidden");
            if (!hidden && displayName != null && category != null) {
                String displayKey = category.toUpperCase(Locale.ROOT) + ":" + displayName.toLowerCase(Locale.ROOT);
                String previous = visibleNames.putIfAbsent(displayKey, id);
                if (previous != null) {
                    errors.add("duplicate visible display name " + displayName + " in " + category + " for " + previous + " and " + id);
                }
            }

            String kind = string(definition, "kind");
            String canonicalId = string(definition, "canonicalId");
            boolean trigger = bool(definition, "trigger");
            String handler = string(definition, "handler");
            if (!trigger && (handler == null || handler.isBlank())) {
                errors.add(id + " is missing handler");
            }
            if (handler != null && !handler.isBlank() && !handlerIds.contains(handler)) {
                errors.add(id + " references unknown handler " + handler);
            }
            if ("ALIAS".equalsIgnoreCase(kind) && (canonicalId == null || canonicalId.isBlank())) {
                errors.add(id + " is an alias without canonicalId");
            }
            if ("FAMILY".equalsIgnoreCase(kind) && !hasModeOrActionInput(definition)) {
                errors.add(id + " is a family node without mode/action input");
            }

            validateClockDomain(id, definition, handler, hidden, errors);

            validateHandlerOperation(id, definition, handler, handlerOperations, errors);
            validatePins(id, definition, "inputs", dataTypes, errors);
            validatePins(id, definition, "outputs", dataTypes, errors);
        }
    }

    private static void validateMigrationMap(Path migrationMap, List<JsonObject> definitions, List<String> errors) throws IOException {
        if (!Files.exists(migrationMap)) {
            errors.add("missing migration map " + migrationMap);
            return;
        }
        Set<String> ids = new HashSet<>();
        for (JsonObject definition : definitions) {
            String id = string(definition, "id");
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        JsonElement root = JsonParser.parseString(Files.readString(migrationMap, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) {
            errors.add("migration map is not a JSON object");
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                errors.add("migration map target for " + entry.getKey() + " is not a string");
                continue;
            }
            String target = entry.getValue().getAsString();
            if (!ids.contains(target)) {
                errors.add("migration map target missing for " + entry.getKey() + " -> " + target);
            }
        }
    }

    private static void validateHandlerOperation(String id, JsonObject definition, String handler, Map<String, Set<String>> handlerOperations, List<String> errors) {
        if (!definition.has("handlerConfig") || !definition.get("handlerConfig").isJsonObject()) {
            return;
        }
        String operation = string(definition.getAsJsonObject("handlerConfig"), "operation");
        if (operation == null || operation.isBlank() || handler == null || handler.isBlank()) {
            return;
        }
        Set<String> operations = handlerOperations.get(handler);
        if (operations != null && !operations.isEmpty() && !operations.contains(operation)) {
            errors.add(id + " references unknown operation " + handler + "." + operation);
        }
    }

    private static void validatePins(String id, JsonObject definition, String key, Set<String> dataTypes, List<String> errors) {
        if (!definition.has(key) || !definition.get(key).isJsonArray()) {
            return;
        }
        JsonArray pins = definition.getAsJsonArray(key);
        Set<String> names = new HashSet<>();
        for (JsonElement pinElement : pins) {
            if (!pinElement.isJsonObject()) {
                errors.add(id + " has non-object pin in " + key);
                continue;
            }
            JsonObject pin = pinElement.getAsJsonObject();
            String name = string(pin, "name");
            if (name == null || name.isBlank()) {
                errors.add(id + " has unnamed pin in " + key);
            } else if (!names.add(name)) {
                errors.add(id + " has duplicate pin " + name + " in " + key);
            }
            String pinType = string(pin, "pinType");
            if (pinType == null) {
                pinType = string(pin, "type");
            }
            String dataType = string(pin, "dataType");
            if (!"FLOW".equalsIgnoreCase(String.valueOf(pinType))) {
                validateDataTypeExpression(id, name, dataType, dataTypes, errors);
            }
            String optionsSource = string(pin, "optionsSource");
            if (optionsSource != null && !optionsSource.isBlank()) {
                validateOptionSource(id, name, optionsSource, errors);
            }
            if (pin.has("options") && pin.get("options").isJsonObject()) {
                errors.add(id + "." + name + " uses an object for options; dynamic catalogs require optionsSource");
            }
            if (pin.has("repeatable")) {
                if (!"inputs".equals(key) || !pin.get("repeatable").isJsonObject()) {
                    errors.add(id + "." + name + " has invalid repeatable input metadata");
                } else {
                    JsonObject repeatable = pin.getAsJsonObject("repeatable");
                    String groupId = string(repeatable, "groupId");
                    if (groupId == null || groupId.isBlank()) {
                        errors.add(id + "." + name + " repeatable metadata requires groupId");
                    }
                }
            }
        }
    }

    private static void validateClockDomain(String id, JsonObject definition, String handler, boolean hidden, List<String> errors) {
        String operation = definition.has("handlerConfig") && definition.get("handlerConfig").isJsonObject()
            ? string(definition.getAsJsonObject("handlerConfig"), "operation") : null;
        boolean temporal = "TimeHandler".equals(handler) || "ScheduleHandler".equals(handler)
            || "delay".equals(operation) || "loop_interval".equals(operation);
        String clockDomain = string(definition, "clockDomain");
        if (temporal && !hidden && (clockDomain == null || clockDomain.isBlank())) {
            errors.add(id + " is temporal and does not declare clockDomain");
            return;
        }
        if (clockDomain == null || clockDomain.isBlank()) {
            return;
        }
        for (String domain : clockDomain.split(",")) {
            if (!CLOCK_DOMAINS.contains(domain.strip())) {
                errors.add(id + " declares unknown clockDomain " + domain.strip());
            }
        }
    }

    private static void validateDataTypeExpression(String nodeId, String pinName, String expression, Set<String> dataTypes, List<String> errors) {
        FlowTypeRef typeRef;
        try {
            typeRef = FlowTypeRef.parse(expression == null || expression.isBlank() ? "any" : expression);
        } catch (IllegalArgumentException exception) {
            errors.add(nodeId + "." + pinName + " has invalid dataType expression " + expression);
            return;
        }
        validateDataTypeRef(nodeId, pinName, typeRef, dataTypes, errors);
    }

    private static void validateDataTypeRef(String nodeId, String pinName, FlowTypeRef typeRef, Set<String> dataTypes, List<String> errors) {
        if (typeRef.isTypeVariable()) {
            return;
        }
        if (!dataTypes.contains(typeRef.getTypeId())) {
            errors.add(nodeId + "." + pinName + " references unknown dataType " + typeRef.getTypeId());
        }
        int arguments = typeRef.getArguments().size();
        if (Set.of("list", "set", "queue", "stack", "optional", "result", "job_reference").contains(typeRef.getTypeId()) && arguments != 1) {
            errors.add(nodeId + "." + pinName + " requires one type argument for " + typeRef.getTypeId());
        }
        if ("map".equals(typeRef.getTypeId()) && arguments != 2) {
            errors.add(nodeId + "." + pinName + " requires key and value type arguments for map");
        }
        if ("resource_reference".equals(typeRef.getTypeId())) {
            if (typeRef.getArguments().size() > 1) {
                errors.add(nodeId + "." + pinName + " resource reference accepts at most one resource kind");
            } else if (!typeRef.getArguments().isEmpty()) {
                String resourceKind = typeRef.getArguments().getFirst().getTypeId();
                if (ReSyncResourceCatalog.byType(resourceKind) == null && !resourceKind.contains(":")) {
                    errors.add(nodeId + "." + pinName + " references unknown resource kind " + resourceKind);
                }
            }
            return;
        }
        for (FlowTypeRef argument : typeRef.getArguments()) {
            validateDataTypeRef(nodeId, pinName, argument, dataTypes, errors);
        }
    }

    private static void validateOptionSource(String id, String pinName, String optionsSource, List<String> errors) {
        if (!OPTION_SOURCE_ID.matcher(optionsSource).matches()) {
            errors.add(id + "." + pinName + " references malformed optionsSource " + optionsSource);
        }
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    private static boolean hasModeOrActionInput(JsonObject definition) {
        if (!definition.has("inputs") || !definition.get("inputs").isJsonArray()) {
            return false;
        }
        for (JsonElement element : definition.getAsJsonArray("inputs")) {
            if (!element.isJsonObject()) {
                continue;
            }
            String name = string(element.getAsJsonObject(), "name");
            if ("mode".equalsIgnoreCase(name) || "action".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
