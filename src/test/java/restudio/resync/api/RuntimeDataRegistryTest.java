package restudio.resync.api;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowTypeRef;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDataRegistryTest {
    @Test
    void registryCombinesAdaptersAndAppliesOneSharedQueryContract() {
        RuntimeDataRegistry registry = new RuntimeDataRegistry();
        assertTrue(registry.register(adapter("test:vanilla", List.of(
            record("stone", "Stone", Set.of("blocks", "vanilla"), Set.of("building"), Map.of("solid", true)),
            record("apple", "Apple", Set.of("food", "vanilla"), Set.of("edible"), Map.of("solid", false))
        ))));
        assertTrue(registry.register(adapter("test:custom", List.of(
            record("ruby_sword", "Ruby Sword", Set.of("weapons", "custom"), Set.of("rare"), Map.of("solid", false))
        ))));

        RuntimeDataQuery query = new RuntimeDataQuery(Set.of(), Set.of("custom", "weapons"), Set.of("rare"), Set.of(), Set.of(),
            Map.of("solid", false), Map.of(), "ruby", RuntimeDataQuery.MatchMode.ALL, RuntimeDataQuery.MatchMode.ALL, 10);
        List<RuntimeDataRecord> records = registry.query("item", query);

        assertEquals(1, records.size());
        assertEquals("ruby_sword", records.getFirst().id());
        assertEquals("resolved:ruby_sword:3", registry.resolve(records.getFirst(), 3));
        assertTrue(registry.categories("item", RuntimeDataQuery.all()).stream().anyMatch(category -> category.id().equals("vanilla") && category.count() == 2));
    }

    @Test
    void optionCatalogCompatibilityReadsThroughTheRuntimeRegistry() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:test:gem";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of("ruby", "sapphire");
            }

            @Override
            public List<OptionCatalogItem> items() {
                return List.of(new OptionCatalogItem("ruby", "Ruby", "Rare gem", "", "Gems", Map.of("tags", List.of("rare"))),
                    new OptionCatalogItem("sapphire", "Sapphire"));
            }
        });

        assertEquals(List.of("ruby", "sapphire"), catalogs.values("server:test:gem", new OptionCatalogQuery("server:test:gem", Map.of())));
        assertEquals("ruby", runtimeData.query("gem", new RuntimeDataQuery(Set.of(), Set.of("gems"), Set.of("rare"), Set.of(), Set.of(),
            Map.of(), Map.of(), "", RuntimeDataQuery.MatchMode.ANY, RuntimeDataQuery.MatchMode.ALL, 0)).getFirst().id());
        assertFalse(runtimeData.domains().isEmpty());
    }

    private static RuntimeDataAdapter<String> adapter(String id, List<RuntimeDataRecord> records) {
        return new RuntimeDataAdapter<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String domain() {
                return "item";
            }

            @Override
            public FlowTypeRef valueType() {
                return FlowTypeRef.simple("string");
            }

            @Override
            public Class<String> valueClass() {
                return String.class;
            }

            @Override
            public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
                return records;
            }

            @Override
            public String resolve(RuntimeDataRecord record, int amount) {
                return "resolved:" + record.id() + ":" + amount;
            }
        };
    }

    private static RuntimeDataRecord record(String id, String label, Set<String> categories, Set<String> tags, Map<String, Object> attributes) {
        return new RuntimeDataRecord("item", "", id, label, "", categories, tags, attributes);
    }
}
