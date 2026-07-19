package restudio.resync.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OptionCatalogProviderContextTest {
    @Test
    void providerCanResolveValuesFromSiblingInputs() {
        OptionCatalogProvider provider = new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:test:mode_items";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of();
            }

            @Override
            public List<String> values(OptionCatalogQuery query) {
                return "creative".equals(query.text("mode")) ? List.of("barrier") : List.of("stone");
            }
        };

        OptionCatalogQuery query = new OptionCatalogQuery(provider.sourceId(), Map.of("mode", "creative"));

        assertEquals(List.of("barrier"), provider.values(query));
        assertEquals("barrier", provider.items(query).getFirst().value());
    }
}
