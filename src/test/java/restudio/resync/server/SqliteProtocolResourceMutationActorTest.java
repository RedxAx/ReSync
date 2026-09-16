package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.catalog.CatalogVersion;
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
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.security.ClientIdentity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProtocolResourceMutationActorTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ContractRef<ResourceTypeId> TYPE = ContractRef.of(new OwnerId("restudio.resync"),
        new ResourceTypeId(ReSyncResourceCatalog.GUI));

    @Test
    void exactReplayRequiresTheSameActor(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        FlowResourceRegistry registry = registry(values);
        ServerResourceLocator resource = resource("actor-replay");
        UUID mutationId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        ProtocolEnvelope<Map<String, Object>> request = create(resource, "Draft", mutationId);

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult first = mutate(authority, request, "actor-a");
            assertTrue(first.handled(), first.code() + ": " + first.message());

            ProtocolEnvelopeDispatchResult replay = mutate(authority, request, "actor-b");
            assertFalse(replay.handled());
            assertEquals(409, replay.transportCode());
            assertEquals(SqliteProtocolResourceMutationAuthority.ACTOR_CONFLICT_CODE, replay.code());
            assertNull(replay.response());
        }
    }

    @Test
    void sameActorReplayRemainsIdempotentAfterRestart(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        FlowResourceRegistry registry = registry(values);
        ServerResourceLocator resource = resource("actor-restart");
        UUID mutationId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        ProtocolEnvelope<Map<String, Object>> request = create(resource, "Draft", mutationId);

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult first = mutate(authority, request, "actor-a");
            assertTrue(first.handled(), first.code() + ": " + first.message());
        }
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request, "actor-a");
            assertTrue(replay.handled(), replay.code() + ": " + replay.message());
            ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
                replay.response().body());
            assertEquals(mutationId, body.document().mutationId());
            assertEquals(1, body.document().revision());
        }
    }

    @Test
    void migratedLegacyReceiptsAreNeverReplayable(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("resource.db");
        ServerResourceLocator resource = resource("legacy-receipt");
        UUID mutationId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        writeLegacyReceipt(database, resource, mutationId);
        FlowResourceRegistry registry = registry(new ConcurrentHashMap<>());

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, create(resource, "Draft", mutationId), "actor-a");
            assertFalse(result.handled());
            assertEquals(409, result.transportCode());
            assertEquals(SqliteProtocolResourceMutationAuthority.ACTOR_CONFLICT_CODE, result.code());
            assertNull(result.response());
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement schema = connection.prepareStatement("SELECT value FROM resource_mutation_authority_meta WHERE key = 'schema'");
             var schemaResult = schema.executeQuery()) {
            assertTrue(schemaResult.next());
            assertEquals(SqliteProtocolResourceMutationAuthority.SCHEMA, schemaResult.getString(1));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement actor = connection.prepareStatement("SELECT actor_id FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            actor.setString(1, mutationId.toString());
            try (var result = actor.executeQuery()) {
                assertTrue(result.next());
                assertEquals(SqliteProtocolResourceMutationAuthority.LEGACY_ACTOR, result.getString(1));
            }
        }
    }

    @Test
    void pendingRecoveryUsesTheStoredActor(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("resource.db");
        ServerResourceLocator resource = resource("pending-recovery");
        UUID mutationId = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        CanonicalPayload<Map<String, Object>> payload = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Recovered"));
        writePendingReceipt(database, resource, mutationId, "stored-actor", payload);
        FlowResourceRegistry registry = registry(new ConcurrentHashMap<>());

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            JsonObject recovered = (JsonObject) registry.get(ReSyncResourceCatalog.GUI).get(resource.id());
            assertEquals("Recovered", recovered.get("name").getAsString());
            assertEquals("stored-actor", registry.auditSnapshot().getLast().actor());
        }
    }

    private static FlowResourceRegistry registry(Map<String, JsonObject> values) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new FixtureAdapter(values));
        return registry;
    }

    private static SqliteProtocolResourceMutationAuthority authority(FlowResourceRegistry registry, Path directory) {
        return new SqliteProtocolResourceMutationAuthority(registry, SERVER, directory.resolve("resource.db"),
            CoreGraphResourceAuthority.unavailable(), AuthorityEpoch.fixed(1L));
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER, TYPE, id);
    }

    private static ProtocolEnvelope<Map<String, Object>> create(ServerResourceLocator resource, String name, UUID mutationId) {
        CanonicalPayload<Map<String, Object>> payload = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", name));
        ResourceCreateRequest<Map<String, Object>> operation = new ResourceCreateRequest<>(resource, payload, mutationId);
        return envelope(resource, operation);
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource, ResourceOperation operation) {
        ContentHash payloadHash = operation instanceof ResourceCreateRequest<?> create ? create.payloadHash() : null;
        UUID mutationId = operation instanceof ResourceCreateRequest<?> create ? create.mutationId() : null;
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, new CatalogVersion(1, 0), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), SERVER, resource, 0, 1L, mutationId,
            ContractRef.of(new OwnerId("restudio.resync"), new OperationId("resource." + operation.kind().name().toLowerCase())),
            Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources"))),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")), null, payloadHash, false,
            null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(operation));
    }

    private static ProtocolEnvelopeDispatchResult mutate(SqliteProtocolResourceMutationAuthority authority,
                                                          ProtocolEnvelope<Map<String, Object>> request, String actor) {
        ConnectionInfo connection = connection(actor);
        return authority.mutate(connection, session(connection, actor), request,
            ((ProtocolBody.ResourceRequest) request.body()).operation());
    }

    private static ConnectionInfo connection(String actor) {
        ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId(actor);
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        return connection;
    }

    private static Session session(ConnectionInfo connection, String actor) {
        return new Session("session-" + actor, actor, connection, new ClientIdentity(actor, "2.1.0"));
    }

    private static void writeLegacyReceipt(Path database, ServerResourceLocator resource, UUID mutationId) throws Exception {
        Files.createDirectories(database.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE resource_mutation_authority_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO resource_mutation_authority_meta(key, value) VALUES('schema', 'resource-mutation-authority-v1')");
            statement.execute("CREATE TABLE resource_mutation_state(resource TEXT PRIMARY KEY, revision INTEGER NOT NULL, mutation_id TEXT NOT NULL, payload_hash TEXT NOT NULL, deleted INTEGER NOT NULL, payload TEXT, updated_at INTEGER NOT NULL)");
            statement.execute("""
                CREATE TABLE resource_mutation_receipt(
                    mutation_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, operation TEXT NOT NULL,
                    requested_resource TEXT NOT NULL, response_resource TEXT NOT NULL, source_resource TEXT,
                    target_resource TEXT, expected_revision INTEGER NOT NULL, precondition_hash TEXT NOT NULL,
                    status TEXT NOT NULL, result_revision INTEGER NOT NULL, result_mutation_id TEXT NOT NULL,
                    result_hash TEXT NOT NULL, result_deleted INTEGER NOT NULL, result_payload TEXT,
                    sequence INTEGER NOT NULL, error_code TEXT NOT NULL, error_message TEXT NOT NULL,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                """);
        }
        insertReceipt(database, resource, mutationId, null, "stored-fingerprint", "APPLIED", 1,
            mutationId, "legacy-hash", false, null, 0, "", "");
    }

    private static void writePendingReceipt(Path database, ServerResourceLocator resource, UUID mutationId,
                                            String actor, CanonicalPayload<Map<String, Object>> payload) throws Exception {
        Files.createDirectories(database.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE resource_mutation_authority_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO resource_mutation_authority_meta(key, value) VALUES('schema', '" + SqliteProtocolResourceMutationAuthority.SCHEMA + "')");
            statement.execute("CREATE TABLE resource_mutation_state(resource TEXT PRIMARY KEY, revision INTEGER NOT NULL, mutation_id TEXT NOT NULL, payload_hash TEXT NOT NULL, deleted INTEGER NOT NULL, payload TEXT, updated_at INTEGER NOT NULL)");
            statement.execute("""
                CREATE TABLE resource_mutation_receipt(
                    mutation_id TEXT PRIMARY KEY, actor_id TEXT NOT NULL, fingerprint TEXT NOT NULL, operation TEXT NOT NULL,
                    requested_resource TEXT NOT NULL, response_resource TEXT NOT NULL, source_resource TEXT,
                    target_resource TEXT, expected_revision INTEGER NOT NULL, precondition_hash TEXT NOT NULL,
                    status TEXT NOT NULL, result_revision INTEGER NOT NULL, result_mutation_id TEXT NOT NULL,
                    result_hash TEXT NOT NULL, result_deleted INTEGER NOT NULL, result_payload TEXT,
                    sequence INTEGER NOT NULL, error_code TEXT NOT NULL, error_message TEXT NOT NULL,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                """);
        }
        insertReceipt(database, resource, mutationId, actor, "stored-fingerprint", "PENDING", 1,
            mutationId, payload.checksum().canonicalText(), false, payload.value(), 0, "", "");
    }

    private static void insertReceipt(Path database, ServerResourceLocator resource, UUID mutationId, String actor,
                                      String fingerprint, String status, long resultRevision, UUID resultMutationId,
                                      String resultHash, boolean deleted, Map<String, Object> payload, long sequence,
                                      String errorCode, String errorMessage) throws Exception {
        String sql = actor == null
            ? "INSERT INTO resource_mutation_receipt(mutation_id, fingerprint, operation, requested_resource, response_resource, source_resource, target_resource, expected_revision, precondition_hash, status, result_revision, result_mutation_id, result_hash, result_deleted, result_payload, sequence, error_code, error_message, created_at, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            : "INSERT INTO resource_mutation_receipt(mutation_id, actor_id, fingerprint, operation, requested_resource, response_resource, source_resource, target_resource, expected_revision, precondition_hash, status, result_revision, result_mutation_id, result_hash, result_deleted, result_payload, sequence, error_code, error_message, created_at, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, mutationId.toString());
            if (actor != null) {
                statement.setString(index++, actor);
            }
            statement.setString(index++, fingerprint);
            statement.setString(index++, "CREATE");
            statement.setString(index++, resource.canonicalText());
            statement.setString(index++, resource.canonicalText());
            statement.setString(index++, null);
            statement.setString(index++, resource.canonicalText());
            statement.setLong(index++, 0);
            statement.setString(index++, "");
            statement.setString(index++, status);
            statement.setLong(index++, resultRevision);
            statement.setString(index++, resultMutationId.toString());
            statement.setString(index++, resultHash);
            statement.setInt(index++, deleted ? 1 : 0);
            statement.setString(index++, payload == null ? null : new Gson().toJson(payload));
            statement.setLong(index++, sequence);
            statement.setString(index++, errorCode);
            statement.setString(index++, errorMessage);
            statement.setLong(index++, Instant.now().toEpochMilli());
            statement.setLong(index, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static final class FixtureAdapter implements FlowResourceAdapter<JsonObject> {
        private final Map<String, JsonObject> values;
        private final Map<String, FlowResourceMutationStamp> stamps = new ConcurrentHashMap<>();

        private FixtureAdapter(Map<String, JsonObject> values) {
            this.values = values;
        }

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.GUI);
        }

        @Override
        public JsonObject get(String id) {
            JsonObject value = values.get(id);
            return value == null ? null : value.deepCopy();
        }

        @Override
        public List<String> listIds() {
            return List.copyOf(values.keySet());
        }

        @Override
        public JsonObject deserialize(String json) {
            return new Gson().fromJson(json, JsonObject.class);
        }

        @Override
        public String serialize(JsonObject value) {
            return value.toString();
        }

        @Override
        public String id(JsonObject value) {
            return value.get("id").getAsString();
        }

        @Override
        public void save(JsonObject value) {
            save(value, UUID.randomUUID(), currentRevision(id(value)));
        }

        @Override
        public boolean supportsAuthoritativeMutationIdentity() {
            return true;
        }

        @Override
        public void save(JsonObject value, UUID mutationId, long expectedRevision) {
            String id = id(value);
            long currentRevision = currentRevision(id);
            if (currentRevision != expectedRevision) {
                throw new IllegalStateException("Unexpected expected revision");
            }
            JsonObject copy = value.deepCopy();
            values.put(id, copy);
            stamps.put(id, new FlowResourceMutationStamp(ReSyncResourceCatalog.GUI, id, expectedRevision + 1L,
                mutationId, payloadHash(copy), false));
        }

        @Override
        public void delete(String id) {
            values.remove(id);
        }

        @Override
        public void delete(String id, UUID mutationId, long expectedRevision) {
            long currentRevision = currentRevision(id);
            if (currentRevision != expectedRevision || !values.containsKey(id)) {
                throw new IllegalStateException("Unexpected expected revision");
            }
            FlowResourceMutationStamp previous = stamps.get(id);
            values.remove(id);
            stamps.put(id, new FlowResourceMutationStamp(ReSyncResourceCatalog.GUI, id, expectedRevision + 1L,
                mutationId, previous.payloadHash(), true));
        }

        @Override
        public FlowResourceMutationStamp readMutationStamp(String id) {
            return stamps.get(id);
        }

        private long currentRevision(String id) {
            FlowResourceMutationStamp stamp = stamps.get(id);
            return stamp == null ? 0L : stamp.revision();
        }

        private String payloadHash(JsonObject value) {
            Map<String, Object> payload = new Gson().fromJson(value.toString(), Map.class);
            return ResourcePayloadCodecs.json().canonicalize(payload).checksum().canonicalText();
        }

        @Override
        public Set<String> supportedOperations() {
            return Set.of("discover", "query", "get", "create", "validate", "save", "update", "delete");
        }
    }
}
