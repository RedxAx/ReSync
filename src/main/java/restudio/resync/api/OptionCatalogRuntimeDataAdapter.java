package restudio.resync.api;

import restudio.flow.data.FlowTypeRef;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class OptionCatalogRuntimeDataAdapter implements RuntimeDataAdapter<Object> {
    private final OptionCatalogProvider provider;

    OptionCatalogRuntimeDataAdapter(OptionCatalogProvider provider) {
        this.provider = provider;
    }

    @Override
    public String id() {
        return provider.sourceId();
    }

    @Override
    public String domain() {
        return provider.runtimeDataDomain();
    }

    @Override
    public FlowTypeRef valueType() {
        return provider.runtimeDataType();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<Object> valueClass() {
        return (Class<Object>) provider.runtimeDataClass();
    }

    @Override
    public String revision() {
        return provider.revision();
    }

    @Override
    public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
        OptionCatalogQuery catalogQuery = new OptionCatalogQuery(provider.sourceId(), query != null ? query.context() : Map.of());
        List<OptionCatalogItem> items = provider.items(catalogQuery);
        return items != null ? items.stream().map(this::record).toList() : List.of();
    }

    @Override
    public Object resolve(RuntimeDataRecord record, int amount) {
        return record != null ? provider.resolveRuntimeData(record.id()) : null;
    }

    OptionCatalogItem item(RuntimeDataRecord record) {
        Map<String, Object> attributes = new LinkedHashMap<>(record.attributes());
        String icon = text(attributes.remove("$catalogIcon"));
        String group = text(attributes.remove("$catalogGroup"));
        return new OptionCatalogItem(record.id(), record.label(), record.description(), icon, group, attributes);
    }

    private RuntimeDataRecord record(OptionCatalogItem item) {
        Map<String, Object> attributes = new LinkedHashMap<>(item.metadata());
        attributes.put("$catalogIcon", item.icon());
        attributes.put("$catalogGroup", item.group());
        attributes.put("source", provider.sourceId());
        Set<String> categories = new LinkedHashSet<>();
        add(categories, item.group());
        addValues(categories, item.metadata().get("category"));
        addValues(categories, item.metadata().get("categories"));
        Set<String> tags = new LinkedHashSet<>();
        addValues(tags, item.metadata().get("tags"));
        return new RuntimeDataRecord(domain(), id(), item.value(), item.label(), item.description(), categories, tags, attributes);
    }

    private static void addValues(Set<String> target, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> add(target, item));
        } else {
            add(target, value);
        }
    }

    private static void add(Set<String> target, Object value) {
        String normalized = text(value).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!normalized.isBlank()) {
            target.add(normalized);
        }
    }

    private static String text(Object value) {
        return value != null ? value.toString() : "";
    }
}
