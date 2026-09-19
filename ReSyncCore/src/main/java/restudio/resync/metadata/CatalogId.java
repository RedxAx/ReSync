package restudio.resync.metadata;

import java.util.Objects;
import java.util.regex.Pattern;

public record CatalogId(String namespace, String path) implements Comparable<CatalogId> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*");

    public CatalogId {
        namespace = component(namespace, "Catalog namespace", NAMESPACE, 96);
        path = component(path, "Catalog path", PATH, 192);
    }

    public static CatalogId of(String namespace, String path) {
        return new CatalogId(namespace, path);
    }

    public static CatalogId parseCanonicalText(String value) {
        String checked = MetadataValidation.text(value, "Catalog ID", 289);
        int separator = checked.indexOf(':');
        if (separator < 1 || separator != checked.lastIndexOf(':') || separator == checked.length() - 1) {
            throw new IllegalArgumentException("Catalog ID must contain one namespace and path separator");
        }
        CatalogId id = new CatalogId(checked.substring(0, separator), checked.substring(separator + 1));
        if (!id.canonicalText().equals(checked)) {
            throw new IllegalArgumentException("Catalog ID is not canonical");
        }
        return id;
    }

    public String canonicalText() {
        return namespace + ":" + path;
    }

    @Override
    public int compareTo(CatalogId other) {
        return canonicalText().compareTo(Objects.requireNonNull(other, "Catalog ID is required").canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }

    private static String component(String value, String field, Pattern pattern, int maximumCodePoints) {
        String checked = MetadataValidation.text(value, field, maximumCodePoints);
        if (!pattern.matcher(checked).matches()) {
            throw new IllegalArgumentException(field + " is not a canonical resource identifier component");
        }
        return checked;
    }
}
