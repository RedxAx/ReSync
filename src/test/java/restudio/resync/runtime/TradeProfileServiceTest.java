package restudio.resync.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeProfileServiceTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void convertsOffersIntoMerchantRecipes() {
        TestTradeProfileService service = new TestTradeProfileService("""
            {
              "enabled": true,
              "maxUses": 8,
              "offers": [
                {
                  "cost": "minecraft:emerald",
                  "costAmount": 2,
                  "cost2": "minecraft:book",
                  "cost2Amount": 1,
                  "result": "minecraft:diamond",
                  "resultAmount": 3
                }
              ]
            }
            """);

        List<MerchantRecipe> recipes = service.recipes("profile");

        assertEquals(1, recipes.size());
        MerchantRecipe recipe = recipes.getFirst();
        assertEquals(Material.DIAMOND, recipe.getResult().getType());
        assertEquals(3, recipe.getResult().getAmount());
        assertEquals(8, recipe.getMaxUses());
        assertEquals(2, recipe.getIngredients().size());
        assertEquals(Material.EMERALD, recipe.getIngredients().getFirst().getType());
        assertEquals(2, recipe.getIngredients().getFirst().getAmount());
        assertEquals(Material.BOOK, recipe.getIngredients().get(1).getType());
    }

    @Test
    void disabledProfilesReturnNoRecipes() {
        TestTradeProfileService service = new TestTradeProfileService("""
            {
              "enabled": false,
              "offers": [
                { "cost": "minecraft:emerald", "result": "minecraft:diamond" }
              ]
            }
            """);

        assertTrue(service.recipes("profile").isEmpty());
    }

    @Test
    void invalidOfferItemsAreSkipped() {
        TestTradeProfileService service = new TestTradeProfileService("""
            {
              "enabled": true,
              "offers": [
                { "cost": "minecraft:emerald", "result": "minecraft:not_real" }
              ]
            }
            """);

        assertTrue(service.recipes("profile").isEmpty());
    }

    @Test
    void replacementRuntimeDoesNotDispatchLegacyHookFallback() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TestTradeProfileService service = new TestTradeProfileService("""
            {
              "enabled": true,
              "hooks": { "openFlow": "legacy-open" }
            }
            """, dispatcher, LegacyRuntimeActivationGate.runtime(Path.of("build", "trade-runtime-gate-test")));
        Player player = MockBukkit.getMock().addPlayer();

        assertTrue(service.openVirtualTrades(player, "profile"));
        assertTrue(dispatcher.flowIds.isEmpty());
    }

    @Test
    void rejectedTradeAdmissionLeavesBothPaymentSlotsUntouched() throws Exception {
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of());
        admission.quiesce();
        var installation = PaperPlayerDataMutationAdmission.installShared(admission);
        try {
            TestTradeProfileService service = new TestTradeProfileService("""
                {"enabled":true,"offers":[{"cost":"minecraft:emerald","costAmount":2,
                "cost2":"minecraft:book","cost2Amount":1,"result":"minecraft:diamond"}]}
                """);
            MockBukkit.getMock().addSimpleWorld("trade");
            Player player = MockBukkit.getMock().addPlayer();
            assertTrue(service.openVirtualTrades(player, "profile"));
            var view = player.getOpenInventory();
            var inventory = view.getTopInventory();
            inventory.setItem(0, new ItemStack(Material.EMERALD, 2));
            inventory.setItem(1, new ItemStack(Material.BOOK, 1));
            inventory.setItem(2, null);
            InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.RESULT, 2,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
            assertThrows(IllegalStateException.class, () -> service.onTradeResultClick(event));
            assertEquals(2, inventory.getItem(0).getAmount());
            assertEquals(1, inventory.getItem(1).getAmount());
            assertTrue(event.isCancelled());
        } finally {
            PaperPlayerDataMutationAdmission.clearSharedInstallation(installation);
        }
    }

    private static class TestTradeProfileService extends TradeProfileService {
        private final JsonObject profile;

        TestTradeProfileService(String json) {
            super(null, null);
            this.profile = JsonParser.parseString(json).getAsJsonObject();
        }

        TestTradeProfileService(String json, RuntimeFlowDispatcher dispatcher, LegacyRuntimeActivationGate gate) {
            super(null, null, dispatcher, null, gate);
            this.profile = JsonParser.parseString(json).getAsJsonObject();
        }

        @Override
        public JsonObject get(String id) {
            return profile;
        }

    }

    private static final class RecordingDispatcher extends RuntimeFlowDispatcher {
        private final List<String> flowIds = new ArrayList<>();

        private RecordingDispatcher() {
            super(null, null);
        }

        @Override
        public boolean dispatch(String flowId, Player player, Event event, Map<String, Object> variables) {
            flowIds.add(flowId);
            return true;
        }
    }
}
