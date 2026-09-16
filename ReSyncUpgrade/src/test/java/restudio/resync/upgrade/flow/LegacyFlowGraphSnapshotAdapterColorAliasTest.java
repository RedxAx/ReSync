package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LegacyFlowGraphSnapshotAdapterColorAliasTest {
    @Test
    void normalizesLegacyColorAliasesWithExplicitAndMissingOperations() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        for (String type : List.of("color.mix", "color_mix")) {
            assertColorBlend(adapter, type, "mix");
            assertColorBlend(adapter, type, null);
        }
    }

    @Test
    void normalizesCanonicalUtilityColorBlend() {
        LegacyFlowGraphSnapshotAdapter adapter = new LegacyFlowGraphSnapshotAdapter();
        assertColorBlend(adapter, "utility.color_blend", "color_blend");
    }

    private static void assertColorBlend(LegacyFlowGraphSnapshotAdapter adapter, String type,
                                         String operation) {
        byte[] first = adapter.transformGraph(graph(type, operation), "flow");
        Map<?, ?> root = root(first);
        Map<?, ?> nodes = object(root.get("nodes"));
        Map<?, ?> node = object(nodes.get("blend"));
        Map<?, ?> inputs = object(node.get("inputValues"));
        Map<?, ?> handlerConfig = object(node.get("handlerConfig"));

        assertEquals("color_blend", node.get("type"), type);
        assertEquals(2, ((Number) node.get("version")).intValue(), type);
        assertEquals(Map.of("operation", "color_blend"), handlerConfig, type);
        assertEquals(EXPECTED_INPUT_PINS, inputs.keySet(), type);
        assertEquals("#102030", inputs.get("color1"), type);
        assertEquals("#A0B0C0", inputs.get("color2"), type);
        assertEquals(1, ((Number) inputs.get("ratio")).intValue(), type);
        assertEquals("keep", inputs.get("extra"), type);
        assertFalse(inputs.containsKey("flow"), type);
        assertTrue(root.get("connections") instanceof List<?> connections && connections.isEmpty(), type);
        assertArrayEquals(first, adapter.transformGraph(first, "flow"), type);
    }

    private static byte[] graph(String type, String operation) {
        String handlerConfig = operation == null ? "" : "\"handlerConfig\":{\"operation\":\"" + operation + "\"},";
        String source = "{\"id\":\"color-alias\",\"version\":1,\"nodes\":{\"blend\":{"
            + "\"type\":\"" + type + "\"," + handlerConfig
            + "\"inputValues\":{\"color1\":\"#102030\",\"color2\":\"#A0B0C0\",\"ratio\":1,\"extra\":\"keep\"}}},"
            + "\"connections\":[]}";
        return source.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<?, ?> root(byte[] bytes) {
        Object value = ((JsonValue.JsonObject) CanonicalCodec.decodePermissive(bytes)).toJava();
        return object(value);
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected object");
        }
        return map;
    }

    private static final Set<String> EXPECTED_INPUT_PINS = Set.of("color1", "color2", "ratio", "extra");
}
