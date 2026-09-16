package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.GenericListHandler;

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

class ListBasicCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "list_basic.json");
    private static final List<String> IDS = List.of(
        "list_create", "list_add", "list_remove", "list_remove_at", "list_clear",
        "list_get", "list_set", "list_size", "list_is_empty", "list_contains",
        "list_add_at", "list_insert", "list_of");

    @Test
    void basicListCatalogPublishesExactRuntimeContracts() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new GenericListHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        Map<String, String> operations = Map.ofEntries(
            Map.entry("list_create", "create"),
            Map.entry("list_add", "add"),
            Map.entry("list_remove", "remove"),
            Map.entry("list_remove_at", "remove_at"),
            Map.entry("list_clear", "clear"),
            Map.entry("list_get", "get"),
            Map.entry("list_set", "set"),
            Map.entry("list_size", "size"),
            Map.entry("list_is_empty", "is_empty"),
            Map.entry("list_contains", "contains"),
            Map.entry("list_add_at", "add_at"),
            Map.entry("list_insert", "insert"),
            Map.entry("list_of", "of"));
        Map<String, String> legacyIds = Map.ofEntries(
            Map.entry("list_create", "list.create"),
            Map.entry("list_add", "list.add"),
            Map.entry("list_remove", "list.remove"),
            Map.entry("list_remove_at", "list.remove_at"),
            Map.entry("list_clear", "list.clear"),
            Map.entry("list_get", "list.get"),
            Map.entry("list_set", "list.set"),
            Map.entry("list_size", "list.size"),
            Map.entry("list_is_empty", "list.is_empty"),
            Map.entry("list_contains", "list.contains"),
            Map.entry("list_add_at", "list.add_at"),
            Map.entry("list_insert", "list.insert"),
            Map.entry("list_of", "list.of"));
        Map<String, Integer> schemaVersions = Map.ofEntries(
            Map.entry("list_add", 2),
            Map.entry("list_remove", 2),
            Map.entry("list_remove_at", 2),
            Map.entry("list_clear", 2),
            Map.entry("list_set", 2),
            Map.entry("list_create", 1),
            Map.entry("list_get", 1),
            Map.entry("list_size", 1),
            Map.entry("list_is_empty", 1),
            Map.entry("list_contains", 1),
            Map.entry("list_add_at", 2),
            Map.entry("list_insert", 2),
            Map.entry("list_of", 2));
        Map<String, List<String>> migrationPins = Map.of(
            "list_add", List.of("input:list->list", "input:value->value", "output:list->output_list"),
            "list_remove", List.of("input:list->list", "input:value->value", "output:list->output_list"),
            "list_remove_at", List.of("input:list->list", "input:index->index", "output:list->output_list"),
            "list_clear", List.of("input:list->list", "output:list->output_list"),
            "list_set", List.of("input:list->list", "input:index->index", "input:value->value", "output:list->output_list"),
            "list_add_at", List.of("input:list->list", "input:index->index", "input:value->value", "output:list->output_list"));
        Map<String, List<PinContract>> inputs = Map.ofEntries(
            Map.entry("list_create", List.of()),
            Map.entry("list_add", List.of(
                new PinContract("list", "List", "list<type:t>", null),
                new PinContract("value", "Value", "type:t", null))),
            Map.entry("list_remove", List.of(
                new PinContract("list", "List", "list<type:t>", null),
                new PinContract("value", "Value", "type:t", null))),
            Map.entry("list_remove_at", List.of(
                new PinContract("list", "List", "list<type:t>", null),
                new PinContract("index", "Index", "number", 0))),
            Map.entry("list_clear", List.of(new PinContract("list", "List", "list<type:t>", null))),
            Map.entry("list_get", List.of(
                new PinContract("list", "List", "list<type:t>", null),
                new PinContract("index", "Index", "number", 0))),
            Map.entry("list_set", List.of(
                new PinContract("list", "List", "list<type:t>", null),
                new PinContract("index", "Index", "number", 0),
                new PinContract("value", "Value", "type:t", null))),
            Map.entry("list_size", List.of(new PinContract("list", "List", "list<any>", null))),
            Map.entry("list_is_empty", List.of(new PinContract("list", "List", "list<any>", null))),
            Map.entry("list_contains", List.of(
                new PinContract("list", "List", "list<type:t>", null),
                new PinContract("value", "Value", "type:t", null))),
            Map.entry("list_add_at", List.of(
                new PinContract("list", "List", "list<type:t>", null),
                new PinContract("index", "Index", "number", 0),
                new PinContract("value", "Value", "type:t", null))),
            Map.entry("list_insert", List.of(
                new PinContract("list", "List", "list<type:t>", null),
                new PinContract("index", "Index", "number", 0),
                new PinContract("item", "Item", "type:t", null))),
            Map.entry("list_of", List.of(new PinContract("items", "Items", "list<any>", null))));
        Map<String, List<PinContract>> outputs = Map.ofEntries(
            Map.entry("list_create", List.of(new PinContract("list", "List", "list<any>", null))),
            Map.entry("list_add", List.of(new PinContract("output_list", "List", "list", "list<type:t>", null))),
            Map.entry("list_remove", List.of(new PinContract("output_list", "List", "list", "list<type:t>", null))),
            Map.entry("list_remove_at", List.of(new PinContract("output_list", "List", "list", "list<type:t>", null))),
            Map.entry("list_clear", List.of(new PinContract("output_list", "List", "list", "list<type:t>", null))),
            Map.entry("list_get", List.of(new PinContract("value", "Value", "type:t", null))),
            Map.entry("list_set", List.of(new PinContract("output_list", "List", "list", "list<type:t>", null))),
            Map.entry("list_size", List.of(new PinContract("size", "Size", "number", null))),
            Map.entry("list_is_empty", List.of(new PinContract("empty", "Empty", "boolean", null))),
            Map.entry("list_contains", List.of(new PinContract("contains", "Contains", "boolean", null))),
            Map.entry("list_add_at", List.of(new PinContract("output_list", "List", "list", "list<type:t>", null))),
            Map.entry("list_insert", List.of(new PinContract("output_list", "List", "list", "list<type:t>", null))),
            Map.entry("list_of", List.of(new PinContract("list", "List", "list<any>", null))));

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals("core", raw.get("domain").getAsString());
            assertEquals("list", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("generic-list", raw.get("handlerCapability").getAsString());
            assertEquals("typed-list", raw.get("selectorIntent").getAsString());
            assertEquals("GenericListHandler", raw.get("handler").getAsString());
            assertEquals(schemaVersions.get(definition.getId()), raw.get("schemaVersion").getAsInt());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(List.of(legacyIds.get(definition.getId())), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertFalse(raw.has("migration"));
            if (migrationPins.containsKey(definition.getId())) {
                JsonObject migration = raw.getAsJsonObject("migrationMapping");
                assertEquals(1, migration.get("sourceSchemaVersion").getAsInt());
                assertEquals(2, migration.get("targetSchemaVersion").getAsInt());
                assertTrue(migration.get("complete").getAsBoolean());
                assertEquals(migrationPins.get(definition.getId()), migration.getAsJsonArray("pins").asList().stream()
                    .map(pin -> {
                        JsonObject value = pin.getAsJsonObject();
                        return value.get("direction").getAsString() + ":" + value.get("source").getAsString()
                            + "->" + value.get("target").getAsString();
                    }).toList());
                assertEquals(migrationPins.get(definition.getId()), definition.getPinMigrationMappings().stream()
                    .map(mapping -> mapping.direction().name().toLowerCase(Locale.ROOT) + ":"
                        + mapping.sourcePinId().value() + "->" + mapping.targetPinId().value()).toList());
            } else {
                assertFalse(raw.has("migrationMapping"));
                assertTrue(definition.getPinMigrationMappings().isEmpty());
            }
            assertPins(raw.getAsJsonArray("inputs"), inputs.get(definition.getId()), true);
            assertPins(raw.getAsJsonArray("outputs"), outputs.get(definition.getId()), false);
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
        }

        assertTrue(rawById.get("list_add").get("description").getAsString().contains("empty list"));
        assertTrue(rawById.get("list_add").get("description").getAsString().contains("null"));
        assertTrue(rawById.get("list_remove_at").get("description").getAsString().contains("index defaults to 0"));
        assertTrue(rawById.get("list_remove_at").get("description").getAsString().contains("invalid index"));
        assertTrue(rawById.get("list_get").get("description").getAsString().contains("returns null"));
        assertTrue(rawById.get("list_set").get("description").getAsString().contains("value defaults to null"));
        assertTrue(rawById.get("list_set").get("description").getAsString().contains("invalid index"));
        assertTrue(rawById.get("list_is_empty").get("description").getAsString().contains("returns true"));
        assertTrue(rawById.get("list_add_at").get("description").getAsString().contains("value defaults to null"));
        assertTrue(rawById.get("list_add_at").get("description").getAsString().contains("invalid index"));
        assertTrue(rawById.get("list_insert").get("description").getAsString().contains("item defaults to null"));
        assertTrue(rawById.get("list_insert").get("description").getAsString().contains("invalid index"));
        assertTrue(rawById.get("list_of").get("description").getAsString().contains("retained in order"));
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
            assertEquals(contract.name(), pin.get("name").getAsString());
            assertEquals("DATA", pin.get("pinType").getAsString());
            assertEquals(contract.dataType(), pin.get("dataType").getAsString());
            assertTrue(pin.has("description"));
            assertFalse(pin.get("description").getAsString().isBlank());
            if (input && contract.defaultValue() != null) {
                assertEquals(contract.defaultValue(), pin.get("defaultValue").getAsInt());
            } else {
                assertFalse(pin.has("defaultValue"));
            }
        }
    }

    private record PinContract(String id, String displayName, String name, String dataType, Integer defaultValue) {
        private PinContract(String id, String displayName, String dataType, Integer defaultValue) {
            this(id, displayName, id, dataType, defaultValue);
        }
    }
}
