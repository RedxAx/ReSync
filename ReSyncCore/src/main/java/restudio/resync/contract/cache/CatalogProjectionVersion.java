package restudio.resync.contract.cache;

import java.util.Objects;

public record CatalogProjectionVersion(int generation, int minor) implements Comparable<CatalogProjectionVersion> {
    public static final CatalogProjectionVersion LEGACY = new CatalogProjectionVersion(1, 0);
    public static final CatalogProjectionVersion CURRENT = new CatalogProjectionVersion(1, 1);

    public CatalogProjectionVersion {
        if (generation < 1) {
            throw new IllegalArgumentException("Catalog projection generation must be positive");
        }
        if (minor < 0) {
            throw new IllegalArgumentException("Catalog projection minor must not be negative");
        }
    }

    public static CatalogProjectionVersion current() {
        return CURRENT;
    }

    public boolean legacy() {
        return equals(LEGACY);
    }

    public boolean isCurrent() {
        return equals(CURRENT);
    }

    public boolean requiresCatalogBinding() {
        return generation == CURRENT.generation() && minor >= CURRENT.minor();
    }

    public static boolean isSupported(CatalogProjectionVersion version) {
        Objects.requireNonNull(version, "Catalog projection version is required");
        return version.generation() == CURRENT.generation() && version.minor() <= CURRENT.minor();
    }

    public static CatalogProjectionVersion parseCanonicalText(String value) {
        Objects.requireNonNull(value, "Catalog projection version is required");
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Catalog projection version must contain generation and minor");
        }
        try {
            CatalogProjectionVersion version = new CatalogProjectionVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            if (!version.canonicalText().equals(value)) {
                throw new IllegalArgumentException("Catalog projection version is not canonical");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Catalog projection version is not numeric", exception);
        }
    }

    public String canonicalText() {
        return generation + "." + minor;
    }

    @Override
    public int compareTo(CatalogProjectionVersion other) {
        Objects.requireNonNull(other, "Catalog projection version is required");
        int generationResult = Integer.compare(generation, other.generation);
        return generationResult != 0 ? generationResult : Integer.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
