package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.customcontent.ItemAttributeSchemaService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinOptionCatalogServiceTest {
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
}
