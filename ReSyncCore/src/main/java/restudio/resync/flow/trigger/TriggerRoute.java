package restudio.resync.flow.trigger;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;

import java.util.Objects;

public record TriggerRoute(TriggerKind kind, ContractRef<NodeId> source) {
    public TriggerRoute {
        kind = Objects.requireNonNull(kind, "Trigger Kind Is Required");
        source = Objects.requireNonNull(source, "Trigger Source Is Required");
    }
}
