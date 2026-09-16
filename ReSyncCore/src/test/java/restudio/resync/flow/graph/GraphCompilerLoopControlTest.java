package restudio.resync.flow.graph;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphCompilerLoopControlTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ContractRef<CapabilityId> LOOP_CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("flow.control"));
    private static final ContractRef<CapabilityId> EXECUTE_CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("flow.test"));
    private static final ContractRef<CapabilityId> EDITOR = ContractRef.of(OWNER, CapabilityId.of("generic-editor"));
    private static final ContractRef<CapabilityId> CATEGORY = ContractRef.of(OWNER, CapabilityId.of("flow"));
    private static final TypeExpr EXECUTION = TypeExpr.named(TypeReference.of("builtin", "execution"));
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr BOOLEAN = TypeExpr.named(TypeReference.of("builtin", "boolean"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final NodeInstanceId LOOP = nodeId("10000000-0000-4000-8000-000000000001");
    private static final NodeInstanceId FIRST = nodeId("20000000-0000-4000-8000-000000000002");
    private static final NodeInstanceId SECOND = nodeId("30000000-0000-4000-8000-000000000003");
    private static final NodeInstanceId THIRD = nodeId("40000000-0000-4000-8000-000000000004");
    private static final NodeInstanceId FOURTH = nodeId("50000000-0000-4000-8000-000000000005");
    private static final NodeInstanceId DONE = nodeId("90000000-0000-4000-8000-000000000009");

    @Test
    void compilesExactCountControlPinsAndOrderedIterationDataScope() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), transform(), action(), done());
        GraphDocument graph = graph(catalog, List.of(
            node(LOOP, "count-loop"),
            node(FIRST, "transform"),
            node(SECOND, "action"),
            node(THIRD, "transform"),
            node(FOURTH, "action"),
            node(DONE, "done")), List.of(
            connection("body-first", LOOP, "loop", SECOND, "flow"),
            connection("index-first", LOOP, "index", FIRST, "input"),
            connection("first-third", FIRST, "result", THIRD, "input"),
            connection("third-fourth", THIRD, "result", FOURTH, "value"),
            connection("second-fourth", SECOND, "next", FOURTH, "flow"),
            connection("done", LOOP, "done", DONE, "flow"),
            connection("completed", LOOP, "completed", DONE, "completed")));

        CompiledExecutionStep.LoopControl control = loopControl(new GraphCompiler().compile(graph, catalog), LOOP);

        assertEquals(CompiledExecutionStep.LoopControl.Kind.COUNT, control.kind());
        assertEquals(PinId.of("flow"), control.entryInput());
        assertEquals(PinId.of("count"), control.sourceInput());
        assertEquals(PinId.of("loop"), control.bodyOutput());
        assertEquals(PinId.of("done"), control.doneOutput());
        assertEquals(PinId.of("index"), control.indexOutput());
        assertNull(control.elementOutput());
        assertEquals(PinId.of("completed"), control.completedOutput());
        assertEquals(NUMBER, control.sourceType());
        assertNull(control.elementType());
        assertEquals(List.of(FIRST, SECOND, THIRD, FOURTH), control.bodySteps());
    }

    @Test
    void compilesExactForEachControlAndElementType() {
        CatalogSnapshot catalog = catalog(forEachLoop(TEXT), textAction(), done());
        GraphDocument graph = graph(catalog, List.of(node(LOOP, "for-each-loop"), node(FIRST, "text-action"), node(DONE, "done")), List.of(
            connection("body", LOOP, "loop", FIRST, "flow"),
            connection("element", LOOP, "element", FIRST, "text"),
            connection("done", LOOP, "done", DONE, "flow")));

        CompiledExecutionStep.LoopControl control = loopControl(new GraphCompiler().compile(graph, catalog), LOOP);

        assertEquals(CompiledExecutionStep.LoopControl.Kind.FOR_EACH, control.kind());
        assertEquals(PinId.of("list"), control.sourceInput());
        assertEquals(PinId.of("element"), control.elementOutput());
        assertEquals(TypeExpr.list(TEXT), control.sourceType());
        assertEquals(TEXT, control.elementType());
        assertEquals(List.of(FIRST), control.bodySteps());
    }

    @Test
    void nestedLoopsAssignOnlyImmediateOwnedSteps() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action(), done());
        NodeInstanceId inner = SECOND;
        GraphDocument graph = graph(catalog, List.of(
            node(LOOP, "count-loop"),
            node(inner, "count-loop"),
            node(THIRD, "action"),
            node(FOURTH, "action"),
            node(DONE, "done")), List.of(
            connection("outer-body", LOOP, "loop", inner, "flow"),
            connection("inner-body", inner, "loop", THIRD, "flow"),
            connection("inner-done", inner, "done", FOURTH, "flow"),
            connection("outer-done", LOOP, "done", DONE, "flow")));

        CompiledExecutionPlan plan = new GraphCompiler().compile(graph, catalog);

        assertEquals(List.of(inner, FOURTH), loopControl(plan, LOOP).bodySteps());
        assertEquals(List.of(THIRD), loopControl(plan, inner).bodySteps());
    }

    @Test
    void loopControlChangesCanonicalPlanHash() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action(), done());
        GraphDocument graph = graph(catalog, List.of(node(LOOP, "count-loop"), node(FIRST, "action"), node(DONE, "done")), List.of(
            connection("body", LOOP, "loop", FIRST, "flow"),
            connection("done", LOOP, "done", DONE, "flow")));
        CompiledExecutionPlan compiled = new GraphCompiler().compile(graph, catalog);
        List<CompiledExecutionStep> withoutControl = compiled.steps().stream().map(step -> step.nodeId().equals(LOOP)
            ? new CompiledExecutionStep(step.stepId(), step.nodeId(), step.definition(), step.handler(), step.inputBindings(),
                step.outputBindings(), step.semantics(), step.resolvedBinding(), step.resolvedBindingFingerprint(), step.unknown())
            : step).toList();
        CompiledExecutionPlan ordinary = new CompiledExecutionPlan(compiled.planId(), compiled.graph(), compiled.graphRevision(),
            compiled.catalogBinding(), compiled.graphHash(), withoutControl, compiled.connections(), compiled.conversionRoutes(),
            compiled.structuralRoutes(), compiled.functionBindings(), compiled.providerLeases(), compiled.unknown());

        assertNotEquals(ordinary.planHash(), compiled.planHash());
        assertFalse(ordinary.canonicalJson().contains("\"loopControl\""));
        assertTrue(compiled.canonicalJson().contains("\"loopControl\""));
    }

    @Test
    void ordinaryHandlerDoesNotAcquireLoopControl() {
        CatalogSnapshot catalog = catalog(action());
        CompiledExecutionPlan plan = new GraphCompiler().compile(graph(catalog, List.of(node(FIRST, "action")), List.of()), catalog);

        assertNull(plan.steps().getFirst().loopControl());
    }

    @Test
    void exactLoopOperationRejectsInvalidPinSignature() {
        CatalogSnapshot catalog = catalog(countLoop(BOOLEAN));
        GraphDocument graph = graph(catalog, List.of(node(LOOP, "count-loop")), List.of());

        GraphCompilationResult result = new GraphCompiler().compileResult(graph, catalog);

        assertFalse(result.compiled());
        assertNull(result.plan());
        assertTrue(result.validation().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.PIN_TYPE_MISMATCH")));
        assertThrows(GraphCompilationException.class, () -> new GraphCompiler().compile(graph, catalog));
    }

    @Test
    void rejectsBodyAndDoneConvergence() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action());
        GraphDocument graph = graph(catalog, List.of(node(LOOP, "count-loop"), node(FIRST, "action")), List.of(
            connection("body", LOOP, "loop", FIRST, "flow"),
            connection("done", LOOP, "done", FIRST, "flow")));

        assertRejected(graph, catalog, "GRAPH.LOOP_SCOPE_INVALID", "Body And Done Scopes Converge");
    }

    @Test
    void rejectsLateExternalBodyDependency() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action(), source());
        GraphDocument graph = graph(catalog, List.of(node(LOOP, "count-loop"), node(FIRST, "action"), node(SECOND, "source")), List.of(
            connection("body", LOOP, "loop", FIRST, "flow"),
            connection("late-value", SECOND, "number", FIRST, "value")));

        assertRejected(graph, catalog, "GRAPH.LOOP_SCOPE_INVALID", "Body Has A Late External Data Dependency");
    }

    @Test
    void rejectsTransitiveCompletedDataLeavingDoneScope() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action(), booleanTransform(), done());
        GraphDocument graph = graph(catalog, List.of(
            node(LOOP, "count-loop"),
            node(FIRST, "boolean-transform"),
            node(SECOND, "action"),
            node(FOURTH, "done"),
            node(DONE, "done")), List.of(
            connection("body", LOOP, "loop", SECOND, "flow"),
            connection("done", LOOP, "done", DONE, "flow"),
            connection("completed-transform", LOOP, "completed", FIRST, "input"),
            connection("completed-done", FIRST, "result", DONE, "completed"),
            connection("completed-unrelated", FIRST, "result", FOURTH, "completed")));

        assertRejected(graph, catalog, "GRAPH.LOOP_SCOPE_INVALID", "Completed Output Must Belong To The Done Scope");
    }

    @Test
    void rejectsExternalExecutionIngress() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action(), source());
        GraphDocument graph = graph(catalog, List.of(node(LOOP, "count-loop"), node(FIRST, "action"), node(SECOND, "source")), List.of(
            connection("body", LOOP, "loop", FIRST, "flow"),
            connection("external-flow", SECOND, "flow", FIRST, "flow")));

        assertRejected(graph, catalog, "GRAPH.LOOP_SCOPE_INVALID", "Body Has External Execution Ingress");
    }

    @Test
    void rejectsBodyDataEgress() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action());
        GraphDocument graph = graph(catalog, List.of(node(LOOP, "count-loop"), node(FIRST, "action"), node(SECOND, "action")), List.of(
            connection("body", LOOP, "loop", FIRST, "flow"),
            connection("body-data-egress", FIRST, "number", SECOND, "value")));

        assertRejected(graph, catalog, "GRAPH.LOOP_SCOPE_INVALID", "Body Has External Data Egress");
    }

    @Test
    void rejectsIterationDataEscape() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action(), transform());
        GraphDocument graph = graph(catalog, List.of(node(LOOP, "count-loop"), node(FIRST, "action"), node(SECOND, "transform")), List.of(
            connection("body", LOOP, "loop", FIRST, "flow"),
            connection("escaped-index", LOOP, "index", SECOND, "input")));

        assertRejected(graph, catalog, "GRAPH.LOOP_SCOPE_INVALID", "Iteration Data Leaves The Body Scope");
    }

    @Test
    void loopCyclesReturnStructuredCompilationFailure() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action());
        GraphDocument graph = graph(catalog, List.of(node(LOOP, "count-loop"), node(FIRST, "action")), List.of(
            connection("body", LOOP, "loop", FIRST, "flow"),
            connection("cycle", FIRST, "next", LOOP, "flow")));

        assertRejected(graph, catalog, "GRAPH.LOOP_CYCLE", "Compiled Graph Contains A Cycle");
    }

    @Test
    void reorderedGraphProducesTheSameDeterministicBodyOrder() {
        CatalogSnapshot catalog = catalog(countLoop(NUMBER), action(), done());
        List<GraphNode> nodes = List.of(node(LOOP, "count-loop"), node(FIRST, "action"), node(SECOND, "action"), node(DONE, "done"));
        List<GraphConnection> connections = List.of(
            connection("body", LOOP, "loop", FIRST, "flow"),
            connection("body-next", FIRST, "next", SECOND, "flow"),
            connection("done", LOOP, "done", DONE, "flow"));
        GraphDocument first = graph(catalog, nodes, connections);
        GraphDocument reordered = graph(catalog, nodes.reversed(), connections.reversed());

        CompiledExecutionPlan firstPlan = new GraphCompiler().compile(first, catalog);
        CompiledExecutionPlan reorderedPlan = new GraphCompiler().compile(reordered, catalog);

        assertEquals(List.of(FIRST, SECOND), loopControl(firstPlan, LOOP).bodySteps());
        assertEquals(loopControl(firstPlan, LOOP).bodySteps(), loopControl(reorderedPlan, LOOP).bodySteps());
        assertEquals(firstPlan.planHash(), reorderedPlan.planHash());
    }

    private static void assertRejected(GraphDocument graph, CatalogSnapshot catalog, String code, String reason) {
        GraphCompilationResult result = new GraphCompiler().compileResult(graph, catalog);
        assertFalse(result.compiled());
        assertNull(result.plan());
        assertTrue(result.validation().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals(code)
                && reason.equals(diagnostic.evidence().get("reason"))),
            result.validation().diagnostics()::toString);
        assertThrows(GraphCompilationException.class, () -> new GraphCompiler().compile(graph, catalog));
    }

    private static CompiledExecutionStep.LoopControl loopControl(CompiledExecutionPlan plan, NodeInstanceId nodeId) {
        return plan.steps().stream().filter(step -> step.nodeId().equals(nodeId)).findFirst().orElseThrow().loopControl();
    }

    private static GraphDocument graph(CatalogSnapshot catalog, List<GraphNode> nodes, List<GraphConnection> connections) {
        return new GraphDocument(
            new ServerResourceLocator(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                ContractRef.of(OWNER, ResourceTypeId.of("flow")), "loop-test"),
            1,
            CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()),
            nodes,
            connections);
    }

    private static GraphNode node(NodeInstanceId id, String definition) {
        return new GraphNode(id, ContractRef.of(OWNER, NodeId.of(definition)), 1, Map.of());
    }

    private static GraphConnection connection(String id, NodeInstanceId source, String sourcePin, NodeInstanceId target, String targetPin) {
        return new GraphConnection(ConnectionId.deterministic(id), new GraphEndpoint(source, PinId.of(sourcePin)),
            new GraphEndpoint(target, PinId.of(targetPin)));
    }

    private static CatalogSnapshot catalog(CatalogNodeDescriptor... definitions) {
        CatalogVersion version = new CatalogVersion(1, 0);
        List<CatalogNodeDescriptor> nodes = List.of(definitions);
        List<RuntimeOperationDescriptor> requirements = nodes.stream().map(GraphCompilerLoopControlTest::requirement).toList();
        CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(version, version),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/loop-control-test.json", "1.0.0", "test", "loop-control"))
            .categories(List.of(new CatalogCategoryDescriptor("flow", "Flow", "Operations that compose deterministic compiled loop tests.", 1)))
            .capabilities(List.of(
                new CatalogCapabilityDescriptor(CapabilityId.of("flow"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("flow.control"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("flow.test"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.READ_ONLY_FIELD)))
            .definitions(nodes)
            .runtimeRequirements(requirements)
            .build();
        Map<RuntimeBindingKey, ContentHash> fingerprints = requirements.stream()
            .collect(Collectors.toMap(RuntimeOperationDescriptor::key, RuntimeOperationDescriptor::executionFingerprint,
                (left, right) -> left, LinkedHashMap::new));
        return new CatalogCompiler(version, CatalogBindingProof.fixed(fingerprints, new ContentHash("f".repeat(64))))
            .compile(List.of(contribution), 1).snapshot().orElseThrow();
    }

    private static CatalogNodeDescriptor countLoop(TypeExpr countType) {
        return descriptor("count-loop", LOOP_CAPABILITY, "loop_count", List.of(
            pin("flow", CatalogNodeDescriptor.Direction.INPUT, EXECUTION),
            pin("count", CatalogNodeDescriptor.Direction.INPUT, countType),
            pin("loop", CatalogNodeDescriptor.Direction.OUTPUT, EXECUTION),
            pin("done", CatalogNodeDescriptor.Direction.OUTPUT, EXECUTION),
            pin("index", CatalogNodeDescriptor.Direction.OUTPUT, NUMBER),
            pin("completed", CatalogNodeDescriptor.Direction.OUTPUT, BOOLEAN)));
    }

    private static CatalogNodeDescriptor forEachLoop(TypeExpr elementType) {
        return descriptor("for-each-loop", LOOP_CAPABILITY, "loop_for_each", List.of(
            pin("flow", CatalogNodeDescriptor.Direction.INPUT, EXECUTION),
            pin("list", CatalogNodeDescriptor.Direction.INPUT, TypeExpr.list(elementType)),
            pin("loop", CatalogNodeDescriptor.Direction.OUTPUT, EXECUTION),
            pin("done", CatalogNodeDescriptor.Direction.OUTPUT, EXECUTION),
            pin("index", CatalogNodeDescriptor.Direction.OUTPUT, NUMBER),
            pin("element", CatalogNodeDescriptor.Direction.OUTPUT, elementType),
            pin("completed", CatalogNodeDescriptor.Direction.OUTPUT, BOOLEAN)));
    }

    private static CatalogNodeDescriptor transform() {
        return descriptor("transform", EXECUTE_CAPABILITY, "transform", List.of(
            pin("input", CatalogNodeDescriptor.Direction.INPUT, NUMBER),
            pin("result", CatalogNodeDescriptor.Direction.OUTPUT, NUMBER)));
    }

    private static CatalogNodeDescriptor action() {
        return descriptor("action", EXECUTE_CAPABILITY, "action", List.of(
            pin("flow", CatalogNodeDescriptor.Direction.INPUT, EXECUTION),
            pin("value", CatalogNodeDescriptor.Direction.INPUT, NUMBER),
            pin("next", CatalogNodeDescriptor.Direction.OUTPUT, EXECUTION),
            pin("number", CatalogNodeDescriptor.Direction.OUTPUT, NUMBER)));
    }

    private static CatalogNodeDescriptor textAction() {
        return descriptor("text-action", EXECUTE_CAPABILITY, "text-action", List.of(
            pin("flow", CatalogNodeDescriptor.Direction.INPUT, EXECUTION),
            pin("text", CatalogNodeDescriptor.Direction.INPUT, TEXT)));
    }

    private static CatalogNodeDescriptor done() {
        return descriptor("done", EXECUTE_CAPABILITY, "done", List.of(
            pin("flow", CatalogNodeDescriptor.Direction.INPUT, EXECUTION),
            pin("completed", CatalogNodeDescriptor.Direction.INPUT, BOOLEAN)));
    }

    private static CatalogNodeDescriptor booleanTransform() {
        return descriptor("boolean-transform", EXECUTE_CAPABILITY, "boolean-transform", List.of(
            pin("input", CatalogNodeDescriptor.Direction.INPUT, BOOLEAN),
            pin("result", CatalogNodeDescriptor.Direction.OUTPUT, BOOLEAN)));
    }

    private static CatalogNodeDescriptor source() {
        return descriptor("source", EXECUTE_CAPABILITY, "source", List.of(
            pin("number", CatalogNodeDescriptor.Direction.OUTPUT, NUMBER),
            pin("flow", CatalogNodeDescriptor.Direction.OUTPUT, EXECUTION)));
    }

    private static CatalogNodeDescriptor descriptor(String id, ContractRef<CapabilityId> capability, String operation,
                                                     List<CatalogNodeDescriptor.Pin> pins) {
        CatalogNodeDescriptor.Branch failure = new CatalogNodeDescriptor.Branch("failed", "Failed",
            "Describes the structured failure outcome for this test operation.",
            List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The test operation completed with a structured failure.")));
        return CatalogNodeDescriptor.builder(NodeId.of(id))
            .domain("flow")
            .family("test")
            .displayName("Test Operation")
            .description("Executes one deterministic compiled loop contract test operation.")
            .category(CATEGORY)
            .pins(pins)
            .branches(List.of(failure))
            .handler(new CatalogNodeDescriptor.Handler(capability, ContractRef.of(OWNER, OperationId.of(operation))))
            .semantics(semantics())
            .requiredCapabilities(Set.of(capability))
            .build();
    }

    private static CatalogNodeDescriptor.Pin pin(String id, CatalogNodeDescriptor.Direction direction, TypeExpr type) {
        return new CatalogNodeDescriptor.Pin(PinId.of(id), direction, type, "Test Pin",
            "Carries one exact typed value through the compiled loop contract test.", CatalogNodeDescriptor.Requirement.REQUIRED,
            null, EDITOR, null, null, null);
    }

    private static RuntimeOperationDescriptor requirement(CatalogNodeDescriptor node) {
        List<RuntimeOperationDescriptor.Pin> pins = node.pins().stream()
            .map(pin -> new RuntimeOperationDescriptor.Pin(pin.id(),
                pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                    ? RuntimeOperationDescriptor.Direction.INPUT
                    : RuntimeOperationDescriptor.Direction.OUTPUT,
                pin.type()))
            .toList();
        return new RuntimeOperationDescriptor(node.handler().capability(), node.handler().operation(), pins, node.semantics());
    }

    private static RuntimeSemantics semantics() {
        ContractRef<CapabilityId> authorization = ContractRef.of(OWNER, CapabilityId.of("flow.test"));
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, authorization,
            RuntimeSemantics.Cancellation.NONE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(NUMBER, Set.of("RUNTIME.FAILURE"), Set.of("failed"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }

    private static NodeInstanceId nodeId(String value) {
        return NodeInstanceId.of(UUID.fromString(value));
    }
}
