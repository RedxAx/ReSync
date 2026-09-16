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
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathArithmeticCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "math_arithmetic.json");
    private static final List<String> IDS = List.of(
        "math_add", "math_subtract", "math_multiply", "math_negate", "math_hypotenuse", "math_sin", "math_cos", "math_tan", "math_atan",
        "math_random", "math_vector_add", "math_vector_subtract", "math_vector_multiply", "math_vector_multiply_components",
        "math_vector_cross", "math_vector_dot");

    @Test
    void arithmeticMathCatalogPublishesExactRuntimeContracts() throws Exception {
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
            Map.entry("math_add", "add"),
            Map.entry("math_subtract", "subtract"),
            Map.entry("math_multiply", "multiply"),
            Map.entry("math_negate", "negate"),
            Map.entry("math_hypotenuse", "hypotenuse"),
            Map.entry("math_sin", "sin"),
            Map.entry("math_cos", "cos"),
            Map.entry("math_tan", "tan"),
            Map.entry("math_atan", "atan"),
            Map.entry("math_random", "random"),
            Map.entry("math_vector_add", "vector_add"),
            Map.entry("math_vector_subtract", "vector_subtract"),
            Map.entry("math_vector_multiply", "vector_multiply"),
            Map.entry("math_vector_multiply_components", "vector_multiply_components"),
            Map.entry("math_vector_cross", "vector_cross"),
            Map.entry("math_vector_dot", "vector_dot"));
        Map<String, List<String>> legacyIds = Map.ofEntries(
            Map.entry("math_add", List.of("math.add")),
            Map.entry("math_subtract", List.of("math.subtract")),
            Map.entry("math_multiply", List.of("math.multiply")),
            Map.entry("math_negate", List.of("math.negate")),
            Map.entry("math_hypotenuse", List.of("math.hypotenuse")),
            Map.entry("math_sin", List.of("math.sin")),
            Map.entry("math_cos", List.of("math.cos")),
            Map.entry("math_tan", List.of("math.tan")),
            Map.entry("math_atan", List.of("math.atan")),
            Map.entry("math_random", List.of("math.random")),
            Map.entry("math_vector_add", List.of("math.vector_add")),
            Map.entry("math_vector_subtract", List.of("math.vector_subtract")),
            Map.entry("math_vector_multiply", List.of("math.vector_multiply")),
            Map.entry("math_vector_multiply_components", List.of("math.vector_multiply_components")),
            Map.entry("math_vector_cross", List.of("math.vector_cross")),
            Map.entry("math_vector_dot", List.of("math.vector_dot")));
        Map<String, List<PinContract>> inputs = Map.ofEntries(
            Map.entry("math_add", List.of(new PinContract("a", "A", 0D), new PinContract("b", "B", 0D))),
            Map.entry("math_subtract", List.of(new PinContract("a", "A", 0D), new PinContract("b", "B", 0D))),
            Map.entry("math_multiply", List.of(new PinContract("a", "A", 0D), new PinContract("b", "B", 0D))),
            Map.entry("math_negate", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_hypotenuse", List.of(new PinContract("a", "A", 0D), new PinContract("b", "B", 0D))),
            Map.entry("math_sin", List.of(new PinContract("angle_degrees", "Angle Degrees", 0D))),
            Map.entry("math_cos", List.of(new PinContract("angle_degrees", "Angle Degrees", 0D))),
            Map.entry("math_tan", List.of(new PinContract("angle_degrees", "Angle Degrees", 0D))),
            Map.entry("math_atan", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_random", List.of(new PinContract("min", "Min", 0D), new PinContract("max", "Max", 1D))),
            Map.entry("math_vector_add", List.of(new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))),
            Map.entry("math_vector_subtract", List.of(new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))),
            Map.entry("math_vector_multiply", List.of(new PinContract("vector", "Vector", "vector"), new PinContract("scalar", "Scalar", 1D))),
            Map.entry("math_vector_multiply_components", List.of(
                new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))),
            Map.entry("math_vector_cross", List.of(
                new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))),
            Map.entry("math_vector_dot", List.of(
                new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))));
        Map<String, List<PinContract>> outputs = Map.ofEntries(
            Map.entry("math_add", List.of(new PinContract("result", "Result"))),
            Map.entry("math_subtract", List.of(new PinContract("result", "Result"))),
            Map.entry("math_multiply", List.of(new PinContract("result", "Result"))),
            Map.entry("math_negate", List.of(new PinContract("result", "Result"))),
            Map.entry("math_hypotenuse", List.of(new PinContract("hypotenuse", "Hypotenuse"))),
            Map.entry("math_sin", List.of(new PinContract("sin", "Sin"))),
            Map.entry("math_cos", List.of(new PinContract("cos", "Cos"))),
            Map.entry("math_tan", List.of(new PinContract("tan", "Tan"))),
            Map.entry("math_atan", List.of(new PinContract("angle_degrees", "Angle Degrees"))),
            Map.entry("math_random", List.of(new PinContract("result", "Result"))),
            Map.entry("math_vector_add", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_subtract", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_multiply", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_multiply_components", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_cross", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_dot", List.of(new PinContract("result", "Result"))));
        Map<String, Integer> schemaVersions = Map.ofEntries(
            Map.entry("math_add", 1), Map.entry("math_subtract", 1), Map.entry("math_multiply", 1), Map.entry("math_negate", 1),
            Map.entry("math_hypotenuse", 1), Map.entry("math_sin", 1), Map.entry("math_cos", 1), Map.entry("math_tan", 1),
            Map.entry("math_atan", 1), Map.entry("math_random", 2), Map.entry("math_vector_add", 2), Map.entry("math_vector_subtract", 2),
            Map.entry("math_vector_multiply", 2), Map.entry("math_vector_multiply_components", 2), Map.entry("math_vector_cross", 2),
            Map.entry("math_vector_dot", 2));
        Map<String, List<String>> migrationPins = Map.ofEntries(
            Map.entry("math_random", List.of("input:min->min", "input:max->max", "output:result->result")),
            Map.entry("math_vector_add", List.of("input:vector1->vector1", "input:vector2->vector2", "output:result_vector->result_vector")),
            Map.entry("math_vector_subtract", List.of("input:vector1->vector1", "input:vector2->vector2", "output:result_vector->result_vector")),
            Map.entry("math_vector_multiply", List.of("input:vector->vector", "input:scalar->scalar", "output:result_vector->result_vector")),
            Map.entry("math_vector_multiply_components", List.of(
                "input:vector1->vector1", "input:vector2->vector2", "output:result_vector->result_vector")),
            Map.entry("math_vector_cross", List.of("input:vector1->vector1", "input:vector2->vector2", "output:result_vector->result_vector")),
            Map.entry("math_vector_dot", List.of("input:vector1->vector1", "input:vector2->vector2", "output:result->result")));

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
            assertEquals(schemaVersions.get(definition.getId()), raw.get("schemaVersion").getAsInt());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            if (migrationPins.containsKey(definition.getId())) {
                assertMigration(definition, raw, migrationPins.get(definition.getId()));
            } else {
                assertFalse(raw.has("migrationMapping"));
                assertTrue(definition.getPinMigrationMappings().isEmpty());
            }
            assertPins(raw.getAsJsonArray("inputs"), inputs.get(definition.getId()), true);
            assertPins(raw.getAsJsonArray("outputs"), outputs.get(definition.getId()), false);
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            String description = raw.get("description").getAsString();
            if (definition.getId().equals("math_random")) {
                assertTrue(description.contains("inclusive"));
                assertTrue(description.contains("exclusive"));
                assertTrue(description.contains("defaults"));
            } else if (definition.getId().startsWith("math_vector_")) {
                assertTrue(description.contains("vector"));
            } else {
                assertTrue(description.contains("finite"));
                assertTrue(description.contains("fail"));
            }
        }

        for (String id : List.of("math_sin", "math_cos", "math_tan", "math_atan")) {
            String description = rawById.get(id).get("description").getAsString();
            assertTrue(description.contains("degrees"));
            assertTrue(description.contains("radians"));
        }
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static void assertMigration(NodeDefinition definition, JsonObject raw, List<String> expectedMappings) {
        JsonObject migration = raw.getAsJsonObject("migrationMapping");
        assertEquals(1, migration.get("sourceSchemaVersion").getAsInt());
        assertEquals(2, migration.get("targetSchemaVersion").getAsInt());
        assertTrue(migration.get("complete").getAsBoolean());
        assertEquals(expectedMappings, migration.getAsJsonArray("pins").asList().stream().map(pin -> {
            JsonObject value = pin.getAsJsonObject();
            return value.get("direction").getAsString() + ":" + value.get("source").getAsString()
                + "->" + value.get("target").getAsString();
        }).toList());
        assertEquals(expectedMappings, definition.getPinMigrationMappings().stream()
            .map(mapping -> mapping.direction().name().toLowerCase(Locale.ROOT) + ":"
                + mapping.sourcePinId().value() + "->" + mapping.targetPinId().value()).toList());
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
            assertEquals(contract.dataType(), pin.get("dataType").getAsString());
            assertTrue(pin.has("description"));
            assertFalse(pin.get("description").getAsString().isBlank());
            if (input && contract.defaultValue() != null) {
                assertEquals(contract.defaultValue(), pin.get("defaultValue").getAsDouble());
            } else {
                assertFalse(pin.has("defaultValue"));
            }
        }
    }

    private record PinContract(String id, String displayName, String dataType, Double defaultValue) {
        private PinContract(String id, String displayName) {
            this(id, displayName, "number", null);
        }

        private PinContract(String id, String displayName, String dataType) {
            this(id, displayName, dataType, null);
        }

        private PinContract(String id, String displayName, double defaultValue) {
            this(id, displayName, "number", defaultValue);
        }
    }
}
