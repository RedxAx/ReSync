package restudio.resync.flow.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionAndCapabilityProtocolTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    private static final ContentHash CATALOG_HASH = new ContentHash("b".repeat(64));
    private static final ContentHash BINDING_HASH = new ContentHash("c".repeat(64));
    private static final ContractRef<InspectorFieldId> SOURCE = ContractRef.of(new OwnerId("catalog.alpha"),
        InspectorFieldId.of("resource-options"));
    private static final TypedValue TEXT = TypedValue.value(TypeExpr.named(TypeReference.of("system", "text")), "flow-a");
    private static final ProtocolPayloadCodec<Map<String, String>> PAYLOAD_CODEC = new ProtocolPayloadCodec<>() {
        @Override
        public Map<String, String> immutable(Map<String, String> payload) {
            return Map.copyOf(payload);
        }

        @Override
        public ContentHash checksum(Map<String, String> immutablePayload) {
            return CATALOG_HASH;
        }
    };

    @Test
    void optionQueriesCarryTypedContextAndPaginatedResults() {
        ContractRef<CapabilityId> query = capability("resource-options");
        OptionQuery optionQuery = new OptionQuery(SOURCE, query, SERVER, null, Map.of("project", TEXT), Map.of("owner", TEXT),
            "cursor", 25, "flow", 7, "resources-7");
        OptionItem item = new OptionItem(TEXT, "Flow A", "A selectable flow resource", true, null);
        OptionPage page = new OptionPage(SOURCE, query, 7, "resources-7", List.of(item), "next", false, List.of());
        OptionInvalidation invalidation = new OptionInvalidation(SOURCE, query, SERVER, null, 8, "resources-8", Set.of("project"));

        assertEquals(SOURCE, optionQuery.sourceRef());
        assertEquals(SOURCE, page.sourceRef());
        assertEquals(SOURCE, invalidation.sourceRef());
        assertEquals(TEXT, optionQuery.context().get("project"));
        assertEquals("next", page.nextCursor());
        assertEquals(8, invalidation.revision());
        assertThrows(IllegalArgumentException.class, () -> new OptionItem(TEXT, "Unavailable", "Not usable", false, null));
    }

    @Test
    void capabilityNegotiationDistinguishesAdditiveReadOnlyAndUnsupported() {
        ContractRef<CapabilityId> editor = capability("editor.resource");
        ContractRef<CapabilityId> preview = capability("preview.resource");
        CapabilityNegotiationRequest request = new CapabilityNegotiationRequest(new CatalogVersion(1, 0), new CatalogVersion(1, 2),
            Set.of(new CapabilityRequirement(editor, 1, true), new CapabilityRequirement(preview, 1, false)), CATALOG_HASH, BINDING_HASH);
        CapabilityNegotiationResult additive = CapabilityNegotiationResult.additive(new CatalogVersion(1, 1), Set.of(editor), Set.of(preview),
            CATALOG_HASH, BINDING_HASH, List.of());
        CapabilityNegotiationResult readOnly = CapabilityNegotiationResult.readOnly(new CatalogVersion(1, 1), Set.of(), Set.of(editor),
            CATALOG_HASH, BINDING_HASH, "Editor capability is unavailable", List.of());
        CapabilityNegotiationResult unsupported = CapabilityNegotiationResult.unsupported(new CatalogVersion(1, 1), Set.of(editor),
            CATALOG_HASH, BINDING_HASH, "Required editor capability is unavailable", List.of());

        assertEquals(Set.of(editor, preview), request.capabilityIds());
        assertEquals(CapabilityOutcome.ADDITIVE, additive.outcome());
        assertEquals(ProtocolEditability.READ_ONLY_GRAPH, readOnly.editability());
        assertEquals(ProtocolEditability.REJECTED, unsupported.editability());
        assertThrows(IllegalArgumentException.class, () -> new CapabilityNegotiationResult(new CatalogVersion(1, 1), Set.of(editor), Set.of(),
            Set.of(), CapabilityOutcome.READ_ONLY, ProtocolEditability.EDITABLE, CATALOG_HASH, BINDING_HASH, "reason", List.of()));
    }

    @Test
    void optionPageRoundTripsTypedLocatorStatesAndOpaqueUnavailableValues() {
        TypeExpr.ResourceType resourceType = new TypeExpr.ResourceType(TypeReference.of("restudio.resync", "flow"),
            Map.of("futureType", "preserved"));
        ServerResourceLocator locator = new ServerResourceLocator(SERVER, resourceType("flow"), "flow-a",
            Map.of("futureLocator", Map.of("preserved", true)));
        TypeExpr nestedType = TypeExpr.tuple(List.of(
            TypeExpr.list(TypeExpr.optional(resourceType)),
            TypeExpr.map(TypeExpr.named(TypeReference.of("builtin", "string")),
                TypeExpr.result(resourceType, TypeExpr.opaque(TypeReference.of("future.extension", "failure"))))));
        ArrayList<Object> optionalLocators = new ArrayList<>();
        optionalLocators.add(locator);
        optionalLocators.add(null);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("value", locator);
        result.put("futureResult", List.of("preserved"));
        TypedValue nested = TypedValue.value(nestedType, List.of(optionalLocators, Map.of("selected", result)),
            Map.of("futureValue", "preserved"));
        TypeExpr.OpaqueType opaqueType = new TypeExpr.OpaqueType(TypeReference.of("future.extension", "selector"),
            Map.of("futureType", List.of(1, 2)));
        TypedValue opaque = TypedValue.opaque(opaqueType, Map.of("locator", locator.canonicalValue(), "raw", List.of("kept")),
            Map.of("futureValue", true));
        OptionPage page = new OptionPage(SOURCE, capability("resource-options"), 8, "resources-8", List.of(
            new OptionItem(TypedValue.locator(resourceType, locator), "Located", "Top-level locator state", true, null),
            new OptionItem(TypedValue.value(resourceType, locator), "Material", "Top-level value state", true, null),
            new OptionItem(nested, "Unavailable", "Nested typed locator material", false, "Provider is unavailable"),
            new OptionItem(TypedValue.absent(resourceType), "Absent", "No option was supplied", true, null),
            new OptionItem(TypedValue.nullValue(resourceType), "Null", "The option was explicitly cleared", true, null),
            new OptionItem(opaque, "Opaque", "Unknown extension value", true, null)), null, true, List.of());
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);
        ProtocolEnvelope<Map<String, String>> envelope = optionPageEnvelope(page);

        byte[] encoded = codec.encodeBytes(envelope);
        ProtocolEnvelope<Map<String, String>> decoded = codec.decodeBytes(encoded);
        OptionPage decodedPage = ((ProtocolBody.OptionPageResponse) decoded.body()).page();

        assertArrayEquals(encoded, codec.encodeBytes(decoded));
        assertEquals(TypedValue.State.LOCATOR, decodedPage.items().get(0).value().state());
        assertEquals(locator, decodedPage.items().get(0).value().locator());
        assertEquals(TypedValue.State.VALUE, decodedPage.items().get(1).value().state());
        assertTrue(decodedPage.items().get(1).value().value() instanceof ServerResourceLocator);
        List<?> decodedTuple = (List<?>) decodedPage.items().get(2).value().value();
        assertTrue(((List<?>) decodedTuple.get(0)).get(0) instanceof ServerResourceLocator);
        Map<?, ?> decodedResult = (Map<?, ?>) ((Map<?, ?>) decodedTuple.get(1)).get("selected");
        assertTrue(decodedResult.get("value") instanceof ServerResourceLocator);
        assertEquals(List.of("preserved"), decodedResult.get("futureResult"));
        assertEquals(TypedValue.State.ABSENT, decodedPage.items().get(3).value().state());
        assertEquals(TypedValue.State.NULL, decodedPage.items().get(4).value().state());
        assertEquals(TypedValue.State.OPAQUE, decodedPage.items().get(5).value().state());
        assertTrue(decodedPage.items().get(5).value().value() instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) decodedPage.items().get(5).value().value()).get("locator") instanceof Map<?, ?>);
        assertEquals("Provider is unavailable", decodedPage.items().get(2).reason());
        assertEquals("preserved", decodedPage.items().get(2).value().unknown().get("futureValue"));
    }

    @Test
    void optionQueryAndInvalidationRoundTripWithIndependentSourceIdentity() {
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);
        ContractRef<CapabilityId> provider = capability("resource-options");
        OptionQuery query = new OptionQuery(SOURCE, provider, SERVER, null, Map.of("project", TEXT), Map.of(), null, 25,
            null, 4, "resources-4");
        ProtocolEnvelope<Map<String, String>> queryEnvelope = optionEnvelope(ProtocolEnvelope.Kind.REQUEST,
            new ProtocolBody.OptionQueryRequest(query), new CatalogVersion(1, 3),
            Set.of(ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY));
        OptionInvalidation invalidation = new OptionInvalidation(SOURCE, provider, SERVER, null, 5, "resources-5",
            Set.of("zeta", "alpha"));
        ProtocolEnvelope<Map<String, String>> invalidationEnvelope = optionEnvelope(ProtocolEnvelope.Kind.EVENT,
            new ProtocolBody.OptionInvalidationEvent(invalidation), new CatalogVersion(1, 3),
            Set.of(ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY));

        ProtocolEnvelope<Map<String, String>> decodedQueryEnvelope = codec.decodeBytes(codec.encodeBytes(queryEnvelope));
        String invalidationWire = codec.encodeText(invalidationEnvelope);
        ProtocolEnvelope<Map<String, String>> decodedInvalidationEnvelope = codec.decodeText(invalidationWire);
        OptionQuery decodedQuery = ((ProtocolBody.OptionQueryRequest) decodedQueryEnvelope.body()).query();
        OptionInvalidation decodedInvalidation = ((ProtocolBody.OptionInvalidationEvent) decodedInvalidationEnvelope.body()).invalidation();

        assertEquals(SOURCE, decodedQuery.sourceRef());
        assertEquals(provider, decodedQuery.query());
        assertEquals(SOURCE, decodedInvalidation.sourceRef());
        assertEquals(Set.of("alpha", "zeta"), decodedInvalidation.dependencyKeys());
        assertTrue(invalidationWire.indexOf("alpha") < invalidationWire.indexOf("zeta"));
        assertThrows(IllegalArgumentException.class, () -> new OptionInvalidation(SOURCE, provider, SERVER, null, 5,
            "resources-5", Set.of("")));

        String sourceField = ",\"sourceRef\":{\"localId\":\"resource-options\",\"ownerId\":\"catalog.alpha\"}";
        String missingSource = invalidationWire.replace(sourceField, "");
        assertTrue(missingSource.length() < invalidationWire.length());
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(missingSource));
        ProtocolEnvelope<Map<String, String>> collision = optionEnvelope(ProtocolEnvelope.Kind.EVENT,
            new ProtocolBody.OptionInvalidationEvent(invalidation, Map.of("sourceRef", "override")), new CatalogVersion(1, 3),
            Set.of(ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeBytes(collision));
    }

    @Test
    void optionProtocolRequiresVersionCapabilityAndExactEnvelopeKind() {
        ContractRef<CapabilityId> provider = capability("resource-options");
        OptionQuery query = new OptionQuery(SOURCE, provider, SERVER, null, Map.of(), Map.of(), null, 25, null, 0,
            "resources-0");
        OptionPage page = new OptionPage(SOURCE, provider, 0, "resources-0", List.of(), null, true, List.of());
        OptionInvalidation invalidation = new OptionInvalidation(SOURCE, provider, SERVER, null, 1, "resources-1", Set.of());
        Set<ContractRef<CapabilityId>> supported = Set.of(ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY);
        Set<ContractRef<CapabilityId>> wrongOwner = Set.of(ContractRef.of(new OwnerId("catalog.alpha"),
            CapabilityId.of("option_queries")));

        assertThrows(IllegalArgumentException.class, () -> optionEnvelope(ProtocolEnvelope.Kind.REQUEST,
            new ProtocolBody.OptionQueryRequest(query), new CatalogVersion(1, 2), supported));
        assertThrows(IllegalArgumentException.class, () -> optionEnvelope(ProtocolEnvelope.Kind.REQUEST,
            new ProtocolBody.OptionQueryRequest(query), new CatalogVersion(1, 3), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> optionEnvelope(ProtocolEnvelope.Kind.REQUEST,
            new ProtocolBody.OptionQueryRequest(query), new CatalogVersion(1, 3), wrongOwner));
        assertThrows(IllegalArgumentException.class, () -> optionEnvelope(ProtocolEnvelope.Kind.RESPONSE,
            new ProtocolBody.OptionQueryRequest(query), new CatalogVersion(1, 3), supported));
        assertThrows(IllegalArgumentException.class, () -> optionEnvelope(ProtocolEnvelope.Kind.REQUEST,
            new ProtocolBody.OptionPageResponse(page), new CatalogVersion(1, 3), supported));
        assertThrows(IllegalArgumentException.class, () -> optionEnvelope(ProtocolEnvelope.Kind.RESPONSE,
            new ProtocolBody.OptionInvalidationEvent(invalidation), new CatalogVersion(1, 3), supported));
    }

    @Test
    void addingOptionNegotiationDoesNotChangeOldControlEnvelopeBytes() {
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);
        ProtocolEnvelope<Map<String, String>> envelope = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 2), UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"), SERVER, null, 0, null,
            operation("control.test"), Set.of(), resourceType("control.request"), null, null, false, null, null, null,
            null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ControlRequest("test", Map.of("value", true)));
        String expected = "{\"body\":{\"action\":\"test\",\"bodyKind\":\"control_request\",\"values\":{\"value\":true}},"
            + "\"capabilities\":[],\"contractVersion\":{\"generation\":1,\"minor\":2},"
            + "\"correlationId\":\"33333333-3333-4333-8333-333333333333\",\"diagnostics\":[],\"kind\":\"request\","
            + "\"messageId\":\"11111111-1111-4111-8111-111111111111\","
            + "\"operation\":{\"localId\":\"control.test\",\"ownerId\":\"restudio.resync\"},"
            + "\"payloadType\":{\"localId\":\"control.request\",\"ownerId\":\"restudio.resync\"},"
            + "\"requestId\":\"22222222-2222-4222-8222-222222222222\",\"sequence\":0,"
            + "\"serverId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"status\":\"accepted\","
            + "\"statusCode\":\"accepted\",\"traceId\":\"44444444-4444-4444-8444-444444444444\"}";

        assertEquals(expected, codec.encodeText(envelope));
    }

    @Test
    void optionQueryDecodeRejectsMislabeledBuiltinMaterial() {
        TypeExpr builtinString = TypeExpr.named(TypeReference.of("builtin", "string"));
        TypedValue value = TypedValue.value(builtinString, "valid");
        OptionQuery query = new OptionQuery(SOURCE, capability("resource-options"), SERVER, null,
            Map.of("value", value), Map.of(), null, 25, null, 0, "resources-0");
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);
        ProtocolEnvelope<Map<String, String>> envelope = optionEnvelope(ProtocolEnvelope.Kind.REQUEST,
            new ProtocolBody.OptionQueryRequest(query), new CatalogVersion(1, 3),
            Set.of(ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY));
        String validWire = codec.encodeText(envelope);
        String invalidWire = validWire.replace("\"value\":\"valid\"", "\"value\":3");

        assertTrue(invalidWire.length() < validWire.length());
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(invalidWire));
    }

    private static ContractRef<CapabilityId> capability(String id) {
        return ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of(id));
    }

    private static ProtocolEnvelope<Map<String, String>> optionPageEnvelope(OptionPage page) {
        return optionEnvelope(ProtocolEnvelope.Kind.RESPONSE, new ProtocolBody.OptionPageResponse(page),
            new CatalogVersion(1, 3), Set.of(ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY));
    }

    private static ProtocolEnvelope<Map<String, String>> optionEnvelope(ProtocolEnvelope.Kind kind, ProtocolBody body,
                                                                        CatalogVersion version,
                                                                        Set<ContractRef<CapabilityId>> capabilities) {
        return new ProtocolEnvelope<>(kind, version,
            UUID.fromString("11111111-1111-4111-8111-111111111111"), UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"), UUID.fromString("44444444-4444-4444-8444-444444444444"),
            SERVER, null, 0, null, operation("option.protocol"), capabilities, resourceType("option.protocol"), null, null,
            false, null, null, null, null, null, 0, ProtocolEnvelope.Status.OK, List.of(), Map.of(), body);
    }

    private static ContractRef<OperationId> operation(String id) {
        return ContractRef.of(new OwnerId("restudio.resync"), OperationId.of(id));
    }

    private static ContractRef<ResourceTypeId> resourceType(String id) {
        return ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of(id));
    }
}
