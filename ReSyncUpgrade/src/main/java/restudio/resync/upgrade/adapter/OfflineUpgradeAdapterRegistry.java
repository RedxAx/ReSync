package restudio.resync.upgrade.adapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

public final class OfflineUpgradeAdapterRegistry {
    private final Map<String, OfflineUpgradeAdapter> adapters = new LinkedHashMap<>();
    private final Map<String, OfflineUpgradeSnapshotAdapter> snapshotAdapters = new LinkedHashMap<>();

    public OfflineUpgradeAdapterRegistry() {
    }

    public OfflineUpgradeAdapterRegistry(Collection<? extends OfflineUpgradeAdapter> values) {
        if (values != null) {
            values.forEach(this::register);
        }
    }

    public OfflineUpgradeAdapterRegistry(Collection<? extends OfflineUpgradeAdapter> values,
                                         Collection<? extends OfflineUpgradeSnapshotAdapter> snapshots) {
        this(values);
        if (snapshots != null) {
            snapshots.forEach(this::registerSnapshot);
        }
    }

    public static OfflineUpgradeAdapterRegistry discover() {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    public static OfflineUpgradeAdapterRegistry discover(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        OfflineUpgradeAdapterRegistry registry = new OfflineUpgradeAdapterRegistry();
        try {
            ServiceLoader.load(OfflineUpgradeAdapterProvider.class, loader).stream()
                .sorted(Comparator.comparing(provider -> provider.type().getName()))
                .forEach(provider -> {
                    OfflineUpgradeAdapterProvider value = provider.get();
                    value.adapters().stream()
                        .sorted(Comparator.comparing(adapter -> adapter.wireId()))
                        .forEach(registry::register);
                    value.snapshotAdapters().stream()
                        .sorted(Comparator.comparing(adapter -> adapter.wireId()))
                        .forEach(registry::registerSnapshot);
                });
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException("Offline Upgrade Adapter Discovery Failed", error);
        }
        return registry;
    }

    public synchronized void register(OfflineUpgradeAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        String wireId = adapter.wireId();
        if (snapshotAdapters.containsKey(wireId)) {
            throw new IllegalArgumentException("Duplicate Offline Upgrade Adapter: " + wireId);
        }
        OfflineUpgradeAdapter previous = adapters.putIfAbsent(wireId, adapter);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate Offline Upgrade Adapter: " + wireId);
        }
    }

    public synchronized void registerSnapshot(OfflineUpgradeSnapshotAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        String wireId = adapter.wireId();
        if (adapters.containsKey(wireId) || snapshotAdapters.putIfAbsent(wireId, adapter) != null) {
            throw new IllegalArgumentException("Duplicate Offline Upgrade Adapter: " + wireId);
        }
    }

    public synchronized List<OfflineUpgradeAdapter> adapters() {
        return adapters.values().stream()
            .sorted(Comparator.comparing(OfflineUpgradeAdapter::wireId))
            .toList();
    }

    public synchronized List<OfflineUpgradeSnapshotAdapter> snapshotAdapters() {
        return snapshotAdapters.values().stream()
            .sorted(Comparator.comparing(OfflineUpgradeSnapshotAdapter::wireId))
            .toList();
    }

    public synchronized OfflineUpgradeAdapter byWireId(String wireId) {
        return adapters.get(wireId);
    }

    public synchronized OfflineUpgradeSnapshotAdapter snapshotByWireId(String wireId) {
        return snapshotAdapters.get(wireId);
    }

    public synchronized Resolution resolve(String relativePath) {
        List<OfflineUpgradeAdapter> matches = adapters.values().stream()
            .filter(adapter -> adapter.matches(relativePath))
            .sorted(Comparator.comparing(OfflineUpgradeAdapter::wireId))
            .toList();
        return switch (matches.size()) {
            case 0 -> Resolution.none();
            case 1 -> Resolution.unique(matches.getFirst());
            default -> Resolution.ambiguous(matches);
        };
    }

    public record Resolution(State state, OfflineUpgradeAdapter adapter, List<OfflineUpgradeAdapter> matches) {
        public Resolution {
            state = Objects.requireNonNull(state, "state");
            matches = List.copyOf(matches == null ? List.of() : new ArrayList<>(matches));
            if (state == State.UNIQUE && adapter == null) {
                throw new IllegalArgumentException("Unique Adapter Resolution Requires An Adapter");
            }
            if (state != State.UNIQUE && adapter != null) {
                throw new IllegalArgumentException("Non-Unique Adapter Resolution Cannot Carry An Adapter");
            }
        }

        public static Resolution none() {
            return new Resolution(State.NONE, null, List.of());
        }

        public static Resolution unique(OfflineUpgradeAdapter adapter) {
            return new Resolution(State.UNIQUE, Objects.requireNonNull(adapter, "adapter"), List.of(adapter));
        }

        public static Resolution ambiguous(List<? extends OfflineUpgradeAdapter> matches) {
            return new Resolution(State.AMBIGUOUS, null, matches.stream().map(value -> (OfflineUpgradeAdapter) value).toList());
        }

        public enum State {
            NONE,
            UNIQUE,
            AMBIGUOUS
        }
    }
}
