package restudio.resync.api;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public record OptionCatalogQuery(String sourceId, Map<String, Object> context) {
    public OptionCatalogQuery {
        sourceId = sourceId != null ? sourceId : "";
        context = context != null ? Collections.unmodifiableMap(new LinkedHashMap<>(context)) : Map.of();
    }

    public Object value(String key) {
        return context.get(key);
    }

    public String text(String key) {
        Object value = context.get(key);
        return value != null ? value.toString() : "";
    }
}
