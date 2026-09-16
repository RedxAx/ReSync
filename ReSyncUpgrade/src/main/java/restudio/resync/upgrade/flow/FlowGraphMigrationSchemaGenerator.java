package restudio.resync.upgrade.flow;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.migration.FlowGraphMigrationSchemaCatalog;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class FlowGraphMigrationSchemaGenerator {
    private static final String SOURCE_MODE = "compatibility";
    private static final String SOURCE_ROOT = "ReSyncUpgrade/src/main/resources/nodes/migrated";
    private static final String ACTIVE_SOURCE_MODE = "authored";
    private static final String ACTIVE_SOURCE_ROOT = "src/main/resources/nodes";
    private static final Pattern PIN_ID = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,31}(?:[._-][A-Za-z0-9][A-Za-z0-9]{0,31})*");

    private FlowGraphMigrationSchemaGenerator() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments == null || arguments.length != 3) {
            throw new IllegalArgumentException("Expected a migrated node directory, authored node directory, and generated schema path");
        }
        Path sourceRoot = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path authoredRoot = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[2]).toAbsolutePath().normalize();
        GeneratedSchema schema = generate(sourceRoot, authoredRoot);
        Files.createDirectories(output.getParent());
        Files.write(output, schema.bytes());
    }

    public static GeneratedSchema generate(Path sourceRoot) throws IOException {
        return generate(sourceRoot, null);
    }

    public static GeneratedSchema generate(Path sourceRoot, Path authoredRoot) throws IOException {
        Path root = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        List<Path> files = sourceFiles(root, "Production node definition");
        if (files.isEmpty()) {
            throw new IOException("Production node definition directory contains no JSON definitions: " + root);
        }

        Map<String, Object> nodes = new LinkedHashMap<>();
        Map<String, CompatibilityPins> compatibilityPins = new LinkedHashMap<>();
        List<Object> sourceFiles = new ArrayList<>();
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            JsonValue parsed;
            try {
                parsed = CanonicalCodec.decodePermissive(Files.readAllBytes(file));
            } catch (RuntimeException exception) {
                throw new IOException("Invalid node definition JSON: " + relative, exception);
            }
            sourceFiles.add(Map.of(
                "path", relative,
                "hash", CanonicalJson.genericCanonicalContentHash(parsed.canonicalBytes())));
            Object javaValue = parsed.toJava();
            if (javaValue instanceof List<?> list) {
                for (Object value : list) {
                    addDefinition(nodes, value, relative, compatibilityPins);
                }
            } else {
                addDefinition(nodes, javaValue, relative, compatibilityPins);
            }
        }
        if (nodes.isEmpty()) {
            throw new IOException("Production node definition set is empty");
        }

        AuthoredSource authored = authoredRoot == null ? null : readAuthoredSource(authoredRoot, nodes);
        if (authored != null) {
            mergeAuthoredMappings(nodes, authored.definitions(), compatibilityPins);
        }

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("root", SOURCE_ROOT);
        source.put("mode", SOURCE_MODE);
        source.put("count", sourceFiles.size());
        source.put("files", sourceFiles);
        source.put("hash", CanonicalJson.sha256("resync.flow-graph-schema.source", sourceFiles));
        if (authored != null) {
            source.put("active", authored.provenance());
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("version", FlowGraphMigrationSchemaCatalog.VERSION);
        content.put("source", source);
        content.put("nodes", nodes);
        Map<String, Object> output = new LinkedHashMap<>(content);
        output.put("schemaHash", CanonicalJson.sha256("resync.flow-graph-schema.artifact", content));
        byte[] bytes = CanonicalCodec.encode(JsonValue.fromJava(output));
        return new GeneratedSchema(bytes, output, sourceFiles.size(), nodes.size());
    }

    private static List<Path> sourceFiles(Path root, String label) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException(label + " definition directory is missing: " + root);
        }
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().startsWith("_"))
                .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                .toList();
        }
    }

    private static AuthoredSource readAuthoredSource(Path root, Map<String, Object> nodes) throws IOException {
        List<Path> files = sourceFiles(root, "Authored node definition");
        List<Object> sourceFiles = new ArrayList<>();
        List<AuthoredDefinition> definitions = new ArrayList<>();
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            JsonValue parsed;
            try {
                parsed = CanonicalCodec.decodePermissive(Files.readAllBytes(file));
            } catch (RuntimeException exception) {
                throw new IOException("Invalid authored node definition JSON: " + relative, exception);
            }
            sourceFiles.add(Map.of(
                "path", relative,
                "hash", CanonicalJson.genericCanonicalContentHash(parsed.canonicalBytes())));
            Object javaValue = parsed.toJava();
            if (javaValue instanceof List<?> list) {
                for (int index = 0; index < list.size(); index++) {
                    definitions.add(authoredDefinition(list.get(index), relative, index, nodes));
                }
            } else {
                definitions.add(authoredDefinition(javaValue, relative, 0, nodes));
            }
        }
        definitions = definitions.stream().filter(Objects::nonNull)
            .sorted(Comparator.comparing(AuthoredDefinition::sortKey))
            .toList();

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("root", ACTIVE_SOURCE_ROOT);
        provenance.put("mode", ACTIVE_SOURCE_MODE);
        provenance.put("count", sourceFiles.size());
        provenance.put("files", sourceFiles);
        provenance.put("hash", CanonicalJson.sha256("resync.flow-graph-schema.source", sourceFiles));
        return new AuthoredSource(provenance, definitions);
    }

    private static AuthoredDefinition authoredDefinition(Object value, String source, int index, Map<String, Object> nodes) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Authored node definition must be an object: " + source + "#" + index);
        }
        Map<String, Object> definition = object(raw, "authored node definition in " + source + "#" + index);
        Object rawMigration = definition.get("migrationMapping");
        if (rawMigration == null) {
            return null;
        }
        String id = text(definition.get("id"));
        if (id.isBlank()) {
            throw new IllegalArgumentException("Authored node definition id is missing: " + source + "#" + index);
        }
        List<String> legacyIds = legacyIds(definition, id, source, index);
        List<String> authorities = legacyIds.stream().filter(nodes::containsKey).distinct().sorted().toList();
        if (authorities.isEmpty()) {
            return null;
        }
        Map<String, Object> migration = object(rawMigration, "migrationMapping in " + source + "#" + index);
        int sourceVersion = requiredInteger(migration.get("sourceSchemaVersion"), "migrationMapping.sourceSchemaVersion", source, index);
        int targetVersion = requiredInteger(migration.get("targetSchemaVersion"), "migrationMapping.targetSchemaVersion", source, index);
        if (sourceVersion < 1 || targetVersion <= sourceVersion) {
            throw new IllegalArgumentException("Authored pin migration schema versions are invalid: " + source + "#" + index);
        }
        if (!Boolean.TRUE.equals(migration.get("complete"))) {
            throw new IllegalArgumentException("Authored pin migration mapping must be complete: " + source + "#" + index);
        }
        Object rawPins = migration.get("pins");
        if (!(rawPins instanceof List<?> pins) || pins.isEmpty()) {
            throw new IllegalArgumentException("Authored pin migration mapping must contain pins: " + source + "#" + index);
        }
        List<Object> mappings = new ArrayList<>();
        Set<String> sourcePins = new java.util.HashSet<>();
        Set<String> targetPins = new java.util.HashSet<>();
        for (int pinIndex = 0; pinIndex < pins.size(); pinIndex++) {
            Object rawPin = pins.get(pinIndex);
            if (!(rawPin instanceof Map<?, ?> pinRaw)) {
                throw new IllegalArgumentException("Authored pin migration entry must be an object: " + source + "#" + index + ":" + pinIndex);
            }
            Map<String, Object> pin = object(pinRaw, "authored pin migration entry");
            String sourcePin = pinText(pin, "sourcePinId", "source", source, index, pinIndex);
            String targetPin = pinText(pin, "targetPinId", "target", source, index, pinIndex);
            requirePinId(sourcePin, "sourcePinId", source, index, pinIndex);
            requirePinId(targetPin, "targetPinId", source, index, pinIndex);
            String direction = text(pin.get("direction")).toUpperCase(Locale.ROOT);
            if (!List.of("INPUT", "OUTPUT").contains(direction)) {
                throw new IllegalArgumentException("Authored pin migration direction is invalid: " + source + "#" + index + ":" + pinIndex);
            }
            if (!sourcePins.add(direction + ':' + sourcePin) || !targetPins.add(direction + ':' + targetPin)) {
                throw new IllegalArgumentException("Authored pin migration IDs must be unique: " + source + "#" + index);
            }
            Integer pinSourceVersion = optionalInteger(pin.get("sourceSchemaVersion"));
            Integer pinTargetVersion = optionalInteger(pin.get("targetSchemaVersion"));
            if (pinSourceVersion != null && pinSourceVersion != sourceVersion
                || pinTargetVersion != null && pinTargetVersion != targetVersion) {
                throw new IllegalArgumentException("Authored pin migration entry versions do not match the mapping: " + source + "#" + index + ":" + pinIndex);
            }
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("sourcePinId", sourcePin);
            mapping.put("targetPinId", targetPin);
            mapping.put("direction", direction.toLowerCase(Locale.ROOT));
            mapping.put("sourceSchemaVersion", sourceVersion);
            mapping.put("targetSchemaVersion", targetVersion);
            mappings.add(mapping);
        }
        mappings.sort(Comparator.comparing(FlowGraphMigrationSchemaGenerator::mappingSortKey));
        return new AuthoredDefinition(id, source, index, authorities, mappings);
    }

    private static List<String> legacyIds(Map<String, Object> definition, String id, String source, int index) {
        Object rawLegacyIds = definition.get("legacyIds");
        if (rawLegacyIds == null) {
            return List.of(id);
        }
        if (!(rawLegacyIds instanceof List<?> values)) {
            throw new IllegalArgumentException("Authored node legacyIds must be an array: " + source + "#" + index);
        }
        if (values.isEmpty()) {
            return List.of(id);
        }
        Set<String> result = new LinkedHashSet<>();
        result.add(id);
        for (Object value : values) {
            String legacyId = text(value);
            if (legacyId.isBlank()) {
                throw new IllegalArgumentException("Authored node legacy ID is blank: " + source + "#" + index);
            }
            result.add(legacyId);
        }
        return List.copyOf(result);
    }

    private static void mergeAuthoredMappings(Map<String, Object> nodes, List<AuthoredDefinition> definitions,
                                              Map<String, CompatibilityPins> compatibilityPins) {
        Map<String, List<Object>> mappingsByAuthority = new LinkedHashMap<>();
        for (AuthoredDefinition definition : definitions) {
            for (String authority : definition.authorities()) {
                CompatibilityPins pins = compatibilityPins.get(authority);
                if (pins == null) {
                    throw new IllegalArgumentException("Compatibility pin inventory is missing: " + authority);
                }
                List<Object> projected = definition.mappings().stream()
                    .filter(mapping -> pins.contains(mappingDirection(mapping), mappingSourcePin(mapping)))
                    .toList();
                if (mappingsByAuthority.putIfAbsent(authority, projected) != null) {
                    throw new IllegalArgumentException("Duplicate authored pin migration authority: " + authority);
                }
            }
        }
        mappingsByAuthority.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Map<String, Object> schema = object(nodes.get(entry.getKey()), "migrated node schema");
            List<Object> mappings = entry.getValue();
            if (mappings.isEmpty()) {
                return;
            }
            int targetVersion = mappingTargetVersion(mappings, entry.getKey());
            schema.put("version", targetVersion);
            schema.put("pinMappings", mappings);
            nodes.put(entry.getKey(), schema);
        });
    }

    private static String mappingDirection(Object value) {
        return text(object(value, "pin migration mapping").get("direction"));
    }

    private static String mappingSourcePin(Object value) {
        return text(object(value, "pin migration mapping").get("sourcePinId"));
    }

    private static int mappingTargetVersion(List<Object> mappings, String nodeId) {
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("Authored pin migration mappings are missing: " + nodeId);
        }
        Integer sourceVersion = null;
        Integer targetVersion = null;
        for (int index = 0; index < mappings.size(); index++) {
            Map<String, Object> mapping = object(mappings.get(index), "pin migration mapping");
            int mappingSourceVersion = integer(mapping.get("sourceSchemaVersion"), -1);
            int mappingTargetVersion = integer(mapping.get("targetSchemaVersion"), -1);
            if (mappingSourceVersion < 1 || mappingTargetVersion <= mappingSourceVersion) {
                throw new IllegalArgumentException("Authored pin migration schema versions are invalid: "
                    + nodeId + "#" + index);
            }
            if (sourceVersion != null && sourceVersion != mappingSourceVersion
                || targetVersion != null && targetVersion != mappingTargetVersion) {
                throw new IllegalArgumentException("Authored pin migration schema versions are inconsistent: " + nodeId);
            }
            sourceVersion = mappingSourceVersion;
            targetVersion = mappingTargetVersion;
        }
        return targetVersion;
    }

    private static String mappingSortKey(Object value) {
        Map<String, Object> mapping = object(value, "pin migration mapping");
        return text(mapping.get("sourcePinId")) + '\u0000' + text(mapping.get("targetPinId")) + '\u0000'
            + text(mapping.get("direction"));
    }

    private static String pinText(Map<String, Object> pin, String preferred, String fallback,
                                  String source, int index, int pinIndex) {
        String value = text(pin.get(preferred));
        String alternate = text(pin.get(fallback));
        if (!value.isBlank() && !alternate.isBlank() && !value.equals(alternate)) {
            throw new IllegalArgumentException("Authored pin migration IDs disagree: " + source + "#" + index + ":" + pinIndex);
        }
        value = value.isBlank() ? alternate : value;
        if (value.isBlank()) {
            throw new IllegalArgumentException("Authored pin migration ID is missing: " + source + "#" + index + ":" + pinIndex);
        }
        return value;
    }

    private static void requirePinId(String value, String field, String source, int index, int pinIndex) {
        if (value.length() > 128 || !PIN_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Authored " + field + " is not a canonical PinId: " + source + "#" + index + ":" + pinIndex);
        }
    }

    private static int requiredInteger(Object value, String field, String source, int index) {
        if (value == null) {
            throw new IllegalArgumentException("Authored " + field + " is missing: " + source + "#" + index);
        }
        return integer(value, -1);
    }

    private static Integer optionalInteger(Object value) {
        return value == null ? null : integer(value, -1);
    }

    private static void addDefinition(Map<String, Object> nodes, Object value, String source,
                                      Map<String, CompatibilityPins> compatibilityPins) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Node definition must be an object: " + source);
        }
        Map<String, Object> definition = object(raw, "node definition in " + source);
        Map<String, Object> normalized = compatibilityCopy(definition);
        String id = text(normalized.get("id"));
        if (id.isBlank()) {
            throw new IllegalArgumentException("Node definition id is missing: " + source);
        }
        if (nodes.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate node definition id: " + id);
        }
        int version = integer(normalized.get("schemaVersion"), 1);
        if (version < 1) {
            throw new IllegalArgumentException("Node definition schemaVersion is invalid: " + id);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("version", version);
        schema.put("handlerDefaults", objectOrEmpty(normalized.get("handlerConfig")));
        schema.put("identity", identity(normalized));
        schema.put("inputTypes", inputTypes(normalized.get("inputs"), id));
        schema.put("normalization", normalization(normalized));
        nodes.put(id, schema);
        compatibilityPins.put(id, compatibilityPins(normalized));
    }

    private static CompatibilityPins compatibilityPins(Map<String, Object> definition) {
        return new CompatibilityPins(pinNames(definition.get("inputs")), pinNames(definition.get("outputs")));
    }

    private static Set<String> pinNames(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object member : list) {
            if (member instanceof Map<?, ?> raw) {
                Map<String, Object> pin = object(raw, "pin");
                String name = text(pin.get("name"));
                if (name.isBlank()) {
                    name = text(pin.get("id"));
                }
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return Set.copyOf(names);
    }

    private static Map<String, Object> identity(Map<String, Object> definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("handler", "category", "authorizationPolicy")) {
            String value = text(definition.get(field));
            if (!value.isBlank()) {
                result.put(field, value);
            }
        }
        return result;
    }

    private static Map<String, Object> compatibilityCopy(Map<String, Object> source) {
        Map<String, Object> copy = deepObject(source);
        normalizePins(copy.get("inputs"));
        normalizePins(copy.get("outputs"));
        Object handlerConfig = copy.get("handlerConfig");
        if (handlerConfig instanceof Map<?, ?> rawConfig && !copy.containsKey("property")) {
            Map<String, Object> config = object(rawConfig, "handlerConfig");
            if (config.containsKey("property")) {
                String id = text(copy.get("id"));
                int separator = id.lastIndexOf('.');
                if (separator >= 0 && separator + 1 < id.length()) {
                    config.put("property", id.substring(separator + 1));
                }
            }
        }
        return copy;
    }

    private static void normalizePins(Object value) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object member : list) {
            if (!(member instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> pin = object(raw, "pin");
            if (pin.containsKey("id") && !pin.containsKey("name")) {
                pin.put("name", pin.get("id"));
                pin.remove("id");
            }
            String type = text(pin.get("type")).toUpperCase(Locale.ROOT);
            if (List.of("INPUT", "OUTPUT").contains(type)) {
                pin.remove("type");
                pin.putIfAbsent("pinType", "DATA");
            }
            if (!pin.containsKey("pinType")) {
                switch (type) {
                    case "FLOW", "EXEC", "EXECUTION" -> {
                        pin.put("pinType", "FLOW");
                        pin.remove("type");
                    }
                    case "DATA" -> {
                        pin.put("pinType", "DATA");
                        pin.remove("type");
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private static Map<String, Object> inputTypes(Object value, String nodeId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) {
            return result;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Node inputs must be an array: " + nodeId);
        }
        for (Object member : list) {
            if (!(member instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Node input pin must be an object: " + nodeId);
            }
            Map<String, Object> pin = object(raw, "input pin");
            String name = text(pin.get("name"));
            if (name.isBlank()) {
                throw new IllegalArgumentException("Node input pin name is missing: " + nodeId);
            }
            String type = semanticType(text(pin.get("optionsSource")), text(pin.get("dataType")));
            String pinType = text(pin.get("pinType")).toLowerCase(Locale.ROOT);
            if (type.isBlank()) {
                type = "FLOW".equalsIgnoreCase(pinType) ? "execution" : "any";
            }
            result.put(name, normalizeType(type));
        }
        return result;
    }

    private static Map<String, Object> normalization(Map<String, Object> definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (definition.containsKey("normalization")) {
            result.put("declared", deepCopy(definition.get("normalization")));
        }
        Map<String, Object> pins = new LinkedHashMap<>();
        Object inputs = definition.get("inputs");
        if (inputs instanceof List<?> list) {
            for (Object member : list) {
                if (!(member instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> pin = object(raw, "input pin");
                String name = text(pin.get("name"));
                if (name.isBlank()) {
                    continue;
                }
                pins.put(name, pinNormalization(pin));
            }
        }
        result.put("inputs", pins);
        return result;
    }

    private static Map<String, Object> pinNormalization(Map<String, Object> pin) {
        Map<String, Object> result = new LinkedHashMap<>();
        String type = semanticType(text(pin.get("optionsSource")), text(pin.get("dataType")));
        String pinType = text(pin.get("pinType")).toLowerCase(Locale.ROOT);
        result.put("type", normalizeType(type.isBlank() ? ("FLOW".equalsIgnoreCase(pinType) ? "execution" : "any") : type));
        if (pin.containsKey("pinType")) {
            result.put("pinType", text(pin.get("pinType")).toUpperCase(Locale.ROOT));
        }
        for (String field : List.of("widget", "options", "optionsSource", "defaultValue", "constraints", "visibleWhen", "description", "optional", "repeatable")) {
            if (pin.containsKey(field)) {
                result.put(field, deepCopy(pin.get(field)));
            }
        }
        return result;
    }

    private static String semanticType(String optionsSource, String fallback) {
        if (!optionsSource.startsWith("server:resync:")) {
            return fallback;
        }
        return switch (optionsSource.substring("server:resync:".length())) {
            case "flow" -> "flow_id";
            case "function" -> "function";
            case "command" -> "command_id";
            case "custom_content" -> "custom_content_id";
            case "gui" -> "gui_id";
            case "scoreboard" -> "scoreboard_id";
            case "tab" -> "tab_id";
            case "chat" -> "chat_id";
            case "motd_profile" -> "motd_profile_id";
            case "message_rule" -> "message_rule_id";
            case "recipe_definition" -> "recipe_id";
            case "text_template" -> "text_template_id";
            case "advancement_tree" -> "advancement_tree_id";
            case "dialog" -> "dialog_id";
            case "trade_profile" -> "trade_profile_id";
            case "npc_definition" -> "npc_id";
            case "loot_table" -> "loot_table_id";
            case "worldgen" -> "worldgen_project";
            default -> fallback;
        };
    }

    private static String normalizeType(String type) {
        String value = type == null || type.isBlank() ? "any" : type.strip();
        StringBuilder output = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current)) {
                continue;
            }
            if (Character.isLetterOrDigit(current) || current == '_' || current == ':' || current == '.' || current == '-') {
                output.append(Character.toLowerCase(current));
            } else if (current == '<' || current == '>' || current == ',') {
                output.append(current);
            } else {
                throw new IllegalArgumentException("Invalid dataType expression: " + type);
            }
        }
        if (output.isEmpty()) {
            return "any";
        }
        String normalized = output.toString();
        if (parseType(normalized, 0) != normalized.length()) {
            throw new IllegalArgumentException("Invalid dataType expression: " + type);
        }
        return normalized;
    }

    private static int parseType(String value, int offset) {
        int start = offset;
        while (offset < value.length()) {
            char current = value.charAt(offset);
            if (Character.isLetterOrDigit(current) || current == '_' || current == ':' || current == '.' || current == '-') {
                offset++;
            } else {
                break;
            }
        }
        if (start == offset) {
            throw new IllegalArgumentException("Invalid dataType expression: " + value);
        }
        if (offset >= value.length() || value.charAt(offset) != '<') {
            return offset;
        }
        offset = parseType(value, offset + 1);
        while (offset < value.length() && value.charAt(offset) == ',') {
            offset = parseType(value, offset + 1);
        }
        if (offset >= value.length() || value.charAt(offset) != '>') {
            throw new IllegalArgumentException("Invalid dataType expression: " + value);
        }
        return offset + 1;
    }

    private static int integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected a numeric schema version");
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Schema version must be an integer", exception);
        }
    }

    private static String text(Object value) {
        return value instanceof String string ? string : "";
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        return value == null ? new LinkedHashMap<>() : object(value, "object");
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Expected an object for " + field);
        }
        return object(raw, field);
    }

    private static Map<String, Object> object(Map<?, ?> value, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Object key is not text for " + field);
            }
            result.put(key, deepCopy(entry.getValue()));
        }
        return result;
    }

    private static Map<String, Object> deepObject(Map<String, Object> value) {
        return object(value, "object");
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            return object(map, "object");
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FlowGraphMigrationSchemaGenerator::deepCopy).toList();
        }
        return value;
    }

    private record AuthoredSource(Map<String, Object> provenance, List<AuthoredDefinition> definitions) {
        private AuthoredSource {
            provenance = Map.copyOf(Objects.requireNonNull(provenance, "provenance"));
            definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        }
    }

    private record AuthoredDefinition(String id, String source, int index, List<String> authorities, List<Object> mappings) {
        private AuthoredDefinition {
            id = Objects.requireNonNull(id, "id");
            source = Objects.requireNonNull(source, "source");
            authorities = List.copyOf(Objects.requireNonNull(authorities, "authorities"));
            mappings = List.copyOf(Objects.requireNonNull(mappings, "mappings"));
        }

        private String sortKey() {
            return authorities.getFirst() + '\u0000' + id + '\u0000' + source + '\u0000' + index;
        }
    }

    private record CompatibilityPins(Set<String> inputs, Set<String> outputs) {
        private CompatibilityPins {
            inputs = Set.copyOf(Objects.requireNonNull(inputs, "inputs"));
            outputs = Set.copyOf(Objects.requireNonNull(outputs, "outputs"));
        }

        private boolean contains(String direction, String pin) {
            return switch (direction) {
                case "input" -> inputs.contains(pin);
                case "output" -> outputs.contains(pin);
                default -> false;
            };
        }
    }

    public record GeneratedSchema(byte[] bytes, Map<String, Object> value, int sourceFileCount, int nodeCount) {
        public GeneratedSchema {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            value = Map.copyOf(Objects.requireNonNull(value, "value"));
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
