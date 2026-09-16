package restudio.resync.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PersistenceExternalInput {
    public static final String PROPERTIES_ID = "resync.properties";
    public static final String NODES_ID = "dataRoot/nodes";

    private PersistenceExternalInput() {
    }

    public static List<Input> forDataRoot(Path dataRoot) {
        Path root = requireRoot(dataRoot);
        return validate(root, List.of(
            new Input(PROPERTIES_ID, root.resolve("resync.properties"), Kind.OPERATOR_CONFIGURATION,
                "Operator-owned ReSync configuration is read-only runtime input and is excluded from ReSync persistence"),
            new Input(NODES_ID, root.resolve("nodes"), Kind.OPERATOR_CATALOG,
                "Operator-owned node definitions are read-only runtime input and are excluded from ReSync persistence")));
    }

    public static List<Input> validate(Path dataRoot, Collection<Input> inputs) {
        Path root = requireRoot(dataRoot);
        Map<String, Input> unique = new LinkedHashMap<>();
        for (Input input : inputs == null ? List.<Input>of() : inputs) {
            Input value = Objects.requireNonNull(input, "external input");
            if (!value.path().startsWith(root) || value.path().equals(root)) {
                throw new IllegalArgumentException("External Input Must Be Inside Data Root: " + value.id());
            }
            if (unique.putIfAbsent(value.id(), value) != null) {
                throw new IllegalArgumentException("External Input ID Is Ambiguous: " + value.id());
            }
        }
        List<Input> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(Input::id));
        for (int first = 0; first < sorted.size(); first++) {
            Input left = sorted.get(first);
            for (int second = first + 1; second < sorted.size(); second++) {
                Input right = sorted.get(second);
                if (left.path().equals(right.path()) || left.path().startsWith(right.path()) || right.path().startsWith(left.path())) {
                    throw new IllegalArgumentException("External Input Paths Overlap: " + left.id() + " And " + right.id());
                }
            }
        }
        return List.copyOf(sorted);
    }

    public static boolean contains(Collection<Input> inputs, Path path) {
        Path candidate = requireRoot(path);
        return inputs != null && inputs.stream()
            .filter(Objects::nonNull)
            .anyMatch(input -> input.owns(candidate));
    }

    private static Path requireRoot(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public enum Kind {
        OPERATOR_CONFIGURATION,
        OPERATOR_CATALOG
    }

    public record Input(String id, Path path, Kind kind, String reason) {
        public Input {
            id = requireText(id, "id");
            path = requireRoot(path);
            kind = Objects.requireNonNull(kind, "kind");
            reason = requireText(reason, "reason");
        }

        public boolean readOnly() {
            return true;
        }

        public boolean excludedFromPersistence() {
            return true;
        }

        public boolean owns(Path candidate) {
            Path normalized = requireRoot(candidate);
            return normalized.equals(path) || normalized.startsWith(path);
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " Must Not Be Blank");
            }
            return value.trim();
        }
    }
}
