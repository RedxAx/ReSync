package restudio.resync.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncRuntimeResourceServiceTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void lootTableGeneratesWeightedValidEntryAndFixedAmount() {
        TestLootTableService service = new TestLootTableService(lootTable(true,
            entry("minecraft:diamond", 0, 1, 1),
            entry("minecraft:stone", 1, 3, 3)
        ));

        List<ItemStack> items = service.generate("starter");

        assertEquals(1, items.size());
        assertEquals(Material.STONE, items.getFirst().getType());
        assertEquals(3, items.getFirst().getAmount());
    }

    @Test
    void lootTableSkipsDisabledAndInvalidMaterialResources() {
        TestLootTableService disabled = new TestLootTableService(lootTable(false, entry("minecraft:stone", 1, 1, 1)));
        TestLootTableService invalid = new TestLootTableService(lootTable(true, entry("minecraft:not_real", 1, 1, 1)));

        assertTrue(disabled.generate("starter").isEmpty());
        assertTrue(invalid.generate("starter").isEmpty());
    }

    @Test
    void lootTableAppliesComponentsAndEntryConditions() {
        JsonObject entry = entry("minecraft:stone", 1, 2, 2);
        JsonObject components = new JsonObject();
        components.addProperty("displayName", "Starter Stone");
        components.addProperty("customModelData", 77);
        entry.add("components", components);
        JsonObject conditions = new JsonObject();
        conditions.addProperty("enabled", true);
        entry.add("conditions", conditions);
        TestLootTableService service = new TestLootTableService(lootTable(true, entry));

        ItemStack item = service.generate("starter").getFirst();

        assertEquals("Starter Stone", item.getItemMeta().getDisplayName());
        assertEquals(77, item.getItemMeta().getCustomModelData());
    }

    @Test
    void villageProfileConvertsOffersIntoMerchantRecipes() {
        TestVillageProfileService service = new TestVillageProfileService(villageProfile());

        List<MerchantRecipe> recipes = service.recipes("librarian");

        assertEquals(1, recipes.size());
        assertEquals(Material.BOOK, recipes.getFirst().getResult().getType());
        assertEquals(2, recipes.getFirst().getResult().getAmount());
        assertEquals(Material.EMERALD, recipes.getFirst().getIngredients().getFirst().getType());
        assertEquals(4, recipes.getFirst().getIngredients().getFirst().getAmount());
    }

    @Test
    void npcHookDispatchCarriesCompactBindingPayload() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TestNpcService service = new TestNpcService(npcDefinition("npc-interact"), dispatcher);

        service.dispatch("guide", "interactFlow", null, null, null, null);

        assertEquals("npc-interact", dispatcher.flowId);
        assertEquals("guide", dispatcher.variables.get("npcId"));
        assertTrue(dispatcher.variables.containsKey("player"));
        assertTrue(dispatcher.variables.containsKey("entity"));
        assertTrue(dispatcher.variables.containsKey("location"));
    }

    private static JsonObject lootTable(boolean enabled, JsonObject... entries) {
        JsonObject table = new JsonObject();
        table.addProperty("id", "starter");
        table.addProperty("enabled", enabled);
        JsonArray pools = new JsonArray();
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        JsonArray entryArray = new JsonArray();
        for (JsonObject entry : entries) {
            entryArray.add(entry);
        }
        pool.add("entries", entryArray);
        pools.add(pool);
        table.add("pools", pools);
        return table;
    }

    private static JsonObject entry(String item, int weight, int minAmount, int maxAmount) {
        JsonObject entry = new JsonObject();
        entry.addProperty("item", item);
        entry.addProperty("weight", weight);
        entry.addProperty("chance", 100);
        entry.addProperty("minAmount", minAmount);
        entry.addProperty("maxAmount", maxAmount);
        return entry;
    }

    private static JsonObject villageProfile() {
        JsonObject profile = new JsonObject();
        profile.addProperty("id", "librarian");
        profile.addProperty("enabled", true);
        JsonArray offers = new JsonArray();
        JsonObject offer = new JsonObject();
        offer.addProperty("cost", "minecraft:emerald");
        offer.addProperty("costAmount", 4);
        offer.addProperty("result", "minecraft:book");
        offer.addProperty("resultAmount", 2);
        offers.add(offer);
        profile.add("offers", offers);
        return profile;
    }

    private static JsonObject npcDefinition(String interactFlow) {
        JsonObject definition = new JsonObject();
        JsonObject hooks = new JsonObject();
        hooks.addProperty("interactFlow", interactFlow);
        definition.add("hooks", hooks);
        return definition;
    }

    private static class TestLootTableService extends LootTableService {
        private final JsonObject table;

        private TestLootTableService(JsonObject table) {
            super(null, null);
            this.table = table;
        }

        @Override
        public JsonObject get(String id) {
            return table;
        }
    }

    private static class TestVillageProfileService extends VillageProfileService {
        private final JsonObject profile;

        private TestVillageProfileService(JsonObject profile) {
            super(null, null);
            this.profile = profile;
        }

        @Override
        public JsonObject get(String id) {
            return profile;
        }
    }

    private static class TestNpcService extends NpcService {
        private final JsonObject definition;

        private TestNpcService(JsonObject definition, RuntimeFlowDispatcher dispatcher) {
            super(null, null, null, dispatcher, null, null, null, NamespacedKey.minecraft("resync_npc_id"));
            this.definition = definition;
        }

        @Override
        public JsonObject get(String id) {
            return definition;
        }
    }

    private static class RecordingDispatcher extends RuntimeFlowDispatcher {
        private String flowId;
        private Map<String, Object> variables;

        private RecordingDispatcher() {
            super(null, null);
        }

        @Override
        public boolean dispatch(String flowId, Player player, Event event, Map<String, Object> variables) {
            this.flowId = flowId;
            this.variables = variables;
            return true;
        }
    }
}
