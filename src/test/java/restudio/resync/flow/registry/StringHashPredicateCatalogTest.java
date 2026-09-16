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

class StringHashPredicateCatalogTest {
    @Test
    void stringHashAndPredicateCatalogExposesStableTypedOperations() throws Exception {
        JsonArray nodes = JsonParser.parseString(Files.readString(Path.of("src", "main", "resources", "nodes", "string_hash_predicate.json"))).getAsJsonArray();
        Map<String, Integer> schemaVersions = Map.of(
            "string_md5", 1,
            "string_sha256", 1,
            "string_sha512", 1,
            "string_is_alpha", 1,
            "string_is_alphanumeric", 1,
            "string_is_email", 1,
            "string_contains", 1,
            "string_matches", 2,
            "string_equals_ignore_case", 2);
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
            assertNoFlowPins(node);
            assertNoInventedDefaults(node);
        }

        List<String> ids = List.of("string_md5", "string_sha256", "string_sha512", "string_is_alpha",
            "string_is_alphanumeric", "string_is_email", "string_contains", "string_matches", "string_equals_ignore_case");
        assertEquals(ids, List.copyOf(definitions.keySet()));

        Map<String, String> operations = Map.of(
            "string_md5", "md5",
            "string_sha256", "sha256",
            "string_sha512", "sha512",
            "string_is_alpha", "is_alpha",
            "string_is_alphanumeric", "is_alphanumeric",
            "string_is_email", "is_email",
            "string_contains", "contains",
            "string_matches", "matches",
            "string_equals_ignore_case", "equals_ignore_case"
        );
        Map<String, String> legacyIds = Map.of(
            "string_md5", "string.md5",
            "string_sha256", "string.sha256",
            "string_sha512", "string.sha512",
            "string_is_alpha", "string.is_alpha",
            "string_is_alphanumeric", "string.is_alphanumeric",
            "string_is_email", "string.is_email",
            "string_contains", "string.contains",
            "string_matches", "string.matches",
            "string_equals_ignore_case", "string.equals_ignore_case"
        );
        for (String id : ids) {
            JsonObject node = definitions.get(id);
            assertEquals(operations.get(id), node.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(List.of(legacyIds.get(id)), node.get("legacyIds").getAsJsonArray().asList().stream().map(element -> element.getAsString()).toList());
        }

        for (String id : List.of("string_md5", "string_sha256", "string_sha512", "string_is_alpha", "string_is_alphanumeric", "string_is_email")) {
            assertEquals(List.of("text"), pinIds(definitions.get(id).getAsJsonArray("inputs")));
            assertEquals("string", pin(definitions.get(id), "inputs", "text").get("dataType").getAsString());
        }
        assertEquals(List.of("hash"), pinIds(definitions.get("string_md5").getAsJsonArray("outputs")));
        assertEquals(List.of("hash"), pinIds(definitions.get("string_sha256").getAsJsonArray("outputs")));
        assertEquals(List.of("hash"), pinIds(definitions.get("string_sha512").getAsJsonArray("outputs")));
        for (String id : List.of("string_md5", "string_sha256", "string_sha512")) {
            assertEquals("string", pin(definitions.get(id), "outputs", "hash").get("dataType").getAsString());
            assertTrue(definitions.get(id).get("description").getAsString().contains("UTF-8"));
        }
        for (String id : List.of("string_is_alpha", "string_is_alphanumeric", "string_is_email")) {
            String output = id.substring("string_".length());
            assertEquals(List.of(output), pinIds(definitions.get(id).getAsJsonArray("outputs")));
            assertEquals("boolean", pin(definitions.get(id), "outputs", output).get("dataType").getAsString());
            assertTrue(definitions.get(id).get("description").getAsString().contains("empty text"));
        }
        assertEquals(List.of("value", "substring"), pinIds(definitions.get("string_contains").getAsJsonArray("inputs")));
        assertEquals(List.of("result"), pinIds(definitions.get("string_contains").getAsJsonArray("outputs")));
        assertEquals("boolean", pin(definitions.get("string_contains"), "outputs", "result").get("dataType").getAsString());
        assertEquals(List.of("value", "pattern"), pinIds(definitions.get("string_matches").getAsJsonArray("inputs")));
        assertEquals(List.of("result"), pinIds(definitions.get("string_matches").getAsJsonArray("outputs")));
        assertEquals("boolean", pin(definitions.get("string_matches"), "outputs", "result").get("dataType").getAsString());
        assertEquals(List.of("value", "other"), pinIds(definitions.get("string_equals_ignore_case").getAsJsonArray("inputs")));
        assertEquals(List.of("result"), pinIds(definitions.get("string_equals_ignore_case").getAsJsonArray("outputs")));
        assertEquals("boolean", pin(definitions.get("string_equals_ignore_case"), "outputs", "result").get("dataType").getAsString());
        assertTrue(definitions.get("string_matches").get("description").getAsString().contains("regular expression"));
        assertTrue(definitions.get("string_equals_ignore_case").get("description").getAsString().contains("letter case"));
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
