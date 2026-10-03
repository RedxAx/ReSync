package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.Log;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.FunctionParameter;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceCreateResult;
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceDuplicateRequest;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodec;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.MigrationReportsPersistenceParticipant;
import restudio.resync.modules.flow.CoreResourceMutationBus;
import restudio.resync.modules.flow.CoreResourceMutationCheckpoint;
import restudio.resync.modules.flow.CoreResourceMutationTransition;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationAdmission;
import restudio.resync.modules.flow.FlowResourceKey;
import restudio.resync.modules.flow.FlowResourceMutationContext;
import restudio.resync.modules.flow.FlowResourceMutationLease;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.storage.StorageSafety;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetProjectMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

public final class SqliteProtocolResourceMutationAuthority implements ProtocolResourceMutationAuthority, AutoCloseable {
    static final String SCHEMA = "resource-mutation-authority-v8";
    private static final String PRE_EVOLUTION_SCHEMA = "resource-mutation-authority-v7";
    private static final String PREVIOUS_SCHEMA = "resource-mutation-authority-v6";
    private static final String LEGACY_SCHEMA = "resource-mutation-authority-v5";
    private static final String OLDEST_SCHEMA = "resource-mutation-authority-v4";
    private static final String ANCIENT_SCHEMA = "resource-mutation-authority-v3";
    private static final String EARLIEST_SCHEMA = "resource-mutation-authority-v2";
    private static final String FIRST_SCHEMA = "resource-mutation-authority-v1";
    static final String LEGACY_ACTOR = "legacy-unattributed";
    private static final String LEGACY_ADMIN_RECOVERY_ACTOR = "legacy-admin-recovery";
    private static final String CORE_REVISION_REPAIR_ACTOR = "core-revision-repair";
    private static final String LEGACY_CORE_PAYLOAD_KIND = "legacy-flow-graph-v3";
    static final String ACTOR_CONFLICT_CODE = ProtocolRejectionCode.RESOURCE_MUTATION_ACTOR_CONFLICT.legacyValue();
    private static final OwnerId PROTOCOL_OWNER = new OwnerId("restudio.resync");
    private static final Set<String> CORE_RESOURCE_TYPES = Set.of("flow", "function", "command");
    private static final String PROJECT_METADATA_TYPE = "project_metadata";
    private static final String PROJECT_METADATA_LINEAGE_TYPE = "project_metadata.lineage";
    private static final String PROJECT_METADATA_LINEAGE_ID = "project";
    private static final int MAX_LEGACY_ADMIN_CHAIN = 32;
    private static final int MAX_COORDINATOR_BINDINGS = 4096;
    private static final ContentHash UNAVAILABLE_CORE_HASH = new ContentHash("0".repeat(64));
    private static final Gson PRETTY_LINEAGE_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ContractRef<ResourceTypeId> DOCUMENT_TYPE = ContractRef.of(PROTOCOL_OWNER, new ResourceTypeId("resource.document"));
    private static final Set<String> SUPPORTED_CAPABILITIES = Set.of(
        "resources", "resource_revisions", "resource_events", "opaque_resources", "authorization", "asset_integrity",
        "transaction_recovery", "deltas", "diagnostics", "resource_activation", "resource_create_presentation"
    );
    private final FlowResourceRegistry registry;
    private final ServerId serverId;
    private final ProtocolResourceAuthorizer authorizer;
    private final CoreGraphResourceAuthority coreAuthority;
    private final AuthorityEpoch authorityEpoch;
    private final AggregateResourceCreateStorage aggregateCreateStorage;
    private final MigrationReportsPersistenceParticipant migrationReports;
    private final CoreCatalogBindingMigration catalogMigration;
    private final Path scopeRoot;
    private final Path relativeDatabasePath;
    private Path activeScopeRoot;
    private Path databasePath;
    private Connection connection;
    private final Gson gson = new Gson();
    private final CoreGraphStorageBoundary coreBoundary = new CoreGraphStorageBoundary();
    private final ResourcePayloadCodec<Map<String, Object>> payloadCodec = ResourcePayloadCodecs.json();
    private final Map<UUID, State> coupledProjectMetadataRepairs = new LinkedHashMap<>();
    private ProjectMetadataCache projectMetadataCache;
    private boolean recoveryBlocked;
    private String recoveryReason = "";
    private boolean mutationAdmissionClosed;
    private boolean catalogRecoveryRequired;
    private boolean coreReadAuthorityReady;
    private boolean catalogSettlementPending;
    private PersistenceState persistenceState = PersistenceState.OPEN;
    private volatile boolean durableCapability;

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath) {
        this(registry, serverId, databasePath, CoreGraphResourceAuthority.unavailable(),
            ProtocolResourceAuthorizer.serverGranted(), requireExplicitAuthorityEpoch());
    }

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                   ProtocolResourceAuthorizer authorizer) {
        this(registry, serverId, databasePath, CoreGraphResourceAuthority.unavailable(), authorizer, requireExplicitAuthorityEpoch());
    }

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                   CoreGraphResourceAuthority coreAuthority) {
        this(registry, serverId, databasePath, coreAuthority, ProtocolResourceAuthorizer.serverGranted(), requireExplicitAuthorityEpoch());
    }

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                   CoreGraphResourceAuthority coreAuthority,
                                                   ProtocolResourceAuthorizer authorizer) {
        this(registry, serverId, databasePath, coreAuthority, authorizer, requireExplicitAuthorityEpoch());
    }

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                   CoreGraphResourceAuthority coreAuthority, AuthorityEpoch authorityEpoch) {
        this(registry, serverId, databasePath, coreAuthority, ProtocolResourceAuthorizer.serverGranted(), authorityEpoch);
    }

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                   CoreGraphResourceAuthority coreAuthority,
                                                   ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch) {
        this(registry, serverId, databasePath, coreAuthority, authorizer, authorityEpoch,
            AggregateResourceCreateStorage.unavailable());
    }

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                   CoreGraphResourceAuthority coreAuthority,
                                                   ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch,
                                                   AggregateResourceCreateStorage aggregateCreateStorage) {
        this(registry, serverId, databasePath, coreAuthority, authorizer, authorityEpoch, aggregateCreateStorage, null);
    }

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                   CoreGraphResourceAuthority coreAuthority,
                                                   ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch,
                                                   AggregateResourceCreateStorage aggregateCreateStorage,
                                                   MigrationReportsPersistenceParticipant migrationReports) {
        this(registry, serverId, databasePath, coreAuthority, authorizer, authorityEpoch, aggregateCreateStorage,
            migrationReports, CoreCatalogBindingMigration.load());
    }

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                   CoreGraphResourceAuthority coreAuthority,
                                                   ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch,
                                                   AggregateResourceCreateStorage aggregateCreateStorage,
                                                   MigrationReportsPersistenceParticipant migrationReports,
                                                   CatalogStartup catalogStartup) {
        this(registry, serverId, databasePath, coreAuthority, authorizer, authorityEpoch, aggregateCreateStorage,
            migrationReports, CoreCatalogBindingMigration.load(), catalogStartup);
    }

    SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                            CoreGraphResourceAuthority coreAuthority,
                                            ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch,
                                            AggregateResourceCreateStorage aggregateCreateStorage,
                                            MigrationReportsPersistenceParticipant migrationReports,
                                            CoreCatalogBindingMigration catalogMigration) {
        this(registry, serverId, databasePath, coreAuthority, authorizer, authorityEpoch, aggregateCreateStorage,
            migrationReports, catalogMigration, CatalogStartup.IMMEDIATE);
    }

    private SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                     CoreGraphResourceAuthority coreAuthority,
                                                     ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch,
                                                     AggregateResourceCreateStorage aggregateCreateStorage,
                                                     MigrationReportsPersistenceParticipant migrationReports,
                                                     CoreCatalogBindingMigration catalogMigration,
                                                     CatalogStartup catalogStartup) {
        this.registry = Objects.requireNonNull(registry, "Resource registry is required");
        this.serverId = Objects.requireNonNull(serverId, "Server ID is required");
        this.coreAuthority = coreAuthority == null ? CoreGraphResourceAuthority.unavailable() : coreAuthority;
        this.authorizer = ProtocolResourceAuthorizer.require(authorizer);
        this.aggregateCreateStorage = Objects.requireNonNull(aggregateCreateStorage, "Aggregate create storage is required");
        this.migrationReports = migrationReports;
        this.catalogMigration = Objects.requireNonNull(catalogMigration,
            "Core catalog binding migration is required");
        this.catalogSettlementPending = Objects.requireNonNull(catalogStartup, "Catalog startup mode is required")
            == CatalogStartup.DEFERRED;
        AuthorityEpoch boundAuthorityEpoch = Objects.requireNonNull(authorityEpoch, "Authority epoch is required");
        if (boundAuthorityEpoch.current() < 1L) {
            throw new IllegalArgumentException("Authority epoch must be positive");
        }
        this.authorityEpoch = boundAuthorityEpoch;
        this.databasePath = requireDatabasePath(databasePath);
        Path parent = this.databasePath.getParent();
        if (parent == null || parent.getParent() == null) {
            throw new IllegalArgumentException("Resource mutation database must be below a persistence scope root");
        }
        this.scopeRoot = MigrationPaths.requirePath(parent.getParent(), "scopeRoot");
        this.relativeDatabasePath = scopeRoot.relativize(this.databasePath);
        this.activeScopeRoot = scopeRoot;
        try {
            Path databaseParent = this.databasePath.getParent();
            Files.createDirectories(databaseParent);
            MigrationPaths.requireDirectory(databaseParent, "resource mutation database root");
            MigrationPaths.requireNoSymlinkTraversal(scopeRoot, this.databasePath);
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.databasePath, new Properties());
            configure();
            migrate();
            recoverCommittedAggregateCreates();
            if (!recoveryBlocked) {
                restoreCommittedCoreTransitionHighWater();
                Set<ServerResourceLocator> blockedTransitions = recoverCommittedCoreTransitions();
                recoverPending(blockedTransitions);
                recoverCommittedCoreTransitions();
            }
            if (!recoveryBlocked) {
                migrateCoreCatalogBindings();
                recoverCoreRevisionSkews();
                reconcileProjectMetadataState();
                if (!catalogSettlementPending) {
                    migrateCompatibleCoreCatalogBindings();
                    evolveCoreCatalogBindings();
                }
            }
            coreReadAuthorityReady = !catalogSettlementPending && !recoveryBlocked && establishCoreReadAuthority();
            publishDurability();
        } catch (Exception exception) {
            closeQuietly(connection);
            throw new IllegalStateException("Failed To Initialize Durable Resource Mutation Authority", exception);
        }
    }

    public SqliteProtocolResourceMutationAuthority(FlowResourceRegistry registry, ServerId serverId, Path databasePath,
                                                   ProtocolResourceAuthorizer authorizer,
                                                   CoreGraphResourceAuthority coreAuthority) {
        this(registry, serverId, databasePath, coreAuthority, authorizer, requireExplicitAuthorityEpoch());
    }

    @Override
    public synchronized ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connectionInfo, Session session,
                                                               ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperation operation) {
        long mutationStarted = TemporaryLifecycleDiagnostics.start();
        String clientId = ProtocolRequestAuthority.trustedClientId(connectionInfo, session);
        if (clientId == null || !validResourceEnvelope(envelope, operation)
            || !ProtocolRequestAuthority.owns(serverId, envelope, operation)) {
            return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                "Protocol resource mutation is not authorized");
        }
        if (!authorizer.authorize(connectionInfo, session, envelope, operation)) {
            return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                "Protocol resource mutation is not authorized");
        }
        return mutateAdmitted(clientId, connectionInfo.getConnectionId(), envelope, operation, mutationStarted);
    }

    @Override
    public synchronized ProtocolEnvelopeDispatchResult mutateOperator(ProtocolRequestAuthority.OperatorGrant grant,
                                                                       ProtocolEnvelope<Map<String, Object>> envelope,
                                                                       ResourceOperation operation) {
        if (grant == null || !validResourceEnvelope(envelope, operation)
            || !ProtocolRequestAuthority.owns(serverId, envelope, operation) || !grant.matches(serverId, envelope)) {
            return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                "Local operator resource mutation is not authorized");
        }
        if (!authorityEpoch.acceptsTyped(envelope.authorityEpoch())) {
            return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "Protocol envelope authority epoch is stale");
        }
        if (!grant.claim(serverId, envelope)) {
            return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                "Local operator resource admission was already consumed");
        }
        return mutateAdmitted(grant.actorId(), null, envelope, operation, TemporaryLifecycleDiagnostics.start());
    }

    private ProtocolEnvelopeDispatchResult mutateAdmitted(String clientId, Integer connectionId,
                                                            ProtocolEnvelope<Map<String, Object>> envelope,
                                                            ResourceOperation operation, long mutationStarted) {
        if (requiresMutationEpoch(operation) && !authorityEpoch.acceptsTyped(envelope.authorityEpoch())) {
            return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "Protocol envelope authority epoch is stale");
        }
        if (catalogSettlementPending) {
            return unavailable(ProtocolRejectionCode.RESOURCE_RECOVERY_BLOCKED,
                "Resource catalog startup is not settled");
        }
        if (recoveryBlocked) {
            return unavailable(ProtocolRejectionCode.RESOURCE_RECOVERY_BLOCKED,
                "Resource mutation recovery is blocked");
        }
        if (persistenceState != PersistenceState.OPEN) {
            return unavailable(persistenceState == PersistenceState.CLOSED
                    ? ProtocolRejectionCode.RESOURCE_AUTHORITY_CLOSED : ProtocolRejectionCode.RESOURCE_MUTATION_QUIESCED,
                persistenceState == PersistenceState.CLOSED
                    ? "Resource mutation authority is closed" : "Resource mutation authority is quiesced");
        }
        if (!supports(operation)) {
            return rejectUnsupported(operation);
        }
        ServerResourceLocator operationResource = operationResource(operation);
        if (isCoreResource(operationResource) && coreAuthority.available() && !authoritativeCoreReads()) {
            return unavailable(ProtocolRejectionCode.RESOURCE_RECOVERY_BLOCKED,
                "Core resource authority is not ready");
        }
        if (!validMutationLocator(operation)) {
            return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED,
                "Project metadata resource ID is not authoritative");
        }
        try {
            Command command = command(envelope, operation);
            if (command.presentation() != null && !aggregateCreateStorage.available(command.responseResource())) {
                return unavailable(ProtocolRejectionCode.RESOURCE_DURABILITY_UNAVAILABLE,
                    "Aggregate resource create storage is unavailable");
            }
            Map<String, Object> diagnosticIdentity = diagnosticIdentity(command, envelope,
                connectionId);
            TemporaryLifecycleDiagnostics.event("authority_admission", mutationStarted,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", command.operationName(), "outcome", "accepted"));
            try {
                long recoveryStarted = TemporaryLifecycleDiagnostics.start();
                Set<ServerResourceLocator> blockedTransitions = recoverCommittedCoreTransitions();
                TemporaryLifecycleDiagnostics.event("transition_recovery_check", recoveryStarted,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "blockedCount", blockedTransitions.size()));
                if (blockedTransitions.contains(command.responseResource())) {
                    return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                        "An earlier Core resource transition is awaiting delivery");
                }
            } catch (SQLException | RuntimeException exception) {
                blockRecovery("Committed Core resource transition recovery failed: " + exception.getMessage());
                return unavailable(ProtocolRejectionCode.RESOURCE_RECOVERY_BLOCKED,
                    "Resource mutation recovery is blocked");
            }
            if (!ownsType(command.responseResource().type())
                || command.source() != null && !ownsType(command.source().type())) {
                return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                    "Protocol resource type is not authorized");
            }
            if (isCoreResource(command.responseResource()) && !coreAuthority.available()) {
                return unavailable(ProtocolRejectionCode.RESOURCE_DURABILITY_UNAVAILABLE,
                    "Core graph resource authority is unavailable");
            }
            long receiptStarted = TemporaryLifecycleDiagnostics.start();
            MutationRow previous = mutation(command.mutationId());
            TemporaryLifecycleDiagnostics.event("mutation_receipt_read", receiptStarted,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "outcome", previous == null ? "absent" : "found",
                    "receiptStatus", previous == null ? null : previous.status()));
            if (previous != null && !sameReplayActor(previous.actorId(), clientId)) {
                TemporaryLifecycleDiagnostics.event("mutation_conflict", 0L,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "conflict", "actor"));
                return actorConflict();
            }
            String requestFingerprint = fingerprint(command, clientId);
            if (previous != null && !previous.fingerprint().equals(requestFingerprint)) {
                TemporaryLifecycleDiagnostics.event("mutation_conflict", 0L,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "conflict", "idempotency",
                        "authoritativeRevision", previous.resultRevision()));
                return conflict(envelope, command.kind(), command.responseResource(), document(state(command.responseResource())));
            }
            if (previous != null && previous.status() != Status.PENDING && previous.fingerprint().equals(requestFingerprint)) {
                TemporaryLifecycleDiagnostics.event("mutation_replay", 0L,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "outcome", previous.status(),
                        "authoritativeRevision", previous.resultRevision()));
                return storedResponse(envelope, command.kind(), previous);
            }
            if (isCoreResource(command.responseResource()) && command.presentation() == null) {
                return mutateCore(envelope, command, clientId, previous, requestFingerprint);
            }
            FlowResourceAdapter<Object> adapter = adapter(command.resource());
            if (command.presentation() == null && (adapter == null || !adapter.durable() || !supportsExactMutation(command))) {
                return unavailable(ProtocolRejectionCode.RESOURCE_DURABILITY_UNAVAILABLE,
                    "Resource adapter does not expose an authoritative durable " + command.operationName() + " boundary");
            }
            List<FlowResourceKey> mutationKeys = mutationKeys(command);
            if (mutationKeys.isEmpty() || hasDuplicateKeys(mutationKeys)) {
                return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_FAILED,
                    "The resource mutation key set must contain unique typed resource keys");
            }
            Optional<FlowResourceMutationLease> acquired = registry.mutationAdmission().tryAcquire(mutationKeys,
                command.mutationId().toString(), FlowResourceMutationAdmission.AdmissionKind.NEW);
            if (acquired.isEmpty()) {
                TemporaryLifecycleDiagnostics.event("mutation_admission", 0L,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "outcome", "busy", "keyCount", mutationKeys.size()));
                return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                    "The resource mutation is already admitted");
            }
            TemporaryLifecycleDiagnostics.event("mutation_admission", 0L,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "outcome", "acquired", "keyCount", mutationKeys.size()));
            FlowResourceMutationLease lease = acquired.get();
            try {
                if (previous != null && previous.fingerprint().equals(requestFingerprint)) {
                    if (previous.status() == Status.PENDING) {
                        return reconcilePending(envelope, command, previous, lease);
                    }
                    return storedResponse(envelope, command.kind(), previous);
                }
                if (hasOtherPendingMutation(command)) {
                    return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                        "An earlier mutation for this resource is awaiting durable recovery");
                }
                if (command.presentation() == null) {
                    validatePayloadIdentity(command, adapter);
                }
                if (!isProjectMetadata(command.responseResource()) && adapter(projectMetadataResource()) != null) {
                    synchronize(projectMetadataResource());
                }
                State requestedState = isCoreResource(command.resource()) ? coreStateOrNull(command.resource()) : synchronize(command.resource());
                State sourceState = command.source() == null ? null : synchronize(command.source());
                State targetState = command.target() == null || command.target().equals(command.resource())
                    ? requestedState : isCoreResource(command.target()) ? coreStateOrNull(command.target())
                    : synchronize(command.target());
                String preconditionHash = preconditionHash(command, requestedState, sourceState, targetState);
                Command appliedCommand = normalizeAggregateCoreCreate(command, requestedState);
                Outcome outcome = outcome(appliedCommand, requestedState, sourceState, targetState);
                TemporaryLifecycleDiagnostics.event("sqlite_mutation_state", TemporaryLifecycleDiagnostics.start(),
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", command.operationName(),
                        "storedState", requestedState == null ? "missing" : "present", "validated", true,
                        "outcome", outcome.status()));
                if (legacyInactive(envelope, outcome.resultActivationState(), outcome.resultDeleted())) {
                    return rejectInactiveLegacyResource();
                }
                if (outcome.status() != Status.PENDING) {
                    MutationRow terminal = insertTerminal(appliedCommand, clientId, requestFingerprint, preconditionHash, outcome);
                    TemporaryLifecycleDiagnostics.event("sqlite_mutation_terminal", 0L,
                        TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", command.operationName(),
                            "outcome", terminal.status(), "receiptPersisted", true,
                            "responsePrepared", true, "resultRevision", terminal.resultRevision()));
                    return storedResponse(envelope, command.kind(), terminal);
                }
                MutationRow pending = command.presentation() == null
                    ? insertPending(appliedCommand, clientId, requestFingerprint, preconditionHash, outcome)
                    : insertAggregatePending(appliedCommand, clientId, requestFingerprint, preconditionHash, outcome);
                if (appliedCommand.presentation() != null) {
                    try {
                        AggregateCreateState aggregate = applyAggregateCreate(appliedCommand, outcome, clientId, lease);
                        verifyPostApplyPrecondition(appliedCommand, pending);
                        MutationRow committed = commitAggregateApplied(pending, aggregate);
                        cacheProjectMetadataAfterCommit(aggregate.projectMetadata());
                        TemporaryLifecycleDiagnostics.event("sqlite_mutation_commit", 0L,
                            TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", command.operationName(),
                                "outcome", "committed", "durableCommitted", true, "receiptPersisted", true,
                                "responsePrepared", true, "resultRevision", committed.resultRevision()));
                        return storedResponse(envelope, command.kind(), committed);
                    } catch (AggregateResourceCreateStorage.PreCommitRejection rejection) {
                        MutationRow rejected = finishRejected(pending, rejection.errorCode(), rejection.getMessage());
                        TemporaryLifecycleDiagnostics.event("sqlite_mutation_terminal", 0L,
                            TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", command.operationName(),
                                "outcome", rejected.status(), "receiptPersisted", true,
                                "responsePrepared", true, "resultRevision", rejected.resultRevision()));
                        return storedResponse(envelope, command.kind(), rejected);
                    } catch (RuntimeException exception) {
                        Log.error("Aggregate resource create " + pending.mutationId()
                            + " requires durable recovery: " + safeMessage(exception), exception);
                        return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                            "Resource mutation is awaiting durable recovery");
                    }
                }
                FlowOperationResult<?> result;
                try {
                    result = applyExternal(command, outcome, clientId, lease);
                } catch (RuntimeException exception) {
                    Log.error("Resource mutation " + pending.mutationId() + " requires durable recovery after "
                        + command.operationName() + " " + command.responseResource().canonicalText() + ": " + safeMessage(exception), exception);
                    return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                        "Resource mutation is awaiting durable recovery");
                }
                if (!result.success()) {
                    if (mayHaveAppliedExternally(result)) {
                        Log.error("Resource mutation " + pending.mutationId() + " requires durable recovery after "
                            + command.operationName() + " " + command.responseResource().canonicalText() + ": "
                            + result.errorCode() + " " + result.message());
                        return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                            "Resource mutation is awaiting durable recovery");
                    }
                    MutationRow rejected = finishRejected(pending, result.errorCode(), result.message());
                    return storedResponse(envelope, command.kind(), rejected);
                }
                try {
                    State after = synchronizeExternal(command.responseResource(), command.mutationId(), outcome.resultRevision(),
                        outcome.resultHash(), outcome.resultDeleted(), outcome.resultPayload(), outcome.resultActivationState());
                    completePostCommitRecovery(command.responseResource(), command.mutationId(), outcome.resultRevision(),
                        outcome.resultDeleted());
                    verifyPostApplyPrecondition(command, pending);
                    MutationRow committed = commitApplied(pending, after);
                    TemporaryLifecycleDiagnostics.event("sqlite_mutation_commit", 0L,
                        TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", command.operationName(),
                            "outcome", "committed", "durableCommitted", true, "receiptPersisted", true,
                            "responsePrepared", true, "resultRevision", committed.resultRevision()));
                    return storedResponse(envelope, command.kind(), committed);
                } catch (RuntimeException exception) {
                    blockRecovery("Resource mutation " + pending.mutationId() + " cannot be committed: " + safeMessage(exception));
                    Log.error(recoveryReason, exception);
                    return unavailable(ProtocolRejectionCode.RESOURCE_RECOVERY_BLOCKED,
                        "Resource mutation recovery is blocked");
                }
            } finally {
                lease.close();
            }
        } catch (RuntimeException exception) {
            if (exception instanceof CoreGraphMutationValidationException validationFailure) {
                return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED,
                    validationFailure.actionableMessage());
            }
            Log.error("Resource mutation failed: " + safeMessage(exception), exception);
            return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_FAILED, "Resource mutation failed");
        }
    }

    @Override
    public boolean durable() {
        return durableCapability;
    }

    private void publishDurability() {
        durableCapability = !catalogSettlementPending && !recoveryBlocked && persistenceState == PersistenceState.OPEN;
    }

    @Override
    public boolean supports(ResourceOperation operation) {
        ServerResourceLocator resource = operationResource(operation);
        if (isCoreResource(resource)) {
            return operation.kind() == ResourceOperationKind.CREATE || operation.kind() == ResourceOperationKind.SAVE
                || operation.kind() == ResourceOperationKind.DELETE || operation.kind() == ResourceOperationKind.DUPLICATE
                || operation.kind() == ResourceOperationKind.ACTIVATE;
        }
        return operation instanceof ResourceCreateRequest<?> || operation instanceof ResourceSaveRequest<?>
            || operation instanceof ResourceDeleteRequest || operation instanceof ResourceDuplicateRequest
            || operation instanceof ResourceActivateRequest;
    }

    @Override
    public boolean supportsActivation() {
        return true;
    }

    @Override
    public synchronized boolean authoritativeReads() {
        if (catalogSettlementPending || recoveryBlocked || persistenceState != PersistenceState.OPEN || connection == null) {
            return false;
        }
        try {
            return !connection.isClosed() && connection.isValid(1);
        } catch (SQLException exception) {
            return false;
        }
    }

    @Override
    public synchronized boolean authoritativeCoreReads() {
        return authoritativeReads() && coreReadAuthorityReady && coreAuthority.available();
    }

    @Override
    public synchronized boolean allowLegacyReads() {
        return false;
    }

    @Override
    public synchronized ResourceDocument<Map<String, Object>> load(ServerResourceLocator resource) {
        long started = TemporaryLifecycleDiagnostics.start();
        if (resource == null || !serverId.equals(resource.serverId()) || !ownsType(resource.type())) {
            TemporaryLifecycleDiagnostics.event("authority_load", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                    resource == null ? null : resource.canonicalText(), null, null, null, null, authorityEpoch.current(), null),
                    "outcome", "rejected"));
            return null;
        }
        requireReadable();
        ServerResourceLocator canonical = canonicalResource(resource);
        if (isCoreResource(canonical)) {
            requireCoreReadable();
        }
        ResourceDocument<Map<String, Object>> document = isCoreResource(canonical)
            ? document(synchronizeCore(canonical)) : document(synchronize(canonical));
        TemporaryLifecycleDiagnostics.event("authority_load", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, canonical.canonicalText(),
                document == null ? null : document.mutationId(), null, null, document == null ? null : document.revision(),
                authorityEpoch.current(), null), "outcome", document == null ? "missing" : "loaded"));
        return document;
    }

    @Override
    public synchronized List<ResourceDocument<Map<String, Object>>> list(ServerId requestedServerId,
                                                                           ContractRef<ResourceTypeId> type,
                                                                           String search) {
        long started = TemporaryLifecycleDiagnostics.start();
        if (requestedServerId == null || !serverId.equals(requestedServerId) || !ownsType(type)) {
            TemporaryLifecycleDiagnostics.event("authority_list", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                    type == null ? null : type.id().value(), null, null, null, null, authorityEpoch.current(), null),
                    "outcome", "rejected", "count", 0));
            return List.of();
        }
        requireReadable();
        ServerResourceLocator probe = new ServerResourceLocator(serverId, type, "protocol-read");
        if (isCoreResource(probe)) {
            requireCoreReadable();
            List<ResourceDocument<Map<String, Object>>> result = listCore(type, search);
            TemporaryLifecycleDiagnostics.event("authority_list", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, type.id().value(), null,
                    null, null, null, authorityEpoch.current(), null), "outcome", "listed", "count", result.size()));
            return result;
        }
        FlowResourceAdapter<Object> adapter = adapter(probe);
        if (adapter == null || (!adapter.supportedOperations().contains("discover") && !adapter.supportedOperations().contains("query"))) {
            TemporaryLifecycleDiagnostics.event("authority_list", started,
                TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, type.id().value(), null,
                    null, null, null, authorityEpoch.current(), null), "outcome", "unsupported", "count", 0));
            return List.of();
        }
        String normalizedSearch = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        List<String> ids = adapter.listIds().stream()
            .filter(id -> id != null && (normalizedSearch.isBlank() || id.toLowerCase(Locale.ROOT).contains(normalizedSearch)))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        List<ResourceDocument<Map<String, Object>>> documents = new ArrayList<>();
        for (String id : ids) {
            State state = synchronize(new ServerResourceLocator(serverId, type, id));
            if (state != null && !state.deleted()) {
                documents.add(document(state));
            }
        }
        List<ResourceDocument<Map<String, Object>>> result = List.copyOf(documents);
        TemporaryLifecycleDiagnostics.event("authority_list", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, type.id().value(), null,
                null, null, null, authorityEpoch.current(), null), "outcome", "listed", "count", result.size()));
        return result;
    }

    private List<ResourceDocument<Map<String, Object>>> listCore(ContractRef<ResourceTypeId> type, String search) {
        if (!coreAuthority.available()) {
            throw new IllegalStateException(CoreGraphResourceAuthority.UNAVAILABLE_MESSAGE);
        }
        String normalizedSearch = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        Map<String, ServerResourceLocator> resources = new LinkedHashMap<>();
        for (CoreGraphResourceAuthority.CoreGraphResourceState state : coreAuthority.list(type.id().value())) {
            ServerResourceLocator resource = state.resource();
            if (!serverId.equals(resource.serverId()) || !type.equals(resource.type()) || !isCoreResource(resource)) {
                throw new IllegalStateException("Core graph list returned a resource outside its requested authority");
            }
            if (resources.putIfAbsent(resource.id(), resource) != null) {
                throw new IllegalStateException("Core graph list returned a duplicate resource ID: " + resource.id());
            }
        }
        return resources.values().stream()
            .filter(resource -> normalizedSearch.isBlank() || resource.id().toLowerCase(Locale.ROOT).contains(normalizedSearch))
            .sorted(Comparator.comparing(ServerResourceLocator::id, String.CASE_INSENSITIVE_ORDER))
            .map(this::synchronizeCore)
            .filter(Objects::nonNull)
            .filter(state -> !state.deleted())
            .map(this::document)
            .toList();
    }

    @Override
    public String unsupportedOperationReason(ResourceOperation operation) {
        if (operation == null) {
            return "The durable resource authority requires a typed resource operation";
        }
        return switch (operation.kind()) {
            case RENAME -> "Resource adapters do not expose a durable presentation rename transaction";
            case MOVE -> "Resource adapters do not expose a durable presentation move transaction";
            case SUBSCRIBE -> "The protocol authority does not expose a session-bound durable resource subscription";
            default -> "The durable resource authority does not expose this resource operation";
        };
    }

    public synchronized Path databasePath() {
        return databasePath;
    }

    @Override
    public synchronized void close() {
        if (persistenceState == PersistenceState.CLOSED) {
            return;
        }
        projectMetadataCache = null;
        if (connection == null) {
            persistenceState = PersistenceState.CLOSED;
            publishDurability();
            return;
        }
        RuntimeException failure = null;
        try {
            flushPersistence();
        } catch (IOException | RuntimeException exception) {
            failure = exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            if (failure == null) {
                failure = new IllegalStateException("Failed To Close Durable Resource Mutation Authority", exception);
            } else {
                failure.addSuppressed(exception);
            }
        } finally {
            projectMetadataCache = null;
            coreReadAuthorityReady = false;
            persistenceState = PersistenceState.CLOSED;
            publishDurability();
        }
        if (failure != null) {
            throw failure;
        }
    }

    synchronized void flushPersistence() throws IOException {
        requireConnection();
        try {
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(FULL)");
            }
            StorageSafety.forceDirectory(databasePath.getParent());
        } catch (SQLException exception) {
            rollback();
            throw new IOException("Failed To Flush Resource Mutation Database", exception);
        }
    }

    synchronized void closeMutationAdmission() {
        mutationAdmissionClosed = true;
        if (persistenceState == PersistenceState.OPEN) {
            persistenceState = PersistenceState.ADMISSION_CLOSED;
            publishDurability();
        }
    }

    synchronized void quiescePersistence() throws IOException {
        if (persistenceState == PersistenceState.CLOSED) {
            throw new IOException("Resource mutation authority is closed");
        }
        if (persistenceState == PersistenceState.QUIESCED) {
            return;
        }
        flushPersistence();
        coreReadAuthorityReady = false;
        persistenceState = PersistenceState.QUIESCED;
        publishDurability();
    }

    synchronized void resumePersistence() throws IOException {
        if (persistenceState == PersistenceState.CLOSED) {
            throw new IOException("Resource mutation authority is closed");
        }
        if (catalogRecoveryRequired) {
            try {
                recoverCommittedAggregateCreates();
                if (recoveryBlocked) {
                    throw new IOException("Aggregate create publication recovery is blocked");
                }
                restoreCommittedCoreTransitionHighWater();
                Set<ServerResourceLocator> blockedTransitions = recoverCommittedCoreTransitions();
                recoverPending(blockedTransitions);
                recoverCommittedCoreTransitions();
                if (recoveryBlocked) {
                    throw new IOException("Resource mutation recovery is blocked: " + recoveryReason);
                }
                migrateCoreCatalogBindings();
                if (!catalogSettlementPending) {
                    migrateCompatibleCoreCatalogBindings();
                    evolveCoreCatalogBindings();
                }
                recoverCoreRevisionSkews();
                reconcileProjectMetadataState();
                recoverCommittedCoreTransitions();
                requireCompletedCoreCatalogRebindProof();
                catalogRecoveryRequired = false;
            } catch (SQLException | IOException | RuntimeException exception) {
                throw new IOException("Resource mutation catalog recovery after rebind failed", exception);
            }
        }
        healthCheckPersistence();
        requireCompletedCoreCatalogRebindProof();
        coreReadAuthorityReady = !catalogSettlementPending && establishCoreReadAuthority();
        if (!catalogSettlementPending && !coreReadAuthorityReady && coreAuthority.available()) {
            throw new IOException("Core resource authority is not ready");
        }
        persistenceState = mutationAdmissionClosed ? PersistenceState.ADMISSION_CLOSED : PersistenceState.OPEN;
        publishDurability();
    }

    synchronized void rebindPersistence(Path activeRoot) throws IOException {
        if (persistenceState != PersistenceState.QUIESCED) {
            throw new IOException("Resource mutation authority must be quiesced before rebind");
        }
        Path candidateScope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidateDatabase = candidateScope.resolve(relativeDatabasePath).toAbsolutePath().normalize();
        if (!candidateDatabase.startsWith(candidateScope) || candidateDatabase.equals(candidateScope)) {
            throw new IOException("Resource mutation rebind escaped active root");
        }
        Path candidateParent = candidateDatabase.getParent();
        if (candidateParent == null) {
            throw new IOException("Resource mutation rebind has no database root");
        }
        MigrationPaths.requireDirectory(candidateParent, "resource mutation database root");
        MigrationPaths.requireNoSymlinkTraversal(candidateScope, candidateDatabase);
        requireRegularDatabase(candidateDatabase);

        Connection previousConnection = connection;
        projectMetadataCache = null;
        Path previousDatabase = databasePath;
        Path previousScope = activeScopeRoot;
        boolean previousRecoveryBlocked = recoveryBlocked;
        String previousRecoveryReason = recoveryReason;
        boolean previousCatalogRecoveryRequired = catalogRecoveryRequired;
        boolean previousCoreReadAuthorityReady = coreReadAuthorityReady;
        Connection candidateConnection = null;
        try {
            candidateConnection = DriverManager.getConnection("jdbc:sqlite:" + candidateDatabase, new Properties());
            connection = candidateConnection;
            databasePath = candidateDatabase;
            activeScopeRoot = candidateScope;
            recoveryBlocked = false;
            publishDurability();
            recoveryReason = "";
            configure();
            migrate();
            catalogRecoveryRequired = true;
            coreReadAuthorityReady = false;
            healthCheckPersistence();
            previousConnection.close();
        } catch (Exception exception) {
            closeQuietly(candidateConnection);
            connection = previousConnection;
            databasePath = previousDatabase;
            activeScopeRoot = previousScope;
            recoveryBlocked = previousRecoveryBlocked;
            publishDurability();
            recoveryReason = previousRecoveryReason;
            catalogRecoveryRequired = previousCatalogRecoveryRequired;
            coreReadAuthorityReady = previousCoreReadAuthorityReady;
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            String reason = exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
            throw new IOException("Failed To Rebind Resource Mutation Database: " + reason, exception);
        }
    }

    synchronized void healthCheckPersistence() throws IOException {
        requireConnection();
        Path parent = databasePath.getParent();
        if (parent == null) {
            throw new IOException("Resource mutation database has no parent");
        }
        MigrationPaths.requireDirectory(parent, "resource mutation database root");
        MigrationPaths.requireNoSymlinkTraversal(activeScopeRoot, databasePath);
        requireRegularDatabase(databasePath);
        if (recoveryBlocked) {
            throw new IOException("Resource mutation recovery is blocked: " + recoveryReason);
        }
        try {
            if (!connection.isValid(2)) {
                throw new IOException("Resource mutation database connection is invalid");
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM resource_mutation_authority_meta WHERE key = 'schema'")) {
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !SCHEMA.equals(result.getString(1))) {
                        throw new IOException("Resource mutation database schema is invalid");
                    }
                }
            }
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new IOException("Resource mutation database integrity check failed");
                }
            }
            if (persistenceState != PersistenceState.QUIESCED) {
                requireCompletedCoreCatalogRebindProof();
            }
        } catch (SQLException exception) {
            throw new IOException("Failed To Check Resource Mutation Database", exception);
        }
    }

    private void requireCompletedCoreCatalogRebindProof() throws IOException {
        if (!catalogRebindReportsAvailable(migrationReports)) {
            return;
        }
        CoreCatalogBindingMigration migration = catalogMigration;
        try {
            if (coreAuthority.acceptsCatalogRebind(migration) && !completedCoreCatalogRebind(migration)) {
                throw new IOException("Core catalog rebind is not complete");
            }
        } catch (SQLException exception) {
            throw new IOException("Failed To Verify Core Catalog Rebind Proof", exception);
        }
    }

    synchronized boolean isQuiesced() {
        return persistenceState == PersistenceState.QUIESCED;
    }

    public synchronized Path rebindScope() {
        return scopeRoot;
    }

    private void requireConnection() throws IOException {
        if (connection == null || persistenceState == PersistenceState.CLOSED) {
            throw new IOException("Resource mutation authority is closed");
        }
        try {
            if (connection.isClosed()) {
                throw new IOException("Resource mutation database connection is closed");
            }
        } catch (SQLException exception) {
            throw new IOException("Failed To Inspect Resource Mutation Database Connection", exception);
        }
    }

    private void requireReadable() {
        if (catalogSettlementPending) {
            throw new IllegalStateException("Resource catalog startup is not settled");
        }
        if (recoveryBlocked) {
            throw new IllegalStateException("Resource mutation recovery is blocked: " + recoveryReason);
        }
        if (persistenceState != PersistenceState.OPEN) {
            throw new IllegalStateException(persistenceState == PersistenceState.CLOSED
                ? "Resource mutation authority is closed" : "Resource mutation authority is quiesced");
        }
        if (connection == null) {
            throw new IllegalStateException("Resource mutation authority is unavailable");
        }
        try {
            if (connection.isClosed() || !connection.isValid(1)) {
                throw new IllegalStateException("Resource mutation database connection is unavailable");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Check Resource Mutation Database Connection", exception);
        }
    }

    private void requireCoreReadable() {
        if (!coreReadAuthorityReady || !coreAuthority.available()) {
            throw new IllegalStateException("Core resource authority is not ready");
        }
    }

    private boolean establishCoreReadAuthority() throws IOException {
        if (!coreAuthority.available()) {
            return false;
        }
        CoreCatalogBindingMigration migration = catalogMigration;
        if (!coreAuthority.acceptsCatalogRebind(migration)) {
            return true;
        }
        if (catalogRebindReportsAvailable(migrationReports)) {
            requireCompletedCoreCatalogRebindProof();
            return true;
        }
        try {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM core_catalog_rebind_run WHERE migration_id = ?")) {
                statement.setString(1, CoreCatalogBindingMigration.ID);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return false;
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IOException("Failed To Verify Core Read Authority", exception);
        }
        for (ServerResourceLocator resource : migration.resources(serverId)) {
            CoreGraphResourceAuthority.CoreGraphResourceState state = coreAuthority.state(resource).orElse(null);
            if (state != null && !state.deleted() && migration.eligible(resource, state.envelope())) {
                return false;
            }
        }
        return true;
    }

    public synchronized void settleCatalogBinding(CatalogBinding expected) {
        Objects.requireNonNull(expected, "Final catalog binding is required");
        try {
            requireConnection();
            if (recoveryBlocked || persistenceState != PersistenceState.OPEN
                || !expected.equals(coreAuthority.activeCatalogBinding().orElse(null))) {
                throw new IllegalStateException("Final catalog authority is unavailable or changed");
            }
            coreReadAuthorityReady = false;
            catalogSettlementPending = true;
            publishDurability();
            recoverCommittedAggregateCreates();
            if (recoveryBlocked) {
                throw new IllegalStateException("Final aggregate create publication recovery is blocked");
            }
            Set<ServerResourceLocator> blockedTransitions = recoverCommittedCoreTransitions();
            recoverPending(blockedTransitions);
            recoverCommittedCoreTransitions();
            if (recoveryBlocked) {
                throw new IllegalStateException("Final catalog recovery is blocked");
            }
            migrateCompatibleCoreCatalogBindings();
            evolveCoreCatalogBindings();
            Set<ServerResourceLocator> storedResources = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT resource FROM resource_mutation_state WHERE deleted = 0 AND core_payload_kind IS NOT NULL");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ServerResourceLocator resource = locator(result.getString("resource"));
                    if (isCoreResource(resource)) {
                        storedResources.add(resource);
                    }
                }
            }
            for (String type : CORE_RESOURCE_TYPES.stream().sorted().toList()) {
                for (CoreGraphResourceAuthority.CoreGraphResourceState listed : coreAuthority.list(type)) {
                    if (listed.deleted()) {
                        continue;
                    }
                    CoreState current = synchronizeCore(listed.resource());
                    MutationRow receipt = current == null ? null : mutation(current.mutationId());
                    if (current == null || !sameCoreState(current, coreState(listed.envelope()))) {
                        throw new IllegalStateException("A live Core resource does not match durable mutation state: "
                            + listed.resource().canonicalText());
                    }
                    CatalogBinding bound = CoreCatalogCompatibilityRebind.graph(current.decoded()).catalogBinding();
                    if (!expected.equals(bound)) {
                        throw new IllegalStateException("A live Core resource is not bound to the final catalog: "
                            + listed.resource().canonicalText() + " expected " + expected.canonicalText()
                            + " bound " + bound.canonicalText());
                    }
                    if (!provenCoreCatalogSource(current, receipt)) {
                        throw new IllegalStateException("A live Core resource does not prove the final catalog binding: "
                            + listed.resource().canonicalText());
                    }
                    coreAuthority.validateSave(listed.resource(), current.decoded());
                    storedResources.remove(listed.resource());
                }
            }
            if (!storedResources.isEmpty()) {
                throw new IllegalStateException("Stored Core resource heads are missing from final catalog inventory");
            }
            if (recoveryBlocked || !expected.equals(coreAuthority.activeCatalogBinding().orElse(null))
                || !establishCoreReadAuthority()) {
                throw new IllegalStateException("Final Core catalog authority did not settle");
            }
            coreReadAuthorityReady = true;
            catalogSettlementPending = false;
            publishDurability();
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("Failed To Settle Final Core Catalog Authority", failure);
        }
    }

    public enum CatalogStartup {
        IMMEDIATE,
        DEFERRED
    }

    private static Path requireDatabasePath(Path databasePath) {
        Path normalized = MigrationPaths.requirePath(databasePath, "databasePath");
        if (normalized.getFileName() == null || normalized.getParent() == null) {
            throw new IllegalArgumentException("Resource mutation database path must have a parent and file name");
        }
        return normalized;
    }

    private static void requireRegularDatabase(Path databasePath) throws IOException {
        if (Files.exists(databasePath, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(databasePath) || !Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Resource mutation database must be a regular non-symbolic-link file: " + databasePath);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private void migrate() throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS resource_mutation_authority_meta(
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS resource_mutation_state(
                        resource TEXT PRIMARY KEY,
                        revision INTEGER NOT NULL,
                        mutation_id TEXT NOT NULL,
                        payload_hash TEXT NOT NULL,
                        deleted INTEGER NOT NULL,
                        payload TEXT,
                        activation_state TEXT NOT NULL DEFAULT 'active',
                        asset_hash TEXT,
                        core_payload_hash TEXT,
                        core_payload_kind TEXT,
                        updated_at INTEGER NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS resource_mutation_receipt(
                        mutation_id TEXT PRIMARY KEY,
                        actor_id TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        requested_resource TEXT NOT NULL,
                        response_resource TEXT NOT NULL,
                        source_resource TEXT,
                        target_resource TEXT,
                        expected_revision INTEGER NOT NULL,
                        precondition_hash TEXT NOT NULL,
                        status TEXT NOT NULL,
                        result_revision INTEGER NOT NULL,
                        result_mutation_id TEXT NOT NULL,
                        result_hash TEXT NOT NULL,
                        result_deleted INTEGER NOT NULL,
                        result_payload TEXT,
                        target_activation_state TEXT,
                        result_activation_state TEXT,
                        precondition_asset_hash TEXT,
                        precondition_core_payload_hash TEXT,
                        precondition_core_payload_kind TEXT,
                        result_asset_hash TEXT,
                        result_core_payload_hash TEXT,
                        result_core_payload_kind TEXT,
                        transition_envelope TEXT,
                        transition_hash TEXT,
                        transition_published INTEGER NOT NULL DEFAULT 0,
                        sequence INTEGER NOT NULL,
                        error_code TEXT NOT NULL,
                        error_message TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS resource_create_aggregate_receipt(
                        mutation_id TEXT PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        resource_path TEXT NOT NULL,
                        sort_order INTEGER NOT NULL,
                        metadata_resource TEXT NOT NULL,
                        metadata_revision INTEGER,
                        metadata_mutation_id TEXT,
                        metadata_hash TEXT,
                        metadata_payload TEXT,
                        published INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(mutation_id) REFERENCES resource_mutation_receipt(mutation_id)
                    )
                    """);
                String previousSchema = schemaValue();
                if (previousSchema != null && !SCHEMA.equals(previousSchema) && !PRE_EVOLUTION_SCHEMA.equals(previousSchema)
                    && !PREVIOUS_SCHEMA.equals(previousSchema)
                    && !LEGACY_SCHEMA.equals(previousSchema) && !OLDEST_SCHEMA.equals(previousSchema)
                    && !ANCIENT_SCHEMA.equals(previousSchema) && !EARLIEST_SCHEMA.equals(previousSchema)
                    && !FIRST_SCHEMA.equals(previousSchema)) {
                    throw new SQLException("Unsupported resource mutation database schema: " + previousSchema);
                }
                ensureReceiptActorColumn(statement);
                ensureStateActivationColumn(statement);
                ensureReceiptActivationColumns(statement);
                ensureStateCoreColumns(statement);
                ensureReceiptCoreColumns(statement);
                ensureTransitionPublicationColumn(statement);
                ensureAggregatePublicationColumn(statement);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS core_catalog_rebind_run(
                        migration_id TEXT PRIMARY KEY,
                        manifest_hash TEXT NOT NULL,
                        source_binding TEXT NOT NULL,
                        target_binding TEXT NOT NULL,
                        plan_hash TEXT NOT NULL,
                        status TEXT NOT NULL,
                        report_hash TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS core_catalog_rebind_item(
                        migration_id TEXT NOT NULL,
                        resource TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        source_revision INTEGER NOT NULL,
                        source_mutation_id TEXT NOT NULL,
                        source_asset_hash TEXT NOT NULL,
                        source_core_hash TEXT NOT NULL,
                        source_payload_kind TEXT NOT NULL,
                        source_envelope TEXT NOT NULL,
                        mutation_id TEXT NOT NULL UNIQUE,
                        status TEXT NOT NULL,
                        diagnostic TEXT NOT NULL,
                        result_revision INTEGER,
                        result_asset_hash TEXT,
                        PRIMARY KEY(migration_id, resource),
                        FOREIGN KEY(migration_id) REFERENCES core_catalog_rebind_run(migration_id)
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS core_catalog_evolution_receipt(
                        mutation_id TEXT PRIMARY KEY,
                        registration_hash TEXT NOT NULL,
                        source_envelope TEXT NOT NULL,
                        target_binding TEXT NOT NULL,
                        result_asset_hash TEXT NOT NULL,
                        proof_hash TEXT NOT NULL,
                        FOREIGN KEY(mutation_id) REFERENCES resource_mutation_receipt(mutation_id)
                    )
                    """);
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS resource_mutation_transition_outbox
                    ON resource_mutation_receipt(transition_published, status, sequence)
                    """);
                if (!SCHEMA.equals(previousSchema)) {
                    migrateActivationStateValues();
                }
                String actorMigration = SCHEMA.equals(previousSchema) || PRE_EVOLUTION_SCHEMA.equals(previousSchema)
                    || PREVIOUS_SCHEMA.equals(previousSchema)
                    || LEGACY_SCHEMA.equals(previousSchema) || OLDEST_SCHEMA.equals(previousSchema)
                    ? "UPDATE resource_mutation_receipt SET actor_id = ? WHERE actor_id IS NULL OR TRIM(actor_id) = ''"
                    : "UPDATE resource_mutation_receipt SET actor_id = ?";
                try (PreparedStatement update = connection.prepareStatement(actorMigration)) {
                    update.setString(1, LEGACY_ACTOR);
                    update.executeUpdate();
                }
            }
            migrateLegacyCustomContentPresentationPaths();
            try (PreparedStatement schema = connection.prepareStatement(
                "INSERT OR REPLACE INTO resource_mutation_authority_meta(key, value) VALUES('schema', ?)")) {
                schema.setString(1, SCHEMA);
                schema.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            rollback();
            throw exception;
        } finally {
            if (autoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    private void ensureReceiptActorColumn(Statement statement) throws SQLException {
        boolean present = false;
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(resource_mutation_receipt)")) {
            while (columns.next()) {
                if ("actor_id".equalsIgnoreCase(columns.getString("name"))) {
                    present = true;
                    break;
                }
            }
        }
        if (!present) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN actor_id TEXT NOT NULL DEFAULT '" + LEGACY_ACTOR + "'");
        }
    }

    private void ensureStateActivationColumn(Statement statement) throws SQLException {
        if (!hasColumn(statement, "resource_mutation_state", "activation_state")) {
            statement.execute("ALTER TABLE resource_mutation_state ADD COLUMN activation_state TEXT NOT NULL DEFAULT 'active'");
        }
    }

    private void ensureReceiptActivationColumns(Statement statement) throws SQLException {
        if (!hasColumn(statement, "resource_mutation_receipt", "target_activation_state")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN target_activation_state TEXT");
        }
        if (!hasColumn(statement, "resource_mutation_receipt", "result_activation_state")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN result_activation_state TEXT");
        }
    }

    private void ensureStateCoreColumns(Statement statement) throws SQLException {
        if (!hasColumn(statement, "resource_mutation_state", "asset_hash")) {
            statement.execute("ALTER TABLE resource_mutation_state ADD COLUMN asset_hash TEXT");
        }
        if (!hasColumn(statement, "resource_mutation_state", "core_payload_hash")) {
            statement.execute("ALTER TABLE resource_mutation_state ADD COLUMN core_payload_hash TEXT");
        }
        if (!hasColumn(statement, "resource_mutation_state", "core_payload_kind")) {
            statement.execute("ALTER TABLE resource_mutation_state ADD COLUMN core_payload_kind TEXT");
        }
    }

    private void ensureReceiptCoreColumns(Statement statement) throws SQLException {
        if (!hasColumn(statement, "resource_mutation_receipt", "precondition_asset_hash")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN precondition_asset_hash TEXT");
        }
        if (!hasColumn(statement, "resource_mutation_receipt", "precondition_core_payload_hash")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN precondition_core_payload_hash TEXT");
        }
        if (!hasColumn(statement, "resource_mutation_receipt", "precondition_core_payload_kind")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN precondition_core_payload_kind TEXT");
        }
        if (!hasColumn(statement, "resource_mutation_receipt", "result_asset_hash")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN result_asset_hash TEXT");
        }
        if (!hasColumn(statement, "resource_mutation_receipt", "result_core_payload_hash")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN result_core_payload_hash TEXT");
        }
        if (!hasColumn(statement, "resource_mutation_receipt", "result_core_payload_kind")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN result_core_payload_kind TEXT");
        }
    }

    private void ensureTransitionPublicationColumn(Statement statement) throws SQLException {
        boolean migrated = false;
        if (!hasColumn(statement, "resource_mutation_receipt", "transition_envelope")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN transition_envelope TEXT");
            migrated = true;
        }
        if (!hasColumn(statement, "resource_mutation_receipt", "transition_hash")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN transition_hash TEXT");
            migrated = true;
        }
        if (!hasColumn(statement, "resource_mutation_receipt", "transition_published")) {
            statement.execute("ALTER TABLE resource_mutation_receipt ADD COLUMN transition_published INTEGER NOT NULL DEFAULT 0");
            migrated = true;
        }
        if (migrated) {
            statement.execute("UPDATE resource_mutation_receipt SET transition_published = 1 WHERE status = 'APPLIED'");
        }
    }

    private void ensureAggregatePublicationColumn(Statement statement) throws SQLException {
        if (!hasColumn(statement, "resource_create_aggregate_receipt", "published")) {
            statement.execute("ALTER TABLE resource_create_aggregate_receipt ADD COLUMN published INTEGER NOT NULL DEFAULT 0");
        }
    }

    private boolean hasColumn(Statement statement, String table, String column) throws SQLException {
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void migrateActivationStateValues() throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(
            "SELECT resource, deleted, payload, activation_state FROM resource_mutation_state WHERE deleted = 0 AND payload IS NOT NULL");
             ResultSet result = read.executeQuery();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE resource_mutation_state SET activation_state = ? WHERE resource = ?")) {
            while (result.next()) {
                String migratedState = activationState(result.getString("payload"), ResourceActivationState.ACTIVE).wireName();
                if (migratedState.equalsIgnoreCase(result.getString("activation_state"))) {
                    continue;
                }
                update.setString(1, migratedState);
                update.setString(2, result.getString("resource"));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private void migrateLegacyCustomContentPresentationPaths() throws SQLException {
        List<MutationRow> pending = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT r.* FROM resource_mutation_receipt r
            JOIN resource_create_aggregate_receipt a ON a.mutation_id = r.mutation_id
            WHERE r.status = 'PENDING' AND r.operation = 'CREATE' AND a.metadata_revision IS NULL
            ORDER BY r.created_at, r.mutation_id
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                pending.add(readMutation(result));
            }
        }
        try (PreparedStatement presentationUpdate = connection.prepareStatement("""
                 UPDATE resource_create_aggregate_receipt SET resource_path = ?
                 WHERE mutation_id = ? AND resource_path = ? AND metadata_revision IS NULL
                 """);
             PreparedStatement fingerprintUpdate = connection.prepareStatement("""
                 UPDATE resource_mutation_receipt SET fingerprint = ?
                 WHERE mutation_id = ? AND status = 'PENDING' AND fingerprint = ?
                 """)) {
            for (MutationRow row : pending) {
                Command command = command(row);
                ResourcePresentationIntent migrated = canonicalCreatePresentation(command.responseResource(),
                    command.presentation());
                if (migrated == null || migrated.equals(command.presentation())) {
                    continue;
                }
                String originalFingerprint = fingerprint(command, row.actorId());
                Command migratedCommand = new Command(command.operationName(), command.resource(), command.source(),
                    command.responseResource(), command.mutationId(), command.expectedRevision(), command.payload(),
                    command.payloadHash(), command.activationState(), migrated);
                String migratedFingerprint = fingerprint(migratedCommand, row.actorId());
                if (!originalFingerprint.equals(row.fingerprint()) && !migratedFingerprint.equals(row.fingerprint())) {
                    throw new SQLException("Legacy custom content presentation receipt fingerprint is invalid: "
                        + row.mutationId());
                }
                presentationUpdate.setString(1, migrated.path());
                presentationUpdate.setString(2, row.mutationId().toString());
                presentationUpdate.setString(3, command.presentation().path());
                if (presentationUpdate.executeUpdate() != 1) {
                    throw new SQLException("Legacy custom content presentation receipt was not migrated: "
                        + row.mutationId());
                }
                fingerprintUpdate.setString(1, migratedFingerprint);
                fingerprintUpdate.setString(2, row.mutationId().toString());
                fingerprintUpdate.setString(3, row.fingerprint());
                if (fingerprintUpdate.executeUpdate() != 1) {
                    throw new SQLException("Legacy custom content mutation fingerprint was not migrated: "
                        + row.mutationId());
                }
            }
        }
    }

    private String schemaValue() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT value FROM resource_mutation_authority_meta WHERE key = 'schema'")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private void recoverPending(Set<ServerResourceLocator> blockedTransitions) throws SQLException {
        if (recoveryBlocked) {
            return;
        }
        long started = TemporaryLifecycleDiagnostics.start();
        List<MutationRow> pending = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM resource_mutation_receipt WHERE status = 'PENDING' ORDER BY rowid");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                pending.add(readMutation(result));
            }
        }
        int recovered = 0;
        int skipped = 0;
        for (MutationRow row : pending) {
            if (recoveryBlocked) {
                return;
            }
            FlowResourceMutationLease lease = null;
            try {
                Command command = command(row);
                if (blockedTransitions.contains(command.responseResource())) {
                    skipped++;
                    continue;
                }
                List<FlowResourceKey> keys = mutationKeys(command);
                if (keys.isEmpty() || hasDuplicateKeys(keys)) {
                    throw new IllegalStateException("Pending resource mutation does not have a unique typed key set");
                }
                lease = registry.mutationAdmission().tryAcquire(keys, row.mutationId().toString(),
                    FlowResourceMutationAdmission.AdmissionKind.NEW).orElseThrow(
                        () -> new IllegalStateException("Pending resource mutation is already admitted"));
                if (isCoreResource(command.responseResource()) && command.presentation() == null) {
                    recoverCore(row, lease);
                } else {
                    recover(row, lease);
                }
                recovered++;
            } catch (RuntimeException exception) {
                if (rejectProvenUnappliedCreate(row, exception)) {
                    recovered++;
                    continue;
                }
                blockRecovery("Pending resource mutation " + row.mutationId() + " cannot be reconciled: " + safeMessage(exception));
                TemporaryLifecycleDiagnostics.event("pending_receipt_recovery", started,
                    TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, null, null, null,
                        null, null, authorityEpoch.current(), null), "outcome", "blocked", "pendingCount", pending.size(),
                        "recoveredCount", recovered, "skippedCount", skipped, "failure", exception.getClass().getSimpleName()));
                return;
            } finally {
                if (lease != null) {
                    lease.close();
                }
            }
        }
        TemporaryLifecycleDiagnostics.event("pending_receipt_recovery", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, null, null, null,
                null, null, authorityEpoch.current(), null), "outcome", "complete", "pendingCount", pending.size(),
                "recoveredCount", recovered, "skippedCount", skipped));
    }

    private boolean rejectProvenUnappliedCreate(MutationRow row, RuntimeException failure) {
        try {
            Command command = command(row);
            if (!"CREATE".equals(command.operationName()) || row.expectedRevision() != 0L
                || row.preconditionHash() != null && !row.preconditionHash().isBlank()
                || state(command.responseResource()) != null) {
                return false;
            }
            FlowResourceAdapter<Object> adapter = adapter(command.responseResource());
            if (adapter == null || readStamp(command.responseResource(), adapter) != null) {
                return false;
            }
            if (command.presentation() != null) {
                AggregateReceipt aggregate = aggregateReceipt(row.mutationId());
                if (aggregate == null || aggregate.metadata() != null) {
                    return false;
                }
            } else if (adapter.get(command.responseResource().id()) != null) {
                return false;
            }
            finishRejected(row, "RESOURCE_PAYLOAD_INVALID", safeMessage(failure));
            TemporaryLifecycleDiagnostics.event("pending_receipt_recovery", 0L,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "rejected",
                    "failure", failure.getClass().getSimpleName()));
            return true;
        } catch (RuntimeException proofFailure) {
            return false;
        }
    }

    private void migrateCoreCatalogBindings() throws SQLException, IOException {
        if (!coreAuthority.available() || recoveryBlocked || !catalogRebindReportsAvailable(migrationReports)) {
            return;
        }
        CoreCatalogBindingMigration migration = catalogMigration;
        if (!coreAuthority.acceptsCatalogRebind(migration) || completedCoreCatalogRebind(migration)) {
            return;
        }
        List<CoreCatalogRebindItem> items = loadCoreCatalogRebindItems();
        if (items.isEmpty()) {
            items = planCoreCatalogRebind(migration);
            persistCoreCatalogRebindPlan(migration, items);
        } else {
            requireCoreCatalogRebindRun(migration, items);
        }
        for (CoreCatalogRebindItem item : items) {
            if (!"PLANNED".equals(item.status())) {
                continue;
            }
            MutationRow receipt = mutation(item.mutationId());
            if (receipt != null) {
                if (receipt.status() != Status.APPLIED || !CoreCatalogBindingMigration.ACTOR.equals(receipt.actorId())) {
                    blockCoreCatalogRebind("Frozen incident receipt is not the expected applied migration receipt");
                    throw new IllegalStateException("Core catalog rebind receipt is not durably applied: " + item.resource());
                }
                markCoreCatalogRebindApplied(item, receipt);
                continue;
            }
            CoreGraphStorageBoundary.Decoded source = coreBoundary.decodeText(item.sourceEnvelope(), item.resource());
            CoreState before = synchronizeCore(item.resource());
            requireCoreCatalogRebindSource(item, source, before, migration);
            CoreGraphStorageBoundary.Decoded candidate = migration.project(source, item.mutationId());
            coreAuthority.validateSave(item.resource(), candidate);
            Map<String, Object> payload = corePayload(candidate);
            ContentHash payloadHash = payloadCodec.canonicalize(payload).checksum();
            Command command = new Command("SAVE", item.resource(), null, item.resource(), item.mutationId(),
                source.envelope().assetRevision(), payload, payloadHash,
                source.envelope().assetActivationState(), null);
            CoreOutcome outcome = corePending(command, before, coreState(candidate));
            String fingerprint = coreCatalogRebindFingerprint(migration, item);
            MutationRow pending = insertCorePending(command, CoreCatalogBindingMigration.ACTOR, fingerprint, outcome);
            CoreGraphResourceAuthority.CoreCatalogRebindResult rebound = coreAuthority.rebindCatalog(item.resource(),
                new CoreGraphResourceAuthority.CoreCatalogRebindSource(source), item.mutationId(), migration)
                .orElseThrow(() -> new IllegalStateException("Core catalog rebind source changed: " + item.resource()));
            CoreState after = coreState(rebound.rebound());
            verifyCorePostApply(command, outcome, after);
            MutationRow committed = commitCoreApplied(pending, after);
            markCoreCatalogRebindApplied(item, committed);
        }
        completeCoreCatalogRebind(migration, loadCoreCatalogRebindItems());
    }

    static boolean catalogRebindReportsAvailable(MigrationReportsPersistenceParticipant reports) {
        return reports != null && reports.isAdmitted();
    }

    private void migrateCompatibleCoreCatalogBindings() throws SQLException {
        if (!coreAuthority.available() || recoveryBlocked) {
            return;
        }
        CatalogBinding target = coreAuthority.activeCatalogBinding().orElse(null);
        if (target == null) {
            return;
        }
        long started = TemporaryLifecycleDiagnostics.start();
        int inspected = 0;
        int rebound = 0;
        int rejected = 0;
        for (CoreGraphResourceAuthority.CoreGraphResourceState listed : compatibleCatalogOrder()) {
            if (recoveryBlocked) {
                break;
            }
            if (listed.deleted()) {
                continue;
            }
            CoreGraphStorageBoundary.Decoded source = listed.envelope();
            if (target.equals(CoreCatalogCompatibilityRebind.graph(source).catalogBinding())) {
                continue;
            }
            inspected++;
            if (!CoreCatalogCompatibilityRebind.eligible(source, target)) {
                rejected++;
                continue;
            }
            ServerResourceLocator resource = listed.resource();
            CoreState before = synchronizeCore(resource);
            CoreState listedState = coreState(source);
            MutationRow sourceReceipt = before == null ? null : mutation(before.mutationId());
            if (before == null || !sameCoreState(before, listedState)
                || !compatibleCoreCatalogSource(before, sourceReceipt)) {
                rejected++;
                continue;
            }
            Map<ServerResourceLocator, Long> dependencies;
            CoreGraphStorageBoundary.Decoded candidate;
            UUID mutationId;
            try {
                dependencies = compatibleCatalogDependencies(source, target, 0L, true, new LinkedHashSet<>());
                mutationId = CoreCatalogCompatibilityRebind.mutationId(resource, source, target, dependencies);
                candidate = CoreCatalogCompatibilityRebind.project(source, target, mutationId, dependencies);
                coreAuthority.validateSave(resource, candidate);
            } catch (RuntimeException incompatible) {
                CatalogBinding currentTarget = coreAuthority.activeCatalogBinding().orElse(null);
                if (!target.equals(currentTarget)) {
                    throw new IllegalStateException("Active Core catalog binding changed during compatibility rebind", incompatible);
                }
                rejected++;
                continue;
            }
            if (mutation(mutationId) != null) {
                throw new IllegalStateException("Compatible Core catalog rebind mutation ID is already registered: "
                    + resource.canonicalText());
            }
            Map<String, Object> payload = corePayload(candidate);
            ContentHash payloadHash = payloadCodec.canonicalize(payload).checksum();
            Command command = new Command("SAVE", resource, null, resource, mutationId,
                source.envelope().assetRevision(), payload, payloadHash,
                source.envelope().assetActivationState(), null);
            CoreOutcome outcome = corePending(command, before, coreState(candidate));
            MutationRow pending = insertCatalogPending(command, CoreCatalogCompatibilityRebind.PROOF_ACTOR,
                compatibleCoreCatalogFingerprint(source, target, mutationId), CoreCatalogCompatibilityRebind.REGISTRATION,
                source, target, candidate, outcome);
            CoreGraphStorageBoundary.Decoded saved = saveCatalogProjection(pending, candidate);
            CoreState after = coreState(saved);
            verifyCorePostApply(command, outcome, after);
            commitCoreApplied(pending, after);
            rebound++;
        }
        if (!target.equals(coreAuthority.activeCatalogBinding().orElse(null))) {
            throw new IllegalStateException("Active Core catalog binding changed during compatibility rebind");
        }
        TemporaryLifecycleDiagnostics.event("core_catalog_compatibility_rebind", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, null, null, null,
                null, null, authorityEpoch.current(), null), "outcome", "complete", "targetBinding",
                target.canonicalText(), "inspectedCount", inspected, "reboundCount", rebound, "rejectedCount",
                rejected));
    }

    private CoreGraphStorageBoundary.Decoded saveCatalogProjection(MutationRow row, CoreGraphStorageBoundary.Decoded candidate) {
        row = Objects.requireNonNull(mutation(row.mutationId()), "Catalog Projection Pending Receipt Is Required");
        if (row.status() != Status.PENDING || !CoreCatalogCompatibilityRebind.PROOF_ACTOR.equals(row.actorId())) {
            throw new IllegalStateException("Catalog Projection Requires Its Pending Proof Receipt");
        }
        EvolutionReceipt evidence = Objects.requireNonNull(evolutionReceipt(row.mutationId()), "Catalog Projection Evidence Is Required");
        CoreGraphStorageBoundary.Decoded verified = verifyCompatibleCatalogProjection(row, evidence, true, new LinkedHashSet<>());
        String canonical = new String(coreBoundary.encode(verified), StandardCharsets.UTF_8);
        if (!canonical.equals(new String(coreBoundary.encode(candidate), StandardCharsets.UTF_8))) {
            throw new IllegalStateException("Catalog Projection Does Not Match Its Durable Proof");
        }
        Command command = command(row);
        verifyCoreExternalPrecondition(command, validateStoredCorePrecondition(command, row));
        CoreGraphStorageBoundary.Decoded source = coreBoundary.decodeText(evidence.sourceEnvelope(), command.responseResource());
        try (CatalogProjection projection = new CatalogProjection(this, source, verified)) {
            return coreAuthority.saveCatalogProjection(command.responseResource(), candidate, row.mutationId(),
                row.expectedRevision(), candidate.envelope().assetHash(), projection);
        }
    }

    public static final class CatalogProjection implements CoreGraphResourceAuthority.CatalogProjection, AutoCloseable {
        private final SqliteProtocolResourceMutationAuthority owner;
        private final ServerResourceLocator resource;
        private final UUID mutationId;
        private final long expectedRevision;
        private final ContentHash checksum;
        private final CatalogBinding target;
        private final long epoch;
        private final String source;
        private final String candidate;
        private volatile boolean open = true;

        private CatalogProjection(SqliteProtocolResourceMutationAuthority owner,
                CoreGraphStorageBoundary.Decoded source, CoreGraphStorageBoundary.Decoded candidate) {
            this.owner = owner;
            this.resource = CoreCatalogCompatibilityRebind.graph(candidate).resource();
            this.mutationId = UUID.fromString(candidate.envelope().assetMutationId());
            this.expectedRevision = source.envelope().assetRevision();
            this.checksum = candidate.envelope().assetHash();
            this.target = CoreCatalogCompatibilityRebind.graph(candidate).catalogBinding();
            this.epoch = owner.authorityEpoch.current();
            this.source = new String(owner.coreBoundary.encode(source), StandardCharsets.UTF_8);
            this.candidate = new String(owner.coreBoundary.encode(candidate), StandardCharsets.UTF_8);
        }

        @Override
        public void requireCurrent(CoreGraphResourceAuthority authority, ServerResourceLocator resource,
                CoreGraphStorageBoundary.Decoded candidate, UUID mutationId, long expectedRevision, ContentHash checksum) {
            if (!open || authority != owner.coreAuthority || owner.connection == null || owner.recoveryBlocked
                || owner.persistenceState != PersistenceState.OPEN || owner.mutationAdmissionClosed
                || epoch != owner.authorityEpoch.current() || !this.resource.equals(resource)
                || !this.mutationId.equals(mutationId) || this.expectedRevision != expectedRevision
                || !this.checksum.equals(checksum) || candidate == null
                || !this.candidate.equals(new String(owner.coreBoundary.encode(candidate), StandardCharsets.UTF_8))
                || !target.equals(authority.activeCatalogBinding().orElse(null))) {
                throw new IllegalStateException("Catalog Projection Admission Is Stale Or Does Not Match The Save");
            }
            CoreGraphStorageBoundary.Decoded current = authority.load(resource)
                .orElseThrow(() -> new IllegalStateException("Catalog Projection Source Is No Longer Available"));
            if (!source.equals(new String(owner.coreBoundary.encode(current), StandardCharsets.UTF_8))) {
                throw new IllegalStateException("Catalog Projection Source Changed Before The Save");
            }
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private List<CoreGraphResourceAuthority.CoreGraphResourceState> compatibleCatalogOrder() {
        Map<ServerResourceLocator, CoreGraphResourceAuthority.CoreGraphResourceState> sources = new LinkedHashMap<>();
        CORE_RESOURCE_TYPES.stream().sorted().forEach(type -> coreAuthority.list(type).stream()
            .filter(state -> !state.deleted()).sorted(Comparator.comparing(CoreGraphResourceAuthority.CoreGraphResourceState::resource))
            .forEach(state -> sources.put(state.resource(), state)));
        List<CoreGraphResourceAuthority.CoreGraphResourceState> ordered = new ArrayList<>();
        Set<ServerResourceLocator> complete = new LinkedHashSet<>();
        Set<ServerResourceLocator> invalid = new LinkedHashSet<>();
        for (ServerResourceLocator resource : sources.keySet()) {
            orderCompatibleCatalog(resource, sources, new LinkedHashSet<>(), complete, invalid, ordered);
        }
        return List.copyOf(ordered);
    }

    private void orderCompatibleCatalog(ServerResourceLocator resource,
            Map<ServerResourceLocator, CoreGraphResourceAuthority.CoreGraphResourceState> sources,
            Set<ServerResourceLocator> path, Set<ServerResourceLocator> complete, Set<ServerResourceLocator> invalid,
            List<CoreGraphResourceAuthority.CoreGraphResourceState> ordered) {
        if (complete.contains(resource)) {
            return;
        }
        if (path.size() >= 64 || !path.add(resource)) {
            invalid.addAll(path);
            invalid.add(resource);
            return;
        }
        CoreGraphResourceAuthority.CoreGraphResourceState state = sources.get(resource);
        if (state == null) {
            invalid.add(resource);
        } else {
            for (FunctionBinding binding : CoreCatalogCompatibilityRebind.graph(state.envelope()).functions()) {
                orderCompatibleCatalog(binding.function(), sources, path, complete, invalid, ordered);
                if (invalid.contains(binding.function())) {
                    invalid.add(resource);
                }
            }
        }
        path.remove(resource);
        complete.add(resource);
        if (state != null && !invalid.contains(resource)) {
            ordered.add(state);
        }
    }

    private void evolveCoreCatalogBindings() throws SQLException {
        if (!coreAuthority.available() || recoveryBlocked) {
            return;
        }
        CoreCatalogEvolution.Proof proof = coreAuthority.activeCatalogEvolution().orElse(null);
        if (proof == null) {
            return;
        }
        for (String type : CORE_RESOURCE_TYPES.stream().sorted().toList()) {
            for (CoreGraphResourceAuthority.CoreGraphResourceState listed : coreAuthority.list(type)) {
                if (listed.deleted() || proof.target().equals(
                    CoreCatalogCompatibilityRebind.graph(listed.envelope()).catalogBinding())) {
                    continue;
                }
                CoreGraphStorageBoundary.Decoded source = listed.envelope();
                if (!proof.eligible(source)) {
                    continue;
                }
                ServerResourceLocator resource = listed.resource();
                CoreState before = synchronizeCore(resource);
                MutationRow sourceReceipt = before == null ? null : mutation(before.mutationId());
                if (before == null || !sameCoreState(before, coreState(source))
                    || !provenCoreCatalogSource(before, sourceReceipt)
                    || proof.evolution().requiresSourceReceipt() && !validEvolutionSourceReceipt(before, sourceReceipt)) {
                    continue;
                }
                UUID mutationId = proof.mutationId(source);
                MutationRow registered = mutation(mutationId);
                if (registered != null) {
                    if (registered.status() == Status.REJECTED && CoreCatalogEvolution.isActor(registered.actorId())) {
                        rearmRejectedEvolution(registered);
                        recoverPending(Set.of());
                        recoverCommittedCoreTransitions();
                        MutationRow recovered = mutation(mutationId);
                        if (recoveryBlocked || recovered == null || recovered.status() != Status.APPLIED
                            || !publishedCoreReceipt(recovered)) {
                            throw new IllegalStateException("Core catalog evolution recovery is incomplete: "
                                + resource.canonicalText());
                        }
                        continue;
                    }
                    throw new IllegalStateException("Core catalog evolution has an unresolved registered mutation: "
                        + resource.canonicalText());
                }
                CoreGraphStorageBoundary.Decoded candidate = proof.project(source, mutationId);
                coreAuthority.validateSave(resource, candidate);
                if (coreAuthority.activeCatalogEvolution().orElse(null) != proof
                    || !proof.target().equals(coreAuthority.activeCatalogBinding().orElse(null))) {
                    throw new IllegalStateException("Active Core catalog changed before evolution admission");
                }
                Map<String, Object> payload = corePayload(candidate);
                Command command = new Command("SAVE", resource, null, resource, mutationId,
                    source.envelope().assetRevision(), payload, payloadCodec.canonicalize(payload).checksum(),
                    source.envelope().assetActivationState(), null);
                CoreOutcome outcome = corePending(command, before, coreState(candidate));
                MutationRow pending = insertEvolutionPending(command, proof, source, candidate, outcome);
                verifyEvolutionReceipt(pending, true);
                CoreGraphStorageBoundary.Decoded saved = coreAuthority.save(resource, candidate, mutationId,
                    source.envelope().assetRevision(), candidate.envelope().assetHash());
                CoreState after = coreState(saved);
                verifyCorePostApply(command, outcome, after);
                MutationRow committed = commitCoreApplied(pending, after);
                if (recoveryBlocked || !publishedCoreReceipt(committed)) {
                    throw new IllegalStateException("Core catalog evolution is awaiting committed transition recovery");
                }
            }
        }
        if (coreAuthority.activeCatalogEvolution().orElse(null) != proof
            || !proof.target().equals(coreAuthority.activeCatalogBinding().orElse(null))) {
            throw new IllegalStateException("Active Core catalog changed during evolution");
        }
    }

    private void rearmRejectedEvolution(MutationRow rejected) {
        MutationRow pending = new MutationRow(rejected.mutationId(), rejected.actorId(), rejected.fingerprint(),
            rejected.operation(), rejected.requestedResource(), rejected.responseResource(), rejected.sourceResource(),
            rejected.targetResource(), rejected.targetActivationState(), rejected.expectedRevision(),
            rejected.preconditionHash(), Status.PENDING, rejected.resultRevision(), rejected.resultMutationId(),
            rejected.resultHash(), rejected.resultDeleted(), rejected.resultActivationState(), rejected.resultPayload(),
            rejected.sequence(), "", "", rejected.createdAt(), Instant.now().toEpochMilli(),
            rejected.preconditionAssetHash(), rejected.preconditionCorePayloadHash(), rejected.preconditionCorePayloadKind(),
            rejected.resultAssetHash(), rejected.resultCorePayloadHash(), rejected.resultCorePayloadKind());
        verifyEvolutionReceipt(pending, true);
        try {
            connection.setAutoCommit(false);
            writeMutation(pending, true);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollback();
            throw new IllegalStateException("Failed To Reopen A Proven Core Catalog Evolution", failure);
        } finally {
            resetAutoCommit();
        }
    }

    private MutationRow insertEvolutionPending(Command command, CoreCatalogEvolution.Proof proof,
                                                CoreGraphStorageBoundary.Decoded source,
                                                CoreGraphStorageBoundary.Decoded candidate, CoreOutcome outcome) {
        return insertCatalogPending(command, proof.evolution().id(), proof.fingerprint(source, command.mutationId()),
            proof.evolution().registrationHash().canonicalText(), source, proof.target(), candidate, outcome);
    }

    private MutationRow insertCatalogPending(Command command, String actor, String fingerprint, String registrationHash,
            CoreGraphStorageBoundary.Decoded source, CatalogBinding target,
            CoreGraphStorageBoundary.Decoded candidate, CoreOutcome outcome) {
        String envelope = new String(coreBoundary.encode(source), StandardCharsets.UTF_8);
        String resultHash = candidate.envelope().assetHash().canonicalText();
        String evidenceHash = evolutionEvidenceHash(command.mutationId(), registrationHash, envelope,
            target.canonicalText(), resultHash);
        try {
            connection.setAutoCommit(false);
            MutationRow pending = insertCorePending(command, actor, fingerprint, outcome);
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO core_catalog_evolution_receipt(mutation_id, registration_hash, source_envelope,
                    target_binding, result_asset_hash, proof_hash) VALUES(?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, command.mutationId().toString());
                statement.setString(2, registrationHash);
                statement.setString(3, envelope);
                statement.setString(4, target.canonicalText());
                statement.setString(5, resultHash);
                statement.setString(6, evidenceHash);
                statement.executeUpdate();
            }
            connection.commit();
            return pending;
        } catch (SQLException | RuntimeException failure) {
            rollback();
            throw new IllegalStateException("Failed To Persist Core Catalog Evolution Proof", failure);
        } finally {
            resetAutoCommit();
        }
    }

    private EvolutionReceipt verifyEvolutionReceipt(MutationRow row, boolean requireActive) {
        EvolutionReceipt evidence = evolutionReceipt(row.mutationId());
        if (evidence == null) {
            if (CoreCatalogEvolution.isActor(row.actorId()) || CoreCatalogCompatibilityRebind.PROOF_ACTOR.equals(row.actorId())) {
                throw new IllegalStateException("Core catalog evolution receipt has no durable proof");
            }
            return null;
        }
        if (CoreCatalogCompatibilityRebind.REGISTRATION.equals(evidence.registrationHash())) {
            verifyCompatibleCatalogProjection(row, evidence, requireActive, new LinkedHashSet<>());
            return evidence;
        }
        CoreCatalogEvolution evolution = CoreCatalogEvolution.registered(evidence.registrationHash());
        ServerResourceLocator resource = locator(row.responseResource());
        CoreGraphStorageBoundary.Decoded source = coreBoundary.decodeText(evidence.sourceEnvelope(), resource);
        CatalogBinding target = CatalogBinding.parseCanonicalText(evidence.targetBinding());
        CoreState sourceState = coreState(source);
        UUID expectedId = evolution.mutationId(source, target);
        CoreGraphStorageBoundary.Decoded projected = evolution.projectReceipt(source, target, expectedId);
        CoreState expected = coreState(projected);
        MutationRow sourceReceipt = mutation(sourceState.mutationId());
        if (!evolution.id().equals(row.actorId()) || !"SAVE".equals(row.operation())
            || row.status() != Status.PENDING && row.status() != Status.APPLIED
            || !evolution.registrationHash().canonicalText().equals(evidence.registrationHash())
            || !expectedId.equals(row.mutationId())
            || !evolution.fingerprint(source, target, expectedId).equals(row.fingerprint())
            || !resource.canonicalText().equals(row.requestedResource())
            || !resource.canonicalText().equals(row.targetResource()) || row.sourceResource() != null
            || row.expectedRevision() != sourceState.revision()
            || !sourceState.protocolHash().equals(row.preconditionHash())
            || !Objects.equals(sourceState.assetHash(), row.preconditionAssetHash())
            || !Objects.equals(sourceState.corePayloadHash(), row.preconditionCorePayloadHash())
            || !Objects.equals(sourceState.corePayloadKind(), row.preconditionCorePayloadKind())
            || row.targetActivationState() != sourceState.activationState()
            || row.resultActivationState() != sourceState.activationState()
            || !provenCoreCatalogSource(sourceState, sourceReceipt)
            || evolution.requiresSourceReceipt() && !validEvolutionSourceReceipt(sourceState, sourceReceipt)
            || row.status() == Status.APPLIED && sourceReceipt != null && row.sequence() <= sourceReceipt.sequence()
            || !matchesCoreOutcome(expected, row) || !expected.assetHash().equals(evidence.resultAssetHash())
            || !evolutionEvidenceHash(row.mutationId(), evidence.registrationHash(), evidence.sourceEnvelope(),
                evidence.targetBinding(), evidence.resultAssetHash()).equals(evidence.proofHash())) {
            throw new IllegalStateException("Core catalog evolution source and result receipt proof is invalid");
        }
        if (row.status() == Status.PENDING) {
            if (row.sequence() != 0L || row.resultPayload() == null
                || !payloadCodec.canonicalize(corePayload(projected)).checksum().canonicalText().equals(row.resultHash())
                || !payloadCodec.canonicalInput(corePayload(projected)).equals(row.resultPayload())) {
                throw new IllegalStateException("Core catalog evolution pending payload proof is invalid");
            }
        } else if (row.resultPayload() != null
            || !validCoreCatalogRebindTransition(target, row, uncheckedCoreTransitionProof(row.mutationId()), expected)) {
            throw new IllegalStateException("Core catalog evolution committed transition proof is invalid");
        }
        if (requireActive) {
            CoreCatalogEvolution.Proof active = coreAuthority.activeCatalogEvolution().orElseThrow(() ->
                new IllegalStateException("Core catalog evolution target authority is unavailable"));
            if (active.evolution() != evolution || !target.equals(active.target())
                || !target.equals(coreAuthority.activeCatalogBinding().orElse(null))
                || !active.eligible(source)) {
                throw new IllegalStateException("Core catalog evolution target authority changed while pending");
            }
            coreAuthority.validateSave(resource, projected);
        }
        return evidence;
    }

    private CoreGraphStorageBoundary.Decoded verifyCompatibleCatalogProjection(MutationRow row, EvolutionReceipt evidence,
            boolean requireActive, Set<UUID> path) {
        if (path.size() >= 64 || !path.add(row.mutationId())) {
            throw new IllegalStateException("Compatible Catalog Receipt Dependency Is Cyclic Or Too Deep");
        }
        try {
            ServerResourceLocator resource = locator(row.responseResource());
            CoreGraphStorageBoundary.Decoded source = coreBoundary.decodeText(evidence.sourceEnvelope(), resource);
            CatalogBinding target = CatalogBinding.parseCanonicalText(evidence.targetBinding());
            CoreState before = coreState(source);
            Map<ServerResourceLocator, Long> dependencies = compatibleCatalogDependencies(source, target, row.sequence(), requireActive, path);
            UUID expectedId = CoreCatalogCompatibilityRebind.mutationId(resource, source, target, dependencies);
            CoreGraphStorageBoundary.Decoded projected = CoreCatalogCompatibilityRebind.project(source, target, expectedId, dependencies);
            CoreState expected = coreState(projected);
            MutationRow sourceReceipt = mutation(before.mutationId());
            if (!CoreCatalogCompatibilityRebind.REGISTRATION.equals(evidence.registrationHash())
                || !CoreCatalogCompatibilityRebind.PROOF_ACTOR.equals(row.actorId()) || !"SAVE".equals(row.operation())
                || row.status() != Status.PENDING && row.status() != Status.APPLIED
                || !expectedId.equals(row.mutationId())
                || !compatibleCoreCatalogFingerprint(source, target, expectedId).equals(row.fingerprint())
                || !resource.canonicalText().equals(row.requestedResource())
                || !resource.canonicalText().equals(row.targetResource()) || row.sourceResource() != null
                || row.expectedRevision() != before.revision() || !before.protocolHash().equals(row.preconditionHash())
                || !Objects.equals(before.assetHash(), row.preconditionAssetHash())
                || !Objects.equals(before.corePayloadHash(), row.preconditionCorePayloadHash())
                || !Objects.equals(before.corePayloadKind(), row.preconditionCorePayloadKind())
                || row.targetActivationState() != before.activationState() || row.resultActivationState() != before.activationState()
                || !compatibleCoreCatalogSource(before, sourceReceipt)
                || row.status() == Status.APPLIED && (row.sequence() < 1L || sourceReceipt != null && row.sequence() <= sourceReceipt.sequence())
                || !matchesCoreOutcome(expected, row) || !expected.assetHash().equals(evidence.resultAssetHash())
                || !evolutionEvidenceHash(row.mutationId(), evidence.registrationHash(), evidence.sourceEnvelope(),
                    evidence.targetBinding(), evidence.resultAssetHash()).equals(evidence.proofHash())) {
                throw new IllegalStateException("Compatible Catalog Source And Result Receipt Proof Is Invalid");
            }
            if (row.status() == Status.PENDING) {
                if (row.sequence() != 0L || row.resultPayload() == null
                    || !payloadCodec.canonicalInput(corePayload(projected)).equals(row.resultPayload())) {
                    throw new IllegalStateException("Compatible Catalog Pending Payload Proof Is Invalid");
                }
            } else if (row.resultPayload() != null
                || !validCoreCatalogRebindTransition(target, row, uncheckedCoreTransitionProof(row.mutationId()), expected)) {
                throw new IllegalStateException("Compatible Catalog Committed Transition Proof Is Invalid");
            }
            if (requireActive) {
                if (!target.equals(coreAuthority.activeCatalogBinding().orElse(null))) {
                    throw new IllegalStateException("Compatible Catalog Target Changed While Pending");
                }
                coreAuthority.validateSave(resource, projected);
            }
            return projected;
        } finally {
            path.remove(row.mutationId());
        }
    }

    private Map<ServerResourceLocator, Long> compatibleCatalogDependencies(CoreGraphStorageBoundary.Decoded source,
            CatalogBinding target, long sequence, boolean requireCurrent, Set<UUID> path) {
        Map<ServerResourceLocator, Long> revisions = new LinkedHashMap<>();
        for (FunctionBinding binding : CoreCatalogCompatibilityRebind.graph(source).functions()) {
            ServerResourceLocator resource = binding.function();
            if (!serverId.equals(resource.serverId()) || !OwnerId.of("restudio.resync").equals(resource.owner())
                || !"function".equals(resource.resourceType().value()) || binding.revision() < 1L) {
                throw new IllegalStateException("Compatible Catalog Function Dependency Identity Is Invalid");
            }
            CoreState current = requireCurrent ? synchronizeCore(resource) : null;
            MutationRow selected = requireCurrent ? current == null ? null : mutation(current.mutationId())
                : compatibleCatalogHead(resource, sequence);
            long revision = requireCurrent ? current == null ? -1L : current.revision()
                : selected == null ? binding.revision() : selected.resultRevision();
            if (revision < binding.revision() || requireCurrent && (current.deleted()
                || current.activationState() != ResourceActivationState.ACTIVE
                || current.decoded().functionSourceDocument() == null
                || !target.equals(CoreCatalogCompatibilityRebind.graph(current.decoded()).catalogBinding()))) {
                throw new IllegalStateException("Compatible Catalog Function Dependency Is Not An Active Current Target");
            }
            if (revision == binding.revision()) {
                if (requireCurrent) {
                    requireCompatibleSignature(binding, current.decoded().functionSourceDocument().signature());
                }
                continue;
            }
            CoreGraphStorageBoundary.Decoded previous = null;
            for (MutationRow child : compatibleCatalogChain(resource, binding.revision(), revision, sequence)) {
                EvolutionReceipt proof = evolutionReceipt(child.mutationId());
                if (proof == null || !CoreCatalogCompatibilityRebind.REGISTRATION.equals(proof.registrationHash())
                    || !publishedCoreReceipt(child)) {
                    throw new IllegalStateException("Function Pin Advancement Requires Durable Catalog Only Source Evidence");
                }
                CoreGraphStorageBoundary.Decoded childSource = coreBoundary.decodeText(proof.sourceEnvelope(), resource);
                if (previous == null) {
                    if (childSource.envelope().assetRevision() != binding.revision()
                        || childSource.functionSourceDocument() == null) {
                        throw new IllegalStateException("Function Pin Source Revision Is Not Proven");
                    }
                    requireCompatibleSignature(binding, childSource.functionSourceDocument().signature());
                } else if (!sameCoreState(coreState(previous), coreState(childSource))) {
                    throw new IllegalStateException("Function Catalog Receipt Chain Is Not Contiguous");
                }
                previous = verifyCompatibleCatalogProjection(child, proof, false, path);
            }
            if (previous == null || previous.envelope().assetRevision() != revision
                || !target.equals(CoreCatalogCompatibilityRebind.graph(previous).catalogBinding())
                || requireCurrent && !sameCoreState(coreState(previous), current)) {
                throw new IllegalStateException("Function Catalog Receipt Chain Does Not Prove The Selected Head");
            }
            requireCompatibleSignature(binding, previous.functionSourceDocument().signature());
            if (revisions.putIfAbsent(resource, revision) != null) {
                throw new IllegalStateException("Compatible Catalog Function Dependency Is Duplicated");
            }
        }
        return Map.copyOf(revisions);
    }

    private static void requireCompatibleSignature(FunctionBinding binding, FunctionSignature signature) {
        if (!binding.function().equals(signature.function().resource())
            || !compatibleParameters(binding.inputs(), signature.inputs()) || !compatibleParameters(binding.outputs(), signature.outputs())) {
            throw new IllegalStateException("Function Pin Parameters Do Not Match The Proven Source Signature");
        }
    }

    private static boolean compatibleParameters(List<FunctionParameter> declared, List<FunctionParameterContract> parameters) {
        return declared.size() == parameters.size() && declared.stream().allMatch(parameter -> parameters.stream()
            .anyMatch(current -> current.id().equals(parameter.parameterId()) && current.type().equals(parameter.type())
                && Objects.equals(current.defaultValue(), parameter.defaultValue())));
    }

    private MutationRow compatibleCatalogHead(ServerResourceLocator resource, long sequence) {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM resource_mutation_receipt WHERE response_resource = ? AND status = 'APPLIED'
                AND sequence > 0 AND sequence < ? ORDER BY sequence DESC LIMIT 1
            """)) {
            statement.setString(1, resource.canonicalText());
            statement.setLong(2, sequence);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readMutation(result) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed To Read Historical Function Catalog Head", failure);
        }
    }

    private List<MutationRow> compatibleCatalogChain(ServerResourceLocator resource, long sourceRevision,
            long targetRevision, long sequence) {
        List<MutationRow> receipts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM resource_mutation_receipt WHERE response_resource = ? AND status = 'APPLIED'
                AND result_revision > ? AND result_revision <= ? AND sequence > 0 AND sequence < ?
            ORDER BY result_revision, sequence
            """)) {
            statement.setString(1, resource.canonicalText());
            statement.setLong(2, sourceRevision);
            statement.setLong(3, targetRevision);
            statement.setLong(4, sequence == 0L ? Long.MAX_VALUE : sequence);
            long expectedRevision = Math.addExact(sourceRevision, 1L);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    MutationRow row = readMutation(result);
                    if (row.resultRevision() != expectedRevision || row.expectedRevision() != expectedRevision - 1L) {
                        throw new IllegalStateException("Function Catalog Receipt History Has A Gap Or Collision");
                    }
                    expectedRevision = Math.addExact(expectedRevision, 1L);
                    receipts.add(row);
                }
            }
            if (expectedRevision != Math.addExact(targetRevision, 1L)) {
                throw new IllegalStateException("Function Catalog Receipt History Is Incomplete");
            }
            return List.copyOf(receipts);
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed To Read Function Catalog Receipt History", failure);
        }
    }

    private EvolutionReceipt evolutionReceipt(UUID mutationId) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM core_catalog_evolution_receipt WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new EvolutionReceipt(result.getString("registration_hash"),
                    result.getString("source_envelope"), result.getString("target_binding"),
                    result.getString("result_asset_hash"), result.getString("proof_hash")) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed To Read Core Catalog Evolution Proof", failure);
        }
    }

    private String evolutionEvidenceHash(UUID mutationId, String registrationHash, String sourceEnvelope,
                                         String targetBinding, String resultAssetHash) {
        String actor = CoreCatalogCompatibilityRebind.REGISTRATION.equals(registrationHash)
            ? CoreCatalogCompatibilityRebind.PROOF_ACTOR : CoreCatalogEvolution.registered(registrationHash).id();
        return StorageSafety.sha256(String.join("\n", actor, mutationId.toString(),
            registrationHash, sourceEnvelope, targetBinding, resultAssetHash));
    }

    private boolean publishedCoreReceipt(MutationRow row) {
        CoreTransitionProof proof = row == null ? null : uncheckedCoreTransitionProof(row.mutationId());
        return proof != null && proof.published() && proof.envelope() == null
            && proof.hash() != null && !proof.hash().isBlank();
    }

    private boolean provenCoreCatalogSource(CoreState source, MutationRow row) {
        return validEvolutionSourceReceipt(source, row) || historicalLiveCoreCatalogSource(source, row);
    }

    private boolean compatibleCoreCatalogSource(CoreState source, MutationRow row) {
        return row == null ? historicalLiveCoreCatalogSource(source, row)
            : compatibleCoreCatalogSourceReceipt(source, row);
    }

    private boolean historicalLiveCoreCatalogSource(CoreState source, MutationRow row) {
        return row == null && source != null && !source.deleted() && source.decoded() != null
            && source.decoded().envelope().assetFormatVersion()
            == CoreGraphStorageBoundary.CURRENT_ASSET_FORMAT_VERSION;
    }

    private boolean validEvolutionSourceReceipt(CoreState source, MutationRow row) {
        if (!compatibleCoreCatalogSourceReceipt(source, row)) {
            return false;
        }
        if (publishedCoreReceipt(row)) {
            return validCoreCatalogRebindTransition(CoreCatalogCompatibilityRebind.graph(source.decoded()).catalogBinding(),
                row, uncheckedCoreTransitionProof(row.mutationId()), source);
        }
        return validAggregateEvolutionSource(source, row);
    }

    private boolean validAggregateEvolutionSource(CoreState source, MutationRow row) {
        return "CREATE".equals(row.operation()) && aggregateReceipt(row.mutationId()) != null
            && aggregatePublished(row.mutationId()) && validAggregateSource(source, row);
    }

    private boolean validAggregateSource(CoreState source, MutationRow row) {
        if (!compatibleCoreCatalogSourceReceipt(source, row)) {
            return false;
        }
        if (!"CREATE".equals(row.operation()) || row.expectedRevision() != 0L || row.resultRevision() < 1L
            || !row.mutationId().equals(source.mutationId()) || !row.requestedResource().equals(row.responseResource())
            || !row.responseResource().equals(row.targetResource()) || row.sourceResource() != null
            || row.targetActivationState() != null || row.preconditionHash() == null
            || (row.resultRevision() == 1L ? !row.preconditionHash().isEmpty()
                : !row.preconditionHash().matches("[0-9a-f]{64}"))
            || row.preconditionAssetHash() != null || row.preconditionCorePayloadHash() != null
            || row.preconditionCorePayloadKind() != null || row.resultPayload() == null) {
            return false;
        }
        AggregateReceipt aggregate = aggregateReceipt(row.mutationId());
        CoreTransitionProof transition = uncheckedCoreTransitionProof(row.mutationId());
        if (aggregate == null || aggregate.metadata() == null
            || !projectMetadataResource().equals(aggregate.metadataResource())
            || transition == null || transition.published() || transition.envelope() != null || transition.hash() != null) {
            return false;
        }
        CoreGraphStorageBoundary.Decoded retained = coreBoundary.decode(
            CanonicalJson.canonicalBytes(corePayload(row.resultPayload())), source.resource());
        CanonicalPayload<Map<String, Object>> metadata = payloadCodec.canonicalize(
            payload(gson.fromJson(aggregate.metadata().payload(), Map.class)));
        return sameCoreState(source, coreState(retained))
            && payloadCodec.canonicalInput(corePayload(source.decoded())).equals(row.resultPayload())
            && metadata.checksum().canonicalText().equals(aggregate.metadata().payloadHash())
            && canonicalInput(metadata).equals(aggregate.metadata().payload())
            && row.fingerprint().equals(fingerprint(command(row), row.actorId()));
    }

    private void recoverCommittedAggregateCreates() throws SQLException {
        if (recoveryBlocked || !coreAuthority.available()) {
            return;
        }
        List<MutationRow> unpublished = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT r.* FROM resource_mutation_receipt r
            JOIN resource_create_aggregate_receipt a ON a.mutation_id = r.mutation_id
            WHERE r.status = 'APPLIED' AND a.published = 0 AND r.result_core_payload_kind IS NOT NULL
            ORDER BY r.sequence
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                unpublished.add(readMutation(result));
            }
        }
        for (MutationRow row : unpublished) {
            try {
                ServerResourceLocator resource = locator(row.responseResource());
                if (!isCoreResource(resource) || row.resultPayload() == null) {
                    throw new IllegalStateException("Unpublished aggregate Core receipt identity is invalid");
                }
                CoreState original = coreState(coreBoundary.decode(CanonicalJson.canonicalBytes(
                    corePayload(row.resultPayload())), resource));
                if (!validAggregateSource(original, row)) {
                    throw new IllegalStateException("Unpublished aggregate Core receipt evidence is invalid");
                }
                if (!publishAggregateCommitted(row)) {
                    throw new IllegalStateException("Committed aggregate Core publication remains pending");
                }
            } catch (RuntimeException failure) {
                blockRecovery("Committed aggregate Core publication recovery failed: " + failure.getClass().getSimpleName());
                return;
            }
        }
    }

    private CoreTransitionProof uncheckedCoreTransitionProof(UUID mutationId) {
        try {
            return coreTransitionProof(mutationId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed To Read Core Transition Proof", failure);
        }
    }

    private record EvolutionReceipt(String registrationHash, String sourceEnvelope, String targetBinding,
                                    String resultAssetHash, String proofHash) {
    }

    private boolean compatibleCoreCatalogSourceReceipt(CoreState source, MutationRow receipt) {
        return receipt != null && receipt.status() == Status.APPLIED && receipt.sequence() > 0L
            && source.resource().canonicalText().equals(receipt.responseResource())
            && receipt.resultRevision() == source.revision()
            && source.mutationId().toString().equals(receipt.resultMutationId())
            && source.protocolHash().equals(receipt.resultHash()) && !receipt.resultDeleted()
            && receipt.resultActivationState() == source.activationState()
            && Objects.equals(source.assetHash(), receipt.resultAssetHash())
            && Objects.equals(source.corePayloadHash(), receipt.resultCorePayloadHash())
            && Objects.equals(source.corePayloadKind(), receipt.resultCorePayloadKind());
    }

    private String compatibleCoreCatalogFingerprint(CoreGraphStorageBoundary.Decoded source, CatalogBinding target,
                                                     UUID mutationId) {
        GraphDocument graph = CoreCatalogCompatibilityRebind.graph(source);
        return StorageSafety.sha256(String.join("\n", CoreCatalogCompatibilityRebind.ID,
            graph.resource().canonicalText(), Long.toString(source.envelope().assetRevision()),
            source.envelope().assetMutationId(), source.envelope().assetHash().canonicalText(),
            graph.catalogBinding().canonicalText(), target.canonicalText(), mutationId.toString()));
    }

    private boolean completedCoreCatalogRebind(CoreCatalogBindingMigration migration) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT manifest_hash, source_binding, target_binding, plan_hash, status, report_hash "
                + "FROM core_catalog_rebind_run WHERE migration_id = ?")) {
            statement.setString(1, CoreCatalogBindingMigration.ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"COMPLETE".equals(result.getString("status"))) {
                    return false;
                }
                if (!migration.manifestHash().canonicalText().equals(result.getString("manifest_hash"))
                    || !migration.source().canonicalText().equals(result.getString("source_binding"))
                    || !migration.target().canonicalText().equals(result.getString("target_binding"))
                    || result.getString("report_hash") == null) {
                    throw new IllegalStateException("Completed Core catalog rebind identity is invalid");
                }
                List<CoreCatalogRebindItem> items = loadCoreCatalogRebindItems();
                requireExactCoreCatalogRebindItems(migration, items);
                String planHash = migration.planHash(coreCatalogRebindPlan(migration, items)).canonicalText();
                if (!planHash.equals(result.getString("plan_hash"))) {
                    throw new IllegalStateException("Completed Core catalog rebind plan hash is invalid");
                }
                for (CoreCatalogRebindItem item : items) {
                    requireCompletedCoreCatalogRebindItem(migration, item);
                }
                String expectedReport = coreCatalogRebindReport(migration, items, planHash);
                String storedReport = Objects.requireNonNull(migrationReports,
                    "Core catalog rebind report authority is required").readCatalogRebindReport();
                if (!expectedReport.equals(storedReport)
                    || !StorageSafety.sha256(storedReport).equals(result.getString("report_hash"))) {
                    throw new IllegalStateException("Completed Core catalog rebind report proof is invalid");
                }
                return true;
            }
        }
    }

    private void requireCompletedCoreCatalogRebindItem(CoreCatalogBindingMigration migration,
                                                       CoreCatalogRebindItem item) throws SQLException {
        CoreCatalogBindingMigration.Incident incident = migration.incident(item.resource());
        if (!"APPLIED".equals(item.status()) || item.resultRevision() == null || item.resultAssetHash() == null) {
            throw new IllegalStateException("Completed Core catalog rebind contains a non-applied item");
        }
        if (incident == null || incident.revision() != item.sourceRevision()
            || !incident.mutationId().equals(item.sourceMutationId())
            || !incident.assetHash().equals(item.sourceAssetHash())) {
            throw new IllegalStateException("Completed Core catalog rebind source item is not frozen");
        }
        CoreGraphStorageBoundary.Decoded source = coreBoundary.decodeText(item.sourceEnvelope(), item.resource());
        ContentHash sourceCoreHash = source.graphDocument() != null
            ? source.graphDocument().checksum() : source.functionSourceDocument().checksum();
        ContentHash sourceProtocolHash = payloadCodec.canonicalize(corePayload(source)).checksum();
        CoreState expectedResult = coreState(migration.project(source, item.mutationId()));
        CoreTransitionProof transitionProof = coreTransitionProof(item.mutationId());
        if (!migration.eligible(item.resource(), source) || !sourceCoreHash.equals(item.sourceCoreHash())
            || item.resultRevision() != Math.addExact(item.sourceRevision(), 1L)) {
            throw new IllegalStateException("Completed Core catalog rebind source envelope proof is invalid");
        }
        MutationRow receipt = mutation(item.mutationId());
        if (receipt == null || receipt.status() != Status.APPLIED
            || !CoreCatalogBindingMigration.ACTOR.equals(receipt.actorId()) || !"SAVE".equals(receipt.operation())
            || !coreCatalogRebindFingerprint(migration, item).equals(receipt.fingerprint())
            || !item.resource().canonicalText().equals(receipt.requestedResource())
            || !item.resource().canonicalText().equals(receipt.responseResource())
            || receipt.sourceResource() != null
            || !item.resource().canonicalText().equals(receipt.targetResource())
            || receipt.targetActivationState() != source.envelope().assetActivationState()
            || receipt.expectedRevision() != item.sourceRevision()
            || !sourceProtocolHash.canonicalText().equals(receipt.preconditionHash())
            || !Objects.equals(item.sourceAssetHash().canonicalText(), receipt.preconditionAssetHash())
            || !Objects.equals(item.sourceCoreHash().canonicalText(), receipt.preconditionCorePayloadHash())
            || !Objects.equals(item.sourcePayloadKind(), receipt.preconditionCorePayloadKind())
            || receipt.resultRevision() != item.resultRevision()
            || !item.mutationId().toString().equals(receipt.resultMutationId()) || receipt.resultDeleted()
            || receipt.resultPayload() != null || receipt.sequence() < 1L
            || !item.resultAssetHash().canonicalText().equals(expectedResult.assetHash())
            || !matchesCoreOutcome(expectedResult, receipt)
            || !validCoreCatalogRebindTransition(migration, receipt, transitionProof, expectedResult)) {
            throw new IllegalStateException("Completed Core catalog rebind receipt linkage is invalid: "
                + item.resource());
        }
        requireCompletedCoreCatalogRebindHead(migration, item, receipt);
    }

    private void requireCompletedCoreCatalogRebindHead(CoreCatalogBindingMigration migration,
                                                       CoreCatalogRebindItem item,
                                                       MutationRow migrationReceipt) throws SQLException {
        CoreState current = synchronizeCore(item.resource());
        if (current == null || current.revision() < item.resultRevision()) {
            throw invalidCompletedCoreCatalogRebindHead(item);
        }
        MutationRow previous = migrationReceipt;
        CatalogBinding expectedBinding = migration.target();
        long expectedRevision = Math.addExact(item.resultRevision(), 1L);
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM resource_mutation_receipt
            WHERE response_resource = ? AND status = 'APPLIED' AND result_revision > ? AND result_revision <= ?
                AND result_mutation_id = mutation_id
            ORDER BY result_revision, sequence, created_at, mutation_id
            """)) {
            statement.setString(1, item.resource().canonicalText());
            statement.setLong(2, item.resultRevision());
            statement.setLong(3, current.revision());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    MutationRow descendant = readMutation(result);
                    CoreTransitionProof transitionProof = readCoreTransitionProof(result);
                    CatalogBinding descendantBinding = coreCatalogDescendantBinding(item.resource(), previous,
                        descendant, expectedBinding);
                    if (descendant.resultRevision() != expectedRevision
                        || !validCoreCatalogRebindDescendant(descendantBinding, item.resource(), previous, descendant,
                        transitionProof, !expectedBinding.equals(descendantBinding))) {
                        throw invalidCompletedCoreCatalogRebindHead(item);
                    }
                    previous = descendant;
                    expectedBinding = descendantBinding;
                    expectedRevision = Math.addExact(expectedRevision, 1L);
                }
            }
        }
        if (expectedRevision != Math.addExact(current.revision(), 1L) || !matchesCoreOutcome(current, previous)
            || !current.deleted() && !expectedBinding.equals(
            CoreCatalogBindingMigration.graph(current.decoded()).catalogBinding())
            || !("CREATE".equals(previous.operation()) && aggregateReceipt(previous.mutationId()) != null
                ? validAggregateSource(current, previous)
                : validCoreCatalogRebindTransition(expectedBinding, previous, coreTransitionProof(previous.mutationId()), current))) {
            throw invalidCompletedCoreCatalogRebindHead(item);
        }
    }

    private CatalogBinding coreCatalogDescendantBinding(ServerResourceLocator resource, MutationRow previous,
                                                         MutationRow descendant, CatalogBinding expected) {
        EvolutionReceipt evolution = verifyEvolutionReceipt(descendant, false);
        if (evolution != null) {
            CoreGraphStorageBoundary.Decoded source = coreBoundary.decodeText(evolution.sourceEnvelope(), resource);
            if (!expected.equals(CoreCatalogCompatibilityRebind.graph(source).catalogBinding())
                || !previous.mutationId().toString().equals(source.envelope().assetMutationId())) {
                throw new IllegalStateException("Core catalog evolution does not extend the preceding binding");
            }
            return CatalogBinding.parseCanonicalText(evolution.targetBinding());
        }
        if (!CoreCatalogCompatibilityRebind.ACTOR.equals(descendant.actorId())) {
            return expected;
        }
        List<CatalogBinding> bindings = CoreCatalogEvolution.load().sources().stream().sorted().toList();
        CatalogBinding source = bindings.getFirst();
        CatalogBinding target = bindings.getLast();
        String seed = String.join("\n", resource.canonicalText(), Long.toString(previous.resultRevision()),
            previous.resultMutationId(), previous.resultAssetHash(), source.canonicalText(), target.canonicalText());
        UUID mutationId = IdentityCodec.deterministicUuid(CoreCatalogCompatibilityRebind.ID, seed);
        String fingerprint = StorageSafety.sha256(String.join("\n", CoreCatalogCompatibilityRebind.ID, seed,
            mutationId.toString()));
        CoreTransitionProof transition = uncheckedCoreTransitionProof(descendant.mutationId());
        if (source.generation() != 54L || target.generation() != 55L || !source.equals(expected)
            || !source.catalogChecksum().equals(target.catalogChecksum())
            || !"SAVE".equals(descendant.operation()) || descendant.status() != Status.APPLIED
            || !mutationId.equals(descendant.mutationId()) || !fingerprint.equals(descendant.fingerprint())
            || !publishedCoreReceipt(descendant)
            || !validCoreCatalogRebindTransition(target, descendant, transition, null)) {
            throw new IllegalStateException("Core catalog compatibility receipt binding proof is invalid");
        }
        return target;
    }

    private boolean validCoreCatalogRebindDescendant(CatalogBinding target,
                                                       ServerResourceLocator resource, MutationRow previous,
                                                       MutationRow descendant, CoreTransitionProof transitionProof,
                                                       boolean bindingChanged) {
        String resourceText = resource.canonicalText();
        if ("CREATE".equals(descendant.operation())) {
            if (!previous.resultDeleted() || descendant.sequence() <= previous.sequence()
                || descendant.expectedRevision() != 0L || descendant.resultRevision() != resultRevision(previous.resultRevision())
                || descendant.resultDeleted() || descendant.resultActivationState() == null
                || !descendant.mutationId().toString().equals(descendant.resultMutationId())
                || !resourceText.equals(descendant.requestedResource()) || !resourceText.equals(descendant.responseResource())
                || descendant.sourceResource() != null || !resourceText.equals(descendant.targetResource())
                || !Objects.equals(previous.resultHash(), descendant.preconditionHash())) {
                return false;
            }
            if (aggregateReceipt(descendant.mutationId()) != null) {
                if (descendant.resultPayload() == null) {
                    return false;
                }
                CoreState recreated = coreState(coreBoundary.decode(
                    CanonicalJson.canonicalBytes(corePayload(descendant.resultPayload())), resource));
                return target.equals(CoreCatalogBindingMigration.graph(recreated.decoded()).catalogBinding())
                    && validAggregateSource(recreated, descendant);
            }
            return Objects.equals(previous.resultAssetHash(), descendant.preconditionAssetHash())
                && Objects.equals(previous.resultCorePayloadHash(), descendant.preconditionCorePayloadHash())
                && Objects.equals(previous.resultCorePayloadKind(), descendant.preconditionCorePayloadKind())
                && validCoreCatalogRebindTransition(target, descendant, transitionProof, null);
        }
        boolean supportedOperation = "SAVE".equals(descendant.operation())
            || "ACTIVATE".equals(descendant.operation()) || "DELETE".equals(descendant.operation());
        return supportedOperation && descendant.sequence() > previous.sequence() && !previous.resultDeleted()
            && descendant.expectedRevision() == previous.resultRevision()
            && descendant.mutationId().toString().equals(descendant.resultMutationId())
            && resourceText.equals(descendant.requestedResource()) && resourceText.equals(descendant.responseResource())
            && descendant.sourceResource() == null && resourceText.equals(descendant.targetResource())
            && Objects.equals(previous.resultHash(), descendant.preconditionHash())
            && Objects.equals(previous.resultAssetHash(), descendant.preconditionAssetHash())
            && Objects.equals(previous.resultCorePayloadHash(), descendant.preconditionCorePayloadHash())
            && Objects.equals(previous.resultCorePayloadKind(), descendant.preconditionCorePayloadKind())
            && descendant.resultHash() != null && !descendant.resultHash().isBlank()
            && descendant.resultAssetHash() != null && !descendant.resultAssetHash().isBlank()
            && descendant.resultCorePayloadHash() != null && !descendant.resultCorePayloadHash().isBlank()
            && descendant.resultCorePayloadKind() != null && !descendant.resultCorePayloadKind().isBlank()
            && descendant.resultPayload() == null
            && ("DELETE".equals(descendant.operation()) == descendant.resultDeleted())
            && ("ACTIVATE".equals(descendant.operation())
            ? descendant.targetActivationState() == descendant.resultActivationState()
                && descendant.targetActivationState() != previous.resultActivationState()
            : bindingChanged && "SAVE".equals(descendant.operation())
                ? descendant.targetActivationState() == previous.resultActivationState()
                    && descendant.resultActivationState() == previous.resultActivationState()
                : descendant.targetActivationState() == null)
            && (!"SAVE".equals(descendant.operation())
                || descendant.resultActivationState() == previous.resultActivationState())
            && (descendant.resultDeleted() ? descendant.resultActivationState() == null
            : descendant.resultActivationState() != null)
            && (!descendant.resultDeleted()
            || Objects.equals(previous.resultHash(), descendant.resultHash())
                && Objects.equals(previous.resultAssetHash(), descendant.resultAssetHash())
                && Objects.equals(previous.resultCorePayloadHash(), descendant.resultCorePayloadHash())
                && Objects.equals(previous.resultCorePayloadKind(), descendant.resultCorePayloadKind()))
            && validCoreCatalogRebindTransition(target, descendant, transitionProof, null);
    }

    private boolean validCoreCatalogRebindTransition(CoreCatalogBindingMigration migration, MutationRow row,
                                                       CoreTransitionProof proof, CoreState exactState) {
        return validCoreCatalogRebindTransition(migration.target(), row, proof, exactState);
    }

    private boolean validCoreCatalogRebindTransition(CatalogBinding target, MutationRow row,
                                                       CoreTransitionProof proof, CoreState exactState) {
        if (proof == null || proof.hash() == null || proof.hash().isBlank()
            || proof.published() && proof.envelope() != null
            || !proof.published() && (proof.envelope() == null || proof.envelope().isBlank())) {
            return false;
        }
        try {
            ContentHash storedHash = new ContentHash(proof.hash());
            if (exactState != null) {
                CoreResourceMutationTransition expected = coreTransition(exactState, row.actorId());
                return expected.checkpoint().canonicalEnvelopeHash().equals(storedHash)
                    && (proof.envelope() == null || expected.canonicalEnvelope().equals(proof.envelope()));
            }
            if (row.resultDeleted()) {
                String envelope = new String(coreBoundary.encodeTombstone(locator(row.responseResource()),
                    row.resultRevision(), row.mutationId(), new ContentHash(row.resultCorePayloadHash())),
                    StandardCharsets.UTF_8);
                CoreResourceMutationTransition expected = new CoreResourceMutationTransition(
                    locator(row.responseResource()), row.resultRevision(), row.mutationId(), true, null,
                    row.actorId(), envelope);
                return expected.checkpoint().canonicalEnvelopeHash().equals(storedHash)
                    && (proof.envelope() == null || expected.canonicalEnvelope().equals(proof.envelope()));
            }
            if (proof.envelope() != null) {
                StoredCoreTransition stored = new StoredCoreTransition(row, proof.envelope(), storedHash);
                CoreResourceMutationTransition transition = storedCoreTransition(stored);
                CoreState state = coreState(coreBoundary.decodeText(transition.canonicalEnvelope(), transition.locator()));
                return matchesCoreOutcome(state, row) && target.equals(
                    CoreCatalogBindingMigration.graph(state.decoded()).catalogBinding());
            }
            return validPublishedLiveReceiptAuthority(row, proof);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean validPublishedLiveReceiptAuthority(MutationRow row, CoreTransitionProof proof) {
        return row.status() == Status.APPLIED && !row.resultDeleted() && row.resultPayload() == null
            && row.mutationId().toString().equals(row.resultMutationId()) && proof.published()
            && proof.envelope() == null;
    }

    private CoreTransitionProof coreTransitionProof(UUID mutationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT transition_envelope, transition_hash, transition_published
            FROM resource_mutation_receipt WHERE mutation_id = ?
            """)) {
            statement.setString(1, mutationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readCoreTransitionProof(result) : null;
            }
        }
    }

    private CoreTransitionProof readCoreTransitionProof(ResultSet result) throws SQLException {
        return new CoreTransitionProof(result.getString("transition_envelope"), result.getString("transition_hash"),
            result.getInt("transition_published") != 0);
    }

    private IllegalStateException invalidCompletedCoreCatalogRebindHead(CoreCatalogRebindItem item) {
        return new IllegalStateException("Completed Core catalog rebind state linkage is invalid: " + item.resource());
    }

    private List<CoreCatalogRebindItem> planCoreCatalogRebind(CoreCatalogBindingMigration migration) {
        List<CoreCatalogRebindItem> items = new ArrayList<>();
        for (ServerResourceLocator resource : migration.resources(serverId)) {
            CoreCatalogBindingMigration.Incident incident = Objects.requireNonNull(migration.incident(resource));
            UUID mutationId = migration.mutationId(resource, incident);
            CoreGraphResourceAuthority.CoreGraphResourceState state = coreAuthority.state(resource).orElse(null);
            if (state == null || state.deleted() || !migration.eligible(resource, state.envelope())) {
                String diagnostic = state == null || state.deleted()
                    ? "Frozen incident asset is unavailable"
                    : "Frozen incident asset identity changed";
                items.add(new CoreCatalogRebindItem(resource, incident.revision(), incident.mutationId(),
                    incident.assetHash(), UNAVAILABLE_CORE_HASH, "unavailable", "{}", mutationId, "REJECTED",
                    diagnostic, null, null));
                continue;
            }
            CoreGraphStorageBoundary.Decoded source = state.envelope();
            String status = "PLANNED";
            String diagnostic = "";
            try {
                coreAuthority.validateSave(resource, migration.project(source, mutationId));
            } catch (RuntimeException rejection) {
                status = "REJECTED";
                diagnostic = safeMessage(rejection);
            }
            items.add(new CoreCatalogRebindItem(resource, source.envelope().assetRevision(),
                UUID.fromString(source.envelope().assetMutationId()), source.envelope().assetHash(),
                state.corePayloadChecksum(), source.corePayloadKind(),
                new String(coreBoundary.encode(source), StandardCharsets.UTF_8), mutationId, status, diagnostic,
                null, null));
        }
        return items.stream().sorted(Comparator.comparing(item -> item.resource().canonicalText())).toList();
    }

    private void persistCoreCatalogRebindPlan(CoreCatalogBindingMigration migration,
                                               List<CoreCatalogRebindItem> items) throws SQLException {
        String canonicalPlan = coreCatalogRebindPlan(migration, items);
        String planHash = migration.planHash(canonicalPlan).canonicalText();
        long now = Instant.now().toEpochMilli();
        connection.setAutoCommit(false);
        try (PreparedStatement run = connection.prepareStatement("""
            INSERT INTO core_catalog_rebind_run(migration_id, manifest_hash, source_binding, target_binding,
                plan_hash, status, report_hash, created_at, updated_at) VALUES(?, ?, ?, ?, ?, 'RUNNING', NULL, ?, ?)
            """); PreparedStatement item = connection.prepareStatement("""
            INSERT INTO core_catalog_rebind_item(migration_id, resource, ordinal, source_revision,
                source_mutation_id, source_asset_hash, source_core_hash, source_payload_kind, source_envelope,
                mutation_id, status, diagnostic, result_revision, result_asset_hash)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
            """)) {
            run.setString(1, CoreCatalogBindingMigration.ID);
            run.setString(2, migration.manifestHash().canonicalText());
            run.setString(3, migration.source().canonicalText());
            run.setString(4, migration.target().canonicalText());
            run.setString(5, planHash);
            run.setLong(6, now);
            run.setLong(7, now);
            run.executeUpdate();
            for (int index = 0; index < items.size(); index++) {
                CoreCatalogRebindItem value = items.get(index);
                item.setString(1, CoreCatalogBindingMigration.ID);
                item.setString(2, value.resource().canonicalText());
                item.setInt(3, index);
                item.setLong(4, value.sourceRevision());
                item.setString(5, value.sourceMutationId().toString());
                item.setString(6, value.sourceAssetHash().canonicalText());
                item.setString(7, value.sourceCoreHash().canonicalText());
                item.setString(8, value.sourcePayloadKind());
                item.setString(9, value.sourceEnvelope());
                item.setString(10, value.mutationId().toString());
                item.setString(11, value.status());
                item.setString(12, value.diagnostic());
                item.addBatch();
            }
            item.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            rollback();
            throw exception;
        } finally {
            resetAutoCommit();
        }
    }

    private List<CoreCatalogRebindItem> loadCoreCatalogRebindItems() throws SQLException {
        List<CoreCatalogRebindItem> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM core_catalog_rebind_item WHERE migration_id = ? ORDER BY ordinal
            """)) {
            statement.setString(1, CoreCatalogBindingMigration.ID);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Long resultRevision = result.getObject("result_revision") == null ? null
                        : result.getLong("result_revision");
                    String resultAssetHash = result.getString("result_asset_hash");
                    items.add(new CoreCatalogRebindItem(locator(result.getString("resource")),
                        result.getLong("source_revision"), UUID.fromString(result.getString("source_mutation_id")),
                        new ContentHash(result.getString("source_asset_hash")),
                        new ContentHash(result.getString("source_core_hash")), result.getString("source_payload_kind"),
                        result.getString("source_envelope"), UUID.fromString(result.getString("mutation_id")),
                        result.getString("status"), result.getString("diagnostic"), resultRevision,
                        resultAssetHash == null ? null : new ContentHash(resultAssetHash)));
                }
            }
        }
        return List.copyOf(items);
    }

    private void requireCoreCatalogRebindRun(CoreCatalogBindingMigration migration,
                                             List<CoreCatalogRebindItem> items) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT manifest_hash, source_binding, target_binding, plan_hash, status
            FROM core_catalog_rebind_run WHERE migration_id = ?
            """)) {
            statement.setString(1, CoreCatalogBindingMigration.ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !migration.manifestHash().canonicalText().equals(result.getString(1))
                    || !migration.source().canonicalText().equals(result.getString(2))
                    || !migration.target().canonicalText().equals(result.getString(3))
                    || !migration.planHash(coreCatalogRebindPlan(migration, items)).canonicalText().equals(result.getString(4))) {
                    throw new IllegalStateException("Core catalog rebind durable plan identity is invalid");
                }
                if ("BLOCKED".equals(result.getString(5))) {
                    blockRecovery("Core catalog rebind is durably blocked");
                    throw new IllegalStateException("Core catalog rebind is durably blocked");
                }
                if (!"RUNNING".equals(result.getString(5))) {
                    throw new IllegalStateException("Core catalog rebind run status is invalid");
                }
            }
        }
    }

    private String coreCatalogRebindPlan(CoreCatalogBindingMigration migration, List<CoreCatalogRebindItem> items) {
        List<Map<String, Object>> values = items.stream().map(item -> Map.<String, Object>of(
            "resource", item.resource().canonicalText(), "sourceRevision", item.sourceRevision(),
            "sourceMutationId", item.sourceMutationId().toString(),
            "sourceAssetHash", item.sourceAssetHash().canonicalText(),
            "sourceCoreHash", item.sourceCoreHash().canonicalText(), "sourcePayloadKind", item.sourcePayloadKind(),
            "mutationId", item.mutationId().toString(), "admission", item.initialStatus())).toList();
        return CanonicalJson.canonicalize(Map.of("format", CoreCatalogBindingMigration.ID,
            "manifestHash", migration.manifestHash().canonicalText(), "items", values));
    }

    private String coreCatalogRebindFingerprint(CoreCatalogBindingMigration migration, CoreCatalogRebindItem item) {
        return StorageSafety.sha256(String.join("\n", CoreCatalogBindingMigration.ID,
            migration.manifestHash().canonicalText(), item.resource().canonicalText(),
            Long.toString(item.sourceRevision()), item.sourceMutationId().toString(),
            item.sourceAssetHash().canonicalText(), item.mutationId().toString()));
    }

    private void requireCoreCatalogRebindSource(CoreCatalogRebindItem item,
                                                CoreGraphStorageBoundary.Decoded source,
                                                CoreState current,
                                                CoreCatalogBindingMigration migration) {
        if (!migration.eligible(item.resource(), source) || current == null || current.deleted()
            || current.revision() != item.sourceRevision() || !current.mutationId().equals(item.sourceMutationId())
            || !current.assetHash().equals(item.sourceAssetHash().canonicalText())
            || !current.corePayloadHash().equals(item.sourceCoreHash().canonicalText())
            || !current.corePayloadKind().equals(item.sourcePayloadKind())) {
            throw new IllegalStateException("Core catalog rebind source proof changed: " + item.resource());
        }
    }

    private void markCoreCatalogRebindApplied(CoreCatalogRebindItem item, MutationRow receipt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE core_catalog_rebind_item SET status = 'APPLIED', diagnostic = '', result_revision = ?,
                result_asset_hash = ? WHERE migration_id = ? AND resource = ? AND status = 'PLANNED'
            """)) {
            statement.setLong(1, receipt.resultRevision());
            statement.setString(2, receipt.resultAssetHash());
            statement.setString(3, CoreCatalogBindingMigration.ID);
            statement.setString(4, item.resource().canonicalText());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Core catalog rebind item was not marked applied");
            }
        }
    }

    private void completeCoreCatalogRebind(CoreCatalogBindingMigration migration,
                                           List<CoreCatalogRebindItem> items) throws SQLException, IOException {
        requireExactCoreCatalogRebindItems(migration, items);
        if (items.stream().anyMatch(item -> !"APPLIED".equals(item.status()))) {
            blockCoreCatalogRebind("One or more frozen incident assets were not applied");
            throw new IllegalStateException("Core catalog rebind is blocked by a non-applied incident asset");
        }
        try {
            for (CoreCatalogRebindItem item : items) {
                requireCompletedCoreCatalogRebindItem(migration, item);
            }
        } catch (RuntimeException invalidProof) {
            blockCoreCatalogRebind("One or more frozen incident assets do not have exact applied proof");
            throw invalidProof;
        }
        String planHash = migration.planHash(coreCatalogRebindPlan(migration, items)).canonicalText();
        String report = coreCatalogRebindReport(migration, items, planHash);
        MigrationReportsPersistenceParticipant reports = Objects.requireNonNull(migrationReports,
            "Core catalog rebind report authority is required");
        if (!reports.isAdmitted()) {
            throw new IOException("Core catalog rebind report authority is not admitted");
        }
        reports.writeCatalogRebindRecoveryReport(report);
        String durableReport = reports.readCatalogRebindReport();
        if (!report.equals(durableReport)) {
            throw new IOException("Core catalog rebind durable report does not match the completed proof");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE core_catalog_rebind_run SET status = 'COMPLETE', report_hash = ?, updated_at = ?
            WHERE migration_id = ? AND status = 'RUNNING'
            """)) {
            statement.setString(1, StorageSafety.sha256(durableReport));
            statement.setLong(2, Instant.now().toEpochMilli());
            statement.setString(3, CoreCatalogBindingMigration.ID);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Core catalog rebind run was not completed");
            }
        }
    }

    private String coreCatalogRebindReport(CoreCatalogBindingMigration migration,
                                           List<CoreCatalogRebindItem> items, String planHash) {
        List<Map<String, Object>> results = items.stream().map(item -> {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("resource", item.resource().canonicalText());
            value.put("sourceRevision", item.sourceRevision());
            value.put("mutationId", item.mutationId().toString());
            value.put("status", item.status());
            if (!item.diagnostic().isBlank()) {
                value.put("diagnostic", item.diagnostic());
            }
            if (item.resultRevision() != null) {
                value.put("resultRevision", item.resultRevision());
                value.put("resultAssetHash", item.resultAssetHash().canonicalText());
            }
            return Map.copyOf(value);
        }).toList();
        return CanonicalJson.canonicalize(Map.of("format", "core-catalog-binding-rebind-report-v1",
            "migrationId", CoreCatalogBindingMigration.ID, "manifestHash", migration.manifestHash().canonicalText(),
            "planHash", planHash, "sourceBinding", migration.source().canonicalText(),
            "targetBinding", migration.target().canonicalText(), "items", results));
    }

    private void requireExactCoreCatalogRebindItems(CoreCatalogBindingMigration migration,
                                                    List<CoreCatalogRebindItem> items) {
        List<String> expected = migration.resources(serverId).stream()
            .map(ServerResourceLocator::canonicalText).sorted().toList();
        List<String> actual = items.stream().map(item -> item.resource().canonicalText()).sorted().toList();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Core catalog rebind item set does not match the frozen incidents");
        }
    }

    private void blockCoreCatalogRebind(String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE core_catalog_rebind_run SET status = 'BLOCKED', report_hash = NULL, updated_at = ?
            WHERE migration_id = ? AND status = 'RUNNING'
            """)) {
            statement.setLong(1, Instant.now().toEpochMilli());
            statement.setString(2, CoreCatalogBindingMigration.ID);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Core catalog rebind run was not durably blocked: " + reason);
            }
        }
        blockRecovery(reason);
    }

    private void recoverCoreRevisionSkews() throws SQLException {
        if (!coreAuthority.available() || recoveryBlocked) {
            return;
        }
        long started = TemporaryLifecycleDiagnostics.start();
        List<State> states = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM resource_mutation_state
            WHERE deleted = 0 AND payload IS NULL AND asset_hash IS NOT NULL
                AND core_payload_hash IS NOT NULL AND core_payload_kind IS NOT NULL
            ORDER BY resource
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                ServerResourceLocator resource = locator(result.getString("resource"));
                if (isCoreResource(resource)) {
                    states.add(readState(result, resource));
                }
            }
        }
        int repaired = 0;
        for (State source : states) {
            CoreState coordinated = null;
            try {
                coordinated = coreAuthority.load(source.resource()).map(this::coreState).orElse(null);
            } catch (IllegalStateException invalid) {
                coordinated = null;
            }
            if (coordinated != null && sameCoreState(source, corePersistedState(coordinated))) {
                continue;
            }
            MutationRow sourceReceipt = mutation(source.mutationId());
            requireCoreRevisionRepairSourceReceipt(source, sourceReceipt);
            UUID repairMutationId = coreRevisionRepairMutationId(source);
            if (coordinated != null) {
                requireCoreRevisionRepairResult(source, coordinated, repairMutationId);
            }
            MutationRow collision = mutation(repairMutationId);
            if (collision != null) {
                throw new IllegalStateException("Core revision repair mutation ID is already registered");
            }
            CoreGraphResourceAuthority.CoreRevisionRepairSource repairSource =
                new CoreGraphResourceAuthority.CoreRevisionRepairSource(source.revision(), source.mutationId(),
                    new ContentHash(source.assetHash()), new ContentHash(source.corePayloadHash()),
                    source.corePayloadKind(), source.activationState());
            Optional<CoreGraphResourceAuthority.CoreRevisionRepairResult> recovery = coreAuthority.repairRevisionSkew(
                source.resource(), repairSource, repairMutationId);
            if (recovery.isEmpty()) {
                continue;
            }
            CoreState repairedState = coreState(recovery.orElseThrow().repaired());
            requireCoreRevisionRepairResult(source, repairedState, repairMutationId);
            commitCoreRevisionRepair(source, repairedState, repairMutationId);
            repaired++;
        }
        TemporaryLifecycleDiagnostics.event("core_revision_repair", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, null, null, null,
                null, null, authorityEpoch.current(), null), "outcome", "complete", "inspectedCount", states.size(),
                "repairedCount", repaired));
    }

    private void requireCoreRevisionRepairSourceReceipt(State source, MutationRow receipt) {
        if (receipt == null || receipt.status() != Status.APPLIED || receipt.sequence() < 1L
            || !source.resource().canonicalText().equals(receipt.responseResource())
            || receipt.resultRevision() != source.revision()
            || !source.mutationId().toString().equals(receipt.resultMutationId())
            || !source.payloadHash().equals(receipt.resultHash()) || receipt.resultDeleted()
            || receipt.resultActivationState() != source.activationState()
            || !Objects.equals(source.assetHash(), receipt.resultAssetHash())
            || !Objects.equals(source.corePayloadHash(), receipt.resultCorePayloadHash())
            || !Objects.equals(source.corePayloadKind(), receipt.resultCorePayloadKind())) {
            throw new IllegalStateException("Core revision repair source is not bound to its exact applied receipt: "
                + source.resource().canonicalText());
        }
    }

    private UUID coreRevisionRepairMutationId(State source) {
        String seed = String.join("\n", serverId.canonicalText(), source.resource().canonicalText(),
            Long.toString(source.revision()), source.mutationId().toString(), source.payloadHash(), source.assetHash(),
            source.corePayloadHash(), source.corePayloadKind(), source.activationState().wireName());
        return IdentityCodec.deterministicUuid("core-revision-skew-repair-v1", seed);
    }

    private void requireCoreRevisionRepairResult(State source, CoreState repaired, UUID repairMutationId) {
        if (!source.resource().equals(repaired.resource())
            || repaired.revision() != Math.addExact(source.revision(), 1L)
            || !repairMutationId.equals(repaired.mutationId()) || repaired.deleted()
            || repaired.activationState() != source.activationState()
            || !source.corePayloadKind().equals(repaired.corePayloadKind())) {
            throw new IllegalStateException("Core revision repair result does not preserve the authoritative payload");
        }
    }

    private void commitCoreRevisionRepair(State source, CoreState repaired, UUID repairMutationId) throws SQLException {
        State current = state(source.resource());
        if (!sameCoreState(source, current)) {
            throw new IllegalStateException("Core revision repair source changed before receipt commit");
        }
        long now = Instant.now().toEpochMilli();
        String fingerprint = StorageSafety.sha256(String.join("\n", "core-revision-skew-repair-v1",
            source.resource().canonicalText(), Long.toString(source.revision()), source.mutationId().toString(),
            source.payloadHash(), source.assetHash(), source.corePayloadHash(), repairMutationId.toString()));
        MutationRow row = new MutationRow(repairMutationId, CORE_REVISION_REPAIR_ACTOR, fingerprint, "SAVE",
            source.resource().canonicalText(), source.resource().canonicalText(), null,
            source.resource().canonicalText(), source.activationState(), source.revision(), source.payloadHash(),
            Status.APPLIED, repaired.revision(), repairMutationId.toString(), repaired.protocolHash(), false,
            repaired.activationState(), null, nextSequence(), "", "", now, now, source.assetHash(),
            source.corePayloadHash(), source.corePayloadKind(), repaired.assetHash(), repaired.corePayloadHash(),
            repaired.corePayloadKind());
        connection.setAutoCommit(false);
        try {
            writeState(corePersistedState(repaired));
            writeMutation(row, false);
            try (PreparedStatement marker = connection.prepareStatement("""
                UPDATE resource_mutation_receipt SET transition_published = 1
                WHERE mutation_id = ? AND status = 'APPLIED'
                """)) {
                marker.setString(1, repairMutationId.toString());
                if (marker.executeUpdate() != 1) {
                    throw new SQLException("Core revision repair receipt marker was not updated");
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            rollback();
            throw exception;
        } finally {
            resetAutoCommit();
        }
    }

    private boolean sameCoreState(State first, State second) {
        return first != null && second != null && first.resource().equals(second.resource())
            && first.revision() == second.revision() && first.mutationId().equals(second.mutationId())
            && first.deleted() == second.deleted() && first.activationState() == second.activationState()
            && Objects.equals(first.payloadHash(), second.payloadHash())
            && Objects.equals(first.assetHash(), second.assetHash())
            && Objects.equals(first.corePayloadHash(), second.corePayloadHash())
            && Objects.equals(first.corePayloadKind(), second.corePayloadKind())
            && Objects.equals(first.payload(), second.payload());
    }

    private Set<ServerResourceLocator> recoverCommittedCoreTransitions() throws SQLException {
        long started = TemporaryLifecycleDiagnostics.start();
        List<StoredCoreTransition> unpublished = new ArrayList<>();
        Set<ServerResourceLocator> blocked = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM resource_mutation_receipt
            WHERE status = 'APPLIED' AND transition_published = 0 AND sequence > 0
                AND result_asset_hash IS NOT NULL AND result_core_payload_hash IS NOT NULL
                AND transition_envelope IS NOT NULL AND transition_hash IS NOT NULL
            ORDER BY sequence, created_at, mutation_id
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                unpublished.add(new StoredCoreTransition(readMutation(result), result.getString("transition_envelope"),
                    new ContentHash(result.getString("transition_hash"))));
            }
        }
        for (StoredCoreTransition stored : unpublished) {
            verifyEvolutionReceipt(stored.row(), false);
            CoreResourceMutationTransition transition = storedCoreTransition(stored);
            if (blocked.contains(transition.locator())) {
                continue;
            }
            CoreResourceMutationBus.Publication publication = publishCoreTransition(stored.row(), transition, true);
            if (publication == CoreResourceMutationBus.Publication.FAILED
                || publication == CoreResourceMutationBus.Publication.STALE) {
                blocked.add(transition.locator());
            }
        }
        Set<ServerResourceLocator> result = Set.copyOf(blocked);
        TemporaryLifecycleDiagnostics.event("committed_transition_recovery", started,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId, null, null, null,
                null, null, authorityEpoch.current(), null), "outcome", "complete", "unpublishedCount", unpublished.size(),
                "blockedCount", result.size()));
        return result;
    }

    private void restoreCommittedCoreTransitionHighWater() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT *
            FROM resource_mutation_receipt
            WHERE status = 'APPLIED' AND transition_published = 1 AND sequence > 0
                AND transition_hash IS NOT NULL
            ORDER BY sequence, created_at, mutation_id
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                verifyEvolutionReceipt(readMutation(result), false);
                boolean deleted = result.getInt("result_deleted") != 0;
                CoreResourceMutationCheckpoint checkpoint = new CoreResourceMutationCheckpoint(
                    locator(result.getString("response_resource")), result.getLong("result_revision"),
                    UUID.fromString(result.getString("result_mutation_id")), deleted,
                    deleted ? null : parseActivationState(result.getString("result_activation_state")),
                    normalizeActor(result.getString("actor_id")),
                    new ContentHash(result.getString("transition_hash")));
                registry.restoreCoreMutationHighWater(checkpoint);
            }
        }
    }

    private CoreResourceMutationTransition storedCoreTransition(StoredCoreTransition stored) {
        MutationRow row = stored.row();
        boolean deleted = row.resultDeleted();
        CoreResourceMutationTransition transition = new CoreResourceMutationTransition(
            locator(row.responseResource()), row.resultRevision(), UUID.fromString(row.resultMutationId()), deleted,
            deleted ? null : row.resultActivationState(), row.actorId(), stored.canonicalEnvelope());
        if (!transition.checkpoint().canonicalEnvelopeHash().equals(stored.canonicalEnvelopeHash())) {
            throw new IllegalStateException("Stored Core resource transition hash does not match its canonical envelope");
        }
        return transition;
    }

    private ProtocolEnvelopeDispatchResult reconcilePending(ProtocolEnvelope<Map<String, Object>> envelope, Command command,
                                                              MutationRow row, FlowResourceMutationLease lease) {
        try {
            if (isCoreResource(command.responseResource())) {
                recoverCore(row, lease);
            } else {
                recover(row, lease);
            }
        } catch (RuntimeException exception) {
            blockRecovery("Pending resource mutation " + row.mutationId() + " cannot be reconciled: " + safeMessage(exception));
            return unavailable(ProtocolRejectionCode.RESOURCE_RECOVERY_BLOCKED,
                "Resource mutation recovery is blocked");
        }
        MutationRow reconciled = mutation(row.mutationId());
        if (reconciled == null) {
            blockRecovery("Pending resource mutation " + row.mutationId() + " has no durable receipt after recovery");
            return unavailable(ProtocolRejectionCode.RESOURCE_RECOVERY_BLOCKED,
                "Resource mutation recovery is blocked");
        }
        return storedResponse(envelope, command.kind(), reconciled);
    }

    private ProtocolEnvelopeDispatchResult mutateCore(ProtocolEnvelope<Map<String, Object>> envelope, Command command,
                                                       String clientId, MutationRow previous,
                                                       String requestFingerprint) {
        if (!coreAuthority.available()) {
            return unavailable(ProtocolRejectionCode.RESOURCE_DURABILITY_UNAVAILABLE,
                "Core graph resource authority is unavailable");
        }
        List<FlowResourceKey> keys = mutationKeys(command);
        if (keys.isEmpty() || hasDuplicateKeys(keys)) {
            return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_FAILED,
                "The Core graph mutation key set must contain unique typed resource keys");
        }
        Optional<FlowResourceMutationLease> acquired = registry.mutationAdmission().tryAcquire(keys,
            command.mutationId().toString(), FlowResourceMutationAdmission.AdmissionKind.NEW);
        if (acquired.isEmpty()) {
            TemporaryLifecycleDiagnostics.event("mutation_admission", 0L,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(command, envelope, null), "outcome", "busy", "keyCount", keys.size()));
            return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                "The resource mutation is already admitted");
        }
        TemporaryLifecycleDiagnostics.event("mutation_admission", 0L,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity(command, envelope, null), "outcome", "acquired", "keyCount", keys.size()));
        FlowResourceMutationLease lease = acquired.get();
        try {
            if (previous != null && previous.fingerprint().equals(requestFingerprint)) {
                if (previous.status() == Status.PENDING) {
                    return reconcilePending(envelope, command, previous, lease);
                }
                return storedResponse(envelope, command.kind(), previous);
            }
            if (hasOtherPendingMutation(command)) {
                return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                    "An earlier mutation for this resource is awaiting durable recovery");
            }
            if (adapter(projectMetadataResource()) != null) {
                synchronize(projectMetadataResource());
            }
            CoreState current = synchronizeCore(command.responseResource());
            CoreOutcome outcome = coreOutcome(command, current);
            if (legacyInactive(envelope, outcome.activationState(), outcome.deleted())) {
                return rejectInactiveLegacyResource();
            }
            if (outcome.status() != Status.PENDING) {
                MutationRow terminal = insertCoreTerminal(command, clientId, requestFingerprint, outcome);
                return storedResponse(envelope, command.kind(), terminal);
            }
            MutationRow pending = insertCorePending(command, clientId, requestFingerprint, outcome);
            try {
                applyCore(command, outcome);
            } catch (CoreGraphMutationValidationException rejection) {
                MutationRow rejected = finishCoreValidationRejected(pending, rejection);
                return storedResponse(envelope, command.kind(), rejected);
            } catch (RuntimeException exception) {
                return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                    "Resource mutation is awaiting durable recovery");
            }
            try {
                CoreState after = coreAppliedState(command, outcome);
                verifyCorePostApply(command, outcome, after);
                MutationRow committed = commitCoreApplied(pending, after);
                return storedResponse(envelope, command.kind(), committed);
            } catch (RuntimeException exception) {
                blockRecovery("Core graph mutation " + pending.mutationId()
                    + " cannot be committed: " + safeMessage(exception));
                return unavailable(ProtocolRejectionCode.RESOURCE_RECOVERY_BLOCKED,
                    "Resource mutation recovery is blocked");
            }
        } finally {
            lease.close();
        }
    }

    private CoreOutcome coreOutcome(Command command, CoreState current) {
        return switch (command.operationName()) {
            case "CREATE" -> current != null && !current.deleted()
                ? coreTerminal(Status.CONFLICT, current)
                : corePending(command, current, coreState(decodeCorePayload(command, current == null ? 0L : current.revision())));
            case "SAVE" -> current == null || current.deleted()
                ? coreTerminal(Status.NOT_FOUND, current)
                : current.revision() != command.expectedRevision()
                    ? coreTerminal(Status.REVISION_CONFLICT, current)
                    : corePending(command, current, coreState(decodeCorePayload(command, current.revision())));
            case "DELETE" -> current == null || current.deleted()
                ? coreTerminal(Status.NOT_FOUND, current)
                : current.revision() != command.expectedRevision()
                    ? coreTerminal(Status.REVISION_CONFLICT, current)
                    : coreDeletePending(command, current);
            case "ACTIVATE" -> current == null || current.deleted()
                ? coreTerminal(Status.NOT_FOUND, current)
                : current.revision() != command.expectedRevision()
                    ? coreTerminal(Status.REVISION_CONFLICT, current)
                    : current.activationState() == command.activationState()
                        ? coreTerminal(Status.APPLIED, current)
                        : coreActivatePending(command, current);
            case "DUPLICATE" -> coreDuplicateOutcome(command, current);
            default -> throw new IllegalArgumentException("Core graph operation is not supported by the durable authority");
        };
    }

    private CoreOutcome corePending(Command command, CoreState precondition, CoreState desired) {
        if (desired == null || desired.deleted()) {
            throw new IllegalStateException("A Core graph mutation requires a live result");
        }
        return new CoreOutcome(Status.PENDING, precondition, desired, desired.protocolHash(), desired.assetHash(),
            desired.corePayloadHash(), desired.corePayloadKind(), false, desired.revision(), desired.mutationId(),
            desired.activationState(), desired.payload());
    }

    private CoreOutcome coreDeletePending(Command command, CoreState current) {
        CoreState desired = new CoreState(command.responseResource(), resultRevision(current.revision()), command.mutationId(),
            current.protocolHash(), current.assetHash(), current.corePayloadHash(), current.corePayloadKind(), true,
            null, null, null);
        return new CoreOutcome(Status.PENDING, current, desired, desired.protocolHash(), desired.assetHash(),
            desired.corePayloadHash(), desired.corePayloadKind(), true, desired.revision(), desired.mutationId(), null, null);
    }

    private CoreOutcome coreDuplicateOutcome(Command command, CoreState current) {
        CoreState source = synchronizeCore(command.source());
        if (source == null || source.deleted()) {
            return coreTerminal(Status.NOT_FOUND, source);
        }
        if (source.revision() != command.expectedRevision()) {
            return coreTerminal(Status.REVISION_CONFLICT, source);
        }
        if (current != null) {
            return coreTerminal(Status.CONFLICT, current);
        }
        return corePending(command, source, duplicateCoreState(command, source));
    }

    private CoreState duplicateCoreState(Command command, CoreState source) {
        CoreGraphStorageBoundary.Decoded decoded = source.decoded();
        if (decoded == null) {
            decoded = coreAuthority.load(command.source()).orElseThrow(() ->
                new IllegalStateException("The duplicate source Core graph is unavailable"));
        }
        ResourceActivationState activation = decoded.envelope().assetActivationState();
        if (activation == null) {
            activation = ResourceActivationState.ACTIVE;
        }
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(
            command.responseResource().resourceType().value(), 1L, command.mutationId(), activation);
        CoreGraphStorageBoundary.Decoded duplicated;
        if (decoded.graphDocument() != null) {
            GraphDocument graph = rebaseGraphResource(decoded.graphDocument(), command.responseResource(), 1L);
            duplicated = coreBoundary.decode(coreBoundary.encode(graph, metadata, command.responseResource()),
                command.responseResource());
        } else {
            FunctionSourceDocument function = rebaseFunctionResource(decoded.functionSourceDocument(),
                command.responseResource(), 1L);
            duplicated = coreBoundary.decode(coreBoundary.encode(function, metadata, command.responseResource()),
                command.responseResource());
        }
        coreAuthority.validateSave(command.responseResource(), duplicated);
        CoreState desired = coreState(duplicated);
        if (desired.revision() != 1L || !command.mutationId().equals(desired.mutationId())
            || !command.responseResource().equals(desired.resource())) {
            throw new IllegalStateException("The duplicated Core graph did not preserve the requested target identity");
        }
        return desired;
    }

    private CoreOutcome coreActivatePending(Command command, CoreState current) {
        CoreGraphStorageBoundary.Decoded projected = projectCoreActivation(command.responseResource(), current,
            command.activationState(), command.mutationId(), resultRevision(current.revision()));
        CoreState desired = coreState(projected);
        return corePending(command, current, desired);
    }

    private CoreOutcome coreTerminal(Status status, CoreState current) {
        return new CoreOutcome(status, null, current, current == null ? null : current.protocolHash(),
            current == null ? null : current.assetHash(), current == null ? null : current.corePayloadHash(),
            current == null ? null : current.corePayloadKind(), current != null && current.deleted(),
            current == null ? 0L : current.revision(), current == null ? null : current.mutationId(),
            current == null ? null : current.activationState(), null);
    }

    private CoreGraphStorageBoundary.Decoded decodeCorePayload(Command command, long currentRevision) {
        if (command.payload() == null || command.payloadHash() == null) {
            throw new IllegalArgumentException("Core graph payload is required");
        }
        CanonicalPayload<Map<String, Object>> canonical = payloadCodec.canonicalize(command.payload());
        if (!command.payloadHash().equals(canonical.checksum())) {
            throw new IllegalArgumentException("Core graph protocol payload checksum does not match its canonical payload");
        }
        CoreGraphStorageBoundary.Decoded decoded = coreBoundary.decode(CanonicalJson.canonicalBytes(canonical.value()),
            command.responseResource());
        long expectedRevision = resultRevision(currentRevision);
        if (decoded.envelope().assetRevision() != expectedRevision
            || !command.mutationId().toString().equals(decoded.envelope().assetMutationId())) {
            throw new IllegalArgumentException("Core graph envelope revision or mutation ID does not match the request");
        }
        if (!command.responseResource().resourceType().value().equals(decoded.envelope().resourceType())) {
            throw new IllegalArgumentException("Core graph envelope resource type does not match the locator");
        }
        coreAuthority.validateSave(command.responseResource(), decoded);
        return decoded;
    }

    private CoreGraphStorageBoundary.Decoded projectCoreActivation(ServerResourceLocator resource, CoreState current,
                                                                     ResourceActivationState activationState, UUID mutationId,
                                                                     long revision) {
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(
            resource.resourceType().value(), revision, mutationId, activationState);
        if (current.decoded().graphDocument() != null) {
            GraphDocument graph = rebaseGraphRevision(current.decoded().graphDocument(), revision);
            return coreBoundary.decode(coreBoundary.encode(graph, metadata, resource), resource);
        }
        FunctionSourceDocument source = rebaseFunctionRevision(current.decoded().functionSourceDocument(), revision);
        return coreBoundary.decode(coreBoundary.encode(source, metadata, resource), resource);
    }

    private Command normalizeAggregateCoreCreate(Command command, State current) {
        if (!"CREATE".equals(command.operationName()) || command.presentation() == null
            || !isCoreResource(command.responseResource()) || current != null && !current.deleted()) {
            return command;
        }
        CanonicalPayload<Map<String, Object>> submitted = payloadCodec.canonicalize(command.payload());
        if (!submitted.checksum().equals(command.payloadHash())) {
            throw new IllegalArgumentException("Core graph protocol payload checksum does not match its canonical payload");
        }
        CoreGraphStorageBoundary.Decoded decoded = coreBoundary.decode(CanonicalJson.canonicalBytes(submitted.value()),
            command.responseResource());
        if (!command.mutationId().toString().equals(decoded.envelope().assetMutationId())) {
            throw new IllegalArgumentException("Core graph envelope mutation ID does not match the request");
        }
        long revision = resultRevision(current == null ? 0L : current.revision());
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(
            command.responseResource().resourceType().value(), revision, command.mutationId(),
            decoded.envelope().assetActivationState());
        CoreGraphStorageBoundary.Decoded rebased;
        if (decoded.graphDocument() != null) {
            rebased = coreBoundary.decode(coreBoundary.encode(rebaseGraphRevision(decoded.graphDocument(), revision), metadata,
                command.responseResource()), command.responseResource());
        } else {
            rebased = coreBoundary.decode(coreBoundary.encode(rebaseFunctionRevision(decoded.functionSourceDocument(), revision),
                metadata, command.responseResource()), command.responseResource());
        }
        CanonicalPayload<Map<String, Object>> canonical = payloadCodec.canonicalize(corePayload(rebased));
        return new Command(command.operationName(), command.resource(), command.source(), command.responseResource(),
            command.mutationId(), command.expectedRevision(), canonical.value(), canonical.checksum(),
            command.activationState(), command.presentation());
    }

    private GraphDocument rebaseGraphRevision(GraphDocument graph, long revision) {
        return rebaseGraphResource(graph, graph.resource(), revision);
    }

    private GraphDocument rebaseGraphResource(GraphDocument graph, ServerResourceLocator resource, long revision) {
        return new GraphDocument(graph.schemaVersion(), resource, revision, graph.catalogBinding(),
            graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.passthroughs(), graph.variables(), graph.functions(),
            graph.unknown());
    }

    private FunctionSourceDocument rebaseFunctionRevision(FunctionSourceDocument source, long revision) {
        return rebaseFunctionResource(source, source.graph().resource(), revision);
    }

    private FunctionSourceDocument rebaseFunctionResource(FunctionSourceDocument source, ServerResourceLocator resource,
                                                          long revision) {
        GraphDocument graph = rebaseGraphResource(source.graph(), resource, revision);
        FunctionSignature signature = source.signature();
        FunctionSignature rebased = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(revision),
            signature.inputs(), signature.outputs(), signature.unknown());
        return new FunctionSourceDocument(rebased, graph, source.unknown());
    }

    private void applyCore(Command command, CoreOutcome outcome) {
        ServerResourceLocator resource = command.responseResource();
        switch (command.operationName()) {
            case "CREATE", "SAVE", "DUPLICATE" -> coreAuthority.save(resource, CanonicalJson.canonicalBytes(outcome.payload()),
                command.mutationId(), outcome.revision() - 1L, new ContentHash(outcome.assetHash()));
            case "DELETE" -> {
                CoreGraphStorageBoundary.CoreGraphTombstone tombstone = coreAuthority.delete(resource,
                    command.mutationId(), command.expectedRevision(), new ContentHash(outcome.assetHash()));
                verifyCoreTombstone(tombstone, outcome);
            }
            case "ACTIVATE" -> coreAuthority.activate(resource, command.activationState(), command.mutationId(),
                command.expectedRevision(), new ContentHash(outcome.precondition().assetHash()));
            default -> throw new IllegalArgumentException("Core graph operation is not supported by the durable authority");
        }
    }

    private CoreState coreAppliedState(Command command, CoreOutcome outcome) {
        if ("DELETE".equals(command.operationName())) {
            return outcome.desired();
        }
        return coreAuthority.load(command.responseResource()).map(this::coreState)
            .orElseThrow(() -> new IllegalStateException("The Core graph mutation did not publish its resource"));
    }

    private void verifyCoreTombstone(CoreGraphStorageBoundary.CoreGraphTombstone tombstone, CoreOutcome outcome) {
        if (!outcome.resource().equals(tombstone.resource()) || tombstone.revision() != outcome.revision()
            || !outcome.mutationId().equals(tombstone.mutationId()) || !tombstone.deleted()
            || !new ContentHash(outcome.corePayloadHash()).equals(tombstone.priorPayloadHash())) {
            throw new IllegalStateException("Core graph tombstone does not match the durable mutation outcome");
        }
    }

    private void verifyCorePostApply(Command command, CoreOutcome outcome, CoreState after) {
        if (after == null || !outcome.resource().equals(after.resource()) || after.revision() != outcome.revision()
            || !outcome.mutationId().equals(after.mutationId())
            || !Objects.equals(outcome.protocolHash(), after.protocolHash())
            || !Objects.equals(outcome.assetHash(), after.assetHash())
            || !Objects.equals(outcome.corePayloadHash(), after.corePayloadHash())
            || !Objects.equals(outcome.corePayloadKind(), after.corePayloadKind())
            || outcome.deleted() != after.deleted()
            || !Objects.equals(outcome.activationState(), after.activationState())) {
            throw new IllegalStateException("Core graph mutation result does not match the requested outcome");
        }
    }

    private void recoverCore(MutationRow row, FlowResourceMutationLease lease) {
        if (!coreAuthority.available()) {
            throw new IllegalStateException("Core graph resource authority is unavailable");
        }
        if (legacyAdminRecoveryRow(row)) {
            recoverLegacyAdminCore(row);
            return;
        }
        verifyEvolutionReceipt(row, true);
        Command command = command(row);
        CoreState precondition = validateStoredCorePrecondition(command, row);
        CoreState externallyApplied = externallyAppliedCore(command, row, precondition);
        if (externallyApplied != null) {
            verifyCorePostApply(command, coreOutcome(row, precondition), externallyApplied);
            commitCoreApplied(row, externallyApplied);
            return;
        }
        verifyCoreExternalPrecondition(command, precondition);
        CoreOutcome outcome = coreOutcome(row, precondition);
        try {
            if (compatibleCatalogActor(row)) {
                CoreGraphStorageBoundary.Decoded candidate = coreBoundary.decode(CanonicalJson.canonicalBytes(outcome.payload()), command.responseResource());
                saveCatalogProjection(row, candidate);
            } else {
                applyCore(command, outcome);
            }
        } catch (CoreGraphMutationValidationException rejection) {
            if (CoreCatalogEvolution.isActor(row.actorId()) || compatibleCatalogActor(row)) {
                throw rejection;
            }
            finishCoreValidationRejected(row, rejection);
            return;
        }
        CoreState after = coreAppliedState(command, outcome);
        verifyCorePostApply(command, outcome, after);
        commitCoreApplied(row, after);
    }

    private MutationRow finishCoreValidationRejected(MutationRow pending,
                                                       CoreGraphMutationValidationException rejection) {
        MutationRow rejected = finishRejected(pending, ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.legacyValue(),
            rejection.actionableMessage());
        TemporaryLifecycleDiagnostics.event("sqlite_mutation_terminal", 0L,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity(pending), "operation", pending.operation(),
                "outcome", rejected.status(), "receiptPersisted", true, "responsePrepared", true,
                "resultRevision", rejected.resultRevision(), "failure", rejection.getClass().getSimpleName()));
        return rejected;
    }

    private CoreState externallyAppliedCore(Command command, MutationRow row, CoreState precondition) {
        if ("DELETE".equals(command.operationName())) {
            if (coreAuthority.load(command.responseResource()).isPresent()) {
                return null;
            }
            CoreGraphStorageBoundary.CoreGraphTombstone tombstone = coreAuthority.delete(command.responseResource(),
                row.mutationId(), row.expectedRevision(), new ContentHash(precondition.assetHash()));
            CoreOutcome outcome = coreOutcome(row, precondition);
            verifyCoreTombstone(tombstone, outcome);
            CoreState deleted = outcome.desired();
            return matchesCoreOutcome(deleted, row) ? deleted : null;
        }
        Optional<CoreGraphStorageBoundary.Decoded> loaded = coreAuthority.load(command.responseResource());
        if (loaded.isEmpty()) {
            return null;
        }
        CoreState state = coreState(loaded.get());
        return matchesCoreOutcome(state, row) ? state : null;
    }

    private void verifyCoreExternalPrecondition(Command command, CoreState precondition) {
        if ("CREATE".equals(command.operationName()) || "DUPLICATE".equals(command.operationName())) {
            if (coreAuthority.load(command.responseResource()).isPresent()) {
                throw new IllegalStateException("The Core graph mutation target changed while pending");
            }
            if ("DUPLICATE".equals(command.operationName()) && !sameCoreState(synchronizeCore(command.source()), precondition)) {
                throw new IllegalStateException("The duplicate source Core graph changed while pending");
            }
            return;
        }
        CoreState current = synchronizeCore(command.responseResource());
        if (!sameCoreState(current, precondition)) {
            throw new IllegalStateException("The Core graph resource changed while pending");
        }
    }

    private CoreState validateStoredCorePrecondition(Command command, MutationRow row) {
        State persistedState = state(command.responseResource());
        CoreState current = persistedState == null ? null : coreState(persistedState);
        if ("CREATE".equals(command.operationName())) {
            if (current != null && !current.deleted() || row.expectedRevision() != 0
                || !Objects.equals(current == null ? "" : current.protocolHash(), row.preconditionHash())) {
                throw new IllegalStateException("The durable Core graph create precondition is invalid");
            }
        } else if ("DUPLICATE".equals(command.operationName())) {
            if (current != null) {
                throw new IllegalStateException("The durable Core graph duplicate target already has authoritative state");
            }
            CoreState source = synchronizeCore(command.source());
            if (source == null || source.deleted() || source.assetHash() == null || source.assetHash().isBlank()
                || source.corePayloadHash() == null || source.corePayloadHash().isBlank()
                || source.corePayloadKind() == null || source.corePayloadKind().isBlank()
                || source.revision() != row.expectedRevision()
                || !Objects.equals(source.protocolHash(), row.preconditionHash())
                || !Objects.equals(source.assetHash(), row.preconditionAssetHash())
                || !Objects.equals(source.corePayloadHash(), row.preconditionCorePayloadHash())
                || !Objects.equals(source.corePayloadKind(), row.preconditionCorePayloadKind())) {
                throw new IllegalStateException("The durable Core graph duplicate source precondition no longer matches");
            }
            current = source;
        } else if (current == null || current.deleted() || current.payload() != null || current.assetHash() == null
            || current.assetHash().isBlank() || current.corePayloadHash() == null || current.corePayloadHash().isBlank()
            || current.corePayloadKind() == null || current.corePayloadKind().isBlank()
            || current.revision() != row.expectedRevision()
            || !Objects.equals(current.protocolHash(), row.preconditionHash())
            || !Objects.equals(current.assetHash(), row.preconditionAssetHash())
            || !Objects.equals(current.corePayloadHash(), row.preconditionCorePayloadHash())
            || !Objects.equals(current.corePayloadKind(), row.preconditionCorePayloadKind())) {
            throw new IllegalStateException("The durable Core graph mutation precondition no longer matches");
        }
        long expectedResultRevision = "DUPLICATE".equals(command.operationName()) ? 1L
            : "CREATE".equals(command.operationName())
            ? resultRevision(current == null ? 0L : current.revision()) : resultRevision(row.expectedRevision());
        if (row.resultRevision() != expectedResultRevision || row.resultHash() == null || row.resultHash().isBlank()
            || row.resultMutationId() == null || !row.mutationId().toString().equals(row.resultMutationId())
            || row.resultAssetHash() == null || row.resultAssetHash().isBlank()
            || row.resultCorePayloadHash() == null || row.resultCorePayloadHash().isBlank()
            || row.resultCorePayloadKind() == null || row.resultCorePayloadKind().isBlank()) {
            throw new IllegalStateException("The durable Core graph mutation result metadata is invalid");
        }
        return current;
    }

    private CoreOutcome coreOutcome(MutationRow row, CoreState precondition) {
        if (row.resultDeleted()) {
            CoreState desired = new CoreState(locator(row.responseResource()), row.resultRevision(), row.mutationId(),
                row.resultHash(), row.resultAssetHash(), row.resultCorePayloadHash(), row.resultCorePayloadKind(), true,
                null, null, null);
            return new CoreOutcome(Status.PENDING, precondition, desired, row.resultHash(), row.resultAssetHash(),
                row.resultCorePayloadHash(), row.resultCorePayloadKind(), true, row.resultRevision(), row.mutationId(),
                null, null);
        }
        if (row.resultPayload() == null || row.resultPayload().isBlank()) {
            throw new IllegalStateException("The pending Core graph request payload is missing");
        }
        CoreGraphStorageBoundary.Decoded decoded = coreBoundary.decode(CanonicalJson.canonicalBytes(corePayload(row.resultPayload())),
            locator(row.responseResource()));
        CoreState desired = coreState(decoded);
        if (!matchesCoreMetadata(desired, row)) {
            throw new IllegalStateException("The pending Core graph request metadata is invalid");
        }
        return new CoreOutcome(Status.PENDING, precondition, desired, row.resultHash(), row.resultAssetHash(),
            row.resultCorePayloadHash(), row.resultCorePayloadKind(), false, row.resultRevision(), row.mutationId(),
            row.resultActivationState(), corePayload(row.resultPayload()));
    }

    private boolean matchesCoreMetadata(CoreState state, MutationRow row) {
        return state.revision() == row.resultRevision() && state.mutationId().equals(row.mutationId())
            && state.protocolHash().equals(row.resultHash()) && state.assetHash().equals(row.resultAssetHash())
            && state.corePayloadHash().equals(row.resultCorePayloadHash())
            && state.corePayloadKind().equals(row.resultCorePayloadKind())
            && state.activationState() == row.resultActivationState();
    }

    private boolean matchesCoreOutcome(CoreState state, MutationRow row) {
        return state != null && state.resource().equals(locator(row.responseResource()))
            && state.revision() == row.resultRevision()
            && row.mutationId().equals(state.mutationId()) && state.protocolHash().equals(row.resultHash())
            && Objects.equals(state.assetHash(), row.resultAssetHash())
            && Objects.equals(state.corePayloadHash(), row.resultCorePayloadHash())
            && Objects.equals(state.corePayloadKind(), row.resultCorePayloadKind())
            && state.deleted() == row.resultDeleted()
            && Objects.equals(state.activationState(), row.resultActivationState());
    }

    private boolean sameCoreState(CoreState first, CoreState second) {
        return first != null && second != null && first.resource().equals(second.resource())
            && first.revision() == second.revision() && first.mutationId().equals(second.mutationId())
            && first.protocolHash().equals(second.protocolHash()) && first.assetHash().equals(second.assetHash())
            && first.corePayloadHash().equals(second.corePayloadHash())
            && first.corePayloadKind().equals(second.corePayloadKind()) && first.deleted() == second.deleted()
            && first.activationState() == second.activationState();
    }

    private CoreState synchronizeCore(ServerResourceLocator resource) {
        if (!coreAuthority.available()) {
            throw new IllegalStateException("Core graph resource authority is unavailable");
        }
        State persisted = state(resource);
        Optional<CoreGraphStorageBoundary.Decoded> loaded = coreAuthority.load(resource);
        if (loaded.isPresent()) {
            CoreState current = coreState(loaded.get());
            if (!resource.equals(current.resource())) {
                throw new IllegalStateException("The Core graph authority returned a different resource locator");
            }
            if (persisted == null) {
                bootstrapState(corePersistedState(current));
                return current;
            }
            verifyCorePersistedState(persisted, current);
            return current;
        }
        if (persisted == null) {
            return null;
        }
        if (!persisted.deleted() || persisted.payloadHash() == null || persisted.payloadHash().isBlank()
            || persisted.assetHash() == null || persisted.assetHash().isBlank()
            || persisted.corePayloadHash() == null || persisted.corePayloadHash().isBlank()
            || persisted.corePayloadKind() == null || persisted.corePayloadKind().isBlank() || persisted.payload() != null) {
            throw new IllegalStateException("The Core graph authority is missing a durable resource state");
        }
        return coreState(persisted);
    }

    private CoreState coreState(CoreGraphStorageBoundary.Decoded decoded) {
        Objects.requireNonNull(decoded, "Core graph payload is required");
        Object payload = decoded.payload();
        ServerResourceLocator resource = payload instanceof GraphDocument graph
            ? graph.resource() : ((FunctionSourceDocument) payload).graph().resource();
        if (!isCoreResource(resource)) {
            throw new IllegalStateException("The Core graph payload locator is not authoritative");
        }
        Map<String, Object> envelope = corePayload(decoded);
        CanonicalPayload<Map<String, Object>> canonical = payloadCodec.canonicalize(envelope);
        String corePayloadHash = payload instanceof GraphDocument graph
            ? graph.checksum().canonicalText()
            : ((FunctionSourceDocument) payload).checksum().canonicalText();
        return new CoreState(resource, decoded.envelope().assetRevision(),
            UUID.fromString(decoded.envelope().assetMutationId()), canonical.checksum().canonicalText(),
            decoded.envelope().assetHash().canonicalText(), corePayloadHash, decoded.corePayloadKind(), false,
            decoded.envelope().assetActivationState(), decoded, envelope);
    }

    private CoreState coreState(State persisted) {
        return new CoreState(persisted.resource(), persisted.revision(), persisted.mutationId(), persisted.payloadHash(),
            persisted.assetHash(), persisted.corePayloadHash(), persisted.corePayloadKind(), persisted.deleted(),
            persisted.activationState(), null, null);
    }

    private State corePersistedState(CoreState state) {
        return new State(state.resource(), state.revision(), state.mutationId(), state.protocolHash(), state.deleted(), null,
            state.activationState(), state.assetHash(), state.corePayloadHash(), state.corePayloadKind());
    }

    private void verifyCorePersistedState(State persisted, CoreState current) {
        if (persisted.deleted() || persisted.revision() != current.revision()
            || !persisted.mutationId().equals(current.mutationId())
            || !Objects.equals(persisted.payloadHash(), current.protocolHash())
            || !Objects.equals(persisted.assetHash(), current.assetHash())
            || !Objects.equals(persisted.corePayloadHash(), current.corePayloadHash())
            || !Objects.equals(persisted.corePayloadKind(), current.corePayloadKind())
            || persisted.activationState() != current.activationState() || persisted.payload() != null) {
            throw new IllegalStateException("The authoritative Core graph state differs from durable metadata");
        }
    }

    private Map<String, Object> corePayload(CoreGraphStorageBoundary.Decoded decoded) {
        Object parsed = CanonicalJson.parse(coreBoundary.encode(decoded));
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalStateException("The Core graph envelope is not a JSON object");
        }
        return stringMap(map);
    }

    private Map<String, Object> corePayload(String serialized) {
        Object parsed = CanonicalJson.parse(serialized);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalStateException("The pending Core graph envelope is not a JSON object");
        }
        return stringMap(map);
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException("Core graph envelope keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return payloadCodec.normalize(result);
    }

    private ResourceDocument<Map<String, Object>> document(CoreState state) {
        if (state == null) {
            return null;
        }
        if (state.deleted()) {
            return ResourceDocument.tombstone(state.resource(), state.revision(), state.mutationId(),
                new ContentHash(state.corePayloadHash()), "server");
        }
        return ResourceDocument.live(state.resource(), state.revision(), state.mutationId(),
            payloadCodec.canonicalize(state.payload()), state.activationState(), "server");
    }

    private MutationRow insertCorePending(Command command, String actorId, String fingerprint, CoreOutcome outcome) {
        long started = TemporaryLifecycleDiagnostics.start();
        long now = Instant.now().toEpochMilli();
        MutationRow row = coreRow(command, actorId, fingerprint, outcome, Status.PENDING,
            outcome.payload() == null ? null : payloadCodec.canonicalInput(outcome.payload()), 0L, "", "", now, now);
        writeMutation(row, false);
        TemporaryLifecycleDiagnostics.event("mutation_receipt_commit", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity(command, null, null), "receiptStatus", row.status(),
                "resultRevision", row.resultRevision(), "core", true));
        return row;
    }

    private MutationRow insertCoreTerminal(Command command, String actorId, String fingerprint, CoreOutcome outcome) {
        long started = TemporaryLifecycleDiagnostics.start();
        long now = Instant.now().toEpochMilli();
        MutationRow row = coreRow(command, actorId, fingerprint, outcome, outcome.status(), null, 0L,
            errorCode(outcome.status()), errorMessage(outcome.status()), now, now);
        writeMutation(row, false);
        TemporaryLifecycleDiagnostics.event("mutation_receipt_commit", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity(command, null, null), "receiptStatus", row.status(),
                "resultRevision", row.resultRevision(), "core", true));
        return row;
    }

    private MutationRow coreRow(Command command, String actorId, String fingerprint, CoreOutcome outcome,
                                Status status, String resultPayload, long sequence, String errorCode,
                                String errorMessage, long createdAt, long updatedAt) {
        CoreState precondition = outcome.precondition();
        return new MutationRow(command.mutationId(), actorId, fingerprint, command.operationName(),
            command.resource().canonicalText(), command.responseResource().canonicalText(),
            command.source() == null ? null : command.source().canonicalText(),
            command.target() == null ? null : command.target().canonicalText(), command.activationState(), command.expectedRevision(),
            precondition == null ? "" : precondition.protocolHash(), status, outcome.revision(),
            outcome.mutationId() == null ? command.mutationId().toString() : outcome.mutationId().toString(),
            outcome.protocolHash() == null ? "" : outcome.protocolHash(), outcome.deleted(), outcome.activationState(),
            resultPayload, sequence, errorCode, errorMessage, createdAt, updatedAt,
            precondition == null ? null : precondition.assetHash(), precondition == null ? null : precondition.corePayloadHash(),
            precondition == null ? null : precondition.corePayloadKind(), outcome.assetHash(), outcome.corePayloadHash(),
            outcome.corePayloadKind());
    }

    private MutationRow commitCoreApplied(MutationRow pending, CoreState state) {
        long started = TemporaryLifecycleDiagnostics.start();
        long sequence = nextSequence();
        CoreResourceMutationTransition transition = coreTransition(state, pending.actorId());
        State coupledProjectMetadata = coupledProjectMetadataState(pending);
        MutationRow row = new MutationRow(pending.mutationId(), pending.actorId(), pending.fingerprint(), pending.operation(),
            pending.requestedResource(), pending.responseResource(), pending.sourceResource(), pending.targetResource(),
            pending.targetActivationState(), pending.expectedRevision(), pending.preconditionHash(), Status.APPLIED,
            state.revision(), state.mutationId().toString(), state.protocolHash(), state.deleted(), state.activationState(), null,
            sequence, "", "", pending.createdAt(), Instant.now().toEpochMilli(), pending.preconditionAssetHash(),
            pending.preconditionCorePayloadHash(), pending.preconditionCorePayloadKind(), state.assetHash(),
            state.corePayloadHash(), state.corePayloadKind());
        try {
            connection.setAutoCommit(false);
            writeState(corePersistedState(state));
            if (coupledProjectMetadata != null) {
                writeState(coupledProjectMetadata);
            }
            writeMutation(row, true);
            writeCoreTransitionOutbox(row.mutationId(), transition);
            connection.commit();
            TemporaryLifecycleDiagnostics.event("storage_metadata_commit", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "committed", "core", true,
                    "metadataCoupled", coupledProjectMetadata != null, "sequence", sequence));
        } catch (SQLException exception) {
            rollback();
            TemporaryLifecycleDiagnostics.event("storage_metadata_commit", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "failed", "core", true,
                    "failure", exception.getClass().getSimpleName()));
            throw new IllegalStateException("Failed To Commit Core Graph Mutation", exception);
        } finally {
            resetAutoCommit();
        }
        publishCoreTransition(row, transition, false);
        return row;
    }

    private CoreResourceMutationBus.Publication publishCoreTransition(MutationRow row,
                                                                       CoreResourceMutationTransition transition,
                                                                       boolean recovery) {
        long started = TemporaryLifecycleDiagnostics.start();
        CoreResourceMutationBus.Publication publication = registry.publishCommittedCoreMutation(transition);
        TemporaryLifecycleDiagnostics.event("transition_dispatch", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", publication, "recovery", recovery));
        if (publication == CoreResourceMutationBus.Publication.FAILED
            || publication == CoreResourceMutationBus.Publication.STALE) {
            if (CoreCatalogEvolution.isActor(row.actorId()) || compatibleCatalogActor(row)) {
                blockRecovery("Core catalog evolution " + row.mutationId()
                    + " is awaiting committed transition publication");
            }
            TemporaryLifecycleDiagnostics.event("mutation_receipt_publication", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", publication,
                    "transitionPublished", false, "receiptPersisted", false, "recovery", recovery));
            return publication;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE resource_mutation_receipt SET transition_published = 1, transition_envelope = NULL
            WHERE mutation_id = ? AND status = 'APPLIED' AND transition_published = 0
            """)) {
            statement.setString(1, row.mutationId().toString());
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new SQLException("Committed Core resource transition marker was not updated");
            }
            TemporaryLifecycleDiagnostics.event("mutation_receipt_publication", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "marked",
                    "transitionPublished", true, "receiptPersisted", true, "recovery", recovery));
        } catch (SQLException exception) {
            if (recovery) {
                throw new IllegalStateException("Failed To Mark Recovered Core Resource Transition", exception);
            }
            blockRecovery("Core resource mutation " + row.mutationId()
                + " transition marker cannot be committed: " + exception.getMessage());
            TemporaryLifecycleDiagnostics.event("mutation_receipt_publication", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "failed",
                    "transitionPublished", false, "receiptPersisted", false, "recovery", recovery,
                    "failure", exception.getClass().getSimpleName()));
        }
        return publication;
    }

    private boolean compatibleCatalogActor(MutationRow row) {
        return CoreCatalogCompatibilityRebind.PROOF_ACTOR.equals(row.actorId());
    }

    private void writeCoreTransitionOutbox(UUID mutationId, CoreResourceMutationTransition transition) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE resource_mutation_receipt
            SET transition_envelope = ?, transition_hash = ?, transition_published = 0
            WHERE mutation_id = ? AND status = 'APPLIED'
            """)) {
            statement.setString(1, transition.canonicalEnvelope());
            statement.setString(2, transition.checkpoint().canonicalEnvelopeHash().canonicalText());
            statement.setString(3, mutationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Core resource transition outbox was not written");
            }
        }
    }

    private CoreResourceMutationTransition coreTransition(CoreState state, String author) {
        byte[] canonicalEnvelope = state.deleted()
            ? coreBoundary.encodeTombstone(state.resource(), state.revision(), state.mutationId(),
                new ContentHash(state.corePayloadHash()))
            : CanonicalJson.canonicalBytes(state.payload());
        return new CoreResourceMutationTransition(state.resource(), state.revision(), state.mutationId(),
            state.deleted(), state.activationState(), normalizeActor(author),
            new String(canonicalEnvelope, StandardCharsets.UTF_8));
    }

    private long resultRevision(long expectedRevision) {
        try {
            return Math.addExact(expectedRevision, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Expected resource revision is too large", exception);
        }
    }

    private void recover(MutationRow row, FlowResourceMutationLease lease) {
        Command command = command(row);
        State target = state(command.responseResource());
        if (("CREATE".equals(command.operationName()) || "DUPLICATE".equals(command.operationName()))
            && target != null && (!"CREATE".equals(command.operationName()) || !target.deleted())) {
            finishTerminal(row, new Outcome(Status.CONFLICT, command.responseResource(), hash(target),
                target.deleted(), payload(target), target, target.activationState()));
            return;
        }
        State precondition = validateStoredPrecondition(command, row);
        if (command.presentation() != null) {
            Outcome outcome = recoveryOutcome(command, row, precondition);
            AggregateCreateState aggregate;
            try {
                aggregate = applyAggregateCreate(command, outcome, row.actorId(), lease);
            } catch (AggregateResourceCreateStorage.PreCommitRejection rejection) {
                finishRejected(row, rejection.errorCode(), rejection.getMessage());
                TemporaryLifecycleDiagnostics.event("pending_receipt_recovery", 0L,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "rejected",
                        "failure", rejection.getClass().getSimpleName()));
                return;
            }
            verifyPostApplyPrecondition(command, row);
            publishAggregateCommitted(commitAggregateApplied(row, aggregate));
            return;
        }
        State externallyApplied = externallyApplied(command.responseResource(), row, precondition);
        if (externallyApplied != null) {
            completePostCommitRecovery(command.responseResource(), row.mutationId(), externallyApplied.revision(), externallyApplied.deleted());
            recoverCoupledProjectMetadataLineage(row, externallyApplied);
            verifyPostApplyPrecondition(command, row);
            commitApplied(row, externallyApplied);
            return;
        }
        verifyExternalPrecondition(command, row, precondition);
        Outcome outcome = recoveryOutcome(command, row, precondition);
        FlowOperationResult<?> result = applyExternal(command, outcome, row.actorId(), lease);
        if (!result.success()) {
            if (!mayHaveAppliedExternally(result)) {
                finishRejected(row, result.errorCode(), result.message());
            }
            return;
        }
        State after = synchronizeExternal(command.responseResource(), row.mutationId(), outcome.resultRevision(), outcome.resultHash(),
            outcome.resultDeleted(), outcome.resultPayload(), outcome.resultActivationState());
        completePostCommitRecovery(command.responseResource(), row.mutationId(), outcome.resultRevision(), outcome.resultDeleted());
        verifyPostApplyPrecondition(command, row);
        commitApplied(row, after);
    }

    private State externallyApplied(ServerResourceLocator resource, MutationRow row, State precondition) {
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            throw new IllegalStateException("Resource adapter is unavailable: " + resource.resourceType().value());
        }
        FlowResourceMutationStamp stamp = readStamp(resource, adapter);
        if (stamp == null || !row.mutationId().equals(stamp.mutationId()) || row.resultRevision() != stamp.revision()
            || row.resultDeleted() != stamp.deleted()) {
            return null;
        }
        if (stamp.deleted()) {
            if (row.resultActivationState() != null || row.resultHash() == null || !row.resultHash().equals(stamp.payloadHash())) {
                return null;
            }
            State state = new State(resource, stamp.revision(), stamp.mutationId(), stamp.payloadHash(), true, null, null);
            return matchesOutcome(state, row) ? state : null;
        }
        Object value = adapter.get(resource.id());
        if (value == null) {
            return null;
        }
        CanonicalPayload<Map<String, Object>> canonical = canonical(adapter.serialize(value));
        if (!canonical.checksum().canonicalText().equals(stamp.payloadHash())) {
            return null;
        }
        ResourceActivationState currentActivation = activationState(canonical.value(), ResourceActivationState.ACTIVE);
        if (row.resultActivationState() != null && currentActivation != row.resultActivationState()) {
            return null;
        }
        State state = new State(resource, stamp.revision(), stamp.mutationId(), stamp.payloadHash(), false, canonicalInput(canonical), currentActivation);
        return matchesOutcome(state, row) || matchesCommittedNormalization(adapter, precondition, state, row) ? state : null;
    }

    private boolean matchesCommittedNormalization(FlowResourceAdapter<Object> adapter, State precondition,
                                                  State state, MutationRow row) {
        if (adapter == null || precondition == null || precondition.payload() == null || state == null
            || state.deleted() || row.resultDeleted() || row.resultPayload() == null
            || row.resultHash() == null || row.resultHash().isBlank() || state.payload() == null) {
            return false;
        }
        Object previous = adapter.deserialize(precondition.payload());
        Object requested = adapter.deserialize(row.resultPayload());
        Object actual = adapter.deserialize(state.payload());
        return adapter.matchesCommittedPayloadRecovery(previous, requested, actual);
    }

    private State validateStoredPrecondition(Command command, MutationRow row) {
        if ("ACTIVATE".equals(command.operationName()) && command.activationState() == null) {
            throw new IllegalStateException("The durable activation target is missing");
        }
        boolean targetAbsent = "CREATE".equals(command.operationName()) || "DUPLICATE".equals(command.operationName());
        State response = state(command.responseResource());
        if (targetAbsent && response != null && (!"CREATE".equals(command.operationName()) || !response.deleted())) {
            throw new IllegalStateException("The durable mutation target already has authoritative state");
        }
        State precondition = "DUPLICATE".equals(command.operationName()) ? state(command.source()) : response;
        if ("CREATE".equals(command.operationName())) {
            if (row.expectedRevision() != 0
                || !Objects.equals(precondition == null ? "" : precondition.payloadHash(), row.preconditionHash())) {
                throw new IllegalStateException("The durable create precondition is invalid");
            }
        } else {
            if (precondition == null || precondition.deleted() || precondition.revision() != row.expectedRevision()
                || !Objects.equals(precondition.payloadHash(), row.preconditionHash())) {
                throw new IllegalStateException("The durable mutation precondition no longer matches");
            }
        }
        long expectedResultRevision = "DUPLICATE".equals(command.operationName()) ? 1L
            : resultRevision(precondition == null ? 0L : precondition.revision());
        if (row.resultRevision() != expectedResultRevision || row.resultHash() == null || row.resultHash().isBlank()
            || row.resultMutationId() == null || !row.mutationId().toString().equals(row.resultMutationId())) {
            throw new IllegalStateException("The durable mutation result revision is invalid");
        }
        return precondition;
    }

    private void verifyExternalPrecondition(Command command, MutationRow row, State precondition) {
        if ("CREATE".equals(command.operationName()) && precondition != null) {
            if (!sameState(synchronize(command.responseResource()), precondition)) {
                throw new IllegalStateException("The deleted create target changed while a durable mutation was pending");
            }
        } else if ("CREATE".equals(command.operationName()) || "DUPLICATE".equals(command.operationName())) {
            if (synchronize(command.responseResource()) != null) {
                throw new IllegalStateException("The mutation target changed while a durable mutation was pending");
            }
        } else {
            State current = synchronize(command.responseResource());
            if (!sameState(current, precondition)) {
                throw new IllegalStateException("The resource changed while a durable mutation was pending");
            }
        }
        if ("DUPLICATE".equals(command.operationName())) {
            State source = synchronize(command.source());
            if (!sameState(source, precondition)) {
                throw new IllegalStateException("The duplicate source changed while a durable mutation was pending");
            }
        }
    }

    private Outcome recoveryOutcome(Command command, MutationRow row, State precondition) {
        ContentHash resultHash = row.resultHash() == null || row.resultHash().isBlank() ? null : new ContentHash(row.resultHash());
        Map<String, Object> resultPayload = row.resultPayload() == null ? null : payload(gson.fromJson(row.resultPayload(), Map.class));
        ResourceActivationState resultActivationState = row.resultActivationState();
        if ("ACTIVATE".equals(command.operationName()) && (resultHash == null || resultPayload == null)) {
            CanonicalPayload<Map<String, Object>> projected = projectedActivation(precondition, command.activationState());
            resultHash = projected.checksum();
            resultPayload = projected.value();
            resultActivationState = command.activationState();
        }
        return new Outcome(Status.PENDING, command.responseResource(), resultHash, row.resultDeleted(), resultPayload, precondition,
            row.resultRevision(), resultActivationState);
    }

    private void verifyPostApplyPrecondition(Command command, MutationRow row) {
        State precondition = validateStoredPrecondition(command, row);
        if ("DUPLICATE".equals(command.operationName())) {
            State source = synchronize(command.source());
            if (!sameState(source, precondition)) {
                throw new IllegalStateException("The duplicate source changed during the mutation");
            }
        }
    }

    private boolean sameState(State first, State second) {
        return first != null && second != null && first.revision() == second.revision()
            && Objects.equals(first.mutationId(), second.mutationId())
            && Objects.equals(first.payloadHash(), second.payloadHash())
            && first.deleted() == second.deleted()
            && first.activationState() == second.activationState();
    }

    private Command command(ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperation operation) {
        if (operation instanceof ResourceCreateRequest<?> create) {
            ServerResourceLocator resource = canonicalResource(create.resource());
            CanonicalPayload<Map<String, Object>> canonical = canonicalMutationPayload(resource, payload(create.payload()));
            return new Command("CREATE", resource, null, resource, create.mutationId(), 0,
                canonical.value(), canonical.checksum(), null,
                canonicalCreatePresentation(resource, create.presentation()));
        }
        if (operation instanceof ResourceSaveRequest<?> save) {
            ServerResourceLocator resource = canonicalResource(save.resource());
            CanonicalPayload<Map<String, Object>> canonical = canonicalMutationPayload(resource, payload(save.payload()));
            return new Command("SAVE", resource, null, resource, save.mutationId(), save.expectedRevision(),
                canonical.value(), canonical.checksum(), null, null);
        }
        if (operation instanceof ResourceDeleteRequest delete) {
            ServerResourceLocator resource = canonicalResource(delete.resource());
            return new Command("DELETE", resource, null, resource, delete.mutationId(), delete.expectedRevision(),
                null, null, null, null);
        }
        if (operation instanceof ResourceDuplicateRequest duplicate) {
            ServerResourceLocator source = canonicalResource(duplicate.source());
            ServerResourceLocator target = canonicalResource(duplicate.target());
            return new Command("DUPLICATE", target, source, target, duplicate.mutationId(), duplicate.expectedRevision(),
                null, null, null, null);
        }
        if (operation instanceof ResourceActivateRequest activate) {
            ServerResourceLocator resource = canonicalResource(activate.resource());
            return new Command("ACTIVATE", resource, null, resource, activate.mutationId(), activate.expectedRevision(),
                null, null, activate.targetState(), null);
        }
        throw new IllegalArgumentException("Resource operation is not supported by the durable authority");
    }

    private ServerResourceLocator canonicalResource(ServerResourceLocator resource) {
        if (resource == null || !PROJECT_METADATA_TYPE.equals(resource.resourceType().value())) {
            return resource;
        }
        return new ServerResourceLocator(resource.serverId(), resource.type(), serverId.canonicalText());
    }

    static ResourcePresentationIntent canonicalCreatePresentation(ServerResourceLocator resource,
                                                                   ResourcePresentationIntent presentation) {
        if (resource == null || presentation == null) {
            return presentation;
        }
        String type = resource.resourceType().value();
        var managed = ReSyncResourceCatalog.byType(type);
        if (managed == null || managed.defaultFolder().isBlank()) {
            return presentation;
        }
        String fileName = AssetFileFormat.idOnlyFileName(resource.id());
        String path = presentation.path().replace('\\', '/');
        String folder = path;
        if (path.toLowerCase(Locale.ROOT).endsWith(".json")) {
            int separator = path.lastIndexOf('/');
            folder = separator < 0 ? "" : path.substring(0, separator);
        }
        String root = managed.defaultFolder();
        if (folder.isBlank() || !folder.equals(root) && !folder.startsWith(root + "/")) {
            folder = root;
        }
        return new ResourcePresentationIntent(presentation.displayName(), folder + "/" + fileName,
            presentation.sortOrder());
    }

    private CanonicalPayload<Map<String, Object>> canonicalMutationPayload(ServerResourceLocator resource,
                                                                             Map<String, Object> payload) {
        if (resource == null || !PROJECT_METADATA_TYPE.equals(resource.resourceType().value())) {
            return payloadCodec.canonicalize(payload);
        }
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            throw new IllegalArgumentException("Project metadata resource adapter is unavailable");
        }
        Object value = adapter.deserialize(gson.toJson(payload));
        if (value == null) {
            throw new IllegalArgumentException("Project metadata payload is required");
        }
        return canonical(adapter.serialize(value));
    }

    private Command command(MutationRow row) {
        AggregateReceipt aggregate = aggregateReceipt(row.mutationId());
        return new Command(row.operation(), locator(row.responseResource()), row.sourceResource() == null ? null : locator(row.sourceResource()),
            locator(row.responseResource()), row.mutationId(), row.expectedRevision(),
            row.resultPayload() == null ? null : payload(gson.fromJson(row.resultPayload(), Map.class)),
            row.resultHash() == null || row.resultHash().isBlank() ? null : new ContentHash(row.resultHash()), row.targetActivationState(),
            aggregate == null ? null : aggregate.presentation());
    }

    private Map<String, Object> payload(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Durable resource payload must be an object");
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Durable resource payload keys must be strings");
            }
            copy.put(key, entry.getValue());
        }
        return payloadCodec.normalize(copy);
    }

    private Map<String, Object> payload(State state) {
        return state == null || state.payload() == null ? null : payload(gson.fromJson(state.payload(), Map.class));
    }

    private ResourceActivationState activationState(String serialized, ResourceActivationState fallback) {
        return serialized == null ? fallback : activationState(payload(gson.fromJson(serialized, Map.class)), fallback);
    }

    private ResourceActivationState activationState(Map<String, Object> payload, ResourceActivationState fallback) {
        if (payload == null || !payload.containsKey("enabled")) {
            return fallback == null ? ResourceActivationState.ACTIVE : fallback;
        }
        Object enabled = payload.get("enabled");
        if (enabled instanceof Boolean value) {
            return value ? ResourceActivationState.ACTIVE : ResourceActivationState.INACTIVE;
        }
        return fallback == null ? ResourceActivationState.ACTIVE : fallback;
    }

    private ResourceActivationState parseActivationState(String value) {
        return ResourceActivationState.fromWireName(value == null || value.isBlank() ? ResourceActivationState.ACTIVE.wireName() : value);
    }

    private ResourceActivationState parseNullableActivationState(String value) {
        return value == null || value.isBlank() ? null : parseActivationState(value);
    }

    private Outcome outcome(Command command, State requested, State source, State target) {
        return switch (command.operationName()) {
            case "CREATE" -> requested != null && !requested.deleted()
                ? new Outcome(Status.CONFLICT, command.responseResource(), hash(requested), requested.deleted(), payload(requested), requested,
                    requested.activationState())
                : pending(command, command.payload(), false, resultRevision(requested == null ? 0L : requested.revision()), command.payloadHash(),
                    activationState(command.payload(), ResourceActivationState.ACTIVE));
            case "SAVE" -> requested == null || requested.deleted() && !PROJECT_METADATA_TYPE.equals(command.responseResource().type().id().value())
                ? new Outcome(Status.NOT_FOUND, command.responseResource(), hash(requested), requested != null && requested.deleted(), payload(requested), requested,
                    requested == null ? null : requested.activationState())
                : requested.revision() != command.expectedRevision()
                    ? new Outcome(Status.REVISION_CONFLICT, command.responseResource(), hash(requested), requested.deleted(), payload(requested), requested,
                        requested.activationState())
                    : pending(command, command.payload(), false, requested.revision() + 1, command.payloadHash(),
                        activationState(command.payload(), requested.activationState()));
            case "DELETE" -> requested == null || requested.deleted()
                ? new Outcome(Status.NOT_FOUND, command.responseResource(), hash(requested), requested != null && requested.deleted(), payload(requested), requested,
                    requested == null ? null : requested.activationState())
                : requested.revision() != command.expectedRevision()
                    ? new Outcome(Status.REVISION_CONFLICT, command.responseResource(), hash(requested), requested.deleted(), payload(requested), requested,
                        requested.activationState())
                    : pending(command, null, true, requested.revision() + 1, hash(requested), null);
            case "DUPLICATE" -> source == null || source.deleted()
                ? new Outcome(Status.NOT_FOUND, command.responseResource(), hash(source), source != null && source.deleted(), payload(source), source,
                    source == null ? null : source.activationState())
                : source.revision() != command.expectedRevision()
                    ? new Outcome(Status.REVISION_CONFLICT, command.responseResource(), hash(source), source.deleted(), payload(source), source,
                        source.activationState())
                    : target != null
                        ? new Outcome(Status.CONFLICT, command.responseResource(), hash(target), target.deleted(), payload(target), target,
                            target.activationState())
                        : pendingDuplicate(command);
            case "ACTIVATE" -> requested == null || requested.deleted()
                ? new Outcome(Status.NOT_FOUND, command.responseResource(), hash(requested), requested != null && requested.deleted(), payload(requested), requested,
                    requested == null ? null : requested.activationState())
                : requested.revision() != command.expectedRevision()
                    ? new Outcome(Status.REVISION_CONFLICT, command.responseResource(), hash(requested), requested.deleted(), payload(requested), requested,
                        requested.activationState())
                    : requested.activationState() == command.activationState()
                        ? new Outcome(Status.APPLIED, command.responseResource(), hash(requested), false, payload(requested), requested,
                            requested.activationState())
                        : activationOutcome(command, requested);
            default -> throw new IllegalArgumentException("Resource operation is not supported by the durable authority");
        };
    }

    private Outcome activationOutcome(Command command, State requested) {
        CanonicalPayload<Map<String, Object>> projected = projectedActivation(requested, command.activationState());
        return pending(command, projected.value(), false, requested.revision() + 1, projected.checksum(), command.activationState());
    }

    private CanonicalPayload<Map<String, Object>> projectedActivation(State current, ResourceActivationState target) {
        if (current == null || current.deleted()) {
            throw new IllegalStateException("An activation result requires a live resource");
        }
        LinkedHashMap<String, Object> projected = new LinkedHashMap<>(payload(current));
        projected.put("enabled", target == ResourceActivationState.ACTIVE);
        return payloadCodec.canonicalize(projected);
    }

    private Outcome pendingDuplicate(Command command) {
        FlowResourceAdapter<Object> adapter = adapter(command.source());
        if (adapter == null) {
            throw new IllegalStateException("Resource adapter is unavailable: " + command.source().resourceType().value());
        }
        Object value = adapter.get(command.source().id());
        if (value == null) {
            throw new IllegalStateException("The duplicate source resource is unavailable");
        }
        Object duplicate = adapter.duplicate(value, command.responseResource().id());
        if (duplicate == null || !command.responseResource().id().equals(adapter.id(duplicate))) {
            throw new IllegalStateException("The duplicate adapter did not produce the requested target resource");
        }
        CanonicalPayload<Map<String, Object>> canonical = canonical(adapter.serialize(duplicate));
        return pending(command, canonical.value(), false, 1, canonical.checksum(),
            activationState(canonical.value(), ResourceActivationState.ACTIVE));
    }

    private Outcome pending(Command command, Map<String, Object> payload, boolean deleted, long revision, ContentHash hash,
                            ResourceActivationState activationState) {
        return new Outcome(Status.PENDING, command.responseResource(), hash, deleted, payload, null, revision, activationState);
    }

    private String preconditionHash(Command command, State requested, State source, State target) {
        if ("DUPLICATE".equals(command.operationName())) {
            return currentHash(source);
        }
        return currentHash(requested);
    }

    private String fingerprint(Command command, String actorId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("actorId", actorId);
        values.put("operation", command.operationName());
        values.put("resource", command.resource().canonicalText());
        values.put("responseResource", command.responseResource().canonicalText());
        values.put("source", command.source() == null ? "" : command.source().canonicalText());
        values.put("targetActivationState", command.activationState() == null ? "" : command.activationState().wireName());
        values.put("expectedRevision", command.expectedRevision());
        values.put("payload", command.payload() == null ? "" : payloadCodec.canonicalInput(command.payload()));
        values.put("presentation", command.presentation() == null ? Map.of() : Map.of(
            "displayName", command.presentation().displayName(), "path", command.presentation().path(),
            "sortOrder", command.presentation().sortOrder()));
        return CanonicalJson.sha256(ResourcePayloadCodec.MUTATION_FINGERPRINT_HASH_DOMAIN, values);
    }

    private MutationRow insertAggregatePending(Command command, String actorId, String fingerprint,
                                               String preconditionHash, Outcome outcome) {
        long started = TemporaryLifecycleDiagnostics.start();
        long now = Instant.now().toEpochMilli();
        MutationRow row = new MutationRow(command.mutationId(), actorId, fingerprint, command.operationName(),
            command.resource().canonicalText(), command.responseResource().canonicalText(), null,
            command.responseResource().canonicalText(), null, 0L, preconditionHash == null ? "" : preconditionHash,
            Status.PENDING, outcome.resultRevision(), command.mutationId().toString(),
            outcome.resultHash().canonicalText(), false, outcome.resultActivationState(),
            payloadCodec.canonicalInput(outcome.resultPayload()), 0L, "", "", now, now);
        try {
            connection.setAutoCommit(false);
            writeMutation(row, false);
            writeAggregateReceipt(new AggregateReceipt(command.mutationId(), command.presentation(),
                projectMetadataResource(), null));
            connection.commit();
            TemporaryLifecycleDiagnostics.event("aggregate_create_receipt", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(command, null, null), "outcome", "pending"));
            return row;
        } catch (SQLException exception) {
            rollback();
            throw new IllegalStateException("Failed To Persist Aggregate Resource Create Receipt", exception);
        } catch (RuntimeException exception) {
            rollback();
            throw exception;
        } finally {
            resetAutoCommit();
        }
    }

    private AggregateCreateState applyAggregateCreate(Command command, Outcome outcome, String clientId,
                                                      FlowResourceMutationLease lease) {
        FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", clientId, lease,
            command.mutationId(), outcome.resultRevision() - 1L, outcome.resultHash().canonicalText());
        Object value = aggregateCreateValue(command);
        long started = TemporaryLifecycleDiagnostics.start();
        AggregateResourceCreateStorage.Result result = aggregateCreateStorage.create(command.responseResource(), value,
            context, command.presentation());
        AggregateCreateState aggregate = validateAggregateResult(command, outcome, result);
        TemporaryLifecycleDiagnostics.event("aggregate_create_storage", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity(command, null, null), "outcome", "committed",
                "primaryRevision", aggregate.primary().revision(), "metadataRevision", aggregate.projectMetadata().revision()));
        return aggregate;
    }

    private Object aggregateCreateValue(Command command) {
        try {
            if (isCoreResource(command.responseResource())) {
                return coreBoundary.decode(CanonicalJson.canonicalBytes(command.payload()), command.responseResource());
            }
            FlowResourceAdapter<Object> adapter = adapter(command.responseResource());
            if (adapter == null) {
                throw new IllegalStateException("Resource adapter is unavailable");
            }
            Object value = adapter.deserialize(gson.toJson(command.payload()));
            String payloadId = value == null ? null : adapter.id(value);
            if (!command.responseResource().id().equals(payloadId)) {
                throw new IllegalArgumentException("Resource payload ID does not match its typed locator");
            }
            adapter.validate(value);
            return value;
        } catch (AggregateResourceCreateStorage.PreCommitRejection rejection) {
            throw rejection;
        } catch (RuntimeException rejection) {
            String message = rejection instanceof CoreGraphMutationValidationException validationFailure
                ? validationFailure.actionableMessage() : safeMessage(rejection);
            throw AggregateResourceCreateStorage.rejectBeforeCommit(
                ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.legacyValue(), message, rejection);
        }
    }

    private AggregateCreateState validateAggregateResult(Command command, Outcome outcome, AggregateResourceCreateStorage.Result result) {
        Objects.requireNonNull(result, "Aggregate create result is required");
        CanonicalPayload<Map<String, Object>> primaryPayload = payloadCodec.canonicalize(result.primary().canonicalPayload());
        CanonicalPayload<Map<String, Object>> metadataPayload = payloadCodec.canonicalize(result.projectMetadata().canonicalPayload());
        boolean core = isCoreResource(result.primary().resource());
        State primary = aggregateState(result.primary(), primaryPayload, !core);
        State metadata = aggregateState(result.projectMetadata(), metadataPayload, true);
        State metadataBaseline = state(projectMetadataResource());
        long metadataRevision;
        try {
            metadataRevision = metadataBaseline == null ? 1L : Math.addExact(metadataBaseline.revision(), 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Project metadata revision is exhausted", exception);
        }
        if (!command.responseResource().equals(primary.resource()) || !projectMetadataResource().equals(metadata.resource())
            || !command.mutationId().equals(primary.mutationId()) || !command.mutationId().equals(metadata.mutationId())
            || primary.revision() != outcome.resultRevision() || !command.payloadHash().canonicalText().equals(primary.payloadHash())
            || !payloadCodec.canonicalInput(command.payload()).equals(canonicalInput(primaryPayload))
            || primary.deleted() || metadata.deleted() || metadata.activationState() != ResourceActivationState.ACTIVE
            || metadata.revision() != metadataRevision || core != (primary.assetHash() != null) || metadata.assetHash() != null) {
            throw new IllegalStateException("Aggregate create storage returned a non-authoritative result");
        }
        return new AggregateCreateState(primary, metadata, canonicalInput(primaryPayload));
    }

    private State aggregateState(AggregateResourceCreateStorage.ResourceState resourceState,
                                 CanonicalPayload<Map<String, Object>> canonical, boolean persistPayload) {
        FlowResourceMutationStamp stamp = resourceState.stamp();
        if (!canonical.checksum().canonicalText().equals(stamp.payloadHash())) {
            throw new IllegalStateException("Aggregate create state payload does not match its authoritative stamp");
        }
        ResourceActivationState activation = activationState(canonical.value(), ResourceActivationState.ACTIVE);
        return new State(resourceState.resource(), stamp.revision(), stamp.mutationId(), stamp.payloadHash(), false,
            persistPayload ? canonicalInput(canonical) : null, activation, resourceState.assetHash(), resourceState.corePayloadHash(),
            resourceState.corePayloadKind());
    }

    private MutationRow commitAggregateApplied(MutationRow pending, AggregateCreateState aggregate) {
        long started = TemporaryLifecycleDiagnostics.start();
        long sequence = nextSequence();
        State primary = aggregate.primary();
        MutationRow row = new MutationRow(pending.mutationId(), pending.actorId(), pending.fingerprint(), pending.operation(),
            pending.requestedResource(), pending.responseResource(), pending.sourceResource(), pending.targetResource(),
            pending.targetActivationState(), pending.expectedRevision(), pending.preconditionHash(), Status.APPLIED,
            primary.revision(), primary.mutationId().toString(), primary.payloadHash(), false, primary.activationState(),
            aggregate.primaryReceiptPayload(), sequence, "", "", pending.createdAt(), Instant.now().toEpochMilli(),
            pending.preconditionAssetHash(), pending.preconditionCorePayloadHash(), pending.preconditionCorePayloadKind(),
            primary.assetHash(), primary.corePayloadHash(), primary.corePayloadKind());
        AggregateReceipt pendingReceipt = aggregateReceipt(row.mutationId());
        if (pendingReceipt == null) {
            throw new IllegalStateException("Aggregate resource create receipt is missing");
        }
        AggregateReceipt receipt = new AggregateReceipt(row.mutationId(), pendingReceipt.presentation(),
            aggregate.projectMetadata().resource(), aggregate.projectMetadata());
        try {
            connection.setAutoCommit(false);
            writeState(primary);
            writeState(aggregate.projectMetadata());
            writeMutation(row, true);
            writeAggregateReceipt(receipt);
            connection.commit();
            TemporaryLifecycleDiagnostics.event("aggregate_create_authority_commit", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "committed", "sequence", sequence,
                    "primaryRevision", primary.revision(), "metadataRevision", aggregate.projectMetadata().revision()));
            return row;
        } catch (SQLException exception) {
            rollback();
            throw new IllegalStateException("Failed To Commit Aggregate Resource Create", exception);
        } catch (RuntimeException exception) {
            rollback();
            throw exception;
        } finally {
            resetAutoCommit();
        }
    }

    private MutationRow insertPending(Command command, String actorId, String fingerprint, String preconditionHash, Outcome outcome) {
        long started = TemporaryLifecycleDiagnostics.start();
        long now = Instant.now().toEpochMilli();
        MutationRow row = new MutationRow(command.mutationId(), actorId, fingerprint, command.operationName(), command.resource().canonicalText(),
            command.responseResource().canonicalText(), command.source() == null ? null : command.source().canonicalText(),
            command.target() == null ? null : command.target().canonicalText(), command.activationState(), command.expectedRevision(),
            preconditionHash == null ? "" : preconditionHash,
            Status.PENDING, outcome.resultRevision(), command.mutationId().toString(), outcome.resultHash() == null ? "" : outcome.resultHash().canonicalText(), outcome.resultDeleted(),
            outcome.resultActivationState(), outcome.resultPayload() == null ? null : payloadCodec.canonicalInput(outcome.resultPayload()), 0, "", "", now, now);
        writeMutation(row, false);
        TemporaryLifecycleDiagnostics.event("mutation_receipt_commit", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity(command, null, null), "receiptStatus", row.status(),
                "resultRevision", row.resultRevision(), "core", false));
        return row;
    }

    private MutationRow insertTerminal(Command command, String actorId, String fingerprint, String preconditionHash, Outcome outcome) {
        long started = TemporaryLifecycleDiagnostics.start();
        long now = Instant.now().toEpochMilli();
        MutationRow row = new MutationRow(command.mutationId(), actorId, fingerprint, command.operationName(), command.resource().canonicalText(),
            command.responseResource().canonicalText(), command.source() == null ? null : command.source().canonicalText(),
            command.target() == null ? null : command.target().canonicalText(), command.activationState(), command.expectedRevision(),
            preconditionHash == null ? "" : preconditionHash,
            outcome.status(), outcome.resultRevision(), outcome.current() == null ? command.mutationId().toString() : outcome.current().mutationId().toString(),
            outcome.resultHash() == null ? "" : outcome.resultHash().canonicalText(), outcome.resultDeleted(),
            outcome.resultActivationState(), outcome.resultPayload() == null ? null : payloadCodec.canonicalInput(outcome.resultPayload()), 0,
            errorCode(outcome.status()), errorMessage(outcome.status()), now, now);
        writeMutation(row, false);
        TemporaryLifecycleDiagnostics.event("mutation_receipt_commit", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity(command, null, null), "receiptStatus", row.status(),
                "resultRevision", row.resultRevision(), "core", false));
        return row;
    }

    private void writeMutation(MutationRow row, boolean update) {
        try {
            if (!update) {
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO resource_mutation_receipt(mutation_id, actor_id, fingerprint, operation, requested_resource, response_resource,
                        source_resource, target_resource, target_activation_state, expected_revision, precondition_hash, status,
                        result_revision, result_mutation_id, result_hash, result_deleted, result_activation_state, result_payload,
                        precondition_asset_hash, precondition_core_payload_hash, precondition_core_payload_kind,
                        result_asset_hash, result_core_payload_hash, result_core_payload_kind,
                        sequence, error_code, error_message, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                    bindMutation(statement, row);
                    statement.executeUpdate();
                }
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_mutation_receipt SET status = ?, result_revision = ?, result_mutation_id = ?, result_hash = ?,
                    result_deleted = ?, result_activation_state = ?, result_payload = ?, sequence = ?, error_code = ?, error_message = ?,
                    precondition_asset_hash = ?, precondition_core_payload_hash = ?, precondition_core_payload_kind = ?,
                    result_asset_hash = ?, result_core_payload_hash = ?, result_core_payload_kind = ?,
                    updated_at = ? WHERE mutation_id = ?
                """)) {
                statement.setString(1, row.status().name());
                statement.setLong(2, row.resultRevision());
                statement.setString(3, row.resultMutationId());
                statement.setString(4, row.resultHash());
                statement.setInt(5, row.resultDeleted() ? 1 : 0);
                statement.setString(6, row.resultActivationState() == null ? null : row.resultActivationState().wireName());
                statement.setString(7, row.resultPayload());
                statement.setLong(8, row.sequence());
                statement.setString(9, row.errorCode());
                statement.setString(10, row.errorMessage());
                statement.setString(11, row.preconditionAssetHash());
                statement.setString(12, row.preconditionCorePayloadHash());
                statement.setString(13, row.preconditionCorePayloadKind());
                statement.setString(14, row.resultAssetHash());
                statement.setString(15, row.resultCorePayloadHash());
                statement.setString(16, row.resultCorePayloadKind());
                statement.setLong(17, row.updatedAt());
                statement.setString(18, row.mutationId().toString());
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Persist Resource Mutation Receipt", exception);
        }
    }

    private AggregateReceipt aggregateReceipt(UUID mutationId) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM resource_create_aggregate_receipt WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                ResourcePresentationIntent presentation = new ResourcePresentationIntent(result.getString("display_name"),
                    result.getString("resource_path"), result.getInt("sort_order"));
                State metadata = result.getString("metadata_revision") == null ? null : new State(
                    locator(result.getString("metadata_resource")), result.getLong("metadata_revision"),
                    UUID.fromString(result.getString("metadata_mutation_id")), result.getString("metadata_hash"), false,
                    result.getString("metadata_payload"), ResourceActivationState.ACTIVE);
                return new AggregateReceipt(mutationId, presentation, locator(result.getString("metadata_resource")), metadata);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Read Aggregate Resource Create Receipt", exception);
        }
    }

    private void writeAggregateReceipt(AggregateReceipt receipt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO resource_create_aggregate_receipt(mutation_id, display_name, resource_path, sort_order,
                metadata_resource, metadata_revision, metadata_mutation_id, metadata_hash, metadata_payload)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(mutation_id) DO UPDATE SET metadata_resource = excluded.metadata_resource,
                metadata_revision = excluded.metadata_revision, metadata_mutation_id = excluded.metadata_mutation_id,
                metadata_hash = excluded.metadata_hash, metadata_payload = excluded.metadata_payload
            """)) {
            statement.setString(1, receipt.mutationId().toString());
            statement.setString(2, receipt.presentation().displayName());
            statement.setString(3, receipt.presentation().path());
            statement.setInt(4, receipt.presentation().sortOrder());
            statement.setString(5, receipt.metadataResource().canonicalText());
            if (receipt.metadata() == null) {
                statement.setObject(6, null);
                statement.setObject(7, null);
                statement.setObject(8, null);
                statement.setObject(9, null);
            } else {
                statement.setLong(6, receipt.metadata().revision());
                statement.setString(7, receipt.metadata().mutationId().toString());
                statement.setString(8, receipt.metadata().payloadHash());
                statement.setString(9, receipt.metadata().payload());
            }
            statement.executeUpdate();
        }
    }

    private ServerResourceLocator projectMetadataResource() {
        return new ServerResourceLocator(serverId,
            ContractRef.of(PROTOCOL_OWNER, new ResourceTypeId(PROJECT_METADATA_TYPE)), serverId.canonicalText());
    }

    private MutationRow finishRejected(MutationRow pending, String code, String message) {
        ProtocolRejectionCode rejection = ProtocolRejectionCode.fromResourceError(code);
        MutationRow row = new MutationRow(pending.mutationId(), pending.actorId(), pending.fingerprint(), pending.operation(), pending.requestedResource(), pending.responseResource(),
            pending.sourceResource(), pending.targetResource(), pending.targetActivationState(), pending.expectedRevision(), pending.preconditionHash(), Status.REJECTED,
            pending.resultRevision(), pending.resultMutationId(), pending.resultHash(), pending.resultDeleted(), pending.resultActivationState(), pending.resultPayload(), 0,
            rejection.legacyValue(), message == null || message.isBlank() ? rejection.legacyValue() : message,
            pending.createdAt(), Instant.now().toEpochMilli());
        try {
            connection.setAutoCommit(false);
            writeMutation(row, true);
            connection.commit();
            return row;
        } catch (SQLException exception) {
            rollback();
            throw new IllegalStateException("Failed To Persist Resource Mutation Failure", exception);
        } finally {
            resetAutoCommit();
        }
    }

    private MutationRow finishTerminal(MutationRow pending, Outcome outcome) {
        if (outcome.status() == Status.PENDING || outcome.current() == null) {
            throw new IllegalArgumentException("A pending mutation requires an authoritative terminal state");
        }
        MutationRow row = new MutationRow(pending.mutationId(), pending.actorId(), pending.fingerprint(),
            pending.operation(), pending.requestedResource(), pending.responseResource(), pending.sourceResource(),
            pending.targetResource(), pending.targetActivationState(), pending.expectedRevision(),
            pending.preconditionHash(), outcome.status(), outcome.resultRevision(),
            outcome.current().mutationId().toString(),
            outcome.resultHash() == null ? "" : outcome.resultHash().canonicalText(), outcome.resultDeleted(),
            outcome.resultActivationState(),
            outcome.resultPayload() == null ? null : payloadCodec.canonicalInput(outcome.resultPayload()), 0,
            errorCode(outcome.status()), errorMessage(outcome.status()), pending.createdAt(),
            Instant.now().toEpochMilli());
        try {
            connection.setAutoCommit(false);
            writeMutation(row, true);
            connection.commit();
            return row;
        } catch (SQLException exception) {
            rollback();
            throw new IllegalStateException("Failed To Persist Resource Mutation Terminal State", exception);
        } finally {
            resetAutoCommit();
        }
    }

    private MutationRow commitApplied(MutationRow pending, State state) {
        long started = TemporaryLifecycleDiagnostics.start();
        long sequence = nextSequence();
        State coupledProjectMetadata = coupledProjectMetadataState(pending);
        MutationRow row = new MutationRow(pending.mutationId(), pending.actorId(), pending.fingerprint(), pending.operation(), pending.requestedResource(), pending.responseResource(),
            pending.sourceResource(), pending.targetResource(), pending.targetActivationState(), pending.expectedRevision(), pending.preconditionHash(), Status.APPLIED,
            state.revision(), state.mutationId().toString(), state.payloadHash(), state.deleted(), state.activationState(), state.payload(), sequence, "", "",
            pending.createdAt(), Instant.now().toEpochMilli());
        try {
            connection.setAutoCommit(false);
            writeState(state);
            if (coupledProjectMetadata != null) {
                writeState(coupledProjectMetadata);
            }
            writeMutation(row, true);
            connection.commit();
            coupledProjectMetadataRepairs.remove(pending.mutationId());
            TemporaryLifecycleDiagnostics.event("storage_metadata_commit", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "committed", "core", false,
                    "metadataCoupled", coupledProjectMetadata != null, "sequence", sequence));
            return row;
        } catch (SQLException exception) {
            rollback();
            TemporaryLifecycleDiagnostics.event("storage_metadata_commit", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "failed", "core", false,
                    "failure", exception.getClass().getSimpleName()));
            throw new IllegalStateException("Failed To Commit Resource Mutation", exception);
        } finally {
            resetAutoCommit();
        }
    }

    private long nextSequence() {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(sequence), 0) + 1 FROM resource_mutation_receipt");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Allocate Resource Mutation Sequence", exception);
        }
    }

    private void writeState(State state) throws SQLException {
        if (isProjectMetadata(state.resource())) {
            projectMetadataCache = null;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO resource_mutation_state(resource, revision, mutation_id, payload_hash, deleted, payload, activation_state,
                asset_hash, core_payload_hash, core_payload_kind, updated_at)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(resource) DO UPDATE SET revision = excluded.revision, mutation_id = excluded.mutation_id,
                payload_hash = excluded.payload_hash, deleted = excluded.deleted, payload = excluded.payload,
                activation_state = excluded.activation_state, asset_hash = excluded.asset_hash,
                core_payload_hash = excluded.core_payload_hash, core_payload_kind = excluded.core_payload_kind,
                updated_at = excluded.updated_at
            """)) {
            statement.setString(1, state.resource().canonicalText());
            statement.setLong(2, state.revision());
            statement.setString(3, state.mutationId().toString());
            statement.setString(4, state.payloadHash());
            statement.setInt(5, state.deleted() ? 1 : 0);
            statement.setString(6, state.payload());
            statement.setString(7, state.activationState() == null ? ResourceActivationState.ACTIVE.wireName() : state.activationState().wireName());
            statement.setString(8, state.assetHash());
            statement.setString(9, state.corePayloadHash());
            statement.setString(10, state.corePayloadKind());
            statement.setLong(11, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private MutationRow mutation(UUID mutationId) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readMutation(result) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Read Resource Mutation Receipt", exception);
        }
    }

    private State state(ServerResourceLocator resource) {
        if (resource == null) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM resource_mutation_state WHERE resource = ?")) {
            statement.setString(1, resource.canonicalText());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readState(result, resource) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Read Resource Mutation State", exception);
        }
    }

    private State synchronize(ServerResourceLocator resource) {
        ServerResourceLocator canonical = canonicalResource(resource);
        if (isProjectMetadata(canonical)) {
            return synchronizeProjectMetadata(canonical);
        }
        State persisted = state(resource);
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            throw new IllegalStateException("Resource adapter is unavailable: " + resource.resourceType().value());
        }
        FlowResourceMutationStamp stamp = readStamp(resource, adapter);
        if (persisted == null) {
            if (stamp == null) {
                Object value = adapter.get(resource.id());
                if (value != null) {
                    throw new IllegalStateException("A live resource has no authoritative mutation stamp");
                }
                return null;
            }
            if (stamp.deleted()) {
                return bootstrap(resource, stamp, null);
            }
            Object value = adapter.get(resource.id());
            if (value == null) {
                throw new IllegalStateException("An authoritative live resource is missing from its adapter");
            }
            return bootstrap(resource, stamp, value, adapter);
        }
        verifyStamp(resource, stamp, persisted.revision(), persisted.mutationId(), new ContentHash(persisted.payloadHash()), persisted.deleted());
        if (persisted.deleted()) {
            return persisted;
        }
        Object value = adapter.get(resource.id());
        if (value == null) {
            throw new IllegalStateException("A durable live resource is missing from its authoritative adapter");
        }
        String hash = canonical(adapter.serialize(value)).checksum().canonicalText();
        if (!persisted.payloadHash().equals(hash) || !persisted.activationState().equals(activationState(adapter.serialize(value), persisted.activationState()))) {
            throw new IllegalStateException("The authoritative adapter changed outside the durable resource boundary");
        }
        return persisted;
    }

    private State coreStateOrNull(ServerResourceLocator resource) {
        CoreState current = synchronizeCore(resource);
        return current == null ? null : corePersistedState(current);
    }

    private State synchronizeProjectMetadata(ServerResourceLocator resource) {
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            throw new IllegalStateException("Resource adapter is unavailable: " + resource.resourceType().value());
        }
        State persisted = state(resource);
        FlowResourceAdapter.MutationObservation observation = adapter.readMutationObservation(resource.id());
        ProjectMetadataCache cached = projectMetadataCache;
        if (cached != null && cached.adapter() == adapter && cached.observation().equals(observation)
            && sameResourceState(cached.state(), persisted) && matchesProjectMetadataObservation(persisted, observation)) {
            return persisted;
        }
        State reconciled = synchronizeProjectMetadataFull(resource, adapter, persisted);
        cacheProjectMetadata(adapter, reconciled);
        return reconciled;
    }

    private State synchronizeProjectMetadataFull(ServerResourceLocator resource, FlowResourceAdapter<Object> adapter,
                                                 State persisted) {
        State current = authoritativeState(resource, adapter);
        if (current == null) {
            if (persisted != null) {
                throw new IllegalStateException("The project metadata authority is missing a durable resource");
            }
            return null;
        }
        if (persisted == null) {
            return bootstrapState(current);
        }
        if (sameResourceState(persisted, current)) {
            return persisted;
        }
        State recovered = recoverUnreceiptedProjectMetadataState(persisted, current, adapter);
        if (recovered != null) {
            persistReconciledProjectMetadata(recovered);
            return recovered;
        }
        State imported = recoverLegacyAdminProjectMetadataChain(persisted, current);
        if (imported != null) {
            return imported;
        }
        requireForwardProjectMetadataState(persisted, current);
        persistReconciledProjectMetadata(current);
        return current;
    }

    private void cacheProjectMetadataAfterCommit(State state) {
        try {
            cacheProjectMetadata(adapter(projectMetadataResource()), state);
        } catch (RuntimeException exception) {
            projectMetadataCache = null;
            Log.warn("Project metadata observation was unavailable after commit: " + safeMessage(exception));
        }
    }

    private void cacheProjectMetadata(FlowResourceAdapter<Object> adapter, State state) {
        projectMetadataCache = null;
        if (adapter == null || state == null) {
            return;
        }
        FlowResourceAdapter.MutationObservation observation = adapter.readMutationObservation(state.resource().id());
        if (matchesProjectMetadataObservation(state, observation)) {
            projectMetadataCache = new ProjectMetadataCache(adapter, observation, state);
        }
    }

    private boolean matchesProjectMetadataObservation(State state, FlowResourceAdapter.MutationObservation observation) {
        if (state == null || observation == null || observation.stamp() == null) {
            return false;
        }
        FlowResourceMutationStamp stamp = observation.stamp();
        return state.resource().resourceType().value().equals(stamp.type()) && state.resource().id().equals(stamp.id())
            && state.revision() == stamp.revision() && state.mutationId().equals(stamp.mutationId())
            && state.payloadHash().equals(stamp.payloadHash()) && state.deleted() == stamp.deleted();
    }

    private State authoritativeState(ServerResourceLocator resource) {
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            throw new IllegalStateException("Resource adapter is unavailable: " + resource.resourceType().value());
        }
        return authoritativeState(resource, adapter);
    }

    private State authoritativeState(ServerResourceLocator resource, FlowResourceAdapter<Object> adapter) {
        FlowResourceMutationStamp stamp = readStamp(resource, adapter);
        if (stamp == null) {
            Object value = adapter.get(resource.id());
            if (value != null) {
                throw new IllegalStateException("A live resource has no authoritative mutation stamp");
            }
            return null;
        }
        if (stamp.deleted()) {
            return new State(resource, stamp.revision(), stamp.mutationId(), stamp.payloadHash(), true, null, null);
        }
        Object value = adapter.get(resource.id());
        if (value == null) {
            throw new IllegalStateException("An authoritative live resource is missing from its adapter");
        }
        CanonicalPayload<Map<String, Object>> canonical = canonical(adapter.serialize(value));
        ContentHash hash = canonical.checksum();
        if (isProjectMetadata(resource) && !stamp.payloadHash().equals(hash.canonicalText())) {
            State repaired = new State(resource, stamp.revision(), stamp.mutationId(), hash.canonicalText(), false,
                canonicalInput(canonical), activationState(canonical.value(), ResourceActivationState.ACTIVE));
            State recovered = recoverUnreceiptedProjectMetadataState(state(resource), repaired, adapter);
            if (recovered != null) {
                return recovered;
            }
            State imported = recoverLegacyAdminProjectMetadataChain(state(resource), repaired);
            if (imported != null) {
                return imported;
            }
            requireProjectMetadataAdvanceReceipt(repaired);
            return repaired;
        }
        verifyStamp(resource, stamp, stamp.revision(), stamp.mutationId(), hash, false);
        return new State(resource, stamp.revision(), stamp.mutationId(), stamp.payloadHash(), false,
            canonicalInput(canonical), activationState(canonical.value(), ResourceActivationState.ACTIVE));
    }

    private void requireForwardProjectMetadataState(State persisted, State current) {
        if (current.deleted()) {
            throw new IllegalStateException("Project metadata deletion cannot be reconciled from a side effect");
        }
        if (current.revision() < persisted.revision()) {
            throw new IllegalStateException("The project metadata authority moved backwards");
        }
        if (current.revision() == persisted.revision()) {
            throw new IllegalStateException("The project metadata authority changed identity at the same revision");
        }
        requireProjectMetadataAdvanceReceipt(current);
    }

    private void requireProjectMetadataAdvanceReceipt(State current) {
        if (hasPendingMutation(current.resource())) {
            throw new IllegalStateException("A project metadata mutation is still pending");
        }
        MutationRow row = mutation(current.mutationId());
        if (row == null || row.status() != Status.APPLIED) {
            throw new IllegalStateException("Project metadata advanced without an applied durable mutation receipt");
        }
        ServerResourceLocator primary = locator(row.responseResource());
        if (!serverId.equals(primary.serverId()) || isProjectMetadata(primary)
            || !Objects.equals(row.resultMutationId(), current.mutationId().toString())) {
            throw new IllegalStateException("Project metadata advance is not bound to an applied payload mutation");
        }
    }

    private void persistReconciledProjectMetadata(State current) {
        try {
            connection.setAutoCommit(false);
            writeState(current);
            connection.commit();
        } catch (SQLException exception) {
            rollback();
            throw new IllegalStateException("Failed To Reconcile Project Metadata State", exception);
        } finally {
            resetAutoCommit();
        }
    }

    private State recoverUnreceiptedProjectMetadataState(State persisted, State current,
                                                           FlowResourceAdapter<Object> adapter) {
        if (persisted == null || current == null || persisted.deleted() || current.deleted()
            || hasPendingMutation(persisted.resource())) {
            return null;
        }
        long revision;
        try {
            revision = Math.addExact(persisted.revision(), 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Project metadata lineage revision is exhausted", exception);
        }
        boolean baselinePayloadDrift = current.revision() == persisted.revision()
            && current.mutationId().equals(persisted.mutationId())
            && !current.payloadHash().equals(persisted.payloadHash());
        boolean committedRepair = current.revision() == revision
            && !current.payloadHash().equals(persisted.payloadHash());
        if (!baselinePayloadDrift && !committedRepair) {
            return null;
        }
        FlowResourceMutationStamp recovered = adapter.recoverUnreceiptedProjectMetadataLineage(
            persisted.revision(), persisted.mutationId(), persisted.payloadHash());
        if (recovered == null) {
            return null;
        }
        FlowResourceMutationStamp published = readStamp(persisted.resource(), adapter);
        Object value = adapter.get(persisted.resource().id());
        if (published == null || published.deleted() || recovered.deleted() || value == null
            || recovered.revision() != revision || published.revision() != revision
            || !published.mutationId().equals(recovered.mutationId())
            || !published.payloadHash().equals(recovered.payloadHash())) {
            throw new IllegalStateException("Unreceipted project metadata lineage recovery is not authoritative");
        }
        CanonicalPayload<Map<String, Object>> canonical = canonical(adapter.serialize(value));
        String hash = canonical.checksum().canonicalText();
        if (!hash.equals(recovered.payloadHash())) {
            throw new IllegalStateException("Unreceipted project metadata lineage recovery payload is not canonical");
        }
        return new State(persisted.resource(), revision, recovered.mutationId(), hash, false,
            canonicalInput(canonical), activationState(canonical.value(), ResourceActivationState.ACTIVE));
    }

    private State recoverLegacyAdminProjectMetadataChain(State persisted, State current) {
        if (persisted == null || current == null || persisted.deleted() || current.deleted()
            || current.revision() <= persisted.revision() || hasPendingMutation(persisted.resource())
            || mutation(current.mutationId()) != null) {
            return rejectLegacyAdminRecovery("admission", current == null ? null : current.mutationId(), -1L);
        }
        long gap = current.revision() - persisted.revision();
        if (gap < 1L || gap > MAX_LEGACY_ADMIN_CHAIN) {
            return rejectLegacyAdminRecovery("revision_gap", current.mutationId(), gap);
        }
        Path assetsRoot = activeScopeRoot.resolve("assets").toAbsolutePath().normalize();
        Path bindingRoot = assetsRoot.resolve(".asset-coordinator").resolve("bindings");
        if (!Files.isDirectory(bindingRoot, LinkOption.NOFOLLOW_LINKS)) {
            return rejectLegacyAdminRecovery("binding_inventory", current.mutationId(), -1L);
        }
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assetsRoot, gson)) {
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
            AssetTransactionCoordinator.AssetKey lineageKey = new AssetTransactionCoordinator.AssetKey(
                PROJECT_METADATA_LINEAGE_TYPE, PROJECT_METADATA_LINEAGE_ID);
            UUID currentLineage = snapshot.mutationValue(lineageKey).flatMap(SqliteProtocolResourceMutationAuthority::uuid).orElse(null);
            if (!current.mutationId().equals(currentLineage)) {
                return rejectLegacyAdminRecovery("current_lineage", current.mutationId(), snapshot.rootSequence());
            }
            AssetTransactionCoordinator.MutationView baseline = coordinator.mutation(persisted.mutationId()).orElse(null);
            if (baseline == null || baseline.result().project().revision() >= snapshot.project().revision()) {
                return rejectLegacyAdminRecovery("baseline", persisted.mutationId(), snapshot.rootSequence());
            }
            List<AssetTransactionCoordinator.MutationView> history = coordinatorHistory(coordinator, bindingRoot);
            LegacyAdminChain chain = legacyAdminChain(baseline, history, snapshot, lineageKey, persisted, current);
            if (chain == null) {
                return rejectLegacyAdminRecovery("chain", current.mutationId(), snapshot.rootSequence());
            }
            legacyAdminRecoveryEvent("validated", current.mutationId(), snapshot.rootSequence(),
                "transitionCount", chain.transitions().size());
            importLegacyAdminChain(chain, current);
            if (!recoverCommittedCoreTransitions().isEmpty()) {
                throw new IllegalStateException("Recovered legacy Core resource transition could not be published");
            }
            legacyAdminRecoveryEvent("imported", current.mutationId(), snapshot.rootSequence(),
                "transitionCount", chain.transitions().size());
            return current;
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Failed To Recover Legacy Admin Resource Mutations", exception);
        }
    }

    private List<AssetTransactionCoordinator.MutationView> coordinatorHistory(AssetTransactionCoordinator coordinator,
                                                                               Path bindingRoot) throws IOException {
        List<Path> bindings;
        try (Stream<Path> stream = Files.list(bindingRoot)) {
            bindings = stream.sorted().limit(MAX_COORDINATOR_BINDINGS + 1L).toList();
        }
        if (bindings.size() > MAX_COORDINATOR_BINDINGS) {
            throw new IOException("Asset coordinator binding inventory exceeds the recovery bound");
        }
        List<AssetTransactionCoordinator.MutationView> history = new ArrayList<>();
        for (Path binding : bindings) {
            String name = binding.getFileName().toString();
            if (!Files.isRegularFile(binding, LinkOption.NOFOLLOW_LINKS) || !name.endsWith(".json")) {
                throw new IOException("Asset coordinator binding inventory is not canonical");
            }
            UUID mutationId = uuid(name.substring(0, name.length() - ".json".length())).orElse(null);
            if (mutationId == null) {
                throw new IOException("Asset coordinator binding identity is invalid");
            }
            AssetTransactionCoordinator.MutationView mutation = coordinator.mutation(mutationId)
                .orElseThrow(() -> new IOException("Asset coordinator binding has no validated mutation"));
            history.add(mutation);
        }
        return history;
    }

    private LegacyAdminChain legacyAdminChain(AssetTransactionCoordinator.MutationView baseline,
                                               List<AssetTransactionCoordinator.MutationView> history,
                                               AssetTransactionCoordinator.Snapshot snapshot,
                                               AssetTransactionCoordinator.AssetKey lineageKey,
                                               State persisted, State current) throws IOException {
        AssetTransactionCoordinator.ExpectedProject previousProject = baseline.result().project();
        AssetTransactionCoordinator.ExpectedState previousLineage = baseline.result().states().get(lineageKey);
        if (!(previousLineage instanceof AssetTransactionCoordinator.Live baselineLineage)
            || !matchesProjectMetadataLineageHash(baselineLineage.hash(), persisted.revision(), persisted.mutationId(),
            persisted.payloadHash())) {
            return rejectLegacyAdminRecovery("baseline_lineage", persisted.mutationId(), baseline.result().rootSequence());
        }
        Map<String, Object> metadata = gson.fromJson(persisted.payload(), Map.class);
        if (metadata == null) {
            return rejectLegacyAdminRecovery("baseline_metadata", persisted.mutationId(), baseline.result().rootSequence());
        }
        List<LegacyAdminTransition> transitions = new ArrayList<>();
        long metadataRevision = persisted.revision();
        String sharedId = null;
        Map<String, Boolean> liveTypes = new LinkedHashMap<>();
        Map<String, Integer> transitionCounts = new LinkedHashMap<>();
        String survivingCoreType = null;
        long previousSequence = baseline.result().rootSequence();
        long baselineSequence = previousSequence;
        List<AssetTransactionCoordinator.MutationView> ordered = history.stream()
            .filter(mutation -> mutation.result().rootSequence() > baselineSequence
                && mutation.result().rootSequence() <= snapshot.rootSequence())
            .sorted(Comparator.comparingLong(mutation -> mutation.result().rootSequence()))
            .toList();
        for (AssetTransactionCoordinator.MutationView mutation : ordered) {
            if (mutation.result().rootSequence() != Math.addExact(previousSequence, 1L)) {
                return rejectLegacyAdminRecovery("sequence_gap", mutation.mutationId(), mutation.result().rootSequence());
            }
            if (mutation.result().project().equals(previousProject)
                && !mutation.result().states().containsKey(lineageKey)) {
                if (!exactMetadataNeutralMutation(mutation, previousProject)) {
                    return rejectLegacyAdminRecovery("neutral_mutation", mutation.mutationId(), mutation.result().rootSequence());
                }
                previousSequence = mutation.result().rootSequence();
                continue;
            }
            if (transitions.size() >= MAX_LEGACY_ADMIN_CHAIN || !exactProjectBaseline(mutation, previousProject)
                || !mutation.result().states().containsKey(lineageKey)) {
                return rejectLegacyAdminRecovery("transition_baseline", mutation.mutationId(), mutation.result().rootSequence());
            }
            LegacyAdminTransition transition = legacyAdminTransition(mutation, lineageKey, previousLineage,
                metadata, Math.addExact(metadataRevision, 1L));
            if (transition == null) {
                return rejectLegacyAdminRecovery("transition_shape", mutation.mutationId(), mutation.result().rootSequence());
            }
            if (sharedId == null) {
                sharedId = transition.resource().id();
            } else if (!sharedId.equals(transition.resource().id())) {
                return rejectLegacyAdminRecovery("resource_identity", mutation.mutationId(), mutation.result().rootSequence());
            }
            String type = transition.resource().resourceType().value();
            transitionCounts.merge(type, 1, Integer::sum);
            boolean live = liveTypes.getOrDefault(type, false);
            if ("CREATE".equals(transition.operation())) {
                if (live) {
                    return rejectLegacyAdminRecovery("duplicate_create", mutation.mutationId(), mutation.result().rootSequence());
                }
                liveTypes.put(type, true);
                if (CORE_RESOURCE_TYPES.contains(type)) {
                    if (survivingCoreType != null) {
                        return rejectLegacyAdminRecovery("multiple_core", mutation.mutationId(), mutation.result().rootSequence());
                    }
                    survivingCoreType = type;
                }
            } else {
                if (!live || CORE_RESOURCE_TYPES.contains(type)) {
                    return rejectLegacyAdminRecovery("invalid_delete", mutation.mutationId(), mutation.result().rootSequence());
                }
                liveTypes.put(type, false);
            }
            transitions.add(transition);
            previousSequence = mutation.result().rootSequence();
            previousProject = mutation.result().project();
            previousLineage = mutation.result().states().get(lineageKey);
            metadataRevision++;
        }
        if (transitions.size() != current.revision() - persisted.revision()
            || !previousProject.equals(snapshot.project()) || survivingCoreType == null
            || !current.mutationId().equals(transitions.getLast().mutation().mutationId())) {
            return rejectLegacyAdminRecovery("chain_summary", current.mutationId(), snapshot.rootSequence());
        }
        for (Map.Entry<String, Boolean> entry : liveTypes.entrySet()) {
            int expectedTransitions = CORE_RESOURCE_TYPES.contains(entry.getKey()) ? 1 : 2;
            if (entry.getValue() != CORE_RESOURCE_TYPES.contains(entry.getKey())
                || transitionCounts.getOrDefault(entry.getKey(), 0) != expectedTransitions) {
                return rejectLegacyAdminRecovery("type_balance", current.mutationId(), snapshot.rootSequence());
            }
        }
        CanonicalPayload<Map<String, Object>> canonical = payloadCodec.canonicalize(metadata);
        if (!canonical.checksum().canonicalText().equals(current.payloadHash())) {
            return rejectLegacyAdminRecovery("canonical_metadata", current.mutationId(), snapshot.rootSequence());
        }
        return new LegacyAdminChain(List.copyOf(transitions), survivingCoreType, persisted);
    }

    private <T> T rejectLegacyAdminRecovery(String reason, UUID mutationId, long sequence) {
        legacyAdminRecoveryEvent("rejected", mutationId, sequence, "reason", reason);
        return null;
    }

    private void legacyAdminRecoveryEvent(String outcome, UUID mutationId, long sequence, Object... values) {
        List<Object> fields = new ArrayList<>();
        fields.add("outcome");
        fields.add(outcome);
        if (sequence >= 0L) {
            fields.add("sequence");
            fields.add(sequence);
        }
        if (values != null) {
            fields.addAll(List.of(values));
        }
        TemporaryLifecycleDiagnostics.event("legacy_admin_recovery", 0L,
            TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(serverId,
                PROJECT_METADATA_TYPE + ':' + serverId.canonicalText(), mutationId, null, null, null,
                authorityEpoch, null), fields.toArray()));
    }

    private boolean exactMetadataNeutralMutation(AssetTransactionCoordinator.MutationView mutation,
                                                 AssetTransactionCoordinator.ExpectedProject previous) {
        JsonObject intent = mutation.intent();
        if (!intent.has("expectedProject") || !intent.get("expectedProject").isJsonObject()) {
            return false;
        }
        JsonObject expected = intent.getAsJsonObject("expectedProject");
        JsonArray deltas = intent.has("projectDeltas") && intent.get("projectDeltas").isJsonArray()
            ? intent.getAsJsonArray("projectDeltas") : null;
        if (!expected.has("revision") || !expected.has("hash") || deltas == null || !deltas.isEmpty()
            || expected.get("revision").getAsLong() != previous.revision()
            || !expected.get("hash").getAsString().equals(previous.hash())) {
            return false;
        }
        MutationRow receipt = mutation(mutation.mutationId());
        if (receipt == null || receipt.status() != Status.APPLIED || !"SAVE".equals(receipt.operation())
            || !mutation.mutationId().toString().equals(receipt.resultMutationId())) {
            return false;
        }
        ServerResourceLocator resource = locator(receipt.responseResource());
        if (!isCoreResource(resource)) {
            return false;
        }
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey(
            resource.resourceType().value(), resource.id());
        AssetTransactionCoordinator.ExpectedState state = mutation.result().states().get(key);
        boolean assetProof = state instanceof AssetTransactionCoordinator.Live live
            && exactNeutralCoreAsset(intent, resource, receipt, live);
        CoreState core = synchronizeCore(resource);
        boolean coreProof = state instanceof AssetTransactionCoordinator.Live live && core != null
            && matchesCoreOutcome(core, receipt)
            && live.hash().equals(StorageSafety.sha256(coreBoundary.encode(core.decoded())));
        boolean exact = mutation.result().states().size() == 1 && state instanceof AssetTransactionCoordinator.Live live
            && receipt.resultRevision() == live.revision() && assetProof && coreProof;
        return exact;
    }

    private boolean exactNeutralCoreAsset(JsonObject intent, ServerResourceLocator resource, MutationRow receipt,
                                          AssetTransactionCoordinator.Live live) {
        if (!intent.has("assets") || !intent.get("assets").isJsonArray()) {
            return false;
        }
        JsonArray assets = intent.getAsJsonArray("assets");
        if (assets.size() != 1 || !assets.get(0).isJsonObject()) {
            return false;
        }
        JsonObject asset = assets.get(0).getAsJsonObject();
        JsonObject expected = asset.has("expected") && asset.get("expected").isJsonObject()
            ? asset.getAsJsonObject("expected") : null;
        boolean exact = expected != null && "WRITE".equals(string(asset, "operation"))
            && resource.resourceType().value().equals(string(asset, "type"))
            && resource.id().equals(string(asset, "id")) && "LIVE".equals(string(expected, "kind"))
            && live.hash().equals(string(asset, "payloadHash"))
            && expected.has("revision") && expected.get("revision").getAsLong() == receipt.expectedRevision()
            && expected.has("hash") && !expected.get("hash").getAsString().isBlank()
            && receipt.preconditionAssetHash() != null && !receipt.preconditionAssetHash().isBlank();
        return exact;
    }

    private boolean exactProjectBaseline(AssetTransactionCoordinator.MutationView mutation,
                                         AssetTransactionCoordinator.ExpectedProject previous) {
        JsonObject intent = mutation.intent();
        if (intent.has("scope") || !intent.has("expectedProject") || !intent.get("expectedProject").isJsonObject()) {
            return false;
        }
        JsonObject expected = intent.getAsJsonObject("expectedProject");
        return expected.has("revision") && expected.has("hash")
            && expected.get("revision").getAsLong() == previous.revision()
            && expected.get("hash").getAsString().equals(previous.hash())
            && mutation.result().project().revision() == previous.revision() + 1L;
    }

    private LegacyAdminTransition legacyAdminTransition(AssetTransactionCoordinator.MutationView mutation,
                                                        AssetTransactionCoordinator.AssetKey lineageKey,
                                                        AssetTransactionCoordinator.ExpectedState previousLineage,
                                                        Map<String, Object> metadata,
                                                        long metadataRevision) throws IOException {
        JsonObject intent = mutation.intent();
        if (!intent.has("projectDeltas") || !intent.get("projectDeltas").isJsonArray()) {
            return null;
        }
        JsonArray deltas = intent.getAsJsonArray("projectDeltas");
        if (deltas.size() != 1 || !deltas.get(0).isJsonObject()) {
            return null;
        }
        JsonObject delta = deltas.get(0).getAsJsonObject();
        if (!"SET".equals(string(delta, "operation")) || !delta.has("path") || !delta.get("path").isJsonArray()
            || delta.getAsJsonArray("path").size() != 1
            || !"resources".equals(delta.getAsJsonArray("path").get(0).getAsString())
            || !delta.has("value") || !delta.get("value").isJsonArray()) {
            return null;
        }
        metadata.put("resources", gson.fromJson(delta.get("value"), List.class));
        String payloadHash = payloadCodec.canonicalize(metadata).checksum().canonicalText();
        AssetTransactionCoordinator.ExpectedState resultLineage = mutation.result().states().get(lineageKey);
        if (!(previousLineage instanceof AssetTransactionCoordinator.Live previousLive)
            || !(resultLineage instanceof AssetTransactionCoordinator.Live resultLive)
            || resultLive.revision() != previousLive.revision() + 1L
            || !matchesProjectMetadataLineageHash(resultLive.hash(), metadataRevision, mutation.mutationId(), payloadHash)) {
            return null;
        }
        String lineageHash = resultLive.hash();
        JsonArray assets = intent.has("assets") && intent.get("assets").isJsonArray()
            ? intent.getAsJsonArray("assets") : new JsonArray();
        JsonObject primary = null;
        boolean exactLineage = false;
        List<JsonObject> sidecars = new ArrayList<>();
        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject asset = element.getAsJsonObject();
            String type = string(asset, "type");
            String id = string(asset, "id");
            if (lineageKey.type().equals(type) && lineageKey.id().equals(id)) {
                exactLineage = exactLineageAsset(asset, previousLive, resultLive, lineageHash);
                continue;
            }
            if (type != null && (type.endsWith(".intent") || type.endsWith(".tombstone")
                || type.startsWith("tombstone:"))) {
                sidecars.add(asset);
                continue;
            }
            if (primary != null) {
                return null;
            }
            primary = asset;
        }
        if (!exactLineage || primary == null) {
            return null;
        }
        String type = string(primary, "type");
        String id = string(primary, "id");
        if (type == null || id == null || type.isBlank() || id.isBlank() || PROJECT_METADATA_TYPE.equals(type)) {
            return null;
        }
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey(type, id);
        AssetTransactionCoordinator.ExpectedState result = mutation.result().states().get(key);
        JsonObject expected = primary.has("expected") && primary.get("expected").isJsonObject()
            ? primary.getAsJsonObject("expected") : null;
        if (expected == null || result == null) {
            return null;
        }
        String expectedKind = string(expected, "kind");
        long expectedRevision = expected.has("revision") ? expected.get("revision").getAsLong() : -1L;
        String operation;
        if ("WRITE".equals(string(primary, "operation")) && "MISSING".equals(expectedKind)
            && expectedRevision == 0L && result instanceof AssetTransactionCoordinator.Live live && live.revision() == 1L) {
            operation = "CREATE";
        } else if ("DELETE".equals(string(primary, "operation")) && "LIVE".equals(expectedKind)
            && expectedRevision > 0L && result instanceof AssetTransactionCoordinator.Deleted deleted
            && deleted.revision() == expectedRevision + 1L) {
            operation = "DELETE";
        } else {
            return null;
        }
        if (!exactLegacyAdminSidecars(sidecars, type, id, operation)) {
            return null;
        }
        ServerResourceLocator resource = new ServerResourceLocator(serverId,
            ContractRef.of(PROTOCOL_OWNER, new ResourceTypeId(type)), id);
        return new LegacyAdminTransition(mutation, resource, operation, expectedRevision, result);
    }

    private boolean exactLegacyAdminSidecars(List<JsonObject> sidecars, String type, String id, String operation) {
        if (CORE_RESOURCE_TYPES.contains(type)) {
            return sidecars.isEmpty() && "CREATE".equals(operation);
        }
        if (ReSyncResourceCatalog.SCOREBOARD.equals(type)) {
            if ("CREATE".equals(operation)) {
                return sidecars.isEmpty();
            }
            if (sidecars.size() != 1) {
                return false;
            }
            JsonObject tombstone = sidecars.getFirst();
            JsonObject expected = tombstone.has("expected") && tombstone.get("expected").isJsonObject()
                ? tombstone.getAsJsonObject("expected") : null;
            return expected != null && ("tombstone:" + type).equals(string(tombstone, "type"))
                && id.equals(string(tombstone, "id")) && "WRITE".equals(string(tombstone, "operation"))
                && "MISSING".equals(string(expected, "kind"));
        }
        int expected = "CREATE".equals(operation) ? 1 : 2;
        if (sidecars.size() != expected) {
            return false;
        }
        boolean intent = false;
        boolean tombstone = false;
        for (JsonObject sidecar : sidecars) {
            if (!id.equals(string(sidecar, "id")) || !"WRITE".equals(string(sidecar, "operation"))
                || !sidecar.has("expected") || !sidecar.get("expected").isJsonObject()) {
                return false;
            }
            String sidecarType = string(sidecar, "type");
            String expectedKind = string(sidecar.getAsJsonObject("expected"), "kind");
            if ((type + ".intent").equals(sidecarType)) {
                intent = !intent && ("CREATE".equals(operation) ? "MISSING" : "LIVE").equals(expectedKind);
            } else if ((type + ".tombstone").equals(sidecarType)) {
                tombstone = !tombstone && "DELETE".equals(operation) && "MISSING".equals(expectedKind);
            } else {
                return false;
            }
        }
        return intent && ("CREATE".equals(operation) || tombstone);
    }

    private boolean exactLineageAsset(JsonObject asset, AssetTransactionCoordinator.Live expected,
                                      AssetTransactionCoordinator.Live result, String payloadHash) {
        if (!"WRITE".equals(string(asset, "operation")) || !payloadHash.equals(string(asset, "payloadHash"))
            || !asset.has("expected") || !asset.get("expected").isJsonObject()) {
            return false;
        }
        JsonObject value = asset.getAsJsonObject("expected");
        return "LIVE".equals(string(value, "kind")) && value.has("revision")
            && value.get("revision").getAsLong() == expected.revision()
            && expected.hash().equals(string(value, "hash")) && result.hash().equals(payloadHash);
    }

    private void importLegacyAdminChain(LegacyAdminChain chain, State current) throws SQLException {
        Map<UUID, LegacyAdminImport> imports = new LinkedHashMap<>();
        Map<String, LegacyAdminTransition> terminal = new LinkedHashMap<>();
        for (LegacyAdminTransition transition : chain.transitions()) {
            terminal.put(transition.resource().canonicalText(), transition);
        }
        for (LegacyAdminTransition transition : terminal.values()) {
            CoreResourceMutationTransition coreTransition = null;
            State state;
            if (isCoreResource(transition.resource())) {
                AssetTransactionCoordinator.ExpectedState result = transition.result();
                CoreGraphResourceAuthority.LegacyCoreRecoverySource source =
                    result instanceof AssetTransactionCoordinator.Live live
                        ? coreAuthority.legacyRecoverySource(transition.resource(), transition.mutation().mutationId(),
                            live.revision(), new ContentHash(live.hash())).orElse(null) : null;
                if (source != null) {
                    state = legacyCoreState(source);
                } else {
                    CoreState core = synchronizeCore(transition.resource());
                    if (core != null && result instanceof AssetTransactionCoordinator.Live live
                        && live.revision() == core.revision()
                        && live.hash().equals(StorageSafety.sha256(coreBoundary.encode(core.decoded())))) {
                        state = corePersistedState(core);
                        coreTransition = coreTransition(core, LEGACY_ACTOR);
                    } else {
                        state = null;
                    }
                }
            } else {
                state = currentState(transition.resource());
            }
            if (exactLegacyTerminalState(transition, state)) {
                imports.put(transition.mutation().mutationId(), new LegacyAdminImport(transition, state, coreTransition));
            }
        }
        long currentMatches = imports.values().stream().filter(value -> value.state().mutationId().equals(current.mutationId())).count();
        long liveCoreMatches = imports.values().stream().filter(value -> !value.state().deleted()
            && CORE_RESOURCE_TYPES.contains(value.state().resource().resourceType().value())).count();
        if (imports.size() != terminal.size() || currentMatches != 1L || liveCoreMatches != 1L
            || imports.values().stream().noneMatch(value -> value.state().resource().resourceType().value().equals(chain.survivingCoreType()))) {
            throw new IllegalStateException("Legacy admin resource recovery does not match current authority: imports="
                + imports.size() + ", terminal=" + terminal.size() + ", current=" + currentMatches + ", core=" + liveCoreMatches
                + ", survivingCore=" + chain.survivingCoreType() + ", importedKeys=" + imports.keySet()
                + ", terminalKeys=" + terminal.keySet());
        }
        for (LegacyAdminTransition transition : chain.transitions()) {
            if (mutation(transition.mutation().mutationId()) != null) {
                throw new IllegalStateException("Legacy admin resource mutation was already admitted");
            }
        }
        LegacyAdminImport coreImport = imports.values().stream()
            .filter(value -> isCoreResource(value.state().resource())).findFirst()
            .orElseThrow(() -> new IllegalStateException("Legacy admin Core recovery source is unavailable"));
        boolean recoverLegacyCore = LEGACY_CORE_PAYLOAD_KIND.equals(coreImport.state().corePayloadKind());
        if (recoverLegacyCore && !legacyAdminRecoveryProfile(chain)) {
            throw new IllegalStateException("Legacy admin Core recovery does not match the disposable probe profile");
        }
        MutationRow pendingRecovery = recoverLegacyCore
            ? legacyAdminRecoveryPending(coreImport.state(), legacyAdminRecoveryMutationId(chain, current, coreImport)) : null;
        try {
            connection.setAutoCommit(false);
            if (!sameResourceState(state(current.resource()), chain.baseline())) {
                throw new IllegalStateException("Legacy admin resource recovery baseline changed during admission");
            }
            for (LegacyAdminTransition transition : chain.transitions()) {
                LegacyAdminTransition terminalTransition = terminal.get(transition.resource().canonicalText());
                LegacyAdminImport terminalImport = imports.get(terminalTransition.mutation().mutationId());
                writeMutation(legacyAdminReceipt(transition, terminalImport.state()), false);
            }
            for (LegacyAdminImport value : imports.values()) {
                writeState(value.state());
                if (value.coreTransition() != null) {
                    writeCoreTransitionOutbox(value.transition().mutation().mutationId(), value.coreTransition());
                }
            }
            writeState(current);
            if (pendingRecovery != null) {
                writeMutation(pendingRecovery, false);
            }
            connection.commit();
        } catch (RuntimeException | SQLException exception) {
            rollback();
            throw exception;
        } finally {
            resetAutoCommit();
        }
        if (pendingRecovery != null) {
            recoverLegacyAdminCore(pendingRecovery);
        }
    }

    private State legacyCoreState(CoreGraphResourceAuthority.LegacyCoreRecoverySource source) {
        return new State(source.resource(), source.revision(), source.mutationId(),
            source.payloadHash().canonicalText(), false, null, ResourceActivationState.ACTIVE,
            source.assetHash().canonicalText(), source.payloadHash().canonicalText(), source.payloadKind());
    }

    private boolean legacyAdminRecoveryProfile(LegacyAdminChain chain) {
        List<String> expected = List.of(
            ReSyncResourceCatalog.TEXT_TEMPLATE + ":CREATE",
            ReSyncResourceCatalog.SCOREBOARD + ":CREATE",
            ReSyncResourceCatalog.MOTD_PROFILE + ":CREATE",
            "flow:CREATE",
            ReSyncResourceCatalog.SCOREBOARD + ":DELETE",
            ReSyncResourceCatalog.MOTD_PROFILE + ":DELETE",
            ReSyncResourceCatalog.TEXT_TEMPLATE + ":DELETE");
        List<String> actual = chain.transitions().stream().map(transition ->
            transition.resource().resourceType().value() + ':' + transition.operation()).toList();
        return "flow".equals(chain.survivingCoreType()) && actual.equals(expected);
    }

    private UUID legacyAdminRecoveryMutationId(LegacyAdminChain chain, State current, LegacyAdminImport coreImport) {
        StringBuilder seed = new StringBuilder("restudio.resync/legacy-admin-core-recovery-v1\n")
            .append(serverId.canonicalText()).append('\n')
            .append(chain.baseline().revision()).append('\n')
            .append(chain.baseline().mutationId()).append('\n')
            .append(chain.baseline().payloadHash()).append('\n')
            .append(current.revision()).append('\n')
            .append(current.mutationId()).append('\n')
            .append(current.payloadHash()).append('\n')
            .append(coreImport.state().resource().canonicalText()).append('\n')
            .append(coreImport.state().assetHash()).append('\n');
        for (LegacyAdminTransition transition : chain.transitions()) {
            seed.append(transition.mutation().result().rootSequence()).append(':')
                .append(transition.mutation().mutationId()).append(':')
                .append(transition.mutation().intentHash()).append('\n');
        }
        return IdentityCodec.deterministicUuid("legacy-admin-core-recovery-v1", seed.toString());
    }

    private MutationRow legacyAdminRecoveryPending(State source, UUID recoveryMutationId) {
        long now = Instant.now().toEpochMilli();
        String fingerprint = StorageSafety.sha256("legacy-admin-core-recovery-v1\n" + source.resource().canonicalText()
            + '\n' + source.revision() + '\n' + source.mutationId() + '\n' + source.assetHash() + '\n'
            + source.corePayloadHash() + '\n' + recoveryMutationId);
        return new MutationRow(recoveryMutationId, LEGACY_ADMIN_RECOVERY_ACTOR, fingerprint, "DELETE",
            source.resource().canonicalText(), source.resource().canonicalText(), null,
            source.resource().canonicalText(), null, source.revision(), source.payloadHash(), Status.PENDING,
            Math.addExact(source.revision(), 1L), recoveryMutationId.toString(), source.payloadHash(), true,
            null, null, 0L, "", "", now, now, source.assetHash(), source.corePayloadHash(),
            source.corePayloadKind(), source.assetHash(), source.corePayloadHash(), source.corePayloadKind());
    }

    private boolean legacyAdminRecoveryRow(MutationRow row) {
        return LEGACY_ADMIN_RECOVERY_ACTOR.equals(row.actorId()) && "DELETE".equals(row.operation())
            && row.resultDeleted() && LEGACY_CORE_PAYLOAD_KIND.equals(row.preconditionCorePayloadKind())
            && LEGACY_CORE_PAYLOAD_KIND.equals(row.resultCorePayloadKind());
    }

    private void recoverLegacyAdminCore(MutationRow row) {
        if (!legacyAdminRecoveryRow(row)) {
            throw new IllegalStateException("Legacy admin Core recovery receipt is invalid");
        }
        ServerResourceLocator resource = locator(row.responseResource());
        State source = state(resource);
        if (source == null || source.deleted() || source.revision() != row.expectedRevision()
            || !source.payloadHash().equals(row.preconditionHash())
            || !source.assetHash().equals(row.preconditionAssetHash())
            || !source.corePayloadHash().equals(row.preconditionCorePayloadHash())
            || !source.corePayloadKind().equals(row.preconditionCorePayloadKind())) {
            throw new IllegalStateException("Legacy admin Core recovery precondition changed");
        }
        CoreGraphResourceAuthority.LegacyCoreRecoveryResult recovered = coreAuthority.recoverLegacyDelete(resource,
            source.mutationId(), source.revision(), new ContentHash(source.assetHash()), row.mutationId());
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = recovered.tombstone();
        if (!resource.equals(tombstone.resource()) || tombstone.revision() != row.resultRevision()
            || !row.mutationId().equals(tombstone.mutationId()) || !tombstone.deleted()
            || !new ContentHash(source.corePayloadHash()).equals(tombstone.priorPayloadHash())) {
            throw new IllegalStateException("Legacy admin Core recovery tombstone is not authoritative");
        }
        CoreState deleted = new CoreState(resource, tombstone.revision(), tombstone.mutationId(),
            source.payloadHash(), source.assetHash(), source.corePayloadHash(), source.corePayloadKind(), true,
            null, null, null);
        verifyCorePostApply(command(row), coreOutcome(row, coreState(source)), deleted);
        commitCoreApplied(row, deleted);
    }

    private State currentState(ServerResourceLocator resource) {
        if (isCoreResource(resource)) {
            return coreStateOrNull(resource);
        }
        FlowResourceAdapter<Object> resourceAdapter = adapter(resource);
        return resourceAdapter == null ? null : authoritativeState(resource, resourceAdapter);
    }

    private boolean exactLegacyTerminalState(LegacyAdminTransition transition, State state) {
        AssetTransactionCoordinator.ExpectedState result = transition.result();
        return state != null && state.resource().equals(transition.resource())
            && state.mutationId().equals(transition.mutation().mutationId())
            && state.revision() == result.revision()
            && state.deleted() == (result instanceof AssetTransactionCoordinator.Deleted);
    }

    private boolean matchesProjectMetadataLineageHash(String hash, long revision, UUID mutationId, String payloadHash) {
        JsonObject lineage = projectMetadataLineage(revision, mutationId, payloadHash);
        return hash.equals(StorageSafety.sha256(gson.toJson(lineage).getBytes(StandardCharsets.UTF_8)))
            || hash.equals(StorageSafety.sha256(PRETTY_LINEAGE_GSON.toJson(lineage).getBytes(StandardCharsets.UTF_8)));
    }

    private JsonObject projectMetadataLineage(long revision, UUID mutationId, String payloadHash) {
        JsonObject lineage = new JsonObject();
        lineage.addProperty("format", "project-metadata-lineage-v1");
        lineage.addProperty("type", PROJECT_METADATA_TYPE);
        lineage.addProperty("id", serverId.canonicalText());
        lineage.addProperty("revision", revision);
        lineage.addProperty("mutationId", mutationId.toString());
        lineage.addProperty("payloadHash", payloadHash);
        lineage.addProperty("deleted", false);
        return lineage;
    }

    private MutationRow legacyAdminReceipt(LegacyAdminTransition transition, State authoritative) {
        AssetTransactionCoordinator.ExpectedState result = transition.result();
        boolean deleted = result instanceof AssetTransactionCoordinator.Deleted;
        boolean core = isCoreResource(transition.resource());
        long now = Instant.now().toEpochMilli();
        return new MutationRow(transition.mutation().mutationId(), LEGACY_ACTOR, transition.mutation().intentHash(),
            transition.operation(), transition.resource().canonicalText(), transition.resource().canonicalText(), null,
            transition.resource().canonicalText(), null, transition.expectedRevision(), deleted ? authoritative.payloadHash() : "",
            Status.APPLIED, result.revision(), transition.mutation().mutationId().toString(),
            authoritative.payloadHash(), deleted,
            core && !deleted ? authoritative.activationState() : null, null, nextSequence(), "", "", now, now,
            null, null, null, core ? authoritative.assetHash() : null, core ? authoritative.corePayloadHash() : null,
            core ? authoritative.corePayloadKind() : null);
    }

    private static String string(JsonObject value, String name) {
        return value != null && value.has(name) && value.get(name).isJsonPrimitive() ? value.get(name).getAsString() : null;
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private State coupledProjectMetadataState(MutationRow pending) {
        ServerResourceLocator primary = locator(pending.responseResource());
        if (isProjectMetadata(primary)) {
            return null;
        }
        ServerResourceLocator resource = projectMetadataResource();
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            return null;
        }
        State current = authoritativeState(resource, adapter);
        if (current == null) {
            return null;
        }
        if (current.deleted()) {
            throw new IllegalStateException("Project metadata deletion cannot accompany a resource mutation");
        }
        State persisted = state(current.resource());
        if (persisted != null && sameResourceState(persisted, current)) {
            return null;
        }
        if (persisted != null && (!pending.mutationId().equals(current.mutationId())
            || current.revision() > Math.addExact(persisted.revision(), 1L))) {
            State historical = historicalCoupledProjectMetadataState(pending);
            if (historical != null) {
                return historical;
            }
        }
        if (pending.mutationId().equals(current.mutationId())) {
            if (persisted == null) {
                if (current.revision() != 1L) {
                    throw new IllegalStateException("The coupled project metadata baseline is unavailable");
                }
            } else {
                try {
                    if (current.revision() != Math.addExact(persisted.revision(), 1L)) {
                        throw new IllegalStateException("The coupled project metadata revision did not advance exactly once");
                    }
                } catch (ArithmeticException exception) {
                    throw new IllegalStateException("The coupled project metadata revision is exhausted", exception);
                }
            }
            return current;
        }
        State repaired = coupledProjectMetadataRepairs.get(pending.mutationId());
        if (repaired != null) {
            if (!sameResourceState(repaired, current) || persisted == null
                || current.revision() != Math.addExact(persisted.revision(), 1L)) {
                throw new IllegalStateException("The coupled project metadata repair does not match durable authority");
            }
            return current;
        }
        if (persisted == null) {
            requireProjectMetadataAdvanceReceipt(current);
            return current;
        }
        requireForwardProjectMetadataState(persisted, current);
        return current;
    }

    private State historicalCoupledProjectMetadataState(MutationRow pending) {
        State baseline = state(projectMetadataResource());
        ServerResourceLocator primary = locator(pending.responseResource());
        Path assetsRoot = activeScopeRoot.resolve("assets").toAbsolutePath().normalize();
        if (baseline == null || baseline.deleted() || baseline.payload() == null || isProjectMetadata(primary)
            || !Files.isDirectory(assetsRoot.resolve(".asset-coordinator"), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assetsRoot, gson)) {
            AssetTransactionCoordinator.MutationView committed = coordinator.mutation(pending.mutationId()).orElse(null);
            if (committed == null) {
                return null;
            }
            AssetTransactionCoordinator.AssetKey primaryKey = new AssetTransactionCoordinator.AssetKey(
                primary.resourceType().value(), primary.id());
            AssetTransactionCoordinator.AssetKey lineageKey = new AssetTransactionCoordinator.AssetKey(
                PROJECT_METADATA_LINEAGE_TYPE, PROJECT_METADATA_LINEAGE_ID);
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
            AssetTransactionCoordinator.ExpectedState primaryState = committed.result().states().get(primaryKey);
            AssetTransactionCoordinator.ExpectedState lineageState = committed.result().states().get(lineageKey);
            if (primaryState == null || primaryState.revision() != pending.resultRevision()
                || !primaryState.equals(snapshot.states().get(primaryKey))
                || !snapshot.mutationValue(primaryKey).filter(pending.mutationId().toString()::equals).isPresent()
                || !(lineageState instanceof AssetTransactionCoordinator.Live lineage)) {
                return null;
            }
            JsonObject intent = committed.intent();
            JsonObject expectedProject = intent.getAsJsonObject("expectedProject");
            AssetProjectMetadata previousProject = AssetProjectMetadata.of(JsonParser.parseString(baseline.payload()).getAsJsonObject());
            if (expectedProject == null || !expectedProject.has("revision")) {
                return null;
            }
            String expectedProjectHash = string(expectedProject, "hash");
            boolean unchangedProject = committed.result().project().hash().equals(expectedProjectHash)
                && committed.result().project().revision() == expectedProject.get("revision").getAsLong()
                && committed.projectAfter().canonicalJson().equals(previousProject.canonicalJson());
            if (!previousProject.hash().equals(expectedProjectHash) && !unchangedProject) {
                return null;
            }
            long revision = Math.addExact(baseline.revision(), 1L);
            CanonicalPayload<Map<String, Object>> payload = canonical(gson.toJson(committed.projectAfter().document()));
            String hash = payload.checksum().canonicalText();
            if (!matchesProjectMetadataLineageHash(lineage.hash(), revision, pending.mutationId(), hash)) {
                return null;
            }
            JsonArray assets = intent.getAsJsonArray("assets");
            if (assets == null) {
                return null;
            }
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                if (!lineageKey.type().equals(string(asset, "type")) || !lineageKey.id().equals(string(asset, "id"))) {
                    continue;
                }
                JsonObject expected = asset.getAsJsonObject("expected");
                if (expected == null || !"LIVE".equals(string(expected, "kind")) || !expected.has("revision")) {
                    return null;
                }
                long expectedRevision = expected.get("revision").getAsLong();
                String expectedHash = string(expected, "hash");
                if (expectedHash == null || lineage.revision() != Math.addExact(expectedRevision, 1L)
                    || !matchesProjectMetadataLineageHash(expectedHash, baseline.revision(), baseline.mutationId(), baseline.payloadHash())
                    || !exactLineageAsset(asset, new AssetTransactionCoordinator.Live(expectedRevision, expectedHash), lineage, lineage.hash())) {
                    return null;
                }
                return new State(baseline.resource(), revision, pending.mutationId(), hash, false,
                    canonicalInput(payload), ResourceActivationState.ACTIVE);
            }
            return null;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed To Read Coupled Resource Mutation History", exception);
        }
    }

    private void recoverCoupledProjectMetadataLineage(MutationRow pending, State applied) {
        if (historicalCoupledProjectMetadataState(pending) != null) {
            return;
        }
        ServerResourceLocator primary = locator(pending.responseResource());
        if (isProjectMetadata(primary) || applied == null || applied.deleted() || applied.payloadHash() == null) {
            return;
        }
        ServerResourceLocator resource = projectMetadataResource();
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            return;
        }
        State persisted = state(resource);
        FlowResourceMutationStamp stamp = readStamp(resource, adapter);
        if (persisted == null || stamp == null || stamp.deleted()) {
            return;
        }
        Object value = adapter.get(resource.id());
        if (value == null) {
            throw new IllegalStateException("Project metadata payload is unavailable during lineage recovery");
        }
        CanonicalPayload<Map<String, Object>> canonical = canonical(adapter.serialize(value));
        String currentHash = canonical.checksum().canonicalText();
        if (currentHash.equals(persisted.payloadHash())) {
            return;
        }
        long revision;
        try {
            revision = Math.addExact(persisted.revision(), 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Project metadata lineage revision is exhausted", exception);
        }
        boolean baseline = stamp.revision() == persisted.revision()
            && stamp.mutationId().equals(persisted.mutationId())
            && stamp.payloadHash().equals(persisted.payloadHash());
        boolean committedRepair = stamp.revision() == revision && stamp.payloadHash().equals(currentHash);
        if (!baseline && !committedRepair) {
            throw new IllegalStateException("Project metadata lineage recovery candidate is ambiguous");
        }
        FlowResourceMutationStamp repairedStamp = adapter.recoverProjectMetadataLineage(pending.mutationId(),
            primary.resourceType().value(), primary.id(), applied.revision(), applied.payloadHash(),
            persisted.revision(), persisted.mutationId(), persisted.payloadHash());
        if (repairedStamp == null || repairedStamp.deleted() || repairedStamp.revision() != revision
            || !repairedStamp.payloadHash().equals(currentHash)) {
            throw new IllegalStateException("Project metadata lineage recovery did not publish exact authority");
        }
        State repaired = authoritativeState(resource, adapter);
        if (repaired == null || repaired.deleted() || repaired.revision() != revision
            || !repaired.mutationId().equals(repairedStamp.mutationId())
            || !repaired.payloadHash().equals(currentHash)) {
            throw new IllegalStateException("Project metadata lineage recovery is not authoritative");
        }
        coupledProjectMetadataRepairs.put(pending.mutationId(), repaired);
    }

    private void reconcileProjectMetadataState() {
        ServerResourceLocator resource = projectMetadataResource();
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            return;
        }
        State current = authoritativeState(resource, adapter);
        State persisted = state(resource);
        if (current == null) {
            if (persisted != null) {
                throw new IllegalStateException("The project metadata authority is missing a durable resource");
            }
            return;
        }
        if (persisted == null) {
            bootstrapState(current);
            return;
        }
        if (sameResourceState(persisted, current)) {
            return;
        }
        State recovered = recoverUnreceiptedProjectMetadataState(persisted, current, adapter);
        if (recovered != null) {
            persistReconciledProjectMetadata(recovered);
            return;
        }
        State imported = recoverLegacyAdminProjectMetadataChain(persisted, current);
        if (imported != null) {
            return;
        }
        requireForwardProjectMetadataState(persisted, current);
        persistReconciledProjectMetadata(current);
    }

    private boolean hasPendingMutation(ServerResourceLocator resource) {
        String canonical = resource.canonicalText();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM resource_mutation_receipt
            WHERE status = 'PENDING'
                AND (requested_resource = ? OR response_resource = ? OR source_resource = ? OR target_resource = ?)
            LIMIT 1
            """)) {
            statement.setString(1, canonical);
            statement.setString(2, canonical);
            statement.setString(3, canonical);
            statement.setString(4, canonical);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Read Pending Resource Mutation", exception);
        }
    }

    private boolean sameResourceState(State first, State second) {
        return first != null && second != null && first.resource().equals(second.resource()) && sameState(first, second)
            && Objects.equals(first.payload(), second.payload());
    }

    private boolean isProjectMetadata(ServerResourceLocator resource) {
        return resource != null && PROJECT_METADATA_TYPE.equals(resource.resourceType().value());
    }

    private State synchronizeExternal(ServerResourceLocator resource, UUID mutationId, long expectedRevision,
                                      ContentHash expectedHash, boolean deleted, Map<String, Object> expectedPayload,
                                      ResourceActivationState expectedActivationState) {
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            throw new IllegalStateException("Resource adapter is unavailable: " + resource.resourceType().value());
        }
        FlowResourceMutationStamp stamp = readStamp(resource, adapter);
        verifyStamp(resource, stamp, expectedRevision, mutationId, expectedHash, deleted);
        if (deleted) {
            return new State(resource, stamp.revision(), stamp.mutationId(), stamp.payloadHash(), true, null, null);
        }
        Object value = adapter.get(resource.id());
        if (value == null) {
            throw new IllegalStateException("The mutation boundary did not publish its resource");
        }
        String serialized = adapter.serialize(value);
        CanonicalPayload<Map<String, Object>> canonical = canonical(serialized);
        if (expectedHash != null && !expectedHash.equals(canonical.checksum())) {
            throw new IllegalStateException("The authoritative adapter published a different resource payload");
        }
        ResourceActivationState activationState = activationState(canonical.value(), expectedActivationState == null
            ? ResourceActivationState.ACTIVE : expectedActivationState);
        if (expectedActivationState != null && activationState != expectedActivationState) {
            throw new IllegalStateException("The mutation boundary did not publish its requested activation state");
        }
        if (!stamp.payloadHash().equals(canonical.checksum().canonicalText())) {
            throw new IllegalStateException("The authoritative mutation stamp does not match its serialized resource");
        }
        return new State(resource, stamp.revision(), stamp.mutationId(), stamp.payloadHash(), false, canonicalInput(canonical), activationState);
    }

    private void completePostCommitRecovery(ServerResourceLocator resource, UUID mutationId, long revision, boolean deleted) {
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null) {
            throw new IllegalStateException("Resource adapter is unavailable: " + resource.resourceType().value());
        }
        adapter.completePostCommitRecovery(resource.id(), mutationId, revision, deleted);
    }

    private State bootstrap(ServerResourceLocator resource, FlowResourceMutationStamp stamp, Object value,
                            FlowResourceAdapter<Object> adapter) {
        CanonicalPayload<Map<String, Object>> canonical = canonical(adapter.serialize(value));
        if (!stamp.payloadHash().equals(canonical.checksum().canonicalText())) {
            throw new IllegalStateException("The authoritative mutation stamp does not match the live resource");
        }
        State state = new State(resource, stamp.revision(), stamp.mutationId(), stamp.payloadHash(), false, canonicalInput(canonical),
            activationState(canonical.value(), ResourceActivationState.ACTIVE));
        return bootstrapState(state);
    }

    private State bootstrap(ServerResourceLocator resource, FlowResourceMutationStamp stamp, Object value) {
        State state = new State(resource, stamp.revision(), stamp.mutationId(), stamp.payloadHash(), true, null, null);
        return bootstrapState(state);
    }

    private State bootstrapState(State state) {
        try {
            connection.setAutoCommit(false);
            writeState(state);
            connection.commit();
            return state;
        } catch (SQLException exception) {
            rollback();
            throw new IllegalStateException("Failed To Bootstrap Durable Resource State", exception);
        } finally {
            resetAutoCommit();
        }
    }

    private void validatePayloadIdentity(Command command, FlowResourceAdapter<Object> adapter) {
        if (!"CREATE".equals(command.operationName()) && !"SAVE".equals(command.operationName())) {
            return;
        }
        if (command.payload() == null) {
            throw new IllegalArgumentException("Resource payload is required");
        }
        Object value = adapter.deserialize(gson.toJson(command.payload()));
        String payloadId = value == null ? null : adapter.id(value);
        if (payloadId == null || !command.responseResource().id().equals(payloadId)) {
            throw new IllegalArgumentException("Resource payload ID does not match its typed locator");
        }
    }

    private FlowOperationResult<?> applyExternal(Command command, Outcome outcome, String clientId,
                                                 FlowResourceMutationLease lease) {
        FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", clientId, lease,
            command.mutationId(), "CREATE".equals(command.operationName()) ? outcome.resultRevision() - 1L : command.expectedRevision(),
            outcome.resultHash() == null ? "" : outcome.resultHash().canonicalText());
        String type = command.responseResource().resourceType().value();
        if ("CREATE".equals(command.operationName())) {
            ExternalPayload payload = deserializeExternalPayload(command, outcome);
            if (payload.failure() != null) {
                return payload.failure();
            }
            return registry.create(type, payload.value(), context);
        }
        if ("SAVE".equals(command.operationName())) {
            ExternalPayload payload = deserializeExternalPayload(command, outcome);
            if (payload.failure() != null) {
                return payload.failure();
            }
            return registry.update(type, payload.value(), context);
        }
        if ("DELETE".equals(command.operationName())) {
            return registry.delete(type, command.responseResource().id(), context);
        }
        if ("ACTIVATE".equals(command.operationName())) {
            String serialized = registry.setEnabledAuthoritative(context, type, command.responseResource().id(),
                command.activationState() == ResourceActivationState.ACTIVE);
            return FlowOperationResult.success(serialized);
        }
        return registry.duplicate(type, command.source().id(), command.responseResource().id(), context);
    }

    private ExternalPayload deserializeExternalPayload(Command command, Outcome outcome) {
        try {
            return new ExternalPayload(adapter(command.responseResource()).deserialize(gson.toJson(outcome.resultPayload())), null);
        } catch (RuntimeException failure) {
            return new ExternalPayload(null, FlowOperationResult.failure("RESOURCE_PAYLOAD_INVALID", safeMessage(failure),
                Map.of("failureType", failure.getClass().getSimpleName())));
        }
    }

    private record ExternalPayload(Object value, FlowOperationResult<?> failure) {
    }

    private String registryOperation(Command command) {
        return switch (command.operationName()) {
            case "SAVE" -> "update";
            case "ACTIVATE" -> "save";
            default -> command.operationName().toLowerCase(Locale.ROOT);
        };
    }

    private boolean supportsExactMutation(Command command) {
        FlowResourceAdapter<Object> responseAdapter = adapter(command.responseResource());
        if (!supportsExactAdapter(responseAdapter, registryOperation(command))) {
            return false;
        }
        if ("DUPLICATE".equals(command.operationName())) {
            FlowResourceAdapter<Object> sourceAdapter = adapter(command.source());
            return supportsExactAdapter(sourceAdapter, "duplicate");
        }
        return true;
    }

    private boolean supportsExactAdapter(FlowResourceAdapter<Object> adapter, String operation) {
        return adapter != null && adapter.durable() && adapter.supportsAuthoritativeMutationIdentity()
            && adapter.supportedOperations().contains(operation);
    }

    private List<FlowResourceKey> mutationKeys(Command command) {
        try {
            List<FlowResourceKey> keys = new ArrayList<>();
            if (command.source() != null) {
                keys.add(new FlowResourceKey(command.source().resourceType().value(), command.source().id()));
            }
            keys.add(new FlowResourceKey(command.responseResource().resourceType().value(), command.responseResource().id()));
            FlowResourceAdapter<Object> responseAdapter = adapter(command.responseResource());
            if (command.presentation() != null || "DUPLICATE".equals(command.operationName())
                && responseAdapter != null && responseAdapter.supportsAggregateCreate()) {
                keys.add(new FlowResourceKey(PROJECT_METADATA_TYPE, serverId.canonicalText()));
            }
            return keys.stream().sorted().toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private boolean hasDuplicateKeys(List<FlowResourceKey> keys) {
        return keys.size() != new LinkedHashSet<>(keys).size();
    }

    private boolean hasOtherPendingMutation(Command command) {
        Set<String> resources = new LinkedHashSet<>();
        resources.add(command.resource().canonicalText());
        resources.add(command.responseResource().canonicalText());
        if (command.source() != null) {
            resources.add(command.source().canonicalText());
        }
        if (command.target() != null) {
            resources.add(command.target().canonicalText());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT requested_resource, response_resource, source_resource, target_resource
            FROM resource_mutation_receipt
            WHERE status = 'PENDING' AND mutation_id <> ?
            """)) {
            statement.setString(1, command.mutationId().toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (resources.contains(result.getString("requested_resource"))
                        || resources.contains(result.getString("response_resource"))
                        || resources.contains(result.getString("source_resource"))
                        || resources.contains(result.getString("target_resource"))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Inspect Pending Resource Mutations", exception);
        }
    }

    private ProtocolEnvelopeDispatchResult storedResponse(ProtocolEnvelope<Map<String, Object>> envelope,
                                                           ResourceOperationKind kind, MutationRow row) {
        if (row.status() == Status.REVISION_CONFLICT || row.status() == Status.CONFLICT || row.status() == Status.IDEMPOTENCY_CONFLICT) {
            return conflict(envelope, kind, locator(row.responseResource()), document(row));
        }
        if (row.status() == Status.NOT_FOUND) {
            return unavailable(ProtocolRejectionCode.RESOURCE_NOT_FOUND, "Resource was not found");
        }
        if (row.status() == Status.REJECTED) {
            return unavailable(ProtocolRejectionCode.fromResourceError(row.errorCode()), row.errorMessage());
        }
        if (row.status() == Status.PENDING) {
            return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                "Resource mutation is awaiting durable recovery");
        }
        ResourceDocument<Map<String, Object>> document = document(row);
        if (legacyInactive(envelope, document)) {
            return rejectInactiveLegacyResource();
        }
        AggregateReceipt aggregate = aggregateReceipt(row.mutationId());
        if (aggregate != null) {
            if (kind != ResourceOperationKind.CREATE || aggregate.metadata() == null) {
                throw new IllegalStateException("Aggregate resource create receipt is incomplete");
            }
            if (!publishAggregateCommitted(row)) {
                return unavailable(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING,
                    "Resource mutation publication is awaiting retry");
            }
            ResourceCreateResult result = new ResourceCreateResult(document, document(aggregate.metadata()), aggregate.presentation());
            return ProtocolEnvelopeDispatchResult.handled(response(envelope, kind, document, ProtocolEnvelope.Kind.ACK,
                ProtocolEnvelope.Status.OK, new ProtocolBody.ResourceCreateResponse(result, bodyUnknown(envelope))));
        }
        return ProtocolEnvelopeDispatchResult.handled(response(envelope, kind, document, ProtocolEnvelope.Kind.ACK,
            ProtocolEnvelope.Status.OK, new ProtocolBody.ResourceDocumentResponse(kind, document, bodyUnknown(envelope))));
    }

    private boolean publishAggregateCommitted(MutationRow row) {
        long started = TemporaryLifecycleDiagnostics.start();
        try {
            if (aggregatePublished(row.mutationId())) {
                TemporaryLifecycleDiagnostics.event("aggregate_create_publication", started,
                    TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "already_published"));
                return true;
            }
            aggregateCreateStorage.publishCommitted(locator(row.responseResource()), row.mutationId());
            markAggregatePublished(row.mutationId());
            TemporaryLifecycleDiagnostics.event("aggregate_create_publication", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "published"));
            return true;
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("aggregate_create_publication", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity(row), "outcome", "deferred",
                    "failure", exception.getClass().getSimpleName()));
            return false;
        }
    }

    private boolean aggregatePublished(UUID mutationId) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT published FROM resource_create_aggregate_receipt WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Aggregate resource create receipt is missing");
                }
                return result.getInt(1) != 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Read Aggregate Resource Publication State", exception);
        }
    }

    private void markAggregatePublished(UUID mutationId) {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE resource_create_aggregate_receipt SET published = 1 WHERE mutation_id = ? AND published = 0")) {
            statement.setString(1, mutationId.toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Aggregate resource publication state was not advanced");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Persist Aggregate Resource Publication State", exception);
        }
    }

    private ProtocolEnvelopeDispatchResult conflict(ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperationKind kind,
                                                     ServerResourceLocator resource, ResourceDocument<Map<String, Object>> current) {
        if (legacyInactive(envelope, current)) {
            return rejectInactiveLegacyResource();
        }
        return ProtocolEnvelopeDispatchResult.handled(response(envelope, kind, resource,
            current == null ? 0 : current.revision(), current == null ? null : current.mutationId(), current == null ? null : current.payloadHash(),
            current != null && current.deleted(), ProtocolEnvelope.Kind.CONFLICT, ProtocolEnvelope.Status.CONFLICT,
            new ProtocolBody.ConflictResponse(resource, current, bodyUnknown(envelope))));
    }

    private boolean legacyInactive(ProtocolEnvelope<Map<String, Object>> envelope, ResourceDocument<?> document) {
        return !supportsResourceActivation(envelope) && isInactive(document);
    }

    private boolean legacyInactive(ProtocolEnvelope<Map<String, Object>> envelope, ResourceActivationState activationState,
                                   boolean deleted) {
        return !deleted && !supportsResourceActivation(envelope) && activationState == ResourceActivationState.INACTIVE;
    }

    private boolean supportsResourceActivation(ProtocolEnvelope<?> envelope) {
        return envelope != null && ProtocolEnvelope.supportsResourceActivation(envelope.contractVersion(), envelope.capabilities());
    }

    private boolean isInactive(ResourceDocument<?> document) {
        return document != null && !document.deleted() && document.activationState() == ResourceActivationState.INACTIVE;
    }

    private ProtocolEnvelopeDispatchResult rejectInactiveLegacyResource() {
        return unavailable(ProtocolRejectionCode.RESOURCE_OPERATION_UNSUPPORTED,
            "Inactive resources require generic resource contract 1.1 and resource_activation capability");
    }

    private ProtocolEnvelopeDispatchResult unavailable(ProtocolRejectionCode code, String message) {
        ProtocolRejectionCode rejection = Objects.requireNonNull(code, "Rejection code is required");
        return ProtocolEnvelopeDispatchResult.rejected(rejection,
            message == null || message.isBlank() ? rejection.legacyValue() : message);
    }

    private ProtocolEnvelopeDispatchResult actorConflict() {
        return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_MUTATION_ACTOR_CONFLICT,
            "Mutation ID is already owned by another actor");
    }

    private ProtocolEnvelope<Map<String, Object>> response(ProtocolEnvelope<Map<String, Object>> request, ResourceOperationKind kind,
                                                            ServerResourceLocator resource, long revision, UUID mutationId, ContentHash hash,
                                                            boolean deleted, ProtocolEnvelope.Kind envelopeKind, ProtocolEnvelope.Status status,
                                                            ProtocolBody body) {
        Set<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>(request.capabilities());
        capabilities.add(ContractRef.of(PROTOCOL_OWNER, new CapabilityId("resources")));
        return new ProtocolEnvelope<>(envelopeKind, request.contractVersion(), UUID.randomUUID(), request.requestId(), request.correlationId(), request.traceId(),
            serverId, resource, revision, authorityEpoch.current(), mutationId, ContractRef.of(PROTOCOL_OWNER, new OperationId("resource." + kind.name().toLowerCase(Locale.ROOT))),
            capabilities, request.payloadType(), null, hash, deleted, null, null, null, null, null, request.sequence(), status, List.of(), request.unknown(), body);
    }

    private boolean requiresMutationEpoch(ResourceOperation operation) {
        return switch (operation.kind()) {
            case CREATE, SAVE, DELETE, DUPLICATE, RENAME, MOVE, ACTIVATE -> true;
            default -> false;
        };
    }

    private ProtocolEnvelope<Map<String, Object>> response(ProtocolEnvelope<Map<String, Object>> request, ResourceOperationKind kind,
                                                            ResourceDocument<Map<String, Object>> document, ProtocolEnvelope.Kind envelopeKind,
                                                            ProtocolEnvelope.Status status, ProtocolBody body) {
        return response(request, kind, document == null ? null : document.resource(), document == null ? 0 : document.revision(),
            document == null ? null : document.mutationId(), document == null ? null : document.payloadHash(), document != null && document.deleted(),
            envelopeKind, status, body);
    }

    private ResourceDocument<Map<String, Object>> document(MutationRow row) {
        ServerResourceLocator resource = locator(row.responseResource());
        if (isCoreResource(resource)) {
            if (row.resultDeleted()) {
                return ResourceDocument.tombstone(resource, row.resultRevision(), UUID.fromString(row.resultMutationId()),
                    new ContentHash(row.resultCorePayloadHash()), "server");
            }
            if (row.resultPayload() != null) {
                Map<String, Object> payload = payload(gson.fromJson(row.resultPayload(), Map.class));
                return ResourceDocument.live(resource, row.resultRevision(), UUID.fromString(row.resultMutationId()),
                    payloadCodec.canonicalize(payload), row.resultActivationState(), "server");
            }
            return document(synchronizeCore(resource));
        }
        ContentHash hash = new ContentHash(row.resultHash());
        if (row.resultDeleted()) {
            return ResourceDocument.tombstone(resource, row.resultRevision(), UUID.fromString(row.resultMutationId()), hash, "server");
        }
        Map<String, Object> payload = row.resultPayload() == null ? Map.of() : payload(gson.fromJson(row.resultPayload(), Map.class));
        return ResourceDocument.live(resource, row.resultRevision(), UUID.fromString(row.resultMutationId()), payloadCodec.canonicalize(payload),
            row.resultActivationState() == null ? activationState(payload, ResourceActivationState.ACTIVE) : row.resultActivationState(), "server");
    }

    private ResourceDocument<Map<String, Object>> document(State state) {
        if (state == null) {
            return null;
        }
        if (isCoreResource(state.resource())) {
            if (state.deleted()) {
                return ResourceDocument.tombstone(state.resource(), state.revision(), state.mutationId(),
                    new ContentHash(state.corePayloadHash()), "server");
            }
            CoreState current = synchronizeCore(state.resource());
            return document(current);
        }
        if (state.deleted()) {
            return ResourceDocument.tombstone(state.resource(), state.revision(), state.mutationId(), new ContentHash(state.payloadHash()), "server");
        }
        Map<String, Object> payload = payload(gson.fromJson(state.payload(), Map.class));
        CanonicalPayload<Map<String, Object>> canonical = payloadCodec.canonicalize(payload);
        if (!state.payloadHash().equals(canonical.checksum().canonicalText())) {
            throw new IllegalStateException("Stored resource state payload does not match its authoritative hash");
        }
        return ResourceDocument.live(state.resource(), state.revision(), state.mutationId(), canonical, state.activationState(), "server");
    }

    private FlowResourceAdapter<Object> adapter(ServerResourceLocator resource) {
        if (resource == null || !serverId.equals(resource.serverId()) || isCoreResource(resource)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        FlowResourceAdapter<Object> adapter = (FlowResourceAdapter<Object>) registry.get(resource.resourceType().value());
        return adapter;
    }

    private FlowResourceMutationStamp readStamp(ServerResourceLocator resource, FlowResourceAdapter<Object> adapter) {
        if (!adapter.supportsAuthoritativeMutationIdentity()) {
            throw new IllegalStateException(FlowResourceAdapter.AUTHORITATIVE_MUTATION_IDENTITY_UNAVAILABLE);
        }
        FlowResourceMutationStamp stamp = adapter.readMutationStamp(resource.id());
        if (stamp != null && (!resource.resourceType().value().equals(stamp.type()) || !resource.id().equals(stamp.id()))) {
            throw new IllegalStateException("The authoritative mutation stamp does not match the typed resource locator");
        }
        return stamp;
    }

    private void verifyStamp(ServerResourceLocator resource, FlowResourceMutationStamp stamp, long expectedRevision,
                              UUID expectedMutationId, ContentHash expectedHash, boolean expectedDeleted) {
        if (stamp == null) {
            throw new IllegalStateException("The authoritative adapter did not publish a mutation stamp");
        }
        if (!resource.resourceType().value().equals(stamp.type()) || !resource.id().equals(stamp.id())
            || stamp.revision() != expectedRevision || !expectedMutationId.equals(stamp.mutationId())
            || expectedHash == null || !expectedHash.canonicalText().equals(stamp.payloadHash())
            || stamp.deleted() != expectedDeleted) {
            throw new IllegalStateException("The authoritative mutation stamp does not match the durable mutation outcome");
        }
    }

    private boolean ownsType(ContractRef<ResourceTypeId> type) {
        if (type == null || type.id() == null || type.owner() == null) {
            return false;
        }
        if (CORE_RESOURCE_TYPES.contains(type.id().value())) {
            return PROTOCOL_OWNER.equals(type.owner());
        }
        String registeredOwner = registry.owner(type.id().value());
        if (registeredOwner == null || registeredOwner.isBlank()) {
            return false;
        }
        String requestedOwner = type.owner().canonicalText();
        return requestedOwner.equals(registeredOwner)
            || "builtin".equals(registeredOwner) && PROTOCOL_OWNER.canonicalText().equals(requestedOwner);
    }

    private boolean validResourceEnvelope(ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperation operation) {
        if (operation == null || envelope == null || envelope.kind() != ProtocolEnvelope.Kind.REQUEST
            || (operation instanceof ResourceActivateRequest
                ? !ProtocolEnvelope.supportsResourceActivation(envelope.contractVersion(), envelope.capabilities())
                : !ReSyncProtocolContract.supportsGenericResourceContract(envelope.contractVersion()))
            || !DOCUMENT_TYPE.equals(envelope.payloadType())
            || envelope.operation() == null || !PROTOCOL_OWNER.equals(envelope.operation().owner())
            || !(envelope.body() instanceof ProtocolBody.ResourceRequest request)
            || !Objects.equals(request.operation(), operation)
            || !capabilitiesSupported(envelope.capabilities())) {
            return false;
        }
        return ("resource." + operation.kind().name().toLowerCase(Locale.ROOT))
            .equals(envelope.operation().id().value());
    }

    private boolean capabilitiesSupported(Set<ContractRef<CapabilityId>> capabilities) {
        return capabilities != null && capabilities.stream().allMatch(capability -> capability != null
            && PROTOCOL_OWNER.equals(capability.owner()) && SUPPORTED_CAPABILITIES.contains(capability.id().value()));
    }

    private ServerResourceLocator operationResource(ResourceOperation operation) {
        if (operation == null) {
            return null;
        }
        return switch (operation) {
            case ResourceCreateRequest<?> create -> create.resource();
            case ResourceSaveRequest<?> save -> save.resource();
            case ResourceDeleteRequest delete -> delete.resource();
            case ResourceDuplicateRequest duplicate -> duplicate.target();
            case ResourceActivateRequest activate -> activate.resource();
            default -> null;
        };
    }

    private boolean validMutationLocator(ResourceOperation operation) {
        if (!validProjectMetadataLocator(operationResource(operation))) {
            return false;
        }
        return !(operation instanceof ResourceDuplicateRequest duplicate)
            || validProjectMetadataLocator(duplicate.source());
    }

    private boolean validProjectMetadataLocator(ServerResourceLocator resource) {
        if (resource == null || !PROJECT_METADATA_TYPE.equals(resource.resourceType().value())) {
            return true;
        }
        return "project".equals(resource.id()) || serverId.canonicalText().equals(resource.id());
    }

    private boolean isCoreResource(ServerResourceLocator resource) {
        return resource != null && serverId.equals(resource.serverId()) && PROTOCOL_OWNER.equals(resource.owner())
            && CORE_RESOURCE_TYPES.contains(resource.resourceType().value());
    }

    private ProtocolEnvelopeDispatchResult rejectUnsupported(ResourceOperationKind kind) {
        return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_OPERATION_UNSUPPORTED,
            "The Core graph authority does not expose a " + kind.name().toLowerCase(Locale.ROOT) + " boundary");
    }

    private CanonicalPayload<Map<String, Object>> canonical(String serialized) {
        JsonObjectLike parsed = JsonObjectLike.parse(gson, serialized);
        return payloadCodec.canonicalize(payload(parsed.value()));
    }

    private String canonicalInput(CanonicalPayload<Map<String, Object>> canonical) {
        return payloadCodec.canonicalInput(canonical.value());
    }

    private State readState(ResultSet result, ServerResourceLocator resource) throws SQLException {
        return new State(resource, result.getLong("revision"), UUID.fromString(result.getString("mutation_id")),
            result.getString("payload_hash"), result.getInt("deleted") != 0, result.getString("payload"),
            result.getInt("deleted") != 0 ? null : parseActivationState(result.getString("activation_state")),
            result.getString("asset_hash"), result.getString("core_payload_hash"), result.getString("core_payload_kind"));
    }

    private MutationRow readMutation(ResultSet result) throws SQLException {
        return new MutationRow(UUID.fromString(result.getString("mutation_id")), normalizeActor(result.getString("actor_id")), result.getString("fingerprint"), result.getString("operation"),
            result.getString("requested_resource"), result.getString("response_resource"), result.getString("source_resource"), result.getString("target_resource"),
            parseNullableActivationState(result.getString("target_activation_state")), result.getLong("expected_revision"), result.getString("precondition_hash"),
            Status.valueOf(result.getString("status")), result.getLong("result_revision"), result.getString("result_mutation_id"), result.getString("result_hash"),
            result.getString("result_deleted") != null && result.getInt("result_deleted") != 0,
            parseNullableActivationState(result.getString("result_activation_state")), result.getString("result_payload"),
            result.getLong("sequence"), result.getString("error_code"), result.getString("error_message"), result.getLong("created_at"), result.getLong("updated_at"),
            result.getString("precondition_asset_hash"), result.getString("precondition_core_payload_hash"),
            result.getString("precondition_core_payload_kind"), result.getString("result_asset_hash"),
            result.getString("result_core_payload_hash"), result.getString("result_core_payload_kind"));
    }

    private void bindMutation(PreparedStatement statement, MutationRow row) throws SQLException {
        statement.setString(1, row.mutationId().toString());
        statement.setString(2, row.actorId());
        statement.setString(3, row.fingerprint());
        statement.setString(4, row.operation());
        statement.setString(5, row.requestedResource());
        statement.setString(6, row.responseResource());
        statement.setString(7, row.sourceResource());
        statement.setString(8, row.targetResource());
        statement.setString(9, row.targetActivationState() == null ? null : row.targetActivationState().wireName());
        statement.setLong(10, row.expectedRevision());
        statement.setString(11, row.preconditionHash());
        statement.setString(12, row.status().name());
        statement.setLong(13, row.resultRevision());
        statement.setString(14, row.resultMutationId());
        statement.setString(15, row.resultHash());
        statement.setInt(16, row.resultDeleted() ? 1 : 0);
        statement.setString(17, row.resultActivationState() == null ? null : row.resultActivationState().wireName());
        statement.setString(18, row.resultPayload());
        statement.setString(19, row.preconditionAssetHash());
        statement.setString(20, row.preconditionCorePayloadHash());
        statement.setString(21, row.preconditionCorePayloadKind());
        statement.setString(22, row.resultAssetHash());
        statement.setString(23, row.resultCorePayloadHash());
        statement.setString(24, row.resultCorePayloadKind());
        statement.setLong(25, row.sequence());
        statement.setString(26, row.errorCode());
        statement.setString(27, row.errorMessage());
        statement.setLong(28, row.createdAt());
        statement.setLong(29, row.updatedAt());
    }

    private ServerResourceLocator locator(String canonical) {
        ServerResourceLocator resource = ServerResourceLocator.parseCanonicalText(canonical);
        if (!serverId.equals(resource.serverId())) {
            throw new IllegalArgumentException("Resource locator belongs to a different server");
        }
        return resource;
    }

    private String currentHash(State state) {
        return state == null ? "" : state.payloadHash();
    }

    private ContentHash hash(State state) {
        return state == null || state.payloadHash() == null || state.payloadHash().isBlank() ? null : new ContentHash(state.payloadHash());
    }

    private boolean matchesOutcome(State state, MutationRow row) {
        if (state == null || state.revision() != row.resultRevision()
            || !Objects.equals(state.mutationId().toString(), row.resultMutationId())
            || !Objects.equals(state.payloadHash(), row.resultHash())
            || state.deleted() != row.resultDeleted()) {
            return false;
        }
        ResourceActivationState expected = row.resultActivationState();
        return expected == null ? state.activationState() == null : expected == state.activationState();
    }

    private String errorCode(Status status) {
        return switch (status) {
            case CONFLICT -> "RESOURCE_ALREADY_EXISTS";
            case REVISION_CONFLICT -> "RESOURCE_REVISION_CONFLICT";
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            case IDEMPOTENCY_CONFLICT -> "RESOURCE_IDEMPOTENCY_CONFLICT";
            default -> "";
        };
    }

    private String errorMessage(Status status) {
        return switch (status) {
            case CONFLICT -> "Resource already exists";
            case REVISION_CONFLICT -> "Resource revision does not match";
            case NOT_FOUND -> "Resource was not found";
            case IDEMPOTENCY_CONFLICT -> "Mutation ID was reused with different input";
            default -> "";
        };
    }

    private Map<String, Object> bodyUnknown(ProtocolEnvelope<Map<String, Object>> envelope) {
        return envelope.body() instanceof ProtocolBody.ResourceRequest request ? request.unknown() : Map.of();
    }

    private boolean sameReplayActor(String storedActor, String currentActor) {
        String normalized = normalizeActor(storedActor);
        return !LEGACY_ACTOR.equals(normalized) && normalized.equals(currentActor);
    }

    private String normalizeActor(String actor) {
        return actor == null || actor.isBlank() ? LEGACY_ACTOR : actor;
    }

    private Map<String, Object> diagnosticIdentity(Command command, ProtocolEnvelope<Map<String, Object>> envelope,
                                                   Integer generation) {
        return TemporaryLifecycleDiagnostics.identity(serverId,
            command == null || command.responseResource() == null ? null : command.responseResource().canonicalText(),
            command == null ? null : command.mutationId(), envelope == null ? null : envelope.requestId(),
            envelope == null ? null : envelope.correlationId(), command == null ? null : command.expectedRevision(),
            envelope == null ? authorityEpoch.current() : envelope.authorityEpoch(), generation);
    }

    private Map<String, Object> diagnosticIdentity(MutationRow row) {
        return TemporaryLifecycleDiagnostics.identity(serverId, row == null ? null : row.responseResource(),
            row == null ? null : row.mutationId(), null, null, row == null ? null : row.resultRevision(),
            authorityEpoch.current(), null);
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank() ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static AuthorityEpoch requireExplicitAuthorityEpoch() {
        throw new IllegalStateException("Authority epoch is required; use the bound-epoch constructor");
    }

    private boolean mayHaveAppliedExternally(FlowOperationResult<?> result) {
        String errorCode = result == null ? null : result.errorCode();
        return errorCode != null && errorCode.endsWith("_FAILED");
    }

    private void blockRecovery(String reason) {
        recoveryBlocked = true;
        publishDurability();
        recoveryReason = reason == null || reason.isBlank() ? "Resource mutation recovery is blocked" : reason;
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void resetAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed To Reset Resource Mutation Transaction", exception);
        }
    }

    private enum Status {
        PENDING,
        APPLIED,
        CONFLICT,
        REVISION_CONFLICT,
        IDEMPOTENCY_CONFLICT,
        NOT_FOUND,
        REJECTED
    }

    private record Command(String operationName, ServerResourceLocator resource, ServerResourceLocator source,
                           ServerResourceLocator responseResource, UUID mutationId, long expectedRevision,
                           Map<String, Object> payload, ContentHash payloadHash, ResourceActivationState activationState,
                           ResourcePresentationIntent presentation) {
        private ResourceOperationKind kind() {
            return ResourceOperationKind.valueOf(operationName);
        }

        private ServerResourceLocator target() {
            return responseResource;
        }
    }

    private record Outcome(Status status, ServerResourceLocator resource, ContentHash resultHash, boolean resultDeleted,
                           Map<String, Object> resultPayload, State current, long resultRevision,
                           ResourceActivationState resultActivationState) {
        private Outcome(Status status, ServerResourceLocator resource, ContentHash resultHash, boolean resultDeleted,
                        Map<String, Object> resultPayload, State current, ResourceActivationState resultActivationState) {
            this(status, resource, resultHash, resultDeleted, resultPayload, current, current == null ? 0 : current.revision(), resultActivationState);
        }

        private Outcome(Status status, ServerResourceLocator resource, ContentHash resultHash, boolean resultDeleted,
                        Map<String, Object> resultPayload, State current) {
            this(status, resource, resultHash, resultDeleted, resultPayload, current, current == null ? 0 : current.revision(),
                current == null ? null : current.activationState());
        }
    }

    private record CoreOutcome(Status status, CoreState precondition, CoreState desired, String protocolHash,
                               String assetHash, String corePayloadHash, String corePayloadKind, boolean deleted,
                               long revision, UUID mutationId, ResourceActivationState activationState,
                               Map<String, Object> payload) {
        private ServerResourceLocator resource() {
            return desired != null ? desired.resource() : precondition == null ? null : precondition.resource();
        }
    }

    private record CoreState(ServerResourceLocator resource, long revision, UUID mutationId, String protocolHash,
                             String assetHash, String corePayloadHash, String corePayloadKind, boolean deleted,
                             ResourceActivationState activationState, CoreGraphStorageBoundary.Decoded decoded,
                             Map<String, Object> payload) {
    }

    private record CoreCatalogRebindItem(ServerResourceLocator resource, long sourceRevision,
                                         UUID sourceMutationId, ContentHash sourceAssetHash,
                                         ContentHash sourceCoreHash, String sourcePayloadKind,
                                         String sourceEnvelope, UUID mutationId, String status,
                                         String diagnostic, Long resultRevision, ContentHash resultAssetHash) {
        private CoreCatalogRebindItem {
            resource = Objects.requireNonNull(resource, "Core catalog rebind resource is required");
            sourceMutationId = Objects.requireNonNull(sourceMutationId,
                "Core catalog rebind source mutation ID is required");
            sourceAssetHash = Objects.requireNonNull(sourceAssetHash,
                "Core catalog rebind source asset hash is required");
            sourceCoreHash = Objects.requireNonNull(sourceCoreHash,
                "Core catalog rebind source Core hash is required");
            sourcePayloadKind = Objects.requireNonNull(sourcePayloadKind,
                "Core catalog rebind source payload kind is required");
            sourceEnvelope = Objects.requireNonNull(sourceEnvelope,
                "Core catalog rebind source envelope is required");
            mutationId = Objects.requireNonNull(mutationId, "Core catalog rebind mutation ID is required");
            status = Objects.requireNonNull(status, "Core catalog rebind status is required");
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        private String initialStatus() {
            return "REJECTED".equals(status) ? "REJECTED" : "PLANNED";
        }
    }

    private record State(ServerResourceLocator resource, long revision, UUID mutationId, String payloadHash,
                         boolean deleted, String payload, ResourceActivationState activationState, String assetHash,
                         String corePayloadHash, String corePayloadKind) {
        private State(ServerResourceLocator resource, long revision, UUID mutationId, String payloadHash,
                      boolean deleted, String payload, ResourceActivationState activationState) {
            this(resource, revision, mutationId, payloadHash, deleted, payload, activationState, null, null, null);
        }
    }

    private record ProjectMetadataCache(FlowResourceAdapter<Object> adapter,
                                        FlowResourceAdapter.MutationObservation observation, State state) {
    }

    private record LegacyAdminChain(List<LegacyAdminTransition> transitions, String survivingCoreType, State baseline) {
    }

    private record LegacyAdminTransition(AssetTransactionCoordinator.MutationView mutation,
                                         ServerResourceLocator resource, String operation,
                                         long expectedRevision, AssetTransactionCoordinator.ExpectedState result) {
    }

    private record LegacyAdminImport(LegacyAdminTransition transition, State state,
                                     CoreResourceMutationTransition coreTransition) {
    }

    private record MutationRow(UUID mutationId, String actorId, String fingerprint, String operation, String requestedResource, String responseResource,
                               String sourceResource, String targetResource, ResourceActivationState targetActivationState, long expectedRevision,
                               String preconditionHash, Status status, long resultRevision, String resultMutationId, String resultHash,
                               boolean resultDeleted, ResourceActivationState resultActivationState, String resultPayload, long sequence,
                               String errorCode, String errorMessage, long createdAt, long updatedAt,
                               String preconditionAssetHash, String preconditionCorePayloadHash, String preconditionCorePayloadKind,
                               String resultAssetHash, String resultCorePayloadHash, String resultCorePayloadKind) {
        private MutationRow(UUID mutationId, String actorId, String fingerprint, String operation, String requestedResource,
                            String responseResource, String sourceResource, String targetResource,
                            ResourceActivationState targetActivationState, long expectedRevision, String preconditionHash,
                            Status status, long resultRevision, String resultMutationId, String resultHash,
                            boolean resultDeleted, ResourceActivationState resultActivationState, String resultPayload,
                            long sequence, String errorCode, String errorMessage, long createdAt, long updatedAt) {
            this(mutationId, actorId, fingerprint, operation, requestedResource, responseResource, sourceResource, targetResource,
                targetActivationState, expectedRevision, preconditionHash, status, resultRevision, resultMutationId,
                resultHash, resultDeleted, resultActivationState, resultPayload, sequence, errorCode, errorMessage,
                createdAt, updatedAt, null, null, null, null, null, null);
        }
    }

    private record AggregateReceipt(UUID mutationId, ResourcePresentationIntent presentation,
                                    ServerResourceLocator metadataResource, State metadata) {
        private AggregateReceipt {
            mutationId = Objects.requireNonNull(mutationId, "mutationId");
            presentation = Objects.requireNonNull(presentation, "presentation");
            metadataResource = Objects.requireNonNull(metadataResource, "metadataResource");
            if (!PROJECT_METADATA_TYPE.equals(metadataResource.resourceType().value())
                || !PROTOCOL_OWNER.equals(metadataResource.owner())
                || !metadataResource.serverId().canonicalText().equals(metadataResource.id())) {
                throw new IllegalArgumentException("Aggregate receipt metadata locator is not canonical");
            }
            if (metadata != null && (!metadataResource.equals(metadata.resource())
                || !mutationId.equals(metadata.mutationId()) || metadata.revision() < 1L || metadata.deleted()
                || metadata.payload() == null || metadata.activationState() != ResourceActivationState.ACTIVE)) {
                throw new IllegalArgumentException("Aggregate receipt metadata locator does not match its state");
            }
        }
    }

    private record AggregateCreateState(State primary, State projectMetadata, String primaryReceiptPayload) {
        private AggregateCreateState {
            primary = Objects.requireNonNull(primary, "primary");
            projectMetadata = Objects.requireNonNull(projectMetadata, "projectMetadata");
            primaryReceiptPayload = Objects.requireNonNull(primaryReceiptPayload, "primaryReceiptPayload");
        }
    }

    private record StoredCoreTransition(MutationRow row, String canonicalEnvelope,
                                        ContentHash canonicalEnvelopeHash) {
    }

    private record CoreTransitionProof(String envelope, String hash, boolean published) {
    }

    private static final class JsonObjectLike {
        private final Map<String, Object> value;

        private JsonObjectLike(Map<String, Object> value) {
            this.value = value;
        }

        private static JsonObjectLike parse(Gson gson, String serialized) {
            Map<?, ?> value = gson.fromJson(serialized, Map.class);
            if (value == null) {
                throw new IllegalArgumentException("Resource payload must be an object");
            }
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            value.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("Resource payload keys must be strings");
                }
                map.put(text, item);
            });
            return new JsonObjectLike(map);
        }

        private Map<String, Object> value() {
            return value;
        }
    }

    private enum PersistenceState {
        OPEN,
        ADMISSION_CLOSED,
        QUIESCED,
        CLOSED
    }
}
