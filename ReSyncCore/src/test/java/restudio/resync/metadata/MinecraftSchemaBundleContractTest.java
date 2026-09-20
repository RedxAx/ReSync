package restudio.resync.metadata;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.metadata.MinecraftSchemaBundle.Applicability;
import restudio.resync.metadata.MinecraftSchemaBundle.Capability;
import restudio.resync.metadata.MinecraftSchemaBundle.EnumValue;
import restudio.resync.metadata.MinecraftSchemaBundle.Evidence;
import restudio.resync.metadata.MinecraftSchemaBundle.Field;
import restudio.resync.metadata.MinecraftSchemaBundle.ListValue;
import restudio.resync.metadata.MinecraftSchemaBundle.OpaqueValue;
import restudio.resync.metadata.MinecraftSchemaBundle.Presentation;
import restudio.resync.metadata.MinecraftSchemaBundle.PrimitiveKind;
import restudio.resync.metadata.MinecraftSchemaBundle.PrimitiveValue;
import restudio.resync.metadata.MinecraftSchemaBundle.RecordValue;
import restudio.resync.metadata.MinecraftSchemaBundle.RegistryReferenceValue;
import restudio.resync.metadata.MinecraftSchemaBundle.ResourceLocationValue;
import restudio.resync.metadata.MinecraftSchemaBundle.RuntimeIdentity;
import restudio.resync.metadata.MinecraftSchemaBundle.Schema;
import restudio.resync.metadata.MinecraftSchemaBundle.SchemaKey;
import restudio.resync.metadata.MinecraftSchemaBundle.UnionValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinecraftSchemaBundleContractTest {
    private static final MinecraftSchemaBundleCodec CODEC = new MinecraftSchemaBundleCodec();

    @Test
    void writesExactCanonicalSchemaBytes() {
        MinecraftSchemaBundle bundle = bundle();
        String expected = "{\"artifactFamily\":\"minecraft_schema\",\"capabilities\":[{\"available\":true,"
            + "\"id\":\"data_components\"}],\"createdAt\":\"2026-09-20T00:00:00Z\",\"formatVersion\":1,"
            + "\"minecraftVersion\":\"26.3\",\"runtime\":{\"apiVersion\":\"1.21.10-R0.1-SNAPSHOT\","
            + "\"build\":\"git-Paper-130\",\"softwareFamily\":\"paper\",\"softwareVersion\":\"26.3\"},"
            + "\"schemas\":[{\"applicability\":{\"categories\":[\"armor\",\"tool\",\"weapon\"],"
            + "\"universal\":false,\"values\":[]},\"complete\":true,\"description\":\"Stats And Equipment Slots\","
            + "\"domain\":\"item_component\",\"evidence\":[{\"kind\":\"paper_round_trip\","
            + "\"source\":\"paper:26.3\"},{\"kind\":\"runtime_registry\",\"source\":\"paper:26.3\"}],"
            + "\"examples\":[],\"id\":\"minecraft:attribute_modifiers\",\"label\":\"Attribute Modifiers\","
            + "\"presentation\":{\"category\":\"Combat\",\"editor\":\"list\",\"priority\":350,"
            + "\"search\":[\"attribute\",\"modifier\"]},\"value\":{\"items\":{\"additionalFields\":false,"
            + "\"fields\":[{\"description\":\"Attribute to modify.\",\"name\":\"type\",\"required\":true,"
            + "\"value\":{\"catalog\":\"minecraft:attribute\",\"tagsAllowed\":false,\"type\":\"registry_ref\"}},"
            + "{\"description\":\"Modifier amount.\",\"name\":\"amount\",\"required\":true,\"value\":{"
            + "\"primitive\":\"decimal\",\"type\":\"primitive\"}},{\"description\":\"Modifier operation.\","
            + "\"name\":\"operation\",\"required\":true,\"value\":{\"type\":\"enum\",\"values\":["
            + "\"add_value\",\"add_multiplied_base\",\"add_multiplied_total\"]}},{\"description\":"
            + "\"Stable modifier identity.\",\"name\":\"id\",\"required\":true,\"value\":{\"type\":"
            + "\"resource_location\"}}],\"type\":\"record\"},\"type\":\"list\",\"uniqueItems\":false}}]}";

        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), CODEC.encodeBytes(bundle));
        assertEquals(bundle, CODEC.decodeText(expected));
    }

    @Test
    void freezesCollectionsAndSortsBundleIdentities() {
        List<Capability> capabilities = new ArrayList<>(List.of(
            new Capability("legacy_item_metadata", false, "Unavailable"),
            new Capability("data_components", true, null)));
        List<Schema> schemas = new ArrayList<>(bundle().schemas());
        MinecraftSchemaBundle bundle = new MinecraftSchemaBundle("26.3", "created",
            new RuntimeIdentity("paper", "26.3", "build", "api"), capabilities, schemas);
        capabilities.clear();
        schemas.clear();

        assertEquals(List.of("data_components", "legacy_item_metadata"),
            bundle.capabilities().stream().map(Capability::id).toList());
        assertEquals(1, bundle.schemas().size());
        assertThrows(UnsupportedOperationException.class, () -> bundle.schemas().clear());
    }

    @Test
    void rejectsDuplicateAndMalformedContracts() {
        Schema schema = bundle().schemas().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new MinecraftSchemaBundle("26.3", "created",
            new RuntimeIdentity("paper", "26.3", "build", "api"), List.of(), List.of(schema, schema)));
        assertThrows(IllegalArgumentException.class, () -> new RecordValue(List.of(
            new Field("value", "Value.", PrimitiveValue.string(), true, null),
            new Field("value", "Value.", PrimitiveValue.string(), true, null)), false));
        assertThrows(IllegalArgumentException.class, () -> new EnumValue(List.of(JsonValue.of("same"), JsonValue.of("same"))));
        assertThrows(IllegalArgumentException.class, () -> new UnionValue(List.of(PrimitiveValue.string(), PrimitiveValue.string())));
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveValue(PrimitiveKind.INTEGER,
            new BigDecimal("1.5"), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Capability("missing", false, null));
        assertThrows(IllegalArgumentException.class, () -> new Applicability(true, List.of("minecraft:stone"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftSchemaBundle("26.3", "created",
            new RuntimeIdentity("paper", "26.3", "build", "api"), List.of(), List.of(new Schema(
                new SchemaKey("item_component", CatalogId.of("minecraft", "unknown")), "Unknown", "Unknown.",
                new OpaqueValue("Unknown shape"), Applicability.any(),
                new Presentation("Other", "schema", 100, List.of()),
                List.of(new Evidence("runtime_registry", "paper:26.3", null)), null, List.of(), true))));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(CODEC.encodeText(bundle()) + "\n"));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(CODEC.encodeText(bundle())
            .replace("\"artifactFamily\":\"minecraft_schema\"", "\"artifactFamily\":\"minecraft_registry\"")));
    }

    private static MinecraftSchemaBundle bundle() {
        RecordValue modifier = new RecordValue(List.of(
            new Field("type", "Attribute to modify.", new RegistryReferenceValue(CatalogId.of("minecraft", "attribute"), false), true, null),
            new Field("amount", "Modifier amount.", PrimitiveValue.decimal(), true, null),
            new Field("operation", "Modifier operation.", new EnumValue(List.of(JsonValue.of("add_value"),
                JsonValue.of("add_multiplied_base"), JsonValue.of("add_multiplied_total"))), true, null),
            new Field("id", "Stable modifier identity.", new ResourceLocationValue(), true, null)), false);
        Schema schema = new Schema(new SchemaKey("item_component", CatalogId.of("minecraft", "attribute_modifiers")),
            "Attribute Modifiers", "Stats And Equipment Slots", new ListValue(modifier, null, null, false),
            new Applicability(false, List.of(), List.of("weapon", "armor", "tool")),
            new Presentation("Combat", "list", 350, List.of("modifier", "attribute")),
            List.of(new Evidence("runtime_registry", "paper:26.3", null),
                new Evidence("paper_round_trip", "paper:26.3", null)), null, List.of(), true);
        return new MinecraftSchemaBundle("26.3", "2026-09-20T00:00:00Z",
            new RuntimeIdentity("paper", "26.3", "git-Paper-130", "1.21.10-R0.1-SNAPSHOT"),
            List.of(new Capability("data_components", true, null)), List.of(schema));
    }
}
