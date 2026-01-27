package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class ListAdvancedNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("list_sort", "List Sort", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("sort_order", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("sorted_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_sort_by_property", "List Sort By Property", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("property_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("sort_order", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("sorted_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_filter", "List Filter", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("property_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("operator", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("compare_value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("filtered_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_map", "List Map", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("transformation_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("transformed_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_reduce", "List Reduce", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("operation", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("separator", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("result", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("list_shuffle", "List Shuffle", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("shuffled_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_unique", "List Unique", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("unique_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_slice", "List Slice", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("start_index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("end_index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("slice_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_reverse", "List Reverse", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("reversed_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_find_first", "List Find First", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("property_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("operator", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("compare_value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("found_element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("list_find_all", "List Find All", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("property_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("operator", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("compare_value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("found_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_find_index", "List Find Index", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("element", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("index", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("list_contains_any", "List Contains Any", NodeDefinition.NodeCategory.LOGIC)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("elements_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("contains_any", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("list_contains_all", "List Contains All", NodeDefinition.NodeCategory.LOGIC)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("elements_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("contains_all", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("list_sum", "List Sum", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("sum", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("list_average", "List Average", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("average", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("list_min_value", "List Min Value", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("min", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("list_max_value", "List Max Value", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("max", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("list_median", "List Median", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("median", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("list_mode", "List Mode", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("modes_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_range", "List Range", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("range", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("list_variance", "List Variance", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("variance", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("list_stddev", "List Standard Deviation", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("stddev", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("list_flatten", "List Flatten", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("flattened_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_chunk", "List Chunk", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("chunk_size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("chunks_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_partition", "List Partition", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("property_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("operator", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("compare_value", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("true_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("false_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_intersect", "List Intersect", NodeDefinition.NodeCategory.DATA)
            .input("list1", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("list2", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("intersection_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_union", "List Union", NodeDefinition.NodeCategory.DATA)
            .input("list1", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("list2", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("union_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_difference", "List Difference", NodeDefinition.NodeCategory.DATA)
            .input("list1", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("list2", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("difference_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_zip", "List Zip", NodeDefinition.NodeCategory.DATA)
            .input("list1", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("list2", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("pairs_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_take_first", "List Take First", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("taken_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_take_last", "List Take Last", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("taken_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_drop_first", "List Drop First", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("remaining_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("list_drop_last", "List Drop Last", NodeDefinition.NodeCategory.DATA)
            .input("list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("remaining_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());
    }
}
