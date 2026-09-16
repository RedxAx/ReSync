package restudio.resync.flow.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CatalogVersionRange {
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+][0-9A-Za-z.-]+)?$");
    private final List<Constraint> constraints;

    private CatalogVersionRange(List<Constraint> constraints) {
        this.constraints = List.copyOf(constraints);
    }

    public static CatalogVersionRange parse(String value) {
        String normalized = CatalogIds.required(value, "versionRange");
        if (normalized.equals("*") || normalized.equalsIgnoreCase("any")) {
            return new CatalogVersionRange(List.of());
        }
        List<Constraint> parsed = new ArrayList<>();
        for (String part : normalized.split("[ ,]+")) {
            if (!part.isBlank()) {
                parsed.add(parseConstraint(part));
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Version range contains no constraints");
        }
        return new CatalogVersionRange(parsed);
    }

    public boolean includes(String version) {
        Semver parsed = Semver.parse(version);
        return constraints.stream().allMatch(constraint -> constraint.matches(parsed));
    }

    private static Constraint parseConstraint(String value) {
        if (value.startsWith("^")) {
            Semver base = Semver.parse(value.substring(1));
            Semver upper = new Semver(base.major() + 1, 0, 0);
            return new Constraint(Operator.GREATER_OR_EQUAL, base, upper);
        }
        if (value.startsWith("~")) {
            Semver base = Semver.parse(value.substring(1));
            Semver upper = new Semver(base.major(), base.minor() + 1, 0);
            return new Constraint(Operator.GREATER_OR_EQUAL, base, upper);
        }
        Operator operator = Operator.EXACT;
        String version = value;
        for (Operator candidate : List.of(Operator.GREATER_OR_EQUAL, Operator.LESS_OR_EQUAL, Operator.GREATER, Operator.LESS)) {
            if (value.startsWith(candidate.symbol())) {
                operator = candidate;
                version = value.substring(candidate.symbol().length());
                break;
            }
        }
        return new Constraint(operator, Semver.parse(version), null);
    }

    private enum Operator {
        EXACT(""),
        GREATER_OR_EQUAL(">="),
        LESS_OR_EQUAL("<="),
        GREATER(">"),
        LESS("<");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        String symbol() {
            return symbol;
        }
    }

    private record Constraint(Operator operator, Semver base, Semver upperExclusive) {
        boolean matches(Semver value) {
            return switch (operator) {
                case EXACT -> value.compareTo(base) == 0;
                case GREATER_OR_EQUAL -> value.compareTo(base) >= 0 && (upperExclusive == null || value.compareTo(upperExclusive) < 0);
                case LESS_OR_EQUAL -> value.compareTo(base) <= 0;
                case GREATER -> value.compareTo(base) > 0;
                case LESS -> value.compareTo(base) < 0;
            };
        }
    }

    private record Semver(int major, int minor, int patch) implements Comparable<Semver> {
        static Semver parse(String value) {
            String normalized = CatalogIds.required(value, "version");
            Matcher matcher = VERSION.matcher(normalized);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid semantic version: " + normalized);
            }
            return new Semver(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        }

        @Override
        public int compareTo(Semver other) {
            int majorResult = Integer.compare(major, other.major);
            if (majorResult != 0) {
                return majorResult;
            }
            int minorResult = Integer.compare(minor, other.minor);
            return minorResult != 0 ? minorResult : Integer.compare(patch, other.patch);
        }
    }
}
