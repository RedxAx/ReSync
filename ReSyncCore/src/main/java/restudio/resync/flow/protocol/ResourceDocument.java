package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.UUID;

public record ResourceDocument<P>(ServerResourceLocator resource, long revision, UUID mutationId, ContentHash payloadHash, boolean deleted,
                                  CanonicalPayload<P> canonicalPayload, ResourceActivationState activationState, String author) {
    public ResourceDocument(ServerResourceLocator resource, long revision, UUID mutationId, ContentHash payloadHash, boolean deleted,
                            CanonicalPayload<P> canonicalPayload, String author) {
        this(resource, revision, mutationId, payloadHash, deleted, canonicalPayload,
            deleted ? null : ResourceActivationState.ACTIVE, author);
    }

    public ResourceDocument {
        resource = Objects.requireNonNull(resource, "resource");
        revision = ProtocolValues.revision(revision, "revision");
        mutationId = Objects.requireNonNull(mutationId, "mutationId");
        payloadHash = Objects.requireNonNull(payloadHash, "payloadHash");
        if (deleted && canonicalPayload != null) {
            throw new IllegalArgumentException("Tombstones cannot carry payload");
        }
        if (!deleted && canonicalPayload == null) {
            throw new IllegalArgumentException("Live resources require payload");
        }
        if (deleted && activationState != null) {
            throw new IllegalArgumentException("Tombstones cannot carry activation state");
        }
        if (!deleted && activationState == null) {
            throw new IllegalArgumentException("Live resources require activation state");
        }
        if (canonicalPayload != null && !payloadHash.equals(canonicalPayload.checksum())) {
            throw new IllegalArgumentException("Resource checksum does not match canonical payload");
        }
        author = ProtocolValues.optionalText(author, "author", 256);
    }

    public static <P> ResourceDocument<P> live(ServerResourceLocator resource, long revision, UUID mutationId,
                                               CanonicalPayload<P> canonicalPayload, String author) {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        return live(resource, revision, mutationId, canonicalPayload, ResourceActivationState.ACTIVE, author);
    }

    public static <P> ResourceDocument<P> live(ServerResourceLocator resource, long revision, UUID mutationId,
                                               CanonicalPayload<P> canonicalPayload, ResourceActivationState activationState,
                                               String author) {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        return new ResourceDocument<>(resource, revision, mutationId, canonicalPayload.checksum(), false, canonicalPayload,
            activationState, author);
    }

    public static <P> ResourceDocument<P> tombstone(ServerResourceLocator resource, long revision, UUID mutationId, ContentHash payloadHash,
                                                    String author) {
        return new ResourceDocument<>(resource, revision, mutationId, payloadHash, true, null, null, author);
    }

    public P payload() {
        return canonicalPayload == null ? null : canonicalPayload.value();
    }
}
