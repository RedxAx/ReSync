package restudio.resync.modules.flow;

import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CoreResourceMutationCheckpoint(ServerResourceLocator locator, long revision, UUID mutationId,
                                             boolean deleted, ResourceActivationState activationState,
                                             String author, ContentHash canonicalEnvelopeHash) {
    private static final OwnerId CORE_OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> CORE_TYPES = Set.of("flow", "function", "command");

    public CoreResourceMutationCheckpoint {
        locator = Objects.requireNonNull(locator, "Core resource locator is required");
        if (!CORE_OWNER.equals(locator.owner()) || !CORE_TYPES.contains(locator.resourceType().value())) {
            throw new IllegalArgumentException("Core resource locator is not authoritative");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("Core resource revision must be positive");
        }
        mutationId = Objects.requireNonNull(mutationId, "Core resource mutation ID is required");
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("Core resource mutation author is required");
        }
        if (deleted && activationState != null || !deleted && activationState == null) {
            throw new IllegalArgumentException("Core resource activation state does not match its deletion state");
        }
        canonicalEnvelopeHash = Objects.requireNonNull(canonicalEnvelopeHash,
            "Canonical Core resource envelope hash is required");
    }
}
