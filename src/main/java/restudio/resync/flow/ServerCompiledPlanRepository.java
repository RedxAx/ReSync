package restudio.resync.flow;

import restudio.resync.Log;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.graph.CompiledPlanAuthority;
import restudio.resync.flow.graph.CompiledPlanCacheKey;
import restudio.resync.flow.graph.CompiledPlanLease;
import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.FunctionParameter;
import restudio.resync.flow.graph.GraphCompilationException;
import restudio.resync.flow.graph.GraphCompiler;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.server.CoreGraphResourceAuthority;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class ServerCompiledPlanRepository implements CompiledPlanAuthority, AutoCloseable {
    private final CoreGraphResourceAuthority resources;
    private final Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier;
    private final PlanCompiler compiler;
    private final Function<CoreGraphStorageBoundary.Decoded, FunctionSourceDocument> functionDecoder;
    private final Map<CompiledPlanCacheKey, CompletableFuture<PlanEntry>> cache = new ConcurrentHashMap<>();
    private final Map<AdmissionKey, ResidentPlan> residentPlans = new ConcurrentHashMap<>();
    private final Map<ResourceRevisionKey, ResidentPlan> residentIndex = new ConcurrentHashMap<>();
    private final Map<AdmissionKey, CompiledPlanAdmissionException> negativeCache = new ConcurrentHashMap<>();
    private final Map<ServerResourceLocator, MutationCursor> mutations = new ConcurrentHashMap<>();
    private final Map<ServerResourceLocator, Long> resourceEpochs = new ConcurrentHashMap<>();
    private final Map<ServerResourceLocator, Long> pendingReconciliations = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activeLeases = new AtomicInteger();
    private final AtomicLong mutationEpoch = new AtomicLong();
    private final Object lifecycle = new Object();
    private volatile TemplateCompiler templateCompiler;
    private volatile MetadataCompiler metadataCompiler;

    public ServerCompiledPlanRepository(CoreGraphResourceAuthority resources,
                                        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier) {
        this(resources, activationSupplier, new PlanCompiler() {
            @Override
            public CompiledExecutionPlan compile(GraphDocument graph, CatalogSnapshot catalog,
                                                 RuntimeBindingManifest runtimeManifest) {
                return new GraphCompiler(runtimeManifest).compile(graph, catalog);
            }

            @Override
            public CompiledExecutionPlan compile(FunctionSourceDocument source, CatalogSnapshot catalog,
                                                 RuntimeBindingManifest runtimeManifest) {
                return new GraphCompiler(runtimeManifest).compile(source, catalog);
            }
        }, CoreGraphStorageBoundary.Decoded::functionSourceDocument);
    }

    public ServerCompiledPlanRepository(CoreGraphResourceAuthority resources,
                                        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
                                        PlanCompiler compiler) {
        this(resources, activationSupplier, compiler, CoreGraphStorageBoundary.Decoded::functionSourceDocument);
    }

    public ServerCompiledPlanRepository(CoreGraphResourceAuthority resources,
                                        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
                                        PlanCompiler compiler,
                                        Function<CoreGraphStorageBoundary.Decoded, FunctionSourceDocument> functionDecoder) {
        this.resources = Objects.requireNonNull(resources, "Core Graph Resource Authority Is Required");
        this.activationSupplier = Objects.requireNonNull(activationSupplier, "Catalog Runtime Activation Supplier Is Required");
        this.compiler = Objects.requireNonNull(compiler, "Compiled Plan Compiler Is Required");
        this.functionDecoder = Objects.requireNonNull(functionDecoder, "Function Source Decoder Is Required");
    }

    @Override
    public CompiledPlanLease acquire(ExecutionTarget target) {
        Objects.requireNonNull(target, "Execution Target Is Required");
        AdmissionKey admissionKey = AdmissionKey.from(target);
        synchronized (lifecycle) {
            requireOpen(target.resource());
            CompiledPlanAdmissionException cachedFailure = negativeCache.get(admissionKey);
            if (cachedFailure != null) {
                TemporaryLifecycleDiagnostics.residentPlanMiss();
                throw cachedFailure;
            }
            ResidentPlan resident = residentPlans.get(admissionKey);
            if (resident == null || !admitted(resident)) {
                TemporaryLifecycleDiagnostics.residentPlanMiss();
                throw failure(CompiledPlanAdmissionException.Reason.RESOURCE_MISSING, target.resource(),
                    "No Admitted Resident Compiled Plan Matches The Execution Target");
            }
            target.require(resident.key());
            if (!resident.entries().contains(target.startNodeId())) {
                TemporaryLifecycleDiagnostics.residentPlanMiss();
                throw failure(CompiledPlanAdmissionException.Reason.ENTRY_NODE_MISSING, target.resource(),
                    "Execution Target Start Node Is Not Part Of The Compiled Plan");
            }
            CompiledExecutionRunner.ExecutionTemplate template = resident.templates().get(target.startNodeId());
            if (templateCompiler != null && template == null) {
                TemporaryLifecycleDiagnostics.residentPlanMiss();
                throw failure(CompiledPlanAdmissionException.Reason.ENTRY_NODE_MISSING, target.resource(),
                    "Execution Target Has No Admitted Resident Execution Template");
            }
            activeLeases.incrementAndGet();
            TemporaryLifecycleDiagnostics.residentPlanHit();
            return new ServerCompiledPlanLease(resident.key(), resident.plan(), resident.closure(), template,
                activeLeases::decrementAndGet);
        }
    }

    public void bindTemplateCompiler(TemplateCompiler compiler) {
        Objects.requireNonNull(compiler, "Resident Execution Template Compiler Is Required");
        synchronized (lifecycle) {
            if (closed.get()) {
                throw new IllegalStateException("Compiled Plan Repository Is Closed");
            }
            if (templateCompiler != null && templateCompiler != compiler) {
                throw new IllegalStateException("Resident Execution Template Compiler Is Already Bound");
            }
            if (!residentPlans.isEmpty()) {
                throw new IllegalStateException("Resident Execution Template Compiler Must Be Bound Before Admission");
            }
            templateCompiler = compiler;
        }
    }

    public void bindMetadataCompiler(MetadataCompiler compiler) {
        Objects.requireNonNull(compiler, "Resident Metadata Compiler Is Required");
        synchronized (lifecycle) {
            if (closed.get()) {
                throw new IllegalStateException("Compiled Plan Repository Is Closed");
            }
            if (metadataCompiler != null && metadataCompiler != compiler) {
                throw new IllegalStateException("Resident Metadata Compiler Is Already Bound");
            }
            if (!residentPlans.isEmpty()) {
                throw new IllegalStateException("Resident Metadata Compiler Must Be Bound Before Admission");
            }
            metadataCompiler = compiler;
        }
    }

    public void initialize() {
        long admissionEpoch = mutationEpoch.get();
        LinkedHashMap<AdmissionKey, ResidentPlan> prepared = new LinkedHashMap<>();
        LinkedHashMap<AdmissionKey, CompiledPlanAdmissionException> rejected = new LinkedHashMap<>();
        for (String type : resources.types()) {
            TemporaryLifecycleDiagnostics.filesystemValidationAttempt();
            for (CoreGraphResourceAuthority.CoreGraphResourceState state : resources.list(type)) {
                if (state.deleted() || state.activationState() != ResourceActivationState.ACTIVE) {
                    continue;
                }
                CatalogBinding sourceBinding = graph(state).catalogBinding();
                AdmissionKey admissionKey = new AdmissionKey(state.resource(), state.revision(), sourceBinding);
                try {
                    prepared.put(admissionKey, prepare(state.resource(), state.revision(), sourceBinding, admissionEpoch));
                } catch (CompiledPlanAdmissionException failure) {
                    Log.warn("Compiled plan admission rejected for " + state.resource() + ": " + failure.getMessage(), failure);
                    if (cacheable(failure)) {
                        rejected.put(admissionKey, failure);
                    }
                }
            }
        }
        synchronized (lifecycle) {
            if (closed.get()) {
                throw new IllegalStateException("Compiled Plan Repository Is Closed");
            }
            if (mutationEpoch.get() != admissionEpoch) {
                throw new IllegalStateException("A Core Graph Resource Changed During Initial Plan Admission");
            }
            residentPlans.clear();
            residentPlans.putAll(prepared);
            residentIndex.clear();
            prepared.values().forEach(plan -> residentIndex.put(ResourceRevisionKey.from(plan.key()), plan));
            negativeCache.clear();
            negativeCache.putAll(rejected);
        }
    }

    public boolean replace(CoreGraphMutationEvent event) {
        return replace(event, null);
    }

    public void reconcile(ServerResourceLocator resource) {
        Objects.requireNonNull(resource, "Core Graph Resource Is Required");
        long epoch;
        synchronized (lifecycle) {
            requireOpen(resource);
            epoch = mutationEpoch.incrementAndGet();
            resourceEpochs.put(resource, epoch);
            pendingReconciliations.put(resource, epoch);
        }
        var current = readState(resource);
        synchronized (lifecycle) {
            requireOpen(resource);
            if (!Objects.equals(resourceEpochs.get(resource), epoch)
                || !Objects.equals(pendingReconciliations.get(resource), epoch)) {
                return;
            }
            if (current.isEmpty() || current.get().deleted()) {
                retire(resource);
                pendingReconciliations.remove(resource, epoch);
                TemporaryLifecycleDiagnostics.residentPlanInvalidated();
                return;
            }
            var state = current.get();
            if (!resource.equals(state.resource())) {
                throw failure(CompiledPlanAdmissionException.Reason.RESOURCE_IDENTITY_MISMATCH, resource,
                    "Reconciled Core Graph State Does Not Match Its Typed Resource Locator");
            }
            SourceStamp actual = new SourceStamp(state.revision(), state.payloadChecksum(), state.corePayloadChecksum(),
                state.mutationId(), state.activationState());
            List<SourceStamp> retained = residentPlans.values().stream().map(plan -> plan.stamps().get(resource))
                .filter(Objects::nonNull).toList();
            if (!retained.isEmpty() && retained.stream().allMatch(actual::equals)) {
                pendingReconciliations.remove(resource, epoch);
                return;
            }
        }
        var state = current.orElseThrow();
        replace(CoreGraphMutationEvent.live(resource, state.revision(), state.mutationId(), state.payloadChecksum(),
            state.activationState()), epoch);
    }

    public void refreshProjection(ServerResourceLocator resource) {
        Objects.requireNonNull(resource, "Compiled projection resource is required");
        if (!"custom_content".equals(resource.resourceType().value())) {
            throw new IllegalArgumentException("Only aggregate-owned custom content has a derived Core projection");
        }
        synchronized (lifecycle) {
            requireOpen(resource);
            mutations.remove(resource);
        }
        reconcile(resource);
    }

    private boolean replace(CoreGraphMutationEvent event, Long reconciliationEpoch) {
        Objects.requireNonNull(event, "Core Graph Mutation Event Is Required");
        long admissionEpoch;
        synchronized (lifecycle) {
            if (reconciliationEpoch != null && (!reconciliationEpoch.equals(resourceEpochs.get(event.resource()))
                || !reconciliationEpoch.equals(pendingReconciliations.get(event.resource())))) {
                return false;
            }
            if (!acceptMutation(event) && (reconciliationEpoch == null
                || !MutationCursor.from(event).equals(mutations.get(event.resource())))) {
                return false;
            }
            mutationEpoch.incrementAndGet();
            admissionEpoch = mutationEpoch.get();
            resourceEpochs.put(event.resource(), admissionEpoch);
            retire(event.resource());
            pendingReconciliations.remove(event.resource());
            TemporaryLifecycleDiagnostics.residentPlanInvalidated();
        }
        if (event.deleted() || event.activationState() != ResourceActivationState.ACTIVE) {
            return true;
        }
        ResidentPlan prepared;
        AdmissionKey admissionKey;
        try {
            CatalogRuntimeActivation.ActivationRecord activation = activeActivation(event.resource());
            CatalogBinding activeBinding = binding(activation);
            prepared = prepare(event.resource(), event.revision(), activeBinding, admissionEpoch);
            admissionKey = new AdmissionKey(event.resource(), event.revision(), activeBinding);
        } catch (CompiledPlanAdmissionException failure) {
            Log.warn("Compiled plan admission rejected for " + event.resource() + ": " + failure.getMessage(), failure);
            return true;
        }
        synchronized (lifecycle) {
            requireOpen(event.resource());
            requireFresh(prepared.stamps(), admissionEpoch, event.resource());
            residentPlans.put(admissionKey, prepared);
            residentIndex.put(ResourceRevisionKey.from(prepared.key()), prepared);
            negativeCache.remove(admissionKey);
            TemporaryLifecycleDiagnostics.residentPlanReplaced();
        }
        return true;
    }

    public boolean invalidate(CoreGraphMutationEvent event) {
        Objects.requireNonNull(event, "Core Graph Mutation Event Is Required");
        synchronized (lifecycle) {
            if (!acceptMutation(event)) {
                return false;
            }
            mutationEpoch.incrementAndGet();
            retire(event.resource());
            resourceEpochs.put(event.resource(), mutationEpoch.get());
            pendingReconciliations.remove(event.resource());
            TemporaryLifecycleDiagnostics.residentPlanInvalidated();
            return true;
        }
    }

    public void onMutation(CoreGraphMutationEvent event) {
        replace(event);
    }

    public Optional<ResidentSource> residentSource(ServerResourceLocator resource, long revision) {
        Objects.requireNonNull(resource, "Resident Source Resource Is Required");
        synchronized (lifecycle) {
            requireOpen(resource);
            return Optional.ofNullable(residentIndex.get(new ResourceRevisionKey(resource, revision)))
                .filter(this::admitted)
                .map(plan -> new ResidentSource(plan.source().graph(), plan.source().functionSource(), plan.key(),
                    plan.source().stamp().envelopeChecksum(), plan.source().stamp().payloadChecksum(),
                    plan.source().stamp().mutationId(), plan.source().stamp().activationState()));
        }
    }

    public Optional<ResidentExecution> residentExecution(ServerResourceLocator resource, long revision) {
        Objects.requireNonNull(resource, "Resident Execution Resource Is Required");
        synchronized (lifecycle) {
            requireOpen(resource);
            return Optional.ofNullable(residentIndex.get(new ResourceRevisionKey(resource, revision)))
                .filter(this::admitted)
                .map(plan -> new ResidentExecution(plan.source().graph(), plan.source().functionSource(), plan.key(),
                    plan.source().stamp().envelopeChecksum(), plan.source().stamp().payloadChecksum(),
                    plan.source().stamp().mutationId(), plan.source().stamp().activationState(), plan.metadata(),
                    plan.synchronousEventWindow()));
        }
    }

    public boolean isExecutionAuthorized(CompiledGraphMetadata metadata, long revision, String hash) {
        Objects.requireNonNull(metadata, "Compiled Execution Metadata Is Required");
        synchronized (lifecycle) {
            requireOpen(metadata.resource());
            ResidentPlan plan = residentIndex.get(new ResourceRevisionKey(metadata.resource(), revision));
            return plan != null
                && admitted(plan)
                && plan.source().graph().catalogBinding().equals(metadata.catalogBinding())
                && plan.source().stamp().activationState() == ResourceActivationState.ACTIVE
                && (plan.source().stamp().envelopeChecksum().canonicalText().equals(hash)
                    || plan.source().graph().checksum().canonicalText().equals(hash));
        }
    }

    public Optional<ResidentExecution> residentExecution(ServerResourceLocator resource) {
        Objects.requireNonNull(resource, "Resident Execution Resource Is Required");
        synchronized (lifecycle) {
            requireOpen(resource);
            return residentPlans.values().stream().filter(this::admitted).filter(plan -> plan.key().graph().equals(resource))
                .max(Comparator.comparingLong(plan -> plan.key().graphRevision()))
                .map(plan -> new ResidentExecution(plan.source().graph(), plan.source().functionSource(), plan.key(),
                    plan.source().stamp().envelopeChecksum(), plan.source().stamp().payloadChecksum(),
                    plan.source().stamp().mutationId(), plan.source().stamp().activationState(), plan.metadata(),
                    plan.synchronousEventWindow()));
        }
    }

    private ResidentPlan prepare(ServerResourceLocator resource, long revision, CatalogBinding expectedBinding,
                                 long admissionEpoch) {
        CatalogRuntimeActivation.ActivationRecord activation = activeActivation(resource);
        CatalogBinding activeBinding = binding(activation);
        if (!activeBinding.equals(expectedBinding)) {
            throw failure(CompiledPlanAdmissionException.Reason.CATALOG_BINDING_MISMATCH, resource,
                "Execution Source Does Not Match The Active Catalog Runtime Binding");
        }
        LoadedSource root = load(resource, revision, activeBinding, false);
        PlanEntry rootEntry = cachedOrCompile(key(root.graph()), root, activation);
        ClosureResolution resolution = resolveClosure(rootEntry, activation);
        verifyActivation(rootEntry);
        verifyPinned(resolution.stamps());
        synchronized (lifecycle) {
            requireOpen(resource);
            requireFresh(resolution.stamps(), admissionEpoch, resource);
        }
        CompiledGraphMetadataProvider.Result metadata = prepareMetadata(rootEntry.source());
        return new ResidentPlan(rootEntry.key(), rootEntry.plan(), resolution.closure(), resolution.stamps(), rootEntry.source(),
            rootEntry.source().functionSource() == null ? prepareTemplates(rootEntry.plan()) : Map.of(),
            rootEntry.plan().steps().stream().map(step -> step.nodeId()).collect(Collectors.toUnmodifiableSet()),
            metadata, synchronousEventWindow(rootEntry.plan()) || resolution.closure().values().stream()
                .anyMatch(function -> synchronousEventWindow(function.plan())));
    }

    private boolean admitted(ResidentPlan plan) {
        return plan.stamps().keySet().stream().noneMatch(pendingReconciliations::containsKey);
    }

    private CompiledGraphMetadataProvider.Result prepareMetadata(LoadedSource source) {
        MetadataCompiler factory = metadataCompiler;
        if (factory == null) {
            return null;
        }
        CompiledGraphMetadataProvider.Result result = Objects.requireNonNull(
            factory.compile(source.graph(), source.functionSource()), "Resident Compiled Metadata Is Required");
        if (!result.accepted()) {
            throw failure(CompiledPlanAdmissionException.Reason.COMPILATION_REJECTED, source.graph().resource(),
                "Resident Compiled Metadata Was Rejected");
        }
        return result;
    }

    private static boolean synchronousEventWindow(CompiledExecutionPlan plan) {
        return plan.steps().stream().map(step -> step.definition().canonicalText()).anyMatch(LiveEventScope::requiresWindow);
    }

    private Map<NodeInstanceId, CompiledExecutionRunner.ExecutionTemplate> prepareTemplates(CompiledExecutionPlan plan) {
        TemplateCompiler factory = templateCompiler;
        if (factory == null) {
            return Map.of();
        }
        try {
            LinkedHashMap<NodeInstanceId, CompiledExecutionRunner.ExecutionTemplate> templates = new LinkedHashMap<>();
            Set<NodeInstanceId> incoming = plan.connections().stream().map(connection -> connection.target().nodeId())
                .collect(Collectors.toSet());
            for (var step : plan.steps()) {
                if (!incoming.contains(step.nodeId())) {
                    templates.put(step.nodeId(), Objects.requireNonNull(factory.compile(plan, step.nodeId()),
                        "Resident Execution Template Is Required"));
                }
            }
            return Map.copyOf(templates);
        } catch (CompiledPlanAdmissionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CompiledPlanAdmissionException(CompiledPlanAdmissionException.Reason.COMPILER_FAILURE,
                plan.graph(), "Resident Execution Template Preparation Failed Unexpectedly", failure, false);
        }
    }

    private boolean acceptMutation(CoreGraphMutationEvent event) {
        MutationCursor next = MutationCursor.from(event);
        MutationCursor current = mutations.get(event.resource());
        if (current != null && (current.revision() > next.revision()
            || current.revision() == next.revision() && current.mutationId().equals(next.mutationId()))) {
            return false;
        }
        if (current != null && current.revision() == next.revision()) {
            return false;
        }
        mutations.put(event.resource(), next);
        return true;
    }

    private void requireFresh(Map<ServerResourceLocator, SourceStamp> stamps, long expected, ServerResourceLocator resource) {
        if (stamps.keySet().stream().anyMatch(dependency -> resourceEpochs.getOrDefault(dependency, 0L) > expected)) {
            throw new CompiledPlanAdmissionException(
                CompiledPlanAdmissionException.Reason.RESOURCE_MUTATED_DURING_ADMISSION, resource,
                "A Core Graph Resource Changed During Plan Admission", null, false);
        }
    }

    private static GraphDocument graph(CoreGraphResourceAuthority.CoreGraphResourceState state) {
        CoreGraphStorageBoundary.Decoded decoded = state.envelope();
        GraphDocument graph = decoded.graphDocument();
        return graph != null ? graph : decoded.functionSourceDocument().graph();
    }

    public int cachedPlanCount() {
        return residentPlans.size();
    }

    public int negativePlanCount() {
        return negativeCache.size();
    }

    public int activeLeaseCount() {
        return activeLeases.get();
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed.compareAndSet(false, true)) {
                cache.clear();
                residentPlans.clear();
                residentIndex.clear();
                negativeCache.clear();
                mutations.clear();
                resourceEpochs.clear();
                pendingReconciliations.clear();
            }
        }
    }

    private PlanEntry cachedOrCompile(CompiledPlanCacheKey key, LoadedSource source,
                                      CatalogRuntimeActivation.ActivationRecord activation) {
        CompletableFuture<PlanEntry> created = new CompletableFuture<>();
        CompletableFuture<PlanEntry> existing = cache.putIfAbsent(key, created);
        CompletableFuture<PlanEntry> selected = existing != null ? existing : created;
        if (existing == null) {
            try {
                CompiledExecutionPlan plan = compile(source, activation);
                key.require(plan);
                PlanEntry entry = new PlanEntry(key, plan, source);
                created.complete(entry);
            } catch (CompiledPlanAdmissionException failure) {
                if (!failure.deterministic()) {
                    cache.remove(key, created);
                }
                created.completeExceptionally(failure);
            } catch (RuntimeException failure) {
                cache.remove(key, created);
                created.completeExceptionally(new CompiledPlanAdmissionException(
                    CompiledPlanAdmissionException.Reason.COMPILER_FAILURE, key.graph(),
                    "Compiled Plan Creation Failed Unexpectedly", failure, false));
            }
        }
        try {
            return selected.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw failure;
        }
    }

    private ClosureResolution resolveClosure(PlanEntry root, CatalogRuntimeActivation.ActivationRecord activation) {
        LinkedHashMap<ServerResourceLocator, LoadedSource> loaded = new LinkedHashMap<>();
        LinkedHashMap<ServerResourceLocator, ServerCompiledPlanLease.PinnedPlan> closure = new LinkedHashMap<>();
        loaded.put(root.source().graph().resource(), root.source());
        if (root.source().functionSource() != null) {
            closure.put(root.key().graph(), new ServerCompiledPlanLease.PinnedPlan(root.key(), root.plan()));
        }
        LinkedHashSet<ServerResourceLocator> path = new LinkedHashSet<>();
        path.add(root.key().graph());
        collectFunctions(root.source().graph(), activation, loaded, closure, path);
        LinkedHashMap<ServerResourceLocator, SourceStamp> stamps = new LinkedHashMap<>();
        loaded.forEach((resource, source) -> stamps.put(resource, source.stamp()));
        return new ClosureResolution(Map.copyOf(closure), Map.copyOf(stamps));
    }

    private void collectFunctions(GraphDocument graph, CatalogRuntimeActivation.ActivationRecord activation,
                                  Map<ServerResourceLocator, LoadedSource> loaded,
                                  Map<ServerResourceLocator, ServerCompiledPlanLease.PinnedPlan> closure,
                                  Set<ServerResourceLocator> path) {
        List<FunctionBinding> bindings = graph.functions().stream()
            .sorted(Comparator.comparing(binding -> binding.function().canonicalText()))
            .toList();
        for (FunctionBinding binding : bindings) {
            LoadedSource existing = loaded.get(binding.function());
            if (existing != null) {
                if (existing.graph().revision() != binding.revision()) {
                    throw failure(CompiledPlanAdmissionException.Reason.FUNCTION_SOURCE_PROVENANCE_COLLISION,
                        binding.function(), "Function Closure Contains Conflicting Revisions For One Typed Resource");
                }
                requireSignature(binding, existing.functionSource());
                if (path.contains(binding.function())) {
                    throw failure(CompiledPlanAdmissionException.Reason.FUNCTION_DEPENDENCY_CYCLE, binding.function(),
                        "Static Function Dependency Cycle Is Not Executable");
                }
                continue;
            }
            if (path.contains(binding.function())) {
                throw failure(CompiledPlanAdmissionException.Reason.FUNCTION_DEPENDENCY_CYCLE, binding.function(),
                    "Static Function Dependency Cycle Is Not Executable");
            }
            LoadedSource source;
            try {
                source = load(binding.function(), binding.revision(), binding(graph, activation), true);
            } catch (CompiledPlanAdmissionException failure) {
                CompiledPlanAdmissionException.Reason reason = failure.reason() == CompiledPlanAdmissionException.Reason.RESOURCE_MISSING
                    ? CompiledPlanAdmissionException.Reason.FUNCTION_DEPENDENCY_MISSING
                    : CompiledPlanAdmissionException.Reason.FUNCTION_DEPENDENCY_STALE;
                throw new CompiledPlanAdmissionException(reason, binding.function(),
                    "Static Function Dependency Cannot Be Pinned: " + binding.function().canonicalText(), failure,
                    failure.deterministic());
            }
            requireSignature(binding, source.functionSource());
            loaded.put(binding.function(), source);
            CompiledPlanCacheKey key = key(source.graph());
            PlanEntry dependency = cachedOrCompile(key, source, activation);
            closure.put(binding.function(), new ServerCompiledPlanLease.PinnedPlan(key, dependency.plan()));
            path.add(binding.function());
            try {
                collectFunctions(source.graph(), activation, loaded, closure, path);
            } finally {
                path.remove(binding.function());
            }
        }
    }

    private CompiledExecutionPlan compile(LoadedSource source, CatalogRuntimeActivation.ActivationRecord activation) {
        try {
            CompiledExecutionPlan plan = Objects.requireNonNull(
                source.functionSource() == null
                    ? compiler.compile(source.graph(), activation.catalog(), activation.runtimeManifest())
                    : compiler.compile(source.functionSource(), activation.catalog(), activation.runtimeManifest()),
                "Compiled Plan Compiler Returned Null");
            key(source.graph()).require(plan);
            return plan;
        } catch (GraphCompilationException failure) {
            throw new CompiledPlanAdmissionException(CompiledPlanAdmissionException.Reason.COMPILATION_REJECTED,
                source.graph().resource(), "Graph Compilation Was Rejected", failure, true);
        }
    }

    private LoadedSource load(ServerResourceLocator resource, long expectedRevision, CatalogBinding expectedBinding,
                              boolean functionRequired) {
        try {
            if (!resources.available()) {
                throw new CompiledPlanAdmissionException(CompiledPlanAdmissionException.Reason.AUTHORITY_UNAVAILABLE,
                    resource, "Core Graph Resource Authority Is Unavailable", null, false);
            }
        } catch (CompiledPlanAdmissionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CompiledPlanAdmissionException(CompiledPlanAdmissionException.Reason.AUTHORITY_UNAVAILABLE,
                resource, "Core Graph Resource Authority Is Unavailable", failure, false);
        }
        CoreGraphResourceAuthority.CoreGraphResourceState state = readState(resource).orElseThrow(() -> failure(
            CompiledPlanAdmissionException.Reason.RESOURCE_MISSING, resource,
            "Core Graph Resource Does Not Exist"));
        if (!resource.equals(state.resource())) {
            throw failure(CompiledPlanAdmissionException.Reason.RESOURCE_IDENTITY_MISMATCH, resource,
                "Core Graph Authority Returned A Different Typed Resource Locator");
        }
        if (state.deleted()) {
            throw failure(CompiledPlanAdmissionException.Reason.RESOURCE_MISSING, resource,
                "Core Graph Resource Is Deleted");
        }
        if (state.activationState() != ResourceActivationState.ACTIVE) {
            throw failure(CompiledPlanAdmissionException.Reason.RESOURCE_INACTIVE, resource,
                "Core Graph Resource Is Not Active");
        }
        if (state.revision() != expectedRevision || state.envelope().envelope().assetRevision() != expectedRevision) {
            throw failure(CompiledPlanAdmissionException.Reason.REVISION_MISMATCH, resource,
                "Core Graph Revision Does Not Match The Execution Target");
        }
        CoreGraphStorageBoundary.Decoded decoded = state.envelope();
        FunctionSourceDocument functionSource = null;
        GraphDocument graph;
        if ("function".equals(resource.resourceType().value())) {
            try {
                functionSource = functionDecoder.apply(decoded);
            } catch (RuntimeException failure) {
                throw new CompiledPlanAdmissionException(CompiledPlanAdmissionException.Reason.FUNCTION_SOURCE_REQUIRED,
                    resource, "Function Resource Source Could Not Be Decoded", failure, true);
            }
            if (functionSource == null) {
                throw failure(CompiledPlanAdmissionException.Reason.FUNCTION_SOURCE_REQUIRED, resource,
                    "Function Resources Require Authoritative Function Source Provenance");
            }
            graph = functionSource.graph();
            if (!functionSource.signature().function().resource().equals(resource)
                || functionSource.signature().revision().value() != expectedRevision) {
                throw failure(CompiledPlanAdmissionException.Reason.FUNCTION_SOURCE_REQUIRED, resource,
                    "Function Source Provenance Does Not Match Its Typed Resource And Revision");
            }
        } else {
            if (functionRequired) {
                throw failure(CompiledPlanAdmissionException.Reason.FUNCTION_SOURCE_REQUIRED, resource,
                    "Static Function Dependencies Must Use The Function Resource Type");
            }
            graph = decoded.graphDocument();
            if (graph == null) {
                throw failure(CompiledPlanAdmissionException.Reason.RESOURCE_IDENTITY_MISMATCH, resource,
                    "Flow And Command Resources Require A Graph Document");
            }
        }
        if (!resource.equals(graph.resource())) {
            throw failure(CompiledPlanAdmissionException.Reason.RESOURCE_IDENTITY_MISMATCH, resource,
                "Core Graph Payload Does Not Match Its Typed Resource Locator");
        }
        if (graph.revision() != expectedRevision) {
            throw failure(CompiledPlanAdmissionException.Reason.REVISION_MISMATCH, resource,
                "Core Graph Document Revision Does Not Match Its Envelope");
        }
        if (!expectedBinding.equals(graph.catalogBinding())) {
            throw failure(CompiledPlanAdmissionException.Reason.CATALOG_BINDING_MISMATCH, resource,
                "Core Graph Document Does Not Match The Active Catalog Runtime Binding");
        }
        ContentHash payloadChecksum = functionSource != null ? functionSource.checksum() : graph.checksum();
        if (!payloadChecksum.equals(state.corePayloadChecksum())) {
            throw failure(CompiledPlanAdmissionException.Reason.PAYLOAD_CHECKSUM_MISMATCH, resource,
                "Core Graph Payload Checksum Does Not Match Its Authoritative State");
        }
        return new LoadedSource(graph, functionSource,
            new SourceStamp(state.revision(), state.payloadChecksum(), payloadChecksum, state.mutationId(), state.activationState()));
    }

    private void verifyPinned(Map<ServerResourceLocator, SourceStamp> stamps) {
        stamps.forEach((resource, expected) -> {
            CoreGraphResourceAuthority.CoreGraphResourceState state = readState(resource).orElseThrow(() ->
                failure(CompiledPlanAdmissionException.Reason.RESOURCE_MISSING, resource,
                    "Compiled Plan Resource Was Retired During Admission"));
            if (!resource.equals(state.resource())) {
                throw failure(CompiledPlanAdmissionException.Reason.RESOURCE_IDENTITY_MISMATCH, resource,
                    "Final Core Graph State Does Not Match Its Typed Resource Locator");
            }
            if (state.deleted()) {
                throw failure(CompiledPlanAdmissionException.Reason.FUNCTION_DEPENDENCY_STALE, resource,
                    "Compiled Plan Resource Changed During Admission");
            }
            SourceStamp actual = new SourceStamp(state.revision(), state.payloadChecksum(), state.corePayloadChecksum(),
                state.mutationId(), state.activationState());
            if (!expected.equals(actual)) {
                throw failure(CompiledPlanAdmissionException.Reason.FUNCTION_DEPENDENCY_STALE, resource,
                    "Compiled Plan Resource Changed During Admission");
            }
        });
    }

    private void verifyActivation(PlanEntry entry) {
        CatalogBinding active = binding(activeActivation(entry.key().graph()));
        if (!entry.key().catalogBinding().equals(active)) {
            cache.remove(entry.key());
            throw failure(CompiledPlanAdmissionException.Reason.CATALOG_BINDING_MISMATCH, entry.key().graph(),
                "Catalog Runtime Activation Changed During Plan Admission");
        }
    }

    private void retire(ServerResourceLocator resource) {
        residentPlans.entrySet().removeIf(entry -> entry.getValue().stamps().containsKey(resource));
        residentIndex.entrySet().removeIf(entry -> entry.getValue().stamps().containsKey(resource));
        Set<ServerResourceLocator> retired = new LinkedHashSet<>();
        retired.add(resource);
        boolean expanded;
        do {
            expanded = false;
            for (Map.Entry<CompiledPlanCacheKey, CompletableFuture<PlanEntry>> entry : cache.entrySet()) {
                CompletableFuture<PlanEntry> future = entry.getValue();
                if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
                    continue;
                }
                PlanEntry plan;
                try {
                    plan = future.getNow(null);
                } catch (CompletionException failure) {
                    continue;
                }
                if (plan != null && plan.source().graph().functions().stream()
                    .map(FunctionBinding::function)
                    .anyMatch(retired::contains) && retired.add(entry.getKey().graph())) {
                    expanded = true;
                }
            }
        } while (expanded);
        cache.keySet().removeIf(key -> retired.contains(key.graph()));
        negativeCache.clear();
    }

    private CatalogRuntimeActivation.ActivationRecord activeActivation(ServerResourceLocator resource) {
        try {
            return Objects.requireNonNull(activationSupplier.get(), "Active Catalog Runtime Activation Is Required");
        } catch (RuntimeException failure) {
            throw new CompiledPlanAdmissionException(CompiledPlanAdmissionException.Reason.AUTHORITY_UNAVAILABLE,
                resource, "Active Catalog Runtime Activation Is Unavailable", failure, false);
        }
    }

    private Optional<CoreGraphResourceAuthority.CoreGraphResourceState> readState(ServerResourceLocator resource) {
        TemporaryLifecycleDiagnostics.filesystemValidationAttempt();
        try {
            return Objects.requireNonNull(resources.state(resource),
                "Core Graph Resource Authority State Result Is Required");
        } catch (RuntimeException failure) {
            throw new CompiledPlanAdmissionException(CompiledPlanAdmissionException.Reason.AUTHORITY_UNAVAILABLE,
                resource, "Core Graph Resource Could Not Be Read", failure, false);
        }
    }

    private void requireOpen(ServerResourceLocator resource) {
        if (closed.get()) {
            throw failure(CompiledPlanAdmissionException.Reason.REPOSITORY_CLOSED, resource,
                "Compiled Plan Repository Is Closed");
        }
    }

    private static void requireSignature(FunctionBinding binding, FunctionSourceDocument source) {
        if (source == null || !binding.function().equals(source.signature().function().resource())
            || binding.revision() != source.signature().revision().value()
            || !matches(binding.inputs(), source.signature().inputs())
            || !matches(binding.outputs(), source.signature().outputs())) {
            throw failure(CompiledPlanAdmissionException.Reason.FUNCTION_SOURCE_PROVENANCE_COLLISION,
                binding.function(), "Function Binding Does Not Match Its Authoritative Source Signature");
        }
    }

    private static boolean matches(List<FunctionParameter> binding, List<FunctionParameterContract> source) {
        if (binding.size() != source.size()) {
            return false;
        }
        for (int index = 0; index < binding.size(); index++) {
            FunctionParameter left = binding.get(index);
            FunctionParameterContract right = source.get(index);
            if (!left.parameterId().equals(right.id()) || !left.type().equals(right.type())) {
                return false;
            }
        }
        return true;
    }

    private static CatalogBinding binding(CatalogRuntimeActivation.ActivationRecord activation) {
        return new CatalogBinding(activation.catalog().generation(), activation.catalog().contentChecksum(),
            activation.runtime().bindingManifestHash());
    }

    private static CatalogBinding binding(GraphDocument graph, CatalogRuntimeActivation.ActivationRecord activation) {
        CatalogBinding active = binding(activation);
        if (!active.equals(graph.catalogBinding())) {
            throw failure(CompiledPlanAdmissionException.Reason.CATALOG_BINDING_MISMATCH, graph.resource(),
                "Graph Does Not Match The Captured Catalog Runtime Activation");
        }
        return active;
    }

    private static CompiledPlanCacheKey key(GraphDocument graph) {
        return new CompiledPlanCacheKey(graph.resource(), graph.revision(), graph.checksum(), graph.catalogBinding());
    }

    private static CompiledPlanAdmissionException failure(CompiledPlanAdmissionException.Reason reason,
                                                          ServerResourceLocator resource, String message) {
        return new CompiledPlanAdmissionException(reason, resource, message);
    }

    private static boolean cacheable(CompiledPlanAdmissionException failure) {
        return failure.deterministic()
            && failure.reason() != CompiledPlanAdmissionException.Reason.ENTRY_NODE_MISSING
            && failure.reason() != CompiledPlanAdmissionException.Reason.REPOSITORY_CLOSED
            && failure.reason() != CompiledPlanAdmissionException.Reason.AUTHORITY_UNAVAILABLE
            && failure.reason() != CompiledPlanAdmissionException.Reason.CATALOG_BINDING_MISMATCH;
    }

    @FunctionalInterface
    public interface PlanCompiler {
        CompiledExecutionPlan compile(GraphDocument graph, CatalogSnapshot catalog, RuntimeBindingManifest runtimeManifest);

        default CompiledExecutionPlan compile(FunctionSourceDocument source, CatalogSnapshot catalog,
                                              RuntimeBindingManifest runtimeManifest) {
            return compile(source.graph(), catalog, runtimeManifest);
        }
    }

    private record LoadedSource(GraphDocument graph, FunctionSourceDocument functionSource, SourceStamp stamp) {
        private LoadedSource {
            graph = Objects.requireNonNull(graph, "Loaded Graph Document Is Required");
            stamp = Objects.requireNonNull(stamp, "Loaded Graph Source Stamp Is Required");
        }
    }

    private record SourceStamp(long revision, ContentHash envelopeChecksum, ContentHash payloadChecksum,
                               UUID mutationId, ResourceActivationState activationState) {
        private SourceStamp {
            envelopeChecksum = Objects.requireNonNull(envelopeChecksum, "Envelope Checksum Is Required");
            payloadChecksum = Objects.requireNonNull(payloadChecksum, "Payload Checksum Is Required");
            mutationId = Objects.requireNonNull(mutationId, "Mutation ID Is Required");
            activationState = Objects.requireNonNull(activationState, "Activation State Is Required");
        }
    }

    private record PlanEntry(CompiledPlanCacheKey key, CompiledExecutionPlan plan, LoadedSource source) {
        private PlanEntry {
            key = Objects.requireNonNull(key, "Compiled Plan Cache Key Is Required");
            plan = Objects.requireNonNull(plan, "Compiled Execution Plan Is Required");
            source = Objects.requireNonNull(source, "Compiled Plan Source Is Required");
            key.require(plan);
        }
    }

    private record ClosureResolution(Map<ServerResourceLocator, ServerCompiledPlanLease.PinnedPlan> closure,
                                     Map<ServerResourceLocator, SourceStamp> stamps) {
        private ClosureResolution {
            closure = Map.copyOf(Objects.requireNonNull(closure, "Compiled Function Closure Is Required"));
            stamps = Map.copyOf(Objects.requireNonNull(stamps, "Compiled Source Stamps Are Required"));
        }
    }

    private record ResidentPlan(CompiledPlanCacheKey key, CompiledExecutionPlan plan,
                                Map<ServerResourceLocator, ServerCompiledPlanLease.PinnedPlan> closure,
                                Map<ServerResourceLocator, SourceStamp> stamps, LoadedSource source,
                                Map<NodeInstanceId, CompiledExecutionRunner.ExecutionTemplate> templates,
                                Set<NodeInstanceId> entries,
                                CompiledGraphMetadataProvider.Result metadata, boolean synchronousEventWindow) {
        private ResidentPlan {
            key = Objects.requireNonNull(key, "Resident Compiled Plan Key Is Required");
            plan = Objects.requireNonNull(plan, "Resident Compiled Plan Is Required");
            closure = Map.copyOf(Objects.requireNonNull(closure, "Resident Function Closure Is Required"));
            stamps = Map.copyOf(Objects.requireNonNull(stamps, "Resident Source Stamps Are Required"));
            source = Objects.requireNonNull(source, "Resident Source Is Required");
            templates = Map.copyOf(Objects.requireNonNull(templates, "Resident Execution Templates Are Required"));
            entries = Set.copyOf(Objects.requireNonNull(entries, "Resident Execution Entries Are Required"));
            key.require(plan);
        }
    }

    @FunctionalInterface
    public interface TemplateCompiler {
        CompiledExecutionRunner.ExecutionTemplate compile(CompiledExecutionPlan plan, NodeInstanceId startNodeId);
    }

    @FunctionalInterface
    public interface MetadataCompiler {
        CompiledGraphMetadataProvider.Result compile(GraphDocument graph, FunctionSourceDocument functionSource);
    }

    public record ResidentExecution(GraphDocument graph, FunctionSourceDocument functionSource, CompiledPlanCacheKey key,
                                    ContentHash envelopeChecksum, ContentHash payloadChecksum, UUID mutationId,
                                    ResourceActivationState activationState,
                                    CompiledGraphMetadataProvider.Result metadata, boolean synchronousEventWindow) {
        public ResidentExecution {
            graph = Objects.requireNonNull(graph, "Resident Graph Source Is Required");
            key = Objects.requireNonNull(key, "Resident Graph Key Is Required");
            envelopeChecksum = Objects.requireNonNull(envelopeChecksum, "Resident Envelope Checksum Is Required");
            payloadChecksum = Objects.requireNonNull(payloadChecksum, "Resident Payload Checksum Is Required");
            mutationId = Objects.requireNonNull(mutationId, "Resident Mutation ID Is Required");
            activationState = Objects.requireNonNull(activationState, "Resident Activation State Is Required");
        }
    }

    public record ResidentSource(GraphDocument graph, FunctionSourceDocument functionSource, CompiledPlanCacheKey key,
                                 ContentHash envelopeChecksum, ContentHash payloadChecksum, UUID mutationId,
                                 ResourceActivationState activationState) {
        public ResidentSource {
            graph = Objects.requireNonNull(graph, "Resident Graph Source Is Required");
            key = Objects.requireNonNull(key, "Resident Graph Key Is Required");
            envelopeChecksum = Objects.requireNonNull(envelopeChecksum, "Resident Envelope Checksum Is Required");
            payloadChecksum = Objects.requireNonNull(payloadChecksum, "Resident Payload Checksum Is Required");
            mutationId = Objects.requireNonNull(mutationId, "Resident Mutation ID Is Required");
            activationState = Objects.requireNonNull(activationState, "Resident Activation State Is Required");
        }
    }

    private record AdmissionKey(ServerResourceLocator resource, long revision, CatalogBinding binding) {
        private static AdmissionKey from(ExecutionTarget target) {
            return new AdmissionKey(target.resource(), target.expectedRevision(), target.expectedBinding());
        }
    }

    private record ResourceRevisionKey(ServerResourceLocator resource, long revision) {
        private static ResourceRevisionKey from(CompiledPlanCacheKey key) {
            return new ResourceRevisionKey(key.graph(), key.graphRevision());
        }
    }

    private record MutationCursor(long revision, UUID mutationId, ContentHash payloadChecksum,
                                  ResourceActivationState activationState, boolean deleted) {
        private static MutationCursor from(CoreGraphMutationEvent event) {
            return new MutationCursor(event.revision(), event.mutationId(), event.payloadChecksum(), event.activationState(), event.deleted());
        }
    }
}
