package restudio.resync.server;

import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class FlowStorageCoreGraphResourceAuthority implements CoreGraphResourceAuthority {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> RESOURCE_TYPES = Set.of("flow", "function", "command");

    private final FlowStorage storage;
    private final ServerId serverId;
    private final CoreGraphMutationValidator mutationValidator;
    private final Supplier<OptionCatalogRegistry> optionCatalogs;
    private final CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();

    FlowStorageCoreGraphResourceAuthority(FlowStorage storage, ServerId serverId) {
        this(storage, serverId, CoreGraphMutationValidator.testOnlyUnrestricted());
    }

    public FlowStorageCoreGraphResourceAuthority(FlowStorage storage, ServerId serverId,
                                                 CoreGraphMutationValidator mutationValidator) {
        this(storage, serverId, mutationValidator, null);
    }

    public FlowStorageCoreGraphResourceAuthority(FlowStorage storage, ServerId serverId,
                                                 CoreGraphMutationValidator mutationValidator,
                                                 Supplier<OptionCatalogRegistry> optionCatalogs) {
        this.storage = storage;
        this.serverId = serverId;
        this.mutationValidator = Objects.requireNonNull(mutationValidator, "Core graph mutation validator is required");
        this.optionCatalogs = optionCatalogs;
    }

    public static FlowStorageCoreGraphResourceAuthority unavailable() {
        return new FlowStorageCoreGraphResourceAuthority(null, null);
    }

    @Override
    public boolean available() {
        return storage != null && serverId != null;
    }

    @Override
    public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
        requireResource(resource);
        try {
            return storage.getCoreGraph(resource.resourceType().value(), resource.id())
                .map(decoded -> canonicalDecoded(resource, decoded));
        } catch (IllegalStateException exception) {
            throw unavailableFailure(exception);
        }
    }

    @Override
    public Optional<CatalogBinding> activeCatalogBinding() {
        if (!available()) {
            return Optional.empty();
        }
        return Optional.of(mutationValidator.activeBinding());
    }

    @Override
    public Optional<CoreCatalogEvolution.Proof> activeCatalogEvolution() {
        return available() ? mutationValidator.activeEvolution() : Optional.empty();
    }

    @Override
    public List<CoreGraphResourceState> list(String type) {
        requireType(type);
        if (!available()) {
            throw unavailableFailure(null);
        }
        List<String> ids = storage.listGraphIds(type);
        Set<String> seen = new HashSet<>();
        List<CoreGraphResourceState> states = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (!seen.add(id)) {
                throw new IllegalStateException("Core graph list contains duplicate resource ID: " + type + ':' + id);
            }
            ServerResourceLocator resource = resource(type, id);
            if (storage.coordinatedRawGraphSource(resource).isPresent()) {
                continue;
            }
            storage.getCoreGraph(type, id)
                .map(decoded -> canonicalDecoded(resource, decoded))
                .map(CoreGraphResourceState::live)
                .ifPresent(states::add);
        }
        return states.stream().sorted(Comparator.comparing(CoreGraphResourceState::resource)).toList();
    }

    @Override
    public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
        return load(resource).map(CoreGraphResourceState::live);
    }

    @Override
    public Optional<LegacyCoreRecoverySource> coordinatedRawGraphSource(ServerResourceLocator resource) {
        requireResource(resource);
        return storage.coordinatedRawGraphSource(resource)
            .map(source -> new LegacyCoreRecoverySource(resource, source.revision(), source.mutationId(),
                source.assetHash(), source.payloadHash(), source.payloadKind()));
    }

    @Override
    public Optional<LegacyCoreRecoverySource> legacyRecoverySource(ServerResourceLocator resource,
                                                                    UUID sourceMutationId, long sourceRevision,
                                                                    ContentHash sourceAssetHash) {
        requireResource(resource);
        return storage.legacyCoreRecoverySource(resource, sourceMutationId, sourceRevision, sourceAssetHash)
            .map(source -> new LegacyCoreRecoverySource(resource, source.revision(), source.mutationId(),
                source.assetHash(), source.payloadHash(), source.payloadKind()));
    }

    @Override
    public LegacyCoreRecoveryResult recoverLegacyDelete(ServerResourceLocator resource, UUID sourceMutationId,
                                                         long sourceRevision, ContentHash sourceAssetHash,
                                                         UUID recoveryMutationId) {
        requireResource(resource);
        FlowStorage.LegacyCoreRecoveryResult recovered = storage.recoverLegacyCoreGraph(resource, sourceMutationId,
            sourceRevision, sourceAssetHash, recoveryMutationId);
        return new LegacyCoreRecoveryResult(recovered.tombstone(), recovered.projectMetadataIdentity(),
            recovered.canonicalProjectMetadataJson());
    }

    @Override
    public Optional<CoreRevisionRepairResult> repairRevisionSkew(ServerResourceLocator resource,
                                                                 CoreRevisionRepairSource source,
                                                                 UUID repairMutationId) {
        requireResource(resource);
        Objects.requireNonNull(source, "Core revision repair source is required");
        Objects.requireNonNull(repairMutationId, "Core revision repair mutation ID is required");
        FlowStorage.CoreRevisionRepairSource storageSource = new FlowStorage.CoreRevisionRepairSource(
            source.revision(), source.mutationId(), source.assetHash(), source.payloadHash(), source.payloadKind(),
            source.activationState());
        return storage.repairCoreRevisionSkew(resource, storageSource, repairMutationId)
            .map(result -> new CoreRevisionRepairResult(canonicalDecoded(resource, result.repaired()),
                result.replayed()));
    }

    @Override
    public Optional<CoreCatalogRebindResult> rebindCatalog(ServerResourceLocator resource,
                                                           CoreCatalogRebindSource source,
                                                           UUID mutationId,
                                                           CoreCatalogBindingMigration migration) {
        requireResource(resource);
        Objects.requireNonNull(source, "Core catalog rebind source is required");
        Objects.requireNonNull(mutationId, "Core catalog rebind mutation ID is required");
        Objects.requireNonNull(migration, "Core catalog binding migration is required");
        CoreGraphStorageBoundary.Decoded candidate = migration.project(source.decoded(), mutationId);
        CoreGraphMutationValidator.AdmissionProof proof = mutationValidator.admitCatalogRebind(resource,
            source.decoded(), candidate, migration);
        FlowStorage.RuntimeObservation validation = validateOptions(resource, candidate, proof, mutationId);
        FlowStorage.CoreCatalogRebindSource storageSource = new FlowStorage.CoreCatalogRebindSource(source.decoded());
        return mutationValidator.executeCurrent(proof, resource, candidate,
            () -> storage.rebindCoreCatalog(resource, storageSource, candidate, mutationId, validation))
            .map(result -> new CoreCatalogRebindResult(canonicalDecoded(resource, result.rebound()),
                result.replayed()));
    }

    @Override
    public boolean acceptsCatalogRebind(CoreCatalogBindingMigration migration) {
        Objects.requireNonNull(migration, "Core catalog binding migration is required");
        if (!available()) {
            return false;
        }
        try {
            return migration.target().equals(mutationValidator.activeBinding());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                  UUID mutationId, long expectedRevision, ContentHash payloadChecksum) {
        requireResource(resource);
        Objects.requireNonNull(canonicalEnvelope, "Canonical Core graph envelope is required");
        Objects.requireNonNull(mutationId, "Core graph mutation ID is required");
        requireRevision(expectedRevision);
        Objects.requireNonNull(payloadChecksum, "Core graph payload checksum is required");
        CoreGraphStorageBoundary.Decoded requested = boundary.decode(canonicalEnvelope, resource);
        if (!payloadChecksum.equals(requested.envelope().assetHash())) {
            throw new IllegalArgumentException("Core graph payload checksum does not match the canonical envelope");
        }
        requireSaveEnvelope(resource, requested, mutationId, expectedRevision);
        CoreGraphMutationValidator.AdmissionProof proof = mutationValidator.admit(resource, requested);
        CoreGraphStorageBoundary.Decoded saved = saveDecoded(resource, requested,
            requested.envelope().assetActivationState(), mutationId, expectedRevision, proof);
        if (!requested.envelope().assetHash().equals(saved.envelope().assetHash())) {
            throw new IllegalStateException("Core graph save result does not match the canonical envelope");
        }
        return saved;
    }

    @Override
    public CoreGraphStorageBoundary.Decoded saveCatalogProjection(ServerResourceLocator resource,
            CoreGraphStorageBoundary.Decoded candidate, UUID mutationId, long expectedRevision,
            ContentHash payloadChecksum, CatalogProjection projection) {
        requireResource(resource);
        Objects.requireNonNull(candidate, "Canonical Core Graph Envelope Is Required");
        Objects.requireNonNull(mutationId, "Core Graph Mutation ID Is Required");
        requireRevision(expectedRevision);
        Objects.requireNonNull(payloadChecksum, "Core Graph Payload Checksum Is Required");
        Objects.requireNonNull(projection, "Catalog Projection Admission Is Required");
        CoreGraphStorageBoundary.Decoded requested = canonicalDecoded(resource, candidate);
        if (!payloadChecksum.equals(requested.envelope().assetHash())) {
            throw new IllegalArgumentException("Core Graph Payload Checksum Does Not Match The Canonical Envelope");
        }
        requireSaveEnvelope(resource, requested, mutationId, expectedRevision);
        CoreGraphMutationValidator.AdmissionProof proof = mutationValidator.admit(resource, requested);
        CoreGraphStorageBoundary.Decoded saved = saveDecoded(resource, requested,
            requested.envelope().assetActivationState(), mutationId, expectedRevision, proof, projection);
        if (!requested.envelope().assetHash().equals(saved.envelope().assetHash())) {
            throw new IllegalStateException("Core Graph Save Result Does Not Match The Canonical Envelope");
        }
        return saved;
    }

    @Override
    public boolean supportsAggregateCreate() {
        return true;
    }

    @Override
    public CoreGraphCreateResult create(ServerResourceLocator resource,
                                        CoreGraphStorageBoundary.Decoded canonicalEnvelope,
                                        UUID mutationId, long expectedRevision,
                                        ResourcePresentationIntent presentation) {
        requireResource(resource);
        Objects.requireNonNull(canonicalEnvelope, "Canonical Core graph envelope is required");
        Objects.requireNonNull(mutationId, "Core graph mutation ID is required");
        Objects.requireNonNull(presentation, "Core graph presentation is required");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Aggregate Core create expected revision cannot be negative");
        }
        CoreGraphMutationValidator.AdmissionProof proof;
        FlowStorage.RuntimeObservation validation;
        try {
            requireSaveEnvelope(resource, canonicalEnvelope, mutationId, expectedRevision);
            proof = mutationValidator.admit(resource, canonicalEnvelope);
            validation = validateOptions(resource, canonicalEnvelope, proof, mutationId);
        } catch (IllegalArgumentException | UnsupportedOperationException rejection) {
            String message = rejection instanceof CoreGraphMutationValidationException validationFailure
                ? validationFailure.actionableMessage() : rejection.getMessage();
            throw AggregateResourceCreateStorage.rejectBeforeCommit(
                ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.legacyValue(), message, rejection);
        }
        FlowStorage.CoreAggregateCreate created = mutationValidator.executeCurrent(proof, resource,
            canonicalEnvelope, () -> createDecoded(canonicalEnvelope, presentation, mutationId, expectedRevision, validation));
        CoreGraphStorageBoundary.Decoded canonical = canonicalDecoded(resource, created.primary());
        requireSavedEnvelope(resource, canonicalEnvelope, canonical,
            canonicalEnvelope.envelope().assetActivationState(), mutationId, expectedRevision);
        return new CoreGraphCreateResult(canonical, created.projectMetadataIdentity(),
            created.canonicalProjectMetadataJson(), created.replayed());
    }

    @Override
    public void publishCommitted(ServerResourceLocator resource, UUID mutationId) {
        requireResource(resource);
        Objects.requireNonNull(mutationId, "Core graph mutation ID is required");
        storage.publishCoreGraph(resource.resourceType().value(), resource.id());
    }

    @Override
    public void validateSave(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded canonicalEnvelope) {
        mutationValidator.requireValid(resource, canonicalEnvelope);
    }

    @Override
    public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                               long expectedRevision, ContentHash payloadChecksum) {
        requireResource(resource);
        Objects.requireNonNull(mutationId, "Core graph mutation ID is required");
        requireRevision(expectedRevision);
        Objects.requireNonNull(payloadChecksum, "Core graph payload checksum is required");
        Optional<CoreGraphStorageBoundary.Decoded> current = load(resource);
        if (current.isPresent()) {
            CoreGraphStorageBoundary.Decoded decoded = current.get();
            requireAssetHash(decoded, payloadChecksum);
            return delete(resource, mutationId, expectedRevision, payloadChecksum, corePayloadChecksum(decoded));
        }
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = storage.deleteCoreGraph(resource, mutationId,
            expectedRevision);
        return canonicalTombstone(resource, tombstone, mutationId, expectedRevision, null);
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                               long expectedRevision, ContentHash expectedAssetHash,
                                                               ContentHash payloadChecksum) {
        requireResource(resource);
        Objects.requireNonNull(mutationId, "Core graph mutation ID is required");
        requireRevision(expectedRevision);
        Objects.requireNonNull(expectedAssetHash, "Expected Core graph asset hash is required");
        Objects.requireNonNull(payloadChecksum, "Core graph payload checksum is required");
        Optional<CoreGraphStorageBoundary.Decoded> current = load(resource);
        current.ifPresent(decoded -> {
            requireAssetHash(decoded, expectedAssetHash);
            requireCorePayloadChecksum(decoded, payloadChecksum);
        });
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = storage.deleteCoreGraph(resource, mutationId,
            expectedRevision);
        return canonicalTombstone(resource, tombstone, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, long expectedRevision,
                                                               UUID mutationId, ContentHash expectedAssetHash,
                                                               ContentHash payloadChecksum) {
        return delete(resource, mutationId, expectedRevision, expectedAssetHash, payloadChecksum);
    }

    public CoreGraphResourceState deleteState(ServerResourceLocator resource, UUID mutationId,
                                               long expectedRevision, ContentHash expectedAssetHash,
                                               ContentHash payloadChecksum) {
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = delete(resource, mutationId, expectedRevision,
            expectedAssetHash, payloadChecksum);
        return CoreGraphResourceState.tombstoned(tombstone);
    }

    public CoreGraphResourceState deleteState(ServerResourceLocator resource, long expectedRevision,
                                               UUID mutationId, ContentHash expectedAssetHash,
                                               ContentHash payloadChecksum) {
        return deleteState(resource, mutationId, expectedRevision, expectedAssetHash, payloadChecksum);
    }

    @Override
    public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                      ResourceActivationState activationState, UUID mutationId,
                                                      long expectedRevision, ContentHash payloadChecksum) {
        requireResource(resource);
        Objects.requireNonNull(activationState, "Core graph activation state is required");
        Objects.requireNonNull(mutationId, "Core graph mutation ID is required");
        requireRevision(expectedRevision);
        Objects.requireNonNull(payloadChecksum, "Core graph payload checksum is required");
        CoreGraphStorageBoundary.Decoded current = requireLoaded(resource);
        requireChecksum(current, payloadChecksum);
        CoreGraphStorageBoundary.Decoded projected = projectActivation(resource, current, activationState, mutationId,
            resultRevision(expectedRevision));
        CoreGraphMutationValidator.AdmissionProof proof = mutationValidator.admit(resource, projected);
        return saveDecoded(resource, projected, activationState, mutationId, expectedRevision, proof);
    }

    private CoreGraphStorageBoundary.Decoded projectActivation(ServerResourceLocator resource,
                                                                CoreGraphStorageBoundary.Decoded current,
                                                                ResourceActivationState activationState,
                                                                UUID mutationId, long revision) {
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(
            resource.resourceType().value(), revision, mutationId, activationState);
        if (current.graphDocument() != null) {
            GraphDocument graph = rebaseRevision(current.graphDocument(), revision);
            return boundary.decode(boundary.encode(graph, metadata, resource), resource);
        }
        FunctionSourceDocument source = current.functionSourceDocument();
        GraphDocument graph = rebaseRevision(source.graph(), revision);
        FunctionSignature signature = source.signature();
        FunctionSignature rebased = new FunctionSignature(signature.function(), new FunctionRevision(revision),
            signature.inputs(), signature.outputs(), signature.unknown());
        FunctionSourceDocument projected = new FunctionSourceDocument(rebased, graph, source.unknown());
        return boundary.decode(boundary.encode(projected, metadata, resource), resource);
    }

    private GraphDocument rebaseRevision(GraphDocument graph, long revision) {
        return new GraphDocument(graph.schemaVersion(), graph.resource(), revision, graph.catalogBinding(),
            graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.passthroughs(), graph.variables(), graph.functions(),
            graph.unknown());
    }

    private CoreGraphStorageBoundary.Decoded saveDecoded(ServerResourceLocator resource,
                                                         CoreGraphStorageBoundary.Decoded requested,
                                                         ResourceActivationState activationState, UUID mutationId,
                                                         long expectedRevision,
                                                         CoreGraphMutationValidator.AdmissionProof proof) {
        return saveDecoded(resource, requested, activationState, mutationId, expectedRevision, proof, null);
    }

    private CoreGraphStorageBoundary.Decoded saveDecoded(ServerResourceLocator resource,
            CoreGraphStorageBoundary.Decoded requested, ResourceActivationState activationState,
            UUID mutationId, long expectedRevision, CoreGraphMutationValidator.AdmissionProof proof,
            CatalogProjection projection) {
        Object payload = requested.payload();
        FlowStorage.RuntimeObservation validation = validateOptions(resource, requested, proof, mutationId, projection, expectedRevision);
        CoreGraphStorageBoundary.Decoded saved = mutationValidator.executeCurrent(proof, resource, requested, () -> {
            if (payload instanceof GraphDocument graph) {
                return storage.saveCoreGraph(graph, activationState, mutationId, expectedRevision, validation);
            } else if (payload instanceof FunctionSourceDocument source) {
                return storage.saveCoreGraph(source, activationState, mutationId, expectedRevision, validation);
            }
            throw new IllegalArgumentException("Unsupported Core graph payload type");
        });
        CoreGraphStorageBoundary.Decoded canonical = canonicalDecoded(resource, saved);
        requireSavedEnvelope(resource, requested, canonical, activationState, mutationId, expectedRevision);
        return canonical;
    }

    private FlowStorage.CoreAggregateCreate createDecoded(CoreGraphStorageBoundary.Decoded requested,
                                                           ResourcePresentationIntent presentation,
                                                           UUID mutationId, long expectedRevision,
                                                           FlowStorage.RuntimeObservation validation) {
        Object payload = requested.payload();
        ResourceActivationState activationState = requested.envelope().assetActivationState();
        if (payload instanceof GraphDocument graph) {
            return storage.createCoreGraph(graph, activationState, mutationId, expectedRevision, presentation, validation);
        }
        if (payload instanceof FunctionSourceDocument source) {
            return storage.createCoreGraph(source, activationState, mutationId, expectedRevision, presentation, validation);
        }
        throw new IllegalArgumentException("Unsupported Core graph payload type");
    }

    private FlowStorage.RuntimeObservation validateOptions(ServerResourceLocator resource,
                                                            CoreGraphStorageBoundary.Decoded requested,
                                                            CoreGraphMutationValidator.AdmissionProof proof,
                                                            UUID mutationId) {
        return validateOptions(resource, requested, proof, mutationId, null, 0L);
    }

    private FlowStorage.RuntimeObservation validateOptions(ServerResourceLocator resource,
            CoreGraphStorageBoundary.Decoded requested, CoreGraphMutationValidator.AdmissionProof proof,
            UUID mutationId, CatalogProjection projection, long expectedRevision) {
        if (projection != null) {
            projection.requireCurrent(this, resource, requested, mutationId, expectedRevision, requested.envelope().assetHash());
        }
        FlowStorage.RuntimeObservation observation = storage.observeRuntime()
            .orElseThrow(() -> new IllegalStateException("Asset state is unavailable for Core graph validation"));
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey(
            resource.resourceType().value(), resource.id());
        boolean replay = observation.coordinator().committedAsset(key)
            .filter(asset -> asset.state() instanceof AssetTransactionCoordinator.Live)
            .map(asset -> mutationId.toString().equals(asset.mutationId().value())).orElse(false);
        if (!replay && projection == null && optionCatalogs != null && !trustedCatalogProjection(resource, requested, mutationId)) {
            Objects.requireNonNull(proof, "Core graph option validation requires catalog admission");
            OptionCatalogRegistry catalogs = Objects.requireNonNull(optionCatalogs.get(),
                "Core graph option catalogs are unavailable");
            GraphDocument graph = requested.graphDocument() != null
                ? requested.graphDocument() : requested.functionSourceDocument().graph();
            List<Diagnostic> diagnostics = new CoreGraphOptionValidator(catalogs).validate(graph, proof.activation().catalog());
            if (!diagnostics.isEmpty()) {
                throw new CoreGraphMutationValidationException(diagnostics);
            }
        }
        return observation;
    }

    private boolean trustedCatalogProjection(ServerResourceLocator resource,
                                             CoreGraphStorageBoundary.Decoded requested, UUID mutationId) {
        if (requested == null || mutationId == null
            || !mutationId.toString().equals(requested.envelope().assetMutationId())) {
            return false;
        }
        CatalogBinding target;
        try {
            target = mutationValidator.activeBinding();
        } catch (RuntimeException unavailable) {
            return false;
        }
        if (!target.equals(CoreCatalogCompatibilityRebind.graph(requested).catalogBinding())) {
            return false;
        }
        Optional<CoreGraphStorageBoundary.Decoded> current = load(resource);
        if (current.isEmpty()) {
            return false;
        }
        CoreGraphStorageBoundary.Decoded source = current.orElseThrow();
        if (CoreCatalogCompatibilityRebind.exactProjection(resource, source, requested, target)) {
            return true;
        }
        if (target.equals(CoreCatalogCompatibilityRebind.graph(source).catalogBinding())) {
            return false;
        }
        CoreCatalogEvolution.Proof evolution = mutationValidator.activeEvolution().orElse(null);
        return evolution != null && evolution.target().equals(target)
            && evolution.matchesProjection(source, requested, mutationId);
    }

    private CoreGraphStorageBoundary.Decoded requireLoaded(ServerResourceLocator resource) {
        return load(resource).orElseThrow(() -> new IllegalStateException("Core graph resource is not available: "
            + resource.canonicalText()));
    }

    private CoreGraphStorageBoundary.Decoded canonicalDecoded(ServerResourceLocator resource,
                                                              CoreGraphStorageBoundary.Decoded decoded) {
        Objects.requireNonNull(decoded, "Core graph decoded payload is required");
        byte[] encoded = boundary.encode(decoded);
        CoreGraphStorageBoundary.Decoded canonical = boundary.decode(encoded, resource);
        if (!resource.equals(payloadResource(canonical))) {
            throw new IllegalStateException("Core graph payload locator does not match the requested resource");
        }
        return canonical;
    }

    private CoreGraphStorageBoundary.CoreGraphTombstone canonicalTombstone(ServerResourceLocator resource,
                                                                             CoreGraphStorageBoundary.CoreGraphTombstone tombstone,
                                                                             UUID mutationId,
                                                                             long expectedRevision,
                                                                             ContentHash payloadChecksum) {
        Objects.requireNonNull(tombstone, "Core graph tombstone is required");
        byte[] encoded = boundary.encodeTombstone(tombstone);
        CoreGraphStorageBoundary.CoreGraphTombstone canonical = boundary.decodeTombstone(encoded, resource);
        if (!resource.equals(canonical.resource()) || !mutationId.equals(canonical.mutationId())
            || canonical.revision() != resultRevision(expectedRevision)
            || (payloadChecksum != null && !payloadChecksum.equals(canonical.priorPayloadHash()))
            || !canonical.deleted()) {
            throw new IllegalStateException("Core graph tombstone does not match the requested mutation");
        }
        return canonical;
    }

    private void requireChecksum(CoreGraphStorageBoundary.Decoded decoded, ContentHash payloadChecksum) {
        if (!payloadChecksum.equals(decoded.envelope().assetHash())) {
            throw new IllegalStateException("Core graph payload checksum does not match the authoritative state");
        }
    }

    private void requireAssetHash(CoreGraphStorageBoundary.Decoded decoded, ContentHash expectedAssetHash) {
        if (!expectedAssetHash.equals(decoded.envelope().assetHash())) {
            throw new IllegalStateException("Core graph asset hash does not match the authoritative state");
        }
    }

    private void requireCorePayloadChecksum(CoreGraphStorageBoundary.Decoded decoded, ContentHash payloadChecksum) {
        if (!payloadChecksum.equals(corePayloadChecksum(decoded))) {
            throw new IllegalStateException("Core graph payload checksum does not match the authoritative state");
        }
    }

    private void requireSaveEnvelope(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded requested,
                                     UUID mutationId, long expectedRevision) {
        long expectedResultRevision = resultRevision(expectedRevision);
        if (!resource.equals(payloadResource(requested))
            || requested.envelope().assetRevision() != expectedResultRevision
            || !mutationId.toString().equals(requested.envelope().assetMutationId())) {
            throw new IllegalArgumentException("Core graph save envelope does not match the requested mutation");
        }
    }

    private void requireSavedEnvelope(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded requested,
                                      CoreGraphStorageBoundary.Decoded saved,
                                      ResourceActivationState activationState, UUID mutationId,
                                      long expectedRevision) {
        if (!resource.equals(payloadResource(saved))
            || saved.envelope().assetRevision() != resultRevision(expectedRevision)
            || !mutationId.toString().equals(saved.envelope().assetMutationId())
            || saved.envelope().assetActivationState() != activationState
            || !requested.corePayloadKind().equals(saved.corePayloadKind())
            || !corePayloadChecksum(requested).equals(corePayloadChecksum(saved))) {
            throw new IllegalStateException("Core graph save result does not match the requested mutation");
        }
    }

    private long resultRevision(long expectedRevision) {
        try {
            return Math.addExact(expectedRevision, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Expected Core graph revision is too large", exception);
        }
    }

    private void requireResource(ServerResourceLocator resource) {
        if (!available()) {
            throw unavailableFailure(null);
        }
        Objects.requireNonNull(resource, "Core graph resource is required");
        if (!serverId.equals(resource.serverId())) {
            throw new IllegalArgumentException("Core graph resource server does not match the authority");
        }
        if (!OWNER.equals(resource.owner()) || !RESOURCE_TYPES.contains(resource.resourceType().value())) {
            throw new IllegalArgumentException("Core graph resource locator is not authoritative");
        }
    }

    private void requireType(String type) {
        if (type == null || !RESOURCE_TYPES.contains(type)) {
            throw new IllegalArgumentException("Core graph type must be flow, function, or command");
        }
    }

    private void requireRevision(long expectedRevision) {
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Expected Core graph revision cannot be negative");
        }
    }

    private IllegalStateException unavailableFailure(Throwable cause) {
        return cause == null ? new IllegalStateException(UNAVAILABLE_MESSAGE)
            : new IllegalStateException(UNAVAILABLE_MESSAGE, cause);
    }

    private ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(serverId, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private ServerResourceLocator payloadResource(CoreGraphStorageBoundary.Decoded decoded) {
        Object payload = decoded.payload();
        if (payload instanceof GraphDocument graph) {
            return graph.resource();
        }
        return ((FunctionSourceDocument) payload).graph().resource();
    }

    private ContentHash corePayloadChecksum(CoreGraphStorageBoundary.Decoded decoded) {
        Object payload = decoded.payload();
        if (payload instanceof GraphDocument graph) {
            return graph.checksum();
        }
        return ((FunctionSourceDocument) payload).checksum();
    }
}
