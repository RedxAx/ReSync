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
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceLoadRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.security.ClientIdentity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProtocolResourceReadAuthorityTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ContractRef<ResourceTypeId> TYPE = ContractRef.of(new OwnerId("restudio.resync"),
        new ResourceTypeId(ReSyncResourceCatalog.GUI));

    @Test
    void loadAndListUseDurableRevisionAcrossRestartAndExposeTombstone(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        FlowResourceRegistry registry = registry(values);
        ServerResourceLocator resource = resource("durable-read");

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult created = mutate(authority, create(resource, "Draft"));
            assertTrue(created.handled(), created.code() + ": " + created.message());
            ProtocolEnvelopeDispatchResult saved = mutate(authority, save(resource, 1, "Updated"));
            assertTrue(saved.handled(), saved.code() + ": " + saved.message());

            FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER, authority,
                ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
            ProtocolBody.ResourceDocumentResponse loaded = documentResponse(dispatch(handler, load(resource)));
            assertEquals(2, loaded.document().revision());
            assertEquals("Updated", ((Map<?, ?>) loaded.document().payload()).get("name"));

            ProtocolBody.ResourcePageResponse page = pageResponse(dispatch(handler, list()));
            assertEquals(1, page.page().items().size());
            assertEquals(2, page.page().items().getFirst().revision());

            ProtocolEnvelopeDispatchResult deleted = mutate(authority, delete(resource, 2));
            assertTrue(deleted.handled(), deleted.code() + ": " + deleted.message());
        }

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER, authority,
                ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
            ProtocolBody.ResourceDocumentResponse tombstone = documentResponse(dispatch(handler, load(resource)));
            assertTrue(tombstone.document().deleted());
            assertEquals(3, tombstone.document().revision());
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

    private static ProtocolEnvelope<Map<String, Object>> create(ServerResourceLocator resource, String name) {
        Map<String, Object> payload = Map.of("id", resource.id(), "name", name);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return envelope(resource, new ResourceCreateRequest<>(resource, canonical, UUID.randomUUID()));
    }

    private static ProtocolEnvelope<Map<String, Object>> save(ServerResourceLocator resource, long revision, String name) {
        Map<String, Object> payload = Map.of("id", resource.id(), "name", name);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return envelope(resource, new ResourceSaveRequest<>(resource, revision, canonical, UUID.randomUUID()));
    }

    private static ProtocolEnvelope<Map<String, Object>> delete(ServerResourceLocator resource, long revision) {
        return envelope(resource, new ResourceDeleteRequest(resource, revision, UUID.randomUUID()));
    }

    private static ProtocolEnvelope<Map<String, Object>> load(ServerResourceLocator resource) {
        return envelope(resource, new ResourceLoadRequest(resource));
    }

    private static ProtocolEnvelope<Map<String, Object>> list() {
        return envelope(null, new ResourceListRequest(TYPE, null, 20, null));
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource, ResourceOperation operation) {
        ServerId serverId = resource == null ? SERVER : resource.serverId();
        long revision = operation instanceof ResourceSaveRequest<?> save ? save.expectedRevision()
            : operation instanceof ResourceDeleteRequest delete ? delete.expectedRevision() : 0;
        UUID mutation = operation instanceof ResourceCreateRequest<?> create ? create.mutationId()
            : operation instanceof ResourceSaveRequest<?> save ? save.mutationId()
            : operation instanceof ResourceDeleteRequest delete ? delete.mutationId() : null;
        ContentHash payloadHash = payloadHash(operation);
        long authorityEpoch = switch (operation.kind()) {
            case CREATE, SAVE, DELETE -> 1L;
            default -> 0L;
        };
        ContractRef<ResourceTypeId> responseType = ContractRef.of(new OwnerId("restudio.resync"),
            new ResourceTypeId(switch (operation.kind()) {
                case LIST, QUERY -> "resource.page";
                default -> "resource.document";
            }));
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, new CatalogVersion(1, 0), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), serverId, resource, revision, authorityEpoch, mutation,
            ContractRef.of(new OwnerId("restudio.resync"), new OperationId("resource." + operation.kind().name().toLowerCase())),
            Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources"))),
            responseType, null, payloadHash, false,
            null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(operation));
    }

    private static ContentHash payloadHash(ResourceOperation operation) {
        if (operation instanceof ResourceCreateRequest<?> create) {
            return create.payloadHash();
        }
        if (operation instanceof ResourceSaveRequest<?> save) {
            return save.payloadHash();
        }
        return null;
    }

    private static ProtocolEnvelopeDispatchResult mutate(SqliteProtocolResourceMutationAuthority authority,
                                                          ProtocolEnvelope<Map<String, Object>> request) {
        ConnectionInfo connection = connection();
        return authority.mutate(connection, session(connection), request, ((ProtocolBody.ResourceRequest) request.body()).operation());
    }

    private static ConnectionInfo connection() {
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

    private static Session session(ConnectionInfo connection) {
        return new Session("session", "client", connection, new ClientIdentity("client", "2.1.0"));
    }

    private static ProtocolBody.ResourceDocumentResponse documentResponse(ProtocolEnvelopeDispatchResult result) {
        assertTrue(result.handled(), result.code() + ": " + result.message());
        return assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, result.response().body());
    }

    private static ProtocolBody.ResourcePageResponse pageResponse(ProtocolEnvelopeDispatchResult result) {
        assertTrue(result.handled(), result.code() + ": " + result.message());
        return assertInstanceOf(ProtocolBody.ResourcePageResponse.class, result.response().body());
    }

    private static ProtocolEnvelopeDispatchResult dispatch(FlowResourceProtocolEnvelopeHandler handler,
                                                            ProtocolEnvelope<Map<String, Object>> request) {
        ConnectionInfo connection = connection();
        return handler.handle(connection, session(connection), request);
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
