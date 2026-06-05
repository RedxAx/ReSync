package restudio.resync.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.Location;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event.Result;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CampfireStartEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.inventory.StonecutterInventory;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.StonecutterView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowGraph;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customization.ResourceJson;
import restudio.resync.customcontent.CustomContentProvider;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowPredicateSupport;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FunctionCallSupport;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.text.ReTextService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RecipeModule implements Module, Listener {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("recipes", "Recipes").withDependencies("flow");
    private ReSyncJsonResourceStorage storage;
    private ReTextService text;
    private CustomContentService customContentService;
    private FlowStorage flowStorage;
    private FlowExecutor flowExecutor;
    private ModuleContext context;
    private final Set<NamespacedKey> registered = new HashSet<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.context = context;
        storage = context.getRequiredService(ReSyncJsonResourceStorage.class);
        text = context.getRequiredService(ReTextService.class);
        customContentService = context.getService(CustomContentService.class);
        flowStorage = context.getService(FlowStorage.class);
        flowExecutor = context.getService(FlowExecutor.class);
        context.registerService(RecipeModule.class, this);
    }

    @Override
    public void start(ModuleContext context) {
        Bukkit.getPluginManager().registerEvents(this, context.getPlugin());
        reloadRecipes();
    }

    @Override
    public void stop(ModuleContext context) {
        HandlerList.unregisterAll(this);
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
    }

    public void reloadRecipes() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
        for (String id : storage.listIds(ReSyncResourceCatalog.RECIPE_DEFINITION)) {
            JsonObject definition = storage.get(ReSyncResourceCatalog.RECIPE_DEFINITION, id);
            if (!ResourceJson.bool(definition, "enabled", true)) {
                continue;
            }
            if (manualCraftingRecipe(definition)) {
                continue;
            }
            Recipe recipe = createRecipe(definition);
            if (recipe != null && Bukkit.addRecipe(recipe)) {
                registered.add(key(definition));
            }
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        JsonObject definition = matchingManualCraftingDefinition(player, event.getInventory());
        if (definition == null) {
            definition = definitionFor(event.getRecipe());
        }
        if (definition == null) {
            if (matrixHasCustomContent(event.getInventory().getMatrix())) {
                event.getInventory().setResult(null);
            }
            return;
        }
        if (!conditionsPass(definition, player, false) || !ingredientsPass(definition, event.getInventory())) {
            event.getInventory().setResult(null);
            return;
        }
        ItemStack dynamic = dynamicOutput(definition, player);
        if (dynamic != null) {
            event.getInventory().setResult(dynamic);
        } else if (manualCraftingRecipe(definition)) {
            event.getInventory().setResult(item(ResourceJson.object(definition, "output")));
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        JsonObject definition = matchingManualCraftingDefinition(player, event.getInventory());
        if (definition == null) {
            definition = definitionFor(event.getRecipe());
        }
        if (definition == null) {
            if (matrixHasCustomContent(event.getInventory().getMatrix())) {
                event.setCancelled(true);
            }
            return;
        }
        handleCraftTake(definition, event, player, event.getInventory());
    }

    @EventHandler
    public void onManualCraftTake(InventoryClickEvent event) {
        if (event instanceof CraftItemEvent || event.getSlotType() != InventoryType.SlotType.RESULT || !(event.getWhoClicked() instanceof Player player)
            || !(event.getView().getTopInventory() instanceof CraftingInventory inventory)) {
            return;
        }
        JsonObject definition = matchingManualCraftingDefinition(player, inventory);
        if (definition == null) {
            if (matrixHasCustomContent(inventory.getMatrix())) {
                event.setCancelled(true);
            }
            return;
        }
        handleCraftTake(definition, event, player, inventory);
    }

    private void handleCraftTake(JsonObject definition, InventoryClickEvent event, Player player, CraftingInventory inventory) {
        boolean allowed = conditionsPass(definition, player) && ingredientsPass(definition, inventory);
        if (!allowed) {
            event.setCancelled(true);
            String denyMessage = ResourceJson.string(ResourceJson.object(definition, "conditions"), "denyMessage", "<red>No Permission");
            player.sendMessage(text.render(denyMessage, player, player));
            dispatchFlow(definition, "denied", player, event, Map.of("event.recipe", recipeId(definition)));
            return;
        }
        ItemStack dynamic = dynamicOutput(definition, player);
        if (dynamic != null) {
            inventory.setResult(dynamic);
        } else if (manualCraftingRecipe(definition)) {
            inventory.setResult(item(ResourceJson.object(definition, "output")));
        }
        int crafts = craftAmount(definition, event, inventory);
        if (crafts <= 0) {
            event.setCancelled(true);
            return;
        }
        ItemStack output = inventory.getResult();
        if (output == null || output.getType() == Material.AIR) {
            event.setCancelled(true);
            return;
        }
        output = output.clone();
        output.setAmount(output.getAmount() * crafts);
        if (!deliverClickResult(event, player, output)) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        consumeIngredients(definition, inventory, crafts);
        player.updateInventory();
        Map<String, Object> vars = new HashMap<>();
        vars.put("event.recipe", recipeId(definition));
        vars.put("event.amount", crafts);
        vars.put("event.output", output);
        dispatchFlow(definition, "crafted", player, event, vars);
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        JsonObject definition = definitionFor(event.getInventory().getRecipe());
        if (definition == null || !(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        if (!conditionsPass(definition, player, false) || !smithingIngredientsPass(definition, event.getInventory())) {
            event.getInventory().setResult(null);
            return;
        }
        ItemStack dynamic = dynamicOutput(definition, player);
        if (dynamic != null) {
            event.getInventory().setResult(dynamic);
        }
    }

    @EventHandler
    public void onSmithItem(SmithItemEvent event) {
        JsonObject definition = definitionFor(event.getInventory().getRecipe());
        if (definition == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!conditionsPass(definition, player) || !smithingIngredientsPass(definition, event.getInventory())) {
            event.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(context.getPlugin(), () -> consumeExtraSmithingIngredients(definition, event.getInventory()));
    }

    @EventHandler
    public void onFurnaceStart(FurnaceStartSmeltEvent event) {
        JsonObject definition = definitionFor(event.getRecipe());
        if (definition == null) {
            return;
        }
        if (!blockConditionsPass(definition, event.getBlock().getWorld().getName()) || !itemMatches(inputDefinition(definition), event.getSource())) {
            event.setTotalCookTime(Integer.MAX_VALUE);
            return;
        }
        Bukkit.getScheduler().runTask(context.getPlugin(), () -> consumeExtraFurnaceInput(definition, event.getBlock().getState()));
    }

    @EventHandler
    public void onCampfireStart(CampfireStartEvent event) {
        JsonObject definition = definitionFor(event.getRecipe());
        if (definition == null) {
            definition = matchingCampfireDefinition(event.getSource(), event.getBlock().getWorld().getName());
        }
        if (definition == null) {
            return;
        }
        if (!blockConditionsPass(definition, event.getBlock().getWorld().getName())
            || !itemMatchesIgnoringAmount(inputDefinition(definition), event.getSource())) {
            event.setTotalCookTime(Integer.MAX_VALUE);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCampfireInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getItem() == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Material blockType = event.getClickedBlock().getType();
        if (!isCampfire(blockType)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack before = event.getItem().clone();
        JsonObject definition = matchingCampfireDefinition(before, player, event.getClickedBlock().getWorld().getName());
        if (definition == null) {
            return;
        }
        int amount = matchedAmount(inputDefinition(definition), before);
        if (amount <= 1 || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (before.getAmount() < amount) {
            denyInteraction(event);
            dispatchFlow(definition, "denied", player, event, Map.of("event.recipe", recipeId(definition), "event.source", before));
            return;
        }
        EquipmentSlot hand = event.getHand();
        Location location = event.getClickedBlock().getLocation();
        Bukkit.getScheduler().runTask(context.getPlugin(), () -> consumeCampfireExtra(player, hand, location, before, amount));
    }

    @EventHandler
    public void onBlockCook(BlockCookEvent event) {
        Recipe recipe = event.getRecipe();
        JsonObject definition = recipe instanceof CookingRecipe<?> cookingRecipe ? definitionFor(cookingRecipe) : null;
        if (isCampfire(event.getBlock().getType())) {
            definition = definition != null ? definition : matchingCampfireDefinition(event.getSource(), event.getBlock().getWorld().getName());
        }
        if (definition != null && !blockConditionsPass(definition, event.getBlock().getWorld().getName())) {
            event.setCancelled(true);
            dispatchFlow(definition, "denied", null, event, Map.of("event.recipe", recipeId(definition), "event.source", event.getSource()));
            return;
        }
        if (definition != null && !itemMatchesIgnoringAmount(inputDefinition(definition), event.getSource())) {
            event.setCancelled(true);
            dispatchFlow(definition, "denied", null, event, Map.of("event.recipe", recipeId(definition), "event.source", event.getSource()));
            return;
        }
        if (definition != null) {
            ItemStack dynamic = dynamicOutput(definition, null);
            if (dynamic != null) {
                event.setResult(dynamic);
            } else {
                event.setResult(item(ResourceJson.object(definition, "output")));
            }
            Map<String, Object> vars = new HashMap<>();
            vars.put("event.recipe", recipeId(definition));
            vars.put("event.source", event.getSource());
            if (event.getResult() != null) {
                vars.put("event.output", event.getResult());
            }
            dispatchFlow(definition, "cooked", null, event, vars);
        }
    }

    private void denyInteraction(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
    }

    @EventHandler
    public void onStonecuttingSelect(PlayerStonecutterRecipeSelectEvent event) {
        JsonObject definition = definitionFor(event.getStonecuttingRecipe());
        if (definition == null) {
            return;
        }
        if (!conditionsPass(definition, event.getPlayer(), false) || !itemMatches(inputDefinition(definition), event.getStonecutterInventory().getInputItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onStonecuttingTake(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType() != InventoryType.STONECUTTER || event.getSlotType() != InventoryType.SlotType.RESULT || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        JsonObject definition = selectedStonecuttingDefinition(event);
        if (definition == null) {
            return;
        }
        StonecutterInventory inventory = (StonecutterInventory) event.getView().getTopInventory();
        if (!conditionsPass(definition, player) || !itemMatches(inputDefinition(definition), inventory.getInputItem())) {
            event.setCancelled(true);
            return;
        }
        int amount = stonecuttingAmount(definition, event, inventory);
        if (amount <= 0) {
            event.setCancelled(true);
            return;
        }
        ItemStack output = event.getCurrentItem();
        if (output == null || output.getType() == Material.AIR) {
            event.setCancelled(true);
            return;
        }
        output = output.clone();
        output.setAmount(output.getAmount() * amount);
        if (!deliverClickResult(event, player, output)) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        consumeStonecuttingInput(definition, inventory, amount);
        player.updateInventory();
    }

    private Recipe createRecipe(JsonObject definition) {
        ItemStack output = item(ResourceJson.object(definition, "output"));
        if (output.getType() == Material.AIR) {
            return null;
        }
        String type = ResourceJson.string(definition, "type", "shaped").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "shapeless" -> shapelessRecipe(definition, output);
            case "furnace", "smelting" -> cookingRecipe(definition, output, "furnace");
            case "blasting", "blast" -> cookingRecipe(definition, output, "blasting");
            case "smoking", "smoker" -> cookingRecipe(definition, output, "smoking");
            case "campfire", "campfire_cooking" -> cookingRecipe(definition, output, "campfire");
            case "stonecutting", "stonecutter" -> stonecuttingRecipe(definition, output);
            case "smithing", "smithing_transform" -> smithingTransformRecipe(definition, output);
            case "smithing_trim", "trim" -> smithingTrimRecipe(definition);
            default -> shapedRecipe(definition, output);
        };
    }

    private Recipe shapelessRecipe(JsonObject definition, ItemStack output) {
        ShapelessRecipe recipe = new ShapelessRecipe(key(definition), output);
        for (JsonElement element : ingredients(definition)) {
            RecipeChoice choice = choice(element);
            if (choice != null) {
                recipe.addIngredient(choice);
            }
        }
        return recipe;
    }

    private Recipe shapedRecipe(JsonObject definition, ItemStack output) {
        ShapedRecipe recipe = new ShapedRecipe(key(definition), output);
        JsonArray shape = definition.has("shape") && definition.get("shape").isJsonArray() ? definition.getAsJsonArray("shape") : new JsonArray();
        if (shape.isEmpty()) {
            recipe.shape("AAA", "AAA", "AAA");
        } else {
            String[] rows = new String[Math.min(3, shape.size())];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = shape.get(i).getAsString();
            }
            recipe.shape(rows);
        }
        JsonObject keys = ResourceJson.object(definition, "keys");
        if (keys != null) {
            for (String symbol : keys.keySet()) {
                if (!symbol.isBlank()) {
                    RecipeChoice choice = choice(keys.get(symbol));
                    if (choice != null) {
                        recipe.setIngredient(symbol.charAt(0), choice);
                    }
                }
            }
        }
        return recipe;
    }

    private Recipe cookingRecipe(JsonObject definition, ItemStack output, String type) {
        RecipeChoice input = inputChoice(definition);
        if (input == null) {
            return null;
        }
        float experience = (float) ResourceJson.decimal(definition, "experience", 0);
        int cookingTime = Math.max(1, ResourceJson.integer(definition, "cookingTime", ResourceJson.integer(definition, "time", 200)));
        return switch (type) {
            case "blasting" -> new BlastingRecipe(key(definition), output, input, experience, cookingTime);
            case "smoking" -> new SmokingRecipe(key(definition), output, input, experience, cookingTime);
            case "campfire" -> new CampfireRecipe(key(definition), output, input, experience, cookingTime);
            default -> new FurnaceRecipe(key(definition), output, input, experience, cookingTime);
        };
    }

    private Recipe stonecuttingRecipe(JsonObject definition, ItemStack output) {
        RecipeChoice input = inputChoice(definition);
        return input != null ? new StonecuttingRecipe(key(definition), output, input) : null;
    }

    private Recipe smithingTransformRecipe(JsonObject definition, ItemStack output) {
        RecipeChoice template = choiceField(definition, "template");
        RecipeChoice base = choiceField(definition, "base");
        RecipeChoice addition = choiceField(definition, "addition");
        return template != null && base != null && addition != null ? new SmithingTransformRecipe(key(definition), output, template, base, addition) : null;
    }

    private Recipe smithingTrimRecipe(JsonObject definition) {
        RecipeChoice template = choiceField(definition, "template");
        RecipeChoice base = choiceField(definition, "base");
        RecipeChoice addition = choiceField(definition, "addition");
        return template != null && base != null && addition != null ? new SmithingTrimRecipe(key(definition), template, base, addition) : null;
    }

    private RecipeChoice choiceField(JsonObject definition, String key) {
        return definition.has(key) ? choice(definition.get(key)) : null;
    }

    private RecipeChoice inputChoice(JsonObject definition) {
        if (definition.has("input")) {
            return choice(definition.get("input"));
        }
        if (definition.has("ingredient")) {
            return choice(definition.get("ingredient"));
        }
        JsonArray ingredients = ingredients(definition);
        return ingredients.isEmpty() ? null : choice(ingredients.get(0));
    }

    private JsonArray ingredients(JsonObject definition) {
        return definition.has("ingredients") && definition.get("ingredients").isJsonArray() ? definition.getAsJsonArray("ingredients") : new JsonArray();
    }

    private ItemStack item(JsonObject object) {
        ItemStack referenced = referencedItem(object, Math.max(1, ResourceJson.integer(object, "amount", 1)));
        if (referenced != null) {
            return referenced;
        }
        String contentId = contentId(object);
        if (!contentId.isBlank() && customContentService != null) {
            ItemStack custom = customContentService.createItem(contentId, Math.max(1, ResourceJson.integer(object, "amount", 1)));
            if (custom != null) {
                return custom;
            }
        }
        ItemStack providerItem = providerItem(object, Math.max(1, ResourceJson.integer(object, "amount", 1)));
        if (providerItem != null) {
            return providerItem;
        }
        Material material = material(ResourceJson.string(object, "material", "AIR"));
        int amount = Math.max(1, ResourceJson.integer(object, "amount", 1));
        ItemStack item = new ItemStack(material, amount);
        applyItemMeta(item, object);
        return item;
    }

    private RecipeChoice choice(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            Set<Material> materials = new HashSet<>();
            for (JsonElement choice : element.getAsJsonArray()) {
                if (choice.isJsonObject()) {
                    materials.addAll(choiceMaterials(choice.getAsJsonObject()));
                } else {
                    ItemStack referenced = referencedItem(choice.getAsString(), 1);
                    if (referenced != null && referenced.getType() != Material.AIR) {
                        materials.add(referenced.getType());
                        continue;
                    }
                    Material material = material(choice.getAsString());
                    if (material != Material.AIR) {
                        materials.add(material);
                    }
                }
            }
            return materials.isEmpty() ? null : new RecipeChoice.MaterialChoice(materials.stream().toList());
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            RecipeChoice referenceChoice = referenceChoice(object);
            if (referenceChoice != null) {
                return referenceChoice;
            }
            ItemStack exact = exactChoiceItem(object);
            if (exact != null) {
                return new RecipeChoice.ExactChoice(exact);
            }
            JsonArray choices = object.has("materials") && object.get("materials").isJsonArray() ? object.getAsJsonArray("materials") : null;
            if (choices != null) {
                return choice(choices);
            }
            Set<Material> tagged = tagMaterials(ResourceJson.string(object, "tag", ""));
            if (!tagged.isEmpty()) {
                return new RecipeChoice.MaterialChoice(tagged.stream().toList());
            }
            return materialChoice(ResourceJson.string(object, "material", ResourceJson.string(object, "item", "AIR")));
        }
        ItemStack referenced = referencedItem(element.getAsString(), 1);
        if (referenced != null) {
            return referenced.getType() != Material.AIR ? new RecipeChoice.MaterialChoice(referenced.getType()) : null;
        }
        return materialChoice(element.getAsString());
    }

    private Set<Material> choiceMaterials(JsonObject object) {
        Set<Material> materials = new HashSet<>();
        String materialName = ResourceJson.string(object, "material", ResourceJson.string(object, "item", ""));
        Material material = material(materialName);
        if (material != Material.AIR) {
            materials.add(material);
        }
        materials.addAll(tagMaterials(ResourceJson.string(object, "tag", "")));
        ItemStack exact = exactChoiceItem(object);
        if (exact != null && exact.getType() != Material.AIR) {
            materials.add(exact.getType());
        }
        return materials;
    }

    private RecipeChoice referenceChoice(JsonObject object) {
        ItemStack referenced = referencedItem(object, 1);
        return referenced != null && referenced.getType() != Material.AIR ? new RecipeChoice.MaterialChoice(referenced.getType()) : null;
    }

    private Set<Material> tagMaterials(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return Set.of();
        }
        NamespacedKey key = namespacedKey(tagName);
        if (key == null) {
            return Set.of();
        }
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
        return tag != null ? tag.getValues() : Set.of();
    }

    private ItemStack exactChoiceItem(JsonObject object) {
        ItemStack referenced = referencedItem(object, 1);
        if (referenced != null) {
            return referenced;
        }
        String contentId = contentId(object);
        if (!contentId.isBlank() && customContentService != null) {
            return customContentService.createItem(contentId, 1);
        }
        return providerItem(object, 1);
    }

    private ItemStack referencedItem(JsonObject object, int amount) {
        String reference = itemReference(object);
        if (reference.isBlank()) {
            String contentId = contentId(object);
            if (!contentId.isBlank()) {
                reference = "content:" + contentId;
            }
        }
        if (reference.isBlank()) {
            String provider = ResourceJson.string(object, "provider", "");
            String externalId = ResourceJson.string(object, "externalId", ResourceJson.string(object, "external_id", ResourceJson.string(object, "nexo", "")));
            if (provider.isBlank() && !externalId.isBlank()) {
                provider = "nexo";
            }
            if (!provider.isBlank() && !externalId.isBlank()) {
                reference = "provider:" + provider + ':' + externalId;
            }
        }
        return !reference.isBlank() ? referencedItem(reference, amount) : null;
    }

    private ItemStack referencedItem(String reference, int amount) {
        if (customContentService == null || reference == null || reference.isBlank()) {
            return null;
        }
        return customContentService.createReferencedItem(reference, Math.max(1, amount));
    }

    private String itemReference(JsonObject object) {
        String reference = ResourceJson.string(object, "reference", ResourceJson.string(object, "ref", ""));
        if (!reference.isBlank()) {
            return reference;
        }
        String item = ResourceJson.string(object, "item", "");
        if (isCustomItemReference(item)) {
            return item;
        }
        String material = ResourceJson.string(object, "material", "");
        return isCustomItemReference(material) ? material : "";
    }

    private ItemStack providerItem(JsonObject object, int amount) {
        String provider = ResourceJson.string(object, "provider", "");
        String externalId = ResourceJson.string(object, "externalId", ResourceJson.string(object, "external_id", ResourceJson.string(object, "nexo", "")));
        if (provider.isBlank() && !externalId.isBlank()) {
            provider = "nexo";
        }
        if (provider.isBlank() || externalId.isBlank() || customContentService == null || !customContentService.isProviderAvailable(provider)) {
            return null;
        }
        CustomContentDefinition definition = new CustomContentDefinition();
        definition.setId(ResourceJson.string(object, "id", provider + "_" + externalId));
        definition.setProvider(provider);
        definition.setExternalId(externalId);
        definition.setType(ResourceJson.string(object, "contentType", "item"));
        definition.setMaterial(ResourceJson.string(object, "material", "STICK"));
        CustomContentProvider customProvider = customContentService.providerFor(definition);
        return customProvider.createItem(definition, Math.max(1, amount));
    }

    private RecipeChoice materialChoice(String name) {
        Material material = material(name);
        return material == Material.AIR ? null : new RecipeChoice.MaterialChoice(material);
    }

    private Material material(String name) {
        Material material = name != null ? Material.matchMaterial(name) : null;
        return material != null ? material : Material.AIR;
    }

    private boolean conditionsPass(JsonObject definition, Player player) {
        return conditionsPass(definition, player, true);
    }

    private boolean conditionsPass(JsonObject definition, Player player, boolean commitCooldown) {
        JsonObject conditions = ResourceJson.object(definition, "conditions");
        if (player == null) {
            return !ResourceJson.bool(conditions, "requiresPlayer", false);
        }
        String permission = ResourceJson.string(conditions, "permission", "");
        if (!permission.isBlank() && !player.hasPermission(permission)) {
            return false;
        }
        for (String value : ResourceJson.strings(conditions, "permissions")) {
            if (!value.isBlank() && !player.hasPermission(value)) {
                return false;
            }
        }
        String world = ResourceJson.string(conditions, "world", "");
        if (!world.isBlank() && !player.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }
        if (!ResourceJson.strings(conditions, "worlds").isEmpty() && ResourceJson.strings(conditions, "worlds").stream().noneMatch(player.getWorld().getName()::equalsIgnoreCase)) {
            return false;
        }
        for (String value : ResourceJson.strings(conditions, "groups")) {
            if (!value.isBlank() && !player.hasPermission("group." + value)) {
                return false;
            }
        }
        if (!playerStatePass(player, conditions)) {
            return false;
        }
        if (!customContentOwnershipPass(player, conditions)) {
            return false;
        }
        if (!inventoryContains(player, ResourceJson.object(conditions, "inventoryContains"))) {
            return false;
        }
        int cooldownSeconds = ResourceJson.integer(conditions, "cooldownSeconds", 0);
        if (cooldownSeconds > 0 && !cooldownReady(recipeId(definition), player, cooldownSeconds, commitCooldown)) {
            return false;
        }
        String biome = ResourceJson.string(conditions, "biome", "");
        if (!biome.isBlank() && !player.getLocation().getBlock().getBiome().name().equalsIgnoreCase(biome)) {
            return false;
        }
        long time = player.getWorld().getTime();
        int minTime = ResourceJson.integer(conditions, "minTime", -1);
        int maxTime = ResourceJson.integer(conditions, "maxTime", -1);
        if (minTime >= 0 && time < minTime || maxTime >= 0 && time > maxTime) {
            return false;
        }
        String weather = ResourceJson.string(conditions, "weather", "");
        return (weather.isBlank() || weatherMatches(player, weather)) && flowPredicate(definition, player);
    }

    private boolean playerStatePass(Player player, JsonObject conditions) {
        JsonObject state = ResourceJson.object(conditions, "playerState");
        String gameMode = conditionString(conditions, state, "gameMode", "");
        GameMode gameModeValue = !gameMode.isBlank() ? gameMode(gameMode) : null;
        if (gameModeValue == null && !gameMode.isBlank() || gameModeValue != null && player.getGameMode() != gameModeValue) {
            return false;
        }
        String gamemode = conditionString(conditions, state, "gamemode", "");
        GameMode gamemodeValue = !gamemode.isBlank() ? gameMode(gamemode) : null;
        if (gamemodeValue == null && !gamemode.isBlank() || gamemodeValue != null && player.getGameMode() != gamemodeValue) {
            return false;
        }
        if (!boolStatePass(conditions, state, "sneaking", player.isSneaking())) {
            return false;
        }
        if (!boolStatePass(conditions, state, "sprinting", player.isSprinting())) {
            return false;
        }
        if (!boolStatePass(conditions, state, "flying", player.isFlying())) {
            return false;
        }
        if (!boolStatePass(conditions, state, "swimming", player.isSwimming())) {
            return false;
        }
        if (!boolStatePass(conditions, state, "gliding", player.isGliding())) {
            return false;
        }
        if (!boolStatePass(conditions, state, "onGround", player.isOnGround())) {
            return false;
        }
        if (!rangePass(player.getHealth(), conditionDecimal(conditions, state, "minHealth", -1), conditionDecimal(conditions, state, "maxHealth", -1))) {
            return false;
        }
        if (!rangePass(player.getFoodLevel(), conditionDecimal(conditions, state, "minFood", -1), conditionDecimal(conditions, state, "maxFood", -1))) {
            return false;
        }
        if (!rangePass(player.getSaturation(), conditionDecimal(conditions, state, "minSaturation", -1), conditionDecimal(conditions, state, "maxSaturation", -1))) {
            return false;
        }
        if (!rangePass(player.getLevel(), conditionDecimal(conditions, state, "minLevel", -1), conditionDecimal(conditions, state, "maxLevel", -1))) {
            return false;
        }
        return rangePass(player.getExp(), conditionDecimal(conditions, state, "minExp", -1), conditionDecimal(conditions, state, "maxExp", -1));
    }

    private boolean customContentOwnershipPass(Player player, JsonObject conditions) {
        JsonElement requirement = conditionElement(conditions, "customContentOwnership", "ownedCustomContent", "ownedContent", "customContent");
        if (requirement == null || requirement.isJsonNull()) {
            return true;
        }
        if (customContentService == null) {
            return false;
        }
        return contentRequirementPass(player, requirement);
    }

    private boolean contentRequirementPass(Player player, JsonElement requirement) {
        if (requirement == null || requirement.isJsonNull()) {
            return true;
        }
        if (requirement.isJsonArray()) {
            for (JsonElement element : requirement.getAsJsonArray()) {
                if (!contentRequirementPass(player, element)) {
                    return false;
                }
            }
            return true;
        }
        if (!requirement.isJsonObject()) {
            return ownedContentAmount(player, requirement) > 0;
        }
        JsonObject object = requirement.getAsJsonObject();
        if (object.has("any") && object.get("any").isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray("any")) {
                if (contentRequirementPass(player, element)) {
                    return true;
                }
            }
            return false;
        }
        if (object.has("all") && object.get("all").isJsonArray()) {
            return contentRequirementPass(player, object.getAsJsonArray("all"));
        }
        int amount = Math.max(1, ResourceJson.integer(object, "amount", 1));
        return ownedContentAmount(player, requirement) >= amount;
    }

    private int ownedContentAmount(Player player, JsonElement requirement) {
        int amount = 0;
        for (ItemStack item : playerInventoryItems(player)) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            if (ownedContentMatches(requirement, item)) {
                amount += item.getAmount();
            }
        }
        return amount;
    }

    private boolean ownedContentMatches(JsonElement requirement, ItemStack item) {
        if (requirement == null || requirement.isJsonNull()) {
            return true;
        }
        if (!requirement.isJsonObject()) {
            String reference = requirement.getAsString();
            if (isCustomItemReference(reference)) {
                return customContentService.matchesItemReference(item, reference);
            }
            String identified = customContentService.identifyItem(item);
            return reference.equalsIgnoreCase(identified);
        }
        JsonObject object = requirement.getAsJsonObject();
        if (!object.has("amount")) {
            return itemMatches(requirement, item);
        }
        JsonObject copy = object.deepCopy();
        copy.remove("amount");
        return itemMatches(copy, item);
    }

    private List<ItemStack> playerInventoryItems(Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            items.add(item);
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            items.add(item);
        }
        items.add(player.getInventory().getItemInOffHand());
        return items;
    }

    private boolean boolStatePass(JsonObject conditions, JsonObject state, String key, boolean actual) {
        JsonElement value = conditionElement(conditions, state, key);
        return value == null || value.isJsonNull() || value.getAsBoolean() == actual;
    }

    private boolean rangePass(double value, double min, double max) {
        return (min < 0 || value >= min) && (max < 0 || value <= max);
    }

    private GameMode gameMode(String value) {
        try {
            return GameMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String conditionString(JsonObject conditions, JsonObject state, String key, String fallback) {
        JsonElement value = conditionElement(conditions, state, key);
        return value != null && !value.isJsonNull() ? value.getAsString() : fallback;
    }

    private double conditionDecimal(JsonObject conditions, JsonObject state, String key, double fallback) {
        JsonElement value = conditionElement(conditions, state, key);
        return value != null && !value.isJsonNull() ? value.getAsDouble() : fallback;
    }

    private JsonElement conditionElement(JsonObject conditions, JsonObject state, String key) {
        if (state != null && state.has(key)) {
            return state.get(key);
        }
        return conditions != null && conditions.has(key) ? conditions.get(key) : null;
    }

    private JsonElement conditionElement(JsonObject conditions, String... keys) {
        if (conditions == null) {
            return null;
        }
        for (String key : keys) {
            if (conditions.has(key)) {
                return conditions.get(key);
            }
        }
        return null;
    }

    private boolean blockConditionsPass(JsonObject definition, String worldName) {
        JsonObject conditions = ResourceJson.object(definition, "conditions");
        String world = ResourceJson.string(conditions, "world", "");
        if (!world.isBlank() && !worldName.equalsIgnoreCase(world)) {
            return false;
        }
        List<String> worlds = ResourceJson.strings(conditions, "worlds");
        return worlds.isEmpty() || worlds.stream().anyMatch(worldName::equalsIgnoreCase);
    }

    private JsonObject selectedStonecuttingDefinition(InventoryClickEvent event) {
        if (!(event.getView() instanceof StonecutterView view)) {
            return null;
        }
        int index = view.getSelectedRecipeIndex();
        List<StonecuttingRecipe> recipes = view.getRecipes();
        if (index < 0 || index >= recipes.size()) {
            return null;
        }
        return definitionFor(recipes.get(index));
    }

    private JsonObject matchingManualCraftingDefinition(Player player, CraftingInventory inventory) {
        for (String id : storage.listIds(ReSyncResourceCatalog.RECIPE_DEFINITION)) {
            JsonObject definition = storage.get(ReSyncResourceCatalog.RECIPE_DEFINITION, id);
            if (!ResourceJson.bool(definition, "enabled", true) || !manualCraftingRecipe(definition)) {
                continue;
            }
            if (conditionsPass(definition, player, false) && ingredientsPass(definition, inventory)) {
                return definition;
            }
        }
        return null;
    }

    private boolean manualCraftingRecipe(JsonObject definition) {
        String type = ResourceJson.string(definition, "type", "shaped").toLowerCase(Locale.ROOT);
        if (!"shaped".equals(type) && !"shapeless".equals(type)) {
            return false;
        }
        if ("shapeless".equals(type)) {
            for (JsonElement element : ingredients(definition)) {
                if (customIngredientReference(element)) {
                    return true;
                }
            }
            return false;
        }
        JsonObject keys = ResourceJson.object(definition, "keys");
        if (keys == null) {
            return false;
        }
        for (String key : keys.keySet()) {
            if (customIngredientReference(keys.get(key))) {
                return true;
            }
        }
        return false;
    }

    private boolean customIngredientReference(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (customIngredientReference(child)) {
                    return true;
                }
            }
            return false;
        }
        if (!element.isJsonObject()) {
            return isCustomItemReference(element.getAsString());
        }
        JsonObject object = element.getAsJsonObject();
        if (!itemReference(object).isBlank() || !contentId(object).isBlank()) {
            return true;
        }
        String externalId = ResourceJson.string(object, "externalId", ResourceJson.string(object, "external_id", ResourceJson.string(object, "nexo", "")));
        return !externalId.isBlank();
    }

    private boolean matrixHasCustomContent(ItemStack[] matrix) {
        if (customContentService == null) {
            return false;
        }
        for (ItemStack item : matrix) {
            if (item != null && item.getType() != Material.AIR && customContentService.identifyItem(item) != null) {
                return true;
            }
        }
        return false;
    }

    private JsonObject matchingCampfireDefinition(ItemStack item, Player player, String worldName) {
        for (String id : storage.listIds(ReSyncResourceCatalog.RECIPE_DEFINITION)) {
            JsonObject definition = storage.get(ReSyncResourceCatalog.RECIPE_DEFINITION, id);
            String type = ResourceJson.string(definition, "type", "shaped").toLowerCase(Locale.ROOT);
            JsonElement input = inputDefinition(definition);
            if (("campfire".equals(type) || "campfire_cooking".equals(type)) && input != null && conditionsPass(definition, player) && blockConditionsPass(definition, worldName) && itemMatchesIgnoringAmount(input, item)) {
                return definition;
            }
        }
        return null;
    }

    private JsonObject matchingCampfireDefinition(ItemStack item, String worldName) {
        for (String id : storage.listIds(ReSyncResourceCatalog.RECIPE_DEFINITION)) {
            JsonObject definition = storage.get(ReSyncResourceCatalog.RECIPE_DEFINITION, id);
            String type = ResourceJson.string(definition, "type", "shaped").toLowerCase(Locale.ROOT);
            JsonElement input = inputDefinition(definition);
            if (("campfire".equals(type) || "campfire_cooking".equals(type)) && input != null && blockConditionsPass(definition, worldName) && itemMatchesIgnoringAmount(input, item)) {
                return definition;
            }
        }
        return null;
    }

    private boolean ingredientsPass(JsonObject definition, CraftingInventory inventory) {
        String type = ResourceJson.string(definition, "type", "shaped").toLowerCase(Locale.ROOT);
        ItemStack[] matrix = inventory.getMatrix();
        return "shapeless".equals(type) ? shapelessIngredientsPass(definition, matrix) : shapedIngredientsPass(definition, matrix);
    }

    private boolean smithingIngredientsPass(JsonObject definition, SmithingInventory inventory) {
        return itemMatches(ResourceJson.object(definition, "template"), inventory.getInputTemplate())
            && itemMatches(ResourceJson.object(definition, "base"), inventory.getInputEquipment())
            && itemMatches(ResourceJson.object(definition, "addition"), inventory.getInputMineral());
    }

    private boolean shapedIngredientsPass(JsonObject definition, ItemStack[] matrix) {
        JsonArray shape = definition.has("shape") && definition.get("shape").isJsonArray() ? definition.getAsJsonArray("shape") : new JsonArray();
        JsonObject keys = ResourceJson.object(definition, "keys");
        if (shape.isEmpty() || keys == null) {
            return true;
        }
        for (int row = 0; row < Math.min(3, shape.size()); row++) {
            String line = shape.get(row).getAsString();
            for (int column = 0; column < Math.min(3, line.length()); column++) {
                char symbol = line.charAt(column);
                if (symbol == ' ') {
                    continue;
                }
                JsonElement ingredient = keys.get(String.valueOf(symbol));
                int index = row * 3 + column;
                if (ingredient != null && index < matrix.length && !itemMatches(ingredient, matrix[index])) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean shapelessIngredientsPass(JsonObject definition, ItemStack[] matrix) {
        List<JsonElement> required = new ArrayList<>();
        for (JsonElement element : ingredients(definition)) {
            required.add(element);
        }
        for (ItemStack item : matrix) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            int match = -1;
            for (int i = 0; i < required.size(); i++) {
                if (itemMatches(required.get(i), item)) {
                    match = i;
                    break;
                }
            }
            if (match >= 0) {
                required.remove(match);
            }
        }
        return required.isEmpty();
    }

    private int craftAmount(JsonObject definition, InventoryClickEvent event, CraftingInventory inventory) {
        int maxCrafts = maxCrafts(definition, inventory);
        if (maxCrafts <= 0) {
            return 0;
        }
        ItemStack result = inventory.getResult();
        if (result == null || result.getType() == Material.AIR) {
            return 0;
        }
        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            return Math.min(maxCrafts, roomForResult(event, result));
        }
        if (click == ClickType.NUMBER_KEY) {
            return hotbarCanTake(event, result) ? 1 : 0;
        }
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP || click == ClickType.WINDOW_BORDER_LEFT || click == ClickType.WINDOW_BORDER_RIGHT) {
            return 1;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType() == Material.AIR) {
            return 1;
        }
        return cursor.isSimilar(result) && cursor.getAmount() + result.getAmount() <= cursor.getMaxStackSize() ? 1 : 0;
    }

    private boolean deliverClickResult(InventoryClickEvent event, Player player, ItemStack output) {
        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            return player.getInventory().addItem(output).isEmpty();
        }
        if (click == ClickType.NUMBER_KEY) {
            int button = event.getHotbarButton();
            if (button < 0 || button > 8) {
                return false;
            }
            ItemStack item = player.getInventory().getItem(button);
            if (item == null || item.getType() == Material.AIR) {
                player.getInventory().setItem(button, output);
                return true;
            }
            if (!item.isSimilar(output) || item.getAmount() + output.getAmount() > item.getMaxStackSize()) {
                return false;
            }
            item.setAmount(item.getAmount() + output.getAmount());
            return true;
        }
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP || click == ClickType.WINDOW_BORDER_LEFT || click == ClickType.WINDOW_BORDER_RIGHT) {
            player.getWorld().dropItemNaturally(player.getLocation(), output);
            return true;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType() == Material.AIR) {
            player.setItemOnCursor(output);
            return true;
        }
        if (!cursor.isSimilar(output) || cursor.getAmount() + output.getAmount() > cursor.getMaxStackSize()) {
            return false;
        }
        cursor.setAmount(cursor.getAmount() + output.getAmount());
        player.setItemOnCursor(cursor);
        return true;
    }

    private int maxCrafts(JsonObject definition, CraftingInventory inventory) {
        ItemStack[] matrix = inventory.getMatrix();
        List<IngredientUse> uses = ingredientUses(definition, matrix);
        if (uses.isEmpty()) {
            return 0;
        }
        int crafts = Integer.MAX_VALUE;
        for (IngredientUse use : uses) {
            ItemStack item = matrix[use.slot()];
            if (item == null || item.getType() == Material.AIR || use.amount() <= 0) {
                return 0;
            }
            crafts = Math.min(crafts, item.getAmount() / use.amount());
        }
        return crafts == Integer.MAX_VALUE ? 0 : crafts;
    }

    private int roomForResult(InventoryClickEvent event, ItemStack result) {
        Inventory bottom = event.getView().getBottomInventory();
        int room = 0;
        for (ItemStack item : bottom.getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) {
                room += result.getMaxStackSize();
            } else if (item.isSimilar(result)) {
                room += Math.max(0, item.getMaxStackSize() - item.getAmount());
            }
        }
        return room / Math.max(1, result.getAmount());
    }

    private boolean hotbarCanTake(InventoryClickEvent event, ItemStack result) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return false;
        }
        int button = event.getHotbarButton();
        if (button < 0 || button > 8) {
            return false;
        }
        ItemStack item = player.getInventory().getItem(button);
        return item == null || item.getType() == Material.AIR || item.isSimilar(result) && item.getAmount() + result.getAmount() <= item.getMaxStackSize();
    }

    private void consumeIngredients(JsonObject definition, CraftingInventory inventory, int crafts) {
        if (crafts <= 0) {
            return;
        }
        ItemStack[] matrix = inventory.getMatrix();
        for (IngredientUse use : ingredientUses(definition, matrix)) {
            consumeAmount(matrix, use.slot(), use.amount() * crafts);
        }
        inventory.setMatrix(matrix);
    }

    private List<IngredientUse> ingredientUses(JsonObject definition, ItemStack[] matrix) {
        String type = ResourceJson.string(definition, "type", "shaped").toLowerCase(Locale.ROOT);
        return "shapeless".equals(type) ? shapelessIngredientUses(definition, matrix) : shapedIngredientUses(definition, matrix);
    }

    private List<IngredientUse> shapedIngredientUses(JsonObject definition, ItemStack[] matrix) {
        JsonArray shape = definition.has("shape") && definition.get("shape").isJsonArray() ? definition.getAsJsonArray("shape") : new JsonArray();
        JsonObject keys = ResourceJson.object(definition, "keys");
        if (shape.isEmpty() || keys == null) {
            return List.of();
        }
        List<IngredientUse> uses = new ArrayList<>();
        for (int row = 0; row < Math.min(3, shape.size()); row++) {
            String line = shape.get(row).getAsString();
            for (int column = 0; column < Math.min(3, line.length()); column++) {
                char symbol = line.charAt(column);
                if (symbol == ' ') {
                    continue;
                }
                JsonElement ingredient = keys.get(String.valueOf(symbol));
                int index = row * 3 + column;
                if (ingredient != null && index < matrix.length && itemMatches(ingredient, matrix[index])) {
                    uses.add(new IngredientUse(index, matchedAmount(ingredient, matrix[index])));
                }
            }
        }
        return uses;
    }

    private List<IngredientUse> shapelessIngredientUses(JsonObject definition, ItemStack[] matrix) {
        List<JsonElement> required = new ArrayList<>();
        for (JsonElement element : ingredients(definition)) {
            required.add(element);
        }
        List<IngredientUse> uses = new ArrayList<>();
        for (int index = 0; index < matrix.length; index++) {
            ItemStack item = matrix[index];
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            int match = -1;
            for (int i = 0; i < required.size(); i++) {
                if (itemMatches(required.get(i), item)) {
                    match = i;
                    break;
                }
            }
            if (match >= 0) {
                JsonElement ingredient = required.remove(match);
                uses.add(new IngredientUse(index, matchedAmount(ingredient, item)));
            }
        }
        return required.isEmpty() ? uses : List.of();
    }

    private void consumeAmount(ItemStack[] matrix, int index, int amount) {
        if (amount <= 0 || index < 0 || index >= matrix.length) {
            return;
        }
        ItemStack item = matrix[index];
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        int nextAmount = item.getAmount() - amount;
        if (nextAmount <= 0) {
            matrix[index] = null;
        } else {
            item.setAmount(nextAmount);
        }
    }

    private void consumeExtraSmithingIngredients(JsonObject definition, SmithingInventory inventory) {
        inventory.setInputTemplate(consumedExtra(inventory.getInputTemplate(), ResourceJson.object(definition, "template")));
        inventory.setInputEquipment(consumedExtra(inventory.getInputEquipment(), ResourceJson.object(definition, "base")));
        inventory.setInputMineral(consumedExtra(inventory.getInputMineral(), ResourceJson.object(definition, "addition")));
    }

    private void consumeExtraFurnaceInput(JsonObject definition, Object state) {
        if (!(state instanceof Furnace furnace)) {
            return;
        }
        FurnaceInventory inventory = furnace.getInventory();
        inventory.setSmelting(consumedExtra(inventory.getSmelting(), inputDefinition(definition)));
    }

    private int stonecuttingAmount(JsonObject definition, InventoryClickEvent event, StonecutterInventory inventory) {
        ItemStack input = inventory.getInputItem();
        JsonElement ingredient = inputDefinition(definition);
        if (!itemMatches(ingredient, input)) {
            return 0;
        }
        int perCraft = matchedAmount(ingredient, input);
        if (perCraft <= 0) {
            return 0;
        }
        int maxCrafts = input.getAmount() / perCraft;
        if (maxCrafts <= 0) {
            return 0;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) {
            return 0;
        }
        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            return Math.min(maxCrafts, roomForResult(event, result));
        }
        if (click == ClickType.NUMBER_KEY) {
            return hotbarCanTake(event, result) ? 1 : 0;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType() == Material.AIR) {
            return 1;
        }
        return cursor.isSimilar(result) && cursor.getAmount() + result.getAmount() <= cursor.getMaxStackSize() ? 1 : 0;
    }

    private void consumeStonecuttingInput(JsonObject definition, StonecutterInventory inventory, int crafts) {
        ItemStack input = inventory.getInputItem();
        int amount = matchedAmount(inputDefinition(definition), input) * crafts;
        if (input == null || input.getType() == Material.AIR || amount <= 0) {
            return;
        }
        int nextAmount = input.getAmount() - amount;
        if (nextAmount <= 0) {
            inventory.setInputItem(null);
        } else {
            input.setAmount(nextAmount);
            inventory.setInputItem(input);
        }
    }

    private void consumeCampfireExtra(Player player, EquipmentSlot hand, Location location, ItemStack before, int amount) {
        ItemStack current = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (current == null || current.getType() == Material.AIR || !current.isSimilar(before) || current.getAmount() != before.getAmount() - 1) {
            return;
        }
        int nextAmount = current.getAmount() - Math.max(0, amount - 1);
        if (nextAmount <= 0) {
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
            return;
        }
        current.setAmount(nextAmount);
    }

    private boolean isCampfire(Material material) {
        return material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE;
    }

    private ItemStack consumedExtra(ItemStack item, JsonElement ingredient) {
        if (item == null || item.getType() == Material.AIR) {
            return item;
        }
        int extra = Math.max(0, matchedAmount(ingredient, item) - 1);
        if (extra == 0) {
            return item;
        }
        int amount = item.getAmount() - extra;
        if (amount <= 0) {
            return null;
        }
        item.setAmount(amount);
        return item;
    }

    private int matchedAmount(JsonElement element, ItemStack item) {
        if (element == null || element.isJsonNull()) {
            return 1;
        }
        if (element.isJsonArray()) {
            for (JsonElement choice : element.getAsJsonArray()) {
                if (itemMatches(choice, item)) {
                    return matchedAmount(choice, item);
                }
            }
            return 1;
        }
        if (!element.isJsonObject()) {
            return 1;
        }
        return Math.max(1, ResourceJson.integer(element.getAsJsonObject(), "amount", 1));
    }

    private JsonElement inputDefinition(JsonObject definition) {
        if (definition.has("input")) {
            return definition.get("input");
        }
        if (definition.has("ingredient")) {
            return definition.get("ingredient");
        }
        JsonArray ingredients = ingredients(definition);
        return ingredients.isEmpty() ? null : ingredients.get(0);
    }

    private boolean itemMatchesIgnoringAmount(JsonElement element, ItemStack item) {
        if (element == null || element.isJsonNull()) {
            return item != null && item.getType() != Material.AIR;
        }
        if (element.isJsonArray()) {
            for (JsonElement choice : element.getAsJsonArray()) {
                if (itemMatchesIgnoringAmount(choice, item)) {
                    return true;
                }
            }
            return false;
        }
        if (!element.isJsonObject()) {
            return itemMatches(element, item);
        }
        JsonObject copy = element.getAsJsonObject().deepCopy();
        copy.remove("amount");
        return itemMatches(copy, item);
    }

    private boolean itemMatches(JsonElement element, ItemStack item) {
        if (element == null || element.isJsonNull()) {
            return true;
        }
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        if (element.isJsonArray()) {
            for (JsonElement choice : element.getAsJsonArray()) {
                if (itemMatches(choice, item)) {
                    return true;
                }
            }
            return false;
        }
        if (!element.isJsonObject()) {
            String reference = element.getAsString();
            if (isCustomItemReference(reference) && customContentService != null) {
                return customContentService.matchesItemReference(item, reference);
            }
            return item.getType() == material(reference);
        }
        JsonObject object = element.getAsJsonObject();
        String reference = itemReference(object);
        if (!reference.isBlank() && customContentService != null && !customContentService.matchesItemReference(item, reference)) {
            return false;
        }
        String contentId = contentId(object);
        if (!contentId.isBlank() && customContentService != null) {
            String identified = customContentService.identifyItem(item);
            if (!contentId.equalsIgnoreCase(identified)) {
                return false;
            }
        }
        String provider = ResourceJson.string(object, "provider", "");
        String externalId = ResourceJson.string(object, "externalId", ResourceJson.string(object, "external_id", ResourceJson.string(object, "nexo", "")));
        if (provider.isBlank() && !externalId.isBlank()) {
            provider = "nexo";
        }
        if (!provider.isBlank() && !externalId.isBlank() && customContentService != null
            && !customContentService.matchesItemReference(item, "provider:" + provider + ':' + externalId)) {
            return false;
        }
        String materialName = ResourceJson.string(object, "material", ResourceJson.string(object, "item", ""));
        if (isCustomItemReference(materialName)) {
            materialName = "";
        }
        if (!materialName.isBlank() && item.getType() != material(materialName)) {
            return false;
        }
        Set<Material> tagged = tagMaterials(ResourceJson.string(object, "tag", ""));
        if (!tagged.isEmpty() && !tagged.contains(item.getType())) {
            return false;
        }
        int amount = ResourceJson.integer(object, "amount", 1);
        if (amount > 1 && item.getAmount() < amount) {
            return false;
        }
        return metaMatches(item, object);
    }

    private boolean metaMatches(ItemStack item, JsonObject object) {
        ItemMeta meta = item.getItemMeta();
        int modelData = ResourceJson.integer(object, "customModelData", -1);
        if (modelData >= 0 && (meta == null || !meta.hasCustomModelData() || meta.getCustomModelData() != modelData)) {
            return false;
        }
        JsonObject pdc = ResourceJson.object(object, "pdc");
        if (pdc == null || pdc.size() == 0) {
            pdc = ResourceJson.object(object, "persistentData");
        }
        if (pdc == null || pdc.size() == 0) {
            return true;
        }
        if (meta == null) {
            return false;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        for (String keyText : pdc.keySet()) {
            NamespacedKey key = namespacedKey(keyText);
            if (key == null) {
                return false;
            }
            String expected = pdc.get(keyText).getAsString();
            String actual = container.get(key, PersistentDataType.STRING);
            if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private void applyItemMeta(ItemStack item, JsonObject object) {
        if (item.getType() == Material.AIR) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        int modelData = ResourceJson.integer(object, "customModelData", -1);
        if (modelData >= 0) {
            meta.setCustomModelData(modelData);
        }
        JsonObject pdc = ResourceJson.object(object, "pdc");
        if (pdc == null || pdc.size() == 0) {
            pdc = ResourceJson.object(object, "persistentData");
        }
        if (pdc != null) {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            for (String keyText : pdc.keySet()) {
                NamespacedKey key = namespacedKey(keyText);
                if (key != null) {
                    container.set(key, PersistentDataType.STRING, pdc.get(keyText).getAsString());
                }
            }
        }
        item.setItemMeta(meta);
    }

    private boolean inventoryContains(Player player, JsonObject rule) {
        if (player == null || rule == null || rule.size() == 0) {
            return true;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (itemMatches(rule, item)) {
                return true;
            }
        }
        return false;
    }

    private boolean cooldownReady(String recipeId, Player player, int seconds) {
        return cooldownReady(recipeId, player, seconds, true);
    }

    private boolean cooldownReady(String recipeId, Player player, int seconds, boolean commit) {
        String key = recipeId + ':' + player.getUniqueId();
        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(key, 0L);
        if (readyAt > now) {
            return false;
        }
        if (commit) {
            cooldowns.put(key, now + seconds * 1000L);
        }
        return true;
    }

    private ItemStack dynamicOutput(JsonObject definition, Player player) {
        JsonArray outputs = definition.has("dynamicOutputs") && definition.get("dynamicOutputs").isJsonArray() ? definition.getAsJsonArray("dynamicOutputs") : new JsonArray();
        for (JsonElement element : outputs) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject candidate = element.getAsJsonObject();
            if (dynamicConditionsPass(candidate, player)) {
                ItemStack item = item(ResourceJson.object(candidate, "output"));
                if (item.getType() != Material.AIR) {
                    return item;
                }
            }
        }
        return null;
    }

    private boolean dynamicConditionsPass(JsonObject candidate, Player player) {
        JsonObject conditions = ResourceJson.object(candidate, "conditions");
        if (conditions == null || conditions.size() == 0) {
            return true;
        }
        JsonObject wrapped = new JsonObject();
        wrapped.add("conditions", conditions);
        wrapped.addProperty("id", ResourceJson.string(candidate, "id", "dynamic_output"));
        return conditionsPass(wrapped, player);
    }

    private boolean flowPredicate(JsonObject definition, Player player) {
        String flowId = ResourceJson.string(ResourceJson.object(definition, "conditions"), "flowPredicate", "");
        JsonObject conditions = ResourceJson.object(definition, "conditions");
        Map<String, Object> vars = new HashMap<>();
        vars.put("event.recipe", recipeId(definition));
        boolean flowPass = true;
        if (!flowId.isBlank() && flowStorage != null && flowExecutor != null) {
            flowPass = FlowPredicateSupport.evaluate(flowStorage, flowExecutor, flowId, player, null, vars);
        }
        return flowPass && FunctionCallSupport.evaluate(flowStorage, flowExecutor, ResourceJson.object(conditions, "predicate"), player, null, vars);
    }

    private void dispatchFlow(JsonObject definition, String trigger, Player player, Event event, Map<String, Object> vars) {
        Map<String, Object> eventVars = new HashMap<>();
        if (vars != null) {
            eventVars.putAll(vars);
        }
        eventVars.put("event.trigger", "recipe_" + trigger);
        FunctionCallSupport.execute(flowStorage, flowExecutor, ResourceJson.object(definition, trigger + "Action"), player, event, eventVars);
        FunctionCallSupport.execute(flowStorage, flowExecutor, ResourceJson.object(definition, trigger + "Function"), player, event, eventVars);
        String flowId = ResourceJson.string(definition, trigger + "Flow", ResourceJson.string(definition, "flowId", ""));
        if (flowId.isBlank() || flowStorage == null || flowExecutor == null) {
            return;
        }
        FlowGraph graph = flowStorage.getGraph(flowId);
        if (graph == null) {
            return;
        }
        flowExecutor.execute(graph, findStartNode(graph), player, event, eventVars);
    }

    private String findStartNode(FlowGraph graph) {
        return graph != null && graph.getNodes() != null ? graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null) : null;
    }

    private String recipeId(JsonObject definition) {
        return ResourceJson.string(definition, "id", "recipe");
    }

    private String contentId(JsonObject object) {
        return ResourceJson.string(object, "contentId", ResourceJson.string(object, "customContentId", ResourceJson.string(object, "customContent", "")));
    }

    private boolean isCustomItemReference(String reference) {
        if (reference == null) {
            return false;
        }
        String normalized = reference.toLowerCase(Locale.ROOT);
        return normalized.startsWith("content:") || normalized.startsWith("provider:");
    }

    private NamespacedKey namespacedKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.contains(":") ? NamespacedKey.fromString(value.toLowerCase(Locale.ROOT)) : NamespacedKey.minecraft(value.toLowerCase(Locale.ROOT));
    }

    private boolean weatherMatches(Player player, String weather) {
        String normalized = weather.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "clear" -> !player.getWorld().hasStorm() && !player.getWorld().isThundering();
            case "rain", "storm" -> player.getWorld().hasStorm();
            case "thunder" -> player.getWorld().isThundering();
            default -> true;
        };
    }

    private JsonObject definitionFor(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed)) {
            return null;
        }
        for (String id : storage.listIds(ReSyncResourceCatalog.RECIPE_DEFINITION)) {
            JsonObject definition = storage.get(ReSyncResourceCatalog.RECIPE_DEFINITION, id);
            if (key(definition).equals(keyed.getKey())) {
                return definition;
            }
        }
        return null;
    }

    private NamespacedKey key(JsonObject definition) {
        String id = ResourceJson.string(definition, "id", "recipe");
        return new NamespacedKey(context.getPlugin(), id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_"));
    }

    private record IngredientUse(int slot, int amount) {
    }
}
