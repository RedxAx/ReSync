package restudio.resync.api;

import java.util.Map;

public record OptionCatalogItem(String value, String label, String description, String icon, String group, Map<String, Object> metadata) {
    public OptionCatalogItem(String value) {
        this(value, value, "", "", "", Map.of());
    }

    public OptionCatalogItem(String value, String label) {
        this(value, label, "", "", "", Map.of());
    }

    public OptionCatalogItem {
        value = value != null ? value : "";
        label = label != null && !label.isBlank() ? label : value;
        description = description != null ? description : "";
        icon = icon != null ? icon : "";
        group = group != null ? group : "";
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
