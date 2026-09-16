package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.GenericStringHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringPaddingCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "string_padding.json");
    private static final List<String> IDS = List.of("string_pad_left", "string_pad_right", "string_truncate");

    @Test
    void stringPaddingCatalogPublishesExactRuntimeContracts() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new GenericStringHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        Map<String, String> operations = Map.of(
            "string_pad_left", "pad_left",
            "string_pad_right", "pad_right",
            "string_truncate", "truncate");
        Map<String, List<String>> legacyIds = Map.of(
            "string_pad_left", List.of("string.pad_left"),
            "string_pad_right", List.of("string.pad_right"),
            "string_truncate", List.of("string.truncate"));
        Map<String, List<PinContract>> inputs = Map.of(
            "string_pad_left", List.of(new PinContract("text", "Text", "string"), new PinContract("length", "Length", "number"),
                new PinContract("pad_char", "Pad Character", "string")),
            "string_pad_right", List.of(new PinContract("text", "Text", "string"), new PinContract("length", "Length", "number"),
                new PinContract("pad_char", "Pad Character", "string")),
            "string_truncate", List.of(new PinContract("text", "Text", "string"), new PinContract("length", "Length", "number"),
                new PinContract("add_ellipsis", "Add Ellipsis", "boolean")));
        Map<String, PinContract> outputs = Map.of(
            "string_pad_left", new PinContract("padded", "Padded", "string"),
            "string_pad_right", new PinContract("padded", "Padded", "string"),
            "string_truncate", new PinContract("truncated", "Truncated", "string"));

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("string", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("generic-string", raw.get("handlerCapability").getAsString());
            assertEquals("GenericStringHandler", raw.get("handler").getAsString());
            assertEquals(1, raw.get("schemaVersion").getAsInt());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertFalse(raw.has("migrationMapping"));
            assertPins(raw.getAsJsonArray("inputs"), inputs.get(definition.getId()));
            assertPins(raw.getAsJsonArray("outputs"), List.of(outputs.get(definition.getId())));
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertTrue(definition.getPinMigrationMappings().isEmpty());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
        }

        for (String id : IDS) {
            JsonObject length = pin(rawById.get(id), "inputs", "length");
            assertEquals(0, length.getAsJsonObject("constraints").get("min").getAsInt());
            assertEquals(65536, length.getAsJsonObject("constraints").get("max").getAsInt());
            assertTrue(length.get("description").getAsString().contains("zero leaves the text unchanged"));
            assertTrue(length.get("description").getAsString().contains("values outside 0 through 65536 fail before output"));
            assertTrue(rawById.get(id).get("description").getAsString().contains("Values outside 0 through 65536 fail before output"));
        }

        assertTrue(rawById.get("string_pad_left").get("description").getAsString().contains("first character"));
        assertTrue(rawById.get("string_pad_left").get("description").getAsString().contains("space"));
        assertTrue(pin(rawById.get("string_pad_left"), "inputs", "pad_char").get("description").getAsString().contains("first character"));
        assertTrue(rawById.get("string_truncate").get("description").getAsString().contains("greater than three"));
        assertTrue(pin(rawById.get("string_truncate"), "inputs", "add_ellipsis").get("description").getAsString().contains("false"));
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static void assertPins(JsonArray actual, List<PinContract> expected) {
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
        }
    }

    private static JsonObject pin(JsonObject node, String direction, String id) {
        for (JsonElement element : node.getAsJsonArray(direction)) {
            JsonObject pin = element.getAsJsonObject();
            if (id.equals(pin.get("id").getAsString())) {
                return pin;
            }
        }
        throw new AssertionError("Missing pin " + id + " in " + node.get("id").getAsString());
    }

    private record PinContract(String id, String displayName, String dataType) {
    }
}
