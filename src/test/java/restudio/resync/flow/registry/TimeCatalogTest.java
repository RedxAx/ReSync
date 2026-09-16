package restudio.resync.flow.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.TimeHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeCatalogTest {
    private static final Path ACTIVE_PATH = Path.of("src", "main", "resources", "nodes", "time.json");
    private static final List<String> IDS = List.of("time_format", "time_parse", "time_add", "time_diff", "time_to_ticks",
        "time_current", "time_get_current_ticks", "time_get_current_time");

    @Test
    void timeCatalogPublishesExactRuntimeContracts() throws Exception {
        JsonArray source = JsonParser.parseString(Files.readString(ACTIVE_PATH)).getAsJsonArray();
        Map<String, JsonObject> rawById = byId(source);
        assertEquals(IDS, source.asList().stream().map(element -> element.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(IDS.size(), rawById.size());

        HandlerRegistry handlers = new HandlerRegistry();
        new TimeHandler().registerTo(handlers);
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(ACTIVE_PATH)) {
            definitions = loader.parseReplacement(input, ACTIVE_PATH.toString());
        }

        assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            loader.getDiagnostics().toString());
        assertEquals(IDS, definitions.stream().map(NodeDefinition::getId).toList());

        Map<String, String> operations = Map.of(
            "time_format", "time_format",
            "time_parse", "time_parse",
            "time_add", "time_add",
            "time_diff", "time_diff",
            "time_to_ticks", "time_to_ticks",
            "time_current", "time_current",
            "time_get_current_ticks", "time_get_current_ticks",
            "time_get_current_time", "time_get_current_time");
        Map<String, List<String>> legacyIds = Map.of(
            "time_format", List.of("time.format", "misc.time_format"),
            "time_parse", List.of("time.parse", "misc.time_parse"),
            "time_add", List.of("time.add", "misc.time_add"),
            "time_diff", List.of("time.diff", "misc.time_diff"),
            "time_to_ticks", List.of("time.time_to_ticks"),
            "time_current", List.of("time.time_current"),
            "time_get_current_ticks", List.of("get.current.ticks", "get_current_ticks"),
            "time_get_current_time", List.of("get.current.time", "get_current_time"));
        Map<String, Integer> schemaVersions = Map.of(
            "time_format", 3,
            "time_parse", 3,
            "time_add", 4,
            "time_diff", 4,
            "time_to_ticks", 2,
            "time_current", 2,
            "time_get_current_ticks", 2,
            "time_get_current_time", 2);
        Map<String, String> clockDomains = Map.of(
            "time_format", "wall_time",
            "time_parse", "wall_time",
            "time_add", "wall_time",
            "time_diff", "wall_time",
            "time_to_ticks", "monotonic_elapsed",
            "time_current", "wall_time",
            "time_get_current_ticks", "server_ticks",
            "time_get_current_time", "world_day_time");
        Map<String, String> domains = Map.of(
            "time_format", "core",
            "time_parse", "core",
            "time_add", "core",
            "time_diff", "core",
            "time_to_ticks", "core",
            "time_current", "core",
            "time_get_current_ticks", "core",
            "time_get_current_time", "world");
        Map<String, List<String>> descriptionTerms = Map.of(
            "time_format", List.of("valid=false", "error"),
            "time_parse", List.of("valid=false", "error"),
            "time_add", List.of("valid=false", "error"),
            "time_diff", List.of("valid=false", "error"),
            "time_to_ticks", List.of("valid=false", "error"),
            "time_current", List.of("runtime clock"),
            "time_get_current_ticks", List.of("monotonic tick counter"),
            "time_get_current_time", List.of("valid=false", "error"));

        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, true);
        for (NodeDefinition definition : definitions) {
            JsonObject raw = rawById.get(definition.getId());
            assertEquals(domains.get(definition.getId()), raw.get("domain").getAsString());
            assertEquals("time", raw.get("family").getAsString());
            assertEquals("active", raw.get("lifecycle").getAsString());
            assertEquals("restudio.resync", raw.get("owner").getAsString());
            assertEquals("time", raw.get("handlerCapability").getAsString());
            assertEquals("TimeHandler", raw.get("handler").getAsString());
            assertEquals(schemaVersions.get(definition.getId()), raw.get("schemaVersion").getAsInt());
            assertEquals(clockDomains.get(definition.getId()), raw.get("clockDomain").getAsString());
            assertEquals(operations.get(definition.getId()), raw.getAsJsonObject("handlerConfig").get("operation").getAsString());
            assertEquals(legacyIds.get(definition.getId()), raw.getAsJsonArray("legacyIds").asList().stream()
                .map(JsonElement::getAsString).toList());
            assertTrue(validator.validate(definition).valid(), validator.validate(definition).errors().toString());
            assertFalse(definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            assertFalse(definition.getOutputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW));
            for (String term : descriptionTerms.get(definition.getId())) {
                assertTrue(raw.get("description").getAsString().contains(term), definition.getId());
            }
            assertPinsAreExplicit(raw);
        }

        assertPins(rawById.get("time_format"), List.of("time", "format", "time_zone", "locale"),
            List.of("instant", "string", "string", "string"), List.of("string", "valid", "error"),
            List.of("string", "boolean", "string"));
        assertEquals("uuuu-MM-dd HH:mm:ss", pin(rawById.get("time_format"), "inputs", "format").get("defaultValue").getAsString());
        assertEquals("UTC", pin(rawById.get("time_format"), "inputs", "time_zone").get("defaultValue").getAsString());
        assertEquals("", pin(rawById.get("time_format"), "inputs", "locale").get("defaultValue").getAsString());
        assertTrue(rawById.get("time_format").get("description").getAsString().contains("strict"));
        assertTrue(rawById.get("time_format").get("description").getAsString().contains("IANA"));
        assertTrue(rawById.get("time_format").get("description").getAsString().contains("BCP 47"));

        assertPins(rawById.get("time_parse"), List.of("string", "format", "time_zone", "locale"),
            List.of("string", "string", "string", "string"), List.of("time", "valid", "error"),
            List.of("instant", "boolean", "string"));
        assertEquals("uuuu-MM-dd HH:mm:ss", pin(rawById.get("time_parse"), "inputs", "format").get("defaultValue").getAsString());
        assertEquals("UTC", pin(rawById.get("time_parse"), "inputs", "time_zone").get("defaultValue").getAsString());
        assertEquals("", pin(rawById.get("time_parse"), "inputs", "locale").get("defaultValue").getAsString());
        assertTrue(rawById.get("time_parse").get("description").getAsString().contains("incomplete"));
        assertTrue(rawById.get("time_parse").get("description").getAsString().contains("calendar"));

        assertPins(rawById.get("time_add"), List.of("time", "amount", "unit", "time_zone"),
            List.of("instant", "number", "string", "string"), List.of("output_time", "valid", "error"),
            List.of("instant", "boolean", "string"));
        assertEquals(0, pin(rawById.get("time_add"), "inputs", "amount").get("defaultValue").getAsInt());
        assertEquals("seconds", pin(rawById.get("time_add"), "inputs", "unit").get("defaultValue").getAsString());
        assertEquals("UTC", pin(rawById.get("time_add"), "inputs", "time_zone").get("defaultValue").getAsString());
        assertEquals("Time", pin(rawById.get("time_add"), "outputs", "output_time").get("displayName").getAsString());
        assertEquals("time", pin(rawById.get("time_add"), "outputs", "output_time").get("name").getAsString());
        assertTrue(rawById.get("time_add").get("description").getAsString().contains("whole finite"));
        assertTrue(rawById.get("time_add").get("description").getAsString().contains("calendar arithmetic"));
        assertTrue(rawById.get("time_add").get("description").getAsString().contains("overflow"));
        assertEquals(List.of(
            "input:time->time", "input:amount->amount", "input:unit->unit", "input:time_zone->time_zone",
            "output:time->output_time", "output:valid->valid", "output:error->error"),
            rawById.get("time_add").getAsJsonObject("migrationMapping").getAsJsonArray("pins").asList().stream()
                .map(TimeCatalogTest::mappingText).toList());
        assertEquals(7, definitions.stream().filter(definition -> definition.getId().equals("time_add"))
            .findFirst().orElseThrow().getPinMigrationMappings().size());

        assertPins(rawById.get("time_diff"), List.of("time1", "time2", "unit"),
            List.of("instant", "instant", "string"), List.of("diff", "signed_diff", "unit_diff", "valid", "error"),
            List.of("duration", "duration", "number", "boolean", "string"));
        assertEquals("milliseconds", pin(rawById.get("time_diff"), "inputs", "unit").get("defaultValue").getAsString());
        assertTrue(rawById.get("time_diff").get("description").getAsString().contains("absolute"));
        assertTrue(rawById.get("time_diff").get("description").getAsString().contains("signed"));
        assertTrue(rawById.get("time_diff").get("description").getAsString().contains("unsupported"));

        assertPins(rawById.get("time_to_ticks"), List.of("seconds"), List.of("number"),
            List.of("ticks", "valid", "error"), List.of("number", "boolean", "string"));
        assertTrue(rawById.get("time_to_ticks").get("description").getAsString().contains("finite"));
        assertTrue(rawById.get("time_to_ticks").get("description").getAsString().contains("whole"));
        assertTrue(rawById.get("time_to_ticks").get("description").getAsString().contains("in-range"));
        assertTrue(rawById.get("time_to_ticks").get("description").getAsString().contains("Non-integral"));
        assertTrue(rawById.get("time_to_ticks").get("description").getAsString().contains("non-finite"));
        assertFalse(rawById.get("time_to_ticks").get("clockDomain").getAsString().contains("server_ticks"));
        assertFalse(rawById.get("time_to_ticks").has("migrationMapping"));
        assertPins(rawById.get("time_current"), List.of(), List.of(), List.of("time", "valid", "error"),
            List.of("instant", "boolean", "string"));
        assertPins(rawById.get("time_get_current_ticks"), List.of(), List.of(), List.of("ticks", "valid", "error"),
            List.of("number", "boolean", "string"));
        assertPins(rawById.get("time_get_current_time"), List.of("world"), List.of("world"),
            List.of("time", "valid", "error"), List.of("number", "boolean", "string"));
        for (String id : IDS) {
            if (id.equals("time_add")) {
                continue;
            }
            assertFalse(rawById.get(id).has("migrationMapping"));
            assertEquals(0, definitions.stream().filter(definition -> definition.getId().equals(id)).findFirst().orElseThrow()
                .getPinMigrationMappings().size());
        }
    }

    private static Map<String, JsonObject> byId(JsonArray definitions) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        definitions.forEach(element -> result.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject()));
        return result;
    }

    private static void assertPins(JsonObject node, List<String> inputIds, List<String> inputTypes,
                                   List<String> outputIds, List<String> outputTypes) {
        assertEquals(inputIds, pinIds(node.getAsJsonArray("inputs")));
        assertEquals(inputTypes, pinTypes(node.getAsJsonArray("inputs")));
        assertEquals(outputIds, pinIds(node.getAsJsonArray("outputs")));
        assertEquals(outputTypes, pinTypes(node.getAsJsonArray("outputs")));
    }

    private static void assertPinsAreExplicit(JsonObject node) {
        for (String direction : List.of("inputs", "outputs")) {
            for (JsonElement element : node.getAsJsonArray(direction)) {
                JsonObject pin = element.getAsJsonObject();
                assertTrue(pin.has("id"));
                assertTrue(pin.has("displayName"));
                assertTrue(pin.has("name"));
                assertEquals("DATA", pin.get("pinType").getAsString());
                assertTrue(pin.has("dataType"));
                assertTrue(pin.has("description"));
                assertFalse(pin.get("description").getAsString().isBlank());
            }
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

    private static List<String> pinIds(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("id").getAsString()).toList();
    }

    private static List<String> pinTypes(JsonArray pins) {
        return pins.asList().stream().map(pin -> pin.getAsJsonObject().get("dataType").getAsString()).toList();
    }

    private static String mappingText(JsonElement element) {
        JsonObject mapping = element.getAsJsonObject();
        return mapping.get("direction").getAsString() + ":" + mapping.get("source").getAsString()
            + "->" + mapping.get("target").getAsString();
    }
}
