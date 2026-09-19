package restudio.resync.metadata;

public record MetadataArtifactFamily(String value) implements Comparable<MetadataArtifactFamily> {
    public MetadataArtifactFamily {
        value = MetadataValidation.id(value, "Metadata artifact family");
        if (value.codePointCount(0, value.length()) > 64) {
            throw new IllegalArgumentException("Metadata artifact family exceeds 64 code points");
        }
    }

    public static MetadataArtifactFamily of(String value) {
        return new MetadataArtifactFamily(value);
    }

    public static MetadataArtifactFamily parseCanonicalText(String value) {
        return new MetadataArtifactFamily(value);
    }

    public String canonicalText() {
        return value;
    }

    @Override
    public int compareTo(MetadataArtifactFamily other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
