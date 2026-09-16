package restudio.resync.flow.inspector;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class InspectorMutation {
    private final UUID mutationId;
    private final ServerResourceLocator resource;
    private final long expectedRevision;
    private final Map<InspectorFieldId, TypedValue> fields;
    private final Map<String, Object> unknown;

    public InspectorMutation(UUID mutationId, ServerResourceLocator resource, long expectedRevision, Map<InspectorFieldId, TypedValue> fields, Map<String, ?> unknown) {
        this.mutationId = Objects.requireNonNull(mutationId, "mutation ID");
        this.resource = Objects.requireNonNull(resource, "resource");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected revision cannot be negative");
        }
        this.expectedRevision = expectedRevision;
        this.fields = InspectorDraft.typedFields(fields);
        InspectorSupport.rejectKnownKeys(unknown == null ? Map.of() : unknown, "unknown mutation data", "kind", "mutationId", "resource", "expectedRevision", "fields", "unknown");
        this.unknown = InspectorSupport.map(unknown == null ? Map.of() : unknown, "unknown mutation data");
    }

    public UUID mutationId() {
        return mutationId;
    }

    public ServerResourceLocator resource() {
        return resource;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public Map<InspectorFieldId, TypedValue> fields() {
        return fields;
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public Map<String, Object> toWireMap() {
        var value = new LinkedHashMap<String, Object>();
        value.put("kind", "inspector-mutation");
        value.put("mutationId", mutationId);
        value.put("resource", resource.canonicalValue());
        value.put("expectedRevision", expectedRevision);
        value.put("fields", InspectorDraft.wireFields(fields));
        value.put("unknown", unknown);
        return Map.copyOf(value);
    }

    public String serialized() {
        return CanonicalJson.canonicalize(toWireMap());
    }
}
