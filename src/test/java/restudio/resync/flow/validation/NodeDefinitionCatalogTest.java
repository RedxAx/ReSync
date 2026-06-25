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

        assertEquals(1242, count);
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

    @Test
    void visibleSideEffectNodesExposeFlowPins() throws Exception {
        Map<String, List<String>> nodes = Map.of(
            "server.json", List.of("server.system_broadcast", "server.execute.command", "server.system_restart", "server.system_shutdown", "server.system_set_motd"),
            "schedule.json", List.of("schedule.schedule", "schedule.schedule_repeating", "schedule.cancel_task", "schedule.wait_ticks"),
            "placeholder.json", List.of("placeholder.placeholder_set", "placeholder.placeholder_remove"),
            "sound.json", List.of("sound.sound_play_ambient", "sound.play.for.player", "sound.play.for.all", "sound.stop.for.player", "sound.stop.all", "sound.play.category", "sound.stop.category", "sound.loop.for.player", "sound.play.sequence", "sound.play.with.distance"),
            "team.json", List.of("team.remove", "team.set.allow.friendly.fire", "team.see.friendly.invisibles", "team.set.display.name"),
            "misc.json", List.of("misc.delay"),
            "function.json", List.of("function.function_output")
        );

        for (Map.Entry<String, List<String>> entry : nodes.entrySet()) {
            for (String nodeId : entry.getValue()) {
                JsonObject node = findNode(entry.getKey(), nodeId);
                assertTrue(hasFlowInput(node), nodeId + " should expose flow input");
                if (!"function.function_output".equals(nodeId)) {
                    assertTrue(hasFlowOutput(node), nodeId + " should expose flow output");
                }
            }
        }

        for (String nodeId : List.of("region.region_create", "region.region_delete", "region.region_clone", "region.region_save", "region.region_load", "region.region_set_blocks", "region.region_mirror_x", "region.region_mirror_y", "region.region_mirror_z", "region.region_rotate_90", "region.region_rotate_180", "region.region_move", "region.region_stack")) {
            JsonObject node = findNode("region.json", nodeId);
            assertTrue(hasFlowInput(node), nodeId + " should expose flow input");
            assertTrue(hasFlowOutput(node), nodeId + " should expose flow output");
        }
    }

    @Test
    void chatPlanNodesAreVisibleAndActionable() throws Exception {
        for (String nodeId : List.of("chat.cancel", "chat.set.message", "chat.add.viewer", "chat.remove.viewer", "chat.send.channel")) {
            JsonObject node = findNode("chat.json", nodeId);
            assertEquals("CHAT", node.get("category").getAsString());
            assertFalse(node.has("hidden") && node.get("hidden").getAsBoolean());
            assertTrue(hasFlowInput(node), nodeId + " should expose flow input");
            assertTrue(hasFlowOutput(node), nodeId + " should expose flow output");
        }

        for (String nodeId : List.of("event.chat.received", "event.chat.routed", "event.chat.private_message", "event.chat.mention", "event.chat.channel_join", "event.chat.channel_leave", "event.chat.channel_send")) {
            JsonObject node = findNode("chat.json", nodeId);
            assertTrue(node.get("trigger").getAsBoolean());
            assertTrue(hasFlowOutput(node), nodeId + " should expose flow output");
            assertTrue(node.has("eventType") && !node.get("eventType").getAsString().isBlank(), nodeId + " should register a real event type");
        }
    }

    @Test
    void permissionNodesUsePolishedVisibleLabels() throws Exception {
        JsonObject check = findNode("permission.json", "permission.perm_has");
        JsonObject grant = findNode("permission.json", "permission.perm_add");
        JsonObject temporary = findNode("permission.json", "permission.perm_add_temp");

        assertEquals("Check Permission", check.get("displayName").getAsString());
        assertEquals("Grant Permission", grant.get("displayName").getAsString());
        assertEquals("Grant Temporary Permission", temporary.get("displayName").getAsString());
        assertFalse(check.has("hidden") && check.get("hidden").getAsBoolean());
        assertFalse(grant.has("hidden") && grant.get("hidden").getAsBoolean());
        assertFalse(temporary.has("hidden") && temporary.get("hidden").getAsBoolean());

        for (String nodeId : List.of("perm.set.group", "perm.get.groups", "perm.get.prefix", "perm.get.suffix", "perm.check", "perm.grant", "perm.revoke")) {
            JsonObject node = findNode("permission.json", nodeId);
            assertTrue(hasFlowInput(node), nodeId + " should expose flow input");
            assertTrue(hasFlowOutput(node), nodeId + " should expose flow output");
        }
    }

    @Test
    void stringIndexNodesUseStringHandlerOperations() throws Exception {
        JsonObject indexOf = findNode("string.json", "string.index_of");
        JsonObject lastIndexOf = findNode("string.json", "string.last_index_of");

        assertEquals("GenericStringHandler", indexOf.get("handler").getAsString());
        assertEquals("index_of", indexOf.getAsJsonObject("handlerConfig").get("operation").getAsString());
        assertEquals("GenericStringHandler", lastIndexOf.get("handler").getAsString());
        assertEquals("last_index_of", lastIndexOf.getAsJsonObject("handlerConfig").get("operation").getAsString());
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

    private boolean hasFlowInput(JsonObject node) {
        return hasFlowPin(node, "inputs");
    }

    private boolean hasFlowOutput(JsonObject node) {
        return hasFlowPin(node, "outputs");
    }

    private boolean hasFlowPin(JsonObject node, String pins) {
        if (!node.has(pins)) {
            return false;
        }
        for (JsonElement pinElement : node.getAsJsonArray(pins)) {
            JsonObject pin = pinElement.getAsJsonObject();
            if (pin.has("pinType") && "FLOW".equalsIgnoreCase(pin.get("pinType").getAsString())) {
                return true;
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
