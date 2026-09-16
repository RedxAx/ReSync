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

class MathDivisionCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "math_division.json");
    private static final List<String> IDS = List.of("math_divide", "math_modulo", "math_vector_divide", "math_vector_divide_components");

    @Test
    void divisionMathCatalogPublishesExactRuntimeContracts() throws Exception {
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

        Map<String, String> operations = Map.of(
            "math_divide", "divide",
            "math_modulo", "modulo",
            "math_vector_divide", "vector_divide",
            "math_vector_divide_components", "vector_divide_components");
        Map<String, List<String>> legacyIds = Map.of(
            "math_divide", List.of("math.divide"),
            "math_modulo", List.of("math.modulo"),
            "math_vector_divide", List.of("math.vector_divide"),
            "math_vector_divide_components", List.of("math.vector_divide_components"));
        Map<String, Integer> schemaVersions = Map.of(
            "math_divide", 1,
            "math_modulo", 1,
            "math_vector_divide", 2,
            "math_vector_divide_components", 2);
        Map<String, List<PinContract>> inputs = Map.of(
            "math_divide", List.of(new PinContract("a", "A", 0D), new PinContract("b", "B", 1D)),
            "math_modulo", List.of(new PinContract("a", "A", 0D), new PinContract("b", "B", 1D)),
            "math_vector_divide", List.of(new PinContract("vector", "Vector", "vector"), new PinContract("scalar", "Scalar", 1D)),
            "math_vector_divide_components", List.of(
                new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector")));
        Map<String, List<PinContract>> outputs = Map.of(
            "math_divide", List.of(new PinContract("result", "Result")),
            "math_modulo", List.of(new PinContract("result", "Result")),
            "math_vector_divide", List.of(new PinContract("result_vector", "Result Vector", "vector")),
            "math_vector_divide_components", List.of(new PinContract("result_vector", "Result Vector", "vector")));
        Map<String, List<String>> migrationPins = Map.of(
            "math_vector_divide", List.of("input:vector->vector", "input:scalar->scalar", "output:result_vector->result_vector"),
            "math_vector_divide_components", List.of(
                "input:vector1->vector1", "input:vector2->vector2", "output:result_vector->result_vector"));
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
            if (definition.getId().startsWith("math_vector_")) {
                assertTrue(description.contains("zero"));
            } else {
                assertTrue(description.contains("finite"));
                assertTrue(description.contains("fail"));
                assertTrue(description.contains("zero divisor returns 0"));
            }
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
