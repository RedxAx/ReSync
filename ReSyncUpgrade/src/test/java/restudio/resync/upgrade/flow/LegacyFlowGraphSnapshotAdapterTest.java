package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.migration.FlowNodeMigrationMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyFlowGraphSnapshotAdapterTest {
    @Test
    void appliesVersionedSchemaDefaultsAndTypedColors() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter(
            LegacyFlowGraphSnapshotAdapter.GRAPH_OWNER,
            java.util.List.of(new LegacyFlowGraphSnapshotAdapter.GraphPath("assets/Blueprints/Flows", "flow")),
            Map.of("test.colors", new LegacyFlowGraphSnapshotAdapter.NodeSchema(3,
                Map.of("operation", "inspect"),
                Map.of("named", "named_text_color", "rgb", "rgb_color"))));
        String source = "{\"id\":\"colors\",\"version\":1,\"nodes\":{\"node\":{\"type\":\"test.colors\",\"version\":1,\"inputValues\":{\"named\":\"&4\",\"rgb\":\"gold\"}}},\"connections\":[]}";

        Map<?, ?> root = root(adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), "flow"));
        Map<?, ?> node = (Map<?, ?>) ((Map<?, ?>) root.get("nodes")).get("node");
        assertEquals(3, ((Number) node.get("version")).intValue());
        assertEquals(Map.of("operation", "inspect"), node.get("handlerConfig"));
        assertEquals("dark_red", ((Map<?, ?>) node.get("inputValues")).get("named"));
        assertEquals("#FFAA00", ((Map<?, ?>) node.get("inputValues")).get("rgb"));
        assertEquals("flow", root.get("resourceType"));
    }

    @Test
    void secondTransformIsByteStable() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        String source = "{\"id\":\"stable\",\"version\":1,\"nodes\":{\"node\":{\"type\":\"logic_true\",\"inputValues\":{}}},\"connections\":[]}";
        byte[] first = adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), "flow");
        byte[] second = adapter.transformGraph(first, "flow");
        assertTrue(java.util.Arrays.equals(first, second));
        assertFalse(first.length == 0);
    }

    @Test
    void migratesEveryNewlyActiveLogicMapAndResultIdentity() throws IOException {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("logic.logic_and", "and"),
            Map.entry("logic.logic_or", "or"),
            Map.entry("logic.logic_not", "not"),
            Map.entry("logic.logic_xor", "xor"),
            Map.entry("logic.logic_nand", "nand"),
            Map.entry("logic.logic_nor", "nor"),
            Map.entry("logic.logic_true", "true"),
            Map.entry("logic.logic_false", "false"),
            Map.entry("logic.compare_equals", "core.logic.compare_equals"),
            Map.entry("logic.compare_not_equals", "core.logic.compare_not_equals"),
            Map.entry("logic.compare_greater", "core.logic.compare_greater"),
            Map.entry("logic.compare_less", "core.logic.compare_less"),
            Map.entry("logic.compare_greater_or_equal", "core.logic.compare_greater_or_equal"),
            Map.entry("logic.compare_less_or_equal", "core.logic.compare_less_or_equal"),
            Map.entry("logic.compare_between", "core.logic.compare_between"),
            Map.entry("logic.compare_type", "core.logic.compare_type"),
            Map.entry("map.create", "core.map.create"),
            Map.entry("map.get", "core.map.get"),
            Map.entry("map.set", "core.map.set"),
            Map.entry("map.contains_key", "core.map.contains_key"),
            Map.entry("map.contains_value", "core.map.contains_value"),
            Map.entry("map.keys", "core.map.keys"),
            Map.entry("map.values", "core.map.values"),
            Map.entry("map.size", "core.map.size"),
            Map.entry("map.is_empty", "core.map.is_empty"),
            Map.entry("map.remove", "core.map.remove"),
            Map.entry("map.clear", "core.map.clear"),
            Map.entry("map.merge", "core.map.merge"),
            Map.entry("map.put_all", "core.map.put_all"),
            Map.entry("result.success", "core.result.success"),
            Map.entry("result.failure", "core.result.failure"),
            Map.entry("result.is_success", "core.result.is_success"),
            Map.entry("result.value", "core.result.value"),
            Map.entry("result.error", "core.result.error"),
            Map.entry("result.match", "core.result.match"));
        Map<String, String> migration = FlowNodeMigrationMap.load();
        expected.forEach((sourceId, targetId) -> assertEquals(targetId, migration.get(sourceId)));
        Set<String> activeNodeIds = activeNodeIds();
        expected.values().forEach(targetId -> assertTrue(activeNodeIds.contains(targetId), "Missing active node target: " + targetId));

        Map<String, Object> nodes = new LinkedHashMap<>();
        expected.keySet().forEach(id -> nodes.put(id, Map.of("type", id)));
        Map<String, Object> source = Map.of("id", "renamed", "version", 1, "nodes", nodes, "connections", List.of());
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter(
            LegacyFlowGraphSnapshotAdapter.GRAPH_OWNER,
            List.of(new LegacyFlowGraphSnapshotAdapter.GraphPath("assets/Blueprints/Flows", "flow")),
            Map.of());
        Map<?, ?> migrated = root(adapter.transformGraph(CanonicalCodec.encode(JsonValue.fromJava(source)), "flow"));
        Map<?, ?> migratedNodes = (Map<?, ?>) migrated.get("nodes");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), ((Map<?, ?>) migratedNodes.get(entry.getKey())).get("type"));
        }
    }

    @Test
    void migratesUuidAliasesDirectlyToActiveLocalIds() {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("uuid.from.string", "uuid_from_string"),
            Map.entry("uuid.to.string", "uuid_to_string"),
            Map.entry("utility.uuid_generate", "uuid_generate"),
            Map.entry("utility.uuid_version", "uuid_version"),
            Map.entry("utility.uuid_timestamp", "uuid_timestamp"));
        Map<String, String> migration = FlowNodeMigrationMap.load();
        expected.forEach((sourceId, targetId) -> assertEquals(targetId, migration.get(sourceId)));
        expected.values().forEach(activeId -> assertFalse(migration.containsKey(activeId)));
        assertFalse(migration.containsKey("uuid_random"));
        assertFalse(migration.containsValue("uuid.random"));
    }

    @Test
    void productSchemaIsRequiredAndUndeclaredColorsRemainOpaque() {
        LegacyFlowGraphSnapshotAdapter defaultAdapter = new LegacyFlowGraphSnapshotAdapter();
        String canonicalNode = "{\"id\":\"known\",\"version\":1,\"nodes\":{\"node\":{\"type\":\"if\",\"inputValues\":{}}},\"connections\":[]}";
        assertTrue(new String(defaultAdapter.transformGraph(canonicalNode.getBytes(StandardCharsets.UTF_8), "flow"), StandardCharsets.UTF_8)
            .contains("assetHash"));

        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter(
            LegacyFlowGraphSnapshotAdapter.GRAPH_OWNER,
            java.util.List.of(new LegacyFlowGraphSnapshotAdapter.GraphPath("assets/Blueprints/Flows", "flow")),
            Map.of("test.opaque", new LegacyFlowGraphSnapshotAdapter.NodeSchema(1, Map.of(), Map.of())));
        String source = "{\"id\":\"opaque\",\"version\":1,\"nodes\":{\"node\":{\"type\":\"test.opaque\",\"inputValues\":{\"color\":\"gold\"}}},\"connections\":[]}";
        Map<?, ?> root = root(adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), "flow"));
        Map<?, ?> node = (Map<?, ?>) ((Map<?, ?>) root.get("nodes")).get("node");
        assertEquals("gold", ((Map<?, ?>) node.get("inputValues")).get("color"));

        LegacyFlowGraphSnapshotAdapter unavailable = new LegacyFlowGraphSnapshotAdapter(
            LegacyFlowGraphSnapshotAdapter.GRAPH_OWNER,
            java.util.List.of(new LegacyFlowGraphSnapshotAdapter.GraphPath("assets/Blueprints/Flows", "flow")),
            Map.of());
        assertThrows(IllegalArgumentException.class, () -> unavailable.transformGraph(
            canonicalNode.getBytes(StandardCharsets.UTF_8), "flow"));
    }

    private static Map<?, ?> root(byte[] bytes) {
        Object value = ((restudio.resync.contract.canonical.JsonValue.JsonObject) CanonicalCodec.decodePermissive(bytes)).toJava();
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Set<String> activeNodeIds() throws IOException {
        Set<String> ids = new HashSet<>();
        for (String fileName : List.of("logic_boolean.json", "logic_compare.json", "map_mutation.json", "map_query.json", "result.json")) {
            Object value = ((JsonValue.JsonArray) CanonicalCodec.decodePermissive(Files.readAllBytes(activeNodes().resolve(fileName)))).toJava();
            if (!(value instanceof List<?> definitions)) {
                throw new IllegalStateException("Active node definitions must be an array: " + fileName);
            }
            for (Object definition : definitions) {
                if (definition instanceof Map<?, ?> object && object.get("id") instanceof String id) {
                    ids.add(id);
                }
            }
        }
        return Set.copyOf(ids);
    }

    private static Path activeNodes() {
        Path direct = Path.of("src", "main", "resources", "nodes");
        if (Files.isRegularFile(direct.resolve("logic_boolean.json"))) {
            return direct;
        }
        Path parent = Path.of("..", "src", "main", "resources", "nodes");
        if (Files.isRegularFile(parent.resolve("logic_boolean.json"))) {
            return parent;
        }
        throw new IllegalStateException("Active node definitions are missing");
    }
}
