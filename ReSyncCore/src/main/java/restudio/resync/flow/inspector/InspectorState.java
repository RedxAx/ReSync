package restudio.resync.flow.inspector;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public final class InspectorState {
    private final Status state;
    private final Map<InspectorFieldId, TypedValue> fields;
    private final Map<String, Object> unknown;
    private final List<Diagnostic> validation;
    private final InspectorFallback fallback;
    private final UUID draftId;
    private final Long baseRevision;

    public InspectorState(Status state, Map<InspectorFieldId, TypedValue> fields, Map<String, ?> unknown, List<Diagnostic> validation, InspectorFallback fallback, UUID draftId, Long baseRevision) {
        this.state = state == null ? Status.CLEAN : state;
        this.fields = typedFields(fields);
        InspectorSupport.rejectKnownKeys(unknown == null ? Map.of() : unknown, "unknown inspector state", "kind", "state", "fields", "unknown", "validation", "fallback", "draftId", "baseRevision");
        this.unknown = InspectorSupport.map(unknown == null ? Map.of() : unknown, "unknown inspector state");
        this.validation = InspectorSupport.list(validation == null ? List.of() : validation, "validation message");
        this.fallback = fallback == null ? InspectorFallback.GENERIC : fallback;
        if (this.fallback == InspectorFallback.REJECT) {
            throw new IllegalArgumentException("Inspector state fallback cannot reject persisted state");
        }
        if (baseRevision != null && baseRevision < 0) {
            throw new IllegalArgumentException("Inspector base revision cannot be negative");
        }
        if (this.state == Status.DRAFT && draftId == null) {
            throw new IllegalArgumentException("Draft inspector state requires a draft ID");
        }
        this.draftId = draftId;
        this.baseRevision = baseRevision;
    }

    public static InspectorState clean(Map<InspectorFieldId, TypedValue> fields) {
        return new InspectorState(Status.CLEAN, fields, Map.of(), List.of(), InspectorFallback.GENERIC, null, null);
    }

    public Status state() {
        return state;
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

    public UUID draftId() {
        return draftId;
    }

    public Long baseRevision() {
        return baseRevision;
    }

    public Map<String, Object> toWireMap() {
        var value = new LinkedHashMap<String, Object>();
        value.put("kind", "inspector-state");
        value.put("state", state.wireName());
        value.put("fields", wireFields(fields));
        value.put("unknown", unknown);
        value.put("validation", validation.stream().map(Diagnostic::toMap).toList());
        value.put("fallback", fallback.stateWireName());
        if (draftId != null) {
            value.put("draftId", draftId);
        }
        if (baseRevision != null) {
            value.put("baseRevision", baseRevision);
        }
        return Map.copyOf(value);
    }

    public String serialized() {
        return CanonicalJson.canonicalize(toWireMap());
    }

    private static Map<InspectorFieldId, TypedValue> typedFields(Map<InspectorFieldId, TypedValue> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var copy = new TreeMap<InspectorFieldId, TypedValue>();
        for (var entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "field"), Objects.requireNonNull(entry.getValue(), "typed field value"));
        }
        return Map.copyOf(copy);
    }

    private static Map<String, Object> wireFields(Map<InspectorFieldId, TypedValue> source) {
        return InspectorDraft.wireFields(source);
    }

    public enum Status {
        CLEAN("clean"),
        DRAFT("draft"),
        SAVING("saving"),
        CONFLICT("conflict"),
        READ_ONLY("read-only");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
