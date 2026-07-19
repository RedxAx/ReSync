package restudio.resync.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.java.JavaPlugin;
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

public class TradeProfileService implements Listener {
    private final ReSyncJsonResourceStorage storage;
    private final CustomContentService customContentService;
    private final RuntimeFlowDispatcher dispatcher;
    private final JavaPlugin plugin;
    private final Map<UUID, TradeSession> openedProfiles = new ConcurrentHashMap<>();
    private final Map<UUID, String> appliedProfiles = new ConcurrentHashMap<>();

    public TradeProfileService(ReSyncJsonResourceStorage storage, CustomContentService customContentService) {
        this(storage, customContentService, null, null);
    }

    public TradeProfileService(ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher) {
        this(storage, customContentService, dispatcher, null);
    }

    public TradeProfileService(ReSyncJsonResourceStorage storage, CustomContentService customContentService, RuntimeFlowDispatcher dispatcher, JavaPlugin plugin) {
        this.storage = storage;
        this.customContentService = customContentService;
        this.dispatcher = dispatcher;
        this.plugin = plugin;
    }

    public JsonObject get(String id) {
        return storage != null ? storage.get(ReSyncResourceCatalog.TRADE_PROFILE, id) : null;
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
            dispatch(profile, "deniedAction", null, villager, id, null, Map.of("success", false));
            return false;
        }
        applyProfileData(villager, profile);
        villager.setRecipes(recipes(id));
        applyProfileData(villager, profile);
        appliedProfiles.put(villager.getUniqueId(), id);
        return true;
    }

    public Villager spawn(Location location, String id) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        JsonObject profile = get(id);
        if (profile == null || !bool(profile, "enabled", true)) {
            return null;
        }
        Villager villager = location.getWorld().spawn(location, Villager.class, spawned -> {
            spawned.setAdult();
            applyProfileData(spawned, profile);
        });
        if (!apply(villager, id)) {
            return null;
        }
        if (plugin != null && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!villager.isDead()) {
                    apply(villager, id);
                }
            });
        }
        return villager;
    }

    public boolean openTrades(Player player, Entity entity, String id) {
        if (entity instanceof Villager villager) {
            return openTrades(player, villager, id);
        }
        return openVirtualTrades(player, entity, id);
    }

    public boolean openTrades(Player player, Villager villager, String id) {
        if (player == null || villager == null || !apply(villager, id)) {
            dispatch(get(id), "deniedAction", player, villager, id, null, Map.of("success", false));
            return false;
        }
        player.openMerchant(villager, true);
        openedProfiles.put(player.getUniqueId(), new TradeSession(id, villager.getUniqueId(), false));
        dispatch(get(id), "openAction", player, villager, id, null, Map.of("success", true));
        return true;
    }

    public boolean openVirtualTrades(Player player, String id) {
        return openVirtualTrades(player, null, id);
    }

    public boolean openVirtualTrades(Player player, Entity entity, String id) {
        JsonObject profile = get(id);
        if (player == null || profile == null || !bool(profile, "enabled", true)) {
            dispatch(profile, "deniedAction", player, entity, id, null, Map.of("success", false));
            return false;
        }
        openVirtualMerchant(player, id, profile);
        openedProfiles.put(player.getUniqueId(), new TradeSession(id, entity != null ? entity.getUniqueId() : null, true));
        dispatch(profile, "openAction", player, entity, id, null, Map.of("success", true));
        return true;
    }

    public void reload(String id, boolean deleted) {
        if (id == null || id.isBlank()) {
            return;
        }
        JsonObject profile = !deleted ? get(id) : null;
        boolean unavailable = deleted || profile == null || !bool(profile, "enabled", true);
        for (Map.Entry<UUID, TradeSession> entry : new ArrayList<>(openedProfiles.entrySet())) {
            TradeSession session = entry.getValue();
            if (!id.equals(session.profileId())) {
                continue;
            }
            Player player = resolvePlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                openedProfiles.remove(entry.getKey());
                continue;
            }
            if (unavailable) {
                openedProfiles.remove(entry.getKey());
                player.closeInventory();
                continue;
            }
            refreshTradeSession(player, session);
        }
        for (Map.Entry<UUID, String> entry : new ArrayList<>(appliedProfiles.entrySet())) {
            if (!id.equals(entry.getValue())) {
                continue;
            }
            if (!(resolveEntity(entry.getKey()) instanceof Villager villager) || villager.isDead()) {
                appliedProfiles.remove(entry.getKey());
                continue;
            }
            if (unavailable) {
                villager.setRecipes(List.of());
                appliedProfiles.remove(entry.getKey());
            } else {
                apply(villager, id);
            }
        }
    }

    @EventHandler
    public void onTradeResultClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getView().getType() != InventoryType.MERCHANT || event.getRawSlot() != 2) {
            return;
        }
        TradeSession session = openedProfiles.get(player.getUniqueId());
        if (session == null || session.profileId().isBlank()) {
            return;
        }
        String id = session.profileId();
        JsonObject profile = get(id);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            if (!completeReferencedTrade(player, profile, id, event)) {
                dispatch(profile, "deniedAction", player, null, id, event, Map.of("success", false));
            }
            return;
        }
        ItemStack tradedItem = event.getCurrentItem();
        Map<String, Object> variables = new HashMap<>();
        variables.put("tradedItem", tradedItem);
        variables.put("resultItem", tradedItem);
        variables.put("event.item", tradedItem);
        variables.put("event.output", tradedItem);
        variables.put("success", true);
        dispatch(profile, "completeAction", player, null, id, event, variables);
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

    private void refreshTradeSession(Player player, TradeSession session) {
        JsonObject profile = get(session.profileId());
        if (profile == null || !bool(profile, "enabled", true)) {
            openedProfiles.remove(player.getUniqueId());
            player.closeInventory();
            return;
        }
        Entity entity = session.entityId() != null ? resolveEntity(session.entityId()) : null;
        if (!session.virtual() && entity instanceof Villager villager && apply(villager, session.profileId())) {
            player.openMerchant(villager, true);
            openedProfiles.put(player.getUniqueId(), session);
            return;
        }
        openVirtualMerchant(player, session.profileId(), profile);
        openedProfiles.put(player.getUniqueId(), new TradeSession(session.profileId(), session.entityId(), true));
    }

    private void openVirtualMerchant(Player player, String id, JsonObject profile) {
        Merchant merchant = Bukkit.createMerchant(text(profile, "displayName").isBlank() ? id : text(profile, "displayName"));
        merchant.setRecipes(recipes(id));
        player.openMerchant(merchant, true);
    }

    protected Player resolvePlayer(UUID id) {
        return Bukkit.getPlayer(id);
    }

    protected Entity resolveEntity(UUID id) {
        return Bukkit.getEntity(id);
    }

    private void applyProfileData(Villager villager, JsonObject profile) {
        String villagerType = text(profile, "villagerType");
        if (!villagerType.isBlank()) {
            try {
                villager.setVillagerType(Villager.Type.valueOf(enumName(villagerType)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown villager type: " + villagerType, exception);
            }
        }
        String profession = text(profile, "profession");
        if (!profession.isBlank()) {
            try {
                villager.setProfession(Villager.Profession.valueOf(enumName(profession)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown villager profession: " + profession, exception);
            }
        }
        int level = integer(profile, "level", 1);
        if (level < 1 || level > 5) throw new IllegalArgumentException("Villager level must be between 1 and 5");
        villager.setVillagerLevel(level);
    }

    private ItemStack item(String reference, int amount) {
        return createReferencedItem(reference, amount);
    }

    private boolean completeReferencedTrade(Player player, JsonObject profile, String id, InventoryClickEvent event) {
        if (player == null || profile == null || event == null) {
            return false;
        }
        Inventory inventory = event.getView().getTopInventory();
        ItemStack first = inventory.getItem(0);
        ItemStack second = inventory.getItem(1);
        JsonArray offers = profile.has("offers") && profile.get("offers").isJsonArray() ? profile.getAsJsonArray("offers") : new JsonArray();
        for (JsonElement element : offers) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject offer = element.getAsJsonObject();
            if (!bool(offer, "enabled", true) || !offerMatches(offer, first, second)) {
                continue;
            }
            ItemStack result = item(text(offer, "result"), integer(offer, "resultAmount", 1));
            if (result == null) {
                return false;
            }
            event.setCancelled(true);
            consume(inventory, 0, integer(offer, "costAmount", 1));
            if (!text(offer, "cost2").isBlank()) {
                consume(inventory, 1, integer(offer, "cost2Amount", 1));
            }
            giveTradeResult(player, event.getCursor(), result);
            Map<String, Object> variables = new HashMap<>();
            variables.put("tradedItem", result);
            variables.put("resultItem", result);
            variables.put("event.item", result);
            variables.put("event.output", result);
            variables.put("success", true);
            dispatch(profile, "completeAction", player, null, id, event, variables);
            return true;
        }
        return false;
    }

    private boolean offerMatches(JsonObject offer, ItemStack first, ItemStack second) {
        if (!itemMatches(first, text(offer, "cost"), integer(offer, "costAmount", 1))) {
            return false;
        }
        String cost2 = text(offer, "cost2");
        return cost2.isBlank() || itemMatches(second, cost2, integer(offer, "cost2Amount", 1));
    }

    private boolean itemMatches(ItemStack stack, String reference, int amount) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() < Math.max(1, amount) || reference == null || reference.isBlank()) {
            return false;
        }
        if (customContentService != null && customContentService.matchesItemReference(stack, reference)) {
            return true;
        }
        Material material = RuntimeMaterialResolver.itemMaterial(reference);
        return material != null && stack.getType() == material;
    }

    private void consume(Inventory inventory, int slot, int amount) {
        ItemStack stack = inventory.getItem(slot);
        if (stack == null) {
            return;
        }
        int next = stack.getAmount() - Math.max(1, amount);
        if (next <= 0) {
            inventory.setItem(slot, null);
        } else {
            stack.setAmount(next);
            inventory.setItem(slot, stack);
        }
    }

    private void giveTradeResult(Player player, ItemStack cursor, ItemStack result) {
        if (cursor == null || cursor.getType().isAir()) {
            player.setItemOnCursor(result);
            return;
        }
        if (cursor.isSimilar(result) && cursor.getAmount() + result.getAmount() <= cursor.getMaxStackSize()) {
            cursor.setAmount(cursor.getAmount() + result.getAmount());
            player.setItemOnCursor(cursor);
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(result);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    protected ItemStack createReferencedItem(String reference, int amount) {
        ItemStack stack = customContentService != null ? customContentService.createReferencedItem(reference, amount) : null;
        if (stack != null) {
            return stack;
        }
        Material material = RuntimeMaterialResolver.itemMaterial(reference);
        return material != null ? new ItemStack(material, Math.max(1, amount)) : null;
    }

    private void dispatch(JsonObject profile, String hook, Player player, Entity entity, String profileId, Event event, Map<String, Object> extraVariables) {
        JsonObject hooks = profile != null && profile.has("hooks") && profile.get("hooks").isJsonObject() ? profile.getAsJsonObject("hooks") : null;
        if (hooks == null || dispatcher == null) {
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("player", player);
        variables.put("entity", entity);
        variables.put("profileId", profileId);
        variables.put("event.id", profileId);
        variables.put("event.entity", entity);
        variables.put("event.target", entity);
        variables.put("trade", null);
        variables.put("result", hook);
        variables.put("hook", hook);
        if (extraVariables != null) {
            variables.putAll(extraVariables);
        }
        if (hooks.has(hook) && hooks.get(hook).isJsonObject()) {
            dispatcher.dispatchFunction(hooks.getAsJsonObject(hook), player, event, variables);
            return;
        }
        String legacyHook = hook.endsWith("Action") ? hook.substring(0, hook.length() - "Action".length()) + "Flow" : hook;
        String flowId = text(hooks, legacyHook);
        if (!flowId.isBlank()) {
            dispatcher.dispatch(flowId, player, event, variables);
        }
    }

    private String enumName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int namespace = value.indexOf(':');
        String local = namespace >= 0 ? value.substring(namespace + 1) : value;
        return local.replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private String text(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private int integer(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Trade profile field must be an integer: " + key, exception);
        }
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Trade profile field must be a boolean: " + key, exception);
        }
    }

    private record TradeSession(String profileId, UUID entityId, boolean virtual) {
    }
}
