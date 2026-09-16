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

class MathPowerCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "math_power.json");
    private static final List<String> IDS = List.of("math_log", "math_log10", "math_pow", "math_power", "math_round_decimal");

    @Test
    void powerMathCatalogPublishesExactRuntimeContracts() throws Exception {
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

        Map<String, String> operations = Map.ofEntries(
            Map.entry("math_log", "log"),
            Map.entry("math_log10", "log10"),
            Map.entry("math_pow", "pow"),
            Map.entry("math_power", "power"),
            Map.entry("math_round_decimal", "round_decimal"));
        Map<String, List<String>> legacyIds = Map.ofEntries(
            Map.entry("math_log", List.of("math.log")),
            Map.entry("math_log10", List.of("math.log10")),
            Map.entry("math_pow", List.of("math.pow")),
            Map.entry("math_power", List.of("math.power")),
            Map.entry("math_round_decimal", List.of("math.round_decimal")));
        Map<String, List<PinContract>> inputs = Map.ofEntries(
            Map.entry("math_log", List.of(new PinContract("value", "Value", 1D))),
            Map.entry("math_log10", List.of(new PinContract("value", "Value", 1D))),
            Map.entry("math_pow", List.of(new PinContract("base", "Base", 0D), new PinContract("exponent", "Exponent", 1D))),
            Map.entry("math_power", List.of(new PinContract("base", "Base", 0D), new PinContract("exponent", "Exponent", 0D))),
            Map.entry("math_round_decimal", List.of(new PinContract("value", "Value", 0D), new PinContract("decimal_places", "Decimal Places", 0D))));
        Map<String, List<PinContract>> outputs = Map.ofEntries(
            Map.entry("math_log", List.of(new PinContract("log", "Log", null))),
            Map.entry("math_log10", List.of(new PinContract("log10", "Log10", null))),
            Map.entry("math_pow", List.of(new PinContract("result", "Result", null))),
            Map.entry("math_power", List.of(new PinContract("result", "Result", null))),
            Map.entry("math_round_decimal", List.of(new PinContract("rounded", "Rounded", null))));

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("math", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("generic-math", raw.get("handlerCapability").getAsString());
            assertEquals("GenericMathHandler", raw.get("handler").getAsString());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertFalse(raw.has("migrationMapping"));
            assertPins(raw.getAsJsonArray("inputs"), inputs.get(definition.getId()), true);
            assertPins(raw.getAsJsonArray("outputs"), outputs.get(definition.getId()), false);
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertTrue(definition.getPinMigrationMappings().isEmpty());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertTrue(raw.get("description").getAsString().contains("finite"));
            assertTrue(raw.get("description").getAsString().contains("fail"));
        }

        for (String id : List.of("math_log", "math_log10")) {
            String description = rawById.get(id).get("description").getAsString();
            assertTrue(description.contains("positive"));
            assertTrue(description.contains("Non-positive"));
        }
        assertEquals(1, rawById.get("math_log").getAsJsonArray("inputs").get(0).getAsJsonObject().get("defaultValue").getAsInt());
        assertEquals(1, rawById.get("math_log10").getAsJsonArray("inputs").get(0).getAsJsonObject().get("defaultValue").getAsInt());
        assertEquals(1, rawById.get("math_pow").getAsJsonArray("inputs").get(1).getAsJsonObject().get("defaultValue").getAsInt());
        assertEquals(0, rawById.get("math_power").getAsJsonArray("inputs").get(1).getAsJsonObject().get("defaultValue").getAsInt());
        String roundDescription = rawById.get("math_round_decimal").get("description").getAsString();
        assertTrue(roundDescription.contains("clamped to [-15, 15]"));
        assertTrue(rawById.get("math_round_decimal").getAsJsonArray("inputs").get(1).getAsJsonObject().get("description").getAsString()
            .contains("clamped to [-15, 15]"));
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static void assertPins(JsonArray actual, List<PinContract> expected, boolean input) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            PinContract contract = expected.get(index);
            JsonObject pin = actual.get(index).getAsJsonObject();
            assertEquals(contract.id(), pin.get("id").getAsString());
            assertEquals(contract.displayName(), pin.get("displayName").getAsString());
            assertEquals(contract.id(), pin.get("name").getAsString());
            assertEquals("DATA", pin.get("pinType").getAsString());
            assertEquals("number", pin.get("dataType").getAsString());
            assertTrue(pin.has("description"));
            assertFalse(pin.get("description").getAsString().isBlank());
            if (input) {
                assertEquals(contract.defaultValue(), pin.get("defaultValue").getAsDouble());
            } else {
                assertFalse(pin.has("defaultValue"));
            }
        }
    }

    private record PinContract(String id, String displayName, Double defaultValue) {
    }
}
