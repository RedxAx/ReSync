package restudio.resync.customcontent;

import org.junit.jupiter.api.Test;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowGraph;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void graphAdapterRoundTripsStudioFields() {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph("studio_item", "item", "Studio Item");
        CustomContentGraphAdapter.setContentProperty(graph, "custom_model_data", 42);
        CustomContentGraphAdapter.setContentProperty(graph, "lore", "Line One, Line Two");
        CustomContentGraphAdapter.setContentProperty(graph, "tags", "rare, quest");
        CustomContentGraphAdapter.setContentProperty(graph, "enabled", false);
        CustomContentGraphAdapter.setContentProperty(graph, "priority", 7);
        CustomContentGraphAdapter.setEnabledTriggerBranches(graph, List.of("use", "hit_entity", "while_holding"));

        CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(graph);

        assertEquals(42, definition.getCustomModelData());
        assertEquals(List.of("Line One", "Line Two"), definition.getLore());
        assertEquals(List.of("rare", "quest"), definition.getTags());
        assertEquals(3, definition.getAbilities().size());
        assertTrue(definition.getAbilities().stream().allMatch(binding -> !binding.isEnabled()));
        assertTrue(definition.getAbilities().stream().allMatch(binding -> binding.getRule().getPriority() == 7));
    }
}
