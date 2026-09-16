package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.LocalId;

public record InspectorId(String value) implements LocalId {
    public InspectorId {
        value = CapabilityId.of(value).value();
    }

    public static InspectorId of(String value) {
        return new InspectorId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
