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

        assertEquals(1420, count);
    }

    @Test
    void functionCatalogSupportsTypedCallsAndProgrammaticLookup() throws Exception {
        JsonObject call = findNode("function.json", "call.function");
        assertEquals("function", pinType(call, "inputs", "function"));
        assertEquals("any", pinType(call, "inputs", "arguments"));
        assertEquals("boolean", pinType(call, "inputs", "continue_on_failure"));
        assertEquals("result<map<string,any>>", pinType(call, "outputs", "result"));
        assertEquals("map<string,any>", pinType(findNode("function.json", "function.argument"), "outputs", "arguments"));
        assertEquals("any", pinType(findNode("function.json", "function.result_value"), "outputs", "value"));
        assertEquals("list<function>", pinType(findNode("function.json", "function.list"), "outputs", "functions"));
        assertEquals("result<function>", pinType(findNode("function.json", "function.find"), "outputs", "result"));
        assertEquals("number", pinType(findNode("function.json", "function.index"), "outputs", "index"));
        assertEquals("function", pinType(findNode("function.json", "function.at_index"), "outputs", "function"));
        assertEquals("map<string,string>", pinType(findNode("function.json", "function.describe"), "outputs", "inputs"));
    }

    @Test
    void graphResourcesExposeTypedGetterNodes() throws Exception {
        assertEquals("flow_definition", pinType(findNode("domain_resources.json", "flow.get"), "outputs", "value"));
        assertEquals("function_definition", pinType(findNode("domain_resources.json", "function.get"), "outputs", "value"));
        assertEquals("command_definition", pinType(findNode("domain_resources.json", "command.get"), "outputs", "value"));
        assertEquals("worldgen_project", pinType(findNode("domain_resources.json", "worldgen.get"), "outputs", "value"));
        assertEquals("flow_id", pinType(findNode("domain_resources.json", "flow.run"), "inputs", "selected_flow"));
        assertEquals("flow_definition", pinType(findNode("domain_resources.json", "flow.run.value"), "inputs", "value"));
        assertEquals("function_definition", pinType(findNode("domain_resources.json", "function.call.value"), "inputs", "value"));
        assertEquals("motd_profile", pinType(findNode("domain_resources.json", "motd.profile.details"), "inputs", "profile"));
        assertEquals("map<string,any>", pinType(findNode("domain_resources.json", "recipe.details"), "outputs", "details"));
    }

    @Test
    void genericItemAndEntityDataNodesRemainCatalogued() throws Exception {
        for (String id : List.of("entity.entity_data", "entity.entity_apply_data")) {
            assertEquals(id, findNode("entity.json", id).get("id").getAsString());
        }
        for (String id : List.of("itemstack.item_components", "itemstack.item_component", "itemstack.item_set_component", "itemstack.item_remove_component", "itemstack.item_apply_components")) {
            assertEquals(id, findNode("itemstack.json", id).get("id").getAsString());
        }
    }

    @Test
    void runtimeDataNodesExposeTypedQueriesAndItemResolution() throws Exception {
        assertEquals("Find Data", findNode("runtime_data.json", "data.runtime_query").get("displayName").getAsString());
        assertEquals("list<runtime_data_entry>", pinType(findNode("runtime_data.json", "data.runtime_query"), "outputs", "results"));
        assertEquals("list<runtime_data_category>", pinType(findNode("runtime_data.json", "data.runtime_categories"), "outputs", "categories"));
        assertEquals("Find Items", findNode("runtime_data.json", "data.query_items").get("displayName").getAsString());
        assertEquals("list<itemstack>", pinType(findNode("runtime_data.json", "data.query_items"), "outputs", "items"));
        assertEquals("Pick Random Item", findNode("runtime_data.json", "data.random_item").get("displayName").getAsString());
        assertEquals("itemstack", pinType(findNode("runtime_data.json", "data.random_item"), "outputs", "item"));
        for (String id : List.of("data.runtime_query", "data.runtime_categories", "data.filter_runtime_entries", "data.random_runtime_entry",
            "data.runtime_entry_fields", "data.query_items", "data.random_item", "data.item_from_runtime_entry", "data.item_categories", "data.describe_item")) {
            JsonObject node = findNode("runtime_data.json", id);
            assertFalse(node.get("displayName").getAsString().contains("Runtime"));
            assertFalse(hasInput(node, "adapter"));
            assertFalse(hasInput(node, "adapters"));
            assertFalse(hasInput(node, "domain"));
            assertFalse(hasInput(node, "attributes"));
        }
        JsonObject legacyRandomItem = findNode("random.json", "random.item");
        assertTrue(legacyRandomItem.get("hidden").getAsBoolean());
        assertTrue(legacyRandomItem.get("deprecated").getAsBoolean());
        assertEquals("data.random_item", legacyRandomItem.get("replacementFor").getAsString());
    }

    @Test
    void entityAndItemDataNodesExposeClassifiedTypes() throws Exception {
        assertTrue(findNode("entity.json", "entity.entity_data").get("hidden").getAsBoolean());
        assertEquals("string", pinType(findNode("entity.json", "entity.entity_text_data"), "outputs", "text"));
        assertEquals("boolean", pinType(findNode("entity.json", "entity.entity_boolean_data"), "outputs", "boolean"));
        assertEquals("number", pinType(findNode("entity.json", "entity.entity_number_data"), "outputs", "number"));
        assertEquals("vector", pinType(findNode("entity.json", "entity.entity_vector_data"), "outputs", "vector"));
        assertEquals("location", pinType(findNode("entity.json", "entity.entity_location_data"), "outputs", "location"));
        assertEquals("living_entity", pinType(findNode("entity.json", "entity.entity_reference_data"), "outputs", "reference"));
        JsonObject dataEntry = findNode("entity.json", "entity.entity_data_entry");
        assertEquals("entity_data", pinType(dataEntry, "outputs", "data"));
        assertEquals("server:minecraft:entity_writable_data_property", pin(dataEntry, "inputs", "property").get("optionsSource").getAsString());
        assertFalse(hasInput(dataEntry, "data_type"));
        assertEquals("vector", pinType(dataEntry, "inputs", "vector"));
        assertEquals("boolean", pinType(dataEntry, "inputs", "boolean"));
        assertEquals("number", pinType(dataEntry, "inputs", "number"));
        for (String id : List.of("entity.entity_text_data_entry", "entity.entity_boolean_data_entry", "entity.entity_number_data_entry",
            "entity.entity_vector_data_entry", "entity.entity_location_data_entry", "entity.entity_reference_data_entry", "entity.entity_attribute_data_entry")) {
            assertTrue(findNode("entity.json", id).get("hidden").getAsBoolean());
        }
        assertEquals("entity_data", pinType(findNode("entity.json", "entity.entity_spawn"), "inputs", "data"));

        assertTrue(findNode("itemstack.json", "itemstack.item_component").get("hidden").getAsBoolean());
        assertTrue(findNode("itemstack.json", "itemstack.item_set_component").get("hidden").getAsBoolean());
        assertEquals("number", pinType(findNode("itemstack.json", "itemstack.item_number_component"), "outputs", "number"));
        assertEquals("boolean", pinType(findNode("itemstack.json", "itemstack.item_boolean_component"), "outputs", "boolean"));
        assertEquals("string", pinType(findNode("itemstack.json", "itemstack.item_text_component"), "outputs", "text"));
        assertEquals("item_component", pinType(findNode("itemstack.json", "itemstack.item_object_component"), "outputs", "component_value"));
        assertEquals("item_component_list", pinType(findNode("itemstack.json", "itemstack.item_list_component"), "outputs", "items"));
        assertEquals("item_components", pinType(findNode("itemstack.json", "itemstack.item_components"), "outputs", "components"));
        assertEquals("item_attribute", pinType(findNode("itemstack.json", "itemstack.item_attribute_modifier"), "outputs", "modifier"));
        assertEquals("item_component", pinType(findNode("itemstack.json", "itemstack.item_component_number_field"), "outputs", "component_value"));
        assertEquals("item_component_list", pinType(findNode("itemstack.json", "itemstack.item_component_text_list_entry"), "outputs", "items"));
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
    void propertySelectorsAreSearchableAcrossEveryPropertyFamily() throws Exception {
        Path root = Path.of("src", "main", "resources", "nodes", "migrated");
        try (var files = Files.list(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json") && !path.getFileName().toString().startsWith("_")).toList()) {
                JsonElement element = JsonParser.parseString(Files.readString(file));
                Iterable<JsonElement> nodes = element.isJsonArray() ? element.getAsJsonArray() : List.of(element);
                for (JsonElement nodeElement : nodes) {
                    JsonObject node = nodeElement.getAsJsonObject();
                    if (!node.has("inputs")) {
                        continue;
                    }
                    for (JsonElement inputElement : node.getAsJsonArray("inputs")) {
                        JsonObject input = inputElement.getAsJsonObject();
                        if (input.has("name") && "property".equals(input.get("name").getAsString()) && input.has("options")) {
                            assertEquals("SEARCHABLE_LIST", input.get("widget").getAsString(), node.get("id").getAsString());
                        }
                    }
                }
            }
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
        assertTrue(hasString(pin(check, "inputs", "mode"), "options", "Find None"));
        assertTrue(hasString(pin(check, "inputs", "mode"), "options", "Count"));
        assertEquals("number", pinType(check, "outputs", "count"));
        assertEquals("permission_context", pinType(check, "inputs", "context"));
        assertEquals("permission_context", pinType(check, "outputs", "resolved_context"));
        assertEquals("list<permission>", pinType(findNode("permission.json", "perm.get.permissions"), "outputs", "permissions"));
        assertEquals("list<permission_group>", pinType(findNode("permission.json", "perm.get.all.groups"), "outputs", "groups"));
        assertEquals("list<permission_track>", pinType(findNode("permission.json", "perm.list.tracks"), "outputs", "tracks"));
        JsonObject group = pin(findNode("permission.json", "perm.add.group"), "inputs", "group");
        JsonObject track = pin(findNode("permission.json", "perm.promote"), "inputs", "track");
        assertEquals("permission_group", group.get("dataType").getAsString());
        assertEquals("SEARCHABLE_LIST", group.get("widget").getAsString());
        assertEquals("server:luckperms:group", group.get("optionsSource").getAsString());
        assertEquals("permission_track", track.get("dataType").getAsString());
        assertEquals("SEARCHABLE_LIST", track.get("widget").getAsString());
        assertEquals("server:luckperms:track", track.get("optionsSource").getAsString());
        assertEquals("permission_group", pinType(findNode("permission.json", "perm.get.primary.group"), "outputs", "group"));

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

    @Test
    void additionNodeIsDiscoverableByCommonNames() throws Exception {
        JsonObject add = findNode("math.json", "math.add");

        assertEquals("Add", add.get("displayName").getAsString());
        assertEquals("Adds Two Numbers", add.get("description").getAsString());
        assertTrue(hasString(add, "aliases", "Sum"));
        assertTrue(hasString(add, "aliases", "Plus"));
        assertEquals("add", add.getAsJsonObject("handlerConfig").get("operation").getAsString());
    }

    @Test
    void networkNodesUseSemanticTypesAndAuthoritativeCatalogs() throws Exception {
        JsonObject status = findNode("network.json", "network.status");
        JsonObject servers = findNode("network.json", "network.get.servers");
        JsonObject health = findNode("network.json", "network.get.server.health");
        JsonObject variable = findNode("network.json", "network.variable.get");
        JsonObject handoff = findNode("network.json", "network.player.handoff");

        assertEquals("network_node", pinType(status, "outputs", "node_id"));
        assertEquals("list<network_node>", pinType(servers, "outputs", "servers"));
        assertEquals("network_node", pinType(health, "inputs", "node_id"));
        assertEquals("server:resync:network_node", pin(health, "inputs", "node_id").get("optionsSource").getAsString());
        assertEquals("network_scope", pinType(variable, "inputs", "scope"));
        assertEquals("server:resync:network_scope", pin(variable, "inputs", "scope").get("optionsSource").getAsString());
        assertEquals("network_variable", pinType(variable, "outputs", "variable"));
        assertEquals("network_node", pinType(handoff, "inputs", "target_node"));
        assertEquals("network_route", pinType(handoff, "inputs", "server"));
        assertEquals("network_transfer_result", pinType(handoff, "outputs", "transfer_result"));
        assertEquals(2, handoff.get("schemaVersion").getAsInt());
    }

    @Test
    void standardCollectionNodesExposeExecutableTypedContracts() throws Exception {
        Map<String, String> outputs = Map.ofEntries(
            Map.entry("list.sort", "sorted_list"),
            Map.entry("list.filter", "filtered_list"),
            Map.entry("list.map", "transformed_list"),
            Map.entry("list.reduce", "result"),
            Map.entry("list.shuffle", "shuffled_list"),
            Map.entry("list.unique", "unique_list"),
            Map.entry("list.slice", "slice_list"),
            Map.entry("list.reverse", "reversed_list"),
            Map.entry("list.find_first", "found_element"),
            Map.entry("list.flatten", "flattened_list"),
            Map.entry("list.intersect", "intersection_list"),
            Map.entry("list.difference", "difference_list"),
            Map.entry("list.zip", "pairs_list"),
            Map.entry("list.group_by", "groups"),
            Map.entry("list.any", "matches"),
            Map.entry("list.all", "matches"),
            Map.entry("list.none", "matches")
        );

        for (Map.Entry<String, String> entry : outputs.entrySet()) {
            JsonObject node = findNode("list.json", entry.getKey());
            assertEquals("GenericListHandler", node.get("handler").getAsString());
            assertTrue(hasOutput(node, entry.getValue()), entry.getKey() + " should publish " + entry.getValue());
        }

        assertEquals("map<string,list<type:t>>", pinType(findNode("list.json", "list.group_by"), "outputs", "groups"));
        assertEquals("boolean", pinType(findNode("list.json", "list.any"), "outputs", "matches"));
        assertEquals("type:t", pinType(findNode("list.json", "list.find_first"), "outputs", "found_element"));
        assertTrue(hasInput(findNode("list.json", "list.add_at"), "value"));
    }

    @Test
    void standardMapMutationAndContainmentExposeRequiredValues() throws Exception {
        assertTrue(hasInput(findNode("map.json", "map.set"), "value"));
        assertTrue(hasInput(findNode("map.json", "map.contains_value"), "value"));
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

    private boolean hasString(JsonObject node, String arrayName, String expected) {
        if (!node.has(arrayName) || !node.get(arrayName).isJsonArray()) {
            return false;
        }
        for (JsonElement value : node.getAsJsonArray(arrayName)) {
            if (expected.equals(value.getAsString())) {
                return true;
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

    private String pinType(JsonObject node, String direction, String name) {
        return pin(node, direction, name).get("dataType").getAsString();
    }

    private JsonObject pin(JsonObject node, String direction, String name) {
        for (JsonElement pinElement : node.getAsJsonArray(direction)) {
            JsonObject pin = pinElement.getAsJsonObject();
            if (name.equals(pin.get("name").getAsString())) {
                return pin;
            }
        }
        throw new AssertionError("Missing pin " + name + " in " + node.get("id").getAsString());
    }
}
