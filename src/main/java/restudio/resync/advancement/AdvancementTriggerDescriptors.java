package restudio.resync.advancement;

import java.util.List;
import java.util.Set;

public final class AdvancementTriggerDescriptors {
    public static final Set<String> IDS = Set.of(
        "obtain_item", "consume_item", "using_item", "place_block", "break_block", "place_furniture", "break_furniture", "interact_furniture",
        "craft_recipe", "kill_entity_with_item", "player_hurt_entity", "entity_hurt_player", "player_killed_entity", "entity_killed_player",
        "killed_by_arrow", "tame_animal", "bred_animals", "villager_trade", "enchanted_item", "shoot_bow", "shot_crossbow",
        "fishing_rod_hooked", "filled_bucket", "item_durability_changed", "item_used_on_block", "recipe_crafted", "recipe_unlocked",
        "player_interacted_with_entity", "player_sheared_equipment", "bee_nest_destroyed", "started_riding", "effects_changed",
        "changed_dimension", "slept_in_bed", "used_totem", "fall_from_height", "used_ender_eye", "held_item", "permission", "in_biome", "impossible"
    );

    private AdvancementTriggerDescriptors() {
    }

    public static List<String> sorted() {
        return IDS.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
