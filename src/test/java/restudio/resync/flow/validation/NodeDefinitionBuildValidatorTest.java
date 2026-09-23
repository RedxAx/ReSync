package restudio.resync.flow.validation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionBuildValidatorTest {
    @Test
    void conflictingRuntimeRequirementShapesAreRejectedBeforePackaging() {
        List<JsonObject> definitions = List.of(
            definition("itemstack.component", "any", "value"),
            definition("itemstack.attributes", "item_component_list", "modifiers")
        );
        List<String> errors = new ArrayList<>();

        NodeDefinitionBuildValidator.validateRuntimeRequirementKeys(definitions, errors);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("restudio.resync/generic-itemstack#restudio.resync/item_component"));
        assertTrue(errors.getFirst().contains("itemstack.component"));
        assertTrue(errors.getFirst().contains("itemstack.attributes"));
    }

    @Test
    void identicalRuntimeRequirementShapesRemainShareable() {
        JsonObject first = definition("itemstack.component", "any", "value");
        JsonObject second = first.deepCopy();
        second.addProperty("id", "itemstack.component_alias");
        second.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("description", "Different UI description");
        List<String> errors = new ArrayList<>();

        NodeDefinitionBuildValidator.validateRuntimeRequirementKeys(List.of(first, second), errors);

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void conflictingOptionSourceValueTypesAreRejectedBeforePackaging() {
        JsonObject typed = optionDefinition("player.enchant", "enchantment", null);
        JsonObject string = optionDefinition("item.enchant", "string", "server:minecraft:enchantment");
        List<String> errors = new ArrayList<>();

        NodeDefinitionBuildValidator.validateOptionSourceTypes(List.of(typed, string), errors);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("server:minecraft:enchantment"));
        assertTrue(errors.getFirst().contains("player.enchant.enchantment (enchantment)"));
        assertTrue(errors.getFirst().contains("item.enchant.enchantment (string)"));
    }

    @Test
    void inferredAndExplicitOptionSourcesWithTheSameTypeRemainShareable() {
        JsonObject inferred = optionDefinition("player.enchant", "enchantment", null);
        JsonObject explicit = optionDefinition("item.enchant", "enchantment", "server:minecraft:enchantment");
        List<String> errors = new ArrayList<>();

        NodeDefinitionBuildValidator.validateOptionSourceTypes(List.of(inferred, explicit), errors);

        assertTrue(errors.isEmpty(), errors.toString());
    }

    private JsonObject definition(String id, String dataType, String pinId) {
        return JsonParser.parseString("""
            {
              "id": "%s",
              "owner": "restudio.resync",
              "handlerCapability": "generic-itemstack",
              "kind": "ACTION",
              "handler": "InventoryActionHandler",
              "handlerConfig": {"operation": "item_component"},
              "inputs": [{"id": "%s", "dataType": "%s"}],
              "outputs": []
            }
            """.formatted(id, pinId, dataType)).getAsJsonObject();
    }

    private JsonObject optionDefinition(String id, String dataType, String source) {
        String sourceProperty = source == null ? "" : ", \"optionsSource\": \"" + source + "\"";
        return JsonParser.parseString("""
            {
              "id": "%s",
              "inputs": [{"id": "enchantment", "name": "enchantment", "dataType": "%s"%s}]
            }
            """.formatted(id, dataType, sourceProperty)).getAsJsonObject();
    }
}
