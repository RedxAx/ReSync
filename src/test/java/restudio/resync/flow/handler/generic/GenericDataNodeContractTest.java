package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericDataNodeContractTest {
    @Test
    void entityDataSupportsAttributesExplosivesAndSpawnData() throws Exception {
        String handler = Files.readString(Path.of("src/main/java/restudio/resync/flow/handler/generic/EntityDataAccess.java"));
        String operations = Files.readString(Path.of("src/main/java/restudio/resync/flow/handler/generic/EntityActionHandler.java"));
        String nodes = Files.readString(Path.of("src/main/resources/nodes/migrated/entity.json"));
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
        assertTrue(nodes.contains("\"id\": \"entity.entity_apply_data\""));
        assertTrue(nodes.contains("\"id\": \"entity.entity_number_data\""));
        assertTrue(nodes.contains("\"dataType\": \"entity_data\""));
        assertTrue(nodes.contains("\"optionsSource\": \"server:minecraft:entity_data_property\""));
        assertTrue(catalogs.contains("catalog(\"entity_data_property\", true)"));
        assertTrue(catalogs.contains("registryKeysByField(\"ATTRIBUTE\").stream().map(value -> \"attribute:\" + value)"));
    }

    @Test
    void itemComponentsExposeTypedFlowValuesAndAttributeBuilders() throws Exception {
        String handler = Files.readString(Path.of("src/main/java/restudio/resync/flow/handler/generic/InventoryActionHandler.java"));
        String nodes = Files.readString(Path.of("src/main/resources/nodes/migrated/itemstack.json"));

        assertTrue(handler.contains("operations.put(\"item_get_components\""));
        assertTrue(handler.contains("operations.put(\"item_get_component\""));
        assertTrue(handler.contains("operations.put(\"item_set_component\""));
        assertTrue(handler.contains("operations.put(\"item_remove_component\""));
        assertTrue(handler.contains("operations.put(\"item_apply_components\""));
        assertTrue(handler.contains("operations.put(\"item_typed_component\""));
        assertTrue(handler.contains("operations.put(\"item_attribute_modifier\""));
        assertTrue(handler.contains("operations.put(\"item_component_object_field\""));
        assertTrue(handler.contains("operations.put(\"item_component_list_entry\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.item_component\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.item_set_component\""));
        assertTrue(nodes.contains("\"optionsSource\": \"server:minecraft:item_attribute_schema\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.item_number_component\""));
        assertTrue(nodes.contains("\"id\": \"itemstack.item_attribute_modifier\""));
        assertTrue(nodes.contains("\"dataType\": \"item_components\""));
        assertFalse(nodes.contains("minecraft:generic.attack_damage"));
    }
}
