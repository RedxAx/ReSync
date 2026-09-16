package restudio.resync.modules.flow;

import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CoreResourceMutationTransition(ServerResourceLocator locator, long revision, UUID mutationId,
                                             boolean deleted, ResourceActivationState activationState,
                                             String author, String canonicalEnvelope) {
    private static final String HASH_DOMAIN = "core-resource-mutation-transition";
    private static final OwnerId CORE_OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> CORE_TYPES = Set.of("flow", "function", "command");
    private static final CoreGraphStorageBoundary BOUNDARY = new CoreGraphStorageBoundary();

    public CoreResourceMutationTransition {
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
        if (canonicalEnvelope == null || canonicalEnvelope.isBlank()) {
            throw new IllegalArgumentException("Canonical Core resource envelope is required");
        }
        byte[] encoded = canonicalEnvelope.getBytes(StandardCharsets.UTF_8);
        if (deleted) {
            CoreGraphStorageBoundary.CoreGraphTombstone tombstone = BOUNDARY.decodeTombstone(encoded, locator);
            if (tombstone.revision() != revision || !tombstone.mutationId().equals(mutationId)) {
                throw new IllegalArgumentException("Canonical Core resource tombstone identity does not match the transition");
            }
        } else {
            CoreGraphStorageBoundary.Decoded decoded = BOUNDARY.decode(encoded, locator);
            if (decoded.envelope().assetRevision() != revision
                || !decoded.envelope().assetMutationId().equals(mutationId.toString())
                || decoded.envelope().assetActivationState() != activationState) {
                throw new IllegalArgumentException("Canonical Core resource envelope identity does not match the transition");
            }
        }
    }

    public CoreResourceMutationCheckpoint checkpoint() {
        return new CoreResourceMutationCheckpoint(locator, revision, mutationId, deleted, activationState, author,
            new ContentHash(CanonicalJson.sha256Canonical(HASH_DOMAIN,
                canonicalEnvelope.getBytes(StandardCharsets.UTF_8))));
    }
}
