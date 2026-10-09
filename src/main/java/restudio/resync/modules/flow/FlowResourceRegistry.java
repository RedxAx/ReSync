package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.server.CoreGraphResourceAuthority;
import restudio.resync.server.AggregateResourceCreateStorage;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class FlowResourceRegistry implements AggregateResourceCreateStorage {
    private static final int MAX_AUDIT_RECORDS = 2048;
    private static final List<String> STANDARD_OPERATIONS = List.of("discover", "query", "get", "create", "validate", "save", "update", "rename", "move", "duplicate", "activate", "delete", "subscribe", "reload", "apply");
    private static final Set<String> CORE_GRAPH_TYPES = Set.of("flow", "function", "command");
    private static final OwnerId CORE_GRAPH_OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> CORE_PROTOCOL_OPERATIONS = Set.of(
        "discover", "query", "get", "load", "list", "state", "create", "save", "delete", "activate", "activation");
    private static final Gson GSON = new Gson();
    private final Map<String, ResourceRegistration> registrations = new ConcurrentHashMap<>();
    private final Deque<FlowResourceAuditRecord> auditRecords = new ArrayDeque<>();
    private final FlowResourceMutationAdmission mutationAdmission;
    private final Map<FlowResourceKey, SessionSaveState> pendingSessionLeases;
    private final Object pendingSessionMonitor;
    private final Set<Session> closedSessionSaves;
    private final AtomicBoolean allSessionSavesClosed;
    private final Set<FlowResourceMutationLease.DeferredCompletion> pendingRefreshes;
    private final Map<FlowResourceKey, Set<FlowResourceMutationLease.DeferredCompletion>> pendingRefreshesByKey;
    private final Map<FlowResourceMutationLease.DeferredCompletion, TrackedMutationCompletion> pendingDurableCompletions;
    private final Map<FlowResourceKey, Set<TrackedMutationCompletion>> pendingDurableCompletionsByKey;
    private final ThreadLocal<FlowResourceMutationLease> currentMutationLease;
    private final AtomicReference<CoreGraphResourceAuthority> coreGraphAuthority;
    private AtomicReference<Supplier<NetworkCoreMutations>> networkCoreMutations = new AtomicReference<>();
    private final CoreResourceMutationBus coreMutationBus;
    private Consumer<String> changeListener = ignored -> {
    };
    private Consumer<Runnable> liveRefreshExecutor = Runnable::run;
    private FlowResourceMutationListener mutationListener = FlowResourceMutationListener.NONE;
    private FlowResourceCommitListener commitListener = FlowResourceCommitListener.NONE;
    private FlowWorkspaceService workspaces;
    private volatile ExtensionRegistryActivation activation;
    private volatile String genericMutationAuthorityReason = "";
    private FlowResourceAuthorizationPolicy authorizationPolicy = (context, operation, resourceType, resourceId) ->
        "system".equals(context.source()) || "flow".equals(context.source()) || "protocol".equals(context.source());

    public FlowResourceRegistry() {
        this(new FlowResourceMutationAdmission(), new ConcurrentHashMap<>(), new Object(),
            Collections.newSetFromMap(new WeakHashMap<>()), new AtomicBoolean(), ConcurrentHashMap.newKeySet(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ThreadLocal<>(),
            new AtomicReference<>(), new CoreResourceMutationBus());
    }

    FlowResourceRegistry(FlowResourceMutationAdmission mutationAdmission) {
        this(mutationAdmission, new ConcurrentHashMap<>(), new Object(),
            Collections.newSetFromMap(new WeakHashMap<>()), new AtomicBoolean(), ConcurrentHashMap.newKeySet(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ThreadLocal<>(),
            new AtomicReference<>(), new CoreResourceMutationBus());
    }

    private FlowResourceRegistry(FlowResourceMutationAdmission mutationAdmission,
                                 Map<FlowResourceKey, SessionSaveState> pendingSessionLeases,
                                 Object pendingSessionMonitor,
                                 Set<Session> closedSessionSaves,
                                 AtomicBoolean allSessionSavesClosed,
                                 Set<FlowResourceMutationLease.DeferredCompletion> pendingRefreshes,
                                 Map<FlowResourceKey, Set<FlowResourceMutationLease.DeferredCompletion>> pendingRefreshesByKey,
                                 Map<FlowResourceMutationLease.DeferredCompletion, TrackedMutationCompletion> pendingDurableCompletions,
                                 Map<FlowResourceKey, Set<TrackedMutationCompletion>> pendingDurableCompletionsByKey,
                                 ThreadLocal<FlowResourceMutationLease> currentMutationLease,
                                 AtomicReference<CoreGraphResourceAuthority> coreGraphAuthority,
                                 CoreResourceMutationBus coreMutationBus) {
        this.mutationAdmission = mutationAdmission != null ? mutationAdmission : new FlowResourceMutationAdmission();
        this.pendingSessionLeases = pendingSessionLeases != null ? pendingSessionLeases : new ConcurrentHashMap<>();
        this.pendingSessionMonitor = pendingSessionMonitor != null ? pendingSessionMonitor : new Object();
        this.closedSessionSaves = closedSessionSaves != null ? closedSessionSaves : Collections.newSetFromMap(new WeakHashMap<>());
        this.allSessionSavesClosed = allSessionSavesClosed != null ? allSessionSavesClosed : new AtomicBoolean();
        this.pendingRefreshes = pendingRefreshes != null ? pendingRefreshes : ConcurrentHashMap.newKeySet();
        this.pendingRefreshesByKey = pendingRefreshesByKey != null ? pendingRefreshesByKey : new ConcurrentHashMap<>();
        this.pendingDurableCompletions = pendingDurableCompletions != null ? pendingDurableCompletions : new ConcurrentHashMap<>();
        this.pendingDurableCompletionsByKey = pendingDurableCompletionsByKey != null
            ? pendingDurableCompletionsByKey : new ConcurrentHashMap<>();
        this.currentMutationLease = currentMutationLease != null ? currentMutationLease : new ThreadLocal<>();
        this.coreGraphAuthority = coreGraphAuthority != null ? coreGraphAuthority : new AtomicReference<>();
        this.coreMutationBus = coreMutationBus != null ? coreMutationBus : new CoreResourceMutationBus();
    }

    public FlowResourceMutationAdmission mutationAdmission() {
        return mutationAdmission;
    }

    public FlowResourceMutationAdmission resourceMutationAdmission() {
        return mutationAdmission;
    }

    public Optional<FlowResourceMutationLease> tryAcquireMutation(FlowResourceKey key) {
        return mutationAdmission.tryAcquire(key);
    }

    public FlowResourceMutationLease acquireMutation(FlowResourceKey key) {
        return mutationAdmission.acquire(key);
    }

    public void bindActivation(ExtensionRegistryActivation activation) {
        this.activation = activation;
    }

    public void bindCoreGraphResourceAuthority(CoreGraphResourceAuthority authority) {
        if (authority == null) {
            throw new IllegalArgumentException("Core graph resource authority is required");
        }
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowResourceRegistry.class, target -> {
                target.bindCoreGraphResourceAuthorityLocal(authority);
                return null;
            });
            return;
        }
        bindCoreGraphResourceAuthorityLocal(authority);
    }

    private void bindCoreGraphResourceAuthorityLocal(CoreGraphResourceAuthority authority) {
        if (!coreGraphAuthority.compareAndSet(null, authority)) {
            throw new IllegalStateException("Core graph resource authority is already bound");
        }
    }

    public void bindCoreGraphAuthority(CoreGraphResourceAuthority authority) {
        bindCoreGraphResourceAuthority(authority);
    }

    public interface NetworkCoreMutations {
        void save(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded source);

        void delete(ServerResourceLocator resource);
    }

    public void bindNetworkCoreMutations(Supplier<NetworkCoreMutations> mutations) {
        if (!networkCoreMutations.compareAndSet(null, Objects.requireNonNull(mutations, "Network Core mutation authority is required"))) {
            throw new IllegalStateException("Network Core mutation authority is already bound");
        }
    }

    public void saveNetworkCoreGraph(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded source) {
        requireNetworkCoreMutations().save(resource, source);
    }

    public void deleteNetworkCoreGraph(ServerResourceLocator resource) {
        requireNetworkCoreMutations().delete(resource);
    }

    private NetworkCoreMutations requireNetworkCoreMutations() {
        Supplier<NetworkCoreMutations> mutations = networkCoreMutations.get();
        if (mutations == null) {
            throw new IllegalStateException("Durable network Core mutation authority is unavailable");
        }
        return Objects.requireNonNull(mutations.get(), "Durable network Core mutation authority is unavailable");
    }

    public CoreGraphResourceAuthority coreGraphResourceAuthority() {
        FlowResourceRegistry activeRegistry = activeRegistry();
        return activeRegistry != this ? activeRegistry.coreGraphAuthority.get() : coreGraphAuthority.get();
    }

    public CoreGraphResourceAuthority coreGraphAuthority() {
        return coreGraphResourceAuthority();
    }

    public boolean coreGraphResourceAuthorityAvailable() {
        CoreGraphResourceAuthority authority = coreGraphResourceAuthority();
        return authority != null && authority.available();
    }

    private boolean coreGraphAggregateCreateAvailable() {
        CoreGraphResourceAuthority authority = coreGraphResourceAuthority();
        return authority != null && authority.available() && authority.supportsAggregateCreate();
    }

    @Override
    public boolean available() {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.available();
        }
        long started = TemporaryLifecycleDiagnostics.start();
        boolean projectMetadataRegistered = registration("project_metadata") != null;
        boolean coreAuthorityBound = false;
        boolean coreAuthorityAvailable = false;
        boolean coreAggregateCreateSupported = false;
        boolean aggregateCreateSupported = false;
        boolean available = false;
        if (projectMetadataRegistered) {
            CoreGraphResourceAuthority authority = coreGraphResourceAuthority();
            coreAuthorityBound = authority != null;
            if (coreAuthorityBound) {
                coreAuthorityAvailable = authority.available();
                if (coreAuthorityAvailable) {
                    coreAggregateCreateSupported = authority.supportsAggregateCreate();
                }
            }
            aggregateCreateSupported = coreAggregateCreateSupported;
            if (!aggregateCreateSupported) {
                aggregateCreateSupported = registrations.values().stream()
                    .anyMatch(registration -> registration.adapter().supportsAggregateCreate());
            }
            available = aggregateCreateSupported;
        }
        TemporaryLifecycleDiagnostics.event("aggregate_availability_preflight", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null, "aggregate", null,
                null, null, null, null, null), "projectMetadataRegistered", projectMetadataRegistered,
                "adapterRegistered", !registrations.isEmpty(), "coreAuthorityBound", coreAuthorityBound,
                "coreAuthorityAvailable", coreAuthorityAvailable, "coreAggregateCreateSupported",
                coreAggregateCreateSupported, "aggregateCreateSupported", aggregateCreateSupported,
                "outcome", available ? "available" : "unavailable"));
        return available;
    }

    @Override
    public boolean available(ServerResourceLocator resource) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.available(resource);
        }
        long started = TemporaryLifecycleDiagnostics.start();
        boolean projectMetadataRegistered = false;
        boolean adapterRegistered = false;
        boolean createSupported = false;
        boolean aggregateCreateSupported = false;
        boolean coreAuthorityBound = false;
        boolean coreAuthorityAvailable = false;
        boolean coreAggregateCreateSupported = false;
        boolean available = false;
        if (resource == null) {
            TemporaryLifecycleDiagnostics.event("aggregate_availability_preflight", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null, null, null,
                    null, null, null, null, null), "outcome", "invalid_resource"));
            return false;
        }
        projectMetadataRegistered = registration("project_metadata") != null;
        if (projectMetadataRegistered) {
            if (isCoreGraphResource(resource)) {
                CoreGraphResourceAuthority authority = coreGraphResourceAuthority();
                coreAuthorityBound = authority != null;
                if (coreAuthorityBound) {
                    coreAuthorityAvailable = authority.available();
                    if (coreAuthorityAvailable) {
                        coreAggregateCreateSupported = authority.supportsAggregateCreate();
                    }
                }
                aggregateCreateSupported = coreAggregateCreateSupported;
                available = aggregateCreateSupported;
            } else {
                ResourceRegistration registration = registration(resource.resourceType().value());
                adapterRegistered = registration != null;
                if (adapterRegistered) {
                    createSupported = registration.adapter().supportedOperations().contains("create");
                    if (createSupported) {
                        aggregateCreateSupported = registration.adapter().supportsAggregateCreate();
                    }
                    available = aggregateCreateSupported;
                }
            }
        }
        TemporaryLifecycleDiagnostics.event("aggregate_availability_preflight", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(resource.serverId(),
                resource.resourceType().value() + ':' + resource.id(), null, null, null, null, null, null),
                "projectMetadataRegistered", projectMetadataRegistered, "adapterRegistered", adapterRegistered,
                "createSupported", createSupported, "aggregateCreateSupported", aggregateCreateSupported,
                "coreAuthorityBound", coreAuthorityBound, "coreAuthorityAvailable", coreAuthorityAvailable,
                "coreAggregateCreateSupported", coreAggregateCreateSupported,
                "outcome", available ? "available" : "unavailable"));
        return available;
    }

    @Override
    public AggregateResourceCreateStorage.Result create(ServerResourceLocator resource, Object value,
                                                        FlowResourceMutationContext context,
                                                        ResourcePresentationIntent presentation) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.create(resource, value, context, presentation);
        }
        Objects.requireNonNull(resource, "Aggregate create resource is required");
        Objects.requireNonNull(value, "Aggregate create value is required");
        Objects.requireNonNull(presentation, "Aggregate create presentation is required");
        String type = resource.resourceType().value();
        FlowResourceKey primaryKey = new FlowResourceKey(type, resource.id());
        FlowResourceKey metadataKey = new FlowResourceKey("project_metadata", resource.serverId().canonicalText());
        if (context == null || context.expectedRevision() < 0L
            || !validExactContext(context, List.of(primaryKey, metadataKey))) {
            throw new IllegalStateException("Aggregate create requires an exact dual-resource mutation context");
        }
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> identity = TemporaryLifecycleDiagnostics.identity(resource.serverId(),
            type + ':' + resource.id(), context.exactMutationId(), null, null,
            context.expectedRevision(), null, null);
        try {
            AggregateResourceCreateStorage.Result result = isCoreGraphResource(resource)
                ? createCoreAggregate(resource, value, context, presentation)
                : createAdapterAggregate(resource, value, context, presentation);
            requireAggregateResult(resource, context, result);
            TemporaryLifecycleDiagnostics.event("aggregate_resource_create", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "committed",
                    "primaryRevision", result.primary().stamp().revision(),
                    "metadataRevision", result.projectMetadata().stamp().revision()));
            return result;
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("aggregate_resource_create", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed",
                    "failure", exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    @Override
    public void publishCommitted(ServerResourceLocator resource, UUID mutationId) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            activeRegistry.publishCommitted(resource, mutationId);
            return;
        }
        Objects.requireNonNull(resource, "Aggregate create resource is required");
        Objects.requireNonNull(mutationId, "Aggregate create mutation ID is required");
        if (isCoreGraphResource(resource)) {
            long started = TemporaryLifecycleDiagnostics.start();
            requireCoreGraphAuthority(resource, "publish committed create").publishCommitted(resource, mutationId);
            TemporaryLifecycleDiagnostics.event("aggregate_create_primary_publication", started,
                TemporaryLifecycleDiagnostics.identity(resource.serverId(), resource.resourceType().value() + ':' + resource.id(),
                    mutationId, null, null, null, null, null));
            publishAggregateProjectMetadata(resource, mutationId);
            return;
        }
        ResourceRegistration registration = registration(resource.resourceType().value());
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null || !rawAdapter.supportsAggregateCreate()) {
            throw new IllegalStateException("Aggregate create resource adapter is unavailable");
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        Object current = adapter.get(resource.id());
        if (current == null) {
            RefreshOutcome refresh = refreshAfterDelete(adapter, resource.id());
            if (!refresh.succeeded()) {
                throw new IllegalStateException("Aggregate create delete publication failed: " + refresh.error());
            }
            RuntimeException publicationFailure = publishSessionDeleted(null,
                resource.resourceType().value(), resource.id());
            if (publicationFailure != null) {
                throw publicationFailure;
            }
            publishAggregateProjectMetadata(resource, mutationId);
            return;
        }
        if (!resource.id().equals(adapter.id(current))) {
            throw new IllegalStateException("Aggregate create publication loaded a different resource identity");
        }
        RefreshOutcome refresh = refreshAfterSave(adapter, current);
        if (!refresh.succeeded()) {
            throw new IllegalStateException("Aggregate create publication failed: " + refresh.error());
        }
        RuntimeException publicationFailure = publishSessionSaved(null, adapter, current);
        if (publicationFailure != null) {
            throw publicationFailure;
        }
        publishAggregateProjectMetadata(resource, mutationId);
    }

    private void publishAggregateProjectMetadata(ServerResourceLocator resource, UUID mutationId) {
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> identity = TemporaryLifecycleDiagnostics.identity(resource.serverId(),
            "project_metadata:" + resource.serverId().canonicalText(), mutationId, null, null, null, null, null);
        ResourceRegistration registration = registration("project_metadata");
        if (registration == null) {
            throw new IllegalStateException("Project metadata adapter is unavailable during aggregate create publication");
        }
        FlowResourceAdapter<Object> adapter = adapter(registration.adapter());
        Object current = adapter.get(resource.serverId().canonicalText());
        TemporaryLifecycleDiagnostics.event("aggregate_create_metadata_read", started, identity);
        if (current == null || !resource.serverId().canonicalText().equals(adapter.id(current))) {
            throw new IllegalStateException("Project metadata is unavailable during aggregate create publication");
        }
        long refreshStarted = TemporaryLifecycleDiagnostics.start();
        RefreshOutcome refresh = refreshAfterSave(adapter, current);
        TemporaryLifecycleDiagnostics.event("aggregate_create_metadata_refresh", refreshStarted,
            TemporaryLifecycleDiagnostics.with(identity, "outcome", refresh.succeeded() ? "refreshed" : "failed"));
        if (!refresh.succeeded()) {
            throw new IllegalStateException("Aggregate create project metadata publication failed: " + refresh.error());
        }
        long publicationStarted = TemporaryLifecycleDiagnostics.start();
        RuntimeException publicationFailure = publishSessionSaved(null, adapter, current);
        TemporaryLifecycleDiagnostics.event("aggregate_create_metadata_publication", publicationStarted,
            TemporaryLifecycleDiagnostics.with(identity, "outcome", publicationFailure == null ? "published" : "failed"));
        if (publicationFailure != null) {
            throw publicationFailure;
        }
    }

    @FunctionalInterface
    public interface FlowResourceAuthorizationPolicy {
        boolean authorize(FlowResourceMutationContext context, String operation, String resourceType, String resourceId);
    }

    public void setChangeListener(Consumer<String> changeListener) {
        this.changeListener = changeListener != null ? changeListener : ignored -> {
        };
    }

    public void setLiveRefreshExecutor(Consumer<Runnable> liveRefreshExecutor) {
        this.liveRefreshExecutor = liveRefreshExecutor != null ? liveRefreshExecutor : Runnable::run;
    }

    void runLiveRefresh(Runnable refresh) {
        liveRefreshExecutor.accept(refresh);
    }

    private void runWithLease(FlowResourceMutationLease lease, Runnable action) {
        FlowResourceMutationLease previous = currentMutationLease.get();
        currentMutationLease.set(lease);
        try {
            action.run();
        } finally {
            if (previous != null) {
                currentMutationLease.set(previous);
            } else {
                currentMutationLease.remove();
            }
        }
    }

    private void scheduleTrackedRefresh(FlowResourceKey key, FlowResourceMutationLease lease, Runnable refresh) {
        if (lease == null) {
            runLiveRefresh(refresh);
            return;
        }
        FlowResourceMutationLease.DeferredCompletion completion = createTrackedCompletion(key, lease, refresh);
        try {
            runLiveRefresh(completion::run);
        } catch (RuntimeException exception) {
            completion.cancel();
            untrackRefresh(key, completion);
            throw exception;
        }
    }

    private FlowResourceMutationLease.DeferredCompletion createTrackedCompletion(FlowResourceKey key,
                                                                                   FlowResourceMutationLease lease,
                                                                                   Runnable refresh) {
        AtomicReference<FlowResourceMutationLease.DeferredCompletion> reference = new AtomicReference<>();
        FlowResourceMutationLease.DeferredCompletion completion = lease.defer(() -> {
            try {
                runWithLease(lease, refresh);
            } finally {
                untrackRefresh(key, reference.get());
            }
        });
        reference.set(completion);
        trackRefresh(key, completion);
        return completion;
    }

    private void scheduleTrackedRefresh(FlowResourceMutationLease lease, Runnable refresh) {
        scheduleTrackedRefresh(null, lease, refresh);
    }

    private void trackRefresh(FlowResourceKey key, FlowResourceMutationLease.DeferredCompletion completion) {
        pendingRefreshes.add(completion);
        if (key != null) {
            pendingRefreshesByKey.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(completion);
        }
    }

    private void untrackRefresh(FlowResourceKey key, FlowResourceMutationLease.DeferredCompletion completion) {
        if (completion == null) {
            return;
        }
        pendingRefreshes.remove(completion);
        if (key != null) {
            Set<FlowResourceMutationLease.DeferredCompletion> completions = pendingRefreshesByKey.get(key);
            if (completions != null && completions.remove(completion) && completions.isEmpty()) {
                pendingRefreshesByKey.remove(key, completions);
            }
        } else {
            pendingRefreshesByKey.entrySet().removeIf(entry -> {
                Set<FlowResourceMutationLease.DeferredCompletion> completions = entry.getValue();
                completions.remove(completion);
                return completions.isEmpty();
            });
        }
    }

    private TrackedMutationCompletion createDurableCompletion(
        FlowResourceKey key, FlowResourceMutationLease lease, Runnable finalizer,
        Consumer<RuntimeException> onSettled, Runnable onSuccess, Consumer<RuntimeException> onFailure) {
        synchronized (pendingSessionMonitor) {
            FlowResourceMutationLease.DeferredCompletion deferred = createTrackedCompletion(key, lease, finalizer);
            TrackedMutationCompletion completion = new TrackedMutationCompletion(deferred, failure -> {
                try {
                    onSettled.accept(failure);
                } finally {
                    untrackDurableCompletion(key, deferred);
                }
            }, onSuccess, onFailure);
            pendingDurableCompletions.put(deferred, completion);
            pendingDurableCompletionsByKey.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(completion);
            return completion;
        }
    }

    private void untrackDurableCompletion(FlowResourceKey key,
                                          FlowResourceMutationLease.DeferredCompletion deferred) {
        synchronized (pendingSessionMonitor) {
            TrackedMutationCompletion completion = pendingDurableCompletions.remove(deferred);
            Set<TrackedMutationCompletion> completions = pendingDurableCompletionsByKey.get(key);
            if (completion != null && completions != null && completions.remove(completion) && completions.isEmpty()) {
                pendingDurableCompletionsByKey.remove(key, completions);
            }
        }
    }

    private void finishSessionState(FlowResourceKey key, SessionSaveState state,
                                    RuntimeException failure) {
        synchronized (pendingSessionMonitor) {
            state.phase = failure == null ? SessionSavePhase.COMPLETED : SessionSavePhase.FAILED;
            pendingSessionLeases.remove(key, state);
        }
    }

    public void cancelPendingLiveRefreshes() {
        List<TrackedMutationCompletion> durable;
        List<FlowResourceMutationLease.DeferredCompletion> cancellable;
        synchronized (pendingSessionMonitor) {
            for (SessionSaveState state : List.copyOf(pendingSessionLeases.values())) {
                state.finalizationRequested = true;
                if (state.phase == SessionSavePhase.READY) {
                    state.phase = SessionSavePhase.DISPATCHED;
                }
            }
            durable = List.copyOf(pendingDurableCompletions.values());
            cancellable = pendingRefreshes.stream()
                .filter(completion -> !pendingDurableCompletions.containsKey(completion))
                .toList();
        }
        durable.forEach(TrackedMutationCompletion::run);
        for (FlowResourceMutationLease.DeferredCompletion completion : cancellable) {
            if (completion.cancel()) {
                untrackRefresh(null, completion);
            }
        }
    }

    public boolean cancelPendingLiveRefresh(FlowResourceKey key) {
        if (key == null) {
            return false;
        }
        List<TrackedMutationCompletion> durable;
        List<FlowResourceMutationLease.DeferredCompletion> cancellable;
        boolean sessionSave = false;
        synchronized (pendingSessionMonitor) {
            SessionSaveState state = pendingSessionLeases.get(key);
            if (state != null) {
                sessionSave = true;
                state.finalizationRequested = true;
                if (state.phase == SessionSavePhase.READY) {
                    state.phase = SessionSavePhase.DISPATCHED;
                }
            }
            durable = pendingDurableCompletionsByKey.containsKey(key)
                ? List.copyOf(pendingDurableCompletionsByKey.get(key)) : List.of();
            Set<FlowResourceMutationLease.DeferredCompletion> refreshes = pendingRefreshesByKey.get(key);
            cancellable = refreshes == null ? List.of() : refreshes.stream()
                .filter(completion -> !pendingDurableCompletions.containsKey(completion))
                .toList();
        }
        durable.forEach(TrackedMutationCompletion::run);
        boolean cancelled = sessionSave || !durable.isEmpty();
        for (FlowResourceMutationLease.DeferredCompletion completion : cancellable) {
            if (completion.cancel()) {
                cancelled = true;
                untrackRefresh(key, completion);
            }
        }
        return cancelled;
    }

    public void cancelPendingRefreshes() {
        cancelPendingLiveRefreshes();
    }

    public boolean cancelPendingRefresh(FlowResourceKey key) {
        return cancelPendingLiveRefresh(key);
    }

    public void setMutationListener(FlowResourceMutationListener mutationListener) {
        this.mutationListener = mutationListener != null ? mutationListener : FlowResourceMutationListener.NONE;
    }

    public void setCommitListener(FlowResourceCommitListener commitListener) {
        this.commitListener = commitListener != null ? commitListener : FlowResourceCommitListener.NONE;
    }

    public void setWorkspaceService(FlowWorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    public CoreResourceMutationRegistration addCoreMutationListener(Consumer<CoreResourceMutationTransition> listener) {
        return coreMutationBus.addListener(listener);
    }

    public CoreResourceMutationBus.Publication publishCommittedCoreMutation(CoreResourceMutationTransition transition) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.publishCommittedCoreMutation(transition);
        }
        long started = TemporaryLifecycleDiagnostics.start();
        CoreResourceMutationBus.Publication publication = coreMutationBus.publish(transition);
        TemporaryLifecycleDiagnostics.event("registry_transition_publication", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(null,
                transition == null ? null : transition.locator().canonicalText(), transition == null ? null : transition.mutationId(),
                null, null, transition == null ? null : transition.revision(), null, null), "outcome", publication,
                "transitionPublished", publication == CoreResourceMutationBus.Publication.PUBLISHED
                    || publication == CoreResourceMutationBus.Publication.REPLAY,
                "notificationChannel", "core_mutation"));
        return publication;
    }

    public void restoreCoreMutationHighWater(CoreResourceMutationCheckpoint checkpoint) {
        coreMutationBus.restoreHighWater(checkpoint);
    }

    public void requireGenericMutationAuthority(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        String resolved = normalized.isBlank()
            ? "Resource mutations must use the generic authoritative resource protocol"
            : normalized;
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowResourceRegistry.class, target -> {
                target.setGenericMutationAuthorityReason(resolved);
                return null;
            });
            return;
        }
        genericMutationAuthorityReason = resolved;
    }

    public void clearGenericMutationAuthorityRequirement() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowResourceRegistry.class, target -> {
                target.setGenericMutationAuthorityReason("");
                return null;
            });
            return;
        }
        genericMutationAuthorityReason = "";
    }

    public boolean genericMutationAuthorityRequired() {
        FlowResourceRegistry activeRegistry = activeRegistry();
        return !(activeRegistry != this ? activeRegistry.genericMutationAuthorityReason : genericMutationAuthorityReason).isBlank();
    }

    public String genericMutationAuthorityReason() {
        FlowResourceRegistry activeRegistry = activeRegistry();
        String reason = activeRegistry != this ? activeRegistry.genericMutationAuthorityReason : genericMutationAuthorityReason;
        return reason.isBlank()
            ? "Resource mutations must use the generic authoritative resource protocol"
            : reason;
    }

    private void setGenericMutationAuthorityReason(String reason) {
        genericMutationAuthorityReason = reason != null ? reason : "";
    }

    <T> T saveFromSession(Session session, FlowResourceAdapter<T> adapter, T value) {
        return saveFromSession(session, adapter, value, false, null, () -> {
        }, ignored -> {
        });
    }

    <T> T saveFromSession(Session session, FlowResourceAdapter<T> adapter, T value,
                          Runnable onSuccess, Consumer<RuntimeException> onFailure) {
        return saveFromSession(session, adapter, value, true, null, onSuccess, onFailure);
    }

    <T> T saveFromSession(Session session, FlowResourceAdapter<T> adapter, T value,
                          Consumer<Runnable> onCancellationReady,
                          Runnable onSuccess, Consumer<RuntimeException> onFailure) {
        return saveFromSession(session, adapter, value, true, onCancellationReady, onSuccess, onFailure);
    }

    private <T> T saveFromSession(Session session, FlowResourceAdapter<T> adapter, T value, boolean autoComplete,
                                  Consumer<Runnable> onCancellationReady,
                                  Runnable onSuccess, Consumer<RuntimeException> onFailure) {
        if (adapter == null || value == null) {
            throw new IllegalArgumentException("Resource adapter and value are required");
        }
        String id = adapter.id(value);
        FlowResourceKey key = new FlowResourceKey(adapter.descriptor().typeId(), id);
        if (allSessionSavesClosed.get()) {
            throw new IllegalStateException("Session saves are closed: " + key);
        }
        FlowResourceMutationLease current = currentMutationLease.get();
        FlowResourceMutationLease lease = current != null && current.admission() == mutationAdmission
            ? mutationAdmission.tryContinue(List.of(key), current.mutationId())
                .orElseThrow(() -> new IllegalStateException("Resource mutation lease is closed: " + key))
            : mutationAdmission.tryAcquire(key)
                .orElseThrow(() -> new IllegalStateException("Resource mutation is already admitted: " + key));
        SessionSaveState state = new SessionSaveState(session, onSuccess, onFailure);
        boolean reserved = false;
        boolean retained = false;
        TrackedMutationCompletion dispatch = null;
        TrackedMutationCompletion cancellationCompletion = null;
        try {
            synchronized (pendingSessionMonitor) {
                if (allSessionSavesClosed.get()) {
                    throw new IllegalStateException("Session saves are closed: " + key);
                }
                if (session != null && closedSessionSaves.contains(session)) {
                    throw new IllegalStateException("Session save is closed: " + key);
                }
                if (pendingSessionLeases.putIfAbsent(key, state) != null) {
                    throw new IllegalStateException("Session save is already pending: " + key);
                }
                reserved = true;
            }
            AtomicReference<T> result = new AtomicReference<>();
            runWithLease(lease, () -> {
                if (workspaces != null) {
                    result.set(workspaces.save(session, adapter, value));
                } else {
                    adapter.save(value);
                    result.set(value);
                }
            });
            T saved = result.get();
            String savedId = adapter.id(saved);
            FlowResourceKey savedKey = new FlowResourceKey(adapter.descriptor().typeId(), savedId);
            if (!key.equals(savedKey)) {
                throw new IllegalStateException("Session save changed the resource identity");
            }
            T persisted = saved;
            synchronized (pendingSessionMonitor) {
                if (pendingSessionLeases.get(savedKey) != state || state.phase != SessionSavePhase.STORING) {
                    throw new IllegalStateException("Session save lifecycle was lost: " + savedKey);
                }
                TrackedMutationCompletion tracked = createDurableCompletion(savedKey, lease,
                    () -> finalizeSessionSave(state.owner, adapter, persisted, savedKey),
                    failure -> finishSessionState(savedKey, state, failure), state.onSuccess, state.onFailure);
                state.completion = tracked;
                boolean dispatchNow = autoComplete || state.finalizationRequested
                    || allSessionSavesClosed.get() || session != null && closedSessionSaves.contains(session);
                state.phase = dispatchNow ? SessionSavePhase.DISPATCHED : SessionSavePhase.READY;
                cancellationCompletion = tracked;
                if (dispatchNow) {
                    dispatch = tracked;
                }
            }
            lease.close();
            retained = true;
            if (onCancellationReady != null) {
                TrackedMutationCompletion exactCompletion = cancellationCompletion;
                onCancellationReady.accept(exactCompletion::run);
            }
            if (dispatch != null) {
                dispatchTrackedCompletion(dispatch);
            }
            return saved;
        } catch (RuntimeException exception) {
            synchronized (pendingSessionMonitor) {
                if (reserved && pendingSessionLeases.remove(key, state)) {
                    state.phase = SessionSavePhase.FAILED;
                }
            }
            throw exception;
        } finally {
            if (!retained) {
                lease.close();
            }
        }
    }

    <T> void completeSessionSave(Session session, FlowResourceAdapter<T> adapter, T value) {
        completeSessionSave(session, adapter, value, () -> {
        }, ignored -> {
        });
    }

    <T> void completeSessionSave(Session session, FlowResourceAdapter<T> adapter, T value,
                                 Runnable onSuccess, Consumer<RuntimeException> onFailure) {
        String id = adapter.id(value);
        FlowResourceKey key = new FlowResourceKey(adapter.descriptor().typeId(), id);
        SessionSaveState state;
        TrackedMutationCompletion dispatch;
        synchronized (pendingSessionMonitor) {
            state = pendingSessionLeases.get(key);
            if (state == null) {
                throw new IllegalStateException("Session save continuation is missing: " + key);
            }
            if (!state.ownerMatches(session)) {
                throw new IllegalStateException("Session save belongs to another session: " + key);
            }
            if (state.phase == SessionSavePhase.DISPATCHED) {
                return;
            }
            if (state.phase != SessionSavePhase.READY || state.completion == null) {
                throw new IllegalStateException("Session save continuation is already settled: " + key);
            }
            state.onSuccess = onSuccess != null ? onSuccess : () -> {
            };
            state.onFailure = onFailure != null ? onFailure : ignored -> {
            };
            state.completion.callbacks(state.onSuccess, state.onFailure);
            state.phase = SessionSavePhase.DISPATCHED;
            dispatch = state.completion;
        }
        dispatchTrackedCompletion(dispatch);
    }

    public boolean cancelSessionSave(Session session, FlowResourceAdapter<?> adapter, String resourceId) {
        if (adapter == null || resourceId == null || resourceId.isBlank()) {
            return false;
        }
        FlowResourceKey key = new FlowResourceKey(adapter.descriptor().typeId(), resourceId);
        TrackedMutationCompletion dispatch = null;
        synchronized (pendingSessionMonitor) {
            SessionSaveState state = pendingSessionLeases.get(key);
            if (state == null || !state.ownerMatches(session)) {
                return false;
            }
            state.finalizationRequested = true;
            if (state.phase == SessionSavePhase.READY) {
                state.phase = SessionSavePhase.DISPATCHED;
                dispatch = state.completion;
            } else if (state.phase == SessionSavePhase.DISPATCHED) {
                dispatch = state.completion;
            }
        }
        if (dispatch != null) {
            dispatch.run();
        }
        return true;
    }

    public boolean cancelSessionSave(FlowResourceAdapter<?> adapter, String resourceId) {
        return cancelSessionSave(null, adapter, resourceId);
    }

    public boolean cancelSessionSaveValue(Session session, FlowResourceAdapter<?> adapter, Object value) {
        if (adapter == null || value == null) {
            return false;
        }
        FlowResourceAdapter<Object> typedAdapter = adapter(adapter);
        return cancelSessionSave(session, adapter, typedAdapter.id(value));
    }

    public boolean closeSessionSaves(Session session) {
        if (session == null) {
            return false;
        }
        List<TrackedMutationCompletion> completions = new ArrayList<>();
        boolean found = false;
        synchronized (pendingSessionMonitor) {
            closedSessionSaves.add(session);
            for (SessionSaveState state : List.copyOf(pendingSessionLeases.values())) {
                if (!state.ownerMatches(session)) {
                    continue;
                }
                found = true;
                state.finalizationRequested = true;
                if (state.phase == SessionSavePhase.READY) {
                    state.phase = SessionSavePhase.DISPATCHED;
                    completions.add(state.completion);
                } else if (state.phase == SessionSavePhase.DISPATCHED && state.completion != null) {
                    completions.add(state.completion);
                }
            }
        }
        completions.forEach(TrackedMutationCompletion::run);
        return found;
    }

    public boolean cancelSessionSaves(Session session) {
        return closeSessionSaves(session);
    }

    public boolean closeAllSessionSaves() {
        List<TrackedMutationCompletion> completions = new ArrayList<>();
        boolean found;
        synchronized (pendingSessionMonitor) {
            allSessionSavesClosed.set(true);
            found = !pendingSessionLeases.isEmpty();
            for (SessionSaveState state : List.copyOf(pendingSessionLeases.values())) {
                state.finalizationRequested = true;
                if (state.phase == SessionSavePhase.READY) {
                    state.phase = SessionSavePhase.DISPATCHED;
                    completions.add(state.completion);
                } else if (state.phase == SessionSavePhase.DISPATCHED && state.completion != null) {
                    completions.add(state.completion);
                }
            }
        }
        completions.forEach(TrackedMutationCompletion::run);
        return found;
    }

    private <T> void finalizeSessionSave(Session session, FlowResourceAdapter<T> adapter, T persisted,
                                         FlowResourceKey key) {
        RuntimeException failure = null;
        T latest = persisted;
        try {
            T current = adapter.get(key.resourceId());
            if (current != null) {
                latest = current;
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            adapter.afterSave(session, latest);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            T current = adapter.get(key.resourceId());
            if (current != null) {
                latest = current;
            } else {
                failure = appendFailure(failure,
                    new IllegalStateException("Saved resource is unavailable during publication: " + key));
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            changeListener.accept(adapter.catalogSource());
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        RuntimeException publicationFailure = publishSessionSaved(session, adapter, latest);
        if (publicationFailure != null) {
            failure = appendFailure(failure, publicationFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private RuntimeException appendFailure(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }

    private void dispatchTrackedCompletion(TrackedMutationCompletion completion) {
        try {
            runLiveRefresh(completion::run);
        } catch (RuntimeException exception) {
            completion.run();
        }
    }

    void deleteSerialized(FlowResourceAdapter<?> adapter, String id) {
        FlowResourceKey key = new FlowResourceKey(adapter.descriptor().typeId(), id);
        FlowResourceMutationLease current = currentMutationLease.get();
        if (current != null && current.owns(key)) {
            deleteSerializedValue(adapter, id);
            return;
        }
        FlowResourceMutationLease lease = mutationAdmission.tryAcquire(key)
            .orElseThrow(() -> new IllegalStateException("Resource mutation is already admitted: " + key));
        try {
            runWithLease(lease, () -> deleteSerializedValue(adapter, id));
        } finally {
            lease.close();
        }
    }

    private void deleteSerializedValue(FlowResourceAdapter<?> adapter, String id) {
        if (workspaces != null) {
            workspaces.delete(adapter.descriptor().typeId(), id, () -> adapter.delete(id));
            return;
        }
        adapter.delete(id);
    }

    public void setAuthorizationPolicy(FlowResourceAuthorizationPolicy authorizationPolicy) {
        this.authorizationPolicy = authorizationPolicy != null ? authorizationPolicy : this.authorizationPolicy;
    }

    public synchronized List<FlowResourceAuditRecord> auditSnapshot() {
        return List.copyOf(auditRecords);
    }

    public void register(FlowResourceAdapter<?> adapter) {
        register("builtin", adapter);
    }

    public void register(String owner, FlowResourceAdapter<?> adapter) {
        if (adapter == null || adapter.descriptor() == null || adapter.descriptor().typeId() == null || adapter.descriptor().typeId().isBlank()) {
            throw new IllegalArgumentException("Resource adapter and type ID are required");
        }
        if (adapter.descriptor().displayName() == null || adapter.descriptor().displayName().isBlank()) {
            throw new IllegalArgumentException("Resource display name is required: " + adapter.descriptor().typeId());
        }
        if (adapter.supportedOperations() == null || adapter.identityRules() == null || adapter.identityRules().isBlank()
            || adapter.lifecycle() == null || adapter.lifecycle().isBlank() || adapter.catalogSource() == null || adapter.catalogSource().isBlank()
            || adapter.authoritativeService() == null || adapter.authoritativeService().isBlank()) {
            throw new IllegalArgumentException("Resource lifecycle contract is incomplete: " + adapter.descriptor().typeId());
        }
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowResourceRegistry.class, target -> {
                target.registerLocal(owner, adapter);
                return null;
            });
            return;
        }
        registerLocal(owner, adapter);
    }

    private void registerLocal(String owner, FlowResourceAdapter<?> adapter) {
        String normalizedOwner = owner != null && !owner.isBlank() ? owner : "builtin";
        ResourceRegistration registration = new ResourceRegistration(normalizedOwner, adapter);
        ResourceRegistration previous = registrations.putIfAbsent(normalize(adapter.descriptor().typeId()), registration);
        if (previous != null && previous.adapter() != adapter) {
            throw new IllegalStateException("Resource adapter already registered: " + adapter.descriptor().typeId());
        }
    }

    public void unregister(String typeId) {
        if (typeId != null) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(FlowResourceRegistry.class, target -> {
                    target.unregisterLocal(typeId);
                    return null;
                });
                return;
            }
            unregisterLocal(typeId);
        }
    }

    private void unregisterLocal(String typeId) {
        registrations.remove(normalize(typeId));
    }

    public void unregister(String owner, String typeId) {
        if (owner != null && typeId != null) {
            ExtensionRegistryActivation current = activation;
            if (current != null) {
                current.update(FlowResourceRegistry.class, target -> {
                    target.unregisterLocal(owner, typeId);
                    return null;
                });
                return;
            }
            unregisterLocal(owner, typeId);
        }
    }

    private void unregisterLocal(String owner, String typeId) {
        registrations.computeIfPresent(normalize(typeId), (ignored, registration) -> owner.equals(registration.owner()) ? null : registration);
    }

    public FlowResourceAdapter<?> get(String typeId) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.get(typeId);
        }
        ResourceRegistration registration = registration(typeId);
        return registration != null ? registration.adapter() : null;
    }

    public boolean hasAuthoritativeAdapter(String owner, String typeId) {
        ResourceRegistration registration = registration(typeId);
        return registration != null
            && registration.adapter().descriptor().enabled()
            && owner != null
            && !owner.isBlank()
            && owner.equals(registration.owner())
            && typeId != null
            && typeId.equalsIgnoreCase(registration.adapter().descriptor().typeId());
    }

    public String owner(String typeId) {
        ResourceRegistration registration = registration(typeId);
        return registration != null ? registration.owner() : "";
    }

    public Collection<FlowResourceAdapter<?>> adapters() {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.adapters();
        }
        return registrations.values().stream()
            .<FlowResourceAdapter<?>>map(ResourceRegistration::adapter)
            .toList();
    }

    public List<String> typeIds() {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.typeIds();
        }
        return registrations.values().stream().map(registration -> registration.adapter().descriptor().typeId())
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public String protocolOwner(String typeId) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.protocolOwner(typeId);
        }
        if (isCoreGraphType(typeId)) {
            return CORE_GRAPH_OWNER.canonicalText();
        }
        ResourceRegistration registration = registration(typeId);
        return registration != null ? registration.owner() : "";
    }

    public boolean protocolSupports(String typeId, String operation) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.protocolSupports(typeId, operation);
        }
        if (isCoreGraphType(typeId)) {
            return operation != null && CORE_PROTOCOL_OPERATIONS.contains(operation.toLowerCase(Locale.ROOT));
        }
        ResourceRegistration registration = registration(typeId);
        return registration != null && operation != null
            && registration.adapter().supportedOperations().contains(operation.toLowerCase(Locale.ROOT));
    }

    public ResourceDocument<Map<String, Object>> protocolLoad(ServerResourceLocator resource) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.protocolLoad(resource);
        }
        if (!isCoreGraphResource(resource)) {
            return null;
        }
        Optional<CoreGraphStorageBoundary.Decoded> loaded = loadCoreGraph(resource);
        if (loaded.isEmpty()) {
            return null;
        }
        return coreDocument(loaded.get());
    }

    public ResourceDocument<Map<String, Object>> protocolDocument(ServerResourceLocator resource) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.protocolDocument(resource);
        }
        if (!isCoreGraphResource(resource)) {
            return null;
        }
        Optional<CoreGraphResourceAuthority.CoreGraphResourceState> state = stateCoreGraph(resource);
        if (state.isEmpty()) {
            return null;
        }
        return coreDocument(state.get());
    }

    public List<ResourceDocument<Map<String, Object>>> protocolList(ServerId serverId,
                                                                     ContractRef<ResourceTypeId> type,
                                                                     String query) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.protocolList(serverId, type, query);
        }
        if (serverId == null || type == null || !CORE_GRAPH_OWNER.equals(type.owner())
            || !isCoreGraphType(type.id().value())) {
            return List.of();
        }
        CoreGraphResourceAuthority authority = coreGraphResourceAuthority();
        if (authority == null || !authority.available()) {
            throw new IllegalStateException(CoreGraphResourceAuthority.UNAVAILABLE_MESSAGE + ": list");
        }
        String normalizedQuery = query != null ? query.strip().toLowerCase(Locale.ROOT) : "";
        return authority.list(type.id().value()).stream()
            .filter(state -> serverId.equals(state.resource().serverId())
                && type.id().value().equalsIgnoreCase(state.resource().resourceType().value())
                && (normalizedQuery.isEmpty()
                || state.resource().id().toLowerCase(Locale.ROOT).contains(normalizedQuery)))
            .sorted(Comparator.comparing(CoreGraphResourceAuthority.CoreGraphResourceState::resource))
            .map(this::coreDocument)
            .filter(document -> !document.deleted())
            .toList();
    }

    public List<ResourceDocument<Map<String, Object>>> protocolQuery(ServerId serverId,
                                                                      ContractRef<ResourceTypeId> type,
                                                                      String query) {
        return protocolList(serverId, type, query);
    }

    public Optional<CoreGraphStorageBoundary.Decoded> loadCoreGraph(ServerResourceLocator resource) {
        return requireCoreGraphAuthority(resource, "load").load(resource);
    }

    public Optional<CoreGraphResourceAuthority.CoreGraphResourceState> stateCoreGraph(ServerResourceLocator resource) {
        return requireCoreGraphAuthority(resource, "state").state(resource);
    }

    public CoreGraphStorageBoundary.Decoded saveCoreGraph(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                          UUID mutationId, long expectedRevision,
                                                          ContentHash payloadChecksum) {
        CoreGraphStorageBoundary.Decoded saved = requireCoreGraphAuthority(resource, "save").save(resource, canonicalEnvelope, mutationId,
            expectedRevision, payloadChecksum);
        return saved;
    }

    public CoreGraphStorageBoundary.Decoded saveCoreGraph(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                          long expectedRevision, UUID mutationId,
                                                          ContentHash payloadChecksum) {
        return saveCoreGraph(resource, canonicalEnvelope, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone deleteCoreGraph(ServerResourceLocator resource,
                                                                         UUID mutationId, long expectedRevision,
                                                                         ContentHash payloadChecksum) {
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = requireCoreGraphAuthority(resource, "delete").delete(resource, mutationId, expectedRevision,
            payloadChecksum);
        return tombstone;
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone deleteCoreGraph(ServerResourceLocator resource,
                                                                         long expectedRevision, UUID mutationId,
                                                                         ContentHash payloadChecksum) {
        return deleteCoreGraph(resource, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded activateCoreGraph(ServerResourceLocator resource,
                                                               ResourceActivationState activationState,
                                                               UUID mutationId, long expectedRevision,
                                                               ContentHash payloadChecksum) {
        CoreGraphStorageBoundary.Decoded activated = requireCoreGraphAuthority(resource, "activation").activate(resource, activationState, mutationId,
            expectedRevision, payloadChecksum);
        return activated;
    }

    public CoreGraphStorageBoundary.Decoded activationCoreGraph(ServerResourceLocator resource,
                                                                 ResourceActivationState activationState,
                                                                 UUID mutationId, long expectedRevision,
                                                                 ContentHash payloadChecksum) {
        return activateCoreGraph(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded activateCoreGraph(ServerResourceLocator resource,
                                                               ResourceActivationState activationState,
                                                               long expectedRevision, UUID mutationId,
                                                               ContentHash payloadChecksum) {
        return activateCoreGraph(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded activationCoreGraph(ServerResourceLocator resource,
                                                                 ResourceActivationState activationState,
                                                                 long expectedRevision, UUID mutationId,
                                                                 ContentHash payloadChecksum) {
        return activateCoreGraph(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    public Optional<CoreGraphStorageBoundary.Decoded> protocolCoreLoad(ServerResourceLocator resource) {
        return loadCoreGraph(resource);
    }

    public Optional<CoreGraphResourceAuthority.CoreGraphResourceState> protocolCoreState(ServerResourceLocator resource) {
        return stateCoreGraph(resource);
    }

    public Optional<CoreGraphResourceAuthority.CoreGraphResourceState> protocolState(ServerResourceLocator resource) {
        return stateCoreGraph(resource);
    }

    public CoreGraphStorageBoundary.Decoded protocolCoreSave(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                              UUID mutationId, long expectedRevision,
                                                              ContentHash payloadChecksum) {
        return saveCoreGraph(resource, canonicalEnvelope, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded protocolSave(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                         UUID mutationId, long expectedRevision,
                                                         ContentHash payloadChecksum) {
        return saveCoreGraph(resource, canonicalEnvelope, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded protocolCoreSave(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                              long expectedRevision, UUID mutationId,
                                                              ContentHash payloadChecksum) {
        return saveCoreGraph(resource, canonicalEnvelope, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded protocolSave(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                         long expectedRevision, UUID mutationId,
                                                         ContentHash payloadChecksum) {
        return saveCoreGraph(resource, canonicalEnvelope, mutationId, expectedRevision, payloadChecksum);
    }


    public CoreGraphStorageBoundary.CoreGraphTombstone protocolCoreDelete(ServerResourceLocator resource,
                                                                            UUID mutationId, long expectedRevision,
                                                                            ContentHash payloadChecksum) {
        return deleteCoreGraph(resource, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone protocolDelete(ServerResourceLocator resource,
                                                                       UUID mutationId, long expectedRevision,
                                                                       ContentHash payloadChecksum) {
        return deleteCoreGraph(resource, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone protocolCoreDelete(ServerResourceLocator resource,
                                                                            long expectedRevision, UUID mutationId,
                                                                            ContentHash payloadChecksum) {
        return deleteCoreGraph(resource, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone protocolDelete(ServerResourceLocator resource,
                                                                       long expectedRevision, UUID mutationId,
                                                                       ContentHash payloadChecksum) {
        return deleteCoreGraph(resource, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded protocolCoreActivation(ServerResourceLocator resource,
                                                                    ResourceActivationState activationState,
                                                                    UUID mutationId, long expectedRevision,
                                                                    ContentHash payloadChecksum) {
        return activateCoreGraph(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded protocolActivation(ServerResourceLocator resource,
                                                                ResourceActivationState activationState,
                                                                UUID mutationId, long expectedRevision,
                                                                ContentHash payloadChecksum) {
        return activateCoreGraph(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded protocolActivate(ServerResourceLocator resource,
                                                              ResourceActivationState activationState,
                                                              UUID mutationId, long expectedRevision,
                                                              ContentHash payloadChecksum) {
        return activateCoreGraph(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded protocolCoreActivation(ServerResourceLocator resource,
                                                                    ResourceActivationState activationState,
                                                                    long expectedRevision, UUID mutationId,
                                                                    ContentHash payloadChecksum) {
        return activateCoreGraph(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded protocolActivation(ServerResourceLocator resource,
                                                                ResourceActivationState activationState,
                                                                long expectedRevision, UUID mutationId,
                                                                ContentHash payloadChecksum) {
        return activateCoreGraph(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    public CoreGraphStorageBoundary.Decoded protocolActivate(ServerResourceLocator resource,
                                                              ResourceActivationState activationState,
                                                              long expectedRevision, UUID mutationId,
                                                              ContentHash payloadChecksum) {
        return activateCoreGraph(resource, activationState, mutationId, expectedRevision, payloadChecksum);
    }

    private CoreGraphResourceAuthority requireCoreGraphAuthority(ServerResourceLocator resource, String operation) {
        if (!isCoreGraphResource(resource)) {
            throw new IllegalArgumentException("Core graph resource locator is not authoritative: "
                + (resource != null ? resource.canonicalText() : ""));
        }
        CoreGraphResourceAuthority authority = coreGraphResourceAuthority();
        if (authority == null || !authority.available()) {
            throw new IllegalStateException(CoreGraphResourceAuthority.UNAVAILABLE_MESSAGE + ": " + operation);
        }
        return authority;
    }

    private boolean isCoreGraphType(String typeId) {
        return typeId != null && CORE_GRAPH_TYPES.contains(typeId.toLowerCase(Locale.ROOT));
    }

    private boolean isCoreGraphResource(ServerResourceLocator resource) {
        return resource != null && isCoreGraphType(resource.resourceType().value())
            && CORE_GRAPH_OWNER.equals(resource.owner()) && resource.id() != null && !resource.id().isBlank();
    }

    private AggregateResourceCreateStorage.Result createAdapterAggregate(ServerResourceLocator resource, Object value,
                                                                         FlowResourceMutationContext context,
                                                                         ResourcePresentationIntent presentation) {
        ResourceRegistration registration = registration(resource.resourceType().value());
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null || !rawAdapter.supportedOperations().contains("create")
            || !rawAdapter.supportsAggregateCreate()) {
            throw new UnsupportedOperationException("Resource adapter does not support aggregate create");
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        String id = adapter.id(value);
        if (!resource.id().equals(id)) {
            throw new IllegalArgumentException("Aggregate create value identity does not match its resource locator");
        }
        adapter.validate(value);
        return adapter.createAggregate(resource, value, context, presentation);
    }

    private AggregateResourceCreateStorage.Result createCoreAggregate(ServerResourceLocator resource, Object value,
                                                                      FlowResourceMutationContext context,
                                                                      ResourcePresentationIntent presentation) {
        if (!(value instanceof CoreGraphStorageBoundary.Decoded decoded)) {
            throw new IllegalArgumentException("Aggregate Core create requires a decoded Core graph payload");
        }
        Map<String, Object> requestedPayload = corePayload(new CoreGraphStorageBoundary().encode(decoded));
        CanonicalPayload<Map<String, Object>> requestedCanonical = ResourcePayloadCodecs.json().canonicalize(requestedPayload);
        if (!context.expectedPayloadHash().equals(requestedCanonical.checksum().canonicalText())) {
            throw new IllegalArgumentException("Aggregate Core create payload does not match the exact mutation context");
        }
        CoreGraphResourceAuthority.CoreGraphCreateResult created = requireCoreGraphAuthority(resource, "create")
            .create(resource, decoded, context.exactMutationId(), context.expectedRevision(), presentation);
        CoreGraphStorageBoundary.Decoded primary = created.primary();
        Map<String, Object> primaryPayload = corePayload(new CoreGraphStorageBoundary().encode(primary));
        CanonicalPayload<Map<String, Object>> primaryCanonical = ResourcePayloadCodecs.json().canonicalize(primaryPayload);
        CoreGraphStorageBoundary.AssetEnvelope envelope = primary.envelope();
        FlowResourceMutationStamp primaryStamp = new FlowResourceMutationStamp(resource.resourceType().value(),
            resource.id(), envelope.assetRevision(), UUID.fromString(envelope.assetMutationId()),
            primaryCanonical.checksum().canonicalText(), false);
        FlowStorage.ResourceIdentity metadataIdentity = created.projectMetadataIdentity();
        FlowResourceMutationStamp metadataStamp = new FlowResourceMutationStamp(metadataIdentity.type(),
            metadataIdentity.id(), metadataIdentity.revision(), UUID.fromString(metadataIdentity.mutationId()),
            metadataIdentity.payloadHash(), metadataIdentity.deleted());
        Map<String, Object> metadataPayload = GSON.fromJson(created.canonicalProjectMetadataJson(), Map.class);
        ServerResourceLocator metadataResource = new ServerResourceLocator(resource.serverId(),
            ContractRef.of(CORE_GRAPH_OWNER, ResourceTypeId.of("project_metadata")), metadataIdentity.id());
        ContentHash corePayloadHash = corePayloadChecksum(primary);
        return new AggregateResourceCreateStorage.Result(
            new AggregateResourceCreateStorage.ResourceState(resource, primaryStamp, primaryCanonical.value(),
                envelope.assetHash().canonicalText(), corePayloadHash.canonicalText(), primary.corePayloadKind()),
            new AggregateResourceCreateStorage.ResourceState(metadataResource, metadataStamp, metadataPayload));
    }

    private void requireAggregateResult(ServerResourceLocator requested, FlowResourceMutationContext context,
                                        AggregateResourceCreateStorage.Result result) {
        Objects.requireNonNull(result, "Aggregate create result is required");
        ServerResourceLocator metadataResource = new ServerResourceLocator(requested.serverId(),
            ContractRef.of(CORE_GRAPH_OWNER, ResourceTypeId.of("project_metadata")),
            requested.serverId().canonicalText());
        if (!requested.equals(result.primary().resource()) || !metadataResource.equals(result.projectMetadata().resource())
            || !context.exactMutationId().equals(result.primary().stamp().mutationId())
            || !context.exactMutationId().equals(result.projectMetadata().stamp().mutationId())
            || result.primary().stamp().revision() != Math.addExact(context.expectedRevision(), 1L)
            || !context.expectedPayloadHash().equals(result.primary().stamp().payloadHash())) {
            throw new IllegalStateException("Aggregate create result does not match the exact requested mutation");
        }
    }

    private ContentHash corePayloadChecksum(CoreGraphStorageBoundary.Decoded decoded) {
        Object payload = decoded.payload();
        return payload instanceof GraphDocument graph ? graph.checksum()
            : ((FunctionSourceDocument) payload).checksum();
    }

    private ResourceDocument<Map<String, Object>> coreDocument(CoreGraphStorageBoundary.Decoded decoded) {
        Objects.requireNonNull(decoded, "Core graph payload is required");
        Map<String, Object> payload = corePayload(new CoreGraphStorageBoundary().encode(decoded));
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        CoreGraphStorageBoundary.AssetEnvelope envelope = decoded.envelope();
        return ResourceDocument.live(decodedResource(decoded), envelope.assetRevision(),
            UUID.fromString(envelope.assetMutationId()), canonical, envelope.assetActivationState(), "core");
    }

    private ResourceDocument<Map<String, Object>> coreDocument(CoreGraphResourceAuthority.CoreGraphResourceState state) {
        if (state.deleted()) {
            return ResourceDocument.tombstone(state.resource(), state.revision(), state.mutationId(),
                state.payloadChecksum(), "core");
        }
        return coreDocument(state.envelope());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> corePayload(byte[] canonicalBytes) {
        JsonElement parsed = JsonParser.parseString(new String(canonicalBytes, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException("Core graph protocol payload must be an object");
        }
        return GSON.fromJson(parsed, Map.class);
    }

    private ServerResourceLocator decodedResource(CoreGraphStorageBoundary.Decoded decoded) {
        Object payload = decoded.payload();
        if (payload instanceof GraphDocument graph) {
            return graph.resource();
        }
        return ((FunctionSourceDocument) payload).graph().resource();
    }

    public synchronized FlowResourceRegistry copy() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().resources();
        }
        FlowResourceRegistry copy = new FlowResourceRegistry(mutationAdmission, pendingSessionLeases, pendingSessionMonitor,
            closedSessionSaves, allSessionSavesClosed, pendingRefreshes, pendingRefreshesByKey,
            pendingDurableCompletions, pendingDurableCompletionsByKey, currentMutationLease, coreGraphAuthority,
            coreMutationBus);
        copy.registrations.putAll(registrations);
        copy.networkCoreMutations = networkCoreMutations;
        copy.auditRecords.addAll(auditRecords);
        copy.changeListener = changeListener;
        copy.liveRefreshExecutor = liveRefreshExecutor;
        copy.mutationListener = mutationListener;
        copy.commitListener = commitListener;
        copy.workspaces = workspaces;
        copy.genericMutationAuthorityReason = genericMutationAuthorityReason;
        copy.authorizationPolicy = authorizationPolicy;
        return copy;
    }

    public synchronized void replaceFrom(FlowResourceRegistry staged) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowResourceRegistry.class, target -> {
                target.replaceFromLocal(staged);
                return null;
            });
            return;
        }
        replaceFromLocal(staged);
    }

    private synchronized void replaceFromLocal(FlowResourceRegistry staged) {
        if (staged == null) {
            throw new IllegalArgumentException("A staged Flow resource registry is required");
        }
        registrations.clear();
        registrations.putAll(staged.registrations);
    }

    public String serialize(String typeId, String id) {
        ResourceRegistration registration = registration(typeId);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown resource type");
        }
        FlowResourceAdapter<Object> adapter = adapter(registration.adapter());
        Object value = adapter.get(id);
        if (value == null) {
            throw new IllegalArgumentException("Resource not found");
        }
        return adapter.serialize(value);
    }

    public void createJson(String typeId, String json) {
        persistJson(typeId, json, true);
    }

    public void updateJson(String typeId, String json) {
        persistJson(typeId, json, false);
    }

    private void persistJson(String typeId, String json, boolean create) {
        ResourceRegistration registration = registration(typeId);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown resource type");
        }
        FlowResourceAdapter<Object> adapter = adapter(registration.adapter());
        Object value = adapter.deserialize(json);
        FlowOperationResult<FlowResourceReference> result = create ? create(typeId, value) : update(typeId, value);
        if (!result.success()) {
            throw new IllegalArgumentException(result.message());
        }
    }

    public void deleteAuthoritative(String typeId, String id) {
        FlowOperationResult<FlowResourceReference> result = delete(typeId, id);
        if (!result.success()) {
            throw new IllegalArgumentException(result.message());
        }
    }

    public FlowResourceReference reference(String typeId, String id, boolean available) {
        ResourceRegistration registration = registration(typeId);
        return registration != null ? reference(registration, id != null ? id : "", available)
            : new FlowResourceReference(typeId != null ? typeId : "", id != null ? id : "", "unresolved", false, Map.of());
    }

    public FlowOperationResult<List<FlowResourceReference>> discover(String typeId, String query) {
        return list(typeId, query, "discover");
    }

    public FlowOperationResult<List<FlowResourceReference>> query(String typeId, String query) {
        return list(typeId, query, "query");
    }

    private FlowOperationResult<List<FlowResourceReference>> list(String typeId, String query, String operation) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        if (adapter == null) {
            return unavailable(typeId, operation);
        }
        if (!adapter.supportedOperations().contains(operation)) {
            return unsupported(typeId, operation);
        }
        String normalizedQuery = query != null ? query.strip() : "";
        try {
            List<FlowResourceReference> references = adapter.listIds().stream()
                .filter(id -> normalizedQuery.isEmpty() || id.toLowerCase(Locale.ROOT).contains(normalizedQuery.toLowerCase(Locale.ROOT)))
                .map(id -> reference(registration, id, true))
                .toList();
            return FlowOperationResult.success(references);
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_DISCOVERY_FAILED", failureMessage(exception), Map.of("resourceType", typeId));
        }
    }

    public FlowOperationResult<Object> get(String typeId, String id) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        if (adapter == null) {
            return unavailable(typeId, "get");
        }
        if (!adapter.supportedOperations().contains("get")) {
            return unsupported(typeId, "get");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        try {
            Object value = adapter.get(id);
            return value != null ? FlowOperationResult.success(value)
                : FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_READ_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    public FlowOperationResult<FlowResourceReference> save(String typeId, Object value) {
        return save(typeId, value, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> save(String typeId, Object value, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, resourceId(typeId, value), "save", context,
            resolvedContext -> persist(typeId, value, "save", ExistencePolicy.ANY, resolvedContext));
    }

    public FlowOperationResult<FlowResourceReference> saveNetwork(String typeId, Object value) {
        if (isCoreGraphType(typeId)) {
            return mutateNetworkCore(typeId, resourceId(typeId, value), value, false);
        }
        return authorizedMutation(typeId, resourceId(typeId, value), "save", FlowResourceMutationContext.system(),
            context -> persist(typeId, value, "save", ExistencePolicy.ANY, context, true));
    }

    public FlowOperationResult<FlowResourceReference> deleteNetwork(String typeId, String id) {
        return isCoreGraphType(typeId) ? mutateNetworkCore(typeId, id, null, true) : delete(typeId, id);
    }

    private FlowOperationResult<FlowResourceReference> mutateNetworkCore(String typeId, String id, Object value, boolean deleted) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.mutateNetworkCore(typeId, id, value, deleted);
        }
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<Object> adapter = registration == null ? null : adapter(registration.adapter());
        String operation = deleted ? "delete" : "save";
        if (adapter == null) {
            return unavailable(typeId, operation);
        }
        if (!adapter.supportedOperations().contains(operation)) {
            return unsupported(typeId, operation);
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        if (!authorizationPolicy.authorize(FlowResourceMutationContext.system(), operation, typeId, id)) {
            return FlowOperationResult.failure("RESOURCE_AUTHORIZATION_DENIED", "Resource operation is not authorized",
                Map.of("operation", operation, "resourceType", typeId, "resourceId", id));
        }
        try {
            if (deleted) {
                adapter.deleteNetwork(id);
            } else {
                adapter.saveNetwork(value);
            }
            return FlowOperationResult.success(reference(registration, id, !deleted));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_" + operation.toUpperCase(Locale.ROOT) + "_FAILED", failureMessage(exception),
                Map.of("operation", operation, "resourceType", typeId, "resourceId", id));
        }
    }

    void saveAuthoritative(String typeId, Object value) {
        saveAuthoritativeDurable(typeId, value).run();
    }

    Runnable saveAuthoritativeDurable(String typeId, Object value) {
        return saveAuthoritativeDurableHandle(typeId, value, () -> {
        }, () -> {
        })::run;
    }

    public FlowResourceMutationLease.DeferredCompletion saveAuthoritativeDurableHandle(String typeId, Object value) {
        return saveAuthoritativeDurableHandle(typeId, value, () -> {
        }, () -> {
        });
    }

    Runnable saveAuthoritativeDurable(String typeId, Object value, Runnable beforeVisible, Runnable afterVisible) {
        return saveAuthoritativeDurableHandle(typeId, value, beforeVisible, afterVisible)::run;
    }

    public FlowResourceMutationLease.DeferredCompletion saveAuthoritativeDurableHandle(String typeId, Object value,
                                                                                        Runnable beforeVisible,
                                                                                        Runnable afterVisible) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            throw new IllegalStateException("Resource adapter unavailable: " + typeId);
        }
        if (!rawAdapter.supportedOperations().contains("save")) {
            throw new IllegalStateException("Resource save is unavailable: " + typeId);
        }
        if (value == null) {
            throw new IllegalArgumentException("Resource value is required");
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        String id = adapter.id(value);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Resource ID is required");
        }
        FlowResourceKey key = new FlowResourceKey(typeId, id);
        FlowResourceMutationLease current = currentMutationLease.get();
        boolean alreadyOwned = current != null && current.admission() == mutationAdmission && current.owns(key);
        FlowResourceMutationLease lease = alreadyOwned ? current : mutationAdmission.tryAcquire(key)
            .orElseThrow(() -> new IllegalStateException("Resource mutation is already admitted: " + key));
        try {
            adapter.validate(value);
            adapter.save(value);
            FlowResourceMutationLease.DeferredCompletion completion = createTrackedCompletion(key, lease, () -> {
                Object latest = adapter.get(id);
                if (latest == null) {
                    return;
                }
                beforeVisible.run();
                try {
                    adapter.afterSave(latest);
                    latest = adapter.get(id);
                    if (latest == null) {
                        return;
                    }
                    try {
                        changeListener.accept(adapter.catalogSource());
                    } catch (RuntimeException exception) {
                    }
                    notifySaved(adapter, latest);
                } finally {
                    afterVisible.run();
                }
            });
            return completion;
        } catch (RuntimeException exception) {
            throw exception;
        } finally {
            if (!alreadyOwned) {
                lease.close();
            }
        }
    }

    public String setEnabledAuthoritative(String typeId, String resourceId, boolean enabled) {
        return setEnabledAuthoritative((Session) null, typeId, resourceId, enabled);
    }

    public String setEnabledAuthoritative(Session session, String typeId, String resourceId, boolean enabled) {
        return setEnabledAuthoritative(session, typeId, resourceId, enabled, null);
    }

    public String setEnabledAuthoritative(FlowResourceMutationContext context, String typeId, String resourceId, boolean enabled) {
        return setEnabledAuthoritative(null, typeId, resourceId, enabled, context);
    }

    public String setEnabledAuthoritative(Session session, String typeId, String resourceId, boolean enabled,
                                          FlowResourceMutationContext context) {
        FlowResourceKey key = new FlowResourceKey(typeId, resourceId);
        if (context != null && context.isExactMutation() && !validExactContext(context, List.of(key))) {
            throw new IllegalStateException("The exact resource mutation context does not match its retained lease");
        }
        LeaseResolution resolution = resolveLease(context, List.of(key));
        FlowResourceMutationLease lease = resolution.lease();
        if (lease == null && !resolution.alreadyOwned()) {
            throw new IllegalStateException("Resource mutation is already admitted: " + key);
        }
        AtomicReference<String> result = new AtomicReference<>("");
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            scheduleTrackedRefresh(key, lease, () -> {
                try {
                    result.set(setEnabledOnRuntime(session, typeId, resourceId, enabled, context));
                } catch (RuntimeException exception) {
                    failure.set(exception);
                }
            });
            if (failure.get() != null) {
                throw failure.get();
            }
            return result.get();
        } finally {
            if (resolution.acquired() || resolution.retained()) {
                lease.close();
            }
        }
    }

    private String setEnabledOnRuntime(Session session, String typeId, String resourceId, boolean enabled,
                                       FlowResourceMutationContext context) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            throw new IllegalStateException("Resource unavailable");
        }
        if (!rawAdapter.supportedOperations().contains("save")) {
            throw new IllegalStateException("Resource cannot be updated");
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("Resource ID is required");
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        boolean exact = context != null && context.isExactMutation();
        if (exact && !rawAdapter.supportsAuthoritativeMutationIdentity()) {
            throw new IllegalStateException(FlowResourceAdapter.AUTHORITATIVE_MUTATION_IDENTITY_UNAVAILABLE);
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            Object current = adapter.get(resourceId);
            if (current == null) {
                throw new IllegalArgumentException("Resource not found");
            }
            FlowResourceMutationStamp before = exact ? adapter.readMutationStamp(resourceId) : null;
            if (exact) {
                validateExactDeleteOrActivationPrecondition(typeId, resourceId, context, before);
            }
            JsonObject document;
            try {
                document = JsonParser.parseString(adapter.serialize(current)).getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Resource cannot be updated", exception);
            }
            boolean currentEnabled = !document.has("enabled") || document.get("enabled").isJsonNull() || document.get("enabled").getAsBoolean();
            if (currentEnabled == enabled) {
                return adapter.serialize(current);
            }
            JsonElement previousEnabled = document.has("enabled") ? document.get("enabled").deepCopy() : null;
            document.addProperty("enabled", enabled);
            Object updated = adapter.deserialize(document.toString());
            if (updated == null || !resourceId.equals(adapter.id(updated))) {
                throw new IllegalStateException("Resource identity changed");
            }
            try {
                if (enabled) {
                    adapter.validate(updated);
                }
                if (exact) {
                    String expectedHash = expectedPayloadHash(context);
                    adapter.save(updated, context.exactMutationId(), context.expectedRevision(), expectedHash);
                    verifyExactStamp(typeId, resourceId, context.expectedRevision() + 1L, context.exactMutationId(), expectedHash,
                        false, adapter, updated);
                } else {
                    adapter.save(updated);
                }
                RefreshOutcome refresh = refreshAfterSaveReliably(adapter, updated);
                if (!refresh.succeeded()) {
                    if (!exact) {
                        rollbackEnabled(adapter, resourceId, previousEnabled);
                    }
                    throw new IllegalStateException("The live resource could not be refreshed: " + refresh.error());
                }
                notifySaved(session, adapter, updated);
                return adapter.serialize(updated);
            } catch (ResourceRevisionConflictException exception) {
                if (exact || attempt == 2) {
                    throw exception;
                }
            }
        }
        throw new IllegalStateException("Resource could not be updated");
    }

    private RefreshOutcome refreshAfterSaveReliably(FlowResourceAdapter<Object> adapter, Object value) {
        RefreshOutcome refresh = refreshAfterSave(adapter, value);
        for (int attempt = 1; attempt < 3 && !refresh.succeeded(); attempt++) {
            refresh = refreshAfterSave(adapter, value);
        }
        return refresh;
    }

    private void rollbackEnabled(FlowResourceAdapter<Object> adapter, String resourceId, JsonElement previousEnabled) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Object latest = adapter.get(resourceId);
            if (latest == null) {
                throw new IllegalStateException("The previous resource state could not be restored");
            }
            JsonObject document = JsonParser.parseString(adapter.serialize(latest)).getAsJsonObject();
            if (previousEnabled == null) {
                document.remove("enabled");
            } else {
                document.add("enabled", previousEnabled.deepCopy());
            }
            Object restored = adapter.deserialize(document.toString());
            try {
                adapter.save(restored);
                RefreshOutcome refresh = refreshAfterSaveReliably(adapter, restored);
                if (!refresh.succeeded()) {
                    throw new IllegalStateException("The previous live resource state could not be restored: " + refresh.error());
                }
                notifySaved(adapter, restored);
                return;
            } catch (ResourceRevisionConflictException exception) {
                if (attempt == 2) {
                    throw exception;
                }
            }
        }
    }

    public FlowOperationResult<FlowResourceReference> create(String typeId, Object value) {
        return create(typeId, value, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> create(String typeId, Object value, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, resourceId(typeId, value), "create", context,
            resolvedContext -> persist(typeId, value, "create", ExistencePolicy.MISSING, resolvedContext));
    }

    public FlowOperationResult<FlowResourceReference> update(String typeId, Object value) {
        return update(typeId, value, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> update(String typeId, Object value, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, resourceId(typeId, value), "update", context,
            resolvedContext -> persist(typeId, value, "update", ExistencePolicy.EXISTING, resolvedContext));
    }

    private FlowOperationResult<FlowResourceReference> persist(String typeId, Object value, String operation, ExistencePolicy existencePolicy) {
        return persist(typeId, value, operation, existencePolicy, FlowResourceMutationContext.system());
    }

    private FlowOperationResult<FlowResourceReference> persist(String typeId, Object value, String operation,
                                                               ExistencePolicy existencePolicy,
                                                               FlowResourceMutationContext context) {
        return persist(typeId, value, operation, existencePolicy, context, false);
    }

    private FlowOperationResult<FlowResourceReference> persist(String typeId, Object value, String operation,
                                                               ExistencePolicy existencePolicy,
                                                               FlowResourceMutationContext context, boolean network) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, operation);
        }
        if (!rawAdapter.supportedOperations().contains(operation)) {
            return unsupported(typeId, operation);
        }
        if (value == null) {
            return FlowOperationResult.failure("RESOURCE_VALUE_REQUIRED", "Resource value is required", Map.of("resourceType", typeId));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        boolean exact = context != null && context.isExactMutation();
        if (exact && !rawAdapter.supportsAuthoritativeMutationIdentity()) {
            return authoritativeMutationUnavailable(typeId, operation);
        }
        try {
            String id = adapter.id(value);
            if (id == null || id.isBlank()) {
                return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
            }
            FlowResourceMutationStamp before = exact ? adapter.readMutationStamp(id) : null;
            boolean existed = adapter.get(id) != null;
            if (exact) {
                validateExactPersistPrecondition(typeId, id, operation, existencePolicy, context, before, existed);
            }
            if (existencePolicy == ExistencePolicy.MISSING && adapter.conflicts(id)) {
                return FlowOperationResult.failure("RESOURCE_ALREADY_EXISTS", "Resource already exists: " + id,
                    Map.of("resourceType", typeId, "resourceId", id, "operation", operation));
            }
            if (existencePolicy == ExistencePolicy.EXISTING && !existed) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id,
                    Map.of("resourceType", typeId, "resourceId", id, "operation", operation));
            }
            try {
                adapter.validate(value);
            } catch (IllegalArgumentException rejection) {
                return FlowOperationResult.failure("RESOURCE_PAYLOAD_INVALID", failureMessage(rejection),
                    Map.of("resourceType", typeId, "operation", operation));
            }
            String expectedHash = exact ? expectedPayloadHash(context) : "";
            if (exact) {
                adapter.save(value, context.exactMutationId(), context.expectedRevision(), expectedHash);
                verifyExactStamp(typeId, id, context.expectedRevision() + 1L, context.exactMutationId(), expectedHash,
                    false, adapter, value);
            } else if (network) {
                adapter.saveNetwork(value);
            } else {
                adapter.save(value);
            }
            RefreshOutcome refresh = refreshAfterSave(adapter, value);
            notifySaved(adapter, value);
            return new FlowOperationResult<>(true, reference(registration, id, true), "", "",
                mutationDetails(typeId, id, !existed, existed, false, adapter, refresh));
        } catch (FlowResourceAdapter.PreCommitRejection rejection) {
            return FlowOperationResult.failure("RESOURCE_PAYLOAD_INVALID", failureMessage(rejection),
                Map.of("resourceType", typeId, "operation", operation));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_" + operation.toUpperCase(Locale.ROOT) + "_FAILED", failureMessage(exception),
                Map.of("resourceType", typeId, "operation", operation));
        }
    }

    public FlowOperationResult<FlowResourceReference> duplicate(String typeId, String sourceId, String targetId) {
        return duplicate(typeId, sourceId, targetId, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> duplicate(String typeId, String sourceId, String targetId, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, targetId, "duplicate", context,
            duplicateResourceKeys(typeId, sourceId, targetId, context),
            resolvedContext -> duplicateAuthorized(typeId, sourceId, targetId, resolvedContext));
    }

    private FlowOperationResult<FlowResourceReference> duplicateAuthorized(String typeId, String sourceId, String targetId) {
        return duplicateAuthorized(typeId, sourceId, targetId, FlowResourceMutationContext.system());
    }

    private FlowOperationResult<FlowResourceReference> duplicateAuthorized(String typeId, String sourceId, String targetId,
                                                                            FlowResourceMutationContext context) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "duplicate");
        }
        if (!rawAdapter.supportedOperations().contains("duplicate")) {
            return unsupported(typeId, "duplicate");
        }
        boolean exact = context != null && context.isExactMutation();
        if (exact && !rawAdapter.supportsAuthoritativeMutationIdentity()) {
            return authoritativeMutationUnavailable(typeId, "duplicate");
        }
        if (sourceId == null || sourceId.isBlank() || targetId == null || targetId.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Source and target resource IDs are required",
                Map.of("resourceType", typeId, "sourceId", text(sourceId), "targetId", text(targetId)));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        try {
            Object source = adapter.get(sourceId);
            if (source == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + sourceId,
                    Map.of("resourceType", typeId, "resourceId", sourceId));
            }
            FlowResourceMutationStamp sourceStamp = exact ? adapter.readMutationStamp(sourceId) : null;
            FlowResourceMutationStamp targetStamp = exact ? adapter.readMutationStamp(targetId) : null;
            if (exact) {
                validateExactDuplicatePrecondition(typeId, sourceId, targetId, context, sourceStamp, targetStamp);
            }
            if (adapter.conflicts(targetId)) {
                return FlowOperationResult.failure("RESOURCE_ALREADY_EXISTS", "Resource already exists: " + targetId,
                    Map.of("resourceType", typeId, "resourceId", targetId));
            }
            Object copy = adapter.duplicate(source, targetId);
            if (copy == null || !targetId.equals(adapter.id(copy))) {
                return FlowOperationResult.failure("RESOURCE_DUPLICATE_INVALID", "Duplicated resource did not preserve the requested target ID",
                    Map.of("resourceType", typeId, "sourceId", sourceId, "targetId", targetId));
            }
            adapter.validate(copy);
            String expectedHash = exact ? expectedPayloadHash(context) : "";
            if (exact) {
                if (adapter.supportsAggregateCreate()) {
                    duplicateAggregate(typeId, targetId, copy, context, adapter);
                } else {
                    adapter.save(copy, context.exactMutationId(), 0L, expectedHash);
                }
                verifyExactStamp(typeId, targetId, 1L, context.exactMutationId(), expectedHash,
                    false, adapter, copy);
                FlowResourceMutationStamp sourceAfter = adapter.readMutationStamp(sourceId);
                if (!sameStamp(sourceStamp, sourceAfter)) {
                    throw new IllegalStateException("The duplicate source changed during the mutation");
                }
            } else {
                adapter.save(copy);
            }
            RefreshOutcome refresh = refreshAfterSave(adapter, copy);
            notifySaved(adapter, copy);
            return new FlowOperationResult<>(true, reference(registration, targetId, true), "", "",
                mutationDetails(typeId, targetId, true, false, false, adapter, refresh));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_DUPLICATE_FAILED", failureMessage(exception),
                Map.of("resourceType", typeId, "sourceId", sourceId, "targetId", targetId));
        }
    }

    public FlowOperationResult<Object> reload(String typeId, String id) {
        return reload(typeId, id, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<Object> reload(String typeId, String id, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, id, "reload", context, () -> reloadAuthorized(typeId, id));
    }

    private FlowOperationResult<Object> reloadAuthorized(String typeId, String id) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "reload");
        }
        if (!rawAdapter.supportedOperations().contains("reload")) {
            return unsupported(typeId, "reload");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        try {
            Object value = adapter(rawAdapter).reload(id);
            if (value == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
            }
            String refreshError = "";
            try {
                changeListener.accept(rawAdapter.catalogSource());
            } catch (RuntimeException exception) {
                refreshError = failureMessage(exception);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("resourceType", typeId);
            details.put("resourceId", id);
            details.put("reloaded", true);
            details.put("refreshSucceeded", refreshError.isBlank());
            if (!refreshError.isBlank()) {
                details.put("refreshError", refreshError);
            }
            return new FlowOperationResult<>(true, value, "", "", Map.copyOf(details));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_RELOAD_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    public FlowOperationResult<Object> apply(String typeId, String id, Object context) {
        return apply(typeId, id, context, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<Object> apply(String typeId, String id, Object runtimeContext, FlowResourceMutationContext mutationContext) {
        return authorizedMutation(typeId, id, "apply", mutationContext, () -> applyAuthorized(typeId, id, runtimeContext));
    }

    public FlowOperationResult<Object> applyValue(String typeId, Object value, Object runtimeContext, FlowResourceMutationContext mutationContext) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "apply");
        }
        if (!rawAdapter.supportedOperations().contains("apply")) {
            return unsupported(typeId, "apply");
        }
        if (value == null) {
            return FlowOperationResult.failure("RESOURCE_VALUE_REQUIRED", "Resource value is required", Map.of("resourceType", typeId));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        String id = adapter.id(value);
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource value has no ID", Map.of("resourceType", typeId));
        }
        return authorizedMutation(typeId, id, "apply", mutationContext, () -> {
            try {
                return FlowOperationResult.success(adapter.apply(value, runtimeContext));
            } catch (RuntimeException exception) {
                return FlowOperationResult.failure("RESOURCE_APPLY_FAILED", failureMessage(exception),
                    Map.of("resourceType", typeId, "resourceId", id));
            }
        });
    }

    private FlowOperationResult<Object> applyAuthorized(String typeId, String id, Object context) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "apply");
        }
        if (!rawAdapter.supportedOperations().contains("apply")) {
            return unsupported(typeId, "apply");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        try {
            Object value = adapter.get(id);
            if (value == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
            }
            return FlowOperationResult.success(adapter.apply(value, context));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_APPLY_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    public FlowOperationResult<FlowResourceReference> validate(String typeId, Object value) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "validate");
        }
        if (!rawAdapter.supportedOperations().contains("validate")) {
            return unsupported(typeId, "validate");
        }
        if (value == null) {
            return FlowOperationResult.failure("RESOURCE_VALUE_REQUIRED", "Resource value is required", Map.of("resourceType", typeId));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        try {
            adapter.validate(value);
            String id = adapter.id(value);
            if (id == null || id.isBlank()) {
                return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
            }
            return FlowOperationResult.success(reference(registration, id, true));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_VALIDATION_FAILED", failureMessage(exception), Map.of("resourceType", typeId));
        }
    }

    public FlowOperationResult<FlowResourceReference> delete(String typeId, String id) {
        return delete(typeId, id, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> delete(String typeId, String id, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, id, "delete", context,
            resolvedContext -> deleteAuthorized(typeId, id, resolvedContext));
    }

    FlowOperationResult<FlowResourceReference> deleteFromSession(Session session, FlowResourceAdapter<?> adapter,
                                                                 String id, FlowResourceMutationContext context,
                                                                 Consumer<Runnable> onCancellationReady,
                                                                 Runnable onSuccess,
                                                                 Consumer<RuntimeException> onFailure) {
        if (adapter == null) {
            return FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource adapter is required",
                Map.of("resourceId", text(id)));
        }
        String typeId = adapter.descriptor().typeId();
        AtomicReference<TrackedMutationCompletion> pending = new AtomicReference<>();
        FlowOperationResult<FlowResourceReference> result = authorizedMutation(typeId, id, "delete", context,
            resolvedContext -> deleteAuthorized(typeId, id, resolvedContext, session, pending, onSuccess, onFailure));
        if (result.success()) {
            TrackedMutationCompletion completion = pending.get();
            if (completion == null) {
                RuntimeException failure = new IllegalStateException("Resource deletion finalization is missing: " + typeId + ":" + id);
                if (onFailure != null) {
                    onFailure.accept(failure);
                }
                return FlowOperationResult.failure("RESOURCE_DELETE_FINALIZATION_MISSING", failure.getMessage(),
                    Map.of("resourceType", typeId, "resourceId", text(id)));
            }
            if (onCancellationReady != null) {
                onCancellationReady.accept(completion::run);
            }
            dispatchTrackedCompletion(completion);
        }
        return result;
    }

    public FlowOperationResult<FlowResourceReference> previewDelete(String typeId, String id, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, id, "delete_preview", context, () -> previewDeleteAuthorized(typeId, id));
    }

    private FlowOperationResult<FlowResourceReference> previewDeleteAuthorized(String typeId, String id) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        if (adapter == null) {
            return unavailable(typeId, "delete");
        }
        if (!adapter.supportedOperations().contains("delete")) {
            return unsupported(typeId, "delete");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        try {
            if (adapter.get(id) == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
            }
            return new FlowOperationResult<>(true, reference(registration, id, true), "", "",
                Map.of("resourceType", typeId, "resourceId", id, "preview", true, "wouldDelete", true, "changed", false));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_DELETE_PREVIEW_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    private FlowOperationResult<FlowResourceReference> deleteAuthorized(String typeId, String id) {
        return deleteAuthorized(typeId, id, FlowResourceMutationContext.system());
    }

    private FlowOperationResult<FlowResourceReference> deleteAuthorized(String typeId, String id,
                                                                         FlowResourceMutationContext context) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        if (adapter == null) {
            return unavailable(typeId, "delete");
        }
        if (!adapter.supportedOperations().contains("delete")) {
            return unsupported(typeId, "delete");
        }
        boolean exact = context != null && context.isExactMutation();
        if (exact && !adapter.supportsAuthoritativeMutationIdentity()) {
            return authoritativeMutationUnavailable(typeId, "delete");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        try {
            FlowResourceAdapter<Object> typedAdapter = adapter(adapter);
            FlowResourceMutationStamp before = exact ? typedAdapter.readMutationStamp(id) : null;
            if (typedAdapter.get(id) == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
            }
            if (exact) {
                validateExactDeletePrecondition(typeId, id, context, before);
                typedAdapter.delete(id, context.exactMutationId(), context.expectedRevision());
                verifyExactStamp(typeId, id, context.expectedRevision() + 1L, context.exactMutationId(), context.expectedPayloadHash(),
                    true, typedAdapter, null);
            } else {
                deleteSerialized(adapter, id);
            }
            RefreshOutcome refresh = refreshAfterDelete(adapter, id);
            notifyDeleted(typeId, id);
            return new FlowOperationResult<>(true, reference(registration, id, false), "", "",
                mutationDetails(typeId, id, false, false, true, adapter, refresh));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_DELETE_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    public List<FlowResourceMetadata> metadata() {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.metadata();
        }
        List<FlowResourceMetadata> result = new ArrayList<>();
        for (ReSyncManagedResource resource : ReSyncResourceCatalog.all()) {
            result.add(metadata(resource, registration(resource.typeId())));
        }
        for (ResourceRegistration registration : registrations.values()) {
            if (ReSyncResourceCatalog.byType(registration.adapter().descriptor().typeId()) == null) {
                result.add(metadata(registration.adapter().descriptor(), registration));
            }
        }
        result.sort(Comparator.comparing(FlowResourceMetadata::getTypeId, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private String resourceId(String typeId, Object value) {
        ResourceRegistration registration = registration(typeId);
        if (registration == null || value == null) {
            return "";
        }
        try {
            return text(adapter(registration.adapter()).id(value));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Resource adapter could not resolve an ID for " + typeId, exception);
        }
    }

    private List<FlowResourceKey> duplicateResourceKeys(String typeId, String sourceId, String targetId,
                                                        FlowResourceMutationContext context) {
        List<FlowResourceKey> keys = new ArrayList<>(resourceKeys(typeId, sourceId, targetId));
        ResourceRegistration registration = registration(typeId);
        if (context == null || !context.isExactMutation() || registration == null
            || !registration.adapter().supportsAggregateCreate() || context.lease() == null) {
            return keys;
        }
        context.lease().keys().stream()
            .filter(key -> ReSyncResourceCatalog.PROJECT_METADATA.equals(key.typeId()))
            .forEach(keys::add);
        return keys;
    }

    private void duplicateAggregate(String typeId, String targetId, Object copy,
                                    FlowResourceMutationContext context, FlowResourceAdapter<Object> adapter) {
        List<FlowResourceKey> metadataKeys = context.lease().keys().stream()
            .filter(key -> ReSyncResourceCatalog.PROJECT_METADATA.equals(key.typeId()))
            .toList();
        if (metadataKeys.size() != 1) {
            throw new IllegalStateException("Aggregate duplicate requires one exact project metadata authority key");
        }
        ServerId serverId = ServerId.parseCanonicalText(metadataKeys.getFirst().resourceId());
        ServerResourceLocator resource = new ServerResourceLocator(serverId,
            ContractRef.of(CORE_GRAPH_OWNER, ResourceTypeId.of(typeId)), targetId);
        FlowResourceMutationContext createContext = new FlowResourceMutationContext(context.source(), context.flowId(),
            context.nodeId(), context.actor(), context.lease(), context.exactMutationId(), 0L,
            context.expectedPayloadHash());
        String path = ReSyncResourceCatalog.defaultFolder(typeId) + '/' + AssetFileFormat.idOnlyFileName(targetId);
        ResourcePresentationIntent presentation = new ResourcePresentationIntent(targetId, path, 0);
        AggregateResourceCreateStorage.Result result = createAdapterAggregate(resource, copy, createContext, presentation);
        requireAggregateResult(resource, createContext, result);
        if (!result.primary().stamp().equals(adapter.readMutationStamp(targetId))) {
            throw new IllegalStateException("Aggregate duplicate result does not match authoritative target state");
        }
    }

    private FlowOperationResult<FlowResourceReference> deleteAuthorized(
        String typeId, String id, FlowResourceMutationContext context, Session session,
        AtomicReference<TrackedMutationCompletion> pending, Runnable onSuccess,
        Consumer<RuntimeException> onFailure) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        if (adapter == null) {
            return unavailable(typeId, "delete");
        }
        if (!adapter.supportedOperations().contains("delete")) {
            return unsupported(typeId, "delete");
        }
        boolean exact = context != null && context.isExactMutation();
        if (exact && !adapter.supportsAuthoritativeMutationIdentity()) {
            return authoritativeMutationUnavailable(typeId, "delete");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        try {
            FlowResourceAdapter<Object> typedAdapter = adapter(adapter);
            FlowResourceMutationStamp before = exact ? typedAdapter.readMutationStamp(id) : null;
            if (typedAdapter.get(id) == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id,
                    Map.of("resourceType", typeId, "resourceId", id));
            }
            if (exact) {
                validateExactDeletePrecondition(typeId, id, context, before);
                typedAdapter.delete(id, context.exactMutationId(), context.expectedRevision());
                verifyExactStamp(typeId, id, context.expectedRevision() + 1L, context.exactMutationId(),
                    context.expectedPayloadHash(), true, typedAdapter, null);
            } else {
                deleteSerialized(adapter, id);
            }
            FlowResourceMutationLease lease = currentMutationLease.get();
            if (lease == null || !lease.owns(typeId, id)) {
                throw new IllegalStateException("Resource deletion lease is missing: " + typeId + ":" + id);
            }
            FlowResourceKey key = new FlowResourceKey(typeId, id);
            TrackedMutationCompletion completion = createDurableCompletion(key, lease,
                () -> finalizeSessionDelete(session, adapter, id), ignored -> {
                }, onSuccess, onFailure);
            pending.set(completion);
            Map<String, Object> details = new LinkedHashMap<>(
                mutationDetails(typeId, id, false, false, true, adapter, new RefreshOutcome(false, "")));
            details.put("completionPending", true);
            return new FlowOperationResult<>(true, reference(registration, id, false), "", "", Map.copyOf(details));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_DELETE_FAILED", failureMessage(exception),
                Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    private void finalizeSessionDelete(Session session, FlowResourceAdapter<?> adapter, String id) {
        RuntimeException failure = null;
        try {
            adapter.afterDelete(session, id);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            changeListener.accept(adapter.catalogSource());
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        RuntimeException publicationFailure = publishSessionDeleted(session, adapter.descriptor().typeId(), id);
        if (publicationFailure != null) {
            failure = appendFailure(failure, publicationFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private List<FlowResourceKey> resourceKeys(String typeId, String... resourceIds) {
        if (typeId == null || typeId.isBlank() || resourceIds == null || resourceIds.length == 0) {
            return List.of();
        }
        List<FlowResourceKey> keys = new ArrayList<>();
        for (String resourceId : resourceIds) {
            if (resourceId != null && !resourceId.isBlank()) {
                keys.add(new FlowResourceKey(typeId, resourceId));
            }
        }
        return keys;
    }

    private <T> FlowOperationResult<T> authorizedMutation(String typeId, String resourceId, String operation, FlowResourceMutationContext context,
                                                           Supplier<FlowOperationResult<T>> action) {
        return authorizedMutation(typeId, resourceId, operation, context, resourceKeys(typeId, resourceId), ignored -> action.get());
    }

    private <T> FlowOperationResult<T> authorizedMutation(String typeId, String resourceId, String operation,
                                                           FlowResourceMutationContext context,
                                                           Function<FlowResourceMutationContext, FlowOperationResult<T>> action) {
        return authorizedMutation(typeId, resourceId, operation, context, resourceKeys(typeId, resourceId), action);
    }

    private <T> FlowOperationResult<T> authorizedMutation(String typeId, String resourceId, String operation,
                                                           FlowResourceMutationContext context, Collection<FlowResourceKey> keys,
                                                           Supplier<FlowOperationResult<T>> action) {
        return authorizedMutation(typeId, resourceId, operation, context, keys, ignored -> action.get());
    }

    private <T> FlowOperationResult<T> authorizedMutation(String typeId, String resourceId, String operation,
                                                           FlowResourceMutationContext context, Collection<FlowResourceKey> keys,
                                                           Function<FlowResourceMutationContext, FlowOperationResult<T>> action) {
        long started = TemporaryLifecycleDiagnostics.start();
        FlowResourceMutationContext resolvedContext = context != null ? context : FlowResourceMutationContext.system();
        Map<String, Object> diagnosticIdentity = TemporaryLifecycleDiagnostics.identity(null,
            text(typeId) + ":" + text(resourceId), resolvedContext.mutationId(), null, null,
            resolvedContext.isExactMutation() ? resolvedContext.expectedRevision() : null, null, null);
        TemporaryLifecycleDiagnostics.event("registry_mutation_admission", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", operation, "keyCount",
                keys == null ? 0 : keys.size(), "source", resolvedContext.source()));
        FlowOperationResult<T> result;
        if (!authorizationPolicy.authorize(resolvedContext, operation, text(typeId), text(resourceId))) {
            result = FlowOperationResult.failure("RESOURCE_AUTHORIZATION_DENIED", "Resource operation is not authorized",
                Map.of("operation", operation, "resourceType", text(typeId), "resourceId", text(resourceId), "source", resolvedContext.source()));
        } else if (resolvedContext.isExactMutation() && !validExactContext(resolvedContext, keys)) {
            result = FlowOperationResult.failure("RESOURCE_MUTATION_EXACT_CONTEXT_INVALID",
                "The exact resource mutation context does not match its retained lease",
                Map.of("operation", operation, "resourceType", text(typeId), "resourceId", text(resourceId),
                    "mutationId", resolvedContext.mutationId(), "expectedRevision", resolvedContext.expectedRevision()));
        } else {
            LeaseResolution resolution = resolveLease(resolvedContext, keys);
            if (hasDuplicateKeys(keys)) {
                result = FlowOperationResult.failure("RESOURCE_MUTATION_INVALID_INPUT", "Resource mutation keys must be unique",
                    Map.of("operation", operation, "resourceType", text(typeId), "resourceId", text(resourceId), "keys", List.copyOf(keys)));
            } else if (resolution.continuationMissing()) {
                result = FlowOperationResult.failure("RESOURCE_MUTATION_CONTINUATION_MISSING", "The resource mutation continuation is missing",
                    Map.of("operation", operation, "resourceType", text(typeId), "resourceId", text(resourceId),
                        "mutationId", resolvedContext.mutationId(), "keys", List.copyOf(keys)));
            } else if (resolution.lease() == null && !resolution.alreadyOwned() && !keys.isEmpty()) {
                result = FlowOperationResult.failure("RESOURCE_MUTATION_IN_PROGRESS", "Resource mutation is already admitted",
                    Map.of("operation", operation, "resourceType", text(typeId), "resourceId", text(resourceId),
                        "mutationId", resolvedContext.mutationId(), "keys", List.copyOf(keys)));
            } else {
                try {
                    AtomicReference<FlowOperationResult<T>> actionResult = new AtomicReference<>();
                    if (resolution.lease() != null) {
                        runWithLease(resolution.lease(), () -> actionResult.set(action.apply(resolvedContext)));
                    } else {
                        actionResult.set(action.apply(resolvedContext));
                    }
                    result = actionResult.get();
                } catch (RuntimeException exception) {
                    result = FlowOperationResult.failure("RESOURCE_" + operation.toUpperCase(Locale.ROOT) + "_FAILED",
                        failureMessage(exception), Map.of("resourceType", text(typeId), "resourceId", text(resourceId)));
                } finally {
                    if (resolution.acquired() || resolution.retained()) {
                        resolution.lease().close();
                    }
                }
            }
        }
        appendAudit(new FlowResourceAuditRecord(System.currentTimeMillis(), operation, typeId, resourceId, resolvedContext.source(),
            resolvedContext.flowId(), resolvedContext.nodeId(), resolvedContext.actor(), result.success(), result.errorCode(), result.details()));
        TemporaryLifecycleDiagnostics.event("registry_mutation_settlement", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", operation,
                "outcome", result.success() ? "success" : "failure", "errorCode", result.errorCode()));
        return result;
    }

    private LeaseResolution resolveLease(FlowResourceMutationContext context, Collection<FlowResourceKey> keys) {
        List<FlowResourceKey> requested = keys != null ? List.copyOf(keys) : List.of();
        if (requested.isEmpty()) {
            return new LeaseResolution(null, false, false, false, false);
        }
        if (context != null && context.lease() != null) {
            if (context.lease().admission() != mutationAdmission || !context.lease().ownsAll(requested)) {
                return new LeaseResolution(null, false, false, true, false);
            }
            Optional<FlowResourceMutationLease> ownedLease = mutationAdmission.tryContinue(requested, context.lease().mutationId());
            if (ownedLease.isPresent()) {
                return new LeaseResolution(ownedLease.get(), false, true, false, true);
            }
            return new LeaseResolution(null, false, false, true, false);
        }
        if (context != null && context.hasMutationId()) {
            Optional<FlowResourceMutationLease> ownedLease = mutationAdmission.tryContinue(requested, context.mutationId());
            if (ownedLease.isPresent()) {
                return new LeaseResolution(ownedLease.get(), false, true, false, true);
            }
            return new LeaseResolution(null, false, false, true, false);
        }
        if (context != null && context.isContinuation()) {
            return new LeaseResolution(null, false, false, true, false);
        }
        FlowResourceMutationLease current = currentMutationLease.get();
        if (current != null && current.admission() == mutationAdmission) {
            Optional<FlowResourceMutationLease> ownedLease = mutationAdmission.tryContinue(requested, current.mutationId());
            if (ownedLease.isPresent()) {
                return new LeaseResolution(ownedLease.get(), false, true, false, true);
            }
        }
        Optional<FlowResourceMutationLease> lease = mutationAdmission.tryAcquire(requested,
            FlowResourceMutationAdmission.AdmissionKind.NEW, UUID.randomUUID().toString());
        return lease.map(value -> new LeaseResolution(value, true, false, false, false))
            .orElseGet(() -> new LeaseResolution(null, false, false, false, false));
    }

    private boolean validExactContext(FlowResourceMutationContext context, Collection<FlowResourceKey> keys) {
        if (context == null || !context.isExactMutation() || context.lease() == null || context.exactMutationId() == null
            || context.expectedRevision() < 0L || context.mutationId().isBlank()
            || !context.mutationId().equals(context.exactMutationId().toString())
            || !context.lease().mutationId().equals(context.exactMutationId().toString())
            || context.lease().admission() != mutationAdmission) {
            return false;
        }
        return keys != null && !keys.isEmpty() && context.lease().ownsAll(keys);
    }

    private void validateExactPersistPrecondition(String typeId, String id, String operation,
                                                  ExistencePolicy existencePolicy,
                                                  FlowResourceMutationContext context,
                                                  FlowResourceMutationStamp before, boolean existed) {
        long expectedRevision = context.expectedRevision();
        if ("create".equals(operation)) {
            if (existed || before == null && expectedRevision != 0L
                || before != null && (!before.deleted() || !typeId.equals(before.type())
                    || !id.equals(before.id()) || before.revision() != expectedRevision)) {
                throw new IllegalStateException("The exact create precondition does not match " + typeId + ":" + id);
            }
            return;
        }
        if (before == null || before.deleted() || !typeId.equals(before.type()) || !id.equals(before.id())
            || before.revision() != expectedRevision || !existed) {
            throw new IllegalStateException("The exact resource precondition does not match " + typeId + ":" + id);
        }
        if (existencePolicy == ExistencePolicy.MISSING) {
            throw new IllegalStateException("The exact create precondition does not match " + typeId + ":" + id);
        }
    }

    private void validateExactDeletePrecondition(String typeId, String id, FlowResourceMutationContext context,
                                                 FlowResourceMutationStamp before) {
        if (before == null || before.deleted() || !typeId.equals(before.type()) || !id.equals(before.id())
            || before.revision() != context.expectedRevision()) {
            throw new IllegalStateException("The exact delete precondition does not match " + typeId + ":" + id);
        }
    }

    private void validateExactDeleteOrActivationPrecondition(String typeId, String id,
                                                              FlowResourceMutationContext context,
                                                              FlowResourceMutationStamp before) {
        validateExactDeletePrecondition(typeId, id, context, before);
    }

    private void validateExactDuplicatePrecondition(String typeId, String sourceId, String targetId,
                                                    FlowResourceMutationContext context,
                                                    FlowResourceMutationStamp sourceStamp,
                                                    FlowResourceMutationStamp targetStamp) {
        if (sourceStamp == null || sourceStamp.deleted() || !typeId.equals(sourceStamp.type()) || !sourceId.equals(sourceStamp.id())
            || sourceStamp.revision() != context.expectedRevision() || targetStamp != null) {
            throw new IllegalStateException("The exact duplicate precondition does not match " + typeId + ":" + sourceId + " -> " + targetId);
        }
    }

    private String expectedPayloadHash(FlowResourceMutationContext context) {
        if (context == null || !context.isExactMutation() || context.expectedPayloadHash() == null
            || context.expectedPayloadHash().isBlank()) {
            throw new IllegalStateException("Exact resource mutation payload hash is required");
        }
        return context.expectedPayloadHash();
    }

    private void verifyExactStamp(String typeId, String id, long expectedRevision, UUID mutationId,
                                  String expectedHash, boolean deleted, FlowResourceAdapter<Object> adapter,
                                  Object value) {
        FlowResourceMutationStamp stamp = adapter.readMutationStamp(id);
        String hash = expectedHash != null ? expectedHash : "";
        if (stamp == null || !typeId.equals(stamp.type()) || !id.equals(stamp.id())
            || stamp.revision() != expectedRevision || !mutationId.equals(stamp.mutationId())
            || !hash.equals(stamp.payloadHash()) || stamp.deleted() != deleted) {
            throw new IllegalStateException("Authoritative resource mutation identity verification failed for " + typeId + ":" + id);
        }
    }

    private boolean sameStamp(FlowResourceMutationStamp first, FlowResourceMutationStamp second) {
        return first != null && first.equals(second);
    }

    private FlowOperationResult<FlowResourceReference> authoritativeMutationUnavailable(String typeId, String operation) {
        return FlowOperationResult.failure("RESOURCE_OPERATION_UNSUPPORTED",
            FlowResourceAdapter.AUTHORITATIVE_MUTATION_IDENTITY_UNAVAILABLE,
            Map.of("resourceType", text(typeId), "operation", text(operation), "authoritativeMutationIdentity", false));
    }

    private boolean hasDuplicateKeys(Collection<FlowResourceKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        Set<FlowResourceKey> unique = new HashSet<>();
        for (FlowResourceKey key : keys) {
            if (key == null || !unique.add(key)) {
                return true;
            }
        }
        return false;
    }

    public FlowResourceMetadata metadata(String typeId) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.metadata(typeId);
        }
        if (typeId == null || typeId.isBlank()) {
            return null;
        }
        ResourceRegistration registration = registration(typeId);
        ReSyncManagedResource resource = ReSyncResourceCatalog.byType(typeId);
        if (resource == null && registration != null) {
            resource = registration.adapter().descriptor();
        }
        return resource != null ? metadata(resource, registration) : null;
    }

    private synchronized void appendAudit(FlowResourceAuditRecord record) {
        while (auditRecords.size() >= MAX_AUDIT_RECORDS) {
            auditRecords.removeFirst();
        }
        auditRecords.addLast(record);
    }

    private FlowResourceMetadata metadata(ReSyncManagedResource resource, ResourceRegistration registration) {
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        FlowResourceMetadata metadata = new FlowResourceMetadata();
        metadata.setTypeId(resource.typeId());
        metadata.setDisplayName(resource.displayName());
        metadata.setReferenceType("resource_reference<" + resource.typeId() + ">");
        metadata.setDefaultFolder(resource.defaultFolder());
        metadata.setOwner(registration != null ? registration.owner() : "builtin");
        metadata.setAuthorizationPolicy("trusted_server_flow");
        metadata.setAudited(true);
        metadata.setDurable(adapter != null ? adapter.durable() : resource.jsonStorageSupported());
        metadata.setAvailable(resource.enabled() && adapter != null);
        if (adapter == null) {
            metadata.setIdentityRules("undeclared");
            metadata.setLifecycle("unavailable");
            metadata.setOperations(List.of());
            metadata.setOperationAvailability(unavailableOperationAvailability("No authoritative lifecycle adapter is registered"));
            metadata.setUnavailableReason(resource.enabled() ? "No authoritative lifecycle adapter is registered" : "Resource domain is disabled");
            return metadata;
        }
        metadata.setIdentityRules(adapter.identityRules());
        metadata.setLifecycle(adapter.lifecycle());
        metadata.setCatalogSource(adapter.catalogSource());
        metadata.setOperations(adapter.supportedOperations().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        metadata.setOperationAvailability(operationAvailability(adapter));
        metadata.setAuthoritativeService(adapter.authoritativeService());
        metadata.setChangeEvents(adapter.changeEvents());
        metadata.setActiveRefresh(adapter.activeRefresh());
        return metadata;
    }

    private Map<String, String> operationAvailability(FlowResourceAdapter<?> adapter) {
        Set<String> supported = adapter.supportedOperations();
        Map<String, String> availability = new LinkedHashMap<>();
        for (String operation : STANDARD_OPERATIONS) {
            availability.put(operation, supported.contains(operation) ? "available" : adapter.unsupportedOperationReason(operation));
        }
        return Map.copyOf(availability);
    }

    private Map<String, String> unavailableOperationAvailability(String reason) {
        Map<String, String> availability = new LinkedHashMap<>();
        for (String operation : STANDARD_OPERATIONS) {
            availability.put(operation, reason);
        }
        return Map.copyOf(availability);
    }

    private FlowResourceReference reference(ResourceRegistration registration, String id, boolean available) {
        FlowResourceAdapter<?> adapter = registration.adapter();
        return new FlowResourceReference(adapter.descriptor().typeId(), id, registration.owner(), available,
            Map.of("catalogSource", adapter.catalogSource(), "authoritativeService", adapter.authoritativeService()));
    }

    private <T> FlowOperationResult<T> unavailable(String typeId, String operation) {
        return FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable: " + typeId,
            Map.of("resourceType", typeId != null ? typeId : "", "operation", operation));
    }

    private <T> FlowOperationResult<T> unsupported(String typeId, String operation) {
        ResourceRegistration registration = registration(typeId);
        String reason = registration != null ? registration.adapter().unsupportedOperationReason(operation)
            : "No authoritative lifecycle adapter is registered";
        return FlowOperationResult.failure("RESOURCE_OPERATION_UNSUPPORTED", reason,
            Map.of("resourceType", typeId != null ? typeId : "", "operation", operation != null ? operation : "", "reason", reason));
    }

    private String failureMessage(RuntimeException exception) {
        return exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    private RefreshOutcome refreshAfterSave(FlowResourceAdapter<Object> adapter, Object value) {
        String error = "";
        try {
            FlowResourceKey key = new FlowResourceKey(adapter.descriptor().typeId(), adapter.id(value));
            scheduleTrackedRefresh(key, currentMutationLease.get(), () -> adapter.afterSave(value));
        } catch (RuntimeException exception) {
            error = failureMessage(exception);
        }
        try {
            changeListener.accept(adapter.catalogSource());
        } catch (RuntimeException exception) {
            error = appendError(error, failureMessage(exception));
        }
        return new RefreshOutcome(error.isBlank(), error);
    }

    private RefreshOutcome refreshAfterDelete(FlowResourceAdapter<?> adapter, String id) {
        String error = "";
        try {
            FlowResourceKey key = new FlowResourceKey(adapter.descriptor().typeId(), id);
            scheduleTrackedRefresh(key, currentMutationLease.get(), () -> adapter.afterDelete(id));
        } catch (RuntimeException exception) {
            error = failureMessage(exception);
        }
        try {
            changeListener.accept(adapter.catalogSource());
        } catch (RuntimeException exception) {
            error = appendError(error, failureMessage(exception));
        }
        return new RefreshOutcome(error.isBlank(), error);
    }

    private String appendError(String current, String next) {
        return current == null || current.isBlank() ? next : current + "; " + next;
    }

    private Map<String, Object> mutationDetails(String typeId, String id, boolean created, boolean updated, boolean deleted,
                                                FlowResourceAdapter<?> adapter, RefreshOutcome refresh) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resourceType", typeId);
        details.put("resourceId", id);
        details.put("created", created);
        details.put("updated", updated);
        details.put("deleted", deleted);
        details.put("changed", created || updated || deleted);
        details.put("activeRefresh", adapter.activeRefresh());
        details.put("refreshSucceeded", refresh.succeeded());
        if (!refresh.error().isBlank()) {
            details.put("refreshError", refresh.error());
        }
        return Map.copyOf(details);
    }

    private String normalize(String typeId) {
        return typeId.toLowerCase(Locale.ROOT);
    }

    private ResourceRegistration registration(String typeId) {
        FlowResourceRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.registration(typeId);
        }
        return typeId != null ? registrations.get(normalize(typeId)) : null;
    }

    private FlowResourceRegistry activeRegistry() {
        ExtensionRegistryActivation current = activation;
        return current != null ? current.snapshot().resources() : this;
    }

    public <T> void notifySaved(FlowResourceAdapter<T> adapter, T value) {
        notifySaved(null, adapter, value);
    }

    <T> void notifySaved(Session session, FlowResourceAdapter<T> adapter, T value) {
        if (adapter == null || value == null) {
            return;
        }
        String type;
        String resourceId;
        String payload;
        try {
            type = adapter.descriptor().typeId();
            resourceId = adapter.id(value);
            payload = adapter.serialize(value);
        } catch (RuntimeException exception) {
            Log.warn("Serialize ReSync resource change failed: " + failureMessage(exception));
            return;
        }
        try {
            mutationListener.saved(type, resourceId, payload);
        } catch (RuntimeException exception) {
            Log.warn("Publish ReSync resource change failed: " + failureMessage(exception));
        }
        try {
            if (session != null) {
                commitListener.saved(session, type, resourceId, payload);
            } else {
                commitListener.saved(type, resourceId, payload);
            }
        } catch (RuntimeException exception) {
            Log.warn("Broadcast ReSync resource change failed: " + failureMessage(exception));
        }
    }

    private <T> RuntimeException publishSessionSaved(Session session, FlowResourceAdapter<T> adapter, T value) {
        String type;
        String resourceId;
        String payload;
        try {
            type = adapter.descriptor().typeId();
            resourceId = adapter.id(value);
            payload = adapter.serialize(value);
        } catch (RuntimeException exception) {
            Log.warn("Serialize ReSync resource change failed: " + failureMessage(exception));
            return exception;
        }
        RuntimeException failure = null;
        try {
            mutationListener.saved(type, resourceId, payload);
        } catch (RuntimeException exception) {
            Log.warn("Publish ReSync resource change failed: " + failureMessage(exception));
            failure = appendFailure(failure, exception);
        }
        try {
            if (session != null) {
                commitListener.saved(session, type, resourceId, payload);
            } else {
                commitListener.saved(type, resourceId, payload);
            }
        } catch (RuntimeException exception) {
            Log.warn("Broadcast ReSync resource change failed: " + failureMessage(exception));
            failure = appendFailure(failure, exception);
        }
        return failure;
    }

    public void notifyDeleted(String typeId, String resourceId) {
        notifyDeleted(null, typeId, resourceId);
    }

    void notifyDeleted(Session session, String typeId, String resourceId) {
        try {
            mutationListener.deleted(typeId, resourceId);
        } catch (RuntimeException exception) {
            Log.warn("Publish ReSync resource deletion failed: " + failureMessage(exception));
        }
        try {
            if (session != null) {
                commitListener.deleted(session, typeId, resourceId);
            } else {
                commitListener.deleted(typeId, resourceId);
            }
        } catch (RuntimeException exception) {
            Log.warn("Broadcast ReSync resource deletion failed: " + failureMessage(exception));
        }
    }

    private RuntimeException publishSessionDeleted(Session session, String typeId, String resourceId) {
        RuntimeException failure = null;
        try {
            mutationListener.deleted(typeId, resourceId);
        } catch (RuntimeException exception) {
            Log.warn("Publish ReSync resource deletion failed: " + failureMessage(exception));
            failure = appendFailure(failure, exception);
        }
        try {
            if (session != null) {
                commitListener.deleted(session, typeId, resourceId);
            } else {
                commitListener.deleted(typeId, resourceId);
            }
        } catch (RuntimeException exception) {
            Log.warn("Broadcast ReSync resource deletion failed: " + failureMessage(exception));
            failure = appendFailure(failure, exception);
        }
        return failure;
    }

    private String text(String value) {
        return value != null ? value : "";
    }

    @SuppressWarnings("unchecked")
    private FlowResourceAdapter<Object> adapter(FlowResourceAdapter<?> adapter) {
        return (FlowResourceAdapter<Object>) adapter;
    }

    private record ResourceRegistration(String owner, FlowResourceAdapter<?> adapter) {
    }

    private static final class SessionSaveState {
        private final Session owner;
        private TrackedMutationCompletion completion;
        private Runnable onSuccess;
        private Consumer<RuntimeException> onFailure;
        private SessionSavePhase phase = SessionSavePhase.STORING;
        private boolean finalizationRequested;

        private SessionSaveState(Session owner, Runnable onSuccess, Consumer<RuntimeException> onFailure) {
            this.owner = owner;
            this.onSuccess = onSuccess != null ? onSuccess : () -> {
            };
            this.onFailure = onFailure != null ? onFailure : ignored -> {
            };
        }

        private boolean ownerMatches(Session session) {
            return owner == session;
        }
    }

    private static final class TrackedMutationCompletion {
        private final FlowResourceMutationLease.DeferredCompletion completion;
        private final Consumer<RuntimeException> onSettled;
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile Runnable onSuccess;
        private volatile Consumer<RuntimeException> onFailure;

        private TrackedMutationCompletion(FlowResourceMutationLease.DeferredCompletion completion,
                                          Consumer<RuntimeException> onSettled, Runnable onSuccess,
                                          Consumer<RuntimeException> onFailure) {
            this.completion = completion;
            this.onSettled = onSettled;
            callbacks(onSuccess, onFailure);
        }

        private void callbacks(Runnable onSuccess, Consumer<RuntimeException> onFailure) {
            this.onSuccess = onSuccess != null ? onSuccess : () -> {
            };
            this.onFailure = onFailure != null ? onFailure : ignored -> {
            };
        }

        private void run() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            RuntimeException failure = null;
            try {
                completion.run();
            } catch (RuntimeException exception) {
                failure = exception;
            }
            try {
                onSettled.accept(failure);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
            try {
                if (failure == null) {
                    onSuccess.run();
                } else {
                    onFailure.accept(failure);
                }
            } catch (RuntimeException exception) {
                String message = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
                Log.warn("Complete ReSync resource mutation response failed: " + message);
            }
        }
    }

    private enum SessionSavePhase {
        STORING,
        READY,
        DISPATCHED,
        COMPLETED,
        FAILED
    }

    private record RefreshOutcome(boolean succeeded, String error) {
    }

    private record LeaseResolution(FlowResourceMutationLease lease, boolean acquired, boolean alreadyOwned,
                                   boolean continuationMissing, boolean retained) {
    }

    private enum ExistencePolicy {
        ANY,
        MISSING,
        EXISTING
    }
}
