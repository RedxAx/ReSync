package restudio.resync.server;

import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourcePresentationIntent;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface CoreGraphResourceAuthority {
    String UNAVAILABLE_MESSAGE = "Core graph resource authority is unavailable";

    boolean available();

    default List<String> types() {
        return List.of("flow", "function", "command");
    }

    Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource);

    default Optional<CatalogBinding> activeCatalogBinding() {
        return Optional.empty();
    }

    default Optional<CoreCatalogEvolution.Proof> activeCatalogEvolution() {
        return Optional.empty();
    }

    List<CoreGraphResourceState> list(String type);

    Optional<CoreGraphResourceState> state(ServerResourceLocator resource);

    default Optional<LegacyCoreRecoverySource> legacyRecoverySource(ServerResourceLocator resource,
                                                                     UUID sourceMutationId, long sourceRevision,
                                                                     ContentHash sourceAssetHash) {
        return Optional.empty();
    }

    default LegacyCoreRecoveryResult recoverLegacyDelete(ServerResourceLocator resource, UUID sourceMutationId,
                                                          long sourceRevision, ContentHash sourceAssetHash,
                                                          UUID recoveryMutationId) {
        throw new UnsupportedOperationException("Legacy Core recovery is unavailable");
    }

    default Optional<CoreRevisionRepairResult> repairRevisionSkew(ServerResourceLocator resource,
                                                                  CoreRevisionRepairSource source,
                                                                  UUID repairMutationId) {
        return Optional.empty();
    }

    default Optional<CoreCatalogRebindResult> rebindCatalog(ServerResourceLocator resource,
                                                            CoreCatalogRebindSource source,
                                                            UUID mutationId,
                                                            CoreCatalogBindingMigration migration) {
        return Optional.empty();
    }

    default boolean acceptsCatalogRebind(CoreCatalogBindingMigration migration) {
        return false;
    }

    default void validateSave(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded canonicalEnvelope) {
    }

    CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                          UUID mutationId, long expectedRevision, ContentHash payloadChecksum);

    default CoreGraphStorageBoundary.Decoded saveCatalogProjection(ServerResourceLocator resource,
            CoreGraphStorageBoundary.Decoded candidate, UUID mutationId, long expectedRevision,
            ContentHash payloadChecksum, CatalogProjection projection) {
        Objects.requireNonNull(projection, "Catalog Projection Admission Is Required")
            .requireCurrent(this, resource, candidate, mutationId, expectedRevision, payloadChecksum);
        return save(resource, candidate, mutationId, expectedRevision, payloadChecksum);
    }

    sealed interface CatalogProjection permits SqliteProtocolResourceMutationAuthority.CatalogProjection {
        void requireCurrent(CoreGraphResourceAuthority authority, ServerResourceLocator resource,
            CoreGraphStorageBoundary.Decoded candidate, UUID mutationId, long expectedRevision, ContentHash payloadChecksum);
    }

    default boolean supportsAggregateCreate() {
        return false;
    }

    default CoreGraphCreateResult create(ServerResourceLocator resource,
                                         CoreGraphStorageBoundary.Decoded canonicalEnvelope,
                                         UUID mutationId, long expectedRevision,
                                         ResourcePresentationIntent presentation) {
        throw new UnsupportedOperationException("Aggregate Core graph create is unavailable");
    }

    default void publishCommitted(ServerResourceLocator resource, UUID mutationId) {
    }

    default CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource,
                                                  CoreGraphStorageBoundary.Decoded canonicalEnvelope,
                                                  UUID mutationId, long expectedRevision,
                                                  ContentHash payloadChecksum) {
        Objects.requireNonNull(canonicalEnvelope, "Core graph envelope is required");
        return save(resource, new CoreGraphStorageBoundary().encode(canonicalEnvelope), mutationId,
            expectedRevision, payloadChecksum);
    }

    default CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource,
                                                  CoreGraphStorageBoundary.Decoded canonicalEnvelope,
                                                  long expectedRevision, UUID mutationId,
                                                  ContentHash payloadChecksum) {
        return save(resource, canonicalEnvelope, mutationId, expectedRevision, payloadChecksum);
    }

    default CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                  long expectedRevision, UUID mutationId,
                                                  ContentHash payloadChecksum) {
        return save(resource, canonicalEnvelope, mutationId, expectedRevision, payloadChecksum);
    }

    CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                        long expectedRevision, ContentHash payloadChecksum);

    default CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, long expectedRevision,
                                                                UUID mutationId, ContentHash payloadChecksum) {
        return delete(resource, mutationId, expectedRevision, payloadChecksum);
    }

    default CoreGraphResourceState deleteState(ServerResourceLocator resource, UUID mutationId,
                                               long expectedRevision, ContentHash payloadChecksum) {
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = delete(resource, mutationId, expectedRevision,
            payloadChecksum);
        return CoreGraphResourceState.tombstoned(tombstone, new CoreGraphStorageBoundary());
    }

    default CoreGraphResourceState deleteState(ServerResourceLocator resource, long expectedRevision,
                                               UUID mutationId, ContentHash payloadChecksum) {
        return deleteState(resource, mutationId, expectedRevision, payloadChecksum);
    }

    CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource, ResourceActivationState activationState,
                                               UUID mutationId, long expectedRevision, ContentHash payloadChecksum);

    default CoreGraphStorageBoundary.Decoded activation(ServerResourceLocator resource,
                                                         ResourceActivationState activationState, UUID mutationId,
                                                         long expectedRevision, ContentHash payloadChecksum) {
        return activate(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    default CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                       ResourceActivationState activationState, long expectedRevision,
                                                       UUID mutationId, ContentHash payloadChecksum) {
        return activate(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    default CoreGraphStorageBoundary.Decoded activation(ServerResourceLocator resource,
                                                         ResourceActivationState activationState, long expectedRevision,
                                                         UUID mutationId, ContentHash payloadChecksum) {
        return activate(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    static CoreGraphResourceAuthority unavailable() {
        return new CoreGraphResourceAuthority() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
                throw unavailableFailure();
            }

            @Override
            public List<CoreGraphResourceState> list(String type) {
                throw unavailableFailure();
            }

            @Override
            public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
                throw unavailableFailure();
            }

            @Override
            public Optional<LegacyCoreRecoverySource> legacyRecoverySource(ServerResourceLocator resource,
                                                                            UUID sourceMutationId, long sourceRevision,
                                                                            ContentHash sourceAssetHash) {
                throw unavailableFailure();
            }

            @Override
            public LegacyCoreRecoveryResult recoverLegacyDelete(ServerResourceLocator resource, UUID sourceMutationId,
                                                                 long sourceRevision, ContentHash sourceAssetHash,
                                                                 UUID recoveryMutationId) {
                throw unavailableFailure();
            }

            @Override
            public Optional<CoreRevisionRepairResult> repairRevisionSkew(ServerResourceLocator resource,
                                                                         CoreRevisionRepairSource source,
                                                                         UUID repairMutationId) {
                throw unavailableFailure();
            }

            @Override
            public Optional<CoreCatalogRebindResult> rebindCatalog(ServerResourceLocator resource,
                                                                   CoreCatalogRebindSource source,
                                                                   UUID mutationId,
                                                                   CoreCatalogBindingMigration migration) {
                throw unavailableFailure();
            }

            @Override
            public boolean acceptsCatalogRebind(CoreCatalogBindingMigration migration) {
                return false;
            }

            @Override
            public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                          UUID mutationId, long expectedRevision,
                                                          ContentHash payloadChecksum) {
                throw unavailableFailure();
            }

            @Override
            public CoreGraphCreateResult create(ServerResourceLocator resource,
                                                CoreGraphStorageBoundary.Decoded canonicalEnvelope,
                                                UUID mutationId, long expectedRevision,
                                                ResourcePresentationIntent presentation) {
                throw unavailableFailure();
            }

            @Override
            public void publishCommitted(ServerResourceLocator resource, UUID mutationId) {
                throw unavailableFailure();
            }

            @Override
            public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource,
                                                                       UUID mutationId, long expectedRevision,
                                                                       ContentHash payloadChecksum) {
                throw unavailableFailure();
            }

            @Override
            public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                              ResourceActivationState activationState, UUID mutationId,
                                                              long expectedRevision, ContentHash payloadChecksum) {
                throw unavailableFailure();
            }

            private IllegalStateException unavailableFailure() {
                return new IllegalStateException(UNAVAILABLE_MESSAGE);
            }
        };
    }

    record CoreGraphCreateResult(CoreGraphStorageBoundary.Decoded primary,
                                 FlowStorage.ResourceIdentity projectMetadataIdentity,
                                 String canonicalProjectMetadataJson, boolean replayed) {
        public CoreGraphCreateResult {
            primary = Objects.requireNonNull(primary, "Core graph create primary is required");
            projectMetadataIdentity = Objects.requireNonNull(projectMetadataIdentity,
                "Core graph create project metadata identity is required");
            canonicalProjectMetadataJson = Objects.requireNonNull(canonicalProjectMetadataJson,
                "Core graph create project metadata is required");
            if (projectMetadataIdentity.deleted()) {
                throw new IllegalArgumentException("Core graph create project metadata must be live");
            }
        }
    }

    record LegacyCoreRecoverySource(ServerResourceLocator resource, long revision, UUID mutationId,
                                    ContentHash assetHash, ContentHash payloadHash, String payloadKind) {
        public LegacyCoreRecoverySource {
            resource = Objects.requireNonNull(resource, "Legacy Core recovery resource is required");
            if (revision <= 0L) {
                throw new IllegalArgumentException("Legacy Core recovery revision must be positive");
            }
            mutationId = Objects.requireNonNull(mutationId, "Legacy Core recovery mutation ID is required");
            assetHash = Objects.requireNonNull(assetHash, "Legacy Core recovery asset hash is required");
            payloadHash = Objects.requireNonNull(payloadHash, "Legacy Core recovery payload hash is required");
            if (payloadKind == null || payloadKind.isBlank()) {
                throw new IllegalArgumentException("Legacy Core recovery payload kind is required");
            }
        }
    }

    record LegacyCoreRecoveryResult(CoreGraphStorageBoundary.CoreGraphTombstone tombstone,
                                    FlowStorage.ResourceIdentity projectMetadataIdentity,
                                    String canonicalProjectMetadataJson) {
        public LegacyCoreRecoveryResult {
            tombstone = Objects.requireNonNull(tombstone, "Legacy Core recovery tombstone is required");
            projectMetadataIdentity = Objects.requireNonNull(projectMetadataIdentity,
                "Legacy Core recovery project metadata identity is required");
            canonicalProjectMetadataJson = Objects.requireNonNull(canonicalProjectMetadataJson,
                "Legacy Core recovery project metadata is required");
            if (projectMetadataIdentity.deleted()) {
                throw new IllegalArgumentException("Legacy Core recovery project metadata must be live");
            }
        }
    }

    record CoreRevisionRepairSource(long revision, UUID mutationId, ContentHash assetHash,
                                    ContentHash payloadHash, String payloadKind,
                                    ResourceActivationState activationState) {
        public CoreRevisionRepairSource {
            if (revision < 1L) {
                throw new IllegalArgumentException("Core revision repair source revision must be positive");
            }
            mutationId = Objects.requireNonNull(mutationId, "Core revision repair source mutation ID is required");
            assetHash = Objects.requireNonNull(assetHash, "Core revision repair source asset hash is required");
            payloadHash = Objects.requireNonNull(payloadHash, "Core revision repair source payload hash is required");
            if (payloadKind == null || payloadKind.isBlank()) {
                throw new IllegalArgumentException("Core revision repair source payload kind is required");
            }
            activationState = Objects.requireNonNull(activationState,
                "Core revision repair source activation state is required");
        }
    }

    record CoreRevisionRepairResult(CoreGraphStorageBoundary.Decoded repaired, boolean replayed) {
        public CoreRevisionRepairResult {
            repaired = Objects.requireNonNull(repaired, "Repaired Core graph is required");
        }
    }

    record CoreCatalogRebindSource(CoreGraphStorageBoundary.Decoded decoded) {
        public CoreCatalogRebindSource {
            decoded = Objects.requireNonNull(decoded, "Core catalog rebind source is required");
        }
    }

    record CoreCatalogRebindResult(CoreGraphStorageBoundary.Decoded rebound, boolean replayed) {
        public CoreCatalogRebindResult {
            rebound = Objects.requireNonNull(rebound, "Rebound Core graph is required");
        }
    }

    record CoreGraphResourceState(Kind kind, ServerResourceLocator resource, long revision, UUID mutationId,
                                  ContentHash payloadChecksum, ResourceActivationState activationState,
                                  CoreGraphStorageBoundary.Decoded envelope,
                                  CoreGraphStorageBoundary.CoreGraphTombstone tombstone, byte[] canonicalBytes) {
        public CoreGraphResourceState {
            kind = Objects.requireNonNull(kind, "Core graph resource state kind is required");
            resource = Objects.requireNonNull(resource, "Core graph resource is required");
            if (revision <= 0L) {
                throw new IllegalArgumentException("Core graph resource revision must be positive");
            }
            mutationId = Objects.requireNonNull(mutationId, "Core graph resource mutation ID is required");
            payloadChecksum = Objects.requireNonNull(payloadChecksum, "Core graph resource payload checksum is required");
            canonicalBytes = Objects.requireNonNull(canonicalBytes, "Canonical Core graph bytes are required").clone();
            if ((envelope == null) == (tombstone == null)) {
                throw new IllegalArgumentException("Core graph resource state must contain exactly one payload form");
            }
            if (kind == Kind.LIVE) {
                if (envelope == null || activationState == null || tombstone != null) {
                    throw new IllegalArgumentException("Live Core graph resource state is incomplete");
                }
                if (!resource.equals(payloadResource(envelope)) || envelope.envelope().assetRevision() != revision
                    || !mutationId.toString().equals(envelope.envelope().assetMutationId())
                    || activationState != envelope.envelope().assetActivationState()
                    || !payloadChecksum.equals(envelope.envelope().assetHash())) {
                    throw new IllegalArgumentException("Live Core graph resource state does not match its envelope");
                }
                if (!Arrays.equals(canonicalBytes, new CoreGraphStorageBoundary().encode(envelope))) {
                    throw new IllegalArgumentException("Live Core graph resource state is not canonical");
                }
            } else {
                if (tombstone == null || envelope != null || activationState != null) {
                    throw new IllegalArgumentException("Tombstoned Core graph resource state is incomplete");
                }
                if (!resource.equals(tombstone.resource()) || tombstone.revision() != revision
                    || !mutationId.equals(tombstone.mutationId()) || !tombstone.deleted()
                    || !payloadChecksum.equals(tombstone.priorPayloadHash())) {
                    throw new IllegalArgumentException("Tombstoned Core graph resource state does not match its tombstone");
                }
                if (!Arrays.equals(canonicalBytes, new CoreGraphStorageBoundary().encodeTombstone(tombstone))) {
                    throw new IllegalArgumentException("Tombstoned Core graph resource state is not canonical");
                }
            }
        }

        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }

        public byte[] canonicalEnvelope() {
            return kind == Kind.LIVE ? canonicalBytes() : null;
        }

        public byte[] canonicalTombstone() {
            return kind == Kind.TOMBSTONED ? canonicalBytes() : null;
        }

        public boolean deleted() {
            return kind == Kind.TOMBSTONED;
        }

        public ContentHash corePayloadChecksum() {
            if (envelope == null) {
                return null;
            }
            Object payload = envelope.payload();
            if (payload instanceof GraphDocument graph) {
                return graph.checksum();
            }
            return ((FunctionSourceDocument) payload).checksum();
        }

        private static ServerResourceLocator payloadResource(CoreGraphStorageBoundary.Decoded decoded) {
            Object payload = decoded.payload();
            if (payload instanceof GraphDocument graph) {
                return graph.resource();
            }
            return ((FunctionSourceDocument) payload).graph().resource();
        }

        private static CoreGraphResourceState live(CoreGraphStorageBoundary.Decoded envelope,
                                                   CoreGraphStorageBoundary boundary) {
            Objects.requireNonNull(envelope, "Core graph envelope is required");
            Objects.requireNonNull(boundary, "Core graph storage boundary is required");
            return new CoreGraphResourceState(Kind.LIVE, payloadResource(envelope), envelope.envelope().assetRevision(),
                UUID.fromString(envelope.envelope().assetMutationId()), envelope.envelope().assetHash(),
                envelope.envelope().assetActivationState(), envelope, null, boundary.encode(envelope));
        }

        private static CoreGraphResourceState tombstoned(CoreGraphStorageBoundary.CoreGraphTombstone tombstone,
                                                         CoreGraphStorageBoundary boundary) {
            Objects.requireNonNull(tombstone, "Core graph tombstone is required");
            Objects.requireNonNull(boundary, "Core graph storage boundary is required");
            return new CoreGraphResourceState(Kind.TOMBSTONED, tombstone.resource(), tombstone.revision(),
                tombstone.mutationId(), tombstone.priorPayloadHash(), null, null, tombstone,
                boundary.encodeTombstone(tombstone));
        }

        public static CoreGraphResourceState live(CoreGraphStorageBoundary.Decoded envelope) {
            return live(envelope, new CoreGraphStorageBoundary());
        }

        public static CoreGraphResourceState tombstoned(CoreGraphStorageBoundary.CoreGraphTombstone tombstone) {
            return tombstoned(tombstone, new CoreGraphStorageBoundary());
        }

        public enum Kind {
            LIVE,
            TOMBSTONED
        }
    }
}
