package restudio.resync.metadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MetadataManifest(int formatVersion, String resolverRevision, List<MetadataBundleDescriptor> bundles) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public MetadataManifest {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported metadata manifest format version: " + formatVersion);
        }
        resolverRevision = MetadataValidation.text(resolverRevision, "Metadata resolver revision", 256);
        Objects.requireNonNull(bundles, "Metadata bundle descriptors are required");
        ArrayList<MetadataBundleDescriptor> sorted = new ArrayList<>(bundles.size());
        for (MetadataBundleDescriptor bundle : bundles) {
            sorted.add(Objects.requireNonNull(bundle, "Metadata bundle descriptor is required"));
        }
        sorted.sort(MetadataBundleDescriptor::compareTo);
        Set<SelectorKey> selectors = new HashSet<>();
        for (MetadataBundleDescriptor bundle : sorted) {
            if (!selectors.add(new SelectorKey(bundle.artifactFamily(), bundle.selector()))) {
                throw new IllegalArgumentException("Metadata manifest contains an ambiguous artifact selector");
            }
        }
        bundles = List.copyOf(sorted);
    }

    public MetadataManifest(String resolverRevision, List<MetadataBundleDescriptor> bundles) {
        this(CURRENT_FORMAT_VERSION, resolverRevision, bundles);
    }

    private record SelectorKey(MetadataArtifactFamily family, MetadataSelector selector) {
    }
}
