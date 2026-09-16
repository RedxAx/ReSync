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

class MathRangeCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "math_range.json");
    private static final List<String> IDS = List.of(
        "math_clamp", "math_lerp", "math_round", "math_asin", "math_acos", "math_atan2", "math_distance", "math_random_range",
        "math_vector_min", "math_vector_max", "math_vector_floor", "math_vector_ceil", "math_vector_round", "math_vector_distance",
        "math_vector_angle_between", "math_vector_midpoint", "math_vector_rotate_x", "math_vector_rotate_y", "math_vector_rotate_z");

    @Test
    void rangeMathCatalogPublishesExactRuntimeContracts() throws Exception {
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
            Map.entry("math_clamp", "clamp"),
            Map.entry("math_lerp", "lerp"),
            Map.entry("math_round", "round"),
            Map.entry("math_asin", "asin"),
            Map.entry("math_acos", "acos"),
            Map.entry("math_atan2", "atan2"),
            Map.entry("math_distance", "distance"),
            Map.entry("math_random_range", "random_range"),
            Map.entry("math_vector_min", "vector_min"),
            Map.entry("math_vector_max", "vector_max"),
            Map.entry("math_vector_floor", "vector_floor"),
            Map.entry("math_vector_ceil", "vector_ceil"),
            Map.entry("math_vector_round", "vector_round"),
            Map.entry("math_vector_distance", "vector_distance"),
            Map.entry("math_vector_angle_between", "vector_angle_between"),
            Map.entry("math_vector_midpoint", "vector_midpoint"),
            Map.entry("math_vector_rotate_x", "vector_rotate_x"),
            Map.entry("math_vector_rotate_y", "vector_rotate_y"),
            Map.entry("math_vector_rotate_z", "vector_rotate_z"));
        Map<String, List<String>> legacyIds = Map.ofEntries(
            Map.entry("math_clamp", List.of("math.clamp")),
            Map.entry("math_lerp", List.of("math.lerp")),
            Map.entry("math_round", List.of("math.round")),
            Map.entry("math_asin", List.of("math.asin")),
            Map.entry("math_acos", List.of("math.acos")),
            Map.entry("math_atan2", List.of("math.atan2")),
            Map.entry("math_distance", List.of("math.distance")),
            Map.entry("math_random_range", List.of("math.random_range", "random.range")),
            Map.entry("math_vector_min", List.of("math.vector_min")),
            Map.entry("math_vector_max", List.of("math.vector_max")),
            Map.entry("math_vector_floor", List.of("math.vector_floor")),
            Map.entry("math_vector_ceil", List.of("math.vector_ceil")),
            Map.entry("math_vector_round", List.of("math.vector_round")),
            Map.entry("math_vector_distance", List.of("math.vector_distance")),
            Map.entry("math_vector_angle_between", List.of("math.vector_angle_between")),
            Map.entry("math_vector_midpoint", List.of("math.vector_midpoint")),
            Map.entry("math_vector_rotate_x", List.of("math.vector_rotate_x")),
            Map.entry("math_vector_rotate_y", List.of("math.vector_rotate_y")),
            Map.entry("math_vector_rotate_z", List.of("math.vector_rotate_z")));
        Map<String, List<PinContract>> inputs = Map.ofEntries(
            Map.entry("math_clamp", List.of(
                new PinContract("value", "Value", 0D), new PinContract("min", "Min", 0D), new PinContract("max", "Max", 1D))),
            Map.entry("math_lerp", List.of(
                new PinContract("a", "A", 0D), new PinContract("b", "B", 0D), new PinContract("t", "T", 0.5D))),
            Map.entry("math_round", List.of(
                new PinContract("value", "Value", 0D), new PinContract("decimal_places", "Decimal Places", 0D))),
            Map.entry("math_asin", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_acos", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_atan2", List.of(new PinContract("y", "Y", 0D), new PinContract("x", "X", 0D))),
            Map.entry("math_distance", List.of(
                new PinContract("x1", "X1", 0D), new PinContract("y1", "Y1", 0D), new PinContract("z1", "Z1", 0D),
                new PinContract("x2", "X2", 0D), new PinContract("y2", "Y2", 0D), new PinContract("z2", "Z2", 0D))),
            Map.entry("math_random_range", List.of(new PinContract("min", "Min", 0D), new PinContract("max", "Max", 1D))),
            Map.entry("math_vector_min", List.of(new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))),
            Map.entry("math_vector_max", List.of(new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))),
            Map.entry("math_vector_floor", List.of(new PinContract("vector", "Vector", "vector"))),
            Map.entry("math_vector_ceil", List.of(new PinContract("vector", "Vector", "vector"))),
            Map.entry("math_vector_round", List.of(new PinContract("vector", "Vector", "vector"))),
            Map.entry("math_vector_distance", List.of(new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))),
            Map.entry("math_vector_angle_between", List.of(
                new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))),
            Map.entry("math_vector_midpoint", List.of(
                new PinContract("vector1", "Vector 1", "vector"), new PinContract("vector2", "Vector 2", "vector"))),
            Map.entry("math_vector_rotate_x", List.of(new PinContract("vector", "Vector", "vector"), new PinContract("degrees", "Degrees", 0D))),
            Map.entry("math_vector_rotate_y", List.of(new PinContract("vector", "Vector", "vector"), new PinContract("degrees", "Degrees", 0D))),
            Map.entry("math_vector_rotate_z", List.of(new PinContract("vector", "Vector", "vector"), new PinContract("degrees", "Degrees", 0D))));
        Map<String, List<PinContract>> outputs = Map.ofEntries(
            Map.entry("math_clamp", List.of(new PinContract("clamped", "Clamped"))),
            Map.entry("math_lerp", List.of(new PinContract("result", "Result"))),
            Map.entry("math_round", List.of(new PinContract("rounded", "Rounded"))),
            Map.entry("math_asin", List.of(new PinContract("angle_degrees", "Angle Degrees"))),
            Map.entry("math_acos", List.of(new PinContract("angle_degrees", "Angle Degrees"))),
            Map.entry("math_atan2", List.of(new PinContract("angle_degrees", "Angle Degrees"))),
            Map.entry("math_distance", List.of(new PinContract("result", "Result"))),
            Map.entry("math_random_range", List.of(new PinContract("result", "Result"))),
            Map.entry("math_vector_min", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_max", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_floor", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_ceil", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_round", List.of(new PinContract("result_vector", "Result Vector", "vector"))),
            Map.entry("math_vector_distance", List.of(new PinContract("distance", "Distance"))),
            Map.entry("math_vector_angle_between", List.of(new PinContract("angle", "Angle"))),
            Map.entry("math_vector_midpoint", List.of(new PinContract("midpoint_vector", "Midpoint Vector", "vector"))),
            Map.entry("math_vector_rotate_x", List.of(new PinContract("rotated_vector", "Rotated Vector", "vector"))),
            Map.entry("math_vector_rotate_y", List.of(new PinContract("rotated_vector", "Rotated Vector", "vector"))),
            Map.entry("math_vector_rotate_z", List.of(new PinContract("rotated_vector", "Rotated Vector", "vector"))));
        Map<String, Integer> schemaVersions = Map.ofEntries(
            Map.entry("math_clamp", 1), Map.entry("math_lerp", 1), Map.entry("math_round", 1), Map.entry("math_asin", 1),
            Map.entry("math_acos", 1), Map.entry("math_atan2", 1), Map.entry("math_distance", 1), Map.entry("math_random_range", 2),
            Map.entry("math_vector_min", 2), Map.entry("math_vector_max", 2), Map.entry("math_vector_floor", 2),
            Map.entry("math_vector_ceil", 2), Map.entry("math_vector_round", 2), Map.entry("math_vector_distance", 2),
            Map.entry("math_vector_angle_between", 2), Map.entry("math_vector_midpoint", 2), Map.entry("math_vector_rotate_x", 2),
            Map.entry("math_vector_rotate_y", 2), Map.entry("math_vector_rotate_z", 2));
        Map<String, String> categories = Map.ofEntries(
            Map.entry("math_random_range", "UTILITY"),
            Map.entry("math_clamp", "DATA"), Map.entry("math_lerp", "DATA"), Map.entry("math_round", "DATA"),
            Map.entry("math_asin", "DATA"), Map.entry("math_acos", "DATA"), Map.entry("math_atan2", "DATA"),
            Map.entry("math_distance", "DATA"), Map.entry("math_vector_min", "DATA"), Map.entry("math_vector_max", "DATA"),
            Map.entry("math_vector_floor", "DATA"), Map.entry("math_vector_ceil", "DATA"), Map.entry("math_vector_round", "DATA"),
            Map.entry("math_vector_distance", "DATA"), Map.entry("math_vector_angle_between", "DATA"),
            Map.entry("math_vector_midpoint", "DATA"), Map.entry("math_vector_rotate_x", "DATA"),
            Map.entry("math_vector_rotate_y", "DATA"), Map.entry("math_vector_rotate_z", "DATA"));
        Map<String, List<String>> migrationPins = Map.ofEntries(
            Map.entry("math_random_range", List.of("input:min->min", "input:max->max", "output:result->result")),
            Map.entry("math_vector_min", List.of("input:vector1->vector1", "input:vector2->vector2", "output:result_vector->result_vector")),
            Map.entry("math_vector_max", List.of("input:vector1->vector1", "input:vector2->vector2", "output:result_vector->result_vector")),
            Map.entry("math_vector_floor", List.of("input:vector->vector", "output:result_vector->result_vector")),
            Map.entry("math_vector_ceil", List.of("input:vector->vector", "output:result_vector->result_vector")),
            Map.entry("math_vector_round", List.of("input:vector->vector", "output:result_vector->result_vector")),
            Map.entry("math_vector_distance", List.of("input:vector1->vector1", "input:vector2->vector2", "output:distance->distance")),
            Map.entry("math_vector_angle_between", List.of("input:vector1->vector1", "input:vector2->vector2", "output:angle->angle")),
            Map.entry("math_vector_midpoint", List.of("input:vector1->vector1", "input:vector2->vector2", "output:midpoint_vector->midpoint_vector")),
            Map.entry("math_vector_rotate_x", List.of("input:vector->vector", "input:degrees->degrees", "output:rotated_vector->rotated_vector")),
            Map.entry("math_vector_rotate_y", List.of("input:vector->vector", "input:degrees->degrees", "output:rotated_vector->rotated_vector")),
            Map.entry("math_vector_rotate_z", List.of("input:vector->vector", "input:degrees->degrees", "output:rotated_vector->rotated_vector")));

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("math", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("generic-math", raw.get("handlerCapability").getAsString());
            assertEquals("GenericMathHandler", raw.get("handler").getAsString());
            assertEquals(categories.get(definition.getId()), raw.get("category").getAsString());
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
            if (definition.getId().equals("math_random_range")) {
                assertTrue(description.contains("inclusive"));
                assertTrue(description.contains("exclusive"));
            } else if (definition.getId().startsWith("math_vector_")) {
                assertTrue(description.contains("vector"));
            }
        }

        for (String id : List.of("math_asin", "math_acos", "math_atan2")) {
            String description = rawById.get(id).get("description").getAsString();
            assertTrue(description.contains("degrees"));
        }
        String finiteFailure = "Non-finite numeric inputs fail before a result is published.";
        for (String id : IDS.subList(0, 7)) {
            JsonObject raw = rawById.get(id);
            assertTrue(raw.get("description").getAsString().contains(finiteFailure), id);
            for (JsonElement input : raw.getAsJsonArray("inputs")) {
                assertTrue(input.getAsJsonObject().get("description").getAsString().contains(finiteFailure), id);
            }
        }
        String roundBound = "clamped to [-15, 15] before rounding";
        assertTrue(rawById.get("math_round").get("description").getAsString().contains(roundBound));
        JsonElement roundDecimalPlaces = rawById.get("math_round").getAsJsonArray("inputs").get(1);
        assertTrue(roundDecimalPlaces.getAsJsonObject().get("description").getAsString().contains(roundBound));
        assertTrue(rawById.get("math_clamp").get("description").getAsString().contains("inclusive range"));
        assertTrue(rawById.get("math_clamp").get("description").getAsString().contains("below min"));
        assertTrue(rawById.get("math_clamp").get("description").getAsString().contains("above max"));
        assertTrue(rawById.get("math_clamp").get("description").getAsString().contains("min is greater than max"));
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
