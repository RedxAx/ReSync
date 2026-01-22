package restudio.resync.flow.nodes;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class EntityEventNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("event:entity_spawn", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity = (Entity) ctx.getVariable("event.entity");
            Location location = (Location) ctx.getVariable("event.location");
            String entityType = (String) ctx.getVariable("event.entity_type");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "entity_type", entityType);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_target", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity = (Entity) ctx.getVariable("event.entity");
            Entity target = (Entity) ctx.getVariable("event.target");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "target", target);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_breed", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity1 = (Entity) ctx.getVariable("event.entity1");
            Entity entity2 = (Entity) ctx.getVariable("event.entity2");
            Integer experience = (Integer) ctx.getVariable("event.experience");
            Entity bredEntity = (Entity) ctx.getVariable("event.bred_entity");

            ctx.setNodeOutput(nodeId, "entity1", entity1);
            ctx.setNodeOutput(nodeId, "entity2", entity2);
            ctx.setNodeOutput(nodeId, "experience", experience);
            ctx.setNodeOutput(nodeId, "bred_entity", bredEntity);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_tame", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity = (Entity) ctx.getVariable("event.entity");
            Entity tamer = (Entity) ctx.getVariable("event.tamer");
            String entityType = (String) ctx.getVariable("event.entity_type");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "tamer", tamer);
            ctx.setNodeOutput(nodeId, "entity_type", entityType);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_transform", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity oldEntity = (Entity) ctx.getVariable("event.old_entity");
            Entity newEntity = (Entity) ctx.getVariable("event.new_entity");
            String newEntityType = (String) ctx.getVariable("event.new_entity_type");

            ctx.setNodeOutput(nodeId, "old_entity", oldEntity);
            ctx.setNodeOutput(nodeId, "new_entity", newEntity);
            ctx.setNodeOutput(nodeId, "new_entity_type", newEntityType);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_death", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity = (Entity) ctx.getVariable("event.entity");
            Entity killer = (Entity) ctx.getVariable("event.killer");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "killer", killer);
            ctx.triggerOutput("next");
        });

        registry.register("event:item_merge", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Item item1 = (Item) ctx.getVariable("event.item1");
            Item item2 = (Item) ctx.getVariable("event.item2");
            Location location = (Location) ctx.getVariable("event.location");
            ItemStack result = (ItemStack) ctx.getVariable("event.result");

            ctx.setNodeOutput(nodeId, "item1", item1);
            ctx.setNodeOutput(nodeId, "item2", item2);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "result", result);
            ctx.triggerOutput("next");
        });

        registry.register("event:chunk_load", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Chunk chunk = (Chunk) ctx.getVariable("event.chunk");
            String worldName = (String) ctx.getVariable("event.world_name");
            Integer x = (Integer) ctx.getVariable("event.chunk_x");
            Integer z = (Integer) ctx.getVariable("event.chunk_z");

            ctx.setNodeOutput(nodeId, "chunk", chunk);
            ctx.setNodeOutput(nodeId, "world_name", worldName);
            ctx.setNodeOutput(nodeId, "chunk_x", x);
            ctx.setNodeOutput(nodeId, "chunk_z", z);
            ctx.triggerOutput("next");
        });

        registry.register("event:chunk_unload", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Chunk chunk = (Chunk) ctx.getVariable("event.chunk");
            String worldName = (String) ctx.getVariable("event.world_name");
            Integer x = (Integer) ctx.getVariable("event.chunk_x");
            Integer z = (Integer) ctx.getVariable("event.chunk_z");

            ctx.setNodeOutput(nodeId, "chunk", chunk);
            ctx.setNodeOutput(nodeId, "world_name", worldName);
            ctx.setNodeOutput(nodeId, "chunk_x", x);
            ctx.setNodeOutput(nodeId, "chunk_z", z);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_combust", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity = (Entity) ctx.getVariable("event.entity");
            Integer duration = (Integer) ctx.getVariable("event.duration");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "duration", duration);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_damaged", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity = (Entity) ctx.getVariable("event.entity");
            Entity damager = (Entity) ctx.getVariable("event.damager");
            Double damage = (Double) ctx.getVariable("event.damage");
            String cause = (String) ctx.getVariable("event.cause");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "damager", damager);
            ctx.setNodeOutput(nodeId, "damage", damage);
            ctx.setNodeOutput(nodeId, "cause", cause);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_heal", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity = (Entity) ctx.getVariable("event.entity");
            Double amount = (Double) ctx.getVariable("event.amount");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "amount", amount);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_regain_health", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            LivingEntity entity = (LivingEntity) ctx.getVariable("event.entity");
            Double amount = (Double) ctx.getVariable("event.amount");
            Double newHealth = (Double) ctx.getVariable("event.new_health");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "amount", amount);
            ctx.setNodeOutput(nodeId, "new_health", newHealth);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_pickup", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            LivingEntity entity = (LivingEntity) ctx.getVariable("event.entity");
            Item item = (Item) ctx.getVariable("event.item");
            Integer remaining = (Integer) ctx.getVariable("event.remaining");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.setNodeOutput(nodeId, "remaining", remaining);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_drop", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            LivingEntity entity = (LivingEntity) ctx.getVariable("event.entity");
            Item dropped = (Item) ctx.getVariable("event.dropped");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "dropped", dropped);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_enter_portal", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity = (Entity) ctx.getVariable("event.entity");
            Location location = (Location) ctx.getVariable("event.location");
            String portalType = (String) ctx.getVariable("event.portal_type");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "portal_type", portalType);
            ctx.triggerOutput("next");
        });

        registry.register("event:entity_exit_portal", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Entity entity = (Entity) ctx.getVariable("event.entity");
            Location location = (Location) ctx.getVariable("event.location");
            String portalType = (String) ctx.getVariable("event.portal_type");

            ctx.setNodeOutput(nodeId, "entity", entity);
            ctx.setNodeOutput(nodeId, "location", location);
            ctx.setNodeOutput(nodeId, "portal_type", portalType);
            ctx.triggerOutput("next");
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
