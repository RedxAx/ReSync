package restudio.resync.api;

import restudio.flow.data.FlowTypeRef;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class OptionCatalogRuntimeDataAdapter implements RuntimeDataAdapter<Object> {
    private final OptionCatalogProvider provider;
    private final OptionCatalogRegistry.CaptureRouter captures;
    private final OptionCatalogProvider categoryCatalog;

    OptionCatalogRuntimeDataAdapter(OptionCatalogProvider provider, OptionCatalogRegistry.CaptureRouter captures) {
        this.provider = provider;
        this.captures = captures;
        this.categoryCatalog = new CategoryCatalog();
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
        return sourceCapture(Map.of()).revision();
    }

    @Override
    public OptionCatalogProvider categoryCatalog() {
        return categoryCatalog;
    }

    @Override
    public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
        OptionCatalogCapture capture = sourceCapture(query != null ? query.context() : Map.of());
        if (!"available".equalsIgnoreCase(capture.status())) {
            String message = capture.diagnostic().isBlank()
                ? "Option catalog capture is " + capture.status()
                : capture.diagnostic();
            throw new OptionCatalogRegistry.CaptureUnavailable(capture.status(), message);
        }
        return capture.items().stream().map(this::record).toList();
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
        Set<String> tags = new LinkedHashSet<>();
        addValues(tags, item.metadata().get("tags"));
        return new RuntimeDataRecord(domain(), id(), item.value(), item.label(), item.description(), categories(item), tags, attributes);
    }

    private OptionCatalogCapture sourceCapture(Map<String, Object> context) {
        return captures.capture(provider, new OptionCatalogQuery(provider.sourceId(), context));
    }

    private Set<String> categories(OptionCatalogItem item) {
        Set<String> categories = new LinkedHashSet<>();
        add(categories, item.group());
        addValues(categories, item.metadata().get("category"));
        addValues(categories, item.metadata().get("categories"));
        return categories;
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

    private static String label(String value) {
        StringBuilder result = new StringBuilder();
        for (String part : value.replace(':', '_').split("_")) {
            if (part.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private final class CategoryCatalog implements OptionCatalogProvider {
        @Override
        public String sourceId() {
            return id() + ":category";
        }

        @Override
        public String runtimeDataDomain() {
            return "";
        }

        @Override
        public Set<String> contextKeys() {
            Set<String> contextKeys = provider.contextKeys();
            return contextKeys != null ? Set.copyOf(contextKeys) : Set.of();
        }

        @Override
        public CaptureAffinity captureAffinity() {
            return CaptureAffinity.SERVER_MAIN;
        }

        @Override
        public OptionCatalogCapture capture(OptionCatalogQuery query) {
            OptionCatalogCapture source = sourceCapture(query != null ? query.context() : Map.of());
            if (!"available".equalsIgnoreCase(source.status())) {
                return categoryCapture(source, List.of());
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (OptionCatalogItem item : source.items()) {
                if (item != null) {
                    categories(item).forEach(category -> counts.merge(category, 1, Integer::sum));
                }
            }
            List<OptionCatalogItem> items = counts.entrySet().stream()
                .map(entry -> new OptionCatalogItem(entry.getKey(), label(entry.getKey()), entry.getValue() + " Values", "",
                    "Categories", Map.of("count", entry.getValue(), "sources", List.of(id()))))
                .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.label(), right.label()))
                .toList();
            return categoryCapture(source, items);
        }

        @Override
        public String revision() {
            return capture(defaultQuery()).revision();
        }

        @Override
        public String revision(OptionCatalogQuery query) {
            return capture(query != null ? query : defaultQuery()).revision();
        }

        @Override
        public List<String> values() {
            return capture(defaultQuery()).values();
        }

        @Override
        public List<String> values(OptionCatalogQuery query) {
            return capture(query != null ? query : defaultQuery()).values();
        }

        @Override
        public List<OptionCatalogItem> items() {
            return capture(defaultQuery()).items();
        }

        @Override
        public List<OptionCatalogItem> items(OptionCatalogQuery query) {
            return capture(query != null ? query : defaultQuery()).items();
        }

        private OptionCatalogQuery defaultQuery() {
            return new OptionCatalogQuery(sourceId(), Map.of());
        }

        private OptionCatalogCapture categoryCapture(OptionCatalogCapture source, List<OptionCatalogItem> items) {
            Objects.requireNonNull(source, "Source option catalog capture is required");
            String revision = sourceId() + ":" + source.revision() + ":" + source.status() + ":"
                + source.diagnostic().hashCode() + ":" + items.size() + ":" + items.hashCode();
            return new OptionCatalogCapture(revision, items, source.status(), source.diagnostic());
        }
    }
}
