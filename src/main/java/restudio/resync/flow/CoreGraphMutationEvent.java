package restudio.resync.flow;

import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.util.Objects;
import java.util.UUID;

public record CoreGraphMutationEvent(
    ServerResourceLocator resource,
    long revision,
    UUID mutationId,
    ContentHash payloadChecksum,
    ResourceActivationState activationState,
    boolean deleted
) {
    public CoreGraphMutationEvent {
        resource = Objects.requireNonNull(resource, "Core Graph Mutation Resource Is Required");
        if (revision <= 0) {
            throw new IllegalArgumentException("Core Graph Mutation Revision Must Be Positive");
        }
        mutationId = Objects.requireNonNull(mutationId, "Core Graph Mutation ID Is Required");
        payloadChecksum = Objects.requireNonNull(payloadChecksum, "Core Graph Mutation Payload Checksum Is Required");
        if (deleted == (activationState != null)) {
            throw new IllegalArgumentException("Core Graph Mutation Must Be Either Live Or Deleted");
        }
    }

    public static CoreGraphMutationEvent live(ServerResourceLocator resource, long revision, UUID mutationId,
                                              ContentHash payloadChecksum, ResourceActivationState activationState) {
        return new CoreGraphMutationEvent(resource, revision, mutationId, payloadChecksum,
            Objects.requireNonNull(activationState, "Core Graph Activation State Is Required"), false);
    }

    public static CoreGraphMutationEvent deleted(ServerResourceLocator resource, long revision, UUID mutationId,
                                                 ContentHash payloadChecksum) {
        return new CoreGraphMutationEvent(resource, revision, mutationId, payloadChecksum, null, true);
    }
}
