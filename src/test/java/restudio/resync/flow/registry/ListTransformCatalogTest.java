package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.GenericListHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListTransformCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "list_transform.json");
    private static final List<String> IDS = List.of(
        "list_slice", "list_reverse", "list_unique", "list_flatten", "list_intersect", "list_difference", "list_zip", "list_concat",
        "list_filter", "list_map", "list_reduce", "list_shuffle", "list_sublist", "list_join", "list_map_string", "list_filter_type",
        "list_group_by", "list_chunk", "list_partition", "list_range", "list_union", "list_take_first", "list_take_last",
        "list_drop_first", "list_drop_last", "list_random");

    @Test
    void listTransformCatalogPublishesExactRuntimeContracts() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new GenericListHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        Map<String, String> operations = Map.ofEntries(
            Map.entry("list_slice", "slice"),
            Map.entry("list_reverse", "reverse"),
            Map.entry("list_unique", "unique"),
            Map.entry("list_flatten", "flatten"),
            Map.entry("list_intersect", "intersect"),
            Map.entry("list_difference", "difference"),
            Map.entry("list_zip", "zip"),
            Map.entry("list_concat", "concat"),
            Map.entry("list_filter", "filter"),
            Map.entry("list_map", "map"),
            Map.entry("list_reduce", "reduce"),
            Map.entry("list_shuffle", "shuffle"),
            Map.entry("list_sublist", "sublist"),
            Map.entry("list_join", "join"),
            Map.entry("list_map_string", "map_string"),
            Map.entry("list_filter_type", "filter_type"),
            Map.entry("list_group_by", "group_by"),
            Map.entry("list_chunk", "chunk"),
            Map.entry("list_partition", "partition"),
            Map.entry("list_range", "range"),
            Map.entry("list_union", "union"),
            Map.entry("list_take_first", "take_first"),
            Map.entry("list_take_last", "take_last"),
            Map.entry("list_drop_first", "drop_first"),
            Map.entry("list_drop_last", "drop_last"),
            Map.entry("list_random", "random"));
        Map<String, List<String>> legacyIds = Map.ofEntries(
            Map.entry("list_slice", List.of("list.slice")),
            Map.entry("list_reverse", List.of("list.reverse")),
            Map.entry("list_unique", List.of("list.unique")),
            Map.entry("list_flatten", List.of("list.flatten")),
            Map.entry("list_intersect", List.of("list.intersect")),
            Map.entry("list_difference", List.of("list.difference")),
            Map.entry("list_zip", List.of("list.zip")),
            Map.entry("list_concat", List.of("list.concat")),
            Map.entry("list_filter", List.of("list.filter")),
            Map.entry("list_map", List.of("list.map")),
            Map.entry("list_reduce", List.of("list.reduce")),
            Map.entry("list_shuffle", List.of("list.shuffle")),
            Map.entry("list_sublist", List.of("list.sublist")),
            Map.entry("list_join", List.of("list.join")),
            Map.entry("list_map_string", List.of("list.map_string")),
            Map.entry("list_filter_type", List.of("list.filter_type")),
            Map.entry("list_group_by", List.of("list.group_by")),
            Map.entry("list_chunk", List.of("list.chunk")),
            Map.entry("list_partition", List.of("list.partition")),
            Map.entry("list_range", List.of("list.range")),
            Map.entry("list_union", List.of("list.union")),
            Map.entry("list_take_first", List.of("list.take_first")),
            Map.entry("list_take_last", List.of("list.take_last")),
            Map.entry("list_drop_first", List.of("list.drop_first")),
            Map.entry("list_drop_last", List.of("list.drop_last")),
            Map.entry("list_random", List.of("list.random")));
        Map<String, List<String>> inputPins = Map.ofEntries(
            Map.entry("list_slice", List.of("list:list<type:t>", "start_index:number", "end_index:number")),
            Map.entry("list_reverse", List.of("list:list<type:t>")),
            Map.entry("list_unique", List.of("list:list<type:t>")),
            Map.entry("list_flatten", List.of("list:list<any>")),
            Map.entry("list_intersect", List.of("list1:list<type:t>", "list2:list<type:t>")),
            Map.entry("list_difference", List.of("list1:list<type:t>", "list2:list<type:t>")),
            Map.entry("list_zip", List.of("list1:list<type:t>", "list2:list<type:u>")),
            Map.entry("list_concat", List.of("lista:list<type:t>", "listb:list<type:t>")),
            Map.entry("list_filter", List.of("list:list<type:t>", "property_name:string", "operator:string", "compare_value:any")),
            Map.entry("list_map", List.of("list:list<any>", "transformation_type:string")),
            Map.entry("list_reduce", List.of("list:list<any>", "operation:string", "separator:string")),
            Map.entry("list_shuffle", List.of("list:list<type:t>")),
            Map.entry("list_sublist", List.of("list:list<type:t>", "start:number", "count:number")),
            Map.entry("list_join", List.of("list:list<any>", "separator:string")),
            Map.entry("list_map_string", List.of("list:list<any>")),
            Map.entry("list_filter_type", List.of("list:list<type:t>", "type_name:string")),
            Map.entry("list_group_by", List.of("list:list<type:t>", "property_name:string")),
            Map.entry("list_chunk", List.of("list:list<type:t>", "size:number")),
            Map.entry("list_partition", List.of("list:list<type:t>", "size:number")),
            Map.entry("list_range", List.of("list:list<number>")),
            Map.entry("list_union", List.of("lista:list<type:t>", "listb:list<type:t>")),
            Map.entry("list_take_first", List.of("list:list<type:t>", "count:number")),
            Map.entry("list_take_last", List.of("list:list<type:t>", "count:number")),
            Map.entry("list_drop_first", List.of("list:list<type:t>", "count:number")),
            Map.entry("list_drop_last", List.of("list:list<type:t>", "count:number")),
            Map.entry("list_random", List.of("list:list<type:t>")));
        Map<String, List<String>> outputPins = Map.ofEntries(
            Map.entry("list_slice", List.of("slice_list:list<type:t>")),
            Map.entry("list_reverse", List.of("reversed_list:list<type:t>")),
            Map.entry("list_unique", List.of("unique_list:list<type:t>")),
            Map.entry("list_flatten", List.of("flattened_list:list<any>")),
            Map.entry("list_intersect", List.of("intersection_list:list<type:t>")),
            Map.entry("list_difference", List.of("difference_list:list<type:t>")),
            Map.entry("list_zip", List.of("pairs_list:list<map<string,any>>")),
            Map.entry("list_concat", List.of("list:list<type:t>")),
            Map.entry("list_filter", List.of("filtered_list:list<type:t>")),
            Map.entry("list_map", List.of("transformed_list:list<any>")),
            Map.entry("list_reduce", List.of("result:any")),
            Map.entry("list_shuffle", List.of("shuffled_list:list<type:t>")),
            Map.entry("list_sublist", List.of("output_list:list<type:t>")),
            Map.entry("list_join", List.of("string:string")),
            Map.entry("list_map_string", List.of("output_list:list<string>")),
            Map.entry("list_filter_type", List.of("output_list:list<type:t>")),
            Map.entry("list_group_by", List.of("groups:map<string,list<type:t>>")),
            Map.entry("list_chunk", List.of("chunks:list<list<type:t>>")),
            Map.entry("list_partition", List.of("partitions:list<list<type:t>>")),
            Map.entry("list_range", List.of("range:number")),
            Map.entry("list_union", List.of("list:list<type:t>")),
            Map.entry("list_take_first", List.of("output_list:list<type:t>")),
            Map.entry("list_take_last", List.of("output_list:list<type:t>")),
            Map.entry("list_drop_first", List.of("output_list:list<type:t>")),
            Map.entry("list_drop_last", List.of("output_list:list<type:t>")),
            Map.entry("list_random", List.of("item:type:t")));
        Set<String> schemaOneIds = Set.of(
            "list_slice", "list_reverse", "list_unique", "list_flatten", "list_intersect", "list_difference", "list_zip");
        Map<String, List<String>> migrationPins = Map.of(
            "list_concat", List.of("input:listA->lista", "input:listB->listb", "output:list->list"),
            "list_sublist", List.of("input:list->list", "input:start->start", "input:count->count", "output:list->output_list"),
            "list_map_string", List.of("input:list->list", "output:list->output_list"),
            "list_filter_type", List.of("input:list->list", "input:type_name->type_name", "output:list->output_list"));
        Set<String> boundedDescriptionIds = Set.of(
            "list_slice", "list_reverse", "list_unique", "list_flatten", "list_intersect", "list_difference", "list_zip", "list_concat");
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("list", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("generic-list", raw.get("handlerCapability").getAsString());
            assertEquals("typed-list", raw.get("selectorIntent").getAsString());
            assertEquals("GenericListHandler", raw.get("handler").getAsString());
            assertEquals("DATA", raw.get("category").getAsString());
            assertEquals(schemaOneIds.contains(definition.getId()) ? 1 : 2, raw.get("schemaVersion").getAsInt());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertEquals(inputPins.get(definition.getId()), pinContracts(raw.getAsJsonArray("inputs")));
            assertEquals(outputPins.get(definition.getId()), pinContracts(raw.getAsJsonArray("outputs")));
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertAllPinsDeclareLimit(raw);
            assertDescriptionContains(raw, "missing", "null");
            if (boundedDescriptionIds.contains(definition.getId())) {
                assertDescriptionContains(raw, "65536", "fail");
            }
            if (migrationPins.containsKey(definition.getId())) {
                JsonObject migration = raw.getAsJsonObject("migrationMapping");
                assertEquals(1, migration.get("sourceSchemaVersion").getAsInt());
                assertEquals(2, migration.get("targetSchemaVersion").getAsInt());
                assertTrue(migration.get("complete").getAsBoolean());
                assertEquals(migrationPins.get(definition.getId()),
                    migration.getAsJsonArray("pins").asList().stream().map(pin -> {
                        JsonObject value = pin.getAsJsonObject();
                        return value.get("direction").getAsString() + ":" + value.get("source").getAsString()
                            + "->" + value.get("target").getAsString();
                    }).toList());
                assertEquals(migrationPins.get(definition.getId()),
                    definition.getPinMigrationMappings().stream().map(mapping -> mapping.direction().name().toLowerCase()
                        + ":" + mapping.sourcePinId().value() + "->" + mapping.targetPinId().value()).toList());
            } else {
                assertFalse(raw.has("migrationMapping"));
                assertTrue(definition.getPinMigrationMappings().isEmpty());
            }
        }

        assertDescriptionContains(rawById.get("list_slice"), "negative", "exclusive", "empty");
        assertDescriptionContains(rawById.get("list_unique"), "first occurrence", "order");
        assertDescriptionContains(rawById.get("list_flatten"), "one", "null", "shallow");
        assertDescriptionContains(rawById.get("list_intersect"), "first-list order", "once");
        assertDescriptionContains(rawById.get("list_difference"), "duplicate", "order");
        assertDescriptionContains(rawById.get("list_zip"), "shorter", "first", "second");
        assertDescriptionContains(rawById.get("list_concat"), "List A", "List B", "combined");
        assertDescriptionContains(rawById.get("list_filter"), "predicate", "order", "element type");
        assertDescriptionContains(rawById.get("list_map"), "transform", "property", "size");
        assertDescriptionContains(rawById.get("list_reduce"), "numeric sum", "text concatenation", "separator");
        assertDescriptionContains(rawById.get("list_shuffle"), "randomly shuffled", "source list is not changed");
        assertDescriptionContains(rawById.get("list_sublist"), "segment", "start defaults to 0", "count defaults to 1");
        assertDescriptionContains(rawById.get("list_join"), "text", "separator defaults to a comma");
        assertDescriptionContains(rawById.get("list_map_string"), "text form", "null values remain null");
        assertDescriptionContains(rawById.get("list_filter_type"), "runtime type", "empty type name");
        assertDescriptionContains(rawById.get("list_group_by"), "selected property", "list order", "blank property");
        assertDescriptionContains(rawById.get("list_chunk"), "chunks", "size defaults to 1", "non-positive");
        assertDescriptionContains(rawById.get("list_partition"), "partitions", "size defaults to 1", "non-positive");
        assertDescriptionContains(rawById.get("list_range"), "numeric range", "no numeric value");
        assertDescriptionContains(rawById.get("list_union"), "List A", "List B", "unique to List B");
        assertDescriptionContains(rawById.get("list_take_first"), "first", "count defaults to 1", "whole list");
        assertDescriptionContains(rawById.get("list_take_last"), "last", "count defaults to 1", "whole list");
        assertDescriptionContains(rawById.get("list_drop_first"), "first", "count defaults to 1", "empty list");
        assertDescriptionContains(rawById.get("list_drop_last"), "last", "count defaults to 1", "empty list");
        assertDescriptionContains(rawById.get("list_random"), "random value", "empty list returns null");
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static List<String> pinContracts(JsonArray pins) {
        return pins.asList().stream().map(element -> {
            JsonObject pin = element.getAsJsonObject();
            assertTrue(pin.has("id"));
            assertTrue(pin.has("displayName"));
            assertTrue(pin.has("description"));
            assertFalse(pin.get("description").getAsString().isBlank());
            assertEquals("DATA", pin.get("pinType").getAsString());
            return pin.get("id").getAsString() + ":" + pin.get("dataType").getAsString();
        }).toList();
    }

    private static void assertAllPinsDeclareLimit(JsonObject node) {
        for (String direction : List.of("inputs", "outputs")) {
            for (JsonElement element : node.getAsJsonArray(direction)) {
                JsonObject pin = element.getAsJsonObject();
                if (pin.get("dataType").getAsString().startsWith("list<")) {
                    assertEquals(0, pin.getAsJsonObject("constraints").get("min").getAsInt());
                    assertEquals(65536, pin.getAsJsonObject("constraints").get("max").getAsInt());
                }
            }
        }
    }

    private static void assertDescriptionContains(JsonObject node, String... fragments) {
        String description = node.get("description").getAsString().toLowerCase();
        for (String fragment : fragments) {
            assertTrue(description.contains(fragment.toLowerCase()), node.get("id").getAsString() + ": " + description);
        }
    }
}
