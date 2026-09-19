package restudio.resync.metadata;

import org.junit.jupiter.api.Test;
import restudio.resync.metadata.MinecraftRegistryBundle.Catalog;
import restudio.resync.metadata.MinecraftRegistryBundle.Entry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftRegistryBundleContractTest {
    private static final MinecraftRegistryBundleCodec CODEC = new MinecraftRegistryBundleCodec();

    @Test
    void writesExactCanonicalRegistryBytes() {
        MinecraftRegistryBundle bundle = new MinecraftRegistryBundle("27.1", "2026-09-19T16:00:00Z", List.of(
            catalog("items", "Items", "Vanilla item types", List.of(
                entry("minecraft:stone", "Stone", "A block item", "minecraft:item/stone", "Building Blocks",
                    linkedMap("rarity", "common", "translation_key", "block.minecraft.stone")))),
            catalog("entities", "Entities", "Vanilla entity types", List.of(
                entry("minecraft:zombie", "Zombie", "An undead hostile mob", null, "Monsters", Map.of())))));
        String expected = "{\"artifactFamily\":\"minecraft_registry\",\"catalogs\":[{\"description\":\"Vanilla entity types\","
            + "\"entries\":[{\"description\":\"An undead hostile mob\",\"group\":\"Monsters\",\"label\":\"Zombie\","
            + "\"metadata\":{},\"value\":\"minecraft:zombie\"}],\"id\":\"minecraft:entities\",\"label\":\"Entities\"},{"
            + "\"description\":\"Vanilla item types\",\"entries\":[{\"description\":\"A block item\","
            + "\"group\":\"Building Blocks\",\"icon\":\"minecraft:item/stone\",\"label\":\"Stone\","
            + "\"metadata\":{\"rarity\":\"common\",\"translation_key\":\"block.minecraft.stone\"},"
            + "\"value\":\"minecraft:stone\"}],\"id\":\"minecraft:items\",\"label\":\"Items\"}],"
            + "\"createdAt\":\"2026-09-19T16:00:00Z\",\"formatVersion\":1,\"minecraftVersion\":\"27.1\"}";

        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), CODEC.encodeBytes(bundle));
        assertEquals(bundle, CODEC.decodeBytes(expected.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void preservesSemanticEntryOrderAndTreatsVersionsAsOpaque() {
        Entry zombie = entry("minecraft:zombie", "Zombie", "Zombie", null, "Monsters", Map.of());
        Entry allay = entry("minecraft:allay", "Allay", "Allay", null, "Passive", Map.of());
        MinecraftRegistryBundle bundle = new MinecraftRegistryBundle("27.1-preview+registry.4", "created", List.of(
            catalog("items", "Items", "Items", List.of(entry("minecraft:stone", "Stone", "Stone", null, null, Map.of()))),
            catalog("entities", "Entities", "Entities", List.of(zombie, allay))));

        assertEquals("27.1-preview+registry.4", bundle.minecraftVersion());
        assertEquals(List.of("minecraft:entities", "minecraft:items"),
            bundle.catalogs().stream().map(value -> value.id().canonicalText()).toList());
        assertEquals(List.of(zombie, allay), bundle.catalogs().getFirst().entries());
        assertEquals(bundle, CODEC.decodeBytes(CODEC.encodeBytes(bundle)));
    }

    @Test
    void freezesCallerCollectionsAndSortsMetadataKeys() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("translation_key", "entity.minecraft.zombie");
        metadata.put("category", "monster");
        Entry entry = entry("minecraft:zombie", "Zombie", "Zombie", null, null, metadata);
        List<Entry> entries = new ArrayList<>(List.of(entry));
        Catalog catalog = catalog("entities", "Entities", "Entities", entries);
        List<Catalog> catalogs = new ArrayList<>(List.of(catalog));
        MinecraftRegistryBundle bundle = new MinecraftRegistryBundle("26.3", "created", catalogs);

        metadata.clear();
        entries.clear();
        catalogs.clear();

        assertEquals(Map.of("category", "monster", "translation_key", "entity.minecraft.zombie"), entry.metadata());
        assertEquals(List.of("category", "translation_key"), new ArrayList<>(entry.metadata().keySet()));
        assertEquals(1, catalog.entries().size());
        assertEquals(1, bundle.catalogs().size());
        assertThrows(UnsupportedOperationException.class, () -> entry.metadata().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> bundle.catalogs().clear());
    }

    @Test
    void rejectsDuplicateCatalogsAndEntryValues() {
        Entry zombie = entry("minecraft:zombie", "Zombie", "Zombie", null, null, Map.of());
        Catalog entities = catalog("entities", "Entities", "Entities", List.of(zombie));

        assertThrows(IllegalArgumentException.class,
            () -> new MinecraftRegistryBundle("26.3", "created", List.of(entities, entities)));
        assertThrows(IllegalArgumentException.class,
            () -> catalog("entities", "Entities", "Entities", List.of(zombie, zombie)));
        assertThrows(IllegalArgumentException.class,
            () -> new MinecraftRegistryBundle("26.3", "created", List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new Catalog(CatalogId.of("minecraft", "entities"), "Entities", "Entities", List.of()));
    }

    @Test
    void rejectsUnsafeIdentitiesAndUnboundedOrInvalidMetadata() {
        assertEquals(CatalogId.of("minecraft", "entity_types"), CatalogId.parseCanonicalText("minecraft:entity_types"));
        assertEquals(CatalogId.of("minecraft", "worldgen/biome"), CatalogId.parseCanonicalText("minecraft:worldgen/biome"));
        assertThrows(IllegalArgumentException.class, () -> CatalogId.parseCanonicalText("minecraft"));
        assertThrows(IllegalArgumentException.class, () -> CatalogId.parseCanonicalText("minecraft:entities:hostile"));
        assertThrows(IllegalArgumentException.class, () -> CatalogId.of("Minecraft", "entities"));
        assertThrows(IllegalArgumentException.class,
            () -> new MinecraftRegistryBundle("../27.1", "created", List.of(fixtureCatalog())));

        Map<String, String> oversized = new HashMap<>();
        for (int index = 0; index <= MinecraftRegistryBundle.MAX_ENTRY_METADATA; index++) {
            oversized.put("key_" + index, "value");
        }
        assertThrows(IllegalArgumentException.class,
            () -> entry("minecraft:zombie", "Zombie", "Zombie", null, null, oversized));
        assertThrows(IllegalArgumentException.class,
            () -> entry("minecraft:zombie", "Zombie", "Zombie", null, null, Map.of("Bad Key", "value")));
        assertThrows(IllegalArgumentException.class,
            () -> entry("minecraft:zombie", "Zombie", "Zombie", null, null, Map.of("key", " ")));
    }

    @Test
    void decoderRejectsMalformedUnknownAndNoncanonicalInput() {
        MinecraftRegistryBundle bundle = new MinecraftRegistryBundle("26.3", "created", List.of(
            catalog("items", "Items", "Items", List.of(entry("minecraft:stone", "Stone", "Stone", null, null, Map.of()))),
            fixtureCatalog()));
        String canonical = CODEC.encodeText(bundle);

        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(canonical + "\n"));
        assertThrows(IllegalArgumentException.class,
            () -> CODEC.decodeText(canonical.replace("\"artifactFamily\":\"minecraft_registry\"",
                "\"artifactFamily\":\"server_settings\"")));
        assertThrows(IllegalArgumentException.class,
            () -> CODEC.decodeText(canonical.replace("\"formatVersion\":1", "\"formatVersion\":2")));
        assertThrows(IllegalArgumentException.class,
            () -> CODEC.decodeText(canonical.substring(0, canonical.length() - 1) + ",\"unknown\":true}"));
        assertThrows(IllegalArgumentException.class,
            () -> CODEC.decodeText(canonical.replace("\"metadata\":{}", "\"metadata\":[]")));

        String entities = CODEC.encodeText(new MinecraftRegistryBundle("26.3", "created", List.of(fixtureCatalog())));
        String items = CODEC.encodeText(new MinecraftRegistryBundle("26.3", "created", List.of(
            catalog("items", "Items", "Items", List.of(entry("minecraft:stone", "Stone", "Stone", null, null, Map.of()))))));
        String entitiesCatalog = catalogArray(entities);
        String itemsCatalog = catalogArray(items);
        String reversed = canonical.replace(entitiesCatalog + "," + itemsCatalog, itemsCatalog + "," + entitiesCatalog);
        assertTrue(reversed.contains(itemsCatalog + "," + entitiesCatalog));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(reversed));
    }

    private static Catalog fixtureCatalog() {
        return catalog("entities", "Entities", "Entities", List.of(
            entry("minecraft:zombie", "Zombie", "Zombie", null, null, Map.of())));
    }

    private static Catalog catalog(String path, String label, String description, List<Entry> entries) {
        return new Catalog(CatalogId.of("minecraft", path), label, description, entries);
    }

    private static Entry entry(String value, String label, String description, String icon, String group,
                               Map<String, String> metadata) {
        return new Entry(value, label, description, icon, group, metadata);
    }

    private static Map<String, String> linkedMap(String firstKey, String firstValue, String secondKey, String secondValue) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }

    private static String catalogArray(String bundle) {
        int start = bundle.indexOf("\"catalogs\":[") + "\"catalogs\":[".length();
        int end = bundle.indexOf("],\"createdAt\"");
        return bundle.substring(start, end);
    }
}
