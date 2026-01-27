package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class PlayerActionNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("player_sprint", "Sprint", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_sneak", "Sneak", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_fly", "Fly", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_gamemode", "Gamemode", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("mode", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_vanish", "Vanish", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_glowing", "Glowing", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_invulnerable", "Invulnerable", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_food_level", "Food Level", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("level", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_saturation", "Saturation", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("saturation", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_exhaustion", "Exhaustion", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("exhaustion", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_health", "Health", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("health", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_max_health", "Max Health", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("max_health", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_absorption", "Absorption", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("absorption", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_xp", "XP", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("level", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("points", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_tp", "Teleport", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("yaw", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_launch", "Launch", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("vx", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("vy", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("vz", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_push", "Push", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("strength", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_spin", "Spin", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("yaw", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_allow_flight", "Allow Flight", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("allowed", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_deny_flight", "Deny Flight", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_set_walk_speed", "Walk Speed", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("speed", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_set_fly_speed", "Fly Speed", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("speed", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_freeze", "Freeze", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_unfreeze", "Unfreeze", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_set_fire_ticks", "Fire Ticks", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_set_air_ticks", "Air Ticks", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_set_no_damage_ticks", "No Damage Ticks", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_add_potion", "Add Potion", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("effect_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("duration_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("amplifier", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_clear_potions", "Clear Potions", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_has_potion", "Has Potion", NodeDefinition.NodeCategory.LOGIC)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("effect_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("has_effect", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("amplifier", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("player_send_resourcepack", "Send Resource Pack", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("force", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_set_compass_target", "Set Compass Target", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_reset_compass", "Reset Compass", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_exp_level", "Get XP Level", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("level", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_exp_to_level", "Get XP To Level", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("exp_needed", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("player_get_total_exp", "Get Total XP", NodeDefinition.NodeCategory.DATA)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .output("total_exp", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("player_set_exp", "Set XP", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("exp", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_give_exp", "Give XP", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("exp", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_show_bossbar", "Show Boss Bar", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("title", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("progress", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("color", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("style", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("bossbar_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("player_hide_bossbar", "Hide Boss Bar", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("bossbar_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_update_bossbar", "Update Boss Bar", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("bossbar_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("new_title", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("new_progress", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());


        registry.register(new NodeDefinition.Builder("player_set_saturation", "Set Saturation", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("saturation", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_set_food_level", "Set Food Level", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("player", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("level", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_execute_command", "Execute Player Command", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("command", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("as_op", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("player_chat", "Chat", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("player_say", "Say", NodeDefinition.NodeCategory.ACTION)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());
    }
}
