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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapMutationCatalogTest {
    @Test
    void mapMutationReplacementCatalogPreservesOperationsAndMigrations() throws Exception {
        JsonArray nodes = JsonParser.parseString(Files.readString(Path.of("src", "main", "resources", "nodes", "map_mutation.json"))).getAsJsonArray();
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        for (var element : nodes) {
            JsonObject node = element.getAsJsonObject();
            definitions.put(node.get("id").getAsString(), node);
            assertEquals("active", node.get("lifecycle").getAsString());
            assertEquals("restudio.resync", node.get("owner").getAsString());
            assertEquals("GenericMapHandler", node.get("handler").getAsString());
            assertEquals(2, node.get("schemaVersion").getAsInt());
        }

        assertEquals(List.of("core.map.create", "core.map.set", "core.map.remove", "core.map.clear", "core.map.merge", "core.map.put_all"), List.copyOf(definitions.keySet()));
        Map<String, String> operations = Map.of(
            "core.map.create", "create",
            "core.map.set", "set",
            "core.map.remove", "remove",
            "core.map.clear", "clear",
            "core.map.merge", "merge",
            "core.map.put_all", "put_all"
        );
        for (var entry : operations.entrySet()) {
            assertEquals(entry.getValue(), definitions.get(entry.getKey()).getAsJsonObject("handlerConfig").get("operation").getAsString());
        }

        Map<String, List<String>> expectedMigrations = Map.of(
            "core.map.set", List.of("map->map", "key->key", "value->value", "map->output_map"),
            "core.map.remove", List.of("map->map", "key->key", "map->output_map"),
            "core.map.clear", List.of("map->map", "map->output_map"),
            "core.map.merge", List.of("mapA->map_a", "mapB->map_b", "map->output_map"),
            "core.map.put_all", List.of("map->map", "other->other", "map->output_map"));
        for (var entry : expectedMigrations.entrySet()) {
            JsonObject migration = definitions.get(entry.getKey()).getAsJsonObject("migrationMapping");
            assertTrue(migration.get("complete").getAsBoolean(), entry.getKey());
            assertEquals(1, migration.get("sourceSchemaVersion").getAsInt(), entry.getKey());
            assertEquals(2, migration.get("targetSchemaVersion").getAsInt(), entry.getKey());
            assertEquals(entry.getValue(), migrationPins(migration), entry.getKey());
        }

        JsonObject merge = definitions.get("core.map.merge");
        assertEquals(List.of("map_a", "map_b"), pinIds(merge.getAsJsonArray("inputs")));
        assertEquals(List.of("map<string,type:t>", "map<string,type:t>"), pinTypes(merge.getAsJsonArray("inputs")));
        JsonObject migration = merge.getAsJsonObject("migrationMapping");
        assertTrue(migration.get("complete").getAsBoolean());
        assertEquals(1, migration.get("sourceSchemaVersion").getAsInt());
        assertEquals(2, migration.get("targetSchemaVersion").getAsInt());
        assertEquals(List.of("mapA->map_a", "mapB->map_b", "map->output_map"), migrationPins(migration));
    }

    private List<String> pinIds(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("id").getAsString()).toList();
    }

    private List<String> pinTypes(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("dataType").getAsString()).toList();
    }

    private List<String> migrationPins(JsonObject migration) {
        return migration.getAsJsonArray("pins").asList().stream()
            .map(pin -> pin.getAsJsonObject().get("source").getAsString() + "->"
                + pin.getAsJsonObject().get("target").getAsString()).toList();
    }
}
