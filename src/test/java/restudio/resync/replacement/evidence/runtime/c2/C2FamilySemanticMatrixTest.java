package restudio.resync.replacement.evidence.runtime.c2;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C2FamilySemanticMatrixTest {
    private static final Path ROOT = Path.of("src", "main", "resources", "nodes", "migrated");
    private static final List<String> FILES = List.of("player.json", "player_action.json", "player_query_restored.json", "entity.json", "entity_restored.json", "world.json", "world_action.json", "block.json", "block_action.json", "inventory.json", "itemstack.json", "particle.json");
    private static final Set<String> STATUS = Set.of("proven", "intentional-change-required", "quarantine-required");

    @Test
    void matrixProvidesOneSemanticRecordForEveryCurrentDefinitionAndPhysicalPin() throws Exception {
        JsonArray matrix = JsonParser.parseString(Files.readString(Path.of("src", "test", "resources", "fixtures", "node-replacement", "runtime", "c2", "family-semantic-matrix.json"))).getAsJsonObject().getAsJsonArray("definitions");
        assertEquals(497, matrix.size());
        Map<String, JsonObject> matrixNodes = new HashMap<>();
        Set<String> matrixPins = new HashSet<>();
        for (JsonElement element : matrix) {
            JsonObject node = element.getAsJsonObject();
            String key = nodeKey(node.get("sourceFile").getAsString(), node.get("nodeId").getAsString());
            assertTrue(matrixNodes.putIfAbsent(key, node) == null, "duplicate node " + key);
            assertStatus(node);
            for (JsonElement pinElement : node.getAsJsonArray("pins")) {
                JsonObject pin = pinElement.getAsJsonObject();
                String pinKey = key + ":" + pin.get("direction").getAsString() + ":" + pin.get("name").getAsString();
                assertTrue(matrixPins.add(pinKey), "duplicate pin " + pinKey);
                assertTrue(pin.has("type") && !pin.get("type").getAsString().isBlank(), pinKey);
                assertTrue(pin.has("requiredOrOptional") && !pin.get("requiredOrOptional").getAsString().isBlank(), pinKey);
                assertTrue(pin.has("resourceSelector") && pin.get("resourceSelector").isJsonObject(), pinKey);
                assertTrue(pin.has("canonicalMapping"), pinKey);
                assertStatus(pin);
            }
        }

        int definitions = 0;
        int pins = 0;
        for (String file : FILES) {
            for (JsonElement sourceElement : JsonParser.parseString(Files.readString(ROOT.resolve(file))).getAsJsonArray()) {
                JsonObject source = sourceElement.getAsJsonObject();
                String key = nodeKey(file, source.get("id").getAsString());
                JsonObject node = matrixNodes.remove(key);
                assertTrue(node != null, "missing node " + key);
                assertEquals(source.get("handler").getAsString(), node.get("handler").getAsString(), key);
                assertNullableText(source, node, "canonicalId", key);
                assertNodeValue(node, "operation", handlerConfigValue(source, "operation"), key);
                assertNodeValue(node, "property", handlerConfigValue(source, "property"), key);
                assertNodeValue(node, "action", actionDefault(source), key);
                for (String field : List.of("classification", "effect", "thread", "branches", "failure")) {
                    assertTrue(node.has(field) && !node.get(field).isJsonNull() && !node.get(field).getAsString().isBlank(), key + ":" + field);
                }
                for (String sourceDirection : List.of("inputs", "outputs")) {
                    String direction = sourceDirection.substring(0, sourceDirection.length() - 1);
                    if (!source.has(sourceDirection)) continue;
                    for (JsonElement sourcePinElement : source.getAsJsonArray(sourceDirection)) {
                        JsonObject sourcePin = sourcePinElement.getAsJsonObject();
                        String pinKey = key + ":" + direction + ":" + sourcePin.get("name").getAsString();
                        assertTrue(matrixPins.remove(pinKey), "missing pin " + pinKey);
                        JsonObject matrixPin = pin(node, direction, sourcePin.get("name").getAsString());
                        assertEquals(sourcePin.get("dataType").getAsString(), matrixPin.get("type").getAsString(), pinKey);
                        assertPinValue(matrixPin, "default", sourcePin.has("defaultValue") ? sourcePin.get("defaultValue") : null, pinKey);
                        assertPinValue(matrixPin, "description", sourcePin.has("description") ? sourcePin.get("description") : null, pinKey);
                        assertPinValue(matrixPin, "visibility", sourcePin.has("visibleWhen") ? sourcePin.get("visibleWhen") : null, pinKey);
                        JsonObject selector = matrixPin.getAsJsonObject("resourceSelector");
                        assertPinValue(selector, "widget", sourcePin.has("widget") ? sourcePin.get("widget") : null, pinKey);
                        assertPinValue(selector, "optionsSource", sourcePin.has("optionsSource") ? sourcePin.get("optionsSource") : null, pinKey);
                        assertPinValue(selector, "options", sourcePin.has("options") ? sourcePin.get("options") : null, pinKey);
                        for (String field : List.of("direction", "name", "type", "default", "requiredOrOptional", "visibility", "resourceSelector", "description", "canonicalMapping")) {
                            assertTrue(matrixPin.has(field), pinKey + ":" + field);
                        }
                        pins++;
                    }
                }
                definitions++;
            }
        }
        assertEquals(497, definitions);
        assertEquals(2833, pins);
        assertTrue(matrixNodes.isEmpty(), "unexpected nodes " + matrixNodes.keySet());
        assertTrue(matrixPins.isEmpty(), "unexpected pins " + matrixPins);
    }

    private void assertStatus(JsonObject object) {
        assertTrue(object.has("status") && STATUS.contains(object.get("status").getAsString()));
        assertTrue(object.has("reason") && !object.get("reason").getAsString().isBlank());
    }

    private void assertNullableText(JsonObject source, JsonObject matrix, String field, String key) {
        assertTrue(matrix.has(field), key);
        if (!source.has(field) || source.get(field).isJsonNull()) {
            assertTrue(matrix.get(field).isJsonNull(), key);
            return;
        }
        assertFalse(matrix.get(field).isJsonNull(), key);
        assertEquals(source.get(field).getAsString(), matrix.get(field).getAsString(), key);
    }

    private void assertNodeValue(JsonObject node, String field, String source, String key) {
        assertTrue(node.has(field), key + ":" + field);
        if (source == null) assertTrue(node.get(field).isJsonNull(), key + ":" + field);
        else assertEquals(source, node.get(field).getAsString(), key + ":" + field);
    }

    private void assertPinValue(JsonObject matrixPin, String field, JsonElement source, String key) {
        assertTrue(matrixPin.has(field), key + ":" + field);
        if (source == null) assertTrue(matrixPin.get(field).isJsonNull(), key + ":" + field);
        else if (source.isJsonPrimitive()) assertEquals(source.getAsString(), matrixPin.get(field).getAsString(), key + ":" + field);
        else assertEquals(source, matrixPin.get(field), key + ":" + field);
    }

    private String handlerConfigValue(JsonObject source, String field) {
        if (!source.has("handlerConfig") || !source.get("handlerConfig").isJsonObject()) return null;
        JsonObject config = source.getAsJsonObject("handlerConfig");
        return config.has(field) ? config.get(field).getAsString() : null;
    }

    private String actionDefault(JsonObject source) {
        if (!source.has("inputs")) return null;
        for (JsonElement element : source.getAsJsonArray("inputs")) {
            JsonObject pin = element.getAsJsonObject();
            if ("action".equals(pin.get("name").getAsString()) && pin.has("defaultValue")) return pin.get("defaultValue").getAsString();
        }
        return null;
    }

    private JsonObject pin(JsonObject node, String direction, String name) {
        for (JsonElement element : node.getAsJsonArray("pins")) {
            JsonObject pin = element.getAsJsonObject();
            if (direction.equals(pin.get("direction").getAsString()) && name.equals(pin.get("name").getAsString())) return pin;
        }
        throw new AssertionError("Missing matrix pin " + direction + ":" + name);
    }

    private String nodeKey(String file, String id) {
        return file + ":" + id;
    }
}
