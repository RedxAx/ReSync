package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.customcontent.ItemAttributeSchemaService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltinOptionCatalogServiceTest {
    @Test
    void raycastWildcardIsAnAuthoritativeChoiceOnlyInItsBlockFilter() {
        MockBukkit.mock();
        try {
            OptionCatalogRegistry registry = new OptionCatalogRegistry();
            new BuiltinOptionCatalogService(() -> null, new ItemAttributeSchemaService()).registerProviders(registry);
            OptionCatalogProvider blocks = registry.provider("server:minecraft:block");
            OptionCatalogQuery raycast = new OptionCatalogQuery(blocks.sourceId(), Map.of("$nodeType", "ability_raycast"));
            OptionCatalogQuery other = new OptionCatalogQuery(blocks.sourceId(), Map.of("$nodeType", "block_set_type"));

            OptionCatalogCapture capture = blocks.capture(raycast);
            assertTrue(capture.values().contains("any"));
            assertTrue(capture.values().contains("stone"));
            assertFalse(capture.values().contains("diamond_sword"));
            assertEquals(capture.items(), blocks.items(raycast));
            assertEquals(capture.revision(), blocks.revision(raycast));
            assertFalse(blocks.capture(other).values().contains("any"));
            assertFalse(blocks.capture(null).values().contains("any"));
            assertFalse(registry.provider("server:minecraft:material").capture(raycast).values().contains("any"));
            assertTrue(blocks.contextKeys().contains("$nodeType"));
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void immutableMaterialCaptureRemainsAvailableOffThreadAfterAdmission() {
        MockBukkit.mock();
        try {
            OptionCatalogRegistry registry = new OptionCatalogRegistry();
            new BuiltinOptionCatalogService(() -> null, new ItemAttributeSchemaService()).registerProviders(registry);
            OptionCatalogProvider materials = registry.provider("server:minecraft:material");
            OptionCatalogQuery query = new OptionCatalogQuery(materials.sourceId(), Map.of());
            assertEquals(OptionCatalogProvider.CaptureAffinity.SERVER_MAIN, materials.captureAffinity(query));
            OptionCatalogCapture capture = materials.capture(query);
            assertEquals(OptionCatalogProvider.CaptureAffinity.CALLER, materials.captureAffinity(query));
            assertThrows(UnsupportedOperationException.class, () -> capture.items().clear());
            assertThrows(UnsupportedOperationException.class, () -> capture.items().getFirst().metadata().clear());
            assertEquals(capture, CompletableFuture.supplyAsync(() -> materials.capture(query)).join());
            assertEquals(capture.values(), materials.values(query));
            assertEquals(capture.revision(), materials.revision(query));
            assertEquals(OptionCatalogProvider.CaptureAffinity.SERVER_MAIN,
                registry.provider("server:minecraft:world").captureAffinity(query));
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void builtinCatalogItemsCarryRichDiscoveryMetadata() {
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        new BuiltinOptionCatalogService(() -> null, new ItemAttributeSchemaService()).registerProviders(registry);

        OptionCatalogProvider provider = registry.provider("server:resync:network_scope");
        OptionCatalogItem item = provider.items().stream().filter(candidate -> "NETWORK".equals(candidate.value())).findFirst().orElseThrow();

        assertEquals("Network", item.label());
        assertEquals("ReSync", item.group());
        assertFalse(item.description().isBlank());
        assertEquals("server:resync:network_scope", item.metadata().get("source"));
        assertEquals("resync", item.metadata().get("provider"));
        assertEquals("builtin", item.metadata().get("owner"));
        assertTrue(Boolean.TRUE.equals(item.metadata().get("available")));
        assertTrue(item.metadata().get("aliases") instanceof List<?> aliases && aliases.contains("NETWORK"));
    }

    @Test
    void contextualCustomContentCatalogReportsUnavailableAuthority() {
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        new BuiltinOptionCatalogService(() -> null, new ItemAttributeSchemaService()).registerProviders(registry);

        OptionCatalogProvider provider = registry.provider("server:custom_content:asset");
        OptionCatalogQuery query = new OptionCatalogQuery(provider.sourceId(), Map.of("provider", "nexo", "content_type", "item"));

        assertEquals("unavailable", provider.status(query));
        assertFalse(provider.diagnostic(query).isBlank());
        assertTrue(provider.values(query).isEmpty());
        assertTrue(provider.items(query).isEmpty());
    }

    @Test
    void serverSettingsCatalogsRegisterOnlyPublicAuthorities() {
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        new BuiltinOptionCatalogService(() -> null, new ItemAttributeSchemaService()).registerProviders(registry);

        assertNotNull(registry.provider("server:minecraft:data_component_type"));
        assertNotNull(registry.provider("server:minecraft:statistic"));
        assertNotNull(registry.provider("server:minecraft:command"));
        assertNull(registry.provider("server:minecraft:item_model"));
        assertNull(registry.provider("server:minecraft:serverbound_packet"));
        assertNull(registry.provider("server:minecraft:configured_feature"));
    }
}
