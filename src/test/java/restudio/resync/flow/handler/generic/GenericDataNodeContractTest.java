package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.ActiveNodeDefinitionSource;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.registry.NodeDefinitionLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericDataNodeContractTest {
    @Test
    void entityDataSupportsAttributesExplosivesAndSpawnData() throws Exception {
        String handler = Files.readString(Path.of("src/main/java/restudio/resync/flow/handler/generic/EntityDataAccess.java"));
        String operations = Files.readString(Path.of("src/main/java/restudio/resync/flow/handler/generic/EntityActionHandler.java"));
        String nodes = ActiveNodeDefinitionSource.read("entity.json");
        String catalogs = Files.readString(Path.of("src/main/java/restudio/resync/modules/flow/BuiltinOptionCatalogService.java"));

        assertTrue(handler.contains("property.startsWith(\"attribute:\")"));
        assertTrue(handler.contains("case \"fuse_ticks\""));
        assertTrue(handler.contains("case \"yield\""));
        assertTrue(handler.contains("case \"incendiary\""));
        assertTrue(operations.contains("operations.put(\"entity_data\""));
        assertTrue(operations.contains("operations.put(\"entity_typed_data\""));
        assertTrue(operations.contains("operations.put(\"entity_data_entry\""));
        assertTrue(operations.contains("operations.put(\"entity_apply_data\""));
        assertTrue(operations.contains("EntityDataAccess.apply(ctx, entity, ctx.getInputValue(node, \"data\""));
        assertTrue(nodes.contains("\"id\": \"entity.entity_data\""));
        assertTrue(nodes.contains("\"id\": \"entity.entity_data_entry\""));
        assertFalse(nodes.contains("\"name\": \"data_type\""));
        assertTrue(nodes.contains("\"id\": \"entity.entity_apply_data\""));
        assertTrue(nodes.contains("\"id\": \"entity.entity_number_data\""));
        assertTrue(nodes.contains("\"dataType\": \"entity_data\""));
        assertTrue(nodes.contains("\"optionsSource\": \"server:minecraft:entity_data_property\""));
        assertTrue(catalogs.contains("catalog(\"entity_data_property\", true)"));
        assertTrue(catalogs.contains("catalog(\"entity_writable_data_property\", true)"));
        assertTrue(catalogs.contains("registryKeysByField(\"ATTRIBUTE\").stream().map(value -> \"attribute:\" + value)"));
    }

    @Test
    void itemComponentsExposeTypedFlowValuesAndAttributeBuilders() throws Exception {
        String handler = Files.readString(Path.of("src/main/java/restudio/resync/flow/handler/generic/InventoryActionHandler.java"));
        String nodes = Files.readString(Path.of("src/main/resources/nodes/itemstack.json"));

        assertTrue(handler.contains("operations.put(\"item_get_components\""));
        assertTrue(handler.contains("operations.put(\"item_get_component\""));
        assertTrue(handler.contains("operations.put(\"item_set_component\""));
        assertTrue(handler.contains("operations.put(\"item_remove_component\""));
        assertTrue(handler.contains("operations.put(\"item_apply_components\""));
        assertTrue(handler.contains("operations.put(\"item_typed_component\""));
        assertTrue(handler.contains("operations.put(\"item_component\""));
        assertTrue(handler.contains("operations.put(\"item_attribute_modifier\""));
        assertTrue(handler.contains("operations.put(\"item_component_object_field\""));
        assertTrue(handler.contains("operations.put(\"item_component_list_entry\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.component\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.component_field\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.component_list_entry\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.attributes\""));
        assertTrue(nodes.contains("\"itemstack.item_set_component\""));
        assertTrue(nodes.contains("\"optionsSource\": \"server:minecraft:item_attribute_schema\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.item_number_component\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.item_attribute_modifier\""));
        assertTrue(nodes.contains("\"dataType\": \"item_components\""));
        assertFalse(nodes.contains("minecraft:generic.attack_damage"));
    }

    @Test
    void itemCreationUsesTheCompleteServerItemCatalog() throws Exception {
        String handler = Files.readString(Path.of("src/main/java/restudio/resync/flow/handler/generic/InventoryActionHandler.java"));
        String nodes = Files.readString(Path.of("src/main/resources/nodes/itemstack.json"));
        String legacyNodes = Files.readString(Path.of("src/main/resources/nodes/inventory.json"));

        assertTrue(handler.contains("operations.put(\"item_create_reference\""));
        assertTrue(handler.contains("customContent.createReferencedItem(reference, 1)"));
        assertTrue(nodes.contains("\"id\": \"itemstack.create_item\""));
        assertTrue(nodes.contains("\"optionsSource\": \"server:custom_content:recipe_item\""));
        assertTrue(legacyNodes.contains("\"id\": \"inventory.item_create\""));
        assertTrue(legacyNodes.contains("\"hidden\": true"));
    }

    @Test
    void productionItemCatalogAdmitsConsolidatedNodesAndRetainsHiddenCompatibilityNodes() throws Exception {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(Path.of("src/main/resources/nodes/itemstack.json"))) {
            definitions = loader.parseReplacement(input, "nodes/itemstack.json");
        }

        List<NodeDefinitionDiagnostic> errors = loader.getDiagnostics().stream()
            .filter(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR)
            .toList();
        assertTrue(errors.isEmpty(), errors.toString());
        assertFalse(definition(definitions, "itemstack.create_item").isHidden());
        assertFalse(definition(definitions, "itemstack.component").isHidden());
        NodeDefinition componentBuilder = definition(definitions, "itemstack.apply_component_builder");
        assertFalse(componentBuilder.isHidden());
        assertEquals("component_builder_id", componentBuilder.getInputs().stream()
            .filter(pin -> "builder".equals(pin.getName())).findFirst().orElseThrow().getDataType().getId());
        assertEquals("server:resync:component_builder", componentBuilder.getInputs().stream()
            .filter(pin -> "builder".equals(pin.getName())).findFirst().orElseThrow().getOptionsSource());
        assertFalse(definition(definitions, "itemstack.component_field").isHidden());
        assertFalse(definition(definitions, "itemstack.component_list_entry").isHidden());
        assertFalse(definition(definitions, "itemstack.item_components").isHidden());
        assertTrue(definition(definitions, "itemstack.item_apply_components").isHidden());
        assertTrue(definition(definitions, "itemstack.item_components").getDisplayName().equals("Get Item Components"));
        assertTrue(definition(definitions, "itemstack.item_apply_components").getDisplayName().equals("Apply Item Components"));
        assertTrue(definition(definitions, "itemstack.attributes").getDisplayName().equals("Item Attribute Modifiers"));
        assertTrue(definition(definitions, "itemstack.actions").isHidden());
        assertTrue(definition(definitions, "itemstack.attributes").isHidden());
        assertTrue(definition(definitions, "itemstack.item_attribute_modifier").isHidden());
        assertTrue(definition(definitions, "itemstack.item_attribute_modifier_list").isHidden());
        assertTrue(definition(definitions, "itemstack.item_number_component").isHidden());
        assertTrue(definition(definitions, "itemstack.item_remove_component").isHidden());
    }

    @Test
    void directItemSettersRemainVisibleAndEnchantmentsUseTheAuthoritativeCatalog() throws Exception {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(Path.of("src/main/resources/nodes/inventory.json"))) {
            definitions = loader.parseReplacement(input, "nodes/inventory.json");
        }

        assertFalse(definition(definitions, "inventory.item_set_custom_name").isHidden());
        assertFalse(definition(definitions, "inventory.item_set_lore").isHidden());
        assertFalse(definition(definitions, "inventory.item_set_unbreakable").isHidden());
        NodeDefinition addEnchantment = definition(definitions, "inventory.item_add_enchant");
        assertFalse(addEnchantment.isHidden());
        assertTrue(addEnchantment.getInputs().stream().anyMatch(pin -> pin.getId().canonicalText().equals("enchantment")
            && "enchantment".equals(pin.getTypeRef().getTypeId())
            && "server:minecraft:enchantment".equals(pin.getOptionsSource())));
    }

    private static NodeDefinition definition(List<NodeDefinition> definitions, String id) {
        return definitions.stream().filter(definition -> id.equals(definition.getId())).findFirst().orElseThrow();
    }
}
