package restudio.resync.flow.graph;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ConversionRoute(
    ConnectionId connectionId,
    GraphEndpoint source,
    GraphEndpoint target,
    TypeExpr sourceType,
    TypeExpr targetType,
    List<TypeReference> conversionIds
) {
    public ConversionRoute {
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        source = Objects.requireNonNull(source, "source");
        target = Objects.requireNonNull(target, "target");
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        targetType = Objects.requireNonNull(targetType, "targetType");
        conversionIds = List.copyOf(Objects.requireNonNull(conversionIds, "conversionIds").stream()
            .map(value -> Objects.requireNonNull(value, "conversion ID"))
            .toList());
        if (sourceType.equals(targetType)) {
            throw new IllegalArgumentException("A conversion route must change its type");
        }
        if (conversionIds.isEmpty()) {
            throw new IllegalArgumentException("A conversion route requires at least one conversion");
        }
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    Map<String, Object> canonicalValue() {
        var value = new LinkedHashMap<String, Object>();
        value.put("connectionId", connectionId.canonicalText());
        value.put("source", source.canonicalValue());
        value.put("target", target.canonicalValue());
        value.put("sourceType", sourceType.canonicalValue());
        value.put("targetType", targetType.canonicalValue());
        value.put("conversionIds", conversionIds.stream().map(TypeReference::canonicalValue).toList());
        return value;
    }
}
