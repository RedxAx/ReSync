package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionDiscoveryMetadataTest {
    @Test
    void generatedDescriptionsRecognizeDomainPrefixedActions() {
        List<NodeDefinition> definitions = parse("""
            [
              {"id":"list.sort","displayName":"List Sort","category":"data","kind":"PURE","handler":"GenericListHandler"},
              {"id":"player.set_health","displayName":"Player Set Health","category":"player","kind":"ACTION","handler":"PlayerActionHandler"},
              {"id":"list.contains","displayName":"List Contains","category":"data","kind":"PURE","handler":"GenericListHandler"}
            ]
            """);

        assertEquals("Sorts list.", definitions.get(0).getDescription());
        assertEquals("Sets player health.", definitions.get(1).getDescription());
        assertEquals("Checks whether the list contains the requested value.", definitions.get(2).getDescription());
    }

    @Test
    void generatedDiscoveryMetadataIsSearchableAndActionable() {
        NodeDefinition definition = parse("""
            {"id":"player.set_health","displayName":"Player Set Health","category":"player","kind":"ACTION","handler":"PlayerActionHandler"}
            """).getFirst();

        assertTrue(definition.getTags().contains("player"));
        assertTrue(definition.getTags().contains("health"));
        assertFalse(definition.getExamples().isEmpty());
    }

    @Test
    void inferredCatalogInputsKeepAutomaticWidgetAuthority() {
        NodeDefinition definition = parse("""
            {
              "id":"test.material_input",
              "displayName":"Material Input",
              "category":"data",
              "kind":"PURE",
              "handler":"TestHandler",
              "inputs":[{"name":"material","pinType":"DATA","dataType":"material"}]
            }
            """).getFirst();
        NodeDefinition.PinDefinition input = definition.getInputs().getFirst();

        assertEquals("server:minecraft:material", input.getOptionsSource());
        assertEquals(NodeDefinition.WidgetType.AUTO, input.getWidgetType());
    }

    private List<NodeDefinition> parse(String json) {
        return new NodeDefinitionLoader().parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "discovery-test");
    }
}
