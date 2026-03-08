package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class WorldNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("world_get_by_name", "World Get By Name", NodeDefinition.NodeCategory.DATA)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("world_get_all", "World Get All", NodeDefinition.NodeCategory.DATA)
            .output("worlds_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_time", "World Set Time", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("time_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_get_time", "World Get Time", NodeDefinition.NodeCategory.DATA)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("time_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_full_time", "World Set Full Time", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("full_time_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_get_full_time", "World Get Full Time", NodeDefinition.NodeCategory.DATA)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("full_time_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_day_time", "World Set Day Time", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("time", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_weather", "World Set Weather", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("weather_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("duration_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_get_weather", "World Get Weather", NodeDefinition.NodeCategory.DATA)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("weather_type", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("thundering", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("has_storm", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("world_spawn_set", "World Spawn Set", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_spawn_get", "World Spawn Get", NodeDefinition.NodeCategory.DATA)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("spawn_location", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_difficulty", "World Set Difficulty", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("difficulty", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_get_difficulty", "World Get Difficulty", NodeDefinition.NodeCategory.DATA)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("difficulty", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_pvp", "World Set PVP", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("pvp_enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_get_pvp", "World Get PVP", NodeDefinition.NodeCategory.DATA)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("pvp_enabled", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("world_save", "World Save", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_auto_save_set", "World Auto Save Set", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("interval_ticks", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_set_spawn_limits", "World Set Spawn Limits", NodeDefinition.NodeCategory.WORLD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .input("monsters", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("animals", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("water_ambient", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("water_animals", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("axolotls", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("water_underground", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_get_snapshot", "World Snapshot", NodeDefinition.NodeCategory.DATA)
            .output("snapshot", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_get_worlds", "World Entries", NodeDefinition.NodeCategory.DATA)
            .output("worlds", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_get_world", "World Entry", NodeDefinition.NodeCategory.DATA)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("world", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_get_portals", "World Portals", NodeDefinition.NodeCategory.DATA)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("portals", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_get_portal", "World Portal", NodeDefinition.NodeCategory.DATA)
            .input("portal_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("portal", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_get_game_rules", "World Game Rules", NodeDefinition.NodeCategory.DATA)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("game_rules", NodeDefinition.PinType.DATA, FlowType.MAP)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_get_game_rule_descriptors", "World Game Rule Descriptors", NodeDefinition.NodeCategory.DATA)
            .output("descriptors", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("world_management_get_map_snapshot", "World Map Snapshot", NodeDefinition.NodeCategory.DATA)
            .input("world_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("center_x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("center_z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("zoom", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("snapshot", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());
    }
}
