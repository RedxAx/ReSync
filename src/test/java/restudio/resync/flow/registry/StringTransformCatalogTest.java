package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringTransformCatalogTest {
    @Test
    void stringTransformCatalogExposesSevenStableTypedOperations() throws Exception {
        JsonArray nodes = JsonParser.parseString(Files.readString(Path.of("src", "main", "resources", "nodes", "string_transform.json"))).getAsJsonArray();
        Map<String, Integer> schemaVersions = Map.of(
            "string_starts_with", 1,
            "string_ends_with", 1,
            "string_replace", 1,
            "string_replace_regex", 2,
            "string_base64_encode", 1,
            "string_url_encode", 1,
            "string_join", 1);
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        for (var element : nodes) {
            JsonObject node = element.getAsJsonObject();
            definitions.put(node.get("id").getAsString(), node);
            assertEquals("active", node.get("lifecycle").getAsString());
            assertEquals("core", node.get("domain").getAsString());
            assertEquals("string", node.get("family").getAsString());
            assertEquals("restudio.resync", node.get("owner").getAsString());
            assertEquals("generic-string", node.get("handlerCapability").getAsString());
            assertEquals("GenericStringHandler", node.get("handler").getAsString());
            assertEquals(schemaVersions.get(node.get("id").getAsString()), node.get("schemaVersion").getAsInt());
            assertFalse(node.has("migration"));
            assertFalse(node.has("migrationMapping"));
            assertNoFlowPinsOrDefaults(node);
            for (String direction : List.of("inputs", "outputs")) {
                for (var elementPin : node.getAsJsonArray(direction)) {
                    JsonObject pin = elementPin.getAsJsonObject();
                    assertTrue(pin.has("id"));
                    assertTrue(pin.has("displayName"));
                    assertEquals(pin.get("id").getAsString(), pin.get("name").getAsString());
                    assertTrue(pin.has("dataType"));
                    assertTrue(pin.has("description"));
                    assertFalse(pin.get("description").getAsString().isBlank());
                }
            }
        }

        List<String> ids = List.of("string_starts_with", "string_ends_with", "string_replace", "string_replace_regex",
            "string_base64_encode", "string_url_encode", "string_join");
        assertEquals(ids, List.copyOf(definitions.keySet()));
        Map<String, String> operations = Map.of(
            "string_starts_with", "starts_with",
            "string_ends_with", "ends_with",
            "string_replace", "replace",
            "string_replace_regex", "replace_regex",
            "string_base64_encode", "base64_encode",
            "string_url_encode", "url_encode",
            "string_join", "join"
        );
        Map<String, String> legacyIds = Map.of(
            "string_starts_with", "string.starts_with",
            "string_ends_with", "string.ends_with",
            "string_replace", "string.replace",
            "string_replace_regex", "string.replace_regex",
            "string_base64_encode", "string.base64_encode",
            "string_url_encode", "string.url_encode",
            "string_join", "string.join"
        );
        for (String id : ids) {
            JsonObject node = definitions.get(id);
            assertEquals(operations.get(id), node.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(List.of(legacyIds.get(id)), node.getAsJsonArray("legacyIds").asList().stream().map(element -> element.getAsString()).toList());
        }

        assertPins(definitions.get("string_starts_with"), List.of("value", "prefix"), List.of("string", "string"), List.of("result"), List.of("boolean"));
        assertPins(definitions.get("string_ends_with"), List.of("value", "suffix"), List.of("string", "string"), List.of("result"), List.of("boolean"));
        assertPins(definitions.get("string_replace"), List.of("value", "target", "replacement"), List.of("string", "string", "string"), List.of("result"), List.of("string"));
        assertPins(definitions.get("string_replace_regex"), List.of("value", "pattern", "replacement"), List.of("string", "string", "string"), List.of("result"), List.of("string"));
        assertPins(definitions.get("string_base64_encode"), List.of("text"), List.of("string"), List.of("encoded"), List.of("string"));
        assertPins(definitions.get("string_url_encode"), List.of("text"), List.of("string"), List.of("encoded"), List.of("string"));
        assertPins(definitions.get("string_join"), List.of("list", "separator"), List.of("list<string>", "string"), List.of("result"), List.of("string"));
        assertTrue(definitions.get("string_replace").get("description").getAsString().contains("literal"));
        assertTrue(definitions.get("string_replace_regex").get("description").getAsString().contains("regular-expression"));
        assertTrue(definitions.get("string_base64_encode").get("description").getAsString().contains("UTF-8"));
        assertTrue(definitions.get("string_url_encode").get("description").getAsString().contains("UTF-8"));
        assertTrue(definitions.get("string_join").get("description").getAsString().contains("comma"));
    }

    private void assertPins(JsonObject node, List<String> inputIds, List<String> inputTypes, List<String> outputIds, List<String> outputTypes) {
        assertEquals(inputIds, pinIds(node.getAsJsonArray("inputs")));
        assertEquals(inputTypes, pinTypes(node.getAsJsonArray("inputs")));
        assertEquals(outputIds, pinIds(node.getAsJsonArray("outputs")));
        assertEquals(outputTypes, pinTypes(node.getAsJsonArray("outputs")));
    }

    private void assertNoFlowPinsOrDefaults(JsonObject node) {
        for (String direction : List.of("inputs", "outputs")) {
            for (var element : node.getAsJsonArray(direction)) {
                JsonObject pin = element.getAsJsonObject();
                assertFalse("FLOW".equalsIgnoreCase(pin.get("pinType").getAsString()), node.get("id").getAsString());
                assertFalse(pin.has("defaultValue"), node.get("id").getAsString());
            }
        }
    }

    private List<String> pinIds(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("id").getAsString()).toList();
    }

    private List<String> pinTypes(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("dataType").getAsString()).toList();
    }
}
