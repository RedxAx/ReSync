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

class ListOrderingCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "list_ordering.json");
    private static final List<String> IDS = List.of("list_sort", "list_sort_descending", "list_sort_by_property");

    @Test
    void listOrderingCatalogPublishesExactRuntimeContracts() throws Exception {
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

        Map<String, String> operations = Map.of(
            "list_sort", "sort",
            "list_sort_descending", "sort_descending",
            "list_sort_by_property", "sort_by_property");
        Map<String, List<String>> legacyIds = Map.of(
            "list_sort", List.of("list.sort"),
            "list_sort_descending", List.of("list.sort_descending"),
            "list_sort_by_property", List.of("list.sort_by_property"));
        Map<String, List<String>> inputPins = Map.of(
            "list_sort", List.of("list:list<type:t>", "sort_order:string"),
            "list_sort_descending", List.of("list:list<type:t>"),
            "list_sort_by_property", List.of("list:list<type:t>", "property:string"));
        Map<String, List<String>> outputPins = Map.of(
            "list_sort", List.of("sorted_list:list<type:t>"),
            "list_sort_descending", List.of("output_list:list<type:t>"),
            "list_sort_by_property", List.of("output_list:list<type:t>"));
        Map<String, Integer> schemaVersions = Map.of(
            "list_sort", 1,
            "list_sort_descending", 2,
            "list_sort_by_property", 2);
        Map<String, List<String>> descriptionFragments = Map.of(
            "list_sort", List.of("stable", "null", "finite", "natural", "mixed", "object", "shallow", "65536"),
            "list_sort_descending", List.of("stable", "null", "finite", "natural", "mixed", "object", "shallow", "65536"),
            "list_sort_by_property", List.of("stable", "shallow", "null property values", "required", "unsupported property values"));

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
            assertEquals(schemaVersions.get(definition.getId()), raw.get("schemaVersion").getAsInt());
            assertEquals("PURE", raw.get("kind").getAsString());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            if (definition.getId().equals("list_sort_descending")) {
                JsonObject migration = raw.getAsJsonObject("migrationMapping");
                assertEquals(1, migration.get("sourceSchemaVersion").getAsInt());
                assertEquals(2, migration.get("targetSchemaVersion").getAsInt());
                assertTrue(migration.get("complete").getAsBoolean());
                assertEquals(List.of("input:list->list", "output:list->output_list"), migration.getAsJsonArray("pins").asList().stream()
                    .map(pin -> {
                        JsonObject value = pin.getAsJsonObject();
                        return value.get("direction").getAsString() + ":" + value.get("source").getAsString()
                            + "->" + value.get("target").getAsString();
                    }).toList());
                assertEquals(List.of("input:list->list", "output:list->output_list"), definition.getPinMigrationMappings().stream()
                    .map(mapping -> mapping.direction().name().toLowerCase() + ":" + mapping.sourcePinId().value()
                        + "->" + mapping.targetPinId().value()).toList());
            } else {
                assertFalse(raw.has("migrationMapping"));
                assertTrue(definition.getPinMigrationMappings().isEmpty());
            }
            assertEquals(inputPins.get(definition.getId()), pinContracts(raw.getAsJsonArray("inputs")));
            assertEquals(outputPins.get(definition.getId()), pinContracts(raw.getAsJsonArray("outputs")));
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertAllListPinsDeclareLimit(raw);
            assertDescriptionContains(raw, descriptionFragments.get(definition.getId()).toArray(String[]::new));
        }

        JsonObject ascending = rawById.get("list_sort");
        JsonObject sortOrder = pin(ascending, "inputs", "sort_order");
        assertEquals("DROPDOWN", sortOrder.get("widget").getAsString());
        assertEquals(List.of("ascending", "descending"), sortOrder.getAsJsonArray("options").asList().stream()
            .map(JsonElement::getAsString).toList());
        assertEquals("ascending", sortOrder.get("defaultValue").getAsString());
        assertDescriptionContains(ascending, "ascending", "descending", "exactly");
        assertDescriptionContains(rawById.get("list_sort_descending"), "descending", "null", "first");
        assertEquals("list", pin(rawById.get("list_sort_descending"), "outputs", "output_list").get("name").getAsString());
        JsonObject propertySort = rawById.get("list_sort_by_property");
        assertEquals("", pin(propertySort, "inputs", "property").get("defaultValue").getAsString());
        assertEquals("property", pin(propertySort, "inputs", "property").get("name").getAsString());
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static List<String> pinContracts(JsonArray pins) {
        return pins.asList().stream().map(element -> {
            JsonObject pin = element.getAsJsonObject();
            assertTrue(pin.has("name"));
            assertFalse(pin.get("name").getAsString().isBlank());
            assertEquals("DATA", pin.get("pinType").getAsString());
            assertTrue(pin.has("displayName"));
            assertTrue(pin.has("description"));
            assertFalse(pin.get("description").getAsString().isBlank());
            return pin.get("id").getAsString() + ":" + pin.get("dataType").getAsString();
        }).toList();
    }

    private static JsonObject pin(JsonObject node, String direction, String id) {
        for (JsonElement element : node.getAsJsonArray(direction)) {
            JsonObject pin = element.getAsJsonObject();
            if (id.equals(pin.get("id").getAsString())) {
                return pin;
            }
        }
        throw new AssertionError("Missing " + direction + " pin " + id);
    }

    private static void assertAllListPinsDeclareLimit(JsonObject node) {
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
