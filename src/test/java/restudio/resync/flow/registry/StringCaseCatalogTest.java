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

class StringCaseCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "string_case.json");
    private static final List<String> IDS = List.of(
        "string_slugify", "string_camel_case", "string_pascal_case", "string_snake_case",
        "string_kebab_case", "string_template", "string_capitalize");

    @Test
    void stringCaseCatalogPublishesExactRuntimeContracts() throws Exception {
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

        Map<String, String> operations = Map.ofEntries(
            Map.entry("string_slugify", "slugify"),
            Map.entry("string_camel_case", "camel_case"),
            Map.entry("string_pascal_case", "pascal_case"),
            Map.entry("string_snake_case", "snake_case"),
            Map.entry("string_kebab_case", "kebab_case"),
            Map.entry("string_template", "template"),
            Map.entry("string_capitalize", "capitalize"));
        Map<String, String> legacyIds = Map.ofEntries(
            Map.entry("string_slugify", "string.slugify"),
            Map.entry("string_camel_case", "string.camel_case"),
            Map.entry("string_pascal_case", "string.pascal_case"),
            Map.entry("string_snake_case", "string.snake_case"),
            Map.entry("string_kebab_case", "string.kebab_case"),
            Map.entry("string_template", "string.template"),
            Map.entry("string_capitalize", "string.capitalize"));
        Map<String, PinContract> inputs = Map.ofEntries(
            Map.entry("string_slugify", new PinContract("text", "Text", "string")),
            Map.entry("string_camel_case", new PinContract("text", "Text", "string")),
            Map.entry("string_pascal_case", new PinContract("text", "Text", "string")),
            Map.entry("string_snake_case", new PinContract("text", "Text", "string")),
            Map.entry("string_kebab_case", new PinContract("text", "Text", "string")),
            Map.entry("string_template", new PinContract("template", "Template", "string")),
            Map.entry("string_capitalize", new PinContract("value", "Value", "string")));
        Map<String, PinContract> outputs = Map.ofEntries(
            Map.entry("string_slugify", new PinContract("slug", "Slug", "string")),
            Map.entry("string_camel_case", new PinContract("camel_case", "Camel Case", "string")),
            Map.entry("string_pascal_case", new PinContract("pascal_case", "Pascal Case", "string")),
            Map.entry("string_snake_case", new PinContract("snake_case", "Snake Case", "string")),
            Map.entry("string_kebab_case", new PinContract("kebab_case", "Kebab Case", "string")),
            Map.entry("string_template", new PinContract("result", "Result", "string")),
            Map.entry("string_capitalize", new PinContract("result", "Result", "string")));

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
            assertEquals(List.of(legacyIds.get(definition.getId())), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertFalse(raw.has("migrationMapping"));
            assertPins(raw.getAsJsonArray("inputs"), List.of(inputs.get(definition.getId())));
            assertPins(raw.getAsJsonArray("outputs"), List.of(outputs.get(definition.getId())));
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertTrue(definition.getPinMigrationMappings().isEmpty());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));

            assertDescriptionContract(raw);
        }

        assertTrue(rawById.get("string_slugify").get("description").getAsString().contains("ASCII letters"));
        assertTrue(rawById.get("string_slugify").get("description").getAsString().contains("hyphens"));
        for (String id : List.of("string_camel_case", "string_pascal_case", "string_snake_case", "string_kebab_case")) {
            String description = rawById.get(id).get("description").getAsString();
            assertTrue(description.contains("locale-independent"), id);
            assertTrue(description.contains("punctuation"), id);
            assertTrue(description.contains("separator"), id);
        }
        String templateDescription = rawById.get("string_template").get("description").getAsString();
        assertTrue(templateDescription.contains("does not interpolate variables"));
        String capitalizeDescription = rawById.get("string_capitalize").get("description").getAsString();
        assertTrue(capitalizeDescription.contains("whitespace"));
        assertTrue(capitalizeDescription.contains("single spaces"));
        assertTrue(capitalizeDescription.contains("remaining characters are lowercase"));
    }

    private static void assertDescriptionContract(JsonObject node) {
        assertTrue(node.get("description").getAsString().contains("65536"), node.get("id").getAsString());
        assertTrue(node.get("description").getAsString().contains("fail before output"), node.get("id").getAsString());
        for (String direction : List.of("inputs", "outputs")) {
            for (JsonElement element : node.getAsJsonArray(direction)) {
                JsonObject pin = element.getAsJsonObject();
                assertTrue(pin.has("id"));
                assertTrue(pin.has("displayName"));
                assertEquals(pin.get("id").getAsString(), pin.get("name").getAsString());
                assertEquals("DATA", pin.get("pinType").getAsString());
                assertTrue(pin.has("description"));
                assertFalse(pin.get("description").getAsString().isBlank());
                assertFalse(pin.has("defaultValue"));
                assertFalse("FLOW".equalsIgnoreCase(pin.get("pinType").getAsString()));
                assertTrue(pin.get("description").getAsString().contains("65536"), node.get("id").getAsString());
                assertTrue(pin.get("description").getAsString().contains("fail before output"), node.get("id").getAsString());
            }
        }
        JsonObject input = node.getAsJsonArray("inputs").get(0).getAsJsonObject();
        assertEquals(0, input.getAsJsonObject("constraints").get("min").getAsInt());
        assertEquals(65536, input.getAsJsonObject("constraints").get("max").getAsInt());
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
        }
    }

    private record PinContract(String id, String displayName, String dataType) {
    }
}
