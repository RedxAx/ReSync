package restudio.resync.flow.trigger;

import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;

import java.util.Objects;
import java.util.Optional;

public interface TriggerSourceCatalog {
    CatalogBinding binding();

    Optional<TriggerSourceDescriptor> resolve(ContractRef<NodeId> source);

    default TriggerSourceDescriptor require(ContractRef<NodeId> source) {
        Objects.requireNonNull(source, "Trigger Source Is Required");
        return Objects.requireNonNull(resolve(source), "Trigger Source Resolution Is Required")
            .orElseThrow(() -> new IllegalArgumentException("Unknown Trigger Source: " + source));
    }
}
