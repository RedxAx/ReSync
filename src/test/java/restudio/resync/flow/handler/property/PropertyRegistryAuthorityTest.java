package restudio.resync.flow.handler.property;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;

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
            .input(new NodeDefinition.PinBuilder("input_action", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .runtimeName("action")
                .options(List.of("get", "set"))
                .build())
            .output(new NodeDefinition.PinBuilder("output_value", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.LIST)
                .runtimeName("value")
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

    @Test
    void propertyOutputRuntimeNameSuppliesDescriptorType() {
        NodeDefinition definition = new NodeDefinition.Builder("inventory.health", "Inventory Health", NodeDefinition.NodeCategory.INVENTORY)
            .handler("inventory")
            .handlerConfig(Map.of("property", "health"))
            .output(new NodeDefinition.PinBuilder("output_health", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.INTEGER)
                .runtimeName("health")
                .build())
            .build();
        PropertyRegistry registry = new PropertyRegistry();

        registry.loadNodeDefinitions(List.of(definition));

        assertEquals("integer", registry.getDescriptor("inventory", "health").type().getTypeId());
    }

    @Test
    void selectorDefinitionsRegisterOptionsAndMergeQueryAndActionCapabilities() {
        NodeDefinition query = new NodeDefinition.Builder("inventory.query", "Inventory Query", NodeDefinition.NodeCategory.INVENTORY)
            .handler("inventory")
            .handlerConfig(Map.of("operation", "get"))
            .input(new NodeDefinition.PinBuilder("property", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .options(List.of("size", " ", "items", "missing", "size"))
                .build())
            .input(new NodeDefinition.PinBuilder("action", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .options(List.of("get", "has"))
                .build())
            .output(new NodeDefinition.PinBuilder("size_result", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.NUMBER)
                .runtimeName("size")
                .build())
            .output(new NodeDefinition.PinBuilder("items", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.LIST)
                .runtimeName("items_output")
                .typeRef(FlowTypeRef.parse("list<itemstack>"))
                .build())
            .build();
        NodeDefinition action = new NodeDefinition.Builder("inventory.action", "Inventory Action", NodeDefinition.NodeCategory.INVENTORY)
            .handler("inventory")
            .handlerConfig(Map.of("operation", "set"))
            .input(new NodeDefinition.PinBuilder("selector", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .runtimeName("property")
                .options(List.of("size", "items", "missing"))
                .build())
            .input(new NodeDefinition.PinBuilder("action", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .options(List.of("set", "do"))
                .build())
            .input(new NodeDefinition.PinBuilder("value", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.ANY)
                .build())
            .output(new NodeDefinition.PinBuilder("success", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.BOOLEAN)
                .build())
            .build();

        PropertyRegistry forward = new PropertyRegistry();
        forward.loadNodeDefinitions(List.of(query, action));
        PropertyRegistry reverse = new PropertyRegistry();
        reverse.loadNodeDefinitions(List.of(action, query));

        assertEquals(List.of("items", "missing", "size"), forward.getProperties("inventory"));
        assertFalse(forward.hasProperty("inventory", ""));
        for (String property : List.of("size", "items", "missing")) {
            assertEquals(forward.getType("inventory", property).toString(), reverse.getType("inventory", property).toString());
            assertEquals(List.of("get", "has", "set", "do"), forward.getActions("inventory", property));
            assertEquals(forward.getActions("inventory", property), reverse.getActions("inventory", property));
        }
        assertEquals("number", forward.getType("inventory", "size").getTypeId());
        assertEquals("list<itemstack>", forward.getType("inventory", "items").toString());
        assertEquals("any", forward.getType("inventory", "missing").getTypeId());
    }

    @Test
    void productionSelectorsAdvertiseAffectedProperties() {
        PropertyRegistry registry = new PropertyRegistry();
        registry.loadNodeDefinitions(new NodeDefinitionLoader().loadFromClasspath("nodes"));

        assertEquals("number", registry.getType("inventory", "size").getTypeId());
        assertEquals("number", registry.getType("itemstack", "amount").getTypeId());
        assertEquals("list<itemstack>", registry.getType("block", "container_items").toString());
        assertEquals("boolean", registry.getType("entity", "is_dead").getTypeId());
        assertEquals("list<item>", registry.getType("player", "armor").toString());
        assertTrue(registry.getActions("inventory", "type").containsAll(List.of("get", "has", "set", "do", "execute")));
        assertTrue(registry.getActions("itemstack", "amount").containsAll(List.of("get", "has")));
    }
}
