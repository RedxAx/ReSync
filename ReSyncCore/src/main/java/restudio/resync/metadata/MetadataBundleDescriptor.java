package restudio.resync.metadata;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record MetadataBundleDescriptor(MetadataBundleId bundleId, MetadataArtifactFamily artifactFamily, int formatVersion,
                                       MetadataSelector selector, long byteSize, Instant createdAt,
                                       Map<String, String> provenance) implements Comparable<MetadataBundleDescriptor> {
    public static final long MAX_EXACT_BYTE_SIZE = 9_007_199_254_740_991L;

    public MetadataBundleDescriptor {
        bundleId = Objects.requireNonNull(bundleId, "Metadata bundle ID is required");
        artifactFamily = Objects.requireNonNull(artifactFamily, "Metadata artifact family is required");
        if (formatVersion < 1) {
            throw new IllegalArgumentException("Metadata bundle format version must be positive");
        }
        selector = Objects.requireNonNull(selector, "Metadata selector is required");
        if (!artifactFamily.equals(selector.artifactFamily())) {
            throw new IllegalArgumentException("Metadata descriptor family must match its selector family");
        }
        if (byteSize < 1 || byteSize > MAX_EXACT_BYTE_SIZE) {
            throw new IllegalArgumentException("Metadata bundle byte size must be positive and exactly representable");
        }
        createdAt = Objects.requireNonNull(createdAt, "Metadata bundle creation time is required");
        provenance = MetadataValidation.provenance(provenance);
    }

    @Override
    public int compareTo(MetadataBundleDescriptor other) {
        int result = artifactFamily.compareTo(other.artifactFamily);
        if (result == 0) result = selector.compareTo(other.selector);
        if (result == 0) result = Integer.compare(formatVersion, other.formatVersion);
        if (result == 0) result = bundleId.compareTo(other.bundleId);
        return result;
    }
}
