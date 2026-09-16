package restudio.resync.flow.cache;

import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CatalogCachePublicationTransport {
    private static final ExecutorService PREPARATION_EXECUTOR = new ThreadPoolExecutor(
        1, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
            Thread thread = new Thread(runnable, "resync-catalog-preparation");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private static final ContractRef<CapabilityId> AUTHORING_PROTOCOL_CAPABILITY = ContractRef.of(
        OwnerId.of("restudio.resync"), CapabilityId.of("catalog_authoring"));
    private final ServerId serverId;
    private final Supplier<CatalogSnapshot> activeSnapshotSupplier;
    private final Supplier<CatalogRuntimeActivation.ActivationRecord> activeActivationSupplier;
    private final CatalogRuntimeActivation activation;
    private final Set<ContractRef<CapabilityId>> supportedCapabilities;
    private final boolean deriveActiveCapabilities;
    private final CatalogProjectionVersion projectionVersion;
    private volatile CatalogCachePublication lastValidPublication;
    private volatile CatalogCacheSnapshot lastValidProjection;
    private volatile ContentHash lastValidBindingManifestHash;
    private volatile CatalogCachePublication pendingPublication;
    private volatile CatalogCacheSnapshot pendingProjection;
    private volatile ContentHash pendingBindingManifestHash;
    private volatile CatalogRuntimeActivation.ActivationRecord pendingActivation;
    private volatile CatalogRuntimeActivation.ActivationRecord lastCommittedActivation;
    private volatile String lastFailureCode = "";
    private volatile String lastAuthoringFailureDetail = "";
    private CatalogRuntimeActivation.ActivationRecord cachedAuthoringActivation;
    private CatalogSnapshot cachedAuthoringSnapshot;
    private Set<ContractRef<CapabilityId>> cachedAuthoringCapabilities = Set.of();
    private Optional<CatalogAuthoringPublication> cachedAuthoringPublication = Optional.empty();
    private boolean authoringCacheValid;
    private AuthoringPrewarm authoringPrewarm;
    private FullDraftCache cachedFullDraft;
    private AsyncPreparationWork asyncPreparation;

    public CatalogCachePublicationTransport(ServerId serverId, Supplier<CatalogSnapshot> activeSnapshotSupplier,
                                            Set<ContractRef<CapabilityId>> supportedCapabilities) {
        this(serverId, activeSnapshotSupplier, supportedCapabilities, CatalogProjectionVersion.current());
    }

    public CatalogCachePublicationTransport(ServerId serverId, Supplier<CatalogSnapshot> activeSnapshotSupplier,
                                            Set<ContractRef<CapabilityId>> supportedCapabilities,
                                            CatalogProjectionVersion projectionVersion) {
        this.serverId = Objects.requireNonNull(serverId, "Server ID is required");
        this.activeSnapshotSupplier = Objects.requireNonNull(activeSnapshotSupplier, "Active catalog snapshot supplier is required");
        this.activeActivationSupplier = null;
        this.activation = null;
        this.supportedCapabilities = supportedCapabilities == null ? Set.of() : Set.copyOf(supportedCapabilities);
        this.deriveActiveCapabilities = false;
        this.projectionVersion = Objects.requireNonNull(projectionVersion, "Catalog projection version is required");
    }

    public CatalogCachePublicationTransport(ServerId serverId, CatalogRuntimeActivation activation) {
        this.serverId = Objects.requireNonNull(serverId, "Server ID is required");
        this.activeSnapshotSupplier = null;
        this.activation = Objects.requireNonNull(activation, "Catalog Runtime Activation Is Required");
        this.activeActivationSupplier = this.activation::active;
        this.supportedCapabilities = Set.of();
        this.deriveActiveCapabilities = true;
        this.projectionVersion = CatalogProjectionVersion.current();
    }

    public CatalogCachePublicationTransport(
        ServerId serverId,
        CatalogRuntimeActivation activation,
        Set<ContractRef<CapabilityId>> supportedCapabilities
    ) {
        this.serverId = Objects.requireNonNull(serverId, "Server ID is required");
        this.activeSnapshotSupplier = null;
        this.activation = Objects.requireNonNull(activation, "Catalog Runtime Activation Is Required");
        this.activeActivationSupplier = this.activation::active;
        this.supportedCapabilities = supportedCapabilities == null ? Set.of() : Set.copyOf(supportedCapabilities);
        this.deriveActiveCapabilities = false;
        this.projectionVersion = CatalogProjectionVersion.current();
    }

    public void prewarmAuthoring() {
        if (activeActivationSupplier == null) {
            return;
        }
        CatalogRuntimeActivation.ActivationRecord currentActivation = currentActivationForPrewarm();
        CatalogSnapshot active = currentActivation == null ? activeSnapshot() : currentActivation.catalog();
        if (active == null) {
            return;
        }
        Set<ContractRef<CapabilityId>> capabilities = capabilitiesFor(active);
        synchronized (this) {
            if (authoringCacheValid && cachedAuthoringActivation == currentActivation
                && cachedAuthoringSnapshot == active && cachedAuthoringCapabilities.equals(capabilities)) {
                return;
            }
            if (authoringPrewarm != null && authoringPrewarm.matches(currentActivation, active, capabilities)) {
                return;
            }
            AuthoringPrewarm previous = authoringPrewarm;
            CompletableFuture<Optional<CatalogAuthoringPublication>> result = new CompletableFuture<>();
            AuthoringPrewarm prewarm = new AuthoringPrewarm(currentActivation, active, capabilities, result,
                previous != null ? previous.readiness() : new CompletableFuture<>());
            authoringPrewarm = prewarm;
            result.whenComplete((publication, failure) -> completeAuthoringPrewarm(prewarm, publication, failure));
            submitPreparation(() -> {
                Optional<CatalogAuthoringPublication> publication = failureSafeAuthoringProjection(active, capabilities);
                result.complete(publication);
            }, () -> rejectAuthoringPrewarm(prewarm));
        }
    }

    public synchronized Optional<CatalogCachePublication> publishFull() {
        return publishFull(null);
    }

    public synchronized Optional<CatalogCachePublication> publishFull(CatalogCacheKey expectedKey) {
        beginOperation();
        Optional<PublicationDraft> draft = buildFull(expectedKey);
        if (draft.isEmpty()) {
            return Optional.empty();
        }
        PublicationDraft value = draft.orElseThrow();
        return remember(value) ? Optional.of(value.publication()) : Optional.empty();
    }

    public synchronized Optional<CatalogCachePublication> prepareFull() {
        return prepareFull(null);
    }

    public synchronized Optional<CatalogCachePublication> prepareFull(CatalogCacheKey expectedKey) {
        beginOperation();
        Optional<PublicationDraft> draft = buildFull(expectedKey);
        if (!stage(draft)) {
            return Optional.empty();
        }
        return draft.map(PublicationDraft::publication);
    }

    public synchronized Optional<CatalogCachePublication> publishDelta(CatalogCacheSnapshot previous,
                                                                         CatalogCacheSnapshot current) {
        beginOperation();
        Optional<PublicationDraft> draft = buildDelta(previous, current);
        if (draft.isEmpty()) {
            return Optional.empty();
        }
        PublicationDraft value = draft.orElseThrow();
        return remember(value) ? Optional.of(value.publication()) : Optional.empty();
    }

    public synchronized Optional<CatalogCachePublication> prepareDelta(CatalogCacheSnapshot previous,
                                                                         CatalogCacheSnapshot current) {
        beginOperation();
        Optional<PublicationDraft> draft = buildDelta(previous, current);
        if (!stage(draft)) {
            return Optional.empty();
        }
        return draft.map(PublicationDraft::publication);
    }

    public synchronized Optional<CatalogCachePublication> publishDelta(CatalogCacheSnapshot current) {
        beginOperation();
        CatalogCacheSnapshot previous = lastValidProjection;
        if (previous == null) {
            fail("CATALOG_PUBLICATION.NO_BASE_PROJECTION");
            return Optional.empty();
        }
        Optional<PublicationDraft> draft = buildDelta(previous, current);
        if (draft.isEmpty()) {
            return Optional.empty();
        }
        PublicationDraft value = draft.orElseThrow();
        return remember(value) ? Optional.of(value.publication()) : Optional.empty();
    }

    public synchronized Optional<CatalogCachePublication> prepareDelta(CatalogCacheSnapshot current) {
        beginOperation();
        CatalogCacheSnapshot previous = lastValidProjection;
        if (previous == null) {
            fail("CATALOG_PUBLICATION.NO_BASE_PROJECTION");
            return Optional.empty();
        }
        Optional<PublicationDraft> draft = buildDelta(previous, current);
        if (!stage(draft)) {
            return Optional.empty();
        }
        return draft.map(PublicationDraft::publication);
    }

    public synchronized Optional<CatalogCachePublication> publishRefresh() {
        beginOperation();
        Optional<PublicationDraft> draft = buildRefresh();
        if (draft.isEmpty()) {
            return Optional.empty();
        }
        PublicationDraft value = draft.orElseThrow();
        return remember(value) ? Optional.of(value.publication()) : Optional.empty();
    }

    public synchronized Optional<CatalogCachePublication> prepareRefresh() {
        beginOperation();
        Optional<PublicationDraft> draft = buildRefresh();
        if (!stage(draft)) {
            return Optional.empty();
        }
        return draft.map(PublicationDraft::publication);
    }

    public CompletableFuture<PreparationResult> prepareFullAsync() {
        return prepareFullAsync(null);
    }

    public CompletableFuture<PreparationResult> prepareFullAsync(CatalogCacheKey expectedKey) {
        synchronized (this) {
            CaptureAttempt attempt = captureFull(expectedKey);
            if (attempt.result() != null) {
                return CompletableFuture.completedFuture(attempt.result());
            }
            return submitPreparation(attempt.capture());
        }
    }

    public CompletableFuture<PreparationResult> prepareRefreshAsync() {
        synchronized (this) {
            CaptureAttempt attempt = captureRefresh();
            if (attempt.result() != null) {
                return CompletableFuture.completedFuture(attempt.result());
            }
            return submitPreparation(attempt.capture());
        }
    }

    public synchronized boolean commitAsyncPreparedPublication(PreparedPublication prepared) {
        if (prepared == null) {
            fail("CATALOG_PUBLICATION.ASYNC_COMMIT_REJECTED");
            return false;
        }
        if (pendingPublication != null || !matchesPreparedCapture(prepared)) {
            fail("CATALOG_PUBLICATION.ASYNC_STALE");
            return false;
        }
        CatalogSnapshot active = activeSnapshot();
        if (active == null || active != prepared.snapshot()) {
            fail("CATALOG_PUBLICATION.ASYNC_STALE");
            return false;
        }
        if (activation != null && !matchesCurrentActivation(prepared.activation())) {
            fail("CATALOG_PUBLICATION.ASYNC_STALE");
            return false;
        }
        PublicationDraft draft = new PublicationDraft(prepared.publication(), prepared.projection(),
            active.bindingManifestHash(), prepared.activation());
        if (!remember(draft)) {
            fail("CATALOG_PUBLICATION.ASYNC_COMMIT_REJECTED");
            return false;
        }
        if (prepared.publication().kind() == CatalogCachePublication.Kind.FULL) {
            cachedFullDraft = new FullDraftCache(lastCommittedActivation, prepared.snapshot(),
                prepared.publicationKey(), prepared.projection().revision(),
                draft.withActivation(lastCommittedActivation));
        }
        return true;
    }

    public synchronized boolean commitPreparedPublication(CatalogCachePublication publication) {
        CatalogSnapshot active = activeSnapshot();
        if (active == null) {
            clearPending();
            return false;
        }
        if (publication == null || pendingPublication == null || pendingProjection == null
            || !pendingPublication.equals(publication)) {
            clearPending();
            fail("CATALOG_PUBLICATION.COMMIT_REJECTED");
            return false;
        }
        if (activation != null && !matchesCurrentActivation(pendingActivation)) {
            clearPending();
            fail("CATALOG_PUBLICATION.KEY_MISMATCH");
            return false;
        }
        if (!matchesPreparedCandidate(publication, active)
            || !Objects.equals(pendingBindingManifestHash, active.bindingManifestHash())
            || !pendingProjection.key().equals(publication.key())
            || pendingProjection.revision() != publication.revision()) {
            clearPending();
            fail("CATALOG_PUBLICATION.KEY_MISMATCH");
            return false;
        }
        if (!remember(new PublicationDraft(publication, pendingProjection, active.bindingManifestHash(), pendingActivation))) {
            clearPending();
            return false;
        }
        clearPending();
        return true;
    }

    public synchronized void discardPreparedPublication(CatalogCachePublication publication) {
        if (publication == null || Objects.equals(pendingPublication, publication)) {
            clearPending();
        }
    }

    public synchronized boolean validatesAgainstActive(CatalogCachePublication publication) {
        CatalogSnapshot active = activeSnapshot();
        if (active == null) {
            fail("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT");
            return false;
        }
        boolean pending = publication != null && pendingPublication != null && pendingProjection != null
            && pendingPublication.equals(publication)
            && matchesCurrentActivation(pendingActivation)
            && Objects.equals(pendingBindingManifestHash, active.bindingManifestHash())
            && pendingProjection.key().equals(publication.key())
            && pendingProjection.revision() == publication.revision()
            && matchesPreparedCandidate(publication, active);
        boolean committed = publication != null && matchesActivePublication(publication, active);
        if (!pending && !committed) {
            fail("CATALOG_PUBLICATION.KEY_MISMATCH");
            return false;
        }
        return true;
    }

    public Optional<CatalogCachePublication> lastValidPublication() {
        return Optional.ofNullable(lastValidPublication);
    }

    public boolean catalogAuthoringAvailable() {
        CatalogSnapshot active = activeSnapshot();
        return active != null && authoringPublication(active).isPresent();
    }

    public CompletableFuture<Boolean> ensureCatalogAuthoringAvailable() {
        prewarmAuthoring();
        synchronized (this) {
            CatalogSnapshot active = activeSnapshot();
            if (active == null) {
                return CompletableFuture.completedFuture(false);
            }
            if (authoringPublication(active).isPresent()) {
                return CompletableFuture.completedFuture(true);
            }
            AuthoringPrewarm pending = authoringPrewarm;
            return pending == null ? CompletableFuture.completedFuture(false) : pending.readiness();
        }
    }

    public Optional<CatalogAuthoringPublication> catalogAuthoringPublication() {
        CatalogSnapshot active = activeSnapshot();
        return active == null ? Optional.empty() : authoringPublication(active);
    }

    public Optional<CatalogAuthoringPublication> catalogAuthoringPublicationFor(CatalogCachePublication publication) {
        if (publication == null) {
            return Optional.empty();
        }
        Optional<CatalogAuthoringPublication> projected = catalogAuthoringPublication();
        if (projected.isEmpty()) {
            return Optional.empty();
        }
        CatalogAuthoringPublication authoring = projected.orElseThrow();
        return publication.catalogBinding() != null
            && publication.catalogBinding().equals(authoring.binding())
            && publication.projectionVersion().equals(authoring.projectionVersion())
            ? Optional.of(authoring) : Optional.empty();
    }

    public CatalogCachePublication publicationForSession(CatalogCachePublication publication,
                                                          Set<ContractRef<CapabilityId>> acknowledgedCapabilities) {
        if (publication == null) {
            return null;
        }
        CatalogSnapshot active = activeSnapshot();
        if (active == null) {
            return publication;
        }
        return authoringPublicationForSession(active, acknowledgedCapabilities)
            .filter(value -> publication.catalogBinding() != null
                && publication.catalogBinding().equals(value.binding())
                && publication.projectionVersion().equals(value.projectionVersion()))
            .map(publication::withAuthoringPublication)
            .orElse(publication);
    }

    public Optional<Map<String, Object>> catalogAuthoringCapability() {
        CatalogSnapshot active = activeSnapshot();
        if (active == null) {
            return Optional.empty();
        }
        Optional<CatalogAuthoringPublication> projected = authoringPublication(active);
        if (projected.isEmpty()) {
            return Optional.empty();
        }
        CatalogAuthoringPublication publication = projected.orElseThrow();
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("capability", "restudio.resync/catalog_authoring");
        capability.put("available", true);
        capability.put("projectionVersion", publication.projectionVersion().canonicalText());
        capability.put("maxPublicationBytes", CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES);
        capability.put("maxChunkBytes", CatalogPublicationChunkPacket.MAX_CHUNK_BYTES);
        return Optional.of(Map.copyOf(capability));
    }

    public Optional<CatalogCacheSnapshot> lastValidProjection() {
        return Optional.ofNullable(lastValidProjection);
    }

    public Optional<String> lastFailureCode() {
        return lastFailureCode.isBlank() ? Optional.empty() : Optional.of(lastFailureCode);
    }

    public Optional<String> lastAuthoringFailureDetail() {
        return lastAuthoringFailureDetail.isBlank() ? Optional.empty() : Optional.of(lastAuthoringFailureDetail);
    }

    public Optional<CatalogCacheKey> activeKey() {
        if (activation != null) {
            return activeActivationSupplier.get().publicationKey();
        }
        CatalogSnapshot active = activeSnapshot();
        if (active == null) {
            return Optional.empty();
        }
        try {
            CatalogCacheSnapshot previous = lastValidProjection;
            return Optional.of(previous != null && sameSemanticIdentity(previous, active)
                ? previous.key() : activeKey(active));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public Optional<CatalogRuntimeActivation.ActivationRecord> activeActivation() {
        if (activation == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(activeActivationSupplier.get());
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT");
            return Optional.empty();
        }
    }

    public synchronized Optional<CatalogRuntimeActivation.ActivationRecord> committedActivationFor(
        CatalogCachePublication publication
    ) {
        if (activation == null || publication == null || lastValidPublication == null
            || !lastValidPublication.equals(publication)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastCommittedActivation);
    }

    public boolean withPublicationFence(
        CatalogCachePublication publication,
        CatalogRuntimeActivation.ActivationRecord expected,
        BooleanSupplier action
    ) {
        Objects.requireNonNull(publication, "Catalog publication is required");
        Objects.requireNonNull(action, "Catalog publication fence action is required");
        if (activation != null) {
            if (expected == null) {
                fail("CATALOG_PUBLICATION.KEY_MISMATCH");
                return false;
            }
            return activation.withPublicationFence(expected, publication.key(), action);
        }
        synchronized (this) {
            CatalogSnapshot active = activeSnapshot();
            if (active == null || !matchesActivePublication(publication, active)) {
                fail("CATALOG_PUBLICATION.KEY_MISMATCH");
                return false;
            }
            return action.getAsBoolean();
        }
    }

    private CaptureAttempt captureFull(CatalogCacheKey expectedKey) {
        CatalogRuntimeActivation.ActivationRecord currentActivation = currentActivationRecord();
        CatalogSnapshot active = activation == null
            ? activeSnapshot()
            : currentActivation == null ? null : currentActivation.catalog();
        if (active == null) {
            return CaptureAttempt.result(PreparationResult.notReady("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT"));
        }
        Set<ContractRef<CapabilityId>> capabilities = Set.copyOf(capabilitiesFor(active));
        Optional<CatalogAuthoringPublication> authoring = readyAuthoring(currentActivation, active, capabilities);
        if (activation != null && authoring.isEmpty()) {
            scheduleAuthoringPrewarm(currentActivation, active, capabilities);
            return CaptureAttempt.result(PreparationResult.notReady("CATALOG_PUBLICATION.AUTHORING_NOT_READY"));
        }
        CatalogCacheKey publicationKey = asyncPublicationKey(currentActivation, active);
        if (expectedKey != null && !expectedKey.equals(publicationKey)) {
            return CaptureAttempt.result(PreparationResult.rejected("CATALOG_PUBLICATION.KEY_MISMATCH"));
        }
        CatalogCacheSnapshot baseline = lastValidProjection;
        long baselineRevision = committedBaselineRevision(publicationKey);
        long revision = nextRevision(baseline, publicationKey, active);
        Optional<PublicationDraft> cached = cachedFullDraft(currentActivation, active, publicationKey,
            baselineRevision);
        if (cached.isPresent() && cached.orElseThrow().publication().authoringPublication() != null) {
            PublicationDraft draft = cached.orElseThrow();
            return CaptureAttempt.result(PreparationResult.ready(new PreparedPublication(draft.publication(),
                draft.projection(), currentActivation, active, publicationKey, baseline, baselineRevision,
                capabilities)));
        }
        return CaptureAttempt.capture(new PreparationCapture(PreparationMode.FULL, currentActivation, active,
            publicationKey, baseline, baselineRevision, revision, capabilities, authoring));
    }

    private CaptureAttempt captureRefresh() {
        CatalogRuntimeActivation.ActivationRecord currentActivation = currentActivationRecord();
        CatalogSnapshot active = activation == null
            ? activeSnapshot()
            : currentActivation == null ? null : currentActivation.catalog();
        if (active == null) {
            return CaptureAttempt.result(PreparationResult.notReady("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT"));
        }
        Set<ContractRef<CapabilityId>> capabilities = Set.copyOf(capabilitiesFor(active));
        Optional<CatalogAuthoringPublication> authoring = readyAuthoring(currentActivation, active, capabilities);
        if (activation != null && authoring.isEmpty()) {
            scheduleAuthoringPrewarm(currentActivation, active, capabilities);
            return CaptureAttempt.result(PreparationResult.notReady("CATALOG_PUBLICATION.AUTHORING_NOT_READY"));
        }
        CatalogCacheKey publicationKey = asyncPublicationKey(currentActivation, active);
        CatalogCacheSnapshot baseline = lastValidProjection;
        long baselineRevision = committedBaselineRevision(publicationKey);
        long revision = nextRevision(baseline, publicationKey, active);
        if ((baseline == null || !baseline.key().equals(publicationKey))) {
            Optional<PublicationDraft> cached = cachedFullDraft(currentActivation, active, publicationKey,
                baselineRevision);
            if (cached.isPresent() && cached.orElseThrow().publication().authoringPublication() != null) {
                PublicationDraft draft = cached.orElseThrow();
                return CaptureAttempt.result(PreparationResult.ready(new PreparedPublication(draft.publication(),
                    draft.projection(), currentActivation, active, publicationKey, baseline, baselineRevision,
                    capabilities)));
            }
        }
        return CaptureAttempt.capture(new PreparationCapture(PreparationMode.REFRESH, currentActivation, active,
            publicationKey, baseline, baselineRevision, revision, capabilities, authoring));
    }

    private CompletableFuture<PreparationResult> submitPreparation(PreparationCapture capture) {
        if (asyncPreparation != null) {
            if (asyncPreparation.matches(capture)) {
                return asyncPreparation.result();
            }
            return CompletableFuture.completedFuture(PreparationResult.notReady(
                "CATALOG_PUBLICATION.PREPARATION_BUSY"));
        }
        CompletableFuture<PreparationResult> result = new CompletableFuture<>();
        AsyncPreparationWork work = new AsyncPreparationWork(capture, result);
        asyncPreparation = work;
        result.whenComplete((ignored, failure) -> {
            synchronized (this) {
                if (asyncPreparation == work) {
                    asyncPreparation = null;
                }
            }
        });
        try {
            PREPARATION_EXECUTOR.execute(() -> {
                try {
                    result.complete(buildAsync(capture));
                } catch (RuntimeException exception) {
                    result.complete(PreparationResult.rejected("CATALOG_PUBLICATION.PROJECTION_REJECTED"));
                }
            });
        } catch (RejectedExecutionException exception) {
            asyncPreparation = null;
            result.complete(PreparationResult.notReady("CATALOG_PUBLICATION.PREPARATION_BUSY"));
        }
        return result;
    }

    private void submitPreparation(Runnable task, Runnable rejected) {
        try {
            PREPARATION_EXECUTOR.execute(task);
        } catch (RejectedExecutionException exception) {
            rejected.run();
        }
    }

    private synchronized void rejectAuthoringPrewarm(AuthoringPrewarm prewarm) {
        if (authoringPrewarm == prewarm) {
            prewarm.result().complete(Optional.empty());
        }
    }

    private PreparationResult buildAsync(PreparationCapture capture) {
        if (!matchesCaptureCurrent(capture, false)) {
            return PreparationResult.rejected("CATALOG_PUBLICATION.ASYNC_STALE");
        }
        Optional<CatalogAuthoringPublication> authoring = capture.authoring();
        if (capture.activation() == null && authoring.isEmpty()) {
            authoring = failureSafeAuthoringProjection(capture.snapshot(), capture.capabilities());
        }
        if (authoring.isEmpty()) {
            return capture.activation() == null
                ? PreparationResult.rejected("CATALOG_PUBLICATION.AUTHORING_UNAVAILABLE")
                : PreparationResult.notReady("CATALOG_PUBLICATION.AUTHORING_NOT_READY");
        }
        PreparedPublication prepared = capture.mode() == PreparationMode.FULL
            ? buildAsyncFull(capture, authoring.orElseThrow())
            : buildAsyncRefresh(capture, authoring.orElseThrow());
        if (prepared == null) {
            return PreparationResult.rejected("CATALOG_PUBLICATION.PROJECTION_REJECTED");
        }
        return matchesCaptureCurrent(capture, capture.activation() != null)
            ? PreparationResult.ready(prepared)
            : PreparationResult.rejected("CATALOG_PUBLICATION.ASYNC_STALE");
    }

    private PreparedPublication buildAsyncFull(
        PreparationCapture capture,
        CatalogAuthoringPublication authoring
    ) {
        CatalogCacheKey publicationKey = capture.publicationKey();
        if (!matchesAuthoringBinding(publicationKey, authoring)) {
            return null;
        }
        try {
            CatalogCacheSnapshot projected = CatalogCacheProjector.project(activeKey(capture.snapshot()),
                capture.revision(), capture.snapshot(), capture.capabilities());
            CatalogCacheSnapshot projection = rekey(projected, publicationKey);
            CatalogCachePublication publication = CatalogCachePublication.full(projection)
                .withAuthoringPublication(authoring);
            if (!matchesSnapshotIdentity(publicationKey, capture.snapshot())
                || !publicationKey.equals(publication.key())) {
                return null;
            }
            return new PreparedPublication(publication, projection, capture.activation(), capture.snapshot(),
                publicationKey, capture.baselineProjection(), capture.baselineRevision(), capture.capabilities());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private PreparedPublication buildAsyncRefresh(
        PreparationCapture capture,
        CatalogAuthoringPublication authoring
    ) {
        CatalogCacheSnapshot previous = capture.baselineProjection();
        CatalogCacheKey publicationKey = capture.publicationKey();
        if (previous == null || !previous.key().equals(publicationKey)) {
            return buildAsyncFull(capture.withMode(PreparationMode.FULL), authoring);
        }
        try {
            CatalogCacheSnapshot projected = CatalogCacheProjector.project(activeKey(capture.snapshot()),
                capture.revision(), capture.snapshot(), capture.capabilities());
            CatalogCacheSnapshot current = rekey(projected, publicationKey);
            CatalogCachePublication publication = CatalogCachePublication.delta(previous, current)
                .withAuthoringPublication(authoring);
            if (!matchesSnapshotIdentity(publicationKey, capture.snapshot())) {
                return null;
            }
            return new PreparedPublication(publication, current, capture.activation(), capture.snapshot(),
                publicationKey, capture.baselineProjection(), capture.baselineRevision(), capture.capabilities());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Optional<CatalogAuthoringPublication> readyAuthoring(
        CatalogRuntimeActivation.ActivationRecord currentActivation,
        CatalogSnapshot active,
        Set<ContractRef<CapabilityId>> capabilities
    ) {
        if (!authoringCacheValid || cachedAuthoringActivation != currentActivation
            || cachedAuthoringSnapshot != active || !cachedAuthoringCapabilities.equals(capabilities)) {
            return Optional.empty();
        }
        return cachedAuthoringPublication;
    }

    private CatalogCacheKey asyncPublicationKey(
        CatalogRuntimeActivation.ActivationRecord currentActivation,
        CatalogSnapshot active
    ) {
        return activation == null
            ? activeKey(active)
            : currentActivation.publicationKey().orElseGet(() -> activeKey(active));
    }

    private long nextRevision(CatalogCacheSnapshot baseline, CatalogCacheKey key, CatalogSnapshot active) {
        if (baseline == null || !baseline.key().equals(key)) {
            return active.generation();
        }
        return Math.max(active.generation(), baseline.revision() + 1);
    }

    private synchronized boolean matchesCaptureCurrent(PreparationCapture capture, boolean requireAuthoring) {
        CatalogRuntimeActivation.ActivationRecord currentActivation = null;
        CatalogSnapshot active;
        try {
            if (activation == null) {
                active = activeSnapshotSupplier.get();
            } else {
                currentActivation = activeActivationSupplier.get();
                active = currentActivation == null ? null : currentActivation.catalog();
            }
        } catch (RuntimeException exception) {
            return false;
        }
        if (active != capture.snapshot() || currentActivation != capture.activation()) {
            return false;
        }
        if (!asyncPublicationKey(currentActivation, active).equals(capture.publicationKey())
            || lastValidProjection != capture.baselineProjection()
            || committedBaselineRevision(capture.publicationKey()) != capture.baselineRevision()
            || !Set.copyOf(capabilitiesFor(active)).equals(capture.capabilities())) {
            return false;
        }
        return !requireAuthoring || readyAuthoring(currentActivation, active, capture.capabilities())
            .filter(value -> capture.authoring().filter(value::equals).isPresent())
            .isPresent();
    }

    private boolean matchesPreparedCapture(PreparedPublication prepared) {
        CatalogRuntimeActivation.ActivationRecord currentActivation = null;
        CatalogSnapshot active;
        try {
            if (activation == null) {
                active = activeSnapshotSupplier.get();
            } else {
                currentActivation = activeActivationSupplier.get();
                active = currentActivation == null ? null : currentActivation.catalog();
            }
        } catch (RuntimeException exception) {
            return false;
        }
        if (active == null || active != prepared.snapshot() || currentActivation != prepared.activation()
            || !prepared.publicationKey().equals(prepared.publication().key())
            || !matchesSnapshotIdentity(prepared.publicationKey(), active)
            || !asyncPublicationKey(currentActivation, active).equals(prepared.publicationKey())
            || lastValidProjection != prepared.baselineProjection()
            || committedBaselineRevision(prepared.publicationKey()) != prepared.baselineRevision()
            || !Set.copyOf(capabilitiesFor(active)).equals(prepared.capabilities())) {
            return false;
        }
        if (prepared.publication().revision() != prepared.projection().revision()
            || !prepared.projection().key().equals(prepared.publicationKey())) {
            return false;
        }
        return activation == null || readyAuthoring(currentActivation, active, prepared.capabilities())
            .filter(value -> prepared.publication().authoringPublication() != null
                && value.equals(prepared.publication().authoringPublication()))
            .isPresent();
    }

    private Optional<PublicationDraft> buildFull(CatalogCacheKey expectedKey) {
        CatalogRuntimeActivation.ActivationRecord currentActivation = currentActivationRecord();
        CatalogSnapshot active = currentActivation == null ? activeSnapshot() : currentActivation.catalog();
        if (active == null) {
            fail("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT");
            return Optional.empty();
        }
        try {
            CatalogCacheKey activeKey = activeKey(active);
            CatalogCacheKey publicationKey = publicationKey(active);
            Optional<CatalogAuthoringPublication> authoring = authoringPublication(active);
            if (authoring.isPresent() && !matchesAuthoringBinding(publicationKey, authoring.orElseThrow())) {
                publicationKey = activeKey;
            }
            if (expectedKey != null && !expectedKey.equals(publicationKey)) {
                fail("CATALOG_PUBLICATION.KEY_MISMATCH");
                return Optional.empty();
            }
            long baselineRevision = committedBaselineRevision(publicationKey);
            Optional<PublicationDraft> cached = cachedFullDraft(currentActivation, active, publicationKey,
                baselineRevision);
            if (cached.isPresent()) {
                return cached;
            }
            long revision = previousRevision(publicationKey, active);
            CatalogCacheSnapshot projected = CatalogCacheProjector.project(activeKey, revision, active,
                capabilitiesFor(active));
            CatalogCacheSnapshot projection = rekey(projected, publicationKey);
            if (!matchesPublicationIdentity(projection.key(), active)) {
                fail("CATALOG_PUBLICATION.KEY_MISMATCH");
                return Optional.empty();
            }
            CatalogCachePublication publication = attachAuthoring(CatalogCachePublication.full(projection), active);
            if (!matchesPublicationIdentity(publication.key(), active)) {
                fail("CATALOG_PUBLICATION.KEY_MISMATCH");
                return Optional.empty();
            }
            PublicationDraft draft = new PublicationDraft(publication, projection, active.bindingManifestHash(), currentActivation);
            cachedFullDraft = new FullDraftCache(currentActivation, active, publicationKey, baselineRevision, draft);
            return Optional.of(draft);
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.PROJECTION_REJECTED");
            return Optional.empty();
        }
    }

    private Optional<PublicationDraft> buildDelta(CatalogCacheSnapshot previous,
                                                   CatalogCacheSnapshot current) {
        CatalogRuntimeActivation.ActivationRecord currentActivation = currentActivationRecord();
        CatalogSnapshot active = currentActivation == null ? activeSnapshot() : currentActivation.catalog();
        if (active == null) {
            fail("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT");
            return Optional.empty();
        }
        if (previous == null || current == null) {
            fail("CATALOG_PUBLICATION.DELTA_REJECTED");
            return Optional.empty();
        }
        if (!matchesActive(current.key(), active)) {
            fail("CATALOG_PUBLICATION.KEY_MISMATCH");
            return Optional.empty();
        }
        if (!matchesActive(previous.key(), active)) {
            fail("CATALOG_PUBLICATION.DELTA_REQUIRES_FULL");
            return Optional.empty();
        }
        try {
            CatalogCachePublication publication = attachAuthoring(CatalogCachePublication.delta(previous, current), active);
            if (!publication.hasAuthoringPublication() && authoringPublication(active).isPresent()) {
                return buildFull(null);
            }
            return Optional.of(new PublicationDraft(publication, current, active.bindingManifestHash(), currentActivation));
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.DELTA_REJECTED");
            return Optional.empty();
        }
    }

    private Optional<PublicationDraft> buildRefresh() {
        CatalogRuntimeActivation.ActivationRecord currentActivation = currentActivationRecord();
        CatalogSnapshot active = currentActivation == null ? activeSnapshot() : currentActivation.catalog();
        if (active == null) {
            fail("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT");
            return Optional.empty();
        }
        CatalogCacheSnapshot previous = lastValidProjection;
        CatalogCacheKey publicationKey = publicationKey(active);
        if (previous == null || !previous.key().equals(publicationKey)) {
            return buildFull(null);
        }
        try {
            CatalogCacheKey activeKey = activeKey(active);
            long revision = Math.max(active.generation(), previous.revision() + 1);
            CatalogCacheSnapshot projected = CatalogCacheProjector.project(activeKey, revision, active, capabilitiesFor(active));
            if (!sameSemanticIdentity(previous, active)) {
                return buildFull(null);
            }
            CatalogCacheSnapshot current = rekey(projected, publicationKey);
            return buildStableDelta(previous, current, active, currentActivation);
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.PROJECTION_REJECTED");
            return Optional.empty();
        }
    }

    private Optional<PublicationDraft> buildStableDelta(CatalogCacheSnapshot previous,
                                                         CatalogCacheSnapshot current,
                                                         CatalogSnapshot active,
                                                         CatalogRuntimeActivation.ActivationRecord currentActivation) {
        if (!sameSemanticIdentity(previous, active) || !sameSemanticIdentity(current, active)) {
            fail("CATALOG_PUBLICATION.DELTA_REQUIRES_FULL");
            return Optional.empty();
        }
        try {
            CatalogCachePublication publication = attachAuthoring(CatalogCachePublication.delta(previous, current), active);
            if (!publication.hasAuthoringPublication() && authoringPublication(active).isPresent()) {
                return buildFull(null);
            }
            return Optional.of(new PublicationDraft(publication, current, active.bindingManifestHash(), currentActivation));
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.DELTA_REJECTED");
            return Optional.empty();
        }
    }

    private CatalogSnapshot activeSnapshot() {
        try {
            if (activeActivationSupplier != null) {
                CatalogRuntimeActivation.ActivationRecord activation = activeActivationSupplier.get();
                return activation == null ? null : activation.catalog();
            }
            return activeSnapshotSupplier.get();
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT");
            return null;
        }
    }

    private boolean remember(PublicationDraft draft) {
        if (!matchesCurrentActivation(draft.activation())) {
            fail("CATALOG_PUBLICATION.KEY_MISMATCH");
            return false;
        }
        if (!publishActivationKey(draft)) {
            return false;
        }
        lastValidProjection = draft.projection();
        lastValidPublication = draft.publication();
        lastValidBindingManifestHash = draft.bindingManifestHash();
        if (draft.publication().kind() == CatalogCachePublication.Kind.FULL
            && cachedFullDraft != null
            && cachedFullDraft.draft().publication().equals(draft.publication())) {
            cachedFullDraft = cachedFullDraft.committed(lastCommittedActivation, draft.projection().revision());
        } else {
            invalidateFullDraftCache();
        }
        lastFailureCode = "";
        return true;
    }

    private void beginOperation() {
        clearPending();
        lastFailureCode = "";
    }

    private boolean stage(Optional<PublicationDraft> draft) {
        if (draft.isEmpty()) {
            clearPending();
            return true;
        }
        PublicationDraft value = draft.orElseThrow();
        pendingPublication = value.publication();
        pendingProjection = value.projection();
        pendingBindingManifestHash = value.bindingManifestHash();
        pendingActivation = value.activation();
        return true;
    }

    private void clearPending() {
        pendingPublication = null;
        pendingProjection = null;
        pendingBindingManifestHash = null;
        pendingActivation = null;
    }

    private void fail(String code) {
        lastFailureCode = code;
    }

    private boolean matchesActive(CatalogCacheKey key, CatalogSnapshot active) {
        boolean identity = serverId.equals(key.serverId())
            && key.snapshotChecksum().equals(active.contentChecksum())
            && key.projectionVersion().equals(projectionVersion);
        if (!identity) {
            return false;
        }
        if (!matchesBinding(key, active)) {
            return false;
        }
        if (activation != null) {
            return activeActivationSupplier.get().publicationKey().map(key::equals)
                .orElse(key.catalogGeneration() == active.generation());
        }
        return key.catalogGeneration() == active.generation();
    }

    private boolean matchesActivePublication(CatalogCachePublication publication, CatalogSnapshot active) {
        if (!matchesPublicationIdentity(publication.key(), active)
            || lastValidPublication == null
            || !lastValidPublication.equals(publication)) {
            return false;
        }
        if (!matchesPublicationBinding(publication, active)) {
            return false;
        }
        return matchesActive(publication.key(), active)
            ? Objects.equals(lastValidBindingManifestHash, active.bindingManifestHash())
            : lastValidProjection != null
                && lastValidProjection.key().equals(publication.key())
                && Objects.equals(lastValidBindingManifestHash, active.bindingManifestHash());
    }

    private boolean matchesPreparedCandidate(CatalogCachePublication publication, CatalogSnapshot active) {
        if (publication == null || active == null) {
            return false;
        }
        CatalogCacheKey key = publication.key();
        CatalogBinding expectedBinding = new CatalogBinding(active.generation(), active.contentChecksum(),
            active.bindingManifestHash());
        return serverId.equals(key.serverId())
            && key.catalogGeneration() == active.generation()
            && key.snapshotChecksum().equals(active.contentChecksum())
            && key.projectionVersion().equals(projectionVersion)
            && expectedBinding.equals(key.catalogBinding())
            && expectedBinding.equals(publication.catalogBinding());
    }

    private boolean matchesSnapshotIdentity(CatalogCacheKey key, CatalogSnapshot active) {
        return serverId.equals(key.serverId())
            && key.catalogGeneration() == active.generation()
            && key.snapshotChecksum().equals(active.contentChecksum())
            && key.projectionVersion().equals(projectionVersion)
            && matchesBinding(key, active);
    }

    private boolean matchesPublicationIdentity(CatalogCacheKey key, CatalogSnapshot active) {
        boolean identity = serverId.equals(key.serverId())
            && key.snapshotChecksum().equals(active.contentChecksum())
            && key.projectionVersion().equals(projectionVersion);
        if (!identity) {
            return false;
        }
        if (!matchesBinding(key, active)) {
            return false;
        }
        if (activation != null) {
            return activeActivationSupplier.get().publicationKey().map(key::equals)
                .orElse(key.catalogGeneration() == active.generation());
        }
        return true;
    }

    private boolean sameSemanticIdentity(CatalogCacheSnapshot previous, CatalogSnapshot active) {
        return matchesPublicationIdentity(previous.key(), active)
            && Objects.equals(lastValidBindingManifestHash, active.bindingManifestHash());
    }

    private CatalogCacheKey activeKey(CatalogSnapshot active) {
        return new CatalogCacheKey(serverId, active.generation(), active.contentChecksum(),
            active.bindingManifestHash(), projectionVersion);
    }

    private boolean matchesBinding(CatalogCacheKey key, CatalogSnapshot active) {
        CatalogCacheKey expected = activeKey(active);
        return projectionVersion.requiresCatalogBinding()
            ? expected.bindingManifestHash().equals(key.bindingManifestHash())
            : key.bindingManifestHash() == null || expected.bindingManifestHash().equals(key.bindingManifestHash());
    }

    private boolean matchesPublicationBinding(CatalogCachePublication publication, CatalogSnapshot active) {
        CatalogBinding expected = new CatalogBinding(active.generation(), active.contentChecksum(), active.bindingManifestHash());
        return projectionVersion.requiresCatalogBinding()
            ? expected.equals(publication.catalogBinding())
            : publication.catalogBinding() == null || expected.equals(publication.catalogBinding());
    }

    private boolean matchesAuthoringBinding(CatalogCacheKey key, CatalogAuthoringPublication authoring) {
        return key != null && key.catalogBinding() != null
            && key.catalogBinding().equals(authoring.binding())
            && key.projectionVersion().equals(authoring.projectionVersion());
    }

    private CatalogCacheKey publicationKey(CatalogSnapshot active) {
        if (activation != null) {
            return activeActivationSupplier.get().publicationKey().orElseGet(() -> activeKey(active));
        }
        CatalogCacheSnapshot previous = lastValidProjection;
        return previous != null && sameSemanticIdentity(previous, active) ? previous.key() : activeKey(active);
    }

    private boolean matchesCurrentActivation(CatalogRuntimeActivation.ActivationRecord expected) {
        if (activation == null) {
            return true;
        }
        if (expected == null) {
            return false;
        }
        try {
            return activeActivationSupplier.get() == expected;
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT");
            return false;
        }
    }

    private boolean publishActivationKey(PublicationDraft draft) {
        if (activation == null) {
            return true;
        }
        try {
            lastCommittedActivation = activation.publishPublicationKey(draft.activation(), draft.publication().key());
            return true;
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.KEY_MISMATCH");
            return false;
        }
    }

    private long previousRevision(CatalogCacheKey key, CatalogSnapshot active) {
        CatalogCacheSnapshot previous = lastValidProjection;
        if (previous == null || !previous.key().equals(key)) {
            return active.generation();
        }
        return Math.max(active.generation(), previous.revision() + 1);
    }

    private long committedBaselineRevision(CatalogCacheKey key) {
        CatalogCacheSnapshot previous = lastValidProjection;
        return previous != null && previous.key().equals(key) ? previous.revision() : -1L;
    }

    private Optional<PublicationDraft> cachedFullDraft(
        CatalogRuntimeActivation.ActivationRecord currentActivation,
        CatalogSnapshot active,
        CatalogCacheKey publicationKey,
        long baselineRevision
    ) {
        FullDraftCache cached = cachedFullDraft;
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.matches(currentActivation, active, publicationKey, baselineRevision)) {
            return Optional.of(cached.draft());
        }
        invalidateFullDraftCache();
        return Optional.empty();
    }

    private void invalidateFullDraftCache() {
        cachedFullDraft = null;
    }

    private CatalogCacheSnapshot rekey(CatalogCacheSnapshot projection, CatalogCacheKey key) {
        return projection.key().equals(key) ? projection : new CatalogCacheSnapshot(key, projection.revision(), projection.entries().values());
    }

    private Optional<CatalogAuthoringPublication> authoringPublication(CatalogSnapshot active) {
        return authoringPublication(active, capabilitiesFor(active));
    }

    private Optional<CatalogAuthoringPublication> authoringPublicationForSession(
        CatalogSnapshot active, Set<ContractRef<CapabilityId>> acknowledgedCapabilities) {
        Set<ContractRef<CapabilityId>> capabilities = acknowledgedCapabilities == null
            || acknowledgedCapabilities.isEmpty()
            || acknowledgedCapabilities.contains(AUTHORING_PROTOCOL_CAPABILITY)
            ? activeCapabilityReferences(active) : acknowledgedCapabilities;
        return authoringPublication(active, capabilities);
    }

    private synchronized Optional<CatalogAuthoringPublication> authoringPublication(
        CatalogSnapshot active, Set<ContractRef<CapabilityId>> acknowledgedCapabilities) {
        Objects.requireNonNull(active, "Active catalog snapshot is required");
        Set<ContractRef<CapabilityId>> requestedCapabilities = acknowledgedCapabilities == null
            ? Set.of() : Set.copyOf(acknowledgedCapabilities);
        CatalogRuntimeActivation.ActivationRecord currentActivation = currentActivationRecord();
        CatalogSnapshot effectiveActive = currentActivation == null ? active : currentActivation.catalog();
        if (authoringCacheValid && cachedAuthoringActivation == currentActivation
            && cachedAuthoringSnapshot == effectiveActive
            && cachedAuthoringCapabilities.equals(requestedCapabilities)) {
            return cachedAuthoringPublication;
        }
        if (activation != null) {
            scheduleAuthoringPrewarm(currentActivation, effectiveActive, requestedCapabilities);
            return Optional.empty();
        }
        Optional<CatalogAuthoringPublication> result = failureSafeAuthoringProjection(effectiveActive, requestedCapabilities);
        cachedAuthoringActivation = currentActivation;
        cachedAuthoringSnapshot = effectiveActive;
        cachedAuthoringCapabilities = requestedCapabilities;
        cachedAuthoringPublication = result;
        authoringCacheValid = true;
        return result;
    }

    private CatalogRuntimeActivation.ActivationRecord currentActivationRecord() {
        if (activeActivationSupplier == null) {
            return null;
        }
        try {
            CatalogRuntimeActivation.ActivationRecord current = activeActivationSupplier.get();
            if (authoringCacheValid && cachedAuthoringActivation != current) {
                if (isMetadataOnlyActivationTransition(cachedAuthoringActivation, current)) {
                    cachedAuthoringActivation = current;
                } else {
                    invalidateAuthoringCache();
                }
            }
            if (cachedFullDraft != null && cachedFullDraft.activation() != current) {
                invalidateFullDraftCache();
            }
            return current;
        } catch (RuntimeException exception) {
            invalidateAuthoringCache();
            invalidateFullDraftCache();
            fail("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT");
            return null;
        }
    }

    private boolean isMetadataOnlyActivationTransition(
        CatalogRuntimeActivation.ActivationRecord previous,
        CatalogRuntimeActivation.ActivationRecord current
    ) {
        return previous != null && current != null
            && previous.catalog() == current.catalog()
            && previous.runtime() == current.runtime()
            && previous.publicationKey().isEmpty()
            && current.publicationKey().isPresent()
            && cachedAuthoringSnapshot == current.catalog();
    }

    private void invalidateAuthoringCache() {
        AuthoringPrewarm pending = authoringPrewarm;
        cachedAuthoringActivation = null;
        cachedAuthoringSnapshot = null;
        cachedAuthoringCapabilities = Set.of();
        cachedAuthoringPublication = Optional.empty();
        authoringCacheValid = false;
        authoringPrewarm = null;
        if (pending != null) {
            pending.readiness().complete(false);
        }
    }

    private CatalogRuntimeActivation.ActivationRecord currentActivationForPrewarm() {
        if (activeActivationSupplier == null) {
            return null;
        }
        try {
            return activeActivationSupplier.get();
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT");
            return null;
        }
    }

    private void scheduleAuthoringPrewarm(
        CatalogRuntimeActivation.ActivationRecord currentActivation,
        CatalogSnapshot active,
        Set<ContractRef<CapabilityId>> capabilities
    ) {
        if (authoringPrewarm != null && authoringPrewarm.matches(currentActivation, active, capabilities)) {
            return;
        }
        AuthoringPrewarm previous = authoringPrewarm;
        CompletableFuture<Optional<CatalogAuthoringPublication>> result = new CompletableFuture<>();
        AuthoringPrewarm prewarm = new AuthoringPrewarm(currentActivation, active, capabilities, result,
            previous != null ? previous.readiness() : new CompletableFuture<>());
        authoringPrewarm = prewarm;
        result.whenComplete((publication, failure) -> completeAuthoringPrewarm(prewarm, publication, failure));
        submitPreparation(() -> {
            Optional<CatalogAuthoringPublication> publication = failureSafeAuthoringProjection(active, capabilities);
            result.complete(publication);
        }, () -> rejectAuthoringPrewarm(prewarm));
    }

    private void completeAuthoringPrewarm(
        AuthoringPrewarm prewarm,
        Optional<CatalogAuthoringPublication> publication,
        Throwable failure
    ) {
        boolean available;
        synchronized (this) {
            if (authoringPrewarm != prewarm) {
                return;
            }
            cachedAuthoringActivation = prewarm.activation();
            cachedAuthoringSnapshot = prewarm.snapshot();
            cachedAuthoringCapabilities = prewarm.capabilities();
            cachedAuthoringPublication = failure == null && publication != null ? publication : Optional.empty();
            authoringCacheValid = true;
            invalidateFullDraftCache();
            authoringPrewarm = null;
            available = cachedAuthoringPublication.isPresent();
        }
        prewarm.readiness().complete(available);
    }

    private Optional<CatalogAuthoringPublication> failureSafeAuthoringProjection(
        CatalogSnapshot active,
        Set<ContractRef<CapabilityId>> requestedCapabilities
    ) {
        try {
            CatalogBinding binding = new CatalogBinding(active.generation(), active.contentChecksum(),
                active.bindingManifestHash());
            Set<ContractRef<CapabilityId>> available = activeCapabilityReferences(active);
            Set<ContractRef<CapabilityId>> capabilities = requestedCapabilities.stream()
                .filter(available::contains)
                .collect(Collectors.toUnmodifiableSet());
            CatalogAuthoringPublication publication = CatalogCacheProjector.projectAuthoring(binding, active,
                capabilities, EnumSet.allOf(CatalogAuthoringPublication.Section.class), projectionVersion);
            if (!publication.compatible()) {
                lastAuthoringFailureDetail = "incompatible;generation=" + active.generation()
                    + ";definitions=" + active.definitions().size()
                    + ";capabilities=" + capabilities.size();
                return Optional.empty();
            }
            lastAuthoringFailureDetail = "";
            return Optional.of(publication);
        } catch (RuntimeException exception) {
            lastAuthoringFailureDetail = exception.getClass().getName() + ":" + String.valueOf(exception.getMessage());
            return Optional.empty();
        }
    }

    private Set<ContractRef<CapabilityId>> activeCapabilityReferences(CatalogSnapshot active) {
        return active.capabilities().stream()
            .map(CatalogOwned::key)
            .map(value -> ContractRef.of(value.owner(), CapabilityId.of(value.id().canonicalText())))
            .collect(Collectors.toUnmodifiableSet());
    }

    private Set<ContractRef<CapabilityId>> capabilitiesFor(CatalogSnapshot active) {
        return deriveActiveCapabilities ? activeCapabilityReferences(active) : supportedCapabilities;
    }

    private CatalogCachePublication attachAuthoring(CatalogCachePublication publication, CatalogSnapshot active) {
        return authoringPublication(active)
            .filter(value -> publication.catalogBinding() != null
                && publication.catalogBinding().equals(value.binding())
                && publication.projectionVersion().equals(value.projectionVersion()))
            .map(publication::withAuthoringPublication)
            .orElse(publication);
    }

    private record PublicationDraft(CatalogCachePublication publication,
                                    CatalogCacheSnapshot projection,
                                    ContentHash bindingManifestHash,
                                    CatalogRuntimeActivation.ActivationRecord activation) {
        private PublicationDraft withActivation(CatalogRuntimeActivation.ActivationRecord nextActivation) {
            return new PublicationDraft(publication, projection, bindingManifestHash, nextActivation);
        }
    }

    private record FullDraftCache(
        CatalogRuntimeActivation.ActivationRecord activation,
        CatalogSnapshot snapshot,
        CatalogCacheKey publicationKey,
        long baselineRevision,
        PublicationDraft draft
    ) {
        private FullDraftCache {
            Objects.requireNonNull(snapshot, "Full draft snapshot is required");
            Objects.requireNonNull(publicationKey, "Full draft publication key is required");
            Objects.requireNonNull(draft, "Full draft is required");
        }

        private boolean matches(
            CatalogRuntimeActivation.ActivationRecord currentActivation,
            CatalogSnapshot currentSnapshot,
            CatalogCacheKey currentPublicationKey,
            long currentBaselineRevision
        ) {
            return activation == currentActivation
                && snapshot == currentSnapshot
                && publicationKey.equals(currentPublicationKey)
                && baselineRevision == currentBaselineRevision;
        }

        private FullDraftCache committed(
            CatalogRuntimeActivation.ActivationRecord committedActivation,
            long committedRevision
        ) {
            return new FullDraftCache(committedActivation, snapshot, publicationKey, committedRevision,
                draft.withActivation(committedActivation));
        }
    }

    private record AuthoringPrewarm(
        CatalogRuntimeActivation.ActivationRecord activation,
        CatalogSnapshot snapshot,
        Set<ContractRef<CapabilityId>> capabilities,
        CompletableFuture<Optional<CatalogAuthoringPublication>> result,
        CompletableFuture<Boolean> readiness
    ) {
        private AuthoringPrewarm {
            capabilities = Set.copyOf(capabilities);
            result = Objects.requireNonNull(result, "authoring prewarm result");
            readiness = Objects.requireNonNull(readiness, "authoring prewarm readiness");
        }

        private boolean matches(
            CatalogRuntimeActivation.ActivationRecord currentActivation,
            CatalogSnapshot currentSnapshot,
            Set<ContractRef<CapabilityId>> currentCapabilities
        ) {
            return activation == currentActivation && snapshot == currentSnapshot && capabilities.equals(currentCapabilities);
        }
    }

    public enum PreparationStatus {
        READY,
        NOT_READY,
        REJECTED
    }

    public record PreparationResult(
        PreparationStatus status,
        String code,
        PreparedPublication prepared
    ) {
        public PreparationResult {
            status = Objects.requireNonNull(status, "Preparation status is required");
            code = code == null ? "" : code;
            if (status == PreparationStatus.READY && prepared == null) {
                throw new IllegalArgumentException("Ready catalog preparations require a publication");
            }
            if (status != PreparationStatus.READY && prepared != null) {
                throw new IllegalArgumentException("Rejected catalog preparations cannot carry a publication");
            }
            if (status != PreparationStatus.READY && code.isBlank()) {
                throw new IllegalArgumentException("Rejected catalog preparations require a code");
            }
        }

        public static PreparationResult ready(PreparedPublication prepared) {
            return new PreparationResult(PreparationStatus.READY, "", Objects.requireNonNull(prepared, "prepared"));
        }

        public static PreparationResult notReady(String code) {
            return new PreparationResult(PreparationStatus.NOT_READY, code, null);
        }

        public static PreparationResult rejected(String code) {
            return new PreparationResult(PreparationStatus.REJECTED, code, null);
        }

        public boolean ready() {
            return status == PreparationStatus.READY;
        }

        public boolean retryable() {
            return status == PreparationStatus.NOT_READY;
        }
    }

    public record PreparedPublication(
        CatalogCachePublication publication,
        CatalogCacheSnapshot projection,
        CatalogRuntimeActivation.ActivationRecord activation,
        CatalogSnapshot snapshot,
        CatalogCacheKey publicationKey,
        CatalogCacheSnapshot baselineProjection,
        long baselineRevision,
        Set<ContractRef<CapabilityId>> capabilities
    ) {
        public PreparedPublication {
            publication = Objects.requireNonNull(publication, "prepared publication");
            projection = Objects.requireNonNull(projection, "prepared projection");
            snapshot = Objects.requireNonNull(snapshot, "prepared snapshot");
            publicationKey = Objects.requireNonNull(publicationKey, "prepared publication key");
            if (!publicationKey.equals(publication.key()) || !publicationKey.equals(projection.key())) {
                throw new IllegalArgumentException("Prepared publication identity does not match its projection");
            }
            if (publication.authoringPublication() == null) {
                throw new IllegalArgumentException("Prepared catalog publications require authoring data");
            }
            if (publication.revision() != projection.revision()) {
                throw new IllegalArgumentException("Prepared publication revision does not match its projection");
            }
            if (baselineRevision < -1) {
                throw new IllegalArgumentException("Prepared publication baseline revision is invalid");
            }
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "prepared capabilities"));
        }
    }

    private enum PreparationMode {
        FULL,
        REFRESH
    }

    private record PreparationCapture(
        PreparationMode mode,
        CatalogRuntimeActivation.ActivationRecord activation,
        CatalogSnapshot snapshot,
        CatalogCacheKey publicationKey,
        CatalogCacheSnapshot baselineProjection,
        long baselineRevision,
        long revision,
        Set<ContractRef<CapabilityId>> capabilities,
        Optional<CatalogAuthoringPublication> authoring
    ) {
        private PreparationCapture {
            mode = Objects.requireNonNull(mode, "preparation mode");
            snapshot = Objects.requireNonNull(snapshot, "preparation snapshot");
            publicationKey = Objects.requireNonNull(publicationKey, "preparation publication key");
            if (baselineRevision < -1 || revision < 0) {
                throw new IllegalArgumentException("Preparation revisions are invalid");
            }
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "preparation capabilities"));
            authoring = Objects.requireNonNull(authoring, "preparation authoring");
        }

        private PreparationCapture withMode(PreparationMode nextMode) {
            return new PreparationCapture(nextMode, activation, snapshot, publicationKey, baselineProjection,
                baselineRevision, revision, capabilities, authoring);
        }
    }

    private record CaptureAttempt(PreparationCapture capture, PreparationResult result) {
        private CaptureAttempt {
            if ((capture == null) == (result == null)) {
                throw new IllegalArgumentException("A capture attempt must contain exactly one outcome");
            }
        }

        private static CaptureAttempt capture(PreparationCapture capture) {
            return new CaptureAttempt(Objects.requireNonNull(capture, "capture"), null);
        }

        private static CaptureAttempt result(PreparationResult result) {
            return new CaptureAttempt(null, Objects.requireNonNull(result, "result"));
        }
    }

    private record AsyncPreparationWork(
        PreparationCapture capture,
        CompletableFuture<PreparationResult> result
    ) {
        private boolean matches(PreparationCapture other) {
            return capture.mode() == other.mode()
                && capture.activation() == other.activation()
                && capture.snapshot() == other.snapshot()
                && capture.publicationKey().equals(other.publicationKey())
                && capture.baselineProjection() == other.baselineProjection()
                && capture.baselineRevision() == other.baselineRevision()
                && capture.revision() == other.revision()
                && capture.capabilities().equals(other.capabilities())
                && capture.authoring().equals(other.authoring());
        }
    }
}
