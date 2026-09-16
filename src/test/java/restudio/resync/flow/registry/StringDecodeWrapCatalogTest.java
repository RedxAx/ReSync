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

class StringDecodeWrapCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "string_decode_wrap.json");
    private static final List<String> IDS = List.of("string_base64_decode", "string_url_decode", "string_word_wrap",
        "string_to_json", "string_from_json");

    @Test
    void stringDecodeWrapCatalogPublishesExactRuntimeContracts() throws Exception {
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

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        Map<String, Integer> schemaVersions = Map.of(
            "string_base64_decode", 1,
            "string_url_decode", 1,
            "string_word_wrap", 1,
            "string_to_json", 2,
            "string_from_json", 2);
        Map<String, String> operations = Map.of(
            "string_base64_decode", "base64_decode",
            "string_url_decode", "url_decode",
            "string_word_wrap", "word_wrap",
            "string_to_json", "to_json",
            "string_from_json", "from_json");
        Map<String, String> legacyIds = Map.of(
            "string_base64_decode", "string.base64_decode",
            "string_url_decode", "string.url_decode",
            "string_word_wrap", "string.word_wrap",
            "string_to_json", "string.to_json",
            "string_from_json", "string.from_json");
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
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertTrue(definition.getPinMigrationMappings().isEmpty());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertPinsAreExplicit(raw);
        }

        JsonObject base64 = rawById.get("string_base64_decode");
        assertPins(base64, List.of("encoded"), List.of("string"), List.of("decoded"), List.of("string"));
        assertEquals("base64_decode", base64.getAsJsonObject("handlerConfig").get("operation").getAsString());
        assertEquals(65536, pin(base64, "inputs", "encoded").getAsJsonObject("constraints").get("max").getAsInt());
        assertTrue(base64.get("description").getAsString().contains("strict standard Base64"));
        assertTrue(base64.get("description").getAsString().contains("UTF-8"));
        assertTrue(base64.get("description").getAsString().contains("malformed Base64"));
        assertTrue(base64.get("description").getAsString().contains("65536"));

        JsonObject url = rawById.get("string_url_decode");
        assertPins(url, List.of("encoded"), List.of("string"), List.of("decoded"), List.of("string"));
        assertEquals("url_decode", url.getAsJsonObject("handlerConfig").get("operation").getAsString());
        assertEquals(65536, pin(url, "inputs", "encoded").getAsJsonObject("constraints").get("max").getAsInt());
        assertTrue(url.get("description").getAsString().contains("URL form"));
        assertTrue(url.get("description").getAsString().contains("plus signs become spaces"));
        assertTrue(url.get("description").getAsString().contains("malformed percent escapes"));
        assertTrue(url.get("description").getAsString().contains("65536"));

        JsonObject wrap = rawById.get("string_word_wrap");
        assertPins(wrap, List.of("text", "width"), List.of("string", "number"), List.of("wrapped_lines_list"), List.of("list<string>"));
        JsonObject width = pin(wrap, "inputs", "width");
        assertEquals(80, width.get("defaultValue").getAsInt());
        assertEquals(1, width.getAsJsonObject("constraints").get("min").getAsInt());
        assertEquals(65536, width.getAsJsonObject("constraints").get("max").getAsInt());
        assertEquals(65536, pin(wrap, "inputs", "text").getAsJsonObject("constraints").get("max").getAsInt());
        assertTrue(wrap.get("description").getAsString().contains("Unicode whitespace"));
        assertTrue(wrap.get("description").getAsString().contains("overlong words remain unsplit"));
        assertTrue(wrap.get("description").getAsString().contains("finite whole number"));
        assertTrue(wrap.get("description").getAsString().contains("1 through 65536"));
        assertTrue(wrap.get("description").getAsString().contains("fail before output"));

        JsonObject toJson = rawById.get("string_to_json");
        assertPins(toJson, List.of("value"), List.of("any"), List.of("json"), List.of("string"));
        assertTrue(pin(toJson, "inputs", "value").get("optional").getAsBoolean());
        assertTrue(toJson.get("description").getAsString().contains("JSON literal null"));

        JsonObject fromJson = rawById.get("string_from_json");
        assertPins(fromJson, List.of("json", "type_name"), List.of("string", "string"), List.of("result"), List.of("any"));
        assertEquals("string", pin(fromJson, "inputs", "type_name").get("defaultValue").getAsString());
        assertTrue(fromJson.get("description").getAsString().contains("string, list, or map"));
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
                assertEquals("DATA", pin.get("pinType").getAsString());
                assertTrue(pin.has("description"));
                assertFalse(pin.get("description").getAsString().isBlank());
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
