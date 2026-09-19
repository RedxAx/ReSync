package restudio.resync.metadata;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

public record MetadataSelector(MetadataArtifactFamily artifactFamily, String edition, String minecraftVersion,
                               Integer minimumDataVersion, Integer maximumDataVersion,
                               Integer minimumProtocolVersion, Integer maximumProtocolVersion,
                               String softwareFamily, String softwareVersion, String distribution,
                               Set<String> requiredCapabilities) implements Comparable<MetadataSelector> {
    private static final Comparator<String> OPTIONAL_TEXT = Comparator.nullsFirst(String::compareTo);
    private static final Comparator<Integer> OPTIONAL_NUMBER = Comparator.nullsFirst(Integer::compareTo);

    public MetadataSelector {
        artifactFamily = Objects.requireNonNull(artifactFamily, "Metadata selector artifact family is required");
        edition = MetadataValidation.optionalId(edition, "Metadata selector edition");
        minecraftVersion = MetadataValidation.optionalText(minecraftVersion, "Metadata selector Minecraft version", 128);
        minimumDataVersion = nonNegative(minimumDataVersion, "Minimum data version");
        maximumDataVersion = nonNegative(maximumDataVersion, "Maximum data version");
        minimumProtocolVersion = nonNegative(minimumProtocolVersion, "Minimum protocol version");
        maximumProtocolVersion = nonNegative(maximumProtocolVersion, "Maximum protocol version");
        requireRange(minimumDataVersion, maximumDataVersion, "Data version");
        requireRange(minimumProtocolVersion, maximumProtocolVersion, "Protocol version");
        softwareFamily = MetadataValidation.optionalId(softwareFamily, "Metadata selector software family");
        softwareVersion = MetadataValidation.optionalText(softwareVersion, "Metadata selector software version", 128);
        distribution = MetadataValidation.optionalId(distribution, "Metadata selector distribution");
        if (softwareVersion != null && softwareFamily == null) {
            throw new IllegalArgumentException("Metadata selector software version requires a software family");
        }
        requiredCapabilities = MetadataValidation.capabilities(requiredCapabilities);
    }

    public boolean matches(MetadataCoordinate coordinate, Set<String> capabilities) {
        Objects.requireNonNull(coordinate, "Metadata coordinate is required");
        Set<String> availableCapabilities = MetadataValidation.capabilities(capabilities);
        return matches(edition, coordinate.edition())
            && matches(minecraftVersion, coordinate.minecraftVersion())
            && within(coordinate.dataVersion(), minimumDataVersion, maximumDataVersion)
            && within(coordinate.protocolVersion(), minimumProtocolVersion, maximumProtocolVersion)
            && matches(softwareFamily, coordinate.softwareFamily())
            && matches(softwareVersion, coordinate.softwareVersion())
            && matches(distribution, coordinate.distribution())
            && availableCapabilities.containsAll(requiredCapabilities);
    }

    public int specificity() {
        int value = requiredCapabilities.size();
        if (edition != null) value++;
        if (minecraftVersion != null) value++;
        if (minimumDataVersion != null) value++;
        if (maximumDataVersion != null) value++;
        if (minimumProtocolVersion != null) value++;
        if (maximumProtocolVersion != null) value++;
        if (softwareFamily != null) value++;
        if (softwareVersion != null) value++;
        if (distribution != null) value++;
        return value;
    }

    @Override
    public int compareTo(MetadataSelector other) {
        int result = artifactFamily.compareTo(other.artifactFamily);
        if (result == 0) result = OPTIONAL_TEXT.compare(edition, other.edition);
        if (result == 0) result = OPTIONAL_TEXT.compare(minecraftVersion, other.minecraftVersion);
        if (result == 0) result = OPTIONAL_NUMBER.compare(minimumDataVersion, other.minimumDataVersion);
        if (result == 0) result = OPTIONAL_NUMBER.compare(maximumDataVersion, other.maximumDataVersion);
        if (result == 0) result = OPTIONAL_NUMBER.compare(minimumProtocolVersion, other.minimumProtocolVersion);
        if (result == 0) result = OPTIONAL_NUMBER.compare(maximumProtocolVersion, other.maximumProtocolVersion);
        if (result == 0) result = OPTIONAL_TEXT.compare(softwareFamily, other.softwareFamily);
        if (result == 0) result = OPTIONAL_TEXT.compare(softwareVersion, other.softwareVersion);
        if (result == 0) result = OPTIONAL_TEXT.compare(distribution, other.distribution);
        if (result == 0) result = compareCapabilities(requiredCapabilities, other.requiredCapabilities);
        return result;
    }

    private static boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private static boolean within(Integer value, Integer minimum, Integer maximum) {
        if (minimum == null && maximum == null) {
            return true;
        }
        return value != null && (minimum == null || value >= minimum) && (maximum == null || value <= maximum);
    }

    private static Integer nonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static void requireRange(Integer minimum, Integer maximum, String field) {
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException(field + " minimum must not exceed its maximum");
        }
    }

    private static int compareCapabilities(Set<String> left, Set<String> right) {
        var leftIterator = left.iterator();
        var rightIterator = right.iterator();
        while (leftIterator.hasNext() && rightIterator.hasNext()) {
            int result = leftIterator.next().compareTo(rightIterator.next());
            if (result != 0) {
                return result;
            }
        }
        return Boolean.compare(leftIterator.hasNext(), rightIterator.hasNext());
    }
}
