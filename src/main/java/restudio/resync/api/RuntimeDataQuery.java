package restudio.resync.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RuntimeDataQuery(Set<String> adapters, Set<String> categories, Set<String> tags,
                               Set<String> excludedCategories, Set<String> excludedTags, Map<String, Object> attributes,
                               Map<String, Object> context, String search, MatchMode categoryMatch, MatchMode tagMatch, int limit) {
    public RuntimeDataQuery {
        adapters = normalizedSet(adapters);
        categories = normalizedSet(categories);
        tags = normalizedSet(tags);
        excludedCategories = normalizedSet(excludedCategories);
        excludedTags = normalizedSet(excludedTags);
        attributes = attributes != null ? Map.copyOf(new LinkedHashMap<>(attributes)) : Map.of();
        context = context != null ? Map.copyOf(new LinkedHashMap<>(context)) : Map.of();
        search = normalize(search);
        categoryMatch = categoryMatch != null ? categoryMatch : MatchMode.ANY;
        tagMatch = tagMatch != null ? tagMatch : MatchMode.ALL;
        limit = Math.max(0, limit);
    }

    public static RuntimeDataQuery all() {
        return new RuntimeDataQuery(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), "", MatchMode.ANY, MatchMode.ALL, 0);
    }

    public RuntimeDataQuery withAdapters(Collection<String> adapterIds) {
        return new RuntimeDataQuery(new LinkedHashSet<>(adapterIds != null ? adapterIds : Set.of()), categories, tags, excludedCategories,
            excludedTags, attributes, context, search, categoryMatch, tagMatch, limit);
    }

    public RuntimeDataQuery withoutCategoryFilter() {
        return new RuntimeDataQuery(adapters, Set.of(), tags, excludedCategories, excludedTags, attributes, context, search, categoryMatch, tagMatch, 0);
    }

    public boolean matches(RuntimeDataRecord record) {
        if (record == null || !adapters.isEmpty() && !adapters.contains(normalize(record.adapterId()))) {
            return false;
        }
        if (!matchesSet(record.categories(), categories, categoryMatch) || !matchesSet(record.tags(), tags, tagMatch)) {
            return false;
        }
        if (record.categories().stream().anyMatch(excludedCategories::contains) || record.tags().stream().anyMatch(excludedTags::contains)) {
            return false;
        }
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (!attributeMatches(record.attributes().get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        if (search.isBlank()) {
            return true;
        }
        return searchable(record).contains(search);
    }

    private static boolean matchesSet(Set<String> actual, Set<String> expected, MatchMode mode) {
        if (expected.isEmpty()) {
            return true;
        }
        return mode == MatchMode.ALL ? actual.containsAll(expected) : expected.stream().anyMatch(actual::contains);
    }

    private static boolean attributeMatches(Object actual, Object expected) {
        if (expected instanceof Collection<?> expectedValues) {
            return expectedValues.stream().anyMatch(value -> attributeMatches(actual, value));
        }
        if (actual instanceof Collection<?> actualValues) {
            return actualValues.stream().anyMatch(value -> attributeMatches(value, expected));
        }
        if (actual instanceof String || expected instanceof String) {
            return normalize(Objects.toString(actual, "")).equals(normalize(Objects.toString(expected, "")));
        }
        return Objects.equals(actual, expected);
    }

    private static String searchable(RuntimeDataRecord record) {
        return normalize(String.join(" ", record.id(), record.label(), record.description(), String.join(" ", record.categories()),
            String.join(" ", record.tags()), record.attributes().toString()));
    }

    private static Set<String> normalizedSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalize(value);
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    public enum MatchMode {
        ANY,
        ALL;

        public static MatchMode parse(String value, MatchMode fallback) {
            try {
                return value != null ? valueOf(value.trim().toUpperCase(Locale.ROOT)) : fallback;
            } catch (IllegalArgumentException exception) {
                return fallback;
            }
        }
    }
}
