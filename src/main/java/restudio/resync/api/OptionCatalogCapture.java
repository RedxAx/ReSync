package restudio.resync.api;

import java.util.List;
import java.util.Objects;

public record OptionCatalogCapture(String revision, List<OptionCatalogItem> items, String status, String diagnostic) {
    public OptionCatalogCapture {
        revision = Objects.requireNonNull(revision, "Option catalog revision is required").strip();
        if (revision.isBlank()) {
            throw new IllegalArgumentException("Option catalog revision is required");
        }
        items = items == null ? List.of() : List.copyOf(items);
        status = status == null || status.isBlank() ? "available" : status.strip();
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public List<String> values() {
        return items.stream().map(OptionCatalogItem::value).toList();
    }
}
