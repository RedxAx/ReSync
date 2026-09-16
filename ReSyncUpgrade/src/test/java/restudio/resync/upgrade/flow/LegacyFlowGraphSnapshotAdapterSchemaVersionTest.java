package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LegacyFlowGraphSnapshotAdapterSchemaVersionTest {
    @Test
    void rejectsPinMappingsThatDoNotTargetNodeSchemaVersion() {
        LegacyFlowGraphSnapshotAdapter.PinMapping mapping = new LegacyFlowGraphSnapshotAdapter.PinMapping(
            "legacy", "current", "output", 1, 3);

        assertThrows(IllegalArgumentException.class, () -> new LegacyFlowGraphSnapshotAdapter.NodeSchema(
            2, Map.of(), Map.of(), Map.of(), Map.of(), List.of(mapping)));
    }

    @Test
    void migrationUsesResolvedDefinitionSchemaVersion() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter(
            LegacyFlowGraphSnapshotAdapter.GRAPH_OWNER,
            List.of(new LegacyFlowGraphSnapshotAdapter.GraphPath("assets/Blueprints/Flows", "flow")),
            Map.of("test.versioned", new LegacyFlowGraphSnapshotAdapter.NodeSchema(3,
                Map.of("operation", "inspect"), Map.of())));

        Map<String, Object> root = transform(adapter,
            "{\"id\":\"schema-version\",\"version\":1,\"nodes\":{\"node\":{\"type\":\"test.versioned\",\"version\":1,\"inputValues\":{}}},\"connections\":[]}");
        Map<String, Object> node = node(root, "node");

        assertEquals(3, number(node.get("version")));
        assertEquals(Map.of("operation", "inspect"), node.get("handlerConfig"));
    }

    @Test
    void migratesLegacyPermissionRepeatableCount() {
        Map<String, Object> root = transform(defaultAdapter(), graph("permission-count", 0,
            Map.of("permission", node("permission.perm_has", Map.of("__permission_count", 4)))));
        Map<String, Object> inputs = inputs(root, "permission");

        assertEquals(4, number(inputs.get("__repeatable_count:permissions")));
        assertFalse(inputs.containsKey("__permission_count"));
    }

    @Test
    void movesArmorSlotOutOfAllNodeInputsAndIntoContentConfiguration() {
        Map<String, Object> root = transform(defaultAdapter(), graph("armor", 1,
            Map.of(
                "item", node("custom_content.item", Map.of("content_id", "item", "armor_slot", "chest")),
                "block", node("custom_content.block", Map.of("content_id", "block", "armor_slot", "head")),
                "armor", node("custom_content.armor", Map.of("content_id", "armor", "armor_slot", "chest")))));

        assertFalse(inputs(root, "item").containsKey("armor_slot"));
        assertFalse(inputs(root, "block").containsKey("armor_slot"));
        assertFalse(inputs(root, "armor").containsKey("armor_slot"));
        assertEquals("chest", object(root.get("contentProperties")).get("armor_slot"));
    }

    @Test
    void migratesControlAliasesDirectlyToCanonicalNodes() {
        Map<String, Object> root = transform(defaultAdapter(), graph("control-aliases", 1,
            Map.of(
                "count", node("flow.loop_count", Map.of()),
                "each", node("logic_loop_for_each", Map.of()),
                "switch", node("logic.switch_case", Map.of()))));

        assertEquals("loop.count", node(root, "count").get("type"));
        assertEquals("loop.for.each", node(root, "each").get("type"));
        assertEquals("flow.switch_case", node(root, "switch").get("type"));
    }

    @Test
    void migratesRuntimeResourceIdsToTypedReferences() {
        Map<String, Object> root = transform(defaultAdapter(), graph("trade", 1,
            Map.of("trade", node("trade.open_trades", Map.of("profile_id", "starter")))));
        Map<String, Object> reference = object(inputs(root, "trade").get("profile_id"));

        assertEquals("trade_profile", reference.get("kind"));
        assertEquals("starter", reference.get("id"));
    }

    @Test
    void migratesLegacyNetworkIdentifiersToTypedReferences() {
        String source = graph("handoff", 1, Map.of("handoff", node("network.player.handoff",
            Map.of("target_node", "node-02", "server", "survival"))));
        LegacyFlowGraphSnapshotAdapter adapter = defaultAdapter();
        Map<String, Object> root = transform(adapter, source);
        Map<String, Object> inputs = inputs(root, "handoff");
        Map<String, Object> targetNode = object(inputs.get("target_node"));
        Map<String, Object> route = object(inputs.get("server"));

        assertEquals("network_node", targetNode.get("kind"));
        assertEquals("node-02", targetNode.get("id"));
        assertEquals("network_route", route.get("kind"));
        assertEquals("survival", route.get("id"));
        assertEquals(root, transform(adapter, new String(adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), "flow"), StandardCharsets.UTF_8)));
    }

    @Test
    void migratesLegacyScoreboardTemplateIdentifiers() {
        Map<String, Object> root = transform(defaultAdapter(), graph("scoreboard", 1,
            Map.of("scoreboard", node("scoreboard.show.template", Map.of("scoreboard_id", "lobby")))));
        Map<String, Object> reference = object(inputs(root, "scoreboard").get("scoreboard_id"));

        assertEquals("scoreboard", reference.get("kind"));
        assertEquals("lobby", reference.get("id"));
    }

    @Test
    void migratesLegacyNamedAndRgbColorValuesByResolvedPinType() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter(
            LegacyFlowGraphSnapshotAdapter.GRAPH_OWNER,
            List.of(new LegacyFlowGraphSnapshotAdapter.GraphPath("assets/Blueprints/Flows", "flow")),
            Map.of("test.colors", new LegacyFlowGraphSnapshotAdapter.NodeSchema(1, Map.of(),
                Map.of("named", "named_text_color", "rgb", "rgb_color"))));
        Map<String, Object> root = transform(adapter,
            "{\"id\":\"colors\",\"version\":1,\"nodes\":{\"colors\":{\"type\":\"test.colors\",\"version\":1,\"inputValues\":{\"named\":\"&4\",\"rgb\":\"gold\"}}},\"connections\":[]}");
        Map<String, Object> inputs = inputs(root, "colors");

        assertEquals("dark_red", inputs.get("named"));
        assertEquals("#FFAA00", inputs.get("rgb"));
        assertEquals(root, transform(adapter, canonical(root)));
    }

    @Test
    void migratesLenientMiscTimeNodesToTheStrictTimeContract() {
        Map<String, Object> root = transform(defaultAdapter(), graph("time-format", 1,
            Map.of(
                "format", node("misc.time_format", Map.of("timestamp_ms", 1_000L, "format_pattern", "uuuu-MM-dd HH:mm:ss")),
                "target", node("test.target", Map.of()))),
            List.of(connection("format", "formatted_string", "target", "value")));
        Map<String, Object> node = node(root, "format");

        assertEquals("time_format", node.get("type"));
        assertEquals(3, number(node.get("version")));
        assertEquals(1_000, number(inputs(root, "format").get("time")));
        assertEquals("uuuu-MM-dd HH:mm:ss", inputs(root, "format").get("format"));
        assertEquals("UTC", inputs(root, "format").get("time_zone"));
        assertFalse(inputs(root, "format").containsKey("timestamp_ms"));
        assertFalse(inputs(root, "format").containsKey("format_pattern"));
        assertEquals("string", connection(root, 0).get("sourcePin"));
    }

    @Test
    void migratesImplicitTemporalZonesToUtcIdempotently() {
        Map<String, Object> source = transform(defaultAdapter(), graph("temporal", 1,
            Map.of(
                "parser", node("time.parse", Map.of("time_zone", "")),
                "schedule", node("schedule.cron", Map.of()))));

        assertEquals("UTC", inputs(source, "parser").get("time_zone"));
        assertEquals("UTC", inputs(source, "schedule").get("time_zone"));
        assertEquals(source, transform(defaultAdapter(), canonical(source)));
    }

    @Test
    void migratesLegacyParticleNodesAndConnectionsToTheParticleFamily() {
        Map<String, Object> root = transform(defaultAdapter(), graph("particle", 0,
            Map.of(
                "source", node("test.source", Map.of()),
                "particle", node("particle_circle", Map.of("particle_type", "FLAME", "center_location", "legacy", "points", 32)),
                "target", node("test.target", Map.of())),
            List.of(
                connection("source", "location", "particle", "center_location"),
                connection("particle", "next", "target", "flow"))));
        Map<String, Object> particle = node(root, "particle");

        assertEquals("particle.apply", particle.get("type"));
        assertEquals("particle_apply", object(particle.get("handlerConfig")).get("operation"));
        assertEquals("circle", inputs(root, "particle").get("mode"));
        assertEquals("FLAME", inputs(root, "particle").get("particle"));
        assertEquals("legacy", inputs(root, "particle").get("location"));
        assertEquals(32, number(inputs(root, "particle").get("count")));
        assertEquals("location", connection(root, 0).get("targetPin"));
        assertEquals("output_flow", connection(root, 1).get("sourcePin"));
        assertEquals(root, transform(defaultAdapter(), canonical(root)));
    }

    @Test
    void migratesCompatibleLegacyDelayNodesWithoutChangingTheirClockUnits() {
        LegacyFlowGraphSnapshotAdapter adapter = defaultAdapter();
        String source = graph("delay", 0,
            Map.of(
                "delay", node("misc.delay", Map.of("ticks", 40), Map.of("operation", "delay")),
                "target", node("test.target", Map.of())),
            List.of(connection("delay", "done", "target", "value")));
        byte[] firstBytes = adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), "flow");
        Map<String, Object> root = object(firstBytes);
        Map<String, Object> delay = node(root, "delay");

        assertEquals("schedule.wait_ticks", delay.get("type"));
        assertEquals("wait_ticks", object(delay.get("handlerConfig")).get("operation"));
        assertEquals(2, number(delay.get("version")));
        assertEquals(40, number(inputs(root, "delay").get("ticks")));
        assertEquals("completed", connection(root, 0).get("sourcePin"));
        assertArrayEquals(firstBytes, adapter.transformGraph(firstBytes, "flow"));
    }

    @Test
    void migratesLegacyMarketplaceExecutionPinsAndObsoleteLiterals() {
        Map<String, Object> root = transform(defaultAdapter(), graph("marketplace", 1,
            Map.of(
                "loop", node("loop_while", Map.of()),
                "branch", node("if", Map.of()),
                "kill", node("entity.kill", Map.of("action", "do"))),
            List.of(connection("loop", "completed", "branch", "flow"))));

        assertEquals("done", connection(root, 0).get("sourcePin"));
        assertFalse(inputs(root, "kill").containsKey("action"));
        assertEquals(root, transform(defaultAdapter(), canonical(root)));
    }

    @Test
    void migratesLegacyLoopBodyAndBreakContinuationTopology() {
        Map<String, Object> root = transform(defaultAdapter(), graph("loop-break", 1,
            Map.of(
                "loop", node("loop.for.each", Map.of()),
                "branch", node("if", Map.of()),
                "break", node("break.loop", Map.of()),
                "end", node("function.end", Map.of())),
            List.of(
                connection("loop", "flow", "branch", "flow"),
                connection("branch", "true", "break", "flow"),
                connection("break", "flow", "end", "flow"))));

        assertEquals("loop", connection(root, 0).get("sourcePin"));
        assertEquals("loop", connection(root, 2).get("sourceNodeId"));
        assertEquals("done", connection(root, 2).get("sourcePin"));
        assertEquals(root, transform(defaultAdapter(), canonical(root)));
    }

    @Test
    void migratesFunctionAndVariableSemanticMetadata() {
        Map<String, Object> root = transform(defaultAdapter(), """
            {
              "id":"legacy_function",
              "version":1,
              "nodes":{},
              "connections":[],
              "localVariables":[{"name":"value","type":"string","initialValue":""}],
              "function":true,
              "functionInputs":[{"name":"items","type":"list"}],
              "functionOutputs":[]
            }
            """);
        Map<String, Object> input = object(((List<?>) root.get("functionInputs")).getFirst());
        Map<String, Object> variable = object(((List<?>) root.get("localVariables")).getFirst());
        Map<String, Object> typeRef = object(input.get("typeRef"));

        assertEquals(2, number(root.get("version")));
        assertEquals("server", root.get("functionOwner"));
        assertEquals("local", root.get("functionNamespace"));
        assertEquals("list", typeRef.get("typeId"));
        assertEquals(Map.of("typeId", "any", "arguments", List.of()), ((List<?>) typeRef.get("arguments")).getFirst());
        assertEquals("execution", variable.get("lifetime"));
        assertEquals("isolated", variable.get("concurrencyPolicy"));
    }

    private LegacyFlowGraphSnapshotAdapter defaultAdapter() {
        return new LegacyFlowGraphSnapshotAdapter();
    }

    private Map<String, Object> transform(LegacyFlowGraphSnapshotAdapter adapter, String source) {
        return transform(adapter, source, "flow");
    }

    private Map<String, Object> transform(LegacyFlowGraphSnapshotAdapter adapter, String source, List<Object> connections) {
        Map<String, Object> root = object(source);
        root.put("connections", connections);
        return transform(adapter, canonical(root), "flow");
    }

    private Map<String, Object> transform(LegacyFlowGraphSnapshotAdapter adapter, String source, String resourceType) {
        return object(adapter.transformGraph(source.getBytes(StandardCharsets.UTF_8), resourceType));
    }

    private Map<String, Object> object(String source) {
        return object(CanonicalCodec.decodePermissive(source.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> object(byte[] source) {
        return object(CanonicalCodec.decodePermissive(source));
    }

    private Map<String, Object> object(JsonValue value) {
        if (value instanceof JsonValue.JsonObject object) {
            return object(object.toJava());
        }
        throw new IllegalArgumentException("Expected JSON object");
    }

    private Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put((String) entry.getKey(), entry.getValue());
            }
            return result;
        }
        throw new IllegalArgumentException("Expected JSON object");
    }

    private String canonical(Map<String, Object> value) {
        return new String(CanonicalCodec.encode(JsonValue.fromJava(value)), StandardCharsets.UTF_8);
    }

    private Map<String, Object> node(Map<String, Object> root, String id) {
        return object(object(root.get("nodes")).get(id));
    }

    private Map<String, Object> inputs(Map<String, Object> root, String id) {
        return object(node(root, id).get("inputValues"));
    }

    private Map<String, Object> connection(Map<String, Object> root, int index) {
        return object(((List<?>) root.get("connections")).get(index));
    }

    private int number(Object value) {
        return ((Number) value).intValue();
    }

    private String graph(String id, int version, Map<String, Map<String, Object>> nodes) {
        return graph(id, version, nodes, List.of());
    }

    private String graph(String id, int version, Map<String, Map<String, Object>> nodes, List<Object> connections) {
        Map<String, Object> root = new HashMap<>();
        root.put("id", id);
        root.put("version", version);
        root.put("nodes", nodes);
        root.put("connections", connections);
        return canonical(root);
    }

    private Map<String, Object> node(String type, Map<String, Object> inputs) {
        return node(type, inputs, Map.of());
    }

    private Map<String, Object> node(String type, Map<String, Object> inputs, Map<String, Object> handlerConfig) {
        Map<String, Object> node = new HashMap<>();
        node.put("type", type);
        node.put("version", 1);
        node.put("inputValues", inputs);
        node.put("handlerConfig", handlerConfig);
        return node;
    }

    private Map<String, Object> connection(String sourceNodeId, String sourcePin, String targetNodeId, String targetPin) {
        return new HashMap<>(Map.of(
            "sourceNodeId", sourceNodeId,
            "sourcePin", sourcePin,
            "targetNodeId", targetNodeId,
            "targetPin", targetPin));
    }
}
