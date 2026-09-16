package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import restudio.flow.data.FlowDataType;
import restudio.resync.Log;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.core.Session;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.jobs.JobManager;
import restudio.resync.jobs.JobExecutionLease;
import restudio.resync.jobs.JobRecord;
import restudio.resync.jobs.JobStatus;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.server.TemporaryLifecycleDiagnostics;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedState;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionResult;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.WorldGenOperationService;
import restudio.resync.worldgen.WorldGenGeneratedOutputController;
import restudio.resync.worldgen.WorldGenGeneratedOutputRebuilder;
import restudio.resync.worldgen.WorldGenGeneratedPersistenceParticipant;
import restudio.resync.worldgen.datapack.WorldGenDatapackCompiler;
import restudio.resync.worldgen.datapack.WorldGenDatapackInstaller;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackCapability;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackPersistenceParticipant;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;
import restudio.resync.worldgen.preview.WorldGenPreviewManager;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.WorldGenCompileDiagnostics;
import restudio.resync.worldgen.registry.WorldGenFlowCatalogContribution;
import restudio.resync.worldgen.registry.WorldGenNodeDefinitions;
import restudio.resync.worldgen.registry.WorldGenNodeRegistry;
import restudio.resync.worldgen.registry.WorldGenOptionCatalogs;
import restudio.resync.worldgen.runtime.WorldGenRuntimeListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class WorldGenModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("worldGen", "WorldGen", "worldgen").withDependencies("flowJobs");
    private static final int MAX_MUTATION_RECEIPTS = 2048;
    private static final long MUTATION_RECEIPT_RETENTION_MS = 15L * 60L * 1000L;
    public static final String WORLD_GEN_PROJECT_SAVE_ACTION = "worldGenProjectSave";
    public static final String WORLD_GEN_PROJECT_DELETE_ACTION = "worldGenProjectDelete";
    public static final String RESPONSE_AUTHORITY_EPOCH_FIELD = "authorityEpoch";
    public static final String RESPONSE_REVISION_FIELD = "revision";
    public static final String RESPONSE_DATA_FIELD = "data";
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final Gson gson = new GsonBuilder()
        .registerTypeAdapter(FlowDataType.class, new TypeAdapter<FlowDataType>() {
            @Override
            public void write(JsonWriter out, FlowDataType value) throws IOException {
                out.value(value == null ? FlowDataType.ANY.getId() : value.getId());
            }

            @Override
            public FlowDataType read(JsonReader in) throws IOException {
                return FlowDataType.fromString(in.nextString());
            }
        })
        .create();
    private Codec codec;
    private int channelId;
    private WorldGenPreviewManager previewManager;
    private WorldGenProjectStorage projectStorage;
    private WorldGenGeneratedOutputController generatedOutputController;
    private WorldGenGeneratedOutputRebuilder generatedOutputRebuilder;
    private WorldGenInstalledDatapackPersistenceParticipant installedDatapackParticipant;
    private WorldGenRuntimeListener runtimeListener;
    private JobManager jobManager;
    private ReSyncPersistenceCoordinator persistence;
    private AuthorityEpoch authorityEpoch;
    private final AtomicLong projectCatalogRevision = new AtomicLong();
    private final AtomicLong projectCatalogAuthorityEpoch = new AtomicLong();
    private final Map<String, String> mutationIdsByRequest = new ConcurrentHashMap<>();
    private final Map<String, MutationReceipt> mutationReceiptsByRequest = new ConcurrentHashMap<>();
    private final Map<String, Long> mutationReceiptTimesByKey = new ConcurrentHashMap<>();
    private boolean startupActivationComplete;
    private CompletionStage<Void> previewShutdown = CompletableFuture.completedFuture(null);

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        previewShutdown = CompletableFuture.completedFuture(null);
        this.codec = context.getCodec();
        this.channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        this.persistence = context.getRequiredService(ReSyncPersistenceCoordinator.class);
        AuthorityEpoch configuredAuthorityEpoch = context.getRequiredService(AuthorityEpoch.class);
        if (configuredAuthorityEpoch.current() < 1L) {
            throw new IllegalStateException("WorldGen authority epoch must be positive");
        }
        this.authorityEpoch = configuredAuthorityEpoch;
        AssetTransactionCoordinator assetTransactions = context.getRequiredService(AssetTransactionCoordinator.class);
        Path activeRoot;
        try {
            activeRoot = persistence.activeDataRoot().toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("WorldGen Active Data Root Could Not Be Read", exception);
        }
        AssetPersistenceGate assetsGate = context.getRequiredService(AssetPersistenceGate.class);
        LegacyRuntimeActivationGate legacyRuntimeGate = LegacyRuntimeActivationGate.runtime(activeRoot);
        this.projectStorage = new WorldGenProjectStorage(activeRoot.toFile(), legacyRuntimeGate, assetsGate, assetTransactions);
        initializeProjectCatalogRevision();
        WorldGenDatapackCompiler compiler = new WorldGenDatapackCompiler(context.getPlugin(), projectStorage.legacyCompatibilityEnabled());
        generatedOutputRebuilder = new WorldGenGeneratedOutputRebuilder(projectStorage, compiler, assetTransactions,
            persistence::freshDerivedRepairAuthorized);
        projectStorage.setGeneratedRecipeUpdater(() -> {
            try {
                generatedOutputRebuilder.persistCurrentRecipe();
            } catch (IOException exception) {
                throw new IllegalStateException("WorldGen Generated Rebuild Recipe Could Not Update", exception);
            }
        }, () -> generatedOutputRebuilder.recipeMatchesCurrentAssets());
        WorldGenGeneratedOutputController generatedOutput;
        WorldGenGeneratedPersistenceParticipant participant;
        try {
            generatedOutput = new WorldGenGeneratedOutputController(
                activeRoot, persistence.fence(), WorldGenGeneratedOutputController.DEFAULT_DRAIN_TIMEOUT, generatedOutputRebuilder);
            participant = new WorldGenGeneratedPersistenceParticipant(
                activeRoot, generatedOutput);
        } catch (IOException exception) {
            throw new IllegalStateException("WorldGen Generated Output Could Not Initialize", exception);
        }
        this.generatedOutputController = generatedOutput;
        WorldGenDatapackInstaller installer = new WorldGenDatapackInstaller(persistence.fence());
        WorldGenInstalledDatapackPersistenceParticipant datapackParticipant;
        try {
            datapackParticipant = new WorldGenInstalledDatapackPersistenceParticipant(
                activeRoot, installer);
        } catch (IOException exception) {
            throw new IllegalStateException("WorldGen Installed Datapack Lifecycle Could Not Initialize", exception);
        }
        this.installedDatapackParticipant = datapackParticipant;
        this.previewManager = new WorldGenPreviewManager(context.getPlugin(), legacyRuntimeGate.isCompatibilityMode(), generatedOutput, installer);
        FlowJobRegistry flowJobs = context.getRequiredService(FlowJobRegistry.class);
        WorldGenOperationService operationService = new WorldGenOperationService(context.getPlugin(), projectStorage, previewManager,
            flowJobs, generatedOutput, installer);
        this.runtimeListener = new WorldGenRuntimeListener(context.getPlugin());
        this.jobManager = new JobManager(flowJobs, job -> broadcastJob("jobStatus", jobSnapshot(job)));
        WorldGenNodeDefinitions.registerDefaults(WorldGenNodeRegistry.getInstance());
        WorldGenFlowCatalogContribution catalogContribution = WorldGenFlowCatalogContribution.create();
        WorldGenOptionCatalogs.register(context.getRequiredService(OptionCatalogRegistry.class));
        context.registerService(WorldGenModule.class, this);
        context.registerService(WorldGenProjectStorage.class, projectStorage);
        context.registerService(WorldGenGeneratedOutputController.class, generatedOutput);
        context.registerService(WorldGenGeneratedOutputRebuilder.class, generatedOutputRebuilder);
        context.registerService(WorldGenGeneratedPersistenceParticipant.class, participant);
        context.registerService(WorldGenInstalledDatapackCapability.class, installer.capability());
        context.registerService(WorldGenInstalledDatapackPersistenceParticipant.class, datapackParticipant);
        context.registerService(WorldGenOperationService.class, operationService);
        context.registerService(WorldGenFlowCatalogContribution.class, catalogContribution);
    }

    public synchronized void completeStartupActivation(PersistenceRootReadiness readiness) {
        ReSyncPersistenceCoordinator.ReadinessProof proof = persistence == null || readiness == null ? null
            : persistence.currentValidatedReadinessProof(readiness).orElse(null);
        completeStartupActivation(proof);
    }

    public synchronized ReSyncPersistenceCoordinator.ReadinessProof completeStartupActivation(
        ReSyncPersistenceCoordinator.ReadinessProof proof) {
        if (startupActivationComplete) {
            return proof;
        }
        PersistenceRootReadiness readiness = proof == null ? null : proof.readiness();
        if (readiness == null || !readiness.complete() || persistence == null) {
            throw new IllegalStateException("WorldGen Startup Requires Sealed Restore-Ready Persistence");
        }
        if (generatedOutputRebuilder == null || generatedOutputController == null || installedDatapackParticipant == null
            || runtimeListener == null || previewManager == null) {
            throw new IllegalStateException("WorldGen Runtime Is Not Initialized");
        }
        long startupStarted = System.nanoTime();
        AtomicLong recipeReady = new AtomicLong(startupStarted);
        AtomicLong rebuildReady = new AtomicLong(startupStarted);
        long activationReady = startupStarted;
        boolean runtimeStarted = false;
        try {
            Set<String> pendingOwners = pendingWorldGenDerivedOwners(readiness);
            boolean generatedPending = pendingOwners.contains(WorldGenGeneratedPersistenceParticipant.OWNER);
            if (!generatedPending) {
                long skippedAt = System.nanoTime();
                recipeReady.set(skippedAt);
                rebuildReady.set(skippedAt);
                TemporaryLifecycleDiagnostics.event("worldgen_startup_stage", startupStarted,
                    TemporaryLifecycleDiagnostics.with(worldGenDiagnosticIdentity(), "stageName", "recipe", "outcome", "skipped"));
                TemporaryLifecycleDiagnostics.event("worldgen_startup_stage", skippedAt,
                    TemporaryLifecycleDiagnostics.with(worldGenDiagnosticIdentity(), "stageName", "rebuild", "outcome", "skipped"));
            }
            ReSyncPersistenceCoordinator.ReadinessProof strictProof;
            if (pendingOwners.isEmpty()) {
                strictProof = persistence.completeStartupActivation(proof, pendingOwners);
            } else {
                strictProof = persistence.completeStartupActivation(proof, pendingOwners, context -> {
                    context.requireActiveRoot(generatedOutputController.scopeRoot());
                    if (generatedPending) {
                        generatedOutputController.rebuildForStartup(context, () -> {
                            generatedOutputRebuilder.persistCurrentRecipe();
                            long recipeCompleted = System.nanoTime();
                            recipeReady.set(recipeCompleted);
                            TemporaryLifecycleDiagnostics.event("worldgen_startup_stage", startupStarted,
                                TemporaryLifecycleDiagnostics.with(worldGenDiagnosticIdentity(), "stageName", "recipe", "outcome", "complete"));
                        });
                        long rebuildCompleted = System.nanoTime();
                        rebuildReady.set(rebuildCompleted);
                        TemporaryLifecycleDiagnostics.event("worldgen_startup_stage", recipeReady.get(),
                            TemporaryLifecycleDiagnostics.with(worldGenDiagnosticIdentity(), "stageName", "rebuild", "outcome", "complete"));
                    }
                });
            }
            requireWorldGenDerivedStartupReady(strictProof.readiness());
            activationReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("worldgen_startup_stage", rebuildReady.get(),
                TemporaryLifecycleDiagnostics.with(worldGenDiagnosticIdentity(), "stageName", "activation", "outcome", "complete"));
            runtimeStarted = true;
            runtimeListener.start();
            CompletionStage<Void> orphanCleanup = previewManager.cleanupOrphanedPreviews();
            previewShutdown = previewShutdown.thenCombine(orphanCleanup, (unused, ignored) -> null);
            orphanCleanup.whenComplete((unused, failure) -> {
                if (failure != null) {
                    Log.error("WorldGen orphaned preview cleanup failed: " + failure.getMessage());
                }
            });
            startupActivationComplete = true;
            long startupReady = System.nanoTime();
            TemporaryLifecycleDiagnostics.event("worldgen_startup_stage", activationReady,
                TemporaryLifecycleDiagnostics.with(worldGenDiagnosticIdentity(), "stageName", "runtime", "outcome", "complete",
                    "totalElapsedMs", elapsedMillis(startupStarted, startupReady)));
            return strictProof;
        } catch (RuntimeException | Error failure) {
            startupActivationComplete = false;
            if (runtimeStarted) {
                HandlerList.unregisterAll(runtimeListener);
            }
            throw failure;
        }
    }

    private static long elapsedMillis(long started, long completed) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, completed - started));
    }

    private Map<String, Object> worldGenDiagnosticIdentity() {
        return TemporaryLifecycleDiagnostics.with(
            TemporaryLifecycleDiagnostics.identity(null, null, null, null, null,
                projectCatalogRevision.get(), authorityEpoch == null ? null : authorityEpoch.current(), null),
            "moduleId", "worldgen");
    }

    static Set<String> pendingWorldGenDerivedOwners(PersistenceRootReadiness readiness) {
        LinkedHashSet<String> pending = new LinkedHashSet<>();
        addPendingWorldGenDerivedOwner(readiness, WorldGenGeneratedPersistenceParticipant.OWNER, pending);
        addPendingWorldGenDerivedOwner(readiness, WorldGenInstalledDatapackCapability.OWNER, pending);
        return Set.copyOf(pending);
    }

    static void requireWorldGenDerivedStartupReady(PersistenceRootReadiness readiness) {
        if (!pendingWorldGenDerivedOwners(readiness).isEmpty()) {
            throw new IllegalStateException("WorldGen Runtime Requires Its Derived Persistence Owners To Be Ready");
        }
    }

    private static void addPendingWorldGenDerivedOwner(PersistenceRootReadiness readiness, String owner,
                                                        Set<String> pending) {
        PersistenceRootReadiness.Owner declared = readiness.owner(owner);
        if (declared != null && !declared.required()
            && declared.classification() == PersistenceParticipantClassification.DERIVED_CACHE
            && declared.state() == PersistenceRootReadiness.State.UNAVAILABLE) {
            pending.add(owner);
        }
    }

    @Override
    public void stop(ModuleContext context) {
        if (runtimeListener != null) {
            HandlerList.unregisterAll(runtimeListener);
        }
        startupActivationComplete = false;
        if (previewManager != null) {
            CompletionStage<Void> next = previewManager.stopAllPreviewsAsync();
            previewShutdown = previewShutdown.thenCombine(next, (unused, ignored) -> null);
        }
    }

    @Override
    public void prepareStop(ModuleContext context) {
        stop(context);
    }

    @Override
    public CompletionStage<Void> finishStopAsync(ModuleContext context) {
        return previewShutdown == null ? CompletableFuture.completedFuture(null) : previewShutdown;
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        subscribedSessions.add(session);
    }

    @Override
    public void cleanup(Session session) {
        subscribedSessions.remove(session);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        byte[] payload = req.getPayload();
        if (payload == null || payload.length < 1) {
            sendStatus(session, "", "error", "EmptyWorldGenPacket");
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte packetId = buffer.get();
        long packetStarted = TemporaryLifecycleDiagnostics.start();
        TemporaryLifecycleDiagnostics.event("worldgen_packet", packetStarted,
            TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "worldgen", "operation",
                "0x" + String.format("%02X", packetId), "inputChars", payload.length, "outcome", "accepted")));
        MutationJson mutation = null;
        try {
            if (requiresMutationEpoch(packetId)) {
                mutation = readMutationJson(buffer);
                mutation = bindAuthenticatedIntent(session, mutation);
                if (!mutation.action().equals(mutationAction(packetId))) {
                    throw new IllegalArgumentException("WorldGen mutation action does not match its packet");
                }
                if (isProjectMutation(mutation.action())) {
                    requireProjectMutationIdentity(mutation);
                }
                if (!acceptsIncomingEpoch(mutation)) {
                    sendMutationStatus(session, mutation, "error", "WorldGen Authority Epoch Is Stale");
                    return;
                }
            }
            switch (packetId) {
                case 0x20 -> rejectLegacySave(session, mutation);
                case 0x21 -> handlePreviewCreate(session, mutation);
                case 0x22 -> handlePreviewStop(session, mutation);
                case 0x24 -> sendRegistrySnapshot(session);
                case 0x30 -> handleSaveProject(session, mutation);
                case 0x31 -> handlePreviewApply(session, mutation);
                case 0x32 -> handleProjectRequest(session, buffer);
                case 0x33 -> handleProjectDelete(session, mutation);
                case 0x34 -> sendProjectList(session);
                case 0x3A -> sendJobSnapshot(session);
                default -> {
                    Log.warn("Unknown worldgen packet: 0x" + String.format("%02X", packetId));
                    sendStatus(session, "", "error", "UnknownWorldGenPacket");
                }
            }
        } catch (Exception e) {
            Log.error("Error handling worldgen packet 0x" + String.format("%02X", packetId) + ": " + e.getMessage());
            if (mutation != null) {
                if (packetId == (byte) 0x30) {
                    sendMutationFailureDiagnosticsSafely(session, mutation, mutation.resourceId(), e.getMessage(),
                        projectRevisionSafely(mutation.resourceId()));
                } else {
                    try {
                        sendMutationStatus(session, mutation, "error", e.getMessage());
                    } catch (RuntimeException responseFailure) {
                        Log.warn("WorldGen mutation error response could not be sent: " + responseFailure.getMessage());
                    }
                }
            } else {
                sendStatus(session, "", "error", e.getMessage());
            }
        }
    }

    private void rejectLegacySave(Session session, MutationJson payload) {
        sendMutationStatus(session, payload, "error",
            "Legacy WorldGen Graph Save Is Retired; Use Typed WorldGen Project Mutation");
    }

    private void handleSaveProject(Session session, MutationJson payload) {
        String projectId = requireProjectMutationIdentity(payload);
        WorldGenProject project = WorldGenSerializer.deserializeProject(payload.json());
        if (project == null || project.getId() == null || project.getId().isBlank() || !projectId.equals(project.getId())) {
            throw new IllegalArgumentException("WorldGen Project Identity Does Not Match Mutation Resource");
        }
        if (payload.expectedRevision() < 1L) {
            throw new IllegalArgumentException("WorldGen Project Create Must Use Typed Resource Authority");
        }
        FlowResourceMutationStamp current = projectStorage.readMutationStamp(projectId);
        if (current == null || current.deleted()) {
            throw new IllegalArgumentException("WorldGen Project Save Requires An Authoritative Live Project");
        }
        JobAdmission admission = beginReplayableJob(session, payload.action(), project.getId(), payload);
        if (admission.identityConflict()) {
            sendMutationStatus(session, payload, "conflict", "WorldGen Mutation Identity Conflicts With An In-Flight Operation");
            return;
        }
        if (admission.inFlightDuplicate()) {
            sendMutationStatus(session, payload, "in_progress", "WorldGen Mutation Is Already Running");
            return;
        }
        if (admission.terminalReplay()) {
            replaySaveProject(session, payload, project, admission.job());
            return;
        }
        if (!admission.acquired()) {
            return;
        }
        JobRecord<String> job = admission.job();
        long admittedEpoch = payload.authorityEpoch();
        long baselineRevision = projectRevisionSafely(project.getId());
        boolean committed = false;
        String completionMessage = "Saved";
        try {
            project.rebuildIndices();
            WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project, projectStorage.legacyCompatibilityEnabled());
            String diagnosticJson = mutationDiagnosticsJson(payload, diagnostics, project.getId());
            sendJsonPacket(session, (byte) 0x38, diagnosticJson, baselineRevision, admittedEpoch);
            if (!diagnostics.isSuccess()) {
                throw new IllegalArgumentException("WorldGen Compile Failed");
            }
            requireMutationEpoch(payload);
            TransactionResult transaction = projectStorage.saveProject(project, payload.mutationUuid(), payload.expectedRevision(),
                mutationIntentScope(session, payload));
            committed = true;
            long revision = transactionRevision(transaction, project.getId());
            advanceProjectCatalogRevision(revision);
            JsonObject acknowledgement = new JsonObject();
            acknowledgement.addProperty("action", canonicalMutationAction(payload.action()));
            acknowledgement.addProperty("projectId", project.getId());
            acknowledgement.addProperty("resourceId", payload.resourceId());
            acknowledgement.addProperty("requestId", payload.requestId());
            acknowledgement.addProperty("mutationId", payload.mutationId());
            acknowledgement.addProperty("operationId", payload.operationId());
            String acknowledgementJson = acknowledgement.toString();
            recordMutationReceipt(session, payload, MutationReceipt.success(diagnosticJson, baselineRevision,
                acknowledgementJson, revision, admittedEpoch, payload.intentHash()));
            publishCommittedMutation(session, payload, acknowledgementJson, revision, admittedEpoch,
                "WorldGen Save Committed Before An Authority Transition; Refreshing");
        } catch (WorldGenProjectStorage.CommittedMutationException exception) {
            committed = true;
            completionMessage = "Saved; Generated Recipe Repair Pending";
            publishCommittedRecipeFailure(session, payload, project.getId(), exception, true);
        } catch (Exception exception) {
            if (committed) {
                Log.warn("WorldGen save committed but response publication failed: " + exception.getMessage());
            } else {
                long failureRevision = exception instanceof ResourceRevisionConflictException conflict
                    ? positiveResponseRevision(conflict.getCurrentRevision(), baselineRevision) : baselineRevision;
                String failureJson = mutationFailureDiagnosticsJson(payload, project.getId(), exception.getMessage());
                try {
                    recordMutationReceipt(session, payload, MutationReceipt.failure(failureJson, failureRevision, admittedEpoch,
                        exception.getMessage(), payload.intentHash()));
                } catch (RuntimeException receiptFailure) {
                    Log.warn("WorldGen save failure receipt could not be recorded: " + receiptFailure.getMessage());
                }
                try {
                    sendJsonPacket(session, (byte) 0x38, failureJson, failureRevision, responseEpoch(admittedEpoch));
                } catch (RuntimeException responseFailure) {
                    Log.warn("WorldGen save failure response could not be sent: " + responseFailure.getMessage());
                } finally {
                    failJobSafely(job, exception.getMessage(), exception);
                }
            }
        } finally {
            if (committed) {
                completeJobSafely(job, project.getId(), completionMessage);
            }
            completeJobExecution(job);
        }
    }

    private void handleProjectRequest(Session session, ByteBuffer buffer) {
        String projectId = readJson(buffer);
        long started = TemporaryLifecycleDiagnostics.start();
        WorldGenProject project = projectStorage.getProject(projectId);
        if (project != null) {
            TemporaryLifecycleDiagnostics.event("worldgen_project_load", started,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "worldgen", "operation", "load",
                    "resourceId", projectId, "outcome", "found", "revision", projectResponseRevision(project.getId()))));
            sendJsonPacket(session, (byte) 0x35, WorldGenSerializer.serializeProject(project),
                projectResponseRevision(project.getId()));
        } else {
            TemporaryLifecycleDiagnostics.event("worldgen_project_load", started,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "worldgen", "operation", "load",
                    "resourceId", projectId, "outcome", "missing")));
            sendStatus(session, "", "error", "WorldGen Project Missing");
        }
    }

    private void handleProjectDelete(Session session, MutationJson payload) {
        String projectId = requireProjectMutationIdentity(payload);
        JobAdmission admission = beginReplayableJob(session, payload.action(), projectId, payload);
        if (admission.identityConflict()) {
            sendMutationStatus(session, payload, "conflict", "WorldGen Mutation Identity Conflicts With An In-Flight Operation");
            return;
        }
        if (admission.inFlightDuplicate()) {
            sendMutationStatus(session, payload, "in_progress", "WorldGen Mutation Is Already Running");
            return;
        }
        if (admission.terminalReplay()) {
            replayDeleteProject(session, payload, admission.job());
            return;
        }
        if (!admission.acquired()) {
            return;
        }
        JobRecord<String> job = admission.job();
        long admittedEpoch = payload.authorityEpoch();
        long baselineRevision = projectRevisionSafely(projectId);
        boolean committed = false;
        String completionMessage = "Deleted";
        try {
            requireMutationEpoch(payload);
            TransactionResult transaction = projectStorage.deleteProject(projectId, payload.mutationUuid(), payload.expectedRevision(),
                mutationIntentScope(session, payload));
            committed = true;
            long revision = transactionRevision(transaction, projectId);
            advanceProjectCatalogRevision(revision);
            JsonObject acknowledgement = new JsonObject();
            acknowledgement.addProperty("action", canonicalMutationAction(payload.action()));
            acknowledgement.addProperty("projectId", projectId);
            acknowledgement.addProperty("resourceId", payload.resourceId());
            acknowledgement.addProperty("requestId", payload.requestId());
            acknowledgement.addProperty("mutationId", payload.mutationId());
            acknowledgement.addProperty("operationId", payload.operationId());
            acknowledgement.addProperty("deleted", true);
            String acknowledgementJson = acknowledgement.toString();
            recordMutationReceipt(session, payload, MutationReceipt.success(null, catalogRevision(),
                acknowledgementJson, revision, admittedEpoch, payload.intentHash()));
            publishCommittedMutation(session, payload, acknowledgementJson, revision, admittedEpoch,
                "WorldGen Delete Committed Before An Authority Transition; Refreshing");
        } catch (WorldGenProjectStorage.CommittedMutationException exception) {
            committed = true;
            completionMessage = "Deleted; Generated Recipe Repair Pending";
            publishCommittedRecipeFailure(session, payload, projectId, exception, true);
        } catch (Exception exception) {
            if (committed) {
                Log.warn("WorldGen delete committed but response publication failed: " + exception.getMessage());
            } else {
                long failureRevision = exception instanceof ResourceRevisionConflictException conflict
                    ? positiveResponseRevision(conflict.getCurrentRevision(), baselineRevision) : baselineRevision;
                String failureJson = mutationFailureDiagnosticsJson(payload, projectId, exception.getMessage());
                try {
                    recordMutationReceipt(session, payload, MutationReceipt.failure(failureJson, failureRevision, admittedEpoch,
                        exception.getMessage(), payload.intentHash()));
                } catch (RuntimeException receiptFailure) {
                    Log.warn("WorldGen delete failure receipt could not be recorded: " + receiptFailure.getMessage());
                }
                try {
                    sendJsonPacket(session, (byte) 0x38, failureJson, failureRevision, responseEpoch(admittedEpoch));
                } catch (RuntimeException responseFailure) {
                    Log.warn("WorldGen delete failure response could not be sent: " + responseFailure.getMessage());
                } finally {
                    failJobSafely(job, exception.getMessage(), exception);
                }
            }
        } finally {
            if (committed) {
                completeJobSafely(job, projectId, completionMessage);
            }
            completeJobExecution(job);
        }
    }

    private void sendProjectList(Session session) {
        long started = TemporaryLifecycleDiagnostics.start();
        List<String> projectIds = projectStorage.listProjectIds();
        long revision = projectListRevision();
        TemporaryLifecycleDiagnostics.event("worldgen_project_list", started,
            TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "worldgen", "operation", "list",
                "count", projectIds.size(), "revision", revision, "outcome", "complete")));
        sendJsonPacket(session, (byte) 0x36, gson.toJson(projectIds), revision);
    }

    private void handlePreviewCreate(Session session, MutationJson payload) {
        PreviewCreateRequest request = gson.fromJson(payload.json(), PreviewCreateRequest.class);
        String previewId = requirePreviewMutationIdentity(payload);
        JobAdmission admission = beginPreviewJob(session, payload, previewId);
        if (!admitPreview(session, payload, previewId, admission)) {
            return;
        }
        JobRecord<String> job = admission.job();
        CompletableFuture<Void> termination = jobManager.execution(job).terminationFuture();
        Runnable cancellation = () -> {
            try {
                previewManager.stopPreview(previewId, () -> termination.complete(null), termination::completeExceptionally);
            } catch (Throwable failure) {
                termination.completeExceptionally(failure);
            }
        };
        setJobCancellation(job, cancellation);
        boolean launchAttempted = false;
        try {
            WorldGenGraph graph = gson.fromJson(gson.toJson(request.graph()), WorldGenGraph.class);
            graph.rebuildIndices();
            World.Environment environment = parseEnvironment(request.environment());
            launchAttempted = true;
            boolean launched = jobManager.launch(job, () -> previewManager.createPreview(
                previewId,
                request.playerUuid(),
                graph,
                environment,
                request.seed(),
                 preview -> {
                     sendPreviewStatusSafely(session, previewId, payload, "ready", "Ready");
                     succeedJob(job, previewId, "Ready");
                 },
                 throwable -> {
                     String message = throwable != null ? throwable.getMessage() : "Preview Failed";
                     sendPreviewStatusSafely(session, previewId, payload, "error", message);
                     failJob(job, message, throwable);
                 },
                   () -> termination.complete(null), termination::completeExceptionally,
                   outcome -> cancelPreviewJobAfterPhysical(job, outcome)
               ));
            if (!launched) {
                cancellation.run();
            }
        } catch (Throwable failure) {
            sendPreviewStatusSafely(session, previewId, payload, "error", failure.getMessage());
            failJob(job, failure.getMessage(), failure);
            if (!launchAttempted) {
                termination.complete(null);
            } else {
                previewManager.stopPreview(previewId, () -> termination.complete(null), termination::completeExceptionally);
            }
        }
    }

    private void handlePreviewApply(Session session, MutationJson payload) {
        PreviewApplyRequest request = gson.fromJson(payload.json(), PreviewApplyRequest.class);
        String previewId = requirePreviewMutationIdentity(payload);
        JobAdmission admission = beginPreviewJob(session, payload, previewId);
        if (!admitPreview(session, payload, previewId, admission)) {
            return;
        }
        JobRecord<String> job = admission.job();
        CompletableFuture<Void> termination = jobManager.execution(job).terminationFuture();
        Runnable cancellation = () -> {
            try {
                previewManager.stopPreview(previewId, () -> termination.complete(null), termination::completeExceptionally);
            } catch (Throwable failure) {
                termination.completeExceptionally(failure);
            }
        };
        setJobCancellation(job, cancellation);
        boolean launchAttempted = false;
        try {
            WorldGenProject project = null;
            if (request.draftProject() != null) {
                project = gson.fromJson(gson.toJson(request.draftProject()), WorldGenProject.class);
            }
            if (project == null && request.project() != null) {
                project = gson.fromJson(gson.toJson(request.project()), WorldGenProject.class);
            }
            if (project == null && request.projectId() != null && !request.projectId().isBlank()) {
                project = projectStorage.getProject(request.projectId());
            }
            if (project == null || project.getTerrainGraph() == null) {
                throw new IllegalArgumentException("Terrain Graph Missing");
            }
            project.rebuildIndices();
            WorldGenProject previewProject = project;
            WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project, projectStorage.legacyCompatibilityEnabled());
            sendJsonPacket(session, (byte) 0x38, gson.toJson(diagnostics), projectResponseRevision(project.getId()));
            if (!diagnostics.isSuccess()) {
                failJob(job, "WorldGen Compile Failed", null);
                throw new IllegalArgumentException("WorldGen Compile Failed");
            }
            long started = System.nanoTime();
            World.Environment environment = parseEnvironment(request.environment());
            launchAttempted = true;
            boolean launched = jobManager.launch(job, () -> previewManager.createPreview(
                previewId,
                request.playerUuid(),
                previewProject,
                environment,
                request.seed(),
                 preview -> {
                     String message = previewReadyMessage(preview, started);
                     sendPreviewStatusSafely(session, previewId, payload, "ready", message);
                     succeedJob(job, previewId, message);
                 },
                 throwable -> {
                     String message = throwable != null ? throwable.getMessage() : "Preview Failed";
                     sendPreviewStatusSafely(session, previewId, payload, "error", message);
                     failJob(job, message, throwable);
                 },
                   () -> termination.complete(null), termination::completeExceptionally,
                   outcome -> cancelPreviewJobAfterPhysical(job, outcome)
               ));
            if (!launched) {
                cancellation.run();
            }
        } catch (Throwable failure) {
            sendPreviewStatusSafely(session, previewId, payload, "error", failure.getMessage());
            failJob(job, failure.getMessage(), failure);
            if (!launchAttempted) {
                termination.complete(null);
            } else {
                previewManager.stopPreview(previewId, () -> termination.complete(null), termination::completeExceptionally);
            }
        }
    }

    private String previewReadyMessage(WorldGenPreviewManager.PreviewWorld preview, long started) {
        long elapsed = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        if (preview == null || preview.datapackBuild() == null) {
            return "Ready " + elapsed + "ms";
        }
        String suffix = preview.datapackBuild().getWarnings().isEmpty() ? "" : " · " + preview.datapackBuild().getWarnings().size() + " Warnings";
        return "Ready " + elapsed + "ms · Datapack " + preview.datapackBuild().getFileCount() + " Files" + suffix;
    }

    private void handlePreviewStop(Session session, MutationJson payload) {
        PreviewStopRequest request = gson.fromJson(payload.json(), PreviewStopRequest.class);
        String previewId = requirePreviewMutationIdentity(payload);
        JobAdmission admission = beginPreviewJob(session, payload, previewId);
        if (!admitPreview(session, payload, previewId, admission)) {
            return;
        }
        JobRecord<String> job = admission.job();
        CompletableFuture<Void> termination = jobManager.execution(job).terminationFuture();
        Runnable cancellation = () -> {
            try {
                previewManager.stopPreview(previewId, () -> termination.complete(null), termination::completeExceptionally);
            } catch (Throwable failure) {
                termination.completeExceptionally(failure);
            }
        };
        setJobCancellation(job, cancellation);
        boolean launchAttempted = false;
        try {
            launchAttempted = true;
            boolean launched = jobManager.launch(job, () -> previewManager.stopPreview(previewId, () -> {
                try {
                    sendPreviewStatusSafely(session, previewId, payload, "stopped", "Stopped");
                    succeedJob(job, previewId, "Stopped");
                } finally {
                    termination.complete(null);
                }
            }, throwable -> {
                try {
                    String message = throwable != null ? throwable.getMessage() : "Stop Failed";
                    Log.error("Error stopping worldgen preview: " + message);
                    sendPreviewStatusSafely(session, previewId, payload, "error", message);
                    failJob(job, message, throwable);
                } finally {
                    termination.completeExceptionally(throwable != null ? throwable : new IllegalStateException("WorldGen Preview Stop Failed"));
                }
            }));
            if (!launched) {
                cancellation.run();
            }
        } catch (Throwable failure) {
            sendPreviewStatusSafely(session, previewId, payload, "error", failure.getMessage());
            failJob(job, failure.getMessage(), failure);
            if (!launchAttempted) {
                termination.complete(null);
            } else {
                previewManager.stopPreview(previewId, () -> termination.complete(null), termination::completeExceptionally);
            }
        }
    }

    private void sendRegistrySnapshot(Session session) {
        sendJsonPacket(session, (byte) 0x25, gson.toJson(Map.of(
            "nodes", WorldGenNodeRegistry.getInstance().getAllDefinitions(),
            "capabilities", Map.of(
                "backend", "bukkit",
                "datapackBackend", "compile_only",
                "datapackBiomes", true,
                "datapackFeatures", true,
                "datapackStructures", true,
                "datapackSpawns", true,
                "liveDatapackActivation", false,
                "previewDatapackCompile", true,
                "minecraftVersion", Bukkit.getMinecraftVersion(),
                "targetVersions", WorldGenTargetVersion.supportedIds()
            )
        )), projectListRevision());
    }

    private void sendStatus(Session session, String previewId, String status, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("previewId", previewId == null ? "" : previewId);
        data.addProperty("status", status == null ? "error" : status);
        data.addProperty("message", message == null ? "" : message);
        sendJsonPacket(session, (byte) 0x23, data.toString());
    }

    private void sendStatus(Session session, String previewId, MutationJson mutation, String status, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("previewId", previewId == null ? "" : previewId);
        data.addProperty("action", mutation.action());
        data.addProperty("resourceId", mutation.resourceId());
        data.addProperty("requestId", mutation.requestId());
        data.addProperty("mutationId", mutation.mutationId());
        data.addProperty("operationId", mutation.operationId());
        data.addProperty("status", status == null ? "error" : status);
        data.addProperty("message", message == null ? "" : message);
        sendJsonPacket(session, (byte) 0x23, data.toString());
    }

    private void sendPreviewStatusSafely(Session session, String previewId, MutationJson mutation, String status, String message) {
        try {
            sendStatus(session, previewId, mutation, status, message);
        } catch (RuntimeException responseFailure) {
            Log.warn("WorldGen preview status could not be sent: " + responseFailure.getMessage());
        }
    }

    private void sendMutationStatus(Session session, MutationJson mutation, String status, String message) {
        sendMutationStatus(session, mutation, status, message, false);
    }

    private void sendMutationStatus(Session session, MutationJson mutation, String status, String message,
                                    boolean forceRefresh) {
        JsonObject data = new JsonObject();
        String action = canonicalMutationAction(mutation.action());
        data.addProperty("action", action);
        if (isProjectMutation(action)) {
            data.addProperty("projectId", requireProjectMutationIdentity(mutation));
        }
        data.addProperty("resourceId", mutation.resourceId());
        data.addProperty("requestId", mutation.requestId());
        data.addProperty("mutationId", mutation.mutationId());
        data.addProperty("operationId", mutation.operationId());
        data.addProperty("status", status == null ? "error" : status);
        data.addProperty("message", message == null ? "" : message);
        data.addProperty("forceRefresh", forceRefresh);
        boolean committedOldAuthority = "authority_transition".equalsIgnoreCase(status)
            || "committed_old_authority".equalsIgnoreCase(status);
        data.addProperty("committed", committedOldAuthority);
        data.addProperty("committedOldAuthority", committedOldAuthority);
        data.addProperty("admittedAuthorityEpoch", mutation.authorityEpoch());
        sendJsonPacket(session, (byte) 0x23, data.toString(), catalogRevision());
    }

    private void publishCommittedMutation(Session session, MutationJson mutation, String acknowledgement,
                                          long revision, long admittedEpoch, String transitionMessage) {
        if (authorityEpoch.current() != admittedEpoch) {
            sendAuthorityTransition(session, mutation, transitionMessage);
            return;
        }
        try {
            sendJsonPacket(session, (byte) 0x37, acknowledgement, revision, admittedEpoch);
        } catch (RuntimeException sendFailure) {
            if (authorityEpoch.current() != admittedEpoch) {
                sendAuthorityTransition(session, mutation, transitionMessage);
            } else {
                Log.warn("WorldGen mutation committed but its acknowledgement could not be sent: " + sendFailure.getMessage());
            }
        }
    }

    private void sendAuthorityTransition(Session session, MutationJson mutation, String message) {
        for (int attempt = 0; attempt < 3; attempt++) {
            long currentEpoch = authorityEpoch.current();
            if (currentEpoch < 1L) {
                Log.warn("WorldGen mutation committed but no positive authority epoch is available for refresh");
                return;
            }
            try {
                sendMutationStatus(session, mutation, "authority_transition", message, true);
                return;
            } catch (RuntimeException failure) {
                if (authorityEpoch.current() == currentEpoch) {
                    Log.warn("WorldGen authority-transition response could not be sent: " + failure.getMessage());
                    return;
                }
            }
        }
        Log.warn("WorldGen authority-transition response could not be published after the authority changed repeatedly");
    }

    private void sendMutationDiagnostics(Session session, MutationJson mutation, WorldGenCompileDiagnostics diagnostics,
                                         String projectId, long revision) {
        long responseRevision = positiveResponseRevision(revision, projectRevisionSafely(projectId));
        sendJsonPacket(session, (byte) 0x38, mutationDiagnosticsJson(mutation, diagnostics, projectId), responseRevision,
            responseEpoch(mutation.authorityEpoch()));
    }

    private void sendMutationFailureDiagnostics(Session session, MutationJson mutation, String projectId,
                                                String message, long revision) {
        long responseRevision = positiveResponseRevision(revision, projectRevisionSafely(projectId));
        sendJsonPacket(session, (byte) 0x38, mutationFailureDiagnosticsJson(mutation, projectId, message), responseRevision,
            responseEpoch(mutation.authorityEpoch()));
    }

    private String mutationDiagnosticsJson(MutationJson mutation, WorldGenCompileDiagnostics diagnostics, String projectId) {
        JsonObject data = gson.toJsonTree(diagnostics).getAsJsonObject();
        addMutationIdentity(data, mutation, projectId);
        return data.toString();
    }

    private String mutationFailureDiagnosticsJson(MutationJson mutation, String projectId, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("success", false);
        JsonObject diagnostic = new JsonObject();
        diagnostic.addProperty("severity", "error");
        diagnostic.addProperty("message", message == null || message.isBlank() ? "WorldGen Mutation Failed" : message);
        data.add("diagnostics", gson.toJsonTree(List.of(diagnostic)));
        addMutationIdentity(data, mutation, projectId);
        return data.toString();
    }

    private void addMutationIdentity(JsonObject data, MutationJson mutation, String projectId) {
        String action = canonicalMutationAction(mutation.action());
        String resolvedProjectId = projectId;
        if (isProjectMutation(action)) {
            String mutationProjectId = requireProjectMutationIdentity(mutation);
            if (resolvedProjectId == null || resolvedProjectId.isBlank() || !mutationProjectId.equals(resolvedProjectId)) {
                throw new IllegalArgumentException("WorldGen Project Identity Does Not Match Mutation Resource");
            }
            resolvedProjectId = mutationProjectId;
        }
        data.addProperty("projectId", resolvedProjectId == null ? mutation.resourceId() : resolvedProjectId);
        data.addProperty("action", action);
        data.addProperty("resourceId", mutation.resourceId());
        data.addProperty("requestId", mutation.requestId());
        data.addProperty("mutationId", mutation.mutationId());
        data.addProperty("operationId", mutation.operationId());
    }

    private JobAdmission beginPreviewJob(Session session, MutationJson mutation, String previewId) {
        return createJob(session, mutation.action(), previewId, mutation.requestId(), mutation.mutationId(),
            mutation.operationId(), mutation.expectedRevision(), mutation.intentHash());
    }

    private boolean admitPreview(Session session, MutationJson mutation, String previewId, JobAdmission admission) {
        if (admission == null) {
            return false;
        }
        if (admission.identityConflict()) {
            sendPreviewStatusSafely(session, previewId, mutation, "conflict",
                "WorldGen Preview Mutation Identity Conflicts With An Existing Operation");
            return false;
        }
        if (admission.inFlightDuplicate()) {
            sendPreviewStatusSafely(session, previewId, mutation, "in_progress",
                "WorldGen Preview Mutation Is Already Running");
            return false;
        }
        if (admission.terminalReplay()) {
            JobRecord<?> job = admission.job();
            String message = String.valueOf(job.snapshot().getOrDefault("message", "WorldGen Preview Operation Completed"));
            sendPreviewStatusSafely(session, previewId, mutation, job.getStatus().wireName(), message);
            return false;
        }
        return admission.acquired();
    }

    private JobAdmission beginReplayableJob(Session session, String action, String target, String requestId,
                                             String mutationId) {
        return createJob(session, action, target, requestId, mutationId, null, -1L, null);
    }

    private JobAdmission beginReplayableJob(Session session, String action, String target, MutationJson mutation) {
        return createJob(session, action, target, mutation.requestId(), mutation.mutationId(),
            mutation.operationId(), mutation.expectedRevision(), mutation.intentHash());
    }

    private JobAdmission createJob(Session session, String action, String target, String requestId, String mutationId) {
        return createJob(session, action, target, requestId, mutationId, null, -1L, null);
    }

    private JobAdmission createJob(Session session, String action, String target, String requestId, String mutationId,
                                   String operationId, long expectedRevision, String intentHash) {
        String actorClientId = session != null ? session.getClientId() : "unknown";
        JobManager.StartedJob<String> started = jobManager.createStarted(action,
            actorClientId, target == null ? "" : target, requestId, mutationId, operationId, expectedRevision, intentHash);
        JobRecord<String> job = started.job();
        if (started.identityConflict()) {
            return new JobAdmission(job, started.disposition(), started.execution());
        }
        Map<String, Object> snapshot = jobSnapshot(job);
        if (mutationId != null && !mutationId.isBlank()) {
            snapshot.put("mutationId", mutationId);
            snapshot.put("resourceId", target == null ? "" : target);
            mutationIdsByRequest.put(requestKey(actorClientId, requestId), mutationId);
        }
        try {
            sendJob(session, "jobAccepted", snapshot);
        } catch (RuntimeException responseFailure) {
            if (started.started()) {
                failJobSafely(job, responseFailure.getMessage(), responseFailure);
                completeJobExecution(job);
                return new JobAdmission(job, JobManager.StartDisposition.NOT_STARTED, null);
            }
            Log.warn("WorldGen job acceptance response could not be sent: " + responseFailure.getMessage());
        }
        return new JobAdmission(job, started.disposition(), started.execution());
    }

    private Map<String, Object> jobSnapshot(JobRecord<?> job) {
        Map<String, Object> snapshot = new LinkedHashMap<>(job.snapshot());
        Object target = snapshot.get("target");
        snapshot.put("resourceId", target == null ? "" : target);
        String operationId = jobManager != null ? jobManager.operationId(job) : null;
        if (operationId != null && !operationId.isBlank()) {
            snapshot.put("operationId", operationId);
        }
        return snapshot;
    }

    private void setJobCancellation(JobRecord<?> job, Runnable cancellation) {
        jobManager.setExecutionCancellation(job, cancellation);
    }

    private void completeJobExecution(JobRecord<?> job) {
        jobManager.completeExecution(job);
    }

    private void succeedJob(JobRecord<String> job, String result, String message) {
        if (job != null && job.markSucceeded(result, message == null || message.isBlank() ? "Succeeded" : message)) {
            jobManager.publish(job);
        }
    }

    private void cancelPreviewJobAfterPhysical(JobRecord<String> job, WorldGenPreviewManager.PreviewTermination outcome) {
        String message = outcome == WorldGenPreviewManager.PreviewTermination.REPLACED
            ? "Preview Replaced" : "Preview Cancelled";
        if (job != null && job.markCancelledAfterPhysical(message)) {
            jobManager.publish(job);
        }
    }

    private void failJob(JobRecord<String> job, String message, Throwable throwable) {
        if (job != null && job.markFailed(message == null || message.isBlank() ? "Failed" : message, throwable)) {
            jobManager.publish(job);
        }
    }

    private void failJobSafely(JobRecord<String> job, String message, Throwable throwable) {
        try {
            failJob(job, message, throwable);
        } catch (RuntimeException publicationFailure) {
            Log.warn("WorldGen job failure publication failed: " + publicationFailure.getMessage());
        }
    }

    private void completeJobSafely(JobRecord<String> job, String result, String message) {
        try {
            succeedJob(job, result, message);
        } catch (RuntimeException publicationFailure) {
            Log.warn("WorldGen job completion publication failed: " + publicationFailure.getMessage());
        }
    }

    private void replaySaveProject(Session session, MutationJson payload, WorldGenProject project, JobRecord<String> job) {
        MutationReceipt receipt = mutationReceipt(session, payload);
        if (receipt != null) {
            publishRetainedReceipt(session, payload, receipt,
                "WorldGen Save Result Belongs To An Older Authority; Refreshing");
            return;
        }
        if (job.getStatus() != JobStatus.SUCCEEDED) {
            String message = String.valueOf(job.snapshot().getOrDefault("message", "WorldGen Mutation Failed"));
            sendMutationFailureDiagnosticsSafely(session, payload, project.getId(), message,
                projectRevisionSafely(project.getId()));
            return;
        }
        boolean committed = false;
        try {
            requireMutationEpoch(payload);
            TransactionResult transaction = projectStorage.saveProject(project, payload.mutationUuid(), payload.expectedRevision(),
                mutationIntentScope(session, payload));
            committed = true;
            long revision = transactionRevision(transaction, project.getId());
            String acknowledgement = acknowledgementJson(payload, project.getId(), false);
            recordMutationReceipt(session, payload, MutationReceipt.success(null, catalogRevision(), acknowledgement, revision,
                payload.authorityEpoch(), payload.intentHash()));
            publishCommittedMutation(session, payload, acknowledgement, revision, payload.authorityEpoch(),
                "WorldGen Save Result Belongs To An Older Authority; Refreshing");
        } catch (WorldGenProjectStorage.CommittedMutationException exception) {
            publishCommittedRecipeFailure(session, payload, project.getId(), exception, false);
        } catch (Exception exception) {
            if (committed) {
                Log.warn("WorldGen replayed save committed but response publication failed: " + exception.getMessage());
            } else {
                long revision = exception instanceof ResourceRevisionConflictException conflict
                    ? positiveResponseRevision(conflict.getCurrentRevision(), projectRevisionSafely(project.getId()))
                    : projectRevisionSafely(project.getId());
                String failure = mutationFailureDiagnosticsJson(payload, project.getId(), exception.getMessage());
                try {
                    recordMutationReceipt(session, payload, MutationReceipt.failure(failure, revision,
                        payload.authorityEpoch(), exception.getMessage(), payload.intentHash()));
                } catch (RuntimeException receiptFailure) {
                    Log.warn("WorldGen replayed save failure receipt could not be recorded: " + receiptFailure.getMessage());
                }
                sendMutationFailureDiagnosticsSafely(session, payload, project.getId(), exception.getMessage(), revision);
            }
        }
    }

    private void replayDeleteProject(Session session, MutationJson payload, JobRecord<String> job) {
        MutationReceipt receipt = mutationReceipt(session, payload);
        if (receipt != null) {
            publishRetainedReceipt(session, payload, receipt,
                "WorldGen Delete Result Belongs To An Older Authority; Refreshing");
            return;
        }
        if (job.getStatus() != JobStatus.SUCCEEDED) {
            String message = String.valueOf(job.snapshot().getOrDefault("message", "WorldGen Mutation Failed"));
            sendMutationFailureDiagnosticsSafely(session, payload, payload.resourceId(), message,
                projectRevisionSafely(payload.resourceId()));
            return;
        }
        boolean committed = false;
        try {
            requireMutationEpoch(payload);
            TransactionResult transaction = projectStorage.deleteProject(payload.resourceId(), payload.mutationUuid(),
                payload.expectedRevision(), mutationIntentScope(session, payload));
            committed = true;
            long revision = transactionRevision(transaction, payload.resourceId());
            String acknowledgement = acknowledgementJson(payload, payload.resourceId(), true);
            recordMutationReceipt(session, payload, MutationReceipt.success(null, catalogRevision(), acknowledgement, revision,
                payload.authorityEpoch(), payload.intentHash()));
            publishCommittedMutation(session, payload, acknowledgement, revision, payload.authorityEpoch(),
                "WorldGen Delete Result Belongs To An Older Authority; Refreshing");
        } catch (WorldGenProjectStorage.CommittedMutationException exception) {
            publishCommittedRecipeFailure(session, payload, payload.resourceId(), exception, false);
        } catch (Exception exception) {
            if (committed) {
                Log.warn("WorldGen replayed delete committed but response publication failed: " + exception.getMessage());
            } else {
                long revision = exception instanceof ResourceRevisionConflictException conflict
                    ? positiveResponseRevision(conflict.getCurrentRevision(), projectRevisionSafely(payload.resourceId()))
                    : projectRevisionSafely(payload.resourceId());
                String failure = mutationFailureDiagnosticsJson(payload, payload.resourceId(), exception.getMessage());
                try {
                    recordMutationReceipt(session, payload, MutationReceipt.failure(failure, revision,
                        payload.authorityEpoch(), exception.getMessage(), payload.intentHash()));
                } catch (RuntimeException receiptFailure) {
                    Log.warn("WorldGen replayed delete failure receipt could not be recorded: " + receiptFailure.getMessage());
                }
                sendMutationFailureDiagnosticsSafely(session, payload, payload.resourceId(), exception.getMessage(), revision);
            }
        }
    }

    private void publishRetainedReceipt(Session session, MutationJson mutation, MutationReceipt receipt,
                                        String transitionMessage) {
        if (receipt.authorityEpoch() != authorityEpoch.current()) {
            sendAuthorityTransition(session, mutation, transitionMessage);
            return;
        }
        try {
            sendMutationReceipt(session, receipt);
        } catch (RuntimeException sendFailure) {
            if (receipt.authorityEpoch() != authorityEpoch.current()) {
                sendAuthorityTransition(session, mutation, transitionMessage);
            } else {
                Log.warn("WorldGen retained mutation receipt could not be sent: " + sendFailure.getMessage());
            }
        }
    }

    private void sendMutationReceipt(Session session, MutationReceipt receipt) {
        if (receipt.diagnosticsJson() != null) {
            sendJsonPacket(session, (byte) 0x38, receipt.diagnosticsJson(), receipt.diagnosticsRevision(),
                receipt.authorityEpoch());
        }
        if (receipt.success() && receipt.acknowledgementJson() != null) {
            sendJsonPacket(session, (byte) 0x37, receipt.acknowledgementJson(), receipt.revision(),
                receipt.authorityEpoch());
        }
    }

    private void sendMutationFailureDiagnosticsSafely(Session session, MutationJson mutation, String projectId,
                                                     String message, long revision) {
        try {
            sendMutationFailureDiagnostics(session, mutation, projectId, message, revision);
        } catch (RuntimeException responseFailure) {
            Log.warn("WorldGen mutation failure diagnostics could not be sent: " + responseFailure.getMessage());
        }
    }

    private void publishCommittedRecipeFailure(Session session, MutationJson mutation, String projectId,
                                               WorldGenProjectStorage.CommittedMutationException failure,
                                               boolean advanceCatalog) {
        long revision = transactionRevision(failure.transaction(), projectId);
        if (advanceCatalog) {
            advanceProjectCatalogRevision(revision);
        }
        try {
            JsonObject diagnostics = JsonParser.parseString(
                mutationFailureDiagnosticsJson(mutation, projectId, failure.getMessage())).getAsJsonObject();
            diagnostics.addProperty("committed", true);
            sendJsonPacket(session, (byte) 0x38, diagnostics.toString(), positiveResponseRevision(revision, revision),
                responseEpoch(mutation.authorityEpoch()));
        } catch (RuntimeException responseFailure) {
            Log.warn("WorldGen committed recipe failure diagnostics could not be sent: " + responseFailure.getMessage());
        }
    }

    private void recordMutationReceipt(Session session, MutationJson mutation, MutationReceipt receipt) {
        String key = mutationReceiptKey(session, mutation);
        mutationReceiptsByRequest.put(key, receipt);
        mutationReceiptTimesByKey.put(key, System.currentTimeMillis());
        pruneMutationReceipts();
    }

    private MutationReceipt mutationReceipt(Session session, MutationJson mutation) {
        pruneMutationReceipts();
        return mutationReceiptsByRequest.get(mutationReceiptKey(session, mutation));
    }

    private void pruneMutationReceipts() {
        long cutoff = System.currentTimeMillis() - MUTATION_RECEIPT_RETENTION_MS;
        mutationReceiptTimesByKey.entrySet().removeIf(entry -> {
            if (entry.getValue() >= cutoff) {
                return false;
            }
            mutationReceiptsByRequest.remove(entry.getKey());
            return true;
        });
        int overflow = mutationReceiptsByRequest.size() - MAX_MUTATION_RECEIPTS;
        if (overflow <= 0) {
            return;
        }
        List<String> oldest = mutationReceiptTimesByKey.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(overflow)
            .map(Map.Entry::getKey)
            .toList();
        for (String key : oldest) {
            mutationReceiptTimesByKey.remove(key);
            mutationReceiptsByRequest.remove(key);
        }
    }

    private String mutationReceiptKey(Session session, MutationJson mutation) {
        String actor = session != null ? session.getClientId() : "unknown";
        return requestKey(actor, mutation.requestId()) + '\n' + mutation.mutationId() + '\n'
            + mutation.operationId() + '\n' + canonicalMutationAction(mutation.action()) + '\n'
            + mutation.resourceId() + '\n' + mutation.expectedRevision() + '\n'
            + mutation.intentHash();
    }

    private long transactionRevision(TransactionResult transaction, String projectId) {
        if (transaction == null || projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("WorldGen transaction result is incomplete");
        }
        ExpectedState state = transaction.states().get(new AssetKey("worldgen", projectId));
        if (state == null || state.revision() < 1L) {
            throw new IllegalStateException("WorldGen transaction result has no exact project revision: " + projectId);
        }
        return state.revision();
    }

    private String acknowledgementJson(MutationJson mutation, String projectId, boolean deleted) {
        String resolvedProjectId = requireProjectMutationIdentity(mutation);
        if (projectId == null || projectId.isBlank() || !resolvedProjectId.equals(projectId)) {
            throw new IllegalArgumentException("WorldGen Project Identity Does Not Match Mutation Resource");
        }
        JsonObject acknowledgement = new JsonObject();
        acknowledgement.addProperty("action", deleted ? WORLD_GEN_PROJECT_DELETE_ACTION : WORLD_GEN_PROJECT_SAVE_ACTION);
        acknowledgement.addProperty("projectId", resolvedProjectId);
        acknowledgement.addProperty("resourceId", mutation.resourceId());
        acknowledgement.addProperty("requestId", mutation.requestId());
        acknowledgement.addProperty("mutationId", mutation.mutationId());
        acknowledgement.addProperty("operationId", mutation.operationId());
        if (deleted) {
            acknowledgement.addProperty("deleted", true);
        }
        return acknowledgement.toString();
    }

    private void requireMutationEpoch(MutationJson mutation) {
        if (!acceptsIncomingEpoch(mutation)) {
            throw new IllegalStateException("WorldGen Authority Epoch Is Stale");
        }
    }

    private long responseEpoch(long requestEpoch) {
        long current = authorityEpoch.current();
        return current >= 1L ? current : requestEpoch;
    }

    private String requestKey(String actorClientId, String requestId) {
        String actor = actorClientId == null || actorClientId.isBlank() ? "unknown" : actorClientId;
        return actor + '\n' + (requestId == null ? "" : requestId);
    }

    private void broadcastJob(String action, Object data) {
        for (Session session : subscribedSessions) {
            sendJob(session, action, data);
        }
    }

    private void sendJob(Session session, String action, Object data) {
        Object jobData = data == null ? Map.of() : data;
        if (data instanceof Map<?, ?> values) {
            Map<String, Object> enriched = new LinkedHashMap<>();
            values.forEach((key, value) -> enriched.put(String.valueOf(key), value));
            String requestId = enriched.get("requestId") == null ? null : String.valueOf(enriched.get("requestId"));
            String actorClientId = enriched.get("actorClientId") == null ? null : String.valueOf(enriched.get("actorClientId"));
            String mutationId = requestId == null ? null : mutationIdsByRequest.get(requestKey(actorClientId, requestId));
            if (mutationId != null && !mutationId.isBlank()) {
                enriched.put("mutationId", mutationId);
                enriched.putIfAbsent("operationId", requestId);
            }
            jobData = enriched;
        }
        sendJsonPacket(session, (byte) 0x39, gson.toJson(Map.of(
            "type", "job",
            "action", action == null ? "jobStatus" : action,
            "data", jobData,
            "timestamp", System.currentTimeMillis()
        )));
    }

    private void sendJobSnapshot(Session session) {
        List<Map<String, Object>> snapshots = jobManager.activeOrRecentSnapshot(
            session != null ? session.getClientId() : "unknown", 300000).stream()
            .map(snapshot -> {
                Map<String, Object> enriched = new LinkedHashMap<>(snapshot);
                Object target = enriched.get("target");
                enriched.put("resourceId", target == null ? "" : target);
                return enriched;
            })
            .toList();
        sendJob(session, "jobSnapshot", snapshots);
    }

    private World.Environment parseEnvironment(String value) {
        if (value == null || value.isBlank() || "CUSTOM".equalsIgnoreCase(value)) {
            return World.Environment.NORMAL;
        }
        try {
            return World.Environment.valueOf(value);
        } catch (Exception ignored) {
            return World.Environment.NORMAL;
        }
    }

    private String readJson(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private MutationJson readMutationJson(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException("WorldGen mutation framing is missing");
        }
        int requestIdLength = buffer.getInt();
        if (requestIdLength <= 0 || requestIdLength > 256 || requestIdLength > buffer.remaining()) {
            throw new IllegalArgumentException("WorldGen mutation request identity is invalid");
        }
        byte[] requestBytes = new byte[requestIdLength];
        buffer.get(requestBytes);
        String framedRequestId = new String(requestBytes, StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(readJson(buffer));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("WorldGen mutation envelope must be an object");
        }
        JsonObject envelope = parsed.getAsJsonObject();
        String action = requiredText(envelope, "action");
        String requestId = requiredText(envelope, "requestId");
        if (!framedRequestId.equals(requestId)) {
            throw new IllegalArgumentException("WorldGen framed and envelope request identities differ");
        }
        String mutationId = requiredText(envelope, "mutationId");
        try {
            UUID parsedMutationId = UUID.fromString(mutationId);
            if (!parsedMutationId.toString().equals(mutationId)) {
                throw new IllegalArgumentException("WorldGen mutationId must be canonical");
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("WorldGen mutationId must be a UUID", exception);
        }
        String operationId = requiredText(envelope, "operationId");
        String resourceId = requiredText(envelope, "resourceId");
        long incomingEpoch = requiredLong(envelope, "authorityEpoch", 1L);
        long expectedRevision = requiredLong(envelope, "expectedRevision", 0L);
        JsonElement data = envelope.get("data");
        if (data == null || data.isJsonNull() || !data.isJsonObject()) {
            throw new IllegalArgumentException("WorldGen mutation logical data must be an object");
        }
        JsonObject logicalData = data.getAsJsonObject().deepCopy();
        return new MutationJson(action, requestId, mutationId, operationId, resourceId, incomingEpoch, expectedRevision,
            logicalData, mutationIntentHash("unknown", requestId, mutationId, operationId, action, resourceId,
                expectedRevision, logicalData));
    }

    private MutationJson bindAuthenticatedIntent(Session session, MutationJson mutation) {
        String actorClientId = session != null ? session.getClientId() : "unknown";
        return mutation.withIntentHash(mutationIntentHash(actorClientId, mutation.requestId(), mutation.mutationId(),
            mutation.operationId(), mutation.action(), mutation.resourceId(), mutation.expectedRevision(), mutation.data()));
    }

    private String requiredText(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("WorldGen mutation " + field + " is required");
        }
        return value.getAsString();
    }

    private long requiredLong(JsonObject object, String field, long minimum) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("WorldGen mutation " + field + " is invalid");
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.getAsString());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("WorldGen mutation " + field + " is invalid", exception);
        }
        if (parsed < minimum) {
            throw new IllegalArgumentException("WorldGen mutation " + field + " is invalid");
        }
        return parsed;
    }

    private String mutationIntentHash(String actorClientId, String requestId, String mutationId, String operationId,
                                      String action, String resourceId, long expectedRevision, JsonObject data) {
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("actorClientId", actorClientId == null || actorClientId.isBlank() ? "unknown" : actorClientId);
        intent.put("requestId", requestId);
        intent.put("mutationId", mutationId);
        intent.put("operationId", operationId);
        intent.put("action", canonicalMutationAction(action));
        intent.put("resourceId", resourceId);
        intent.put("expectedRevision", expectedRevision);
        intent.put("data", CanonicalJson.parseOpaque(data.toString()));
        return CanonicalJson.sha256("worldgen-mutation", intent);
    }

    private JsonObject mutationIntentScope(Session session, MutationJson mutation) {
        JsonObject scope = new JsonObject();
        String actorClientId = session != null ? session.getClientId() : "unknown";
        scope.addProperty("actorClientId", actorClientId == null || actorClientId.isBlank() ? "unknown" : actorClientId);
        scope.addProperty("requestId", mutation.requestId());
        scope.addProperty("mutationId", mutation.mutationId());
        scope.addProperty("operationId", mutation.operationId());
        scope.addProperty("action", canonicalMutationAction(mutation.action()));
        scope.addProperty("resourceId", mutation.resourceId());
        scope.addProperty("expectedRevision", mutation.expectedRevision());
        scope.add("payload", mutation.data().deepCopy());
        scope.addProperty("worldGenIntentHash", mutation.intentHash());
        return scope;
    }

    private long projectRevision(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return 0L;
        }
        var stamp = projectStorage.readMutationStamp(projectId);
        return stamp == null ? 0L : stamp.revision();
    }

    private long projectRevisionSafely(String projectId) {
        try {
            long revision = projectRevision(projectId);
            return revision >= 1L ? revision : catalogRevision();
        } catch (RuntimeException ignored) {
            return catalogRevision();
        }
    }

    private long projectResponseRevision(String projectId) {
        return projectRevisionSafely(projectId);
    }

    private long positiveResponseRevision(long revision, long fallback) {
        if (revision >= 1L) {
            return revision;
        }
        if (fallback >= 1L) {
            return fallback;
        }
        return catalogRevision();
    }

    private void initializeProjectCatalogRevision() {
        long currentEpoch = authorityEpoch.current();
        long previousEpoch = projectCatalogAuthorityEpoch.getAndSet(currentEpoch);
        long persistedRevision = 1L;
        for (String projectId : projectStorage.listProjectIds()) {
            persistedRevision = Math.max(persistedRevision, projectRevision(projectId));
        }
        long establishedRevision = persistedRevision;
        projectCatalogRevision.updateAndGet(current -> {
            long next = Math.max(Math.max(1L, current), establishedRevision);
            if (previousEpoch > 0L && previousEpoch != currentEpoch && next < Long.MAX_VALUE) {
                next++;
            }
            return next;
        });
    }

    private long catalogRevision() {
        long currentEpoch = authorityEpoch.current();
        synchronized (this) {
            long observedEpoch = projectCatalogAuthorityEpoch.get();
            if (observedEpoch != currentEpoch) {
                projectCatalogAuthorityEpoch.set(currentEpoch);
                projectCatalogRevision.updateAndGet(current -> current == Long.MAX_VALUE ? current : Math.max(1L, current + 1L));
            }
            return projectCatalogRevision.updateAndGet(current -> Math.max(1L, current));
        }
    }

    private long projectListRevision() {
        long revision = catalogRevision();
        for (String projectId : projectStorage.listProjectIds()) {
            revision = Math.max(revision, projectRevision(projectId));
        }
        projectCatalogRevision.accumulateAndGet(revision, Math::max);
        return revision;
    }

    private void advanceProjectCatalogRevision(long projectRevision) {
        if (projectRevision < 1L) {
            throw new IllegalArgumentException("WorldGen project revision must be positive");
        }
        catalogRevision();
        projectCatalogRevision.updateAndGet(current -> {
            long next = current == Long.MAX_VALUE ? current : current + 1L;
            return Math.max(next, projectRevision);
        });
    }

    private void sendJsonPacket(Session session, byte packetId, String json) {
        sendJsonPacket(session, packetId, json, catalogRevision());
    }

    private void sendJsonPacket(Session session, byte packetId, String json, long revision) {
        sendJsonPacket(session, packetId, json, revision, authorityEpoch.current());
    }

    private void sendJsonPacket(Session session, byte packetId, String json, long revision, long responseEpoch) {
        long started = TemporaryLifecycleDiagnostics.start();
        String outcome = "failed";
        int outputChars = 0;
        try {
        if (revision < 1L) {
            throw new IllegalStateException("WorldGen response revision must be positive");
        }
        if (responseEpoch < 1L) {
            throw new IllegalStateException("WorldGen response authority epoch must be positive");
        }
        if (responseEpoch != authorityEpoch.current()) {
            throw new IllegalStateException("WorldGen response authority epoch changed before publication");
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty(RESPONSE_AUTHORITY_EPOCH_FIELD, responseEpoch);
        envelope.addProperty(RESPONSE_REVISION_FIELD, revision);
        envelope.add(RESPONSE_DATA_FIELD, responseData(json));
        byte[] jsonBytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);
        DataMessage message = new DataMessage();
        message.setChannel(channelId);
        message.setPayload(buffer.array());
        codec.sendMessage(session.getConnection().getFrameSender(), message, channelId, jsonBytes.length > 1024);
        outputChars = jsonBytes.length;
        outcome = "sent";
        } finally {
            TemporaryLifecycleDiagnostics.event("worldgen_response", started,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "worldgen", "operation",
                    "0x" + String.format("%02X", packetId), "outputChars", outputChars,
                    "revision", revision, "authorityEpoch", responseEpoch, "outcome", outcome)));
        }
    }

    private JsonElement responseData(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            return new JsonPrimitive(json);
        }
    }

    private boolean requiresMutationEpoch(byte packetId) {
        return switch (packetId) {
            case 0x20, 0x21, 0x22, 0x30, 0x31, 0x33 -> true;
            default -> false;
        };
    }

    private boolean acceptsIncomingEpoch(MutationJson mutation) {
        return mutation != null && mutation.authorityEpoch() >= 1L
            && authorityEpoch.acceptsTyped(mutation.authorityEpoch());
    }

    private boolean isProjectMutation(String action) {
        String canonicalAction = canonicalMutationAction(action);
        return WORLD_GEN_PROJECT_SAVE_ACTION.equals(canonicalAction)
            || WORLD_GEN_PROJECT_DELETE_ACTION.equals(canonicalAction);
    }

    private String requireProjectMutationIdentity(MutationJson mutation) {
        if (mutation == null || mutation.resourceId() == null || mutation.resourceId().isBlank()) {
            throw new IllegalArgumentException("WorldGen Project Resource Identity Is Required");
        }
        String action = canonicalMutationAction(mutation.action());
        String field = WORLD_GEN_PROJECT_SAVE_ACTION.equals(action) ? "id" : "projectId";
        JsonElement value = mutation.data() == null ? null : mutation.data().get(field);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()
            || !mutation.resourceId().equals(value.getAsString())) {
            throw new IllegalArgumentException("WorldGen Project Identity Does Not Match Mutation Resource");
        }
        return value.getAsString();
    }

    private String requirePreviewMutationIdentity(MutationJson mutation) {
        if (mutation == null || mutation.resourceId() == null || mutation.resourceId().isBlank()) {
            throw new IllegalArgumentException("WorldGen Preview Resource Identity Is Required");
        }
        JsonElement value = mutation.data() == null ? null : mutation.data().get("previewId");
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()
            || !mutation.resourceId().equals(value.getAsString())) {
            throw new IllegalArgumentException("WorldGen Preview Identity Does Not Match Mutation Resource");
        }
        return previewManager.requirePreviewId(value.getAsString());
    }

    private String canonicalMutationAction(String action) {
        return switch (action == null ? "" : action) {
            case "saveWorldGenProject", WORLD_GEN_PROJECT_SAVE_ACTION -> WORLD_GEN_PROJECT_SAVE_ACTION;
            case "deleteWorldGenProject", WORLD_GEN_PROJECT_DELETE_ACTION -> WORLD_GEN_PROJECT_DELETE_ACTION;
            default -> action == null ? "" : action;
        };
    }

    private String mutationAction(byte packetId) {
        return switch (packetId) {
            case 0x20 -> "worldGenSave";
            case 0x21 -> "worldGenPreviewCreate";
            case 0x22 -> "worldGenPreviewStop";
            case 0x30 -> WORLD_GEN_PROJECT_SAVE_ACTION;
            case 0x31 -> "worldGenPreviewApply";
            case 0x33 -> WORLD_GEN_PROJECT_DELETE_ACTION;
            default -> "";
        };
    }

    private record PreviewCreateRequest(WorldGenGraph graph, String previewId, String environment, long seed, String playerUuid) {
    }

    private record PreviewApplyRequest(String projectId, WorldGenProject draftProject, WorldGenProject project, String previewId, String environment, long seed, String playerUuid) {
    }

    private record PreviewStopRequest(String previewId) {
    }

    private record MutationJson(String action, String requestId, String mutationId, String operationId, String resourceId,
                                 long authorityEpoch, long expectedRevision, JsonObject data, String intentHash) {
        private String json() {
            return data.toString();
        }

        public String intentHash() {
            return intentHash;
        }

        private MutationJson withIntentHash(String authenticatedIntentHash) {
            return new MutationJson(action, requestId, mutationId, operationId, resourceId, authorityEpoch,
                expectedRevision, data, authenticatedIntentHash);
        }

        private UUID mutationUuid() {
            return UUID.fromString(mutationId);
        }
    }

    private record JobAdmission(JobRecord<String> job, JobManager.StartDisposition disposition,
                                JobExecutionLease startedExecution) {
        private boolean acquired() {
            return disposition == JobManager.StartDisposition.ACQUIRED;
        }

        private boolean inFlightDuplicate() {
            return disposition == JobManager.StartDisposition.IN_FLIGHT_DUPLICATE;
        }

        private boolean terminalReplay() {
            return disposition == JobManager.StartDisposition.TERMINAL_REPLAY;
        }

        private boolean identityConflict() {
            return disposition == JobManager.StartDisposition.IDENTITY_CONFLICT;
        }
    }

    private record MutationReceipt(boolean success, String diagnosticsJson, long diagnosticsRevision,
                                   String acknowledgementJson, long revision, long authorityEpoch,
                                   String failureMessage, String intentHash) {
        private static MutationReceipt success(String diagnosticsJson, long diagnosticsRevision,
                                               String acknowledgementJson, long revision, long authorityEpoch,
                                               String intentHash) {
            return new MutationReceipt(true, diagnosticsJson, diagnosticsRevision, acknowledgementJson, revision,
                authorityEpoch, null, intentHash);
        }

        private static MutationReceipt failure(String diagnosticsJson, long revision, long authorityEpoch,
                                               String failureMessage, String intentHash) {
            return new MutationReceipt(false, diagnosticsJson, revision, null, revision, authorityEpoch, failureMessage,
                intentHash);
        }
    }
}
