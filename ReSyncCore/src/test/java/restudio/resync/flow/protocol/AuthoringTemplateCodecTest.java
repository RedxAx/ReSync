package restudio.resync.flow.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.resource.ResourceManagementDescriptor;
import restudio.resync.flow.type.CodecDescriptor;
import restudio.resync.flow.type.TypeDescriptor;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypeValueCodec;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthoringTemplateCodecTest {
    private static final ServerId SERVER = ServerId.deterministic("authoring-template-test");
    private static final CatalogBinding BINDING = new CatalogBinding(4, hash('a'), hash('b'));
    private static final CatalogCacheKey KEY = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
    private static final ContractRef<ResourceTypeId> FLOW_TYPE = ContractRef.of(new OwnerId("restudio"), new ResourceTypeId("flow"));
    private static final ContractRef<ResourceTypeId> COMMAND_TYPE = ContractRef.of(new OwnerId("restudio"), new ResourceTypeId("command"));
    private static final ContractRef<ResourceTypeId> FUNCTION_TYPE = ContractRef.of(new OwnerId("restudio"), new ResourceTypeId("function"));
    private static final ContractRef<ResourceTypeId> WORLD_TYPE = ContractRef.of(new OwnerId("restudio"), new ResourceTypeId("world"));
    private static final ContractRef<CapabilityId> MANAGEMENT = ContractRef.of(new OwnerId("restudio"), CapabilityId.of("world-authoring"));
    private static final TypeReference PAYLOAD_TYPE = TypeReference.of("restudio", "world-document");
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr BOOLEAN = TypeExpr.named(TypeReference.of("builtin", "boolean"));

    @Test
    void requestCarriesBindingAwarePublicationIdentity() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, FLOW_TYPE, "template");
        AuthoringTemplateRequest request = new AuthoringTemplateRequest(resource, KEY, Map.of("future", true));

        AuthoringTemplateRequest decoded = AuthoringTemplateCodec.INSTANCE.decodeRequestText(
            AuthoringTemplateCodec.INSTANCE.encodeRequestText(request));

        assertEquals(request, decoded);
        assertEquals(KEY, decoded.publicationIdentity());
        assertFalse(decoded.hasAcknowledgedAuthoringPublicationChecksum());
    }

    @Test
    void requestCarriesOptionalAuthoringPublicationChecksum() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, FLOW_TYPE, "template");
        ContentHash checksum = hash('c');
        AuthoringTemplateRequest request = new AuthoringTemplateRequest(resource, KEY, checksum);

        AuthoringTemplateRequest decoded = AuthoringTemplateCodec.INSTANCE.decodeRequestText(
            AuthoringTemplateCodec.INSTANCE.encodeRequestText(request));

        assertEquals(checksum, decoded.acknowledgedAuthoringPublicationChecksum());
        assertTrue(decoded.hasAcknowledgedAuthoringPublicationChecksum());
    }

    @Test
    void responseCarriesTypedRevisionZeroDocumentAndCapabilities() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, FLOW_TYPE, "template");
        GraphDocument graph = graph(resource);
        AuthoringTemplateResponse response = AuthoringTemplateResponse.of(resource, KEY, BINDING,
            AuthoringTemplatePayload.flow(graph), Set.of());

        AuthoringTemplateResponse decoded = AuthoringTemplateCodec.INSTANCE.decodeResponseText(
            AuthoringTemplateCodec.INSTANCE.encodeResponseText(response));

        assertEquals(response.publicationKey(), decoded.publicationKey());
        assertEquals(response.catalogBinding(), decoded.catalogBinding());
        assertEquals(response.templateChecksum(), decoded.templateChecksum());
        assertFalse(decoded.hasAuthoringPublicationChecksum());
        assertEquals(response.requiredCapabilities(), decoded.requiredCapabilities());
        assertEquals(response.payload().graphDocument().canonicalJson(), decoded.payload().graphDocument().canonicalJson());
    }

    @Test
    void responseCarriesOptionalAuthoringPublicationChecksum() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, FLOW_TYPE, "template");
        ContentHash checksum = hash('c');
        AuthoringTemplateResponse response = AuthoringTemplateResponse.of(resource, KEY, BINDING,
            AuthoringTemplatePayload.flow(graph(resource)), checksum, Set.of());

        AuthoringTemplateResponse decoded = AuthoringTemplateCodec.INSTANCE.decodeResponseText(
            AuthoringTemplateCodec.INSTANCE.encodeResponseText(response));

        assertEquals(checksum, decoded.authoringPublicationChecksum());
        assertTrue(decoded.hasAuthoringPublicationChecksum());
    }

    @Test
    void functionPayloadUsesFunctionSourceDocument() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, FUNCTION_TYPE, "template");
        GraphDocument graph = graph(resource);
        FunctionSourceDocument source = new FunctionSourceDocument(
            new FunctionSignature(new FunctionLocator(resource), FunctionRevision.initial(), List.of(), List.of()), graph);
        AuthoringTemplatePayload payload = AuthoringTemplatePayload.function(source);

        assertEquals(AuthoringTemplatePayload.Kind.FUNCTION, payload.kind());
        assertEquals(source.checksum(), payload.checksum());
        assertEquals(source, payload.functionSourceDocument());
    }

    @Test
    void rejectsNonCanonicalWireTextAndNonZeroTemplates() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, FLOW_TYPE, "template");
        AuthoringTemplateRequest request = new AuthoringTemplateRequest(resource, KEY);
        String encoded = AuthoringTemplateCodec.INSTANCE.encodeRequestText(request);

        assertThrows(IllegalArgumentException.class, () -> AuthoringTemplateCodec.INSTANCE.decodeRequestText(" " + encoded));
        assertThrows(IllegalArgumentException.class, () -> AuthoringTemplatePayload.flow(
            new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(), List.of(), List.of(), List.of(), List.of(), OpaqueData.empty())));
    }

    @Test
    void legacyRequestAndAllThreeLegacyResponsesKeepTheirExactWireShape() {
        ServerResourceLocator flow = new ServerResourceLocator(SERVER, FLOW_TYPE, "template");
        AuthoringTemplateRequest request = new AuthoringTemplateRequest(flow, KEY, hash('c'));
        Map<String, JsonValue> requestGolden = new LinkedHashMap<>();
        requestGolden.put("kind", JsonValue.of("authoring-template-request"));
        requestGolden.put("version", JsonValue.fromJava(1));
        requestGolden.put("resource", IdentityCodec.encode(flow));
        requestGolden.put("acknowledgedCatalogKey", JsonValue.of(KEY.canonicalText()));
        requestGolden.put("acknowledgedAuthoringPublicationChecksum", JsonValue.of(hash('c').canonicalText()));
        assertArrayEquals(JsonValue.object(requestGolden).canonicalBytes(), AuthoringTemplateCodec.INSTANCE.encodeRequestBytes(request));

        AuthoringTemplateRequest legacyWithoutChecksum = new AuthoringTemplateRequest(flow, KEY);
        requestGolden.remove("acknowledgedAuthoringPublicationChecksum");
        assertArrayEquals(JsonValue.object(requestGolden).canonicalBytes(),
            AuthoringTemplateCodec.INSTANCE.encodeRequestBytes(legacyWithoutChecksum));

        AuthoringTemplateResponse flowResponse = AuthoringTemplateResponse.of(flow, KEY, BINDING,
            AuthoringTemplatePayload.flow(graph(flow)), Set.of());
        assertLegacyResponseGolden(flowResponse, GraphDocumentCodec.INSTANCE.encode(graph(flow)));

        ServerResourceLocator command = new ServerResourceLocator(SERVER, COMMAND_TYPE, "template");
        AuthoringTemplateResponse commandResponse = AuthoringTemplateResponse.of(command, KEY, BINDING,
            AuthoringTemplatePayload.command(graph(command)), Set.of());
        assertLegacyResponseGolden(commandResponse, GraphDocumentCodec.INSTANCE.encode(graph(command)));

        ServerResourceLocator function = new ServerResourceLocator(SERVER, FUNCTION_TYPE, "template");
        FunctionSourceDocument source = new FunctionSourceDocument(
            new FunctionSignature(new FunctionLocator(function), FunctionRevision.initial(), List.of(), List.of()), graph(function));
        AuthoringTemplateResponse functionResponse = AuthoringTemplateResponse.of(function, KEY, BINDING,
            AuthoringTemplatePayload.function(source), Set.of());
        assertLegacyResponseGolden(functionResponse, FunctionSourceDocumentCodec.INSTANCE.encode(source));
    }

    @Test
    void resourceRequestRequiresTheExplicitVersionedManagementQuartet() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, WORLD_TYPE, "spawn");
        AuthoringTemplateRequest request = new AuthoringTemplateRequest(resource, KEY, hash('c'),
            AuthoringTemplatePayload.Kind.RESOURCE, 1, hash('d'), MANAGEMENT);

        AuthoringTemplateRequest decoded = AuthoringTemplateCodec.INSTANCE.decodeRequestBytes(
            AuthoringTemplateCodec.INSTANCE.encodeRequestBytes(request));

        assertEquals(AuthoringTemplatePayload.Kind.RESOURCE, decoded.expectedTemplateKind());
        assertEquals(1, decoded.expectedTemplateVersion());
        assertEquals(hash('d'), decoded.acknowledgedManagementDescriptorChecksum());
        assertEquals(MANAGEMENT, decoded.acknowledgedManagementCapability());
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateRequest(resource, KEY, null,
            AuthoringTemplatePayload.Kind.RESOURCE, 1, hash('d'), MANAGEMENT, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateRequest(resource, KEY, null,
            AuthoringTemplatePayload.Kind.RESOURCE, 1, hash('d'), null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateRequest(resource, KEY, null,
            AuthoringTemplatePayload.Kind.FLOW, 1, hash('d'), MANAGEMENT, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateRequest(resource, KEY, null,
            AuthoringTemplatePayload.Kind.RESOURCE, 2, hash('d'), MANAGEMENT, Map.of()));
    }

    @Test
    void resourceRequestTransportsTypedInputsAndResolvesOnlyDescriptorDefaults() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, WORLD_TYPE, "spawn");
        InspectorFieldId name = InspectorFieldId.of("name");
        InspectorFieldId region = InspectorFieldId.of("region");
        InspectorFieldId note = InspectorFieldId.of("note");
        InspectorFieldId label = InspectorFieldId.of("label");
        InspectorFieldId color = InspectorFieldId.of("color");
        TypeExpr optionalText = TypeExpr.optional(TEXT);
        List<ResourceManagementDescriptor.Input> declarations = List.of(
            input(name, TEXT, true, null),
            input(region, TEXT, true, TypedValue.value(TEXT, "Earth")),
            input(note, optionalText, false, null),
            input(label, optionalText, false, TypedValue.value(optionalText, "Default Label")),
            input(color, TEXT, false, TypedValue.value(TEXT, "Blue")));
        ResourceManagementDescriptor descriptor = managementDescriptor(WORLD_TYPE, MANAGEMENT, declarations, true);
        Map<InspectorFieldId, TypedValue> supplied = Map.of(
            name, TypedValue.value(TEXT, "Spawn", Map.of("inputFuture", true)),
            label, TypedValue.nullValue(optionalText));
        AuthoringTemplateRequest request = new AuthoringTemplateRequest(resource, KEY, hash('c'),
            AuthoringTemplatePayload.Kind.RESOURCE, 1, descriptor.checksum(), MANAGEMENT, supplied,
            Map.of("requestFuture", true));

        AuthoringTemplateRequest decoded = AuthoringTemplateCodec.INSTANCE.decodeRequestBytes(
            AuthoringTemplateCodec.INSTANCE.encodeRequestBytes(request));
        Map<InspectorFieldId, TypedValue> resolved = decoded.resolveAuthoringInputs(descriptor);

        assertEquals(supplied, decoded.authoringInputs());
        assertEquals("Spawn", resolved.get(name).value());
        assertEquals(true, resolved.get(name).unknown().get("inputFuture"));
        assertEquals("Earth", resolved.get(region).value());
        assertFalse(resolved.containsKey(note));
        assertEquals(TypedValue.State.NULL, resolved.get(label).state());
        assertEquals("Blue", resolved.get(color).value());
        assertEquals(true, decoded.unknown().get("requestFuture"));
        assertThrows(UnsupportedOperationException.class,
            () -> decoded.authoringInputs().put(note, TypedValue.value(optionalText, "Later")));

        JsonValue.JsonObject encoded = AuthoringTemplateCodec.INSTANCE.encodeRequest(request);
        assertTrue(encoded.contains("authoringInputs"));
        Map<String, JsonValue> missingInputs = new LinkedHashMap<>(encoded.fields());
        missingInputs.remove("authoringInputs");
        assertThrows(IllegalArgumentException.class,
            () -> AuthoringTemplateCodec.INSTANCE.decodeRequest(JsonValue.object(missingInputs)));

        List<JsonValue> duplicatedInputs = new ArrayList<>(((JsonValue.JsonArray) encoded.value("authoringInputs")).values());
        duplicatedInputs.add(duplicatedInputs.getFirst());
        Map<String, JsonValue> duplicateWire = new LinkedHashMap<>(encoded.fields());
        duplicateWire.put("authoringInputs", JsonValue.array(duplicatedInputs));
        assertThrows(IllegalArgumentException.class,
            () -> AuthoringTemplateCodec.INSTANCE.decodeRequest(JsonValue.object(duplicateWire)));
    }

    @Test
    void resourceInputAdmissionRejectsMissingUndeclaredAbsentWrongTypedNullAndGateMismatches() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, WORLD_TYPE, "spawn");
        InspectorFieldId name = InspectorFieldId.of("name");
        ResourceManagementDescriptor descriptor = managementDescriptor(WORLD_TYPE, MANAGEMENT,
            List.of(input(name, TEXT, true, null)), true);

        AuthoringTemplateRequest missing = resourceRequest(resource, descriptor, MANAGEMENT, Map.of());
        assertThrows(IllegalArgumentException.class, () -> missing.resolveAuthoringInputs(descriptor));

        AuthoringTemplateRequest undeclared = resourceRequest(resource, descriptor, MANAGEMENT,
            Map.of(InspectorFieldId.of("future"), TypedValue.value(TEXT, "value")));
        assertThrows(IllegalArgumentException.class, () -> undeclared.resolveAuthoringInputs(descriptor));

        AuthoringTemplateRequest wrongType = resourceRequest(resource, descriptor, MANAGEMENT,
            Map.of(name, TypedValue.value(BOOLEAN, true)));
        assertThrows(IllegalArgumentException.class, () -> wrongType.resolveAuthoringInputs(descriptor));

        AuthoringTemplateRequest wrongNull = resourceRequest(resource, descriptor, MANAGEMENT,
            Map.of(name, TypedValue.nullValue(TEXT)));
        assertThrows(IllegalArgumentException.class, () -> wrongNull.resolveAuthoringInputs(descriptor));

        assertThrows(IllegalArgumentException.class, () -> resourceRequest(resource, descriptor, MANAGEMENT,
            Map.of(name, TypedValue.absent(TEXT))));

        AuthoringTemplateRequest wrongChecksum = new AuthoringTemplateRequest(resource, KEY, hash('c'),
            AuthoringTemplatePayload.Kind.RESOURCE, 1, hash('d'), MANAGEMENT,
            Map.of(name, TypedValue.value(TEXT, "Spawn")), Map.of());
        assertThrows(IllegalArgumentException.class, () -> wrongChecksum.resolveAuthoringInputs(descriptor));

        ContractRef<CapabilityId> otherCapability = ContractRef.of(new OwnerId("other"), CapabilityId.of("world-authoring"));
        ResourceManagementDescriptor wrongCapability = managementDescriptor(WORLD_TYPE, otherCapability,
            descriptor.serverAuthoring().inputs(), true);
        AuthoringTemplateRequest capabilityRequest = resourceRequest(resource, wrongCapability, MANAGEMENT,
            Map.of(name, TypedValue.value(TEXT, "Spawn")));
        assertThrows(IllegalArgumentException.class, () -> capabilityRequest.resolveAuthoringInputs(wrongCapability));

        ContractRef<ResourceTypeId> otherType = ContractRef.of(new OwnerId("other"), new ResourceTypeId("world"));
        ResourceManagementDescriptor wrongTypeDescriptor = managementDescriptor(otherType, MANAGEMENT,
            descriptor.serverAuthoring().inputs(), true);
        AuthoringTemplateRequest typeRequest = resourceRequest(resource, wrongTypeDescriptor, MANAGEMENT,
            Map.of(name, TypedValue.value(TEXT, "Spawn")));
        assertThrows(IllegalArgumentException.class, () -> typeRequest.resolveAuthoringInputs(wrongTypeDescriptor));

        ResourceManagementDescriptor createUnavailable = managementDescriptor(WORLD_TYPE, MANAGEMENT,
            descriptor.serverAuthoring().inputs(), false);
        AuthoringTemplateRequest unavailableRequest = resourceRequest(resource, createUnavailable, MANAGEMENT,
            Map.of(name, TypedValue.value(TEXT, "Spawn")));
        assertThrows(IllegalArgumentException.class, () -> unavailableRequest.resolveAuthoringInputs(createUnavailable));
    }

    @Test
    void resourceInputMapsAndWireArraysEnforceTheSharedBoundAndKnownFieldOwnership() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, WORLD_TYPE, "spawn");
        ResourceManagementDescriptor descriptor = managementDescriptor(WORLD_TYPE, MANAGEMENT, List.of(), true);
        Map<InspectorFieldId, TypedValue> oversized = new LinkedHashMap<>();
        for (int index = 0; index <= ResourceManagementDescriptor.MAX_INPUTS; index++) {
            oversized.put(InspectorFieldId.of("input-" + index), TypedValue.value(TEXT, "value"));
        }
        assertThrows(IllegalArgumentException.class,
            () -> resourceRequest(resource, descriptor, MANAGEMENT, oversized));

        AuthoringTemplateRequest empty = resourceRequest(resource, descriptor, MANAGEMENT, Map.of());
        JsonValue.JsonObject encoded = AuthoringTemplateCodec.INSTANCE.encodeRequest(empty);
        JsonValue.JsonObject input = JsonValue.object(Map.of(
            "id", JsonValue.of("name"),
            "value", TypeValueCodec.INSTANCE.encode(TypedValue.value(TEXT, "value"))));
        List<JsonValue> oversizedWire = new ArrayList<>();
        for (int index = 0; index <= ResourceManagementDescriptor.MAX_INPUTS; index++) {
            oversizedWire.add(input);
        }
        Map<String, JsonValue> malformed = new LinkedHashMap<>(encoded.fields());
        malformed.put("authoringInputs", JsonValue.array(oversizedWire));
        assertThrows(IllegalArgumentException.class,
            () -> AuthoringTemplateCodec.INSTANCE.decodeRequest(JsonValue.object(malformed)));

        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateRequest(resource, KEY, hash('c'),
            AuthoringTemplatePayload.Kind.RESOURCE, 1, descriptor.checksum(), MANAGEMENT,
            Map.of("authoringInputs", List.of())));
    }

    @Test
    void resourcePayloadRoundTripsFullCanonicalMaterialAndRequiresExactDescriptorAdmission() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, WORLD_TYPE, "spawn");
        AuthoringTemplatePayload.Resource payload = new AuthoringTemplatePayload.Resource(resource, BINDING, PAYLOAD_TYPE,
            TypedValue.value(TEXT, "Spawn", Map.of("valueFuture", true)), Set.of(MANAGEMENT), Map.of("payloadFuture", true));
        AuthoringTemplateResponse response = new AuthoringTemplateResponse(resource, KEY, BINDING, payload,
            payload.checksum(), hash('c'), Set.of(MANAGEMENT), Set.of(MANAGEMENT), hash('d'), MANAGEMENT,
            Map.of("responseFuture", true));

        AuthoringTemplateResponse decoded = AuthoringTemplateCodec.INSTANCE.decodeResponseBytes(
            AuthoringTemplateCodec.INSTANCE.encodeResponseBytes(response));
        AuthoringTemplatePayload.Resource decodedPayload = (AuthoringTemplatePayload.Resource) decoded.payload();

        assertEquals(payload.checksum(), decodedPayload.checksum());
        assertEquals(true, decodedPayload.unknown().get("payloadFuture"));
        assertEquals(true, decodedPayload.payload().unknown().get("valueFuture"));
        assertEquals(true, decoded.unknown().get("responseFuture"));
        assertEquals(hash('d'), decoded.managementDescriptorChecksum());

        CodecDescriptor codec = new CodecDescriptor(TypeReference.of("restudio", "json"), 1, true, true);
        TypeDescriptor matching = new TypeDescriptor(PAYLOAD_TYPE, "World Document", TEXT, Map.of(), codec, codec,
            MANAGEMENT, List.of(), true, true);
        assertEquals(matching, decodedPayload.requirePayloadDescriptor(matching));
        TypeDescriptor wrongExpression = new TypeDescriptor(PAYLOAD_TYPE, "World Document",
            TypeExpr.named(TypeReference.of("builtin", "boolean")), Map.of(), codec, codec, MANAGEMENT,
            List.of(), true, true);
        assertThrows(IllegalArgumentException.class, () -> decodedPayload.requirePayloadDescriptor(wrongExpression));
        TypeDescriptor wrongId = new TypeDescriptor(TypeReference.of("restudio", "other-document"), "Other Document",
            TEXT, Map.of(), codec, codec, MANAGEMENT, List.of(), true, true);
        assertThrows(IllegalArgumentException.class, () -> decodedPayload.requirePayloadDescriptor(wrongId));
    }

    @Test
    void resourceResponseRequiresAndExactlyEchoesEveryRequestAcknowledgement() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, WORLD_TYPE, "spawn");
        ResourceManagementDescriptor descriptor = managementDescriptor(WORLD_TYPE, MANAGEMENT, List.of(), true);
        AuthoringTemplateRequest request = resourceRequest(resource, descriptor, MANAGEMENT, Map.of());
        AuthoringTemplateResponse response = resourceResponse(resource, KEY, BINDING, hash('c'), descriptor.checksum(),
            MANAGEMENT);

        assertEquals(response, response.requireAcknowledgements(request));
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateResponse(resource, KEY, BINDING,
            response.payload(), response.templateChecksum(), null, Set.of(MANAGEMENT), Set.of(MANAGEMENT),
            descriptor.checksum(), MANAGEMENT));
        Map<String, JsonValue> missingPublication = new LinkedHashMap<>(
            AuthoringTemplateCodec.INSTANCE.encodeResponse(response).fields());
        missingPublication.remove("authoringPublicationChecksum");
        assertThrows(IllegalArgumentException.class,
            () -> AuthoringTemplateCodec.INSTANCE.decodeResponse(JsonValue.object(missingPublication)));
        assertThrows(NullPointerException.class, () -> AuthoringTemplateResponse.ofResource(resource, KEY, BINDING,
            (AuthoringTemplatePayload.Resource) response.payload(), null, Set.of(MANAGEMENT), descriptor.checksum(),
            MANAGEMENT));

        ServerResourceLocator otherResource = new ServerResourceLocator(SERVER, WORLD_TYPE, "other");
        assertThrows(IllegalArgumentException.class, () -> resourceResponse(otherResource, KEY, BINDING, hash('c'),
            descriptor.checksum(), MANAGEMENT).requireAcknowledgements(request));

        CatalogBinding otherBinding = new CatalogBinding(5, hash('e'), hash('f'));
        CatalogCacheKey otherKey = new CatalogCacheKey(SERVER, otherBinding, CatalogProjectionVersion.current());
        assertThrows(IllegalArgumentException.class, () -> resourceResponse(resource, otherKey, otherBinding, hash('c'),
            descriptor.checksum(), MANAGEMENT).requireAcknowledgements(request));
        assertThrows(IllegalArgumentException.class, () -> resourceResponse(resource, KEY, BINDING, hash('e'),
            descriptor.checksum(), MANAGEMENT).requireAcknowledgements(request));
        assertThrows(IllegalArgumentException.class, () -> resourceResponse(resource, KEY, BINDING, hash('c'),
            hash('e'), MANAGEMENT).requireAcknowledgements(request));

        ContractRef<CapabilityId> otherCapability = ContractRef.of(new OwnerId("other"), CapabilityId.of("world-authoring"));
        assertThrows(IllegalArgumentException.class, () -> resourceResponse(resource, KEY, BINDING, hash('c'),
            descriptor.checksum(), otherCapability).requireAcknowledgements(request));
        assertThrows(IllegalArgumentException.class, () -> response.requireAcknowledgements(
            new AuthoringTemplateRequest(resource, KEY, hash('c'))));
    }

    @Test
    void resourceAuthoringRejectsAbsentPayloadAndIdentityBindingChecksumOrCapabilityMismatch() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, WORLD_TYPE, "spawn");
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplatePayload.Resource(resource, BINDING,
            PAYLOAD_TYPE, TypedValue.absent(TEXT), Set.of(MANAGEMENT)));

        AuthoringTemplatePayload.Resource payload = new AuthoringTemplatePayload.Resource(resource, BINDING,
            PAYLOAD_TYPE, TypedValue.value(TEXT, "Spawn"), Set.of(MANAGEMENT));
        AuthoringTemplatePayload.Resource changed = new AuthoringTemplatePayload.Resource(resource, BINDING,
            PAYLOAD_TYPE, TypedValue.value(TEXT, "Other"), Set.of(MANAGEMENT));
        assertFalse(payload.checksum().equals(changed.checksum()));

        ServerResourceLocator otherResource = new ServerResourceLocator(SERVER, WORLD_TYPE, "other");
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateResponse(otherResource, KEY, BINDING,
            payload, payload.checksum(), hash('c'), Set.of(MANAGEMENT), Set.of(MANAGEMENT), hash('d'), MANAGEMENT));
        CatalogBinding otherBinding = new CatalogBinding(5, hash('e'), hash('f'));
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateResponse(resource, KEY, otherBinding,
            payload, payload.checksum(), hash('c'), Set.of(MANAGEMENT), Set.of(MANAGEMENT), hash('d'), MANAGEMENT));
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateResponse(resource, KEY, BINDING,
            payload, changed.checksum(), hash('c'), Set.of(MANAGEMENT), Set.of(MANAGEMENT), hash('d'), MANAGEMENT));
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateResponse(resource, KEY, BINDING,
            payload, payload.checksum(), hash('c'), Set.of(), Set.of(MANAGEMENT), hash('d'), MANAGEMENT));
        assertThrows(IllegalArgumentException.class, () -> new AuthoringTemplateResponse(resource, KEY, BINDING,
            payload, payload.checksum(), hash('c'), Set.of(MANAGEMENT), Set.of(MANAGEMENT), null, null));
    }

    private static GraphDocument graph(ServerResourceLocator resource) {
        return new GraphDocument(new CatalogVersion(1, 0), resource, 0, BINDING, Set.of(), List.of(), List.of(),
            List.of(), List.of(), OpaqueData.empty());
    }

    private static ResourceManagementDescriptor.Input input(InspectorFieldId id, TypeExpr type, boolean required,
                                                            TypedValue defaultValue) {
        return new ResourceManagementDescriptor.Input(id, type, required, MANAGEMENT, defaultValue);
    }

    private static ResourceManagementDescriptor managementDescriptor(ContractRef<ResourceTypeId> resourceType,
                                                                     ContractRef<CapabilityId> capability,
                                                                     List<ResourceManagementDescriptor.Input> inputs,
                                                                     boolean createAvailable) {
        List<ResourceManagementDescriptor.Operation> operations = new ArrayList<>();
        for (ResourceOperationKind operation : ResourceOperationKind.values()) {
            operations.add(operation == ResourceOperationKind.CREATE && !createAvailable
                ? new ResourceManagementDescriptor.Operation(operation,
                ResourceManagementDescriptor.OperationState.UNAVAILABLE, "Creation is unavailable.")
                : ResourceManagementDescriptor.Operation.available(operation));
        }
        return new ResourceManagementDescriptor(resourceType, PAYLOAD_TYPE,
            ResourceManagementDescriptor.Availability.available(), operations,
            new ResourceManagementDescriptor.ServerAuthoring(capability, inputs));
    }

    private static AuthoringTemplateRequest resourceRequest(ServerResourceLocator resource,
                                                             ResourceManagementDescriptor descriptor,
                                                             ContractRef<CapabilityId> capability,
                                                             Map<InspectorFieldId, TypedValue> inputs) {
        return new AuthoringTemplateRequest(resource, KEY, hash('c'), AuthoringTemplatePayload.Kind.RESOURCE, 1,
            descriptor.checksum(), capability, inputs, Map.of());
    }

    private static AuthoringTemplateResponse resourceResponse(ServerResourceLocator resource, CatalogCacheKey key,
                                                               CatalogBinding binding, ContentHash publicationChecksum,
                                                               ContentHash managementChecksum,
                                                               ContractRef<CapabilityId> capability) {
        AuthoringTemplatePayload.Resource payload = new AuthoringTemplatePayload.Resource(resource, binding, PAYLOAD_TYPE,
            TypedValue.value(TEXT, "Spawn"), Set.of(capability));
        return AuthoringTemplateResponse.ofResource(resource, key, binding, payload, publicationChecksum,
            Set.of(capability), managementChecksum, capability);
    }

    private static ContentHash hash(char value) {
        return new ContentHash(String.valueOf(value).repeat(64));
    }

    private static void assertLegacyResponseGolden(AuthoringTemplateResponse response, JsonValue document) {
        Map<String, JsonValue> payload = new LinkedHashMap<>();
        payload.put("kind", JsonValue.of(response.payload().kind().wireName()));
        payload.put("document", document);
        Map<String, JsonValue> golden = new LinkedHashMap<>();
        golden.put("kind", JsonValue.of("authoring-template-response"));
        golden.put("version", JsonValue.fromJava(1));
        golden.put("resource", IdentityCodec.encode(response.resource()));
        golden.put("publicationKey", JsonValue.of(response.publicationKey().canonicalText()));
        golden.put("catalogBinding", JsonValue.fromJava(Map.of("generation", BINDING.generation(),
            "catalogChecksum", BINDING.catalogChecksum().canonicalText(),
            "bindingManifestHash", BINDING.bindingManifestHash().canonicalText())));
        golden.put("payload", JsonValue.object(payload));
        golden.put("templateChecksum", JsonValue.of(response.templateChecksum().canonicalText()));
        golden.put("editCapabilities", JsonValue.array(response.editCapabilities().stream()
            .sorted(Comparator.comparing(ContractRef::canonicalText)).map(IdentityCodec::encode).toList()));
        golden.put("requiredCapabilities", JsonValue.array(response.requiredCapabilities().stream()
            .sorted(Comparator.comparing(ContractRef::canonicalText)).map(IdentityCodec::encode).toList()));
        assertArrayEquals(JsonValue.object(golden).canonicalBytes(), AuthoringTemplateCodec.INSTANCE.encodeResponseBytes(response));
    }
}
