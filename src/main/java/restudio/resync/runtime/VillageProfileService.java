package restudio.resync.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VillageProfileService implements Listener {
    private final ReSyncJsonResourceStorage storage;
    private final CustomContentService customContentService;
    private final RuntimeFlowDispatcher dispatcher;
    private final Map<UUID, String> openedProfiles = new ConcurrentHashMap<>();

    public VillageProfileService(ReSyncJsonResourceStorage storage, CustomContentService customContentService) {
        this(storage, customContentService, null);
    }

    public VillageProfileService(ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher) {
        this.storage = storage;
        this.customContentService = customContentService;
        this.dispatcher = dispatcher;
    }

    public JsonObject get(String id) {
        return storage != null ? storage.get(ReSyncResourceCatalog.VILLAGE_PROFILE, id) : null;
    }

    public List<MerchantRecipe> recipes(String id) {
        JsonObject profile = get(id);
        if (profile == null || !bool(profile, "enabled", true)) {
            return List.of();
        }
        List<MerchantRecipe> recipes = new ArrayList<>();
        JsonArray offers = profile.has("offers") && profile.get("offers").isJsonArray() ? profile.getAsJsonArray("offers") : new JsonArray();
        for (JsonElement element : offers) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            MerchantRecipe recipe = recipe(profile, element.getAsJsonObject());
            if (recipe != null) {
                recipes.add(recipe);
            }
        }
        return List.copyOf(recipes);
    }

    public boolean apply(Villager villager, String id) {
        if (villager == null) {
            return false;
        }
        JsonObject profile = get(id);
        if (profile == null || !bool(profile, "enabled", true)) {
            dispatch(profile, "deniedFlow", null, villager, id, null);
            return false;
        }
        applyProfileData(villager, profile);
        villager.setRecipes(recipes(id));
        return true;
    }

    public boolean openTrades(Player player, Villager villager, String id) {
        if (player == null || villager == null || !apply(villager, id)) {
            dispatch(get(id), "deniedFlow", player, villager, id, null);
            return false;
        }
        openedProfiles.put(player.getUniqueId(), id);
        player.openMerchant(villager, true);
        dispatch(get(id), "openFlow", player, villager, id, null);
        return true;
    }

    @EventHandler
    public void onTradeResultClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getView().getType() != InventoryType.MERCHANT || event.getRawSlot() != 2) {
            return;
        }
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }
        String id = openedProfiles.get(player.getUniqueId());
        if (id == null || id.isBlank()) {
            return;
        }
        dispatch(get(id), "completeFlow", player, null, id, event);
    }

    @EventHandler
    public void onTradeClose(InventoryCloseEvent event) {
        if (event.getView().getType() == InventoryType.MERCHANT && event.getPlayer() instanceof Player player) {
            openedProfiles.remove(player.getUniqueId());
        }
    }

    private MerchantRecipe recipe(JsonObject profile, JsonObject offer) {
        ItemStack result = item(text(offer, "result"), integer(offer, "resultAmount", 1));
        ItemStack cost = item(text(offer, "cost"), integer(offer, "costAmount", 1));
        if (result == null || cost == null) {
            return null;
        }
        MerchantRecipe recipe = new MerchantRecipe(result, Math.max(1, integer(offer, "maxUses", integer(profile, "maxUses", 12))));
        List<ItemStack> ingredients = new ArrayList<>();
        ingredients.add(cost);
        ItemStack cost2 = item(text(offer, "cost2"), integer(offer, "cost2Amount", 1));
        if (cost2 != null) {
            ingredients.add(cost2);
        }
        recipe.setIngredients(ingredients);
        return recipe;
    }

    private void applyProfileData(Villager villager, JsonObject profile) {
        try {
            villager.setProfession(Villager.Profession.valueOf(text(profile, "profession").toUpperCase(Locale.ROOT)));
        } catch (Exception ignored) {
        }
        try {
            villager.setVillagerType(Villager.Type.valueOf(text(profile, "villagerType").toUpperCase(Locale.ROOT)));
        } catch (Exception ignored) {
        }
        try {
            villager.setVillagerLevel(Math.max(1, Math.min(5, integer(profile, "level", 1))));
        } catch (Exception ignored) {
        }
    }

    private ItemStack item(String reference, int amount) {
        return createReferencedItem(reference, amount);
    }

    protected ItemStack createReferencedItem(String reference, int amount) {
        ItemStack stack = customContentService != null ? customContentService.createReferencedItem(reference, amount) : null;
        if (stack != null) {
            return stack;
        }
        Material material = Material.matchMaterial(reference);
        return material != null && material.isItem() && !material.isAir() ? new ItemStack(material, Math.max(1, amount)) : null;
    }

    private void dispatch(JsonObject profile, String hook, Player player, Villager villager, String profileId, Event event) {
        String flowId = profile != null && profile.has("hooks") && profile.get("hooks").isJsonObject()
            ? text(profile.getAsJsonObject("hooks"), hook)
            : "";
        if (flowId.isBlank() || dispatcher == null) {
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("player", player);
        variables.put("entity", villager);
        variables.put("profileId", profileId);
        variables.put("trade", null);
        variables.put("result", hook);
        dispatcher.dispatch(flowId, player, event, variables);
    }

    private String text(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private int integer(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
