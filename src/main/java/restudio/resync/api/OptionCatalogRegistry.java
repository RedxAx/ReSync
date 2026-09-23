package restudio.resync.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class OptionCatalogRegistry {
    private final Map<String, OptionCatalogProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, OptionCatalogRuntimeDataAdapter> catalogAdapters = new ConcurrentHashMap<>();
    private final List<RegistrationDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
    private final RuntimeDataRegistry runtimeData;
    private final CaptureRouter captures;
    private volatile ExtensionRegistryActivation activation;

    public OptionCatalogRegistry() {
        this(new RuntimeDataRegistry(), new CaptureRouter());
    }

    public OptionCatalogRegistry(RuntimeDataRegistry runtimeData) {
        this(runtimeData, new CaptureRouter());
    }

    private OptionCatalogRegistry(RuntimeDataRegistry runtimeData, CaptureRouter captures) {
        this.runtimeData = runtimeData != null ? runtimeData : new RuntimeDataRegistry();
        this.captures = Objects.requireNonNull(captures, "Option catalog capture router is required");
    }

    public void bindCapture(CaptureAccess captureAccess) {
        captures.bind(captureAccess);
    }

    public void bindActivation(ExtensionRegistryActivation activation) {
        this.activation = activation;
    }

    public boolean register(OptionCatalogProvider provider) {
        if (provider == null || provider.sourceId() == null || provider.sourceId().isBlank()) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(OptionCatalogRegistry.class, target -> {
                    target.addDiagnosticLocal(new RegistrationDiagnostic("INVALID_PROVIDER", "", "Catalog provider and source ID are required"));
                    return null;
                });
            } else {
                addDiagnosticLocal(new RegistrationDiagnostic("INVALID_PROVIDER", "", "Catalog provider and source ID are required"));
            }
            return false;
        }
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.update(OptionCatalogRegistry.class, target -> target.registerLocal(provider));
        }
        return registerLocal(provider);
    }

    private boolean registerLocal(OptionCatalogProvider provider) {
        String sourceId = normalize(provider.sourceId());
        OptionCatalogProvider existing = providers.putIfAbsent(sourceId, provider);
        if (existing != null) {
            if (existing == provider) {
                return true;
            }
            addDiagnosticLocal(new RegistrationDiagnostic("DUPLICATE_SOURCE", provider.sourceId(), "Catalog source is already registered by " + existing.providerId()));
            return false;
        }
        if (provider.runtimeDataDomain() != null && !provider.runtimeDataDomain().isBlank()) {
            OptionCatalogRuntimeDataAdapter adapter = new OptionCatalogRuntimeDataAdapter(provider, captures);
            if (!runtimeData.register(adapter)) {
                providers.remove(sourceId, provider);
                addDiagnosticLocal(new RegistrationDiagnostic("DUPLICATE_RUNTIME_DATA_ADAPTER", provider.sourceId(), "Runtime data adapter is already registered"));
                return false;
            }
            catalogAdapters.put(sourceId, adapter);
        }
        return true;
    }

    private void addDiagnosticLocal(RegistrationDiagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    public void unregister(String sourceId) {
        if (sourceId != null) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(OptionCatalogRegistry.class, target -> {
                    target.unregisterLocal(sourceId);
                    return null;
                });
                return;
            }
            unregisterLocal(sourceId);
        }
    }

    private void unregisterLocal(String sourceId) {
        providers.remove(normalize(sourceId));
        catalogAdapters.remove(normalize(sourceId));
        runtimeData.unregister(sourceId);
    }

    public OptionCatalogProvider provider(String sourceId) {
        OptionCatalogRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.provider(sourceId);
        }
        if (sourceId == null) {
            return null;
        }
        return providers.get(normalize(sourceId));
    }

    public boolean contains(String sourceId) {
        OptionCatalogRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.contains(sourceId);
        }
        return provider(sourceId) != null;
    }

    public List<OptionCatalogProvider> providers() {
        OptionCatalogRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.providers();
        }
        return providers.values().stream().sorted(Comparator.comparing(OptionCatalogProvider::sourceId, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public OptionCatalogCapture capture(String sourceId, OptionCatalogQuery query) {
        return resolveCapture(sourceId, query).capture();
    }

    public ResolvedCapture resolveCapture(String sourceId, OptionCatalogQuery query) {
        ExtensionRegistryActivation current = activation;
        if (current == null) {
            return resolveCaptureLocal(sourceId, query);
        }
        ExtensionRegistryActivation.State state = current.snapshot();
        ResolvedCapture resolved = state.optionCatalogs().resolveCaptureLocal(sourceId, query);
        if (current.snapshot() != state) {
            throw new CaptureUnavailable("invalidated", "Option catalog provider changed during capture: " + sourceId);
        }
        return resolved;
    }

    private ResolvedCapture resolveCaptureLocal(String sourceId, OptionCatalogQuery query) {
        if (sourceId == null) {
            throw new ProviderUnavailable("Catalog provider is not registered: null");
        }
        String key = normalize(sourceId);
        OptionCatalogProvider provider = providers.get(key);
        if (provider == null) {
            throw new ProviderUnavailable("Catalog provider is not registered: " + sourceId);
        }
        Set<String> contextKeys = provider.contextKeys();
        Set<String> capturedContextKeys = contextKeys == null ? Set.of() : Set.copyOf(contextKeys);
        OptionCatalogCapture capture = captures.capture(provider,
            query != null ? query : new OptionCatalogQuery(sourceId, Map.of()));
        if (providers.get(key) != provider) {
            throw new CaptureUnavailable("invalidated", "Option catalog provider changed during capture: " + sourceId);
        }
        return new ResolvedCapture(provider, capturedContextKeys, capture);
    }

    public List<RegistrationDiagnostic> diagnostics() {
        OptionCatalogRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.diagnostics();
        }
        return List.copyOf(diagnostics);
    }

    public RuntimeDataRegistry runtimeData() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().runtimeData();
        }
        return runtimeData;
    }

    public synchronized OptionCatalogRegistry copy() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().optionCatalogs();
        }
        OptionCatalogRegistry copy = new OptionCatalogRegistry(runtimeData.copy(), captures);
        copy.providers.putAll(providers);
        copy.catalogAdapters.putAll(catalogAdapters);
        copy.diagnostics.addAll(diagnostics);
        return copy;
    }

    public synchronized void replaceFrom(OptionCatalogRegistry staged) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(OptionCatalogRegistry.class, target -> {
                target.replaceFromLocal(staged);
                return null;
            });
            return;
        }
        replaceFromLocal(staged);
    }

    private synchronized void replaceFromLocal(OptionCatalogRegistry staged) {
        if (staged == null) {
            throw new IllegalArgumentException("A staged option catalog registry is required");
        }
        providers.clear();
        providers.putAll(staged.providers);
        catalogAdapters.clear();
        catalogAdapters.putAll(staged.catalogAdapters);
        diagnostics.clear();
        diagnostics.addAll(staged.diagnostics);
        runtimeData.replaceFrom(staged.runtimeData);
    }

    public List<OptionCatalogItem> items(String sourceId, OptionCatalogQuery query) {
        return requireAvailable(capture(sourceId, query)).items();
    }

    public List<String> values(String sourceId, OptionCatalogQuery query) {
        return requireAvailable(capture(sourceId, query)).values();
    }

    private OptionCatalogCapture requireAvailable(OptionCatalogCapture capture) {
        if (!"available".equalsIgnoreCase(capture.status())) {
            String message = capture.diagnostic().isBlank()
                ? "Option catalog capture is " + capture.status()
                : capture.diagnostic();
            throw new CaptureUnavailable(capture.status(), message);
        }
        return capture;
    }

    private String normalize(String sourceId) {
        return sourceId.toLowerCase(Locale.ROOT);
    }

    private OptionCatalogRegistry activeRegistry() {
        ExtensionRegistryActivation current = activation;
        return current != null ? current.snapshot().optionCatalogs() : this;
    }

    public record RegistrationDiagnostic(String code, String sourceId, String message) {
    }

    public record ResolvedCapture(OptionCatalogProvider provider, Set<String> contextKeys, OptionCatalogCapture capture) {
        public ResolvedCapture {
            Objects.requireNonNull(provider, "Resolved option catalog provider is required");
            contextKeys = Set.copyOf(Objects.requireNonNull(contextKeys, "Resolved option catalog context keys are required"));
            Objects.requireNonNull(capture, "Resolved option catalog capture is required");
        }
    }

    @FunctionalInterface
    public interface CaptureAccess {
        OptionCatalogCapture capture(OptionCatalogProvider provider, OptionCatalogQuery query);
    }

    public interface PreparedCaptureProvider extends OptionCatalogProvider {
        OptionCatalogCapture preparedCapture(OptionCatalogQuery query);
    }

    static final class CaptureRouter {
        private volatile CaptureAccess access = CaptureRouter::captureCaller;
        private volatile boolean bound;

        private void bind(CaptureAccess captureAccess) {
            access = Objects.requireNonNull(captureAccess, "Option catalog capture access is required");
            bound = true;
        }

        OptionCatalogCapture capture(OptionCatalogProvider provider, OptionCatalogQuery query) {
            Objects.requireNonNull(provider, "Option catalog provider is required");
            Objects.requireNonNull(query, "Option catalog query is required");
            OptionCatalogProvider.CaptureAffinity affinity = provider.captureAffinity(query);
            if (affinity == OptionCatalogProvider.CaptureAffinity.UNSUPPORTED) {
                throw new CaptureUnavailable("unsupported", "Option catalog provider does not expose a coherent capture");
            }
            if (!bound) {
                return captureCaller(provider, query);
            }
            if (provider instanceof PreparedCaptureProvider prepared
                && affinity == OptionCatalogProvider.CaptureAffinity.IO) {
                return Objects.requireNonNull(prepared.preparedCapture(query), "Prepared option catalog capture is required");
            }
            return Objects.requireNonNull(access.capture(provider, query), "Option catalog capture is required");
        }

        String revision(OptionCatalogProvider provider) {
            OptionCatalogQuery query = new OptionCatalogQuery(provider.sourceId(), Map.of());
            return capture(provider, query).revision();
        }

        private static OptionCatalogCapture captureCaller(OptionCatalogProvider provider, OptionCatalogQuery query) {
            if (provider.captureAffinity(query) != OptionCatalogProvider.CaptureAffinity.CALLER) {
                throw new CaptureUnavailable("unavailable", "Option catalog provider requires an affinity capture executor");
            }
            return provider.capture(query);
        }
    }

    public static final class ProviderUnavailable extends IllegalArgumentException {
        public ProviderUnavailable(String message) {
            super(message);
        }
    }

    public static final class CaptureUnavailable extends IllegalStateException {
        private final String status;

        public CaptureUnavailable(String status, String message) {
            super(message);
            this.status = status == null || status.isBlank() ? "unavailable" : status;
        }

        public String status() {
            return status;
        }
    }
}
