package restudio.resync.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import restudio.resync.customcontent.CustomContentService;

import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AdvancementPredicateEvaluator {
    private final CustomContentService customContent;

    public AdvancementPredicateEvaluator(CustomContentService customContent) {
        this.customContent = customContent;
    }

    public boolean matches(Player player, JsonObject conditions, Map<String, Object> inputs) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        if (player == null) {
            return false;
        }
        try {
            for (Map.Entry<String, JsonElement> entry : conditions.entrySet()) {
                if (!matches(player, entry.getKey(), entry.getValue(), inputs)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean matches(Player player, String key, JsonElement value, Map<String, Object> inputs) {
        return switch (key) {
            case "op" -> player.isOp() == value.getAsBoolean();
            case "sneaking" -> player.isSneaking() == value.getAsBoolean();
            case "sprinting" -> player.isSprinting() == value.getAsBoolean();
            case "flying" -> player.isFlying() == value.getAsBoolean();
            case "onGround" -> player.isOnGround() == value.getAsBoolean();
            case "gamemode" -> player.getGameMode() == gameMode(value.getAsString());
            case "permission" -> !strings(value).isEmpty() && strings(value).stream().allMatch(player::hasPermission);
            case "levelMin" -> player.getLevel() >= value.getAsInt();
            case "levelMax" -> player.getLevel() <= value.getAsInt();
            case "foodMin" -> player.getFoodLevel() >= value.getAsInt();
            case "foodMax" -> player.getFoodLevel() <= value.getAsInt();
            case "healthMin" -> player.getHealth() >= value.getAsDouble();
            case "healthMax" -> player.getHealth() <= value.getAsDouble();
            case "experienceMin" -> player.getTotalExperience() >= value.getAsInt();
            case "experienceMax" -> player.getTotalExperience() <= value.getAsInt();
            case "world" -> containsIgnoreCase(strings(value), player.getWorld().getName());
            case "biome" -> containsIgnoreCase(strings(value), player.getLocation().getBlock().getBiome().getKey().toString());
            case "heldItem" -> item(player.getInventory().getItemInMainHand(), value);
            case "offhandItem" -> item(player.getInventory().getItemInOffHand(), value);
            case "inventoryItem" -> Arrays.stream(player.getInventory().getContents()).anyMatch(item -> item(item, value));
            case "effect" -> !strings(value).isEmpty() && strings(value).stream().allMatch(effect -> effect(effect) != null && player.hasPotionEffect(effect(effect)));
            case "weather" -> weather(player, value.getAsString());
            case "input" -> input(value, inputs);
            case "item" -> inputItem(value, inputs);
            case "block" -> inputBlock(value, inputs);
            case "entity" -> inputEntity(value, inputs);
            case "dimension" -> inputText("event.to", value.getAsString(), inputs);
            case "recipe" -> inputText("event.recipe", value.getAsString(), inputs);
            case "changedEffect" -> inputText("event.effect", value.getAsString(), inputs);
            case "damageMin" -> inputNumber("event.damage", inputs) >= value.getAsDouble();
            case "damageMax" -> inputNumber("event.damage", inputs) <= value.getAsDouble();
            case "distanceMin" -> inputNumber("event.distance", inputs) >= value.getAsDouble();
            case "distanceMax" -> inputNumber("event.distance", inputs) <= value.getAsDouble();
            case "allOf" -> value.isJsonArray() && value.getAsJsonArray().asList().stream().allMatch(element -> element.isJsonObject() && matches(player, element.getAsJsonObject(), inputs));
            case "anyOf" -> value.isJsonArray() && value.getAsJsonArray().asList().stream().anyMatch(element -> element.isJsonObject() && matches(player, element.getAsJsonObject(), inputs));
            case "not" -> value.isJsonObject() && !matches(player, value.getAsJsonObject(), inputs);
            default -> false;
        };
    }

    private boolean input(JsonElement value, Map<String, Object> inputs) {
        if (!value.isJsonObject() || inputs == null) {
            return false;
        }
        JsonObject input = value.getAsJsonObject();
        if (!input.has("key") || !input.has("value")) {
            return false;
        }
        Object actual = inputs.get(input.get("key").getAsString());
        return actual != null && String.valueOf(actual).equalsIgnoreCase(input.get("value").getAsString());
    }

    private GameMode gameMode(String value) {
        try {
            return GameMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private PotionEffectType effect(String value) {
        return PotionEffectType.getByName(value.toUpperCase(Locale.ROOT));
    }

    private boolean material(ItemStack item, String value) {
        if (customContent != null) {
            return customContent.matchesItemReference(item, value);
        }
        Material expected = Material.matchMaterial(value);
        return expected != null && item != null && item.getType() == expected;
    }

    private boolean inputItem(JsonElement value, Map<String, Object> inputs) {
        return inputs != null && inputs.get("event.item") instanceof ItemStack item && item(item, value);
    }

    private boolean inputBlock(JsonElement value, Map<String, Object> inputs) {
        String reference = reference(value);
        if (reference.isBlank()) {
            return false;
        }
        if (customContent != null && inputs != null && inputs.get("event.block") instanceof Block block) {
            return customContent.matchesBlockReference(block.getLocation(), reference);
        }
        Material expected = Material.matchMaterial(reference);
        return expected != null && inputs != null && inputs.get("event.block") instanceof Block block && block.getType() == expected;
    }

    private boolean inputEntity(JsonElement value, Map<String, Object> inputs) {
        String reference = reference(value);
        String type = reference.contains(":") ? reference.substring(reference.indexOf(':') + 1) : reference;
        return !type.isBlank() && inputs != null && inputs.get("event.entity") instanceof Entity entity && entity.getType().name().equalsIgnoreCase(type);
    }

    private boolean inputText(String key, String value, Map<String, Object> inputs) {
        return inputs != null && inputs.get(key) != null && String.valueOf(inputs.get(key)).equalsIgnoreCase(value);
    }

    private boolean item(ItemStack item, JsonElement condition) {
        String reference = reference(condition);
        if (reference.isBlank() || !material(item, reference)) {
            return false;
        }
        return !condition.isJsonObject() || !condition.getAsJsonObject().has("amountMin") || item.getAmount() >= condition.getAsJsonObject().get("amountMin").getAsInt();
    }

    private String reference(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        if (!value.isJsonObject()) {
            return "";
        }
        JsonObject object = value.getAsJsonObject();
        for (String key : new String[]{"reference", "item", "block", "entity", "value"}) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                return object.get(key).getAsString();
            }
        }
        return "";
    }

    private List<String> strings(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return List.of(value.getAsString());
        }
        if (!value.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                return List.of();
            }
            values.add(element.getAsString());
        }
        return values;
    }

    private boolean containsIgnoreCase(List<String> values, String actual) {
        return !values.isEmpty() && values.stream().anyMatch(value -> value.equalsIgnoreCase(actual));
    }

    private double inputNumber(String key, Map<String, Object> inputs) {
        Object value = inputs != null ? inputs.get(key) : null;
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    private boolean weather(Player player, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "clear" -> !player.getWorld().hasStorm() && !player.getWorld().isThundering();
            case "rain", "storm" -> player.getWorld().hasStorm();
            case "thunder" -> player.getWorld().isThundering();
            default -> false;
        };
    }
}
