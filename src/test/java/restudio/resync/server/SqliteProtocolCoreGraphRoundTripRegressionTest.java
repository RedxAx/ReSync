package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.CoreGraphTransfer;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceCreateResult;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.modules.flow.CoreResourceMutationTransition;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.security.ClientIdentity;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.ProjectMetadataLineage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProtocolCoreGraphRoundTripRegressionTest {
    private static final ServerId SERVER = ServerId.deterministic("core-graph-round-trip-regression");
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogBinding BINDING = CoreCatalogBindingMigration.TARGET;
    private static final CoreGraphStorageBoundary BOUNDARY = new CoreGraphStorageBoundary();
    private AssetPersistenceGate assetsGate;
    private AssetTransactionCoordinator coordinator;

    @AfterEach
    void tearDown() throws Exception {
        if (assetsGate != null) {
            assetsGate.quiesce();
        }
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void aggregateCreateActivationLoadAndListRemainCanonicalWithoutLegacyGraphAdapters(@TempDir Path directory) {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registerProjectMetadata(registry, storage);
        List<CoreResourceMutationTransition> transitions = new ArrayList<>();
        registry.addCoreMutationListener(transitions::add);
        FlowStorageCoreGraphResourceAuthority core = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        registry.bindCoreGraphResourceAuthority(core);
        Map<String, ServerResourceLocator> resources = new LinkedHashMap<>();
        resources.put("command", resource("command", "round-trip-command"));
        resources.put("flow", resource("flow", "round-trip-flow"));
        resources.put("function", resource("function", "round-trip-function"));

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            registry, SERVER, directory.resolve("resource.db"), core, ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), registry)) {
            int sortOrder = 1;
            for (Map.Entry<String, ServerResourceLocator> entry : resources.entrySet()) {
                String type = entry.getKey();
                ServerResourceLocator resource = entry.getValue();
                UUID createMutation = UUID.nameUUIDFromBytes((type + "-create").getBytes(StandardCharsets.UTF_8));
                UUID activateMutation = UUID.nameUUIDFromBytes((type + "-activate").getBytes(StandardCharsets.UTF_8));
                long metadataRevision = Math.multiplyExact(sortOrder, 2L);
                ResourcePresentationIntent presentation = new ResourcePresentationIntent(
                    "Round Trip " + type, "Core/" + type + '/' + resource.id() + ".json", sortOrder++);
                ProtocolEnvelope<Map<String, Object>> create = create(resource, createMutation, presentation);

                assertNull(registry.get(type));
                ProtocolEnvelopeDispatchResult created = mutate(authority, create);
                assertTrue(created.handled(), created.code() + ": " + created.message());
                ResourceCreateResult createResult = createResult(created);
                assertCreated(createResult, resource, createMutation, presentation, metadataRevision);
                assertEquals("APPLIED", receiptStatus(directory, createMutation));

                ProtocolEnvelopeDispatchResult replayed = mutate(authority, create);
                assertTrue(replayed.handled(), replayed.code() + ": " + replayed.message());
                assertEquals(createResult, createResult(replayed));
                assertEquals("APPLIED", receiptStatus(directory, createMutation));
                assertNull(registry.get(type));

                ProtocolEnvelopeDispatchResult activated = mutate(authority,
                    envelope(resource, new ResourceActivateRequest(resource, 1L,
                        ResourceActivationState.INACTIVE, activateMutation)));
                assertTrue(activated.handled(), activated.code() + ": " + activated.message());
                assertCanonical(document(activated), resource, 2L, activateMutation,
                    ResourceActivationState.INACTIVE);

                ResourceDocument<Map<String, Object>> loaded = authority.load(resource);
                assertNotNull(loaded);
                assertCanonical(loaded, resource, 2L, activateMutation, ResourceActivationState.INACTIVE);
            }

            for (Map.Entry<String, ServerResourceLocator> entry : resources.entrySet()) {
                List<ResourceDocument<Map<String, Object>>> listed = authority.list(SERVER,
                    ContractRef.of(OWNER, ResourceTypeId.of(entry.getKey())), "round-trip");
                assertEquals(1, listed.size());
                assertCanonical(listed.getFirst(), entry.getValue(), 2L,
                    UUID.nameUUIDFromBytes((entry.getKey() + "-activate").getBytes(StandardCharsets.UTF_8)),
                    ResourceActivationState.INACTIVE);
                assertTrue(authority.list(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(entry.getKey())),
                    "missing").isEmpty());
            }
            assertEquals(3, transitions.size());
        }
    }

    @Test
    void deletedGraphsRecreateWithNewLineageAndSurviveAuthorityReopen(@TempDir Path directory) {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registerProjectMetadata(registry, storage);
        List<CoreResourceMutationTransition> transitions = new ArrayList<>();
        registry.addCoreMutationListener(transitions::add);
        FlowStorageCoreGraphResourceAuthority core = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        registry.bindCoreGraphResourceAuthority(core);
        Map<ServerResourceLocator, UUID> recreated = new LinkedHashMap<>();
        Map<ServerResourceLocator, ProtocolEnvelope<Map<String, Object>>> requests = new LinkedHashMap<>();
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            registry, SERVER, directory.resolve("resource.db"), core, ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), registry)) {
            for (String type : List.of("command", "flow", "function")) {
                ServerResourceLocator resource = resource(type, "recreated-" + type);
                ResourcePresentationIntent presentation = new ResourcePresentationIntent("Recreated " + type,
                    "Core/" + type + '/' + resource.id() + ".json", 1);
                ProtocolEnvelopeDispatchResult first = mutate(authority, create(resource, UUID.randomUUID(), presentation));
                assertTrue(first.handled(), first.code() + ": " + first.message());
                assertEquals(1L, createResult(first).resource().revision());
                ProtocolEnvelopeDispatchResult deleted = mutate(authority,
                    envelope(resource, new ResourceDeleteRequest(resource, 1L, UUID.randomUUID())));
                assertTrue(deleted.handled(), deleted.code() + ": " + deleted.message());
                assertTrue(document(deleted).deleted());
                assertEquals(2L, document(deleted).revision());
                UUID mutation = UUID.randomUUID();
                ProtocolEnvelope<Map<String, Object>> request = create(resource, mutation, presentation, 1L);
                ProtocolEnvelopeDispatchResult result = mutate(authority, request);
                assertTrue(result.handled(), result.code() + ": " + result.message());
                ResourceCreateResult created = createResult(result);
                assertEquals(3L, created.resource().revision());
                assertEquals(mutation, created.resource().mutationId());
                assertEquals(mutation, created.projectMetadata().mutationId());
                assertEquals(created, createResult(mutate(authority, request)));
                assertEquals(ProtocolEnvelope.Kind.CONFLICT,
                    mutate(authority, create(resource, UUID.randomUUID(), presentation, 4L)).response().kind());
                assertCanonical(authority.load(resource), resource, 3L, mutation, ResourceActivationState.ACTIVE);
                recreated.put(resource, mutation);
                requests.put(resource, request);
            }
            assertEquals(3, transitions.size());
            assertTrue(transitions.stream().allMatch(transition -> transition.deleted() && transition.revision() == 2L));
        }
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            registry, SERVER, directory.resolve("resource.db"), core, ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), registry)) {
            for (Map.Entry<ServerResourceLocator, UUID> entry : recreated.entrySet()) {
                assertCanonical(authority.load(entry.getKey()), entry.getKey(), 3L, entry.getValue(), ResourceActivationState.ACTIVE);
                assertEquals(3L, createResult(mutate(authority, requests.get(entry.getKey()))).resource().revision());
            }
        }
    }

    @Test
    void interruptedRecreationReplaysCommittedStorageWithoutAnotherTransaction(@TempDir Path directory) throws Exception {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registerProjectMetadata(registry, storage);
        List<CoreResourceMutationTransition> transitions = new ArrayList<>();
        registry.addCoreMutationListener(transitions::add);
        FlowStorageCoreGraphResourceAuthority core = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        registry.bindCoreGraphResourceAuthority(core);
        ServerResourceLocator resource = resource("command", "interrupted-recreation");
        ResourcePresentationIntent initial = new ResourcePresentationIntent("Initial",
            "Blueprints/Commands/Initial/interrupted-recreation.json", 1);
        ResourcePresentationIntent replacement = new ResourcePresentationIntent("Replacement",
            "Blueprints/Commands/Replacement/interrupted-recreation.json", 2);
        UUID mutation = UUID.randomUUID();
        ProtocolEnvelope<Map<String, Object>> request = create(resource, mutation, replacement, 3L);
        Path database = directory.resolve("resource.db");
        long committedSequence;
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            registry, SERVER, database, core, ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry)) {
            assertTrue(mutate(authority, create(resource, UUID.randomUUID(), initial)).handled());
            assertTrue(mutate(authority, envelope(resource, new ResourceDeleteRequest(resource, 1L, UUID.randomUUID()))).handled());
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TRIGGER fail_recreation_commit BEFORE UPDATE ON resource_mutation_receipt
                    WHEN NEW.status = 'APPLIED'
                    BEGIN SELECT RAISE(ABORT, 'interrupted recreation authority commit'); END
                    """);
            }
            ProtocolEnvelopeDispatchResult pending = mutate(authority, request);
            assertEquals("RESOURCE_MUTATION_PENDING", pending.code());
            assertEquals("PENDING", receiptStatus(directory, mutation));
            assertEquals(3L, storage.getCoreGraph("command", resource.id()).orElseThrow().envelope().assetRevision());
            committedSequence = coordinator.read(snapshot -> snapshot.rootSequence());
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_recreation_commit");
        }
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            registry, SERVER, database, core, ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry)) {
            assertEquals("APPLIED", receiptStatus(directory, mutation));
            assertCanonical(authority.load(resource), resource, 3L, mutation, ResourceActivationState.ACTIVE);
            ResourceCreateResult replay = createResult(mutate(authority, request));
            assertEquals(replacement, replay.presentation());
            assertEquals(3L, replay.resource().revision());
            assertEquals(mutation, replay.projectMetadata().mutationId());
            assertEquals(committedSequence, coordinator.read(snapshot -> snapshot.rootSequence()).longValue());
        }
    }

    @Test
    void networkCoreMutationsPreserveLocalCatalogAndDurableDeletion(@TempDir Path directory) throws Exception {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registerProjectMetadata(registry, storage);
        registry.addCoreMutationListener(ignored -> {
        });
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot base = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        String canonical = CatalogCanonicalizer.canonicalSnapshotContent(3L, base.contractVersion(), base.contributions(),
            base.minimumClientCapabilities(), base.diagnostics(), runtime.bindingManifestHash());
        CatalogSnapshot catalog = new CatalogSnapshot(3L, base.contractVersion(), base.contentChecksum(), runtime.bindingManifestHash(),
            base.minimumClientCapabilities(), base.contributions(), base.definitions(), base.types(), base.conversions(), base.categories(),
            base.inspectors(), base.capabilities(), base.runtimeRequirements(), base.optionSources(), base.validators(), base.editors(),
            base.previews(), base.migrations(), base.provenance(), base.diagnostics(), canonical, runtime.bindingManifestHash());
        FlowStorageCoreGraphResourceAuthority core = new FlowStorageCoreGraphResourceAuthority(storage, SERVER,
            new CoreGraphMutationValidator(SERVER, new CatalogRuntimeActivation(catalog, runtime), CatalogActivationAuthority.freshInstall()));
        registry.bindCoreGraphResourceAuthority(core);
        CatalogBinding local = core.activeCatalogBinding().orElseThrow();
        CatalogBinding foreign = new CatalogBinding(2L, local.catalogChecksum(), local.bindingManifestHash());
        ServerResourceLocator resource = resource("flow", "shared-core");
        ServerResourceLocator origin = new ServerResourceLocator(ServerId.deterministic("shared-core-origin"), resource.key());
        UUID saved;
        UUID deleted;
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            directory.resolve("resource.db"), core, ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry)) {
            FlowResourceRegistry.NetworkCoreMutations mutations = authority.networkCoreMutations();
            for (long revision : List.of(9L, 10L)) {
                GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), origin, revision, foreign, Set.of(),
                    List.of(), List.of(), List.of(), List.of(), OpaqueData.of(Map.of("shared", revision)));
                CoreGraphStorageBoundary.Decoded source = BOUNDARY.decode(BOUNDARY.encode(graph,
                    new CoreGraphStorageBoundary.AssetMetadata("flow", revision, UUID.randomUUID(), ResourceActivationState.INACTIVE), origin));
                CoreGraphStorageBoundary.Decoded portable = CoreGraphTransfer.decode(CoreGraphTransfer.serialize(source), "flow", resource.id());
                assertEquals(1L, portable.graphDocument().catalogBinding().generation());
                mutations.save(resource, portable);
                assertEquals(revision - 8L, authority.load(resource).revision());
                assertEquals(local, storage.getCoreGraph("flow", resource.id()).orElseThrow().graphDocument().catalogBinding());
                assertEquals("APPLIED", receiptStatus(directory, authority.load(resource).mutationId()));
                mutations.save(resource, portable);
                assertEquals(revision - 8L, authority.load(resource).revision());
            }
            saved = authority.load(resource).mutationId();
            authority.quiescePersistence();
            assertThrows(IllegalStateException.class, () -> mutations.delete(resource));
            authority.resumePersistence();
            assertThrows(IllegalStateException.class, () -> mutations.delete(resource));
            authority.networkCoreMutations().delete(resource);
            ResourceDocument<Map<String, Object>> tombstone = authority.load(resource);
            assertTrue(tombstone.deleted());
            assertEquals(3L, tombstone.revision());
            deleted = tombstone.mutationId();
        }
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            directory.resolve("resource.db"), core, ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry)) {
            assertTrue(authority.load(resource).deleted());
            assertEquals(deleted, authority.load(resource).mutationId());
            assertEquals("APPLIED", receiptStatus(directory, saved));
            assertEquals("APPLIED", receiptStatus(directory, deleted));
        }
    }

    @Test
    void codecRejectsEnvelopeAndEmbeddedRevisionDivergence() {
        ServerResourceLocator flow = resource("flow", "divergent-flow");
        ServerResourceLocator function = resource("function", "divergent-function");
        UUID mutationId = UUID.nameUUIDFromBytes("divergent-revision".getBytes(StandardCharsets.UTF_8));
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(
            "flow", 2L, mutationId, ResourceActivationState.ACTIVE);

        assertThrows(IllegalArgumentException.class, () -> BOUNDARY.encode(graph(flow, 1L), metadata, flow));
        assertThrows(IllegalArgumentException.class, () -> BOUNDARY.encode(source(function, 1L),
            new CoreGraphStorageBoundary.AssetMetadata("function", 2L, mutationId,
                ResourceActivationState.ACTIVE), function));

        byte[] canonicalFlow = BOUNDARY.encode(graph(flow, 2L), metadata, flow);
        CanonicalCodec<GraphDocument> staleGraphCodec = new CanonicalCodec<>() {
            @Override
            public JsonValue encode(GraphDocument value) {
                return GraphDocumentCodec.INSTANCE.encode(value);
            }

            @Override
            public GraphDocument decode(JsonValue value) {
                return graph(flow, 1L);
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new CoreGraphStorageBoundary(staleGraphCodec,
            FunctionSourceDocumentCodec.INSTANCE).decode(canonicalFlow, flow));

        byte[] canonicalFunction = BOUNDARY.encode(source(function, 2L),
            new CoreGraphStorageBoundary.AssetMetadata("function", 2L, mutationId,
                ResourceActivationState.ACTIVE), function);
        CanonicalCodec<FunctionSourceDocument> staleFunctionCodec = new CanonicalCodec<>() {
            @Override
            public JsonValue encode(FunctionSourceDocument value) {
                return FunctionSourceDocumentCodec.INSTANCE.encode(value);
            }

            @Override
            public FunctionSourceDocument decode(JsonValue value) {
                return source(function, 1L);
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new CoreGraphStorageBoundary(
            GraphDocumentCodec.INSTANCE, staleFunctionCodec).decode(canonicalFunction, function));
    }

    private FlowStorage storage(Path directory) {
        assetsGate = new AssetPersistenceGate(directory);
        try {
            coordinator = new AssetTransactionCoordinator(directory.resolve("assets"), new Gson());
            bootstrapProjectMetadata(coordinator);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open Core graph regression persistence", exception);
        }
        return new FlowStorage(directory.toFile(), LegacyRuntimeActivationGate.runtime(directory), assetsGate,
            SERVER, coordinator);
    }

    private static void bootstrapProjectMetadata(AssetTransactionCoordinator coordinator) throws IOException {
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(current -> current);
        UUID mutationId = UUID.nameUUIDFromBytes("project-metadata-bootstrap".getBytes(StandardCharsets.UTF_8));
        List<AssetTransactionCoordinator.ProjectDelta> projectDeltas = List.of(
            AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"),
                new JsonPrimitive(SERVER.canonicalText())));
        AssetTransactionCoordinator.AssetDelta lineage = ProjectMetadataLineage.writer(
            coordinator.canonicalRoot(), new Gson()).write(snapshot, projectDeltas, mutationId);
        if (lineage == null) {
            throw new IOException("Project metadata bootstrap did not produce durable lineage");
        }
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
            mutationId, snapshot.project(), List.of(lineage), projectDeltas));
    }

    private static void registerProjectMetadata(FlowResourceRegistry registry, FlowStorage storage) {
        registry.register(new FlowResourceAdapter<String>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.PROJECT_METADATA);
            }

            @Override
            public String get(String id) {
                return storage.getProjectMetadata(id);
            }

            @Override
            public List<String> listIds() {
                return storage.listProjectMetadataIds();
            }

            @Override
            public String deserialize(String json) {
                return storage.normalizeProjectMetadataPayload(json);
            }

            @Override
            public String serialize(String value) {
                return storage.normalizeProjectMetadataPayload(value);
            }

            @Override
            public String id(String value) {
                return storage.projectMetadataResourceId();
            }

            @Override
            public void save(String value) {
                storage.saveProjectMetadata(value);
            }

            @Override
            public boolean supportsAuthoritativeMutationIdentity() {
                return true;
            }

            @Override
            public void save(String value, UUID mutationId, long expectedRevision) {
                storage.saveProjectMetadata(value, mutationId, expectedRevision);
            }

            @Override
            public void delete(String id) {
                storage.deleteProjectMetadata(id);
            }

            @Override
            public void delete(String id, UUID mutationId, long expectedRevision) {
                storage.deleteProjectMetadata(id, mutationId, expectedRevision);
            }

            @Override
            public FlowResourceMutationStamp readMutationStamp(String id) {
                FlowStorage.ResourceIdentity identity = storage.readProjectMetadataIdentity(id);
                return identity == null ? null : new FlowResourceMutationStamp(identity.type(), identity.id(),
                    identity.revision(), UUID.fromString(identity.mutationId()), identity.payloadHash(),
                    identity.deleted());
            }
        });
    }

    private static ProtocolEnvelope<Map<String, Object>> create(ServerResourceLocator resource, UUID mutationId,
                                                                  ResourcePresentationIntent presentation) {
        return create(resource, mutationId, presentation, 1L);
    }

    private static ProtocolEnvelope<Map<String, Object>> create(ServerResourceLocator resource, UUID mutationId,
                                                                  ResourcePresentationIntent presentation, long revision) {
        byte[] bytes = "function".equals(resource.resourceType().value())
            ? BOUNDARY.encode(source(resource, revision), metadata(resource, revision, mutationId), resource)
            : BOUNDARY.encode(graph(resource, revision), metadata(resource, revision, mutationId), resource);
        return envelope(resource, new ResourceCreateRequest<>(resource, payload(bytes), mutationId, presentation));
    }

    private static CoreGraphStorageBoundary.AssetMetadata metadata(ServerResourceLocator resource, long revision,
                                                                    UUID mutationId) {
        return new CoreGraphStorageBoundary.AssetMetadata(resource.resourceType().value(), revision, mutationId,
            ResourceActivationState.ACTIVE);
    }

    private static GraphDocument graph(ServerResourceLocator resource, long revision) {
        List<GraphNode> nodes = "command".equals(resource.resourceType().value())
            ? List.of(new GraphNode(NodeInstanceId.deterministic("round-trip-command-start"),
                CommandGraphContract.CANONICAL_START, 1, Map.of()))
            : List.of();
        return new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(), nodes,
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "preserved")));
    }

    private static FunctionSourceDocument source(ServerResourceLocator resource, long revision) {
        return new FunctionSourceDocument(new FunctionSignature(new FunctionLocator(resource),
            new FunctionRevision(revision), List.of(), List.of(), Map.of()), graph(resource, revision));
    }

    @SuppressWarnings("unchecked")
    private static CanonicalPayload<Map<String, Object>> payload(byte[] bytes) {
        return ResourcePayloadCodecs.json().canonicalize((Map<String, Object>) CanonicalJson.parse(bytes));
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource,
                                                                   ResourceOperation operation) {
        boolean activation = operation instanceof ResourceActivateRequest;
        Set<ContractRef<CapabilityId>> capabilities = activation
            ? Set.of(ContractRef.of(OWNER, CapabilityId.of("resources")),
                ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY)
            : Set.of(ContractRef.of(OWNER, CapabilityId.of("resources")),
                ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY);
        long expectedRevision = activation ? ((ResourceActivateRequest) operation).expectedRevision()
            : operation instanceof ResourceDeleteRequest delete ? delete.expectedRevision() : 0L;
        UUID mutationId = activation ? ((ResourceActivateRequest) operation).mutationId()
            : operation instanceof ResourceDeleteRequest delete ? delete.mutationId()
            : ((ResourceCreateRequest<?>) operation).mutationId();
        ContentHash payloadHash = operation instanceof ResourceCreateRequest<?> create ? create.payloadHash() : null;
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, new CatalogVersion(1, activation ? 1 : 2),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SERVER, resource,
            expectedRevision, 1L, mutationId, ContractRef.of(OWNER,
            OperationId.of("resource." + operation.kind().name().toLowerCase())), capabilities,
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, payloadHash, false, null, null,
            null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(operation));
    }

    private static ProtocolEnvelopeDispatchResult mutate(SqliteProtocolResourceMutationAuthority authority,
                                                           ProtocolEnvelope<Map<String, Object>> request) {
        ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId("core-round-trip-test");
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        Session session = new Session("core-round-trip-session", "core-round-trip-test", connection,
            new ClientIdentity("core-round-trip-test", "2.1.0"));
        return authority.mutate(connection, session, request,
            ((ProtocolBody.ResourceRequest) request.body()).operation());
    }

    private static ResourceCreateResult createResult(ProtocolEnvelopeDispatchResult result) {
        return ((ProtocolBody.ResourceCreateResponse) result.response().body()).result();
    }

    private static String receiptStatus(Path directory, UUID mutationId) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
             PreparedStatement receipt = connection.prepareStatement(
                 "SELECT status FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            receipt.setString(1, mutationId.toString());
            try (var result = receipt.executeQuery()) {
                return result.next() ? result.getString(1) : "";
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read the Core aggregate mutation receipt", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static ResourceDocument<Map<String, Object>> document(ProtocolEnvelopeDispatchResult result) {
        return (ResourceDocument<Map<String, Object>>)
            ((ProtocolBody.ResourceDocumentResponse) result.response().body()).document();
    }

    private static void assertCreated(ResourceCreateResult result, ServerResourceLocator resource, UUID mutationId,
                                      ResourcePresentationIntent presentation, long metadataRevision) {
        assertEquals(resource, result.resource().resource());
        assertEquals(1L, result.resource().revision());
        assertEquals(mutationId, result.resource().mutationId());
        assertEquals(ResourceActivationState.ACTIVE, result.resource().activationState());
        assertEquals(resource(ReSyncResourceCatalog.PROJECT_METADATA, SERVER.canonicalText()),
            result.projectMetadata().resource());
        assertEquals(metadataRevision, result.projectMetadata().revision());
        assertEquals(mutationId, result.projectMetadata().mutationId());
        assertEquals(ResourceActivationState.ACTIVE, result.projectMetadata().activationState());
        assertEquals(new ResourcePresentationIntent(presentation.displayName(),
            ReSyncResourceCatalog.byType(resource.resourceType().value()).defaultFolder() + '/' + resource.id() + ".json",
            presentation.sortOrder()), result.presentation());
        @SuppressWarnings("unchecked")
        ResourceDocument<Map<String, Object>> primary = (ResourceDocument<Map<String, Object>>) result.resource();
        assertCanonical(primary, resource, 1L, mutationId, ResourceActivationState.ACTIVE);
    }

    private static void assertCanonical(ResourceDocument<Map<String, Object>> document,
                                        ServerResourceLocator resource, long revision, UUID mutationId,
                                        ResourceActivationState activationState) {
        assertEquals(resource, document.resource());
        assertEquals(revision, document.revision());
        assertEquals(mutationId, document.mutationId());
        assertEquals(activationState, document.activationState());
        CoreGraphStorageBoundary.Decoded decoded = BOUNDARY.decode(CanonicalJson.canonicalBytes(document.payload()),
            resource);
        assertEquals(revision, decoded.envelope().assetRevision());
        assertEquals(mutationId.toString(), decoded.envelope().assetMutationId());
        assertEquals(activationState, decoded.envelope().assetActivationState());
        if (decoded.graphDocument() != null) {
            assertEquals(revision, decoded.graphDocument().revision());
        } else {
            assertEquals(revision, decoded.functionSourceDocument().graph().revision());
            assertEquals(revision, decoded.functionSourceDocument().signature().revision().value());
        }
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }
}
