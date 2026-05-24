package restudio.resync.api;

import restudio.flow.data.FlowDataType;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.List;

public final class NodeDefinitions {
    private NodeDefinitions() {
    }

    public static NodeDefinition.PinDefinition flowIn() {
        return input("flow", FlowDataType.EXECUTION, NodeDefinition.PinType.FLOW);
    }

    public static NodeDefinition.PinDefinition flowOut() {
        return output("flow", FlowDataType.EXECUTION, NodeDefinition.PinType.FLOW);
    }

    public static NodeDefinition.PinDefinition player(String name) {
        return input(name, FlowDataType.PLAYER);
    }

    public static NodeDefinition.PinDefinition input(String name, FlowDataType type) {
        return input(name, type, NodeDefinition.PinType.DATA);
    }

    public static NodeDefinition.PinDefinition output(String name, FlowDataType type) {
        return output(name, type, NodeDefinition.PinType.DATA);
    }

    public static NodeDefinition.PinDefinition searchableInput(String name, FlowDataType type, String source, String defaultValue) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, type)
            .widget(NodeDefinition.WidgetType.SEARCHABLE_LIST)
            .optionsSource(source)
            .defaultValue(defaultValue)
            .build();
    }

    public static NodeDefinition.PinDefinition dropdownInput(String name, String defaultValue, List<String> options) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
            .widget(NodeDefinition.WidgetType.DROPDOWN)
            .options(options)
            .defaultValue(defaultValue)
            .build();
    }

    public static NodeDefinition.PinDefinition toggleInput(String name, boolean defaultValue) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.BOOLEAN)
            .widget(NodeDefinition.WidgetType.TOGGLE)
            .defaultValue(String.valueOf(defaultValue))
            .build();
    }

    public static NodeDefinition.PinDefinition numberInput(String name, int defaultValue) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.NUMBER)
            .defaultValue(String.valueOf(defaultValue))
            .build();
    }

    private static NodeDefinition.PinDefinition input(String name, FlowDataType type, NodeDefinition.PinType pinType) {
        return new NodeDefinition.PinDefinition(name, pinType, NodeDefinition.PinDirection.INPUT, type);
    }

    private static NodeDefinition.PinDefinition output(String name, FlowDataType type, NodeDefinition.PinType pinType) {
        return new NodeDefinition.PinDefinition(name, pinType, NodeDefinition.PinDirection.OUTPUT, type);
    }
}
