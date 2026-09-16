package restudio.resync.flow.graph;

import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;

import java.util.Map;
import java.util.Objects;

public final class RepeatableElement {
    private final RepeatableElementId elementId;
    private final Map<PinId, PinValue> values;
    private final OpaqueData unknown;

    public RepeatableElement(RepeatableElementId elementId, Map<PinId, PinValue> values, OpaqueData unknown) {
        this.elementId = Objects.requireNonNull(elementId, "elementId");
        this.values = GraphCollections.pinValues(values);
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        this.unknown.rejectKnownFields("elementId", "values");
    }

    public RepeatableElement(RepeatableElementId elementId, Map<PinId, PinValue> values) {
        this(elementId, values, OpaqueData.empty());
    }

    public RepeatableElementId elementId() {
        return elementId;
    }

    public Map<PinId, PinValue> values() {
        return values;
    }

    public OpaqueData unknown() {
        return unknown;
    }

    Map<String, Object> canonicalValue() {
        return OpaqueData.mergeKnownFields(unknown, Map.of("elementId", elementId.canonicalText(), "values", PinValue.canonicalValues(values)));
    }
}
