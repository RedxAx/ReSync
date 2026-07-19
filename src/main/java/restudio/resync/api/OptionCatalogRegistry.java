package restudio.resync.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class OptionCatalogRegistry {
    private final Map<String, OptionCatalogProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, OptionCatalogRuntimeDataAdapter> catalogAdapters = new ConcurrentHashMap<>();
    private final List<RegistrationDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
    private final RuntimeDataRegistry runtimeData;

    public OptionCatalogRegistry() {
        this(new RuntimeDataRegistry());
    }

    public OptionCatalogRegistry(RuntimeDataRegistry runtimeData) {
        this.runtimeData = runtimeData != null ? runtimeData : new RuntimeDataRegistry();
    }

    public boolean register(OptionCatalogProvider provider) {
        if (provider == null || provider.sourceId() == null || provider.sourceId().isBlank()) {
            diagnostics.add(new RegistrationDiagnostic("INVALID_PROVIDER", "", "Catalog provider and source ID are required"));
            return false;
        }
        String sourceId = normalize(provider.sourceId());
        OptionCatalogProvider existing = providers.putIfAbsent(sourceId, provider);
        if (existing != null) {
            if (existing == provider) {
                return true;
            }
            diagnostics.add(new RegistrationDiagnostic("DUPLICATE_SOURCE", provider.sourceId(), "Catalog source is already registered by " + existing.providerId()));
            return false;
        }
        if (provider.runtimeDataDomain() != null && !provider.runtimeDataDomain().isBlank()) {
            OptionCatalogRuntimeDataAdapter adapter = new OptionCatalogRuntimeDataAdapter(provider);
            if (!runtimeData.register(adapter)) {
                providers.remove(sourceId, provider);
                diagnostics.add(new RegistrationDiagnostic("DUPLICATE_RUNTIME_DATA_ADAPTER", provider.sourceId(), "Runtime data adapter is already registered"));
                return false;
            }
            catalogAdapters.put(sourceId, adapter);
        }
        return true;
    }

    public void unregister(String sourceId) {
        if (sourceId != null) {
            providers.remove(normalize(sourceId));
            catalogAdapters.remove(normalize(sourceId));
            runtimeData.unregister(sourceId);
        }
    }

    public OptionCatalogProvider provider(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        return providers.get(normalize(sourceId));
    }

    public boolean contains(String sourceId) {
        return provider(sourceId) != null;
    }

    public List<OptionCatalogProvider> providers() {
        return providers.values().stream().sorted(Comparator.comparing(OptionCatalogProvider::sourceId, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public List<RegistrationDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public RuntimeDataRegistry runtimeData() {
        return runtimeData;
    }

    public List<OptionCatalogItem> items(String sourceId, OptionCatalogQuery query) {
        OptionCatalogProvider provider = provider(sourceId);
        if (provider == null) {
            return List.of();
        }
        RuntimeDataQuery runtimeQuery = new RuntimeDataQuery(Set.of(sourceId), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(),
            query != null ? query.context() : Map.of(), "", RuntimeDataQuery.MatchMode.ANY, RuntimeDataQuery.MatchMode.ALL, 0);
        OptionCatalogRuntimeDataAdapter adapter = catalogAdapters.get(normalize(sourceId));
        if (adapter == null) {
            List<OptionCatalogItem> items = provider.items(query);
            return items != null ? List.copyOf(items) : List.of();
        }
        return runtimeData.query(adapter.domain(), runtimeQuery).stream().map(adapter::item).toList();
    }

    public List<String> values(String sourceId, OptionCatalogQuery query) {
        return items(sourceId, query).stream().map(OptionCatalogItem::value).toList();
    }

    private String normalize(String sourceId) {
        return sourceId.toLowerCase(Locale.ROOT);
    }

    public record RegistrationDiagnostic(String code, String sourceId, String message) {
    }
}
