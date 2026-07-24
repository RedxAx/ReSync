package restudio.resync.flow.contract;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowContractTest {
    @Test
    void customContentTriggersAreCanonicalForEveryRuntime() {
        assertEquals("item.while_holding", CustomContentContract.triggerForPin("item", "while_holding"));
        assertEquals("while_holding", CustomContentContract.pinForTrigger("armor.while_holding"));
        assertEquals(CustomContentContract.PROJECTILE_NODE, CustomContentContract.nodeType("projectile"));
    }

    @Test
    void nodeCategoryCatalogContainsClientAndServerCapabilities() {
        Set<String> ids = FlowNodeCategoryContract.categories().stream().map(FlowNodeCategoryContract.Category::id).collect(Collectors.toSet());
        assertTrue(ids.containsAll(Set.of("player", "chat", "trade", "npc", "loot", "world_gen")));
        assertEquals(0xFF4F8CFF, FlowNodeCategoryContract.category("network").color());
    }
}
