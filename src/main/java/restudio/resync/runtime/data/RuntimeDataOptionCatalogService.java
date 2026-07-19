package restudio.resync.runtime.data;

import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.RuntimeDataAdapter;
import restudio.resync.api.RuntimeDataCategory;
import restudio.resync.api.RuntimeDataQuery;
import restudio.resync.api.RuntimeDataRegistry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuntimeDataOptionCatalogService {
    public static final String TYPE_SOURCE = "server:runtime_data:type";
    public static final String SOURCE_SOURCE = "server:runtime_data:source";
    public static final String CATEGORY_SOURCE = "server:runtime_data:category";
    private final RuntimeDataRegistry runtimeData;

    public RuntimeDataOptionCatalogService(RuntimeDataRegistry runtimeData) {
        this.runtimeData = runtimeData;
    }

    public void registerProviders(OptionCatalogRegistry catalogs) {
        catalogs.register(typeProvider());
        catalogs.register(sourceProvider());
        catalogs.register(categoryProvider());
    }

    private OptionCatalogProvider typeProvider() {
        return provider(TYPE_SOURCE, Set.of(), query -> runtimeData.domains().stream()
            .map(domain -> new OptionCatalogItem(domain, RuntimeDataLabels.label(domain))).toList());
    }

    private OptionCatalogProvider sourceProvider() {
        return provider(SOURCE_SOURCE, Set.of("data_type"), query -> runtimeData.adapters(dataType(query)).stream()
            .map(adapter -> new OptionCatalogItem(adapter.id(), RuntimeDataLabels.label(adapter.id()), adapter.valueType().toString(), "",
                RuntimeDataLabels.label(adapter.domain()), Map.of("domain", adapter.domain(), "capabilities", adapter.capabilities().stream().map(Enum::name).toList())))
            .toList());
    }

    private OptionCatalogProvider categoryProvider() {
        return provider(CATEGORY_SOURCE, Set.of("data_type", "source", "sources"), query -> {
            Set<String> adapters = new LinkedHashSet<>();
            add(adapters, query != null ? query.value("source") : null);
            add(adapters, query != null ? query.value("sources") : null);
            RuntimeDataQuery runtimeQuery = RuntimeDataQuery.all().withAdapters(adapters);
            return runtimeData.categories(dataType(query), runtimeQuery).stream().map(this::categoryItem).toList();
        });
    }

    private OptionCatalogItem categoryItem(RuntimeDataCategory category) {
        return new OptionCatalogItem(category.id(), category.label(), category.count() + " Values", "", "Categories",
            Map.of("count", category.count(), "sources", category.adapters()));
    }

    private OptionCatalogProvider provider(String sourceId, Set<String> contextKeys, CatalogValues values) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public String runtimeDataDomain() {
                return "";
            }

            @Override
            public Set<String> contextKeys() {
                return contextKeys;
            }

            @Override
            public String revision() {
                return sourceId + ":" + runtimeData.domains().hashCode();
            }

            @Override
            public String revision(OptionCatalogQuery query) {
                List<OptionCatalogItem> items = items(query);
                return sourceId + ":" + items.size() + ":" + items.hashCode();
            }

            @Override
            public List<String> values() {
                return items().stream().map(OptionCatalogItem::value).toList();
            }

            @Override
            public List<String> values(OptionCatalogQuery query) {
                return items(query).stream().map(OptionCatalogItem::value).toList();
            }

            @Override
            public List<OptionCatalogItem> items() {
                return values.items(new OptionCatalogQuery(sourceId, Map.of()));
            }

            @Override
            public List<OptionCatalogItem> items(OptionCatalogQuery query) {
                return values.items(query != null ? query : new OptionCatalogQuery(sourceId, Map.of()));
            }
        };
    }

    private String dataType(OptionCatalogQuery query) {
        String dataType = query != null ? query.text("data_type") : "";
        return dataType.isBlank() ? "item" : dataType;
    }

    private static void add(Set<String> values, Object source) {
        if (source instanceof Iterable<?> iterable) {
            iterable.forEach(item -> add(values, item));
            return;
        }
        if (source != null) {
            for (String value : source.toString().split("[,\\r\\n]")) {
                if (!value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
    }

    @FunctionalInterface
    private interface CatalogValues {
        List<OptionCatalogItem> items(OptionCatalogQuery query);
    }
}
