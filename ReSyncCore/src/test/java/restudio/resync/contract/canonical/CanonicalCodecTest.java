package restudio.resync.contract.canonical;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CanonicalCodecTest {
    @Test
    void preservesUnknownFieldsAndRejectsKnownCollisions() {
        JsonValue value = CanonicalCodec.decode("{\"known\":1,\"nested\":{\"unknown\":true}}".getBytes(StandardCharsets.UTF_8));
        JsonValue.JsonObject object = CanonicalCodec.requireObject(value);
        Map<String, JsonValue> unknown = CanonicalCodec.unknownFields(object, Set.of("known"));
        JsonValue.JsonObject merged = CanonicalCodec.mergeKnownFields(Map.of("known", JsonValue.of(2)), unknown);

        assertEquals("{\"known\":2,\"nested\":{\"unknown\":true}}", merged.canonicalText());
        assertThrows(IllegalArgumentException.class, () -> CanonicalCodec.mergeKnownFields(Map.of("nested", JsonValue.nullValue()), unknown));
    }

    @Test
    void typedHooksUseTheSharedTree() {
        CanonicalCodec<Integer> codec = CanonicalCodec.of(value -> (int) ((JsonValue.JsonNumber) value).value().longValue(), value -> JsonValue.of(value));
        assertEquals(12, codec.decodeText("12"));
        assertArrayEquals("12".getBytes(StandardCharsets.UTF_8), codec.encodeBytes(12));
    }

    @Test
    void canonicalBytesRequireCanonicalInputAndHashSidecarsVerify() {
        byte[] canonical = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(canonical, CanonicalCodec.canonicalBytes(canonical));
        assertThrows(IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes("{ \"a\": 1 }".getBytes(StandardCharsets.UTF_8)));
        CanonicalHash.Sidecar sidecar = CanonicalHash.sidecar(canonical);
        assertEquals("domain=" + CanonicalHash.DOMAIN + "\nsha256=" + CanonicalHash.sha256(canonical) + "\n", sidecar.text());
        CanonicalHash.verify(CanonicalHash.parseSidecar(sidecar.text()), canonical);
    }

    @Test
    void rejectsCyclesAtBothJavaAndContractBoundaries() {
        Map<String, Object> cyclicMap = new LinkedHashMap<>();
        cyclicMap.put("self", cyclicMap);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(cyclicMap));

        List<Object> cyclicList = new ArrayList<>();
        cyclicList.add(cyclicList);
        assertThrows(IllegalArgumentException.class, () -> JsonValue.fromJava(cyclicList));
    }

    @Test
    void enforcesPlainExpansionAndCanonicalNumericTokenLimits() {
        CanonicalLimits expansionLimits = new CanonicalLimits(64, 64, 4, 32, 64, 64, 128, 128, 4, 38, 18, true);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("1e-3", expansionLimits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(new BigDecimal("1e-3"), expansionLimits));

        CanonicalLimits tokenLimits = new CanonicalLimits(64, 64, 4, 32, 64, 64, 4, 128, 64, 38, 18, true);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("1e+00000", tokenLimits));
        String expanded = CanonicalJson.canonicalize(new BigDecimal("1e4"), tokenLimits);
        assertEquals("10000", expanded);
        assertEquals(expanded, CanonicalJson.canonicalizeJson(expanded.getBytes(StandardCharsets.UTF_8), tokenLimits));

        CanonicalLimits scaleLimits = new CanonicalLimits(64, 64, 4, 32, 64, 64, 128, 128, 5, 38, 18, true);
        assertEquals("0.001", CanonicalJson.canonicalizeJson("1e-3".getBytes(StandardCharsets.UTF_8), scaleLimits));
    }

    @Test
    void canonicalPlainExpansionRoundTripsBeyondNumericTokenLimit() {
        String expanded = CanonicalJson.canonicalize(new BigDecimal("1e128"));
        assertEquals(129, expanded.length());
        assertEquals(expanded, CanonicalJson.canonicalizeJson(expanded.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expanded, CanonicalCodec.decode(expanded.getBytes(StandardCharsets.UTF_8)).canonicalText());
        assertThrows(IllegalArgumentException.class, () -> CanonicalCodec.decode("1e128".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void enforcesAggregateAndDirectCanonicalizationTokenLimits() {
        CanonicalLimits byteLimits = new CanonicalLimits(64, 8, 4, 32, 64, 64, 16, 128, 64, 38, 18, true);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("1e8", byteLimits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes("1e8".getBytes(StandardCharsets.UTF_8), byteLimits));

        CanonicalLimits tokenLimits = new CanonicalLimits(64, 64, 4, 2, 64, 64, 16, 128, 64, 38, 18, true);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(List.of(true), tokenLimits));
        assertThrows(IllegalArgumentException.class, () -> JsonValue.array(List.of(JsonValue.of(true))).canonicalBytes(tokenLimits));
    }

    @Test
    void authoritativeDecodeRejectsNoncanonicalInputAndSidecarDomainsAreSafe() {
        byte[] noncanonical = "{ \"a\": 1 }".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> CanonicalCodec.decode(noncanonical));
        assertEquals("{\"a\":1}", CanonicalCodec.decodePermissive(noncanonical).canonicalText());

        byte[] canonical = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String hash = CanonicalHash.sha256(canonical);
        assertThrows(IllegalArgumentException.class, () -> new CanonicalHash.Sidecar("catalog\nsha256=other", hash));
        assertThrows(IllegalArgumentException.class, () -> CanonicalHash.semanticSidecar("Catalog", canonical));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalHash.Sidecar("catalog/other", hash));
    }

    @Test
    void exactDecodeUsesParserOwnedCanonicalSubtreeRanges() {
        String input = "{\"a\":{\"control\":\"\\u001f\",\"path\":\"/api\"},\"b\":[0,1]}";
        CanonicalCodec.ValidatedJson decoded = CanonicalCodec.decodeValidated(input);
        JsonValue.JsonObject root = CanonicalCodec.requireObject(decoded.value());
        JsonValue subtree = root.value("a");

        assertEquals("{\"control\":\"\\u001f\",\"path\":\"/api\"}", decoded.canonicalText(subtree));
        assertArrayEquals("{\"control\":\"\\u001f\",\"path\":\"/api\"}".getBytes(StandardCharsets.UTF_8),
            decoded.canonicalBytes(subtree));
        assertArrayEquals(input.getBytes(StandardCharsets.UTF_8), decoded.canonicalBytes());
        assertThrows(IllegalArgumentException.class,
            () -> decoded.canonicalText(JsonValue.fromJava(subtree.toJava())));
    }

    @Test
    void exactDecodeRejectsEveryLocallyNoncanonicalWireForm() {
        List<String> values = List.of(
            "{ \"a\":0}",
            "{\"b\":0,\"a\":0}",
            "{\"a\":1e0}",
            "{\"a\":\"\\/\"}",
            "{\"a\":\"\\u002f\"}",
            "{\"a\":\"\\u000a\"}",
            "{\"a\":\"\\u001F\"}"
        );

        values.forEach(value -> assertThrows(IllegalArgumentException.class,
            () -> CanonicalCodec.decodeValidated(value)));
        assertEquals("{\"a\":\"\\u001f\"}", CanonicalCodec.decode("{\"a\":\"\\u001f\"}").canonicalText());
        assertEquals("{\"a\":\"/\"}", CanonicalCodec.decodePermissive("{\"a\":\"\\u002f\"}").canonicalText());
    }
}
