package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryActionHandlerTest {
    @Test
    void componentFieldCopiesTheExistingObjectAndAcceptsAnyValueKind() {
        Map<String, Object> existing = Map.of("first", 1);
        TestFlowContext context = execute("item_component_object_field", Map.of(
            "component_value", existing,
            "field", "second",
            "value", List.of("value")
        ), Map.of("valuePin", "value"));

        assertEquals(Map.of("first", 1), existing);
        assertEquals(Map.of("first", 1, "second", List.of("value")), context.outputs.get("component_value"));
        assertNotSame(existing, context.outputs.get("component_value"));
    }

    @Test
    void componentListEntryCopiesTheExistingListAndAcceptsAnyValueKind() {
        List<Object> existing = List.of("first");
        TestFlowContext context = execute("item_component_list_entry", Map.of(
            "items", existing,
            "value", Map.of("nested", true)
        ), Map.of("valuePin", "value"));

        assertEquals(List.of("first"), existing);
        assertEquals(List.of("first", Map.of("nested", true)), context.outputs.get("items"));
        assertNotSame(existing, context.outputs.get("items"));
    }

    @Test
    void componentBuilderConsumesTheCompiledTypedResourceLocator() {
        ServerId server = new ServerId(UUID.fromString("11111111-1111-5111-8111-111111111111"));
        ServerResourceLocator locator = new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("builtin"), ResourceTypeId.of(ReSyncResourceCatalog.COMPONENT_BUILDER)), "lore");

        assertEquals("lore", InventoryActionHandler.requireComponentBuilderId(locator, server));
        assertEquals("lore", InventoryActionHandler.requireComponentBuilderId(locator.canonicalText(), server));
        assertThrows(IllegalArgumentException.class, () -> InventoryActionHandler.requireComponentBuilderId(
            new ServerResourceLocator(new ServerId(UUID.randomUUID()), locator.type(), "lore"), server));
        assertThrows(IllegalArgumentException.class, () -> InventoryActionHandler.requireComponentBuilderId(
            new ServerResourceLocator(server, ContractRef.of(OwnerId.of("builtin"), ResourceTypeId.of("flow")), "lore"), server));
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs, Map<String, Object> configuration) {
        HandlerRegistry registry = new HandlerRegistry();
        new InventoryActionHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("InventoryActionHandler");
        FlowNode node = new FlowNode("itemstack." + operation, 0, 0, Map.of());
        Map<String, Object> handlerConfiguration = new HashMap<>(configuration);
        handlerConfiguration.put("operation", operation);
        node.setHandlerConfig(handlerConfiguration);
        TestFlowContext context = new TestFlowContext(inputs);
        handler.execute(context, node);
        return context;
    }

    private static final class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            Object value = inputs.get(pinName);
            return value != null ? type.cast(value) : defaultValue;
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }

        @Override
        public void triggerOutput(String pinName) {
        }
    }
}
