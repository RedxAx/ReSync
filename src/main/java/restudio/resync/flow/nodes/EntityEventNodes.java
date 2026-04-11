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
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class EntityEventNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void registerLegacyNodes(FlowRegistry registry) {
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

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (EntityEventNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("next");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "event:entity_spawn", displayName = "On Entity Spawn", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "entity_type", dataType = FlowType.STRING)
            })
    public void onEntitySpawn(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_spawn", ctx, node);
    }

    @DefineNode(id = "event:entity_target", displayName = "On Entity Target", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "target", dataType = FlowType.ENTITY)
            })
    public void onEntityTarget(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_target", ctx, node);
    }

    @DefineNode(id = "event:entity_breed", displayName = "On Entity Breed", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity1", dataType = FlowType.ENTITY),
                    @FlowPin(name = "entity2", dataType = FlowType.ENTITY),
                    @FlowPin(name = "experience", dataType = FlowType.NUMBER),
                    @FlowPin(name = "bred_entity", dataType = FlowType.ENTITY)
            })
    public void onEntityBreed(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_breed", ctx, node);
    }

    @DefineNode(id = "event:entity_tame", displayName = "On Entity Tame", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "tamer", dataType = FlowType.ENTITY),
                    @FlowPin(name = "entity_type", dataType = FlowType.STRING)
            })
    public void onEntityTame(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_tame", ctx, node);
    }

    @DefineNode(id = "event:entity_transform", displayName = "On Entity Transform", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "old_entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "new_entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "new_entity_type", dataType = FlowType.STRING)
            })
    public void onEntityTransform(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_transform", ctx, node);
    }

    @DefineNode(id = "event:entity_death", displayName = "On Entity Death", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "killer", dataType = FlowType.ENTITY)
            })
    public void onEntityDeath(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_death", ctx, node);
    }

    @DefineNode(id = "event:item_merge", displayName = "On Item Merge", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "item1", dataType = FlowType.ENTITY),
                    @FlowPin(name = "item2", dataType = FlowType.ENTITY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "result", dataType = FlowType.ITEMSTACK)
            })
    public void onItemMerge(FlowContext ctx, FlowNode node) {
        executeLegacy("event:item_merge", ctx, node);
    }

    @DefineNode(id = "event:chunk_load", displayName = "On Chunk Load", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "chunk", dataType = FlowType.ANY),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "chunk_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "chunk_z", dataType = FlowType.NUMBER)
            })
    public void onChunkLoad(FlowContext ctx, FlowNode node) {
        executeLegacy("event:chunk_load", ctx, node);
    }

    @DefineNode(id = "event:chunk_unload", displayName = "On Chunk Unload", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "chunk", dataType = FlowType.ANY),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "chunk_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "chunk_z", dataType = FlowType.NUMBER)
            })
    public void onChunkUnload(FlowContext ctx, FlowNode node) {
        executeLegacy("event:chunk_unload", ctx, node);
    }

    @DefineNode(id = "event:entity_combust", displayName = "On Entity Combust", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "duration", dataType = FlowType.NUMBER)
            })
    public void onEntityCombust(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_combust", ctx, node);
    }

    @DefineNode(id = "event:entity_damaged", displayName = "On Entity Damaged", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "damager", dataType = FlowType.ENTITY),
                    @FlowPin(name = "damage", dataType = FlowType.NUMBER),
                    @FlowPin(name = "cause", dataType = FlowType.STRING)
            })
    public void onEntityDamaged(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_damaged", ctx, node);
    }

    @DefineNode(id = "event:entity_heal", displayName = "On Entity Heal", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER)
            })
    public void onEntityHeal(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_heal", ctx, node);
    }

    @DefineNode(id = "event:entity_regain_health", displayName = "On Entity Regain Health", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "amount", dataType = FlowType.NUMBER),
                    @FlowPin(name = "new_health", dataType = FlowType.NUMBER)
            })
    public void onEntityRegainHealth(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_regain_health", ctx, node);
    }

    @DefineNode(id = "event:entity_pickup", displayName = "On Entity Pickup", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "item", dataType = FlowType.ENTITY),
                    @FlowPin(name = "remaining", dataType = FlowType.NUMBER)
            })
    public void onEntityPickup(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_pickup", ctx, node);
    }

    @DefineNode(id = "event:entity_drop", displayName = "On Entity Drop", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "dropped", dataType = FlowType.ENTITY)
            })
    public void onEntityDrop(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_drop", ctx, node);
    }

    @DefineNode(id = "event:entity_enter_portal", displayName = "On Entity Enter Portal", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "portal_type", dataType = FlowType.STRING)
            })
    public void onEntityEnterPortal(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_enter_portal", ctx, node);
    }

    @DefineNode(id = "event:entity_exit_portal", displayName = "On Entity Exit Portal", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "portal_type", dataType = FlowType.STRING)
            })
    public void onEntityExitPortal(FlowContext ctx, FlowNode node) {
        executeLegacy("event:entity_exit_portal", ctx, node);
    }
}
