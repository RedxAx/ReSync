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

class MathScalarCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "math_scalar.json");
    private static final List<String> IDS = List.of(
        "math_abs", "math_floor", "math_ceil", "math_sqrt", "math_cbrt", "math_signum", "math_to_radians", "math_to_degrees",
        "math_random_chance", "math_random_choice", "math_random_choice_weighted", "math_vector_create", "math_vector_create_int",
        "math_vector_split", "math_vector_set", "math_vector_length", "math_vector_normalize");

    @Test
    void scalarMathCatalogPublishesExactRuntimeContracts() throws Exception {
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
            Map.entry("math_abs", "abs"),
            Map.entry("math_floor", "floor"),
            Map.entry("math_ceil", "ceil"),
            Map.entry("math_sqrt", "sqrt"),
            Map.entry("math_cbrt", "cbrt"),
            Map.entry("math_signum", "signum"),
            Map.entry("math_to_radians", "to_radians"),
            Map.entry("math_to_degrees", "to_degrees"),
            Map.entry("math_random_chance", "random_chance"),
            Map.entry("math_random_choice", "random_choice"),
            Map.entry("math_random_choice_weighted", "random_choice_weighted"),
            Map.entry("math_vector_create", "vector_create"),
            Map.entry("math_vector_create_int", "vector_create_int"),
            Map.entry("math_vector_split", "vector_split"),
            Map.entry("math_vector_set", "vector_set"),
            Map.entry("math_vector_length", "vector_length"),
            Map.entry("math_vector_normalize", "vector_normalize"));
        Map<String, List<String>> legacyIds = Map.ofEntries(
            Map.entry("math_abs", List.of("math.abs")),
            Map.entry("math_floor", List.of("math.floor")),
            Map.entry("math_ceil", List.of("math.ceil")),
            Map.entry("math_sqrt", List.of("math.sqrt")),
            Map.entry("math_cbrt", List.of("math.cbrt")),
            Map.entry("math_signum", List.of("math.signum")),
            Map.entry("math_to_radians", List.of("math.to_radians")),
            Map.entry("math_to_degrees", List.of("math.to_degrees")),
            Map.entry("math_random_chance", List.of("math.random_chance", "random.chance")),
            Map.entry("math_random_choice", List.of("math.random_choice")),
            Map.entry("math_random_choice_weighted", List.of("math.random_choice_weighted")),
            Map.entry("math_vector_create", List.of("math.vector_create")),
            Map.entry("math_vector_create_int", List.of("math.vector_create_int")),
            Map.entry("math_vector_split", List.of("math.vector_split")),
            Map.entry("math_vector_set", List.of("math.vector_set")),
            Map.entry("math_vector_length", List.of("math.vector_length")),
            Map.entry("math_vector_normalize", List.of("math.vector_normalize")));
        Map<String, List<PinContract>> inputs = Map.ofEntries(
            Map.entry("math_abs", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_floor", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_ceil", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_sqrt", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_cbrt", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_signum", List.of(new PinContract("value", "Value", 0D))),
            Map.entry("math_to_radians", List.of(new PinContract("degrees", "Degrees", 0D))),
            Map.entry("math_to_degrees", List.of(new PinContract("radians", "Radians", 0D))),
            Map.entry("math_random_chance", List.of(new PinContract("chance_percent", "Chance Percent", 50D))),
            Map.entry("math_random_choice", List.of(new PinContract("items_list", "Items List", "list<any>"))),
            Map.entry("math_random_choice_weighted", List.of(
                new PinContract("items_list", "Items List", "list<any>"), new PinContract("weights_list", "Weights List", "list<number>"))),
            Map.entry("math_vector_create", List.of(
                new PinContract("x", "X", 0D), new PinContract("y", "Y", 0D), new PinContract("z", "Z", 0D))),
            Map.entry("math_vector_create_int", List.of(
                new PinContract("x", "X", 0D), new PinContract("y", "Y", 0D), new PinContract("z", "Z", 0D))),
            Map.entry("math_vector_split", List.of(new PinContract("vector", "Vector", "vector"))),
            Map.entry("math_vector_set", List.of(
                new PinContract("vector", "Vector", "vector"), new PinContract("component", "Component", "component", "string", "x"),
                new PinContract("value", "Value", 0D))),
            Map.entry("math_vector_length", List.of(new PinContract("vector", "Vector", "vector"))),
            Map.entry("math_vector_normalize", List.of(new PinContract("vector", "Vector", "vector"))));
        Map<String, List<PinContract>> outputs = Map.ofEntries(
            Map.entry("math_abs", List.of(new PinContract("absolute", "Absolute"))),
            Map.entry("math_floor", List.of(new PinContract("floored", "Floored"))),
            Map.entry("math_ceil", List.of(new PinContract("ceiling", "Ceiling"))),
            Map.entry("math_sqrt", List.of(new PinContract("sqrt", "Square Root"))),
            Map.entry("math_cbrt", List.of(new PinContract("cbrt", "Cube Root"))),
            Map.entry("math_signum", List.of(new PinContract("sign", "Sign"))),
            Map.entry("math_to_radians", List.of(new PinContract("radians", "Radians"))),
            Map.entry("math_to_degrees", List.of(new PinContract("degrees", "Degrees"))),
            Map.entry("math_random_chance", List.of(new PinContract("success", "Success", "boolean"))),
            Map.entry("math_random_choice", List.of(new PinContract("chosen_item", "Chosen Item", "any"))),
            Map.entry("math_random_choice_weighted", List.of(new PinContract("chosen_item", "Chosen Item", "any"))),
            Map.entry("math_vector_create", List.of(new PinContract("vector", "Vector", "vector"))),
            Map.entry("math_vector_create_int", List.of(new PinContract("vector", "Vector", "vector"))),
            Map.entry("math_vector_split", List.of(
                new PinContract("x", "X"), new PinContract("y", "Y"), new PinContract("z", "Z"),
                new PinContract("block_x", "Block X"), new PinContract("block_y", "Block Y"), new PinContract("block_z", "Block Z"))),
            Map.entry("math_vector_set", List.of(new PinContract("output_vector", "Vector", "vector", "vector", null))),
            Map.entry("math_vector_length", List.of(new PinContract("length", "Length"))),
            Map.entry("math_vector_normalize", List.of(new PinContract("normalized_vector", "Normalized Vector", "vector"))));
        Map<String, Integer> schemaVersions = Map.ofEntries(
            Map.entry("math_abs", 1), Map.entry("math_floor", 1), Map.entry("math_ceil", 1), Map.entry("math_sqrt", 1),
            Map.entry("math_cbrt", 1), Map.entry("math_signum", 1), Map.entry("math_to_radians", 1), Map.entry("math_to_degrees", 1),
            Map.entry("math_random_chance", 2), Map.entry("math_random_choice", 2), Map.entry("math_random_choice_weighted", 2),
            Map.entry("math_vector_create", 2), Map.entry("math_vector_create_int", 2), Map.entry("math_vector_split", 2),
            Map.entry("math_vector_set", 2), Map.entry("math_vector_length", 2), Map.entry("math_vector_normalize", 2));
        Map<String, String> categories = Map.ofEntries(
            Map.entry("math_abs", "DATA"), Map.entry("math_floor", "DATA"), Map.entry("math_ceil", "DATA"),
            Map.entry("math_sqrt", "DATA"), Map.entry("math_cbrt", "DATA"), Map.entry("math_signum", "DATA"),
            Map.entry("math_to_radians", "DATA"), Map.entry("math_to_degrees", "DATA"), Map.entry("math_random_chance", "LOGIC"),
            Map.entry("math_random_choice", "UTILITY"), Map.entry("math_random_choice_weighted", "UTILITY"),
            Map.entry("math_vector_create", "DATA"), Map.entry("math_vector_create_int", "DATA"), Map.entry("math_vector_split", "DATA"),
            Map.entry("math_vector_set", "DATA"), Map.entry("math_vector_length", "DATA"), Map.entry("math_vector_normalize", "DATA"));
        Map<String, List<String>> migrationPins = Map.ofEntries(
            Map.entry("math_random_chance", List.of("input:chance_percent->chance_percent", "output:success->success")),
            Map.entry("math_random_choice", List.of("input:items_list->items_list", "output:chosen_item->chosen_item")),
            Map.entry("math_random_choice_weighted", List.of(
                "input:items_list->items_list", "input:weights_list->weights_list", "output:chosen_item->chosen_item")),
            Map.entry("math_vector_create", List.of("input:x->x", "input:y->y", "input:z->z", "output:vector->vector")),
            Map.entry("math_vector_create_int", List.of("input:x->x", "input:y->y", "input:z->z", "output:vector->vector")),
            Map.entry("math_vector_split", List.of(
                "input:vector->vector", "output:x->x", "output:y->y", "output:z->z", "output:block_x->block_x",
                "output:block_y->block_y", "output:block_z->block_z")),
            Map.entry("math_vector_set", List.of(
                "input:vector->vector", "input:component->component", "input:value->value", "output:vector->output_vector")),
            Map.entry("math_vector_length", List.of("input:vector->vector", "output:length->length")),
            Map.entry("math_vector_normalize", List.of("input:vector->vector", "output:normalized_vector->normalized_vector")));

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
            if (definition.getId().startsWith("math_random_")) {
                assertTrue(description.contains("random"));
            } else if (definition.getId().startsWith("math_vector_")) {
                assertTrue(description.contains("vector"));
            }
        }

        String sqrtDescription = rawById.get("math_sqrt").get("description").getAsString();
        assertTrue(sqrtDescription.contains("negative"));
        assertTrue(sqrtDescription.contains("fail"));
        assertTrue(sqrtDescription.contains("finite"));
        assertFalse(sqrtDescription.contains("NaN"));
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

    private static void assertPins(JsonArray actual, PinContract expected, boolean input) {
        assertPins(actual, List.of(expected), input);
    }

    private static void assertPins(JsonArray actual, List<PinContract> expected, boolean input) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            PinContract contract = expected.get(index);
            JsonObject pin = actual.get(index).getAsJsonObject();
            assertEquals(contract.id(), pin.get("id").getAsString());
            assertEquals(contract.displayName(), pin.get("displayName").getAsString());
            assertEquals(contract.name(), pin.get("name").getAsString());
            assertEquals("DATA", pin.get("pinType").getAsString());
            assertEquals(contract.dataType(), pin.get("dataType").getAsString());
            assertTrue(pin.has("description"));
            assertFalse(pin.get("description").getAsString().isBlank());
            if (input && contract.defaultValue() != null) {
                if (contract.defaultValue() instanceof Number number) {
                    assertEquals(number.doubleValue(), pin.get("defaultValue").getAsDouble());
                } else {
                    assertEquals(contract.defaultValue().toString(), pin.get("defaultValue").getAsString());
                }
            } else {
                assertFalse(pin.has("defaultValue"));
            }
        }
    }

    private record PinContract(String id, String displayName, String name, String dataType, Object defaultValue) {
        private PinContract(String id, String displayName) {
            this(id, displayName, id, "number", null);
        }

        private PinContract(String id, String displayName, double defaultValue) {
            this(id, displayName, id, "number", defaultValue);
        }

        private PinContract(String id, String displayName, String dataType) {
            this(id, displayName, id, dataType, null);
        }
    }
}
