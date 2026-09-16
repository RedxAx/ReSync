package restudio.resync.modules.flow;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class FlowResourceMutationAdmission {
    public enum AdmissionKind {
        NEW,
        CONTINUATION
    }

    private final Object monitor = new Object();
    private final Map<FlowResourceKey, Claim> claims = new HashMap<>();
    private final Map<String, Claim> claimsByMutationId = new HashMap<>();

    public Optional<FlowResourceMutationLease> tryAcquire(FlowResourceKey key) {
        return tryAcquire(List.of(key));
    }

    public Optional<FlowResourceMutationLease> tryAcquire(String typeId, String resourceId) {
        return tryAcquire(new FlowResourceKey(typeId, resourceId));
    }

    public Optional<FlowResourceMutationLease> tryAdmit(Collection<FlowResourceKey> keys) {
        return tryAcquire(keys);
    }

    public Optional<FlowResourceMutationLease> tryAdmit(FlowResourceKey... keys) {
        return tryAcquire(keys);
    }

    public Optional<FlowResourceMutationLease> tryAcquire(Collection<FlowResourceKey> keys) {
        return tryAcquireNew(keys, UUID.randomUUID().toString());
    }

    public Optional<FlowResourceMutationLease> tryAcquire(FlowResourceKey... keys) {
        return tryAcquire(keys != null ? Arrays.asList(keys) : null);
    }

    public Optional<FlowResourceMutationLease> tryAcquire(Collection<FlowResourceKey> keys, String mutationId) {
        return tryAcquireNew(keys, mutationId);
    }

    public Optional<FlowResourceMutationLease> tryAcquire(Collection<FlowResourceKey> keys, String mutationId,
                                                           AdmissionKind kind) {
        Objects.requireNonNull(kind, "kind");
        return kind == AdmissionKind.CONTINUATION
            ? tryContinue(keys, mutationId)
            : tryAcquireNew(keys, mutationId);
    }

    public Optional<FlowResourceMutationLease> tryAcquire(Collection<FlowResourceKey> keys, AdmissionKind kind,
                                                           String mutationId) {
        return tryAcquire(keys, mutationId, kind);
    }

    public Optional<FlowResourceMutationLease> tryAdmitNew(Collection<FlowResourceKey> keys) {
        return tryAcquire(keys);
    }

    public Optional<FlowResourceMutationLease> tryContinue(Collection<FlowResourceKey> keys, String mutationId) {
        List<FlowResourceKey> ordered = normalizeKeys(keys);
        String token = mutationId != null ? mutationId.strip() : "";
        if (ordered.isEmpty() || token.isBlank()) {
            return Optional.empty();
        }
        synchronized (monitor) {
            Claim claim = claimsByMutationId.get(token);
            if (claim == null || claim.lease == null || !ownsClaim(claim, ordered)) {
                return Optional.empty();
            }
            if (!claim.lease.retain()) {
                return Optional.empty();
            }
            return Optional.of(claim.lease);
        }
    }

    public Optional<FlowResourceMutationLease> continueAdmission(Collection<FlowResourceKey> keys, String mutationId) {
        return tryContinue(keys, mutationId);
    }

    public Optional<FlowResourceMutationLease> tryContinue(String mutationId, Collection<FlowResourceKey> keys) {
        return tryContinue(keys, mutationId);
    }

    private Optional<FlowResourceMutationLease> tryAcquireNew(Collection<FlowResourceKey> keys, String mutationId) {
        List<FlowResourceKey> ordered = normalizeKeys(keys);
        String token = requireMutationId(mutationId);
        if (ordered.isEmpty()) {
            return Optional.empty();
        }
        synchronized (monitor) {
            if (claimsByMutationId.containsKey(token)) {
                return Optional.empty();
            }
            for (FlowResourceKey key : ordered) {
                if (claims.containsKey(key)) {
                    return Optional.empty();
                }
            }
            Claim claim = new Claim(token, ordered);
            FlowResourceMutationLease lease = new FlowResourceMutationLease(this, claim);
            claim.lease = lease;
            claimsByMutationId.put(token, claim);
            for (FlowResourceKey key : ordered) {
                claims.put(key, claim);
            }
            return Optional.of(lease);
        }
    }

    public FlowResourceMutationLease acquire(FlowResourceKey key) {
        return acquire(List.of(key));
    }

    public FlowResourceMutationLease acquire(String typeId, String resourceId) {
        return acquire(new FlowResourceKey(typeId, resourceId));
    }

    public FlowResourceMutationLease acquire(Collection<FlowResourceKey> keys) {
        return tryAcquire(keys).orElseThrow(() -> new IllegalStateException("Resource mutation is already admitted"));
    }

    public FlowResourceMutationLease admit(Collection<FlowResourceKey> keys) {
        return acquire(keys);
    }

    public FlowResourceMutationLease acquire(FlowResourceKey... keys) {
        return tryAcquire(keys).orElseThrow(() -> new IllegalStateException("Resource mutation is already admitted"));
    }

    public FlowResourceMutationLease acquire(Collection<FlowResourceKey> keys, String mutationId) {
        return tryAcquire(keys, mutationId).orElseThrow(() -> new IllegalStateException("Resource mutation is already admitted"));
    }

    public boolean owns(String mutationId, Collection<FlowResourceKey> keys) {
        String token = mutationId != null ? mutationId.strip() : "";
        List<FlowResourceKey> normalized = normalizeKeys(keys);
        if (token.isBlank() || normalized.isEmpty()) {
            return false;
        }
        synchronized (monitor) {
            Claim claim = claimsByMutationId.get(token);
            return claim != null && ownsClaim(claim, normalized);
        }
    }

    public boolean owns(String mutationId, FlowResourceKey key) {
        return key != null && owns(mutationId, List.of(key));
    }

    public Optional<FlowResourceMutationLease> leaseFor(String mutationId, Collection<FlowResourceKey> keys) {
        return tryContinue(keys, mutationId);
    }

    public Optional<FlowResourceMutationLease> leaseForMutation(String mutationId, Collection<FlowResourceKey> keys) {
        return leaseFor(mutationId, keys);
    }

    public boolean isAdmitted(FlowResourceKey key) {
        if (key == null) {
            return false;
        }
        synchronized (monitor) {
            return claims.containsKey(key);
        }
    }

    public boolean contains(FlowResourceKey key) {
        return isAdmitted(key);
    }

    public boolean isHeld(FlowResourceKey key) {
        return isAdmitted(key);
    }

    public List<FlowResourceKey> activeKeys() {
        synchronized (monitor) {
            return claims.keySet().stream().sorted().toList();
        }
    }

    boolean owns(FlowResourceMutationLease lease, FlowResourceKey key) {
        if (lease == null || key == null || lease.admission() != this) {
            return false;
        }
        synchronized (monitor) {
            return claims.get(key) == lease.claim();
        }
    }

    void release(FlowResourceMutationAdmission.Claim claim) {
        if (claim == null) {
            return;
        }
        synchronized (monitor) {
            for (FlowResourceKey key : claim.keys()) {
                if (claims.get(key) == claim) {
                    claims.remove(key);
                }
            }
            if (claimsByMutationId.get(claim.mutationId()) == claim) {
                claimsByMutationId.remove(claim.mutationId());
            }
        }
    }

    private boolean ownsClaim(Claim claim, Collection<FlowResourceKey> keys) {
        for (FlowResourceKey key : keys) {
            if (claims.get(key) != claim) {
                return false;
            }
        }
        return true;
    }

    private List<FlowResourceKey> normalizeKeys(Collection<FlowResourceKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<FlowResourceKey> unique = new LinkedHashSet<>();
        for (FlowResourceKey key : keys) {
            if (key == null || !unique.add(key)) {
                return List.of();
            }
        }
        return unique.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private String requireMutationId(String mutationId) {
        String token = mutationId != null ? mutationId.strip() : "";
        if (token.isBlank()) {
            throw new IllegalArgumentException("mutationId is required");
        }
        return token;
    }

    static final class Claim {
        private final String mutationId;
        private final List<FlowResourceKey> keys;
        private volatile FlowResourceMutationLease lease;

        private Claim(String mutationId, List<FlowResourceKey> keys) {
            this.mutationId = mutationId;
            this.keys = List.copyOf(keys);
        }

        String mutationId() {
            return mutationId;
        }

        List<FlowResourceKey> keys() {
            return keys;
        }
    }
}
