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

class StringBasicCatalogTest {
    @Test
    void stringBasicReplacementCatalogExposesStableTypedOperations() throws Exception {
        JsonArray nodes = JsonParser.parseString(Files.readString(Path.of("src", "main", "resources", "nodes", "string_basic.json"))).getAsJsonArray();
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
            assertEquals(1, node.get("schemaVersion").getAsInt());
            assertNoFlowPins(node);
            assertNoInventedDefaults(node);
        }

        List<String> ids = List.of("string_concat", "string_upper", "string_lower", "string_trim", "string_length",
            "string_is_empty", "string_is_blank", "string_is_numeric");
        assertEquals(ids, List.copyOf(definitions.keySet()));

        Map<String, String> operations = Map.of(
            "string_concat", "concat",
            "string_upper", "upper",
            "string_lower", "lower",
            "string_trim", "trim",
            "string_length", "length",
            "string_is_empty", "is_empty",
            "string_is_blank", "is_blank",
            "string_is_numeric", "is_numeric"
        );
        Map<String, String> legacyIds = Map.of(
            "string_concat", "string.concat",
            "string_upper", "string.upper",
            "string_lower", "string.lower",
            "string_trim", "string.trim",
            "string_length", "string.length",
            "string_is_empty", "string.is_empty",
            "string_is_blank", "string.is_blank",
            "string_is_numeric", "string.is_numeric"
        );
        for (String id : ids) {
            JsonObject node = definitions.get(id);
            assertEquals(operations.get(id), node.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(List.of(legacyIds.get(id)), node.get("legacyIds").getAsJsonArray().asList().stream().map(element -> element.getAsString()).toList());
        }

        assertEquals(List.of("a", "b"), pinIds(definitions.get("string_concat").getAsJsonArray("inputs")));
        assertEquals(List.of("string", "string"), pinTypes(definitions.get("string_concat").getAsJsonArray("inputs")));
        assertEquals("string", pin(definitions.get("string_concat"), "outputs", "result").get("dataType").getAsString());
        for (String id : List.of("string_upper", "string_lower", "string_trim")) {
            assertEquals(List.of("value"), pinIds(definitions.get(id).getAsJsonArray("inputs")));
            assertEquals("string", pin(definitions.get(id), "outputs", "result").get("dataType").getAsString());
        }
        assertEquals("number", pin(definitions.get("string_length"), "outputs", "result").get("dataType").getAsString());
        for (String id : List.of("string_is_empty", "string_is_blank", "string_is_numeric")) {
            assertEquals(List.of("text"), pinIds(definitions.get(id).getAsJsonArray("inputs")));
            assertEquals("boolean", pin(definitions.get(id), "outputs", id.substring("string_".length())).get("dataType").getAsString());
        }
    }

    private void assertNoFlowPins(JsonObject node) {
        for (String direction : List.of("inputs", "outputs")) {
            for (var element : node.getAsJsonArray(direction)) {
                assertFalse("FLOW".equalsIgnoreCase(element.getAsJsonObject().get("pinType").getAsString()), node.get("id").getAsString());
            }
        }
    }

    private void assertNoInventedDefaults(JsonObject node) {
        for (String direction : List.of("inputs", "outputs")) {
            for (var element : node.getAsJsonArray(direction)) {
                assertFalse(element.getAsJsonObject().has("defaultValue"), node.get("id").getAsString());
            }
        }
    }

    private List<String> pinIds(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("id").getAsString()).toList();
    }

    private List<String> pinTypes(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("dataType").getAsString()).toList();
    }

    private JsonObject pin(JsonObject node, String direction, String name) {
        for (var element : node.getAsJsonArray(direction)) {
            JsonObject pin = element.getAsJsonObject();
            if (name.equals(pin.get("id").getAsString())) {
                return pin;
            }
        }
        throw new AssertionError("Missing pin " + name + " in " + node.get("id").getAsString());
    }
}
