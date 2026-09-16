package restudio.resync.migration;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record PersistenceRebindStatus(State state, Path requestedRoot, Optional<Path> activeRoot, String failedOwner,
                                      List<String> reboundOwners, List<String> rolledBackOwners,
                                      Map<String, String> rollbackFailures, String reason) {
    public enum State {
        NOT_ATTEMPTED,
        PROVISIONAL,
        COMMITTED,
        ROLLED_BACK,
        INCONSISTENT
    }

    public PersistenceRebindStatus {
        state = state == null ? State.NOT_ATTEMPTED : state;
        requestedRoot = requestedRoot == null ? null : requestedRoot.toAbsolutePath().normalize();
        activeRoot = activeRoot == null ? Optional.empty() : activeRoot.map(value -> value.toAbsolutePath().normalize());
        failedOwner = failedOwner == null ? "" : failedOwner.trim();
        reboundOwners = reboundOwners == null ? List.of() : List.copyOf(reboundOwners);
        rolledBackOwners = rolledBackOwners == null ? List.of() : List.copyOf(rolledBackOwners);
        rollbackFailures = rollbackFailures == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(rollbackFailures));
        reason = reason == null ? "" : reason.trim();
        if (state == State.INCONSISTENT && rollbackFailures.isEmpty()) {
            throw new IllegalArgumentException("Inconsistent Persistence Rebind Must Report Rollback Failures");
        }
    }

    public static PersistenceRebindStatus notAttempted() {
        return new PersistenceRebindStatus(State.NOT_ATTEMPTED, null, Optional.empty(), "", List.of(), List.of(), Map.of(), "");
    }

    public static PersistenceRebindStatus committed(Path root, List<String> owners) {
        return new PersistenceRebindStatus(State.COMMITTED, root, Optional.of(root), "", owners, List.of(), Map.of(), "");
    }

    public static PersistenceRebindStatus provisional(Path root, List<String> owners) {
        return new PersistenceRebindStatus(State.PROVISIONAL, root, Optional.empty(), "", owners, List.of(), Map.of(),
            "Awaiting Final Ownership Validation");
    }

    public boolean stable() {
        return state != State.PROVISIONAL && state != State.INCONSISTENT;
    }

    public Map<String, Object> payload() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("state", state.name());
        if (requestedRoot != null) {
            value.put("requestedRoot", requestedRoot.toString());
        }
        activeRoot.ifPresent(root -> value.put("activeRoot", root.toString()));
        if (!failedOwner.isBlank()) {
            value.put("failedOwner", failedOwner);
        }
        value.put("reboundOwners", reboundOwners);
        value.put("rolledBackOwners", rolledBackOwners);
        value.put("rollbackFailures", rollbackFailures);
        if (!reason.isBlank()) {
            value.put("reason", reason);
        }
        return Collections.unmodifiableMap(value);
    }
}
