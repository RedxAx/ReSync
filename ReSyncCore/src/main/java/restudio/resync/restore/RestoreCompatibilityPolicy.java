package restudio.resync.restore;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.migration.SnapshotMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record RestoreCompatibilityPolicy(
    int minimumSnapshotFormat,
    int maximumSnapshotFormat,
    Set<String> compatibleBuilds,
    CatalogVersion contractVersion,
    CatalogBinding catalogBinding,
    Map<String, String> extensionVersions
) {
    public RestoreCompatibilityPolicy {
        if (minimumSnapshotFormat < 1 || maximumSnapshotFormat < minimumSnapshotFormat) {
            throw new IllegalArgumentException("Snapshot Format Range Is Invalid");
        }
        compatibleBuilds = normalizeBuilds(compatibleBuilds);
        contractVersion = Objects.requireNonNull(contractVersion, "contractVersion");
        catalogBinding = Objects.requireNonNull(catalogBinding, "catalogBinding");
        extensionVersions = normalizeExtensions(extensionVersions);
    }

    public List<RestorePreflight.Check> checks(SnapshotMetadata metadata) {
        List<RestorePreflight.Check> checks = new ArrayList<>();
        if (metadata == null) {
            checks.add(new RestorePreflight.Check("snapshot-manifest", false, "Snapshot Metadata Is Required"));
            return List.copyOf(checks);
        }
        checks.add(new RestorePreflight.Check("snapshot-format", metadata.formatVersion() >= minimumSnapshotFormat && metadata.formatVersion() <= maximumSnapshotFormat, "Received " + metadata.formatVersion() + ", supported " + minimumSnapshotFormat + "-" + maximumSnapshotFormat));
        checks.add(new RestorePreflight.Check("build", compatibleBuilds.contains(metadata.build()), "Received " + metadata.build() + ", supported " + String.join(", ", compatibleBuilds)));
        checks.add(new RestorePreflight.Check("contract-catalog", catalogBinding.catalogChecksum().canonicalText().equals(metadata.catalogChecksum()), "Received " + metadata.catalogChecksum() + ", expected " + catalogBinding.catalogChecksum().canonicalText() + " for contract " + contractVersion));
        checks.add(new RestorePreflight.Check("extension-manifest", extensionVersions.equals(metadata.extensionVersions()), "Received " + metadata.extensionVersions() + ", expected " + extensionVersions));
        return List.copyOf(checks);
    }

    private static Set<String> normalizeBuilds(Set<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("At Least One Compatible Build Is Required");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            String build = requireText(value, "compatible build");
            if (!normalized.add(build)) {
                throw new IllegalArgumentException("Duplicate Compatible Build: " + build);
            }
        }
        return Set.copyOf(normalized);
    }

    private static Map<String, String> normalizeExtensions(Map<String, String> values) {
        TreeMap<String, String> normalized = new TreeMap<>();
        if (values != null) {
            values.forEach((owner, version) -> normalized.put(requireText(owner, "extension owner"), requireText(version, "extension version")));
        }
        return Map.copyOf(new LinkedHashMap<>(normalized));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Required");
        }
        return value;
    }
}
