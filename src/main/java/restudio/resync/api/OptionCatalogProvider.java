package restudio.resync.api;

import java.util.List;

public interface OptionCatalogProvider {
    String sourceId();

    String revision();

    List<String> values();

    default List<OptionCatalogItem> items() {
        List<String> values = values();
        return values != null ? values.stream().map(OptionCatalogItem::new).toList() : List.of();
    }
}
