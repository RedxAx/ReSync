package restudio.resync.migration;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record PersistenceReadinessCertificate(
    long generation,
    long topologyEpoch,
    long authorityEpoch,
    Path activeRoot,
    PersistenceRootReadiness readiness,
    PersistenceRebindStatus rebind,
    long derivationGeneration,
    List<Ephemeral> ephemeralLifecycles
) {
    public PersistenceReadinessCertificate(long generation, long authorityEpoch, Path activeRoot,
                                           PersistenceRootReadiness readiness, PersistenceRebindStatus rebind) {
        this(generation, 1L, authorityEpoch, activeRoot, readiness, rebind, 0L, List.of());
    }

    public PersistenceReadinessCertificate(long generation, long authorityEpoch, Path activeRoot,
                                           PersistenceRootReadiness readiness, PersistenceRebindStatus rebind,
                                           List<Ephemeral> ephemeralLifecycles) {
        this(generation, 1L, authorityEpoch, activeRoot, readiness, rebind, 0L, ephemeralLifecycles);
    }

    public PersistenceReadinessCertificate {
        if (generation < 1L) {
            throw new IllegalArgumentException("Persistence Readiness Certificate Generation Must Be Positive");
        }
        if (topologyEpoch < 1L) {
            throw new IllegalArgumentException("Persistence Readiness Certificate Topology Epoch Must Be Positive");
        }
        if (authorityEpoch < 1L) {
            throw new IllegalArgumentException("Persistence Readiness Certificate Authority Epoch Must Be Positive");
        }
        if (derivationGeneration < 0L) {
            throw new IllegalArgumentException("Persistence Readiness Certificate Derivation Generation Must Not Be Negative");
        }
        activeRoot = MigrationPaths.requirePath(activeRoot, "activeRoot");
        readiness = Objects.requireNonNull(readiness, "readiness");
        rebind = Objects.requireNonNull(rebind, "rebind");
        ephemeralLifecycles = ephemeralLifecycles == null ? List.of() : List.copyOf(ephemeralLifecycles);
        if (!readiness.complete()) {
            throw new IllegalArgumentException("Persistence Readiness Certificate Requires Complete Readiness");
        }
        if (rebind.state() != PersistenceRebindStatus.State.COMMITTED
            || rebind.activeRoot().isEmpty() || !activeRoot.equals(rebind.activeRoot().orElseThrow())) {
            throw new IllegalArgumentException("Persistence Readiness Certificate Requires A Committed Active Root");
        }
    }

    public boolean accepts(long currentAuthorityEpoch, Path currentActiveRoot) {
        return authorityEpoch == currentAuthorityEpoch
            && activeRoot.equals(currentActiveRoot == null ? null : currentActiveRoot.toAbsolutePath().normalize());
    }

    public record Ephemeral(String owner, boolean available, String state, int physicalTasks, String reason) {
        public Ephemeral {
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("Ephemeral Lifecycle Owner Must Not Be Blank");
            }
            owner = owner.trim();
            state = state == null || state.isBlank() ? "UNKNOWN" : state.trim();
            if (physicalTasks < 0) {
                throw new IllegalArgumentException("Ephemeral Lifecycle Physical Tasks Must Not Be Negative");
            }
            reason = reason == null ? "" : reason.trim();
        }
    }
}
