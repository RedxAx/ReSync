package restudio.resync.flow.graph;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDocumentCodecTest {
    private static final UUID SERVER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final NodeInstanceId SOURCE_ID = NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final NodeInstanceId TARGET_ID = NodeInstanceId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final ConnectionId CONNECTION_ID = ConnectionId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final FunctionParameterId INPUT_ID = FunctionParameterId.of(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    private static final FunctionParameterId OUTPUT_ID = FunctionParameterId.of(UUID.fromString("66666666-6666-4666-8666-666666666666"));
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final CatalogBinding BINDING = new CatalogBinding(3, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));

    @Test
    void canonicalBytesRoundTripAllGraphIdentityAndOpaqueState() {
        GraphDocument original = graph();

        byte[] first = GraphDocumentCodec.INSTANCE.encodeBytes(original);
        GraphDocument decoded = GraphDocumentCodec.INSTANCE.decodeBytes(first);
        byte[] second = GraphDocumentCodec.INSTANCE.encodeBytes(decoded);

        assertArrayEquals(first, second);
        assertEquals(original.checksum(), decoded.checksum());
        assertEquals(SOURCE_ID, decoded.nodes().getFirst().instanceId());
        assertEquals(CONNECTION_ID, decoded.connections().getFirst().connectionId());
        assertEquals(INPUT_ID, decoded.functions().getFirst().inputs().getFirst().parameterId());
        assertEquals(OUTPUT_ID, decoded.functions().getFirst().outputs().getFirst().parameterId());
        assertEquals(true, decoded.unknown().get("futureRoot"));
        assertEquals(true, decoded.nodes().getFirst().unknown().get("futureNode"));
        assertEquals(true, decoded.connections().getFirst().unknown().get("futureConnection"));
        assertEquals(true, decoded.functions().getFirst().unknown().get("futureFunction"));
    }

    @Test
    void passthroughRelationsRoundTripWithStableConnectionIdentity() {
        GraphDocument base = graph();
        GraphPassthrough passthrough = new GraphPassthrough(TARGET_ID, PinId.of("input"), List.of(CONNECTION_ID),
            OpaqueData.of(Map.of("futurePassthrough", true)));
        GraphDocument original = new GraphDocument(base.schemaVersion(), base.resource(), base.revision(),
            base.catalogBinding(), base.requiredCapabilities(), base.nodes(), base.connections(), List.of(passthrough),
            base.variables(), base.functions(), base.unknown());

        GraphDocument decoded = GraphDocumentCodec.INSTANCE.decodeBytes(GraphDocumentCodec.INSTANCE.encodeBytes(original));

        assertEquals(List.of(CONNECTION_ID), decoded.passthroughs().getFirst().connectionIds());
        assertEquals(TARGET_ID, decoded.passthroughs().getFirst().nodeId());
        assertEquals(PinId.of("input"), decoded.passthroughs().getFirst().inputPin());
        assertEquals(true, decoded.passthroughs().getFirst().unknown().get("futurePassthrough"));
        assertArrayEquals(GraphDocumentCodec.INSTANCE.encodeBytes(original), GraphDocumentCodec.INSTANCE.encodeBytes(decoded));
    }

    @Test
    void unavailableNodeRoundTripsWithoutCatalogAccess() {
        GraphDocument graph = new GraphDocument(
            new CatalogVersion(1, 0), resource("flow", "unavailable"), 8, BINDING, Set.of(),
            List.of(new GraphNode(NodeInstanceId.deterministic("unavailable"),
                ContractRef.of(new OwnerId("future.extension"), NodeId.of("unknown-node")), 9, null,
                Map.of(), Map.of(), List.of(), List.of(), InspectorState.empty(), 0, 0,
                OpaqueData.of(Map.of("unavailable", true)))),
            List.of(), List.of(), List.of(), OpaqueData.empty());

        GraphDocument decoded = GraphDocumentCodec.INSTANCE.decodeBytes(GraphDocumentCodec.INSTANCE.encodeBytes(graph));

        assertEquals(graph.canonicalJson(), decoded.canonicalJson());
        assertEquals(true, decoded.nodes().getFirst().unknown().get("unavailable"));
    }

    @Test
    void duplicateNodeAndMalformedEndpointFailClosed() {
        GraphDocument graph = graph();
        JsonValue.JsonObject encoded = GraphDocumentCodec.INSTANCE.encode(graph);
        Map<String, JsonValue> duplicateRoot = new LinkedHashMap<>(encoded.fields());
        JsonValue node = ((JsonValue.JsonArray) encoded.value("nodes")).values().getFirst();
        duplicateRoot.put("nodes", JsonValue.array(List.of(node, node)));
        assertThrows(IllegalArgumentException.class, () -> GraphDocumentCodec.INSTANCE.decode(JsonValue.object(duplicateRoot)));

        JsonValue.JsonObject connectionValue = ((JsonValue.JsonArray) encoded.value("connections")).values().stream()
            .map(JsonValue.JsonObject.class::cast).findFirst().orElseThrow();
        Map<String, JsonValue> malformedEndpoint = new LinkedHashMap<>(((JsonValue.JsonObject) connectionValue.value("source")).fields());
        malformedEndpoint.remove("pinId");
        Map<String, JsonValue> malformedConnection = new LinkedHashMap<>(connectionValue.fields());
        malformedConnection.put("source", JsonValue.object(malformedEndpoint));
        Map<String, JsonValue> malformedRoot = new LinkedHashMap<>(encoded.fields());
        malformedRoot.put("connections", JsonValue.array(List.of(JsonValue.object(malformedConnection))));
        assertThrows(IllegalArgumentException.class, () -> GraphDocumentCodec.INSTANCE.decode(JsonValue.object(malformedRoot)));
    }

    @Test
    void nonCanonicalOptionalShapeIsRejectedWithoutDefaultInjection() {
        GraphDocument graph = graph();
        JsonValue.JsonObject encoded = GraphDocumentCodec.INSTANCE.encode(graph);
        Map<String, JsonValue> root = new LinkedHashMap<>(encoded.fields());
        root.put("variables", JsonValue.array(List.of()));

        assertThrows(IllegalArgumentException.class, () -> GraphDocumentCodec.INSTANCE.decode(JsonValue.object(root)));
    }

    @Test
    void opaqueNullMaterialRoundTripsAsAnExplicitValue() {
        TypeExpr.OpaqueType opaqueType = TypeExpr.opaque(TypeReference.of("future", "blob"));
        PinId pin = PinId.of("opaque");
        GraphNode node = new GraphNode(SOURCE_ID,
            ContractRef.of(new OwnerId("future.extension"), NodeId.of("opaque-node")), 1, null,
            Map.of(pin, new PinValue(pin, TypedValue.opaque(opaqueType, null))), Map.of(), List.of(), List.of(),
            InspectorState.empty(), 0, 0, OpaqueData.empty());
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource("flow", "opaque"), 0, BINDING,
            Set.of(), List.of(node), List.of(), List.of(), List.of(), OpaqueData.empty());

        GraphDocument decoded = GraphDocumentCodec.INSTANCE.decodeBytes(GraphDocumentCodec.INSTANCE.encodeBytes(graph));
        TypedValue value = decoded.nodes().getFirst().values().get(pin).value();

        assertEquals(TypedValue.State.OPAQUE, value.state());
        assertNull(value.value());
        assertEquals(graph.canonicalJson(), decoded.canonicalJson());
        assertTrue(graph.canonicalJson().contains("\"value\":null"));
    }

    @Test
    void nestedIdentityOpaqueFieldsSurviveCanonicalRoundTrip() {
        ContractRef<ResourceTypeId> flowType = new ContractRef<>(new OwnerId("resync"), new ResourceTypeId("flow"),
            Map.of("futureType", true));
        ResourceKey rootKey = new ResourceKey(flowType, "identity", Map.of("futureKey", true));
        ServerResourceLocator rootResource = new ServerResourceLocator(SERVER_ID, rootKey, Map.of("futureLocator", true));
        ContractRef<NodeId> definition = new ContractRef<>(new OwnerId("future.extension"), NodeId.of("unknown-node"),
            Map.of("futureDefinition", true));
        ContractRef<CapabilityId> capability = new ContractRef<>(new OwnerId("future.extension"), CapabilityId.of("execute"),
            Map.of("futureCapability", true));
        ServerResourceLocator functionResource = new ServerResourceLocator(SERVER_ID,
            new ResourceKey(new ContractRef<>(new OwnerId("future.extension"), new ResourceTypeId("function"),
                Map.of("futureFunctionType", true)), "compute", Map.of("futureFunctionKey", true)),
            Map.of("futureFunctionLocator", true));
        FunctionBinding function = functionBinding(functionResource);
        GraphNode node = new GraphNode(SOURCE_ID, definition, 1, Map.of());
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), rootResource, 2, BINDING,
            Set.of(capability), List.of(node), List.of(), List.of(), List.of(function), OpaqueData.empty());

        byte[] encoded = GraphDocumentCodec.INSTANCE.encodeBytes(graph);
        GraphDocument decoded = GraphDocumentCodec.INSTANCE.decodeBytes(encoded);

        assertArrayEquals(encoded, GraphDocumentCodec.INSTANCE.encodeBytes(decoded));
        assertEquals(true, decoded.resource().unknown().get("futureLocator"));
        assertEquals(true, decoded.resource().key().unknown().get("futureKey"));
        assertEquals(true, decoded.resource().type().unknown().get("futureType"));
        assertEquals(true, decoded.nodes().getFirst().definition().unknown().get("futureDefinition"));
        assertEquals(true, decoded.functions().getFirst().function().unknown().get("futureFunctionLocator"));
        assertEquals(true, decoded.functions().getFirst().function().key().unknown().get("futureFunctionKey"));
        assertEquals(true, decoded.functions().getFirst().function().type().unknown().get("futureFunctionType"));
        assertEquals(true, decoded.requiredCapabilities().iterator().next().unknown().get("futureCapability"));
    }

    @Test
    void sharedTypeCodecDelegationPreservesTheGraphCanonicalGolden() {
        TypeExpr.UnionType nested = new TypeExpr.UnionType(List.of(
            new TypeExpr.UnionVariant("count", new TypeExpr.ListType(NUMBER, Map.of("listFuture", true))),
            new TypeExpr.UnionVariant("text", TEXT, "Text", "A text value for the graph.", Map.of("variantFuture", true))),
            Map.of("unionFuture", true));
        TypedValue value = TypedValue.unionValue(nested, "count", List.of(new BigDecimal("12.5")),
            Map.of("valueFuture", true));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource("flow", "typed-golden"), 0,
            BINDING, Set.of(), List.of(), List.of(), List.of(new GraphVariable(
            UUID.fromString("99999999-9999-4999-8999-999999999999"), "value", nested, value)), List.of(), OpaqueData.empty());
        byte[] preDelegationGolden = JsonValue.fromJava(graph.canonicalValue()).canonicalBytes();

        byte[] encoded = GraphDocumentCodec.INSTANCE.encodeBytes(graph);
        GraphDocument decoded = GraphDocumentCodec.INSTANCE.decodeBytes(encoded);

        assertArrayEquals(preDelegationGolden, encoded);
        assertArrayEquals(encoded, GraphDocumentCodec.INSTANCE.encodeBytes(decoded));
        TypeExpr.UnionType decodedType = (TypeExpr.UnionType) decoded.variables().getFirst().type();
        assertEquals(true, decodedType.unknown().get("unionFuture"));
        assertEquals(true, decoded.variables().getFirst().value().unknown().get("valueFuture"));
    }

    @Test
    void legacyPinInspectorFieldsAreRejectedByTheCoreCodec() {
        JsonValue.JsonObject encoded = GraphDocumentCodec.INSTANCE.encode(graph());
        JsonValue.JsonObject node = ((JsonValue.JsonArray) encoded.value("nodes")).values().stream()
            .map(JsonValue.JsonObject.class::cast).findFirst().orElseThrow();
        JsonValue typed = JsonValue.fromJava(TypedValue.value(TEXT, "legacy").canonicalValue());
        JsonValue.JsonObject legacyValue = JsonValue.object(Map.of("value", typed));
        Map<String, JsonValue> inspector = new LinkedHashMap<>();
        inspector.put("legacy", legacyValue);
        Map<String, JsonValue> malformedNode = new LinkedHashMap<>(node.fields());
        malformedNode.put("inspector", JsonValue.object(inspector));
        Map<String, JsonValue> malformedRoot = new LinkedHashMap<>(encoded.fields());
        malformedRoot.put("nodes", JsonValue.array(List.of(JsonValue.object(malformedNode))));

        assertThrows(IllegalArgumentException.class, () -> GraphDocumentCodec.INSTANCE.decode(JsonValue.object(malformedRoot)));

        JsonValue.JsonObject inspectorState = (JsonValue.JsonObject) node.value("inspectorState");
        Map<String, JsonValue> stateFields = new LinkedHashMap<>();
        stateFields.put("legacy", legacyValue);
        Map<String, JsonValue> malformedState = new LinkedHashMap<>(inspectorState.fields());
        malformedState.put("fields", JsonValue.object(stateFields));
        Map<String, JsonValue> stateNode = new LinkedHashMap<>(node.fields());
        stateNode.put("inspectorState", JsonValue.object(malformedState));
        Map<String, JsonValue> stateRoot = new LinkedHashMap<>(encoded.fields());
        stateRoot.put("nodes", JsonValue.array(List.of(JsonValue.object(stateNode))));

        assertThrows(IllegalArgumentException.class, () -> GraphDocumentCodec.INSTANCE.decode(JsonValue.object(stateRoot)));
    }

    @Test
    void encodeRejectsLegacyPinInspectorCompatibilityState() {
        PinId legacyPin = PinId.of("legacy");
        PinValue legacyValue = new PinValue(legacyPin, TypedValue.value(TEXT, "legacy"));
        GraphNode legacyNode = new GraphNode(SOURCE_ID,
            ContractRef.of(new OwnerId("builtin"), NodeId.of("legacy-node")), 1, null, Map.of(),
            Map.of(legacyPin, legacyValue), List.of(), List.of(), InspectorState.empty(), 0, 0, OpaqueData.empty());
        assertThrows(IllegalArgumentException.class, () -> GraphDocumentCodec.INSTANCE.encode(legacyGraph(List.of(legacyNode))));

        InspectorState legacyState = new InspectorState(InspectorState.State.CLEAN, Map.of(legacyPin, legacyValue),
            InspectorState.Fallback.EDITABLE, null, null, OpaqueData.empty());
        GraphNode stateNode = new GraphNode(SOURCE_ID,
            ContractRef.of(new OwnerId("builtin"), NodeId.of("legacy-state-node")), 1, null, Map.of(), Map.of(),
            List.of(), List.of(), legacyState, 0, 0, OpaqueData.empty());
        assertThrows(IllegalArgumentException.class, () -> GraphDocumentCodec.INSTANCE.encode(legacyGraph(List.of(stateNode))));

        BranchCase branchCase = new BranchCase(CaseId.of("case"), Map.of(), legacyState, OpaqueData.empty());
        BranchBinding branch = new BranchBinding(BranchId.of("branch"), CaseId.of("case"), List.of(branchCase));
        GraphNode branchNode = new GraphNode(SOURCE_ID,
            ContractRef.of(new OwnerId("builtin"), NodeId.of("legacy-branch-node")), 1, null, Map.of(), Map.of(),
            List.of(branch), List.of(), InspectorState.empty(), 0, 0, OpaqueData.empty());
        assertThrows(IllegalArgumentException.class, () -> GraphDocumentCodec.INSTANCE.encode(legacyGraph(List.of(branchNode))));
    }

    private static GraphDocument graph() {
        PinId input = PinId.of("input");
        PinId output = PinId.of("output");
        TypedValue number = TypedValue.value(NUMBER, new BigDecimal("12.50"), Map.of("futureValue", true));
        GraphNode source = new GraphNode(SOURCE_ID,
            ContractRef.of(new OwnerId("builtin"), NodeId.of("source")), 2, null,
            Map.of(output, new PinValue(output, number)),
            Map.of(InspectorFieldId.of("label"), TypedValue.value(TEXT, "source")),
            List.of(new BranchBinding(BranchId.of("result"), CaseId.of("success"),
                List.of(new BranchCase(CaseId.of("success"), Map.of(), InspectorState.empty(), OpaqueData.empty())), OpaqueData.empty())),
            List.of(new RepeatableBinding(RepeatableGroupId.of("items"), true,
                List.of(new RepeatableElement(RepeatableElementId.of(UUID.fromString("77777777-7777-4777-8777-777777777777")), Map.of())), OpaqueData.empty())),
            new InspectorState(InspectorState.State.CLEAN, Map.of(InspectorFieldId.of("enabled"), TypedValue.value(TEXT, "yes")), InspectorState.Fallback.EDITABLE,
                null, null, OpaqueData.empty()), 1.5, -2.25, OpaqueData.of(Map.of("futureNode", true)));
        GraphNode target = new GraphNode(TARGET_ID,
            ContractRef.of(new OwnerId("builtin"), NodeId.of("target")), 1, Map.of());
        GraphConnection connection = new GraphConnection(CONNECTION_ID,
            new GraphEndpoint(SOURCE_ID, output, null, BranchId.of("result"), OpaqueData.of(Map.of("futureEndpoint", true))),
            new GraphEndpoint(TARGET_ID, input), OpaqueData.of(Map.of("futureConnection", true)));
        GraphVariable variable = new GraphVariable(UUID.fromString("88888888-8888-4888-8888-888888888888"), "count", NUMBER, number,
            OpaqueData.of(Map.of("futureVariable", true)));
        FunctionParameter inputParameter = new FunctionParameter(INPUT_ID, "input", NUMBER,
            "Provides the input value to this function.", number, OpaqueData.empty());
        FunctionParameter outputParameter = new FunctionParameter(OUTPUT_ID, "output", NUMBER,
            "Provides the output value from this function.", null, OpaqueData.empty());
        FunctionBinding function = new FunctionBinding(resource("function", "compute"), 4,
            List.of(inputParameter), List.of(outputParameter), OpaqueData.of(Map.of("futureFunction", true)));
        return new GraphDocument(new CatalogVersion(1, 0), resource("flow", "example"), 4, BINDING,
            Set.of(ContractRef.of(new OwnerId("builtin"), CapabilityId.of("flow-execute"))), List.of(source, target), List.of(connection),
            List.of(variable), List.of(function), OpaqueData.of(Map.of("futureRoot", true)));
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER_ID, ContractRef.of(new OwnerId("resync"), new ResourceTypeId(type)), id);
    }

    private static FunctionBinding functionBinding(ServerResourceLocator resource) {
        FunctionParameter input = new FunctionParameter(INPUT_ID, "input", NUMBER,
            "Provides the input value to this function.", null, OpaqueData.empty());
        FunctionParameter output = new FunctionParameter(OUTPUT_ID, "output", NUMBER,
            "Provides the output value from this function.", null, OpaqueData.empty());
        return new FunctionBinding(resource, 1, List.of(input), List.of(output));
    }

    private static GraphDocument legacyGraph(List<GraphNode> nodes) {
        return new GraphDocument(new CatalogVersion(1, 0), resource("flow", "legacy"), 0, BINDING,
            Set.of(), nodes, List.of(), List.of(), List.of(), OpaqueData.empty());
    }
}
