package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class EntityAdvancedNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("entity_mount", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object mountEntityObj = ctx.getInputValue(node, "mount_entity", Entity.class, null);
            
            if (entityObj != null && mountEntityObj != null) {
                Entity entity = (Entity)entityObj;
                Entity mountEntity = (Entity)mountEntityObj;
                entity.teleport(mountEntity.getLocation());
                mountEntity.addPassenger(entity);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_dismount", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null) {
                Entity entity = (Entity)entityObj;
                if (entity.isInsideVehicle()) {
                    entity.leaveVehicle();
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_ai_disable", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof Mob) {
                ((Mob)entityObj).setAI(false);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_ai_enable", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof Mob) {
                ((Mob)entityObj).setAI(true);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_no_damage", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean noDamage = ctx.getInputValue(node, "no_damage", Boolean.class, false);
            
            if (entityObj != null) {
                ((Entity)entityObj).setInvulnerable(noDamage);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_silent", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean silent = ctx.getInputValue(node, "silent", Boolean.class, false);
            
            if (entityObj != null) {
                ((Entity)entityObj).setSilent(silent);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_add_potion", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object effectTypeObj = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
            Integer duration = ctx.getInputValue(node, "duration", Integer.class, 200);
            Integer amplifier = ctx.getInputValue(node, "amplifier", Integer.class, 0);
            
            if (entityObj != null && entityObj instanceof LivingEntity && effectTypeObj != null) {
                PotionEffectType effectType = PotionEffectType.getByName(((String)effectTypeObj).toUpperCase());
                if (effectType != null) {
                    PotionEffect effect = new PotionEffect(effectType, duration, amplifier);
                    ((LivingEntity)entityObj).addPotionEffect(effect);
                } else {
                    Bukkit.getLogger().warning("[Flow] Invalid potion effect type: " + effectTypeObj);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_clear_potions", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                living.getActivePotionEffects().stream().map(PotionEffect::getType).forEach(living::removePotionEffect);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_leash", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object holderEntityObj = ctx.getInputValue(node, "holder_entity", Entity.class, null);
            
            if (entityObj != null && holderEntityObj != null && entityObj instanceof LivingEntity && holderEntityObj instanceof LivingEntity) {
                ((LivingEntity)entityObj).setLeashHolder((LivingEntity)holderEntityObj);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_unleash", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                ((LivingEntity)entityObj).setLeashHolder(null);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_custom_name", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object nameObj = ctx.getInputValue(node, "name", String.class, "");
            
            if (entityObj != null) {
                Entity entity = (Entity)entityObj;
                entity.setCustomName((String)nameObj);
                entity.setCustomNameVisible(true);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_get_passengers", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            List<Entity> passengers = new ArrayList<>();
            if (entityObj != null) {
                passengers = ((Entity)entityObj).getPassengers();
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "passengers_list", passengers);
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_get_vehicle", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            Entity vehicle = null;
            if (entityObj != null) {
                vehicle = ((Entity)entityObj).getVehicle();
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "vehicle", vehicle);
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_fire_ticks", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
            
            if (entityObj != null) {
                ((Entity)entityObj).setFireTicks(ticks);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_frozen", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
            
            if (entityObj != null) {
                ((Entity)entityObj).setFreezeTicks(ticks);
            }
            
            ctx.triggerOutput("flow");
        });

        registry.register("entity_add_tag", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String tag = ctx.getInputValue(node, "tag", String.class, "");
            if (entity != null && !tag.isEmpty()) {
                entity.addScoreboardTag(tag);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("entity_remove_tag", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String tag = ctx.getInputValue(node, "tag", String.class, "");
            if (entity != null && !tag.isEmpty()) {
                entity.removeScoreboardTag(tag);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("entity_clear_tags", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity != null) {
                entity.getScoreboardTags().forEach(entity::removeScoreboardTag);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("entity_has_tag", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String tag = ctx.getInputValue(node, "tag", String.class, "");
            boolean hasTag = entity != null && !tag.isEmpty() && entity.getScoreboardTags().contains(tag);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has_tag", hasTag);
        });

        registry.register("entity_has_any_tag", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            List<String> tags = ctx.getInputValue(node, "tags", List.class, List.of());
            boolean hasAny = false;
            if (entity != null && tags != null && !tags.isEmpty()) {
                for (String tag : tags) {
                    if (entity.getScoreboardTags().contains(tag)) {
                        hasAny = true;
                        break;
                    }
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has_any", hasAny);
        });

        registry.register("entity_has_all_tags", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            List<String> tags = ctx.getInputValue(node, "tags", List.class, List.of());
            boolean hasAll = entity != null && tags != null && !tags.isEmpty() && entity.getScoreboardTags().containsAll(tags);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has_all", hasAll);
        });

        registry.register("entity_get_tags", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            List<String> tags = entity != null ? new ArrayList<>(entity.getScoreboardTags()) : List.of();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "tags", tags);
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
