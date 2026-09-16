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

class ListAggregateCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "list_aggregate.json");
    private static final List<String> IDS = List.of(
        "list_sum", "list_average", "list_min", "list_max", "list_median", "list_mode", "list_variance", "list_stddev");

    @Test
    void listAggregateCatalogPublishesExactRuntimeContracts() throws Exception {
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
            "list_sum", "sum",
            "list_average", "average",
            "list_min", "min",
            "list_max", "max",
            "list_median", "median",
            "list_mode", "mode",
            "list_variance", "variance",
            "list_stddev", "stddev");
        Map<String, String> legacyIds = Map.of(
            "list_sum", "list.sum",
            "list_average", "list.average",
            "list_min", "list.min",
            "list_max", "list.max",
            "list_median", "list.median",
            "list_mode", "list.mode",
            "list_variance", "list.variance",
            "list_stddev", "list.stddev");
        Map<String, String> outputIds = Map.of(
            "list_sum", "sum",
            "list_average", "average",
            "list_min", "min",
            "list_max", "max",
            "list_median", "median",
            "list_mode", "mode",
            "list_variance", "variance",
            "list_stddev", "stddev");
        Map<String, String> outputNames = Map.of(
            "list_sum", "Sum",
            "list_average", "Average",
            "list_min", "Minimum",
            "list_max", "Maximum",
            "list_median", "Median",
            "list_mode", "Mode",
            "list_variance", "Variance",
            "list_stddev", "Standard Deviation");
        Map<String, Integer> schemaVersions = Map.of(
            "list_sum", 1,
            "list_average", 1,
            "list_min", 1,
            "list_max", 1,
            "list_median", 2,
            "list_mode", 2,
            "list_variance", 2,
            "list_stddev", 2);
        Map<String, String> inputDataTypes = Map.of(
            "list_sum", "list<number>",
            "list_average", "list<number>",
            "list_min", "list<number>",
            "list_max", "list<number>",
            "list_median", "list<number>",
            "list_mode", "list<type:t>",
            "list_variance", "list<number>",
            "list_stddev", "list<number>");
        Map<String, String> outputDataTypes = Map.of(
            "list_sum", "number",
            "list_average", "number",
            "list_min", "number",
            "list_max", "number",
            "list_median", "number",
            "list_mode", "type:t",
            "list_variance", "number",
            "list_stddev", "number");
        Map<String, List<String>> inputDescriptionFragments = Map.ofEntries(
            Map.entry("list_sum", List.of("null and non-number values are ignored", "finite", "65536")),
            Map.entry("list_average", List.of("null and non-number values are ignored", "finite", "65536")),
            Map.entry("list_min", List.of("null and non-number values are ignored", "finite", "65536")),
            Map.entry("list_max", List.of("null and non-number values are ignored", "finite", "65536")),
            Map.entry("list_median", List.of("null and non-number values are ignored", "65536")),
            Map.entry("list_mode", List.of("empty list", "65536")),
            Map.entry("list_variance", List.of("null and non-number values are ignored", "65536")),
            Map.entry("list_stddev", List.of("null and non-number values are ignored", "65536")));
        Map<String, List<String>> outputDescriptionFragments = Map.ofEntries(
            Map.entry("list_sum", List.of("Double numeric value", "empty or number-free list returns 0", "non-finite results fail before output")),
            Map.entry("list_average", List.of("Double numeric value", "empty or number-free list returns 0", "non-finite results fail before output")),
            Map.entry("list_min", List.of("Double numeric value", "empty or number-free list returns 0", "non-finite results fail before output")),
            Map.entry("list_max", List.of("Double numeric value", "empty or number-free list returns 0", "non-finite results fail before output")),
            Map.entry("list_median", List.of("middle numeric value", "no numeric values returns 0")),
            Map.entry("list_mode", List.of("most frequent value", "null for an empty list")),
            Map.entry("list_variance", List.of("population variance", "0 when no numeric value is present")),
            Map.entry("list_stddev", List.of("population standard deviation", "0 when no numeric value is present")));
        Map<String, List<String>> nodeDescriptionFragments = Map.ofEntries(
            Map.entry("list_sum", List.of("null and non-number values are ignored", "empty or number-free list returns 0", "Double numeric value", "non-finite inputs or results fail before output")),
            Map.entry("list_average", List.of("null and non-number values are ignored", "empty or number-free list returns 0", "Double numeric value", "non-finite inputs or results fail before output")),
            Map.entry("list_min", List.of("null and non-number values are ignored", "empty or number-free list returns 0", "Double numeric value", "non-finite inputs or results fail before output")),
            Map.entry("list_max", List.of("null and non-number values are ignored", "empty or number-free list returns 0", "Double numeric value", "non-finite inputs or results fail before output")),
            Map.entry("list_median", List.of("numeric values", "null and non-number values are ignored", "empty or number-free list returns 0")),
            Map.entry("list_mode", List.of("most frequent value", "ties keep the first", "empty list returns null")),
            Map.entry("list_variance", List.of("population variance", "null and non-number values are ignored", "empty or number-free list returns 0")),
            Map.entry("list_stddev", List.of("population standard deviation", "null and non-number values are ignored", "empty or number-free list returns 0")));

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
            assertEquals(List.of(legacyIds.get(definition.getId())), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertFalse(raw.has("migrationMapping"));
            assertEquals(1, raw.getAsJsonArray("inputs").size());
            JsonObject input = raw.getAsJsonArray("inputs").get(0).getAsJsonObject();
            assertEquals("list", input.get("id").getAsString());
            assertEquals("List", input.get("displayName").getAsString());
            assertEquals("list", input.get("name").getAsString());
            assertEquals("DATA", input.get("pinType").getAsString());
            assertEquals(inputDataTypes.get(definition.getId()), input.get("dataType").getAsString());
            assertEquals(0, input.getAsJsonObject("constraints").get("min").getAsInt());
            assertEquals(65536, input.getAsJsonObject("constraints").get("max").getAsInt());
            assertFalse(input.has("defaultValue"));
            assertDescriptionContains(input, inputDescriptionFragments.get(definition.getId()).toArray(String[]::new));

            assertEquals(1, raw.getAsJsonArray("outputs").size());
            JsonObject output = raw.getAsJsonArray("outputs").get(0).getAsJsonObject();
            assertEquals(outputIds.get(definition.getId()), output.get("id").getAsString());
            assertEquals(outputNames.get(definition.getId()), output.get("displayName").getAsString());
            assertEquals(outputIds.get(definition.getId()), output.get("name").getAsString());
            assertEquals("DATA", output.get("pinType").getAsString());
            assertEquals(outputDataTypes.get(definition.getId()), output.get("dataType").getAsString());
            assertFalse(output.has("defaultValue"));
            assertDescriptionContains(output, outputDescriptionFragments.get(definition.getId()).toArray(String[]::new));

            assertDescriptionContains(raw, nodeDescriptionFragments.get(definition.getId()).toArray(String[]::new));
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertTrue(definition.getPinMigrationMappings().isEmpty());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
        }
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static void assertDescriptionContains(JsonObject object, String... fragments) {
        String description = object.get("description").getAsString().toLowerCase();
        for (String fragment : fragments) {
            assertTrue(description.contains(fragment.toLowerCase()), object.get("id") + ": " + description);
        }
    }
}
