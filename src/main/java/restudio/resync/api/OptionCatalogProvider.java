package restudio.resync.api;

import restudio.flow.data.FlowTypeRef;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public interface OptionCatalogProvider {
    String sourceId();

    default String providerId() {
        String sourceId = sourceId();
        if (sourceId == null || sourceId.isBlank()) {
            return "unknown";
        }
        String[] segments = sourceId.split(":");
        if (segments.length >= 3 && ("server".equalsIgnoreCase(segments[0]) || "client".equalsIgnoreCase(segments[0]))) {
            return segments[1];
        }
        return segments[0];
    }

    default String runtimeDataDomain() {
        String sourceId = sourceId();
        int separator = sourceId != null ? sourceId.lastIndexOf(':') : -1;
        return separator >= 0 ? sourceId.substring(separator + 1) : sourceId;
    }

    default FlowTypeRef runtimeDataType() {
        return FlowTypeRef.simple("string");
    }

    default Class<?> runtimeDataClass() {
        return String.class;
    }

    default Object resolveRuntimeData(String value) {
        return value;
    }

    default String widgetType() {
        return "SEARCHABLE_LIST";
    }

    default boolean searchable() {
        return true;
    }

    default Set<String> contextKeys() {
        return Set.of();
    }

    default String status(OptionCatalogQuery query) {
        return "available";
    }

    default String diagnostic(OptionCatalogQuery query) {
        return "";
    }

    String revision();

    default String revision(OptionCatalogQuery query) {
        return revision();
    }

    List<String> values();

    default List<String> values(OptionCatalogQuery query) {
        return values();
    }

    default List<OptionCatalogItem> items() {
        List<String> values = values();
        return values != null ? values.stream().map(OptionCatalogItem::new).toList() : List.of();
    }

    default List<OptionCatalogItem> items(OptionCatalogQuery query) {
        List<String> contextualValues = values(query);
        if (query == null || query.context().isEmpty() || Objects.equals(contextualValues, values())) {
            return items();
        }
        return contextualValues != null ? contextualValues.stream().map(OptionCatalogItem::new).toList() : List.of();
    }
}
