package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.ArrayList;
import java.util.List;

public class EntitySpawnNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("entity_spawn", (ctx, node) -> {
            Object entityTypeObj = ctx.getInputValue(node, "entity_type", String.class, "ZOMBIE");
            Object locationObj = ctx.getInputValue(node, "location", Location.class, null);
            
            if (locationObj == null) {
                ctx.triggerOutput("flow");
                return;
            }
            
            Location location = (Location)locationObj;
            World world = location.getWorld();
            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }
            
            Entity spawned = null;
            try {
                EntityType type = EntityType.valueOf(((String)entityTypeObj).toUpperCase());
                spawned = world.spawnEntity(location, type);
            } catch (IllegalArgumentException e) {
                Bukkit.getLogger().warning("[Flow] Invalid entity type: " + entityTypeObj);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "entity", spawned);
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_despawn", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null) {
                ((Entity)entityObj).remove();
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_get_nearby", (ctx, node) -> {
            Object locationObj = ctx.getInputValue(node, "center", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            Object entityTypeObj = ctx.getInputValue(node, "entity_type", String.class, null);
            
            List<Entity> entities = new ArrayList<>();
            
            if (locationObj != null) {
                Location center = (Location)locationObj;
                World world = center.getWorld();
                if (world != null) {
                    entities.addAll(world.getNearbyEntities(center, radius, radius, radius));
                    
                    if (entityTypeObj != null) {
                        try {
                            EntityType filterType = EntityType.valueOf(((String)entityTypeObj).toUpperCase());
                            entities.removeIf(entity -> entity.getType() != filterType);
                        } catch (IllegalArgumentException e) {
                            Bukkit.getLogger().warning("[Flow] Invalid entity type filter: " + entityTypeObj);
                        }
                    }
                }
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "entities", entities);
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_get_all", (ctx, node) -> {
            Object worldObj = ctx.getInputValue(node, "world", World.class, null);
            Object entityTypeObj = ctx.getInputValue(node, "entity_type", String.class, null);
            
            List<Entity> entities = new ArrayList<>();
            
            if (worldObj != null) {
                World world = (World)worldObj;
                for (Entity entity : world.getEntities()) {
                    if (entityTypeObj == null) {
                        entities.add(entity);
                    } else {
                        try {
                            EntityType filterType = EntityType.valueOf(((String)entityTypeObj).toUpperCase());
                            if (entity.getType() == filterType) {
                                entities.add(entity);
                            }
                        } catch (IllegalArgumentException e) {
                            Bukkit.getLogger().warning("[Flow] Invalid entity type filter: " + entityTypeObj);
                        }
                    }
                }
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "entities", entities);
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_teleport", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object locationObj = ctx.getInputValue(node, "location", Location.class, null);
            
            if (entityObj != null && locationObj != null) {
                ((Entity)entityObj).teleport((Location)locationObj);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_remove", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null) {
                ((Entity)entityObj).remove();
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_get_player_nearby", (ctx, node) -> {
            Object locationObj = ctx.getInputValue(node, "center", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            
            List<Player> players = new ArrayList<>();
            
            if (locationObj != null) {
                Location center = (Location)locationObj;
                World world = center.getWorld();
                if (world != null) {
                    for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                        if (entity instanceof Player) {
                            players.add((Player)entity);
                        }
                    }
                }
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "players", players);
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_get_mob_nearby", (ctx, node) -> {
            Object locationObj = ctx.getInputValue(node, "center", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            Object entityTypeObj = ctx.getInputValue(node, "entity_type", String.class, null);
            
            List<Entity> mobs = new ArrayList<>();
            
            if (locationObj != null) {
                Location center = (Location)locationObj;
                World world = center.getWorld();
                if (world != null) {
                    for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                        if (!(entity instanceof Player)) {
                            if (entityTypeObj == null) {
                                mobs.add(entity);
                            } else {
                                try {
                                    EntityType filterType = EntityType.valueOf(((String)entityTypeObj).toUpperCase());
                                    if (entity.getType() == filterType) {
                                        mobs.add(entity);
                                    }
                                } catch (IllegalArgumentException e) {
                                    Bukkit.getLogger().warning("[Flow] Invalid entity type filter: " + entityTypeObj);
                                }
                            }
                        }
                    }
                }
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "mobs", mobs);
            ctx.triggerOutput("flow");
        });
    }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
