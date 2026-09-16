package restudio.resync.flow.canonical;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CanonicalJsonTest {
    @Test
    void gate0bGoldensAndContractOrdering() throws Exception {
        for (int index = 1; index <= 7; index++) {
            Path golden = Path.of("src", "test", "resources", "restudio", "resync", "flow", "canonical", "goldens", "G-00" + index);
            byte[] input = Files.readAllBytes(golden.resolve("input.json"));
            String expected = Files.readString(golden.resolve("canonical.json"), StandardCharsets.UTF_8).strip();
            String sidecar = Files.readString(golden.resolve("canonical.sha256"), StandardCharsets.UTF_8);
            String expectedHash = sidecar.lines()
                .filter(line -> line.startsWith("sha256="))
                .map(line -> line.substring("sha256=".length()))
                .findFirst()
                .orElseThrow();
            String actual = CanonicalJson.canonicalizeJson(input);
            assertEquals(expected, actual);
            assertEquals(expectedHash, CanonicalJson.genericCanonicalContentHash(expected.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void canonicalizesUnicodeNumbersMapsAndSets() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("😀", 1e2);
        value.put("", new BigDecimal("-0.00"));
        value.put("text", "<>&\u0001");
        assertEquals("{\"text\":\"<>&\\u0001\",\"\":0,\"😀\":100}", CanonicalJson.canonicalize(value));

        LinkedHashSet<Object> set = new LinkedHashSet<>(List.of(Map.of("b", 2), Map.of("a", 1)));
        assertEquals("[{\"a\":1},{\"b\":2}]", CanonicalJson.canonicalize(set));
    }

    @Test
    void preparedFragmentsPreserveCanonicalOutputAndAggregateLimits() {
        CanonicalLimits limits = new CanonicalLimits(256, 256, 2, 8, 64, 64, 16, 16, 64, 16, 8, true);
        CanonicalJson.CanonicalFragment fragment = CanonicalJson.prepare(Map.of("value", 2), limits);

        assertEquals("[{\"value\":2}]", CanonicalJson.canonicalize(List.of(fragment), limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(List.of(List.of(fragment)), limits));

        CanonicalJson.CanonicalFragment pair = CanonicalJson.prepare(List.of(1, 2), limits);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(List.of(pair, pair), limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(fragment, CanonicalLimits.standard()));
    }

    @Test
    void preparedFragmentsEnforceAggregateUtf8BytesAtTheExactBoundary() {
        CanonicalLimits limits = new CanonicalLimits(256, 10, 2, 8, 64, 64, 16, 16, 64, 16, 8, true);
        CanonicalJson.CanonicalFragment multibyte = CanonicalJson.prepare("é", limits);
        CanonicalJson.CanonicalFragment boundary = CanonicalJson.prepare("a", limits);
        CanonicalJson.CanonicalFragment overflow = CanonicalJson.prepare("aa", limits);

        assertEquals("[\"é\",\"a\"]", CanonicalJson.canonicalize(List.of(multibyte, boundary), limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(List.of(multibyte, overflow), limits));
    }

    @Test
    void rejectsMalformedInputAndUnicode() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("{\"a\":01}"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("{\"a\":NaN}"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '0'}));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.requireNfc("e\u0301"));
    }

    @Test
    void enforcesPublishedLimits() {
        CanonicalLimits limits = new CanonicalLimits(64, 64, 2, 8, 4, 8, 8, 4, 8, 3, 2, true);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("[[[0]]]", limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("[true,false,null,true,false,true,false]", limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("\"12345\"", limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("123456789", limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("1e9", limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("-1e7", limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize("123456789", limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalDecimal(new BigDecimal("1.234"), limits));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parseOpaque("{\"payload\":\"123456789\"}", limits));
    }

    @Test
    void hashesOnlyTheDomainSeparatedCanonicalBytes() {
        byte[] bytes = "null".getBytes(StandardCharsets.UTF_8);
        assertEquals("bc5b557a729a48ee5c2d303f2d79fa5ec852f6361c3ba506c5a32d8c49acd16e", CanonicalJson.genericCanonicalContentHash(bytes));
        assertEquals("1.23", CanonicalJson.parseDecimal("1.2300").toPlainString());

        assertEquals("d246dc01c32b0692d7531fb69ccfd65d6c771d10fe8e1ab7deee999d3a59b2ed", CanonicalJson.sha256Canonical(CanonicalJson.CATALOG_HASH_DOMAIN, bytes));
        assertEquals("e194791e3d261d27a8bfc178df6f36cce9d9523597a2b197eabb54b0bd0999a3", CanonicalJson.sha256Canonical(CanonicalJson.GRAPH_HASH_DOMAIN, bytes));
        assertEquals("1ebcd3de07857a6f443e2bb5408658bc1bb2bb890dec444c6ecb458b77f56f28", CanonicalJson.sha256Canonical(CanonicalJson.PLAN_HASH_DOMAIN, bytes));
        assertEquals("f2d03f5cb13290af9ba90c4c6c18036cff2d0f612b879af6ad182d7bb988c19f", CanonicalJson.sha256Canonical(CanonicalJson.RESOURCE_HASH_DOMAIN, bytes));
        assertEquals("edc97c3fd69d66f8457a6c79f806653f2b08e1be7666d34d6defb25c99418270", CanonicalJson.sha256Canonical(CanonicalJson.MIGRATION_HASH_DOMAIN, bytes));
        assertNotEquals(CanonicalJson.sha256Canonical(CanonicalJson.CATALOG_HASH_DOMAIN, bytes), CanonicalJson.sha256Canonical(CanonicalJson.GRAPH_HASH_DOMAIN, bytes));
        assertEquals(CanonicalJson.sha256(CanonicalJson.CATALOG_HASH_DOMAIN, null), CanonicalJson.sha256Canonical(CanonicalJson.CATALOG_HASH_DOMAIN, bytes));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.sha256Canonical("Catalog", bytes));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.sha256Canonical("catalog domain", bytes));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.sha256Canonical("catalog\u0000domain", bytes));
    }

    @Test
    void parserReportsExactCanonicalWireWithoutRewritingTheTree() {
        CanonicalJson.ParsedTree exact = CanonicalJson.parseTreeResult("{\"a\":[0,true],\"b\":\"é\"}");
        CanonicalJson.ParsedTree unordered = CanonicalJson.parseTreeResult("{\"b\":\"é\",\"a\":[0,true]}");
        CanonicalJson.ParsedTree spaced = CanonicalJson.parseTreeResult(" {\"a\":[0,true],\"b\":\"é\"}");

        assertTrue(exact.exactCanonical());
        assertEquals("{\"a\":[0,true],\"b\":\"é\"}", exact.canonicalText(exact.value()));
        assertFalse(unordered.exactCanonical());
        assertFalse(spaced.exactCanonical());
        assertThrows(IllegalStateException.class, () -> unordered.canonicalText(unordered.value()));
        assertEquals("{\"a\":[0,true],\"b\":\"é\"}",
            CanonicalJson.canonicalizeJson("{\"b\":\"é\",\"a\":[0,true]}".getBytes(StandardCharsets.UTF_8)));
    }
}
