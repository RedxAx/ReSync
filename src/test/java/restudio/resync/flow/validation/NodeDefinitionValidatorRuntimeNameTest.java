package restudio.resync.flow.validation;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionValidator;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionValidatorRuntimeNameTest {
    @Test
    void familyContractRecognizesActionRuntimeNameWithStableId() {
        NodeDefinition definition = new NodeDefinition.Builder("test.family", "Test Family", NodeDefinition.NodeCategory.UTILITY)
            .kind(NodeDefinition.NodeKind.FAMILY)
            .handler("test")
            .input(new NodeDefinition.PinBuilder("input_flow", NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION)
                .runtimeName("flow")
                .build())
            .input(new NodeDefinition.PinBuilder("input_action", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .runtimeName("action")
                .build())
            .build();

        NodeDefinitionValidator.ValidationResult result = new NodeDefinitionValidator(null, true).validate(definition);

        assertTrue(result.valid(), result.errors().toString());
    }
}
