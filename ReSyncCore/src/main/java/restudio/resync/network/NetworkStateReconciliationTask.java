package restudio.resync.network;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record NetworkStateReconciliationTask(String transitionId, Set<UUID> playerIds, Set<String> families) {
    public NetworkStateReconciliationTask {
        transitionId = NetworkValues.required(transitionId, "Transition ID");
        playerIds = playerIds == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(playerIds));
        families = families == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(families));
        if (families.isEmpty()) {
            throw new IllegalArgumentException("Reconciliation Requires At Least One Family");
        }
    }
}
