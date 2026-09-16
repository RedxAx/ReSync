package restudio.resync.flow.inspector;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record InspectorConflict(UUID mutationId, ServerResourceLocator resource, long authoritativeRevision, Map<String, Object> authoritativePayload, InspectorDraft draft, List<Diagnostic> diagnostics) {
    public InspectorConflict {
        mutationId = Objects.requireNonNull(mutationId, "mutation ID");
        resource = Objects.requireNonNull(resource, "resource");
        if (authoritativeRevision < 0) {
            throw new IllegalArgumentException("Authoritative revision cannot be negative");
        }
        authoritativePayload = InspectorSupport.map(authoritativePayload == null ? Map.of() : authoritativePayload, "authoritative payload");
        draft = Objects.requireNonNull(draft, "draft");
        diagnostics = InspectorSupport.list(diagnostics, "conflict diagnostic");
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Inspector conflicts require diagnostics");
        }
    }

    public Map<String, Object> toWireMap() {
        var value = new LinkedHashMap<String, Object>();
        value.put("kind", "inspector-conflict");
        value.put("mutationId", mutationId);
        value.put("resource", resource.canonicalValue());
        value.put("authoritativeRevision", authoritativeRevision);
        value.put("authoritativePayload", authoritativePayload);
        value.put("draft", draft.toWireMap());
        value.put("diagnostics", diagnostics.stream().map(Diagnostic::toMap).toList());
        return Map.copyOf(value);
    }

    public String serialized() {
        return CanonicalJson.canonicalize(toWireMap());
    }
}
