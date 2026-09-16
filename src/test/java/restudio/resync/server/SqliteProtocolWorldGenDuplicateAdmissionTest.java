package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceDuplicateRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceKey;
import restudio.resync.modules.flow.FlowResourceMutationListener;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.security.ClientIdentity;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.data.WorldGenConnection;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenNode;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.registry.WorldGenNodeDefinitions;
import restudio.resync.worldgen.registry.WorldGenNodeRegistry;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProtocolWorldGenDuplicateAdmissionTest {
    private static final Gson GSON = new Gson();
    private static final ServerId SERVER = ServerId.deterministic("worldgen-duplicate-admission");

    @Test
    void aggregateWorldGenDuplicateAdmitsSourceTargetAndProjectMetadata(@TempDir Path directory) throws Exception {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new AggregateWorldGenAdapter());
        ServerResourceLocator source = resource("source");
        ServerResourceLocator target = resource("target");
        ResourceDuplicateRequest duplicate = new ResourceDuplicateRequest(source, target, 4L, UUID.randomUUID());

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            registry, SERVER, directory.resolve("authority").resolve("resource.db"), CoreGraphResourceAuthority.unavailable(),
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry)) {
            Method commandFactory = SqliteProtocolResourceMutationAuthority.class.getDeclaredMethod(
                "command", ProtocolEnvelope.class, ResourceOperation.class);
            commandFactory.setAccessible(true);
            Object command = commandFactory.invoke(authority, null, duplicate);
            Method mutationKeys = SqliteProtocolResourceMutationAuthority.class.getDeclaredMethod("mutationKeys", command.getClass());
            mutationKeys.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<FlowResourceKey> keys = (List<FlowResourceKey>) mutationKeys.invoke(authority, command);
            assertEquals(3, keys.size());
            assertEquals(Set.of(
                new FlowResourceKey(ReSyncResourceCatalog.WORLDGEN, source.id()),
                new FlowResourceKey(ReSyncResourceCatalog.WORLDGEN, target.id()),
                new FlowResourceKey(ReSyncResourceCatalog.PROJECT_METADATA, SERVER.canonicalText())), Set.copyOf(keys));
        }
    }

    @Test
    void projectMetadataDuplicateCanonicalizesAliasSourceAndTarget(@TempDir Path directory) throws Exception {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ServerResourceLocator alias = projectMetadata("project");
        ServerResourceLocator canonical = projectMetadata(SERVER.canonicalText());
        UUID mutationId = UUID.randomUUID();
        ResourceDuplicateRequest duplicate = new ResourceDuplicateRequest(alias, canonical, 4L, mutationId);
        ResourceDuplicateRequest reversed = new ResourceDuplicateRequest(canonical, alias, 4L, mutationId);

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            registry, SERVER, directory.resolve("authority").resolve("resource.db"), CoreGraphResourceAuthority.unavailable(),
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry)) {
            Method commandFactory = SqliteProtocolResourceMutationAuthority.class.getDeclaredMethod(
                "command", ProtocolEnvelope.class, ResourceOperation.class);
            commandFactory.setAccessible(true);
            Object command = commandFactory.invoke(authority, null, duplicate);
            Object reversedCommand = commandFactory.invoke(authority, null, reversed);
            Method sourceAccessor = command.getClass().getDeclaredMethod("source");
            Method resourceAccessor = command.getClass().getDeclaredMethod("resource");
            Method responseAccessor = command.getClass().getDeclaredMethod("responseResource");
            Method fingerprint = SqliteProtocolResourceMutationAuthority.class.getDeclaredMethod(
                "fingerprint", command.getClass(), String.class);
            sourceAccessor.setAccessible(true);
            resourceAccessor.setAccessible(true);
            responseAccessor.setAccessible(true);
            fingerprint.setAccessible(true);

            assertEquals(canonical, sourceAccessor.invoke(command));
            assertEquals(canonical, resourceAccessor.invoke(command));
            assertEquals(canonical, responseAccessor.invoke(command));
            assertEquals(fingerprint.invoke(authority, command, "client"),
                fingerprint.invoke(authority, reversedCommand, "client"));
        }
    }

    @Test
    void worldGenDuplicatePersistsAtomicStateAndReplaysAfterCallbackLossAndRestart(@TempDir Path directory) throws Exception {
        Path assets = directory.resolve("assets");
        Path database = directory.resolve("resource.db");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, GSON)) {
            AssetPersistenceGate persistenceGate = new AssetPersistenceGate(directory);
            FlowStorage storage = new FlowStorage(directory.toFile(), LegacyRuntimeActivationGate.runtime(directory), persistenceGate,
                SERVER, coordinator);
            WorldGenProjectStorage worldGenStorage = new WorldGenProjectStorage(directory.toFile(), LegacyRuntimeActivationGate.runtime(directory),
                persistenceGate, coordinator);
            try {
                FlowResourceRegistry registry = new FlowResourceRegistry();
                FlowResourcePacketRouter router = new FlowResourcePacketRouter(storage, null, null, null,
                    null, null, null, registry, ignored -> {
                });
                router.registerExternalLifecycle(worldGenStorage, null);
                WorldGenNodeDefinitions.registerDefaults(WorldGenNodeRegistry.getInstance());
                FlowResourceAdapter<String> metadataAdapter = adapter(registry, ReSyncResourceCatalog.PROJECT_METADATA);
                metadataAdapter.save("{}", UUID.fromString("10000000-0000-4000-8000-000000000001"), 0L);
                FlowResourceAdapter<WorldGenProject> worldGenAdapter = adapter(registry, ReSyncResourceCatalog.WORLDGEN);

                ServerResourceLocator sourceResource = resource("protocol-source");
                ServerResourceLocator targetResource = resource("protocol-target");
                WorldGenProject source = worldGenProject(sourceResource.id());
                UUID sourceMutation = UUID.fromString("20000000-0000-4000-8000-000000000002");
                ResourcePresentationIntent sourcePresentation = new ResourcePresentationIntent(
                    "Protocol Source", "WorldGen/protocol-source.json", 5);
                ProtocolEnvelope<Map<String, Object>> create = envelope(sourceResource,
                    new ResourceCreateRequest<>(sourceResource, canonical(worldGenAdapter.serialize(source)), sourceMutation,
                        sourcePresentation));
                UUID duplicateMutation = UUID.fromString("30000000-0000-4000-8000-000000000003");
                ProtocolEnvelope<Map<String, Object>> duplicate = envelope(targetResource,
                    new ResourceDuplicateRequest(sourceResource, targetResource, 1L, duplicateMutation));

                FlowResourceMutationStamp sourceStamp;
                String sourceJson;
                ResourceDocument<Map<String, Object>> firstDocument;
                long committedSequence;
                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, database)) {
                    ProtocolEnvelopeDispatchResult created = mutate(authority, create);
                    assertTrue(created.handled(), created.code() + ": " + created.message());
                    sourceStamp = worldGenAdapter.readMutationStamp(sourceResource.id());
                    sourceJson = worldGenAdapter.serialize(worldGenAdapter.get(sourceResource.id()));
                    assertEquals(canonical(statePayload(database, sourceResource)).value(), canonical(sourceJson).value());
                    AtomicBoolean failPublication = new AtomicBoolean(true);
                    registry.setMutationListener(new FlowResourceMutationListener() {
                        @Override
                        public void saved(String type, String resourceId, String payload) {
                            if (failPublication.getAndSet(false)) {
                                throw new IllegalStateException("callback lost");
                            }
                        }

                        @Override
                        public void deleted(String type, String resourceId) {
                        }
                    });

                    ProtocolEnvelopeDispatchResult first = mutate(authority, duplicate);
                    assertTrue(first.handled(), first.code() + ": " + first.message());
                    firstDocument = document(first);
                    assertEquals(targetResource, firstDocument.resource());
                    assertEquals(1L, firstDocument.revision());
                    assertEquals(duplicateMutation, firstDocument.mutationId());
                    assertEquals(targetResource.id(), firstDocument.payload().get("id"));
                    assertEquals(sourceStamp, worldGenAdapter.readMutationStamp(sourceResource.id()));
                    assertEquals(sourceJson, worldGenAdapter.serialize(worldGenAdapter.get(sourceResource.id())));
                    assertTrue(Files.isRegularFile(assets.resolve("WorldGen/protocol-target.json")));
                    assertWorldGenMetadata(assets.resolve("project.json"), sourceResource.id(), targetResource.id());
                    assertDurableDuplicateState(database, sourceResource, targetResource, sourceStamp, duplicateMutation,
                        firstDocument, storage);
                    committedSequence = coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence);

                    ProtocolEnvelopeDispatchResult callbackLossReplay = mutate(authority, duplicate);
                    assertTrue(callbackLossReplay.handled(), callbackLossReplay.code() + ": " + callbackLossReplay.message());
                    assertEquals(firstDocument, document(callbackLossReplay));
                    assertEquals(committedSequence, coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence).longValue());
                }

                try (SqliteProtocolResourceMutationAuthority authority = authority(registry, database)) {
                    ProtocolEnvelopeDispatchResult restartReplay = mutate(authority, duplicate);
                    assertTrue(restartReplay.handled(), restartReplay.code() + ": " + restartReplay.message());
                    assertEquals(firstDocument, document(restartReplay));
                    assertEquals(committedSequence, coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence).longValue());
                    assertEquals(sourceStamp, worldGenAdapter.readMutationStamp(sourceResource.id()));
                    assertEquals(sourceJson, worldGenAdapter.serialize(worldGenAdapter.get(sourceResource.id())));
                    assertDurableDuplicateState(database, sourceResource, targetResource, sourceStamp, duplicateMutation,
                        firstDocument, storage);
                }
            } finally {
                worldGenStorage.closePersistence();
            }
        }
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(ReSyncResourceCatalog.WORLDGEN)), id);
    }

    private static ServerResourceLocator projectMetadata(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(ReSyncResourceCatalog.PROJECT_METADATA)), id);
    }

    private static SqliteProtocolResourceMutationAuthority authority(FlowResourceRegistry registry, Path database) {
        return new SqliteProtocolResourceMutationAuthority(registry, SERVER, database,
            CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), registry);
    }

    private static CanonicalPayload<Map<String, Object>> canonical(String serialized) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = GSON.fromJson(serialized, Map.class);
        return ResourcePayloadCodecs.json().canonicalize(payload);
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource, ResourceOperation operation) {
        ContentHash payloadHash = operation instanceof ResourceCreateRequest<?> create ? create.payloadHash() : null;
        long revision = operation instanceof ResourceDuplicateRequest duplicate ? duplicate.expectedRevision() : 0L;
        UUID mutationId = operation instanceof ResourceCreateRequest<?> create ? create.mutationId()
            : ((ResourceDuplicateRequest) operation).mutationId();
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SERVER, resource, revision, 1L,
            mutationId, ContractRef.of(OwnerId.of("restudio.resync"),
                OperationId.of("resource." + operation.kind().name().toLowerCase())),
            Set.of(ContractRef.of(OwnerId.of("restudio.resync"), CapabilityId.of("resources")),
                ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("resource.document")), null, payloadHash,
            false, null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(operation));
    }

    private static ProtocolEnvelopeDispatchResult mutate(SqliteProtocolResourceMutationAuthority authority,
                                                           ProtocolEnvelope<Map<String, Object>> request) {
        ConnectionInfo connection = authenticatedConnection();
        return authority.mutate(connection, new Session("session", "client", connection,
            new ClientIdentity("client", "2.1.0")), request,
            ((ProtocolBody.ResourceRequest) request.body()).operation());
    }

    private static ConnectionInfo authenticatedConnection() {
        ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId("client");
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        return connection;
    }

    @SuppressWarnings("unchecked")
    private static ResourceDocument<Map<String, Object>> document(ProtocolEnvelopeDispatchResult result) {
        ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
            result.response().body());
        return (ResourceDocument<Map<String, Object>>) (ResourceDocument<?>) body.document();
    }

    private static void assertWorldGenMetadata(Path metadataFile, String sourceId, String targetId) throws Exception {
        JsonObject metadata = JsonParser.parseString(Files.readString(metadataFile)).getAsJsonObject();
        Map<String, JsonObject> entries = new LinkedHashMap<>();
        metadata.getAsJsonArray("resources").forEach(element -> {
            JsonObject entry = element.getAsJsonObject();
            if (ReSyncResourceCatalog.WORLDGEN.equals(entry.get("type").getAsString())) {
                entries.put(entry.get("id").getAsString(), entry);
            }
        });
        assertEquals("WorldGen/protocol-source.json", entries.get(sourceId).get("path").getAsString());
        assertEquals("WorldGen/protocol-target.json", entries.get(targetId).get("path").getAsString());
    }

    private static void assertDurableDuplicateState(Path database, ServerResourceLocator source,
                                                     ServerResourceLocator target, FlowResourceMutationStamp sourceStamp,
                                                     UUID mutationId,
                                                     ResourceDocument<Map<String, Object>> targetDocument,
                                                     FlowStorage storage) throws Exception {
        assertState(database, source, sourceStamp.revision(), sourceStamp.mutationId(),
            new ContentHash(sourceStamp.payloadHash()), false);
        assertState(database, target, 1L, mutationId, targetDocument.payloadHash(), false);
        Map<?, ?> targetPayload = GSON.fromJson(statePayload(database, target), Map.class);
        assertEquals(target.id(), targetPayload.get("id"));
        FlowStorage.ResourceIdentity metadataIdentity = storage.readProjectMetadataIdentity(SERVER.canonicalText());
        assertEquals(mutationId.toString(), metadataIdentity.mutationId());
        ServerResourceLocator metadataResource = projectMetadata(SERVER.canonicalText());
        assertState(database, metadataResource, metadataIdentity.revision(), mutationId,
            new ContentHash(metadataIdentity.payloadHash()), false);
        Map<?, ?> metadataPayload = GSON.fromJson(statePayload(database, metadataResource), Map.class);
        List<?> resources = assertInstanceOf(List.class, metadataPayload.get("resources"));
        assertTrue(resources.stream().map(Map.class::cast).anyMatch(resource ->
            ReSyncResourceCatalog.WORLDGEN.equals(resource.get("type")) && target.id().equals(resource.get("id"))));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT operation, requested_resource, response_resource, source_resource, target_resource,
                     expected_revision, status, result_revision, result_mutation_id, result_hash, result_deleted
                 FROM resource_mutation_receipt WHERE mutation_id = ?
                 """)) {
            statement.setString(1, mutationId.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("DUPLICATE", result.getString("operation"));
                assertEquals(target.canonicalText(), result.getString("requested_resource"));
                assertEquals(target.canonicalText(), result.getString("response_resource"));
                assertEquals(source.canonicalText(), result.getString("source_resource"));
                assertEquals(target.canonicalText(), result.getString("target_resource"));
                assertEquals(1L, result.getLong("expected_revision"));
                assertEquals("APPLIED", result.getString("status"));
                assertEquals(1L, result.getLong("result_revision"));
                assertEquals(mutationId.toString(), result.getString("result_mutation_id"));
                assertEquals(targetDocument.payloadHash().canonicalText(), result.getString("result_hash"));
                assertEquals(0, result.getInt("result_deleted"));
                assertFalse(result.next());
            }
        }
    }

    private static void assertState(Path database, ServerResourceLocator resource, long revision,
                                    UUID mutationId, ContentHash payloadHash, boolean deleted) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT revision, mutation_id, payload_hash, deleted FROM resource_mutation_state WHERE resource = ?
                 """)) {
            statement.setString(1, resource.canonicalText());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(revision, result.getLong("revision"));
                if (mutationId != null) {
                    assertEquals(mutationId.toString(), result.getString("mutation_id"));
                }
                if (payloadHash != null) {
                    assertEquals(payloadHash.canonicalText(), result.getString("payload_hash"));
                }
                assertEquals(deleted, result.getInt("deleted") != 0);
                assertFalse(result.next());
            }
        }
    }

    private static String statePayload(Path database, ServerResourceLocator resource) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT payload FROM resource_mutation_state WHERE resource = ?")) {
            statement.setString(1, resource.canonicalText());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                String payload = result.getString("payload");
                assertFalse(result.next());
                return payload;
            }
        }
    }

    private static WorldGenProject worldGenProject(String id) {
        WorldGenProject project = new WorldGenProject();
        project.setId(id);
        WorldGenGraph graph = new WorldGenGraph();
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        nodes.put("noise", new WorldGenNode("worldgen:simplex", 0, 0,
            Map.of("seed", 0, "frequency", 0.01f)));
        nodes.put("height", new WorldGenNode("worldgen:output_height", 160, 0, Map.of()));
        graph.setNodes(nodes);
        graph.setConnections(List.of(new WorldGenConnection("noise", "out", "height", "height")));
        project.setTerrainGraph(graph);
        return project;
    }

    @SuppressWarnings("unchecked")
    private static <T> FlowResourceAdapter<T> adapter(FlowResourceRegistry registry, String type) {
        return (FlowResourceAdapter<T>) registry.get(type);
    }

    private static final class AggregateWorldGenAdapter implements FlowResourceAdapter<JsonObject> {
        private final Gson gson = new Gson();

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.WORLDGEN);
        }

        @Override
        public JsonObject get(String id) {
            return null;
        }

        @Override
        public List<String> listIds() {
            return List.of();
        }

        @Override
        public JsonObject deserialize(String json) {
            return gson.fromJson(json, JsonObject.class);
        }

        @Override
        public String serialize(JsonObject value) {
            return gson.toJson(value);
        }

        @Override
        public String id(JsonObject value) {
            return value != null && value.has("id") ? value.get("id").getAsString() : "";
        }

        @Override
        public void save(JsonObject value) {
        }

        @Override
        public void delete(String id) {
        }

        @Override
        public boolean supportsAuthoritativeMutationIdentity() {
            return true;
        }

        @Override
        public boolean supportsAggregateCreate() {
            return true;
        }

        @Override
        public Set<String> supportedOperations() {
            return Set.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "delete");
        }
    }
}
