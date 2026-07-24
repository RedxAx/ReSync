package restudio.resync.flow.contract;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CustomContentContract {
    public static final String ITEM_NODE = "custom_content.item";
    public static final String BLOCK_NODE = "custom_content.block";
    public static final String ARMOR_NODE = "custom_content.armor";
    public static final String PROJECTILE_NODE = "custom_content.projectile";
    public static final String FLOW_BRANCHES_KEY = "__flow_branches";

    private static final Map<String, List<Trigger>> TRIGGERS = Map.of(
        "item", List.of(
            new Trigger("use", "item.use"),
            new Trigger("left_click", "item.left_click"),
            new Trigger("right_click", "item.right_click"),
            new Trigger("hit_entity", "item.hit_entity"),
            new Trigger("damage_entity", "item.damage_entity"),
            new Trigger("break_block", "item.break_block"),
            new Trigger("consume", "item.consume"),
            new Trigger("drop", "item.drop"),
            new Trigger("pickup", "item.pickup"),
            new Trigger("while_holding", "item.while_holding")
        ),
        "block", List.of(
            new Trigger("place", "block.place"),
            new Trigger("break", "block.break"),
            new Trigger("interact", "block.interact"),
            new Trigger("step_on", "block.step_on"),
            new Trigger("nearby_player", "block.nearby_player"),
            new Trigger("redstone", "block.redstone"),
            new Trigger("tick", "block.tick")
        ),
        "armor", List.of(
            new Trigger("equip", "armor.equip"),
            new Trigger("unequip", "armor.unequip"),
            new Trigger("damaged", "armor.damaged"),
            new Trigger("tick", "armor.tick"),
            new Trigger("while_holding", "armor.while_holding"),
            new Trigger("full_set", "armor.full_set"),
            new Trigger("full_set_tick", "armor.full_set_tick")
        ),
        "projectile", List.of(
            new Trigger("fire", "projectile.fire"),
            new Trigger("hit", "projectile.hit")
        )
    );

    private CustomContentContract() {
    }

    public static String typeFromNode(String nodeType) {
        return switch (nodeType == null ? "" : nodeType) {
            case ITEM_NODE -> "item";
            case BLOCK_NODE -> "block";
            case ARMOR_NODE -> "armor";
            case PROJECTILE_NODE -> "projectile";
            default -> null;
        };
    }

    public static String nodeType(String type) {
        return switch (normalizeType(type)) {
            case "block" -> BLOCK_NODE;
            case "armor" -> ARMOR_NODE;
            case "projectile" -> PROJECTILE_NODE;
            default -> ITEM_NODE;
        };
    }

    public static String contentFlowId(String type, String contentId) {
        String id = contentId != null ? contentId : "content";
        return "content." + normalizeType(type) + "." + id;
    }

    public static String triggerForPin(String type, String pin) {
        return triggersForType(type).stream().filter(trigger -> trigger.pin().equals(pin)).map(Trigger::trigger).findFirst().orElse(null);
    }

    public static String pinForTrigger(String trigger) {
        if (trigger == null) return null;
        return TRIGGERS.values().stream().flatMap(List::stream).filter(entry -> entry.trigger().equalsIgnoreCase(trigger)).map(Trigger::pin).findFirst().orElse(null);
    }

    public static List<Trigger> triggersForType(String type) {
        return TRIGGERS.getOrDefault(normalizeType(type), List.of());
    }

    public static String normalizeType(String type) {
        String normalized = type != null ? type.toLowerCase(Locale.ROOT) : "item";
        return switch (normalized) {
            case "block", "armor", "projectile" -> normalized;
            default -> "item";
        };
    }

    public record Trigger(String pin, String trigger) {
    }
}
