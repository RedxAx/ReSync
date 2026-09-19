package restudio.resync.metadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public record ServerSoftwareBundle(int formatVersion, String createdAt, List<GameRelease> gameReleases,
                                   List<SoftwareCategory> categories, List<SoftwareFamily> families) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final MetadataArtifactFamily ARTIFACT_FAMILY = MetadataArtifactFamily.of("server_software");

    public ServerSoftwareBundle {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported server software bundle format version: " + formatVersion);
        }
        createdAt = MetadataValidation.text(createdAt, "Server software bundle creation time", 128);
        gameReleases = sorted(gameReleases, GameRelease::id, "Game releases", true);
        categories = sorted(categories, SoftwareCategory::id, "Software categories", false);
        families = sorted(families, SoftwareFamily::id, "Software families", true);
        Set<String> releaseIds = ids(gameReleases, GameRelease::id);
        Set<String> categoryIds = ids(categories, SoftwareCategory::id);
        for (SoftwareFamily family : families) {
            for (String categoryId : family.categoryIds()) {
                if (!categoryIds.contains(categoryId)) {
                    throw new IllegalArgumentException("Software family references an unknown category: " + categoryId);
                }
            }
            for (String releaseId : family.compatibleGameReleaseIds()) {
                if (!releaseIds.contains(releaseId)) {
                    throw new IllegalArgumentException("Software family references an unknown compatible game release: " + releaseId);
                }
            }
            for (SoftwareVersion version : family.versions()) {
                if (version.gameReleaseId() != null && !family.compatibleGameReleaseIds().contains(version.gameReleaseId())) {
                    throw new IllegalArgumentException("Software version game release is not declared compatible by its family: "
                        + version.gameReleaseId());
                }
            }
        }
    }

    public ServerSoftwareBundle(String createdAt, List<GameRelease> gameReleases, List<SoftwareCategory> categories,
                                List<SoftwareFamily> families) {
        this(CURRENT_FORMAT_VERSION, createdAt, gameReleases, categories, families);
    }

    public record GameRelease(String id, String display, String type, String releaseTime, boolean supported, int order)
            implements Comparable<GameRelease> {
        public GameRelease {
            id = opaqueId(id, "Game release ID");
            display = MetadataValidation.text(display, "Game release display", 256);
            type = MetadataValidation.id(type, "Game release type");
            releaseTime = MetadataValidation.text(releaseTime, "Game release time", 128);
            order = checkedOrder(order, "Game release order");
        }

        @Override
        public int compareTo(GameRelease other) {
            return id.compareTo(other.id);
        }
    }

    public record SoftwareCategory(String id, String display) implements Comparable<SoftwareCategory> {
        public SoftwareCategory {
            id = MetadataValidation.id(id, "Software category ID");
            display = MetadataValidation.text(display, "Software category display", 256);
        }

        @Override
        public int compareTo(SoftwareCategory other) {
            return id.compareTo(other.id);
        }
    }

    public record SoftwareFamily(String id, String display, String description, String homepage, String icon, String color,
                                 boolean deprecated, boolean experimental, List<String> categoryIds,
                                 List<String> compatibility, List<String> compatibleGameReleaseIds,
                                 List<SoftwareVersion> versions) implements Comparable<SoftwareFamily> {
        public SoftwareFamily {
            id = MetadataValidation.id(id, "Software family ID");
            display = MetadataValidation.text(display, "Software family display", 256);
            description = MetadataValidation.optionalText(description, "Software family description", 2048);
            homepage = MetadataValidation.optionalText(homepage, "Software family homepage", 2048);
            icon = MetadataValidation.optionalText(icon, "Software family icon", 2048);
            color = MetadataValidation.optionalText(color, "Software family color", 32);
            categoryIds = sortedMetadataIds(categoryIds, "Software family category IDs");
            compatibility = sortedMetadataIds(compatibility, "Software family compatibility");
            compatibleGameReleaseIds = sortedIds(compatibleGameReleaseIds, "Compatible game release IDs", false);
            versions = sorted(versions, SoftwareVersion::id, "Software versions", true);
        }

        @Override
        public int compareTo(SoftwareFamily other) {
            return id.compareTo(other.id);
        }
    }

    public record SoftwareVersion(String id, String display, String gameReleaseId, boolean supported, String createdAt, int order,
                                  String latestBuildId, String latestBuildDisplay, int buildCount,
                                  List<SoftwareBuild> builds) implements Comparable<SoftwareVersion> {
        public SoftwareVersion {
            id = opaqueId(id, "Software version ID");
            display = MetadataValidation.text(display, "Software version display", 256);
            gameReleaseId = gameReleaseId == null ? null : opaqueId(gameReleaseId, "Software version game release ID");
            createdAt = MetadataValidation.text(createdAt, "Software version creation time", 128);
            order = checkedOrder(order, "Software version order");
            latestBuildId = opaqueId(latestBuildId, "Latest software build ID");
            latestBuildDisplay = MetadataValidation.text(latestBuildDisplay, "Latest software build display", 256);
            builds = sorted(builds, SoftwareBuild::id, "Software builds", true);
            if (buildCount != builds.size()) {
                throw new IllegalArgumentException("Software build count does not match the complete build list");
            }
            String checkedLatestBuildId = latestBuildId;
            SoftwareBuild latest = builds.stream().filter(build -> build.id().equals(checkedLatestBuildId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Latest software build is not present in the complete build list"));
            if (!latest.display().equals(latestBuildDisplay)) {
                throw new IllegalArgumentException("Latest software build display does not match the complete build list");
            }
        }

        @Override
        public int compareTo(SoftwareVersion other) {
            return id.compareTo(other.id);
        }
    }

    public record SoftwareBuild(String id, String display, int order) implements Comparable<SoftwareBuild> {
        public SoftwareBuild {
            id = opaqueId(id, "Software build ID");
            display = MetadataValidation.text(display, "Software build display", 256);
            order = checkedOrder(order, "Software build order");
        }

        @Override
        public int compareTo(SoftwareBuild other) {
            return id.compareTo(other.id);
        }
    }

    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:+-]{0,191}");

    private static int checkedOrder(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static String opaqueId(String value, String field) {
        String checked = MetadataValidation.text(value, field, 192);
        if (!OPAQUE_ID.matcher(checked).matches() || ".".equals(checked) || "..".equals(checked) || checked.contains("..")) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return checked;
    }

    private static List<String> sortedIds(List<String> values, String field, boolean requireValues) {
        Objects.requireNonNull(values, field + " are required");
        ArrayList<String> sorted = new ArrayList<>(values.size());
        Set<String> identities = new HashSet<>();
        for (String value : values) {
            String checked = opaqueId(value, field + " entry");
            if (!identities.add(checked)) {
                throw new IllegalArgumentException(field + " contain a duplicate identity: " + checked);
            }
            sorted.add(checked);
        }
        if (requireValues && sorted.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static List<String> sortedMetadataIds(List<String> values, String field) {
        Objects.requireNonNull(values, field + " are required");
        ArrayList<String> sorted = new ArrayList<>(values.size());
        Set<String> identities = new HashSet<>();
        for (String value : values) {
            String checked = MetadataValidation.id(value, field + " entry");
            if (!identities.add(checked)) {
                throw new IllegalArgumentException(field + " contain a duplicate identity: " + checked);
            }
            sorted.add(checked);
        }
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static <T> List<T> sorted(List<T> values, Function<T, String> identity, String field, boolean requireValues) {
        Objects.requireNonNull(values, field + " are required");
        ArrayList<T> sorted = new ArrayList<>(values.size());
        Set<String> identities = new HashSet<>();
        for (T value : values) {
            T checked = Objects.requireNonNull(value, field + " entry is required");
            String id = Objects.requireNonNull(identity.apply(checked), field + " identity is required");
            if (!identities.add(id)) {
                throw new IllegalArgumentException(field + " contain a duplicate identity: " + id);
            }
            sorted.add(checked);
        }
        if (requireValues && sorted.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        sorted.sort(Comparator.comparing(identity));
        return List.copyOf(sorted);
    }

    private static <T> Set<String> ids(List<T> values, Function<T, String> identity) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(identity.apply(value)));
        return Set.copyOf(result);
    }
}
