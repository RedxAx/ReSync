package restudio.resync.flow.registry;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.NodeId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record AuthoredNodeMetadata(String id, String domain, String family, String lifecycle, String description,
                                   String handlerCapability, String selectorIntent, String inspectorIntent,
                                   AuthoredSourceProvenance sourceProvenance) {
    private static final Set<String> LIFECYCLES = Set.of("active", "deprecated", "retiring", "migration-only");

    public AuthoredNodeMetadata(String id, String domain, String family, String lifecycle, String description,
                                String handlerCapability, String selectorIntent, String inspectorIntent) {
        this(id, domain, family, lifecycle, description, handlerCapability, selectorIntent, inspectorIntent, null);
    }

    public AuthoredNodeMetadata {
        id = identifier(id, "Authored node ID", NodeId::of);
        domain = identifier(domain, "Authored node domain", CapabilityId::of);
        family = identifier(family, "Authored node family", CapabilityId::of);
        lifecycle = required(lifecycle, "Authored node lifecycle");
        if (!LIFECYCLES.contains(lifecycle.strip().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported authored node lifecycle: " + lifecycle);
        }
        description = description(description);
        handlerCapability = identifier(handlerCapability, "Authored handler capability", CapabilityId::of);
        selectorIntent = bounded(selectorIntent, "Authored selector intent", 128);
        inspectorIntent = bounded(inspectorIntent, "Authored inspector intent", 128);
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("domain", domain);
        values.put("family", family);
        values.put("lifecycle", lifecycle);
        values.put("description", description);
        values.put("handlerCapability", handlerCapability);
        values.put("selectorIntent", selectorIntent);
        values.put("inspectorIntent", inspectorIntent);
        if (sourceProvenance != null) {
            values.put("sourceProvenance", sourceProvenance.toMetadata());
        }
        return Collections.unmodifiableMap(values);
    }

    public AuthoredNodeMetadata withSourceProvenance(AuthoredSourceProvenance value) {
        return new AuthoredNodeMetadata(id, domain, family, lifecycle, description, handlerCapability,
            selectorIntent, inspectorIntent, Objects.requireNonNull(value, "sourceProvenance"));
    }

    private static String identifier(String value, String field, java.util.function.Function<String, ?> parser) {
        String required = required(value, field);
        try {
            parser.apply(required);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " cannot be represented by the Core identity contract", exception);
        }
        return required;
    }

    private static String description(String value) {
        String required = required(value, "Authored node description");
        String normalized = required.strip();
        if (normalized.length() < 24 || normalized.length() > 280) {
            throw new IllegalArgumentException("Authored node description cannot be represented by the Core descriptor contract");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.matches("^(todo|tbd|placeholder|lorem ipsum|n/?a|none|unknown|fill in|coming soon)([ .,:;-].*)?$")) {
            throw new IllegalArgumentException("Authored node description must contain product text");
        }
        return required;
    }

    private static String bounded(String value, String field, int maximum) {
        String required = required(value, field);
        if (required.length() > maximum) {
            throw new IllegalArgumentException(field + " cannot be represented by the Core descriptor contract");
        }
        return required;
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
