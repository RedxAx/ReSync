package restudio.resync.runtime.data;

import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.RuntimeDataAdapter;
import restudio.resync.api.RuntimeDataRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

abstract class RuntimeDataCategoryCatalog<T> implements OptionCatalogRegistry.PreparedCaptureProvider {
    private final RuntimeDataAdapter<?> adapter;
    private final AtomicReference<Prepared<T>> prepared = new AtomicReference<>();
    private Snapshot<T> residentSnapshot;
    private OptionCatalogCapture residentCapture;

    RuntimeDataCategoryCatalog(RuntimeDataAdapter<?> adapter) {
        this.adapter = Objects.requireNonNull(adapter, "Runtime data adapter is required");
    }

    @Override
    public final String sourceId() {
        return adapter.id() + ":category";
    }

    @Override
    public final String runtimeDataDomain() {
        return "";
    }

    @Override
    public final OptionCatalogCapture capture(OptionCatalogQuery query) {
        Prepared<T> observed = prepared.get();
        Snapshot<T> snapshot = Objects.requireNonNull(captureSnapshot(), "Runtime data category snapshot is required");
        OptionCatalogCapture capture = capture(snapshot);
        if (captureAffinity() == CaptureAffinity.IO) {
            if (!current(snapshot.token())) {
                prepared.compareAndSet(observed, null);
                throw unavailable("Runtime data category snapshot changed during capture: " + adapter.id());
            }
            prepared.set(new Prepared<>(snapshot.token(), capture));
        }
        return capture;
    }

    @Override
    public final OptionCatalogCapture preparedCapture(OptionCatalogQuery query) {
        if (captureAffinity() != CaptureAffinity.IO) {
            return capture(query);
        }
        Prepared<T> snapshot = prepared.get();
        if (snapshot == null) {
            throw unavailable("Runtime data category snapshot has not been prepared: " + adapter.id());
        }
        if (!current(snapshot.token())) {
            prepared.compareAndSet(snapshot, null);
            throw unavailable("Runtime data category snapshot is no longer current: " + adapter.id());
        }
        return snapshot.capture();
    }

    @Override
    public final String revision() {
        return accessibleCapture().revision();
    }

    @Override
    public final String revision(OptionCatalogQuery query) {
        return accessibleCapture(query).revision();
    }

    @Override
    public final List<String> values() {
        return accessibleCapture().values();
    }

    @Override
    public final List<String> values(OptionCatalogQuery query) {
        return accessibleCapture(query).values();
    }

    @Override
    public final List<OptionCatalogItem> items() {
        return accessibleCapture().items();
    }

    @Override
    public final List<OptionCatalogItem> items(OptionCatalogQuery query) {
        return accessibleCapture(query).items();
    }

    protected abstract Snapshot<T> captureSnapshot();

    protected boolean current(T token) {
        return true;
    }

    protected final Snapshot<T> snapshot(String revision, List<RuntimeDataRecord> records, T token) {
        return new Snapshot<>(revision, records, token);
    }

    private OptionCatalogCapture accessibleCapture() {
        return accessibleCapture(new OptionCatalogQuery(sourceId(), Map.of()));
    }

    private OptionCatalogCapture accessibleCapture(OptionCatalogQuery query) {
        return captureAffinity() == CaptureAffinity.IO ? preparedCapture(query) : capture(query);
    }

    private synchronized OptionCatalogCapture capture(Snapshot<T> snapshot) {
        if (residentSnapshot == snapshot) {
            return residentCapture;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RuntimeDataRecord record : snapshot.records()) {
            if (record != null) {
                record.categories().forEach(category -> counts.merge(category, 1, Integer::sum));
            }
        }
        List<OptionCatalogItem> items = counts.entrySet().stream()
            .map(entry -> new OptionCatalogItem(entry.getKey(), RuntimeDataLabels.label(entry.getKey()), entry.getValue() + " Values", "",
                "Categories", Map.of("count", entry.getValue(), "sources", List.of(adapter.id()))))
            .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.label(), right.label()))
            .toList();
        OptionCatalogCapture capture = new OptionCatalogCapture(sourceId() + ":" + snapshot.revision() + ":" + items.size() + ":" + items.hashCode(),
            items, "available", "");
        residentSnapshot = snapshot;
        residentCapture = capture;
        return capture;
    }

    private OptionCatalogRegistry.CaptureUnavailable unavailable(String message) {
        return new OptionCatalogRegistry.CaptureUnavailable("unavailable", message);
    }

    record Snapshot<T>(String revision, List<RuntimeDataRecord> records, T token) {
        Snapshot {
            revision = Objects.requireNonNull(revision, "Runtime data category revision is required");
            if (revision.isBlank()) {
                throw new IllegalArgumentException("Runtime data category revision is required");
            }
            records = List.copyOf(Objects.requireNonNull(records, "Runtime data category records are required"));
        }
    }

    private record Prepared<T>(T token, OptionCatalogCapture capture) {
        private Prepared {
            Objects.requireNonNull(capture, "Prepared runtime data category capture is required");
        }
    }
}
