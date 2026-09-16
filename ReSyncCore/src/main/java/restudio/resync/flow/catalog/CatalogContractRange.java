package restudio.resync.flow.catalog;

import java.util.Objects;

public record CatalogContractRange(CatalogVersion minimum, CatalogVersion maximum) {
    public CatalogContractRange {
        minimum = Objects.requireNonNull(minimum, "minimum");
        maximum = Objects.requireNonNull(maximum, "maximum");
        if (minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Contract range minimum exceeds maximum");
        }
    }

    public boolean contains(CatalogVersion version) {
        return Objects.requireNonNull(version, "version").isBetween(minimum, maximum);
    }
}
