package restudio.resync.flow.identity;

import java.util.Objects;

public record CatalogBinding(long generation, ContentHash catalogChecksum, ContentHash bindingManifestHash) implements Comparable<CatalogBinding> {
    public CatalogBinding {
        if (generation < 1) {
            throw new IllegalArgumentException("Catalog generation must be at least 1");
        }
        catalogChecksum = Objects.requireNonNull(catalogChecksum, "Catalog checksum is required");
        bindingManifestHash = Objects.requireNonNull(bindingManifestHash, "Binding manifest hash is required");
    }

    public CatalogBinding(long generation, String catalogChecksum, String bindingManifestHash) {
        this(generation, new ContentHash(catalogChecksum), new ContentHash(bindingManifestHash));
    }

    public static CatalogBinding of(long generation, ContentHash catalogChecksum, ContentHash bindingManifestHash) {
        return new CatalogBinding(generation, catalogChecksum, bindingManifestHash);
    }

    public String canonicalText() {
        return generation + "|" + catalogChecksum.canonicalText() + "|" + bindingManifestHash.canonicalText();
    }

    public static CatalogBinding parseCanonicalText(String value) {
        Objects.requireNonNull(value, "Catalog binding text is required");
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Catalog binding text must contain generation and two hashes");
        }
        long generation;
        try {
            generation = Long.parseLong(parts[0]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Catalog generation must be an integer", exception);
        }
        CatalogBinding binding = new CatalogBinding(generation, new ContentHash(parts[1]), new ContentHash(parts[2]));
        if (!binding.canonicalText().equals(value)) {
            throw new IllegalArgumentException("Catalog binding text is not canonical");
        }
        return binding;
    }

    @Override
    public int compareTo(CatalogBinding other) {
        int generationOrder = Long.compare(generation, other.generation);
        if (generationOrder != 0) {
            return generationOrder;
        }
        int catalogOrder = catalogChecksum.compareTo(other.catalogChecksum);
        return catalogOrder != 0 ? catalogOrder : bindingManifestHash.compareTo(other.bindingManifestHash);
    }
}
