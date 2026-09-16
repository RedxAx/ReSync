package restudio.resync.runtime.data;

import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.RuntimeDataAdapter;
import restudio.resync.api.RuntimeDataRegistry;
import restudio.resync.server.OptionCatalogCaptureExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

public final class RuntimeDataOptionCatalogService {
    public static final String TYPE_SOURCE = "server:runtime_data:type";
    public static final String SOURCE_SOURCE = "server:runtime_data:source";
    public static final String CATEGORY_SOURCE = "server:runtime_data:category";
    private final RuntimeDataRegistry runtimeData;

    public RuntimeDataOptionCatalogService(RuntimeDataRegistry runtimeData) {
        this.runtimeData = Objects.requireNonNull(runtimeData, "Runtime data registry is required");
    }

    public void registerProviders(OptionCatalogRegistry catalogs) {
        catalogs.register(typeProvider());
        catalogs.register(sourceProvider());
        catalogs.register(categoryProvider());
    }

    public static CompletionStage<Void> prewarm(OptionCatalogRegistry catalogs, OptionCatalogCaptureExecutor executor) {
        try {
            return prepare(catalogs, executor, Set.of());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public static CompletionStage<Void> refresh(OptionCatalogRegistry catalogs, OptionCatalogCaptureExecutor executor, String adapterId) {
        try {
            if (adapterId == null || adapterId.isBlank()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Runtime data adapter ID is required"));
            }
            return prepare(catalogs, executor, Set.of(normalize(adapterId)));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static CompletionStage<Void> prepare(OptionCatalogRegistry catalogs, OptionCatalogCaptureExecutor executor, Set<String> selected) {
        Objects.requireNonNull(catalogs, "Option catalog registry is required");
        Objects.requireNonNull(executor, "Option catalog capture executor is required");
        RuntimeDataRegistry runtimeData = catalogs.runtimeData();
        List<PreparedTarget> targets = new ArrayList<>();
        for (String domain : runtimeData.domains()) {
            for (RuntimeDataAdapter<?> adapter : runtimeData.adapters(domain)) {
                if (!selected.isEmpty() && !selected.contains(normalize(adapter.id()))) {
                    continue;
                }
                OptionCatalogProvider provider = adapter.categoryCatalog();
                if (provider != null && provider.captureAffinity() == OptionCatalogProvider.CaptureAffinity.IO) {
                    if (!(provider instanceof OptionCatalogRegistry.PreparedCaptureProvider prepared)) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                            "IO runtime data category provider does not expose prepared captures: " + adapter.id()));
                    }
                    if (provider.sourceId() == null || provider.sourceId().isBlank()) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                            "Runtime data category provider source ID is required: " + adapter.id()));
                    }
                    targets.add(new PreparedTarget(domain, adapter, provider, prepared, provider.sourceId()));
                }
            }
        }
        Set<String> preparedIds = targets.stream().map(target -> normalize(target.adapter().id())).collect(Collectors.toSet());
        if (!selected.isEmpty() && (!preparedIds.equals(selected) || targets.size() != selected.size())) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Runtime data category adapter is unavailable or does not require IO preparation: " + String.join(", ", selected)));
        }
        CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
        for (PreparedTarget target : targets) {
            stage = stage.thenCompose(ignored -> prepare(catalogs, executor, target));
        }
        return stage;
    }

    private static CompletionStage<Void> prepare(OptionCatalogRegistry catalogs, OptionCatalogCaptureExecutor executor, PreparedTarget target) {
        OptionCatalogQuery query = new OptionCatalogQuery(target.sourceId(), Map.of());
        return executor.captureAsync(target.provider(), query).thenAccept(capture -> {
            if (!"available".equalsIgnoreCase(capture.status())) {
                throw new IllegalStateException(capture.diagnostic().isBlank()
                    ? "Runtime data category provider is unavailable: " + target.adapter().id() : capture.diagnostic());
            }
            RuntimeDataAdapter<?> current = catalogs.runtimeData().adapters(target.domain()).stream()
                .filter(adapter -> normalize(adapter.id()).equals(normalize(target.adapter().id())))
                .findFirst().orElse(null);
            if (current != target.adapter() || current.categoryCatalog() != target.provider()
                || target.provider().captureAffinity() != OptionCatalogProvider.CaptureAffinity.IO
                || !target.sourceId().equals(target.provider().sourceId())) {
                throw new IllegalStateException("Runtime data category provider changed during preparation: " + target.adapter().id());
            }
            OptionCatalogCapture prepared = target.prepared().preparedCapture(query);
            if (!capture.revision().equals(prepared.revision()) || !"available".equalsIgnoreCase(prepared.status())) {
                throw new IllegalStateException("Runtime data category snapshot changed during preparation: " + target.adapter().id());
            }
        });
    }

    private OptionCatalogProvider typeProvider() {
        return provider(TYPE_SOURCE, Set.of(), query -> runtimeData.domains().stream()
            .map(domain -> new OptionCatalogItem(domain, RuntimeDataLabels.label(domain))).toList());
    }

    private OptionCatalogProvider sourceProvider() {
        return provider(SOURCE_SOURCE, Set.of("data_type"), query -> runtimeData.adapters(dataType(query)).stream()
            .map(adapter -> new OptionCatalogItem(adapter.id(), RuntimeDataLabels.label(adapter.id()), adapter.valueType().toString(), "",
                RuntimeDataLabels.label(adapter.domain()), Map.of("domain", adapter.domain(), "capabilities",
                adapter.capabilities().stream().map(Enum::name).toList())))
            .toList());
    }

    private OptionCatalogProvider categoryProvider() {
        return new CategoryProvider();
    }

    private final class CategoryProvider implements OptionCatalogProvider {
        @Override
        public String sourceId() {
            return CATEGORY_SOURCE;
        }

        @Override
        public String runtimeDataDomain() {
            return "";
        }

        @Override
        public Set<String> contextKeys() {
            return Set.of("data_type", "source", "sources");
        }

        @Override
        public CaptureAffinity captureAffinity() {
            return CaptureAffinity.SERVER_MAIN;
        }

        @Override
        public OptionCatalogCapture capture(OptionCatalogQuery query) {
            String domain = dataType(query);
            Set<String> requested = selectedSources(query);
            AdapterSelection selection = select(domain, requested);
            if (!selection.available()) {
                return unavailable(selection.diagnostic());
            }
            Map<String, CategoryAggregate> categories = new LinkedHashMap<>();
            Map<AdapterCapture, String> capturedRevisions = new LinkedHashMap<>();
            StringBuilder revision = new StringBuilder(domain).append('\n');
            for (AdapterCapture target : selection.adapters()) {
                OptionCatalogCapture capture = capture(target, query);
                if (!"available".equalsIgnoreCase(capture.status())) {
                    String diagnostic = capture.diagnostic().isBlank()
                        ? "Runtime data category provider is unavailable: " + target.adapter().id() : capture.diagnostic();
                    return unavailable(diagnostic);
                }
                Set<String> values = new LinkedHashSet<>();
                for (OptionCatalogItem item : capture.items()) {
                    if (item == null || item.value().isBlank() || !values.add(normalize(item.value()))) {
                        return unavailable("Runtime data category provider returned an invalid category set: " + target.adapter().id());
                    }
                    Object countValue = item.metadata().get("count");
                    if (!(countValue instanceof Number number) || number.longValue() < 1L || number.longValue() > Integer.MAX_VALUE
                        || number.doubleValue() != number.longValue()) {
                        return unavailable("Runtime data category provider returned an invalid count: " + target.adapter().id());
                    }
                    CategoryAggregate aggregate = categories.computeIfAbsent(normalize(item.value()), ignored -> new CategoryAggregate());
                    try {
                        aggregate.count = Math.addExact(aggregate.count, number.intValue());
                    } catch (ArithmeticException exception) {
                        return unavailable("Runtime data category count exceeds the supported range: " + item.value());
                    }
                    aggregate.sources.add(target.adapter().id());
                }
                revision.append(target.adapter().id()).append('\n').append(target.sourceId()).append('\n')
                    .append(capture.revision()).append('\n');
                capturedRevisions.put(target, capture.revision());
            }
            if (!current(selection, domain, requested)) {
                return unavailable("Runtime data category adapters changed during capture");
            }
            for (AdapterCapture target : selection.adapters()) {
                if (target.affinity() != CaptureAffinity.IO) {
                    continue;
                }
                OptionCatalogCapture current = capture(target, query);
                if (!"available".equalsIgnoreCase(current.status())
                    || !Objects.equals(capturedRevisions.get(target), current.revision())) {
                    return unavailable("Runtime data category snapshot changed during capture: " + target.adapter().id());
                }
            }
            List<OptionCatalogItem> items = categories.entrySet().stream().map(entry -> categoryItem(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(OptionCatalogItem::label, String.CASE_INSENSITIVE_ORDER)).toList();
            return new OptionCatalogCapture(CATEGORY_SOURCE + ":" + revision.toString().hashCode() + ":" + items.size() + ":" + items.hashCode(),
                items, "available", "");
        }

        @Override
        public String revision() {
            return capture(defaultQuery()).revision();
        }

        @Override
        public String revision(OptionCatalogQuery query) {
            return capture(normalizeQuery(query)).revision();
        }

        @Override
        public List<String> values() {
            return capture(defaultQuery()).values();
        }

        @Override
        public List<String> values(OptionCatalogQuery query) {
            return capture(normalizeQuery(query)).values();
        }

        @Override
        public List<OptionCatalogItem> items() {
            return capture(defaultQuery()).items();
        }

        @Override
        public List<OptionCatalogItem> items(OptionCatalogQuery query) {
            return capture(normalizeQuery(query)).items();
        }

        private AdapterSelection select(String domain, Set<String> requested) {
            List<RuntimeDataAdapter<?>> domainAdapters = runtimeData.adapters(domain);
            Map<String, RuntimeDataAdapter<?>> available = new LinkedHashMap<>();
            for (RuntimeDataAdapter<?> adapter : domainAdapters) {
                if (available.put(normalize(adapter.id()), adapter) != null) {
                    return AdapterSelection.unavailable("Runtime data category source is ambiguous: " + adapter.id());
                }
            }
            List<AdapterCapture> selected = new ArrayList<>();
            Set<String> adapterIds = requested.isEmpty() ? available.keySet() : requested;
            for (String adapterId : adapterIds) {
                RuntimeDataAdapter<?> adapter = available.get(adapterId);
                if (adapter == null) {
                    return AdapterSelection.unavailable("Runtime data category source is unavailable for " + domain + ": " + adapterId);
                }
                OptionCatalogProvider provider = adapter.categoryCatalog();
                CaptureAffinity affinity = provider != null ? provider.captureAffinity() : CaptureAffinity.UNSUPPORTED;
                if (provider == null || affinity == CaptureAffinity.UNSUPPORTED) {
                    return AdapterSelection.unavailable("Runtime data adapter does not expose category captures: " + adapter.id());
                }
                if (affinity == CaptureAffinity.IO
                    && !(provider instanceof OptionCatalogRegistry.PreparedCaptureProvider)) {
                    return AdapterSelection.unavailable("Runtime data adapter does not expose prepared category captures: " + adapter.id());
                }
                String sourceId = provider.sourceId();
                if (sourceId == null || sourceId.isBlank()) {
                    return AdapterSelection.unavailable("Runtime data category provider source ID is required: " + adapter.id());
                }
                selected.add(new AdapterCapture(adapter, provider, affinity, sourceId));
            }
            selected.sort(Comparator.comparing(target -> target.adapter().id(), String.CASE_INSENSITIVE_ORDER));
            return AdapterSelection.available(selected);
        }

        private OptionCatalogCapture capture(AdapterCapture target, OptionCatalogQuery aggregateQuery) {
            OptionCatalogQuery query = new OptionCatalogQuery(target.sourceId(),
                aggregateQuery != null ? aggregateQuery.context() : Map.of());
            try {
                return switch (target.affinity()) {
                    case CALLER, SERVER_MAIN -> Objects.requireNonNull(target.provider().capture(query),
                        "Runtime data category capture is required");
                    case IO -> Objects.requireNonNull(
                        ((OptionCatalogRegistry.PreparedCaptureProvider) target.provider()).preparedCapture(query),
                        "Prepared runtime data category capture is required");
                    case UNSUPPORTED -> unavailable("Runtime data adapter does not expose category captures: " + target.adapter().id());
                };
            } catch (RuntimeException exception) {
                String message = exception.getMessage();
                return unavailable(message != null && !message.isBlank() ? message
                    : "Runtime data category provider is unavailable: " + target.adapter().id());
            }
        }

        private boolean current(AdapterSelection selection, String domain, Set<String> requested) {
            AdapterSelection current = select(domain, requested);
            if (!current.available() || current.adapters().size() != selection.adapters().size()) {
                return false;
            }
            for (int index = 0; index < selection.adapters().size(); index++) {
                AdapterCapture before = selection.adapters().get(index);
                AdapterCapture after = current.adapters().get(index);
                if (before.adapter() != after.adapter() || before.provider() != after.provider() || before.affinity() != after.affinity()
                    || !before.sourceId().equals(after.sourceId())) {
                    return false;
                }
            }
            return true;
        }

        private OptionCatalogQuery defaultQuery() {
            return new OptionCatalogQuery(CATEGORY_SOURCE, Map.of());
        }

        private OptionCatalogQuery normalizeQuery(OptionCatalogQuery query) {
            return query != null ? query : defaultQuery();
        }

        private OptionCatalogCapture unavailable(String diagnostic) {
            String message = diagnostic != null && !diagnostic.isBlank() ? diagnostic : "Runtime data category capture is unavailable";
            return new OptionCatalogCapture(CATEGORY_SOURCE + ":unavailable:" + message.hashCode(), List.of(), "unavailable", message);
        }
    }

    private OptionCatalogItem categoryItem(String category, CategoryAggregate aggregate) {
        return new OptionCatalogItem(category, RuntimeDataLabels.label(category), aggregate.count + " Values", "", "Categories",
            Map.of("count", aggregate.count, "sources", List.copyOf(aggregate.sources)));
    }

    private OptionCatalogProvider provider(String sourceId, Set<String> contextKeys, CatalogValues values) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public String runtimeDataDomain() {
                return "";
            }

            @Override
            public Set<String> contextKeys() {
                return contextKeys;
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.CALLER;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                List<OptionCatalogItem> capturedItems = List.copyOf(items(query));
                return new OptionCatalogCapture(sourceId + ":" + capturedItems.size() + ":" + capturedItems.hashCode(),
                    capturedItems, "available", "");
            }

            @Override
            public String revision() {
                return sourceId + ":" + runtimeData.domains().hashCode();
            }

            @Override
            public String revision(OptionCatalogQuery query) {
                List<OptionCatalogItem> items = items(query);
                return sourceId + ":" + items.size() + ":" + items.hashCode();
            }

            @Override
            public List<String> values() {
                return items().stream().map(OptionCatalogItem::value).toList();
            }

            @Override
            public List<String> values(OptionCatalogQuery query) {
                return items(query).stream().map(OptionCatalogItem::value).toList();
            }

            @Override
            public List<OptionCatalogItem> items() {
                return values.items(new OptionCatalogQuery(sourceId, Map.of()));
            }

            @Override
            public List<OptionCatalogItem> items(OptionCatalogQuery query) {
                return values.items(query != null ? query : new OptionCatalogQuery(sourceId, Map.of()));
            }
        };
    }

    private String dataType(OptionCatalogQuery query) {
        String dataType = query != null ? query.text("data_type") : "";
        return dataType.isBlank() ? "item" : normalize(dataType);
    }

    private Set<String> selectedSources(OptionCatalogQuery query) {
        Set<String> values = new LinkedHashSet<>();
        add(values, query != null ? query.value("source") : null);
        add(values, query != null ? query.value("sources") : null);
        return values;
    }

    private static void add(Set<String> values, Object source) {
        if (source instanceof Iterable<?> iterable) {
            iterable.forEach(item -> add(values, item));
            return;
        }
        if (source != null) {
            for (String value : source.toString().split("[,\\r\\n]")) {
                if (!value.isBlank()) {
                    values.add(normalize(value));
                }
            }
        }
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    @FunctionalInterface
    private interface CatalogValues {
        List<OptionCatalogItem> items(OptionCatalogQuery query);
    }

    private static final class CategoryAggregate {
        private int count;
        private final Set<String> sources = new LinkedHashSet<>();
    }

    private record AdapterCapture(RuntimeDataAdapter<?> adapter, OptionCatalogProvider provider,
                                  OptionCatalogProvider.CaptureAffinity affinity, String sourceId) {
    }

    private record AdapterSelection(boolean available, List<AdapterCapture> adapters, String diagnostic) {
        private AdapterSelection {
            adapters = List.copyOf(adapters);
            diagnostic = diagnostic != null ? diagnostic : "";
        }

        private static AdapterSelection available(List<AdapterCapture> adapters) {
            return new AdapterSelection(true, adapters, "");
        }

        private static AdapterSelection unavailable(String diagnostic) {
            return new AdapterSelection(false, List.of(), diagnostic);
        }
    }

    private record PreparedTarget(String domain, RuntimeDataAdapter<?> adapter, OptionCatalogProvider provider,
                                  OptionCatalogRegistry.PreparedCaptureProvider prepared, String sourceId) {
    }
}
