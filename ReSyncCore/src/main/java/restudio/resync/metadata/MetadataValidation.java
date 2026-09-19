package restudio.resync.metadata;

import restudio.resync.flow.canonical.CanonicalJson;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class MetadataValidation {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*(?:/[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*)?");

    private MetadataValidation() {
    }

    static String id(String value, String field) {
        String checked = text(value, field, 96);
        if (!ID.matcher(checked).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase metadata identifier");
        }
        return checked;
    }

    static String text(String value, String field, int maximumCodePoints) {
        String checked = Objects.requireNonNull(value, field + " is required");
        if (checked.isBlank() || !checked.equals(checked.strip())) {
            throw new IllegalArgumentException(field + " must be non-blank canonical text");
        }
        if (checked.codePointCount(0, checked.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(field + " exceeds " + maximumCodePoints + " code points");
        }
        return CanonicalJson.requireNfc(checked);
    }

    static String optionalText(String value, String field, int maximumCodePoints) {
        return value == null ? null : text(value, field, maximumCodePoints);
    }

    static String optionalId(String value, String field) {
        return value == null ? null : id(value, field);
    }

    static Set<String> capabilities(Set<String> values) {
        Objects.requireNonNull(values, "Required capabilities are required");
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            String checked = text(value, "Required capability", 192);
            if (!CAPABILITY.matcher(checked).matches()) {
                throw new IllegalArgumentException("Required capability must be a lowercase capability identifier");
            }
            if (!sorted.add(checked)) {
                throw new IllegalArgumentException("Required capabilities contain a duplicate: " + checked);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    static Map<String, String> provenance(Map<String, String> values) {
        Objects.requireNonNull(values, "Metadata provenance is required");
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = id(entry.getKey(), "Metadata provenance key");
            String value = text(entry.getValue(), "Metadata provenance value", 2048);
            if (sorted.put(key, value) != null) {
                throw new IllegalArgumentException("Metadata provenance contains a duplicate key: " + key);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
