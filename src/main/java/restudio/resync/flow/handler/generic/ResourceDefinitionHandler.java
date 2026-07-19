package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public final class ResourceDefinitionHandler implements NodeHandler {
    private final Gson gson = new Gson();
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ResourceDefinitionHandler() {
        operations.put("build_gui", this::buildGui);
        operations.put("build_scoreboard", this::buildScoreboard);
        operations.put("build_tab", this::buildTab);
        operations.put("build_custom_content", this::buildCustomContent);
        operations.put("build_trade", this::buildTrade);
        operations.put("build_trade_profile", this::buildTradeProfile);
        operations.put("build_loot_entry", this::buildLootEntry);
        operations.put("build_loot_pool", this::buildLootPool);
        operations.put("build_loot_table", this::buildLootTable);
        operations.put("build_npc", this::buildNpc);
        operations.put("build_dialog", this::buildDialog);
        operations.put("build_advancement_tree", this::buildAdvancementTree);
        operations.put("build_recipe_ingredient", this::buildRecipeIngredient);
        operations.put("build_recipe", this::buildRecipe);
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ResourceDefinitionHandler", this);
    }

    @Override
    public void execute(FlowContext context, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> handler = operation != null ? operations.get(operation) : null;
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported resource definition operation: " + operation);
        }
        try {
            handler.accept(context, node);
            Object value = resultValue(context, node, operation);
            setResult(context, node, FlowOperationResult.success(value));
            context.triggerOutput("flow");
        } catch (IllegalArgumentException | ArithmeticException exception) {
            String message = exception.getMessage() != null && !exception.getMessage().isBlank()
                ? exception.getMessage() : "Resource definition is invalid";
            setResult(context, node, FlowOperationResult.failure("RESOURCE_DEFINITION_INVALID", message, Map.of("operation", operation)));
            context.triggerOutput("failed");
        }
    }

    @Override
    public Set<String> getSupportedOperations() {
        return Set.copyOf(operations.keySet());
    }

    private Object resultValue(FlowContext context, FlowNode node, String operation) {
        String output = switch (operation) {
            case "build_trade" -> "trade";
            case "build_loot_entry" -> "entry";
            case "build_loot_pool" -> "pool";
            case "build_recipe_ingredient" -> "ingredient";
            default -> "definition";
        };
        return context.getOutput(node, output);
    }

    private void setResult(FlowContext context, FlowNode node, FlowOperationResult<?> result) {
        context.setOutput(node, "result", result);
        context.setOutput(node, "success", result.success());
        context.setOutput(node, "error_code", result.errorCode());
        context.setOutput(node, "message", result.message());
    }

    private void buildGui(FlowContext context, FlowNode node) {
        String id = requiredId(context, node);
        GuiDefinition definition = new GuiDefinition(id, text(context, node, "title", id), Math.clamp(integer(context, node, "rows", 3), 1, 6));
        definition.setExtendToPlayerInventory(bool(context, node, "extend_to_inventory", false));
        definition.setClickSound(soundId(context.getInputValue(node, "click_sound")));
        definition.setOpenFlowId(text(context, node, "open_flow", ""));
        definition.setCloseFlowId(text(context, node, "close_flow", ""));
        context.setOutput(node, "definition", definition);
    }

    private void buildScoreboard(FlowContext context, FlowNode node) {
        String id = requiredId(context, node);
        ScoreboardDefinition definition = new ScoreboardDefinition(id, text(context, node, "title", id));
        definition.setObjectiveId(text(context, node, "objective_id", id));
        definition.setLines(context.getRepeatableInputValues(node, "line", String.class));
        context.setOutput(node, "definition", definition);
    }

    private void buildTab(FlowContext context, FlowNode node) {
        TabDefinition definition = new TabDefinition(requiredId(context, node));
        definition.setHeader(text(context, node, "header", ""));
        definition.setEntryFormat(text(context, node, "entry_format", "%player%"));
        definition.setFooter(text(context, node, "footer", ""));
        context.setOutput(node, "definition", definition);
    }

    private void buildCustomContent(FlowContext context, FlowNode node) {
        String id = requiredId(context, node);
        CustomContentDefinition definition = new CustomContentDefinition();
        definition.setId(id);
        definition.setFlowId(text(context, node, "flow_id", id));
        definition.setType(text(context, node, "content_type", "item"));
        definition.setDisplayName(text(context, node, "display_name", id));
        definition.setProvider(text(context, node, "provider", "vanilla"));
        definition.setExternalId(text(context, node, "external_id", ""));
        definition.setMaterial(materialId(context.getInputValue(node, "material"), "minecraft:stick"));
        definition.setLore(context.getRepeatableInputValues(node, "lore", String.class));
        definition.setTags(context.getRepeatableInputValues(node, "tag", String.class));
        context.setOutput(node, "definition", definition);
    }

    private void buildTrade(FlowContext context, FlowNode node) {
        JsonObject trade = new JsonObject();
        trade.addProperty("cost", materialId(context.getInputValue(node, "cost"), "minecraft:emerald"));
        trade.addProperty("costAmount", Math.max(1, integer(context, node, "cost_amount", 1)));
        String secondCost = materialId(context.getInputValue(node, "second_cost"), "");
        if (!secondCost.isBlank()) {
            trade.addProperty("cost2", secondCost);
            trade.addProperty("cost2Amount", Math.max(1, integer(context, node, "second_cost_amount", 1)));
        }
        trade.addProperty("result", materialId(context.getInputValue(node, "result"), "minecraft:stone"));
        trade.addProperty("resultAmount", Math.max(1, integer(context, node, "result_amount", 1)));
        trade.addProperty("maxUses", Math.max(1, integer(context, node, "max_uses", 12)));
        trade.addProperty("experience", Math.max(0, integer(context, node, "experience", 0)));
        trade.addProperty("priceMultiplier", Math.max(0.0, decimal(context, node, "price_multiplier", 0.05)));
        context.setOutput(node, "trade", trade);
    }

    private void buildTradeProfile(FlowContext context, FlowNode node) {
        JsonObject profile = identity(context, node);
        profile.addProperty("enabled", bool(context, node, "enabled", true));
        profile.addProperty("displayName", text(context, node, "display_name", requiredId(context, node)));
        profile.addProperty("profession", text(context, node, "profession", "none"));
        profile.add("offers", array(context.getRepeatableInputValues(node, "offer", JsonObject.class)));
        context.setOutput(node, "definition", profile);
    }

    private void buildLootEntry(FlowContext context, FlowNode node) {
        JsonObject entry = new JsonObject();
        entry.addProperty("item", materialId(context.getInputValue(node, "item"), "minecraft:stone"));
        entry.addProperty("weight", Math.max(1, integer(context, node, "weight", 1)));
        entry.addProperty("chance", Math.clamp(decimal(context, node, "chance", 100.0), 0.0, 100.0));
        int minimum = Math.max(0, integer(context, node, "min_amount", 1));
        entry.addProperty("minAmount", minimum);
        entry.addProperty("maxAmount", Math.max(minimum, integer(context, node, "max_amount", minimum)));
        context.setOutput(node, "entry", entry);
    }

    private void buildLootPool(FlowContext context, FlowNode node) {
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", Math.max(1, integer(context, node, "rolls", 1)));
        pool.add("entries", array(context.getRepeatableInputValues(node, "entry", JsonObject.class)));
        context.setOutput(node, "pool", pool);
    }

    private void buildLootTable(FlowContext context, FlowNode node) {
        JsonObject table = identity(context, node);
        table.addProperty("enabled", bool(context, node, "enabled", true));
        table.add("pools", array(context.getRepeatableInputValues(node, "pool", JsonObject.class)));
        context.setOutput(node, "definition", table);
    }

    private void buildNpc(FlowContext context, FlowNode node) {
        JsonObject npc = identity(context, node);
        npc.addProperty("enabled", bool(context, node, "enabled", true));
        npc.addProperty("displayName", text(context, node, "display_name", requiredId(context, node)));
        npc.addProperty("entityType", entityTypeId(context.getInputValue(node, "entity_type")));
        npc.addProperty("spawnMode", text(context, node, "spawn_mode", "manual").toLowerCase(Locale.ROOT));
        npc.addProperty("ai", bool(context, node, "ai", false));
        npc.addProperty("gravity", bool(context, node, "gravity", true));
        npc.addProperty("invulnerable", bool(context, node, "invulnerable", true));
        npc.addProperty("followPlayer", bool(context, node, "follow_player", false));
        npc.addProperty("followRange", decimal(context, node, "follow_range", 12.0));
        Location location = context.getInputValue(node, "location", Location.class, null);
        if (location != null && location.getWorld() != null) {
            JsonObject serializedLocation = new JsonObject();
            serializedLocation.addProperty("world", location.getWorld().getName());
            serializedLocation.addProperty("x", location.getX());
            serializedLocation.addProperty("y", location.getY());
            serializedLocation.addProperty("z", location.getZ());
            serializedLocation.addProperty("yaw", location.getYaw());
            serializedLocation.addProperty("pitch", location.getPitch());
            npc.add("location", serializedLocation);
        }
        JsonObject skin = new JsonObject();
        addOptionalProperty(skin, "username", text(context, node, "skin_username", ""));
        addOptionalProperty(skin, "uuid", text(context, node, "skin_uuid", ""));
        addOptionalProperty(skin, "texture", text(context, node, "skin_texture", ""));
        addOptionalProperty(skin, "signature", text(context, node, "skin_signature", ""));
        if (!skin.isEmpty()) {
            npc.add("skin", skin);
        }
        JsonObject equipment = new JsonObject();
        addOptionalProperty(equipment, "mainHand", text(context, node, "main_hand", ""));
        addOptionalProperty(equipment, "offHand", text(context, node, "off_hand", ""));
        addOptionalProperty(equipment, "helmet", text(context, node, "helmet", ""));
        addOptionalProperty(equipment, "chestplate", text(context, node, "chestplate", ""));
        addOptionalProperty(equipment, "leggings", text(context, node, "leggings", ""));
        addOptionalProperty(equipment, "boots", text(context, node, "boots", ""));
        if (!equipment.isEmpty()) {
            npc.add("equipment", equipment);
        }
        JsonObject hooks = new JsonObject();
        addHook(hooks, "spawnAction", text(context, node, "spawn_flow", ""));
        addHook(hooks, "interactAction", text(context, node, "interact_flow", ""));
        addHook(hooks, "rightClickAction", text(context, node, "right_click_flow", ""));
        addHook(hooks, "leftClickAction", text(context, node, "left_click_flow", ""));
        addHook(hooks, "damageAction", text(context, node, "damage_flow", ""));
        addHook(hooks, "deathAction", text(context, node, "death_flow", ""));
        addHook(hooks, "despawnAction", text(context, node, "despawn_flow", ""));
        npc.add("hooks", hooks);
        String tradeProfile = text(context, node, "trade_profile", "");
        String dialog = text(context, node, "dialog", "");
        if (!tradeProfile.isBlank() && !dialog.isBlank()) {
            throw new IllegalArgumentException("NPC interaction cannot open both a dialog and a trade profile");
        }
        if (!tradeProfile.isBlank()) {
            npc.addProperty("tradeProfile", tradeProfile);
        }
        if (!dialog.isBlank()) {
            npc.addProperty("dialog", dialog);
        }
        String lootTable = text(context, node, "loot_table", "");
        if (!lootTable.isBlank()) {
            npc.addProperty("lootTable", lootTable);
        }
        context.setOutput(node, "definition", npc);
    }

    private void buildDialog(FlowContext context, FlowNode node) {
        JsonObject dialog = identity(context, node);
        dialog.addProperty("enabled", bool(context, node, "enabled", true));
        dialog.addProperty("displayName", text(context, node, "display_name", requiredId(context, node)));
        dialog.addProperty("title", text(context, node, "title", requiredId(context, node)));
        dialog.addProperty("type", text(context, node, "dialog_type", "minecraft:notice"));
        JsonArray body = new JsonArray();
        String contents = text(context, node, "body", "");
        if (!contents.isBlank()) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "minecraft:plain_message");
            message.addProperty("contents", contents);
            message.addProperty("width", Math.clamp(integer(context, node, "body_width", 200), 1, 1024));
            body.add(message);
        }
        dialog.add("body", body);
        context.setOutput(node, "definition", dialog);
    }

    private void addHook(JsonObject hooks, String key, String flowId) {
        if (flowId != null && !flowId.isBlank()) {
            hooks.addProperty(key, flowId);
        }
    }

    private void addOptionalProperty(JsonObject target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.addProperty(key, value);
        }
    }

    private void buildAdvancementTree(FlowContext context, FlowNode node) {
        String id = requiredId(context, node);
        JsonObject tree = identity(context, node);
        tree.addProperty("displayName", text(context, node, "display_name", id));
        JsonObject root = new JsonObject();
        root.addProperty("enabled", true);
        JsonObject display = new JsonObject();
        display.addProperty("title", text(context, node, "title", id));
        display.addProperty("description", text(context, node, "description", ""));
        display.addProperty("icon", materialId(context.getInputValue(node, "icon"), "minecraft:stone"));
        display.addProperty("frame", text(context, node, "frame", "task"));
        display.addProperty("showToast", bool(context, node, "show_toast", true));
        display.addProperty("announceToChat", bool(context, node, "announce_to_chat", false));
        display.addProperty("hidden", bool(context, node, "hidden", false));
        root.add("display", display);
        JsonObject criterion = new JsonObject();
        criterion.addProperty("trigger", "impossible");
        JsonObject criteria = new JsonObject();
        criteria.add("requirement", criterion);
        root.add("criteria", criteria);
        JsonArray requirement = new JsonArray();
        requirement.add("requirement");
        JsonArray requirements = new JsonArray();
        requirements.add(requirement);
        root.add("requirements", requirements);
        JsonObject nodes = new JsonObject();
        nodes.add("root", root);
        tree.add("nodes", nodes);
        context.setOutput(node, "definition", tree);
    }

    private void buildRecipeIngredient(FlowContext context, FlowNode node) {
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("material", materialId(context.getInputValue(node, "material"), "minecraft:stone"));
        ingredient.addProperty("amount", Math.max(1, integer(context, node, "amount", 1)));
        context.setOutput(node, "ingredient", ingredient);
    }

    private void buildRecipe(FlowContext context, FlowNode node) {
        JsonObject recipe = identity(context, node);
        recipe.addProperty("enabled", bool(context, node, "enabled", true));
        String recipeType = text(context, node, "recipe_type", "shapeless").toLowerCase(Locale.ROOT);
        recipe.addProperty("type", recipeType);
        JsonObject output = new JsonObject();
        output.addProperty("material", materialId(context.getInputValue(node, "result"), "minecraft:stone"));
        output.addProperty("amount", Math.max(1, integer(context, node, "result_amount", 1)));
        recipe.add("output", output);
        List<JsonObject> ingredients = context.getRepeatableInputValues(node, "ingredient", JsonObject.class);
        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException("Recipe requires at least one ingredient");
        }
        if ("shaped".equals(recipeType)) {
            addShapedRecipe(recipe, ingredients);
        } else if ("smithing_transform".equals(recipeType) || "smithing_trim".equals(recipeType)) {
            if (ingredients.size() < 3) {
                throw new IllegalArgumentException("Smithing recipe requires template, base, and addition ingredients");
            }
            recipe.add("template", ingredients.get(0).deepCopy());
            recipe.add("base", ingredients.get(1).deepCopy());
            recipe.add("addition", ingredients.get(2).deepCopy());
        } else {
            recipe.add("ingredients", array(ingredients));
        }
        context.setOutput(node, "definition", recipe);
    }

    private void addShapedRecipe(JsonObject recipe, List<JsonObject> ingredients) {
        if (ingredients.size() > 9) {
            throw new IllegalArgumentException("Shaped recipe supports at most nine ingredients");
        }
        int width = Math.min(3, (int) Math.ceil(Math.sqrt(ingredients.size())));
        int height = (int) Math.ceil((double) ingredients.size() / width);
        JsonArray shape = new JsonArray();
        JsonObject keys = new JsonObject();
        for (int row = 0; row < height; row++) {
            StringBuilder line = new StringBuilder(width);
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                if (index >= ingredients.size()) {
                    line.append(' ');
                    continue;
                }
                char symbol = (char) ('A' + index);
                line.append(symbol);
                keys.add(String.valueOf(symbol), ingredients.get(index).deepCopy());
            }
            shape.add(line.toString());
        }
        recipe.add("shape", shape);
        recipe.add("keys", keys);
    }

    private JsonObject identity(FlowContext context, FlowNode node) {
        JsonObject value = new JsonObject();
        value.addProperty("id", requiredId(context, node));
        return value;
    }

    private String requiredId(FlowContext context, FlowNode node) {
        String value = text(context, node, "id", "");
        String id = value != null ? value.strip() : "";
        if (id.isBlank()) {
            throw new IllegalArgumentException("Resource ID is required");
        }
        return id;
    }

    private String text(FlowContext context, FlowNode node, String pin, String fallback) {
        return context.getInputValue(node, pin, String.class, fallback);
    }

    private int integer(FlowContext context, FlowNode node, String pin, int fallback) {
        Number value = context.getInputValue(node, pin, Number.class, fallback);
        return value != null ? value.intValue() : fallback;
    }

    private double decimal(FlowContext context, FlowNode node, String pin, double fallback) {
        Number value = context.getInputValue(node, pin, Number.class, fallback);
        return value != null ? value.doubleValue() : fallback;
    }

    private boolean bool(FlowContext context, FlowNode node, String pin, boolean fallback) {
        return context.getInputValue(node, pin, Boolean.class, fallback);
    }

    private String materialId(Object value, String fallback) {
        if (value instanceof Material material) {
            return material.getKey().toString();
        }
        String id = value != null ? String.valueOf(value).strip() : "";
        if (id.isBlank()) {
            return fallback;
        }
        return id.contains(":") ? id.toLowerCase() : "minecraft:" + id.toLowerCase();
    }

    private String entityTypeId(Object value) {
        if (value instanceof EntityType entityType) {
            return entityType.getKey().toString();
        }
        String id = value != null ? String.valueOf(value).strip() : "villager";
        return id.contains(":") ? id.toLowerCase() : "minecraft:" + id.toLowerCase();
    }

    private String soundId(Object value) {
        if (value instanceof Sound sound) {
            return sound.name();
        }
        String id = value != null ? String.valueOf(value).strip() : "";
        return id.isBlank() ? "UI_BUTTON_CLICK" : id.toUpperCase().replace('.', '_').replace(':', '_');
    }

    private JsonArray array(Object value) {
        JsonArray result = new JsonArray();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                add(result, item);
            }
        } else if (value != null) {
            add(result, value);
        }
        return result;
    }

    private void add(JsonArray array, Object value) {
        JsonElement element = value instanceof JsonElement json ? json.deepCopy() : gson.toJsonTree(value);
        array.add(element);
    }
}
