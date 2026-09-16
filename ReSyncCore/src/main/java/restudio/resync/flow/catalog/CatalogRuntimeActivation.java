package restudio.resync.flow.catalog;

import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.runtime.RuntimeBindingRegistry.RuntimePostCommitDiagnostic;
import restudio.resync.flow.type.TypeExpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class CatalogRuntimeActivation {
    private static final OwnerId BUNDLED_NODE_OWNER = OwnerId.of("builtin");
    private static final OwnerId CORE_NODE_OWNER = OwnerId.of("restudio.resync");
    private final AtomicReference<ActivationRecord> active;
    private final AtomicReference<PublicationIdentity> publicationIdentity;

    public CatalogRuntimeActivation(CatalogSnapshot catalog, RuntimeRegistrySnapshot runtime) {
        this(new ActivationRecord(catalog, runtime, Optional.empty()));
    }

    public CatalogRuntimeActivation(ActivationRecord initial) {
        initial = Objects.requireNonNull(initial, "Initial Activation Record Is Required");
        active = new AtomicReference<>(initial);
        publicationIdentity = new AtomicReference<>(initial.publicationKey().map(PublicationIdentity::from).orElse(null));
    }

    public static CatalogRuntimeActivation bootstrap(
        CatalogSnapshot candidate,
        RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement,
        CatalogCacheKey publicationKey
    ) {
        Objects.requireNonNull(candidate, "Bootstrap Catalog Is Required");
        Objects.requireNonNull(runtimeReplacement, "Bootstrap Runtime Replacement Is Required");
        RuntimeRegistrySnapshot preview = runtimeReplacement.preview();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(
            new ActivationRecord(candidate, preview, publicationKey == null ? Optional.empty() : Optional.of(publicationKey)));
        RuntimeBindingRegistry.RuntimeReplacementResult result;
        try {
            result = runtimeReplacement.commit(runtime -> activation.active.set(
                new ActivationRecord(candidate, runtime,
                    publicationKey == null ? Optional.empty() : Optional.of(publicationKey))));
        } catch (RuntimeException | Error failure) {
            runtimeReplacement.close();
            throw failure;
        }
        if (!result.committed()) {
            runtimeReplacement.close();
            throw new IllegalStateException("Catalog runtime bootstrap was not committed: " + result.status());
        }
        return activation;
    }

    public ActivationRecord active() {
        return active.get();
    }

    public CatalogSnapshot catalog() {
        return active().catalog();
    }

    public RuntimeRegistrySnapshot runtime() {
        return active().runtime();
    }

    public Optional<CatalogCacheKey> activePublicationKey() {
        return active().publicationKey();
    }

    public synchronized boolean withPublicationFence(
        ActivationRecord expected,
        CatalogCacheKey publicationKey,
        BooleanSupplier action
    ) {
        Objects.requireNonNull(expected, "Expected Activation Record Is Required");
        Objects.requireNonNull(publicationKey, "Publication Key Is Required");
        Objects.requireNonNull(action, "Publication Fence Action Is Required");
        ActivationRecord current = active.get();
        if (current != expected || current.publicationKey().filter(publicationKey::equals).isEmpty()) {
            return false;
        }
        return action.getAsBoolean();
    }

    public synchronized ActivationRecord publishPublicationKey(CatalogCacheKey key) {
        Objects.requireNonNull(key, "Publication Key Is Required");
        while (true) {
            ActivationRecord current = active.get();
            requirePublicationKey(current, key);
            requireCompatiblePublicationKey(current.catalog(), key);
            if (current.publicationKey().filter(key::equals).isPresent()) {
                return current;
            }
            ActivationRecord next = new ActivationRecord(current.catalog(), current.runtime(), Optional.of(key));
            if (active.compareAndSet(current, next)) {
                publicationIdentity.compareAndSet(null, PublicationIdentity.from(key));
                return next;
            }
        }
    }

    public synchronized ActivationRecord publishPublicationKey(
        ActivationRecord expected,
        CatalogCacheKey key
    ) {
        Objects.requireNonNull(expected, "Expected Activation Record Is Required");
        Objects.requireNonNull(key, "Publication Key Is Required");
        if (active.get() != expected) {
            throw new IllegalStateException("Catalog Runtime Activation Changed During Publication");
        }
        return publishPublicationKey(key);
    }

    public synchronized ActivationTransaction stage(
        CatalogSnapshot candidate,
        RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement,
        CatalogCacheKey publicationKey
    ) {
        return stage(candidate, runtimeReplacement, publicationKey, false);
    }

    public synchronized ActivationTransaction stageReplacement(
        CatalogSnapshot candidate,
        RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement
    ) {
        return stage(candidate, runtimeReplacement, null, true);
    }

    public synchronized ActivationTransaction stageExactRestore(
        ActivationTransaction publishedTransaction,
        RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement
    ) {
        Objects.requireNonNull(publishedTransaction, "Published Activation Transaction Is Required");
        Objects.requireNonNull(runtimeReplacement, "Restore Runtime Replacement Is Required");
        if (!publishedTransaction.belongsTo(this)) {
            throw new IllegalArgumentException("Published Activation Transaction Belongs To Another Activation");
        }
        if (!publishedTransaction.published) {
            throw new IllegalStateException("Exact Catalog Restore Requires A Published Activation Transaction");
        }
        ActivationRecord live = active.get();
        if (!publishedTransaction.matchesLiveCandidate(live)) {
            throw new IllegalStateException("Exact Catalog Restore Requires The Published Candidate To Be Live");
        }
        if (runtimeReplacement.baseline() != live.runtime()) {
            throw new IllegalStateException("Restore Runtime Replacement Was Prepared Against A Stale Activation");
        }
        RuntimeRegistrySnapshot preview = runtimeReplacement.preview();
        ActivationRecord previous = publishedTransaction.baseline;
        if (!previous.catalog().bindingManifestHash().equals(preview.bindingManifestHash())) {
            throw new IllegalArgumentException("Restore Catalog Does Not Bind Restore Runtime Manifest");
        }
        return new ActivationTransaction(live, previous.catalog(), runtimeReplacement, preview,
            previous.publicationKey());
    }

    private ActivationTransaction stage(
        CatalogSnapshot candidate,
        RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement,
        CatalogCacheKey publicationKey,
        boolean replacePublicationKey
    ) {
        Objects.requireNonNull(candidate, "Candidate Catalog Is Required");
        Objects.requireNonNull(runtimeReplacement, "Candidate Runtime Replacement Is Required");
        ActivationRecord baseline = active.get();
        RuntimeRegistrySnapshot preview = runtimeReplacement.preview();
        if (runtimeReplacement.baseline() != baseline.runtime()) {
            throw new IllegalStateException("Runtime Replacement Was Prepared Against A Stale Activation");
        }
        if (!candidate.bindingManifestHash().equals(preview.bindingManifestHash())) {
            throw new IllegalArgumentException("Candidate Catalog Does Not Bind Candidate Runtime Manifest");
        }
        requirePinCompatibility(baseline.catalog(), candidate);
        requireGenerationCompatibility(baseline.catalog(), candidate);
        boolean sameCatalogPublication = sameSemanticState(candidate, baseline.catalog())
            && candidate.generation() == baseline.catalog().generation();
        Optional<CatalogCacheKey> key = publicationKey == null
            ? replacePublicationKey && !sameCatalogPublication ? Optional.empty() : baseline.publicationKey()
            : Optional.of(publicationKey);
        key.ifPresent(value -> {
            requirePublicationKey(baseline, value);
            requirePublicationKey(candidate, value);
        });
        return new ActivationTransaction(baseline, candidate, runtimeReplacement, preview, key);
    }

    private static void requireGenerationCompatibility(CatalogSnapshot baseline, CatalogSnapshot candidate) {
        long baselineGeneration = baseline.generation();
        long candidateGeneration = candidate.generation();
        if (candidateGeneration < baselineGeneration
            || (baselineGeneration == Long.MAX_VALUE && candidateGeneration > baselineGeneration)
            || (baselineGeneration < Long.MAX_VALUE && candidateGeneration > baselineGeneration + 1)) {
            throw new IllegalArgumentException("Candidate Catalog Generation Is Not Monotonic");
        }
        if (!sameSemanticState(candidate, baseline) && candidateGeneration != baselineGeneration + 1) {
            throw new IllegalArgumentException("Candidate Catalog Generation Is Not Monotonic");
        }
    }

    public synchronized ActivationResult activate(
        CatalogSnapshot candidate,
        RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement,
        CatalogCacheKey publicationKey
    ) {
        ActivationTransaction transaction = null;
        try {
            transaction = stage(candidate, runtimeReplacement, publicationKey);
            ActivationResult result = transaction.commit();
            if (result.pending()) {
                transaction.close();
            }
            return result;
        } catch (RuntimeException | Error failure) {
            if (transaction != null) {
                transaction.close();
            } else if (runtimeReplacement != null) {
                runtimeReplacement.close();
            }
            throw failure;
        }
    }

    private synchronized ActivationResult commit(ActivationTransaction transaction) {
        if (transaction.closed) {
            throw new IllegalStateException("Activation Transaction Is Closed");
        }
        if (transaction.published) {
            RuntimeBindingRegistry.RuntimeReplacementResult retry = transaction.runtimeReplacement.commit();
            if (!retry.healthDegraded()) {
                transaction.closed = true;
            }
            return new ActivationResult(
                retry.status() == RuntimeBindingRegistry.ReplacementStatus.COMMITTED_WITH_DIAGNOSTICS
                    ? ActivationStatus.COMMITTED_WITH_DIAGNOSTICS : ActivationStatus.COMMITTED,
                active.get(),
                retry.healthDegraded() ? "Catalog Runtime Activation Remains Committed With Cleanup Diagnostics"
                    : "Catalog Runtime Activation Cleanup Completed",
                retry.diagnostics());
        }
        ActivationRecord current = active.get();
        if (current != transaction.baseline) {
            return new ActivationResult(ActivationStatus.STALE, current, "Catalog Runtime Activation Changed During Commit");
        }
        AtomicReference<ActivationRecord> committedRecord = new AtomicReference<>();
        RuntimeBindingRegistry.RuntimeReplacementResult runtimeResult = transaction.runtimeReplacement.commit(runtime -> {
            ActivationRecord next = new ActivationRecord(transaction.candidate, runtime, transaction.publicationKey);
            if (!sameActivationState(next, current)) {
                active.set(next);
                next.publicationKey().ifPresent(value -> publicationIdentity.compareAndSet(null, PublicationIdentity.from(value)));
                committedRecord.set(next);
            }
        });
        if (runtimeResult.status() == RuntimeBindingRegistry.ReplacementStatus.BLOCKED) {
            return new ActivationResult(ActivationStatus.BLOCKED, current,
                "Runtime provider retirement is blocked by " + runtimeResult.blockedLeases() + " active lease(s)");
        }
        if (runtimeResult.status() == RuntimeBindingRegistry.ReplacementStatus.STALE) {
            return new ActivationResult(ActivationStatus.STALE, current, "Runtime Active Snapshot Changed During Commit");
        }
        ActivationRecord next = committedRecord.get();
        if (next == null) {
            transaction.published = true;
            if (!runtimeResult.healthDegraded()) {
                transaction.closed = true;
            }
            return new ActivationResult(
                runtimeResult.healthDegraded() ? ActivationStatus.COMMITTED_WITH_DIAGNOSTICS : ActivationStatus.NOOP,
                current,
                runtimeResult.healthDegraded() ? "Catalog Runtime Activation Is Unchanged With Cleanup Diagnostics"
                    : "Catalog Runtime Activation Is Semantically Unchanged",
                runtimeResult.diagnostics());
        }
        transaction.published = true;
        if (!runtimeResult.healthDegraded()) {
            transaction.closed = true;
        }
        return new ActivationResult(
            runtimeResult.healthDegraded() ? ActivationStatus.COMMITTED_WITH_DIAGNOSTICS : ActivationStatus.COMMITTED,
            next,
            runtimeResult.healthDegraded() ? "Catalog Runtime Activation Committed With Cleanup Diagnostics"
                : "Catalog Runtime Activation Committed",
            runtimeResult.diagnostics());
    }

    private static void requirePublicationKey(CatalogSnapshot catalog, CatalogCacheKey key) {
        if (key.catalogGeneration() != catalog.generation()
            || !key.snapshotChecksum().equals(catalog.contentChecksum())
            || !CatalogProjectionVersion.isSupported(key.projectionVersion())) {
            throw new IllegalArgumentException("Publication Key Does Not Bind Candidate Catalog");
        }
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
        if (key.projectionVersion().requiresCatalogBinding() && !binding.equals(key.catalogBinding())) {
            throw new IllegalArgumentException("Publication Key Does Not Carry Candidate Catalog Binding");
        }
        if (key.catalogBinding() != null && !binding.equals(key.catalogBinding())) {
            throw new IllegalArgumentException("Publication Key Does Not Carry Candidate Catalog Binding");
        }
    }

    private void requirePublicationKey(ActivationRecord baseline, CatalogCacheKey key) {
        PublicationIdentity expected = publicationIdentity.get();
        if (expected != null && !expected.matches(key)) {
            throw new IllegalArgumentException("Publication Key Does Not Preserve Catalog Publication Identity");
        }
        baseline.publicationKey().ifPresent(previous -> {
            if (!PublicationIdentity.from(previous).matches(key)) {
                throw new IllegalArgumentException("Publication Key Does Not Preserve Catalog Publication Identity");
            }
        });
    }

    private static void requireCompatiblePublicationKey(CatalogSnapshot catalog, CatalogCacheKey key) {
        requirePublicationKey(catalog, key);
    }

    private static boolean sameSemanticState(CatalogSnapshot left, CatalogSnapshot right) {
        return left.contentChecksum().equals(right.contentChecksum())
            && left.bindingManifestHash().equals(right.bindingManifestHash());
    }

    private static boolean sameActivationState(ActivationRecord left, ActivationRecord right) {
        return sameSemanticState(left.catalog(), right.catalog())
            && left.catalog().generation() == right.catalog().generation()
            && left.runtime() == right.runtime()
            && left.publicationKey().equals(right.publicationKey());
    }

    private static void requirePinCompatibility(CatalogSnapshot baseline, CatalogSnapshot candidate) {
        Map<NodeIdentity, CatalogNodeDescriptor> baselineNodes = nodeDescriptors(baseline);
        Map<NodeIdentity, CatalogNodeDescriptor> candidateNodes = nodeDescriptors(candidate);
        for (Map.Entry<NodeIdentity, CatalogNodeDescriptor> entry : baselineNodes.entrySet()) {
            NodeIdentity identity = entry.getKey();
            CatalogNodeDescriptor previous = entry.getValue();
            CatalogNodeDescriptor next = candidateNodes.get(identity);
            if (next == null) {
                if (!isProtectedNodeOwner(identity)) {
                    continue;
                }
                throw new IllegalArgumentException("Candidate Catalog Removes Node Pins Without A Complete Migration Edge");
            }
            if (next.schemaVersion() < previous.schemaVersion()) {
                throw new IllegalArgumentException("Candidate Catalog Node Schema Version Must Be Monotonic");
            }
            List<PinSignature> previousSignature = pinSignature(previous);
            List<PinSignature> nextSignature = pinSignature(next);
            if (previousSignature.equals(nextSignature)) {
                continue;
            }
            if (next.schemaVersion() <= previous.schemaVersion()) {
                throw new IllegalArgumentException("Candidate Catalog Pin Identity Changes Require A Monotonic Schema Advance");
            }
            if (!hasCompleteMigrationEdge(candidate, identity, previous, next,
                pinIds(previousSignature), pinIds(nextSignature))) {
                throw new IllegalArgumentException("Candidate Catalog Pin Identity Changes Require A Complete Migration Edge");
            }
        }
    }

    private static boolean isProtectedNodeOwner(NodeIdentity identity) {
        return BUNDLED_NODE_OWNER.equals(identity.ownerId()) || CORE_NODE_OWNER.equals(identity.ownerId());
    }

    private static Map<NodeIdentity, CatalogNodeDescriptor> nodeDescriptors(CatalogSnapshot catalog) {
        Map<NodeIdentity, CatalogNodeDescriptor> result = new HashMap<>();
        for (CatalogOwned<CatalogNodeDescriptor> owned : catalog.definitions()) {
            result.put(new NodeIdentity(owned.key().owner(), owned.descriptor().id()), owned.descriptor());
        }
        return result;
    }

    private static List<PinSignature> pinSignature(CatalogNodeDescriptor descriptor) {
        List<PinSignature> pins = new ArrayList<>(descriptor.pins().size());
        for (int order = 0; order < descriptor.pins().size(); order++) {
            CatalogNodeDescriptor.Pin pin = descriptor.pins().get(order);
            pins.add(new PinSignature(pin.id(), pin.direction(), pin.type(), order));
        }
        return List.copyOf(pins);
    }

    private static Set<DirectedPin> pinIds(List<PinSignature> signature) {
        Set<DirectedPin> pins = new HashSet<>();
        for (PinSignature pin : signature) {
            if (!pins.add(new DirectedPin(pin.direction(), pin.id().value()))) {
                throw new IllegalArgumentException("Catalog Node Pin IDs Must Be Unique Per Direction");
            }
        }
        return Set.copyOf(pins);
    }

    private static boolean hasCompleteMigrationEdge(
        CatalogSnapshot candidate,
        NodeIdentity identity,
        CatalogNodeDescriptor previous,
        CatalogNodeDescriptor next,
        Set<DirectedPin> previousPins,
        Set<DirectedPin> nextPins
    ) {
        for (CatalogOwned<CatalogMigrationEdge> owned : candidate.migrations()) {
            CatalogMigrationEdge migration = owned.descriptor();
            if (migration.fromVersion() != previous.schemaVersion()
                || migration.toVersion() != next.schemaVersion()
                || !migrationScopeMatches(migration, identity)) {
                continue;
            }
            List<CatalogMigrationEdge.PinMapping> mappings = migration.pinMappings();
            boolean additiveOutputs = preservesPinsAndAddsOutputs(previous, next)
                && mappings.stream().allMatch(mapping -> mapping.identityMeaningful()
                    && mapping.source().value().equals(mapping.target().value()));
            if (mappings.size() != previousPins.size()
                || mappings.size() != nextPins.size() && !additiveOutputs) {
                continue;
            }
            Set<DirectedPin> sources = new HashSet<>();
            Set<DirectedPin> targets = new HashSet<>();
            Map<DirectedPin, DirectedPin> edges = new HashMap<>();
            Map<CatalogNodeDescriptor.Direction, Integer> previousDirections = directionCounts(previousPins);
            Map<CatalogNodeDescriptor.Direction, Integer> mappingDirections = new HashMap<>();
            boolean valid = true;
            for (CatalogMigrationEdge.PinMapping mapping : mappings) {
                DirectedPin source = new DirectedPin(mapping.direction(), mapping.source().value());
                DirectedPin target = new DirectedPin(mapping.direction(), mapping.target().value());
                if (!identity.equals(new NodeIdentity(mapping.ownerId(), mapping.nodeId()))
                    || mapping.sourceSchemaVersion() != previous.schemaVersion()
                    || mapping.targetSchemaVersion() != next.schemaVersion()
                    || !nextPins.contains(target)
                    || !sources.add(source)
                    || !targets.add(target)) {
                    valid = false;
                    break;
                }
                mappingDirections.merge(mapping.direction(), 1, Integer::sum);
                edges.put(source, target);
            }
            if (valid && sources.size() == previousPins.size()
                && mappingDirections.equals(previousDirections)
                && (targets.equals(nextPins) || additiveOutputs && sources.equals(previousPins)
                    && targets.equals(previousPins))
                && !hasPinMappingCycle(edges, mappings)) {
                return true;
            }
        }
        return false;
    }

    private static boolean preservesPinsAndAddsOutputs(CatalogNodeDescriptor previous, CatalogNodeDescriptor next) {
        int retained = previous.pins().size();
        return next.pins().size() > retained
            && previous.pins().equals(next.pins().subList(0, retained))
            && next.pins().subList(retained, next.pins().size()).stream()
                .allMatch(pin -> pin.direction() == CatalogNodeDescriptor.Direction.OUTPUT);
    }

    private static boolean migrationScopeMatches(CatalogMigrationEdge migration, NodeIdentity identity) {
        return migration.ownerId() != null
            && migration.nodeId() != null
            && migration.ownerId().equals(identity.ownerId())
            && migration.nodeId().equals(identity.nodeId());
    }

    private static Map<CatalogNodeDescriptor.Direction, Integer> directionCounts(Set<DirectedPin> pins) {
        Map<CatalogNodeDescriptor.Direction, Integer> counts = new HashMap<>();
        for (DirectedPin pin : pins) {
            counts.merge(pin.direction(), 1, Integer::sum);
        }
        return counts;
    }

    private static boolean hasPinMappingCycle(Map<DirectedPin, DirectedPin> edges, List<CatalogMigrationEdge.PinMapping> mappings) {
        Set<DirectedPin> identityPins = new HashSet<>();
        for (CatalogMigrationEdge.PinMapping mapping : mappings) {
            DirectedPin source = new DirectedPin(mapping.direction(), mapping.source().value());
            DirectedPin target = new DirectedPin(mapping.direction(), mapping.target().value());
            if (mapping.identityMeaningful() && source.equals(target)) {
                identityPins.add(source);
            }
        }
        Set<DirectedPin> visiting = new HashSet<>();
        Set<DirectedPin> visited = new HashSet<>();
        for (DirectedPin source : edges.keySet()) {
            if (!identityPins.contains(source) && hasPinMappingCycle(source, edges, identityPins, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPinMappingCycle(
        DirectedPin source,
        Map<DirectedPin, DirectedPin> edges,
        Set<DirectedPin> identityPins,
        Set<DirectedPin> visiting,
        Set<DirectedPin> visited
    ) {
        if (identityPins.contains(source)) {
            return false;
        }
        if (visiting.contains(source)) {
            return true;
        }
        if (!visited.add(source)) {
            return false;
        }
        visiting.add(source);
        DirectedPin target = edges.get(source);
        boolean cycle = target != null && !identityPins.contains(target)
            && hasPinMappingCycle(target, edges, identityPins, visiting, visited);
        visiting.remove(source);
        return cycle;
    }

    private record NodeIdentity(OwnerId ownerId, NodeId nodeId) {
        private NodeIdentity {
            ownerId = Objects.requireNonNull(ownerId, "ownerId");
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
        }
    }

    private record PinSignature(PinId id, CatalogNodeDescriptor.Direction direction, TypeExpr type, int order) {
        private PinSignature {
            id = Objects.requireNonNull(id, "id");
            direction = Objects.requireNonNull(direction, "direction");
            type = Objects.requireNonNull(type, "type");
            if (order < 0) {
                throw new IllegalArgumentException("Pin order must not be negative");
            }
        }
    }

    private record DirectedPin(CatalogNodeDescriptor.Direction direction, String value) {
        private DirectedPin {
            direction = Objects.requireNonNull(direction, "direction");
            value = Objects.requireNonNull(value, "value");
        }
    }

    private record PublicationIdentity(ServerId serverId,
                                      CatalogProjectionVersion projectionVersion) {
        private PublicationIdentity {
            serverId = Objects.requireNonNull(serverId, "serverId");
            projectionVersion = Objects.requireNonNull(projectionVersion, "projectionVersion");
        }

        private static PublicationIdentity from(CatalogCacheKey key) {
            return new PublicationIdentity(key.serverId(), key.projectionVersion());
        }

        private boolean matches(CatalogCacheKey key) {
            return serverId.equals(key.serverId()) && projectionVersion.equals(key.projectionVersion());
        }
    }

    public enum ActivationStatus {
        COMMITTED,
        COMMITTED_WITH_DIAGNOSTICS,
        NOOP,
        BLOCKED,
        STALE
    }

    public record ActivationResult(
        ActivationStatus status,
        ActivationRecord record,
        String detail,
        List<RuntimePostCommitDiagnostic> diagnostics
    ) {
        public ActivationResult {
            status = Objects.requireNonNull(status, "Activation Status Is Required");
            record = Objects.requireNonNull(record, "Activation Record Is Required");
            detail = detail == null ? "" : detail;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            if (status != ActivationStatus.COMMITTED_WITH_DIAGNOSTICS && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("Only A Committed Activation May Carry Post-Commit Diagnostics");
            }
        }

        public ActivationResult(ActivationStatus status, ActivationRecord record, String detail) {
            this(status, record, detail, List.of());
        }

        public boolean committed() {
            return status == ActivationStatus.COMMITTED
                || status == ActivationStatus.COMMITTED_WITH_DIAGNOSTICS
                || status == ActivationStatus.NOOP;
        }

        public boolean pending() {
            return status == ActivationStatus.BLOCKED || status == ActivationStatus.STALE;
        }

        public boolean healthDegraded() {
            return !diagnostics.isEmpty();
        }
    }

    public final class ActivationTransaction implements AutoCloseable {
        private final ActivationRecord baseline;
        private final CatalogSnapshot candidate;
        private final RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement;
        private final RuntimeRegistrySnapshot candidateRuntime;
        private final Optional<CatalogCacheKey> publicationKey;
        private boolean closed;
        private boolean published;

        private ActivationTransaction(
            ActivationRecord baseline,
            CatalogSnapshot candidate,
            RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement,
            RuntimeRegistrySnapshot candidateRuntime,
            Optional<CatalogCacheKey> publicationKey
        ) {
            this.baseline = baseline;
            this.candidate = candidate;
            this.runtimeReplacement = runtimeReplacement;
            this.candidateRuntime = Objects.requireNonNull(candidateRuntime, "Candidate Runtime Snapshot Is Required");
            this.publicationKey = publicationKey;
        }

        public ActivationRecord baseline() {
            return baseline;
        }

        public ActivationRecord candidate() {
            return new ActivationRecord(candidate, candidateRuntime, publicationKey);
        }

        public ActivationResult commit() {
            return CatalogRuntimeActivation.this.commit(this);
        }

        private boolean belongsTo(CatalogRuntimeActivation activation) {
            return CatalogRuntimeActivation.this == activation;
        }

        private boolean matchesLiveCandidate(ActivationRecord live) {
            return live != null
                && live.catalog() == candidate
                && live.catalog().generation() == candidate.generation()
                && live.catalog().contentChecksum().equals(candidate.contentChecksum())
                && live.catalog().bindingManifestHash().equals(candidate.bindingManifestHash())
                && live.runtime().generation() == candidateRuntime.generation()
                && live.runtime().bindingManifestHash().equals(candidateRuntime.bindingManifestHash());
        }

        @Override
        public void close() {
            if (!closed) {
                runtimeReplacement.close();
                closed = true;
            }
        }
    }

    public record ActivationRecord(
        CatalogSnapshot catalog,
        RuntimeRegistrySnapshot runtime,
        Optional<CatalogCacheKey> publicationKey
    ) {
        public ActivationRecord {
            catalog = Objects.requireNonNull(catalog, "Catalog Snapshot Is Required");
            runtime = Objects.requireNonNull(runtime, "Runtime Registry Snapshot Is Required");
            publicationKey = publicationKey == null ? Optional.empty() : publicationKey;
            if (!catalog.bindingManifestHash().equals(runtime.bindingManifestHash())) {
                throw new IllegalArgumentException("Catalog And Runtime Manifest Hashes Must Match");
            }
            if (publicationKey.isPresent()) {
                requireCompatiblePublicationKey(catalog, publicationKey.orElseThrow());
            }
        }

        public ActivationRecord(CatalogSnapshot catalog, RuntimeRegistrySnapshot runtime) {
            this(catalog, runtime, Optional.empty());
        }

        public RuntimeBindingManifest runtimeManifest() {
            return runtime.manifest();
        }
    }
}
