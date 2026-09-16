package restudio.resync.api;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowTypeRef;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.CALLER;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                return new OptionCatalogCapture("1", List.of(
                    new OptionCatalogItem("ruby", "Ruby", "Rare gem", "", "Gems", Map.of("tags", List.of("rare"))),
                    new OptionCatalogItem("sapphire", "Sapphire")), "available", "");
            }

            @Override
            public String revision() {
                throw new AssertionError("Runtime data must use the coherent capture");
            }

            @Override
            public List<String> values() {
                throw new AssertionError("Runtime data must use the coherent capture");

            }

            @Override
            public List<OptionCatalogItem> items() {
                throw new AssertionError("Runtime data must use the coherent capture");
            }
        });

        assertEquals(List.of("ruby", "sapphire"), catalogs.values("server:test:gem", new OptionCatalogQuery("server:test:gem", Map.of())));
        assertEquals("ruby", runtimeData.query("gem", new RuntimeDataQuery(Set.of(), Set.of("gems"), Set.of("rare"), Set.of(), Set.of(),
            Map.of(), Map.of(), "", RuntimeDataQuery.MatchMode.ANY, RuntimeDataQuery.MatchMode.ALL, 0)).getFirst().id());
        assertFalse(runtimeData.domains().isEmpty());
    }

    @Test
    void preparedIoCaptureServesSynchronousConsumersWithoutRunningIoOnTheCaller() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        AtomicInteger affinityCaptures = new AtomicInteger();
        catalogs.bindCapture((provider, query) -> {
            affinityCaptures.incrementAndGet();
            return provider.capture(query);
        });
        catalogs.register(new OptionCatalogRegistry.PreparedCaptureProvider() {
            @Override
            public String sourceId() {
                return "server:test:prepared";
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.IO;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                throw new AssertionError("Synchronous consumers must not execute IO capture");
            }

            @Override
            public OptionCatalogCapture preparedCapture(OptionCatalogQuery query) {
                return new OptionCatalogCapture("prepared:1", List.of(new OptionCatalogItem("ready")), "available", "");
            }

            @Override
            public String revision() {
                throw new AssertionError("Synchronous consumers must use the prepared capture");
            }

            @Override
            public List<String> values() {
                throw new AssertionError("Synchronous consumers must use the prepared capture");
            }
        });

        assertEquals(List.of("ready"), catalogs.values("server:test:prepared",
            new OptionCatalogQuery("server:test:prepared", Map.of())));
        assertEquals("ready", runtimeData.query("prepared", RuntimeDataQuery.all()).getFirst().id());
        assertEquals("prepared:1", runtimeData.adapter("server:test:prepared").revision());
        assertEquals(0, affinityCaptures.get());
    }

    @Test
    void boundCaptureAccessOwnsServerMainAffinityForRegistryAndRuntimeData() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        AtomicInteger affinityCaptures = new AtomicInteger();
        catalogs.bindCapture((provider, query) -> {
            affinityCaptures.incrementAndGet();
            return provider.capture(query);
        });
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:test:main";
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.SERVER_MAIN;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                return new OptionCatalogCapture("main:1", List.of(new OptionCatalogItem("main-thread")), "available", "");
            }

            @Override
            public String revision() {
                throw new AssertionError("Bound consumers must use affinity capture access");
            }

            @Override
            public List<String> values() {
                throw new AssertionError("Bound consumers must use affinity capture access");
            }
        });

        assertEquals(List.of("main-thread"), catalogs.values("server:test:main",
            new OptionCatalogQuery("server:test:main", Map.of())));
        assertEquals("main-thread", runtimeData.query("main", RuntimeDataQuery.all()).getFirst().id());
        assertEquals("main:1", runtimeData.adapter("server:test:main").revision());
        assertEquals(3, affinityCaptures.get());
    }

    @Test
    void unavailablePreparedCaptureFailsInsteadOfMasqueradingAsAnEmptyCatalog() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        catalogs.bindCapture((provider, query) -> provider.capture(query));
        catalogs.register(new OptionCatalogRegistry.PreparedCaptureProvider() {
            @Override
            public String sourceId() {
                return "server:test:stale";
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.IO;
            }

            @Override
            public OptionCatalogCapture preparedCapture(OptionCatalogQuery query) {
                return new OptionCatalogCapture("stale:unavailable", List.of(), "unavailable", "Awaiting refresh");
            }

            @Override
            public String revision() {
                return "stale:unavailable";
            }

            @Override
            public List<String> values() {
                return List.of();
            }
        });

        OptionCatalogRegistry.CaptureUnavailable catalogFailure = assertThrows(OptionCatalogRegistry.CaptureUnavailable.class,
            () -> catalogs.values("server:test:stale", new OptionCatalogQuery("server:test:stale", Map.of())));
        OptionCatalogRegistry.CaptureUnavailable runtimeFailure = assertThrows(OptionCatalogRegistry.CaptureUnavailable.class,
            () -> runtimeData.query("stale", RuntimeDataQuery.all()));

        assertEquals("Awaiting refresh", catalogFailure.getMessage());
        assertEquals("Awaiting refresh", runtimeFailure.getMessage());
    }

    @Test
    void unsupportedProviderFailsWithoutCallingSplitRawMethods() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        AtomicInteger rawCalls = new AtomicInteger();
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:test:unsupported";
            }

            @Override
            public String revision() {
                rawCalls.incrementAndGet();
                return "raw";
            }

            @Override
            public String revision(OptionCatalogQuery query) {
                rawCalls.incrementAndGet();
                return "raw";
            }

            @Override
            public List<String> values() {
                rawCalls.incrementAndGet();
                return List.of("raw");
            }

            @Override
            public List<OptionCatalogItem> items(OptionCatalogQuery query) {
                rawCalls.incrementAndGet();
                return List.of(new OptionCatalogItem("raw"));
            }

            @Override
            public String status(OptionCatalogQuery query) {
                rawCalls.incrementAndGet();
                return "available";
            }

            @Override
            public String diagnostic(OptionCatalogQuery query) {
                rawCalls.incrementAndGet();
                return "raw";
            }
        });

        assertThrows(OptionCatalogRegistry.CaptureUnavailable.class, () -> catalogs.values("server:test:unsupported",
            new OptionCatalogQuery("server:test:unsupported", Map.of())));
        assertThrows(OptionCatalogRegistry.CaptureUnavailable.class,
            () -> runtimeData.query("unsupported", RuntimeDataQuery.all()));
        assertThrows(OptionCatalogRegistry.CaptureUnavailable.class,
            () -> runtimeData.adapter("server:test:unsupported").revision());
        assertEquals(0, rawCalls.get());
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
