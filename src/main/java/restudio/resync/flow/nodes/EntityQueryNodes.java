package restudio.resync.flow.nodes;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class EntityQueryNodes implements NodeCategory {
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("entity_is_alive", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            boolean isValid = entity != null && entity.isValid();
            boolean isDead = entity == null || entity.isDead();
            boolean isAlive = isValid && !isDead;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "is_alive", isAlive);
            ctx.setNodeOutput(nodeId, "is_valid", isValid);
            ctx.setNodeOutput(nodeId, "is_dead", isDead);
        });

        registry.register("entity_get_info", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String nodeId = findNodeId(ctx, node);
            if (entity == null) {
                ctx.setNodeOutput(nodeId, "entity_type", "");
                ctx.setNodeOutput(nodeId, "uuid", "");
                ctx.setNodeOutput(nodeId, "name", "");
                ctx.setNodeOutput(nodeId, "custom_name", "");
                ctx.setNodeOutput(nodeId, "location", null);
                ctx.setNodeOutput(nodeId, "world_name", "");
                ctx.setNodeOutput(nodeId, "ticks_lived", 0);
                ctx.setNodeOutput(nodeId, "is_dead", true);
                ctx.setNodeOutput(nodeId, "is_valid", false);
                return;
            }
            Location location = entity.getLocation();
            ctx.setNodeOutput(nodeId, "entity_type", entity.getType().name());
            ctx.setNodeOutput(nodeId, "uuid", entity.getUniqueId().toString());
            ctx.setNodeOutput(nodeId, "name", entity.getName());
            ctx.setNodeOutput(nodeId, "custom_name", entity.getCustomName());
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "world_name", location.getWorld() != null ? location.getWorld().getName() : "");
            ctx.setNodeOutput(nodeId, "ticks_lived", entity.getTicksLived());
            boolean isValid = entity.isValid();
            ctx.setNodeOutput(nodeId, "is_dead", entity.isDead() || !isValid);
            ctx.setNodeOutput(nodeId, "is_valid", isValid);
        });

        registry.register("entity_get_health", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String nodeId = findNodeId(ctx, node);
            if (entity instanceof LivingEntity living) {
                double maxHealth = 0.0;
                if (living.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    maxHealth = living.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
                }
                ctx.setNodeOutput(nodeId, "health", living.getHealth());
                ctx.setNodeOutput(nodeId, "max_health", maxHealth);
                ctx.setNodeOutput(nodeId, "absorption", living.getAbsorptionAmount());
                return;
            }
            ctx.setNodeOutput(nodeId, "health", 0.0);
            ctx.setNodeOutput(nodeId, "max_health", 0.0);
            ctx.setNodeOutput(nodeId, "absorption", 0.0);
        });

        registry.register("entity_get_velocity", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String nodeId = findNodeId(ctx, node);
            Vector velocity = entity != null ? entity.getVelocity() : null;
            ctx.setNodeOutput(nodeId, "velocity", velocity);
        });

        registry.register("entity_get_fire_ticks", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "fire_ticks", entity != null ? entity.getFireTicks() : 0);
        });

        registry.register("entity_get_freeze_ticks", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "freeze_ticks", entity != null ? entity.getFreezeTicks() : 0);
        });

        registry.register("entity_get_last_damage", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String nodeId = findNodeId(ctx, node);
            if (!(entity instanceof LivingEntity living)) {
                ctx.setNodeOutput(nodeId, "damage", 0.0);
                ctx.setNodeOutput(nodeId, "cause", "");
                ctx.setNodeOutput(nodeId, "damager", null);
                return;
            }
            EntityDamageEvent damageEvent = living.getLastDamageCause();
            double damage = 0.0;
            String cause = "";
            Entity damager = null;
            if (damageEvent != null) {
                damage = damageEvent.getDamage();
                cause = damageEvent.getCause().name();
                if (damageEvent instanceof EntityDamageByEntityEvent byEntityEvent) {
                    damager = byEntityEvent.getDamager();
                }
            }
            ctx.setNodeOutput(nodeId, "damage", damage);
            ctx.setNodeOutput(nodeId, "cause", cause);
            ctx.setNodeOutput(nodeId, "damager", damager);
        });

        registry.register("entity_get_location", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "location", entity != null ? entity.getLocation() : null);
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
