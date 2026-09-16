package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.FunctionCatalogHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionCatalogQueryTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "function_catalog.json");
    private static final List<String> IDS = List.of(
        "function_list", "function_find", "function_exists", "function_index", "function_at_index", "function_filter");
    private static final Map<String, String> OPERATIONS = Map.of(
        "function_list", "list",
        "function_find", "find",
        "function_exists", "exists",
        "function_index", "index",
        "function_at_index", "at_index",
        "function_filter", "filter");
    private static final Map<String, String> LEGACY_IDS = Map.of(
        "function_list", "function.list",
        "function_find", "function.find",
        "function_exists", "function.exists",
        "function_index", "function.index",
        "function_at_index", "function.at_index",
        "function_filter", "function.filter");

    @Test
    void functionCatalogQueriesPreserveLoaderValidatorAndOutputParity() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new FunctionCatalogHandler(null).registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream()
            .noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        OptionCatalogRegistry catalogs = new OptionCatalogRegistry();
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:function";
            }

            @Override
            public String runtimeDataDomain() {
                return "";
            }

            @Override
            public String revision() {
                return "test";
            }

            @Override
            public List<String> values() {
                return List.of();
            }
        });
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, catalogs, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("function-catalog", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("function-catalog", raw.get("handlerCapability").getAsString());
            assertEquals("FunctionCatalogHandler", raw.get("handler").getAsString());
            assertEquals("FUNCTION", raw.get("category").getAsString());
            assertEquals(2, raw.get("schemaVersion").getAsInt());
            assertEquals("PURE", raw.get("kind").getAsString());
            assertEquals(OPERATIONS.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(List.of(LEGACY_IDS.get(definition.getId())), raw.getAsJsonArray("legacyIds").asList()
                .stream().map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            assertFalse(raw.has("migrationMapping"));
            assertTrue(handlers.hasOperation("FunctionCatalogHandler", OPERATIONS.get(definition.getId())));
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertTrue(definition.getPinMigrationMappings().isEmpty());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertPins(raw.getAsJsonArray("inputs"), definition.getInputs());
            assertPins(raw.getAsJsonArray("outputs"), definition.getOutputs());
            assertTrue(raw.get("description").getAsString().contains("String"));
            assertFalse(raw.get("description").getAsString().isBlank());
        }

        assertEquals("list", rawById.get("function_list").getAsJsonObject("handlerConfig").get("operation").getAsString());
        assertTrue(rawById.get("function_find").get("description").getAsString().contains("exact case"));
        assertTrue(rawById.get("function_find").get("description").getAsString().contains("FUNCTION_NAME_AMBIGUOUS"));
        assertTrue(rawById.get("function_find").get("description").getAsString().contains("FUNCTION_NOT_FOUND"));
        assertTrue(rawById.get("function_exists").get("description").getAsString().contains("false"));
        assertTrue(rawById.get("function_index").get("description").getAsString().contains("without validation or mutation"));
        assertTrue(rawById.get("function_index").get("description").getAsString().contains("omitted or null"));
        assertTrue(rawById.get("function_index").get("description").getAsString().contains("explicit empty"));
        assertTrue(rawById.get("function_at_index").get("description").getAsString().contains("whole integer"));
        assertTrue(rawById.get("function_at_index").get("description").getAsString().contains("FUNCTION_INDEX_INVALID"));
        assertTrue(rawById.get("function_at_index").get("description").getAsString().contains("FUNCTION_INDEX_OUT_OF_RANGE"));
        assertTrue(rawById.get("function_filter").get("description").getAsString().contains("blank query"));
        assertEquals(2, rawById.get("function_index").getAsJsonArray("inputs").size());
        assertTrue(rawById.get("function_index").getAsJsonArray("inputs").get(0).getAsJsonObject().get("optional").getAsBoolean());
        assertTrue(rawById.get("function_at_index").getAsJsonArray("inputs").get(0).getAsJsonObject().get("optional").getAsBoolean());
        assertTrue(rawById.get("function_at_index").getAsJsonArray("inputs").get(0).getAsJsonObject().get("description").getAsString().contains("explicit empty"));
        assertTrue(rawById.get("function_at_index").getAsJsonArray("inputs").get(1).getAsJsonObject().get("description").getAsString().contains("FUNCTION_INDEX_INVALID"));
        assertTrue(rawById.get("function_at_index").getAsJsonArray("inputs").get(1).getAsJsonObject().get("description").getAsString().contains("FUNCTION_INDEX_OUT_OF_RANGE"));
        assertEquals("SEARCHABLE_LIST", rawById.get("function_exists").getAsJsonArray("inputs").get(0).getAsJsonObject().get("widget").getAsString());
        assertEquals("server:resync:function", rawById.get("function_exists").getAsJsonArray("inputs").get(0).getAsJsonObject().get("optionsSource").getAsString());
        assertFalse(source.asList().stream().anyMatch(element -> "function_describe".equals(element.getAsJsonObject().get("id").getAsString())));
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static void assertPins(JsonArray rawPins, List<NodeDefinition.PinDefinition> definitions) {
        assertEquals(rawPins.size(), definitions.size());
        for (int index = 0; index < rawPins.size(); index++) {
            JsonObject raw = rawPins.get(index).getAsJsonObject();
            NodeDefinition.PinDefinition definition = definitions.get(index);
            assertEquals(raw.get("id").getAsString(), definition.getId().value());
            assertEquals(raw.get("name").getAsString(), definition.getName());
            assertEquals(raw.get("displayName").getAsString(), definition.getDisplayName());
            assertEquals(raw.get("pinType").getAsString(), definition.getType().name());
            assertEquals(raw.get("dataType").getAsString(), definition.getTypeRef().toString());
            assertEquals(raw.has("widget") ? NodeDefinition.WidgetType.valueOf(raw.get("widget").getAsString()) : NodeDefinition.WidgetType.AUTO,
                definition.getWidgetType());
            assertEquals(raw.has("options") ? raw.getAsJsonArray("options").asList().stream().map(JsonElement::getAsString).toList() : List.of(),
                definition.getOptions());
            assertEquals(raw.has("optionsSource") ? raw.get("optionsSource").getAsString() : null, definition.getOptionsSource());
            assertEquals(raw.has("optional") && raw.get("optional").getAsBoolean(), definition.isOptional());
            assertFalse(raw.has("defaultValue"));
            assertNull(definition.getDefaultValue());
            assertFalse(raw.get("pinType").getAsString().equalsIgnoreCase("FLOW"));
            assertTrue(raw.has("description"));
            assertFalse(raw.get("description").getAsString().isBlank());
        }
    }
}
