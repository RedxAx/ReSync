package restudio.resync.flow.catalog;

public record CatalogVersion(int generation, int minor) implements Comparable<CatalogVersion> {
    public CatalogVersion {
        if (generation < 1) {
            throw new IllegalArgumentException("Catalog contract generation must be positive");
        }
        if (minor < 0) {
            throw new IllegalArgumentException("Catalog contract minor must not be negative");
        }
    }

    @Override
    public int compareTo(CatalogVersion other) {
        int generationResult = Integer.compare(generation, other.generation);
        return generationResult != 0 ? generationResult : Integer.compare(minor, other.minor);
    }

    public boolean isBetween(CatalogVersion minimum, CatalogVersion maximum) {
        return compareTo(minimum) >= 0 && compareTo(maximum) <= 0;
    }

    @Override
    public String toString() {
        return generation + "." + minor;
    }
}
