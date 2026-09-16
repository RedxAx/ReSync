package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.GenericStringHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringSliceCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "string_slice.json");
    private static final List<String> IDS = List.of("string_substring", "string_split", "string_index_of", "string_last_index_of");

    @Test
    void stringSliceCatalogPublishesExactRuntimeContracts() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new GenericStringHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        Map<String, Integer> schemaVersions = Map.of(
            "string_substring", 1,
            "string_split", 1,
            "string_index_of", 2,
            "string_last_index_of", 2);
        Map<String, String> operations = Map.of(
            "string_substring", "substring",
            "string_split", "split",
            "string_index_of", "index_of",
            "string_last_index_of", "last_index_of");
        Map<String, String> legacyIds = Map.of(
            "string_substring", "string.substring",
            "string_split", "string.split",
            "string_index_of", "string.index_of",
            "string_last_index_of", "string.last_index_of");
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("string", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("generic-string", raw.get("handlerCapability").getAsString());
            assertEquals("GenericStringHandler", raw.get("handler").getAsString());
            assertEquals(schemaVersions.get(definition.getId()), raw.get("schemaVersion").getAsInt());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(List.of(legacyIds.get(definition.getId())), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertFalse(raw.has("migrationMapping"));
            assertTrue(definition.getPinMigrationMappings().isEmpty());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertPinsAreExplicit(raw);
        }

        JsonObject substring = rawById.get("string_substring");
        assertPins(substring, List.of("value", "start", "length"), List.of("string", "number", "number"), List.of("result"), List.of("string"));
        assertEquals(0, pin(substring, "inputs", "start").get("defaultValue").getAsInt());
        assertEquals(0, pin(substring, "inputs", "start").getAsJsonObject("constraints").get("min").getAsInt());
        assertEquals(65536, pin(substring, "inputs", "start").getAsJsonObject("constraints").get("max").getAsInt());
        JsonObject length = pin(substring, "inputs", "length");
        assertTrue(length.get("optional").getAsBoolean());
        assertEquals(0, length.getAsJsonObject("constraints").get("min").getAsInt());
        assertEquals(65536, length.getAsJsonObject("constraints").get("max").getAsInt());
        assertTrue(substring.get("description").getAsString().contains("65536"));
        assertTrue(substring.get("description").getAsString().contains("negative length"));
        assertTrue(length.get("description").getAsString().contains("negative"));

        JsonObject split = rawById.get("string_split");
        assertPins(split, List.of("value", "delimiter"), List.of("string", "string"), List.of("result"), List.of("list<string>"));
        JsonObject delimiter = pin(split, "inputs", "delimiter");
        assertEquals(",", delimiter.get("defaultValue").getAsString());
        assertEquals(65536, delimiter.getAsJsonObject("constraints").get("max").getAsInt());
        assertTrue(split.get("description").getAsString().contains("literal delimiter"));
        assertTrue(pin(split, "outputs", "result").get("description").getAsString().contains("literal delimiter"));

        JsonObject indexOf = rawById.get("string_index_of");
        assertPins(indexOf, List.of("value", "substring"), List.of("string", "string"), List.of("index"), List.of("number"));
        assertTrue(indexOf.get("description").getAsString().contains("-1 means"));
        assertTrue(pin(indexOf, "outputs", "index").get("description").getAsString().contains("first substring occurrence"));

        JsonObject lastIndexOf = rawById.get("string_last_index_of");
        assertPins(lastIndexOf, List.of("value", "substring"), List.of("string", "string"), List.of("index"), List.of("number"));
        assertTrue(lastIndexOf.get("description").getAsString().contains("-1 means"));
        assertTrue(pin(lastIndexOf, "outputs", "index").get("description").getAsString().contains("last substring occurrence"));
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static void assertPinsAreExplicit(JsonObject node) {
        for (String direction : List.of("inputs", "outputs")) {
            for (JsonElement element : node.getAsJsonArray(direction)) {
                JsonObject pin = element.getAsJsonObject();
                assertTrue(pin.has("id"));
                assertTrue(pin.has("displayName"));
                assertEquals(pin.get("id").getAsString(), pin.get("name").getAsString());
                assertTrue(pin.has("description"));
                assertFalse(pin.get("description").getAsString().isBlank());
                assertFalse("FLOW".equalsIgnoreCase(pin.get("pinType").getAsString()));
            }
        }
    }

    private static void assertPins(JsonObject node, List<String> inputIds, List<String> inputTypes,
                                   List<String> outputIds, List<String> outputTypes) {
        assertEquals(inputIds, pinIds(node.getAsJsonArray("inputs")));
        assertEquals(inputTypes, pinTypes(node.getAsJsonArray("inputs")));
        assertEquals(outputIds, pinIds(node.getAsJsonArray("outputs")));
        assertEquals(outputTypes, pinTypes(node.getAsJsonArray("outputs")));
    }

    private static JsonObject pin(JsonObject node, String direction, String id) {
        for (JsonElement element : node.getAsJsonArray(direction)) {
            JsonObject pin = element.getAsJsonObject();
            if (id.equals(pin.get("id").getAsString())) {
                return pin;
            }
        }
        throw new AssertionError("Missing pin " + id + " in " + node.get("id").getAsString());
    }

    private static List<String> pinIds(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("id").getAsString()).toList();
    }

    private static List<String> pinTypes(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("dataType").getAsString()).toList();
    }
}
