package restudio.resync.customcontent;

import org.junit.jupiter.api.Test;
import restudio.resync.metadata.MinecraftSchemaBundle;
import restudio.resync.metadata.MinecraftSchemaBundle.ListValue;
import restudio.resync.metadata.MinecraftSchemaBundle.OpaqueValue;
import restudio.resync.metadata.MinecraftSchemaBundle.RecordValue;
import restudio.resync.metadata.MinecraftSchemaBundle.RegistryReferenceValue;
import restudio.resync.metadata.MinecraftSchemaBundle.RuntimeIdentity;
import restudio.resync.metadata.MinecraftSchemaBundleCodec;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftSchemaExporterTest {
    @Test
    void exportsRuntimeComponentsAndAttributesThroughPortableContract() {
        Map<String, Object> modifier = Map.of(
            "type", "minecraft:attack_damage",
            "amount", 1.0,
            "operation", "add_value",
            "slot", "mainhand",
            "id", "resync:attack_damage");
        ItemAttributeSchemaService service = new ItemAttributeSchemaService(
            List.of("minecraft:attribute_modifiers", "minecraft:unbreakable"),
            Map.of("minecraft:attribute_modifiers", List.of(modifier), "minecraft:unbreakable", Map.of()), true);
        MinecraftSchemaExporter exporter = new MinecraftSchemaExporter(service);
        RuntimeIdentity runtime = new RuntimeIdentity("paper", "26.3", "git-Paper-130", "api");

        MinecraftSchemaBundle bundle = exporter.export("26.3", "2026-09-20T00:00:00Z", runtime,
            List.of("minecraft:attack_damage", "minecraft:max_health"), true);

        assertEquals(List.of("attribute/minecraft:attack_damage", "attribute/minecraft:max_health",
                "item_component/minecraft:attribute_modifiers", "item_component/minecraft:unbreakable"),
            bundle.schemas().stream().map(schema -> schema.key().canonicalText()).toList());
        MinecraftSchemaBundle.Schema attributeModifiers = bundle.schemas().get(2);
        ListValue list = assertInstanceOf(ListValue.class, attributeModifiers.value());
        RecordValue entry = assertInstanceOf(RecordValue.class, list.items());
        RegistryReferenceValue attribute = assertInstanceOf(RegistryReferenceValue.class, entry.fields().getFirst().value());
        assertEquals("minecraft:attribute", attribute.catalog().canonicalText());
        assertFalse(attributeModifiers.complete());
        assertTrue(attributeModifiers.evidence().stream().anyMatch(value -> value.kind().equals("runtime_registry")));
        assertTrue(attributeModifiers.evidence().stream().anyMatch(value -> value.kind().equals("paper_round_trip")));
        assertTrue(bundle.capabilities().stream().allMatch(MinecraftSchemaBundle.Capability::available));

        MinecraftSchemaBundleCodec codec = new MinecraftSchemaBundleCodec();
        assertEquals(bundle, codec.decodeBytes(codec.encodeBytes(bundle)));
        assertArrayEquals(codec.encodeBytes(bundle), codec.encodeBytes(exporter.export("26.3",
            "2026-09-20T00:00:00Z", runtime, List.of("minecraft:attack_damage", "minecraft:max_health"), true)));
    }

    @Test
    void preservesUnknownRuntimeShapesAsOpaqueEvidence() {
        ItemAttributeSchemaService service = new ItemAttributeSchemaService(
            List.of("minecraft:future_component"), Map.of(), true);
        MinecraftSchemaBundle bundle = new MinecraftSchemaExporter(service).export("26.3", "2026-09-20T00:00:00Z",
            new RuntimeIdentity("paper", "26.3", "git-Paper-130", "api"), List.of(), true);

        MinecraftSchemaBundle.Schema schema = bundle.schemas().getFirst();
        assertInstanceOf(OpaqueValue.class, schema.value());
        assertTrue(schema.evidence().stream().anyMatch(value -> value.kind().equals("runtime_shape_unavailable")));
        assertFalse(schema.evidence().stream().anyMatch(value -> value.kind().equals("sample_inference")));
    }

    @Test
    void refusesToLabelRuntimeEvidenceAsAnotherMinecraftVersion() {
        ItemAttributeSchemaService service = new ItemAttributeSchemaService(List.of(), Map.of(), false);
        MinecraftSchemaExporter exporter = new MinecraftSchemaExporter(service);
        RuntimeIdentity runtime = new RuntimeIdentity("paper", "26.2", "git-Paper-120", "api");

        assertThrows(IllegalArgumentException.class, () -> exporter.export("26.3", "2026-09-20T00:00:00Z",
            runtime, List.of(), false));
    }
}
