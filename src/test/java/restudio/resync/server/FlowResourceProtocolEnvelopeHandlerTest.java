package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.AuthoringTemplateRequest;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceLoadRequest;
import restudio.resync.flow.protocol.ResourceMoveRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourceQueryRequest;
import restudio.resync.flow.protocol.ResourceRenameRequest;
import restudio.resync.flow.protocol.ResourceSubscribeRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ProtocolEnvelopeBoundary;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.security.ClientIdentity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourceProtocolEnvelopeHandlerTest {
    private static final UUID SERVER_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REQUEST_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final ServerId SERVER = new ServerId(SERVER_UUID);
    private static final CatalogBinding CORE_BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @Test
    void activatesRegistryBackedLoadAndPreservesUnknownFields() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        JsonObject stored = new JsonObject();
        stored.addProperty("id", "main");
        stored.addProperty("name", "Draft");
        stored.add("futurePayload", new Gson().toJsonTree(Map.of("keep", true)));
        values.put("main", stored);
        registry.register(new FixtureAdapter(values));

        ServerResourceLocator resource = new ServerResourceLocator(new ServerId(SERVER_UUID),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(ReSyncResourceCatalog.GUI)), "main");
        ProtocolEnvelope<Map<String, Object>> request = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            REQUEST_UUID,
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            new ServerId(SERVER_UUID),
            resource,
            0,
            1L,
            null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.load")),
            Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources"))),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of("futureEnvelope", Map.of("keep", true)),
            new ProtocolBody.ResourceRequest(new ResourceLoadRequest(resource), Map.of("futureBody", Map.of("keep", true))));

        ConnectionInfo connection = authenticatedConnection("client");
        Session session = session(connection, "client");
        ProtocolEnvelopeDispatchResult result = new ProtocolEnvelopeDispatchBoundary(new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L)))
            .dispatch(connection, session, new ProtocolEnvelopeBoundary().encode(request));

        assertTrue(result.handled());
        assertEquals(ProtocolEnvelope.Kind.ACK, result.response().kind());
        assertEquals(Map.of("keep", true), result.response().unknown().get("futureEnvelope"));
        ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, result.response().body());
        assertEquals(Map.of("keep", true), body.unknown().get("futureBody"));
        assertFalse(body.document().deleted());
        assertEquals(SERVER, result.response().serverId());
        assertEquals(SERVER, body.document().resource().serverId());
        assertEquals("main", body.document().resource().id());
        assertTrue(body.document().payload() instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) body.document().payload()).get("futurePayload") instanceof Map<?, ?>);
    }

    @Test
    void rejectsUnknownCapabilityAndOwnerBeforeRegistryAccess() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
        assertFalse(handler.supports(new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            UUID.randomUUID(),
            REQUEST_UUID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new ServerId(SERVER_UUID),
            null,
            0,
            null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.list")),
            Set.of(ContractRef.of(new OwnerId("future.owner"), new CapabilityId("future"))),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of(),
                 new ProtocolBody.ResourceRequest(new ResourceListRequest(
                     ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(ReSyncResourceCatalog.GUI)), null, 10, null)))));
    }

    @Test
    void requiresOwnerQualifiedCapabilityAcknowledgementForSameIds() {
        ContractRef<CapabilityId> firstOwner = ContractRef.of(new OwnerId("owner.a"), new CapabilityId("shared"));
        ContractRef<CapabilityId> secondOwner = ContractRef.of(new OwnerId("owner.b"), new CapabilityId("shared"));

        assertTrue(FlowResourceProtocolEnvelopeHandler.acknowledgedCapability(Set.of(firstOwner.canonicalText()), firstOwner));
        assertFalse(FlowResourceProtocolEnvelopeHandler.acknowledgedCapability(Set.of(secondOwner.canonicalText()), firstOwner));
        assertFalse(FlowResourceProtocolEnvelopeHandler.acknowledgedCapability(Set.of(firstOwner.id().value()), firstOwner));
    }

    @Test
    void rejectsAuthoringTemplateWithoutAcknowledgedChecksumAsInvalidPayload() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("flow")), "main");
        AuthoringTemplateProducer producer = new AuthoringTemplateProducer(SERVER,
            () -> { throw new AssertionError("missing checksum must be rejected before activation lookup"); },
            () -> { throw new AssertionError("missing checksum must be rejected before authority lookup"); });

        AuthoringTemplateProducer.Rejected rejection = assertThrows(AuthoringTemplateProducer.Rejected.class,
            () -> producer.produce(new AuthoringTemplateRequest(resource, CatalogCacheKey.of(SERVER, CORE_BINDING))));

        assertEquals(ProtocolRejectionCode.INVALID_PAYLOAD, rejection.code());
        assertEquals(AuthoringTemplateProducer.AUTHORING_CHECKSUM_REQUIRED, rejection.getMessage());
    }

    @Test
    void rejectsAuthoringTemplateForAnUntrustedOwnerEvenWhenTypeMatches() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("evil.owner"), new ResourceTypeId("flow")), "main");
        AuthoringTemplateProducer producer = new AuthoringTemplateProducer(SERVER,
            () -> { throw new AssertionError("untrusted owner must be rejected before activation lookup"); },
            () -> { throw new AssertionError("untrusted owner must be rejected before authority lookup"); });

        AuthoringTemplateProducer.Rejected rejection = assertThrows(AuthoringTemplateProducer.Rejected.class,
            () -> producer.produce(new AuthoringTemplateRequest(resource, CatalogCacheKey.of(SERVER, CORE_BINDING),
                new ContentHash("c".repeat(64)))));

        assertEquals(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, rejection.code());
        assertEquals("Authoring templates are available only for server-owned Core graph resources", rejection.getMessage());
    }

    @Test
    void acceptsNormalResourceRequestAtGenericResourceContractVersion() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        JsonObject stored = new JsonObject();
        stored.addProperty("id", "main");
        stored.addProperty("name", "Draft");
        values.put("main", stored);
        registry.register(new FixtureAdapter(values));
        ServerResourceLocator resource = resource("main");
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
        ProtocolEnvelope<Map<String, Object>> request = loadRequest(SERVER, resource,
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        ConnectionInfo connection = authenticatedConnection("client");

        assertTrue(handler.supports(request));
        ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"), request);

        assertTrue(result.handled());
        assertEquals(ProtocolEnvelope.Kind.ACK, result.response().kind());
        assertEquals(ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION, result.response().contractVersion());
    }

    @Test
    void acceptsPagePayloadTypeForGenericResourceList() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        JsonObject stored = new JsonObject();
        stored.addProperty("id", "main");
        stored.addProperty("name", "Draft");
        values.put("main", stored);
        registry.register(new FixtureAdapter(values));
        ContractRef<ResourceTypeId> type = ContractRef.of(new OwnerId("restudio.resync"),
            new ResourceTypeId(ReSyncResourceCatalog.GUI));
        ResourceListRequest list = new ResourceListRequest(type, null, 100, null);
        ProtocolEnvelope<Map<String, Object>> request = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST,
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION, UUID.randomUUID(), REQUEST_UUID, UUID.randomUUID(),
            UUID.randomUUID(), SERVER, null, 0L, 1L, null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.list")),
            Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources"))),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.page")), null, null, false,
            null, null, null, null, null, 0L, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(list));
        ConnectionInfo connection = authenticatedConnection("client");
        ProtocolEnvelopeDispatchResult result = new ProtocolEnvelopeDispatchBoundary(new FlowResourceProtocolEnvelopeHandler(registry,
            SERVER, ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L)))
            .dispatch(connection, session(connection, "client"), new ProtocolEnvelopeBoundary().encode(request));

        assertTrue(result.handled());
        assertEquals(ProtocolEnvelope.Kind.RESPONSE, result.response().kind());
        ProtocolBody.ResourcePageResponse body = assertInstanceOf(ProtocolBody.ResourcePageResponse.class, result.response().body());
        assertEquals(ResourceOperationKind.LIST, body.operation());
        assertEquals(1, body.page().items().size());
        assertEquals("main", body.page().items().getFirst().resource().id());
    }

    @Test
    void refusesCoreReadWithoutFallingBackToLegacyAdapter() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new ThrowingCoreAdapter());
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            genericOnlyAuthority(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
        ConnectionInfo connection = authenticatedConnection("client");

        ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"),
            loadRequest(SERVER, coreResource("main")));

        assertFalse(result.handled());
        assertEquals(503, result.transportCode());
        assertEquals("RESOURCE_READ_UNAVAILABLE", result.code());
    }

    @Test
    void unavailableCoreListAndQueryRejectWithoutPublishingAnAuthoritativeEmptyPage() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new ThrowingCoreAdapter());
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            genericOnlyAuthority(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
        ContractRef<ResourceTypeId> type = ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow"));

        for (ResourceOperation operation : List.of(new ResourceListRequest(type, null, 100, null),
            new ResourceQueryRequest(type, Map.of(), null, 100, null))) {
            ConnectionInfo connection = authenticatedConnection("client");
            ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"),
                pageRequest(operation));

            assertFalse(result.handled());
            assertEquals(503, result.transportCode());
            assertEquals("RESOURCE_READ_UNAVAILABLE", result.code());
            assertNull(result.response());
        }
    }

    @Test
    void loadsCanonicalCoreEnvelopeMapWithoutLegacyAdapter() {
        ServerResourceLocator resource = coreResource("canonical");
        UUID mutationId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        GraphDocument graph = coreGraph(resource);
        byte[] bytes = boundary.encode(graph,
            new CoreGraphStorageBoundary.AssetMetadata("flow", 1L, mutationId), resource);
        CoreGraphStorageBoundary.Decoded decoded = boundary.decode(bytes, resource);
        CoreGraphResourceAuthority.CoreGraphResourceState state = CoreGraphResourceAuthority.CoreGraphResourceState.live(decoded);
        FlowResourceRegistry registry = coreRegistry(state);
        ConnectionInfo connection = authenticatedConnection("client");

        ProtocolEnvelopeDispatchResult result = new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            new FixtureCoreProtocolAuthority(registry), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L))
            .handle(connection, session(connection, "client"), loadRequest(SERVER, resource));

        assertTrue(result.handled());
        ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
            result.response().body());
        ResourceDocument<?> document = body.document();
        assertFalse(document.deleted());
        assertEquals(ResourceActivationState.ACTIVE, document.activationState());
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = new Gson().fromJson(new String(boundary.encode(decoded), StandardCharsets.UTF_8), Map.class);
        assertEquals(expected, document.payload());
        assertEquals(ResourcePayloadCodecs.json().canonicalize(expected).checksum(), document.payloadHash());
    }

    @Test
    void loadsCoreTombstoneWithoutPayloadOrActivation() {
        ServerResourceLocator resource = coreResource("deleted");
        UUID mutationId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        ContentHash priorPayloadHash = new ContentHash("c".repeat(64));
        CoreGraphStorageBoundary.CoreGraphTombstone tombstone = new CoreGraphStorageBoundary.CoreGraphTombstone(
            resource, 4L, mutationId, priorPayloadHash);
        CoreGraphResourceAuthority.CoreGraphResourceState state = CoreGraphResourceAuthority.CoreGraphResourceState.tombstoned(tombstone);
        FlowResourceRegistry registry = coreRegistry(state);
        ConnectionInfo connection = authenticatedConnection("client");

        ProtocolEnvelopeDispatchResult result = new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            new FixtureCoreProtocolAuthority(registry), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L))
            .handle(connection, session(connection, "client"), loadRequest(SERVER, resource));

        assertTrue(result.handled());
        ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
            result.response().body());
        ResourceDocument<?> document = body.document();
        assertTrue(document.deleted());
        assertEquals(priorPayloadHash, document.payloadHash());
        assertNull(document.payload());
        assertNull(document.canonicalPayload());
        assertNull(document.activationState());
    }

    @Test
    void refusesMutationWithoutDurableAuthority() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        Map<String, Object> payload = Map.of("id", "new-resource", "name", "Draft");
        ServerResourceLocator resource = new ServerResourceLocator(new ServerId(SERVER_UUID),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(ReSyncResourceCatalog.GUI)), "new-resource");
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        ResourceCreateRequest<Map<String, Object>> create = new ResourceCreateRequest<>(resource, canonical, UUID.randomUUID());
        ProtocolEnvelope<Map<String, Object>> request = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            UUID.randomUUID(),
            REQUEST_UUID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new ServerId(SERVER_UUID),
             resource,
             0,
             1L,
             create.mutationId(),
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.create")),
            Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources"))),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")),
            null,
            create.payloadHash(),
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of(),
            new ProtocolBody.ResourceRequest(create));

        ConnectionInfo connection = authenticatedConnection("client");
        ProtocolEnvelopeDispatchResult result = new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L))
            .handle(connection, session(connection, "client"), request);

        assertFalse(result.handled());
        assertEquals(503, result.transportCode());
        assertEquals(FailClosedProtocolResourceMutationAuthority.ERROR_CODE, result.code());
    }

    @Test
    void rejectsUnsupportedMutationShapesExplicitlyWhenDurableAuthorityIsAvailable() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        JsonObject stored = new JsonObject();
        stored.addProperty("id", "main");
        values.put("main", stored);
        registry.register(new FixtureAdapter(values));
        ProtocolResourceMutationAuthority durableAuthority = new ProtocolResourceMutationAuthority() {
            @Override
            public ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connection, Session session,
                                                          ProtocolEnvelope<Map<String, Object>> envelope,
                                                          ResourceOperation operation) {
                throw new AssertionError("Unsupported operation reached the durable mutation boundary");
            }

            @Override
            public boolean durable() {
                return true;
            }
        };
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER, durableAuthority,
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
        ServerResourceLocator resource = new ServerResourceLocator(new ServerId(SERVER_UUID),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(ReSyncResourceCatalog.GUI)), "main");

        for (ResourceOperation operation : List.of(
            new ResourceRenameRequest(resource, 1, "renamed", UUID.randomUUID()),
            new ResourceMoveRequest(resource, 1, "folder", UUID.randomUUID()),
            new ResourceSubscribeRequest(resource, 0, true))) {
            ProtocolEnvelope<Map<String, Object>> request = mutationRequest(operation, resource);
            ConnectionInfo connection = authenticatedConnection("client");
            ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"), request);

            assertFalse(result.handled());
            assertEquals(405, result.transportCode());
            assertEquals("RESOURCE_OPERATION_UNSUPPORTED", result.code());
        }
    }

    @Test
    void routesActivationThroughDurableAuthorityAndReturnsAuthoritativeAck() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new FixtureAdapter(new ConcurrentHashMap<>()));
        ServerResourceLocator resource = resource("main");
        ProtocolResourceMutationAuthority authority = new ProtocolResourceMutationAuthority() {
            @Override
            public ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connection, Session session,
                                                          ProtocolEnvelope<Map<String, Object>> envelope,
                                                          ResourceOperation operation) {
                ResourceActivateRequest activate = assertInstanceOf(ResourceActivateRequest.class, operation);
                ResourceDocument<Map<String, Object>> document = ResourceDocument.live(activate.resource(), 5,
                    activate.mutationId(), ResourcePayloadCodecs.json().canonicalize(Map.of("id", "main")),
                    activate.targetState(), "server");
                return ProtocolEnvelopeDispatchResult.handled(activationResponse(envelope, document,
                    ProtocolEnvelope.Kind.ACK, ProtocolEnvelope.Status.OK));
            }

            @Override
            public boolean durable() {
                return true;
            }

            @Override
            public boolean supportsActivation() {
                return true;
            }
        };
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER, authority,
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
        ProtocolEnvelope<Map<String, Object>> request = activationRequest(resource, 4, ResourceActivationState.INACTIVE);
        ConnectionInfo connection = authenticatedConnection("client");

        assertTrue(handler.supports(request));
        ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"), request);

        assertTrue(result.handled());
        assertEquals(ProtocolEnvelope.Kind.ACK, result.response().kind());
        ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
            result.response().body());
        assertEquals(ResourceOperationKind.ACTIVATE, body.operation());
        assertEquals(5, body.document().revision());
        assertEquals(ResourceActivationState.INACTIVE, body.document().activationState());
    }

    @Test
    void returnsAuthoritativeActivationConflictDocument() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new FixtureAdapter(new ConcurrentHashMap<>()));
        ServerResourceLocator resource = resource("main");
        UUID currentMutation = UUID.fromString("66666666-6666-4666-8666-666666666666");
        ProtocolResourceMutationAuthority authority = new ProtocolResourceMutationAuthority() {
            @Override
            public ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connection, Session session,
                                                          ProtocolEnvelope<Map<String, Object>> envelope,
                                                          ResourceOperation operation) {
                ResourceDocument<Map<String, Object>> current = ResourceDocument.live(resource, 6, currentMutation,
                    ResourcePayloadCodecs.json().canonicalize(Map.of("id", "main", "name", "current")),
                    ResourceActivationState.ACTIVE, "server");
                return ProtocolEnvelopeDispatchResult.handled(activationResponse(envelope, current,
                    ProtocolEnvelope.Kind.CONFLICT, ProtocolEnvelope.Status.CONFLICT));
            }

            @Override
            public boolean durable() {
                return true;
            }

            @Override
            public boolean supportsActivation() {
                return true;
            }
        };
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER, authority,
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
        ProtocolEnvelope<Map<String, Object>> request = activationRequest(resource, 4, ResourceActivationState.ACTIVE);
        ConnectionInfo connection = authenticatedConnection("client");

        ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"), request);

        assertTrue(result.handled());
        assertEquals(ProtocolEnvelope.Kind.CONFLICT, result.response().kind());
        ProtocolBody.ConflictResponse body = assertInstanceOf(ProtocolBody.ConflictResponse.class, result.response().body());
        assertEquals(6, body.current().revision());
        assertEquals(currentMutation, body.current().mutationId());
        assertEquals(ResourceActivationState.ACTIVE, body.current().activationState());
    }

    @Test
    void refusesActivationWhenDurableAuthorityDoesNotAdvertiseIt() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new FixtureAdapter(new ConcurrentHashMap<>()));
        ServerResourceLocator resource = resource("main");
        ProtocolResourceMutationAuthority authority = new ProtocolResourceMutationAuthority() {
            @Override
            public ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connection, Session session,
                                                          ProtocolEnvelope<Map<String, Object>> envelope,
                                                          ResourceOperation operation) {
                throw new AssertionError("Activation reached an authority that does not advertise activation");
            }

            @Override
            public boolean durable() {
                return true;
            }
        };
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER, authority,
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
        ProtocolEnvelope<Map<String, Object>> request = activationRequest(resource, 4, ResourceActivationState.ACTIVE);
        ConnectionInfo connection = authenticatedConnection("client");

        ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"), request);

        assertFalse(result.handled());
        assertEquals(405, result.transportCode());
        assertEquals("RESOURCE_OPERATION_UNSUPPORTED", result.code());
    }

    @Test
    void rejectsForeignServerAndMismatchedAuthenticatedIdentity() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        JsonObject stored = new JsonObject();
        stored.addProperty("id", "main");
        values.put("main", stored);
        registry.register(new FixtureAdapter(values));
        ServerId foreignServer = new ServerId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        ServerResourceLocator foreignResource = new ServerResourceLocator(foreignServer,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(ReSyncResourceCatalog.GUI)), "main");
        ProtocolEnvelope<Map<String, Object>> foreignRequest = loadRequest(foreignServer, foreignResource);
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L));
        ConnectionInfo connection = authenticatedConnection("client");

        assertFalse(handler.authorize(connection, session(connection, "client"), foreignRequest));
        ProtocolEnvelopeDispatchResult foreign = handler.handle(connection, session(connection, "client"), foreignRequest);
        assertFalse(foreign.handled());
        assertEquals(403, foreign.transportCode());

        ServerResourceLocator localResource = new ServerResourceLocator(SERVER, foreignResource.type(), "main");
        ProtocolEnvelope<Map<String, Object>> localRequest = loadRequest(SERVER, localResource);
        assertFalse(handler.authorize(connection, session(connection, "different-client"), localRequest));
    }

    @Test
    void appliesInjectedPerSessionResourceAuthorizationBeforeRegistryAccess() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        JsonObject stored = new JsonObject();
        stored.addProperty("id", "protected");
        values.put("protected", stored);
        registry.register(new FixtureAdapter(values));
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(ReSyncResourceCatalog.GUI)), "protected");
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(registry, SERVER,
            ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.denyAll(), AuthorityEpoch.fixed(1L));
        ConnectionInfo connection = authenticatedConnection("client");
        ProtocolEnvelope<Map<String, Object>> request = loadRequest(SERVER, resource);

        assertFalse(handler.authorize(connection, session(connection, "client"), request));
        ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"), request);
        assertFalse(result.handled());
        assertEquals(403, result.transportCode());
        assertTrue(values.containsKey("protected"));
    }

    @Test
    void serverGrantedAuthorizerFailsClosedForAnUnboundSession() {
        ConnectionInfo connection = authenticatedConnection("client");
        Session otherSession = session(authenticatedConnection("client"), "client");

        assertFalse(ProtocolResourceAuthorizer.serverGranted().authorize(connection, otherSession, null, null));
    }

    private static ProtocolEnvelope<Map<String, Object>> loadRequest(ServerId serverId, ServerResourceLocator resource) {
        return loadRequest(serverId, resource, new CatalogVersion(1, 0));
    }

    private static ProtocolEnvelope<Map<String, Object>> loadRequest(ServerId serverId, ServerResourceLocator resource,
                                                                     CatalogVersion contractVersion) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, contractVersion, UUID.randomUUID(), REQUEST_UUID,
            UUID.randomUUID(), UUID.randomUUID(), serverId, resource, 0, null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.load")),
            Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources"))),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")), null, null, false,
            null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(new ResourceLoadRequest(resource)));
    }

    private static ProtocolEnvelope<Map<String, Object>> pageRequest(ResourceOperation operation) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
            UUID.randomUUID(), REQUEST_UUID, UUID.randomUUID(), UUID.randomUUID(), SERVER, null, 0L, 0L, null,
            ContractRef.of(OwnerId.of("restudio.resync"), OperationId.of("resource."
                + operation.kind().name().toLowerCase(Locale.ROOT))),
            Set.of(ContractRef.of(OwnerId.of("restudio.resync"), CapabilityId.of("resources"))),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("resource.page")), null, null, false,
            null, null, null, null, null, 0L, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(operation));
    }

    private static ProtocolResourceMutationAuthority genericOnlyAuthority() {
        return new ProtocolResourceMutationAuthority() {
            @Override
            public ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connection, Session session,
                                                           ProtocolEnvelope<Map<String, Object>> envelope,
                                                           ResourceOperation operation) {
                throw new UnsupportedOperationException("Fixture authority does not mutate resources");
            }

            @Override
            public boolean authoritativeReads() {
                return true;
            }

            @Override
            public boolean durable() {
                return true;
            }
        };
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(ReSyncResourceCatalog.GUI)), id);
    }

    private static ServerResourceLocator coreResource(String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(ReSyncResourceCatalog.FLOW)), id);
    }

    private static FlowResourceRegistry coreRegistry(CoreGraphResourceAuthority.CoreGraphResourceState state) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.bindCoreGraphResourceAuthority(new FixtureCoreGraphAuthority(state));
        registry.register(new ThrowingCoreAdapter());
        return registry;
    }

    private static GraphDocument coreGraph(ServerResourceLocator resource) {
        return new GraphDocument(new CatalogVersion(1, 0), resource, 1L, CORE_BINDING, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "preserve")));
    }

    private static ProtocolEnvelope<Map<String, Object>> activationRequest(ServerResourceLocator resource, long expectedRevision,
                                                                            ResourceActivationState targetState) {
        ResourceActivateRequest activate = new ResourceActivateRequest(resource, expectedRevision, targetState, UUID.randomUUID());
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, new CatalogVersion(1, 1), UUID.randomUUID(), REQUEST_UUID,
            UUID.randomUUID(), UUID.randomUUID(), SERVER, resource, expectedRevision, 1L, activate.mutationId(),
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.activate")),
            Set.of(ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")), null, null, false,
            null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(activate));
    }

    private static ProtocolEnvelope<Map<String, Object>> activationResponse(ProtocolEnvelope<Map<String, Object>> request,
                                                                             ResourceDocument<Map<String, Object>> document,
                                                                             ProtocolEnvelope.Kind kind,
                                                                             ProtocolEnvelope.Status status) {
        ProtocolBody body = kind == ProtocolEnvelope.Kind.CONFLICT
            ? new ProtocolBody.ConflictResponse(document.resource(), document)
            : new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.ACTIVATE, document);
        return new ProtocolEnvelope<>(kind, request.contractVersion(), UUID.randomUUID(), request.requestId(), request.correlationId(),
            request.traceId(), SERVER, document.resource(), document.revision(), 1L, document.mutationId(),
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.activate")), request.capabilities(),
            request.payloadType(), null, document.payloadHash(), document.deleted(), null, null, null, null, null, request.sequence(),
            status, List.of(), request.unknown(), body);
    }

    private static ProtocolEnvelope<Map<String, Object>> mutationRequest(ResourceOperation operation, ServerResourceLocator resource) {
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            UUID.randomUUID(),
            REQUEST_UUID,
            UUID.randomUUID(),
            UUID.randomUUID(),
             new ServerId(SERVER_UUID),
             resource,
             operation instanceof ResourceSubscribeRequest ? 0 : 1,
             operation instanceof ResourceSubscribeRequest ? 0 : 1,
             operation instanceof ResourceRenameRequest rename ? rename.mutationId()
                : operation instanceof ResourceMoveRequest move ? move.mutationId() : null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource." + operation.kind().name().toLowerCase(Locale.ROOT))),
            Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources"))),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of(),
            new ProtocolBody.ResourceRequest(operation));
    }

    private static FrameSender sender() {
        return new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        };
    }

    private static ConnectionInfo authenticatedConnection(String clientId) {
        ConnectionInfo connection = new ConnectionInfo(null, sender(), 1);
        connection.setClientId(clientId);
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        return connection;
    }

    private static Session session(ConnectionInfo connection, String clientId) {
        return new Session("session", clientId, connection, new ClientIdentity(clientId, "2.1.0"));
    }

    private static final class FixtureAdapter implements FlowResourceAdapter<JsonObject> {
        private final Map<String, JsonObject> values;

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
            values.put(id(value), value.deepCopy());
        }

        @Override
        public void delete(String id) {
            values.remove(id);
        }
    }

    private static final class FixtureCoreGraphAuthority implements CoreGraphResourceAuthority {
        private final CoreGraphResourceState state;

        private FixtureCoreGraphAuthority(CoreGraphResourceState state) {
            this.state = state;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
            return state.resource().equals(resource) && !state.deleted() ? Optional.of(state.envelope()) : Optional.empty();
        }

        @Override
        public List<CoreGraphResourceState> list(String type) {
            return !state.deleted() && state.resource().resourceType().value().equalsIgnoreCase(type)
                ? List.of(state) : List.of();
        }

        @Override
        public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
            return state.resource().equals(resource) ? Optional.of(state) : Optional.empty();
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                      UUID mutationId, long expectedRevision, ContentHash payloadChecksum) {
            throw new UnsupportedOperationException("Fixture authority does not mutate resources");
        }

        @Override
        public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                                   long expectedRevision, ContentHash payloadChecksum) {
            throw new UnsupportedOperationException("Fixture authority does not mutate resources");
        }

        @Override
        public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                          ResourceActivationState activationState, UUID mutationId,
                                                          long expectedRevision, ContentHash payloadChecksum) {
            throw new UnsupportedOperationException("Fixture authority does not mutate resources");
        }
    }

    private static final class FixtureCoreProtocolAuthority implements ProtocolResourceMutationAuthority {
        private final FlowResourceRegistry registry;

        private FixtureCoreProtocolAuthority(FlowResourceRegistry registry) {
            this.registry = registry;
        }

        @Override
        public ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connection, Session session,
                                                       ProtocolEnvelope<Map<String, Object>> envelope,
                                                       ResourceOperation operation) {
            throw new UnsupportedOperationException("Fixture authority does not mutate resources");
        }

        @Override
        public boolean authoritativeReads() {
            return true;
        }

        @Override
        public boolean authoritativeCoreReads() {
            return true;
        }

        @Override
        public ResourceDocument<Map<String, Object>> load(ServerResourceLocator resource) {
            return registry.protocolDocument(resource);
        }

        @Override
        public List<ResourceDocument<Map<String, Object>>> list(ServerId serverId,
                                                                 ContractRef<ResourceTypeId> type,
                                                                 String search) {
            return registry.protocolList(serverId, type, search);
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class ThrowingCoreAdapter implements FlowResourceAdapter<JsonObject> {
        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.FLOW);
        }

        @Override
        public JsonObject get(String id) {
            throw new AssertionError("Core protocol reads must not invoke the legacy adapter");
        }

        @Override
        public List<String> listIds() {
            throw new AssertionError("Core protocol reads must not enumerate the legacy adapter");
        }

        @Override
        public JsonObject deserialize(String json) {
            throw new AssertionError("Core protocol reads must not deserialize through the legacy adapter");
        }

        @Override
        public String serialize(JsonObject value) {
            throw new AssertionError("Core protocol reads must not serialize through the legacy adapter");
        }

        @Override
        public String id(JsonObject value) {
            throw new AssertionError("Core protocol reads must not resolve IDs through the legacy adapter");
        }

        @Override
        public void save(JsonObject value) {
            throw new AssertionError("Core protocol reads must not mutate through the legacy adapter");
        }

        @Override
        public void delete(String id) {
            throw new AssertionError("Core protocol reads must not delete through the legacy adapter");
        }
    }
}
