package restudio.resync.customcontent;

import org.junit.jupiter.api.Test;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowGraph;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentCompatibilityTest {
    @Test
    void validatorAcceptsGraphDerivedDefaultContent() {
        CustomContentValidator validator = new CustomContentValidator();

        for (String type : new String[]{"item", "block", "armor"}) {
            FlowGraph graph = CustomContentGraphAdapter.createContentGraph("flow_default_" + type, type, "Default " + type);
            CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(graph);

            assertTrue(validator.validate(definition).isEmpty(), () -> type + " should be valid");
        }
    }
}
