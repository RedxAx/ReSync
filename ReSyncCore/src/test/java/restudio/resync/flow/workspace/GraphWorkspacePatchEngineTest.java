package restudio.resync.flow.workspace;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.FunctionParameter;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphWorkspacePatchEngineTest {
    private static final UUID SERVER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final NodeInstanceId NODE = NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final RepeatableElementId ELEMENT = RepeatableElementId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final FunctionParameterId PARAMETER = FunctionParameterId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final PinId VALUE = PinId.of("value");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));

    @Test
    void appliesStableNestedSelectorsAsOneValidatedBatch() {
        GraphDocument graph = graph();
        String function = graph.functions().getFirst().function().canonicalText().replace("~", "~0").replace("/", "~1");
        List<WorkspacePatch<JsonValue>> patches = List.of(
            patch("set", "/nodes/@" + NODE.canonicalText() + "/position/x", JsonValue.of(32)),
            patch("remove", "/nodes/@" + NODE.canonicalText() + "/branches/@result/cases/@success", null),
            patch("set", "/nodes/@" + NODE.canonicalText() + "/branches/@result/selectedCaseId", JsonValue.of("failure")),
            patch("set", "/nodes/@" + NODE.canonicalText() + "/repeatables/@items/elements/@" + ELEMENT.canonicalText() + "/values", JsonValue.object(Map.of())),
            patch("set", "/functions/@" + function + "/inputs/@" + PARAMETER.canonicalText() + "/name", JsonValue.of("message"))
        );

        GraphDocument patched = new GraphWorkspacePatchEngine().apply(graph, patches);

        assertEquals(32D, patched.nodes().getFirst().x());
        assertEquals(CaseId.of("failure"), patched.nodes().getFirst().branches().getFirst().selectedCaseId());
        assertEquals(List.of(CaseId.of("failure")), patched.nodes().getFirst().branches().getFirst().cases().stream().map(BranchCase::caseId).toList());
        assertEquals("message", patched.functions().getFirst().inputs().getFirst().name());
    }

    @Test
    void treatsOpaqueArraysAtomicallyAndProtectsManagedState() {
        GraphWorkspacePatchEngine engine = new GraphWorkspacePatchEngine();
        GraphDocument graph = graph();

        assertThrows(IllegalArgumentException.class, () -> engine.apply(graph,
            List.of(patch("set", "/futureArray/0", JsonValue.of("changed")))));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(graph,
            List.of(patch("set", "/revision", JsonValue.of(9)))));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(graph,
            List.of(patch("set", "/nodes/0/position/x", JsonValue.of(9)))));

        GraphDocument replaced = engine.apply(graph,
            List.of(patch("set", "/futureArray", JsonValue.array(List.of(JsonValue.of("changed"))))));
        assertEquals(List.of("changed"), replaced.unknown().get("futureArray"));

        GraphDocument nestedRevision = engine.apply(graph, List.of(patch("set", "/futureState/revision", JsonValue.of(2))));
        assertEquals(2, ((Number) ((Map<?, ?>) nestedRevision.unknown().get("futureState")).get("revision")).intValue());
        assertThrows(IllegalArgumentException.class, () -> engine.apply(graph,
            List.of(patch("set", "/futureNode/branches/@fake/value", JsonValue.of(true)))));
    }

    @Test
    void exactArrayRemovalRejectsStaleMemberState() {
        GraphWorkspacePatchEngine engine = new GraphWorkspacePatchEngine();
        GraphDocument graph = graph();
        JsonValue.JsonArray nodes = (JsonValue.JsonArray) GraphDocumentCodec.INSTANCE.encode(graph).value("nodes");
        JsonValue.JsonObject node = (JsonValue.JsonObject) nodes.values().getFirst();
        Map<String, JsonValue> staleFields = new LinkedHashMap<>(node.fields());
        staleFields.put("futureChanged", JsonValue.of(true));

        assertThrows(IllegalArgumentException.class, () -> engine.apply(graph,
            List.of(patch("array_remove", "/nodes", JsonValue.object(staleFields)))));

        GraphDocument removed = engine.apply(graph, List.of(patch("array_remove", "/nodes", node)));
        assertEquals(List.of(), removed.nodes());

        Map<String, JsonValue> changedManaged = new LinkedHashMap<>(node.fields());
        changedManaged.put("definitionVersion", JsonValue.of(2));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(graph, List.of(
            patch("array_remove", "/nodes", node), patch("array_add", "/nodes", JsonValue.object(changedManaged)))));
    }

    @Test
    void rebaseTreatsAlreadyAcknowledgedAddsAndRemovalsAsApplied() {
        GraphWorkspacePatchEngine engine = new GraphWorkspacePatchEngine();
        GraphDocument graph = graph();
        JsonValue.JsonObject original = GraphDocumentCodec.INSTANCE.encode(graph);
        JsonValue.JsonObject node = (JsonValue.JsonObject) ((JsonValue.JsonArray) original.value("nodes")).values().getFirst();
        WorkspacePatch<JsonValue> removal = patch("array_remove", "/nodes", node);
        JsonValue.JsonObject saved = engine.apply(original, List.of(removal));

        JsonValue.JsonObject rebased = engine.rebase(saved, List.of(removal));

        assertEquals(saved, rebased);
    }

    @Test
    void canonicalizesTheLastOptionalKnownArrayRemoval() {
        GraphWorkspacePatchEngine engine = new GraphWorkspacePatchEngine();
        JsonValue.JsonObject encoded = GraphDocumentCodec.INSTANCE.encode(graph());
        JsonValue.JsonObject function = (JsonValue.JsonObject) ((JsonValue.JsonArray) encoded.value("functions")).values().getFirst();

        JsonValue.JsonObject removed = engine.apply(encoded, List.of(patch("array_remove", "/functions", function)));

        assertEquals(null, removed.value("functions"));
        assertEquals(removed, engine.rebase(removed, List.of(patch("array_remove", "/functions", function))));
        String selector = graph().functions().getFirst().function().canonicalText().replace("~", "~0").replace("/", "~1");
        WorkspacePatch<JsonValue> selectorRemoval = patch("remove", "/functions/@" + selector, null);
        JsonValue.JsonObject selectorRemoved = engine.apply(encoded, List.of(selectorRemoval));
        assertEquals(selectorRemoved, engine.rebase(selectorRemoved, List.of(selectorRemoval)));
    }

    @Test
    void preventsSameBatchReorderingWhenRepeatableElementsFinishOrdered() {
        GraphWorkspacePatchEngine engine = new GraphWorkspacePatchEngine();
        JsonValue.JsonObject encoded = GraphDocumentCodec.INSTANCE.encode(graph());
        JsonValue.JsonObject node = (JsonValue.JsonObject) ((JsonValue.JsonArray) encoded.value("nodes")).values().getFirst();
        JsonValue.JsonObject repeatable = (JsonValue.JsonObject) ((JsonValue.JsonArray) node.value("repeatables")).values().getFirst();
        JsonValue.JsonObject element = (JsonValue.JsonObject) ((JsonValue.JsonArray) repeatable.value("elements")).values().getFirst();
        String root = "/nodes/@" + NODE.canonicalText() + "/repeatables/@items/elements";
        Map<String, JsonValue> secondFields = new LinkedHashMap<>(element.fields());
        secondFields.put("elementId", JsonValue.of("55555555-5555-4555-8555-555555555555"));
        JsonValue.JsonObject second = JsonValue.object(secondFields);

        assertThrows(IllegalArgumentException.class, () -> engine.apply(encoded, List.of(
            patch("array_remove", root, element), patch("array_add", root, element))));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(encoded, List.of(
            patch("remove", root + "/@" + ELEMENT.canonicalText(), null), patch("array_add", root, element))));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(encoded, List.of(
            patch("set", "/nodes/@" + NODE.canonicalText() + "/repeatables/@items", repeatable),
            patch("array_remove", root, element))));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(encoded, List.of(
            patch("set", "/nodes/@" + NODE.canonicalText() + "/repeatables/@items/ordered", JsonValue.of(true)),
            patch("array_remove", root, element))));

        assertThrows(IllegalArgumentException.class, () -> engine.apply(encoded, List.of(
            patch("set", "/nodes/@" + NODE.canonicalText() + "/repeatables/@items/ordered", JsonValue.of(false)),
            patch("array_remove", root, element), patch("array_add", root, element))));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(encoded, List.of(
            patch("set", "/nodes/@" + NODE.canonicalText() + "/repeatables/@items/ordered", JsonValue.of(false)),
            patch("set", root + "/@" + ELEMENT.canonicalText() + "/future", JsonValue.of(true)))));

        JsonValue.JsonObject opaqueBaseline = engine.apply(encoded, List.of(
            patch("set", root + "/@" + ELEMENT.canonicalText() + "/future", JsonValue.of(true))));
        JsonValue.JsonObject opaqueRemovedWithSuffix = engine.apply(opaqueBaseline, List.of(
            patch("remove", root + "/@" + ELEMENT.canonicalText() + "/future", null), patch("array_add", root, second)));
        assertEquals(2, GraphDocumentCodec.INSTANCE.decode(opaqueRemovedWithSuffix).nodes().getFirst().repeatables().getFirst().elements().size());

        JsonValue.JsonObject twoElements = engine.apply(encoded, List.of(patch("array_add", root, second)));
        JsonValue.JsonObject twoElementNode = (JsonValue.JsonObject) ((JsonValue.JsonArray) twoElements.value("nodes")).values().getFirst();
        JsonValue.JsonObject twoElementRepeatable = (JsonValue.JsonObject) ((JsonValue.JsonArray) twoElementNode.value("repeatables")).values().getFirst();
        Map<String, JsonValue> reorderedFields = new LinkedHashMap<>(twoElementRepeatable.fields());
        reorderedFields.put("elements", JsonValue.array(List.of(second, element)));
        JsonValue.JsonObject reordered = JsonValue.object(reorderedFields);
        assertThrows(IllegalArgumentException.class, () -> engine.apply(twoElements, List.of(
            patch("remove", "/nodes/@" + NODE.canonicalText() + "/repeatables/@items", null),
            patch("array_add", "/nodes/@" + NODE.canonicalText() + "/repeatables", reordered))));

        Map<String, JsonValue> reorderedNodeFields = new LinkedHashMap<>(twoElementNode.fields());
        reorderedNodeFields.put("repeatables", JsonValue.array(List.of(reordered)));
        JsonValue.JsonObject reorderedNode = JsonValue.object(reorderedNodeFields);
        assertThrows(IllegalArgumentException.class, () -> engine.apply(twoElements, List.of(
            patch("remove", "/nodes/@" + NODE.canonicalText(), null), patch("array_add", "/nodes", reorderedNode))));
    }

    @Test
    void arrayReorderPreservesCurrentMembersAndSupportsIdempotentRebase() {
        GraphWorkspacePatchEngine engine = new GraphWorkspacePatchEngine();
        JsonValue.JsonObject encoded = GraphDocumentCodec.INSTANCE.encode(graph());
        JsonValue.JsonObject node = (JsonValue.JsonObject) ((JsonValue.JsonArray) encoded.value("nodes")).values().getFirst();
        JsonValue.JsonObject repeatable = (JsonValue.JsonObject) ((JsonValue.JsonArray) node.value("repeatables")).values().getFirst();
        JsonValue.JsonObject first = (JsonValue.JsonObject) ((JsonValue.JsonArray) repeatable.value("elements")).values().getFirst();
        RepeatableElementId secondId = RepeatableElementId.of(UUID.fromString("55555555-5555-4555-8555-555555555555"));
        Map<String, JsonValue> secondFields = new LinkedHashMap<>(first.fields());
        secondFields.put("elementId", JsonValue.of(secondId.canonicalText()));
        secondFields.put("future", JsonValue.of("second"));
        JsonValue.JsonObject second = JsonValue.object(secondFields);
        String root = "/nodes/@" + NODE.canonicalText() + "/repeatables/@items/elements";
        JsonValue changedValues = elements(GraphDocumentCodec.INSTANCE.encode(graph("changed"))).values().getFirst();
        changedValues = ((JsonValue.JsonObject) changedValues).value("values");
        JsonValue.JsonObject current = engine.apply(encoded, List.of(
            patch("array_add", root, second),
            patch("set", root + "/@" + ELEMENT.canonicalText() + "/values", changedValues),
            patch("set", root + "/@" + ELEMENT.canonicalText() + "/future", JsonValue.of("first"))));
        WorkspacePatch<JsonValue> reorder = reorder(root, List.of(ELEMENT, secondId), List.of(secondId, ELEMENT));

        JsonValue.JsonObject reordered = engine.apply(current, List.of(reorder));
        JsonValue.JsonArray reorderedElements = elements(reordered);

        assertTrue(engine.valid(reorder));
        assertEquals(List.of(secondId.canonicalText(), ELEMENT.canonicalText()),
            reorderedElements.values().stream().map(value -> ((JsonValue.JsonString) ((JsonValue.JsonObject) value).value("elementId")).value()).toList());
        assertEquals("second", ((JsonValue.JsonString) ((JsonValue.JsonObject) reorderedElements.values().getFirst()).value("future")).value());
        JsonValue.JsonObject preservedFirst = (JsonValue.JsonObject) reorderedElements.values().get(1);
        assertEquals("first", ((JsonValue.JsonString) preservedFirst.value("future")).value());
        assertEquals(changedValues, preservedFirst.value("values"));
        assertEquals(reordered, engine.rebase(reordered, List.of(reorder)));
    }

    @Test
    void arrayReorderRejectsConflictsMalformedTargetsAndMutationBypasses() {
        GraphWorkspacePatchEngine engine = new GraphWorkspacePatchEngine();
        JsonValue.JsonObject encoded = GraphDocumentCodec.INSTANCE.encode(graph());
        JsonValue.JsonObject first = (JsonValue.JsonObject) elements(encoded).values().getFirst();
        RepeatableElementId secondId = RepeatableElementId.of(UUID.fromString("55555555-5555-4555-8555-555555555555"));
        RepeatableElementId thirdId = RepeatableElementId.of(UUID.fromString("66666666-6666-4666-8666-666666666666"));
        Map<String, JsonValue> secondFields = new LinkedHashMap<>(first.fields());
        secondFields.put("elementId", JsonValue.of(secondId.canonicalText()));
        JsonValue.JsonObject second = JsonValue.object(secondFields);
        Map<String, JsonValue> thirdFields = new LinkedHashMap<>(first.fields());
        thirdFields.put("elementId", JsonValue.of(thirdId.canonicalText()));
        JsonValue.JsonObject third = JsonValue.object(thirdFields);
        String root = "/nodes/@" + NODE.canonicalText() + "/repeatables/@items/elements";
        JsonValue.JsonObject two = engine.apply(encoded, List.of(patch("array_add", root, second)));
        WorkspacePatch<JsonValue> reorder = reorder(root, List.of(ELEMENT, secondId), List.of(secondId, ELEMENT));
        JsonValue.JsonObject desired = engine.apply(two, List.of(reorder));

        assertThrows(IllegalArgumentException.class, () -> engine.apply(desired, List.of(reorder)));
        JsonValue.JsonObject three = engine.apply(two, List.of(patch("array_add", root, third)));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(three, List.of(reorder)));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(two, List.of(
            patch("array_reorder", root, reorderValue(List.of(ELEMENT, ELEMENT), List.of(ELEMENT, secondId))))));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(two, List.of(
            patch("array_reorder", root.replace("@items", "@missing"), reorder.value()))));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(two, List.of(
            patch("array_reorder", "/nodes/@" + NODE.canonicalText() + "/branches", reorder.value()))));
        WorkspacePatch<JsonValue> restoreAfterBypass = reorder(root, List.of(secondId, ELEMENT), List.of(ELEMENT, secondId));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(two, List.of(
            patch("array_remove", root, first), patch("array_add", root, first), restoreAfterBypass)));
        JsonValue.JsonObject node = (JsonValue.JsonObject) ((JsonValue.JsonArray) two.value("nodes")).values().getFirst();
        assertThrows(IllegalArgumentException.class, () -> engine.apply(two, List.of(
            patch("array_remove", "/nodes", node), patch("array_add", "/nodes", node), reorder)));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(two, List.of(
            patch("set", root.replace("/elements", "/ordered"), JsonValue.of(false)), reorder)));

        JsonValue.JsonObject unordered = engine.apply(two,
            List.of(patch("set", root.replace("/elements", "/ordered"), JsonValue.of(false))));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(unordered, List.of(reorder)));
    }

    private static WorkspacePatch<JsonValue> patch(String operation, String path, JsonValue value) {
        return new WorkspacePatch<>(operation, path, value);
    }

    private static WorkspacePatch<JsonValue> reorder(String path, List<RepeatableElementId> expected,
                                                      List<RepeatableElementId> order) {
        return patch("array_reorder", path, reorderValue(expected, order));
    }

    private static JsonValue.JsonObject reorderValue(List<RepeatableElementId> expected,
                                                      List<RepeatableElementId> order) {
        return JsonValue.object(Map.of(
            "expected", JsonValue.array(expected.stream().map(id -> JsonValue.of(id.canonicalText())).toList()),
            "order", JsonValue.array(order.stream().map(id -> JsonValue.of(id.canonicalText())).toList())));
    }

    private static JsonValue.JsonArray elements(JsonValue.JsonObject graph) {
        JsonValue.JsonObject node = (JsonValue.JsonObject) ((JsonValue.JsonArray) graph.value("nodes")).values().getFirst();
        JsonValue.JsonObject repeatable = (JsonValue.JsonObject) ((JsonValue.JsonArray) node.value("repeatables")).values().getFirst();
        return (JsonValue.JsonArray) repeatable.value("elements");
    }

    private static GraphDocument graph() {
        return graph("initial");
    }

    private static GraphDocument graph(String elementValue) {
        BranchBinding branch = new BranchBinding(BranchId.of("result"), CaseId.of("success"), List.of(
            new BranchCase(CaseId.of("failure"), Map.of()),
            new BranchCase(CaseId.of("success"), Map.of())));
        RepeatableBinding repeatable = new RepeatableBinding(RepeatableGroupId.of("items"), true,
            List.of(new RepeatableElement(ELEMENT, Map.of(VALUE, new PinValue(VALUE, TypedValue.value(TEXT, elementValue))))));
        GraphNode node = new GraphNode(NODE, ContractRef.of(OwnerId.of("builtin"), NodeId.of("fixture")), 1, null, Map.of(), Map.of(),
            List.of(branch), List.of(repeatable), InspectorState.empty(), 1, 2, OpaqueData.empty());
        ServerResourceLocator functionResource = resource("function", "compute");
        FunctionParameter parameter = new FunctionParameter(PARAMETER, "input", TEXT, "Provides the input value to this function.", null);
        FunctionBinding function = new FunctionBinding(functionResource, 1, List.of(parameter), List.of());
        return new GraphDocument(new CatalogVersion(1, 0), resource("flow", "workspace"), 3, BINDING, Set.of(), List.of(node), List.of(), List.of(),
            List.of(function), OpaqueData.of(Map.of(
                "futureArray", List.of("one", "two"),
                "futureState", Map.of("revision", 1),
                "futureNode", Map.of("instanceId", "opaque", "branches", List.of(Map.of("branchId", "fake", "value", false))))));
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }
}
