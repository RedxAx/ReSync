package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.migration.FlowNodeMigrationMap;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.UuidHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "uuid.json");
    private static final List<String> IDS = List.of(
        "uuid_from_string", "uuid_to_string", "uuid_generate", "uuid_version", "uuid_timestamp");

    @Test
    void uuidCatalogPublishesExactHandlerContractsWithoutLegacyRandomNode() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());
        assertFalse(rawById.containsKey("uuid.random"));
        Map<String, String> compatibility = FlowNodeMigrationMap.load();
        assertEquals("uuid_generate", compatibility.get("uuid.random"));
        assertTrue(IDS.stream().noneMatch(compatibility::containsKey), compatibility.toString());

        HandlerRegistry handlers = new HandlerRegistry();
        new UuidHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        Map<String, String> operations = Map.of(
            "uuid_from_string", "uuid_from_string",
            "uuid_to_string", "uuid_to_string",
            "uuid_generate", "uuid_generate",
            "uuid_version", "uuid_version",
            "uuid_timestamp", "uuid_timestamp");
        Map<String, List<String>> legacyIds = Map.of(
            "uuid_from_string", List.of("uuid.from.string"),
            "uuid_to_string", List.of("uuid.to.string"),
            "uuid_generate", List.of("utility.uuid_generate", "uuid.random"),
            "uuid_version", List.of("utility.uuid_version"),
            "uuid_timestamp", List.of("utility.uuid_timestamp"));
        Map<String, List<PinContract>> inputs = Map.of(
            "uuid_from_string", List.of(new PinContract("uuid_string", "UUID String", "string")),
            "uuid_to_string", List.of(new PinContract("uuid_object", "UUID", "uuid")),
            "uuid_generate", List.of(),
            "uuid_version", List.of(new PinContract("uuid_object", "UUID", "uuid")),
            "uuid_timestamp", List.of(new PinContract("uuid_object", "UUID", "uuid")));
        Map<String, List<PinContract>> outputs = Map.of(
            "uuid_from_string", List.of(new PinContract("uuid_object", "UUID", "uuid")),
            "uuid_to_string", List.of(new PinContract("uuid_string", "UUID String", "string")),
            "uuid_generate", List.of(new PinContract("uuid_string", "UUID String", "string")),
            "uuid_version", List.of(new PinContract("version", "Version", "number")),
            "uuid_timestamp", List.of(new PinContract("timestamp", "Timestamp", "number")));

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("uuid", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("uuid", raw.get("handlerCapability").getAsString());
            assertEquals("UuidHandler", raw.get("handler").getAsString());
            assertEquals(2, raw.get("schemaVersion").getAsInt());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertTrue(raw.get("description").getAsString().toLowerCase().contains("fail"));
            assertFalse(raw.has("migrationMapping"));
            assertTrue(definition.getPinMigrationMappings().isEmpty());
            assertPins(raw.getAsJsonArray("inputs"), inputs.get(definition.getId()));
            assertPins(raw.getAsJsonArray("outputs"), outputs.get(definition.getId()));
            assertEquals(inputs.get(definition.getId()).stream().map(PinContract::id).toList(),
                definition.getInputs().stream().map(pin -> pin.getId().value()).toList());
            assertEquals(outputs.get(definition.getId()).stream().map(PinContract::id).toList(),
                definition.getOutputs().stream().map(pin -> pin.getId().value()).toList());
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
        }

        assertEquals("", rawById.get("uuid_from_string").getAsJsonArray("inputs").get(0).getAsJsonObject()
            .get("defaultValue").getAsString());
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static void assertPins(JsonArray actual, List<PinContract> expected) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            PinContract pin = expected.get(index);
            JsonObject actualPin = actual.get(index).getAsJsonObject();
            assertEquals(pin.id(), actualPin.get("id").getAsString());
            assertEquals(pin.displayName(), actualPin.get("displayName").getAsString());
            assertEquals(pin.id(), actualPin.get("name").getAsString());
            assertEquals("DATA", actualPin.get("pinType").getAsString());
            assertEquals(pin.dataType(), actualPin.get("dataType").getAsString());
            assertTrue(actualPin.has("description"));
            assertFalse(actualPin.get("description").getAsString().isBlank());
        }
    }

    private record PinContract(String id, String displayName, String dataType) {
    }

}
