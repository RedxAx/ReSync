package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class WorldStateNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("world_set_time", "Set Time", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("time", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_weather", "Set Weather", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("weather", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_thunder", "Set Thunder", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("thundering", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_spawn", "Set Spawn", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_difficulty", "Set Difficulty", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("difficulty", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_pvp", "Set PVP", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("pvp", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_keep_spawn", "Set Keep Spawn", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("keep_spawn_time", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_auto_save", "Set Auto Save", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("auto_save", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_spawn_lightning", "Spawn Lightning", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("effect", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_border_size", "Set Border Size", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("size", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_border_damage", "Set Border Damage", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("damage_amount", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_border_warning", "Set Border Warning", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("warning_distance", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_create_world", "Create World", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("seed", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("environment", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("generator", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_scan_worlds", "Scan Worlds", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_import_worlds", "Import Worlds", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_clone_world", "Clone World", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("source_world", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("target_world", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("load_after_clone", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_load_world", "Load World", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_unload_world", "Unload World", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("fallback_world", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_delete_world", "Delete World", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("delete_files", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("fallback_world", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_set_rule", "Set World Rule", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("rule_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("value", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_set_difficulty", "Set World Difficulty", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("difficulty", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_set_time_lock", "Set Time Lock", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("locked_time", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_set_weather_lock", "Set Weather Lock", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("storm", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .input("thundering", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_set_isolated_state", "Set Isolated State", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_create_portal", "Create Portal", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("portal_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("source_world", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("min_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("min_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("min_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("max_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("max_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("max_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("destination_world", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("destination_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("destination_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("destination_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("destination_yaw", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("destination_pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_delete_portal", "Delete Portal", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_set_portal_enabled", "Set Portal Enabled", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_set_portal_destination", "Set Portal Destination", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("destination_world", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("destination_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("destination_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("destination_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("destination_yaw", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("destination_pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_set_portal_bounds", "Set Portal Bounds", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("source_world", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("min_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("min_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("min_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("max_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("max_y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("max_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_teleport_player_to_world", "Teleport Player To World", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("yaw", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pitch", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_teleport_player_to_world_spawn", "Teleport Player To World Spawn", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_teleport_player_to_portal", "Teleport Player To Portal", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("target", NodeDefinition.PinType.DATA, FlowType.PLAYER)
            .input("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("message", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());
    }
}
