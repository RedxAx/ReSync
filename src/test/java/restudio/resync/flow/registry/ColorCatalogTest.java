package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.ColorHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "color.json");
    private static final List<String> IDS = List.of(
        "color_from_rgb", "color_from_hex", "color_to_hex", "color_to_rgb", "color_invert",
        "color_brighten", "color_darken", "color_blend", "color_random", "color_distance");

    @Test
    void colorCatalogPublishesExactLoaderAndValidatorMetadata() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new ColorHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        Map<String, String> operations = Map.ofEntries(
            Map.entry("color_from_rgb", "color_from_rgb"),
            Map.entry("color_from_hex", "color_from_hex"),
            Map.entry("color_to_hex", "color_to_hex"),
            Map.entry("color_to_rgb", "color_to_rgb"),
            Map.entry("color_invert", "color_invert"),
            Map.entry("color_brighten", "color_brighten"),
            Map.entry("color_darken", "color_darken"),
            Map.entry("color_blend", "color_blend"),
            Map.entry("color_random", "color_random"),
            Map.entry("color_distance", "color_distance"));
        Map<String, List<String>> legacyIds = Map.ofEntries(
            Map.entry("color_from_rgb", List.of("color.from.rgb")),
            Map.entry("color_from_hex", List.of("color.from.hex")),
            Map.entry("color_to_hex", List.of("color.to.hex")),
            Map.entry("color_to_rgb", List.of("color.to.rgb")),
            Map.entry("color_invert", List.of("color.invert")),
            Map.entry("color_brighten", List.of("color.brighten")),
            Map.entry("color_darken", List.of("color.darken")),
            Map.entry("color_blend", List.of("utility.color_blend")),
            Map.entry("color_random", List.of("utility.color_random")),
            Map.entry("color_distance", List.of("utility.color_distance")));
        Map<String, List<PinContract>> inputs = Map.ofEntries(
            Map.entry("color_from_rgb", List.of(
                new PinContract("red", "Red", "integer", 0, 0, 255, false, null),
                new PinContract("green", "Green", "integer", 0, 0, 255, false, null),
                new PinContract("blue", "Blue", "integer", 0, 0, 255, false, null))),
            Map.entry("color_from_hex", List.of(new PinContract("hex_string", "Hex String", "string", "#FFFFFF", null, null, false, "COLOR"))),
            Map.entry("color_to_hex", List.of(new PinContract("color", "Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_to_rgb", List.of(new PinContract("color", "Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_invert", List.of(new PinContract("color", "Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_brighten", List.of(
                new PinContract("color", "Color", "rgb_color", null, null, null, false, null),
                new PinContract("amount", "Amount", "number", 0.2, 0, 1, false, null))),
            Map.entry("color_darken", List.of(
                new PinContract("color", "Color", "rgb_color", null, null, null, false, null),
                new PinContract("amount", "Amount", "number", 0.2, 0, 1, false, null))),
            Map.entry("color_blend", List.of(
                new PinContract("color1", "Color 1", "rgb_color", null, null, null, false, null),
                new PinContract("color2", "Color 2", "rgb_color", null, null, null, false, null),
                new PinContract("ratio", "Ratio", "number", 0.5, 0, 1, false, null))),
            Map.entry("color_random", List.of()),
            Map.entry("color_distance", List.of(
                new PinContract("color1", "Color 1", "rgb_color", null, null, null, false, null),
                new PinContract("color2", "Color 2", "rgb_color", null, null, null, false, null))));
        Map<String, List<PinContract>> outputs = Map.ofEntries(
            Map.entry("color_from_rgb", List.of(new PinContract("color", "Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_from_hex", List.of(new PinContract("color", "Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_to_hex", List.of(new PinContract("hex_string", "Hex String", "string", null, null, null, false, null))),
            Map.entry("color_to_rgb", List.of(
                new PinContract("red", "Red", "integer", null, null, null, false, null),
                new PinContract("green", "Green", "integer", null, null, null, false, null),
                new PinContract("blue", "Blue", "integer", null, null, null, false, null))),
            Map.entry("color_invert", List.of(new PinContract("inverted_color", "Inverted Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_brighten", List.of(new PinContract("brightened_color", "Brightened Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_darken", List.of(new PinContract("darkened_color", "Darkened Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_blend", List.of(new PinContract("mixed_color", "Mixed Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_random", List.of(new PinContract("color", "Color", "rgb_color", null, null, null, false, null))),
            Map.entry("color_distance", List.of(new PinContract("distance", "Distance", "number", null, null, null, false, null))));

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("color", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals(2, raw.get("schemaVersion").getAsInt());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("generic-color", raw.get("handlerCapability").getAsString());
            assertEquals("ColorHandler", raw.get("handler").getAsString());
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
        }

        assertDescriptionContains(rawById, "color_from_rgb", "clamped", "0 through 255");
        assertDescriptionContains(rawById, "color_from_hex", "3-digit", "6-digit", "invalid", "fails");
        for (String id : List.of("color_to_hex", "color_to_rgb", "color_invert", "color_brighten", "color_darken", "color_blend", "color_distance")) {
            assertDescriptionContains(rawById, id, "Missing or null");
        }
        assertDescriptionContains(rawById, "color_brighten", "finite", "fail", "truncated");
        assertDescriptionContains(rawById, "color_darken", "finite", "fail", "truncated");
        assertDescriptionContains(rawById, "color_blend", "finite", "fail", "interpolat", "truncated");
        assertDescriptionContains(rawById, "color_random", "fresh", "nondeterministic", "random");
        assertDescriptionContains(rawById, "color_distance", "Euclidean");
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
            assertEquals(contract.dataType(), pin.get("dataType").getAsString());
            assertTrue(pin.has("description"));
            assertFalse(pin.get("description").getAsString().isBlank());
            if (contract.defaultValue() == null) {
                assertFalse(pin.has("defaultValue"));
            } else if (contract.defaultValue() instanceof Number number) {
                assertEquals(number.doubleValue(), pin.get("defaultValue").getAsDouble());
            } else {
                assertEquals(contract.defaultValue(), pin.get("defaultValue").getAsString());
            }
            if (contract.min() == null && contract.max() == null) {
                assertFalse(pin.has("constraints"));
            } else {
                assertEquals(contract.min(), pin.getAsJsonObject("constraints").get("min").getAsInt());
                assertEquals(contract.max(), pin.getAsJsonObject("constraints").get("max").getAsInt());
            }
            if (contract.widget() == null) {
                assertFalse(pin.has("widget"));
            } else {
                assertEquals(contract.widget(), pin.get("widget").getAsString());
            }
            assertFalse(pin.has("migrationMapping"));
            if (!input) {
                assertFalse(pin.has("defaultValue"));
            }
        }
    }

    private static void assertDescriptionContains(Map<String, JsonObject> definitions, String id, String... fragments) {
        String description = definitions.get(id).get("description").getAsString();
        for (String fragment : fragments) {
            assertTrue(description.toLowerCase().contains(fragment.toLowerCase()), id + ": " + description);
        }
    }

    private record PinContract(String id, String displayName, String dataType, Object defaultValue, Integer min, Integer max,
                               boolean optional, String widget) {
    }
}
