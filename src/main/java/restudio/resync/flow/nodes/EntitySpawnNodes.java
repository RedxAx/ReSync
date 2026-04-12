package restudio.resync.flow.nodes;

import restudio.resync.Log;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.List;

public class EntitySpawnNodes {

    @DefineNode(id = "entity_spawn", displayName = "Spawn Entity", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "entity_type", dataType = FlowType.STRING), @FlowPin(name = "location", dataType = FlowType.LOCATION)},
            outputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySpawn(FlowContext ctx, FlowNode node) {
        String entityType = ctx.getInputValue(node, "entity_type", String.class, "ZOMBIE");
        Location location = ctx.getInputValue(node, "location", Location.class, null);
        if (location == null || location.getWorld() == null) {
            ctx.triggerOutput("flow");
            return;
        }
        Entity spawned = null;
        try {
            spawned = location.getWorld().spawnEntity(location, EntityType.valueOf(entityType.toUpperCase()));
        } catch (IllegalArgumentException e) {
            Log.warn("[Flow] Invalid entity type: " + entityType);
        }
        ctx.setOutput(node, "entity", spawned);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_despawn", displayName = "Despawn Entity", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityDespawn(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity != null) {
            entity.remove();
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_get_nearby", displayName = "Nearby Entities", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "center", dataType = FlowType.LOCATION), @FlowPin(name = "radius", dataType = FlowType.NUMBER), @FlowPin(name = "entity_type", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "entities", dataType = FlowType.LIST), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityGetNearby(FlowContext ctx, FlowNode node) {
        Location center = ctx.getInputValue(node, "center", Location.class, null);
        Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
        String typeFilter = ctx.getInputValue(node, "entity_type", String.class, null);
        List<Entity> entities = new ArrayList<>();
        if (center != null && center.getWorld() != null) {
            entities.addAll(center.getWorld().getNearbyEntities(center, radius, radius, radius));
            if (typeFilter != null) {
                try {
                    EntityType filterType = EntityType.valueOf(typeFilter.toUpperCase());
                    entities.removeIf(entity -> entity.getType() != filterType);
                } catch (IllegalArgumentException e) {
                    Log.warn("[Flow] Invalid entity type filter: " + typeFilter);
                }
            }
        }
        ctx.setOutput(node, "entities", entities);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_get_all", displayName = "All Entities", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "entity_type", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "entities", dataType = FlowType.LIST), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityGetAll(FlowContext ctx, FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class, null);
        String typeFilter = ctx.getInputValue(node, "entity_type", String.class, null);
        List<Entity> entities = new ArrayList<>();
        if (world != null) {
            if (typeFilter == null) {
                entities.addAll(world.getEntities());
            } else {
                try {
                    EntityType filterType = EntityType.valueOf(typeFilter.toUpperCase());
                    for (Entity entity : world.getEntities()) {
                        if (entity.getType() == filterType) {
                            entities.add(entity);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    Log.warn("[Flow] Invalid entity type filter: " + typeFilter);
                }
            }
        }
        ctx.setOutput(node, "entities", entities);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_teleport", displayName = "Teleport Entity", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "entity", dataType = FlowType.ENTITY), @FlowPin(name = "location", dataType = FlowType.LOCATION)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityTeleport(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Location location = ctx.getInputValue(node, "location", Location.class, null);
        if (entity != null && location != null) {
            entity.teleport(location);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_remove", displayName = "Remove Entity", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityRemove(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity != null) {
            entity.remove();
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_get_player_nearby", displayName = "Nearby Players", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "center", dataType = FlowType.LOCATION), @FlowPin(name = "radius", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "players", dataType = FlowType.LIST), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityGetPlayerNearby(FlowContext ctx, FlowNode node) {
        Location center = ctx.getInputValue(node, "center", Location.class, null);
        Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
        List<Player> players = new ArrayList<>();
        if (center != null && center.getWorld() != null) {
            for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (entity instanceof Player player) {
                    players.add(player);
                }
            }
        }
        ctx.setOutput(node, "players", players);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_get_mob_nearby", displayName = "Nearby Mobs", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "center", dataType = FlowType.LOCATION), @FlowPin(name = "radius", dataType = FlowType.NUMBER), @FlowPin(name = "entity_type", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "mobs", dataType = FlowType.LIST), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityGetMobNearby(FlowContext ctx, FlowNode node) {
        Location center = ctx.getInputValue(node, "center", Location.class, null);
        Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
        String typeFilter = ctx.getInputValue(node, "entity_type", String.class, null);
        List<Entity> mobs = new ArrayList<>();
        if (center != null && center.getWorld() != null) {
            EntityType filterType = null;
            if (typeFilter != null) {
                try {
                    filterType = EntityType.valueOf(typeFilter.toUpperCase());
                } catch (IllegalArgumentException e) {
                    Log.warn("[Flow] Invalid entity type filter: " + typeFilter);
                }
            }
            for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (entity instanceof Player) {
                    continue;
                }
                if (filterType == null || entity.getType() == filterType) {
                    mobs.add(entity);
                }
            }
        }
        ctx.setOutput(node, "mobs", mobs);
        ctx.triggerOutput("flow");
    }
}
