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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListQueryCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "list_query.json");
    private static final List<String> IDS = List.of(
        "list_index_of", "list_count", "list_first", "list_last", "list_find_first", "list_find_all",
        "list_find_index", "list_contains_any", "list_contains_all", "list_any", "list_all", "list_none");

    @Test
    void listQueryCatalogPublishesExactRuntimeContracts() throws Exception {
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
            Map.entry("list_index_of", "index_of"),
            Map.entry("list_count", "count"),
            Map.entry("list_first", "first"),
            Map.entry("list_last", "last"),
            Map.entry("list_find_first", "find_first"),
            Map.entry("list_find_all", "find_all"),
            Map.entry("list_find_index", "find_index"),
            Map.entry("list_contains_any", "contains_any"),
            Map.entry("list_contains_all", "contains_all"),
            Map.entry("list_any", "any"),
            Map.entry("list_all", "all"),
            Map.entry("list_none", "none"));
        Map<String, String> categories = Map.ofEntries(
            Map.entry("list_contains_any", "LOGIC"),
            Map.entry("list_contains_all", "LOGIC"),
            Map.entry("list_any", "LOGIC"),
            Map.entry("list_all", "LOGIC"),
            Map.entry("list_none", "LOGIC"));
        Map<String, List<String>> legacyIds = Map.ofEntries(
            Map.entry("list_index_of", List.of("list.index_of")),
            Map.entry("list_count", List.of("list.count")),
            Map.entry("list_first", List.of("list.first")),
            Map.entry("list_last", List.of("list.last")),
            Map.entry("list_find_first", List.of("list.find_first")),
            Map.entry("list_find_all", List.of("list.find_all")),
            Map.entry("list_find_index", List.of("list.find_index")),
            Map.entry("list_contains_any", List.of("list.contains_any")),
            Map.entry("list_contains_all", List.of("list.contains_all")),
            Map.entry("list_any", List.of("list.any")),
            Map.entry("list_all", List.of("list.all")),
            Map.entry("list_none", List.of("list.none")));
        Map<String, List<String>> inputPins = Map.ofEntries(
            Map.entry("list_index_of", List.of("list:list<type:t>", "value:type:t")),
            Map.entry("list_count", List.of("list:list<type:t>", "value:type:t")),
            Map.entry("list_first", List.of("list:list<type:t>")),
            Map.entry("list_last", List.of("list:list<type:t>")),
            Map.entry("list_find_first", List.of("list:list<type:t>", "property_name:string", "operator:string", "compare_value:any")),
            Map.entry("list_find_all", List.of("list:list<type:t>", "target:type:t")),
            Map.entry("list_find_index", List.of("list:list<type:t>", "condition:string")),
            Map.entry("list_contains_any", List.of("list:list<type:t>", "items:list<type:t>")),
            Map.entry("list_contains_all", List.of("list:list<type:t>", "items:list<type:t>")),
            Map.entry("list_any", List.of("list:list<type:t>", "property_name:string", "operator:string", "compare_value:any")),
            Map.entry("list_all", List.of("list:list<type:t>", "property_name:string", "operator:string", "compare_value:any")),
            Map.entry("list_none", List.of("list:list<type:t>", "property_name:string", "operator:string", "compare_value:any")));
        Map<String, List<String>> outputPins = Map.ofEntries(
            Map.entry("list_index_of", List.of("index:number")),
            Map.entry("list_count", List.of("count:number")),
            Map.entry("list_first", List.of("item:type:t")),
            Map.entry("list_last", List.of("item:type:t")),
            Map.entry("list_find_first", List.of("found_element:type:t")),
            Map.entry("list_find_all", List.of("indices:list<number>")),
            Map.entry("list_find_index", List.of("index:number")),
            Map.entry("list_contains_any", List.of("contains:boolean")),
            Map.entry("list_contains_all", List.of("contains:boolean")),
            Map.entry("list_any", List.of("matches:boolean")),
            Map.entry("list_all", List.of("matches:boolean")),
            Map.entry("list_none", List.of("matches:boolean")));
        Map<String, Integer> schemaVersions = Map.ofEntries(
            Map.entry("list_index_of", 1),
            Map.entry("list_count", 1),
            Map.entry("list_first", 1),
            Map.entry("list_last", 1),
            Map.entry("list_find_first", 2),
            Map.entry("list_find_all", 2),
            Map.entry("list_find_index", 2),
            Map.entry("list_contains_any", 2),
            Map.entry("list_contains_all", 2),
            Map.entry("list_any", 2),
            Map.entry("list_all", 2),
            Map.entry("list_none", 2));
        Map<String, Map<String, String>> inputDefaults = Map.ofEntries(
            Map.entry("list_find_first", Map.of("property_name", "", "operator", "equals")),
            Map.entry("list_find_index", Map.of("condition", "")),
            Map.entry("list_any", Map.of("property_name", "", "operator", "equals")),
            Map.entry("list_all", Map.of("property_name", "", "operator", "equals")),
            Map.entry("list_none", Map.of("property_name", "", "operator", "equals")));

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
            assertEquals(categories.getOrDefault(definition.getId(), "DATA"), raw.get("category").getAsString());
            assertEquals(schemaVersions.get(definition.getId()), raw.get("schemaVersion").getAsInt());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertFalse(raw.has("migrationMapping"));
            assertEquals(inputPins.get(definition.getId()), pinContracts(raw.getAsJsonArray("inputs")));
            assertEquals(outputPins.get(definition.getId()), pinContracts(raw.getAsJsonArray("outputs")));
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertTrue(definition.getPinMigrationMappings().isEmpty());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            Map<String, String> defaults = inputDefaults.getOrDefault(definition.getId(), Map.of());
            for (JsonElement pinElement : raw.getAsJsonArray("inputs")) {
                JsonObject pin = pinElement.getAsJsonObject();
                if (defaults.containsKey(pin.get("id").getAsString())) {
                    assertEquals(defaults.get(pin.get("id").getAsString()), pin.get("defaultValue").getAsString());
                } else {
                    assertFalse(pin.has("defaultValue"));
                }
            }
            assertFalse(raw.getAsJsonArray("outputs").asList().stream().anyMatch(pin -> pin.getAsJsonObject().has("defaultValue")));
        }

        assertTrue(rawById.get("list_index_of").get("description").getAsString().contains("empty list"));
        assertTrue(rawById.get("list_index_of").get("description").getAsString().contains("null value"));
        assertTrue(rawById.get("list_index_of").get("description").getAsString().contains("-1"));
        assertTrue(rawById.get("list_count").get("description").getAsString().contains("equality"));
        assertTrue(rawById.get("list_count").get("description").getAsString().contains("null value"));
        assertTrue(rawById.get("list_first").get("description").getAsString().contains("returns null"));
        assertTrue(rawById.get("list_last").get("description").getAsString().contains("returns null"));
        assertDescriptionContains(rawById.get("list_find_first"), "property predicate", "empty list", "no match returns null");
        assertDescriptionContains(rawById.get("list_find_all"), "every index", "null values", "ascending order");
        assertDescriptionContains(rawById.get("list_find_index"), "first index", "blank condition", "no match returns -1");
        assertDescriptionContains(rawById.get("list_contains_any"), "at least one", "empty item list returns true");
        assertDescriptionContains(rawById.get("list_contains_all"), "every value", "empty item list returns true");
        assertDescriptionContains(rawById.get("list_any"), "at least one value", "blank property");
        assertDescriptionContains(rawById.get("list_all"), "every value", "blank property");
        assertDescriptionContains(rawById.get("list_none"), "no values", "blank property");
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static List<String> pinContracts(JsonArray pins) {
        return pins.asList().stream().map(element -> {
            JsonObject pin = element.getAsJsonObject();
            assertEquals(pin.get("id").getAsString(), pin.get("name").getAsString());
            assertEquals("DATA", pin.get("pinType").getAsString());
            assertTrue(pin.has("displayName"));
            assertTrue(pin.has("description"));
            assertFalse(pin.get("description").getAsString().isBlank());
            return pin.get("id").getAsString() + ":" + pin.get("dataType").getAsString();
        }).toList();
    }

    private static void assertDescriptionContains(JsonObject node, String... fragments) {
        String description = node.get("description").getAsString().toLowerCase();
        for (String fragment : fragments) {
            assertTrue(description.contains(fragment.toLowerCase()), node.get("id").getAsString() + ": " + description);
        }
    }
}
