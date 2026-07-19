package restudio.resync.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class RuntimeDataRegistry {
    private final Map<String, Map<String, RuntimeDataAdapter<?>>> adapters = new ConcurrentHashMap<>();

    public boolean register(RuntimeDataAdapter<?> adapter) {
        if (adapter == null || normalize(adapter.id()).isBlank() || normalize(adapter.domain()).isBlank()) {
            return false;
        }
        return adapters.computeIfAbsent(normalize(adapter.domain()), ignored -> new ConcurrentHashMap<>())
            .putIfAbsent(normalize(adapter.id()), adapter) == null;
    }

    public void unregister(String adapterId) {
        String normalized = normalize(adapterId);
        adapters.values().forEach(domainAdapters -> domainAdapters.remove(normalized));
        adapters.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public RuntimeDataAdapter<?> adapter(String adapterId) {
        String normalized = normalize(adapterId);
        return adapters.values().stream().map(domain -> domain.get(normalized)).filter(value -> value != null).findFirst().orElse(null);
    }

    public List<RuntimeDataAdapter<?>> adapters(String domain) {
        return adapters.getOrDefault(normalize(domain), Map.of()).values().stream()
            .filter(RuntimeDataAdapter::available)
            .sorted(Comparator.comparing(RuntimeDataAdapter::id, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public List<String> domains() {
        return adapters.entrySet().stream().filter(entry -> entry.getValue().values().stream().anyMatch(RuntimeDataAdapter::available))
            .map(Map.Entry::getKey).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<RuntimeDataRecord> query(String domain, RuntimeDataQuery query) {
        RuntimeDataQuery effective = query != null ? query : RuntimeDataQuery.all();
        List<RuntimeDataRecord> values = new ArrayList<>();
        for (RuntimeDataAdapter<?> adapter : adapters(domain)) {
            if (!effective.adapters().isEmpty() && !effective.adapters().contains(normalize(adapter.id()))) {
                continue;
            }
            List<RuntimeDataRecord> records = adapter.records(effective);
            if (records == null) {
                continue;
            }
            for (RuntimeDataRecord record : records) {
                RuntimeDataRecord owned = record != null ? record.withOwner(adapter.domain(), adapter.id()) : null;
                if (effective.matches(owned)) {
                    values.add(owned);
                }
            }
        }
        values.sort(Comparator.comparing(RuntimeDataRecord::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(RuntimeDataRecord::adapterId, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(RuntimeDataRecord::id, String.CASE_INSENSITIVE_ORDER));
        return effective.limit() > 0 && values.size() > effective.limit() ? List.copyOf(values.subList(0, effective.limit())) : List.copyOf(values);
    }

    public Optional<RuntimeDataRecord> random(String domain, RuntimeDataQuery query) {
        List<RuntimeDataRecord> records = query(domain, query);
        return records.isEmpty() ? Optional.empty() : Optional.of(records.get(ThreadLocalRandom.current().nextInt(records.size())));
    }

    public Object resolve(RuntimeDataRecord record, int amount) {
        RuntimeDataAdapter<?> adapter = record != null ? adapter(record.adapterId()) : null;
        if (adapter == null || !normalize(adapter.domain()).equals(normalize(record.domain()))
            || !adapter.capabilities().contains(RuntimeDataCapability.RESOLVE)) {
            return null;
        }
        return adapter.resolve(record, Math.max(1, amount));
    }

    public List<Object> resolveAll(List<RuntimeDataRecord> records, int amount) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream().map(record -> resolve(record, amount)).filter(value -> value != null).toList();
    }

    public RuntimeDataRecord describe(String domain, Object value) {
        if (value == null) {
            return null;
        }
        for (RuntimeDataAdapter<?> adapter : adapters(domain)) {
            RuntimeDataRecord record = describe(adapter, value);
            if (record != null) {
                return record.withOwner(adapter.domain(), adapter.id());
            }
        }
        return null;
    }

    public List<RuntimeDataCategory> categories(String domain, RuntimeDataQuery query) {
        RuntimeDataQuery effective = query != null ? query.withoutCategoryFilter() : RuntimeDataQuery.all();
        Map<String, List<RuntimeDataRecord>> grouped = query(domain, effective).stream()
            .flatMap(record -> record.categories().stream().map(category -> Map.entry(category, record)))
            .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return grouped.entrySet().stream().map(entry -> new RuntimeDataCategory(entry.getKey(), label(entry.getKey()), entry.getValue().size(),
                entry.getValue().stream().map(RuntimeDataRecord::adapterId).collect(Collectors.toSet())))
            .sorted(Comparator.comparing(RuntimeDataCategory::label, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @SuppressWarnings("unchecked")
    private RuntimeDataRecord describe(RuntimeDataAdapter<?> adapter, Object value) {
        if (!adapter.capabilities().contains(RuntimeDataCapability.DESCRIBE) || !adapter.valueClass().isInstance(value)) {
            return null;
        }
        return ((RuntimeDataAdapter<Object>) adapter).describe(value);
    }

    private static String label(String value) {
        StringBuilder result = new StringBuilder();
        for (String part : value.replace(':', '_').split("_")) {
            if (part.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
