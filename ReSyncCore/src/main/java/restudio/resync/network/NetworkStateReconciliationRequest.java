package restudio.resync.network;

import java.util.LinkedHashSet;
import java.util.Set;

public record NetworkStateReconciliationRequest(String transitionId, Set<String> nodeIds, Set<String> families) {
    public NetworkStateReconciliationRequest {
        transitionId = NetworkValues.required(transitionId, "Transition ID");
        nodeIds = normalized(nodeIds, "Reconciliation Node");
        families = normalized(families, "Reconciliation Family");
        if (nodeIds.isEmpty()) {
            throw new IllegalArgumentException("Reconciliation Requires At Least One Node");
        }
        if (families.isEmpty()) {
            throw new IllegalArgumentException("Reconciliation Requires At Least One Family");
        }
    }

    private static Set<String> normalized(Set<String> values, String label) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                normalized.add(NetworkValues.required(value, label));
            }
        }
        return Set.copyOf(normalized);
    }
}
