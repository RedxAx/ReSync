package restudio.resync.flow.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.WorldActionHandler;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionValidator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLookupNodeCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "world_lookup.json");
    private static final Path SOURCE_PATH = Path.of("ReSyncUpgrade", "src", "main", "resources", "nodes", "migrated", "world_action.json");

    private static final List<NodeContract> CONTRACTS = List.of(
        new NodeContract("get.location", "get_location", "Get Location", "get_location",
            List.of(new Pin("x", "X", "number"), new Pin("y", "Y", "number"), new Pin("z", "Z", "number")),
            List.of(new Pin("location", "Location", "location"))),
        new NodeContract("world.world_get_by_name", "world_get_by_name", "World Get By Name", "world_get_by_name",
            List.of(new Pin("world_name", "World Name", "string")),
            List.of(new Pin("world", "World", "world"))),
        new NodeContract("world.world_get_all", "world_get_all", "World Get All", "world_get_all",
            List.of(), List.of(new Pin("worlds_list", "Worlds", "list<world>")))
    );

    @Test
    void worldLookupCatalogPreservesSourceIdentityPinsAndHandlerContracts() throws Exception {
        JsonArray active = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        JsonArray source = JsonParser.parseString(Files.readString(SOURCE_PATH)).getAsJsonArray();
        Map<String, JsonObject> activeById = byId(active);
        Map<String, JsonObject> sourceById = byId(source);

        assertEquals(CONTRACTS.size(), active.size());
        assertEquals(CONTRACTS.size(), activeById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new WorldActionHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parse(input, ACTIVE_PATH.toString(), NodeDefinitionLoader.CatalogSource.REPLACEMENT);
        }
        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == restudio.resync.flow.registry.NodeDefinitionDiagnostic.Severity.ERROR));

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeContract contract : CONTRACTS) {
            JsonObject node = activeById.get(contract.targetId());
            assertNotNull(node, contract.targetId());
            assertEquals(contract.displayName(), node.get("displayName").getAsString());
            assertEquals("world", node.get("domain").getAsString());
            assertEquals("world_lookup", node.get("family").getAsString());
            assertEquals("active", node.get("lifecycle").getAsString());
            assertEquals("restudio.resync", node.get("owner").getAsString());
            assertEquals("world-action", node.get("handlerCapability").getAsString());
            assertEquals("WorldActionHandler", node.get("handler").getAsString());
            assertEquals(contract.operation(), node.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(List.of(contract.sourceId()), node.getAsJsonArray("legacyIds").asList().stream().map(JsonElement::getAsString).toList());

            assertPins(node.getAsJsonArray("inputs"), contract.inputs());
            assertPins(node.getAsJsonArray("outputs"), contract.outputs());
            assertFalse(hasFlowPin(node));

            JsonObject legacy = sourceById.get(contract.sourceId());
            assertNotNull(legacy, contract.sourceId());
            assertEquals(contract.operation(), legacy.getAsJsonObject("handlerConfig").get("operation").getAsString());

            NodeDefinition definition = definitions.stream().filter(value -> contract.targetId().equals(value.getId())).findFirst().orElse(null);
            assertNotNull(definition, contract.targetId());
            assertTrue(validator.validate(definition).valid(), contract.targetId());
            assertTrue(handlers.hasOperation("WorldActionHandler", contract.operation()), contract.operation());
        }
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> {
            JsonObject definition = element.getAsJsonObject();
            result.put(definition.get("id").getAsString(), definition);
        });
        return result;
    }

    private static void assertPins(JsonArray actual, List<Pin> expected) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            Pin pin = expected.get(index);
            JsonObject actualPin = actual.get(index).getAsJsonObject();
            assertEquals(pin.id(), actualPin.get("id").getAsString());
            assertEquals(pin.displayName(), actualPin.get("displayName").getAsString());
            assertEquals(pin.id(), actualPin.get("name").getAsString());
            assertEquals("DATA", actualPin.get("pinType").getAsString());
            assertEquals(pin.dataType(), actualPin.get("dataType").getAsString());
            assertTrue(actualPin.has("description"));
            assertFalse(actualPin.get("description").getAsString().isBlank());
        }
    }

    private static boolean hasFlowPin(JsonObject node) {
        return hasFlowPin(node, "inputs") || hasFlowPin(node, "outputs");
    }

    private static boolean hasFlowPin(JsonObject node, String direction) {
        for (JsonElement element : node.getAsJsonArray(direction)) {
            if (element.getAsJsonObject().has("pinType") && "FLOW".equals(element.getAsJsonObject().get("pinType").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private record NodeContract(String sourceId, String targetId, String displayName, String operation, List<Pin> inputs, List<Pin> outputs) {
    }

    private record Pin(String id, String displayName, String dataType) {
    }
}
