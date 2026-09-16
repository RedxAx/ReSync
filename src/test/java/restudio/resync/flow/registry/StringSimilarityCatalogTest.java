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

class StringSimilarityCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "string_similarity.json");
    private static final List<String> IDS = List.of("string_reverse", "string_repeat", "string_levenshtein",
        "string_shuffle", "string_soundex", "string_metaphone");

    @Test
    void stringSimilarityCatalogPublishesExactRuntimeContracts() throws Exception {
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
            "string_reverse", 1,
            "string_repeat", 1,
            "string_levenshtein", 1,
            "string_shuffle", 2,
            "string_soundex", 2,
            "string_metaphone", 2);
        Map<String, String> operations = Map.of(
            "string_reverse", "reverse",
            "string_repeat", "repeat",
            "string_levenshtein", "levenshtein",
            "string_shuffle", "shuffle",
            "string_soundex", "soundex",
            "string_metaphone", "metaphone");
        Map<String, List<String>> legacyIds = Map.of(
            "string_reverse", List.of("string.reverse"),
            "string_repeat", List.of("string.repeat"),
            "string_levenshtein", List.of("string.levenshtein"),
            "string_shuffle", List.of("string.shuffle"),
            "string_soundex", List.of("string.soundex"),
            "string_metaphone", List.of("string.metaphone"));
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
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertFalse(raw.has("migrationMapping"));
            assertTrue(raw.get("description").getAsString().contains("Missing or null"));
            assertPinsAreExplicit(raw);
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertTrue(definition.getPinMigrationMappings().isEmpty());
        }

        assertPins(rawById.get("string_reverse"), List.of("text"), List.of("string"), List.of("reversed"), List.of("string"));
        assertPins(rawById.get("string_repeat"), List.of("text", "count"), List.of("string", "number"), List.of("repeated"), List.of("string"));
        assertPins(rawById.get("string_levenshtein"), List.of("text1", "text2"), List.of("string", "string"), List.of("distance"), List.of("number"));
        assertPins(rawById.get("string_shuffle"), List.of("text"), List.of("string"), List.of("shuffled"), List.of("string"));
        assertPins(rawById.get("string_soundex"), List.of("text"), List.of("string"), List.of("code"), List.of("string"));
        assertPins(rawById.get("string_metaphone"), List.of("text"), List.of("string"), List.of("code"), List.of("string"));

        JsonObject count = pin(rawById.get("string_repeat"), "inputs", "count");
        assertEquals(0, count.getAsJsonObject("constraints").get("min").getAsInt());
        assertEquals(65536, count.getAsJsonObject("constraints").get("max").getAsInt());
        assertTrue(rawById.get("string_repeat").get("description").getAsString().contains("65536"));
        assertTrue(pin(rawById.get("string_repeat"), "outputs", "repeated").get("description").getAsString().contains("65536"));
        for (String id : List.of("text1", "text2")) {
            JsonObject input = pin(rawById.get("string_levenshtein"), "inputs", id);
            assertEquals(0, input.getAsJsonObject("constraints").get("min").getAsInt());
            assertEquals(1024, input.getAsJsonObject("constraints").get("max").getAsInt());
            assertTrue(input.get("description").getAsString().contains("1024"));
            assertTrue(input.get("description").getAsString().contains("fail"));
        }
        assertTrue(rawById.get("string_shuffle").get("description").getAsString().contains("random order"));
        assertTrue(rawById.get("string_soundex").get("description").getAsString().contains("Soundex"));
        assertTrue(rawById.get("string_metaphone").get("description").getAsString().contains("Metaphone"));
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
