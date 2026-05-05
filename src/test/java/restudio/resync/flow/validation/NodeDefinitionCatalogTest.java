package restudio.resync.flow.validation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionCatalogTest {
    @Test
    void migratedNodeCatalogKeepsExpectedNodeCount() throws Exception {
        Path root = Path.of("src", "main", "resources", "nodes", "migrated");
        int count = 0;
        try (var files = Files.list(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json") && !path.getFileName().toString().startsWith("_")).toList()) {
                JsonElement element = JsonParser.parseString(Files.readString(file));
                count += element.isJsonArray() ? element.getAsJsonArray().size() : 1;
            }
        }

        assertEquals(1213, count);
    }

    @Test
    void localFamilyNodesAreSplitBetweenDataQueriesAndFlowActions() throws Exception {
        for (String family : List.of("player", "entity", "world", "block", "inventory", "itemstack")) {
            JsonObject query = findNode(family + ".json", family + ".properties");
            JsonObject action = findNode(family + ".json", family + ".actions");

            assertFalse(hasFlowPin(query), family + ".properties should not expose flow pins");
            assertFalse(hasInput(query, "value"), family + ".properties should not expose set value input");
            assertFalse(hasOutput(query, "value"), family + ".properties should expose typed property outputs instead of value:any");
            assertTrue(hasActionOption(query, "get"), family + ".properties should expose get");
            assertTrue(hasActionOption(query, "has"), family + ".properties should expose has");
            assertFalse(hasActionOption(query, "set"), family + ".properties should not expose set");

            assertTrue(hasFlowPin(action), family + ".actions should expose flow pins");
            assertTrue(hasActionOption(action, "set"), family + ".actions should expose set");
            assertTrue(hasActionOption(action, "do"), family + ".actions should expose do");
            assertTrue(hasActionOption(action, "execute"), family + ".actions should expose execute");
            assertFalse(hasActionOption(action, "get"), family + ".actions should not expose get");
        }
    }

    @Test
    void duplicateGetterNodesAreHiddenBehindCanonicalFamilyNodes() throws Exception {
        Map<String, String> duplicates = Map.of(
            "player_action.json:get.player.info", "player.properties",
            "entity.json:entity.entity_get_info", "entity.properties",
            "world_action.json:world.world_get_time", "world.properties",
            "block_action.json:block.block_get_type", "block.properties",
            "inventory.json:inventory.inventory_get_slot", "inventory.properties",
            "itemstack.json:itemstack.max_durability", "itemstack.properties"
        );

        for (Map.Entry<String, String> entry : duplicates.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            JsonObject node = findNode(parts[0], parts[1]);

            assertTrue(node.get("hidden").getAsBoolean(), parts[1] + " should be hidden");
            assertEquals(entry.getValue(), node.get("canonicalId").getAsString());
        }
    }

    private JsonObject findNode(String fileName, String id) throws Exception {
        Path file = Path.of("src", "main", "resources", "nodes", "migrated", fileName);
        JsonElement element = JsonParser.parseString(Files.readString(file));
        for (JsonElement child : element.getAsJsonArray()) {
            JsonObject object = child.getAsJsonObject();
            if (id.equals(object.get("id").getAsString())) {
                return object;
            }
        }
        throw new AssertionError("Missing node " + id + " in " + fileName);
    }

    private boolean hasFlowPin(JsonObject node) {
        for (String pins : List.of("inputs", "outputs")) {
            if (!node.has(pins)) {
                continue;
            }
            for (JsonElement pinElement : node.getAsJsonArray(pins)) {
                JsonObject pin = pinElement.getAsJsonObject();
                if (pin.has("pinType") && "FLOW".equalsIgnoreCase(pin.get("pinType").getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasActionOption(JsonObject node, String option) {
        for (JsonElement pinElement : node.getAsJsonArray("inputs")) {
            JsonObject pin = pinElement.getAsJsonObject();
            if (!"action".equals(pin.get("name").getAsString()) || !pin.has("options")) {
                continue;
            }
            for (JsonElement value : pin.getAsJsonArray("options")) {
                if (option.equals(value.getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasOutput(JsonObject node, String name) {
        if (!node.has("outputs")) {
            return false;
        }
        for (JsonElement pinElement : node.getAsJsonArray("outputs")) {
            JsonObject pin = pinElement.getAsJsonObject();
            if (name.equals(pin.get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInput(JsonObject node, String name) {
        if (!node.has("inputs")) {
            return false;
        }
        for (JsonElement pinElement : node.getAsJsonArray("inputs")) {
            JsonObject pin = pinElement.getAsJsonObject();
            if (name.equals(pin.get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }
}
