package restudio.resync.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.OperationId;
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
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceDuplicateRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.CoreResourceMutationBus;
import restudio.resync.modules.flow.CoreResourceMutationTransition;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.security.ClientIdentity;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProtocolCoreGraphAuthorityTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
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
    void routesCoreLifecycleWithoutAFlowResourceAdapterAndPersistsMetadataOnly(@TempDir Path directory) throws Exception {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ServerResourceLocator resource = resource("flow", "core-lifecycle");
        UUID createMutation = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID activateMutation = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID deleteMutation = UUID.fromString("44444444-4444-4444-8444-444444444444");
        FlowStorageCoreGraphResourceAuthority core = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        List<CoreResourceMutationTransition> transitions = new ArrayList<>();
        List<CoreResourceMutationTransition> workspace = new ArrayList<>();
        List<CoreResourceMutationTransition> collaboration = new ArrayList<>();
        List<CoreResourceMutationTransition> runtime = new ArrayList<>();
        registry.addCoreMutationListener(transitions::add);
        registry.addCoreMutationListener(workspace::add);
        registry.addCoreMutationListener(collaboration::add);
        registry.addCoreMutationListener(runtime::add);

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory, core)) {
            ProtocolEnvelope<Map<String, Object>> create = create(resource, graph(resource, 1L), createMutation, boundary);
            ProtocolEnvelopeDispatchResult created = mutate(authority, create);
            assertTrue(created.handled(), created.code() + ": " + created.message());
            ResourceDocumentView createdDocument = document(created);
            assertEquals(1L, createdDocument.revision());
            assertEquals(createMutation, createdDocument.mutationId());
            assertEquals(ResourceActivationState.ACTIVE, createdDocument.activationState());
            assertEquals(createdDocument.payloadHash(), ResourcePayloadCodecs.json().canonicalize(createdDocument.payload()).checksum());
            assertNotEquals(createdDocument.payloadHash(), new ContentHash((String) createdDocument.payload().get("assetHash")));
            assertNull(registry.get("flow"));

            ProtocolEnvelopeDispatchResult activated = mutate(authority,
                activate(resource, 1L, ResourceActivationState.INACTIVE, activateMutation));
            assertTrue(activated.handled(), activated.code() + ": " + activated.message());
            assertEquals(2L, document(activated).revision());
            assertEquals(ResourceActivationState.INACTIVE, document(activated).activationState());

            ProtocolEnvelope<Map<String, Object>> deleteRequest = delete(resource, 2L, deleteMutation);
            ProtocolEnvelopeDispatchResult deleted = mutate(authority, deleteRequest);
            assertTrue(deleted.handled(), deleted.code() + ": " + deleted.message());
            assertTrue(document(deleted).deleted());
            assertEquals(3L, document(deleted).revision());
            assertEquals(graph(resource, 2L).checksum(), document(deleted).payloadHash());
            ProtocolEnvelopeDispatchResult rediscovered = mutate(authority, deleteRequest);
            assertTrue(rediscovered.handled(), rediscovered.code() + ": " + rediscovered.message());
            assertTrue(document(rediscovered).deleted());
            assertEquals(deleteMutation, document(rediscovered).mutationId());
            assertEquals(3L, document(rediscovered).revision());
            ResourceDocument<Map<String, Object>> loaded = authority.load(resource);
            assertTrue(loaded.deleted());
            assertEquals(graph(resource, 2L).checksum(), loaded.payloadHash());
        }

        assertEquals(3, transitions.size());
        assertTransition(transitions.get(0), resource, 1L, createMutation, false, ResourceActivationState.ACTIVE);
        assertTransition(transitions.get(1), resource, 2L, activateMutation, false, ResourceActivationState.INACTIVE);
        assertTransition(transitions.get(2), resource, 3L, deleteMutation, true, null);
        assertEquals(transitions, workspace);
        assertEquals(transitions, collaboration);
        assertEquals(transitions, runtime);
        assertEquals(CoreResourceMutationBus.Publication.STALE,
            registry.publishCommittedCoreMutation(transitions.getFirst()));
        assertEquals(CoreResourceMutationBus.Publication.REPLAY,
            registry.publishCommittedCoreMutation(transitions.getLast()));
        CoreResourceMutationTransition collision = new CoreResourceMutationTransition(resource, 3L,
            deleteMutation, true, null, "different-author", transitions.getLast().canonicalEnvelope());
        assertThrows(IllegalStateException.class, () -> registry.publishCommittedCoreMutation(collision));
        assertEquals(new String(boundary.encodeTombstone(resource, 3L, deleteMutation,
            graph(resource, 2L).checksum()), StandardCharsets.UTF_8), transitions.get(2).canonicalEnvelope());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
             PreparedStatement state = connection.prepareStatement(
                 "SELECT payload, asset_hash, core_payload_hash, core_payload_kind FROM resource_mutation_state WHERE resource = ?");
             PreparedStatement receipt = connection.prepareStatement(
                 "SELECT result_payload, result_asset_hash, result_core_payload_hash, transition_envelope, transition_hash, "
                     + "transition_published FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            state.setString(1, resource.canonicalText());
            try (var result = state.executeQuery()) {
                assertTrue(result.next());
                assertNull(result.getString(1));
                assertTrue(result.getString(2) != null && !result.getString(2).isBlank());
                assertTrue(result.getString(3) != null && !result.getString(3).isBlank());
                assertTrue(result.getString(4) != null && !result.getString(4).isBlank());
            }
            receipt.setString(1, deleteMutation.toString());
            try (var result = receipt.executeQuery()) {
                assertTrue(result.next());
                assertNull(result.getString(1));
                assertTrue(result.getString(2) != null && !result.getString(2).isBlank());
                assertTrue(result.getString(3) != null && !result.getString(3).isBlank());
                assertNull(result.getString(4));
                assertTrue(result.getString(5) != null && !result.getString(5).isBlank());
                assertEquals(1, result.getInt(6));
            }
        }
    }

    @Test
    void recoversAnAppliedUnpublishedTransitionOnceAfterAuthorityRestart(@TempDir Path directory) throws Exception {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ServerResourceLocator resource = resource("command", "core-replay");
        UUID mutationId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        ProtocolEnvelope<Map<String, Object>> request = create(resource, graph(resource, 1L), mutationId, boundary);
        CoreGraphResourceAuthority core = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        List<CoreResourceMutationTransition> initial = new ArrayList<>();
        registry.addCoreMutationListener(initial::add);

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory, core)) {
            assertTrue(mutate(authority, request).handled());
            assertEquals(1, initial.size());
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
             PreparedStatement crash = connection.prepareStatement("""
                 UPDATE resource_mutation_receipt
                 SET transition_published = 0, transition_envelope = ?
                 WHERE mutation_id = ? AND status = 'APPLIED'
                 """)) {
            crash.setString(1, initial.getFirst().canonicalEnvelope());
            crash.setString(2, mutationId.toString());
            assertEquals(1, crash.executeUpdate());
        }

        FlowResourceRegistry recoveredRegistry = new FlowResourceRegistry();
        List<CoreResourceMutationTransition> recovered = new ArrayList<>();
        recoveredRegistry.addCoreMutationListener(recovered::add);
        try (SqliteProtocolResourceMutationAuthority authority = authority(recoveredRegistry, directory, core)) {
            assertEquals(1, recovered.size());
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);
            assertTrue(replay.handled(), replay.code() + ": " + replay.message());
            assertEquals(mutationId, document(replay).mutationId());
            assertEquals(1L, document(replay).revision());
            assertEquals(1, recovered.size());
        }

        FlowResourceRegistry repeatedRegistry = new FlowResourceRegistry();
        List<CoreResourceMutationTransition> repeated = new ArrayList<>();
        repeatedRegistry.addCoreMutationListener(repeated::add);
        try (SqliteProtocolResourceMutationAuthority ignored = authority(repeatedRegistry, directory, core)) {
            assertTrue(repeated.isEmpty());
        }
    }

    @Test
    void routesFunctionSourceThroughTheSameCoreAuthority(@TempDir Path directory) {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ServerResourceLocator resource = resource("function", "core-function");
        UUID mutationId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        FunctionSourceDocument source = source(resource, 1L);

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory,
            new FlowStorageCoreGraphResourceAuthority(storage, SERVER))) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, create(resource, source, mutationId, boundary));
            assertTrue(result.handled(), result.code() + ": " + result.message());
            assertEquals(CoreGraphStorageBoundary.FUNCTION_SOURCE_KIND,
                document(result).payload().get(CoreGraphStorageBoundary.CORE_PAYLOAD_KIND));
        }
    }

    @Test
    void recoversAnAppliedCoreRequestAndClearsItsTransientEnvelope(@TempDir Path directory) throws Exception {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ServerResourceLocator resource = resource("flow", "core-recovery");
        UUID mutationId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        ProtocolEnvelope<Map<String, Object>> request = create(resource, graph(resource, 1L), mutationId, boundary);
        ThrowAfterSaveCoreAuthority failing = new ThrowAfterSaveCoreAuthority(
            new FlowStorageCoreGraphResourceAuthority(storage, SERVER));
        List<CoreResourceMutationTransition> transitions = new ArrayList<>();
        registry.addCoreMutationListener(transitions::add);

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory, failing)) {
            ProtocolEnvelopeDispatchResult pending = mutate(authority, request);
            assertFalse(pending.handled());
            assertEquals("RESOURCE_MUTATION_PENDING", pending.code());
            assertTrue(transitions.isEmpty());
        }

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory,
            new FlowStorageCoreGraphResourceAuthority(storage, SERVER))) {
            ResourceDocument<Map<String, Object>> recovered = authority.load(resource);
            assertEquals(1L, recovered.revision());
        }
        assertEquals(1, transitions.size());
        assertTransition(transitions.getFirst(), resource, 1L, mutationId, false, ResourceActivationState.ACTIVE);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
             PreparedStatement receipt = connection.prepareStatement(
                 "SELECT status, result_payload FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            receipt.setString(1, mutationId.toString());
            try (var result = receipt.executeQuery()) {
                assertTrue(result.next());
                assertEquals("APPLIED", result.getString(1));
                assertNull(result.getString(2));
            }
        }
    }

    @Test
    void isolatesTransitionAndFanoutFailuresAfterTheAppliedCommit(@TempDir Path directory) {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ServerResourceLocator resource = resource("flow", "core-listener-failure");
        UUID mutationId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        List<CoreResourceMutationTransition> firstConsumer = new ArrayList<>();
        List<CoreResourceMutationTransition> secondConsumer = new ArrayList<>();
        List<String> receiptStatuses = new ArrayList<>();
        registry.addCoreMutationListener(idempotent(firstConsumer));
        AtomicBoolean reject = new AtomicBoolean(true);
        registry.addCoreMutationListener(transition -> {
            receiptStatuses.add(receiptStatus(directory, transition.mutationId()));
            if (reject.get()) {
                throw new IllegalStateException("Injected typed listener failure");
            }
            secondConsumer.add(transition);
        });
        ProtocolEnvelope<Map<String, Object>> request = create(resource, graph(resource, 1L), mutationId, boundary);
        UUID nextMutation = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        ProtocolEnvelope<Map<String, Object>> next = activate(resource, 1L,
            ResourceActivationState.INACTIVE, nextMutation);
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory,
            new FlowStorageCoreGraphResourceAuthority(storage, SERVER))) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, request);
            assertTrue(result.handled(), result.code() + ": " + result.message());
            assertEquals(1L, authority.load(resource).revision());
            ProtocolEnvelopeDispatchResult blocked = mutate(authority, next);
            assertFalse(blocked.handled());
            assertEquals("RESOURCE_MUTATION_PENDING", blocked.code());
            assertEquals(1L, authority.load(resource).revision());
            assertEquals("", receiptStatus(directory, nextMutation));
            assertEquals(1, firstConsumer.size());
            assertTrue(secondConsumer.isEmpty());
        }

        reject.set(false);
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory,
            new FlowStorageCoreGraphResourceAuthority(storage, SERVER))) {
            assertEquals(1, secondConsumer.size());
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);
            assertTrue(replay.handled(), replay.code() + ": " + replay.message());
            ProtocolEnvelopeDispatchResult activated = mutate(authority, next);
            assertTrue(activated.handled(), activated.code() + ": " + activated.message());
            assertEquals(2L, authority.load(resource).revision());
        }

        assertEquals(List.of("APPLIED", "APPLIED", "APPLIED", "APPLIED"), receiptStatuses);
        assertEquals(2, firstConsumer.size());
        assertEquals(2, secondConsumer.size());
        assertTransition(firstConsumer.getFirst(), resource, 1L, mutationId, false, ResourceActivationState.ACTIVE);
        assertTransition(secondConsumer.getFirst(), resource, 1L, mutationId, false, ResourceActivationState.ACTIVE);
        assertEquals(firstConsumer, secondConsumer);
    }

    @Test
    void duplicatesCoreGraphsOntoANewResource(@TempDir Path directory) {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ServerResourceLocator source = resource("flow", "core-source");
        ServerResourceLocator target = resource("flow", "core-copy");
        UUID createMutation = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID duplicateMutation = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        FlowStorageCoreGraphResourceAuthority core = new FlowStorageCoreGraphResourceAuthority(storage, SERVER);
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory, core)) {
            ProtocolEnvelopeDispatchResult created = mutate(authority, create(source, graph(source, 1L), createMutation, boundary));
            assertTrue(created.handled(), created.code() + ": " + created.message());

            ProtocolEnvelopeDispatchResult duplicated = mutate(authority, duplicate(source, target, 1L, duplicateMutation));
            assertTrue(duplicated.handled(), duplicated.code() + ": " + duplicated.message());
            ResourceDocumentView copy = document(duplicated);
            assertEquals(target, copy.resource());
            assertEquals(1L, copy.revision());
            assertEquals(duplicateMutation, copy.mutationId());
            assertFalse(copy.deleted());
            ResourceDocument<Map<String, Object>> loaded = authority.load(target);
            assertEquals(1L, loaded.revision());
            assertEquals(duplicateMutation, loaded.mutationId());
            assertEquals(createMutation, authority.load(source).mutationId());
        }
    }

    @Test
    void duplicatesCoreFunctionSourcesOntoANewResource(@TempDir Path directory) {
        FlowStorage storage = storage(directory);
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ServerResourceLocator source = resource("function", "core-function-source");
        ServerResourceLocator target = resource("function", "core-function-copy");
        UUID createMutation = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        UUID duplicateMutation = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory,
            new FlowStorageCoreGraphResourceAuthority(storage, SERVER))) {
            ProtocolEnvelopeDispatchResult created = mutate(authority, create(source, source(source, 1L), createMutation, boundary));
            assertTrue(created.handled(), created.code() + ": " + created.message());
            ProtocolEnvelopeDispatchResult duplicated = mutate(authority, duplicate(source, target, 1L, duplicateMutation));
            assertTrue(duplicated.handled(), duplicated.code() + ": " + duplicated.message());
            assertEquals(CoreGraphStorageBoundary.FUNCTION_SOURCE_KIND,
                document(duplicated).payload().get(CoreGraphStorageBoundary.CORE_PAYLOAD_KIND));
            assertEquals(1L, document(duplicated).revision());
            assertEquals(duplicateMutation, document(duplicated).mutationId());
        }
    }

    @Test
    void rejectsCoreMutationWhenTheCoreAuthorityIsUnavailable(@TempDir Path directory) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ServerResourceLocator resource = resource("flow", "unavailable");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        ProtocolEnvelope<Map<String, Object>> request = create(resource, graph(resource, 1L),
            UUID.fromString("66666666-6666-4666-8666-666666666666"), boundary);

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory,
            CoreGraphResourceAuthority.unavailable())) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, request);
            assertFalse(result.handled());
            assertEquals("RESOURCE_DURABILITY_UNAVAILABLE", result.code());
        }
    }

    private FlowStorage storage(Path directory) {
        assetsGate = new AssetPersistenceGate(directory);
        try {
            coordinator = new AssetTransactionCoordinator(directory.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open SQLite Core graph authority test persistence", exception);
        }
        return new FlowStorage(directory.toFile(), LegacyRuntimeActivationGate.runtime(directory), assetsGate, SERVER, coordinator);
    }

    private static SqliteProtocolResourceMutationAuthority authority(FlowResourceRegistry registry, Path directory,
                                                                      CoreGraphResourceAuthority core) {
        return new SqliteProtocolResourceMutationAuthority(registry, SERVER, directory.resolve("resource.db"), core,
            AuthorityEpoch.fixed(1L));
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private static GraphDocument graph(ServerResourceLocator resource, long revision) {
        List<GraphNode> nodes = "command".equals(resource.resourceType().value())
            ? List.of(new GraphNode(NodeInstanceId.deterministic("command-start"),
                CommandGraphContract.CANONICAL_START, 1, Map.of()))
            : List.of();
        return new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, Set.of(), nodes,
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "preserve")));
    }

    private static FunctionSourceDocument source(ServerResourceLocator resource, long revision) {
        GraphDocument graph = graph(resource, revision);
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(revision),
            List.of(), List.of(), Map.of());
        return new FunctionSourceDocument(signature, graph);
    }

    private static ProtocolEnvelope<Map<String, Object>> create(ServerResourceLocator resource, GraphDocument graph,
                                                                  UUID mutationId, CoreGraphStorageBoundary boundary) {
        byte[] bytes = boundary.encode(graph, new CoreGraphStorageBoundary.AssetMetadata(
            resource.resourceType().value(), 1L, mutationId, ResourceActivationState.ACTIVE), resource);
        return envelope(resource, new ResourceCreateRequest<>(resource, payload(bytes), mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> create(ServerResourceLocator resource,
                                                                  FunctionSourceDocument source, UUID mutationId,
                                                                  CoreGraphStorageBoundary boundary) {
        byte[] bytes = boundary.encode(source, new CoreGraphStorageBoundary.AssetMetadata(
            resource.resourceType().value(), 1L, mutationId, ResourceActivationState.ACTIVE), resource);
        return envelope(resource, new ResourceCreateRequest<>(resource, payload(bytes), mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> activate(ServerResourceLocator resource, long revision,
                                                                   ResourceActivationState state, UUID mutationId) {
        return envelope(resource, new ResourceActivateRequest(resource, revision, state, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> duplicate(ServerResourceLocator source,
                                                                   ServerResourceLocator target, long revision,
                                                                   UUID mutationId) {
        return envelope(target, new ResourceDuplicateRequest(source, target, revision, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> delete(ServerResourceLocator resource, long revision,
                                                                 UUID mutationId) {
        return envelope(resource, new ResourceDeleteRequest(resource, revision, mutationId));
    }

    @SuppressWarnings("unchecked")
    private static CanonicalPayload<Map<String, Object>> payload(byte[] bytes) {
        Object parsed = CanonicalJson.parse(bytes);
        return ResourcePayloadCodecs.json().canonicalize((Map<String, Object>) parsed);
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource,
                                                                  ResourceOperation operation) {
        CatalogVersion version = operation instanceof ResourceActivateRequest
            ? new CatalogVersion(1, 1) : new CatalogVersion(1, 0);
        Set<ContractRef<CapabilityId>> capabilities =
            operation instanceof ResourceActivateRequest
                ? Set.of(ContractRef.of(OWNER, new CapabilityId("resources")),
                    ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY)
                : Set.of(ContractRef.of(OWNER, new CapabilityId("resources")));
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, version, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), SERVER, resource, operation instanceof ResourceCreateRequest<?> ? 0
            : operation instanceof ResourceSaveRequest<?> save ? save.expectedRevision()
            : operation instanceof ResourceDeleteRequest delete ? delete.expectedRevision()
            : operation instanceof ResourceActivateRequest activate ? activate.expectedRevision()
            : operation instanceof ResourceDuplicateRequest duplicate ? duplicate.expectedRevision() : 0,
            1L, mutation(operation), ContractRef.of(OWNER, new OperationId(
                "resource." + operation.kind().name().toLowerCase())),
            capabilities, ContractRef.of(OWNER, new ResourceTypeId("resource.document")), null,
            operation instanceof ResourceCreateRequest<?> create ? create.payloadHash()
            : operation instanceof ResourceSaveRequest<?> save ? save.payloadHash() : null, false, null, null, null,
            null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(operation));
    }

    private static UUID mutation(ResourceOperation operation) {
        return switch (operation) {
            case ResourceCreateRequest<?> create -> create.mutationId();
            case ResourceSaveRequest<?> save -> save.mutationId();
            case ResourceDeleteRequest delete -> delete.mutationId();
            case ResourceActivateRequest activate -> activate.mutationId();
            case ResourceDuplicateRequest duplicate -> duplicate.mutationId();
            default -> throw new IllegalArgumentException("Unsupported test operation");
        };
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
        connection.setClientId("core-test");
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        Session session = new Session("core-session", "core-test", connection,
            new ClientIdentity("core-test", "2.1.0"));
        return authority.mutate(connection, session, request,
            ((ProtocolBody.ResourceRequest) request.body()).operation());
    }

    private static ResourceDocumentView document(ProtocolEnvelopeDispatchResult result) {
        ProtocolBody.ResourceDocumentResponse response =
            (ProtocolBody.ResourceDocumentResponse) result.response().body();
        @SuppressWarnings("unchecked")
        ResourceDocument<Map<String, Object>> document = (ResourceDocument<Map<String, Object>>) response.document();
        return new ResourceDocumentView(document.resource(), document.revision(), document.mutationId(),
            document.payloadHash(), document.deleted(), document.payload(), document.activationState());
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
            throw new IllegalStateException("Failed to read the Core mutation receipt", exception);
        }
    }

    private static void assertTransition(CoreResourceMutationTransition transition, ServerResourceLocator resource,
                                         long revision, UUID mutationId, boolean deleted,
                                         ResourceActivationState activationState) {
        assertEquals(resource, transition.locator());
        assertEquals(revision, transition.revision());
        assertEquals(mutationId, transition.mutationId());
        assertEquals(deleted, transition.deleted());
        assertEquals(activationState, transition.activationState());
        assertEquals("core-test", transition.author());
        assertFalse(transition.canonicalEnvelope().isBlank());
    }

    private static Consumer<CoreResourceMutationTransition> idempotent(List<CoreResourceMutationTransition> transitions) {
        return transition -> {
            if (transitions.isEmpty() || !transitions.getLast().checkpoint().equals(transition.checkpoint())) {
                transitions.add(transition);
            }
        };
    }

    private record ResourceDocumentView(ServerResourceLocator resource, long revision, UUID mutationId,
                                        ContentHash payloadHash, boolean deleted, Map<String, Object> payload,
                                        ResourceActivationState activationState) {
    }

    private static final class ThrowAfterSaveCoreAuthority implements CoreGraphResourceAuthority {
        private final CoreGraphResourceAuthority delegate;
        private boolean failAfterSave = true;

        private ThrowAfterSaveCoreAuthority(CoreGraphResourceAuthority delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean available() {
            return delegate.available();
        }

        @Override
        public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
            return delegate.load(resource);
        }

        @Override
        public List<CoreGraphResourceAuthority.CoreGraphResourceState> list(String type) {
            return delegate.list(type);
        }

        @Override
        public Optional<CoreGraphResourceAuthority.CoreGraphResourceState> state(ServerResourceLocator resource) {
            return delegate.state(resource);
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                       UUID mutationId, long expectedRevision, ContentHash payloadChecksum) {
            CoreGraphStorageBoundary.Decoded saved = delegate.save(resource, canonicalEnvelope, mutationId,
                expectedRevision, payloadChecksum);
            if (failAfterSave) {
                failAfterSave = false;
                throw new IllegalStateException("Injected post-apply failure");
            }
            return saved;
        }

        @Override
        public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                                    long expectedRevision, ContentHash payloadChecksum) {
            return delegate.delete(resource, mutationId, expectedRevision, payloadChecksum);
        }

        @Override
        public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                           ResourceActivationState activationState, UUID mutationId,
                                                           long expectedRevision, ContentHash payloadChecksum) {
            return delegate.activate(resource, activationState, mutationId, expectedRevision, payloadChecksum);
        }
    }
}
