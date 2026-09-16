package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LegacyFlowGraphSnapshotAdapterPinMappingTest {
    @Test
    void migratesCanonicalTimeAddPinsAndPreservesUnknownInputs() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        byte[] firstBytes = adapter.transformGraph(timeAddSource("time.add", Map.of(
            "time", 1_000L, "amount", 2L, "unit", "seconds", "unknown", true),
            "time", "sink"), "flow");
        Map<String, Object> first = root(firstBytes);
        Map<String, Object> node = object(object(first.get("nodes")).get("add"));
        Map<String, Object> inputs = object(node.get("inputValues"));
        assertEquals("time_add", node.get("type"));
        assertEquals(Set.of("time", "amount", "unit", "time_zone", "unknown"), inputs.keySet());
        assertEquals(1_000L, ((Number) inputs.get("time")).longValue());
        assertEquals(2L, ((Number) inputs.get("amount")).longValue());
        assertEquals("seconds", inputs.get("unit"));
        assertEquals("UTC", inputs.get("time_zone"));
        assertEquals(true, inputs.get("unknown"));

        List<?> connections = list(first.get("connections"));
        assertEquals(2, connections.size());
        assertConnection(connections.get(0), "input", "value", "add", "time");
        assertConnection(connections.get(1), "add", "output_time", "sink", "value");
        assertArrayEquals(firstBytes, adapter.transformGraph(firstBytes, "flow"));
    }

    @Test
    void migratesMiscTimeAddPinsToCanonicalTimeAdd() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        byte[] firstBytes = adapter.transformGraph(timeAddSource("misc.time_add", Map.of(
            "timestamp_ms", 2_000L, "amount", 3L, "unit", "minutes", "unknown", false),
            "timestamp_ms", "sink"), "flow");
        Map<String, Object> first = root(firstBytes);
        Map<String, Object> node = object(object(first.get("nodes")).get("add"));
        Map<String, Object> inputs = object(node.get("inputValues"));
        assertEquals("time_add", node.get("type"));
        assertEquals(Set.of("time", "amount", "unit", "time_zone", "unknown"), inputs.keySet());
        assertEquals(2_000L, ((Number) inputs.get("time")).longValue());
        assertEquals(3L, ((Number) inputs.get("amount")).longValue());
        assertEquals("minutes", inputs.get("unit"));
        assertEquals("UTC", inputs.get("time_zone"));
        assertEquals(false, inputs.get("unknown"));

        List<?> connections = list(first.get("connections"));
        assertEquals(2, connections.size());
        assertConnection(connections.get(0), "input", "value", "add", "time");
        assertConnection(connections.get(1), "add", "output_time", "sink", "value");
        assertArrayEquals(firstBytes, adapter.transformGraph(firstBytes, "flow"));
    }

    @Test
    void rejectsAmbiguousMiscTimeAddInputAliases() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("add", node("misc.time_add", Map.of(
            "timestamp_ms", 2_000L, "time", 3_000L, "amount", 1L, "unit", "seconds")));
        byte[] source = graphBytes("misc-time-add-input-collision", nodes, List.of());

        assertThrows(IllegalArgumentException.class, () -> adapter.transformGraph(source, "flow"));
    }

    @Test
    void rejectsAmbiguousMiscTimeAddOutputAliases() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        for (String canonicalPin : List.of("output_time", "time")) {
            byte[] source = miscTimeAddOutputCollisionSource(canonicalPin);
            assertThrows(IllegalArgumentException.class,
                () -> adapter.transformGraph(source, "flow"), canonicalPin);
        }
    }

    @Test
    void acceptsSameSourceTimeOutputFanoutAndPreservesOrder() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("add", node("time.add", Map.of("time", 1_000L)));
        nodes.put("sinkA", node("test.sink", Map.of()));
        nodes.put("sinkB", node("test.sink", Map.of()));
        byte[] source = graphBytes("time-fanout", nodes, List.of(
            connection("add", "time", "sinkA", "first"),
            connection("add", "time", "sinkB", "second")));

        byte[] firstBytes = adapter.transformGraph(source, "flow");
        Map<String, Object> first = root(firstBytes);
        List<?> connections = list(first.get("connections"));
        assertEquals(2, connections.size());
        assertConnection(connections.get(0), "add", "output_time", "sinkA", "first");
        assertConnection(connections.get(1), "add", "output_time", "sinkB", "second");
        assertArrayEquals(firstBytes, adapter.transformGraph(firstBytes, "flow"));
    }

    @Test
    void rejectsSimultaneousLegacyAndTargetTimeOutputs() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("add", node("time.add", Map.of("time", 1_000L)));
        nodes.put("legacySink", node("test.sink", Map.of()));
        nodes.put("targetSink", node("test.sink", Map.of()));
        byte[] source = graphBytes("time-output-collision", nodes, List.of(
            connection("add", "time", "legacySink", "first"),
            connection("add", "output_time", "targetSink", "second")));

        assertThrows(IllegalArgumentException.class, () -> adapter.transformGraph(source, "flow"));
    }

    @Test
    void preservesOtherTimePinsAndLeavesCurrentAndTicksNonAuthored() {
        Map<String, LegacyFlowGraphSnapshotAdapter.NodeSchema> schemas = LegacyFlowGraphSnapshotAdapter.loadProductSchemas(
            getClass().getResourceAsStream("/restudio/resync/flow/migration/flow-graph-schema-v2.json"));
        assertExactInputPins(schemas, "time.format", Set.of("time", "format", "time_zone", "locale"));
        assertExactInputPins(schemas, "time.parse", Set.of("string", "format", "time_zone", "locale"));
        assertExactInputPins(schemas, "time.diff", Set.of("time1", "time2", "unit"));
        assertExactInputPins(schemas, "time.time_to_ticks", Set.of("seconds"));
        for (String type : List.of(
            "time.format", "time.parse", "time.diff", "time.time_to_ticks",
            "time.time_current", "get.current.time", "get.current.ticks")) {
            assertTrue(schemas.get(type).pinMappings().isEmpty(), type);
        }
    }

    @Test
    void consumesGeneratedMapPinMappingsForBothConnectionEndpoints() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        String source = """
            {
              "id":"map-mappings",
              "version":1,
              "nodes":{
                "source":{"type":"map.merge","version":1,"inputValues":{}},
                "mapA":{"type":"map.merge","version":1,"inputValues":{"mapA":"first","unknown":true}},
                "mapB":{"type":"map.merge","version":1,"inputValues":{"mapB":"second"}},
                "sink":{"type":"test.sink","version":1,"inputValues":{}}
              },
              "connections":[
                {"sourceNodeId":"source","sourcePin":"map","targetNodeId":"mapA","targetPin":"mapA"},
                {"sourceNodeId":"source","sourcePin":"map","targetNodeId":"mapB","targetPin":"mapB"},
                {"sourceNodeId":"mapA","sourcePin":"map","targetNodeId":"sink","targetPin":"value"}
              ]
            }
            """;

        Map<String, Object> first = root(adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), "flow"));
        List<?> connections = list(first.get("connections"));
        assertEquals("output_map", object(connections.get(0)).get("sourcePin"));
        assertEquals("map_a", object(connections.get(0)).get("targetPin"));
        assertEquals("output_map", object(connections.get(1)).get("sourcePin"));
        assertEquals("map_b", object(connections.get(1)).get("targetPin"));
        assertEquals("output_map", object(connections.get(2)).get("sourcePin"));
        Map<String, Object> nodes = object(first.get("nodes"));
        Map<String, Object> mapAInputs = object(object(nodes.get("mapA")).get("inputValues"));
        Map<String, Object> mapBInputs = object(object(nodes.get("mapB")).get("inputValues"));
        assertEquals("first", mapAInputs.get("map_a"));
        assertEquals(true, mapAInputs.get("unknown"));
        assertEquals(false, mapAInputs.containsKey("mapA"));
        assertEquals("second", mapBInputs.get("map_b"));
        assertEquals(false, mapBInputs.containsKey("mapB"));

        Map<String, Object> second = root(adapter.transformGraph(CanonicalCodec.encode(
            restudio.resync.contract.canonical.JsonValue.fromJava(first)), "flow"));
        assertEquals(first, second);
    }

    @Test
    void generatedMapSchemaContainsDirectionSpecificMappings() {
        LegacyFlowGraphSnapshotAdapter.NodeSchema schema = LegacyFlowGraphSnapshotAdapter.loadProductSchemas(
            getClass().getResourceAsStream("/restudio/resync/flow/migration/flow-graph-schema-v2.json"))
            .get("map.merge");
        assertNotNull(schema);
        assertEquals(3, schema.pinMappings().size());
        assertEquals(List.of(
            new LegacyFlowGraphSnapshotAdapter.PinMapping("map", "output_map", "output", 1, 2),
            new LegacyFlowGraphSnapshotAdapter.PinMapping("mapA", "map_a", "input", 1, 2),
            new LegacyFlowGraphSnapshotAdapter.PinMapping("mapB", "map_b", "input", 1, 2)), schema.pinMappings());
    }

    @Test
    void rejectsAuthoredInputPinCollisionBeforeMutatingInputs() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        String source = "{\"id\":\"map-collision\",\"version\":1,\"nodes\":{\"node\":{\"type\":\"map.merge\",\"version\":1,\"inputValues\":{\"mapA\":1,\"map_a\":2}}},\"connections\":[]}";
        assertThrows(IllegalArgumentException.class,
            () -> adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), "flow"));
    }

    @Test
    void migratesListConcatPinsCaseSensitivelyAndPreservesUnknownInputs() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("listA", List.of("first"));
        inputs.put("listB", List.of("second"));
        inputs.put("unknown", true);
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("concat", node("list.concat", inputs));
        nodes.put("sourceA", node("test.data_source", Map.of()));
        nodes.put("sourceB", node("test.data_source", Map.of()));
        nodes.put("sink", node("test.sink", Map.of()));
        byte[] source = graphBytes("list-concat-pin-migration", nodes, List.of(
            connection("sourceA", "value", "concat", "listA"),
            connection("sourceB", "value", "concat", "listB"),
            connection("concat", "list", "sink", "value")));

        byte[] firstBytes = adapter.transformGraph(source, "flow");
        Map<String, Object> first = root(firstBytes);
        Map<String, Object> migratedNode = object(object(first.get("nodes")).get("concat"));
        Map<String, Object> migratedInputs = object(migratedNode.get("inputValues"));
        assertEquals("list_concat", migratedNode.get("type"));
        assertEquals(2L, ((Number) migratedNode.get("version")).longValue());
        assertEquals(Set.of("lista", "listb", "unknown"), migratedInputs.keySet());
        assertEquals(List.of("first"), migratedInputs.get("lista"));
        assertEquals(List.of("second"), migratedInputs.get("listb"));
        assertEquals(true, migratedInputs.get("unknown"));
        assertFalse(migratedInputs.containsKey("listA"));
        assertFalse(migratedInputs.containsKey("listB"));

        List<?> connections = list(first.get("connections"));
        assertConnection(connections.get(0), "sourceA", "value", "concat", "lista");
        assertConnection(connections.get(1), "sourceB", "value", "concat", "listb");
        assertConnection(connections.get(2), "concat", "list", "sink", "value");

        byte[] secondBytes = adapter.transformGraph(firstBytes, "flow");
        assertArrayEquals(firstBytes, secondBytes);
        assertEquals(first, root(secondBytes));
    }

    @Test
    void generatedListConcatSchemaContainsDirectionSpecificMappings() {
        LegacyFlowGraphSnapshotAdapter.NodeSchema schema = LegacyFlowGraphSnapshotAdapter.loadProductSchemas(
            getClass().getResourceAsStream("/restudio/resync/flow/migration/flow-graph-schema-v2.json"))
            .get("list.concat");
        assertNotNull(schema);
        assertEquals(3, schema.pinMappings().size());
        assertEquals(List.of(
            new LegacyFlowGraphSnapshotAdapter.PinMapping("list", "list", "output", 1, 2),
            new LegacyFlowGraphSnapshotAdapter.PinMapping("listA", "lista", "input", 1, 2),
            new LegacyFlowGraphSnapshotAdapter.PinMapping("listB", "listb", "input", 1, 2)), schema.pinMappings());
    }

    @Test
    void rejectsListConcatRawAndTargetInputCollisionBeforeMigration() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("listA", List.of("legacy"));
        inputs.put("lista", List.of("target"));
        inputs.put("listB", List.of());
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("concat", node("list.concat", inputs));
        byte[] source = graphBytes("list-concat-input-collision", nodes, List.of());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> adapter.transformGraph(source, "flow"));
        assertEquals("FLOW_GRAPH_AUTHORED_PIN_INPUT_COLLISION", exception.getMessage());
    }

    @Test
    void migratesListSortDescendingPinsDirectionallyAndPreservesUnknownInputs() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("list", List.of(3L, 1L, 2L));
        inputs.put("unknown", true);
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("sort", node("list.sort_descending", inputs));
        nodes.put("source", node("test.data_source", Map.of()));
        nodes.put("sink", node("test.sink", Map.of()));
        byte[] source = graphBytes("list-sort-descending-pin-migration", nodes, List.of(
            connection("source", "value", "sort", "list"),
            connection("sort", "list", "sink", "value")));

        byte[] firstBytes = adapter.transformGraph(source, "flow");
        Map<String, Object> first = root(firstBytes);
        Map<String, Object> migratedNode = object(object(first.get("nodes")).get("sort"));
        Map<String, Object> migratedInputs = object(migratedNode.get("inputValues"));
        assertEquals("list_sort_descending", migratedNode.get("type"));
        assertEquals(2L, ((Number) migratedNode.get("version")).longValue());
        assertEquals(Set.of("list", "unknown"), migratedInputs.keySet());
        List<?> migratedList = list(migratedInputs.get("list"));
        assertEquals(3, migratedList.size());
        assertEquals(3L, ((Number) migratedList.get(0)).longValue());
        assertEquals(1L, ((Number) migratedList.get(1)).longValue());
        assertEquals(2L, ((Number) migratedList.get(2)).longValue());
        assertEquals(true, migratedInputs.get("unknown"));

        List<?> connections = list(first.get("connections"));
        assertConnection(connections.get(0), "source", "value", "sort", "list");
        assertConnection(connections.get(1), "sort", "output_list", "sink", "value");

        byte[] secondBytes = adapter.transformGraph(firstBytes, "flow");
        assertArrayEquals(firstBytes, secondBytes);
        assertEquals(first, root(secondBytes));
    }

    @Test
    void generatedListSortDescendingSchemaContainsDirectionSpecificMappings() {
        LegacyFlowGraphSnapshotAdapter.NodeSchema schema = LegacyFlowGraphSnapshotAdapter.loadProductSchemas(
            getClass().getResourceAsStream("/restudio/resync/flow/migration/flow-graph-schema-v2.json"))
            .get("list.sort_descending");
        assertNotNull(schema);
        assertEquals(2, schema.pinMappings().size());
        assertEquals(List.of(
            new LegacyFlowGraphSnapshotAdapter.PinMapping("list", "list", "input", 1, 2),
            new LegacyFlowGraphSnapshotAdapter.PinMapping("list", "output_list", "output", 1, 2)),
            schema.pinMappings());
    }

    @Test
    void rejectsSimultaneousLegacyAndTargetListSortDescendingOutputs() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("sort", node("list.sort_descending", Map.of("list", List.of(3L, 1L))));
        nodes.put("legacySink", node("test.sink", Map.of()));
        nodes.put("targetSink", node("test.sink", Map.of()));
        byte[] source = graphBytes("list-sort-descending-output-collision", nodes, List.of(
            connection("sort", "list", "legacySink", "first"),
            connection("sort", "output_list", "targetSink", "second")));

        assertThrows(IllegalArgumentException.class, () -> adapter.transformGraph(source, "flow"));
    }

    @Test
    void acceptsSameSourceListOutputFanout() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        String source = """
            {
              "id":"list-fanout",
              "version":1,
              "nodes":{
                "list":{"type":"list.add","version":1,"inputValues":{"list":["legacy"],"value":"entry"}},
                "sinkA":{"type":"test.sink","version":1,"inputValues":{}},
                "sinkB":{"type":"test.sink","version":1,"inputValues":{}}
              },
              "connections":[
                {"sourceNodeId":"list","sourcePin":"list","targetNodeId":"sinkA","targetPin":"first"},
                {"sourceNodeId":"list","sourcePin":"list","targetNodeId":"sinkB","targetPin":"second"}
              ]
            }
            """;

        Map<String, Object> first = root(adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), "flow"));
        List<?> connections = list(first.get("connections"));
        assertEquals(2, connections.size());
        assertEquals("output_list", object(connections.get(0)).get("sourcePin"));
        assertEquals("sinkA", object(connections.get(0)).get("targetNodeId"));
        assertEquals("first", object(connections.get(0)).get("targetPin"));
        assertEquals("output_list", object(connections.get(1)).get("sourcePin"));
        assertEquals("sinkB", object(connections.get(1)).get("targetNodeId"));
        assertEquals("second", object(connections.get(1)).get("targetPin"));

        Map<String, Object> second = root(adapter.transformGraph(CanonicalCodec.encode(
            restudio.resync.contract.canonical.JsonValue.fromJava(first)), "flow"));
        assertEquals(first, second);
    }

    @Test
    void migratesEveryBasicListPinWithoutChangingAuthoredInputs() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        for (ListCase listCase : listCases()) {
            byte[] firstBytes = adapter.transformGraph(listCase.source().getBytes(StandardCharsets.UTF_8), "flow");
            Map<String, Object> first = root(firstBytes);
            Map<String, Object> node = object(object(first.get("nodes")).get("list"));
            Map<String, Object> inputs = object(node.get("inputValues"));
            Map<String, Object> connection = object(list(first.get("connections")).getFirst());

            assertEquals(listCase.expectedInputs().keySet(), inputs.keySet(), listCase.type());
            listCase.expectedInputs().forEach((key, expected) -> {
                Object actual = inputs.get(key);
                if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
                    assertEquals(expectedNumber.longValue(), actualNumber.longValue(), listCase.type() + "." + key);
                } else {
                    assertEquals(expected, actual, listCase.type() + "." + key);
                }
            });
            assertEquals("output_list", connection.get("sourcePin"), listCase.type());
            assertEquals("value", connection.get("targetPin"), listCase.type());

            byte[] secondBytes = adapter.transformGraph(firstBytes, "flow");
            assertArrayEquals(firstBytes, secondBytes, listCase.type());
            assertEquals(first, root(secondBytes), listCase.type());
        }
    }

    @Test
    void rejectsSimultaneousLegacyAndTargetListOutputs() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        for (ListCase listCase : listCases()) {
            String source = listCase.source().replace(
                "\"targetNodeId\":\"sink\",\"targetPin\":\"value\"",
                "\"targetNodeId\":\"sink\",\"targetPin\":\"value\"},"
                    + "{\"sourceNodeId\":\"list\",\"sourcePin\":\"output_list\",\"targetNodeId\":\"otherSink\",\"targetPin\":\"value\"");
            assertThrows(IllegalArgumentException.class,
                () -> adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), "flow"), listCase.type());
        }
    }

    @Test
    void migratesEveryAuthoredJsonFlowPinAndPreservesDataPins() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        for (JsonCase jsonCase : jsonCases()) {
            byte[] firstBytes = adapter.transformGraph(jsonSource(jsonCase), "flow");
            Map<String, Object> first = root(firstBytes);
            Map<String, Object> nodes = object(first.get("nodes"));
            Map<String, Object> node = object(nodes.get("json"));
            assertEquals(jsonCase.currentType(), node.get("type"), jsonCase.legacyType());
            assertEquals(jsonCase.inputValues(), object(node.get("inputValues")), jsonCase.legacyType());

            List<?> connections = list(first.get("connections"));
            int index = 0;
            assertConnection(connections.get(index++), "flowSource", "flow", "json", "flow");
            assertConnection(connections.get(index++), "json", "output_flow", "flowSink", "flow");
            for (int inputIndex = 0; inputIndex < jsonCase.dataInputs().size(); inputIndex++) {
                assertConnection(connections.get(index++), "dataSource" + inputIndex, "value", "json",
                    jsonCase.dataInputs().get(inputIndex));
            }
            assertConnection(connections.get(index), "json", jsonCase.dataOutput(), "dataSink", "value");
            assertEquals(connections.size() - 1, index, jsonCase.legacyType());

            byte[] secondBytes = adapter.transformGraph(firstBytes, "flow");
            assertArrayEquals(firstBytes, secondBytes, jsonCase.legacyType());
            assertEquals(first, root(secondBytes), jsonCase.legacyType());
        }
    }

    @Test
    void acceptsSameJsonFlowOutputFanoutAndPreservesOrder() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        byte[] firstBytes = adapter.transformGraph(jsonFlowFanoutSource(), "flow");
        Map<String, Object> first = root(firstBytes);
        List<?> connections = list(first.get("connections"));
        assertEquals(2, connections.size());
        assertConnection(connections.get(0), "json", "output_flow", "sinkA", "first");
        assertConnection(connections.get(1), "json", "output_flow", "sinkB", "second");
        assertArrayEquals(firstBytes, adapter.transformGraph(firstBytes, "flow"));
    }

    @Test
    void rejectsSimultaneousLegacyAndTargetJsonFlowOutputs() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        for (JsonCase jsonCase : jsonCases()) {
            assertThrows(IllegalArgumentException.class,
                () -> adapter.transformGraph(jsonFlowCollisionSource(jsonCase), "flow"), jsonCase.legacyType());
        }
    }

    @Test
    void leavesNonAuthoredJsonActionsUnconvertedBySchemaPath() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        Map<String, Object> setInputs = Map.of(
            "object", Map.of("key", "old"),
            "path", "key",
            "value", "new",
            "unknown", true);
        Map<String, Object> deleteInputs = Map.of(
            "object", Map.of("key", "old"),
            "path", "key",
            "unknown", true);
        byte[] firstBytes = adapter.transformGraph(jsonActionSource(setInputs, deleteInputs), "flow");
        Map<String, Object> first = root(firstBytes);
        Map<String, Object> nodes = object(first.get("nodes"));
        Map<String, Object> setNode = object(nodes.get("set"));
        Map<String, Object> deleteNode = object(nodes.get("delete"));
        assertEquals("json.set", setNode.get("type"));
        assertEquals("json.delete", deleteNode.get("type"));
        assertEquals(setInputs, object(setNode.get("inputValues")));
        assertEquals(deleteInputs, object(deleteNode.get("inputValues")));

        List<?> connections = list(first.get("connections"));
        assertConnection(connections.get(0), "source", "flow", "set", "flow");
        assertConnection(connections.get(1), "set", "flow", "delete", "flow");
        assertConnection(connections.get(2), "delete", "flow", "sink", "flow");
        assertArrayEquals(firstBytes, adapter.transformGraph(firstBytes, "flow"));
    }

    private static List<JsonCase> jsonCases() {
        return List.of(
            new JsonCase("json.parse", "json_parse", Map.of(
                "json_string", "{\"value\":\"text\"}", "unknown", true),
                List.of("json_string"), "object"),
            new JsonCase("json.to.string", "json_to_string", Map.of(
                "object", Map.of("value", "text"), "unknown", true),
                List.of("object"), "string"),
            new JsonCase("json.get", "json_get", Map.of(
                "object", Map.of("value", "text"), "path", "value", "unknown", true),
                List.of("object", "path"), "value"),
            new JsonCase("json.has", "json_has", Map.of(
                "object", Map.of("value", "text"), "path", "value", "unknown", true),
                List.of("object", "path"), "has"),
            new JsonCase("json.keys", "json_keys", Map.of(
                "object", Map.of("value", "text"), "unknown", true),
                List.of("object"), "keys"),
            new JsonCase("json.merge", "json_merge", Map.of(
                "object1", Map.of("one", "1"), "object2", Map.of("two", "2"), "unknown", true),
                List.of("object1", "object2"), "merged"),
            new JsonCase("json.create", "json_create", Map.of("unknown", true),
                List.of(), "object"),
            new JsonCase("json.set.array", "json_set_array", Map.of(
                "values", List.of("one", "two"), "unknown", true),
                List.of("values"), "array"));
    }

    private static byte[] jsonSource(JsonCase jsonCase) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("json", node(jsonCase.legacyType(), jsonCase.inputValues()));
        nodes.put("flowSource", node("test.flow_source", Map.of()));
        nodes.put("flowSink", node("test.flow_sink", Map.of()));
        nodes.put("dataSink", node("test.data_sink", Map.of()));
        for (int index = 0; index < jsonCase.dataInputs().size(); index++) {
            nodes.put("dataSource" + index, node("test.data_source", Map.of()));
        }

        List<Object> connections = new ArrayList<>();
        connections.add(connection("flowSource", "flow", "json", "flow"));
        connections.add(connection("json", "flow", "flowSink", "flow"));
        for (int index = 0; index < jsonCase.dataInputs().size(); index++) {
            connections.add(connection("dataSource" + index, "value", "json", jsonCase.dataInputs().get(index)));
        }
        connections.add(connection("json", jsonCase.dataOutput(), "dataSink", "value"));
        return graphBytes("json-pin-mappings-" + jsonCase.currentType(), nodes, connections);
    }

    private static byte[] jsonFlowFanoutSource() {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("json", node("json.create", Map.of("unknown", true)));
        nodes.put("sinkA", node("test.flow_sink", Map.of()));
        nodes.put("sinkB", node("test.flow_sink", Map.of()));
        return graphBytes("json-flow-fanout", nodes, List.of(
            connection("json", "flow", "sinkA", "first"),
            connection("json", "flow", "sinkB", "second")));
    }

    private static byte[] jsonFlowCollisionSource(JsonCase jsonCase) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("json", node(jsonCase.legacyType(), jsonCase.inputValues()));
        nodes.put("legacySink", node("test.flow_sink", Map.of()));
        nodes.put("targetSink", node("test.flow_sink", Map.of()));
        return graphBytes("json-flow-collision-" + jsonCase.currentType(), nodes, List.of(
            connection("json", "flow", "legacySink", "first"),
            connection("json", "output_flow", "targetSink", "second")));
    }

    private static byte[] jsonActionSource(Map<String, Object> setInputs, Map<String, Object> deleteInputs) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("source", node("test.flow_source", Map.of()));
        nodes.put("set", node("json.set", setInputs));
        nodes.put("delete", node("json.delete", deleteInputs));
        nodes.put("sink", node("test.flow_sink", Map.of()));
        return graphBytes("json-actions-unconverted", nodes, List.of(
            connection("source", "flow", "set", "flow"),
            connection("set", "flow", "delete", "flow"),
            connection("delete", "flow", "sink", "flow")));
    }

    private static byte[] graphBytes(String id, Map<String, Object> nodes, List<Object> connections) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("id", id);
        graph.put("version", 1);
        graph.put("nodes", nodes);
        graph.put("connections", connections);
        return CanonicalCodec.encode(JsonValue.fromJava(graph));
    }

    private static byte[] timeAddSource(String type, Map<String, Object> inputValues,
                                        String inputPin, String sinkId) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("add", node(type, inputValues));
        nodes.put("input", node("test.data_source", Map.of()));
        nodes.put(sinkId, node("test.sink", Map.of()));
        return graphBytes("time-add-" + type, nodes, List.of(
            connection("input", "value", "add", inputPin),
            connection("add", type.equals("misc.time_add") ? "new_timestamp" : "time", sinkId, "value")));
    }

    private static byte[] miscTimeAddOutputCollisionSource(String canonicalPin) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("add", node("misc.time_add", Map.of(
            "timestamp_ms", 2_000L, "amount", 1L, "unit", "seconds")));
        nodes.put("legacySink", node("test.sink", Map.of()));
        nodes.put("targetSink", node("test.sink", Map.of()));
        return graphBytes("misc-time-add-output-collision-" + canonicalPin, nodes, List.of(
            connection("add", "new_timestamp", "legacySink", "first"),
            connection("add", canonicalPin, "targetSink", "second")));
    }

    private static void assertExactInputPins(Map<String, LegacyFlowGraphSnapshotAdapter.NodeSchema> schemas,
                                              String type, Set<String> expected) {
        LegacyFlowGraphSnapshotAdapter.NodeSchema schema = schemas.get(type);
        assertNotNull(schema, type);
        assertEquals(expected, schema.inputTypes().keySet(), type);
    }

    private static Map<String, Object> node(String type, Map<String, Object> inputValues) {
        return Map.of("type", type, "version", 1, "inputValues", inputValues);
    }

    private static Map<String, Object> connection(String sourceNodeId, String sourcePin,
                                                  String targetNodeId, String targetPin) {
        return Map.of("sourceNodeId", sourceNodeId, "sourcePin", sourcePin,
            "targetNodeId", targetNodeId, "targetPin", targetPin);
    }

    private static void assertConnection(Object value, String sourceNodeId, String sourcePin,
                                         String targetNodeId, String targetPin) {
        Map<String, Object> connection = object(value);
        assertEquals(sourceNodeId, connection.get("sourceNodeId"));
        assertEquals(sourcePin, connection.get("sourcePin"));
        assertEquals(targetNodeId, connection.get("targetNodeId"));
        assertEquals(targetPin, connection.get("targetPin"));
    }

    private static List<ListCase> listCases() {
        return List.of(
            new ListCase("list.add", "{\"list\":[\"legacy\"],\"value\":\"entry\",\"unknown\":true}",
                Map.of("list", List.of("legacy"), "value", "entry", "unknown", true)),
            new ListCase("list.remove", "{\"list\":[\"legacy\"],\"value\":\"entry\",\"unknown\":true}",
                Map.of("list", List.of("legacy"), "value", "entry", "unknown", true)),
            new ListCase("list.remove_at", "{\"list\":[\"legacy\"],\"index\":2,\"unknown\":true}",
                Map.of("list", List.of("legacy"), "index", 2, "unknown", true)),
            new ListCase("list.clear", "{\"list\":[\"legacy\"],\"unknown\":true}",
                Map.of("list", List.of("legacy"), "unknown", true)),
            new ListCase("list.set", "{\"list\":[\"legacy\"],\"index\":2,\"value\":\"replacement\",\"unknown\":true}",
                Map.of("list", List.of("legacy"), "index", 2, "value", "replacement", "unknown", true)));
    }

    private record ListCase(String type, String inputsJson, Map<String, Object> expectedInputs) {
        private String source() {
            return "{\"id\":\"" + type + "\",\"version\":1,\"nodes\":{" +
                "\"list\":{\"type\":\"" + type + "\",\"version\":1,\"inputValues\":" + inputsJson + "}," +
                "\"sink\":{\"type\":\"test.sink\",\"version\":1,\"inputValues\":{}}," +
                "\"otherSink\":{\"type\":\"test.sink\",\"version\":1,\"inputValues\":{}}}," +
                "\"connections\":[{\"sourceNodeId\":\"list\",\"sourcePin\":\"list\",\"targetNodeId\":\"sink\",\"targetPin\":\"value\"}]}";
        }
    }

    private record JsonCase(String legacyType, String currentType, Map<String, Object> inputValues,
                            List<String> dataInputs, String dataOutput) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> root(byte[] bytes) {
        return (Map<String, Object>) ((restudio.resync.contract.canonical.JsonValue.JsonObject) CanonicalCodec.decodePermissive(bytes)).toJava();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value) {
        return (List<?>) value;
    }
}
