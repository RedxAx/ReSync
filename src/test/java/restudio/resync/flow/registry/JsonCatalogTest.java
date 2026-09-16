package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.JsonHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "json.json");
    private static final List<String> IDS = List.of(
        "json_parse", "json_to_string", "json_get", "json_has", "json_keys", "json_merge", "json_create", "json_set_array");

    @Test
    void jsonCatalogPublishesExactRuntimeContractsAndCompleteFlowMigrations() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new JsonHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        Map<String, String> operations = Map.ofEntries(
            Map.entry("json_parse", "json_parse"),
            Map.entry("json_to_string", "json_to_string"),
            Map.entry("json_get", "json_get"),
            Map.entry("json_has", "json_has"),
            Map.entry("json_keys", "json_keys"),
            Map.entry("json_merge", "json_merge"),
            Map.entry("json_create", "json_create"),
            Map.entry("json_set_array", "json_set_array"));
        Map<String, String> legacyIds = Map.ofEntries(
            Map.entry("json_parse", "json.parse"),
            Map.entry("json_to_string", "json.to.string"),
            Map.entry("json_get", "json.get"),
            Map.entry("json_has", "json.has"),
            Map.entry("json_keys", "json.keys"),
            Map.entry("json_merge", "json.merge"),
            Map.entry("json_create", "json.create"),
            Map.entry("json_set_array", "json.set.array"));
        Map<String, List<String>> inputIds = Map.ofEntries(
            Map.entry("json_parse", List.of("flow", "json_string")),
            Map.entry("json_to_string", List.of("flow", "object")),
            Map.entry("json_get", List.of("flow", "object", "path")),
            Map.entry("json_has", List.of("flow", "object", "path")),
            Map.entry("json_keys", List.of("flow", "object")),
            Map.entry("json_merge", List.of("flow", "object1", "object2")),
            Map.entry("json_create", List.of("flow")),
            Map.entry("json_set_array", List.of("flow", "values")));
        Map<String, List<String>> outputIds = Map.ofEntries(
            Map.entry("json_parse", List.of("output_flow", "object")),
            Map.entry("json_to_string", List.of("output_flow", "string")),
            Map.entry("json_get", List.of("output_flow", "value")),
            Map.entry("json_has", List.of("output_flow", "has")),
            Map.entry("json_keys", List.of("output_flow", "keys")),
            Map.entry("json_merge", List.of("output_flow", "merged")),
            Map.entry("json_create", List.of("output_flow", "object")),
            Map.entry("json_set_array", List.of("output_flow", "array")));
        Map<String, List<String>> inputTypes = Map.ofEntries(
            Map.entry("json_parse", List.of("execution", "string")),
            Map.entry("json_to_string", List.of("execution", "any")),
            Map.entry("json_get", List.of("execution", "map<any,any>", "string")),
            Map.entry("json_has", List.of("execution", "map<any,any>", "string")),
            Map.entry("json_keys", List.of("execution", "map<any,any>")),
            Map.entry("json_merge", List.of("execution", "map<any,any>", "map<any,any>")),
            Map.entry("json_create", List.of("execution")),
            Map.entry("json_set_array", List.of("execution", "list<any>")));
        Map<String, List<String>> outputTypes = Map.ofEntries(
            Map.entry("json_parse", List.of("execution", "any")),
            Map.entry("json_to_string", List.of("execution", "string")),
            Map.entry("json_get", List.of("execution", "any")),
            Map.entry("json_has", List.of("execution", "boolean")),
            Map.entry("json_keys", List.of("execution", "list<any>")),
            Map.entry("json_merge", List.of("execution", "map<any,any>")),
            Map.entry("json_create", List.of("execution", "map<any,any>")),
            Map.entry("json_set_array", List.of("execution", "list<any>")));

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("json", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("generic-json", raw.get("handlerCapability").getAsString());
            assertEquals("JsonHandler", raw.get("handler").getAsString());
            assertEquals(2, raw.get("schemaVersion").getAsInt());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(List.of(legacyIds.get(definition.getId())), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertEquals(inputIds.get(definition.getId()), definition.getInputs().stream().map(pin -> pin.getId().value()).toList());
            assertEquals(outputIds.get(definition.getId()), definition.getOutputs().stream().map(pin -> pin.getId().value()).toList());
            assertEquals(inputTypes.get(definition.getId()), definition.getInputs().stream().map(pin -> pin.getTypeRef().toString()).toList());
            assertEquals(outputTypes.get(definition.getId()), definition.getOutputs().stream().map(pin -> pin.getTypeRef().toString()).toList());
            assertTrue(definition.getInputs().stream().allMatch(pin -> pin.getDisplayName() != null && !pin.getDisplayName().isBlank()));
            assertTrue(definition.getOutputs().stream().allMatch(pin -> pin.getDisplayName() != null && !pin.getDisplayName().isBlank()));
            assertEquals("Flow", definition.getInputs().getFirst().getDisplayName());
            assertEquals("Flow", definition.getOutputs().getFirst().getDisplayName());
            assertEquals("flow", raw.getAsJsonArray("inputs").get(0).getAsJsonObject().get("name").getAsString());
            assertEquals("flow", raw.getAsJsonArray("outputs").get(0).getAsJsonObject().get("name").getAsString());

            JsonObject migration = raw.getAsJsonObject("migrationMapping");
            assertEquals(1, migration.get("sourceSchemaVersion").getAsInt());
            assertEquals(2, migration.get("targetSchemaVersion").getAsInt());
            assertTrue(migration.get("complete").getAsBoolean());
            List<String> expectedMappings = expectedMappings(inputIds.get(definition.getId()), outputIds.get(definition.getId()));
            assertEquals(expectedMappings, migration.getAsJsonArray("pins").asList().stream()
                .map(pin -> {
                    JsonObject value = pin.getAsJsonObject();
                    return value.get("direction").getAsString() + ":" + value.get("source").getAsString()
                        + "->" + value.get("target").getAsString();
                }).toList());
            assertEquals(expectedMappings, definition.getPinMigrationMappings().stream()
                .map(mapping -> mapping.direction().name().toLowerCase(Locale.ROOT) + ":"
                    + mapping.sourcePinId().value() + "->" + mapping.targetPinId().value()).toList());
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
        }

        assertEquals("any", rawById.get("json_parse").getAsJsonArray("outputs").get(1).getAsJsonObject().get("dataType").getAsString());
        assertFalse(rawById.containsKey("json_set"));
        assertFalse(rawById.containsKey("json_delete"));
        assertTrue(rawById.get("json_parse").get("description").getAsString().contains("65536"));
        assertTrue(rawById.get("json_parse").get("description").getAsString().contains("64"));
        String toStringDescription = rawById.get("json_to_string").get("description").getAsString();
        assertTrue(toStringDescription.contains("CanonicalJson"));
        assertTrue(toStringDescription.contains("Character"));
        assertTrue(toStringDescription.contains("UUID"));
        assertTrue(toStringDescription.contains("Set"));
        assertTrue(toStringDescription.contains("Iterable"));
        assertTrue(toStringDescription.contains("array"));
        assertTrue(toStringDescription.contains("canonical JSON contract"));
        String getDescription = rawById.get("json_get").get("description").getAsString();
        assertTrue(getDescription.contains("dotted"));
        assertTrue(getDescription.contains("blank paths"));
        assertTrue(getDescription.contains("non-object"));
        assertTrue(getDescription.contains("fail before output"));
        String hasDescription = rawById.get("json_has").get("description").getAsString();
        assertTrue(hasDescription.contains("blank paths"));
        assertTrue(hasDescription.contains("non-object"));
        assertTrue(hasDescription.contains("fail before output"));
        String keysDescription = rawById.get("json_keys").get("description").getAsString();
        assertTrue(keysDescription.contains("canonical code-point-sorted order"));
        assertTrue(rawById.get("json_keys").getAsJsonArray("outputs").get(1).getAsJsonObject().get("description").getAsString()
            .contains("canonical code-point-sorted order"));
        String mergeDescription = rawById.get("json_merge").get("description").getAsString();
        assertTrue(mergeDescription.contains("recursively detached canonical copies"));
        assertFalse(mergeDescription.contains("copied by reference"));
        assertTrue(rawById.get("json_create").get("description").getAsString().contains("fresh"));
        String arrayDescription = rawById.get("json_set_array").get("description").getAsString();
        assertTrue(arrayDescription.contains("recursively detached"));
        assertFalse(arrayDescription.contains("shallow"));
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static List<String> expectedMappings(List<String> inputs, List<String> outputs) {
        List<String> mappings = inputs.stream().map(id -> "input:" + id + "->" + id).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        mappings.addAll(outputs.stream().map(id -> "output:" + ("output_flow".equals(id) ? "flow" : id) + "->" + id).toList());
        return mappings;
    }
}
