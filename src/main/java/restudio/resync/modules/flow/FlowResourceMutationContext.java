package restudio.resync.modules.flow;

import java.util.UUID;
import java.util.regex.Pattern;

public record FlowResourceMutationContext(String source, String flowId, String nodeId, String actor,
                                           FlowResourceMutationLease lease, String mutationId,
                                           UUID exactMutationId, long expectedRevision,
                                           String expectedPayloadHash) {
    private static final Pattern PAYLOAD_HASH = Pattern.compile("[0-9a-f]{64}");

    public FlowResourceMutationContext {
        source = source != null && !source.isBlank() ? source : "system";
        flowId = flowId != null ? flowId : "";
        nodeId = nodeId != null ? nodeId : "";
        actor = actor != null && !actor.isBlank() ? actor : "server";
        mutationId = mutationId != null ? mutationId.strip() : "";
        expectedPayloadHash = expectedPayloadHash != null ? expectedPayloadHash.strip() : "";
        if (expectedRevision < -1L) {
            throw new IllegalArgumentException("Expected resource revision cannot be less than -1");
        }
        if (!expectedPayloadHash.isBlank() && !PAYLOAD_HASH.matcher(expectedPayloadHash).matches()) {
            throw new IllegalArgumentException("Expected resource payload hash must be 64 lowercase hexadecimal characters");
        }
        if (lease != null && (mutationId.isBlank() || !mutationId.equals(lease.mutationId()))) {
            throw new IllegalArgumentException("Mutation lease and mutation ID must match");
        }
        if (exactMutationId != null) {
            if (lease == null || expectedRevision < 0L || expectedPayloadHash.isBlank()) {
                throw new IllegalArgumentException("Exact resource mutation requires a retained lease, nonnegative expected revision, and payload hash");
            }
            String exactToken = exactMutationId.toString();
            if (!exactToken.equals(mutationId) || !exactToken.equals(lease.mutationId())) {
                throw new IllegalArgumentException("Exact mutation ID must match the retained lease");
            }
        } else if (expectedRevision >= 0L || !expectedPayloadHash.isBlank()) {
            throw new IllegalArgumentException("Expected revision and payload hash require an exact UUID mutation ID");
        }
    }

    public FlowResourceMutationContext(String source, String flowId, String nodeId, String actor) {
        this(source, flowId, nodeId, actor, null, "", null, -1L, "");
    }

    public FlowResourceMutationContext(String source, String flowId, String nodeId, String actor, Object authority) {
        this(source, flowId, nodeId, actor, authorityLease(authority), authorityMutationId(authority), null, -1L, "");
    }

    public FlowResourceMutationContext(String source, String flowId, String nodeId, String actor,
                                       FlowResourceMutationLease lease) {
        this(source, flowId, nodeId, actor, lease, lease != null ? lease.mutationId() : "", null, -1L, "");
    }

    public FlowResourceMutationContext(String source, String flowId, String nodeId, String actor,
                                       FlowResourceMutationLease lease, String mutationId) {
        this(source, flowId, nodeId, actor, lease, mutationId, null, -1L, "");
    }

    public FlowResourceMutationContext(String source, String flowId, String nodeId, String actor, String mutationId) {
        this(source, flowId, nodeId, actor, null, mutationId, null, -1L, "");
    }

    public FlowResourceMutationContext(String source, String flowId, String nodeId, String actor,
                                       FlowResourceMutationLease lease, UUID mutationId, long expectedRevision) {
        this(source, flowId, nodeId, actor, lease, mutationId, expectedRevision, "");
    }

    public FlowResourceMutationContext(String source, String flowId, String nodeId, String actor,
                                       FlowResourceMutationLease lease, UUID mutationId, long expectedRevision,
                                       String expectedPayloadHash) {
        this(source, flowId, nodeId, actor, lease, exactMutationToken(mutationId), mutationId, expectedRevision,
            expectedPayloadHash);
    }

    public static FlowResourceMutationContext withLease(String source, String flowId, String nodeId, String actor,
                                                        FlowResourceMutationLease lease) {
        return new FlowResourceMutationContext(source, flowId, nodeId, actor, lease);
    }

    public static FlowResourceMutationContext withMutationId(String source, String flowId, String nodeId, String actor,
                                                             String mutationId) {
        return new FlowResourceMutationContext(source, flowId, nodeId, actor, mutationId);
    }

    public static FlowResourceMutationContext withExactMutation(String source, String flowId, String nodeId, String actor,
                                                                 FlowResourceMutationLease lease, UUID mutationId,
                                                                 long expectedRevision) {
        return new FlowResourceMutationContext(source, flowId, nodeId, actor, lease, mutationId, expectedRevision);
    }

    public static FlowResourceMutationContext withExactMutation(String source, String flowId, String nodeId, String actor,
                                                                 FlowResourceMutationLease lease, UUID mutationId,
                                                                 long expectedRevision, String expectedPayloadHash) {
        return new FlowResourceMutationContext(source, flowId, nodeId, actor, lease, mutationId, expectedRevision,
            expectedPayloadHash);
    }

    public boolean hasLease() {
        return lease != null;
    }

    public boolean hasMutationId() {
        return !mutationId.isBlank();
    }

    public boolean isContinuation() {
        return lease != null || hasMutationId();
    }

    public boolean isNew() {
        return !isContinuation();
    }

    public boolean isExactMutation() {
        return exactMutationId != null;
    }

    public boolean hasExactMutation() {
        return isExactMutation();
    }

    public FlowResourceMutationLease mutationLease() {
        return lease;
    }

    public FlowResourceMutationLease ownedLease() {
        return lease;
    }

    public String mutationToken() {
        return mutationId;
    }

    public String ownedMutationId() {
        return mutationId;
    }

    public UUID mutationUuid() {
        return exactMutationId;
    }

    public static FlowResourceMutationContext system() {
        return new FlowResourceMutationContext("system", "", "", "server", null, "", null, -1L, "");
    }

    private static String exactMutationToken(UUID mutationId) {
        return mutationId != null ? mutationId.toString() : "";
    }

    private static FlowResourceMutationLease authorityLease(Object authority) {
        if (authority == null || authority instanceof FlowResourceMutationLease) {
            return (FlowResourceMutationLease) authority;
        }
        if (authority instanceof String) {
            return null;
        }
        throw new IllegalArgumentException("Mutation authority must be a lease or mutation ID");
    }

    private static String authorityMutationId(Object authority) {
        if (authority == null) {
            return "";
        }
        if (authority instanceof FlowResourceMutationLease lease) {
            return lease.mutationId();
        }
        if (authority instanceof String mutationId) {
            return mutationId;
        }
        throw new IllegalArgumentException("Mutation authority must be a lease or mutation ID");
    }
}
