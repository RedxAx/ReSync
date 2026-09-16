package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.registry.AuthoredNodeMetadata;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowModuleQualifiedTypeRuntimeDescriptorTest {
    @Test
    void ownerQualifiedExtensionTypesReachTheRuntimeBindingWithoutCoercion() {
        HandlerRegistry handlers = requestHandlers();
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(requestDefinition("request:quest"), handlers);

        assertEquals(ContractRef.of(OwnerId.of("request"), CapabilityId.of("quest.handler")), requirement.capability());
        assertEquals(ContractRef.of(OwnerId.of("request"), OperationId.of("info")), requirement.operation());
        TypeExpr.Named quest = assertInstanceOf(TypeExpr.Named.class, requirement.inputs().getFirst());
        assertEquals(TypeReference.of("request", "quest"), quest.reference());
        RuntimeOperationDescriptor collectionRequirement = FlowModule.runtimeOperationDescriptor(
            requestDefinition(FlowTypeRef.parse("list<request:quest>")), handlers);
        TypeExpr.ListType quests = assertInstanceOf(TypeExpr.ListType.class, collectionRequirement.inputs().getFirst());
        assertEquals(TypeReference.of("request", "quest"), assertInstanceOf(TypeExpr.Named.class, quests.element()).reference());

        RuntimeBindingRegistry bindings = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("resync"), ProviderId.of("flow"));
        bindings.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(
            RuntimeBinding.available(requirement, provider, "1.0.0", ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));

        assertTrue(CatalogBindingProof.live(bindings).proves(requirement));
    }

    @Test
    void malformedOwnerQualifiedTypesAreRejectedBeforeRuntimeBinding() {
        HandlerRegistry handlers = requestHandlers();

        for (String type : List.of(":quest", "request:", "request::quest", "request:quest:extra")) {
            assertThrows(IllegalArgumentException.class, () -> FlowModule.runtimeOperationDescriptor(requestDefinition(type), handlers));
        }
    }

    @Test
    void normalRequestMessageFunctionKeepsItsDeclaredCallContract() {
        FlowGraph function = new FlowGraph();
        function.setId("reQuestMessage");
        function.setFunction(true);
        function.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter("target", FlowDataType.PLAYER),
            new FlowGraph.FunctionParameter("message", FlowDataType.STRING)
        ));
        HandlerRegistry handlers = new HandlerRegistry();
        new CustomFunctionCallHandler().registerTo(handlers);

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(function);
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(definition, handlers);

        assertEquals("custom_function_call", requirement.operation().id().value());
        assertEquals(List.of("flow", "message", "target"), definition.getInputs().stream()
            .map(NodeDefinition.PinDefinition::getRuntimeName).sorted().toList());
        assertEquals(TypeReference.of("builtin", "string"), namedInput(definition, requirement, "message").reference());
        assertEquals(TypeReference.of("builtin", "player"), namedInput(definition, requirement, "target").reference());
    }

    @Test
    void triggerOperationsRemainDistinctWhenTheyShareOneCapability() {
        RuntimeOperationDescriptor variable = FlowModule.runtimeOperationDescriptor(triggerDefinition("event.variable.changed"), new HandlerRegistry());
        RuntimeOperationDescriptor timer = FlowModule.runtimeOperationDescriptor(triggerDefinition("event.timer"), new HandlerRegistry());

        assertEquals("trigger_event.variable.changed", variable.operation().id().value());
        assertEquals("trigger_event.timer", timer.operation().id().value());
        assertNotEquals(variable.key(), timer.key());
    }

    @Test
    void genericJobReferencesRemainNamedRuntimeTypes() {
        RuntimeOperationDescriptor requirement = FlowModule.runtimeOperationDescriptor(
            requestDefinition(FlowTypeRef.parse("job_reference<any>")), requestHandlers());

        TypeExpr.Named job = assertInstanceOf(TypeExpr.Named.class, requirement.inputs().getFirst());
        assertEquals(TypeReference.of("builtin", "job_reference"), job.reference());
        assertEquals(1, job.arguments().size());
        assertEquals(TypeReference.of("builtin", "any"), assertInstanceOf(TypeExpr.Named.class, job.arguments().getFirst()).reference());
    }

    private HandlerRegistry requestHandlers() {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("request:handler", new RequestHandler());
        return handlers;
    }

    private NodeDefinition requestDefinition(String questType) {
        return requestDefinition(FlowTypeRef.simple(questType));
    }

    private NodeDefinition requestDefinition(FlowTypeRef questType) {
        return new NodeDefinition.Builder("request:quest_info", "Quest Info", NodeDefinition.NodeCategory.UTILITY)
            .owner("request")
            .handler("request:handler")
            .handlerConfig(Map.of("operation", "info"))
            .authoredMetadata(new AuthoredNodeMetadata("quest_info", "quest", "quest", "active",
                "Reads the selected quest and the player's current progress.", "quest.handler", "typed-selector", "compact"))
            .input(new NodeDefinition.PinBuilder("quest", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .typeRef(questType)
                .build())
            .output("title", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .build();
    }

    private NodeDefinition triggerDefinition(String id) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.EVENT)
            .description("Starts a Flow when the selected automation event occurs.")
            .trigger(true)
            .eventType("restudio.resync.flow.automation.event." + id.substring(id.indexOf('.') + 1))
            .authoredMetadata(new AuthoredNodeMetadata(id, "automation", "event", "active",
                "Starts a Flow when the selected automation event occurs.", "automation.trigger", "typed-reference", "generic"))
            .output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .build();
    }

    private TypeExpr.Named namedInput(NodeDefinition definition, RuntimeOperationDescriptor requirement, String name) {
        int index = definition.getInputs().stream().map(NodeDefinition.PinDefinition::getRuntimeName).toList().indexOf(name);
        return assertInstanceOf(TypeExpr.Named.class, requirement.inputs().get(index));
    }

    private static final class RequestHandler implements NodeHandler {
        @Override
        public void execute(FlowContext context, FlowNode node) {
        }

        @Override
        public Set<String> getSupportedOperations() {
            return Set.of("info");
        }
    }
}
