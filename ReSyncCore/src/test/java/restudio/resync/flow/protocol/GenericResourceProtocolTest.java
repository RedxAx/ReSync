package restudio.resync.flow.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericResourceProtocolTest {
    private static final UUID SERVER_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MUTATION_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final ContentHash HASH = new ContentHash("a".repeat(64));
    private static final ProtocolPayloadCodec<Map<String, String>> PAYLOAD_CODEC = new ProtocolPayloadCodec<>() {
        @Override
        public Map<String, String> immutable(Map<String, String> payload) {
            return Map.copyOf(payload);
        }

        @Override
        public ContentHash checksum(Map<String, String> immutablePayload) {
            return HASH;
        }
    };

    @Test
    void resourceOperationsRemainGenericAndTyped() {
        ServerResourceLocator resource = resource("flow-a");
        HashMap<String, String> mutablePayload = new HashMap<>(Map.of("name", "draft"));
        CanonicalPayload<Map<String, String>> canonicalPayload = PAYLOAD_CODEC.canonicalize(mutablePayload);
        ResourceCreateRequest<Map<String, String>> create = new ResourceCreateRequest<>(resource, canonicalPayload, MUTATION_UUID);
        ResourceSaveRequest<Map<String, String>> save = new ResourceSaveRequest<>(resource, 4, canonicalPayload, MUTATION_UUID);
        ResourceDuplicateRequest duplicate = new ResourceDuplicateRequest(resource, resource("flow-b"), 4, MUTATION_UUID);
        mutablePayload.put("name", "changed");

        assertEquals(ResourceOperationKind.CREATE, create.kind());
        assertEquals(ResourceOperationKind.SAVE, save.kind());
        assertEquals(ResourceOperationKind.DUPLICATE, duplicate.kind());
        assertEquals(resource.type(), duplicate.target().type());
        assertEquals("draft", save.payload().get("name"));
        assertEquals(HASH, save.payloadHash());
        assertThrows(UnsupportedOperationException.class, () -> save.payload().put("other", "value"));
        assertThrows(IllegalArgumentException.class, () -> PAYLOAD_CODEC.verify(Map.of("name", "draft"), new ContentHash("b".repeat(64))));
    }

    @Test
    void envelopeRejectsPayloadOnTombstoneAndChecksServerScope() {
        ServerResourceLocator resource = resource("flow-a");
        ContractRef<OperationId> operation = operation("resource.delete");
        ContractRef<ResourceTypeId> payloadType = resourceType("resource.document");
        TypedValue unknown = TypedValue.opaque(TypeExpr.opaque(TypeReference.of("restudio.resync", "future")), List.of("preserved"));
        ProtocolEnvelope<Map<String, String>> event = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.EVENT,
            new CatalogVersion(1, 0),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            null,
            new ServerId(SERVER_UUID),
            resource,
            5,
            MUTATION_UUID,
            operation,
            Set.of(),
            payloadType,
            null,
            HASH,
            true,
            null,
            null,
            null,
            null,
            null,
            1,
            ProtocolEnvelope.Status.OK,
            List.of(),
            Map.of("futureField", unknown)
        );

        assertTrue(event.deleted());
        assertEquals(unknown, event.unknown().get("futureField"));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolEnvelope<Map<String, String>>(
            ProtocolEnvelope.Kind.EVENT,
            new CatalogVersion(1, 0),
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            null,
            new ServerId(UUID.fromString("99999999-9999-4999-8999-999999999999")),
            resource,
            5,
            MUTATION_UUID,
            operation,
            Set.of(),
            payloadType,
            null,
            HASH,
            true,
            null,
            null,
            null,
            null,
            null,
            1,
            ProtocolEnvelope.Status.OK,
            List.of(),
            Map.of()
        ));
    }

    @Test
    void tombstoneDocumentCannotCarryPayload() {
        ServerResourceLocator resource = resource("flow-a");
        CanonicalPayload<Map<String, String>> canonicalPayload = PAYLOAD_CODEC.canonicalize(Map.of("name", "draft"));
        ResourceDocument<Map<String, String>> tombstone = ResourceDocument.tombstone(resource, 9, MUTATION_UUID, HASH, "server");

        assertTrue(tombstone.deleted());
        assertNull(tombstone.payload());
        assertThrows(IllegalArgumentException.class,
            () -> new ResourceDocument<>(resource, 9, MUTATION_UUID, HASH, true, canonicalPayload, "server"));
    }

    @Test
    void duplicateAndMutationModelsRejectCrossTypeTargets() {
        ServerResourceLocator source = resource("flow-a");
        ServerResourceLocator target = new ServerResourceLocator(new ServerId(SERVER_UUID),
            new ContractRef<>(new OwnerId("restudio.resync"), new ResourceTypeId("function")), "function-a");

        assertThrows(IllegalArgumentException.class, () -> new ResourceDuplicateRequest(source, target, 1, MUTATION_UUID));
        assertEquals(ResourceOperationKind.DELETE, new ResourceDeleteRequest(source, 9, MUTATION_UUID).kind());
    }

    @Test
    void aggregateCreatePresentationRoundTripsExactPrimaryAndMetadataDocuments() {
        ServerResourceLocator primary = resource("flow-a");
        ServerResourceLocator metadata = new ServerResourceLocator(new ServerId(SERVER_UUID),
            new ContractRef<>(new OwnerId("restudio.resync"), new ResourceTypeId("project_metadata")),
            SERVER_UUID.toString());
        CanonicalPayload<Map<String, String>> primaryPayload = PAYLOAD_CODEC.canonicalize(Map.of("name", "draft"));
        CanonicalPayload<Map<String, String>> metadataPayload = PAYLOAD_CODEC.canonicalize(Map.of("name", "project"));
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Draft", "Blueprints/Flows/flow-a.json", 2);
        ResourceCreateRequest<Map<String, String>> create = new ResourceCreateRequest<>(primary, primaryPayload,
            MUTATION_UUID, presentation);
        ProtocolEnvelope<Map<String, String>> request = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 2), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new ServerId(SERVER_UUID), primary, 0, MUTATION_UUID, operation("resource.create"),
            Set.of(ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY), resourceType("resource.document"),
            null, HASH, false, null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(create));
        ResourceCreateResult result = new ResourceCreateResult(
            ResourceDocument.live(primary, 1, MUTATION_UUID, primaryPayload, "server"),
            ResourceDocument.live(metadata, 8, MUTATION_UUID, metadataPayload, "server"), presentation);
        ProtocolEnvelope<Map<String, String>> response = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.ACK,
            new CatalogVersion(1, 2), UUID.randomUUID(), request.requestId(), request.correlationId(), request.traceId(),
            new ServerId(SERVER_UUID), primary, 1, MUTATION_UUID, operation("resource.create"),
            Set.of(ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY), resourceType("resource.document"),
            null, HASH, false, null, null, null, null, null, 0, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourceCreateResponse(result));
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);

        ProtocolEnvelope<Map<String, String>> decodedRequest = codec.decodeBytes(codec.encodeBytes(request));
        ProtocolEnvelope<Map<String, String>> decodedResponse = codec.decodeBytes(codec.encodeBytes(response));

        assertEquals(presentation, ((ResourceCreateRequest<?>) ((ProtocolBody.ResourceRequest) decodedRequest.body()).operation()).presentation());
        assertEquals(presentation, ((ProtocolBody.ResourceCreateResponse) decodedResponse.body()).result().presentation());
        assertEquals(metadata, ((ProtocolBody.ResourceCreateResponse) decodedResponse.body()).result().projectMetadata().resource());
        String unsupportedPresentation = codec.encodeText(request).replace("\"sortOrder\":2",
            "\"future\":true,\"sortOrder\":2");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(unsupportedPresentation));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 1), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new ServerId(SERVER_UUID), primary, 0, MUTATION_UUID, operation("resource.create"), Set.of(),
            resourceType("resource.document"), null, HASH, false, null, null, null, null, null, 0,
            ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(), new ProtocolBody.ResourceRequest(create)));
        assertThrows(IllegalArgumentException.class,
            () -> new ResourcePresentationIntent(" Draft", "Blueprints/Flows/flow-a.json", 2));
        assertThrows(IllegalArgumentException.class,
            () -> new ResourcePresentationIntent("Draft", "Blueprints/../flow-a.json", 2));
        assertThrows(IllegalArgumentException.class,
            () -> new ResourcePresentationIntent("Draft", "Blueprints/Flows/flow-a.json", -1));
    }

    @Test
    void typedEnvelopeCodecRoundTripsUnknownDataAndRejectsTampering() {
        ServerResourceLocator resource = resource("flow-a");
        CanonicalPayload<Map<String, String>> payload = PAYLOAD_CODEC.canonicalize(Map.of("name", "draft"));
        ResourceSaveRequest<Map<String, String>> save = new ResourceSaveRequest<>(resource, 4, payload, MUTATION_UUID);
        ProtocolEnvelope<Map<String, String>> envelope = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new ServerId(SERVER_UUID),
            resource,
            4,
            MUTATION_UUID,
            operation("resource.save"),
            Set.of(),
            resourceType("resource.document"),
            null,
            HASH,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of("futureEnvelope", Map.of("preserved", true)),
            new ProtocolBody.ResourceRequest(save, Map.of("futureBody", "value")));
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);

        byte[] encoded = codec.encodeBytes(envelope);
        ProtocolEnvelope<Map<String, String>> decoded = codec.decodeBytes(encoded);

        assertArrayEquals(encoded, codec.encodeBytes(decoded));
        assertEquals(Map.of("preserved", true), decoded.unknown().get("futureEnvelope"));
        assertEquals("value", decoded.body().unknown().get("futureBody"));
        String tampered = new String(encoded, java.nio.charset.StandardCharsets.UTF_8).replace("a".repeat(64), "b".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(tampered));
    }

    @Test
    void resourceQueryCodecRehydratesNestedLocatorsAndRejectsWrongScopeTypeAndShape() {
        TypeExpr.ResourceType resourceType = TypeExpr.resource(TypeReference.of("restudio.resync", "flow"));
        TypeExpr filterType = TypeExpr.list(TypeExpr.optional(resourceType));
        ServerResourceLocator locator = new ServerResourceLocator(new ServerId(SERVER_UUID), resourceType("flow"), "flow-a",
            Map.of("futureLocator", "preserved"));
        TypedValue filter = TypedValue.value(filterType, List.of(locator), Map.of("futureValue", List.of("preserved")));
        ProtocolEnvelope<Map<String, String>> envelope = queryEnvelope(filter);
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);

        byte[] encoded = codec.encodeBytes(envelope);
        ProtocolEnvelope<Map<String, String>> decoded = codec.decodeBytes(encoded);
        ResourceQueryRequest decodedQuery = (ResourceQueryRequest) ((ProtocolBody.ResourceRequest) decoded.body()).operation();

        assertArrayEquals(encoded, codec.encodeBytes(decoded));
        assertTrue(((List<?>) decodedQuery.filters().get("target").value()).getFirst() instanceof ServerResourceLocator);
        assertEquals(List.of("preserved"), decodedQuery.filters().get("target").unknown().get("futureValue"));

        Map<String, Object> wrongServer = encodedQuery(codec, envelope);
        queryFilterLocator(wrongServer).put("serverId", "99999999-9999-4999-8999-999999999999");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.fromJava(wrongServer)));

        Map<String, Object> wrongType = encodedQuery(codec, envelope);
        @SuppressWarnings("unchecked")
        Map<String, Object> locatorType = (Map<String, Object>) queryFilterLocator(wrongType).get("type");
        locatorType.put("localId", "function");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.fromJava(wrongType)));

        Map<String, Object> wrongShape = encodedQuery(codec, envelope);
        queryFilter(wrongShape).put("value", Map.of("not", "an-array"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.fromJava(wrongShape)));

        Map<String, Object> wrongState = encodedQuery(codec, envelope);
        queryFilter(wrongState).put("state", "absent");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.fromJava(wrongState)));
    }

    @Test
    void envelopeCodecAcceptsCanonicalIntegralFieldsAfterNumericNormalization() {
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);
        for (long expected : List.of(10L, 100L, 1000L)) {
            ProtocolEnvelope<Map<String, String>> decoded = codec.decodeText(codec.encodeText(loadEnvelope(expected, expected)));

            assertEquals(expected, decoded.revision());
            assertEquals(expected, decoded.sequence());
        }

        String encoded = codec.encodeText(loadEnvelope(1, 1));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"sequence\":1", "\"sequence\":-1")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"sequence\":1", "\"sequence\":1.5")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"sequence\":1", "\"sequence\":9223372036854775808")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"sequence\":1", "\"sequence\":1e2")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeText(encoded.replace("\"sequence\":1", "\"sequence\":1.0")));
    }

    @Test
    void envelopeCodecRejectsIntegerNarrowingOverflow() {
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = new LinkedHashMap<>((Map<String, Object>) codec.encode(loadEnvelope(1, 1)).toJava());
        @SuppressWarnings("unchecked")
        Map<String, Object> version = new LinkedHashMap<>((Map<String, Object>) envelope.get("contractVersion"));
        version.put("generation", 4_294_967_297L);
        envelope.put("contractVersion", version);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> codec.decode(JsonValue.fromJava(envelope)));
        assertTrue(failure.getMessage().contains("Protocol integer is out of range: generation"));
    }

    @Test
    void activationRequestAndDocumentStateRoundTripThroughVersionedContract() {
        ServerResourceLocator resource = resource("flow-a");
        ResourceActivateRequest request = new ResourceActivateRequest(resource, 4, ResourceActivationState.INACTIVE, MUTATION_UUID);
        ProtocolEnvelope<Map<String, String>> requestEnvelope = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 1),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new ServerId(SERVER_UUID),
            resource,
            4,
            MUTATION_UUID,
            operation("resource.activate"),
            Set.of(ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY),
            resourceType("resource.document"),
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
            new ProtocolBody.ResourceRequest(request)
        );
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);

        JsonValue.JsonObject encodedRequest = codec.encode(requestEnvelope);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBody = (Map<String, Object>) encodedRequest.value("body").toJava();
        assertEquals("inactive", requestBody.get("targetState"));
        ProtocolBody.ResourceRequest decodedRequest = (ProtocolBody.ResourceRequest) codec.decode(encodedRequest).body();
        assertEquals(ResourceActivationState.INACTIVE, ((ResourceActivateRequest) decodedRequest.operation()).targetState());

        ResourceDocument<Map<String, String>> document = ResourceDocument.live(resource, 5, MUTATION_UUID,
            PAYLOAD_CODEC.canonicalize(Map.of("name", "draft")), ResourceActivationState.INACTIVE, "server");
        ProtocolEnvelope<Map<String, String>> responseEnvelope = documentEnvelope(new CatalogVersion(1, 1),
            Set.of(ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY), document,
            ResourceOperationKind.ACTIVATE);
        JsonValue.JsonObject encodedResponse = codec.encode(responseEnvelope);
        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = (Map<String, Object>) encodedResponse.value("body").toJava();
        @SuppressWarnings("unchecked")
        Map<String, Object> encodedDocument = (Map<String, Object>) responseBody.get("document");
        assertEquals("inactive", encodedDocument.get("activationState"));
        assertEquals(ResourceActivationState.INACTIVE,
            codec.decode(encodedResponse).body() instanceof ProtocolBody.ResourceDocumentResponse response
                ? response.document().activationState() : null);
    }

    @Test
    void activationRequiresContract11AndCapability() {
        ServerResourceLocator resource = resource("flow-a");
        ResourceActivateRequest request = new ResourceActivateRequest(resource, 4, ResourceActivationState.ACTIVE, MUTATION_UUID);
        assertThrows(IllegalArgumentException.class, () -> new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new ServerId(SERVER_UUID),
            resource,
            4,
            MUTATION_UUID,
            operation("resource.activate"),
            Set.of(),
            resourceType("resource.document"),
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
            new ProtocolBody.ResourceRequest(request)
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 1),
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            new ServerId(SERVER_UUID),
            resource,
            4,
            MUTATION_UUID,
            operation("resource.activate"),
            Set.of(),
            resourceType("resource.document"),
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
            new ProtocolBody.ResourceRequest(request)
        ));
    }

    @Test
    void version10NonActivationDocumentsDefaultToActiveWithoutWireField() {
        ServerResourceLocator resource = resource("flow-a");
        ResourceDocument<Map<String, String>> document = ResourceDocument.live(resource, 5, MUTATION_UUID,
            PAYLOAD_CODEC.canonicalize(Map.of("name", "draft")), "server");
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);
        ProtocolEnvelope<Map<String, String>> envelope = documentEnvelope(new CatalogVersion(1, 0), Set.of(), document,
            ResourceOperationKind.LOAD);

        JsonValue.JsonObject encoded = codec.encode(envelope);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) encoded.value("body").toJava();
        @SuppressWarnings("unchecked")
        Map<String, Object> encodedDocument = (Map<String, Object>) body.get("document");
        assertFalse(encodedDocument.containsKey("activationState"));
        ProtocolBody.ResourceDocumentResponse decoded = (ProtocolBody.ResourceDocumentResponse) codec.decode(encoded).body();
        assertEquals(ResourceActivationState.ACTIVE, decoded.document().activationState());
    }

    @Test
    void tombstonesCannotCarryActivationState() {
        ServerResourceLocator resource = resource("flow-a");
        assertThrows(IllegalArgumentException.class, () -> new ResourceDocument<>(resource, 9, MUTATION_UUID, HASH, true,
            null, ResourceActivationState.ACTIVE, "server"));
    }

    @Test
    void nullCurrentConflictOmitsLifecycleFieldsAndPreservesRequestIdentity() {
        ServerResourceLocator resource = resource("missing-flow");
        ProtocolEnvelope<Map<String, String>> envelope = conflictEnvelope(resource, null);
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);

        JsonValue.JsonObject encoded = codec.encode(envelope);
        @SuppressWarnings("unchecked")
        Map<String, Object> encodedMap = new LinkedHashMap<>((Map<String, Object>) encoded.toJava());
        assertFalse(encodedMap.containsKey("revision"));
        assertFalse(encodedMap.containsKey("mutationId"));
        assertFalse(encodedMap.containsKey("payloadHash"));
        assertFalse(encodedMap.containsKey("deleted"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) encodedMap.get("body");
        assertFalse(body.containsKey("current"));

        ProtocolEnvelope<Map<String, String>> decoded = codec.decode(encoded);
        assertEquals(envelope, decoded);
        assertEquals(envelope.requestId(), decoded.requestId());
        assertEquals(envelope.correlationId(), decoded.correlationId());
        assertEquals(envelope.traceId(), decoded.traceId());
        assertEquals(resource, decoded.resource());
        assertEquals(resource, ((ProtocolBody.ConflictResponse) decoded.body()).requestedResource());

        Map<String, Object> nonCanonical = new LinkedHashMap<>(encodedMap);
        nonCanonical.put("revision", 0L);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.fromJava(nonCanonical)));
    }

    @Test
    void currentConflictRetainsAuthoritativeLifecycleFields() {
        ServerResourceLocator resource = resource("existing-flow");
        ResourceDocument<Map<String, String>> current = ResourceDocument.live(resource, 3, MUTATION_UUID,
            PAYLOAD_CODEC.canonicalize(Map.of("name", "current")), "server");
        ProtocolEnvelope<Map<String, String>> envelope = conflictEnvelope(resource, current);
        ProtocolEnvelopeCodec<Map<String, String>> codec = new ProtocolEnvelopeCodec<>(PAYLOAD_CODEC);

        JsonValue.JsonObject encoded = codec.encode(envelope);
        @SuppressWarnings("unchecked")
        Map<String, Object> encodedMap = (Map<String, Object>) encoded.toJava();
        assertEquals(3L, ((Number) encodedMap.get("revision")).longValue());
        assertEquals(MUTATION_UUID.toString(), encodedMap.get("mutationId"));
        assertEquals(HASH.canonicalText(), encodedMap.get("payloadHash"));
        assertEquals(false, encodedMap.get("deleted"));
        assertEquals(envelope, codec.decode(encoded));
    }

    private static ProtocolEnvelope<Map<String, String>> loadEnvelope(long revision, long sequence) {
        ServerResourceLocator resource = resource("flow-a");
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new ServerId(SERVER_UUID),
            resource,
            revision,
            null,
            operation("resource.load"),
            Set.of(),
            resourceType("resource.document"),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            sequence,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of(),
            new ProtocolBody.ResourceRequest(new ResourceLoadRequest(resource), Map.of()));
    }

    private static ProtocolEnvelope<Map<String, String>> queryEnvelope(TypedValue filter) {
        ResourceQueryRequest query = new ResourceQueryRequest(resourceType("flow"), Map.of("target", filter), null, 25, null);
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, new CatalogVersion(1, 0),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"), UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new ServerId(SERVER_UUID), null, 0, null, operation("resource.query"), Set.of(), resourceType("resource.page"), null,
            null, false, null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(),
            new ProtocolBody.ResourceRequest(query));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> encodedQuery(ProtocolEnvelopeCodec<Map<String, String>> codec,
                                                     ProtocolEnvelope<Map<String, String>> envelope) {
        return (Map<String, Object>) mutableCopy(codec.encode(envelope).toJava());
    }

    private static Object mutableCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put((String) key, mutableCopy(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(nested -> copy.add(mutableCopy(nested)));
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> queryFilter(Map<String, Object> envelope) {
        Map<String, Object> body = (Map<String, Object>) envelope.get("body");
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        return (Map<String, Object>) filters.get("target");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> queryFilterLocator(Map<String, Object> envelope) {
        List<Object> values = (List<Object>) queryFilter(envelope).get("value");
        return (Map<String, Object>) values.getFirst();
    }

    private static ProtocolEnvelope<Map<String, String>> conflictEnvelope(ServerResourceLocator resource,
                                                                          ResourceDocument<Map<String, String>> current) {
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.CONFLICT,
            new CatalogVersion(1, 0),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new ServerId(SERVER_UUID),
            resource,
            current == null ? 0 : current.revision(),
            current == null ? null : current.mutationId(),
            operation("resource.delete"),
            Set.of(),
            resourceType("resource.document"),
            null,
            current == null ? null : current.payloadHash(),
            current != null && current.deleted(),
            null,
            null,
            null,
            null,
            null,
            4,
            ProtocolEnvelope.Status.CONFLICT,
            List.of(),
            Map.of(),
            new ProtocolBody.ConflictResponse(resource, current));
    }

    private static ProtocolEnvelope<Map<String, String>> documentEnvelope(CatalogVersion version,
                                                                            Set<ContractRef<CapabilityId>> capabilities,
                                                                            ResourceDocument<Map<String, String>> document,
                                                                            ResourceOperationKind operation) {
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.ACK,
            version,
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            null,
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new ServerId(SERVER_UUID),
            document.resource(),
            document.revision(),
            document.mutationId(),
            operation("resource." + operation.name().toLowerCase(java.util.Locale.ROOT)),
            capabilities,
            resourceType("resource.document"),
            null,
            document.payloadHash(),
            document.deleted(),
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.OK,
            List.of(),
            Map.of(),
            new ProtocolBody.ResourceDocumentResponse(operation, document)
        );
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(new ServerId(SERVER_UUID),
            resourceType("flow"), id);
    }

    private static ContractRef<OperationId> operation(String id) {
        return ContractRef.of(new OwnerId("restudio.resync"), OperationId.of(id));
    }

    private static ContractRef<ResourceTypeId> resourceType(String id) {
        return ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of(id));
    }
}
