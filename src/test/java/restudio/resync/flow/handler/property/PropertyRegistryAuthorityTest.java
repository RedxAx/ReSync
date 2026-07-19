package restudio.resync.flow.handler.property;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyRegistryAuthorityTest {
    @Test
    void definitionsPopulateExecutablePropertyCapabilities() {
        NodeDefinition definition = new NodeDefinition.Builder("inventory.items", "Inventory Items", NodeDefinition.NodeCategory.INVENTORY)
            .handler("inventory")
            .handlerConfig(Map.of("property", "items"))
            .input(new NodeDefinition.PinBuilder("action", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .options(List.of("get", "set"))
                .build())
            .output(new NodeDefinition.PinBuilder("value", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.LIST)
                .typeRef(FlowTypeRef.parse("list<item>"))
                .build())
            .build();
        PropertyRegistry registry = new PropertyRegistry();

        registry.loadNodeDefinitions(List.of(definition));

        PropertyRegistry.PropertyDescriptor descriptor = registry.getDescriptor("inventory", "items");
        assertEquals("list<item>", descriptor.type().toString());
        assertEquals(List.of("get", "set"), descriptor.actions());
        assertTrue(descriptor.readable());
        assertTrue(descriptor.writable());
        assertFalse(descriptor.invokable());
    }
}
