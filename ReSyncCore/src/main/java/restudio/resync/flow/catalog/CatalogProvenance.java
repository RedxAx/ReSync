package restudio.resync.flow.catalog;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record CatalogProvenance(SourceKind sourceKind, String sourceUri, ContentHash sourceHash, String sourceVersion, String buildId, String loadedAt, List<SourceEntry> entries) {
    public CatalogProvenance {
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        sourceUri = CatalogIds.text(sourceUri, "sourceUri", 1024);
        sourceHash = Objects.requireNonNull(sourceHash, "sourceHash");
        sourceVersion = CatalogIds.text(sourceVersion, "sourceVersion", 128);
        buildId = CatalogIds.text(buildId, "buildId", 128);
        loadedAt = CatalogIds.optionalText(loadedAt, "loadedAt", 128);
        entries = entries == null ? List.of() : entries.stream().filter(Objects::nonNull)
            .sorted(Comparator.comparing(SourceEntry::sourceUri)
                .thenComparingInt(SourceEntry::rowIndex)
                .thenComparing(SourceEntry::owner)
                .thenComparing(SourceEntry::definitionId))
            .toList();
    }

    public CatalogProvenance(SourceKind sourceKind, String sourceUri, ContentHash sourceHash, String sourceVersion, String buildId, String loadedAt) {
        this(sourceKind, sourceUri, sourceHash, sourceVersion, buildId, loadedAt, List.of());
    }

    public CatalogProvenance(SourceKind sourceKind, String sourceUri, String sourceVersion, String buildId) {
        this(sourceKind, sourceUri, ContentHash.of(CanonicalJson.genericCanonicalContentHash(sourceUri)), sourceVersion, buildId, null, List.of());
    }

    public static CatalogProvenance fromText(SourceKind sourceKind, String sourceUri, String sourceVersion, String buildId, String sourceText) {
        return new CatalogProvenance(sourceKind, sourceUri, ContentHash.of(CanonicalJson.genericCanonicalContentHash(sourceText)), sourceVersion, buildId, null, List.of());
    }

    public CatalogProvenance withEntries(List<SourceEntry> values) {
        return new CatalogProvenance(sourceKind, sourceUri, sourceHash, sourceVersion, buildId, loadedAt, values);
    }

    public List<SourceEntry> sources() {
        return entries;
    }

    public List<SourceEntry> sourceEntries() {
        return entries;
    }

    public CatalogProvenance forDefinition(String definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        List<SourceEntry> matches = entries.stream().filter(value -> value.definitionId().equals(definitionId)).toList();
        if (matches.isEmpty()) {
            return this;
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("CATALOG.PROVENANCE_AMBIGUOUS: " + definitionId);
        }
        SourceEntry entry = matches.getFirst();
        return new CatalogProvenance(sourceKind, entry.sourceUri(), entry.sourceHash(), sourceVersion, buildId, loadedAt, matches);
    }

    public record SourceEntry(String sourceUri, int rowIndex, OwnerId owner, String definitionId, ContentHash sourceHash) {
        public SourceEntry {
            sourceUri = CatalogIds.text(sourceUri, "sourceEntry.sourceUri", 1024);
            if (rowIndex < 0) {
                throw new IllegalArgumentException("Source entry row must be non-negative");
            }
            owner = Objects.requireNonNull(owner, "sourceEntry.owner");
            definitionId = CatalogIds.local(definitionId, "sourceEntry.definitionId");
            sourceHash = Objects.requireNonNull(sourceHash, "sourceEntry.sourceHash");
        }

        public SourceEntry(String sourceUri, int rowIndex, String owner, String definitionId, String sourceHash) {
            this(sourceUri, rowIndex, OwnerId.of(owner), definitionId, ContentHash.parseCanonicalText(sourceHash));
        }
    }

    public enum SourceKind {
        BUNDLED,
        LOCAL,
        FUNCTION,
        EXTENSION
    }
}
