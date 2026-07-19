package restudio.resync.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record RuntimeDataCategory(String id, String label, long count, Set<String> adapters) {
    public RuntimeDataCategory {
        id = id != null ? id : "";
        label = label != null && !label.isBlank() ? label : id;
        count = Math.max(0, count);
        adapters = adapters != null ? Set.copyOf(adapters) : Set.of();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("label", label);
        value.put("count", count);
        value.put("sources", adapters.stream().toList());
        return value;
    }
}
