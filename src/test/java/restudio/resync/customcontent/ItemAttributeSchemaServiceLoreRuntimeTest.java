package restudio.resync.customcontent;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.ItemWriteback;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemAttributeSchemaServiceLoreRuntimeTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void appliesPlainLoreLinesThroughTheTypedPaperComponentApi() {
        ItemAttributeSchemaService service = new ItemAttributeSchemaService();

        ItemStack result = service.applyComponents(new ItemStack(Material.STICK),
            Map.of("minecraft:lore", List.of("First Line", "Second Line")));

        ItemLore lore = result.getData(DataComponentTypes.LORE);
        assertEquals(List.of("First Line", "Second Line"), lore.lines().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize).toList());
    }

    @Test
    void writesTheChangedItemBackToTheInvokingPlayersHand() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        ItemStack original = player.getInventory().getItemInMainHand();
        ItemStack changed = new ItemAttributeSchemaService().applyComponents(original,
            Map.of("minecraft:lore", List.of("Changed")));
        changed.setAmount(7);
        FlowNode playerProperties = new FlowNode("player.properties", 0, 0, Map.of());
        FlowNode apply = new FlowNode("itemstack.apply_component_builder", 0, 0, Map.of());
        FlowGraph graph = new FlowGraph("component-writeback", Map.of("player", playerProperties, "apply", apply),
            List.of(new FlowConnection("player", "item_in_hand", "apply", "target")), List.of());
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(new NodeDefinition.Builder("player.properties", "Player Properties", NodeDefinition.NodeCategory.DATA)
            .handler("player")
            .build());
        definitions.register(new NodeDefinition.Builder("itemstack.apply_component_builder", "Apply Component Builder",
            NodeDefinition.NodeCategory.ACTION).handler("InventoryActionHandler").build());
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of(), Map.of(), definitions);

        ItemWriteback.resolve(new FlowContext(runtime, player, null), apply, original)
            .accept(changed);

        assertEquals(7, player.getInventory().getItemInMainHand().getAmount());
    }
}
