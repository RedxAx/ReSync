package restudio.resync.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    void tradeProfileConvertsOffersIntoMerchantRecipes() {
        TestTradeProfileService service = new TestTradeProfileService(tradeProfile());

        List<MerchantRecipe> recipes = service.recipes("librarian");

        assertEquals(1, recipes.size());
        assertEquals(Material.BOOK, recipes.getFirst().getResult().getType());
        assertEquals(2, recipes.getFirst().getResult().getAmount());
        assertEquals(Material.EMERALD, recipes.getFirst().getIngredients().getFirst().getType());
        assertEquals(4, recipes.getFirst().getIngredients().getFirst().getAmount());
    }

    @Test
    void tradeProfileReloadRefreshesOpenAndAppliedRuntimeState() {
        JsonObject profile = tradeProfile();
        UUID villagerId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicInteger recipeWrites = new AtomicInteger();
        AtomicInteger merchantOpens = new AtomicInteger();
        AtomicInteger inventoryCloses = new AtomicInteger();
        AtomicBoolean recipesCleared = new AtomicBoolean();
        Villager villager = proxy(Villager.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> villagerId;
            case "isDead" -> false;
            case "setRecipes" -> {
                List<?> recipes = (List<?>) arguments[0];
                recipeWrites.incrementAndGet();
                recipesCleared.set(recipes.isEmpty());
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        Player player = proxy(Player.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "isOnline" -> true;
            case "openMerchant" -> {
                merchantOpens.incrementAndGet();
                yield null;
            }
            case "closeInventory" -> {
                inventoryCloses.incrementAndGet();
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        ReloadableTradeProfileService service = new ReloadableTradeProfileService(profile, player, villager);
        assertTrue(service.apply(villager, "librarian"));
        assertTrue(service.openTrades(player, villager, "librarian"));
        recipeWrites.set(0);
        merchantOpens.set(0);

        service.reload("librarian", false);

        assertTrue(recipeWrites.get() > 0);
        assertTrue(merchantOpens.get() > 0);
        service.reload("librarian", true);
        assertTrue(recipesCleared.get());
        assertEquals(1, inventoryCloses.get());
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

    private static JsonObject tradeProfile() {
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

    private static class TestTradeProfileService extends TradeProfileService {
        private final JsonObject profile;

        private TestTradeProfileService(JsonObject profile) {
            super(null, null);
            this.profile = profile;
        }

        @Override
        public JsonObject get(String id) {
            return profile;
        }
    }

    private static class ReloadableTradeProfileService extends TradeProfileService {
        private final JsonObject profile;
        private final Map<UUID, Player> players = new HashMap<>();
        private final Map<UUID, Entity> entities = new HashMap<>();

        private ReloadableTradeProfileService(JsonObject profile, Player player, Villager villager) {
            super(null, null);
            this.profile = profile;
            players.put(player.getUniqueId(), player);
            entities.put(villager.getUniqueId(), villager);
        }

        @Override
        public JsonObject get(String id) {
            return profile;
        }

        @Override
        protected Player resolvePlayer(UUID id) {
            return players.get(id);
        }

        @Override
        protected Entity resolveEntity(UUID id) {
            return entities.get(id);
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

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0;
        return null;
    }
}
