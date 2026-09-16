package restudio.resync.flow.graph;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class InspectorState {
    private final State state;
    private final Map<InspectorFieldId, TypedValue> fields;
    private final Map<PinId, PinValue> legacyFields;
    private final Fallback fallback;
    private final UUID draftId;
    private final Long baseRevision;
    private final OpaqueData unknown;

    public InspectorState(State state, Map<?, ?> fields, Fallback fallback, UUID draftId, Long baseRevision, OpaqueData unknown) {
        this.state = state != null ? state : State.CLEAN;
        GraphCollections.InspectorValues values = GraphCollections.inspectorValues(fields);
        this.fields = values.fields();
        this.legacyFields = values.pinValues();
        this.fallback = fallback != null ? fallback : Fallback.EDITABLE;
        if (baseRevision != null && baseRevision < 0) {
            throw new IllegalArgumentException("Inspector base revision cannot be negative");
        }
        if (this.state == State.DRAFT && draftId == null) {
            throw new IllegalArgumentException("Draft inspector state requires a draft ID");
        }
        this.draftId = draftId;
        this.baseRevision = baseRevision;
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        this.unknown.rejectKnownFields("kind", "state", "fields", "fallback", "draftId", "baseRevision");
    }

    public static InspectorState empty() {
        return new InspectorState(State.CLEAN, Map.of(), Fallback.EDITABLE, null, null, OpaqueData.empty());
    }

    public State state() {
        return state;
    }

    public Map<InspectorFieldId, TypedValue> fields() {
        return fields;
    }

    public Map<PinId, PinValue> legacyFields() {
        return legacyFields;
    }

    public Fallback fallback() {
        return fallback;
    }

    public UUID draftId() {
        return draftId;
    }

    public Long baseRevision() {
        return baseRevision;
    }

    public OpaqueData unknown() {
        return unknown;
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("kind", "inspector-state");
        values.put("state", state.name().toLowerCase(Locale.ROOT));
        LinkedHashMap<String, Object> fieldValues = new LinkedHashMap<>();
        legacyFields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> fieldValues.put(entry.getKey().canonicalText(), entry.getValue().canonicalValue()));
        fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (fieldValues.put(entry.getKey().canonicalText(), entry.getValue().canonicalValue()) != null) {
                throw new IllegalArgumentException("Duplicate inspector field identity: " + entry.getKey());
            }
        });
        values.put("fields", fieldValues);
        values.put("fallback", fallback.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (draftId != null) {
            values.put("draftId", draftId.toString());
        }
        if (baseRevision != null) {
            values.put("baseRevision", baseRevision);
        }
        return OpaqueData.mergeKnownFields(unknown, values);
    }

    public enum State {
        CLEAN,
        DRAFT,
        SAVING,
        CONFLICT,
        READ_ONLY
    }

    public enum Fallback {
        EDITABLE,
        READ_ONLY_FIELD,
        READ_ONLY_NODE,
        READ_ONLY_GRAPH
    }
}
