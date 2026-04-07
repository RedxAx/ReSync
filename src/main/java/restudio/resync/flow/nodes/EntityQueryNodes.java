package restudio.resync.flow.nodes;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

public class EntityQueryNodes {

    @DefineNode(id = "entity_is_alive", displayName = "Entity Is Alive", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {
                    @FlowPin(name = "is_alive", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "is_valid", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "is_dead", dataType = FlowType.BOOLEAN)
            })
    public void entityIsAlive(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        boolean isValid = entity != null && entity.isValid();
        boolean isDead = entity == null || entity.isDead();
        boolean isAlive = isValid && !isDead;
        ctx.setOutput(node, "is_alive", isAlive);
        ctx.setOutput(node, "is_valid", isValid);
        ctx.setOutput(node, "is_dead", isDead);
    }

    @DefineNode(id = "entity_get_info", displayName = "Entity Get Info", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {
                    @FlowPin(name = "entity_type", dataType = FlowType.STRING),
                    @FlowPin(name = "uuid", dataType = FlowType.STRING),
                    @FlowPin(name = "name", dataType = FlowType.STRING),
                    @FlowPin(name = "custom_name", dataType = FlowType.STRING),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "ticks_lived", dataType = FlowType.NUMBER),
                    @FlowPin(name = "is_dead", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "is_valid", dataType = FlowType.BOOLEAN)
            })
    public void entityGetInfo(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity == null) {
            ctx.setOutput(node, "entity_type", "");
            ctx.setOutput(node, "uuid", "");
            ctx.setOutput(node, "name", "");
            ctx.setOutput(node, "custom_name", "");
            ctx.setOutput(node, "location", null);
            ctx.setOutput(node, "world_name", "");
            ctx.setOutput(node, "ticks_lived", 0);
            ctx.setOutput(node, "is_dead", true);
            ctx.setOutput(node, "is_valid", false);
            return;
        }
        Location location = entity.getLocation();
        ctx.setOutput(node, "entity_type", entity.getType().name());
        ctx.setOutput(node, "uuid", entity.getUniqueId().toString());
        ctx.setOutput(node, "name", entity.getName());
        ctx.setOutput(node, "custom_name", entity.getCustomName());
        ctx.setOutput(node, "location", location);
        ctx.setOutput(node, "world_name", location.getWorld() != null ? location.getWorld().getName() : "");
        ctx.setOutput(node, "ticks_lived", entity.getTicksLived());
        boolean isValid = entity.isValid();
        ctx.setOutput(node, "is_dead", entity.isDead() || !isValid);
        ctx.setOutput(node, "is_valid", isValid);
    }

    @DefineNode(id = "entity_get_health", displayName = "Entity Get Health", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {
                    @FlowPin(name = "health", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max_health", dataType = FlowType.NUMBER),
                    @FlowPin(name = "absorption", dataType = FlowType.NUMBER)
            })
    public void entityGetHealth(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity instanceof LivingEntity living) {
            double maxHealth = 0.0;
            if (living.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                maxHealth = living.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            }
            ctx.setOutput(node, "health", living.getHealth());
            ctx.setOutput(node, "max_health", maxHealth);
            ctx.setOutput(node, "absorption", living.getAbsorptionAmount());
            return;
        }
        ctx.setOutput(node, "health", 0.0);
        ctx.setOutput(node, "max_health", 0.0);
        ctx.setOutput(node, "absorption", 0.0);
    }

    @DefineNode(id = "entity_get_velocity", displayName = "Entity Get Velocity", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "velocity", dataType = FlowType.ANY)})
    public void entityGetVelocity(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Vector velocity = entity != null ? entity.getVelocity() : null;
        ctx.setOutput(node, "velocity", velocity);
    }

    @DefineNode(id = "entity_get_fire_ticks", displayName = "Entity Get Fire Ticks", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "fire_ticks", dataType = FlowType.NUMBER)})
    public void entityGetFireTicks(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        ctx.setOutput(node, "fire_ticks", entity != null ? entity.getFireTicks() : 0);
    }

    @DefineNode(id = "entity_get_freeze_ticks", displayName = "Entity Get Freeze Ticks", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "freeze_ticks", dataType = FlowType.NUMBER)})
    public void entityGetFreezeTicks(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        ctx.setOutput(node, "freeze_ticks", entity != null ? entity.getFreezeTicks() : 0);
    }

    @DefineNode(id = "entity_get_last_damage", displayName = "Entity Get Last Damage", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {
                    @FlowPin(name = "damage", dataType = FlowType.NUMBER),
                    @FlowPin(name = "cause", dataType = FlowType.STRING),
                    @FlowPin(name = "damager", dataType = FlowType.ENTITY)
            })
    public void entityGetLastDamage(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (!(entity instanceof LivingEntity living)) {
            ctx.setOutput(node, "damage", 0.0);
            ctx.setOutput(node, "cause", "");
            ctx.setOutput(node, "damager", null);
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
        ctx.setOutput(node, "damage", damage);
        ctx.setOutput(node, "cause", cause);
        ctx.setOutput(node, "damager", damager);
    }

    @DefineNode(id = "entity_get_location", displayName = "Entity Get Location", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "location", dataType = FlowType.LOCATION)})
    public void entityGetLocation(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        ctx.setOutput(node, "location", entity != null ? entity.getLocation() : null);
    }
}
