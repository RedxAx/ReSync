package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class DataStructureNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("map_create", "Map Create", NodeDefinition.NodeCategory.DATA)
            .output("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_put", "Map Put", NodeDefinition.NodeCategory.ACTION)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("key", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_get", "Map Get", NodeDefinition.NodeCategory.DATA)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("key", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("default_value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("map_remove", "Map Remove", NodeDefinition.NodeCategory.ACTION)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("key", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_contains_key", "Map Contains Key", NodeDefinition.NodeCategory.LOGIC)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("key", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("contains", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("map_contains_value", "Map Contains Value", NodeDefinition.NodeCategory.LOGIC)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("contains", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("map_clear", "Map Clear", NodeDefinition.NodeCategory.ACTION)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .output("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_size", "Map Size", NodeDefinition.NodeCategory.DATA)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .output("size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("map_is_empty", "Map Is Empty", NodeDefinition.NodeCategory.LOGIC)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .output("is_empty", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("map_keys", "Map Keys", NodeDefinition.NodeCategory.DATA)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .output("keys_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("map_values", "Map Values", NodeDefinition.NodeCategory.DATA)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .output("values_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("map_entries", "Map Entries", NodeDefinition.NodeCategory.DATA)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .output("entries_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("map_merge", "Map Merge", NodeDefinition.NodeCategory.DATA)
            .input("map1", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("map2", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("overwrite", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("merged_map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_put_all", "Map Put All", NodeDefinition.NodeCategory.ACTION)
            .input("target_map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("source_map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .output("target_map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_from_lists", "Map From Lists", NodeDefinition.NodeCategory.DATA)
            .input("keys_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("values_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_to_lists", "Map To Lists", NodeDefinition.NodeCategory.DATA)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .output("keys_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("values_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("map_filter_by_keys", "Map Filter By Keys", NodeDefinition.NodeCategory.DATA)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("filter_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("keep", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("filtered_map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_filter_by_values", "Map Filter By Values", NodeDefinition.NodeCategory.DATA)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("property_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("operator", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("compare_value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("filtered_map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_transform_values", "Map Transform Values", NodeDefinition.NodeCategory.ACTION)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .input("transformation", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("transformed_map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("map_clone", "Map Clone", NodeDefinition.NodeCategory.DATA)
            .input("map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .output("cloned_map", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("set_create", "Set Create", NodeDefinition.NodeCategory.DATA)
            .output("set", NodeDefinition.PinType.DATA, FlowType.SET)
            .build());

        registry.register(new NodeDefinition.Builder("set_add", "Set Add", NodeDefinition.NodeCategory.ACTION)
            .input("set", NodeDefinition.PinType.DATA, FlowType.SET)
            .input("element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("set", NodeDefinition.PinType.DATA, FlowType.SET)
            .build());

        registry.register(new NodeDefinition.Builder("set_remove", "Set Remove", NodeDefinition.NodeCategory.ACTION)
            .input("set", NodeDefinition.PinType.DATA, FlowType.SET)
            .input("element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("set", NodeDefinition.PinType.DATA, FlowType.SET)
            .build());

        registry.register(new NodeDefinition.Builder("set_contains", "Set Contains", NodeDefinition.NodeCategory.LOGIC)
            .input("set", NodeDefinition.PinType.DATA, FlowType.SET)
            .input("element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("contains", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("set_clear", "Set Clear", NodeDefinition.NodeCategory.ACTION)
            .input("set", NodeDefinition.PinType.DATA, FlowType.SET)
            .output("set", NodeDefinition.PinType.DATA, FlowType.SET)
            .build());

        registry.register(new NodeDefinition.Builder("set_size", "Set Size", NodeDefinition.NodeCategory.DATA)
            .input("set", NodeDefinition.PinType.DATA, FlowType.SET)
            .output("size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("set_union", "Set Union", NodeDefinition.NodeCategory.DATA)
            .input("set1", NodeDefinition.PinType.DATA, FlowType.SET)
            .input("set2", NodeDefinition.PinType.DATA, FlowType.SET)
            .output("union_set", NodeDefinition.PinType.DATA, FlowType.SET)
            .build());

        registry.register(new NodeDefinition.Builder("set_intersection", "Set Intersection", NodeDefinition.NodeCategory.DATA)
            .input("set1", NodeDefinition.PinType.DATA, FlowType.SET)
            .input("set2", NodeDefinition.PinType.DATA, FlowType.SET)
            .output("intersection_set", NodeDefinition.PinType.DATA, FlowType.SET)
            .build());

        registry.register(new NodeDefinition.Builder("set_difference", "Set Difference", NodeDefinition.NodeCategory.DATA)
            .input("set1", NodeDefinition.PinType.DATA, FlowType.SET)
            .input("set2", NodeDefinition.PinType.DATA, FlowType.SET)
            .output("difference_set", NodeDefinition.PinType.DATA, FlowType.SET)
            .build());

        registry.register(new NodeDefinition.Builder("set_is_subset", "Set Is Subset", NodeDefinition.NodeCategory.LOGIC)
            .input("potential_subset", NodeDefinition.PinType.DATA, FlowType.SET)
            .input("superset", NodeDefinition.PinType.DATA, FlowType.SET)
            .output("is_subset", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("set_is_superset", "Set Is Superset", NodeDefinition.NodeCategory.LOGIC)
            .input("superset", NodeDefinition.PinType.DATA, FlowType.SET)
            .input("potential_subset", NodeDefinition.PinType.DATA, FlowType.SET)
            .output("is_superset", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("queue_create", "Queue Create", NodeDefinition.NodeCategory.DATA)
            .output("queue", NodeDefinition.PinType.DATA, FlowType.QUEUE)
            .build());

        registry.register(new NodeDefinition.Builder("queue_enqueue", "Queue Enqueue", NodeDefinition.NodeCategory.ACTION)
            .input("queue", NodeDefinition.PinType.DATA, FlowType.QUEUE)
            .input("element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("queue", NodeDefinition.PinType.DATA, FlowType.QUEUE)
            .build());

        registry.register(new NodeDefinition.Builder("queue_dequeue", "Queue Dequeue", NodeDefinition.NodeCategory.DATA)
            .input("queue", NodeDefinition.PinType.DATA, FlowType.QUEUE)
            .output("dequeued_element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("queue_peek", "Queue Peek", NodeDefinition.NodeCategory.DATA)
            .input("queue", NodeDefinition.PinType.DATA, FlowType.QUEUE)
            .output("front_element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("stack_create", "Stack Create", NodeDefinition.NodeCategory.DATA)
            .output("stack", NodeDefinition.PinType.DATA, FlowType.STACK)
            .build());

        registry.register(new NodeDefinition.Builder("stack_push", "Stack Push", NodeDefinition.NodeCategory.ACTION)
            .input("stack", NodeDefinition.PinType.DATA, FlowType.STACK)
            .input("element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("stack", NodeDefinition.PinType.DATA, FlowType.STACK)
            .build());

        registry.register(new NodeDefinition.Builder("stack_pop", "Stack Pop", NodeDefinition.NodeCategory.DATA)
            .input("stack", NodeDefinition.PinType.DATA, FlowType.STACK)
            .output("popped_element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("stack_peek", "Stack Peek", NodeDefinition.NodeCategory.DATA)
            .input("stack", NodeDefinition.PinType.DATA, FlowType.STACK)
            .output("top_element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());
    }
}
