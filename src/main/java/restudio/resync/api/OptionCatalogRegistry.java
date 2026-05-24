package restudio.resync.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OptionCatalogRegistry {
    private final Map<String, OptionCatalogProvider> providers = new ConcurrentHashMap<>();

    public void register(OptionCatalogProvider provider) {
        if (provider == null || provider.sourceId() == null || provider.sourceId().isBlank()) {
            return;
        }
        providers.put(normalize(provider.sourceId()), provider);
    }

    public void unregister(String sourceId) {
        if (sourceId != null) {
            providers.remove(normalize(sourceId));
        }
    }

    public OptionCatalogProvider provider(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        return providers.get(normalize(sourceId));
    }

    public List<OptionCatalogProvider> providers() {
        return List.copyOf(providers.values());
    }

    private String normalize(String sourceId) {
        return sourceId.toLowerCase(Locale.ROOT);
    }
}
