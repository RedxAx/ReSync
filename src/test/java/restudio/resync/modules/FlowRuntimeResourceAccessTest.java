package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimeResourceAccessTest {
    private static final ContractRef<ResourceTypeId> TIMER_DEFINITION = ContractRef.of(
        OwnerId.of("builtin"), ResourceTypeId.of("timer_definition"));
    private static final ContractRef<ResourceTypeId> QUEST = ContractRef.of(
        OwnerId.of("fixture"), ResourceTypeId.of("quest"));

    @Test
    void directInputResourceDeclaresItsExactBuiltinRead() {
        RuntimeOperationDescriptor descriptor = descriptor(builder()
            .input(pin("timer", NodeDefinition.PinDirection.INPUT, FlowDataType.TIMER_REFERENCE,
                FlowTypeRef.simple("timer_reference")))
            .build());

        assertEquals(Set.of(TIMER_DEFINITION), descriptor.semantics().resourceReads());
        assertTrue(descriptor.semantics().resourceWrites().isEmpty());
    }

    @Test
    void outputOnlyResourceDeclaresItsExactCustomOwnerRead() {
        RuntimeOperationDescriptor descriptor = descriptor(builder()
            .output(pin("quest", NodeDefinition.PinDirection.OUTPUT, FlowDataType.RESOURCE_REFERENCE,
                FlowTypeRef.parse("resource_reference<fixture:quest>")))
            .build());

        assertEquals(Set.of(QUEST), descriptor.semantics().resourceReads());
        assertTrue(descriptor.semantics().resourceWrites().isEmpty());
    }

    @Test
    void nestedResourceTypesDeclareEveryExactReadOnce() {
        FlowTypeRef nested = FlowTypeRef.parse(
            "map<resource_reference<builtin:timer_definition>,list<optional<resource_reference<fixture:quest>>>>");
        RuntimeOperationDescriptor descriptor = descriptor(builder()
            .input(pin("resources", NodeDefinition.PinDirection.INPUT, FlowDataType.MAP, nested))
            .build());

        assertEquals(Set.of(TIMER_DEFINITION, QUEST), descriptor.semantics().resourceReads());
        assertTrue(descriptor.semantics().resourceWrites().isEmpty());
    }

    @Test
    void resourceFreePinsDeclareNoResourceAccess() {
        RuntimeOperationDescriptor descriptor = descriptor(builder()
            .input(pin("labels", NodeDefinition.PinDirection.INPUT, FlowDataType.LIST, FlowTypeRef.parse("list<string>")))
            .output(pin("count", NodeDefinition.PinDirection.OUTPUT, FlowDataType.NUMBER, FlowTypeRef.simple("number")))
            .build());

        assertTrue(descriptor.semantics().resourceReads().isEmpty());
        assertTrue(descriptor.semantics().resourceWrites().isEmpty());
    }

    private NodeDefinition.Builder builder() {
        return new NodeDefinition.Builder("fixture:resource_access", "Resource Access", NodeDefinition.NodeCategory.UTILITY)
            .owner("fixture")
            .handler("ResourceHandler")
            .handlerConfig(Map.of("operation", "inspect"));
    }

    private NodeDefinition.PinDefinition pin(String id, NodeDefinition.PinDirection direction, FlowDataType dataType,
                                             FlowTypeRef typeRef) {
        return new NodeDefinition.PinBuilder(id, NodeDefinition.PinType.DATA, direction, dataType)
            .typeRef(typeRef)
            .build();
    }

    private RuntimeOperationDescriptor descriptor(NodeDefinition definition) {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("ResourceHandler", new ResourceHandler());
        return FlowModule.runtimeOperationDescriptor(definition, handlers);
    }

    private static final class ResourceHandler implements NodeHandler {
        @Override
        public void execute(FlowContext context, FlowNode node) {
        }

        @Override
        public Set<String> getSupportedOperations() {
            return Set.of("inspect");
        }
    }
}
