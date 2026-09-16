package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.GenericMathHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathAggregateCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "math_aggregate.json");
    private static final List<String> IDS = List.of("math_min", "math_max");

    @Test
    void aggregateMathCatalogPublishesExactRuntimeContracts() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new GenericMathHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        Map<String, String> operations = Map.of("math_min", "min", "math_max", "max");
        Map<String, List<String>> legacyIds = Map.of(
            "math_min", List.of("math.min", "math.min_list"),
            "math_max", List.of("math.max", "math.max_list"));
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("math", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("generic-math", raw.get("handlerCapability").getAsString());
            assertEquals("GenericMathHandler", raw.get("handler").getAsString());
            assertEquals("DATA", raw.get("category").getAsString());
            assertEquals(1, raw.get("schemaVersion").getAsInt());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertFalse(raw.has("migrationMapping"));
            assertEquals(1, raw.getAsJsonArray("inputs").size());
            JsonObject input = raw.getAsJsonArray("inputs").get(0).getAsJsonObject();
            assertEquals("values_list", input.get("id").getAsString());
            assertEquals("Values List", input.get("displayName").getAsString());
            assertEquals("values_list", input.get("name").getAsString());
            assertEquals("DATA", input.get("pinType").getAsString());
            assertEquals("list<any>", input.get("dataType").getAsString());
            assertFalse(input.has("defaultValue"));
            assertTrue(input.get("description").getAsString().contains("Non-number values are ignored"));
            assertTrue(input.get("description").getAsString().contains("empty list"));
            JsonObject output = raw.getAsJsonArray("outputs").get(0).getAsJsonObject();
            String outputId = definition.getId().equals("math_min") ? "min" : "max";
            String displayName = definition.getId().equals("math_min") ? "Minimum" : "Maximum";
            assertEquals(outputId, output.get("id").getAsString());
            assertEquals(displayName, output.get("displayName").getAsString());
            assertEquals(outputId, output.get("name").getAsString());
            assertEquals("DATA", output.get("pinType").getAsString());
            assertEquals("number", output.get("dataType").getAsString());
            assertTrue(output.get("description").getAsString().contains("or 0 when no number is present"));
            String description = raw.get("description").getAsString();
            assertTrue(description.contains("Non-number values are ignored"));
            assertTrue(description.contains("empty or number-free list returns 0"));
            assertTrue(description.contains("non-finite numbers fail"));
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
}
