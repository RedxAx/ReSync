package restudio.resync.upgrade.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.identity.PinId;
import restudio.resync.upgrade.UpgradeNodeDefinitionSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeneratedFlowGraphMigrationSchemaParityTest {
    private static final String RESOURCE = "/restudio/resync/flow/migration/flow-graph-schema-v2.json";

    @Test
    void generatedSchemaMatchesCompatibilityLoaderForEveryProductionDefinition() throws Exception {
        Map<String, Object> root = schemaRoot();
        Map<String, Object> source = object(root.get("source"));
        List<Object> sourceFiles = list(source.get("files"));

        Path definitionsRoot = UpgradeNodeDefinitionSource.root();
        try (var files = Files.walk(definitionsRoot)) {
            long sourceFileCount = files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().startsWith("_"))
                .count();
            assertEquals(sourceFileCount, sourceFiles.size());
        }
        List<NodeDefinition> definitions = new NodeDefinitionLoader().loadFromDirectory(definitionsRoot,
            NodeDefinitionLoader.CatalogSource.COMPATIBILITY);
        Map<String, JsonElement> rawDefinitions = rawDefinitions(definitionsRoot);
        Map<String, NodeDefinition> byId = new HashMap<>();
        for (NodeDefinition definition : definitions) {
            assertTrue(byId.put(definition.getId(), definition) == null, "Duplicate loaded definition: " + definition.getId());
        }

        Map<String, Object> nodes = object(root.get("nodes"));
        assertEquals(rawDefinitions.keySet(), nodes.keySet());
        for (NodeDefinition definition : definitions) {
            Map<String, Object> schema = object(nodes.get(definition.getId()));
            int expectedVersion = definition.getSchemaVersion();
            Object rawMappings = schema.get("pinMappings");
            if (rawMappings instanceof List<?> mappings && !mappings.isEmpty()) {
                expectedVersion = integer(object(mappings.getFirst()).get("targetSchemaVersion"));
            }
            assertEquals(expectedVersion, integer(schema.get("version")));
            assertCanonicalEquals(definition.getHandlerConfig(), schema.get("handlerDefaults"));
            Map<String, Object> inputTypes = object(schema.get("inputTypes"));
            JsonElement rawDefinition = rawDefinitions.get(definition.getId());
            assertNotNull(rawDefinition, "Missing raw migrated definition: " + definition.getId());
            List<String> rawInputNames = rawPinNames(rawDefinition, "inputs", definition.getId());
            assertEquals(definition.getInputs().size(), rawInputNames.size(), definition.getId());
            Map<String, String> expectedTypes = new LinkedHashMap<>();
            for (int index = 0; index < definition.getInputs().size(); index++) {
                String rawInputName = rawInputNames.get(index);
                assertTrue(expectedTypes.put(rawInputName, definition.getInputs().get(index).getTypeRef().toString()) == null,
                    "Duplicate raw input key for " + definition.getId() + ": " + rawInputName);
            }
            assertEquals(expectedTypes, inputTypes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> text(entry.getValue()))));
            assertPinMappingsUseRawWireKeys(schema, rawDefinition, definition);
        }

        long expectedRgbInputs = definitions.stream()
            .flatMap(definition -> definition.getInputs().stream())
            .filter(pin -> pin.getDataType() == FlowDataType.RGB_COLOR)
            .count();
        long generatedRgbInputs = nodes.values().stream()
            .map(this::object)
            .map(schema -> object(schema.get("inputTypes")))
            .flatMap(inputTypes -> inputTypes.values().stream())
            .filter(value -> "rgb_color".equals(text(value)))
            .count();
        assertEquals(expectedRgbInputs, generatedRgbInputs);
        assertTrue(expectedRgbInputs >= 9);
        Map<String, Object> timeFormat = object(nodes.get("time.format"));
        assertFalse(object(timeFormat.get("inputTypes")).containsKey("color"));
        assertEquals(Set.of("listA", "listB"), object(object(nodes.get("list.concat")).get("inputTypes")).keySet());
        assertEquals(Set.of("mapA", "mapB"), object(object(nodes.get("map.merge")).get("inputTypes")).keySet());
    }

    private void assertPinMappingsUseRawWireKeys(Map<String, Object> schema, JsonElement rawDefinition,
                                                  NodeDefinition definition) {
        Object rawMappings = schema.get("pinMappings");
        if (rawMappings == null) {
            return;
        }
        List<Object> mappings = list(rawMappings);
        Map<String, Set<String>> rawPins = Map.of(
            "input", Set.copyOf(rawPinNames(rawDefinition, "inputs", definition.getId())),
            "output", Set.copyOf(rawPinNames(rawDefinition, "outputs", definition.getId())));
        Set<String> sourceKeys = new HashSet<>();
        Set<String> targetKeys = new HashSet<>();
        for (Object rawMapping : mappings) {
            Map<String, Object> mapping = object(rawMapping);
            String direction = text(mapping.get("direction"));
            assertTrue(rawPins.containsKey(direction), "Unknown pin mapping direction for " + definition.getId());
            String source = text(mapping.get("sourcePinId"));
            String target = text(mapping.get("targetPinId"));
            Set<String> rawPinsForDirection = rawPins.get(direction);
            if (!rawPinsForDirection.isEmpty()) {
                assertTrue(rawPinsForDirection.contains(source),
                    "Pin mapping source must use the raw persisted wire key for " + definition.getId() + ": " + source);
            }
            assertTrue(sourceKeys.add(direction + ":" + source),
                "Duplicate source pin mapping for " + definition.getId() + ": " + source);
            String canonicalTarget = PinId.of(target).value();
            assertEquals(target, canonicalTarget,
                "Pin mapping target must already be canonical for " + definition.getId());
            assertTrue(targetKeys.add(direction + ":" + canonicalTarget),
                "Duplicate canonical target pin mapping for " + definition.getId() + ": " + target);
            int sourceVersion = integer(mapping.get("sourceSchemaVersion"));
            int targetVersion = integer(mapping.get("targetSchemaVersion"));
            assertTrue(sourceVersion >= 1, definition.getId());
            assertTrue(targetVersion > sourceVersion, definition.getId());
            assertEquals(integer(schema.get("version")), targetVersion, definition.getId());
        }
    }

    @Test
    void missingAndStaleGeneratedSchemasFailClosed() throws Exception {
        assertThrows(IllegalStateException.class, () -> LegacyFlowGraphSnapshotAdapter.loadProductSchemas(null));
        byte[] source;
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            source = input.readAllBytes();
        }
        Map<String, Object> stale = schemaRoot(source);
        stale.put("schemaHash", "0".repeat(64));
        byte[] staleBytes = CanonicalCodec.encode(JsonValue.fromJava(stale));
        assertThrows(IllegalStateException.class,
            () -> LegacyFlowGraphSnapshotAdapter.loadProductSchemas(new ByteArrayInputStream(staleBytes)));
    }

    private Map<String, Object> schemaRoot() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            return schemaRoot(input.readAllBytes());
        }
    }

    private Map<String, Object> schemaRoot(byte[] bytes) {
        return object(CanonicalCodec.decodePermissive(bytes).toJava());
    }

    private Map<String, JsonElement> rawDefinitions(Path definitionsRoot) throws IOException {
        Map<String, JsonElement> result = new HashMap<>();
        try (var files = Files.walk(definitionsRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().startsWith("_"))
                .sorted().toList()) {
                JsonElement root = JsonParser.parseString(Files.readString(file));
                if (root.isJsonArray()) {
                    for (JsonElement definition : root.getAsJsonArray()) {
                        addRawDefinition(result, definition, file);
                    }
                } else {
                    addRawDefinition(result, root, file);
                }
            }
        }
        return result;
    }

    private void addRawDefinition(Map<String, JsonElement> definitions, JsonElement value, Path source) {
        assertTrue(value != null && value.isJsonObject(), "Invalid raw definition in " + source);
        String id = text(value.getAsJsonObject().get("id"));
        assertTrue(!id.isBlank(), "Raw definition ID is missing in " + source);
        assertTrue(definitions.put(id, value) == null, "Duplicate raw definition: " + id);
    }

    private List<String> rawPinNames(JsonElement rawDefinition, String direction, String nodeId) {
        JsonElement pinsValue = rawDefinition.getAsJsonObject().get(direction);
        if (pinsValue == null || pinsValue.isJsonNull()) {
            return List.of();
        }
        assertTrue(pinsValue.isJsonArray(), "Raw " + direction + " pins must be an array: " + nodeId);
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement pin : pinsValue.getAsJsonArray()) {
            assertTrue(pin != null && pin.isJsonObject(), "Raw " + direction + " pin must be an object: " + nodeId);
            var pinObject = pin.getAsJsonObject();
            String wireKey = text(pinObject.get("name"));
            if (wireKey.isBlank()) {
                wireKey = text(pinObject.get("id"));
            }
            assertTrue(!wireKey.isBlank(), "Raw " + direction + " pin key is missing: " + nodeId);
            assertTrue(seen.add(wireKey), "Duplicate raw " + direction + " key for " + nodeId + ": " + wireKey);
            names.add(wireKey);
        }
        return names;
    }

    private static void assertCanonicalEquals(Object expected, Object actual) {
        byte[] expectedBytes = CanonicalCodec.encode(JsonValue.fromJava(expected == null ? Map.of() : expected));
        byte[] actualBytes = CanonicalCodec.encode(JsonValue.fromJava(actual));
        assertArrayEquals(expectedBytes, actualBytes);
    }

    private Map<String, Object> object(Object value) {
        assertTrue(value instanceof Map<?, ?>);
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            assertTrue(entry.getKey() instanceof String);
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private List<Object> list(Object value) {
        assertTrue(value instanceof List<?>);
        return new ArrayList<>((List<?>) value);
    }

    private static String text(Object value) {
        return value instanceof String string ? string : "";
    }

    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : "";
    }

    private static int integer(Object value) {
        assertTrue(value instanceof Number);
        return ((Number) value).intValue();
    }
}
