package restudio.resync.metadata;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataManifestContractTest {
    private static final MetadataManifestCodec CODEC = new MetadataManifestCodec();
    private static final MetadataArtifactFamily REGISTRY = MetadataArtifactFamily.of("minecraft_registry");

    @Test
    void writesExactCanonicalManifestBytes() {
        MetadataManifest manifest = new MetadataManifest("head-7", List.of(registryDescriptor("26.1")));
        String expected = "{\"bundles\":[{\"artifactFamily\":\"minecraft_registry\",\"bundleId\":\"" + "a".repeat(64)
            + "\",\"byteSize\":1234,\"createdAt\":\"2026-09-19T12:34:56Z\",\"formatVersion\":1,"
            + "\"provenance\":{\"source\":\"mojang\",\"source_version\":\"26.1\"},\"selector\":{"
            + "\"artifactFamily\":\"minecraft_registry\",\"edition\":\"java\",\"maximumDataVersion\":4435,"
            + "\"maximumProtocolVersion\":771,\"minecraftVersion\":\"26.1\",\"minimumDataVersion\":4435,"
            + "\"minimumProtocolVersion\":771,\"requiredCapabilities\":[\"restudio.metadata/registry\"]}}],"
            + "\"formatVersion\":1,\"resolverRevision\":\"head-7\"}";

        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), CODEC.encodeBytes(manifest));
        assertEquals(manifest, CODEC.decodeBytes(expected.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void computesRawCanonicalByteSha256RatherThanTheNamespacedCanonicalHash() {
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        MetadataBundleId bundleId = MetadataBundleId.ofCanonicalBytes(bytes);

        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", bundleId.canonicalText());
        assertNotEquals(CanonicalJson.genericCanonicalContentHash(bytes), bundleId.canonicalText());
        assertTrue(bundleId.verifiesCanonicalBytes(bytes));
        assertThrows(IllegalArgumentException.class,
            () -> MetadataBundleId.ofCanonicalBytes("{ }".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void canonicalEncodingDoesNotDependOnCallerCollectionOrder() {
        MetadataBundleDescriptor registry = registryDescriptor("26.1");
        MetadataArtifactFamily settingsFamily = MetadataArtifactFamily.of("server_settings");
        Set<String> capabilities = new HashSet<>();
        capabilities.add("restudio.metadata/settings");
        capabilities.add("restudio.metadata/base");
        MetadataSelector settingsSelector = new MetadataSelector(settingsFamily, "java", "26.1", null, null, null, null,
            "paper", "26.1-15", "paper", capabilities);
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put("source_version", "26.1-15");
        provenance.put("source", "paper");
        MetadataBundleDescriptor settings = new MetadataBundleDescriptor(new MetadataBundleId("b".repeat(64)), settingsFamily,
            1, settingsSelector, 4321, Instant.parse("2026-09-19T12:35:00Z"), provenance);

        MetadataManifest first = new MetadataManifest("head-8", List.of(settings, registry));
        MetadataManifest second = new MetadataManifest("head-8", List.of(registry, settings));

        assertArrayEquals(CODEC.encodeBytes(first), CODEC.encodeBytes(second));
        assertEquals(List.of(REGISTRY, settingsFamily), first.bundles().stream().map(MetadataBundleDescriptor::artifactFamily).toList());
        assertEquals(List.of("restudio.metadata/base", "restudio.metadata/settings"),
            settings.selector().requiredCapabilities().stream().toList());
        assertEquals(List.of("source", "source_version"), settings.provenance().keySet().stream().toList());
    }

    @Test
    void selectorsMatchExactFutureReleaseNamesAndNumericBounds() {
        for (String version : List.of("26.1", "26.2", "26.3", "27.1", "27w14a")) {
            MetadataSelector selector = selector(version, 4400, 4500, 760, 800, Set.of("restudio.metadata/registry"));
            MetadataCoordinate coordinate = new MetadataCoordinate("java", version, 4435, 771, null, null, null);

            assertTrue(selector.matches(coordinate, Set.of("restudio.metadata/registry", "restudio.metadata/cache")));
            assertFalse(selector.matches(new MetadataCoordinate("java", version + "-other", 4435, 771, null, null, null),
                Set.of("restudio.metadata/registry")));
            assertFalse(selector.matches(new MetadataCoordinate("java", version, 4501, 771, null, null, null),
                Set.of("restudio.metadata/registry")));
            assertFalse(selector.matches(coordinate, Set.of()));
        }

        MetadataSelector paper = new MetadataSelector(MetadataArtifactFamily.of("server_settings"), "java", "27.1",
            null, null, null, null, "paper", "27.1-4", "paper", Set.of());
        assertTrue(paper.matches(new MetadataCoordinate("java", "27.1", null, null, "paper", "27.1-4", "paper"), Set.of()));
        assertFalse(paper.matches(new MetadataCoordinate("java", "27.1", null, null, "purpur", "27.1-4", "paper"), Set.of()));
    }

    @Test
    void freezesCallerCollectionsAndRejectsAmbiguousOrInvalidContracts() {
        Set<String> capabilities = new HashSet<>(Set.of("restudio.metadata/registry"));
        MetadataSelector selector = selector("26.1", null, null, null, null, capabilities);
        Map<String, String> provenance = new LinkedHashMap<>(Map.of("source", "mojang"));
        MetadataBundleDescriptor descriptor = new MetadataBundleDescriptor(new MetadataBundleId("c".repeat(64)), REGISTRY,
            1, selector, 5, Instant.parse("2026-09-19T12:34:56Z"), provenance);
        List<MetadataBundleDescriptor> bundles = new ArrayList<>(List.of(descriptor));
        MetadataManifest manifest = new MetadataManifest("head-9", bundles);

        capabilities.add("restudio.metadata/changed");
        provenance.put("changed", "true");
        bundles.clear();

        assertEquals(Set.of("restudio.metadata/registry"), selector.requiredCapabilities());
        assertEquals(Map.of("source", "mojang"), descriptor.provenance());
        assertEquals(List.of(descriptor), manifest.bundles());
        assertThrows(UnsupportedOperationException.class, () -> manifest.bundles().clear());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.provenance().put("blocked", "true"));
        assertThrows(IllegalArgumentException.class, () -> new MetadataManifest("head", List.of(descriptor, descriptor)));
        assertThrows(IllegalArgumentException.class, () -> MetadataArtifactFamily.of("Minecraft Registry"));
        assertThrows(IllegalArgumentException.class, () -> new MetadataBundleId("A".repeat(64)));
        assertThrows(IllegalArgumentException.class,
            () -> new MetadataCoordinate("java", "26.1", -1, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> selector("26.1", 10, 9, null, null, Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new MetadataCoordinate("java", "26.1", null, null, null, "build-1", null));
    }

    @Test
    void decoderRejectsUnknownFieldsNoncanonicalInputAndNoncanonicalTimestamps() {
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(
            "{\"bundles\":[],\"formatVersion\":1,\"resolverRevision\":\"head\",\"z\":true}"));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(
            "{\"formatVersion\":1,\"bundles\":[],\"resolverRevision\":\"head\"}"));

        String canonical = CODEC.encodeText(new MetadataManifest("head", List.of(registryDescriptor("26.1"))));
        String offsetTimestamp = canonical.replace("2026-09-19T12:34:56Z", "2026-09-19T12:34:56+00:00");
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(offsetTimestamp));
        String unsortedCapabilities = canonical.replace("[\"restudio.metadata/registry\"]",
            "[\"restudio.metadata/registry\",\"restudio.metadata/base\"]");
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(unsortedCapabilities));
        assertThrows(IllegalArgumentException.class, () -> new MetadataManifest(2, "head", List.of()));
    }

    private static MetadataBundleDescriptor registryDescriptor(String version) {
        MetadataSelector selector = selector(version, 4435, 4435, 771, 771, Set.of("restudio.metadata/registry"));
        return new MetadataBundleDescriptor(new MetadataBundleId("a".repeat(64)), REGISTRY, 1, selector, 1234,
            Instant.parse("2026-09-19T12:34:56Z"), Map.of("source_version", version, "source", "mojang"));
    }

    private static MetadataSelector selector(String version, Integer minimumDataVersion, Integer maximumDataVersion,
                                             Integer minimumProtocolVersion, Integer maximumProtocolVersion,
                                             Set<String> capabilities) {
        return new MetadataSelector(REGISTRY, "java", version, minimumDataVersion, maximumDataVersion,
            minimumProtocolVersion, maximumProtocolVersion, null, null, null, capabilities);
    }
}
