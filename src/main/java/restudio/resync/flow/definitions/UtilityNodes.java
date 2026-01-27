package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class UtilityNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("time_current_ticks", "Current Ticks", NodeDefinition.NodeCategory.DATA)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("time_current_real_ms", "Current Real MS", NodeDefinition.NodeCategory.DATA)
            .output("time_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("time_current_real_seconds", "Current Real Seconds", NodeDefinition.NodeCategory.DATA)
            .output("time_seconds", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("time_format", "Time Format", NodeDefinition.NodeCategory.DATA)
            .input("timestamp_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("format_pattern", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("formatted_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("time_parse", "Time Parse", NodeDefinition.NodeCategory.DATA)
            .input("date_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("format_pattern", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("timestamp_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("time_add", "Time Add", NodeDefinition.NodeCategory.DATA)
            .input("timestamp_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("unit", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("new_timestamp", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("time_diff", "Time Diff", NodeDefinition.NodeCategory.DATA)
            .input("timestamp1_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("timestamp2_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("unit", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("diff_value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("time_between", "Time Between", NodeDefinition.NodeCategory.DATA)
            .input("start_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("end_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("days", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("hours", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("minutes", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("seconds", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("milliseconds", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("time_is_before", "Time Is Before", NodeDefinition.NodeCategory.LOGIC)
            .input("timestamp1_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("timestamp2_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("is_before", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("time_is_after", "Time Is After", NodeDefinition.NodeCategory.LOGIC)
            .input("timestamp1_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("timestamp2_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("is_after", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("time_convert_ticks_to_ms", "Ticks To MS", NodeDefinition.NodeCategory.DATA)
            .input("ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("milliseconds", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("time_convert_ms_to_ticks", "MS To Ticks", NodeDefinition.NodeCategory.DATA)
            .input("milliseconds", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("uuid_random", "UUID Random", NodeDefinition.NodeCategory.DATA)
            .output("uuid_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("uuid_from_string", "UUID From String", NodeDefinition.NodeCategory.DATA)
            .input("uuid_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("uuid_object", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("uuid_to_string", "UUID To String", NodeDefinition.NodeCategory.DATA)
            .input("uuid_object", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("uuid_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("color_from_rgb", "Color From RGB", NodeDefinition.NodeCategory.DATA)
            .input("red", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("green", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("blue", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("color_from_hex", "Color From Hex", NodeDefinition.NodeCategory.DATA)
            .input("hex_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("color_to_hex", "Color To Hex", NodeDefinition.NodeCategory.DATA)
            .input("color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("hex_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("color_to_rgb", "Color To RGB", NodeDefinition.NodeCategory.DATA)
            .input("color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("red", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("green", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("blue", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("color_mix", "Color Mix", NodeDefinition.NodeCategory.DATA)
            .input("color1", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("color2", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("ratio", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("mixed_color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("color_invert", "Color Invert", NodeDefinition.NodeCategory.DATA)
            .input("color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("inverted_color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("color_brighten", "Color Brighten", NodeDefinition.NodeCategory.DATA)
            .input("color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("brightened_color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("color_darken", "Color Darken", NodeDefinition.NodeCategory.DATA)
            .input("color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("darkened_color", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("delay", "Delay", NodeDefinition.NodeCategory.UTILITY)
            .input("ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("run_async", "Run Async", NodeDefinition.NodeCategory.UTILITY)
            .output("async_flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("run_sync", "Run Sync", NodeDefinition.NodeCategory.UTILITY)
            .output("sync_flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("console_log", "Console Log", NodeDefinition.NodeCategory.UTILITY)
            .input("level", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
