package restudio.resync.flow.graph;

import restudio.resync.flow.canonical.CanonicalJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StructuralRoute(String routeId, Kind kind, List<String> targetIds) {
    public StructuralRoute {
        routeId = requireId(routeId, "routeId");
        kind = Objects.requireNonNull(kind, "kind");
        var normalizedTargets = Objects.requireNonNull(targetIds, "targetIds").stream()
            .map(value -> requireId(value, "targetId"))
            .distinct()
            .toList();
        if (kind != Kind.REPEATABLE_GROUP) {
            normalizedTargets = normalizedTargets.stream().sorted().toList();
        }
        targetIds = List.copyOf(normalizedTargets);
        if (targetIds.isEmpty()) {
            throw new IllegalArgumentException("A structural route requires a target");
        }
    }

    public enum Kind {
        BRANCH("branch"),
        CASE("case"),
        REPEATABLE_GROUP("repeatable-group"),
        REPEATABLE_ELEMENT("repeatable-element"),
        PARAMETER("parameter"),
        INSPECTOR_FIELD("inspector-field");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    Map<String, Object> canonicalValue() {
        var value = new LinkedHashMap<String, Object>();
        value.put("routeId", routeId);
        value.put("kind", kind.wireValue());
        value.put("targetIds", targetIds);
        return value;
    }

    private static String requireId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 256 || value.indexOf('\u0000') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
