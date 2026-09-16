package restudio.resync.flow.inspector;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public final class InspectorDraft {
    private final UUID draftId;
    private final ServerResourceLocator resource;
    private final long baseRevision;
    private final Map<InspectorFieldId, TypedValue> fields;
    private final Map<String, Object> unknown;
    private final List<Diagnostic> validation;
    private final InspectorFallback fallback;

    public InspectorDraft(UUID draftId, ServerResourceLocator resource, long baseRevision, Map<InspectorFieldId, TypedValue> fields, Map<String, ?> unknown, List<Diagnostic> validation, InspectorFallback fallback) {
        this.draftId = Objects.requireNonNull(draftId, "draft ID");
        this.resource = Objects.requireNonNull(resource, "resource");
        if (baseRevision < 0) {
            throw new IllegalArgumentException("Draft base revision cannot be negative");
        }
        this.baseRevision = baseRevision;
        this.fields = typedFields(fields);
        InspectorSupport.rejectKnownKeys(unknown == null ? Map.of() : unknown, "unknown draft data", "kind", "draftId", "resource", "baseRevision", "fields", "unknown", "validation", "fallback");
        this.unknown = InspectorSupport.map(unknown == null ? Map.of() : unknown, "unknown draft data");
        this.validation = InspectorSupport.list(validation == null ? List.of() : validation, "draft validation message");
        this.fallback = fallback == null ? InspectorFallback.GENERIC : fallback;
        if (this.fallback == InspectorFallback.REJECT) {
            throw new IllegalArgumentException("Inspector draft fallback cannot reject persisted state");
        }
    }

    public InspectorDraft(UUID draftId, ServerResourceLocator resource, long baseRevision, Map<InspectorFieldId, TypedValue> fields) {
        this(draftId, resource, baseRevision, fields, Map.of(), List.of(), InspectorFallback.GENERIC);
    }

    public UUID draftId() {
        return draftId;
    }

    public ServerResourceLocator resource() {
        return resource;
    }

    public long baseRevision() {
        return baseRevision;
    }

    public Map<InspectorFieldId, TypedValue> fields() {
        return fields;
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public List<Diagnostic> validation() {
        return validation;
    }

    public InspectorFallback fallback() {
        return fallback;
    }

    public InspectorState state(InspectorState.Status status) {
        return new InspectorState(status, fields, unknown, validation, fallback, draftId, baseRevision);
    }

    public InspectorMutation mutation(UUID mutationId) {
        return new InspectorMutation(mutationId, resource, baseRevision, fields, unknown);
    }

    public Map<String, Object> toWireMap() {
        var value = new LinkedHashMap<String, Object>();
        value.put("kind", "inspector-draft");
        value.put("draftId", draftId);
        value.put("resource", resource.canonicalValue());
        value.put("baseRevision", baseRevision);
        value.put("fields", wireFields(fields));
        value.put("unknown", unknown);
        value.put("validation", validation.stream().map(Diagnostic::toMap).toList());
        value.put("fallback", fallback.stateWireName());
        return Map.copyOf(value);
    }

    public String serialized() {
        return CanonicalJson.canonicalize(toWireMap());
    }

    static Map<InspectorFieldId, TypedValue> typedFields(Map<InspectorFieldId, TypedValue> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var copy = new TreeMap<InspectorFieldId, TypedValue>();
        for (var entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "field"), Objects.requireNonNull(entry.getValue(), "typed field value"));
        }
        return Map.copyOf(copy);
    }

    static Map<String, Object> wireFields(Map<InspectorFieldId, TypedValue> source) {
        var fields = new TreeMap<String, Object>();
        for (var entry : source.entrySet()) {
            fields.put(entry.getKey().value(), CanonicalJson.parse(entry.getValue().canonicalJson()));
        }
        return Map.copyOf(fields);
    }
}
