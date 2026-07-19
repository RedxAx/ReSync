package restudio.resync.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record RuntimeDataRecord(String domain, String adapterId, String id, String label, String description,
                                Set<String> categories, Set<String> tags, Map<String, Object> attributes) {
    public RuntimeDataRecord {
        domain = normalize(domain);
        adapterId = normalize(adapterId);
        id = id != null ? id : "";
        label = label != null && !label.isBlank() ? label : id;
        description = description != null ? description : "";
        categories = normalizedSet(categories);
        tags = normalizedSet(tags);
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    public RuntimeDataRecord withOwner(String domain, String adapterId) {
        return new RuntimeDataRecord(domain, adapterId, id, label, description, categories, tags, attributes);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", domain);
        value.put("source", adapterId);
        value.put("id", id);
        value.put("label", label);
        value.put("description", description);
        value.put("categories", categories.stream().toList());
        value.put("tags", tags.stream().toList());
        value.put("attributes", attributes);
        return value;
    }

    public static RuntimeDataRecord fromMap(Map<?, ?> value) {
        if (value == null) {
            return null;
        }
        Object source = value.containsKey("source") ? value.get("source") : value.get("adapter");
        Object type = value.containsKey("type") ? value.get("type") : value.get("domain");
        return new RuntimeDataRecord(text(type), text(source), text(value.get("id")),
            text(value.get("label")), text(value.get("description")), strings(value.get("categories")),
            strings(value.get("tags")), map(value.get("attributes")));
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static Set<String> normalizedSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalize(value);
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static Set<String> strings(Object value) {
        if (value instanceof Collection<?> collection) {
            Set<String> values = new LinkedHashSet<>();
            for (Object item : collection) {
                if (item != null) {
                    values.add(item.toString());
                }
            }
            return values;
        }
        if (value == null || value.toString().isBlank()) {
            return Set.of();
        }
        return Set.of(value.toString());
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Object value) {
        return value != null ? value.toString() : "";
    }
}
