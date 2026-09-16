package restudio.resync.server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.protocol.OptionItem;
import restudio.resync.flow.protocol.OptionPage;
import restudio.resync.flow.protocol.OptionQuery;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceQueryRequest;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.security.ClientIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourceOptionProtocolTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerId OTHER_SERVER = new ServerId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ContractRef<CapabilityId> QUERY = ContractRef.of(OWNER, CapabilityId.of("gui-options"));
    private static final ContractRef<InspectorFieldId> SOURCE_REF = ContractRef.of(OWNER, InspectorFieldId.of("gui-options"));
    private static final ContractRef<ResourceTypeId> GUI_TYPE = ContractRef.of(OWNER,
        ResourceTypeId.of(ReSyncResourceCatalog.GUI));
    private static final TypeExpr GUI_VALUE_TYPE = TypeExpr.resource(TypeReference.of("restudio.resync",
        ReSyncResourceCatalog.GUI));
    private static final OptionQuerySchemaV1 ANY_CONTEXT = OptionQuerySchemaV1.empty();

    @Test
    void returnsDeterministicTypedResourcePagesWithPresentationAndAvailability() {
        Fixture fixture = fixture();
        TypeExpr optionType = new TypeExpr.ResourceType(TypeReference.of("restudio.resync", ReSyncResourceCatalog.GUI),
            Map.of("futureType", true));
        Diagnostic diagnostic = Diagnostic.builder("INSPECTOR.MISSING_FIELD", DiagnosticSeverity.ERROR,
                DiagnosticPhase.SEMANTIC, "inspector-conflict")
            .messageKey(ContractRef.of(OWNER, CapabilityId.of("inspector-validation")))
            .arguments(Map.of("fieldId", "gui-options"))
            .correlationId(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .build();
        OptionQueryAuthority authority = authority(source(optionType, ANY_CONTEXT, 2), List.of(diagnostic));
        FlowResourceProtocolEnvelopeHandler handler = handler(fixture.registry, authority);
        ConnectionInfo connection = authenticatedConnection("client");
        OptionQuery firstQuery = query(SERVER, null, null, 2, null, 0L, "client-initial");

        ProtocolEnvelopeDispatchResult firstResult = handler.handle(connection, session(connection, "client"),
            request(firstQuery, Map.of("futureEnvelope", true), Map.of("futureBody", true)));

        assertTrue(firstResult.handled());
        assertEquals(ProtocolEnvelope.Kind.RESPONSE, firstResult.response().kind());
        assertEquals(0L, firstResult.response().revision());
        assertEquals(OptionQueryAuthority.OPERATION, firstResult.response().operation());
        assertEquals(OptionQueryAuthority.PAGE_TYPE, firstResult.response().payloadType());
        assertEquals(Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY), firstResult.response().capabilities());
        assertEquals(true, firstResult.response().unknown().get("futureEnvelope"));
        ProtocolBody.OptionPageResponse firstBody = assertInstanceOf(ProtocolBody.OptionPageResponse.class,
            firstResult.response().body());
        assertEquals(true, firstBody.unknown().get("futureBody"));
        OptionPage first = firstBody.page();
        assertEquals(2, first.items().size());
        assertFalse(first.complete());
        assertTrue(first.revision() > 0L);
        assertTrue(first.invalidationKey().startsWith("resource-options:"));
        assertEquals(List.of(diagnostic), first.diagnostics());
        assertEquals(first.diagnostics(), firstResult.response().diagnostics());

        OptionItem unavailable = first.items().getFirst();
        assertEquals("beta", unavailable.value().locator().id());
        assertEquals(SERVER, unavailable.value().locator().serverId());
        assertEquals(GUI_TYPE, unavailable.value().locator().type());
        assertEquals(optionType, unavailable.value().type());
        assertEquals("Beta GUI", unavailable.label());
        assertEquals("A disabled GUI resource", unavailable.description());
        assertFalse(unavailable.available());
        assertEquals("Disabled by fixture policy", unavailable.reason());

        OptionItem alpha = first.items().get(1);
        assertEquals("hidden-id", alpha.value().locator().id());
        assertEquals("Visible Alpha", alpha.label());
        assertTrue(alpha.available());
        assertNull(alpha.reason());

        OptionQuery continuation = query(SERVER, null, null, 2, first.nextCursor(), first.revision(), first.invalidationKey());
        ProtocolEnvelopeDispatchResult secondResult = handler.handle(connection, session(connection, "client"), request(continuation));
        OptionPage second = assertInstanceOf(ProtocolBody.OptionPageResponse.class, secondResult.response().body()).page();

        assertTrue(second.complete());
        assertNull(second.nextCursor());
        assertEquals(first.revision(), second.revision());
        assertEquals(first.invalidationKey(), second.invalidationKey());
        assertEquals(List.of("zeta"), second.items().stream().map(item -> item.value().locator().id()).toList());
    }

    @Test
    void searchesLabelsAndDescriptionsBeforeStableIdentityOrdering() {
        Fixture fixture = fixture();
        FlowResourceProtocolEnvelopeHandler handler = handler(fixture.registry,
            authority(source(GUI_VALUE_TYPE, ANY_CONTEXT, 50)));
        ConnectionInfo connection = authenticatedConnection("client");

        OptionPage byLabel = page(handler.handle(connection, session(connection, "client"),
            request(query(SERVER, null, "alpha", 50, null, 0L, "initial"))));
        OptionPage byDescription = page(handler.handle(connection, session(connection, "client"),
            request(query(SERVER, null, "disabled gui", 50, null, 0L, "initial"))));

        assertEquals(List.of("hidden-id"), byLabel.items().stream().map(item -> item.value().locator().id()).toList());
        assertEquals(List.of("beta"), byDescription.items().stream().map(item -> item.value().locator().id()).toList());
        fixture.adapter.order(List.of("zeta", "beta", "hidden-id"));
        OptionPage reordered = page(handler.handle(connection, session(connection, "client"),
            request(query(SERVER, null, "alpha", 50, null, 0L, "initial"))));
        assertEquals(byLabel.revision(), reordered.revision());
        assertEquals(byLabel.invalidationKey(), reordered.invalidationKey());
        assertEquals(byLabel.items(), reordered.items());
    }

    @Test
    void rejectsAContinuationAfterTheAuthoritativeResourceSetChanges() {
        Fixture fixture = fixture();
        FlowResourceProtocolEnvelopeHandler handler = handler(fixture.registry,
            authority(source(GUI_VALUE_TYPE, ANY_CONTEXT, 1)));
        ConnectionInfo connection = authenticatedConnection("client");
        OptionPage first = page(handler.handle(connection, session(connection, "client"),
            request(query(SERVER, null, null, 1, null, 0L, "initial"))));
        fixture.adapter.put(resource("aardvark", "Aardvark", "A newly added GUI resource", true, null));
        OptionQuery stale = query(SERVER, null, null, 1, first.nextCursor(), first.revision(), first.invalidationKey());

        ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"), request(stale));

        assertFalse(result.handled());
        assertEquals(ProtocolRejectionCode.INVALID_CURSOR, result.rejectionCode());
        assertEquals("PROTO.INVALID_CURSOR", result.rejectionCode().wireValue());
        assertEquals("fix_request", result.structured().get("recoveryAction"));
    }

    @Test
    void failsClosedForMissingAuthorityWrongServerAndUnavailableTypes() {
        Fixture fixture = fixture();
        ConnectionInfo connection = authenticatedConnection("client");
        OptionQuery valid = query(SERVER, null, null, 10, null, 0L, "initial");
        FlowResourceProtocolEnvelopeHandler closed = handler(fixture.registry, OptionQueryAuthority.failClosed());

        ProtocolEnvelopeDispatchResult unavailableAuthority = closed.handle(connection, session(connection, "client"), request(valid));
        ProtocolEnvelopeDispatchResult wrongServer = handler(fixture.registry,
            authority(source(GUI_VALUE_TYPE, ANY_CONTEXT, 10))).handle(connection, session(connection, "client"),
            request(query(OTHER_SERVER, null, null, 10, null, 0L, "initial")));
        InspectorOptionSource missingType = source(TypeExpr.resource(TypeReference.of("restudio.resync", "future")),
            ANY_CONTEXT, 10);
        ProtocolEnvelopeDispatchResult unavailableType = handler(fixture.registry, authority(missingType)).handle(connection,
            session(connection, "client"), request(valid));
        InspectorOptionSource wrongOwnerType = source(TypeExpr.resource(TypeReference.of("extension.owner",
            ReSyncResourceCatalog.GUI)), ANY_CONTEXT, 10);
        ProtocolEnvelopeDispatchResult wrongOwner = handler(fixture.registry, authority(wrongOwnerType)).handle(connection,
            session(connection, "client"), request(valid));

        assertEquals(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, unavailableAuthority.rejectionCode());
        assertEquals(ProtocolRejectionCode.AUTHORIZATION_DENIED, wrongServer.rejectionCode());
        assertEquals(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, unavailableType.rejectionCode());
        assertEquals(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, wrongOwner.rejectionCode());
        assertNull(unavailableAuthority.response());
        assertNull(wrongServer.response());
        assertNull(unavailableType.response());
        assertNull(wrongOwner.response());
    }

    @Test
    void rejectsNonResourceOptionTypesAndMismatchedTypedContext() {
        Fixture fixture = fixture();
        ConnectionInfo connection = authenticatedConnection("client");
        OptionQuery valid = query(SERVER, null, null, 10, null, 0L, "initial");
        ProtocolEnvelopeDispatchResult scalar = handler(fixture.registry,
            authority(source(TypeExpr.named(TypeReference.of("builtin", "string")), ANY_CONTEXT, 10)))
            .handle(connection, session(connection, "client"), request(valid));
        ServerResourceLocator wrongContext = new ServerResourceLocator(SERVER,
            ContractRef.of(OWNER, ResourceTypeId.of("flow")), "context");
        ProtocolEnvelopeDispatchResult context = handler(fixture.registry,
            authority(source(GUI_VALUE_TYPE, OptionQuerySchemaV1.requiredResource((TypeExpr.ResourceType) GUI_VALUE_TYPE), 10)))
            .handle(connection, session(connection, "client"),
            request(query(SERVER, wrongContext, null, 10, null, 0L, "initial")));
        ServerResourceLocator foreignLocator = new ServerResourceLocator(OTHER_SERVER, GUI_TYPE, "foreign");
        OptionQuery foreignTypedContext = new OptionQuery(SOURCE_REF, QUERY, SERVER, null,
            Map.of("gui", TypedValue.locator(GUI_VALUE_TYPE, foreignLocator)), Map.of(), null, 10, null, 0L, "initial");
        ProtocolEnvelopeDispatchResult foreign = handler(fixture.registry,
            authority(source(GUI_VALUE_TYPE, ANY_CONTEXT, 10))).handle(connection, session(connection, "client"),
            request(foreignTypedContext));

        assertEquals(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, scalar.rejectionCode());
        assertEquals(ProtocolRejectionCode.INVALID_PAYLOAD, context.rejectionCode());
        assertEquals(ProtocolRejectionCode.AUTHORIZATION_DENIED, foreign.rejectionCode());
    }

    @Test
    void usesExactCapabilityOwnershipAndRejectsAmbiguousMappings() {
        InspectorOptionSource source = source(GUI_VALUE_TYPE, ANY_CONTEXT, 10);
        MappedOptionQueryAuthority authority = new MappedOptionQueryAuthority(List.of(
            new OptionQueryAuthority.Source(SOURCE_REF, source, "catalog-4", 4L, List.of())));
        ContractRef<CapabilityId> wrongOwner = ContractRef.of(OwnerId.of("extension.owner"), QUERY.id());

        assertEquals(source, authority.require(SOURCE_REF, QUERY).descriptor());
        assertEquals(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
            assertThrows(OptionQueryAuthority.Rejected.class, () -> authority.require(SOURCE_REF, wrongOwner)).code());
        assertThrows(IllegalArgumentException.class, () -> new MappedOptionQueryAuthority(List.of(
            new OptionQueryAuthority.Source(SOURCE_REF, source, "catalog-4", 4L, List.of()),
            new OptionQueryAuthority.Source(SOURCE_REF, source, "catalog-5", 5L, List.of()))));
    }

    @Test
    void authorizesTheResolvedResourceTypeThroughTheExistingResourcePolicy() {
        Fixture fixture = fixture();
        List<ResourceTypeId> authorizedTypes = new ArrayList<>();
        ProtocolResourceAuthorizer authorizer = (connection, session, envelope, operation) -> {
            if (operation instanceof ResourceQueryRequest query) {
                authorizedTypes.add(query.type().id());
            }
            return false;
        };
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(fixture.registry, SERVER,
            ProtocolResourceMutationAuthority.failClosed(), authorizer, AuthorityEpoch.fixed(1L), null,
            authority(source(GUI_VALUE_TYPE, ANY_CONTEXT, 10)));
        ConnectionInfo connection = authenticatedConnection("client");

        ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"),
            request(query(SERVER, null, null, 10, null, 0L, "initial")));

        assertEquals(List.of(ResourceTypeId.of(ReSyncResourceCatalog.GUI)), authorizedTypes);
        assertEquals(ProtocolRejectionCode.AUTHORIZATION_DENIED, result.rejectionCode());
    }

    @Test
    void providerCaptureUsesExactResourceLocatorAndCatalogOwner() {
        Fixture fixture = fixture();
        FlowResourceOptionQueryAdapter adapter = new FlowResourceOptionQueryAdapter(fixture.registry, SERVER);
        InspectorOptionSource descriptor = source(GUI_VALUE_TYPE, ANY_CONTEXT, 10);
        OptionQueryAuthority.Source exact = new OptionQueryAuthority.Source(SOURCE_REF, descriptor,
            "server:resync:" + ReSyncResourceCatalog.GUI, 3L, List.of());
        OptionPage page = adapter.query(query(SERVER, null, null, 10, null, 0L, "initial"), exact,
            new OptionCatalogCapture("capture", List.of(new OptionCatalogItem("beta")), "available", ""));

        ServerResourceLocator locator = page.items().getFirst().value().locator();
        assertEquals(SERVER, locator.serverId());
        assertEquals(GUI_TYPE, locator.type());
        assertEquals("beta", locator.id());

        OptionQueryAuthority.Source foreign = new OptionQueryAuthority.Source(SOURCE_REF, descriptor,
            "server:other:gui", 3L, List.of());
        assertEquals(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
            assertThrows(OptionQueryAuthority.Rejected.class, () -> adapter.query(
                query(SERVER, null, null, 10, null, 0L, "initial"), foreign,
                new OptionCatalogCapture("capture", List.of(new OptionCatalogItem("beta")), "available", ""))).code());
    }

    @Test
    void providerCaptureMaterializesOnlyExactBuiltinScalarsAndOpaqueValues() {
        FlowResourceOptionQueryAdapter adapter = new FlowResourceOptionQueryAdapter(new FlowResourceRegistry(), SERVER);
        assertEquals("value", providerValue(adapter, TypeExpr.named(TypeReference.of("builtin", "string")), "value"));
        assertEquals(Boolean.TRUE, providerValue(adapter, TypeExpr.named(TypeReference.of("builtin", "boolean")), "true"));
        assertEquals(new BigInteger("42"), providerValue(adapter, TypeExpr.named(TypeReference.of("builtin", "integer")), "42"));
        assertEquals(new BigDecimal("1.25"), providerValue(adapter, TypeExpr.named(TypeReference.of("builtin", "number")), "1.25"));
        UUID uuid = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertEquals(uuid, providerValue(adapter, TypeExpr.named(TypeReference.of("builtin", "uuid")), uuid.toString()));
        assertEquals("raw", providerValue(adapter, TypeExpr.opaque(TypeReference.of("extension.owner", "future")), "raw"));
    }

    @Test
    void providerCaptureRejectsInvalidLexemesCompositesForeignTypesAndCanonicalDuplicates() {
        FlowResourceOptionQueryAdapter adapter = new FlowResourceOptionQueryAdapter(new FlowResourceRegistry(), SERVER);
        List<TypeValue> invalid = List.of(
            new TypeValue(TypeExpr.named(TypeReference.of("builtin", "boolean")), "TRUE"),
            new TypeValue(TypeExpr.named(TypeReference.of("builtin", "integer")), "01"),
            new TypeValue(TypeExpr.named(TypeReference.of("builtin", "number")), "1.0"),
            new TypeValue(TypeExpr.named(TypeReference.of("builtin", "uuid")), "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")
        );
        for (TypeValue value : invalid) {
            assertEquals(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                assertThrows(OptionQueryAuthority.Rejected.class,
                    () -> providerPage(adapter, value.type(), List.of(value.value()))).code());
        }
        List<TypeExpr> unsupported = List.of(
            TypeExpr.list(TypeExpr.named(TypeReference.of("builtin", "string"))),
            TypeExpr.named(TypeReference.of("extension.owner", "string")),
            TypeExpr.named(TypeReference.of("builtin", "string"), TypeExpr.named(TypeReference.of("builtin", "string")))
        );
        for (TypeExpr type : unsupported) {
            assertEquals(ProtocolRejectionCode.UNSUPPORTED_GENERATION,
                assertThrows(OptionQueryAuthority.Rejected.class,
                    () -> providerPage(adapter, type, List.of("value"))).code());
        }
        assertEquals(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
            assertThrows(OptionQueryAuthority.Rejected.class, () -> providerPage(adapter,
                TypeExpr.named(TypeReference.of("builtin", "integer")), List.of("1", "1"))).code());
    }

    @Test
    void scalarAuthorizationDoesNotInvokeTheResourceAuthorizer() {
        Fixture fixture = fixture();
        AtomicInteger resourceAuthorizations = new AtomicInteger();
        ProtocolResourceAuthorizer authorizer = (connection, session, envelope, operation) -> {
            resourceAuthorizations.incrementAndGet();
            return false;
        };
        InspectorOptionSource scalar = source(TypeExpr.named(TypeReference.of("builtin", "string")), ANY_CONTEXT, 10);
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(fixture.registry, SERVER,
            ProtocolResourceMutationAuthority.failClosed(), authorizer, AuthorityEpoch.fixed(1L), null, authority(scalar));
        ConnectionInfo connection = authenticatedConnection("client");
        Session session = session(connection, "client");

        assertTrue(handler.authorize(connection, session,
            request(query(SERVER, null, null, 10, null, 0L, "initial"))));
        assertEquals(0, resourceAuthorizations.get());
    }

    @Test
    void failsClosedWhenTheResourceAuthorityReturnsDuplicateIdentities() {
        Fixture fixture = fixture();
        fixture.adapter.order(List.of("beta", "beta"));
        FlowResourceProtocolEnvelopeHandler handler = handler(fixture.registry,
            authority(source(GUI_VALUE_TYPE, ANY_CONTEXT, 10)));
        ConnectionInfo connection = authenticatedConnection("client");

        ProtocolEnvelopeDispatchResult result = handler.handle(connection, session(connection, "client"),
            request(query(SERVER, null, null, 10, null, 0L, "initial")));

        assertEquals(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, result.rejectionCode());
        assertNull(result.response());
    }

    private static Fixture fixture() {
        FixtureAdapter adapter = new FixtureAdapter();
        adapter.put(resource("zeta", "Zeta GUI", "The final GUI resource", true, null));
        adapter.put(resource("hidden-id", "Visible Alpha", "A searchable GUI resource", true, null));
        adapter.put(resource("beta", "Beta GUI", "A disabled GUI resource", false, "Disabled by fixture policy"));
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(adapter);
        return new Fixture(registry, adapter);
    }

    private static Object providerValue(FlowResourceOptionQueryAdapter adapter, TypeExpr type, String value) {
        return providerPage(adapter, type, List.of(value)).items().getFirst().value().value();
    }

    private static OptionPage providerPage(FlowResourceOptionQueryAdapter adapter, TypeExpr type, List<String> values) {
        InspectorOptionSource descriptor = source(type, ANY_CONTEXT, 50);
        OptionQueryAuthority.Source source = new OptionQueryAuthority.Source(SOURCE_REF, descriptor, "server:test:scalar", 1L,
            List.of());
        List<OptionCatalogItem> items = values.stream().map(OptionCatalogItem::new).toList();
        return adapter.query(query(SERVER, null, null, 50, null, 0L, "initial"), source,
            new OptionCatalogCapture("capture", items, "available", ""));
    }

    private static JsonObject resource(String id, String name, String description, boolean enabled, String unavailableReason) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("name", name);
        value.addProperty("description", description);
        value.addProperty("enabled", enabled);
        if (unavailableReason != null) {
            value.addProperty("unavailableReason", unavailableReason);
        }
        return value;
    }

    private static OptionQueryAuthority authority(InspectorOptionSource source) {
        return authority(source, List.of());
    }

    private static OptionQueryAuthority authority(InspectorOptionSource source, List<Diagnostic> diagnostics) {
        return new MappedOptionQueryAuthority(List.of(new OptionQueryAuthority.Source(SOURCE_REF, source, "catalog-7", 7L, diagnostics)));
    }

    private static InspectorOptionSource source(TypeExpr optionType, Object contextSchema, int pageLimit) {
        return new InspectorOptionSource(InspectorFieldId.of("gui-options"), "GUI Resources",
            "Lists GUI resources from the authoritative server catalog.", optionType, contextSchema, QUERY, pageLimit,
            "gui-resources");
    }

    private static OptionQuery query(ServerId serverId, ServerResourceLocator resource, String search, int limit,
                                     String cursor, long revision, String invalidationKey) {
        return new OptionQuery(SOURCE_REF, QUERY, serverId, resource, Map.of(), Map.of(), cursor, limit, search, revision,
            invalidationKey);
    }

    private static ProtocolEnvelope<Map<String, Object>> request(OptionQuery query) {
        return request(query, Map.of(), Map.of());
    }

    private static ProtocolEnvelope<Map<String, Object>> request(OptionQuery query, Map<String, Object> envelopeUnknown,
                                                                 Map<String, Object> bodyUnknown) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST,
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), query.serverId(), null, 0L, 1L, null, OptionQueryAuthority.OPERATION,
            Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY),
            OptionQueryAuthority.PAGE_TYPE, null, null, false, null, null, null, null, null, 0L,
            ProtocolEnvelope.Status.ACCEPTED, List.of(), envelopeUnknown,
            new ProtocolBody.OptionQueryRequest(query, bodyUnknown));
    }

    private static OptionPage page(ProtocolEnvelopeDispatchResult result) {
        assertTrue(result.handled());
        return assertInstanceOf(ProtocolBody.OptionPageResponse.class, result.response().body()).page();
    }

    private static FlowResourceProtocolEnvelopeHandler handler(FlowResourceRegistry registry,
                                                               OptionQueryAuthority authority) {
        return new FlowResourceProtocolEnvelopeHandler(registry, SERVER, ProtocolResourceMutationAuthority.failClosed(),
            ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(1L), null, authority);
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

    private record Fixture(FlowResourceRegistry registry, FixtureAdapter adapter) {
    }

    private record TypeValue(TypeExpr type, String value) {
    }

    private static final class FixtureAdapter implements FlowResourceAdapter<JsonObject> {
        private final Map<String, JsonObject> values = new LinkedHashMap<>();
        private List<String> order = new ArrayList<>();

        private void put(JsonObject value) {
            String id = value.get("id").getAsString();
            values.put(id, value.deepCopy());
            if (!order.contains(id)) {
                order.add(id);
            }
        }

        private void order(List<String> values) {
            order = new ArrayList<>(values);
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
            return List.copyOf(order);
        }

        @Override
        public JsonObject deserialize(String json) {
            throw new UnsupportedOperationException();
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
            put(value);
        }

        @Override
        public void delete(String id) {
            values.remove(id);
            order.remove(id);
        }
    }
}
