package restudio.resync.flow.validation;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapQueryNodeCatalogTest {
    private static final List<NodeContract> CONTRACTS = List.of(
        new NodeContract("map.get", "core.map.get", "Map Get", "get",
            List.of(new Pin("map", "Map", "map<string,type:t>"), new Pin("key", "Key", "string")),
            List.of(new Pin("value", "Value", "type:t"))),
        new NodeContract("map.contains_key", "core.map.contains_key", "Contains Key", "contains_key",
            List.of(new Pin("map", "Map", "map<string,type:t>"), new Pin("key", "Key", "string")),
            List.of(new Pin("contains", "Contains", "boolean"))),
        new NodeContract("map.contains_value", "core.map.contains_value", "Contains Value", "contains_value",
            List.of(new Pin("map", "Map", "map<string,type:t>"), new Pin("value", "Value", "type:t")),
            List.of(new Pin("contains", "Contains", "boolean"))),
        new NodeContract("map.keys", "core.map.keys", "Keys", "keys",
            List.of(new Pin("map", "Map", "map<string,type:t>")),
            List.of(new Pin("keys", "Keys", "list<string>"))),
        new NodeContract("map.values", "core.map.values", "Values", "values",
            List.of(new Pin("map", "Map", "map<string,type:t>")),
            List.of(new Pin("values", "Values", "list<type:t>"))),
        new NodeContract("map.size", "core.map.size", "Map Size", "size",
            List.of(new Pin("map", "Map", "map<any,any>")),
            List.of(new Pin("size", "Size", "number"))),
        new NodeContract("map.is_empty", "core.map.is_empty", "Map Is Empty", "is_empty",
            List.of(new Pin("map", "Map", "map<any,any>")),
            List.of(new Pin("is_empty", "Is Empty", "boolean")))
    );

    @Test
    void mapQueryCatalogPreservesReplacementIdentityAndTypes() throws Exception {
        Path activePath = Path.of("src", "main", "resources", "nodes", "map_query.json");
        Path sourcePath = Path.of("ReSyncUpgrade", "src", "main", "resources", "nodes", "migrated", "map.json");
        JsonArray active = JsonParser.parseString(Files.readString(activePath)).getAsJsonArray();
        JsonArray source = JsonParser.parseString(Files.readString(sourcePath)).getAsJsonArray();
        Map<String, JsonObject> activeById = byId(active);
        Map<String, JsonObject> sourceById = byId(source);

        assertEquals(CONTRACTS.size(), active.size());
        assertEquals(CONTRACTS.size(), activeById.size());
        for (NodeContract contract : CONTRACTS) {
            JsonObject node = activeById.get(contract.targetId());
            assertNotNull(node, contract.targetId());
            assertEquals(contract.displayName(), node.get("displayName").getAsString());
            assertEquals("active", node.get("lifecycle").getAsString());
            assertEquals("restudio.resync", node.get("owner").getAsString());
            assertEquals("GenericMapHandler", node.get("handler").getAsString());
            assertEquals(contract.operation(), node.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertPins(node.getAsJsonArray("inputs"), contract.inputs());
            assertPins(node.getAsJsonArray("outputs"), contract.outputs());

            JsonObject legacy = sourceById.get(contract.sourceId());
            assertNotNull(legacy, contract.sourceId());
            assertEquals(contract.operation(), legacy.getAsJsonObject("handlerConfig").get("operation").getAsString());
        }
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> {
            JsonObject definition = element.getAsJsonObject();
            result.put(definition.get("id").getAsString(), definition);
        });
        return result;
    }

    private static void assertPins(JsonArray actual, List<Pin> expected) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            Pin pin = expected.get(index);
            JsonObject actualPin = actual.get(index).getAsJsonObject();
            assertEquals(pin.id(), actualPin.get("id").getAsString());
            assertEquals(pin.displayName(), actualPin.get("displayName").getAsString());
            assertEquals(pin.id(), actualPin.get("name").getAsString());
            assertEquals(pin.dataType(), actualPin.get("dataType").getAsString());
            assertTrue(actualPin.has("description"));
            assertFalse(actualPin.get("description").getAsString().isBlank());
        }
    }

    private record NodeContract(String sourceId, String targetId, String displayName, String operation, List<Pin> inputs, List<Pin> outputs) {
    }

    private record Pin(String id, String displayName, String dataType) {
    }
}
