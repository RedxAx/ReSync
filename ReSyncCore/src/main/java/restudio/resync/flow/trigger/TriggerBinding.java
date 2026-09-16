package restudio.resync.flow.trigger;

import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.TriggerBindingId;

import java.util.Objects;
import java.util.Set;

public record TriggerBinding(TriggerBindingId id, TriggerRoute route, ExecutionTarget target, OpaqueData unknown) {
    private static final Set<String> KNOWN_FIELDS = Set.of("id", "route", "target");

    public TriggerBinding {
        id = Objects.requireNonNull(id, "Trigger Binding ID Is Required");
        route = Objects.requireNonNull(route, "Trigger Binding Route Is Required");
        target = Objects.requireNonNull(target, "Trigger Binding Target Is Required");
        unknown = unknown != null ? unknown : OpaqueData.empty();
        for (String field : KNOWN_FIELDS) {
            if (unknown.contains(field)) {
                throw new IllegalArgumentException("Trigger Binding Unknown Data Collides With Known Field: " + field);
            }
        }
    }

    public TriggerBinding(TriggerBindingId id, TriggerRoute route, ExecutionTarget target) {
        this(id, route, target, OpaqueData.empty());
    }
}
